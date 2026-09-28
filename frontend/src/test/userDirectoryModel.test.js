import { describe, it, expect } from 'vitest'
import {
  mergeDirectory, directoryMatches, directoryCsv, sortDirectory, directoryStats, facetOptions, rowActions, parseUserAgent,
  loginBucket, fold, initialFilters, isTourPreset, facetCount, hasAnyFilter, toggleValue, sameSet, nextSort, sortFromOption,
  sortToOption, timeoutUsedPct, splitDisplayName, EMPTY_FILTERS, avatarSrc,
} from '../components/admin/useractivity/directoryModel.js'

describe('avatarSrc — has_photo bayrağı (2026-09-28)', () => {
  it('has_photo=false → istek YOK; true ya da bayrak yok (eski sunucu) → /photo; user_id yoksa null', () => {
    expect(avatarSrc({ user_id: 7, has_photo: false })).toBeNull()
    expect(avatarSrc({ user_id: 7, has_photo: true })).toBe('/api/users/7/photo')
    expect(avatarSrc({ user_id: 7 })).toBe('/api/users/7/photo')
    expect(avatarSrc({ username: 'u', has_photo: true })).toBeNull()
    expect(avatarSrc(null)).toBeNull()
  })
})

/**
 * Kullanıcı Dizini saf modeli (2026-09-28 yeniden tasarım). Zaman: bütün damgalar `now`'dan TÜRETİLİR (kayan pencere —
 * 24 sa / 7 gün / 30 gün kovaları — sabit tarihe karşı ölçülmez; bkz. test-fixed-date-time-bomb). Kova sınırlarından
 * uzak mesafeler seçildi (2 sa, 3 gün, 12 gün, 45 gün).
 */
const NOW = Date.now()
const ago = (sec) => new Date(NOW - sec * 1000).toISOString().slice(0, 19)
const DAY = 86_400

