// Sayfa Bütünlüğü — kontrol geçmişi "Bozulmuş" satırının SORUNLU KAYNAK ayrıntısı (E1, 2026-11-12, kullanıcı isteği:
// "1 kırık kaynak diyor ama hangi kaynak / nasıl bir hata / hangi sayfada belirtmiyor"). Satır açılınca o kontrolün
// sorunlu kaynakları (URL, HTTP durumu, bulunduğu sayfa, neden/ne yapmalı) listelenir. Telefon/tablet/dizüstünde
// ekrana sığar, yatay kayma yok; shadcn + mweb.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 800 },
]
const json = (body) => (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
const CHECKED = new Date(Date.now() - 65 * 60_000).toISOString().slice(0, 19)
const LONG_URL = 'https://cok-uzun-bir-alt-alan-adi.odeme-servisleri.example.com/statik/paketler/vendor.app.min.js?v=20261112'

const HISTORY = { success: true, data: { items: [
  { id: 9001, monitor_id: 1, status: 'DEGRADED', ok: false, http_status: 200, response_ms: 20835,
    broken_resources: 1, timeout_count: 4, mixed_content_count: 0, total_resources: 45, pages_crawled: 3,
    checked_at: CHECKED, failure_reason: 'RESOURCES_BROKEN',
    failure_detail: JSON.stringify({ phase: 'CONTENT', broken: 1, timeouts: 4, mixed: 0, total_resources: 45 }) },
], counts: { total: 1, fail: 1, errors: 0 }, buckets: [], alerts: [], total: 1, page: 0, size: 50 } }

const ISSUES = { success: true, data: [
  { id: 1, monitor_id: 1, resource_url: LONG_URL, resource_type: 'JS', source_page: 'https://www.juzdan.com.tr/urunler',
    issue_type: 'BROKEN', first_party: true, http_status: 404, duration_ms: 12, checked_at: CHECKED },
  { id: 2, monitor_id: 1, resource_url: 'https://cdn.example.com/a.png', resource_type: 'IMG',
    source_page: 'https://www.juzdan.com.tr/urunler', issue_type: 'TIMEOUT', first_party: false, http_status: null,
    duration_ms: 4000, checked_at: CHECKED },
] }

// Pencere içinde ekran dışına taşan öğe + yatay kayma ölçümü (check-failure-history.spec ile AYNI: kırpan/kayan
// kapsayıcı içindeki öğe — ör. tarih-aralığı ToggleGroup'u — sayılmaz, içinde kayar).
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
 test.describe(`sayfa bütünlüğü ayrıntısı @${vp.name}`, () => {
  test.use({ hasTouch: vp.width < 1024 })
  test(`"Bozulmuş" satırı sorunlu kaynakları gösterir, ekrana sığar @${vp.name} ${vp.width}×${vp.height}`,
    async ({ page }) => {
      const touch = vp.width < 1024
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mockApi(page)
      await page.route((u) => /^\/api\/monitoring\/page\/\d+\/history$/.test(new URL(u).pathname), json(HISTORY))
      await page.route((u) => /^\/api\/monitoring\/page\/\d+\/issues$/.test(new URL(u).pathname), json(ISSUES))
      await page.goto('/?tab=page')
      await page.locator('.upt-grid [data-slot="card"] [data-monitor-open]').first().click({ timeout: 20_000 })
      const dialog = page.getByRole('dialog').first()
      await dialog.waitFor()

      // Kontrol Geçmişi sekmesi → "Bozulmuş" satırının "Ayrıntıyı göster"i
      const tab = dialog.getByRole('tab', { name: /kontrol geçmişi|check history/i })
      if (touch) await tab.tap(); else await tab.click()
      const toggle = dialog.locator('[data-slot="chkfail-toggle"]').first()
      await toggle.waitFor({ timeout: 20_000 })
      await toggle.scrollIntoViewIfNeeded()
      if (touch) {
        const b = await toggle.boundingBox()
        expect(b.height, `aç/kapa dokunma hedefi @${vp.name}`).toBeGreaterThanOrEqual(39)
        await toggle.tap()
      } else {
        await toggle.click()
      }

      // E1: sorunlu kaynak listesi
      const issues = dialog.locator('[data-slot="page-hist-issues"]')
      await expect(issues).toBeVisible()
      const rows = dialog.locator('[data-slot="page-issue-row"]')
      await expect(rows).toHaveCount(2)
      await expect(issues).toContainText('HTTP 404')
      await expect(issues).toContainText('/urunler')     // bulunduğu sayfa
      await expect(rows.first()).toContainText(LONG_URL)
      // E4: neden metni (BROKEN_404 → "bulunamadı / not found")
      await expect(rows.first()).toContainText(/bulunamad|not found/i)

      await page.waitForTimeout(300)
      const m = await page.evaluate(measure, '[role="dialog"]')
      expect(m.offenders, `@${vp.name}: pencerede ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `@${vp.name}: sayfa yatay kayıyor (px)`).toBeLessThanOrEqual(1)
      const ib = await issues.boundingBox()
      expect(ib.x + ib.width, `@${vp.name}: sorunlu kaynak listesi sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    })
 })
}
