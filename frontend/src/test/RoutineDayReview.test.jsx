import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

/**
 * Nöbet Rutinleri — gün sayfası inceleme düzeltmeleri (2026-11-26). Pinlenenler (bulgu numaraları inceleme notundaki):
 *  #2  yoklama birleştirmesi: tik bittikten SONRA dönen ama tikten ÖNCE çıkmış yoklama yanıtı onaylı durumu ezmez (yerleşme
 *      kuralı); aynı satırın (item_id) daha eski sürümü yazılmaz (sürüm kuralı); bölüm tamamlama sürerken gelen yoklama
 *      iyimser durumu ezmez; toplu istek sürerken tekli tık sıraya girer, toplu yanıttan sonra sunucunun onayladığı durumla
 *      gider, toplu sonuç belirsizse hiç gitmez;
 *  #8  `onTodayProgress` bugünün sayılarını bildirir; hafta şeridindeki bugünün hapı canlı sayıyı gösterir;
 *  #9  atla / not paneli kapanınca odak satırın onay kutusuna döner;
 *  #10 gün değişince "Geri al" bildirimi kapanır, geç basılan geri alma hiçbir şey göndermez;
 *  #15 alt çubuk: kısmen tamamlanan gün amber "Kısmen tamamlandı", tam gün "Tamamlandı"; ilerleme metninde aria-live yok,
 *      ayrı canlı bölge yalnız durum değişince söyler;
 *  #16 gelecek / 7 günden eski / duraklatılmış gün: sorun eklenemez, günün notu salt okunur;
 *  #18 geçmiş günde canlı sayaç yok; gün değişince "Günün geçmişi" paneli kapanır.
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
// Yoklama aralığı elle tetiklenir: kanca her çizimde son geri çağrıyı aralığına göre saklar (0 = kurulmadı)
const polls = vi.hoisted(() => ({ fns: new Map() }))
vi.mock('../hooks/useVisibleInterval.js', () => ({
  BUSY_MAX_MS: 120_000,
  useVisibleInterval: (fn, ms) => { if (ms > 0) polls.fns.set(ms, fn) },
}))
// Sorun paneli kendi testinde (RoutineProblems.test.jsx); burada yalnız aldığı yetkileri gösterir.
vi.mock('../components/oncallroutine/ProblemsPanel.jsx', () => ({
  default: (p) => (
    <div data-slot="stub-problems" data-total={String(p.total)} data-can-create={String(p.canCreate)}
      data-can-work={String(p.canWork)} />
  ),
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
import DayView, { DAY_POLL_MS, SIGNALS_POLL_MS } from '../components/oncallroutine/DayView.jsx'
import { TR } from '../i18n/tr.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import {
  KEY, ME, OTHER, TEAM_ID, TEAM_NAME, TODAY, YESTERDAY,
  buildCompletedSheet, buildDaySheet, buildSignals, buildTemplateResponse, deferred, fail, ok, serverItem, withItem,
} from './helpers/oncallRoutineFixtures.js'

const or = () => api.oncallRoutines
const OR_FNS = ['day', 'signals', 'setItemState', 'bulkItemState', 'saveItemNote', 'events', 'reopenDay', 'completeDay',
  'template', 'people', 'assign', 'clearAssignment', 'saveDayNote', 'saveHandoverNote', 'ackHandover', 'problems']
const tr = (k, ...a) => TR[k].replace(/\{(\d+)\}/g, (_, i) => String(a[Number(i)] ?? ''))
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const row = (key) => document.querySelector(`[data-slot="or-task"][data-key="${key}"]`)
const check = (key) => document.querySelector(`[data-slot="or-task-check"][data-key="${key}"]`)
const sectionComplete = (id) => document.querySelector(`[data-slot="or-section"][data-section="${id}"] [data-slot="or-section-complete"]`)
const signalChips = () => document.querySelectorAll('[data-slot="or-task-signal"]')
const todayPill = () => document.querySelector(`[data-slot="or-week-day"][data-date="${TODAY}"]`)
const pillLabel = (resolvedOverRequired) => tr('or.week.dayAria', TR['or.weekday.4'], '26.11.2026', TR['or.status.OPEN'], resolvedOverRequired)

const doneItem = (taskId, at = '2026-11-26T10:43:00Z') => serverItem(taskId, {
  state: 'DONE', state_by_name: ME.name, state_by_me: true, state_at: at, version: 2,
})
const pendingItem = (taskId, version = 3) => serverItem(taskId, {
  state: 'PENDING', state_by_name: null, state_by_me: false, state_at: null, version,
})
/** "Kapasite notlarını güncelle" (isteğe bağlı, satırı henüz yok: item_id null) — sunucu yazınca satırı oluşur. */
const capacityDone = () => serverItem('t-capacity', {
  item_id: 1001, version: 1, state: 'DONE', state_by_name: ME.name, state_by_me: true, state_at: '2026-11-26T05:07:00Z',
})
const bulkStartDone = () => ok({
  results: [
    { task_id: 't-dash', period_kind: 'DAY', period_key: TODAY, status: 'done', item: doneItem('t-dash') },
    { task_id: 't-errors', period_kind: 'DAY', period_key: TODAY, status: 'done', item: doneItem('t-errors') },
  ],
  progress: { required: 5, done: 4, skipped: 1, pending: 0, overdue: 0, optional_pending: 1, problems_open: 1 },
})

