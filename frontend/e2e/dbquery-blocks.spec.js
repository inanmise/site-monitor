import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

// 2026-10-31 (Veritabanı Sorgu İzleme — yapı taşları): Ayarlar → Platform → Tatil takvimi ve alarm ayrıntısındaki "Alarm
// anındaki sorgu sonucu" bölümü telefon (390, dokunmatik), tablet (768, dokunmatik) ve dizüstünde (1280): sayfa yatay
// kaymaz, öğe ekran dışına taşmaz, uzun metin (AD takım adı, uzun tatil adı, uzun hücre) kırpılmaz, dokunmatikte hedefler
// ≥ 40 px; geniş sonuç tablosu KENDİ kabında yatay kayar (sayfa değil); gün ekle + kaydet tam listeyi PUT eder; paylaşımla
// okuyan satır görmez.
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]
const Y = new Date().getFullYear()
const LONG_HOLIDAY = 'Ramazan Bayramı Arifesi ve Bayramın Birinci Günü (arife yarım günü tam gün sayılır — kurum içi ek tatil)'
const DAYS = [
  { day: `${Y}-01-01`, name: 'Yılbaşı' },
  { day: `${Y}-04-23`, name: 'Ulusal Egemenlik ve Çocuk Bayramı' },
  { day: `${Y}-05-19`, name: 'Atatürk\'ü Anma, Gençlik ve Spor Bayramı' },
  { day: `${Y}-10-29`, name: 'Cumhuriyet Bayramı' },
  { day: `${Y}-12-31`, name: LONG_HOLIDAY },
]
const LONG_CELL = 'Ödeme sağlayıcısından zaman aşımı döndü; işlem yeniden kuyruğa alındı ve müşteriye SMS ile bilgi verildi (referans 9f2c1e7a4b5d6c8e0f1a2b3c4d5e6f70)'
const COLS = ['SIPARIS_NO', 'MUSTERI_TCKN', 'DURUM', 'KANAL', 'TUTAR', 'PARA_BIRIMI', 'OLUSTURMA_ZAMANI', 'SON_DENEME_ZAMANI', 'ACIKLAMA']
const ROWS = Array.from({ length: 8 }, (_, i) => [String(10000 + i), '••••', 'HATA', 'MOBIL', '1250,50', 'TRY', '2026-10-31 08:59:00', '2026-10-31 09:00:00', i === 0 ? LONG_CELL : null])
const ALERT = {
  id: 501, domain: 'Bekleyen ödeme siparişleri — gün sonu mutabakat izlemesi', alert_type: 'DBQUERY_THRESHOLD', alert_level: 'CRITICAL',
  resolved: false, acknowledged: false, created_at: new Date(Date.now() - 600_000).toISOString().slice(0, 19), team_id: 1, team_name: 'Takım A',
  message: 'Satır sayısı 120 (kritik eşik > 100)',
}

