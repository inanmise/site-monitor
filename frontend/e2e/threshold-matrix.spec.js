import { test, expect } from '@playwright/test'

/**
 * Eşik karşılaştırma matrisi (2026-11-12, #3) — mweb. İzole harness gerçek ThresholdMatrix'i sabit veriyle mount eder.
 * Üç boyutta: yatay TAŞMA yok; kap genişliğine göre dar(<600)=KART, geniş=TABLO; 5 satır (Varsayılan + 4 tier) + etki.
 * Salt-okur (düzenleme kartlarda) → dokunma hedefi yok; taşma/kırpılma ana ölçüm.
 */
const VIEWPORTS = [
  { name: 'phone',  width: 390,  height: 844,  cards: true },
  { name: 'tablet', width: 768,  height: 1024, cards: false },
  { name: 'laptop', width: 1280, height: 800,  cards: false },
]

async function pageOverflow(page) {
  return page.evaluate(() => {
    const el = document.documentElement
    const panel = document.querySelector('[data-slot="threshold-matrix"]')
    const p = panel ? panel.scrollWidth - panel.clientWidth : 0
    return Math.max(el.scrollWidth - el.clientWidth, p)
  })
}

for (const vp of VIEWPORTS) {
  test(`eşik matrisi (${vp.name} ${vp.width}): taşma yok, ${vp.cards ? 'kartlar' : 'tablo'}, 5 satır`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto('/e2e/harness/threshold-matrix.html')

    const panel = page.locator('[data-slot="threshold-matrix"]')
    await expect(panel).toBeVisible()
    await expect(page.locator('[data-slot="tm-row"]')).toHaveCount(5)

    const tables = await panel.locator('table').count()
    if (vp.cards) expect(tables, 'dar kapta tablo olmamalı (kartlar)').toBe(0)
    else expect(tables, 'geniş kapta tablo olmalı').toBeGreaterThanOrEqual(1)

    expect(await pageOverflow(page), 'yatay taşma').toBeLessThanOrEqual(1)

    // Tier 1 kendi eşiği (farklı) → override hücreleri; Tier 2 varsayılanla aynı → devralınmış değil, override yok
    await expect(panel.locator('[data-slot="tm-row"][data-tier="1"] [data-slot="tm-cell"][data-override="true"]')).toHaveCount(3)
  })
}
