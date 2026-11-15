// Etkinliklerim (`?tab=myactivity`) — prod kapısı e2e paketi (2026-11-15). Gerçek uygulama rotası, API mock'lu (yalnız
// /api/). Telefon (390, dokunmatik), tablet (768, dokunmatik) ve dizüstünde (1280):
//   - zaman çizelgesi + yerleşim; giriş satırından "Bu girişi ben yapmadım" → POST {auditId} → "Hesabınızı koruyun?"
//     → "Hatırlanan girişleri temizle" → POST logout-others;
//   - Cihazlarım paneli: hatırlanan cihazı çıkış yaptır (DELETE /api/me/devices/remembered/{id}, liste tazelenir, satır
//     gider); giriş geçmişinden bildir → "Şimdi değil" (logout-others ÇAĞRILMAZ); tüm hatırlananları çıkış yaptır;
//   - `ma_sec=push` derin bağlantısı Bildirim tercihlerim kartını görünür kaydırır, odaklar ve adresten düşer.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { VIEWPORTS, json, pathIs, checkLayout, settle } from './support/layoutAudit.js'

const HOUR = 3_600_000
const DAY = 24 * HOUR
/** UTC ISO, ek yok — backend biçimi; koşu anına göreli. */
const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const WIN = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36'
// Sunucunun ürettiği gerçekçi özet ("işletim sistemi · tarayıcı"); cihaz adı satırı bilinçli tek satır (App.css .dev-row-name)
const LONG_DEVICE = 'iOS · Safari'

/**
 * BİLİNEN ÜRÜN HATALARI (prod kapısı raporunda; düzeltilince buradan çıkarılır):
 *  - Push bildirimlerim: mesaj `line-clamp-3` (myactivity/PushHistorySection.jsx:256) — tam metne ulaşan açılım / ipucu yok;
 *    uzun push metni (≤ 320 karakter) telefonda, tablette ve dizüstü tablosunda kesiliyor.
 *  - Bildirim tercihlerim "Tümünü seç" bağlantı düğmesi (myactivity/PushPreferencesCard.jsx:262) `max-sm:min-h-10` taşıyor,
 *    `pointer-coarse:` değil — 768 dokunmatik tablette 16 px yüksek.
 */
const KNOWN = {
  allowClip: [/span\[ph-message\]/],
  allowSmall: [/@push-prefs>card-content>field>button \d+×\d+ "Select all"/],
}
/**
 *  - Cihazlarım paneli (DeviceHistoryPanel.jsx) dokunmatikte 40 px altı düğmeler: hatırlanan cihaz "Çıkış yaptır" (:300 `size="sm"`,
 *    32 px), "Başarısız denemeler" açma düğmesi (:384 `h-auto py-1`, 32 px), "Tüm hatırlanan cihazlardan çıkış" (:428 `size="sm"`),
 *    "Parolamı değiştir" (:435 `size="sm"`), açılan giriş satırında "Bu girişi ben yapmadım" (:360 `size="xs"`, 24 px) ve
 *    "IP adresini kopyala" (:345 CopyButton varsayılan `icon-sm`, 32×32) — hiçbirinde `pointer-coarse:` boyutu yok.
 */
const KNOWN_SHEET = {
  allowSmall: [
    / \d+×3\d "[^"]*— Sign out"/,
    /@sheet-content>button \d+×3\d "(Failed sign-in attempts|Sign out all remembered device|Change my password)/,
    / \d+×24 "[^"]*— This wa/,   // etiket ölçüde 30 karakterde kesilir
    / 32×32 "Copy IP address"/,
  ],
}

