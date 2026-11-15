// Giriş ve oturum yaşam döngüsü uçtan uca (yayın öncesi kapı, 2026-11-15).
//
//  1) Parola girişi → 409 ACTIVE_SESSION_EXISTS ("başka yerde açık oturum") onayı → aynı bilgilerle `force_login: "true"`
//     → uygulama açılır; ardından oturumlu bir istek 401 alır → `/?session=expired` ve giriş sayfasında süre doldu bildirimi.
//  2) Çıkış sırasında `/logout` başarısız → istemci temizliği YİNE yapılır, `sm.logout.pending` yazılır, giriş formu açılır;
//     sayfa yenilenince /me SORULMAZ (çerez oturumu geri getirmez), bekleyen çıkış yeniden denenir.
//  3) Pasif hesap (401 ACCOUNT_INACTIVE): kapatılamaz geri sayımlı pencere, sonra `/?session=inactive` + bildirim.
// Telefon (390, dokunmatik), tablet (768, dokunmatik), dizüstü (1280): taşma yok, metin kesilmez, dokunma hedefleri ≥ 40 px.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { GATE_VIEWPORTS, expectLayout, json } from './support/gateChecks.js'

/** Uygulama açık: Sertifika Panosu başlığındaki sertifika sayısı (kullanıcı menüsü telefonda çekmece kapalıyken yok). */
const APP_READY = '[data-slot="dash-cert-count"]'

/**
 * Giriş sayfasında dokunmatikte 40 px altı kalan hedefler — ÜRÜN HATASI, raporlandı (yayın öncesi kapı 2026-11-15):
 * dil düğmesi 32 px (`pages/Login.jsx` `lp-lang-btn`, size="sm"), parola göster / gizle 32 px (`lp-eye`, size="icon-sm"),
 * "Sistem yöneticisine bildirin" bağlantı düğmesi 19 px (`lp-help-link`, kendi satırında — satır içi metin istisnası değil).
 */
const LOGIN_KNOWN_SMALL = [/"(Türkçe|English)" \d+x32$/, /"(Show|Hide) password" 32x32$/, /"Report it to the system admini" \d+x19$/]

const ME = {
  success: true, username: 'demo', display_name: 'Demo Kullanıcı', system_role: 'ADMIN', global_admin: true, weekly_reports_visible: true,
  team_id: 1, team_name: 'Takım A', team_ids: [1], team_names: ['Takım A'], tour: { status: 'dismissed', version: 99 },
}

/**
 * mockApi üstüne oturum durumu: `state.authed` false iken /me 401; `state.expire` 'expired' | 'inactive' olunca her oturumlu
 * istek 401 (ACCOUNT_INACTIVE gövdesiyle ya da gövdesiz). Giriş / çıkış istekleri kaydedilir.
 */
async function sessionRoutes(page, state) {
  await mockApi(page)
  state.logins = []
  state.meCalls = 0
  state.logoutCalls = 0
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    if (p === '/api/public/login-methods') return route.fulfill(json({ success: true, ldap: true, otp_push: false, otp_email: false }))
    if (p === '/api/login' && req.method() === 'POST') {
      const body = JSON.parse(req.postData() || '{}')
      state.logins.push(body)
      if (body.force_login !== 'true') {
        return route.fulfill(json({ success: false, error_code: 'ACTIVE_SESSION_EXISTS', error: 'Active session elsewhere' }, 409))
      }
      state.authed = true
      return route.fulfill(json(ME))
    }
    if (p === '/api/logout') {
      state.logoutCalls++
      if (state.logoutFails) return route.fulfill(json({ success: false, error: 'Service unavailable' }, 503))
      state.authed = false
      return route.fulfill(json({ success: true }))
    }
    if (p === '/api/me') {
      state.meCalls++
      if (!state.authed) return route.fulfill(json({ success: false, error: 'Not authenticated' }, 401))
      return route.fulfill(json(ME))
    }
    const open = /^\/api\/(public|branding|public-stats|session\/leave)/.test(p)
    if (state.expire && !open) {
      const body = state.expire === 'inactive'
        ? { success: false, code: 'ACCOUNT_INACTIVE', error_code: 'ACCOUNT_INACTIVE', error: 'Account inactive' }
        : { success: false, error: 'Session superseded' }
      return route.fulfill(json(body, 401))
    }
    return route.fallback()
  })
}

