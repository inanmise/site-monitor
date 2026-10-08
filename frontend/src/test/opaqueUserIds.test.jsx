import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { userRefValue, sameUser } from '../utils/userRef'
import TeamMembersManager from '../components/admin/TeamMembersManager.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    admin: { getTeamUsers: vi.fn(), addTeamMember: vi.fn(), removeTeamMember: vi.fn() },
  }),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: vi.fn() }),
  DialogProvider: ({ children }) => children,
}))
import { api } from '../api/client'

/**
 * Opak kullanıcı kimliği (2026-10-08, "bir kullanıcı başka bir kullanıcının id'sini okuyamasın"): global admin dışındaki
 * görüntüleyici kimliği UUID metni olarak alır. Arayüz kimliği sayıya ÇEVİRMEZ (Number(uuid) = NaN → seçim kaybolurdu);
 * global admin'in sayısal gövdesi bayt bayt aynı kalır.
 */
const A = '0b7a3c1e-1d2f-4a5b-8c9d-0e1f2a3b4c5d'
const B = '5f6e7d8c-9b0a-4c1d-8e2f-3a4b5c6d7e8f'

describe('userRef yardımcıları', () => {
  it('userRefValue: rakam → sayı (global admin gövdesi aynı), opak kimlik → metin, boş → null', () => {
    expect(userRefValue('42')).toBe(42)
    expect(userRefValue(42)).toBe(42)
    expect(userRefValue(A)).toBe(A)
    expect(userRefValue(` ${A} `)).toBe(A)
    expect(userRefValue('')).toBeNull()
    expect(userRefValue(null)).toBeNull()
    expect(userRefValue(undefined)).toBeNull()
  })

  it('sameUser: sayı ↔ rakam metni eşit; opak kimlik yalnız kendisine eşit; boş hiçbir şeye eşit değil', () => {
    expect(sameUser(7, '7')).toBe(true)
    expect(sameUser(A, A)).toBe(true)
    expect(sameUser(A, B)).toBe(false)
    expect(sameUser(null, null)).toBe(false)
    expect(sameUser(undefined, A)).toBe(false)
  })
})

describe('TeamMembersManager — opak kimlikle üye ekleme', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getTeamUsers.mockResolvedValue({ success: true, data: [{ id: A, username: 'ali', display_name: 'Ali', system_role: 'USER', team_id: 7 }] })
    api.admin.addTeamMember.mockResolvedValue({ success: true, data: { added: true } })
  })

  it('aday listesi opak kimlikle üyeyi dışarıda bırakır; ekle → addTeamMember(teamId, "<uuid>") (sayı değil)', async () => {
    const users = [
      { id: A, username: 'ali', display_name: 'Ali', active: true },
      { id: B, username: 'veli', display_name: 'Veli', active: true },
    ]
    render(<TeamMembersManager team={{ id: 7, name: 'Takım A' }} users={users} canManage onClose={() => {}} />)
    await screen.findByText('Ali')
    fireEvent.mouseDown(screen.getByLabelText(/Kullanıcı seçin|Choose a user/))
    expect(screen.queryByText(/Ali \(ali\)/)).toBeNull()
    fireEvent.mouseDown(await screen.findByText(/Veli \(veli\)/))
    fireEvent.click(screen.getByRole('button', { name: /^Ekle$|^Add$/ }))
    await waitFor(() => expect(api.admin.addTeamMember).toHaveBeenCalledWith(7, B))
  })
})
