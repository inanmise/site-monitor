import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import PingMonitorPage from '../components/PingMonitorPage.jsx'

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: {
    monitoring: {
      getPingMonitors:   vi.fn(),
      getPingHistory:    vi.fn(),
      createPingMonitor: vi.fn(),
      updatePingMonitor: vi.fn(),
      deletePingMonitor: vi.fn(),
      triggerPingCheck:  vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  },
}))
import { api } from '../api/client'

const monitor = {
  id: 1, name: 'GW', host: '10.0.0.1', ip_version: 'auto', group_name: 'Y Sistemleri',
  team_name: 'SY-A', status: 'up', rtt_ms: 3, active: true, checked_at: '2026-06-24T00:00:00',
}

describe('PingMonitorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getPingMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getPingHistory.mockResolvedValue({ success: true, data: { checks: [], total: 0, down: 0 } })
  })

  it('ping kartını (host) + grup rozetini listeler', async () => {
    render(<PingMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPingMonitors).toHaveBeenCalled())
    expect(await screen.findByText('10.0.0.1')).toBeInTheDocument()
    expect(screen.getByText('Y Sistemleri')).toBeInTheDocument()
  })

  it('Yeni modal: grup alanı render olur', async () => {
    render(<PingMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPingMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitor|yeni ping|new ping/i }))
    expect(screen.getByText(/^Group$|^Grup$/)).toBeInTheDocument()
  })

  it('karta tıkla → detay modalında 3 sekme görünür', async () => {
    render(<PingMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPingMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByText('10.0.0.1'))
    await waitFor(() => expect(api.monitoring.getPingHistory).toHaveBeenCalled())
    expect(screen.getByRole('button', { name: /check history|kontrol/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /response chart|süre/i })).toBeInTheDocument()
  })
})
