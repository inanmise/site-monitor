// Giriş sonrası eksikler penceresi (2026-11-15, kullanıcı isteği) — SAF model: profil eksikleri (oturumdaki kullanıcı
// kaydından) + kişinin KENDİ takımlarının veri kalitesi bulguları (`/api/data-quality/teams/{id}`) dört kategoride; ne
// zaman kendiliğinden gösterileceği (1 gün sonra hatırlat / yeni eksik çıkana dek gösterme). React yok, ağ yok.

/** Kategoriler (sıra = ekrandaki sıra). */
export const GAP_CATEGORIES = Object.freeze(['profile', 'team', 'monitors', 'inventory'])
/** Profil eksik kodları. */
export const PROFILE_GAPS = Object.freeze(['PROFILE_NO_EMAIL', 'PROFILE_NO_PHONE'])
/** En çok sorgulanan takım (kendi takımları; fazlası notla söylenir). */
export const MAX_TEAMS = 10
/** Kural başına gösterilen en çok kalem (kalanı "veri kalitesi sayfasında"). */
export const MAX_ITEMS_PER_GAP = 5
/** "Daha sonra hatırlat" süresi. */
export const SNOOZE_MS = 24 * 60 * 60 * 1000
/** Saklanan "gösterme" özetinin en çok uzunluğu (kullanıcı tercih belgesine sığsın). */
export const MAX_DISMISSED = 500
/** Takım veri kalitesi isteğinin süre sınırı (ms) — istek asla süresiz beklemez. */
export const TEAM_FETCH_TIMEOUT_MS = 20_000
/** Kendiliğinden gösterme öncesi bekleme (sayfa otursun, başka pencere açılmışsa sorulmaz). */
export const AUTO_PROMPT_DELAY_MS = 1500
/** Kullanıcı tercihindeki (yerel ayna) anahtar — UserPreferencesService.LOCAL_KEYS ile aynı. */
export const GAPS_STATE_KEY = 'sm.gaps.state'

const SCOPE_CATEGORY = Object.freeze({ TEAM: 'team', MONITOR: 'monitors', INVENTORY: 'inventory' })
const SEVERITIES = ['HIGH', 'MEDIUM', 'LOW']
const SEVERITY_RANK = Object.freeze({ HIGH: 0, MEDIUM: 1, LOW: 2 })

const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)
const num = (v) => (v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))

/** Basit teslim edilebilir e-posta denetimi (sunucunun ayrıntılı kuralı değil — boş / bozuk adresi yakalar). */
function deliverable(email) {
  if (typeof email !== 'string') return false
  const e = email.trim()
  const at = e.indexOf('@')
  return at > 0 && at === e.lastIndexOf('@') && e.indexOf('.', at) > at + 1 && !/\s/.test(e)
}

/**
 * Profil eksikleri: e-posta yok / bozuk (bildirim gelmez), telefon yok (nöbet arama listesi, kodla giriş). Alan yanıtta
 * HİÇ yoksa (eski sunucu, giriş yanıtı) bilinmiyor sayılır — eksik denmez.
 */
export function profileGaps(profile) {
  if (!isObj(profile)) return []
  const out = []
  const has = (k) => Object.prototype.hasOwnProperty.call(profile, k)
  if (has('email') && !deliverable(profile.email)) out.push({ key: 'PROFILE_NO_EMAIL', category: 'profile', code: 'PROFILE_NO_EMAIL', severity: 'HIGH' })
  if (has('phone') && (typeof profile.phone !== 'string' || !profile.phone.trim())) {
    out.push({ key: 'PROFILE_NO_PHONE', category: 'profile', code: 'PROFILE_NO_PHONE', severity: 'MEDIUM' })
  }
  return out
}

/** Kişinin kendi takımları: üyesi olduğu + yönettiği (paylaşım / 7/24 genişlemesi YOK), tekil, en çok MAX_TEAMS. */
export function ownTeamIds(myTeamIds, manageTeamIds) {
  const seen = new Set()
  const out = []
  for (const list of [myTeamIds, manageTeamIds]) {
    if (!Array.isArray(list)) continue
    for (const raw of list) {
      const id = num(raw)
      if (id === null || id <= 0 || seen.has(id)) continue
      seen.add(id)
      out.push(id)
    }
  }
  return { ids: out.slice(0, MAX_TEAMS), more: Math.max(0, out.length - MAX_TEAMS) }
}

/** Bir kalemin kalıcı anahtarı (kural + tür + kimlik); kimliksiz kalem adıyla. */
function itemKey(code, it) {
  const id = it?.id ?? it?.name ?? ''
  return `${code}|${it?.kind ?? ''}|${it?.type ?? ''}|${id}`
}

/**
 * Takım veri kalitesi yanıtından eksikler: kusurlu (failing > 0) her kural bir eksik — kategori kuralın kapsamından,
 * kalemler en çok MAX_ITEMS_PER_GAP (`more` = gösterilmeyen). Bilinmeyen kapsam atlanır.
 */
