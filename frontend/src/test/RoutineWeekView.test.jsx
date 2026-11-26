import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

/**
 * Nöbet Rutinleri — hafta görünümü (WeekView, 2026-11-26). Pinlenenler (sözleşme §9.6):
 *  - günler: kapsayıcı ≥ 760 px (jsdom genişliği 0) tablo, daha darda kart; durum rozeti, `tamamlanan/zorunlu`, sorun sayısı,
 *    nöbetçi (`responsible_by_day`; kendisiyse "Siz"), tamamlayan · saat + "geç" rozeti; iş günü olmayan gün "—";
 *  - "Günü aç" yalnız geçmiş / bugünkü iş gününde (gelecek ve tatil günü açılmaz);
 *  - önceki / sonraki hafta ±7 gün (Pazartesi), "Bu hafta" yalnız başka bir hafta seçiliyken;
 *  - haftalık / aylık madde buradan işaretlenir: bağlam günü = haftanın bugüne kadarki SON iş günü; aylık maddede yalnız
 *    maddenin ayına düşen günler (`weekContextDate`); uygun gün yoksa ya da çalışma izni yoksa işaretlenemez;
 *  - okuma hatası + yeniden dene; geç yanıt düşer; dönem görevi yoksa bilgi satırı; devir notu / önceki devir özeti.
 */
const toastMock = vi.hoisted(() => ({
  success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn(), action: vi.fn(), dismiss: vi.fn(),
}))
vi.mock('../components/ui/Toast.jsx', () => ({
  useToast: () => toastMock,
  ToastProvider: ({ children }) => children,
}))
vi.mock('../components/oncallroutine/useRoutineBadge.js', () => ({
  refreshRoutineBadge: vi.fn(),
  useRoutineBadge: () => null,
  fetchRoutineMe: vi.fn(),
  badgeOf: () => null,
  resetRoutineBadgeForTests: () => {},
  ROUTINE_BADGE_POLL_MS: 60_000,
  ROUTINE_BADGE_KICK_MS: 800,
}))
// jsdom yerleşim yapmaz: kapsayıcı genişliği testten verilir (0 = ölçülmedi → tablo, 400 = telefon → kartlar)
const widthBox = vi.hoisted(() => ({ w: 0 }))
vi.mock('../hooks/useElementWidth.js', async (orig) => ({
  ...(await orig()),
  useElementWidth: () => [() => {}, widthBox.w],
}))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  getRecentFailures: () => [],
  api: withApiFallback({
    oncallRoutines: {
      week: vi.fn(), setItemState: vi.fn(), bulkItemState: vi.fn(), saveItemNote: vi.fn(), saveHandoverNote: vi.fn(),
      ackHandover: vi.fn(), problems: vi.fn(), events: vi.fn(),
    },
  }),
}))

import { api } from '../api/client'
import { refreshRoutineBadge } from '../components/oncallroutine/useRoutineBadge.js'
import WeekView, { WEEK_TABLE_MIN_PX, weekContextDate } from '../components/oncallroutine/WeekView.jsx'
import { TR } from '../i18n/tr.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import {
  KEY, ME, MONTH_KEY, OTHER, PREV_WEEK_KEY, TEAM_ID, TODAY, WEEK_KEY, YESTERDAY,
  buildHandover, buildWeekResponse, deferred, fail, ok, serverItem, withItem,
} from './helpers/oncallRoutineFixtures.js'

const or = () => api.oncallRoutines
const OR_FNS = ['week', 'setItemState', 'bulkItemState', 'saveItemNote', 'saveHandoverNote', 'ackHandover', 'problems', 'events']
const tr = (k, ...a) => TR[k].replace(/\{(\d+)\}/g, (_, i) => String(a[Number(i)] ?? ''))
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]
const dayRow = (date) => document.querySelector(`[data-slot="or-week-row"][data-date="${date}"]`)
const taskRow = (key) => document.querySelector(`[data-slot="or-task"][data-key="${key}"]`)
const check = (key) => document.querySelector(`[data-slot="or-task-check"][data-key="${key}"]`)

/** Tek bir gün satırı (hafta yanıtı biçiminde). */
function mkDay(date, extra = {}) {
  return {
    date, workday: true, holiday_name: null, status: 'FUTURE', required: null, resolved: null, done: null, skipped: null,
    problems_opened: 0, responsible_name: null, completed_by_name: null, completed_at: null, completed_late: false,
    today: false, ...extra,
  }
}

