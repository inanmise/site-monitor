import { test, expect } from '@playwright/test'

/**
 * Kişiler → eskalasyon KAPSAM matrisi (2026-11-12, çapraz zenginleştirme #1) — mweb. İzole harness
 * (e2e/harness/contacts-coverage.html) gerçek CoveragePanel'i sabit veriyle mount eder. Üç boyutta: sayfa/panel yatay
 * TAŞMAZ; kap genişliğine göre dar(<640)=KART, geniş=TABLO; dokunmatik boyutlarda hedefler ≥ 40 px; "Kişi ekle" (gap
 * satırı) onQuickAdd'ı doğru takımla tetikler; boşlukta panel OTOMATİK açık (4 satır görünür).
 */
const VIEWPORTS = [
  { name: 'phone',  width: 390,  height: 844,  touch: true },
  { name: 'tablet', width: 768,  height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800,  touch: false },
]

async function pageOverflow(page) {
  return page.evaluate(() => {
    const el = document.documentElement
    const panel = document.querySelector('[data-slot="contacts-coverage"]')
    const p = panel ? panel.scrollWidth - panel.clientWidth : 0
    return Math.max(el.scrollWidth - el.clientWidth, p)
  })
}

/** Dokunmatikte 40 px'ten kısa görünür kontroller (RESPONSIVE.md §4; pointer-coarse sınıfları uygulanır). */
async function smallTargets(root) {
  return root.evaluate((el) => {
    const out = []
    for (const b of el.querySelectorAll('button, a[href], input:not([type="hidden"])')) {
      const r = b.getBoundingClientRect()
      if (r.width === 0 || r.height === 0) continue
      const cs = getComputedStyle(b)
      if (cs.visibility === 'hidden' || cs.display === 'none') continue
      if (r.height < 39.5) out.push(`${b.tagName.toLowerCase()}:${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 24)} ${Math.round(r.height)}`)
    }
    return out
  })
}

for (const vp of VIEWPORTS) {
  test(`kapsam matrisi (${vp.name} ${vp.width}): taşma yok, ${vp.width < 640 ? 'kartlar' : 'tablo'}, hızlı ekle`, async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch })
    const page = await context.newPage()
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto('/e2e/harness/contacts-coverage.html')

    const panel = page.locator('[data-slot="contacts-coverage"]')
    await expect(panel).toBeVisible()
    // Boşluk var → panel otomatik açık → 4 satır görünür
    await expect(page.locator('[data-slot="cov-row"]')).toHaveCount(4)

    // Kap genişliğine göre düzen: dar → tablo yok (kartlar), geniş → tablo
    const tables = await panel.locator('table').count()
    if (vp.width < 640) expect(tables, 'dar kapta tablo olmamalı (kartlar)').toBe(0)
    else expect(tables, 'geniş kapta tablo olmalı').toBeGreaterThanOrEqual(1)

    // Yatay taşma yok
    expect(await pageOverflow(page), 'yatay taşma').toBeLessThanOrEqual(1)

    // Dokunmatik boyutlarda hedefler ≥ 40 px
    if (vp.touch) {
      const small = await smallTargets(panel)
      expect(small, `küçük dokunma hedefleri:\n${small.join('\n')}`).toEqual([])
    }

    // "Kişi ekle" (gap = takım 3) onQuickAdd'ı o takımla tetikler
    await panel.locator('[data-slot="cov-quickadd"][data-team="3"]').first().click()
    await expect.poll(() => page.evaluate(() => window.__quickAdd)).toBe(3)

    await context.close()
  })
}
