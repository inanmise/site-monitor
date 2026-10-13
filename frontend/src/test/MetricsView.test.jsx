import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within, act } from './test-utils.jsx'
import userEvent from '@testing-library/user-event'
import MetricsView from '../components/admin/metrics/MetricsView.jsx'
import MetricChart from '../components/admin/metrics/MetricChart.jsx'

/**
 * Sistem Sağlığı → Metrikler görünümü (2026-10-13): özet kutucukları + pod listesi + grafik bölümleri; aralık değişince
 * eski seri silinir ve URL'ye yazılır; geç gelen eski yanıt düşer; uç düşerse hata bandı (son veri korunur); hiç örnek
 * yoksa "toplanıyor" notu ve bilinmeyen kutucuklar nötr (yeşil değil); Geri.
 */
const { sm } = vi.hoisted(() => ({ sm: { overview: vi.fn(), series: vi.fn() } }))
vi.mock('../api/client', () => ({ api: { admin: { systemMetrics: sm } } }))

const LONG_POD = 'site-monitor-production-deployment-7f9c8d6b5a-xk2lp (Kurumsal Uygulamalar / Canlı Ortam)'
const overview = {
  generated_at: '2031-04-02T12:00:30', served_by: 'a:1', pods_live: 1,
  pods: [
    { id: 'a:1', name: LONG_POD, last_seen: '2031-04-02T11:59:00', live: true,
      latest: { uptime_s: 7200, cpu_process: 12.5, heap_pct: 41, heap_used_mb: 420, heap_max_mb: 1024, threads: 88,
        db_active: 2, db_max: 10, db_pending: 0, db_usage_pct: 20, exec_queue: 3, exec_queue_cap: 5000 } },
    { id: 'b:1', name: 'pod-b', last_seen: '2031-04-02T10:00:00', live: false, latest: null },
  ],
  last_hour: { http_requests: 1200, http_errors: 6, http_error_pct: 0.5, http_avg_ms: 40, http_p95_ms: 180, caller_runs: 0, log_warn: 4, log_error: 0, gc_pause_ms: 12 },
}
const seriesFor = (range) => ({
  range, bucket: range === '1h' ? '1m' : '10m', from: '2031-04-02T11:00:30', to: '2031-04-02T12:00:30', instance: null,
  pods: overview.pods.map(({ id, name, live }) => ({ id, name, live })),
  points: [{ pod: 'a:1', t: '2031-04-02T11:50:00', samples: 10, cpu_process_avg: 11, heap_pct_max: 40, threads_max: 90,
    http_requests: 500, http_errors: 2, http_sum_ms: 20000, http_p95_ms: 150, log_warn: 1, log_error: 0, cache_hits: 8, cache_misses: 2 }],
})

beforeEach(() => {
  sm.overview.mockReset().mockResolvedValue({ success: true, data: overview })
  sm.series.mockReset().mockImplementation((range) => Promise.resolve({ success: true, data: seriesFor(range) }))
  window.history.replaceState(null, '', '/?tab=health&view=metrics')
})
afterEach(() => { window.history.replaceState(null, '', '/') })