const ACTIVE = [
  { username: 'bob', user_id: 2, display_name: 'Bob', idle_sec: 2400, expires_in_sec: 1200, login_at: ago(7200), duration_min: 120, ip: '10.0.0.2', city: 'Ankara', country: 'TR', org: 'Örnek ISS', user_agent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36', last_seen: ago(2400) },
  { username: 'Admin', user_id: 1, display_name: 'Yonetici', idle_sec: 20, expires_in_sec: 3580, login_at: ago(3600), duration_min: 60, last_seen: ago(20) },   // bağlantı alanları sunucuda düşürülmüş (maske)
]
const STATUS = [
  { username: 'carol', user_id: 3, display_name: 'Carol Ornek (Teknoloji Bolumu)', email: 'carol@example.com', system_role: 'USER', org_role: 'TECH', team_id: 5, team_name: 'Takım A', team_ids: [5, 9], auth_source: 'LDAP', active: true, created_at: ago(200 * DAY), last_seen_at: ago(45 * DAY), last_login_at: ago(45 * DAY), tour_status: 'completed', employee_id: 'E-3' },
  { username: 'admin', user_id: 1, display_name: 'Yonetici', email: 'admin@example.com', system_role: 'ADMIN', team_id: 5, team_name: 'Takım A', auth_source: 'LOCAL', active: true, created_at: ago(300 * DAY), last_login_at: ago(3600), tour_status: 'dismissed' },
  { username: 'bob', user_id: 2, display_name: 'Bob', email: 'bob@example.com', system_role: 'USER', team_id: 9, team_name: 'Takım B', auth_source: 'LDAP', active: true, created_at: ago(100 * DAY), last_login_at: ago(7200), tour_status: 'completed', failed_since_login: 2 },
  { username: 'dave', user_id: 4, display_name: 'Dave', email: null, system_role: 'USER', team_id: 9, team_name: 'Takım B', auth_source: 'LOCAL', active: false, permanent_lock: true, created_at: ago(20 * DAY), last_login_at: null, tour_status: 'none' },
  { username: 'erin', user_id: 5, display_name: 'Gül Işık', email: 'erin@example.com', system_role: 'AUDIT', team_id: 5, team_name: 'Takım A', auth_source: 'LDAP', active: true, permanent_lock: true, created_at: ago(50 * DAY), last_login_at: ago(3 * DAY), last_seen_at: ago(3 * DAY), tour_status: 'started' },
  { username: 'fred', user_id: 6, display_name: 'Fred', email: 'fred@example.com', system_role: 'USER', team_id: 9, team_name: 'Takım B', auth_source: 'LOCAL', active: true, created_at: ago(40 * DAY), last_login_at: ago(12 * DAY), last_seen_at: ago(12 * DAY), tour_status: 'snoozed' },
]
const rows = () => mergeDirectory(STATUS, ACTIVE)
const names = (list) => list.map((r) => r.username)
const match = (f) => names(rows().filter((r) => directoryMatches(r, { ...EMPTY_FILTERS, ...f }, NOW)))

describe('mergeDirectory', () => {
  it('çevrimiçi başta (boşta süresine göre), sonra son giriş yeniden eskiye, hiç girmeyen sonda; oturum alanları birleşir', () => {
    const r = rows()
    expect(names(r)).toEqual(['admin', 'bob', 'erin', 'fred', 'carol', 'dave'])
    expect(r[1]).toMatchObject({ online: true, idle_sec: 2400, ip: '10.0.0.2', org: 'Örnek ISS', email: 'bob@example.com', conn_masked: false })
    expect(r[4]).toMatchObject({ online: false, last_seen: STATUS[0].last_seen_at })
  })
  it('bağlantı alanları payload\'da HİÇ yoksa (kimlik maskesi) conn_masked; null değer maske değildir', () => {
    const r = rows()
    expect(r[0].conn_masked).toBe(true)
    const withNull = mergeDirectory(STATUS, [{ username: 'bob', idle_sec: 1, ip: null, user_agent: null }])
    expect(withNull.find((x) => x.username === 'bob').conn_masked).toBe(false)
  })
  it('dizinde olmayan oturum da listelenir (savunma)', () => {
    const r = mergeDirectory([], [{ username: 'ghost', idle_sec: 5 }])
    expect(r).toHaveLength(1); expect(r[0]).toMatchObject({ username: 'ghost', online: true, tour_status: 'none' })
  })
})

describe('directoryMatches — fasetler', () => {
  it('görünüm: çevrimiçi / çevrimdışı', () => {
    expect(match({ view: 'online' })).toEqual(['admin', 'bob'])
    expect(match({ view: 'offline' })).toEqual(['erin', 'fred', 'carol', 'dave'])
  })
  it('faset İÇİNDE veya, fasetler ARASINDA ve', () => {
    expect(match({ role: ['ADMIN', 'AUDIT'] })).toEqual(['admin', 'erin'])
    expect(match({ role: ['USER'], provider: ['LDAP'] })).toEqual(['bob', 'carol'])
  })
  it('takım: ana takım ya da ek takım (team_ids)', () => {
    expect(match({ team: ['9'] })).toEqual(['bob', 'fred', 'carol', 'dave'])
  })
  it('hesap: etkin = ne pasif ne kilitli; kilitli ve pasif ayrı ayrı', () => {
    expect(match({ account: ['active'] })).toEqual(['admin', 'bob', 'fred', 'carol'])
    expect(match({ account: ['locked'] })).toEqual(['erin', 'dave'])
    expect(match({ account: ['inactive'] })).toEqual(['dave'])
  })
  it('son giriş kovası now\'dan (çevrimiçi olmak kovayı değiştirmez)', () => {
    expect(match({ login: ['today'] })).toEqual(['admin', 'bob'])
    expect(match({ login: ['week'] })).toEqual(['erin'])
    expect(match({ login: ['month'] })).toEqual(['fred'])
    expect(match({ login: ['dormant'] })).toEqual(['carol'])
    expect(match({ login: ['never'] })).toEqual(['dave'])
    expect(loginBucket({ last_login_at: ago(DAY / 2) }, NOW)).toBe('today')
  })
  it('tur ve eski tekil dize biçimi (panel kartı { tour: "completed" })', () => {
    expect(match({ tour: 'completed' })).toEqual(['bob', 'carol'])
    expect(match({ tour: ['started', 'snoozed'] })).toEqual(['erin', 'fred'])
  })
  it('arama: büyük/küçük harf ve Türkçe aksan duyarsız; e-posta, sicil, takım adı', () => {
    expect(match({ q: 'CAROL@' })).toEqual(['carol'])
    expect(match({ q: 'e-3' })).toEqual(['carol'])
    expect(match({ q: 'gul isik' })).toEqual(['erin'])
    expect(match({ q: 'takim b' })).toEqual(['bob', 'fred', 'dave'])
    expect(fold('İSTANBUL Işık')).toBe('istanbul isik')
  })
})

describe('sortDirectory', () => {
  it('varsayılan: çevrimiçi önce', () => {
    expect(names(sortDirectory(rows()))).toEqual(['admin', 'bob', 'erin', 'fred', 'carol', 'dave'])
  })
  it('ad A→Z / Z→A (parantezli departman eki sayılmaz)', () => {
    expect(names(sortDirectory(rows(), { col: 'name', dir: 'asc' }))).toEqual(['bob', 'carol', 'dave', 'fred', 'erin', 'admin'])
    expect(names(sortDirectory(rows(), { col: 'name', dir: 'desc' }))).toEqual(['admin', 'erin', 'fred', 'dave', 'carol', 'bob'])
  })
  it('son giriş: en yeni önce; hiç girmeyen HER İKİ yönde de en sonda', () => {
    expect(names(sortDirectory(rows(), { col: 'last_login', dir: 'desc' }))).toEqual(['admin', 'bob', 'erin', 'fred', 'carol', 'dave'])
    expect(names(sortDirectory(rows(), { col: 'last_login', dir: 'asc' }))).toEqual(['carol', 'fred', 'erin', 'bob', 'admin', 'dave'])
  })
  it('son görülme ve oluşturulma', () => {
    expect(names(sortDirectory(rows(), { col: 'last_seen', dir: 'desc' })).slice(0, 2)).toEqual(['admin', 'bob'])
    expect(names(sortDirectory(rows(), { col: 'created', dir: 'desc' }))).toEqual(['dave', 'fred', 'erin', 'bob', 'carol', 'admin'])
  })
  it('başlık tıklaması ve seçici seçeneği', () => {
    expect(nextSort({ col: 'presence', dir: 'desc' }, 'name')).toEqual({ col: 'name', dir: 'asc' })
    expect(nextSort({ col: 'name', dir: 'asc' }, 'name')).toEqual({ col: 'name', dir: 'desc' })
    expect(nextSort({ col: 'name', dir: 'asc' }, 'last_login')).toEqual({ col: 'last_login', dir: 'desc' })
    expect(sortFromOption('created:asc')).toEqual({ col: 'created', dir: 'asc' })
    expect(sortFromOption('bogus')).toEqual({ col: 'presence', dir: 'desc' })
    expect(sortToOption({ col: 'last_seen', dir: 'desc' })).toBe('last_seen:desc')
  })
})

describe('özet ve faset sayıları', () => {
  it('directoryStats', () => {
    expect(directoryStats(rows(), NOW)).toMatchObject({
      total: 6, online: 2, live: 1, idle: 1, recent: 3, today: 2, inactive: 1, locked: 2, restricted: 2,
      ldap: 3, local: 3, tourCompleted: 2, tourDismissed: 1, never: 1, teams: 2,
    })
  })
  it('facetOptions: seçenek başına sayı; takım adları', () => {
    const o = facetOptions(rows(), NOW)
    expect(o.role).toEqual([{ value: 'ADMIN', count: 1 }, { value: 'AUDIT', count: 1 }, { value: 'USER', count: 4 }])
    expect(o.team).toEqual([{ value: '5', name: 'Takım A', count: 3 }, { value: '9', name: 'Takım B', count: 4 }])
    expect(o.account).toEqual([{ value: 'active', count: 4 }, { value: 'inactive', count: 1 }, { value: 'locked', count: 2 }])
    expect(o.login.find((x) => x.value === 'never').count).toBe(1)
  })
})

describe('rowActions — yetki matrisi (2026-09-20 dizinindekiyle aynı)', () => {
  const r = rows()
  const bob = r.find((x) => x.username === 'bob'), dave = r.find((x) => x.username === 'dave'), admin = r.find((x) => x.username === 'admin')
  it('normal kullanıcı: yalnız e-posta; yönetici eylemi YOK', () => {
    expect(rowActions(bob, { isAdmin: false, globalAdmin: false, username: 'fred' }))
      .toEqual({ self: false, openAdmin: false, mail: true, terminate: false, tourReset: false, unlock: false })
  })
  it('kapsamlı müdür (ADMIN rolü, global değil): sonlandırma + tur + yönetimde aç, KİLİT AÇMA YOK', () => {
    const ctx = { isAdmin: true, globalAdmin: false, username: 'admin' }
    expect(rowActions(bob, ctx)).toMatchObject({ openAdmin: true, terminate: true, tourReset: true, unlock: false })
    expect(rowActions(dave, ctx)).toMatchObject({ unlock: false, tourReset: false, terminate: false, mail: false })
  })
  it('global admin kilit açar; kendi oturumunu sonlandıramaz (harf duyarsız)', () => {
    const ctx = { isAdmin: true, globalAdmin: true, username: 'ADMIN' }
    expect(rowActions(dave, ctx).unlock).toBe(true)
    expect(rowActions(admin, ctx)).toMatchObject({ self: true, terminate: false })
  })
})

describe('parseUserAgent', () => {
  it.each([
    ['Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 Edg/128.0.0.0', { browser: 'Edge', version: '128', os: 'Windows 10/11', device: 'desktop' }],
    ['Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36', { browser: 'Chrome', version: '127', os: 'Android 14', device: 'mobile' }],
    ['Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1', { browser: 'Safari', version: '17.5', os: 'iOS 17', device: 'mobile' }],
    ['Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1', { browser: 'Safari', os: 'iPadOS', device: 'tablet' }],
    ['Mozilla/5.0 (Macintosh; Intel Mac OS X 14.5; rv:129.0) Gecko/20100101 Firefox/129.0', { browser: 'Firefox', version: '129', os: 'macOS', device: 'desktop' }],
    ['curl/8.7.1', { browser: 'curl', device: 'bot' }],
  ])('%s', (ua, want) => { expect(parseUserAgent(ua)).toMatchObject(want) })
  it('boş → null', () => { expect(parseUserAgent(null)).toBeNull() })
})

describe('süzgeç durumu yardımcıları', () => {
  it('initialFilters: panel ön ayarı → tam durum; isTourPreset yalnız saf "turu tamamlayan"da', () => {
    const f = initialFilters({ tour: 'completed' })
    expect(f).toMatchObject({ view: 'all', tour: ['completed'], role: [], q: '' })
    expect(isTourPreset(f)).toBe(true)
    expect(isTourPreset({ ...f, role: ['USER'] })).toBe(false)
    expect(initialFilters({ view: 'bogus' }).view).toBe('all')
  })
  it('facetCount (arama hariç) / hasAnyFilter / toggleValue / sameSet', () => {
    const f = { ...EMPTY_FILTERS, view: 'online', role: ['A', 'B'], q: 'x' }
    expect(facetCount(f)).toBe(3)
    expect(hasAnyFilter({ ...EMPTY_FILTERS, q: '  ' })).toBe(false)
    expect(hasAnyFilter({ ...EMPTY_FILTERS, q: 'a' })).toBe(true)
    expect(toggleValue(['a'], 'b')).toEqual(['a', 'b'])
    expect(toggleValue(['a', 'b'], 'a')).toEqual(['b'])
    expect(sameSet(['week', 'today'], ['today', 'week'])).toBe(true)
    expect(sameSet(['today'], ['today', 'week'])).toBe(false)
  })
  it('splitDisplayName: parantezli ek departman; yoksa department', () => {
    expect(splitDisplayName('Carol Ornek (Teknoloji Bolumu)')).toEqual({ name: 'Carol Ornek', dept: 'Teknoloji Bolumu' })
    expect(splitDisplayName('Bob', 'Satış')).toEqual({ name: 'Bob', dept: 'Satış' })
  })
  it('timeoutUsedPct: boşta / (boşta + kalan)', () => {
    expect(timeoutUsedPct({ idle_sec: 2400, expires_in_sec: 1200 })).toBe(67)
    expect(timeoutUsedPct({ idle_sec: 20, expires_in_sec: null })).toBeNull()
  })
})

describe('directoryCsv', () => {
  it('başlık + satırlar; çevrimiçi 1/0, hesap durumu (kolonlar 2026-09-20 ile aynı)', () => {
    const t = (k) => k
    const lines = directoryCsv(rows(), t).split('\r\n').filter(Boolean)
    expect(lines[0]).toContain('uact.colUser,uact.detailDisplayName,uact.detailEmail')
    expect(lines[1]).toContain('admin,Yonetici,admin@example.com,ADMIN,,Takım A,LOCAL,1,')
    expect(lines[6]).toContain('dave,Dave,,USER,,Takım B,LOCAL,0,')
    expect(lines[6].endsWith(',none,inactive')).toBe(true)
    expect(lines[3].endsWith(',started,locked')).toBe(true)
  })
})
