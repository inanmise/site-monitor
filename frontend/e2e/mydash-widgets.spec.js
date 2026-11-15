// Kişisel Panom — yalnız "Kutu ekle"den gelen kutular (2026-11-15, prod kapısı e2e paketi; model mydash.spec.js):
// `pagespeed_slowest` (En yavaş sayfalar), `pagespeed_over_budget` (Bütçeyi aşan sayfalar), `my_gaps` (Eksiklerim).
// Telefon (390, dokunmatik), tablet (768, dokunmatik), dizüstü (1280), API taklitli:
//   1) düzenleme kipinde katalogdan üçü eklenir → tercih PUT gövdesi üç türü taşır; katalog penceresi ekrana sığar;
//   2) veriyle çizim: en yavaş sayfa (7.4 s) uzun adıyla ilk satırda, alınamayan / ölçülmemiş sayılır, ton kırmızı; bütçe
//      kutusu 1 aşan + ölçüler, ton turuncu; Eksiklerim düzenleme kipinde okuma TETİKLEMEZ, "Denetle" ile okunur (profil 2) ve
//      sayıya dokununca eksikler penceresi açılır; satır izlemenin ayrıntısına gider;
//   3) TV hücresi: en yavaş liste olarak kalır (renkli panele inmez), bütçe kutusu renkli panel, Eksiklerim duvar ekranında
//      kişisel okuma tetiklemez ("Denetle" yok, veri kalitesi takım ucu çağrılmaz).
// Her aşamada yerleşim: sayfa taşmaz, öğe ekran dışına çıkmaz, metin kırpılmaz, dokunmatikte hedefler ≥ 40 px.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { VIEWPORTS, json, pathIs, checkLayout, expectInViewport, recordRequests } from './support/layoutAudit.js'

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const LONG_PAGE = 'Kurumsal İnternet Şubesi — Ödeme Onay Sayfası (Dijital Kanallar / Bireysel ve Kurumsal Bankacılık)'
const PAGES = [
  { id: 701, name: LONG_PAGE, url: 'https://odeme.example.com/onay', active: true, status: 'SLOW', ok: true, response_ms: 7420, max_load_ms: 3000,
    breached_metrics: ['LOAD', 'TTFB', 'JS'], budget_defined: true, team_name: LONG_TEAM },
  { id: 702, name: 'Kampanya Sayfası', url: 'https://kampanya.example.com/', active: true, status: 'OK', ok: true, response_ms: 1840, max_load_ms: 3000,
    breached_metrics: [], budget_defined: true, team_name: 'Takım A' },
  { id: 703, name: 'Kart başvuru formu', url: 'https://kart.example.com/basvuru', active: true, status: 'DOWN', ok: false, response_ms: null, team_name: 'Takım A' },
  { id: 704, name: 'Yeni eklenen sayfa (henüz ölçülmedi)', url: 'https://yeni.example.com/', active: true, status: null, response_ms: null, team_name: 'Takım A' },
  { id: 705, name: 'Hakkımızda', url: 'https://www.example.com/hakkimizda', active: true, status: 'OK', ok: true, response_ms: 980, budget_defined: false, team_name: 'Takım A' },
  { id: 706, name: 'Duraklatılmış eski kampanya', url: 'https://eski.example.com/', active: false, status: 'OK', ok: true, response_ms: 12000, team_name: 'Takım A' },
]
const TYPES = ['pagespeed_slowest', 'pagespeed_over_budget', 'my_gaps']
const BASE = { v: 1, widgets: [{ id: 'data-quality', type: 'data_quality', size: 'sm' }] }
const WITH_ALL = { v: 1, widgets: [{ id: 'pagespeed-slowest', type: 'pagespeed_slowest', size: 'md' },
  { id: 'pagespeed-over-budget', type: 'pagespeed_over_budget', size: 'md' }, { id: 'my-gaps', type: 'my_gaps', size: 'sm' }] }

