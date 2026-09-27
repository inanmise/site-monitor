/**
 * Sistem Sağlığı türetimleri — SAF fonksiyonlar, React yok (2026-09-27 yeniden tasarım).
 *
 * Genel durum bandı, KPI ızgarası, bölüm başlığı rozetleri ve entegrasyon kartları AYNI hesaptan beslenir;
 * seviye kararı bir yerde verilir, ekran yalnız çizer. Seviyeler: `ok` · `warn` (bozulma) · `down` (kritik) ·
 * `off` (bilinçli kapalı) · `unknown` (veri yok). Kritik olan: heartbeat/tarama/ağ alarmı, RDAP kaynağının
 * tamamen düşmesi, heap ≥ %85 (tek pod, OOM = kesinti), HTTP hata oranı > %5, push başarı oranı < %90.
 */
import { toUtc } from '../../../utils/localDay.js'

export const SECTION_KEYS = ['sys', 'sched', 'db', 'integrations', 'heartbeat', 'releases', 'users', 'http']

/** Grafik eşikleri (2026-09-12, #23) — tek pod / 100 eşzamanlı kullanıcı kabulü. */
export const HEALTH_THRESHOLDS = {
  cpu:     { warn: 70, crit: 85 },     // %
  heap:    { warn: 75, crit: 90 },     // %
  httpMs:  { warn: 1000, crit: 3000 }, // ms ortalama
  httpErr: { warn: 1, crit: 5 },       // hata / dk
  dbMs:    { warn: 200, crit: 500 },   // ms ortalama sorgu
}
export const MEMORY_PCT = { warn: 65, crit: 85 }
export const POOL_PCT = { warn: 80 }
export const HTTP_ERR_PCT = { warn: 1, crit: 5 }
export const PUSH_RATE = { warn: 95, crit: 90 }   // yüzde
export const SMTP_RATE = { warn: 99, crit: 95 }   // yüzde (kart tonu); alarm bayrağı sunucudan

const RANK = { ok: 0, off: 0, unknown: 0, warn: 1, down: 2 }

/** En kötü seviye (off/unknown = ok ile aynı ağırlık). */
export function worstLevel(levels) {
  let w = 'ok'
  for (const l of levels || []) if ((RANK[l] ?? 0) > RANK[w]) w = l
  return w
}

/** Seviye → KpiCard tonu (`ok|warn|danger`) ve ToneBadge tonu. */
export const KPI_TONE = { ok: 'ok', warn: 'warn', down: 'danger' }
export const BADGE_TONE = { ok: 'success', warn: 'warning', down: 'danger', off: 'muted', unknown: 'muted' }

function pctLevel(pct, { warn, crit }) {
  if (pct == null || Number.isNaN(Number(pct))) return 'unknown'
  return pct >= crit ? 'down' : pct >= warn ? 'warn' : 'ok'
}

/** Webhook push özeti → oran YÜZDE (eski kart 0–1 oranını "%" diye basıyordu: "0.98765…%"). */
export function pushStats(kpi) {
  const k = kpi || {}
  const sent = k.sent || 0, failed = k.failed || 0
  const attempted = sent + failed
  const rate = attempted > 0 ? Math.round((sent / attempted) * 1000) / 10 : null
  const queued = (k.pending || 0) + (k.blocked || 0)
  const level = rate == null ? 'unknown' : rate < PUSH_RATE.crit ? 'down' : rate < PUSH_RATE.warn ? 'warn' : 'ok'
  return { sent, failed, attempted, queued, rate, level }
}

/** SMTP: seçili periyot verisi + seviye (alarm sunucudan; oran %80'in altına inmişse kritik). */
export function smtpStats(smtp, period) {
  const data = smtp?.periods?.[period] ?? smtp ?? {}
  const rate = data.rate ?? 100
  const alarm = !!data.alarm
  const level = alarm ? (rate < 80 ? 'down' : 'warn') : 'ok'
  const rateTone = rate >= SMTP_RATE.warn ? 'ok' : rate >= SMTP_RATE.crit ? 'warn' : 'down'
  return { data, rate, alarm, level, rateTone, sent: data.sent ?? 0, attempted: data.attempted ?? data.total ?? 0 }
}

