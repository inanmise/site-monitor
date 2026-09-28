/**
 * HTTP istekleri (Sistem Sağlığı bölümü + İstek Gezgini) — SAF model: React yok, API istemcisi yok; birim testleri
 * doğrudan koşar (2026-09-28 yeniden tasarım).
 *
 * <p>Veri kaynakları (tel biçimi snake_case):
 * - Bölüm: `GET /api/admin/system/http-metrics` → `{ summary: { total_requests, total_errors, error_rate_pct, avg_ms,
 *   max_ms, p50_ms?, p95_ms?, p99_ms?, req_per_min?, peak_req_per_min? }, history: [{ ts (UTC, Z'siz), count, errors,
 *   avg_ms, max_ms, p95_ms? }], top_endpoints?: { slowest: [...], errors: [...] } }` — bellek içi, son 24 saat.
 * - Gezgin: `GET …/http-metrics/overview` → `{ granularity, capped, clamped, from, to, summary, status_codes: [{code,
 *   count}], data: [{ ts (IST yerel), t (epoch ms), count, errors, avg_ms, p50_ms, p95_ms, p99_ms, max_ms, status_2xx
 *   … status_5xx, status_other, unclassified }], endpoints: [...], endpoints_total, endpoints_truncated }`.
 * - Uç ayrıntısı: `…/http-metrics/series?endpoint=` (aynı nokta biçimi + `status_codes`).
 *
 * <p>Renk kuralı (dataviz doğrulayıcısından geçti: 2xx/3xx/4xx/5xx bitişik çiftleri CVD ΔE ≥ 8, normal görüş ≥ 15):
 * durum sınıfları proje grafik jetonlarıyla — 2xx `--chart-2` (yeşil), 3xx `--chart-5` (mor), 4xx `--chart-3`
 * (kehribar), 5xx `--destructive`; süre ailesi TEK ton (`--chart-1`) ve çizgi deseniyle ayrılır (ort. düz, p95
 * kesikli, p99 noktalı). Serbest hex YOK. Metin her zaman metin jetonuyla; renk yalnız işaretin kendisinde.
 */
import { HEALTH_THRESHOLDS, HTTP_ERR_PCT } from '../health/healthModel.js'
import { toUtc } from '../../../utils/localDay.js'
import { dateLocale, formatPercent } from '../../../i18n/dateLocale.js'
import { csvRows } from '../../../utils/csv.js'
import { QUICK_RANGES } from '../../ui/TimeRangePicker.jsx'

export const STATUS_CLASSES = ['2xx', '3xx', '4xx', '5xx']
export const CLASS_COLOR = {
  '2xx': 'var(--chart-2)',
  '3xx': 'var(--chart-5)',
  '4xx': 'var(--chart-3)',
  '5xx': 'var(--destructive)',
  other: 'var(--muted-foreground)',
  unclassified: 'var(--muted-foreground)',
}
export const LATENCY_COLOR = 'var(--chart-1)'
export const METHOD_ORDER = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']

/** Otomatik yenileme yalnız ≤ 24 saatlik göreli aralıkta (uzun aralıkta dakikalık tazeleme bir şey değiştirmez, tarama pahalı). */
export const LIVE_MAX_MINUTES = 1440
export const LIVE_INTERVAL_MS = 60_000

const num = (v) => (v == null || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))
const cnt = (v) => Math.max(0, Math.round(Number(v) || 0))

// ── Uç adı ─────────────────────────────────────────────────────────────────────────────────────

/** "GET /api/x/{id}" → { method: 'GET', path: '/api/x/{id}' }; yöntemsiz özel kova ("(overflow)") → method ''. */
export function splitEndpoint(ep) {
  const s = String(ep ?? '')
  const m = /^([A-Z]{3,7}) (.*)$/.exec(s)
  return m ? { method: m[1], path: m[2] } : { method: '', path: s }
}

/** Durum kodu → sınıf ('2xx' … '5xx' | 'other'). */
export function classOf(code) {
  const c = Number(code)
  if (c >= 200 && c <= 299) return '2xx'
  if (c >= 300 && c <= 399) return '3xx'
  if (c >= 400 && c <= 499) return '4xx'
  if (c >= 500 && c <= 599) return '5xx'
  return 'other'
}

