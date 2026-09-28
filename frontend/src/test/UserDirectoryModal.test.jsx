import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import UserDirectoryModal, { mergeDirectory, directoryMatches, directoryCsv } from '../components/admin/useractivity/UserDirectoryModal.jsx'
import UserActivityPanel from '../components/admin/useractivity/UserActivityPanel.jsx'

/**
 * Kullanıcı Dizini (2026-09-28 yeniden tasarım; ilk sürüm 2026-09-20). Özet kutucukları (süzgeç düğmeleri), faset süzgeç
 * çubuğu + etkin süzgeç çipleri, sıralanabilir tablo (lg+) / kart listesi (telefon + tablet), ayrıntı paneli (Sheet),
 * yetkiye bağlı eylemler, yenilemede durum korunumu, CSV. Sorgular rol / erişilebilir ad / data-slot'a bağlı.
 * Zaman: damgalar Date.now()'dan türetilir (24 sa / 7 gün kovaları sabit tarihe karşı ölçülmez).
 */
const { apiMock, csvSpy } = vi.hoisted(() => ({
  apiMock: { admin: { getLoginSeries: vi.fn(), getUserTimeline: vi.fn(), ackAnomaly: vi.fn(), terminateUserSession: vi.fn(), resetUserTour: vi.fn(), unlockUser: vi.fn() } },
  csvSpy: vi.fn(),
}))
vi.mock('../api/client', () => ({ api: apiMock, formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => (s ?? '').slice(0, 10) }))
vi.mock('../components/admin/LoginActivityChart.jsx', () => ({ default: () => <div data-testid="login-chart" /> }))
vi.mock('../utils/csvExport.js', async (importOriginal) => ({ ...(await importOriginal()), downloadCsv: csvSpy }))
import { api } from '../api/client'

const ago = (sec) => new Date(Date.now() - sec * 1000).toISOString().slice(0, 19)
const DAY = 86_400
const CHROME_WIN = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36'
const ACTIVE = [
  { username: 'bob', user_id: 2, display_name: 'Bob', system_role: 'USER', team_id: 9, team_name: 'Takım B', login_at: ago(7200), last_seen: ago(2400), idle_sec: 2400, expires_in_sec: 1200, duration_min: 120, ip: '10.0.0.2', city: 'Ankara', country: 'TR', org: 'Örnek ISS', user_agent: CHROME_WIN, last_tab: 'forecast', last_tab_at: ago(2400) },
  // yönetici kendi satırı: bağlantı alanları sunucuda düşürülmüş (kimlik maskesi) → anahtar HİÇ yok
  { username: 'admin', user_id: 1, display_name: 'Yonetici', system_role: 'ADMIN', team_id: 5, team_name: 'Takım A', login_at: ago(3600), last_seen: ago(20), idle_sec: 20, expires_in_sec: 3580, duration_min: 60 },
]
const STATUS = [
  { username: 'carol', user_id: 3, display_name: 'Carol Ornek (Teknoloji Servis Bolumu)', email: 'carol@example.com', system_role: 'USER', org_role: 'TECH', team_id: 5, team_name: 'Takım A', team_ids: [5, 9], auth_source: 'LDAP', active: true, created_at: '2026-01-05T10:00:00', last_seen_at: ago(45 * DAY), last_login_at: ago(45 * DAY), tour_status: 'completed', tour_at: '2026-02-01T09:00:00', employee_id: 'E-3' },
  { username: 'admin', user_id: 1, display_name: 'Yonetici', email: 'admin@example.com', system_role: 'ADMIN', team_id: 5, team_name: 'Takım A', auth_source: 'LOCAL', active: true, created_at: '2025-12-01T10:00:00', last_login_at: ago(3600), tour_status: 'dismissed' },
  { username: 'bob', user_id: 2, display_name: 'Bob', email: 'bob@example.com', system_role: 'USER', team_id: 9, team_name: 'Takım B', auth_source: 'LDAP', active: true, created_at: '2026-03-01T10:00:00', last_login_at: ago(7200), last_login_method: 'LDAP', last_login_ip: '10.0.0.2', failed_since_login: 2, tour_status: 'completed' },
  { username: 'dave', user_id: 4, display_name: 'Dave', email: null, system_role: 'USER', team_id: 9, team_name: 'Takım B', auth_source: 'LOCAL', active: false, permanent_lock: true, created_at: '2026-04-01T10:00:00', last_login_at: null, tour_status: 'none' },
  { username: 'erin', user_id: 5, display_name: 'Erin', email: 'erin@example.com', system_role: 'AUDIT', team_id: 5, team_name: 'Takım A', auth_source: 'LDAP', active: true, created_at: '2026-05-01T10:00:00', last_seen_at: ago(3 * DAY), last_login_at: ago(3 * DAY), tour_status: 'started' },
]
const DATA = {
  generated_at: '2026-09-20T00:30:00', office_hours: { start: 8, end: 20 },
  summary: { active_count: 2, logins_24h: 3, failed_24h: 0, anomalies_24h: 1, unique_users_24h: 2, total_users: 5, dormant_30d: 1, never_logged_in: 1, tour: { completed: 2, dismissed: 1, none: 1 } },
  active_users: ACTIVE, login_status: STATUS, series: { day: [] }, top_sources: [],
  anomalies: { total: 1, unacked_recent: 1, counts: { OFF_HOURS: 1 }, recent: [
    { id: 7, time: '2026-09-19T20:00:00', actor: 'bob', user_id: 2, display_name: 'Bob', system_role: 'USER', team_id: 9, team_name: 'Takım B', ip: '10.0.0.2', outcome: 'SUCCESS', flags: 'OFF_HOURS', ack: null },
  ] },
  role_team: { by_role: [], by_team: [{ team_id: 5, team_name: 'Takım A', count: 1, users: [] }, { team_id: 9, team_name: 'Takım B', count: 1, users: [] }] },
  heatmaps: [], usage: { days: 7, pages: [{ tab: 'forecast', minutes: 90, users: 2, share: 90, last_seen: ago(120) }], users: [], teams: [] },
  details: { logins: [{ time: '2026-09-19T09:00:00', actor: 'carol', team_name: 'Takım A', system_role: 'USER', user_agent: 'Chrome/1 Windows' }], failed: [], anomalies: [], unique_users: [{ username: 'bob', logins: 3, last_login: ago(7200) }], dormant: [] },
}

