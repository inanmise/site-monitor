// MOBİL WEB SÜPÜRMESİ — dokunmatik telefon (390×844) ve dokunmatik tablet (768×1024).
//
// responsive.spec.js her sekmede SAYFA TAŞMASINI ölçer ama fareli tarayıcıda: dokunma hedefi (RESPONSIVE.md §4, ≥ 40 px)
// ve kırpılan ad / uzun metin (CLAUDE.md "Names and long text are never clipped") orada görünmez. 2026-11-05 mweb
// denetimi bu iki sınıfta 20'den fazla bulgu çıkardı — çoğu izleme DETAY penceresinde, izleme FORMLARINDA ve dokunmatik
// tablette (768 px'te `sm:h-9` kalıbı 36 px'e düşüyordu). Bu dosya o ekranları `hasTouch` + `isMobile` ile açar
// (`pointer: coarse` gerçekten eşleşir) ve üç şeyi ölçer:
//   1) taşma — sayfa yatay kaymaz, görünür öğe ekran dışına çıkmaz (kendi kaydırma kabında kalan muaf);
//   2) kırpılma — doğrudan metin taşıyan görünür öğe kendi kutusunda (`truncate` / `line-clamp`) ya da `overflow:hidden`
//      bir atasında kesilmez;
//   3) dokunma hedefi — düğme / bağlantı / sekme / anahtar / girdi en az 40 px (görünmez `::after` vuruş alanı, anahtarın
//      etiket satırı ve giriş grubunun kutusu sayılır; `pointer-events:none` olanlar — yoğunluk şeridi hücreleri — hedef değil).
//
// İZİNLİ İSTİSNALAR (TASARIM GEREĞİ, gerekçeli — liste yalnız küçülür): aşağıdaki ALLOW_CLIP. Yeni bir kırpma eklemek yerine
// metni sar (`[overflow-wrap:anywhere]`).
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const VIEWPORTS = [
  { name: 'telefon', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
]

/**
 * Bilinçli kırpmalar — `data-slot` / sınıf eşlemesi → gerekçe.
 *  - chkfail-oneline / kwfail-oneline: geçmiş satırının TEK SATIRLIK hata özeti; satıra dokununca tam teşhis paneli açılır.
 *  - upt-rt-error: sentetik hata metni 1500 karaktere kadar çıkabilir (App.css "KIRPMA ŞART"); tam metin satır panelinde.
 *  - change-chips: değişiklik tablosu hücresi çipi (önce → sonra) masaüstünde tek satır; satır "Ayrıntı"sı tam farkı açar.
 */
const ALLOW_CLIP = [/\[chkfail-oneline\]/, /\[kwfail-oneline\]/, /\.upt-rt-error/, /change-chips>badge/]

const settle = (page) => page.evaluate(() => Promise.all(document.getAnimations()
  .filter((a) => a.effect?.getComputedTiming().endTime !== Infinity).map((a) => a.finished.catch(() => null))))

/** Tarayıcıda: { pageOverflow, offscreen[], clipped[], small[] } — `rootSel` son eşleşen kök (açık pencere), yoksa `.app-main`. */
function audit(rootSel) {
  const vw = document.documentElement.clientWidth
  const root = (rootSel && [...document.querySelectorAll(rootSel)].pop()) || document.querySelector('.app-main') || document.body
  const cs = (el) => getComputedStyle(el)
  const visible = (el) => {
    const r = el.getBoundingClientRect()
    if (r.width <= 1.5 || r.height <= 1.5) return false
    const s = cs(el)
    if (s.visibility === 'hidden' || s.display === 'none' || Number(s.opacity) === 0 || (s.clip && s.clip !== 'auto')) return false
    return !el.closest('.sr-only, [aria-hidden="true"], [hidden], [inert]')
  }
  const desc = (el) => {
    const slot = el.getAttribute('data-slot')
    const cls = typeof el.className === 'string' ? el.className.trim().split(/\s+/).slice(0, 4).join('.') : ''
    const path = []
    for (let p = el, i = 0; p && i < 5; p = p.parentElement, i++) { const s = p.getAttribute?.('data-slot'); if (s) path.unshift(s) }
    return `${el.tagName.toLowerCase()}${slot ? `[${slot}]` : ''}${cls ? '.' + cls : ''} @${path.join('>')}`
  }
  const pageOverflow = Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth,
    document.body.scrollWidth - document.body.clientWidth, root.scrollWidth - root.clientWidth)

  const offscreen = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if ((r.right <= vw + 1 && r.left >= -1) || !visible(el)) continue
    let contained = false
    for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
      if (cs(p).overflowX !== 'visible') { const pr = p.getBoundingClientRect(); if (pr.right <= vw + 1 && pr.left >= -1) { contained = true; break } }
    }
    if (!contained) offscreen.push(`${desc(el)} ${Math.round(r.left)}..${Math.round(r.right)}`)
    if (offscreen.length >= 10) break
  }

  const textRect = (el) => {
    let rr = null
    for (const n of el.childNodes) {
      if (n.nodeType !== 3 || !n.textContent.trim()) continue
      const range = document.createRange(); range.selectNodeContents(n)
      for (const q of range.getClientRects()) {
        if (q.width < 1) continue
        rr = rr ? { left: Math.min(rr.left, q.left), right: Math.max(rr.right, q.right), bottom: Math.max(rr.bottom, q.bottom) } : { left: q.left, right: q.right, bottom: q.bottom }
      }
    }
    return rr
  }
  const clipped = []
  for (const el of root.querySelectorAll('*')) {
    if (el instanceof SVGElement || el.closest('pre, code, textarea, select, svg, input, .cm-editor, .recharts-wrapper')) continue
    if (![...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim().length > 2) || !visible(el)) continue
    const s = cs(el)
    let why = null
    if (el.scrollWidth > el.clientWidth + 1 && (s.overflowX === 'hidden' || s.overflowX === 'clip' || s.textOverflow === 'ellipsis')) why = 'kendi kutusunda'
    else if (s.webkitLineClamp && s.webkitLineClamp !== 'none' && el.scrollHeight > el.clientHeight + 1) why = 'line-clamp'
    else {
      const tr = textRect(el)
      for (let p = el.parentElement; tr && p && p !== document.body; p = p.parentElement) {
        const ps = cs(p)
        if (ps.overflowX === 'auto' || ps.overflowX === 'scroll') break
        const pr = p.getBoundingClientRect()
        if ((ps.overflowX === 'hidden' || ps.overflowX === 'clip') && (tr.right > pr.right + 1.5 || tr.left < pr.left - 1.5)) { why = `atada (${desc(p)})`; break }
      }
    }
    if (why) clipped.push(`${desc(el)} [${why}] "${el.textContent.trim().slice(0, 50)}"`)
    if (clipped.length >= 20) break
  }

  const small = []
  if (matchMedia('(pointer: coarse)').matches) {
    const sel = 'button, a[href], [role="button"], [role="tab"], [role="switch"], [role="checkbox"], [role="radio"], [role="menuitem"], [role="option"], [role="combobox"], select, input:not([type="hidden"]), summary'
    for (const b of root.querySelectorAll(sel)) {
      if (b.disabled || b.getAttribute('aria-disabled') === 'true' || !visible(b) || cs(b).pointerEvents === 'none') continue
      const r = b.getBoundingClientRect()
      const s = cs(b)
      // Metin içi bağlantı (paragrafın akışında): WCAG 2.5.8 istisnası
      if (b.tagName === 'A' && s.display === 'inline' && /^(P|LI|SPAN|TD|DD)$/.test(b.parentElement?.tagName || '')) continue
      let h = r.height
      let w = r.width
      const role = b.getAttribute('role')
      const type = b.getAttribute('type')
      if (['switch', 'checkbox', 'radio'].includes(role) || type === 'checkbox' || type === 'radio') {
        const lab = b.closest('label') || (b.id && document.querySelector(`label[for="${CSS.escape(b.id)}"]`))
        for (const row of [lab, b.parentElement].filter(Boolean)) { const rr = row.getBoundingClientRect(); h = Math.max(h, rr.height); w = Math.max(w, rr.width) }
      }
      if (b.getAttribute('data-slot') === 'input-group-control') { const g = b.closest('[data-slot="input-group"]'); if (g) h = Math.max(h, g.getBoundingClientRect().height) }
      const af = getComputedStyle(b, '::after')
      if (af.position === 'absolute' && af.content !== 'none') {
        const ah = parseFloat(af.height); const aw = parseFloat(af.width)
        if (ah > 0) h = Math.max(h, ah)
        if (aw > 0) w = Math.max(w, aw)
        h = Math.max(h, r.height - Math.min(0, parseFloat(af.top) || 0) - Math.min(0, parseFloat(af.bottom) || 0))
      }
      const iconOnly = !(b.textContent || '').trim()
      if (h < 39.5 || (iconOnly && w < 39.5)) {
        small.push(`${desc(b)} ${Math.round(w)}×${Math.round(h)} "${(b.getAttribute('aria-label') || b.textContent || b.getAttribute('placeholder') || '').trim().slice(0, 30)}"`)
      }
      if (small.length >= 20) break
    }
  }
  return { pageOverflow, offscreen, clipped, small }
}

