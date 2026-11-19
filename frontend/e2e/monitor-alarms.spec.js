// İzleme sayfasının "İzlemeler | Alarmlar" görünümü (2026-11-19, kullanıcı isteği) — gerçek tarayıcıda.
//
// HTTP sayfasında Alarmlar görünümüne geçilir: URL `mal=open`, açık alarmların son durumu (sunucu özeti), son 7 günde en
// çok alarm üreten izlemeler şeridi (uzun ad sarar), gömülü Alarm Geçmişi listesi YALNIZ sayfanın alarm tipleriyle
// (ACCESSIBILITY yok) ve satırda izleme adı. Şeritte bir izleme seçilince liste o alan adına süzülür (istek `domain`
// taşır); "İzlemeyi aç" tam sayfa yüklemeden İzlemeler görünümüne döner ve izlemenin ayrıntısını açar. Erişilebilirlik
// sayfası yalnız ACCESSIBILITY ister; Sertifika Panosu'nda görünüm sertifika tipleriyle çalışır ve pano içeriği gizlenir.
// Telefon (390, dokunmatik), tablet (768, dokunmatik), dizüstü (1280): taşma yok, metin kesilmez, dokunma hedefleri ≥ 40 px.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { GATE_VIEWPORTS, expectLayout, pageOverflow, json } from './support/gateChecks.js'

const ago = (ms) => new Date(Date.now() - ms).toISOString().slice(0, 19)
const LONG_NAME = 'Ödeme servisleri yedek bölge 2 — kurumsal ödeme giriş uç noktası (Kurumsal ve Bireysel Bankacılık Ağ Operasyonları / Takım A)'

const HTTP_ROWS = [
  { id: 701, domain: 'https://www.example.com/', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', resolved: false, acknowledged: false,
    created_at: ago(30 * 60_000), team_id: 1, team_name: 'Takım A', message: 'HTTP 503 Service Unavailable', act_scope: true, notify_scope: true,
    monitor_ref: { name: LONG_NAME, type: 'http', tab: 'http', monitor_id: 100 } },
  { id: 702, domain: 'https://api.example.com/', alert_type: 'HTTP_SLOW', alert_level: 'WARNING', resolved: false, acknowledged: false,
    created_at: ago(90 * 60_000), team_id: 1, team_name: 'Takım A', message: 'Yanıt süresi 4200 ms (eşik 2000 ms)', act_scope: true, notify_scope: true,
    monitor_ref: { name: 'api.example.com', type: 'http', tab: 'http', monitor_id: 101 } },
]
const UPTIME_ROWS = [
  { id: 801, domain: 'intranet.example.com', alert_type: 'ACCESSIBILITY', alert_level: 'HIGH', resolved: false, acknowledged: false,
    created_at: ago(20 * 60_000), team_id: 1, team_name: 'Takım A', message: 'Erişilemiyor', act_scope: true, notify_scope: true },
]
const CERT_ROWS = [
  { id: 901, domain: 'www.example.com', alert_type: 'EXPIRY', alert_level: 'HIGH', resolved: false, acknowledged: false,
    created_at: ago(5 * 3_600_000), team_id: 1, team_name: 'Takım A', message: 'Sertifikanın süresi 9 gün içinde doluyor', act_scope: true, notify_scope: true },
]

