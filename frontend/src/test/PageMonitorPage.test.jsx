import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import PageMonitorPage from '../components/PageMonitorPage.jsx'

vi.mock('../api/client', () => ({
  formatDate:    (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: {
    monitoring: {
      listGroups:        vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults:   vi.fn(() => Promise.resolve({ success: true, data: { page: {} } })),
      getPageMonitors:   vi.fn(),
      getPageHistory:    vi.fn(),
      getPageIssues:     vi.fn(),
      createPageMonitor: vi.fn(),
      updatePageMonitor: vi.fn(),
      deletePageMonitor: vi.fn(),
      triggerPageCheck:  vi.fn(),
      testPage:          vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  },
}))
import { api } from '../api/client'

const monitor = {
  id: 1, name: 'Akbank', url: 'https://www.akbank.com/', mode: 'SINGLE_PAGE',
  group_name: 'X Sistemleri', team_name: 'SY-A', status: 'DEGRADED',
  broken_resources: 2, mixed_content_count: 0, total_resources: 12, active: true, checked_at: '2026-06-24T00:00:00',
}

describe('PageMonitorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getPageHistory.mockResolvedValue({ success: true, data: { checks: [], total: 0, down: 0 } })
    api.monitoring.getPageIssues.mockResolvedValue({ success: true, data: [] })
  })

  it('izleme kartını (url) listeler', async () => {
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    expect(await screen.findByText('https://www.akbank.com/')).toBeInTheDocument()
  })

  it('Yeni modal açılır (form alanları görünür)', async () => {
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni izleme/i }))
    expect(screen.getByPlaceholderText('https://example.com')).toBeInTheDocument()
  })

  it('Test butonu testPage çağırır ve sonucu gösterir', async () => {
    // Arka plan kartı OK olsun → "Degraded" yalnız test-sonucu banner'ında görünsün (tekil eşleşme).
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [{ ...monitor, status: 'OK' }] })
    api.monitoring.testPage.mockResolvedValue({
      success: true,
      data: { status: 'DEGRADED', total_resources: 10, broken_resources: 2, mixed_content_count: 0, http_status: 200 },
    })
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni izleme/i }))
    fireEvent.change(screen.getByPlaceholderText('https://example.com'), { target: { value: 'https://x.example.com' } })
    fireEvent.click(screen.getByRole('button', { name: /^test$|test et/i }))
    await waitFor(() => expect(api.monitoring.testPage).toHaveBeenCalled())
    expect(await screen.findByText(/degraded|bozulmuş/i)).toBeInTheDocument()
  })

  it('karta tıkla → detay modalında Sorunlar + Grafik sekmeleri; issues yüklenir', async () => {
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByText('https://www.akbank.com/'))
    await waitFor(() => expect(api.monitoring.getPageIssues).toHaveBeenCalled())
    expect(screen.getByRole('button', { name: /issues|sorunlar/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /chart|grafik/i })).toBeInTheDocument()
  })

  it('istatistik panosu: OK/DEGRADED/DOWN ayrımı + filtreleme', async () => {
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [
      { id: 1, url: 'https://a.example.com', status: 'OK',       active: true },
      { id: 2, url: 'https://b.example.com', status: 'DEGRADED', active: true },
      { id: 3, url: 'https://c.example.com', status: 'DOWN',     active: true, active_alarm: true, alarm_acknowledged: false, alarm_level: 'CRITICAL' },
    ] })
    const { container } = render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    await screen.findByText('https://a.example.com')

    expect(container.querySelector('.stats-panel')).toBeNull()
    fireEvent.click(container.querySelector('.stats-collapse-bar'))
    expect(container.querySelector('.stats-panel')).not.toBeNull()

    expect(container.querySelector('.stat-item-total .stat-value').textContent).toBe('3')
    expect(container.querySelector('.stat-item-valid .stat-value').textContent).toBe('1')     // OK
    expect(container.querySelector('.stat-item-warning .stat-value').textContent).toBe('1')   // DEGRADED
    expect(container.querySelector('.stat-item-critical .stat-value').textContent).toBe('1')  // DOWN

    fireEvent.click(container.querySelector('.stat-item-warning'))
    await waitFor(() => expect(screen.queryByText('https://a.example.com')).not.toBeInTheDocument())
    expect(screen.getByText('https://b.example.com')).toBeInTheDocument()
    expect(screen.queryByText('https://c.example.com')).not.toBeInTheDocument()

    fireEvent.click(container.querySelector('.stat-item-warning'))
    await screen.findByText('https://a.example.com')
  })
})