const json = (body, status = 200) => (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

async function routes(page, { rows = 'ok' } = {}) {
  await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yok */ } })
  await mockApi(page, { role: 'ADMIN', globalAdmin: true })
  const calls = { puts: [] }
  let days = DAYS.slice()
  await page.route((u) => new URL(u).pathname === '/api/admin/holidays', (route) => {
    if (route.request().method() === 'PUT') {
      const body = JSON.parse(route.request().postData() || '{}')
      calls.puts.push(body)
      days = body.days
    }
    return json({ success: true, data: { days, can_edit: true } })(route)
  })
  await page.route((u) => new URL(u).pathname === '/api/admin/alerts/501', json({ success: true, data: ALERT }))
  await page.route((u) => new URL(u).pathname === '/api/monitoring/dbquery/alerts/501/rows', json(rows === 'hidden'
    ? { success: true, data: { hidden: true, row_count: 120, row_count_capped: false } }
    : { success: true, data: { captured_at: ALERT.created_at, columns: COLS, rows: ROWS, masked: ['MUSTERI_TCKN'], row_count: 120, row_count_capped: false, truncated: true, hidden: false } }))
  return calls
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, select, input:not([type="hidden"]), textarea, [role="combobox"], a[href]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled) continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none' || b.getAttribute('aria-hidden') === 'true') continue
    const role = b.getAttribute('role')
    if (role === 'checkbox') continue
    let h = r.height
    if (role === 'switch') h = Math.max(h, b.parentElement.getBoundingClientRect().height)
    const af = getComputedStyle(b, '::after')
    if (af.position === 'absolute' && af.content !== 'none') {
      const ah = parseFloat(af.height)
      if (Number.isFinite(ah)) h = Math.max(h, ah)
      const top = parseFloat(af.top) || 0
      const bottom = parseFloat(af.bottom) || 0
      h = Math.max(h, r.height - Math.min(0, top) - Math.min(0, bottom))
    }
    if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || b.id || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('span, p, li, h3, h4, label, button, dt, dd, code, td, th')]
    .filter((n) => !n.closest('.sr-only') && !n.closest('pre') && !n.closest('select') && !n.closest('[role="combobox"]'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 12
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')).map((n) => n.textContent.slice(0, 40))
}

function offenders(sel) {
  const vw = document.documentElement.clientWidth
  const root = document.querySelector(sel) || document.body
  const out = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    const cs = getComputedStyle(el)
    if (r.width <= 1 || r.height <= 1 || cs.visibility === 'hidden' || cs.display === 'none') continue
    if (r.right <= vw + 1 && r.left >= -1) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (!contained) out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || ''}] ${Math.round(r.left)}..${Math.round(r.right)}`)
    if (out.length >= 8) break
  }
  return out
}

const overflowX = () => document.documentElement.scrollWidth - document.documentElement.clientWidth

for (const vp of VIEWPORTS) {
  test.describe(`dbquery yapı taşları — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('Ayarlar → Tatil takvimi: sığar, kırpılmaz, dokunma hedefleri; gün ekle + kaydet tam listeyi PUT eder', async ({ page }) => {
      test.setTimeout(120_000)
      const calls = await routes(page)
      await page.goto('/?tab=settings&sec=holidays')
      const root = page.locator('[data-slot="holiday-settings"]')
      await expect(root.locator('[data-slot="holiday-row"]')).toHaveCount(5, { timeout: 20_000 })
      await expect(root.locator(`[data-slot="holiday-row"][data-day="${Y}-12-31"]`)).toContainText(LONG_HOLIDAY)
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(offenders, '[data-slot="holiday-settings"]'), `@${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(await page.evaluate(clipped, '[data-slot="holiday-settings"]'), `@${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="holiday-settings"]'), `@${vp.name}: küçük dokunma hedefi`).toEqual([])
      await page.screenshot({ path: `test-results/dbquery-holidays-${vp.name}.png`, fullPage: false })

      // Ad boşken ekle → alanın altında hata; doldurup ekle → kaydet çubuğu kirli → kaydet tam listeyi gönderir
      const add = root.locator('[data-slot="holiday-add"]')
      await add.getByRole('button', { name: 'Ekle' }).click()
      await expect(add).toContainText('Tarihi seçin.')
      await expect(add).toContainText('Günün adını yazın.')
      await add.locator('[data-slot="date-picker-trigger"]').click()
      const dayBtn = page.locator('[data-slot="calendar"] button:not([disabled])', { hasText: /^15$/ }).first()
      await dayBtn.click()
      await add.getByLabel(/^Ad/).fill('Kurum içi bakım günü')
      await add.getByRole('button', { name: 'Ekle' }).click()
      const bar = root.locator('[data-slot="settings-save-bar"]')
      await expect(bar).toHaveAttribute('data-dirty', 'true')
      await bar.getByRole('button', { name: /Kaydet/ }).click()
      await expect.poll(() => calls.puts.length).toBe(1)
      const sent = calls.puts[0].days
      expect(sent).toHaveLength(6)
      expect(sent.map((d) => d.day)).toEqual([...sent.map((d) => d.day)].sort())
      expect(sent.some((d) => d.name === 'Kurum içi bakım günü')).toBe(true)
      await expect(bar).not.toHaveAttribute('data-dirty', 'true')
      // Tam sayfa görüntüsü EN SONDA — dokunmatik öykünmesini sıfırlar (CLAUDE.md, e2e fullPage tuzağı)
      await page.screenshot({ path: `test-results/dbquery-holidays-${vp.name}-full.png`, fullPage: true })
    })

    test('alarm ayrıntısı: alarm anındaki satırlar — geniş tablo kendi kabında kayar, uzun hücre sarar, maskeli kolon rozeti', async ({ page }) => {
      test.setTimeout(120_000)
      await routes(page)
      await page.goto('/?tab=alerthistory&alert=501')
      const sec = page.locator('[data-slot="dbq-alert-rows"][data-state="ok"]')
      await expect(sec).toBeVisible({ timeout: 20_000 })
      await expect(sec.locator('[data-slot="dbq-alert-row"]')).toHaveCount(8)
      await expect(sec.locator('[data-slot="dbq-alert-rows-count"]')).toHaveText('120 satır döndü')
      await expect(sec.locator('[data-slot="dbq-alert-rows-partial"]')).toHaveText('ilk 8 satır gösteriliyor')
      await expect(sec.locator('[data-slot="dbq-alert-rows-masked"]')).toContainText('MUSTERI_TCKN')
      await sec.scrollIntoViewIfNeeded()
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), `@${vp.name}: sayfa yatay taşma`).toBeLessThanOrEqual(1)
      // Tablo KENDİ kabında yatay kayar (dokuz kolon her genişlikte sığmaz → kaydırılabilir olmalı, sayfa değil)
      const scroller = await sec.locator('[data-slot="table-container"]').evaluate((el) => ({ sw: el.scrollWidth, cw: el.clientWidth, ox: getComputedStyle(el).overflowX }))
      expect(scroller.ox, `@${vp.name}: tablo kabı kaydırılabilir`).toBe('auto')
      expect(scroller.sw, `@${vp.name}: geniş tablo kapta kayar`).toBeGreaterThan(scroller.cw)
      // Uzun hücre sarar — kırpılmaz, tek satıra zorlanmaz
      const cell = sec.locator('td', { hasText: 'referans 9f2c1e7a' })
      const c = await cell.evaluate((el) => ({ sw: el.scrollWidth, cw: el.clientWidth, h: el.getBoundingClientRect().height, ws: getComputedStyle(el).whiteSpace }))
      expect(c.ws).toBe('pre-wrap')
      expect(c.sw, `@${vp.name}: uzun hücre kırpıldı`).toBeLessThanOrEqual(c.cw + 1)
      expect(c.h, `@${vp.name}: uzun hücre tek satıra sığdırılmış`).toBeGreaterThan(30)
      expect(await page.evaluate(clipped, '[data-slot="dbq-alert-rows"]'), `@${vp.name}: kırpılan metin`).toEqual([])
      await page.screenshot({ path: `test-results/dbquery-alert-rows-${vp.name}.png`, fullPage: false })
    })

    test('alarm ayrıntısı: paylaşımla okuyan satır görmez (yalnız sayı)', async ({ page }) => {
      test.setTimeout(90_000)
      await routes(page, { rows: 'hidden' })
      await page.goto('/?tab=alerthistory&alert=501')
      const sec = page.locator('[data-slot="dbq-alert-rows"][data-state="hidden"]')
      await expect(sec).toBeVisible({ timeout: 20_000 })
      await expect(sec).toContainText('Bu alarmı paylaşımla görüyorsunuz; sonuç satırları gösterilmez (120 satır).')
      await expect(sec.locator('[data-slot="dbq-alert-row"]')).toHaveCount(0)
      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma`).toBeLessThanOrEqual(1)
    })
  })
}
