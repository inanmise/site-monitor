import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'

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
 * "Üyeleri yönet" penceresi (20.166 bugfix, UIA-A7): pasif üyeler SONDA ve belirgin "Pasif" rozetiyle — takım eksikleri
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
