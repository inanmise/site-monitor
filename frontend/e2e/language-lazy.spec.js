// Sözlükler AYRI (lazy) chunk (İngilizce 2026-10-02, performans önerisi 22; Türkçe 2026-11-06) — gerçek tarayıcıda, API
// mock'lu, girişsiz. Sözleşme: ekranlar aynı kalır; tek görünür fark bir dilde açılırken / o dile geçerken kısa bir yükleme.
//   1) Saklı dil İngilizce: sözlük inene kadar açılış ekranı ("Loading..."), ardından uygulama DOĞRUDAN İngilizce —
//      Türkçe ara kare ya da ham i18n anahtarı (ör. `nav.dashboard`) yok.
//   2) Çalışırken TR→EN: kullanıcı menüsünde "English" seçilince satırda meşgul göstergesi, sonra İngilizce etiketler;
//      tercih kaydedilir.
//   3) Sözlük indirilemezse arayüz Türkçe kalır ve hata bildirimi çıkar (ham anahtar yok).
//   4) (2026-11-06) Açılış parçası yalnız ETKİN dilin sözlüğünü ister: Türkçe kullanıcı İngilizce, İngilizce kullanıcı Türkçe
//      sözlüğü indirmez. Türkçe parça inmezse (bir yenilemeden sonra) İngilizce yedek; ikisi de inmezse sözlüksüz hata ekranı.
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
  await expect(page.locator('[data-slot="lang-boot"]')).toHaveCount(0)      // Türkçe sözlük inince açılış ekranı kalkar

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

// ── 2026-11-06: Türkçe sözlük de ayrı parça ─────────────────────────────────────────────────────────────────────────────
const TR_CHUNK = (u) => new URL(u).pathname === '/src/i18n/tr.js'
const EN_CHUNK_EXACT = (u) => new URL(u).pathname === '/src/i18n/en.js'
const NOT_FOUND = { status: 404, contentType: 'text/plain;charset=UTF-8', headers: { 'Cache-Control': 'no-store, must-revalidate' }, body: 'Not Found' }

/** Sözlük parçası istek sayaçları (ağa giden her istek; Vite geliştirme sunucusunda parça = kaynak modül). */
function countDictRequests(page) {
  const box = { tr: 0, en: 0, docs: 0 }
  page.on('request', (r) => {
    const p = new URL(r.url()).pathname
    if (p === '/src/i18n/tr.js') box.tr++
    if (p === '/src/i18n/en.js') box.en++
    if (r.resourceType() === 'document' && r.frame() === page.mainFrame()) box.docs++
  })
  return box
}

for (const lang of ['tr', 'en']) {
  test(`saklı dil ${lang}: yalnız ${lang} sözlük parçası istenir, öbürü İSTENMEZ`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await mockApi(page)
    await presetLanguage(page, lang)
    const n = countDictRequests(page)
    await page.goto('/?tab=dashboard')
    await page.locator('.app-main').waitFor({ timeout: 30_000 })
    const dict = lang === 'tr' ? TR : EN
    await expect(page.getByRole('heading', { name: dict['app.dashTitle'], exact: true })).toBeVisible()
    await expect(page.locator('html')).toHaveAttribute('lang', lang)
    await page.waitForTimeout(800)                                            // ilk veri çizimi + tembel parçalar
    expect(lang === 'tr' ? n.tr : n.en).toBe(1)
    expect(lang === 'tr' ? n.en : n.tr).toBe(0)
    expect(await rawKeysOnPage(page)).toEqual([])
  })
}

