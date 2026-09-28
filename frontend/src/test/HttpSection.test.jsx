import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import HttpSection from '../components/admin/health/HttpSection.jsx'

/**
 * Sistem Sağlığı → HTTP istekleri bölümü (2026-09-28 yeniden tasarım). Fixture GERÇEK tel biçiminde:
 * `/api/admin/system/http-metrics` → { summary, history (UTC, Z'siz ts), top_endpoints }. Sorgular rol / ad ve
 * `data-slot` / `data-kpi` / `data-tone` ile.
 */
const t = (k, ...a) => (a.length ? `${k}(${a.join('|')})` : k)
const minute = (i) => new Date(Date.UTC(2026, 8, 28, 9, i)).toISOString().slice(0, 19)

const TOP_EP = (endpoint, extra) => ({
  endpoint, method: endpoint.split(' ')[0], path: endpoint.split(' ')[1], count: 40, errors: 0, error_rate_pct: 0,
  avg_ms: 50, max_ms: 90, p50_ms: 40, p95_ms: 80, p99_ms: 88, status_2xx: 40, status_3xx: 0, status_4xx: 0, status_5xx: 0,
  status_other: 0, unclassified: 0, last_seen: '2026-09-28T09:10:00', ...extra,
})

const HTTP = {
  summary: { total_requests: 184210, total_errors: 11800, error_rate_pct: 6.4, avg_ms: 1450, max_ms: 6120, buckets: 12,
    p50_ms: 90, p95_ms: 2400, p99_ms: 5200, req_per_min: 128.4, peak_req_per_min: 420 },
  history: Array.from({ length: 12 }, (_, i) => ({ ts: minute(i), count: 500 + i, errors: i === 3 ? 7 : 0, sum_ms: 0,
    avg_ms: i === 5 ? 3500 : 1200, max_ms: 4000, p95_ms: 1500 })),
  top_endpoints: {
    window_hours: 24, endpoint_count: 12,
    slowest: [TOP_EP('GET /api/reports/{id}', { p95_ms: 3200 }), TOP_EP('POST /api/monitoring/http/{id}/check', { p95_ms: 1100 })],
    errors: [TOP_EP('POST /api/auth/login', { errors: 30, error_rate_pct: 12.5, status_4xx: 30 })],
  },
}

function renderSection(props = {}) {
  const onOpenExplorer = vi.fn()
  const onRetry = vi.fn()
  render(<HttpSection t={t} httpMetrics={HTTP} error={false} onRetry={onRetry} onOpenExplorer={onOpenExplorer} {...props} />)
  return { onOpenExplorer, onRetry }
}
const tile = (id) => document.querySelector(`[data-slot="hreq-tile"][data-kpi="${id}"]`)

