import { describe, it, expect } from 'vitest'
import {
  deriveOverall, deriveKpis, sectionLevels, integrationList, pushStats, smtpStats, executorStats, poolStats,
  worstLevel, formatDurationShort, relTime, SECTION_KEYS, scanPollStep, clusterModel, SCAN_KINDS, POD_STATE_TONE, cacheBusStale,
} from '../components/admin/health/healthModel.js'

/**
 * Sistem Sağlığı seviye modeli — SAF fonksiyonlar (2026-09-27). Banner, KPI ızgarası ve bölüm rozetleri aynı
 * hesaptan beslendiği için kararlar burada ısırılır: kritik/bozulma sınıflaması, push oranının YÜZDE olması (eski
 * kart 0–1 oranını "%" diye basıyordu), entegrasyon listesi, bölüm eşlemesi.
 */
const t = (k, ...a) => a.length ? `${k}(${a.join(',')})` : k

/** SchedulerService + SystemController payload'ının sağlıklı hâli. */
const CLEAN = {
  scheduler: { running: false, next_run: '2026-09-27T05:00:00', last_run: '2026-09-27T04:00:00', active_domains: 412, instance_id: 'pod-a' },
  build: { version: '20.86.0', commit: 'a1b2c3d', environment: 'prod', uptime_seconds: 3 * 86400 + 4 * 3600 + 17 * 60, started_at: '2026-09-24T01:00:00' },
  lock: { held: false },
  pool: { active: 6, idle: 4, total: 10, waiting: 0, max_size: 30 },
  executor_pool: { queue_size: 12, queue_capacity: 5000, active_count: 4, pool_size: 20, core_pool_size: 20, max_pool_size: 50, completed_tasks: 100, caller_runs: 0, saturated: false },
  memory: { used_mb: 800, free_mb: 1248, total_mb: 2048, max_mb: 2048, used_pct: 39 },
  scan: { last_run: '2026-09-27T04:00:00', duration_ms: 42150, total: 412, warnings: 3, errors: 1 },
  scan_alarm: false,
  db_ms: 14,
  smtp: { rate: 100, alarm: false, sent: 10, attempted: 10, periods: { '7d': { rate: 100, alarm: false, sent: 10, attempted: 10 }, '1d': { rate: 93.5, alarm: true, sent: 29, attempted: 31 } } },
  heartbeat: { last_heartbeat: '2026-09-27T04:59:00', minutes_since: 1, alarm: false, recent: [] },
  network: { alarm: false },
  domain_expiry: { source: 'RDAP', alarm: false },
  weekly_availability: { enabled: true, last_run_failed: 0 },
  cleanup: { alarm: false, never_run: false, hold_active: false },
}
const HTTP_OK = { summary: { total_requests: 1000, error_rate_pct: 0.4, avg_ms: 120, max_ms: 900 }, history: [{ avg_ms: 100, errors: 0 }, { avg_ms: 140, errors: 1 }] }
const PUSH_OK = { sent: 240, failed: 3, pending: 0, blocked: 0 }

