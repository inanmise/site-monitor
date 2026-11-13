// Dinamik taban çizgisi diyaloğu — mobil web kapısı (hata taraması 2026-11-13 #8: bilinmeyen / ana anahtar kapalı / eski
// sunucu durumu ASLA yeşil "hazır" değildir; hazırlığı sunucu gerçek değerlendirme penceresinden hesaplar). Ping izlemesinin
// detayından açılır; telefon (390×844, dokunmatik), tablet (768×1024, dokunmatik) ve dizüstünde (1280×800):
//   - hazırlık bandının durumu sunucunun alanlarına uyar (hazır / kuruluyor / yetersiz / ana anahtar kapalı / eski sunucu);
//   - pencere görünüm alanına sığar, sayfa ve pencere YATAY kaymaz, uzun izleme adı kesilmez;
//   - dokunmatikte düğmeler, giriş kutuları ve seçim parçaları ≥ 40 px.
// jsdom yerleşim yapmaz; bu kapı tarayıcıda ölçer.
import { test, expect } from '@playwright/test'
import { MONITORS, mockApi } from './support/monitorMocks.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'desktop', width: 1280, height: 800, touch: false },
]
const LONG_NAME = 'Ödeme servisleri yedek bölge 2 — çok uzun bir izleme adı (Kurumsal Ağ / Dış Bağlantı Katmanı)'
const DIALOG = '[role="dialog"]:has([data-slot="baseline-body"])'

const BASE = { enabled: true, direction: 'up', k_warning: 3, k_critical: 5, window: 30, min_samples: 10, season: 'none',
  sample_count: 120, defaults: false }
const CASES = [
  { key: 'ready', cfg: { ...BASE, global_enabled: true, ready_state: 'ready', ready_samples: 30, ready_min: 10 }, state: 'ready' },
  { key: 'building', cfg: { ...BASE, global_enabled: true, ready_state: 'building', ready_samples: 4, ready_min: 10 }, state: 'building' },
  { key: 'insufficient', cfg: { ...BASE, season: 'weekly', global_enabled: true, ready_state: 'insufficient', ready_samples: 3,
    ready_min: 10 }, state: 'insufficient' },
  { key: 'switchOff', cfg: { ...BASE, global_enabled: false, ready_state: 'ready', ready_samples: 30, ready_min: 10 }, state: 'switchOff' },
  { key: 'oldServer', cfg: { ...BASE }, state: 'unverified' },
]

function measure(sel) {
  const vw = document.documentElement.clientWidth
  const root = document.querySelector(sel) || document.body
  const pageOverflow = Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth,
    document.body.scrollWidth - document.body.clientWidth)
  const offenders = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.width <= 1 || r.height <= 1) continue
    if (r.right > vw + 1 || r.left < -1) offenders.push(`${el.tagName.toLowerCase()} ${(el.textContent || '').trim().slice(0, 30)}`)
    if (offenders.length >= 8) break
  }
  const name = root.querySelector('[data-slot="baseline-monitor"]')
  const nameClipped = !!name && (name.scrollWidth > name.clientWidth + 1)
  return { pageOverflow, offenders, nameClipped }
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  const out = []
  for (const el of root.querySelectorAll('button, input, [role="radio"]')) {
    if (el.getAttribute('role') === 'switch') continue   // anahtarın 40×40 görünmez ::after alanı var; satırı ayrıca ölçülür
    const r = el.getBoundingClientRect()
    if (r.width <= 1 || r.height <= 1) continue
    if (r.height < 39.5) out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || el.getAttribute('aria-label') || ''}] ${Math.round(r.height)}px`)
  }
  const row = root.querySelector('[data-slot="baseline-enable"]')
  if (row && row.getBoundingClientRect().height < 39.5) out.push('baseline-enable satırı')
  return out
}

for (const vp of VIEWPORTS) {
  test.describe(`dinamik taban diyaloğu @${vp.name}`, () => {
    test.use({ hasTouch: vp.touch, isMobile: vp.touch && vp.width < 768 })

    for (const c of CASES) {
      test(`${c.key}: hazırlık bandı doğru, ekrana sığar @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
        await page.setViewportSize({ width: vp.width, height: vp.height })
        await mockApi(page, { monitors: { ...MONITORS, ping: MONITORS.ping.map((m, i) => (i === 0 ? { ...m, name: LONG_NAME, can_edit: true } : m)) } })
        await page.route((u) => /^\/api\/monitoring\/baseline\/ping\/\d+$/.test(new URL(u).pathname),
          (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: c.cfg }) }))
        await page.goto('/?tab=ping&monitor=100')
        const open = page.locator('[data-slot="baseline-open"]').first()
        await open.waitFor({ timeout: 20_000 })
        await open.click()
        const dlg = page.locator(DIALOG)
        await dlg.waitFor({ timeout: 20_000 })

        const band = dlg.locator('[data-slot="baseline-readiness"]')
        await expect(band).toHaveAttribute('data-state', c.state)
        await expect(dlg.locator('[data-slot="baseline-monitor"]')).toHaveText(LONG_NAME)

        await page.waitForTimeout(300)
        const m = await page.evaluate(measure, DIALOG)
        expect(m.offenders, `${c.key} @${vp.name}: pencerede ekran dışına taşan öğe`).toEqual([])
        expect(m.pageOverflow, `${c.key} @${vp.name}: sayfa yatay kayıyor (px)`).toBeLessThanOrEqual(1)
        expect(m.nameClipped, `${c.key} @${vp.name}: izleme adı kesiliyor`).toBe(false)
        const box = await dlg.boundingBox()
        expect(box.x).toBeGreaterThanOrEqual(-1)
        expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
        if (vp.touch) {
          const small = await page.evaluate(smallTargets, DIALOG)
          expect(small, `${c.key} @${vp.name}: 40 px altı dokunmatik hedef`).toEqual([])
        }
      })
    }
  })
}
