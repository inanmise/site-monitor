// İngilizce sözlük AYRI (lazy) chunk (2026-10-02, performans önerisi 22) — gerçek tarayıcıda, API mock'lu, girişsiz.
// Sözleşme: ekranlar aynı kalır; tek görünür fark İngilizce açılırken / İngilizce'ye geçerken kısa bir yükleme.
//   1) Saklı dil İngilizce: sözlük inene kadar açılış ekranı ("Loading..."), ardından uygulama DOĞRUDAN İngilizce —
//      Türkçe ara kare ya da ham i18n anahtarı (ör. `nav.dashboard`) yok.
//   2) Çalışırken TR→EN: kullanıcı menüsünde "English" seçilince satırda meşgul göstergesi, sonra İngilizce etiketler;
//      tercih kaydedilir.
//   3) Sözlük indirilemezse arayüz Türkçe kalır ve hata bildirimi çıkar (ham anahtar yok).
// jsdom dinamik import zamanlamasını ve gerçek chunk isteğini göremez; burada Vite dev sunucusu modülü ayrıca sunar.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { TR } from '../src/i18n/tr.js'
import { EN } from '../src/i18n/en.js'

const LANG_KEY = 'site-monitor-lang'
const EN_CHUNK = (u) => /\/src\/i18n\/en\.js(\?|$)/.test(new URL(u).pathname + new URL(u).search)
const KEYS = new Set(Object.keys(TR))

/** Sayfa metninde sözlük ANAHTARI gibi görünen ve gerçekten bir anahtar olan parçalar (ham anahtar sızıntısı). */
async function rawKeysOnPage(page) {
  const text = await page.evaluate(() => document.body.innerText)
  const tokens = text.match(/\b[a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9_-]+)+\b/g) || []
  return [...new Set(tokens.filter((tk) => KEYS.has(tk)))]
}

/** Saklı dili sayfa açılmadan yazar (yalnız İLK yüklemede — sonraki yeniden yüklemelerde kullanıcının seçimi kalır). */
async function presetLanguage(page, lang) {
  await page.addInitScript(([k, v]) => {
    try { if (!sessionStorage.getItem('e2e.langSeeded')) { localStorage.setItem(k, v); sessionStorage.setItem('e2e.langSeeded', '1') } } catch { /* yoksay */ }
  }, [LANG_KEY, lang])
}

test('saklı dil İngilizce: açılış ekranı → uygulama doğrudan İngilizce, ham anahtar yok', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await mockApi(page)
  await presetLanguage(page, 'en')
  // Sözlük isteğini kısa süre beklet: açılış ekranı gözlenebilsin (gerçekte genellikle bir an)
  let release
  const held = new Promise((r) => { release = r })
  let requested = 0
  await page.route(EN_CHUNK, async (route) => { requested++; await held; await route.continue() })

  await page.goto('/?tab=dashboard')
  const boot = page.locator('[data-slot="lang-boot"]')
  await expect(boot).toBeVisible({ timeout: 20_000 })
  await expect(boot.getByRole('status')).toContainText(EN['app.loading'])
  await expect(page.locator('.app-main')).toHaveCount(0)                    // uygulama ağacı sözlük inmeden çizilmez
  expect(await page.evaluate(() => document.body.innerText)).not.toContain(TR['app.loading'])
  await expect.poll(() => requested).toBe(1)                                  // tek istek (açılışta önceden başlatılan)

  release()
  await page.locator('.app-main').waitFor({ timeout: 20_000 })
  await expect(boot).toHaveCount(0)
  await expect(page.getByRole('heading', { name: EN['app.dashTitle'], exact: true })).toBeVisible()
  await expect(page.locator('[data-slot="sidebar"]')).toContainText(EN['nav.dashboard'])
  await expect(page.locator('html')).toHaveAttribute('lang', 'en')
  await page.waitForTimeout(800)                                            // ilk veri çizimi
  expect(await rawKeysOnPage(page)).toEqual([])
  const body = await page.evaluate(() => document.body.innerText)
  expect(body).not.toContain(TR['app.dashTitle'])
})

test('çalışırken TR→EN: menüde meşgul göstergesi, sonra İngilizce etiketler; tercih kaydedilir', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await mockApi(page)
  await presetLanguage(page, 'tr')
  let release
  const held = new Promise((r) => { release = r })
  await page.route(EN_CHUNK, async (route) => { await held; await route.continue() })

  await page.goto('/?tab=dashboard')
  await page.locator('.app-main').waitFor({ timeout: 20_000 })
  await expect(page.getByRole('heading', { name: TR['app.dashTitle'], exact: true })).toBeVisible()
  await expect(page.locator('[data-slot="lang-boot"]')).toHaveCount(0)      // Türkçe açılışta yükleme ekranı YOK

  await page.locator('[data-slot="user-menu-trigger"]').click()
  await page.locator('[data-slot="user-menu-language"]').click()
  const english = page.getByRole('menuitemradio', { name: /English/ })
  await english.click()
  // Sözlük inerken: arayüz hâlâ Türkçe, seçilen satırda meşgul göstergesi
  await expect(english).toHaveAttribute('aria-busy', 'true')
  await expect(english.getByRole('status')).toContainText(TR['nav.langLoading'])
  await expect(page.getByRole('heading', { name: TR['app.dashTitle'], exact: true })).toBeVisible()

  release()
  await expect(page.getByRole('heading', { name: EN['app.dashTitle'], exact: true })).toBeVisible({ timeout: 10_000 })
  await expect(page.getByRole('menuitemradio', { name: /English/ })).toHaveAttribute('aria-checked', 'true')
  await expect(page.locator('html')).toHaveAttribute('lang', 'en')
  expect(await page.evaluate((k) => localStorage.getItem(k), LANG_KEY)).toBe('en')
  await page.keyboard.press('Escape')
  await expect(page.locator('[data-slot="sidebar"]')).toContainText(EN['nav.dashboard'])
  expect(await rawKeysOnPage(page)).toEqual([])

  // Yeniden yükleme: tercih kalıcı, uygulama İngilizce açılır
  await page.unroute(EN_CHUNK)
  await page.reload()
  await page.locator('.app-main').waitFor({ timeout: 20_000 })
  await expect(page.getByRole('heading', { name: EN['app.dashTitle'], exact: true })).toBeVisible()
})

test('sözlük indirilemezse: arayüz Türkçe kalır, hata bildirimi çıkar, ham anahtar yok', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await mockApi(page)
  await presetLanguage(page, 'tr')
  await page.route(EN_CHUNK, (route) => route.abort('failed'))

  await page.goto('/?tab=dashboard')
  await page.locator('.app-main').waitFor({ timeout: 20_000 })
  await page.locator('[data-slot="user-menu-trigger"]').click()
  await page.locator('[data-slot="user-menu-language"]').click()
  await page.getByRole('menuitemradio', { name: /English/ }).click()

  await expect(page.getByText(TR['lang.loadFailed'])).toBeVisible({ timeout: 10_000 })
  await expect(page.getByRole('heading', { name: TR['app.dashTitle'], exact: true })).toBeVisible()
  await expect(page.getByRole('menuitemradio', { name: /Türkçe/ })).toHaveAttribute('aria-checked', 'true')
  expect(await page.evaluate((k) => localStorage.getItem(k), LANG_KEY)).toBe('tr')
  expect(await rawKeysOnPage(page)).toEqual([])
})
