import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

// Kaynak analizi prs3 (fe2) — ekran davranışları: bölünmüş başlat düğmesi + "Siteyi analiz et" penceresi (eski sunucu
// scope'u bilmezse not), performans bütçesi bandı ve penceresi (doğrulama, yalnız değişen alan, sunucu alan hatası, salt
// okunur, eski sunucu, okunamadı), geçmiş / karşılaştırma işaretleri, eğilim görünümü (aralık, eski sunucu, okunamadı, boş),
// sayfalar görünümü (site analizi) ve dışa aktarıma giden bütçe / önceki analiz.
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
import { scanDetailV2, scanSummary } from './helpers/resourceScanFixtures.js'
import { budgetData, budgetEval, deepDetail, fgHistory, siteDetail, trendData } from './helpers/resourceScanFgFixtures.js'

const MONITOR = { id: 100, name: 'Kurumsal Ana Sayfa', url: 'https://www.example.com/', can_edit: true }
const ok = (data, extra = {}) => ({ success: true, data, ...extra })
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]
const openTab = (name) => fireEvent.mouseDown(screen.getByRole('tab', { name }), { button: 0 })

/** Ayrıntı: 30 site analizi (bütçe aşıldı), 12 sürüm 3 (bütçe içinde), diğerleri sürüm 2 (bütçe alanı YOK = eski pod). */
function detailOf(id) {
  if (id === 30) return ok(siteDetail(30))
  if (id === 12) return ok(deepDetail(12, { budget: { defined: true, exceeded: 0, checks: [{ key: 'max_requests', limit: 80, actual: 54, ok: true }] } }))
  return ok(scanDetailV2(id))
}

beforeEach(() => {
  vi.clearAllMocks()
  resetWatchers()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  window.history.replaceState(null, '', '/?tab=page&monitor=100&mtab=resources')
  api.monitoring.resourceScans.mockResolvedValue(ok(fgHistory()))
  api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve(detailOf(scanId)))
  api.monitoring.resourceBudget.mockResolvedValue(ok(budgetData({ max_page_kb: 2048, max_requests: 80, min_grade: 80, min_security_score: 70 })))
  api.monitoring.saveResourceBudget.mockImplementation((_id, body) => Promise.resolve(ok(budgetData({ max_page_kb: 2048, max_requests: 80, min_grade: 80, min_security_score: 70, ...body }))))
  api.monitoring.resourceScanTrend.mockResolvedValue(ok(trendData()))
})
afterEach(() => { resetWatchers() })

async function mount(props = {}) {
  render(<ResourceScanTab monitor={MONITOR} pollMs={15} {...props} />)
  await waitFor(() => expect(slot('prs-hero')).not.toBeNull())
}

