import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

// Eksikler penceresi — OKUNAMAYAN kaynaklar (2026-11-17, inceleme: takımın veri kalitesi ve bildirim grupları okunamadığında
// kategori sekmeleri ve dışa aktarma "tamamlanacak bir şey yok" diyordu). Üç boyutta (390 telefon dokunmatik, 768 tablet
// dokunmatik, 1280 dizüstü): pencere "tamamlanacak bir şey yok" demez, toplam "kesin değil / en az" okunur, okunamayan
// takım ve denetim adıyla söylenir (yeniden dene düğmesiyle), takım / izleme / envanter sekmeleri "eksik yok" yerine
// "kesin değil" der; pencere ekrana sığar, metinler kesilmez, dokunmatikte hedefler ≥ 40 px. API mock'lu.
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]
const DIALOG = '[data-slot="gaps-dialog"]'
const TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme ve Operasyon Ekibi'

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

async function clipped(root) {
  return root.evaluate((el) => {
    const out = []
    for (const n of el.querySelectorAll('span, p, h2, li')) {
      if (n.closest('.sr-only')) continue
      if (n.scrollWidth > n.clientWidth + 1 && getComputedStyle(n).overflow !== 'visible') out.push((n.textContent || '').trim().slice(0, 40))
      if (getComputedStyle(n).textOverflow === 'ellipsis') out.push(`ellipsis:${(n.textContent || '').trim().slice(0, 40)}`)
    }
    return out
  })
}

const fail = (route) => route.fulfill({ status: 500, contentType: 'application/json',
  body: JSON.stringify({ success: false, code: 'INTERNAL', error: 'Sunucu şu an yanıt vermiyor; birkaç dakika sonra yeniden deneyin.' }) })

for (const vp of VIEWPORTS) {
  test(`eksikler penceresi okunamayan kaynakla (${vp.name} ${vp.width}): "eksik yok" demez, adıyla söyler, sığar`, async ({ browser }) => {
    test.setTimeout(120_000)
    const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })
    const page = await context.newPage()
    await page.emulateMedia({ reducedMotion: 'reduce' })
    // Kendiliğinden açılma kapalı (gaps: false) — pencere kullanıcı menüsünden açılır (eksik görünmese de açılmalı)
    await mockApi(page, {
      role: 'USER', globalAdmin: false, teamIds: [1],
      perms: { 'data_quality.view': { view: true }, 'notification.groups': { view: true }, 'oncall.read': { view: true } },
    })
    await page.route((u) => new URL(u).pathname === '/api/me', (route) => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        success: true, username: 'demo', system_role: 'USER', global_admin: false, weekly_reports_visible: true,
        team_id: 1, team_name: TEAM, team_ids: [1], team_names: [TEAM], manage_team_ids: [],
        email: 'demo@example.com', phone: '05551112233', tour: { status: 'dismissed', version: 99 },
      }),
    }))
    // Takımın veri kalitesi ve bildirim grupları OKUNAMIYOR
    await page.route((u) => new URL(u).pathname.startsWith('/api/data-quality/teams/'), fail)
    await page.route((u) => new URL(u).pathname === '/api/notification-groups', fail)
    await page.goto('/?tab=dashboard')

    if (vp.width < 768) await page.locator('[data-sidebar="trigger"]').first().click()
    await page.locator('[data-slot="user-menu-trigger"]').filter({ visible: true }).first().click()
    await page.locator('[data-slot="user-menu-gaps"]').filter({ visible: true }).first().click()
    const dialog = page.locator(DIALOG)
    await expect(dialog).toHaveAttribute('data-status', 'ready', { timeout: 20_000 })

    // "Tamamlanacak bir şey yok" YOK; toplam kesin değil / en az
    await expect(dialog.locator('[data-slot="gaps-none"]')).toHaveCount(0)
    const total = dialog.locator('[data-slot="gaps-total"]')
    await expect(total).toHaveAttribute('data-incomplete', 'true')
    await expect(total).toHaveText(/not certain|kesin değil|At least|En az/)
    // Okunamayan takım ve denetim adıyla, yeniden dene düğmesiyle
    await expect(dialog.locator('[data-slot="gaps-failed"]')).toBeVisible()
    const checks = dialog.locator('[data-slot="gaps-failed-checks"]')
    await expect(checks).toBeVisible()
    await expect(checks).toContainText(/Notification groups|Bildirim grupları/)
    await expect(dialog).not.toContainText(/gaps\.[a-zA-Z]|dq\.rule\.|undefined|NaN/)

    // Takım / izleme / envanter sekmeleri: "eksik yok" değil, "kesin değil"
    for (const cat of ['team', 'monitors', 'inventory']) {
      await page.locator(`[data-slot="gaps-tab"][data-category="${cat}"]`).click()
      const panel = page.locator(`[data-slot="gaps-panel"][data-category="${cat}"]`)
      await expect(panel.locator('[data-slot="gaps-empty"]'), `${cat}: "eksik yok" yazıyor`).toHaveCount(0)
      await expect(panel.locator('[data-slot="gaps-category-unchecked"], [data-slot="gaps-category-partial"]').first()).toBeVisible()
      const bad = await clipped(dialog)
      expect(bad, `${cat}: kesilen metin ${bad.join(' | ')}`).toEqual([])
      if (vp.touch) {
        const small = await smallTargets(dialog)
        expect(small, `${cat}: 40 px altı hedefler: ${small.join(', ')}`).toEqual([])
      }
    }

    // Pencere ekrana sığar, yatay taşmaz
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
    await page.screenshot({ path: `test-results/login-gaps-unchecked-${vp.name}.png` })
    await context.close()
  })
}
