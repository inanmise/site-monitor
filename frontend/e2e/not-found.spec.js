// Markalı 404 + uygulama içi "Sayfa bulunamadı" (2026-10-08) — gerçek tarayıcı, API mock'lu. jsdom yerleşim hesaplamaz:
// burada telefon / tablet / masaüstü boyunda yatay kayma, dokunma hedefi (≥ 40 px), marka içeriği ve belge meta'sı ölçülür.
// Not: Vite geliştirme sunucusu bilinmeyen yola kabuğu 200 ile verir; HTTP 404 sözleşmesi backend'dedir
// (SpaNotFoundAdviceTest + canlı tur e2e/live/site-tour.live.spec.js).
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 800 },
]

/** Sayfa yatay kayıyor mu + görünür öğe görünüm alanının sağına taşıyor mu (kendi kaydırma kabı dışında). */
function measure(sel) {
  const vw = document.documentElement.clientWidth
  const root = document.querySelector(sel) || document.body
  const pageOverflow = Math.max(document.documentElement.scrollWidth - vw, document.body.scrollWidth - document.body.clientWidth)
  const offenders = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.width <= 1 || r.height <= 1 || r.right <= vw + 1) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (!contained) offenders.push(`${el.tagName.toLowerCase()}:${Math.round(r.right)}`)
    if (offenders.length >= 8) break
  }
  return { pageOverflow, offenders }
}

async function expectTouchTargets(locators, label) {
  for (const loc of locators) {
    const box = await loc.boundingBox()
    expect(box, `${label}: öğe görünür olmalı`).not.toBeNull()
    expect(box.height, `${label}: dokunma hedefi yüksekliği (${await loc.textContent()})`).toBeGreaterThanOrEqual(40)
  }
}

const metaDescription = (page) => page.locator('meta[name="description"]').getAttribute('content')

for (const vp of VIEWPORTS) {
  test.describe(`404 — ${vp.name} ${vp.width}×${vp.height}`, () => {
    test('bilinmeyen yol → markalı 404 sayfası (oturum açılışı yok), taşma yok, hedefler ≥ 40 px', async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height })
      const meCalls = []
      page.on('request', (r) => { if (new URL(r.url()).pathname === '/api/me') meCalls.push(r.url()) })
      await mockApi(page)
      await page.goto('/olmayan/bir/sayfa?kaynak=eposta')
      const nf = page.locator('[data-slot="not-found-page"]')
      await expect(nf).toBeVisible({ timeout: 20_000 })
      await expect(page.getByRole('heading', { level: 1, name: 'Page not found' })).toBeVisible()
      // Marka: başlıkta ve çizimde turp logosu (görsel gerçekten yüklendi)
      for (const img of [nf.locator('header img.brand-logo'), nf.locator('[data-slot="nf-art"] img.brand-logo')]) {
        await expect(img).toBeVisible()
        await expect.poll(() => img.evaluate((el) => el.complete && el.naturalWidth > 0), { timeout: 15_000 }).toBe(true)
      }
      await expect(nf.locator('[data-slot="nf-requested"]')).toHaveText('/olmayan/bir/sayfa?kaynak=eposta')
      await page.waitForTimeout(300)
      const m = await page.evaluate(measure, '[data-slot="not-found-page"]')
      expect(m.offenders, `${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `${vp.name}: yatay kayma`).toBeLessThanOrEqual(1)
      await expectTouchTargets([
        nf.locator('[data-slot="nf-home"]'), nf.locator('[data-slot="nf-help"]'), nf.locator('[data-slot="nf-lang"]'),
        ...await nf.locator('[data-slot="nf-link"]').all(),
      ], vp.name)
      expect(await page.title()).toBe('Page not found · SiteMonitor')
      expect(await metaDescription(page)).toMatch(/could not be found/)
      expect(meCalls, 'oturum açılışı (/api/me) 404 sayfasında çağrılmaz').toEqual([])
      // Ana sayfaya dön → uygulama kabuğu (mock oturum açık)
      await Promise.all([
        page.waitForURL((u) => u.pathname === '/', { timeout: 30_000 }),
        nf.locator('[data-slot="nf-home"]').click(),
      ])
      await expect(page.locator('.app-main')).toBeVisible({ timeout: 45_000 })
      await expect.poll(() => page.title()).toBe('Dashboard · SiteMonitor')
    })

    test('oturum açıkken bilinmeyen ?tab= → uygulama içi panel; Panoya git; taşma yok', async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mockApi(page)
      await page.goto('/?tab=kaldirilmis-sekme')
      await expect(page.locator('.app-main')).toBeVisible({ timeout: 20_000 })
      const panel = page.locator('[data-slot="not-found-panel"]')
      await expect(panel).toBeVisible()
      await expect(panel.getByRole('heading', { name: 'Page not found' })).toBeVisible()
      await expect(panel.locator('[data-slot="nf-requested"]')).toHaveText('?tab=kaldirilmis-sekme')
      await page.waitForTimeout(300)
      const m = await page.evaluate(measure, '.app-main')
      expect(m.offenders, `${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `${vp.name}: yatay kayma`).toBeLessThanOrEqual(1)
      await expectTouchTargets([panel.locator('[data-slot="nf-dashboard"]'), panel.locator('[data-slot="nf-help"]'),
        ...await panel.locator('[data-slot="nf-link"]').all()], vp.name)
      await expect.poll(() => page.title()).toBe('Page not found · SiteMonitor')
      // Uygulama içinde favicon seti ezilmez (useStatusFavicon('ok'))
      expect(await page.locator('link[rel="icon"][type="image/svg+xml"]').getAttribute('href')).toBe('/favicon.svg')
      await panel.locator('[data-slot="nf-dashboard"]').click()
      await expect(panel).toHaveCount(0)
      await expect(page).toHaveURL(/tab=dashboard/)
      await expect.poll(() => page.title()).toBe('Dashboard · SiteMonitor')
    })
  })
}

test('favicon seti ve manifest sunuluyor', async ({ request }) => {
  for (const [p, type] of [['/favicon.svg', /svg/], ['/favicon.ico', /icon/], ['/apple-touch-icon.png', /png/],
    ['/icon-192.png', /png/], ['/icon-512.png', /png/], ['/icon-maskable-512.png', /png/]]) {
    const r = await request.get(p)
    expect(r.status(), p).toBe(200)
    expect(r.headers()['content-type'], p).toMatch(type)
  }
  const mf = await request.get('/site.webmanifest')
  expect(mf.status()).toBe(200)
  const json = JSON.parse(await mf.text())
  expect(json.name).toBe('SiteMonitor')
})
