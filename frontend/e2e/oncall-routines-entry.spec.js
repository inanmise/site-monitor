// Nöbet Rutinleri — GİRİŞ NOKTALARI (2026-11-26). Sayfanın kendisi `oncall-routines.spec.js`'te; burada sayfaya giden her yol
// gerçek tarayıcıda 390 (dokunmatik) / 768 (dokunmatik) / 1280'de:
//  1) Ayarlar → "On-call routines" bölümü (telefonda seçici, tablette şerit, masaüstünde yan gezinti): üç anahtar, saklama notu,
//     takım özeti (aktif / kurulmamış + modül kapalı / duraklatılmış); bir anahtar değişince kayıt gövdesi YALNIZ o alan.
//  2) Kişisel Panom "My on-call routine" kutusu: nöbetçisi olduğum takım önce, ilerleme / gecikme / sorun / devir rozetleri,
//     kutu tonu; takım satırı Nöbet Rutinleri sayfasını o takımla açar.
//  3) Menü rozeti: kapalı "Reports" bölüm başlığında bekleyen görev sayısı (4, kritik), bölüm açılınca sekme satırında;
//     satır sayfayı açar.
//  4) Komut paleti (Ctrl+K / kenar çubuğu / telefon üst çubuğu): "routines" yazınca sayfa bulunur ve açılır.
//  5) Açılış sekmesi tercihi `oncallroutines` → adres parametresiz açılışta uygulama doğrudan rutin sayfasına gider.
// Her adımda (layoutAudit): sayfa yatay kaymaz, ad / uzun metin kırpılmaz, dokunmatikte hedefler ≥ 40 px. Tam sayfa görüntüsü
// EN SONDA alınır (fullPage görüntüsü Chromium'un dokunmatik öykünmesini sıfırlar).
import { test, expect } from '@playwright/test'
import { EN } from '../src/i18n/en.js'
import { mockApi, PREFS } from './support/monitorMocks.js'
import { VIEWPORTS, checkLayout, expectInViewport } from './support/layoutAudit.js'
import {
  mockOncallRoutines, OR_TEAM_ID, OR_TEAM_NAME, OR_OTHER_TEAM_ID, OR_TODAY, OR_NOW, OR_SELIN,
} from './support/oncallRoutineMocks.js'
import {
  mockOncallRoutineEntry, ORE_BADGE, ORE_MY_DAY, ORE_RETENTION_DAYS,
  ORE_DONE_TEAM_ID, ORE_DONE_TEAM_NAME, ORE_OFF_TEAM_ID, ORE_OFF_TEAM_NAME, ORE_PAUSED_TEAM_ID, ORE_PAUSED_TEAM_NAME,
} from './support/oncallRoutineEntryMocks.js'

const PAGE = '[data-slot="or-page"]'
const ORS = '[data-slot="ors-root"]'
const WIDGET = '[data-slot="mydash-widget"][data-type="oncall_routine"]'
const PALETTE = '[data-command-palette]'
/** Kapalı / açık "Reports" bölümünün menü öğesi (bölüm başlığı + alt sekmeler) — yerleşim ölçüsünün kökü. */
const REPORTS_ITEM = 'li[data-sidebar="menu-item"]:has([data-nav-section-trigger="reports"])'


/** EN sözlükten metin; `{0}` `{1}` … yer tutucuları sırayla doldurulur. Anahtar yoksa test hemen düşer. */
function tx(key, ...args) {
  const s = EN[key]
  if (typeof s !== 'string') throw new Error(`EN key missing: ${key}`)
  return args.reduce((acc, a, i) => acc.split(`{${i}}`).join(String(a)), s)
}
const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/**
 * Saat sabit (İstanbul 11:41), dil İngilizce, genel sahte API + rutin sahte sunucusu + giriş noktası ek uçları; sayfa açılır.
 * `storage`: açılıştan önce yazılacak / silinecek (null) localStorage anahtarları.
 */
