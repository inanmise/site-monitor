import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

// Kaynak analizi — r177 (inceleme sonrası) ekran davranışları: sunucu bulguları / kararı, "Notu en çok düşüren 3 şey",
// hedefler, öneriden TAM listeye geçiş + odak (H1), seçim değişince süzgeç (H5), süren analiz satırı (H4), eski / başarısız
// analiz şeritleri, nesne ayrıntı penceresi (yeni alanlar / eski kayıtta "kaydedilmemiş"), şelale, karşılaştırma,
// paylaşılabilir bağlantı + URL durumu, iptal (eski sunucuda gizlenir), bitiş bildirimi (gizli sekme ve sökülen sekme),
// üçüncü taraflar, ölçülmüş görsel kazancı, geçen süre (sunucu saati) ve ilerleme (geri gitmez).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    monitoring: { resourceScans: vi.fn(), resourceScan: vi.fn(), startResourceScan: vi.fn(), cancelResourceScan: vi.fn() },
  }),
}))
const copyMock = vi.hoisted(() => ({ copyText: vi.fn(async () => true) }))
vi.mock('../utils/copyText.js', () => copyMock)
const navMock = vi.hoisted(() => ({ applyTabView: vi.fn(), navigateTo: vi.fn() }))
const exportMock = vi.hoisted(() => ({ exportResourceScanXlsx: vi.fn(), exportResourceScanPdf: vi.fn(() => Promise.resolve()) }))
vi.mock('../components/page/resourcescan/resourceScanExport.js', () => exportMock)
vi.mock('../utils/navigate.js', () => ({ ...navMock, default: navMock.navigateTo, APPLY_VIEW_EVENT: 'sm:apply-view' }))

import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import ResourceScanTab from '../components/page/resourcescan/ResourceScanTab.jsx'
import { resetWatchers } from '../components/page/resourcescan/resourceScanWatcher.js'
import { scanDetail, scanDetailV2, scanHistory, scanItems, scanSummary } from './helpers/resourceScanFixtures.js'

/** Sürüm 2 geçmişi: özet satırları `algorithm_version: 2` taşır (sunucu r177). */
function v2History(opts) {
  const h = scanHistory(opts)
  return { ...h, items: h.items.map((s) => (s.status === 'DONE' ? { ...s, algorithm_version: 2 } : s)) }
}

const MONITOR = { id: 100, name: 'Kurumsal Ana Sayfa', url: 'https://www.example.com/' }
const ok = (data, extra = {}) => ({ success: true, data, ...extra })
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]
const tab = (name) => screen.getByRole('tab', { name })
const openTab = (name) => fireEvent.mouseDown(tab(name), { button: 0 })

/** v2 (sürüm 2) ayrıntı: en yeni (12) ve 9 sürüm 2; 10 eski (ayrıntı saklanmamış), 11 başarısız. */
function v2Detail(scanId) {
  if (scanId === 10) return ok({ ...scanDetail(10, { scan: { grade: 64, grade_letter: 'D' } }), items: null, items_kept: false })
  if (scanId === 11) return ok({ scan: scanHistory().items[1], analysis: null, items: null, items_kept: false })
  return ok(scanDetailV2(scanId))
}

beforeEach(() => {
  vi.clearAllMocks()
  resetWatchers()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  window.history.replaceState(null, '', '/?tab=page&monitor=100&mtab=resources')
  api.monitoring.resourceScans.mockResolvedValue(ok(v2History()))
  api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve(v2Detail(scanId)))
})
afterEach(() => { resetWatchers() })

async function mountDone(props = {}) {
  render(<ResourceScanTab monitor={MONITOR} pollMs={15} {...props} />)
  await waitFor(() => expect(slot('prs-hero')).not.toBeNull())
}

