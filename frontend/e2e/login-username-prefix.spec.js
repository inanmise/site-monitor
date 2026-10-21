import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

// 2026-10-21 (kullanıcı: "kullanıcılar iki ayrı username ile login olabiliyor — biri N, diğeri MP ile başlıyor; yalnız N ile
// başlayana izin verelim, harfleri ayarlardan yöneteyim"): Ayarlar → Güvenlik → Giriş Yöntemleri → "Kullanıcı adı ön eki" kartı
// ve giriş sayfası reddi telefon (390, dokunmatik), tablet (768, dokunmatik) ve dizüstünde (1280). Kart: çipler + canlı önizleme
// (60+ karakterlik görünen adlar SARAR), geçersiz ön ek alanın altında, yatay taşma yok, dokunmatikte hedefler ≥ 40 px, kayıt
// onayı + YALNIZ değişen alanlar. Giriş: ipucu kullanıcı adı alanının altında, MP… reddi izinli ön eki adlandırır, taşma yok.
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

const LONG_NAME = 'Mehmet Ali Pınarbaşıoğlu (Dijital Kanallar Mobil Bankacılık Uygulama Geliştirme Müdürlüğü)'
const LONG_EXEMPT = 'SERVIS.HESABI.DIJITAL.KANALLAR.MOBIL.BANKACILIK.ENTEGRASYON'

