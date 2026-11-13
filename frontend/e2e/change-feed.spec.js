import { test, expect } from '@playwright/test'

/**
 * Yönetim → Denetim → Değişiklik akışı (2026-11-13, zenginleştirme #2) — mweb. İzole harness
 * (e2e/harness/change-feed.html) gerçek AdminChangeFeed'i sabit veriyle (ağ yok) mount eder. Üç boyutta: yatay taşma
 * yok; 1280'de tablo, 390 / 768'de kart; uzun takım / grup adları kırpılmaz; alan çipi listeyi süzer; satır açılımı
 * (fark tablosu / JSON anlık görüntü) ekrana sığar; dokunmatikte hedefler ≥ 40 px.
 */
const VIEWPORTS = [
  { name: 'phone',  width: 390,  height: 844,  touch: true,  layout: 'cards' },
  { name: 'tablet', width: 768,  height: 1024, touch: true,  layout: 'cards' },
  { name: 'laptop', width: 1280, height: 800,  touch: false, layout: 'table' },
]

async function overflow(page) {
  return page.evaluate(() => {
    const el = document.documentElement
    const sec = document.querySelector('[data-slot="change-feed"]')
    return Math.max(el.scrollWidth - el.clientWidth, sec ? sec.scrollWidth - sec.clientWidth : 0)
  })
}

/** Kesilmiş metin: kendi kutusuna sığmayan (kaydırma genişliği taşan) metin öğeleri. */
async function clipped(root) {
  return root.evaluate((el) => {
    const out = []
    for (const n of el.querySelectorAll('span, td, li, time, button')) {
      const cs = getComputedStyle(n)
      if (cs.display === 'none' || n.closest('.sr-only')) continue
      // Özet çipleri (ChangeChipList) uzun DEĞERİ bilinçli tek satıra kısaltır — tamamı ipucunda ve satır açılımındaki
      // fark tablosunda (aşağıda o tablonun tam değeri gösterdiği ayrıca denetlenir). Diğer geçmiş ekranlarıyla aynı.
      if (n.closest('[data-slot="change-chips"]')) continue
      if (n.scrollWidth > n.clientWidth + 1 && cs.overflow !== 'visible' && cs.overflowX !== 'visible') {
        out.push(`${n.tagName.toLowerCase()}:${(n.textContent || '').trim().slice(0, 30)} ${n.scrollWidth}>${n.clientWidth}`)
      }
    }
    return out
  })
}

async function smallTargets(root) {
  return root.evaluate((el) => {
    const out = []
    for (const b of el.querySelectorAll('button, a[href], input:not([type="hidden"]), select, [role="button"]')) {
      const r = b.getBoundingClientRect()
      if (r.width === 0 || r.height === 0) continue
      const cs = getComputedStyle(b)
      if (cs.visibility === 'hidden' || cs.display === 'none') continue
      if (b.closest('[hidden], [aria-hidden="true"]')) continue
      // Görünmez ::after dokunma alanı (TeamBadge TEAM_BADGE_TOUCH kalıbı) hedefi büyütür — sayılır
      const af = getComputedStyle(b, '::after')
      let h = r.height
      if (af.content && af.content !== 'none' && af.position === 'absolute') {
        h = Math.max(h, r.height - Math.min(0, parseFloat(af.top) || 0) - Math.min(0, parseFloat(af.bottom) || 0))
      }
      if (h < 39.5) out.push(`${b.tagName.toLowerCase()}:${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 24)} ${Math.round(r.height)}`)
    }
    return out
  })
}

for (const vp of VIEWPORTS) {
  test(`değişiklik akışı (${vp.name} ${vp.width}): taşma yok, ${vp.layout}, alan süzgeci, açılım`, async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch })
    const page = await context.newPage()
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto('/e2e/harness/change-feed.html')

    const section = page.locator('[data-slot="change-feed"]')
    await expect(section).toBeVisible()
    await expect(section.locator('[data-slot="cf-row"]')).toHaveCount(4)
    await expect(section.locator('[data-slot="cf-area"]')).toHaveCount(8)

    // Düzen: geniş ekranda tablo, dar kapta kart
    if (vp.layout === 'table') await expect(section.locator('table')).toBeVisible()
    else await expect(section.locator('table')).toHaveCount(0)

    expect(await overflow(page), 'yatay taşma').toBeLessThanOrEqual(1)
    const cut = await clipped(section)
    expect(cut, `kırpılan metin:\n${cut.join('\n')}`).toEqual([])

    // Satır açılımı: fark tablosu (ilk satır) ekrana sığar
    const first = section.locator('[data-slot="cf-row"]').first()
    if (vp.layout === 'table') await first.click()
    else await first.getByRole('button').last().click()
    const det = section.locator('[data-slot="cf-detail"]')
    await expect(det).toBeVisible()
    await expect(det).toContainText('yeni.adres.cok.uzun.ornek@example.com.tr')
    expect(await overflow(page), 'açılım sonrası taşma').toBeLessThanOrEqual(1)

    // Dokunmatik boyutlarda hedefler ≥ 40 px
    if (vp.touch) {
      const small = await smallTargets(section)
      expect(small, `küçük dokunma hedefleri:\n${small.join('\n')}`).toEqual([])
    }

    // Alan süzgeci: yalnız alarm iletimi
    await section.locator('[data-slot="cf-area"][data-area="forwards"]').click()
    await expect(section.locator('[data-slot="cf-row"]')).toHaveCount(1)
    await expect(section.locator('[data-slot="cf-row"]').first()).toHaveAttribute('data-area', 'forwards')
    expect(await overflow(page), 'süzme sonrası taşma').toBeLessThanOrEqual(1)

    await context.close()
  })
}
