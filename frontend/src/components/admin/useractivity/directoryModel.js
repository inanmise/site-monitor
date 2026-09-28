import { toCsv } from '../../../utils/csvExport.js'
import { idleBand } from './uactModel.js'

/**
 * Kullanıcı Dizini saf modeli (2026-09-28 yeniden tasarım) — React'siz, test edilebilir.
 *
 * Veri: SystemHealth payload'ındaki `login_status` (TÜM kullanıcılar) + `active_users` (oturumu açık olanlar); ek istek
 * yok. Tel biçimi snake_case, burada dönüştürülmez. Süzgeç modeli faset tabanlıdır (shadcn "data table faceted filter"):
 * her faset bir DİZİ; faset içinde VEYA, fasetler arasında VE. Eski tekil dize biçimi (`tour: 'completed'`) de kabul
 * edilir (panel kartları dizini `{ tour: 'completed' }` / `{ view: 'all' }` ile açıyor).
 */
export const TOUR_STATES = ['completed', 'dismissed', 'snoozed', 'started', 'none']
export const ACCOUNT_STATES = ['active', 'inactive', 'locked']
/** Son giriş kovaları: <24 sa · 1–7 gün · 7–30 gün · 30+ gün · hiç (çevrimiçi olmak kovayı DEĞİŞTİRMEZ). */
export const LOGIN_BUCKETS = ['today', 'week', 'month', 'dormant', 'never']
export const PROVIDERS = ['LDAP', 'LOCAL']
/** Faset anahtarları — çiplerin ve telefon süzgeç panelinin sırası. */
export const FACETS = ['account', 'role', 'team', 'provider', 'tour', 'login']

export const EMPTY_FILTERS = Object.freeze({ q: '', view: 'all', account: [], role: [], team: [], provider: [], tour: [], login: [] })

/** Sıralama: `presence` = çevrimiçi önce (boşta süresine göre), sonra son giriş yeniden eskiye (yön yok sayılır). */
export const SORT_COLS = ['presence', 'name', 'last_seen', 'last_login', 'created']
export const DEFAULT_SORT = Object.freeze({ col: 'presence', dir: 'desc' })
/** Kart görünümündeki sıralama seçicisinin seçenekleri (`kolon:yön`) — i18n `udir.sortOpt.<değer>`. */
export const SORT_OPTIONS = ['presence', 'name:asc', 'name:desc', 'last_seen:desc', 'last_seen:asc', 'last_login:desc', 'last_login:asc', 'created:desc', 'created:asc']

const DAY_MS = 86_400_000

/** Sunucu damgası (Z'siz UTC) → ms; boş/bozuk → 0. */
export function ts(iso) {
  if (!iso) return 0
  const s = String(iso)
  const t = Date.parse(s.endsWith('Z') ? s : s + 'Z')
  return Number.isNaN(t) ? 0 : t
}
export const userKey = (r) => String(r?.username || '').toLowerCase()
const list = (v) => (Array.isArray(v) ? v : (v != null && v !== '' ? [v] : [])).map(String)

/**
 * Arama katlaması: büyük/küçük harf + Türkçe aksan duyarsız ("gul" → "Gül", "isik" → "Işık", "ian" → "Ian").
 * `tr` yerel küçültmesi I→ı yapar; ı → i eşlemesi İngilizce adların da eşleşmesini sağlar.
 */
export function fold(s) {
  return String(s ?? '').toLocaleLowerCase('tr').normalize('NFD').replace(/\p{M}/gu, '').replace(/ı/g, 'i')
}

/** Görünen ad: sondaki parantezli ek ("(… Bölümü)") departmandır → ayrı döner; ek yoksa `department`. */
export function splitDisplayName(displayName, department) {
  const raw = displayName ? String(displayName) : ''
  const m = raw.match(/\s*\(([^)]*)\)\s*$/)
  return { name: m ? raw.slice(0, m.index).trim() : raw.trim(), dept: m ? m[1].trim() : (department || null) }
}
export const nameOf = (r) => splitDisplayName(r?.display_name, r?.department).name || r?.username || ''

