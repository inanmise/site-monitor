/**
 * 7/24 İzleme Ekibi (NOC) — SAF model (2026-09-27; sözleşme `.migration/noc/CONTRACT.md`).
 *
 * React yok: Ayarlar bölümü (admin/NocSettings), Kapsam sayfası (pages/NocCoveragePage) ve Pano şeridi
 * (noc/NocCoverageBanner) aynı kuralları buradan okur; birim testte doğrudan sınanır.
 *
 * Sözlük: "kapsanan" (covered) = izleme aktif VE `noc_notify` VE tür etkin VE en az bir aktif hedef grup var.
 * Kapsanmama nedeni (`reason`) sunucudan gelir: MONITOR_OFF | TYPE_DISABLED | NO_ACTIVE_GROUP | PAUSED.
 */

/** Tür anahtarları — sözleşmedeki sıra (Ayarlar anahtarları ve tür başına kapsam bu sırayla çizilir). */
export const NOC_TYPES = ['SSL', 'PING', 'HTTP', 'KEYWORD', 'PAGE', 'PAGESPEED', 'SCRIPTED', 'DNS', 'PORT', 'DOMAIN']
/** Kapsanmama nedenleri — liste sırası = eylem önceliği (kullanıcının kendisinin düzeltebileceği önce). */
export const NOC_REASONS = ['MONITOR_OFF', 'TYPE_DISABLED', 'NO_ACTIVE_GROUP', 'PAUSED']
/** En düşük seviye seçenekleri (proje alarm seviyeleri, en katıdan gevşeğe). */
export const NOC_LEVELS = ['CRITICAL', 'HIGH', 'WARNING']
export const DEFAULT_LEVEL = 'CRITICAL'
/** Grup başına e-posta tavanı (sözleşme 1..50) ve arama talimatı tavanı (sunucu NocConfigService.MAX_INSTRUCTIONS —
 *  `noc-limits-sync` testi eşitliği kilitler; daha küçük istemci tavanı kayıtlı uzun metni sessizce keserdi). */
export const MAX_EMAILS = 50
export const MAX_INSTRUCTIONS = 2000
/** Sunucu sınırları (CONTRACT "Backend sapmaları"): ad ≤100 (harf duyarsız tekil), açıklama ≤500. */
export const MAX_GROUP_NAME = 100
export const MAX_GROUP_DESC = 500
/** Takım arama listesi tavanı (sunucu: en fazla 25 kişi). */
export const MAX_CALL_LIST = 25
/** "Takımsız" satırların süzgeç anahtarı (takım süzgeci takım kimliğiyle çalışır). */
export const NO_TEAM = '__none__'
/** Kapsam kutucukları (süzgeç) — `n_status`. */
export const STATUS_KEYS = ['covered', 'not_covered', 'paused']

/** Tür → izleme sekmesi (derin bağlantı `?tab=<sekme>&monitor=<id>`). SSL sertifika envanteridir: Pano araması. */
export const NOC_TYPE_TAB = {
  PING: 'ping', HTTP: 'http', KEYWORD: 'keyword', PAGE: 'page', PAGESPEED: 'pagespeed',
  SCRIPTED: 'scripted', DNS: 'dns', PORT: 'port', DOMAIN: 'domain',
}

/**
 * Sunucu yanıtını tek biçime indirir. Proje uçları `{ success, data }` zarfı döner; sözleşme yalnız VERİ biçimini
 * yazıyor — zarfsız bir yanıt da (ör. `{ summary, items }`) kabul edilir ki uç zarfı değiştirirse ekran düşmesin.
 * `request()` 401'de `null` döner.
 */
export function unwrap(res) {
  if (res == null) return { ok: false, data: null, error: null }
  if (typeof res === 'object' && !Array.isArray(res) && 'success' in res) {
    if (res.success === false) return { ok: false, data: null, error: res.error || res.message || null }
    return { ok: true, data: 'data' in res ? res.data : res, error: null }
  }
  return { ok: true, data: res, error: null }
}

// ── E-posta listesi ───────────────────────────────────────────────────────────────────────────

/**
 * Pratik e-posta doğrulaması: yerel kısım harf/rakam ve `._%+'-`, alan adı noktalı etiketler + en az iki harfli
 * uzantı. Ardışık/uçta nokta reddedilir. Gerçek teslim garantisi sunucuda (test e-postası).
 */
