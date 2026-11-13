// Sistem Sağlığı → Veritabanı büyümesi (2026-11-13, kullanıcı kararı: dört parça birden). Gerçek tarayıcıda 390 (dokunmatik) /
// 768 (dokunmatik) / 1280: KPI kutucukları, iki grafik, öneriler, tablo listesi (kap genişliği ≥ 860 px → tablo, altında kart),
// sağlık analizi sığar; yatay taşma yok, metin kırpılmaz, dokunma hedefleri ≥ 40 px. "Uygula" onay penceresi (uyum uyarısı,
// silinecek satır tahmini) Veri Saklama'nın kayıt ucunu aynı anahtarla çağırır; "Veri Saklama'da aç" politikayı odaklar.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const MB = 1024 * 1024
const GB = 1024 * MB
const DAY = 86_400_000
const today = new Date(Date.UTC(2026, 10, 13))
const day = (offset) => new Date(today.getTime() + offset * DAY).toISOString().slice(0, 10)

const LONG = 'tech_error_report_events_with_a_very_long_table_name_for_layout'
const TABLE_NAMES = ['port_checks', 'ping_checks', 'activity_log', LONG, 'http_checks', 'keyword_results', 'page_checks', 'dns_records',
  ...Array.from({ length: 22 }, (_, i) => `aux_table_${String(i).padStart(2, '0')}`)]

function tableRow(name, i) {
  const big = Math.max(1, 30 - i)
  return {
    name, total_bytes: big * GB, table_bytes: big * 0.6 * GB, index_bytes: big * 0.4 * GB, toast_bytes: 0, live_rows: big * 5e6,
    dead_rows: 1000, growth_30d_bytes: i % 3 === 0 ? big * 0.1 * GB : null, daily_growth_bytes: big * 3 * MB, daily_growth_estimate: i % 3 !== 0,
    rows_per_day: i === 1 ? 2.5 : big * 1000, rows_per_day_method: i % 2 ? 'histogram' : 'stats', oldest_at: '2026-08-15T00:00:00',
    oldest_source: 'min',
    retention: i === 3 ? null : { policy_id: `p-${i}`, mode: i === 2 ? 'BOUNDED' : 'AGE', days: i === 2 ? null : 90, min_days: 7, configurable: true,
      setting_key: `site.monitor.p${i}.retention-days`, data_class: i === 2 ? 'PERSONAL' : 'OPERATIONAL', policies: 1 },
    saturation_pct: i === 0 ? 100 : 64.2, saturated: i === 0, equilibrium_bytes: i === 0 ? null : big * 1.5 * GB,
    projection_90d_bytes: big * 1.3 * GB,
    findings: i === 0 ? ['SHORTEN_CANDIDATE'] : i === 3 ? ['NO_RULE', 'TOAST_HEAVY'] : i === 4 ? ['NOT_SATURATED'] : [],
  }
}