/**
 * Avatar fotoğraf adresi — sunucu `has_photo: false` diyorsa istek YOK (fotoğrafsız her kişi için boşuna 204 isteği
 * atılıyordu; TeamMemberCards.photoIdOf ile aynı kural). Bayrak hiç yoksa (eski sunucu / dizinde olmayan oturum) istenir.
 */
export const avatarSrc = (r) => (r?.user_id != null && r?.has_photo !== false ? `/api/users/${r.user_id}/photo` : null)

/**
 * login_status ⊕ active_users → dizin satırları; çevrimiçi (boşta süresi kısa) önce, sonra son giriş yeniye göre.
 * `conn_masked`: çevrimiçi satırda bağlantı alanları (ip/konum/tarayıcı) sunucuda DÜŞÜRÜLMÜŞSE true — denetleyici bunları
 * global admin / AUDIT dışındakilere göndermez (anahtar hiç yoktur; `null` değil). Ayrıntı panelinde "gizli" notu için.
 */
export function mergeDirectory(loginStatus = [], activeUsers = []) {
  const act = new Map((activeUsers || []).map((u) => [userKey(u), u]))
  const seen = new Set()
  const rows = []
  const live = (a) => ({
    online: true, idle_sec: a.idle_sec, expires_in_sec: a.expires_in_sec, login_at: a.login_at, duration_min: a.duration_min,
    ip: a.ip, city: a.city, country: a.country, org: a.org, last_tab: a.last_tab, last_tab_at: a.last_tab_at,
    user_agent: a.user_agent, conn_masked: !('ip' in a) && !('user_agent' in a),
  })
  for (const r of loginStatus || []) {
    const key = userKey(r); seen.add(key)
    const a = act.get(key)
    rows.push(a ? { ...r, ...live(a), last_seen: a.last_seen || r.last_seen_at } : { ...r, online: false, last_seen: r.last_seen_at })
  }
  // Savunma: dizinde olmayan oturum (login_status'tan önce oluşmuş kayıt) yine listelenir.
  for (const [key, a] of act) if (!seen.has(key)) rows.push({ ...a, ...live(a), last_seen: a.last_seen, tour_status: a.tour_status || 'none' })
  return rows.sort(presenceCmp)
}

function presenceCmp(x, y) {
  if (x.online !== y.online) return x.online ? -1 : 1
  if (x.online) return (x.idle_sec ?? 1e9) - (y.idle_sec ?? 1e9) || userKey(x).localeCompare(userKey(y))
  return ts(y.last_login_at) - ts(x.last_login_at) || userKey(x).localeCompare(userKey(y))
}

/** Son giriş kovası (çevrimiçi olmaktan BAĞIMSIZ — yalnız `last_login_at`). */
export function loginBucket(row, now = Date.now()) {
  const t = ts(row?.last_login_at)
  if (!t) return 'never'
  const days = (now - t) / DAY_MS
  if (days < 1) return 'today'
  if (days < 7) return 'week'
  if (days < 30) return 'month'
  return 'dormant'
}

/** Hesap durumu eşleşmesi: pasif = `active:false`; kilitli = `permanent_lock`; etkin = ikisi de değil. */
export function accountMatches(row, state) {
  if (state === 'inactive') return row.active === false
  if (state === 'locked') return !!row.permanent_lock
  if (state === 'active') return row.active !== false && !row.permanent_lock
  return false
}
export const providerOf = (row) => (row?.auth_source === 'LDAP' ? 'LDAP' : 'LOCAL')
export const teamIdsOf = (row) => {
  const ids = new Set((row?.team_ids || []).map(String))
  if (row?.team_id != null) ids.add(String(row.team_id))
  return ids
}

