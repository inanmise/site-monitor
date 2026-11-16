import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

// Tüm site kaynak analizi (2026-11-15): başlatma menüsünde "Tüm siteyi tara" (yalnız sunucu sınırı gönderiyorsa), onay
// penceresi → gövde { scope:'site', crawl:'full', max_pages }; eski sunucu crawl'u bilmezse not; Sayfalar görünümünde tarama
// özeti, taranmayan sayfalar (adıyla, nedeniyle, sayfalı), "Kaldığı yerden sürdür" → resume_from; 1000+ sayfa sayfalı.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      resourceScans: vi.fn(), resourceScan: vi.fn(), startResourceScan: vi.fn(), cancelResourceScan: vi.fn(),
      resourceBudget: vi.fn(), saveResourceBudget: vi.fn(), resourceScanTrend: vi.fn(),
    },
  }),
}))
const exportMock = vi.hoisted(() => ({ exportResourceScanXlsx: vi.fn(), exportResourceScanPdf: vi.fn(() => Promise.resolve()) }))
vi.mock('../components/page/resourcescan/resourceScanExport.js', () => exportMock)

import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import ResourceScanTab from '../components/page/resourcescan/ResourceScanTab.jsx'
import { resetWatchers } from '../components/page/resourcescan/resourceScanWatcher.js'
import { scanSummary } from './helpers/resourceScanFixtures.js'
import { budgetData, fullSiteDetail as fullDetail, fullSiteHistory as history, siteDetail, trendData } from './helpers/resourceScanFgFixtures.js'
import {
  canResumeCrawl, canResumeInterrupted, fullSiteLimits, siteModel, siteRowText,
} from '../components/page/resourcescan/resourceScanReportModel.js'
import { pollCapMs } from '../components/page/resourcescan/resourceScanModel.js'
import { TR as tr } from '../i18n/tr.js'

const MONITOR = { id: 100, name: 'Kurumsal Ana Sayfa', url: 'https://www.example.com/', can_edit: true }
const ok = (data, extra = {}) => ({ success: true, data, ...extra })
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]
const openTab = (name) => fireEvent.mouseDown(screen.getByRole('tab', { name }), { button: 0 })
const FULL_LIMITS = { full_site_max_pages: 2500, full_site_max_seconds: 9000 }
const t = (k, ...a) => String(tr[k] ?? k).replace(/\{(\d+)\}/g, (_, i) => String(a[Number(i)]))

beforeEach(() => {
  vi.clearAllMocks()
  resetWatchers()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  window.history.replaceState(null, '', '/?tab=page&monitor=100&mtab=resources')
  api.monitoring.resourceScans.mockResolvedValue(ok(history()))
  api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve(scanId === 31 ? ok(fullDetail(31)) : ok(siteDetail(scanId))))
  api.monitoring.resourceBudget.mockResolvedValue(ok(budgetData({})))
  api.monitoring.resourceScanTrend.mockResolvedValue(ok(trendData()))
})
afterEach(() => { resetWatchers() })

async function mount() {
  render(<ResourceScanTab monitor={MONITOR} pollMs={15} />)
  await waitFor(() => expect(slot('prs-hero')).not.toBeNull())
}

