// Olay & Hata Geçmişi — yeni olay formunun tarih alanları (2026-11-17, kullanıcı isteği: "tarih alanlarına çok zor giriş
// yapılıyor; çok daha efektif ve üst düzey bir tarih seçimi"). Gerçek tarayıcıda 390 (dokunmatik) / 768 (dokunmatik) / 1280:
// yazarak giriş ("dün 09:15"), okunamayan metin önceki değeri korur, hızlı seçim çipi (+15 dk), takvim + saat / dakika
// listeleri; alanlar ve panel ekrana sığar, metin kırpılmaz, dokunmatik hedefler ≥ 40 px; kaydın gövdesi UTC ISO taşır.
// API mock'lu, saat sabit (İstanbul).
import { test, expect } from '@playwright/test'

const NOW = new Date('2026-11-16T12:42:00Z')   // İstanbul 16.11.2026 15:42
let created = null

async function mock(page) {
  created = null
  await page.addInitScript(() => {
    try {
      localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 }))
      localStorage.setItem('site-monitor-lang', 'tr')
    } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    let body = { success: true, data: [] }
    if (p === '/api/me') body = { success: true, username: 'demo', system_role: 'ADMIN', global_admin: true, team_id: 5, team_ids: [5], tour: { status: 'dismissed', version: 99 } }
    else if (p === '/api/me/permissions') body = { success: true, data: { 'incidents.view': { view: true }, 'incidents.manage': { edit: true } } }
    else if (p === '/api/admin/teams') body = { success: true, data: [{ id: 5, name: 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi' }] }
    else if (p === '/api/incidents' && req.method() === 'POST') {
      created = JSON.parse(req.postData() || '{}')
      body = { success: true, data: { id: 9, ...created } }
    } else if (p === '/api/incidents') body = { success: true, data: [], total: 0, page: 0, size: 20 }
    else if (p === '/api/incidents/trends') body = { success: true, data: { summary: { total: 0, open: 0, resolved: 0 }, by_severity: {}, by_status: {}, daily: [] } }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

/** Görünür etkileşimli öğelerden 40 px'in altında kalanlar (dokunmatik): yükseklik, ya da ::after vuruş alanı. */
function smallTargets(root) {
  const out = []
  for (const b of root.querySelectorAll('button, select, input')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled) continue
    let h = r.height
    const after = getComputedStyle(b, '::after')
    if (after.position === 'absolute' && after.height && after.height !== 'auto') h = Math.max(h, parseFloat(after.height) || 0)
    if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

/** Kırpılan / taşan metin (tek düğümlü yaprak öğeler); ekran okuyucuya ayrılmış görünmez öğeler (≤ 2 px) sayılmaz. */
function clipped(root) {
  return [...root.querySelectorAll('span, p, label, button')]
    .filter((n) => { const r = n.getBoundingClientRect(); return r.width > 2 && r.height > 2 })
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 3
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis'))
    .map((n) => n.textContent.slice(0, 40))
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Olay tarih seçici — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch, timezoneId: 'Europe/Istanbul' })

    test('yazarak giriş, hızlı seçim, takvim + saat listeleri; sığar, kırpılmaz, dokunmatik ≥ 40 px; gövde UTC ISO', async ({ page }) => {
      test.setTimeout(120_000)
      await page.clock.setFixedTime(NOW)
      await mock(page)
      await page.goto('/?tab=incident-history')
      await page.getByRole('button', { name: /^Yeni Olay$/ }).first().click()
      const dlg = page.getByRole('dialog', { name: /Yeni Olay Kaydı/ })
      await expect(dlg).toBeVisible()

      // 1) Oluş zamanı: yazarak ("dün 09:15"), Tab ile biçimlenir; bağlam satırı "… önce · gün"
      const occurred = dlg.getByRole('textbox', { name: /^Oluş Zamanı/ })
      await occurred.fill('dün 09:15')
      await occurred.press('Tab')
      await expect(occurred).toHaveValue('15.11.2026 09:15')
      const fields = dlg.locator('[data-slot="smart-datetime"]')
      await expect(fields.nth(0).locator('[data-slot="smart-datetime-context"]')).toContainText(/önce · pazar/i)

      // 2) Okunamayan metin: önceki değer korunur, açıklama görünür
      await occurred.fill('31.02.2026 10:00')
      await occurred.press('Tab')
      await expect(occurred).toHaveValue('15.11.2026 09:15')
      await expect(fields.nth(0).locator('[data-slot="smart-datetime-problem"]')).toContainText('31.02.2026 10:00')
      await occurred.fill('15.11.2026 09:15')
      await occurred.press('Tab')
      await expect(fields.nth(0).locator('[data-slot="smart-datetime-problem"]')).toHaveCount(0)

      // 3) Tespit zamanı: oluşa göre "+15 dk" çipi
      const detected = dlg.getByRole('textbox', { name: /^Tespit Zamanı/ })
      await fields.nth(1).getByRole('button', { name: /Oluş Zamanı zamanından 15 dk sonra/ }).click()
      await expect(detected).toHaveValue('15.11.2026 09:30')
      await expect(fields.nth(1).locator('[data-slot="smart-datetime-context"]')).toContainText('Oluş Zamanı zamanından 15 dk sonra')

      // 4) Çözülme zamanı: takvim + saat / dakika listeleri
      const resolved = dlg.getByRole('textbox', { name: /^Çözülme Zamanı/ })
      await fields.nth(2).getByRole('button', { name: /Takvimden ve saat listesinden seç/ }).click()
      const panel = page.locator('[data-slot="smart-datetime-panel"]')
      await expect(panel).toBeVisible()
      await expect(panel.locator('select')).toHaveCount(2)                         // ay + yıl açılır listeleri
      // Açılışta panel başından görünür (seçili saat yalnız kendi listesinde ortalanır, panel kaydırılmaz)
      expect(await panel.evaluate((el) => el.scrollTop)).toBe(0)
      await expect(panel.locator('select').first().locator('option:checked')).toHaveText('Kas')   // ay adı uygulama dilinde
      await panel.getByRole('grid').getByRole('button', { name: /15/ }).first().click()
      await panel.locator('[data-slot="smart-datetime-hours"]').getByRole('radio', { name: 'Saat 11' }).click()
      await panel.locator('[data-slot="smart-datetime-minutes"]').getByRole('radio', { name: 'Dakika 45' }).click()
      await expect(panel.locator('[data-slot="smart-datetime-panel-value"]')).toHaveText('15.11.2026 11:45')
      const box = await panel.boundingBox()
      expect(box.x).toBeGreaterThanOrEqual(0)
      expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 0.5)                // panel ekrana sığar
      if (vp.touch) expect(await panel.evaluate(smallTargets)).toEqual([])
      expect(await panel.evaluate(clipped)).toEqual([])
      await panel.getByRole('button', { name: /^Tamam$/ }).click()
      await expect(panel).toBeHidden()
      await expect(resolved).toHaveValue('15.11.2026 11:45')
      // Değerle yeniden açılış: panel başından görünür, seçili saat ve dakika kendi listelerinde görünür (ortalanmış)
      await fields.nth(2).getByRole('button', { name: /Takvimden ve saat listesinden seç/ }).click()
      await expect(panel).toBeVisible()
      await expect(panel.locator('[data-slot="smart-datetime-hours"] [data-state="on"]')).toBeInViewport()
      await expect(panel.locator('[data-slot="smart-datetime-minutes"] [data-state="on"]')).toBeInViewport()
      expect(await panel.evaluate((el) => el.scrollTop)).toBe(0)
      await page.keyboard.press('Escape')
      await expect(panel).toBeHidden()
      await expect(resolved).toHaveValue('15.11.2026 11:45')
      await expect(fields.nth(2).locator('[data-slot="smart-datetime-context"]')).toContainText('2 sa 30 dk sonra')

      // 5) Yerleşim: diyalog yatay kaydırma yok, alanlar kırpılmaz, dokunmatik hedefler ≥ 40 px
      const area = dlg.locator('[role="tabpanel"]').first()
      expect(await area.evaluate((el) => {
        const lim = el.getBoundingClientRect().right + 1
        if (el.scrollWidth <= el.clientWidth + 1) return []
        return [...el.querySelectorAll('*')].filter((n) => n.getBoundingClientRect().right > lim)
          .map((n) => `${n.tagName}.${(n.getAttribute('data-slot') || n.className || '').toString().slice(0, 60)} ${Math.round(n.getBoundingClientRect().right)}>${Math.round(lim)}`).slice(0, 8)
      })).toEqual([])
      for (let i = 0; i < 3; i++) {
        const f = fields.nth(i)
        expect(await f.evaluate(clipped)).toEqual([])
        if (vp.touch) expect(await f.evaluate(smallTargets)).toEqual([])
        const fb = await f.boundingBox()
        expect(fb.x + fb.width).toBeLessThanOrEqual(vp.width + 0.5)
      }

      // 6) Kaydet: gövdede UTC ISO (İstanbul − 3 sa) ve otomatik süre (150 dk)
      await dlg.getByRole('textbox', { name: /^Başlık/ }).fill('Ödeme servisinde kesinti')
      await dlg.getByRole('combobox', { name: /^Takım/ }).selectOption('5')
      await dlg.locator('[data-action="save"]').click()
      await expect.poll(() => created).not.toBeNull()
      expect(created.occurred_at).toBe('2026-11-15T06:15:00')
      expect(created.detected_at).toBe('2026-11-15T06:30:00')
      expect(created.resolved_at).toBe('2026-11-15T08:45:00')
      expect(Number(created.duration_minutes)).toBe(150)
    })
  })
}