describe('HttpSection — özet kutucukları', () => {
  it('altı kutucuk; değerler sunucudan, tonlar Sistem Sağlığı eşiklerinden', () => {
    renderSection()
    expect([...document.querySelectorAll('[data-slot="hreq-tile"]')].map((el) => el.getAttribute('data-kpi')))
      .toEqual(['total', 'rate', 'errors', 'avg', 'max', 'slowest'])
    expect(tile('total').textContent).toContain('184,210')
    expect(tile('rate').textContent).toContain('128')
    expect(tile('rate').textContent).toContain('hreq.kpi.peak(420)')
    expect(tile('errors')).toHaveAttribute('data-tone', 'crit')      // %6,4 ≥ %5
    expect(tile('avg')).toHaveAttribute('data-tone', 'warn')         // 1,45 sn ≥ 1 sn
    expect(tile('avg').textContent).toContain('hreq.kpi.p95Sub(2.4 rtc.unit.s)')
    expect(tile('max').textContent).toContain('hreq.kpi.p99Sub(5.2 rtc.unit.s)')
    // ton renk tek başına değil: ekran okuyucuya metin olarak da söylenir
    expect(within(tile('errors')).getByText('(hreq.tone.crit)')).toBeInTheDocument()
  })

  it('"en yavaş uç" kutucuğu bir eylem düğmesi: gezgini o uçla açar', () => {
    const { onOpenExplorer } = renderSection()
    const btn = screen.getByRole('button', { name: /hreq\.kpi\.slowestAction\(.*GET \/api\/reports\/\{id\}/ })
    expect(btn).toHaveAttribute('data-tone', 'crit')               // p95 3,2 sn ≥ 3 sn
    fireEvent.click(btn)
    expect(onOpenExplorer).toHaveBeenCalledWith({ endpoint: 'GET /api/reports/{id}' })
  })

  it('birincil çağrı düğmesi gezgini süzgeçsiz açar', () => {
    const { onOpenExplorer } = renderSection()
    fireEvent.click(screen.getByRole('button', { name: 'health.httpOpenExplorer' }))
    expect(onOpenExplorer).toHaveBeenCalledWith(undefined)
  })
})

describe('HttpSection — grafikler', () => {
  it('üç küçük grafik (hacim / süre / hata-dk); süre ve hata grafiklerinde eşik ihlal rozeti', () => {
    renderSection()
    const charts = document.querySelector('[data-slot="http-charts"]')
    expect([...charts.querySelectorAll('[data-slot="hreq-mini"]')].map((el) => el.getAttribute('data-chart-id')))
      .toEqual(['volume', 'latency', 'errors'])
    const lat = charts.querySelector('[data-chart-id="latency"] [data-slot="hreq-breach"]')
    // 12 dakikanın 11'i ≥ 1 sn (uyarı), biri ≥ 3 sn (kritik) → 12 ihlal, ton kritik
    expect(lat).toHaveAttribute('data-breach', 'crit')
    expect(lat.textContent).toBe('hreq.chart.breach(12)')
    const err = charts.querySelector('[data-chart-id="errors"] [data-slot="hreq-breach"]')
    expect(err).toHaveAttribute('data-breach', 'crit')              // 7 hata/dk ≥ 5
    expect(charts.querySelector('[data-chart-id="volume"] [data-slot="hreq-mini-current"]').textContent).toBe('hreq.chart.perMin(511)')
    expect(charts.querySelectorAll('[data-slot="chart"]').length).toBe(3)
  })

  it('geçmiş boşsa grafik yerine "veri toplanıyor"', () => {
    renderSection({ httpMetrics: { ...HTTP, history: [] } })
    expect(document.querySelector('[data-slot="http-charts"]')).toBeNull()
    expect(screen.getByText('hreq.chart.collecting')).toBeInTheDocument()
  })
})

describe('HttpSection — en çok hata / en yavaş listeleri', () => {
  it('satırlar uç adını taşıyan düğmeler; tıklanınca gezgin o uca odaklı açılır', () => {
    const { onOpenExplorer } = renderSection()
    const errors = document.querySelector('[data-slot="hreq-top-errors"]')
    const row = within(errors).getByRole('button', { name: 'hreq.top.open(POST /api/auth/login)' })
    expect(row.textContent).toContain('hreq.top.errLine(30|')
    fireEvent.click(row)
    expect(onOpenExplorer).toHaveBeenLastCalledWith({ endpoint: 'POST /api/auth/login' })
    const slow = document.querySelector('[data-slot="hreq-top-slowest"]')
    expect(within(slow).getAllByRole('button').map((b) => b.getAttribute('data-endpoint')))
      .toEqual(['GET /api/reports/{id}', 'POST /api/monitoring/http/{id}/check'])
  })

  it('hata veren uç yoksa olumlu boş durum; top_endpoints gelmezse (eski sunucu / DB hatası) listeler hiç çizilmez', () => {
    renderSection({ httpMetrics: { ...HTTP, top_endpoints: { slowest: [], errors: [] } } })
    expect(within(document.querySelector('[data-slot="hreq-top-errors"]')).getByText('hreq.top.noErrors')).toBeInTheDocument()
    expect(within(document.querySelector('[data-slot="hreq-top-slowest"]')).getByText('hreq.top.noData')).toBeInTheDocument()
  })

  it('top_endpoints null → liste yok, "en yavaş uç" kutucuğu eylemsiz', () => {
    const { onOpenExplorer } = renderSection({ httpMetrics: { ...HTTP, top_endpoints: null } })
    expect(document.querySelector('[data-slot="hreq-top"]')).toBeNull()
    expect(within(tile('slowest')).getByText('—')).toBeInTheDocument()
    fireEvent.click(tile('slowest'))                                // yalnız açıklama balonu — gezgin açılmaz
    expect(onOpenExplorer).not.toHaveBeenCalled()
  })
})

describe('HttpSection — hata durumu', () => {
  it('uç düşerse hata bloğu + Tekrar dene', () => {
    const { onRetry } = renderSection({ error: true })
    expect(document.querySelector('[data-slot="empty"][data-tone="danger"]')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'db.retry' }))
    expect(onRetry).toHaveBeenCalled()
  })
})
