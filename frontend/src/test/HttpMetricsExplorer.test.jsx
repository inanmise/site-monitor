import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import HttpMetricsExplorer from '../components/admin/HttpMetricsExplorer.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))

vi.mock('../api/client', () => ({
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getHttpMetricsOverview:  vi.fn(),
      getHttpMetricsSeries:    vi.fn(),
      getGeneralSettings:      vi.fn(),
      saveGeneralSettings:     vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

/**
 * İstek Gezgini (2026-09-28 yeniden tasarım). Fixture'lar GERÇEK tel biçiminde (`/http-metrics/overview`, snake_case).
 * Sorgular rol / erişilebilir ad / `data-slot` / `data-kpi` ile; metinler test sağlayıcısının İngilizcesi.
 *
 * Kapsanan sözleşmeler: tek çağrılı veri + özet tonları · yöntem/durum süzgeçleri + çipler · telefonda süzgeç Sheet'i ·
 * uç tablosu (sıralama, arama, sayfalama) ve kart düzeni · ayrıntı paneli (kendi serisi + durum kodları + "bu uca süz")
 * · CSV (görünen süzülmüş liste) · yenilemede durum korunur · boş / hata / bayat-hata · saklama ayarı (eski sözleşme).
 */
const pad = (n) => String(n).padStart(2, '0')
const EP = (endpoint, extra = {}) => ({
  endpoint, method: endpoint.split(' ')[0], path: endpoint.split(' ')[1],
  count: 100, errors: 0, error_rate_pct: 0, avg_ms: 40, max_ms: 200, p50_ms: 30, p95_ms: 90, p99_ms: 150,
  status_2xx: 100, status_3xx: 0, status_4xx: 0, status_5xx: 0, status_other: 0, unclassified: 0,
  last_seen: '2026-09-28T09:04:00', ...extra,
})
const ENDPOINTS = [
  ...Array.from({ length: 12 }, (_, i) => EP(`GET /api/e${pad(i)}`, { count: 120 - i, status_2xx: 120 - i, p95_ms: 50 + i })),
  EP('POST /api/auth/login', { count: 118, errors: 13, error_rate_pct: 32.5, status_2xx: 105, status_4xx: 10, status_5xx: 3, p95_ms: 400 }),
  EP('DELETE /api/x/{id}', { count: 5, p95_ms: 3500, avg_ms: 1200, max_ms: 4100, status_2xx: 5 }),
]
const POINTS = Array.from({ length: 5 }, (_, i) => ({
  ts: `2026-09-28T12:0${i}:00`, t: Date.UTC(2026, 8, 28, 9, i), count: 300, errors: 3, avg_ms: 60, p50_ms: 40, p95_ms: 1500, p99_ms: 2500,
  max_ms: 4100, status_2xx: 290, status_3xx: 0, status_4xx: 7, status_5xx: 3, status_other: 0, unclassified: 0,
}))
const overview = (extra = {}) => ({
  success: true,
  data: {
    from: '2026-09-28T08:00:00', to: '2026-09-28T09:00:00', clamped: false, capped: false, granularity: 'minute',
    summary: { total: 1500, errors: 92, error_rate_pct: 6.1, avg_ms: 60, max_ms: 4100, min_ms: 2, p50_ms: 40, p95_ms: 1500,
      p99_ms: 2500, req_per_min: 25, status_2xx: 1408, status_3xx: 0, status_4xx: 70, status_5xx: 22, status_other: 0, unclassified: 0 },
    status_codes: [{ code: 200, count: 1400 }, { code: 401, count: 70 }, { code: 500, count: 22 }, { code: 204, count: 8 }],
    data: POINTS, endpoints: ENDPOINTS, endpoints_total: ENDPOINTS.length, endpoints_truncated: false,
    ...extra,
  },
})
const series = {
  success: true,
  data: { granularity: 'minute', data: POINTS, capped: false,
    summary: { total: 40, errors: 13, error_rate_pct: 32.5, avg_ms: 40, max_ms: 200, p95_ms: 400, status_2xx: 27, status_4xx: 10, status_5xx: 3 },
    status_codes: [{ code: 200, count: 27 }, { code: 401, count: 10 }, { code: 503, count: 3 }] },
}
const settings = (value = '7', extra = {}) => ({
  success: true,
  data: { settings: [{ key: 'site.monitor.metrics.http.retention-days', value, default: '7', ...extra }] },
})

const tile = (id) => document.querySelector(`[data-slot="hreq-tile"][data-kpi="${id}"]`)
const lastOverview = () => api.admin.getHttpMetricsOverview.mock.calls.at(-1)[0]
// hidden: açık bir Sheet (Radix modal) arka planı aria-hidden yapar; kartlar yine DOM'da ve sayılmalı
const cardNames = () => screen.queryAllByRole('button', { name: /^Details: /, hidden: true }).map((b) => b.getAttribute('aria-label').replace('Details: ', ''))
async function renderLoaded(props) {
  const r = render(<HttpMetricsExplorer {...props} />)
  await waitFor(() => expect(tile('total')).not.toBeNull())
  return r
}

const realMatchMedia = window.matchMedia
function wideScreen() {
  window.matchMedia = (q) => ({ ...realMatchMedia(q), matches: /min-width:\s*1024px/.test(q) })
}

beforeEach(() => {
  vi.clearAllMocks()
  mobile.on = false
  window.matchMedia = realMatchMedia
  try { localStorage.clear() } catch { /* yoksay */ }
  api.admin.getHttpMetricsOverview.mockResolvedValue(overview())
  api.admin.getHttpMetricsSeries.mockResolvedValue(series)
  api.admin.getGeneralSettings.mockResolvedValue(settings())
  api.admin.saveGeneralSettings.mockResolvedValue({ success: true })
})
afterEach(() => { window.matchMedia = realMatchMedia })

describe('HttpMetricsExplorer — veri + özet', () => {
  it('açılışta TEK çağrı (overview, son 1 saat, UTC ISO); özet kutucukları ve tonları', async () => {
    await renderLoaded()
    expect(api.admin.getHttpMetricsOverview).toHaveBeenCalledTimes(1)
    const { from, to, endpoint, methods } = lastOverview()
    expect(from).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
    expect((Date.parse(to + 'Z') - Date.parse(from + 'Z')) / 60000).toBe(60)
    expect(endpoint).toBeUndefined()
    expect(methods).toEqual([])
    expect(tile('total').textContent).toContain('1,500')
    expect(tile('total').textContent).toContain('avg 25/min')
    expect(tile('errors')).toHaveAttribute('data-tone', 'crit')      // %6,1
    expect(tile('errors').textContent).toContain('92 errors · 22 5xx')
    expect(tile('p95')).toHaveAttribute('data-tone', 'warn')          // 1,5 s
    expect(tile('endpoints').textContent).toContain('14')
    expect(tile('endpoints').textContent).toContain('1 with errors')
    // ana grafik (iki küçük katlı panel) + durum kodları
    expect(document.querySelector('[data-slot="hreq-chart-volume"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="hreq-chart-latency"]')).not.toBeNull()
    expect([...document.querySelectorAll('[data-slot="hreq-code-list"] [data-code]')].map((li) => li.getAttribute('data-code')))
      .toEqual(['200', '401', '500', '204'])
  })

  it('seri aç/kapa: ToggleGroup düğmesi sınıfı gizler (aria-pressed)', async () => {
    await renderLoaded()
    const group = screen.getByRole('toolbar', { name: 'Series' })   // Radix ToggleGroup (gezici odak) = toolbar
    const btn = within(group).getByRole('button', { name: /4xx/ })
    expect(btn).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(btn)
    expect(btn).toHaveAttribute('aria-pressed', 'false')
  })
})

describe('HttpMetricsExplorer — süzgeçler', () => {
  it('yöntem faseti yeniden ister (methods), çip çıkar; çip kaldırılınca süzgeç düşer; "Clear all"', async () => {
    await renderLoaded()
    fireEvent.click(screen.getByRole('button', { name: 'Method' }))
    fireEvent.click(screen.getByRole('option', { name: /POST/ }))
    await waitFor(() => expect(lastOverview().methods).toEqual(['POST']))
    const chips = screen.getByRole('group', { name: 'Active filters' })
    fireEvent.click(within(chips).getByRole('button', { name: 'Remove filter: POST' }))
    await waitFor(() => expect(lastOverview().methods).toEqual([]))
    expect(screen.queryByRole('group', { name: 'Active filters' })).toBeNull()
  })

  it('durum sınıfı süzgeci İSTEMCİDE: tablo yalnız o sınıfta isteği olan uçlar, not görünür, yeniden istek yok', async () => {
    await renderLoaded()
    const calls = api.admin.getHttpMetricsOverview.mock.calls.length
    fireEvent.click(screen.getByRole('button', { name: 'Status' }))
    fireEvent.click(screen.getByRole('option', { name: '5xx server error' }))
    await waitFor(() => expect(cardNames()).toEqual(['POST /api/auth/login']))
    expect(screen.getAllByText(/The status filter applies to request counts/).length).toBeGreaterThan(0)
    expect(tile('total').textContent).toContain('22')                // seçili sınıfın istekleri
    expect(api.admin.getHttpMetricsOverview.mock.calls.length).toBe(calls)
  })

  it('TELEFON: uç/yöntem/durum "Filters" Sheet\'inde; seçim sayısı düğmede, "Show results (N)"', async () => {
    mobile.on = true
    await renderLoaded()
    expect(screen.queryByRole('button', { name: 'Method' })).toBeNull()   // satır içi faset yok
    fireEvent.click(screen.getByRole('button', { name: 'Filters' }))
    const sheet = await screen.findByRole('dialog', { name: 'Filters' })
    fireEvent.click(within(sheet).getByRole('button', { name: 'POST' }))
    await waitFor(() => expect(lastOverview().methods).toEqual(['POST']))
    expect(within(sheet).getByRole('button', { name: 'Show results (1)' })).toBeInTheDocument()
    fireEvent.click(within(sheet).getByRole('button', { name: 'Show results (1)' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Filters' })).toBeNull())
    expect(screen.getByRole('button', { name: 'Filters (1)' })).toBeInTheDocument()
  })
})

describe('HttpMetricsExplorer — uç listesi', () => {
  it('dar ekranda KART: yöntem + yol, sayfalama (pencere ön ayarı 10) ve arama', async () => {
    await renderLoaded()
    expect(document.querySelector('[data-slot="hreq-endpoint-cards"]')).not.toBeNull()
    expect(screen.queryByRole('table')).toBeNull()
    expect(cardNames()).toHaveLength(10)
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(cardNames()).toHaveLength(4))
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search paths…' }), { target: { value: 'E05' } })
    await waitFor(() => expect(cardNames()).toEqual(['GET /api/e05']))
  })

  it('geniş ekranda TABLO: başlık tıklaması sıralar (aria-sort), ikinci tık yönü çevirir', async () => {
    wideScreen()
    await renderLoaded()
    const table = screen.getByRole('table')
    const p95Head = within(table).getByRole('columnheader', { name: 'p95' })
    expect(within(table).getByRole('columnheader', { name: /Requests/ })).toHaveAttribute('aria-sort', 'descending')
    fireEvent.click(within(p95Head).getByRole('button'))
    await waitFor(() => expect(p95Head).toHaveAttribute('aria-sort', 'descending'))
    const firstRow = () => within(table).getAllByRole('row')[1]
    expect(firstRow()).toHaveAttribute('data-endpoint', 'DELETE /api/x/{id}')
    // ton: 3,5 sn p95 kritik — renk + gizli metin
    expect(within(firstRow()).getByText('(critical)')).toBeInTheDocument()
    fireEvent.click(within(p95Head).getByRole('button'))
    await waitFor(() => expect(p95Head).toHaveAttribute('aria-sort', 'ascending'))
    expect(firstRow()).toHaveAttribute('data-endpoint', 'GET /api/e00')
  })

  it('CSV = görünen (süzülmüş + sıralı) listenin TÜM satırları; BOM + başlık', async () => {
    const blobs = []
    const origCreate = URL.createObjectURL
    const origRevoke = URL.revokeObjectURL
    URL.createObjectURL = vi.fn((b) => { blobs.push(b); return 'blob:x' })
    URL.revokeObjectURL = vi.fn()
    try {
      await renderLoaded()
      fireEvent.click(screen.getByRole('button', { name: 'Method' }))
      fireEvent.click(screen.getByRole('option', { name: /DELETE/ }))
      await waitFor(() => expect(cardNames()).toEqual(['DELETE /api/x/{id}']))
      fireEvent.click(screen.getByRole('button', { name: 'Download the filtered list as CSV' }))
      expect(blobs).toHaveLength(1)
      const text = typeof blobs[0].text === 'function' ? await blobs[0].text()
        : await new Promise((res) => { const r = new FileReader(); r.onload = () => res(r.result); r.readAsText(blobs[0]) })
      const lines = text.replace(String.fromCharCode(0xfeff), '').split('\r\n')
      expect(lines[0].startsWith('Method,Path,Requests,Errors,Error %')).toBe(true)
      expect(lines).toHaveLength(2)
      expect(lines[1].startsWith('DELETE,/api/x/{id},5,')).toBe(true)
    } finally {
      URL.createObjectURL = origCreate
      URL.revokeObjectURL = origRevoke
    }
  })
})

describe('HttpMetricsExplorer — ayrıntı paneli', () => {
  it('karta tıklayınca Sheet: aynı aralıkla uç serisi, durum kodları; "bu uca süz" süzgeci kurar', async () => {
    await renderLoaded()
    fireEvent.click(screen.getByRole('button', { name: 'Details: POST /api/auth/login' }))
    const sheet = await screen.findByRole('dialog', { name: 'Endpoint details' })
    // Ayrıntı, sunucunun GERÇEKTEN taradığı aralığı ister (yanıttaki from/to; 2026-09-28c ek-2) — fixture'da 08:00–09:00
    await waitFor(() => expect(api.admin.getHttpMetricsSeries).toHaveBeenCalledWith('2026-09-28T08:00:00', '2026-09-28T09:00:00', 'POST /api/auth/login'))
    await waitFor(() => expect(sheet.querySelector('[data-slot="hreq-code-list"]')).not.toBeNull())
    expect([...sheet.querySelectorAll('[data-slot="hreq-code-list"] [data-code]')].map((li) => li.getAttribute('data-code')))
      .toEqual(['200', '401', '503'])
    expect(within(sheet).getByText('32.5%')).toBeInTheDocument()
    fireEvent.click(within(sheet).getByRole('button', { name: 'Filter the chart to this endpoint' }))
    await waitFor(() => expect(lastOverview().endpoint).toBe('POST /api/auth/login'))
    expect(screen.getByRole('button', { name: 'Remove filter: POST /api/auth/login' })).toBeInTheDocument()
  })

  it('ayrıntı yüklenemezse hata + Tekrar dene', async () => {
    api.admin.getHttpMetricsSeries.mockRejectedValueOnce(new Error('boom'))
    await renderLoaded()
    fireEvent.click(screen.getByRole('button', { name: 'Details: GET /api/e01' }))
    const sheet = await screen.findByRole('dialog', { name: 'Endpoint details' })
    await within(sheet).findByText("Couldn't load the endpoint details")
    fireEvent.click(within(sheet).getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(sheet.querySelector('[data-slot="hreq-code-list"]')).not.toBeNull())
  })
})

describe('HttpMetricsExplorer — yenileme durumu korur', () => {
  it('elle yenile: arama, sıralama ve açık ayrıntı AYNEN kalır; ayrıntı da yeniden yüklenir', async () => {
    // Saat DONDURULUR: göreli aralık iki yüklemede AYNI from/to'ya çözülür → ayrıntının yeniden yüklenmesini aralık
    // değişimi değil YALNIZ yenileme işareti tetikleyebilir (mutlak aralıktaki davranış). Yalnız Date sahte; zamanlayıcılar gerçek.
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(Date.UTC(2026, 8, 28, 9, 30, 0)))
    try {
      await refreshKeepsState()
    } finally {
      vi.useRealTimers()
    }
  })
})

/** Yenileme durumu korur — gövde (saati donduran test çağırır). */
async function refreshKeepsState() {
  await renderLoaded()
  fireEvent.change(screen.getByRole('searchbox', { name: 'Search paths…' }), { target: { value: 'api' } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Sort by' }), { target: { value: 'p95:desc' } })
  fireEvent.click(screen.getByRole('button', { name: 'Details: DELETE /api/x/{id}' }))
  await screen.findByRole('dialog', { name: 'Endpoint details' })
  await waitFor(() => expect(api.admin.getHttpMetricsSeries).toHaveBeenCalledTimes(1))

  // Açık Sheet arka planı aria-hidden yapar → yenileme (otomatik yenileme tikinin karşılığı) gizli düğmeden tetiklenir
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Refresh', hidden: true })) })
  await waitFor(() => expect(api.admin.getHttpMetricsOverview).toHaveBeenCalledTimes(2))
  await waitFor(() => expect(api.admin.getHttpMetricsSeries).toHaveBeenCalledTimes(2))
  expect(screen.getByRole('searchbox', { name: 'Search paths…', hidden: true })).toHaveValue('api')
  expect(screen.getByRole('combobox', { name: 'Sort by', hidden: true })).toHaveValue('p95:desc')
  expect(screen.getByRole('dialog', { name: 'Endpoint details' })).toBeInTheDocument()
  expect(cardNames()[0]).toBe('DELETE /api/x/{id}')
}

describe('HttpMetricsExplorer — boş / hata durumları', () => {
  it('aralıkta istek yok: boş durum + "Widen to" bir sonraki hızlı aralıkla yeniden ister', async () => {
    api.admin.getHttpMetricsOverview.mockResolvedValue(overview({ summary: { total: 0 }, data: [], endpoints: [], status_codes: [], endpoints_total: 0 }))
    await renderLoaded()
    expect(screen.getByText('No requests in this range')).toBeInTheDocument()
    expect(screen.getByText('No endpoints match the filters.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Widen to: Last 3 hours' }))
    await waitFor(() => {
      const { from, to } = lastOverview()
      expect((Date.parse(to + 'Z') - Date.parse(from + 'Z')) / 60000).toBe(180)
    })
  })

  it('ilk yükleme düşerse hata bloğu; "Try again" başarıyla veriyi getirir', async () => {
    api.admin.getHttpMetricsOverview.mockRejectedValueOnce(new Error('network down'))
    render(<HttpMetricsExplorer />)
    const block = await screen.findByRole('alert')
    expect(within(block).getByText("Couldn't load the request data")).toBeInTheDocument()
    fireEvent.click(within(block).getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(tile('total')).not.toBeNull())
  })

  it('yenileme düşerse ÖNCEKİ veri kalır + uyarı bandı', async () => {
    await renderLoaded()
    api.admin.getHttpMetricsOverview.mockResolvedValueOnce({ success: false, error: '500' })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Refresh' })) })
    await screen.findByText('The last refresh failed, so this is data from the previous load.')
    expect(tile('total').textContent).toContain('1,500')
  })

  it('bölümden bir uçla açılış: o uca süzülü + son 24 saat', async () => {
    await renderLoaded({ initialFocus: { endpoint: 'POST /api/auth/login' } })
    const first = api.admin.getHttpMetricsOverview.mock.calls[0][0]
    expect(first.endpoint).toBe('POST /api/auth/login')
    expect((Date.parse(first.to + 'Z') - Date.parse(first.from + 'Z')) / 60000).toBe(1440)
    expect(screen.getByRole('button', { name: 'Remove filter: POST /api/auth/login' })).toBeInTheDocument()
  })

  it('otomatik yenileme ≤ 24 sa aralıkta açık (Live), kapatılabilir', async () => {
    await renderLoaded()
    const live = screen.getByRole('button', { name: 'Live' })
    expect(live).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(live)
    expect(live).toHaveAttribute('aria-pressed', 'false')
  })

  it('seri boş/bozuk dönerse çökmez', async () => {
    api.admin.getHttpMetricsOverview.mockResolvedValue({ success: true, data: null })
    render(<HttpMetricsExplorer />)
    await waitFor(() => expect(screen.getByText('No requests in this range')).toBeInTheDocument())
  })
})

/**
 * SAKLAMA ANAHTARI (eski sözleşme korunur): kutu, Genel Ayarlar satırını ADIYLA arar; bulunamazsa / yetki yoksa gizli;
 * kapsamlı müdürde (read_only, GLOBAL_ONLY) değer görünür + kilitli; geçersiz gün gönderilmez; geçerli gün DOĞRU anahtarla.
 */
describe('HttpMetricsExplorer — saklama süresi', () => {
  const box = () => document.querySelector('[data-testid="hme-retention"]')
  const input = () => within(box()).getByRole('spinbutton', { name: /Retention/i })

  it('anahtar ADIYLA bulunur; kutu çizilir', async () => {
    await renderLoaded()
    await waitFor(() => expect(box()).not.toBeNull())
    expect(input()).toHaveValue(7)
  })

  it('anahtar yok / yetki yok / istek düşer → kutu gizli, ekran ayakta', async () => {
    for (const res of [{ success: true, data: { settings: [] } }, { success: false, error: '403' }]) {
      api.admin.getGeneralSettings.mockResolvedValueOnce(res)
      const { unmount } = await renderLoaded()
      expect(box()).toBeNull()
      unmount()
    }
    api.admin.getGeneralSettings.mockRejectedValueOnce(new Error('network'))
    await renderLoaded()
    expect(box()).toBeNull()
  })

  it('read_only → değer görünür, girdi kilitli, kaydet YOK, kilit notu', async () => {
    api.admin.getGeneralSettings.mockResolvedValue(settings('14', { read_only: true }))
    await renderLoaded()
    await waitFor(() => expect(box()).not.toBeNull())
    expect(input()).toBeDisabled()
    expect(input()).toHaveValue(14)
    expect(within(box()).queryByRole('button')).toBeNull()
    expect(within(box()).getByText('Only a global admin can change this setting.')).toBeInTheDocument()
  })

  it('GEÇERSİZ gün kaydedilmez; geçerli gün doğru anahtarla', async () => {
    await renderLoaded()
    await waitFor(() => expect(box()).not.toBeNull())
    fireEvent.change(input(), { target: { value: '0' } })
    fireEvent.click(within(box()).getByRole('button', { name: /Save/ }))
    await waitFor(() => expect(api.admin.saveGeneralSettings).not.toHaveBeenCalled())
    fireEvent.change(input(), { target: { value: '30' } })
    fireEvent.click(within(box()).getByRole('button', { name: /Save/ }))
    await waitFor(() => expect(api.admin.saveGeneralSettings).toHaveBeenCalled())
    expect(api.admin.saveGeneralSettings.mock.calls.at(-1)[0].values).toHaveProperty('site.monitor.metrics.http.retention-days', '30')
  })
})

describe('HttpMetricsExplorer — 2026-09-28c düzeltmeleri', () => {
  it('ek-2: sunucu aralığı 31 güne kırptıysa ayrıntı KIRPILMIŞ aralığı ister (istek aralığını değil); kırpma/tavan bantları ayrıntıda da', async () => {
    api.admin.getHttpMetricsOverview.mockResolvedValue(overview({ from: '2026-08-28T09:00:00', to: '2026-09-28T09:00:00', clamped: true }))
    api.admin.getHttpMetricsSeries.mockResolvedValue({ ...series, data: { ...series.data, capped: true } })
    await renderLoaded()
    fireEvent.click(screen.getByRole('button', { name: 'Details: POST /api/auth/login' }))
    const sheet = await screen.findByRole('dialog', { name: 'Endpoint details' })
    await waitFor(() => expect(api.admin.getHttpMetricsSeries).toHaveBeenCalledWith('2026-08-28T09:00:00', '2026-09-28T09:00:00', 'POST /api/auth/login'))
    const req = lastOverview()
    expect(api.admin.getHttpMetricsSeries).not.toHaveBeenCalledWith(req.from, req.to, 'POST /api/auth/login')
    expect(await within(sheet).findByText(/can't show the whole range/)).toBeInTheDocument()
  })

  it('ek-4: sunucunun doğrulama iletisi genel hata metni yerine gösterilir', async () => {
    api.admin.getHttpMetricsOverview.mockResolvedValueOnce({ success: false, error: 'The start must not be after the end' })
    render(<HttpMetricsExplorer />)
    const block = await screen.findByRole('alert')
    expect(within(block).getByText('The start must not be after the end')).toBeInTheDocument()
  })

  it('ek-5: asılı istekte Yenile KİLİTLENMEZ (meşgul işaretli ama tıklanabilir); yeni tur eskisini bayat yapar', async () => {
    await renderLoaded()
    let release
    api.admin.getHttpMetricsOverview.mockImplementationOnce(() => new Promise((r) => { release = r }))
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Refresh' })) })
    const btn = screen.getByRole('button', { name: 'Refresh' })
    expect(btn).toHaveAttribute('aria-busy', 'true')
    expect(btn).toBeEnabled()
    await act(async () => { fireEvent.click(btn) })                      // asılı isteğe rağmen yeniden denenebilir
    await waitFor(() => expect(api.admin.getHttpMetricsOverview).toHaveBeenCalledTimes(3))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Refresh' })).not.toHaveAttribute('aria-busy'))
    await act(async () => { release(overview({ summary: { total: 1 } })) })   // bayat yanıt: yazılmaz
    expect(tile('total').textContent).toContain('1,500')
  })

  it('ek-9: "Diğer" (status_other) sınıfı hacim serisinde aç/kapa düğmesiyle görünür', async () => {
    const base = overview().data
    api.admin.getHttpMetricsOverview.mockResolvedValue(overview({ summary: { ...base.summary, status_other: 12 } }))
    await renderLoaded()
    const group = screen.getByRole('toolbar', { name: 'Series' })
    expect(within(group).getByRole('button', { name: /^Other$/ })).toHaveAttribute('aria-pressed', 'true')
  })
})
