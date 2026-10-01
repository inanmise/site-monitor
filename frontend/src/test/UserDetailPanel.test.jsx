import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import UserDetailPanel from '../components/admin/UserDetailPanel.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getContacts: vi.fn(), getPermissionMatrix: vi.fn(), history: vi.fn(), userPush: { explain: vi.fn() },
      userTeamMembership: vi.fn(), userLdapCheck: vi.fn(), userLdapResync: vi.fn(), unlockUserField: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

/**
 * Kullanıcı Detayı yeniden tasarımı (2026-09-27): sağdan Sheet + profil başlığı + sayılı sekmeler, bölüm başına
 * iskelet / hata + yeniden dene. Adlar yer tutucu (Takım A, example.com).
 */
const DAY = 86_400_000
const ago = (ms) => new Date(Date.now() - ms).toISOString().slice(0, 19)
const TEAMS = [{ id: 1, name: 'Takım A' }, { id: 2, name: 'Takım B' }, { id: 3, name: 'Takım C' }]
const USER = {
  id: 7, username: 'KULLANICI_A', display_name: 'Kullanıcı A', first_name: 'Kullanıcı', last_name: 'A',
  email: 'kullanici.a@example.com', employee_id: '100001', system_role: 'TEAM_ADMIN', org_role: 'PO',
  team_id: 1, team_ids: [1, 2], active: true, auth_source: 'LDAP', last_login_at: ago(2 * DAY), last_login_method: 'LDAP',
  team_locked: true, org_role_locked: true, role_locked: false, permanent_lock: false,
  manager_sicil: '100005', title: 'Uzman', department: 'Departman A', created_at: '2025-01-02T03:04:05', updated_at: '2026-09-20T10:00:00',
}
const MEMBERSHIP = { success: true, data: { user_id: 7, team_locked: true, memberships: [
  { team_id: 1, team_name: 'Takım A', primary: true, source: 'LDAP_GROUP', detail: 'CN=Takım A', updated_by: 'LDAP', updated_at: '2026-09-25T08:00:00' },
  { team_id: 2, team_name: 'Takım B', primary: false, source: 'MANUAL', detail: null, updated_by: 'admin', updated_at: '2026-09-01T08:00:00' },
] } }
const CONTACTS = { success: true, data: [
  { id: 5, user_id: 7, role: 'PO', min_alert_level: 'HIGH', team_id: 1, active: true, webhook_url: 'https://hooks.example.com/a', webhook_type: 'TEAMS' },
  { id: 6, user_id: 7, role: 'TECH', min_alert_level: 'WARNING', team_id: 2, active: false },
  { id: 9, user_id: 8, role: 'TECH', min_alert_level: 'CRITICAL', team_id: 2, active: true },
] }
const MATRIX = { success: true,
  catalog: [
    { resource_key: 'inventory.list', group: 'certificates', actions: ['view'] },
    { resource_key: 'inventory.crud', group: 'certificates', actions: ['edit'] },
    { resource_key: 'users.crud', group: 'management', actions: ['view', 'edit'] },
    { resource_key: 'settings.smtp', group: 'settings', actions: ['edit'] },
  ],
  grants: [
    { role: 'TEAM_ADMIN', resource_key: 'inventory.list', action: 'view', allowed: true },
    { role: 'TEAM_ADMIN', resource_key: 'inventory.crud', action: 'edit', allowed: true },
    { role: 'TEAM_ADMIN', resource_key: 'users.crud', action: 'view', allowed: true },
    { role: 'TEAM_ADMIN', resource_key: 'users.crud', action: 'edit', allowed: false },
    { role: 'TEAM_ADMIN', resource_key: 'settings.smtp', action: 'edit', allowed: false },
    { role: 'ADMIN', resource_key: 'settings.smtp', action: 'edit', allowed: true },
  ] }
