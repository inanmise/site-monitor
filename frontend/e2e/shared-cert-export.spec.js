import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { unzipSync, strFromU8 } from 'fflate'
import { mockApi } from './support/monitorMocks.js'
import { CERT_DOMAINS, PERMS, mockCertApi } from './support/certMocks.js'

// 2026-10-08 (kullanıcı: "kartta 'x alan adında ortak SAN' yazıyor; tıklayınca açılan pencerede takım bazlı, grup bazlı
// görebilmeliyim; PDF ve Excel olarak dışa alabilmeliyim"). GERÇEK tarayıcıda: zengin kart çipi → pencere → Takıma göre →
// Excel ve PDF indirilir; dosyalar açılıp içerik doğrulanır (jsdom testleri dışa aktarma modülünü taklit ediyor).
const DOMAIN = CERT_DOMAINS.healthy
const iso = (ms = 0) => new Date(Date.now() - ms).toISOString().slice(0, 19)
const SHARED = {
  domain: DOMAIN, fingerprint: 'AB12CD34EF56', subject: 'CN=*.example.com', issuer: 'Example TLS RSA CA 2026',
  not_after: iso(-120 * 86400000), days_remaining: 120, san: [DOMAIN, 'api.example.com', 'shop.example.com'], hidden: 1,
  peers: [
    { domain: DOMAIN, self: true, days_remaining: 120, not_after: iso(-120 * 86400000), checked_at: iso(3600000), team_id: 2, team_name: 'Takım B', group_name: 'Ödeme', platform: 'IIS', tier: 1, port: 443, in_inventory: true },
    { domain: 'api.example.com', days_remaining: 120, not_after: iso(-120 * 86400000), checked_at: iso(3600000), team_id: 1, team_name: 'Takım A', group_name: 'Çekirdek', platform: 'OPENSHIFT', platform_detail: 'ocp-prod', tier: 2, port: 8443, in_inventory: true },
    { domain: 'shop.example.com', days_remaining: 120, not_after: iso(-120 * 86400000), checked_at: iso(3600000), team_id: null, team_name: null, group_name: null, in_inventory: false },
  ],
}
const VIEWPORTS = [
  { name: 'desktop', width: 1440, height: 900 },
  { name: 'phone', width: 390, height: 844 },
]

for (const vp of VIEWPORTS) {
  test(`paylaşılan sertifika penceresi (${vp.name}): takıma göre görünüm + gerçek Excel ve PDF dosyası`, async ({ page }) => {
    test.setTimeout(120_000)
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page, { perms: PERMS })
    await mockCertApi(page)
    await page.route('**/api/certificates/card-extras', (route) => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ success: true, data: { [DOMAIN]: { shared: { count: 2, domains: ['api.example.com', 'shop.example.com'], san_count: 3 } } } }),
    }))
    await page.route('**/api/certificates/shared?*', (route) => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: SHARED }),
    }))
    await page.goto('/?tab=dashboard')
    // Zengin kart görünümü (çip yalnız orada)
    await page.getByRole('radio', { name: /^(Rich|Zengin)$/ }).or(page.getByRole('button', { name: /^(Rich|Zengin)$/ })).first().click({ timeout: 20_000 })
    const card = page.locator(`[data-slot="card"][data-domain="${DOMAIN}"]`)
    await card.getByRole('button', { name: /(2 alan adında ortak|Shared across 2 domains)/ }).first().click({ timeout: 20_000 })
    const dialog = page.getByRole('dialog')
    await expect(dialog.getByTestId('shc-table')).toBeVisible()

    await dialog.getByRole('button', { name: /^(By team|Takıma göre)$/ }).click()
    const sections = dialog.locator('[data-slot="shc-section"]')
    await expect(sections).toHaveCount(3)
    await expect(sections.last()).toHaveAttribute('data-section', 'none')
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(overflow, 'sayfa yatay taşmaz').toBeLessThanOrEqual(1)
    await dialog.screenshot({ path: `test-results/shared-cert-${vp.name}.png` })

    // Excel — gerçek .xlsx: paket açılır, takım sırası (A → B → takımsız) ve sertifika sayfası doğrulanır
    const [xl] = await Promise.all([page.waitForEvent('download'), dialog.getByRole('button', { name: /(Download Excel|Excel indir)/ }).click()])
    expect(xl.suggestedFilename()).toMatch(/\.xlsx$/)
    await xl.saveAs(`test-results/shared-cert-${vp.name}.xlsx`)
    const files = unzipSync(new Uint8Array(readFileSync(await xl.path())))
    const s1 = strFromU8(files['xl/worksheets/sheet1.xml'])
    const order = ['api.example.com:8443', DOMAIN, 'shop.example.com'].map((d) => s1.indexOf(d))
    expect(order.every((i) => i > 0)).toBe(true)
    expect(order).toEqual([...order].sort((a, b) => a - b))
    expect(strFromU8(files['xl/worksheets/sheet2.xml'])).toContain('AB12CD34EF56')

    // PDF — gerçek dosya (%PDF imzası, Türkçe yazı tipi gömülü → makul boyut)
    const [pdf] = await Promise.all([page.waitForEvent('download'), dialog.getByRole('button', { name: /(Download PDF|PDF indir)/ }).click()])
    expect(pdf.suggestedFilename()).toMatch(/\.pdf$/)
    await pdf.saveAs(`test-results/shared-cert-${vp.name}.pdf`)
    const buf = readFileSync(await pdf.path())
    expect(buf.subarray(0, 4).toString()).toBe('%PDF')
    expect(buf.length).toBeGreaterThan(20_000)
  })
}
