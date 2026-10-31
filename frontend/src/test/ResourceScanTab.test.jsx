import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

// Sayfa bütünlüğü → Kaynak analizi sekmesi (2026-10-31): boş durum + başlat, süren koşu → bitti (ardışık sorgu yanıtları),
// 409 süren koşuyu izler, 429 sunucu metni, okunamayan geçmiş (asla "analiz yok"), pano (not, farklar, öneriler, notlar),
// nesne listesi (tür / kova / kapsam / durum / arama / sıralama / sayfalama), kategoriye basınca süzme, öneri nesneleri,
// eski analiz (`items_kept=false`), başarısız analiz, dışa aktarım (tembel modül).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    monitoring: { resourceScans: vi.fn(), resourceScan: vi.fn(), startResourceScan: vi.fn() },
  }),
}))
const exportMock = vi.hoisted(() => ({ exportResourceScanXlsx: vi.fn(), exportResourceScanPdf: vi.fn(() => Promise.resolve()) }))
vi.mock('../components/page/resourcescan/resourceScanExport.js', () => exportMock)

import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import ResourceScanTab from '../components/page/resourcescan/ResourceScanTab.jsx'
import { scanDetail, scanHistory, scanSummary } from './helpers/resourceScanFixtures.js'

const MONITOR = { id: 100, name: 'Kurumsal Ana Sayfa', url: 'https://www.example.com/' }
const ok = (data) => ({ success: true, data })
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]
const tab = (name) => screen.getByRole('tab', { name })
const openTab = (name) => fireEvent.mouseDown(tab(name), { button: 0 })

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.monitoring.resourceScans.mockResolvedValue(ok(scanHistory()))
  api.monitoring.resourceScan.mockImplementation((_id, scanId) => {
    if (scanId === 10) return Promise.resolve(ok({ ...scanDetail(10, { scan: { grade: 64, grade_letter: 'D', items_kept: false } }), items: null, items_kept: false }))
    if (scanId === 11) return Promise.resolve(ok({ scan: scanHistory().items[1], analysis: null, items: null, items_kept: false }))
    return Promise.resolve(ok(scanDetail(scanId)))
  })
})

async function mountDone() {
  render(<ResourceScanTab monitor={MONITOR} pollMs={15} />)
  await waitFor(() => expect(slot('prs-hero')).not.toBeNull())
}

