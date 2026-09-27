// MOBİL WEB — takım üyeliği kaynağı / AD ile karşılaştırma yüzeyleri (prod hatası 2026-09-26).
// jsdom yerleşim yapmaz; burada gerçek tarayıcıda ölçülür: (1) Takımlar → ⋮ → "AD ile üyelik denetimi" penceresi,
// (2) Kullanıcılar → kullanıcı detayı → "AD ile karşılaştır" sonucu — telefon ve tablet boyunda hiçbir görünür öğe
// ekran dışına taşmaz, pencere ekrana sığar. API tamamen mock'lu (yalnız /api/ yolu). Adlar yer tutucu.
import { test, expect } from '@playwright/test'

const TEAM = { id: 1, name: 'Takım A Mobil Servis ve Uzun Adlı Ekip', email: 'takim-a@example.com', active: true, leader_id: 2, manager_id: null }
const USERS = [
  { id: 2, username: 'KULLANICI_Y', display_name: 'Kullanıcı Y Uzun Soyadlı Örnek', email: 'y@example.com', system_role: 'USER', org_role: 'TECH',
    team_id: 1, team_ids: [1], manager_id: 3, manager_sicil: '100003', active: true, auth_source: 'LDAP' },
  { id: 3, username: 'KULLANICI_X', display_name: 'Kullanıcı X', email: 'x@example.com', system_role: 'TEAM_ADMIN', org_role: 'MANAGER',
    team_id: 1, team_ids: [1], manager_id: 4, manager_sicil: '100004', active: true, auth_source: 'LDAP', employee_id: '100003' },
  { id: 4, username: 'BOLUM_B', display_name: 'Bölüm Başkanı B', email: 'b@example.com', system_role: 'USER', org_role: 'BOLUM_BASKANI',
    team_id: null, team_ids: [], active: true, auth_source: 'LDAP', employee_id: '100004' },
]
const TEAM_CHECK = { team_id: 1, team_name: TEAM.name, checked: 2, truncated: false, members: [
  { user_id: 2, username: 'KULLANICI_Y', display_name: USERS[0].display_name, active: true, auth_source: 'LDAP', team_locked: false, primary: true,
    source: 'LDAP_GROUP', detail: TEAM.name, ldap_found: true, supported_by_ad: true, on_resync: 'KEEP', on_login: 'KEEP' },
  { user_id: 3, username: 'KULLANICI_X', display_name: 'Kullanıcı X', active: true, auth_source: 'LDAP', team_locked: true, primary: true,
    source: 'LDAP_COMPANY', detail: 'company=YAZILIM GELISTIRME MUDURU-Takım A Mobil Servis ve Uzun Adlı Ekip', ldap_found: true,
    supported_by_ad: false, on_resync: 'KEEP_LOCKED', on_login: 'KEEP_LOCKED' },
] }
const MEMBERSHIP = { user_id: 3, team_locked: false, memberships: [
  { team_id: 1, team_name: TEAM.name, primary: true, legacy_primary_only: true, source: 'LEGACY' },
] }
const USER_CHECK = { user_id: 3, found: true, team_locked: false,
  memberships: [{ team_id: 1, team_name: TEAM.name, primary: true, source: 'LEGACY', supported_by_ad: false, on_resync: 'REMOVE', on_login: 'KEEP' }],
  to_add: [{ team_name: 'Takım B Çok Uzun Bir Scrum Grubu Adı', team_id: null, source: 'LDAP_GROUP', on_resync: 'ADD' }],
  ignored_groups: [{ dn: 'CN=Takım A Eski Arşiv Grubu,OU=ScrumGroupsArchive,OU=Staff,DC=example,DC=com', reason: 'NOT_TEAM_OU' }],
  manager: { candidates: { extensionAttribute4: '100004', manager: '100005' }, attributes_disagree: true, ad_sicil: '100004',
    ad_manager_name: 'Bölüm Başkanı B', db_sicil: '100004', db_manager_name: 'Bölüm Başkanı B', matches_ad: false, db_consistent: false } }

async function mock(page) {
  await page.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const p = new URL(route.request().url()).pathname
    let body = { success: true, data: [] }
    if (p === '/api/me') {
      body = { success: true, username: 'demo', system_role: 'ADMIN', global_admin: true, team_id: 1, team_name: TEAM.name,
        team_ids: [1], team_names: [TEAM.name], tour: { status: 'dismissed', version: 99 } }
    } else if (p === '/api/admin/teams') body = { success: true, data: [TEAM] }
    else if (p === '/api/admin/users') body = { success: true, data: USERS }
    else if (p === '/api/admin/users/search') body = { success: true, data: USERS, total: USERS.length, page: 0, total_pages: 1, active_admin_count: 1 }
    else if (p === '/api/admin/teams/stats') body = { success: true, data: { 1: { members: 2, domains: 0, monitors: 0, open_alerts: 0, contacts: 0, groups: 0 } } }
    else if (p === '/api/admin/teams/1/ldap-check') body = { success: true, data: TEAM_CHECK }
    else if (p === '/api/admin/users/3/team-membership') body = { success: true, data: MEMBERSHIP }
    else if (p === '/api/admin/users/3/ldap-check') body = { success: true, data: USER_CHECK }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

/** Pencere içinde ekranın sağına taşan görünür öğeler (kendi kaydırma kabında kalanlar sayılmaz). */
function offendersIn(sel) {
  const vw = document.documentElement.clientWidth
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.width <= 1 || r.height <= 1 || r.right <= vw + 1) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (!contained) out.push(`${el.tagName.toLowerCase()} → ${Math.round(r.right)} "${(el.textContent || '').trim().slice(0, 30)}"`)
    if (out.length >= 6) break
  }
  return out
}