describe('başlatma menüsü (G)', () => {
  it('ana düğme bugünkü tek sayfa analizi (gövdesiz); menüden "Bu sayfayı analiz et" de gövdesiz', async () => {
    api.monitoring.startResourceScan.mockResolvedValue(ok(scanSummary(50, { status: 'RUNNING', progress: { done: 0, total: 0 }, grade: null })))
    await mount()
    fireEvent.click(slot('prs-start'))
    await waitFor(() => expect(api.monitoring.startResourceScan).toHaveBeenCalledWith(100))
    expect(api.monitoring.startResourceScan.mock.calls[0]).toHaveLength(1)
  })

  it('"Siteyi analiz et" → pencere (2 … sunucu sınırı) → gövde { scope: site, max_pages }; koşu kartında "Site · N sayfa"', async () => {
    const run = scanSummary(51, { status: 'RUNNING', scope: 'site', pages_count: 7,
      progress: { done: 3, total: 40, pages_done: 2, pages_total: 7 }, grade: null, finished_at: null })
    api.monitoring.startResourceScan.mockResolvedValue(ok(run))
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve(scanId === 51
      ? ok({ scan: run, analysis: null, items: null, items_kept: true }) : detailOf(scanId)))
    await mount()
    pressMenuTrigger(slot('prs-start-more'))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Siteyi analiz et \(en çok 10 sayfa\)/ }))
    const dlg = await waitFor(() => { const d = slot('prs-site-dialog'); expect(d).not.toBeNull(); return d })
    const select = within(dlg).getByRole('combobox', { name: 'Sayfa sayısı' })
    expect([...select.querySelectorAll('option')].map((o) => o.value)).toEqual(['2', '3', '4', '5', '6', '7', '8', '9', '10'])
    expect(select.value).toBe('5')
    expect(slot('prs-site-facts').textContent).toMatch(/15 dk 00 sn/)
    fireEvent.change(select, { target: { value: '7' } })
    fireEvent.click(screen.getByRole('button', { name: '7 sayfayı analiz et' }))
    await waitFor(() => expect(api.monitoring.startResourceScan).toHaveBeenCalledWith(100, { scope: 'site', max_pages: 7 }))
    await waitFor(() => expect(slot('prs-run-site')).not.toBeNull())
    expect(slot('prs-run-site').textContent).toBe('Site · sayfa 2 / 7')
    expect(slot('prs-site-ignored')).toBeNull()
    await waitFor(() => expect(slot('prs-site-dialog')).toBeNull())
  })

  it('eski sunucu scope bilmiyor (yanıtta scope yok) → not: yalnız bu sayfa analiz ediliyor', async () => {
    api.monitoring.startResourceScan.mockResolvedValue(ok(scanSummary(52, { status: 'RUNNING', progress: { done: 0, total: 0 }, grade: null, finished_at: null })))
    await mount()
    pressMenuTrigger(slot('prs-start-more'))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Siteyi analiz et/ }))
    fireEvent.click(await screen.findByRole('button', { name: '5 sayfayı analiz et' }))
    await waitFor(() => expect(slot('prs-site-ignored')).not.toBeNull())
    expect(slot('prs-site-ignored').textContent).toMatch(/çok sayfalı analizi bilmiyor/)
    expect(slot('prs-run-site')).toBeNull()
  })

  it('başlatma hatası: pencere açık kalır (yeniden denenebilir), hata tostu', async () => {
    api.monitoring.startResourceScan.mockResolvedValue({ success: false, status: 429, code: 'RATE_LIMITED', error: 'Dakikada en çok 3 analiz başlatabilirsiniz.' })
    await mount()
    pressMenuTrigger(slot('prs-start-more'))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Siteyi analiz et/ }))
    fireEvent.click(await screen.findByRole('button', { name: '5 sayfayı analiz et' }))
    expect(await screen.findByText('Dakikada en çok 3 analiz başlatabilirsiniz.')).toBeInTheDocument()
    expect(slot('prs-site-dialog')).not.toBeNull()
  })
})

