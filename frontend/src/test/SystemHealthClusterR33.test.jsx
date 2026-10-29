import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor } from './test-utils.jsx'
import SystemHealth from '../components/admin/SystemHealth.jsx'

/**
 * Sistem Sağlığı → Zamanlayıcı → Küme kartı, çok pod Sürüm 3-3 bölümü (2026-10-29, paket D1; tasarım R3-3 §7.2): "Dağıtık
 * kontroller" — ana anahtar, tür kipleri (yanıtlayan pod'un görüşü; yalnız liderde anlamlı), kuyruk kutucukları, işçiler (kap
 * genişliği — jsdom 0 → tablo), yetenek uyuşmazlığı uyarısı ve "Bu pod" satırı. Açık anahtar yeşil DEĞİL; okunamayan kuyruk
 * bilinmiyor (gri); 30 sn'den eski iş / sonuç kartı uyarıya çeker; eski sunucu (alan yok) → bölüm gizli.
 */
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => true, canExecute: () => true, perms: {} }),
}))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => false }))

const { adminProxy } = vi.hoisted(() => {
  const target = {
    getSystemHealth: vi.fn(), getMetrics: vi.fn(), getHttpMetrics: vi.fn(), getConfigHealth: vi.fn(),
    getHeartbeatTimeline: vi.fn(), getWatchdog: vi.fn(), pushLog: { summary: vi.fn() },
  }
  return {
    adminProxy: new Proxy(target, {
      get(t, prop) {
        if (prop in t || typeof prop === 'symbol') return t[prop]
        t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
        return t[prop]
      },
    }),
  }
})
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: { admin: adminProxy, runScheduler: vi.fn(() => Promise.resolve({ success: true })) },
}))

import { api } from '../api/client'

const A = 'sitemonitor-6b7c9d-aaaaa-0000000a'
const POD_A = 'sitemonitor-6b7c9d-aaaaa'
const POD_B = 'site-monitor-production-deployment-7f9c8d6b5a-q8m2zr'
const BASE = {
  timestamp: '2026-10-29T09:00:00', served_by: A, served_by_pod: POD_A,
  scheduler: { running: false, last_run: '2026-10-29T08:00:00', instance_id: A, active_domains: 10, status_source: 'cluster',
    sweep_leader: { owner: A, owner_pod: POD_A, until: '2026-10-29T09:03:00', expired: false, held_by_me: true, renewer: true },
    held_locks: [], round_claim_failures: 0, skipped_rounds: [] },
  build: { version: '20.170.0', commit: 'a1b2c3d', environment: 'prod', started_at: '2026-10-29T01:00:00', uptime_seconds: 3600 },
  lock: { held: false },
  pool: { active: 3, idle: 7, total: 10, waiting: 0, max_size: 25 },
  executor_pool: { queue_size: 0, queue_capacity: 1000, active_count: 1, pool_size: 20, core_pool_size: 20, max_pool_size: 50, completed_tasks: 1, caller_runs: 0, saturated: false },
  memory: { used_mb: 812, free_mb: 236, total_mb: 1048, max_mb: 2048, used_pct: 39 },
  scan: { last_run: '2026-10-29T08:00:00', duration_ms: 42150, total: 10, warnings: 0, errors: 0, source: 'cluster', runs: {} },
  scan_alarm: false, db_ms: 14,
  smtp: { total: 1, attempted: 1, sent: 1, rate: 100, alarm: false, periods: { '7d': { total: 1, attempted: 1, sent: 1, rate: 100, alarm: false } } },
  heartbeat: { last_heartbeat: '2026-10-29T08:59:00', minutes_since: 1, alarm: false, recent: [] },
  network: { alarm: false }, domain_expiry: { source: 'RDAP', alarm: false },
  cleanup: { hold_active: false, last_run: '2026-10-29T01:00:00', hours_since: 8, total_deleted: 0, failed_count: 0, never_run: false, alarm: false },
  eval_state: {
    this_pod: { mode: 'verify', active: true, writer: A, writer_pod: POD_A, generation: 12, pending: 0, dropped: 0, flushes: 40,
      write_failures: 0, last_flush_at: '2026-10-29T08:59:59', last_failure_at: null, last_failure: null, table_missing: false,
      failing: false, fenced: false, hook_failures: 0, inherited: null, last_verify: null },
    verify: { observed_by: A, observed_by_pod: POD_A, observed_at: '2026-10-29T08:58:00', age_seconds: 120, stale_after_seconds: 900,
      stale: false, generation: 12, status: 'ok', reason: null, pass_fail_mismatches: 0, first_mismatches: [],
      kinds: { recovery: { compared: 40, mismatches: 0, skipped: 0, measured: false } } },
  },
}
const WORKER = (over = {}) => ({ instance_id: `${POD_B}-5e6f7a8b`, pod: POD_B, self: false,
  caps: ['port', 'ping', 'http', 'keyword', 'page', 'pagespeed', 'dns', 'scripted'], missing_caps: [], slots: 16, busy: 4, completed: 18240,
  late: 3, claim_lag_p95_ms: 820, heartbeat_at: '2026-10-29T08:59:55', heartbeat_age_ms: 5000, version: '20.170.0', stopping: false,
  live: true, ...over })
