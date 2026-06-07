import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import AlertHistory from '../components/admin/AlertHistory.jsx'

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: {
    admin: {
      getAlerts:        vi.fn(),
      acknowledgeAlert: vi.fn(),
      resolveAlert:     vi.fn(),
      reNotifyAlert:    vi.fn(),
    },
  },
}))

import { api } from '../api/client'

const closedAlert = {
  id: 101,
  domain: 'foo.example.com',
  alert_type: 'EXPIRY',
  alert_level: 'CRITICAL',
  days_remaining: 7,
  acknowledged: true,
  acknowledged_by: 'erdi',
  acknowledged_at: '2026-06-05T10:00:00',
  resolved: true,
  resolved_by: 'erdi',
  resolved_at: '2026-06-07T10:00:00',
  created_at: '2026-06-01T08:00:00',
  // enrichment fields
  sy_team_name:       'SY-Team-A',
  ug_team_name:       'UG-Team-B',
  cert_tier:          1,
  email_sent_count:   3,
  email_failed_count: 1,
}

describe('AlertHistory closed-alert details', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getAlerts.mockResolvedValue({
      success: true, data: [closedAlert], total: 1, page: 0, size: 20,
    })
  })

  it('renders without crashing on the open tab', async () => {
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    expect(document.body.textContent.length).toBeGreaterThan(0)
  })

  it('shows enrichment chips on closed alerts: SY/UG teams and tier badge', async () => {
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())

    const closedTab = screen.getByRole('button', { name: /kapalı|closed/i })
    fireEvent.click(closedTab)

    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())
    expect(screen.getByText('SY-Team-A')).toBeDefined()
    expect(screen.getByText('UG-Team-B')).toBeDefined()
    expect(screen.getByText('T1')).toBeDefined()
  })

  it('renders sent/failed mail counts and the open-duration in the stats row', async () => {
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /kapalı|closed/i }))
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())

    const card = document.querySelector('.alert-history-card')
    expect(card).not.toBeNull()
    // Stats row contains the mail counts and the open duration label
    expect(card.textContent).toMatch(/3.*başarılı|3.*sent/i)
    expect(card.textContent).toMatch(/1.*başarısız|1.*failed/i)
    // 6 days 2 hours between 2026-06-01 08:00 and 2026-06-07 10:00
    expect(card.textContent).toMatch(/6g/)
  })
})
