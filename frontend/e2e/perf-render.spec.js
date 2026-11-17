// Ön yüz performans bekçisi (2026-11-17). Gerçek tarayıcıda, 1280 px, API tamamen mock'lu — çizim maliyetinin geri gelmesini
// yakalar (jsdom çizim / uzun görev ölçemez):
//  a) SQL Playground, 180 tablo: düzenleyiciye 30 karakter yazmak en çok 2 uzun görev (> 50 ms) üretir; çalışan sorgu 2 sn
//     beklerken şema gezgininde HİÇ DOM değişimi olmaz (geçen süre sayacı gezgini yeniden çizmez). SQL Playground düzeltmeleri
//     ayrı daldadır — bu bölüm birleşmiş kodun karşılaması gerekeni doğrular.
//  b) Sayfa Hızı ayrıntısı "Kaynaklar" dışındaki bir sekmede açıkken iki tazeleme döngüsü boyunca `/resources` OKUNMAZ
//     (sahte saat `page.clock` ile 30 sn'lik döngüler ilerletilir; liste her döngüde yeni bir ölçüm döndürür).
//  c) İstatistikler: arama kutusuna yazarken grafik kartlarında DOM değişimi olmaz; sonuç gecikmeden (debounce) sonra güncellenir.
import { test, expect } from '@playwright/test'
import { MONITORS, mockApi } from './support/monitorMocks.js'

test.use({ viewport: { width: 1280, height: 900 } })

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) })

/** Gözlemciyi kurar: seçicinin altındaki (alt ağaç, öznitelik, metin) bütün değişimler `window.__perfMut` dizisine sayılır. */
function startMutations(sel) {
  const root = document.querySelector(sel)
  if (!root) return false
  const records = []
  const mo = new MutationObserver((list) => {
    for (const r of list) records.push(`${r.type}:${r.target?.nodeName}:${r.attributeName ?? ''}`)
  })
  mo.observe(root, { subtree: true, childList: true, attributes: true, characterData: true })
  window.__perfMut = { mo, records }
  return true
}
function takeMutations() {
  const st = window.__perfMut
  if (!st) return ['NO_OBSERVER']
  st.records.push(...st.mo.takeRecords().map((r) => `${r.type}:${r.target?.nodeName}:${r.attributeName ?? ''}`))
  st.mo.disconnect()
  return st.records
}
/** Birden çok kökü aynı anda izler (istatistik grafik kartları). */
function startMutationsAll(sels) {
  const records = []
  const observers = []
  for (const sel of sels) {
    const root = document.querySelector(sel)
    if (!root) continue
    const mo = new MutationObserver((list) => {
      for (const r of list) records.push(`${sel} ${r.type}:${r.target?.nodeName}:${r.attributeName ?? ''}`)
    })
    mo.observe(root, { subtree: true, childList: true, attributes: true, characterData: true })
    observers.push(mo)
  }
  window.__perfMutAll = { observers, records }
  return observers.length
}
function takeMutationsAll() {
  const st = window.__perfMutAll
  if (!st) return ['NO_OBSERVER']
  for (const mo of st.observers) mo.disconnect()
  return st.records
}

