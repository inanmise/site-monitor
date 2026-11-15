// Sayfa Hızı 2. sürüm (2026-11-15): "Şelale ve hız notu" sekmesi — hız notu + öneriler, şelale (gerçek uzunlukta adresler),
// görsel satırında görsel aracı menüsü, iki ölçümü karşılaştırma penceresi, PDF / Excel raporu (gerçek indirme). Telefon /
// tablet (dokunmatik) / dizüstünde ekrana sığar, yatay kayma yok, ham çeviri anahtarı yok, yeni denetimler dokunmatikte
// ≥ 40 px. jsdom yerleşim hesaplamaz → gerçek tarayıcı. API tamamen mock'lu.
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
const LONG_URL = 'https://cok-uzun-bir-alt-alan-adi.odeme-servisleri.example.com/statik/paketler/vendor.app.min.js?v=20261114'
const IMG_URL = 'https://cdn.example.net/gorseller/kampanya-banner-cok-buyuk-gorsel-dosyasi-2026-kasim.png'

const ROW = {
  ...MONITORS.pagespeed[0], id: 100, name: LONG_NAME, active: true, status: 'SLOW', ok: true, can_check: true,
  response_ms: 6200, ttfb_ms: 820, total_bytes: 3_250_000, request_count: 96, html_ms: 910, breached_metrics: ['LOAD'],
  max_load_ms: 5000, last_check: iso(60_000), checked_at: iso(60_000),
}

function snapshot(checkId, slower) {
  const k = slower ? 1.6 : 1
  return { success: true, data: {
    check_id: checkId, has_start: true, truncated: false, total: 4,
    check: { checked_at: iso(slower ? 86_400_000 : 60_000), ttfb_ms: Math.round(820 * k), html_ms: 910, response_ms: Math.round(6200 * k),
      total_bytes: 3_250_000, html_bytes: 84_000, request_count: 96, failed_count: 1, dns_ms: 24, connect_ms: 31, tls_ms: 58, server_ms: 700,
      download_ms: 120, time_truncated: false },
    resources: [
      { url: LONG_URL, type: 'JS', start_ms: 930, duration_ms: Math.round(820 * k), bytes: 640_000, http_status: 200, third_party: false, check_id: checkId },
      { url: IMG_URL, type: 'IMG', start_ms: 1200, duration_ms: 2400, bytes: slower ? 1_600_000 : 1_200_000, http_status: 200, third_party: true, check_id: checkId },
      { url: 'https://cdn.example.net/eksik.woff2', type: 'FONT', start_ms: 1300, duration_ms: 90, bytes: 0, http_status: 404, third_party: true, failed: true, check_id: checkId },
      { url: 'https://www.example.com/stil.css', type: 'CSS', start_ms: 915, duration_ms: 60, bytes: 40_000, http_status: 200, check_id: checkId },
    ],
  } }
}

const RESOURCES = { success: true, data: {
  resources: [], total: 0, filtered_total: 0, page: 0, size: 1, facets: {},
  breaches: [{ check_id: 55, checked_at: iso(86_400_000), breached_metrics: ['LOAD'] }],
} }

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

// Metni kesilen (ellipsis / tek satıra sıkıştırılmış) öğeler — ad ve adres asla kesilmez
const clipped = (sel) => {
  const root = document.querySelector(sel)
  const out = []
  for (const el of root.querySelectorAll('*')) {
    if (el.closest('.sr-only') || !el.childNodes.length) continue
    const hasText = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim())
    if (!hasText) continue
    const cs = getComputedStyle(el)
    if (cs.textOverflow === 'ellipsis' || (el.scrollWidth > el.clientWidth + 1 && cs.overflowX !== 'visible')) {
      out.push(`${el.tagName.toLowerCase()} ${el.getAttribute('data-slot') || ''} "${el.textContent.slice(0, 40)}"`)
    }
  }
  return out.slice(0, 8)
}

const targetHeight = (loc) => loc.evaluate((el) => {
  const r = el.getBoundingClientRect()
  const af = getComputedStyle(el, '::after')
  if (af.position === 'absolute' && af.content !== 'none') {
    const h = r.height - (parseFloat(af.top) || 0) - (parseFloat(af.bottom) || 0)
    return Math.max(r.height, h)
  }
  return r.height
})

