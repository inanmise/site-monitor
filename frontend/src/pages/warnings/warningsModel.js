/**
 * Dikkat Gerektiren Sertifikalar (Uyarılar, `?tab=warnings`) — SAF model (2026-09-27 yeniden tasarım).
 *
 * <p>React yok: zenginleştirme, "neden dikkat istiyor" gerekçeleri, aciliyet grupları, önerilen sonraki adım,
 * kutucuk (neden) süzgeçleri, faset süzgeçleri, sıralama ve CSV satırları burada — sayfa ve testler aynı sözlüğü
 * paylaşsın diye.
 *
 * <p><b>Veri.</b> `/api/warnings` = `warning=true` (kalan gün ≤ genel uyarı eşiği) VEYA `status=error` olan aktif
 * envanter satırları, `CertificateDto` — takım/tier/alert_level/platform TAŞIMAZ (sunucu `getWarnings()` bunlarla
 * zenginleştirmiyor). Bu alanlar Pano listesinden (`/api/certificates`, aynı takım kapsamı) alan adıyla doldurulur;
 * uyarı satırının kendi değeri varsa o kazanır. 7/24 alanları (`noc_notify`, `noc_group_ids` — 2026-09-28) satırın
 * KENDİSİNDE gelir (sunucu aktif süzgecin aynı envanter okumasından yazar) → zenginleştirme listesinde YOK.
 *
 * <p><b>Gruplar</b> (bir satır TEK gruba düşer; öncelik sırası = görüntü sırası):
 *  - `now`    — dolmuş, ≤ 7 gün, erişilemeyen, iptal edilmiş, güvenilmeyen CA / ad uyuşmazlığı (tarayıcı reddediyor)
 *  - `config` — zincir kırık/eksik, dağıtım uyuşmazlığı (eski sertifika sunuluyor), ara sertifika ≤ 30 gün, zayıf
 *               algoritma — yalnız yenilemek bunları çözmeyebilir
 *  - `soon`   — kalanı: uyarı penceresinde, yapılandırması temiz → yenilemeyi planla
 */

/** Aciliyet grupları — görüntü sırası. */
export const GROUP_KEYS = ['now', 'config', 'soon']

/** "Neden" kutucukları (URL `wa_why`) — sıra kutucuk çubuğunun sırası. */
export const WHY_KEYS = ['expired', 'week', 'month', 'chain', 'error', 'weak', 'silent', 'mail']

/** Sıralama anahtarları (URL `wa_sort`); `urgency` varsayılan. Sıralama grup İÇİNDE uygulanır. */
export const SORT_KEYS = ['urgency', 'expiry', 'domain', 'team']

/** Kritiklik faset değerleri; `none` = sınıflandırılmamış. */
export const TIER_VALUES = ['1', '2', '3', '4', 'none']

/** Takım faset değeri: takımsız satırlar. */
export const NO_TEAM = '__none__'

/** Pano satırından uyarı satırına taşınan envanter alanları (uyarı satırında boşsa). */
const ENRICH_KEYS = ['team_id', 'team_name', 'tier', 'alert_level', 'platform', 'platform_name', 'platform_detail', 'group_name', 'tags', 'port']

/**
 * Uyarı satırlarını Pano listesiyle alan adından zenginleştirir. Alan adı olmayan satır atlanır (anahtar yok).
 * @param {Array} warnings `/api/warnings` `data`
 * @param {Array} certs    `/api/certificates` `data` (App durumu)
 */
export function enrich(warnings, certs) {
  const idx = new Map()
  for (const c of certs || []) if (c?.domain) idx.set(c.domain, c)
  return (warnings || []).filter((w) => w?.domain).map((w) => {
    const c = idx.get(w.domain)
    if (!c) return w
    const out = { ...w }
    for (const k of ENRICH_KEYS) if (out[k] == null && c[k] != null) out[k] = c[k]
    return out
  })
}

const daysOf = (r) => (Number.isFinite(r?.days_remaining) ? r.days_remaining : null)
const flagsOf = (r) => (Array.isArray(r?.security_flags) ? r.security_flags : [])

