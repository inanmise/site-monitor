import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import UserDetailPanel from '../components/admin/UserDetailPanel.jsx'
import TeamLdapAuditModal from '../components/admin/TeamLdapAuditModal.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const confirmMock = vi.hoisted(() => vi.fn())

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getContacts: vi.fn(), getPermissionMatrix: vi.fn(), history: vi.fn(), userPush: { explain: vi.fn() },
      userTeamMembership: vi.fn(), userLdapCheck: vi.fn(), userLdapResync: vi.fn(),
      teamLdapCheck: vi.fn(), teamLdapResync: vi.fn(),
    },
  }),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: confirmMock }),
  DialogProvider: ({ children }) => children,
}))
import { api } from '../api/client'

/**
 * Prod hatası 2026-09-26 — "bu kişi bu takıma NEDEN üye?" ekrandan cevaplanmalı ve düzeltme veritabanı
 * elle düzenlenmeden, onaylı + denetimli yapılmalı. Adlar yer tutucu.
 */
const TEAMS = [{ id: 1, name: 'Takım A' }, { id: 2, name: 'Takım B' }]
const X = { id: 3, username: 'KULLANICI_X', display_name: 'Kullanıcı X', email: 'x@example.com', system_role: 'TEAM_ADMIN',
  team_id: 1, team_ids: [1, 2], active: true, auth_source: 'LDAP' }

const CHECK = {
  user_id: 3, found: true, team_locked: false,
  memberships: [
    { team_id: 1, team_name: 'Takım A', primary: true, source: 'LEGACY', detail: null, supported_by_ad: false, on_resync: 'REMOVE', on_login: 'KEEP' },
    { team_id: 2, team_name: 'Takım B', primary: false, source: 'LDAP_GROUP', detail: 'Takım B', supported_by_ad: true, on_resync: 'KEEP', on_login: 'KEEP' },
  ],
  to_add: [],
  ignored_groups: [{ dn: 'CN=Takım A,OU=ScrumGroupsArchive,DC=example,DC=com', reason: 'NOT_TEAM_OU' }],
  manager: { candidates: { extensionAttribute4: '100004', manager: '100005' }, attributes_disagree: true,
    ad_sicil: '100004', ad_manager_name: 'Bölüm Başkanı B', db_sicil: '100004', db_manager_name: 'Bölüm Başkanı B',
    matches_ad: true, db_consistent: true },
}