let nextId = 100
function ev(msAgo, type, extra = {}) {
  return {
    id: nextId++, event_type: type, event_time: iso(msAgo), actor: 'demo', outcome: 'SUCCESS',
    ip_address: '203.0.113.24', ip_city: 'İstanbul', ip_country: 'Türkiye', ip_org: 'Example Broadband',
    user_agent: WIN, ua_summary: 'Windows · Chrome', resource_type: 'USER', resource_id: 'demo',
    detail: null, changes: null, failure_reason: null, anomaly_flags: null, correlation_id: 'c-0001', ...extra,
  }
}
function auditRows() {
  nextId = 100
  return [
    ev(1_000, 'LOGIN'),
    ev(2 * HOUR, 'MONITOR_UPDATE', { resource_type: 'HTTP_MONITOR', resource_id: '12', detail: 'raporlama-ve-analitik-platformu.ic-servisler.example.com',
      changes: JSON.stringify({ interval_seconds: { from: 300, to: 60 } }) }),
    ev(DAY + HOUR, 'LOGIN_FAILED', { outcome: 'FAILURE', failure_reason: "BAD_PASSWORD: attempt #1/5 for 'demo'",
      ip_address: '192.0.2.77', ip_city: 'Amsterdam', ip_country: 'Netherlands', user_agent: 'python-requests/2.32.3', ua_summary: null }),
    ev(2 * DAY, 'LOGOUT'),
    ev(3 * DAY, 'LOGIN', { anomaly_flags: 'UNUSUAL_IP', ip_address: '198.51.100.7', ip_city: 'Ankara', ua_summary: 'iOS · Safari' }),   // id 104
    ev(5 * DAY, 'SELF_PASSWORD_CHANGE', { resource_id: '42' }),
  ]
}
const SUSPICIOUS_ID = 104

/** Etkinlik + cihaz uçları; mockApi'den SONRA kaydedilir (önce gelir). */
async function activityMock(page) {
  await mockApi(page, { role: 'USER', globalAdmin: false })
  const state = { reports: [], revokes: [], logoutOthers: 0 }
  const events = auditRows()
  let remembered = [
    { id: 501, ua_summary: 'Windows · Chrome', device: 'desktop', city: 'İstanbul', country: 'Türkiye', last_used_at: iso(HOUR), is_this_device: true },
    { id: 502, ua_summary: LONG_DEVICE, device: 'mobile', city: 'Ankara', country: 'Türkiye', last_used_at: iso(3 * DAY), is_this_device: false },
  ]
  await page.route(pathIs('/api/me/audit'), (route) => {
    const q = new URL(route.request().url()).searchParams
    const eventType = q.get('eventType'); const outcome = q.get('outcome'); const since = q.get('since'); const until = q.get('until')
    const rows = events.filter((e) => (!eventType || e.event_type === eventType) && (!outcome || e.outcome === outcome)
      && (!since || e.event_time >= since) && (!until || e.event_time <= until))
    const page0 = Number(q.get('page') || 0); const size = Math.min(Number(q.get('size') || 50), 200)
    return json({ success: true, data: rows.slice(page0 * size, page0 * size + size), total: rows.length, page: page0, total_pages: Math.ceil(rows.length / size) })(route)
  })
  await page.route(pathIs('/api/me/devices'), (route) => json({ success: true, data: {
    current: { known: true, ua_summary: 'Windows · Chrome', device: 'desktop', ip: '203.0.113.24', city: 'İstanbul', country: 'Türkiye',
      login_at: iso(HOUR), last_seen_at: iso(60_000), remembered_on_this_device: true },
    remembered, retention_days: 365,
  } })(route))
  await page.route(pathIs('/api/me/devices/logins'), (route) => {
    const failed = new URL(route.request().url()).searchParams.get('failed') === 'true'
    const rows = failed ? [] : [
      { id: 701, at: iso(3 * DAY), ua_summary: LONG_DEVICE, device: 'mobile', ua_raw: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1',
        ip: '198.51.100.7', city: 'Ankara', country: 'Türkiye', org: 'Example Mobile Operator', anomaly_flags: ['UNUSUAL_IP'], outcome: 'SUCCESS' },
      { id: 702, at: iso(HOUR), ua_summary: 'Windows · Chrome', device: 'desktop', ua_raw: WIN, ip: '203.0.113.24', city: 'İstanbul', country: 'Türkiye',
        org: 'Example Broadband', anomaly_flags: [], outcome: 'SUCCESS' },
    ]
    return json({ success: true, data: { rows, total: rows.length, page: 0, total_pages: 1, retention_days: 365 } })(route)
  })
  await page.route((u) => /^\/api\/me\/devices\/remembered\/\d+$/.test(new URL(u).pathname), (route) => {
    const id = Number(new URL(route.request().url()).pathname.split('/').pop())
    state.revokes.push({ method: route.request().method(), id })
    remembered = remembered.filter((r) => r.id !== id)
    return json({ success: true })(route)
  })
  await page.route(pathIs('/api/me/devices/report-login'), (route) => {
    state.reports.push(JSON.parse(route.request().postData() || '{}'))
    return json({ success: true, ref: 'SM-2026-0042' })(route)
  })
  await page.route(pathIs('/api/me/devices/logout-others'), (route) => {
    state.logoutOthers++
    remembered = []
    return json({ success: true })(route)
  })
  return state
}