/** Fikstür haftasının günlerinden birini değiştirilmiş kopyası. */
function weekWithDay(date, patch, over = {}) {
  const base = buildWeekResponse(over)
  return { ...base, days: base.days.map((d) => (d.date === date ? { ...d, ...patch } : d)) }
}

function renderWeek(props = {}) {
  const handlers = { onWeekChange: vi.fn(), onOpenDay: vi.fn(), onEditTemplate: vi.fn() }
  const all = { ...handlers, ...props }
  const utils = render(<WeekView teamId={TEAM_ID} week={null} meName={ME.name} {...all} />)
  return { ...utils, ...all }
}
const loaded = () => waitFor(() => expect(slot('or-week-days')).not.toBeNull())

beforeEach(() => {
  for (const n of OR_FNS) or()[n].mockReset()
  toastMock.success.mockReset(); toastMock.error.mockReset(); toastMock.info.mockReset()
  refreshRoutineBadge.mockReset()
  widthBox.w = 0
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
  or().week.mockImplementation(() => Promise.resolve(ok(buildWeekResponse())))
})
afterEach(() => {
  vi.restoreAllMocks()
})

describe('weekContextDate', () => {
  const days = buildWeekResponse().days
  const weekItem = { period_kind: 'WEEK', period_key: WEEK_KEY }

  it('haftalık madde: bugüne kadarki son iş günü (gelecek ve tatil günleri atlanır)', () => {
    expect(weekContextDate(days, weekItem)).toBe(TODAY)
    // Bugün tatilse bağlam dündür
    const holidayToday = days.map((d) => (d.date === TODAY ? { ...d, workday: false, status: 'NOT_WORKDAY' } : d))
    expect(weekContextDate(holidayToday, weekItem)).toBe(YESTERDAY)
    // Sıra önemsiz: en büyük tarih
    expect(weekContextDate([...days].reverse(), weekItem)).toBe(TODAY)
  })

  it('aylık madde yalnız kendi ayına düşen günlerden seçer', () => {
    const cross = [
      mkDay('2026-11-30', { status: 'COMPLETED' }),
      mkDay('2026-12-01', { status: 'OPEN', today: true }),
      mkDay('2026-12-02'),
      mkDay('2026-12-05', { workday: false, status: 'NOT_WORKDAY' }),
    ]
    expect(weekContextDate(cross, { period_kind: 'MONTH', period_key: '2026-11' })).toBe('2026-11-30')
    expect(weekContextDate(cross, { period_kind: 'MONTH', period_key: '2026-12' })).toBe('2026-12-01')
    expect(weekContextDate(cross, weekItem)).toBe('2026-12-01')
    expect(weekContextDate(cross, { period_kind: 'MONTH', period_key: '2026-10' })).toBeNull()
    // Dönem anahtarı olmayan aylık madde kısıtlanmaz
    expect(weekContextDate(cross, { period_kind: 'MONTH' })).toBe('2026-12-01')
  })

  it('uygun gün yoksa null (hepsi gelecek / iş günü değil / boş / geçersiz liste)', () => {
    expect(weekContextDate([mkDay('2026-11-30'), mkDay('2026-12-01')], weekItem)).toBeNull()
    expect(weekContextDate([mkDay('2026-11-28', { workday: false, status: 'NOT_WORKDAY' })], weekItem)).toBeNull()
    expect(weekContextDate([], weekItem)).toBeNull()
    expect(weekContextDate(null, weekItem)).toBeNull()
    expect(weekContextDate(undefined, null)).toBeNull()
    expect(weekContextDate([null, { workday: true, status: 'OPEN' }], weekItem)).toBeNull()
  })

  it('tablo eşiği 760 px', () => {
    expect(WEEK_TABLE_MIN_PX).toBe(760)
  })
})

