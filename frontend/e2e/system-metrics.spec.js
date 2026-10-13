// Sistem Sağlığı → Metrikler (2026-10-13, kullanıcı isteği). Gerçek tarayıcıda 390 (dokunmatik) / 768 (dokunmatik) / 1280:
// giriş kartından açılır, adres `view=metrics`; özet kutucukları, pod listesi (dar ekranda kart, genişte tablo; uzun pod adı
// kırpılmaz), 14+ grafik gerçekten çizilir; yatay taşma yok, dokunma hedefleri ≥ 40 px; aralık / pod seçimi isteğe ve
// adrese yansır, adres yeniden yüklenince aynı görünüm açılır; Geri ana sayfaya döner. API mock'lu.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const LONG_POD = 'site-monitor-production-deployment-7f9c8d6b5a-xk2lp (Kurumsal Uygulamalar / Canlı Ortam)'
const fmt = (ms) => new Date(ms).toISOString().slice(0, 19)
const NOW = Date.now()
const PODS = [
  { id: 'pod-a:1', name: LONG_POD, live: true, state: 'live' },
  { id: 'pod-b:1', name: 'site-monitor-7f9c8d6b5a-q8m2z', live: true, state: 'live' },
  { id: 'pod-c:1', name: 'site-monitor-7f9c8d6b5a-old01', live: false, state: 'stopped' },
]
const latest = (cpu, heap) => ({ uptime_s: 93_784, cpu_process: cpu, heap_pct: heap, heap_used_mb: heap * 10, heap_max_mb: 1000, threads: 92,
  db_active: 3, db_max: 10, db_pending: 0, db_usage_pct: 30, exec_queue: 4, exec_queue_cap: 5000, http_requests: 40, http_errors: 0, http_p95_ms: 120 })
const OVERVIEW = {
  generated_at: fmt(NOW), served_by: 'pod-a:1', pods_live: 2,
  pods: [
    { ...PODS[0], last_seen: fmt(NOW - 40_000), latest: latest(23.4, 48) },
    { ...PODS[1], last_seen: fmt(NOW - 50_000), latest: latest(71.2, 66) },
    { ...PODS[2], state: 'stopped', last_seen: fmt(NOW - 5 * 3_600_000), latest: null },
  ],
  last_hour: { http_requests: 2400, http_errors: 18, http_error_pct: 0.8, http_avg_ms: 46, http_p95_ms: 410, caller_runs: 0, log_warn: 12, log_error: 1, gc_pause_ms: 140 },
}
const BUCKET = { '1h': ['1m', 60_000, 60], '6h': ['10m', 600_000, 360], '24h': ['10m', 600_000, 1440], '7d': ['1h', 3_600_000, 10_080] }

function series(range, instance) {
  const [bucket, step, minutes] = BUCKET[range] || BUCKET['6h']
  const from = NOW - minutes * 60_000
  const pods = instance ? PODS.filter((p) => p.id === instance) : PODS.slice(0, 2)
  const points = []
  for (let t = Math.ceil(from / step) * step, i = 0; t <= NOW; t += step, i++) {
    pods.forEach((p, j) => {
      const w = Math.sin(i / 5 + j) * 0.5 + 0.5
      const mins = Math.max(1, step / 60_000)
      points.push({ pod: p.id, t: fmt(t), samples: mins, delta_minutes: mins, http_minutes: mins, cpu_process_avg: 15 + 40 * w + j * 8, cpu_process_max: 30 + 50 * w,
        heap_used_max: 400 + 300 * w, heap_max: 1000, heap_pct_max: 40 + 30 * w, threads_max: 85 + Math.round(10 * w), gc_pause_ms_pm: Math.round(20 * w),
        db_active_max: 2 + Math.round(4 * w), db_max: 10, db_pending_max: 0, exec_active_max: Math.round(12 * w), exec_queue_max: Math.round(30 * w),
        exec_completed_pm: Math.round(400 * w), caller_runs_pm: 0, caller_runs: 0, http_requests: 300 + Math.round(200 * w), http_errors: Math.round(3 * w),
        http_rpm: 300 + Math.round(200 * w),
        http_sum_ms: 15_000 + Math.round(9_000 * w), http_p95_ms: 150 + Math.round(250 * w), log_warn_pm: Math.round(3 * w), log_error_pm: i % 17 === 0 ? 1 : 0,
        log_warn: Math.round(3 * w) * mins, log_error: i % 17 === 0 ? mins : 0,
        cache_hits: 900, cache_misses: 100 + Math.round(60 * w) })
    })
  }
  return { range, bucket, from: fmt(from), to: fmt(NOW), instance: instance || null, pods: PODS, points }
}

