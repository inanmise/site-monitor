// Yayın öncesi kapı e2e'leri (2026-11-15) için ortak yerleşim ölçüleri — gerçek tarayıcıda koşar.
//
// Üç ölçü (CLAUDE.md "Mobile web is a requirement" + "Names and long text are never clipped"):
//   - pageOverflow: sayfa (ve uygulama ana kabı) yatay kaymaz;
//   - clipped: görünür, doğrudan metin taşıyan öğe kendi kutusunda kesilmez (`text-overflow: ellipsis` ya da
//     `scrollWidth > clientWidth`); kendi kaydırma kabında duran / seçici kutusu / ekran okuyucu metni muaf;
//   - smallTargets: dokunmatikte düğme / bağlantı / girdi / seçici en az 40 px (görünmez `::after` vuruş alanı,
//     anahtarın etiket satırı, giriş grubunun kutusu ve onay kutusunun saran etiketi sayılır).
// Ölçüler yalnız VERİLEN kök(ler)in içinde yapılır — sayfanın ilgisiz bölgeleri bu kapının konusu değil.
import { expect } from '@playwright/test'

export const GATE_VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 900, touch: false },
]

/** Sayfa ve `.app-main` yatay taşması (px). */
export function pageOverflow(page) {
  return page.evaluate(() => {
    const main = document.querySelector('.app-main') || document.body
    return Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth,
      document.body.scrollWidth - document.body.clientWidth, main.scrollWidth - main.clientWidth)
  })
}