describe('pano — notun nedeni, hedefler, sıra', () => {
  it('"Notu en çok düşüren 3 şey" (kayıp puan) ve ilgili öneriye git → öneri odaklanır', async () => {
    await mountDone()
    const losses = slots('prs-loss')
    expect(losses.map((l) => l.getAttribute('data-key'))).toEqual(['load_time', 'page_weight', 'images'])
    expect(losses[0].textContent).toMatch(/−9,5 puan/)
    expect(losses[0].textContent).toMatch(/Tam puan: ≤ 1 sn · sıfır puan: ≥ 12 sn/)
    fireEvent.click(within(losses[2]).getByRole('button', { name: /İlgili öneriye git/ }))
    const rec = slots('prs-rec').find((r) => r.getAttribute('data-code') === 'LARGE_IMAGES')
    await waitFor(() => expect(document.activeElement).toBe(rec))
  })

  it('öneriler panoda yukarıda (türlerden önce); bileşen satırında kayıp ve hedef; "değişmedi" gürültüsü yok', async () => {
    await mountDone()
    // öneriler türlerden, görsellerden ve grafikten ÖNCE (eylem yukarıda)
    for (const later of ['prs-categories', 'prs-scores', 'prs-images', 'prs-third']) {
      expect(slot('prs-recs').compareDocumentPosition(slot(later)) & Node.DOCUMENT_POSITION_FOLLOWING, later).toBeTruthy()
    }
    const img = slots('prs-score').find((s) => s.getAttribute('data-key') === 'images')
    expect(img.textContent).toMatch(/nottan −9 puan/)
    expect(slot('prs-score-target', img)).not.toBeNull()
    expect(slots('prs-delta', slot('prs-hero')).every((d) => d.getAttribute('data-direction') !== 'same')).toBe(true)
    expect(slot('prs-delta-base').textContent).toMatch(/2026-10-29T09:00:00 tarihli önceki analize göre/)
  })

  it('eski analizde (alanlar yok) kayıp puan istemcide hesaplanır, hedef UYDURULMAZ', async () => {
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve(ok(scanDetail(scanId))))
    await mountDone()
    expect(slots('prs-loss').map((l) => l.getAttribute('data-key'))).toEqual(['load_time', 'page_weight', 'images'])
    expect(slots('prs-score-target')).toHaveLength(0)
  })

  it('üçüncü taraf tablosu, ölçülmüş görsel kazancı ("1,6 MB → 400 KB"), yönlendirmeli TTFB', async () => {
    await mountDone()
    expect(slots('prs-third-row').map((r) => r.getAttribute('data-host'))).toEqual(['cok-uzun-bir-icerik-dagitim-agi-adresi-1.example.test', 'analytics.example.net'])
    const tile = slots('prs-img-tile').find((x) => x.getAttribute('data-key') === 'saving')
    expect(tile.getAttribute('data-measured')).toBe('true')
    expect(tile.textContent).toMatch(/Ölçülen kazanç/)
    expect(slot('prs-img-optimized').textContent).toMatch(/1,6 MB → 400 KB/)
    expect(slots('prs-kpi').find((k) => k.getAttribute('data-key') === 'load').textContent).toMatch(/2 yönlendirme \(480 ms\)/)
    // yeniden sıkıştırma küçültmediyse (optimized_bytes = orijinal) "zaten iyi sıkıştırılmış" — kazanç gibi gösterilmez
    const good = slots('prs-img-optimized').find((x) => x.getAttribute('data-already-good') === 'true')
    expect(good.textContent).toBe('Zaten iyi sıkıştırılmış')
    // ölçülen + tahmini kazanç ayrımı
    expect(tile.textContent).toMatch(/2 görsel ölçüldü/)
    // TTFB: sunucunun `ttfb_ms` alanı (son adım) `html_ttfb_ms`'ten önce gelir
    expect(slots('prs-kpi').find((k) => k.getAttribute('data-key') === 'load').textContent).toMatch(/300 ms/)
  })

  it('sınırlı ölçümde hüküm "Kısmi ölçüm" (asla "Mükemmel")', async () => {
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve(ok(scanDetailV2(scanId, { scan: { capped: true, grade: 96, grade_letter: 'A' } }))))
    await mountDone()
    expect(slot('prs-verdict').textContent).toBe('Kısmi ölçüm — A (kesin değil)')
  })

  it('farklı not algoritması sürümüyle karşılaştırmada not farkı gösterilmez', async () => {
    const hist = scanHistory()
    hist.items[2] = { ...hist.items[2], algorithm_version: 1 }
    api.monitoring.resourceScans.mockResolvedValue(ok(hist))
    await mountDone()
    expect(slot('prs-algo-changed')).not.toBeNull()
    expect(slot('prs-verdict').parentElement.querySelector('[data-slot="prs-delta"]')).toBeNull()
  })
})