async function mock(page, calls) {
  await mockApi(page)
  await page.route((u) => new URL(u).pathname === '/api/admin/system/metrics/overview', (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: OVERVIEW }),
  }))
  await page.route((u) => new URL(u).pathname === '/api/admin/system/metrics/series', (route) => {
    const q = new URL(route.request().url()).searchParams
    calls.push({ range: q.get('range'), instance: q.get('instance') })
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: series(q.get('range'), q.get('instance')) }) })
  })
}

function overflowIn(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const vw = document.documentElement.clientWidth
  const out = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.width > 1 && r.right > vw + 1) out.push(`${el.tagName.toLowerCase()} ${Math.round(r.right)}`)
    if (out.length > 5) break
  }
  if (document.documentElement.scrollWidth > vw + 1) out.push(`page ${document.documentElement.scrollWidth}`)
  return out
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, [role="combobox"]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0) continue
    if (r.height < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(r.height)}`)
  }
  return out
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true, layout: 'cards' },
  { name: 'tablet', width: 768, height: 1024, touch: true, layout: 'cards' },
  { name: 'laptop', width: 1280, height: 800, touch: false, layout: 'table' },
]

for (const vp of VIEWPORTS) {
  test.describe(`Metrikler — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('giriş kartından açılır; kutucuklar, podlar, grafikler sığar; seçimler isteğe ve adrese yansır', async ({ page }) => {
      test.setTimeout(120_000)
      const calls = []
      await mock(page, calls)
      await page.goto('/?tab=health')
      const entry = page.locator('[data-slot="mx-entry"]')
      await expect(entry).toBeVisible({ timeout: 30_000 })
      await entry.getByRole('button').click()
      await expect(page).toHaveURL(/view=metrics/)
      const view = page.locator('[data-slot="mx-view"]')
      await expect(view.locator('[data-slot="mx-kpis"] [data-kpi]')).toHaveCount(7, { timeout: 30_000 })
      await expect(view.locator('[data-kpi="pods"]'), 'durmuş pod uyarı değil').toHaveAttribute('data-tone', 'ok')
      await expect(view.locator('[data-kpi="cpu"]')).toHaveAttribute('data-tone', 'warn')

      const pods = view.locator('[data-slot="mx-pods"]')
      await expect(pods).toHaveAttribute('data-layout', vp.layout)
      await expect(pods.locator('[data-slot="mx-pod"]')).toHaveCount(3)
      await expect(pods.locator('[data-slot="mx-pod-status"][data-state="stopped"]')).toHaveCount(1)
      // Tablo başlıkları dar sütunda taşmaz (sarar)
      const headOverflow = await pods.evaluate((root) => [...root.querySelectorAll('th')]
        .filter((th) => th.scrollWidth > th.clientWidth + 1).map((th) => th.textContent))
      expect(headOverflow, 'taşan başlık').toEqual([])
      const clipped = await pods.evaluate((root) => [...root.querySelectorAll('[data-slot="mx-pod-name"]')]
        .filter((n) => n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis').map((n) => n.textContent.slice(0, 30)))
      expect(clipped, 'kırpılan pod adı').toEqual([])
      await expect(pods.getByText(LONG_POD)).toBeVisible()

      const charts = view.locator('[data-slot="mx-chart"]')
      expect(await charts.count()).toBeGreaterThanOrEqual(14)
      await expect(view.locator('[data-slot="mx-chart"][data-empty]')).toHaveCount(0)
      const drawn = await charts.evaluateAll((els) => els.filter((c) => c.querySelector('.recharts-line-curve, .recharts-area-area')).length)
      expect(drawn, 'çizilen grafik').toBe(await charts.count())

      expect(await page.evaluate(overflowIn, '[data-slot="mx-view"]'), 'yatay taşma').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="mx-view"]'), 'küçük hedef').toEqual([])
      // Kutucuk metni (değer + alt satır) kırpılmaz
      const clippedTiles = await view.locator('[data-slot="mx-kpis"]').evaluate((root) => [...root.querySelectorAll('[data-kpi] span')]
        .filter((n) => n.children.length === 0 && !n.classList.contains('sr-only') && n.scrollWidth > n.clientWidth + 1)
        .map((n) => n.textContent))
      expect(clippedTiles, 'kırpılan kutucuk metni').toEqual([])

      // Aralık → istek + adres; yeniden yükleme aynı görünümü açar
      await view.getByRole('button', { name: /^(7 days|7 gün)$/ }).click()
      await expect.poll(() => calls.at(-1)?.range).toBe('7d')
      await expect(page).toHaveURL(/mx_range=7d/)
      await expect(view.locator('[data-slot="mx-bucket"]')).toHaveAttribute('data-bucket', '1h')
      await page.reload()
      await expect(page.locator('[data-slot="mx-view"] [data-slot="mx-bucket"]')).toHaveAttribute('data-bucket', '1h', { timeout: 30_000 })
      await expect.poll(() => calls.at(-1)?.range).toBe('7d')

      // Pod seçimi → istek tek podla
      const v2 = page.locator('[data-slot="mx-view"]')
      await v2.getByRole('combobox', { name: /^(Pod|Pod seçimi)$/ }).click()
      await page.getByRole('option', { name: 'site-monitor-7f9c8d6b5a-q8m2z' }).click()
      await expect.poll(() => calls.at(-1)?.instance).toBe('pod-b:1')
      await expect(page).toHaveURL(/mx_pod=pod-b/)
      await expect(v2.locator('[data-slot="mx-chart"][data-empty]')).toHaveCount(0)
      expect(await page.evaluate(overflowIn, '[data-slot="mx-view"]'), 'yatay taşma (tek pod)').toEqual([])

      // Geri → ana sayfa, mx_ parametreleri temizlenir
      await v2.getByRole('button', { name: /^(System Health|Sistem Sağlığı)$/ }).click()
      await expect(page.locator('[data-slot="mx-entry"]')).toBeVisible()
      await expect(page).not.toHaveURL(/view=metrics|mx_range|mx_pod/)
      await page.screenshot({ path: `test-results/system-metrics-${vp.name}.png`, fullPage: false })
    })
  })
}

