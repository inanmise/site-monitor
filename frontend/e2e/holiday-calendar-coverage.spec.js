import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

// Ayarlar → Platform → Tatil takvimi — 10 yıllık kapsam (2026-11-17, inceleme: 2026–2035 resmî tatil verisi
// (HolidayCalendarTenYearFill) ve "takvim bitti" durumu e2e'de görünmüyordu). Telefon (390, dokunmatik), tablet (768,
// dokunmatik), dizüstü (1280): 10 yıllık takvimde kapsam satırı "ok" (son gün 2035) ve yıl düğmeleri her yılı gösterir,
// yıl süzgeci kendi kabında kayar (sayfa değil), uzun bayram adları kırpılmaz; takvim BİTTİYSE uyarı sığar ve "sabit
// tatilleri ekle" bu yılı POST eder, uyarı "ok"a döner. API mock'lu.
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]
const Y = new Date().getFullYear()
const LAST = '2035-12-03'
const LONG = 'Ramazan Bayramı 1. Gün · Cumhuriyet Bayramı Arifesi (yarım gün) — Diyanet Dini Günler tablosu ile doğrulanmıştır'
// 2026–2035 (HolidayCalendarDataset biçimi): yıl başına üç gün; son gün 2035-12-03 (Ramazan Bayramı)
const TEN_YEARS = []
for (let y = 2026; y <= 2035; y++) {
  TEN_YEARS.push({ day: `${y}-01-01`, name: 'Yılbaşı' })
  TEN_YEARS.push({ day: `${y}-04-23`, name: 'Ulusal Egemenlik ve Çocuk Bayramı' })
  TEN_YEARS.push({ day: `${y}-10-29`, name: y === 2035 ? LONG : 'Cumhuriyet Bayramı' })
}
TEN_YEARS.push({ day: LAST, name: 'Ramazan Bayramı 3. Gün' })