export function directoryMatches(r, f, now = Date.now()) {
  if (f.view === 'online' && !r.online) return false
  if (f.view === 'offline' && r.online) return false
  const acc = list(f.account); if (acc.length && !acc.some((s) => accountMatches(r, s))) return false
  const roles = list(f.role); if (roles.length && !roles.includes(String(r.system_role ?? ''))) return false
  const teams = list(f.team)
  if (teams.length) { const mine = teamIdsOf(r); if (!teams.some((id) => mine.has(id))) return false }
  const prov = list(f.provider); if (prov.length && !prov.includes(providerOf(r))) return false
  const tours = list(f.tour); if (tours.length && !tours.includes(r.tour_status || 'none')) return false
  const logins = list(f.login); if (logins.length && !logins.includes(loginBucket(r, now))) return false
  const q = fold(String(f.q || '').trim())
  if (q && ![r.username, r.display_name, r.email, r.employee_id, r.team_name, r.title, r.department]
    .some((v) => v != null && fold(v).includes(q))) return false
  return true
}

/** Panelin açılış ön ayarı (`{ view: 'all' }`, `{ tour: 'completed' }` …) → tam süzgeç durumu. */
export function initialFilters(initial = {}) {
  const i = initial || {}
  return {
    ...EMPTY_FILTERS,
    q: typeof i.q === 'string' ? i.q : '',
    view: i.view === 'online' || i.view === 'offline' ? i.view : 'all',
    account: list(i.account), role: list(i.role), team: list(i.team), provider: list(i.provider), tour: list(i.tour), login: list(i.login),
  }
}
/** Faset + görünüm süzgeç sayısı (arama hariç — telefonda arama kutusu zaten görünür). */
export function facetCount(f) {
  return FACETS.reduce((n, k) => n + list(f[k]).length, 0) + (f.view && f.view !== 'all' ? 1 : 0)
}
export function hasAnyFilter(f) { return facetCount(f) > 0 || !!String(f.q || '').trim() }
/** "Turu tamamlayan" ön ayarı hâlâ aynen duruyor mu (başlık kartın adını taşır)? */
export function isTourPreset(f) {
  return f.view === 'all' && list(f.tour).length === 1 && list(f.tour)[0] === 'completed' && !String(f.q || '').trim()
    && FACETS.every((k) => k === 'tour' || list(f[k]).length === 0)
}
/** Faset değerini ekler/çıkarır (dizi kopyası). */
export function toggleValue(arr, v) {
  const cur = list(arr); const s = String(v)
  return cur.includes(s) ? cur.filter((x) => x !== s) : [...cur, s]
}
/** Faset seçimi tam olarak bu küme mi (sıra önemsiz)? Özet kutucuğu `aria-pressed`i. */
export function sameSet(arr, values) {
  const a = list(arr); const b = list(values)
  return a.length === b.length && b.every((v) => a.includes(v))
}

/** Sıralama; boş damgalar yönden bağımsız EN SONDA. Eşitlikte kullanıcı adı. */
export function sortDirectory(rows, sort = DEFAULT_SORT) {
  const { col = 'presence', dir = 'desc' } = sort || {}
  const arr = [...(rows || [])]
  if (col === 'presence' || !SORT_COLS.includes(col)) return arr.sort(presenceCmp)
  const sign = dir === 'asc' ? 1 : -1
  if (col === 'name') {
    return arr.sort((a, b) => sign * nameOf(a).localeCompare(nameOf(b), 'tr', { sensitivity: 'base' }) || userKey(a).localeCompare(userKey(b)))
  }
  const val = { last_seen: (r) => ts(r.last_seen), last_login: (r) => ts(r.last_login_at), created: (r) => ts(r.created_at) }[col]
  return arr.sort((a, b) => {
    const x = val(a), y = val(b)
    if (!x !== !y) return x ? -1 : 1
    return sign * (x - y) || userKey(a).localeCompare(userKey(b))
  })
}
export function sortFromOption(opt) {
  const [col, dir] = String(opt || 'presence').split(':')
  return SORT_COLS.includes(col) ? { col, dir: dir === 'asc' ? 'asc' : 'desc' } : { ...DEFAULT_SORT }
}
export function sortToOption(sort) {
  return !sort || sort.col === 'presence' ? 'presence' : `${sort.col}:${sort.dir === 'asc' ? 'asc' : 'desc'}`
}
/** Başlık tıklaması: aynı kolonda yön değişir; yeni kolon adda A→Z, zamanlarda en yeni önce başlar. */
export function nextSort(sort, col) {
  if (sort?.col === col) return { col, dir: sort.dir === 'asc' ? 'desc' : 'asc' }
  return { col, dir: col === 'name' ? 'asc' : 'desc' }
}

