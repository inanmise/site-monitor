// Yuvarlanan dağıtımda karışan sayfa dosyaları (2026-10-23, çok pod sürüm 2b · MP-47) — gerçek tarayıcı, API mock'lu.
// Eski + yeni pod birlikte sunarken index.html bir poddan (A), paketin dosyaları öbüründen (B) gelir: B'de A'nın hash'li
// dosyası yoktur → düz metin 404 (SpaNotFoundAdvice) → React hiç başlamaz. Sözleşme:
//   1) Paketin giriş betiği bir kez yüklenemezse sayfa BİR KEZ yenilenir (public/boot-guard.js) ve uygulama çizilir.
//   2) Her yükleme başarısızsa yine yalnız BİR yenileme — sonra boş sayfa yerine uyarı + "Reload" (390 / 1280 sığar).
//   3) Açılışta İngilizce sözlük parçası bir kez inmezse: bir yenileme, uygulama İngilizce (Türkçe'ye düşülmez).
//   4) Sözlük her seferinde inmezse: bir yenileme, sonra eski yedek yol (Türkçe + bildirim).
// Vite geliştirme sunucusunda giriş betiği /src/main.jsx'tir; üretimdeki /assets/index-<hash>.js 404'ünün yerine onun
// 404'ü verilir (aynı yol: modül betiğinin "error" olayı). Gerçek imajda önceki sürümün dosyaları ayrıca taşınır
// (Dockerfile "previous-release", image-boot-check.sh adım 7) — bu senaryo taşınmayan / yetişmeyen yöndür.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { TR } from '../src/i18n/tr.js'
import { EN } from '../src/i18n/en.js'

const LANG_KEY = 'site-monitor-lang'
const ENTRY = (u) => new URL(u).pathname === '/src/main.jsx'
const EN_CHUNK = (u) => new URL(u).pathname === '/src/i18n/en.js'
const NOT_FOUND = { status: 404, contentType: 'text/plain;charset=UTF-8', headers: { 'Cache-Control': 'no-store, must-revalidate' }, body: 'Not Found' }

async function presetLanguage(page, lang) {
  await page.addInitScript(([k, v]) => {
    try { if (!sessionStorage.getItem('e2e.langSeeded')) { localStorage.setItem(k, v); sessionStorage.setItem('e2e.langSeeded', '1') } } catch { /* yoksay */ }
  }, [LANG_KEY, lang])
}

/** Sayfa (document) yüklemelerini sayar — ilk açılış 1, her yenileme +1. */
function countDocuments(page) {
  const box = { n: 0 }
  page.on('request', (r) => { if (r.resourceType() === 'document' && r.frame() === page.mainFrame()) box.n++ })
  return box
}

test.describe('rolling update — paketin dosyası yüklenemedi', () => {
  test('giriş betiği BİR KEZ 404: sayfa bir kez yenilenir, uygulama çizilir', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await mockApi(page)
    await presetLanguage(page, 'tr')
    const docs = countDocuments(page)
    let entryRequests = 0
    await page.route(ENTRY, async (route) => {
      entryRequests++
      if (entryRequests === 1) await route.fulfill(NOT_FOUND)
      else await route.continue()
    })
    await page.goto('/?tab=dashboard')
    await page.locator('.app-main').waitFor({ timeout: 30_000 })
    await expect(page.getByRole('heading', { name: TR['app.dashTitle'], exact: true })).toBeVisible()
    expect(docs.n).toBe(2)                                                    // tam olarak BİR yenileme
    expect(entryRequests).toBe(2)
    const rec = await page.evaluate(() => JSON.parse(sessionStorage.getItem('eb-chunk-reload')))
    expect(rec.builds).toEqual({ '/src/main.jsx': 1 })
    await expect(page.locator('[data-slot="boot-failure"]')).toHaveCount(0)
    await page.waitForTimeout(1500)
    expect(docs.n).toBe(2)                                                    // sonradan ek yenileme yok
  })

  for (const vp of [{ width: 390, height: 844 }, { width: 1280, height: 900 }]) {
    test(`giriş betiği HER SEFER 404 (${vp.width}): yalnız bir yenileme, sonra boş sayfa yerine uyarı`, async ({ page }) => {
      await page.setViewportSize(vp)
      await mockApi(page)
      const docs = countDocuments(page)
      await page.route(ENTRY, (route) => route.fulfill(NOT_FOUND))
      await page.goto('/?tab=dashboard')
      const notice = page.locator('[data-slot="boot-failure"]')
      await expect(notice).toBeVisible({ timeout: 20_000 })
      await expect(notice).toHaveAttribute('role', 'alert')
      await expect(notice).toContainText('SiteMonitor is being updated')      // varsayılan dil İngilizce
      const button = notice.getByRole('button', { name: 'Reload' })
      await expect(button).toBeVisible()
      const box = await button.boundingBox()
      expect(box.height).toBeGreaterThanOrEqual(40)
      expect(box.width).toBeGreaterThanOrEqual(40)
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
      expect(overflow).toBeLessThanOrEqual(0)
      const nb = await notice.boundingBox()
      expect(nb.x).toBeGreaterThanOrEqual(0)
      expect(nb.x + nb.width).toBeLessThanOrEqual(vp.width)
      await page.waitForTimeout(2500)
      expect(docs.n).toBe(2)                                                  // döngü yok: ilk açılış + BİR yenileme
      // Kullanıcının kendi yenilemesi serbest (sayaçsız) — yeni yükleme de başarısızsa yine yalnız uyarı
      await button.click()
      await expect(notice).toBeVisible({ timeout: 20_000 })
      await page.waitForTimeout(1500)
      expect(docs.n).toBe(3)
    })
  }
})

test.describe('rolling update — İngilizce sözlük parçası açılışta inmedi', () => {
  test('BİR KEZ inmezse: bir yenileme, uygulama doğrudan İngilizce (Türkçe yedek yok)', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await mockApi(page)
    await presetLanguage(page, 'en')
    const docs = countDocuments(page)
    let enRequests = 0
    await page.route(EN_CHUNK, async (route) => {
      enRequests++
      if (enRequests === 1) await route.fulfill(NOT_FOUND)
      else await route.continue()
    })
    await page.goto('/?tab=dashboard')
    await page.locator('.app-main').waitFor({ timeout: 30_000 })
    await expect(page.getByRole('heading', { name: EN['app.dashTitle'], exact: true })).toBeVisible()
    await expect(page.locator('html')).toHaveAttribute('lang', 'en')
    expect(docs.n).toBe(2)
    await expect(page.getByText(TR['lang.loadFailed'])).toHaveCount(0)
  })

  test('HER SEFER inmezse: yalnız bir yenileme, sonra eski yedek yol (Türkçe arayüz + bildirim)', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await mockApi(page)
    await presetLanguage(page, 'en')
    const docs = countDocuments(page)
    await page.route(EN_CHUNK, (route) => route.fulfill(NOT_FOUND))
    await page.goto('/?tab=dashboard')
    await page.locator('.app-main').waitFor({ timeout: 30_000 })
    await expect(page.getByRole('heading', { name: TR['app.dashTitle'], exact: true })).toBeVisible()
    await expect(page.getByText(TR['lang.loadFailed'])).toBeVisible({ timeout: 10_000 })
    expect(await page.evaluate((k) => localStorage.getItem(k), LANG_KEY)).toBe('en')   // tercih korunur
    await page.waitForTimeout(1500)
    expect(docs.n).toBe(2)
  })
})
