import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { CERTS, PERMS, mockCertApi } from './support/certMocks.js'

// 2026-10-08 (kullanıcı: "aktif olmayan sertifikalar kartlarda gösterilmiyor … filtreleyebilmeli" + "kartın pasif olduğunu
// kart görünümünden anlamamız lazım"): Genel Bakış'ta pasif (izlemesi durdurulmuş) kartlar aktiflerin ARDINDAN gelir ve
// görünümden ayrılır — "Pasif" rozeti (duraklat simgesi), kesikli kenar, rozetin altında "İzleme durduruldu" şeridi, gri
// kahraman, "Şimdi kontrol et" yok. İzleme süzgeci (Tümü / Aktif / Pasif). Masaüstü + telefon: taşma yok.
const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const inDays = (d) => new Date(Date.now() + d * 86400000).toISOString().slice(0, 19)
const PAUSED = [
  { ...CERTS[0], domain: 'arsiv.example.com', days_remaining: -12, not_after: inDays(-12), alert_level: 'expired', warning: true,
    checked_at: iso(40 * 86400000), paused: true, noc_notify: false },
  { domain: 'eski-portal.example.com', port: 443, team_id: 1, team_name: 'Takım A', tier: 3, paused: true },
]
const VIEWPORTS = [
  { name: 'desktop', width: 1440, height: 900 },
  { name: 'phone', width: 390, height: 844 },
]

for (const vp of VIEWPORTS) {
  test(`pasif kartlar (${vp.name} ${vp.width}): sonda, görünümden ayrılır, süzülür, taşmaz`, async ({ page }) => {
    test.setTimeout(120_000)
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page, { perms: PERMS })
    await mockCertApi(page)
    await page.route('**/api/certificates/paused', (route) => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: PAUSED, timestamp: iso(0) }),
    }))
    await page.goto('/?tab=dashboard')
    const grid = page.locator('[data-slot="cert-grid"]')
    const paused = grid.locator('[data-slot="card"][data-paused="true"]')
    await expect(paused).toHaveCount(2, { timeout: 20_000 })

    // Sıra: tüm aktif kartlar önce, pasifler sonda
    const order = await grid.locator('[data-slot="card"]').evaluateAll((els) => els.map((e) => e.getAttribute('data-paused') === 'true'))
    expect(order.indexOf(true)).toBe(order.length - 2)

    // Görünüm: rozet + şerit + kesikli kenar görünür; Şimdi kontrol et yok
    const first = paused.first()
    await first.scrollIntoViewIfNeeded()
    await expect(first.locator('[data-slot="cert-status"][data-status="paused"]')).toBeVisible()
    await expect(first.locator('[data-slot="cert-paused-note"]')).toBeVisible()
    const dashed = await first.evaluate((el) => getComputedStyle(el).borderTopStyle)
    expect(dashed).toBe('dashed')
    await expect(first.getByRole('button', { name: /check now|şimdi kontrol et/i })).toHaveCount(0)
    await first.screenshot({ path: `test-results/paused-card-${vp.name}.png` })

    // Taşma yok
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(overflow, 'yatay taşma').toBeLessThanOrEqual(1)
    const cardBox = await first.boundingBox()
    expect(cardBox.x + cardBox.width).toBeLessThanOrEqual(vp.width + 1)

    // Süzgeç: Pasif → yalnız pasif kartlar (telefonda "Süzgeçler" çekmecesindeki yerel seçici)
    if (vp.width >= 768) {
      await page.getByRole('button', { name: /^(Monitoring|İzleme): / }).click()
      await page.getByRole('option', { name: /^(Paused|Pasif) \(2\)/ }).click()
    } else {
      await page.locator('[data-slot="dashboard-filters-trigger"]').click()
      const sheet = page.locator('[data-slot="dashboard-filter-sheet"]')
      await sheet.getByLabel(/^(Monitoring|İzleme)$/).selectOption('paused')
      await sheet.getByRole('button', { name: /^(Apply|Uygula)/ }).click()
    }
    await expect(grid.locator('[data-slot="card"]')).toHaveCount(2)
    await expect(grid.locator('[data-slot="card"]:not([data-paused="true"])')).toHaveCount(0)
    await page.screenshot({ path: `test-results/paused-dashboard-${vp.name}.png`, fullPage: false })
  })
}