async function check(page, label, rootSel) {
  await settle(page)
  const m = await page.evaluate(audit, rootSel)
  const clipped = m.clipped.filter((c) => !ALLOW_CLIP.some((re) => re.test(c)))
  expect(m.pageOverflow, `${label}: sayfa yatay taşıyor (px)`).toBeLessThanOrEqual(1)
  expect(m.offscreen, `${label}: görünür öğe ekran dışına çıkıyor`).toEqual([])
  expect(clipped, `${label}: ad / uzun metin kırpılıyor`).toEqual([])
  expect(m.small, `${label}: dokunma hedefi 40 px'ten küçük`).toEqual([])
}

const DETAIL = ['http', 'keyword', 'page', 'pagespeed', 'domain', 'ping', 'port', 'dns', 'scripted', 'dbquery', 'uptime']
const FORMS = ['http', 'keyword', 'page', 'pagespeed', 'domain', 'ping', 'port', 'dns', 'scripted', 'dbquery']
const PAGES = [
  { key: 'pano', url: '/?tab=dashboard' },
  { key: 'dikkat', url: '/?tab=warnings' },
  { key: 'yetki matrisi', url: '/?tab=permissions' },
  { key: 'yenileme kılavuzu', url: '/?tab=renewal-guide' },
  { key: 'eskalasyon kişileri', url: '/?tab=admin&g_tab=contacts' },
]

