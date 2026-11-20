/**
 * Olay & Hata Geçmişi — saf model (sabitler, URL eşlemesi, süzgeç çipleri, özet kartı süzgeçleri, form doğrulama,
 * kirli-form karşılaştırması, zaman çizelgesi). Bileşenlerden ayrı: jsdom'suz sınanır, sayfa ince kalır.
 *
 * <p><b>URL ad alanı.</b> `tab` / `domain` / `monitor` / `incident` UYGULAMANINDIR (App.jsx). Bu sayfanın durumu `ih_`
 * önekli anahtarlarda (`hooks/useUrlQuerySync.js` PAGE_STATE_PREFIXES — sekme değişince temizlenir); sayfalama
 * standart `page` / `ps` (useServerPagination). E-posta derin bağlantısı `?tab=incident-history&incident=<id>` okunur
 * (açılışta tüketilir), açık ayrıntı `ih_id` olarak adrese yazılır — yenileme ayrıntıyı yeniden açar.
 *
 * <p><b>Tel biçimi.</b> Sunucu yanıtları snake_case (`occurred_at`, `team_name`, `created_by`…); istek gövdesi de
 * bu sayfada snake_case yazılır (IncidentService.applyBody aynı adları okur).
 */
import { formatDuration, parseUtc, autoDurationMinutes } from '../../utils/incidentMeta.js'
import { toIso } from '../../utils/smartDateTime.js'

export const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']
export const STATUSES = ['OPEN', 'INVESTIGATING', 'MITIGATED', 'RESOLVED']
export const CATEGORIES = ['DATABASE', 'NETWORK', 'CERTIFICATE', 'APPLICATION', 'INFRASTRUCTURE', 'OTHER']
export const PRESETS = ['today', 'last7d', 'last30d']
/** Günlük trend penceresi seçenekleri (gün). */
export const TREND_RANGES = [7, 30, 60, 90]

/** Grafik tonları (önem) — açık/koyu temada aynı; rozetler Tailwind jetonlarıyla çizilir. */
export const SEV_COLOR = { CRITICAL: '#dc2626', HIGH: '#ea580c', MEDIUM: '#d97706', LOW: '#16a34a' }

/** Problem tipi — sabit ana maddeler (çoklu seçilir; serbest ekleme de yapılabilir). */
export const PROBLEM_TYPES = [
  'Performans/Kodlama', 'Test/Kontrol Eksikliği', 'Operasyonel Hata', 'Analiz Eksikliği',
  'Konfigürasyon', 'Dış Firma Kaynaklı', 'Donanım Arızası', 'Plansız Değişiklik', 'Diğer',
]

/**
 * Teknik hata adedi üst sınırı (10^15) ve kesinti ID uzunluk sınırı — sunucudaki `IncidentService.MAX_TECH_ERROR_COUNT` /
 * `MAX_OUTAGE_ID` ile AYNI (incidentHistoryModel.test.js Java kaynağını okuyup karşılaştırır).
 */
export const MAX_TECH_ERROR_COUNT = 1e15
export const MAX_OUTAGE_ID = 60

/**
 * Boş form — sunucunun okuduğu alanların TAMAMI (snake_case). Son üç alan (2026-11-20) isteğe bağlıdır:
 * `tech_error_count` metin ('' = bilinmiyor), `outage_related` ÜÇ durumlu (true / false / null = bilinmiyor — null asla
 * "Hayır" değildir), `outage_id` yalnız cevap Evet iken anlamlıdır.
 */
export const EMPTY_FORM = Object.freeze({
  title: '', occurred_at: '', severity: 'HIGH', status: 'OPEN', category: 'APPLICATION',
  error_code: '', function_code: '', channel_code: '', service: '', channel: '', team_id: '', team_name: '',
  detected_at: '', resolved_at: '',
  rca_summary: '', description: '', resolution_steps: '', business_impact: '',
  affected_services: '', problem_types: '', affected_app: '', affected_systems: '',
  affected_customers: '', affected_transactions: '',
  sla_breached: false, error_budget_burn_pct: '', duration_minutes: '', tags: '',
  tech_error_count: '', outage_related: null, outage_id: '',
})

// ── URL ────────────────────────────────────────────────────────────────────────────────────────────────

/** Süzgeç → URL anahtarı (`ih_` öneki; PAGE_STATE_PREFIXES'te). */
export const URL_KEYS = Object.freeze({
  q: 'ih_q', severity: 'ih_sev', status: 'ih_st', category: 'ih_cat', channel: 'ih_ch', team_id: 'ih_team',
  since: 'ih_from', until: 'ih_to', slaBreached: 'ih_sla', open: 'ih_open', _preset: 'ih_preset',
})
/** Açık ayrıntının URL anahtarı; `incident` = uygulama düzeyi e-posta derin bağlantısı (yalnız okunur). */
export const DETAIL_KEY = 'ih_id'
export const LEGACY_DETAIL_KEY = 'incident'

export const FILTER_DEFAULTS = Object.freeze({
  q: '', severity: '', category: '', status: '', channel: '', team_id: '', since: '', until: '',
  slaBreached: undefined, open: undefined, _preset: undefined,
})

const DAY_RE = /^\d{4}-\d{2}-\d{2}$/
const ID_RE = /^\d{1,18}$/

/** URL → süzgeç. Bozuk/bilinmeyen değer yok sayılır (varsayılan kalır) — adres çubuğu sayfayı bozamaz. */
export function filtersFromUrl(read) {
  const r = (k) => {
    try { return read(k, null) } catch { return null }
  }
  const pick = (k, allowed) => { const v = r(k); return v && allowed.includes(v) ? v : '' }
  const day = (k) => { const v = r(k); return v && DAY_RE.test(v) ? v : '' }
  const sla = r(URL_KEYS.slaBreached)
  const open = r(URL_KEYS.open)
  const preset = r(URL_KEYS._preset)
  const team = r(URL_KEYS.team_id)
  return {
    ...FILTER_DEFAULTS,
    q: String(r(URL_KEYS.q) ?? '').slice(0, 200),
    severity: pick(URL_KEYS.severity, SEVERITIES),
    status: pick(URL_KEYS.status, STATUSES),
    category: pick(URL_KEYS.category, CATEGORIES),
    channel: String(r(URL_KEYS.channel) ?? '').slice(0, 200),
    team_id: team && ID_RE.test(team) ? team : '',
    since: day(URL_KEYS.since),
    until: day(URL_KEYS.until),
    slaBreached: sla === 'true' ? true : sla === 'false' ? false : undefined,
    open: open === 'true' || open === '1' ? true : undefined,
    _preset: PRESETS.includes(preset) ? preset : undefined,
  }
}