/** Özet kutucukları — bütün dizin üzerinden (süzgeçten bağımsız). */
export function directoryStats(rows, now = Date.now()) {
  const s = { total: 0, online: 0, live: 0, idle: 0, recent: 0, today: 0, inactive: 0, locked: 0, restricted: 0, ldap: 0, local: 0, tourCompleted: 0, tourDismissed: 0, never: 0, teams: 0 }
  const teams = new Set()
  for (const r of rows || []) {
    s.total++
    if (r.online) { s.online++; if (idleBand(r.idle_sec) === 'live') s.live++; else s.idle++ }
    const b = loginBucket(r, now)
    if (b === 'today' || b === 'week') s.recent++
    if (b === 'today') s.today++
    if (b === 'never') s.never++
    if (r.active === false) s.inactive++
    if (r.permanent_lock) s.locked++
    if (r.active === false || r.permanent_lock) s.restricted++
    if (providerOf(r) === 'LDAP') s.ldap++; else s.local++
    if (r.tour_status === 'completed') s.tourCompleted++
    if (r.tour_status === 'dismissed') s.tourDismissed++
    for (const id of teamIdsOf(r)) teams.add(id)
  }
  s.teams = teams.size
  return s
}

/** Faset seçenekleri + o anki sayıları (tüm dizin üzerinden). `labels` çağırandan (i18n). */
export function facetOptions(rows, now = Date.now()) {
  const count = (fn) => { const m = new Map(); for (const r of rows || []) for (const k of fn(r)) m.set(k, (m.get(k) || 0) + 1); return m }
  const roles = count((r) => (r.system_role ? [String(r.system_role)] : []))
  const teamNames = new Map()
  for (const r of rows || []) if (r.team_id != null && r.team_name) teamNames.set(String(r.team_id), r.team_name)
  const teams = count((r) => [...teamIdsOf(r)])
  const acc = count((r) => ACCOUNT_STATES.filter((s) => accountMatches(r, s)))
  const prov = count((r) => [providerOf(r)])
  const tour = count((r) => [r.tour_status || 'none'])
  const login = count((r) => [loginBucket(r, now)])
  return {
    account: ACCOUNT_STATES.map((v) => ({ value: v, count: acc.get(v) || 0 })),
    role: [...roles.keys()].sort().map((v) => ({ value: v, count: roles.get(v) })),
    // Yalnız adı bilinen takımlar (ek takım kimliği tek başına ad taşımıyor — rozet onu dizinden çözer).
    team: [...teamNames.entries()].sort((a, b) => a[1].localeCompare(b[1], 'tr')).map(([v, name]) => ({ value: v, name, count: teams.get(v) || 0 })),
    provider: PROVIDERS.map((v) => ({ value: v, count: prov.get(v) || 0 })),
    tour: TOUR_STATES.map((v) => ({ value: v, count: tour.get(v) || 0 })),
    login: LOGIN_BUCKETS.map((v) => ({ value: v, count: login.get(v) || 0 })),
  }
}

/**
 * Satır eylemlerinin görünürlüğü — TEK kaynak (işlem menüsü + ayrıntı paneli). Yetki kuralları 2026-09-20 dizininden
 * AYNEN: `isAdmin` = sistem rolü ADMIN (global admin VE kapsamlı müdür; sunucu yine kendi kapısını uygular), kilit açma
 * YALNIZ global admin; kişi kendi oturumunu sonlandıramaz.
 */
export function rowActions(r, { isAdmin = false, globalAdmin = false, username = '' } = {}) {
  const self = !!username && userKey(r) === String(username).toLowerCase()
  return {
    self,
    openAdmin: !!isAdmin,
    mail: !!r.email,
    terminate: !!isAdmin && !!r.online && !self,
    tourReset: !!isAdmin && r.user_id != null && (r.tour_status || 'none') !== 'none',
    unlock: !!globalAdmin && !!r.permanent_lock && r.user_id != null,
  }
}