describe('UserDetailPanel — üyelik kaynağı + AD ile karşılaştır', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getContacts.mockResolvedValue({ success: true, data: [] })
    api.admin.getPermissionMatrix.mockResolvedValue({ success: true, catalog: [], grants: [] })
    api.admin.history.mockResolvedValue({ success: true, items: [] })
    api.admin.userPush.explain.mockResolvedValue({ success: true, members: [] })
    api.admin.userTeamMembership.mockResolvedValue({ success: true, data: { user_id: 3, team_locked: false, memberships: [
      { team_id: 1, team_name: 'Takım A', primary: true, legacy_primary_only: true, source: 'LEGACY' },
      { team_id: 2, team_name: 'Takım B', primary: false, source: 'LDAP_GROUP', detail: 'Takım B', updated_by: 'LDAP', updated_at: '2026-09-26T08:00:00' },
    ] } })
    api.admin.userLdapCheck.mockResolvedValue({ success: true, data: CHECK })
    api.admin.userLdapResync.mockResolvedValue({ success: true, data: { teams_before: [1, 2], teams_after: [2] } })
  })

  afterEach(async () => { await act(() => new Promise((r) => setTimeout(r, 0))) })   // bekleyen bölüm yanıtları
  // Kullanıcı Detayı sekmeli (2026-09-27): üyelikler "Takımlar", AD karşılaştırması "Dizin (AD)" sekmesinde.
  // Radix Tabs tetiği jsdom'da mousedown ile etkinleşir (SHADCN.md §8.3).
  const openTab = (re) => fireEvent.mouseDown(screen.getByRole('tab', { name: re }), { button: 0 })
  /** Çizer ve ilk bölüm yüklemelerini act içinde boşaltır. */
  const renderAct = async (ui) => { let out; await act(async () => { out = render(ui) }); return out }

  it('her takım üyeliği KAYNAĞIYLA görünür (AD grubu / kaynak kaydı yok) ve kanıt satırda yazar', async () => {
    await renderAct(<UserDetailPanel user={X} teams={TEAMS} isAdmin onClose={() => {}} />)
    openTab(/^(Takımlar|Teams)/)
    const list = await screen.findByTestId('user-team-sources')
    const rowA = list.querySelector('[data-team-id="1"]')
    const rowB = list.querySelector('[data-team-id="2"]')
    expect(rowA.querySelector('[data-source]').getAttribute('data-source')).toBe('LEGACY')
    expect(rowB.querySelector('[data-source]').getAttribute('data-source')).toBe('LDAP_GROUP')
    expect(rowA.textContent).toMatch(/yalnız birincil takım alanında|primary-team field only/)
    expect(rowB.textContent).toContain('LDAP · 2026-09-26T08:00:00')
    expect(api.admin.userTeamMembership).toHaveBeenCalledWith(3)
  })

  it('AD ile karşılaştır: desteklenmeyen üyelik + eşitleme sonucu + müdür nitelik uyuşmazlığı; yeniden eşitle onayla çalışır', async () => {
    const onChanged = vi.fn()
    await renderAct(<UserDetailPanel user={X} teams={TEAMS} isAdmin onClose={() => {}} onChanged={onChanged} />)
    openTab(/Dizin \(AD\)|Directory \(AD\)/)
    const box = await screen.findByTestId('ldap-compare')
    fireEvent.click(within(box).getByRole('button', { name: /AD ile karşılaştır|Compare with AD/ }))
    const rows = await within(box).findByTestId('ldap-compare-rows')
    const rowA = rows.querySelector('[data-team-id="1"]')
    expect(rowA.querySelector('[data-supported]').getAttribute('data-supported')).toBe('false')
    expect([...rowA.querySelectorAll('[data-action]')].map(e => e.getAttribute('data-action'))).toEqual(['REMOVE', 'KEEP'])
    expect(within(box).getByTestId('ldap-compare-manager').querySelector('[data-flag="disagree"]').textContent)
      .toContain('extensionAttribute4=100004, manager=100005')
    expect(box.textContent).toMatch(/takım OU'su dışında|outside the team OU/)

    confirmMock.mockResolvedValueOnce(false)
    fireEvent.click(within(box).getByRole('button', { name: /AD'den yeniden eşitle|Re-sync from AD/ }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalledTimes(1))
    expect(api.admin.userLdapResync).not.toHaveBeenCalled()

    confirmMock.mockResolvedValueOnce(true)
    fireEvent.click(within(box).getByRole('button', { name: /AD'den yeniden eşitle|Re-sync from AD/ }))
    await waitFor(() => expect(api.admin.userLdapResync).toHaveBeenCalledWith(3))
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
    await waitFor(() => expect(api.admin.userTeamMembership).toHaveBeenCalledTimes(2))   // kaynaklar tazelendi
  })

  it('AD karşılaştırması yalnız admin + LDAP hesapta çizilir (sekme ve başlık girişi dâhil)', async () => {
    const dirTab = () => screen.queryByRole('tab', { name: /Dizin \(AD\)|Directory \(AD\)/ })
    const adEntry = () => screen.queryByRole('button', { name: /AD ile denetle|Check against AD/ })
    const { unmount } = await renderAct(<UserDetailPanel user={{ ...X, auth_source: 'LOCAL' }} teams={TEAMS} isAdmin onClose={() => {}} />)
    await screen.findByTestId('user-detail')
    expect(dirTab()).toBeNull()
    expect(adEntry()).toBeNull()
    expect(screen.queryByTestId('ldap-compare')).toBeNull()
    unmount()
    const second = await renderAct(<UserDetailPanel user={X} teams={TEAMS} isAdmin={false} onClose={() => {}} />)
    await screen.findByTestId('user-detail')
    expect(dirTab()).toBeNull()
    expect(adEntry()).toBeNull()
    expect(screen.queryByTestId('ldap-compare')).toBeNull()
    second.unmount()
    await renderAct(<UserDetailPanel user={X} teams={TEAMS} isAdmin onClose={() => {}} />)
    expect(dirTab()).not.toBeNull()
    expect(adEntry()).not.toBeNull()
  })
})

describe('TeamLdapAuditModal — takım üyeliklerini AD ile denetle', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.teamLdapCheck.mockResolvedValue({ success: true, data: { team_id: 1, team_name: 'Takım A', checked: 2, truncated: false, members: [
      { user_id: 2, username: 'KULLANICI_Y', display_name: 'Kullanıcı Y', active: true, auth_source: 'LDAP', team_locked: false, primary: true,
        source: 'LDAP_GROUP', detail: 'Takım A', ldap_found: true, supported_by_ad: true, on_resync: 'KEEP', on_login: 'KEEP' },
      { user_id: 3, username: 'KULLANICI_X', display_name: 'Kullanıcı X', active: true, auth_source: 'LDAP', team_locked: false, primary: false,
        source: 'LEGACY', detail: null, ldap_found: true, supported_by_ad: false, on_resync: 'REMOVE', on_login: 'KEEP' },
    ] } })
    api.admin.teamLdapResync.mockResolvedValue({ success: true, data: { ok: 2, failed: 0, skipped: 0, removed_from_team: 1, results: [] } })
  })

  it('müdür AYRI satırda "takım üyesi değil" diye yazar; üye başına kaynak + AD desteği + sonuç', async () => {
    render(<TeamLdapAuditModal team={{ id: 1, name: 'Takım A' }} onClose={() => {}}
      managerInfo={{ label: 'Kullanıcı M', manual: false, reports: ['Kullanıcı Y'] }} />)
    const list = await screen.findByTestId('tla-members')
    expect(screen.getByTestId('tla-manager').textContent).toMatch(/türetilmiş — takım üyesi değil|derived — not a team member/)
    expect(screen.getByTestId('tla-manager').textContent).toContain('Kullanıcı Y')
    const x = list.querySelector('[data-user-id="3"]')
    expect(x.querySelector('[data-source]').getAttribute('data-source')).toBe('LEGACY')
    expect(x.querySelector('[data-supported]').getAttribute('data-supported')).toBe('false')
    expect(x.querySelector('[data-action="REMOVE"]')).not.toBeNull()
    // Müdür üye listesinde YOK
    expect(list.textContent).not.toContain('Kullanıcı M')
  })

  it('kilitsiz üyeleri eşitle: onay → istek → özet bildirimi + liste tazelenir + çağıran haberdar', async () => {
    const onChanged = vi.fn()
    render(<TeamLdapAuditModal team={{ id: 1, name: 'Takım A' }} onClose={() => {}} onChanged={onChanged} managerInfo={null} />)
    await screen.findByTestId('tla-members')
    confirmMock.mockResolvedValueOnce(true)
    fireEvent.click(screen.getByRole('button', { name: /Kilitsiz üyeleri AD'den eşitle|Re-sync unlocked members from AD/ }))
    await waitFor(() => expect(api.admin.teamLdapResync).toHaveBeenCalledWith(1))
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
    await waitFor(() => expect(api.admin.teamLdapCheck).toHaveBeenCalledTimes(2))
    expect(screen.getByTestId('tla-manager').textContent).toMatch(/türetilemedi|could be derived/)
  })
})
