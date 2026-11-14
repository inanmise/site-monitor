// Kaynak analizi prs3 (fe2, 2026-11-04 kullanıcı kararı: "shadcn, en üst düzey kullanıcı deneyimi, mobil 390 / 768 ve
// masaüstü 1280, e2e testli"): performans bütçesi (bant + pencere: alan altı hata, yalnız değişen alan), çok sayfalı (site)
// analiz (bölünmüş başlat düğmesi → pencere → gövde, koşu kartı işareti, eski sunucu notu, Sayfalar görünümü: tablo ↔
// kart), eğilim görünümü (4 grafik, aralık), geçmiş / karşılaştırma işaretleri, yönetici PDF'i ve Excel (indirme GERÇEKTEN
// başlar, içerik denetlenir). Her aşamada: ekran dışına taşan öğe yok, pencere gövdesi yatay kaymaz, metin kırpılmaz,
// dokunmatikte hedefler ≥ 40 px. API tamamen taklit (G'nin arka ucu henüz yok — sözleşmedeki tel biçimi).
import { readFileSync } from 'node:fs'
import { unzipSync, strFromU8 } from 'fflate'
import { test, expect } from '@playwright/test'
import { mockApi, MONITORS } from './support/monitorMocks.js'
import { scanDetail, scanDetailV2, scanSummary } from '../src/test/helpers/resourceScanFixtures.js'
import { budgetData, deepDetail, fgHistory, siteDetail, trendData } from '../src/test/helpers/resourceScanFgFixtures.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]
const DIALOG = '[role="dialog"]:has([data-slot="prs-root"])'
const ROOT = '[data-slot="prs-root"]'
const json = (body, status = 200) => (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
const PATH = (re) => (u) => re.test(new URL(u).pathname)

function detailOf(id) {
  if (id === 30 || id === 51) return siteDetail(id)
  if (id === 12) return deepDetail(12, { budget: { defined: true, exceeded: 0, checks: [{ key: 'max_requests', limit: 80, actual: 54, ok: true }] } })
  if (id === 10) return { ...scanDetail(10, { scan: { grade: 64, grade_letter: 'D' } }), items: null, items_kept: false }
  if (id === 11) return { scan: fgHistory().items.find((s) => s.id === 11), analysis: null, items: null, items_kept: false }
  return scanDetailV2(id)
}

/** Sahte sunucu: geçmiş, ayrıntı, bütçe (GET / PUT), eğilim; başlatma (site → RUNNING, sonra DONE). `oldServer`: scope yok. */
async function routes(page, { oldServer = false } = {}) {
  // İzleme satırları liste sözleşmesiyle düzenleme bayrağı taşır (bütçe düğmesinin adı)
  await mockApi(page, { monitors: { ...MONITORS, page: MONITORS.page.map((m) => ({ ...m, can_edit: true })) } })
  const state = { started: null, polls: 0, posts: [], puts: [], trend: [] }
  const running = (id, scope) => scanSummary(id, { status: 'RUNNING', grade: null, grade_letter: null, load_ms: null, finished_at: null,
    started_at: new Date(Date.now() - 3000).toISOString().slice(0, 19), progress: { done: 9, total: 40, ...(scope ? { pages_done: 2, pages_total: 7 } : {}) },
    ...(scope ? { scope: 'site', pages_count: null } : {}) })
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-scans$/), (route) => {
    if (route.request().method() === 'POST') {
      let body = null
      try { body = JSON.parse(route.request().postData() || 'null') } catch { body = null }
      state.posts.push(body)
      const site = body?.scope === 'site' && !oldServer
      state.started = running(51, site)
      return json({ success: true, data: state.started }, 202)(route)
    }
    const h = fgHistory()
    if (state.started && state.polls < 2) return json({ success: true, data: { ...h, items: [state.started, ...h.items], running: state.started } })(route)
    if (state.started) return json({ success: true, data: { ...h, items: [{ ...siteDetail(51).scan, started_at: state.started.started_at }, ...h.items] } })(route)
    return json({ success: true, data: h })(route)
  })
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-scans\/trend$/), (route) => {
    state.trend.push(new URL(route.request().url()).searchParams.get('days'))
    return json({ success: true, data: trendData() })(route)
  })
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-scans\/\d+$/), (route) => {
    const id = Number(new URL(route.request().url()).pathname.split('/').pop())
    if (id === 51 && state.started) {
      state.polls += 1
      if (state.polls < 2) return json({ success: true, data: { scan: state.started, analysis: null, items: null, items_kept: true } })(route)
    }
    return json({ success: true, data: detailOf(id) })(route)
  })
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-budget$/), (route) => {
    if (route.request().method() === 'PUT') {
      const body = JSON.parse(route.request().postData() || '{}')
      state.puts.push(body)
      return json({ success: true, data: budgetData({ max_page_kb: 2048, max_requests: 80, min_grade: 80, min_security_score: 70, ...body }) })(route)
    }
    return json({ success: true, data: budgetData({ max_page_kb: 2048, max_requests: 80, min_grade: 80, min_security_score: 70 }) })(route)
  })
  return state
}