describe('performans bütçesi (F1)', () => {
  it('bant: aşılan kalemler sınır ↔ ölçülen; ölçülemeyen "Ölçülemedi" (yeşil değil); eski analizde bant yok', async () => {
    await mount()
    const band = slot('prs-budget-band')
    expect(band.getAttribute('data-state')).toBe('exceeded')
    expect(slot('prs-budget-band-status').textContent).toBe('Bütçe aşıldı: 2 kalem')
    const rows = slots('prs-budget-row')
    expect(rows.map((r) => r.getAttribute('data-ok'))).toEqual(['exceeded', 'ok', 'exceeded', 'unknown'])
    expect(rows[0].textContent).toMatch(/Sınır ≤ 2 MB · ölçülen 3,4 MB/)
    expect(rows[3].textContent).toMatch(/Ölçülemedi/)
    expect(slot('prs-budget-row-state', rows[3]).getAttribute('data-tone')).toBe('muted')
    // eski analiz (budget alanı yok) → bant çizilmez
    openTab(/^Geçmiş/)
    fireEvent.click(document.querySelector('[data-slot="prs-history-row"][data-id="9"]'))
    await waitFor(() => expect(slot('prs-hero')).not.toBeNull())
    await waitFor(() => expect(slot('prs-budget-band')).toBeNull())
  })

  it('pencere: alan hatası alanın altında, istek gitmez; düzeltince YALNIZ değişen alan gider; kayıt sonrası ayrıntı ve geçmiş yeniden okunur', async () => {
    await mount()
    fireEvent.click(slot('prs-budget-open'))
    const dlg = await waitFor(() => { const d = slot('prs-budget-dialog'); expect(d?.getAttribute('data-state')).toBe('ok'); return d })
    expect(dlg.getAttribute('data-editable')).toBe('true')
    const input = within(dlg).getByRole('textbox', { name: 'Sayfa boyutu (en çok)' })
    expect(input.value).toBe('2048')
    expect(screen.getByRole('button', { name: /Kaydet/ })).toBeDisabled()
    fireEvent.change(input, { target: { value: '10' } })
    fireEvent.click(screen.getByRole('button', { name: /Kaydet/ }))
    expect(await screen.findByText(/Değer 50 ile 51\.200 KB arasında olmalı/)).toBeInTheDocument()
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(api.monitoring.saveResourceBudget).not.toHaveBeenCalled()
    fireEvent.change(input, { target: { value: '3000' } })
    const reads = api.monitoring.resourceScans.mock.calls.length
    fireEvent.click(screen.getByRole('button', { name: /Kaydet/ }))
    await waitFor(() => expect(api.monitoring.saveResourceBudget).toHaveBeenCalledWith(100, { max_page_kb: 3000 }))
    expect(await screen.findByText('Performans bütçesi kaydedildi.')).toBeInTheDocument()
    await waitFor(() => expect(slot('prs-budget-dialog')).toBeNull())
    await waitFor(() => expect(api.monitoring.resourceScans.mock.calls.length).toBeGreaterThan(reads))
    // seçili analiz önbellekten değil yeniden okunur (güncel bütçeyle değerlendirme)
    expect(api.monitoring.resourceScan.mock.calls.filter(([, id]) => id === 30).length).toBeGreaterThanOrEqual(2)
  })

  it('sunucu alan hatası (400 + field) alanın altında; sınırı boşaltmak null gönderir', async () => {
    api.monitoring.saveResourceBudget.mockResolvedValueOnce({ success: false, status: 400, code: 'VALIDATION_FAILED', field: 'max_js_kb', error: 'JS sınırı 10 ile 20480 KB arasında olmalı.' })
    await mount()
    fireEvent.click(slot('prs-budget-open'))
    const dlg = await waitFor(() => { const d = slot('prs-budget-dialog'); expect(d?.getAttribute('data-state')).toBe('ok'); return d })
    fireEvent.change(within(dlg).getByRole('textbox', { name: 'JavaScript boyutu (en çok)' }), { target: { value: '500' } })
    fireEvent.change(within(dlg).getByRole('textbox', { name: 'Sayfa boyutu (en çok)' }), { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: /Kaydet/ }))
    await waitFor(() => expect(api.monitoring.saveResourceBudget).toHaveBeenCalledWith(100, { max_page_kb: null, max_js_kb: 500 }))
    expect(await screen.findByText('JS sınırı 10 ile 20480 KB arasında olmalı.')).toBeInTheDocument()
    expect(within(dlg).getByRole('textbox', { name: 'JavaScript boyutu (en çok)' })).toHaveAttribute('aria-invalid', 'true')
  })

  it('salt okunur: alanlar kilitli, kaydet yok, açıklama şeridi', async () => {
    api.monitoring.resourceBudget.mockResolvedValue(ok(budgetData({ max_page_kb: 2048 }, false)))
    render(<ResourceScanTab monitor={{ ...MONITOR, can_edit: false }} pollMs={15} />)
    await waitFor(() => expect(slot('prs-hero')).not.toBeNull())
    expect(slot('prs-budget-open').textContent).toMatch(/Bütçeyi gör/)
    fireEvent.click(slot('prs-budget-open'))
    const dlg = await waitFor(() => { const d = slot('prs-budget-dialog'); expect(d?.getAttribute('data-state')).toBe('ok'); return d })
    expect(dlg.getAttribute('data-editable')).toBe('false')
    expect(slot('prs-budget-readonly')).not.toBeNull()
    for (const inp of within(dlg).getAllByRole('textbox')) expect(inp).toBeDisabled()
    expect(screen.queryByRole('button', { name: /Kaydet/ })).toBeNull()
  })

  it('eski sunucu (404) → "desteklemiyor"; okunamadı (500) → hata + yeniden dene', async () => {
    api.monitoring.resourceBudget.mockResolvedValueOnce({ success: false, status: 404, code: 'NOT_FOUND', error: 'Bulunamadı' })
    await mount()
    fireEvent.click(slot('prs-budget-open'))
    await waitFor(() => expect(slot('prs-budget-unsupported')).not.toBeNull())
    expect(slot('prs-budget-dialog').textContent).toMatch(/Sunucu bütçeyi desteklemiyor/)
    fireEvent.click(screen.getAllByRole('button', { name: 'Kapat' }).at(-1))
    await waitFor(() => expect(slot('prs-budget-dialog')).toBeNull())
    api.monitoring.resourceBudget.mockResolvedValueOnce({ success: false, status: 500, error: 'Sunucu yanıt vermedi.' })
    fireEvent.click(slot('prs-budget-open'))
    await waitFor(() => expect(slot('prs-budget-error')).not.toBeNull())
    fireEvent.click(within(slot('prs-budget-error')).getByRole('button', { name: /Tekrar dene|Yeniden dene/ }))
    await waitFor(() => expect(slot('prs-budget-dialog').getAttribute('data-state')).toBe('ok'))
  })

  it('bütçe tanımsız ve düzenleme izni yok → bant söyler, düğme yok', async () => {
    api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve(scanId === 30
      ? ok({ ...siteDetail(30), budget: { defined: false, checks: [], exceeded: 0 } }) : detailOf(scanId)))
    render(<ResourceScanTab monitor={{ ...MONITOR, can_edit: false }} pollMs={15} />)
    await waitFor(() => expect(slot('prs-budget-band')).not.toBeNull())
    expect(slot('prs-budget-band').getAttribute('data-state')).toBe('none')
    expect(slot('prs-budget-open')).toBeNull()
    expect(slot('prs-budget-band').textContent).toMatch(/bütçesi tanımlanmamış/)
  })
})

