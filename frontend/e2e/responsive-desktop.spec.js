// MASAÜSTÜ YERLEŞİM KAPISI (2026-11-15, prod kapısı) — responsive.spec.js her sekmeyi telefon (390) ve tablette (768)
// ölçer; masaüstü genişliğinde ölçmez. Bu dosya AYNI sekme listesini 1280×900'de, tablo ağırlıklı sekmeleri ayrıca
// 1920×1080'de açar ve iki şeyi ölçer:
//   1) Yatay taşma: sayfa (belge, gövde, `.app-main`) yatay kaymaz; görünür bir öğe görünüm alanının sağına taşmaz
//      (kendi kaydırma / kırpma kabının — tablo kabı, sekme listesi — içinde kalan öğe taşma sayılmaz).
//   2) Kırpılan başlık: sayfa başlığı, kart başlıkları ve h1–h4 kendi kutusunda (`truncate`, `line-clamp`) ya da
//      `overflow:hidden` bir atasında kesilmez (CLAUDE.md "Names and long text are never clipped").
// API tamamen mock'lu (`mockApi`, küresel yönetici). Sekme listesi responsive.spec.js `TABS` ile aynıdır — yeni sekme
// eklendiğinde ikisine birden eklenir.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { mockOncallRoutinesMinimal } from './support/oncallRoutineMocks.js'

/** responsive.spec.js `TABS` ile aynı (App.jsx VALID_TABS). */
const TABS = [
  'dashboard', 'mydash', 'all', 'domains', 'manualcerts', 'forecast', 'renewal', 'renewal-guide',
  'warnings', 'incidents', 'maintenance', 'alerthistory', 'noc', 'stats', 'weakalgo', 'dataquality', 'weeklyreports', 'incident-history', 'executive', 'channelreports', 'oncallroutines',
  'health', 'uptime', 'monitoring', 'status', 'storms', 'http', 'domain', 'port', 'dns', 'keyword', 'ping', 'page', 'pagespeed', 'scripted', 'dbquery',
  'activity', 'myactivity', 'system', 'monitorchanges',
  'admin', 'permissions', 'sqlplayground', 'login-issues', 'help', 'settings',
]

/** Tablo ağırlıklı sekmeler — geniş masaüstünde (1920×1080) de ölçülür: tablolar en geniş hâllerine açılır. */
const TABLE_TABS = [
  'all', 'domains', 'manualcerts', 'renewal', 'incidents', 'maintenance', 'alerthistory', 'noc', 'stats', 'dataquality',
  'incident-history', 'monitoring', 'storms', 'activity', 'system', 'monitorchanges', 'admin', 'permissions',
  'sqlplayground', 'login-issues', 'weeklyreports',
]

const VIEWPORTS = [
  { name: 'laptop', width: 1280, height: 900, tabs: TABS },
  { name: 'desktop', width: 1920, height: 1080, tabs: TABS.filter((t) => TABLE_TABS.includes(t)) },
]

/**
 * Tarayıcıda: { pageOverflow, offenders[], headings[], headingCount } — sayfa taşması, görünüm alanının sağına taşan en
 * dıştaki görünür öğeler ve kırpılan başlıklar.
 */