async function boot(page, { prefs = PREFS, url = '/?tab=dashboard', storage = {} } = {}) {
  await page.clock.setFixedTime(new Date(OR_NOW))
  await page.addInitScript((kv) => {
    try {
      localStorage.setItem('site-monitor-lang', 'en')
      for (const [k, v] of Object.entries(kv)) { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v) }
    } catch { /* depolama yok */ }
  }, storage)
  await mockApi(page, { prefs })
  const st = await mockOncallRoutines(page)
  const entry = await mockOncallRoutineEntry(page)   // SONRA bağlanır → `/me` buradan
  await page.goto(url)
  return { st, entry }
}

/** Rutin sayfası açıldı mı: adres + varsayılan takımın bugünü yüklü. */
async function expectRoutinesOpen(page) {
  await expect(page).toHaveURL(/[?&]tab=oncallroutines(&|$)/, { timeout: 30_000 })
  await expect(page.locator(`${PAGE}[data-team="${OR_TEAM_ID}"]`)).toBeVisible({ timeout: 30_000 })
  await expect(page.locator(`[data-slot="or-day"][data-date="${OR_TODAY}"]`)).toBeVisible({ timeout: 30_000 })
}

/** Test sonu: tanınmayan rutin ucu yok + tam sayfa görüntüsü (EN SONDA). */
async function finish(page, st, name) {
  expect(st.unknown, 'mock: unrecognised on-call routine endpoints').toEqual([])
  await page.screenshot({ path: test.info().outputPath(`${name}.png`), fullPage: true })
}

/** Telefonda kenar çubuğu çekmecesini açar; dönen konum menünün görünür kenar çubuğudur. */
async function openSidebar(page, vp) {
  await page.locator('.app-main').waitFor({ timeout: 30_000 })
  if (vp.width < 768) {
    await page.locator('[data-slot="mobile-top-bar"] [data-sidebar="trigger"]').click()
    await expect(page.locator('[data-mobile="true"][data-slot="sidebar"]')).toBeVisible()
  }
  return page.locator('[data-slot="sidebar"]').filter({ has: page.locator('[data-nav-section-trigger="reports"]') }).last()
}