describe('geçmiş ve karşılaştırma işaretleri', () => {
  it('geçmiş satırı: "Site · 5 sayfa", bütçe aşıldı (2) / içinde / bilinmiyor; bütçesiz satırda işaret yok', async () => {
    await mount()
    openTab(/^Geçmiş/)
    const row = (id) => document.querySelector(`[data-slot="prs-history-row"][data-id="${id}"]`)
    await waitFor(() => expect(row(30)).not.toBeNull())
    expect(slot('prs-history-scope', row(30)).textContent).toBe('Site · 5 sayfa')
    expect(slot('prs-budget-status', row(30)).textContent).toBe('Bütçe aşıldı (2)')
    expect(slot('prs-budget-status', row(12)).getAttribute('data-status')).toBe('ok')
    expect(slot('prs-budget-status', row(10)).textContent).toBe('Bütçe: bilinmiyor')
    expect(slot('prs-budget-status', row(10)).getAttribute('data-tone')).toBe('muted')
    expect(slot('prs-budget-status', row(9))).toBeNull()
    expect(row(30).getAttribute('aria-label')).toMatch(/Site · 5 sayfa/)
  })

  it('karşılaştırma: iki tarafın bütçe durumu, site işareti ve kategori puanları', async () => {
    await mount()
    openTab(/^Geçmiş/)
    fireEvent.click(within(slot('prs-history-mode')).getByRole('button', { name: /Karşılaştır/ }))
    await waitFor(() => expect(slot('prs-cmp-body')).not.toBeNull())
    const m = slot('prs-cmp-markers')
    expect(slots('prs-budget-status', m).map((b) => b.getAttribute('data-status'))).toEqual(['ok', 'exceeded'])
    expect(slot('prs-cmp-site', m).textContent).toBe('Site · 5 sayfa')
    expect(slot('prs-cmp-scores', m).textContent).toMatch(/Güvenlik.*58/)
  })
})