const toast = (page, re) => page.locator('[data-sonner-toast]').filter({ hasText: re })
const confirmDialog = (page, title) => page.getByRole('dialog').filter({ has: page.getByRole('heading', { name: title }) })

for (const vp of VIEWPORTS) {
  test.describe(`Etkinliklerim — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('zaman çizelgesi + "Bu girişi ben yapmadım" → bildir → hatırlanan girişleri temizle', async ({ page }) => {
      test.setTimeout(120_000)
      const state = await activityMock(page)
      await page.goto('/?tab=myactivity')
      const entries = page.locator('[data-slot="activity-entry"]')
      await expect(entries).toHaveCount(6, { timeout: 20_000 })
      await expect(page.locator('[data-slot="push-prefs"] [data-slot="push-snooze"]')).toBeVisible()
      await checkLayout(page, `myactivity@${vp.name}`, undefined, KNOWN)

      const suspicious = entries.filter({ has: page.locator('[data-flag="UNUSUAL_IP"]') })
      await expect(suspicious).toHaveCount(1)
      await suspicious.locator('button').first().click()
      const detail = suspicious.locator('[data-slot="activity-detail"]')
      await expect(detail).toBeVisible()
      await detail.getByRole('button', { name: /This was not me/ }).click()
      const report = confirmDialog(page, 'Report this sign-in?')
      await expect(report).toBeVisible()
      await checkLayout(page, `report dialog@${vp.name}`, '[role="dialog"]')
      await report.getByRole('button', { name: 'Report', exact: true }).click()
      await expect.poll(() => state.reports.length).toBe(1)
      expect(state.reports[0]).toEqual({ auditId: SUSPICIOUS_ID })
      await expect(toast(page, /Reported\. It is worth changing your password too\./)).toBeVisible()
      // Bildirimden sonra referans numarasıyla koruma önerisi; kabul → hatırlanan girişler temizlenir
      const protect = confirmDialog(page, 'Protect your account?')
      await expect(protect).toBeVisible()
      await expect(protect).toContainText('SM-2026-0042')
      expect(state.logoutOthers).toBe(0)
      await protect.getByRole('button', { name: 'Clear remembered sign-ins', exact: true }).click()
      await expect.poll(() => state.logoutOthers).toBe(1)
      await expect(toast(page, 'Remembered sign-ins have been cleared.')).toBeVisible()
      await expect(protect).toHaveCount(0)
    })

    test('Cihazlarım: hatırlanan cihazı çıkış yaptır, geçmişten bildir ("Şimdi değil"), tümünü çıkış yaptır', async ({ page }) => {
      test.setTimeout(150_000)
      const state = await activityMock(page)
      await page.goto('/?tab=myactivity')
      await page.locator('[data-slot="my-activity-header"]').getByRole('button', { name: 'My devices', exact: true }).click({ timeout: 20_000 })
      const sheet = page.getByRole('dialog').filter({ hasText: 'Device history' })
      await expect(sheet.locator('[data-slot="dev-card"]')).toBeVisible({ timeout: 20_000 })
      const rows = sheet.locator('li.dev-row')
      const longRow = rows.filter({ hasText: LONG_DEVICE }).filter({ has: page.getByRole('button', { name: /Sign out$/ }) })
      await expect(longRow).toHaveCount(1)
      await checkLayout(page, `devices sheet@${vp.name}`, '[role="dialog"]', KNOWN_SHEET)

      // Hatırlanan cihaz: onayla → DELETE → liste tazelenir, satır gider
      await longRow.getByRole('button', { name: `${LONG_DEVICE} — Sign out` }).click()
      const revoke = confirmDialog(page, 'Sign this device out?')
      await expect(revoke).toBeVisible()
      await expect(revoke).toContainText(LONG_DEVICE)
      await settle(page)   // açılış geçişi bitsin (düğme kararlı)
      await revoke.getByRole('button', { name: 'Sign out', exact: true }).click()
      await expect.poll(() => state.revokes.length).toBe(1)
      expect(state.revokes[0]).toEqual({ method: 'DELETE', id: 502 })
      await expect(toast(page, 'The device has been signed out.')).toBeVisible()
      await expect(longRow).toHaveCount(0)
      expect(state.logoutOthers).toBe(0)

      // Giriş geçmişi: satırı aç → bildir → koruma önerisini ertele (logout-others yok)
      const loginRow = rows.filter({ has: page.locator('[aria-expanded]') }).filter({ hasText: LONG_DEVICE })
      await loginRow.getByRole('button', { name: /show or hide details/ }).click()
      await expect(loginRow.locator('.dev-row-detail')).toContainText('198.51.100.7')
      await checkLayout(page, `devices sheet (row open)@${vp.name}`, '[role="dialog"]', KNOWN_SHEET)
      await loginRow.getByRole('button', { name: /This was not me/ }).click()
      await confirmDialog(page, 'Report this sign-in?').getByRole('button', { name: 'Report', exact: true }).click()
      await expect.poll(() => state.reports.length).toBe(1)
      expect(state.reports[0]).toEqual({ auditId: 701 })
      const protect = confirmDialog(page, 'Protect your account?')
      await expect(protect).toContainText('SM-2026-0042')
      await protect.getByRole('button', { name: 'Not now', exact: true }).click()
      await expect(protect).toHaveCount(0)
      await page.waitForTimeout(300)
      expect(state.logoutOthers, '"Şimdi değil" hatırlanan girişleri temizlemez').toBe(0)

      // Tüm hatırlanan girişler
      await sheet.getByRole('button', { name: 'Sign out all remembered devices', exact: true }).click()
      const all = confirmDialog(page, 'Sign out all remembered devices?')
      await expect(all).toBeVisible()
      await all.getByRole('button', { name: 'Sign out', exact: true }).click()
      await expect.poll(() => state.logoutOthers).toBe(1)
      await expect(toast(page, 'Remembered sign-ins have been cleared.')).toBeVisible()
      await expect(sheet.getByText('No remembered devices', { exact: false })).toBeVisible()
    })

    test('ma_sec=push derin bağlantısı: tercih kartı görünür kaydırılır, odaklanır, anahtar adresten düşer', async ({ page }) => {
      test.setTimeout(90_000)
      await activityMock(page)
      await page.goto('/?tab=myactivity&ma_sec=push')
      const anchor = page.locator('[data-slot="push-prefs-anchor"]')
      await expect(anchor.locator('[data-slot="push-snooze"]')).toBeVisible({ timeout: 20_000 })
      await expect.poll(() => page.evaluate(() => document.activeElement?.getAttribute('data-slot'))).toBe('push-prefs-anchor')
      await expect.poll(() => new URL(page.url()).searchParams.get('ma_sec')).toBeNull()
      expect(new URL(page.url()).searchParams.get('tab')).toBe('myactivity')
      // Kart görünüm alanına kaydırıldı (kartın bir kısmı ekranda). Not: kaydırma tercihler yüklenince BİR kez yapılır; 1280'de
      // yan sütunda kartın üstündeki kartlar (özet yüklenince) sonradan boy değiştirirse kart başlığı ~30 px yukarıda kalabiliyor
      // — prod kapısı raporunda düşük önemli bulgu; burada yalnız "ekranda" denetlenir.
      await expect.poll(async () => {
        const b = await anchor.boundingBox()
        return !!b && b.y + Math.min(b.height, 200) > 0 && b.y < vp.height - 100
      }, { timeout: 10_000 }).toBe(true)
      await checkLayout(page, `myactivity push deep link@${vp.name}`, undefined, KNOWN)
      // Yeniden yükleme: anahtar yok, kendiliğinden kaydırma / odak yok
      await page.reload()
      await expect(anchor.locator('[data-slot="push-snooze"]')).toBeVisible({ timeout: 20_000 })
      await page.waitForTimeout(500)
      expect(await page.evaluate(() => document.activeElement?.getAttribute('data-slot'))).not.toBe('push-prefs-anchor')
    })
  })
}
