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
/**
 * Saklanan "gösterme" kaydının en çok girdisi. Girdi EKSİK (kural) düzeyindedir, kalem düzeyinde değil: profil 2 + en çok
 * 10 takım × ~20 kural — bu sınıra ulaşılmaz; kalem düzeyi (kural başına 500 kalem) sınırı aşıp pencereyi her girişte
 * yeniden açıyordu (inceleme 2026-11-15).
 */
export const MAX_DISMISSED = 500
/** Takım veri kalitesi isteğinin süre sınırı (ms) — istek asla süresiz beklemez. */
export const TEAM_FETCH_TIMEOUT_MS = 20_000
/**
 * Bir okumanın (yanıt gövdesi dahil) üst süresi. `request()`'in zaman aşımı başlıklar gelince biter; gövde takılırsa okuma
 * bu süre dolunca "okunamadı" sayılır (süresiz bekleme yok).
 */
export const LOAD_DEADLINE_MS = 30_000
/** Kendiliğinden gösterme: okuma bundan uzun sürdüyse pencere açılmaz (kullanıcı başka işe geçmiştir; menüden açılabilir). */
export const AUTO_MAX_AGE_MS = 30_000
/** Başka bir sekme bu kadar süre içinde denetlediyse yeni sekme yeniden okumaz (her sekmede MB'lar indirilmesin). */
export const RECHECK_MS = 30 * 60 * 1000
/** "Kapat": bu oturumda (çıkışta silinir) bu kadar süre kendiliğinden açılmaz — sekmeler arası. */
export const CLOSE_QUIET_MS = 12 * 60 * 60 * 1000
/** Oturuma bağlı yerel kayıt (tercih aynasına GİRMEZ; çıkışta kişisel depolamayla silinir). */
export const GAPS_SESSION_KEY = 'sm.gaps.session'
/** Kendiliğinden gösterme öncesi bekleme (sayfa otursun, başka pencere açılmışsa sorulmaz). */
export const AUTO_PROMPT_DELAY_MS = 1500
/** Kullanıcı tercihindeki (yerel ayna) anahtar — UserPreferencesService.LOCAL_KEYS ile aynı. */
export const GAPS_STATE_KEY = 'sm.gaps.state'

const SCOPE_CATEGORY = Object.freeze({ TEAM: 'team', MONITOR: 'monitors', INVENTORY: 'inventory' })
const SEVERITIES = ['HIGH', 'MEDIUM', 'LOW']
const SEVERITY_RANK = Object.freeze({ HIGH: 0, MEDIUM: 1, LOW: 2 })

const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)
const num = (v) => (v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))

/**
 * Yalnız açıkça kullanılamaz adres eksik sayılır: boş, '@' yok / birden çok, yerel ya da alan kısmı boş, boşluk içeriyor.
 * Sunucunun kuralından daha katı DEĞİL (ör. `ayse@localhost` eksik sayılmaz — yanlış uyarı yok).
 */
function deliverable(email) {
  if (typeof email !== 'string') return false
  const e = email.trim()
  const at = e.indexOf('@')
  return at > 0 && at === e.lastIndexOf('@') && at < e.length - 1 && !/\s/.test(e)
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
    out.push({
      key: `${teamId}:${r.code}`, category, code: r.code, severity: SEVERITIES.includes(r.severity) ? r.severity : 'LOW',
      teamId, teamName, failing, items: items.slice(0, MAX_ITEMS_PER_GAP),
      more: Math.max(0, failing - Math.min(items.length, MAX_ITEMS_PER_GAP)),
    })
  }
  return out
}

/**
 * Tüm eksikler: `{ byCategory: {profile, team, monitors, inventory}, total, marks, failedTeams, profileKnown }`. Kategoride
 * sıra: önem, sonra takım adı, sonra kural. `marks` = kendiliğinden gösterme kararı için EKSİK düzeyinde işaretler
 * `{ key, n, scope }` (n = kusurlu kayıt sayısı; profil 1). Okunamayan takım `failedTeams`'e girer (bilinmeyen ≠ eksik yok)
 * ve işaretlere katılmaz; uygulanamayan takım (`skipped`: pasif / görünmez — 404) hiçbir yere girmez. `profileKnown`:
 * profil alanları yanıtta vardı (yoksa "tamamlanacak bir şey yok" denmez).
 */
export function collectGaps({ profile, profileFailed = false, teams = [] }) {
  const byCategory = Object.fromEntries(GAP_CATEGORIES.map((c) => [c, []]))
  const marks = []
  const failedTeams = []
  for (const g of profileGaps(profile)) {
    byCategory.profile.push(g)
    marks.push({ key: g.key, n: 1, scope: 'p' })
  }
  for (const t of teams) {
    if (!t || t.id == null || t.skipped) continue
    if (t.failed) { failedTeams.push({ id: t.id, name: t.name ?? null }); continue }
    for (const g of teamGaps(t.id, t.data)) {
      byCategory[g.category].push(g)
      marks.push({ key: g.key, n: g.failing, scope: `t${t.id}` })
    }
  }
  const has = (k) => isObj(profile) && Object.prototype.hasOwnProperty.call(profile, k)
  const profileKnown = has('email') && has('phone')
  const coll = typeof Intl !== 'undefined' ? new Intl.Collator('tr', { sensitivity: 'base', numeric: true }) : null
  for (const c of GAP_CATEGORIES) {
    byCategory[c].sort((a, b) => (SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity])
      || (coll ? coll.compare(a.teamName || '', b.teamName || '') : 0) || String(a.code).localeCompare(String(b.code)))
  }
  const total = GAP_CATEGORIES.reduce((n, c) => n + byCategory[c].length, 0)
  return { byCategory, total, marks, failedTeams, profileFailed: profileFailed === true, profileKnown }
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