describe('nesneler — bulgular, karar, ayrıntı, şelale', () => {
  it('rozetler sunucunun bulgularından; "Nesneleri göster (N)" sunucunun item_count değerini yazar ve tam listeyi süzer', async () => {
    await mountDone()
    const rec = slots('prs-rec').find((r) => r.getAttribute('data-code') === 'NO_CACHE')
    expect(slot('prs-rec-show', rec).getAttribute('data-count')).toBe('1')
    fireEvent.click(slot('prs-rec-show', rec))
    await waitFor(() => expect(slot('prs-objects')).not.toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(slot('prs-count')))   // H1: odak sayaçta
    expect(slots('prs-obj')).toHaveLength(1)
    const row = slot('prs-obj')
    expect([...row.querySelectorAll('[data-finding]')].map((f) => f.getAttribute('data-finding')))
      .toEqual(['UNCOMPRESSED_TEXT', 'NO_CACHE', 'REDIRECTED_OBJECTS', 'HEAVY_JS'])
  })

  it('sayfa düzeyi öneri (item_count null): adres sayısını yazar, o adreslere süzer', async () => {
    await mountDone()
    const rec = slots('prs-rec').find((r) => r.getAttribute('data-code') === 'THIRD_PARTY_HEAVY')
    expect(slot('prs-rec-show', rec).getAttribute('data-count')).toBe('1')
    fireEvent.click(slot('prs-rec-show', rec))
    await waitFor(() => expect(slot('prs-objects')).not.toBeNull())
    expect(slot('prs-rec-filter')).not.toBeNull()
    expect(slots('prs-obj')).toHaveLength(1)
    expect(slot('prs-obj').getAttribute('data-category')).toBe('IMAGE')
    // nesneye "üçüncü taraf ağır" bulgusu yazılmaz (sunucu: yalnız 13 nesne kodu)
    expect(slot('prs-obj').querySelector('[data-finding="THIRD_PARTY_HEAVY"]')).toBeNull()
  })

  it('indirilmeyen medya "İndirilmedi" (kırık değil); boyutu bilinmeyen bağlantı "Bilinmiyor" (0 B değil)', async () => {
    const d = scanDetailV2(12)
    d.items.push(
      { ...d.items[8], url: 'https://www.example.com/video/tanitim-filmi.mp4', category: 'MEDIA', status: 0, bytes: 0, verdict: 'skipped',
        page_load: false, lazy: true, start_ms: null, optimized_bytes: null, width: null, height: null, headers: null },
      { ...d.items[7], url: 'https://www.example.com/hakkimizda', status: 200, bytes: 0, size_source: 'unknown', verdict: 'ok', error: null,
        duration_ms: 90, ttfb_ms: 80 },
    )
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve(scanId === 12 ? ok(d) : v2Detail(scanId)))
    await mountDone()
    openTab(/Nesneler/)
    await waitFor(() => expect(slot('prs-objects')).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: /^Tümü \(\d+\)$/ }))
    fireEvent.change(slot('prs-search'), { target: { value: 'tanitim-filmi' } })
    await waitFor(() => expect(slots('prs-obj')).toHaveLength(1))
    const st = slot('prs-obj-status')
    expect(st.textContent).toBe('İndirilmedi')
    expect(st.getAttribute('data-failed')).toBe('false')
    expect(slot('prs-obj-verdict')).toBeNull()
    fireEvent.change(slot('prs-search'), { target: { value: 'hakkimizda' } })
    await waitFor(() => expect(slot('prs-obj')?.textContent).toMatch(/hakkimizda/))
    expect(slot('prs-obj').textContent).toMatch(/Bilinmiyor/)
    expect(slot('prs-obj').textContent).not.toMatch(/0 B/)
    expect(slot('prs-obj').getAttribute('data-bucket')).toBeNull()
  })

  it('doğrulanamayan nesne nötr ve kırık sayılmaz; "Doğrulanamayanlar" süzgeci çıkar', async () => {
    await mountDone()
    openTab(/Nesneler/)
    await waitFor(() => expect(slot('prs-objects')).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: /^Tümü \(\d+\)$/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Doğrulanamayanlar' }))
    await waitFor(() => expect(slots('prs-obj')).toHaveLength(1))
    const st = slot('prs-obj-status')
    expect(st.getAttribute('data-verdict')).toBe('unverified')
    expect(st.getAttribute('data-failed')).toBe('false')
    expect(st.getAttribute('data-tone')).toBe('muted')
    expect(slot('prs-obj').textContent).toMatch(/Yanıt yok/)
    expect(slot('prs-obj').textContent).not.toMatch(/Bağlantı kurulamadı/)
  })

  it('nesne ayrıntı penceresi: son adres, bulunduğu yer, yol, başlıklar, bulgular → öneriye git', async () => {
    await mountDone()
    openTab(/Nesneler/)
    await waitFor(() => expect(slot('prs-objects')).not.toBeNull())
    fireEvent.change(slot('prs-search'), { target: { value: 'app.bundle' } })
    await waitFor(() => expect(slots('prs-obj')).toHaveLength(1))
    fireEvent.click(within(slot('prs-obj')).getByRole('button', { name: /Nesne ayrıntısı/ }))
    const od = await waitFor(() => { const el = slot('prs-od'); expect(el).not.toBeNull(); return el })
    expect(slot('prs-od-final', od).textContent).toMatch(/app\.bundle\.v2\.js/)
    expect(slot('prs-od-found', od).textContent).toMatch(/Sayfanın HTML'i/)
    expect(slot('prs-od-via', od).textContent).toMatch(/doğrudan bağlantı/)
    expect(slot('prs-od-headers', od).textContent).toMatch(/no-store/)
    expect(slot('prs-od-headers', od).textContent).not.toMatch(/Cookie|Set-Cookie/i)
    fireEvent.click(within(slot('prs-od-findings', od)).getAllByRole('button', { name: /Öneriyi göster/ })[0])
    await waitFor(() => expect(slot('prs-od')).toBeNull())
    await waitFor(() => expect(slot('prs-hero')).not.toBeNull())
  })

  it('eski analizde ayrıntı penceresi "Bu analizde kaydedilmemiş" der (değer uydurmaz)', async () => {
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve(ok(scanDetail(scanId))))
    await mountDone()
    openTab(/Nesneler/)
    await waitFor(() => expect(slot('prs-objects')).not.toBeNull())
    fireEvent.click(within(slots('prs-obj')[0]).getByRole('button', { name: /Nesne ayrıntısı/ }))
    const od = await waitFor(() => { const el = slot('prs-od'); expect(el).not.toBeNull(); return el })
    expect(slot('prs-od-final', od).getAttribute('data-missing')).toBe('true')
    expect(slot('prs-od-via', od).textContent).toMatch(/Bu analizde kaydedilmemiş/)
    expect(slot('prs-od-headers', od).textContent).toMatch(/kaydedilmemiş/)
  })

  it('şelale görünümü: yalnız tahmine girenler, başlangıç sırası, dışarıda kalan sayısı, eksen; URL prs_v=waterfall', async () => {
    await mountDone()
    openTab(/Nesneler/)
    await waitFor(() => expect(slot('prs-objects')).not.toBeNull())
    fireEvent.click(within(slot('prs-objects-mode')).getByRole('button', { name: 'Şelale' }))
    await waitFor(() => expect(slot('prs-wf')).not.toBeNull())
    const withStart = scanItems().filter((i) => i.start_ms != null && i.page_load !== false)
    expect(slots('prs-wf-row')).toHaveLength(withStart.length)
    expect(slots('prs-wf-row')[0].getAttribute('data-category')).toBe('HTML')   // HTML (başlangıç 0) ilk
    expect(slot('prs-wf-excluded').textContent).toMatch(/1 nesne/)                    // geç yüklenen görsel
    expect(slot('prs-wf-axis')).not.toBeNull()
    await waitFor(() => expect(window.location.search).toMatch(/prs_v=waterfall/))
  })
})