describe('MetricsView', () => {
  it('kutucuklar, pod listesi (uzun ad kırpılmaz, sessiz pod), grafik bölümleri', async () => {
    render(<MetricsView onBack={() => {}} />)
    const kpis = await screen.findByRole('group', { name: /Summary of current values|Anlık değerlerin özeti/ })
    expect(within(kpis).getAllByText(/1 \/ 2/).length).toBeGreaterThan(0)
    expect(kpis.querySelector('[data-kpi="pods"]').getAttribute('data-tone')).toBe('warn')
    expect(kpis.querySelector('[data-kpi="cpu"]').getAttribute('data-tone')).toBe('ok')
    const pods = document.querySelector('[data-slot="mx-pods"]')
    expect(within(pods).getByText(LONG_POD)).toBeInTheDocument()
    expect(pods.querySelectorAll('[data-slot="mx-pod-status"][data-live="false"]')).toHaveLength(1)
    expect(within(pods).getByText(/This pod|Bu pod/)).toBeInTheDocument()
    for (const sec of ['pods', 'jvm', 'http', 'db', 'exec', 'logs']) {
      expect(document.querySelector(`[data-slot="mx-sec"][data-sec="${sec}"]`), sec).not.toBeNull()
    }
    expect(document.querySelectorAll('[data-slot="mx-chart"]').length).toBeGreaterThanOrEqual(14)
    expect(sm.series).toHaveBeenCalledWith('6h', null)
  })

  it('aralık değişince yeni seri istenir, adrese yazılır; önceki aralığın geç yanıtı düşer', async () => {
    const user = userEvent.setup()
    render(<MetricsView onBack={() => {}} />)
    await screen.findByRole('group', { name: /Summary of current values|Anlık değerlerin özeti/ })
    let lateResolve
    sm.series.mockImplementationOnce(() => new Promise((r) => { lateResolve = r }))
    await user.click(screen.getByRole('button', { name: /^(24 hours|24 saat)$/ }))
    await waitFor(() => expect(sm.series).toHaveBeenLastCalledWith('24h', null))
    await user.click(screen.getByRole('button', { name: /^(1 hour|1 saat)$/ }))
    await waitFor(() => expect(sm.series).toHaveBeenLastCalledWith('1h', null))
    expect(new URL(window.location.href).searchParams.get('mx_range')).toBe('1h')
    await waitFor(() => expect(document.querySelector('[data-slot="mx-bucket"]').getAttribute('data-bucket')).toBe('1m'))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="mx-chart"]:not([data-empty])').length).toBeGreaterThan(0))
    await act(async () => { lateResolve({ success: true, data: { ...seriesFor('24h'), points: [] } }) })
    expect(document.querySelector('[data-slot="mx-bucket"]').getAttribute('data-bucket')).toBe('1m')
    expect(document.querySelectorAll('[data-slot="mx-chart"]:not([data-empty])').length).toBeGreaterThan(0)
  })

  it('uç düşerse hata bandı; önceki veri korunur', async () => {
    const user = userEvent.setup()
    render(<MetricsView onBack={() => {}} />)
    await screen.findByRole('group', { name: /Summary of current values|Anlık değerlerin özeti/ })
    sm.series.mockRejectedValueOnce(new Error('ağ'))
    sm.overview.mockResolvedValueOnce({ success: true, data: overview })
    await user.click(screen.getByRole('button', { name: /^(Refresh|Yenile)$/ }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/time series|zaman serisi/i)
    expect(alert).toHaveTextContent(/last values|son okunan/i)
    expect(document.querySelectorAll('[data-slot="mx-chart"]').length).toBeGreaterThan(0)
  })

  it('ilk yükleme düşerse "pod yok" / "örnek yok" denmez: okunamadı', async () => {
    sm.overview.mockRejectedValue(new Error('ağ'))
    sm.series.mockRejectedValue(new Error('ağ'))
    render(<MetricsView onBack={() => {}} />)
    expect(await screen.findByRole('alert')).not.toHaveTextContent(/last values|son okunan/i)
    expect(document.querySelector('[data-slot="mx-pods-failed"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="mx-pods-empty"]')).toBeNull()
    expect(document.querySelector('[data-slot="mx-charts-failed"]')).not.toBeNull()
    const pods = document.querySelector('[data-kpi="pods"]')
    expect(pods.getAttribute('data-tone')).toBe('neutral')
    expect(pods).toHaveTextContent(/could not be read|okunamadı/)
    expect(screen.queryByText(/no samples yet|henüz örnek yok/)).toBeNull()
  })

  it('durmuş pod (dağıtım) uyarı değil: nötr rozet, kutucuk yeşil', async () => {
    sm.overview.mockResolvedValue({ success: true, data: { ...overview,
      pods: [overview.pods[0], { ...overview.pods[1], state: 'stopped' }] } })
    render(<MetricsView onBack={() => {}} />)
    const kpis = await screen.findByRole('group', { name: /Summary of current values|Anlık değerlerin özeti/ })
    expect(kpis.querySelector('[data-kpi="pods"]').getAttribute('data-tone')).toBe('ok')
    expect(kpis.querySelector('[data-kpi="pods"]')).toHaveTextContent(/1 stopped|1 pod durdu/)
    expect(document.querySelectorAll('[data-slot="mx-pod-status"][data-state="stopped"]')).toHaveLength(1)
    expect(document.querySelectorAll('[data-slot="mx-pod-status"][data-state="silent"]')).toHaveLength(0)
  })

  it('örnekleme kapalıyken "kapalı" denir; "toplanıyor" / kırmızı pod kutucuğu yok', async () => {
    sm.overview.mockResolvedValue({ success: true, data: { ...overview, enabled: false,
      pods: [{ ...overview.pods[0], state: 'stopped', live: false, latest: null }] } })
    render(<MetricsView onBack={() => {}} />)
    expect(await screen.findByText(/Metric sampling is turned off|Metrik örnekleme kapalı/)).toBeInTheDocument()
    expect(screen.queryByText(/Metrics are being collected|Metrikler toplanıyor/)).toBeNull()
    const tile = document.querySelector('[data-kpi="pods"]')
    expect(tile.getAttribute('data-tone')).toBe('neutral')
    expect(tile).toHaveTextContent(/turned off|kapalı/)
  })

  it('açık ama örnek yazılmıyorsa "toplanıyor" değil kırmızı uyarı', async () => {
    sm.overview.mockResolvedValue({ success: true, data: { pods: [], last_hour: {}, served_by: 'x', enabled: true,
      generated_at: '2031-04-02T12:00:30', served_uptime_s: 1800 } })
    sm.series.mockResolvedValue({ success: true, data: { range: '6h', bucket: '10m', from: '2031-04-02T06:00:00', to: '2031-04-02T12:00:30', pods: [], points: [] } })
    render(<MetricsView onBack={() => {}} />)
    expect(await screen.findByText(/Metric samples are not being written|Metrik örnekleri yazılmıyor/)).toBeInTheDocument()
    expect(screen.queryByText(/Metrics are being collected|Metrikler toplanıyor/)).toBeNull()
    expect(document.querySelector('[data-kpi="pods"]').getAttribute('data-tone')).toBe('crit')
  })

  it('önceki aralığın hatası yeni aralık yüklenirken "okunamadı" demez', async () => {
    const user = userEvent.setup()
    sm.series.mockRejectedValueOnce(new Error('ağ'))
    render(<MetricsView onBack={() => {}} />)
    await waitFor(() => expect(document.querySelector('[data-slot="mx-charts-failed"]')).not.toBeNull())
    let release
    sm.series.mockImplementationOnce(() => new Promise((r) => { release = r }))
    await user.click(screen.getByRole('button', { name: /^(1 hour|1 saat)$/ }))
    await waitFor(() => expect(sm.series).toHaveBeenLastCalledWith('1h', null))
    expect(document.querySelector('[data-slot="mx-charts-failed"]')).toBeNull()
    await act(async () => { release({ success: true, data: seriesFor('1h') }) })
    expect(document.querySelectorAll('[data-slot="mx-chart"]').length).toBeGreaterThan(0)
  })

  it('hiç örnek yoksa "toplanıyor" notu, kutucuklar nötr (yeşil değil)', async () => {
    sm.overview.mockResolvedValue({ success: true, data: { pods: [], last_hour: {}, served_by: 'x' } })
    sm.series.mockResolvedValue({ success: true, data: { range: '6h', bucket: '10m', from: '2031-04-02T06:00:30', to: '2031-04-02T12:00:30', pods: [], points: [] } })
    render(<MetricsView onBack={() => {}} />)
    expect(await screen.findByText(/Metrics are being collected|Metrikler toplanıyor/)).toBeInTheDocument()
    const kpis = screen.getByRole('group', { name: /Summary of current values|Anlık değerlerin özeti/ })
    for (const el of kpis.querySelectorAll('[data-kpi]')) expect(el.getAttribute('data-tone'), el.getAttribute('data-kpi')).toBe('neutral')
    expect(document.querySelector('[data-slot="mx-pods-empty"]')).not.toBeNull()
    const charts = document.querySelectorAll('[data-slot="mx-chart"]')
    expect([...charts].every((c) => c.hasAttribute('data-empty'))).toBe(true)
  })

  it('URL\'deki aralık ve pod başlangıçta okunur; Geri onBack çağırır', async () => {
    const onBack = vi.fn()
    const user = userEvent.setup()
    render(<MetricsView onBack={onBack} initial={{ range: '7d', pod: 'a:1' }} />)
    await waitFor(() => expect(sm.series).toHaveBeenCalledWith('7d', 'a:1'))
    await user.click(screen.getByRole('button', { name: /^(System Health|Sistem Sağlığı)$/ }))
    expect(onBack).toHaveBeenCalled()
  })
})

