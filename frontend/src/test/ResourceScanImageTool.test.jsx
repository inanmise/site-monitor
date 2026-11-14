import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

// Kaynak analizi sekmesi + görsel aracı (2026-11-14) — sekmenin KENDİSİYLE uçtan uca: yeni "Görsel aracı" bölümü ve
// paylaşılabilir adresi, analizsiz açılış, nesne satırında / ayrıntıda / panoda "İndir" menüsü, toplu indirme düğmesi,
// "not ne olurdu?" kartı, bu sekmede işlenenlerin dışa aktarıma gitmesi. Motor bağdaştırıcısı ve sunucu taklit.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    monitoring: { resourceScans: vi.fn(), resourceScan: vi.fn(), startResourceScan: vi.fn(), resourceScanImage: vi.fn(), resourceScanImageProjection: vi.fn() },
  }),
}))
const exportMock = vi.hoisted(() => ({ exportResourceScanXlsx: vi.fn(), exportResourceScanPdf: vi.fn(() => Promise.resolve()) }))
vi.mock('../components/page/resourcescan/resourceScanExport.js', () => exportMock)
const eng = vi.hoisted(() => ({
  FALLBACK_LIMITS: { maxInputBytes: 25 * 1024 * 1024, maxPixels: 50_000_000, maxSide: 30_000 },
  loadEngine: vi.fn(), engineLimits: vi.fn(), engineSupport: vi.fn(), inspectImage: vi.fn(), processImage: vi.fn(), decodeForPreview: vi.fn(),
  isEngineError: (e) => e?.name === 'EngineError' && typeof e?.code === 'string',
}))
vi.mock('../components/page/resourcescan/imagetool/engineClient.js', () => eng)

import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { flushUrlQuerySync } from '../hooks/useUrlQuerySync.js'
import ResourceScanTab from '../components/page/resourcescan/ResourceScanTab.jsx'
import { isImageWorkBusy, resetImageWorkForTests } from '../components/page/resourcescan/imagetool/imageToolBusy.js'
import { emitSessionReset } from '../utils/sessionResetBus.js'
import { scanDetail, scanHistory, scanItems } from './helpers/resourceScanFixtures.js'

const MONITOR = { id: 100, name: 'Kurumsal Ana Sayfa', url: 'https://www.example.com/' }
const ok = (data) => ({ success: true, data })
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]
const openTab = (name) => fireEvent.mouseDown(screen.getByRole('tab', { name }), { button: 0 })
const blobOf = (n, type = 'image/jpeg') => new Blob([new Uint8Array(n).fill(7)], { type })
const BIG = scanItems()[1]              // 1,6 MB, 3840×2160 üçüncü taraf görsel (LARGE_IMAGES)

let clickSpy
let downloaded
beforeEach(() => {
  vi.clearAllMocks()
  resetImageWorkForTests()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  window.history.replaceState(null, '', '/?tab=page&monitor=100&mtab=resources')
  api.monitoring.resourceScans.mockResolvedValue(ok(scanHistory()))
  api.monitoring.resourceScan.mockImplementation((_id, scanId) => {
    if (scanId === 10) return Promise.resolve(ok({ ...scanDetail(10, { scan: { grade: 64, grade_letter: 'D', items_kept: false } }), items: null, items_kept: false }))
    return Promise.resolve(ok(scanDetail(scanId)))
  })
  api.monitoring.resourceScanImage.mockImplementation(async () => ({ success: true, blob: blobOf(1600 * 1024), name: 'banner.webp', type: 'webp', width: 3840, height: 2160 }))
  eng.engineLimits.mockResolvedValue(eng.FALLBACK_LIMITS)
  eng.engineSupport.mockResolvedValue({ worker: true, webp: true })
  eng.inspectImage.mockImplementation(async (blob) => ({ type: 'webp', width: 3840, height: 2160, bytes: blob.size, hasAlpha: false, animated: false }))
  eng.processImage.mockImplementation(async (blob, options) => {
    const width = options.resize.method === 'scale' ? options.resize.width : 3840
    return {
      blob: blobOf(Math.floor(blob.size / 4), 'image/webp'), type: 'webp', mime: 'image/webp', width, height: Math.round((width * 9) / 16), bytes: Math.floor(blob.size / 4),
      original: { type: 'webp', width: 3840, height: 2160, bytes: blob.size, hasAlpha: false, animated: false, metadata: {} },
      savingPct: 75, quality: 78, colors: null, similarity: 0.99, resized: width !== 3840, cropped: false, kept: false, notes: [],
    }
  })
  downloaded = []
  URL.createObjectURL = vi.fn(() => 'blob:test')
  URL.revokeObjectURL = vi.fn()
  clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function click() { if (this.download) downloaded.push(this.download) })
})
afterEach(() => { clickSpy.mockRestore() })

