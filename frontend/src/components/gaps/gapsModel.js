// Giriş sonrası eksikler penceresi (2026-11-15, kullanıcı isteği; zenginleştirme aynı gün) — SAF model: profil eksikleri,
// kişinin KENDİ takımlarının veri kalitesi bulguları (`/api/data-quality/teams/{id}`), veri kalitesi dışı takım eksikleri
// (gapsExtra.js) ve bekleyen işler beş kategoride; ne zaman kendiliğinden gösterileceği (erteleme / yeni eksik çıkana dek
// gösterme), tek tek gizlenen eksikler ve son görülen eksiklerle karşılaştırma ("Yeni" / "düzeldi"). React yok, ağ yok.

import {
  approvalTasks, actionTasks, channelApproverGaps, channelReportTasks, forwardGaps, groupGaps, oncallGaps,
} from './gapsExtra.js'
import { profileGaps as profileGapsOf } from './gapsProfile.js'

/** Kategoriler (sıra = ekrandaki sıra). */
export const GAP_CATEGORIES = Object.freeze(['profile', 'team', 'monitors', 'inventory', 'tasks'])
/** En çok sorgulanan takım (kendiliğinden yol; fazlası pencerede "sonraki takımlar" / takım seçiciyle). */
export const MAX_TEAMS = 10
/** Bir pencerede en çok denetlenen takım (seçiciyle eklenenler dahil) — saklanan kayıt ve istek sayısı sınırlı kalsın. */
export const MAX_LOADED_TEAMS = 40
/** Kural başına gösterilen en çok kalem (kalanı "veri kalitesi sayfasında"). */
export const MAX_ITEMS_PER_GAP = 5
/** "Daha sonra hatırlat" süreleri. */
export const SNOOZE_MS = 24 * 60 * 60 * 1000
export const SNOOZE_WEEK_MS = 7 * SNOOZE_MS
/**
 * Saklanan "gösterme" kaydının en çok girdisi. Girdi EKSİK (kural) düzeyindedir: profil 6 + 10 takım × ~21 + 2 iş ≈ 220.
 * Sınırlar birlikte kullanıcı tercihi anahtar sınırının (`UserPreferencesService.MAX_LOCAL_VALUE`, 16 KB) altında kalır —
 * aşılırsa sunucu bütün tercih belgesini 400 ile reddeder (gapsModel testi en kötü durumu ölçer).
 */
export const MAX_DISMISSED = 250
/** Tek tek gizlenen eksiklerin en çok sayısı (en yeniler kalır). */
export const MAX_HIDDEN = 100
/** "Son görülen" eksiklerin en çok sayısı (Yeni / düzeldi karşılaştırması). */
export const MAX_SEEN = 250
/** Sıkıştırılmış "son görülen" dizgesinin en çok uzunluğu (bozuk / şişmiş değer okunmaz). */
export const MAX_SEEN_CHARS = 6000
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
/**
 * Kendiliğinden gösterme kullanıcının işini BÖLMEZ (2026-11-15, CI'da yakalanan hata: pencere açık bir "İşlemler" menüsünün
 * üstüne açılıyor, odağı alıyor, kipsiz menü kapanıyordu — kullanıcı "Sürdür"e basamadan menü elinden gidiyordu). Kullanıcı
 * en az bu kadar süredir dokunmamış / tuşa basmamış / kaydırmamış ve açık bir menü / açılır kutu yoksa pencere açılır.
 */
export const AUTO_QUIET_MS = 2000
/** Sessizlik beklenirken yeniden bakma aralığı. */
export const AUTO_DEFER_POLL_MS = 500
/**
 * Sessizlik bekleme üst sınırı: kullanıcı bu kadar süre boyunca kesintisiz çalıştıysa bu oturumda kendiliğinden açılmaz
 * (kullanıcı menüsündeki "Eksiklerim" her zaman açar) — bekleme süresiz değil.
 */
export const AUTO_DEFER_MAX_MS = 60_000

