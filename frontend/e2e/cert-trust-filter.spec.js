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
    // Tanı: rozet penceresi beklenmedik kapanırsa sayfa olayları (yeniden yükleme, sayfa hatası) ve odak hata iletisine yazılır
    const events = []
    page.on('framenavigated', (f) => { if (f === page.mainFrame()) events.push(`nav ${new URL(f.url()).search}`) })
    page.on('pageerror', (e) => events.push(`pageerror ${String(e?.message).slice(0, 200)}`))
    page.on('console', (m) => {
      if (m.type() === 'error' || /\[vite\]/.test(m.text())) events.push(`console.${m.type()} ${m.text().slice(0, 200)}`)
    })
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
    // Açılış animasyonu (zoom-in %95 → %100) bitmeden ölçülen yükseklik %95'e düşer (CI'da 38,2 px) — sonlu animasyonların
    // bitmesini bekle (sonsuz animasyon beklenmez)
    await detail.evaluate((el) => {
      const root = el.closest('[data-radix-popper-content-wrapper]') || el
      return Promise.all(root.getAnimations({ subtree: true })
        .filter((a) => a.effect?.getComputedTiming?.().iterations !== Infinity)
        .map((a) => a.finished.catch(() => {})))
    })
    try {
      await expect(detail.locator('[data-slot="trust-check"]')).toHaveCount(3)
    } catch (e) {
      const st = await page.evaluate(() => ({
        active: document.activeElement?.outerHTML.slice(0, 200),
        popovers: document.querySelectorAll('[data-slot="cert-trust-detail"]').length,
        dialogs: [...document.querySelectorAll('[role="dialog"], [role="alertdialog"]')]
          .map((d) => d.getAttribute('data-slot') || d.getAttribute('aria-label') || d.tagName),
        rows: document.querySelectorAll('[data-domain="kismen.example.com"]').length,
        url: location.search,
      })).catch((err) => ({ evaluateFailed: String(err?.message).slice(0, 120) }))
      throw new Error([e.message, `OLAYLAR: ${events.join(' | ')}`, `DURUM: ${JSON.stringify(st)}`].join('\n'))
    }
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

// Kök neden (2026-11-17; 15–17 Kasım CI'da tablet / masaüstünde ara sıra düşüş): süzgeç seçicisi (kipsiz Radix Popover)
// kapanış animasyonundayken rozete tıklanınca, rozetin `click` yayılımını durdurması yüzünden kapanan seçici dışarı
// etkileşimi görmüyor (Radix 1.3 ertelenen dışarı tıklama) ve animasyon sonunda odağı kendi tetiğine alıyordu — yeni açılan
// rozet penceresi odağı kaybedip kapanıyordu. CI'da animasyon geç bittiği için yalnız orada görülüyordu. Burada seçicinin
// kapanış animasyonu 1,5 sn'ye uzatılır: yarış HER koşuda kurulur; rozet penceresi açık kalmalı (shadcn PopoverContent
// `keepMovedFocus`).
test('süzgeç seçilir seçilmez rozet: pencere açık kalır, kapanan seçici odağı geri almaz', async ({ page }) => {
  test.setTimeout(90_000)
  await page.setViewportSize({ width: 1280, height: 800 })
  await page.addInitScript(() => {
    const style = document.createElement('style')
    style.textContent = '[data-state="closed"][data-slot="popover-content"]{animation-duration:1500ms!important}'
    document.addEventListener('DOMContentLoaded', () => document.head.appendChild(style))
  })
  await mockApi(page, { perms: PERMS })
  await mockCertApi(page)
  await page.route((u) => new URL(u).pathname === '/api/certificates/list', async (route) => {
    const data = new URL(route.request().url()).searchParams.get('filter_trust') === 'partial' ? [ROWS[1]] : ROWS
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      success: true, data, facets: FACETS, shared: {}, scope: 'mine', visible_to_all: false,
      pagination: { current_page: 1, per_page: 25, total: data.length, total_pages: 1 }, timestamp: iso(),
    }) })
  })
  await page.goto('/?tab=all')
  await expect(page.locator('[data-domain="kismen.example.com"]').first()).toBeVisible({ timeout: 20_000 })
  await page.getByRole('combobox', { name: /^(Trust|Güven)$/ }).click()
  await page.getByRole('option').filter({ hasText: /Partly verified \(1\)|Kısmen doğrulandı \(1\)/ }).click()
  // Seçicinin kapanış animasyonu sürerken (beklemeden) rozete tıklanır
  await page.getByRole('button', { name: /(Partly verified|Kısmen doğrulandı) — / }).first().click()
  // Yarış gerçekten kuruldu mu: tıklama, seçici hâlâ kapanırken oldu (Playwright örten listeyi bekleseydi test düzeltme
  // olmadan da geçerdi)
  await expect(page.locator('[data-slot="popover-content"][data-state="closed"]'), 'seçici hâlâ kapanıyor').toHaveCount(1)
  const detail = page.locator('[data-slot="cert-trust-detail"]')
  await expect(detail).toBeVisible()
  // Seçicinin kapanışı (1,5 sn) bitene dek bekle; pencere hâlâ açık ve odak onun içinde
  await expect(page.locator('[data-slot="popover-content"][data-state="closed"]')).toHaveCount(0, { timeout: 10_000 })
  await page.waitForTimeout(300)
  await expect(detail.locator('[data-slot="trust-check"]')).toHaveCount(3)
  expect(await detail.evaluate((el) => el.contains(document.activeElement)), 'odak rozet penceresinde').toBe(true)
})
