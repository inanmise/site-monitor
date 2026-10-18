import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { mockApi } from './support/monitorMocks.js'

// 2026-10-18 (kullanıcı: "Grafana'da SiteMonitor'ü ayrı bir panoyla uçtan uca izleyeyim; Ayarlar'daki bir sayfadan içe
// aktarılabilir JSON'u indireyim; shadcn, mweb responsive"): Ayarlar → Platform → Grafana panosu telefon (390, dokunmatik),
// tablet (768, dokunmatik) ve dizüstünde (1280): sayfa yatay kaymaz, öğe ekran dışına taşmaz, metin kırpılmaz, dokunmatikte
// hedefler ≥ 40 px; "JSON indir" GERÇEK bir dosya indirir — inen dosya üreticinin çıktısıdır (e2e/fixtures, arka uç
// GrafanaDashboardFixtureTest güncel tutar) ve ayrıştırılır: __inputs DS_PROMETHEUS, takım / tür değişkenleri, 30+ panel, boş
// başlık yok. Alan hatası alanın altında; kayıt yalnız değişen alanları gönderir; kapsamlı müdür salt okunur.
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

const HERE = path.dirname(fileURLToPath(import.meta.url))
const FIXTURE = readFileSync(path.join(HERE, 'fixtures', 'grafana-dashboard.sample.json'), 'utf8')
const FILE_NAME = 'sitemonitor-grafana-dashboard-20.157.0.json'
const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const SECTIONS = ['overview', 'alerts', 'monitors', 'certificates', 'notifications', 'health', 'runtime']

const VALUES = {
  title: '', uid: 'sitemonitor-overview', link_base_url: '', selector: 'namespace="site-monitor-prod"', time_from: 'now-24h',
  refresh: '1m', language: 'tr', sections: SECTIONS, per_monitor: true, default_team: '', top_n: 10, export_enabled: true,
  export_per_monitor: true, export_max_entities: 5000, export_refresh_seconds: 60,
}
const SUMMARY = {
  panels: 106, rows: 7, panels_by_section: { overview: 26, alerts: 13, monitors: 12, certificates: 15, notifications: 14, health: 16, runtime: 16 },
  variables: ['datasource', 'team', 'type', 'monitor', 'level', 'topn', 'job', 'instance'],
  metrics: ['sitemonitor_monitor_state', 'sitemonitor_alerts_open', 'sitemonitor_certificate_days_remaining_with_a_long_name_for_wrapping', 'up'],
  file_name: FILE_NAME, revision: 1, links: true, title: 'SiteMonitor — Uçtan uca izleme', uid: 'sitemonitor-overview',
}

function settingsData(values, readOnly) {
  return {
    values, app_base_url: 'https://sitemonitor.example.org', effective_link_base_url: 'https://sitemonitor.example.org', read_only: readOnly,
    options: {
      time_from: ['now-1h', 'now-6h', 'now-12h', 'now-24h', 'now-2d', 'now-7d', 'now-30d'], refresh: ['30s', '1m', '5m', '15m', '30m', '1h'],
      languages: ['tr', 'en'], sections: SECTIONS, top_n: { min: 3, max: 100, default: 10 },
      export_max_entities: { min: 100, max: 50000, default: 5000 }, export_refresh_seconds: { min: 30, max: 600, default: 60 },
    },
    teams: readOnly ? [] : [LONG_TEAM, 'Ödeme Sistemleri'],
    exporter: { state: 'running', enabled: true, token_configured: true, per_monitor: true, refresh_seconds: 60, max_entities: 5000,
      pod: 'site-monitor-7c9d6f8b5-xk2lq', server_now: new Date().toISOString().slice(0, 19),
      refreshed_at: new Date(Date.now() - 20_000).toISOString().slice(0, 19), duration_ms: 140, series_total: 9120,
      entities: { monitors: 640, certificates: 210, domains: 30, alerts: 12 },
      sources: [{ source: 'overview', up: true }, { source: 'alerts', up: true }], capped: [] },
    summary: SUMMARY, revision: 1, schema_version: 41, grafana_min_version: '12.0.0', app_version: '20.157.0', metrics_path: '/metrics/sitemonitor',
    runtime_metrics_path: '/metrics', token_env: 'METRICS_EXPORT_TOKEN',
    metrics: [
      { name: 'sitemonitor_monitor_state', scope: 'entity', labels: ['type', 'team', 'team_id', 'group', 'monitor', 'monitor_id', 'target'],
        help: 'Monitor state code: 0 up, 1 stale (check overdue), 2 down, 3 unknown (never checked), 4 paused' },
      { name: 'sitemonitor_alerts_open', scope: 'aggregate', labels: ['family', 'alert_type', 'team', 'team_id', 'ug_team', 'ug_team_id', 'level', 'acknowledged'],
        help: 'Open alerts by monitor family, alert type, owning teams, level and acknowledgement' },
      { name: 'sitemonitor_sweep_leader', scope: 'pod', labels: [], help: '1 when this pod holds the monitoring sweep leadership' },
    ],
  }
}

