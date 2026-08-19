import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from './test-utils.jsx'
import LoginAnomalySettings from '../components/admin/LoginAnomalySettings.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getLoginAnomalySettings: vi.fn(),
      saveLoginAnomalySettings: vi.fn(),
      testLoginAnomalyEmail: vi.fn(),
      getLoginAnomalyIncidents: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

const cfg = {
  enabled: true, window_minutes: 10, threshold_total: 20, threshold_per_account: 5,
  threshold_per_ip: 15, threshold_distinct_users_per_ip: 5, threshold_distinct_ips_per_account: 5,
  relative_multiplier: 3.0, baseline_hours: 24, relative_floor: 8, catchup_cap_minutes: 60,
  cooldown_minutes: 60, resolved_email_enabled: true, retention_days: 90,
  alert_recipients: '', system_admin_email: 'admin@x',
}

describe('LoginAnomalySettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getLoginAnomalySettings.mockResolvedValue({ success: true, data: cfg })
    api.admin.getLoginAnomalyIncidents.mockResolvedValue({ success: true, data: [] })
    api.admin.saveLoginAnomalySettings.mockResolvedValue({ success: true, data: cfg })
    api.admin.testLoginAnomalyEmail.mockResolvedValue({ success: true, data: { status: 'SENT', sent: true } })
  })

  it('ayarları yükler; genel-hacim eşiği + master toggle görünür', async () => {
    render(<LoginAnomalySettings />)
    await waitFor(() => expect(api.admin.getLoginAnomalySettings).toHaveBeenCalled())
    expect(await screen.findByDisplayValue('20')).toBeInTheDocument()   // threshold_total
    const toggles = screen.getAllByRole('checkbox')
    expect(toggles[0]).toBeChecked()                                    // enabled
  })

  it('Kaydet → saveLoginAnomalySettings çağırır', async () => {
    render(<LoginAnomalySettings />)
    await waitFor(() => expect(api.admin.getLoginAnomalySettings).toHaveBeenCalled())
    await screen.findByDisplayValue('20')
    fireEvent.click(screen.getByRole('button', { name: /^kaydet$|^save$/i }))
    await waitFor(() => expect(api.admin.saveLoginAnomalySettings).toHaveBeenCalledWith(
      expect.objectContaining({ threshold_total: 20, cooldown_minutes: 60 })))
  })

  it('test e-postası → testLoginAnomalyEmail çağırır', async () => {
    render(<LoginAnomalySettings />)
    await waitFor(() => expect(api.admin.getLoginAnomalySettings).toHaveBeenCalled())
    const emailInput = await screen.findByPlaceholderText(/test@/)
    fireEvent.change(emailInput, { target: { value: 'me@x.com' } })
    fireEvent.click(screen.getByRole('button', { name: /test gönder|send test/i }))
    await waitFor(() => expect(api.admin.testLoginAnomalyEmail).toHaveBeenCalledWith('me@x.com'))
  })

  it('son incidentlar tabloda listelenir', async () => {
    api.admin.getLoginAnomalyIncidents.mockResolvedValue({
      success: true,
      data: [{ id: 1, opened_at: '2026-07-28T10:00:00', rules_signature: 'GLOBAL_VOLUME,IP_BRUTE_FORCE', peak_total: 47, resolved: false }],
    })
    render(<LoginAnomalySettings />)
    await waitFor(() => expect(api.admin.getLoginAnomalyIncidents).toHaveBeenCalled())
    expect(await screen.findByText('GLOBAL_VOLUME,IP_BRUTE_FORCE')).toBeInTheDocument()
  })
})