async function mount(props = {}) {
  const view = render(<ResourceScanTab monitor={MONITOR} pollMs={15} {...props} />)
  await waitFor(() => expect(slot('prs-hero')).not.toBeNull())
  return view
}
const rowOf = (url) => slots('prs-obj').find((r) => r.textContent.includes(url.slice(0, 60)))
const menuOf = (root) => slot('prs-it-menu', root)
const pick = async (root, action) => {
  pressMenuTrigger(menuOf(root))
  const item = await waitFor(() => { const n = document.querySelector(`[data-slot="prs-it-menu-content"] [data-action="${action}"]`); expect(n).not.toBeNull(); return n })
  fireEvent.click(item)
}

describe('"Görsel aracı" bölümü', () => {
  it('"Geçmiş"in yanında yeni sekme; açılınca araç görünümü yüklenir ve adres paylaşılabilir olur (prs_v=tool)', async () => {
    await mount()
    const tabs = within(slot('prs-sections')).getAllByRole('tab').map((n) => n.textContent.replace(/\d+/g, '').trim())
    expect(tabs).toEqual(['Pano', 'Nesneler', 'Denetim', 'Eğilim', 'Geçmiş', 'Görsel aracı'])
    expect(slot('prs-sections').querySelector('[role="tablist"]').getAttribute('data-count')).toBe('6')
    openTab(/^Görsel aracı/)
    await waitFor(() => expect(slot('prs-it-view')).not.toBeNull())
    expect(slot('prs-root').getAttribute('data-view')).toBe('tool')
    await waitFor(() => { flushUrlQuerySync(); expect(window.location.search).toContain('prs_v=tool') }, { timeout: 5000 })
    expect(slot('prs-it-drop')).toHaveTextContent('Görselleri buraya sürükleyip bırakın')
    expect(slot('prs-it-back')).toBeNull()                    // sekmeler varken geri düğmesi gereksiz
    openTab(/^Pano/)
    await waitFor(() => expect(slot('prs-hero')).not.toBeNull())
    await waitFor(() => { flushUrlQuerySync(); expect(window.location.search).not.toContain('prs_v=tool') }, { timeout: 5000 })     // adres yazımı gecikmeli (debounce)
  })

  it('paylaşılan bağlantı (prs_v=tool) doğrudan aracı açar; eski analiz şeridi araçta çizilmez', async () => {
    window.history.replaceState(null, '', '/?tab=page&monitor=100&mtab=resources&prs=9&prs_v=tool')
    render(<ResourceScanTab monitor={MONITOR} pollMs={15} />)
    await waitFor(() => expect(slot('prs-it-view')).not.toBeNull())
    expect(screen.getByRole('tab', { name: /^Görsel aracı/ }).getAttribute('data-state')).toBe('active')
    expect(slot('prs-old-run')).toBeNull()
  })

  it('hiç analiz yokken de açılır (yükleme için analiz ve çalıştırma yetkisi gerekmez); "geri" boş duruma döner', async () => {
    api.monitoring.resourceScans.mockResolvedValue(ok({ items: [], running: null, can_run: false, limits: { max_seconds: 300 } }))
    render(<ResourceScanTab monitor={MONITOR} pollMs={15} />)
    await waitFor(() => expect(slot('prs-empty')).not.toBeNull())
    fireEvent.click(within(slot('prs-empty')).getByRole('button', { name: 'Görsel aracını aç' }))
    await waitFor(() => expect(slot('prs-it-view')).not.toBeNull())
    expect(slot('prs-empty')).toBeNull()
    await waitFor(() => { flushUrlQuerySync(); expect(window.location.search).toContain('prs_v=tool') }, { timeout: 5000 })
    fireEvent.change(slot('prs-it-file-input'), { target: { files: [new File([new Uint8Array(4000)], 'yuklenen.png', { type: 'image/png' })] } })
    await waitFor(() => expect(slot('prs-it-entry')?.getAttribute('data-state')).toBe('done'))
    expect(api.monitoring.resourceScanImage).not.toHaveBeenCalled()         // yüklenen dosya sunucuya gitmez
    fireEvent.click(slot('prs-it-back'))
    await waitFor(() => expect(slot('prs-empty')).not.toBeNull())
  })
})