// Türkçe başlıklar daha uzun ("İş parçacığı", "Çalışma süresi"): 1280'de tablo başlıkları ve pod adları taşmaz / kırpılmaz.
test.describe('Metrikler — Türkçe tablo 1280', () => {
  test.use({ viewport: { width: 1280, height: 800 } })

  test('başlıklar sarar, taşmaz; uzun pod adı tam görünür', async ({ page }) => {
    test.setTimeout(90_000)
    await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yok */ } })
    await mock(page, [])
    await page.goto('/?tab=health&view=metrics')
    const pods = page.locator('[data-slot="mx-pods"]')
    await expect(pods).toHaveAttribute('data-layout', 'table', { timeout: 30_000 })
    await expect(pods.getByText('Çalışma süresi')).toBeVisible()
    const overflow = await pods.evaluate((root) => [...root.querySelectorAll('th, td, [data-slot="mx-pod-name"]')]
      .filter((n) => n.scrollWidth > n.clientWidth + 1).map((n) => n.textContent.slice(0, 30)))
    expect(overflow, 'taşan hücre').toEqual([])
    expect(await page.evaluate(overflowIn, '[data-slot="mx-view"]'), 'yatay taşma').toEqual([])
  })
})

// Dağıtımdan sonra yeni podun tek kovası: iki yanı boş değer nokta olarak görünür (connectNulls=false sıfır uzunlukta çizerdi).
test.describe('Metrikler — yalnız kalan değer 1280', () => {
  test.use({ viewport: { width: 1280, height: 800 } })

  test('tek kovalık pod verisi grafikte nokta olarak çizilir', async ({ page }) => {
    test.setTimeout(90_000)
    await mockApi(page)
    await page.route((u) => new URL(u).pathname === '/api/admin/system/metrics/overview', (route) => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: OVERVIEW }),
    }))
    const bucket = Math.floor((NOW - 3 * 3_600_000) / 600_000) * 600_000
    await page.route((u) => new URL(u).pathname === '/api/admin/system/metrics/series', (route) => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: {
        range: '6h', bucket: '10m', from: fmt(Math.floor((NOW - 6 * 3_600_000) / 600_000) * 600_000), to: fmt(NOW), instance: null,
        pods: [PODS[1]],
        points: [{ pod: PODS[1].id, t: fmt(bucket), samples: 10, delta_minutes: 10, cpu_process_avg: 42, heap_pct_max: 50, threads_max: 90 }],
      } }),
    }))
    await page.goto('/?tab=health&view=metrics')
    const cpu = page.locator('[data-slot="mx-chart"][data-chart-id="cpu"]')
    await expect(cpu).toBeVisible({ timeout: 30_000 })
    await expect(cpu.locator('[data-slot="mx-dot"]')).toHaveCount(1)
  })
})
