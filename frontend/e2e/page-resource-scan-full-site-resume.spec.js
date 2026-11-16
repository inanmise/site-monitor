// Tüm site kaynak analizi — sürdürme ve süren tarama (inceleme 2026-11-17). Telefon (390, dokunmatik), tablet (768,
// dokunmatik), dizüstü (1280):
//  (a) YARIDA KALAN tüm site taraması → "Kaldığı yerden sürdür" gövdesi resume_from taşır; sunucunun 409 retleri
//      (RESOURCE_SCAN_NOTHING_TO_RESUME, RESOURCE_SCAN_NOT_RESUMABLE) METİN olarak görünür; ret sonrası analiz yeniden
//      okunur — sunucu denetim noktasını bıraktıysa (resumable false) düğme kalkar.
//  (b) SÜREN tüm site taraması sayfa ilerlemesini gösterir ("Site · sayfa n / N", ilerledikçe güncellenir), bitince sonuca
//      devreder (ilerleme kartı kalkar, analiz + Sayfalar bölümü görünür).
// Her aşamada: ekran dışına taşan öğe yok, sayfa yatay kaymaz, metin kırpılmaz, dokunmatikte hedefler ≥ 40 px.
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

const NOTHING_TEXT = 'Bu analizde taranmayı bekleyen sayfa kalmadı — bulunan bütün sayfalar tarandı.'
const NOT_RESUMABLE_TEXT = 'Bu analiz sürdürülemez: tüm site taraması değil, sürdürme bilgisi yok ya da daha önce sürdürüldü. '
  + 'En son tüm site taramasından sürdürün ya da yeni bir tüm site taraması başlatın.'

const interruptedRow = (resumable) => scanSummary(41, {
  status: 'INTERRUPTED', scope: 'site', crawl: 'full', resumable, grade: null, grade_letter: null, load_ms: null,
  error: 'Analiz yarıda kaldı: çalıştığı sunucu kapandı (yeni sürüm dağıtılıyordu). Kaldığı yerden sürdürülebilir.',
  started_at: '2026-11-16T08:00:00', finished_at: '2026-11-16T09:10:00',
  progress: { done: 3400, total: 3400, pages_done: 640, pages_total: 2500 }, pages_count: null,
})

/** (a) Yarıda kalan taramanın sürdürülmesi: POST yanıtları sırayla; ret sonrası ayrıntı yeniden okunur. */
async function interruptedRoutes(page) {
  await mockApi(page, { monitors: { ...MONITORS, page: MONITORS.page.map((m) => ({ ...m, can_edit: true })) } })
  const state = { posts: [], resumable: true, detailReads: 0, replies: [] }
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-scans$/), (route) => {
    if (route.request().method() === 'POST') {
      let body = null
      try { body = JSON.parse(route.request().postData() || 'null') } catch { body = null }
      state.posts.push(body)
      const r = state.replies.shift()
      if (r === 'nothing') return json({ success: false, code: 'RESOURCE_SCAN_NOTHING_TO_RESUME', error: NOTHING_TEXT }, 409)(route)
      if (r === 'gone') {
        state.resumable = false   // sunucu kaynağın denetim noktasını bırakmış (zaten sürdürüldü)
        return json({ success: false, code: 'RESOURCE_SCAN_NOT_RESUMABLE', error: NOT_RESUMABLE_TEXT }, 409)(route)
      }
      return json({ success: false, code: 'X', error: 'beklenmeyen' }, 500)(route)
    }
    const h = fullSiteHistory()
    return json({ success: true, data: { ...h, items: [interruptedRow(state.resumable), ...h.items] } })(route)
  })
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-scans\/trend$/), json({ success: true, data: trendData() }))
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-scans\/\d+$/), (route) => {
    const id = Number(new URL(route.request().url()).pathname.split('/').pop())
    if (id === 41) {
      state.detailReads += 1
      return json({ success: true, data: { scan: interruptedRow(state.resumable), analysis: null, items: null, items_kept: false } })(route)
    }
    return json({ success: true, data: id === 31 ? fullSiteDetail(31) : siteDetail(id) })(route)
  })
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-budget$/), json({ success: true, data: budgetData({}) }))
  return state
}