describe('healthModel — genel seviye', () => {
  it('temiz payload → ok, sebep yok, her bölüm ok', () => {
    const o = deriveOverall({ health: CLEAN, httpMetrics: HTTP_OK, pushKpi: PUSH_OK })
    expect(o.level).toBe('ok')
    expect(o.reasons).toEqual([])
    const lv = sectionLevels(o.reasons)
    for (const k of SECTION_KEYS) expect(lv[k]).toBe('ok')
  })

  it('heartbeat alarmı KRİTİK (down) ve heartbeat bölümüne düşer; SMTP alarmı BOZULMA (warn) → entegrasyonlar', () => {
    const o = deriveOverall({ health: { ...CLEAN, heartbeat: { ...CLEAN.heartbeat, alarm: true, minutes_since: 12 } }, httpMetrics: HTTP_OK, pushKpi: PUSH_OK, smtpPeriod: '1d' })
    expect(o.level).toBe('down')
    expect(o.reasons.map((r) => r.code)).toEqual(['hbAlarm', 'smtpAlarm'])   // kritik önce
    expect(o.reasons[0].section).toBe('heartbeat')
    expect(o.reasons[1]).toMatchObject({ level: 'warn', section: 'integrations', args: ['1d'] })
    expect(sectionLevels(o.reasons)).toMatchObject({ heartbeat: 'down', integrations: 'warn', sys: 'ok' })
  })

  it('yalnız uyarılar → warn: doygun kuyruk + caller-runs (sched), heap %70 (sys), bekleyen havuz (sched)', () => {
    const h = { ...CLEAN, executor_pool: { ...CLEAN.executor_pool, queue_size: 4500, saturated: true, caller_runs: 12 },
      memory: { ...CLEAN.memory, used_pct: 70 }, pool: { ...CLEAN.pool, waiting: 2 } }
    const o = deriveOverall({ health: h, httpMetrics: HTTP_OK, pushKpi: PUSH_OK })
    expect(o.level).toBe('warn')
    expect(o.reasons.map((r) => `${r.code}@${r.section}`)).toEqual(['memory@sys', 'queueSaturated@sched', 'callerRuns@sched', 'poolWaiting@sched'])
    expect(o.reasons.find((r) => r.code === 'callerRuns').args).toEqual([12])
  })

  it('heap ≥ %85 tek podda KRİTİK (OOM = kesinti); HTTP hata oranı > %5 kritik, 1–5 uyarı; tarama alarmı kritik → sched', () => {
    expect(deriveOverall({ health: { ...CLEAN, memory: { ...CLEAN.memory, used_pct: 86 } }, httpMetrics: HTTP_OK, pushKpi: PUSH_OK }).level).toBe('down')
    const err = deriveOverall({ health: CLEAN, httpMetrics: { ...HTTP_OK, summary: { ...HTTP_OK.summary, error_rate_pct: 6 } }, pushKpi: PUSH_OK })
    expect(err.reasons[0]).toMatchObject({ code: 'httpErr', level: 'down', section: 'http' })
    const warn = deriveOverall({ health: CLEAN, httpMetrics: { ...HTTP_OK, summary: { ...HTTP_OK.summary, error_rate_pct: 2 } }, pushKpi: PUSH_OK })
    expect(warn.reasons[0]).toMatchObject({ code: 'httpErr', level: 'warn' })
    const scan = deriveOverall({ health: { ...CLEAN, scan_alarm: true }, httpMetrics: HTTP_OK, pushKpi: PUSH_OK })
    expect(scan.reasons[0]).toMatchObject({ code: 'scanAlarm', level: 'down', section: 'sched' })
  })

  it('yükleme hataları: health → kritik; metrics/http/users → uyarı (metrics sys bölümüne)', () => {
    const o = deriveOverall({ health: null, httpMetrics: null, pushKpi: null, loadErrors: { health: true, metrics: true, http: true, users: true } })
    expect(o.level).toBe('down')
    expect(o.reasons.map((r) => `${r.code}:${r.section}`)).toEqual(['loadHealth:null', 'loadPart:sys', 'loadPart:http', 'loadPart:users'])
  })
})

