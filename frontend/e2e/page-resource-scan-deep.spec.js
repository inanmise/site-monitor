// Sayfa bütünlüğü → "Kaynak analizi" → derin analiz paketleri A–E (prs3, 2026-11-04 kullanıcı kararı; arayüz shadcn, mobil
// 390 / 768 dokunmatik + masaüstü 1280). Algoritma 3 tam analiz (sahte API): pano kategori puanları → kutucuk ilgili
// denetim alanını açar (URL `prs_v=audit-<alan>`), altı alanın her biri (yükleme ve ağ, israf ve önbellek, görsel ve yazı
// tipi, güvenlik, SEO, erişilebilirlik) gezinilir; uzun liste "Tümünü göster" ile açılır; denetimden nesne listesine bulgu
// süzgeci; nesne ayrıntısının derin alanları. Eski (algoritma 2) analiz: kutucuklar "bu analizde yok", denetim bölümü
// yeniden analiz önerir. Her aşamada: ekran dışına taşan öğe yok, pencere gövdesi yatay kaymaz, metin kırpılmaz,
// dokunmatikte hedefler ≥ 40 px; tablolar kap genişliğine göre tablo ↔ kart.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { scanDetailV2, scanHistory } from '../src/test/helpers/resourceScanFixtures.js'
import { scanDetailV3 } from '../src/test/helpers/resourceScanFixturesV3.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]
const DIALOG = '[role="dialog"]:has([data-slot="prs-root"])'
const OD_DIALOG = '[role="dialog"]:has([data-slot="prs-od"])'
const ROOT = '[data-slot="prs-root"]'
const AREAS = ['loading', 'waste', 'media', 'security', 'seo', 'a11y']
const json = (body, status = 200) => (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

/** Geçmiş: 12 ve 9 algoritma 3, 10 algoritma 2 (eski). */
function history() {
  const h = scanHistory()
  return { ...h, items: h.items.map((s) => (s.status !== 'DONE' ? s : { ...s, algorithm_version: s.id === 10 ? 2 : 3 })) }
}
function detailOf(id) {
  if (id === 10) return scanDetailV2(10, { scan: { grade: 64, grade_letter: 'D', algorithm_version: 2 } })
  if (id === 11) return { scan: scanHistory().items[1], analysis: null, items: null, items_kept: false }
  return scanDetailV3(id)
}

async function routes(page) {
  await mockApi(page)
  await page.route((u) => new URL(u).pathname === '/api/monitoring/page/100/resource-scans', json({ success: true, data: history() }))
  await page.route((u) => /^\/api\/monitoring\/page\/100\/resource-scans\/\d+$/.test(new URL(u).pathname), (route) => {
    const id = Number(new URL(route.request().url()).pathname.split('/').pop())
    return json({ success: true, data: detailOf(id) })(route)
  })
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

/** Kırpılan metin: yaprak öğe içeriğinden geniş ya da üç noktayla kesilmiş. */
function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('span, p, li, h3, h4, h5, label, button, dt, dd, code, td, th, a')]
    .filter((n) => !n.closest('.sr-only') && !n.closest('select') && !n.closest('svg'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 6
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')).map((n) => n.textContent.slice(0, 40))
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, select, input:not([type="hidden"]), [role="combobox"], [role="tab"]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled) continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none' || b.getAttribute('aria-hidden') === 'true') continue
    if (r.height < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(r.height)}`)
  }
  return out
}

/** Açık bağlantı (NVD) dokunmatikte de ≥ 40 px. */
function smallLinks(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('[data-slot="prs-d-vuln-link"]')].map((a) => a.getBoundingClientRect())
    .filter((r) => r.height > 0 && r.height < 39.5).map((r) => Math.round(r.height))
}

for (const vp of VIEWPORTS) {
  test.describe(`Kaynak analizi → derin analiz (A–E) — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    const checker = (page, dlg, sel = DIALOG, rootSel = ROOT) => async (stage) => {
      await page.waitForTimeout(250)
      const m = await page.evaluate(measure, sel)
      expect(m.offenders, `${stage} @${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `${stage} @${vp.name}: sayfa yatay kayıyor`).toBeLessThanOrEqual(1)
      const body = dlg.locator('[data-slot="modal-shell-body"]').first()
      const hScroll = await body.evaluate((el) => el.scrollWidth - el.clientWidth)
      const wide = hScroll > 1 ? await body.evaluate((el) => {
        const right = el.getBoundingClientRect().right
        return [...el.querySelectorAll('*')].filter((n) => n.getBoundingClientRect().right > right + 1)
          .slice(0, 6).map((n) => `${n.tagName.toLowerCase()}[${n.getAttribute('data-slot') || ''}] ${Math.round(n.getBoundingClientRect().right - right)}`)
      }) : []
      expect(hScroll, `${stage} @${vp.name}: pencere gövdesi yatay kayıyor ${wide.join(', ')}`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, rootSel), `${stage} @${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) {
        expect(await page.evaluate(smallTargets, rootSel), `${stage} @${vp.name}: küçük dokunma hedefi`).toEqual([])
        expect(await page.evaluate(smallLinks, rootSel), `${stage} @${vp.name}: küçük bağlantı`).toEqual([])
      }
    }

    test('algoritma 3: kategori puanları → altı denetim alanı → nesne listesi; sığar, kırpılmaz, dokunmatik ≥ 40 px', async ({ page }) => {
      test.setTimeout(180_000)
      await routes(page)
      await page.goto('/?tab=page&monitor=100&mtab=resources')
      const dlg = page.locator(DIALOG)
      const root = dlg.locator(ROOT)
      await expect(root.locator('[data-slot="prs-hero"]')).toBeVisible({ timeout: 20_000 })
      const check = checker(page, dlg)

      // 1) Pano: dört kategori puanı (hesaplanamayan soluk "—"), önerilerden önce
      const tiles = root.locator('[data-slot="prs-cat-score"]')
      await expect(tiles).toHaveCount(4)
      await expect(tiles.nth(3)).toHaveAttribute('data-tone', 'muted')
      await expect(tiles.nth(3)).toContainText('—')
      // Bölüm sekmeleri (5: Denetim + Eğilim dahil) hiçbiri kırpılmıyor; sm+ tek satır, telefonda 3 + 2 (sectionTabCols)
      const secTabs = root.locator('[data-slot="prs-sections"] > [role="tablist"] [role="tab"]')
      await expect(secTabs).toHaveCount(5)
      const tops = await secTabs.evaluateAll((els) => els.map((el) => Math.round(el.getBoundingClientRect().top)))
      if (vp.width >= 640) {
        expect(Math.max(...tops) - Math.min(...tops), `@${vp.name}: bölüm sekmeleri tek satır değil`).toBeLessThanOrEqual(1)
      } else {
        expect(new Set(tops).size, `@${vp.name}: telefonda bölüm sekmeleri iki satır olmalı`).toBe(2)
      }
      const tabOverflow = await secTabs.evaluateAll((els) => els.filter((el) => el.scrollWidth > el.clientWidth + 1).map((el) => el.textContent))
      expect(tabOverflow, `@${vp.name}: bölüm sekmesi kırpılıyor`).toEqual([])
      await root.locator('[data-slot="prs-cat-scores"]').scrollIntoViewIfNeeded()
      await check('pano + kategori puanları')

      // 2) Güvenlik kutucuğu → denetim (güvenlik), odak alan başlığında, URL'de alan
      await tiles.nth(1).locator('[data-slot="prs-cat-score-open"]').click()
      const audit = root.locator('[data-slot="prs-audit"]')
      await expect(audit).toHaveAttribute('data-area', 'security', { timeout: 10_000 })
      await expect(root.locator('[data-focus-target="prs-audit"]')).toBeFocused()
      await expect(page).toHaveURL(/prs_v=audit-security/)
      await expect(root.locator('[data-slot="prs-audit-tab"]')).toHaveCount(6)

      // 3) Her alan: gezin, blokları gör, ölç
      for (const a of AREAS) {
        await root.locator(`[data-slot="prs-audit-tab"][data-area="${a}"]`).click()
        await expect(audit).toHaveAttribute('data-area', a)
        if (a === 'loading') {
          await expect(root.locator('[data-slot="prs-a-rb-row"]')).toHaveCount(4)
          // kap genişliğine göre: telefonda kart, dizüstünde tablo (tablette kabın gerçek genişliği belirler)
          if (vp.name !== 'tablet') await expect(root.locator('[data-slot="prs-a-rb-list"]')).toHaveAttribute('data-layout', vp.name === 'laptop' ? 'table' : 'cards')
          await expect(root.locator('[data-slot="prs-a-profile"]')).toHaveCount(4)
          await expect(root.locator('[data-slot="prs-a-rb"] [data-kind="estimate"]').first()).toBeVisible()
        }
        if (a === 'waste') {
          await expect(root.locator('[data-slot="prs-b-cache-bucket"]')).toHaveCount(5)
          await expect(root.locator('[data-slot="prs-b-map-row"][data-exposed="true"]')).toHaveCount(1)
        }
        if (a === 'media') {
          const md = root.locator('[data-slot="prs-c-img-group"][data-key="missing_dimensions"]')
          await expect(md.locator('[data-slot="prs-deep-url"]')).toHaveCount(8)
          await md.locator('[data-slot="prs-deep-more"]').click()
          await expect(md.locator('[data-slot="prs-deep-url"]')).toHaveCount(50)
          await expect(md.locator('[data-slot="prs-deep-capped"]')).toBeVisible()
        }
        if (a === 'security') {
          await expect(root.locator('[data-slot="prs-d-header"]')).toHaveCount(8)
          await expect(root.locator('[data-slot="prs-d-kvkk"]')).toBeVisible()
          await expect(root.locator('[data-slot="prs-d-vuln-link"]').first()).toHaveAttribute('href', /nvd\.nist\.gov/)
        }
        if (a === 'seo' || a === 'a11y') {
          await expect(root.locator('[data-slot="prs-e-check"]').first()).toHaveAttribute('data-status', 'fail')
        }
        await expect(page).toHaveURL(a === 'loading' ? /prs_v=audit(?!-)/ : new RegExp(`prs_v=audit-${a}`))
        await check(`denetim: ${a}`)
      }
      // Gezinme kutucukları (6) taşmaz
      await audit.scrollIntoViewIfNeeded()
      await check('denetim gezinmesi')

      // 4) Denetimden nesne listesine: "Nesneleri göster" → bulgu süzgeci
      await root.locator('[data-slot="prs-audit-tab"][data-area="loading"]').click()
      await root.locator('[data-slot="prs-a-rb"] [data-slot="prs-deep-show-objects"]').click()
      await expect(root.locator('[data-slot="prs-objects"]')).toBeVisible()
      await expect(root.locator('[data-slot="prs-obj"]')).toHaveCount(3)
      await expect(root.locator('[data-slot="prs-count"]')).toBeFocused()
      await check('nesneler (çizimi engelleyenler)')

      // 5) Nesne ayrıntısı: derin alanlar (HTTP sürümü, kütüphane, SRI, kategori)
      await root.locator('[data-slot="prs-obj"]', { hasText: 'jquery/1.12.4' }).locator('[data-slot="prs-obj-open"]').click()
      const od = page.locator(OD_DIALOG)
      await expect(od.locator('[data-slot="prs-od-library"]')).toContainText('jquery 1.12.4')
      await expect(od.locator('[data-slot="prs-od-http"]')).toContainText('HTTP/2')
      await checker(page, od, OD_DIALOG, OD_DIALOG)('nesne ayrıntısı (derin)')
      await page.screenshot({ path: `test-results/page-resource-scan-deep-${vp.name}.png`, fullPage: false })
    })

    test('eski (algoritma 2) analiz: kutucuklar "bu analizde yok", denetim yeniden analiz önerir; sığar', async ({ page }) => {
      test.setTimeout(120_000)
      await routes(page)
      await page.goto('/?tab=page&monitor=100&mtab=resources&prs=10')
      const dlg = page.locator(DIALOG)
      const root = dlg.locator(ROOT)
      await expect(root.locator('[data-slot="prs-grade"]')).toHaveAttribute('data-letter', 'D', { timeout: 20_000 })
      const tiles = root.locator('[data-slot="prs-cat-score"]')
      await expect(tiles).toHaveCount(4)
      await expect(tiles.nth(0)).toHaveAttribute('data-available', 'true')
      for (const i of [1, 2, 3]) await expect(tiles.nth(i)).toHaveAttribute('data-available', 'false')
      await expect(root.locator('[data-slot="prs-cat-scores-old"]')).toBeVisible()
      await expect(root.locator('[data-slot="prs-rec-audit"]')).toHaveCount(0)
      await root.locator('[data-slot="prs-cat-scores"]').scrollIntoViewIfNeeded()
      await checker(page, dlg)('eski analiz pano')
      await tiles.nth(2).locator('[data-slot="prs-cat-score-open"]').click()
      await expect(root.locator('[data-slot="prs-audit-old"]')).toBeVisible()
      await expect(root.locator('[data-slot="prs-audit-restart"]')).toBeVisible()
      await expect(root.locator('[data-slot="prs-audit"]')).toHaveCount(0)
      await checker(page, dlg)('eski analiz denetim')
    })
  })
}
