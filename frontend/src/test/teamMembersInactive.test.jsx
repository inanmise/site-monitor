import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import TeamMembersModal from '../components/ui/TeamMembersModal.jsx'
import { EN } from '../i18n/en.js'
import { isInactiveMember, memberActivityCounts, withInactiveLast } from '../components/ui/teamMembersModel.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    teams: { members: vi.fn() },
    noc: { getCallList: vi.fn() },
  }),
}))

import { api } from '../api/client'

/**
 * Pasif üyeler takım listelerinde GİZLENMEZ, belirgin gösterilir (2026-10-02, kullanıcı kararı): "Pasif" rozeti (shadcn
 * Badge destructive + UserX), soluk ad, her sıralamada aktiflerden SONRA, sayı "N aktif · M pasif". Adlar yer tutucu.
 */
const L = (key, ...args) => args.reduce((s, a, i) => s.split(`{${i}}`).join(String(a)), EN[key])

const mem = (id, display_name, extra = {}) => ({
  id, username: `U${id}`, display_name, email: `u${id}@example.com`, title: 'Uzman', org_role: 'TECH',
  company_level: '5', active: true, ...extra,
})
const MEMBERS = [
  mem(21, 'Kişi Z', { active: false, org_role: 'MANAGER', company_level: '9' }),   // pasif — rol sırasında önde olurdu
  mem(22, 'Kişi A'),
  mem(23, 'Kişi B', { active: false }),
  mem(24, 'Kişi C', { org_role: 'PO' }),
]

const dialog = () => screen.getByRole('dialog')
const rows = () => [...dialog().querySelectorAll('[data-slot="team-member-card"]')]
const rowNames = () => rows().map((r) => r.querySelector('[data-slot="team-member-name"]').textContent.trim())

beforeEach(() => {
  vi.clearAllMocks()
  api.teams.members.mockResolvedValue({ success: true, data: { team: { id: 1, name: 'Takım A' }, members: MEMBERS, escalation_contacts: [] } })
  api.noc.getCallList.mockResolvedValue({ success: true, data: [] })
})

function show() {
  return render(<TeamMembersModal open team={{ id: 1, name: 'Takım A' }} onClose={() => {}} />)
}

describe('Takım üyeleri — pasif üyeler belirgin', () => {
  it('pasifler aktiflerden SONRA (rol sırası pasif müdürü öne almaz); belirgin "Pasif" rozeti + soluk ad', async () => {
    show()
    await within(await screen.findByRole('dialog')).findAllByText('Kişi A')

    expect(rowNames()).toEqual(['Kişi C', 'Kişi A', 'Kişi Z', 'Kişi B'])
    const passive = rows().filter((r) => r.getAttribute('data-inactive') === 'true')
    expect(passive.map((r) => r.querySelector('[data-slot="team-member-name"]').textContent)).toEqual(['Kişi Z', 'Kişi B'])
    for (const r of passive) {
      const badge = r.querySelector('[data-slot="team-member-inactive"]')
      expect(badge).not.toBeNull()
      expect(badge.getAttribute('data-variant')).toBe('destructive')
      expect(badge).toHaveTextContent(EN['team.memberInactive'])
      expect(badge.querySelector('svg')).not.toBeNull()   // UserX ikonu
      expect(r.querySelector('[data-slot="team-member-name"]').className).toContain('text-muted-foreground')
    }
    const active = rows().filter((r) => r.getAttribute('data-inactive') !== 'true')
    for (const r of active) expect(r.querySelector('[data-slot="team-member-inactive"]')).toBeNull()
  })

  it('başlık sayısı "N aktif · M pasif"', async () => {
    show()
    const hits = await within(await screen.findByRole('dialog')).findAllByText(L('team.membersActiveInactive', 2, 2))
    // Başlık rozeti + listenin (ekran okuyucu) sayı satırı aynı metni taşır.
    expect(hits.some((n) => n.closest('[data-slot="team-member-count"]'))).toBe(true)
    expect(hits.some((n) => n.closest('[data-slot="team-member-results"]'))).toBe(true)
  })

  it('ada göre sıralamada da pasifler sonda', async () => {
    show()
    await within(await screen.findByRole('dialog')).findAllByText('Kişi A')
    fireEvent.change(within(dialog()).getByLabelText(EN['team.sortLabel']), { target: { value: 'name' } })
    expect(rowNames()).toEqual(['Kişi A', 'Kişi C', 'Kişi B', 'Kişi Z'])
  })

  it('pasif üye yokken sayı bugünkü gibi "N members" ve rozet yok', async () => {
    api.teams.members.mockResolvedValue({ success: true, data: {
      team: { id: 1, name: 'Takım A' }, members: [mem(31, 'Kişi A'), mem(32, 'Kişi B')], escalation_contacts: [],
    } })
    show()
    const hits = await within(await screen.findByRole('dialog')).findAllByText(L('team.membersCount', 2))
    expect(hits.some((n) => n.closest('[data-slot="team-member-count"]'))).toBe(true)
    expect(dialog().querySelector('[data-slot="team-member-inactive"]')).toBeNull()
  })
})

describe('teamMembersModel — pasif yardımcıları', () => {
  it('isInactiveMember: yalnız active === false; alan yoksa (eski yanıt) aktif', () => {
    expect(isInactiveMember({ active: false })).toBe(true)
    expect(isInactiveMember({ active: true })).toBe(false)
    expect(isInactiveMember({})).toBe(false)
  })
  it('memberActivityCounts + withInactiveLast (grup içi sıra korunur, pasif yoksa aynı dizi)', () => {
    expect(memberActivityCounts(MEMBERS)).toEqual({ active: 2, inactive: 2 })
    expect(withInactiveLast(MEMBERS).map((m) => m.id)).toEqual([22, 24, 21, 23])
    const plain = [mem(1, 'A'), mem(2, 'B')]
    expect(withInactiveLast(plain)).toBe(plain)
  })
})