const LDAP_LEVEL = { ok: 'ok', warn: 'warn', bad: 'down', off: 'off' }

/**
 * Entegrasyon listesi (sırası ekranın sırası). LDAP yalnız yapılandırma sağlığı (global admin ucu) geldiyse.
 * @returns {{key:string, level:string, data:object}[]}
 */
export function integrationList({ health, pushKpi, smtpPeriod = '7d', configChecks = null }) {
  const h = health || {}
  const out = []
  const smtp = smtpStats(h.smtp, smtpPeriod)
  out.push({ key: 'smtp', level: smtp.level, data: smtp })
  const push = pushStats(pushKpi)
  out.push({ key: 'push', level: push.level, data: push })
  if (h.weekly_availability) {
    const w = h.weekly_availability
    const level = w.error ? 'unknown' : !w.enabled ? 'off' : (w.last_run_failed || 0) > 0 ? 'warn' : 'ok'
    out.push({ key: 'weekly', level, data: w })
  }
  if (h.domain_expiry) {
    const d = h.domain_expiry
    const src = d.source || 'IDLE'
    const level = d.alarm || src === 'NONE' ? 'down' : src === 'RDAP' ? 'ok' : src === 'FALLBACK' ? 'warn' : 'unknown'
    out.push({ key: 'domain', level, data: d })
  }
  if (h.network) out.push({ key: 'network', level: h.network.alarm ? 'down' : 'ok', data: h.network })
  const ldap = Array.isArray(configChecks) ? configChecks.find((c) => c?.key === 'ldap') : null
  if (ldap) out.push({ key: 'ldap', level: LDAP_LEVEL[ldap.status] || 'unknown', data: ldap })
  if (h.cleanup) {
    const c = h.cleanup
    const level = c.error ? 'unknown' : c.hold_active ? 'off' : (c.alarm || c.never_run) ? 'warn' : 'ok'
    out.push({ key: 'cleanup', level, data: c })
  }
  return out
}

/** Yürütücü havuzu durumu. */
export function executorStats(ex) {
  if (!ex) return null
  const queue = ex.queue_size ?? 0, cap = Math.max(1, ex.queue_capacity ?? 1)
  const saturated = !!ex.saturated || queue > cap * 0.8
  const callerRuns = ex.caller_runs ?? 0
  const level = saturated || callerRuns > 0 ? 'warn' : queue > cap * 0.5 ? 'warn' : 'ok'
  return { queue, cap, saturated, callerRuns, active: ex.active_count ?? 0, pool: ex.pool_size ?? 0,
    core: ex.core_pool_size ?? 0, max: ex.max_pool_size ?? 0, completed: ex.completed_tasks ?? 0, level }
}

/** DB bağlantı havuzu durumu. */
export function poolStats(pool, dbMs) {
  if (!pool || pool.error) return null
  const max = pool.max_size ?? 0, active = pool.active ?? 0
  const pct = max > 0 ? Math.round((active / max) * 100) : 0
  const waiting = pool.waiting ?? 0
  const level = waiting > 0 ? 'warn' : pct >= POOL_PCT.warn ? 'warn' : 'ok'
  const msLevel = dbMs == null ? 'unknown' : dbMs < 0 ? 'down' : dbMs >= HEALTH_THRESHOLDS.dbMs.crit ? 'warn' : 'ok'
  return { active, idle: pool.idle ?? 0, total: pool.total ?? 0, waiting, max, pct, level: worstLevel([level, msLevel]), ms: dbMs ?? null }
}

/**
 * Genel durum: sebep listesi (seviye + i18n kodu + argümanlar + hedef bölüm) ve en kötü seviye.
 * Kodlar ekranda `REASON_TEXT` ile metne çevrilir (HealthStatusBanner).
 */
