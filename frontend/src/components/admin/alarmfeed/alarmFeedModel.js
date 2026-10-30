import { changedFields as diffFields } from '../../../utils/changedFields.js'
import { PAGINATION_PRESETS } from '../../../hooks/paginationPresets.js'

/**
 * Ayarlar → Platform → Alarm akışı — saf model (2026-10-30).
 *
 * Merkezi izleme ekibi SiteMonitor'ün alarmlarını herkese açık (jetonsuz) `GET /api/public/alarm-feed` ucundan dakikada bir
 * çeker; bu sayfa ucun ayarlarını, önizlemesini ve çağrı günlüğünü yönetir. Sunucu sözleşmesi `/api/admin/alarm-feed`
 * (yalnız global yönetici). Bilinmeyen / okunamayan değer hiçbir zaman "yeşil" ya da 0 sayılmaz ("—").
 */

/** Asgari seviye seçenekleri (sunucu sırası). */
export const LEVELS = ['WARNING', 'HIGH', 'CRITICAL']
/** Çözülen alarmın gösterimi: SEVERITY aynı + STATUS kolonu (önerilen) ya da SEVERITY = clear_text. */
export const RESOLVED_MODES = ['status', 'clear']
/** RCI: hedef + izleme türü ya da yalnız hedef. */
export const RCI_MODES = ['target_type', 'target']
/** MAILS ayırıcısı. */
export const MAIL_SEPARATORS = ['semicolon', 'comma']
/** Çağrı sonuçları (günlük süzgeci + rozet). */
export const OUTCOMES = ['OK', 'DISABLED', 'IP_DENIED', 'RATE_LIMITED', 'BLOCKED', 'ERROR']
/** Sonuç → ToneBadge tonu. Yalnız gerçek OK yeşildir. */
export const OUTCOME_TONE = {
  OK: 'success', DISABLED: 'muted', IP_DENIED: 'warning', RATE_LIMITED: 'warning', BLOCKED: 'danger', ERROR: 'danger',
}
/** Akışın döndüğü kolonlar — önizleme sunucunun `columns`'unu kullanır; yoksa bu sıra. */
export const FEED_COLUMNS = ['CREATEDTIME', 'SEVERITY', 'RCI', 'RCITYPE', 'CATEGORY', 'FILTERSOURCE', 'TEXT', 'BODY', 'MAILS',
  'STATUS', 'RESOLVEDTIME', 'ALERTID', 'TEAM', 'UGTEAM', 'LINK']
/** Sunucu `limits` göndermezse (eski pod) kullanılan sınırlar — sunucuyla aynı. */
export const DEFAULT_LIMITS = Object.freeze({
  resolved_minutes: [1, 1440], rate_per_minute: [1, 600], max_rows: [100, 10000], text_max: 60, allowed_ips_max: 100,
  block_threshold: [10, 6000], block_minutes: [1, 1440],
})

/** Etkin akışta son çağrı bundan eskiyse "arayan sessiz" uyarısı. */
export const SILENT_AFTER_MS = 5 * 60_000
/** Durum kartının sessiz yenilemesi (form taslağına dokunmaz). */
export const STATUS_REFRESH_MS = 60_000
/** Liste kabı bu genişlikten itibaren tablo, altında kart (useElementWidth; jsdom 0 → tablo). */
export const PREVIEW_TABLE_MIN_PX = 900
export const REQUESTS_TABLE_MIN_PX = 900
export const CALLERS_TABLE_MIN_PX = 760
/** Engellenen IP'ler tablosu — kap genişliği bundan darsa kartlar. */
export const BLOCKS_TABLE_MIN_PX = 760
/** Çağrı günlüğü sayfa boyutları — sunucu sayfayı 100 ile sınırlar (panel ön ayarından ≤ 100). */
export const REQUEST_SIZE_OPTIONS = Object.freeze(PAGINATION_PRESETS.panel.sizeOptions.filter((n) => n <= 100))
/** IP süzgeci gecikmesi (ms). */
export const IP_FILTER_DEBOUNCE_MS = 300

