import { describe, it, expect, vi, beforeEach, afterAll } from 'vitest'
import { useState } from 'react'
import { render, fireEvent, waitFor, within } from './test-utils.jsx'

// Nöbet Rutinleri — İstatistik görünümü (2026-11-26): KPI kutucukları (bilinmeyen yüzde "—", asla yeşil), görev / kişi
// tabloları (geniş kap tablo, dar kap kart), KPI kutucuğu görev süzgeci, sıralama, okunamayan istatistik → tehlike bloğu,
// Excel dışa aktarımı (tembel modül; 400 EXPORT_TOO_LARGE aralık alanının altında, 429 bekleme ipucuyla tostta, indirme
// başlamadıysa hata tostu, başarıda dosya adı).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', async () => {
  const { localDayKey } = await import('../utils/localDay.js')
  return {
    localDayKey,
    formatDate: (s) => s ?? '',
    api: withApiFallback({ oncallRoutines: { stats: vi.fn() } }),
  }
})
const widthBox = vi.hoisted(() => ({ w: 0 }))
vi.mock('../hooks/useElementWidth.js', async (orig) => ({
  ...(await orig()),
  useElementWidth: () => [() => {}, widthBox.w],
}))
// Dışa aktarım modülü (tembel `import('./routineExport.js')`) — sonuç eşlemesi burada sınanır; modülün kendisi
// `routineExport.test.js`'te
const exportRoutineXlsx = vi.hoisted(() => vi.fn())
vi.mock('../components/oncallroutine/routineExport.js', () => ({ exportRoutineXlsx }))

// Sabit "bugün": İstanbul 2026-11-26 — yalnız `Date` sahte
vi.hoisted(() => {
  vi.useFakeTimers({ toFake: ['Date'], shouldAdvanceTime: true })
  vi.setSystemTime(new Date('2026-11-26T09:00:00Z'))
})
afterAll(() => vi.useRealTimers())

import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { TR } from '../i18n/tr.js'
import RoutineStats, {
  LIST_PAGE_MIN, exportOutcome, filterTasks, pctText, sortTasks, stampText,
} from '../components/oncallroutine/RoutineStats.jsx'

const stats = () => api.oncallRoutines.stats

const LONG_NAME = 'Ayşe Yılmaz (Dijital Kanallar Servis Yönetimi / Kıdemli Uzman)'
const TASKS = [
  { task_id: 't-1', title: 'Gece yığın işlerinin sonuçlarını Grafana panosunda denetle ve hatalı işleri kayda geçir',
    section_title: 'Sabah kontrolleri', frequency: 'DAILY', occurrences: 21, done: 21, skipped: 0, missed: 0, pending: 0,
    done_pct: 100, problems: 0, last_done_at: '2026-11-26T06:12:00Z', last_done_by_name: LONG_NAME, median_done_time: '09:15' },
  { task_id: 't-2', title: 'Sertifika süre uyarılarını kontrol et', section_title: 'Sabah kontrolleri', frequency: 'WEEKLY',
    occurrences: 4, done: 2, skipped: 1, missed: 1, pending: 0, done_pct: 50, problems: 3,
    last_done_at: null, last_done_by_name: null, median_done_time: null },
  { task_id: 't-3', title: 'Aylık kapasite raporunu hazırla', section_title: 'Raporlar', frequency: 'MONTHLY',
    occurrences: 1, done: 0, skipped: 0, missed: 0, pending: 1, done_pct: null, problems: 1,
    last_done_at: null, last_done_by_name: null, median_done_time: null },
]
const STATS = {
  from: '2026-10-28', to: '2026-11-26', workdays: 21,
  days: { completed: 15, partial: 2, incomplete: 1, missed: 2, open: 1, none: 0 },
  on_time_pct: 93.68,
  tasks: TASKS,
  people: [
    { user_id: 'u-1', name: LONG_NAME, days_responsible: 11, days_completed: 10, days_completed_on_time: 9, items_done: 190, problems_opened: 3 },
    { user_id: 'u-2', name: 'Mehmet Demir', days_responsible: 10, days_completed: 7, days_completed_on_time: 7, items_done: 120, problems_opened: 2 },
  ],
  problems: { opened: 5, resolved: 3, open_now: 2, by_severity: { LOW: 1, MEDIUM: 2, HIGH: 2 }, median_resolve_hours: 6.55 },
  truncated: false,
}
const ok = (over = {}) => ({ success: true, data: { ...STATS, ...over } })