export function teamGaps(teamId, data) {
  const team = isObj(data?.team) ? data.team : {}
  const teamName = typeof team.name === 'string' ? team.name : (typeof team.team_name === 'string' ? team.team_name : null)
  const rules = Array.isArray(data?.rules) ? data.rules : []
  const out = []
  for (const r of rules) {
    if (!isObj(r) || typeof r.code !== 'string') continue
    const category = SCOPE_CATEGORY[r.scope]
    const failing = num(r.failing) ?? 0
    if (!category || failing <= 0) continue
    const items = (Array.isArray(r.items) ? r.items : []).filter(isObj)
    const keys = items.length ? items.map((it) => itemKey(r.code, it)) : [`${r.code}|team|${teamId}`]
    out.push({
      key: `${teamId}:${r.code}`, category, code: r.code, severity: SEVERITIES.includes(r.severity) ? r.severity : 'LOW',
      teamId, teamName, failing, items: items.slice(0, MAX_ITEMS_PER_GAP),
      more: Math.max(0, failing - Math.min(items.length, MAX_ITEMS_PER_GAP)), itemKeys: keys,
    })
  }
  return out
}

/**
 * Tüm eksikler: `{ byCategory: {profile, team, monitors, inventory}, total, keys, failedTeams }`. Kategoride sıra: önem,
 * sonra takım adı, sonra kural. `keys` = kendiliğinden gösterme kararı için kalem anahtarları (profil kodu dahil).
 * Okunamayan takım `failedTeams`'e girer (bilinmeyen ≠ eksik yok) ve anahtarlara katılmaz.
 */
export function collectGaps({ profile, profileFailed = false, teams = [] }) {
  const byCategory = Object.fromEntries(GAP_CATEGORIES.map((c) => [c, []]))
  const keys = []
  const failedTeams = []
  for (const g of profileGaps(profile)) {
    byCategory.profile.push(g)
    keys.push(g.key)
  }
  for (const t of teams) {
    if (!t || t.id == null) continue
    if (t.failed) { failedTeams.push({ id: t.id, name: t.name ?? null }); continue }
    for (const g of teamGaps(t.id, t.data)) {
      byCategory[g.category].push(g)
      keys.push(...g.itemKeys)
    }
  }
  const coll = typeof Intl !== 'undefined' ? new Intl.Collator('tr', { sensitivity: 'base', numeric: true }) : null
  for (const c of GAP_CATEGORIES) {
    byCategory[c].sort((a, b) => (SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity])
      || (coll ? coll.compare(a.teamName || '', b.teamName || '') : 0) || String(a.code).localeCompare(String(b.code)))
  }
  const total = GAP_CATEGORIES.reduce((n, c) => n + byCategory[c].length, 0)
  return { byCategory, total, keys, failedTeams, profileFailed: profileFailed === true }
}

/** FNV-1a 32 bit (onaltılık) — saklanan anahtar özetleri kısa olsun, ham kimlik / ad tercih belgesine yazılmasın. */
export function hashKey(s) {
  let h = 0x811c9dc5
  const str = String(s)
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i)
    h = Math.imul(h, 0x01000193) >>> 0
  }
  return h.toString(16).padStart(8, '0')
}

/** Saklanan durum (bozuksa boş): `{ v: 1, snoozeUntil: ms|null, dismissed: [hex] }`. */
export function readState(raw) {
  let o = raw
  if (typeof raw === 'string') {
    try { o = JSON.parse(raw) } catch { o = null }
  }
  if (!isObj(o)) return { v: 1, snoozeUntil: null, dismissed: [] }
  const snooze = num(o.snoozeUntil)
  const dismissed = (Array.isArray(o.dismissed) ? o.dismissed : []).filter((x) => typeof x === 'string' && /^[0-9a-f]{8}$/.test(x))
    .slice(0, MAX_DISMISSED)
  return { v: 1, snoozeUntil: snooze !== null && snooze > 0 ? snooze : null, dismissed }
}

/**
 * Kendiliğinden gösterilsin mi: eksik varsa, ertelenmediyse ve "gösterme" dendiğinden beri YENİ bir eksik çıktıysa.
 * Eksik yoksa ya da bilinmiyorsa (hiç anahtar) gösterilmez.
 */
export function shouldPrompt(state, keys, now) {
  const s = readState(state)
  if (!Array.isArray(keys) || keys.length === 0) return false
  if (s.snoozeUntil !== null && s.snoozeUntil > now) return false
  if (s.dismissed.length === 0) return true
  const seen = new Set(s.dismissed)
  return keys.some((k) => !seen.has(hashKey(k)))
}

/** "Daha sonra hatırlat": 1 gün sonra (gösterme listesi korunur). */
export function snoozeState(state, now) {
  const s = readState(state)
  return { ...s, snoozeUntil: now + SNOOZE_MS }
}

/** "Bir daha gösterme": şu anki eksikler hatırlanır; yeni bir eksik çıkınca yine gösterilir. */
export function dismissState(state, keys) {
  const s = readState(state)
  const hashes = []
  const seen = new Set()
  for (const k of Array.isArray(keys) ? keys : []) {
    const h = hashKey(k)
    if (seen.has(h)) continue
    seen.add(h)
    hashes.push(h)
    if (hashes.length >= MAX_DISMISSED) break
  }
  return { ...s, snoozeUntil: null, dismissed: hashes }
}
