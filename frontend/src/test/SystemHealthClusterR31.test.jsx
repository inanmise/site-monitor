import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor } from './test-utils.jsx'
import SystemHealth from '../components/admin/SystemHealth.jsx'

/**
 * Sistem Sağlığı → Zamanlayıcı → Küme kartı, çok pod Sürüm 3-1 satırları (2026-10-23, paket B): kalıcı değerlendirme
 * durumunun KÜME doğrulaması ("Küme" etiketli bölüm, gözlemleyen pod ile) ve yanıtlayan pod'un kaydedicisi + yan işleri
 * ("Bu pod" bölümü). Bilinmeyen / okunamayan / eski asla yeşil değil; eski sunucu (alan yok) → satırlar gizli, çökme yok.
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
const B = 'sitemonitor-6b7c9d-bbbbb-0000000b'
const BASE = {
  timestamp: '2026-10-23T09:00:00', served_by: A, served_by_pod: 'sitemonitor-6b7c9d-aaaaa',
  scheduler: { running: false, last_run: '2026-10-23T08:00:00', instance_id: A, active_domains: 10, status_source: 'cluster',
    sweep_leader: { owner: B, owner_pod: 'sitemonitor-6b7c9d-bbbbb', until: '2026-10-23T09:03:00', expired: false, held_by_me: false, renewer: true },
    held_locks: [], round_claim_failures: 0, skipped_rounds: [] },
  build: { version: '20.168.0', commit: 'a1b2c3d', environment: 'prod', started_at: '2026-10-22T01:00:00', uptime_seconds: 3600 },
  lock: { held: false },
  pool: { active: 3, idle: 7, total: 10, waiting: 0, max_size: 25 },
  executor_pool: { queue_size: 0, queue_capacity: 1000, active_count: 1, pool_size: 20, core_pool_size: 20, max_pool_size: 50, completed_tasks: 1, caller_runs: 0, saturated: false },
  memory: { used_mb: 812, free_mb: 236, total_mb: 1048, max_mb: 2048, used_pct: 39 },
  scan: { last_run: '2026-10-23T08:00:00', duration_ms: 42150, total: 10, warnings: 0, errors: 0, source: 'cluster', runs: {} },
  scan_alarm: false, db_ms: 14,
  smtp: { total: 1, attempted: 1, sent: 1, rate: 100, alarm: false, periods: { '7d': { total: 1, attempted: 1, sent: 1, rate: 100, alarm: false } } },
  heartbeat: { last_heartbeat: '2026-10-23T08:59:00', minutes_since: 1, alarm: false, recent: [] },
  network: { alarm: false }, domain_expiry: { source: 'RDAP', alarm: false },
  cleanup: { hold_active: false, last_run: '2026-10-23T01:00:00', hours_since: 8, total_deleted: 0, failed_count: 0, never_run: false, alarm: false },
}
const REC = { mode: 'verify', active: false, writer: A, writer_pod: 'sitemonitor-6b7c9d-aaaaa', generation: null, pending: 0, dropped: 0,
  flushes: 0, write_failures: 0, last_flush_at: null, last_failure_at: null, last_failure: null, table_missing: false, failing: false,
  inherited: null, last_verify: null }
const VERIFY = { observed_by: B, observed_by_pod: 'sitemonitor-6b7c9d-bbbbb', observed_at: '2026-10-23T08:58:00', age_seconds: 120,
  stale_after_seconds: 900, stale: false, generation: 7, status: 'ok', reason: null, pass_fail_mismatches: 0, first_mismatches: [],
  kinds: { recovery: { compared: 40, mismatches: 0, skipped: 0, measured: false }, sibling: { compared: 12, mismatches: 0, skipped: 2, measured: false },
    window_seed: { compared: 9, mismatches: 1, skipped: 0, max_delta: 0.12, measured: true } } }
const SIDE = { threads: 3, accepting: true, leader_yield_enabled: true, leader_hint: false, other_live_pod: true, running: 1, queued: 0,
  failed: 1, deferred_total: 4, yielded_total: 2, skipped_total: 0, jobs: [
    { name: 'cert-hourly', state: 'running', started_at: '2026-10-23T08:59:30', runs: 3 },
    { name: 'tls-profile', state: 'idle', last_outcome: 'failed', last_finished_at: '2026-10-23T08:40:00', runs: 1 } ] }

const overall = () => document.querySelector('[data-slot="health-overall"]')
const card = () => document.querySelector('[data-card="cluster"]')
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

describe('SystemHealth — Küme kartı, çok pod R3-1 (kalıcı değerlendirme durumu + yan işler)', () => {
  it('doğrulama KÜME etiketli (gözlemleyen pod, kuşak, türler, ölçüm notu); kaydedici + yan işler "Bu pod" bölümünde', async () => {
    await open({ ...BASE, eval_state: { this_pod: REC, verify: VERIFY }, side_jobs: SIDE })
    const sec = card().querySelector('[data-slot="cluster-eval-verify"]')
    expect(sec).not.toBeNull()
    expect(sec).toHaveAttribute('data-level', 'ok')
    expect(sec.querySelector('[data-slot="pod-chip"]')).toHaveAttribute('data-scope', 'cluster')
    expect(sec.querySelector('[data-slot="es-verify-status"] [data-tone]')).toHaveAttribute('data-tone', 'success')
    expect(sec.querySelector('[data-slot="es-verified-by"]').textContent).toContain('sitemonitor-6b7c9d-bbbbb')
    expect(sec.querySelector('[data-slot="es-generation"]').textContent).toBe('7')
    expect([...sec.querySelectorAll('[data-slot="es-kind"]')].map((k) => k.getAttribute('data-kind'))).toEqual(['recovery', 'sibling', 'window_seed'])
    expect(sec.querySelector('[data-slot="es-decision-note"]')).not.toBeNull()
    // Bu pod bölümü
    const podPart = card().querySelector('[data-slot="cluster-this-pod"]')
    expect(podPart.querySelector('[data-slot="pod-chip"]')).toHaveAttribute('data-scope', 'pod')
    const rec = podPart.querySelector('[data-slot="cluster-eval-recorder"]')
    expect(rec).toHaveAttribute('data-level', 'ok')
    expect(rec.textContent).toMatch(/not writing|yazmıyor/)
    const sj = podPart.querySelector('[data-slot="cluster-side-jobs"]')
    expect(sj).toHaveAttribute('data-known', 'true')
    expect([...sj.querySelectorAll('[data-slot="side-job"]')].map((j) => j.getAttribute('data-state'))).toEqual(['running'])
    expect(sj.textContent).toContain('cert-hourly')
    expect(sj.querySelector('[data-slot="side-job-failed"]').textContent).toContain('tls-profile')
    expect(sj.querySelector('[data-slot="side-jobs-yield"]').textContent).toMatch(/another live pod seen|başka canlı pod var/)
    // Uyuşmazlık yok + kira sağlıklı → kart uyarı değil
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'success')
  })

  it('taze uyuşmazlık → bölüm ve kart uyarı; uyuşmaz tür vurgulu, ilk anahtarlar özetle', async () => {
    const mm = { ...VERIFY, status: 'mismatch', pass_fail_mismatches: 2, first_mismatches: ['sibling:0123456789ab'],
      kinds: { ...VERIFY.kinds, sibling: { compared: 12, mismatches: 2, skipped: 0, measured: false } } }
    await open({ ...BASE, eval_state: { this_pod: REC, verify: mm }, side_jobs: SIDE })
    const sec = card().querySelector('[data-slot="cluster-eval-verify"]')
    expect(sec).toHaveAttribute('data-level', 'mismatch')
    expect(sec.querySelector('[data-slot="es-verify-status"] [data-tone]')).toHaveAttribute('data-tone', 'warning')
    expect(sec.querySelector('[data-kind="sibling"]')).toHaveAttribute('data-warn', 'true')
    expect(sec.querySelector('[data-kind="window_seed"]')).not.toHaveAttribute('data-warn')
    expect(sec.querySelector('[data-slot="es-first-keys"]').textContent).toBe('sibling:0123456789ab')
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'warning')
  })

  it('bilinmeyen tur / okunamayan gözlem / eski gözlem / hiç gözlem → gri, yeşil DEĞİL, sayı yok', async () => {
    const cases = [
      [{ this_pod: REC, verify: { ...VERIFY, status: 'unknown', reason: 'flush_lag', pass_fail_mismatches: null, kinds: {} } }, 'unknown'],
      [{ this_pod: REC, verify: null, verify_error: 'BadSqlGrammarException' }, 'unknown'],
      [{ this_pod: REC, verify: { ...VERIFY, stale: true } }, 'stale'],
      [{ this_pod: REC, verify: null }, 'none'],
    ]
    for (const [es, level] of cases) {
      const r = await open({ ...BASE, eval_state: es })
      const sec = card().querySelector('[data-slot="cluster-eval-verify"]')
      expect(sec, level).toHaveAttribute('data-level', level)
      expect(sec.querySelector('[data-slot="es-verify-status"] [data-tone]')).toHaveAttribute('data-tone', 'muted')
      expect(sec.querySelector('[data-slot="es-verify-mismatches"]')).toBeNull()
      expect(sec.querySelector('[data-slot="es-verify-hint"]') || level === 'unknown').toBeTruthy()
      r.unmount()
    }
  })

  it('kaydedici şu an yazamıyor → uyarı satırı; durum bilinmiyor / yan iş okunamadı → "Bilinmiyor"', async () => {
    const first = await open({ ...BASE, eval_state: { this_pod: { ...REC, active: true, generation: 4, failing: true, write_failures: 3 }, verify: VERIFY },
      side_jobs: { error: 'IllegalStateException' } })
    const rec = card().querySelector('[data-slot="cluster-eval-recorder"]')
    expect(rec).toHaveAttribute('data-level', 'warn')
    expect(rec.querySelector('[data-slot="es-failing"]')).not.toBeNull()
    expect(card().querySelector('[data-slot="cluster-side-jobs"]')).toHaveAttribute('data-known', 'false')
    expect(card().querySelector('[data-slot="cluster-side-jobs"]').textContent).toMatch(/Unknown|Bilinmiyor/)

    first.unmount()
    await open({ ...BASE, eval_state: { this_pod: null, this_pod_error: 'IllegalStateException', verify: VERIFY } })
    expect(card().querySelector('[data-slot="cluster-eval-recorder"]')).toHaveAttribute('data-level', 'unknown')
    expect(card().querySelector('[data-slot="cluster-eval-recorder"]').textContent).toMatch(/Unknown|Bilinmiyor/)
  })

  // R3-1 inceleme M2 (FX-OBS): DB kesintisinde liderliği kaybeden pod artık yazmıyor — eski hatası "yeniden deneniyor" uyarısı ve
  // uyarı kart başlığı göstermez (yanıt iki pod'a düşünce kart uyarı ↔ normal arasında gidip geliyordu)
  it('eski lider (yazmıyor) + eski son hata → uyarı yok, kart başlığı normal', async () => {
    await open({ ...BASE, eval_state: { this_pod: { ...REC, active: false, failing: true, write_failures: 3,
      last_failure_at: '2026-10-23T08:30:00', last_flush_at: '2026-10-23T08:20:00', last_failure: 'CannotGetJdbcConnectionException' },
    verify: VERIFY }, side_jobs: SIDE })
    const rec = card().querySelector('[data-slot="cluster-eval-recorder"]')
    expect(rec).toHaveAttribute('data-level', 'ok')
    expect(rec.querySelector('[data-slot="es-failing"]')).toBeNull()
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'success')
  })

  // R3-1 entegrasyon: çitlenmiş lider → "yazmıyor, kendiliğinden devralır" uyarısı (yeniden deneniyor DEĞİL); kanca hataları satırı
  it('çitlenmiş lider uyarısı ve kanca hatası sayısı', async () => {
    await open({ ...BASE, eval_state: { this_pod: { ...REC, active: false, generation: null, fenced: true, hook_failures: 2 },
      verify: VERIFY }, side_jobs: SIDE })
    const rec = card().querySelector('[data-slot="cluster-eval-recorder"]')
    expect(rec).toHaveAttribute('data-level', 'warn')
    expect(rec.querySelector('[data-slot="es-fenced"]').textContent).toMatch(/another pod took the write generation|başka pod aldı/)
    expect(rec.querySelector('[data-slot="es-failing"]')).toBeNull()
    expect(rec.querySelector('[data-slot="es-hook-failures"]').textContent).toMatch(/2/)
  })

  // R3-1 inceleme L4 (FX-OBS): "başka canlı pod" satırı takipçide sürekli "bilinmiyor" demez
  it('yan işler başka canlı pod: takipçide "yalnız tarama lideri bakar", lider önbelleği boşken son görülen gözlem', async () => {
    const first = await open({ ...BASE, eval_state: { this_pod: REC, verify: VERIFY }, side_jobs: { ...SIDE, other_live_pod: null } })
    let line = card().querySelector('[data-slot="side-jobs-yield"]')
    expect(line).toHaveAttribute('data-other-pod', 'leaderOnly')
    expect(line.textContent).toMatch(/only the sweep leader checks|yalnız tarama lideri bakar/)
    expect(line.textContent).not.toMatch(/unknown|bilinmiyor/)
    first.unmount()

    const second = await open({ ...BASE, eval_state: { this_pod: REC, verify: VERIFY }, side_jobs: { ...SIDE, leader_hint: true,
      other_live_pod: null, other_live_pod_seen: { value: true, at: '2026-10-23T08:57:00' } } })
    line = card().querySelector('[data-slot="side-jobs-yield"]')
    expect(line).toHaveAttribute('data-other-pod', 'seenYes')
    expect(line.textContent).toMatch(/another live pod at the last check|son bakışta başka canlı pod vardı/)
    second.unmount()

    await open({ ...BASE, eval_state: { this_pod: REC, verify: VERIFY }, side_jobs: { ...SIDE, leader_hint: true, other_live_pod: null } })
    line = card().querySelector('[data-slot="side-jobs-yield"]')
    expect(line).toHaveAttribute('data-other-pod', 'notChecked')
    expect(line.textContent).toMatch(/not checked yet|henüz bakılmadı/)
  })

  it('eski sunucu (eval_state / side_jobs yok) → satırlar gizli, kart her zamanki gibi', async () => {
    await open(BASE)
    expect(card().querySelector('[data-slot="cluster-eval-verify"]')).toBeNull()
    expect(card().querySelector('[data-slot="cluster-eval-recorder"]')).toBeNull()
    expect(card().querySelector('[data-slot="cluster-side-jobs"]')).toBeNull()
    expect(card().querySelector('[data-slot="cluster-is-leader"]')).toHaveAttribute('data-value', 'false')
  })

  it('küme kaydı yok (tek pod dalı) → doğrulama ve bu pod satırları yine görünür', async () => {
    const { sweep_leader, ...sched } = BASE.scheduler
    expect(sweep_leader).toBeTruthy()
    await open({ ...BASE, scheduler: sched, eval_state: { this_pod: { ...REC, active: true, generation: 1 }, verify: VERIFY },
      side_jobs: { ...SIDE, threads: 0, jobs: [], running: 0, failed: 0 } })
    expect(card().querySelector('[data-slot="cluster-single"]')).not.toBeNull()
    expect(card().querySelector('[data-slot="cluster-eval-verify"]')).toHaveAttribute('data-level', 'ok')
    expect(card().querySelector('[data-slot="cluster-eval-recorder"]').textContent).toMatch(/writing \(generation 1\)|yazıyor \(kuşak 1\)/)
    expect(card().querySelector('[data-slot="side-jobs-summary"]').textContent).toMatch(/idle|boşta/)
  })
})