describe('eğilim görünümü (F2)', () => {
  it('sekme açılınca 90 gün okunur; 4 grafik; puan serisi eski analizlerde boşluk; algoritma notu; büyüyen nesneler; aralık değişimi', async () => {
    await mount()
    openTab(/^Eğilim/)
    await waitFor(() => expect(api.monitoring.resourceScanTrend).toHaveBeenCalledWith(100, 90))
    await waitFor(() => expect(slots('prs-tv-chart')).toHaveLength(4))
    expect(slots('prs-tv-chart').map((c) => [c.getAttribute('data-chart'), c.getAttribute('data-known')]))
      .toEqual([['grade', 'true'], ['load', 'true'], ['bytes', 'true'], ['scores', 'true']])
    expect(slot('prs-tv-algo-note').textContent).toMatch(/v2 → v3/)
    expect(slot('prs-tv-stack-missing').textContent).toMatch(/2 eski analizde kategori ayrımı yok/)
    expect(slots('prs-tv-growth-row')).toHaveLength(2)
    expect(slots('prs-tv-growth-row')[0].textContent).toMatch(/%100/)
    // bütçe bir kez okunur (bütçe çizgileri)
    await waitFor(() => expect(api.monitoring.resourceBudget).toHaveBeenCalledTimes(1))
    fireEvent.click(within(slot('prs-tv-days')).getByRole('button', { name: '30 gün' }))
    await waitFor(() => expect(api.monitoring.resourceScanTrend).toHaveBeenLastCalledWith(100, 30))
    await waitFor(() => expect(window.location.search).toMatch(/prs_v=trend/))
  })

  it('aralıkta veri yok → "1 yıla genişlet"; hiçbir noktada puan yok → kartta "bu veri yok" (sıfır değil)', async () => {
    api.monitoring.resourceScanTrend.mockResolvedValueOnce(ok({ points: [], growth: [] }))
    api.monitoring.resourceScanTrend.mockResolvedValueOnce(ok({ points: trendData().points.slice(0, 2), growth: [] }))
    await mount()
    openTab(/^Eğilim/)
    await waitFor(() => expect(slot('prs-tv-empty')).not.toBeNull())
    fireEvent.click(slot('prs-tv-widen'))
    await waitFor(() => expect(api.monitoring.resourceScanTrend).toHaveBeenLastCalledWith(100, 365))
    await waitFor(() => expect(slots('prs-tv-chart')).toHaveLength(4))
    const scores = document.querySelector('[data-slot="prs-tv-chart"][data-chart="scores"]')
    expect(scores.getAttribute('data-known')).toBe('false')
    expect(slot('prs-tv-chart-empty', scores).textContent).toMatch(/bu veri yok/)
    expect(slot('prs-tv-growth-empty')).not.toBeNull()
  })

  it('eski sunucu (405) → bilgi şeridi + geçmişten yüklenme grafiği; okunamadı → hata + yeniden dene', async () => {
    api.monitoring.resourceScanTrend.mockResolvedValueOnce({ success: false, status: 405 })
    await mount()
    openTab(/^Eğilim/)
    await waitFor(() => expect(slot('prs-tv-unsupported')).not.toBeNull())
    await waitFor(() => expect(slot('prs-trend')).not.toBeNull())
    // aralık değiştirilemez (eski sunucu); yeniden kurulumda hata yolu
    api.monitoring.resourceScanTrend.mockResolvedValue({ success: false, status: 500, error: 'Sunucu yanıt vermedi.' })
  })

  it('okunamadı → hata bloğu, yeniden dene ile okunur', async () => {
    api.monitoring.resourceScanTrend.mockResolvedValueOnce({ success: false, status: 500, error: 'Sunucu yanıt vermedi.' })
    await mount()
    openTab(/^Eğilim/)
    await waitFor(() => expect(slot('prs-tv-error')).not.toBeNull())
    expect(slot('prs-tv-error').textContent).toMatch(/Eğilim okunamadı/)
    fireEvent.click(within(slot('prs-tv-error')).getByRole('button', { name: /Tekrar dene|Yeniden dene/ }))
    await waitFor(() => expect(slots('prs-tv-chart')).toHaveLength(4))
  })
})

