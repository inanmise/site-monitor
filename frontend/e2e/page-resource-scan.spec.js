// Sayfa bütünlüğü → "Kaynak analizi" sekmesi (2026-10-31, kullanıcı isteği: "sayfanın tüm nesnelerini tara, kategorize et,
// boyut + süreyle büyükten küçüğe sırala, süz, Excel / PDF; puan, yüklenme süresi, geçmiş grafiği, öneriler, büyük görsellerin
// etkisi; shadcn, mweb"). Telefon (390, dokunmatik), tablet (768, dokunmatik) ve dizüstünde (1280): detay penceresi derin
// bağlantıyla (`mtab=resources`) sekmede açılır; boş durum → "Analizi başlat" → ilerleme (ardışık RUNNING yanıtları) → pano
// (not halkası, kutucuklar, grafik, kırılım, öneriler, görsel etkisi, notlar) → kategoriye basınca nesne listesi süzülür
// (dar kapta KART, geniş ekranda TABLO), kova / durum süzgeçleri, Excel (ve dizüstünde PDF) indirmesi başlar, geçmiş listesi.
// Her aşamada: pencerede ekran dışına taşan öğe yok, sayfa / pencere gövdesi yatay kaymaz, metin kırpılmaz, dokunmatikte
// hedefler ≥ 40 px. API tamamen taklit (yalnız `/api/` yolları).
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { scanDetail, scanHistory, scanSummary } from '../src/test/helpers/resourceScanFixtures.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]
const DIALOG = '[role="dialog"]:has([data-slot="prs-root"])'
const ROOT = '[data-slot="prs-root"]'
const json = (body, status = 200) => (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

/** mockApi'den SONRA kaydedilen yollar önceliklidir. Koşu durumlu: boş geçmiş → başlat → 2 RUNNING → DONE. */
async function routes(page) {
  await mockApi(page)
  const state = { started: false, polls: 0, posts: 0 }
  const running = scanSummary(50, { status: 'RUNNING', progress: { done: 0, total: 0 }, grade: null, grade_letter: null, load_ms: null,
    finished_at: null, started_at: new Date(Date.now() - 4000).toISOString().slice(0, 19) })
  const done = scanSummary(50, { started_at: running.started_at })
  await page.route((u) => new URL(u).pathname === '/api/monitoring/page/100/resource-scans', (route) => {
    if (route.request().method() === 'POST') {
      state.started = true
      state.posts += 1
      return json({ success: true, data: running }, 202)(route)
    }
    if (!state.started) return json({ success: true, data: { items: [], running: null, can_run: true, limits: { max_seconds: 300, max_objects: 1500, detail_runs: 20 } } })(route)
    if (state.polls < 3) return json({ success: true, data: { ...scanHistory(), items: [running], running } })(route)
    const h = scanHistory()
    return json({ success: true, data: { ...h, items: [done, ...h.items] } })(route)
  })
  await page.route((u) => /^\/api\/monitoring\/page\/100\/resource-scans\/\d+$/.test(new URL(u).pathname), (route) => {
    const id = Number(new URL(route.request().url()).pathname.split('/').pop())
    if (id === 50) {
      state.polls += 1
      if (state.polls === 1) return json({ success: true, data: { scan: { ...running, progress: { done: 9, total: 40 } }, analysis: null, items: null, items_kept: true } })(route)
      if (state.polls === 2) return json({ success: true, data: { scan: { ...running, progress: { done: 31, total: 40 } }, analysis: null, items: null, items_kept: true } })(route)
      return json({ success: true, data: scanDetail(50, { scan: { started_at: running.started_at } }) })(route)
    }
    if (id === 10) return json({ success: true, data: { ...scanDetail(10, { scan: { grade: 64, grade_letter: 'D', items_kept: false } }), items: null, items_kept: false } })(route)
    return json({ success: true, data: scanDetail(id) })(route)
  })
  return state
}

/** Pencerede görünüm alanının dışına taşan (kendi kaydırma kabında olmayan) öğeler + sayfa düzeyi yatay taşma. */
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
  return [...root.querySelectorAll('span, p, li, h3, h4, h5, label, button, dt, dd, code, td, th, a')]
    .filter((n) => !n.closest('.sr-only') && !n.closest('select') && !n.closest('svg'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 12
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')).map((n) => n.textContent.slice(0, 40))
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, select, input:not([type="hidden"]), [role="combobox"]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled) continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none' || b.getAttribute('aria-hidden') === 'true') continue
    if (r.height < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(r.height)}`)
  }
  return out
}

for (const vp of VIEWPORTS) {
  test.describe(`Sayfa bütünlüğü → Kaynak analizi — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('boş durum → başlat → ilerleme → pano → nesneler (süzgeç) → dışa aktarım → geçmiş; sığar, kırpılmaz', async ({ page }) => {
      test.setTimeout(150_000)
      const state = await routes(page)
      await page.goto('/?tab=page&monitor=100&mtab=resources')
      const dlg = page.locator(DIALOG)
      const root = dlg.locator(ROOT)
      await expect(root).toHaveAttribute('data-state', 'ok', { timeout: 20_000 })
      await expect(dlg.getByRole('tab', { name: /Resource analysis|Kaynak analizi/ })).toHaveAttribute('aria-selected', 'true')

      const check = async (stage) => {
        await page.waitForTimeout(300)
        const m = await page.evaluate(measure, DIALOG)
        expect(m.offenders, `${stage} @${vp.name}: ekran dışına taşan öğe`).toEqual([])
        expect(m.pageOverflow, `${stage} @${vp.name}: sayfa yatay kayıyor`).toBeLessThanOrEqual(1)
        const hScroll = await dlg.locator('[data-slot="modal-shell-body"]').first().evaluate((el) => el.scrollWidth - el.clientWidth)
        expect(hScroll, `${stage} @${vp.name}: pencere gövdesi yatay kayıyor`).toBeLessThanOrEqual(1)
        expect(await page.evaluate(clipped, ROOT), `${stage} @${vp.name}: kırpılan metin`).toEqual([])
        if (vp.touch) expect(await page.evaluate(smallTargets, ROOT), `${stage} @${vp.name}: küçük dokunma hedefi`).toEqual([])
      }

      // 1) Boş durum: açıklama + tek "Analizi başlat"
      const empty = root.locator('[data-slot="prs-empty"]')
      await expect(empty).toBeVisible()
      await expect(empty.locator('[data-slot="prs-empty-facts"] li')).toHaveCount(4)
      await expect(root.locator('[data-slot="prs-start"]')).toHaveCount(1)
      await check('boş durum')

      // 2) Başlat → ilerleme → pano
      await root.locator('[data-slot="prs-start"]').click()
      await expect(root.locator('[data-slot="prs-running"]')).toBeVisible()
      await expect(root.locator('[data-slot="prs-progress-text"]')).toContainText(/9|31/, { timeout: 10_000 })
      await check('ilerleme')
      await expect(root.locator('[data-slot="prs-hero"]')).toBeVisible({ timeout: 20_000 })
      expect(state.posts).toBe(1)
      await expect(root.locator('[data-slot="prs-running"]')).toHaveCount(0)
      await expect(root.locator('[data-slot="prs-grade"]')).toHaveAttribute('data-letter', 'C')
      await expect(root.locator('[data-slot="prs-kpi"]')).toHaveCount(5)
      await expect(root.locator('[data-slot="prs-trend-load"] svg').first()).toBeVisible({ timeout: 10_000 })
      await expect(root.locator('[data-slot="prs-rec"]')).toHaveCount(5)
      await expect(root.locator('[data-slot="prs-rec"]').first()).toHaveAttribute('data-code', 'LARGE_IMAGES')
      await expect(root.locator('[data-slot="prs-rec-hosts"]')).toContainText('analytics.example.net')
      await expect(root.locator('[data-slot="prs-image-row"]')).toHaveCount(3)
      // sm+ (768 / 1280): "Yeniden analiz et / Excel / PDF" TEK satırda — uzun künye düğmeleri alt satıra itmez
      if (vp.width >= 640) {
        const tops = await root.locator('[data-slot="prs-actions"] > button').evaluateAll((els) =>
          els.map((el) => Math.round(el.getBoundingClientRect().top)))
        expect(tops.length, `@${vp.name}: başlık eylemleri`).toBe(3)
        expect(Math.max(...tops) - Math.min(...tops), `@${vp.name}: başlık eylemleri tek satır değil (${tops.join(', ')})`).toBeLessThanOrEqual(1)
      }
      await expect(root.locator('[data-slot="prs-note"]')).toHaveCount(2)
      await check('pano')
      if (vp.name === 'phone') {
        const box = await dlg.boundingBox()
        expect(Math.round(box.width), 'telefonda tam ekran').toBe(vp.width)
      }

      // 3) Kategori → nesne listesi o türe süzülür; düzen kap genişliğine göre
      await root.locator('[data-slot="prs-cat-row"][data-category="IMAGE"] button').click()
      const list = root.locator('[data-slot="prs-objects-list"]')
      await expect(list).toBeVisible()
      await expect(root.locator('[data-slot="prs-count"]')).toContainText('34')
      await expect(root.locator('[data-slot="prs-type-chips"] [data-category="IMAGE"]')).toHaveAttribute('data-state', 'on')
      await expect(list).toHaveAttribute('data-layout', vp.width >= 1280 ? 'table' : 'cards')
      await check('nesneler (görsel)')

      // 4) Süzgeçler: temizle → ≥ 1 MB kovası (görsel + belge) → yalnız açılmayanlar
      await root.locator('[data-slot="prs-clear-filters"]').click()
      await expect(root.locator('[data-slot="prs-count"]')).toContainText('40')
      await root.locator('[data-slot="prs-bucket-chips"] [data-bucket="xl"]').click()
      await expect(root.locator('[data-slot="prs-obj"]')).toHaveCount(2)
      await expect(root.locator('[data-slot="prs-obj"]').first()).toHaveAttribute('data-category', 'DOCUMENT')
      await check('nesneler (kova)')
      await root.locator('[data-slot="prs-clear-filters"]').click()
      await expect(root.locator('[data-slot="prs-obj"]')).toHaveCount(25)
      await check('nesneler (tümü, ilk sayfa)')

      // 5) Dışa aktarım: Excel indirmesi başlar (dizüstünde PDF de)
      const xlsx = page.waitForEvent('download')
      await root.locator('[data-slot="prs-export-xlsx"]').click()
      const dl = await xlsx
      expect(dl.suggestedFilename()).toMatch(/^www-example-com-(resource-analysis|kaynak-analizi)-\d{4}-\d{2}-\d{2}-\d{4}\.xlsx$/)
      if (vp.width >= 1280) {
        const pdf = page.waitForEvent('download')
        await root.locator('[data-slot="prs-export-pdf"]').click()
        expect((await pdf).suggestedFilename()).toMatch(/\.pdf$/)
      }

      // 6) Geçmiş: yeniden eskiye, seçili satır işaretli; nesne ayrıntısı saklanmayan eski analiz
      await dlg.getByRole('tab', { name: /^(History|Geçmiş)/ }).click()
      const rows = root.locator('[data-slot="prs-history-row"]')
      await expect(rows).toHaveCount(8)
      await expect(rows.first()).toHaveAttribute('aria-current', 'true')
      await check('geçmiş')
      await root.locator('[data-slot="prs-history-row"][data-id="10"]').click()
      await expect(root.locator('[data-slot="prs-grade"]')).toHaveAttribute('data-letter', 'D')
      await dlg.getByRole('tab', { name: /^(Objects|Nesneler)/ }).click()
      await expect(root.locator('[data-slot="prs-items-not-kept"]')).toBeVisible()
      await check('eski analiz')
      await page.screenshot({ path: `test-results/page-resource-scan-${vp.name}.png`, fullPage: false })
    })
  })
}
