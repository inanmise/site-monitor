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
    // Geç gelen parçalar (giriş yöntemleri, kullanım istatistikleri) formu kaydırabilir: ağ durulsun, iki kutu AYNI anda
    // ölçülsün — ayrı ölçümler yük altında farklı karelerden geliyordu (2026-10-04, tam koşuda 46 px yalancı fark).
    await page.waitForLoadState('networkidle')
    const { input, eye } = await page.evaluate(() => {
      const r = (sel) => { const b = document.querySelector(sel).getBoundingClientRect(); return { x: b.x, y: b.y, width: b.width, height: b.height } }
      return { input: r('#lp-pass'), eye: r('.lp-eye') }
    })

    expect(eye.x).toBeGreaterThanOrEqual(input.x)
    expect(eye.x + eye.width).toBeLessThanOrEqual(input.x + input.width + 0.5)
    const inputMid = input.y + input.height / 2
    const eyeMid = eye.y + eye.height / 2
    expect(Math.abs(inputMid - eyeMid), 'göz düğmesi dikeyde ortalı değil').toBeLessThan(2)
  })

  // H paketi (2026-11-26, deneyim 12): göz düğmesi klavyeyle erişilebilir — parola alanından Tab ile gelinir,
  // Enter / Boşluk açar-kapatır, aria-pressed durumu söyler; dokunmatikte ≥ 40 px dokunma alanı.
  for (const vp of [
    { name: 'telefon-390-dokunmatik', width: 390, height: 844, touch: true },
    { name: 'tablet-768-dokunmatik', width: 768, height: 1024, touch: true },
    { name: 'laptop-1280', width: 1280, height: 800, touch: false },
  ]) {
    test.describe(`şifre göz düğmesi klavye — ${vp.name}`, () => {
      test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })
      test('Tab ile odaklanır, Enter ve Boşluk alanı açar / kapatır', async ({ page }) => {
        await page.goto('/')
        await page.waitForSelector('#lp-pass')
        const pass = page.locator('#lp-pass')
        const eye = page.getByRole('button', { name: /^(Şifreyi göster|Show password)$/ })
        await pass.fill('gizli-parola')
        await pass.focus()
        await page.keyboard.press('Tab')
        await expect(eye).toBeFocused()
        await expect(eye).toHaveAttribute('aria-pressed', 'false')
        await page.keyboard.press('Enter')
        await expect(pass).toHaveAttribute('type', 'text')
        await expect(eye).toHaveAttribute('aria-pressed', 'true')
        await page.keyboard.press('Space')
        await expect(pass).toHaveAttribute('type', 'password')
        await expect(eye).toHaveAttribute('aria-pressed', 'false')
        // Odak halkası görünür (klavye kullanıcısı nerede olduğunu görür)
        const ring = await eye.evaluate((el) => {
          const cs = getComputedStyle(el)
          return cs.outlineStyle !== 'none' || cs.boxShadow !== 'none'
        })
        expect(ring, 'göz düğmesinde odak göstergesi yok').toBe(true)
        if (vp.touch) {
          // Dokunma alanı: düğme ya da ::after genişletmesi en az 40 px
          const hit = await eye.evaluate((el) => {
            const r = el.getBoundingClientRect()
            const a = getComputedStyle(el, '::after')
            const inset = a.content && a.content !== 'none' && a.position === 'absolute' ? -parseFloat(a.top || '0') : 0
            return { w: r.width + 2 * Math.max(0, inset), h: r.height + 2 * Math.max(0, inset) }
          })
          expect(hit.w).toBeGreaterThanOrEqual(39.5)
          expect(hit.h).toBeGreaterThanOrEqual(39.5)
        }
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
        expect(overflow, 'sayfa yatay kayıyor').toBeLessThanOrEqual(0)
      })
    })
  }

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

    // Uzun form kısa ekrana sığmaz: pencere ya da (2026-09-27 yeniden tasarım: ModalShell) GÖVDESİ kendi içinde kayar,
    // alt çubuktaki gönder düğmesine ulaşılır. Kaydırılan öğe pencerenin kendisi ya da bir alt öğesi olabilir.
    const scrollable = await dialog.evaluate((root) => [root, ...root.querySelectorAll('*')].some((el) => {
      const oy = getComputedStyle(el).overflowY
      return (oy === 'auto' || oy === 'scroll') && el.scrollHeight > el.clientHeight + 1
    }))
    expect(scrollable, 'pencere içi kaydırma yok — alt kısım erişilemez').toBe(true)
    const send = dialog.getByRole('button', { name: /send report|gönder/i })
    await send.scrollIntoViewIfNeeded()
    await expect(send).toBeInViewport()
  })
})