describe('healthModel — entegrasyonlar', () => {
  it('push oranı YÜZDE: 240/243 → 98.8 ok; 8/10 → 80 down; trafik yoksa unknown', () => {
    expect(pushStats(PUSH_OK)).toMatchObject({ rate: 98.8, level: 'ok', attempted: 243 })
    expect(pushStats({ sent: 8, failed: 2 })).toMatchObject({ rate: 80, level: 'down' })
    expect(pushStats({ sent: 93, failed: 7 })).toMatchObject({ rate: 93, level: 'warn' })
    expect(pushStats(null)).toMatchObject({ rate: null, level: 'unknown', queued: 0 })
    expect(pushStats({ sent: 1, failed: 0, pending: 2, blocked: 1 }).queued).toBe(3)
  })

  it('SMTP seçili periyottan okunur; alarm → warn, oran < 80 → down; periyot yoksa kök değerler', () => {
    expect(smtpStats(CLEAN.smtp, '7d')).toMatchObject({ rate: 100, level: 'ok', rateTone: 'ok' })
    expect(smtpStats(CLEAN.smtp, '1d')).toMatchObject({ rate: 93.5, level: 'warn', rateTone: 'down' })
    expect(smtpStats({ periods: { '1d': { rate: 50, alarm: true } } }, '1d')).toMatchObject({ rate: 50, level: 'down' })
    expect(smtpStats({ rate: 93.5, alarm: true, sent: 290, attempted: 310 }, '7d')).toMatchObject({ level: 'warn', rateTone: 'down', sent: 290 })
    expect(smtpStats(undefined, '7d')).toMatchObject({ rate: 100, level: 'ok' })
  })

  it('liste sırası ve seviyeleri: RDAP yedeği warn, NONE down, ağ alarmı down, haftalık kapalı off, temizlik beklemede off, LDAP yalnız config-health ile', () => {
    const h = { ...CLEAN, domain_expiry: { source: 'FALLBACK', alarm: false }, weekly_availability: { enabled: false }, cleanup: { hold_active: true } }
    const list = integrationList({ health: h, pushKpi: PUSH_OK, smtpPeriod: '7d' })
    expect(list.map((i) => `${i.key}:${i.level}`)).toEqual(['smtp:ok', 'push:ok', 'weekly:off', 'domain:warn', 'network:ok', 'cleanup:off'])
    const withLdap = integrationList({ health: { ...CLEAN, domain_expiry: { source: 'NONE' }, network: { alarm: true } }, pushKpi: PUSH_OK,
      configChecks: [{ key: 'smtp', status: 'ok' }, { key: 'ldap', status: 'bad', detail: 'tested_fail:2026-09-26' }] })
    expect(withLdap.find((i) => i.key === 'ldap')).toMatchObject({ level: 'down' })
    expect(withLdap.find((i) => i.key === 'domain').level).toBe('down')
    expect(withLdap.find((i) => i.key === 'network').level).toBe('down')
    const o = deriveOverall({ health: { ...CLEAN, network: { alarm: true } }, httpMetrics: HTTP_OK, pushKpi: PUSH_OK, configChecks: withLdap.find((i) => i.key === 'ldap') ? [{ key: 'ldap', status: 'warn', detail: 'never_tested' }] : null })
    expect(o.reasons.map((r) => r.code)).toEqual(['networkAlarm', 'ldap'])
  })
})

describe('healthModel — KPI sayıları', () => {
  it('sekiz KPI: uptime/heartbeat, zamanlayıcı, yürütücü, DB, HTTP süre+hata (seri), bellek (seri), entegrasyon sayımı', () => {
    const k = deriveKpis({ health: CLEAN, httpMetrics: HTTP_OK, metrics: [{ heap_pct: 40 }, { heap_pct: 45 }], pushKpi: PUSH_OK })
    expect(k.uptime).toMatchObject({ seconds: CLEAN.build.uptime_seconds, hbMinutes: 1, level: 'ok' })
    expect(k.scheduler).toMatchObject({ running: false, level: 'ok' })
    expect(k.executor).toMatchObject({ active: 4, pool: 20, queue: 12, cap: 5000, level: 'ok' })
    expect(k.db).toMatchObject({ active: 6, max: 30, pct: 20, ms: 14, level: 'ok' })
    expect(k.httpMs).toMatchObject({ value: 120, level: 'ok' })
    expect(k.httpMs.series).toEqual([{ v: 100 }, { v: 140 }])
    expect(k.httpErr).toMatchObject({ value: 0.4, level: 'ok' })
    expect(k.memory).toMatchObject({ pct: 39, level: 'ok' })
    expect(k.memory.series).toEqual([{ v: 40 }, { v: 45 }])
    expect(k.integrations).toMatchObject({ ok: 6, total: 6, level: 'ok' })
  })

  it('veri yoksa çökmez: seviyeler unknown, seriler boş, entegrasyon sayımı off/unknown olanları saymaz', () => {
    const k = deriveKpis({ health: { smtp: null, weekly_availability: { enabled: false } }, httpMetrics: null, metrics: null, pushKpi: null })
    expect(k.executor).toBeNull()
    expect(k.db).toBeNull()
    expect(k.memory).toBeNull()
    expect(k.httpMs).toMatchObject({ value: null, level: 'unknown', series: [] })
    expect(k.uptime.level).toBe('unknown')
    // smtp ok (varsayılan %100) sayılır; push unknown ve weekly off sayılmaz
    expect(k.integrations).toMatchObject({ ok: 1, total: 1 })
  })

  it('yürütücü ve havuz eşikleri', () => {
    expect(executorStats({ queue_size: 2600, queue_capacity: 5000, saturated: false }).level).toBe('warn')   // > %50
    expect(executorStats({ queue_size: 0, queue_capacity: 5000, caller_runs: 1 }).level).toBe('warn')
    expect(executorStats(null)).toBeNull()
    expect(poolStats({ active: 25, max_size: 30, waiting: 0 }, 10)).toMatchObject({ pct: 83, level: 'warn' })
    expect(poolStats({ active: 1, max_size: 30, waiting: 0 }, 600)).toMatchObject({ level: 'warn', ms: 600 })
    expect(poolStats({ active: 1, max_size: 30, waiting: 0 }, -1).level).toBe('down')
    expect(poolStats({ error: 'x' })).toBeNull()
  })
})