describe('tarama görsellerinde "İndir" menüsü', () => {
  it('nesne listesinde yalnız görsel satırlarında; optimize edilince satırda gerçek sonuç, dosya iner; dışa aktarım ölçülen değerleri alır', async () => {
    await mount()
    openTab(/^Nesneler/)
    await waitFor(() => expect(slots('prs-obj').length).toBeGreaterThan(0))
    const imgRow = rowOf(BIG.url)
    expect(imgRow.getAttribute('data-category')).toBe('IMAGE')
    expect(menuOf(imgRow)).not.toBeNull()
    expect(slot('prs-it-suggest', imgRow)).toHaveTextContent('3.840 piksel geniş (2.000 pikselden fazla) → 1.920 piksel yeter')
    const jsRow = slots('prs-obj').find((r) => r.getAttribute('data-category') === 'JS')
    expect(menuOf(jsRow)).toBeNull()
    await pick(imgRow, 'resize')
    await waitFor(() => expect(slot('prs-it-result', rowOf(BIG.url))).not.toBeNull())
    expect(api.monitoring.resourceScanImage).toHaveBeenCalledTimes(1)
    expect(api.monitoring.resourceScanImage.mock.calls[0].slice(0, 3)).toEqual([100, 12, BIG.url])
    expect(eng.processImage.mock.calls[0][1].resize).toEqual({ method: 'scale', width: 1920 })
    expect(slot('prs-it-result', rowOf(BIG.url))).toHaveTextContent('1,56 MB → 400 KB (−%75)')
    expect(downloaded).toEqual(['banner-1920w.webp'])
    // dışa aktarım: bu sekmede işlenen görselin ölçülen boyutu / kazancı / ölçüsü / biçimi modüle gider
    fireEvent.click(slot('prs-export-xlsx'))
    await waitFor(() => expect(exportMock.exportResourceScanXlsx).toHaveBeenCalledTimes(1))
    expect(exportMock.exportResourceScanXlsx.mock.calls[0][0].processed).toEqual([{ url: BIG.url, cells: [409_600, '%75', '1920×1080', 'WebP'] }])
  })

  it('hiçbir görsel işlenmediyse dışa aktarıma boş liste gider (sütun eklenmez)', async () => {
    await mount()
    fireEvent.click(slot('prs-export-xlsx'))
    await waitFor(() => expect(exportMock.exportResourceScanXlsx).toHaveBeenCalledTimes(1))
    expect(exportMock.exportResourceScanXlsx.mock.calls[0][0].processed).toEqual([])
  })

  it('çalıştırma yetkisi olmayan görüntüleyicide menü nedenini söyler; sunucudan istek gitmez', async () => {
    api.monitoring.resourceScans.mockResolvedValue(ok(scanHistory({ canRun: false })))
    await mount()
    openTab(/^Nesneler/)
    await waitFor(() => expect(slots('prs-obj').length).toBeGreaterThan(0))
    const row = rowOf(BIG.url)
    pressMenuTrigger(menuOf(row))
    await waitFor(() => expect(slot('prs-it-unavailable')).not.toBeNull())
    expect(slot('prs-it-unavailable').getAttribute('data-reason')).toBe('no_permission')
    expect(api.monitoring.resourceScanImage).not.toHaveBeenCalled()
  })

  it('"Görsel aracında aç": görsel kuyruğa düşer ve araç bölümüne geçilir', async () => {
    await mount()
    openTab(/^Nesneler/)
    await waitFor(() => expect(slots('prs-obj').length).toBeGreaterThan(0))
    await pick(rowOf(BIG.url), 'tool')
    await waitFor(() => expect(slot('prs-it-view')).not.toBeNull())
    await waitFor(() => expect(slot('prs-it-entry')?.getAttribute('data-state')).toBe('done'))
    expect(slot('prs-it-entry').getAttribute('data-source')).toBe('scan')
    expect(slot('prs-it-entry-own')).toHaveTextContent('Kendi ayarı: 1.920 piksel genişliğe ölçekle')
    expect(screen.getByRole('tab', { name: /^Görsel aracı/ }).getAttribute('data-state')).toBe('active')
  })

  it('nesne ayrıntı penceresinde "Görsel" grubu: öneri + indirme menüsü', async () => {
    await mount()
    openTab(/^Nesneler/)
    await waitFor(() => expect(slots('prs-obj').length).toBeGreaterThan(0))
    fireEvent.click(slot('prs-obj-open', rowOf(BIG.url)))
    const group = await waitFor(() => { const g = slot('prs-od-image'); expect(g).not.toBeNull(); return g })
    expect(group).toHaveTextContent('Görsel')
    expect(menuOf(group)).not.toBeNull()
    expect(slot('prs-it-suggest', group)).not.toBeNull()
  })
})

