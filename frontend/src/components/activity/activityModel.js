/**
 * Aktivite sayfası — saf model (2026-10-11 yeniden tasarım; 2026-11-05 zenginleştirme D–G). Bileşen yalnız çizer; karar
 * burada ve testli: yerleşim, çizelge noktaları, süzgeç durumu ↔ URL, etkin süzgeç çipleri, KPI ↔ durum eşlemesi, rota
 * (liste / ayrıntı sayfası / izleme zaman çizelgesi) ve hata nedeni kısa adı.
 */

/** Liste kabı bu genişliğin altındaysa kart listesi (tablo sütunları ad + sonuç + zamanı sığdıramaz). */
export const TABLE_MIN_PX = 720
/** Süzgeç kartı bu genişliğin altındaysa tür çipleri katlanır ("İzleme türü (n)" düğmesi). */
export const CHIPS_INLINE_MIN_PX = 640

/**
 * Tablo mu kart mı. Kap ölçüldüyse (gerçek tarayıcı) KAP genişliği belirler — 768 px tablette kenar çubuğu açıkken
 * içerik dar; ölçüm yoksa (jsdom, ilk boyama) görünüm alanı sorgusu (`phone`).
 */
export function pickLayout(width, phone) {
  if (width > 0) return width < TABLE_MIN_PX ? 'cards' : 'table'
  return phone ? 'cards' : 'table'
}

const TZ = 'Europe/Istanbul'

/**
 * Sunucu serisi → grafik noktaları. Saatlik `t` = UTC saat başı ("…Z"), günlük `t` = İstanbul günü (yyyy-MM-dd).
 * Etiket İstanbul saatiyle (UTC+3) — tarayıcının saat dilimi ne olursa olsun.
 */
export function chartPoints(series, lang = 'tr') {
  const pts = Array.isArray(series?.points) ? series.points : []
  const hourly = series?.bucket === 'hour'
  const locale = lang === 'en' ? 'en-GB' : 'tr-TR'
  const hourFmt = new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit', timeZone: TZ })
  const dayFmt = new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short', timeZone: TZ })
  const fullHour = new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZone: TZ })
  const fullDay = new Intl.DateTimeFormat(locale, { weekday: 'short', day: 'numeric', month: 'long', timeZone: TZ })
  return pts.map((p) => {
    // Günlük anahtar İstanbul günü: öğlen 12:00 İstanbul (09:00Z) ile biçimlenir — gün kaymaz
    const at = hourly ? new Date(p.t) : new Date(`${p.t}T09:00:00Z`)
    const ok = Number.isFinite(at.getTime())
    return {
      t: p.t,
      label: ok ? (hourly ? hourFmt : dayFmt).format(at) : String(p.t ?? ''),
      full: ok ? (hourly ? fullHour : fullDay).format(at) : String(p.t ?? ''),
      success: Number(p.success) || 0,
      warning: Number(p.warning) || 0,
      error: Number(p.error) || 0,
      unknown: Number(p.unknown) || 0,
    }
  })
}

/** Serinin toplamı (boşsa grafik yerine boş durum). */
export function seriesTotal(points) {
  return (points || []).reduce((s, p) => s + p.success + p.warning + p.error + p.unknown, 0)
}

// ── Süzgeç sözlüğü (2026-11-05, E) ──────────────────────────────────────────────────────────────

/** İzleme türleri — ActivityLog.TYPES ile AYNI sıra (rozet rengi orada). Monitör başvurusu (`TYPE:id`) bu listeden. */
export const TYPE_KEYS = ['CERT', 'DOMAIN', 'HTTP', 'UPTIME', 'PORT', 'PING', 'DNS', 'KEYWORD', 'PAGE', 'PAGESPEED', 'SCRIPTED', 'DBQUERY']
/** Sonuç durumları — TIMEOUT dahil (2026-11-05, G: durum seçicide yoktu, "Hata" KPI'si onu sayıp süzmüyordu). */
export const STATUSES = ['SUCCESS', 'WARNING', 'ERROR', 'TIMEOUT', 'UNKNOWN']
/** Aralık seçenekleri; `custom` = özel tarih aralığı (`afrom` / `ato`). */
export const RANGES = ['today', '24h', '7d', 'all', 'custom']
export const DEFAULT_RANGE = '24h'
/** Olay grupları — sunucu `group` süzgeciyle aynı anahtarlar (ActivityController be2 sözleşmesi). */
export const GROUPS = ['check', 'alarm', 'notification', 'change', 'other']
/**
 * KPI kartı → durum kümesi. "Hata" kartı sunucuda ERROR + TIMEOUT sayar; tıklanınca İKİSİ de süzülür (2026-11-05, G:
 * eskiden yalnız ERROR süzülüyor, zaman aşımları listeden kayboluyordu).
 */