function classesOf(o) {
  return {
    '2xx': cnt(o?.status_2xx), '3xx': cnt(o?.status_3xx), '4xx': cnt(o?.status_4xx), '5xx': cnt(o?.status_5xx),
    other: cnt(o?.status_other),
  }
}

/** Sunucu sınıf göndermediyse (eski sunucu) ya da eksik gönderdiyse aradaki fark "sınıfsız". */
function unclassifiedOf(o, total, classes) {
  const told = num(o?.unclassified)
  if (told != null) return cnt(told)
  const sum = Object.values(classes).reduce((n, v) => n + v, 0)
  return Math.max(0, total - sum)
}

/** Uç satırı (tel → model). Geçersiz öğe null. */
export function normEndpoint(e) {
  if (!e || typeof e !== 'object' || typeof e.endpoint !== 'string') return null
  const { method, path } = e.method != null && e.path != null ? { method: e.method, path: e.path } : splitEndpoint(e.endpoint)
  const count = cnt(e.count)
  const classes = classesOf(e)
  return {
    endpoint: e.endpoint, method: method || '', path: path || e.endpoint,
    count, errors: cnt(e.errors), errorRate: num(e.error_rate_pct) ?? 0,
    avg: num(e.avg_ms), p50: num(e.p50_ms), p95: num(e.p95_ms), p99: num(e.p99_ms), max: num(e.max_ms),
    classes, unclassified: unclassifiedOf(e, count, classes), lastSeen: typeof e.last_seen === 'string' ? e.last_seen : null,
  }
}

/** Seri noktası. `t` (epoch) yoksa `ts` tarayıcı yerelinde okunur (eski sunucu IST yerel duvar saati gönderir). */
export function normPoint(p) {
  if (!p || typeof p !== 'object') return null
  const t = num(p.t) ?? (typeof p.ts === 'string' ? Date.parse(p.ts) : NaN)
  if (!Number.isFinite(t)) return null
  const count = cnt(p.count)
  const classes = classesOf(p)
  return {
    t, ts: p.ts ?? null, count, errors: cnt(p.errors),
    avg: count > 0 ? num(p.avg_ms) : null, p50: count > 0 ? num(p.p50_ms) : null,
    p95: count > 0 ? num(p.p95_ms) : null, p99: count > 0 ? num(p.p99_ms) : null, max: count > 0 ? num(p.max_ms) : null,
    ...classes, unclassified: unclassifiedOf(p, count, classes),
  }
}

/** Özet. İstek yoksa süre / oran alanları null (sunucu 0 gönderir; "0 ms" ölçülmüş gibi okunur → "—"). */
export function normSummary(s) {
  const total = cnt(s?.total ?? s?.total_requests)
  const classes = classesOf(s)
  const has = total > 0
  const v = (x) => (has ? num(x) : null)
  return {
    total, errors: cnt(s?.errors ?? s?.total_errors), errorRate: has ? (num(s?.error_rate_pct) ?? 0) : null,
    avg: v(s?.avg_ms), max: v(s?.max_ms), min: v(s?.min_ms),
    p50: v(s?.p50_ms), p95: v(s?.p95_ms), p99: v(s?.p99_ms), reqPerMin: num(s?.req_per_min),
    classes, unclassified: unclassifiedOf(s, total, classes),
  }
}

export function normCodes(list) {
  return (Array.isArray(list) ? list : [])
    .map((c) => ({ code: cnt(c?.code), count: cnt(c?.count) }))
    .filter((c) => c.count > 0)
    .sort((a, b) => b.count - a.count || a.code - b.code)
}

/** Gezgin yanıtı → model. Boş/bozuk yanıt boş model (ekran çökmez). */
export function normOverview(d) {
  const data = d && typeof d === 'object' ? d : {}
  const points = (Array.isArray(data.data) ? data.data : []).map(normPoint).filter(Boolean).sort((a, b) => a.t - b.t)
  const endpoints = (Array.isArray(data.endpoints) ? data.endpoints : []).map(normEndpoint).filter(Boolean)
  return {
    granularity: data.granularity === 'hour' ? 'hour' : 'minute',
    capped: !!data.capped, clamped: !!data.clamped,
    // Sunucunun GERÇEKTEN taradığı aralık (31 güne kırpılmış olabilir) — ayrıntı paneli aynı aralığı ister (2026-09-28c ek-2)
    from: typeof data.from === 'string' ? data.from : null, to: typeof data.to === 'string' ? data.to : null,
    summary: normSummary(data.summary), codes: normCodes(data.status_codes),
    points, endpoints,
    endpointsTotal: cnt(data.endpoints_total) || endpoints.length, truncated: !!data.endpoints_truncated,
  }
}

