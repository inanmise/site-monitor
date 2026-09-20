import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import UserManager from '../components/admin/UserManager.jsx'
import UserDetailPanel from '../components/admin/UserDetailPanel.jsx'
import { toCsv } from '../utils/csvExport.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const confirmMock = vi.hoisted(() => vi.fn())

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      searchUsers: vi.fn(), bulkUsers: vi.fn(), getContacts: vi.fn(), getPermissionMatrix: vi.fn(), history: vi.fn(),
      userPush: { explain: vi.fn() },
    },
  }),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: confirmMock }),
  DialogProvider: ({ children }) => children,
}))
vi.mock('../components/admin/AdminChangeHistory.jsx', () => ({ default: () => null }))
import { api } from '../api/client'

const TEAMS = [{ id: 1, name: 'Takım A' }, { id: 2, name: 'Takım B' }]
const USERS = [
  { id: 1, username: 'ali', display_name: 'Ali', email: 'ali@example.com', system_role: 'USER', team_id: 1, team_ids: [1], active: true, auth_source: 'LDAP', last_login_at: '2026-09-19T10:00:00' },
  { id: 2, username: 'veli', display_name: 'Veli', email: 'veli@example.com', system_role: 'USER', team_id: 1, team_ids: [1], active: true, auth_source: 'LOCAL', last_login_at: null },
]

/** Kullanıcılar: son giriş sütunu + uyuyan süzgeci + LDAP rozeti + toplu işlem + CSV + detay kartı (2026-09-20). */
describe('UserManager — zenginleştirme', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState(null, '', '/')
    api.admin.searchUsers.mockResolvedValue({ success: true, data: USERS, total: 2, page: 0, total_pages: 1, active_admin_count: 1 })
    api.admin.bulkUsers.mockResolvedValue({ success: true, data: { ok: 2, failed: 0, results: [] } })
  })

  it('son giriş sütunu: tarih ya da "hiç girmedi"; LDAP rozeti yalnız LDAP hesapta', async () => {
    render(<UserManager systemRole="ADMIN" teams={TEAMS} currentUsername="admin" />)
    expect(await screen.findByText('2026-09-19T10:00:00')).toBeInTheDocument()
    expect(screen.getByText(/hiç girmedi|^never$/)).toBeInTheDocument()
    expect(screen.getAllByText('LDAP').length).toBe(1)
  })

  it('uyuyan hesap süzgeci sunucuya dormantDays / neverLoggedIn olarak gider ve URL\\u0027de g_dormant tutulur', async () => {
    render(<UserManager systemRole="ADMIN" teams={TEAMS} currentUsername="admin" />)
    await screen.findByText('Ali')
    fireEvent.mouseDown(screen.getByLabelText(/Son giriş|Last login/))
    fireEvent.mouseDown(await screen.findByText(/90\+ gündür|90\+ days/))
    await waitFor(() => expect(api.admin.searchUsers).toHaveBeenLastCalledWith(expect.objectContaining({ dormantDays: 90, neverLoggedIn: false })))
    fireEvent.mouseDown(screen.getByLabelText(/Son giriş|Last login/))
    fireEvent.mouseDown(await screen.findByText(/Hiç girmemiş|Never logged in/))
    await waitFor(() => expect(api.admin.searchUsers).toHaveBeenLastCalledWith(expect.objectContaining({ dormantDays: '', neverLoggedIn: true })))
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('g_dormant')).toBe('never'))
  })

  it('toplu işlem: sayfayı seç → çubuk → Pasif yap → onay → bulkUsers(deactivate, ids)', async () => {
    render(<UserManager systemRole="ADMIN" teams={TEAMS} currentUsername="admin" />)
    await screen.findByText('Ali')
    expect(screen.queryByTestId('bulk-bar')).toBeNull()
    fireEvent.click(screen.getByLabelText(/Sayfadakileri seç|Select this page/))
    const bar = await screen.findByTestId('bulk-bar')
    expect(bar).toHaveTextContent(/2 seçili|2 selected/)
    confirmMock.mockResolvedValueOnce(true)
    fireEvent.click(within(bar).getByRole('button', { name: /Pasif yap|Deactivate/ }))
    await waitFor(() => expect(api.admin.bulkUsers).toHaveBeenCalledWith({ action: 'deactivate', ids: [1, 2] }))
    // Seçim temizlenir, liste tazelenir
    await waitFor(() => expect(screen.queryByTestId('bulk-bar')).toBeNull())
    expect(api.admin.searchUsers.mock.calls.length).toBeGreaterThan(1)
  })

  it('toplu işlem: vazgeçilirse istek yok; tek satır seçimi', async () => {
    render(<UserManager systemRole="ADMIN" teams={TEAMS} currentUsername="admin" />)
    await screen.findByText('Ali')
    fireEvent.click(screen.getByLabelText('veli'))
    const bar = await screen.findByTestId('bulk-bar')
    expect(bar).toHaveTextContent(/1 seçili|1 selected/)
    confirmMock.mockResolvedValueOnce(false)
    fireEvent.click(within(bar).getByRole('button', { name: /Aktif yap|^Activate$/ }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.admin.bulkUsers).not.toHaveBeenCalled()
  })

  it('canManage değilse onay kutusu ve çubuk yok', async () => {
    render(<UserManager systemRole="USER" teams={TEAMS} currentUsername="ali" />)
    await screen.findByText('Ali')
    expect(screen.queryByLabelText(/Sayfadakileri seç|Select this page/)).toBeNull()
  })
})