function setWidth(w) {
  Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: w })
  act(() => { window.dispatchEvent(new Event('resize')) })
}
// Dizin penceresi (ayrıntı Sheet'i açıkken Radix onu aria-hidden yapar — rol sorgusu yerine kancadan bulunur)
const dialog = () => document.querySelector('[data-slot="udir-title"]').closest('[role="dialog"]')
const table = () => within(dialog()).getByTestId('udir-table')
const rowsOf = () => within(table()).getAllByRole('row').slice(1)
const users = () => rowsOf().map((r) => r.getAttribute('data-user'))
const stats = () => within(dialog()).getByRole('region', { name: /Dizin özeti|Directory summary/ })
const tile = (name) => within(stats()).getByRole('button', { name })
const detail = () => document.querySelector('[data-slot="udir-detail"]')
const menuOf = (user) => {
  const row = document.querySelector(`[data-user="${user}"]`)
  pressMenuTrigger(within(row).getByRole('button', { name: /İşlem|Action/ }))
  return within(screen.getByRole('menu'))
}
const closeMenu = () => fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' })

function open(props = {}) {
  const cb = { onUser: vi.fn(), onTerminate: vi.fn(), onRefresh: vi.fn(), onClose: vi.fn() }
  const utils = render(<UserDirectoryModal data={DATA} initial={{ view: 'all' }} isAdmin globalAdmin username="admin" {...cb} {...props} />)
  return { ...cb, ...utils }
}

beforeEach(() => { vi.clearAllMocks(); try { localStorage.clear() } catch { /* yok */ }; setWidth(1024) })
afterEach(() => setWidth(1024))

describe('Kullanıcı Dizini — geriye uyum (model dışa aktarımları)', () => {
  it('mergeDirectory / directoryMatches / directoryCsv bileşen dosyasından da erişilir', () => {
    const rows = mergeDirectory(STATUS, ACTIVE)
    expect(rows.map((r) => r.username)).toEqual(['admin', 'bob', 'erin', 'carol', 'dave'])
    expect(rows.filter((r) => directoryMatches(r, { view: 'all', tour: 'completed' })).map((r) => r.username)).toEqual(['bob', 'carol'])
    expect(directoryCsv(rows, (k) => k).split('\r\n')[1]).toContain('admin,Yonetici,admin@example.com,ADMIN')
  })
})

