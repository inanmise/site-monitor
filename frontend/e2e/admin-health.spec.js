import { test, expect } from '@playwright/test'

/**
 * Yönetim sağlık kutucukları (2026-11-12, çapraz zenginleştirme #2) — mweb. İzole harness
 * (e2e/harness/admin-health.html) gerçek HealthTiles'ı sabit sinyallerle mount eder. Üç boyutta: yatay TAŞMA yok;
 * telefonda yalnız ilk 2 kutucuk + "+5 daha" (basınca hepsi), geniş ekranda 7 kutucuk 2 sütun; dokunmatikte hedefler
 * ≥ 40 px; kutucuk tıklaması sekme + SÜZGEÇ verir (ESCALATION_GAP → contacts, USER_ATTENTION → users g_status=locked).
 */
const VIEWPORTS = [
  { name: 'phone',  width: 390,  height: 844,  touch: true,  mobile: true },
  { name: 'tablet', width: 768,  height: 1024, touch: true,  mobile: false },
  { name: 'laptop', width: 1280, height: 800,  touch: false, mobile: false },
]

async function pageOverflow(page) {
  return page.evaluate(() => {
    const el = document.documentElement
    const panel = document.querySelector('[data-slot="overview-health"]')
    const p = panel ? panel.scrollWidth - panel.clientWidth : 0
    return Math.max(el.scrollWidth - el.clientWidth, p)
  })
}

async function smallTargets(root) {
  return root.evaluate((el) => {
    const out = []
    for (const b of el.querySelectorAll('button, a[href]')) {
      const r = b.getBoundingClientRect()
      if (r.width === 0 || r.height === 0) continue
      const cs = getComputedStyle(b)
      if (cs.visibility === 'hidden' || cs.display === 'none') continue
      if (r.height < 39.5) out.push(`${b.tagName.toLowerCase()}:${(b.textContent || '').trim().slice(0, 24)} ${Math.round(r.height)}`)
    }
    return out
  })
}

for (const vp of VIEWPORTS) {
  test(`sağlık kutucukları (${vp.name} ${vp.width}): taşma yok, ${vp.mobile ? '2+N' : '7 kutucuk'}, derin-bağlantı`, async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch })
    const page = await context.newPage()
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto('/e2e/harness/admin-health.html')

    const panel = page.locator('[data-slot="overview-health"]')
    await expect(panel).toBeVisible()
    const tiles = page.locator('[data-warning]')

    if (vp.mobile) {
      // Telefon: ilk 2 kutucuk (ağırlar) + "+5 daha"; basınca 7
      await expect(tiles).toHaveCount(2)
      await expect(page.locator('[data-warning="SINGLE_ADMIN"]')).toBeVisible()
      await page.getByRole('button', { name: /^\+5 (more|daha)$/ }).click()
      await expect(tiles).toHaveCount(7)
    } else {
      await expect(tiles).toHaveCount(7)
    }

    expect(await pageOverflow(page), 'yatay taşma').toBeLessThanOrEqual(1)

    if (vp.touch) {
      const small = await smallTargets(panel)
      expect(small, `küçük dokunma hedefleri:\n${small.join('\n')}`).toEqual([])
    }

    // Derin-bağlantı: ESCALATION_GAP → contacts; USER_ATTENTION → users g_status=locked
    await page.locator('[data-warning="ESCALATION_GAP"]').click()
    await expect.poll(() => page.evaluate(() => window.__jump)).toEqual({ tab: 'contacts', params: {} })
    await page.locator('[data-warning="USER_ATTENTION"]').click()
    await expect.poll(() => page.evaluate(() => window.__jump)).toEqual({ tab: 'users', params: { g_status: 'locked' } })

    await context.close()
  })
}