/** Form alanları — `settings` ile aynı adlar (kayıt gövdesi yalnız DEĞİŞENLERİ taşır). */
export const FORM_FIELDS = [
  'enabled', 'min_level', 'team_ids', 'types', 'resolved_minutes', 'max_rows', 'resolved_mode', 'clear_text',
  'severity_critical', 'severity_high', 'severity_warning', 'rci_mode', 'source', 'rcitype', 'mail_separator',
  'allowed_ips', 'rate_per_minute', 'store_body', 'block_enabled', 'block_threshold', 'block_minutes', 'block_escalate',
  'block_exempt_ips',
]
const INT_FIELDS = ['resolved_minutes', 'rate_per_minute', 'max_rows', 'block_threshold', 'block_minutes']
/** 1..text_max karakter zorunlu metinler. */
const TEXT_FIELDS = ['clear_text', 'severity_critical', 'severity_high', 'severity_warning', 'source']
const ENUMS = { min_level: LEVELS, resolved_mode: RESOLVED_MODES, rci_mode: RCI_MODES, mail_separator: MAIL_SEPARATORS }
/** Bir IP / CIDR girişinin üst sınırı (doğrulama öncesi — doğrusal kalır). */
const MAX_IP_CHARS = 64

// ── Normalleştirme ──────────────────────────────────────────────────────────────────────────────

/** Benzersiz, pozitif tamsayı kimlikler, artan sıra. */
export function normalizeIds(list) {
  const out = new Set()
  for (const v of Array.isArray(list) ? list : []) {
    const n = Number(v)
    if (Number.isInteger(n) && n > 0) out.add(n)
  }
  return [...out].sort((a, b) => a - b)
}

/** Benzersiz tür anahtarları, alfabetik (karşılaştırma için kararlı sıra). */
export function normalizeTypes(list) {
  const out = new Set()
  for (const v of Array.isArray(list) ? list : []) {
    const s = String(v ?? '').trim()
    if (s) out.add(s)
  }
  return [...out].sort()
}

/** IP / CIDR girişi: kırpılmış, küçük harf (IPv6 onaltılıları). */
export function normalizeIp(raw) {
  return String(raw ?? '').trim().toLowerCase()
}

/** Benzersiz IP listesi (giriş sırası korunur). */
export function normalizeIps(list) {
  const out = []
  for (const v of Array.isArray(list) ? list : []) {
    const s = normalizeIp(v)
    if (s && !out.includes(s)) out.push(s)
  }
  return out
}

const text = (v) => (v == null ? '' : String(v))
const numField = (v) => (v == null || v === '' ? '' : String(v))

/** Sunucu `settings` → form (sayılar metin olarak tutulur — yazarken geçici geçersiz değer kaybolmasın). */
export function toForm(settings) {
  const s = settings || {}
  return {
    enabled: s.enabled === true,
    min_level: text(s.min_level),
    team_ids: normalizeIds(s.team_ids),
    types: normalizeTypes(s.types),
    resolved_minutes: numField(s.resolved_minutes),
    max_rows: numField(s.max_rows),
    resolved_mode: text(s.resolved_mode),
    clear_text: text(s.clear_text),
    severity_critical: text(s.severity_critical),
    severity_high: text(s.severity_high),
    severity_warning: text(s.severity_warning),
    rci_mode: text(s.rci_mode),
    source: text(s.source),
    rcitype: text(s.rcitype),
    mail_separator: text(s.mail_separator),
    allowed_ips: normalizeIps(s.allowed_ips),
    rate_per_minute: numField(s.rate_per_minute),
    store_body: s.store_body === true,
    // Eski sunucu alanı göndermezse açık say (sunucu varsayılanı) — gönderilmeyen alan değişmiş sayılmaz (bodyOf aynı kuralı uygular)
    block_enabled: s.block_enabled !== false,
    block_threshold: numField(s.block_threshold),
    block_minutes: numField(s.block_minutes),
    block_escalate: s.block_escalate !== false,
    block_exempt_ips: normalizeIps(s.block_exempt_ips),
  }
}