const VIEW = {
  range_days: 90, generated_at: '2026-11-13T10:00:00', capacity_gb: 500, hold_active: false, supported: true,
  history_days: 60, first_day: day(-59), last_day: day(0), snapshot_today: true, live_only: false,
  current_bytes: 412 * GB, current_source: 'live', forecast_basis: 'history', history_points: 30, min_points: 7,
  forecast: Array.from({ length: 180 }, (_, i) => ({ day: day(i + 1), value: (412 + 0.6 * (i + 1)) * GB,
    low: (412 + 0.4 * (i + 1)) * GB, high: (412 + 0.8 * (i + 1)) * GB })),
  horizons: [30, 90, 180].map((d) => ({ days: d, value: (412 + 0.6 * d) * GB, low: (412 + 0.4 * d) * GB, high: (412 + 0.8 * d) * GB })),
  kpis: { current_bytes: 412 * GB, daily_growth_bytes: 615 * MB, daily_growth_estimate: false, growth_30d_bytes: 18 * GB,
    projection_90d_bytes: 466 * GB, projection_90d_estimate: false, used_pct: 82.4, days_until_full: 146, fills_within_horizon: true,
    days_until_full_estimate: false },
  series: {
    top_tables: TABLE_NAMES.slice(0, 8),
    // 4 günlük boşluk (görüntü alınamadı) — çizgi kopmalı, sıfıra inmemeli
    database: Array.from({ length: 60 }, (_, i) => i).filter((i) => i < 20 || i > 23).map((i) => ({ day: day(i - 59), bytes: (376 + 0.6 * i) * GB })),
    tables: TABLE_NAMES.slice(0, 8).map((name, k) => ({ name, points: Array.from({ length: 60 }, (_, i) => ({ day: day(i - 59), bytes: (30 - k + i * 0.05) * GB })) })),
  },
  tables: TABLE_NAMES.map(tableRow),
  findings: [
    { code: 'SHORTEN_CANDIDATE', severity: 'warn', table: 'activity_log', policy_id: 'activity-log', setting_key: 'site.monitor.activity.retention-days',
      current_days: 365, suggested_days: 180, min_days: 90, est_rows: 41_200_000, est_bytes: 9.4 * GB, est_method: 'uniform', data_class: 'PERSONAL',
      action: 'apply_retention', params: { share_pct: 26.4, table_bytes: 28 * GB, compliance: true } },
    { code: 'NO_RULE', severity: 'warn', table: LONG, action: 'code', params: { growth_30d_bytes: 3 * GB, table_bytes: 27 * GB } },
    { code: 'NOT_SATURATED', severity: 'info', table: 'http_checks', policy_id: 'series-http', setting_key: 'site.monitor.series.http.retention-days',
      current_days: 90, min_days: 30, data_class: 'OPERATIONAL', action: 'open_retention',
      params: { oldest_age_days: 41.5, retention_days: 90, saturation_pct: 46.1, equilibrium_bytes: 48 * GB, days_to_saturate: 49, table_bytes: 26 * GB } },
  ],
  can_edit_capacity: true, can_apply: true, can_open_retention: true, can_analyze: true, capacity_key: 'site.monitor.db.capacity-gb',
  max_capacity_gb: 1_000_000, last_analysis: { run_id: 9, started_at: '2026-11-13T09:00:00', actor: 'admin', status: 'PARTIAL' }, analysis_running: false,
}

const ANALYSIS = {
  run_id: 9, started_at: '2026-11-13T09:00:00', finished_at: '2026-11-13T09:00:12', duration_ms: 12400, actor: 'admin',
  pod: 'sitemonitor-prod-7d9f8c6b5d-x2k4p', status: 'PARTIAL', supported: true,
  sections: { policies: 'ok', vacuum: 'ok', hot_monitors: 'skipped', index_bloat: 'ok', unused_indexes: 'failed' },
  index_bloat: { checked: 84, unknown: 2, bloated: [] },
  findings: [
    { code: 'INDEX_BLOAT', severity: 'warn', table: 'audit_log', action: 'dba', params: { index: 'idx_audit_log_event_time_and_a_long_suffix', index_bytes: 2 * GB, expected_bytes: 300 * MB, maintained: false } },
    { code: 'HOT_MONITOR', severity: 'info', table: 'ping_checks', action: 'none', params: { type: 'ping', monitor_id: 42, rows_24h: 2880 } },
  ],
}

