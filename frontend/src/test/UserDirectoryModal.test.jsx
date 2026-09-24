import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import UserDirectoryModal, { mergeDirectory, directoryMatches, directoryCsv } from '../components/admin/useractivity/UserDirectoryModal.jsx'
import UserActivityPanel from '../components/admin/useractivity/UserActivityPanel.jsx'

/**
 * Kullanıcı Dizini (2026-09-20): "1 Aktif oturum" → bütün kullanıcılar (çevrimiçi başta), e-posta / rol / takım /
 * kaynak / son görülme / son giriş / oluşturulma / tur / hesap sütunları, süzgeç + sayfalama, zengin işlem menüsü;
 * "Turu tamamlayan" kartı aynı dizini tour=completed ile açar; giriş/anomali KPI modallarında takım sütunu.
 */
const { apiMock } = vi.hoisted(() => ({
  apiMock: { admin: { getLoginSeries: vi.fn(), getUserTimeline: vi.fn(), ackAnomaly: vi.fn(), terminateUserSession: vi.fn(), resetUserTour: vi.fn(), unlockUser: vi.fn() } },
}))
vi.mock('../api/client', () => ({ api: apiMock, formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => (s ?? '').slice(0, 10) }))
vi.mock('../components/admin/LoginActivityChart.jsx', () => ({ default: () => <div data-testid="login-chart" /> }))
import { api } from '../api/client'

const ago = (sec) => new Date(Date.now() - sec * 1000).toISOString().slice(0, 19)
const ACTIVE = [
  { username: 'bob', user_id: 2, display_name: 'Bob', system_role: 'USER', team_id: 9, team_name: 'Takim B', login_at: ago(7200), last_seen: ago(2400), idle_sec: 2400, expires_in_sec: 1200, ip: '10.0.0.2' },
  { username: 'admin', user_id: 1, display_name: 'Yonetici', system_role: 'ADMIN', team_id: 5, team_name: 'Takim A', login_at: ago(3600), last_seen: ago(20), idle_sec: 20, expires_in_sec: 3580, ip: '10.0.0.1' },
]
const STATUS = [
  { username: 'carol', user_id: 3, display_name: 'Carol', email: 'carol@example.com', system_role: 'USER', org_role: 'TECH', team_id: 5, team_name: 'Takim A', team_ids: [5, 9], auth_source: 'LDAP', active: true, created_at: '2026-01-05T10:00:00', last_seen_at: ago(45 * 86400), last_login_at: ago(45 * 86400), tour_status: 'completed', tour_at: '2026-02-01T09:00:00', employee_id: 'E-3' },
  { username: 'admin', user_id: 1, display_name: 'Yonetici', email: 'admin@example.com', system_role: 'ADMIN', team_id: 5, team_name: 'Takim A', auth_source: 'LOCAL', active: true, created_at: '2025-12-01T10:00:00', last_login_at: ago(3600), tour_status: 'dismissed' },
  { username: 'bob', user_id: 2, display_name: 'Bob', email: 'bob@example.com', system_role: 'USER', team_id: 9, team_name: 'Takim B', auth_source: 'LDAP', active: true, created_at: '2026-03-01T10:00:00', last_login_at: ago(7200), tour_status: 'completed' },
  { username: 'dave', user_id: 4, display_name: 'Dave', email: null, system_role: 'USER', team_id: 9, team_name: 'Takim B', auth_source: 'LOCAL', active: false, permanent_lock: true, created_at: '2026-04-01T10:00:00', last_login_at: null, tour_status: 'none' },
]
const DATA = {
  generated_at: '2026-09-20T00:30:00', office_hours: { start: 8, end: 20 },
  summary: { active_count: 2, logins_24h: 3, failed_24h: 0, anomalies_24h: 1, unique_users_24h: 2, total_users: 4, dormant_30d: 1, never_logged_in: 1, tour: { completed: 2, dismissed: 1, none: 1 } },
  active_users: ACTIVE, login_status: STATUS, series: { day: [] }, top_sources: [],
  anomalies: { total: 1, unacked_recent: 1, counts: { OFF_HOURS: 1 }, recent: [
    { id: 7, time: '2026-09-19T20:00:00', actor: 'bob', user_id: 2, display_name: 'Bob', system_role: 'USER', team_id: 9, team_name: 'Takim B', ip: '10.0.0.2', outcome: 'SUCCESS', flags: 'OFF_HOURS', ack: null },
  ] },
  role_team: { by_role: [], by_team: [{ team_id: 5, team_name: 'Takim A', count: 1, users: [] }, { team_id: 9, team_name: 'Takim B', count: 1, users: [] }] },
  heatmaps: [], usage: { days: 7, pages: [{ tab: 'forecast', minutes: 90, users: 2, share: 90, last_seen: ago(120) }], users: [], teams: [] },
  details: { logins: [{ time: '2026-09-19T09:00:00', actor: 'carol', team_name: 'Takim A', system_role: 'USER', user_agent: 'Chrome/1 Windows' }], failed: [], anomalies: [], unique_users: [{ username: 'bob', logins: 3, last_login: ago(7200) }], dormant: [] },
}

