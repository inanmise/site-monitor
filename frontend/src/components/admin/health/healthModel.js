import { watchdogReasons } from './watchdogModel.js'
/**
 * Sistem Sağlığı türetimleri — SAF fonksiyonlar, React yok (2026-09-27 yeniden tasarım).
 *
 * Genel durum bandı, KPI ızgarası, bölüm başlığı rozetleri ve entegrasyon kartları AYNI hesaptan beslenir;
 * seviye kararı bir yerde verilir, ekran yalnız çizer. Seviyeler: `ok` · `warn` (bozulma) · `down` (kritik) ·
 * `off` (bilinçli kapalı) · `unknown` (veri yok). Kritik olan: heartbeat/tarama/ağ alarmı, RDAP kaynağının
 * tamamen düşmesi, heap ≥ %85 (tek pod, OOM = kesinti), HTTP hata oranı > %5, push başarı oranı < %90.
 */
import { toUtc } from '../../../utils/localDay.js'
import { evalStateModel, sideJobsModel, certChunksModel } from './clusterRuntimeModel.js'

export const SECTION_KEYS = ['watchdog', 'sys', 'sched', 'db', 'integrations', 'heartbeat', 'releases', 'users', 'http']

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
  // UIA-D5 (20.165.1): sunucu SMTP özetini okuyamadıysa (`error`, `periods` yok, yer tutucu rate 100) "%100 sağlıklı"
  // DEĞİL — bilinmiyor. Seviye `unknown`, oran/adet yok.
  if (smtp?.error && !smtp?.periods) {
    return { data: {}, rate: null, alarm: false, level: 'unknown', rateTone: null, sent: null, attempted: null, unavailable: true }
  }
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