export const isError = (r) => r?.status === 'error'
export const isExpired = (r) => !isError(r) && daysOf(r) != null && daysOf(r) < 0
export const isWeek = (r) => !isError(r) && daysOf(r) != null && daysOf(r) >= 0 && daysOf(r) <= 7
export const isMonth = (r) => !isError(r) && daysOf(r) != null && daysOf(r) >= 8 && daysOf(r) <= 30
export const isRevoked = (r) => r?.revocation_status === 'REVOKED'
export const isUntrusted = (r) => r?.trust_status === 'UNTRUSTED' || flagsOf(r).includes('UNTRUSTED_CA')
export const isNameMismatch = (r) => flagsOf(r).includes('HOSTNAME_MISMATCH')
export const chainState = (r) => (r?.chain_status === 'BROKEN' || r?.chain_status === 'INCOMPLETE' ? r.chain_status : null)
export const isDeploymentGap = (r) => r?.deployment_status === 'INCOMPLETE' || r?.deployment_status === 'MISMATCH'
export const intermediateDays = (r) => (!isError(r) && Number.isFinite(r?.intermediate_days_remaining) && r.intermediate_days_remaining <= 30
  ? r.intermediate_days_remaining : null)

/** Zincir/güven kutucuğu: tarayıcı reddi (iptal, güvenilmeyen CA, ad uyuşmazlığı) + zincir + dağıtım + ara sertifika. */
export const isChainOrTrust = (r) => isRevoked(r) || isUntrusted(r) || isNameMismatch(r) || chainState(r) != null
  || isDeploymentGap(r) || intermediateDays(r) != null

/**
 * Bağlam: `{ weak: Set|null, silent: Set, mail: Set, plans: { [domain]: renewal } }`. `weak` null = zayıf-algoritma
 * verisi yok (bilinmiyor → kutucuk ve gerekçe gösterilmez; yokluk "güçlü" demek değildir).
 */
const has = (set, d) => !!set && typeof set.has === 'function' && set.has(d)
export const isWeak = (r, ctx) => has(ctx?.weak, r?.domain)

/** Satırın planlı yenilemesi (Pano kart eklerinden: `{ planned_at, note, by, overdue, done }`) — yoksa null. */
export const planOf = (r, ctx) => (ctx?.plans && r?.domain ? ctx.plans[r.domain]?.renewal ?? null : null)

/**
 * "Neden dikkat istiyor" gerekçeleri — önem sırasına göre. Her öğe `{ key, tone, n? }`:
 * tone ∈ bad | high | warn | weak | info (çip rengi). `n` metne giren sayı (gün).
 */
export function reasonsOf(r, ctx) {
  const out = []
  const d = daysOf(r)
  if (isError(r)) out.push({ key: 'error', tone: 'bad' })
  if (isExpired(r)) out.push({ key: -d === 1 ? 'expired1' : 'expired', tone: 'bad', n: -d })
  if (isWeek(r)) out.push({ key: d === 0 ? 'today' : d === 1 ? 'days1' : 'days', tone: 'bad', n: d })
  if (isRevoked(r)) out.push({ key: 'revoked', tone: 'bad' })
  if (isUntrusted(r)) out.push({ key: 'untrusted', tone: 'bad' })
  if (isNameMismatch(r)) out.push({ key: 'hostname', tone: 'bad' })
  if (isDeploymentGap(r)) out.push({ key: 'deployment', tone: 'high' })
  const ch = chainState(r)
  if (ch) out.push({ key: ch === 'BROKEN' ? 'chainBroken' : 'chainIncomplete', tone: 'high' })
  const id = intermediateDays(r)
  if (id != null) out.push({ key: 'intermediate', tone: 'warn', n: Math.max(0, id) })
  if (isMonth(r)) out.push({ key: 'days', tone: 'warn', n: d })
  // Uyarı eşiği 30'dan büyük kurulmuşsa (WARNING_DAYS): gün yine gerekçedir
  if (!isError(r) && d != null && d > 30) out.push({ key: 'days', tone: 'warn', n: d })
  if (isWeak(r, ctx)) out.push({ key: 'weak', tone: 'weak' })
  if (has(ctx?.mail, r?.domain)) out.push({ key: 'mail', tone: 'bad' })
  if (has(ctx?.silent, r?.domain)) out.push({ key: 'silent', tone: 'warn' })
  return out
}

