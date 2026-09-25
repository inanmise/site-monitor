import { test, expect } from '@playwright/test'

/**
 * GİRİŞ SAYFASI YERLEŞİMİ — shadcn "authentication" düzeni (feature/shadcn-ui).
 *
 * <p>Giriş sayfası iki sütunlu ızgaraya (solda koyu tanıtım paneli, sağda form) ve gerçek shadcn
 * bileşenlerine (Input/Button/Dialog) geçti. Login.test.jsx yapıyı ve davranışı sınar ama
 * jsdom yerleşim yapmaz: sütunların yan yana durması, dar ekranda tek sütuna inip yatay kaydırma
 * üretmemesi, şifre "göz" düğmesinin alanın İÇİNDE kalması ve sorun bildirimi penceresinin
 * küçük ekranda taşmadan kaydırılabilmesi ancak gerçek tarayıcıda ölçülür.
 *
 * <p>Arka uç gerekmez: oturum sorgusu başarısız olunca uygulama giriş ekranına düşer (CI'da
 * arka uç yok; yerelde de temiz bağlamda oturum yok).
 */

async function box(page, selector) {
  return page.locator(selector).first().boundingBox()
}

test.describe('giriş sayfası yerleşimi', () => {
  test('geniş ekran: tanıtım paneli ile form YAN YANA, form sütunu dar kalır', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/')
    await page.waitForSelector('#lp-user')

    const left = await box(page, '.lp-left')
    const right = await box(page, '.lp-right')
    const form = await box(page, '.lp-form-wrap')

    expect(left.width).toBeGreaterThan(400)
    expect(right.x, 'form sütunu tanıtım panelinin SAĞINDA başlamalı').toBeGreaterThanOrEqual(left.x + left.width - 1)
    expect(Math.abs(right.y - left.y), 'iki sütun aynı satırda').toBeLessThan(2)
    expect(form.width, 'form sütunu shadcn max-w (380px) içinde').toBeLessThanOrEqual(381)
  })

  test('şifre göz düğmesi alanın İÇİNDE ve dikeyde ortalı', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/')
    await page.waitForSelector('#lp-pass')

    const input = await box(page, '#lp-pass')
    const eye = await box(page, '.lp-eye')

    expect(eye.x).toBeGreaterThanOrEqual(input.x)
    expect(eye.x + eye.width).toBeLessThanOrEqual(input.x + input.width + 0.5)
    const inputMid = input.y + input.height / 2
    const eyeMid = eye.y + eye.height / 2
    expect(Math.abs(inputMid - eyeMid), 'göz düğmesi dikeyde ortalı değil').toBeLessThan(2)
  })

  test('dar ekran (390px): tek sütun, yatay kaydırma YOK, vitrin gizli', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/')
    await page.waitForSelector('#lp-user')

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
    expect(overflow, 'sayfa yatay kayıyor').toBeLessThanOrEqual(0)

    const left = await box(page, '.lp-left')
    const right = await box(page, '.lp-right')
    expect(right.y, 'dar ekranda form panelin ALTINA iner').toBeGreaterThanOrEqual(left.y + left.height - 1)
    await expect(page.locator('.lp-pillars')).toBeHidden()

    const input = await box(page, '#lp-user')
    expect(input.x).toBeGreaterThanOrEqual(0)
    expect(input.x + input.width).toBeLessThanOrEqual(390)
  })

  test('sorun bildirimi penceresi küçük ekranda taşmaz, gövdesi kaydırılabilir', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 640 })
    await page.goto('/')
    await page.waitForSelector('.lp-help-link')
    await page.locator('.lp-help-link').click()

    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    const d = await dialog.boundingBox()
    expect(d.x).toBeGreaterThanOrEqual(0)
    expect(d.x + d.width).toBeLessThanOrEqual(390)
    expect(d.y).toBeGreaterThanOrEqual(0)
    expect(d.y + d.height, 'pencere ekranın altından taşıyor').toBeLessThanOrEqual(640)

    // Uzun form kısa ekrana sığmaz: kutu kendi içinde kayar, gönder düğmesine ulaşılır.
    const scrollable = await dialog.evaluate((el) => el.scrollHeight > el.clientHeight && getComputedStyle(el).overflowY === 'auto')
    expect(scrollable, 'pencere içi kaydırma yok — alt kısım erişilemez').toBe(true)
    const send = dialog.getByRole('button', { name: /send report|gönder/i })
    await send.scrollIntoViewIfNeeded()
    await expect(send).toBeInViewport()
  })
})