describe('pano: görsel etkisi kartı, toplu indirme ve "not ne olurdu?"', () => {
  it('en büyük görsellerde indirme menüsü; kartta ve büyük görsel önerisinde "optimize edip indir (ZIP)"; not kartı çizilir', async () => {
    await mount()
    const card = slot('prs-images')
    expect(slots('prs-it-menu', slot('prs-img-top', card)).length).toBe(3)
    expect(slot('prs-it-bulk-open', card)).toHaveTextContent('Büyük görselleri optimize edip indir (ZIP)')
    expect(slots('prs-it-bulk-open').length).toBeGreaterThanOrEqual(2)       // kart + LARGE_IMAGES önerisi
    expect(slot('prs-it-projection')).not.toBeNull()
    api.monitoring.resourceScanImageProjection.mockResolvedValue(ok({ capped: false, applied: 2, ignored: 0,
      before: { grade: 72, letter: 'C', page_bytes: 3_565_158, image_bytes: 2_202_009, load_ms: 2450, large_images: 2, scores: { images: { score: 40, lost: 9 } } },
      after: { grade: 81, letter: 'B', page_bytes: 2_202_009, image_bytes: 838_860, load_ms: 2450, large_images: 0, scores: { images: { score: 90, lost: 1.5 } } } }))
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(slot('prs-it-proj-result')).not.toBeNull())
    expect(api.monitoring.resourceScanImageProjection).toHaveBeenCalledWith(100, 12, { use_measured: true })
    expect(slot('prs-it-proj-diff')).toHaveTextContent('+9 puan')
    // Sunucu bu analizi projekte edemezse (eski algoritma): nötr durum; "Yeniden analiz et" sekmenin başlatıcısını çağırır
    api.monitoring.resourceScanImageProjection.mockResolvedValueOnce({ success: false, status: 409, code: 'RESOURCE_SCAN_NOT_PROJECTABLE', reason: 'algorithm', error: 'x' })
    api.monitoring.startResourceScan.mockResolvedValueOnce({ success: false, status: 500, error: 'Analiz başlatılamadı, birkaç saniye sonra yeniden deneyin.' })
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(slot('prs-it-proj-na')).not.toBeNull())
    expect(slot('prs-it-proj-result')).toBeNull()
    fireEvent.click(slot('prs-it-proj-new-scan'))
    await waitFor(() => expect(api.monitoring.startResourceScan).toHaveBeenCalledWith(100))
  })

  it('toplu indirme: sekmenin üstündeki çubuk ilerlemeyi ve sonucu yazar; sürerken "süren iş" kaydı doludur (tarayıcı sekmesi kapatılırsa sorulur)', async () => {
    let release
    const hold = new Promise((r) => { release = r })
    api.monitoring.resourceScanImage.mockImplementation(async () => { await hold; return { success: true, blob: blobOf(1600 * 1024), name: 'banner.webp', type: 'webp', width: 3840, height: 2160 } })
    await mount()
    fireEvent.click(slot('prs-it-bulk-open', slot('prs-images')))
    await waitFor(() => expect(slot('prs-it-bulk-dialog')).not.toBeNull())
    expect(Number(slot('prs-it-bulk-dialog').getAttribute('data-count'))).toBeGreaterThanOrEqual(2)
    fireEvent.click(slot('prs-it-bulk-start'))
    await waitFor(() => expect(slot('prs-it-bulk-bar')?.getAttribute('data-state')).toBe('running'))
    expect(isImageWorkBusy()).toBe(true)
    openTab(/^Geçmiş/)                                                       // bölüm değişse de iş sürer, çubuk yerinde
    expect(slot('prs-it-bulk-bar')).not.toBeNull()
    release()
    await waitFor(() => expect(slot('prs-it-bulk-bar')?.getAttribute('data-state')).toBe('finished'))
    expect(slot('prs-it-bulk-bar').getAttribute('data-kind')).toBe('done')
    expect(isImageWorkBusy()).toBe(false)
    expect(downloaded).toHaveLength(1)
    expect(downloaded[0]).toMatch(/^kurumsal-ana-sayfa-optimize-gorseller-\d{8}-\d{4}\.zip$/)
  })

  it('nesne ayrıntısı saklanmayan eski analizde: menü ve not kartı nedenini söyler, toplu düğme penceresi "saklanmıyor" der', async () => {
    await mount()
    openTab(/^Geçmiş/)
    fireEvent.click(document.querySelector('[data-slot="prs-history-row"][data-id="10"]'))
    await waitFor(() => expect(slot('prs-items-not-kept') || slot('prs-it-proj-gone')).not.toBeNull())
    openTab(/^Pano/)
    await waitFor(() => expect(slot('prs-it-proj-gone')).not.toBeNull())
    const top = slot('prs-img-top')
    pressMenuTrigger(menuOf(top))
    await waitFor(() => expect(slot('prs-it-unavailable')).not.toBeNull())
    expect(slot('prs-it-unavailable').getAttribute('data-reason')).toBe('items_gone')
    expect(api.monitoring.resourceScanImage).not.toHaveBeenCalled()
  })

  it('sekme sökülünce (uygulama içi gezinme) toplu indirme SÜRER ve ZIP iner; bitince "süren iş" kaydı boşalır', async () => {
    let release
    const hold = new Promise((r) => { release = r })
    api.monitoring.resourceScanImage.mockImplementation(async () => { await hold; return { success: true, blob: blobOf(1600 * 1024), name: 'banner.webp', type: 'webp', width: 3840, height: 2160 } })
    const view = await mount()
    fireEvent.click(slot('prs-it-bulk-open', slot('prs-images')))
    await waitFor(() => expect(slot('prs-it-bulk-start')).not.toBeNull())
    fireEvent.click(slot('prs-it-bulk-start'))
    await waitFor(() => expect(isImageWorkBusy()).toBe(true))
    view.unmount()
    expect(isImageWorkBusy()).toBe(true)                                   // iş sessizce kesilmedi
    release()
    await waitFor(() => expect(downloaded).toHaveLength(1))
    expect(downloaded[0]).toMatch(/^kurumsal-ana-sayfa-optimize-gorseller-\d{8}-\d{4}\.zip$/)
    expect(isImageWorkBusy()).toBe(false)
  })

  it('çıkışta (oturum sıfırlama) süren toplu indirme kesilir: hiçbir şey inmez, "süren iş" kaydı boşalır', async () => {
    let release
    const hold = new Promise((r) => { release = r })
    api.monitoring.resourceScanImage.mockImplementation(async () => { await hold; return { success: true, blob: blobOf(1600 * 1024), name: 'banner.webp', type: 'webp', width: 3840, height: 2160 } })
    const view = await mount()
    fireEvent.click(slot('prs-it-bulk-open', slot('prs-images')))
    await waitFor(() => expect(slot('prs-it-bulk-start')).not.toBeNull())
    fireEvent.click(slot('prs-it-bulk-start'))
    await waitFor(() => expect(isImageWorkBusy()).toBe(true))
    view.unmount()
    emitSessionReset()
    expect(isImageWorkBusy()).toBe(false)
    release()
    await new Promise((r) => setTimeout(r, 30))
    expect(downloaded).toEqual([])
  })

  it('"boyutu fazla" önerisinin toplu düğmesi KENDİ kümesini açar (büyük görsel kümesini değil)', async () => {
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => {
      const d = scanDetail(scanId)
      const wide = d.items.find((it) => Array.isArray(it.findings) && it.findings.includes('OVERSIZED_IMAGE_DIMENSIONS'))
      d.analysis.recommendations = [...d.analysis.recommendations,
        { code: 'OVERSIZED_IMAGE_DIMENSIONS', severity: 'medium', params: { count: 1, max_width: 2000 }, saving_bytes: null, saving_ms: null, item_urls: wide ? [wide.url] : [] }]
      return Promise.resolve(ok(d))
    })
    await mount()
    const rec = await waitFor(() => { const r = document.querySelector('[data-slot="prs-rec"][data-code="OVERSIZED_IMAGE_DIMENSIONS"]'); expect(r).not.toBeNull(); return r })
    const btn = slot('prs-it-bulk-open', rec)
    expect(btn).toHaveTextContent('Ölçüsü büyük görselleri küçültüp indir (ZIP)')
    fireEvent.click(btn)
    await waitFor(() => expect(slot('prs-it-bulk-dialog')?.getAttribute('data-finding')).toBe('OVERSIZED_IMAGE_DIMENSIONS'))
    const large = document.querySelector('[data-slot="prs-rec"][data-code="LARGE_IMAGES"]')
    expect(slot('prs-it-bulk-open', large)).toHaveTextContent('Büyük görselleri optimize edip indir (ZIP)')
  })
})