/** Süzgeç → URL eşlemesi (useUrlQuerySync). Varsayılan değer `null` → param silinir, adres temiz kalır. */
export function filtersToUrl(f) {
  const s = (v) => (v == null || v === '' ? null : String(v))
  return {
    [URL_KEYS.q]: s(f.q?.trim()),
    [URL_KEYS.severity]: s(f.severity),
    [URL_KEYS.status]: s(f.status),
    [URL_KEYS.category]: s(f.category),
    [URL_KEYS.channel]: s(f.channel),
    [URL_KEYS.team_id]: s(f.team_id),
    [URL_KEYS.since]: s(f.since),
    [URL_KEYS.until]: s(f.until),
    [URL_KEYS.slaBreached]: f.slaBreached === true ? 'true' : f.slaBreached === false ? 'false' : null,
    [URL_KEYS.open]: f.open === true ? 'true' : null,
    [URL_KEYS._preset]: s(f._preset),
  }
}

/** Açılışta açılacak ayrıntı kimliği: sayfanın `ih_id`'si, yoksa e-postanın `incident`'ı. */
export function initialDetailId(read) {
  for (const k of [DETAIL_KEY, LEGACY_DETAIL_KEY]) {
    let v = null
    try { v = read(k, null) } catch { v = null }
    if (v && ID_RE.test(String(v))) return String(v)
  }
  return null
}

/** Paylaşılabilir bağlantı — e-posta CTA'sıyla AYNI biçim (`?tab=incident-history&incident=<id>`). */
export function detailLink(id) {
  let origin = ''
  try { origin = window.location.origin + window.location.pathname } catch { /* jsdom dışı ortam */ }
  return `${origin}?tab=incident-history&incident=${encodeURIComponent(id)}`
}

// ── Tarih ──────────────────────────────────────────────────────────────────────────────────────────────

