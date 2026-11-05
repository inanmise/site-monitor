// İzleme menüsü alt grupları akordeon (2026-11-05, kullanıcı isteği: "Erişilebilirlik, Alan Adı ve DNS, İçerik ve Deneyim
// ayrı ayrı akordeon gibi açılsın, izlemelere altından erişilsin; shadcn ile mweb responsive").
//
// Gerçek tarayıcıda, API mock'lu: dokunmatik telefon (390, çekmece), dokunmatik tablet (768) ve masaüstü (1280). Ölçülenler:
//   - dört alt grup kapalı başlar, birbirinden bağımsız açılır, sekmeler altından açılır ve gezinir;
//   - kapalı grubun başlığında alt sekmelerin alarm toplamı (Erişilebilirlik 2, İçerik 3);
//   - kenar çubuğunda yatay taşma yok, grup ve sekme adları kırpılmaz (İngilizce uzun ad sarar);
//   - dokunmatikte grup başlığı ve sekme satırı en az 40 px.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const VIEWPORTS = [
  { name: 'telefon', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]
const GROUPS = ['nav.secAvailability', 'nav.secDomainDns', 'nav.secContent', 'nav.secData']

const settle = (page) => page.evaluate(() => Promise.all(document.getAnimations()
  .filter((a) => a.effect?.getComputedTiming().endTime !== Infinity).map((a) => a.finished.catch(() => null))))

/** Kenar çubuğu ölçümü: { overflow[], clipped[], small[] } — yalnız görünür izleme alt menüsü. */
function auditSidebar() {
  const bar = [...document.querySelectorAll('[data-slot="sidebar"]')].find((el) => el.getBoundingClientRect().width > 50)
  if (!bar) return { overflow: ['kenar çubuğu yok'], clipped: [], small: [] }
  const box = bar.getBoundingClientRect()
  const visible = (el) => {
    const r = el.getBoundingClientRect()
    if (r.width <= 1 || r.height <= 1) return false
    return !el.closest('[hidden], .sr-only, [aria-hidden="true"]')
  }
  const sub = bar.querySelector('[data-nav-subgroup]')?.closest('[data-sidebar="menu-sub"]')
  const nodes = sub ? [...sub.querySelectorAll('*')] : []
  const overflow = []
  const clipped = []
  const small = []
  for (const el of nodes) {
    if (!visible(el)) continue
    const r = el.getBoundingClientRect()
    if (r.right > box.right + 1 || r.left < box.left - 1) overflow.push(`${el.tagName} ${Math.round(r.left)}–${Math.round(r.right)} / ${Math.round(box.left)}–${Math.round(box.right)}`)
    if (el.tagName === 'SPAN' && el.children.length === 0 && el.textContent.trim() && el.scrollWidth > el.clientWidth + 1) {
      clipped.push(`"${el.textContent.trim()}" ${el.scrollWidth}>${el.clientWidth}`)
    }
  }
  if (matchMedia('(pointer: coarse)').matches) {
    for (const b of sub ? sub.querySelectorAll('button') : []) {
      if (!visible(b)) continue
      const h = b.getBoundingClientRect().height
      if (h < 39.5) small.push(`"${b.textContent.trim().slice(0, 30)}" ${Math.round(h)} px`)
    }
  }
  return { overflow, clipped, small }
}

