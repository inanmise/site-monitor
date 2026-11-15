// Alan adı zenginleştirmesi (2026-11-15, paket A + C + D): liste ek süzgeçleri (registrar / kaynak / süre durumu), takvim
// (.ics) dışa aktarımı, toplu yenileme planı penceresi ve Kişisel Panom "Yakında bitecek alan adları" kutusu. Telefon /
// tablet (dokunmatik) / dizüstünde: yatay kayma yok, uzun registrar / ad sarar (kırpılmaz), dokunmatikte hedefler ≥ 40 px,
// .ics dosyası CRLF + 75 oktet katlamalı. jsdom yerleşim hesaplamaz → gerçek tarayıcı.
import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { mockApi } from './support/monitorMocks.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 800 },
]
const json = (body) => (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
const day = (offset) => { const d = new Date(); d.setDate(d.getDate() + offset); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
const LONG_REG = 'Çok Uzun Adlı Alan Adı Tescil ve Barındırma Hizmetleri Anonim Şirketi (Kurumsal Müşteriler Birimi)'
const LONG_NAME = 'Kurumsal İnternet Bankacılığı Kampanya Alan Adı (Bireysel ve Ticari — Canlı Ortam)'
const base = { team_id: 1, team_name: 'Takım A', group_name: 'Kurumsal', tags: 'prod', status: 'OK', active: true, can_edit: true,
  can_check: true, can_delete: true, warning_days: 30, critical_days: 7, checked_at: '2026-11-15T06:00:00', interval_seconds: 86400 }
const ROWS = [
  { ...base, id: 1, name: LONG_NAME, domain: 'kampanya-bireysel-ticari.example.com.tr', registrar: LONG_REG, source: 'RDAP', expiry_date: day(-2), days_remaining: 5, status: 'CRITICAL' },
  { ...base, id: 2, name: 'example.com', domain: 'example.com', registrar: 'GoDaddy.com, LLC', source: 'RDAP_REGISTRY', expiry_date: day(20), days_remaining: 20, status: 'WARNING' },
  { ...base, id: 3, name: 'ornek.com.tr', domain: 'ornek.com.tr', registrar: 'GODADDY.COM, LLC', source: 'WHOIS', expiry_date: day(200), days_remaining: 200 },
  { ...base, id: 4, name: 'bilinmiyor.example.net', domain: 'bilinmiyor.example.net', registrar: null, source: 'NONE', expiry_date: null, days_remaining: null, status: 'UNKNOWN' },
]

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
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.right <= vw + 1 || hidden(el, r)) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (clips(p) && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (contained) continue
    const slot = el.getAttribute('data-slot')
    offenders.push(`${el.tagName.toLowerCase()}${slot ? `[data-slot=${slot}]` : ''} r=${Math.round(r.right)}`)
    if (offenders.length >= 8) break
  }
  return { pageOverflow, offenders }
}
/** Metni kırpılan (ellipsis / taşma gizli) görünür öğeler — adlar sarmalı. */
const clipped = (sel) => [...document.querySelectorAll(sel)].filter((el) => {
  const cs = getComputedStyle(el)
  return el.getClientRects().length && (cs.textOverflow === 'ellipsis' || el.scrollWidth > el.clientWidth + 1)
}).map((el) => el.textContent.slice(0, 40))