const CJ = (over = {}) => ({
  enabled: true, types: ['port', 'ping'],
  mode: { port: 'distributed', ping: 'classic', http: 'classic', keyword: 'classic', page: 'classic', pagespeed: 'classic', dns: 'classic', scripted: 'classic' },
  mode_by: A, mode_by_pod: POD_A, mode_by_leader: true,
  queue: { at: '2026-10-29T09:00:00', pending: 12, claimed: 5, done: 2, oldest_pending_age_ms: 4000, oldest_done_age_ms: 900, expired_claims: 0,
    by_type: { port: { pending: 12, claimed: 5, done: 2 }, ping: { pending: 0, claimed: 0, done: 0 } } },
  workers_known: true, workers_total: 2,
  workers: [WORKER(), WORKER({ instance_id: A, pod: POD_A, self: true, slots: 16, busy: 2, completed: 9120, late: 0, claim_lag_p95_ms: 410 })],
  this_pod: { pod: POD_A, leader: true, worker: { started: true, enabled: true, closing: false, slots: 16, busy: 2, claimed: 9200,
    completed: 9120, late: 0, timeouts: 1, failed: 2, released: 0, claim_errors: 0, complete_errors: 0, dropped_at_shutdown: 0,
    claim_lag_p95_ms: 410, poll_delay_ms: 1000 }, local_caps: ['port', 'ping', 'http', 'keyword', 'page', 'pagespeed', 'dns', 'scripted'],
  db_clock_stale: false,
  dispatch: { dispatch_active: true, dispatched: 27400, dispatch_failures: 0, lost: 1, applied: 27300, dropped_inactive: 4,
    dropped_target: 1, dropped_toggle: 0, dropped_inventory: 0, no_result: 6 },
  drainer: { started: true, draining: true, taken: 27300, waves: 9800, dropped_stale: 0, out_of_order: 0, apply_failures: 0,
    take_failures: 0, last_take_at: '2026-10-29T08:59:59' } },
  ...over,
})

const overall = () => document.querySelector('[data-slot="health-overall"]')
const card = () => document.querySelector('[data-card="cluster"]')
const sec = () => card().querySelector('[data-slot="cluster-check-jobs"]')
async function open(data) {
  api.admin.getSystemHealth.mockResolvedValue({ success: true, data })
  window.history.replaceState(null, '', '/?sec=sched')
  const r = render(<SystemHealth systemRole="ADMIN" globalAdmin />)
  await waitFor(() => expect(overall()).not.toBeNull())
  await waitFor(() => expect(card()).not.toBeNull())
  return r
}