describe('WeekView — günler', () => {
  it('genişlik 0 → tablo: durum, ilerleme, sorun, nöbetçi ("Siz"), tamamlayan · saat + "geç"', async () => {
    renderWeek()
    expect(slot('or-week-loading')).not.toBeNull()
    await loaded()
    expect(or().week).toHaveBeenCalledWith(TEAM_ID, undefined, expect.objectContaining({ signal: expect.anything() }))
    expect(slot('or-week').dataset.week).toBe(WEEK_KEY)
    expect(slot('or-week-range').textContent).toBe(tr('or.wk.range', '23.11', '29.11.2026'))
    expect(screen.getByText(TR['or.wk.current'], { selector: 'p' })).toBeTruthy()

    const table = slot('or-week-table')
    expect(table).not.toBeNull()
    expect(slot('or-week-cards')).toBeNull()
    expect(slots('or-week-row', table)).toHaveLength(7)
    expect(slots('or-week-row').map((r) => r.dataset.status)).toEqual([
      'COMPLETED', 'COMPLETED_PARTIAL', 'INCOMPLETE', 'OPEN', 'FUTURE', 'NOT_WORKDAY', 'NOT_WORKDAY',
    ])
    expect(dayRow('2026-11-23').querySelector('[data-slot="or-day-status"]').dataset.status).toBe('COMPLETED')

    // Gün etiketi + bugün rozeti
    expect(dayRow('2026-11-23').textContent).toContain(`${TR['or.weekday.1']} 23.11`)
    expect(dayRow(TODAY).dataset.today).toBe('true')
    expect(within(dayRow(TODAY)).getByText(TR['or.wk.today'])).toBeTruthy()
    expect(dayRow('2026-11-23').dataset.today).toBeUndefined()

    // İlerleme: geçmiş günlerde n/zorunlu (ekran okuyucu metniyle), gelecek ve tatilde "—"
    expect(within(dayRow('2026-11-23')).getByLabelText(tr('or.wk.progressAria', 5, 5)).textContent).toBe('5/5')
    expect(within(dayRow(YESTERDAY)).getByLabelText(tr('or.wk.progressAria', 1, 5)).textContent).toBe('1/5')
    expect(within(dayRow('2026-11-27')).queryByLabelText(/zorunlu görev/)).toBeNull()
    expect(within(dayRow('2026-11-28')).queryByLabelText(/zorunlu görev/)).toBeNull()

    // Sorun sayısı
    const cells = (date) => [...dayRow(date).querySelectorAll('td')].map((c) => c.textContent)
    expect(cells(YESTERDAY)[3]).toBe('2')
    expect(cells('2026-11-28')[3]).toBe('—')

    // Nöbetçi: başkası adıyla (çipsiz), kendisi "Siz" çipiyle; iş günü olmayan "—"
    expect(cells('2026-11-23')[4]).toBe(OTHER.name)
    expect(dayRow('2026-11-23').querySelector('[data-slot="or-me-chip"]')).toBeNull()
    expect(cells('2026-11-24')[4]).toContain(ME.name)
    expect(within(dayRow('2026-11-24')).getAllByText(TR['or.me']).length).toBeGreaterThan(0)
    expect(cells('2026-11-28')[4]).toBe('—')

    // Tamamlayan · İstanbul saati; geç tamamlanan gün "geç" rozetli
    expect(cells('2026-11-23')[5]).toBe(`${OTHER.name} · 16:58`)
    expect(cells('2026-11-24')[5]).toContain(`${ME.name} · 18:20`)
    expect(within(dayRow('2026-11-24')).getByText(TR['or.wk.late'])).toBeTruthy()
    expect(within(dayRow('2026-11-23')).queryByText(TR['or.wk.late'])).toBeNull()
    expect(cells(YESTERDAY)[5]).toBe('—')
  })

  it('nöbetçi kaydı yoksa günün kendi adına düşer; ad da yoksa "Atanmamış"', async () => {
    const data = weekWithDay(YESTERDAY, { responsible_name: null }, {
      responsible_by_day: { '2026-11-24': { name: ME.name, source: 'TAKEOVER_DAY', is_me: true } },
    })
    or().week.mockImplementation(() => Promise.resolve(ok(data)))
    renderWeek()
    await loaded()
    const resp = (date) => [...dayRow(date).querySelectorAll('td')][4]
    expect(resp('2026-11-23').textContent).toBe(OTHER.name)
    expect(resp(TODAY).textContent).toBe(ME.name)
    expect(resp(TODAY).querySelector('[data-slot="or-me-chip"]')).toBeNull()
    expect(resp(YESTERDAY).textContent).toBe(TR['or.wk.noResponsible'])
  })

  it('tatil günü adı satırda görünür', async () => {
    or().week.mockImplementation(() => Promise.resolve(ok(
      weekWithDay('2026-11-27', { workday: false, status: 'NOT_WORKDAY', holiday_name: 'Kurumsal Bakım Günü' }),
    )))
    renderWeek()
    await loaded()
    expect(within(dayRow('2026-11-27')).getByText('Kurumsal Bakım Günü')).toBeTruthy()
    expect(dayRow('2026-11-27').querySelector('[data-slot="or-week-open-day"]')).toBeNull()
  })

  it('"Günü aç" yalnız geçmiş / bugünkü iş gününde; tıklanan günü bildirir', async () => {
    const { onOpenDay } = renderWeek()
    await loaded()
    const buttons = slots('or-week-open-day')
    expect(buttons.map((b) => b.closest('[data-slot="or-week-row"]').dataset.date)).toEqual([
      '2026-11-23', '2026-11-24', YESTERDAY, TODAY,
    ])
    const btn = within(dayRow('2026-11-24')).getByRole('button', { name: tr('or.wk.openDay', `${TR['or.weekday.2']} 24.11`) })
    fireEvent.click(btn)
    expect(onOpenDay).toHaveBeenCalledWith('2026-11-24')
    fireEvent.click(dayRow(TODAY).querySelector('[data-slot="or-week-open-day"]'))
    expect(onOpenDay).toHaveBeenLastCalledWith(TODAY)
  })

  it('dar kapsayıcı → kartlar (aynı veriler, "Günü aç" düğmesi metinli)', async () => {
    widthBox.w = 400
    const { onOpenDay } = renderWeek()
    await loaded()
    expect(slot('or-week-table')).toBeNull()
    const cards = slot('or-week-cards')
    expect(cards).not.toBeNull()
    expect(slots('or-week-row', cards)).toHaveLength(7)
    expect(slots('or-week-open-day')).toHaveLength(4)
    // İş günü olmayan kartta ayrıntı listesi yok
    expect(dayRow('2026-11-28').querySelector('dl')).toBeNull()
    expect(dayRow('2026-11-24').textContent).toContain(TR['or.wk.late'])
    expect(dayRow('2026-11-24').querySelector('[data-slot="or-me-chip"]')).not.toBeNull()
    fireEvent.click(within(dayRow('2026-11-23')).getByRole('button', { name: TR['or.wk.openDayShort'] }))
    expect(onOpenDay).toHaveBeenCalledWith('2026-11-23')
  })

  it('genişlik 760 ve üstünde tablo, altında kart', async () => {
    widthBox.w = 760
    const first = renderWeek()
    await loaded()
    expect(slot('or-week-table')).not.toBeNull()
    first.unmount()
    widthBox.w = 759
    renderWeek()
    await loaded()
    expect(slot('or-week-cards')).not.toBeNull()
  })
})

