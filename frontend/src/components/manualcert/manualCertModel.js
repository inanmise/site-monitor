import { certTone, dateOnly } from '../certcard/certCardModel.js'
import { toUtc } from '../../utils/localDay.js'
import { emptyFlags } from '../../utils/inventoryFlags.js'
import { expiredAgoText, expiresInText } from '../../utils/dayPhrases.js'
import { nocGroupIdsBody } from '../noc/forms/nocFormModel.js'

/**
 * MANUEL SERTİFİKA (dosyadan yüklenen) — saf model (2026-10-06, kullanıcı isteği: ağ üzerinden erişilemeyen sertifikalar
 * dosyadan yüklenir, süresi ağdakilerle AYNI kurallarla izlenir). React yok: sayfa, yükleme sihirbazı, sürümler sekmesi,
 * rozetler ve testler aynı sözlüğü paylaşır.
 *
 * <p>Sözleşme (backend ile ortak): `/api/manual-certs` — analiz (yazmaz), oluştur, toplu oluştur, yeni sürüm, liste,
 * ayrıntı, PEM indirme, yeniden değerlendirme. Envanter satırı `cert_source === 'MANUAL'` taşır; ağ satırlarında alan
 * yoktur (null) ve hiçbir davranış değişmez. Özel anahtar ve şifre ASLA saklanmaz, loglanmaz, yanıtta dönmez — istemci de
 * şifreyi yalnız sihirbaz açıkken bellekte tutar.
 */

export const CERT_SOURCE_MANUAL = 'MANUAL'

/** Satır dosyadan yüklenmiş bir sertifika mı (envanter / sertifika / liste satırı). */
export const isManualCert = (row) => String(row?.cert_source ?? '').toUpperCase() === CERT_SOURCE_MANUAL

/** Ağ tabanlı ekranlar (Durum İzleme) manuel satırları göstermez — ağ satırları olduğu gibi kalır. */
export const withoutManualCerts = (list) => (Array.isArray(list) ? list.filter((r) => !isManualCert(r)) : list)

// ── Yükleme sınırları (sunucuyla aynı) ───────────────────────────────────────────────────────────────────────────
export const MAX_UPLOAD_MB = 5
export const MAX_UPLOAD_BYTES = MAX_UPLOAD_MB * 1024 * 1024
export const MAX_BATCH = 20
export const NOTE_MAX = 500

/** Dosya seçicinin kabul listesi — sunucu içeriğe bakar; uzantı yalnız ipucu. */
export const ACCEPT_EXTENSIONS = Object.freeze(['.pem', '.crt', '.cer', '.der', '.p7b', '.p7c', '.pfx', '.p12', '.jks', '.jceks', '.bks', '.zip', '.txt'])
export const ACCEPT_ATTR = ACCEPT_EXTENSIONS.join(',')
const PASSWORD_EXT = new Set(['pfx', 'p12', 'jks', 'jceks', 'bks'])
const DISCOURAGED_EXT = new Set(['csr', 'key'])

export function fileExtension(name) {
  const m = /\.([a-z0-9]+)$/i.exec(String(name ?? ''))
  return m ? m[1].toLowerCase() : ''
}
/** Şifre alanı baştan gösterilsin mi (PFX/P12/JKS/JCEKS/BKS). */
export const passwordLikely = (name) => PASSWORD_EXT.has(fileExtension(name))
export const knownExtension = (name) => ACCEPT_EXTENSIONS.includes(`.${fileExtension(name)}`)
/** CSR ya da tek başına özel anahtar — yükleme öncesi uyarılır (sertifika değiller). */
export const discouragedExtension = (name) => DISCOURAGED_EXT.has(fileExtension(name))

/** Kalan gün metni ("25 gün içinde" / "3 gün önce doldu" / "Bugün doluyor"); bilinmiyorsa "—". */
export function daysText(t, d) {
  if (d == null || d === '' || !Number.isFinite(Number(d))) return '—'
  const n = Number(d)
  if (n < 0) return expiredAgoText(t, -n)
  if (n === 0) return t('inv.expiresToday')
  return expiresInText(t, n)
}

