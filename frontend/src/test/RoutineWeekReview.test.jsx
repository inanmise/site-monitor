import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, fireEvent, waitFor, act } from './test-utils.jsx'

/**
 * Nöbet Rutinleri — hafta görünümü ve hafta şeridi inceleme düzeltmeleri (2026-11-26):
 *  #16 sunucunun bugünü (bugün satırı; yoksa yanıt zarfının `timestamp`'i; ikisi de yoksa kısıt yok) − 7 günden eski gün
 *      bağlam olamaz: haftanın geçmiş iş günlerinin hepsi pencere dışındaysa satırlar salt okunur, `or-week-locked` bandı,
 *      devir notu salt okunur; bilinmeyen bugün → kısıt konmaz (sunucu karar verir); bugün satırı zarftan önce gelir;
 *  #9  panel (atla / not) kapanınca odak satırın onay kutusuna döner;
 *  #18 WeekStrip: tatil adı hapın erişilebilir adında (`… · Tatil: <ad>`) ve `title`'ında, `data-holiday`.
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
import WeekView from '../components/oncallroutine/WeekView.jsx'
import WeekStrip from '../components/oncallroutine/WeekStrip.jsx'
import { TR } from '../i18n/tr.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { KEY, ME, OTHER, TEAM_ID, TODAY, buildWeekResponse, ok } from './helpers/oncallRoutineFixtures.js'

const or = () => api.oncallRoutines
const OR_FNS = ['week', 'setItemState', 'bulkItemState', 'saveItemNote', 'saveHandoverNote', 'ackHandover', 'problems', 'events']
const tr = (k, ...a) => TR[k].replace(/\{(\d+)\}/g, (_, i) => String(a[Number(i)] ?? ''))
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const check = (key) => document.querySelector(`[data-slot="or-task-check"][data-key="${key}"]`)
const taskRow = (key) => document.querySelector(`[data-slot="or-task"][data-key="${key}"]`)
const handoverArea = () => slot('or-handover-note').querySelector('textarea')
/** Sunucu yanıt zarfının anı (İstanbul 2026-11-26 13:00). */
const STAMP_TODAY = '2026-11-26T10:00:00Z'

/** Tek bir gün satırı (hafta yanıtı biçiminde). */
function mkDay(date, extra = {}) {
  return {
    date, workday: true, holiday_name: null, status: 'FUTURE', required: null, resolved: null, done: null, skipped: null,
    problems_opened: 0, responsible_name: null, completed_by_name: null, completed_at: null, completed_late: false,
    today: false, ...extra,
  }
}
/** Verilen Pazartesi'den başlayan, bugün satırı OLMAYAN geçmiş bir hafta (`workdays` iş günü; kalanı iş günü değil). */
function pastWeek(monday, workdays = 5) {
  const dates = Array.from({ length: 7 }, (_, i) => {
    const d = new Date(Date.parse(`${monday}T00:00:00Z`) + i * 86_400_000)
    return d.toISOString().slice(0, 10)
  })
  return {
    ...buildWeekResponse(),
    week: { key: monday, from: monday, to: dates[6] },
    days: dates.map((date, i) => (i < workdays
      ? mkDay(date, { status: 'COMPLETED', required: 5, resolved: 5, responsible_name: OTHER.name })
      : mkDay(date, { workday: false, status: 'NOT_WORKDAY' }))),
    responsible_by_day: {},
  }
}
const withStamp = (data, timestamp) => ({ ...ok(data), timestamp })

function renderWeek(props = {}) {
  const all = { onWeekChange: vi.fn(), onOpenDay: vi.fn(), onEditTemplate: vi.fn(), ...props }
  const utils = render(<WeekView teamId={TEAM_ID} week={null} meName={ME.name} {...all} />)
  return { ...utils, props: all }
}
const loaded = () => waitFor(() => expect(slot('or-week-days')).not.toBeNull())

/** Not düzenleyicisinde Esc (kapanış asenkron; ayrıntı RoutineDayReview.test.jsx `escapeNote`). */
async function escapeNote(area) {
  await act(async () => {
    fireEvent.keyDown(area, { key: 'Escape' })
    for (let i = 0; i < 10; i += 1) await Promise.resolve()
  })
}

function press(key, opts = {}) {
  const target = document.activeElement && document.activeElement !== document.documentElement ? document.activeElement : document.body
  const ev = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...opts })
  act(() => { target.dispatchEvent(ev) })
  return ev
}

beforeEach(() => {
  for (const n of OR_FNS) or()[n].mockReset()
  for (const f of Object.values(toastMock)) f.mockReset()
  refreshRoutineBadge.mockReset()
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
  or().week.mockImplementation(() => Promise.resolve(ok(buildWeekResponse())))
  or().setItemState.mockImplementation(() => new Promise(() => {}))
})
afterEach(() => {
  vi.restoreAllMocks()
})