function dayEl(props = {}) {
  return (
    <DayView teamId={TEAM_ID} teamName={TEAM_NAME} member canManage={false} date={null} pendingOnly={false}
      meName={ME.name} {...props} />
  )
}
function renderDay(props = {}) {
  const all = { onPendingChange: vi.fn(), onDateChange: vi.fn(), onEditTemplate: vi.fn(), ...props }
  const utils = render(dayEl(all))
  return { ...utils, props: all }
}
const loaded = () => waitFor(() => expect(slot('or-day')).not.toBeNull())

/** Günün yoklama geri çağrısı (kanca bu aralığı kurmuş olmalı — yalnız bugünün sayfasında kurulur). */
function pollFn() {
  const fn = polls.fns.get(DAY_POLL_MS)
  expect(typeof fn).toBe('function')
  return fn
}
/** Yoklamayı başlatır ama bitmesini beklemez; yoklamanın sözünü döner. */
function startPoll() {
  const fn = pollFn()
  let p
  act(() => { p = fn() })
  return p
}
/** Yoklamayı başlatır ve yanıtı uygulanana dek bekler. */
async function runPoll() {
  const fn = pollFn()
  await act(async () => { await fn() })
}
/** Gerçek saat ilerlesin (yerleşme anı ile yeni yoklamanın başlangıcı aynı milisaniyeye düşmesin). */
const elapse = (ms = 5) => new Promise((r) => setTimeout(r, ms))

/**
 * Not düzenleyicisinde Esc: kapanış ASENKRON (önce kayıt denenir, sonra `onClose`). Mikro görevler act içinde boşaltılır ki
 * panelin kalkışı odak-dönüş zamanlayıcısından (setTimeout 0) önce işlensin — tarayıcıdaki sıra (olay önceliğiyle eşzamanlı
 * işleme). Not: değişiklik kaydedildikten sonraki kapanışta bu sıra garanti değildir (raporlandı).
 */
async function escapeNote(area) {
  await act(async () => {
    fireEvent.keyDown(area, { key: 'Escape' })
    for (let i = 0; i < 10; i += 1) await Promise.resolve()
  })
}

/** Odaktaki öğeye tuş basar (sayfa kısayolları pencerede dinlenir). */
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
  polls.fns.clear()
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

