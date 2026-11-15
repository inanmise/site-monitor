// Sayfa Hızı 2c (2026-11-15): genişletilmiş ölçüm anahtarı + büyüme alarmı alanları formda; kart büyüme ihlalini adlandırır.
// Telefon / tablet (dokunmatik) / dizüstünde: bölümler pencereye sığar, yatay kayma yok, uzun açıklamalar sarar (kırpılmaz),
// anahtarlar dokunmatikte ≥ 40 px dokunma alanı taşır; kip değişimi uyarısı çıkar; kayıt gövdesi kipi ve büyüme alanlarını
// taşır. jsdom yerleşim hesaplamaz → gerçek tarayıcı.
import { test, expect } from '@playwright/test'
import { MONITORS, mockApi } from './support/monitorMocks.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 800 },
]
const json = (body) => (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
const iso = (agoMs) => new Date(Date.now() - agoMs).toISOString().slice(0, 19)
const LONG_NAME = 'Kurumsal İnternet Bankacılığı Ödeme Onay Sayfası (Bireysel ve Ticari Müşteriler — Canlı Ortam)'

const ROW = {
  ...MONITORS.pagespeed[0], id: 101, name: LONG_NAME, active: true, status: 'SLOW', ok: true,
  response_ms: 1400, ttfb_ms: 180, total_bytes: 1_850_000, request_count: 66,
  interval_seconds: 1800, breached_metrics: ['GROWSIZE', 'SIZEBAND'],
  growth_bytes_pct: 20, growth_requests_pct: null, growth_bytes_band: true,
  extended_measurement: null, extended: false,
  last_check: iso(60_000), checked_at: iso(60_000),
}

const measure = (sel) => {
  const vw = document.documentElement.clientWidth
  const root = (sel && document.querySelector(sel)) || document.body
  const pageOverflow = Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth,
    document.body.scrollWidth - document.body.clientWidth)
  const clips = (el) => getComputedStyle(el).overflowX !== 'visible'
  const hidden = (el, r) => {
    if (r.width <= 1 || r.height <= 1) return true
    const cs = getComputedStyle(el)
    return cs.visibility === 'hidden' || cs.display === 'none' || Number(cs.opacity) === 0
  }
  const offenders = []
  const flagged = new Set()
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.right <= vw + 1 || hidden(el, r)) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (clips(p) && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (contained) continue
    flagged.add(el)
    if (el.parentElement && flagged.has(el.parentElement)) continue
    const slot = el.getAttribute('data-slot')
    offenders.push(`${el.tagName.toLowerCase()}${slot ? `[data-slot=${slot}]` : ''} r=${Math.round(r.right)}`)
    if (offenders.length >= 8) break
  }
  return { vw, pageOverflow, offenders }
}

// Kırpılan metin: tek satıra zorlanmış ya da kutusundan taşan metin (sarması gereken açıklamalar)
const clipped = (sel) => {
  const out = []
  for (const el of document.querySelectorAll(`${sel} span, ${sel} p`)) {
    const cs = getComputedStyle(el)
    if (el.getBoundingClientRect().width <= 1) continue
    if (cs.textOverflow === 'ellipsis' || el.scrollWidth > el.clientWidth + 1) out.push((el.textContent || '').slice(0, 40))
  }
  return out
}

