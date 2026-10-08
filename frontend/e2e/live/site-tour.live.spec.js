// CANLI: uçtan uca genel tur (2026-10-08, kullanıcı isteği: "SiteMonitor özelinde uçtan uca genel kontrol").
// GERÇEK arayüz + GERÇEK yerel backend: oturum açık kullanıcının açabildiği HER sekme (utils/appRoutes VALID_TABS)
// masaüstü (1280×800) ve telefon (390×844) boyunda açılır. Her sekmede:
//   • yakalanmamış istisna ve /api 5xx yok (fixtures.js page fikstürü düşürür),
//   • görünür metinde ham i18n anahtarı (sözlükteki HERHANGİ bir anahtar), `{0}` / `undefined` / `NaN` / `[object Object]` yok,
//   • belge başlığı = "<sayfanın TR meta başlığı> · <marka>", meta açıklaması = TR meta açıklaması (varsayılan değil),
//   • sayfa yatay kaymaz, görünür öğe ekran dışına taşmaz, birincil başlık görünür.
// Ayrıca markalı 404: bilinmeyen yol (HTTP 404 + kabuk — backend; arayüz — Vite) ve bilinmeyen ?tab= paneli.
// YALNIZ GEZİNİR: hiçbir düğmeye basılmaz (kontrol tetiklenmez, bildirim / push / OTP testi yok, kayıt değişmez).
import {
  test, expect, liveGate, openApp, apiGet, expectCleanText, measure, stopwatch, liveRequest, LIVE, LIVE_STATE,
} from './fixtures.js'
import { TR } from '../../src/i18n/tr.js'
import { VALID_TABS } from '../../src/utils/appRoutes.js'

const VIEWPORTS = [
  { name: 'masaüstü', width: 1280, height: 800 },
  { name: 'telefon', width: 390, height: 844 },
]
/** index.html'in genel varsayılanı — bir sekme bunu taşıyorsa meta kancası çalışmamış demektir. */
const GENERIC_TITLE = 'SiteMonitor — SSL certificate and service monitoring'
const BACKEND = 'http://127.0.0.1:8080'
const KEYS = new Set(Object.keys(TR))