describe('DayView #2 — yoklama birleştirmesi', () => {
  it('yerleşme kuralı: tikten ÖNCE çıkıp tik bittikten SONRA dönen yoklama onaylı "yapıldı"yı ezmez; yeniden tık expected_state=DONE ile gider', async () => {
    renderDay()
    await loaded()
    expect(row(KEY.optional).dataset.state).toBe('PENDING')
    const pollD = deferred()
    or().day.mockImplementationOnce(() => pollD.promise)
    const pollP = startPoll()
    expect(or().day).toHaveBeenCalledTimes(2)

    or().setItemState.mockResolvedValueOnce(ok({ item: capacityDone(), changed: true }))
    fireEvent.click(check(KEY.optional))
    expect(or().setItemState).toHaveBeenCalledWith(TEAM_ID, expect.objectContaining({
      task_id: 't-capacity', state: 'DONE', expected_state: 'PENDING',
    }))
    // Yazım tamamlandı (onChanged → rozet tazeleme, kuyruk boşaldı): satır sunucunun onaylı maddesi
    await waitFor(() => expect(refreshRoutineBadge).toHaveBeenCalled())
    expect(row(KEY.optional).dataset.state).toBe('DONE')
    await waitFor(() => expect(within(row(KEY.optional)).queryByText('· 08:07')).not.toBeNull())

    // Eski yoklama şimdi döner: madde yanıtta hâlâ bekliyor ve satırı yok (item_id null, sürüm 1)
    await act(async () => { pollD.resolve(ok(buildDaySheet())); await pollP })
    expect(row(KEY.optional).dataset.state).toBe('DONE')
    expect(within(row(KEY.optional)).getByText('· 08:07')).toBeTruthy()

    // Sonraki tık sunucunun onayladığı durumu temel alır
    or().setItemState.mockImplementationOnce(() => new Promise(() => {}))
    fireEvent.click(check(KEY.optional))
    expect(or().setItemState).toHaveBeenCalledTimes(2)
    expect(or().setItemState.mock.calls[1][1]).toEqual({
      date: TODAY, task_id: 't-capacity', period_kind: 'DAY', period_key: TODAY, state: 'PENDING', expected_state: 'DONE',
      skip_reason: null,
    })
  })

  it('tik yerleştikten SONRA çıkan yoklama sunucunun yeni sürümünü yazar (aynı item_id, sürüm 2 → bekliyor)', async () => {
    renderDay()
    await loaded()
    or().setItemState.mockResolvedValueOnce(ok({ item: capacityDone(), changed: true }))
    fireEvent.click(check(KEY.optional))
    await waitFor(() => expect(refreshRoutineBadge).toHaveBeenCalled())
    expect(row(KEY.optional).dataset.state).toBe('DONE')
    await elapse()

    or().day.mockResolvedValueOnce(ok(withItem(buildDaySheet(), 't-capacity', {
      item_id: 1001, version: 2, state: 'PENDING', state_by_name: null, state_by_me: false, state_at: null,
    })))
    await runPoll()
    expect(or().day).toHaveBeenCalledTimes(2)
    expect(row(KEY.optional).dataset.state).toBe('PENDING')
  })

  it('yerleşme SIRAYLA anılır, saatle değil: saat donukken (ya da geri giderken) yerleşmiş satırı sonraki yoklama yine yazar', async () => {
    // Gerçek hata (2026-11-26 e2e, sabit saat): yerleşme anı = okuma başlangıcı (aynı ms) → madde sonsuza dek "okumadan sonra
    // yerleşti" sayılıyor, günü tamamlama sonrası okuma ATLANDI satırları "bekliyor" bırakıyordu. NTP geri sarması da aynısı.
    const frozen = Date.now()
    vi.spyOn(Date, 'now').mockReturnValue(frozen)
    renderDay()
    await loaded()
    or().setItemState.mockResolvedValueOnce(ok({ item: capacityDone(), changed: true }))
    fireEvent.click(check(KEY.optional))
    await waitFor(() => expect(refreshRoutineBadge).toHaveBeenCalled())
    expect(row(KEY.optional).dataset.state).toBe('DONE')

    or().day.mockResolvedValueOnce(ok(withItem(buildDaySheet(), 't-capacity', {
      item_id: 1001, version: 2, state: 'SKIPPED', state_by_name: OTHER.name, state_by_me: false, state_at: null,
    })))
    await runPoll()
    expect(row(KEY.optional).dataset.state).toBe('SKIPPED')
  })

  it('sürüm kuralı: aynı satırın (item_id) daha ESKİ sürümü yazılmaz; daha yeni sürüm yazılır', async () => {
    renderDay()
    await loaded()
    // t-incidents: DONE, item_id 993, sürüm 2
    expect(row(KEY.incidents).dataset.state).toBe('DONE')
    or().day.mockResolvedValueOnce(ok(withItem(buildDaySheet(), 't-incidents', {
      version: 1, state: 'PENDING', state_by_name: null, state_by_me: false, state_at: null,
    })))
    await runPoll()
    expect(or().day).toHaveBeenCalledTimes(2)
    expect(row(KEY.incidents).dataset.state).toBe('DONE')
    expect(within(row(KEY.incidents)).getByText(ME.name)).toBeTruthy()

    or().day.mockResolvedValueOnce(ok(withItem(buildDaySheet(), 't-incidents', {
      version: 3, state: 'PENDING', state_by_name: null, state_by_me: false, state_at: null,
    })))
    await runPoll()
    expect(row(KEY.incidents).dataset.state).toBe('PENDING')
  })

  it('satır yeniden oluştuysa (başka item_id) düşük sürümlü yeni satır esastır', async () => {
    renderDay()
    await loaded()
    or().day.mockResolvedValueOnce(ok(withItem(buildDaySheet(), 't-incidents', {
      item_id: 5000, version: 1, state: 'PENDING', state_by_name: null, state_by_me: false, state_at: null,
    })))
    await runPoll()
    expect(row(KEY.incidents).dataset.state).toBe('PENDING')
  })

  it('"Bölümü tamamla" sürerken gelen yoklama iyimser "yapıldı"yı ezmez; toplu yanıt gelince sunucunun maddesi yazılır', async () => {
    renderDay()
    await loaded()
    const bulkD = deferred()
    or().bulkItemState.mockReturnValueOnce(bulkD.promise)
    fireEvent.click(sectionComplete('s-start'))
    expect(row(KEY.dash).dataset.state).toBe('DONE')
    expect(row(KEY.errors).dataset.state).toBe('DONE')

    or().day.mockResolvedValueOnce(ok(buildDaySheet()))
    await runPoll()
    expect(or().day).toHaveBeenCalledTimes(2)
    expect(row(KEY.dash).dataset.state).toBe('DONE')
    expect(row(KEY.errors).dataset.state).toBe('DONE')

    await act(async () => { bulkD.resolve(bulkStartDone()) })
    await waitFor(() => expect(toastMock.action).toHaveBeenCalledTimes(1))
    expect(row(KEY.dash).dataset.state).toBe('DONE')
    expect(row(KEY.errors).dataset.state).toBe('DONE')
    expect(within(row(KEY.dash)).getByText('· 13:43')).toBeTruthy()
    expect(within(row(KEY.errors)).getByText('· 13:43')).toBeTruthy()
  })

  it('toplu istek sürerken o satıra tek tık: hemen gönderilmez; toplu yanıttan sonra onaylı durumla (DONE) bir kez gider', async () => {
    renderDay()
    await loaded()
    const bulkD = deferred()
    or().bulkItemState.mockReturnValueOnce(bulkD.promise)
    or().setItemState.mockImplementation((_t, b) => Promise.resolve(ok({ item: pendingItem(b.task_id) })))
    fireEvent.click(sectionComplete('s-start'))
    expect(row(KEY.errors).dataset.state).toBe('DONE')
    fireEvent.click(check(KEY.errors))   // iyimser DONE → PENDING niyeti (sıraya)
    expect(row(KEY.errors).dataset.state).toBe('PENDING')
    expect(or().setItemState).not.toHaveBeenCalled()

    await act(async () => { bulkD.resolve(bulkStartDone()) })
    await waitFor(() => expect(or().setItemState).toHaveBeenCalledTimes(1))
    expect(or().setItemState.mock.calls[0][1]).toEqual({
      date: TODAY, task_id: 't-errors', period_kind: 'DAY', period_key: TODAY, state: 'PENDING', expected_state: 'DONE',
      skip_reason: null,
    })
    await waitFor(() => expect(row(KEY.errors).dataset.state).toBe('PENDING'))
    expect(row(KEY.dash).dataset.state).toBe('DONE')
    expect(or().setItemState).toHaveBeenCalledTimes(1)
    expect(or().bulkItemState).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['ağ hatası yanıtı (durum 0)', (d) => d.resolve(fail(0, 'NETWORK_ERROR'))],
    ['istek istisnası', (d) => d.reject(new Error('Failed to fetch'))],
  ])('toplu sonuç belirsizse (%s) sıradaki tek tık GÖNDERİLMEZ; gün yeniden okunur', async (_name, settle) => {
    renderDay()
    await loaded()
    const bulkD = deferred()
    or().bulkItemState.mockReturnValueOnce(bulkD.promise)
    fireEvent.click(sectionComplete('s-start'))
    fireEvent.click(check(KEY.errors))
    expect(or().setItemState).not.toHaveBeenCalled()

    await act(async () => { settle(bulkD) })
    await waitFor(() => expect(toastMock.info).toHaveBeenCalledWith(TR['or.task.unsure'], 6000))
    await waitFor(() => expect(or().day).toHaveBeenCalledTimes(2))
    await act(async () => { await elapse() })
    expect(or().setItemState).not.toHaveBeenCalled()
    expect(toastMock.action).not.toHaveBeenCalled()
    expect(row(KEY.errors).dataset.state).toBe('PENDING')
    expect(row(KEY.dash).dataset.state).toBe('PENDING')
  })
})

