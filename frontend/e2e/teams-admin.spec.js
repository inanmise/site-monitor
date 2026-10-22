// Yönetim → Takımlar (2026-10-11, kullanıcı: "eksik olanların filtrelenebileceği bir süzgeç yok … tablo araları çok
// açık, menüler net belli değil, sayfaya sığmıyor … mweb responsive"). Üç boyutta (390 telefon + dokunmatik, 768 tablet
// + dokunmatik, 1280 dizüstü) gerçek tarayıcıda ölçülür: sayfa yatay taşmaz; dar kapta kartlar, geniş kapta sıkı
// tablo; uzun takım / kişi adları ve e-postalar KESİLMEZ; dokunma hedefleri ≥ 40 px; eksik süzgeci listeyi daraltır;
// eksik rozeti penceresi ve İşlemler menüsü ekrana sığar; düzeltme kısayolu ilgili sekmeye takım süzgeciyle geçer.
// API tamamen mock'lu (yalnız /api/ yolu). Ad ve adresler uydurmadır (gerçek uzunlukta AD görünen adları).
import { test, expect } from '@playwright/test'

const LONG_A = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'
const LONG_B = 'Mehmet Emin Çelikkanat-Özdemiroğulları (Uygulama Geliştirme ve Entegrasyon Müdürü)'
const USERS = [
  { id: 2, username: 'KULLANICI_A', display_name: LONG_A, employee_id: '100002', email: 'a@example.com', system_role: 'USER', org_role: 'TECH', team_id: 1, team_ids: [1], active: true },
  { id: 3, username: 'KULLANICI_B', display_name: LONG_B, employee_id: '100003', email: 'b@example.com', system_role: 'USER', org_role: 'MANAGER', team_id: null, team_ids: [], active: true },
  { id: 4, username: 'KULLANICI_C', display_name: 'Kullanıcı C', employee_id: '100004', email: 'c@example.com', system_role: 'USER', org_role: 'TECH', team_id: 2, team_ids: [2], active: true },
]
const TEAMS = [
  { id: 1, name: 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi', active: true, leader_id: 2, manager_id: 3,
    email: 'dijital-kanallar-mobil-bankacilik-uygulama-gelistirme-ekibi@example.com', description: 'Mobil uygulamalar', weekly_reminder_enabled: true },
  { id: 2, name: 'Ödeme Sistemleri', active: true, leader_id: null, manager_id: null, email: '', description: '' },
  { id: 3, name: 'Kurumsal İnternet Şubesi Altyapı Ekibi', active: true, leader_id: 4, manager_id: 3, email: 'kis@example.com', description: 'İnternet şubesi' },
  { id: 4, name: 'Arşiv Takımı', active: false, leader_id: 4, manager_id: null, email: 'arsiv@example.com', description: '' },
  { id: 5, name: 'Veri Ambarı ve Raporlama Platformu', active: true, leader_id: 2, manager_id: 3, email: 'veri@example.com', description: 'Raporlama' },
  { id: 6, name: 'Ağ ve Güvenlik Operasyon Merkezi Gece Vardiyası', active: true, leader_id: 2, manager_id: 3, email: 'noc@example.com', description: 'NOC' },
]
const STATS = {
  1: { members: 12, members_inactive: 2, domains: 34, monitors: 18, open_alerts: 3, contacts: 2, groups: 1 },
  2: { members: 1, members_inactive: 1, domains: 0, monitors: 0, open_alerts: 0, contacts: 0, groups: 0 },
  3: { members: 5, members_inactive: 0, domains: 4, monitors: 2, open_alerts: 0, contacts: 0, groups: 1 },
  4: { members: 0, members_inactive: 0, domains: 0, monitors: 0, open_alerts: 0, contacts: 0, groups: 0 },
  5: { members: 7, members_inactive: 0, domains: 2, monitors: 9, open_alerts: 1, contacts: 1, groups: 2 },
  6: { members: 9, members_inactive: 0, domains: 0, monitors: 25, open_alerts: 0, contacts: 3, groups: 1 },
}