/** Görünür metinde sözlük anahtarının KENDİSİ (çevrilmemiş t('...') çıktısı) var mı. */
function rawKeysIn(text) {
  const hits = new Set()
  for (const tok of text.split(/[\s"'“”‘’()[\]{},;:!?«»<>|]+/)) {
    const k = tok.replace(/[.…]+$/, '')
    if (k.includes('.') && KEYS.has(k)) hits.add(k)
  }
  return [...hits]
}

/**
 * Görünür metin — `code` / `pre` / `kbd` içerikleri HARİÇ: kılavuz bir özelliği belgelerken yer tutucuyu bilerek yazar
 * (ör. Keyword izlemenin URL'deki `{timestamp}` yer tutucusu). Sözleşme kırığı ararken onlar gürültüdür.
 */
async function proseText(root) {
  let text = await root.innerText()
  for (const c of await root.locator('code, pre, kbd').allInnerTexts()) if (c) text = text.split(c).join(' ')
  return text
}

/** fixtures.expectCleanText ile aynı imzalar + sözlükteki her anahtar; kod blokları hariç. */
async function expectCleanProse(root, label) {
  const text = await proseText(root)
  const bad = []
  const ph = text.match(/\{(\d+|[a-z_]+)\}/g)
  if (ph) bad.push(`yer tutucu: ${[...new Set(ph)].slice(0, 5).join(', ')}`)
  for (const w of ['undefined', 'NaN', '[object Object]', 'Invalid Date']) if (text.includes(w)) bad.push(w)
  const raw = rawKeysIn(text)
  if (raw.length) bad.push(`i18n anahtarı: ${raw.slice(0, 5).join(', ')}`)
  expect.soft(bad, `${label}: görünür metinde sözleşme kırığı`).toEqual([])
}

/**
 * Sekme açılınca birincil başlık görünene dek bekler. Canlı koşu Vite geliştirme sunucusunda yürür: ilk ziyaretteki soğuk
 * dönüşüm ve paralel düzenlemelerin tetiklediği HMR yeniden yüklemesi bir sekmeyi "Yükleniyor"da bırakabilir — o durumda
 * BİR KEZ yeniden yüklenir (gerçek bir eksik başlık ikinci denemede de görünmez ve test kırmızı kalır).
 */
async function waitForHeading(page, tab) {
  const heading = () => page.locator('.app-main').first().locator('h1, h2, [data-slot="page-title"]').filter({ visible: true }).first()
  try {
    await expect(heading()).toBeVisible({ timeout: 30_000 })
  } catch {
    console.log(`[live] ${tab}: başlık 30 sn'de gelmedi — sayfa bir kez yeniden yükleniyor`)
    await page.reload()
    await expect(heading(), `${tab}: birincil başlık`).toBeVisible({ timeout: 45_000 })
  }
}

/** Oturumun görebildiği sekmeler + beklenen meta anahtarı (rolüne kapalı sekme "Erişim yok" paneli gösterir). */
async function plan(request) {
  const me = await apiGet(request, '/api/me')
  expect(me.status, '/api/me').toBe(200)
  const role = me.json?.system_role || 'USER'
  const global = me.json?.global_admin === true
  const brandRes = await apiGet(request, '/api/branding')
  const b = brandRes.json?.data || {}
  const brand = String(b.tab_title || b.app_name || 'SiteMonitor').trim() || 'SiteMonitor'
  const restricted = (tab) => (tab === 'settings' && role !== 'ADMIN') || (tab === 'sqlplayground' && !global)
  return { brand, role, global, restricted }
}

test.describe('uçtan uca genel tur — canlı', () => {
  liveGate()

  let ctx = null
  test.beforeAll(async ({ playwright }) => {
    if (!LIVE) return   // varsayılan (mock'lu) yapılandırma bu dosyayı da toplar — orada hiçbir şey koşmaz
    const req = await liveRequest(playwright)
    try { ctx = await plan(req) } finally { await req.dispose() }
    console.log(`[live] tur: rol=${ctx.role}, global=${ctx.global}, marka="${ctx.brand}", ${VALID_TABS.size} sekme × ${VIEWPORTS.length} boy`)
  })

  for (const vp of VIEWPORTS) {
    for (const tab of VALID_TABS) {
      test(`${vp.name} · ${tab}`, async ({ page }) => {
        const done = stopwatch(`tur ${vp.name} ${tab}`)
        await page.setViewportSize({ width: vp.width, height: vp.height })
        await openApp(page, `/?tab=${tab}`)
        const main = page.locator('.app-main').first()
        // Tembel sekme + ilk veri: birincil başlık görünene dek (yükleme göstergesi başlık taşımaz)
        await waitForHeading(page, tab)
        await page.waitForTimeout(1500)

        const restricted = ctx.restricted(tab)
        const metaKey = restricted ? 'meta.page.restricted' : `meta.tab.${tab}`
        const wantTitle = `${TR[`${metaKey}.title`]} · ${ctx.brand}`
        await expect.poll(() => page.title(), { message: `${tab}: belge başlığı` }).toBe(wantTitle)
        expect(await page.title()).not.toBe(GENERIC_TITLE)
        const desc = await page.locator('meta[name="description"]').getAttribute('content')
        expect.soft(desc, `${tab}: meta açıklaması`).toBe(TR[`${metaKey}.description`])
        if (restricted) await expect(page.locator('[data-slot="not-found-panel"][data-kind="restricted"]')).toBeVisible()
        else await expect(page.locator('[data-slot="not-found-panel"]'), `${tab}: geçerli sekmede panel olmamalı`).toHaveCount(0)

        await expectCleanProse(main, `${vp.name} ${tab}`)

        const m = await page.evaluate(measure, '.app-main')
        expect.soft(m.pageOverflow, `${vp.name} ${tab}: yatay kayma (px)`).toBeLessThanOrEqual(1)
        expect.soft(m.offenders, `${vp.name} ${tab}: ekran dışına taşan öğe`).toEqual([])
        done()
      })
    }
  }

  test('bilinmeyen ?tab= → uygulama içi "Sayfa bulunamadı" paneli; "Panoya git" Pano\'yu açar', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await openApp(page, '/?tab=e2e-live-olmayan-sekme')
    const panel = page.locator('[data-slot="not-found-panel"][data-kind="notFound"]')
    await expect(panel).toBeVisible({ timeout: 30_000 })
    await expect(panel.locator('[data-slot="nf-requested"]')).toHaveText('?tab=e2e-live-olmayan-sekme')
    await expect.poll(() => page.title()).toBe(`${TR['meta.page.notFound.title']} · ${ctx.brand}`)
    await expectCleanText(panel, 'uygulama içi 404')
    const m = await page.evaluate(measure, '.app-main')
    expect(m.pageOverflow).toBeLessThanOrEqual(1)
    await panel.locator('[data-slot="nf-dashboard"]').click()
    await expect(panel).toHaveCount(0)
    await expect(page).toHaveURL(/tab=dashboard/)
    await expect.poll(() => page.title()).toBe(`${TR['meta.tab.dashboard.title']} · ${ctx.brand}`)
  })

  test('bilinmeyen yol → markalı 404 sayfası (arayüz), "Ana sayfaya dön" oturumlu kabuğa götürür', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/e2e-live/olmayan-sayfa?kaynak=tur')
    const nf = page.locator('[data-slot="not-found-page"]')
    await expect(nf).toBeVisible({ timeout: 30_000 })
    await expect(nf.getByRole('heading', { level: 1, name: 'Sayfa bulunamadı' })).toBeVisible()
    await expect(nf.locator('[data-slot="nf-requested"]')).toHaveText('/e2e-live/olmayan-sayfa?kaynak=tur')
    await expect.poll(() => page.title()).toBe(`${TR['meta.page.notFound.title']} · ${ctx.brand}`)
    await expectCleanText(nf, '404 sayfası')
    const m = await page.evaluate(measure, '[data-slot="not-found-page"]')
    expect(m.offenders).toEqual([])
    expect(m.pageOverflow).toBeLessThanOrEqual(1)
    await Promise.all([page.waitForURL((u) => u.pathname === '/'), nf.locator('[data-slot="nf-home"]').click()])
    await expect(page.locator('.app-main')).toBeVisible({ timeout: 45_000 })
  })

  // Backend sözleşmesi doğrudan :8080'de (Vite geliştirme sunucusu bilinmeyen yola kabuğu 200 ile verir).
  // Backend 2026-10-08'den ESKİ bir paketle koşuyorsa bu test kırmızıdır — yeniden derleyip başlatın.
  test('backend: bilinmeyen sayfa 404 + kabuk; bilinmeyen /api 404 JSON NOT_FOUND; eksik varlık düz 404', async ({ playwright }) => {
    const req = await playwright.request.newContext({ baseURL: BACKEND, storageState: LIVE_STATE })
    try {
      const page404 = await req.get('/e2e-live/olmayan-sayfa', { headers: { Accept: 'text/html,application/xhtml+xml' } })
      expect(page404.status(), 'bilinmeyen sayfa').toBe(404)
      expect(page404.headers()['content-type']).toMatch(/text\/html/)
      expect(await page404.text()).toContain('<div id="root">')
      expect(page404.headers()['cache-control']).toMatch(/no-store/)
      expect(page404.headers()['content-security-policy']).toMatch(/script-src 'self';/)

      const api404 = await req.get('/api/e2e-live-olmayan-uc', { headers: { Accept: 'application/json', 'X-Lang': 'tr' } })
      expect(api404.status(), 'bilinmeyen /api').toBe(404)
      const body = await api404.json()
      expect(body).toMatchObject({ success: false, code: 'NOT_FOUND' })
      expect(body.error).toMatch(/bulunamadı/)

      const asset = await req.get('/assets/e2e-live-olmayan.js', { headers: { Accept: '*/*' } })
      expect(asset.status(), 'eksik varlık').toBe(404)
      expect(asset.headers()['content-type']).toMatch(/text\/plain/)
      expect(asset.headers()['cache-control']).not.toMatch(/immutable/)

      const root = await req.get('/', { headers: { Accept: 'text/html' } })
      expect(root.status(), '/').toBe(200)
      const mf = await req.get('/site.webmanifest')
      expect(mf.status(), 'manifest').toBe(200)
      expect(mf.headers()['content-type']).toMatch(/application\/manifest\+json/)
    } finally {
      await req.dispose()
    }
  })
})