describe('DayView #8 — bugünün ilerlemesi', () => {
  it('onTodayProgress bugünün sayılarını bildirir, tikle güncellenir; hafta şeridindeki bugünün hapı canlı sayıyı gösterir', async () => {
    const onTodayProgress = vi.fn()
    renderDay({ onTodayProgress })
    await loaded()
    await waitFor(() => expect(onTodayProgress).toHaveBeenLastCalledWith({
      team_id: TEAM_ID, date: TODAY, today: { resolved: 3, required: 5, overdue: 2, status: 'OPEN' },
    }))
    expect(todayPill().getAttribute('aria-label')).toBe(pillLabel('3/5'))

    or().setItemState.mockImplementationOnce(() => new Promise(() => {}))
    fireEvent.click(check(KEY.errors))
    await waitFor(() => expect(onTodayProgress).toHaveBeenLastCalledWith({
      team_id: TEAM_ID, date: TODAY, today: { resolved: 4, required: 5, overdue: 1, status: 'OPEN' },
    }))
    expect(todayPill().getAttribute('aria-label')).toBe(pillLabel('4/5'))
    expect(todayPill().textContent).toContain('4/5')
    expect(todayPill().getAttribute('aria-current')).toBe('date')
  })
})

describe('DayView #9 — panel kapanınca odak satıra döner', () => {
  it('atlama paneli Esc ile kapanınca odak o satırın onay kutusuna döner', async () => {
    renderDay()
    await loaded()
    act(() => { check(KEY.errors).focus() })
    press('s')
    const input = await waitFor(() => {
      const el = slot('or-skip-reason', row(KEY.errors))
      expect(el).not.toBeNull()
      return el
    })
    await waitFor(() => expect(document.activeElement).toBe(input))
    fireEvent.keyDown(input, { key: 'Escape' })
    await waitFor(() => expect(slot('or-skip-reason')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(check(KEY.errors)))
    expect(or().setItemState).not.toHaveBeenCalled()
  })

  it('not paneli Esc ile kapanınca odak satırın onay kutusuna döner (değişiklik yok → kayıt isteği yok)', async () => {
    renderDay()
    await loaded()
    fireEvent.click(slot('or-task-note-toggle', row(KEY.errors)))
    const area = await waitFor(() => {
      const el = row(KEY.errors).querySelector('[data-slot="or-task-note"] textarea')
      expect(el).not.toBeNull()
      return el
    })
    await waitFor(() => expect(document.activeElement).toBe(area))
    await escapeNote(area)
    await waitFor(() => expect(row(KEY.errors).querySelector('[data-slot="or-task-note"]')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(check(KEY.errors)))
    expect(or().saveItemNote).not.toHaveBeenCalled()
  })
})

describe('DayView #10 — "Geri al" bildirimi gün değişince', () => {
  it('gün değişince bildirim kapatılır (dismiss); geç basılan "Geri al" başka günde hiçbir şey göndermez', async () => {
    const doneThere = { state: 'DONE', state_by_name: ME.name, state_by_me: true, state_at: '2026-11-25T07:00:00Z', version: 2 }
    // Dünün sayfasında aynı anahtarlı maddeler YAPILDI: tarih denetimi olmasa geri alma bunları gönderirdi
    const yesterday = withItem(withItem(buildDaySheet({ date: YESTERDAY }), 't-dash', doneThere), 't-errors', doneThere)
    or().day.mockImplementation((_t, d) => Promise.resolve(ok(d === YESTERDAY ? yesterday : buildDaySheet())))
    toastMock.action.mockReturnValue('toast-1')
    or().bulkItemState.mockResolvedValueOnce(bulkStartDone())
    const { rerender, props } = renderDay()
    await loaded()
    fireEvent.click(sectionComplete('s-start'))
    await waitFor(() => expect(toastMock.action).toHaveBeenCalledTimes(1))
    const opts = toastMock.action.mock.calls[0][1]
    expect(typeof opts.onAction).toBe('function')
    expect(toastMock.dismiss).not.toHaveBeenCalled()

    rerender(dayEl({ ...props, date: YESTERDAY }))
    await waitFor(() => expect(slot('or-day')?.dataset.date).toBe(YESTERDAY))
    expect(toastMock.dismiss).toHaveBeenCalledWith('toast-1')
    expect(row(KEY.dash).dataset.state).toBe('DONE')

    await act(async () => { await opts.onAction() })
    expect(or().bulkItemState).toHaveBeenCalledTimes(1)
    expect(toastMock.info).not.toHaveBeenCalled()
    expect(row(KEY.dash).dataset.state).toBe('DONE')
    expect(row(KEY.errors).dataset.state).toBe('DONE')
  })
})

describe('DayView #15 — alt çubuk', () => {
  it('kısmen tamamlanan gün: çubuk ve durum amber "Kısmen tamamlandı" (partial), canlı bölge onu söyler', async () => {
    or().day.mockImplementation(() => Promise.resolve(ok(buildCompletedSheet({
      day: { status: 'COMPLETED_PARTIAL', version: 4, completed_by_name: OTHER.name, completed_at: '2026-11-26T10:40:00Z' },
    }))))
    renderDay()
    await loaded()
    expect(slot('or-day').dataset.status).toBe('COMPLETED_PARTIAL')
    expect(slot('or-bottom-bar').dataset.state).toBe('partial')
    const st = slot('or-bar-state')
    expect(st.dataset.state).toBe('partial')
    expect(st.textContent).toBe(TR['or.status.COMPLETED_PARTIAL'])
    expect(st.textContent).not.toBe(TR['or.bar.done'])
    expect(slot('or-bar-live').textContent).toBe(TR['or.status.COMPLETED_PARTIAL'])
  })

  it('tam tamamlanan gün: durum yeşil "Tamamlandı" (done)', async () => {
    or().day.mockImplementation(() => Promise.resolve(ok(buildCompletedSheet())))
    renderDay()
    await loaded()
    expect(slot('or-bottom-bar').dataset.state).toBe('done')
    expect(slot('or-bar-state').dataset.state).toBe('done')
    expect(slot('or-bar-state').textContent).toBe(TR['or.bar.done'])
    expect(slot('or-bar-live').textContent).toBe(TR['or.bar.done'])
  })

  it('açık gün: ilerleme metninde aria-live yok; canlı bölge boş, son zorunlu görev çözülünce "hepsi çözüldü" der', async () => {
    renderDay()
    await loaded()
    const bar = slot('or-bottom-bar')
    expect(bar.dataset.state).toBe('open')
    expect(bar.querySelector('p').hasAttribute('aria-live')).toBe(false)
    const live = [...bar.querySelectorAll('[aria-live]')]
    expect(live).toHaveLength(1)
    expect(live[0].dataset.slot).toBe('or-bar-live')
    expect(live[0].getAttribute('aria-live')).toBe('polite')
    expect(slot('or-bar-live').textContent).toBe('')

    or().setItemState.mockImplementation(() => new Promise(() => {}))
    fireEvent.click(check(KEY.dash))
    expect(slot('or-bottom-bar').textContent).toContain(tr('or.bar.progress', 4, 5))
    expect(slot('or-bar-live').textContent).toBe('')
    fireEvent.click(check(KEY.errors))
    expect(slot('or-bottom-bar').textContent).toContain(tr('or.bar.progress', 5, 5))
    await waitFor(() => expect(slot('or-bar-live').textContent).toBe(TR['or.bar.allResolved']))
  })
})

describe('DayView #16 — gün yazımı (sorun ekleme, günün notu)', () => {
  it.each([
    ['FUTURE', '2026-11-27'],
    ['TOO_OLD', '2026-11-10'],
    ['PAUSED', null],
  ])('kilitli gün (%s): sorun eklenemez (canCreate=false; çöz / yeniden aç canWork), günün notu salt okunur', async (reason, date) => {
    or().day.mockImplementation(() => Promise.resolve(ok(buildDaySheet({
      date: date || TODAY, locked_reason: reason, editable: false,
    }))))
    renderDay({ date })
    await loaded()
    expect(slot('or-locked').dataset.reason).toBe(reason)
    const stub = slot('stub-problems')
    expect(stub.dataset.canCreate).toBe('false')
    expect(stub.dataset.canWork).toBe('true')
    const note = slot('or-day-note').querySelector('textarea')
    expect(note.readOnly).toBe(true)
    expect(screen.getByRole('textbox', { name: TR['or.dayNote.label'] })).toHaveAttribute('readonly')
  })

  it.each([
    // [görüntülenen gün, gün kilidi, devir dönemi from..to, yazılabilir mi]
    ['2026-11-10', 'TOO_OLD', '2026-11-09', '2026-11-15', false],   // iki dönem geride
    ['2026-11-17', 'TOO_OLD', '2026-11-16', '2026-11-22', true],    // hemen önceki dönem: gün kilitli, devir notu değil
    ['2026-11-27', 'FUTURE', '2026-11-23', '2026-11-29', true],     // yarın, geçerli dönem
    ['2026-11-30', 'FUTURE', '2026-11-30', '2026-12-06', false],    // gelecek dönem
  ])('devir notu dönem kuralıyla (%s, %s): yazılabilir = %s', async (date, reason, from, to, writable) => {
    or().day.mockImplementation(() => Promise.resolve(ok(buildDaySheet({
      date, locked_reason: reason, editable: false,
      handover_current: { period_key: from, from, to, note: '', note_version: 0, note_by_name: null, note_at: null },
    }))))
    renderDay({ date })
    await loaded()
    const note = slot('or-handover-note').querySelector('textarea')
    expect(note.readOnly).toBe(!writable)
  })

  it('açık gün: sorun eklenebilir, günün notu yazılabilir', async () => {
    renderDay()
    await loaded()
    expect(slot('stub-problems').dataset.canCreate).toBe('true')
    expect(slot('stub-problems').dataset.canWork).toBe('true')
    expect(slot('or-day-note').querySelector('textarea').readOnly).toBe(false)
  })

  it('tamamlanmış gün kilit sayılmaz: sorun eklenebilir, günün notu yazılabilir', async () => {
    or().day.mockImplementation(() => Promise.resolve(ok(buildCompletedSheet())))
    renderDay()
    await loaded()
    expect(slot('stub-problems').dataset.canCreate).toBe('true')
    expect(slot('or-day-note').querySelector('textarea').readOnly).toBe(false)
  })

  it('iş günü olmayan gün: sorun eklenemez (yazılabilir olsa da), günün notu çizilmez', async () => {
    or().day.mockImplementation(() => Promise.resolve(ok(buildDaySheet({
      workday: false, holiday_name: 'Cumhuriyet Bayramı', non_workday_reason: 'HOLIDAY', locked_reason: 'NOT_WORKDAY',
      groups: [], previous_workday: null, day: { status: 'NOT_WORKDAY' },
    }))))
    renderDay()
    await loaded()
    expect(slot('stub-problems').dataset.canCreate).toBe('false')
    expect(slot('or-day-note')).toBeNull()
  })

  it('çalışma izni yoksa sorun eklenemez ve günün notu salt okunur', async () => {
    or().day.mockImplementation(() => Promise.resolve(ok(buildDaySheet({ can_work: false, editable: false }))))
    renderDay()
    await loaded()
    expect(slot('stub-problems').dataset.canCreate).toBe('false')
    expect(slot('stub-problems').dataset.canWork).toBe('false')
    expect(slot('or-day-note').querySelector('textarea').readOnly).toBe(true)
  })
})

describe('DayView #18 — geçmiş gün ve "Günün geçmişi"', () => {
  it('geçmiş günde canlı sayaç çipleri çizilmez, /signals istenmez, yoklama kurulmaz; bugünün sayfasında çizilir', async () => {
    or().day.mockImplementation((_t, d) => Promise.resolve(ok(buildDaySheet(d ? { date: d } : {}))))
    const onTodayProgress = vi.fn()
    const first = renderDay({ date: YESTERDAY, onTodayProgress })
    await loaded()
    expect(slot('or-day').dataset.date).toBe(YESTERDAY)
    await act(async () => { await elapse() })
    expect(signalChips()).toHaveLength(0)
    expect(or().signals).not.toHaveBeenCalled()
    expect(polls.fns.has(SIGNALS_POLL_MS)).toBe(false)
    expect(polls.fns.has(DAY_POLL_MS)).toBe(false)
    expect(onTodayProgress).not.toHaveBeenCalled()
    first.unmount()

    renderDay()
    await loaded()
    await waitFor(() => expect(or().signals).toHaveBeenCalledWith(TEAM_ID, { fresh: false }))
    await waitFor(() => expect(signalChips().length).toBeGreaterThan(0))
    expect(polls.fns.has(SIGNALS_POLL_MS)).toBe(true)
    expect(polls.fns.has(DAY_POLL_MS)).toBe(true)
  })

  it('gün değişince "Günün geçmişi" paneli kapanır; yeni günde açılınca o günün olayları istenir, eski olaylar görünmez', async () => {
    or().day.mockImplementation((_t, d) => Promise.resolve(ok(buildDaySheet(d ? { date: d } : {}))))
    or().events.mockResolvedValueOnce(ok({
      items: [{ id: 71, kind: 'ITEM_DONE', at: '2026-11-26T07:00:00Z', actor_name: OTHER.name, task_id: 't-dash', task_title: 'Alarm panosu' }],
      has_more: false,
    }))
    const { rerender, props } = renderDay()
    await loaded()
    fireEvent.click(slot('or-day-history'))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="or-event"]')).toHaveLength(1))
    expect(or().events).toHaveBeenLastCalledWith(TEAM_ID, { date: TODAY, before: undefined, limit: 100 })

    rerender(dayEl({ ...props, date: YESTERDAY }))
    await waitFor(() => expect(slot('or-day')?.dataset.date).toBe(YESTERDAY))
    await act(async () => { await elapse() })
    expect(slot('or-events')).toBeNull()
    expect(document.querySelectorAll('[data-slot="or-event"]')).toHaveLength(0)
    expect(or().events).toHaveBeenCalledTimes(1)

    const evD = deferred()
    or().events.mockReturnValueOnce(evD.promise)
    fireEvent.click(slot('or-day-history'))
    await waitFor(() => expect(or().events).toHaveBeenCalledTimes(2))
    expect(or().events).toHaveBeenLastCalledWith(TEAM_ID, { date: YESTERDAY, before: undefined, limit: 100 })
    await waitFor(() => expect(slot('or-events')).not.toBeNull())
    expect(document.querySelectorAll('[data-slot="or-event"]')).toHaveLength(0)
    await act(async () => { evD.resolve(ok({ items: [], has_more: false })) })
    await waitFor(() => expect(screen.getByText(TR['or.ev.empty'])).toBeTruthy())
  })
})