describe('tüm site taraması — başlatma', () => {
  it('menüde "Tüm siteyi tara" → pencere → gövde { scope: site, crawl: full, max_pages: 2500 }', async () => {
    const run = scanSummary(60, { status: 'RUNNING', scope: 'site', crawl: 'full', pages_count: null,
      progress: { done: 0, total: 0, pages_done: 1, pages_total: 20 }, grade: null, finished_at: null })
    api.monitoring.startResourceScan.mockResolvedValue(ok(run))
    await mount()
    pressMenuTrigger(slot('prs-start-more'))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Tüm siteyi tara/ }))
    const dlg = await waitFor(() => { const d = slot('prs-full-dialog'); expect(d).not.toBeNull(); return d })
    expect(dlg.textContent).toMatch(/En çok 2\.500 sayfa/)
    expect(dlg.textContent).toMatch(/kaldığı yerden sürdürülebilir/)
    fireEvent.click(slot('prs-full-confirm'))
    await waitFor(() => expect(api.monitoring.startResourceScan).toHaveBeenCalledWith(100, { scope: 'site', crawl: 'full', max_pages: 2500 }))
    await waitFor(() => expect(slot('prs-full-dialog')).toBeNull())
  })

  it('sunucu tüm site sınırı göndermiyorsa (eski sürüm) seçenek yok', async () => {
    api.monitoring.resourceScans.mockResolvedValue(ok(history({})))
    await mount()
    pressMenuTrigger(slot('prs-start-more'))
    await screen.findByRole('menuitem', { name: /Siteyi analiz et/ })
    expect(slot('prs-start-full')).toBeNull()
  })

  it('eski sunucu crawl alanını bilmezse (yanıtta crawl yok) bilgi notu', async () => {
    api.monitoring.startResourceScan.mockResolvedValue(ok(scanSummary(61, { status: 'RUNNING', scope: 'site', progress: { done: 0, total: 0 }, grade: null })))
    await mount()
    pressMenuTrigger(slot('prs-start-more'))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Tüm siteyi tara/ }))
    fireEvent.click(await waitFor(() => { const b = slot('prs-full-confirm'); expect(b).not.toBeNull(); return b }))
    await screen.findByText(/tüm site taramasını bilmiyor/)
  })
})

