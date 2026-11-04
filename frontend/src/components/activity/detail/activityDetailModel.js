/**
 * Aktivite AYRINTI SAYFASI — saf model (2026-11-05, kullanıcı isteği: "çok daha fazla detay istediğimde ilgili aktivite ile
 * gitmek istiyorum"). Bileşenler yalnız çizer; yanıtın okunması, kontrol satırının sağlığı, önceki ↔ bu ↔ sonraki kıyası,
 * ±24 sa serisi ve bağlantı kararları burada ve testli (`activityDetailModel.test.js`).
 *
 * Kurallar: bilinmeyen / okunamayan asla "sağlıklı" (yeşil) sayılmaz; eski sunucu ve eski satır (yeni alanlar yok) bugünkü
 * görünüme düşer; sunucu metni Türkçe özet taşıyabilir — jetonlar listedeki gibi çevrilir.
 */
import { zonedMs } from '../../../utils/relativeTime.js'
import { activityTarget } from '../../../utils/activityTarget.js'
import { tabDeepLink } from '../../../utils/monitorDeepLink.js'
import { parseHttpDiag } from '../../../utils/httpDiag.js'
import { formatBytes } from '../../../utils/formatBytes.js'
import { formatPercent } from '../../../i18n/dateLocale.js'
import { failureTexts as checkFailureTexts, isHealthy } from '../../checks/checkFailureModel.js'
import { failureTexts as keywordFailureTexts } from '../../keyword/keywordFailureModel.js'

export const TZ = 'Europe/Istanbul'

/** Aktivite türü (BÜYÜK) → kontrol geçmişi türü (küçük; checkFailureModel / geçmiş uçları sözlüğü). */
export const CHECK_TYPE_OF = Object.freeze({
  CERT: 'cert', DOMAIN: 'domain', HTTP: 'http', UPTIME: 'uptime', PORT: 'port', PING: 'ping', DNS: 'dns',
  KEYWORD: 'keyword', PAGE: 'page', PAGESPEED: 'pagespeed', SCRIPTED: 'scripted', DBQUERY: 'dbquery',
})

/** Ortak `CheckFailurePanel` (Neden / Etkisi / Ne yapmalı) ile çizilen türler. Keyword, HTTP ve DB sorgusunun kendi paneli var. */
export const FAILURE_PANEL_TYPES = new Set(['ping', 'port', 'dns', 'page', 'pagespeed', 'uptime', 'domain', 'cert', 'scripted'])

/** Kontrol eylemleri — sayfa bunlarda "Kontrol sonucu" bölümünü çizer. */
export const CHECK_ACTIONS = new Set(['SCHEDULED_CHECK', 'MANUAL_CHECK'])

/** Seri tavanı (sunucu ≤ 300 nokta yollar; bozuk / fazla yanıt grafiği şişirmesin). */
export const MAX_SERIES_POINTS = 600

export const isFailedStatus = (s) => s === 'ERROR' || s === 'TIMEOUT'

const str = (v) => (v == null ? '' : String(v).trim())
const upper = (v) => str(v).toUpperCase()
const num = (v) => {
  if (v === null || v === undefined || v === '' || typeof v === 'boolean') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}
const firstLine = (s) => str(s).split(/\r?\n/, 1)[0].trim()
const plainObject = (v) => (v && typeof v === 'object' && !Array.isArray(v) ? v : null)

/** JSON metni ya da nesne → düz nesne; boş / bozuk / dizi → null. */
export function parseObject(raw) {
  if (raw == null) return null
  if (typeof raw === 'object') return plainObject(raw)
  if (typeof raw !== 'string' || !raw.trim()) return null
  try { return plainObject(JSON.parse(raw)) } catch { return null }
}

/** Kontrol türü: sunucunun `check.type`'ı varsa o (küçük harf), yoksa aktivite türünden. Bilinmeyen tür → null. */
export function checkTypeOf(monitorType, check) {
  const fromCheck = str(check?.type).toLowerCase()
  if (fromCheck) return fromCheck
  return CHECK_TYPE_OF[upper(monitorType)] || null
}

