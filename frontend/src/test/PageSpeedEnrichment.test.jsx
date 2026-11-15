import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, fillGroupAndTags } from './test-utils.jsx'
import PageSpeedMonitorPage from '../components/PageSpeedMonitorPage.jsx'
import { kbFieldErrors } from '../utils/pageSpeedThresholds.js'
import { phasesOf, typeBreakdownOf, typeSeriesData, hasTypeData } from '../components/pagespeed/pageSpeedInsightsModel.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups:              vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults:         vi.fn(() => Promise.resolve({ success: true, data: {} })),
      getPageSpeedMonitors:    vi.fn(),
      getPageSpeedResources:   vi.fn(),
      downloadPageSpeedResourcesCsv: vi.fn(),
      getPageSpeedTypeSeries:  vi.fn(),
      getPageSpeedSeries:      vi.fn(),
      createPageSpeedMonitor:  vi.fn(),
      updatePageSpeedMonitor:  vi.fn(),
      triggerPageSpeedCheck:   vi.fn(),
      getCheckHistory:         vi.fn(),
      getCheckHistoryCsvUrl:   vi.fn(() => '#'),
    },
    admin: { getTeams: vi.fn(), getAlerts: vi.fn() },
  }),
}))
import { api } from '../api/client'

/**
 * Sayfa Hızı zenginleştirme 1. sürüm (2026-11-14): tür bütçeleri + asgari boyut (alan hataları alanın altında, değişmeyen
 * değer yeniden denetlenmez), süre tavanı (≥ / kısmi), gizli kalan ölçüler, bütçe uyumu, kaynak süzgeci + sayfalama +
 * tüm-satır CSV, "Kırılım" sekmesi (faz + tür + zaman serisi; bilinmeyen asla 0 değil).
 */
const monitor = {
  id: 1, name: 'Ödeme sayfası', url: 'https://x.com/odeme', team_id: 5, team_name: 'SY-A', group_name: 'Kanal', tags: 'prod',
  active: true, interval_seconds: 1800, timeout_ms: 10000,
  max_load_ms: 3000, max_ttfb_ms: null, max_page_kb: 1000, max_requests: null,
  max_js_kb: null, max_img_kb: null, max_font_kb: null, max_third_party_kb: null, min_page_kb: null,
  status: 'OK', ok: true, response_ms: 900, ttfb_ms: 120, html_ms: 300,
  total_bytes: 2048 * 1024, request_count: 42, failed_count: 2, skipped_lazy: 4, capped: false,
  dns_ms: 12, connect_ms: 28, tls_ms: 46, server_ms: 90,
  html_bytes: 30000, js_bytes: 600000, js_count: 9, css_bytes: 50000, css_count: 3, img_bytes: 900000, img_count: 20,
  font_bytes: 80000, font_count: 2, other_bytes: 1000, other_count: 1, first_party_bytes: 1200000, third_party_bytes: 461000,
  breached_metrics: [], last_check: '2026-11-14T10:00:00',
  has_basic_auth_pass: false, has_custom_headers: false, custom_header_names: [],
}
const withMonitor = (over = {}) => ({ ...monitor, ...over })
const cardTitle = () => screen.findByRole('button', { name: /^https:\/\/x\.com\/odeme — (open details|detayları aç)$/ })

