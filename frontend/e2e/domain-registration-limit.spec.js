import { test, expect } from '@playwright/test'
import { mockApi, MONITORS } from './support/monitorMocks.js'

// Alan adı izleme → "Domain Kaydı" sekmesi — canlı sorgu sınırı (2026-11-17, inceleme: 429 RATE_LIMITED akışı e2e'de
// görünmüyordu). Telefon (390, dokunmatik), tablet (768, dokunmatik), dizüstü (1280): "Yenile" 429 aldığında sunucunun
// metni bant olarak çıkar (ekrandaki kayıt bilgisi KORUNUR — "anlık sorgu başarısız" denmez), Yenile bekleme süresi
// boyunca kapalı kalır, süre dolunca yeniden açılır ve ikinci basış canlı sorguyu yeniden gönderir; bant sığar, metin
// kırpılmaz, Yenile dokunmatikte ≥ 40 px. API mock'lu.
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]
const MON = { ...MONITORS.domain[0], can_check: true, checked_at: new Date(Date.now() - 3600_000).toISOString().slice(0, 19) }
const LIMIT_TEXT = 'Bu alan adı için dakikada en çok 6 canlı sorgu yapılabilir; 3 saniye sonra yeniden deneyin (sorgular izleme başına sayılır).'
const WAIT_S = 3

function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('span, p, li, h3, h4, label, button, dt, dd')]
    .filter((n) => !n.closest('.sr-only') && !n.closest('pre'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 12
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')).map((n) => n.textContent.slice(0, 40))
}

const pageOverflow = () => document.documentElement.scrollWidth - document.documentElement.clientWidth

for (const vp of VIEWPORTS) {
  test.describe(`Domain Kaydı canlı sorgu sınırı — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('429: sunucu metni bant olarak, kayıt korunur, Yenile bekleme süresince kapalı ve sonra yeniden açılır', async ({ page }) => {
      test.setTimeout(120_000)
      await mockApi(page, { role: 'ADMIN', globalAdmin: true, monitors: { ...MONITORS, domain: [MON, ...MONITORS.domain.slice(1)] } })
      const live = []
      await page.route((u) => new URL(u).pathname === `/api/monitoring/domain/${MON.id}/registration`, (route) => {
        const isLive = new URL(route.request().url()).searchParams.get('live') === 'true'
        if (isLive) {
          live.push(Date.now())
          return route.fulfill({ status: 429, contentType: 'application/json', headers: { 'Retry-After': String(WAIT_S) },
            body: JSON.stringify({ success: false, code: 'RATE_LIMITED', error: LIMIT_TEXT, retry_after_seconds: WAIT_S }) })
        }
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: MON }) })
      })
      await page.route((u) => new URL(u).pathname === `/api/monitoring/domain/${MON.id}/reminders`, (route) => route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: { thresholds: [30, 14, 7], items: [] } }) }))

      await page.goto(`/?tab=domain&monitor=${MON.id}&mtab=registration`)
      const reg = page.locator('[data-slot="domain-registration"]')
      await expect(reg).toBeVisible({ timeout: 20_000 })
      const refresh = reg.locator('[data-slot="domain-reg-refresh"]')
      await expect(refresh).toBeEnabled()
      expect(live).toHaveLength(0)   // açılış canlı sorgu yapmaz

      await refresh.click()
      const banner = reg.locator('[data-slot="domain-reg-limited"]')
      await expect(banner).toBeVisible()
      await expect(banner).toContainText('dakikada en çok 6 canlı sorgu')
      expect(live).toHaveLength(1)
      // Kayıt bilgisi ekranda KALIR (sınır bir hata değil): "anlık sorgu başarısız" bandı çıkmaz, son sorgu satırı durur
      await expect(reg.locator('[data-slot="domain-reg-lookup"]')).toBeVisible()
      await expect(reg.locator('[data-slot="domain-reg-section"]').first()).toBeVisible()
      await expect(refresh).toBeDisabled()

      await page.waitForTimeout(300)
      expect(await page.evaluate(pageOverflow), `@${vp.name}: yatay taşma`).toBeLessThanOrEqual(1)
      const box = await banner.boundingBox()
      expect(box.x).toBeGreaterThanOrEqual(-1)
      expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
      expect(await page.evaluate(clipped, '[data-slot="domain-reg-limited"]'), `@${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) {
        const h = await refresh.evaluate((b) => b.getBoundingClientRect().height)
        expect(h, `@${vp.name}: Yenile dokunma hedefi`).toBeGreaterThanOrEqual(39.5)
      }
      await page.screenshot({ path: `test-results/domain-reg-limited-${vp.name}.png`, fullPage: false })

      // Bekleme süresi dolunca Yenile yeniden açılır; ikinci basış canlı sorguyu YENİDEN gönderir
      await expect(refresh).toBeEnabled({ timeout: (WAIT_S + 12) * 1000 })
      expect(Date.now() - live[0]).toBeGreaterThanOrEqual(WAIT_S * 1000 - 500)
      await refresh.click()
      await expect.poll(() => live.length).toBe(2)
      await expect(banner).toBeVisible()
    })
  })
}