describe('healthModel — süre metinleri', () => {
  it('formatDurationShort birimleri sözlükten alır', () => {
    expect(formatDurationShort(3 * 86400 + 4 * 3600 + 17 * 60, t)).toBe('3 health.unitDay 4 chg.unitHour')
    expect(formatDurationShort(4 * 3600 + 17 * 60, t)).toBe('4 chg.unitHour 17 chg.unitMin')
    expect(formatDurationShort(17 * 60 + 5, t)).toBe('17 chg.unitMin')
    expect(formatDurationShort(42, t)).toBe('42 chg.unitSec')
    expect(formatDurationShort(null, t)).toBe('0 chg.unitSec')
  })

  it('relTime: Z’siz sunucu zamanı UTC sayılır (formatDate ile aynı kural); gelecek → inFuture, geçmiş → ago', () => {
    const now = Date.parse('2026-09-27T04:30:00Z')
    expect(relTime('2026-09-27T05:00:00', t, now)).toBe('health.inFuture(30 chg.unitMin)')
    expect(relTime('2026-09-27T04:00:00Z', t, now)).toBe('health.ago(30 chg.unitMin)')
    expect(relTime(null, t, now)).toBeNull()
    expect(relTime('bozuk', t, now)).toBeNull()
  })

  it('worstLevel: down > warn > ok; off/unknown ok ile aynı ağırlık', () => {
    expect(worstLevel(['ok', 'off', 'unknown'])).toBe('ok')
    expect(worstLevel(['warn', 'down', 'ok'])).toBe('down')
    expect(worstLevel([])).toBe('ok')
  })
})

// 20.165.1 (UIA-D5): sunucunun okuyamadığı alt yükler sağlıklı (yeşil) sayılmaz
describe('healthModel — okunamayan alt yük', () => {
  it('smtpStats: {error} ve periods yok → unknown, oran null, sayılmaz', () => {
    const s = smtpStats({ total: 0, attempted: 0, sent: 0, rate: 100, alarm: false, error: 'boom' }, '7d')
    expect(s.level).toBe('unknown')
    expect(s.rate).toBeNull()
    expect(s.unavailable).toBe(true)
  })
  it('executorStats: {error} → null (KPI "—", "0 / 0" yeşili yok)', () => {
    expect(executorStats({ error: 'x' })).toBeNull()
    expect(executorStats({ queue_size: 0, queue_capacity: 10 })).not.toBeNull()
  })
  it('FE-8: genel bant da yeşil kalmaz — SMTP özeti / kilit / görev kuyruğu okunamadıysa "Veri alınamadı" sebebi (ilgili bölüm)', () => {
    expect(deriveOverall({ health: CLEAN, httpMetrics: HTTP_OK, pushKpi: PUSH_OK }).level).toBe('ok')
    const o = deriveOverall({ health: { ...CLEAN, smtp: { rate: 100, alarm: false, error: 'boom' }, lock: { held: false, error: 'x' },
      executor_pool: { error: 'y' } }, httpMetrics: HTTP_OK, pushKpi: PUSH_OK })
    expect(o.level).toBe('warn')
    expect(o.reasons.filter((r) => r.code === 'loadPart').map((r) => [r.args[0], r.section]))
      .toEqual([['smtp', 'integrations'], ['lock', 'sched'], ['executor', 'sched']])
    // Yalnız SMTP: dönem verisi varsa (periods) okunmuş sayılır — sebep yok
    expect(deriveOverall({ health: { ...CLEAN, smtp: { error: 'eski', periods: { '7d': { rate: 100 } } } }, httpMetrics: HTTP_OK, pushKpi: PUSH_OK }).level).toBe('ok')
  })
})