describe('UserDirectoryModal — masaüstü (tablo)', () => {
  it('bütün kullanıcılar, çevrimiçi başta; kişi hücresi, rozetler, son giriş; özet kutucukları sayıları', () => {
    open()
    expect(within(dialog()).getByText(/Kullanıcı Dizini · 5|User Directory · 5/)).toBeInTheDocument()
    expect(users()).toEqual(['admin', 'bob', 'erin', 'carol', 'dave'])
    const [admin, bob, , carol, dave] = rowsOf()
    expect(admin).toHaveAttribute('data-online', 'true'); expect(admin).toHaveAttribute('data-self', 'true')
    expect(carol).not.toHaveAttribute('data-online')
    // kişi hücresi: ad (parantezli departman eki ayrı satırda), e-posta, kullanıcı adı · departman
    expect(carol.querySelector('[data-part="name"]').textContent).toBe('Carol Ornek')
    expect(carol.querySelector('[data-part="meta"]').textContent).toBe('carol · Teknoloji Servis Bolumu')
    expect(carol.querySelector('[data-part="email"]').textContent).toBe('carol@example.com')
    expect(carol.textContent).toMatch(/LDAP/); expect(carol.textContent).toContain('Takım A'); expect(carol.textContent).toContain('2026-01-05')
    expect(bob.textContent).toMatch(/Çevrimiçi|Online/); expect(bob.textContent).toMatch(/boşta 40 dk|idle for 40 min/)
    expect(bob.querySelector('[data-failed="2"]')).not.toBeNull()   // son girişten beri başarısız deneme
    expect(dave.querySelector('[data-account="locked"]')).not.toBeNull(); expect(dave.querySelector('[data-account="inactive"]')).not.toBeNull()
    expect(dave.querySelector('[data-status="never"]')).not.toBeNull()
    // özet kutucukları (bütün dizin): toplam 5, çevrimiçi 2, son 7 gün 3, kilitli/pasif 1, LDAP 3 / Yerel 2, tur 2
    expect(tile(/Toplam kullanıcı|Total users/).textContent).toMatch(/5/)
    expect(tile(/Şu an çevrimiçi|Online now/).textContent).toMatch(/2/)
    expect(tile(/Son 7 günde giriş|Signed in, last 7 days/).textContent).toMatch(/3/)
    expect(tile(/Kilitli \/ pasif|Locked or inactive/).textContent).toMatch(/1/)
    expect(tile(/^LDAP/).textContent).toMatch(/3/); expect(tile(/^Yerel|^Local/).textContent).toMatch(/2/)
    expect(tile(/Turu tamamlayan|Completed the tour/).textContent).toMatch(/2/)
    expect(within(dialog()).getByText(/Gösterilen: 5 \/ 5|Showing 5 of 5/)).toBeInTheDocument()
  })

  it('özet kutucukları SÜZGEÇ düğmesidir (aria-pressed): çevrimiçi, kilitli/pasif, LDAP, tur, 7 gün; Toplam hepsini temizler', () => {
    open()
    const total = tile(/Toplam kullanıcı|Total users/)
    expect(total).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(tile(/Şu an çevrimiçi|Online now/))
    expect(tile(/Şu an çevrimiçi|Online now/)).toHaveAttribute('aria-pressed', 'true')
    expect(total).toHaveAttribute('aria-pressed', 'false')
    expect(users()).toEqual(['admin', 'bob'])
    fireEvent.click(tile(/Şu an çevrimiçi|Online now/))   // yeniden basınca bırakır
    expect(users()).toHaveLength(5)
    fireEvent.click(tile(/Kilitli \/ pasif|Locked or inactive/)); expect(users()).toEqual(['dave'])
    fireEvent.click(total); expect(users()).toHaveLength(5)
    fireEvent.click(tile(/^LDAP/)); expect(users()).toEqual(['bob', 'erin', 'carol'])
    fireEvent.click(tile(/Turu tamamlayan|Completed the tour/)); expect(users()).toEqual(['bob', 'carol'])   // fasetler arası VE
    fireEvent.click(total)
    fireEvent.click(tile(/Son 7 günde giriş|Signed in, last 7 days/)); expect(users()).toEqual(['admin', 'bob', 'erin'])
  })

  it('faset süzgeci (Rol) çoklu seçim; etkin süzgeç çipi × ile kaldırılır; "Süzgeçleri temizle"', () => {
    open()
    const bar = within(dialog()).getByRole('group', { name: /^Süzgeçler$|^Filters$/ })
    fireEvent.click(within(bar).getByRole('button', { name: /^Rol|^Role/ }))
    fireEvent.click(screen.getByRole('option', { name: /^ADMIN/ }))
    fireEvent.click(screen.getByRole('option', { name: /^AUDIT/ }))
    expect(users()).toEqual(['admin', 'erin'])
    const chips = within(dialog()).getByRole('group', { name: /Etkin süzgeçler|Active filters/ })
    fireEvent.click(within(chips).getByRole('button', { name: /(Süzgeci kaldır|Remove filter): (Rol|Role): ADMIN/ }))
    expect(users()).toEqual(['erin'])
    fireEvent.click(within(chips).getByRole('button', { name: /^Süzgeçleri temizle$|^Clear filters$/ }))
    expect(users()).toHaveLength(5)
    expect(within(dialog()).queryByRole('group', { name: /Etkin süzgeçler|Active filters/ })).toBeNull()
  })

  it('arama (aksan/harf duyarsız) + "eşleşen yok" durumu ve temizleme eylemi', () => {
    open()
    const search = within(dialog()).getByRole('searchbox', { name: /^Ara$|^Search$/ })
    fireEvent.change(search, { target: { value: 'TAKIM b' } })
    expect(users()).toEqual(['bob', 'dave'])   // takım ADI araması; "TAKIM" → "Takım" (ı/i katlaması)
    fireEvent.change(search, { target: { value: 'zzz-yok' } })
    expect(within(dialog()).queryByTestId('udir-table')).toBeNull()
    const empty = within(dialog()).getByText(/Süzgeçlerle eşleşen kullanıcı yok|No users match these filters/).closest('[data-slot="empty"]')
    fireEvent.click(within(empty).getByRole('button', { name: /^Süzgeçleri temizle$|^Clear filters$/ }))
    expect(users()).toHaveLength(5)
  })

  it('sıralanabilir başlıklar: Kişi A→Z / Z→A (aria-sort), Son giriş en yeni önce ve hiç girmeyen sonda', () => {
    open()
    const head = (name) => within(table()).getByRole('columnheader', { name })
    fireEvent.click(within(head(/Kişi|Person/)).getByRole('button'))
    expect(head(/Kişi|Person/)).toHaveAttribute('aria-sort', 'ascending')
    expect(users()).toEqual(['bob', 'carol', 'dave', 'erin', 'admin'])
    fireEvent.click(within(head(/Kişi|Person/)).getByRole('button'))
    expect(head(/Kişi|Person/)).toHaveAttribute('aria-sort', 'descending')
    expect(users()).toEqual(['admin', 'erin', 'dave', 'carol', 'bob'])
    fireEvent.click(within(head(/Son giriş|Last sign-in/)).getByRole('button'))
    expect(head(/Son giriş|Last sign-in/)).toHaveAttribute('aria-sort', 'descending')
    expect(head(/Kişi|Person/)).toHaveAttribute('aria-sort', 'none')
    expect(users()).toEqual(['admin', 'bob', 'erin', 'carol', 'dave'])
    fireEvent.click(within(head(/Son giriş|Last sign-in/)).getByRole('button'))
    expect(users()).toEqual(['carol', 'erin', 'bob', 'admin', 'dave'])   // en eski önce, hiç girmeyen YİNE sonda
  })

  it('initial.tour=completed ("Turu tamamlayan" kartı): başlık kartın adı, tur kutucuğu basılı', () => {
    open({ initial: { tour: 'completed' } })
    expect(within(dialog()).getByText(/Turu tamamlayan · 2 \/ 5|Completed the tour · 2 \/ 5/)).toBeInTheDocument()
    expect(users()).toEqual(['bob', 'carol'])
    expect(tile(/Turu tamamlayan|Completed the tour/)).toHaveAttribute('aria-pressed', 'true')
  })

  it('sayfalama (modal ön ayarı 10): 30 kullanıcıda 10 satır + compact sayfa çubuğu', () => {
    const many = Array.from({ length: 30 }, (_, i) => ({ username: `u${i}`, user_id: 100 + i, system_role: 'USER', auth_source: 'LDAP', active: true, last_login_at: ago(i * 3600 + 60), tour_status: 'none' }))
    open({ data: { ...DATA, login_status: many, active_users: [] } })
    expect(rowsOf()).toHaveLength(10)
    expect(within(dialog()).getByRole('navigation')).toBeInTheDocument()
    expect(within(dialog()).getByText('1 / 3')).toBeInTheDocument()
  })
})