describe('UserDetailPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getContacts.mockResolvedValue({ success: true, data: [{ id: 5, user_id: 1, role: 'PO', min_alert_level: 'HIGH', team_id: 1, active: true }] })
    api.admin.getPermissionMatrix.mockResolvedValue({ success: true,
      catalog: [{ resource_key: 'inventory.crud', group: 'certificates', actions: ['view', 'edit'] }, { resource_key: 'users.crud', group: 'management', actions: ['view', 'edit'] }],
      grants: [{ role: 'USER', resource_key: 'inventory.crud', action: 'view', allowed: true }, { role: 'ADMIN', resource_key: 'users.crud', action: 'edit', allowed: true }] })
    api.admin.history.mockResolvedValue({ success: true, items: [{ id: 9, at: '2026-09-18T10:00:00', actor: 'admin', action: 'UPDATE', resource_id: '1', name: 'ali', changes: '{"orgRole":{"from":null,"to":"PO"}}' }] })
    api.admin.userPush.explain.mockResolvedValue({ success: true, members: [{ username: 'ali', decision: 'RECIPIENT', group: 'PO' }] })
  })

  it('admin: erişim, takımlar, eskalasyon kaydı, push kararı, ROLE göre etkin yetkiler ve son değişiklikler', async () => {
    render(<UserDetailPanel user={USERS[0]} teams={TEAMS} isAdmin onClose={() => {}} />)
    expect(await screen.findByText('2026-09-19T10:00:00')).toBeInTheDocument()
    expect(screen.getAllByText('Takım A').length).toBeGreaterThanOrEqual(1)
    expect(await screen.findByText(/≥ HIGH/)).toBeInTheDocument()          // eskalasyon kaydı
    expect(await screen.findByText(/^Alır$|^Receives$/)).toBeInTheDocument()   // push kararı
    // USER rolünün yetkisi: inventory.crud view VAR, users.crud edit (ADMIN) YOK
    expect(await screen.findByText(/inventory\.crud|Envanter/)).toBeInTheDocument()
    expect(screen.queryByText(/users\.crud/)).toBeNull()
    expect(await screen.findByText(/düzenledi|edited/)).toBeInTheDocument()   // son değişiklik
    expect(api.admin.history).toHaveBeenCalledWith('USER', 1, { page: 0, size: 10 })
  })

  it('admin değil: yetki / push / geçmiş ayakları hiç istenmez', async () => {
    render(<UserDetailPanel user={USERS[1]} teams={TEAMS} isAdmin={false} onClose={() => {}} />)
    expect(await screen.findByText(/hiç girmedi|^never$/)).toBeInTheDocument()
    expect(api.admin.getPermissionMatrix).not.toHaveBeenCalled()
    expect(api.admin.history).not.toHaveBeenCalled()
    expect(api.admin.userPush.explain).not.toHaveBeenCalled()
  })
})

describe('toCsv', () => {
  it('BOM + ortak kaçış (utils/csv.js: tırnak ikileme, formül nötrleme) + dizi birleştirme', () => {
    const csv = toCsv(['a', 'b'], [['x,y', 'he said "hi"'], [null, ['t1', 't2']], ['=cmd', 'z']])
    expect(csv.charCodeAt(0)).toBe(0xFEFF)
    const lines = csv.slice(1).split('\r\n')
    expect(lines[0]).toBe('a,b')
    expect(lines[1]).toBe('"x,y","he said ""hi"""')
    expect(lines[2]).toBe(',"t1, t2"')
    expect(lines[3]).toBe("'=cmd,z")   // formül nötrlendi (Excel'de komut çalıştırma denemesi olmasın)
  })
})