async function routes(page, dashboard) {
  await mockApi(page, { role: 'USER', globalAdmin: false, prefs: { dashboard },
    perms: { 'monitoring.read': { view: true }, 'data_quality.view': { view: true } } })
  // Profilde e-posta ve telefon YOK → Eksiklerim profil kategorisi 2
  await page.route(pathIs('/api/me'), json({
    success: true, username: 'demo', system_role: 'USER', global_admin: false, weekly_reports_visible: true,
    team_id: 1, team_name: 'Takım A', team_ids: [1], team_names: ['Takım A'], manage_team_ids: [],
    email: null, phone: '', tour: { status: 'dismissed', version: 99 },
  }))
  await page.route(pathIs('/api/monitoring/pagespeed'), (route) => (route.request().method() === 'GET'
    ? json({ success: true, data: PAGES })(route) : route.fallback()))
  const saved = []
  const warm = { on: false }
  await page.route(pathIs('/api/me/preferences'), (route) => {
    if (warm.on && route.request().method() === 'GET') return json({ success: true, prefs: { dashboard: WITH_ALL } })(route)
    if (route.request().method() === 'PUT') saved.push(JSON.parse(route.request().postData() || '{}'))
    return route.fallback()
  })
  return { saved, log: recordRequests(page), warm }
}

/**
 * Isınma: üç kutunun ve eksikler okumasının tembel parçaları bir kez yüklenir. Vite geliştirme sunucusu yeni bir bağımlılığı
 * ilk istekte ön-paketleyip sayfayı yeniden yükleyebilir; bu yeniden yükleme ölçülen akışın ortasına değil buraya düşsün.
 */
async function warmUp(page, warm) {
  warm.on = true
  await page.goto('/?tab=mydash')
  await expect(page.locator('[data-slot="mydash-widget"][data-type="pagespeed_slowest"] [data-slot="mydash-psp-list"] > li').first())
    .toBeVisible({ timeout: 30_000 })
  // Düzenleme kipi dışında takılan Eksiklerim kutusu okumayı kendisi ister (özet yoksa bir kez)
  await expect(page.locator('[data-slot="mydash-gaps"]')).toBeVisible({ timeout: 30_000 })
  await page.locator('[data-slot="mydash-gaps-total"]').click()
  await expect(page.locator('[data-slot="gaps-dialog"]')).toHaveAttribute('data-status', 'ready', { timeout: 30_000 })
  await page.locator('[data-slot="gaps-close"]').click()
  warm.on = false
}

const widget = (page, type) => page.locator(`[data-slot="mydash-widget"][data-type="${type}"]`)