describe('Kullanıcı Dizini — model', () => {
  it('mergeDirectory: çevrimiçi olanlar başta (boşta süresine göre), sonra son giriş yeniden eskiye, hiç girmeyen sonda', () => {
    const rows = mergeDirectory(STATUS, ACTIVE)
    expect(rows.map((r) => r.username)).toEqual(['admin', 'bob', 'carol', 'dave'])
    expect(rows[0].online).toBe(true); expect(rows[0].idle_sec).toBe(20); expect(rows[0].email).toBe('admin@example.com')
    expect(rows[2].online).toBe(false); expect(rows[2].last_seen).toBe(STATUS[0].last_seen_at)
  })
  it('directoryMatches: görünüm / tur / takım (ek takım dâhil) / kaynak / hesap / metin', () => {
    const rows = mergeDirectory(STATUS, ACTIVE)
    const base = { view: 'all', tour: '', team: '', role: '', provider: '', account: '', q: '' }
    const names = (f) => rows.filter((r) => directoryMatches(r, { ...base, ...f })).map((r) => r.username)
    expect(names({ view: 'online' })).toEqual(['admin', 'bob'])
    expect(names({ view: 'offline' })).toEqual(['carol', 'dave'])
    expect(names({ tour: 'completed' })).toEqual(['bob', 'carol'])
    expect(names({ team: '9' })).toEqual(['bob', 'carol', 'dave'])   // carol ek takım 9
    expect(names({ provider: 'LDAP' })).toEqual(['bob', 'carol'])
    expect(names({ provider: 'LOCAL' })).toEqual(['admin', 'dave'])
    expect(names({ account: 'locked' })).toEqual(['dave'])
    expect(names({ account: 'inactive' })).toEqual(['dave'])
    expect(names({ q: 'carol@' })).toEqual(['carol'])
    expect(names({ q: 'e-3' })).toEqual(['carol'])
  })
  it('directoryCsv: başlık + satır; virgül ayırıcı, çevrimiçi 1/0, hesap durumu', () => {
    const t = (k) => k
    const csv = directoryCsv(mergeDirectory(STATUS, ACTIVE), t)
    const lines = csv.split('\r\n').filter(Boolean)
    expect(lines[0]).toContain('uact.colUser,uact.detailDisplayName,uact.detailEmail')
    expect(lines[1]).toContain('admin,Yonetici,admin@example.com,ADMIN,,Takim A,LOCAL,1,')
    expect(lines[4]).toContain('dave,Dave,,USER,,Takim B,LOCAL,0,')
    expect(lines[4].endsWith(',none,inactive')).toBe(true)
  })
})

