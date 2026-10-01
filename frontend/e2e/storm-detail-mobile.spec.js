// Alarm Fırtınası ayrıntı penceresi telefonda (2026-09-30): pencere ekrana sığar, içindeki hiçbir görünür öğe sağa taşmaz;
// üyeler kart listesi olarak çizilir (tablo değil).
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

for (const vp of [{ name: 'phone', width: 390, height: 844 }, { name: 'tablet', width: 768, height: 1024 }]) {
  test(`fırtına ayrıntı penceresi — ${vp.name}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.goto('/?tab=storms&sf_storm=7')
    const dlg = page.getByRole('dialog').first()
    await dlg.waitFor({ timeout: 20_000 })
    await page.locator('[data-slot="sf-member"]').first().waitFor()
    const box = await dlg.boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(0)
    expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
    const overflow = await page.evaluate((vw) => [...document.querySelectorAll('[role="dialog"] *')]
      .filter((el) => { const r = el.getBoundingClientRect(); return r.width > 1 && r.right > vw + 1 && getComputedStyle(el).visibility !== 'hidden' })
      .filter((el) => { for (let p = el.parentElement; p; p = p.parentElement) { if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= vw + 1) return false } return true })
      .map((el) => el.tagName + '.' + String(el.className).slice(0, 40)).slice(0, 5), vp.width)
    expect(overflow).toEqual([])
    if (vp.name === 'phone') expect(await page.locator('li [data-slot="sf-member"]').count()).toBe(2)
  })
}
