import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'
import DnsMonitorPage from '../components/DnsMonitorPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      getDnsMonitors: vi.fn(),
      getDnsDetails: vi.fn(),
      getCheckHistory: vi.fn(),
    },
    admin: {},
  }),
}))
import { api } from '../api/client'

/**
 * DNS derin bağlantısı aralığı ve sekmeyi korur (2026-10-09, canlı e2e'nin yakaladığı hata): sayfanın adres senkronu,
 * pencere henüz açılmamışken (liste yüklenirken) `range` / `mtab`'ı siliyordu → `?monitor=7&range=30` bağlantısı
 * 1 günle açılıyordu. Artık yalnız pencere bir kez açılıp KAPANINCA silinir.
 */
const row = (i) => ({ id: i, name: `m${i}.example.com`, domain: `m${i}.example.com`, record_type: 'A', value: '203.0.113.10',
  standalone: true, active: true, team_id: 5, team_name: 'Takım A', checked_at: '2026-10-09T08:00:00', interval_seconds: 300 })
const HIST = { success: true, data: { items: [], counts: { total: 0, fail: 0, changed: 0, errors: 0 }, buckets: [], alerts: [],
  range: { from: '2026-09-09T00:00:00', to: '2026-10-09T00:00:00' }, total: 0, page: 0, size: 50 } }
const param = (k) => new URLSearchParams(window.location.search).get(k)
const props = { systemRole: 'ADMIN', teamId: 5, teamName: 'Takım A', myTeams: [{ id: 5, name: 'Takım A' }], globalAdmin: false }

describe('DnsMonitorPage — derin bağlantıda aralık korunur', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    api.monitoring.getDnsMonitors.mockImplementation(() => new Promise((r) => setTimeout(() => r({ success: true, data: [row(7), row(8)] }), 700)))
    api.monitoring.getDnsDetails.mockResolvedValue({ success: true, data: { live: { success: true, records: [] }, records: {} } })
    api.monitoring.getCheckHistory.mockResolvedValue(HIST)
  })
  afterEach(() => window.history.replaceState({}, '', '/'))

  it('liste geç gelse de ?range=30&hst=fail ile açılan pencerenin geçmişi 30 günle istenir; adres korunur', async () => {
    window.history.replaceState({}, '', '/?tab=dns&monitor=7&hst=fail&range=30')
    render(<DnsMonitorPage {...props} />)
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled(), { timeout: 4000 })
    const calls = api.monitoring.getCheckHistory.mock.calls
    expect(calls[0][0]).toBe('dns')
    expect(calls[0][2]).toEqual(expect.objectContaining({ days: 30, status: 'fail' }))
    expect(screen.getByRole('button', { name: /^(Last 30 days|Son 30 gün)$/ })).toHaveAttribute('aria-pressed', 'true')
    expect(param('range')).toBe('30')
  })
})
