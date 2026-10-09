import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { CERTS, PERMS, mockCertApi } from './support/certMocks.js'

// 2026-10-09 (kullanıcı: "All certificates ekranında trust kolonuna filtreleme yapılırken any ve insecure only gibi
// seçenekler geliyor ama kolonda partly verified var; filtreleme hatalı. Ayrıca neden partly verified bilinmiyor"):
// Güven süzgeci sütunun değerlerini sunar (telefonda Süzgeçler çekmecesinde, masaüstünde süzgeç çubuğunda) ve sunucuya
// filter_trust gider; rozete dokununca üç denetimin durumu + sonuçlanmayanın nedeni açılır. Pencere ekrana sığar,
// sayfa yatay kaymaz (telefon 390, tablet 768, masaüstü 1280).
const iso = () => new Date().toISOString().slice(0, 19)
const base = { ...CERTS[0], alert_level: 'valid', days_remaining: 200, warning: false, status: 'valid', checked_at: iso() }
const ROWS = [
  { ...base, domain: 'tam.example.com', chain_status: 'VALID', trust_status: 'TRUSTED', revocation_status: 'GOOD' },
  { ...base, domain: 'kismen.example.com', chain_status: 'VALID', trust_status: 'TRUSTED', revocation_status: 'UNKNOWN',
    revocation_reason: 'NO_ENDPOINTS', ocsp_url: null, crl_url: null },
  { ...base, domain: 'sorun.example.com', chain_status: 'VALID', trust_status: 'UNTRUSTED', revocation_status: 'GOOD' },
]
const FACETS = { all: 3, levels: { valid: 3 }, windows: {}, teams: [], no_team: 3, insecure: 0, tiers: {}, nonstd_port: 0,
  trust: { ok: 1, partial: 1, unknown: 0, bad: 1, chain: 0, untrusted: 1, revoked: 0 } }
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 800 },
]

for (const vp of VIEWPORTS) {
  test(`güven süzgeci + neden açıklaması (${vp.name} ${vp.width})`, async ({ page }) => {
    test.setTimeout(120_000)
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page, { perms: PERMS })
    await mockCertApi(page)
    const trustParams = []
    await page.route((u) => new URL(u).pathname === '/api/certificates/list', async (route) => {
      const q = new URL(route.request().url()).searchParams
      trustParams.push(q.get('filter_trust'))
      const data = q.get('filter_trust') === 'partial' ? [ROWS[1]] : ROWS
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        success: true, data, facets: FACETS, shared: {}, scope: 'mine', visible_to_all: false,
        pagination: { current_page: 1, per_page: 25, total: data.length, total_pages: 1 }, timestamp: iso(),
      }) })
    })
    await page.goto('/?tab=all')
    await expect(page.locator('[data-domain="kismen.example.com"]').first()).toBeVisible({ timeout: 20_000 })

    // 1) Süzgeç: sütunun değerleri (telefonda Süzgeçler çekmecesinde)
    const trigger = page.locator('[data-slot="ct-filters-trigger"]')
    const phone = await trigger.isVisible()
    if (phone) await trigger.click()
    const select = page.getByRole('combobox', { name: /^(Trust|Güven)$/ })
    await select.click()
    const options = page.getByRole('option')
    await expect(options.filter({ hasText: /Partly verified \(1\)|Kısmen doğrulandı \(1\)/ })).toBeVisible()
    await expect(options.filter({ hasText: /Insecure only|Yalnız güvensiz/ })).toHaveCount(0)
    await options.filter({ hasText: /Partly verified \(1\)|Kısmen doğrulandı \(1\)/ }).click()
    if (phone) await page.getByRole('button', { name: /^(Close|Kapat)$/ }).last().click()
    await expect.poll(() => trustParams.at(-1)).toBe('partial')
    await expect(page.locator('[data-domain="sorun.example.com"]')).toHaveCount(0)
    await expect(page.locator('[data-filter-chip="trust"]')).toBeVisible()

    // 2) Neden: rozet → üç denetim + iptal satırında sunucunun nedeni; pencere ekrana sığar
    await page.getByRole('button', { name: /(Partly verified|Kısmen doğrulandı) — / }).first().click()
    const detail = page.locator('[data-slot="cert-trust-detail"]')
    await expect(detail).toBeVisible()
    await expect(detail.locator('[data-slot="trust-check"]')).toHaveCount(3)
    await expect(detail.locator('[data-slot="trust-check"][data-check="rev"]')).toHaveAttribute('data-state', 'unknown')
    await expect(detail.locator('[data-slot="trust-check"][data-check="rev"] [data-slot="trust-check-hint"]')).toContainText(/OCSP|CRL/)
    const box = await detail.boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(0)
    expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
    expect(box.y + box.height).toBeLessThanOrEqual(vp.height + 1)
    // Pencere açılınca sertifika penceresi açılmadı (satır tıklaması tetiklenmedi)
    await expect(page.getByRole('dialog', { name: /kismen\.example\.com/ })).toHaveCount(0)
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
    expect(overflow).toBeLessThanOrEqual(1)
    // Dokunma hedefleri: rozet tetiği ve "Sağlık denetimini aç" telefonda en az 40 px
    if (vp.width < 768) {
      const btn = await detail.locator('[data-slot="trust-open-health"]').boundingBox()
      expect(btn.height).toBeGreaterThanOrEqual(39)
      const trig = await page.locator('[data-slot="cert-trust-trigger"]').first().boundingBox()
      expect(trig.height).toBeGreaterThanOrEqual(39)
    }
  })
}
