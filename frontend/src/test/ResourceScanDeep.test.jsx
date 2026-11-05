import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

// Kaynak analizi — prs3 (derin analiz paketleri A–E) EKRAN davranışları: pano kategori puanları → ayrıntılı denetim alanı
// (URL `prs_v=audit-<alan>`, odak alan başlığında), alan gezinmesi ve bulgu sayıları, alan blokları (çizimi
// engelleyenler + tahmini ilk boyama, ipuçları, sunucular, profiller, israf, önbellek, görsel / yazı tipi, güvenlik
// başlıkları, çerezler DEĞERSİZ, açıklı kütüphane bağlantıları, KVKK notu, SEO / erişilebilirlik denetimleri), tahmin /
// sezgisel rozetleri, "+N daha" ve "Tümünü göster", denetimden nesne listesine bulgu süzgeci, nesne ayrıntısının derin
// alanları, eski analizde "bu analizde yok — yeniden analiz edin" (asla "sorun yok").
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: { resourceScans: vi.fn(), resourceScan: vi.fn(), startResourceScan: vi.fn(), cancelResourceScan: vi.fn() },
  }),
}))
const copyMock = vi.hoisted(() => ({ copyText: vi.fn(async () => true) }))
vi.mock('../utils/copyText.js', () => copyMock)
const navMock = vi.hoisted(() => ({ applyTabView: vi.fn(), navigateTo: vi.fn() }))
vi.mock('../components/page/resourcescan/resourceScanExport.js', () => ({ exportResourceScanXlsx: vi.fn(), exportResourceScanPdf: vi.fn() }))
vi.mock('../utils/navigate.js', () => ({ ...navMock, default: navMock.navigateTo, APPLY_VIEW_EVENT: 'sm:apply-view' }))

import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import ResourceScanTab from '../components/page/resourcescan/ResourceScanTab.jsx'
import { resetWatchers } from '../components/page/resourcescan/resourceScanWatcher.js'
import { scanDetailV2, scanHistory } from './helpers/resourceScanFixtures.js'
import { CDN_JQ, scanDetailV3 } from './helpers/resourceScanFixturesV3.js'

const MONITOR = { id: 100, name: 'Kurumsal Ana Sayfa', url: 'https://www.example.com/' }
const ok = (data, extra = {}) => ({ success: true, data, ...extra })
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]
const tab = (name) => screen.getByRole('tab', { name })
const openTab = (name) => fireEvent.mouseDown(tab(name), { button: 0 })

/** Geçmiş: 12 ve 9 algoritma 3, 10 eski (algoritma 2). */
function history() {
  const h = scanHistory()
  return { ...h, items: h.items.map((s) => (s.status !== 'DONE' ? s : { ...s, algorithm_version: s.id === 10 ? 2 : 3 })) }
}
function detail(scanId) {
  if (scanId === 10) return ok(scanDetailV2(10, { scan: { grade: 64, grade_letter: 'D' } }))
  if (scanId === 11) return ok({ scan: scanHistory().items[1], analysis: null, items: null, items_kept: false })
  return ok(scanDetailV3(scanId))
}

beforeEach(() => {
  vi.clearAllMocks()
  resetWatchers()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  window.history.replaceState(null, '', '/?tab=page&monitor=100&mtab=resources')
  api.monitoring.resourceScans.mockResolvedValue(ok(history()))
  api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve(detail(scanId)))
})
afterEach(() => { resetWatchers() })

async function mountDone(props = {}) {
  render(<ResourceScanTab monitor={MONITOR} pollMs={15} {...props} />)
  await waitFor(() => expect(slot('prs-hero')).not.toBeNull())
}
const area = (a) => slots('prs-audit-tab').find((x) => x.getAttribute('data-area') === a)
async function openAudit(a) {
  openTab(/^Denetim/)
  await waitFor(() => expect(slot('prs-audit')).not.toBeNull())
  if (a) {
    fireEvent.mouseDown(area(a), { button: 0 })
    await waitFor(() => expect(slot('prs-audit').getAttribute('data-area')).toBe(a))
  }
}

