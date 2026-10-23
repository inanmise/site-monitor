// Sistem Sağlığı kümeyi doğru gösterir (2026-10-23, çok pod R2b, paket C1). Gerçek tarayıcıda 390 (dokunmatik) / 768
// (dokunmatik) / 1280: Zamanlayıcı bölümünde Küme kartı (tarama lideri = küme, yanıtlayan pod ayrı), yürütücü / havuz
// "Bu pod: <ad>", zamanlayıcı / kilit / tarama "Küme"; Heartbeat bölümünde Pod'lar kartı (sessiz pod); Entegrasyonlar'da RDAP
// kartının gözlemleyen pod'u; Uygulama bölümünde JVM grafiklerinin pod'u + Metrikler bağlantısı. Gerçek uzunlukta pod adları
// SARAR (kırpılmaz), yatay taşma yok, dokunma hedefleri ≥ 40 px. API mock'lu.
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
}
const TIMELINE = { days: 1, bucket_minutes: 10, counting: 'distinct_minutes', served_by: A, pods_more: 0,
  buckets: Array.from({ length: 144 }, (_, i) => ({ start: fmt(NOW - (144 - i) * 600_000), expected: 10, received: i === 120 ? 4 : 10 })),
  pods: HEALTH.heartbeat.pods.map((p) => ({ ...p, received: p.state === 'silent' ? 900 : 1430, expected: p.state === 'silent' ? 1020 : 1440 })) }

async function mock(page) {
  await mockApi(page)
  const json = (data, extra = {}) => (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data, ...extra }) })
  await page.route((u) => new URL(u).pathname === '/api/admin/system', json(HEALTH))
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
