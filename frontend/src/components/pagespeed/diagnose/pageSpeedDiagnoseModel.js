/**
 * Sayfa Hızı uçtan uca tanılama MODELİ — saf fonksiyonlar, React yok (2026-10-05). Genel kısım (hüküm, yollar, zamanlama,
 * döküm) HTTP tanılamasının modelinden (`httpDiagnoseModel.js`; kod çevirisi `psdx` ad alanını bilir); burada "NEDEN YAVAŞ"
 * çözümlemesine özgü olanlar: eşik ↔ ölçülen satırları (çubuk oranlarıyla), yol başına faz satırları, kaynak listeleri,
 * eşik özeti ve rapor bölümü.
 */
import { formatBytes, routeText, pathTitle } from '../../http/diagnose/httpDiagnoseModel.js'

const isNum = (v) => typeof v === 'number' && Number.isFinite(v)
const arr = (v) => (Array.isArray(v) ? v : [])

/** Eşik metrikleri — sunucunun sırası (`PageSpeedDiagFindings.METRICS`) ve mevcut etiketler. */
export const METRIC_KEYS = ['LOAD', 'TTFB', 'SIZE', 'REQUESTS']
export const METRIC_LABEL = {
  LOAD: 'pspd.metricLoad', TTFB: 'pspd.metricTtfb', SIZE: 'pspd.metricSize', REQUESTS: 'pspd.metricRequests',
}
/** İzleme satırındaki eşik alanları (snake_case). */
const THRESHOLD_FIELD = { LOAD: 'max_load_ms', TTFB: 'max_ttfb_ms', SIZE: 'max_page_kb', REQUESTS: 'max_requests' }
const THRESHOLD_UNIT = { LOAD: 'ms', TTFB: 'ms', SIZE: 'KB', REQUESTS: 'req' }

/** Faz anahtarları (zaman çizelgesi `<key>_ms`) — sunucunun `phase_limits` sözlüğüyle aynı ad. */
export const PHASE_KEYS = ['dns', 'connect', 'proxy', 'tls', 'ttfb', 'download']

/** Kayıt durumu → mevcut etiket + ton. */
export const STATUS_KEY = { OK: 'pspd.statusOk', SLOW: 'pspd.statusSlow', DOWN: 'pspd.statusDown', CONFIG_ERROR: 'pspd.statusConfigError' }
export function statusTone(status) {
  if (status === 'OK') return 'success'
  if (status === 'SLOW') return 'warning'
  if (status === 'DOWN' || status === 'CONFIG_ERROR') return 'danger'
  return 'muted'
}

/** Bir ölçüyü birimiyle okunur yazar: ms → "9000 ms", KB → "4.0 MB", istek → "120". */
export function formatValue(value, unit) {
  if (!isNum(value)) return '—'
  if (unit === 'ms') return `${value} ms`
  if (unit === 'KB') return formatBytes(value * 1024)
  return String(value)
}

/**
 * Eşik ↔ ölçülen satırları — çubuk oranlarıyla: ölçek = max(değer, sınır) × 1,15 (sınır işareti çubuğun içinde kalsın);
 * sınır yoksa ölçek değerin kendisi ve işaret yok. `pct` / `limitPct` 0–100.
 */
export function metricRows(ps) {
  return arr(ps?.metrics).filter((m) => m && METRIC_KEYS.includes(m.key)).map((m) => {
    const value = isNum(m.value) ? m.value : null
    const limit = isNum(m.limit) && m.limit > 0 ? m.limit : null
    const scale = Math.max(value ?? 0, limit ?? 0) * (limit ? 1.15 : 1) || 1
    return {
      key: m.key, value, limit, unit: m.unit || THRESHOLD_UNIT[m.key], breached: m.breached === true,
      over: isNum(m.over) ? m.over : null,
      pct: value == null ? 0 : Math.min(100, Math.round((value / scale) * 1000) / 10),
      limitPct: limit == null ? null : Math.min(100, Math.round((limit / scale) * 1000) / 10),
    }
  })
}