describe('pano — kategori puanları', () => {
  it('dört puan; hesaplanamayan "—" soluk; kutucuk alanı açar, odak alan başlığında, URL güncellenir', async () => {
    await mountDone()
    const tiles = slots('prs-cat-score')
    expect(tiles.map((x) => [x.getAttribute('data-key'), x.getAttribute('data-tone')])).toEqual([
      ['performance', 'warning'], ['security', 'danger'], ['seo', 'success'], ['accessibility', 'muted']])
    expect(tiles[3].textContent).toMatch(/—/)
    expect(tiles[3].textContent).toMatch(/Hesaplanamadı/)
    expect(tiles[1].textContent).toMatch(/58/)
    expect(tiles[1].textContent).toMatch(/5 bulgu/)
    // kategori puanları önerilerden ÖNCE (ilk ekranda)
    expect(slot('prs-cat-scores').compareDocumentPosition(slot('prs-recs')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    fireEvent.click(within(tiles[1]).getByRole('button', { name: /Güvenlik: 58\/100 \(E\)/ }))
    await waitFor(() => expect(slot('prs-audit')?.getAttribute('data-area')).toBe('security'))
    await waitFor(() => expect(document.activeElement?.getAttribute('data-focus-target')).toBe('prs-audit'))
    await waitFor(() => expect(window.location.search).toMatch(/prs_v=audit-security/))
  })

  it('eski analiz: performans notu var, diğerleri "bu analizde yok"; denetim bölümü yeniden analiz önerir', async () => {
    await mountDone()
    fireEvent.click(screen.getByRole('tab', { name: /^Geçmiş/ }))
    openTab(/^Geçmiş/)
    await waitFor(() => expect(slot('prs-history-row')).not.toBeNull())
    fireEvent.click(slots('prs-history-row').find((r) => r.getAttribute('data-id') === '10'))
    await waitFor(() => expect(slot('prs-grade')?.getAttribute('data-letter')).toBe('D'))
    const tiles = slots('prs-cat-score')
    expect(tiles.slice(1).every((x) => x.getAttribute('data-available') === 'false')).toBe(true)
    expect(tiles[1].textContent).toMatch(/Bu analizde yok — yeniden analiz edin/)
    expect(slot('prs-cat-scores-old')).not.toBeNull()
    expect(slots('prs-rec-audit')).toHaveLength(0)            // eski analizde öneriden denetime bağlantı yok
    openTab(/^Denetim/)
    await waitFor(() => expect(slot('prs-audit-old')).not.toBeNull())
    expect(slot('prs-audit-old').textContent).toMatch(/Bu analizde ayrıntılı denetim yok/)
    expect(slot('prs-audit-restart')).not.toBeNull()
    expect(document.body.textContent).not.toMatch(/Sorun yok/)
  })

  it('panoda ilk 6 öneri (önem sırasıyla), gerisi "Tüm önerileri göster"; denetim alanı kendi önerilerini tam listeler', async () => {
    await mountDone()
    expect(slots('prs-rec', slot('prs-recs'))).toHaveLength(6)
    expect(slot('prs-recs-more').textContent).toMatch(/Tüm önerileri göster \(16\)/)
    fireEvent.click(slot('prs-recs-more'))
    expect(slots('prs-rec', slot('prs-recs'))).toHaveLength(16)
    expect(slot('prs-recs-more').getAttribute('aria-expanded')).toBe('true')
  })

  it('öneri satırındaki "Ayrıntılı denetim" ilgili alanı açar', async () => {
    await mountDone()
    const rec = slots('prs-rec').find((r) => r.getAttribute('data-code') === 'RENDER_BLOCKING_RESOURCES')
    expect(slot('prs-rec-audit', rec).getAttribute('data-area')).toBe('loading')
    fireEvent.click(slot('prs-rec-audit', rec))
    await waitFor(() => expect(slot('prs-audit')?.getAttribute('data-area')).toBe('loading'))
    expect(window.location.search === '' || !/prs_v=audit-loading/.test(window.location.search)).toBe(true)
  })
})

describe('ayrıntılı denetim — gezinme ve alanlar', () => {
  it('altı alan; durum rozeti bulgu sayısı / sorun yok; derin bağlantı `prs_v=audit-seo` SEO alanını açar', async () => {
    window.history.replaceState(null, '', '/?tab=page&monitor=100&mtab=resources&prs=12&prs_v=audit-seo')
    await (async () => {
      render(<ResourceScanTab monitor={MONITOR} pollMs={15} />)
      await waitFor(() => expect(slot('prs-audit')).not.toBeNull())
    })()
    expect(slot('prs-audit').getAttribute('data-area')).toBe('seo')
    expect(slots('prs-audit-tab').map((x) => [x.getAttribute('data-area'), x.getAttribute('data-state-summary')])).toEqual([
      ['loading', 'issues'], ['waste', 'issues'], ['media', 'issues'], ['security', 'issues'], ['seo', 'issues'], ['a11y', 'issues']])
    expect(area('seo').textContent).toMatch(/4 bulgu/)
    // SEO: başarısız önce; sorun kodu rozeti; "neden önemli" yalnız geçmeyen denetimde
    const checks = slots('prs-e-check')
    expect(checks[0].getAttribute('data-key')).toBe('meta_description')
    expect(slot('prs-e-check-issue', checks[0]).textContent).toBe('Yok')
    expect(checks.find((c) => c.getAttribute('data-key') === 'title').textContent).toMatch(/Kurumsal Ana Sayfa — Ürünler/)
    expect(checks.find((c) => c.getAttribute('data-key') === 'canonical').textContent).not.toMatch(/bildirir/)
    expect(slot('prs-e-seo-score-value').textContent).toMatch(/85/)
  })

  it('yükleme ve ağ: engelleyenler (+ tahmin rozeti), ipuçları, sunucular (vekil ölçülmez), ağ profilleri', async () => {
    await mountDone()
    await openAudit('loading')
    expect(slots('prs-a-rb-row')).toHaveLength(4)
    expect(slot('prs-a-rb').textContent).toMatch(/Tahmini ilk boyama/)
    expect(slot('prs-a-rb').querySelector('[data-slot="prs-deep-kind"][data-kind="estimate"]')).not.toBeNull()
    expect(slot('prs-a-rb').textContent).toMatch(/async \/ defer yok/)
    expect(slots('prs-a-hint').map((h) => h.getAttribute('data-state'))).toEqual(['missing', 'unused', 'unused', 'used', 'used', 'info'])
    const proxyHost = slots('prs-a-host').find((h) => h.getAttribute('data-host') === 'cok-uzun-bir-icerik-dagitim-agi-adresi-1.example.test')
    expect(proxyHost.textContent).toMatch(/Ölçülmedi: vekil sunucu üzerinden gidiliyor/)
    expect(slot('prs-a-hosts-summary').textContent).toMatch(/4 sunucuya bağlanılıyor/)
    expect(slots('prs-a-profile').map((p) => p.getAttribute('data-key'))).toEqual(['fiber', '4g', '3g', 'slow3g'])
    expect(slot('prs-a-profiles').querySelector('[data-kind="estimate"]')).not.toBeNull()
    // alanın önerileri başta (bu alana bağlı olanlar)
    expect(slots('prs-rec', slot('prs-audit-recs')).map((r) => r.getAttribute('data-code'))).toEqual(
      ['RENDER_BLOCKING_RESOURCES', 'THIRD_PARTY_HEAVY', 'MISSING_PRECONNECT'])
  })

  it('denetimden "Nesneleri göster" → bulgu süzgeciyle tam nesne listesi', async () => {
    await mountDone()
    await openAudit('loading')
    fireEvent.click(slot('prs-deep-show-objects', slot('prs-a-rb')))
    await waitFor(() => expect(slot('prs-objects')).not.toBeNull())
    await waitFor(() => expect(slots('prs-obj')).toHaveLength(3))
    expect(slots('prs-obj').every((o) => o.querySelector('[data-finding="RENDER_BLOCKING"]'))).toBe(true)
  })

  it('israf ve önbellek: yinelenen, çok sürümlü kütüphane, sezgisel küçültme, açık harita, HTML, önbellek kovaları', async () => {
    await mountDone()
    await openAudit('waste')
    expect(slots('prs-b-dup-row').map((r) => r.getAttribute('data-kind'))).toEqual(['same_content', 'same_path'])
    expect(slot('prs-b-lib-row').textContent).toMatch(/1\.12\.4/)
    expect(slot('prs-b-unmin').querySelector('[data-kind="heuristic"]')).not.toBeNull()
    expect(slots('prs-b-map-row').map((r) => r.getAttribute('data-exposed'))).toEqual(['true', 'false'])
    expect(slots('prs-deep-tile', slot('prs-b-html')).find((x) => x.getAttribute('data-key') === 'nodes').textContent).toMatch(/1\.850/)
    expect(slots('prs-b-cache-bucket').map((b) => [b.getAttribute('data-bucket'), b.getAttribute('data-count')])).toEqual([
      ['none', '2'], ['lt_1h', '32'], ['lt_1d', '0'], ['lt_7d', '2'], ['ge_7d', '3']])
    expect(slot('prs-b-cache').textContent).toMatch(/sayfa yükünün %77 kadarı yeniden iner/)
  })

  it('görsel ve yazı tipi: 60 satırlık listede ilk 8 + "Tümünü göster (50)" + "+10 satır daha"; GIF kare sayısı; WOFF2 olmayan', async () => {
    await mountDone()
    await openAudit('media')
    const md = slots('prs-c-img-group').find((g) => g.getAttribute('data-key') === 'missing_dimensions')
    expect(md.getAttribute('data-total')).toBe('60')
    expect(slots('prs-deep-url', md)).toHaveLength(8)
    expect(slot('prs-deep-capped', md).textContent).toMatch(/\+10 satır daha var/)
    fireEvent.click(slot('prs-deep-more', md))
    expect(slots('prs-deep-url', md)).toHaveLength(50)
    expect(slots('prs-c-img-group').find((g) => g.getAttribute('data-key') === 'not_lazy').querySelector('[data-kind="heuristic"]')).not.toBeNull()
    expect(slot('prs-c-img-animated_gif').textContent).toMatch(/48 kare/)
    expect(slot('prs-c-font-nonwoff2').textContent).toMatch(/Biçim: TTF/)
    expect(slot('prs-c-font-family').textContent).toMatch(/Inter Variable Display Çok Uzun Aile Adı Kurumsal Sürüm/)
  })

  it('güvenlik: başlıklar (teknik ad + sorun metni), çerez DEĞERİ yok, açık bağlantısı NVD, KVKK notu', async () => {
    await mountDone()
    await openAudit('security')
    expect(slot('prs-d-score-value').textContent).toMatch(/58/)
    const csp = slots('prs-d-header').find((h) => h.getAttribute('data-key') === 'csp')
    expect(csp.getAttribute('data-status')).toBe('weak')
    expect(csp.textContent).toMatch(/Content-Security-Policy/)
    expect(csp.textContent).toMatch(/'unsafe-inline' satır içi betiğe izin veriyor/)
    const xcto = slots('prs-d-header').find((h) => h.getAttribute('data-key') === 'x_content_type_options')
    expect(xcto.textContent).toMatch(/Yanıtta yok/)
    expect(xcto.textContent).toMatch(/nosniff/)
    const cookies = slots('prs-d-cookie')
    expect(cookies[0].textContent).toMatch(/_ga_cok_uzun_bir_izleme_cerezi_adi_ornek_X7Y8Z9/)
    expect(cookies[0].textContent).toMatch(/Secure yok/)
    expect(slot('prs-d-cookies-js').textContent).toMatch(/JavaScript'in yazdığı çerezler/)
    const link = slots('prs-d-vuln-link')[0]
    expect(link.getAttribute('href')).toMatch(/^https:\/\/nvd\.nist\.gov\/vuln\/detail\/CVE-/)
    expect(link.getAttribute('rel')).toBe('noopener noreferrer')
    expect(slot('prs-d-vuln-kind').textContent).toBe('Prototip kirletme')
    expect(slot('prs-d-libs-clean').textContent).toMatch(/jquery 3\.5\.1/)
    expect(slot('prs-d-kvkk').textContent).toMatch(/izin alınmadan çalışıyor olabilir \(sezgisel\)/)
    expect(slot('prs-d-kvkk').textContent).toMatch(/KVKK/)
    expect(slots('prs-d-tp-row').map((r) => r.getAttribute('data-tracking'))).toEqual(['false', 'true', 'true'])
  })

  it('erişilebilirlik: puan hesaplanamadı (soluk, "—"), örnekler, renk karşıtlığı notu', async () => {
    await mountDone()
    await openAudit('a11y')
    expect(slot('prs-e-a11y-score-value').getAttribute('data-tone')).toBe('muted')
    expect(slot('prs-e-a11y-score-value').textContent).toMatch(/—/)
    expect(slot('prs-e-a11y-score').textContent).toMatch(/Puan hesaplanamadı/)
    expect(slot('prs-e-contrast').textContent).toMatch(/Renk karşıtlığı/)
    const img = slots('prs-e-check').find((c) => c.getAttribute('data-key') === 'img_alt')
    expect(img.getAttribute('data-status')).toBe('fail')
    expect(img.textContent).toMatch(/7 öğe/)
    expect(img.textContent).toMatch(/img\[src=…\/urun-galeri-01\.jpg\]/)
    const ho = slots('prs-e-check').find((c) => c.getAttribute('data-key') === 'heading_order')
    expect(slot('prs-e-check-issue', ho).textContent).toBe('Düzey atlanmış')
  })

  it('bölüm üretilmemişse alan "bu analizde yok" (eski değil, ama bölüm eksik)', async () => {
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => {
      const d = scanDetailV3(scanId)
      delete d.analysis.waste
      return Promise.resolve(ok(d))
    })
    await mountDone()
    await openAudit('waste')
    expect(area('waste').getAttribute('data-state-summary')).toBe('missing')
    expect(slot('prs-area-missing')).not.toBeNull()
  })
})

describe('nesneler ve nesne ayrıntısı — derin alanlar', () => {
  it('yeni bulgu çipleri ve 3. taraf kategorisi rozeti; ayrıntıda HTTP sürümü, kütüphane, SRI, kategori', async () => {
    await mountDone()
    openTab(/^Nesneler/)
    await waitFor(() => expect(slot('prs-objects')).not.toBeNull())
    const chips = slot('prs-finding-chips')
    for (const c of ['VULNERABLE_LIBRARY', 'RENDER_BLOCKING', 'DUPLICATE', 'MISSING_SRI']) expect(chips.querySelector(`[data-finding="${c}"]`), c).not.toBeNull()
    fireEvent.change(slot('prs-search'), { target: { value: 'jquery/1.12.4' } })
    await waitFor(() => expect(slots('prs-obj')).toHaveLength(1))
    expect(slot('prs-obj').textContent).toMatch(/3\. taraf · CDN/)
    fireEvent.click(slot('prs-obj-open'))
    const od = await screen.findByRole('dialog', { name: /jquery\.min\.js/ })
    const deep = slot('prs-od-deep', od)
    expect(slot('prs-od-http', deep).textContent).toMatch(/HTTP\/2/)
    expect(slot('prs-od-library', deep).textContent).toMatch(/jquery 1\.12\.4/)
    expect(slot('prs-od-integrity', deep).textContent).toMatch(/Yok/)
    expect(slot('prs-od-tpc', deep).textContent).toMatch(/CDN/)
    expect(slot('prs-od-headers', od).textContent).toMatch(/CDN önbelleği/)
    // bulgu → öneri (DUPLICATE → DUPLICATE_RESOURCES eşlemesi)
    const dupRow = od.querySelector('[data-finding="DUPLICATE"]')
    expect(within(dupRow).getByRole('button', { name: /Öneriyi göster/ })).toBeTruthy()
    expect(CDN_JQ).toMatch(/jquery/)
  })

  it('eski analizin nesnesi: derin alanlar "bu analizde kaydedilmemiş" (değer uydurulmaz)', async () => {
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve(ok(scanDetailV2(scanId))))
    await mountDone()
    openTab(/^Nesneler/)
    await waitFor(() => expect(slot('prs-obj-open')).not.toBeNull())
    fireEvent.click(slot('prs-obj-open'))
    const od = await screen.findByRole('dialog')
    expect(slot('prs-od-deep', od).textContent).toMatch(/Bu analizde kaydedilmemiş/)
    expect(slot('prs-od-http', od)).toBeNull()
  })
})