// ── Süzme / sıralama ──────────────────────────────────────────────────────────────────────────

export const EMPTY_FILTERS = Object.freeze({ endpoint: '', methods: [], classes: [] })

/** Etkin süzgeç sayısı (çip/rozet). */
export function activeFilterCount(f) {
  return (f?.endpoint ? 1 : 0) + (f?.methods?.length || 0) + (f?.classes?.length || 0)
}

/** Uç tablosu süzgeci: uç / yöntem / durum sınıfı (en az bir isteği o sınıfta) + yol araması. */
export function filterEndpoints(list, { endpoint = '', methods = [], classes = [], q = '' } = {}) {
  const needle = String(q || '').trim().toLowerCase()
  return (list || []).filter((e) => (!endpoint || e.endpoint === endpoint)
    && (!methods.length || methods.includes(e.method))
    && (!classes.length || classes.some((c) => (e.classes[c] || 0) > 0))
    && (!needle || e.endpoint.toLowerCase().includes(needle)))
}

export const SORT_KEYS = ['count', 'errorRate', 'avg', 'p95', 'max', 'lastSeen', 'endpoint']
/** Varsayılan yön: yol A→Z, diğerleri büyükten küçüğe. */
export const defaultDir = (key) => (key === 'endpoint' ? 'asc' : 'desc')

/** Kararlı sıralama; boş değerler yönden bağımsız EN SONA; eşitlikte yol A→Z. */
export function sortEndpoints(list, { key = 'count', dir = 'desc' } = {}) {
  const k = SORT_KEYS.includes(key) ? key : 'count'
  const sign = dir === 'asc' ? 1 : -1
  const val = (e) => (k === 'endpoint' ? e.path : k === 'lastSeen' ? e.lastSeen : e[k])
  return [...(list || [])].sort((a, b) => {
    const va = val(a)
    const vb = val(b)
    if (va == null && vb == null) return a.endpoint.localeCompare(b.endpoint)
    if (va == null) return 1
    if (vb == null) return -1
    const c = typeof va === 'string' ? va.localeCompare(vb) : va - vb
    return c !== 0 ? c * sign : a.endpoint.localeCompare(b.endpoint)
  })
}

/** Durum süzgeci etkinken sayılan istekler (seçili sınıfların toplamı); süzgeç yoksa hepsi. */
export function countForClasses(obj, classes) {
  if (!classes?.length) return obj.count ?? obj.total ?? 0
  return classes.reduce((n, c) => n + (obj.classes?.[c] ?? obj[c] ?? 0), 0)
}

// ── Tonlar ────────────────────────────────────────────────────────────────────────────────────

/** Hata oranı tonu (Sistem Sağlığı eşikleriyle aynı: %1 uyarı, %5 kritik). */
export function errTone(pct, total = 1) {
  if (pct == null || !total) return 'neutral'
  return pct >= HTTP_ERR_PCT.crit ? 'crit' : pct >= HTTP_ERR_PCT.warn ? 'warn' : 'ok'
}

/** Süre tonu (Sistem Sağlığı `httpMs`: 1 sn uyarı, 3 sn kritik). */
export function msTone(ms) {
  if (ms == null) return 'neutral'
  return ms >= HEALTH_THRESHOLDS.httpMs.crit ? 'crit' : ms >= HEALTH_THRESHOLDS.httpMs.warn ? 'warn' : 'neutral'
}

/** Eşik ihlali sayısı (MiniChart'ın "N ihlal" rozetiyle aynı tanım: uyarı + kritik noktalar). */
export function breaches(values, { warn, crit } = {}) {
  let w = 0
  let c = 0
  for (const v of values || []) {
    if (v == null) continue
    if (crit != null && v >= crit) c++
    else if (warn != null && v >= warn) w++
  }
  return { warn: w, crit: c, total: w + c, tone: c > 0 ? 'crit' : w > 0 ? 'warn' : 'ok' }
}

// ── Biçimler ──────────────────────────────────────────────────────────────────────────────────

