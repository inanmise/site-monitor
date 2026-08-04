import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import HttpMonitorPage from '../components/HttpMonitorPage.jsx'

vi.mock('../api/client', () => ({
  formatDate:    (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: {
    monitoring: {
      listGroups:        vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults:   vi.fn(() => Promise.resolve({ success: true, data: { http: {} } })),
      getHttpMonitors:   vi.fn(),
      getHttpHistory:    vi.fn(),
      createHttpMonitor: vi.fn(),
      updateHttpMonitor: vi.fn(),
      deleteHttpMonitor: vi.fn(),
      triggerHttpCheck:  vi.fn(),
      testHttp:          vi.fn(),
    },
    admin: { getTeams: vi.fn(), getAlerts: vi.fn() },
  },
}))
import { api } from '../api/client'

const monitor = {
  id: 1, name: 'Akbank', url: 'https://www.akbank.com/', method: 'GET', expected_status: '201-204',
  group_name: 'X Sistemleri', team_id: 5, team_name: 'SY-A', status: 'up', http_status: 200, response_ms: 12,
  interval_seconds: 600, timeout_ms: 7000, active: true, checked_at: '2026-06-24T00:00:00',
}

describe('HttpMonitorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getHttpHistory.mockResolvedValue({ success: true, data: { checks: [], total: 0, down: 0 } })
    api.monitoring.listGroups.mockResolvedValue({ success: true, data: [] })
    api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { http: {} } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
    api.monitoring.createHttpMonitor.mockResolvedValue({ success: true, data: {} })
  })

  it('izleme kartını (url) listeler', async () => {
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    expect(await screen.findByText('https://www.akbank.com/')).toBeInTheDocument()
  })

  it('Kopyala: TÜM kullanıcı ayarları birebir kopyalanır (yalnız ad "(Kopya)" olur)', async () => {
    // Her alan varsayılandan FARKLI → bir alan formFrom'dan düşerse veya save() içinde
    // sessizce varsayılana dönerse tam-payload karşılaştırması kırılır.
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [{
      id: 1, name: 'Akbank', url: 'https://www.akbank.com/', status: 'up', checked_at: '2026-06-24T00:00:00',
      method: 'POST', expected_status: '201-204', follow_redirects: false, verify_ssl: true,
      group_name: 'Kurumsal', team_id: 5, team_name: 'SY-A', tags: 'prod,kritik', notify_email: false,
      check_ssl_errors: true, ssl_expiry_reminders: true, domain_expiry_reminders: true,
      ssl_reminder_days: '45,20,5', domain_reminder_days: '60,30,10',
      interval_seconds: 600, timeout_ms: 7000,
      confirm_attempts: 5, confirm_interval_seconds: 45, recovery_checks: 4, recovery_interval_seconds: 90,
      active: false,
    }] })

    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    await screen.findByText('https://www.akbank.com/')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))

    // Kopya rozeti + ipucu görünür (yeni-kayıt modu, kaynak belli)
    expect(document.querySelector('.mon-dup-badge')).not.toBeNull()
    expect(document.querySelector('.mon-dup-hint')).not.toBeNull()
    expect(screen.getByPlaceholderText('https://www.akbank.com/').value).toMatch(/\(Kopya\)$/)

    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createHttpMonitor).toHaveBeenCalled())
    expect(api.monitoring.updateHttpMonitor).not.toHaveBeenCalled()

    expect(api.monitoring.createHttpMonitor.mock.calls[0][0]).toEqual({
      name: 'Akbank (Kopya)', url: 'https://www.akbank.com/', method: 'POST',
      expectedStatus: '201-204', followRedirects: false, verifySsl: true,
      groupName: 'Kurumsal', teamId: 5, tags: 'prod,kritik', notifyEmail: false,
      checkSslErrors: true, sslExpiryReminders: true, domainExpiryReminders: true,
      sslReminderDays: '45,20,5', domainReminderDays: '60,30,10',
      intervalSeconds: 600, timeoutMs: 7000,
      confirmAttempts: 5, confirmIntervalSeconds: 45, recoveryChecks: 4, recoveryIntervalSeconds: 90,
      active: false,   // duraklatılmış kaynağın kopyası da pasif doğar
    })
  })

  it('Kopyala → değiştirmeden kaydet: backend "zaten izleniyor" hatası toast ile gösterilir, modal kapanmaz', async () => {
    api.monitoring.createHttpMonitor.mockResolvedValue({
      success: false,
      error: 'Bu URL bu takımda zaten izleniyor; mükerrer HTTP monitörü oluşturulamaz.',
    })
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    await screen.findByText('https://www.akbank.com/')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))

    await waitFor(() => expect(api.monitoring.createHttpMonitor).toHaveBeenCalled())
    expect(await screen.findByText(/zaten izleniyor/i)).toBeInTheDocument()
    // Modal açık kalır (veri kaybı yok) → Kopya rozeti hâlâ DOM'da
    expect(document.querySelector('.mon-dup-badge')).not.toBeNull()
  })
})
