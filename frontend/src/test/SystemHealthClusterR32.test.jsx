import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor } from './test-utils.jsx'
import SystemHealth from '../components/admin/SystemHealth.jsx'
import { EvalLoadSection } from '../components/admin/health/ClusterRuntimeParts.jsx'
import { evalLoadModel, EVAL_LOAD_REASONS, EVAL_LOAD_OPERATIONAL_REASONS } from '../components/admin/health/clusterRuntimeModel.js'
import { useT } from '../i18n/index.jsx'

/**
 * Sistem Sağlığı → Zamanlayıcı → Küme kartı, çok pod Sürüm 3-2 satırları (2026-10-28, paket C; tasarım R3-2 §8.3):
 * "Devirde yüklenen durum" (KÜMENİN son devir yüklemesi, gözlem yoksa bu pod'un son yüklemesi) ve "Bu pod" bölümünde "Devir
 * hazırlığı". Yalnız "Tam" yeşil; işletim nedenli "yüklenmedi" kartı uyarıya çeker; bilinmeyen / okunamayan asla yeşil değil;
 * her neden çevrilir (ham kod basılmaz); eski sunucu (alan yok) → satırlar gizli.
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
  timestamp: '2026-10-28T09:00:00', served_by: A, served_by_pod: 'sitemonitor-6b7c9d-aaaaa',
  scheduler: { running: false, last_run: '2026-10-28T08:00:00', instance_id: A, active_domains: 10, status_source: 'cluster',
    sweep_leader: { owner: A, owner_pod: 'sitemonitor-6b7c9d-aaaaa', until: '2026-10-28T09:03:00', expired: false, held_by_me: true, renewer: true },
    held_locks: [], round_claim_failures: 0, skipped_rounds: [] },
  build: { version: '20.169.0', commit: 'a1b2c3d', environment: 'prod', started_at: '2026-10-28T01:00:00', uptime_seconds: 3600 },
  lock: { held: false },
  pool: { active: 3, idle: 7, total: 10, waiting: 0, max_size: 25 },
  executor_pool: { queue_size: 0, queue_capacity: 1000, active_count: 1, pool_size: 20, core_pool_size: 20, max_pool_size: 50, completed_tasks: 1, caller_runs: 0, saturated: false },
  memory: { used_mb: 812, free_mb: 236, total_mb: 1048, max_mb: 2048, used_pct: 39 },
  scan: { last_run: '2026-10-28T08:00:00', duration_ms: 42150, total: 10, warnings: 0, errors: 0, source: 'cluster', runs: {} },
  scan_alarm: false, db_ms: 14,
  smtp: { total: 1, attempted: 1, sent: 1, rate: 100, alarm: false, periods: { '7d': { total: 1, attempted: 1, sent: 1, rate: 100, alarm: false } } },
  heartbeat: { last_heartbeat: '2026-10-28T08:59:00', minutes_since: 1, alarm: false, recent: [] },
  network: { alarm: false }, domain_expiry: { source: 'RDAP', alarm: false },
  cleanup: { hold_active: false, last_run: '2026-10-28T01:00:00', hours_since: 8, total_deleted: 0, failed_count: 0, never_run: false, alarm: false },
}
const REC = { mode: 'verify', active: true, writer: A, writer_pod: 'sitemonitor-6b7c9d-aaaaa', generation: 12, pending: 0, dropped: 0,
  flushes: 40, write_failures: 0, last_flush_at: '2026-10-28T08:59:59', last_failure_at: null, last_failure: null, table_missing: false,
  failing: false, fenced: false, hook_failures: 0, inherited: null, last_verify: null, handover_ready: true, last_load: null, last_handover: null }
const VERIFY = { observed_by: A, observed_by_pod: 'sitemonitor-6b7c9d-aaaaa', observed_at: '2026-10-28T08:58:00', age_seconds: 120,
  stale_after_seconds: 900, stale: false, generation: 12, status: 'ok', reason: null, pass_fail_mismatches: 0, first_mismatches: [],
  kinds: { recovery: { compared: 40, mismatches: 0, skipped: 0, measured: false } } }
const LOAD = { at_ms: 1, at: '2026-10-28T08:50:00', pod: A, pod_name: 'sitemonitor-6b7c9d-aaaaa', outcome: 'full', reason: null,
  attempted_tier: 'full', from_generation: 11, from_pod: B, from_pod_name: 'sitemonitor-6b7c9d-bbbbb', handover_age_ms: 9000,
  durable_age_ms: 9000, recovery_counters: 3, siblings: 2, suppressions: 1, round_seen: 4, window_types: 2, window_domains: 40,
  read_ms: 85, max_wait_ms: 140, waiters: 2, observed_by: A, observed_by_pod: 'sitemonitor-6b7c9d-aaaaa', observed_at: '2026-10-28T08:50:01',
  age_seconds: 600 }
const loadNone = (reason) => ({ ...LOAD, outcome: 'none', reason, attempted_tier: null, recovery_counters: 0, siblings: 0,
  suppressions: 0, round_seen: 0, window_types: 0, window_domains: 0, waiters: 0, max_wait_ms: 0 })
const es = (extra = {}, pod = {}) => ({ this_pod: { ...REC, ...pod }, verify: VERIFY, read_enabled: true, load: LOAD, ...extra })

const overall = () => document.querySelector('[data-slot="health-overall"]')
const card = () => document.querySelector('[data-card="cluster"]')
const loadSec = () => card().querySelector('[data-slot="cluster-eval-load"]')
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

describe('SystemHealth — Küme kartı, çok pod R3-2 (devirde yüklenen durum + devir hazırlığı)', () => {
  it('TAM yükleme: Küme etiketli bölüm, yeşil rozet, önceki lider (kuşak + pod), yükleyen pod, sayılar, beklemeler; bu pod hazır', async () => {
    await open({ ...BASE, eval_state: es() })
    const sec = loadSec()
    expect(sec).not.toBeNull()
    expect(sec).toHaveAttribute('data-level', 'full')
    expect(sec).toHaveAttribute('data-scope', 'cluster')
    expect(sec.querySelector('[data-slot="pod-chip"]')).toHaveAttribute('data-scope', 'cluster')
    expect(sec.querySelector('[data-slot="es-load-outcome"] [data-tone]')).toHaveAttribute('data-tone', 'success')
    expect(sec.querySelector('[data-slot="es-load-outcome"]').textContent).toMatch(/Full \(clean handover\)|Tam \(temiz devir\)/)
    expect(sec.querySelector('[data-slot="es-load-from"]').textContent).toMatch(/11/)
    expect(sec.querySelector('[data-slot="es-load-from"]').textContent).toContain('sitemonitor-6b7c9d-bbbbb')
    expect(sec.querySelector('[data-slot="es-load-by"]').textContent).toContain('sitemonitor-6b7c9d-aaaaa')
    expect(sec.querySelector('[data-slot="es-load-counts"]').textContent).toMatch(/3 recovery counters|3 kurtarma sayacı/)
    expect(sec.querySelector('[data-slot="es-load-counts"]').textContent).toMatch(/40 window domains|40 pencere alan adı/)
    expect(sec.querySelector('[data-slot="es-load-waited"]').textContent).toMatch(/140/)
    // Bu pod: devir hazırlığı
    const ho = card().querySelector('[data-slot="cluster-this-pod"] [data-slot="cluster-handover"]')
    expect(ho).toHaveAttribute('data-ready', 'true')
    expect(ho.querySelector('[data-tone]')).toHaveAttribute('data-tone', 'success')
    // sıra: doğrulama bölümü → yükleme bölümü → bu pod bölümü
    const order = [...card().querySelectorAll('[data-slot="cluster-eval-verify"], [data-slot="cluster-eval-load"], [data-slot="cluster-this-pod"]')]
      .map((n) => n.getAttribute('data-slot'))
    expect(order).toEqual(['cluster-eval-verify', 'cluster-eval-load', 'cluster-this-pod'])
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'success')
  })

  it('yalnız gözlemler: bilgi tonu, neden çevrilir (ham kod yok)', async () => {
    await open({ ...BASE, eval_state: es({ load: { ...LOAD, outcome: 'observations', reason: 'no_handover', recovery_counters: 0 } }) })
    const sec = loadSec()
    expect(sec).toHaveAttribute('data-level', 'observations')
    expect(sec.querySelector('[data-slot="es-load-outcome"] [data-tone]')).toHaveAttribute('data-tone', 'info')
    expect(sec.querySelector('[data-slot="es-load-reason"]').textContent).toMatch(/No clean handover|Temiz devir yok/)
    expect(sec.textContent).not.toContain('no_handover')
  })

  it('beklenen neden (önceki lider işaretsiz) → gri, kart uyarı DEĞİL; işletim nedeni (zaman aşımı) → uyarı, kart uyarı', async () => {
    const first = await open({ ...BASE, eval_state: es({ load: loadNone('previous_unmarked') }) })
    expect(loadSec()).toHaveAttribute('data-level', 'none')
    expect(loadSec().querySelector('[data-slot="es-load-outcome"] [data-tone]')).toHaveAttribute('data-tone', 'muted')
    expect(loadSec().querySelector('[data-slot="es-load-counts"]')).toBeNull()
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'success')
    first.unmount()

    await open({ ...BASE, eval_state: es({ load: loadNone('timeout') }) })
    expect(loadSec()).toHaveAttribute('data-level', 'warn')
    expect(loadSec().querySelector('[data-slot="es-load-outcome"] [data-tone]')).toHaveAttribute('data-tone', 'warning')
    expect(loadSec().querySelector('[data-slot="es-load-reason"]').textContent).toMatch(/20 seconds|20 saniyede/)
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'warning')
  })

  it('ayar kapalı (J-1 varsayılanı) → "Kapalı" gri + nasıl açılır ipucu; bu pod "Hazır değil: Okuma ayarı kapalı"; kart uyarı değil', async () => {
    await open({ ...BASE, eval_state: es({ read_enabled: false, load: null }, { handover_ready: false,
      last_load: loadNone('setting_off') }) })
    const sec = loadSec()
    expect(sec).toHaveAttribute('data-level', 'off')
    expect(sec.querySelector('[data-slot="es-load-outcome"]').textContent).toMatch(/Off|Kapalı/)
    expect(sec.querySelector('[data-slot="es-load-outcome"] [data-tone]')).toHaveAttribute('data-tone', 'muted')
    expect(sec.querySelector('[data-slot="es-load-hint"]')).not.toBeNull()
    const ho = card().querySelector('[data-slot="cluster-handover"]')
    expect(ho).toHaveAttribute('data-ready', 'false')
    expect(ho).toHaveAttribute('data-reason', 'setting_off')
    expect(ho.textContent).toMatch(/Not ready: The read setting is off|Hazır değil: Okuma ayarı kapalı/)
    expect(card().querySelector('[data-kind="leader"]')).toHaveAttribute('data-tone', 'success')
  })

  it('gözlem okunamadı → "Bilinmiyor" gri + okunamadı ipucu (asla yeşil / "yüklenmedi")', async () => {
    await open({ ...BASE, eval_state: es({ load: null, load_error: 'BadSqlGrammarException' }, { last_load: LOAD }) })
    const sec = loadSec()
    expect(sec).toHaveAttribute('data-level', 'unknown')
    expect(sec.querySelector('[data-slot="es-load-outcome"] [data-tone]')).toHaveAttribute('data-tone', 'muted')
    expect(sec.querySelector('[data-slot="es-load-outcome"]').textContent).toMatch(/Unknown|Bilinmiyor/)
    expect(sec.querySelector('[data-slot="es-load-hint"]').textContent).toMatch(/could not be read|okunamadı/)
  })

  it('küme gözlemi yok → bu pod\'un son yüklemesi, "Bu pod: <ad>" etiketiyle; ikisi de yok → "Henüz yok"', async () => {
    const first = await open({ ...BASE, eval_state: es({ load: null }, { last_load: loadNone('no_previous_generation') }) })
    expect(loadSec()).toHaveAttribute('data-scope', 'pod')
    expect(loadSec().querySelector('[data-slot="pod-chip"]')).toHaveAttribute('data-scope', 'pod')
    expect(loadSec().querySelector('[data-slot="pod-chip"]').textContent).toContain('sitemonitor-6b7c9d-aaaaa')
    expect(loadSec().querySelector('[data-slot="es-load-reason"]').textContent).toMatch(/first leader|ilk lider/)
    first.unmount()

    await open({ ...BASE, eval_state: es({ load: null }) })
    expect(loadSec()).toHaveAttribute('data-level', 'empty')
    expect(loadSec().querySelector('[data-slot="es-load-hint"]')).not.toBeNull()
  })

  it('takipçi: "Hazır değil: lider değil"; lider eşitleniyor / çit dışı; son devir boşaltması çevrilir', async () => {
    const follower = { ...BASE.scheduler, sweep_leader: { ...BASE.scheduler.sweep_leader, owner: B, owner_pod: 'sitemonitor-6b7c9d-bbbbb', held_by_me: false } }
    const first = await open({ ...BASE, scheduler: follower, eval_state: es({}, { handover_ready: false, active: false, generation: null }) })
    let ho = card().querySelector('[data-slot="cluster-handover"]')
    expect(ho).toHaveAttribute('data-reason', 'not_leader')
    expect(ho.textContent).toMatch(/not the sweep leader|tarama lideri değil/)
    first.unmount()

    const second = await open({ ...BASE, eval_state: es({}, { handover_ready: false }) })
    ho = card().querySelector('[data-slot="cluster-handover"]')
    expect(ho).toHaveAttribute('data-reason', 'incomplete')
    expect(ho.textContent).toMatch(/resyncing|eşitleniyor/)
    second.unmount()

    await open({ ...BASE, eval_state: es({}, { handover_ready: false, last_handover: 'budget_exceeded' }) })
    ho = card().querySelector('[data-slot="cluster-handover"]')
    expect(ho.querySelector('[data-slot="handover-last"]')).toHaveAttribute('data-result', 'budget_exceeded')
    expect(ho.querySelector('[data-slot="handover-last"]').textContent).toMatch(/1-second budget|1 saniyelik/)
    expect(ho.textContent).not.toContain('budget_exceeded')
  })

  it('eski sunucu (3-2 alanları yok) → yükleme bölümü ve hazırlık satırı gizli, R3-1 satırları yine görünür', async () => {
    const { handover_ready, last_load, last_handover, ...old } = REC
    expect([handover_ready, last_load, last_handover]).toEqual([true, null, null])
    await open({ ...BASE, eval_state: { this_pod: old, verify: VERIFY } })
    expect(card().querySelector('[data-slot="cluster-eval-verify"]')).not.toBeNull()
    expect(loadSec()).toBeNull()
    expect(card().querySelector('[data-slot="cluster-handover"]')).toBeNull()
    expect(card().querySelector('[data-slot="cluster-eval-recorder"]')).not.toBeNull()
  })
})

/** Doğrudan bileşen: her neden çevrilir (ham kod basılmaz) ve tonu kurala uyar. */
function Probe({ health }) {
  const t = useT()
  return <EvalLoadSection t={t} load={evalLoadModel(health)} />
}

describe('EvalLoadSection — her neden çevrilir, ton kuralı', () => {
  it('22 nedenin her biri: metin sözlükten (ham kod yok); işletim nedenleri uyarı, diğerleri yeşil değil', () => {
    for (const reason of EVAL_LOAD_REASONS) {
      const outcome = ['no_handover', 'handover_too_old', 'intermediate_leader'].includes(reason) ? 'observations' : 'none'
      const health = { eval_state: { this_pod: null, read_enabled: true, load: { ...LOAD, outcome, reason } } }
      const { container, unmount } = render(<Probe health={health} />)
      const reasonEl = container.querySelector('[data-slot="es-load-reason"]')
      expect(reasonEl, reason).not.toBeNull()
      expect(reasonEl.textContent, reason).not.toContain(reason)
      expect(reasonEl.textContent.length, reason).toBeGreaterThan(8)
      const tone = container.querySelector('[data-slot="es-load-outcome"] [data-tone]').getAttribute('data-tone')
      if (outcome === 'none' && EVAL_LOAD_OPERATIONAL_REASONS.includes(reason)) expect(tone, reason).toBe('warning')
      else expect(tone, reason).not.toBe('success')
      unmount()
    }
  })
})