describe('UserDirectoryModal', () => {
  beforeEach(() => { vi.clearAllMocks(); try { localStorage.clear() } catch { /* yok */ } })
  const open = (props = {}) => {
    const onUser = vi.fn(), onTerminate = vi.fn(), onRefresh = vi.fn(), onClose = vi.fn()
    render(<UserDirectoryModal data={DATA} initial={{ view: 'all' }} isAdmin globalAdmin username="admin" onClose={onClose} onUser={onUser} onTerminate={onTerminate} onRefresh={onRefresh} {...props} />)
    return { onUser, onTerminate, onRefresh, onClose, dlg: screen.getByRole('dialog') }
  }
  const rowsOf = (dlg) => within(within(dlg).getByTestId('udir-table')).getAllByRole('row').slice(1)
  const pop = () => within(screen.getByRole('menu'))   // KebabMenu portal (shadcn DropdownMenu)

  it('tüm kullanıcılar listelenir, çevrimiçi başta; sütunlarda e-posta, rol, takım, kaynak, oluşturulma, tur, hesap', () => {
    const { dlg } = open()
    expect(within(dlg).getByText(/Kullanıcı Dizini · 4|User Directory · 4/)).toBeInTheDocument()
    const rows = rowsOf(dlg)
    expect(rows).toHaveLength(4)
    expect(rows[0].className).toContain('is-online'); expect(rows[0].textContent).toContain('admin@example.com')
    expect(rows[2].className).not.toContain('is-online')
    expect(rows[2].textContent).toContain('carol@example.com')
    expect(rows[2].textContent).toContain('LDAP'); expect(rows[2].textContent).toContain('Takim A'); expect(rows[2].textContent).toContain('2026-01-05')
    expect(rows[2].textContent).toMatch(/tamamladı|completed/)
    expect(rows[3].textContent).toMatch(/Kalıcı kilitli|Permanently locked/)
    // özet çipleri
    const stats = within(dlg).getByTestId('udir-stats')
    expect(stats.textContent).toMatch(/4\s*(Tümü|All)/); expect(stats.textContent).toMatch(/2\s*(Çevrimiçi|Online)/); expect(stats.textContent).toMatch(/2\s*LDAP/)
  })

  it('"Çevrimiçi" görünümü ve metin araması daraltır; tur=completed ile açılınca başlık "Turu tamamlayan"', () => {
    const { dlg } = open()
    fireEvent.click(within(dlg).getByRole('button', { name: /^Çevrimiçi$|^Online$/ }))
    expect(rowsOf(dlg)).toHaveLength(2)
    fireEvent.change(within(dlg).getByLabelText(/^Ara$|^Search$/), { target: { value: 'bob' } })
    expect(rowsOf(dlg)).toHaveLength(1)
    fireEvent.click(within(dlg).getByRole('button', { name: /Süzgeçleri temizle|Clear filters/ }))
    expect(rowsOf(dlg)).toHaveLength(4)
  })
  it('initial.tour=completed: yalnız turu tamamlayanlar, başlık kart adıyla', () => {
    const { dlg } = open({ initial: { tour: 'completed' } })
    expect(within(dlg).getByText(/Turu tamamlayan · 2|Completed the tour · 2/)).toBeInTheDocument()
    expect(rowsOf(dlg).map((r) => r.textContent)).toEqual(expect.arrayContaining([expect.stringContaining('bob@example.com'), expect.stringContaining('carol@example.com')]))
  })

  it('işlem menüsü: Detay → onUser; çevrimiçi başkası için Oturumu sonlandır; kendi satırında yok; Turu sıfırla API + tazeleme', async () => {
    const { dlg, onUser, onTerminate, onRefresh } = open()
    api.admin.resetUserTour.mockResolvedValue({ success: true })
    const rows = rowsOf(dlg)
    // bob (çevrimiçi, başkası)
    pressMenuTrigger(within(rows[1]).getByRole('button', { name: /İşlem|Action/ }))
    fireEvent.click(pop().getByRole('menuitem', { name: /Detay|Detail/ }))
    expect(onUser).toHaveBeenCalledWith(expect.objectContaining({ username: 'bob', online: true }))
    pressMenuTrigger(within(rows[1]).getByRole('button', { name: /İşlem|Action/ }))
    fireEvent.click(pop().getByRole('menuitem', { name: /^Sonlandır$|^Terminate$/ }))
    expect(onTerminate).toHaveBeenCalledWith('bob')
    pressMenuTrigger(within(rows[1]).getByRole('button', { name: /İşlem|Action/ }))
    fireEvent.click(pop().getByRole('menuitem', { name: /Turu sıfırla|Reset the tour/ }))
    await waitFor(() => expect(api.admin.resetUserTour).toHaveBeenCalledWith(2))
    await waitFor(() => expect(onRefresh).toHaveBeenCalled())
    // admin (kendisi): sonlandırma yok
    pressMenuTrigger(within(rows[0]).getByRole('button', { name: /İşlem|Action/ }))
    expect(pop().queryByRole('menuitem', { name: /^Sonlandır$|^Terminate$/ })).toBeNull()
    fireEvent.keyDown(document, { key: 'Escape' })
  })
  it('kilitli hesapta global admin "Kilidi aç" görür ve API çağrılır; "Kullanıcı yönetiminde aç" admin sekmesine g_q ile gider', async () => {
    const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    const { dlg, onClose } = open()
    api.admin.unlockUser.mockResolvedValue({ success: true })
    const rows = rowsOf(dlg)
    pressMenuTrigger(within(rows[3]).getByRole('button', { name: /İşlem|Action/ }))
    fireEvent.click(pop().getByRole('menuitem', { name: /Kilidi aç|Unlock/ }))
    await waitFor(() => expect(api.admin.unlockUser).toHaveBeenCalledWith(4))
    pressMenuTrigger(within(rows[2]).getByRole('button', { name: /İşlem|Action/ }))
    fireEvent.click(pop().getByRole('menuitem', { name: /Kullanıcı yönetiminde aç|Open in user management/ }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'admin', params: { g_tab: 'users', g_q: 'carol' } })
    expect(onClose).toHaveBeenCalled()
    window.removeEventListener('sm:navigate', nav)
  })
  it('uzun ad: parantezli departman eki addan ayrılır, ayrı satırda ve e-posta tek satır sınıfıyla (2026-09-20 taşma bildirimi)', () => {
    const rows = [{ ...STATUS[0], display_name: 'Carol Ornek (Teknoloji Servis Yonetimi Bolumu)' }]
    render(<UserDirectoryModal data={{ ...DATA, login_status: rows, active_users: [] }} initial={{}} isAdmin username="admin" onClose={() => {}} />)
    const cell = document.querySelector('.udir-table tbody tr td')
    expect(cell.querySelector('.udir-name').textContent).toContain('Carol Ornek')
    expect(cell.querySelector('.udir-name').textContent).not.toContain('Bolumu')
    expect(cell.querySelector('.udir-meta').textContent).toContain('Teknoloji Servis Yonetimi Bolumu')
    expect(cell.querySelector('.udir-email').textContent).toBe('carol@example.com')
  })

  it('hesap süzgeci panelde değil, Durum sütun başlığında; satırdaki hesap rozetine tıklamak o duruma süzer (toggle)', () => {
    const { dlg } = open()
    const panel = dlg.querySelector('.udir-filters')
    expect(within(panel).queryByLabelText(/^Hesap$|^Account$/)).toBeNull()
    const th = dlg.querySelector('.udir-th-filter')
    expect(within(th).getByLabelText(/^Hesap$|^Account$/)).toBeInTheDocument()
    fireEvent.click(within(rowsOf(dlg)[3]).getByRole('button', { name: /Kalıcı kilitli|Permanently locked/ }))
    expect(rowsOf(dlg)).toHaveLength(1)
    expect(rowsOf(dlg)[0].textContent).toContain('dave')
    fireEvent.click(within(rowsOf(dlg)[0]).getByRole('button', { name: /Kalıcı kilitli|Permanently locked/ }))
    expect(rowsOf(dlg)).toHaveLength(4)
    // başlıktaki seçici: Pasif
    fireEvent.mouseDown(within(th).getByLabelText(/^Hesap$|^Account$/))
    fireEvent.mouseDown((within(dlg).getAllByText(/^Pasif$|^Inactive$/)).find((el) => el.closest('.ss-option')))
    expect(rowsOf(dlg)).toHaveLength(1)
  })

  it('sayfalama: 30 kullanıcıda 25 satır + sayfa çubuğu', () => {
    const many = Array.from({ length: 30 }, (_, i) => ({ username: `u${i}`, user_id: 100 + i, system_role: 'USER', auth_source: 'LDAP', active: true, last_login_at: ago(i * 3600), tour_status: 'none' }))
    render(<UserDirectoryModal data={{ ...DATA, login_status: many, active_users: [] }} initial={{}} isAdmin username="admin" onClose={() => {}} />)
    const dlg = screen.getByRole('dialog')
    expect(rowsOf(dlg)).toHaveLength(25)
    expect(within(dlg).getByRole('navigation')).toBeInTheDocument()
  })
})