for (const vp of VIEWPORTS) {
  test.describe(`Nöbet rutinleri giriş noktaları — ${vp.name} (${vp.width})`, () => {
    test.use({
      viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch, timezoneId: 'Europe/Istanbul',
    })

    test('Ayarlar → Nöbet rutinleri: bölüm açılır ve sığar; anahtar değişince yalnız o alan kaydedilir', async ({ page }) => {
      test.setTimeout(120_000)
      const { st, entry } = await boot(page, { url: '/?tab=settings' })
      await expect(page.locator('[data-slot="settings-page"]')).toBeVisible({ timeout: 30_000 })

      // Bölüm seçimi ekran genişliğine göre farklı denetimden (telefon seçici / tablet şerit / masaüstü yan gezinti)
      if (vp.width < 768) {
        await page.locator('[data-slot="settings-picker"] select').selectOption('oncallroutines')
      } else if (vp.width < 1024) {
        await page.locator('[data-slot="settings-rail"] [data-id="oncallroutines"]').click()
      } else {
        const nav = page.locator('[data-slot="settings-nav"] [data-id="oncallroutines"]')
        await expect(nav).toHaveText(new RegExp(escapeRe(tx('settings.navOncallRoutines'))))
        await nav.click()
      }
      await expect(page).toHaveURL(/[?&]sec=oncallroutines(&|$)/)

      const root = page.locator(ORS)
      await expect(root).toBeVisible({ timeout: 30_000 })
      await expect(root).toContainText(tx('or.adm.title'))
      // Kayıtlı değerler okundu: üç anahtar bilinen değerde (bilinmeyen = kilitli olurdu)
      await expect(root.locator('[data-slot="ors-sw-reminders"]')).toHaveAttribute('data-on', 'true')
      await expect(root.locator('[data-slot="ors-sw-completion"]')).toHaveAttribute('data-on', 'false')
      await expect(root.locator('[data-slot="ors-sw-handover"]')).toHaveAttribute('data-on', 'true')
      for (const slot of ['ors-sw-reminders', 'ors-sw-completion', 'ors-sw-handover']) {
        await expect(root.locator(`[data-slot="${slot}"]`)).not.toHaveAttribute('data-unknown', 'true')
      }
      await expect(root.locator('[data-slot="ors-retention"]')).toContainText(tx('or.adm.retention', ORE_RETENTION_DAYS))
      await expect(root.locator('[data-slot="ors-everyone"]')).toHaveCount(0)   // takım seçili → "herkes" notu yok

      // Takım özeti: dört satır, adlar tam, durumlar
      const teams = root.locator('[data-slot="ors-teams"]')
      await expect(teams).toHaveAttribute('data-status', 'ok')
      await expect(root.locator('[data-slot="ors-team-row"]')).toHaveCount(4)
      const row = (id) => root.locator(`[data-slot="ors-team-row"][data-team="${id}"]`)
      await expect(row(OR_TEAM_ID).locator('[data-slot="ors-team-name"]')).toHaveText(OR_TEAM_NAME)
      await expect(row(OR_TEAM_ID).locator('[data-slot="ors-state"]')).toHaveAttribute('data-state', 'active')
      await expect(row(OR_TEAM_ID).locator('[data-slot="ors-today"]')).toHaveAttribute('data-status', 'OPEN')
      await expect(row(OR_TEAM_ID).locator('[data-slot="ors-pct"]')).toHaveAttribute('data-tone', 'success')   // 19 / 21 = %90
      await expect(row(OR_OTHER_TEAM_ID).locator('[data-slot="ors-state"]')).toHaveAttribute('data-state', 'none')
      await expect(row(OR_OTHER_TEAM_ID).locator('[data-slot="ors-hidden"]')).toBeVisible()
      await expect(row(ORE_DONE_TEAM_ID).locator('[data-slot="ors-team-name"]')).toHaveText(ORE_DONE_TEAM_NAME)
      await expect(row(ORE_DONE_TEAM_ID).locator('[data-slot="ors-today"]')).toHaveAttribute('data-status', 'COMPLETED')
      await expect(row(ORE_PAUSED_TEAM_ID).locator('[data-slot="ors-team-name"]')).toHaveText(ORE_PAUSED_TEAM_NAME)
      await expect(row(ORE_PAUSED_TEAM_ID).locator('[data-slot="ors-state"]')).toHaveAttribute('data-state', 'paused')
      await expect(row(ORE_PAUSED_TEAM_ID).locator('[data-slot="ors-pct"]')).toHaveAttribute('data-unknown', 'true')   // iş günü yok

      // Modül görünürlüğü seçicisi: seçili takım adları KIRPILMAZ (CLAUDE.md "Names and long text are never clipped"). Dört
      // gerçek uzunlukta AD adı tetikte yan yana — `MultiTeamSelect` tetiği `truncate` ise ad kesilir. Yumuşak doğrulama: kusur
      // testi düşürür ama sayfanın geri kalanı (kayıt akışı) yine ölçülür; ortak yerleşim kapısı yalnız BU etiketi muaf tutar.
      const picker = root.locator('[data-slot="ors-teams-field"] [role="combobox"]')
      await expect(picker).toBeVisible()
      for (const nm of [OR_TEAM_NAME, ORE_DONE_TEAM_NAME, ORE_PAUSED_TEAM_NAME, ORE_OFF_TEAM_NAME]) await expect(picker).toContainText(nm)
      const pickerClip = await picker.locator('span').first().evaluate((el) => ({ sw: el.scrollWidth, cw: el.clientWidth }))
      expect(pickerClip.sw,
        `settings ${vp.name}: selected team names are cut in the visibility picker (${pickerClip.sw} > ${pickerClip.cw}px)`)
        .toBeLessThanOrEqual(pickerClip.cw + 1)

      const saveBar = root.locator('[data-slot="settings-save-bar"]')
      await expect(saveBar).not.toHaveAttribute('data-dirty', 'true')
      await checkLayout(page, `settings ${vp.name}`, ORS)

      // Anahtar → kaydedilmemiş; kayıt gövdesi YALNIZ değişen alan
      await page.getByRole('switch', { name: tx('or.adm.sw.completion_notice'), exact: true }).click()
      await expect(root.locator('[data-slot="ors-sw-completion"]')).toHaveAttribute('data-on', 'true')
      await expect(saveBar).toHaveAttribute('data-dirty', 'true')
      await checkLayout(page, `settings dirty ${vp.name}`, ORS)
      await saveBar.getByRole('button', { name: tx('settings.save'), exact: true }).click()
      await expect.poll(() => entry.puts.length, { timeout: 15_000 }).toBe(1)
      expect(entry.puts[0]).toEqual({ completion_notice: true })
      await expect(page.getByText(tx('or.adm.saved')).first()).toBeVisible()
      await expect(saveBar).not.toHaveAttribute('data-dirty', 'true')
      await expect(root.locator('[data-slot="ors-sw-completion"]')).toHaveAttribute('data-on', 'true')
      expect(entry.settingsGets, 'settings read once').toBeGreaterThanOrEqual(1)
      expect(entry.overviewGets, 'overview read').toBeGreaterThanOrEqual(1)

      await finish(page, st, `entry-settings-${vp.name}`)
    })

    test('Kişisel Panom "My on-call routine": satırlar, rozetler, ton; takım satırı rutin sayfasını açar', async ({ page }) => {
      test.setTimeout(120_000)
      const prefs = { ...PREFS, dashboard: { v: 1, widgets: [{ id: 'or-routine', type: 'oncall_routine', size: 'md' }] } }
      const { st, entry } = await boot(page, { prefs, url: '/?tab=mydash' })

      const widget = page.locator(WIDGET)
      await expect(widget).toBeVisible({ timeout: 30_000 })
      await expect(widget).toContainText(tx('mydash.w.oncall_routine'))
      const rows = widget.locator('[data-slot="mydash-or-team"]')
      await expect(rows).toHaveCount(3)
      // Nöbetçisi olduğum takım önce; gecikme → kırmızı ton
      await expect(rows.first()).toHaveAttribute('data-team', String(OR_TEAM_ID))
      await expect(widget).toHaveAttribute('data-tone', 'danger')
      await expect(widget).toContainText(tx('mydash.or.overdueCount', ORE_MY_DAY.overdue))

      const mine = widget.locator(`[data-slot="mydash-or-team"][data-team="${OR_TEAM_ID}"]`)
      await expect(mine).toHaveAttribute('data-tone', 'danger')
      await expect(mine.locator('[data-slot="mydash-or-team-name"]')).toHaveText(OR_TEAM_NAME)
      const facts = mine.locator('[data-slot="mydash-or-facts"]')
      await expect(facts).toContainText(tx('mydash.or.progress', ORE_MY_DAY.resolved, ORE_MY_DAY.required))
      await expect(facts).toContainText(tx('mydash.or.overdue', ORE_MY_DAY.overdue))
      await expect(facts).toContainText(tx('mydash.or.problems', ORE_MY_DAY.problems_open))
      await expect(mine.locator('[data-slot="mydash-or-me"]')).toHaveText(tx('mydash.or.youOnCall'))
      await expect(mine.locator('[data-slot="mydash-or-handover"]')).toHaveText(tx('mydash.or.handoverPending'))
      await expect(mine.locator('[data-slot="mydash-or-status"]')).toHaveAttribute('data-status', 'OPEN')
      await expect(mine.locator('[data-slot="mydash-or-bar"]')).toBeVisible()

      const done = widget.locator(`[data-slot="mydash-or-team"][data-team="${ORE_DONE_TEAM_ID}"]`)
      await expect(done).toHaveAttribute('data-tone', 'success')
      await expect(done.locator('[data-slot="mydash-or-team-name"]')).toHaveText(ORE_DONE_TEAM_NAME)
      await expect(done.locator('[data-slot="mydash-or-facts"]')).toContainText(tx('mydash.or.responsible', OR_SELIN.name))
      await expect(done.locator('[data-slot="mydash-or-me"]')).toHaveCount(0)
      await expect(done.locator('[data-slot="mydash-or-status"]')).toHaveAttribute('data-status', 'COMPLETED')

      // İş günü olmayan takım: gri, ilerleme çubuğu yok (bilinmeyen / iş günü değil asla yeşil değil)
      const off = widget.locator(`[data-slot="mydash-or-team"][data-team="${ORE_OFF_TEAM_ID}"]`)
      await expect(off).toHaveAttribute('data-tone', 'muted')
      await expect(off.locator('[data-slot="mydash-or-team-name"]')).toHaveText(ORE_OFF_TEAM_NAME)
      await expect(off.locator('[data-slot="mydash-or-facts"]')).toContainText(tx('mydash.or.notWorkday'))
      await expect(off.locator('[data-slot="mydash-or-bar"]')).toHaveCount(0)
      expect(entry.meCalls, '/api/oncall-routines/me read').toBeGreaterThanOrEqual(1)

      await checkLayout(page, `mydash widget ${vp.name}`, WIDGET)

      // Takım satırı → Nöbet Rutinleri sayfası o takımla
      await mine.locator('button[data-link="true"]').click()
      await expectRoutinesOpen(page)

      await finish(page, st, `entry-mydash-${vp.name}`)
    })

    test('menü rozeti: kapalı "Reports" başlığında bekleyen görev sayısı, açılınca sekme satırında; satır sayfayı açar', async ({ page }) => {
      test.setTimeout(120_000)
      const { st, entry } = await boot(page, {
        url: '/?tab=dashboard', storage: { 'nav-section-open': null, 'nav-subsection-open': null, 'sidebar-open': 'true' },
      })
      const sidebar = await openSidebar(page, vp)
      const reports = sidebar.locator('[data-nav-section-trigger="reports"]')
      await expect(reports).toBeVisible()
      await expect(reports).toHaveAttribute('aria-expanded', 'false')

      // Kapalı bölüm başlığı: rozet sunucunun sayısı + seviyesi, adı ekran okuyucuya kendi etiketiyle
      const sectionPill = reports.locator('[data-slot="nav-section-routine-count"]')
      await expect(sectionPill).toHaveText(String(ORE_BADGE.count), { timeout: 30_000 })
      await expect(sectionPill).toHaveAttribute('aria-label', tx('nav.oncallRoutinesPending', ORE_BADGE.count))
      await expect(sectionPill).toHaveAttribute('data-level', ORE_BADGE.level)
      expect(entry.meCalls, 'badge polls /me').toBeGreaterThanOrEqual(1)
      await checkLayout(page, `menu closed ${vp.name}`, REPORTS_ITEM)

      // Bölüm açılınca başlık rozeti kalkar (çift sayı yok), sekme satırının kendi rozeti görünür
      await reports.click()
      await expect(reports).toHaveAttribute('aria-expanded', 'true')
      await expect(sectionPill).toHaveCount(0)
      const tab = sidebar.locator('[data-tour="nav-tab-oncallroutines"]')
      await expect(tab).toBeVisible()
      await expect(tab).toContainText(tx('nav.oncallRoutines'))
      const tabPill = sidebar.locator('[data-tab="oncallroutines"] [data-slot="nav-routine-count"]')
      await expect(tabPill).toHaveText(String(ORE_BADGE.count))
      await expect(tabPill).toHaveAttribute('aria-label', tx('nav.oncallRoutinesPending', ORE_BADGE.count))
      await expect(tabPill).toHaveAttribute('data-level', ORE_BADGE.level)
      // Rozet sekme adının üstüne binmez: satır sağdan yer ayırır, rozet satırın içinde
      const tabBox = await tab.boundingBox()
      const pillBox = await tabPill.boundingBox()
      expect(tabBox && pillBox, 'tab row + pill boxes').toBeTruthy()
      expect(pillBox.x + pillBox.width, 'pill inside the row').toBeLessThanOrEqual(tabBox.x + tabBox.width + 1)
      const labelRight = await tab.evaluate((b) => {
        const span = [...b.querySelectorAll('span')].pop()
        const r = document.createRange(); r.selectNodeContents(span)
        return Math.max(...[...r.getClientRects()].map((q) => q.right))
      })
      expect(labelRight, 'tab label ends before the pill').toBeLessThanOrEqual(pillBox.x + 1)
      await checkLayout(page, `menu open ${vp.name}`, REPORTS_ITEM)

      await tab.click()
      await expectRoutinesOpen(page)
      if (vp.width < 768) await expect(page.locator('[data-mobile="true"][data-slot="sidebar"]')).toBeHidden()
      else await expect(tab).toHaveAttribute('aria-current', 'page')

      await finish(page, st, `entry-menu-${vp.name}`)
    })

    test('komut paleti: "routines" aranınca sayfa bulunur ve açılır', async ({ page }) => {
      test.setTimeout(120_000)
      const { st } = await boot(page, { url: '/?tab=dashboard' })
      await page.locator('.app-main').waitFor({ timeout: 30_000 })

      // Açma yolu ekrana göre: masaüstü Ctrl+K, tablet kenar çubuğu düğmesi, telefon üst çubuk düğmesi
      if (vp.width < 768) {
        await page.locator('[data-slot="mobile-top-bar"]').getByRole('button', { name: tx('palette.title'), exact: true }).click()
      } else if (vp.touch) {
        await page.locator('[data-tour="nav-search"]').click()
      } else {
        // Kısayol pencere düzeyinde dinlenir (odak nerede olursa olsun); bilerek hiçbir yere tıklanmaz
        await page.keyboard.press('Control+k')
      }
      const palette = page.locator(PALETTE)
      await expect(palette).toBeVisible({ timeout: 30_000 })
      await expect(palette).toHaveAttribute('data-layout', vp.width < 768 ? 'phone' : 'desktop')

      await palette.locator('input').first().fill('routines')
      const option = palette.getByRole('option', { name: new RegExp(`^${escapeRe(tx('nav.oncallRoutines'))}`) })
      await expect(option).toBeVisible({ timeout: 15_000 })
      await expect(option).toHaveCount(1)
      await expectInViewport(palette, vp, `palette ${vp.name}`)
      await checkLayout(page, `palette ${vp.name}`, PALETTE)

      await option.click()
      await expect(palette).toBeHidden()
      await expectRoutinesOpen(page)

      await finish(page, st, `entry-palette-${vp.name}`)
    })

    test('açılış sekmesi tercihi: parametresiz açılış doğrudan Nöbet Rutinleri sayfası', async ({ page }) => {
      test.setTimeout(120_000)
      const { st } = await boot(page, { prefs: { ...PREFS, landingTab: 'oncallroutines' }, url: '/' })
      const historyBefore = await page.evaluate(() => window.history.length)
      await expectRoutinesOpen(page)
      // Geçmişe kayıt eklenmez: adres yerinde değişir (Geri seçilmemiş Pano'ya dönmez)
      expect(await page.evaluate(() => window.history.length), 'landing uses replaceState').toBe(historyBefore)
      await expect(page.locator(`[data-slot="or-day"][data-date="${OR_TODAY}"]`)).toHaveAttribute('data-status', /.+/)
      // Menüde sekme etkin (masaüstü / tablet — telefonda menü çekmecede kapalı)
      if (vp.width >= 768) {
        await expect(page.locator('[data-slot="sidebar"] [data-tour="nav-tab-oncallroutines"]')).toHaveAttribute('aria-current', 'page')
      }
      await checkLayout(page, `landing ${vp.name}`, PAGE)

      await finish(page, st, `entry-landing-${vp.name}`)
    })
  })
}
