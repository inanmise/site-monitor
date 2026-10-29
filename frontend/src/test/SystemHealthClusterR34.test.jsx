import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor } from './test-utils.jsx'
import SystemHealth from '../components/admin/SystemHealth.jsx'

/**
 * Sistem Sağlığı → Zamanlayıcı → Küme kartı + Sertifika taraması kartı, çok pod Sürüm 3-4 (2026-10-29, paket C1; tasarım R3-4
 * §7.1 / §7.2): "Parçalı sertifika taraması" bölümü — anahtar, son turun kipi, açık turun ilerlemesi, kutucuklar, pod başına pay
 * (kap genişliği — jsdom 0 → tablo), son koşular, "Bu pod" satırı; tarama kartında tur kipi + açık parçalı tur. Açık anahtar
 * yeşil DEĞİL; okunamayan durum bilinmiyor (gri); aykırı pod / ikinci görüş kartı uyarıya çeker; eski sunucu → gizli.
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
  timestamp: '2026-10-29T09:00:45', served_by: A, served_by_pod: POD_A,
  scheduler: { running: true, last_run: '2026-10-29T08:01:10', instance_id: A, active_domains: 400, status_source: 'cluster',
    sweep_leader: { owner: A, owner_pod: POD_A, until: '2026-10-29T09:03:00', expired: false, held_by_me: true, renewer: true },
    held_locks: [], round_claim_failures: 0, skipped_rounds: [] },
  build: { version: '20.171.0', commit: 'a1b2c3d', environment: 'prod', started_at: '2026-10-29T01:00:00', uptime_seconds: 3600 },
  lock: { held: true },
  pool: { active: 3, idle: 7, total: 10, waiting: 0, max_size: 25 },
  executor_pool: { queue_size: 0, queue_capacity: 1000, active_count: 1, pool_size: 20, core_pool_size: 20, max_pool_size: 50, completed_tasks: 1, caller_runs: 0, saturated: false },
  memory: { used_mb: 812, free_mb: 236, total_mb: 1048, max_mb: 2048, used_pct: 39 },
  scan: { last_run: '2026-10-29T08:01:10', last_run_kind: 'hourly', duration_ms: 70_000, total: 400, warnings: 0, errors: 0, source: 'cluster',
    runs: { hourly: { kind: 'hourly', last_run: '2026-10-29T08:01:10', run_id: 'r-1', total: 400, by_pod: POD_B, finished_at: '2026-10-29T08:01:10' } } },
  scan_alarm: false, db_ms: 14,
  smtp: { total: 1, attempted: 1, sent: 1, rate: 100, alarm: false, periods: { '7d': { total: 1, attempted: 1, sent: 1, rate: 100, alarm: false } } },
  heartbeat: { last_heartbeat: '2026-10-29T08:59:00', minutes_since: 1, alarm: false, recent: [] },
  network: { alarm: false }, domain_expiry: { source: 'RDAP', alarm: false },
  cleanup: { hold_active: false, last_run: '2026-10-29T01:00:00', hours_since: 8, total_deleted: 0, failed_count: 0, never_run: false, alarm: false },
}
const RUN = (over = {}) => ({
  run_id: 'r-open', kind: 'hourly', state: 'RUNNING', coordinator: A, coordinator_pod: POD_A, created_at: '2026-10-29T09:00:00',
  age_s: 45, heartbeat_age_s: 3, domains_total: 400, chunks_total: 20, chunk_size: 20, evict_caches: true,
  chunks: { pending: 6, claimed: 4, done: 9, cancelled: 1 }, domains_measured: 200, oldest_pending_age_s: 4, oldest_pending_age_ms: 4_000,
  per_pod: [
    { instance_id: A, pod: POD_A, self: true, chunks: 7, domains: 140, done_domains: 100, network_errors: 1, network_error_rate: 0.01, outlier: false },
    { instance_id: `${POD_B}-0000000b`, pod: POD_B, self: false, chunks: 6, domains: 120, done_domains: 100, network_errors: 0, network_error_rate: 0, outlier: false },
  ],
  outlier_pods: 0, takeovers: null, reclaims: null, second_opinions: null, ...over,
})
const LAST = (over = {}) => ({ run_id: 'r-1', kind: 'hourly', state: 'DONE', outcome: 'DECIDED', coordinator_pod: POD_B,
  created_at: '2026-10-29T08:00:00', finished_at: '2026-10-29T08:01:10', duration_ms: 70_000, domains_total: 400, chunks_total: 20,
  pods: 2, workers: [], takeovers: 1, reclaims: 0, second_opinions: 0, ...over })
const CC = (over = {}) => ({ enabled: true, tables_ready: true, at: '2026-10-29T09:00:45', db_clock_stale: false, run: RUN(),
  last_runs: [LAST(), LAST({ run_id: 'r-0', state: 'ABANDONED', outcome: 'SHUTDOWN', duration_ms: null, pods: null })],
  min_errors: 3, error_rate_threshold: 0.5, last_mode: 'chunked',
  this_pod: { pod: POD_A, worker: { enabled: true, started: true, inflight_chunks: 2, inflight_domains: 40, budget: 40, leader_yielding: true,
    claimed: 9, completed: 7, late: 1, released: 0, discarded: 0, domains_measured: 140, last_claim_at: '2026-10-29T09:00:40', db_clock_stale: false },
  coordinator: { chunked_runs: 3, classic_fallback: 1, takeovers: 2, remeasured: 0, second_opinions: 0, abandoned: 1 } }, ...over })
const SCAN_ON = (over = {}) => ({ ...BASE.scan, last_mode: 'chunked',
  runs: { hourly: { ...BASE.scan.runs.hourly, mode: 'chunked' } },
  chunked: { run_id: 'r-open', kind: 'hourly', state: 'RUNNING', coordinator_pod: POD_A, age_s: 45, chunks_done: 9, chunks_total: 20,
    domains_measured: 200, domains_total: 400, oldest_pending_age_s: 4, per_pod: [{ pod: POD_A, chunks: 7, domains: 140 }, { pod: POD_B, chunks: 6, domains: 120 }] },
  ...over })

const overall = () => document.querySelector('[data-slot="health-overall"]')
const card = () => document.querySelector('[data-card="cluster"]')
const scanCard = () => document.querySelector('[data-card="scan"]')
const sec = () => card().querySelector('[data-slot="cluster-cert-chunks"]')
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

describe('SystemHealth — Küme kartı, çok pod R3-4 (parçalı sertifika taraması)', () => {
  it('eski sunucu (cert_chunks yok) → bölüm, "Bu pod" satırı ve tarama kartı kipi gizli', async () => {
    await open(BASE)
    expect(sec()).toBeNull()
    expect(card().querySelector('[data-slot="cluster-cert-chunk-worker"]')).toBeNull()
    expect(scanCard().querySelector('[data-slot="scan-mode"]')).toBeNull()
    expect(scanCard().querySelector('[data-slot="scan-chunked"]')).toBeNull()
  })

  it('anahtar kapalı → "Kapalı" (gri) + nasıl açılır ipucu; ilerleme, son koşular ve "Bu pod" satırı yok; kart uyarı değil', async () => {
    await open({ ...BASE, cert_chunks: { enabled: false } })
    expect(sec()).toHaveAttribute('data-status', 'off')
    expect(sec().querySelector('[data-slot="cc-switch"] [data-tone]')).toHaveAttribute('data-tone', 'muted')
    expect(sec().querySelector('[data-slot="cc-hint"]').textContent).toMatch(/Scheduler|Zamanlayıcı/)
    expect(sec().querySelector('[data-slot="cc-progress"]')).toBeNull()
    expect(sec().querySelector('[data-slot="cc-last-runs"]')).toBeNull()
    expect(sec().querySelector('[data-slot="cc-last-mode"]')).toBeNull()
    expect(card().querySelector('[data-slot="cluster-cert-chunk-worker"]')).toBeNull()
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'success')
  })

  it('açık tur: Küme etiketi, BİLGİ tonu (yeşil değil), ilerleme, kutucuklar, pod tablosu, son koşular, bu pod satırı; tarama kartı', async () => {
    await open({ ...BASE, scan: SCAN_ON(), cert_chunks: CC() })
    const s = sec()
    expect(s).toHaveAttribute('data-status', 'running')
    expect(s.querySelector('[data-slot="pod-chip"]')).toHaveAttribute('data-scope', 'cluster')
    expect(s.querySelector('[data-slot="cc-switch"] [data-tone]')).toHaveAttribute('data-tone', 'info')
    expect(s.querySelector('[data-slot="cc-last-mode"]')).toHaveAttribute('data-mode', 'chunked')
    expect(s.querySelector('[data-slot="cc-run"]').textContent).toContain(POD_A)
    expect(s.querySelector('[data-slot="cc-progress-text"]').textContent).toMatch(/10 \/ 20/)
    expect(s.querySelector('[data-slot="cc-progress-text"]').textContent).toMatch(/200 \/ 400/)
    expect(s.querySelector('[data-slot="cc-t-pending"]').textContent).toContain('6')
    expect(s.querySelector('[data-slot="cc-t-oldest"]')).not.toHaveAttribute('data-warn')
    expect(s.querySelector('[data-slot="cc-t-takeovers"]').textContent).toContain('1')
    const pods = s.querySelector('[data-slot="cc-pods"]')
    expect(pods).toHaveAttribute('data-layout', 'table')
    expect(pods.querySelectorAll('[data-slot="cc-pod"]')).toHaveLength(2)
    expect(pods.querySelector('[data-slot="cc-pod"][data-self="true"] [data-slot="cc-pod-self"]')).not.toBeNull()
    expect(pods.textContent).toContain(POD_B)
    expect(pods.querySelector('[data-slot="cc-pod-outlier"]')).toBeNull()
    const last = [...s.querySelectorAll('[data-slot="cc-last-run"]')]
    expect(last).toHaveLength(2)
    expect(last[0]).toHaveAttribute('data-tone', 'success')
    expect(last[1]).toHaveAttribute('data-state', 'ABANDONED')
    expect(last[1].querySelector('[data-slot="cc-last-outcome"]')).toHaveAttribute('data-tone', 'warning')
    expect(last[1].textContent).toMatch(/shutdown|kapanış/i)
    expect(s.querySelector('[data-slot="cc-warnings"]')).toBeNull()
    // Bu pod satırı
    const pod = card().querySelector('[data-slot="cluster-this-pod"] [data-slot="cluster-cert-chunk-worker"]')
    expect(pod.querySelector('[data-slot="cc-pod-inflight"]').textContent).toMatch(/40/)
    // db08a982 (C2 B-1) metni değiştirdi: lider artık "yarı" değil "sınırlı güçle" alır
    expect(pod.querySelector('[data-slot="cc-pod-inflight"]').textContent).toMatch(/limited capacity|sınırlı güçle/)
    expect(pod.querySelector('[data-slot="cc-pod-counts"]').textContent).toMatch(/7/)
    expect(pod.querySelector('[data-slot="cc-pod-coordinator"]').textContent).toMatch(/3/)
    // sıra: parçalı tarama bölümü "Bu pod"dan önce
    const order = [...card().querySelectorAll('[data-slot="cluster-cert-chunks"], [data-slot="cluster-this-pod"]')].map((n) => n.getAttribute('data-slot'))
    expect(order).toEqual(['cluster-cert-chunks', 'cluster-this-pod'])
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'success')
    // tarama kartı: kip + açık parçalı tur + tür satırında parçalı rozeti
    expect(scanCard().querySelector('[data-slot="scan-mode"]')).toHaveAttribute('data-mode', 'chunked')
    expect(scanCard().querySelector('[data-slot="scan-chunked"]').textContent).toMatch(/9 \/ 20/)
    expect(scanCard().querySelector('[data-slot="scan-chunked"]').textContent).toContain(POD_B)
    expect(scanCard().querySelector('[data-slot="scan-run"][data-kind="hourly"] [data-slot="scan-run-mode"]')).not.toBeNull()
    // ham anahtar basılmaz
    expect(card().textContent).not.toMatch(/hmp\.cc\./)
    expect(scanCard().textContent).not.toMatch(/hmp\.cc\./)
  })

  it('aykırı pod + ikinci görüş → uyarı tonu, aykırı rozeti, afiş pod adını söyler, kart uyarıya çekilir', async () => {
    const run = RUN({ second_opinions: 12, per_pod: [RUN().per_pod[0], { ...RUN().per_pod[1], network_errors: 60, network_error_rate: 0.6, outlier: true }] })
    await open({ ...BASE, cert_chunks: CC({ run }) })
    expect(sec()).toHaveAttribute('data-status', 'warn')
    expect(sec().querySelector('[data-slot="cc-switch"] [data-tone]')).toHaveAttribute('data-tone', 'warning')
    expect(sec().querySelector('[data-slot="cc-pod"][data-outlier="true"] [data-slot="cc-pod-outlier"]')).not.toBeNull()
    expect(sec().querySelector('[data-slot="cc-t-second"]')).toHaveAttribute('data-warn', 'true')
    const warns = sec().querySelector('[data-slot="cc-warnings"]')
    expect(warns.querySelector('[data-warn="outlier"]').textContent).toContain(POD_B)
    expect(warns.querySelector('[data-warn="secondOpinion"]')).not.toBeNull()
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'warning')
  })

  it('ikinci görüş hataları doğruladı (FX2-COORD L-4) → P1 uyarısı yok: bilgi satırı sayılarla, kutucuk ve son koşu uyarı tonunda '
    + 'değil, kart uyarıya çekilmez', async () => {
    await open({ ...BASE, cert_chunks: CC({
      run: RUN({ state: 'DECIDING', chunks: { pending: 0, claimed: 0, done: 20, cancelled: 0 }, oldest_pending_age_ms: null,
        oldest_pending_age_s: null, second_opinions: 12, second_opinion_ok: 0, second_opinion_failed: 12 }),
      last_runs: [LAST({ second_opinions: 9, second_opinion_ok: 1, second_opinion_failed: 8 })] }) })
    expect(sec()).toHaveAttribute('data-status', 'running')
    expect(sec().querySelector('[data-slot="cc-warnings"]')).toBeNull()
    const info = sec().querySelector('[data-slot="cc-infos"] [data-info="secondOpinionConfirmed"]')
    expect(info.textContent).toContain('12')
    expect(info.textContent).not.toMatch(/P1/)
    expect(info.textContent).not.toMatch(/hmp\./)
    expect(sec().querySelector('[data-slot="cc-t-second"]')).not.toHaveAttribute('data-warn')
    expect(sec().querySelector('[data-slot="cc-last-second"]')).toHaveAttribute('data-outcome', 'confirmed')
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'success')
  })

  it('ikinci görüşte çoğu başka pod\'da başarılı (FX2-COORD L-4) → P1 önerili uyarı sayılarla; hiçbiri ölçülemedi → doğrulanamadı uyarısı',
    async () => {
      const r = await open({ ...BASE, cert_chunks: CC({ run: null,
        last_runs: [LAST({ second_opinions: 20, second_opinion_ok: 14, second_opinion_failed: 6 })] }) })
      let w = sec().querySelector('[data-slot="cc-warnings"] [data-warn="secondOpinionLastEgress"]')
      expect(w.textContent).toMatch(/20/)
      expect(w.textContent).toMatch(/14/)
      expect(w.textContent).toMatch(/P1/)
      expect(sec().querySelector('[data-slot="cc-infos"]')).toBeNull()
      expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'warning')
      r.unmount()
      await open({ ...BASE, cert_chunks: CC({ run: RUN({ state: 'DECIDING', second_opinions: 60, second_opinion_ok: 0,
        second_opinion_failed: 0 }) }) })
      w = sec().querySelector('[data-slot="cc-warnings"] [data-warn="secondOpinionUnverified"]')
      expect(w.textContent).toContain('60')
      expect(w.textContent).toMatch(/P1/)
      expect(sec().querySelector('[data-slot="cc-t-second"]')).toHaveAttribute('data-warn', 'true')
    })

  it('karar aşaması (DECIDING, eski nabız) → uyarı yok, "karar veriliyor" notu; kart uyarı değil', async () => {
    await open({ ...BASE, cert_chunks: CC({ run: RUN({ state: 'DECIDING', heartbeat_age_s: 240, chunks: { pending: 0, claimed: 0, done: 20, cancelled: 0 },
      oldest_pending_age_ms: null, oldest_pending_age_s: null }) }) })
    expect(sec()).toHaveAttribute('data-status', 'running')
    expect(sec().querySelector('[data-slot="cc-run-deciding"]')).not.toBeNull()
    expect(sec().querySelector('[data-slot="cc-warnings"]')).toBeNull()
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'success')
  })

  it('kuyruk duruyor (bekleyen var, hiçbir pod ölçmüyor) ve en eski bekleyen 30 sn üstü → kutucuk uyarı + afiş; kart uyarı (OB-M-1)', async () => {
    await open({ ...BASE, cert_chunks: CC({ run: RUN({ chunks: { pending: 6, claimed: 0, done: 9, cancelled: 1 },
      oldest_pending_age_ms: 45_000, oldest_pending_age_s: 45 }) }) })
    expect(sec().querySelector('[data-slot="cc-t-oldest"]')).toHaveAttribute('data-warn', 'true')
    expect(sec().querySelector('[data-slot="cc-warnings"] [data-warn="pending"]')).not.toBeNull()
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'warning')
  })

  it('kuyruk işleniyor (pod’lar parça ölçüyor) → en eski bekleyen 45 sn olsa da uyarı YOK; kart uyarı değil (OB-M-1)', async () => {
    await open({ ...BASE, cert_chunks: CC({ run: RUN({ chunks: { pending: 18, claimed: 5, done: 9, cancelled: 1 },
      oldest_pending_age_ms: 45_000, oldest_pending_age_s: 45 }) }) })
    expect(sec()).toHaveAttribute('data-status', 'running')
    expect(sec().querySelector('[data-slot="cc-t-oldest"]')).not.toHaveAttribute('data-warn')
    expect(sec().querySelector('[data-slot="cc-warnings"]')).toBeNull()
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'success')
  })

  it('sahipsiz tur (koordinatör kilidi artık tutmuyor) → "terk edilmiş tur" satırı; ilerleme / kutucuk / afiş YOK, kart uyarı değil; '
    + 'tarama kartı ilerleme yerine terk edilmiş tur (OB-L1)', async () => {
    const orphan = RUN({ orphan_suspected: true, coordinator_live: false, state: 'DECIDING', age_s: 5_400, heartbeat_age_s: 5_000,
      chunks: { pending: 6, claimed: 0, done: 9, cancelled: 1 }, oldest_pending_age_ms: 5_400_000 })
    await open({ ...BASE, scan: SCAN_ON({ chunked: { ...SCAN_ON().chunked, orphan_suspected: true } }), cert_chunks: CC({ run: orphan }) })
    expect(sec()).toHaveAttribute('data-status', 'idle')
    expect(sec().querySelector('[data-slot="cc-run-orphan"]').textContent).toContain(POD_A)
    expect(sec().querySelector('[data-slot="cc-run"]')).toBeNull()
    expect(sec().querySelector('[data-slot="cc-progress"]')).toBeNull()
    expect(sec().querySelector('[data-slot="cc-warnings"]')).toBeNull()
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'success')
    const sc = scanCard().querySelector('[data-slot="scan-chunked"]')
    expect(sc).toHaveAttribute('data-orphan', 'true')
    expect(sc.textContent).not.toMatch(/9 \/ 20/)
    expect(card().textContent).not.toMatch(/hmp\.cc\./)
  })

  it('küçük ağ hatası oranı iki anlamlı basamakla (asla "%0", OB-L8); klasiğe dönen tur bilgi tonu + klasik kip (OB-L3); bölünmüş '
    + 'koordinatör sayacı (OB-L6); son turda ikinci görüş → uyarı sürer (OB-L4)', async () => {
    const run = RUN({ per_pod: [{ ...RUN().per_pod[0], done_domains: 2500, network_errors: 1, network_error_rate: 0.0004 }, RUN().per_pod[1]] })
    await open({ ...BASE, cert_chunks: CC({ run,
      last_runs: [LAST({ second_opinions: 12 }), LAST({ run_id: 'r-fb', state: 'ABANDONED', outcome: 'FAILED', duration_ms: null, pods: null })],
      this_pod: { ...CC().this_pod, coordinator: { ...CC().this_pod.coordinator, classic_by_rule: 280, classic_fallback: 2 } } }) })
    const self = sec().querySelector('[data-slot="cc-pod"][data-self="true"]')
    expect(self.textContent).toMatch(/0[.,]04/)
    expect(self.textContent).not.toMatch(/%0(?![.,])|(?<![\d.,])0%/)
    const rows = [...sec().querySelectorAll('[data-slot="cc-last-run"]')]
    expect(rows[1]).toHaveAttribute('data-tone', 'info')
    expect(rows[1].querySelector('[data-mode]')).toHaveAttribute('data-mode', 'classic')
    expect(rows[1].textContent).toMatch(/classic gather|klasik toplamaya/)
    expect(sec().querySelector('[data-slot="cc-warnings"] [data-warn="secondOpinionLast"]').textContent).toContain('12')
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'warning')
    const coord = card().querySelector('[data-slot="cc-pod-coordinator"]')
    expect(coord).toHaveAttribute('data-split', 'true')
    expect(coord.textContent).toContain('280')
    expect(coord.textContent).toMatch(/by rule|kural gereği/)
  })

  it('durum okunamadı → bilinmiyor (gri) + neden; ilerleme / son koşular yok; kart uyarı DEĞİL ve asla yeşil "Açık" değil', async () => {
    await open({ ...BASE, scan: { ...BASE.scan, last_mode: 'unknown', chunked: null, chunked_error: 'QueryTimeoutException' },
      cert_chunks: { enabled: true, status_error: 'QueryTimeoutException', run: null, last_runs: null, last_mode: 'unknown',
        min_errors: 3, error_rate_threshold: 0.5, this_pod: { pod: POD_A, worker: null, coordinator: null } } })
    expect(sec()).toHaveAttribute('data-status', 'unknown')
    expect(sec().querySelector('[data-slot="cc-switch"] [data-tone]')).toHaveAttribute('data-tone', 'muted')
    expect(sec().querySelector('[data-slot="cc-hint"]').textContent).toContain('QueryTimeoutException')
    expect(sec().querySelector('[data-slot="cc-progress"]')).toBeNull()
    expect(sec().querySelector('[data-slot="cc-last-runs"]')).toBeNull()
    expect(sec().querySelector('[data-slot="cc-last-none"]')).toBeNull()
    expect(sec().querySelector('[data-slot="cc-last-mode"]')).toHaveAttribute('data-mode', 'unknown')
    expect(card().querySelector('[data-slot="cc-pod-unknown"]')).not.toBeNull()
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'success')
    expect(scanCard().querySelector('[data-slot="scan-mode"]')).toHaveAttribute('data-mode', 'unknown')
    expect(scanCard().querySelector('[data-slot="scan-mode"]')).toHaveAttribute('data-tone', 'muted')
  })

  it('açık ama tur yok → "şu an tur yok" (bilgi) + açıklama; işçi kapalı pod → kapalı satırı', async () => {
    await open({ ...BASE, cert_chunks: CC({ run: null, last_runs: [], this_pod: { pod: POD_A, worker: { enabled: false }, coordinator: null } }) })
    expect(sec()).toHaveAttribute('data-status', 'idle')
    expect(sec().querySelector('[data-slot="cc-switch"] [data-tone]')).toHaveAttribute('data-tone', 'info')
    expect(sec().querySelector('[data-slot="cc-hint"]').textContent).toMatch(/40/)
    expect(sec().querySelector('[data-slot="cc-last-none"]')).not.toBeNull()
    expect(card().querySelector('[data-slot="cc-pod-off"]').textContent).toContain('CERT_CHUNK_WORKER_ENABLED')
  })

  it('tek pod dalı (kira yok): bölüm yine çizilir, "Bu pod" satırı da', async () => {
    const single = { ...BASE, scheduler: { ...BASE.scheduler, sweep_leader: undefined }, cert_chunks: CC({ run: null }) }
    await open(single)
    expect(card().querySelector('[data-slot="cluster-single"]')).not.toBeNull()
    expect(sec()).toHaveAttribute('data-status', 'idle')
    expect(card().querySelector('[data-slot="cluster-cert-chunk-worker"]')).not.toBeNull()
  })
})