export function deriveOverall({ health, httpMetrics, pushKpi, smtpPeriod = '7d', configChecks = null, loadErrors = {} }) {
  const h = health || {}
  const reasons = []
  const add = (level, code, section, args = []) => reasons.push({ level, code, section, args })

  if (loadErrors.health) add('down', 'loadHealth', null)
  if (h.heartbeat?.alarm) add('down', 'hbAlarm', 'heartbeat')
  if (h.scan_alarm) add('down', 'scanAlarm', 'sched')
  if (h.network?.alarm) add('down', 'networkAlarm', 'integrations')

  const memPct = h.memory?.used_pct
  const memLevel = h.memory ? pctLevel(memPct, MEMORY_PCT) : 'unknown'
  if (memLevel === 'warn' || memLevel === 'down') add(memLevel, 'memory', 'sys', [memPct])

  const ex = executorStats(h.executor_pool)
  if (ex?.saturated) add('warn', 'queueSaturated', 'sched')
  if (ex && ex.callerRuns > 0) add('warn', 'callerRuns', 'sched', [ex.callerRuns])
  const pool = poolStats(h.pool, h.db_ms)
  if (pool && pool.waiting > 0) add('warn', 'poolWaiting', 'sched', [pool.waiting])
  if (pool && pool.ms != null && pool.ms >= HEALTH_THRESHOLDS.dbMs.crit) add('warn', 'dbSlow', 'db', [pool.ms])

  const errPct = httpMetrics?.summary?.error_rate_pct
  const errLevel = httpMetrics ? pctLevel(errPct ?? 0, { warn: HTTP_ERR_PCT.warn, crit: HTTP_ERR_PCT.crit }) : 'unknown'
  if (errLevel === 'down') add('down', 'httpErr', 'http', [errPct])
  else if (errLevel === 'warn') add('warn', 'httpErr', 'http', [errPct])
  const avgMs = httpMetrics?.summary?.avg_ms
  if (avgMs != null && avgMs >= HEALTH_THRESHOLDS.httpMs.crit) add('warn', 'httpSlow', 'http', [avgMs])

  for (const it of integrationList({ health, pushKpi, smtpPeriod, configChecks })) {
    if (it.level !== 'warn' && it.level !== 'down') continue
    if (it.key === 'network') continue   // ağ alarmı yukarıda kritik olarak zaten listelendi
    const s = 'integrations'
    if (it.key === 'smtp') add(it.level, 'smtpAlarm', s, [smtpPeriod])
    else if (it.key === 'push') add(it.level, 'push', s, [it.data.rate])
    else if (it.key === 'weekly') add(it.level, 'weeklyFailed', s, [it.data.last_run_failed])
    else if (it.key === 'domain') add(it.level, it.level === 'down' ? 'domAlarm' : 'domFallback', s)
    else if (it.key === 'ldap') add(it.level, 'ldap', s, [it.data.detail])
    else if (it.key === 'cleanup') add(it.level, 'cleanup', s)
    else add(it.level, it.key, s)
  }
  for (const part of ['metrics', 'http', 'users']) if (loadErrors[part]) add('warn', 'loadPart', part === 'metrics' ? 'sys' : part, [part])

  reasons.sort((a, b) => RANK[b.level] - RANK[a.level])
  return { level: worstLevel(reasons.map((r) => r.level)), reasons }
}

/** Bölüm başlığı rozetleri: bölüme düşen sebeplerin en kötüsü (`ok` = rozet yeşil). */
export function sectionLevels(reasons) {
  const out = {}
  for (const k of SECTION_KEYS) out[k] = 'ok'
  for (const r of reasons || []) if (r.section && RANK[r.level] > RANK[out[r.section]]) out[r.section] = r.level
  return out
}

