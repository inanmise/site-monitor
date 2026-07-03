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

  it('istatistik panosu: sayımlar doğru + karta tıklayınca grid filtrelenir/temizlenir', async () => {
    api.monitoring.getPingMonitors.mockResolvedValue({ success: true, data: [
      { id: 1, host: '10.0.0.1', status: 'up',   active: true },
      { id: 2, host: '10.0.0.2', status: 'down', active: true, active_alarm: true, alarm_acknowledged: false, alarm_level: 'CRITICAL' },
      { id: 3, host: '10.0.0.3', status: 'up',   active: false },
    ] })
    const { container } = render(<PingMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPingMonitors).toHaveBeenCalled())
    await screen.findByText('10.0.0.1')

    // Pano varsayılan KAPALI → aç/kapa çubuğuna tıkla
    expect(container.querySelector('.stats-panel')).toBeNull()
    fireEvent.click(container.querySelector('.stats-collapse-bar'))
    expect(container.querySelector('.stats-panel')).not.toBeNull()

    // Sayımlar (dil-bağımsız: renk sınıfına göre)
    expect(container.querySelector('.stat-item-total .stat-value').textContent).toBe('3')
    expect(container.querySelector('.stat-item-critical .stat-value').textContent).toBe('1')  // Erişilemiyor
    expect(container.querySelector('.stat-item-high .stat-value').textContent).toBe('1')       // Aktif alarm
    expect(container.querySelector('.stat-item-paused .stat-value').textContent).toBe('1')      // Duraklatılmış

    // "Erişilemiyor" kartına tıkla → yalnız down host kalır
    fireEvent.click(container.querySelector('.stat-item-critical'))
    await waitFor(() => expect(screen.queryByText('10.0.0.1')).not.toBeInTheDocument())
    expect(screen.getByText('10.0.0.2')).toBeInTheDocument()
    expect(screen.queryByText('10.0.0.3')).not.toBeInTheDocument()

    // Tekrar tıkla → filtre temizlenir (hepsi geri gelir)
    fireEvent.click(container.querySelector('.stat-item-critical'))
    await screen.findByText('10.0.0.1')
    expect(screen.getByText('10.0.0.3')).toBeInTheDocument()
  })
})