/**
 * Kullanıcı şu an bir şeyle uğraşıyor mu (kendiliğinden pencere beklesin mi)? Açık menü / açılır kutu varsa ya da son
 * girdinin üstünden {@link AUTO_QUIET_MS}'ten az geçtiyse evet. Zamanlar tekdüze saatten (`performance.now`); saat geri
 * gitmiş gibi görünürse (son girdi "gelecekte") temkinli: uğraşıyor sayılır.
 */
export function userInteracting({ now, lastInputAt, popupOpen }) {
  if (popupOpen) return true
  if (lastInputAt == null || !Number.isFinite(lastInputAt) || !Number.isFinite(now)) return false
  return now - lastInputAt < AUTO_QUIET_MS
}
/** Kullanıcı tercihindeki (yerel ayna) anahtar — UserPreferencesService.LOCAL_KEYS ile aynı. */
export const GAPS_STATE_KEY = 'sm.gaps.state'
/** Veri kalitesi dışı denetim adları (okunamadı / yetki yok notları). */
export const EXTRA_CHECKS = Object.freeze(['prefs', 'groups', 'oncall', 'forwards', 'channels', 'actions'])

const SCOPE_CATEGORY = Object.freeze({ TEAM: 'team', MONITOR: 'monitors', INVENTORY: 'inventory' })
const SEVERITIES = ['HIGH', 'MEDIUM', 'LOW']
const SEVERITY_RANK = Object.freeze({ HIGH: 0, MEDIUM: 1, LOW: 2 })
const HEX8 = /^[0-9a-f]{8}$/
/** Okunan ama eksiği olmayan kapsamın "son görülen" kaydındaki yer tutucusu (hiçbir eksiğin özetine denk gelmez). */
const SEEN_PLACEHOLDER = '00000000'

const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)
const num = (v) => (v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))

/** Profil eksikleri (geriye uyum: eski çağrılar `profileGaps(profile)` ile çalışır; ayrıntı gapsProfile.js'te). */
export function profileGaps(profile, ctx, prefs, now) {
  return profileGapsOf(profile, ctx, prefs, now)
}

/** Kişinin kendi takımları: üyesi olduğu + yönettiği (paylaşım / 7/24 genişlemesi YOK), tekil; ilk MAX_TEAMS + kalanlar. */
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
  return { ids: out.slice(0, MAX_TEAMS), more: Math.max(0, out.length - MAX_TEAMS), rest: out.slice(MAX_TEAMS) }
}

/**
 * Takım veri kalitesi yanıtından eksikler: kusurlu (failing > 0) her kural bir eksik — kategori kuralın kapsamından,
 * kalemler en çok MAX_ITEMS_PER_GAP (`more` = gösterilmeyen), `points` = kuralın takım puanından götürdüğü puan (yanıtta
 * yoksa 0). Bilinmeyen kapsam atlanır.
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
      points: Math.max(0, num(r.points) ?? 0), scope: `t${teamId}`, dq: true,
    })
  }
  return out
}

/** Takım veri kalitesi başlığı (puan, bant, 7 günlük değişim) — yanıtta yoksa null alanlar. */
export function teamScore(data) {
  const t = isObj(data?.team) ? data.team : {}
  return { score: num(t.score), band: typeof t.band === 'string' ? t.band : null, delta: num(t.delta_7d) }
}

/** Eski çağrı biçimi (`{id, data|failed|skipped}`) ile yeni (`{id, dq:{status,data}}`) aynı yola. */
function dqOf(t) {
  if (isObj(t?.dq)) return t.dq
  if (t?.failed) return { status: 'failed' }
  if (t?.skipped) return { status: 'skipped' }
  if (t && Object.prototype.hasOwnProperty.call(t, 'data')) return { status: 'ok', data: t.data }
  return { status: 'off' }
}

const statusOf = (src) => (isObj(src) && typeof src.status === 'string' ? src.status : 'off')