/** Aciliyet grubu (bkz. dosya başı). */
export function groupOf(r, ctx) {
  if (isError(r) || isExpired(r) || isWeek(r) || isRevoked(r) || isUntrusted(r) || isNameMismatch(r)) return 'now'
  if (chainState(r) || isDeploymentGap(r) || intermediateDays(r) != null || isWeak(r, ctx)) return 'config'
  return 'soon'
}

/**
 * Önerilen sonraki adım: `{ key, action, date? }`. action ∈ check | health | plan | open — satırın BİRİNCİL düğmesi.
 * Öncelik: erişilemiyor → iptal → tarayıcı reddi → dolmuş/≤7 → dağıtım → zincir → ara sertifika → zayıf → planla.
 */
export function nextStepOf(r, ctx) {
  const plan = planOf(r, ctx)
  const planned = plan && plan.planned_at && !plan.done ? plan : null
  if (isError(r)) return { key: 'checkEndpoint', action: 'check' }
  if (isRevoked(r)) return { key: 'replaceRevoked', action: 'plan' }
  if (isUntrusted(r) || isNameMismatch(r)) return { key: 'replaceUntrusted', action: 'plan' }
  if (isExpired(r) || isWeek(r)) {
    if (planned) return { key: planned.overdue ? 'planOverdue' : 'followPlan', action: 'plan', date: planned.planned_at }
    return { key: 'renewNow', action: 'plan' }
  }
  if (isDeploymentGap(r)) return { key: 'deploy', action: 'health' }
  if (chainState(r)) return { key: 'fixChain', action: 'health' }
  if (intermediateDays(r) != null) return { key: 'updateIntermediate', action: 'health' }
  if (isWeak(r, ctx)) return { key: 'reissueStrong', action: 'plan' }
  if (planned) return { key: planned.overdue ? 'planOverdue' : 'followPlan', action: 'plan', date: planned.planned_at }
  if (daysOf(r) != null) return { key: 'planRenewal', action: 'plan' }
  return { key: 'review', action: 'open' }
}

/** Kutucuk (neden) eşleşmesi. */
export function matchesWhy(r, key, ctx) {
  switch (key) {
    case 'expired': return isExpired(r)
    case 'week': return isWeek(r)
    case 'month': return isMonth(r)
    case 'chain': return isChainOrTrust(r)
    case 'error': return isError(r)
    case 'weak': return isWeak(r, ctx)
    case 'silent': return has(ctx?.silent, r?.domain)
    case 'mail': return has(ctx?.mail, r?.domain)
    default: return true
  }
}

/** Kutucuk sayıları (verilen satırlar üzerinde). */
export function whyCounts(rows, ctx) {
  const out = Object.fromEntries(WHY_KEYS.map((k) => [k, 0]))
  for (const r of rows) for (const k of WHY_KEYS) if (matchesWhy(r, k, ctx)) out[k]++
  return out
}

/** Satırın takım faset değeri (id dizesi ya da NO_TEAM). */
export const teamKey = (r) => (r?.team_id != null ? String(r.team_id) : NO_TEAM)
/** Satırın kritiklik faset değeri. */
export const tierKey = (r) => (r?.tier != null && TIER_VALUES.includes(String(r.tier)) ? String(r.tier) : 'none')

/** Arama: alan adı, takım, veren (CA), hata metni, platform — büyük/küçük harf duyarsız. */
export function matchesSearch(r, q) {
  const s = String(q || '').trim().toLowerCase()
  if (!s) return true
  return [r.domain, r.team_name, r.issuer_cn, r.issuer, r.error, r.platform_name, r.platform, r.group_name]
    .some((v) => v != null && String(v).toLowerCase().includes(s))
}

/** Faset süzgeçleri: arama + takım (çoklu) + kritiklik (çoklu). Boş dizi = süzgeç yok. */
export function applyFacets(rows, { q = '', teams = [], tiers = [] } = {}) {
  return rows.filter((r) => matchesSearch(r, q)
    && (!teams.length || teams.includes(teamKey(r)))
    && (!tiers.length || tiers.includes(tierKey(r))))
}