function Harness({ from: f0 = null, to: t0 = null }) {
  const [range, setRange] = useState({ from: f0, to: t0 })
  return <RoutineStats teamId={12} teamName="Dijital Kanallar SY" from={range.from} to={range.to} onRangeChange={setRange} />
}

const q = (sel) => document.querySelector(sel)
const kpi = (key) => q(`[data-slot="stat-item"][data-key="${key}"]`)
const kpiValue = (key) => kpi(key).querySelector('[data-slot="stat-value"]').textContent
const taskIds = () => [...document.querySelectorAll('[data-slot="or-stats-task"]')].map((x) => x.getAttribute('data-task'))
const bodyText = () => document.body.textContent

async function loaded() {
  await waitFor(() => expect(q('[data-slot="or-stats"]')?.getAttribute('data-state')).toBe('ok'))
}

beforeEach(() => {
  vi.clearAllMocks()
  widthBox.w = 0
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  stats().mockResolvedValue(ok())
  exportRoutineXlsx.mockResolvedValue({ ok: true, name: 'nobet-rutini-dijital-kanallar-sy-2026-10-28-2026-11-26.xlsx' })
})

describe('istatistik yardımcıları (saf)', () => {
  it('yüzde bir ondalığa AŞAĞI yuvarlanır; bilinmeyen "—"; TR önde %, EN sonda %', () => {
    expect(pctText(93.68, 'tr')).toBe('%93,6')
    expect(pctText(93.68, 'en')).toBe('93.6%')
    expect(pctText(99.99, 'tr')).toBe('%99,9')   // asla 100'e yuvarlanmaz
    expect(pctText(100, 'tr')).toBe('%100')
    expect(pctText(0, 'en')).toBe('0%')
    for (const v of [null, undefined, '', 'x', Number.NaN]) expect(pctText(v, 'tr')).toBe('—')
  })

  it('İstanbul damgası; okunamayan değer null', () => {
    expect(stampText('2026-11-26T06:12:00Z')).toBe('26.11.2026 09:12')
    expect(stampText('2026-11-26T06:12:00')).toBe('26.11.2026 09:12')   // dilimsiz = UTC
    expect(stampText('çöp')).toBeNull()
    expect(stampText(null)).toBeNull()
  })

  it('sıralama ve süzgeç: bilinmeyen yüzde sona; KPI süzgeçleri', () => {
    expect(sortTasks(TASKS, 'done_asc').map((x) => x.task_id)).toEqual(['t-2', 't-1', 't-3'])
    expect(sortTasks(TASKS, 'missed_desc')[0].task_id).toBe('t-2')
    expect(sortTasks(TASKS, 'problems_desc').map((x) => x.task_id)).toEqual(['t-2', 't-3', 't-1'])
    expect(sortTasks(TASKS, 'template')).toEqual(TASKS)
    expect(filterTasks(TASKS, 'missed').map((x) => x.task_id)).toEqual(['t-2'])
    expect(filterTasks(TASKS, 'partial').map((x) => x.task_id)).toEqual(['t-2'])
    expect(filterTasks(TASKS, 'problems').map((x) => x.task_id)).toEqual(['t-2', 't-3'])
    expect(filterTasks(TASKS, 'tanımsız')).toHaveLength(3)
  })

  it('dışa aktarım sonucu eşlemesi: ham kod gösterilmez, bekleme ipucu, aralık hatası, sessiz iptal', () => {
    const t = (k, ...a) => (a.length ? `${k}(${a.join(',')})` : k)
    expect(exportOutcome({ ok: true, name: 'a.xlsx' }, t)).toEqual({ kind: 'success', message: 'or.stats.exportDone(a.xlsx)' })
    for (const code of ['SESSION_ENDED', 'REQUEST_ABORTED', 'CANCELLED']) expect(exportOutcome({ ok: false, code }, t).kind).toBe('none')
    expect(exportOutcome({ ok: false, code: 'EXPORT_TOO_LARGE', res: { success: false, code: 'EXPORT_TOO_LARGE', error: 'Çok büyük aralık.' } }, t))
      .toEqual({ kind: 'range', message: 'Çok büyük aralık.' })
    expect(exportOutcome({ ok: false, code: 'EXPORT_TOO_LARGE', res: { success: false, code: 'EXPORT_TOO_LARGE' } }, t).message)
      .toBe('or.err.EXPORT_TOO_LARGE')
    const busy = exportOutcome({ ok: false, code: 'EXPORT_BUSY', res: { success: false, code: 'EXPORT_BUSY', retry_after_seconds: 7.2 } }, t)
    expect(busy).toEqual({ kind: 'error', message: 'or.err.EXPORT_BUSY or.stats.exportRetryIn(8)' })
    expect(exportOutcome({ ok: false, code: 'RATE_LIMITED', res: { success: false, code: 'RATE_LIMITED' } }, t).message)
      .toBe('or.err.RATE_LIMITED or.stats.exportRetryLater')
    expect(exportOutcome({ ok: false, code: 'DOWNLOAD_FAILED' }, t)).toEqual({ kind: 'error', message: 'or.stats.downloadBlocked' })
    expect(exportOutcome({ ok: false, code: 'DB_UNAVAILABLE', res: { success: false, code: 'XYZ_UNKNOWN' } }, t).message)
      .toBe('or.stats.exportNotDone')
    expect(exportOutcome(null, t)).toEqual({ kind: 'error', message: 'or.stats.exportNotDone' })
  })
})