const HIST = Array.from({ length: 25 }, (_, i) => ({
  id: 500 - i, at: `2026-09-${String(25 - (i % 20)).padStart(2, '0')}T10:00:00`, actor: 'admin', action: i === 0 ? 'UPDATE' : 'TEAM_UNLOCK',
  event_type: i === 0 ? 'USER_UPDATE' : 'USER_TEAM_UNLOCK', resource_id: '7', name: 'KULLANICI_A', ip: '10.0.0.1',
  changes: i === 0 ? '{"orgRole":{"from":"TECH","to":"PO"},"teamIds":{"from":"1","to":"1, 2"}}' : null,
}))
function historyImpl(_res, _id, { page = 0, size = 10 } = {}) {
  return Promise.resolve({ success: true, items: HIST.slice(page * size, page * size + size), total: HIST.length, page, size, total_pages: Math.ceil(HIST.length / size) })
}

/** Çizer ve ilk bölüm yüklemelerini act içinde boşaltır (eşzamansız yanıtlar act dışında durum yazmasın). */
async function renderPanel(props = {}) {
  let out
  await act(async () => { out = render(<UserDetailPanel user={USER} teams={TEAMS} isAdmin onClose={() => {}} {...props} />) })
  return out
}
/** Pencerede `data-slot` kancası (yüklenince). */
const findSlot = (name) => waitFor(() => {
  const el = screen.getByTestId('user-detail').querySelector(`[data-slot="${name}"]`)
  if (!el) throw new Error(`${name} yok`)
  return el
})
const tabByName = (re) => screen.getByRole('tab', { name: re })
/** Radix Tabs tetiği jsdom'da mousedown ile etkinleşir (SHADCN.md §8.3). */
const openTab = (re) => fireEvent.mouseDown(tabByName(re), { button: 0 })