export const KPI_STATUS = Object.freeze({ SUCCESS: ['SUCCESS'], WARNING: ['WARNING'], ERROR: ['ERROR', 'TIMEOUT'] })

/** Süzgeç durumunun varsayılanı (URL'de yazılmayan değerler). */
export const DEFAULT_FILTERS = Object.freeze({
  types: [], statuses: [], range: DEFAULT_RANGE, from: '', to: '', q: '', team: '', actor: '', groups: [], reasons: [],
  monitor: '', monitorLabel: '',
})

/** Aktivite listesinin URL anahtarları — sekme değişince silinir (useUrlQuerySync.PAGE_STATE_PARAMS) ve kayıtlı görünüme girer. */
export const LIST_URL_KEYS = ['atype', 'astatus', 'arange', 'afrom', 'ato', 'aq', 'ateam', 'aactor', 'agroup', 'areason', 'amf', 'amfl']
/** Rota anahtarları: `act` = ayrıntı sayfası, `amon` = izleme zaman çizelgesi (+ çizelgenin aralığı `atl*`). GEÇİCİDİR (görünüme girmez). */
export const ROUTE_URL_KEYS = ['act', 'amon', 'atlr', 'atlf', 'atlt']

// Girdi sınırları — URL'den gelen değer düzenli ifadeye girmeden önce uzunlukça kırpılır / reddedilir.
const MAX_LIST = 60
const MAX_TOKEN = 40
const MAX_Q = 200
const MAX_ACTOR = 100
const MAX_LABEL = 160
const TOKEN_RE = /^[A-Z][A-Z0-9_]*$/
/** Neden süzgeci değeri: kod ya da eski satırın hata sınıfı `class:<SINIF>` (be2 ActivityFilter.CLASS_PREFIX). */
const REASON_RE = /^(class:)?[A-Z][A-Z0-9_]*$/
export const CLASS_PREFIX = 'class:'
/** Aktivite satırlarında görülen hata sınıfları (eski satırların neden süzgeci) — `act.cls.<SINIF>` etiketleri. */
export const ERROR_CLASSES = ['NETWORK', 'DNS', 'SSL', 'TIMEOUT', 'BLOCKED', 'CONFIG']
const ISO_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/
const ID_RE = /^\d{1,15}$/

function csvTokens(raw, allowed, re = TOKEN_RE) {
  if (typeof raw !== 'string' || !raw) return []
  const out = []
  for (const part of raw.slice(0, MAX_LIST * (MAX_TOKEN + 1)).split(',')) {
    const v = part.trim()
    if (!v || v.length > MAX_TOKEN || out.includes(v)) continue
    if (allowed ? !allowed.includes(v) : !re.test(v)) continue
    out.push(v)
    if (out.length >= MAX_LIST) break
  }
  return out
}

/** Serbest ISO zaman (UTC, yyyy-MM-ddTHH:mm[:ss]) — saniyesiz gelirse ":00" eklenir; geçersiz → ''. */
export function cleanIso(raw) {
  if (typeof raw !== 'string' || raw.length > 25) return ''
  const v = raw.trim()
  if (!ISO_RE.test(v)) return ''
  return v.length === 16 ? v + ':00' : v
}