describe('ayrıntı paneli (Sheet)', () => {
  it('satıra tıklamak paneli açar: oturum + zaman aşımı, bağlantı (User-Agent okunur), hesap, giriş geçmişi; menü tetiği satırı AÇMAZ', () => {
    open()
    const bobRow = rowsOf()[1]
    pressMenuTrigger(within(bobRow).getByRole('button', { name: /İşlem|Action/ }))
    expect(detail()).toBeNull()
    closeMenu()
    fireEvent.click(bobRow)
    const d = within(detail())
    expect(d.getByRole('heading', { name: /Bob/ })).toBeInTheDocument()
    expect(d.getByText('Chrome 128')).toBeInTheDocument()
    expect(d.getByText('Windows 10/11')).toBeInTheDocument()
    expect(d.getByText(/Masaüstü|Desktop/)).toBeInTheDocument()
    expect(d.getAllByText('10.0.0.2', { selector: 'dd' })).toHaveLength(2)   // oturum IP'si + son giriş IP'si
    expect(d.getByText('Ankara, TR')).toBeInTheDocument()
    expect(detail().querySelector('[data-slot="udir-timeout"]').textContent).toMatch(/20 dk sonra düşer|times out in 20 min/)
    expect(detail().querySelector('[data-slot="udir-timeout"] [role="progressbar"]')).toHaveAttribute('aria-valuenow', '67')
    expect(d.getByText(/Vade Takvimi|Expiry Forecast/)).toBeInTheDocument()   // son sayfa etiketi (nav sözlüğü)
    expect(detail().querySelector('[data-failed-count="2"]')).not.toBeNull()
    // tam User-Agent aç/kapa
    fireEvent.click(d.getByRole('button', { name: /Tam User-Agent'ı göster|Show full User-Agent/ }))
    expect(d.getByText(CHROME_WIN)).toBeInTheDocument()
  })

  it('bağlantı alanları sunucuda düşürülmüşse (maske) bilgi notu; çevrimdışı kişide oturum yerine "açık oturumu yok"', async () => {
    open({ isAdmin: false, globalAdmin: false, username: 'zed' })
    fireEvent.click(rowsOf()[0])   // admin: ip/user_agent anahtarı yok
    expect(within(detail()).getByText(/yalnız global yöneticilere|only to global admins/)).toBeInTheDocument()
    expect(within(detail()).getByText(/gizli \(yalnız global admin\)|hidden \(global admins only\)/)).toBeInTheDocument()   // sicil
    fireEvent.keyDown(detail(), { key: 'Escape' })
    await waitFor(() => expect(detail()).toBeNull())
    fireEvent.click(rowsOf()[3])   // carol: çevrimdışı
    expect(detail().querySelector('[data-slot="udir-offline-note"]').textContent).toMatch(/açık oturumu yok|No open session/)
    expect(within(detail()).queryByText(/Bağlantı|Connection/)).toBeNull()
  })

  it('yenileme (yeni payload) süzgeci ve açık ayrıntıyı KORUR; ayrıntı güncel veriyle çizilir; kişi düşerse "artık dizinde yok"', async () => {
    const { rerender, onUser } = open()
    fireEvent.click(tile(/Şu an çevrimiçi|Online now/))
    fireEvent.click(rowsOf()[1])
    expect(within(detail()).getByText(/boşta 40 dk|idle for 40 min/)).toBeInTheDocument()
    expect(detail().querySelector('[data-presence="away"]')).not.toBeNull()
    const fresh = { ...DATA, active_users: ACTIVE.map((u) => (u.username === 'bob' ? { ...u, idle_sec: 30, last_seen: ago(30) } : u)) }
    rerender(<UserDirectoryModal data={fresh} initial={{ view: 'all' }} isAdmin globalAdmin username="admin" onUser={onUser} onClose={() => {}} />)
    expect(detail()).not.toBeNull()
    expect(detail().querySelector('[data-presence="live"]')).not.toBeNull()   // boşta bandı canlıya döndü (güncel veri)
    const gone = { ...fresh, login_status: STATUS.filter((u) => u.username !== 'bob'), active_users: ACTIVE.filter((u) => u.username !== 'bob') }
    rerender(<UserDirectoryModal data={gone} initial={{ view: 'all' }} isAdmin globalAdmin username="admin" onUser={onUser} onClose={() => {}} />)
    expect(within(detail()).getByText(/artık dizinde yok|no longer in the directory/)).toBeInTheDocument()
    fireEvent.keyDown(detail(), { key: 'Escape' })
    await waitFor(() => expect(detail()).toBeNull())
    expect(tile(/Şu an çevrimiçi|Online now/)).toHaveAttribute('aria-pressed', 'true')   // süzgeç korunur
    expect(users()).toEqual(['admin'])
  })

  it('yenileme SAYFAYI da korur (sayfa yalnız süzgeç/sıralama değişince başa döner)', () => {
    const many = (n) => Array.from({ length: n }, (_, i) => ({ username: `u${String(i).padStart(2, '0')}`, user_id: 100 + i, system_role: 'USER', auth_source: 'LDAP', active: true, last_login_at: ago(i * 3600 + 60), tour_status: 'none' }))
    const { rerender } = open({ data: { ...DATA, login_status: many(30), active_users: [] } })
    fireEvent.click(within(dialog()).getByRole('button', { name: /^Sonraki$|^Next$/ }))
    expect(within(dialog()).getByText('2 / 3')).toBeInTheDocument()
    rerender(<UserDirectoryModal data={{ ...DATA, login_status: many(31), active_users: [] }} initial={{}} isAdmin globalAdmin username="admin" onClose={() => {}} />)
    expect(within(dialog()).getByText('2 / 4')).toBeInTheDocument()
    fireEvent.click(tile(/^LDAP/))
    expect(within(dialog()).getByText('1 / 4')).toBeInTheDocument()
  })
})

describe('yetki — eylem görünürlüğü (işlem menüsü + ayrıntı paneli)', () => {
  const labelsOf = (menu) => menu.getAllByRole('menuitem').map((m) => m.textContent)
  it('normal kullanıcı: yönetici eylemi YOK (menü ve panel)', () => {
    open({ isAdmin: false, globalAdmin: false, username: 'erin' })
    const items = labelsOf(menuOf('bob'))
    expect(items.join('|')).not.toMatch(/Kullanıcı yönetiminde aç|Open in user management|Oturumu sonlandır|End session|Turu sıfırla|Reset the tour|Kilidi aç|Unlock/)
    expect(items.join('|')).toMatch(/E-posta gönder|Send email/)
    closeMenu()
    fireEvent.click(rowsOf()[1])
    expect(detail().querySelector('[data-slot="udir-admin-actions"]')).toBeNull()
  })
  it('kapsamlı müdür (ADMIN, global değil): sonlandır / turu sıfırla / yönetimde aç VAR, kilit açma YOK', () => {
    open({ isAdmin: true, globalAdmin: false, username: 'admin' })
    const bobItems = labelsOf(menuOf('bob')).join('|')
    expect(bobItems).toMatch(/Oturumu sonlandır|End session/); expect(bobItems).toMatch(/Turu sıfırla|Reset the tour/)
    expect(bobItems).toMatch(/Kullanıcı yönetiminde aç|Open in user management/)
    closeMenu()
    expect(labelsOf(menuOf('dave')).join('|')).not.toMatch(/Kilidi aç|Unlock/)
    closeMenu()
    fireEvent.click(rowsOf()[4])
    const admin = within(detail().querySelector('[data-slot="udir-admin-actions"]'))
    expect(admin.queryByRole('button', { name: /Kilidi aç|Unlock/ })).toBeNull()
    expect(admin.getByRole('button', { name: /Kullanıcı yönetiminde aç|Open in user management/ })).toBeInTheDocument()
  })
  it('global admin: kalıcı kilitte "Kilidi aç" API + tazeleme; kendi satırında sonlandırma yok', async () => {
    const { onRefresh } = open()
    api.admin.unlockUser.mockResolvedValue({ success: true })
    expect(labelsOf(menuOf('admin')).join('|')).not.toMatch(/Oturumu sonlandır|End session/)
    closeMenu()
    fireEvent.click(menuOf('dave').getByRole('menuitem', { name: /Kilidi aç|Unlock/ }))
    await waitFor(() => expect(api.admin.unlockUser).toHaveBeenCalledWith(4))
    await waitFor(() => expect(onRefresh).toHaveBeenCalled())
  })
})

describe('eylemler', () => {
  it('menü: Ayrıntılar paneli açar; Giriş geçmişi onUser; Oturumu sonlandır paneli KAPATIP onTerminate', async () => {
    const { onUser, onTerminate } = open()
    fireEvent.click(menuOf('bob').getByRole('menuitem', { name: /Ayrıntılar|Details/ }))
    expect(detail()).not.toBeNull()
    fireEvent.click(within(detail()).getByRole('button', { name: /Oturumu sonlandır|End session/ }))
    await waitFor(() => expect(detail()).toBeNull())
    expect(onTerminate).toHaveBeenCalledWith('bob')
    fireEvent.click(menuOf('carol').getByRole('menuitem', { name: /Giriş geçmişi|Sign-in history/ }))
    expect(onUser).toHaveBeenCalledWith(expect.objectContaining({ username: 'carol', online: false }))
  })
  it('Turu sıfırla: API + tazeleme; başarısız yanıt hata bildirimi (bayrak temizlenir)', async () => {
    const { onRefresh } = open()
    api.admin.resetUserTour.mockResolvedValueOnce({ success: true }).mockRejectedValueOnce(new Error('ağ hatası'))
    fireEvent.click(menuOf('bob').getByRole('menuitem', { name: /Turu sıfırla|Reset the tour/ }))
    await waitFor(() => expect(api.admin.resetUserTour).toHaveBeenCalledWith(2))
    await waitFor(() => expect(onRefresh).toHaveBeenCalledTimes(1))
    fireEvent.click(rowsOf()[1])
    fireEvent.click(within(detail()).getByRole('button', { name: /Turu sıfırla|Reset the tour/ }))
    await waitFor(() => expect(api.admin.resetUserTour).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(within(detail()).getByRole('button', { name: /Turu sıfırla|Reset the tour/ })).not.toBeDisabled())
    expect(onRefresh).toHaveBeenCalledTimes(1)
  })
  it('E-postayı kopyala: panoya adres', async () => {
    const spy = vi.spyOn(navigator.clipboard, 'writeText')
    open()
    fireEvent.click(menuOf('carol').getByRole('menuitem', { name: /E-postayı kopyala|Copy email address/ }))
    await waitFor(() => expect(spy).toHaveBeenCalledWith('carol@example.com'))
    spy.mockRestore()
  })
  it('CSV görünen (süzgeçlenmiş + sıralanmış) listeyi indirir', () => {
    open()
    fireEvent.click(tile(/^LDAP/))
    fireEvent.click(within(dialog()).getByRole('button', { name: /Dizin \(CSV\)|Directory \(CSV\)/ }))
    expect(csvSpy).toHaveBeenCalledTimes(1)
    const [name, csv] = csvSpy.mock.calls[0]
    expect(name).toMatch(/^users-\d{8}-\d{4}\.csv$/)
    const lines = csv.split('\r\n').filter(Boolean)
    expect(lines).toHaveLength(4)
    expect(lines.slice(1).map((l) => l.split(',')[0])).toEqual(['bob', 'erin', 'carol'])
  })
})

describe('telefon düzeni (390 px)', () => {
  it('kart listesi (tablo yok); "Süzgeçler" Sheet\'inde çip süzgeci, sayaç ve "Sonuçları göster"', async () => {
    setWidth(390)
    open()
    expect(within(dialog()).queryByTestId('udir-table')).toBeNull()
    const cards = () => [...document.querySelectorAll('[data-slot="udir-card"]')].map((c) => c.getAttribute('data-user'))
    expect(cards()).toEqual(['admin', 'bob', 'erin', 'carol', 'dave'])
    fireEvent.click(within(dialog()).getByRole('button', { name: /^Süzgeçler$|^Filters$/ }))
    const sheet = within(document.querySelector('[data-slot="udir-filter-sheet"]'))
    const admin = sheet.getByRole('button', { name: /^ADMIN \(1\)$/ })
    fireEvent.click(admin)
    expect(admin).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(sheet.getByRole('button', { name: /Sonuçları göster \(1\)|Show results \(1\)/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="udir-filter-sheet"]')).toBeNull())
    expect(cards()).toEqual(['admin'])
    expect(within(dialog()).getByRole('button', { name: /^Süzgeçler \(1\)$|^Filters \(1\)$/ })).toBeInTheDocument()
  })
  it('kartın herhangi bir yerine basmak ayrıntıyı alttan açar; sıralama seçicisi kart sırasını değiştirir', () => {
    setWidth(390)
    open()
    fireEvent.change(within(dialog()).getByRole('combobox', { name: /Sırala|Sort by/ }), { target: { value: 'name:asc' } })
    expect([...document.querySelectorAll('[data-slot="udir-card"]')].map((c) => c.getAttribute('data-user'))).toEqual(['bob', 'carol', 'dave', 'erin', 'admin'])
    const card = document.querySelector('[data-slot="udir-card"][data-user="carol"]')
    fireEvent.click(within(card).getByRole('button', { name: /Carol Ornek — ayrıntıları aç|Open details for Carol Ornek/ }))
    expect(detail()).not.toBeNull()
    expect(detail().className).toMatch(/rounded-t-xl/)   // telefonda alttan açılan panel
  })
})

describe('UserActivityPanel — dizin bağlantıları ve takım sütunları', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=health')
    api.admin.getLoginSeries.mockResolvedValue({ success: true, data: { buckets: [], granularity: 'day' } })
    api.admin.getUserTimeline.mockResolvedValue({ success: true, data: { logins: 1, failed: 0, distinct_ips: 1, events: [] } })
  })
  const renderPanel = (props = {}) => render(<UserActivityPanel data={DATA} error={false} refreshing={false} onRefresh={vi.fn()} isAdmin globalAdmin username="admin" {...props} />)

  it('"Aktif oturum" hero bağlantısı dizini (tüm kullanıcılar) açar; "Turu tamamlayan" kartı tour=completed ile', async () => {
    renderPanel()
    fireEvent.click(document.querySelector('[data-hero-live]'))
    expect(await screen.findByText(/Kullanıcı Dizini · 5|User Directory · 5/)).toBeInTheDocument()
    fireEvent.click(within(dialog().querySelector('[data-slot="dialog-footer"]')).getByRole('button', { name: /^Kapat$|^Dismiss$|^Close$/ }))
    await waitFor(() => expect(screen.queryByText(/Kullanıcı Dizini · 5|User Directory · 5/)).toBeNull())
    fireEvent.click(document.querySelector('[data-kpi="tour"]'))
    expect(await screen.findByText(/Turu tamamlayan · 2|Completed the tour · 2/)).toBeInTheDocument()
  })
  it('oturum sonlandırma ONAYLI: ayrıntı panelinden → gerekçeli onay penceresi → API + tazeleme', async () => {
    const onTerminated = vi.fn()
    api.admin.terminateUserSession.mockResolvedValue({ success: true })
    renderPanel({ onTerminated })
    fireEvent.click(document.querySelector('[data-hero-live]'))
    await screen.findByText(/Kullanıcı Dizini · 5|User Directory · 5/)
    fireEvent.click(rowsOf()[1])
    fireEvent.click(within(detail()).getByRole('button', { name: /Oturumu sonlandır|End session/ }))
    const confirm = await screen.findByRole('dialog', { name: /Oturumu sonlandır — bob|Terminate session — bob/ })
    expect(api.admin.terminateUserSession).not.toHaveBeenCalled()   // onaysız istek yok
    fireEvent.change(within(confirm).getByRole('textbox'), { target: { value: 'terk edilmiş oturum' } })
    fireEvent.click(within(confirm).getByRole('button', { name: /^Sonlandır$|^Terminate$/ }))
    await waitFor(() => expect(api.admin.terminateUserSession).toHaveBeenCalledWith('bob', 'terk edilmiş oturum'))
    await waitFor(() => expect(onTerminated).toHaveBeenCalled())
  })
  it('giriş KPI modalında takım + tarayıcı sütunu; anomali bölümünde takım sütunu; sayfa kullanımı pay hücresi ayrı etiket', async () => {
    renderPanel()
    fireEvent.click(document.querySelector('[data-kpi="logins"]'))
    const dlg = await screen.findByRole('dialog')
    const row = within(dlg).getAllByRole('row')[1]
    expect(row.textContent).toContain('Takım A'); expect(row.textContent).toContain('Chrome'); expect(row.textContent).not.toContain('USER')
    fireEvent.keyDown(document, { key: 'Escape' })
    const anomRow = document.querySelector('[data-flag]').closest('tr')
    expect(anomRow.textContent).toContain('Takım B')
    expect(document.querySelector('[data-share-lbl]').textContent).toBe('90%')
    expect(document.querySelectorAll('[data-share-lbl]')).toHaveLength(1)
  })
  it('oturum detayı: hesap bölümü (oluşturulma, tur), "Tam kullanıcı kartı" ve "Turu sıfırla" düğmeleri', async () => {
    renderPanel()
    const carolRow = screen.getAllByText('Carol Ornek').map((n) => n.closest('tr')).find(Boolean)
    fireEvent.click(within(carolRow).getByRole('button', { name: /Detay|Detail/ }))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByText(/^Hesap durumu$|^Account status$/)).toBeInTheDocument()
    expect(within(dlg).getByText('2026-01-05T10:00:00')).toBeInTheDocument()
    expect(within(dlg).getByRole('button', { name: /Tam kullanıcı kartı|Full user card/ })).toBeInTheDocument()
    expect(within(dlg).getByRole('button', { name: /Turu sıfırla|Reset the tour/ })).toBeInTheDocument()
    expect(within(dlg).getByRole('button', { name: /Kullanıcı yönetiminde aç|Open in user management/ })).toBeInTheDocument()
  })
})

describe('Kullanıcı Dizini — 2026-09-28c düzeltmeleri', () => {
  it('ek-6: giriş IP\'leri sunucuda düşürülmüşse ayrıntıda "Gizli" (kayıt yok "—" ile karışmaz); maskesiz yükte değer / "—"', async () => {
    // Sunucunun maskeli login_status satırı: IP anahtarları HİÇ yok; yük identity_masked=true taşır
    const status = STATUS.map(({ last_login_ip, prev_login_ip, last_failed_ip, ...rest }) => rest)   // eslint-disable-line no-unused-vars
    const active = ACTIVE.map(({ ip, city, country, org, user_agent, ...rest }) => rest)             // eslint-disable-line no-unused-vars
    const { unmount } = open({ data: { ...DATA, login_status: status, active_users: active, identity_masked: true }, isAdmin: false, globalAdmin: false, username: 'zed' })
    fireEvent.click(rowsOf().find((r) => r.getAttribute('data-user') === 'bob'))
    expect(detail().querySelectorAll('[data-slot="id-masked"]')).toHaveLength(3)   // son / önceki / başarısız giriş IP'si
    expect(within(detail()).queryByText('10.0.0.2', { selector: 'dd' })).toBeNull()
    unmount()
    open()   // global görüntüleyici (bayrak yok): değer ya da "—"
    fireEvent.click(rowsOf().find((r) => r.getAttribute('data-user') === 'bob'))
    expect(detail().querySelectorAll('[data-slot="id-masked"]')).toHaveLength(0)
    expect(within(detail()).getAllByText('10.0.0.2', { selector: 'dd' }).length).toBeGreaterThan(0)
  })

  it('ek-10: iki satırda eşzamanlı işlem — önce biten, diğer satırın meşgul göstergesini SİLMEZ', async () => {
    let finishTour, finishUnlock
    api.admin.resetUserTour.mockImplementation(() => new Promise((r) => { finishTour = r }))
    api.admin.unlockUser.mockImplementation(() => new Promise((r) => { finishUnlock = r }))
    open()
    const trigger = (u) => within(document.querySelector(`[data-user="${u}"]`)).queryByRole('button', { name: /İşlem|Action/ })
    fireEvent.click(menuOf('bob').getByRole('menuitem', { name: /Turu sıfırla|Reset the tour/ }))
    await waitFor(() => expect(trigger('bob')).toBeNull())                  // bob meşgul (menü yerine gösterge)
    fireEvent.click(menuOf('dave').getByRole('menuitem', { name: /Kilidi aç|Unlock/ }))
    await waitFor(() => expect(trigger('dave')).toBeNull())
    expect(trigger('bob')).toBeNull()                                       // ikinci işlem birincinin göstergesini silmedi
    await act(async () => { finishUnlock({ success: true }) })
    await waitFor(() => expect(trigger('dave')).not.toBeNull())
    expect(trigger('bob')).toBeNull()                                       // dave bitti; bob hâlâ sürüyor
    await act(async () => { finishTour({ success: true }) })
    await waitFor(() => expect(trigger('bob')).not.toBeNull())
  })
})