// ── Çok pod R2b (2026-10-23, paket C1) ─────────────────────────────────────────────────────────────────────────────
describe('scanPollStep — "Şimdi çalıştır" yoklaması kümeden biter (MP-34)', () => {
  const ans = (over = {}) => ({ scheduler: { running: false }, lock: { held: false }, scan: { last_run: '2026-10-23T05:00:00', source: 'cluster' }, ...over })
  const PREV = { last_run: '2026-10-23T05:00:00', source: 'cluster' }
  it('yanıtlar iki pod arasında gidip gelse de erken bitmez: kilit canlıyken her yanıt "koşuyor" der', () => {
    let st = { seenLive: false }
    // A koşturuyor: canlı kilit (DB) — hem A hem B aynı değeri döner
    for (const pod of ['A', 'B', 'A', 'B']) {
      const r = scanPollStep(st, ans({ lock: { held: true, locked_by: 'A' }, scheduler: { running: true, running_here: pod === 'A' } }), PREV)
      expect(r.done, pod).toBe(false)
      st = r
    }
    // kilit bırakıldı → bitti (hangi pod yanıtlarsa)
    expect(scanPollStep(st, ans(), PREV).done).toBe(true)
  })
  it('hızlı tarama (kilit hiç görülmedi): kümenin son koşusu değişince biter; aynıysa bitmez', () => {
    expect(scanPollStep({ seenLive: false }, ans(), PREV).done).toBe(false)
    expect(scanPollStep({ seenLive: false }, ans({ scan: { last_run: '2026-10-23T06:00:00', source: 'cluster' } }), PREV).done).toBe(true)
  })
  it('kilit okunamadı → "koşuyor mu" bilinmez, karar verilmez; bu pod belleği (local) son koşu kıyaslanmaz', () => {
    const live = scanPollStep({ seenLive: false }, ans({ lock: { held: true } }), PREV)
    expect(live).toMatchObject({ seenLive: true, done: false })
    expect(scanPollStep(live, ans({ lock: { held: false, error: 'x' } }), PREV).done).toBe(false)
    expect(scanPollStep({ seenLive: false }, ans({ scan: { last_run: '2026-10-23T09:00:00', source: 'local' } }), PREV).done).toBe(false)
    expect(scanPollStep({ seenLive: false }, null, null)).toMatchObject({ seenLive: false, done: false })
  })
  it('H6 — yuvarlanan dağıtım: 20.166.0 pod’unun yanıtı (source yok, başka zaman biçimi) yoklamayı bitirmez', () => {
    const old = ans({ scan: { last_run: '2026-10-23T04:59:58.123456' }, lock: { held: false }, scheduler: { running: false } })
    // canlı kilit henüz görülmedi; eski pod'un farklı biçimli son koşusu "değişti" sayılmaz
    expect(scanPollStep({ seenLive: false }, old, PREV).done).toBe(false)
  })
  it('H6 — tıklamadaki yanıt eski pod’dansa taban İLK küme yanıtından alınır (o yanıt bitirmez), sonraki değişim bitirir', () => {
    const oldPrev = { last_run: '2026-10-23T04:59:58.123456' }
    const st = scanPollStep({ seenLive: false }, ans(), oldPrev)
    expect(st).toMatchObject({ done: false, baseline: '2026-10-23T05:00:00' })
    expect(scanPollStep(st, ans(), oldPrev).done).toBe(false)
    expect(scanPollStep(st, ans({ scan: { last_run: '2026-10-23T06:00:00', source: 'cluster' } }), oldPrev).done).toBe(true)
  })
})