for (const vp of VIEWPORTS) {
  test.describe(`mweb süpürmesi — ${vp.name} ${vp.width}×${vp.height} (dokunmatik)`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: true, isMobile: true })

    for (const pg of PAGES) {
      test(`sayfa: ${pg.key}`, async ({ page }) => {
        await mockApi(page)
        await page.goto(pg.url)
        await page.locator('.app-main').waitFor({ timeout: 20_000 })
        await page.waitForTimeout(900)
        await check(page, `${pg.key}@${vp.name}`)
      })
    }

    test('Kişisel Panom düzenleme kipi — üst eylemler sarar, ekranın soluna taşmaz', async ({ page }) => {
      await mockApi(page)
      await page.goto('/?tab=mydash')
      await page.locator('[data-slot="mydash-edit"]').click({ timeout: 20_000 })
      await expect(page.locator('[data-slot="mydash-add"]')).toBeVisible()
      await check(page, `panom düzenleme@${vp.name}`)
      const main = await page.locator('[data-slot="sidebar-inset"]').boundingBox()
      for (const slot of ['mydash-refresh', 'mydash-add', 'mydash-reset', 'mydash-edit', 'mydash-tv-open']) {
        const box = await page.locator(`[data-slot="${slot}"]`).boundingBox()
        expect(box.x, `${slot}: ana alanın solunda`).toBeGreaterThanOrEqual(main.x - 1)
        expect(box.x + box.width, `${slot}: ana alanın sağında`).toBeLessThanOrEqual(main.x + main.width + 1)
      }
    })

    test('envanter formu (Domain Ekle)', async ({ page }) => {
      await mockApi(page)
      await page.goto('/?tab=domains')
      await page.locator('[data-slot="add-cert-split"] button').first().click({ timeout: 20_000 })
      await page.getByRole('dialog').waitFor()
      await page.waitForTimeout(500)
      await check(page, `envanter formu@${vp.name}`, '[role="dialog"]')
    })

    for (const type of DETAIL) {
      test(`detay penceresi: ${type} (tüm sekmeler)`, async ({ page }) => {
        test.setTimeout(90_000)
        await mockApi(page)
        await page.goto(`/?tab=${type}`)
        await page.locator('.upt-grid [data-slot="card"] [data-monitor-open]').first().click({ timeout: 20_000 })
        const dlg = page.getByRole('dialog').first()
        await dlg.waitFor()
        await page.waitForTimeout(500)
        await check(page, `${type} detay@${vp.name}`, '[role="dialog"]')
        const tabs = dlg.locator('[data-slot="detail-tabs-list"] [role="tab"]')
        const n = await tabs.count()
        for (let i = 1; i < n; i++) {
          const tab = tabs.nth(i)
          const name = ((await tab.getAttribute('aria-controls')) || String(i))
          await tab.click()
          await page.waitForTimeout(250)
          await check(page, `${type} detay / ${name}@${vp.name}`, '[role="dialog"]')
        }
      })
    }

    for (const type of FORMS) {
      test(`yeni izleme formu: ${type}`, async ({ page }) => {
        await mockApi(page)
        await page.goto(`/?tab=${type}`)
        await page.locator('[data-tour="mon-new"]').first().click({ timeout: 20_000 })
        await page.getByRole('dialog').waitFor()
        await page.waitForTimeout(400)
        await check(page, `${type} formu@${vp.name}`, '[role="dialog"]')
      })
    }
  })
}

// Dokunmatik tablette (≥ 640 px) sayfa başlığının "Görünümler" menüsü açılır: öğeler 40 px (DropdownMenuItem tabanı).
test.describe('mweb süpürmesi — tablet menü öğeleri', () => {
  test.use({ viewport: { width: 768, height: 1024 }, hasTouch: true, isMobile: true })
  test('kayıtlı görünümler menüsü', async ({ page }) => {
    await mockApi(page)
    await page.goto('/?tab=http')
    await page.locator('[data-slot="page-actions"] [aria-haspopup="menu"]').last().click({ timeout: 20_000 })
    await page.getByRole('menu').waitFor()
    await check(page, 'görünümler menüsü@tablet', '[role="menu"]')
  })
})