/** Metin → tamsayı; tamsayı değilse kırpılmış metin döner (doğrulama yakalar, gönderilmez). */
function toInt(v) {
  const s = String(v ?? '').trim()
  return /^\d{1,9}$/.test(s) ? Number(s) : s
}

/** Form → sunucu gövdesi biçimi (tüm alanlar, tipli). */
export function bodyOf(form) {
  const f = form || {}
  const out = {
    enabled: f.enabled === true,
    min_level: text(f.min_level).trim(),
    team_ids: normalizeIds(f.team_ids),
    types: normalizeTypes(f.types),
    resolved_mode: text(f.resolved_mode).trim(),
    rci_mode: text(f.rci_mode).trim(),
    mail_separator: text(f.mail_separator).trim(),
    allowed_ips: normalizeIps(f.allowed_ips),
    store_body: f.store_body === true,
    block_enabled: f.block_enabled !== false,
    block_escalate: f.block_escalate !== false,
    block_exempt_ips: normalizeIps(f.block_exempt_ips),
  }
  for (const k of INT_FIELDS) out[k] = toInt(f[k])
  for (const k of [...TEXT_FIELDS, 'rcitype']) out[k] = text(f[k]).trim()
  return out
}

/** Yalnız değişen alanlar — kayıt gövdesi. Değişiklik yoksa `{}` (istek gönderilmez). */
export function changedSettings(saved, form) {
  if (!saved || !form) return {}
  return diffFields(bodyOf(form), bodyOf(saved))
}

export const isDirty = (saved, form) => Object.keys(changedSettings(saved, form)).length > 0

/** Sunucu sınırları (eski pod göndermezse varsayılanlar). */
export function limitsOf(data) {
  const l = data?.limits || {}
  const pair = (k) => (Array.isArray(l[k]) && l[k].length === 2 && l[k].every(Number.isFinite) ? l[k] : DEFAULT_LIMITS[k])
  return {
    resolved_minutes: pair('resolved_minutes'),
    rate_per_minute: pair('rate_per_minute'),
    max_rows: pair('max_rows'),
    block_threshold: pair('block_threshold'),
    block_minutes: pair('block_minutes'),
    text_max: Number.isInteger(l.text_max) && l.text_max > 0 ? l.text_max : DEFAULT_LIMITS.text_max,
    allowed_ips_max: Number.isInteger(l.allowed_ips_max) && l.allowed_ips_max > 0 ? l.allowed_ips_max : DEFAULT_LIMITS.allowed_ips_max,
  }
}

// ── IP / CIDR ───────────────────────────────────────────────────────────────────────────────────

/** IPv4 noktalı ondalık; baştaki sıfır kabul edilmez ("010" sekizlik sanılabilir). */
export function isValidIpv4(s) {
  if (typeof s !== 'string' || s.length > 15) return false
  const parts = s.split('.')
  if (parts.length !== 4) return false
  return parts.every((p) => /^\d{1,3}$/.test(p) && Number(p) <= 255 && !(p.length > 1 && p[0] === '0'))
}

const HEX_GROUP = /^[0-9a-f]{1,4}$/i

/** IPv6 metni (kısaltma `::` ve sonda gömülü IPv4 dahil; bölge kimliği `%` kabul edilmez). Doğrusal. */
export function isValidIpv6(s) {
  if (typeof s !== 'string' || s.length < 2 || s.length > 45 || !s.includes(':')) return false
  let str = s
  const lastColon = str.lastIndexOf(':')
  const tail = str.slice(lastColon + 1)
  if (tail.includes('.')) {
    if (!isValidIpv4(tail)) return false
    str = `${str.slice(0, lastColon + 1)}0:0`   // gömülü IPv4 = iki onaltılı grup
  }
  const dbl = str.indexOf('::')
  if (dbl !== str.lastIndexOf('::')) return false   // birden çok "::" (ya da ":::")
  if (dbl >= 0) {
    const head = str.slice(0, dbl)
    const rest = str.slice(dbl + 2)
    const hg = head ? head.split(':') : []
    const tg = rest ? rest.split(':') : []
    if (hg.length + tg.length > 7) return false
    return [...hg, ...tg].every((g) => HEX_GROUP.test(g))
  }
  const groups = str.split(':')
  return groups.length === 8 && groups.every((g) => HEX_GROUP.test(g))
}

