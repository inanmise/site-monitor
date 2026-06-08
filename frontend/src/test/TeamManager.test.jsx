import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import TeamManager from '../components/admin/TeamManager.jsx'

vi.mock('../api/client', () => ({
  api: {
    admin: {
      getTeams:     vi.fn(),
      getUsers:     vi.fn(),
      getTeamUsers: vi.fn(),
      updateUser:   vi.fn(),
    },
  },
}))

import { api } from '../api/client'

const sampleTeams = [
  { id: 1, name: 'Payments', active: true, team_type: 'SY', leader_id: 7, email: 't@ex.com' },
]
const sampleMembers = [
  {
    id: 7, username: 'einanmis', display_name: 'Erdi I',
    employee_id: '12345', email: 'erdi@example.com',
    system_role: 'ADMIN', org_role: 'TECH', team_id: 1, active: true,
  },
]

describe('TeamManager — business-card members', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getTeams.mockResolvedValue({ success: true, data: sampleTeams })
    api.admin.getUsers.mockResolvedValue({ success: true, data: sampleMembers })
    api.admin.getTeamUsers.mockResolvedValue({ success: true, data: sampleMembers })
  })

  it('renders the team list without crashing', async () => {
    render(<TeamManager onTeamsChange={() => {}} />)
    await waitFor(() => expect(api.admin.getTeams).toHaveBeenCalled())
    expect(screen.getByText('Payments')).toBeDefined()
  })

  it('expands a team row and shows member cards as label-value pairs', async () => {
    render(<TeamManager onTeamsChange={() => {}} />)
    await waitFor(() => expect(screen.getByText('Payments')).toBeDefined())

    // Click the expand chevron (▶/▼ button)
    const expandBtn = document.querySelector('.team-expand-btn')
    expect(expandBtn).not.toBeNull()
    fireEvent.click(expandBtn)

    await waitFor(() => expect(api.admin.getTeamUsers).toHaveBeenCalledWith(1))
    await waitFor(() => expect(document.querySelector('.tm-member-card')).not.toBeNull())
    // Label-value fields are rendered with i18n labels and raw values inside the card
    const card = document.querySelector('.tm-member-card')
    expect(card.textContent).toContain('Erdi I')
    expect(card.textContent).toContain('einanmis')
    expect(card.textContent).toContain('12345')
    expect(card.textContent).toContain('erdi@example.com')
  })

  it('opens the user edit modal when a member card is clicked (admin only)', async () => {
    render(<TeamManager systemRole="ADMIN" onTeamsChange={() => {}} />)
    await waitFor(() => expect(screen.getByText('Payments')).toBeDefined())
    fireEvent.click(document.querySelector('.team-expand-btn'))
    await waitFor(() => expect(document.querySelector('.tm-member-card-clickable')).not.toBeNull())

    const card = document.querySelector('.tm-member-card-clickable')
    expect(card).not.toBeNull()
    fireEvent.click(card)

    // The shared UserEditModal renders an editable email input with the user's email
    await waitFor(() => expect(screen.getByDisplayValue('erdi@example.com')).toBeDefined())
  })

  it('member card is non-clickable for non-admin (read-only view)', async () => {
    render(<TeamManager onTeamsChange={() => {}} />)
    await waitFor(() => expect(screen.getByText('Payments')).toBeDefined())
    fireEvent.click(document.querySelector('.team-expand-btn'))
    await waitFor(() => expect(document.querySelector('.tm-member-card')).not.toBeNull())

    expect(document.querySelector('.tm-member-card-clickable')).toBeNull()
  })
})