const json = (body, status = 200) => (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

/** mockApi'den SONRA kaydedilen yollar önceliklidir. Arayüz dili TR. */
async function routes(page, { readOnly = false } = {}) {
  await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yok */ } })
  await mockApi(page, { role: 'ADMIN', globalAdmin: !readOnly })
  const calls = { puts: [], previews: [], downloads: 0 }
  let values = { ...VALUES }
  await page.route((u) => new URL(u).pathname === '/api/admin/grafana/settings', (route) => {
    if (route.request().method() === 'PUT') {
      const body = JSON.parse(route.request().postData() || '{}')
      calls.puts.push(body)
      if (body.uid !== undefined && !/^[A-Za-z0-9_-]{1,40}$/.test(body.uid)) {
        return json({ success: false, code: 'VALIDATION_FAILED', field: 'uid', error: 'Pano kimliği (UID) yalnız harf, rakam, - ve _ içerebilir.' }, 400)(route)
      }
      values = { ...values, ...body }
      return json({ success: true, message: 'Grafana panosu ayarları kaydedildi (yeniden başlatma gerekmez).', data: settingsData(values, false) })(route)
    }
    return json({ success: true, data: settingsData(values, readOnly) })(route)
  })
  await page.route((u) => new URL(u).pathname === '/api/admin/grafana/dashboard/preview', (route) => {
    calls.previews.push(JSON.parse(route.request().postData() || '{}'))
    return json({ success: true, data: { ...SUMMARY, panels: 58, rows: 4, link_base_url: 'https://sitemonitor.example.org' } })(route)
  })
  await page.route((u) => new URL(u).pathname === '/api/admin/grafana/dashboard', (route) => {
    calls.downloads++
    return route.fulfill({ status: 200, contentType: 'application/json', body: FIXTURE,
      headers: { 'Content-Disposition': `attachment; filename="${FILE_NAME}"` } })
  })
  return calls
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, select, input:not([type="hidden"]), textarea, [role="combobox"]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.getAttribute('role') === 'checkbox' || b.getAttribute('role') === 'switch') continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none' || b.getAttribute('aria-hidden') === 'true') continue
    if (r.height < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || b.id || '').trim().slice(0, 30)}" ${Math.round(r.height)}`)
  }
  return out
}

