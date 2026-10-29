// Sistem Sağlığı kümeyi doğru gösterir (2026-10-23, çok pod R2b, paket C1). Gerçek tarayıcıda 390 (dokunmatik) / 768
// (dokunmatik) / 1280: Zamanlayıcı bölümünde Küme kartı (tarama lideri = küme, yanıtlayan pod ayrı), yürütücü / havuz
// "Bu pod: <ad>", zamanlayıcı / kilit / tarama "Küme"; Heartbeat bölümünde Pod'lar kartı (sessiz pod); Entegrasyonlar'da RDAP
// kartının gözlemleyen pod'u; Uygulama bölümünde JVM grafiklerinin pod'u + Metrikler bağlantısı. Gerçek uzunlukta pod adları
// SARAR (kırpılmaz), yatay taşma yok, dokunma hedefleri ≥ 40 px. API mock'lu. Çok pod Sürüm 3-1 (paket B): Küme kartında kalıcı
// değerlendirme durumunun küme doğrulaması (gözlemleyen pod, tür başına sayılar) + bu pod'un kaydedicisi ve yan işleri; bilinmeyen gri.
// Çok pod Sürüm 3-2 (paket C): devirde yüklenen durum (her ton) + bu pod'un devir hazırlığı.
// Çok pod Sürüm 3-3 (paket D1): "Dağıtık kontroller" bölümü (kapalı / açık / kuyruk uyarısı / okunamadı / yetenek uyuşmazlığı),
// işçiler kap ≥ 760 px tabloda, altında kartta; bilinmeyen gri, açık anahtar yeşil değil.
// Çok pod Sürüm 3-4 (paket C1): "Parçalı sertifika taraması" bölümü (kapalı / açık tur / aykırı pod / okunamadı / tur yok), pod
// payı kap ≥ 760 px tabloda, altında kartta; tarama kartında tur kipi + açık parçalı tur; bilinmeyen gri, açık anahtar yeşil değil.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const POD_A = 'site-monitor-production-deployment-7f9c8d6b5a-xk2lp'
const POD_B = 'site-monitor-production-deployment-7f9c8d6b5a-q8m2zr'
const A = `${POD_A}-1a2b3c4d`
const B = `${POD_B}-5e6f7a8b`
const fmt = (ms) => new Date(ms).toISOString().slice(0, 19)
const NOW = Date.now()

