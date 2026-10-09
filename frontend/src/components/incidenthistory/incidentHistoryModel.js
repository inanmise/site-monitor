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
import { formatDuration, parseUtc } from '../../utils/incidentMeta.js'

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

/** Boş form — sunucunun okuduğu alanların TAMAMI (snake_case). */
export const EMPTY_FORM = Object.freeze({
  title: '', occurred_at: '', severity: 'HIGH', status: 'OPEN', category: 'APPLICATION',
  error_code: '', function_code: '', channel_code: '', service: '', channel: '', team_id: '', team_name: '',
  detected_at: '', resolved_at: '',
  rca_summary: '', description: '', resolution_steps: '', business_impact: '',
  affected_services: '', problem_types: '', affected_app: '', affected_systems: '',
  affected_customers: '', affected_transactions: '',
  sla_breached: false, error_budget_burn_pct: '', duration_minutes: '', tags: '',
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
  business_impact: 'impact',
  error_code: 'codes', function_code: 'codes', channel_code: 'codes', tags: 'codes',
  rca_summary: 'rca', description: 'rca', resolution_steps: 'rca',
  send_notification: 'notify',
})

const isBlank = (v) => v == null || String(v).trim() === ''
const negative = (v) => !isBlank(v) && !(Number.isFinite(Number(v)) && Number(v) >= 0)

/**
 * İstemci doğrulaması — alan → i18n anahtarı. Boş nesne = geçerli. Sunucu aynı zorunlulukları ayrıca uygular;
 * burası anında, alanın yanında geri bildirim içindir.
 */
export function validateIncident(f) {
  const e = {}
  if (isBlank(f.title)) e.title = 'inc.errTitle'
  if (isBlank(f.occurred_at)) e.occurred_at = 'inc.errOccurredAt'
  if (isBlank(f.team_id)) e.team_id = 'inc.errTeam'
  const occ = parseUtc(f.occurred_at)
  const det = parseUtc(f.detected_at)
  const res = parseUtc(f.resolved_at)
  if (det && res && res < det) e.resolved_at = 'inc.resolvedBeforeDetected'
  else if (occ && res && res < occ) e.resolved_at = 'inc.errResolvedBeforeOccurred'
  if (occ && det && det < occ) e.detected_at = 'inc.errDetectedBeforeOccurred'
  for (const k of ['error_budget_burn_pct', 'affected_customers', 'affected_transactions', 'duration_minutes']) {
    if (negative(f[k])) e[k] = 'inc.errNonNegative'
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
    const v = f[k]
    out[k] = typeof v === 'boolean' ? String(v) : (v == null ? '' : String(v))
  }
  if (out.send_notification === '') out.send_notification = 'false'
  if (out.sla_breached === '') out.sla_breached = 'false'
  return JSON.stringify(out)
}

/** Kayıt gövdesi — boş sayısal alanlar gönderilmez (sunucu "" → sayıya çeviremez). */
export function payloadFromForm(f) {
  const p = { ...f }
  for (const k of ['error_budget_burn_pct', 'duration_minutes']) if (p[k] === '' || p[k] == null) delete p[k]
  return p
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