describe('gezinme, seçim, durum metinleri', () => {
  it('kategoriye basınca odak sayaçta (H1); öneri süzgeci başka analize taşınmaz (H5)', async () => {
    await mountDone()
    const rec = slots('prs-rec').find((r) => r.getAttribute('data-code') === 'UNCOMPRESSED_TEXT')
    fireEvent.click(slot('prs-rec-show', rec))
    await waitFor(() => expect(slot('prs-rec-filter')).not.toBeNull())
    openTab(/Geçmiş/)
    await waitFor(() => expect(slot('prs-history')).not.toBeNull())
    fireEvent.click(slots('prs-history-row').find((r) => r.getAttribute('data-id') === '9'))
    await waitFor(() => expect(api.monitoring.resourceScan).toHaveBeenCalledWith(100, 9))
    openTab(/Nesneler/)
    await waitFor(() => expect(slot('prs-objects')).not.toBeNull())
    expect(slot('prs-rec-filter')).toBeNull()
    expect(slot('prs-count').textContent).toMatch(/40 \/ 40/)
  })

  it('eski analiz şeridi → "En yeni analize dön"; başarısız analizde "Son başarılı analizi göster"', async () => {
    await mountDone()
    openTab(/Geçmiş/)
    await waitFor(() => expect(slot('prs-history')).not.toBeNull())
    fireEvent.click(slots('prs-history-row').find((r) => r.getAttribute('data-id') === '9'))
    await waitFor(() => expect(slot('prs-old-run')).not.toBeNull())
    expect(slot('prs-old-run').textContent).toMatch(/eski bir analiz gösteriliyor/)
    fireEvent.click(slot('prs-latest'))
    await waitFor(() => expect(slot('prs-old-run')).toBeNull())
    openTab(/Geçmiş/)
    await waitFor(() => expect(slot('prs-history')).not.toBeNull())
    fireEvent.click(slots('prs-history-row').find((r) => r.getAttribute('data-id') === '11'))
    await waitFor(() => expect(slot('prs-failed')).not.toBeNull())
    expect(slot('prs-meta').textContent).toMatch(/2026-10-30T15:00:00/)   // başarısız analizin künyesi de görünür
    fireEvent.click(slot('prs-show-latest'))
    await waitFor(() => expect(slot('prs-hero')).not.toBeNull())
  })

  it('URL\'deki analiz ve görünüm açılır (prs=10&prs_v=objects); seçim URL\'e yazılır; bağlantı kopyalanır', async () => {
    window.history.replaceState(null, '', '/?tab=page&monitor=100&mtab=resources&prs=10&prs_v=objects')
    render(<ResourceScanTab monitor={MONITOR} pollMs={15} />)
    await waitFor(() => expect(slot('prs-items-not-kept')).not.toBeNull())
    expect(api.monitoring.resourceScan).toHaveBeenCalledWith(100, 10)
    fireEvent.click(slot('prs-share'))
    await waitFor(() => expect(copyMock.copyText).toHaveBeenCalled())
    expect(copyMock.copyText.mock.calls[0][0]).toMatch(/\/\?tab=page&monitor=100&mtab=resources&prs=10&prs_v=objects$/)
  })

  it('başka izlemenin analiz kimliği URL\'de olsa da yok sayılır (en yeni açılır)', async () => {
    window.history.replaceState(null, '', '/?tab=page&monitor=100&mtab=resources&prs=999')
    await mountDone()
    expect(api.monitoring.resourceScan).toHaveBeenCalledWith(100, 12)
    expect(api.monitoring.resourceScan).not.toHaveBeenCalledWith(100, 999)
  })

  it('süren analiz satırına basmak seçimi değiştirmez ve bölümler kaybolmaz (H4)', async () => {
    const running = scanSummary(40, { status: 'RUNNING', progress: { done: 3, total: 9 }, grade: null })
    api.monitoring.resourceScans.mockResolvedValue(ok(scanHistory({ running })))
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => (scanId === 40
      ? Promise.resolve(ok({ scan: running, analysis: null, items: null, items_kept: true }))
      : Promise.resolve(v2Detail(scanId))))
    await mountDone()
    expect(slot('prs-run-show')).toBeNull()
    openTab(/Geçmiş/)
    await waitFor(() => expect(slot('prs-history')).not.toBeNull())
    fireEvent.click(slots('prs-history-row').find((r) => r.getAttribute('data-id') === '40'))
    expect(slot('prs-sections')).not.toBeNull()
    expect(slots('prs-history-row').find((r) => r.getAttribute('data-id') === '12').getAttribute('aria-current')).toBe('true')
    await waitFor(() => expect(document.activeElement).toBe(slot('prs-running')))
  })
})