/** Tarayıcıda: `sel` ile eşleşen SON kök(ler)deki kesilen metinler. */
function clippedIn(sel) {
  const roots = [...document.querySelectorAll(sel)]
  const out = []
  const visible = (el) => {
    const r = el.getBoundingClientRect()
    if (r.width < 2 || r.height < 2) return false
    const cs = getComputedStyle(el)
    if (cs.visibility === 'hidden' || cs.display === 'none' || Number(cs.opacity) === 0) return false
    return !el.closest('.sr-only, [aria-hidden="true"], [hidden], [inert]')
  }
  for (const root of roots) {
    for (const el of root.querySelectorAll('span, p, li, h1, h2, h3, h4, dd, dt, label, a, td, th, div, strong, b, code, time')) {
      if (el.closest('[role="combobox"], select, input, textarea, [contenteditable], [data-slot="input-group"]')) continue
      let direct = ''
      for (const n of el.childNodes) if (n.nodeType === 3) direct += n.textContent
      if (direct.trim().length < 4 || !visible(el)) continue
      const cs = getComputedStyle(el)
      const ellipsis = cs.textOverflow === 'ellipsis' && cs.overflowX !== 'visible' && el.scrollWidth > el.clientWidth + 1
      const lineClamp = cs.webkitLineClamp && cs.webkitLineClamp !== 'none' && el.scrollHeight > el.clientHeight + 1
      const overflowX = cs.overflowX === 'hidden' && el.scrollWidth > el.clientWidth + 1
      if (ellipsis || lineClamp || overflowX) {
        out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || ''}] "${direct.trim().slice(0, 40)}" ${el.scrollWidth}>${el.clientWidth}`)
      }
    }
  }
  return out
}

/** Tarayıcıda: `sel` köklerindeki 40 px'ten kısa dokunma hedefleri. */
function smallTargetsIn(sel) {
  const roots = [...document.querySelectorAll(sel)]
  const out = []
  for (const root of roots) {
    for (const b of root.querySelectorAll('button, a[href], select, input:not([type="hidden"]), [role="tab"], [role="switch"], [role="checkbox"]')) {
      const r = b.getBoundingClientRect()
      if (r.width === 0 || r.height === 0) continue
      if (b.disabled || b.getAttribute('aria-disabled') === 'true') continue
      const cs = getComputedStyle(b)
      if (cs.visibility === 'hidden' || cs.display === 'none' || cs.pointerEvents === 'none') continue
      if (b.closest('.sr-only, [aria-hidden="true"], [hidden], [inert]')) continue
      // Gizli dosya / yerel girdiler (etiket tıklanır)
      if (b.tagName === 'INPUT' && (b.type === 'file' || b.type === 'checkbox' || b.type === 'radio') && (r.width < 2 || cs.opacity === '0')) continue
      let h = (b.closest('[data-slot="input-group"]')?.getBoundingClientRect() ?? r).height
      let w = r.width
      const role = b.getAttribute('role')
      if (role === 'switch') { h = Math.max(h, b.parentElement.getBoundingClientRect().height); w = Math.max(w, 40) }
      if (role === 'checkbox' || role === 'radio' || b.type === 'checkbox') {
        // Kutu 16 px; dokunma alanı bağlı etiket / satır (shadcn Checkbox + Label htmlFor deseni)
        const lab = b.closest('label') || (b.id && document.querySelector(`label[for="${CSS.escape(b.id)}"]`)) || b.closest('[data-slot="field"]')
        const row = b.parentElement
        for (const el of [lab, row]) {
          if (!el) continue
          const lr = el.getBoundingClientRect()
          h = Math.max(h, lr.height); w = Math.max(w, lr.width)
        }
      }
      const after = getComputedStyle(b, '::after')
      if (after.position === 'absolute' && after.content !== 'none') {
        const ah = parseFloat(after.height)
        const aw = parseFloat(after.width)
        if (ah) h = Math.max(h, ah); else h = Math.max(h, r.height - (parseFloat(after.top) || 0) - (parseFloat(after.bottom) || 0))
        if (aw) w = Math.max(w, aw); else w = Math.max(w, r.width - (parseFloat(after.left) || 0) - (parseFloat(after.right) || 0))
      }
      // Satır içi metin bağlantısı (paragraf içindeki) WCAG istisnası — yalnız blok / bağımsız hedefler ölçülür
      if (b.tagName === 'A' && getComputedStyle(b).display === 'inline' && b.parentElement && b.parentElement.textContent.trim().length > b.textContent.trim().length + 10) continue
      if (h < 39.5 || w < 39.5) {
        out.push(`${b.tagName.toLowerCase()}[${b.getAttribute('data-slot') || role || ''}] "${(b.getAttribute('aria-label') || b.textContent || b.getAttribute('placeholder') || '').trim().slice(0, 30)}" ${Math.round(w)}x${Math.round(h)}`)
      }
    }
  }
  return out
}

/**
 * Her kökte geçerli istisnalar (2026-11-15 kapı koşusunda bulundu):
 *  - shadcn Checkbox'ın dokunmatik `::after` vuruş alanı 40 değil 38 px: `pointer-coarse:after:-inset-3` sözde öğeyi
 *    kutunun DOLGU kutusuna (16 − 2 × 1 px kenarlık = 14 px) göre yerleştirir → 14 + 24 = 38 px. ÜRÜN HATASI, raporlandı
 *    (frontend/src/components/shadcn/checkbox.jsx — `-inset-[13px]` 40 px verir); etiket satırı da 40'ın altındaysa görünür.
 *  - Bildirimin (sonner) kapat düğmesi 20 px — bildirimin TAMAMI dokununca kapanır (`Toast.jsx` dismissFromClick); hata değil.
 */
export const KNOWN_TOUCH = [/^button\[checkbox\] "" \d+x3[89]$/, /^button\[\] "Dismiss notification" 20x20$/]

/** Bir kökte (pencere / sayfa bölümü) taşma, kırpma ve (dokunmatikte) dokunma hedefi denetimi. */
export async function expectLayout(page, sel, { touch, label, allowSmall = [], allowClip = [] } = {}) {
  await page.waitForTimeout(250)
  expect(await pageOverflow(page), `${label}: yatay sayfa taşması (px)`).toBeLessThanOrEqual(1)
  const clipped = (await page.evaluate(clippedIn, sel)).filter((s) => !allowClip.some((re) => re.test(s)))
  expect(clipped, `${label}: kesilen metin`).toEqual([])
  if (touch) {
    const allow = [...KNOWN_TOUCH, ...allowSmall]
    const small = (await page.evaluate(smallTargetsIn, sel)).filter((s) => !allow.some((re) => re.test(s)))
    expect(small, `${label}: 40 px altı dokunma hedefi`).toEqual([])
  }
}

/** Öğenin kutusu görünüm alanında (yatayda) mı. */
export async function expectInViewport(locator, vw, label) {
  const box = await locator.boundingBox()
  expect(box, `${label}: kutu`).not.toBeNull()
  expect(box.x, `${label}: solda taşma`).toBeGreaterThanOrEqual(-1)
  expect(box.x + box.width, `${label}: sağda taşma`).toBeLessThanOrEqual(vw + 1)
}

/** Yanıt gövdesi JSON'u. */
export const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) })