/** Oturum zaman aşımı doluluğu (boşta / (boşta + kalan)) — yüzde; bilinmiyorsa null. */
export function timeoutUsedPct(r) {
  const idle = Number(r?.idle_sec), left = Number(r?.expires_in_sec)
  if (!(idle >= 0) || !(left >= 0) || r?.expires_in_sec == null || idle + left <= 0) return null
  return Math.round((idle / (idle + left)) * 100)
}

/**
 * User-Agent → okunur özet: tarayıcı + ana sürüm, işletim sistemi, cihaz sınıfı. Tanınmazsa ilk belirteç.
 * Sıra önemli: Edge/Opera UA'ları "Chrome/" da taşır; Chrome da "Safari/" taşır.
 */
export function parseUserAgent(ua) {
  if (!ua) return null
  const s = String(ua)
  const pick = (re) => { const m = s.match(re); return m ? m[1] : null }
  let browser = null, version = null
  if (/curl\//i.test(s)) { browser = 'curl'; version = pick(/curl\/(\d+(?:\.\d+)?)/i) }
  else if (/Edg(?:e|A|iOS)?\//.test(s)) { browser = 'Edge'; version = pick(/Edg(?:e|A|iOS)?\/(\d+)/) }
  else if (/OPR\/|Opera/.test(s)) { browser = 'Opera'; version = pick(/(?:OPR|Version)\/(\d+)/) }
  else if (/Firefox\/|FxiOS\//.test(s)) { browser = 'Firefox'; version = pick(/(?:Firefox|FxiOS)\/(\d+)/) }
  else if (/Chrome\/|CriOS\//.test(s)) { browser = 'Chrome'; version = pick(/(?:Chrome|CriOS)\/(\d+)/) }
  else if (/Safari\//.test(s)) { browser = 'Safari'; version = pick(/Version\/(\d+(?:\.\d+)?)/) }
  else browser = s.split(/[\s/]/)[0] || null
  let os = null
  if (/Windows NT 10/.test(s)) os = 'Windows 10/11'
  else if (/Windows NT/.test(s)) os = 'Windows'
  else if (/iPad/.test(s)) os = 'iPadOS'
  else if (/iPhone|iPod/.test(s)) { const v = pick(/OS (\d+)_/); os = v ? `iOS ${v}` : 'iOS' }
  else if (/Android/.test(s)) { const v = pick(/Android (\d+(?:\.\d+)?)/); os = v ? `Android ${v}` : 'Android' }
  else if (/CrOS/.test(s)) os = 'ChromeOS'
  else if (/Mac OS X|Macintosh/.test(s)) os = 'macOS'
  else if (/Linux/.test(s)) os = 'Linux'
  let device = 'desktop'
  if (/curl\/|python|Go-http|okhttp|Java\/|bot|spider/i.test(s)) device = 'bot'
  else if (/iPad|Tablet/.test(s) || (/Android/.test(s) && !/Mobile/.test(s))) device = 'tablet'
  else if (/Mobi|iPhone|iPod/.test(s)) device = 'mobile'
  return { browser, version, os, device }
}

/** CSV: görünen (süzgeçlenmiş + sıralanmış) satırlar; kolonlar 2026-09-20 dizininden AYNEN. */
export function directoryCsv(rows, t) {
  const head = [t('uact.colUser'), t('uact.detailDisplayName'), t('uact.detailEmail'), t('uact.colRole'), t('uact.detailOrgRole'), t('uact.colTeam'), t('uact.colAuthSource'), t('uact.dirOnline'), t('uact.detailLastSeen'), t('uact.colLastLogin'), t('uact.colCreated'), t('uact.colTour'), t('uact.colAccount')]
  return toCsv(head, (rows || []).map((r) => [r.username, r.display_name, r.email, r.system_role, r.org_role, r.team_name, r.auth_source, r.online ? 1 : 0, r.last_seen, r.last_login_at, r.created_at, r.tour_status, r.active === false ? 'inactive' : r.permanent_lock ? 'locked' : 'active']))
}