describe('ResourceScanTab — ilk analiz ve izleme', () => {
  it('geçmiş boş: açıklamalı boş durum + "Analizi başlat"; başlatınca ilerleme, bitince pano', async () => {
    api.monitoring.resourceScans.mockResolvedValueOnce(ok({ items: [], running: null, can_run: true, limits: { max_seconds: 300 } }))
    const running = scanSummary(20, { status: 'RUNNING', progress: { done: 0, total: 0 }, grade: null, load_ms: null, finished_at: null })
    api.monitoring.startResourceScan.mockResolvedValue(ok(running))
    let polls = 0
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => {
      polls += 1
      if (polls === 1) return Promise.resolve(ok({ scan: { ...running, progress: { done: 12, total: 48 } }, analysis: null, items: null, items_kept: true }))
      return Promise.resolve(ok(scanDetail(scanId)))
    })
    api.monitoring.resourceScans.mockResolvedValue(ok(scanHistory({ extra: [] })))

    render(<ResourceScanTab monitor={MONITOR} pollMs={15} />)
    await waitFor(() => expect(slot('prs-empty')).not.toBeNull())
    expect(slots('prs-empty-facts')[0].querySelectorAll('li')).toHaveLength(4)
    expect(screen.queryByText(/okunamadı/i)).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: /Analizi başlat/ }))
    await waitFor(() => expect(slot('prs-running')).not.toBeNull())
    expect(api.monitoring.startResourceScan).toHaveBeenCalledWith(100)
    await waitFor(() => expect(slot('prs-progress-text')?.textContent).toMatch(/12 \/ 48/))
    await waitFor(() => expect(slot('prs-hero')).not.toBeNull())
    expect(slot('prs-running')).toBeNull()
    expect(slot('prs-grade').getAttribute('data-letter')).toBe('C')
    expect(api.monitoring.resourceScans.mock.calls.length).toBeGreaterThanOrEqual(2)   // bitince geçmiş tazelendi
  })

  it('409 RESOURCE_SCAN_RUNNING: yanıttaki süren koşu izlenir (hata tostu yok)', async () => {
    const running = scanSummary(30, { status: 'RUNNING', progress: { done: 5, total: 20 }, grade: null })
    api.monitoring.startResourceScan.mockResolvedValue({ success: false, status: 409, code: 'RESOURCE_SCAN_RUNNING', error: 'Zaten sürüyor', data: running })
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => (scanId === 30
      ? Promise.resolve(ok({ scan: running, analysis: null, items: null, items_kept: true }))
      : Promise.resolve(ok(scanDetail(scanId)))))
    await mountDone()
    fireEvent.click(screen.getByRole('button', { name: /Yeniden analiz et/ }))
    await waitFor(() => expect(slot('prs-running')).not.toBeNull())
    expect(slot('prs-progress-text').textContent).toMatch(/5 \/ 20/)
    // önceki tamamlanmış analiz altta görünmeye devam eder
    expect(slot('prs-hero')).not.toBeNull()
    expect(screen.queryByText('Zaten sürüyor')).toBeNull()
  })

  it('429: sunucunun metni gösterilir, koşu başlamaz', async () => {
    api.monitoring.startResourceScan.mockResolvedValue({ success: false, status: 429, code: 'RATE_LIMITED',
      error: 'Dakikada en çok 3 analiz başlatabilirsiniz; 40 saniye sonra yeniden deneyin.' })
    await mountDone()
    fireEvent.click(screen.getByRole('button', { name: /Yeniden analiz et/ }))
    expect(await screen.findByText('Dakikada en çok 3 analiz başlatabilirsiniz; 40 saniye sonra yeniden deneyin.')).toBeInTheDocument()
    expect(slot('prs-running')).toBeNull()
  })

  it('açılışta süren koşu varsa izlenir; yetkisiz kullanıcıya başlat düğmesi yok', async () => {
    const running = scanSummary(40, { status: 'RUNNING', progress: { done: 3, total: 9 }, grade: null })
    api.monitoring.resourceScans.mockResolvedValue(ok(scanHistory({ running, canRun: false })))
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => (scanId === 40
      ? Promise.resolve(ok({ scan: running, analysis: null, items: null, items_kept: true }))
      : Promise.resolve(ok(scanDetail(scanId)))))
    render(<ResourceScanTab monitor={MONITOR} pollMs={15} />)
    await waitFor(() => expect(slot('prs-running')).not.toBeNull())
    await waitFor(() => expect(api.monitoring.resourceScan).toHaveBeenCalledWith(100, 40))
    expect(slot('prs-start')).toBeNull()
  })

  it('geçmiş okunamazsa "okunamadı" + Tekrar dene (asla "analiz yok"); yeniden deneyince yüklenir', async () => {
    api.monitoring.resourceScans.mockResolvedValueOnce({ success: false, status: 503, error: 'Veritabanına şu an ulaşılamıyor.' })
    render(<ResourceScanTab monitor={MONITOR} pollMs={15} />)
    await waitFor(() => expect(slot('prs-history-error')).not.toBeNull())
    expect(screen.queryByText(/Henüz analiz yok/)).toBeNull()
    expect(slot('prs-empty')).toBeNull()
    fireEvent.click(within(slot('prs-history-error')).getByRole('button', { name: /Tekrar dene/ }))
    await waitFor(() => expect(slot('prs-hero')).not.toBeNull())
  })
})

