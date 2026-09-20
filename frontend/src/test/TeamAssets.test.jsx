import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import TeamMembersManager from '../components/admin/TeamMembersManager.jsx'
import TeamDeleteImpactModal from '../components/admin/TeamDeleteImpactModal.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const confirmMock = vi.hoisted(() => vi.fn())

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getTeamUsers: vi.fn(), addTeamMember: vi.fn(), removeTeamMember: vi.fn(),
      teamImpact: vi.fn(), teamMove: vi.fn(), deleteTeam: vi.fn(),
    },
  }),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: confirmMock }),
  DialogProvider: ({ children }) => children,
}))
import { api } from '../api/client'

const TEAM = { id: 7, name: 'Takım A' }
const TEAMS = [TEAM, { id: 9, name: 'Takım B' }]
const USERS = [
  { id: 1, username: 'ali', display_name: 'Ali', active: true },
  { id: 2, username: 'veli', display_name: 'Veli', active: true },
]

/** Takım üye yönetimi + silme etki önizlemesi (2026-09-20). */
describe('TeamMembersManager', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getTeamUsers.mockResolvedValue({ success: true, data: [{ id: 1, username: 'ali', display_name: 'Ali', system_role: 'USER', team_id: 7 }] })
    api.admin.addTeamMember.mockResolvedValue({ success: true, data: { added: true } })
    api.admin.removeTeamMember.mockResolvedValue({ success: true, data: { removed: true } })
  })

  it('üyeleri listeler; aday listesi üye OLMAYAN aktif kullanıcılardır; ekle → addTeamMember(teamId, userId)', async () => {
    const changed = vi.fn()
    render(<TeamMembersManager team={TEAM} users={USERS} canManage onClose={() => {}} onChanged={changed} />)
    expect(await screen.findByText('Ali')).toBeInTheDocument()
    expect(screen.getByText(/birincil takım|primary team/)).toBeInTheDocument()
    fireEvent.mouseDown(screen.getByLabelText(/Kullanıcı seçin|Choose a user/))
    expect(screen.queryByText(/Ali \(ali\)/)).toBeNull()             // zaten üye → aday değil
    fireEvent.mouseDown(await screen.findByText(/Veli \(veli\)/))
    fireEvent.click(screen.getByRole('button', { name: /^Ekle$|^Add$/ }))
    await waitFor(() => expect(api.admin.addTeamMember).toHaveBeenCalledWith(7, 2))
    await waitFor(() => expect(changed).toHaveBeenCalled())
  })

  it('çıkar: onay → removeTeamMember; vazgeçilirse istek yok', async () => {
    render(<TeamMembersManager team={TEAM} users={USERS} canManage onClose={() => {}} />)
    await screen.findByText('Ali')
    confirmMock.mockResolvedValueOnce(false)
    fireEvent.click(screen.getByRole('button', { name: /^Çıkar$|^Remove$/ }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.admin.removeTeamMember).not.toHaveBeenCalled()
    confirmMock.mockResolvedValueOnce(true)
    fireEvent.click(screen.getByRole('button', { name: /^Çıkar$|^Remove$/ }))
    await waitFor(() => expect(api.admin.removeTeamMember).toHaveBeenCalledWith(7, 1))
  })

  it('canManage=false → ekle/çıkar yok', async () => {
    render(<TeamMembersManager team={TEAM} users={USERS} canManage={false} onClose={() => {}} />)
    await screen.findByText('Ali')
    expect(screen.queryByRole('button', { name: /^Ekle$|^Add$/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Çıkar$|^Remove$/ })).toBeNull()
  })
})

describe('TeamDeleteImpactModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.teamMove.mockResolvedValue({ success: true, data: { domains: 2, monitors: 1, users: 1, contacts: 0, groups: 0 } })
    api.admin.deleteTeam.mockResolvedValue({ success: true })
  })

  it('bağlı varlık varken listeler; hedef seçilmeden "Taşı ve sil" kilitli; seçilince taşı → sil sırası', async () => {
    api.admin.teamImpact.mockResolvedValue({ success: true, data: {
      empty: false, open_alerts: 1,
      domains: { count: 2, items: ['a.example.com', 'b.example.com'] }, monitors: { count: 1, items: ['HTTP: site'] },
      users: { count: 1, items: ['ali'] }, contacts: { count: 0, items: [] }, groups: { count: 0, items: [] },
    } })
    const done = vi.fn()
    render(<TeamDeleteImpactModal team={TEAM} teams={TEAMS} onClose={() => {}} onDeleted={done} />)
    expect(await screen.findByText('a.example.com')).toBeInTheDocument()
    expect(screen.getByText(/1 açık alarm|1 open alert/)).toBeInTheDocument()
    const del = screen.getByRole('button', { name: /Taşı ve sil|Move and delete/ })
    expect(del).toBeDisabled()
    fireEvent.mouseDown(screen.getByLabelText(/Hedef takım|Target team/))
    fireEvent.mouseDown(await screen.findByText('Takım B'))
    await waitFor(() => expect(del).not.toBeDisabled())
    fireEvent.click(del)
    await waitFor(() => expect(api.admin.teamMove).toHaveBeenCalledWith(7, 9))
    await waitFor(() => expect(api.admin.deleteTeam).toHaveBeenCalledWith(7))
    expect(api.admin.teamMove.mock.invocationCallOrder[0]).toBeLessThan(api.admin.deleteTeam.mock.invocationCallOrder[0])
    await waitFor(() => expect(done).toHaveBeenCalledWith(true))
  })

  it('boş takım: hedef istenmez, doğrudan Sil', async () => {
    api.admin.teamImpact.mockResolvedValue({ success: true, data: { empty: true, open_alerts: 0, domains: { count: 0, items: [] }, monitors: { count: 0, items: [] }, users: { count: 0, items: [] }, contacts: { count: 0, items: [] }, groups: { count: 0, items: [] } } })
    render(<TeamDeleteImpactModal team={TEAM} teams={TEAMS} onClose={() => {}} onDeleted={() => {}} />)
    const del = await screen.findByRole('button', { name: /^Sil$|^Delete$/ })
    expect(del).not.toBeDisabled()
    expect(screen.queryByLabelText(/Hedef takım|Target team/)).toBeNull()
    fireEvent.click(del)
    await waitFor(() => expect(api.admin.deleteTeam).toHaveBeenCalledWith(7))
    expect(api.admin.teamMove).not.toHaveBeenCalled()
  })
})
