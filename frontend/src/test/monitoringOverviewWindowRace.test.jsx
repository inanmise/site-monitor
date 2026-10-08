import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'
import MonitoringOverviewPage from '../components/MonitoringOverviewPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ monitoring: { getOverview: vi.fn() } }),
}))
import { api } from '../api/client'

/**
 * İzleme Panosu pencere yarışı (2026-10-09): `load` sırasızdı. Pencere 24 sa ↔ 7 gün değişirken eski pencerenin GEÇ
 * gelen yanıtı yeni pencerenin verisini eziyordu — seçici "7 gün" derken liste 24 saatlik veriyi gösteriyordu. Artık yalnız
 * EN SON istek yazar (UptimePage.fetchOverview deseni).
 */
const TYPE = (type) => ({ type, total: 0, active: 0, paused: 0, deleted: 0, down: 0, stale: 0, unknown: 0,
  checks_window: 0, failed_window: 0, success_rate_window: null, open_alerts: 0, open_critical: 0, resolved_window: 0, last_checked_at: null })
const payload = (hours, name) => ({
  success: true,
  data: {
    generated_at: '2026-10-09T10:00:00', window_hours: hours,
    totals: { total: 1, active: 1, paused: 0, deleted: 0, down: 0, stale: 0, unknown: 0, checks_window: 10, failed_window: 0,
      open_alerts: 0, open_critical: 0, resolved_window: 0, last_checked_at: '2026-10-09T09:59:00', success_rate_window: 100 },
    types: ['http', 'ping', 'port', 'dns', 'domain', 'keyword', 'page', 'pagespeed', 'scripted'].map(TYPE),
    monitors: [{ type: 'http', id: 1, name, target: 'https://a.example.com', team_id: 14, team_name: 'SY', active: true, deleted: false,
      status: 'up', last_checked_at: '2026-10-09T09:59:00', last_ok: true, response_ms: 10, interval_seconds: 300, open_alerts: 0,
      checks_window: 10, failed_window: 0, success_rate_window: 100 }],
  },
})
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const rowNames = (c) => [...c.querySelectorAll('[data-slot="mo-row"]')].map((r) => r.textContent)

describe('İzleme Panosu — pencere değişiminde yalnız EN SON yanıt yazar', () => {
  beforeEach(() => { vi.clearAllMocks(); window.history.replaceState({}, '', '/?tab=monitoring'); try { localStorage.clear() } catch { /* yok */ } })

  it('24 sa isteği yoldayken 7 güne geçilir: 7 günlük yanıt gelir, 24 saatin GEÇ yanıtı onu ezmez', async () => {
    const d24 = deferred()
    const d168 = deferred()
    api.monitoring.getOverview.mockImplementation((hours) => (hours === 168 ? d168.p : d24.p))
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(api.monitoring.getOverview).toHaveBeenCalledWith(24))
    fireEvent.click(screen.getByRole('radio', { name: /son 7 gün|last 7 days/i }))
    await waitFor(() => expect(api.monitoring.getOverview).toHaveBeenCalledWith(168))

    await act(async () => { d168.resolve(payload(168, 'Yedi-gunluk-veri')) })
    await waitFor(() => expect(rowNames(container).join(' ')).toContain('Yedi-gunluk-veri'))
    await act(async () => { d24.resolve(payload(24, 'Yirmi-dort-saatlik-veri')) })
    await act(async () => { await new Promise((r) => setTimeout(r, 0)) })
    expect(rowNames(container).join(' ')).toContain('Yedi-gunluk-veri')
    expect(rowNames(container).join(' ')).not.toContain('Yirmi-dort-saatlik-veri')
    expect(screen.getByRole('radio', { name: /son 7 gün|last 7 days/i })).toHaveAttribute('aria-checked', 'true')
  })

  it('bayat yanıtın HATASI da yeni pencerenin verisini silmez / hata bandı açmaz', async () => {
    const d24 = deferred()
    api.monitoring.getOverview.mockImplementation((hours) => (hours === 168 ? Promise.resolve(payload(168, 'Yedi-gunluk-veri')) : d24.p))
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(api.monitoring.getOverview).toHaveBeenCalledWith(24))
    fireEvent.click(screen.getByRole('radio', { name: /son 7 gün|last 7 days/i }))
    await waitFor(() => expect(rowNames(container).join(' ')).toContain('Yedi-gunluk-veri'))
    await act(async () => { d24.resolve({ success: false, error: 'eski pencere düştü' }) })
    await act(async () => { await new Promise((r) => setTimeout(r, 0)) })
    expect(screen.queryByText('eski pencere düştü')).toBeNull()
    // Canlılık rozeti "güncellenemedi"e DÖNMEZ: hata yeni pencerenin değil, terk edilmiş isteğin
    expect(container.querySelector('[data-slot="mo-live"]')).toHaveAttribute('data-state', 'live')
    expect(rowNames(container).join(' ')).toContain('Yedi-gunluk-veri')
  })
})