test.describe('Performans — SQL Playground (180 tablo)', () => {
  const TABLES = Array.from({ length: 180 }, (_, i) => ({ table_name: `tbl_${String(i).padStart(3, '0')}_kayitlari`, live_rows: i * 17 }))

  test('30 karakter yazmak ≤ 2 uzun görev; çalışan sorgu beklerken şema gezgini değişmez', async ({ page }) => {
    test.setTimeout(120_000)
    // Uzun görev sayacı sayfa açılmadan kurulur (buffered: ilk yüklemedekiler de görülür, yazma anına göre süzülür)
    await page.addInitScript(() => {
      try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yoksay */ }
      window.__longTasks = []
      try {
        new PerformanceObserver((list) => {
          for (const e of list.getEntries()) window.__longTasks.push({ start: e.startTime, dur: e.duration })
        }).observe({ type: 'longtask', buffered: true })
      } catch { /* longtask desteklenmiyor */ }
    })
    await mockApi(page)
    const state = { executes: 0, release: null, pending: false }
    await page.route((u) => new URL(u).pathname.startsWith('/api/admin/sql/'), async (route) => {
      const p = new URL(route.request().url()).pathname
      if (p === '/api/admin/sql/tables') return route.fulfill(json({ success: true, data: TABLES }))
      if (p === '/api/admin/sql/execute') {
        state.executes += 1
        state.pending = true
        await new Promise((resolve) => { state.release = resolve; setTimeout(resolve, 15_000) })
        state.pending = false
        return route.fulfill(json({ success: true, ok: true, rows: [{ n: 1 }], rowCount: 1, durationMs: 2000, run_id: route.request().postDataJSON()?.run_id }))
      }
      return route.fallback()
    })
    await page.goto('/?tab=sqlplayground')
    const editor = page.getByRole('textbox', { name: 'SQL sorgusu' })
    await expect(editor).toBeVisible({ timeout: 30_000 })
    const schema = page.locator('[data-slot="sql-schema"]')
    await expect(schema).toBeVisible({ timeout: 30_000 })
    await expect(schema).toContainText('tbl_179_kayitlari', { timeout: 30_000 })
    await page.waitForTimeout(1_000)   // ilk çizim ve tembel parçalar otursun

    // a1) Yazma: yalnız yazma penceresindeki uzun görevler sayılır
    await editor.click()
    await editor.press('Control+End')
    const t0 = await page.evaluate(() => performance.now())
    const text = 'SELECT count(*) FROM alerts a '
    expect(text).toHaveLength(30)
    await page.keyboard.type(text)
    await page.waitForTimeout(500)      // son tuşun çizimi ve ertelenmiş işler biter
    const long = await page.evaluate((from) => (window.__longTasks || []).filter((e) => e.start >= from && e.dur > 50), t0)
    expect(long.length, `yazarken uzun görevler: ${JSON.stringify(long)}`).toBeLessThanOrEqual(2)

    // a2) Çalıştır → uç 2 sn beklerken şema gezgininde DOM değişimi yok
    await editor.press('Control+Enter')
    await expect.poll(() => state.pending, { timeout: 10_000 }).toBe(true)
    await page.waitForTimeout(150)      // çalıştırma durumunun ilk çizimi otursun (gözlem bundan sonra)
    expect(await page.evaluate(startMutations, '[data-slot="sql-schema"]')).toBe(true)
    await page.waitForTimeout(2_000)
    expect(state.pending, 'sorgu ölçüm boyunca beklemede kalmalı').toBe(true)
    const muts = await page.evaluate(takeMutations)
    state.release?.()
    expect(muts, `sorgu beklerken şema gezgininde DOM değişimi: ${muts.slice(0, 10).join(', ')}`).toEqual([])
  })
})