/** KPI ızgarasının sayıları/serileri — ekran yalnız etiket + ikon ekler. */
export function deriveKpis({ health, httpMetrics, metrics, pushKpi, smtpPeriod = '7d', configChecks = null }) {
  const h = health || {}
  const series = (arr, pick) => (Array.isArray(arr) ? arr : []).map((p) => ({ v: Number(pick(p)) || 0 }))
  const hbMin = h.heartbeat?.minutes_since ?? -1
  const ex = executorStats(h.executor_pool)
  const pool = poolStats(h.pool, h.db_ms)
  const sum = httpMetrics?.summary
  const errPct = sum?.error_rate_pct
  const avgMs = sum?.avg_ms
  const memPct = h.memory?.used_pct
  const ints = integrationList({ health, pushKpi, smtpPeriod, configChecks })
  const counted = ints.filter((i) => i.level !== 'off' && i.level !== 'unknown')
  return {
    uptime: { seconds: h.build?.uptime_seconds ?? null, since: h.build?.started_at ?? null, hbMinutes: hbMin,
      level: h.heartbeat ? (h.heartbeat.alarm ? 'down' : 'ok') : 'unknown' },
    scheduler: { running: !!h.scheduler?.running, nextRun: h.scheduler?.next_run ?? null, lastRun: h.scheduler?.last_run ?? null,
      level: h.scan_alarm ? 'down' : h.scheduler ? 'ok' : 'unknown' },
    executor: ex ? { ...ex } : null,
    db: pool,
    httpMs: { value: avgMs ?? null, level: avgMs == null ? 'unknown' : pctLevel(avgMs, HEALTH_THRESHOLDS.httpMs),
      series: series(httpMetrics?.history, (b) => b.avg_ms) },
    httpErr: { value: errPct ?? null, level: errPct == null ? 'unknown' : pctLevel(errPct, HTTP_ERR_PCT),
      series: series(httpMetrics?.history, (b) => b.errors) },
    memory: h.memory ? { pct: memPct, usedMb: h.memory.used_mb, maxMb: h.memory.max_mb, level: pctLevel(memPct, MEMORY_PCT),
      series: series(metrics, (p) => p.heap_pct) } : null,
    integrations: { ok: counted.filter((i) => i.level === 'ok').length, total: counted.length, list: ints,
      level: worstLevel(ints.map((i) => i.level)) },
  }
}

/** Yapılandırma sağlığı ayrıntı kodu → metin (ConfigHealthCard ile aynı sözleşme: `kod:ek`); çevirisi yoksa ham kod. */
export function cfgDetailText(detail, t) {
  const s = String(detail || '')
  const i = s.indexOf(':')
  const kind = i === -1 ? s : s.slice(0, i)
  const arg = i === -1 ? '' : s.slice(i + 1)
  const txt = t(`cfg.detail.${kind}`, arg)
  return txt === `cfg.detail.${kind}` ? s : txt
}

/** "3 g 4 sa" / "12 dk" — kısa süre; birimler sözlükten. */
export function formatDurationShort(seconds, t) {
  const s = Math.max(0, Math.floor(Number(seconds) || 0))
  const d = Math.floor(s / 86400), hh = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60)
  if (d > 0) return `${d} ${t('health.unitDay')} ${hh} ${t('chg.unitHour')}`
  if (hh > 0) return `${hh} ${t('chg.unitHour')} ${m} ${t('chg.unitMin')}`
  if (m > 0) return `${m} ${t('chg.unitMin')}`
  return `${s} ${t('chg.unitSec')}`
}

/** Zaman damgası gelecekte mi (Z'siz = UTC). Geçersiz/boş → false. */
export function isFuture(iso, now = Date.now()) {
  if (!iso) return false
  const ts = Date.parse(toUtc(String(iso)))
  return !Number.isNaN(ts) && ts > now
}

/** "30 dk sonra" / "5 dk önce" — sunucu zaman damgası (Z'siz = UTC, formatDate ile aynı kural). */
export function relTime(iso, t, now = Date.now()) {
  if (!iso) return null
  const ts = Date.parse(toUtc(String(iso)))
  if (Number.isNaN(ts)) return null
  const diff = ts - now
  const txt = formatDurationShort(Math.abs(diff) / 1000, t)
  return diff >= 0 ? t('health.inFuture', txt) : t('health.ago', txt)
}
