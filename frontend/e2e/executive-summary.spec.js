import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { response, SETTINGS } from '../src/test/helpers/executiveFixtures.js'

// Aylık Yönetici Özeti (2026-10-10, kullanıcı: "mweb responsive yapıda olsun", "tasarımlar shadcn ile"): sayfa telefon
// (390), tablet (768) ve dizüstünde (1280) API mock'lu açılır; ölçülen: sayfa düzeyinde yatay taşma YOK, görünür hiçbir
// öğe görünüm alanının sağına taşmaz (kendi kaydırma kabındaki tablo hariç), telefonda/tablette dokunma hedefleri ≥ 40 px,
// tablolar telefonda kart listesi, ≥ 768 tablo. Ayar penceresi telefonda ekrana sığar.
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

const json = (body) => ({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })

async function mockExecutive(page) {
  await page.route((u) => new URL(u).pathname === '/api/executive-summary', (route) => route.fulfill(json(response())))
  await page.route((u) => new URL(u).pathname === '/api/executive-summary/settings',
    (route) => route.fulfill(json({ success: true, data: SETTINGS })))
}

/** Görünüm alanının sağına taşan, kendi kaydırma kabında OLMAYAN görünür öğeler. */
function offenders(rootSel) {
  const vw = document.documentElement.clientWidth
  const root = document.querySelector(rootSel) || document.body
  const out = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.width <= 1 || r.height <= 1 || r.right <= vw + 1) continue
    const cs = getComputedStyle(el)
    if (cs.visibility === 'hidden' || cs.display === 'none') continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (!contained) out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || ''}] ${Math.round(r.right)}`)
    if (out.length > 6) break
  }
  return out
}

for (const vp of VIEWPORTS) {
  test(`yönetici özeti (${vp.name} ${vp.width}): taşma yok, dokunma hedefleri, tablo/kart`, async ({ browser }) => {
    test.setTimeout(120_000)
    const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch })
    const page = await context.newPage()
    await mockApi(page)
    await mockExecutive(page)
    await page.goto('/?tab=executive')

    const sections = page.locator('[data-slot="ex-section"]')
    await expect(sections).toHaveCount(8, { timeout: 30_000 })
    await expect(page.locator('[data-slot="ex-headline"]')).toBeVisible()
    await expect(page.locator('[data-slot="ex-trend-chart"], [data-testid="ex-trend-chart"]').first()).toBeVisible()
    // Yeni bölümler (TLS notu, kripto hazırlığı, veri kalitesi) + dağılım çubukları
    for (const key of ['tls-grade', 'crypto-readiness', 'data-quality']) {
      await expect(page.locator(`[data-slot="ex-section"][data-key="${key}"]`)).toBeVisible()
    }
    await expect(page.locator('[data-slot="ex-tls-dist"]')).toBeVisible()
    await expect(page.locator('[data-slot="ex-crypto-dist"]')).toBeVisible()

    // Sayfa düzeyinde yatay taşma yok (belge + uygulama kaydırma kabı)
    const overflow = await page.evaluate(() => {
      const els = [document.documentElement, document.body, document.querySelector('.app-main')].filter(Boolean)
      return Math.max(...els.map((el) => el.scrollWidth - el.clientWidth))
    })
    expect(overflow, 'yatay taşma').toBeLessThanOrEqual(1)
    expect(await page.evaluate(offenders, '[data-slot="ex-page"]'), 'görünüm alanı dışına taşan öğeler').toEqual([])

    // Telefon: tablolar kart listesi; ≥ 768 gerçek tablo
    const wide = page.locator('[data-slot="ex-table-wide"]').first()
    const cards = page.locator('[data-slot="ex-table-cards"]').first()
    if (vp.width < 768) {
      await expect(cards).toBeVisible()
      await expect(wide).toBeHidden()
    } else {
      await expect(wide).toBeVisible()
      await expect(cards).toBeHidden()
    }

    // Dokunma hedefleri ≥ 40 px (telefon + tablet): sayfadaki görünür düğme, bağlantı-düğme ve seçiciler
    if (vp.touch) {
      const small = await page.evaluate(() => {
        const out = []
        for (const el of document.querySelectorAll('[data-slot="ex-page"] :is(button, a[data-slot="ex-jump"], select)')) {
          const r = el.getBoundingClientRect()
          if (r.width <= 1 || r.height <= 1) continue
          if (getComputedStyle(el).visibility === 'hidden') continue
          if (r.height < 39.5) out.push(`${el.tagName.toLowerCase()} "${(el.textContent || el.getAttribute('aria-label') || '').trim().slice(0, 30)}" ${Math.round(r.height)}px`)
        }
        return out
      })
      expect(small, 'küçük dokunma hedefleri').toEqual([])
    }

    await page.locator('[data-slot="ex-headline"]').screenshot({ path: `test-results/executive-headline-${vp.name}.png` })
    await page.locator('[data-slot="ex-section"][data-key="availability"]').screenshot({ path: `test-results/executive-availability-${vp.name}.png` })
    await page.locator('[data-slot="ex-section"][data-key="tls-grade"]').screenshot({ path: `test-results/executive-tls-grade-${vp.name}.png` })
    await page.locator('[data-slot="ex-section"][data-key="crypto-readiness"]').screenshot({ path: `test-results/executive-crypto-${vp.name}.png` })

    // Ayar penceresi (global yönetici) ekrana sığar
    await page.locator('[data-slot="ex-open-settings"]').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog.locator('[data-slot="ex-settings"]')).toBeVisible({ timeout: 15_000 })
    const box = await dialog.boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(-1)
    expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
    await page.screenshot({ path: `test-results/executive-${vp.name}.png`, fullPage: false })
    await context.close()
  })
}