describe('WeekView — hafta gezintisi', () => {
  it('yüklenirken gezinti kapalı; sonra önceki / sonraki ±7 gün (Pazartesi)', async () => {
    const d = deferred()
    or().week.mockReset()
    or().week.mockImplementationOnce(() => d.promise)
    const { onWeekChange } = renderWeek()
    expect(slot('or-week-range').textContent).toBe(TR['or.wk.loading'])
    expect(slot('or-week-nav-prev')).toBeDisabled()
    expect(slot('or-week-nav-next')).toBeDisabled()
    await act(async () => { d.resolve(ok(buildWeekResponse())) })
    await loaded()
    expect(slot('or-week-nav-prev')).not.toBeDisabled()
    fireEvent.click(slot('or-week-nav-prev'))
    expect(onWeekChange).toHaveBeenLastCalledWith(PREV_WEEK_KEY)
    // Bu haftadan ileri gidilmez (işaretlenecek bir şey yok — gün görünümü gibi)
    expect(slot('or-week-nav-next')).toBeDisabled()
    expect(screen.getByRole('button', { name: TR['or.wk.prev'] })).toBe(slot('or-week-nav-prev'))
    expect(screen.getByRole('button', { name: TR['or.wk.next'] })).toBe(slot('or-week-nav-next'))
  })

  it('geçmiş bir haftada "sonraki" açık ve +7 gün; tümü gelecek bir haftada kapalı', async () => {
    const past = buildWeekResponse({ week: { key: PREV_WEEK_KEY, from: PREV_WEEK_KEY, to: '2026-11-22' } })
    past.days = past.days.map((x) => ({ ...x, today: false, status: x.status === 'FUTURE' ? 'INCOMPLETE' : x.status }))
    or().week.mockImplementation(() => Promise.resolve(ok(past)))
    const first = renderWeek({ week: PREV_WEEK_KEY })
    await loaded()
    expect(slot('or-week-nav-next')).not.toBeDisabled()
    fireEvent.click(slot('or-week-nav-next'))
    expect(first.onWeekChange).toHaveBeenLastCalledWith(WEEK_KEY)
    first.unmount()

    const future = buildWeekResponse({ week: { key: '2026-11-30', from: '2026-11-30', to: '2026-12-06' } })
    future.days = future.days.map((x) => ({ ...x, today: false, status: 'FUTURE' }))
    or().week.mockImplementation(() => Promise.resolve(ok(future)))
    renderWeek({ week: '2026-11-30' })
    await loaded()
    expect(slot('or-week-nav-next')).toBeDisabled()
    expect(slot('or-week-nav-prev')).not.toBeDisabled()
  })

  it('"Bu hafta" yalnız başka bir hafta seçiliyken açık; seçimi bırakır', async () => {
    const first = renderWeek()
    await loaded()
    expect(slot('or-week-nav-current')).toBeDisabled()
    first.unmount()

    const past = {
      ...buildWeekResponse(),
      week: { key: PREV_WEEK_KEY, from: PREV_WEEK_KEY, to: '2026-11-22' },
      days: ['2026-11-16', '2026-11-17', '2026-11-18', '2026-11-19', '2026-11-20'].map((date) => mkDay(date, {
        status: 'COMPLETED', required: 5, resolved: 5, responsible_name: OTHER.name,
      })).concat([mkDay('2026-11-21', { workday: false, status: 'NOT_WORKDAY' }), mkDay('2026-11-22', { workday: false, status: 'NOT_WORKDAY' })]),
      responsible_by_day: {},
    }
    or().week.mockImplementation(() => Promise.resolve(ok(past)))
    const { onWeekChange } = renderWeek({ week: PREV_WEEK_KEY })
    await loaded()
    expect(or().week).toHaveBeenLastCalledWith(TEAM_ID, PREV_WEEK_KEY, expect.objectContaining({ signal: expect.anything() }))
    expect(slot('or-week').dataset.week).toBe(PREV_WEEK_KEY)
    expect(screen.getByText(TR['or.wk.other'])).toBeTruthy()
    expect(slot('or-week-nav-current')).not.toBeDisabled()
    fireEvent.click(slot('or-week-nav-current'))
    expect(onWeekChange).toHaveBeenCalledWith(null)
    fireEvent.click(slot('or-week-nav-prev'))
    expect(onWeekChange).toHaveBeenLastCalledWith('2026-11-09')
  })

  it('hafta değişince önceki haftanın geç yanıtı düşer (önceki istek iptal edilir)', async () => {
    const first = deferred()
    or().week.mockReset()
    or().week
      .mockImplementationOnce(() => first.promise)
      .mockImplementation(() => Promise.resolve(ok({
        ...buildWeekResponse(), week: { key: PREV_WEEK_KEY, from: PREV_WEEK_KEY, to: '2026-11-22' },
      })))
    const utils = renderWeek()
    const firstSignal = or().week.mock.calls[0][2].signal
    utils.rerender(<WeekView teamId={TEAM_ID} week={PREV_WEEK_KEY} meName={ME.name} onWeekChange={utils.onWeekChange}
      onOpenDay={utils.onOpenDay} onEditTemplate={utils.onEditTemplate} />)
    await waitFor(() => expect(slot('or-week').dataset.week).toBe(PREV_WEEK_KEY))
    expect(firstSignal.aborted).toBe(true)
    await act(async () => { first.resolve(ok(buildWeekResponse())) })
    expect(slot('or-week').dataset.week).toBe(PREV_WEEK_KEY)
  })
})

