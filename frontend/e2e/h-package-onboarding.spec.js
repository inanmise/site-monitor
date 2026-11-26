// H paketi (2026-11-26, deneyim 3 + 15) — başlangıç listesi, sayfa turu çipi ve karşılama kartı gerçek tarayıcıda üç boyutta
// (390 / 768 dokunmatik, 1280):
//  - başlangıç listesi kullanıcının YAPAMAYACAĞI maddeyi göstermez ve saymaz: Haftalık Raporlar kapalıysa "report",
//    takımın sertifikası olmadığı KESİNSE "card" yok — aktif VE pasif liste okununca (inceleme bulgusu 8): yalnız pasif kartı
//    olan takımda "card" görünür; aktif ya da pasif liste okunamadıysa bilinmiyor → "card" gösterilir;
//  - liste, sayfa turu çipi ve karşılama kartının düğmeleri dokunmatikte ≥ 40 px; metin kesilmez, sayfa yatay kaymaz.
// API tümüyle mock — gerçek kişi / kurum adı yok.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { CERTS } from './support/certMocks.js'
import { GATE_VIEWPORTS, expectLayout, expectInViewport, json } from './support/gateChecks.js'

const meBody = (tour, weekly) => ({
  success: true, username: 'demo', system_role: 'ADMIN', global_admin: true, weekly_reports_visible: weekly,
  team_id: 1, team_name: 'Takım A', team_ids: [1], team_names: ['Takım A'], tour,
})

/** Sunucu tur durumu (`/api/me` tour + POST /api/me/tour birleştirir); sertifika listesi: some | none | fail; pasif liste:
 *  none | some | fail. */
async function mockTour(page, { tour, weekly = true, certs = 'some', paused = 'none' }) {
  await mockApi(page)
  // mockApi yerel aynaya "dismissed" yazar — bu testlerde sunucunun tur durumu geçerli olsun (ayna boş)
  await page.addInitScript(() => { try { localStorage.removeItem('sm.tour') } catch { /* yoksay */ } })
  let state = tour
  await page.route((u) => new URL(u).pathname === '/api/me', (r) => r.fulfill(json(meBody(state, weekly))))
  await page.route((u) => new URL(u).pathname === '/api/me/tour', async (r) => {
    let patch = {}
    try { patch = JSON.parse(r.request().postData() || '{}') } catch { /* boş */ }
    const cur = state || {}
    const next = { ...cur, ...patch, checklist: { ...(cur.checklist || {}), ...(patch.checklist || {}) } }
    if (patch.seen_page) next.seen_pages = [...(cur.seen_pages || []), patch.seen_page]
    state = next
    return r.fulfill(json({ success: true, tour: state }))
  })
  await page.route((u) => new URL(u).pathname === '/api/certificates', (r) => (certs === 'fail'
    ? r.fulfill(json({ success: false, error: 'Sertifika listesi okunamadı' }, 500))
    : r.fulfill(json({ success: true, data: certs === 'none' ? [] : CERTS, timestamp: new Date().toISOString() }))))
  await page.route((u) => new URL(u).pathname === '/api/certificates/paused', (r) => (paused === 'fail'
    ? r.fulfill(json({ success: false, error: 'Pasif sertifika listesi okunamadı' }, 500))
    : r.fulfill(json({ success: true, data: paused === 'some' ? [{ ...CERTS[0], paused: true, active: false }] : [] }))))
}

const DONE_TOUR = { status: 'completed', version: 99, checklist: { tour: true }, seen_pages: [] }
const items = (page) => page.locator('[data-slot="onboarding-checklist"] [data-checklist-item]')

