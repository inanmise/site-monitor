import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import UserManager from '../components/admin/UserManager.jsx'

/**
 * Kullanıcılar sekmesi yeniden tasarımı (2026-09-26): özet kutucukları (var olan süzgeci uygular),
 * etkin süzgeç çipleri, takımlar ui/TeamBadge, göreli son giriş + 90+ gün rozeti, kilit rozetleri,
 * telefonda KART listesi (useIsMobile — tek varyant), boş durumlar.
 */
const { apiMock, mobile } = vi.hoisted(() => ({
  apiMock: { admin: { searchUsers: vi.fn(), bulkUsers: vi.fn(), overview: vi.fn(), getContacts: vi.fn(), getPermissionMatrix: vi.fn(), history: vi.fn() } },
  mobile: { value: false },
}))
vi.mock('../api/client', () => ({ api: apiMock, formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '' }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.value }))
vi.mock('../components/admin/AdminChangeHistory.jsx', () => ({ default: () => null }))
import { api } from '../api/client'

const NOW = new Date('2026-09-26T09:00:00Z')
const TEAMS = [{ id: 1, name: 'Takım A' }, { id: 2, name: 'Takım B' }]
const USERS = [
  { id: 1, username: 'ali', display_name: 'Ali Örnek', email: 'ali@example.com', system_role: 'ADMIN', org_role: 'MANAGER', team_ids: [1, 2], team_id: 1, active: true, auth_source: 'LDAP', last_login_at: '2026-09-23T09:00:00' },
  { id: 2, username: 'veli', display_name: 'Veli Örnek', email: 'veli@example.com', system_role: 'USER', team_ids: [2], team_id: 2, active: false, auth_source: 'LOCAL', last_login_at: '2026-05-01T09:00:00', permanent_lock: true },
  { id: 3, username: 'ayse', display_name: 'Ayşe Örnek', email: 'ayse@example.com', system_role: 'USER', team_ids: [1], team_id: 1, active: true, auth_source: 'LOCAL', last_login_at: null },
]
const OVERVIEW = { success: true, data: { counts: { users: 42, users_active: 39, admins: 2 }, warnings: [{ code: 'USER_LOCKED', count: 1 }, { code: 'USER_NEVER_LOGGED_IN', count: 4 }, { code: 'USER_DORMANT', count: 6 }], dormant_days: 90 } }

const renderUm = (props = {}) => render(<UserManager systemRole="ADMIN" teams={TEAMS} currentUsername="admin" {...props} />)