for (const vp of VIEWPORTS) {
  test.describe(`sayfa hızı şelale ve hız notu @${vp.name}`, () => {
    const touch = vp.width < 1024
    test.use({ hasTouch: touch, isMobile: touch })

    test(`not, şelale, görsel aracı, karşılaştırma ve rapor ekrana sığar @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
      test.setTimeout(150_000)
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mockApi(page)
      const wfCalls = []
      await page.route((u) => new URL(u).pathname === '/api/monitoring/pagespeed', json({ success: true, data: [ROW] }))
      await page.route((u) => /^\/api\/monitoring\/pagespeed\/\d+\/resources$/.test(new URL(u).pathname), json(RESOURCES))
      await page.route((u) => /^\/api\/monitoring\/pagespeed\/\d+\/waterfall$/.test(new URL(u).pathname), (r) => {
        const cid = new URL(r.request().url()).searchParams.get('checkId')
        wfCalls.push(cid)
        return json(snapshot(cid ? Number(cid) : 91, cid === '55'))(r)
      })
      await page.goto('/?tab=pagespeed')
      await page.locator('.upt-grid [data-slot="card"] [data-monitor-open]').first().click({ timeout: 20_000 })
      const dialog = page.getByRole('dialog').first()
      await dialog.waitFor()
      const tab = dialog.getByRole('tab', { name: /Şelale ve hız notu|Waterfall & speed grade/ })
      if (touch) await tab.tap(); else await tab.click()

      // Hız notu: puan + harf, bileşenler, öneriler (adresleriyle)
      const grade = dialog.locator('[data-slot="pspd-grade"]')
      await expect(grade).toBeVisible({ timeout: 20_000 })
      await expect(grade).toHaveAttribute('data-letter', /[A-F]/)
      await expect(dialog.locator('[data-slot="pspd-grade-rec"]').first()).toBeVisible()
      await expect(dialog.locator('[data-slot="pspd-grade-rec"][data-code="SLOW_TTFB"]')).toBeVisible()

      // Şelale: HTML + 4 kaynak; uzun adres kesilmez (ayrıntıda tam)
      const rows = dialog.locator('[data-slot="pspd-wf-row"]')
      await expect(rows).toHaveCount(5)
      await expect(dialog.locator('[data-slot="pspd-wf-row"][data-failed="true"]')).toHaveCount(1)
      const imgRow = dialog.locator('[data-slot="pspd-wf-row"][data-type="img"]')
      const toggle = imgRow.locator('[data-slot="pspd-wf-toggle"]')
      await toggle.scrollIntoViewIfNeeded()
      if (touch) await toggle.tap(); else await toggle.click()
      const detail = imgRow.locator('[data-slot="pspd-wf-detail"]')
      await expect(detail).toContainText(IMG_URL)
      const menu = detail.locator('[data-slot="prs-it-menu"]')
      await expect(menu).toBeVisible()
      if (touch) {
        for (const [name, loc] of [['şelale satırı', toggle], ['görsel aracı menüsü', menu],
          ['karşılaştır', dialog.locator('[data-slot="pspd-deep-compare"]')], ['PDF', dialog.locator('[data-slot="pspd-deep-pdf"]')],
          ['Excel', dialog.locator('[data-slot="pspd-deep-xlsx"]')], ['ölçüm seçici', dialog.locator('[data-slot="pspd-deep-snapshot"]')]]) {
          expect(await targetHeight(loc), `@${vp.name}: "${name}" dokunma hedefi`).toBeGreaterThanOrEqual(39.5)
        }
      }
      await page.waitForTimeout(300)
      let m = await page.evaluate(measure, '[role="dialog"]')
      expect(m.offenders, `@${vp.name}: sekmede ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `@${vp.name}: sayfa yatay kayıyor (px)`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="pspd-deep"]'), `@${vp.name}: kesilen metin`).toEqual([])
      expect(await dialog.locator('[data-slot="pspd-deep"]').innerText()).not.toMatch(/pspd\.|undefined|NaN/)

      // Rapor: gerçek indirme (PDF ve Excel)
      for (const [slot, ext] of [['pspd-deep-pdf', '.pdf'], ['pspd-deep-xlsx', '.xlsx']]) {
        const btn = dialog.locator(`[data-slot="${slot}"]`)
        const [download] = await Promise.all([page.waitForEvent('download', { timeout: 30_000 }), touch ? btn.tap() : btn.click()])
        expect(download.suggestedFilename()).toMatch(new RegExp(`sayfa-hizi-\\d{4}-\\d{2}-\\d{2}\\${ext}$`))
        await expect(btn).toBeEnabled()
      }

      // İki ölçümü karşılaştır: B = ihlal anı → yük süresi kötüleşmiş (turuncu), büyüyen görsel listede
      const cmpBtn = dialog.locator('[data-slot="pspd-deep-compare"]')
      await expect(cmpBtn).toBeEnabled()
      if (touch) await cmpBtn.tap(); else await cmpBtn.click()
      const cmp = page.locator('[data-slot="pspd-cmp"]')
      await expect(cmp).toBeVisible()
      await cmp.locator('[data-slot="pspd-cmp-a"]').selectOption('')
      await cmp.locator('[data-slot="pspd-cmp-b"]').selectOption('55')
      await expect(cmp.locator('[data-slot="pspd-cmp-row"][data-key="load"]')).toHaveAttribute('data-worse', 'true')
      await expect(cmp.locator('[data-slot="pspd-cmp-list"][data-kind="grown"]')).toContainText(IMG_URL)
      expect(wfCalls).toContain('55')
      await page.waitForTimeout(300)
      m = await page.evaluate(measure, '[data-slot="pspd-cmp"]')
      expect(m.offenders, `@${vp.name}: karşılaştırmada ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `@${vp.name}: sayfa yatay kayıyor (px)`).toBeLessThanOrEqual(1)
      expect(await cmp.innerText()).not.toMatch(/pspd\.|undefined|NaN/)
    })
  })
}