function summaryFor(types) {
  if (types.includes('ACCESSIBILITY')) {
    return { open: { total: 1, level_counts: { HIGH: 1 }, unacked: 1, stale: 0, stale_hours: 24 },
      top: { days: 7, items: [{ domain: 'intranet.example.com', count: 3, open: 1, last_alert_id: 801, last_alert_type: 'ACCESSIBILITY' }] } }
  }
  if (types.includes('EXPIRY')) {
    return { open: { total: 1, level_counts: { HIGH: 1 }, unacked: 1, stale: 1, stale_hours: 24 },
      top: { days: 7, items: [{ domain: 'www.example.com', count: 2, open: 1, last_alert_id: 901, last_alert_type: 'EXPIRY' }] } }
  }
  return { open: { total: 2, level_counts: { CRITICAL: 1, WARNING: 1 }, unacked: 2, stale: 0, stale_hours: 24 },
    top: { days: 7, items: [
      { domain: 'https://www.example.com/', count: 12, open: 1, last_alert_id: 701, last_alert_type: 'HTTP_DOWN',
        monitor: { name: LONG_NAME, type: 'http', tab: 'http', monitor_id: 100 } },
      { domain: 'https://api.example.com/', count: 4, open: 1, last_alert_id: 702, last_alert_type: 'HTTP_SLOW',
        monitor: { name: 'api.example.com', type: 'http', tab: 'http', monitor_id: 101 } },
    ] } }
}

async function mockAlarms(page) {
  const seen = { list: [], summary: [] }
  await mockApi(page, { role: 'ADMIN', globalAdmin: true, teamIds: [1] })
  await page.route((u) => new URL(u).pathname.startsWith('/api/admin/alerts'), async (route) => {
    const url = new URL(route.request().url())
    const p = url.pathname
    if (p === '/api/admin/alerts/type-summary') {
      const types = (url.searchParams.get('alertTypes') || '').split(',').filter(Boolean)
      seen.summary.push(types)
      return route.fulfill(json({ success: true, data: { alert_types: types, ...summaryFor(types) } }))
    }
    if (p === '/api/admin/alerts' && route.request().method() === 'GET') {
      const types = (url.searchParams.get('alertTypes') || '').split(',').filter(Boolean)
      const domain = url.searchParams.get('domain')
      seen.list.push({ types, domain })
      const all = [...HTTP_ROWS, ...UPTIME_ROWS, ...CERT_ROWS]
      const list = all.filter((r) => (types.length === 0 || types.includes(r.alert_type)) && (!domain || r.domain === domain))
      return route.fulfill(json({ success: true, data: list, total: list.length,
        type_counts: Object.fromEntries(list.map((r) => [r.alert_type, list.filter((x) => x.alert_type === r.alert_type).length])),
        level_counts: {}, unacked_total: list.length, stale_total: 0, stale_hours: 24, can_act: true, noc_can_write: false }))
    }
    return route.fallback()
  })
  return seen
}

const mal = (page) => new URL(page.url()).searchParams.get('mal')

