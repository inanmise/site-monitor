import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import DomainMonitorPage from '../components/DomainMonitorPage.jsx'

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: {
    monitoring: {
      listGroups:          vi.fn(() => Promise.resolve({ success: true, data: [] })),
      getDomainMonitors:   vi.fn(),
      getDomainHistory:    vi.fn(),
      createDomainMonitor: vi.fn(),
      updateDomainMonitor: vi.fn(),
      deleteDomainMonitor: vi.fn(),
      triggerDomainCheck:  vi.fn(),
      testDomain:          vi.fn(),
      monitorDefaults:     vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  },
}))
import { api } from '../api/client'

const monitor = {
  id: 1, name: 'akbank', domain: 'akbank.com.tr', team_name: 'SY-A', group_name: 'Kurumsal',
  status: 'OK', source: 'RDAP', days_remaining: 120, expiry_date: '2026-08-13', registrar: 'TR Registry',
  status_codes: ['clientTransferProhibited'], nameservers: ['ns1.akbank.com.tr'], ns_resolves: true,
  active: true, interval_seconds: 86400, warning_days: 30, critical_days: 7, checked_at: '2026-07-10T00:00:00',
}

describe('DomainMonitorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getDomainHistory.mockResolvedValue({ success: true, data: { checks: [], total: 0, down: 0 } })
    api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { domain: { intervalSeconds: 86400, warningDays: 30, criticalDays: 7, thresholds: '60,30,14,7,3,1' } } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 3, name: 'SY-A' }] })
  })

  it('alan adı monitörünü listeler', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    expect(await screen.findByText('akbank.com.tr')).toBeInTheDocument()
    expect(screen.getByText('TR Registry')).toBeInTheDocument()
  })

  it('Yeni Monitör butonu ADMIN için modal açar (alan adı alanı)', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
    expect(screen.getByText(/new domain monitor|yeni alan adı monit/i)).toBeInTheDocument()
  })

  it('alan adı girip kaydet → createDomainMonitor doğru payload ile çağrılır', async () => {
    api.monitoring.createDomainMonitor.mockResolvedValue({ success: true, data: {} })
    render(<DomainMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)   // USER → takım otomatik dolar (zorunlu takım)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
    fireEvent.change(screen.getByPlaceholderText('example.com'), { target: { value: 'example.org' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createDomainMonitor).toHaveBeenCalled())
    expect(api.monitoring.createDomainMonitor.mock.calls[0][0].domain).toBe('example.org')
  })

  it('ADMIN: takım seçilmeden Kaydet devre dışı (zorunlu takım)', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
    fireEvent.change(screen.getByPlaceholderText('example.com'), { target: { value: 'example.org' } })
    expect(screen.getByRole('button', { name: /^save$|^kaydet$/i })).toBeDisabled()   // takım yok → engellendi
  })
})