describe('WeekView — haftalık / aylık maddeler', () => {
  it('haftalık ve aylık bantlar çizilir; haftalık madde bugünün bağlamıyla işaretlenir (iyimser, sayfa yeniden okunmaz)', async () => {
    const d = deferred()
    or().setItemState.mockImplementationOnce(() => d.promise)
    renderWeek()
    await loaded()
    expect([...document.querySelectorAll('[data-slot="or-band"]')].map((b) => b.dataset.kind)).toEqual(['WEEK', 'MONTH'])
    expect(taskRow(KEY.weekly).dataset.state).toBe('PENDING')
    expect(check(KEY.weekly)).not.toBeDisabled()
    expect(check(KEY.monthly)).not.toBeDisabled()

    fireEvent.click(check(KEY.weekly))
    expect(or().setItemState).toHaveBeenCalledWith(TEAM_ID, {
      date: TODAY, task_id: 't-weekly-report', period_kind: 'WEEK', period_key: WEEK_KEY, state: 'DONE',
      expected_state: 'PENDING', skip_reason: null,
    })
    // İyimser: yanıt beklenmeden yapıldı görünür
    await waitFor(() => expect(taskRow(KEY.weekly).dataset.state).toBe('DONE'))
    await act(async () => {
      d.resolve(ok({ item: serverItem('t-weekly-report', {
        state: 'DONE', state_by_name: ME.name, state_by_me: true, state_at: '2026-11-26T10:42:00Z', version: 2,
      }, buildWeekResponse()), changed: true }))
    })
    await waitFor(() => expect(refreshRoutineBadge).toHaveBeenCalled())
    expect(taskRow(KEY.weekly).dataset.state).toBe('DONE')
    expect(or().week).toHaveBeenCalledTimes(1)
  })

  it('aylık maddenin işareti kaldırılır: beklenen durum sunucunun son onayı (DONE)', async () => {
    or().setItemState.mockImplementation(() => Promise.resolve(ok({
      item: serverItem('t-certs', { state: 'PENDING', state_by_name: null, state_by_me: false, state_at: null, version: 3 }, buildWeekResponse()),
    })))
    renderWeek()
    await loaded()
    expect(taskRow(KEY.monthly).dataset.state).toBe('DONE')
    fireEvent.click(check(KEY.monthly))
    expect(or().setItemState).toHaveBeenCalledWith(TEAM_ID, expect.objectContaining({
      date: TODAY, task_id: 't-certs', period_kind: 'MONTH', period_key: MONTH_KEY, state: 'PENDING', expected_state: 'DONE',
    }))
    await waitFor(() => expect(taskRow(KEY.monthly).dataset.state).toBe('PENDING'))
  })

  it('bugün tatilse haftalık madde dünün bağlamıyla gider', async () => {
    or().week.mockImplementation(() => Promise.resolve(ok(
      weekWithDay(TODAY, { workday: false, status: 'NOT_WORKDAY', holiday_name: 'Kurumsal Bakım Günü' }),
    )))
    or().setItemState.mockImplementation(() => new Promise(() => {}))
    renderWeek()
    await loaded()
    fireEvent.click(check(KEY.weekly))
    expect(or().setItemState).toHaveBeenCalledWith(TEAM_ID, expect.objectContaining({ date: YESTERDAY, task_id: 't-weekly-report' }))
  })

  it('ay sınırını aşan haftada aylık madde yalnız kendi ayının son iş gününe bağlanır', async () => {
    const base = buildWeekResponse()
    const cross = {
      ...base,
      week: { key: '2026-11-30', from: '2026-11-30', to: '2026-12-06' },
      days: [
        mkDay('2026-11-30', { status: 'COMPLETED', required: 5, resolved: 5, responsible_name: ME.name }),
        mkDay('2026-12-01', { status: 'OPEN', required: 5, resolved: 1, today: true, responsible_name: ME.name }),
        mkDay('2026-12-02', { responsible_name: ME.name }),
        mkDay('2026-12-03', { responsible_name: ME.name }),
        mkDay('2026-12-04', { responsible_name: ME.name }),
        mkDay('2026-12-05', { workday: false, status: 'NOT_WORKDAY' }),
        mkDay('2026-12-06', { workday: false, status: 'NOT_WORKDAY' }),
      ],
      groups: base.groups.filter((g) => g.kind === 'MONTH'),
      responsible_by_day: {},
    }
    or().week.mockImplementation(() => Promise.resolve(ok(cross)))
    or().setItemState.mockImplementation(() => new Promise(() => {}))
    renderWeek({ week: '2026-11-30' })
    await loaded()
    fireEvent.click(check(KEY.monthly))
    expect(or().setItemState).toHaveBeenCalledWith(TEAM_ID, expect.objectContaining({
      date: '2026-11-30', task_id: 't-certs', period_kind: 'MONTH', period_key: MONTH_KEY,
    }))
  })

  it('bağlam günü bulunamayan madde işaretlenemez', async () => {
    const key = 'MONTH:2026-10:t-certs'
    or().week.mockImplementation(() => Promise.resolve(ok(withItem(buildWeekResponse(), 't-certs', { period_key: '2026-10', key }))))
    renderWeek()
    await loaded()
    expect(check(key)).toBeDisabled()
    fireEvent.click(check(key))
    expect(or().setItemState).not.toHaveBeenCalled()
    expect(check(KEY.weekly)).not.toBeDisabled()
  })

  it('çalışma izni yoksa haftalık / aylık maddeler salt okunur', async () => {
    or().week.mockImplementation(() => Promise.resolve(ok(buildWeekResponse({ can_work: false }))))
    renderWeek()
    await loaded()
    expect(check(KEY.weekly)).toBeDisabled()
    expect(check(KEY.monthly)).toBeDisabled()
    fireEvent.click(check(KEY.weekly))
    expect(or().setItemState).not.toHaveBeenCalled()
  })

  it('işaret reddedilirse madde eski durumuna döner + sunucu metni', async () => {
    or().setItemState.mockImplementation(() => Promise.resolve(fail(500, 'INTERNAL_ERROR', { error: 'İşaret kaydedilemedi, birazdan yeniden deneyin.' })))
    renderWeek()
    await loaded()
    fireEvent.click(check(KEY.weekly))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalled())
    expect(String(toastMock.error.mock.calls[0][0])).toContain('İşaret kaydedilemedi, birazdan yeniden deneyin.')
    await waitFor(() => expect(taskRow(KEY.weekly).dataset.state).toBe('PENDING'))
  })

  it('dönem görevi yoksa bilgi satırı (boş bölümler de "yok" sayılır)', async () => {
    const empty = buildWeekResponse()
    empty.groups = empty.groups.map((g) => ({ ...g, sections: g.sections.map((s) => ({ ...s, items: [] })) }))
    or().week.mockImplementation(() => Promise.resolve(ok(empty)))
    renderWeek()
    await loaded()
    expect(slot('or-week-no-period').textContent).toBe(TR['or.wk.noPeriodTasks'])
    expect(document.querySelector('[data-slot="or-band"]')).toBeNull()
  })
})