describe('ResourceScanTab — pano', () => {
  it('en yeni tamamlanmış analiz açılır; not, önceki tamamlanmış analize göre farklar, kutucuklar', async () => {
    await mountDone()
    expect(api.monitoring.resourceScan).toHaveBeenCalledWith(100, 12)
    expect(slot('prs-grade').getAttribute('data-tone')).toBe('warning')
    expect(slots('prs-kpi').map((k) => k.getAttribute('data-key'))).toEqual(['load', 'size', 'requests', 'images', 'docs'])
    // önceki TAMAMLANMIŞ analiz 10 (11 başarısız): yüklenme 2900 → 2450 daha iyi
    const load = slots('prs-kpi').find((k) => k.getAttribute('data-key') === 'load')
    expect(slot('prs-delta', load).getAttribute('data-direction')).toBe('better')
    const grade = slot('prs-hero').querySelector('[data-slot="prs-delta"]')
    expect(grade.getAttribute('data-direction')).toBe('better')
    expect(slot('prs-meta').textContent).toMatch(/Ayşe Yılmaz Çok Uzun Soyadlı/)
  })

  it('öneriler önem sırasında; üçüncü taraf sunucular ve olası kazanç; ölçüm notları', async () => {
    await mountDone()
    const codes = slots('prs-rec').map((r) => r.getAttribute('data-code'))
    expect(codes[0]).toBe('LARGE_IMAGES')
    expect(codes.at(-1)).toBe('NO_CACHE')
    expect(slot('prs-rec-hosts').textContent).toMatch(/analytics\.example\.net/)
    expect(slots('prs-rec-saving').length).toBeGreaterThan(0)
    expect(slots('prs-note').map((n) => n.getAttribute('data-code'))).toEqual(['JS_NOT_EXECUTED', 'EXCLUDES_APPLIED'])
    expect(slot('prs-notes').textContent).toMatch(/2 nesne/)
    expect(slot('prs-img-summary').textContent).toMatch(/4G/)
    expect(slots('prs-image-row')).toHaveLength(3)
    expect(document.querySelectorAll('img[src^="http"]')).toHaveLength(0)   // uzak görsel çizilmez
  })

  it('EN: sayı tam 1 iken tekil biçim (öneri başlığı ve kutucuk alt satırı); başlık eylemleri sm+ tek satır', async () => {
    localStorage.setItem(LANG_STORAGE_KEY, 'en')
    api.monitoring.resourceScan.mockImplementation((_id, scanId) =>
      Promise.resolve(ok(scanDetail(scanId, { scan: { failed_count: 1, document_count: 1 } }))))
    await mountDone()
    const rec = (code) => slots('prs-rec').find((r) => r.getAttribute('data-code') === code)
    expect(rec('LARGE_IMAGES').textContent).toMatch(/1 large image \(1\.6 MB\)/)
    expect(rec('LARGE_IMAGES').textContent).not.toMatch(/1 large images/)
    expect(rec('BROKEN_LINKS').textContent).toMatch(/1 link does not work/)
    expect(rec('NO_CACHE').textContent).toMatch(/2 objects are not cached/)   // çoğul kalır
    const kpi = (k) => slots('prs-kpi').find((el) => el.getAttribute('data-key') === k)
    expect(kpi('requests').textContent).toMatch(/1 object did not load/)
    expect(kpi('docs').textContent).toMatch(/1 document \(6 MB\) · 7 links/)
    expect(slot('prs-notes').textContent).toMatch(/2 objects matching/)
    // sm+ genişlikte eylemler sarmaz, künye sütunu kalan genişliği alır (gerçek ölçüm e2e'de)
    expect(slot('prs-actions').className).toMatch(/sm:flex-nowrap/)
    expect(slot('prs-actions').className).toMatch(/sm:shrink-0/)
  })

  it('kategoriye basınca nesne listesi o türe süzülür', async () => {
    await mountDone()
    const img = slots('prs-cat-row').find((r) => r.getAttribute('data-category') === 'IMAGE')
    fireEvent.click(within(img).getByRole('button'))
    await waitFor(() => expect(slot('prs-objects')).not.toBeNull())
    expect(slot('prs-count').textContent).toMatch(/34 \/ 40/)
    const chip = slot('prs-type-chips').querySelector('[data-category="IMAGE"]')
    expect(chip.getAttribute('data-state')).toBe('on')
    expect(slots('prs-obj').every((r) => r.getAttribute('data-category') === 'IMAGE')).toBe(true)
  })

  it('"Nesneleri göster" önerinin nesnelerine süzer; süzgeç kaldırılabilir', async () => {
    await mountDone()
    const rec = slots('prs-rec').find((r) => r.getAttribute('data-code') === 'UNCOMPRESSED_TEXT')
    fireEvent.click(within(rec).getByRole('button', { name: /nesneleri göster/i }))
    await waitFor(() => expect(slot('prs-urls-filter')).not.toBeNull())
    expect(slots('prs-obj')).toHaveLength(1)
    expect(slot('prs-obj').textContent).toMatch(/app\.bundle\.js/)
    fireEvent.click(within(slot('prs-urls-filter')).getByRole('button'))
    await waitFor(() => expect(slot('prs-count').textContent).toMatch(/40 \/ 40/))
  })
})

