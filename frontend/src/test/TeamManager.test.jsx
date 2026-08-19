import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import TeamManager from '../components/admin/TeamManager.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: {
      getTeams:     vi.fn(),
      getUsers:     vi.fn(),
      getTeamUsers: vi.fn(),
      updateUser:   vi.fn(),
      createTeam:   vi.fn(),
      updateTeam:   vi.fn(),
      updateTeamWeeklyNotifications: vi.fn(),
    },
  }),
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
    // waitFor(getTeams çağrıldı) yalnız İSTEĞİN yapıldığını bekler; state güncellenmeden
    // getByText çalışırsa yük altında satır henüz çizilmemiş olur ve test aralıklı kırılır
    // (2026-08-16'da tam bu oldu). Render'ın KENDİSİ beklenir — alttaki test zaten böyle.
    await waitFor(() => expect(screen.getByText('Payments')).toBeDefined())
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

describe('TeamManager — haftalık e-posta anahtarları', () => {
  const teams = [
    { id: 1, name: 'Payments', active: true, leader_id: 7, email: 't@ex.com',
      weekly_reminder_enabled: true, weekly_availability_enabled: false },
    { id: 2, name: 'Other', active: true, leader_id: 7, email: 'o@ex.com',
      weekly_reminder_enabled: false, weekly_availability_enabled: false },
  ]

  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getTeams.mockResolvedValue({ success: true, data: teams })
    api.admin.getUsers.mockResolvedValue({ success: true, data: sampleMembers })
    api.admin.getTeamUsers.mockResolvedValue({ success: true, data: sampleMembers })
    api.admin.updateTeamWeeklyNotifications.mockResolvedValue({ success: true, data: {} })
  })

  /** Satır sırası tablodakiyle aynı: her takımda 2 anahtar (hatırlatma, erişilebilirlik). */
  const pills = () => [...document.querySelectorAll('.tm-weekly-cell .perm-pill')]

  it('anahtarın durumu takım verisinden gelir', async () => {
    render(<TeamManager systemRole="USER" ownTeamId={1} myTeamIds={[1]} onTeamsChange={() => {}} />)
    await waitFor(() => expect(screen.getByText('Payments')).toBeDefined())

    const [remind1, avail1] = pills()
    expect(remind1.getAttribute('aria-checked')).toBe('true')
    expect(avail1.getAttribute('aria-checked')).toBe('false')
  })

  it('ÜYE olunan takımın anahtarı çevrilebilir → dar uca doğru gövdeyle gider', async () => {
    render(<TeamManager systemRole="USER" ownTeamId={1} myTeamIds={[1]} onTeamsChange={() => {}} />)
    await waitFor(() => expect(screen.getByText('Payments')).toBeDefined())

    fireEvent.click(pills()[1])   // 1. takımın erişilebilirlik anahtarı: kapalı → açık
    await waitFor(() => expect(api.admin.updateTeamWeeklyNotifications)
      .toHaveBeenCalledWith(1, { weekly_availability_enabled: true }))
  })

  it('ÜYE OLUNMAYAN takımın anahtarları devre dışı (sıradan kullanıcı)', async () => {
    render(<TeamManager systemRole="USER" ownTeamId={1} myTeamIds={[1]} onTeamsChange={() => {}} />)
    await waitFor(() => expect(screen.getByText('Other')).toBeDefined())

    const all = pills()
    expect(all[0].disabled).toBe(false)   // takım 1 → üye
    expect(all[2].disabled).toBe(true)    // takım 2 → üye değil
    fireEvent.click(all[2])
    expect(api.admin.updateTeamWeeklyNotifications).not.toHaveBeenCalled()
  })

  it('ADMIN her takımın anahtarını çevirebilir', async () => {
    render(<TeamManager systemRole="ADMIN" ownTeamId={1} myTeamIds={[1]} onTeamsChange={() => {}} />)
    await waitFor(() => expect(screen.getByText('Other')).toBeDefined())

    expect(pills()[2].disabled).toBe(false)
  })

  it('yeni takım formu iki anahtarı da KAPALI açar', async () => {
    render(<TeamManager systemRole="ADMIN" ownTeamId={1} myTeamIds={[1]} onTeamsChange={() => {}} />)
    await waitFor(() => expect(screen.getByText('Payments')).toBeDefined())

    fireEvent.click(screen.getByRole('button', { name: /Takım Ekle|Add Team/i }))
    const formPills = [...document.querySelectorAll('.tm-weekly-form .perm-pill')]
    expect(formPills).toHaveLength(2)
    formPills.forEach(p => expect(p.getAttribute('aria-checked')).toBe('false'))
  })
})