/** IP izin listesi girişi: IPv4 / IPv6 adresi ya da CIDR (IPv4 /0–32, IPv6 /0–128). */
export function isValidIpEntry(raw) {
  const s = normalizeIp(raw)
  if (!s || s.length > MAX_IP_CHARS) return false
  const slash = s.indexOf('/')
  const addr = slash >= 0 ? s.slice(0, slash) : s
  const prefix = slash >= 0 ? s.slice(slash + 1) : null
  if (prefix != null && !/^\d{1,3}$/.test(prefix)) return false
  if (prefix != null && prefix.length > 1 && prefix[0] === '0') return false
  if (isValidIpv4(addr)) return prefix == null || Number(prefix) <= 32
  if (isValidIpv6(addr)) return prefix == null || Number(prefix) <= 128
  return false
}

// ── Doğrulama ───────────────────────────────────────────────────────────────────────────────────

/**
 * İstemci kuralları — sunucuyla aynı; yalnız DEĞİŞEN alanlar denetlenir (kayıtlı ama artık geçersiz bir değer başka alanın
 * kaydını engellemez). `changes` = {@link changedSettings} çıktısı. Dönen: `{ alan: { key, params } }`.
 */
export function validateChanges(changes, limits = DEFAULT_LIMITS) {
  const out = {}
  const lim = { ...DEFAULT_LIMITS, ...(limits || {}) }
  for (const [k, v] of Object.entries(changes || {})) {
    if (INT_FIELDS.includes(k)) {
      const [min, max] = lim[k]
      if (!Number.isInteger(v) || v < min || v > max) out[k] = { key: 'af.v.int', params: [min, max] }
    } else if (TEXT_FIELDS.includes(k)) {
      const s = String(v ?? '')
      if (!s) out[k] = { key: 'af.v.required', params: [lim.text_max] }
      else if (s.length > lim.text_max) out[k] = { key: 'af.v.tooLong', params: [lim.text_max] }
    } else if (k === 'rcitype') {
      if (String(v ?? '').length > lim.text_max) out[k] = { key: 'af.v.tooLong', params: [lim.text_max] }
    } else if (ENUMS[k]) {
      if (!ENUMS[k].includes(v)) out[k] = { key: 'af.v.choice', params: [] }
    } else if (k === 'allowed_ips' || k === 'block_exempt_ips') {
      const list = Array.isArray(v) ? v : []
      const bad = list.find((x) => !isValidIpEntry(x))
      if (bad != null) out[k] = { key: 'af.v.ip', params: [bad] }
      else if (list.length > lim.allowed_ips_max) out[k] = { key: 'af.v.ipMax', params: [lim.allowed_ips_max] }
    }
  }
  return out
}

/**
 * Alanlar arası kural (sunucu `requireBlockThresholdAboveRate` ile aynı): engel eşiği dakikalık sınırdan küçük olamaz.
 * Yalnız eşik ya da sınır DEĞİŞTİYSE denetlenir; değişen alan suçlanır (ikisi birden → eşik). Geçersiz sayı varsa (alan
 * hatası zaten yazar) karar verilmez.
 */
export function crossFieldErrors(changes, form) {
  const c = changes || {}
  if (!('block_threshold' in c) && !('rate_per_minute' in c)) return {}
  const b = bodyOf(form)
  const rate = b.rate_per_minute
  const threshold = b.block_threshold
  if (!Number.isInteger(rate) || !Number.isInteger(threshold) || threshold >= rate) return {}
  const field = 'block_threshold' in c || !('rate_per_minute' in c) ? 'block_threshold' : 'rate_per_minute'
  return { [field]: { key: 'af.v.blockBelowRate', params: [threshold, rate] } }
}