describe('tüm site taraması — Sayfalar görünümü', () => {
  it('özet: taranmadı uyarısı + neden, kutular, site haritası, devralma; taranmayan sayfalar sayfalı; sürdür → resume_from', async () => {
    const run = scanSummary(62, { status: 'RUNNING', scope: 'site', crawl: 'full', progress: { done: 0, total: 0 }, grade: null, finished_at: null })
    api.monitoring.startResourceScan.mockResolvedValue(ok(run))
    await mount()
    openTab(/^Sayfalar/)
    await waitFor(() => expect(slot('prs-crawl')).not.toBeNull())
    const crawl = slot('prs-crawl')
    expect(crawl.getAttribute('data-complete')).toBe('false')
    expect(slot('prs-crawl-incomplete').textContent).toMatch(/34 sayfa taranmadı — sayfa sınırına ulaşıldı/)
    const tile = (k) => crawl.querySelector(`[data-slot="prs-site-tile"][data-key="${k}"]`)
    expect(tile('discovered').textContent).toMatch(/74/)
    expect(tile('sitemap').textContent).toMatch(/22/)
    expect(tile('prior').textContent).toMatch(/2/)
    expect(slot('prs-crawl-sitemap').textContent).toMatch(/2 dosya, 60 adres, 22 yeni sayfa/)
    expect(slot('prs-crawl-sitemap').textContent).toMatch(/robots\.txt/)
    expect(slot('prs-crawl-resumed').textContent).toMatch(/29 numaralı/)
    expect(slot('prs-crawl-sitemap-errors').textContent).toMatch(/sitemap-eski\.xml/)

    // sayfalar 25'lik sayfalarda (40 sayfa), önceki taramadan gelen satır işaretli
    expect(slots('prs-site-page')).toHaveLength(25)
    expect(slots('prs-site-prior').length).toBeGreaterThan(0)

    // taranmayan sayfalar: 30 listelendi → 25 + sayfa; kaynak ve neden metin (ham kod yok); toplam fazlası söylenir
    const rows = slots('prs-site-unscanned-row')
    expect(rows).toHaveLength(25)
    expect(rows[2].textContent).toMatch(/bekleyen-2/)
    expect(rows[0].textContent).toMatch(/Bağlantı/)
    expect(rows[1].textContent).toMatch(/Site haritası/)
    expect(rows[0].textContent).toMatch(/sayfa sınırına ulaşıldı/)
    expect(document.body.textContent).not.toMatch(/\bPAGES\b/)
    expect(slot('prs-site-unscanned-more').textContent).toMatch(/\+4 sayfa daha/)

    fireEvent.click(slot('prs-crawl-resume'))
    await waitFor(() => expect(api.monitoring.startResourceScan).toHaveBeenCalledWith(100,
      { scope: 'site', crawl: 'full', max_pages: 2500, resume_from: 31 }))
  })

  it('sunucu denetim noktasını tutmuyorsa (resumable false — budandı ya da zaten sürdürüldü) sürdür düğmesi yok', async () => {
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => {
      if (scanId !== 31) return Promise.resolve(ok(siteDetail(scanId)))
      const d = fullDetail(31)
      return Promise.resolve(ok({ ...d, scan: { ...d.scan, resumable: false } }))
    })
    await mount()
    openTab(/^Sayfalar/)
    await waitFor(() => expect(slot('prs-crawl')).not.toBeNull())
    expect(slot('prs-crawl-incomplete')).not.toBeNull()
    expect(slots('prs-site-unscanned-row').length).toBeGreaterThan(0)
    expect(slot('prs-crawl-resume')).toBeNull()
  })

  it('robots.txt okunamadı: "harita yok" denmez, nedeni söylenir; yönlendirmeyle tekrar analiz edilmeyenler sayılır', async () => {
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve(scanId === 31
      ? ok(fullDetail(31, { crawl: { mode: 'full', complete: false, stop_reason: null, discovery_incomplete: true, discovered: 40,
        from_links: 39, from_sitemap: 0, prior_pages: 0, redirect_aliases: 2,
        sitemap: { found: false, robots_failed: true, errors: ['https://www.example.com/robots.txt'] } },
        unscanned: [], unscanned_total: 0 }))
      : ok(siteDetail(scanId))))
    await mount()
    openTab(/^Sayfalar/)
    await waitFor(() => expect(slot('prs-crawl')).not.toBeNull())
    expect(slot('prs-crawl-complete')).toBeNull()
    expect(slot('prs-crawl-incomplete').getAttribute('data-kind')).toBe('discovery')
    expect(slot('prs-crawl-robots-failed').textContent).toMatch(/robots\.txt okunamadı/)
    expect(slot('prs-crawl-sitemap-none')).toBeNull()
    expect(slot('prs-crawl-sitemap-errors').textContent).toMatch(/robots\.txt/)
    expect(slot('prs-crawl-redirect-aliases').textContent).toMatch(/2 bağlantı yönlendirmeyle/)
  })

  it('kuyruk bittiyse: "bütün sayfalar tarandı", sürdür düğmesi ve taranmayan bölümü yok', async () => {
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve(scanId === 31
      ? ok(fullDetail(31, { crawl: { mode: 'full', complete: true, stop_reason: null, discovered: 40, from_links: 39, from_sitemap: 0,
        prior_pages: 0, sitemap: { found: false, errors: [] } }, unscanned: [], unscanned_total: 0, incomplete: false }))
      : ok(siteDetail(scanId))))
    await mount()
    openTab(/^Sayfalar/)
    await waitFor(() => expect(slot('prs-crawl')).not.toBeNull())
    expect(slot('prs-crawl-complete').textContent).toMatch(/Bulunan bütün sayfalar tarandı/)
    expect(slot('prs-crawl-resume')).toBeNull()
    expect(slot('prs-site-unscanned')).toBeNull()
    expect(slot('prs-crawl-sitemap-none')).not.toBeNull()
  })

  it('kuyruk boş ama adres tavanı doldu: "en az N adres" uyarısı, "bütün sayfalar tarandı" ve sürdür yok', async () => {
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve(scanId === 31
      ? ok(fullDetail(31, { crawl: { mode: 'full', complete: false, stop_reason: 'KNOWN_CAP', discovered: 20_120, from_links: 20_000,
        from_sitemap: 0, dropped: 120, dropped_at_least: true, prior_pages: 0, sitemap: { found: false, errors: [] } },
        unscanned: [], unscanned_total: 0 }))
      : ok(siteDetail(scanId))))
    await mount()
    openTab(/^Sayfalar/)
    await waitFor(() => expect(slot('prs-crawl')).not.toBeNull())
    expect(slot('prs-crawl-complete')).toBeNull()
    const inc = slot('prs-crawl-incomplete')
    expect(inc.getAttribute('data-kind')).toBe('known_cap')
    expect(inc.textContent).toMatch(/en az 120 adres daha bulundu/)
    expect(inc.textContent).not.toMatch(/0 sayfa taranmadı/)
    expect(slot('prs-crawl').textContent).toMatch(/En az 120 adres daha bulundu ama/)
    expect(slot('prs-crawl-resume')).toBeNull()
  })

  it('kuyruk bitti ama keşif eksik (okunamayan harita): keşif uyarısı; başka sunucudaki harita satırı', async () => {
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve(scanId === 31
      ? ok(fullDetail(31, { crawl: { mode: 'full', complete: false, stop_reason: null, discovery_incomplete: true, discovered: 40,
        from_links: 39, from_sitemap: 0, prior_pages: 0,
        sitemap: { found: false, other_host: 2, errors: ['https://www.example.com/sm.xml'] } }, unscanned: [], unscanned_total: 0 }))
      : ok(siteDetail(scanId))))
    await mount()
    openTab(/^Sayfalar/)
    await waitFor(() => expect(slot('prs-crawl')).not.toBeNull())
    expect(slot('prs-crawl-complete')).toBeNull()
    expect(slot('prs-crawl-incomplete').getAttribute('data-kind')).toBe('discovery')
    expect(slot('prs-crawl-incomplete').textContent).toMatch(/sayfa keşfi eksik kaldı/)
    expect(slot('prs-crawl-sitemap-other-host').textContent).toMatch(/Başka bir sunucuda duran 2 site haritası/)
  })

  it('yarıda kalan tüm site taraması: "Kaldığı yerden sürdür" → resume_from (devralınan satırın tekil boyutu gösterilmez)', async () => {
    const h = history()
    const row = scanSummary(40, { status: 'INTERRUPTED', error: 'Pod durdu', scope: 'site', crawl: 'full', resumable: true, grade: null,
      progress: { done: 10, total: 20, pages_done: 12, pages_total: 300 } })
    api.monitoring.resourceScans.mockResolvedValue(ok({ ...h, items: [row, ...h.items] }))
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve(scanId === 40 ? ok({ scan: row })
      : scanId === 31 ? ok(fullDetail(31)) : ok(siteDetail(scanId))))
    api.monitoring.startResourceScan.mockResolvedValue(ok(scanSummary(63, { status: 'RUNNING', scope: 'site', crawl: 'full',
      progress: { done: 0, total: 0 }, grade: null, finished_at: null })))
    window.history.replaceState(null, '', '/?tab=page&monitor=100&mtab=resources&prs=40')
    render(<ResourceScanTab monitor={MONITOR} pollMs={15} />)
    const btn = await waitFor(() => { const b = slot('prs-interrupted-resume'); expect(b).not.toBeNull(); return b })
    expect(slot('prs-interrupted-resume-hint').textContent).toMatch(/kaydedilen son noktadan sürdürülebilir/)
    fireEvent.click(btn)
    await waitFor(() => expect(api.monitoring.startResourceScan).toHaveBeenCalledWith(100,
      { scope: 'site', crawl: 'full', max_pages: 2500, resume_from: 40 }))
  })

  it('geçmiş satırı "Tüm site · 40 / 74 sayfa analiz edildi"', async () => {
    await mount()
    openTab(/^Geçmiş/)
    const row = await waitFor(() => { const r = document.querySelector('[data-slot="prs-history-row"][data-id="31"]'); expect(r).not.toBeNull(); return r })
    expect(within(row).getByText(/Tüm site · 40 \/ 74 sayfa analiz edildi/)).toBeTruthy()
  })
})