describe('Sayfa Hızı zenginleştirme — modeller', () => {
  const t = (k) => k
  it('kbFieldErrors: boş geçerli; tam sayı 0..10M; asgari > azami hata; değişmeyen değer denetlenmez', () => {
    expect(kbFieldErrors({ maxJsKb: '', minPageKb: '' }, t)).toEqual({})
    expect(kbFieldErrors({ maxJsKb: '-1' }, t)).toEqual({ maxJsKb: 'pspd.err.kbRange' })
    expect(kbFieldErrors({ maxImgKb: '1.5' }, t)).toEqual({ maxImgKb: 'pspd.err.kbRange' })
    expect(kbFieldErrors({ maxFontKb: '20000000' }, t)).toEqual({ maxFontKb: 'pspd.err.kbRange' })
    expect(kbFieldErrors({ minPageKb: '2000', maxPageKb: '1000' }, t)).toEqual({ minPageKb: 'pspd.err.minAboveMax' })
    expect(kbFieldErrors({ minPageKb: '200', maxPageKb: '' }, t)).toEqual({})
    // Kayıtlı (bozuk) eski değer değişmedikçe kaydı kilitlemez
    const base = { minPageKb: 2000, maxPageKb: 1000, maxJsKb: -5 }
    expect(kbFieldErrors({ minPageKb: 2000, maxPageKb: 1000, maxJsKb: -5 }, t, base)).toEqual({})
    expect(kbFieldErrors({ minPageKb: '3000', maxPageKb: 1000, maxJsKb: -5 }, t, base)).toEqual({ minPageKb: 'pspd.err.minAboveMax' })
  })

  it('phasesOf: ölçülmüş fazlar + HTML indirme (HTML − TTFB); hiçbiri yoksa boş', () => {
    expect(phasesOf(monitor)).toEqual([
      { key: 'dns', ms: 12 }, { key: 'connect', ms: 28 }, { key: 'tls', ms: 46 }, { key: 'server', ms: 90 }, { key: 'download', ms: 180 }])
    expect(phasesOf({ response_ms: 100 })).toEqual([])
  })

  it('typeBreakdownOf: eski satır (kırılım yok) null — asla 0 değil; sıra sabit', () => {
    expect(typeBreakdownOf({ response_ms: 1 })).toBeNull()
    const b = typeBreakdownOf(monitor)
    expect(b.rows.map((r) => r.key)).toEqual(['html', 'js', 'css', 'img', 'font', 'other'])
    expect(b.total).toBe(30000 + 600000 + 50000 + 900000 + 80000 + 1000)
    expect(b.rows.find((r) => r.key === 'html').count).toBeNull()
  })

  it('typeSeriesData: kırılımsız kova null kalır, bozuk damga atılır', () => {
    const d = typeSeriesData([
      { ts: '2026-11-11T00:00:00', html: 1024, js: 2048, css: null, img: null, font: null, other: null, n: 1 },
      { ts: '2026-11-12T00:00:00', html: null, js: null, css: null, img: null, font: null, other: null, n: 0 },
      { ts: 'bozuk', js: 1 },
    ])
    expect(d).toHaveLength(2)
    expect(d[0].js).toBe(2)
    expect(d[1].js).toBeNull()
    expect(hasTypeData(d)).toBe(true)
    expect(hasTypeData(typeSeriesData([{ ts: '2026-11-12T00:00:00', js: null }]))).toBe(false)
  })
})

