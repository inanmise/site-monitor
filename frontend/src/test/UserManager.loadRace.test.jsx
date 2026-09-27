import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'
import UserManager from '../components/admin/UserManager.jsx'

/**
 * 2026-09-27 regresyon taraması (BUG_REGRESYON BF1 / release-fixes.md FRONTEND A #10): Kullanıcılar `load`
 * (searchUsers) sıra korumasızdı — geniş/yavaş "tümü" isteği, sonradan seçilen dar "Yöneticiler" süzgecinin
 * listesini ve toplamını ezebiliyordu; "sayfayı seç" + toplu devre dışı bırakma bu BAYAT liste üzerinde çalışırdı.
 * İlk dönen `finally` de yükleniyor bayrağını erken kapatıyordu. Denetimli promise'lerle belirlenimci.
 */
const { apiMock } = vi.hoisted(() => ({
  apiMock: { admin: { searchUsers: vi.fn(), bulkUsers: vi.fn(), overview: vi.fn(), getContacts: vi.fn(), getPermissionMatrix: vi.fn(), history: vi.fn() } },
}))
vi.mock('../api/client', () => ({ api: apiMock, formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '' }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => false }))
vi.mock('../components/admin/AdminChangeHistory.jsx', () => ({ default: () => null }))
import { api } from '../api/client'

const user = (id, username, role) => ({ id, username, display_name: username, email: `${username}@example.com`, system_role: role, team_ids: [1], team_id: 1, active: true, auth_source: 'LOCAL' })
const OVERVIEW = { success: true, data: { counts: { users: 3, users_active: 3, admins: 1 }, warnings: [], dormant_days: 90 } }
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))

describe('UserManager — süzgeç değişince fetch yarışı (BF1)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState(null, '', '/')
    api.admin.overview.mockResolvedValue(OVERVIEW)
    api.admin.getContacts.mockResolvedValue({ success: true, data: [] })
    api.admin.getPermissionMatrix.mockResolvedValue({ success: true, catalog: [], grants: [] })
    api.admin.history.mockResolvedValue({ success: true, items: [] })
  })

  it('geç dönen "tümü" yanıtı "Yöneticiler" süzgecinin listesini EZMEZ', async () => {
    const all = deferred(), admins = deferred()
    api.admin.searchUsers.mockImplementation((p) => (p.systemRole === 'ADMIN' ? admins.p : all.p))
    render(<UserManager systemRole="ADMIN" teams={[{ id: 1, name: 'Takım A' }]} currentUsername="admin" />)
    const kpis = await screen.findByTestId('um-kpis')
    await waitFor(() => expect(api.admin.searchUsers).toHaveBeenCalled())

    fireEvent.click(kpis.querySelector('[data-kpi="admins"]'))
    await waitFor(() => expect(api.admin.searchUsers).toHaveBeenLastCalledWith(expect.objectContaining({ systemRole: 'ADMIN' })))

    await act(async () => { admins.resolve({ success: true, data: [user(1, 'yonetici', 'ADMIN')], total: 1, page: 0, total_pages: 1, active_admin_count: 1 }) })
    expect((await screen.findAllByText('yonetici')).length).toBeGreaterThan(0)
    await act(async () => { all.resolve({ success: true, data: [user(1, 'yonetici', 'ADMIN'), user(2, 'siradan', 'USER'), user(3, 'diger', 'USER')], total: 3, page: 0, total_pages: 1, active_admin_count: 1 }) })
    await flush()
    expect(screen.queryAllByText('siradan')).toHaveLength(0)
    expect(screen.queryAllByText('diger')).toHaveLength(0)
  })

  it('ESKİ yanıt önce dönerse yeni isteğin satırları beklenir: eski satırlar çizilmez', async () => {
    const all = deferred(), admins = deferred()
    api.admin.searchUsers.mockImplementation((p) => (p.systemRole === 'ADMIN' ? admins.p : all.p))
    render(<UserManager systemRole="ADMIN" teams={[{ id: 1, name: 'Takım A' }]} currentUsername="admin" />)
    const kpis = await screen.findByTestId('um-kpis')
    await waitFor(() => expect(api.admin.searchUsers).toHaveBeenCalled())
    fireEvent.click(kpis.querySelector('[data-kpi="admins"]'))
    await waitFor(() => expect(api.admin.searchUsers).toHaveBeenLastCalledWith(expect.objectContaining({ systemRole: 'ADMIN' })))

    await act(async () => { all.resolve({ success: true, data: [user(2, 'siradan', 'USER')], total: 1, page: 0, total_pages: 1, active_admin_count: 0 }) })
    await flush()
    expect(screen.queryAllByText('siradan')).toHaveLength(0)
    await act(async () => { admins.resolve({ success: true, data: [user(1, 'yonetici', 'ADMIN')], total: 1, page: 0, total_pages: 1, active_admin_count: 1 }) })
    expect((await screen.findAllByText('yonetici')).length).toBeGreaterThan(0)
  })
})
