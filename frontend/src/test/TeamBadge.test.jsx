import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import TeamBadge from '../components/ui/TeamBadge.jsx'
import { TeamDirectoryProvider } from '../components/ui/TeamDirectory.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    teams: { directory: vi.fn(), members: vi.fn() },
  }),
}))

import { api } from '../api/client'

const MEMBERS = {
  success: true,
  data: {
    team: { id: 5, name: 'Payments', email: 'payments@example.com', leader_id: 7, leader_display_name: 'Lider Kişi' },
    members: [
      { id: 7, username: 'lead', display_name: 'Lider Kişi', org_role: 'PO', title: 'Yönetici', email: 'lead@example.com', company_level: '9' },
      { id: 8, username: 'dev', display_name: 'Ahmet Dev', org_role: 'MEMBER', title: 'Uzman', email: 'dev@example.com', company_level: '7', manager_display_name: 'Lider Kişi' },
      { id: 9, username: 'mgr', display_name: 'Müdür Kişi', org_role: 'MANAGER', title: 'Müdür', company_level: '11' },
    ],
    escalation_contacts: [{ id: 1, name: 'Nöbetçi', email: 'oncall@example.com', role: 'TECH', min_alert_level: 'HIGH' }],
  },
}

describe('TeamBadge — tıklanabilir takım adı', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.teams.directory.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Payments', email: 'payments@example.com', leader_id: 7 }] })
    api.teams.members.mockResolvedValue(MEMBERS)
  })

  it('sağlayıcı yokken ve id yokken düz metin çizer (ad asla kaybolmaz, tıklanmaz)', () => {
    render(<TeamBadge teamName="Ghost Team" />)
    expect(screen.getByText('Ghost Team')).toBeDefined()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('teamId verilince düğme olur; tıklayınca üye modalı /teams/{id}/members ile açılır', async () => {
    render(<TeamBadge teamId={5} teamName="Payments" />)
    fireEvent.click(screen.getByRole('button', { name: /Payments/ }))
    await waitFor(() => expect(api.teams.members).toHaveBeenCalledWith(5))
    await waitFor(() => expect(document.querySelector('[role="dialog"]')).not.toBeNull())
    expect(screen.getAllByText('Ahmet Dev').length).toBeGreaterThan(0)
    // Kurum-geneli projeksiyon: telefon / sicil satırı yok
    expect(document.body.textContent).not.toMatch(/\+90/)
  })

  it('yalnız ad varken dizin ada göre id çözer → tıklanabilir', async () => {
    render(<TeamDirectoryProvider><TeamBadge teamName="payments" /></TeamDirectoryProvider>)
    await waitFor(() => expect(screen.getByRole('button', { name: /payments/i })).toBeDefined())
    fireEvent.click(screen.getByRole('button', { name: /payments/i }))
    await waitFor(() => expect(api.teams.members).toHaveBeenCalledWith(5))
  })

  it('modal: lider rozeti, takım e-postası (mailto), üye sayısı; kartlar müdür→PO→üye sırasında; eskalasyon sekmesi', async () => {
    render(<TeamBadge teamId={5} teamName="Payments" />)
    fireEvent.click(screen.getByRole('button', { name: /Payments/ }))
    await waitFor(() => expect(document.querySelector('.tm-member-cards')).not.toBeNull())
    expect(document.querySelector('a[href="mailto:payments@example.com"]')).not.toBeNull()
    expect(screen.getByText(/3 üye|3 members/)).toBeDefined()
    expect(document.querySelector('.tmm-leader')).not.toBeNull()
    const names = [...document.querySelectorAll('.tm-mc-name')].map(e => e.textContent)
    expect(names[0]).toContain('Müdür Kişi')
    expect(names[1]).toContain('Lider Kişi')
    expect(names[2]).toContain('Ahmet Dev')
    fireEvent.click(screen.getByRole('tab', { name: /eskalasyon|escalation/i }))
    await waitFor(() => expect(screen.getByText('oncall@example.com')).toBeDefined())
  })

  it('onOpen verilirse modal yerine o çağrılır (TeamManager)', () => {
    const onOpen = vi.fn()
    render(<TeamBadge teamId={5} teamName="Payments" onOpen={onOpen} />)
    fireEvent.click(screen.getByRole('button', { name: /Payments/ }))
    expect(onOpen).toHaveBeenCalledWith(5, 'Payments')
    expect(document.querySelector('[role="dialog"]')).toBeNull()
  })
})