const HEALTH = {
  served_by: A, served_by_pod: POD_A,
  scheduler: { running: false, running_here: false, last_run: fmt(NOW - 40 * 60_000), last_run_by_pod: POD_B, last_run_id: 'r-9f8e7d6c5b4a',
    next_run: fmt(NOW + 20 * 60_000), instance_id: A, active_domains: 412, status_source: 'cluster',
    sweep_leader: { owner: B, owner_pod: POD_B, until: fmt(NOW + 120_000), expired: false, held_by_me: false, renewer: true, last_sweep_tick: null },
    held_locks: [{ name: 'watchdog', state: 'HELD', until: fmt(NOW + 90_000), acquired_at: fmt(NOW - 30_000) },
      { name: 'quiet-digest', state: 'HELD', until: fmt(NOW + 60_000), acquired_at: fmt(NOW - 10_000) }],
    round_claim_failures: 3, last_round_claim_failure: fmt(NOW - 3_600_000),
    skipped_rounds: [{ round: 'weekly-report-reminder', at: fmt(NOW - 7_200_000) }] },
  cache_bus: { enabled: true, running: true, table_missing: false, applied_generation: 1842, last_poll_ok_at: fmt(NOW - 2_000), poll_failures: 0 },
  build: { version: '20.167.0', commit: 'a1b2c3d', environment: 'prod', image_version: '20.167.0', helm_revision: 88, started_at: fmt(NOW - 3 * 86_400_000), uptime_seconds: 3 * 86_400 },
  lock: { held: false },
  pool: { active: 3, idle: 7, total: 10, waiting: 0, max_size: 25 },
  executor_pool: { queue_size: 12, queue_capacity: 5000, active_count: 4, pool_size: 20, core_pool_size: 20, max_pool_size: 50, completed_tasks: 84213, caller_runs: 0, saturated: false, jvm_start_time: fmt(NOW - 3 * 86_400_000) },
  memory: { used_mb: 812, free_mb: 236, total_mb: 1048, max_mb: 2048, used_pct: 39 },
  scan: { last_run: fmt(NOW - 40 * 60_000), last_run_kind: 'hourly', last_run_by_pod: POD_B, duration_ms: 42_150, total: 412, warnings: 3, errors: 1,
    last_failure: null, source: 'cluster',
    runs: {
      hourly: { kind: 'hourly', last_run: fmt(NOW - 40 * 60_000), total: 412, by_pod: POD_B, finished_at: fmt(NOW - 39 * 60_000) },
      full: { kind: 'full', last_run: fmt(NOW - 26 * 3_600_000), total: 412, by_pod: POD_A, finished_at: fmt(NOW - 26 * 3_600_000) },
      stale: { kind: 'stale', last_run: fmt(NOW - 30 * 60_000), total: 2, by_pod: POD_A, failure: 'IllegalStateException: db yok', failure_at: fmt(NOW - 25 * 60_000), finished_at: fmt(NOW - 25 * 60_000) },
    } },
  scan_alarm: false,
  db_ms: 14,
  smtp: { total: 310, attempted: 310, sent: 310, rate: 100, alarm: false, periods: { '7d': { total: 310, attempted: 310, sent: 310, rate: 100, alarm: false } } },
  heartbeat: { last_heartbeat: fmt(NOW - 30_000), minutes_since: 0, alarm: false, recent: [fmt(NOW - 30_000), fmt(NOW - 90_000)],
    served_by: A, served_by_pod: POD_A, pods_total: 3, pods_live: 2, pods_silent: 1, pods: [
      { id: A, pod: POD_A, name: POD_A, state: 'live', first_seen: fmt(NOW - 3 * 86_400_000), last_seen: fmt(NOW - 30_000), minutes_since: 0 },
      { id: B, pod: POD_B, name: POD_B, state: 'live', first_seen: fmt(NOW - 86_400_000), last_seen: fmt(NOW - 50_000), minutes_since: 0 },
      { id: `${POD_A}-99999999`, pod: POD_A, name: `${POD_A} · 99999999`, state: 'silent', first_seen: fmt(NOW - 20 * 3_600_000), last_seen: fmt(NOW - 3 * 3_600_000), minutes_since: 180 },
    ] },
  network: { alarm: false, last_error_rate: 0.01, last_network_errors: 1, last_total: 412, threshold: 0.5, min_errors: 3 },
  domain_expiry: { source: 'RDAP', alarm: false, last_success: fmt(NOW - 3_600_000), scope: 'cluster', observed_by: B, observed_by_pod: POD_B, observed_at: fmt(NOW - 3_600_000) },
  weekly_availability: { enabled: true, running: false, next_run: fmt(NOW + 86_400_000), last_run_at: fmt(NOW - 86_400_000), last_run_sent: 7, last_run_teams: 8, last_run_failed: 0, last_run_no_recipient: 1 },
  cleanup: { hold_active: false, last_run: fmt(NOW - 4 * 3_600_000), hours_since: 4, total_deleted: 18230, failed_count: 0, duration_ms: 42100, never_run: false, alarm: false },
  // Çok pod R3-1: kalıcı değerlendirme durumu (lider B doğruladı, A yazmıyor) + A'nın yan işleri
  eval_state: {
    this_pod: { mode: 'verify', active: false, writer: A, writer_pod: POD_A, generation: null, pending: 0, dropped: 0, flushes: 0,
      write_failures: 0, last_flush_at: null, last_failure_at: null, last_failure: null, table_missing: false, failing: false,
      inherited: { at: fmt(NOW - 86_400_000), previous_generation: 11, recovery_counters: 3, down_siblings: 1, active_suppressions: 0, abandoned_chains: 2 },
      last_verify: null },
    verify: { observed_by: B, observed_by_pod: POD_B, observed_at: fmt(NOW - 120_000), age_seconds: 120, stale_after_seconds: 900, stale: false,
      at: fmt(NOW - 120_000), pod: B, generation: 12, status: 'mismatch', reason: null, pass_fail_mismatches: 1,
      kinds: {
        recovery: { compared: 412, mismatches: 0, skipped: 3, max_delta: null, measured: false },
        sibling: { compared: 380, mismatches: 1, skipped: 0, max_delta: null, measured: false },
        suppression: { compared: 9, mismatches: 0, skipped: 0, max_delta: null, measured: false },
        round_seen: { compared: 9, mismatches: 0, skipped: 0, max_delta: null, measured: false },
        confirmation: { compared: 4, mismatches: 0, skipped: 1, max_delta: null, measured: false },
        descriptor: { compared: 4, mismatches: 0, skipped: 0, max_delta: null, measured: true },
        window_seed: { compared: 9, mismatches: 1, skipped: 0, max_delta: 0.0731, measured: true },
      },
      first_mismatches: ['sibling:0123456789ab'] },
  },
  side_jobs: { threads: 3, accepting: true, leader_yield_enabled: true, leader_hint: false, other_live_pod: true, running: 1, queued: 1, failed: 1,
    deferred_total: 14, yielded_total: 6, skipped_total: 2, jobs: [
      { name: 'cert-hourly', state: 'running', started_at: fmt(NOW - 45_000), last_finished_at: fmt(NOW - 3_600_000), last_outcome: 'ok', runs: 9, deferred_count: 8, yielded_count: 0, skipped_count: 1 },
      { name: 'executive-summary-catchup', state: 'queued', started_at: null, last_finished_at: null, last_outcome: null, runs: 0, deferred_count: 0, yielded_count: 6, skipped_count: 0 },
      { name: 'tls-profile', state: 'idle', started_at: null, last_finished_at: fmt(NOW - 3_600_000), last_outcome: 'failed', runs: 4, deferred_count: 0, yielded_count: 0, skipped_count: 1 },
    ] },
}
const TIMELINE = { days: 1, bucket_minutes: 10, counting: 'distinct_minutes', served_by: A, pods_more: 0,
  buckets: Array.from({ length: 144 }, (_, i) => ({ start: fmt(NOW - (144 - i) * 600_000), expected: 10, received: i === 120 ? 4 : 10 })),
  pods: HEALTH.heartbeat.pods.map((p) => ({ ...p, received: p.state === 'silent' ? 900 : 1430, expected: p.state === 'silent' ? 1020 : 1440 })) }

async function mock(page, health = HEALTH) {
  await mockApi(page)
  const json = (data, extra = {}) => (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data, ...extra }) })
  await page.route((u) => new URL(u).pathname === '/api/admin/system', json(health))
  await page.route((u) => new URL(u).pathname === '/api/admin/system/heartbeat-timeline', json(TIMELINE))
  await page.route((u) => new URL(u).pathname === '/api/admin/system/metrics', json(
    Array.from({ length: 12 }, (_, i) => ({ ts: fmt(NOW - (12 - i) * 300_000), cpu_process: 20 + i, heap_pct: 40 + i, threads: 80 + i })),
    { served_by: B, served_by_pod: POD_B }))
  await page.route((u) => new URL(u).pathname === '/api/admin/system/http-metrics', json({
    summary: { total_requests: 184210, error_rate_pct: 0.8, avg_ms: 142, max_ms: 6120 }, served_by: A, served_by_pod: POD_A,
    history: Array.from({ length: 12 }, (_, i) => ({ ts: fmt(NOW - (12 - i) * 300_000), count: 500 + i, avg_ms: 100 + i, errors: i % 5 === 0 ? 1 : 0 })) }))
}