/** Bağlantı hedefi `{tab, params}` → doğrulanmış kopya ya da null (params yalnız ilkel değerler). */
function linkTarget(x) {
  const o = plainObject(x)
  if (!o || typeof o.tab !== 'string' || !o.tab.trim()) return null
  const params = {}
  for (const [k, v] of Object.entries(plainObject(o.params) || {})) {
    if (v == null || v === '' || typeof v === 'object') continue
    params[k] = v
  }
  return { tab: o.tab.trim(), params: Object.keys(params).length ? params : undefined }
}

/**
 * Bağlantılar. Sunucu `links` yolladıysa onlar (kapı sunucuda); eski sunucuda satırdan türetilir — liste ile AYNI hedef
 * kuralı (`utils/activityTarget.js`), geçmiş = izleme penceresinin "Kontrol geçmişi" sekmesi (`mtab=control`),
 * değişiklikler = `mtab=changes`. Eski sunucuda tanılama kapısı bilinmez → düğme çizilmez.
 */
export function normalizeLinks(raw, data) {
  const o = plainObject(raw)
  if (o) {
    return {
      monitor: linkTarget(o.monitor), history: linkTarget(o.history), changes: linkTarget(o.changes), alert: linkTarget(o.alert),
      canDiagnose: o.can_diagnose === true, source: 'server',
    }
  }
  const dest = activityTarget(data)
  const withTab = (mtab) => (dest?.params?.monitor != null ? { tab: dest.tab, params: { ...dest.params, mtab } } : null)
  const alertId = num(data?.alert_event_id)
  return {
    monitor: dest ? { tab: dest.tab, params: dest.params } : null,
    history: withTab('control'), changes: withTab('changes'),
    alert: alertId != null ? { tab: 'alerthistory', params: { alert: alertId } } : null,
    canDiagnose: false, source: 'derived',
  }
}

function normalizeAlarm(raw) {
  const a = plainObject(raw)
  if (!a || num(a.id) == null) return null
  const kind = str(a.resolved_by_kind).toLowerCase()
  return {
    id: num(a.id),
    alert_type: str(a.alert_type) || null,
    level: str(a.level) || null,
    opened_at: str(a.opened_at) || null,
    resolved_at: str(a.resolved_at) || null,
    resolved_by_kind: ['auto', 'manual', 'silent'].includes(kind) ? kind : null,
    acknowledged: a.acknowledged === true,
    storm_id: num(a.storm_id),
    // Belirsiz ya da eksik eşleşme "kesin" sayılmaz (yanlış alarmı kesinmiş gibi göstermek, eksik göstermekten kötü)
    match: a.match === 'exact' ? 'exact' : 'approximate',
    can_open: a.can_open === true,
  }
}

function normalizeChange(raw) {
  const c = plainObject(raw)
  if (!c || !str(c.kind) || num(c.id) == null || num(c.seq) == null) return null
  return { kind: str(c.kind).toLowerCase(), id: num(c.id), seq: num(c.seq) }
}

function normalizeSeries(raw) {
  const s = plainObject(raw)
  if (!s || !Array.isArray(s.points)) return null
  return { from: s.from ?? null, to: s.to ?? null, points: s.points.slice(0, MAX_SERIES_POINTS), truncated: s.truncated === true }
}

/**
 * `GET /api/activity/{id}/detail` gövdesi → sayfa modeli. `data` yoksa null (çağıran okuma hatası gösterir).
 * `failure_detail` metin gelirse nesneye çevrilir (kontrol satırındaki maskeli JSON'un aynısı).
 */
export function normalizeDetail(body) {
  const b = plainObject(body)
  const data0 = plainObject(b?.data)
  if (!data0) return null
  const data = { ...data0 }
  const fd = parseObject(data.failure_detail)
  data.failure_detail = fd
  const chk = plainObject(b.check)
  const check = chk && plainObject(chk.row)
    ? { type: checkTypeOf(data.monitor_type, chk), match: chk.match === 'exact' ? 'exact' : 'approximate', row: chk.row }
    : null
  const actor = plainObject(b.actor)
  return {
    legacy: false,
    data,
    check,
    previous: plainObject(b.previous_check),
    next: plainObject(b.next_check),
    series: normalizeSeries(b.series),
    alarm: normalizeAlarm(b.alarm),
    change: normalizeChange(b.change),
    links: normalizeLinks(b.links, data),
    actor: { name: str(actor?.name) || str(data.actor) || null, requestId: str(actor?.request_id) || str(data.request_id) || null },
    recent: [],
  }
}

