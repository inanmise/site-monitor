// Kontrol geçmişi HATA TEŞHİSİ — mobil web kapısı (2026-10-05, kullanıcı isteği: "hata alındığında detaylıca ne hatası
// aldığını görelim … shadcn tasarım, mweb responsive"). DNS, Port ve Sayfa Bütünlüğü detay penceresinde başarısız satırın
// "Ayrıntıyı göster"i açılır; Neden / Etkisi / Ne yapmalı + kayıttaki ayrıntılar (uzun host, çok IP, uzun vekil iletisiyle
// zorlanır) + ham hata paneli telefon (390×844), tablet (768×1024) ve dizüstünde (1280×800) ölçülür:
//   - panel görünür; pencerede ekran dışına taşan öğe yok; pencere görünüm alanına sığar;
//   - pencere gövdesi ve sayfa YATAY kaymaz;
//   - dokunmatikte aç/kapa düğmesi ≥ 40 px.
// jsdom yerleşim yapmaz; bu kapı tarayıcıda ölçer (responsive.spec.js ile aynı ölçüm).
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 800 },
]

const HOUR = 3_600_000
const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const json = (body) => (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
const LONG_HOST = 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com'
const IPS = ['2001:db8:85a3:0000:0000:8a2e:0370:7334', '2001:db8:85a3:0000:0000:8a2e:0370:7335', '192.0.2.10', '192.0.2.11',
  '198.51.100.20', '198.51.100.21', '203.0.113.30', '203.0.113.31']
const CHAIN = ['IOException: vekil tüneli reddetti: HTTP/1.1 403 Forbidden (kurumsal vekil politika kuralı: hedef port listede yok)',
  'SocketException: Connection reset by peer while reading the tunnel response from the corporate proxy']

const env = (items, extra = {}) => ({ success: true, data: {
  items, counts: { total: items.length, fail: 1, errors: items.filter((r) => r.value === '').length, ...extra },
  buckets: [], alerts: [], range: { from: iso(24 * HOUR), to: iso(0) }, total: items.length, page: 0, size: 50,
} })

const DNS_ROWS = [
  { id: 2, monitor_id: 1, record_type: 'A', value: '', changed: false, rotated: false, previous_value: null, ttl: null,
    response_ms: 2004, checked_at: iso(5 * 60_000),
    error: `SERVFAIL — ${LONG_HOST} yetkili ad sunucusundan yanıt alınamadı (DNSSEC doğrulaması başarısız)`,
    failure_reason: 'DNS_SERVFAIL',
    failure_detail: JSON.stringify({ phase: 'DNS', rcode: 'SERVFAIL', record_type: 'A', target: LONG_HOST, response_ms: 2004,
      timeout_ms: 2000, other_answers: 2 }) },
  { id: 1, monitor_id: 1, record_type: 'A', value: '203.0.113.10', changed: false, rotated: false, previous_value: null, ttl: 300,
    response_ms: 11, checked_at: iso(10 * 60_000) },
]

const PORT_ROWS = [
  { id: 2, monitor_id: 1, open: false, response_ms: null, checked_at: iso(5 * 60_000),
    error: 'vekil tüneli reddetti: HTTP/1.1 403 Forbidden — vekil bu porta tünel açmıyor olabilir (izinli: 443, 8443, 9443, 10443, 18443)',
    failure_reason: 'PROXY_REFUSED',
    failure_detail: JSON.stringify({ phase: 'CONNECT', via: 'proxy', proxy_status: 403, proxy_refused: true,
      allowed_ports: [443, 8443, 9443, 10443, 18443], target: `${LONG_HOST}:5432`, protocol: 'TCP', timeout_ms: 5000,
      resolved_ips: IPS, exception: 'IOException', message: CHAIN[0], cause_chain: CHAIN }) },
  { id: 1, monitor_id: 1, open: true, response_ms: 14, checked_at: iso(10 * 60_000) },
]

const PAGE_ROWS = [
  { id: 3, monitor_id: 1, ok: false, status: 'DOWN', http_status: 503, response_ms: 412, total_resources: 0, broken_resources: 0,
    timeout_count: 0, mixed_content_count: 0, checked_at: iso(5 * 60_000),
    error: `ana sayfa HTTP 503 — ${LONG_HOST}/giris/kullanici/oturum-ac?lang=tr&ref=cok-uzun-bir-deger-ile-gelen-baglanti`,
    failure_reason: 'HTTP_STATUS',
    failure_detail: JSON.stringify({ phase: 'RESPONSE', http_status: 503, target: LONG_HOST, via: 'direct', timeout_ms: 4000 }) },
  { id: 2, monitor_id: 1, ok: false, status: 'DEGRADED', http_status: 200, total_resources: 132, broken_resources: 7,
    timeout_count: 2, mixed_content_count: 1, checked_at: iso(65 * 60_000), failure_reason: 'RESOURCES_BROKEN',
    failure_detail: JSON.stringify({ phase: 'CONTENT', broken: 7, timeouts: 2, mixed: 1, total_resources: 132 }) },
  { id: 1, monitor_id: 1, ok: true, status: 'OK', http_status: 200, total_resources: 132, broken_resources: 0, timeout_count: 0,
    mixed_content_count: 0, checked_at: iso(125 * 60_000) },
]

const DNS_DETAILS = { success: true, data: {
  records: { A: { values: ['203.0.113.10'], ttl: 300, response_ms: 11, success: true } },
  soa: { success: false }, authoritative_servers: [], slow_threshold_ms: 1500,
  resolver_config: { servers: ['10.0.0.53'], source: 'os', timeout_ms: 2000, propagation_enabled: false, propagation_resolvers: [] },
} }

const CASES = [
  { tab: 'dns', history: /^\/api\/monitoring\/dns\/\d+\/history$/, rows: DNS_ROWS, code: 'DNS_SERVFAIL', historyTab: null },
  { tab: 'port', history: /^\/api\/monitoring\/port\/\d+\/history$/, rows: PORT_ROWS, code: 'PROXY_REFUSED', historyTab: null },
  { tab: 'page', history: /^\/api\/monitoring\/page\/\d+\/history$/, rows: PAGE_ROWS, code: 'HTTP_STATUS', historyTab: /check history|kontrol geçmişi/i },
]

/** Tarayıcıda: `sel` kökü içinde görünüm alanının sağına taşan en dıştaki görünür öğeler (kendi kaydırma kabı dışında). */
function measure(sel) {
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
    offenders.push({ el: `${el.tagName.toLowerCase()}${slot ? `[data-slot=${slot}]` : ''}`, right: Math.round(r.right),
      text: (el.textContent || '').trim().slice(0, 40) })
    if (offenders.length >= 8) break
  }
  return { vw, pageOverflow, offenders }
}