const RETENTION = {
  hold_active: false, hold_key: 'site.monitor.retention.hold-enabled', totals: { rows: 2000, bytes: 2048, purgeable: 0, policies: 2, tables: 2 },
  last_run: null, approvals: {},
  policies: [
    { id: 'activity-log', table: 'activity_log', data_class: 'PERSONAL', mode: 'AGE', setting_key: 'site.monitor.activity.retention-days',
      configurable: true, deletes: true, days: 365, default_days: 365, min_days: 90, rows: 1000, bytes: 1024, oldest_at: '2025-11-13T00:00:00',
      newest_at: '2026-11-13T00:00:00', purgeable: 0, rationale: 'Her kontrol bir satır', rule: 'activity_time < ?', time_column: 'activity_time' },
    { id: 'series-http', table: 'http_checks', data_class: 'OPERATIONAL', mode: 'AGE', setting_key: 'site.monitor.series.http.retention-days',
      configurable: true, deletes: true, days: 90, default_days: 90, min_days: 30, rows: 1000, bytes: 1024, oldest_at: '2026-10-01T00:00:00',
      newest_at: '2026-11-13T00:00:00', purgeable: 0, rationale: 'HTTP ham serisi', rule: 'checked_at < ?', time_column: 'checked_at' },
  ],
}