/** Eski sunucu (`/detail` yok): temel uç `GET /api/activity/{id}` → yalnız satır + mini geçmiş; bağlantılar türetilir. */
export function legacyDetail(body) {
  const b = plainObject(body)
  const data = plainObject(b?.data)
  if (!data) return null
  return {
    legacy: true,
    data: { ...data, failure_detail: parseObject(data.failure_detail) },
    check: null, previous: null, next: null, series: null, alarm: null, change: null,
    links: normalizeLinks(null, data),
    actor: { name: str(data.actor) || null, requestId: str(data.request_id) || null },
    recent: Array.isArray(b.recent) ? b.recent.filter((r) => plainObject(r)) : [],
  }
}

// ── Kontrol satırı okuma ──────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Kontrol satırının sağlığı: `up` | `warn` | `down` | `unknown`. Satırda türün durum alanı YOKSA bilinmiyor — asla "up".
 */
export function rowHealth(type, row) {
  const r = plainObject(row)
  if (!r) return 'unknown'
  switch (type) {
    case 'dbquery': {
      const s = upper(r.status)
      if (s === 'OK') return 'up'
      if (s === 'BREACH') return upper(r.breach_level) === 'WARNING' ? 'warn' : 'down'
      if (s === 'FAIL') return 'down'
      return 'unknown'
    }
    case 'domain': {
      const s = upper(r.status)
      if (!s) return 'unknown'
      if (s === 'OK') return 'up'
      if (s === 'WARNING' || s === 'CRITICAL') return 'warn'
      return 'down'
    }
    case 'cert': {
      const s = str(r.status).toLowerCase()
      if (s === 'valid') return 'up'
      if (s === 'warning' || s === 'critical') return 'warn'
      if (s === 'expired' || s === 'error') return 'down'
      return 'unknown'
    }
    case 'scripted': {
      const s = upper(r.status)
      if (s === 'PASS') return 'up'
      if (s === 'FAIL' || s === 'ERROR' || s === 'TIMEOUT') return 'down'
      if (s === 'NO_CHECKS') return 'warn'
      if (!s && typeof r.ok === 'boolean') return r.ok ? 'up' : 'down'
      return 'unknown'
    }
    case 'keyword':
    case 'http':
    case 'pagespeed':
      return r.ok === true ? 'up' : r.ok === false ? 'down' : 'unknown'
    case 'ping': return typeof r.up === 'boolean' ? (r.up ? 'up' : 'down') : 'unknown'
    case 'port': return typeof r.open === 'boolean' ? (r.open ? 'up' : 'down') : 'unknown'
    case 'uptime': {
      const s = str(r.status).toLowerCase()
      return !s ? 'unknown' : s === 'up' ? 'up' : 'down'
    }
    case 'dns':
      if (r.value === undefined && r.error === undefined) return 'unknown'
      return isHealthy('dns', r) ? 'up' : 'down'
    case 'page':
      if (r.status === undefined && r.ok === undefined) return 'unknown'
      return isHealthy('page', r) ? 'up' : 'down'
    default:
      return typeof r.ok === 'boolean' ? (r.ok ? 'up' : 'down') : 'unknown'
  }
}

/** Yanıt süresi (ms) — türün alanı; yoksa null. */
export function rowResponseMs(type, row) {
  const r = plainObject(row)
  if (!r) return null
  const keys = type === 'ping' ? ['rtt_ms', 'response_ms'] : ['response_ms', 'duration_ms', 'load_time_ms', 'total_time_ms', 'elapsed_ms']
  for (const k of keys) {
    const v = num(r[k])
    if (v != null) return v
  }
  return null
}

