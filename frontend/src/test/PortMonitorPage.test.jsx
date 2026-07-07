import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import PortMonitorPage from '../components/PortMonitorPage.jsx'

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: {
    monitoring: {
      getPortMonitors:   vi.fn(),
      getPortHistory:    vi.fn(),
      getPortResponseSeries: vi.fn(),
      getMonitorNotes:   vi.fn(),
      createPortMonitor: vi.fn(),
      updatePortMonitor: vi.fn(),
      deletePortMonitor: vi.fn(),
      triggerPortCheck:  vi.fn(),
    },
    admin: { getTeams: vi.fn(), getAlerts: vi.fn() },
  },
}))
import { api } from '../api/client'

const monitor = {
  id: 1, name: 'mail', host: '10.0.0.1', team_name: 'SY-A', group_name: 'Mail',
  port: 25, protocol: 'TCP', status: 'open', response_ms: 3, active: true,
  interval_seconds: 60, timeout_ms: 5000, checked_at: '2026-06-24T00:00:00',
}

describe('PortMonitorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getPortMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getPortHistory.mockResolvedValue({ success: true, data: { checks: [], total: 0, down: 0 } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 3, name: 'SY-A' }] })
  })

  it('port monitörünü (host) listeler', async () => {
    render(<PortMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPortMonitors).toHaveBeenCalled())
    expect(await screen.findByText('10.0.0.1')).toBeInTheDocument()
  })

  it('Yeni Monitör butonu ADMIN için modal açar (host/port/grup alanları)', async () => {
    render(<PortMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPortMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitor/i }))
    expect(screen.getByText(/new port monitor|yeni port monit/i)).toBeInTheDocument()
    expect(screen.getByText(/^Group$|^Grup$/)).toBeInTheDocument()
  })

  it('host+port girip kaydet → createPortMonitor doğru payload ile çağrılır', async () => {
    api.monitoring.createPortMonitor.mockResolvedValue({ success: true, data: {} })
    render(<PortMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPortMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitor/i }))
    fireEvent.change(screen.getByPlaceholderText(/1\.2\.3\.4/), { target: { value: 'mail.example.com' } })
    fireEvent.change(screen.getAllByRole('spinbutton')[0], { target: { value: '993' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createPortMonitor).toHaveBeenCalled())
    const payload = api.monitoring.createPortMonitor.mock.calls[0][0]
    expect(payload.host).toBe('mail.example.com')
    expect(payload.port).toBe(993)
  })

  it('detay modali 4 sekme (Kontrol/Alarm/Grafik/Rehber) gösterir; Rehber sekmesi MonitorNotes\'u host:port hedefiyle yükler', async () => {
    api.monitoring.getMonitorNotes.mockResolvedValue({ success: true, data: { guide: null, notes: [] } })
    render(<PortMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPortMonitors).toHaveBeenCalled())
    fireEvent.click(await screen.findByText('10.0.0.1'))            // satıra tıkla → detay modali açılır
    // 4 sekmeli parite çubuğu (ping/keyword ile aynı)
    expect(screen.getByRole('button', { name: /check history|kontrol geçmişi/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /alarm history|alarm geçmişi/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /response chart|süre grafiği/i })).toBeInTheDocument()
    // Rehber & Notlar sekmesi → MonitorNotes type=PORT, target=host:port
    fireEvent.click(screen.getByRole('button', { name: /guide & notes|rehber & notlar/i }))
    // MonitorNotes lazy import + mount → getMonitorNotes(type, target); dinamik import ilk seferde yavaş olabilir.
    await waitFor(() => expect(api.monitoring.getMonitorNotes).toHaveBeenCalledWith('PORT', '10.0.0.1:25'), { timeout: 5000 })
  })
})
