import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

// Kişisel Panom → "Yakında bitecek alan adları" kutusu TV kipinde (2026-11-17, inceleme: kutunun TV düzeni e2e'de yoktu;
// kutu artık hafif `?view=expiry` listesini okur). Telefon (390, dokunmatik), tablet (768, dokunmatik), dizüstü (1280),
// duvar ekranı (1920×1080): "renkli paneller" açıkken kutu TEK hücrelik renkli paneldir (liste çizilmez, sayı = eşikteki
// alan adı), "ayrıntılı" görünümde liste kutunun içinde kalır; hiçbir öğe kartından taşmaz, sayfa kaymaz, TV'de kutu
// tıklanmaz; istek hafif listeyi (`view=expiry`) ister. API mock'lu.
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true, minFit: 0.6 },
  { name: 'tablet', width: 768, height: 1024, touch: true, minFit: 0.85 },
  { name: 'laptop', width: 1280, height: 800, touch: false, minFit: 0.85 },
  { name: 'wall', width: 1920, height: 1080, touch: false, minFit: 0.85 },
]
const day = (offset) => { const d = new Date(); d.setDate(d.getDate() + offset); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
const LONG_NAME = 'Kurumsal İnternet Bankacılığı Kampanya Alan Adı (Bireysel ve Ticari — Canlı Ortam)'
const base = { team_id: 1, team_name: 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi', active: true,
  warning_days: 30, critical_days: 7 }
// Hafif liste satırları (MonitoringController.listDomainExpiry biçimi)
const ROWS = [
  { ...base, id: 1, name: LONG_NAME, domain: 'kampanya-bireysel-ticari-uzun-alt-alan-adi.example.com.tr', expiry_date: day(-2), days_remaining: -2 },
  { ...base, id: 2, name: 'example.com', domain: 'example.com', expiry_date: day(5), days_remaining: 5 },
  { ...base, id: 3, name: 'ornek.com.tr', domain: 'ornek.com.tr', expiry_date: day(20), days_remaining: 20 },
  { ...base, id: 4, name: 'kurumsal.example.org', domain: 'kurumsal.example.org', expiry_date: day(200), days_remaining: 200 },
  { ...base, id: 5, name: 'bilinmiyor.example.net', domain: 'bilinmiyor.example.net', expiry_date: null, days_remaining: null },
]

/** Etkin sayfanın her kartında kartın DIŞINA taşan görünür öğe + sayfa ölçeği (useFitScale) + kayma. */
function tvAudit() {
  const m = document.querySelector('[data-slot="mydash-tv"] main')
  const g = m.querySelector('[data-slot="mydash-tv-page"][data-active="true"]')
  const over = []
  for (const c of g.querySelectorAll('[data-slot="mydash-widget"] [data-slot="card"]')) {
    const cr = c.getBoundingClientRect()
    const bad = [...c.querySelectorAll('*')].filter((el) => {
      if (el.closest('.sr-only')) return false
      const b = el.getBoundingClientRect()
      return b.width > 0 && b.height > 0 && (b.bottom > cr.bottom + 1 || b.right > cr.right + 1 || b.top < cr.top - 1 || b.left < cr.left - 1)
    })
    if (bad.length) over.push(`${bad[0].tagName}.${bad[0].getAttribute('data-slot') || ''}`)
  }
  const clipped = [...g.querySelectorAll('span, p, li, button, h3')].filter((n) => !n.closest('.sr-only') && n.childElementCount === 0
    && n.textContent.trim().length > 8 && (getComputedStyle(n).textOverflow === 'ellipsis' || n.scrollWidth > n.clientWidth + 1))
    .map((n) => n.textContent.slice(0, 30))
  const pageBottom = g.getBoundingClientRect().bottom - m.getBoundingClientRect().bottom
  return { fit: Number(m.getAttribute('data-fit')), clippedPage: m.hasAttribute('data-clipped'), over, clipped, pageBottom,
    pageOverflowX: document.documentElement.scrollWidth - document.documentElement.clientWidth }
}

for (const vp of VIEWPORTS) {
  test.describe(`Panom TV — yakında bitecek alan adları ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('renkli panelde tek hücre (liste yok), ayrıntılıda liste kartın içinde; taşma / kırpılma / kayma yok', async ({ page }) => {
      test.setTimeout(120_000)
      await mockApi(page, { perms: { 'monitoring.read': { view: true } },
        prefs: { dashboard: { v: 1, widgets: [{ id: 'domain-expiring', type: 'domain_expiring', size: 'md' }] } } })
      const seen = []
      await page.route((u) => new URL(u).pathname === '/api/monitoring/domain', (route) => {
        seen.push(new URL(route.request().url()).searchParams.get('view'))
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: ROWS }) })
      })
      await page.addInitScript(() => { try { localStorage.setItem('sm.mydash.tv', JSON.stringify({ interval: 0, panels: 'stat', density: 'compact' })) } catch { /* yok */ } })
      await page.goto('/?tab=mydash&md_tv=1')
      const tv = page.locator('[data-slot="mydash-tv"]')
      const w = tv.locator('[data-slot="mydash-widget"][data-type="domain_expiring"]')
      await expect(w).toHaveAttribute('data-stat', 'on', { timeout: 20_000 })
      expect(seen.length).toBeGreaterThan(0)
      expect(seen.every((v) => v === 'expiry'), 'kutu hafif listeyi (view=expiry) istemeli').toBe(true)

      // Renkli panel: eşikteki alan adı (dolmuş 1 + kritik 1 + uyarı 1 = 3), kırmızı, liste çizilmez, TV'de tıklanmaz
      await expect(w.locator('[data-slot="mydash-stat-value"]')).toHaveText('3')
      await expect(w).toHaveAttribute('data-tone', 'danger')
      await expect(w.locator('[data-slot="mydash-dexp-list"]')).toHaveCount(0)
      await expect(w.locator('[data-link="true"]')).toHaveCount(0)
      await page.waitForTimeout(400)
      let r = await page.evaluate(tvAudit)
      expect(r.over, 'panel: kart taşması').toEqual([])
      expect(r.clipped, 'panel: kırpılan metin').toEqual([])
      expect(r.clippedPage, 'panel: sayfa kayıyor').toBe(false)
      expect(r.pageOverflowX, 'panel: yatay taşma').toBeLessThanOrEqual(1)
      expect(r.pageBottom, 'panel: sayfa ekrandan taşıyor').toBeLessThanOrEqual(1)
      await page.screenshot({ path: `test-results/mydash-domain-tv-stat-${vp.name}.png` })

      // Ayrıntılı görünüm: liste kartın içinde, en kısa süre başta; uzun ad sarar, kırpılmaz
      await tv.locator('[data-slot="mydash-tv-panels"]').click()
      await expect(w).not.toHaveAttribute('data-stat', 'on')
      const list = w.locator('[data-slot="mydash-dexp-list"]')
      await expect(list).toBeVisible()
      await expect(list.locator('li').first()).toContainText('kampanya-bireysel-ticari-uzun-alt-alan-adi.example.com.tr')
      await expect(w.locator('[data-link="true"]')).toHaveCount(0)
      await page.waitForTimeout(400)
      r = await page.evaluate(tvAudit)
      expect(r.over, 'ayrıntılı: kart taşması').toEqual([])
      expect(r.clipped, 'ayrıntılı: kırpılan metin').toEqual([])
      expect(r.clippedPage, 'ayrıntılı: sayfa kayıyor').toBe(false)
      expect(r.fit, 'ayrıntılı: çok küçültüldü').toBeGreaterThanOrEqual(vp.minFit)
      expect(r.pageOverflowX, 'ayrıntılı: yatay taşma').toBeLessThanOrEqual(1)
      await page.screenshot({ path: `test-results/mydash-domain-tv-detail-${vp.name}.png` })
    })
  })
}