/** HTTP durum kodu (varsa). */
export function rowHttpStatus(row) {
  const r = plainObject(row)
  if (!r) return null
  return num(r.http_status) ?? num(r.status_code)
}

/** Kontrol anı. */
export function rowCheckedAt(row) {
  const r = plainObject(row)
  if (!r) return null
  return str(r.checked_at) || str(r.created_at) || str(r.activity_time) || null
}

/**
 * Sorunlu satırın kısa nedeni `{ code, short, tone }` (sağlıklı / bilinmeyen satırda null). Ortak teşhis modeli, keyword ve
 * HTTP'nin kendi modelleri kullanılır — kıyas hücresinde liste ile aynı kısa ad görünür.
 */
export function rowReason(type, row, monitor, t) {
  const r = plainObject(row)
  if (!r) return null
  const health = rowHealth(type, r)
  if (health === 'up' || health === 'unknown') return null
  if (FAILURE_PANEL_TYPES.has(type)) {
    const f = checkFailureTexts(type, r, monitor, t)
    return f ? { code: f.code, short: f.short, tone: f.tone } : null
  }
  if (type === 'keyword') {
    const f = keywordFailureTexts(r, monitor, t)
    return f ? { code: f.code, short: f.short, tone: f.tone } : null
  }
  if (type === 'http') {
    const d = parseHttpDiag(r)
    if (d?.kind) return { code: d.kind, short: t('httpdiag.kind.' + d.kind), tone: 'danger' }
    const status = rowHttpStatus(r)
    if (firstLine(r.error)) return { code: 'ERROR', short: firstLine(r.error), tone: 'danger' }
    if (status != null) return { code: 'HTTP_STATUS', short: `HTTP ${status}`, tone: 'danger' }
    return null
  }
  if (type === 'dbquery') {
    const msg = firstLine(r.error_message) || firstLine(r.error)
    const code = str(r.failure_reason) || upper(r.status) || 'UNKNOWN'
    return { code, short: msg || code, tone: health === 'warn' ? 'warning' : 'danger' }
  }
  return null
}

/**
 * Kontrol satırı bulunamadığında aktivitenin KENDİ hata alanlarından (yeni kolonlar: failure_reason, failure_detail,
 * http_status) teşhis paneline verilecek satır. Yalnız ERROR / ZAMAN AŞIMI aktivitede; aksi null. `pseudo: true`
 * işaretlidir (sayfa "aktivite kaydından" notu gösterir).
 */
export function pseudoCheck(type, a) {
  if (!a || !isFailedStatus(a.result_status)) return null
  const base = {
    pseudo: true, ok: false,
    failure_reason: str(a.failure_reason) || null,
    failure_detail: a.failure_detail ?? null,
    error: str(a.error_message) || null,
    http_status: num(a.http_status),
    response_ms: num(a.response_ms),
    checked_at: a.activity_time ?? null,
  }
  const host = hostOfTarget(a.target)
  switch (type) {
    case 'ping': return { ...base, up: false }
    case 'port': return { ...base, open: false }
    case 'uptime': return { ...base, status: 'down', domain: host || null }
    case 'dns': return { ...base, value: '' }
    case 'page': return { ...base, status: 'FAIL' }
    case 'pagespeed': return { ...base, error_message: base.error }
    case 'domain': return { ...base, status: 'UNKNOWN' }
    case 'cert': return { ...base, status: 'error', error_class: str(a.error_class) || null, domain: host || null }
    case 'scripted': return { ...base, status: a.result_status === 'TIMEOUT' ? 'TIMEOUT' : 'ERROR' }
    case 'keyword': return { ...base, found: false }
    case 'http': return { ...base, error_detail: a.failure_detail ?? null }
    case 'dbquery': return { ...base, status: 'FAIL', error_message: base.error }
    default: return base
  }
}