// ── Otomatik IP engelleri ───────────────────────────────────────────────────────────────────────

/** Engel satırının durumu: active | expired | unblocked (bilinmeyen → expired gibi nötr). */
export const BLOCK_STATES = ['active', 'expired', 'unblocked']
export const BLOCK_STATE_TONE = { active: 'danger', expired: 'muted', unblocked: 'info' }

/**
 * Engel listesi — etkin engeller önce, iki grup da sunucu sırasıyla (en yeni engel önce). Kalan süre
 * sunucunun anlık değerinden değil `blocked_until` ile `now`'dan hesaplanır (liste yenilenmeden de doğru kalsın); süresi
 * dolmuş "etkin" satır `expired` görünür.
 */
export function blockRows(blocks, now = Date.now()) {
  if (!Array.isArray(blocks)) return null
  const rows = blocks.filter((b) => b && b.ip).map((b) => {
    const until = utcMs(b.blocked_until)
    let state = BLOCK_STATES.includes(b.state) ? b.state : 'expired'
    if (state === 'active' && Number.isFinite(until) && until <= now) state = 'expired'
    const remainingMs = state === 'active' && Number.isFinite(until) ? Math.max(0, until - now) : 0
    return { ...b, state, remainingMs }
  })
  const active = rows.filter((r) => r.state === 'active')
  const rest = rows.filter((r) => r.state !== 'active')
  return [...active, ...rest]
}

/** Kalan engel süresi → i18n anahtarı + parametre (en az 1 dk, ≥ 60 dk saat + dk); geçmiş / bilinmeyen → null. */
export function remainingParts(ms) {
  const n = Number(ms)
  if (!Number.isFinite(n) || n <= 0) return null
  const mins = Math.max(1, Math.ceil(n / 60_000))
  return mins >= 60
    ? { key: 'af.block.remainingHours', params: [Math.floor(mins / 60), mins % 60] }
    : { key: 'af.block.remainingMinutes', params: [mins] }
}

/** Etkin engel sayısı (liste okunamadıysa null — asla 0). */
export function activeBlockCount(blocks, now = Date.now()) {
  const rows = blockRows(blocks, now)
  return rows == null ? null : rows.filter((r) => r.state === 'active').length
}

// ── Zaman / sayı ────────────────────────────────────────────────────────────────────────────────