function measure() {
  const vw = document.documentElement.clientWidth
  const root = document.querySelector('.app-main') || document.body
  const pageOverflow = Math.max(...[document.documentElement, document.body, root].map((el) => el.scrollWidth - el.clientWidth))
  const cs = (el) => getComputedStyle(el)
  const visible = (el) => {
    const r = el.getBoundingClientRect()
    if (r.width <= 1 || r.height <= 1) return false
    const s = cs(el)
    if (s.visibility === 'hidden' || s.display === 'none' || Number(s.opacity) === 0) return false
    return !el.closest('.sr-only, [aria-hidden="true"], [hidden], [inert]')
  }
  const desc = (el) => {
    const slot = el.getAttribute('data-slot')
    const cls = typeof el.className === 'string' ? el.className.trim().split(/\s+/).slice(0, 3).join('.') : ''
    return `${el.tagName.toLowerCase()}${slot ? `[${slot}]` : ''}${cls ? '.' + cls : ''}`
  }

  const offenders = []
  const flagged = new Set()
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.right <= vw + 1 || !visible(el)) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (cs(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (contained) continue
    flagged.add(el)
    if (el.parentElement && flagged.has(el.parentElement)) continue   // yalnız en dıştaki
    offenders.push(`${desc(el)} right=${Math.round(r.right)} "${(el.textContent || '').trim().slice(0, 40)}"`)
    if (offenders.length >= 8) break
  }

  // Başlıklar: metin düğümünün kutusu atanın gizleyen kenarını aşıyor mu, ya da kendi kutusunda kesiliyor mu.
  const textRect = (el) => {
    let rr = null
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
      if (!n.textContent.trim()) continue
      const range = document.createRange(); range.selectNodeContents(n)
      for (const q of range.getClientRects()) {
        if (q.width < 1) continue
        rr = rr ? { left: Math.min(rr.left, q.left), right: Math.max(rr.right, q.right) } : { left: q.left, right: q.right }
      }
    }
    return rr
  }
  const HEADINGS = 'h1, h2, h3, h4, [data-slot="page-title"], [data-slot="card-title"], [data-slot="dialog-title"], [data-slot="sheet-title"]'
  const headings = []
  let headingCount = 0
  for (const el of root.querySelectorAll(HEADINGS)) {
    if (!visible(el) || el.textContent.trim().length < 2 || el.closest('pre, code, textarea, svg, .cm-editor, .recharts-wrapper')) continue
    headingCount++
    const s = cs(el)
    let why = null
    if (el.scrollWidth > el.clientWidth + 1 && (s.overflowX === 'hidden' || s.overflowX === 'clip' || s.textOverflow === 'ellipsis')) why = 'own box'
    else if (s.webkitLineClamp && s.webkitLineClamp !== 'none' && el.scrollHeight > el.clientHeight + 1) why = 'line-clamp'
    else {
      // İç öğe (span) kendi kutusunda kesiliyor mu (`truncate` başlığın çocuğunda)
      for (const child of el.querySelectorAll('*')) {
        const c = cs(child)
        if (!visible(child)) continue
        if (child.scrollWidth > child.clientWidth + 1 && (c.overflowX === 'hidden' || c.overflowX === 'clip' || c.textOverflow === 'ellipsis')) { why = `child ${desc(child)}`; break }
      }
      const tr = !why && textRect(el)
      for (let p = el.parentElement; tr && p && p !== document.body; p = p.parentElement) {
        const ps = cs(p)
        if (ps.overflowX === 'auto' || ps.overflowX === 'scroll') break
        const pr = p.getBoundingClientRect()
        if ((ps.overflowX === 'hidden' || ps.overflowX === 'clip') && (tr.right > pr.right + 1.5 || tr.left < pr.left - 1.5)) { why = `ancestor ${desc(p)}`; break }
      }
    }
    if (why) headings.push(`${desc(el)} [${why}] "${el.textContent.trim().slice(0, 60)}"`)
    if (headings.length >= 10) break
  }
  return { vw, pageOverflow, offenders, headings, headingCount }
}

for (const vp of VIEWPORTS) {
  test.describe(`desktop layout — ${vp.name} ${vp.width}×${vp.height}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height } })

    for (const tab of vp.tabs) {
      test(`${tab}`, async ({ page }) => {
        await mockApi(page)
        if (tab === 'oncallroutines') await mockOncallRoutinesMinimal(page)
        await page.goto(`/?tab=${tab}`)
        await page.locator('.app-main').waitFor({ timeout: 20_000 })
        // Tembel yüklenen sekme parçası gelene dek ölçülmez: soğuk geliştirme sunucusunda 1,2 sn yetmez ve "Yükleniyor"
        // ekranı ölçülürdü (boş ekran taşmayı kanıtlamaz). Sekmenin kendi yükleme blokları da biter.
        await expect(page.locator('.app-main [data-slot="loading-block"]'), `${tab}: still loading`).toHaveCount(0, { timeout: 30_000 })
        await page.waitForTimeout(800)
        await expect(page.locator('.app-main [data-slot="loading-block"]'), `${tab}: still loading`).toHaveCount(0, { timeout: 15_000 })
        // Sonlu animasyonlar biter.
        await page.evaluate(() => Promise.all(document.getAnimations()
          .filter((a) => a.effect?.getComputedTiming().endTime !== Infinity).map((a) => a.finished.catch(() => null))))
        const m = await page.evaluate(measure)
        const key = `${tab}@${vp.name}`
        expect(m.vw, `${key}: viewport`).toBeGreaterThanOrEqual(vp.width - 20)
        expect(m.offenders, `${key}: visible element outside the viewport`).toEqual([])
        expect(m.pageOverflow, `${key}: page scrolls horizontally (px)`).toBeLessThanOrEqual(1)
        expect(m.headings, `${key}: clipped heading`).toEqual([])
        // Bilgi: ölçülen başlık sayısı (izin gerektiren sekme mock'ta "Erişim yok" çizer — başlıksız olabilir).
        test.info().annotations.push({ type: 'headings', description: `${key}: ${m.headingCount}` })
      })
    }
  })
}
