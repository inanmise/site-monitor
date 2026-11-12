import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import UserManager from '../components/admin/UserManager.jsx'
import { useUserPrefsController, UserPrefsContext } from '../hooks/useUserPrefs.js'

/**
 * Kullanıcılar dizini — kayıtlı görünümler (B7, 2026-11-12): paylaşılan SavedViewsMenu durum-tabanlı modda
 * (snapshot/onApply). Görünüm = süzgeç+sıralama anlık görüntüsü, kişisel tercihlerde (savedViews.admin-users).
 * Uygulama URL gezinmesiyle DEĞİL, setter'larla → liste görünümün parametreleriyle yüklenir.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const server = { doc: {} }
vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    admin: { searchUsers: vi.fn(), bulkUsers: vi.fn(), overview: vi.fn() },
    me: { getPreferences: vi.fn(), savePreferences: vi.fn() },
  }),
}))
vi.mock('../components/admin/AdminChangeHistory.jsx', () => ({ default: () => null }))
import { api } from '../api/client'

function PrefsShell({ children }) {
  const ctl = useUserPrefsController('ali', { debounceMs: 20 })
  return <UserPrefsContext.Provider value={ctl}>{children}</UserPrefsContext.Provider>
}

const TEAMS = [{ id: 1, name: 'Takım A' }]
const USERS = [{ id: 1, username: 'ali', display_name: 'Ali', system_role: 'USER', team_id: 1, team_ids: [1], active: true }]
const trigger = () => screen.findByRole('button', { name: /^(Views|Görünümler)/ })

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState(null, '', '/')
  server.doc = {}
  api.admin.searchUsers.mockResolvedValue({ success: true, data: USERS, total: 1, page: 0, total_pages: 1, active_admin_count: 1 })
  api.admin.overview.mockResolvedValue({ success: true, data: null })
  api.me.getPreferences.mockImplementation(() => Promise.resolve({ success: true, prefs: server.doc }))
  api.me.savePreferences.mockImplementation((patch) => { server.doc = { ...server.doc, ...patch }; return Promise.resolve({ success: true, prefs: server.doc }) })
})
afterEach(() => window.history.replaceState(null, '', '/'))

describe('UserManager — kayıtlı görünümler (B7)', () => {
  it('kaydedilmiş görünümü uygula → liste görünümün süzgeç+sıralama parametreleriyle yüklenir', async () => {
    server.doc = { savedViews: { 'admin-users': [
      { name: 'Kilitli LDAP', params: { status: 'locked', source: 'LDAP', sort: 'lastLogin', dir: 'desc' } },
    ] } }
    render(<PrefsShell><UserManager systemRole="ADMIN" teams={TEAMS} currentUsername="admin" /></PrefsShell>)
    await screen.findByText('Ali')
    pressMenuTrigger(await trigger())
    fireEvent.click(screen.getByRole('menuitem', { name: /Kilitli LDAP/ }))
    await waitFor(() => expect(api.admin.searchUsers).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'locked', source: 'LDAP', sort: 'lastLogin', dir: 'desc' })))
  })

  it('bu görünümü kaydet → savedViews.admin-users geçerli süzgeç anlık görüntüsüyle (varsayılan) saklanır', async () => {
    render(<PrefsShell><UserManager systemRole="ADMIN" teams={TEAMS} currentUsername="admin" /></PrefsShell>)
    await screen.findByText('Ali')
    pressMenuTrigger(await trigger())
    fireEvent.click(screen.getByRole('menuitem', { name: /Save this view|Bu görünümü kaydet/ }))
    const dlg = await screen.findByRole('dialog')
    fireEvent.change(dlg.querySelector('input'), { target: { value: 'Benim görünümüm' } })
    fireEvent.click(screen.getByRole('button', { name: /^(Save|Kaydet)$/ }))
    await waitFor(() => expect(api.me.savePreferences).toHaveBeenCalled())
    const list = api.me.savePreferences.mock.calls.at(-1)[0].savedViews['admin-users']
    expect(list.at(-1).name).toBe('Benim görünümüm')
    // snapshot = viewFromFilters(varsayılan süzgeçler): field all, sort username asc
    expect(list.at(-1).params).toMatchObject({ field: 'all', sort: 'username', dir: 'asc', status: '', q: '' })
  })
})