for (const vp of VIEWPORTS) {
  test.describe(`Panom katalog kutuları — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('Kutu ekle → üç kutu kaydedilir; veriyle çizim, Eksiklerim "Denetle", pencere ve gezinme; taşma / kırpma yok', async ({ page }) => {
      test.setTimeout(150_000)
      const { saved, log, warm } = await routes(page, BASE)
      await warmUp(page, warm)
      saved.length = 0
      await page.goto('/?tab=mydash')
      log.length = 0
      const root = page.locator('[data-slot="mydash-page"]')
      await expect(root.locator('[data-slot="mydash-widget"]')).toHaveCount(1, { timeout: 20_000 })

      // ── Düzenleme kipi: katalogdan üç kutu ──
      await page.locator('[data-slot="mydash-edit"]').click()
      await page.locator('[data-slot="mydash-add"]').click()
      const cat = page.locator('[data-slot="mydash-catalog"]')
      for (const type of TYPES) await expect(cat.locator(`[data-slot="mydash-catalog-item"][data-type="${type}"]`)).toBeVisible()
      await expectInViewport(page.getByRole('dialog'), vp, `katalog @${vp.name}`)
      await checkLayout(page, `katalog @${vp.name}`, '[role="dialog"]:has([data-slot="mydash-catalog"])')
      for (const type of TYPES) {
        await cat.locator(`[data-slot="mydash-catalog-item"][data-type="${type}"]`).getByRole('button').click()
        await expect(cat.locator(`[data-slot="mydash-catalog-item"][data-type="${type}"]`)).toHaveCount(0)
      }
      await expect.poll(() => saved.at(-1)?.dashboard?.widgets?.map((w) => w.type))
        .toEqual(['data_quality', 'pagespeed_slowest', 'pagespeed_over_budget', 'my_gaps'])
      await page.keyboard.press('Escape')
      await expect(cat).toHaveCount(0)
      // Düzenleme kipinde Eksiklerim kişisel okuma tetiklemez (pencere / ağ yok)
      await page.waitForTimeout(800)
      expect(log.filter((r) => r.path.startsWith('/api/data-quality/teams/')), 'düzenleme kipinde eksikler okundu').toEqual([])
      await checkLayout(page, `düzenleme kipi @${vp.name}`, '[data-slot="mydash-page"]')
      await page.locator('[data-slot="mydash-edit"]').click()
      await expect(root).toHaveAttribute('data-edit', 'off')

      // ── En yavaş sayfalar ──
      const slowest = widget(page, 'pagespeed_slowest')
      await expect(slowest.locator('[data-slot="mydash-psp-slowest"]')).toContainText('7.4', { timeout: 20_000 })
      await expect(slowest.locator('[data-slot="mydash-psp-list"] > li')).toHaveCount(3, { timeout: 20_000 })   // ölçülen 3 (alınamayan / ölçülmemiş / duraklatılmış yok)
      await expect(slowest.locator('[data-slot="mydash-psp-list"] > li').first()).toContainText(LONG_PAGE)
      await expect(slowest.locator('[data-slot="mydash-psp-measured"]')).toContainText('3')
      await expect(slowest.locator('[data-slot="mydash-psp-down"]')).toContainText('1')
      await expect(slowest.locator('[data-slot="mydash-psp-unknown"]')).toContainText('1')
      await expect(slowest).toHaveAttribute('data-tone', 'danger')
      // ── Bütçeyi aşan sayfalar ──
      const over = widget(page, 'pagespeed_over_budget')
      await expect(over.locator('[data-slot="mydash-psp-over"]')).toContainText('1')
      await expect(over.locator('[data-slot="mydash-psp-ok"]')).toContainText('1')
      await expect(over.locator('[data-slot="mydash-psp-over-list"] > li')).toHaveCount(1)
      await expect(over.locator('[data-slot="mydash-psp-over-list"] > li')).toContainText(LONG_PAGE)
      await expect(over).toHaveAttribute('data-tone', 'warning')
      expect(log.some((r) => r.path === '/api/monitoring/pagespeed'), 'sayfa hızı listesi okunmalı').toBe(true)

      // ── Eksiklerim: "Denetle" → okuma → profil 2; sayıya dokununca pencere ──
      const gaps = widget(page, 'my_gaps')
      await expect(gaps.locator('[data-slot="mydash-gaps-idle"]')).toBeVisible()
      await expect(gaps).toHaveAttribute('data-tone', 'muted')
      await gaps.locator('[data-slot="mydash-gaps-check"]').click()
      await expect(gaps.locator('[data-slot="mydash-gaps"]')).toBeVisible({ timeout: 20_000 })
      await expect(gaps.locator('[data-slot="mydash-gaps-profile"]')).toContainText('2')
      expect(Number(await gaps.locator('[data-slot="mydash-gaps"]').getAttribute('data-total'))).toBeGreaterThanOrEqual(2)
      expect(['danger', 'warning']).toContain(await gaps.getAttribute('data-tone'))
      expect(log.some((r) => r.path === '/api/data-quality/teams/1'), 'eksikler takım okuması').toBe(true)
      await page.waitForTimeout(300)
      await checkLayout(page, `kutular @${vp.name}`, '[data-slot="mydash-page"]')
      const boxOverflow = await page.evaluate(() => [...document.querySelectorAll('[data-slot="mydash-widget"]')]
        .filter((w) => w.scrollWidth > w.clientWidth + 1).map((w) => w.getAttribute('data-type')))
      expect(boxOverflow, 'kutu içi taşma').toEqual([])

      await gaps.locator('[data-slot="mydash-gaps-total"]').click()
      const dialog = page.locator('[data-slot="gaps-dialog"]')
      await expect(dialog).toHaveAttribute('data-status', 'ready', { timeout: 20_000 })
      await page.locator('[data-slot="gaps-close"]').click()
      await expect(dialog).toHaveCount(0)

      // ── Satır izlemenin ayrıntısına gider ──
      await slowest.locator('[data-slot="mydash-psp-list"] > li').first().getByRole('button').click()
      await expect(page).toHaveURL(/tab=pagespeed/)
      await expect(page).toHaveURL(/monitor=701/)
    })

    test('TV hücresi: en yavaş liste, bütçe renkli panel, Eksiklerim kişisel okuma tetiklemez', async ({ page }) => {
      test.setTimeout(120_000)
      const { log } = await routes(page, WITH_ALL)
      await page.goto('/?tab=mydash&md_tv=1')
      const tv = page.locator('[data-slot="mydash-tv"]')
      await expect(tv.locator('[data-slot="mydash-widget"]').first()).toBeVisible({ timeout: 20_000 })
      for (const type of TYPES) await expect(tv.locator(`[data-slot="mydash-widget"][data-type="${type}"]`)).toHaveCount(1)
      const slowest = tv.locator('[data-slot="mydash-widget"][data-type="pagespeed_slowest"]')
      await expect(slowest).not.toHaveAttribute('data-stat', 'on')   // sıralama tek sayıya inmez
      await expect(slowest.locator('[data-slot="mydash-psp-list"] > li').first()).toContainText(LONG_PAGE)
      const over = tv.locator('[data-slot="mydash-widget"][data-type="pagespeed_over_budget"]')
      await expect(over).toHaveAttribute('data-stat', 'on')
      await expect(over.locator('[data-slot="mydash-stat-value"]')).toHaveText('1')
      await expect(over).toHaveAttribute('data-tone', 'warning')
      // Eksiklerim TV'de tek hücrelik panel: okunmamış özet "henüz denetlenmedi" (gri), "Denetle" düğmesi yok
      const gaps = tv.locator('[data-slot="mydash-widget"][data-type="my_gaps"]')
      await expect(gaps).toContainText(/Not checked yet|Henüz denetlenmedi/)
      await expect(gaps).toHaveAttribute('data-tone', 'muted')
      await expect(gaps.locator('[data-slot="mydash-gaps-check"]')).toHaveCount(0)
      // TV'de bağlantı yok
      await expect(tv.locator('[data-slot="mydash-widget"][data-type^="pagespeed_"] [data-link="true"]')).toHaveCount(0)
      await page.waitForTimeout(1000)
      expect(log.filter((r) => r.path.startsWith('/api/data-quality/teams/')), 'duvar ekranında kişisel okuma').toEqual([])
      const fit = await page.evaluate(() => {
        const m = document.querySelector('[data-slot="mydash-tv"] main')
        const g = m.querySelector('[data-slot="mydash-tv-page"][data-active="true"]')
        return Math.round(g.getBoundingClientRect().bottom - m.getBoundingClientRect().bottom)
      })
      expect(fit, `TV sayfası taşıyor @${vp.name}`).toBeLessThanOrEqual(1)
      // TV katmanı `position: fixed` — sidebar-inset ana alanının `overflow` kırpması ona işlemez; ölçünün "atada kırpılma"
      // kuralı sabit konumu göremediği için o atadan gelen kayıtlar yok sayılır (kendi kutusunda kırpılma yine ölçülür).
      await checkLayout(page, `TV @${vp.name}`, '[data-slot="mydash-tv"]', { allowClip: [/\[ancestor \(main\[sidebar-inset\]/] })
      await page.keyboard.press('Escape')
      await expect(tv).toHaveCount(0)
    })
  })
}