const json = (body, status = 200) => (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, select, input:not([type="hidden"]), textarea, [role="combobox"], [role="button"], a[href]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled) continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none' || b.getAttribute('aria-hidden') === 'true') continue
    let h = r.height
    const af = getComputedStyle(b, '::after')
    if (af.position === 'absolute' && af.content !== 'none') {
      const ah = parseFloat(af.height)
      if (Number.isFinite(ah)) h = Math.max(h, ah)
    }
    if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || b.id || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('span, p, li, h3, h4, label, button, dt, dd')]
    .filter((n) => !n.closest('.sr-only'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 12
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')).map((n) => n.textContent.slice(0, 40))
}

/** Görünüm alanının dışına taşan öğe — kendi kaydırma kabı içindeki (yıl süzgeci) sayılmaz. */
function offenders(sel) {
  const vw = document.documentElement.clientWidth
  const root = document.querySelector(sel) || document.body
  const out = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    const cs = getComputedStyle(el)
    if (r.width <= 1 || r.height <= 1 || cs.visibility === 'hidden' || cs.display === 'none') continue
    if (r.right <= vw + 1 && r.left >= -1) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (!contained) out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || ''}] ${Math.round(r.left)}..${Math.round(r.right)}`)
    if (out.length >= 8) break
  }
  return out
}

const pageOverflow = () => document.documentElement.scrollWidth - document.documentElement.clientWidth

for (const vp of VIEWPORTS) {
  test.describe(`Tatil takvimi kapsamı — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('10 yıllık takvim: kapsam "ok" (2035), her yıl bir düğme, uzun bayram adı sarar, sayfa yatay kaymaz', async ({ page }) => {
      test.setTimeout(120_000)
      await mockApi(page, { role: 'ADMIN', globalAdmin: true })
      await page.route((u) => new URL(u).pathname === '/api/admin/holidays', json({ success: true,
        data: { days: TEN_YEARS, can_edit: true, coverage_until: LAST, ends_soon: false } }))
      await page.goto('/?tab=settings&sec=holidays')
      const root = page.locator('[data-slot="holiday-settings"]')
      const cov = root.locator('[data-slot="holiday-coverage"]')
      await expect(cov).toHaveAttribute('data-state', 'ok', { timeout: 20_000 })
      await expect(cov).toContainText('2035')
      await expect(root.locator('[data-slot="holiday-add-fixed"]')).toHaveCount(0)
      // Yıl süzgeci: 2026 … 2035 (+ bu yıl ve gelecek yıl hep listede) + "Tümü"
      const years = new Set([Y, Y + 1])
      for (let y = 2026; y <= 2035; y++) years.add(y)
      const group = root.getByRole('group', { name: /^(Year|Yıl)$/ })
      await expect(group.getByRole('button')).toHaveCount(years.size + 1)
      await group.getByRole('button', { name: '2035' }).click()
      await expect(root.locator('[data-slot="holiday-row"]')).toHaveCount(4)
      await expect(root.locator('[data-slot="holiday-row"][data-day="2035-10-29"]')).toContainText(LONG)
      await expect(root.locator(`[data-slot="holiday-row"][data-day="${LAST}"]`)).toBeVisible()
      await group.getByRole('button', { name: /^(All|Tümü)$/ }).click()
      await expect(root.locator('[data-slot="holiday-row"]')).toHaveCount(TEN_YEARS.length)
      await page.waitForTimeout(300)
      expect(await page.evaluate(pageOverflow), `@${vp.name}: yatay taşma`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(offenders, '[data-slot="holiday-settings"]'), `@${vp.name}: ekran dışına taşan öğe`).toEqual([])
      // (ekleme formundaki tarih seçicinin yer tutucusu bu testin konusu değil — liste ve kapsam satırı ölçülür)
      expect(await page.evaluate(clipped, '[data-slot="holiday-list"]'), `@${vp.name}: kırpılan metin`).toEqual([])
      expect(await page.evaluate(clipped, '[data-slot="holiday-coverage"]'), `@${vp.name}: kapsam satırı kırpılıyor`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="holiday-list"]'), `@${vp.name}: küçük dokunma hedefi`).toEqual([])
      await page.screenshot({ path: `test-results/holiday-ten-years-${vp.name}.png`, fullPage: false })
    })

    test('takvim bitti: uyarı sığar; "sabit tatilleri ekle" bu yılı POST eder, uyarı "ok"a döner', async ({ page }) => {
      test.setTimeout(120_000)
      await mockApi(page, { role: 'ADMIN', globalAdmin: true })
      const posts = []
      let days = [{ day: `${Y - 1}-01-01`, name: 'Yılbaşı' }, { day: `${Y - 1}-10-29`, name: 'Cumhuriyet Bayramı' }]
      let until = `${Y - 1}-10-29`
      await page.route((u) => new URL(u).pathname === '/api/admin/holidays', (route) => json({ success: true,
        data: { days, can_edit: true, coverage_until: until, ends_soon: true } })(route))
      await page.route((u) => new URL(u).pathname === '/api/admin/holidays/add-fixed', (route) => {
        posts.push(new URL(route.request().url()).searchParams.get('year'))
        days = [...days, { day: `${Y}-01-01`, name: 'Yılbaşı' }, { day: `${Y}-12-31`, name: 'Yıl sonu (kurum içi)' }]
        until = `${Y}-12-31`
        return json({ success: true, data: { days, can_edit: true, coverage_until: until, ends_soon: false, added: 2 } })(route)
      })
      await page.goto('/?tab=settings&sec=holidays')
      const root = page.locator('[data-slot="holiday-settings"]')
      const cov = root.locator('[data-slot="holiday-coverage"]')
      await expect(cov).toHaveAttribute('data-state', 'ended', { timeout: 20_000 })
      await expect(cov).toContainText(/ended|bitti/)
      const add = cov.locator('[data-slot="holiday-add-fixed"]')
      await expect(add).toContainText(String(Y))
      await page.waitForTimeout(300)
      expect(await page.evaluate(pageOverflow), `@${vp.name}: yatay taşma`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(offenders, '[data-slot="holiday-settings"]'), `@${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(await page.evaluate(clipped, '[data-slot="holiday-coverage"]'), `@${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="holiday-coverage"]'), `@${vp.name}: küçük dokunma hedefi`).toEqual([])
      await page.screenshot({ path: `test-results/holiday-ended-${vp.name}.png`, fullPage: false })
      await add.click()
      await expect.poll(() => posts).toEqual([String(Y)])
      await expect(cov).toHaveAttribute('data-state', 'ok')
      await expect(root.locator(`[data-slot="holiday-row"][data-day="${Y}-01-01"]`)).toBeVisible()
    })
  })
}