/** "1,2 MB" / "640 KB" — dosya çipi için kısa boyut. */
export function sizeLabel(bytes) {
  const n = Number(bytes)
  if (!Number.isFinite(n) || n < 0) return ''
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${Math.round(n / 1024)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
}

// ── Takip adı (envanter anahtarı) ────────────────────────────────────────────────────────────────────────────────
/** Sunucudaki doğrulayıcıyla AYNI desen: küçük harf, boşluk / `:` / `/` / `@` yok, başı ve sonu harf ya da rakam. */
export const TRACKING_KEY_RE = /^(\*\.)?[a-z0-9]([a-z0-9._-]{0,251}[a-z0-9])?$/

/** Takip adı hatası → i18n anahtarı (geçerliyse null). Mesaj nedeni söyler (yalnız "geçersiz" değil). */
export function trackingKeyError(key) {
  const s = String(key ?? '')
  if (!s.trim()) return 'mcert.key.required'
  if (/\s/.test(s)) return 'mcert.key.spaces'
  if (s !== s.toLowerCase()) return 'mcert.key.lowercase'
  if (/[:/@]/.test(s)) return 'mcert.key.chars'
  if (s.length > 253) return 'mcert.key.length'
  if (!TRACKING_KEY_RE.test(s)) return 'mcert.key.invalid'
  return null
}

// ── Uyarı kodları (sunucu `{code, severity, params}` döner; metin `mcert.warn.<CODE>`) ─────────────────────────
export const WARNING_CODES = Object.freeze([
  'PRIVATE_KEY_IGNORED', 'CSR_NOT_CERTIFICATE', 'NO_CERTIFICATE', 'PASSWORD_REQUIRED', 'PASSWORD_WRONG',
  'UNSUPPORTED_FORMAT', 'FILE_TOO_LARGE', 'ZIP_LIMIT', 'ZIP_SKIPPED_ENTRY', 'EXPIRED', 'NOT_YET_VALID',
  'EXPIRES_SOON', 'SELF_SIGNED', 'CHAIN_INCOMPLETE', 'CHAIN_EXPIRED_INTERMEDIATE', 'WEAK_SIGNATURE', 'WEAK_KEY',
  'CA_CERTIFICATE', 'MULTIPLE_LEAVES', 'DUPLICATE_IN_FILE', 'ALREADY_TRACKED', 'SAME_SUBJECT_TRACKED',
  'NETWORK_MONITORED', 'KEY_CHANGED', 'KEY_SAME', 'SUBJECT_CHANGED', 'OLDER_THAN_CURRENT', 'SAN_CHANGED',
])

/** Önem → AlertBanner / ikon tonu. Bilinmeyen önem "info" sayılır. */
export const SEVERITY_TONE = Object.freeze({ info: 'info', warn: 'warning', error: 'danger' })
export const severityTone = (sev) => SEVERITY_TONE[String(sev ?? '').toLowerCase()] ?? 'info'
const SEVERITY_RANK = { error: 0, warn: 1, info: 2 }
/** Ağır olan önce (hata → uyarı → bilgi), aynı önemde sunucu sırası. */
export function sortWarnings(list) {
  return (Array.isArray(list) ? list : [])
    .map((w, i) => ({ w, i }))
    .sort((a, b) => ((SEVERITY_RANK[a.w?.severity] ?? 3) - (SEVERITY_RANK[b.w?.severity] ?? 3)) || a.i - b.i)
    .map(({ w }) => w)
}

/** Adlı yer tutucuları (`{count}`, `{domain}` …) doldurur; tarih parametreleri yerel tarih, boş değer "—". */
export function fillParams(text, params) {
  let s = String(text ?? '')
  if (!params || typeof params !== 'object') return s
  for (const [k, v] of Object.entries(params)) {
    let val = v == null || v === '' ? '—' : Array.isArray(v) ? (v.length ? v.join(', ') : '—') : String(v)
    if (/date/i.test(k) && val !== '—') val = dateOnly(val)
    s = s.split(`{${k}}`).join(val)
  }
  return s
}

/** Uyarının kullanıcı metni — sözlükte yoksa ham anahtar yerine kodlu genel metin. */
export function warningText(t, w) {
  const code = String(w?.code ?? '')
  const key = `mcert.warn.${code}`
  const raw = code ? t(key) : key
  if (!code || raw === key) return t('mcert.warnUnknown', code || '—')
  return fillParams(raw, w.params)
}

// ── Analiz yanıtı ────────────────────────────────────────────────────────────────────────────────────────────────
/** Girdinin başlığı: CN → konu → konu DN → parmak izi kısaltması. */
export const entryTitle = (e) => e?.cn || e?.subject || e?.subject_dn || String(e?.ref ?? '').slice(0, 16)

/** Analiz girdisinin durumu → kart tonu (Genel Bakış kartıyla aynı aile). */
export function entryTone(e) {
  switch (e?.status) {
    case 'expired': return 'expired'
    case 'not_yet_valid': return 'warning'
    case 'warning': return 'warning'
    default: return 'valid'
  }
}

/** Varsayılan seçim: sunucunun önerisi → tek girdi → ilk girdi. */
export function defaultRef(analysis) {
  const entries = Array.isArray(analysis?.entries) ? analysis.entries : []
  if (!entries.length) return null
  if (analysis.default_ref && entries.some((e) => e.ref === analysis.default_ref)) return analysis.default_ref
  return entries[0].ref
}

/** Birden çok CA girdisi (truststore) — çoklu seçim önerilir. */
export function looksLikeTruststore(analysis) {
  const entries = Array.isArray(analysis?.entries) ? analysis.entries : []
  return entries.length > 1 && entries.every((e) => e.is_ca && !e.is_key_entry)
}

/** Sunucu damgası (UTC, eki olmayabilir) → ms; boş / bozuk → NaN. */
const ms = (iso) => (iso ? Date.parse(toUtc(String(iso))) : NaN)

/** Kalan geçerlilik (ProgressBar için): toplam ve kalan gün; hesaplanamazsa null. */
export function validitySpan(e) {
  const start = ms(e?.not_before)
  const end = ms(e?.not_after)
  const d = Number(e?.days_remaining)
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start || !Number.isFinite(d)) return null
  const total = Math.max(1, Math.round((end - start) / 86400000))
  return { total, remaining: Math.max(0, Math.min(total, d)) }
}