test.describe('Performans — Sayfa Hızı ayrıntısı', () => {
  test('"Kaynaklar" dışındaki sekmede iki tazeleme döngüsü boyunca /resources okunmaz', async ({ page }) => {
    test.setTimeout(120_000)
    if (!page.clock || typeof page.clock.install !== 'function') test.skip(true, 'page.clock bu Playwright sürümünde yok')
    await page.clock.install()
    await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yoksay */ } })
    await mockApi(page)
    let listCalls = 0
    const resourceCalls = []
    const stamp = (n) => new Date(Date.now() - 60_000 + n * 1000).toISOString().slice(0, 19)
    const rowFor = (n) => ({
      ...MONITORS.pagespeed[0], id: 100, active: true, ok: true, can_check: true, can_edit: true,
      response_ms: 1400, ttfb_ms: 180, total_bytes: 1_250_000, request_count: 46, html_ms: 300,
      // Her liste okumasında YENİ bir ölçüm — eski kod sekme kapalıyken de kırılımı okuyordu
      last_check: stamp(n), checked_at: stamp(n),
    })
    await page.route((u) => new URL(u).pathname === '/api/monitoring/pagespeed', (r) => {
      listCalls += 1
      return r.fulfill(json({ success: true, data: [rowFor(listCalls)] }))
    })
    await page.route((u) => /^\/api\/monitoring\/pagespeed\/\d+\/resources$/.test(new URL(u).pathname), (r) => {
      resourceCalls.push(new URL(r.request().url()).search)
      return r.fulfill(json({ success: true, data: { resources: [], total: 0, filtered_total: 0, page: 0, size: 50, facets: {}, breaches: [] } }))
    })
    await page.goto('/?tab=pagespeed')
    const card = page.locator('.upt-grid [data-slot="card"]').first()
    await expect(card).toBeVisible({ timeout: 30_000 })
    await card.locator('[data-monitor-open]').first().click()
    const dialog = page.getByRole('dialog').first()
    await dialog.waitFor()
    // Varsayılan sekme "Kaynaklar" — açılışta bir kez okunur
    await expect.poll(() => resourceCalls.length, { timeout: 10_000 }).toBeGreaterThanOrEqual(1)
    await dialog.getByRole('tab', { name: /^(Grafik|Chart)$/ }).click()
    await page.waitForTimeout(500)
    const resBefore = resourceCalls.length

    // İki tazeleme döngüsü (30 sn'lik ayrıntı yoklaması + 60 sn'lik liste yoklaması)
    for (let cycle = 1; cycle <= 2; cycle += 1) {
      const listBefore = listCalls
      await page.clock.fastForward(31_000)
      await expect.poll(() => listCalls, { timeout: 10_000, message: `döngü ${cycle}: liste yeniden okunmadı` }).toBeGreaterThan(listBefore)
      await page.waitForTimeout(500)   // ardından gelebilecek kırılım isteği için pay
    }
    expect(resourceCalls.length - resBefore, `sekme kapalıyken kırılım okundu: ${resourceCalls.slice(resBefore).join(' | ')}`).toBe(0)

    // Sekmeye dönünce (yeni ölçüm geldiği için) HEMEN bir kez okunur
    await dialog.getByRole('tab', { name: /^(Kaynaklar|Resources)$/ }).click()
    await expect.poll(() => resourceCalls.length, { timeout: 10_000 }).toBe(resBefore + 1)
  })
})

