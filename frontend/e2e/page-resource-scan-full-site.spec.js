// Tüm site kaynak analizi (2026-11-15, kullanıcı isteği: "tüm site taranmalı, eksik analiz edilen sayfa olmamalı"):
// başlatma menüsünde "Tüm siteyi tara" → onay penceresi → gövde { scope:'site', crawl:'full', max_pages }; Sayfalar
// görünümünde tarama özeti (bulunan / kaynaklar / site haritası / devralma), sayfalı sayfa listesi, taranmayan sayfalar
// (adıyla, kaynağı ve nedeniyle; uzun adres sarar) ve "Kaldığı yerden sürdür" → resume_from. Telefon (390, dokunmatik),
// tablet (768, dokunmatik), dizüstü (1280): ekran dışına taşan öğe yok, metin kırpılmaz, dokunmatikte hedefler ≥ 40 px.
import { test, expect } from '@playwright/test'
import { mockApi, MONITORS } from './support/monitorMocks.js'
import { scanSummary } from '../src/test/helpers/resourceScanFixtures.js'
import { budgetData, fullSiteDetail, fullSiteHistory, siteDetail, trendData } from '../src/test/helpers/resourceScanFgFixtures.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]
const DIALOG = '[role="dialog"]:has([data-slot="prs-root"])'
const ROOT = '[data-slot="prs-root"]'
const json = (body, status = 200) => (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
const PATH = (re) => (u) => re.test(new URL(u).pathname)

async function routes(page) {
  await mockApi(page, { monitors: { ...MONITORS, page: MONITORS.page.map((m) => ({ ...m, can_edit: true })) } })
  const state = { posts: [] }
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-scans$/), (route) => {
    if (route.request().method() === 'POST') {
      let body = null
      try { body = JSON.parse(route.request().postData() || 'null') } catch { body = null }
      state.posts.push(body)
      return json({ success: true, data: scanSummary(70, { status: 'RUNNING', scope: 'site', crawl: 'full', grade: null, grade_letter: null,
        load_ms: null, finished_at: null, started_at: new Date(Date.now() - 2000).toISOString().slice(0, 19),
        progress: { done: 3, total: 30, pages_done: 1, pages_total: 50 } }) }, 202)(route)
    }
    return json({ success: true, data: fullSiteHistory() })(route)
  })
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-scans\/trend$/), json({ success: true, data: trendData() }))
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-scans\/\d+$/), (route) => {
    const id = Number(new URL(route.request().url()).pathname.split('/').pop())
    if (id === 70) {
      return json({ success: true, data: { scan: scanSummary(70, { status: 'RUNNING', scope: 'site', crawl: 'full', grade: null, finished_at: null,
        progress: { done: 3, total: 30, pages_done: 1, pages_total: 50 } }), analysis: null, items: null, items_kept: true } })(route)
    }
    return json({ success: true, data: id === 31 ? fullSiteDetail(31) : siteDetail(id) })(route)
  })
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-budget$/), json({ success: true, data: budgetData({}) }))
  return state
}

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
  return [...root.querySelectorAll('span, p, li, h3, h4, h5, label, button, td, th, a')]
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
  test.describe(`Tüm site kaynak analizi — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    const checker = (page, sel = DIALOG, rootSel = ROOT) => async (stage) => {
      await page.waitForTimeout(300)
      const m = await page.evaluate(measure, sel)
      expect(m.offenders, `${stage} @${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `${stage} @${vp.name}: sayfa yatay kayıyor`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, rootSel), `${stage} @${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, rootSel), `${stage} @${vp.name}: küçük dokunma hedefi`).toEqual([])
    }

    test('Sayfalar: tarama özeti, sayfalı listeler, taranmayan sayfalar; sürdür → resume_from; sığar, kırpılmaz', async ({ page }) => {
      test.setTimeout(120_000)
      const state = await routes(page)
      await page.goto('/?tab=page&monitor=100&mtab=resources')
      const root = page.locator(DIALOG).locator(ROOT)
      await expect(root.locator('[data-slot="prs-hero"]')).toBeVisible({ timeout: 20_000 })
      await root.locator('[data-slot="prs-sections"]').getByRole('tab', { name: /^(Sayfalar|Pages)/ }).click()
      const crawl = root.locator('[data-slot="prs-crawl"]')
      await expect(crawl).toBeVisible()
      await expect(crawl).toHaveAttribute('data-complete', 'false')
      await expect(root.locator('[data-slot="prs-crawl-incomplete"]')).toContainText('34')
      await expect(root.locator('[data-slot="prs-site-page"]')).toHaveCount(25)
      await expect(root.locator('[data-slot="prs-site-unscanned-row"]')).toHaveCount(25)
      await expect(root.locator('[data-slot="prs-site-unscanned-more"]')).toBeVisible()
      await checker(page)('tüm site sayfalar')
      // uzun taranmayan adres sarar, satırın dışına taşmaz
      const longRow = root.locator('[data-slot="prs-site-unscanned-row"]').first()
      const fits = await longRow.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)
      expect(fits, 'uzun adres satırdan taşmıyor').toBe(true)
      await root.locator('[data-slot="prs-crawl-resume"]').click()
      await expect.poll(() => state.posts.length).toBe(1)
      expect(state.posts[0]).toEqual({ scope: 'site', crawl: 'full', max_pages: 1000, resume_from: 31 })
    })

    test('başlatma: menüde "Tüm siteyi tara" → pencere → gövde; pencere sığar, hedefler ≥ 40 px', async ({ page }) => {
      test.setTimeout(90_000)
      const state = await routes(page)
      await page.goto('/?tab=page&monitor=100&mtab=resources')
      const root = page.locator(DIALOG).locator(ROOT)
      await expect(root.locator('[data-slot="prs-hero"]')).toBeVisible({ timeout: 20_000 })
      await root.locator('[data-slot="prs-start-more"]').click()
      const item = page.locator('[data-slot="prs-start-full"]')
      await expect(item).toBeVisible()
      if (vp.touch) {
        const h = await item.evaluate((el) => el.getBoundingClientRect().height)
        expect(h, 'menü öğesi dokunma hedefi').toBeGreaterThanOrEqual(39.5)
      }
      await item.click()
      const fdSel = '[role="dialog"]:has([data-slot="prs-full-dialog"])'
      await expect(page.locator(fdSel)).toBeVisible()
      await checker(page, fdSel, fdSel)('tüm site penceresi')
      await page.locator('[data-slot="prs-full-confirm"]').click()
      await expect(page.locator(fdSel)).toHaveCount(0)
      expect(state.posts).toEqual([{ scope: 'site', crawl: 'full', max_pages: 1000 }])
    })
  })
}