const json = (body, status = 200) => (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

function adminPayload(prefix) {
  return {
    success: true,
    data: {
      settings: {
        ldap_enabled: true, push_enabled: false, email_enabled: true, push_ttl_seconds: 45, email_ttl_seconds: 60,
        max_attempts: 3, resend_cooldown_seconds: 30, max_requests_per_user: 5, max_requests_per_ip: 20,
        max_failed_verifications: 5, allow_global_admins: false, push_require_phone: true, email_require_email: true,
        max_contact_mismatches: 5,
        username_prefix_enabled: prefix.enabled, username_prefix_allowed: prefix.allowed, username_prefix_exempt: prefix.exempt,
      },
      limits: { ttl: [30, 300], max_attempts: [1, 10], resend_cooldown: [10, 300], max_requests_per_user: [1, 20],
        max_requests_per_ip: [1, 500], max_failed_verifications: [1, 20], max_contact_mismatches: [1, 20], window_minutes: 15 },
      status: { push_gateway_configured: false, smtp: { host: 'smtp.example.com', configured: true }, ldap_integration_enabled: true },
      public: { ldap: true, otp_push: false, otp_email: true },
      coverage: { active_users: 120, with_phone: 90, with_email: 118 },
      activity: [],
      activity_types: [],
      username_prefix: { ...prefix, active: prefix.enabled, bootstrap_admin: 'admin',
        limits: { prefix_max_len: 10, max_prefixes: 20, exempt_max_len: 100, max_exempt: 50, sample_limit: 50 } },
    },
  }
}

const PREVIEW = {
  success: true,
  data: {
    allowed: ['N'], exempt: [LONG_EXEMPT], active_users: 240, exempt_users: 2, refused: 3, allowed_users: 237, truncated: false,
    self_refused: false, sample_limit: 50,
    sample: [
      { username: 'MP100234', display_name: LONG_NAME, auth_source: 'LDAP' },
      { username: 'MP100235', display_name: 'Ayşe Kara', auth_source: 'LDAP' },
      { username: 'X1', display_name: null, auth_source: 'LOCAL' },
    ],
  },
}

async function settingsRoutes(page) {
  await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yok */ } })
  await mockApi(page, { role: 'ADMIN', globalAdmin: true })
  const saves = []
  let prefix = { enabled: false, allowed: ['N'], exempt: [LONG_EXEMPT] }
  await page.route((u) => new URL(u).pathname === '/api/admin/login-methods', (route) => {
    if (route.request().method() === 'PUT') {
      const body = JSON.parse(route.request().postData() || '{}').settings || {}
      saves.push(body)
      prefix = {
        enabled: 'username_prefix_enabled' in body ? body.username_prefix_enabled : prefix.enabled,
        allowed: body.username_prefix_allowed || prefix.allowed,
        exempt: body.username_prefix_exempt || prefix.exempt,
      }
      return json({ ...adminPayload(prefix), message: 'Giriş yöntemleri kaydedildi' })(route)
    }
    return json(adminPayload(prefix))(route)
  })
  saves.previews = []
  await page.route((u) => new URL(u).pathname === '/api/admin/login-methods/username-prefix/preview', (route) => {
    saves.previews.push(new URL(route.request().url()).searchParams.get('allowed'))
    return json(PREVIEW)(route)
  })
  return saves
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, input:not([type="hidden"])')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled) continue
    let h = r.height
    if (b.getAttribute('role') === 'switch') h = Math.max(h, b.parentElement.getBoundingClientRect().height)
    const af = getComputedStyle(b, '::after')
    if (af.position === 'absolute' && af.content !== 'none') {
      const top = parseFloat(af.top) || 0, bottom = parseFloat(af.bottom) || 0
      h = Math.max(h, r.height - Math.min(0, top) - Math.min(0, bottom))
    }
    if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || b.id || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('span, p, li, h5, label, button')]
    .filter((n) => !n.closest('.sr-only'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 12
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')).map((n) => n.textContent.slice(0, 30))
}

const overflowX = () => document.documentElement.scrollWidth - document.documentElement.clientWidth

for (const vp of VIEWPORTS) {
  test.describe(`kullanıcı adı ön eki — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('Ayarlar kartı: çipler + önizleme sığar ve sarar, geçersiz ön ek alanın altında, onaylı kayıt yalnız değişeni yollar', async ({ page }) => {
      test.setTimeout(120_000)
      const saves = await settingsRoutes(page)
      await page.goto('/?tab=settings&sec=loginmethods')
      const card = page.locator('[data-slot="lm-prefix"]')
      await expect(card).toBeVisible({ timeout: 20_000 })
      await card.scrollIntoViewIfNeeded()
      const preview = card.locator('[data-slot="lm-prefix-preview"]')
      await expect(preview).toHaveAttribute('data-state', 'ready', { timeout: 10_000 })
      await expect(card.locator('[data-slot="lm-prefix-refused"]')).toHaveAttribute('data-count', '3')
      await expect(card.locator('[data-slot="lm-prefix-user"]').first()).toContainText(LONG_NAME)
      await expect(card.locator('[data-slot="token-chip"][data-value="N"]')).toBeVisible()
      await expect(card.locator(`[data-slot="token-chip"][data-value="${LONG_EXEMPT}"]`)).toBeVisible()

      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="lm-prefix"]'), `@${vp.name}: kırpılan metin`).toEqual([])
      const longUser = card.locator('[data-slot="lm-prefix-user"]').first()
      const userBox = await longUser.boundingBox()
      const cardBox = await card.boundingBox()
      expect(userBox.x + userBox.width, `@${vp.name}: uzun ad kartın içinde`).toBeLessThanOrEqual(cardBox.x + cardBox.width + 1)
      expect(await longUser.evaluate((el) => el.scrollWidth - el.clientWidth), `@${vp.name}: uzun ad sarar`).toBeLessThanOrEqual(1)
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="lm-prefix"]'), `@${vp.name}: küçük dokunma hedefi`).toEqual([])

      // Geçersiz ön ek: alanın altında söylenir, çip olmaz
      const allowedInput = card.locator('[data-field="username_prefix_allowed"] input')
      await allowedInput.fill('Ç1')
      await allowedInput.press('Enter')
      await expect(card.locator('[data-field="username_prefix_allowed"]')).toContainText('Geçersiz ön ek')
      await allowedInput.fill('mp')
      await allowedInput.press('Enter')
      await expect(card.locator('[data-slot="token-chip"][data-value="MP"]')).toBeVisible()
      // önizleme yeni değerlerle yeniden sorulur (bekleyip) — onay penceresi kişi sayısını ancak o zaman bilir
      await expect.poll(() => saves.previews.includes('N,MP'), { timeout: 10_000 }).toBe(true)
      await expect(preview).toHaveAttribute('data-state', 'ready')

      // Kuralı aç + kaydet → onay (kişi sayısıyla) → yalnız değişen ön ek alanları gider
      await card.getByRole('switch').click()
      await page.getByRole('button', { name: /^Kaydet$/ }).click()
      const dlg = page.getByRole('dialog')
      await expect(dlg).toContainText(/aktif kullanıcı/)
      await dlg.getByRole('button', { name: 'Kuralı uygula' }).click()
      await expect.poll(() => saves.length).toBe(1)
      expect(saves[0].username_prefix_enabled).toBe(true)
      expect(saves[0].username_prefix_allowed).toEqual(['N', 'MP'])
      expect(saves[0]).not.toHaveProperty('username_prefix_exempt')
      await page.waitForTimeout(200)
      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma (kayıt sonrası)`).toBeLessThanOrEqual(1)
    })

    test('giriş sayfası: ipucu alanın altında; MP… reddi izinli ön eki adlandırır; taşma yok', async ({ page }) => {
      await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yok */ } })
      await page.route((u) => new URL(u).pathname.startsWith('/api/'), json({ success: true, data: [] }))
      await page.route((u) => new URL(u).pathname === '/api/me', json({ success: false, error: 'Not authenticated' }, 401))
      await page.route((u) => new URL(u).pathname === '/api/public/login-methods',
        json({ success: true, ldap: true, otp_push: false, otp_email: false, username_prefix: { allowed: ['N'] } }))
      let logins = 0
      await page.route((u) => new URL(u).pathname === '/api/login', (route) => {
        logins++
        return json({ success: false, code: 'USERNAME_PREFIX_NOT_ALLOWED', error_code: 'USERNAME_PREFIX_NOT_ALLOWED',
          error: 'Bu kullanıcı adıyla giriş yapılamaz.', allowed_prefixes: ['N'] }, 403)(route)
      })
      await page.goto('/')
      await page.waitForSelector('#lp-user')
      await expect(page.locator('[data-slot="login-prefix-hint"]')).toContainText('N ile başlayan')
      await page.fill('#lp-user', 'MP100234')
      await page.fill('#lp-pass', 'parola')
      await page.locator('form.lp-form').evaluate((f) => f.requestSubmit())
      const alert = page.locator('[data-code="USERNAME_PREFIX_NOT_ALLOWED"]')
      await expect(alert).toBeVisible()
      await expect(alert).toContainText('Yalnız N ile başlayan')
      expect(logins).toBe(1)
      const box = await alert.boundingBox()
      expect(box.x).toBeGreaterThanOrEqual(0)
      expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma`).toBeLessThanOrEqual(1)
    })

    test('oturum kapatıldıktan sonra (?session=prefix) giriş sayfası bildirimi', async ({ page }) => {
      await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yok */ } })
      await page.route((u) => new URL(u).pathname.startsWith('/api/'), json({ success: true, data: [] }))
      await page.route((u) => new URL(u).pathname === '/api/me', json({ success: false, error: 'Not authenticated' }, 401))
      await page.route((u) => new URL(u).pathname === '/api/public/login-methods',
        json({ success: true, ldap: true, otp_push: false, otp_email: false, username_prefix: { allowed: ['N'] } }))
      await page.goto('/?session=prefix')
      const notice = page.locator('[data-slot="login-username-prefix"]')
      await expect(notice).toContainText('N ile başlayan')
      await expect(page.getByText('Oturumunuzun süresi doldu')).toHaveCount(0)
      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma`).toBeLessThanOrEqual(1)
    })
  })
}