async function mock(page) {
  await page.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const p = new URL(route.request().url()).pathname
    let body = { success: true, data: [] }
    if (p === '/api/me') {
      body = { success: true, username: 'demo', system_role: 'ADMIN', global_admin: true, team_id: 1, team_name: TEAMS[0].name,
        team_ids: [1], team_names: [TEAMS[0].name], tour: { status: 'dismissed', version: 99 } }
    } else if (p === '/api/admin/teams') body = { success: true, data: TEAMS }
    else if (p === '/api/admin/users') body = { success: true, data: USERS }
    else if (p === '/api/admin/teams/stats') body = { success: true, data: STATS }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

async function pageOverflow(page) {
  return page.evaluate(() => {
    const main = document.querySelector('.app-main') || document.body
    return Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth, main.scrollWidth - main.clientWidth)
  })
}

/**
 * Dokunma hedefi 40 px'ten kısa olan görünür denetimler. Ölçü: öğenin kutusu; `::after` ile dikeyde büyütülmüş çip
 * (TOUCH_CHIP kalıbı) için büyütülmüş boy; anahtar (switch) için etiketli satırı, onay kutusu için saran etiket.
 */
function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, a[href], select, input:not([type="hidden"])')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0) continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none') continue
    let h = (b.closest('[data-slot="input-group"]')?.getBoundingClientRect() ?? r).height
    const role = b.getAttribute('role')
    if (role === 'switch') h = Math.max(h, b.parentElement.getBoundingClientRect().height)
    if (role === 'checkbox') h = Math.max(h, b.closest('label')?.getBoundingClientRect().height ?? 0)
    const after = getComputedStyle(b, '::after')
    if (after.position === 'absolute') h = Math.max(h, r.height - (parseFloat(after.top) || 0) - (parseFloat(after.bottom) || 0))
    if (h < 39.5) out.push(`${b.tagName.toLowerCase()}[${b.getAttribute('data-slot') || role || ''}] "${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

/** Kesilen / kabından taşan ad, kişi ve adres metinleri. */
function clippedText(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const rootR = root.getBoundingClientRect()
  const out = []
  for (const el of root.querySelectorAll('[data-slot="team-badge"] span, [data-slot="team-owners"] dd, [data-slot="team-owner-sicil"], [data-slot="team-email"], [data-slot="team-gap-chip"] span')) {
    const r = el.getBoundingClientRect()
    if (r.width === 0) continue
    const cs = getComputedStyle(el)
    // Çok satırlı metni yuvarlak köşeli + taşması gizli kap (hap rozeti) köşelerinden kırpar — ölçü bunu göremez.
    const box = el.closest('[data-slot="team-badge"]')
    const pillClip = box && getComputedStyle(box).overflow !== 'visible' && box.getBoundingClientRect().height > 30
      && parseFloat(getComputedStyle(box).borderTopLeftRadius) > 8
    if (pillClip || cs.textOverflow === 'ellipsis' || el.scrollWidth > el.clientWidth + 1 || r.right > rootR.right + 1) {
      out.push(`${el.getAttribute('data-slot') || el.tagName.toLowerCase()} "${el.textContent.trim().slice(0, 30)}" ${el.scrollWidth}>${el.clientWidth}`)
    }
  }
  return out
}

/** Öğenin (pencere / menü) tamamı görünüm alanında mı. */
async function inViewport(locator, vw) {
  const box = await locator.boundingBox()
  expect(box.x).toBeGreaterThanOrEqual(-1)
  expect(box.x + box.width).toBeLessThanOrEqual(vw + 1)
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Takımlar — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('taşma yok, ad kesilmez, dokunma hedefleri, eksik süzgeci, rozet penceresi, İşlemler menüsü', async ({ page }) => {
      test.setTimeout(120_000)
      // emulateMedia KULLANILMAZ: Chromium'da dokunmatik öykünmesinin (pointer: coarse) medya özelliğini sıfırlıyor.
      await mock(page)
      await page.goto('/?tab=admin&g_tab=teams')
      const root = page.locator('[data-slot="team-manager"]')
      await expect(root.locator('[data-slot="team-gap-chip"][data-gap="contacts"]')).toHaveAttribute('data-count', '3', { timeout: 20_000 })

      // Dokunmatik öykünmesi gerçekten etkin (yoksa dokunma hedefi ölçüsü sessizce masaüstü kipinde koşar)
      expect(await page.evaluate(() => matchMedia('(pointer: coarse)').matches)).toBe(vp.touch)
      const list = page.locator('[data-slot="tm-list"]')
      if (vp.width < 900) await expect(list).toHaveAttribute('data-view', 'cards')
      else await expect(list).toHaveAttribute('data-view', 'table')
      const items = vp.width < 900 ? page.locator('[data-slot="team-card"]') : page.locator('[data-slot="table-body"] > tr[data-team-id]')
      await expect(items).toHaveCount(6)
      await page.waitForTimeout(300)

      expect(await pageOverflow(page), 'sayfa yatay taşması').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clippedText, '[data-slot="team-manager"]'), 'kesilen ad / adres').toEqual([])
      // Sorumlular: lider + müdür ad-soyadının yanında sicil (2026-10-11)
      await expect(items.first().locator('[data-slot="team-owner-sicil"]')).toHaveCount(2)
      await expect(items.first().locator('[data-slot="team-owner-sicil"]').first()).toContainText('100002')
      if (vp.touch) {
        const bad = await page.evaluate(smallTargets, '[data-slot="team-manager"]')
        expect(bad, `40 px altı hedefler: ${bad.join(', ')}`).toEqual([])
      }
      await page.screenshot({ path: `test-results/teams-${vp.name}.png`, fullPage: true })
      await root.locator('[data-slot="stats-panel"]').screenshot({ path: `test-results/teams-kpi-${vp.name}.png` })
      await root.locator('[data-slot="team-gaps"]').screenshot({ path: `test-results/teams-gaps-${vp.name}.png` })
      await items.first().screenshot({ path: `test-results/teams-item-${vp.name}.png` })

      // Eksik süzgeci: "Eskalasyon kişisi yok" → 3 takım; "Hepsi" + "E-posta yok" → 1 takım
      await root.locator('[data-slot="team-gap-chip"][data-gap="contacts"]').click()
      await expect(items).toHaveCount(3)
      await expect(page).toHaveURL(/g_miss=contacts/)
      await root.locator('[data-slot="team-gap-chip"][data-gap="email"]').click()
      await root.locator('[data-slot="team-gap-mode"] [data-slot="toggle-group-item"]').nth(1).click()
      await expect(items).toHaveCount(1)
      expect(await pageOverflow(page), 'süzgeç sonrası taşma').toBeLessThanOrEqual(1)

      // Satır eksik rozeti → pencere ekrana sığar; "Eskalasyon kişisi ekle" ilgili sekmeye takımla geçer
      await items.first().locator('button[data-slot="team-gap-badge"]').click()
      const pop = page.locator('[data-slot="team-gap-pop"]')
      await expect(pop).toBeVisible()
      await expect(pop.locator('li[data-gap]')).toHaveCount(9)
      await inViewport(pop, vp.width)
      await page.waitForTimeout(400)   // açılış animasyonu (fade/zoom) bitsin
      expect(await pop.evaluate((el) => getComputedStyle(el).opacity)).toBe('1')
      await page.screenshot({ path: `test-results/teams-gap-pop-${vp.name}.png` })
      await pop.locator('li[data-gap="contacts"] [data-slot="team-gap-fix"]').click()
      await expect(page).toHaveURL(/g_tab=contacts/)
      await expect(page).toHaveURL(/g_team=2/)
    })

    test('İşlemler menüsü görünür etiketli, açılan menü ekrana sığar', async ({ page }) => {
      test.setTimeout(60_000)
      await mock(page)
      await page.goto('/?tab=admin&g_tab=teams')
      const trigger = page.locator('[data-slot="team-manager"] button[data-kebab="labeled"]').first()
      await expect(trigger).toBeVisible({ timeout: 20_000 })
      await expect(trigger).toContainText(/İşlemler|Actions/)
      const tb = await trigger.boundingBox()
      if (vp.touch) expect(tb.height).toBeGreaterThanOrEqual(39.5)
      await trigger.click()
      const menu = page.getByRole('menu')
      await expect(menu).toBeVisible()
      // 2026-10-11: "Nöbet çizelgesi" onuncu öğe (nöbet rotasyonu); 2026-10-12: "Erişimi paylaş" on birinci (erişim paylaşımı)
      await expect(menu.getByRole('menuitem')).toHaveCount(11)
      await expect(menu.getByRole('menuitem', { name: /Nöbet çizelgesi|On-call schedule/ })).toBeVisible()
      await expect(menu.getByRole('menuitem', { name: /Erişimi paylaş|Share access/ })).toBeVisible()
      await inViewport(menu, vp.width)
      await page.waitForTimeout(400)
      await page.screenshot({ path: `test-results/teams-menu-${vp.name}.png` })
    })
  })
}

// ── 20.166 bugfix (PKG-8a, UIA-1 / UIA-13): kapsamlı müdür, yönetmediği bir takımı da olan kullanıcıyı düzenler ──────────
// Eskiden her kayıt bütün takım kümesini gönderiyor, sunucu kapsam dışı takımı görünce 403 veriyordu; takım çıplak "#99"
// çiziliyordu. Şimdi değişmeyen takım kümesi GİTMEZ, kapsam dışı takım dizinden ADIYLA görünür ve çıkarılamaz.
const OTHER_TEAM = 'Başka Müdürlüğün Kurumsal Ödeme Altyapısı Uzun Adlı Takımı'
async function mockScoped(page, puts) {
  await page.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  const user = { id: 7, username: 'KULLANICI_X', display_name: LONG_A, employee_id: '100007', email: 'x@example.com',
    system_role: 'USER', org_role: 'TECH', team_id: 1, team_ids: [1, 99], active: true, auth_source: 'LOCAL' }
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    let body = { success: true, data: [] }
    if (p === '/api/me') {
      body = { success: true, username: 'mudur', system_role: 'ADMIN', global_admin: false, team_id: 1, team_name: TEAMS[0].name,
        team_ids: [1], team_names: [TEAMS[0].name], manage_team_ids: [1, 3], tour: { status: 'dismissed', version: 99 } }
    } else if (p === '/api/admin/teams') body = { success: true, data: TEAMS.filter((t) => t.id === 1 || t.id === 3) }
    else if (p === '/api/teams/directory') body = { success: true, data: [...TEAMS, { id: 99, name: OTHER_TEAM, active: true }] }
    else if (p === '/api/admin/users/search') body = { success: true, data: [user], total: 1, page: 0, active_admin_count: 2 }
    else if (p === '/api/admin/users/7' && req.method() === 'PUT') {
      puts.push(JSON.parse(req.postData() || '{}'))
      body = { success: true, data: user }
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

for (const vp of [VIEWPORTS[0], VIEWPORTS[2]]) {
  test.describe(`Kullanıcı düzenleme (kapsamlı müdür) — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('kapsam dışı takım adıyla görünür, çıkarılamaz; telefon kaydı takımları göndermez', async ({ page }) => {
      test.setTimeout(90_000)
      const puts = []
      await mockScoped(page, puts)
      await page.goto('/?tab=admin&g_tab=users')
      await expect(page.getByText('KULLANICI_X').first()).toBeVisible({ timeout: 20_000 })
      await page.getByRole('button', { name: /(İşlemler|Actions)/ }).first().click()
      await page.getByRole('menuitem', { name: /^(Düzenle|Edit)$/ }).click()
      const dlg = page.getByRole('dialog')
      await expect(dlg).toBeVisible()
      // Rol seçici yalnız USER / TEAM_ADMIN (UIA-13)
      await dlg.getByRole('combobox', { name: /^(Sistem Rolü|System Role)/ }).click()
      await expect(page.getByRole('option')).toHaveText(['USER', 'TEAM_ADMIN'])
      await page.keyboard.press('Escape')
      await dlg.getByRole('tab', { name: /^(Takımlar|Teams)/ }).click()
      const row = dlg.locator('[data-slot="ued-team-row"][data-team-id="99"]')
      await expect(row).toContainText(OTHER_TEAM)
      await expect(dlg).not.toContainText('#99')
      await expect(row.locator('[data-slot="ued-team-out-of-scope"]')).toBeVisible()
      await expect(row.getByRole('button')).toHaveCount(0)
      expect(await row.evaluate((el) => el.scrollWidth <= el.clientWidth + 1), 'takım satırı taşmaz').toBe(true)
      await dlg.getByRole('tab', { name: /^(Profil|Profile)/ }).click()
      await dlg.getByLabel(/^(Telefon|Phone)/).fill('5551112233')
      await dlg.getByRole('button', { name: /^(Kaydet|Save)$/ }).click()
      await expect.poll(() => puts.length, { timeout: 10_000 }).toBe(1)
      expect(puts[0].phone).toBe('5551112233')
      expect(puts[0]).not.toHaveProperty('team_ids')
      expect(puts[0]).not.toHaveProperty('team_id')
    })
  })
}