describe('karşılaştırma', () => {
  it('Geçmiş → Karşılaştır: KPI farkları, algoritma farkı uyarısı, eski analizle yalnız özet', async () => {
    await mountDone()
    openTab(/Geçmiş/)
    await waitFor(() => expect(slot('prs-history')).not.toBeNull())
    fireEvent.click(within(slot('prs-history-mode')).getByRole('button', { name: 'Karşılaştır' }))
    await waitFor(() => expect(slot('prs-cmp-body')).not.toBeNull())
    // varsayılan: seçili (12) ile bir önceki tamamlanmış (10 — sürüm 1, ayrıntı yok)
    expect(slot('prs-cmp-algo')).not.toBeNull()
    expect(slot('prs-cmp-no-objects')).not.toBeNull()
    expect(slots('prs-cmp-kpi').map((k) => k.getAttribute('data-key'))).toEqual(['grade', 'load', 'ttfb', 'size', 'requests', 'images', 'broken'])
    expect(slots('prs-cmp-kpi')[0].getAttribute('data-direction')).toBeNull()   // not farkı yok (sürüm farkı)
    await waitFor(() => expect(window.location.search).toMatch(/prs_v=compare/))
  })

  it('iki sürüm 2 analiz: eklenen / kaldırılan / büyüyen nesneler', async () => {
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => {
      if (scanId === 9) {
        const d = scanDetailV2(9, { scan: { started_at: '2026-10-28T09:00:00' } })
        d.items = [...d.items.slice(0, 2), { ...d.items[2], bytes: d.items[2].bytes - 300 * 1024 }, ...d.items.slice(3, -1),
          { ...d.items[3], url: 'https://www.example.com/static/eski.css' }]
        return Promise.resolve(ok(d))
      }
      return Promise.resolve(v2Detail(scanId))
    })
    await mountDone()
    openTab(/Geçmiş/)
    await waitFor(() => expect(slot('prs-history')).not.toBeNull())
    fireEvent.click(within(slot('prs-history-mode')).getByRole('button', { name: 'Karşılaştır' }))
    await waitFor(() => expect(slot('prs-cmp-b')).not.toBeNull())
    fireEvent.change(slot('prs-cmp-b'), { target: { value: '9' } })
    await waitFor(() => expect(slot('prs-cmp-objects')).not.toBeNull())
    expect(slot('prs-cmp-algo')).toBeNull()
    expect(slot('prs-cmp-added').getAttribute('data-count')).toBe('1')
    expect(slot('prs-cmp-removed').getAttribute('data-count')).toBe('1')
    expect(slot('prs-cmp-grown').textContent).toMatch(/app\.bundle\.js/)
    await waitFor(() => expect(window.location.search).toMatch(/prs_b=9/))
  })
})