/**
 * Pencere İÇİNDE yatay kaydırma: bu yüzeyler satır listesi + saran rozetlerdir, yana kaydırmaya gerek yoktur.
 * Kasıtlı kırpma (`truncate` → text-overflow: ellipsis) sayılmaz.
 */
function sideScrollIn(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const el of [root, ...root.querySelectorAll('*')]) {
    const cs = getComputedStyle(el)
    if (cs.overflowX === 'visible' || cs.textOverflow === 'ellipsis') continue
    // Hariç (2026-09-27): ekran okuyucu metni (sr-only, 1 px kutu) ve BİLİNÇLİ yatay kayan sekme şeridi
    // (role="tablist" — telefonda altı sekme tek satırda kayar; sertifika penceresiyle aynı desen).
    const r = el.getBoundingClientRect()
    if (r.width <= 1 || r.height <= 1 || el.getAttribute('role') === 'tablist') continue
    if (el.scrollWidth - el.clientWidth > 1) {
      out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || el.getAttribute('data-testid') || ''}] ${el.scrollWidth}>${el.clientWidth}`)
    }
  }
  return out
}

for (const vp of [{ name: 'phone', width: 390, height: 844 }, { name: 'tablet', width: 768, height: 1024 }]) {
  test.describe(`AD üyelik yüzeyleri — ${vp.name}`, () => {
    test('Takımlar → AD ile üyelik denetimi penceresi ekrana sığar', async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mock(page)
      await page.goto('/?tab=admin&g_tab=teams')
      await page.getByRole('button', { name: /(İşlemler|Actions)/ }).first().click({ timeout: 20_000 })
      await page.getByRole('menuitem', { name: /AD ile üyelik denetimi|Check memberships against AD/ }).click()
      const dlg = page.getByRole('dialog').first()
      await dlg.locator('[data-testid="tla-members"]').waitFor()
      await page.waitForTimeout(400)
      expect(await page.evaluate(offendersIn, '[role="dialog"]')).toEqual([])
      expect(await page.evaluate(sideScrollIn, '[role="dialog"]')).toEqual([])
      const box = await dlg.boundingBox()
      expect(box.x).toBeGreaterThanOrEqual(0)
      expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
      // Eşitleme düğmesi (pencere altbilgisi) görünür ve dokunulabilir boyutta
      const btn = page.getByRole('button', { name: /Kilitsiz üyeleri AD'den eşitle|Re-sync unlocked members from AD/ })
      await expect(btn).toBeVisible()
      expect((await btn.boundingBox()).height).toBeGreaterThanOrEqual(30)
    })

    test('Kullanıcı detayı → AD ile karşılaştır sonucu ekrana sığar', async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mock(page)
      await page.goto('/?tab=admin&g_tab=users')
      // Telefonda kart düğmesi (data-user-open), geniş ekranda tablo satırı açar.
      await page.locator('[data-user-open="3"]' + ':visible')   /* birleştirme: bitişik yazım Tailwind'e sınıf gibi görünüp boş seçicili CSS üretiyordu */.or(page.locator('tr[aria-label*="Kullanıcı X"]:visible'))
        .first().click({ timeout: 20_000 })
      const dlg = page.getByRole('dialog').first()
      // Kullanıcı Detayı sekmeli (2026-09-27 yeniden tasarım): takım kaynakları "Takımlar", AD karşılaştırması "Dizin"
      // sekmesinde — sekmeye geçmeden içerik DOM'da yok.
      await dlg.locator('[data-tab-key="teams"]').click()
      await dlg.locator('[data-testid="user-team-sources"]').waitFor()
      await dlg.locator('[data-tab-key="directory"]').click()
      await dlg.getByRole('button', { name: /AD ile karşılaştır|Compare with AD/ }).click()
      await dlg.locator('[data-testid="ldap-compare-manager"]').waitFor()
      await page.waitForTimeout(400)
      expect(await page.evaluate(offendersIn, '[role="dialog"]')).toEqual([])
      expect(await page.evaluate(sideScrollIn, '[role="dialog"]')).toEqual([])
      const box = await dlg.boundingBox()
      expect(box.x).toBeGreaterThanOrEqual(0)
      expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
    })
  })
}
