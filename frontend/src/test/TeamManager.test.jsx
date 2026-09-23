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
    id: 7, username: 'ali', display_name: 'Ali V',
    employee_id: '12345', email: 'ali@example.com',
    system_role: 'ADMIN', org_role: 'TECH', team_id: 1, active: true,
  },
]

describe('TeamManager — elle takım müdürü (2026-09-10)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getTeamUsers.mockResolvedValue({ success: true, data: sampleMembers })
  })

  it('manager_id doluysa sütunda o kişi yazılır ve "(elle)" işareti çıkar; boşsa AD zincirinden türetilir', async () => {
    const users = [
      ...sampleMembers,
      { id: 8, username: 'mgr', display_name: 'Müdür Elle', org_role: 'MANAGER', active: true },
      { id: 9, username: 'mgr2', display_name: 'Müdür Zincir', org_role: 'MANAGER', active: true },
      { id: 10, username: 'uye', display_name: 'Üye', org_role: 'TECH', team_id: 2, manager_id: 9, active: true },
    ]
    api.admin.getUsers.mockResolvedValue({ success: true, data: users })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [
      { id: 1, name: 'Payments', active: true, leader_id: 7, email: 't@ex.com', manager_id: 8 },
      { id: 2, name: 'Ledger', active: true, leader_id: null, email: 'l@ex.com', manager_id: null },
    ] })
    render(<TeamManager systemRole="ADMIN" onTeamsChange={() => {}} />)
    await waitFor(() => expect(screen.getByText('Payments')).toBeDefined())
    await waitFor(() => expect(screen.getByText('Müdür Elle')).toBeDefined())
    expect(screen.getByText(/\((elle|manual)\)/)).toBeDefined()   // test-utils dili TR/EN olabilir
    await waitFor(() => expect(screen.getByText('Müdür Zincir')).toBeDefined())
  })
})

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

  /** Takım adı rozeti (üye modalını açan düğme) — satırdaki kebab menüsü de aynı adı taşır. */
  const teamBadge = (name) => screen.getAllByRole('button', { name: new RegExp(name) })
    .find(b => b.classList.contains('team-badge'))

  it('takım adına tıklayınca üye kartları MODALDA açılır (etiket-değer çiftleri)', async () => {
    render(<TeamManager onTeamsChange={() => {}} />)
    await waitFor(() => expect(screen.getByText('Payments')).toBeDefined())

    // Satır-içi genişletme yok: ad tıklanabilir rozet, üyeler ModalShell içinde
    expect(document.querySelector('.team-expand-btn')).toBeNull()
    // Satırın kebab menüsü de artık takım adını taşıyor ("Payments — İşlemler"), bu yüzden
    // /Payments/ deseni İKİ düğme yakalıyor. Aranan, adı açan takım rozeti: sınıfıyla ayır
    // (rozetin erişilebilir adı i18n'den geliyor, dile bağlı bir desene bağlanmayalım).
    fireEvent.click(teamBadge('Payments'))

    await waitFor(() => expect(api.admin.getTeamUsers).toHaveBeenCalledWith(1))
    await waitFor(() => expect(document.querySelector('[role="dialog"]')).not.toBeNull())
    await waitFor(() => expect(document.querySelector('.tm-member-card')).not.toBeNull())
    // Label-value fields are rendered with i18n labels and raw values inside the card
    const card = document.querySelector('.tm-member-card')
    expect(card.textContent).toContain('Ali V')
    expect(card.textContent).toContain('ali')
    expect(card.textContent).toContain('12345')
    expect(card.textContent).toContain('ali@example.com')
  })

  it('opens the user edit modal when a member card is clicked (admin only)', async () => {
    render(<TeamManager systemRole="ADMIN" onTeamsChange={() => {}} />)
    await waitFor(() => expect(screen.getByText('Payments')).toBeDefined())
    fireEvent.click(teamBadge('Payments'))
    await waitFor(() => expect(document.querySelector('.tm-member-card-clickable')).not.toBeNull())

    const card = document.querySelector('.tm-member-card-clickable')
    expect(card).not.toBeNull()
    fireEvent.click(card)

    // The shared UserEditModal renders an editable email input with the user's email
    await waitFor(() => expect(screen.getByDisplayValue('ali@example.com')).toBeDefined())
  })

  it('member card is non-clickable for non-admin (read-only view)', async () => {
    render(<TeamManager onTeamsChange={() => {}} />)
    await waitFor(() => expect(screen.getByText('Payments')).toBeDefined())
    fireEvent.click(teamBadge('Payments'))
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