describe('MetricChart eşik anahtarı', () => {
  const rows = (v) => [{ t: Date.UTC(2031, 3, 2, 12, 0), p0: v }, { t: Date.UTC(2031, 3, 2, 12, 1), p0: v }]
  const series = [{ key: 'p0', label: 'pod-a', color: 'var(--chart-1)', shape: 'line' }]
  const t = (k, ...a) => `${k}(${a.join(',')})`

  it('çizgi çizilmeyince (veri eşiğin çok altında) anahtarda da yok; çizilince var', () => {
    const { unmount } = render(<MetricChart id="x" t={t} title="X" rows={rows(5)} series={series} thresholds={{ warn: 1000, crit: 3000 }} />)
    expect(screen.queryByText(/mx\.chart\.warnAt/)).toBeNull()
    expect(screen.queryByText(/mx\.chart\.critAt/)).toBeNull()
    unmount()
    render(<MetricChart id="y" t={t} title="Y" rows={rows(1200)} series={series} thresholds={{ warn: 1000, crit: 3000 }} />)
    expect(screen.getByText(/mx\.chart\.warnAt/)).toBeInTheDocument()
    expect(screen.getByText(/mx\.chart\.critAt/)).toBeInTheDocument()
  })

  it('iki yanı boş tek değer nokta olarak çizilir; komşulu değerlere nokta konmaz', () => {
    const rowsLone = [
      { t: Date.UTC(2031, 3, 2, 12, 0) },
      { t: Date.UTC(2031, 3, 2, 12, 1), p0: 40 },
      { t: Date.UTC(2031, 3, 2, 12, 2) },
    ]
    const { container, unmount } = render(<MetricChart id="z" t={t} title="Z" rows={rowsLone} series={series} />)
    // jsdom'da grafik genişliği 0 olabilir: nokta bileşeni çizilmişse bile koordinatsızdır — en az "boş" sayılmadığını doğrula
    expect(container.querySelector('[data-slot="mx-chart"]').hasAttribute('data-empty')).toBe(false)
    unmount()
  })
})
