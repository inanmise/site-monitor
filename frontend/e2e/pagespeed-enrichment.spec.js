// Sayfa Hızı zenginleştirme 1. sürüm (2026-11-14): kartta "Bütçe uyumu" çipi, ayrıntıda süre tavanı uyarısı ("≥"),
// "Kırılım" sekmesi (bağlantı aşamaları, tür kırılımı, türlerin zaman içindeki değişimi) ve kaynaklarda sunucu süzgeci +
// sayfalama + "CSV (tümü)". Telefon / tablet (dokunmatik) / dizüstünde ekrana sığar, yatay kayma yok, yeni denetimler
// dokunmatikte ≥ 40 px. jsdom yerleşim hesaplamaz → gerçek tarayıcı.
import { test, expect } from '@playwright/test'
import { MONITORS, mockApi } from './support/monitorMocks.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 800 },
]
const json = (body) => (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
const iso = (agoMs) => new Date(Date.now() - agoMs).toISOString().slice(0, 19)
const LONG_NAME = 'Kurumsal İnternet Bankacılığı Ödeme Onay Sayfası (Bireysel ve Ticari Müşteriler — Canlı Ortam)'
const LONG_URL = 'https://cok-uzun-bir-alt-alan-adi.odeme-servisleri.example.com/statik/paketler/vendor.app.min.js?v=20261114'

const ROW = {
  ...MONITORS.pagespeed[0], id: 100, name: LONG_NAME, active: true, status: 'SLOW', ok: true,
  response_ms: 12_400, ttfb_ms: 380, total_bytes: 3_250_000, request_count: 96, html_ms: 910,
  breached_metrics: ['LOAD', 'IMG'], max_load_ms: 5000, max_img_kb: 1500, time_truncated: true,
  failed_count: 2, skipped_lazy: 11, dns_ms: 24, connect_ms: 31, tls_ms: 58, server_ms: 260,
  html_bytes: 84_000, js_bytes: 920_000, js_count: 31, css_bytes: 140_000, css_count: 9, img_bytes: 1_880_000, img_count: 44,
  font_bytes: 160_000, font_count: 6, other_bytes: 66_000, other_count: 5, first_party_bytes: 2_100_000, third_party_bytes: 1_150_000,
  last_check: iso(60_000), checked_at: iso(60_000),
}

const RESOURCES = { success: true, data: {
  resources: [
    { url: LONG_URL, type: 'JS', bytes: 640_000, duration_ms: 820, http_status: 200, third_party: false, truncated: false, failed: false, checked_at: iso(60_000) },
    { url: 'https://cdn.example.net/gorseller/kampanya-banner-buyuk.png', type: 'IMG', bytes: 1_200_000, duration_ms: 2400, http_status: 200, third_party: true, truncated: false, failed: false, checked_at: iso(60_000) },
    { url: 'https://cdn.example.net/eksik.woff2', type: 'FONT', bytes: 0, duration_ms: 90, http_status: 404, third_party: true, truncated: false, failed: true, checked_at: iso(60_000) },
  ],
  total: 96, filtered_total: 96, page: 0, size: 50,
  facets: { type: { js: 31, css: 9, img: 44, font: 6, other: 5 }, party: { first: 60, third: 36 }, failed: 2 },
  breaches: [],
} }

const HOUR = 3_600_000
const TYPE_SERIES = { success: true, data: {
  points: [0, 1, 2, 3, 4, 5].map((i) => ({
    ts: iso((6 - i) * HOUR), n: i === 2 ? 0 : 1,
    html: i === 2 ? null : 80_000 + i * 1000, js: i === 2 ? null : 900_000 + i * 5000, css: i === 2 ? null : 140_000,
    img: i === 2 ? null : 1_700_000 + i * 30_000, font: i === 2 ? null : 160_000, other: i === 2 ? null : 60_000,
  })),
  bucket_len: 13, truncated: false,
} }

const SLA = { success: true, target_pct: 99.9, days: 30,
  data: { 100: { n: 288, fail: 0, up_pct: 100, bad_hours: [], breach: 41, budget_pct: 85.76 } } }

// Pencerede ekran dışına taşan öğe + yatay kayma (page-integrity-detail.spec ile aynı ölçüm).
const measure = (sel) => {
  const vw = document.documentElement.clientWidth
  const root = (sel && document.querySelector(sel)) || document.body
  const pageOverflow = Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth,
    document.body.scrollWidth - document.body.clientWidth)
  const clips = (el) => getComputedStyle(el).overflowX !== 'visible'
  const hidden = (el, r) => {
    if (r.width <= 1 || r.height <= 1) return true
    const cs = getComputedStyle(el)
    return cs.visibility === 'hidden' || cs.display === 'none' || Number(cs.opacity) === 0
  }
  const offenders = []
  const flagged = new Set()
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.right <= vw + 1 || hidden(el, r)) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (clips(p) && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (contained) continue
    flagged.add(el)
    if (el.parentElement && flagged.has(el.parentElement)) continue
    const slot = el.getAttribute('data-slot')
    offenders.push(`${el.tagName.toLowerCase()}${slot ? `[data-slot=${slot}]` : ''} r=${Math.round(r.right)}`)
    if (offenders.length >= 8) break
  }
  return { vw, pageOverflow, offenders }
}

const targetHeight = (loc) => loc.evaluate((el) => {
  const r = el.getBoundingClientRect()
  const af = getComputedStyle(el, '::after')
  if (af.position === 'absolute' && af.content !== 'none') {
    const h = r.height - (parseFloat(af.top) || 0) - (parseFloat(af.bottom) || 0)
    return Math.max(r.height, h)
  }
  return r.height
})