describe('Sayfa Hızı zenginleştirme — sayfa', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getPageSpeedResources.mockResolvedValue({ success: true, data: { resources: [], breaches: [], total: 0, filtered_total: 0 } })
    api.monitoring.getPageSpeedTypeSeries.mockResolvedValue({ success: true, data: { points: [] } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
  })

  it('bütçe alanı hatalıysa kayıt GİTMEZ; hata alanın altında', async () => {
    render(<PageSpeedMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageSpeedMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /add monitor|izleme ekle/i }))
    fireEvent.change(screen.getByPlaceholderText('https://example.com'), { target: { value: 'https://y.com' } })
    await fillGroupAndTags()
    fireEvent.change(screen.getByLabelText(/javascript budget|javascript bütçesi/i), { target: { value: '-3' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))

    const field = document.querySelector('[data-field="maxJsKb"]')
    expect(await within(field).findByText(/whole number of KB|tam sayı \(KB\)/i)).toBeInTheDocument()
    expect(api.monitoring.createPageSpeedMonitor).not.toHaveBeenCalled()
  })

  it('bütçeler ve asgari boyut payload\'a sayı / null olarak girer; sunucu alan hatası alanın altına yazılır', async () => {
    api.monitoring.createPageSpeedMonitor.mockResolvedValue({ success: false, field: 'minPageKb',
      error: 'Asgari sayfa boyutu azami sayfa boyutundan büyük olamaz.' })
    render(<PageSpeedMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageSpeedMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /add monitor|izleme ekle/i }))
    fireEvent.change(screen.getByPlaceholderText('https://example.com'), { target: { value: 'https://y.com' } })
    await fillGroupAndTags()
    fireEvent.change(screen.getByLabelText(/image budget|görsel bütçesi/i), { target: { value: '800' } })
    fireEvent.change(screen.getByLabelText(/minimum page size|asgari sayfa boyutu/i), { target: { value: '40' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))

    await waitFor(() => expect(api.monitoring.createPageSpeedMonitor).toHaveBeenCalled())
    const payload = api.monitoring.createPageSpeedMonitor.mock.calls[0][0]
    expect(payload).toMatchObject({ maxImgKb: 800, minPageKb: 40, maxJsKb: null, maxFontKb: null, maxThirdPartyKb: null })
    const field = document.querySelector('[data-field="minPageKb"]')
    expect(await within(field).findByText(/azami sayfa boyutundan/)).toBeInTheDocument()
  })

  it('detay özeti gizli kalan ölçüleri ve bütçe uyumunu gösterir; süre tavanı ≥ + uyarı', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [withMonitor({ time_truncated: true })] })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await cardTitle())
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('≥ 900 ms')).toBeInTheDocument()
    expect(within(dialog).getByText('300 ms')).toBeInTheDocument()                     // HTML süresi
    expect(within(dialog).getByText(/skipped lazy|atlanan tembel/i)).toBeInTheDocument()
    expect(dialog.querySelector('[data-slot="pspd-time-truncated"]')).not.toBeNull()
  })

  it('kaynak süzgeci sunucuya gider, sayfalama çubuğu toplam sayfayı aşınca çıkar, CSV sunucudan tüm satırları ister', async () => {
    const rows = Array.from({ length: 50 }, (_, i) => ({ url: `https://x.com/r${i}.js`, type: 'JS', bytes: 1000 + i,
      duration_ms: 10, http_status: 200, third_party: false, failed: false }))
    api.monitoring.getPageSpeedResources.mockResolvedValue({ success: true, data: {
      resources: rows, breaches: [], total: 120, filtered_total: 120, page: 0, size: 50,
      facets: { type: { js: 80, css: 10, img: 20, font: 5, other: 5 }, party: { first: 100, third: 20 }, failed: 3 } } })
    api.monitoring.downloadPageSpeedResourcesCsv.mockResolvedValue({ success: true, name: 'x.csv' })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await cardTitle())

    expect(await screen.findByText('https://x.com/r0.js')).toBeInTheDocument()
    expect(screen.getByText(/120 resources in this measurement|bu ölçümde 120 kaynak/i)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="pagination-bar"], nav[aria-label]')).not.toBeNull()

    fireEvent.click(screen.getByRole('checkbox', { name: /failed only|yalnız alınamayan/i }))
    await waitFor(() => expect(api.monitoring.getPageSpeedResources).toHaveBeenLastCalledWith(1,
      expect.objectContaining({ failed: true, page: 0 })))

    fireEvent.click(screen.getByRole('button', { name: /csv \(all\)|csv \(tümü\)/i }))
    await waitFor(() => expect(api.monitoring.downloadPageSpeedResourcesCsv).toHaveBeenCalledWith(1,
      expect.objectContaining({ failed: true, type: 'all', party: 'all' })))
  })

  it('Kırılım sekmesi: faz çubuğu, tür kırılımı, seri boşsa açıklama — eski satırda "kırılım yok"', async () => {
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await cardTitle())
    fireEvent.mouseDown(await screen.findByRole('tab', { name: /^breakdown$|^kırılım$/i }), { button: 0 })

    const phases = await screen.findByText(/time breakdown|süre kırılımı/i)
    expect(phases).toBeInTheDocument()
    expect(document.querySelectorAll('[data-slot="pspd-phase"]')).toHaveLength(5)
    expect(document.querySelector('[data-slot="pspd-types"]')).toHaveAttribute('data-state', 'ready')
    expect(document.querySelectorAll('[data-slot="pspd-type-row"]')).toHaveLength(6)
    await waitFor(() => expect(document.querySelector('[data-slot="pspd-type-chart"]')).toHaveAttribute('data-state', 'empty'))
    expect(api.monitoring.getPageSpeedTypeSeries).toHaveBeenCalledWith(1, { days: 7 })
  })

  it('Kırılım: eski satır kırılımsız → "kırılım yok"; eski sunucu (404) → "desteklemiyor"', async () => {
    const old = { ...monitor }
    for (const k of ['dns_ms', 'connect_ms', 'tls_ms', 'server_ms', 'html_ms', 'html_bytes', 'js_bytes', 'css_bytes',
      'img_bytes', 'font_bytes', 'other_bytes']) delete old[k]
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [old] })
    api.monitoring.getPageSpeedTypeSeries.mockResolvedValue({ success: false, status: 404, code: 'NOT_FOUND' })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await cardTitle())
    fireEvent.mouseDown(await screen.findByRole('tab', { name: /^breakdown$|^kırılım$/i }), { button: 0 })

    await waitFor(() => expect(document.querySelector('[data-slot="pspd-type-series"]')).toHaveAttribute('data-state', 'unsupported'))
    expect(document.querySelector('[data-slot="pspd-types"]')).toHaveAttribute('data-state', 'none')
    expect(document.querySelector('[data-slot="pspd-phases"]')).toHaveAttribute('data-state', 'none')
  })

  it('kontrol geçmişi satırı kısmi ölçüm rozetini ve gizli ölçüleri taşır', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [{ checked_at: '2026-11-13T09:15:00', ok: true, breached_metrics: 'JS', breach_detail: 'JS:300>812',
        response_ms: 61000, ttfb_ms: 55, html_ms: 410, total_bytes: 3 * 1024 * 1024, request_count: 7, time_truncated: true,
        js_bytes: 831488, failed_count: 1 }],
      counts: { total: 1, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-11-07T00:00:00', to: '2026-11-14T23:59:59' }, total: 1, page: 0, size: 50 } })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await cardTitle())
    fireEvent.mouseDown(screen.getByRole('tab', { name: /check history|kontrol geçmişi/i }), { button: 0 })

    expect(await screen.findByText('≥ 61000 ms')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="pspd-hist-partial"]')).not.toBeNull()
    expect(screen.getByText(/JS budget exceeded|JS bütçesi aşıldı/i)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="pspd-hist-extra"]').textContent).toMatch(/410 ms/)
  })
})