for (const vp of VIEWPORTS) {
  for (const lang of ['tr', 'en']) {
    test.describe(`İzleme alt grupları — ${vp.name} ${vp.width} (${lang})`, () => {
      test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

      test('kapalı başlar, bağımsız açılır, toplam rozet, gezinir; taşma / kırpma yok, dokunma hedefi 40 px', async ({ page }) => {
        await page.addInitScript((l) => {
          localStorage.setItem('site-monitor-lang', l)
          localStorage.removeItem('nav-subsection-open')
          localStorage.removeItem('nav-section-open')
        }, lang)
        await mockApi(page)
        await page.goto('/?tab=dashboard')
        await page.locator('.app-main').waitFor({ timeout: 20_000 })
        if (vp.width < 768) {
          await page.locator('[data-sidebar="trigger"]').first().click()
          await expect(page.locator('[data-mobile="true"][data-slot="sidebar"]')).toBeVisible()
        }
        const sidebar = page.locator('[data-slot="sidebar"]').filter({ has: page.locator('[data-nav-section-trigger="monitoring"]') }).last()
        const monitoring = sidebar.locator('[data-nav-section-trigger="monitoring"]')
        await monitoring.click()
        await expect(monitoring).toHaveAttribute('aria-expanded', 'true')

        const trigger = (k) => sidebar.locator(`[data-nav-subgroup-trigger="${k}"]`)
        for (const k of GROUPS) {
          await expect(trigger(k)).toBeVisible()
          await expect(trigger(k)).toHaveAttribute('aria-expanded', 'false')
        }
        // Kapalı grup başlığında alt sekmelerin alarm toplamı
        await expect(trigger('nav.secAvailability').locator('[data-slot="nav-subgroup-alert-count"]')).toHaveText('2')
        await expect(trigger('nav.secContent').locator('[data-slot="nav-subgroup-alert-count"]')).toHaveText('3')
        await expect(sidebar.locator('[data-tour="nav-tab-http"]')).toBeHidden()

        await trigger('nav.secAvailability').click()
        await trigger('nav.secContent').click()
        await trigger('nav.secData').click()
        await settle(page)
        await expect(trigger('nav.secAvailability')).toHaveAttribute('aria-expanded', 'true')
        await expect(trigger('nav.secContent')).toHaveAttribute('aria-expanded', 'true')
        await expect(trigger('nav.secDomainDns')).toHaveAttribute('aria-expanded', 'false')
        await expect(trigger('nav.secAvailability').locator('[data-slot="nav-subgroup-alert-count"]')).toHaveCount(0)
        for (const id of ['http', 'ping', 'port', 'keyword', 'page', 'pagespeed', 'scripted', 'dbquery']) {
          await expect(sidebar.locator(`[data-tour="nav-tab-${id}"]`)).toBeVisible()
        }
        await expect(sidebar.locator('[data-tour="nav-tab-dns"]')).toBeHidden()

        const m = await page.evaluate(auditSidebar)
        expect(m.overflow, 'kenar çubuğundan taşan öğe').toEqual([])
        expect(m.clipped, 'kırpılan ad').toEqual([])
        expect(m.small, 'dokunma hedefi 40 px altında').toEqual([])

        // Klavye: grup başlığı Enter ile kapanır
        await trigger('nav.secData').focus()
        await page.keyboard.press('Enter')
        await expect(trigger('nav.secData')).toHaveAttribute('aria-expanded', 'false')

        // Gezinme: sekme satırı ilgili sayfayı açar; telefonda çekmece kapanır
        await sidebar.locator('[data-tour="nav-tab-keyword"]').click()
        await expect(page).toHaveURL(/tab=keyword/)
        if (vp.width < 768) await expect(page.locator('[data-mobile="true"][data-slot="sidebar"]')).toBeHidden()
        if (vp.width >= 768) {
          await expect(sidebar.locator('[data-tour="nav-tab-keyword"]')).toHaveAttribute('aria-current', 'page')
          await expect(trigger('nav.secContent')).toHaveAttribute('data-contains-active', 'true')
        }
      })
    })
  }
}

test.describe('İzleme alt grupları — etkin sekmenin grubu açılır (laptop)', () => {
  test.use({ viewport: { width: 1280, height: 800 } })
  test('?tab=dns açılınca İzleme bölümü ve Alan Adı ve DNS grubu açık, diğerleri kapalı', async ({ page }) => {
    await page.addInitScript(() => { localStorage.removeItem('nav-subsection-open'); localStorage.removeItem('nav-section-open') })
    await mockApi(page)
    await page.goto('/?tab=dns')
    const dns = page.locator('[data-slot="sidebar"] [data-tour="nav-tab-dns"]')
    await expect(dns).toBeVisible({ timeout: 20_000 })
    await expect(dns).toHaveAttribute('aria-current', 'page')
    await expect(page.locator('[data-nav-subgroup-trigger="nav.secDomainDns"]')).toHaveAttribute('aria-expanded', 'true')
    await expect(page.locator('[data-nav-subgroup-trigger="nav.secAvailability"]')).toHaveAttribute('aria-expanded', 'false')
  })
})