describe('UserActivityPanel — dizin bağlantıları ve takım sütunları', () => {
  beforeEach(() => {
    vi.clearAllMocks(); window.history.replaceState({}, '', '/?tab=health')
    api.admin.getLoginSeries.mockResolvedValue({ success: true, data: { buckets: [], granularity: 'day' } })
    api.admin.getUserTimeline.mockResolvedValue({ success: true, data: { logins: 1, failed: 0, distinct_ips: 1, events: [] } })
  })
  const renderPanel = () => render(<UserActivityPanel data={DATA} error={false} refreshing={false} onRefresh={vi.fn()} isAdmin globalAdmin username="admin" />)

  it('"Aktif oturum" kartı ve hero bağlantısı dizini (tüm kullanıcılar) açar; "Turu tamamlayan" kartı tour=completed ile', async () => {
    renderPanel()
    fireEvent.click(document.querySelector('.uact-hero-live'))
    let dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByText(/Kullanıcı Dizini · 4|User Directory · 4/)).toBeInTheDocument()
    // Altlıktaki kapat düğmesi: başlıktaki X de artık adlı (i18n "Kapat/Close") — altlığa daraltılır.
    fireEvent.click(within(dlg.querySelector('[data-slot="dialog-footer"]')).getByRole('button', { name: /^Kapat$|^Dismiss$|^Close$/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    fireEvent.click(document.querySelectorAll('.uact-kpi--btn')[0])   // Turu tamamlayan karti
    dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByText(/Turu tamamlayan · 2|Completed the tour · 2/)).toBeInTheDocument()
  })
  it('giriş KPI modalında takım + tarayıcı sütunu; anomali bölümünde takım sütunu; sayfa kullanımı pay hücresi ayrı etiket', async () => {
    renderPanel()
    fireEvent.click(document.querySelectorAll('.uact-kpi--btn')[2])   // Login karti
    const dlg = await screen.findByRole('dialog')
    const row = within(dlg).getAllByRole('row')[1]
    expect(row.textContent).toContain('Takim A'); expect(row.textContent).toContain('Chrome'); expect(row.textContent).not.toContain('USER')   // yalnız ad soyad (2026-09-21)
    fireEvent.keyDown(document, { key: 'Escape' })
    const anomRow = document.querySelector('.uact-flag').closest('tr')
    expect(anomRow.textContent).toContain('Takim B')
    expect(document.querySelector('.uact-share-lbl').textContent).toBe('90%')
    expect(document.querySelector('.uact-bar-lbl')).toBeNull()
  })
  it('oturum detayı: hesap bölümü (oluşturulma, tur), ek takımlar, "Tam kullanıcı kartı" ve "Turu sıfırla" düğmeleri', async () => {
    renderPanel()
    // 06 giriş durumu tablosundaki carol satırı → Detay
    const carolRow = screen.getAllByText('Carol').map((n) => n.closest('tr')).find(Boolean)
    fireEvent.click(within(carolRow).getByRole('button', { name: /Detay|Detail/ }))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByText(/^Hesap durumu$|^Account status$/)).toBeInTheDocument()
    expect(within(dlg).getByText('2026-01-05T10:00:00')).toBeInTheDocument()
    expect(within(dlg).getByText(/tamamladı|completed/)).toBeInTheDocument()
    expect(within(dlg).getByRole('button', { name: /Tam kullanıcı kartı|Full user card/ })).toBeInTheDocument()
    expect(within(dlg).getByRole('button', { name: /Turu sıfırla|Reset the tour/ })).toBeInTheDocument()
    expect(within(dlg).getByRole('button', { name: /Kullanıcı yönetiminde aç|Open in user management/ })).toBeInTheDocument()
  })
})