/** Görünüm alanı dışına taşan öğeler + sayfa düzeyi yatay taşma. */
function measure(sel) {
  const vw = document.documentElement.clientWidth
  const root = (sel && document.querySelector(sel)) || document.body
  const pageOverflow = Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth,
    document.body.scrollWidth - document.body.clientWidth)
  const clips = (el) => getComputedStyle(el).overflowX !== 'visible'
  const out = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    const cs = getComputedStyle(el)
    if (r.width <= 1 || r.height <= 1 || cs.visibility === 'hidden' || cs.display === 'none') continue
    if (r.right <= vw + 1 && r.left >= -1) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (clips(p) && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (!contained) out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || ''}] ${Math.round(r.left)}..${Math.round(r.right)}`)
    if (out.length >= 8) break
  }
  return { pageOverflow, offenders: out }
}

function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('span, p, li, h3, h4, h5, label, button, dt, dd, code, td, th, a, legend')]
    .filter((n) => !n.closest('.sr-only') && !n.closest('select') && !n.closest('svg') && !n.closest('.recharts-wrapper'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 12
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')).map((n) => n.textContent.slice(0, 40))
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, select, input:not([type="hidden"]), [role="combobox"], [role="menuitem"]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled) continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none' || b.getAttribute('aria-hidden') === 'true') continue
    if (b.closest('.recharts-wrapper')) continue
    if (r.height < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(r.height)}`)
  }
  return out
}