/**
 * Tüm eksikler. Girdi (hepsi isteğe bağlı): `profile`, `profileFailed`, `ctx` (önem bağlamı), `prefs` + `prefsStatus`
 * (kişisel push tercihleri), `teams` [{id, name, dq:{status,data}, oncall:{status,data}}], `groups` / `forwards` /
 * `channels` / `actions` = {status, data}, `hidden` (gizlenen eksik özetleri), `autoTeamIds` (kendiliğinden yolun takımları).
 * Kaynak durumları: ok | failed | denied (yetki yok) | unsupported (bu sürümde yok) | skipped (uygulanamaz) | off (istenmedi).
 *
 * Çıktı: `byCategory` (gizlenenler HARİÇ; kategoride sıra: önem, puan etkisi, takım adı, kural), `total`, `hiddenList`,
 * `marks` (görünen eksikler — kendiliğinden gösterme ve rozet için `{key, n, scope, auto}`), `allMarks` (gizlenenler dahil —
 * "Yeni / düzeldi" karşılaştırması), `failedTeams` (veri kalitesi okunamayan takım), `failedChecks` (diğer okunamayan
 * denetimler), `deniedChecks`, `readScopes`, `teamScores`, `profileFailed`, `profileKnown`.
 */
export function collectGaps(input = {}) {
  const {
    profile, profileFailed = false, ctx = {}, prefs = null, prefsStatus = 'off', teams = [],
    groups = null, forwards = null, channels = null, actions = null, hidden = null, autoTeamIds = null, now = Date.now(),
  } = input
  const all = []
  const failedTeams = []
  const failedChecks = []
  const denied = new Set()
  const unsupported = new Set()
  const readScopes = new Set()
  const teamScores = []
  const hiddenSet = hidden instanceof Set ? hidden : new Set(Array.isArray(hidden) ? hidden : [])
  const noteSource = (check, st, team = null) => {
    if (st === 'failed') failedChecks.push({ check, teamId: team?.id ?? null, teamName: team?.name ?? null })
    else if (st === 'denied') denied.add(check)
    else if (st === 'unsupported') unsupported.add(check)        // sunucu bu denetimi bu sürümde yapamıyor (404 / 405) — yetki değil
  }

  // ── Profil ──
  const prefsOk = prefsStatus === 'ok' && isObj(prefs)
  for (const g of profileGapsOf(profile, ctx, prefsOk ? prefs : null, now)) all.push(g)
  if (isObj(profile) && !profileFailed) readScopes.add('p')
  if (prefsOk) readScopes.add('pp')
  if (prefsOk && ctx?.onCall != null) readScopes.add('ppo')          // "yalnız kritik" eksiği nöbet bilgisine de bağlı
  noteSource('prefs', prefsStatus)

  // ── Takımlar ──
  const loaded = []
  for (const t of Array.isArray(teams) ? teams : []) {
    if (!t || t.id == null) continue
    const dq = dqOf(t)
    const name = (isObj(dq.data?.team) && typeof dq.data.team.name === 'string' ? dq.data.team.name : null) ?? t.name ?? null
    const team = { id: t.id, name }
    if (dq.status === 'skipped') continue
    loaded.push(team)
    if (dq.status === 'failed') failedTeams.push({ id: t.id, name: t.name ?? null })
    else if (dq.status === 'ok') {
      readScopes.add(`t${t.id}`)
      for (const g of teamGaps(t.id, dq.data)) all.push(g)
      teamScores.push({ id: t.id, name, ...teamScore(dq.data) })
    }
    const oc = isObj(t.oncall) ? t.oncall : null
    if (oc) {
      noteSource('oncall', statusOf(oc), team)
      if (statusOf(oc) === 'ok') { readScopes.add(`o${t.id}`); all.push(...oncallGaps(team, oc.data)) }
    }
  }
  const forEachTeam = (src, check, scopePrefix, fn) => {
    const st = statusOf(src)
    noteSource(check, st)
    if (st !== 'ok') return
    for (const team of loaded) { readScopes.add(`${scopePrefix}${team.id}`); all.push(...fn(team, src.data)) }
  }
  // Takım başına "her denetim okundu mu" (takıma göre görünümde "eksik yok" ancak hepsi okunduysa); yetkisiz denetim sayılmaz
  const sharedOk = (src) => ['ok', 'denied'].includes(statusOf(src))
  const teamChecked = {}
  for (const t of Array.isArray(teams) ? teams : []) {
    if (!t || t.id == null) continue
    const dqSt = dqOf(t).status
    const ocSt = isObj(t.oncall) ? statusOf(t.oncall) : 'off'
    teamChecked[String(t.id)] = ['ok', 'skipped', 'denied'].includes(dqSt) && ['ok', 'skipped', 'denied'].includes(ocSt)
      && sharedOk(groups) && sharedOk(forwards) && sharedOk(channels)
  }
  forEachTeam(groups, 'groups', 'g', groupGaps)
  forEachTeam(forwards, 'forwards', 'f', forwardGaps)
  forEachTeam(channels, 'channels', 'c', (team, data) => [...channelApproverGaps(team, data), ...channelReportTasks(team, data)])
  if (statusOf(channels) === 'ok') { readScopes.add('ka'); all.push(...approvalTasks(loaded, channels.data)) }
  if (statusOf(actions) === 'ok') { readScopes.add('kt'); all.push(...actionTasks(actions.data)) }
  noteSource('actions', statusOf(actions))

  // ── Sınıflama, gizleme, işaretler ──
  const byCategory = Object.fromEntries(GAP_CATEGORIES.map((c) => [c, []]))
  const hiddenList = []
  const marks = []
  const allMarks = []
  const autoSet = autoTeamIds == null ? null : new Set([...autoTeamIds].map(String))
  for (const g of all) {
    if (!byCategory[g.category]) continue
    const mark = { key: g.key, n: Number.isFinite(g.failing) ? g.failing : 1, scope: g.scope,
      auto: autoSet == null || g.teamId == null || autoSet.has(String(g.teamId)) }
    allMarks.push(mark)
    if (hiddenSet.has(hashKey(g.key))) { hiddenList.push(g); continue }
    byCategory[g.category].push(g)
    marks.push(mark)
  }
  const coll = typeof Intl !== 'undefined' ? new Intl.Collator('tr', { sensitivity: 'base', numeric: true }) : null
  const cmp = (a, b) => (SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity]) || ((b.points || 0) - (a.points || 0))
    || (coll ? coll.compare(a.teamName || '', b.teamName || '') : 0) || String(a.code).localeCompare(String(b.code))
  for (const c of GAP_CATEGORIES) byCategory[c].sort(cmp)
  hiddenList.sort(cmp)
  const has = (k) => isObj(profile) && Object.prototype.hasOwnProperty.call(profile, k)
  const profileKnown = has('email') && has('phone')
  const total = GAP_CATEGORIES.reduce((n, c) => n + byCategory[c].length, 0)
  return {
    byCategory, total, hiddenList, marks, allMarks, failedTeams, failedChecks, deniedChecks: [...denied], unsupportedChecks: [...unsupported],
    loadedTeamIds: loaded.map((t) => t.id), teamChecked,
    // Bekleyen işler: kanal / aksiyon kaynağı okunmadıysa (okunamadı, bu sürümde yok, yetkiler bilinmiyor) "iş yok" denmez
    tasksUnchecked: !(sharedOk(channels) && sharedOk(actions)),
    readScopes, teamScores, profileFailed: profileFailed === true, profileKnown, prefsFailed: prefsStatus === 'failed',
  }
}