describe('tüm site taraması — model', () => {
  it('fullSiteLimits: alan yoksa desteklenmez; tavan sunucununki (2500)', () => {
    expect(fullSiteLimits({}).supported).toBe(false)
    expect(fullSiteLimits({ full_site_max_pages: 5000, full_site_max_seconds: 1200 })).toEqual({ supported: true, maxPages: 2500, maxSeconds: 1200 })
    expect(fullSiteLimits({ full_site_max_pages: 300 }).maxSeconds).toBe(9000)
    // 2026-11-16 regresyonu: sunucunun gerçek yanıtı (2500 / 9000) istemcide 1000'e kırpılıyordu.
    expect(fullSiteLimits({ full_site_max_pages: 2500, full_site_max_seconds: 9000 })).toEqual({ supported: true, maxPages: 2500, maxSeconds: 9000 })
    expect(fullSiteLimits({ full_site_max_pages: 1000 }).maxPages).toBe(1000)
  })

  it('canResumeCrawl: yalnız bitmiş + kuyruğu bitmemiş tüm site taraması', () => {
    const m = siteModel(fullDetail(31).analysis.site)
    expect(canResumeCrawl({ status: 'DONE' }, m)).toBe(true)
    expect(canResumeCrawl({ status: 'RUNNING' }, m)).toBe(false)
    expect(canResumeCrawl({ status: 'DONE' }, siteModel(siteDetail(30).analysis.site))).toBe(false)
    const doneModel = siteModel(fullDetail(31, { crawl: { mode: 'full', complete: true }, unscanned: [], unscanned_total: 0 }).analysis.site)
    expect(canResumeCrawl({ status: 'DONE' }, doneModel)).toBe(false)
    const unknown = siteModel(fullDetail(31, { crawl: { mode: 'full' } }).analysis.site)
    expect(canResumeCrawl({ status: 'DONE' }, unknown)).toBe(false)
    // İnceleme 2026-11-17: sunucu denetim noktasını tutmuyor (budandı / zaten sürdürüldü) → düğme yok (409'a gitmez)
    expect(canResumeCrawl({ status: 'DONE', resumable: false }, m)).toBe(false)
    expect(canResumeCrawl({ status: 'DONE', resumable: true }, m)).toBe(true)
  })

  it('canResumeInterrupted: yarıda kalan / başarısız + denetim noktası olan tüm site taraması', () => {
    expect(canResumeInterrupted({ status: 'INTERRUPTED', crawl: 'full', resumable: true })).toBe(true)
    expect(canResumeInterrupted({ status: 'FAILED', crawl: 'full', resumable: true })).toBe(true)
    expect(canResumeInterrupted({ status: 'INTERRUPTED', crawl: 'full', resumable: false })).toBe(false)
    expect(canResumeInterrupted({ status: 'INTERRUPTED', crawl: 'full' })).toBe(false)
    expect(canResumeInterrupted({ status: 'DONE', crawl: 'full', resumable: true })).toBe(false)
    expect(canResumeInterrupted({ status: 'INTERRUPTED', resumable: true })).toBe(false)
    const m = siteModel(fullDetail(31, { totals: { pages: 40, scanned: 40, failed: 0, skipped: 0, unique_scope: 'this_run' } }).analysis.site)
    expect(m.totals.uniqueThisRun).toBe(true)
  })

  it('siteRowText tüm site önekiyle; pollCapMs tüm site bütçesiyle (≥ 3600 sn)', () => {
    expect(siteRowText({ crawl: 'full', pages_count: 3 }, t, 'tr')).toMatch(/^Tüm site · 3 sayfa/)
    expect(siteRowText({ crawl: 'full' }, t, 'tr')).toBe('Tüm site')
    expect(siteRowText({ pages_count: 3 }, t, 'tr')).toMatch(/^Site · 3 sayfa/)
    expect(pollCapMs(FULL_LIMITS, { scope: 'site', crawl: 'full' })).toBeGreaterThanOrEqual(3600 * 1000)
    expect(pollCapMs({}, { scope: 'site', crawl: 'full' })).toBeGreaterThanOrEqual(3600 * 1000)
    expect(pollCapMs({ site_max_seconds: 900 }, { scope: 'site' })).toBeLessThan(3600 * 1000)
  })
})