/** Grup içi aciliyet sırası: dolmuş → ≤7 → tarayıcı reddi → erişilemeyen → dağıtım → zincir → ara → zayıf → kalan. */
export function urgencyRank(r, ctx) {
  if (isExpired(r)) return 0
  if (isWeek(r)) return 1
  if (isRevoked(r) || isUntrusted(r) || isNameMismatch(r)) return 2
  if (isError(r)) return 3
  if (isDeploymentGap(r)) return 4
  if (chainState(r)) return 5
  if (intermediateDays(r) != null) return 6
  if (isWeak(r, ctx)) return 7
  return 8
}

const byDays = (a, b) => (daysOf(a) ?? 1e9) - (daysOf(b) ?? 1e9)
const byDomain = (a, b) => String(a.domain).localeCompare(String(b.domain))
/** Takım adına göre; takımsız satırlar sona. */
const byTeam = (a, b) => {
  if (!a.team_name || !b.team_name) return (a.team_name ? 0 : 1) - (b.team_name ? 0 : 1)
  return a.team_name.localeCompare(b.team_name)
}

/**
 * Sıralama — gruplar her zaman önce (now → config → soon), seçilen anahtar grup İÇİNDE.
 * `items` = analiz edilmiş öğeler `{ row, group, rank }` (sayfa her satırı bir kez analiz eder).
 */
export function sortItems(items, sortKey = 'urgency') {
  const g = (it) => GROUP_KEYS.indexOf(it.group)
  const inner = {
    urgency: (a, b) => a.rank - b.rank || byDays(a.row, b.row) || byDomain(a.row, b.row),
    expiry: (a, b) => byDays(a.row, b.row) || byDomain(a.row, b.row),
    domain: (a, b) => byDomain(a.row, b.row),
    team: (a, b) => byTeam(a.row, b.row) || byDays(a.row, b.row) || byDomain(a.row, b.row),
  }[SORT_KEYS.includes(sortKey) ? sortKey : 'urgency']
  return [...items].sort((a, b) => g(a) - g(b) || inner(a, b))
}

/** Bir satırı sayfa için bir kez analiz eder. */
export function analyze(row, ctx) {
  return { row, group: groupOf(row, ctx), rank: urgencyRank(row, ctx), reasons: reasonsOf(row, ctx), next: nextStepOf(row, ctx), plan: planOf(row, ctx) }
}

/** Sayfa öğelerini grup sırasıyla bölümlere ayırır: `[{ key, items }]` (boş grup yok). */
export function groupItems(items) {
  const by = new Map(GROUP_KEYS.map((k) => [k, []]))
  for (const it of items) by.get(it.group)?.push(it)
  return GROUP_KEYS.filter((k) => by.get(k).length).map((k) => ({ key: k, items: by.get(k) }))
}

/**
 * Takım faset seçenekleri: seçenekler TÜM satırlardan (arama sonuçsuz kalınca faset düğmesi kaybolup yerleşim
 * zıplamasın), sayılar `countRows` üzerinde (öteki süzgeçler uygulanmış — 0 sayılı seçenek soluk görünür).
 */
export function teamOptions(rows, noTeamLabel, countRows = rows) {
  const opts = new Map()
  for (const r of rows) {
    const k = teamKey(r)
    if (!opts.has(k)) opts.set(k, { value: k, label: k === NO_TEAM ? noTeamLabel : (r.team_name || `#${k}`), count: 0 })
  }
  for (const r of countRows) { const o = opts.get(teamKey(r)); if (o) o.count++ }
  const list = [...opts.values()]
  return [...list.filter((o) => o.value !== NO_TEAM).sort((a, b) => a.label.localeCompare(b.label)), ...list.filter((o) => o.value === NO_TEAM)]
}

/** Kritiklik faset seçenekleri — yalnız veride geçen değerler; sayılar `countRows` üzerinde. */
export function tierOptions(rows, noneLabel, countRows = rows) {
  const present = new Set(rows.map(tierKey))
  const counts = {}
  for (const r of countRows) { const k = tierKey(r); counts[k] = (counts[k] || 0) + 1 }
  return TIER_VALUES.filter((k) => present.has(k)).map((k) => ({ value: k, label: k === 'none' ? noneLabel : `T${k}`, count: counts[k] || 0 }))
}

/** URL listesi (virgüllü) ↔ dizi. */
export const parseList = (raw, allowed) => String(raw || '').split(',').map((s) => s.trim())
  .filter((s) => s && (!allowed || allowed.includes(s)))
export const serializeList = (list) => (list && list.length ? list.join(',') : null)