for (const vp of VIEWPORTS) {
  test.describe(`alan adı zenginleştirmesi @${vp.name}`, () => {
    const touch = vp.width < 1024
    test.use({ hasTouch: touch, isMobile: touch })

    test(`süzgeçler + takvim + toplu plan @${vp.name}`, async ({ page }) => {
      test.setTimeout(120_000)
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mockApi(page)
      const bulk = []
      await page.route((u) => new URL(u).pathname === '/api/monitoring/domain', json({ success: true, data: ROWS }))
      await page.route((u) => new URL(u).pathname === '/api/monitoring/domain/renewal-plan/bulk', (r) => {
        const b = JSON.parse(r.request().postData() || '{}')
        bulk.push(b)
        return json({ success: true, data: { action: b.action, done: 2, skipped: 0, failed: 0,
          results: b.ids.map((id) => ({ id, name: ROWS.find((x) => x.id === id)?.name, status: 'done', reason: 'done' })) } })(r)
      })
      await page.goto('/?tab=domain')
      await page.locator('.upt-grid [data-slot="card"]').first().waitFor({ timeout: 20_000 })

      // Süzgeçler: üç seçici araç çubuğunda; registrar katlanır (GoDaddy iki yazımı tek seçenek, 2 kayıt)
      const reg = page.getByRole('combobox', { name: /^(Registrar)$/ })
      await expect(reg).toBeVisible()
      await (touch ? reg.tap() : reg.click())
      const godaddy = page.getByRole('option', { name: /GoDaddy\.com, LLC \(2\)/i })
      await expect(godaddy).toBeVisible()
      await (touch ? godaddy.tap() : godaddy.click())
      await expect(page.locator('.upt-grid [data-slot="card"]')).toHaveCount(2)
      // URL yazımı debounce'lu (useUrlQuerySync) — kayıtlı görünüm / paylaşılan bağlantı için `dr` adres çubuğunda
      await expect.poll(() => page.evaluate(() => new URL(location.href).searchParams.get('dr'))).toBeTruthy()
      const clear = page.locator('[data-slot="domain-filter-clear"]')
      await expect(clear).toBeVisible()
      if (touch) expect((await clear.boundingBox()).height, `@${vp.name}: temizle dokunma hedefi`).toBeGreaterThanOrEqual(39.5)
      let m = await page.evaluate(measure, null)
      expect(m.offenders, `@${vp.name}: süzgeç çubuğunda taşan öğe`).toEqual([])
      expect(m.pageOverflow).toBeLessThanOrEqual(1)
      await (touch ? clear.tap() : clear.click())
      await expect(page.locator('.upt-grid [data-slot="card"]')).toHaveCount(4)

      // Takvim (.ics): gerçek indirme, CRLF + 75 oktet katlama, bitişi bilinmeyen atlanır
      const exp = page.getByRole('button', { name: /^(Dışa aktar|Export)$/ })
      await (touch ? exp.tap() : exp.click())
      const item = page.locator('[data-slot="domain-export-ics"]')
      const [download] = await Promise.all([page.waitForEvent('download'), touch ? item.tap() : item.click()])
      expect(download.suggestedFilename()).toMatch(/\.ics$/)
      const ics = await readFile(await download.path(), 'utf8')
      expect(ics.startsWith('BEGIN:VCALENDAR\r\n')).toBe(true)
      expect(ics.replace(/\r\n/g, '').includes('\n')).toBe(false)
      for (const line of ics.split('\r\n')) expect(Buffer.byteLength(line, 'utf8')).toBeLessThanOrEqual(75)
      expect((ics.match(/BEGIN:VEVENT/g) || []).length).toBe(3)
      await expect(page.getByText(/1 monitors with an unknown expiry were skipped|bitişi bilinmeyen 1 izleme atlandı/)).toBeVisible()

      // Toplu yenileme planı: iki kart seç → çubuktaki düğme → pencere → tarih → tek istek → satır satır sonuç
      const boxes = page.locator('.upt-grid [role="checkbox"]')
      for (let i = 0; i < 2; i++) await (touch ? boxes.nth(i).tap() : boxes.nth(i).click())
      const planBtn = page.locator('[data-slot="domain-bulk-plan"]')
      await expect(planBtn).toBeVisible()
      if (touch) expect((await planBtn.boundingBox()).height, `@${vp.name}: plan düğmesi dokunma hedefi`).toBeGreaterThanOrEqual(39.5)
      await (touch ? planBtn.tap() : planBtn.click())
      const dlg = page.getByRole('dialog').last()
      await expect(dlg.locator('[data-slot="bulk-dialog"]')).toHaveAttribute('data-phase', 'preview')
      await dlg.getByLabel(/^(Plan tarihi|Plan date)/).fill(day(10))
      m = await page.evaluate(measure, '[role="dialog"]')
      expect(m.offenders, `@${vp.name}: toplu plan penceresinde taşan öğe`).toEqual([])
      expect(await page.evaluate(clipped, '[data-slot="bulk-preview-row"] .font-medium'), `@${vp.name}: ad kırpılıyor`).toEqual([])
      const apply = dlg.locator('[data-slot="bulk-confirm"]')
      await (touch ? apply.tap() : apply.click())
      await expect(dlg.locator('[data-slot="bulk-dialog"]')).toHaveAttribute('data-phase', 'result')
      expect(bulk).toHaveLength(1)
      expect(bulk[0]).toMatchObject({ action: 'set', date: day(10) })
      expect(bulk[0].ids).toHaveLength(2)
      m = await page.evaluate(measure, '[role="dialog"]')
      expect(m.offenders, `@${vp.name}: sonuç evresinde taşan öğe`).toEqual([])
    })

    test(`Panom "Yakında bitecek alan adları" @${vp.name}`, async ({ page }) => {
      test.setTimeout(90_000)
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mockApi(page, { perms: { 'monitoring.read': { view: true } },
        prefs: { dashboard: { v: 1, widgets: [{ id: 'domain-expiring', type: 'domain_expiring', size: 'md' }] } } })
      await page.route((u) => new URL(u).pathname === '/api/monitoring/domain', json({ success: true, data: ROWS }))
      await page.goto('/?tab=mydash')
      const w = page.locator('[data-slot="mydash-widget"][data-type="domain_expiring"]')
      await w.locator('[data-slot="mydash-dexp-list"]').waitFor({ timeout: 20_000 })
      await expect(w).toHaveAttribute('data-tone', 'danger')
      const first = w.locator('[data-slot="mydash-dexp-list"] li').first()
      await expect(first).toContainText('kampanya-bireysel-ticari.example.com.tr')
      await expect(first).toContainText(LONG_NAME)
      const m = await page.evaluate(measure, null)
      expect(m.offenders, `@${vp.name}: kutuda taşan öğe`).toEqual([])
      expect(m.pageOverflow).toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="mydash-dexp-list"] li *'), `@${vp.name}: satır metni kırpılıyor`).toEqual([])
      if (touch) {
        const h = await first.locator('button').first().evaluate((el) => el.getBoundingClientRect().height)
        expect(h, `@${vp.name}: satır dokunma hedefi`).toBeGreaterThanOrEqual(39.5)
      }
    })
  })
}