function overflowIn(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const vw = document.documentElement.clientWidth
  const out = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.width > 1 && r.right > vw + 1) out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || ''}] ${Math.round(r.right)}`)
    if (out.length > 5) break
  }
  if (document.documentElement.scrollWidth > vw + 1) out.push(`page ${document.documentElement.scrollWidth}`)
  return out
}

/** Kırpılan metin: kendi kutusundan geniş içerik (pod adları sarmalı). */
function clippedIn(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const el of root.querySelectorAll('[data-slot="pod-chip"], .font-mono, dd, [data-slot="hb-pod"], [data-slot="scan-run"]')) {
    if (el.getBoundingClientRect().width === 0) continue
    if (el.scrollWidth > el.clientWidth + 1) out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || ''}] ${el.scrollWidth}>${el.clientWidth} "${(el.textContent || '').slice(0, 40)}"`)
    if (out.length > 5) break
  }
  return out
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, [role="combobox"], a[href]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0) continue
    if (r.height < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(r.height)}`)
  }
  return out
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Sistem Sağlığı küme — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('Zamanlayıcı: Küme kartı + kapsam etiketleri; adlar sarar, taşma yok, dokunma hedefleri', async ({ page }) => {
      test.setTimeout(90_000)
      await mock(page)
      await page.goto('/?tab=health&sec=sched')
      const card = page.locator('[data-card="cluster"]')
      await expect(card).toBeVisible({ timeout: 30_000 })
      await expect(card.locator('[data-slot="cluster-leader-pod"]')).toContainText(POD_B)
      await expect(card.locator('[data-slot="cluster-this-pod"] [data-slot="pod-chip"]')).toContainText(POD_A)
      await expect(card.locator('[data-slot="cluster-is-leader"]')).toHaveAttribute('data-value', 'false')
      await expect(card.locator('[data-slot="cluster-held-locks"] li')).toHaveCount(2)
      await expect(card.locator('[data-slot="cluster-skipped-rounds"]')).toContainText('weekly-report-reminder')
      await expect(page.locator('[data-card="executor"] [data-slot="pod-chip"]')).toHaveAttribute('data-scope', 'pod')
      await expect(page.locator('[data-card="scheduler"] [data-slot="pod-chip"]')).toHaveAttribute('data-scope', 'cluster')
      await expect(page.locator('[data-slot="scan-run"]')).toHaveCount(3)
      // Çok pod R3-1: kalıcı değerlendirme durumu (küme doğrulaması) + bu pod'un kaydedicisi ve yan işleri
      const es = card.locator('[data-slot="cluster-eval-verify"]')
      await expect(es).toHaveAttribute('data-level', 'mismatch')
      await expect(es.locator('[data-slot="pod-chip"]')).toHaveAttribute('data-scope', 'cluster')
      await expect(es.locator('[data-slot="es-verified-by"]')).toContainText(POD_B)
      await expect(es.locator('[data-slot="es-kind"]')).toHaveCount(7)
      await expect(es.locator('[data-slot="es-kind"][data-warn="true"]')).toHaveCount(1)
      await expect(card.locator('[data-slot="cluster-eval-recorder"]')).toHaveAttribute('data-level', 'ok')
      await expect(card.locator('[data-slot="es-inherited"]')).toBeVisible()
      await expect(card.locator('[data-slot="cluster-side-jobs"] [data-slot="side-job"]')).toHaveCount(2)
      await expect(card.locator('[data-slot="side-job-failed"]')).toContainText('tls-profile')
      // KPI ızgarası (inceleme H1): kapsam etiketleri + yanıtlayan pod başlığı; taşma / kırpma yok
      await expect(page.locator('[data-kpi="memory"] [data-slot="kpi-scope"]')).toHaveAttribute('data-scope', 'pod')
      await expect(page.locator('[data-kpi="scheduler"] [data-slot="kpi-scope"]')).toHaveAttribute('data-scope', 'cluster')
      await expect(page.locator('[data-slot="kpi-pods"]')).toContainText(POD_A)
      expect(await page.evaluate(overflowIn, '[data-slot="health-kpis"]'), 'KPI ızgarası taşması').toEqual([])
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowIn, '[data-section="sched"]'), 'yatay taşma').toEqual([])
      expect(await page.evaluate(clippedIn, '[data-section="sched"]'), 'kırpılan ad / metin').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-section="sched"]'), 'dokunma hedefi < 40 px').toEqual([])
    })

    test('Küme kartı R3-1: doğrulama okunamadı → gri (yeşil değil); kaydedici yazamıyor → uyarı; yan iş durumu bilinmiyor', async ({ page }) => {
      test.setTimeout(90_000)
      await mock(page, { ...HEALTH,
        eval_state: { this_pod: { ...HEALTH.eval_state.this_pod, active: true, generation: 13, failing: true, write_failures: 4, last_failure_at: fmt(NOW - 5_000) },
          verify: null, verify_error: 'CannotGetJdbcConnectionException' },
        side_jobs: { error: 'IllegalStateException' } })
      await page.goto('/?tab=health&sec=sched')
      const card = page.locator('[data-card="cluster"]')
      await expect(card).toBeVisible({ timeout: 30_000 })
      const es = card.locator('[data-slot="cluster-eval-verify"]')
      await expect(es).toHaveAttribute('data-level', 'unknown')
      await expect(es.locator('[data-slot="es-verify-status"] [data-tone]')).toHaveAttribute('data-tone', 'muted')
      await expect(es.locator('[data-slot="es-verify-hint"]')).toBeVisible()
      await expect(card.locator('[data-slot="cluster-eval-recorder"]')).toHaveAttribute('data-level', 'warn')
      await expect(card.locator('[data-slot="es-failing"]')).toBeVisible()
      await expect(card.locator('[data-slot="cluster-side-jobs"]')).toHaveAttribute('data-known', 'false')
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowIn, '[data-section="sched"]'), 'yatay taşma').toEqual([])
      expect(await page.evaluate(clippedIn, '[data-card="cluster"]'), 'kırpılan metin').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-card="cluster"]'), 'dokunma hedefi < 40 px').toEqual([])
    })

    // R3-1 inceleme düzeltmeleri (FX-OBS): M2 eski liderin eski yazma hatası uyarı değil; L4 takipçide "yalnız tarama lideri
    // bakar"; yeni bilinmiyor nedeni (leadership_changed) ham kod olarak basılmaz
    test('Küme kartı R3-1 (FX-OBS): eski liderin eski hatası uyarı değil; takipçide yalnız lider bakar; yeni neden çevrilir', async ({ page }) => {
      test.setTimeout(90_000)
      await mock(page, { ...HEALTH,
        eval_state: {
          this_pod: { ...HEALTH.eval_state.this_pod, active: false, failing: true, write_failures: 4, last_failure_at: fmt(NOW - 300_000),
            last_flush_at: fmt(NOW - 600_000), last_failure: 'CannotGetJdbcConnectionException' },
          verify: { ...HEALTH.eval_state.verify, status: 'unknown', reason: 'leadership_changed', pass_fail_mismatches: null, kinds: {},
            first_mismatches: [] } },
        side_jobs: { ...HEALTH.side_jobs, other_live_pod: null } })
      await page.goto('/?tab=health&sec=sched')
      const card = page.locator('[data-card="cluster"]')
      await expect(card).toBeVisible({ timeout: 30_000 })
      await expect(card.locator('[data-slot="cluster-eval-recorder"]')).toHaveAttribute('data-level', 'ok')
      await expect(card.locator('[data-slot="es-failing"]')).toHaveCount(0)
      const es = card.locator('[data-slot="cluster-eval-verify"]')
      await expect(es).toHaveAttribute('data-level', 'unknown')
      await expect(es.locator('[data-slot="es-verify-status"]')).toContainText(/leadership changed|liderliği değişti/)
      await expect(es.locator('[data-slot="es-verify-status"]')).not.toContainText('leadership_changed')
      const yieldLine = card.locator('[data-slot="side-jobs-yield"]')
      await expect(yieldLine).toHaveAttribute('data-other-pod', 'leaderOnly')
      await expect(yieldLine).toContainText(/only the sweep leader checks|yalnız tarama lideri bakar/)
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowIn, '[data-section="sched"]'), 'yatay taşma').toEqual([])
      expect(await page.evaluate(clippedIn, '[data-card="cluster"]'), 'kırpılan metin').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-card="cluster"]'), 'dokunma hedefi < 40 px').toEqual([])
    })

    // Çok pod Sürüm 3-2 (paket C): "Devirde yüklenen durum" (küme gözlemi, yoksa bu pod'un son yüklemesi) + "Bu pod" bölümünde
    // "Devir hazırlığı". Tonlar: yalnız Tam yeşil; gözlemler bilgi; beklenen neden / kapalı / bilinmiyor gri; işletim nedeni uyarı
    // (kartı uyarıya çeker). Gerçek uzunlukta pod adları sarar, taşma yok, dokunma hedefleri ≥ 40 px.
    test('Küme kartı R3-2: devirde yüklenen durum her tonda; devir hazırlığı; adlar sarar, taşma yok', async ({ page }) => {
      test.setTimeout(150_000)
      const VERIFY_OK = { ...HEALTH.eval_state.verify, status: 'ok', pass_fail_mismatches: 0, first_mismatches: [],
        kinds: { recovery: { compared: 412, mismatches: 0, skipped: 3, max_delta: null, measured: false } } }
      const LOAD = { at_ms: NOW - 600_000, at: fmt(NOW - 600_000), pod: B, pod_name: POD_B, outcome: 'full', reason: null,
        attempted_tier: 'full', from_generation: 11, from_pod: A, from_pod_name: POD_A, handover_age_ms: 9_000, durable_age_ms: 9_000,
        recovery_counters: 31, siblings: 12, suppressions: 1, round_seen: 9, window_types: 9, window_domains: 412, read_ms: 85,
        max_wait_ms: 140, waiters: 2, observed_by: B, observed_by_pod: POD_B, observed_at: fmt(NOW - 600_000), age_seconds: 600 }
      const none = (reason) => ({ ...LOAD, outcome: 'none', reason, attempted_tier: null, recovery_counters: 0, siblings: 0,
        suppressions: 0, round_seen: 0, window_types: 0, window_domains: 0, waiters: 0, max_wait_ms: 0 })
      const pod = { ...HEALTH.eval_state.this_pod, handover_ready: false, last_load: null, last_handover: 'written' }
      // atlanan tur yok, sunucu saati önbellek yolu yoklamasıyla aynı an → kartın uyarısı yalnız yükleme satırından gelebilir
      const withEs = (es, podExtra = {}) => ({ ...HEALTH, timestamp: fmt(NOW), scheduler: { ...HEALTH.scheduler, skipped_rounds: [] }, eval_state: { this_pod: { ...pod, ...podExtra }, verify: VERIFY_OK,
        read_enabled: true, load: LOAD, ...es } })
      const CASES = [
        { name: 'full', health: withEs({}), level: 'full', tone: 'success', scope: 'cluster', cardWarn: false },
        { name: 'observations', health: withEs({ load: { ...LOAD, outcome: 'observations', reason: 'intermediate_leader' } }),
          level: 'observations', tone: 'info', scope: 'cluster', cardWarn: false, reason: /recorded nothing|kaydetmeyen/ },
        { name: 'expected', health: withEs({ load: none('previous_unmarked') }), level: 'none', tone: 'muted', scope: 'cluster',
          cardWarn: false, reason: /complete-state marker|tam durum işareti/ },
        { name: 'operational', health: withEs({ load: none('evaluated_before_load') }), level: 'warn', tone: 'warning',
          scope: 'cluster', cardWarn: true, reason: /before the load finished|Yükleme bitmeden/ },
        { name: 'off', health: withEs({ read_enabled: false, load: null }), level: 'off', tone: 'muted', scope: 'cluster', cardWarn: false },
        { name: 'unreadable', health: withEs({ load: null, load_error: 'CannotGetJdbcConnectionException' }), level: 'unknown',
          tone: 'muted', scope: 'cluster', cardWarn: false },
        { name: 'podFallback', health: withEs({ load: null }, { last_load: none('no_previous_generation') }), level: 'none',
          tone: 'muted', scope: 'pod', cardWarn: false, reason: /first leader|ilk lider/ },
        // inceleme 2026-10-28 L-1: gözlem önceki dönemin lideri A'nın (şimdiki lider B yayımlamadı) → güncel değil, gri
        { name: 'stale', health: withEs({ load: { ...LOAD, pod: A, pod_name: POD_A, observed_by: A, observed_by_pod: POD_A } }),
          level: 'stale', tone: 'muted', scope: 'cluster', cardWarn: false, hint: /earlier term|önceki bir devre/ },
        // L-3: bu pod kapanıyor → "Pod kapanıyor" (lider değil yerine)
        { name: 'closing', health: withEs({}, { closing: true, marker_columns_missing: false }), level: 'full', tone: 'success',
          scope: 'cluster', cardWarn: false, hoReason: 'recorder_closing' },
      ]
      let current = CASES[0].health
      await mock(page)
      await page.route((u) => new URL(u).pathname === '/api/admin/system',
        (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: current }) }))
      for (const c of CASES) {
        current = c.health
        await page.goto('/?tab=health&sec=sched')
        const card = page.locator('[data-card="cluster"]')
        await expect(card, c.name).toBeVisible({ timeout: 30_000 })
        const sec = card.locator('[data-slot="cluster-eval-load"]')
        await expect(sec, c.name).toHaveAttribute('data-level', c.level)
        await expect(sec, c.name).toHaveAttribute('data-scope', c.scope)
        await expect(sec.locator('[data-slot="pod-chip"]'), c.name).toHaveAttribute('data-scope', c.scope)
        await expect(sec.locator('[data-slot="es-load-outcome"] [data-tone]'), c.name).toHaveAttribute('data-tone', c.tone)
        if (c.reason) await expect(sec.locator('[data-slot="es-load-reason"]'), c.name).toContainText(c.reason)
        if (c.hint) {
          await expect(sec.locator('[data-slot="es-load-hint"]'), c.name).toContainText(c.hint)
          await expect(sec.locator('[data-slot="es-load-hint"]'), c.name).toContainText(POD_A)
        }
        if (c.scope === 'pod') await expect(sec.locator('[data-slot="pod-chip"]'), c.name).toContainText(POD_A)
        if (c.name === 'full') {
          await expect(sec.locator('[data-slot="es-load-from"]')).toContainText(POD_A)
          await expect(sec.locator('[data-slot="es-load-from"]')).toContainText(/written 9 sec before|9 sn önce yazılmıştı/)
          await expect(sec.locator('[data-slot="es-load-by"]')).toContainText(POD_B)
          await expect(sec.locator('[data-slot="es-load-counts"]')).toContainText('412')
          await expect(sec.locator('[data-slot="es-load-waited"]')).toContainText('140')
        }
        // A bu pod (takipçi) → devir hazırlığı "lider değil"; ayar kapalıyken "okuma ayarı kapalı"; son devir boşaltması çevrili
        const ho = card.locator('[data-slot="cluster-this-pod"] [data-slot="cluster-handover"]')
        await expect(ho, c.name).toHaveAttribute('data-ready', 'false')
        await expect(ho, c.name).toHaveAttribute('data-reason', c.hoReason ?? (c.name === 'off' ? 'setting_off' : 'not_leader'))
        await expect(ho.locator('[data-slot="handover-last"]'), c.name).toHaveAttribute('data-result', 'written')
        await expect(card.locator('[data-kind="leader"]'), c.name).toHaveAttribute('data-tone', c.cardWarn ? 'warning' : 'success')
        await page.waitForTimeout(250)
        expect(await page.evaluate(overflowIn, '[data-section="sched"]'), `${c.name}: yatay taşma`).toEqual([])
        expect(await page.evaluate(clippedIn, '[data-card="cluster"]'), `${c.name}: kırpılan metin`).toEqual([])
        if (vp.touch) expect(await page.evaluate(smallTargets, '[data-card="cluster"]'), `${c.name}: dokunma hedefi < 40 px`).toEqual([])
      }
    })

    // Çok pod Sürüm 3-3 (paket D1): "Dağıtık kontroller" — her durumda adlar sarar, taşma yok, dokunma hedefleri ≥ 40 px
    test('Küme kartı R3-3: dağıtık kontroller her durumda; işçiler tablo / kart; adlar sarar, taşma yok', async ({ page }) => {
      test.setTimeout(150_000)
      const W = (over = {}) => ({ instance_id: B, pod: POD_B, self: false,
        caps: ['port', 'ping', 'http', 'keyword', 'page', 'pagespeed', 'dns', 'scripted'], missing_caps: [], slots: 16, busy: 4,
        completed: 18240, late: 3, claim_lag_p95_ms: 820, heartbeat_at: fmt(NOW - 5_000), heartbeat_age_ms: 5_000, version: '20.170.0',
        stopping: false, live: true, ...over })
      const CJ = (over = {}) => ({ enabled: true, types: ['port', 'ping', 'http', 'keyword'],
        mode: { port: 'distributed', ping: 'distributed', http: 'classic', keyword: 'distributed', page: 'classic', pagespeed: 'classic',
          dns: 'classic', scripted: 'classic' },
        mode_by: B, mode_by_pod: POD_B, mode_by_leader: false,
        queue: { at: fmt(NOW), pending: 41, claimed: 12, done: 3, oldest_pending_age_ms: 4_200, oldest_done_age_ms: 900, expired_claims: 0,
          by_type: { port: { pending: 20, claimed: 6, done: 1 }, ping: { pending: 11, claimed: 4, done: 2 }, http: { pending: 0, claimed: 0, done: 0 },
            keyword: { pending: 10, claimed: 2, done: 0 } } },
        workers_known: true, workers_total: 3,
        workers: [W(), W({ instance_id: A, pod: POD_A, self: true, busy: 9, completed: 9120, late: 0, claim_lag_p95_ms: 410 }),
          W({ instance_id: `${POD_B}-cafebabe`, pod: `${POD_B}-onceki-surec-uzun-ad`, live: false, heartbeat_age_ms: 120_000, completed: 40 })],
        this_pod: { pod: POD_A, leader: false, worker: { started: true, enabled: true, closing: false, slots: 16, busy: 9, claimed: 9200,
          completed: 9120, late: 0, timeouts: 1, failed: 2, released: 0, claim_errors: 0, complete_errors: 0, dropped_at_shutdown: 0,
          claim_lag_p95_ms: 410, poll_delay_ms: 1000 }, local_caps: ['port', 'ping', 'http', 'keyword', 'page', 'pagespeed', 'dns', 'scripted'],
        db_clock_stale: false, dispatch: null, drainer: null },
        ...over })
      const base = { ...HEALTH, timestamp: fmt(NOW), scheduler: { ...HEALTH.scheduler, skipped_rounds: [] },
        eval_state: { ...HEALTH.eval_state, verify: { ...HEALTH.eval_state.verify, status: 'ok', pass_fail_mismatches: 0, first_mismatches: [],
          kinds: { recovery: { compared: 412, mismatches: 0, skipped: 0, max_delta: null, measured: false } } } } }
      const CASES = [
        { name: 'off', cj: { enabled: false }, status: 'off', tone: 'muted', cardWarn: false },
        { name: 'on', cj: CJ(), status: 'on', tone: 'info', cardWarn: false, workers: 3 },
        { name: 'pendingOld', cj: CJ({ queue: { ...CJ().queue, oldest_pending_age_ms: 95_000 } }), status: 'warn', tone: 'warning', cardWarn: true, workers: 3 },
        { name: 'unreadable', cj: CJ({ queue: null, queue_error: 'CannotGetJdbcConnectionException' }), status: 'unknown', tone: 'muted', cardWarn: false, workers: 3 },
        { name: 'mismatch', cj: CJ({ workers: [W({ caps: ['port', 'http', 'keyword'], missing_caps: ['ping'] })], workers_total: 1 }),
          status: 'warn', tone: 'warning', cardWarn: true, workers: 1 },
      ]
      let current = { ...base, check_jobs: CASES[0].cj }
      await mock(page)
      await page.route((u) => new URL(u).pathname === '/api/admin/system',
        (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: current }) }))
      for (const c of CASES) {
        current = { ...base, check_jobs: c.cj }
        await page.goto('/?tab=health&sec=sched')
        const card = page.locator('[data-card="cluster"]')
        await expect(card, c.name).toBeVisible({ timeout: 30_000 })
        const sec = card.locator('[data-slot="cluster-check-jobs"]')
        await expect(sec, c.name).toHaveAttribute('data-status', c.status)
        await expect(sec.locator('[data-slot="pod-chip"]'), c.name).toHaveAttribute('data-scope', 'cluster')
        await expect(sec.locator('[data-slot="cj-switch"] [data-tone]'), c.name).toHaveAttribute('data-tone', c.tone)
        await expect(card.locator('[data-kind="leader"]'), c.name).toHaveAttribute('data-tone', c.cardWarn ? 'warning' : 'success')
        if (c.name === 'off') {
          await expect(sec.locator('[data-slot="cj-hint"]')).toBeVisible()
          await expect(sec.locator('[data-slot="cj-queue"]')).toHaveCount(0)
          await expect(card.locator('[data-slot="cluster-check-worker"]')).toHaveCount(0)
        } else {
          await expect(sec.locator('[data-slot="cj-mode"]'), c.name).toHaveCount(8)
          // A takipçi (lider B) → seçili türlerin kipi bilinmiyor + liderin yanıtına yönlendiren not
          await expect(sec.locator('[data-slot="cj-mode"][data-type="port"]'), c.name).toHaveAttribute('data-mode', 'unknown')
          await expect(sec.locator('[data-slot="cj-mode-follower"]'), c.name).toContainText(POD_B)
          const workers = sec.locator('[data-slot="cj-workers"]')
          await expect(workers.locator('[data-slot="cj-worker"]'), c.name).toHaveCount(c.workers)
          await expect(workers, c.name).toHaveAttribute('data-layout', vp.width >= 1280 ? 'table' : 'cards')
          await expect(workers, c.name).toContainText(POD_B)
          await expect(card.locator('[data-slot="cluster-this-pod"] [data-slot="cluster-check-worker"] [data-slot="cj-pod-slots"]'), c.name).toContainText('16')
        }
        if (c.name === 'pendingOld') await expect(sec.locator('[data-slot="cj-q-oldest-pending"]')).toHaveAttribute('data-warn', 'true')
        if (c.name === 'unreadable') {
          await expect(sec.locator('[data-slot="cj-queue"]')).toHaveAttribute('data-level', 'unknown')
          await expect(sec.locator('[data-slot="cj-q-pending"]')).toHaveCount(0)
        }
        if (c.name === 'mismatch') {
          await expect(sec.locator('[data-slot="cj-cap-mismatch"]')).toContainText(POD_B)
          await expect(sec.locator('[data-slot="cj-cap-missing"][data-cap="ping"]')).toBeVisible()
        }
        await page.waitForTimeout(250)
        expect(await page.evaluate(overflowIn, '[data-section="sched"]'), `${c.name}: yatay taşma`).toEqual([])
        expect(await page.evaluate(clippedIn, '[data-card="cluster"]'), `${c.name}: kırpılan metin`).toEqual([])
        if (vp.touch) expect(await page.evaluate(smallTargets, '[data-card="cluster"]'), `${c.name}: dokunma hedefi < 40 px`).toEqual([])
      }
      await page.locator('[data-slot="cluster-check-jobs"]').screenshot({ path: `test-results/cluster-check-jobs-${vp.name}.png` })
    })

    test('Küme kartı R3-4: parçalı sertifika taraması her durumda; pod payı tablo / kart; tarama kartı kipi; adlar sarar, taşma yok', async ({ page }) => {
      test.setTimeout(150_000)
      const POD_C = `${POD_B}-ucuncu-pod-uzun-bir-ad-kirpilmamali`
      const RUN = (over = {}) => ({ run_id: 'r-open', kind: 'hourly', state: 'RUNNING', coordinator: B, coordinator_pod: POD_B,
        created_at: fmt(NOW - 45_000), age_s: 45, heartbeat_age_s: 3, domains_total: 1200, chunks_total: 60, chunk_size: 20, evict_caches: true,
        chunks: { pending: 18, claimed: 12, done: 28, cancelled: 2 }, domains_measured: 560, oldest_pending_age_s: 6, oldest_pending_age_ms: 6_000,
        per_pod: [
          { instance_id: B, pod: POD_B, self: false, chunks: 18, domains: 360, done_domains: 240, network_errors: 3, network_error_rate: 0.0125, outlier: false },
          { instance_id: A, pod: POD_A, self: true, chunks: 14, domains: 280, done_domains: 200, network_errors: 2, network_error_rate: 0.01, outlier: false },
          { instance_id: `${POD_C}-0000000c`, pod: POD_C, self: false, chunks: 8, domains: 160, done_domains: 120, network_errors: 1, network_error_rate: 0.0083, outlier: false },
        ], outlier_pods: 0, takeovers: null, reclaims: null, second_opinions: null, ...over })
      const LAST = (over = {}) => ({ run_id: 'r-1', kind: 'hourly', state: 'DONE', outcome: 'DECIDED', coordinator_pod: POD_B,
        created_at: fmt(NOW - 3_600_000), finished_at: fmt(NOW - 3_550_000), duration_ms: 50_000, domains_total: 1200, chunks_total: 60,
        pods: 3, workers: [], takeovers: 1, reclaims: 0, second_opinions: 0, ...over })
      const CC = (over = {}) => ({ enabled: true, tables_ready: true, at: fmt(NOW), db_clock_stale: false, run: RUN(),
        last_runs: [LAST(), LAST({ run_id: 'r-0', kind: 'stale', state: 'ABANDONED', outcome: 'ORPHANED', duration_ms: null, pods: null, coordinator_pod: POD_C }),
          LAST({ run_id: 'r-m', state: 'DONE', outcome: 'STORE_FAILED', second_opinions: 4 })],
        min_errors: 3, error_rate_threshold: 0.5, last_mode: 'chunked',
        this_pod: { pod: POD_A, worker: { enabled: true, started: true, inflight_chunks: 2, inflight_domains: 40, budget: 40, leader_yielding: false,
          claimed: 90, completed: 85, late: 1, released: 2, discarded: 0, domains_measured: 1700, last_claim_at: fmt(NOW - 3_000), db_clock_stale: false },
        coordinator: { chunked_runs: 12, classic_fallback: 3, takeovers: 2, remeasured: 5, second_opinions: 0, abandoned: 1 } }, ...over })
      const SCAN = { ...HEALTH.scan, last_mode: 'chunked', runs: { ...HEALTH.scan.runs, hourly: { ...HEALTH.scan.runs.hourly, mode: 'chunked' } },
        chunked: { run_id: 'r-open', kind: 'hourly', state: 'RUNNING', coordinator_pod: POD_B, age_s: 45, chunks_done: 28, chunks_total: 60,
          domains_measured: 560, domains_total: 1200, oldest_pending_age_s: 6,
          per_pod: [{ pod: POD_B, chunks: 18, domains: 360 }, { pod: POD_A, chunks: 14, domains: 280 }, { pod: POD_C, chunks: 8, domains: 160 }] } }
      const base = { ...HEALTH, timestamp: fmt(NOW), scheduler: { ...HEALTH.scheduler, skipped_rounds: [] },
        eval_state: { ...HEALTH.eval_state, verify: { ...HEALTH.eval_state.verify, status: 'ok', pass_fail_mismatches: 0, first_mismatches: [],
          kinds: { recovery: { compared: 412, mismatches: 0, skipped: 0, max_delta: null, measured: false } } } } }
      const CASES = [
        { name: 'off', cc: { enabled: false }, scan: HEALTH.scan, status: 'off', tone: 'muted', cardWarn: false },
        { name: 'running', cc: CC(), scan: SCAN, status: 'running', tone: 'info', cardWarn: false, pods: 3 },
        { name: 'outlier', cc: CC({ run: RUN({ second_opinions: 24, per_pod: [RUN().per_pod[0], RUN().per_pod[1],
          { ...RUN().per_pod[2], network_errors: 70, network_error_rate: 0.5833, outlier: true }] }) }), scan: SCAN, status: 'warn', tone: 'warning', cardWarn: true, pods: 3 },
        { name: 'unreadable', cc: { enabled: true, status_error: 'CannotGetJdbcConnectionException', run: null, last_runs: null, last_mode: 'unknown',
          min_errors: 3, error_rate_threshold: 0.5, this_pod: { pod: POD_A, worker: null, coordinator: null } },
          scan: { ...HEALTH.scan, last_mode: 'unknown', chunked: null, chunked_error: 'CannotGetJdbcConnectionException' }, status: 'unknown', tone: 'muted', cardWarn: false },
        { name: 'idle', cc: CC({ run: null }), scan: { ...HEALTH.scan, last_mode: 'classic', chunked: null }, status: 'idle', tone: 'info', cardWarn: false },
      ]
      let current = { ...base, cert_chunks: CASES[0].cc }
      await mock(page)
      await page.route((u) => new URL(u).pathname === '/api/admin/system',
        (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: current }) }))
      for (const c of CASES) {
        current = { ...base, scan: c.scan, cert_chunks: c.cc }
        await page.goto('/?tab=health&sec=sched')
        const card = page.locator('[data-card="cluster"]')
        await expect(card, c.name).toBeVisible({ timeout: 30_000 })
        const sec = card.locator('[data-slot="cluster-cert-chunks"]')
        await expect(sec, c.name).toHaveAttribute('data-status', c.status)
        await expect(sec.locator('[data-slot="pod-chip"]'), c.name).toHaveAttribute('data-scope', 'cluster')
        await expect(sec.locator('[data-slot="cc-switch"] [data-tone]'), c.name).toHaveAttribute('data-tone', c.tone)
        await expect(card.locator('[data-kind="leader"]'), c.name).toHaveAttribute('data-tone', c.cardWarn ? 'warning' : 'success')
        const scanCard = page.locator('[data-card="scan"]')
        if (c.name === 'off') {
          await expect(sec.locator('[data-slot="cc-hint"]')).toBeVisible()
          await expect(sec.locator('[data-slot="cc-progress"]')).toHaveCount(0)
          await expect(card.locator('[data-slot="cluster-cert-chunk-worker"]')).toHaveCount(0)
          await expect(scanCard.locator('[data-slot="scan-mode"]')).toHaveCount(0)
        }
        if (c.pods) {
          const pods = sec.locator('[data-slot="cc-pods"]')
          await expect(pods.locator('[data-slot="cc-pod"]'), c.name).toHaveCount(c.pods)
          await expect(pods, c.name).toHaveAttribute('data-layout', vp.width >= 1280 ? 'table' : 'cards')
          await expect(pods, c.name).toContainText(POD_C)
          await expect(sec.locator('[data-slot="cc-last-run"]'), c.name).toHaveCount(3)
          await expect(scanCard.locator('[data-slot="scan-mode"]'), c.name).toHaveAttribute('data-mode', 'chunked')
          await expect(scanCard.locator('[data-slot="scan-chunked"]'), c.name).toContainText(POD_C)
          await expect(card.locator('[data-slot="cluster-this-pod"] [data-slot="cc-pod-inflight"]'), c.name).toContainText('40')
        }
        if (c.name === 'outlier') {
          await expect(sec.locator('[data-slot="cc-pod"][data-outlier="true"]')).toHaveCount(1)
          await expect(sec.locator('[data-slot="cc-warnings"] [data-warn="outlier"]')).toContainText(POD_C)
          await expect(sec.locator('[data-slot="cc-t-second"]')).toHaveAttribute('data-warn', 'true')
        }
        if (c.name === 'unreadable') {
          await expect(sec.locator('[data-slot="cc-hint"]')).toContainText('CannotGetJdbcConnectionException')
          await expect(sec.locator('[data-slot="cc-last-runs"]')).toHaveCount(0)
          await expect(scanCard.locator('[data-slot="scan-mode"]')).toHaveAttribute('data-tone', 'muted')
        }
        if (c.name === 'idle') {
          await expect(sec.locator('[data-slot="cc-progress"]')).toHaveCount(0)
          await expect(scanCard.locator('[data-slot="scan-mode"]')).toHaveAttribute('data-mode', 'classic')
        }
        await page.waitForTimeout(250)
        expect(await page.evaluate(overflowIn, '[data-section="sched"]'), `${c.name}: yatay taşma`).toEqual([])
        expect(await page.evaluate(clippedIn, '[data-card="cluster"]'), `${c.name}: kırpılan metin`).toEqual([])
        expect(await page.evaluate(clippedIn, '[data-card="scan"]'), `${c.name}: kırpılan metin (tarama kartı)`).toEqual([])
        if (vp.touch) expect(await page.evaluate(smallTargets, '[data-card="cluster"]'), `${c.name}: dokunma hedefi < 40 px`).toEqual([])
      }
      await page.locator('[data-slot="cluster-cert-chunks"]').screenshot({ path: `test-results/cluster-cert-chunks-${vp.name}.png` })
    })

    test('Heartbeat: Pod’lar kartı sessiz süreci gösterir; geçmiş penceresinde pod başına satırlar', async ({ page }) => {
      test.setTimeout(90_000)
      await mock(page)
      await page.goto('/?tab=health&sec=heartbeat')
      const pods = page.locator('[data-slot="hb-pod"]')
      await expect(pods).toHaveCount(3, { timeout: 30_000 })
      await expect(page.locator('[data-slot="hb-pod"][data-state="silent"]')).toContainText('99999999')
      await expect(page.locator('[data-slot="health-overall"]')).toHaveAttribute('data-level', 'warn')
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowIn, '[data-section="heartbeat"]')).toEqual([])
      expect(await page.evaluate(clippedIn, '[data-section="heartbeat"]')).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-section="heartbeat"] [data-card="heartbeat"]')).toEqual([])

      await page.locator('[data-card="heartbeat"]').getByRole('button').last().click()
      const rows = page.locator('[data-slot="hb-pod-row"]')
      await expect(rows).toHaveCount(3, { timeout: 15_000 })
      await page.waitForTimeout(400)
      const dlg = page.getByRole('dialog').last()
      const box = await dlg.boundingBox()
      expect(box.x).toBeGreaterThanOrEqual(-1)
      expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
      expect(await page.evaluate(clippedIn, '[data-slot="hb-pod-rows"]')).toEqual([])
    })

    test('Entegrasyonlar: RDAP kartı gözlemleyen pod; Uygulama: JVM grafiklerinin pod’u + Metrikler bağlantısı', async ({ page }) => {
      test.setTimeout(90_000)
      await mock(page)
      await page.goto('/?tab=health&sec=integrations')
      const dom = page.locator('[data-card="domain"]')
      await expect(dom.locator('[data-slot="dom-observed-by"]')).toContainText(POD_B, { timeout: 30_000 })
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowIn, '[data-section="integrations"]')).toEqual([])
      expect(await page.evaluate(clippedIn, '[data-card="domain"]')).toEqual([])

      await page.goto('/?tab=health&sec=sys')
      const sec = page.locator('[data-section="sys"]')
      await expect(sec.locator('[data-slot="serving-pod"]')).toContainText(POD_A, { timeout: 30_000 })
      await expect(sec.locator('[data-slot="pod-chip"]').filter({ hasText: POD_B })).toHaveCount(1)
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowIn, '[data-section="sys"]')).toEqual([])
      expect(await page.evaluate(clippedIn, '[data-section="sys"] [data-card="build"]')).toEqual([])
      if (vp.touch) {
        const link = await sec.locator('[data-slot="jvm-open-metrics"]').boundingBox()
        expect(link.height).toBeGreaterThanOrEqual(39.5)
      }
      await sec.locator('[data-slot="jvm-open-metrics"]').click()
      await expect(page).toHaveURL(/view=metrics/)
    })
  })
}