const nf = (v, max, locale) => Number(v).toLocaleString(locale, { maximumFractionDigits: max, minimumFractionDigits: 0 })

/** Yerel gruplamalı tam sayı ("184.210" / "184,210"). */
export function fmtInt(v, locale = dateLocale()) {
  return v == null || !Number.isFinite(Number(v)) ? '—' : nf(Math.round(Number(v)), 0, locale)
}

/** Kısa sayı (büyük değerde "12,4 B" yerine tam gruplama yeterli; yalnız ondalıklı oran). */
export function fmtRate(v, locale = dateLocale()) {
  if (v == null || !Number.isFinite(Number(v))) return '—'
  const n = Number(v)
  return nf(n, n < 10 ? 1 : 0, locale)
}

/**
 * Saat:dakika etiketi UYGULAMA yereliyle (TR "14:05", EN-GB "14:05") — tarayıcı yereli DEĞİL: `toLocaleTimeString([])`
 * EN arayüzde bile tarayıcıya göre 12 saatlik "2:05 PM" yazabiliyordu (2026-09-28c ek-8).
 */
export function clockLabel(ms, locale = dateLocale()) {
  return new Date(ms).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' })
}

/** Yüzde, yerel ondalıkla ("%0,8" / "0.8%"). */
export function fmtPct(v, locale = dateLocale()) {
  return v == null || !Number.isFinite(Number(v)) ? '—' : formatPercent(nf(Number(v), 1, locale))
}

/** Süre: 1 sn altı "212 ms", üstü "1,24 s" — birim adları çağırandan (i18n). */
export function makeMs({ ms = 'ms', sec = 's', locale = dateLocale() } = {}) {
  const NBSP = String.fromCharCode(0xa0)
  const value = (v) => {
    if (v == null || !Number.isFinite(Number(v))) return '—'
    const n = Number(v)
    if (n < 1000) return `${Math.round(n)} ${ms}`
    return `${nf(n / 1000, n < 10_000 ? 2 : 1, locale)} ${sec}`
  }
  const axis = (v) => {
    if (v == null || !Number.isFinite(Number(v))) return ''
    const n = Number(v)
    if (n < 1000) return `${Math.round(n)}${NBSP}${ms}`
    return `${nf(n / 1000, 1, locale)}${NBSP}${sec}`
  }
  return { value, axis }
}

// ── Aralık ────────────────────────────────────────────────────────────────────────────────────

/** TimeRangePicker tanımı → dakika (göreli) ya da mutlak aralığın uzunluğu; bozuk → null. */
export function rangeMinutes(range) {
  if (range?.type === 'abs') {
    const a = Date.parse(range.from)
    const b = Date.parse(range.to)
    return Number.isFinite(a) && Number.isFinite(b) ? Math.max(0, Math.round((b - a) / 60000)) : null
  }
  return Number(range?.minutes) || null
}

export function isLiveRange(range) {
  const m = rangeMinutes(range)
  return range?.type !== 'abs' && m != null && m <= LIVE_MAX_MINUTES
}

/** Boş aralıkta "daha geniş aralığı göster" önerisi: bir sonraki hızlı aralık (yoksa null). */
export function widerRange(range) {
  if (range?.type === 'abs') return null
  const i = QUICK_RANGES.findIndex((r) => r.key === range?.key)
  const next = i >= 0 ? QUICK_RANGES[i + 1] : null
  return next ? { type: 'rel', minutes: next.minutes, key: next.key } : null
}

// ── CSV ───────────────────────────────────────────────────────────────────────────────────────

/**
 * Görünen (süzülmüş + sıralı) uç listesinin CSV'si — TÜM sayfalar. Başlıklar i18n; sayılar ham (ms), son görülme
 * UTC ISO (`Z`'li; tabloyu açan kişinin yerel saatine göre değil, makinece okunur). Hücre kaçışı + formül nötrleme
 * `utils/csv.js` (CWE-1236). BOM: Excel UTF-8'i tanısın.
 */