for (const vp of GATE_VIEWPORTS) {
  test.describe(`H paketi başlangıç — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('başlangıç listesi: rapor modülü kapalı + sertifika yok → "report" ve "card" yok, sayaç 1/4', async ({ page }) => {
      test.setTimeout(90_000)
      await mockTour(page, { tour: DONE_TOUR, weekly: false, certs: 'none' })
      await page.goto('/?tab=dashboard')
      const list = page.locator('[data-slot="onboarding-checklist"]')
      await expect(list).toBeVisible({ timeout: 30_000 })
      await expect(items(page)).toHaveCount(4)
      expect(await items(page).evaluateAll((els) => els.map((e) => e.getAttribute('data-checklist-item'))))
        .toEqual(['tour', 'all', 'monitor', 'help'])
      await expect(list.locator('[data-slot="onboarding-checklist-count"]')).toHaveText('1/4')
      await expectInViewport(list, vp.width, 'başlangıç listesi')
      await expectLayout(page, '[data-slot="onboarding-checklist"]', { touch: vp.touch, label: `başlangıç listesi @${vp.name}` })
    })

    test('başlangıç listesi: rapor modülü açık + sertifika var → altı madde, sayaç 1/6', async ({ page }) => {
      test.setTimeout(90_000)
      await mockTour(page, { tour: DONE_TOUR, weekly: true, certs: 'some' })
      await page.goto('/?tab=dashboard')
      await expect(page.locator('[data-slot="onboarding-checklist"]')).toBeVisible({ timeout: 30_000 })
      await expect(items(page)).toHaveCount(6)
      await expect(page.locator('[data-slot="onboarding-checklist-count"]')).toHaveText('1/6')
      await expectLayout(page, '[data-slot="onboarding-checklist"]', { touch: vp.touch, label: `başlangıç listesi (6) @${vp.name}` })
    })

    test('başlangıç listesi: sertifika listesi okunamadı → bilinmiyor, "card" gösterilir (bilinmeyen ≠ yok)', async ({ page }) => {
      test.setTimeout(90_000)
      await mockTour(page, { tour: DONE_TOUR, weekly: false, certs: 'fail' })
      await page.goto('/?tab=dashboard')
      await expect(page.locator('[data-slot="onboarding-checklist"]')).toBeVisible({ timeout: 30_000 })
      await expect(page.locator('[data-checklist-item="card"]')).toHaveCount(1)
      await expect(page.locator('[data-checklist-item="report"]')).toHaveCount(0)
      await expect(page.locator('[data-slot="onboarding-checklist-count"]')).toHaveText('1/5')
    })

    test('başlangıç listesi: aktif liste boş ama PASİF kartı var → "card" görünür (sertifikası var), sayaç 1/5', async ({ page }) => {
      test.setTimeout(90_000)
      await mockTour(page, { tour: DONE_TOUR, weekly: false, certs: 'none', paused: 'some' })
      await page.goto('/?tab=dashboard')
      await expect(page.locator('[data-slot="onboarding-checklist"]')).toBeVisible({ timeout: 30_000 })
      // Pasif kart panoda çizildi — aynı anda "card" maddesi de görünür (eskiden aktif boş gelince gizleniyordu)
      await expect(page.locator(`[data-slot="cert-grid"] [data-slot="card"][data-domain="${CERTS[0].domain}"]`)).toBeVisible({ timeout: 30_000 })
      await expect(page.locator('[data-checklist-item="card"]')).toHaveCount(1)
      await expect(page.locator('[data-slot="onboarding-checklist-count"]')).toHaveText('1/5')
      await expectLayout(page, '[data-slot="onboarding-checklist"]', { touch: vp.touch, label: `başlangıç listesi pasif kart @${vp.name}` })
    })

    test('başlangıç listesi: aktif liste boş, PASİF liste okunamadı → bilinmiyor, "card" gösterilir', async ({ page }) => {
      test.setTimeout(90_000)
      await mockTour(page, { tour: DONE_TOUR, weekly: false, certs: 'none', paused: 'fail' })
      await page.goto('/?tab=dashboard')
      await expect(page.locator('[data-slot="onboarding-checklist"]')).toBeVisible({ timeout: 30_000 })
      await page.waitForTimeout(800)   // her iki liste de yerleşti — karar değişmez
      await expect(page.locator('[data-checklist-item="card"]')).toHaveCount(1)
      await expect(page.locator('[data-slot="onboarding-checklist-count"]')).toHaveText('1/5')
    })

    test('sayfa turu çipi: düğmeler dokunmatikte ≥ 40 px, kapatınca görülen sayfalara yazılır', async ({ page }) => {
      test.setTimeout(90_000)
      await mockTour(page, { tour: DONE_TOUR, weekly: true, certs: 'some' })
      await page.goto('/?tab=all')
      const chip = page.locator('[data-slot="tour-page-chip"]')
      await expect(chip).toBeVisible({ timeout: 30_000 })
      await expectInViewport(chip, vp.width, 'sayfa turu çipi')
      await expectLayout(page, '[data-slot="tour-page-chip"]', { touch: vp.touch, label: `sayfa turu çipi @${vp.name}` })
      const tourPost = page.waitForRequest((r) => new URL(r.url()).pathname === '/api/me/tour' && (r.postData() || '').includes('seen_page'))
      await chip.getByRole('button', { name: /Turu kapat|Close the tour/ }).click()
      await expect(chip).toHaveCount(0)
      expect(JSON.parse((await tourPost).postData())).toMatchObject({ seen_page: 'all' })
    })

    test('karşılama kartı (ilk giriş): düğmeler dokunmatikte ≥ 40 px; "Bir daha gösterme" kalıcı kapatır', async ({ page }) => {
      test.setTimeout(90_000)
      await mockTour(page, { tour: null, weekly: true, certs: 'some' })
      await page.goto('/?tab=dashboard')
      const card = page.getByRole('dialog', { name: /Site Monitör'e hoş geldiniz|Welcome to Site Monitor/ })
      await expect(card).toBeVisible({ timeout: 30_000 })
      await expectInViewport(card, vp.width, 'karşılama kartı')
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `karşılama kartı @${vp.name}` })
      const tourPost = page.waitForRequest((r) => new URL(r.url()).pathname === '/api/me/tour')
      await card.getByRole('button', { name: /Bir daha gösterme|Don't show again/ }).click()
      await expect(card).toHaveCount(0)
      expect(JSON.parse((await tourPost).postData())).toMatchObject({ status: 'dismissed' })
    })
  })
}
