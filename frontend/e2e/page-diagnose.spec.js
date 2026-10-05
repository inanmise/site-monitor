// Sayfa Bütünlüğü + Sayfa Hızı UÇTAN UCA TANILAMA penceresi — mobil web kapısı (2026-10-05, kullanıcı isteği: "tanılama ve
// teşhisi eksik olan … izlemeler için tanılama ekleyelim … shadcn tasarım, mweb responsive"). Her tür için derin bağlantıyla
// (`pidx` / `psdx`) KAYITLI bir sonuç telefon (390×844), tablet (768×1024) ve dizüstünde (1280×800) açılır; uzun kaynak
// URL'leri, çok sayıda sorun satırı ve iki yolla zorlanır:
//   - hüküm + türe özgü çözümleme (kaynak çözümlemesi / "Neden yavaş?") görünür;
//   - pencerede ekran dışına taşan öğe yok; pencere görünüm alanına sığar (telefonda tam ekran);
//   - pencere gövdesi ve sayfa YATAY kaymaz; sorun listesi dar kapta KART, genişte TABLO;
//   - dokunmatikte başlık / altlık düğmeleri ve yol sekmeleri ≥ 40 px.
// jsdom yerleşim yapmaz; bu kapı tarayıcıda ölçer (net-diagnose.spec.js ile aynı ölçüm).
import { test, expect } from '@playwright/test'
import { MONITORS, mockApi } from './support/monitorMocks.js'
import { pageBroken, pagePathDiffers, speedSlow } from '../src/test/helpers/pageDiagnoseFixtures.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 800 },
]
const json = (body) => (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
const DIALOG = '[role="dialog"]:has([data-slot="httpdx-body"])'

const LONG_URL = (i) => `https://cok-uzun-bir-icerik-dagitim-agi-adresi-${i}.example.test/assets/2026/10/kampanya/gorseller/ana-sayfa-banner-yuksek-cozunurluk-varyant-${i}.webp?token=*****&v=${'x'.repeat(40)}`

/** Sayfa Bütünlüğü: iki yol (karşılaştırma) + 20 sorun satırı (uzun URL'ler, kaynak sayfası). Monitör kimliği 100. */
function pageData() {
  const d = pagePathDiffers()
  const broken = pageBroken()
  d.monitor = { ...d.monitor, id: 100 }
  // izlemenin yolu sayfayı aldı ama kaynaklar kırık (çözümleme dolu) — öteki yol sağlam
  d.page = { ...broken.page, issues: Array.from({ length: 20 }, (_, i) => ({
    url: i % 3 ? LONG_URL(i) : `https://shop.example.com/img/${i}.png`, resource_type: ['IMG', 'JS', 'CSS', 'LINK'][i % 4],
    kind: ['BROKEN', 'TIMEOUT', 'BLOCKED', 'SLOW', 'MIXED_CONTENT'][i % 5], status: i % 2 ? 404 : null, ms: 100 + i,
    first_party: i % 3 === 0, alarm: i < 4, via: 'proxy',
    source_page: i % 4 === 0 ? `https://shop.example.com/kategori/alt-kategori/urun-listesi-sayfa-${i}` : null })),
    issues_total: 37, monitor_mode: 'SITE_CRAWL', limits: { ...broken.page.limits, time_budget_hit: true } }
  d.findings = [...d.findings, ...broken.findings.map((f) => ({ ...f, params: { ...f.params, sample: LONG_URL(1) } }))]
  return d
}

/** Sayfa Hızı: uzun kaynak URL'leri, iki yol satırı (vekil yolu yavaş). */
function speedData() {
  const d = speedSlow()
  d.monitor = { ...d.monitor, id: 100 }
  d.proxy = { configured: true, host: 'kurumsal-vekil-sunucusu.example.test', port: 8080, auth: false, no_proxy: '' }
  d.comparison = { available: true, differs: false }
  d.paths = [d.paths[0], { ...d.paths[0], key: 'alternate', route: 'proxy', decision: { source: 'compare', wanted: true, bypassed: false } }]
  d.pagespeed = {
    ...d.pagespeed,
    routes: [d.pagespeed.routes[0], { ...d.pagespeed.routes[0], key: 'alternate', route: 'proxy', proxy_ms: 2400, connect_ms: 640 }],
    heaviest: Array.from({ length: 5 }, (_, i) => ({ url: LONG_URL(i), type: 'IMG', bytes: 3_000_000 - i * 100_000, ms: 4000 - i * 100,
      status: 200, third_party: i % 2 === 0, failed: false, truncated: i === 0 })),
    slowest: Array.from({ length: 5 }, (_, i) => ({ url: LONG_URL(i + 10), type: 'JS', bytes: 40_000, ms: 6000 - i * 300, status: i ? 200 : 404,
      third_party: true, failed: i === 0, truncated: false })),
  }
  return d
}

const CASES = [
  { type: 'page', param: 'pidx', runId: 601, data: pageData, analysis: 'pgdx-analysis', open: 'pgdx-open' },
  { type: 'pagespeed', param: 'psdx', runId: 701, data: speedData, analysis: 'psdx-analysis', open: 'psdx-open' },
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
  test.describe(`uçtan uca tanılama (sayfa bütünlüğü / sayfa hızı) @${vp.name}`, () => {
    test.use({ hasTouch: vp.width < 1024 })

    for (const c of CASES) {
      test(`${c.type}: kayıtlı sonuç ekrana sığar, yatay kayma yok @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
        const touch = vp.width < 1024
        await page.setViewportSize({ width: vp.width, height: vp.height })
        await mockApi(page, { monitors: { ...MONITORS, [c.type]: MONITORS[c.type].map((m) => ({ ...m, can_diagnose: true })) } })
        const data = c.data()
        await page.route((u) => new RegExp(`^/api/monitoring/${c.type}/\\d+/diagnose/history/\\d+$`).test(new URL(u).pathname),
          json({ success: true, data }))
        await page.route((u) => new RegExp(`^/api/monitoring/${c.type}/\\d+/diagnose/history$`).test(new URL(u).pathname),
          json({ success: true, data: [{ id: c.runId, started_at: data.started_at, executed_by: 'operator1', verdict_code: data.verdict.code,
            verdict_status: data.verdict.status, monitor_route: data.paths[0].route, monitor_http_status: data.paths[0].http_status,
            alternate_route: data.paths[1]?.route ?? null, alternate_http_status: data.paths[1]?.http_status ?? null,
            findings: data.findings.length, duration_ms: data.duration_ms }] }))
        await page.goto(`/?tab=${c.type}&monitor=100&${c.param}=${c.runId}`)
        const dlg = page.locator(DIALOG)
        await dlg.locator('[data-slot="httpdx-result"][data-stored="true"]').waitFor({ timeout: 20_000 })

        // hüküm + türe özgü çözümleme görünür
        await expect(dlg.locator('[data-slot="httpdx-verdict"]')).toBeVisible()
        await expect(dlg.locator('[data-slot="httpdx-verdict"]')).toHaveAttribute('data-code', data.verdict.code)
        await expect(dlg.locator(`[data-slot="${c.analysis}"]`)).toBeVisible()

        const check = async (stage) => {
          await page.waitForTimeout(400)
          const m = await page.evaluate(measure, DIALOG)
          expect(m.offenders, `${c.type} ${stage} @${vp.name}: pencerede ekran dışına taşan öğe`).toEqual([])
          expect(m.pageOverflow, `${c.type} ${stage} @${vp.name}: sayfa yatay kayıyor (px)`).toBeLessThanOrEqual(1)
          const box = await dlg.boundingBox()
          expect(box.x, `${c.type} ${stage} @${vp.name}: pencere solda taşıyor`).toBeGreaterThanOrEqual(-1)
          expect(box.x + box.width, `${c.type} ${stage} @${vp.name}: pencere sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
          expect(box.y + box.height, `${c.type} ${stage} @${vp.name}: pencere altta taşıyor`).toBeLessThanOrEqual(vp.height + 1)
          const hScroll = await dlg.locator('[data-slot="modal-shell-body"]').first().evaluate((el) => el.scrollWidth - el.clientWidth)
          expect(hScroll, `${c.type} ${stage} @${vp.name}: pencere gövdesi yatay kayıyor (px)`).toBeLessThanOrEqual(1)
        }
        await check('sonuç')
        if (vp.name === 'phone') {
          const box = await dlg.boundingBox()
          expect(Math.round(box.width), `${c.type}: telefonda tam ekran`).toBe(vp.width)
        }

        if (c.type === 'page') {
          const issues = dlg.locator('[data-slot="pgdx-issues"]')
          await issues.scrollIntoViewIfNeeded()
          const view = await issues.getAttribute('data-view')
          if (vp.name === 'phone') expect(view, 'sorunlar telefonda kart').toBe('cards')
          if (vp.name === 'desktop') expect(view, 'sorunlar geniş ekranda tablo').toBe('table')
          await expect(issues.locator('[data-slot="pgdx-issue"]')).toHaveCount(20)
          await check('sorun listesi')
        } else {
          await dlg.locator('[data-slot="psdx-metrics"]').scrollIntoViewIfNeeded()
          await expect(dlg.locator('[data-slot="psdx-metric"]')).toHaveCount(4)
          await expect(dlg.locator('[data-slot="psdx-route"]')).toHaveCount(2)
          await dlg.locator('[data-slot="psdx-slowest"]').scrollIntoViewIfNeeded()
          await check('ölçüm çözümlemesi')
        }

        // öteki yolun ayrıntısı (sekme) — dokunmatikte ≥ 40 px, açılınca yeniden ölç
        const tab = dlg.getByRole('tab', { name: /Öteki yol|Other route/ })
        await tab.scrollIntoViewIfNeeded()
        if (touch) {
          const b = await tab.boundingBox()
          expect(b.height, `${c.type} yol sekmesi yüksekliği @${vp.name}`).toBeGreaterThanOrEqual(39)
          await tab.tap()
        } else await tab.click()
        await check('öteki yol')

        if (touch) {
          // dokunma hedefleri ≥ 40 px: başlık eylemleri, altlık düğmesi
          const targets = [...(await dlg.locator('[data-slot="httpdx-actions"] button').all()), dlg.locator('[data-slot="httpdx-run"]')]
          for (const loc of targets) {
            await loc.scrollIntoViewIfNeeded()
            const b = await loc.boundingBox()
            expect(b.height, `${c.type} dokunma hedefi yüksekliği @${vp.name}`).toBeGreaterThanOrEqual(39)
            expect(b.width, `${c.type} dokunma hedefi genişliği @${vp.name}`).toBeGreaterThanOrEqual(39)
          }
        }
        for (const sel of ['[data-slot="httpdx-actions"]', '[data-slot="httpdx-run"]']) {
          const b = await dlg.locator(sel).boundingBox()
          expect(b.x + b.width, `${sel} @${vp.name}`).toBeLessThanOrEqual(vp.width + 1)
        }

        // başlangıç ekranı: pencereyi kapat, başlıktan yeniden aç (koşu YOK) → türe özgü satır görünür, taşma yok
        await dlg.getByRole('button', { name: /^(Kapat|Close)$/ }).last().click()
        await expect(dlg).toHaveCount(0)
        const openBtn = page.locator(`[data-slot="${c.open}"]`)
        if (touch) {
          const b = await openBtn.boundingBox()
          expect(b.height, `${c.type} "Uçtan uca tanıla" düğmesi @${vp.name}`).toBeGreaterThanOrEqual(39)
        }
        await openBtn.click()
        await dlg.locator('[data-slot="httpdx-start"]').waitFor()
        await expect(dlg.locator(c.type === 'page' ? '[data-slot="pgdx-start-checked"]' : '[data-slot="psdx-start-thresholds"]')).toBeVisible()
        await check('başlangıç')
      })
    }
  })
}
