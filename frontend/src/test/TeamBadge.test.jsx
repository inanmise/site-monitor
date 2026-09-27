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
    await waitFor(() => expect(document.querySelector('[data-slot="team-member-cards"]')).not.toBeNull())
    expect(document.querySelector('a[href="mailto:payments@example.com"]')).not.toBeNull()
    expect(screen.getByText(/3 üye|3 members/)).toBeDefined()
    expect(document.querySelector('[data-slot="team-leader"]')).not.toBeNull()
    const names = [...document.querySelectorAll('[data-slot="team-member-name"]')].map(e => e.textContent)
    expect(names[0]).toContain('Müdür Kişi')
    expect(names[1]).toContain('Lider Kişi')
    expect(names[2]).toContain('Ahmet Dev')
    // shadcn Tabs (Radix): tetik fare BASIŞINDA etkinleşir (jsdom'da click sekme değiştirmez, SHADCN.md §8.3).
    fireEvent.mouseDown(screen.getByRole('tab', { name: /eskalasyon|escalation/i }))
    await waitFor(() => expect(screen.getByText('oncall@example.com')).toBeDefined())
  })

  it('onOpen verilirse modal yerine o çağrılır (TeamManager)', () => {
    const onOpen = vi.fn()
    render(<TeamBadge teamId={5} teamName="Payments" onOpen={onOpen} />)
    fireEvent.click(screen.getByRole('button', { name: /Payments/ }))
    expect(onOpen).toHaveBeenCalledWith(5, 'Payments')
    expect(document.querySelector('[role="dialog"]')).toBeNull()
  })

  it('2026-09-27: dokunmatikte ~40 px dokunma hedefi — görünür boyut aynı, ::after yalnız dikeyde taşar (düğme + span kipi)', () => {
    const HIT = ['relative', 'pointer-coarse:overflow-visible', 'pointer-coarse:after:absolute',
      'pointer-coarse:after:inset-x-0', 'pointer-coarse:after:-inset-y-2.5']
    const { unmount } = render(<TeamBadge teamId={5} teamName="Payments" />)
    const btn = screen.getByRole('button', { name: /Payments/ })
    for (const c of HIT) expect(btn).toHaveClass(c)
    // Yatayda taşma YOK (meta satırındaki komşu düğmelerle çakışmasın); görünür yükseklik sınıfı değişmedi
    expect(btn.className).not.toMatch(/after:-inset-x-|after:-inset-\d/)
    expect(btn).toHaveClass('h-auto')
    unmount()

    render(<TeamBadge teamId={5} teamName="Payments" as="span" />)
    const span = screen.getByRole('button', { name: /Payments/ })
    expect(span.tagName).toBe('SPAN')
    for (const c of HIT) expect(span).toHaveClass(c)
  })

  it('tıklanamaz (statik) rozet dokunma katmanı TAŞIMAZ', () => {
    render(<TeamBadge teamName="Ghost Team" />)
    expect(document.querySelector('[data-slot="team-badge"]')).not.toHaveClass('pointer-coarse:after:absolute')
  })
})