test.describe('Performans — İstatistikler araması', () => {
  const NOW = Date.now()
  const DAY = 86_400_000
  const iso = (ms) => new Date(NOW + ms).toISOString().slice(0, 19)
  const cert = (i, domain, days) => ({
    domain, port: 443, days_remaining: days, not_after: iso(days * DAY), not_before: iso(-300 * DAY), checked_at: iso(-60_000),
    status: 'valid', alert_level: days <= 7 ? 'critical' : days <= 30 ? 'warning' : 'valid',
    issuer_cn: ['R3', 'DigiCert Global G2 TLS RSA SHA256 2020 CA1'][i % 2], tier: 1 + (i % 4), team_id: 1, team_name: 'Takım A',
    owner_team_ids: [1], tls_grade: 'A', chain_status: 'VALID', trust_status: 'TRUSTED', public_key_algorithm: 'RSA', public_key_size: 2048,
  })
  const TARGET = 'odeme-servisleri-yedek-bolge-2'   // 30 karakter
  const CERTS = [
    ...Array.from({ length: 40 }, (_, i) => cert(i, `servis-${i + 1}.example.com`, [3, 9, 15, 24, 40, 55, 70, 90, 120, 200][i % 10])),
    cert(41, `${TARGET}.example.com`, 21),
  ]

  test('yazarken grafik kartları değişmez; sonuç gecikmeden sonra güncellenir', async ({ page }) => {
    test.setTimeout(120_000)
    await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yoksay */ } })
    await mockApi(page, { perms: { 'alerts.read': { view: true }, 'monitoring.read': { view: true }, 'inventory.list': { view: true } } })
    const HEALTH = { scope: 'org', org_allowed: true, teams: [{ id: 1, name: 'Takım A' }], today: iso(0).slice(0, 10),
      collecting_since: iso(-20 * DAY).slice(0, 10),
      points: Array.from({ length: 21 }, (_, i) => ({ day: iso(-(20 - i) * DAY).slice(0, 10), total: 41, evaluated: 41, ok: 30 + (i % 4),
        under30: 6, under7: 2, expired: 0, error: 0, health_pct: Math.round(((30 + (i % 4)) / 41) * 1000) / 10 })) }
    const routes = {
      '/api/certificates': { success: true, data: CERTS, timestamp: iso(0) },
      '/api/certificates/card-extras': { success: true, data: {} },
      '/api/stats': { success: true, data: { total: CERTS.length, valid: 30, warning: 8, critical: 3, error: 0, expired: 0 } },
      '/api/stats/teams': { success: true, data: { mode: 'domains', teams: [{ team_id: 1, team_name: 'Takım A', sy_domains: CERTS.map((c) => c.domain), ug_domains: [] }] } },
      '/api/stats/executive': { success: true, data: {
        certs: { total: CERTS.length, ok: 30, evaluated: CERTS.length, unchecked: 0, under30: 8, under7: 3, expired: 0, error: 0, health_pct: 73.1 },
        alerts: { visible: true, open: 2, critical: 1, opened_last30: 9, opened_prev30: 7, delta: 2 },
        sla: { target_pct: 99.9, monitors: 10, breaches: 0, worst: [], by_team: [] }, teams: [], window_days: 30 } },
      '/api/stats/renewal-timeliness': { success: true, data: { retention_days: 180, windows: [3, 6, 12].map((m) => ({ months: m, renewals: 10, before_expiry: 9,
        after_expiry: 1, last_minute: 1, lapses: 0, on_time_pct: 90, clamped: false, effective_days: m * 30 })) } },
      '/api/stats/health-series': { success: true, data: HEALTH },
      '/api/admin/alerts/noise': { success: true, data: { days: 30, total: 9, critical: 1, resolved_pct: 80, mttr_minutes: 60,
        series: Array.from({ length: 30 }, (_, i) => ({ date: new Date(NOW - (29 - i) * DAY).toISOString().slice(0, 10), count: i % 3 })) } },
      '/api/admin/alerts/team-stats': { success: true, data: { teams: [{ team_id: 1, team_name: 'Takım A', open: 2, last30: 9 }] } },
    }
    await page.route((u) => Object.hasOwn(routes, new URL(u).pathname), (route) => route.fulfill(json(routes[new URL(route.request().url()).pathname])))
    await page.route((u) => new URL(u).pathname === '/api/stats/changes', (route) => route.fulfill(json({ success: true,
      data: { days: 7, added: 1, removed: 0, renewed: 2, alerts_opened: 3, alerts_resolved: 2 } })))

    await page.goto('/?tab=stats')
    const view = page.locator('[data-slot="stats-view"]')
    await expect(view).toBeVisible({ timeout: 30_000 })
    const CHARTS = ['[data-slot="stats-weeks"]', '[data-slot="stats-health"]', '[data-slot="stats-alarm-trend"]']
    for (const sel of CHARTS) await expect(view.locator(sel), sel).toBeVisible({ timeout: 30_000 })
    const rows = view.locator('[data-slot="stats-row"], [data-slot="stats-card"]')
    await expect.poll(() => rows.count(), { timeout: 30_000 }).toBeGreaterThan(1)
    await page.waitForTimeout(1_500)   // grafik animasyonları biter

    const box = view.locator('[data-slot="stats-toolbar"] input[type="search"]')
    await box.click()
    expect(await page.evaluate(startMutationsAll, CHARTS)).toBe(CHARTS.length)
    // Tuşlar gecikme süresinden (250 ms) kısa aralıklarla — yazma bitene kadar arama uygulanmaz
    await box.pressSequentially(TARGET, { delay: 15 })
    const muts = await page.evaluate(takeMutationsAll)
    expect(muts, `yazarken grafik kartlarında DOM değişimi: ${muts.slice(0, 10).join(', ')}`).toEqual([])
    await expect(box).toHaveValue(TARGET)

    // Gecikmeden sonra sonuç güncellenir: yalnız aranan alan adı kalır
    await expect.poll(() => rows.count(), { timeout: 10_000 }).toBe(1)
    await expect(rows.first()).toHaveAttribute('data-domain', `${TARGET}.example.com`)
  })
})