describe('İstatistik görünümü', () => {
  it('istatistiği takım + aralıkla okur; KPI değerleri ve tonları', async () => {
    render(<Harness />)
    await loaded()
    const [teamId, opts] = stats().mock.calls[0]
    expect(teamId).toBe(12)
    expect(opts).toMatchObject({ from: '2025-11-27', to: '2026-11-26' })
    expect(opts.signal).toBeInstanceOf(AbortSignal)

    expect(kpiValue('all')).toBe('21')
    expect(kpi('all').textContent).toMatch(/17 tamamlandı/)        // tamamlanan + kısmi
    expect(kpiValue('ontime')).toBe('%93,6')
    expect(kpi('ontime').getAttribute('data-tone')).toBe('warning')   // 80 ≤ % < 95
    expect(kpiValue('partial')).toBe('2')
    expect(kpiValue('missed')).toBe('2')
    expect(kpi('missed').textContent).toMatch(/\+ 1 eksik gün/)
    expect(kpiValue('problems')).toBe('2')
    expect(kpi('problems').getAttribute('data-tone')).toBe('alert')
  })

  it('bilinmeyen yüzde "—" ve nötr ton (asla yeşil); bilinmeyen sayılar "—"', async () => {
    stats().mockResolvedValue(ok({ on_time_pct: null, workdays: null, problems: null,
      tasks: [{ ...TASKS[2], done_pct: null, skipped: null }] }))
    render(<Harness />)
    await loaded()
    expect(kpiValue('ontime')).toBe('—')
    expect(kpi('ontime').getAttribute('data-tone')).toBe('total')
    expect(kpiValue('all')).toBe('—')
    expect(kpiValue('problems')).toBe('—')
    const row = q('[data-slot="or-stats-task"][data-task="t-3"]')
    expect(row.textContent).toMatch(/—/)
    expect(row.querySelector('[data-slot="progress"]')).toBeNull()     // bilinmeyen yüzde çubuk çizmez
    expect(q('[data-slot="or-stats-problems"]').textContent).toMatch(/Sorun özeti bu yanıtta yok/)
  })

  it('geniş kap (ölçüm 0 = jsdom) → tablolar; görev ve kişi satırları, uzun adlar tam', async () => {
    render(<Harness />)
    await loaded()
    expect(q('[data-slot="or-stats-tasks"]').getAttribute('data-layout')).toBe('table')
    expect(q('[data-slot="or-stats-people"]').getAttribute('data-layout')).toBe('table')
    expect(q('[data-slot="or-stats-tasks"] table')).toBeTruthy()
    expect(taskIds()).toEqual(['t-1', 't-2', 't-3'])
    expect(document.querySelectorAll('[data-slot="or-stats-person"]')).toHaveLength(2)
    expect(q('[data-slot="or-stats-person"]').textContent).toContain(LONG_NAME)
    const t1 = q('[data-slot="or-stats-task"][data-task="t-1"]')
    expect(t1.textContent).toContain(LONG_NAME)
    expect(t1.textContent).toContain('26.11.2026 09:12')
    expect(t1.textContent).toContain('%100')
    // Hiç yapılmamış görev "Henüz yapılmadı"
    expect(q('[data-slot="or-stats-task"][data-task="t-2"]').textContent).toMatch(/Henüz yapılmadı/)
    // Sorun özeti: önem dereceleri ve medyan çözülme süresi (aşağı yuvarlanır)
    const probs = q('[data-slot="or-stats-problems"]')
    expect(probs.querySelector('[data-severity="HIGH"]').textContent).toMatch(/2/)
    expect(probs.textContent).toMatch(/6,5 sa/)
  })

  it('dar kap → kartlar (görev ve kişi)', async () => {
    widthBox.w = 400
    render(<Harness />)
    await loaded()
    expect(q('[data-slot="or-stats-tasks"]').getAttribute('data-layout')).toBe('cards')
    expect(q('[data-slot="or-stats-people"]').getAttribute('data-layout')).toBe('cards')
    expect(q('[data-slot="or-stats-tasks"] table')).toBeNull()
    expect(taskIds()).toEqual(['t-1', 't-2', 't-3'])
    expect(q('[data-slot="or-stats-person"]').textContent).toContain(LONG_NAME)
  })

  it('KPI kutucuğu görev süzgecidir; aynı kutucuk ya da "Süzgeci kaldır" temizler; "zamanında" kişi tablosuna götürür', async () => {
    render(<Harness />)
    await loaded()
    fireEvent.click(kpi('missed'))
    await waitFor(() => expect(taskIds()).toEqual(['t-2']))
    expect(kpi('missed').getAttribute('aria-pressed')).toBe('true')
    expect(q('[data-slot="or-stats-filter"]').textContent).toMatch(/kaçırılan görevler/)
    fireEvent.click(kpi('missed'))
    await waitFor(() => expect(taskIds()).toEqual(['t-1', 't-2', 't-3']))
    expect(q('[data-slot="or-stats-filter"]')).toBeNull()

    fireEvent.click(kpi('problems'))
    await waitFor(() => expect(taskIds()).toEqual(['t-2', 't-3']))
    fireEvent.click(q('[data-slot="or-stats-filter-clear"]'))
    await waitFor(() => expect(taskIds()).toHaveLength(3))

    // "Zamanında" süzgeç değil eylem: kişi bölümüne odaklanır, görev listesi değişmez
    expect(kpi('ontime').hasAttribute('aria-pressed')).toBe(false)
    fireEvent.click(kpi('ontime'))
    expect(document.activeElement?.contains(q('[data-slot="or-stats-people"]'))).toBe(true)
    expect(taskIds()).toHaveLength(3)
  })

  it('süzgece uyan görev yoksa nötr boş durum', async () => {
    stats().mockResolvedValue(ok({ tasks: [TASKS[0]] }))
    render(<Harness />)
    await loaded()
    fireEvent.click(kpi('missed'))
    await waitFor(() => expect(q('[data-slot="or-stats-tasks-empty"]')).toBeTruthy())
    expect(q('[data-slot="or-stats-tasks-empty"]').textContent).toMatch(/Bu süzgece uyan görev yok/)
  })

  it('sıralama seçici', async () => {
    render(<Harness />)
    await loaded()
    fireEvent.change(q('[data-slot="or-stats-sort"]'), { target: { value: 'done_asc' } })
    await waitFor(() => expect(taskIds()).toEqual(['t-2', 't-1', 't-3']))
  })

  it('ilk sayfa boyutundan fazla görev: standart sayfalama (panel, 25) — "tümünü göster" yok', async () => {
    const many = Array.from({ length: LIST_PAGE_MIN + 3 }, (_, i) => ({ ...TASKS[0], task_id: `t-${i}`, title: `Görev ${i}` }))
    stats().mockResolvedValue(ok({ tasks: many }))
    render(<Harness />)
    await loaded()
    expect(taskIds()).toHaveLength(LIST_PAGE_MIN)
    const tasks = q('[data-slot="or-stats-tasks"]')
    expect(within(tasks).queryByRole('button', { name: new RegExp(String(LIST_PAGE_MIN + 3)) })).toBeNull()
    const bar = tasks.querySelector('[data-slot="pagination-bar"]')
    expect(bar).not.toBeNull()
    fireEvent.click(within(bar).getByRole('button', { name: TR['pg.next'] }))
    await waitFor(() => expect(taskIds()).toHaveLength(3))
    expect(taskIds()[0]).toBe(`t-${LIST_PAGE_MIN}`)
  })

  it('ilk sayfa boyutu kadar görev: sayfalama çubuğu çizilmez', async () => {
    const few = Array.from({ length: LIST_PAGE_MIN }, (_, i) => ({ ...TASKS[0], task_id: `t-${i}`, title: `Görev ${i}` }))
    stats().mockResolvedValue(ok({ tasks: few }))
    render(<Harness />)
    await loaded()
    expect(taskIds()).toHaveLength(LIST_PAGE_MIN)
    expect(q('[data-slot="or-stats-tasks"] [data-slot="pagination-bar"]')).toBeNull()
  })

  it('kısaltılmış sonuç bilgi bandıyla söylenir', async () => {
    stats().mockResolvedValue(ok({ truncated: true }))
    render(<Harness />)
    await loaded()
    expect(bodyText()).toMatch(/kısaltıldı/)
  })

  it('okunamayan istatistik → tehlike bloğu (sıfır değil) + Tekrar dene', async () => {
    stats().mockResolvedValueOnce({ success: false, status: 503, code: 'DB_UNAVAILABLE', error: 'Veritabanına şu an ulaşılamıyor; birkaç saniye sonra yeniden deneyin.' })
    render(<Harness />)
    await waitFor(() => expect(q('[data-slot="or-stats-failed"]')).toBeTruthy())
    expect(q('[data-slot="or-stats-failed"]').textContent).toMatch(/Veritabanına şu an ulaşılamıyor/)
    expect(q('[data-slot="stat-item"]')).toBeNull()
    fireEvent.click(q('[data-slot="or-stats-retry"]'))
    await loaded()
    expect(kpiValue('all')).toBe('21')
  })

  it('geçersiz özel aralık: istek gitmez, dışa aktarım kapalı', async () => {
    render(<Harness from="2026-11-20" to="2026-11-10" />)
    await waitFor(() => expect(q('[data-slot="or-range-from"]')?.textContent).toMatch(/Başlangıç, bitişten sonra olamaz/))
    expect(stats()).not.toHaveBeenCalled()
    expect(q('[data-slot="or-export"]').disabled).toBe(true)
  })
})