/** `TYPE:id` → { type, id } ya da null (bilinmeyen tür, bozuk kimlik). */
export function parseMonitorRef(raw) {
  if (typeof raw !== 'string' || !raw || raw.length > MAX_TOKEN) return null
  const i = raw.indexOf(':')
  if (i < 1) return null
  const type = raw.slice(0, i).toUpperCase()
  const idText = raw.slice(i + 1)
  if (!TYPE_KEYS.includes(type) || !ID_RE.test(idText)) return null
  const id = Number(idText)
  return Number.isSafeInteger(id) && id > 0 ? { type, id } : null
}

export const formatMonitorRef = (type, id) => `${String(type || '').toUpperCase()}:${id}`

/** URL sorgusu → süzgeç durumu (her değer doğrulanır; bilinmeyen/bozuk → varsayılan). */
export function readFilters(search) {
  let p
  try { p = new URLSearchParams(search || '') } catch { p = new URLSearchParams() }
  const range = RANGES.includes(p.get('arange')) ? p.get('arange') : DEFAULT_RANGE
  const from = cleanIso(p.get('afrom'))
  const to = cleanIso(p.get('ato'))
  const team = ID_RE.test(p.get('ateam') || '') ? p.get('ateam') : ''
  const monitorRef = parseMonitorRef(p.get('amf'))
  return {
    types: csvTokens(p.get('atype')),
    statuses: csvTokens(p.get('astatus'), STATUSES),
    // Özel aralık uçsuz anlamsız: iki uç da yoksa varsayılan aralığa düşer (yarım kalmış paylaşılan bağlantı)
    range: range === 'custom' && !from && !to ? DEFAULT_RANGE : range,
    from: range === 'custom' ? from : '',
    to: range === 'custom' ? to : '',
    q: (p.get('aq') || '').slice(0, MAX_Q),
    team,
    actor: (p.get('aactor') || '').trim().slice(0, MAX_ACTOR),
    groups: csvTokens(p.get('agroup'), GROUPS),
    reasons: csvTokens(p.get('areason'), null, REASON_RE),
    monitor: monitorRef ? formatMonitorRef(monitorRef.type, monitorRef.id) : '',
    monitorLabel: monitorRef ? (p.get('amfl') || '').slice(0, MAX_LABEL) : '',
  }
}

/** Süzgeç durumu → URL sorgusu (diğer paramlar — tab, sayfa, rota — korunur). Varsayılan değerler yazılmaz. */
export function writeFilters(filters, search) {
  let p
  try { p = new URLSearchParams(search || '') } catch { p = new URLSearchParams() }
  const f = { ...DEFAULT_FILTERS, ...(filters || {}) }
  const set = (k, v) => (v ? p.set(k, v) : p.delete(k))
  set('atype', f.types.join(','))
  set('astatus', f.statuses.join(','))
  set('arange', f.range && f.range !== DEFAULT_RANGE ? f.range : '')
  set('afrom', f.range === 'custom' ? f.from : '')
  set('ato', f.range === 'custom' ? f.to : '')
  set('aq', f.q)
  set('ateam', f.team)
  set('aactor', f.actor)
  set('agroup', f.groups.join(','))
  set('areason', f.reasons.join(','))
  set('amf', f.monitor)
  set('amfl', f.monitor ? f.monitorLabel : '')
  return p.toString()
}

/** Süzgeç varsayılandan farklı mı (boş durum metni ve "Tümünü temizle" görünürlüğü). */
export function isFiltered(f) {
  if (!f) return false
  return f.types.length > 0 || f.statuses.length > 0 || !!f.q || (f.range && f.range !== DEFAULT_RANGE) || !!f.team
    || !!f.actor || f.groups.length > 0 || f.reasons.length > 0 || !!f.monitor
}

/** İstanbul gününün başlangıcı (ms) — Europe/Istanbul sabit UTC+3, yaz saati yok (2026-10-12: "bugün" ve gün grupları
 *  UTC / tarayıcı gece yarısı değil İstanbul gece yarısı; 00:00–03:00 arası etkinlik "bugün"de). */