// ── Yeni sürüm karşılaştırması ───────────────────────────────────────────────────────────────────────────────────
const lowerSet = (list) => new Set((Array.isArray(list) ? list : []).map((s) => String(s).toLowerCase()))

/**
 * Güncel sürüm ↔ yüklenen girdi. `older`: yeni bitiş ≤ güncel bitiş (sunucu onay ister — OLDER_THAN_CURRENT);
 * `same`: aynı parmak izi (sunucu reddeder — SAME_CERTIFICATE). Anahtar değişimi kesin olarak sunucuda (açık anahtar
 * özeti) belirlenir; burada yalnız algoritma/boyut farkı gösterilir.
 */
export function compareWithCurrent(current, entry) {
  if (!current || !entry) return null
  const oldEnd = ms(current.not_after)
  const newEnd = ms(entry.not_after)
  const oldSan = lowerSet(current.san)
  const newSan = lowerSet(entry.san)
  const keyOld = [current.key_alg, current.key_size].filter(Boolean).join(' ')
  const keyNew = [entry.key_alg, entry.key_size].filter(Boolean).join(' ')
  return {
    same: !!current.fingerprint && !!entry.ref && String(current.fingerprint).toUpperCase() === String(entry.ref).toUpperCase(),
    older: Number.isFinite(oldEnd) && Number.isFinite(newEnd) && newEnd <= oldEnd,
    issuerChanged: (current.issuer || '') !== (entry.issuer || ''),
    subjectChanged: (current.subject || '') !== (entry.subject || ''),
    keyOld, keyNew, keyAlgChanged: !!keyOld && !!keyNew && keyOld !== keyNew,
    sanAdded: [...newSan].filter((s) => !oldSan.has(s)),
    sanRemoved: [...oldSan].filter((s) => !newSan.has(s)),
  }
}