describe('ResourceScanTab — nesneler', () => {
  async function openObjects() {
    await mountDone()
    openTab(/Nesneler/)
    await waitFor(() => expect(slot('prs-objects')).not.toBeNull())
  }

  it('varsayılan büyükten küçüğe, sayfalama (25), tablo görünümü, tam URL', async () => {
    await openObjects()
    expect(slot('prs-objects-list').getAttribute('data-layout')).toBe('table')
    const rows = slots('prs-obj')
    expect(rows).toHaveLength(25)
    expect(rows[0].getAttribute('data-category')).toBe('DOCUMENT')
    expect(rows[1].getAttribute('data-category')).toBe('IMAGE')
    expect(rows[1].textContent).toContain('ana-sayfa-banner-yuksek-cozunurluk-varyant-1.webp')
    expect(slot('prs-count').textContent).toMatch(/40 \/ 40/)
    const link = slot('prs-obj-url', rows[0])
    expect(link.getAttribute('target')).toBe('_blank')
    expect(link.getAttribute('rel')).toBe('noopener noreferrer')
  })

  it('arama, kova, durum, kapsam ve sıralama; temizle', async () => {
    await openObjects()
    fireEvent.change(slot('prs-search'), { target: { value: 'BUNDLE' } })
    await waitFor(() => expect(slots('prs-obj')).toHaveLength(1))
    fireEvent.change(slot('prs-search'), { target: { value: '' } })

    fireEvent.click(slot('prs-bucket-chips').querySelector('[data-bucket="xl"]'))
    await waitFor(() => expect(slots('prs-obj')).toHaveLength(2))
    fireEvent.click(slot('prs-bucket-chips').querySelector('[data-bucket="xl"]'))

    fireEvent.click(screen.getByRole('button', { name: 'Yalnız açılmayanlar' }))
    await waitFor(() => expect(slots('prs-obj')).toHaveLength(2))
    expect(slots('prs-obj').map((r) => r.getAttribute('data-category')).sort()).toEqual(['IMAGE', 'LINK'])
    fireEvent.click(screen.getByRole('button', { name: /^Tümü$/ , pressed: false }))

    fireEvent.click(screen.getByRole('button', { name: 'Belgeler' }))
    await waitFor(() => expect(slots('prs-obj')).toHaveLength(1))
    expect(screen.getByRole('button', { name: /Süzgeçleri temizle \(1\)/ })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Süzgeçleri temizle/ }))
    await waitFor(() => expect(slot('prs-count').textContent).toMatch(/40 \/ 40/))

    fireEvent.change(slot('prs-sort'), { target: { value: 'size_asc' } })
    await waitFor(() => expect(slots('prs-obj')[0].getAttribute('data-category')).toBe('IMAGE'))
    expect(slots('prs-obj')[0].textContent).toMatch(/missing\/logo\.png/)
  })

  it('tür çipleri sayaçlı ve çoklu seçim; uyan yoksa "uyan nesne yok" + temizle', async () => {
    await openObjects()
    const chips = slot('prs-type-chips')
    expect(chips.querySelector('[data-category="IMAGE"]').getAttribute('data-count')).toBe('34')
    fireEvent.click(chips.querySelector('[data-category="CSS"]'))
    fireEvent.click(chips.querySelector('[data-category="FONT"]'))
    await waitFor(() => expect(slots('prs-obj')).toHaveLength(2))
    fireEvent.change(slot('prs-search'), { target: { value: 'yok-boyle-bir-adres' } })
    await waitFor(() => expect(slot('prs-objects-nomatch')).not.toBeNull())
  })
})