/** (b) Süren tüm site taraması: ilk okumalarda RUNNING (sayfa ilerlemesi artar), sonra DONE. */
async function runningRoutes(page) {
  await mockApi(page, { monitors: { ...MONITORS, page: MONITORS.page.map((m) => ({ ...m, can_edit: true })) } })
  const state = { polls: 0, finished: false }
  const runRow = (pagesDone) => scanSummary(70, {
    status: 'RUNNING', scope: 'site', crawl: 'full', grade: null, grade_letter: null, load_ms: null, finished_at: null,
    started_at: new Date(Date.now() - 90_000).toISOString().slice(0, 19), owner_pod: 'site-monitor-7d9f5c6b8-x2kqp',
    progress: { done: 40 + pagesDone * 10, total: 400 + pagesDone * 10, pages_done: pagesDone, pages_total: 50 },
  })
  const doneDetail = () => {
    const d = fullSiteDetail(70)
    return { ...d, scan: { ...d.scan, id: 70, status: 'DONE', crawl: 'full', scope: 'site', started_at: '2026-11-17T08:00:00',
      finished_at: '2026-11-17T08:40:00' } }
  }
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-scans$/), (route) => {
    const h = fullSiteHistory()
    if (state.finished) return json({ success: true, data: { ...h, running: null, items: [doneDetail().scan, ...h.items] } })(route)
    const row = runRow(3)
    return json({ success: true, data: { ...h, running: row, items: [row, ...h.items] } })(route)
  })
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-scans\/trend$/), json({ success: true, data: trendData() }))
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-scans\/\d+$/), (route) => {
    const id = Number(new URL(route.request().url()).pathname.split('/').pop())
    if (id === 70) {
      if (state.finished) return json({ success: true, data: doneDetail() })(route)
      state.polls += 1
      // 1–2. okuma: sayfa 3 → 10; ardından tarama biter
      if (state.polls >= 3 && state.release) { state.finished = true; return json({ success: true, data: doneDetail() })(route) }
      const pagesDone = state.polls >= 2 ? 10 : 3
      return json({ success: true, data: { scan: runRow(pagesDone), analysis: null, items: null, items_kept: true } })(route)
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
  test.describe(`Tüm site kaynak analizi — sürdürme / süren tarama — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    const checker = (page) => async (stage) => {
      await page.waitForTimeout(300)
      const m = await page.evaluate(measure, DIALOG)
      expect(m.offenders, `${stage} @${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `${stage} @${vp.name}: sayfa yatay kayıyor`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, ROOT), `${stage} @${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, ROOT), `${stage} @${vp.name}: küçük dokunma hedefi`).toEqual([])
    }

    test('(a) yarıda kalan tüm site taraması: sürdür → resume_from; 409 retleri metin olarak; nokta kalktıysa düğme kalkar', async ({ page }) => {
      test.setTimeout(120_000)
      const state = await interruptedRoutes(page)
      await page.goto('/?tab=page&monitor=100&mtab=resources&prs=41')
      const root = page.locator(DIALOG).locator(ROOT)
      const box = root.locator('[data-slot="prs-interrupted"]')
      await expect(box).toBeVisible({ timeout: 20_000 })
      const resume = root.locator('[data-slot="prs-interrupted-resume"]')
      await expect(resume).toBeVisible()
      await expect(root.locator('[data-slot="prs-interrupted-resume-hint"]')).toBeVisible()
      await checker(page)('yarıda kalan tüm site taraması')

      // 1) Kuyruk boş: sunucu 409 NOTHING_TO_RESUME — ileti METİN olarak (kod değil); nokta hâlâ var → düğme kalır
      state.replies.push('nothing')
      const readsBefore = state.detailReads
      await resume.click()
      await expect.poll(() => state.posts.length).toBe(1)
      expect(state.posts[0]).toEqual({ scope: 'site', crawl: 'full', max_pages: 2500, resume_from: 41 })
      await expect(page.getByText(NOTHING_TEXT).first()).toBeVisible()
      await expect(page.getByText('RESOURCE_SCAN_NOTHING_TO_RESUME')).toHaveCount(0)
      await expect.poll(() => state.detailReads, { message: 'ret sonrası analiz yeniden okunur' }).toBeGreaterThan(readsBefore)
      await expect(resume).toBeVisible()

      // 2) Zaten sürdürülmüş: 409 NOT_RESUMABLE — ileti görünür; yeniden okunan analiz `resumable: false` → düğme kalkar
      state.replies.push('gone')
      await resume.click()
      await expect.poll(() => state.posts.length).toBe(2)
      expect(state.posts[1]).toEqual({ scope: 'site', crawl: 'full', max_pages: 2500, resume_from: 41 })
      await expect(page.getByText(/Bu analiz sürdürülemez: tüm site taraması değil/).first()).toBeVisible()
      await expect(page.getByText('RESOURCE_SCAN_NOT_RESUMABLE')).toHaveCount(0)
      await expect(root.locator('[data-slot="prs-interrupted-resume"]')).toHaveCount(0)
      await expect(box).toBeVisible()
      await checker(page)('sürdürme reddi sonrası')
    })

    test('(b) süren tüm site taraması sayfa ilerlemesini gösterir, bitince sonuca devreder; sığar, kırpılmaz', async ({ page }) => {
      test.setTimeout(120_000)
      const state = await runningRoutes(page)
      await page.goto('/?tab=page&monitor=100&mtab=resources')
      const root = page.locator(DIALOG).locator(ROOT)
      const running = root.locator('[data-slot="prs-running"]')
      await expect(running).toBeVisible({ timeout: 20_000 })
      const chip = running.locator('[data-slot="prs-run-site"]')
      await expect(chip).toContainText(/Site · (sayfa 3 \/ 50|page 3 of 50)/)
      await expect(running.locator('[data-slot="prs-progress-text"]')).toContainText(/nesne ölçüldü|objects measured/)
      await expect(running.locator('[data-slot="prs-stage"]')).toContainText('site-monitor-7d9f5c6b8-x2kqp')
      // ilerleme çubuğu sayfa tabanlı: 3 / 50 sayfa → %6 civarı (ana sayfada %100'e takılmaz)
      const pct = await running.locator('[role="progressbar"]').first().getAttribute('aria-valuenow')
      expect(Number(pct)).toBeLessThan(30)
      await checker(page)('süren tüm site taraması')

      // İlerleme güncellenir: sayfa 10 / 50
      await expect(chip).toContainText(/Site · (sayfa 10 \/ 50|page 10 of 50)/, { timeout: 20_000 })
      await checker(page)('süren tüm site taraması — ilerledi')

      // Biter → ilerleme kartı kalkar, sonuç (analiz + Sayfalar bölümü) görünür
      state.release = true
      await expect(running).toHaveCount(0, { timeout: 30_000 })
      await expect(page, 'biten tarama seçildi (sonuca devredildi)').toHaveURL(/[?&]prs=70(&|$)/, { timeout: 20_000 })
      await expect(root.locator('[data-slot="prs-hero"]')).toBeVisible({ timeout: 20_000 })
      const pagesTab = root.locator('[data-slot="prs-sections"]').getByRole('tab', { name: /^(Sayfalar|Pages)/ })
      await expect(pagesTab).toBeVisible()
      await pagesTab.click()
      await expect(root.locator('[data-slot="prs-crawl"]')).toBeVisible()
      await checker(page)('tüm site taraması bitti — sonuç')
    })
  })
}