function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('span, p, li, h3, h4, label, button, dt, dd, code')].filter((n) => !n.closest('[role="combobox"]'))
    .filter((n) => !n.closest('.sr-only') && !n.closest('pre'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 12
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')).map((n) => n.textContent.slice(0, 40))
}

function offenders(sel) {
  const vw = document.documentElement.clientWidth
  const root = document.querySelector(sel) || document.body
  const out = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    const cs = getComputedStyle(el)
    if (r.width <= 1 || r.height <= 1 || cs.visibility === 'hidden' || cs.display === 'none') continue
    if (r.right <= vw + 1 && r.left >= -1) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (!contained) out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || ''}] ${Math.round(r.left)}..${Math.round(r.right)}`)
    if (out.length >= 8) break
  }
  return out
}

const overflowX = () => document.documentElement.scrollWidth - document.documentElement.clientWidth
const ROOT = '[data-slot="grafana-settings"]'

function allPanels(d) {
  const out = []
  for (const p of d.panels || []) { out.push(p); for (const c of p.panels || []) out.push(c) }
  return out
}

for (const vp of VIEWPORTS) {
  test.describe(`Ayarlar → Grafana panosu — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('sığar, kırpılmaz, dokunma hedefleri; adımlar + aktarıcı + bölümler + ön izleme; katalog açılır', async ({ page }) => {
      test.setTimeout(120_000)
      await routes(page)
      await page.goto('/?tab=settings&sec=grafana')
      const root = page.locator(ROOT)
      await expect(root).toHaveAttribute('data-state', 'ok', { timeout: 20_000 })
      await expect(root.locator('[data-slot="grafana-step"]')).toHaveCount(3)
      await expect(root.locator('[data-slot="grafana-exporter"]')).toHaveAttribute('data-state', 'running')
      await expect(root.locator('[data-slot="grafana-section"]')).toHaveCount(7)
      await expect(root.locator('[data-slot="grafana-scrape"]')).toContainText('names: [site-monitor-prod]')
      // İş ölçümleri belirteçli ayrı uçta; belirteç yalnız EVET / HAYIR; takım panosu erişim denetimi değil
      await expect(root.locator('[data-slot="grafana-scrape"]')).toContainText('metrics_path: /metrics/sitemonitor')
      await expect(root.locator('[data-slot="grafana-scrape"]')).toContainText('credentials_file:')
      await expect(root.locator('[data-slot="grafana-token"]')).toHaveAttribute('data-state', 'configured')
      await expect(root.locator('[data-slot="grafana-team-scope"]')).toBeVisible()
      await page.screenshot({ path: `test-results/settings-grafana-${vp.name}-top.png`, fullPage: false })
      await root.locator('[data-slot="grafana-catalog-toggle"]').click()
      await expect(root.locator('[data-slot="grafana-catalog-item"]')).toHaveCount(3)
      await root.locator('[data-slot="grafana-preview-metrics-toggle"]').click()
      await expect(root.locator('[data-slot="grafana-preview-metrics"]')).toBeVisible()
      await page.waitForTimeout(300)

      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(offenders, ROOT), `@${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(await page.evaluate(clipped, ROOT), `@${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, ROOT), `@${vp.name}: küçük dokunma hedefi`).toEqual([])
      // Kazıma örneği kendi kabında kayar (sayfa kaymaz)
      const pre = root.locator('[data-slot="grafana-scrape"] pre')
      expect(await pre.evaluate((el) => getComputedStyle(el).overflowX)).toBe('auto')
      await page.screenshot({ path: `test-results/settings-grafana-${vp.name}.png`, fullPage: false })
    })

    test('JSON indir gerçek dosya indirir: içe aktarılabilir pano (DS_PROMETHEUS, takım / tür değişkenleri, 30+ panel, boş başlık yok)', async ({ page }) => {
      test.setTimeout(120_000)
      const calls = await routes(page)
      await page.goto('/?tab=settings&sec=grafana')
      const root = page.locator(ROOT)
      await expect(root).toHaveAttribute('data-state', 'ok', { timeout: 20_000 })
      const btn = root.locator('[data-slot="grafana-download"]')
      await btn.scrollIntoViewIfNeeded()
      if (vp.touch) expect((await btn.boundingBox()).height, `@${vp.name}: indirme düğmesi`).toBeGreaterThanOrEqual(39.5)
      const [download] = await Promise.all([page.waitForEvent('download'), btn.click()])
      expect(download.suggestedFilename()).toBe(FILE_NAME)
      const file = test.info().outputPath(`dashboard-${vp.name}.json`)
      await download.saveAs(file)
      const d = JSON.parse(readFileSync(file, 'utf8'))
      expect(d.__inputs[0].name).toBe('DS_PROMETHEUS')
      expect(d.__inputs[0].pluginId).toBe('prometheus')
      expect(d.id).toBeNull()
      const vars = d.templating.list.map((v) => v.name)
      expect(vars).toEqual(expect.arrayContaining(['datasource', 'team', 'type', 'monitor', 'level']))
      const panels = allPanels(d).filter((p) => p.type !== 'row')
      expect(panels.length).toBeGreaterThan(30)
      for (const p of allPanels(d)) expect(String(p.title || '').trim(), 'boş panel başlığı').not.toBe('')
      for (const p of panels.filter((x) => x.type !== 'text')) {
        expect(p.datasource?.uid).toBe('${datasource}')
        expect(p.targets.length).toBeGreaterThan(0)
      }
      expect(calls.downloads).toBe(1)
      expect(calls.puts).toHaveLength(0)   // temizken kayıt yok
    })

    test('alan hatası alanın altında; düzeltince yalnız değişen alan kaydedilir ve ön izleme istenir', async ({ page }) => {
      test.setTimeout(120_000)
      const calls = await routes(page)
      await page.goto('/?tab=settings&sec=grafana')
      const root = page.locator(ROOT)
      await expect(root).toHaveAttribute('data-state', 'ok', { timeout: 20_000 })
      const uid = root.locator('[data-slot="grafana-f-uid"]')
      await uid.scrollIntoViewIfNeeded()
      await uid.fill('kötü uid')
      const save = page.getByRole('button', { name: /^Kaydet$/ })
      await save.click()
      await expect(page.locator('[data-field="uid"]')).toContainText('Pano kimliği yalnız harf')
      expect(calls.puts).toHaveLength(0)
      await uid.fill('dk-panosu')
      await root.locator('[data-slot="grafana-section"][data-id="runtime"] [role="checkbox"]').click()
      // Ön izleme gecikmelidir: iki değişiklik arasında süre geçerse önce yalnız uid'li istek gidebilir — son istek beklenir
      await expect.poll(() => calls.previews.at(-1), { timeout: 5000 })
        .toEqual({ uid: 'dk-panosu', sections: SECTIONS.filter((s) => s !== 'runtime') })
      await expect(root.locator('[data-slot="grafana-preview-tile"][data-key="panels"]')).toContainText('58')
      await save.click()
      await expect.poll(() => calls.puts.length).toBe(1)
      expect(calls.puts[0]).toEqual({ uid: 'dk-panosu', sections: SECTIONS.filter((s) => s !== 'runtime') })
      await expect(page.locator('[data-slot="settings-save-bar"]')).not.toHaveAttribute('data-dirty', 'true')
      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma (hata sonrası)`).toBeLessThanOrEqual(1)
    })

    test('kapsamlı müdür: salt okunur bant, alanlar ve indirme kapalı, kayıt çubuğu yok', async ({ page }) => {
      test.setTimeout(120_000)
      await routes(page, { readOnly: true })
      await page.goto('/?tab=settings&sec=grafana')
      const root = page.locator(ROOT)
      await expect(root).toHaveAttribute('data-state', 'ok', { timeout: 20_000 })
      await expect(root.locator('[data-slot="grafana-readonly-banner"]')).toBeVisible()
      await expect(root.locator('[data-slot="grafana-download"]')).toBeDisabled()
      await expect(root.locator('[data-slot="grafana-f-uid"]')).toBeDisabled()
      await expect(page.locator('[data-slot="settings-save-bar"]')).toHaveCount(0)
      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma (salt okunur)`).toBeLessThanOrEqual(1)
    })
  })
}