// ── Liste (sayfa) ────────────────────────────────────────────────────────────────────────────────────────────────
/** Satır tonu — Genel Bakış kartının hükmü (sunucunun `alert_level`'ı; yoksa gün eşikleri). */
export const rowTone = (row) => certTone(row)

const PROBLEM_RANK = { expired: 0, error: 0, critical: 1, high: 2, warning: 3, valid: 4 }
const COLLATOR = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })
const daysOf = (r) => (r?.days_remaining == null || !Number.isFinite(Number(r.days_remaining)) ? Infinity : Number(r.days_remaining))

/** Varsayılan sıra: sorunlular önce (dolmuş → kritik → yüksek → uyarı), sonra en yakın bitiş, sonra takip adı. */
export function sortManualRows(rows) {
  return [...(Array.isArray(rows) ? rows : [])].sort((a, b) =>
    ((PROBLEM_RANK[rowTone(a)] ?? 4) - (PROBLEM_RANK[rowTone(b)] ?? 4))
    || (daysOf(a) - daysOf(b))
    || COLLATOR.compare(String(a?.domain ?? ''), String(b?.domain ?? '')))
}

export const STATUS_FILTERS = Object.freeze(['all', 'problem', 'healthy', 'expiring', 'expired', 'renewed'])

export function matchesStatus(row, status) {
  const tone = rowTone(row)
  const d = daysOf(row)
  switch (status) {
    case 'problem': return tone !== 'valid'
    case 'healthy': return tone === 'valid'
    case 'expiring': return d >= 0 && d <= 30
    case 'expired': return tone === 'expired' || d < 0
    case 'renewed': return Number(row?.versions_count) > 1
    default: return true
  }
}

export const NO_TEAM = '__none__'

export function matchesTeam(row, team) {
  if (!team || team === 'all') return true
  if (team === NO_TEAM) return row?.team_id == null
  return String(row?.team_id ?? '') === String(team)
}

export function matchesQuery(row, q) {
  const s = String(q ?? '').trim().toLowerCase()
  if (!s) return true
  return [row?.domain, row?.subject, row?.issuer, row?.team_name, row?.group_name, row?.tags, row?.current_version?.file_name]
    .some((v) => v != null && String(v).toLowerCase().includes(s))
}

export function filterManualRows(rows, { q = '', status = 'all', team = 'all' } = {}) {
  return (Array.isArray(rows) ? rows : []).filter((r) => matchesQuery(r, q) && matchesStatus(r, status) && matchesTeam(r, team))
}

/** Özet kutuları: toplam · sağlıklı · 30 gün içinde · dolmuş · saklanan sürüm. */
export function manualKpis(rows) {
  const list = Array.isArray(rows) ? rows : []
  return {
    total: list.length,
    healthy: list.filter((r) => matchesStatus(r, 'healthy')).length,
    expiring: list.filter((r) => matchesStatus(r, 'expiring')).length,
    expired: list.filter((r) => matchesStatus(r, 'expired')).length,
    versions: list.reduce((n, r) => n + (Number(r?.versions_count) > 0 ? Number(r.versions_count) : 1), 0),
  }
}

/** Takım süzgeci seçenekleri (ad sırasıyla; takımsız satır varsa sonda). */
export function teamFilterOptions(rows) {
  const map = new Map()
  let none = false
  for (const r of Array.isArray(rows) ? rows : []) {
    if (r?.team_id == null) none = true
    else if (!map.has(String(r.team_id))) map.set(String(r.team_id), r.team_name || String(r.team_id))
  }
  const opts = [...map.entries()].map(([value, label]) => ({ value, label })).sort((a, b) => COLLATOR.compare(a.label, b.label))
  return { options: opts, hasNone: none }
}

// ── İstek gövdeleri ──────────────────────────────────────────────────────────────────────────────────────────────
/**
 * Çok parçalı yükleme gövdesi: dosya YA DA yapıştırılan metin + (varsa) şifre + ek alanlar (nesne → JSON). Şifre
 * yalnız gövdeye girer; hiçbir yere yazılmaz.
 */