describe('ResourceScanTab — geçmiş, eski ve başarısız analiz, dışa aktarım', () => {
  it('nesne ayrıntısı saklanmayan eski analiz: pano görünür, nesnelerde bilgi notu, kategori satırı düğme değil', async () => {
    await mountDone()
    openTab(/Geçmiş/)
    await waitFor(() => expect(slot('prs-history')).not.toBeNull())
    const rows = slots('prs-history-row')
    expect(rows.map((r) => r.getAttribute('data-id'))).toEqual(['12', '11', '10', '9', '8', '7', '6'])
    expect(rows[0].getAttribute('aria-current')).toBe('true')
    fireEvent.click(rows[2])
    await waitFor(() => expect(api.monitoring.resourceScan).toHaveBeenCalledWith(100, 10))
    await waitFor(() => expect(slot('prs-grade')?.getAttribute('data-letter')).toBe('D'))
    expect(within(slot('prs-categories')).queryAllByRole('button')).toHaveLength(0)
    openTab(/Nesneler/)
    await waitFor(() => expect(slot('prs-items-not-kept')).not.toBeNull())
  })

  it('başarısız analiz: hata bloğu sunucunun nedeniyle ve yeniden analiz', async () => {
    await mountDone()
    openTab(/Geçmiş/)
    await waitFor(() => expect(slot('prs-history')).not.toBeNull())
    fireEvent.click(slots('prs-history-row')[1])
    await waitFor(() => expect(slot('prs-failed')).not.toBeNull())
    expect(slot('prs-failed').textContent).toMatch(/30 sn içinde yanıt vermedi/)
    expect(slot('prs-retry-run')).not.toBeNull()
  })

  it('Excel ve PDF düğmeleri tembel dışa aktarım modülünü seçili analizle çağırır', async () => {
    await mountDone()
    fireEvent.click(slot('prs-export-xlsx'))
    await waitFor(() => expect(exportMock.exportResourceScanXlsx).toHaveBeenCalled())
    const args = exportMock.exportResourceScanXlsx.mock.calls[0][0]
    expect(args.scan.id).toBe(12)
    expect(args.items).toHaveLength(40)
    expect(args.monitor).toBe(MONITOR)
    fireEvent.click(slot('prs-export-pdf'))
    await waitFor(() => expect(exportMock.exportResourceScanPdf).toHaveBeenCalled())
  })

  it('dışa aktarım hatası: çevrilmiş metin, tarayıcı metni değil', async () => {
    exportMock.exportResourceScanXlsx.mockImplementationOnce(() => { throw new RangeError('Invalid array length') })
    await mountDone()
    fireEvent.click(slot('prs-export-xlsx'))
    expect(await screen.findByText(/Dışa aktarım hazırlanamadı/)).toBeInTheDocument()
    expect(screen.queryByText('Invalid array length')).toBeNull()
  })
})