for (const vp of VIEWPORTS) {
  test.describe(`sayfa hızı zenginleştirme @${vp.name}`, () => {
    const touch = vp.width < 1024
    test.use({ hasTouch: touch, isMobile: touch })

    test(`bütçe uyumu, kısmi ölçüm, kırılım ve kaynak süzgeçleri ekrana sığar @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
      test.setTimeout(120_000)
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mockApi(page)
      const resourceCalls = []
      await page.route((u) => new URL(u).pathname === '/api/monitoring/pagespeed', json({ success: true, data: [ROW] }))
      await page.route((u) => new URL(u).pathname === '/api/monitoring/sla', json(SLA))
      await page.route((u) => /^\/api\/monitoring\/pagespeed\/\d+\/resources$/.test(new URL(u).pathname), (r) => {
        resourceCalls.push(new URL(r.request().url()).searchParams.toString())
        return json(RESOURCES)(r)
      })
      await page.route((u) => /^\/api\/monitoring\/pagespeed\/\d+\/type-series$/.test(new URL(u).pathname), json(TYPE_SERIES))
      await page.goto('/?tab=pagespeed')

      // Kart: "Bütçe uyumu" çipi (kullanılabilirlikten AYRI) — kartın içinde, sarılır
      const chip = page.locator('[data-slot="pspd-budget-compliance"]').first()
      await chip.waitFor({ timeout: 20_000 })
      await expect(chip).toHaveAttribute('data-tone', 'crit')
      const cardBox = await page.locator('.upt-grid [data-slot="card"]').first().boundingBox()
      const chipBox = await chip.boundingBox()
      expect(chipBox.x + chipBox.width, `@${vp.name}: bütçe çipi kartın dışına taşıyor`).toBeLessThanOrEqual(cardBox.x + cardBox.width + 1)

      // Ayrıntı: süre tavanı uyarısı + "≥" alt sınır
      await page.locator('.upt-grid [data-slot="card"] [data-monitor-open]').first().click({ timeout: 20_000 })
      const dialog = page.getByRole('dialog').first()
      await dialog.waitFor()
      await expect(dialog.locator('[data-slot="pspd-time-truncated"]')).toBeVisible()
      await expect(dialog).toContainText('≥')

      // Kaynaklar (varsayılan sekme): sunucu süzgeçleri, sayılar, alınamayan rozeti, "CSV (tümü)"
      const filters = dialog.locator('[data-slot="pspd-res-filters"]')
      await expect(filters).toBeVisible()
      await expect(dialog).toContainText(LONG_URL)
      const failedBox = dialog.locator('[data-slot="pspd-res-failed"]')
      await failedBox.scrollIntoViewIfNeeded()
      if (touch) await failedBox.tap(); else await failedBox.click()
      await expect.poll(() => resourceCalls.some((q) => /failed=true/.test(q)), { timeout: 10_000 }).toBe(true)
      const clear = dialog.locator('[data-slot="pspd-res-clear"]')
      await expect(clear).toBeVisible()
      const csv = dialog.locator('[data-slot="pspd-res-csv"]')
      await expect(csv).toBeEnabled()
      if (touch) {
        for (const [name, loc] of [['CSV (tümü)', csv], ['süzgeç temizle', clear], ['alınamayan onay kutusu', failedBox.locator('xpath=..')]]) {
          expect(await targetHeight(loc), `@${vp.name}: "${name}" dokunma hedefi`).toBeGreaterThanOrEqual(39.5)
        }
      }
      await page.waitForTimeout(300)
      let m = await page.evaluate(measure, '[role="dialog"]')
      expect(m.offenders, `@${vp.name}: kaynaklar sekmesinde ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `@${vp.name}: sayfa yatay kayıyor (px)`).toBeLessThanOrEqual(1)

      // Kırılım sekmesi: aşamalar, tür kırılımı (6 satır, birinci / üçüncü taraf), türlerin zaman içindeki değişimi
      const tab = dialog.getByRole('tab', { name: /Kırılım|Breakdown/ })
      if (touch) await tab.tap(); else await tab.click()
      await expect(dialog.locator('[data-slot="pspd-phases"]')).toHaveAttribute('data-state', 'ready')
      await expect(dialog.locator('[data-slot="pspd-phase"]')).toHaveCount(5)
      await expect(dialog.locator('[data-slot="pspd-type-row"]')).toHaveCount(6)
      await expect(dialog.locator('[data-slot="pspd-party"]')).toBeVisible()
      const chart = dialog.locator('[data-slot="pspd-type-chart"]')
      await expect(chart).toHaveAttribute('data-state', 'ready', { timeout: 20_000 })
      await expect(dialog.locator('[data-slot="pspd-type-chart-gap-note"]')).toBeVisible()
      if (touch) {
        const items = dialog.locator('[data-slot="pspd-type-series"] [data-slot="toggle-group-item"]')
        await expect(items).toHaveCount(3)
        for (let i = 0; i < 3; i++) {
          expect(await targetHeight(items.nth(i)), `@${vp.name}: aralık düğmesi ${i + 1} dokunma hedefi`).toBeGreaterThanOrEqual(39.5)
        }
      }
      await page.waitForTimeout(400)
      m = await page.evaluate(measure, '[role="dialog"]')
      expect(m.offenders, `@${vp.name}: kırılım sekmesinde ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `@${vp.name}: sayfa yatay kayıyor (px)`).toBeLessThanOrEqual(1)
      const cb = await chart.boundingBox()
      const db = await dialog.boundingBox()
      expect(cb.x + cb.width, `@${vp.name}: tür grafiği pencereden taşıyor`).toBeLessThanOrEqual(db.x + db.width + 1)
    })
  })
}