export function endpointCsv(list, t) {
  const head = [t('hreq.csv.method'), t('hreq.csv.path'), t('hreq.col.requests'), t('hreq.col.errors'), t('hreq.col.errorRate'),
    t('hreq.csv.avgMs'), t('hreq.csv.p50Ms'), t('hreq.csv.p95Ms'), t('hreq.csv.p99Ms'), t('hreq.csv.maxMs'),
    '2xx', '3xx', '4xx', '5xx', t('hreq.col.lastSeen')]
  const rows = (list || []).map((e) => [e.method, e.path, e.count, e.errors, e.errorRate, e.avg, e.p50, e.p95, e.p99, e.max,
    e.classes['2xx'], e.classes['3xx'], e.classes['4xx'], e.classes['5xx'], e.lastSeen ? toUtc(e.lastSeen) : ''])
  return String.fromCharCode(0xfeff) + csvRows([head, ...rows])
}

// ── Bölüm (bellek içi 24 saat) ────────────────────────────────────────────────────────────────

/**
 * Küçük grafik için seyreltme: 1440 dakikalık noktayı ~300 px'e basmak "barkod" çizer. En çok `max` dilim; her dilim
 * `k` dakika. Anlam korunur: hacim (başarılı / hatalı) DAKİKA ORTALAMASI (yığın toplamı = ortalama istek/dk), hata
 * grafiği dilimin EN YOĞUN dakikası (`errPeak` — eşik aşımı ortalamada kaybolmasın), süre istek ağırlıklı ortalama,
 * p95 dilimin en yükseği. Eşik ihlal SAYILARI seyreltmeden ÖNCE ham dakikalardan hesaplanır. `span` = dilim (ms).
 */
export function downsample(points, max = 240) {
  const list = points || []
  const k = Math.max(1, Math.ceil(list.length / Math.max(1, max)))
  if (k === 1) return list.map((p) => ({ ...p, span: 60_000, errPeak: p.errors }))
  const r1 = (v) => Math.round(v * 10) / 10
  const out = []
  for (let i = 0; i < list.length; i += k) {
    const g = list.slice(i, i + k)
    const n = g.length
    const withAvg = g.filter((p) => p.avg != null && p.count > 0)
    const w = withAvg.reduce((s, p) => s + p.count, 0)
    out.push({
      t: g[0].t,
      span: (g.at(-1).t - g[0].t) + 60_000,
      count: r1(g.reduce((s, p) => s + p.count, 0) / n),
      ok: r1(g.reduce((s, p) => s + p.ok, 0) / n),
      errors: r1(g.reduce((s, p) => s + p.errors, 0) / n),
      errPeak: g.reduce((m, p) => Math.max(m, p.errors), 0),
      avg: w > 0 ? Math.round(withAvg.reduce((s, p) => s + p.avg * p.count, 0) / w) : null,
      p95: g.some((p) => p.p95 != null) ? g.reduce((m, p) => Math.max(m, p.p95 ?? 0), 0) : null,
    })
  }
  return out
}


/** Bölüm modeli: özet + dakikalık noktalar + (varsa) en yavaş / en çok hata listeleri. */
export function sectionModel(httpMetrics) {
  const s = httpMetrics?.summary || {}
  const total = cnt(s.total_requests)
  const v = (x) => (total > 0 ? num(x) : null)   // istek yokken "0 ms" değil "—"
  const summary = {
    total, errors: cnt(s.total_errors), errorRate: total > 0 ? (num(s.error_rate_pct) ?? 0) : null,
    avg: v(s.avg_ms), max: v(s.max_ms), p95: v(s.p95_ms), p99: v(s.p99_ms),
    reqPerMin: num(s.req_per_min), peak: num(s.peak_req_per_min),
  }
  const points = (Array.isArray(httpMetrics?.history) ? httpMetrics.history : []).map((b) => {
    const t = Date.parse(toUtc(String(b?.ts ?? '')))
    if (!Number.isFinite(t)) return null
    const count = cnt(b.count)
    const errors = Math.min(count, cnt(b.errors))
    return { t, count, errors, ok: count - errors, avg: count > 0 ? num(b.avg_ms) : null, p95: count > 0 ? num(b.p95_ms) : null }
  }).filter(Boolean).sort((a, b) => a.t - b.t)
  const top = httpMetrics?.top_endpoints
  const list = (arr) => (Array.isArray(arr) ? arr.map(normEndpoint).filter(Boolean) : [])
  return {
    summary,
    points,
    top: top && typeof top === 'object' ? { slowest: list(top.slowest), errors: list(top.errors) } : null,
  }
}