for (const vp of GATE_VIEWPORTS) {
  test.describe(`izleme Alarmlar görünümü — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('HTTP: anahtar → özet + şerit + sayfanın tipleriyle liste; şerit süzgeci; "İzlemeyi aç" sayfa içinde', async ({ page }) => {
      test.setTimeout(150_000)
      const seen = await mockAlarms(page)
      await page.goto('/?tab=http')
      const sw = page.locator('[data-slot="monitor-view-switch"]')
      await expect(sw).toBeVisible({ timeout: 60_000 })
      await sw.getByRole('button', { name: 'Alarms' }).click()

      const view = page.locator('[data-slot="monitor-alarms"][data-src="http"]')
      await expect(view).toBeVisible({ timeout: 30_000 })
      await expect(page.locator('[data-slot="mal-tile"][data-key="open"]')).toContainText('2')
      await expect(page.locator('[data-slot="mal-tile"][data-key="critical"]')).toContainText('1')
      await expect.poll(() => mal(page)).toBe('open')
      expect(seen.summary.at(-1)).toContain('HTTP_DOWN')
      expect(seen.summary.at(-1)).not.toContain('ACCESSIBILITY')
      await expect.poll(() => seen.list.length).toBeGreaterThan(0)
      expect(seen.list.at(-1).types).not.toContain('ACCESSIBILITY')
      await expect(page.locator('[data-slot="mal-open-history"]')).toHaveAttribute('href', '?tab=alerthistory&view=open&src=http')

      // satırdaki izleme adı + şeritteki uzun ad sarar
      await expect(page.locator('[data-alert-card][data-alert-id="701"] [data-slot="alert-monitor-name"]')).toContainText(LONG_NAME)
      const topName = page.locator('[data-slot="mal-top-item"]').first().locator('[data-slot="mal-top-name"]')
      await expect(topName).toHaveText(LONG_NAME)
      if (vp.width <= 768) expect((await topName.boundingBox()).height).toBeGreaterThan(24)   // birden çok satır
      await expectLayout(page, '[data-slot="monitor-alarms"]', { touch: vp.touch, label: `http alarmlar @${vp.name}` })
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)

      // şeritten süz → liste isteği domain taşır
      await page.locator('[data-slot="mal-top-item"]').nth(1).getByRole('button', { name: /Show its alarms/ }).click()
      await expect.poll(() => seen.list.at(-1)?.domain).toBe('https://api.example.com/')
      await expect(page.locator('[data-slot="mal-focus"]')).toContainText('api.example.com')
      await expect(page.locator('[data-alert-card][data-alert-id="701"]')).toHaveCount(0)
      await page.locator('[data-slot="mal-focus-clear"]').click()
      await expect.poll(() => seen.list.at(-1)?.domain ?? null).toBeNull()

      // "İzlemeyi aç": tam sayfa yüklemesi yok → İzlemeler görünümü + izleme ayrıntısı
      await page.evaluate(() => { window.__noReload = true })
      await page.locator('[data-slot="mal-top-item"]').first().locator('[data-slot="mal-top-open-monitor"]').click()
      await expect(page.locator('[data-slot="monitor-alarms"]')).toHaveCount(0)
      await expect(page.locator('[data-slot="dialog-content"]').first()).toBeVisible({ timeout: 20_000 })
      expect(await page.evaluate(() => window.__noReload === true)).toBe(true)
      await expect.poll(() => mal(page)).toBeNull()
    })

    test('Erişilebilirlik yalnız ACCESSIBILITY; Sertifika Panosu sertifika tipleriyle, pano içeriği gizlenir', async ({ page }) => {
      test.setTimeout(150_000)
      const seen = await mockAlarms(page)
      await page.goto('/?tab=uptime&mal=open')
      const view = page.locator('[data-slot="monitor-alarms"][data-src="uptime"]')
      await expect(view).toBeVisible({ timeout: 60_000 })
      await expect(page.locator('[data-slot="monitor-view-switch"]').getByRole('button', { name: 'Sites' })).toBeVisible()
      await expect.poll(() => seen.summary.at(-1)?.join(',')).toBe('ACCESSIBILITY')
      await expect.poll(() => seen.list.at(-1)?.types.join(',')).toBe('ACCESSIBILITY')
      await expect(page.locator('[data-alert-card][data-alert-id="801"]')).toBeVisible()
      await expectLayout(page, '[data-slot="monitor-alarms"]', { touch: vp.touch, label: `uptime alarmlar @${vp.name}` })

      await page.goto('/?tab=dashboard')
      const sw = page.locator('[data-slot="monitor-view-switch"]')
      await expect(sw.getByRole('button', { name: 'Certificates' })).toBeVisible({ timeout: 60_000 })
      await sw.getByRole('button', { name: 'Alarms' }).click()
      await expect(page.locator('[data-slot="monitor-alarms"][data-src="cert"]')).toBeVisible({ timeout: 30_000 })
      await expect(page.locator('.stats-section')).toHaveCount(0)
      await expect.poll(() => seen.summary.at(-1)).toContain('EXPIRY')
      await expect(page.locator('[data-alert-card][data-alert-id="901"]')).toBeVisible()
      await expectLayout(page, '[data-slot="monitor-alarms"]', { touch: vp.touch, label: `sertifika alarmlar @${vp.name}` })
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
      await sw.getByRole('button', { name: 'Certificates' }).click()
      await expect(page.locator('.stats-section')).toBeVisible({ timeout: 20_000 })
      await expect.poll(() => mal(page)).toBeNull()
    })
  })
}
