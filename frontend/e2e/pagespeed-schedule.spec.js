// Sayfa Hızı 2. sürüm madde 14 (2026-11-15): Sayfa Hızı formu ortak zamanlama düzenleyicisini taşır (aralık / cron +
// izinli / yasak pencereler + tatil + atlanan günler + canlı önizleme); zamanlamalı kart bir çip taşır. Telefon / tablet
// (dokunmatik) / dizüstünde: çip kartın içinde ve sarılı, düzenleyici pencereye sığar, yatay kayma yok, kip düğmeleri
// dokunmatikte ≥ 40 px; kayıt gövdesi zamanlamayı taşır. jsdom yerleşim hesaplamaz → gerçek tarayıcı.
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
  ...MONITORS.pagespeed[0], id: 100, name: LONG_NAME, active: true, status: 'OK', ok: true,
  response_ms: 1400, ttfb_ms: 180, total_bytes: 1_250_000, request_count: 46,
  interval_seconds: 86400, schedule_mode: 'CRON', cron_expression: '0 9 * * 1-5',
  windows: [{ days: [1, 2, 3, 4, 5], from: '08:00', to: '20:00' }], blackouts: [{ days: [6, 7], from: '00:00', to: '23:59' }],
  skip_holidays: true, skip_dates: ['2026-12-31'], next_run_at: '2026-11-16T06:00:00',
  last_check: iso(60_000), checked_at: iso(60_000),
}
const PREVIEW = { success: true, data: { valid: true, errors: [], effective_interval_seconds: 86400,
  next: ['2026-11-16T06:00:00Z', '2026-11-17T06:00:00Z', '2026-11-18T06:00:00Z', '2026-11-19T06:00:00Z'] } }

// Pencerede ekran dışına taşan öğe + yatay kayma (pagespeed-enrichment.spec ile aynı ölçüm).
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

for (const vp of VIEWPORTS) {
  test.describe(`sayfa hızı zamanlaması @${vp.name}`, () => {
    const touch = vp.width < 1024
    test.use({ hasTouch: touch, isMobile: touch })

    test(`zamanlama çipi ve düzenleyici ekrana sığar, kayıt zamanlamayı taşır @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
      test.setTimeout(120_000)
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mockApi(page)
      const puts = []
      const previews = []
      await page.route((u) => new URL(u).pathname === '/api/monitoring/pagespeed', json({ success: true, data: [ROW] }))
      await page.route((u) => new URL(u).pathname === '/api/monitoring/pagespeed/schedule-preview', (r) => {
        previews.push(JSON.parse(r.request().postData() || '{}'))
        return json(PREVIEW)(r)
      })
      await page.route((u) => new URL(u).pathname === '/api/monitoring/pagespeed/100', async (r) => {
        if (r.request().method() === 'PUT') {
          puts.push(JSON.parse(r.request().postData() || '{}'))
          return json({ success: true, data: ROW })(r)
        }
        return r.fallback()
      })
      await page.goto('/?tab=pagespeed')

      // Kart: zamanlama çipi — kartın içinde, uzun özet sarar (kesilmez)
      const chip = page.locator('[data-slot="pspd-schedule-chip"]').first()
      await chip.waitFor({ timeout: 20_000 })
      await expect(chip).toHaveAttribute('data-schedule', 'cron')
      const card = page.locator('.upt-grid [data-slot="card"]').first()
      const cardBox = await card.boundingBox()
      const chipBox = await chip.boundingBox()
      expect(chipBox.x + chipBox.width, `@${vp.name}: zamanlama çipi kartın dışına taşıyor`).toBeLessThanOrEqual(cardBox.x + cardBox.width + 1)
      expect(await chip.evaluate((el) => el.scrollWidth <= el.clientWidth + 1), `@${vp.name}: çip metni kırpılıyor`).toBe(true)

      // Form: ortak zamanlama düzenleyicisi cron kipinde, önizleme Sayfa Hızı ucuna gider
      const edit = card.getByRole('button', { name: /— (Düzenle|Edit)$/ })
      if (await edit.isVisible()) await (touch ? edit.tap() : edit.click())
      else {
        await card.locator('[data-slot="monitor-card-more"] button').first().click()
        await page.getByRole('menuitem', { name: /^(Düzenle|Edit)$/ }).click()
      }
      const dlg = page.getByRole('dialog').last()
      await expect(dlg).toBeVisible()
      const editor = dlg.locator('[data-slot="dbq-schedule-editor"]')
      await editor.scrollIntoViewIfNeeded()
      await expect(editor).toHaveAttribute('data-mode', 'CRON')
      await expect.poll(() => previews.length, { timeout: 10_000 }).toBeGreaterThan(0)
      expect(previews[0].schedule_mode).toBe('CRON')
      expect(previews[0].cron_expression).toBe('0 9 * * 1-5')

      if (touch) {
        const modes = editor.locator('[data-field="schedule_mode"] [data-slot="toggle-group-item"]')
        await expect(modes).toHaveCount(2)
        for (let i = 0; i < 2; i++) {
          const h = await modes.nth(i).evaluate((el) => el.getBoundingClientRect().height)
          expect(h, `@${vp.name}: kip düğmesi ${i + 1} dokunma hedefi`).toBeGreaterThanOrEqual(39.5)
        }
      }
      await page.waitForTimeout(700)   // önizleme yanıtı çizilsin
      const m = await page.evaluate(measure, '[role="dialog"]')
      expect(m.offenders, `@${vp.name}: düzenleyicide ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `@${vp.name}: sayfa yatay kayıyor (px)`).toBeLessThanOrEqual(1)

      // Kayıt: gövde zamanlamayı taşır (satır alanları taşıyor → anahtarlar gider)
      const saveBtn = dlg.getByRole('button', { name: /^(Kaydet|Save)$/ })
      await saveBtn.scrollIntoViewIfNeeded()
      await (touch ? saveBtn.tap() : saveBtn.click())
      await expect.poll(() => puts.length, { timeout: 10_000 }).toBe(1)
      expect(puts[0].schedule_mode).toBe('CRON')
      expect(puts[0].cron_expression).toBe('0 9 * * 1-5')
      expect(puts[0].windows).toEqual(ROW.windows)
      expect(puts[0].skip_holidays).toBe(true)
      expect(puts[0].skip_dates).toEqual(['2026-12-31'])
    })
  })
}