describe('WeekView #16 — düzenleme penceresi (7 gün)', () => {
  it('eski hafta + zarf anı: iş günlerinin hepsi pencere dışında → kilit bandı, satırlar ve devir notu salt okunur', async () => {
    or().week.mockImplementation(() => Promise.resolve(withStamp(pastWeek('2026-11-09'), STAMP_TODAY)))
    renderWeek({ week: '2026-11-09' })
    await loaded()
    expect(slot('or-week').dataset.week).toBe('2026-11-09')
    const band = slot('or-week-locked')
    expect(band).not.toBeNull()
    expect(band.dataset.reason).toBe('TOO_OLD')
    expect(band.textContent).toBe(TR['or.lock.TOO_OLD'])
    expect(check(KEY.weekly)).toBeDisabled()
    expect(check(KEY.monthly)).toBeDisabled()
    fireEvent.click(check(KEY.weekly))
    fireEvent.click(check(KEY.monthly))
    expect(or().setItemState).not.toHaveBeenCalled()
    expect(taskRow(KEY.weekly).dataset.state).toBe('PENDING')
    expect(handoverArea().readOnly).toBe(true)
  })

  it('aynı eski hafta, zarf anı ve bugün satırı yok → bugün bilinmiyor: kısıt konmaz, haftanın son iş günüyle gönderilir', async () => {
    or().week.mockImplementation(() => Promise.resolve(ok(pastWeek('2026-11-09'))))
    renderWeek({ week: '2026-11-09' })
    await loaded()
    expect(slot('or-week-locked')).toBeNull()
    expect(check(KEY.weekly)).not.toBeDisabled()
    fireEvent.click(check(KEY.weekly))
    expect(or().setItemState).toHaveBeenCalledWith(TEAM_ID, expect.objectContaining({
      date: '2026-11-13', task_id: 't-weekly-report', state: 'DONE', expected_state: 'PENDING',
    }))
    expect(handoverArea().readOnly).toBe(false)
  })

  it('bu hafta (bugün satırı var) düzenlenebilir; bugün satırı zarf anından önce gelir', async () => {
    // Zarf anı ileri bir gün dese de (pencere her günü dışarıda bırakırdı) bugün satırı esastır
    or().week.mockImplementation(() => Promise.resolve(withStamp(buildWeekResponse(), '2026-12-20T10:00:00Z')))
    renderWeek()
    await loaded()
    expect(slot('or-week-locked')).toBeNull()
    expect(check(KEY.weekly)).not.toBeDisabled()
    expect(check(KEY.monthly)).not.toBeDisabled()
    fireEvent.click(check(KEY.weekly))
    expect(or().setItemState).toHaveBeenCalledWith(TEAM_ID, expect.objectContaining({ date: TODAY, task_id: 't-weekly-report' }))
    expect(handoverArea().readOnly).toBe(false)
  })

  it('pencere sınırı: bugün − 7 gün dahil (bağlam olur), bir gün önce son iş günüyse kilit', async () => {
    // Hafta 16–22 Kasım: 16–19 iş günü → son iş günü 19 Kasım = 26 − 7 → yazılabilir
    or().week.mockImplementation(() => Promise.resolve(withStamp(pastWeek('2026-11-16', 4), STAMP_TODAY)))
    const first = renderWeek({ week: '2026-11-16' })
    await loaded()
    expect(slot('or-week-locked')).toBeNull()
    fireEvent.click(check(KEY.weekly))
    expect(or().setItemState).toHaveBeenCalledWith(TEAM_ID, expect.objectContaining({ date: '2026-11-19', task_id: 't-weekly-report' }))
    first.unmount()

    // 16–18 iş günü → son iş günü 18 Kasım pencerenin dışında → kilit
    or().setItemState.mockClear()
    or().week.mockImplementation(() => Promise.resolve(withStamp(pastWeek('2026-11-16', 3), STAMP_TODAY)))
    renderWeek({ week: '2026-11-16' })
    await loaded()
    expect(slot('or-week-locked')).not.toBeNull()
    expect(check(KEY.weekly)).toBeDisabled()
    fireEvent.click(check(KEY.weekly))
    expect(or().setItemState).not.toHaveBeenCalled()
  })

  it('devir notu dönem kuralıyla: önceki dönem yazılabilir, gelecek dönem salt okunur (sunucu yalnız geçerli / önceki döneme yazar)', async () => {
    const hc = (from, to) => ({ period_key: from, from, to, note: '', note_version: 0, note_by_name: null, note_at: null })
    or().week.mockImplementation(() => Promise.resolve(withStamp(
      { ...pastWeek('2026-11-16', 4), handover_current: hc('2026-11-16', '2026-11-22') }, STAMP_TODAY)))
    const first = renderWeek({ week: '2026-11-16' })
    await loaded()
    expect(handoverArea().readOnly).toBe(false)
    first.unmount()

    const next = ['2026-11-30', '2026-12-01', '2026-12-02', '2026-12-03', '2026-12-04', '2026-12-05', '2026-12-06']
    or().week.mockImplementation(() => Promise.resolve(withStamp({
      ...buildWeekResponse(),
      week: { key: '2026-11-30', from: '2026-11-30', to: '2026-12-06' },
      days: next.map((date, i) => (i < 5 ? mkDay(date) : mkDay(date, { workday: false, status: 'NOT_WORKDAY' }))),
      responsible_by_day: {},
      handover_current: hc('2026-11-30', '2026-12-06'),
    }, STAMP_TODAY)))
    renderWeek({ week: '2026-11-30' })
    await loaded()
    expect(slot('or-week-locked')).toBeNull()
    expect(handoverArea().readOnly).toBe(true)
  })

  it('çalışma izni yoksa kilit bandı çizilmez (zaten salt okunur)', async () => {
    or().week.mockImplementation(() => Promise.resolve(withStamp({ ...pastWeek('2026-11-09'), can_work: false }, STAMP_TODAY)))
    renderWeek({ week: '2026-11-09' })
    await loaded()
    expect(slot('or-week-locked')).toBeNull()
    expect(check(KEY.weekly)).toBeDisabled()
  })
})

