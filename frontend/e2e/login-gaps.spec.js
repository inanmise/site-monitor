import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

// Giriş sonrası eksikler penceresi (2026-11-15, kullanıcı isteği: "girişten sonra profil, takım, izleme ve envanter
// eksiklerini bir pencerede göster; daha sonra hatırlat / bir daha gösterme; profil menüsünden her zaman açılsın").
// Üç boyutta (390 telefon dokunmatik, 768 tablet dokunmatik, 1280 dizüstü): pencere kendiliğinden açılır, ekrana sığar,
// yatay taşmaz, uzun takım / izleme adları kesilmez, dokunmatikte hedefler ≥ 40 px; "Düzelt" kaydın ekranına götürür;
// "Daha sonra hatırlat" kapatır, kullanıcı menüsündeki "Eksiklerim" yeniden açar.
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

const DIALOG = '[data-slot="gaps-dialog"]'

/** Görünür düğme / sekme içinde 40 px'ten kısa olanlar (dokunmatik kuralı). */
async function smallTargets(root) {
  return root.evaluate((el) => {
    const out = []
    for (const b of el.querySelectorAll('button, a[href], [role="tab"]')) {
      const r = b.getBoundingClientRect()
      if (r.width === 0 || r.height === 0) continue
      const cs = getComputedStyle(b)
      if (cs.visibility === 'hidden' || cs.display === 'none') continue
      if (r.height < 39.5) out.push(`${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)} ${Math.round(r.height)}`)
    }
    return out
  })
}

/** Kesilen metin: kendi kutusundan taşan ya da üç nokta ile kırpılan öğe. */
async function clipped(root) {
  return root.evaluate((el) => {
    const out = []
    for (const n of el.querySelectorAll('span, p, h2, li')) {
      if (n.closest('.sr-only')) continue                     // ekran okuyucu metni (1 px kutu) ölçülmez
      if (n.scrollWidth > n.clientWidth + 1 && getComputedStyle(n).overflow !== 'visible') out.push((n.textContent || '').trim().slice(0, 40))
      if (getComputedStyle(n).textOverflow === 'ellipsis') out.push(`ellipsis:${(n.textContent || '').trim().slice(0, 40)}`)
    }
    return out
  })
}