/** Hedef metninden ana bilgisayar ("https://a.example.com:8443/x" → "a.example.com"). */
export function hostOfTarget(target) {
  const s = str(target)
  if (!s) return ''
  const noScheme = s.replace(/^[a-z][a-z0-9+.-]*:\/\//i, '')
  const authority = noScheme.split(/[/?#]/, 1)[0]
  if (authority.startsWith('[')) return authority.slice(0, authority.indexOf(']') + 1) || authority
  return authority.split(':')[0]
}

/**
 * Teşhis metinlerinin ve tanılama penceresinin beklediği izleme satırı — sunucu izlemenin kendisini döndürmez; aktivitenin
 * hedefinden kurulur (yalnız ad, hedef ve kimlik; eksik alanlar pencerelerde "—" okunur).
 */
export function pseudoMonitor(type, a) {
  const id = a?.monitor_id ?? null
  const name = a?.monitor_name ?? null
  const target = str(a?.target)
  switch (type) {
    case 'http':
    case 'keyword':
    case 'page':
    case 'pagespeed':
      return { id, name, url: target || null }
    case 'ping': return { id, name, host: target || null }
    case 'port': {
      const m = /^(\[[^\]]+\]|[^:]+)(?::(\d{1,5}))?$/.exec(target)
      return { id, name, host: m ? m[1] : (target || null), port: m && m[2] ? Number(m[2]) : null }
    }
    case 'dns':
    case 'domain':
      return { id, name, domain: target || null }
    case 'cert':
    case 'uptime':
      return { id, name, domain: hostOfTarget(target) || null }
    default:
      return { id, name }
  }
}

/** Uçtan uca tanılama penceresinin türü — penceresi olmayan türde null (sayfa izleme ayrıntısına bağlanır). */
export function diagnoseKindOf(type) {
  return ['http', 'keyword', 'page', 'pagespeed', 'ping', 'port', 'dns'].includes(type) ? type : null
}

// ── Kıyas: önceki ↔ bu ↔ sonraki ────────────────────────────────────────────────────────────────────────────────────

const PROBLEM = new Set(['down', 'warn'])

/**
 * İki ardışık kontrolün geçişi: `started` (sorun bu kontrolde başladı), `continues` (sürüyor), `recovered` (düzeldi),
 * `stable` (ikisi de sağlıklı). Biri bilinmiyorsa null — tahmin yok.
 */
export function transitionOf(before, after) {
  if (!before || !after || before === 'unknown' || after === 'unknown') return null
  const b = PROBLEM.has(before), a = PROBLEM.has(after)
  if (!b && a) return 'started'
  if (b && a) return 'continues'
  if (b && !a) return 'recovered'
  return 'stable'
}

/**
 * Kıyas modeli — her hücre `{ key, row, at, health, ms, http, reason, activityId, delta, httpChanged, reasonChanged }`
 * (satır yoksa `row: null`). `delta`: bu hücrenin bir önceki hücreye göre yanıt süresi farkı (ms; önceki hücrede yok).
 * `transition`: önceki → bu; `after`: bu → sonraki.
 */
export function compareModel({ type, current, currentCell = null, previous, next, monitor, t }) {
  const cell = (key, row) => {
    const r = plainObject(row)
    if (!r) return { key, row: null }
    return {
      key, row: r, at: rowCheckedAt(r), health: rowHealth(type, r), ms: rowResponseMs(type, r), http: rowHttpStatus(r),
      reason: rowReason(type, r, monitor, t), activityId: num(r.activity_id),
    }
  }
  const prev = cell('prev', previous)
  const cur = currentCell ? { ...currentCell, key: 'cur' } : cell('cur', current)
  const nxt = cell('next', next)
  const link = (a, b) => {
    if (!a.row || !b.row) return
    b.delta = a.ms != null && b.ms != null ? b.ms - a.ms : null
    b.httpChanged = a.http != null && b.http != null && a.http !== b.http
    b.reasonChanged = (a.reason?.code || null) !== (b.reason?.code || null) && (a.reason != null || b.reason != null)
  }
  link(prev, cur)
  link(cur, nxt)
  return {
    cells: [prev, cur, nxt],
    transition: prev.row && cur.row ? transitionOf(prev.health, cur.health) : null,
    after: cur.row && nxt.row ? transitionOf(cur.health, nxt.health) : null,
  }
}

/** Aktivite sonuç durumu → sağlık (kontrol satırı yokken "bu kontrol" hücresi için). */
export function activityHealth(status) {
  return { SUCCESS: 'up', WARNING: 'warn', ERROR: 'down', TIMEOUT: 'down' }[upper(status)] || 'unknown'
}

/**
 * Kontrol satırı eşleşmediğinde "bu kontrol" hücresi aktivitenin kendi alanlarından: zaman, durum, yanıt süresi, HTTP kodu
 * ve (hatalıysa) aktivitedeki hata nedeninden kısa ad.
 */
export function activityCell(type, a, monitor, t) {
  if (!a) return null
  const pseudo = pseudoCheck(type, a)
  return {
    row: { pseudo: true }, at: a.activity_time ?? null, health: activityHealth(a.result_status),
    ms: num(a.response_ms), http: num(a.http_status), reason: pseudo ? rowReason(type, pseudo, monitor, t) : null, activityId: null,
  }
}

/** "+450 ms" / "−120 ms" / "±0 ms" (eksi işareti U+2212). */
export function formatDelta(ms) {
  const n = num(ms)
  if (n == null) return ''
  const r = Math.round(n)
  if (r === 0) return '±0 ms'
  return `${r > 0 ? '+' : '−'}${Math.abs(r)} ms`
}

// ── ±24 sa seri ─────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Seri → grafik modeli. Nokta `{ x (epoch ms), ms | null, status, fail, warn, failY, lone }`: `ms` null = boş kova (grafikte
 * BOŞLUK, sıfır değil); `failY` = hatalı noktanın işaret yüksekliği (ms yoksa taban 0); `lone` = iki yanı boş tek ölçüm
 * (çizgisi olmayan nokta — ayrıca nokta olarak çizilir). Sayımlar durum bazında; ortalama / en yüksek yalnız ölçülenlerden.
 */
export function seriesModel(series, anchorIso) {
  const raw = Array.isArray(series?.points) ? series.points.slice(0, MAX_SERIES_POINTS) : []
  const pts = raw
    .map((p) => ({ x: zonedMs(p?.t), ms: num(p?.ms), status: upper(p?.status) }))
    .filter((p) => Number.isFinite(p.x))
    .sort((a, b) => a.x - b.x)
  const counts = { total: 0, up: 0, warn: 0, fail: 0, unknown: 0 }
  let sum = 0, measured = 0, max = null
  const points = pts.map((p, i) => {
    const fail = isFailedStatus(p.status)
    const warn = p.status === 'WARNING'
    if (p.status) {
      counts.total++
      if (fail) counts.fail++
      else if (warn) counts.warn++
      else if (p.status === 'SUCCESS') counts.up++
      else counts.unknown++
    }
    if (p.ms != null) { sum += p.ms; measured++; max = max == null ? p.ms : Math.max(max, p.ms) }
    const prevMs = i > 0 ? pts[i - 1].ms : null
    const nextMs = i < pts.length - 1 ? pts[i + 1].ms : null
    return {
      x: p.x, ms: p.ms, status: p.status || 'UNKNOWN', fail, warn,
      failY: fail ? (p.ms ?? 0) : null,
      lone: p.ms != null && prevMs == null && nextMs == null,
    }
  })
  const from = Number.isFinite(zonedMs(series?.from)) ? zonedMs(series.from) : (points[0]?.x ?? null)
  const to = Number.isFinite(zonedMs(series?.to)) ? zonedMs(series.to) : (points.at(-1)?.x ?? null)
  const anchor = zonedMs(anchorIso)
  return {
    points, counts, from, to,
    anchor: Number.isFinite(anchor) ? anchor : null,
    avgMs: measured ? Math.round(sum / measured) : null,
    maxMs: max,
    measured,
    truncated: series?.truncated === true,
    empty: points.length === 0,
  }
}

// ── Biçim ───────────────────────────────────────────────────────────────────────────────────────────────────────────

/** İstanbul saatiyle tarih + saat (sunucu damgası bölgesiz UTC). Çözülemezse ham değer / "—". */
export function formatIstanbul(value, locale = 'tr-TR', opts = {}) {
  const ms = typeof value === 'number' ? value : zonedMs(value)
  if (!Number.isFinite(ms)) return value ? String(value) : '—'
  try {
    return new Intl.DateTimeFormat(locale, {
      year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
      hour12: false, timeZone: TZ, ...opts,
    }).format(new Date(ms))
  } catch {
    return new Date(ms).toISOString()
  }
}

/** Yalnız saat:dakika (eksen işareti). */
export function formatHourMinute(ms, locale = 'tr-TR') {
  return formatIstanbul(ms, locale, { year: undefined, month: undefined, day: undefined, second: undefined })
}

/** Eylem etiketi: `act.ac.<EYLEM>`; sözlükte yoksa (yeni sunucu eylemi) ham kod — ham anahtar ekrana düşmez. */
export function actionLabel(action, t) {
  const a = str(action)
  if (!a) return '—'
  const key = 'act.ac.' + a
  const v = t(key)
  return v && v !== key ? v : a
}

/** Durum etiketi: `act.st.<DURUM>`; bilinmeyen kod → "Bilinmiyor". */
export function statusLabel(status, t) {
  const key = 'act.st.' + upper(status)
  const v = t(key)
  return v && v !== key ? v : t('act.st.UNKNOWN')
}

/**
 * Sunucu özeti yalnız Türkçe üretir ("0 kırık · 12ms", "3 istek", "yüklenemedi") — listedeki (ActivityLog) aynı jeton
 * çevirisi. Türkçede sözlük değeri jetonun kendisidir.
 */
const SUMMARY_TOKENS = [['yapılandırma hatası', 'act.tok.configError'], ['yüklenemedi', 'act.tok.loadFailed'],
  ['zaman aşımı', 'act.tok.timeout'], ['kırık', 'act.tok.broken'], ['istek', 'act.tok.requests']]
export function localizeSummary(summary, t) {
  if (!summary) return summary
  let s = String(summary)
  for (const [tr, key] of SUMMARY_TOKENS) s = s.split(tr).join(t(key))
  return s
}

/**
 * Başarılı (ya da hatası teşhis paneline sığmayan) kontrolün ana ölçüleri — yalnız KAYITTA olan değerler, sırayla.
 * @returns {Array<{key: string, value: string, mono?: boolean}>} `act.dt.m.<key>` etiketleri
 */
export function measuresOf(type, row, t) {
  const r = plainObject(row)
  if (!r) return []
  const out = []
  const push = (key, value, extra = {}) => { if (value != null && value !== '') out.push({ key, value: String(value), ...extra }) }
  const status = rowHttpStatus(r)
  if (status != null) push('httpStatus', `HTTP ${status}`)
  const ms = rowResponseMs(type, r)
  if (ms != null) push('response', `${Math.round(ms)} ms`)
  if (type === 'ping') { const l = num(r.packet_loss); if (l != null) push('packetLoss', formatPercent(l)) }
  const days = num(r.days_remaining)
  if (days != null) push('days', t('act.days', days))
  if (type === 'cert') push('tls', str(r.tls_version) || null)
  if (type === 'dns') { push('recordType', str(r.record_type) || null); push('value', str(r.value) || null, { mono: true }) }
  if (type === 'keyword') { const o = num(r.occurrences); if (o != null) push('occurrences', o) }
  if (type === 'page') {
    const b = num(r.broken_resources); if (b != null) push('broken', b)
    const tot = num(r.total_resources); if (tot != null) push('totalResources', tot)
  }
  if (type === 'pagespeed') {
    const bytes = num(r.total_bytes); if (bytes != null) push('bytes', formatBytes(bytes))
    const req = num(r.request_count); if (req != null) push('requests', req)
  }
  if (type === 'dbquery') {
    const rows = num(r.row_count); if (rows != null) push('rows', rows)
    const mv = num(r.metric_value); if (mv != null) push('metric', mv)
  }
  return out
}

/** Paylaşılabilir mutlak adres: `?tab=activity&act=<id>`. */
export function activityDetailHref(id) {
  return tabDeepLink('activity', { act: id })
}