describe('clusterModel — Küme kartı (N1)', () => {
  const H = {
    served_by: 'sm-aaaa-0000000a', served_by_pod: 'sm-aaaa',
    scheduler: { instance_id: 'sm-aaaa-0000000a',
      sweep_leader: { owner: 'sm-bbbb-0000000b', owner_pod: 'sm-bbbb', until: '2026-10-23T05:03:00', expired: false, held_by_me: false, renewer: true, last_sweep_tick: null },
      held_locks: [{ name: 'watchdog', state: 'HELD', until: '2026-10-23T05:02:00' }], round_claim_failures: 0, last_round_claim_failure: null, skipped_rounds: [] },
    cache_bus: { enabled: true, running: true, table_missing: false, applied_generation: 12, poll_failures: 0 },
  }
  it('lider = kira sahibi (küme); "bu pod" değerleri ayrı; sağlıklı → ok', () => {
    const c = clusterModel(H)
    expect(c.available).toBe(true)
    expect(c.leader).toMatchObject({ known: true, ownerPod: 'sm-bbbb', leaderless: false })
    expect(c.thisPod.isLeader).toBe(false)
    expect(c.pod.name).toBe('sm-aaaa')
    expect(c.level).toBe('ok')
  })
  it('kira okunamadı → bilinmiyor (asla yeşil değil); kira dolmuş / sahipsiz → warn; tıkanma / atlanan tur / durmuş önbellek yolu → warn', () => {
    expect(clusterModel({ ...H, scheduler: { ...H.scheduler, sweep_leader: { error: 'db' } } })).toMatchObject({ level: 'unknown', leader: { known: false }, thisPod: { isLeader: null } })
    expect(clusterModel({ ...H, scheduler: { ...H.scheduler, sweep_leader: { ...H.scheduler.sweep_leader, expired: true } } }).level).toBe('warn')
    expect(clusterModel({ ...H, scheduler: { ...H.scheduler, sweep_leader: { owner: null, expired: true } } }).leader.leaderless).toBe(true)
    expect(clusterModel({ ...H, scheduler: { ...H.scheduler, sweep_leader: { ...H.scheduler.sweep_leader, wedge_yielded_at: '2026-10-23T05:00:00' } } }).level).toBe('warn')
    expect(clusterModel({ ...H, scheduler: { ...H.scheduler, skipped_rounds: [{ round: 'nightly-cleanup', at: 'x' }] } }).level).toBe('warn')
    expect(clusterModel({ ...H, cache_bus: { ...H.cache_bus, running: false } })).toMatchObject({ level: 'warn', cacheLevel: 'warn' })
    expect(clusterModel({ ...H, cache_bus: { error: 'X' } }).cacheLevel).toBe('unknown')
  })
  it('küme kaydı yok (eski / tek pod) → available=false; alanlar yoksa null (uydurma 0 yok)', () => {
    expect(clusterModel({ scheduler: { instance_id: 'x' } })).toMatchObject({ available: false, level: 'unknown' })
    const c = clusterModel({ ...H, scheduler: { sweep_leader: H.scheduler.sweep_leader } })
    expect(c.thisPod.heldLocks).toBeNull()
    expect(c.thisPod.roundClaimFailures).toBeNull()
    expect(c.thisPod.skippedRounds).toBeNull()
  })
  it('sabitler: tarama türleri ve pod durumu tonları', () => {
    expect(SCAN_KINDS).toEqual(['hourly', 'full', 'stale'])
    expect(POD_STATE_TONE).toEqual({ live: 'success', silent: 'warning', restarted: 'muted', replaced: 'muted', stopped: 'muted' })
  })
})

describe('deriveOverall — sessiz pod (MP-35)', () => {
  it('küme alarmı yokken sessiz pod → warn "podSilent" (heartbeat bölümü); küme alarmı varsa yalnız kritik sebep', () => {
    const o = deriveOverall({ health: { ...CLEAN, heartbeat: { ...CLEAN.heartbeat, pods_silent: 1 } }, httpMetrics: HTTP_OK, pushKpi: PUSH_OK })
    expect(o.level).toBe('warn')
    expect(o.reasons).toContainEqual(expect.objectContaining({ code: 'podSilent', section: 'heartbeat', args: [1] }))
    const d = deriveOverall({ health: { ...CLEAN, heartbeat: { ...CLEAN.heartbeat, alarm: true, pods_silent: 2 } }, httpMetrics: HTTP_OK, pushKpi: PUSH_OK })
    expect(d.reasons.some((r) => r.code === 'podSilent')).toBe(false)
    expect(deriveOverall({ health: { ...CLEAN, heartbeat: { ...CLEAN.heartbeat, pods_silent: 0 } }, httpMetrics: HTTP_OK, pushKpi: PUSH_OK }).level).toBe('ok')
  })
})

