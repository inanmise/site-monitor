import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import MonitoringOverviewPage, { filterRows, successPct } from '../components/MonitoringOverviewPage.jsx'

/**
 * İzleme Panosu (2026-09-30): KPI şeridi, tür kartları, izleme listesi ve süzgeçler; tıklamalar izleme sayfasına /
 * Alarm Geçmişi'ne `sm:navigate` ile gider.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ monitoring: { getOverview: vi.fn() } }),
}))
import { api } from '../api/client'

const TYPE = (type, extra = {}) => ({ type, total: 0, active: 0, paused: 0, deleted: 0, down: 0, stale: 0, unknown: 0,
  checks_window: 0, failed_window: 0, success_rate_window: null, open_alerts: 0, open_critical: 0, resolved_window: 0, last_checked_at: null, ...extra })

const DATA = {
  success: true,
  data: {
    generated_at: '2026-09-30T14:00:00', window_hours: 24,
    totals: { total: 4, active: 3, paused: 1, deleted: 0, down: 1, stale: 1, unknown: 0, checks_window: 120, failed_window: 6, open_alerts: 1, open_critical: 1, resolved_window: 2, last_checked_at: '2026-09-30T13:59:00' },
    types: [
      TYPE('http', { total: 2, active: 2, down: 1, checks_window: 100, failed_window: 6, success_rate_window: 94.0, open_alerts: 1, open_critical: 1, last_checked_at: '2026-09-30T13:59:00', resolved_window: 2 }),
      TYPE('ping', { total: 1, active: 1, stale: 1, checks_window: 20, success_rate_window: 100.0, last_checked_at: '2026-09-30T10:00:00' }),
      TYPE('port'), TYPE('dns'), TYPE('domain'), TYPE('keyword'), TYPE('page'), TYPE('pagespeed'),
      TYPE('scripted', { total: 1, paused: 1 }),
    ],
    monitors: [
      { type: 'http', id: 1, name: 'API sağlık', target: 'https://api.example.com/health', team_id: 14, team_name: 'SY', active: true, deleted: false, status: 'down', last_checked_at: '2026-09-30T13:59:00', last_ok: false, response_ms: 800, last_error: 'HTTP 503', interval_seconds: 300, open_alerts: 1, open_alert_level: 'CRITICAL', checks_window: 60, failed_window: 6 },
      { type: 'http', id: 2, name: 'Portal', target: 'https://portal.example.com', team_id: 14, team_name: 'SY', active: true, deleted: false, status: 'up', last_checked_at: '2026-09-30T13:58:00', last_ok: true, response_ms: 120, interval_seconds: 300, open_alerts: 0, checks_window: 40, failed_window: 0 },
      { type: 'ping', id: 3, name: 'GW', target: '10.0.0.1', team_id: 7, team_name: 'Ağ', active: true, deleted: false, status: 'stale', last_checked_at: '2026-09-30T10:00:00', last_ok: true, response_ms: 3, interval_seconds: 60, open_alerts: 0, checks_window: 20, failed_window: 0 },
      { type: 'scripted', id: 4, name: 'Login akışı', target: 'Login akışı', team_id: 14, team_name: 'SY', active: false, deleted: false, status: 'paused', last_checked_at: null, open_alerts: 0, checks_window: 0, failed_window: 0 },
    ],
  },
}

describe('İzleme Panosu', () => {
  beforeEach(() => { vi.clearAllMocks(); window.history.replaceState({}, '', '/?tab=monitoring'); try { localStorage.clear() } catch { /* yok */ } })

  it('KPI şeridi ve tür kartları sunucu özetini gösterir; sorunlu tür kartı kötü tonda', async () => {
    api.monitoring.getOverview.mockResolvedValue(DATA)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(api.monitoring.getOverview).toHaveBeenCalledWith(24))
    const kpi = (key) => container.querySelector(`[data-slot="stat-item"][data-key="${key}"] [data-slot="stat-value"]`).textContent
    expect(kpi('total')).toBe('4')
    expect(kpi('down')).toBe('1')
    expect(kpi('stale')).toBe('1')
    expect(kpi('paused')).toBe('1')
    expect(kpi('alerts')).toBe('1')
    expect(kpi('resolved')).toBe('2')
    const cards = container.querySelectorAll('[data-slot="mo-type-card"]')
    expect(cards).toHaveLength(9)
    expect(container.querySelector('[data-slot="mo-type-card"][data-type="http"]')).toHaveAttribute('data-tone', 'bad')
    expect(container.querySelector('[data-slot="mo-type-card"][data-type="ping"]')).toHaveAttribute('data-tone', 'warn')
    expect(container.querySelector('[data-slot="mo-type-card"][data-type="port"]')).toHaveAttribute('data-tone', 'ok')
    // Liste: sorunlu en üstte
    const rows = [...container.querySelectorAll('[data-slot="mo-row"]')]
    expect(rows.map((r) => r.getAttribute('data-status'))).toEqual(['down', 'stale', 'up', 'paused'])
  })

  it('tür kartına tıklayınca liste türe süzülür; KPI "Sorunlu" durum süzgecidir; temizle hepsini kaldırır', async () => {
    api.monitoring.getOverview.mockResolvedValue(DATA)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(container.querySelectorAll('[data-slot="mo-row"]').length).toBe(4))
    const httpCard = container.querySelector('[data-slot="mo-type-card"][data-type="http"]')
    fireEvent.click(within(httpCard).getAllByRole('button')[0])
    await waitFor(() => expect(container.querySelectorAll('[data-slot="mo-row"]').length).toBe(2))
    expect([...container.querySelectorAll('[data-slot="mo-row"]')].every((r) => r.getAttribute('data-type') === 'http')).toBe(true)
    fireEvent.click(container.querySelector('[data-slot="stat-item"][data-key="down"]'))
    await waitFor(() => expect(container.querySelectorAll('[data-slot="mo-row"]').length).toBe(1))
    expect(container.querySelector('[data-slot="mo-status-filter"]').value).toBe('down')
    fireEvent.click(screen.getByRole('button', { name: /süzgeçleri temizle|clear filters/i }))
    await waitFor(() => expect(container.querySelectorAll('[data-slot="mo-row"]').length).toBe(4))
  })

  it('satır eylemleri: "aç" izleme sayfasına arama ile, "alarmlar" Alarm Geçmişi\'ne türe süzülmüş gider; pencere seçici 7 güne geçince yeniden yükler', async () => {
    api.monitoring.getOverview.mockResolvedValue(DATA)
    const events = []
    const onNav = (e) => events.push(e.detail)
    window.addEventListener('sm:navigate', onNav)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(container.querySelectorAll('[data-slot="mo-row"]').length).toBe(4))
    fireEvent.click(screen.getByRole('button', { name: /API sağlık — (izleme sayfasında aç|open on its monitor page)/i }))
    fireEvent.click(screen.getByRole('button', { name: /API sağlık — (açık alarmlarına git|go to its open alerts)/i }))
    expect(events).toEqual([
      { tab: 'http', params: { q: 'https://api.example.com/health' } },
      { tab: 'alerthistory', params: { view: 'open', src: 'http', q: 'https://api.example.com/health' } },
    ])
    window.removeEventListener('sm:navigate', onNav)
    fireEvent.click(screen.getByRole('radio', { name: /son 7 gün|last 7 days/i }))
    await waitFor(() => expect(api.monitoring.getOverview).toHaveBeenCalledWith(168))
  })

  it('filterRows: tür/durum/takım/arama süzer ve sorunlu → gecikmiş → bilinmiyor → sağlıklı → duraklatılmış → silinmiş sıralar; successPct 0–100 kırpar', () => {
    const rows = DATA.data.monitors
    expect(filterRows(rows, { q: 'portal' }).map((r) => r.id)).toEqual([2])
    expect(filterRows(rows, { team: '7' }).map((r) => r.id)).toEqual([3])
    expect(filterRows(rows, { status: 'paused' }).map((r) => r.id)).toEqual([4])
    expect(filterRows(rows).map((r) => r.status)).toEqual(['down', 'stale', 'up', 'paused'])
    expect(successPct({ success_rate_window: 101 })).toBe(100)
    expect(successPct({ success_rate_window: null })).toBeNull()
  })

  it('yükleme hatası: hata bloğu + Yenile; veri gelince liste', async () => {
    api.monitoring.getOverview.mockResolvedValueOnce({ success: false, error: 'boom' }).mockResolvedValue(DATA)
    const { container } = render(<MonitoringOverviewPage />)
    expect(await screen.findByText('boom')).toBeInTheDocument()
    fireEvent.click(screen.getAllByRole('button', { name: /yenile|refresh/i })[0])
    await waitFor(() => expect(container.querySelectorAll('[data-slot="mo-row"]').length).toBe(4))
  })
})
