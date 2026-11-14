// HTTP izleme kartı — uzun adres ve izleme adı KESİLMEZ, sarar (2026-11-14, kullanıcı kararı: "adlar ve uzun metin kesilmez").
// Eskiden kart başlığı (URL) ve altındaki izleme adı tek satıra kırpılıyordu ("https://odeme-servisleri-yedek-bol…");
// tam metin yalnız fare ile `title`'da görünüyordu (dokunmatikte hiç). jsdom yerleşim yapmaz — bu kapı tarayıcıda ölçer:
//   - başlık metni ve izleme adı kendi kutusuna sığar (scrollWidth <= clientWidth), üç nokta yok;
//   - uzun adres telefonda birden çok satıra iner; kart kendi sütununu ve sayfa yatay sınırı aşmaz;
//   - kopyala düğmesi dokunmatikte ≥ 40 px kalır.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const LONG_HOST = 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com'
const VIEWPORTS = [
  { name: 'telefon', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'dizüstü', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`HTTP kartı başlığı sarar — ${vp.name} ${vp.width}×${vp.height}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('uzun adres ve izleme adı kesilmez', async ({ page }) => {
      await mockApi(page)
      await page.goto('/?tab=http')
      const cards = page.locator('.upt-grid [data-slot="card"]')
      await expect(cards.first()).toBeVisible({ timeout: 20_000 })
      const card = cards.filter({ has: page.locator('[data-monitor-open]', { hasText: LONG_HOST }) }).first()
      await expect(card).toBeVisible()

      const m = await card.evaluate((el) => {
        const title = el.querySelector('[data-monitor-open] > span')
        const name = el.querySelector('[data-slot="http-name"]')
        const cr = el.getBoundingClientRect()
        const box = (n) => {
          if (!n) return null
          const r = n.getBoundingClientRect()
          const s = getComputedStyle(n)
          return {
            text: n.textContent, scrollW: n.scrollWidth, clientW: n.clientWidth, height: r.height,
            lineHeight: parseFloat(s.lineHeight) || parseFloat(s.fontSize) * 1.25, whiteSpace: s.whiteSpace,
            inside: r.left >= cr.left - 1 && r.right <= cr.right + 1,
          }
        }
        const copy = [...el.querySelectorAll('button')].find((b) => /Copy URL|URL'yi kopyala|kopyala/i.test(b.getAttribute('aria-label') || ''))
        const cb = copy ? copy.getBoundingClientRect() : null
        return {
          title: box(title), name: box(name), copy: cb ? { w: cb.width, h: cb.height } : null,
          pageOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
        }
      })

      expect(m.title, 'başlık metni bulunamadı').not.toBeNull()
      expect(m.title.text).toContain(LONG_HOST)
      expect(m.title.whiteSpace, 'başlık tek satıra zorlanmamalı').not.toBe('nowrap')
      expect(m.title.scrollW, 'başlık kendi kutusunda kırpılıyor').toBeLessThanOrEqual(m.title.clientW + 1)
      expect(m.title.inside, 'başlık kartın dışına taşıyor').toBe(true)
      expect(m.name, 'izleme adı satırı bulunamadı').not.toBeNull()
      expect(m.name.text).toBe(LONG_HOST)
      expect(m.name.scrollW, 'izleme adı kendi kutusunda kırpılıyor').toBeLessThanOrEqual(m.name.clientW + 1)
      expect(m.name.inside, 'izleme adı kartın dışına taşıyor').toBe(true)
      expect(m.pageOverflow, 'sayfa yatay taşıyor (px)').toBeLessThanOrEqual(1)
      // 390 px'te 76 karakterlik adres tek satıra sığmaz — sardığının kanıtı: birden çok satır yüksekliği
      if (vp.width === 390) expect(m.title.height, 'uzun adres telefonda sarmalı').toBeGreaterThan(m.title.lineHeight * 1.5)
      if (vp.touch && m.copy) {
        expect(m.copy.w, 'kopyala düğmesi dokunma genişliği').toBeGreaterThanOrEqual(40)
        expect(m.copy.h, 'kopyala düğmesi dokunma yüksekliği').toBeGreaterThanOrEqual(40)
      }
    })
  })
}