describe('İnceleme H1 / H2 / H4 / H5 (2026-10-23)', () => {
  const H = { ...CLEAN, served_by: 'sm-a-0000000a', served_by_pod: 'sm-a', memory: { ...CLEAN.memory, used_pct: 90 } }
  it('H1: pod başına sebepler pod adını taşır (bellek → sağlık pod’u, HTTP → /http-metrics pod’u); küme sebepleri taşımaz', () => {
    const o = deriveOverall({ health: { ...H, heartbeat: { ...CLEAN.heartbeat, pods_silent: 1 } },
      httpMetrics: { ...HTTP_OK, served_by_pod: 'sm-b', summary: { ...HTTP_OK.summary, error_rate_pct: 7 } }, pushKpi: PUSH_OK })
    expect(o.reasons.find((r) => r.code === 'memory').pod).toBe('sm-a')
    expect(o.reasons.find((r) => r.code === 'httpErr').pod).toBe('sm-b')
    expect(o.reasons.find((r) => r.code === 'podSilent').pod).toBeUndefined()
  })
  it('H1: bellek kıvılcımı yalnız aynı pod’dan (metrics pod’u farklıysa çizilmez); pod adları KPI modelinde', () => {
    const metrics = [{ heap_pct: 40 }, { heap_pct: 50 }]
    expect(deriveKpis({ health: H, httpMetrics: HTTP_OK, metrics, metricsPod: 'sm-a', pushKpi: PUSH_OK }).memory.series).toHaveLength(2)
    expect(deriveKpis({ health: H, httpMetrics: HTTP_OK, metrics, metricsPod: 'sm-b', pushKpi: PUSH_OK }).memory.series).toHaveLength(0)
    expect(deriveKpis({ health: CLEAN, httpMetrics: HTTP_OK, metrics, pushKpi: PUSH_OK }).memory.series).toHaveLength(2)   // eski sunucu
    expect(deriveKpis({ health: H, httpMetrics: { ...HTTP_OK, served_by_pod: 'sm-b' }, metrics, metricsPod: 'sm-c', pushKpi: PUSH_OK }).pods)
      .toEqual({ health: 'sm-a', http: 'sm-b', metrics: 'sm-c' })
  })
  it('H2: tarama durumu bu pod belleğindeyse (local) zamanlayıcı KPI "bilinmiyor"; alarm varsa yine kritik', () => {
    expect(deriveKpis({ health: { ...CLEAN, scheduler: { ...CLEAN.scheduler, status_source: 'local' } }, pushKpi: PUSH_OK }).scheduler.level).toBe('unknown')
    expect(deriveKpis({ health: { ...CLEAN, scan: { ...CLEAN.scan, source: 'local' }, scan_alarm: true }, pushKpi: PUSH_OK }).scheduler.level).toBe('down')
    expect(deriveKpis({ health: CLEAN, pushKpi: PUSH_OK }).scheduler.level).toBe('ok')
  })
  it('H4: önbellek yolu son başarılı yoklaması 30 sn’den eski / hiç yok ama yoklama sayılmış → bayat (warn); yeni açılmış → bilinmiyor', () => {
    const now = '2026-10-23T05:00:00'
    expect(cacheBusStale({ enabled: true, running: true, last_poll_ok_at: '2026-10-23T04:59:58' }, now)).toBe(false)
    expect(cacheBusStale({ enabled: true, running: true, last_poll_ok_at: '2026-10-23T04:58:00' }, now)).toBe(true)
    expect(cacheBusStale({ enabled: true, running: true, last_poll_ok_at: null, poll_failures: 12 }, now)).toBe(true)
    expect(cacheBusStale({ enabled: true, running: true, last_poll_ok_at: null, polls: 0 }, now)).toBeNull()
    expect(cacheBusStale({ enabled: false }, now)).toBe(false)
    const base = { served_by_pod: 'sm-a', timestamp: now, scheduler: { sweep_leader: { owner: 'x-0000000a', owner_pod: 'x', expired: false, held_by_me: false } } }
    expect(clusterModel({ ...base, cache_bus: { enabled: true, running: true, last_poll_ok_at: '2026-10-23T04:58:00', poll_failures: 60 } }))
      .toMatchObject({ cacheLevel: 'warn', cacheStale: true, level: 'warn' })
    expect(clusterModel({ ...base, cache_bus: { enabled: true, running: true, last_poll_ok_at: '2026-10-23T04:59:59' } })).toMatchObject({ cacheLevel: 'ok', level: 'ok' })
  })
  it('H5: LOST kilit kartı uyarıya çeker', () => {
    const base = { scheduler: { sweep_leader: { owner: 'x-0000000a', owner_pod: 'x', expired: false, held_by_me: true } } }
    expect(clusterModel({ ...base, scheduler: { ...base.scheduler, held_locks: [{ name: 'watchdog', state: 'HELD' }] } }).level).toBe('ok')
    expect(clusterModel({ ...base, scheduler: { ...base.scheduler, held_locks: [{ name: 'watchdog', state: 'LOST' }] } }))
      .toMatchObject({ level: 'warn', lostLocks: 1 })
  })
})