/** Dokunmatik telefonda kullanıcı menüsü çekmecenin içinde. */
async function openUserMenu(page, vp) {
  if (vp.width < 768) await page.locator('[data-sidebar="trigger"]').first().click()
  await page.locator('[data-slot="user-menu-trigger"]').filter({ visible: true }).first().click()
}

for (const vp of GATE_VIEWPORTS) {
  test.describe(`giriş ve oturum — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('409 başka yerde oturum → onay → force_login → uygulama; sonra 401 → /?session=expired', async ({ page }) => {
      test.setTimeout(120_000)
      const state = { authed: false }
      await sessionRoutes(page, state)
      await page.goto('/')
      await page.waitForSelector('#lp-user', { timeout: 60_000 })
      await expectLayout(page, 'body', { touch: vp.touch, allowSmall: LOGIN_KNOWN_SMALL, label: `giriş sayfası @${vp.name}` })
      await page.fill('#lp-user', 'demo')
      await page.fill('#lp-pass', 'Parola.2026')
      await page.locator('form.lp-form').evaluate((f) => f.requestSubmit())

      const confirm = page.getByRole('alertdialog', { name: 'Active Session Elsewhere' })
      await expect(confirm).toBeVisible({ timeout: 15_000 })
      await expect(confirm).toContainText('If you continue, that session will be closed')
      expect(state.logins).toHaveLength(1)
      expect(state.logins[0]).toMatchObject({ username: 'demo', password: 'Parola.2026', force_login: 'false' })
      await expectLayout(page, 'body', { touch: vp.touch, allowSmall: LOGIN_KNOWN_SMALL, label: `oturum onayı @${vp.name}` })

      await page.locator('[data-slot="login-session-confirm"]').click()
      await expect.poll(() => state.logins.length).toBe(2)
      expect(state.logins[1]).toMatchObject({ username: 'demo', password: 'Parola.2026', force_login: 'true' })
      // Uygulama açıldı (kullanıcı menüsü), giriş formu yok
      await expect(page.locator(APP_READY)).toBeVisible({ timeout: 60_000 })
      await expect(page.locator('#lp-user')).toHaveCount(0)
      expect(await page.evaluate(() => sessionStorage.getItem('sm.session.active'))).toBe('1')

      // Oturum sunucuda düştü: oturumlu istek 401 → sert yönlendirme /?session=expired
      state.expire = 'expired'
      state.authed = false
      // Arka plan yoklaması yönlendirmeyi önce başlatabilir — tıklama yalnız tetikleyicidir
      await page.getByRole('button', { name: 'Refresh' }).first().click({ timeout: 5_000 }).catch(() => {})
      await page.waitForURL(/[?&]session=expired/, { timeout: 60_000 })
      state.expire = null
      await page.waitForSelector('#lp-user', { timeout: 60_000 })
      await expect(page.getByText('Your session has expired. Please sign in again.')).toBeVisible()
      await expectLayout(page, 'body', { touch: vp.touch, allowSmall: LOGIN_KNOWN_SMALL, label: `süre doldu bildirimi @${vp.name}` })
    })

    test('çıkışta /logout başarısız: bekleyen işaret yazılır, giriş formu açılır, yenileme oturumu geri getirmez', async ({ page }) => {
      test.setTimeout(120_000)
      const state = { authed: true, logoutFails: true }
      await sessionRoutes(page, state)
      await page.goto('/?tab=dashboard')
      await expect(page.locator(APP_READY)).toBeVisible({ timeout: 60_000 })
      await openUserMenu(page, vp)
      await page.locator('[data-slot="user-menu-logout"]').filter({ visible: true }).first().click()
      const dlg = page.getByRole('dialog', { name: 'Logout' })
      await expect(dlg).toBeVisible()
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `çıkış onayı @${vp.name}` })
      await dlg.getByRole('button', { name: 'Logout', exact: true }).click()

      // İstemci temizliği HER DURUMDA: giriş formu, bekleyen işaret, kullanıcıya bildirim
      await page.waitForSelector('#lp-user', { timeout: 60_000 })
      expect(state.logoutCalls).toBeGreaterThanOrEqual(1)
      expect(await page.evaluate(() => localStorage.getItem('sm.logout.pending'))).toBeTruthy()
      expect(await page.evaluate(() => sessionStorage.getItem('sm.session.active'))).toBeNull()
      await expect(page.getByText(/the server could not be reached/)).toBeVisible()
      await expectLayout(page, 'body', { touch: vp.touch, allowSmall: LOGIN_KNOWN_SMALL, label: `çıkış sonrası giriş @${vp.name}` })

      // Yenile: /me SORULMAZ (çerez oturumu geri getirmez), çıkış yeniden denenir, giriş formu kalır
      const meBefore = state.meCalls
      const logoutBefore = state.logoutCalls
      await page.reload()
      await page.waitForSelector('#lp-user', { timeout: 60_000 })
      await page.waitForTimeout(1500)
      expect(state.meCalls, 'bekleyen çıkışla açılışta /me sorulmaz').toBe(meBefore)
      expect(state.logoutCalls, 'açılışta bekleyen çıkış yeniden denenir').toBeGreaterThan(logoutBefore)
      await expect(page.locator(APP_READY)).toHaveCount(0)
      expect(await page.evaluate(() => localStorage.getItem('sm.logout.pending'))).toBeTruthy()

      // Sunucu yeniden ulaşılınca çıkış onaylanır: sonraki açılışta işaret kalkar, /me ancak o zaman sorulur
      state.logoutFails = false
      await page.reload()
      await page.waitForSelector('#lp-user', { timeout: 60_000 })
      await expect.poll(() => page.evaluate(() => localStorage.getItem('sm.logout.pending')), { timeout: 10_000 }).toBeNull()
    })

    test('pasif hesap: kapatılamaz geri sayımlı pencere → /?session=inactive', async ({ page }) => {
      test.setTimeout(120_000)
      const state = { authed: true }
      await sessionRoutes(page, state)
      await page.goto('/?tab=dashboard')
      await expect(page.locator(APP_READY)).toBeVisible({ timeout: 60_000 })
      state.expire = 'inactive'
      await page.getByRole('button', { name: 'Refresh' }).first().click({ timeout: 5_000 }).catch(() => {})
      const dlg = page.locator('[data-slot="account-inactive-dialog"]')
      await expect(dlg).toBeVisible({ timeout: 15_000 })
      await expect(dlg).toContainText('Your account has been deactivated')
      const countdown = dlg.locator('[data-slot="account-inactive-countdown"]')
      const first = Number((await countdown.textContent()).match(/\d+/)[0])
      await expectLayout(page, '[data-slot="account-inactive-dialog"]', { touch: vp.touch, label: `pasif hesap penceresi @${vp.name}` })
      // Kapatılamaz: Escape pencereyi kapatmaz
      await page.keyboard.press('Escape')
      await expect(dlg).toBeVisible()
      await expect.poll(async () => Number((await countdown.textContent()).match(/\d+/)[0]), { timeout: 5_000 }).toBeLessThan(first)
      // Süre bitince giriş sayfasına (pasif bildirimi)
      state.expire = null
      state.authed = false
      await page.waitForURL(/[?&]session=inactive/, { timeout: 60_000 })
      await page.waitForSelector('#lp-user', { timeout: 60_000 })
      await expect(page.locator('[data-slot="login-account-inactive"]')).toBeVisible()
      await expectLayout(page, 'body', { touch: vp.touch, allowSmall: LOGIN_KNOWN_SMALL, label: `pasif bildirimi @${vp.name}` })
    })
  })
}
