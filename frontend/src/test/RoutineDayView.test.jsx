import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

/**
 * Nöbet Rutinleri — gün sayfası (DayView, 2026-11-26). Pinlenenler (sözleşme §9.4 / §9.5):
 *  - okuma → bantlar / bölümler / satırlar; uzun başlık sarar; geciken rozet sunucu saatine göre;
 *  - tik İYİMSER: satır hemen "yapıldı", sayfa yeniden okunmaz, iskelet görünmez; istek `expected_state` = sunucunun son
 *    onayladığı durumla gider; hata → eski duruma döner + sunucu metni; 409 ITEM_CHANGED → sunucunun `current`'ı yazılır ve
 *    kimin değiştirdiği söylenir; 409 DAY_COMPLETED → gün yeniden okunur; sonucu belirsiz yanıt → gün yeniden okunur;
 *  - uçuştayken ikinci tık sıraya girer, son niyet kazanır;
 *  - "Yalnız bekleyenler"; "Bölümü tamamla" yalnız bekleyenleri gönderir → "Geri al" yalnız DEĞİŞENLERİ geri alır;
 *  - "Tümünü aç" her bağlantıyı yeni sekmede açar, engellenince satırın altında söylenir;
 *  - canlı sayaç çipleri: okunamayan "—" (nötr), kısmi "≥ n", gizli çizilmez;
 *  - okuma hatası + yeniden dene; önceki günün geç yanıtı düşer; şablon yok / iş günü değil / dün şeridi / alt çubuk /
 *    tamamlanmış günde "Yeniden aç".
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
// Sorun paneli kendi testinde (RoutineProblems.test.jsx); burada yalnız aldığı sayıyı gösterir.
vi.mock('../components/oncallroutine/ProblemsPanel.jsx', () => ({
  default: (p) => <div data-slot="stub-problems" data-total={String(p.total)} />,
}))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  getRecentFailures: () => [],
  api: withApiFallback({
    oncallRoutines: {
      day: vi.fn(), signals: vi.fn(), setItemState: vi.fn(), bulkItemState: vi.fn(), saveItemNote: vi.fn(),
      events: vi.fn(), reopenDay: vi.fn(), completeDay: vi.fn(), template: vi.fn(), people: vi.fn(), assign: vi.fn(),
      clearAssignment: vi.fn(), saveDayNote: vi.fn(), saveHandoverNote: vi.fn(), ackHandover: vi.fn(), problems: vi.fn(),
      problemImageUrl: (id, imageId) => `/api/oncall-routines/problems/${id}/images/${imageId}`,
    },
  }),
}))

import { api } from '../api/client'
import { refreshRoutineBadge } from '../components/oncallroutine/useRoutineBadge.js'
import DayView, { UNDO_MS } from '../components/oncallroutine/DayView.jsx'
import { TR } from '../i18n/tr.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { expectWraps } from './helpers/cardWrap.js'
import {
  KEY, LONG_TITLE, ME, OTHER, TEAM_ID, TEAM_NAME, TODAY, YESTERDAY,
  buildCompletedSheet, buildDaySheet, buildSignals, buildTemplateResponse, deferred, fail, ok, serverItem, sixLinks,
  withItem,
} from './helpers/oncallRoutineFixtures.js'

const or = () => api.oncallRoutines
const OR_FNS = ['day', 'signals', 'setItemState', 'bulkItemState', 'saveItemNote', 'events', 'reopenDay', 'completeDay',
  'template', 'people', 'assign', 'clearAssignment', 'saveDayNote', 'saveHandoverNote', 'ackHandover', 'problems']
const tr = (k, ...a) => TR[k].replace(/\{(\d+)\}/g, (_, i) => String(a[Number(i)] ?? ''))
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const row = (key) => document.querySelector(`[data-slot="or-task"][data-key="${key}"]`)
const rows = () => [...document.querySelectorAll('[data-slot="or-task"]')]
const check = (key) => document.querySelector(`[data-slot="or-task-check"][data-key="${key}"]`)
const chip = (key, sig) => row(key)?.querySelector(`[data-slot="or-task-signal"][data-signal="${sig}"]`) || null
const sectionComplete = (id) => document.querySelector(`[data-slot="or-section"][data-section="${id}"] [data-slot="or-section-complete"]`)
const doneItem = (taskId, at = '2026-11-26T10:42:00Z') => serverItem(taskId, {
  state: 'DONE', state_by_name: ME.name, state_by_me: true, state_at: at, version: 2,
})
const pendingItem = (taskId) => serverItem(taskId, { state: 'PENDING', state_by_name: null, state_by_me: false, state_at: null, version: 3 })

function renderDay(props = {}) {
  const handlers = { onPendingChange: vi.fn(), onDateChange: vi.fn(), onEditTemplate: vi.fn() }
  const all = { ...handlers, ...props }
  const utils = render(
    <DayView teamId={TEAM_ID} teamName={TEAM_NAME} member canManage={false} date={null} pendingOnly={false}
      meName={ME.name} {...all} />,
  )
  return { ...utils, ...all }
}
const loaded = () => waitFor(() => expect(slot('or-day')).not.toBeNull())

beforeEach(() => {
  for (const n of OR_FNS) or()[n].mockReset()
  toastMock.success.mockReset(); toastMock.error.mockReset(); toastMock.info.mockReset()
  toastMock.action.mockReset(); toastMock.warning.mockReset()
  refreshRoutineBadge.mockReset()
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
  or().day.mockImplementation(() => Promise.resolve(ok(buildDaySheet())))
  or().signals.mockImplementation(() => Promise.resolve(ok(buildSignals())))
  or().template.mockImplementation(() => Promise.resolve(ok(buildTemplateResponse())))
  or().events.mockImplementation(() => Promise.resolve(ok({ items: [], has_more: false })))
})
afterEach(() => {
  vi.restoreAllMocks()
})

describe('DayView — okuma', () => {
  it('gün sayfası: bantlar, satırlar, geciken rozet, alt çubuk ilerlemesi; uzun başlık sarar', async () => {
    renderDay()
    expect(slot('or-day-loading')).not.toBeNull()
    await loaded()
    expect(or().day).toHaveBeenCalledWith(TEAM_ID, undefined, expect.objectContaining({ signal: expect.anything() }))
    const day = slot('or-day')
    expect(day.dataset.date).toBe(TODAY)
    expect(day.dataset.status).toBe('OPEN')
    expect(rows()).toHaveLength(7)
    expect([...document.querySelectorAll('[data-slot="or-band"]')].map((b) => b.dataset.kind)).toEqual(['DAY', 'WEEK', 'MONTH'])
    // Sunucu saati 13:41, bölümün hedefi 10:30 → bekleyen zorunlu görevler gecikmiş (canlı sunucu saatiyle)
    await waitFor(() => expect(row(KEY.dash).dataset.overdue).toBe('true'))
    expect(row(KEY.errors).dataset.overdue).toBe('true')
    expect(row(KEY.incidents).dataset.state).toBe('DONE')
    expect(row(KEY.incidents).dataset.overdue).toBeUndefined()
    expect(row(KEY.queue).dataset.state).toBe('SKIPPED')
    expect(row(KEY.optional).dataset.required).toBe('false')
    // Uzun başlık tam görünür (kesilmez)
    const title = within(row(KEY.dash)).getByText(LONG_TITLE)
    expectWraps(title, 'görev başlığı')
    // Atlanan görevin gerekçesi + kim
    expect(within(row(KEY.queue)).getByText('Bakım penceresi nedeniyle kuyruk kapalı')).toBeTruthy()
    const bar = slot('or-bottom-bar')
    expect(bar.textContent).toContain(tr('or.bar.progress', 3, 5))
    expect(bar.textContent).toContain(tr('or.bar.overdue', 2))
    expect(slot('or-complete')).not.toBeDisabled()
    expect(slot('stub-problems').dataset.total).toBe('1')
  })

  it('okuma hatası → "okunamadı" + yeniden dene; ikinci okuma başarılıysa sayfa gelir', async () => {
    or().day.mockReset()
    or().day
      .mockResolvedValueOnce(fail(500, 'INTERNAL_ERROR'))
      .mockImplementation(() => Promise.resolve(ok(buildDaySheet())))
    renderDay()
    await waitFor(() => expect(slot('or-load-failed')).not.toBeNull())
    expect(screen.getByText(TR['or.day.loadFailedTitle'])).toBeTruthy()
    expect(slot('or-day')).toBeNull()
    fireEvent.click(slot('or-load-retry'))
    await loaded()
    expect(or().day).toHaveBeenCalledTimes(2)
    expect(slot('or-load-failed')).toBeNull()
  })

  it('ağ hatasında ağ metni (sunucuya ulaşılamadı)', async () => {
    or().day.mockReset()
    or().day.mockRejectedValueOnce(new Error('Failed to fetch')).mockImplementation(() => Promise.resolve(ok(buildDaySheet())))
    renderDay()
    await waitFor(() => expect(slot('or-load-failed')).not.toBeNull())
    expect(slot('or-load-failed').textContent).toContain(TR['or.day.loadFailedNetwork'])
  })

  it('önceki günün GEÇ yanıtı yeni günün sayfasını ezmez', async () => {
    or().day.mockReset()
    const d24 = deferred()
    const d25 = deferred()
    or().day.mockImplementation((_t, date) => (date === '2026-11-24' ? d24.promise : d25.promise))
    const { rerender, onPendingChange, onDateChange, onEditTemplate } = renderDay({ date: '2026-11-24' })
    rerender(
      <DayView teamId={TEAM_ID} teamName={TEAM_NAME} member canManage={false} date={YESTERDAY} pendingOnly={false}
        meName={ME.name} onPendingChange={onPendingChange} onDateChange={onDateChange} onEditTemplate={onEditTemplate} />,
    )
    await act(async () => { d25.resolve(ok(buildDaySheet({ date: YESTERDAY }))) })
    await waitFor(() => expect(slot('or-day')?.dataset.date).toBe(YESTERDAY))
    await act(async () => { d24.resolve(ok(buildDaySheet({ date: '2026-11-24' }))) })
    expect(slot('or-day').dataset.date).toBe(YESTERDAY)
  })

  it('şablon yok: "Şablonu oluştur" yalnız şablon düzenleyebilene, tıklayınca onEditTemplate(null)', async () => {
    or().day.mockImplementation(() => Promise.resolve(ok(buildDaySheet({
      configured: false, locked_reason: 'NOT_CONFIGURED', groups: [], can_edit_template: true,
    }))))
    const { onEditTemplate, unmount } = renderDay()
    await waitFor(() => expect(slot('or-create-template')).not.toBeNull())
    expect(screen.getByText(TR['or.day.notConfiguredTitle'])).toBeTruthy()
    fireEvent.click(slot('or-create-template'))
    expect(onEditTemplate).toHaveBeenCalledWith(null)
    unmount()

    or().day.mockImplementation(() => Promise.resolve(ok(buildDaySheet({
      configured: false, locked_reason: 'NOT_CONFIGURED', groups: [], can_edit_template: false,
    }))))
    renderDay()
    await screen.findByText(TR['or.day.notConfiguredTitle'])
    expect(screen.getByText(TR['or.day.notConfigured'])).toBeTruthy()
    expect(slot('or-create-template')).toBeNull()
  })

  it('iş günü değil (resmî tatil): durum bloğu; görev listesi, süzgeç ve alt çubuk yok', async () => {
    or().day.mockImplementation(() => Promise.resolve(ok(buildDaySheet({
      workday: false, holiday_name: 'Cumhuriyet Bayramı', non_workday_reason: 'HOLIDAY', locked_reason: 'NOT_WORKDAY',
      groups: [], previous_workday: null, day: { status: 'NOT_WORKDAY' },
    }))))
    renderDay()
    await loaded()
    expect(screen.getByText(tr('or.day.holiday', 'Cumhuriyet Bayramı'))).toBeTruthy()
    expect(slot('or-controls')).toBeNull()
    expect(slot('or-bottom-bar')).toBeNull()
    expect(rows()).toHaveLength(0)
    expect(or().signals).not.toHaveBeenCalled()
  })

  it('dün şeridi: ilk üç başlık + "ve N görev daha"; "O günü aç" önceki iş gününe gider', async () => {
    const { onDateChange } = renderDay()
    await loaded()
    const strip = slot('or-yesterday')
    expect(strip.textContent).toContain('Alarm panosunu kontrol et · Gece hata kayıtlarını incele · Mesaj kuyruklarını kontrol et')
    expect(strip.textContent).toContain(tr('or.day.andMore', 1))
    expect(screen.getByText(tr('or.day.yesterdayTitle', '25.11', 4))).toBeTruthy()
    fireEvent.click(slot('or-yesterday-open'))
    expect(onDateChange).toHaveBeenCalledWith(YESTERDAY)
  })

  it('dün şeridi geçmiş bir günü görüntülerken çizilmez', async () => {
    or().day.mockImplementation(() => Promise.resolve(ok(buildDaySheet({ date: YESTERDAY }))))
    renderDay({ date: YESTERDAY })
    await loaded()
    expect(slot('or-yesterday')).toBeNull()
  })
})

describe('DayView — iyimser işaret', () => {
  it('tik ANINDA yazılır (yeniden okuma yok, iskelet yok); istek sunucunun son onaylı durumuyla gider', async () => {
    renderDay()
    await loaded()
    const d = deferred()
    or().setItemState.mockReturnValueOnce(d.promise)
    fireEvent.click(check(KEY.errors))
    expect(row(KEY.errors).dataset.state).toBe('DONE')
    expect(within(row(KEY.errors)).getByText(ME.name)).toBeTruthy()
    expect(slot('or-day-loading')).toBeNull()
    expect(slot('or-day')).not.toBeNull()
    // İlerleme istemcide yeniden sayılır (bekleme yok)
    expect(slot('or-bottom-bar').textContent).toContain(tr('or.bar.progress', 4, 5))
    expect(slot('or-bottom-bar').textContent).toContain(tr('or.bar.overdue', 1))
    expect(or().setItemState).toHaveBeenCalledTimes(1)
    expect(or().setItemState).toHaveBeenCalledWith(TEAM_ID, {
      date: TODAY, task_id: 't-errors', period_kind: 'DAY', period_key: TODAY, state: 'DONE', expected_state: 'PENDING',
      skip_reason: null,
    })
    await act(async () => {
      d.resolve(ok({
        item: doneItem('t-errors'), changed: true, day: { status: 'OPEN', version: 4 },
        progress: { required: 5, done: 3, skipped: 1, pending: 1, overdue: 1, optional_pending: 1, problems_open: 1 },
      }))
    })
    expect(row(KEY.errors).dataset.state).toBe('DONE')
    expect(within(row(KEY.errors)).getByText('· 13:42')).toBeTruthy()
    expect(refreshRoutineBadge).toHaveBeenCalled()
    expect(or().day).toHaveBeenCalledTimes(1)
    expect(toastMock.error).not.toHaveBeenCalled()
  })

  it('geri alma (yapıldı → bekliyor) expected_state = DONE ile gider', async () => {
    renderDay()
    await loaded()
    or().setItemState.mockResolvedValueOnce(ok({ item: pendingItem('t-incidents'), changed: true }))
    fireEvent.click(check(KEY.incidents))
    expect(row(KEY.incidents).dataset.state).toBe('PENDING')
    expect(or().setItemState.mock.calls[0][1]).toMatchObject({ task_id: 't-incidents', state: 'PENDING', expected_state: 'DONE' })
    await waitFor(() => expect(refreshRoutineBadge).toHaveBeenCalled())
    expect(row(KEY.incidents).dataset.state).toBe('PENDING')
  })

  it('sunucu reddederse satır eski durumuna döner ve sunucunun metni gösterilir', async () => {
    renderDay()
    await loaded()
    const res = fail(500, 'INTERNAL_ERROR', { error: 'Sunucu hatası: işaret kaydedilemedi.' })
    or().setItemState.mockResolvedValueOnce(res)
    fireEvent.click(check(KEY.errors))
    expect(row(KEY.errors).dataset.state).toBe('DONE')
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith('Sunucu hatası: işaret kaydedilemedi.', expect.objectContaining({ details: res })))
    expect(row(KEY.errors).dataset.state).toBe('PENDING')
    expect(or().day).toHaveBeenCalledTimes(1)
    expect(refreshRoutineBadge).not.toHaveBeenCalled()
  })

  it('409 ITEM_CHANGED: sunucunun güncel maddesi yazılır, kimin ne zaman değiştirdiği söylenir; sonraki tık onu temel alır', async () => {
    renderDay()
    await loaded()
    const current = serverItem('t-errors', {
      state: 'DONE', state_by_name: OTHER.name, state_by_me: false, state_at: '2026-11-26T07:15:00Z', version: 2,
    })
    or().setItemState.mockResolvedValueOnce(fail(409, 'ITEM_CHANGED', { current }))
    fireEvent.click(check(KEY.errors))
    await waitFor(() => expect(toastMock.info).toHaveBeenCalledWith(tr('or.task.changedBy', OTHER.name, '10:15'), 6000))
    expect(row(KEY.errors).dataset.state).toBe('DONE')
    expect(within(row(KEY.errors)).getByText(OTHER.name)).toBeTruthy()
    expect(within(row(KEY.errors)).queryByText(ME.name)).toBeNull()
    expect(toastMock.error).not.toHaveBeenCalled()

    or().setItemState.mockResolvedValueOnce(ok({ item: pendingItem('t-errors') }))
    fireEvent.click(check(KEY.errors))
    expect(or().setItemState.mock.calls[1][1]).toMatchObject({ state: 'PENDING', expected_state: 'DONE' })
  })

  it('409 DAY_COMPLETED: hata söylenir, gün yeniden okunur; tamamlanmış günde kutular kilitli, alt çubukta "Yeniden aç"', async () => {
    or().day.mockReset()
    or().day
      .mockResolvedValueOnce(ok(buildDaySheet()))
      .mockImplementation(() => Promise.resolve(ok(buildCompletedSheet())))
    renderDay()
    await loaded()
    or().setItemState.mockResolvedValueOnce(fail(409, 'DAY_COMPLETED', { day: { status: 'COMPLETED', completed_by_name: OTHER.name } }))
    fireEvent.click(check(KEY.errors))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith(TR['or.err.DAY_COMPLETED'], expect.anything()))
    await waitFor(() => expect(or().day).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(slot('or-day').dataset.status).toBe('COMPLETED'))
    expect(row(KEY.errors).dataset.state).toBe('PENDING')
    expect(check(KEY.errors)).toBeDisabled()
    expect(slot('or-bottom-bar').textContent).toContain(tr('or.bar.completed', OTHER.name, '13:40'))
    expect(slot('or-complete')).toBeNull()
    expect(slot('or-reopen')).not.toBeNull()
  })

  it('409 DAY_COMPLETED sonrası "başkası tamamladı" bandı yeniden okunan TAMAMLANMIŞ günde görünür ve kapatılabilir', async () => {
    or().day.mockReset()
    or().day
      .mockResolvedValueOnce(ok(buildDaySheet()))
      .mockImplementation(() => Promise.resolve(ok(buildCompletedSheet())))
    renderDay()
    await loaded()
    expect(screen.queryByText(TR['or.day.completedMeanwhile'])).toBeNull()
    or().setItemState.mockResolvedValueOnce(fail(409, 'DAY_COMPLETED', { day: { status: 'COMPLETED', completed_by_name: OTHER.name } }))
    fireEvent.click(check(KEY.errors))
    await waitFor(() => {
      expect(slot('or-day').dataset.status).toBe('COMPLETED')
      expect(screen.getByText(TR['or.day.completedMeanwhile'])).toBeInTheDocument()
    })
    const banner = screen.getByText(TR['or.day.completedMeanwhile']).closest('[data-slot="alert"]')
    fireEvent.click(within(banner).getByRole('button', { name: TR['or.close'] }))
    await waitFor(() => expect(screen.queryByText(TR['or.day.completedMeanwhile'])).toBeNull())
  })

  it('sonucu belirsiz yanıt (zaman aşımı) → bilgi + gün yeniden okunur; sunucudaki gerçek durum yazılır', async () => {
    or().day.mockReset()
    or().day
      .mockResolvedValueOnce(ok(buildDaySheet()))
      .mockImplementation(() => Promise.resolve(ok(withItem(buildDaySheet(), 't-errors', {
        state: 'DONE', state_by_name: ME.name, state_by_me: true, state_at: '2026-11-26T10:42:00Z', version: 2,
      }))))
    renderDay()
    await loaded()
    or().setItemState.mockResolvedValueOnce({ success: false, status: 0, code: 'REQUEST_TIMEOUT', error: 'İstek zaman aşımına uğradı' })
    fireEvent.click(check(KEY.errors))
    await waitFor(() => expect(toastMock.info).toHaveBeenCalledWith(TR['or.task.unsure'], 6000))
    await waitFor(() => expect(or().day).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(within(row(KEY.errors)).queryByText('· 13:42')).not.toBeNull())
    expect(row(KEY.errors).dataset.state).toBe('DONE')
    expect(toastMock.error).not.toHaveBeenCalled()
    // Yazma körlemesine tekrarlanmaz
    expect(or().setItemState).toHaveBeenCalledTimes(1)
  })

  it('ağ istisnası da belirsiz sayılır (yeniden okuma, hata bildirimi yok)', async () => {
    renderDay()
    await loaded()
    or().setItemState.mockRejectedValueOnce(new Error('Failed to fetch'))
    fireEvent.click(check(KEY.errors))
    await waitFor(() => expect(toastMock.info).toHaveBeenCalledWith(TR['or.task.unsure'], 6000))
    await waitFor(() => expect(or().day).toHaveBeenCalledTimes(2))
    expect(toastMock.error).not.toHaveBeenCalled()
  })

  it('uçuştayken ikinci tık sıraya girer: ilki bitince onaylı durumla (DONE) gönderilir; ilk yanıt son niyeti ezmez', async () => {
    renderDay()
    await loaded()
    const d1 = deferred()
    const d2 = deferred()
    or().setItemState.mockReturnValueOnce(d1.promise).mockReturnValueOnce(d2.promise)
    fireEvent.click(check(KEY.errors))   // → DONE
    fireEvent.click(check(KEY.errors))   // → PENDING (sıraya)
    expect(or().setItemState).toHaveBeenCalledTimes(1)
    expect(row(KEY.errors).dataset.state).toBe('PENDING')
    await act(async () => { d1.resolve(ok({ item: doneItem('t-errors'), day: { status: 'OPEN', version: 4 } })) })
    await waitFor(() => expect(or().setItemState).toHaveBeenCalledTimes(2))
    expect(or().setItemState.mock.calls[1][1]).toMatchObject({ task_id: 't-errors', state: 'PENDING', expected_state: 'DONE' })
    expect(row(KEY.errors).dataset.state).toBe('PENDING')
    await act(async () => { d2.resolve(ok({ item: pendingItem('t-errors') })) })
    expect(row(KEY.errors).dataset.state).toBe('PENDING')
    expect(or().setItemState).toHaveBeenCalledTimes(2)
  })

  it('son niyet kazanır: DONE → PENDING → DONE tıklanınca ilk istek dönünce ikinci istek GİTMEZ', async () => {
    renderDay()
    await loaded()
    const d1 = deferred()
    or().setItemState.mockReturnValueOnce(d1.promise)
    fireEvent.click(check(KEY.errors))
    fireEvent.click(check(KEY.errors))
    fireEvent.click(check(KEY.errors))
    expect(row(KEY.errors).dataset.state).toBe('DONE')
    await act(async () => { d1.resolve(ok({ item: doneItem('t-errors') })) })
    await waitFor(() => expect(within(row(KEY.errors)).queryByText('· 13:42')).not.toBeNull())
    expect(or().setItemState).toHaveBeenCalledTimes(1)
    expect(row(KEY.errors).dataset.state).toBe('DONE')
  })
})

describe('DayView — süzgeç ve bölüm', () => {
  it('"Yalnız bekleyenler" anahtarı onPendingChange(true) çağırır', async () => {
    const { onPendingChange } = renderDay()
    await loaded()
    const sw = slot('or-pending-toggle')
    expect(sw).toHaveAttribute('aria-checked', 'false')
    fireEvent.click(sw)
    expect(onPendingChange).toHaveBeenCalledWith(true)
  })

  it('pendingOnly açıkken çözülmüş satırlar gizlenir; hepsi çözülen bölümde "tamam" satırı kalır', async () => {
    renderDay({ pendingOnly: true })
    await loaded()
    expect(row(KEY.dash)).not.toBeNull()
    expect(row(KEY.errors)).not.toBeNull()
    expect(row(KEY.optional)).not.toBeNull()
    expect(row(KEY.weekly)).not.toBeNull()
    expect(row(KEY.incidents)).toBeNull()
    expect(row(KEY.queue)).toBeNull()
    expect(row(KEY.monthly)).toBeNull()
    const month = document.querySelector('[data-slot="or-section"][data-section="s-month"]')
    expect(slot('or-section-all-done', month)).not.toBeNull()
  })

  it('"Bölümü tamamla" yalnız BEKLEYENLERİ gönderir; "Geri al" yalnız değişenleri expected_state=DONE ile geri alır', async () => {
    renderDay()
    await loaded()
    or().bulkItemState.mockResolvedValueOnce(ok({
      results: [
        { task_id: 't-dash', period_kind: 'DAY', period_key: TODAY, status: 'done', item: doneItem('t-dash', '2026-11-26T10:43:00Z') },
        { task_id: 't-errors', period_kind: 'DAY', period_key: TODAY, status: 'done', item: doneItem('t-errors', '2026-11-26T10:43:00Z') },
      ],
      progress: { required: 5, done: 4, skipped: 1, pending: 0, overdue: 0, optional_pending: 1, problems_open: 1 },
    }))
    fireEvent.click(sectionComplete('s-start'))
    await waitFor(() => expect(toastMock.action).toHaveBeenCalledTimes(1))
    const [teamArg, body] = or().bulkItemState.mock.calls[0]
    expect(teamArg).toBe(TEAM_ID)
    expect(body).toEqual({
      date: TODAY, state: 'DONE',
      items: [
        { task_id: 't-dash', period_kind: 'DAY', period_key: TODAY, expected_state: 'PENDING' },
        { task_id: 't-errors', period_kind: 'DAY', period_key: TODAY, expected_state: 'PENDING' },
      ],
    })
    expect(row(KEY.dash).dataset.state).toBe('DONE')
    expect(row(KEY.errors).dataset.state).toBe('DONE')
    const [msg, opts] = toastMock.action.mock.calls[0]
    expect(msg).toBe(tr('or.sec.doneToast', 2, 'Gün başlangıcı'))
    expect(opts).toMatchObject({ label: TR['or.undo'], duration: UNDO_MS })
    expect(UNDO_MS).toBe(10_000)
    expect(refreshRoutineBadge).toHaveBeenCalled()

    or().bulkItemState.mockResolvedValueOnce(ok({
      results: [
        { task_id: 't-dash', period_kind: 'DAY', period_key: TODAY, status: 'done', item: pendingItem('t-dash') },
        { task_id: 't-errors', period_kind: 'DAY', period_key: TODAY, status: 'done', item: pendingItem('t-errors') },
      ],
    }))
    await act(async () => { await opts.onAction() })
    expect(or().bulkItemState).toHaveBeenCalledTimes(2)
    const undo = or().bulkItemState.mock.calls[1][1]
    expect(undo.state).toBe('PENDING')
    expect(undo.items).toHaveLength(2)
    expect(undo.items).toEqual(expect.arrayContaining([
      { task_id: 't-dash', period_kind: 'DAY', period_key: TODAY, expected_state: 'DONE' },
      { task_id: 't-errors', period_kind: 'DAY', period_key: TODAY, expected_state: 'DONE' },
    ]))
    // Önceden yapılmış görev geri alınmaz
    expect(undo.items.map((i) => i.task_id)).not.toContain('t-incidents')
    expect(toastMock.info).toHaveBeenCalledWith(tr('or.sec.undone', 2))
    expect(row(KEY.dash).dataset.state).toBe('PENDING')
    expect(row(KEY.errors).dataset.state).toBe('PENDING')
    expect(row(KEY.incidents).dataset.state).toBe('DONE')
  })

  it('"Bölümü tamamla" reddedilirse satırlar eski durumuna döner, geri alma bildirimi çıkmaz', async () => {
    renderDay()
    await loaded()
    or().bulkItemState.mockResolvedValueOnce(fail(409, 'DAY_LOCKED', { error: 'Gün düzenleme süresinin dışında.' }))
    fireEvent.click(sectionComplete('s-start'))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith('Gün düzenleme süresinin dışında.', expect.anything()))
    expect(row(KEY.dash).dataset.state).toBe('PENDING')
    expect(row(KEY.errors).dataset.state).toBe('PENDING')
    expect(toastMock.action).not.toHaveBeenCalled()
  })
})

describe('DayView — bağlantılar ve canlı sayaçlar', () => {
  it('altı bağlantı çip olarak çizilir (yeni sekme, noopener); etiketsiz bağlantı sunucu + yolla gösterilir', async () => {
    renderDay()
    await loaded()
    const links = [...row(KEY.dash).querySelectorAll('[data-slot="or-task-link"]')]
    expect(links).toHaveLength(6)
    expect(links.map((a) => a.getAttribute('href'))).toEqual(sixLinks().map((l) => l.url))
    for (const a of links) {
      expect(a).toHaveAttribute('target', '_blank')
      expect(a.getAttribute('rel')).toContain('noopener')
    }
    expect(within(row(KEY.dash)).getByText('status.example.com/incidents')).toBeTruthy()
  })

  it('"Tümünü aç" her bağlantıyı yeni sekmede açar; açılır pencere engellenince satırın altında söylenir', async () => {
    renderDay()
    await loaded()
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => ({}))
    const btn = within(row(KEY.dash)).getByRole('button', { name: tr('or.task.openAll', 6) })
    fireEvent.click(btn)
    expect(openSpy).toHaveBeenCalledTimes(6)
    expect(openSpy.mock.calls.map((c) => c[0])).toEqual(sixLinks().map((l) => l.url))
    expect(openSpy.mock.calls.every((c) => c[1] === '_blank')).toBe(true)
    expect(slot('or-links-blocked', row(KEY.dash))).toBeNull()

    openSpy.mockImplementation(() => null)
    fireEvent.click(btn)
    const blocked = slot('or-links-blocked', row(KEY.dash))
    expect(blocked).not.toBeNull()
    expect(blocked.textContent).toContain(TR['or.task.popupBlocked'])
  })

  it('sayaç çipleri: değer + ton; okunamayan "—" nötr; kısmi "≥ n"; gizli çizilmez', async () => {
    renderDay()
    await loaded()
    await waitFor(() => expect(chip(KEY.dash, 'open_alerts')?.textContent).toContain('3'))
    expect(or().signals).toHaveBeenCalledWith(TEAM_ID, { fresh: false })
    const alerts = chip(KEY.dash, 'open_alerts')
    expect(alerts.textContent).toBe(`${TR['or.sig.open_alerts.label']}3`)
    expect(alerts.dataset.tone).toBe('warn')
    expect(alerts).not.toBeDisabled()
    const down = chip(KEY.dash, 'down_monitors')
    expect(down.textContent.endsWith('—')).toBe(true)
    expect(down.dataset.tone).toBe('neutral')
    expect(down).toBeDisabled()
    expect(chip(KEY.dash, 'stale_monitors')).toBeNull()
    const incidents = chip(KEY.incidents, 'open_incidents')
    expect(incidents.textContent.endsWith('≥ 12')).toBe(true)
  })

  it('sayaçlar okunamazsa her çip "—" (gri) — hiçbir zaman 0 ya da yeşil değil', async () => {
    or().signals.mockImplementation(() => Promise.resolve(fail(503, 'SERVICE_UNAVAILABLE')))
    renderDay()
    await loaded()
    await waitFor(() => expect(chip(KEY.dash, 'open_alerts')?.textContent.endsWith('—')).toBe(true))
    for (const sig of ['open_alerts', 'down_monitors', 'stale_monitors']) {
      expect(chip(KEY.dash, sig).dataset.tone).toBe('neutral')
      expect(chip(KEY.dash, sig).textContent.endsWith('—')).toBe(true)
    }
    expect(chip(KEY.monthly, 'expiring_certs').dataset.tone).toBe('neutral')
  })
})

describe('DayView — günü tamamla / yeniden aç', () => {
  it('alt çubuktaki "Günü tamamla" pencereyi açar (bildirim ayarı okunur)', async () => {
    renderDay()
    await loaded()
    expect(slot('or-complete-dialog')).toBeNull()
    fireEvent.click(slot('or-complete'))
    await waitFor(() => expect(slot('or-complete-dialog')).not.toBeNull())
    expect(or().template).toHaveBeenCalledWith(TEAM_ID, expect.objectContaining({ signal: expect.anything() }))
    expect(slot('or-complete-pending').textContent).toContain(LONG_TITLE)
  })

  it('tamamlanmış gün: alt çubukta kim · saat + "Yeniden aç" → onay → reopenDay(version) → yeniden okunur', async () => {
    or().day.mockReset()
    or().day
      .mockResolvedValueOnce(ok(buildCompletedSheet()))
      .mockImplementation(() => Promise.resolve(ok(buildDaySheet({ day: { version: 5 } }))))
    renderDay()
    await loaded()
    expect(slot('or-day').dataset.status).toBe('COMPLETED')
    expect(slot('or-complete')).toBeNull()
    expect(slot('or-bottom-bar').textContent).toContain(tr('or.bar.completed', OTHER.name, '13:40'))
    expect(slot('or-section-complete')).toBeNull()
    or().reopenDay.mockResolvedValueOnce(ok({ day: { status: 'OPEN', version: 5 } }))
    fireEvent.click(slot('or-reopen'))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByText(TR['or.complete.reopenTitle'])).toBeTruthy()
    fireEvent.click(within(dlg).getByRole('button', { name: TR['or.complete.reopen'] }))
    await waitFor(() => expect(or().reopenDay).toHaveBeenCalledWith(TEAM_ID, TODAY, { version: 4 }))
    await waitFor(() => expect(toastMock.success).toHaveBeenCalledWith(TR['or.complete.reopened']))
    await waitFor(() => expect(slot('or-day').dataset.status).toBe('OPEN'))
    expect(or().day).toHaveBeenCalledTimes(2)
  })

  it('yeniden açma onayından vazgeçilirse istek gitmez', async () => {
    or().day.mockImplementation(() => Promise.resolve(ok(buildCompletedSheet())))
    renderDay()
    await loaded()
    fireEvent.click(slot('or-reopen'))
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: TR['dlg.cancel'] }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(or().reopenDay).not.toHaveBeenCalled()
  })
})