describe('UserManager — yeniden tasarım (2026-09-26)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(NOW)
    mobile.value = false
    window.history.replaceState(null, '', '/')
    api.admin.searchUsers.mockResolvedValue({ success: true, data: USERS, total: 3, page: 0, total_pages: 1, active_admin_count: 1 })
    api.admin.overview.mockResolvedValue(OVERVIEW)
    api.admin.getContacts.mockResolvedValue({ success: true, data: [] })
    api.admin.getPermissionMatrix.mockResolvedValue({ success: true, catalog: [], grants: [] })
    api.admin.history.mockResolvedValue({ success: true, items: [] })
  })
  afterEach(() => { vi.useRealTimers() })

  it('özet kutucukları sayaçları basar; "Yöneticiler" ve "Hiç giriş yapmamış" VAR OLAN süzgeci uygular ve etkin olan vurgulanır', async () => {
    renderUm()
    const kpis = await screen.findByTestId('um-kpis')
    const tile = (k) => kpis.querySelector(`[data-kpi="${k}"]`)
    expect(tile('total').textContent).toContain('42')
    expect(tile('active').textContent).toContain('39')
    expect(tile('locked').textContent).toContain('1')
    expect(tile('total')).toHaveAttribute('aria-pressed', 'true')
    // süzgeci olmayan kutucuk düğme değil
    expect(tile('active').tagName).not.toBe('BUTTON')
    fireEvent.click(tile('admins'))
    await waitFor(() => expect(api.admin.searchUsers).toHaveBeenLastCalledWith(expect.objectContaining({ systemRole: 'ADMIN' })))
    expect(tile('admins')).toHaveAttribute('aria-pressed', 'true')
    expect(tile('total')).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(tile('never'))
    await waitFor(() => expect(api.admin.searchUsers).toHaveBeenLastCalledWith(expect.objectContaining({ neverLoggedIn: true })))
    fireEvent.click(tile('dormant'))
    await waitFor(() => expect(api.admin.searchUsers).toHaveBeenLastCalledWith(expect.objectContaining({ dormantDays: 90, neverLoggedIn: false })))
    // toplam → tüm süzgeçleri temizler
    fireEvent.click(tile('total'))
    await waitFor(() => expect(api.admin.searchUsers).toHaveBeenLastCalledWith(expect.objectContaining({ systemRole: '', dormantDays: '', neverLoggedIn: false })))
  })

  it('özet ucu yetkisizse kutucuk şeridi hiç çizilmez', async () => {
    api.admin.overview.mockResolvedValue({ success: false, error: '403' })
    renderUm()
    await screen.findByText('Ali Örnek')
    expect(screen.queryByTestId('um-kpis')).toBeNull()
  })

  it('etkin süzgeç çipleri: her biri tek tıkla kaldırılır; "Süzgeçleri temizle" hepsini siler', async () => {
    window.history.replaceState(null, '', '/?g_role=ADMIN&g_dormant=never&g_q=ali')
    renderUm()
    const chips = await screen.findByTestId('um-chips')
    expect(within(chips).getAllByRole('button', { name: /^(Süzgeci kaldır|Remove filter): / })).toHaveLength(3)
    fireEvent.click(within(chips).getByRole('button', { name: /(Süzgeci kaldır|Remove filter): (Rol|Role): ADMIN/ }))
    await waitFor(() => expect(api.admin.searchUsers).toHaveBeenLastCalledWith(expect.objectContaining({ systemRole: '', q: 'ali', neverLoggedIn: true })))
    fireEvent.click(within(screen.getByTestId('um-chips')).getByRole('button', { name: /^(Süzgeçleri temizle|Clear filters)$/ }))
    await waitFor(() => expect(screen.queryByTestId('um-chips')).toBeNull())
    await waitFor(() => expect(api.admin.searchUsers).toHaveBeenLastCalledWith(expect.objectContaining({ q: '', neverLoggedIn: false })))
  })

  it('satır: takımlar TeamBadge, son giriş göreli (+90 gün rozeti), hiç girmemiş sessiz rozet, kalıcı kilit Lock rozeti, durum noktalı rozet', async () => {
    renderUm()
    const aliRow = (await screen.findByText('Ali Örnek')).closest('tr')
    const badges = aliRow.querySelectorAll('[data-slot="team-badge"]')
    expect([...badges].map((b) => b.textContent)).toEqual(['Takım A', 'Takım B'])
    expect(within(aliRow).getByLabelText(/2026-09-23T09:00:00/).textContent).toMatch(/3 (gün önce|d ago)/)
    expect(aliRow.querySelector('[data-login="dormant"]')).toBeNull()
    const veliRow = screen.getByText('Veli Örnek').closest('tr')
    expect(veliRow.querySelector('[data-login="dormant"]')).not.toBeNull()
    expect(within(veliRow).getByRole('img', { name: /Kalıcı kilitli|Permanently locked/ })).toHaveAttribute('data-lock', 'perm')
    expect(veliRow.querySelector('[data-account="inactive"]')).not.toBeNull()
    const ayseRow = screen.getByText('Ayşe Örnek').closest('tr')
    expect(ayseRow.querySelector('[data-login="never"]')).not.toBeNull()
    // TeamBadge'e tıklamak satırın detayını AÇMAZ (üye listesi kendi penceresi)
    fireEvent.click(badges[0])
    expect(screen.queryByTestId('user-detail')).toBeNull()
  })

  it('telefon (useIsMobile): tablo yerine KART listesi — kart başlığı detayı açar, onay kutusu ve menü kartı açmaz', async () => {
    mobile.value = true
    renderUm()
    const cards = await screen.findByTestId('um-cards')
    expect(screen.queryByRole('table')).toBeNull()
    expect(cards.querySelectorAll('[data-user-card]')).toHaveLength(3)
    const card = cards.querySelector('[data-user-card="2"]')
    fireEvent.click(within(card).getByRole('checkbox', { name: /veli/ }))
    expect(card).toHaveAttribute('data-state', 'selected')
    expect(screen.queryByTestId('user-detail')).toBeNull()
    expect(await screen.findByTestId('bulk-bar')).toHaveTextContent(/1 (seçili|selected)/)
    const open = card.querySelector('[data-user-open]')
    expect(open).toHaveAccessibleName(/Veli Örnek/)
    fireEvent.click(open)
    expect(await screen.findByTestId('user-detail')).toBeInTheDocument()
  })

  it('boş sonuç: süzgeç varken "süzgeçleri temizle" eylemli durum bloğu; hiç kullanıcı yokken ayrı mesaj', async () => {
    api.admin.searchUsers.mockResolvedValue({ success: true, data: [], total: 0, page: 0, total_pages: 0, active_admin_count: 0 })
    window.history.replaceState(null, '', '/?g_role=AUDIT')
    const { unmount } = renderUm()
    expect(await screen.findByText(/Kayıt bulunamadı|No records found/)).toBeInTheDocument()
    const clearBtns = screen.getAllByRole('button', { name: /^(Süzgeçleri temizle|Clear filters)$/ })
    expect(clearBtns.length).toBeGreaterThanOrEqual(1)
    unmount()
    window.history.replaceState(null, '', '/')
    renderUm()
    expect(await screen.findByText(/Henüz kullanıcı yok|No users yet/)).toBeInTheDocument()
  })
})
