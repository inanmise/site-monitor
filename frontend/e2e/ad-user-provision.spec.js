// Yönetim → Kullanıcılar → "AD'den ara" (2026-11-11): bir AD kullanıcısını GİRİŞ YAPMADAN sisteme çekme.
// Üç boyutta (390 telefon + dokunmatik, 768 tablet + dokunmatik, 1280 dizüstü) gerçek tarayıcıda: arama →
// detay (türetilen takım) → öndolu ekleme formu; sayfa / pencere yatay taşmaz, adlar kesilmez, dokunma
// hedefleri ≥ 40 px. API tamamen mock (/api/). Adlar uydurmadır (gerçek uzunlukta AD görünen adları).
import { test, expect } from '@playwright/test'

const TEAMS = [
  { id: 1, name: 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi', active: true },
  { id: 2, name: 'Ödeme Sistemleri', active: true },
]
const AD_RESULTS = [
  { dn: 'cn=a,dc=x', username: 'MP60146', display_name: 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Dijital Kanallar Uygulama Geliştirme)', email: 'aysegul.karaosmanoglu@ornekbanka.com.tr', department: 'Teknoloji Servis Yönetimi', exists_locally: false, prefix_blocked: false },
  { dn: 'cn=b,dc=x', username: 'XX999', display_name: 'Mehmet Emin Çelikkanat', email: 'mehmet.celikkanat@ornekbanka.com.tr', department: 'Ödeme Sistemleri', exists_locally: true, prefix_blocked: false, local_user_id: 7 },
  { dn: 'cn=c,dc=x', username: 'ZZ1', display_name: 'Veli Kaya', email: 'veli.kaya@ornekbanka.com.tr', department: 'Arşiv', exists_locally: false, prefix_blocked: true },
]
const AD_DETAIL = {
  username: 'MP60146', dn: 'cn=a,dc=x',
  display_name: 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Dijital Kanallar Uygulama Geliştirme)',
  email: 'aysegul.karaosmanoglu@ornekbanka.com.tr', employee_id: '60146', first_name: 'Ayşegül', last_name: 'Karaosmanoğlu',
  title: 'Kıdemli Uzman', department: 'Teknoloji Servis Yönetimi', manager: 'cn=mudur,dc=x',
  derived_teams: ['Ödeme Sistemleri', 'Bilinmeyen Grup Takımı'], exists_locally: false, prefix_blocked: false,
}

async function mock(page) {
  await page.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const p = new URL(route.request().url()).pathname
    let body = { success: true, data: [] }
    if (p === '/api/me') {
      body = { success: true, username: 'demo', system_role: 'ADMIN', global_admin: true, team_id: 1, team_name: TEAMS[0].name, team_ids: [1], team_names: [TEAMS[0].name], tour: { status: 'dismissed', version: 99 } }
    } else if (p === '/api/me/permissions') body = { success: true, data: { scope: { global_admin: true } }, permissions: {} }
    else if (p === '/api/admin/teams') body = { success: true, data: TEAMS }
    else if (p === '/api/admin/users') body = { success: true, data: [] }
    else if (p === '/api/admin/ad-users/search') body = { success: true, results: AD_RESULTS, count: AD_RESULTS.length }
    else if (p === '/api/admin/ad-users/detail') body = { success: true, data: AD_DETAIL }
    else if (p === '/api/admin/ad-users/provision') body = { success: true, user: { id: 50, username: 'MP60146', display_name: AD_DETAIL.display_name, email: AD_DETAIL.email, system_role: 'USER' }, prefix_blocked: false }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

async function pageOverflow(page) {
  return page.evaluate(() => {
    const main = document.querySelector('.app-main') || document.body
    return Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth, main.scrollWidth - main.clientWidth)
  })
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, a[href], select, input:not([type="hidden"]), [role="combobox"]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0) continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none') continue
    let h = (b.closest('[data-slot="input-group"]')?.getBoundingClientRect() ?? r).height
    const after = getComputedStyle(b, '::after')
    if (after.position === 'absolute') h = Math.max(h, r.height - (parseFloat(after.top) || 0) - (parseFloat(after.bottom) || 0))
    if (h < 39.5) out.push(`${b.tagName.toLowerCase()}[${b.getAttribute('data-slot') || b.getAttribute('role') || ''}] "${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 24)}" ${Math.round(h)}`)
  }
  return out
}