/** Sunucu UTC damgası (bölgesiz) → epoch ms; boş / bozuk → NaN. */
export function utcMs(iso) {
  if (!iso || typeof iso !== 'string') return NaN
  const s = /[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : `${iso}Z`
  return new Date(s).getTime()
}

/** Türkiye saatiyle tarih-saat (dd.MM.yyyy HH:mm[:ss]); boş / bozuk → "—". */
export function fmtUtc(iso, lang, seconds = false) {
  const ms = utcMs(iso)
  if (!Number.isFinite(ms)) return '—'
  return new Intl.DateTimeFormat(lang === 'en' ? 'en-GB' : 'tr-TR', {
    day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit',
    ...(seconds ? { second: '2-digit' } : {}), timeZone: 'Europe/Istanbul',
  }).format(new Date(ms))
}

/** Sayı metni (yerel ayırıcılı); null / geçersiz → "—" (asla 0 sanılmaz). */
export function numText(n, lang) {
  if (n == null || n === '') return '—'
  const v = Number(n)
  if (!Number.isFinite(v)) return '—'
  return new Intl.NumberFormat(lang === 'en' ? 'en-GB' : 'tr-TR', { maximumFractionDigits: 1 }).format(v)
}

/** Süre: `{ key, params }` (af.dur.ms | af.dur.sec); bilinmiyor → null. */
export function durationParts(ms, lang) {
  if (ms == null || ms === '') return null
  const n = Number(ms)
  if (!Number.isFinite(n) || n < 0) return null
  if (n < 1000) return { key: 'af.dur.ms', params: [Math.round(n)] }
  const s = new Intl.NumberFormat(lang === 'en' ? 'en-GB' : 'tr-TR', { maximumFractionDigits: 1 }).format(n / 1000)
  return { key: 'af.dur.sec', params: [s] }
}

// ── Durum ───────────────────────────────────────────────────────────────────────────────────────

/**
 * Durum kartının modeli. `tone`: off (kapalı, soluk) · unknown (bilinmiyor, soluk) · warning · info (açık ve sorun yok —
 * bilinçli olarak YEŞİL değil: akış açık olması "sağlıklı" demek değildir).
 * `warnings`: statsUnreadable (sunucu istatistikleri okuyamadı — sayılar bilinmiyor) · silent (son çağrı > 5 dk) · noCalls
 * (son 24 saatte çağrı yok) · logFailures (günlük yazılamadı) · lastError (son çağrı sunucu hatasıyla bitti).
 */
export function statusModel(status, enabled, now = Date.now()) {
  const s = status && typeof status === 'object' ? status : null
  const lastMs = utcMs(s?.last_call_at)
  const lastAgeMs = Number.isFinite(lastMs) ? Math.max(0, now - lastMs) : null
  const calls = s && s.calls_24h != null && Number.isFinite(Number(s.calls_24h)) ? Number(s.calls_24h) : null
  const failures = s && s.log_write_failures != null && Number.isFinite(Number(s.log_write_failures)) ? Number(s.log_write_failures) : null
  const warnings = []
  // Sunucu istatistikleri okuyamadı (`status.error`): sayılar null — "0 çağrı" ya da "sessiz" sanılmaz, ayrıca söylenir
  const unreadable = !!(s && s.error)
  if (unreadable) warnings.push('statsUnreadable')
  if (enabled && !unreadable) {
    if (lastAgeMs != null && lastAgeMs > SILENT_AFTER_MS) warnings.push('silent')
    else if (lastAgeMs == null && calls === 0) warnings.push('noCalls')
    if (s?.last_outcome === 'ERROR') warnings.push('lastError')
  }
  if (failures != null && failures > 0) warnings.push('logFailures')
  let tone
  if (!enabled) tone = warnings.length ? 'warning' : 'off'
  else if (!s) tone = 'unknown'
  else tone = warnings.length ? 'warning' : 'info'
  return { known: !!s && !unreadable, lastAgeMs, warnings, tone, failures }
}

// ── Adres + örnekler ────────────────────────────────────────────────────────────────────────────

/** Sondaki eğik çizgileri atar (geriye tarayan regex yerine doğrusal döngü). */
function trimTrailingSlashes(s) {
  let end = s.length
  while (end > 0 && s[end - 1] === '/') end--
  return s.slice(0, end)
}

/** Tam akış adresi: base_url + endpoint; base yoksa yalnız yol (`full: false`). */
export function feedUrl(baseUrl, endpoint) {
  const ep = String(endpoint || '/api/public/alarm-feed')
  const base = trimTrailingSlashes(String(baseUrl || '').trim())
  return base ? { url: base + ep, full: true } : { url: ep, full: false }
}

/** POSIX kabuğu için tek tırnaklı metin. */
export function shellQuote(s) {
  return `'${String(s).split("'").join("'\\''")}'`
}

/** Python tek tırnaklı dize. */
export function pyQuote(s) {
  return `'${String(s).split('\\').join('\\\\').split("'").join("\\'")}'`
}

/** `curl -s '<url>'` */
export function curlSample(url) {
  return `curl -s ${shellQuote(url)}`
}

/** Üç satırlık Python / pandas örneği. */
export function pythonSample(url, columns = FEED_COLUMNS) {
  const cols = (Array.isArray(columns) && columns.length ? columns : FEED_COLUMNS).map(pyQuote).join(', ')
  return [
    'import pandas as pd, requests',
    `url = ${pyQuote(url)}`,
    `df = pd.DataFrame(requests.get(url, timeout=30).json(), columns=[${cols}])`,
  ].join('\n')
}

// ── Önizleme ────────────────────────────────────────────────────────────────────────────────────

/** Önizleme satırı anahtarı (ALERTID + STATUS + sıra — aynı alarm açık + çözülmüş olarak iki satır olabilir). */
export function previewRowKey(row, i) {
  return `${row?.ALERTID ?? 'x'}|${row?.STATUS ?? ''}|${i}`
}

/** Önizleme kolonları: sunucunun `columns`'u (dizi değilse varsayılan sıra). */
export function previewColumns(preview) {
  return Array.isArray(preview?.columns) && preview.columns.length ? preview.columns : FEED_COLUMNS
}

/** Hücre metni: null / boş → "—". */
export function cellText(v) {
  if (v == null) return '—'
  const s = typeof v === 'object' ? JSON.stringify(v) : String(v)
  return s === '' ? '—' : s
}

// ── Çağrı günlüğü ───────────────────────────────────────────────────────────────────────────────

export const EMPTY_FILTERS = Object.freeze({ outcome: '', ip: '', from: '', to: '' })

/** Süzgeç etkin mi? */
export function filtersActive(f) {
  return !!(f && (f.outcome || f.ip || f.from || f.to))
}

/** İstek parametreleri — boşlar atılır, `page` 0 tabanlı. */
export function requestParams(f, page, size) {
  const out = { page: Math.max(0, Number(page) || 0), size: Number(size) || REQUEST_SIZE_OPTIONS[0] }
  if (f?.outcome && OUTCOMES.includes(f.outcome)) out.outcome = f.outcome
  const ip = String(f?.ip ?? '').trim()
  if (ip) out.ip = ip
  if (f?.from) out.from = String(f.from).slice(0, 10)
  if (f?.to) out.to = String(f.to).slice(0, 10)
  return out
}

/** X-Forwarded-For zinciri → adımlar (virgülle; boşluklar kırpılır). */
export function forwardedChain(raw) {
  if (raw == null) return []
  return String(raw).split(',').map((x) => x.trim()).filter(Boolean)
}

/**
 * Yanıt gövdesi gösterimi: JSON ayrıştırılabiliyorsa girintili (`pretty: true`), değilse metin olduğu gibi.
 * Gövde yoksa `null`.
 */
export function prettyBody(body, contentType) {
  if (body == null) return null
  const raw = String(body)
  const looksJson = /json/i.test(String(contentType || '')) || /^\s*[[{]/.test(raw.slice(0, 64))
  if (looksJson) {
    try {
      return { text: JSON.stringify(JSON.parse(raw), null, 2), pretty: true }
    } catch { /* JSON değil: ham metin */ }
  }
  return { text: raw, pretty: false }
}

/** İndirilecek gövde dosyasının adı: alarm-akisi-yanit-<id>.<json|csv|txt> */
export function bodyFileName(item) {
  const ct = String(item?.body_content_type || '').toLowerCase()
  const ext = ct.includes('json') ? 'json' : ct.includes('csv') ? 'csv' : 'txt'
  return `alarm-akisi-yanit-${item?.id ?? 'x'}.${ext}`
}

/** CSV dışa aktarımının adı (sunucu Content-Disposition vermezse). */
export function exportFileName(now = new Date()) {
  const p = (n) => String(n).padStart(2, '0')
  return `alarm-akisi-cagrilari-${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}.csv`
}

/** İzleme türü etiketi: önce istemci sözlüğü (`userpush.type.<tür>`), sonra sunucunun etiketi, en son ham anahtar. */
export function typeLabel(key, serverLabel, t) {
  const k = `userpush.type.${key}`
  const v = t ? t(k) : k
  if (v && v !== k) return v
  return serverLabel || key
}
