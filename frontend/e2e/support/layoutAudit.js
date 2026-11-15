// Ortak yerleşim denetimi (2026-11-15, prod kapısı e2e paketi) — mweb-sweep.spec.js'teki `audit` ölçüsünün paylaşılan
// kopyası: yeni spesifikasyonlar telefon (390, dokunmatik), tablet (768, dokunmatik) ve dizüstünde (1280) aynı üç şeyi
// ölçer:
//   1) taşma — sayfa yatay kaymaz, görünür öğe ekran dışına çıkmaz (kendi kaydırma kabında kalan muaf);
//   2) kırpılma — doğrudan metin taşıyan görünür öğe kendi kutusunda (`truncate` / `line-clamp`) ya da `overflow:hidden`
//      bir atasında kesilmez (CLAUDE.md "Names and long text are never clipped");
//   3) dokunma hedefi — yalnız `pointer: coarse` iken: düğme / bağlantı / sekme / anahtar / girdi en az 40 px.
// Mevcut spesifikasyonlar bu dosyayı kullanmaz (onlara dokunulmadı); yalnız eklenen.
import { expect } from '@playwright/test'

export const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 900, touch: false },
]

/** JSON yanıtı veren route işleyicisi. */
export const json = (body, status = 200, headers = {}) => (route) => route.fulfill({
  status, contentType: 'application/json', headers, body: JSON.stringify(body),
})

/** Yolun tam eşleşmesi (sorgu dizgisi yok sayılır). */
export const pathIs = (path) => (u) => new URL(u).pathname === path

/** Sonlu animasyonlar bitsin (açılış saydamlık geçişi vb.). */
export const settle = (page) => page.evaluate(() => Promise.all(document.getAnimations()
  .filter((a) => a.effect?.getComputedTiming().endTime !== Infinity).map((a) => a.finished.catch(() => null))))

/** Tarayıcıda: { pageOverflow, offscreen[], clipped[], small[] } — `rootSel` son eşleşen kök, yoksa `.app-main`. */
export function audit(rootSel) {
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
    document.body.scrollWidth - document.body.clientWidth)

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
    if (el.scrollWidth > el.clientWidth + 1 && (s.overflowX === 'hidden' || s.overflowX === 'clip' || s.textOverflow === 'ellipsis')) why = 'own box'
    else if (s.webkitLineClamp && s.webkitLineClamp !== 'none' && el.scrollHeight > el.clientHeight + 1) why = 'line-clamp'
    else {
      const tr = textRect(el)
      for (let p = el.parentElement; tr && p && p !== document.body; p = p.parentElement) {
        const ps = cs(p)
        if (ps.overflowX === 'auto' || ps.overflowX === 'scroll') break
        const pr = p.getBoundingClientRect()
        if ((ps.overflowX === 'hidden' || ps.overflowX === 'clip') && (tr.right > pr.right + 1.5 || tr.left < pr.left - 1.5)) { why = `ancestor (${desc(p)})`; break }
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

/**
 * Yerleşim kapısı: sayfa taşması ≤ 1 px, ekran dışı öğe yok, kırpılan metin yok, (dokunmatikte) 40 px altı hedef yok.
 * @param opts.allowClip  bilinçli kırpmalar için düzenli ifadeler (gerekçesi spesifikasyonda yazılı olmalı)
 * @param opts.allowSmall bilinçli küçük hedefler için düzenli ifadeler
 */
export async function checkLayout(page, label, rootSel, opts = {}) {
  await settle(page)
  const m = await page.evaluate(audit, rootSel)
  const clipped = m.clipped.filter((c) => !(opts.allowClip || []).some((re) => re.test(c)))
  const small = m.small.filter((c) => !(opts.allowSmall || []).some((re) => re.test(c)))
  expect(m.pageOverflow, `${label}: page scrolls horizontally (px)`).toBeLessThanOrEqual(1)
  expect(m.offscreen, `${label}: visible element outside the viewport`).toEqual([])
  expect(clipped, `${label}: clipped name / long text`).toEqual([])
  expect(small, `${label}: touch target under 40 px`).toEqual([])
}

/** Öğenin (pencere / menü) tamamı görünüm alanında mı. */
export async function expectInViewport(locator, vp, label = '') {
  const box = await locator.boundingBox()
  expect(box, `${label}: no box`).not.toBeNull()
  expect(box.x, `${label}: left`).toBeGreaterThanOrEqual(-1)
  expect(box.y, `${label}: top`).toBeGreaterThanOrEqual(-1)
  expect(box.x + box.width, `${label}: right`).toBeLessThanOrEqual(vp.width + 1)
  expect(box.y + box.height, `${label}: bottom`).toBeLessThanOrEqual(vp.height + 1)
}

/** İstek kaydı — `page.on('request')` ile tüm /api/ çağrıları (yöntem + yol + sorgu + gövde). */
export function recordRequests(page) {
  const log = []
  page.on('request', (req) => {
    const u = new URL(req.url())
    if (!u.pathname.startsWith('/api/')) return
    let body = null
    try { body = req.postData() ? JSON.parse(req.postData()) : null } catch { body = req.postData() }
    log.push({ method: req.method(), path: u.pathname, search: u.search, body })
  })
  return log
}