/**
 * Saklanan durum (bozuksa boş): `{ v: 2, snoozeUntil: ms|null, dismissed: [[eksikÖzeti, kayıtSayısı, kapsamÖzeti]] }`.
 * Özetler 8 haneli onaltılık (ham kimlik / ad / kod yazılmaz). Önceki biçim (v1, yalnız özet dizgeleri — yayınlanmadı)
 * yok sayılır.
 */
export function readState(raw) {
  let o = raw
  if (typeof raw === 'string') {
    try { o = JSON.parse(raw) } catch { o = null }
  }
  if (!isObj(o)) return { v: 2, snoozeUntil: null, dismissed: [] }
  const snooze = num(o.snoozeUntil)
  const hex = (x) => typeof x === 'string' && /^[0-9a-f]{8}$/.test(x)
  const dismissed = (Array.isArray(o.dismissed) ? o.dismissed : [])
    .filter((e) => Array.isArray(e) && e.length === 3 && hex(e[0]) && Number.isInteger(e[1]) && e[1] >= 0 && hex(e[2]))
    .slice(0, MAX_DISMISSED)
  return { v: 2, snoozeUntil: snooze !== null && snooze > 0 ? snooze : null, dismissed }
}

/**
 * Kendiliğinden gösterilsin mi: eksik varsa, ertelenmediyse ve "gösterme" dendiğinden beri YENİ bir eksik çıktıysa ya da
 * bir eksiğin kusurlu kayıt sayısı ARTTIYSA. Düzelen (azalan / kaybolan) eksik pencereyi açmaz. Eksik yoksa gösterilmez.
 */
export function shouldPrompt(state, marks, now) {
  const s = readState(state)
  if (!Array.isArray(marks) || marks.length === 0) return false
  if (s.snoozeUntil !== null && s.snoozeUntil > now) return false
  const known = new Map(s.dismissed.map((e) => [e[0], e[1]]))
  return marks.some((m) => {
    const n = known.get(hashKey(m?.key))
    return n === undefined || (Number(m?.n) || 0) > n
  })
}

/** "Daha sonra hatırlat": 1 gün sonra (gösterme kaydı korunur). */
export function snoozeState(state, now) {
  const s = readState(state)
  return { ...s, snoozeUntil: now + SNOOZE_MS }
}

/**
 * "Bir daha gösterme": şu anki eksikler ve kayıt sayıları hatırlanır. Okunamayan takımların (`failedTeamIds`) önceki
 * kaydı KORUNUR (o takım bu kez okunamadı diye, daha önce gösterilmemesi istenen eksikleri yeniden açılmasın).
 */
export function dismissState(state, marks, failedTeamIds = []) {
  const s = readState(state)
  const out = []
  const seen = new Set()
  for (const m of Array.isArray(marks) ? marks : []) {
    const h = hashKey(m?.key)
    if (seen.has(h)) continue
    seen.add(h)
    out.push([h, Math.max(0, Math.round(Number(m?.n) || 0)), hashKey(m?.scope ?? '')])
  }
  const keepScopes = new Set((Array.isArray(failedTeamIds) ? failedTeamIds : []).map((id) => hashKey(`t${id}`)))
  for (const e of s.dismissed) {
    if (!seen.has(e[0]) && keepScopes.has(e[2])) { seen.add(e[0]); out.push(e) }
  }
  return { ...s, snoozeUntil: null, dismissed: out.slice(0, MAX_DISMISSED) }
}

/** Oturuma bağlı yerel kayıt `{ checkedAt, closedAt }` (bozuksa boş). */
export function readSession(raw) {
  let o = raw
  if (typeof raw === 'string') {
    try { o = JSON.parse(raw) } catch { o = null }
  }
  if (!isObj(o)) return { checkedAt: null, closedAt: null }
  const c = num(o.checkedAt)
  const k = num(o.closedAt)
  return { checkedAt: c !== null && c > 0 ? c : null, closedAt: k !== null && k > 0 ? k : null }
}

/**
 * Kendiliğinden okuma YAPILSIN mı (ağa çıkmadan önceki karar): ertelendiyse, bu oturumda kapatıldıysa (12 sa) ya da başka
 * bir sekme yakın zamanda denetlediyse (30 dk) hayır. Saat geri alındıysa (gelecekte damga) kayıt yok sayılır.
 */
export function shouldAutoCheck(state, session, now) {
  const s = readState(state)
  if (s.snoozeUntil !== null && s.snoozeUntil > now) return false
  const ss = readSession(session)
  if (ss.closedAt !== null && ss.closedAt <= now && now - ss.closedAt < CLOSE_QUIET_MS) return false
  if (ss.checkedAt !== null && ss.checkedAt <= now && now - ss.checkedAt < RECHECK_MS) return false
  return true
}

/** Üst süre dolduğunda {@link withDeadline}'in döndürdüğü işaret (okuma "okunamadı" sayılır). */
export const DEADLINE = Symbol('gaps-deadline')

/**
 * Söz `ms` içinde sonuçlanmazsa {@link DEADLINE} döner. Söz kendi zaman aşımıyla sonra biter; sonucu yok sayılır. Söz
 * reddedilirse ret aynen geçer. Zamanlayıcı her durumda temizlenir.
 */
export function withDeadline(promise, ms) {
  let timer
  return Promise.race([
    Promise.resolve(promise),
    new Promise((resolve) => { timer = setTimeout(() => resolve(DEADLINE), ms) }),
  ]).finally(() => clearTimeout(timer))
}