export function istanbulDayStartMs(now = Date.now()) {
  // formatToParts (inceleme 4): `format()` çıktısının "YYYY-MM-DD" olacağı varsayılmaz — bazı ICU sürümlerinde en-CA
  // biçimi değişti; Invalid Date `toISOString()`te istisna atıp listeyi düşürürdü. Parçalar eksikse sabit UTC+3 hesabı.
  try {
    const parts = new Intl.DateTimeFormat('en-CA', { timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit' })
      .formatToParts(new Date(now))
    const get = (type) => parts.find((x) => x.type === type)?.value
    const y = Number(get('year')), m = Number(get('month')), d = Number(get('day'))
    if (y > 0 && m >= 1 && m <= 12 && d >= 1 && d <= 31) return Date.UTC(y, m - 1, d) - ISTANBUL_OFFSET_MS
  } catch { /* Intl yok / saat dilimi bilinmiyor → aşağıdaki sabit hesap */ }
  return Math.floor((now + ISTANBUL_OFFSET_MS) / DAY_MS) * DAY_MS - ISTANBUL_OFFSET_MS
}
const ISTANBUL_OFFSET_MS = 3 * 3_600_000
const DAY_MS = 86_400_000

/**
 * range → {from,to} ISO (UTC, yyyy-MM-ddTHH:mm:ss). Her istekte o anki zamanla hesaplanır (canlı tazeleme "son 24
 * saat"i dondurmasın). `custom` aralığın uçları süzgeçten aynen gelir.
 */
export function rangeToFromTo(range, now = Date.now(), custom = null) {
  const iso = (d) => d.toISOString().slice(0, 19)
  if (range === 'today') return { from: iso(new Date(istanbulDayStartMs(now))), to: null }
  if (range === '24h') return { from: iso(new Date(now - 24 * 3600 * 1000)), to: null }
  if (range === '7d') return { from: iso(new Date(now - 7 * 24 * 3600 * 1000)), to: null }
  if (range === 'custom') return { from: custom?.from || null, to: custom?.to || null }
  return { from: null, to: null }
}

/** UTC ISO (Z'siz) → İstanbul "dd.MM.yyyy HH:mm" (çip / özet metni); okunamazsa olduğu gibi. */
export function istanbulShort(iso, lang = 'tr') {
  if (!iso) return ''
  const s = String(iso)
  const ms = Date.parse(s.endsWith('Z') || /[+-]\d{2}:\d{2}$/.test(s) ? s : s + 'Z')
  if (!Number.isFinite(ms)) return s
  try {
    const parts = new Intl.DateTimeFormat(lang === 'en' ? 'en-GB' : 'tr-TR', {
      timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
    }).formatToParts(new Date(ms))
    const g = (k) => parts.find((x) => x.type === k)?.value || ''
    return `${g('day')}.${g('month')}.${g('year')} ${g('hour')}:${g('minute')}`
  } catch {
    return s
  }
}

/** Özel aralığın sırası doğru mu (ikisi de doluysa başlangıç ≤ bitiş). */
export function customRangeValid(from, to) {
  return !(from && to && from > to)
}

/**
 * Süzgeç → liste / özet / dışa aktarım istek parametreleri. `type` / `status` / `q` her zaman yazılır (boş → istemci
 * düşürür; mevcut testler anahtarın varlığına bakar); diğerleri yalnız doluysa.
 */
export function toApiParams(f, now = Date.now()) {
  const x = { ...DEFAULT_FILTERS, ...(f || {}) }
  const out = { type: x.types.join(','), status: x.statuses.join(','), q: x.q, ...rangeToFromTo(x.range, now, x) }
  if (x.team) out.teamId = x.team
  if (x.actor) out.actor = x.actor
  if (x.groups.length) out.group = x.groups.join(',')
  if (x.reasons.length) out.reason = x.reasons.join(',')
  if (x.monitor) out.monitor = x.monitor
  return out
}

/** Durum kümesi hangi KPI kartına denk (sıra önemsiz): 'ALL' | 'SUCCESS' | 'WARNING' | 'ERROR' | null (başka küme). */
export function kpiKeyOf(statuses) {
  const s = [...(statuses || [])].sort()
  if (!s.length) return 'ALL'
  for (const [k, v] of Object.entries(KPI_STATUS)) {
    const w = [...v].sort()
    if (w.length === s.length && w.every((x, i) => x === s[i])) return k
  }
  return null
}

/**
 * Etkin süzgeç çipleri (kaldırılabilir): türler, varsayılan dışı aralık, arama, takım, izleme, kişi, olay grupları,
 * hata nedenleri. Durum çipi YOK — durum KPI kartının basılı hâli ve durum seçicisi zaten gösteriyor (ikinci bir
 * "Hata" düğmesi aynı adı taşırdı). Sıra sabit (tür, aralık, arama ilk üç — mevcut testler).
 */
export function activeChips(filters) {
  const out = []
  const f = filters || {}
  for (const ty of f.types || []) out.push({ key: 'type:' + ty, kind: 'type', value: ty })
  if (f.range && f.range !== DEFAULT_RANGE) out.push({ key: 'range', kind: 'range', value: f.range })
  if (f.q) out.push({ key: 'q', kind: 'q', value: f.q })
  if (f.team) out.push({ key: 'team', kind: 'team', value: f.team })
  if (f.monitor) out.push({ key: 'monitor', kind: 'monitor', value: f.monitor })
  if (f.actor) out.push({ key: 'actor', kind: 'actor', value: f.actor })
  for (const g of f.groups || []) out.push({ key: 'group:' + g, kind: 'group', value: g })
  for (const r of f.reasons || []) out.push({ key: 'reason:' + r, kind: 'reason', value: r })
  return out
}

/** Çipi kaldır → yeni süzgeç. */
export function removeChip(filters, chip) {
  if (chip.kind === 'type') return { ...filters, types: filters.types.filter((x) => x !== chip.value) }
  if (chip.kind === 'range') return { ...filters, range: DEFAULT_RANGE, from: '', to: '' }
  if (chip.kind === 'q') return { ...filters, q: '' }
  if (chip.kind === 'team') return { ...filters, team: '' }
  if (chip.kind === 'monitor') return { ...filters, monitor: '', monitorLabel: '' }
  if (chip.kind === 'actor') return { ...filters, actor: '' }
  if (chip.kind === 'group') return { ...filters, groups: filters.groups.filter((x) => x !== chip.value) }
  if (chip.kind === 'reason') return { ...filters, reasons: filters.reasons.filter((x) => x !== chip.value) }
  return filters
}

// ── Rota (2026-11-05, A + D) ────────────────────────────────────────────────────────────────────

/** URL → rota: `act=<id>` ayrıntı sayfası, `amon=TYPE:id` izleme zaman çizelgesi, ikisi de yoksa liste. */
export function readRoute(search) {
  let p
  try { p = new URLSearchParams(search || '') } catch { p = new URLSearchParams() }
  const act = p.get('act') || ''
  if (ID_RE.test(act)) {
    const id = Number(act)
    if (Number.isSafeInteger(id) && id > 0) return { kind: 'detail', id }
  }
  const mon = parseMonitorRef(p.get('amon'))
  if (mon) return { kind: 'timeline', type: mon.type, id: mon.id }
  return { kind: 'list' }
}

/** Rota → URL sorgusu: rota anahtarları silinip yalnız hedefinkiler yazılır; liste süzgeçleri ve tab KORUNUR. */
export function routeSearch(search, route) {
  let p
  try { p = new URLSearchParams(search || '') } catch { p = new URLSearchParams() }
  for (const k of ROUTE_URL_KEYS) p.delete(k)
  if (route?.kind === 'detail' && route.id != null) p.set('act', String(route.id))
  if (route?.kind === 'timeline' && route.type && route.id != null) p.set('amon', formatMonitorRef(route.type, route.id))
  const s = p.toString()
  return s ? `?${s}` : ''
}

/** Aynı rota mı (rota tekrar itilmesin). */
export function sameRoute(a, b) {
  if (!a || !b || a.kind !== b.kind) return false
  if (a.kind === 'list') return true
  if (a.kind === 'detail') return String(a.id) === String(b.id)
  return a.type === b.type && String(a.id) === String(b.id)
}

// ── Satır alanları (2026-11-05, G) ─────────────────────────────────────────────────────────────

/**
 * Sunucu özeti yalnız Türkçe üretir ("0 kırık · 12ms", "3 istek", "yüklenemedi"); İngilizce arayüzde
 * jetonlar sözlükten çevrilir (QA 2026-09-12, ISSUE-010). Türkçede sözlük değeri = jetonun kendisi.
 * Liste ve ayrıntı sayfası aynı kuralı kullanır (tek kaynak).
 */
const SUMMARY_TOKENS = [['yapılandırma hatası', 'act.tok.configError'], ['yüklenemedi', 'act.tok.loadFailed'],
  ['zaman aşımı', 'act.tok.timeout'], ['kırık', 'act.tok.broken'], ['istek', 'act.tok.requests']]
export function localizeSummary(summary, t) {
  if (!summary) return summary
  let s = String(summary)
  for (const [tr, key] of SUMMARY_TOKENS) s = s.split(tr).join(t(key))
  return s
}

const ALARM_ACTIONS = new Set(['ALERT_TRIGGERED', 'ALERT_ESCALATED', 'ALERT_ACKNOWLEDGED', 'ALERT_RESOLVED'])
/** Alarm olayı satırı mı (rozet + "Alarm #id"). */
export const isAlarmAction = (action) => ALARM_ACTIONS.has(String(action || ''))

/** Satırın izleme başvurusu (zaman çizelgesi / izleme süzgeci için) — kimliksiz satırda null. */
export function monitorRefOf(row) {
  if (!row || row.monitor_id == null) return null
  const type = String(row.monitor_type || '').toUpperCase()
  const id = Number(row.monitor_id)
  if (!TYPE_KEYS.includes(type) || !Number.isSafeInteger(id) || id <= 0) return null
  return { type, id }
}

const MAX_DETAIL_JSON = 4000

/** `failure_detail` (nesne ya da ≤ 4000 karakter JSON dizesi) → nesne; bozuk / uzun → {}. */
export function failureDetailOf(row) {
  const raw = row?.failure_detail
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) return raw
  if (typeof raw !== 'string' || !raw || raw.length > MAX_DETAIL_JSON) return {}
  try {
    const d = JSON.parse(raw)
    return d && typeof d === 'object' && !Array.isArray(d) ? d : {}
  } catch {
    return {}
  }
}

/** i18n şablonunun ADLI yer tutucularını doldurur; değeri olmayan "…" olur (hiçbir zaman ham "{status}"). */
export function fillNamed(template, params = {}) {
  return String(template ?? '').replace(/\{([a-z][a-z0-9_]*)\}/gi, (_, name) => {
    const v = params[name]
    return v == null || v === '' ? '…' : String(v)
  })
}

/**
 * Hata nedeninin kısa adı: önce kontrol hata kataloğu (`chkfail.<KOD>.short`), sonra keyword kataloğu
 * (`kwfail.<KOD>.short`); ikisi de yoksa kodun kendisi (çağıran tek aralıklı yazıyla çizer). `params` satırdan
 * (HTTP kodu, kırık kaynak sayısı…); yoksa yer tutucu "…" olur.
 */
export function reasonLabel(code, t, params = {}) {
  if (!code) return ''
  const c = String(code).slice(0, MAX_TOKEN)
  // Eski satır: neden kodu yok, hata sınıfı var (`class:NETWORK`) — sınıf adıyla (bilinen sınıf çevrilir)
  if (c.startsWith(CLASS_PREFIX)) {
    const cls = c.slice(CLASS_PREFIX.length)
    const key = `act.cls.${cls}`
    const v = t(key)
    return v && v !== key ? v : t('act.cls.any', cls)
  }
  for (const ns of ['chkfail', 'kwfail']) {
    const key = `${ns}.${c}.short`
    const tpl = t(key)
    if (tpl && tpl !== key) return fillNamed(tpl, params)
  }
  return c
}

/** Satırın neden etiketinin parametreleri (HTTP kodu + ayrıntıdaki sayılar). */
export function reasonParams(row) {
  const d = failureDetailOf(row)
  const n = (v) => (v == null || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))
  return {
    status: n(row?.http_status) ?? n(d.http_status),
    broken: n(d.broken), timeouts: n(d.timeouts), mixed: n(d.mixed),
    ms: n(d.timeout_ms), target: d.target ?? null,
  }
}