/**
 * Takıma göre görünüm: profil dışı eksikler takım başına (sıra: en çok yüksek önemli eksik, sonra takım adı); takımsız işler
 * (onay bekleyen raporlar, aksiyonlar) `teamId: null` grubunda en sonda. Her grup puan başlığını taşır.
 */
export function groupByTeam(gaps) {
  if (!gaps) return []
  const groups = new Map()
  const scoreOf = new Map((gaps.teamScores || []).map((s) => [String(s.id), s]))
  for (const c of GAP_CATEGORIES) {
    if (c === 'profile') continue
    for (const g of gaps.byCategory[c]) {
      const k = g.teamId == null ? '' : String(g.teamId)
      if (!groups.has(k)) groups.set(k, { teamId: g.teamId ?? null, teamName: g.teamName ?? null, gaps: [], high: 0, score: scoreOf.get(k) || null })
      const grp = groups.get(k)
      grp.gaps.push(g)
      if (g.severity === 'HIGH') grp.high++
      if (!grp.teamName && g.teamName) grp.teamName = g.teamName
    }
  }
  // Eksiği olmayan ama puanı okunan takımlar da görünür (eksiksiz takım bilgisi)
  for (const s of gaps.teamScores || []) {
    const k = String(s.id)
    if (!groups.has(k)) groups.set(k, { teamId: s.id, teamName: s.name ?? null, gaps: [], high: 0, score: s })
  }
  const coll = typeof Intl !== 'undefined' ? new Intl.Collator('tr', { sensitivity: 'base', numeric: true }) : null
  return [...groups.values()].sort((a, b) => ((a.teamId == null) - (b.teamId == null)) || (b.high - a.high)
    || (b.gaps.length - a.gaps.length) || (coll ? coll.compare(a.teamName || '', b.teamName || '') : 0))
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
 * "Son görülen" sıkıştırılmış dizge → `Map(kapsamÖzeti → Map(eksikÖzeti → sayı))`. Biçim `kk:ee.n,ee.n;kk:ee.n` (8 haneli
 * onaltılık özetler). Bozuk parça atlanır; uzunluk ve girdi sayısı sınırlı (döngüler girdiyle sınırlı).
 */
export function parseSeen(raw) {
  const out = new Map()
  if (typeof raw !== 'string' || !raw || raw.length > MAX_SEEN_CHARS) return out
  let count = 0
  for (const part of raw.split(';')) {
    const i = part.indexOf(':')
    if (i !== 8) continue
    const sc = part.slice(0, 8)
    if (!HEX8.test(sc)) continue
    const inner = out.get(sc) || new Map()
    for (const e of part.slice(9).split(',')) {
      if (count >= MAX_SEEN) break
      const j = e.indexOf('.')
      if (j !== 8) continue
      const h = e.slice(0, 8)
      const n = Number(e.slice(9))
      if (!HEX8.test(h) || !Number.isInteger(n) || n < 0) continue
      inner.set(h, n)
      count++
    }
    if (inner.size) out.set(sc, inner)
  }
  return out
}

/**
 * `Map(kapsam → Map(eksik → sayı))` → sıkıştırılmış dizge (en çok MAX_SEEN girdi). Kapsam BÜTÜN yazılır ya da hiç yazılmaz:
 * yarım yazılan kapsamın yazılmayan eksikleri her açılışta yanlışlıkla "yeni" görünürdü; sığmayan kapsamın tabanı yok sayılır
 * (taban yoksa "yeni" denmez).
 */
export function formatSeen(map) {
  const parts = []
  let count = 0
  for (const [sc, inner] of map) {
    if (inner.size === 0) continue
    if (count + inner.size > MAX_SEEN) continue
    const es = []
    for (const [h, n] of inner) es.push(`${h}.${Math.max(0, Math.round(n))}`)
    parts.push(`${sc}:${es.join(',')}`)
    count += inner.size
  }
  return parts.join(';')
}

/**
 * Saklanan durum (bozuksa boş): `{ v: 2, snoozeUntil, dismissed: [[eksikÖzeti, kayıtSayısı, kapsamÖzeti]], hidden: [özet],
 * seen: "<sıkıştırılmış>" }`. Özetler 8 haneli onaltılık (ham kimlik / ad / kod yazılmaz). `hidden` ve `seen` 2026-11-15
 * zenginleştirmesinin EKLEMELİ alanlarıdır — `v` 2 kalır (önceki sürümün istemcisi kaydı okumaya devam eder).
 */
export function readState(raw) {
  let o = raw
  if (typeof raw === 'string') {
    try { o = JSON.parse(raw) } catch { o = null }
  }
  if (!isObj(o)) return { v: 2, snoozeUntil: null, dismissed: [], hidden: [], seen: '' }
  const snooze = num(o.snoozeUntil)
  const hex = (x) => typeof x === 'string' && HEX8.test(x)
  const dismissed = (Array.isArray(o.dismissed) ? o.dismissed : [])
    .filter((e) => Array.isArray(e) && e.length === 3 && hex(e[0]) && Number.isInteger(e[1]) && e[1] >= 0 && hex(e[2]))
    .slice(0, MAX_DISMISSED)
  const hidden = [...new Set((Array.isArray(o.hidden) ? o.hidden : []).filter(hex))].slice(-MAX_HIDDEN)
  const seen = typeof o.seen === 'string' && o.seen.length <= MAX_SEEN_CHARS ? formatSeen(parseSeen(o.seen)) : ''
  return { v: 2, snoozeUntil: snooze !== null && snooze > 0 ? snooze : null, dismissed, hidden, seen }
}

/**
 * Kendiliğinden gösterilsin mi: eksik varsa, ertelenmediyse ve "gösterme" dendiğinden beri YENİ bir eksik çıktıysa ya da
 * bir eksiğin kusurlu kayıt sayısı ARTTIYSA. Düzelen (azalan / kaybolan) eksik pencereyi açmaz. Eksik yoksa gösterilmez.
 * Gizlenen eksikler ve kendiliğinden yolun dışındaki takımların eksikleri (`auto === false`) sayılmaz.
 */
export function shouldPrompt(state, marks, now) {
  const s = readState(state)
  const list = (Array.isArray(marks) ? marks : []).filter((m) => m && m.auto !== false && !s.hidden.includes(hashKey(m.key)))
  if (list.length === 0) return false
  if (s.snoozeUntil !== null && s.snoozeUntil > now) return false
  const known = new Map(s.dismissed.map((e) => [e[0], e[1]]))
  return list.some((m) => {
    const n = known.get(hashKey(m?.key))
    return n === undefined || (Number(m?.n) || 0) > n
  })
}

/** "Daha sonra hatırlat": varsayılan 1 gün, isteğe 1 hafta (gösterme kaydı korunur). */
export function snoozeState(state, now, ms = SNOOZE_MS) {
  const s = readState(state)
  const d = ms === SNOOZE_WEEK_MS ? SNOOZE_WEEK_MS : SNOOZE_MS
  return { ...s, snoozeUntil: now + d }
}

/**
 * "Bir daha gösterme": şu anki eksikler ve kayıt sayıları hatırlanır (kendiliğinden yolun dışındaki takımlarınkiler hariç —
 * o yol onlara bakmaz). Okunamayan takımların (`failedTeamIds`) ve okunamayan diğer denetimlerin (`failedScopes`, ham kapsam
 * adları) önceki kaydı KORUNUR (bu kez okunamadı diye daha önce gösterilmemesi istenen eksikleri yeniden açılmasın).
 */
export function dismissState(state, marks, failedTeamIds = [], failedScopes = []) {
  const s = readState(state)
  const out = []
  const seen = new Set()
  for (const m of Array.isArray(marks) ? marks : []) {
    if (!m || m.auto === false) continue
    const h = hashKey(m.key)
    if (seen.has(h)) continue
    seen.add(h)
    out.push([h, Math.max(0, Math.round(Number(m.n) || 0)), hashKey(m.scope ?? '')])
  }
  const keepScopes = new Set([
    ...(Array.isArray(failedTeamIds) ? failedTeamIds : []).map((id) => hashKey(`t${id}`)),
    ...(Array.isArray(failedScopes) ? failedScopes : []).map((sc) => hashKey(sc)),
  ])
  for (const e of s.dismissed) {
    if (!seen.has(e[0]) && keepScopes.has(e[2])) { seen.add(e[0]); out.push(e) }
  }
  return { ...s, snoozeUntil: null, dismissed: out.slice(0, MAX_DISMISSED) }
}

/** Tek eksiği gizle ("bu eksiği gösterme" — geri alınana dek listede ve rozette yok). */
export function hideState(state, key) {
  const s = readState(state)
  const h = hashKey(key)
  return { ...s, hidden: [...s.hidden.filter((x) => x !== h), h].slice(-MAX_HIDDEN) }
}

/** Gizlenen eksiği geri al. */
export function unhideState(state, key) {
  const s = readState(state)
  const h = hashKey(key)
  return { ...s, hidden: s.hidden.filter((x) => x !== h) }
}

/**
 * Son görülenlerle karşılaştırma: `newKeys` = kapsamı geçen sefer de okunmuş ve o zaman OLMAYAN eksikler (kapsam hiç
 * okunmadıysa "yeni" denmez — karşılaştırma tabanı yok); `fixed` = geçen sefer görülen ve kapsamı bu kez de OKUNAN ama artık
 * olmayan eksik sayısı (okunamayan kapsam düzelmiş sayılmaz). `first` = hiç kayıt yok.
 */
export function seenDiff(state, allMarks, readScopes) {
  const s = readState(state)
  const seen = parseSeen(s.seen)
  const read = new Set([...(readScopes || [])].map((sc) => hashKey(sc)))
  const present = new Set((Array.isArray(allMarks) ? allMarks : []).map((m) => hashKey(m.key)))
  const newKeys = new Set()
  for (const m of Array.isArray(allMarks) ? allMarks : []) {
    const inner = seen.get(hashKey(m.scope ?? ''))
    if (inner && !inner.has(hashKey(m.key))) newKeys.add(m.key)
  }
  let fixed = 0
  for (const [sc, inner] of seen) {
    if (!read.has(sc)) continue
    for (const h of inner.keys()) if (h !== SEEN_PLACEHOLDER && !present.has(h)) fixed++
  }
  return { first: seen.size === 0, newKeys, fixed }
}

/**
 * Görülenleri güncelle: okunan kapsamlar şimdiki eksiklerle değişir (eksiksiz okunan kapsam da boş girdi olarak tutulmaz,
 * "kapsam okundu" bilgisi için en az bir eksik gerekmez — taban yoksa yeni denmez), okunamayan kapsamların eski kaydı kalır.
 */
export function markSeen(state, allMarks, readScopes) {
  const s = readState(state)
  const old = parseSeen(s.seen)
  const read = new Set([...(readScopes || [])].map((sc) => hashKey(sc)))
  const next = new Map()
  // Okunan kapsamlar: boş kapsam da yer tutsun diye her okunan kapsam için bir harita (formatSeen boşları yazmaz → okunan ama
  // eksiksiz kapsam için yer tutucu yazılır)
  for (const sc of read) next.set(sc, new Map())
  for (const m of Array.isArray(allMarks) ? allMarks : []) {
    const sc = hashKey(m.scope ?? '')
    if (!read.has(sc)) continue
    next.get(sc).set(hashKey(m.key), Math.max(0, Math.round(Number(m.n) || 0)))
  }
  for (const inner of next.values()) if (inner.size === 0) inner.set(SEEN_PLACEHOLDER, 0)
  for (const [sc, inner] of old) if (!read.has(sc) && !next.has(sc)) next.set(sc, inner)
  return { ...s, seen: formatSeen(next) }
}

/** Oturuma bağlı yerel kayıt `{ checkedAt, closedAt, count }` (bozuksa boş). `count` = son okumadaki görünen eksik sayısı. */
export function readSession(raw) {
  let o = raw
  if (typeof raw === 'string') {
    try { o = JSON.parse(raw) } catch { o = null }
  }
  if (!isObj(o)) return { checkedAt: null, closedAt: null, count: null }
  const c = num(o.checkedAt)
  const k = num(o.closedAt)
  const n = num(o.count)
  return {
    checkedAt: c !== null && c > 0 ? c : null, closedAt: k !== null && k > 0 ? k : null,
    count: n !== null && Number.isInteger(n) && n >= 0 && n < 100_000 ? n : null,
  }
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