describe('Excel dışa aktarımı', () => {
  it('başarı: modül takım + gerçek aralıkla çağrılır, dosya adı tostta', async () => {
    render(<Harness />)
    await loaded()
    fireEvent.click(q('[data-slot="or-export"]'))
    await waitFor(() => expect(bodyText()).toContain('nobet-rutini-dijital-kanallar-sy-2026-10-28-2026-11-26.xlsx indirildi.'))
    expect(exportRoutineXlsx).toHaveBeenCalledTimes(1)
    const arg = exportRoutineXlsx.mock.calls[0][0]
    // Sunucunun döndürdüğü aralık (data.from / data.to) esas alınır
    expect(arg).toMatchObject({ teamId: 12, teamName: 'Dijital Kanallar SY', from: '2026-10-28', to: '2026-11-26', lang: 'tr' })
    expect(typeof arg.t).toBe('function')
    expect(arg.signal).toBeInstanceOf(AbortSignal)
    await waitFor(() => expect(q('[data-slot="or-export"]').disabled).toBe(false))
  })

  it('sürerken düğme kilitli; ikinci tıklama ikinci istek açmaz', async () => {
    let finish
    exportRoutineXlsx.mockImplementationOnce(() => new Promise((r) => { finish = r }))
    render(<Harness />)
    await loaded()
    fireEvent.click(q('[data-slot="or-export"]'))
    await waitFor(() => expect(q('[data-slot="or-export"]').disabled).toBe(true))
    expect(q('[data-slot="or-export"]').getAttribute('aria-busy')).toBe('true')
    fireEvent.click(q('[data-slot="or-export"]'))
    expect(exportRoutineXlsx).toHaveBeenCalledTimes(1)
    finish({ ok: true, name: 'x.xlsx' })
    await waitFor(() => expect(q('[data-slot="or-export"]').disabled).toBe(false))
  })

  it('400 EXPORT_TOO_LARGE aralık alanının altında; aralık değişince kalkar', async () => {
    const msg = 'Bu aralıkta 20.000 satırdan fazla görev kaydı var — aralığı kısaltıp yeniden deneyin.'
    exportRoutineXlsx.mockResolvedValueOnce({ ok: false, code: 'EXPORT_TOO_LARGE',
      res: { success: false, status: 400, code: 'EXPORT_TOO_LARGE', field: 'from', error: msg } })
    render(<Harness />)
    await loaded()
    fireEvent.click(q('[data-slot="or-export"]'))
    await waitFor(() => expect(q('[data-slot="or-range-error"]')?.textContent).toBe(msg))
    expect(q('[data-slot="or-range-error"]').getAttribute('role')).toBe('alert')
    // 30 günlük hazır aralık → hata aralığa aitti, kalkar
    const presets = within(q('[data-slot="or-range-presets"]')).getAllByRole('button')
    fireEvent.click(presets[0])
    await waitFor(() => expect(q('[data-slot="or-range-error"]')).toBeNull())
  })

  it('429 EXPORT_BUSY: sunucu iletisi + bekleme süresi tostta', async () => {
    exportRoutineXlsx.mockResolvedValueOnce({ ok: false, code: 'EXPORT_BUSY',
      res: { success: false, status: 429, code: 'EXPORT_BUSY', retry_after_seconds: 12, error: 'Bir dışa aktarımınız zaten sürüyor.' } })
    render(<Harness />)
    await loaded()
    fireEvent.click(q('[data-slot="or-export"]'))
    await waitFor(() => expect(bodyText()).toContain('Bir dışa aktarımınız zaten sürüyor. 12 saniye sonra yeniden deneyin.'))
    expect(q('[data-slot="or-range-error"]')).toBeNull()
  })

  it('tarayıcı indirmeyi başlatamazsa hata tostu (başarı denmez)', async () => {
    exportRoutineXlsx.mockResolvedValueOnce({ ok: false, code: 'DOWNLOAD_FAILED', name: 'x.xlsx' })
    render(<Harness />)
    await loaded()
    fireEvent.click(q('[data-slot="or-export"]'))
    await waitFor(() => expect(bodyText()).toContain('Tarayıcı indirmeyi başlatamadı.'))
    expect(bodyText()).not.toContain('indirildi.')
  })

  it('beklenmeyen hata çevrilmiş yedek metinle söylenir (ham metin yalnız teknik ayrıntıda)', async () => {
    exportRoutineXlsx.mockRejectedValueOnce(new TypeError('xyzzy internal'))
    render(<Harness />)
    await loaded()
    fireEvent.click(q('[data-slot="or-export"]'))
    await waitFor(() => expect(bodyText()).toContain('Excel dosyası hazırlanamadı'))
    await waitFor(() => expect(q('[data-slot="or-export"]').disabled).toBe(false))
  })

  it('oturum bittiyse sessiz (tost yok)', async () => {
    exportRoutineXlsx.mockResolvedValueOnce({ ok: false, code: 'SESSION_ENDED', res: null })
    render(<Harness />)
    await loaded()
    fireEvent.click(q('[data-slot="or-export"]'))
    await waitFor(() => expect(q('[data-slot="or-export"]').disabled).toBe(false))
    expect(bodyText()).not.toMatch(/indirildi|hazırlanamadı|başlatamadı/)
  })
})