for (const vp of VIEWPORTS) {
  test.describe(`sayfa hızı büyüme alarmı @${vp.name}`, () => {
    const touch = vp.width < 1024
    test.use({ hasTouch: touch, isMobile: touch })

    test(`genişletilmiş ölçüm + büyüme bölümleri sığar, kayıt alanları taşır @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
      test.setTimeout(120_000)
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mockApi(page)
      const puts = []
      await page.route((u) => new URL(u).pathname === '/api/monitoring/pagespeed', json({ success: true, data: [ROW] }))
      await page.route((u) => new URL(u).pathname === '/api/monitoring/pagespeed/schedule-preview',
        json({ success: true, data: { valid: true, errors: [], next: [] } }))
      await page.route((u) => new URL(u).pathname === `/api/monitoring/pagespeed/${ROW.id}`, async (r) => {
        if (r.request().method() === 'PUT') {
          puts.push(JSON.parse(r.request().postData() || '{}'))
          return json({ success: true, data: { ...ROW, extended_measurement: true } })(r)
        }
        return r.fallback()
      })
      await page.goto('/?tab=pagespeed')

      // Kart: büyüme ihlali adlandırılır, kartın içinde ve sarılı
      const extra = page.locator('[data-slot="pspd-extra-breaches"]').first()
      await extra.waitFor({ timeout: 20_000 })
      await expect(extra).toContainText(/Weight growth|Ağırlık büyümesi/)
      await expect(extra).toContainText(/Learned weight band|Öğrenilen ağırlık bandı/)
      const card = page.locator('.upt-grid [data-slot="card"]').first()
      const cardBox = await card.boundingBox()
      const extraBox = await extra.boundingBox()
      expect(extraBox.x + extraBox.width, `@${vp.name}: ihlal satırı kartın dışına taşıyor`).toBeLessThanOrEqual(cardBox.x + cardBox.width + 1)

      // Form: düzenle
      const edit = card.getByRole('button', { name: /— (Düzenle|Edit)$/ })
      if (await edit.isVisible()) await (touch ? edit.tap() : edit.click())
      else {
        await card.locator('[data-slot="monitor-card-more"] button').first().click()
        await page.getByRole('menuitem', { name: /^(Düzenle|Edit)$/ }).click()
      }
      const dlg = page.getByRole('dialog').last()
      await expect(dlg).toBeVisible()

      const ext = dlg.locator('[data-slot="pspd-extended"]')
      await ext.scrollIntoViewIfNeeded()
      const sw = dlg.locator('[data-slot="pspd-extended-switch"]')
      await expect(sw).toHaveAttribute('aria-checked', 'false')   // mevcut izleme klasik kipte kalır
      await expect(dlg.locator('[data-slot="pspd-extended-warn"]')).toHaveCount(0)
      await (touch ? ext.tap() : ext.click())                      // satırın tamamı anahtarı çevirir (etiket)
      await expect(sw).toHaveAttribute('aria-checked', 'true')
      await expect(dlg.locator('[data-slot="pspd-extended-warn"]')).toBeVisible()

      const growth = dlg.locator('[data-slot="pspd-growth"]')
      await growth.scrollIntoViewIfNeeded()
      await expect(dlg.locator('[data-slot="pspd-growth-band-switch"]')).toHaveAttribute('aria-checked', 'true')

      if (touch) {
        // Satırın tamamı (etiket) anahtarı çevirir → dokunma hedefi satırın kendisi
        for (const slot of ['pspd-extended', 'pspd-growth-band']) {
          const b = await dlg.locator(`[data-slot="${slot}"]`).evaluate((el) => {
            const r = el.getBoundingClientRect()
            return { h: r.height, w: r.width }
          })
          expect(b.h, `@${vp.name}: ${slot} dokunma hedefi yüksekliği`).toBeGreaterThanOrEqual(39.5)
          expect(b.w, `@${vp.name}: ${slot} dokunma hedefi genişliği`).toBeGreaterThanOrEqual(39.5)
        }
      }

      // Hatalı yüzde: alanın altında hata, kayıt GİTMEZ
      const pct = dlg.getByLabel(/Weight growth threshold|Ağırlık büyümesi eşiği/)
      await pct.fill('0')
      const saveBtn = dlg.getByRole('button', { name: /^(Kaydet|Save)$/ })
      await saveBtn.scrollIntoViewIfNeeded()
      await (touch ? saveBtn.tap() : saveBtn.click())
      await expect(dlg.locator('[data-field="growthBytesPct"]')).toContainText(/1 and 1000|1 ile 1000/)
      expect(puts).toHaveLength(0)

      await page.waitForTimeout(300)
      const m = await page.evaluate(measure, '[role="dialog"]')
      expect(m.offenders, `@${vp.name}: pencerede ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `@${vp.name}: sayfa yatay kayıyor (px)`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="pspd-extended"]'), `@${vp.name}: kip açıklaması kırpılıyor`).toEqual([])
      expect(await page.evaluate(clipped, '[data-slot="pspd-growth-band"]'), `@${vp.name}: bant açıklaması kırpılıyor`).toEqual([])

      // Düzelt ve kaydet: gövde kipi ve büyüme alanlarını taşır
      await pct.fill('35')
      await (touch ? saveBtn.tap() : saveBtn.click())
      await expect.poll(() => puts.length, { timeout: 10_000 }).toBe(1)
      expect(puts[0]).toMatchObject({ extendedMeasurement: true, growthBytesPct: 35, growthBytesBand: true })
    })
  })
}