function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const rootR = root.getBoundingClientRect()
  const out = []
  for (const el of root.querySelectorAll('span, dd, p, [data-slot="aduser-badge-exists"], [data-slot="aduser-badge-prefix"]')) {
    const r = el.getBoundingClientRect()
    if (r.width === 0 || el.children.length) continue
    if (el.scrollWidth > el.clientWidth + 1 || r.right > rootR.right + 1) {
      out.push(`${el.getAttribute('data-slot') || el.tagName.toLowerCase()} "${el.textContent.trim().slice(0, 24)}" ${el.scrollWidth}>${el.clientWidth}`)
    }
  }
  return out
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`AD'den kullanıcı ekle — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('arama → detay → ekleme formu: taşma yok, adlar kesilmez, dokunma hedefleri', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      await page.goto('/?tab=admin&g_tab=users')

      const openBtn = page.locator('[data-slot="aduser-open"]')
      await expect(openBtn).toBeVisible({ timeout: 20_000 })
      expect(await page.evaluate(() => matchMedia('(pointer: coarse)').matches)).toBe(vp.touch)
      await openBtn.click()

      const dialog = page.locator('[data-slot="aduser"]')
      await expect(dialog).toBeVisible()

      // Arama → sonuçlar (rozetler: zaten sistemde, ön ek uyarısı)
      await page.locator('[data-slot="aduser-search"]').fill('karaosman')
      await expect(page.locator('[data-slot="aduser-result"]')).toHaveCount(3, { timeout: 10_000 })
      await expect(page.locator('[data-slot="aduser-badge-exists"]')).toHaveCount(1)
      await expect(page.locator('[data-slot="aduser-badge-prefix"]')).toHaveCount(1)

      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
      expect(await page.evaluate(smallTargets, '[data-slot="aduser"]')).toEqual([])
      expect(await page.evaluate(clipped, '[data-slot="aduser-results"]')).toEqual([])

      // Zaten var olan satırda "Düzenle", eklenebilir satırda "Görüntüle"
      await expect(page.locator('[data-slot="aduser-result"][data-exists="true"] [data-slot="aduser-edit-existing"]')).toBeVisible()
      await page.locator('[data-slot="aduser-result"][data-username="MP60146"] [data-slot="aduser-view"]').click()

      // Detay + türetilen takım önizlemesi
      await expect(page.locator('[data-slot="aduser-detail"]')).toBeVisible()
      await expect(page.locator('[data-slot="aduser-derived-teams"]')).toBeVisible()
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="aduser-detail"]')).toEqual([])

      // Sisteme ekle → öndolu form (rol/org/takım)
      await page.locator('[data-slot="aduser-start-add"]').click()
      await expect(page.locator('[data-slot="aduser-add-form"]')).toBeVisible()
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
      expect(await page.evaluate(smallTargets, '[data-slot="aduser-add-form"]')).toEqual([])

      // Ekle → provision çağrılır, pencere kapanır (yalnız dizüstünde doğrula)
      if (vp.name === 'laptop') {
        const provision = page.waitForRequest((r) => r.url().includes('/api/admin/ad-users/provision') && r.method() === 'POST')
        await page.locator('[data-slot="aduser-confirm-add"]').click()
        const req = await provision
        expect(JSON.parse(req.postData()).username).toBe('MP60146')
        await expect(dialog).toBeHidden({ timeout: 10_000 })
      }
    })
  })
}