test('Türkçe sözlük parçası inmezse: bir yenileme, sonra İngilizce yedek (bildirim doğru dosyayı söyler, X-Lang en, tercih tr)', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await mockApi(page)
  await presetLanguage(page, 'tr')
  const n = countDictRequests(page)
  const xLang = []
  page.on('request', (r) => { if (new URL(r.url()).pathname.startsWith('/api/')) xLang.push(r.headers()['x-lang'] || '') })
  await page.route(TR_CHUNK, (route) => route.fulfill(NOT_FOUND))
  await page.goto('/?tab=dashboard')
  await expect(page.getByText(EN['lang.loadFailedTr']).first()).toBeVisible({ timeout: 45_000 })
  await page.locator('.app-main').waitFor({ timeout: 30_000 })
  await expect(page.getByRole('heading', { name: EN['app.dashTitle'], exact: true })).toBeVisible()
  await expect(page.locator('html')).toHaveAttribute('lang', 'en')
  await expect.poll(() => xLang.filter(Boolean)).toContain('en')
  expect(xLang.filter((v) => v === 'tr')).toEqual([])                        // oturum İngilizce'ye zorlandı
  expect(await page.evaluate((k) => localStorage.getItem(k), LANG_KEY)).toBe('tr')   // tercih korunur
  expect(n.docs).toBe(2)                                                     // ilk açılış + tek yenileme
  expect(n.en).toBe(1)                                                       // İngilizce yalnız yedek olarak, bir kez
  expect(await rawKeysOnPage(page)).toEqual([])
})

for (const vp of [{ width: 390, height: 844 }, { width: 1280, height: 900 }]) {
  test(`iki sözlük parçası da inmezse (${vp.width}): sözlüksüz, sabit metinli hata ekranı, döngü yok`, async ({ page }) => {
    await page.setViewportSize(vp)
    await mockApi(page)
    await presetLanguage(page, 'tr')
    const n = countDictRequests(page)
    await page.route(TR_CHUNK, (route) => route.fulfill(NOT_FOUND))
    await page.route(EN_CHUNK_EXACT, (route) => route.fulfill(NOT_FOUND))
    await page.goto('/?tab=dashboard')
    const screenEl = page.locator('[data-slot="lang-boot-failure"]')
    await expect(screenEl).toBeVisible({ timeout: 45_000 })
    await expect(screenEl).toHaveAttribute('lang', 'tr')
    const alert = screenEl.getByRole('alert')
    await expect(alert).toContainText(TR['lang.bootFailed.title'])
    await expect(alert).toContainText(TR['lang.bootFailed.body'])
    await expect(page.locator('.app-main')).toHaveCount(0)
    await expect(page.locator('[data-slot="lang-boot"]')).toHaveCount(0)    // "Yükleniyor..." ekranında kalınmaz
    expect(await rawKeysOnPage(page)).toEqual([])
    const button = screenEl.getByRole('button', { name: TR['lang.bootReload'] })
    await expect(button).toBeVisible()
    const box = await button.boundingBox()
    expect(box.height).toBeGreaterThanOrEqual(44)
    expect(box.width).toBeGreaterThanOrEqual(44)
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(overflow).toBeLessThanOrEqual(0)
    const sb = await screenEl.boundingBox()
    expect(sb.x).toBeGreaterThanOrEqual(0)
    expect(sb.x + sb.width).toBeLessThanOrEqual(vp.width)
    // Metin kırpılmaz: başlık ve gövde kutularına sığar
    const clipped = await screenEl.evaluate((el) => [...el.querySelectorAll('*')]
      .filter((e) => e.children.length === 0 && e.textContent.trim() && (e.scrollWidth > e.clientWidth + 1)).length)
    expect(clipped).toBe(0)
    await page.waitForTimeout(1500)
    expect(n.docs).toBe(2)                                                   // ilk açılış + tek otomatik yenileme
    // Kullanıcının kendi yenilemesi serbest: yeni yükleme de başarısızsa yine hata ekranı (döngü yok)
    await button.click()
    await expect(page.locator('[data-slot="lang-boot-failure"]')).toBeVisible({ timeout: 45_000 })
    await page.waitForTimeout(1000)
    expect(n.docs).toBe(3)
  })
}