export function uploadFormData({ source = 'file', file = null, text = '', password = '' } = {}, extra = {}) {
  const fd = new FormData()
  if (source === 'text') {
    if (String(text).trim()) fd.append('text', String(text))
  } else if (file) {
    fd.append('file', file, file.name)
  }
  if (password) fd.append('password', password)
  for (const [k, v] of Object.entries(extra || {})) {
    if (v === undefined || v === null) continue
    fd.append(k, typeof v === 'object' ? JSON.stringify(v) : String(v))
  }
  return fd
}

/** Takip alanlarının boş durumu (sihirbaz "Yeni takip kaydı"). */
export const EMPTY_TRACKING = Object.freeze({
  team_id: '', group_name: '', tags: '', tier: null, description: '', owner: '', platform: '', platform_detail: '',
  notification_group_id: '', noc_notify: false, noc_group_ids: [],
})

/**
 * `inventory` alanı — envanter ekleme ucuyla (POST /api/admin/inventory) AYNI snake_case gövde. Ağa özgü alanlar
 * (port / vekil / TLS kipi / zaman aşımı / sıklık / beklenen parmak izi) manuel kayıtta anlamsız: varsayılanlarıyla
 * gider. Takip adı gövdede DEĞİL, ayrı `domain` alanında (toplu yüklemede satır başına).
 */
export function inventoryPayload(f) {
  const s = (v) => (v == null ? '' : String(v)).trim()
  return {
    port: 443,
    owner: s(f.owner),
    description: s(f.description),
    active: true,
    team_id: f.team_id ? Number(f.team_id) : null,
    notification_group_id: f.notification_group_id ? Number(f.notification_group_id) : null,
    noc_notify: !!f.noc_notify,
    noc_group_ids: nocGroupIdsBody(f.noc_group_ids),
    group_name: s(f.group_name) || null,
    tags: s(f.tags) || null,
    ug_team_id: null,
    ...emptyFlags(),
    use_proxy: false,
    tls_mode: null,
    timeout_seconds: null,
    check_interval_hours: null,
    purchased_by: null,
    platform: f.platform || null,
    platform_detail: s(f.platform_detail) || null,
    svc_mgmt_contact: null, app_dev_contact: null, iis_admin_contact: null, waf_admin_contact: null,
    change_description: null,
    expected_fingerprint: null,
    expected_subject: null,
    tier: f.tier ? Number(f.tier) : null,
  }
}

/** Takip alanlarının istemci doğrulaması (envanter ekleme kuralı: takım + grup + etiket zorunlu). */
export function trackingErrors(f, t) {
  return {
    team_id: !f.team_id && t('inv.teamRequired'),
    group_name: !String(f.group_name ?? '').trim() && t('inv.groupRequired'),
    tags: !String(f.tags ?? '').trim() && t('inv.tagsRequired'),
  }
}

/**
 * Sunucunun 400 `errors` haritası → { alanlar, satırlar, kalan }. Toplu yüklemede `items[2].domain` / `items.2.domain`
 * biçimi satıra eşlenir; tanınmayan alanlar `rest`te kalır (pencere bandında gösterilir).
 */
const FIELD_KEYS = new Set(['domain', 'team_id', 'group_name', 'tags', 'tier', 'description', 'owner', 'platform', 'platform_detail', 'notification_group_id', 'note', 'password', 'file', 'text'])
export function splitServerErrors(errors) {
  const fields = {}; const rows = {}; const rest = []
  for (const [k, v] of Object.entries(errors && typeof errors === 'object' ? errors : {})) {
    const msg = String(v ?? '')
    const m = /^items(?:\[(\d+)\]|\.(\d+))\.(\w+)$/.exec(k)
    const plain = k.replace(/^inventory\./, '')
    if (m) rows[Number(m[1] ?? m[2])] = msg
    else if (FIELD_KEYS.has(plain)) fields[plain] = msg
    else rest.push(msg)
  }
  return { fields, rows, rest }
}

/** Dosya indirme (PEM) — aynı köken, oturum çerezi gider; yeni sekme açılmaz. */
export function downloadFromUrl(url) {
  try {
    const a = document.createElement('a')
    a.href = url
    a.rel = 'noopener'
    a.setAttribute('download', '')
    document.body.appendChild(a)
    a.click()
    a.remove()
  } catch { /* tarayıcı dışı ortam */ }
}