const pad = (n) => String(n).padStart(2, '0')
export const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`

/**
 * Filtre tarih sınırını YEREL gün → UTC ISO'ya çevirir. Kayıtlar UTC saklanır, formatDate tarayıcı yerel saatine göre
 * gösterir; "17 Haz" seçimi yerel 17 Haz 00:00–23:59:59'a, yani UTC karşılığına çevrilmeli (aksi halde 18 Haz 01:00 yerel
 * = 17 Haz 22:00 UTC kaydı "17 Haz" süzgecine sızar). endOfDay=true → günün sonu.
 */
export function localDayToUtcIso(dateStr, endOfDay) {
  if (!dateStr) return undefined
  const [y, m, d] = dateStr.slice(0, 10).split('-').map(Number)
  if (!y || !m || !d) return undefined
  const dt = new Date(y, m - 1, d, endOfDay ? 23 : 0, endOfDay ? 59 : 0, endOfDay ? 59 : 0)
  return `${dt.getUTCFullYear()}-${pad(dt.getUTCMonth() + 1)}-${pad(dt.getUTCDate())}` +
         `T${pad(dt.getUTCHours())}:${pad(dt.getUTCMinutes())}:${pad(dt.getUTCSeconds())}`
}

/** Liste isteğinin parametreleri (tarih sınırları UTC'ye; `_preset` yalnız arayüz işaretidir, sunucuya gitmez). */
export function serverParams(f) {
  const out = {}
  for (const k of ['q', 'severity', 'category', 'status', 'channel', 'team_id']) {
    const v = typeof f[k] === 'string' ? f[k].trim() : f[k]
    if (v != null && v !== '') out[k] = v
  }
  const since = localDayToUtcIso(f.since, false)
  const until = localDayToUtcIso(f.until, true)
  if (since) out.since = since
  if (until) out.until = until
  if (f.slaBreached === true || f.slaBreached === false) out.slaBreached = f.slaBreached
  if (f.open === true) out.open = true
  return out
}

/** "dd.MM.yyyy" — gün biçimli süzgeç değerini okunur yazar (yerel takvim günü, UTC dönüşümü yok). */
export function formatDay(day) {
  if (!day || !DAY_RE.test(day)) return day || ''
  return `${day.slice(8, 10)}.${day.slice(5, 7)}.${day.slice(0, 4)}`
}

/** Göreli zaman ("3 gün önce") — Intl.RelativeTimeFormat, dil uygulamanınki. */
export function relativeFrom(iso, locale, now = Date.now()) {
  const d = parseUtc(iso)
  if (!d) return ''
  const diff = Math.round((d.getTime() - now) / 1000)
  const abs = Math.abs(diff)
  let rtf
  try { rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }) } catch { return '' }
  if (abs < 60) return rtf.format(0, 'second')
  if (abs < 3600) return rtf.format(Math.round(diff / 60), 'minute')
  if (abs < 86400) return rtf.format(Math.round(diff / 3600), 'hour')
  if (abs < 86400 * 30) return rtf.format(Math.round(diff / 86400), 'day')
  if (abs < 86400 * 365) return rtf.format(Math.round(diff / (86400 * 30)), 'month')
  return rtf.format(Math.round(diff / (86400 * 365)), 'year')
}

/**
 * Kaydın süresi (dakika) + sürüyor mu. Öncelik: kayıtlı `duration_minutes` → oluş↔çözülme farkı → (çözülmemişse)
 * oluştan bu yana geçen. Oluş zamanı yoksa null.
 */
export function durationOf(r, nowMs = Date.now()) {
  if (!r) return { minutes: null, ongoing: false }
  const ongoing = r.status !== 'RESOLVED'
  const stored = r.duration_minutes
  if (stored !== '' && stored != null && Number.isFinite(Number(stored)) && Number(stored) >= 0) {
    return { minutes: Number(stored), ongoing: false }
  }
  const start = parseUtc(r.occurred_at)
  if (!start) return { minutes: null, ongoing }
  const endD = r.resolved_at ? parseUtc(r.resolved_at) : (ongoing ? new Date(nowMs) : null)
  if (!endD) return { minutes: null, ongoing }
  const min = Math.round((endD.getTime() - start.getTime()) / 60000)
  return min >= 0 ? { minutes: min, ongoing: ongoing && !r.resolved_at } : { minutes: null, ongoing }
}

/** Dakika → insan-okur süre ("2 sa 5 dk"); birimler i18n (`incov.unit.*`). */
export const formatMinutes = (min, t) => (min == null ? '—' : formatDuration(Number(min) * 60000, t))

/** CSV → tekil, kırpılmış değer listesi. */
export function csvList(s) {
  const seen = new Set()
  const out = []
  for (const part of String(s ?? '').split(',')) {
    const v = part.trim()
    if (v && !seen.has(v.toLowerCase())) { seen.add(v.toLowerCase()); out.push(v) }
  }
  return out
}

// ── Süzgeç çipleri + özet kartları ─────────────────────────────────────────────────────────────────────

/** Etkin süzgeçler — çip listesi (`patch` o süzgeci kaldırır). Sıra: arama, boyutlar, tarih, SLA/açık. */
export function activeFilters(f) {
  const out = []
  const add = (key, value, patch) => out.push({ key, value, patch })
  if (f.q?.trim()) add('q', f.q.trim(), { q: '' })
  if (f.severity) add('severity', f.severity, { severity: '' })
  if (f.status) add('status', f.status, { status: '' })
  if (f.category) add('category', f.category, { category: '' })
  if (f.channel) add('channel', f.channel, { channel: '' })
  if (f.team_id) add('team_id', f.team_id, { team_id: '' })
  if (f.since) add('since', f.since, { since: '', _preset: undefined })
  if (f.until) add('until', f.until, { until: '', _preset: undefined })
  if (f.slaBreached === true || f.slaBreached === false) add('slaBreached', f.slaBreached, { slaBreached: undefined })
  if (f.open === true) add('open', true, { open: undefined })
  return out
}

/** Süzgeç yaması — tarih elle değişince önayar işareti düşer (kart artık etkin görünmez). */
export function patchFilters(prev, patch) {
  const next = { ...prev, ...patch }
  if (('since' in patch || 'until' in patch) && !('_preset' in patch)) next._preset = undefined
  const same = Object.keys(next).every((k) => next[k] === prev[k])
  return same ? prev : next
}

/** Özet kartının süzgeci şu an etkin mi (tek etkin anahtar). */
export function isCardActive(kind, f) {
  switch (kind) {
    case 'critical': return f.severity === 'CRITICAL'
    case 'high': return f.severity === 'HIGH'
    case 'medium': return f.severity === 'MEDIUM'
    case 'low': return f.severity === 'LOW'
    case 'sla': return f.slaBreached === true
    case 'open': return f.open === true
    case 'investigating': return f.status === 'INVESTIGATING'
    case 'mitigated': return f.status === 'MITIGATED'
    case 'resolved': return f.status === 'RESOLVED' && f.slaBreached !== false
    case 'resolved_sla': return f.status === 'RESOLVED' && f.slaBreached === false
    case 'today': case 'last7d': case 'last30d': return f._preset === kind
    case 'total':
      return !f.severity && !f.status && f.slaBreached === undefined && f.open === undefined
        && !f.category && !f.channel && !f.q && !f._preset
    default: return false
  }
}

/**
 * Özet kartına tıklama → yeni süzgeç. Tarih penceresi (since/until) ve takım korunur, diğer boyutlar sıfırlanır,
 * kartın boyutu uygulanır; etkin kart tekrar tıklanırsa kalkar. Zaman önayarları (Bugün / 7 / 30 gün) tarihi yazar.
 */
export function applyCardFilter(prev, kind, now = new Date()) {
  const active = kind !== 'total' && isCardActive(kind, prev)
  const base = { ...prev, q: '', severity: '', category: '', status: '', channel: '',
    slaBreached: undefined, open: undefined, _preset: undefined }
  if (kind === 'total' || active) {
    // Etkin önayarı kaldırmak tarih penceresini de boşaltır (önayar tarihi YAZMIŞTI).
    return active && PRESETS.includes(kind) ? { ...base, since: '', until: '' } : base
  }
  switch (kind) {
    case 'critical': return { ...base, severity: 'CRITICAL' }
    case 'high': return { ...base, severity: 'HIGH' }
    case 'medium': return { ...base, severity: 'MEDIUM' }
    case 'low': return { ...base, severity: 'LOW' }
    case 'sla': return { ...base, slaBreached: true }
    case 'open': return { ...base, open: true }
    case 'investigating': return { ...base, status: 'INVESTIGATING' }
    case 'mitigated': return { ...base, status: 'MITIGATED' }
    case 'resolved': return { ...base, status: 'RESOLVED' }
    case 'resolved_sla': return { ...base, status: 'RESOLVED', slaBreached: false }
    case 'today': case 'last7d': case 'last30d': {
      const back = kind === 'today' ? 0 : kind === 'last7d' ? 7 : 30
      return { ...base, since: ymd(new Date(now.getTime() - back * 86400000)), until: ymd(now), _preset: kind }
    }
    default: return base
  }
}

/** Trend çubuğuna tıklama → o günü süz (since = until = gün); aynı güne tekrar → temizle. */
export function toggleDayFilter(prev, day) {
  if (!day) return prev
  return prev.since === day && prev.until === day
    ? { ...prev, since: '', until: '', _preset: undefined }
    : { ...prev, since: day, until: day, _preset: undefined }
}

/**
 * Günlük trend: son `days` günü SÜREKLİ doldur (olaysız gün = 0) → gerçek takvim trendi. Anahtar yerel gün
 * (sunucu Europe/Istanbul gününe göre gruplar).
 */
export function dailySeries(daily, days, now = new Date()) {
  const byDay = new Map((daily || []).map((d) => [String(d.day).slice(0, 10), d]))
  const out = []
  for (let i = days - 1; i >= 0; i--) {
    const key = ymd(new Date(now.getTime() - i * 86400000))
    const d = byDay.get(key)
    out.push({
      day: key,
      count: Number(d?.count) || 0,
      critical: Number(d?.critical) || 0,
      high: Number(d?.high) || 0,
      medium: Number(d?.medium) || 0,
      low: Number(d?.low) || 0,
    })
  }
  return out
}

/** Günlük serinin gün aralığı (bugünle biten pencere) — trend isteği için. */
export function trendWindow(days, now = new Date()) {
  return { since: ymd(new Date(now.getTime() - (days - 1) * 86400000)), until: ymd(now) }
}

// ── Form ───────────────────────────────────────────────────────────────────────────────────────────────

/** Form bölümleri (sekme sırası) ve alanların bölümü — doğrulama hatası ilk hatalı bölüme götürür. */
export const SECTIONS = ['summary', 'impact', 'codes', 'rca', 'notify']
export const FIELD_SECTION = Object.freeze({
  title: 'summary', occurred_at: 'summary', team_id: 'summary', severity: 'summary', status: 'summary',
  category: 'summary', problem_types: 'summary', detected_at: 'summary', resolved_at: 'summary', duration_minutes: 'summary',
  channel: 'impact', service: 'impact', affected_services: 'impact', affected_app: 'impact', affected_systems: 'impact',
  affected_customers: 'impact', affected_transactions: 'impact', sla_breached: 'impact', error_budget_burn_pct: 'impact',
  tech_error_count: 'impact', outage_related: 'impact', outage_id: 'impact',
  business_impact: 'impact',
  error_code: 'codes', function_code: 'codes', channel_code: 'codes', tags: 'codes',
  rca_summary: 'rca', description: 'rca', resolution_steps: 'rca',
  send_notification: 'notify',
})

const isBlank = (v) => v == null || String(v).trim() === ''

// ── Teknik hata adedi · kesinti kaynaklı mı · kesinti ID (2026-11-20) ──────────────────────────────────

/** `s`'in `from` konumundan sonu yalnız ASCII rakam mı (en az bir rakam) — doğrusal, düzenli ifade yok. */
function digitsFrom(s, from) {
  if (s.length <= from) return false
  for (let i = from; i < s.length; i++) {
    const c = s.charCodeAt(i)
    if (c < 48 || c > 57) return false
  }
  return true
}

/** 10^15'in rakamları — üst sınır karşılaştırması METİN üzerinde yapılır (çok uzun rakam dizisinde kayan nokta yanılmasın). */
const MAX_TECH_ERROR_DIGITS = String(MAX_TECH_ERROR_COUNT)

/**
 * Teknik hata adedi (form metni ya da sayı) → hata i18n anahtarı | null. Sunucudaki `IncidentService.strictTechErrorCount`
 * ile AYNI kural: boş = bilinmiyor (geçerli); yalnız rakamlardan oluşan tam sayı; ondalık ("12.7"), binlik ayraçlı ("1.250"),
 * üstel ("1e3"), işaretli ("+5") ya da metin → tam sayı değil; negatif → ayrı ileti; en çok 10^15.
 */
export function techErrorCountProblem(v) {
  if (isBlank(v)) return null
  const s = String(v).trim()
  const neg = s.charCodeAt(0) === 45
  if (!digitsFrom(s, neg ? 1 : 0)) return 'inc.errTechCountWhole'
  let i = neg ? 1 : 0
  while (i < s.length - 1 && s.charCodeAt(i) === 48) i++          // baştaki sıfırlar ("0007") değeri değiştirmez
  const digits = s.slice(i)
  if (neg) return digits === '0' ? null : 'inc.errTechCountNegative'   // "-0" = 0 (sunucu da kabul eder)
  if (digits.length > MAX_TECH_ERROR_DIGITS.length
    || (digits.length === MAX_TECH_ERROR_DIGITS.length && digits > MAX_TECH_ERROR_DIGITS)) return 'inc.errTechCountMax'
  return null
}

/** Satır sonu sayılan karakter mi: LF, VT, FF, CR, NEL (0x85), satır / paragraf ayırıcı (0x2028 / 0x2029) — sunucuyla aynı. */
const isLineBreakCode = (c) => (c >= 0x0A && c <= 0x0D) || c === 0x85 || c === 0x2028 || c === 0x2029

/**
 * Kesinti ID → hata i18n anahtarı | null. Sunucudaki `IncidentService.strictOutageId` ile AYNI kural: kırpılır; boş geçerli
 * (zorunlu değil); İÇİNDE satır sonu olamaz; en çok {@link MAX_OUTAGE_ID} karakter.
 */
export function outageIdProblem(v) {
  if (isBlank(v)) return null
  const s = String(v).trim()
  for (let i = 0; i < s.length; i++) if (isLineBreakCode(s.charCodeAt(i))) return 'inc.errOutageIdLine'
  return s.length > MAX_OUTAGE_ID ? 'inc.errOutageIdLong' : null
}

/** Kesinti cevabı: yalnız GERÇEK boolean sayılır → true | false | null (bilinmiyor). Bilinmeyen asla "Hayır" değildir. */
export const outageAnswerOf = (r) => (r?.outage_related === true ? true : r?.outage_related === false ? false : null)

/** Kaydın teknik hata adedi → sayı | null (girilmemiş ya da tam sayı olarak okunamıyor — satır / kutu çizilmez). */
export function techErrorCountOf(r) {
  const v = r?.tech_error_count
  if (typeof v !== 'number' && typeof v !== 'string') return null
  if (isBlank(v) || techErrorCountProblem(v)) return null
  const n = Number(String(v).trim())
  return n === 0 ? 0 : n                                          // "-0" → 0 (ekranda "-0" yazmasın)
}

/** Gösterilecek kesinti ID: yalnız cevap Evet VE değer doluyken (kırpılmış); aksi halde ''. */
export function outageIdOf(r) {
  if (r?.outage_related !== true || isBlank(r?.outage_id)) return ''
  return String(r.outage_id).trim()
}

/**
 * Sunucu kaydı (ya da ön doldurma) → form durumu. Üç yeni alan form biçimine çevrilir: adet METİN (null → ''), cevap
 * kesin true / false / null, kimlik metin (null → ''). Anahtarları hiç taşımayan kayıt (eski pod) "bilinmiyor" açılır.
 */
export function formFromRow(row) {
  const r = row && typeof row === 'object' ? row : {}
  return {
    ...EMPTY_FORM, ...r,
    tech_error_count: r.tech_error_count == null ? '' : String(r.tech_error_count),
    outage_related: outageAnswerOf(r),
    outage_id: r.outage_id == null ? '' : String(r.outage_id),
  }
}

/**
 * Metin alanlarının üst sınırları (karakter) — sunucudaki `IncidentService.MAX_*` ile AYNI (kolon uzunlukları; markdown
 * alanları 20 000). incidentHistoryModel.test.js Java kaynağını okuyup karşılaştırır.
 */
export const MAX_LENGTHS = Object.freeze({
  title: 255, error_code: 100, function_code: 100, channel_code: 100,
  service: 500, channel: 500, problem_types: 500, affected_systems: 500, runbook_url: 500,
  affected_services: 255, affected_app: 255, tags: 255,
  rca_summary: 20000, description: 20000, resolution_steps: 20000, business_impact: 20000,
})
/** Tam sayı alanlarının üst sınırı (sunucu: Integer.MAX_VALUE). */
export const MAX_WHOLE = 2147483647
/** Hata bütçesi tüketimi (%) üst sınırı — sunucudaki `IncidentService.MAX_ERROR_BUDGET` ile aynı. */
export const MAX_ERROR_BUDGET = 1e6
const WHOLE_KEYS = ['affected_customers', 'affected_transactions', 'duration_minutes']

/** Yalnız ASCII rakam (doğrusal) — başta tek '-' varsa negatif sayılır. */
function wholeProblem(v) {
  if (isBlank(v)) return null
  const s = String(v).trim()
  const neg = s.charCodeAt(0) === 45
  if (!digitsFrom(s, neg ? 1 : 0)) return Number(s) < 0 ? 'inc.errNonNegative' : 'inc.errWholeNumber'
  if (neg) return /^-0+$/.test(s) ? null : 'inc.errNonNegative'
  return Number(s) > MAX_WHOLE ? 'inc.errWholeNumber' : null
}

/** Ondalık sayı (nokta ayırıcı, üstel yazım yok) 0 … 1.000.000. */
function decimalProblem(v) {
  if (isBlank(v)) return null
  const s = String(v).trim()
  let dot = false
  let digit = false
  const from = s.charCodeAt(0) === 45 ? 1 : 0
  for (let i = from; i < s.length; i++) {
    const c = s.charCodeAt(i)
    if (c === 46) { if (dot) return 'inc.errNumber'; dot = true } else if (c >= 48 && c <= 57) digit = true
    else return 'inc.errNumber'
  }
  if (!digit || s.endsWith('.') || s.charAt(from) === '.') return 'inc.errNumber'
  if (from === 1 && Number(s) !== 0) return 'inc.errNonNegative'
  return Number(s) > MAX_ERROR_BUDGET ? 'inc.errNumber' : null
}

/** Formdaki değer `baseline`'dakiyle (formun açıldığı kayıt) aynı mı — aynıysa yeniden doğrulanmaz (sunucuyla aynı kural). */
function unchangedFrom(baseline, k, v) {
  if (baseline == null || typeof baseline !== 'object') return false
  return canon(k, v) === canon(k, baseline[k])
}

/**
 * İstemci doğrulaması — alan → i18n anahtarı (uzunluk hatasında `{ key, params }`). Boş nesne = geçerli. Sunucu aynı
 * kuralları ayrıca uygular; burası anında, alanın yanında geri bildirim içindir. `baseline` (düzenlemede formun açıldığı
 * kayıt) verilirse sayı ve uzunluk kuralları YALNIZ değişen değerde uygulanır — eski kayıttan kalan, bugünkü kurala uymayan
 * değer düzenlemeyi kilitlemez ("Sağlam Kayıt", 2026-11-20).
 */
export function validateIncident(f, baseline) {
  const e = {}
  // Düzenlemede değişmeyen zorunlu alan yeniden denetlenmez (sunucu da gövdede olmayan alana dokunmaz): eski, takımsız ya da
  // başlıksız kayıt öteki alanları düzeltilerek kaydedilebilir.
  const same = (k) => unchangedFrom(baseline, k, f[k])
  if (isBlank(f.title) && !same('title')) e.title = 'inc.errTitle'
  if (isBlank(f.occurred_at) && !same('occurred_at')) e.occurred_at = 'inc.errOccurredAt'
  if (isBlank(f.team_id) && !same('team_id')) e.team_id = 'inc.errTeam'
  const occ = parseUtc(f.occurred_at)
  const det = parseUtc(f.detected_at)
  const res = parseUtc(f.resolved_at)
  // Sıra kuralı yalnız çiftin EN AZ bir ucu değiştiyse (sunucudaki checkOrder ile aynı): kayıtta zaten ters duran eski
  // zamanlar düzenlemeyi kilitlemez.
  const pair = (a, b) => !same(a) || !same(b)
  if (det && res && res < det && pair('detected_at', 'resolved_at')) e.resolved_at = 'inc.resolvedBeforeDetected'
  else if (occ && res && res < occ && pair('occurred_at', 'resolved_at')) e.resolved_at = 'inc.errResolvedBeforeOccurred'
  if (occ && det && det < occ && pair('occurred_at', 'detected_at')) e.detected_at = 'inc.errDetectedBeforeOccurred'
  for (const k of WHOLE_KEYS) {
    if (unchangedFrom(baseline, k, f[k])) continue
    const p = wholeProblem(f[k])
    if (p) e[k] = p
  }
  if (!unchangedFrom(baseline, 'error_budget_burn_pct', f.error_budget_burn_pct)) {
    const p = decimalProblem(f.error_budget_burn_pct)
    if (p) e.error_budget_burn_pct = p
  }
  for (const [k, max] of Object.entries(MAX_LENGTHS)) {
    if (e[k] || isBlank(f[k]) || unchangedFrom(baseline, k, f[k])) continue
    const len = String(f[k]).trim().length
    if (len > max) e[k] = { key: 'inc.errTooLong', params: [max, len] }
  }
  // Üç isteğe bağlı alan (2026-11-20) — sunucunun katı kurallarının aynısı. Kesinti ID yalnız cevap Evet iken denetlenir
  // (aksi halde alan gizlidir ve gövdeye null yazılır).
  const count = techErrorCountProblem(f.tech_error_count)
  if (count) e.tech_error_count = count
  if (f.outage_related === true) {
    const id = outageIdProblem(f.outage_id)
    if (id) e.outage_id = id
  }
  return e
}

/** Hatalı alanların bölümleri → { bölüm: sayı } (sekme rozetleri). */
export function errorCountBySection(errors) {
  const out = {}
  for (const k of Object.keys(errors || {})) {
    const s = FIELD_SECTION[k] || 'summary'
    out[s] = (out[s] || 0) + 1
  }
  return out
}

/** İlk hatalı bölüm (sekme sırasına göre) — yoksa null. */
export function firstErrorSection(errors) {
  const counts = errorCountBySection(errors)
  return SECTIONS.find((s) => counts[s] > 0) ?? null
}

/**
 * Kirli-form karşılaştırmasının anlık görüntüsü: tüm form alanları metin olarak. `duration_minutes`, oluş + çözülme
 * zamanı varken TÜRETİLMİŞTİR (form açılınca otomatik yazılır) — karşılaştırmaya girmez, yoksa hiçbir şey
 * değiştirilmemiş formda "kaydedilmemiş değişiklik" sorulurdu.
 */
export function formSnapshot(f) {
  const out = {}
  for (const k of [...Object.keys(EMPTY_FORM), 'send_notification']) {
    if (k === 'team_name') continue
    if (k === 'duration_minutes' && !isBlank(f.occurred_at) && !isBlank(f.resolved_at)) continue
    // Kesinti ID yalnız cevap Evet iken kaydedilir: cevap Evet değilken alanda kalan metin (Evet → yaz → Hayır) gövdeye
    // girmez, "kaydedilmemiş değişiklik" de sayılmaz. Evet iken kırpılmış hâli karşılaştırılır (gövdeye de öyle yazılır).
    if (k === 'outage_id') { out[k] = f.outage_related === true && !isBlank(f[k]) ? String(f[k]).trim() : ''; continue }
    const v = f[k]
    out[k] = typeof v === 'boolean' ? String(v) : (v == null ? '' : String(v))
  }
  if (out.send_notification === '') out.send_notification = 'false'
  if (out.sla_breached === '') out.sla_breached = 'false'
  return JSON.stringify(out)
}

/** Üç isteğe bağlı alanın gövde anahtarları (2026-11-20) — düzenlemede yalnız DEĞİŞEN gönderilir. */
export const OUTAGE_PAYLOAD_KEYS = Object.freeze(['tech_error_count', 'outage_related', 'outage_id'])

/**
 * Kayıt gövdesi — boş sayısal alanlar gönderilmez (sunucu "" → sayıya çeviremez).
 *
 * <p>Üç isteğe bağlı alan (2026-11-20): `tech_error_count` boşsa null, geçerliyse SAYI (geçersiz metin olduğu gibi gider —
 * sunucu alan hatası döner, sessizce null'a çevrilmez); `outage_related` true / false / null; `outage_id` yalnız cevap Evet
 * ve değer doluyken (kırpılmış), aksi halde null.
 *
 * <p>`baseline` (DÜZENLEMEDE formun açıldığı kayıt) verilirse bu üç anahtardan yalnız DEĞİŞEN gönderilir (sunucuda "anahtar
 * yok" = dokunma, açık null = temizle): hiç dokunulmamış bir düzenleme — eski, üçü de boş kayıt dahil — üçünü de GÖNDERMEZ,
 * başka birinin bu arada yazdığı değer ezilmez. Karşılaştırma gövde biçiminde yapılır ("1250" = 1250; cevap Evet değilken
 * kimlik = null). `baseline` yoksa (yeni kayıt, mail önizlemesi) üçü de her zaman gövdededir. Öteki alanlar bu kuraldan
 * ETKİLENMEZ (form eskisi gibi tamamını yazar).
 */
export function payloadFromForm(f, baseline) {
  const p = { ...f }
  for (const k of ['error_budget_burn_pct', 'duration_minutes']) if (p[k] === '' || p[k] == null) delete p[k]
  if (isBlank(f.tech_error_count)) p.tech_error_count = null
  else {
    const text = String(f.tech_error_count).trim()
    const n = techErrorCountProblem(text) ? null : Number(text)
    p.tech_error_count = n == null ? text : (n === 0 ? 0 : n)
  }
  p.outage_related = outageAnswerOf(f)
  p.outage_id = outageIdOf(f) || null
  if (baseline != null && typeof baseline === 'object') {
    const was = payloadFromForm(formFromRow(baseline))
    for (const k of OUTAGE_PAYLOAD_KEYS) if (p[k] === was[k]) delete p[k]
  }
  return p
}

// ── "Sağlam Kayıt" (2026-11-20): yalnız değişenler, taslak farkı, tutarlılık dürtmeleri ─────────────────────────────

/** Düzenleme gövdesine girebilen alanlar — sunucunun yazdığı anahtarlar. `team_name` istemciden alınmaz (sunucu okur). */
export const EDIT_KEYS = Object.freeze(Object.keys(EMPTY_FORM).filter((k) => k !== 'team_name'))
const NUM_KEYS = new Set(['affected_customers', 'affected_transactions', 'duration_minutes', 'error_budget_burn_pct', 'team_id', 'tech_error_count'])

/** Karşılaştırma biçimi: boş / null / undefined → ''; sayısal alanda sayı metni sayıya ("0120" = 120); metin kırpılır. */
function canon(k, v) {
  if (k === 'sla_breached') return String(v === true)
  if (v == null) return ''
  if (typeof v === 'boolean') return String(v)
  const s = String(v).trim()
  if (NUM_KEYS.has(k) && s !== '' && /^-?\d+(\.\d+)?$/.test(s)) return String(Number(s))
  return s
}

/** Gövde değeri: boş metin → null (açık temizle); geçerli sayı metni → sayı; geçersiz metin olduğu gibi (sunucu alan hatası). */
function bodyValue(k, v) {
  if (k === 'sla_breached') return v === true
  if (v == null) return null
  if (typeof v === 'number' || typeof v === 'boolean') return v
  const s = String(v)
  if (s.trim() === '') return null
  if (NUM_KEYS.has(k)) {
    const t = s.trim()
    return /^-?\d+(\.\d+)?$/.test(t) ? Number(t) : s
  }
  return s
}

/** Formun iki zamanından türetilen (kilitli) süre mi — iki zaman da varken süre alanı otomatiktir. */
const autoDuration = (f) => !isBlank(f?.occurred_at) && !isBlank(f?.resolved_at)

/**
 * DÜZENLEME gövdesi: yalnız `baseline`'dan (formun açıldığı — ya da yerine geçen taze — kayıt) FARKLI alanlar + `id` +
 * `version` (iyimser kilit; kayıt taşıyorsa). Temizlenen alan açık `null` gider ("anahtar yok" = dokunma). `team_name` yalnız
 * takım değiştiyse eklenir (yeni sunucu onu yok sayar; kademeli dağıtımdaki ESKİ pod yalnız gövdedeki adı yazar). Süre, iki
 * zaman da varken GÖNDERİLMEZ (sunucu hesaplar); çözülme zamanı silindiğinde formda kalan eski otomatik süre de gönderilmez
 * (sunucu yeniden açılan kaydın hesaplanmış süresini kendisi siler). Dönüş `{ body, changed }`; `changed` boşsa ve "Mail
 * gönder" seçilmemişse istek GEREKMEZ.
 */
export function editPayload(f, baseline) {
  const base = baseline && typeof baseline === 'object' ? baseline : {}
  const now = payloadFromForm(f)
  const was = payloadFromForm(formFromRow(base))
  // Eski otomatik süre: bugünkü (aşağı yuvarlanmış) ya da 2026-11-20 öncesi istemcinin (en yakın dakika) hesabı.
  const autoWas = [
    autoDurationMinutes(base.occurred_at, base.resolved_at),
    autoDurationMinutes(base.occurred_at, base.resolved_at, { round: true }),
  ].filter((v) => v != null).map(String)
  const body = {}
  const changed = []
  for (const k of EDIT_KEYS) {
    if (k === 'duration_minutes') {
      if (autoDuration(f)) continue
      if (autoWas.includes(canon(k, f[k]))) continue
    }
    if (canon(k, now[k]) === canon(k, was[k])) continue
    body[k] = bodyValue(k, now[k])
    changed.push(k)
  }
  if (changed.includes('team_id')) body.team_name = isBlank(f.team_name) ? null : String(f.team_name)
  if (base.id != null) body.id = base.id
  const v = Number(base.version)
  if (base.version != null && base.version !== '' && Number.isInteger(v) && v >= 0) body.version = v
  if (f.send_notification === true) body.send_notification = true
  return { body, changed }
}

/** İki kaydın (ya da kayıt ↔ form) farklı alanları — taze kayıt notu ve taslak için (gövde kurallarıyla aynı karşılaştırma). */
export function changedKeys(a, b) {
  const pa = payloadFromForm(formFromRow(a))
  const pb = payloadFromForm(formFromRow(b))
  return EDIT_KEYS.filter((k) => canon(k, pa[k]) !== canon(k, pb[k]))
}

/**
 * Formun kayda göre DEĞİŞEN alanları (ham form değerleri) — taslak saklanırken. Takım değiştiyse adı da; iki zamandan
 * türetilen süre saklanmaz (geri yüklenince yeniden hesaplanır). "Mail gönder" taslağa girmez.
 */
/**
 * Gelen kayıt elde tutulandan DAHA ESKİ sürümlü mü (iki tarafta da tam sayı sürüm varsa). Kayıttan önce başlayıp sonra dönen
 * okuma kaydedilen hâli geri yazmasın diye. Sürüm bilinmiyorsa (eski sunucu) false — bugünkü davranış.
 */
export function olderRecord(held, incoming) {
  const a = Number(held?.version)
  const b = Number(incoming?.version)
  if (held?.version == null || incoming?.version == null || held.version === '' || incoming.version === '') return false
  return Number.isInteger(a) && Number.isInteger(b) && b < a
}

export function formChanges(f, baseline) {
  const now = payloadFromForm(f)
  const was = payloadFromForm(formFromRow(baseline && typeof baseline === 'object' ? baseline : {}))
  const out = {}
  for (const k of EDIT_KEYS) {
    if (k === 'duration_minutes' && autoDuration(f)) continue
    if (canon(k, now[k]) !== canon(k, was[k])) out[k] = f[k]
  }
  if ('team_id' in out) out.team_name = f.team_name ?? ''
  return out
}

/**
 * Durum ↔ zaman tutarlılığı — ENGELLEMEYEN öneriler (kayıt yine yapılabilir):
 *   - `setResolvedNow`: durum Çözüldü ama çözülme zamanı yok → şimdiyi yaz
 *   - `markResolved`: çözülme zamanı var ama durum Çözüldü değil → Çözüldü yap
 *   - `reopenClear`: Çözüldü kaydı yeniden açıldı (açılıştaki durum RESOLVED'di) ama çözülme zamanı duruyor → zamanı ve süreyi temizle
 */
export function consistencyNudges(f, baseline) {
  if (!f) return []
  const resolved = f.status === 'RESOLVED'
  const hasRes = !isBlank(f.resolved_at)
  if (resolved && !hasRes) return ['setResolvedNow']
  if (!resolved && hasRes) return [baseline?.status === 'RESOLVED' ? 'reopenClear' : 'markResolved']
  return []
}

/**
 * "Çözülme zamanı = şimdi" önerisinin değeri: şimdi, oluş ve tespit zamanlarının EN GEÇ olanı, dakikaya YUKARI yuvarlanır
 * (form saniye tutmaz). Alarmdan gelen oluş / tespit bu dakikanın içindeyse (saniyeli) "şimdi"nin aşağı yuvarlanmış hâli
 * ondan önce kalır ve kayıt sıra kuralına takılırdı. Dönüş: UTC ISO, bölge eki yok.
 */
export function resolvedNowIso(f, now = new Date()) {
  let ms = now instanceof Date ? now.getTime() : Number(now)
  for (const k of ['occurred_at', 'detected_at']) {
    const d = parseUtc(f?.[k])
    if (d && d.getTime() > ms) ms = d.getTime()
  }
  if (!Number.isFinite(ms)) return ''
  return toIso(new Date(Math.ceil(ms / 60000) * 60000))
}

/** Önerinin forma uygulanmış hâli (yeni nesne). `nowIso` = dakikaya yuvarlanmış UTC ISO (bölge eki yok; `resolvedNowIso`). */
export function applyNudge(f, kind, nowIso) {
  if (kind === 'setResolvedNow') return { ...f, resolved_at: nowIso }
  if (kind === 'markResolved') return { ...f, status: 'RESOLVED' }
  if (kind === 'reopenClear') return { ...f, resolved_at: '', duration_minutes: '' }
  return f
}

/** Önizlenecek bildirim türü — sunucunun kayıtta göndereceğiyle aynı karar (yeni / çözüldü / güncellendi). */
export function previewKind(mode, status) {
  if (mode === 'create') return 'NEW'
  return status === 'RESOLVED' ? 'RESOLVED' : 'UPDATED'
}

/**
 * Markdown görsellerinin adları (`![ad](adres)`) — eski `/!\[([^\]]*)\]\([^)]*\)/g` ile AYNI sonuç, doğrusal
 * (2026-10-09): eski ifade kapanışı olmayan her `![` için metnin sonuna kadar tarıyordu (çok sayıda `![` → O(k·N),
 * görsel yüklerken sekme donar). Aday `![` konumları artan sırada; sonraki `]` / `)` aramaları önbellekli ve tek
 * yönlü olduğu için toplam iş metin boyuyla orantılıdır.
 */
export function markdownImageCaptions(txt) {
  const s = String(txt ?? '')
  const out = []
  const nextOf = (ch) => {                                        // q artan sırada sorulur → tek yönlü önbellek
    let at = -2
    return (q) => {
      if (at === -1 || (at >= q)) return at
      at = s.indexOf(ch, q)
      return at
    }
  }
  const closeAt = nextOf(']')
  const parenAt = nextOf(')')
  let from = 0
  for (let p = s.indexOf('![', from); p >= 0; p = s.indexOf('![', from)) {
    const close = closeAt(p + 2)
    if (close < 0) break                                          // sonrasında `]` yok → başka eşleşme de yok
    if (s[close + 1] !== '(') { from = p + 1; continue }
    const paren = parenAt(close + 2)
    if (paren < 0) break                                          // sonrasında `)` yok → başka eşleşme de yok
    out.push(s.slice(p + 2, close))
    from = paren + 1
  }
  return out
}

/**
 * Markdown görsel adı tekilleştirme — aynı olayın dört markdown alanı genelinde aynı ad tekrar ederse
 * "ad (2).uzantı" üretir; `reserved` bu oturumda verilmiş adlar (yükleme sürerken ikinci seçim çakışmasın).
 */
export function uniqueCaption(desired, form, reserved) {
  const base = (desired || 'image').trim() || 'image'
  const used = new Set(reserved)
  for (const k of ['rca_summary', 'description', 'resolution_steps', 'business_impact']) {
    for (const caption of markdownImageCaptions(form?.[k] || '')) used.add(caption)
  }
  if (!used.has(base)) return base
  const dot = base.lastIndexOf('.')
  const stem = dot > 0 ? base.slice(0, dot) : base
  const ext = dot > 0 ? base.slice(dot) : ''
  let n = 2
  let cand
  do { cand = `${stem} (${n})${ext}`; n++ } while (used.has(cand))
  return cand
}

// ── Ayrıntı: zaman çizelgesi ───────────────────────────────────────────────────────────────────────────

const minutesBetween = (a, b) => {
  const x = parseUtc(a); const y = parseUtc(b)
  if (!x || !y) return null
  const m = Math.round((y.getTime() - x.getTime()) / 60000)
  return m >= 0 ? m : null
}

/**
 * Zaman çizelgesi adımları: oluştu → tespit edildi → (çözülmemişse güncel durum) → çözüldü. Zamanı girilmemiş adım
 * `pending` işaretlenir (kullanıcı eksik bilgiyi görür); `after` = oluştan bu yana geçen dakika.
 */
export function buildTimeline(r) {
  if (!r) return []
  const resolved = r.status === 'RESOLVED'
  const steps = [{ kind: 'occurred', at: r.occurred_at || null, pending: !r.occurred_at }]
  steps.push({ kind: 'detected', at: r.detected_at || null, pending: !r.detected_at,
    after: minutesBetween(r.occurred_at, r.detected_at) })
  if (!resolved) steps.push({ kind: 'status', status: r.status || 'OPEN', current: true })
  steps.push({ kind: 'resolved', at: r.resolved_at || null, pending: !r.resolved_at,
    after: minutesBetween(r.occurred_at, r.resolved_at) })
  return steps
}