for (const vp of VIEWPORTS) {
  test.describe(`kontrol geçmişi hata teşhisi @${vp.name}`, () => {
    test.use({ hasTouch: vp.width < 1024 })

    for (const c of CASES) {
      test(`${c.tab}: başarısız satır paneli ekrana sığar, yatay kayma yok @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
        const touch = vp.width < 1024
        await page.setViewportSize({ width: vp.width, height: vp.height })
        await mockApi(page)
        await page.route((u) => c.history.test(new URL(u).pathname), json(env(c.rows)))
        if (c.tab === 'dns') await page.route((u) => /^\/api\/monitoring\/dns\/\d+\/details$/.test(new URL(u).pathname), json(DNS_DETAILS))
        await page.goto(`/?tab=${c.tab}`)
        await page.locator('.upt-grid [data-slot="card"] [data-monitor-open]').first().click({ timeout: 20_000 })
        const dialog = page.getByRole('dialog').first()
        await dialog.waitFor()
        if (c.historyTab) {
          const tab = dialog.getByRole('tab', { name: c.historyTab })
          if (touch) await tab.tap()
          else await tab.click()
        }
        const cell = dialog.locator(`[data-slot="chkfail-cell"][data-code="${c.code}"]`).first()
        await cell.waitFor({ timeout: 20_000 })

        const toggle = cell.locator('[data-slot="chkfail-toggle"]')
        await toggle.scrollIntoViewIfNeeded()
        if (touch) {
          const b = await toggle.boundingBox()
          expect(b.height, `${c.tab} aç/kapa dokunma hedefi @${vp.name} (px)`).toBeGreaterThanOrEqual(39)
          await toggle.tap()
        } else {
          await toggle.click()
        }
        const panel = dialog.locator(`[data-slot="chkfail-panel"][data-code="${c.code}"]`)
        await expect(panel).toBeVisible()
        await expect(panel.locator('[data-slot="chkfail-why"]')).toBeVisible()
        await expect(panel.locator('[data-slot="chkfail-details"]')).toBeVisible()
        await panel.scrollIntoViewIfNeeded()
        await page.waitForTimeout(400)

        const m = await page.evaluate(measure, '[role="dialog"]')
        expect(m.offenders, `${c.tab} @${vp.name}: pencerede ekran dışına taşan öğe`).toEqual([])
        expect(m.pageOverflow, `${c.tab} @${vp.name}: sayfa yatay kayıyor (px)`).toBeLessThanOrEqual(1)
        const box = await dialog.boundingBox()
        expect(box.x, `${c.tab} @${vp.name}: pencere solda taşıyor`).toBeGreaterThanOrEqual(-1)
        expect(box.x + box.width, `${c.tab} @${vp.name}: pencere sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
        const hScroll = await dialog.locator('[data-slot="modal-shell-body"]').first().evaluate((el) => el.scrollWidth - el.clientWidth)
        expect(hScroll, `${c.tab} @${vp.name}: pencere gövdesi yatay kayıyor (px)`).toBeLessThanOrEqual(1)
        const pb = await panel.boundingBox()
        expect(pb.x + pb.width, `${c.tab} @${vp.name}: panel sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
      })
    }
  })
}
