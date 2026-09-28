import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import SystemHealth from '../components/admin/SystemHealth.jsx'

vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => true, canExecute: () => true, perms: {} }),
}))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => false }))

/**
 * Sistem Sağlığı → HTTP istekleri bölümü → İstek Gezgini penceresi bağlantısı (2026-09-28). Bölüm ve gezginin kendi
 * testleri ayrı dosyalarda; burası SystemHealth'in pencereyi doğru açtığını (süzgeçsiz / bir uca odaklı) ve pencerenin
 * sabit boyut sözleşmesini taşıdığını pinler. `api.admin` vekil: sayılmayan her uç boş başarı döner.
 */
const { adminProxy } = vi.hoisted(() => {
  const target = {
    getSystemHealth: vi.fn(), getMetrics: vi.fn(), getHttpMetrics: vi.fn(), getHttpMetricsOverview: vi.fn(),
    pushLog: { summary: vi.fn(() => Promise.resolve({ success: true, data: { kpi: {} } })) },
  }
  return {
    adminProxy: new Proxy(target, {
      get(t, prop) {
        if (prop in t || typeof prop === 'symbol') return t[prop]
        t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
        return t[prop]
      },
    }),
  }
})
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: { admin: adminProxy, runScheduler: vi.fn(() => Promise.resolve({ success: true })) },
}))
import { api } from '../api/client'

const minute = (i) => new Date(Date.UTC(2026, 8, 28, 9, i)).toISOString().slice(0, 19)
const HTTP = {
  summary: { total_requests: 1200, total_errors: 6, error_rate_pct: 0.5, avg_ms: 80, max_ms: 900, p95_ms: 200, p99_ms: 600,
    req_per_min: 100, peak_req_per_min: 140 },
  history: Array.from({ length: 12 }, (_, i) => ({ ts: minute(i), count: 100, errors: i === 2 ? 6 : 0, avg_ms: 80, p95_ms: 200 })),
  top_endpoints: {
    window_hours: 24, endpoint_count: 3, errors: [],
    slowest: [{ endpoint: 'GET /api/reports/{id}', method: 'GET', path: '/api/reports/{id}', count: 30, errors: 0, error_rate_pct: 0,
      avg_ms: 700, max_ms: 900, p50_ms: 600, p95_ms: 880, p99_ms: 900, status_2xx: 30, last_seen: minute(10) }],
  },
}

beforeEach(() => {
  vi.clearAllMocks()
  api.admin.getSystemHealth.mockResolvedValue({ success: true, data: { scheduler: {}, network: {}, build: {} } })
  api.admin.getMetrics.mockResolvedValue({ success: true, data: [] })
  api.admin.getHttpMetrics.mockResolvedValue({ success: true, data: HTTP })
  api.admin.getHttpMetricsOverview.mockResolvedValue({ success: true, data: { summary: { total: 0 }, data: [], endpoints: [] } })
  window.history.replaceState(null, '', '/?tab=health&sec=http')
})
afterEach(() => { window.history.replaceState(null, '', '/') })

async function openSection() {
  render(<SystemHealth systemRole="ADMIN" globalAdmin />)
  await waitFor(() => expect(document.querySelector('[data-slot="hreq-section"]')).not.toBeNull())
}

describe('SystemHealth → İstek Gezgini penceresi', () => {
  it('bölüm çağrısı pencereyi açar: başlık "Request Explorer", SABİT boyut + yalnız gövde kayar; gezgin tembel yüklenir', async () => {
    await openSection()
    fireEvent.click(screen.getByRole('button', { name: 'Open request explorer' }))
    const dialog = await screen.findByRole('dialog', { name: 'Request Explorer' })
    expect(dialog).toHaveAttribute('data-scroll-body', 'true')
    expect(dialog.className).toMatch(/h-\[calc\(100dvh-2rem\)\]/)
    await waitFor(() => expect(api.admin.getHttpMetricsOverview).toHaveBeenCalled())
    expect(api.admin.getHttpMetricsOverview.mock.calls[0][0].endpoint).toBeUndefined()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Request Explorer' })).toBeNull())
  })

  it('"en yavaş uç" kutucuğu pencereyi o uca ODAKLI açar (süzgeç çipi + son 24 saat)', async () => {
    await openSection()
    fireEvent.click(screen.getByRole('button', { name: /Slowest endpoint: GET \/api\/reports\/\{id\}/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Request Explorer' })
    await waitFor(() => expect(api.admin.getHttpMetricsOverview).toHaveBeenCalled())
    const first = api.admin.getHttpMetricsOverview.mock.calls[0][0]
    expect(first.endpoint).toBe('GET /api/reports/{id}')
    expect((Date.parse(first.to + 'Z') - Date.parse(first.from + 'Z')) / 60000).toBe(1440)
    expect(await within(dialog).findByRole('button', { name: 'Remove filter: GET /api/reports/{id}' })).toBeInTheDocument()
  })
})
