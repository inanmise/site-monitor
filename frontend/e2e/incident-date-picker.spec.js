// Olay & Hata Geçmişi — yeni olay formunun tarih alanları (2026-11-17, kullanıcı isteği: "tarih alanlarına çok zor giriş
// yapılıyor; çok daha efektif ve üst düzey bir tarih seçimi"). Gerçek tarayıcıda 390 (dokunmatik) / 768 (dokunmatik) / 1280:
// yazarak giriş ("dün 09:15"), okunamayan metin önceki değeri korur, hızlı seçim çipi (+15 dk), takvim + saat / dakika
// listeleri; alanlar ve panel ekrana sığar, metin kırpılmaz, dokunmatik hedefler ≥ 40 px; kaydın gövdesi UTC ISO taşır.
// API mock'lu, saat sabit (İstanbul).
import { test, expect } from '@playwright/test'

const NOW = new Date('2026-11-16T12:42:00Z')   // İstanbul 16.11.2026 15:42
let created = null
/** Doluysa bir SONRAKİ kayıt isteği bu gövdeyle 400 döner (sunucu alan hatası senaryosu); tek kullanımlık. */
let createError = null

async function mock(page) {
  created = null
  createError = null
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
    else if (p === '/api/incidents' && req.method() === 'POST' && createError) {
      const refused = createError
      createError = null
      await route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify(refused) })
      return
    } else if (p === '/api/incidents' && req.method() === 'POST') {
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
      await fields.nth(1).getByRole('button', { name: /15 dk sonra \(esas: Oluş Zamanı\)/ }).click()
      await expect(detected).toHaveValue('15.11.2026 09:30')
      await expect(fields.nth(1).locator('[data-slot="smart-datetime-context"]')).toContainText('15 dk sonra (esas: Oluş Zamanı)')

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

    // Etki bölümündeki üç isteğe bağlı alan (2026-11-20): "Teknik hata adedi", üç durumlu "Kesinti kaynaklı mı?" (Evet /
    // Hayır / seçilmemiş = bilinmiyor) ve yalnız Evet'te açılan "Kesinti ID". Alanlar taşmaz / kırpılmaz, dokunmatik
    // hedefler ≥ 40 px, geçersiz adet alanın altında söylenir, kayıt gövdesi üç anahtarı taşır.
    test('Etki bölümü: teknik hata adedi + kesinti kaynaklı mı + Kesinti ID — sığar, kırpılmaz, dokunmatik ≥ 40 px; gövde üç anahtarı taşır', async ({ page }) => {
      test.setTimeout(120_000)
      await page.clock.setFixedTime(NOW)
      await mock(page)
      await page.goto('/?tab=incident-history')
      await page.getByRole('button', { name: /^Yeni Olay$/ }).first().click()
      const dlg = page.getByRole('dialog', { name: /Yeni Olay Kaydı/ })
      await expect(dlg).toBeVisible()

      // Zorunlu alanlar (Özet bölümü)
      await dlg.getByRole('textbox', { name: /^Başlık/ }).fill('Ödeme servisinde kesinti')
      const occurred = dlg.getByRole('textbox', { name: /^Oluş Zamanı/ })
      await occurred.fill('16.11.2026 10:00')
      await occurred.press('Tab')
      await expect(occurred).toHaveValue('16.11.2026 10:00')
      await dlg.getByRole('combobox', { name: /^Takım/ }).selectOption('5')

      // 1) Etki bölümü: adet kutusu + ipucu, üç durumlu seçim (başta ikisi de seçili DEĞİL), Kesinti ID yok
      await dlg.getByRole('tab', { name: /^Etki/ }).click()
      const panel = dlg.locator('[role="tabpanel"]').first()
      const count = dlg.getByRole('textbox', { name: 'Teknik hata adedi' })
      await expect(count).toBeVisible()
      await expect(count).toHaveAttribute('inputmode', 'numeric')
      await expect(dlg.getByText('Bu olaydan kaynaklanan toplam teknik hata adedi; bilinmiyorsa boş bırakın. Teknik hata raporuna içe alınırken konunun hata adedine yazılır.')).toBeVisible()
      const group = dlg.getByRole('radiogroup', { name: 'Kesinti kaynaklı mı?' })
      const yes = group.getByRole('radio', { name: 'Evet' })
      const no = group.getByRole('radio', { name: 'Hayır' })
      await expect(group).toHaveAttribute('data-answer', 'unknown')
      await expect(yes).toHaveAttribute('aria-checked', 'false')
      await expect(no).toHaveAttribute('aria-checked', 'false')
      const outageId = dlg.getByRole('textbox', { name: 'Kesinti ID' })
      await expect(outageId).toHaveCount(0)

      // 2) Evet → Kesinti ID açılır, boşken odak ona gider
      const ID = 'INC-2026-0915-ODEME-SISTEMLERI-KART-ISLEMLERI-0001'
      await count.fill('1250')
      await yes.click()
      await expect(yes).toHaveAttribute('aria-checked', 'true')
      await expect(outageId).toBeVisible()
      await expect(outageId).toBeFocused()
      await outageId.fill(ID)

      const overflow = (el) => {
        const lim = el.getBoundingClientRect().right + 1
        if (el.scrollWidth <= el.clientWidth + 1) return []
        return [...el.querySelectorAll('*')].filter((n) => n.getBoundingClientRect().right > lim)
          .map((n) => `${n.tagName}.${(n.getAttribute('data-slot') || n.className || '').toString().slice(0, 60)} ${Math.round(n.getBoundingClientRect().right)}>${Math.round(lim)}`).slice(0, 8)
      }
      const checkLayout = async () => {
        expect(await panel.evaluate(overflow)).toEqual([])
        for (const name of ['tech_error_count', 'outage_related', 'outage_id']) {
          const f = dlg.locator(`[data-field="${name}"]`)
          await expect(f).toBeVisible()
          expect(await f.evaluate(clipped), name).toEqual([])
          if (vp.touch) expect(await f.evaluate(smallTargets), name).toEqual([])
          const fb = await f.boundingBox()
          expect(fb.x, name).toBeGreaterThanOrEqual(0)
          expect(fb.x + fb.width, name).toBeLessThanOrEqual(vp.width + 0.5)
        }
        // Sayfa da yatay kaymaz
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true)
      }
      await checkLayout()

      // 3) Üç durum: seçili Evet'e yeniden bas → bilinmiyor (ID gizlenir); Hayır → ID yok; Evet'e dönünce yazılan kimlik yerinde
      await yes.click()
      await expect(group).toHaveAttribute('data-answer', 'unknown')
      await expect(yes).toHaveAttribute('aria-checked', 'false')
      await expect(no).toHaveAttribute('aria-checked', 'false')
      await expect(outageId).toHaveCount(0)
      await no.click()
      await expect(group).toHaveAttribute('data-answer', 'no')
      await expect(outageId).toHaveCount(0)
      await yes.click()
      await expect(outageId).toHaveValue(ID)

      // 4) Geçersiz adet: kayıt gitmez, neden ALANIN altında yazar (sığar, kırpılmaz); yazılan metin korunur
      await count.fill('1.250')
      await dlg.locator('[data-action="save"]').click()
      const countField = dlg.locator('[data-field="tech_error_count"]')
      await expect(countField).toContainText('Yalnız rakamlardan oluşan bir tam sayı yazın')
      await expect(count).toHaveAttribute('aria-invalid', 'true')
      await expect(count).toHaveValue('1.250')
      expect(created).toBeNull()
      await checkLayout()
      await count.fill('1250')
      await expect(count).not.toHaveAttribute('aria-invalid', 'true')

      // 5) SUNUCU alan hatası (400 VALIDATION_FAILED + fields): kullanıcı Özet bölümündeyken kaydeder → Etki bölümüne
      //    geçilir, ileti Kesinti ID'nin ALTINDA durur, odak o alandadır; üstte "işlem başarısız" bandı YOK
      const SERVER_MSG = 'Kesinti ID en çok 60 karakter olabilir (girilen: 61). Yalnız kesinti kaydının kimliğini yazın.'
      createError = { success: false, code: 'VALIDATION_FAILED', error: SERVER_MSG, field: 'outage_id', fields: { outage_id: SERVER_MSG } }
      await dlg.getByRole('tab', { name: /^Özet/ }).click()
      await expect(dlg.getByRole('textbox', { name: /^Başlık/ })).toBeVisible()
      await dlg.locator('[data-action="save"]').click()
      await expect(dlg.getByRole('tab', { name: /^Etki/ })).toHaveAttribute('aria-selected', 'true')
      const idField = dlg.locator('[data-field="outage_id"]')
      await expect(idField).toContainText(SERVER_MSG)
      await expect(outageId).toHaveAttribute('aria-invalid', 'true')
      await expect(outageId).toBeFocused()
      await expect(dlg.getByRole('alert')).toHaveCount(0)
      await expect(dlg.getByRole('tab', { name: /^Etki/ })).toHaveAttribute('data-invalid', 'true')
      await expect(outageId).toHaveValue(ID)                          // form kaybolmaz
      expect(created).toBeNull()
      await checkLayout()                                             // uzun sunucu iletisi alanın altında sığar, kırpılmaz
      // Alan düzenlenince sunucunun iletisi kalkar
      const ID_FIXED = 'INC-2026-0915-ODEME-SISTEMLERI-KART-ISLEMLERI-0002'
      await outageId.fill(ID_FIXED)
      await expect(idField).not.toContainText(SERVER_MSG)
      await expect(outageId).not.toHaveAttribute('aria-invalid', 'true')

      // 6) Kaydet: gövde üç anahtarı taşır — adet SAYI, cevap boolean, kimlik metin
      await dlg.locator('[data-action="save"]').click()
      await expect.poll(() => created).not.toBeNull()
      expect(Object.keys(created)).toEqual(expect.arrayContaining(['tech_error_count', 'outage_related', 'outage_id']))
      expect(created.tech_error_count).toBe(1250)
      expect(created.outage_related).toBe(true)
      expect(created.outage_id).toBe(ID_FIXED)
      expect(created.title).toBe('Ödeme servisinde kesinti')
    })
  })
}