describe('iptal, bitiş bildirimi, ilerleme', () => {
  function runningSetup({ cancel } = {}) {
    const running = scanSummary(40, { status: 'RUNNING', progress: { done: 3, total: 9 }, grade: null, finished_at: null,
      started_at: '2026-10-31T09:00:00', owner_pod: 'site-monitor-abc' })
    api.monitoring.resourceScans.mockResolvedValue(ok(scanHistory({ running }), { timestamp: '2026-10-31T09:00:42Z' }))
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => (scanId === 40
      ? Promise.resolve(ok({ scan: running, analysis: null, items: null, items_kept: true }))
      : Promise.resolve(v2Detail(scanId))))
    if (cancel) api.monitoring.cancelResourceScan.mockResolvedValue(cancel)
    return running
  }

  it('iptal: onay → uç çağrılır → "İptal ediliyor…"; geçen süre sunucu saatinden (42 sn), sunucu adı', async () => {
    runningSetup({ cancel: ok({ id: 40, status: 'RUNNING' }) })
    await mountDone()
    expect(slot('prs-elapsed').textContent).toMatch(/42 sn/)
    expect(slot('prs-stage').textContent).toMatch(/site-monitor-abc/)
    fireEvent.click(slot('prs-cancel'))
    fireEvent.click(await screen.findByRole('button', { name: 'İptal et' }))
    await waitFor(() => expect(api.monitoring.cancelResourceScan).toHaveBeenCalledWith(100, 40))
    await waitFor(() => expect(slot('prs-cancel').textContent).toMatch(/İptal ediliyor/))
    expect(slot('prs-cancel').disabled).toBe(true)
    expect(slot('prs-cancel-pending')).not.toBeNull()
  })

  it('iptal 200 + bitmiş özet (INTERRUPTED / CANCELLED): "iptal edildi" tostu; bakılan analiz değişmez', async () => {
    runningSetup({ cancel: ok(scanSummary(40, { status: 'INTERRUPTED', error_code: 'CANCELLED', cancelled: true, grade: null,
      grade_letter: null, finished_at: '2026-10-31T09:00:50', error: 'İptal edildi' })) })
    await mountDone()
    const before = slot('prs-hero').textContent
    fireEvent.click(slot('prs-cancel'))
    fireEvent.click(await screen.findByRole('button', { name: 'İptal et' }))
    expect(await screen.findByText('Analiz iptal edildi; sonuç kaydedilmedi.')).toBeInTheDocument()
    await waitFor(() => expect(slot('prs-running')).toBeNull())
    expect(slot('prs-interrupted')).toBeNull()             // seçim iptal edilen koşuya atlamaz
    expect(slot('prs-hero').textContent).toBe(before)
  })

  it('409 NOT_RUNNING (gövdede bitmiş özet): koşu kartı kalkar, yeniden sorgu beklenmez', async () => {
    runningSetup({ cancel: { success: false, status: 409, code: 'RESOURCE_SCAN_NOT_RUNNING', error: 'Analiz artık sürmüyor',
      data: scanSummary(40, { finished_at: '2026-10-31T09:00:50' }) } })
    await mountDone()
    fireEvent.click(slot('prs-cancel'))
    fireEvent.click(await screen.findByRole('button', { name: 'İptal et' }))
    await waitFor(() => expect(slot('prs-running')).toBeNull())
  })

  it('eski sunucu (genel NOT_FOUND 404 ama izlemenin geçmişi okunuyor): iptal düğmesi gizlenir, analiz izlenir', async () => {
    runningSetup({ cancel: { success: false, status: 404, code: 'NOT_FOUND', error: 'Bulunamadı' } })
    await mountDone()
    fireEvent.click(slot('prs-cancel'))
    fireEvent.click(await screen.findByRole('button', { name: 'İptal et' }))
    await waitFor(() => expect(slot('prs-cancel')).toBeNull())
    expect(api.monitoring.resourceScans).toHaveBeenCalledWith(100, 1)   // izleme yerinde mi yoklaması
    expect(slot('prs-running')).not.toBeNull()
    expect(await screen.findByText(/iptalini desteklemiyor/)).toBeInTheDocument()
  })

  it.each([
    ['405', { success: false, status: 405, error: 'Method Not Allowed' }],
    ['kodsuz 404', { success: false, status: 404, error: 'Not Found' }],
  ])('eski sunucu (%s): yoklamadan düğme gizlenir', async (_n, cancel) => {
    runningSetup({ cancel })
    await mountDone()
    const before = api.monitoring.resourceScans.mock.calls.length
    fireEvent.click(slot('prs-cancel'))
    fireEvent.click(await screen.findByRole('button', { name: 'İptal et' }))
    await waitFor(() => expect(slot('prs-cancel')).toBeNull())
    expect(api.monitoring.resourceScans.mock.calls.length).toBe(before)
  })

  it('izleme silinmiş / görme yetkisi kalkmış (NOT_FOUND 404, geçmiş de 404): düğme GİZLENMEZ, geçmiş yenilenir (D-2)', async () => {
    runningSetup({ cancel: { success: false, status: 404, code: 'NOT_FOUND',
      error: 'Sayfa Bütünlüğü izlemesi bulunamadı ya da görüntüleme yetkiniz yok.' } })
    await mountDone()
    api.monitoring.resourceScans.mockResolvedValue({ success: false, status: 404, code: 'NOT_FOUND',
      error: 'Sayfa Bütünlüğü izlemesi bulunamadı ya da görüntüleme yetkiniz yok.' })
    fireEvent.click(slot('prs-cancel'))
    fireEvent.click(await screen.findByRole('button', { name: 'İptal et' }))
    expect(await screen.findByText(/izlemesi bulunamadı ya da görüntüleme yetkiniz yok/)).toBeInTheDocument()
    await waitFor(() => expect(api.monitoring.resourceScans).toHaveBeenCalledWith(100, 200))   // geçmiş yenilendi
    expect(slot('prs-cancel')).not.toBeNull()
    expect(screen.queryByText(/iptalini desteklemiyor/)).toBeNull()
  })

  it('analiz kaydı yok (RESOURCE_SCAN_NOT_FOUND): düğme gizlenmez, "zaten bitmiş" der ve geçmiş yenilenir', async () => {
    runningSetup({ cancel: { success: false, status: 404, code: 'RESOURCE_SCAN_NOT_FOUND', error: 'Kaynak analizi kaydı bulunamadı' } })
    await mountDone()
    const before = api.monitoring.resourceScans.mock.calls.length
    fireEvent.click(slot('prs-cancel'))
    fireEvent.click(await screen.findByRole('button', { name: 'İptal et' }))
    expect(await screen.findByText(/Analiz zaten bitmiş/)).toBeInTheDocument()
    await waitFor(() => expect(api.monitoring.resourceScans.mock.calls.length).toBeGreaterThan(before))
    expect(api.monitoring.resourceScans).not.toHaveBeenCalledWith(100, 1)
    expect(screen.queryByText(/iptalini desteklemiyor/)).toBeNull()
  })

  it('iptal edilen analiz: "Analiz iptal edildi" (yarıda kaldı değil); geçmişte "İptal edildi"', async () => {
    const hist = scanHistory()
    hist.items[1] = { ...hist.items[1], status: 'INTERRUPTED', error_code: 'CANCELLED', cancelled: true, error: 'İptal edildi' }
    api.monitoring.resourceScans.mockResolvedValue(ok(hist))
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => (scanId === 11
      ? Promise.resolve(ok({ scan: hist.items[1], analysis: null, items: null, items_kept: false }))
      : Promise.resolve(v2Detail(scanId))))
    await mountDone()
    openTab(/Geçmiş/)
    await waitFor(() => expect(slot('prs-history')).not.toBeNull())
    const row = slots('prs-history-row').find((r) => r.getAttribute('data-id') === '11')
    expect(row.textContent).toMatch(/İptal edildi/)
    fireEvent.click(row)
    await waitFor(() => expect(slot('prs-interrupted')).not.toBeNull())
    expect(slot('prs-interrupted').getAttribute('data-cancelled')).toBe('true')
    expect(slot('prs-interrupted').textContent).toMatch(/Analiz iptal edildi/)
  })

  it('ilerleme yüzdesi geri gitmez (CSS\'ten yeni nesne bulununca toplam artsa da)', async () => {
    const running = runningSetup()
    let n = 0
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => {
      if (scanId !== 40) return Promise.resolve(v2Detail(scanId))
      n += 1
      const p = n === 1 ? { done: 8, total: 10 } : { done: 9, total: 20 }
      return Promise.resolve(ok({ scan: { ...running, progress: p }, analysis: null, items: null, items_kept: true }))
    })
    await mountDone()
    await waitFor(() => expect(slot('prs-progress-text').textContent).toMatch(/9 \/ 20/))
    const bar = slot('prs-running').querySelector('[role="progressbar"]')
    expect(Number(bar.getAttribute('aria-valuenow'))).toBe(80)
  })

  it('dış sekme gizliyken biten analiz: "Aç" düğmeli tost → onShow', async () => {
    const running = runningSetup()
    let n = 0
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => {
      if (scanId !== 40) return Promise.resolve(v2Detail(scanId))
      n += 1
      return Promise.resolve(n < 2 ? ok({ scan: running, analysis: null, items: null, items_kept: true }) : ok(scanDetailV2(40)))
    })
    const onShow = vi.fn()
    await mountDone({ active: false, onShow })
    const open = await screen.findByRole('button', { name: 'Aç' })
    expect(open.closest('[data-slot="prs-toast"]').textContent).toMatch(/kaynak analizi bitti: 72 · C/)
    fireEvent.click(open)
    expect(onShow).toHaveBeenCalled()
  })

  it('BU SEKMEDE başlatılan analiz, sekme sökülünce arka planda izlenir; bitince "Aç" → o analiz', async () => {
    api.monitoring.resourceScans.mockResolvedValueOnce(ok({ items: [], running: null, can_run: true, limits: { max_seconds: 300 } }))
    const running = scanSummary(60, { status: 'RUNNING', progress: { done: 0, total: 0 }, grade: null, finished_at: null })
    api.monitoring.startResourceScan.mockResolvedValue(ok(running))
    let polls = 0
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => {
      if (scanId !== 60) return Promise.resolve(v2Detail(scanId))
      polls += 1
      return Promise.resolve(polls < 4 ? ok({ scan: running, analysis: null, items: null, items_kept: true }) : ok(scanDetailV2(60)))
    })
    function Host() {
      const [show, setShow] = useState(true)
      return (
        <>
          <button type="button" onClick={() => setShow(false)}>kapat</button>
          {show && <ResourceScanTab monitor={MONITOR} pollMs={200} />}
        </>
      )
    }
    render(<Host />)
    await waitFor(() => expect(slot('prs-empty')).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: /Analizi başlat/ }))
    await waitFor(() => expect(slot('prs-running')).not.toBeNull())
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'kapat' })) })
    expect(slot('prs-root')).toBeNull()
    const open = await screen.findByRole('button', { name: 'Aç' }, { timeout: 8000 })
    fireEvent.click(open)
    expect(navMock.applyTabView).toHaveBeenCalledWith('page', { monitor: 100, mtab: 'resources', prs: 60 })
  }, 15_000)
})

describe('dışa aktarım argümanları', () => {
  it('nesneler bulgularıyla (findings) dışa aktarım modülüne gider', async () => {
    await mountDone()
    fireEvent.click(slot('prs-export-xlsx'))
    await waitFor(() => expect(exportMock.exportResourceScanXlsx).toHaveBeenCalled())
    const args = exportMock.exportResourceScanXlsx.mock.calls[0][0]
    expect(args.items).toHaveLength(40)
    expect(args.items.every((i) => Array.isArray(i.findings))).toBe(true)
    expect(args.items.find((i) => /app\.bundle\.js$/.test(i.url)).findings).toContain('NO_CACHE')
  })
})
