// Genel klavye kısayolları (2026-10-02, öneri 24) — gerçek tarayıcıda: `?` kısayol listesini açar (telefonda TAM
// YÜKSEKLİK, tablette/masaüstünde ortalı pencere; içerik ekran dışına taşmaz), `/` sayfanın arama kutusuna odaklanır,
// `g` sonra `m` İzleme Panosu'na gider; yazı alanındayken `?` yazılır, liste açılmaz. jsdom yerleşimi kanıtlamaz.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, full: true },
  { name: 'tablet', width: 768, height: 1024, full: false },
  { name: 'desktop', width: 1280, height: 800, full: false },
]
const TITLE = /Klavye kısayolları|Keyboard shortcuts/

/** Pencere içinde görünüm alanının sağına taşan görünür öğeler (kendi kaydırma kabındakiler hariç). */
function offenders(sel) {
  const vw = document.documentElement.clientWidth
  const root = document.querySelector(sel)
  const out = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.width <= 1 || r.height <= 1 || r.right <= vw + 1) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (!contained) out.push(`${el.tagName.toLowerCase()} ${Math.round(r.right)} ${(el.textContent || '').trim().slice(0, 30)}`)
    if (out.length >= 5) break
  }
  return out
}

for (const vp of VIEWPORTS) {
  test(`kısayol listesi — ${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.goto('/?tab=dashboard')
    await page.locator('.app-main').waitFor({ timeout: 20_000 })
    await page.waitForTimeout(800)
    await page.keyboard.press('?')
    const dlg = page.getByRole('dialog', { name: TITLE })
    await expect(dlg).toBeVisible()
    await expect(dlg.locator('[data-shortcut="palette"]')).toBeVisible()
    await page.waitForTimeout(300)
    const box = await dlg.boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(0)
    expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
    expect(box.y + box.height).toBeLessThanOrEqual(vp.height + 1)
    if (vp.full) {
      expect(Math.round(box.y)).toBe(0)
      expect(Math.round(box.height)).toBeGreaterThanOrEqual(vp.height - 1)
    }
    expect(await page.evaluate(offenders, '[role="dialog"]')).toEqual([])
    // Gövde kayar: son grup (pencerelerde) kaydırılarak görünür
    await dlg.locator('[data-group="windows"]').scrollIntoViewIfNeeded()
    await expect(dlg.locator('[data-group="windows"]')).toBeInViewport()
    await page.keyboard.press('Escape')
    await expect(dlg).toBeHidden()
  })
}

test('`/` arama kutusuna odaklanır; yazı alanında `?` yazılır; `g m` İzleme Panosu', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  await mockApi(page)
  await page.goto('/?tab=dashboard')
  await page.locator('.app-main').waitFor({ timeout: 20_000 })
  await page.waitForTimeout(800)
  await page.keyboard.press('/')
  const search = page.locator('[data-page-search]:visible').first()
  await expect(search).toBeFocused()
  await page.keyboard.type('a?b')
  await expect(search).toHaveValue('a?b')
  await expect(page.getByRole('dialog', { name: TITLE })).toHaveCount(0)
  await search.blur()
  await page.keyboard.press('g')
  await page.keyboard.press('m')
  await expect(page).toHaveURL(/[?&]tab=monitoring/)
})