describe('WeekView #9 — panel kapanınca odak satıra döner', () => {
  it('atlama paneli Esc ile kapanınca odak o satırın onay kutusuna döner', async () => {
    renderWeek()
    await loaded()
    act(() => { check(KEY.weekly).focus() })
    press('s')
    const input = await waitFor(() => {
      const el = slot('or-skip-reason', taskRow(KEY.weekly))
      expect(el).not.toBeNull()
      return el
    })
    await waitFor(() => expect(document.activeElement).toBe(input))
    fireEvent.keyDown(input, { key: 'Escape' })
    await waitFor(() => expect(slot('or-skip-reason')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(check(KEY.weekly)))
    expect(or().setItemState).not.toHaveBeenCalled()
  })

  it('not paneli Esc ile kapanınca odak o satırın onay kutusuna döner', async () => {
    renderWeek()
    await loaded()
    fireEvent.click(slot('or-task-note-toggle', taskRow(KEY.monthly)))
    const area = await waitFor(() => {
      const el = taskRow(KEY.monthly).querySelector('[data-slot="or-task-note"] textarea')
      expect(el).not.toBeNull()
      return el
    })
    await waitFor(() => expect(document.activeElement).toBe(area))
    await escapeNote(area)
    await waitFor(() => expect(taskRow(KEY.monthly).querySelector('[data-slot="or-task-note"]')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(check(KEY.monthly)))
    expect(or().saveItemNote).not.toHaveBeenCalled()
  })
})

describe('WeekStrip #18 — tatil adı', () => {
  const week = {
    key: '2026-10-26', from: '2026-10-26', to: '2026-11-01',
    days: [
      { date: '2026-10-26', workday: true, status: 'COMPLETED', required: 5, resolved: 5 },
      { date: '2026-10-27', workday: true, status: 'COMPLETED', required: 5, resolved: 5 },
      { date: '2026-10-28', workday: true, status: 'INCOMPLETE', required: 5, resolved: 2 },
      { date: '2026-10-29', workday: false, status: 'NOT_WORKDAY', required: null, resolved: null, holiday_name: ' Cumhuriyet Bayramı ' },
      { date: '2026-10-30', workday: true, status: 'OPEN', required: 5, resolved: 1, today: true },
      { date: '2026-10-31', workday: false, status: 'NOT_WORKDAY', required: null, resolved: null, holiday_name: '   ' },
      { date: '2026-11-01', workday: false, status: 'NOT_WORKDAY', required: null, resolved: null, holiday_name: null },
    ],
  }
  const pill = (date) => document.querySelector(`[data-slot="or-week-day"][data-date="${date}"]`)
  const renderStrip = () => render(
    <WeekStrip week={week} date="2026-10-30" today="2026-10-30" onSelect={vi.fn()} onShiftWeek={vi.fn()} onToday={vi.fn()} />,
  )

  it('tatil günü: erişilebilir adda "· Tatil: <ad>", ipucunda (title) ad, data-holiday; ad kırpılır', () => {
    renderStrip()
    const p = pill('2026-10-29')
    const base = tr('or.week.dayAria', TR['or.weekday.4'], '29.10.2026', TR['or.status.NOT_WORKDAY'], '—')
    expect(p.getAttribute('aria-label')).toBe(`${base} · ${tr('or.week.holiday', 'Cumhuriyet Bayramı')}`)
    expect(p.getAttribute('title')).toBe('Cumhuriyet Bayramı')
    expect(p.dataset.holiday).toBe('true')
    expect(p.dataset.status).toBe('NOT_WORKDAY')
  })

  it('tatil olmayan / boş adlı gün: tatil eki, ipucu ve data-holiday yok', () => {
    renderStrip()
    for (const date of ['2026-10-28', '2026-10-31', '2026-11-01']) {
      const p = pill(date)
      expect(p.getAttribute('aria-label'), date).not.toContain(tr('or.week.holiday', ''))
      expect(p.hasAttribute('title'), date).toBe(false)
      expect(p.dataset.holiday, date).toBeUndefined()
    }
    expect(pill('2026-10-28').getAttribute('aria-label'))
      .toBe(tr('or.week.dayAria', TR['or.weekday.3'], '28.10.2026', TR['or.status.INCOMPLETE'], '2/5'))
  })
})