describe('UserDetailPanel — yeniden tasarım', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    try { localStorage.clear() } catch { /* yoksay */ }
    api.admin.userTeamMembership.mockResolvedValue(MEMBERSHIP)
    api.admin.getContacts.mockResolvedValue(CONTACTS)
    api.admin.getPermissionMatrix.mockResolvedValue(MATRIX)
    api.admin.history.mockImplementation(historyImpl)
    api.admin.userPush.explain.mockResolvedValue({ success: true, members: [{ username: 'KULLANICI_A', decision: 'RECIPIENT', group: 'po', min_level: 'WARNING' }] })
  })
  // Bölümler eşzamansız yüklenir: test bitmeden bekleyen yanıtlar act içinde boşaltılır (act uyarısı gürültüsü olmasın).
  afterEach(async () => { await act(() => new Promise((r) => setTimeout(r, 0))) })

  it('başlık: ad pencere başlığında, kullanıcı adı/e-posta kopyalanır, e-posta mailto, rol + durum + kilit rozetleri, son giriş', async () => {
    const writeText = vi.spyOn(navigator.clipboard, 'writeText')
    await renderPanel()
    const dlg = screen.getByRole('dialog', { name: /Kullanıcı A/ })
    expect(dlg).toHaveAttribute('data-slot', 'user-detail')
    const header = dlg.querySelector('[data-slot="ud-header"]')
    expect(within(header).getByText('KULLANICI_A')).toBeInTheDocument()
    expect(within(header).getByRole('link', { name: 'kullanici.a@example.com' })).toHaveAttribute('href', 'mailto:kullanici.a@example.com')

    fireEvent.click(within(header).getByRole('button', { name: 'Copy username: KULLANICI_A' }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('KULLANICI_A'))
    expect(await within(header).findByRole('button', { name: 'Copied' })).toBeInTheDocument()

    const badges = header.querySelector('[data-slot="ud-badges"]')
    expect(badges.querySelector('[data-role="TEAM_ADMIN"]')).not.toBeNull()
    expect(badges.querySelector('[data-role="PO"]')).not.toBeNull()
    expect(badges.querySelector('[data-account="active"]')).not.toBeNull()
    expect(badges.querySelector('[data-auth="ldap"]')).not.toBeNull()
    expect([...badges.querySelectorAll('[data-lock]')].map((e) => e.getAttribute('data-lock'))).toEqual(['org', 'team'])
    // Kilit açıklaması dokunmatikte de açılır (HintPopover, yalnız-hover değil)
    fireEvent.click(within(badges).getByRole('button', { name: /Teams locked — what does this mean\?/ }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent(/assigned by hand/)
    const signin = header.querySelector('[data-slot="ud-signin"]')
    expect(signin).toHaveTextContent(/Last signed in:\s*2 d ago/)
    expect(signin.querySelector('[data-login]')).toBeNull()
  })

  it('başlık: hiç girmemiş ve 90+ gün uyarıları; yerel hesapta AD rozeti yok', async () => {
    const { unmount } = await renderPanel({ user: { ...USER, last_login_at: null, auth_source: 'LOCAL' } })
    let header = screen.getByTestId('user-detail').querySelector('[data-slot="ud-header"]')
    expect(header.querySelector('[data-login="never"]')).toHaveTextContent('Never signed in')
    expect(header.querySelector('[data-auth="local"]')).toHaveTextContent('Local')
    expect(screen.queryByRole('button', { name: /Check against AD/ })).toBeNull()
    unmount()
    await renderPanel({ user: { ...USER, last_login_at: ago(120 * DAY) } })
    header = screen.getByTestId('user-detail').querySelector('[data-slot="ud-header"]')
    expect(header.querySelector('[data-login="dormant"]')).toHaveTextContent('No sign-in for 90+ days')
  })

  it('sekmeler sayılarıyla: Takımlar 2 · Bildirimler 2 · Yetkiler 3 · Değişiklikler 25; Genel Bakış açık başlar', async () => {
    await renderPanel()
    expect(tabByName(/Overview/)).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(tabByName(/^Teams/)).toHaveAccessibleName(/^Teams\s*2$/))
    await waitFor(() => expect(tabByName(/^Notifications/)).toHaveAccessibleName(/^Notifications\s*2$/))
    await waitFor(() => expect(tabByName(/^Permissions/)).toHaveAccessibleName(/^Permissions\s*3$/))
    await waitFor(() => expect(tabByName(/^Changes/)).toHaveAccessibleName(/^Changes\s*25$/))
    expect(tabByName(/Directory \(AD\)/)).toBeInTheDocument()
    expect(screen.getByRole('tablist', { name: 'User detail sections' })).toBeInTheDocument()
    expect(api.admin.history).toHaveBeenCalledWith('USER', 7, { page: 0, size: 10 })
  })

  it('yönetici değil: Yetkiler / Değişiklikler / Dizin sekmeleri yok ve istekleri hiç atılmaz', async () => {
    await renderPanel({ isAdmin: false })
    await waitFor(() => expect(tabByName(/^Teams/)).toHaveAccessibleName(/^Teams\s*2$/))
    expect(screen.queryByRole('tab', { name: /Permissions|Changes|Directory/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Check against AD/ })).toBeNull()
    expect(api.admin.getPermissionMatrix).not.toHaveBeenCalled()
    expect(api.admin.history).not.toHaveBeenCalled()
    expect(api.admin.userPush.explain).not.toHaveBeenCalled()
    openTab(/^Notifications/)
    expect(screen.queryByRole('heading', { name: 'Push notifications' })).toBeNull()
  })

  it('Takımlar: üyelik başına kaynak rozeti + kanıt + birincil; yönetici ÜYE olarak listelenmez, ayrı kartta', async () => {
    await renderPanel()
    openTab(/^Teams/)
    const list = await screen.findByTestId('user-team-sources')
    const rows = [...list.querySelectorAll('[data-team-id]')]
    expect(rows.map((r) => r.getAttribute('data-team-id'))).toEqual(['1', '2'])
    expect(rows[0].querySelector('[data-source]')).toHaveAttribute('data-source', 'LDAP_GROUP')
    expect(rows[0].querySelector('[data-slot="ud-primary"]')).not.toBeNull()
    expect(rows[0]).toHaveTextContent('CN=Takım A')
    expect(rows[0]).toHaveTextContent('LDAP · 2026-09-25T08:00:00')
    expect(rows[1].querySelector('[data-source]')).toHaveAttribute('data-source', 'MANUAL')
    expect(rows[1].querySelector('[data-slot="ud-primary"]')).toBeNull()
    expect(list).not.toHaveTextContent('100005')
    expect(screen.getByTestId('user-detail').querySelector('[data-slot="ud-line-manager"]')).toHaveTextContent('100005')
    expect(screen.getByText(/Teams were edited by hand \(locked\)/)).toBeInTheDocument()
  })

  it('Bildirimler: push kararı (rozet + neden) ve yalnız bu kişinin eskalasyon kayıtları, seviyeleriyle', async () => {
    await renderPanel()
    openTab(/^Notifications/)
    const push = await findSlot('ud-push')
    expect(push.querySelector('[data-decision="RECIPIENT"]')).toHaveTextContent('Receives')
    expect(push).toHaveTextContent('Receives pushes through the “po” group for WARNING alerts and above.')
    expect(api.admin.userPush.explain).toHaveBeenCalledWith(1, 'HIGH')
    const contacts = screen.getByTestId('user-detail').querySelector('[data-slot="ud-contacts"]')
    const items = [...contacts.querySelectorAll('[data-contact-id]')]
    expect(items.map((e) => e.getAttribute('data-contact-id'))).toEqual(['5', '6'])
    expect(items[0].querySelector('[data-level="HIGH"]')).toHaveTextContent('HIGH and above')
    expect(items[0].querySelector('[data-webhook="TEAMS"]')).not.toBeNull()
    expect(items[1]).toHaveAttribute('data-active', 'false')
    expect(items[1]).toHaveTextContent('Inactive')
  })

  it('Yetkiler: rolün etkin yetkileri (salt okunur) — arama ve "Yalnız verilenler" anahtarı', async () => {
    await renderPanel()
    openTab(/^Permissions/)
    const root = await findSlot('ud-permissions')
    expect(root).toHaveTextContent('3 of 5 permissions granted')
    const keys = () => [...root.querySelectorAll('[data-resource]')].map((e) => e.getAttribute('data-resource'))
    expect(keys()).toEqual(['inventory.list', 'inventory.crud', 'users.crud'])
    const usersEdit = root.querySelector('[data-resource="users.crud"] [data-action="edit"]')
    expect(usersEdit).toHaveAttribute('data-granted', 'false')
    expect(usersEdit).toHaveAccessibleName(/not allowed/)

    fireEvent.click(screen.getByRole('switch', { name: 'Granted only' }))
    expect(keys()).toContain('settings.smtp')
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search permissions' }), { target: { value: 'smtp' } })
    expect(keys()).toEqual(['settings.smtp'])
    fireEvent.click(screen.getByRole('switch', { name: 'Granted only' }))
    expect(keys()).toEqual([])
    expect(root).toHaveTextContent('No matching permissions')
  })

  it('Değişiklikler: satırlar (işlem · kim · çipler), ayrıntı açılımı ve standart sayfalama', async () => {
    await renderPanel()
    openTab(/^Changes/)
    const list = await findSlot('ud-changes')
    const rows = () => [...screen.getByTestId('user-detail').querySelectorAll('[data-slot="ud-change-row"]')]
    expect(rows()).toHaveLength(10)
    expect(rows()[0]).toHaveTextContent('edited')
    expect(rows()[0].querySelector('[data-chip="orgRole"]')).not.toBeNull()
    fireEvent.click(within(rows()[0]).getByRole('button', { name: /Show details/ }))
    expect(rows()[0].querySelector('[data-diff="to"]')).toHaveTextContent('PO')
    expect(list).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(api.admin.history).toHaveBeenLastCalledWith('USER', 7, { page: 1, size: 10 }))
    await waitFor(() => expect(rows()[0]).toHaveTextContent('unlocked the team of'))
    expect(rows()[0].querySelector('[data-chip]')).toBeNull()   // anlık görüntüsüz satır: çip ve açılım yok
    expect(within(rows()[0]).queryByRole('button', { name: /Show details/ })).toBeNull()
  })

  it('Dizin (AD): başlıktaki "AD ile denetle" sekmeyi açar; karşılaştırma bileşeni yalnız orada', async () => {
    await renderPanel()
    expect(screen.queryByTestId('ldap-compare')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Check against AD' }))
    expect(tabByName(/Directory \(AD\)/)).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByTestId('ldap-compare')).toBeInTheDocument()
  })

  it('bölüm hatası: üyelik düşerse hata + kayıttaki takımlar yedek; Tekrar dene yeniden yükler', async () => {
    api.admin.userTeamMembership.mockRejectedValueOnce(new Error('Sunucuya ulaşılamadı'))
    await renderPanel()
    openTab(/^Teams/)
    expect(await screen.findByText('Could not load team memberships')).toBeInTheDocument()
    expect(screen.getByText('Sunucuya ulaşılamadı')).toBeInTheDocument()
    expect(screen.getByText(/Membership sources could not be loaded/)).toBeInTheDocument()
    expect(screen.queryByTestId('user-team-sources')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Could not load team memberships — Try again' }))
    expect(await screen.findByTestId('user-team-sources')).toBeInTheDocument()
    expect(screen.queryByText('Could not load team memberships')).toBeNull()
    expect(api.admin.userTeamMembership).toHaveBeenCalledTimes(2)
  })

  it('bölüm hatası: eskalasyon ve geçmiş hataları kendi bölümünde, sunucu mesajıyla', async () => {
    api.admin.getContacts.mockResolvedValue({ success: false, error: 'Yetkiniz yok' })
    api.admin.history.mockRejectedValue(new Error('Zaman aşımı'))
    await renderPanel()
    openTab(/^Notifications/)
    expect(await screen.findByText('Could not load escalation records')).toBeInTheDocument()
    expect(screen.getByText('Yetkiniz yok')).toBeInTheDocument()
    expect(screen.getByTestId('user-detail').querySelector('[data-slot="ud-push"]')).not.toBeNull()   // push etkilenmez
    openTab(/^Changes/)
    expect(await screen.findByText('Could not load the change history')).toBeInTheDocument()
    api.admin.history.mockImplementation(historyImpl)
    fireEvent.click(screen.getByRole('button', { name: 'Could not load the change history — Try again' }))
    await waitFor(() => expect(screen.getByTestId('user-detail').querySelectorAll('[data-slot="ud-change-row"]').length).toBe(10))
  })

  it('kilit açma: YALNIZ verilen mevcut işleyiciler düğme olur; sonrası geçmiş tazelenir ve çağıran haberdar', async () => {
    const team = vi.fn().mockResolvedValue(undefined)
    const onChanged = vi.fn()
    await renderPanel({ onUnlock: { team }, onChanged })
    const locks = screen.getByTestId('user-detail').querySelector('[data-slot="ud-locks"]')
    expect([...locks.querySelectorAll('li[data-lock]')].map((e) => e.getAttribute('data-lock'))).toEqual(['org', 'team'])
    expect(within(locks).queryByRole('button', { name: /Return org role to AD/ })).toBeNull()
    await waitFor(() => expect(api.admin.history).toHaveBeenCalledTimes(1))
    fireEvent.click(within(locks).getByRole('button', { name: 'Return teams to AD' }))
    await waitFor(() => expect(team).toHaveBeenCalledWith(7))
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
    await waitFor(() => expect(api.admin.history).toHaveBeenCalledTimes(2))
  })

  // LDAP alan kilitleri (2026-09-30): satır `locked_field_keys` taşır → başlıkta TEK toplu rozet, Genel Bakış'ta alan başına
  // satır (mevcut perm/role/org/team satırlarından SONRA, kanonik sırada). "AD'ye geri ver" yalnız global yöneticide.
  it('LDAP alan kilitleri: alan başına satır + başlıkta "N alan kilitli"; geri verme düğmesi yalnız globalAdmin ve doğru alanla çağrılır', async () => {
    const locked = { ...USER, locked_field_keys: ['title', 'email'] }   // sunucu kanonik sırada yollar; ekran yine de sıralar
    api.admin.unlockUserField.mockResolvedValue({ success: true, had_lock: true, data: { ...locked, locked_field_keys: ['email'] } })
    const { unmount } = await renderPanel({ user: locked, onUnlock: { team: vi.fn() } })
    let dlg = screen.getByTestId('user-detail')
    const badges = dlg.querySelector('[data-slot="ud-badges"]')
    expect([...badges.querySelectorAll('[data-lock]')].map((e) => e.getAttribute('data-lock'))).toEqual(['org', 'team', 'fields'])
    expect(badges.querySelector('[data-lock="fields"]')).toHaveTextContent('2 fields locked')
    fireEvent.click(within(badges).getByRole('button', { name: /2 fields locked — what does this mean\?/ }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent(/Email, Title/)
    let locks = dlg.querySelector('[data-slot="ud-locks"]')
    expect([...locks.querySelectorAll('li[data-lock]')].map((e) => `${e.getAttribute('data-lock')}:${e.getAttribute('data-field') || ''}`))
      .toEqual(['org:', 'team:', 'field:email', 'field:title'])
    const titleRow = locks.querySelector('li[data-field="title"]')
    expect(titleRow).toHaveTextContent('Title locked')
    expect(titleRow).toHaveTextContent(/Edited by hand/)
    expect(within(titleRow).queryByRole('button', { name: 'Return to AD' })).toBeNull()         // global yönetici değil
    expect(within(locks).getByRole('button', { name: 'Return teams to AD' })).toBeInTheDocument()   // mevcut satır bozulmadı
    unmount()

    const onChanged = vi.fn()
    await renderPanel({ user: locked, globalAdmin: true, onChanged })
    dlg = screen.getByTestId('user-detail')
    locks = dlg.querySelector('[data-slot="ud-locks"]')
    expect(within(locks).getAllByRole('button', { name: 'Return to AD' })).toHaveLength(2)
    await waitFor(() => expect(api.admin.history).toHaveBeenCalledTimes(2))
    fireEvent.click(within(locks.querySelector('li[data-field="title"]')).getByRole('button', { name: 'Return to AD' }))
    await waitFor(() => expect(api.admin.unlockUserField).toHaveBeenCalledWith(7, 'title'))
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
    await waitFor(() => expect(api.admin.history).toHaveBeenCalledTimes(3))   // geçmiş tazelendi
  })

  it('kilitsiz ve işleyicisiz: kilit kartı "kilit yok" der, düğme yok', async () => {
    await renderPanel({ user: { ...USER, team_locked: false, org_role_locked: false } })
    const dlg = screen.getByTestId('user-detail')
    expect(dlg.querySelector('[data-slot="ud-no-locks"]')).not.toBeNull()
    expect(dlg.querySelector('[data-slot="ud-locks"]')).toBeNull()
  })

  it('telefon: pencere tam ekran (tam genişlik, 100dvh), sekme listesi yatay kayar, dokunma hedefleri ≥ 40 px', async () => {
    await renderPanel({ onEdit: () => {} })
    const dlg = screen.getByTestId('user-detail')
    expect(dlg.className).toMatch(/(^|\s)w-full(\s|$)/)
    expect(dlg.className).toContain('h-[100dvh]')
    expect(dlg.className).toContain('sm:w-[min(56rem,calc(100vw-2rem))]')
    const list = screen.getByRole('tablist')
    expect(list.className).toContain('overflow-x-auto')
    for (const tab of screen.getAllByRole('tab')) expect(tab.className).toMatch(/(^|\s)h-11(\s|$)/)
    expect(screen.getByRole('button', { name: 'Close' }).className).toMatch(/(^|\s)size-10(\s|$)/)
    expect(screen.getByRole('button', { name: 'Edit' }).className).toMatch(/(^|\s)h-10(\s|$)/)
    expect(screen.getByRole('button', { name: 'Check against AD' }).className).toMatch(/(^|\s)h-10(\s|$)/)
  })
})
