import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from './test-utils.jsx'

const toastMock = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), dismiss: vi.fn() }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
const confirmMock = vi.hoisted(() => vi.fn(() => Promise.resolve(true)))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ admin: { getTeamUsers: vi.fn(), addTeamMember: vi.fn(), removeTeamMember: vi.fn() } }),
  formatDate: (s) => String(s ?? ''),
}))

import { api } from '../api/client'
import TeamMembersManager from '../components/admin/TeamMembersManager.jsx'

/**
 * "Üyeleri yönet" penceresi (20.165.1 bugfix, UIA-A7): pasif üyeler SONDA ve belirgin "Pasif" rozetiyle — takım eksikleri
 * "pasif üyesi var, çıkarılmaları gerekebilir" diye buraya yönlendiriyor; kimin çıkarılacağı görünmeliydi
 * (CLAUDE.md "Team member lists show passive members last, with a destructive 'Pasif' badge"). Ağ hatası sessiz kalmaz.
 */
const team = { id: 5, name: 'Ödeme Sistemleri' }
const MEMBERS = [
  { id: 1, username: 'pasif1', display_name: 'Pasif Kişi', active: false, system_role: 'USER', team_id: 5 },
  { id: 2, username: 'aktif1', display_name: 'Aktif Kişi', active: true, system_role: 'USER', team_id: 5 },
  { id: 3, username: 'aktif2', display_name: 'Aktif İki', active: true, system_role: 'USER', team_id: 9 },
]

beforeEach(() => {
  vi.clearAllMocks()
  api.admin.getTeamUsers.mockResolvedValue({ success: true, data: MEMBERS })
})

describe('TeamMembersManager — pasif üyeler', () => {
  it('pasif üye listenin SONUNDA ve "Pasif" rozetiyle; aktifler kendi sıralarında', async () => {
    render(<TeamMembersManager team={team} users={[]} canManage onClose={() => {}} />)
    const list = await screen.findByTestId('team-members')
    const rows = [...list.querySelectorAll('li')]
    expect(rows.map((r) => r.textContent.includes('Pasif Kişi'))).toEqual([false, false, true])
    expect(rows[2]).toHaveAttribute('data-inactive', 'true')
    expect(rows[2].querySelector('[data-slot="team-member-inactive"]')).not.toBeNull()
    expect(rows[0].querySelector('[data-slot="team-member-inactive"]')).toBeNull()
  })

  it('çıkarma isteği ağ hatasında reddedilirse sessiz kalmaz (hata bildirimi)', async () => {
    api.admin.removeTeamMember.mockRejectedValue(Object.assign(new Error('Sunucuya ulaşılamadı'), { status: 0 }))
    render(<TeamMembersManager team={team} users={[]} canManage onClose={() => {}} />)
    await screen.findByTestId('team-members')
    screen.getByRole('button', { name: /^Aktif Kişi — (Remove|Çıkar)$/ }).click()
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith('Sunucuya ulaşılamadı'))
  })
})

// ── 20.165.1 erişim incelemesi (A3): ADMIN / AUDIT hesabının takımlarına yalnız GLOBAL yönetici dokunur (sunucu 403) ──────────
describe('TeamMembersManager — ADMIN / AUDIT hedefleri yalnız global yöneticide', () => {
  const MIXED = [
    { id: 11, username: 'mudur1', display_name: 'Müdür Bir', active: true, system_role: 'ADMIN', team_id: 5 },
    { id: 12, username: 'denetci', display_name: 'Denetçi', active: true, system_role: 'AUDIT', team_id: 5 },
    { id: 13, username: 'uye', display_name: 'Sıradan Üye', active: true, system_role: 'USER', team_id: 5 },
  ]
  const USERS = [
    { id: 21, username: 'adm2', display_name: 'Başka Müdür', active: true, system_role: 'ADMIN' },
    { id: 22, username: 'usr2', display_name: 'Yeni Kişi', active: true, system_role: 'USER' },
  ]
  beforeEach(() => { api.admin.getTeamUsers.mockResolvedValue({ success: true, data: MIXED }) })

  it('kapsamlı yönetici: ADMIN / AUDIT üyede "Çıkar" yok (kilit + açıklama), seçicide ADMIN aday yok; USER üye çıkarılabilir', async () => {
    render(<TeamMembersManager team={team} users={USERS} canManage globalAdmin={false} onClose={() => {}} />)
    const list = await screen.findByTestId('team-members')
    const row = (name) => [...list.querySelectorAll('li')].find((li) => li.textContent.includes(name))
    expect(row('Müdür Bir').querySelector('button')).toBeNull()
    expect(row('Müdür Bir').querySelector('[data-slot="team-member-admin-locked"]')).not.toBeNull()
    expect(row('Denetçi').querySelector('button')).toBeNull()
    expect(screen.getByRole('button', { name: /^Sıradan Üye — (Remove|Çıkar)$/ })).toBeInTheDocument()
    expect(document.querySelector('[data-slot="team-member-admin-hint"]')).not.toBeNull()
    fireEvent.mouseDown(screen.getByRole('combobox'))
    const labels = await waitFor(() => {
      const l = [...document.querySelectorAll('[cmdk-item]')].map((o) => o.textContent)
      if (!l.length) throw new Error('seçenek yok')
      return l
    })
    expect(labels.some((l) => l.includes('Yeni Kişi'))).toBe(true)
    expect(labels.some((l) => l.includes('Başka Müdür'))).toBe(false)
  })

  it('global yönetici: ADMIN / AUDIT üyeyi de çıkarabilir, ADMIN adayı ekleyebilir (bugünkü davranış)', async () => {
    render(<TeamMembersManager team={team} users={USERS} canManage globalAdmin onClose={() => {}} />)
    await screen.findByTestId('team-members')
    expect(screen.getByRole('button', { name: /^Müdür Bir — (Remove|Çıkar)$/ })).toBeInTheDocument()
    expect(document.querySelector('[data-slot="team-member-admin-locked"]')).toBeNull()
    expect(document.querySelector('[data-slot="team-member-admin-hint"]')).toBeNull()
  })
})