beforeEach(() => {
  vi.clearAllMocks()
  api.admin.getMetrics.mockResolvedValue({ success: true, data: [] })
  api.admin.getHttpMetrics.mockResolvedValue({ success: true, data: { summary: {}, history: [] } })
  api.admin.getConfigHealth.mockResolvedValue({ success: true, data: { overall: 'ok', checks: [] } })
  api.admin.getHeartbeatTimeline.mockResolvedValue({ success: true, data: { days: 1, bucket_minutes: 5, buckets: [] } })
  api.admin.pushLog.summary.mockResolvedValue({ success: true, data: { kpi: {} } })
  api.admin.getWatchdog.mockResolvedValue({ success: true, data: { status: 'UP', enabled: true, checks: [], channels: {} } })
})
afterEach(() => { window.history.replaceState(null, '', '/') })

describe('SystemHealth — Küme kartı, çok pod R3-3 (dağıtık kontroller)', () => {
  it('eski sunucu (check_jobs yok) → bölüm ve "Bu pod" satırı gizli', async () => {
    await open(BASE)
    expect(card().querySelector('[data-slot="cluster-eval-verify"]')).not.toBeNull()
    expect(sec()).toBeNull()
    expect(card().querySelector('[data-slot="cluster-check-worker"]')).toBeNull()
  })

  it('anahtar kapalı → "Kapalı" (gri) + nasıl açılır ipucu; kuyruk, işçiler ve "Bu pod" satırı yok; kart uyarı değil', async () => {
    await open({ ...BASE, check_jobs: { enabled: false } })
    expect(sec()).toHaveAttribute('data-status', 'off')
    expect(sec().querySelector('[data-slot="cj-switch"] [data-tone]')).toHaveAttribute('data-tone', 'muted')
    expect(sec().querySelector('[data-slot="cj-switch"]').textContent).toMatch(/Off|Kapalı/)
    expect(sec().querySelector('[data-slot="cj-hint"]').textContent).toMatch(/Scheduler|Zamanlayıcı/)
    expect(sec().querySelector('[data-slot="cj-queue"]')).toBeNull()
    expect(sec().querySelector('[data-slot="cj-workers"]')).toBeNull()
    expect(card().querySelector('[data-slot="cluster-check-worker"]')).toBeNull()
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'success')
  })

  it('açık (lider görüşü): Küme etiketi, "Açık" BİLGİ tonu (yeşil değil), kipler, kuyruk kutucukları, işçi tablosu, bu pod satırı; sıra', async () => {
    await open({ ...BASE, check_jobs: CJ() })
    const s = sec()
    expect(s).toHaveAttribute('data-status', 'on')
    expect(s.querySelector('[data-slot="pod-chip"]')).toHaveAttribute('data-scope', 'cluster')
    expect(s.querySelector('[data-slot="cj-switch"] [data-tone]')).toHaveAttribute('data-tone', 'info')
    const modes = [...s.querySelectorAll('[data-slot="cj-mode"]')]
    expect(modes).toHaveLength(8)
    expect(s.querySelector('[data-slot="cj-mode"][data-type="port"]')).toHaveAttribute('data-mode', 'distributed')
    expect(s.querySelector('[data-slot="cj-mode"][data-type="port"] [data-tone]')).toHaveAttribute('data-tone', 'info')
    expect(s.querySelector('[data-slot="cj-mode"][data-type="ping"]')).toHaveAttribute('data-mode', 'classic')
    expect(s.querySelector('[data-slot="cj-mode"][data-type="dns"]')).toHaveAttribute('data-mode', 'off')
    expect(s.querySelector('[data-slot="cj-mode"][data-type="port"]').textContent).toMatch(/Port: (distributed|dağıtık)/)
    expect(s.querySelector('[data-slot="cj-mode-leader"]')).not.toBeNull()
    expect(s.querySelector('[data-slot="cj-q-pending"]').textContent).toContain('12')
    expect(s.querySelector('[data-slot="cj-q-oldest-pending"]')).not.toHaveAttribute('data-warn')
    expect(s.querySelector('[data-slot="cj-q-oldest-pending"]').textContent).toMatch(/4 (sec|sn)/)
    expect(s.querySelector('[data-slot="cj-q-oldest-done"]').textContent).toMatch(/900 ms/)
    expect(s.querySelector('[data-slot="cj-q-claimed"]').textContent).toMatch(/5/)
    expect(s.querySelector('[data-slot="cj-q-by-type"]').textContent).toMatch(/Port: 12/)
    const workers = s.querySelector('[data-slot="cj-workers"]')
    expect(workers).toHaveAttribute('data-layout', 'table')
    expect(workers.querySelectorAll('[data-slot="cj-worker"]')).toHaveLength(2)
    expect(workers.querySelector('[data-slot="cj-worker"][data-self="true"] [data-slot="cj-worker-self"]')).not.toBeNull()
    expect(workers.textContent).toContain(POD_B)
    expect(workers.querySelector('[data-slot="cj-worker-state"]')).toHaveAttribute('data-state', 'live')
    expect(s.querySelector('[data-slot="cj-cap-mismatch"]')).toBeNull()
    // Bu pod satırı: yuvalar, işler, yetenekler, lider sayaçları ve boşaltıcı
    const pod = card().querySelector('[data-slot="cluster-this-pod"] [data-slot="cluster-check-worker"]')
    expect(pod).not.toBeNull()
    expect(pod.querySelector('[data-slot="cj-pod-slots"]').textContent).toMatch(/16/)
    expect(pod.querySelector('[data-slot="cj-pod-jobs"]').textContent).toMatch(/9120/)
    expect(pod.querySelector('[data-slot="cj-pod-leader"]').textContent).toMatch(/27400/)
    expect(pod.querySelector('[data-slot="cj-pod-leader"]').textContent).toMatch(/5/)
    expect(pod.querySelector('[data-slot="cj-pod-drainer"]').textContent).toMatch(/27300/)
    expect(pod.querySelector('[data-slot="cj-pod-clock-stale"]')).toBeNull()
    // sıra: doğrulama → dağıtık kontroller → bu pod
    const order = [...card().querySelectorAll('[data-slot="cluster-eval-verify"], [data-slot="cluster-check-jobs"], [data-slot="cluster-this-pod"]')]
      .map((n) => n.getAttribute('data-slot'))
    expect(order).toEqual(['cluster-eval-verify', 'cluster-check-jobs', 'cluster-this-pod'])
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'success')
    // ham kod basılmaz
    expect(s.textContent).not.toMatch(/hmp\.cj\./)
  })

  it('en eski bekleyen iş 30 sn üstü → kutucuk uyarı + açıklama, kart uyarıya çekilir', async () => {
    await open({ ...BASE, check_jobs: CJ({ queue: { ...CJ().queue, oldest_pending_age_ms: 95_000 } }) })
    expect(sec()).toHaveAttribute('data-status', 'warn')
    expect(sec().querySelector('[data-slot="cj-switch"] [data-tone]')).toHaveAttribute('data-tone', 'warning')
    expect(sec().querySelector('[data-slot="cj-q-oldest-pending"]')).toHaveAttribute('data-warn', 'true')
    expect(sec().querySelector('[data-slot="cj-q-warn"]')).not.toBeNull()
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'warning')
  })

  it('en eski değerlendirilmeyen sonuç 30 sn üstü → uyarı; kirası dolmuş sahiplenme gösterilir', async () => {
    await open({ ...BASE, check_jobs: CJ({ queue: { ...CJ().queue, oldest_done_age_ms: 61_000, expired_claims: 2 } }) })
    expect(sec().querySelector('[data-slot="cj-q-oldest-done"]')).toHaveAttribute('data-warn', 'true')
    expect(sec().querySelector('[data-slot="cj-q-expired"]').textContent).toMatch(/2/)
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'warning')
  })

  it('kuyruk okunamadı → bilinmiyor metni (gri), kutucuk yok, kart uyarı DEĞİL ve asla yeşil "Açık" değil', async () => {
    await open({ ...BASE, check_jobs: CJ({ queue: null, queue_error: 'CannotGetJdbcConnectionException' }) })
    expect(sec()).toHaveAttribute('data-status', 'unknown')
    expect(sec().querySelector('[data-slot="cj-switch"] [data-tone]')).toHaveAttribute('data-tone', 'muted')
    const q = sec().querySelector('[data-slot="cj-queue"]')
    expect(q).toHaveAttribute('data-level', 'unknown')
    expect(q.textContent).toContain('CannotGetJdbcConnectionException')
    expect(sec().querySelector('[data-slot="cj-q-pending"]')).toBeNull()
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'success')
  })

  it('yetenek uyuşmazlığı: canlı işçi seçili "ping"i koşamıyor → eksik yetenek rozeti + uyarı afişi, kart uyarı', async () => {
    await open({ ...BASE, check_jobs: CJ({ workers: [WORKER({ caps: ['port', 'http'], missing_caps: ['ping'] })], workers_total: 1 }) })
    expect(sec().querySelector('[data-slot="cj-worker"]')).toHaveAttribute('data-mismatch', 'true')
    expect(sec().querySelector('[data-slot="cj-cap-missing"][data-cap="ping"]')).not.toBeNull()
    const banner = sec().querySelector('[data-slot="cj-cap-mismatch"]')
    expect(banner.textContent).toContain(POD_B)
    expect(banner.textContent).toMatch(/Ping/)
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'warning')
  })

  it('takipçi yanıtı: seçili türlerin kipi bilinmiyor + liderin yanıtına yönlendiren not; bu pod satırında lider sayaçları yok', async () => {
    const follower = { ...BASE.scheduler, sweep_leader: { ...BASE.scheduler.sweep_leader, owner: 'x-0000000b', owner_pod: 'x', held_by_me: false } }
    await open({ ...BASE, scheduler: follower, check_jobs: CJ({ mode_by_leader: false, this_pod: { ...CJ().this_pod, leader: false } }) })
    expect(sec().querySelector('[data-slot="cj-mode"][data-type="port"]')).toHaveAttribute('data-mode', 'unknown')
    expect(sec().querySelector('[data-slot="cj-mode-follower"]').textContent).toContain(POD_A)
    expect(card().querySelector('[data-slot="cj-pod-leader"]')).toBeNull()
    expect(card().querySelector('[data-slot="cj-pod-drainer"]')).toBeNull()
  })

  it('işçi kaydı henüz okunmadı → "henüz okunmadı" (bilinmiyor), "kayıtlı işçi yok" DEĞİL; bu pod işçisi kapalı → söylenir', async () => {
    await open({ ...BASE, check_jobs: CJ({ workers: [], workers_known: false, workers_error: 'not_read_yet',
      this_pod: { ...CJ().this_pod, worker: { ...CJ().this_pod.worker, enabled: false }, db_clock_stale: true } }) })
    expect(sec().querySelector('[data-slot="cj-workers-unknown"]').textContent).toMatch(/not been read yet|henüz okunmadı/)
    expect(sec().querySelector('[data-slot="cj-workers-none"]')).toBeNull()
    expect(card().querySelector('[data-slot="cj-pod-off"]').textContent).toMatch(/CHECK_WORKER_ENABLED=false/)
    expect(card().querySelector('[data-slot="cj-pod-clock-stale"]')).not.toBeNull()
  })

  it('anahtar açık ama tür seçilmedi → "tür seçilmedi" (gri) + ipucu; kuyruk satırı yok', async () => {
    await open({ ...BASE, check_jobs: CJ({ types: [], queue: null, queue_skipped: 'no_types' }) })
    expect(sec()).toHaveAttribute('data-status', 'idle')
    expect(sec().querySelector('[data-slot="cj-switch"] [data-tone]')).toHaveAttribute('data-tone', 'muted')
    expect(sec().querySelector('[data-slot="cj-queue"]')).toBeNull()
    expect(sec().querySelector('[data-slot="cj-hint"]')).not.toBeNull()
  })
})