const EMAIL_RE = /^[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(?:[.][A-Za-z0-9-]+)*[.][A-Za-z]{2,}$/
export function isValidEmail(s) {
  const v = String(s ?? '').trim()
  if (!EMAIL_RE.test(v)) return false
  const [local, domain] = v.split('@')
  if (local.startsWith('.') || local.endsWith('.') || local.includes('..')) return false
  if (domain.split('.').some((label) => !label || label.startsWith('-') || label.endsWith('-'))) return false
  return true
}

/** Adresi karşılaştırma/saklama biçimine getirir: kırpılmış, küçük harf (e-posta adresleri harf duyarsız). */
export function normalizeEmail(s) {
  return String(s ?? '').trim().toLowerCase()
}

/**
 * Yapıştırılan metni adaylara böler. Ayırıcılar: virgül, noktalı virgül, satır sonu, sekme, boşluk.
 * Outlook biçimi (`"Soyad, Ad" <kisi-a@example.com>; Kişi B <kisi-b@example.com>`) desteklenir: görünen ad VİRGÜL
 * içerebildiği için önce tüm `<…>` adresleri (yerlerinde, sırayla) alınır, aradaki metin ayırıcılarla bölünür. Metinde
 * köşeli adres varsa `@` içermeyen parçalar görünen addır ("Soyad", "Ad") — aday sayılmaz (geçersiz çip üretip Kaydet'i
 * kilitlemesin); `@`'li çıplak adresler yine alınır. Adayların başındaki/sonundaki tırnak ve parantezler atılır.
 */
export function splitEmailText(text) {
  const src = String(text ?? '')
  const outlook = /<[^<>]*@[^<>]*>/.test(src)
  const out = []
  const loose = (chunk) => {
    for (const part of chunk.split(/[,;\n\r\t]+/)) {
      for (const w of part.trim().split(/\s+/)) {
        const c = w.replace(/^["'(<[]+|["')>\]]+$/g, '').trim()
        if (c && !(outlook && !c.includes('@'))) out.push(c)
      }
    }
  }
  let last = 0
  for (const m of src.matchAll(/<([^<>]+)>/g)) {
    loose(src.slice(last, m.index))
    const a = m[1].trim()
    if (a) out.push(a)
    last = m.index + m[0].length
  }
  loose(src.slice(last))
  return out
}

/**
 * Girdi metnini mevcut listeye katar: `{ added, invalid, duplicates }`.
 *  - `added`: geçerli ve YENİ adresler (normalize, girdi sırasıyla, kendi içinde tekil)
 *  - `invalid`: geçersiz adaylar (yazıldığı gibi — kullanıcı düzeltsin diye; tekil)
 *  - `duplicates`: listede zaten olan ya da girdide yinelenen geçerli adres sayısı
 */
export function parseEmailInput(text, existing = []) {
  const seen = new Set((existing || []).map(normalizeEmail))
  const added = []
  const invalid = []
  let duplicates = 0
  for (const raw of splitEmailText(text)) {
    if (!isValidEmail(raw)) {
      if (!invalid.includes(raw)) invalid.push(raw)
      continue
    }
    const n = normalizeEmail(raw)
    if (seen.has(n)) { duplicates++; continue }
    seen.add(n)
    added.push(n)
  }
  return { added, invalid, duplicates }
}

/** Grup formunun doğrulaması — hata anahtarları (i18n) alan başına; boş nesne = geçerli. */
export function validateGroup({ name, emails, invalid }, takenNames = []) {
  const errors = {}
  const n = String(name ?? '').trim()
  if (!n) errors.name = 'noc.errName'
  else if (n.length > MAX_GROUP_NAME) errors.name = 'noc.errNameLong'
  // Sunucu adı harf duyarsız tekil tutar — aynı adı kayıttan ÖNCE söyle (TR yerel küçük harf)
  else if ((takenNames || []).some((x) => String(x ?? '').trim().toLocaleLowerCase('tr') === n.toLocaleLowerCase('tr'))) errors.name = 'noc.errNameTaken'
  if ((invalid || []).length > 0) errors.emails = 'noc.errInvalidPending'
  else if (!(emails || []).length) errors.emails = 'noc.errNoEmail'
  else if (emails.length > MAX_EMAILS) errors.emails = 'noc.errTooMany'
  return errors
}

/** Grup listesi sırası: varsayılanlar önce, sonra aktifler, sonra ada göre (TR yerel). */
export function sortGroups(groups) {
  return [...(groups || [])].sort((a, b) =>
    (Number(!!b.is_default) - Number(!!a.is_default))
    || (Number(!!b.active) - Number(!!a.active))
    || String(a.name ?? '').localeCompare(String(b.name ?? ''), 'tr'))
}

/** Yapılandırma: eksik tür anahtarı AÇIK sayılır (sözleşme: tür kapatılmadıkça bildirim gider). */
export function normalizeConfig(d) {
  const src = d?.enabled_types || {}
  const enabled = {}
  for (const k of NOC_TYPES) enabled[k] = src[k] !== false
  return {
    enabled_types: enabled,
    min_level: NOC_LEVELS.includes(d?.min_level) ? d.min_level : DEFAULT_LEVEL,
    send_resolve: d?.send_resolve !== false,
    call_instructions: String(d?.call_instructions ?? ''),
  }
}

/**
 * PUT gövdesi (istek camelCase — proje kuralı). Talimat KESİLMEZ: tavanı aşan metin sunucuda 400 olur ve kullanıcı
 * hatayı görür (sessiz kırpma kayıtlı metnin sonunu habersiz silerdi); yazarken tavan Textarea `maxLength`'te.
 */
export function configBody(form) {
  return {
    enabledTypes: { ...form.enabled_types },
    minLevel: form.min_level,
    sendResolve: !!form.send_resolve,
    callInstructions: String(form.call_instructions ?? ''),
  }
}

/**
 * Test e-postası sonucu → { sent: sayı, failed: [adres] }. Sunucu `failed`'ı `[{ email, error }]` döner (sözleşmedeki düz
 * dize listesi de kabul); `sent` sayı, dizi ya da mantıksal gelebilir.
 */
export function testOutcome(data, emailCount = 0) {
  const failed = Array.isArray(data?.failed)
    ? data.failed.map((f) => (f && typeof f === 'object' ? String(f.email ?? '') : String(f ?? ''))).filter(Boolean)
    : []
  const s = data?.sent
  const sent = Array.isArray(s) ? s.length : typeof s === 'number' ? s : s === true ? Math.max(0, emailCount - failed.length) : 0
  // Genel e-posta kapalıyken sunucu her adresi `SKIPPED_DISABLED` ile başarısız sayar — ayrı, anlaşılır mesaj
  const raw = Array.isArray(data?.failed) ? data.failed : []
  const disabled = raw.length > 0 && raw.every((f) => f && typeof f === 'object' && f.error === 'SKIPPED_DISABLED')
  return { sent, failed, disabled }
}

// ── Kapsam ─────────────────────────────────────────────────────────────────────────────────────

/** Satır anahtarı — tür + kimlik (DNS/Port envanter türevli satırlar da kendi kimliğini taşır). */
export const itemKey = (it) => `${it.type}:${it.id}`

/** Satırın kutucuk durumu: kapsanan · duraklatılmış · kapsanmayan. */
export function statusOf(it) {
  if (it?.covered) return 'covered'
  if (it?.reason === 'PAUSED' || it?.active === false) return 'paused'
  return 'not_covered'
}

/**
 * Özet — ekrandaki TEK kaynak satırlardır (iyimser güncelleme sonrası kutucuklar ve tür çubukları satırlarla
 * tutarlı kalsın). Tür oranı yalnız aktif (duraklatılmamış) izlemeler üzerinden: duraklatılmış izleme kapsam
 * açığı sayılmaz.
 */
export function summarize(items) {
  const out = { total: 0, covered: 0, not_covered: 0, paused: 0, by_type: {} }
  for (const k of NOC_TYPES) out.by_type[k] = { total: 0, covered: 0, paused: 0 }
  for (const it of items || []) {
    out.total++
    const st = statusOf(it)
    out[st]++
    const bt = out.by_type[it.type] || (out.by_type[it.type] = { total: 0, covered: 0, paused: 0 })
    if (st === 'paused') bt.paused++
    else { bt.total++; if (st === 'covered') bt.covered++ }
  }
  return out
}

/** Tür kapsam oranının tonu (ProgressBar `tone`): tam → ok, yarıdan fazla → warn, altı → crit. */
export function coverageTone(covered, total) {
  if (!total) return undefined
  const r = covered / total
  return r >= 1 ? 'ok' : r >= 0.5 ? 'warn' : 'crit'
}

/** Takım süzgeci anahtarı. */
export const teamKeyOf = (it) => (it?.team_id != null ? String(it.team_id) : NO_TEAM)

/** Arama: ad, hedef, takım, grup adları (TR yerel küçük harf + düz küçük harf). */
function matchesQuery(it, q) {
  const n = q.trim().toLocaleLowerCase('tr')
  if (!n) return true
  const plain = q.trim().toLowerCase()
  return [it.name, it.target, it.team_name, ...(it.group_names || [])]
    .some((f) => f != null && (String(f).toLocaleLowerCase('tr').includes(n) || String(f).toLowerCase().includes(plain)))
}

/** Süzgeçler: arama · takım · tür · neden · kutucuk durumu. Boş dizi = o faset yok. */
export function applyFilters(items, { q = '', teams = [], types = [], reasons = [], status = null } = {}) {
  return (items || []).filter((it) =>
    matchesQuery(it, q)
    && (!teams.length || teams.includes(teamKeyOf(it)))
    && (!types.length || types.includes(it.type))
    && (!reasons.length || reasons.includes(it.reason))
    && (!status || statusOf(it) === status))
}

const STATUS_RANK = { not_covered: 0, paused: 1, covered: 2 }
/** Sıra: kapsanmayanlar önce (kullanıcının düzeltebileceği neden önce), sonra takım, tür, ad. */
export function sortItems(items) {
  const rank = (r) => { const i = NOC_REASONS.indexOf(r); return i < 0 ? NOC_REASONS.length : i }
  // Takımsız satırlar takımlıların SONUNA (boş ad localeCompare'de başa düşerdi).
  const team = (a, b) => (!a.team_name - !b.team_name) || String(a.team_name ?? '').localeCompare(String(b.team_name ?? ''), 'tr')
  return [...(items || [])].sort((a, b) =>
    (STATUS_RANK[statusOf(a)] - STATUS_RANK[statusOf(b)])
    || (rank(a.reason) - rank(b.reason))
    || team(a, b)
    || (NOC_TYPES.indexOf(a.type) - NOC_TYPES.indexOf(b.type))
    || String(a.name ?? '').localeCompare(String(b.name ?? ''), 'tr'))
}

/** Faset seçenekleri: { value, label, count } — sayı, ÖTEKİ süzgeçler uygulanmış kümeden. */
export function teamOptions(items, noTeamLabel, counted = items) {
  const labels = new Map()
  for (const it of items || []) labels.set(teamKeyOf(it), it.team_name || noTeamLabel)
  const counts = new Map()
  for (const it of counted || []) counts.set(teamKeyOf(it), (counts.get(teamKeyOf(it)) || 0) + 1)
  return [...labels.entries()]
    .map(([value, label]) => ({ value, label, count: counts.get(value) || 0 }))
    .sort((a, b) => (a.value === NO_TEAM) - (b.value === NO_TEAM) || a.label.localeCompare(b.label, 'tr'))
}
export function typeOptions(items, labelOf, counted = items) {
  const present = new Set((items || []).map((it) => it.type))
  const counts = new Map()
  for (const it of counted || []) counts.set(it.type, (counts.get(it.type) || 0) + 1)
  return NOC_TYPES.filter((k) => present.has(k)).map((k) => ({ value: k, label: labelOf(k), count: counts.get(k) || 0 }))
}
export function reasonOptions(items, labelOf, counted = items) {
  const present = new Set((items || []).map((it) => it.reason).filter(Boolean))
  const counts = new Map()
  for (const it of counted || []) if (it.reason) counts.set(it.reason, (counts.get(it.reason) || 0) + 1)
  return NOC_REASONS.filter((k) => present.has(k)).map((k) => ({ value: k, label: labelOf(k), count: counts.get(k) || 0 }))
}

/**
 * İyimser "7/24'e bildir" (ya da kapat): satır sunucu yanıtı gelene dek böyle görünür. Açarken yalnız
 * MONITOR_OFF engeli kalkar; tür kapalıysa ya da aktif grup yoksa neden ona döner (kapsanmaz).
 */
export function withNotify(it, enabled, { disabledTypes = [], activeGroups = 1 } = {}) {
  const next = { ...it, noc_notify: !!enabled }
  if (enabled) {
    if (it.reason === 'MONITOR_OFF' || (!it.reason && !it.covered)) {
      if (disabledTypes.includes(it.type)) { next.reason = 'TYPE_DISABLED'; next.covered = false }
      else if (!(activeGroups > 0)) { next.reason = 'NO_ACTIVE_GROUP'; next.covered = false }
      else { next.reason = null; next.covered = true }
    }
  } else if (it.covered || it.reason === null) {
    next.covered = false
    next.reason = it.active === false ? 'PAUSED' : 'MONITOR_OFF'
  }
  return next
}

/**
 * Satırı düzenleyebilir mi? Sunucu `can_edit` verirse o kazanır; yoksa rol + takım üyeliği (asıl kapı uçta —
 * yetkisiz yazma 403 döner ve iyimser güncelleme geri alınır). AUDIT yazamaz.
 */
export function canEditItem(it, { systemRole, globalAdmin, myTeamIds = [] } = {}) {
  if (typeof it?.can_edit === 'boolean') return it.can_edit
  if (globalAdmin || systemRole === 'ADMIN') return true
  // Yazma rolleri beyaz listesi: AUDIT (ve bilinmeyen rol) takım üyesi olsa da yazamaz
  if (systemRole !== 'TEAM_ADMIN' && systemRole !== 'USER') return false
  return it?.team_id != null && myTeamIds.some((id) => String(id) === String(it.team_id))
}

/** Satırın açılacağı yer: { tab, params } — SSL → Pano araması, Sentetik → yalnız sekme (derin bağlantı yok). */
export function openTargetOf(it) {
  if (!it) return null
  // SSL: Pano araması ALAN ADIYLA (`name`) — `target` 443 dışı portta `host:8443` olur ve aramada eşleşmez
  if (it.type === 'SSL') return { tab: 'dashboard', params: { domain: it.name || String(it.target || '').replace(/:[0-9]+$/, '') } }
  const tab = NOC_TYPE_TAB[it.type]
  if (!tab) return null
  return tab === 'scripted' ? { tab, params: undefined } : { tab, params: { monitor: it.id } }
}

/** Pano şeridi sayısı: aktif ama kapsanmayan izlemeler (satırlar varsa satırlardan, yoksa özetten). */
export function uncoveredActiveCount(data) {
  if (Array.isArray(data?.items)) return data.items.filter((it) => statusOf(it) === 'not_covered').length
  const n = Number(data?.summary?.not_covered)
  return Number.isFinite(n) && n > 0 ? n : 0
}

// ── Arama listesi ─────────────────────────────────────────────────────────────────────────────

/** Sıralı listede bir öğeyi yukarı/aşağı taşır (sınırda değişmez; yeni dizi). */
export function moveItem(list, index, delta) {
  const j = index + delta
  if (index < 0 || index >= list.length || j < 0 || j >= list.length) return list
  const next = [...list]
  const [x] = next.splice(index, 1)
  next.splice(j, 0, x)
  return next
}

/**
 * Arama listesini düzenleyebilir mi? Sunucu kuralının (NocController.canEditCallList) istemci tahmini:
 *  - YALNIZ global yönetici her takımı düzenler. Rol ADMIN tek başına yetmez: kapsamlı müdür (rol ADMIN, global değil)
 *    sunucuda `canManage` = yönetim kapsamı; her takıma "düzenle" gösterip 403 almasın.
 *  - Takım lideri ya da takıma elle atanmış müdür (Team.managerId — takım rehberindeki `manager_id`).
 *  - TEAM_ADMIN ve kapsamlı müdür: yönetim listesi istemcide yok → üyesi olduğu takım (en iyi tahmin). Asıl kapı
 *    sunucuda; 403 gelirse kart salt okunura döner ve nedenini yazar (NocCallListCard).
 */
export function canEditCallList(teamId, { systemRole, globalAdmin, myTeamIds = [], userId = null, leaderId = null, managerId = null } = {}) {
  if (teamId == null) return false
  if (globalAdmin) return true
  if (userId != null && [leaderId, managerId].some((x) => x != null && String(x) === String(userId))) return true
  return (systemRole === 'TEAM_ADMIN' || systemRole === 'ADMIN') && myTeamIds.some((id) => String(id) === String(teamId))
}

/** Toplu atlama nedenleri (sunucu): INVALID | NOT_FOUND | FORBIDDEN | UNCHANGED. */
export const BULK_SKIP_REASONS = ['FORBIDDEN', 'NOT_FOUND', 'INVALID', 'UNCHANGED']

/**
 * Toplu atlananları özetler: `rollback` = iyimser hâli geri alınacak satır anahtarları (UNCHANGED hariç — sunucuda
 * zaten açık, iyimser hâl doğru), `counts` = neden → adet (bilinmeyen neden INVALID sayılır), `failed` = gerçek
 * başarısızlık adedi (UNCHANGED hariç).
 */
export function summarizeSkipped(skipped) {
  const rollback = new Set()
  const counts = {}
  let failed = 0
  for (const x of skipped || []) {
    const reason = BULK_SKIP_REASONS.includes(x?.reason) ? x.reason : 'INVALID'
    counts[reason] = (counts[reason] || 0) + 1
    if (reason === 'UNCHANGED') continue
    failed++
    rollback.add(`${x?.type}:${x?.id}`)
  }
  return { rollback, counts, failed }
}