async function mock(page, saved) {
  await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yoksay */ } })
  await mockApi(page)
  const json = (body) => ({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  await page.route((u) => new URL(u).pathname.startsWith('/api/admin/system/db-growth'), (route) => {
    const p = new URL(route.request().url()).pathname
    if (p.endsWith('/analysis/latest')) return route.fulfill(json({ success: true, data: { analysis: ANALYSIS, running: false } }))
    if (p.endsWith('/what-if')) {
      return route.fulfill(json({ success: true, data: { policy_id: 'activity-log', table: 'activity_log', setting_key: 'site.monitor.activity.retention-days',
        current_days: 365, days: 180, min_days: 90, data_class: 'PERSONAL', compliance: true, supported: true, rows: 40_812_000, rows_method: 'count',
        table_bytes: 28 * GB, live_rows: 82_000_000, bytes_estimate: 9.3 * GB } }))
    }
    return route.fulfill(json({ success: true, data: VIEW }))
  })
  await page.route((u) => new URL(u).pathname === '/api/admin/retention/settings', (route) => {
    try { saved.push(JSON.parse(route.request().postData() || '{}')) } catch { saved.push(null) }
    return route.fulfill(json({ success: true, message: 'Kaydedildi' }))
  })
  await page.route((u) => new URL(u).pathname === '/api/admin/retention/overview', (route) => route.fulfill(json({ success: true, data: RETENTION })))
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
  return out
}

/** Kırpılan metin: taşan ve gizleyen (overflow hidden / clip) metin kutuları — grafik SVG'si ve ekran okuyucu metni hariç. */
function clippedIn(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const el of root.querySelectorAll('span, p, code, dd, dt, h3, h4, td, th, li')) {
    if (el.closest('svg') || el.classList.contains('sr-only')) continue
    const cs = getComputedStyle(el)
    if (!['hidden', 'clip'].includes(cs.overflowX)) continue
    if (el.scrollWidth > el.clientWidth + 1) out.push((el.textContent || '').trim().slice(0, 40))
    if (out.length > 5) break
  }
  return out
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, a[href]')) {
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
  test.describe(`Veritabanı büyümesi — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('bölüm DB bölümünün ardında; KPI, grafikler, öneriler, tablolar ve analiz sığar', async ({ page }) => {
      test.setTimeout(90_000)
      await mock(page, [])
      await page.goto('/?tab=health&sec=dbgrowth')
      const sec = page.locator('[data-slot="dbg-section"]')
      await expect(sec).toHaveAttribute('data-state', 'ready', { timeout: 30_000 })
      const sections = await page.locator('[data-section]').evaluateAll((els) => els.map((e) => e.getAttribute('data-section')))
      expect(sections.indexOf('dbgrowth')).toBe(sections.indexOf('db') + 1)
      await expect(sec.locator('[data-slot="hreq-tile"]')).toHaveCount(6)
      await expect(sec.locator('[data-kpi="used"]')).toHaveAttribute('data-tone', 'warn')
      await expect(sec.locator('[data-slot="dbg-chart"]')).toHaveCount(2)
      await expect(sec.locator('[data-slot="dbg-chart"] svg').first()).toBeVisible()
      await expect(sec.locator('[data-slot="dbg-finding"]').first()).toHaveAttribute('data-code', 'SHORTEN_CANDIDATE')
      await expect(sec.locator('[data-slot="dbg-analysis-result"]')).toHaveAttribute('data-status', 'PARTIAL')

      // Tablo ↔ kart: kap genişliğine göre
      const tbl = sec.locator('[data-slot="dbg-tables"]')
      const width = await tbl.evaluate((el) => el.clientWidth)
      await expect(tbl).toHaveAttribute('data-layout', width >= 860 ? 'table' : 'cards')
      if (vp.width < 800) await expect(tbl).toHaveAttribute('data-layout', 'cards')
      await expect(tbl.getByText(LONG).first()).toBeVisible()

      expect(await page.evaluate(overflowIn, '[data-slot="dbg-section"]'), 'yatay taşma').toEqual([])
      expect(await page.evaluate(clippedIn, '[data-slot="dbg-section"]'), 'kırpılan metin').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="dbg-section"]'), 'küçük hedef').toEqual([])
      await page.screenshot({ path: `test-results/db-growth-${vp.name}.png`, fullPage: true })
    })

    test('Uygula: uyum uyarısı + kesin sayım; Veri Saklama kayıt ucu aynı anahtarla çağrılır', async ({ page }) => {
      test.setTimeout(90_000)
      const saved = []
      await mock(page, saved)
      await page.goto('/?tab=health&sec=dbgrowth')
      const sec = page.locator('[data-slot="dbg-section"]')
      await expect(sec).toHaveAttribute('data-state', 'ready', { timeout: 30_000 })
      await sec.locator('[data-slot="dbg-apply"]').first().click()
      const dlg = page.locator('[data-slot="dbg-apply-dialog"]')
      await expect(dlg).toBeVisible()
      await expect(dlg.locator('[data-slot="dbg-apply-estimate"]')).toContainText('40.812.000')
      await expect(dlg).toContainText('Uyum onayı gerektiren veri')
      expect(await page.evaluate(overflowIn, '[data-slot="dbg-apply-dialog"]'), 'pencerede taşma').toEqual([])
      if (vp.touch) {
        const small = await page.evaluate(() => [...document.querySelectorAll('[role="dialog"] button')]
          .map((b) => b.getBoundingClientRect()).filter((r) => r.height > 0 && r.height < 39.5).length)
        expect(small, 'pencerede küçük hedef').toBe(0)
      }
      await page.locator('[data-slot="dbg-apply-confirm"]').click()
      await expect(dlg).toHaveCount(0)
      expect(saved).toEqual([{ values: { 'site.monitor.activity.retention-days': '180' } }])
    })

    test("Veri Saklama'da aç → politika açılır ve vurgulanır", async ({ page }) => {
      test.setTimeout(90_000)
      await mock(page, [])
      await page.goto('/?tab=health&sec=dbgrowth')
      const sec = page.locator('[data-slot="dbg-section"]')
      await expect(sec).toHaveAttribute('data-state', 'ready', { timeout: 30_000 })
      await sec.locator('[data-slot="dbg-finding"][data-code="NOT_SATURATED"] [data-slot="dbg-open-retention"]').click()
      await expect(page).toHaveURL(/tab=settings/)
      const row = page.locator('[data-slot="policy-row"][data-policy-id="series-http"]')
      await expect(row).toHaveAttribute('data-focused', 'true', { timeout: 30_000 })
      await expect(row).toHaveAttribute('data-open', 'true')
      await expect(row).toBeInViewport()
      // Tek seferlik: odaklandıktan sonra param adresten silinir (bölüm değiştirip dönünce yeniden odaklanmaz)
      await expect(page).not.toHaveURL(/ret_focus=/)
    })
  })
}