/**
 * Yolun faz satırları (ana belge): ölçülmüş fazlar, yol gösterici sınırla. Uygulanmayan faz (vekilsiz yolda vekil, düz
 * HTTP'de TLS — sunucu 0 yazar) gösterilmez. `pct` sınıra göre (sınırın 1,5 katı tam çubuk).
 */
export function phaseRows(route, limits) {
  const out = []
  for (const k of PHASE_KEYS) {
    const ms = route?.[`${k}_ms`]
    if (!isNum(ms)) continue
    if ((k === 'proxy' || k === 'tls') && ms === 0) continue
    const limit = isNum(limits?.[`${k}_ms`]) ? limits[`${k}_ms`] : null
    const scale = limit ? limit * 1.5 : Math.max(ms, 1)
    out.push({ key: k, ms, limit, over: limit != null && ms > limit, pct: Math.min(100, Math.round((ms / scale) * 1000) / 10) })
  }
  return out
}

/** Kaynak satırları (en ağır / en yavaş). */
export function resourceRows(list) {
  return arr(list).filter(Boolean).map((r, i) => ({
    i, url: r.url == null ? '' : String(r.url), type: r.type || 'OTHER', bytes: isNum(r.bytes) ? r.bytes : null,
    ms: isNum(r.ms) ? r.ms : null, status: isNum(r.status) ? r.status : null, thirdParty: r.third_party === true,
    failed: r.failed === true, truncated: r.truncated === true,
  }))
}

/** İzlemenin eşik özeti — hedef şeridi ve başlangıç ekranı için `[{ key, text }]` (tanımsız eşik yok sayılır). */
export function thresholdChips(monitor, t) {
  const th = monitor?.thresholds || monitor || {}
  const out = []
  for (const k of METRIC_KEYS) {
    const v = th[THRESHOLD_FIELD[k]]
    if (!isNum(v) || v <= 0) continue
    out.push({ key: k, text: `${t(METRIC_LABEL[k])} ≤ ${formatValue(v, THRESHOLD_UNIT[k])}` })
  }
  return out
}

/** Raporun Sayfa Hızı bölümü (`buildReport` `extra`). */
export function pageSpeedReportExtra({ w, data, t }) {
  const ps = data?.pagespeed
  if (!ps) return
  w.h2(t('psdx.analysis.title'))
  const m = ps.measured
  if (m?.status) w.kv(t('psdx.analysis.recorded'), t(STATUS_KEY[m.status] || 'pspd.statusUnknown'))
  if (ps.analyzed === false) {
    w.p(t('psdx.analysis.unanalyzed'))
    return
  }
  for (const r of metricRows(ps)) {
    w.li(`${t(METRIC_LABEL[r.key])}: ${formatValue(r.value, r.unit)} / ${r.limit != null ? formatValue(r.limit, r.unit) : t('psdx.metric.unset')}`
      + (r.breached ? ` — ${t('psdx.metric.breached')}${r.over != null ? ` (+${formatValue(r.over, r.unit)})` : ''}` : ''))
  }
  for (const route of arr(ps.routes)) {
    w.h3(`${t('psdx.analysis.phases')} — ${pathTitle(route.key, t)} · ${routeText(route.route, t)}`)
    const rows = phaseRows(route, ps.phase_limits)
    w.p(rows.map((p) => `${t(`httpdx.timing.${p.key}`)} ${p.ms} ms${p.over ? ' (!)' : ''}`).join(' · ') || '—')
    if (isNum(route.measured_total_ms)) w.p(t('psdx.analysis.measuredTotal', route.measured_total_ms))
  }
  const heavy = resourceRows(ps.heaviest)
  if (heavy.length) {
    w.h3(t('psdx.analysis.heaviest'))
    for (const r of heavy) w.li(`${r.type} · ${formatBytes(r.bytes)} · ${r.ms ?? '—'} ms — ${r.url}`)
  }
  const slow = resourceRows(ps.slowest)
  if (slow.length) {
    w.h3(t('psdx.analysis.slowest'))
    for (const r of slow) w.li(`${r.type} · ${r.ms ?? '—'} ms · ${formatBytes(r.bytes)} — ${r.url}`)
  }
}