for (const vp of VIEWPORTS) {
  test(`eksikler penceresi (${vp.name} ${vp.width}): kendiliğinden açılır, sığar, kesilmez, düzelt / sonra hatırlat / menüden aç`, async ({ browser }) => {
    test.setTimeout(120_000)
    const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })
    const page = await context.newPage()
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await mockApi(page, { role: 'USER', globalAdmin: false, teamIds: [1], perms: { 'data_quality.view': { view: true } }, gaps: true })
    // Profilde e-posta ve telefon YOK (sonradan kaydedilen yol önce eşleşir)
    await page.route((u) => new URL(u).pathname === '/api/me', (route) => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        success: true, username: 'demo', system_role: 'USER', global_admin: false, weekly_reports_visible: true,
        team_id: 1, team_name: 'Takım A', team_ids: [1], team_names: ['Takım A'], manage_team_ids: [],
        email: null, phone: '', tour: { status: 'dismissed', version: 99 },
      }),
    }))
    await page.goto('/?tab=dashboard')

    const dialog = page.locator(DIALOG)
    await expect(dialog).toHaveAttribute('data-status', 'ready', { timeout: 20_000 })
    const counts = Object.fromEntries(await page.locator('[data-slot="gaps-tab"]').evaluateAll(
      (els) => els.map((e) => [e.dataset.category, e.dataset.count])))
    expect(counts.profile).toBe('2')
    expect(Number(counts.team)).toBeGreaterThanOrEqual(1)
    expect(Number(counts.monitors)).toBeGreaterThanOrEqual(1)
    expect(Number(counts.inventory)).toBeGreaterThanOrEqual(1)
    await expect(dialog).not.toContainText(/gaps\.[a-zA-Z]|dq\.rule\.|undefined|NaN/)

    // Ekrana sığar (telefonda tam ekran), yatay kaymaz
    const box = await dialog.boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(-1)
    expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
    expect(box.y + box.height).toBeLessThanOrEqual(vp.height + 1)
    const overflow = await dialog.evaluate((el) => {
      let worst = 0
      for (const n of el.querySelectorAll('*')) {
        const r = n.getBoundingClientRect()
        if (r.width > 0) worst = Math.max(worst, r.right - window.innerWidth)
      }
      return worst
    })
    expect(overflow, 'pencere içeriği görünüm alanını aşıyor').toBeLessThanOrEqual(1)

    // Sekme satırı sarar ve içeriğin ÜSTÜNE binmez (shadcn listesi sabit 36 px idi): liste paneli örtmez
    // (liste kutusu değil SEKMELERİN alt kenarı: sabit yükseklikli listede ikinci satır kutudan taşıp paneli örterdi)
    const tabsBottom = Math.max(...await page.locator('[data-slot="gaps-tab"]').evaluateAll((els) => els.map((e) => e.getBoundingClientRect().bottom)))
    const panelBox = await page.locator('[data-slot="gaps-panel"][data-state="active"]').boundingBox()
    expect(panelBox.y, 'sekmeler paneli örtüyor').toBeGreaterThanOrEqual(tabsBottom - 1)
    // (scrollHeight kullanılmaz: shadcn sekmesinin görünmez alt çizgisi ::after kutunun altına taşar)
    const tabOverflow = await page.locator('[data-slot="gaps-tab"]').evaluateAll((els) => els.filter((e) => {
      const r = e.getBoundingClientRect()
      return [...e.children].some((k) => { const c = k.getBoundingClientRect(); return c.top < r.top - 0.5 || c.bottom > r.bottom + 0.5 || c.right > r.right + 0.5 })
    }).length)
    expect(tabOverflow, 'sekme metni kendi kutusundan taşıyor').toBe(0)

    // İzlemeler sekmesi: uzun izleme adı sarar, "Düzelt" düğmeleri var
    await page.locator('[data-slot="gaps-tab"][data-category="monitors"]').click()
    const panel = page.locator('[data-slot="gaps-panel"][data-category="monitors"]')
    await expect(panel.locator('[data-slot="gaps-subitem"]').first()).toBeVisible()
    await expect(panel).toContainText('çok uzun izleme adı örneği — taşmamalı')
    for (const cat of ['profile', 'team', 'monitors', 'inventory']) {
      await page.locator(`[data-slot="gaps-tab"][data-category="${cat}"]`).click()
      const bad = await clipped(dialog)
      expect(bad, `${cat}: kesilen metin ${bad.join(' | ')}`).toEqual([])
      if (vp.touch) {
        const small = await smallTargets(dialog)
        expect(small, `${cat}: 40 px altı hedefler: ${small.join(', ')}`).toEqual([])
      }
    }
    await page.screenshot({ path: `test-results/login-gaps-${vp.name}.png` })

    // "Daha sonra hatırlat": kapanır, 1 gün sonrası saklanır
    await page.locator('[data-slot="gaps-snooze"]').click()
    await expect(dialog).toHaveCount(0)
    const stored = await page.evaluate(() => JSON.parse(localStorage.getItem('sm.gaps.state') || 'null'))
    expect(stored?.snoozeUntil).toBeGreaterThan(Date.now() + 23 * 3600_000)

    // Kullanıcı menüsü → "Eksiklerim": her zaman açar (telefonda menü çekmecenin içinde, alt sayfa olarak açılır)
    if (vp.width < 768) await page.locator('[data-sidebar="trigger"]').first().click()
    // (seçici dizesine görünürlük sözde sınıfı yazılmaz: Tailwind onu sınıf adayı sanıp bozuk CSS üretir — filter kullanılır)
    await page.locator('[data-slot="user-menu-trigger"]').filter({ visible: true }).first().click()
    await page.locator('[data-slot="user-menu-gaps"]').filter({ visible: true }).first().click()
    await expect(dialog).toHaveAttribute('data-status', 'ready', { timeout: 20_000 })

    // "Düzelt": izlemenin ekranına gider, pencere kapanır
    await page.locator('[data-slot="gaps-tab"][data-category="monitors"]').click()
    await panel.locator('[data-slot="gaps-fix"]').first().click()
    await expect(dialog).toHaveCount(0)
    await expect(page).toHaveURL(/tab=http/)
    await context.close()
  })
}