describe('WeekView — devir ve hata', () => {
  it('devir notu kartı çizilir; önceki devir yoksa özet yok, varsa onay düğmesiz özet', async () => {
    const first = renderWeek()
    await loaded()
    expect(slot('or-handover-note-card')).not.toBeNull()
    expect(slot('or-handover')).toBeNull()
    first.unmount()

    or().week.mockImplementation(() => Promise.resolve(ok(buildWeekResponse({ handover_previous: buildHandover() }))))
    renderWeek()
    await loaded()
    expect(slot('or-handover')).not.toBeNull()
    expect(slot('or-handover-ack')).toBeNull()
    expect(or().ackHandover).not.toHaveBeenCalled()
  })

  it('okuma hatası → "okunamadı" + yeniden dene; ikinci okuma başarılıysa günler gelir', async () => {
    or().week.mockReset()
    or().week
      .mockResolvedValueOnce(fail(500, 'INTERNAL_ERROR'))
      .mockImplementation(() => Promise.resolve(ok(buildWeekResponse())))
    renderWeek()
    await waitFor(() => expect(slot('or-load-failed')).not.toBeNull())
    expect(within(slot('or-load-failed')).getByText(TR['or.wk.loadFailedTitle'])).toBeTruthy()
    expect(within(slot('or-load-failed')).getByText(TR['or.wk.loadFailed'])).toBeTruthy()
    fireEvent.click(slot('or-load-retry'))
    await loaded()
    expect(slot('or-load-failed')).toBeNull()
    expect(or().week).toHaveBeenCalledTimes(2)
  })

  it('ağ hatası → bağlantı metni', async () => {
    or().week.mockReset()
    or().week.mockRejectedValueOnce(new Error(''))
    renderWeek()
    await waitFor(() => expect(slot('or-load-failed')).not.toBeNull())
    expect(within(slot('or-load-failed')).getByText(TR['or.day.loadFailedNetwork'])).toBeTruthy()
  })
})