/** Yürütücü havuzu durumu. Okunamadıysa (`error`) null — "0 / 0 boş kuyruk" yeşili çizilmez (UIA-D5). */
export function executorStats(ex) {
  if (!ex || ex.error) return null
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
export function deriveOverall({ health, httpMetrics, pushKpi, smtpPeriod = '7d', configChecks = null, loadErrors = {}, watchdog = null }) {
  const h = health || {}
  const reasons = []
  const add = (level, code, section, args = [], pod = null) => reasons.push(pod ? { level, code, section, args, pod } : { level, code, section, args })
  // Pod başına değerler (bellek, yürütücü, havuz, DB süresi — sağlık isteğini yanıtlayan pod; HTTP — /http-metrics'i yanıtlayan
  // pod) sebep metninde pod adıyla (çok pod R2b inceleme H1). Pod bilinmiyorsa (eski sunucu) ad eklenmez.
  const hp = h.served_by_pod || h.served_by || null
  const httpPod = httpMetrics?.served_by_pod || httpMetrics?.served_by || null
  // Kendi kendini izleme (2026-10-11): onaylı sorunlar ve durmuş değerlendirme genel duruma katılır
  for (const r of watchdogReasons(watchdog)) reasons.push(r)

  if (loadErrors.health) add('down', 'loadHealth', null)
  if (h.heartbeat?.alarm) add('down', 'hbAlarm', 'heartbeat')
  // Çok pod (2026-10-23, R2b, MP-35): işaretsiz susan pod (çöktü / zamanlayıcısı takıldı) — kümenin son sinyali başka pod'dan
  // geldiği için yukarıdaki alarm onu göstermez. Uyarı (kritik değil: küme yaşıyor).
  if (!h.heartbeat?.alarm && Number(h.heartbeat?.pods_silent) > 0) add('warn', 'podSilent', 'heartbeat', [h.heartbeat.pods_silent])
  if (h.scan_alarm) add('down', 'scanAlarm', 'sched')
  if (h.network?.alarm) add('down', 'networkAlarm', 'integrations')

  const memPct = h.memory?.used_pct
  const memLevel = h.memory ? pctLevel(memPct, MEMORY_PCT) : 'unknown'
  if (memLevel === 'warn' || memLevel === 'down') add(memLevel, 'memory', 'sys', [memPct], hp)

  const ex = executorStats(h.executor_pool)
  if (ex?.saturated) add('warn', 'queueSaturated', 'sched', [], hp)
  if (ex && ex.callerRuns > 0) add('warn', 'callerRuns', 'sched', [ex.callerRuns], hp)
  const pool = poolStats(h.pool, h.db_ms)
  if (pool && pool.waiting > 0) add('warn', 'poolWaiting', 'sched', [pool.waiting], hp)
  if (pool && pool.ms != null && pool.ms >= HEALTH_THRESHOLDS.dbMs.crit) add('warn', 'dbSlow', 'db', [pool.ms], hp)

  const errPct = httpMetrics?.summary?.error_rate_pct
  const errLevel = httpMetrics ? pctLevel(errPct ?? 0, { warn: HTTP_ERR_PCT.warn, crit: HTTP_ERR_PCT.crit }) : 'unknown'
  if (errLevel === 'down') add('down', 'httpErr', 'http', [errPct], httpPod)
  else if (errLevel === 'warn') add('warn', 'httpErr', 'http', [errPct], httpPod)
  const avgMs = httpMetrics?.summary?.avg_ms
  if (avgMs != null && avgMs >= HEALTH_THRESHOLDS.httpMs.crit) add('warn', 'httpSlow', 'http', [avgMs], httpPod)

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
  // Sunucunun okuyamadığı alt yükler (2026-10-22, FE-8): kartları UIA-D5'ten beri "bilinmiyor" çiziliyor ama genel bant
  // "Tüm sistemler çalışıyor" diyordu — bilinmeyen asla yeşil değil; aynı "Veri alınamadı" sebebi, ilgili bölüme götürür.
  if (health && !loadErrors.health) {
    if (h.smtp?.error && !h.smtp?.periods) add('warn', 'loadPart', 'integrations', ['smtp'])
    if (h.lock?.error) add('warn', 'loadPart', 'sched', ['lock'])
    if (h.executor_pool?.error) add('warn', 'loadPart', 'sched', ['executor'])
  }

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
export function deriveKpis({ health, httpMetrics, metrics, metricsPod = null, pushKpi, smtpPeriod = '7d', configChecks = null }) {
  const h = health || {}
  const hp = h.served_by_pod || h.served_by || null
  const httpPod = httpMetrics?.served_by_pod || httpMetrics?.served_by || null
  // Bellek değeri sağlık isteğinin pod'undan, kıvılcımı /metrics'i yanıtlayan pod'dan (ayrı istek) — iki pod farklıysa kıvılcım
  // çizilmez (bir pod'un değeri öbürünün grafiğiyle yan yana durmasın; inceleme H1). Pod bilinmiyorsa eski davranış.
  const memSeriesOk = !metricsPod || !hp || metricsPod === hp
  // Tarama durumu bu pod'un belleğindense (scan_runs okunamadı) zamanlayıcı kutucuğu "bilinmiyor" (inceleme H2)
  const schedLocal = h.scheduler?.status_source === 'local' || h.scan?.source === 'local'
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
      level: h.scan_alarm ? 'down' : !h.scheduler || schedLocal ? 'unknown' : 'ok' },
    executor: ex ? { ...ex } : null,
    db: pool,
    httpMs: { value: avgMs ?? null, level: avgMs == null ? 'unknown' : pctLevel(avgMs, HEALTH_THRESHOLDS.httpMs),
      series: series(httpMetrics?.history, (b) => b.avg_ms) },
    httpErr: { value: errPct ?? null, level: errPct == null ? 'unknown' : pctLevel(errPct, HTTP_ERR_PCT),
      series: series(httpMetrics?.history, (b) => b.errors) },
    memory: h.memory ? { pct: memPct, usedMb: h.memory.used_mb, maxMb: h.memory.max_mb, level: pctLevel(memPct, MEMORY_PCT),
      series: memSeriesOk ? series(metrics, (p) => p.heap_pct) : [] } : null,
    integrations: { ok: counted.filter((i) => i.level === 'ok').length, total: counted.length, list: ints,
      level: worstLevel(ints.map((i) => i.level)) },
    pods: { health: hp, http: httpPod, metrics: metricsPod || null },
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

// ── Çok pod R2b (2026-10-23, paket C1): küme kartı, tarama yoklaması, pod durumları ────────────────────────────────

/** Tarama türleri (sunucu `scan.runs` anahtarları) — ekran sırası. */
export const SCAN_KINDS = ['hourly', 'full', 'stale']

/**
 * "Şimdi çalıştır" hızlı yoklamasının bir adımı (MP-34). Biter: kümede canlı `cert-check` kilidi GÖRÜLDÜ ve artık yok, ya da
 * kümenin son koşusu (`scan.last_run`, veritabanından) başlangıçtakinden farklı. Yanıtlar farklı pod'lardan gelse de değerler
 * kümenindir — ilk yanıtı başka pod verdi diye erken bitmez. Kilit okunamadıysa "koşuyor mu" bilinmez → karar verilmez;
 * tarama durumu bu pod'un belleğindense (`source: local`) son koşu kıyaslanmaz (başka pod'un değeri olabilir).
 * @returns {{seenLive: boolean, done: boolean}}
 */
export function scanPollStep(state, data, prevScan) {
  const st = state || {}
  // Kıyas tabanı: yalnız KÜME değeri (`source: 'cluster'`) kümenin değeriyle kıyaslanır (inceleme H6). Tıklamadaki yanıt küme
  // değeri taşımıyorsa (eski sürüm pod'u, tablo okunamadı) taban ilk küme yanıtından alınır — o yanıt "bitti" saymaz. Eski pod'un
  // yanıtı (source yok, başka zaman biçimi) son koşuyu hiç değiştirmez; kararı kilit verir.
  let baseline = st.baseline
  if (baseline === undefined && prevScan?.source === 'cluster') baseline = prevScan.last_run ?? null
  if (!data) return { seenLive: !!st.seenLive, baseline, done: false }
  const lockKnown = !data.lock?.error
  const live = lockKnown ? !!(data.lock?.held || data.scheduler?.running) : null
  const seenLive = !!st.seenLive || live === true
  let changed = false
  if (data.scan?.source === 'cluster') {
    const last = data.scan.last_run ?? null
    if (baseline === undefined) baseline = last
    else changed = last != null && last !== baseline
  }
  return { seenLive, baseline, done: (seenLive && live === false) || changed }
}

/**
 * Küme kartı modeli (N1). `scheduler.sweep_leader` kümenin kira satırıdır (hangi pod okursa okusun aynı); `held_by_me`,
 * son tarama tıkı, tıkanma, tutulan kilitler, tur hataları, atlanan turlar ve önbellek yolu YANITLAYAN POD'undur — ekran
 * öyle etiketler. Bilinmeyen asla "sağlıklı" sayılmaz: kira okunamadıysa `leader.known = false`, alan yoksa null.
 */
export function clusterModel(health) {
  const h = health || {}
  const sch = h.scheduler || {}
  const sl = sch.sweep_leader
  const pod = { id: h.served_by ?? sch.instance_id ?? null, name: h.served_by_pod ?? h.served_by ?? sch.instance_id ?? null }
  // Çok pod R3-1 (paket B): kalıcı değerlendirme durumu (küme doğrulaması + bu pod'un kaydedicisi) ve bu pod'un yan işleri
  const evalState = evalStateModel(h)
  const sideJobs = sideJobsModel(h)
  // Çok pod Sürüm 3-4 (paket C1): parçalı sertifika taraması — Küme kartında ayrı bölüm; kart seviyesine yalnız uyarısı katılır
  const certChunks = certChunksModel(h)
  if (!sl) return { available: false, pod, leader: null, thisPod: null, cacheBus: h.cache_bus ?? null, evalState, sideJobs, certChunks, level: 'unknown' }
  const known = !sl.error
  const leaderless = known && (!sl.owner || !!sl.expired)
  const leader = { known, owner: sl.owner ?? null, ownerPod: sl.owner_pod ?? sl.owner ?? null, until: sl.until ?? null,
    expired: !!sl.expired, leaderless, error: sl.error ?? null }
  const thisPod = {
    isLeader: known ? !!sl.held_by_me : null,
    renewer: sl.renewer ?? null,
    lastTick: sl.last_sweep_tick ?? null,
    wedgeYieldedAt: sl.wedge_yielded_at ?? null,
    heldLocks: Array.isArray(sch.held_locks) ? sch.held_locks : null,
    roundClaimFailures: sch.round_claim_failures ?? null,
    lastRoundClaimFailure: sch.last_round_claim_failure ?? null,
    skippedRounds: Array.isArray(sch.skipped_rounds) ? sch.skipped_rounds : null,
  }
  const cb = h.cache_bus ?? null
  const cacheStale = cacheBusStale(cb, h.timestamp)
  const cacheLevel = !cb ? null : cb.error ? 'unknown'
    : cb.enabled === false ? 'ok'
      : (cb.running === false || cb.table_missing || cacheStale === true) ? 'warn' : cacheStale == null ? 'unknown' : 'ok'
  const lostLocks = (thisPod.heldLocks || []).filter((l) => l?.state === 'LOST').length
  const level = !known ? 'unknown'
    : leaderless || thisPod.wedgeYieldedAt || cacheLevel === 'warn' || lostLocks > 0 || (thisPod.skippedRounds?.length || 0) > 0
      || evalState?.level === 'warn' || certChunks?.level === 'warn' ? 'warn' : 'ok'
  return { available: true, pod, leader, thisPod, cacheBus: cb, cacheLevel, cacheStale, lostLocks, evalState, sideJobs, certChunks, level }
}

/** Önbellek yolu yoklamasının bayatlık sınırı (ms) — yoklama 2 sn'de bir; 15 yoklama başarısızsa uyarı. */
export const CACHE_POLL_STALE_MS = 30_000

/**
 * Önbellek yolu son başarılı yoklaması bayat mı (inceleme H4): sunucu saatine (`health.timestamp`) göre `last_poll_ok_at`
 * {@link CACHE_POLL_STALE_MS}'den eski → true; hiç başarılı yoklama yok ama yoklama / hata sayılmış → true; ikisi de yok
 * (yeni açıldı) → null (bilinmiyor); yol kapalı / tablo yok → false (ayrı kural). İş parçacığı "çalışıyor" görünse de yoklamaları
 * düşüyorsa diğer pod'ların yazmaları bu poda ulaşmaz.
 */
export function cacheBusStale(cb, serverNow) {
  if (!cb || cb.error || cb.enabled === false || cb.table_missing) return false
  const now = serverNow ? Date.parse(toUtc(String(serverNow))) : Date.now()
  const last = cb.last_poll_ok_at ? Date.parse(toUtc(String(cb.last_poll_ok_at))) : NaN
  if (Number.isNaN(last)) return Number(cb.polls) > 0 || Number(cb.poll_failures) > 0 ? true : null
  return (Number.isNaN(now) ? Date.now() : now) - last > CACHE_POLL_STALE_MS
}

/** Heartbeat pod durumu → rozet tonu (canlı yeşil; sessiz uyarı; yeniden başladı / durdu nötr; bilinmeyen gri). */
export const POD_STATE_TONE = { live: 'success', silent: 'warning', restarted: 'muted', replaced: 'muted', stopped: 'muted' }

/** Tutulan kilit durumu (ClusterLockService.State) → ton; LOST uyarıdır (kilit başka pod'a geçti / zorla bırakıldı). */
export const LOCK_STATE_TONE = { HELD: 'success', CAPPED: 'warning', LOST: 'warning', RELEASED: 'muted' }