for (const vp of VIEWPORTS) {
  test.describe(`Kaynak analizi prs3 (bütçe, site, eğilim, PDF) — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    /** Ölçüm: `sel` içinde taşma / kaydırma, `rootSel` içinde kırpma ve dokunma hedefi. */
    const checker = (page, sel = DIALOG, rootSel = ROOT, bodySel = `${DIALOG} [data-slot="modal-shell-body"]`) => async (stage) => {
      await page.waitForTimeout(300)
      const m = await page.evaluate(measure, sel)
      expect(m.offenders, `${stage} @${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `${stage} @${vp.name}: sayfa yatay kayıyor`).toBeLessThanOrEqual(1)
      const body = page.locator(bodySel).first()
      if (await body.count()) {
        const hScroll = await body.evaluate((el) => el.scrollWidth - el.clientWidth)
        expect(hScroll, `${stage} @${vp.name}: pencere gövdesi yatay kayıyor`).toBeLessThanOrEqual(1)
      }
      expect(await page.evaluate(clipped, rootSel), `${stage} @${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, rootSel), `${stage} @${vp.name}: küçük dokunma hedefi`).toEqual([])
    }

    test('bütçe bandı + pencere, Sayfalar, Eğilim, geçmiş ve karşılaştırma işaretleri; sığar, kırpılmaz', async ({ page }) => {
      test.setTimeout(150_000)
      const state = await routes(page)
      await page.goto('/?tab=page&monitor=100&mtab=resources')
      const dlg = page.locator(DIALOG)
      const root = dlg.locator(ROOT)
      await expect(root.locator('[data-slot="prs-hero"]')).toBeVisible({ timeout: 20_000 })
      const check = checker(page)

      // 1) Bütçe bandı: aşılan / geçen / ölçülemeyen kalemler
      const band = root.locator('[data-slot="prs-budget-band"]')
      await expect(band).toHaveAttribute('data-state', 'exceeded')
      await expect(band.locator('[data-slot="prs-budget-row"]')).toHaveCount(4)
      await expect(band.locator('[data-slot="prs-budget-row"][data-ok="unknown"]')).toHaveCount(1)
      // Bölüm sekmeleri (7 sekme: Denetim, Eğilim, Sayfalar, Görsel aracı dahil) kırpılmaz: lg+ tek satır; telefon ve
      // tablette 4 + 3 iki satır (5+ eşit sütunda adlar 360 px'te sığmıyordu — sectionTabCols; 768'de satır içi düzen Linux
      // yazı tipiyle kırılıyordu)
      const tabs = root.locator('[data-slot="prs-sections"] [role="tab"]')
      await expect(tabs).toHaveCount(7)
      const tops = await tabs.evaluateAll((els) => els.map((el) => Math.round(el.getBoundingClientRect().top)))
      const rows = new Set(tops).size
      if (vp.width >= 1024) {
        expect(Math.max(...tops) - Math.min(...tops), `@${vp.name}: bölüm sekmeleri tek satır değil`).toBeLessThanOrEqual(1)
      } else {
        expect(rows, `@${vp.name}: telefon / tablette bölüm sekmeleri iki satır olmalı`).toBe(2)
        expect(tops.filter((v) => v === Math.min(...tops)), `@${vp.name}: ilk satırda 4 sekme`).toHaveLength(4)
      }
      // Başlık eylemleri (başlat grubu, bağlantı, Excel, PDF) sm+ genişlikte tek satır
      if (vp.width >= 640) {
        const actTops = await root.locator('[data-slot="prs-actions"] > *').evaluateAll((els) => els.map((el) => Math.round(el.getBoundingClientRect().top)))
        expect(actTops.length).toBe(4)
        expect(Math.max(...actTops) - Math.min(...actTops), `@${vp.name}: başlık eylemleri tek satır değil`).toBeLessThanOrEqual(1)
      }
      await check('bütçe bandı')

      // 2) Bütçe penceresi: aralık dışı değer alanın altında hata; düzeltince YALNIZ değişen alan gider
      await band.locator('[data-slot="prs-budget-open"]').click()
      const bd = page.locator('[role="dialog"]:has([data-slot="prs-budget-dialog"])')
      await expect(bd.locator('[data-slot="prs-budget-dialog"]')).toHaveAttribute('data-state', 'ok')
      const pageKb = bd.locator('input[data-budget-key="max_page_kb"]')
      await expect(pageKb).toHaveValue('2048')
      await pageKb.fill('10')
      await bd.locator('[data-slot="prs-budget-save"]').click()
      await expect(pageKb).toHaveAttribute('aria-invalid', 'true')
      await expect(bd.locator('[data-field="max_page_kb"] [data-slot="field-error"]')).toBeVisible()
      expect(state.puts).toHaveLength(0)
      const bdSel = '[role="dialog"]:has([data-slot="prs-budget-dialog"])'
      await checker(page, bdSel, bdSel, `${bdSel} [data-slot="modal-shell-body"]`)('bütçe penceresi (hata)')
      await pageKb.fill('3000')
      await bd.locator('[data-slot="prs-budget-save"]').click()
      await expect(bd).toHaveCount(0)
      expect(state.puts).toEqual([{ max_page_kb: 3000 }])

      // 3) Sayfalar (site analizi): kapta tablo ↔ kart; açılamayan sayfa nedeniyle; ortak kaynaklar
      await root.locator('[data-slot="prs-sections"]').getByRole('tab', { name: /^(Sayfalar|Pages)/ }).click()
      const site = root.locator('[data-slot="prs-site"]')
      await expect(site).toBeVisible()
      const layout = await site.locator('[data-slot="prs-site-list"]').getAttribute('data-layout')
      if (vp.width === 390) expect(layout).toBe('cards')
      if (vp.width === 1280) expect(layout).toBe('table')
      await expect(site.locator('[data-slot="prs-site-page"]')).toHaveCount(5)
      await expect(site.locator('[data-slot="prs-site-error"]')).toContainText('503')
      await expect(site.locator('[data-slot="prs-site-shared-row"]')).toHaveCount(3)
      await expect(page).toHaveURL(/prs_v=pages/)
      await check('sayfalar')

      // 4) Eğilim: 4 grafik, algoritma notu, büyüyen nesneler; aralık değişimi yeni istek
      await root.locator('[data-slot="prs-sections"]').getByRole('tab', { name: /^(Eğilim|Trend)/ }).click()
      await expect(root.locator('[data-slot="prs-tv-chart"]')).toHaveCount(4)
      await expect(root.locator('[data-slot="prs-tv-chart"][data-chart="bytes"] svg').first()).toBeVisible()
      await expect(root.locator('[data-slot="prs-tv-algo-note"]')).toBeVisible()
      await expect(root.locator('[data-slot="prs-tv-growth-row"]')).toHaveCount(2)
      // geliştirme sunucusu StrictMode'da efekti iki kez koşar — yalnız 90 gün istenmiş olmalı
      expect([...new Set(state.trend)]).toEqual(['90'])
      await root.locator('[data-slot="prs-tv-days"]').getByRole('button', { name: /^(30 gün|30 days)$/ }).click()
      await expect.poll(() => state.trend.at(-1)).toBe('30')
      await expect(root.locator('[data-slot="prs-tv-chart"]')).toHaveCount(4)
      await check('eğilim')

      // 5) Geçmiş: site ve bütçe işaretleri; karşılaştırma işaretleri
      await root.locator('[data-slot="prs-sections"]').getByRole('tab', { name: /^(Geçmiş|History)/ }).click()
      const r30 = root.locator('[data-slot="prs-history-row"][data-id="30"]')
      await expect(r30.locator('[data-slot="prs-history-scope"]')).toBeVisible()
      await expect(r30.locator('[data-slot="prs-budget-status"]')).toHaveAttribute('data-status', 'exceeded')
      await expect(root.locator('[data-slot="prs-history-row"][data-id="10"] [data-slot="prs-budget-status"]')).toHaveAttribute('data-status', 'unknown')
      await check('geçmiş')
      await root.locator('[data-slot="prs-history-mode"]').getByRole('button', { name: /Karşılaştır|Compare/ }).click()
      await expect(root.locator('[data-slot="prs-cmp-markers"]')).toBeVisible()
      await expect(root.locator('[data-slot="prs-cmp-markers"] [data-slot="prs-budget-status"]')).toHaveCount(2)
      await check('karşılaştırma')
      await page.screenshot({ path: `test-results/page-resource-scan-fg-${vp.name}.png`, fullPage: false })
    })

    test('görsel kayıt (ölçüm yok — ekran görüntüsü dokunmatik öykünmeyi sıfırlar): bant, pencere, sayfalar, eğilim', async ({ page }) => {
      test.setTimeout(120_000)
      await routes(page)
      await page.goto('/?tab=page&monitor=100&mtab=resources')
      const root = page.locator(DIALOG).locator(ROOT)
      await expect(root.locator('[data-slot="prs-hero"]')).toBeVisible({ timeout: 20_000 })
      await root.locator('[data-slot="prs-budget-band"]').screenshot({ path: `test-results/prs-fg-budget-band-${vp.name}.png` })
      await root.locator('[data-slot="prs-budget-open"]').click()
      await expect(page.locator('[data-slot="prs-budget-dialog"]')).toHaveAttribute('data-state', 'ok')
      await page.screenshot({ path: `test-results/prs-fg-budget-dialog-${vp.name}.png` })
      await page.keyboard.press('Escape')
      await expect(page.locator('[data-slot="prs-budget-dialog"]')).toHaveCount(0)
      await root.locator('[data-slot="prs-sections"]').getByRole('tab', { name: /^(Sayfalar|Pages)/ }).click()
      await root.locator('[data-slot="prs-site-pages"]').screenshot({ path: `test-results/prs-fg-pages-${vp.name}.png` })
      await root.locator('[data-slot="prs-sections"]').getByRole('tab', { name: /^(Eğilim|Trend)/ }).click()
      await expect(root.locator('[data-slot="prs-tv-chart"]')).toHaveCount(4)
      await root.locator('[data-slot="prs-tv"]').screenshot({ path: `test-results/prs-fg-trend-${vp.name}.png` })
      await root.locator('[data-slot="prs-start-more"]').click()
      await page.locator('[data-slot="prs-start-site"]').click()
      await expect(page.locator('[data-slot="prs-site-dialog"]')).toBeVisible()
      await page.screenshot({ path: `test-results/prs-fg-site-dialog-${vp.name}.png` })
    })

    test('site analizi başlatma: bölünmüş düğme → pencere → gövde; koşu kartında site işareti; bitince Sayfalar', async ({ page }) => {
      test.setTimeout(120_000)
      const state = await routes(page)
      await page.goto('/?tab=page&monitor=100&mtab=resources')
      const dlg = page.locator(DIALOG)
      const root = dlg.locator(ROOT)
      await expect(root.locator('[data-slot="prs-hero"]')).toBeVisible({ timeout: 20_000 })
      await root.locator('[data-slot="prs-start-more"]').click()
      const item = page.locator('[data-slot="prs-start-site"]')
      await expect(item).toBeVisible()
      if (vp.touch) {
        const h = await item.evaluate((el) => el.getBoundingClientRect().height)
        expect(h, 'menü öğesi dokunma hedefi').toBeGreaterThanOrEqual(39.5)
      }
      await item.click()
      const sd = page.locator('[role="dialog"]:has([data-slot="prs-site-dialog"])')
      await expect(sd).toBeVisible()
      const sdSel = '[role="dialog"]:has([data-slot="prs-site-dialog"])'
      await checker(page, sdSel, sdSel, `${sdSel} [data-slot="modal-shell-body"]`)('site penceresi')
      await sd.locator('[data-slot="prs-site-pages"]').selectOption('7')
      await sd.locator('[data-slot="prs-site-confirm"]').click()
      await expect(sd).toHaveCount(0)
      expect(state.posts).toEqual([{ scope: 'site', max_pages: 7 }])
      await expect(root.locator('[data-slot="prs-run-site"]')).toBeVisible()
      await checker(page)('site koşusu')
      await expect(root.locator('[data-slot="prs-sec-pages"]')).toBeVisible({ timeout: 20_000 })
    })

    test('eski sunucu scope bilmiyor → koşu kartında not; sığar', async ({ page }) => {
      test.setTimeout(90_000)
      const state = await routes(page, { oldServer: true })
      await page.goto('/?tab=page&monitor=100&mtab=resources')
      const root = page.locator(DIALOG).locator(ROOT)
      await expect(root.locator('[data-slot="prs-hero"]')).toBeVisible({ timeout: 20_000 })
      await root.locator('[data-slot="prs-start-more"]').click()
      await page.locator('[data-slot="prs-start-site"]').click()
      await page.locator('[data-slot="prs-site-confirm"]').click()
      await expect(root.locator('[data-slot="prs-site-ignored"]')).toBeVisible()
      expect(state.posts).toEqual([{ scope: 'site', max_pages: 5 }])
      await checker(page)('eski sunucu notu')
    })

    test('yönetici PDF\'i ve Excel GERÇEKTEN iner: PDF ≥ 2 sayfa, Excel\'de yönetici ve A–E sayfaları', async ({ page }) => {
      test.setTimeout(120_000)
      await routes(page)
      await page.goto('/?tab=page&monitor=100&mtab=resources')
      const root = page.locator(DIALOG).locator(ROOT)
      await expect(root.locator('[data-slot="prs-hero"]')).toBeVisible({ timeout: 20_000 })
      const [xl] = await Promise.all([page.waitForEvent('download'), root.locator('[data-slot="prs-export-xlsx"]').click()])
      expect(xl.suggestedFilename()).toMatch(/\.xlsx$/)
      const files = unzipSync(new Uint8Array(readFileSync(await xl.path())))
      const wb = strFromU8(files['xl/workbook.xml'])
      expect(wb).toMatch(/Yönetici özeti|Executive summary/)
      expect(wb).toMatch(/Güvenlik|Security/)
      expect(wb).toMatch(/Sayfalar|Pages/)
      const [pdf] = await Promise.all([page.waitForEvent('download'), root.locator('[data-slot="prs-export-pdf"]').click()])
      expect(pdf.suggestedFilename()).toMatch(/\.pdf$/)
      const buf = readFileSync(await pdf.path())
      expect(buf.subarray(0, 4).toString()).toBe('%PDF')
      expect(buf.length).toBeGreaterThan(20_000)
      const pages = (buf.toString('latin1').match(/\/Type\s*\/Page[^s]/g) || []).length
      expect(pages, 'yönetici özeti + ayrıntı').toBeGreaterThanOrEqual(2)
    })
  })
}