describe('sayfalar görünümü (G)', () => {
  it('site analizinde "Sayfalar" sekmesi: toplamlar, en kötü sayfalar, tablo, açılamayan / taranmayan sayfa, ortak kaynaklar', async () => {
    await mount()
    expect(slot('prs-sec-pages')).not.toBeNull()
    // 5 bölüm → telefonda 3 + 2 (iki satır; 5 eşit sütunda adlar 360 px'te sığmıyordu)
    const list = document.querySelector('[data-slot="prs-sections"] [role="tablist"]')
    expect(list.getAttribute('data-count')).toBe('5')
    expect(list.getAttribute('data-cols')).toBe('3')
    openTab(/^Sayfalar/)
    await waitFor(() => expect(slot('prs-site')).not.toBeNull())
    expect(slot('prs-site').getAttribute('data-state')).toBe('ok')
    const tile = (k) => document.querySelector(`[data-slot="prs-site-tile"][data-key="${k}"]`)
    expect(tile('scanned').textContent).toMatch(/3 \/ 5/)
    expect(tile('avg').textContent).toMatch(/67/)
    expect(slots('prs-site-worst-row')).toHaveLength(2)
    expect(slot('prs-site-list').getAttribute('data-layout')).toBe('table')
    const pages = slots('prs-site-page')
    expect(pages.map((p) => p.getAttribute('data-status'))).toEqual(['ok', 'ok', 'ok', 'failed', 'skipped'])
    expect(pages[0].textContent).toMatch(/48/)
    expect(slot('prs-site-error').textContent).toBe('Sayfa HTTP 503 durum kodu döndürdü.')
    expect(pages[0].textContent).toMatch(/Ağır sayfa/)
    expect(pages[0].textContent).toMatch(/Diğer öneri/)
    expect(pages[0].textContent).not.toMatch(/BILINMEYEN_KOD/)
    // puanı hesaplanamayan erişilebilirlik "—" (0 değil)
    const main = pages.find((p) => p.getAttribute('data-main') === 'true')
    expect(main.querySelector('[data-score="a11y"]').textContent).toMatch(/—/)
    expect(slots('prs-site-shared-row')).toHaveLength(3)
    fireEvent.change(slot('prs-site-sort'), { target: { value: 'load_desc' } })
    expect(slots('prs-site-page')[0].textContent).toMatch(/6,1 sn|6,10 sn/)
    await waitFor(() => expect(window.location.search).toMatch(/prs_v=pages/))
  })

  it('tek sayfa analizi seçilince "Sayfalar" sekmesi yok ve görünüm panoya düşer', async () => {
    await mount()
    openTab(/^Sayfalar/)
    await waitFor(() => expect(slot('prs-site')).not.toBeNull())
    openTab(/^Geçmiş/)
    fireEvent.click(document.querySelector('[data-slot="prs-history-row"][data-id="12"]'))
    await waitFor(() => expect(slot('prs-hero')).not.toBeNull())
    expect(slot('prs-sec-pages')).toBeNull()
    expect(document.querySelector('[data-slot="prs-sections"] [role="tablist"]').getAttribute('data-count')).toBe('4')
    expect(document.querySelector('[data-slot="prs-sections"] [role="tablist"]').getAttribute('data-cols')).toBe('4')
  })

  it('paylaşılan bağlantı prs_v=pages → sayfalar görünümü açılır', async () => {
    window.history.replaceState(null, '', '/?tab=page&monitor=100&mtab=resources&prs=30&prs_v=pages')
    render(<ResourceScanTab monitor={MONITOR} pollMs={15} />)
    await waitFor(() => expect(slot('prs-site')).not.toBeNull())
    expect(screen.getByRole('tab', { name: /^Sayfalar/ })).toHaveAttribute('aria-selected', 'true')
  })
})

describe('dışa aktarım (F3)', () => {
  it('PDF ve Excel: bütçe değerlendirmesi ve önceki tamamlanmış analiz modüle gider', async () => {
    await mount()
    fireEvent.click(slot('prs-export-pdf'))
    await waitFor(() => expect(exportMock.exportResourceScanPdf).toHaveBeenCalled())
    const args = exportMock.exportResourceScanPdf.mock.calls[0][0]
    expect(args.budget).toEqual(budgetEval())
    expect(args.prev?.id).toBe(12)
    expect(args.analysis.site.pages).toHaveLength(5)
    fireEvent.click(slot('prs-export-xlsx'))
    await waitFor(() => expect(exportMock.exportResourceScanXlsx).toHaveBeenCalled())
    expect(exportMock.exportResourceScanXlsx.mock.calls[0][0].prev?.id).toBe(12)
  })
})
