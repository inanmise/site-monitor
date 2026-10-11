// Ayarlar → LDAP → Grup → rol eşlemesi (2026-10-11, kullanıcı kararı: "açılırsa, elle değişeni ezmez"). Gerçek tarayıcıda
// 390 (dokunmatik) / 768 (dokunmatik) / 1280: sayfa yatay taşmaz; kural satırları telefonda sarar (girdi + rol tam
// genişlik), sıra düğmeleri ≥ 40 px; "Kim ne olacak?" penceresi ekrana sığar, uzun ad kesilmez. API mock'lu.
import { test, expect } from '@playwright/test'

const LONG = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'
const SETTINGS = {
  enabled: true, host: 'ldap.example.com', port: 636, use_ldaps: true, start_tls: false, skip_cert_verification: false,
  ca_cert_pem: '', bind_dn: 'CN=svc,OU=Service,DC=example,DC=com', bind_password_set: true, base_dn: 'DC=example,DC=com',
  user_search_filter: '(objectclass=person)', user_attribute: 'sAMAccountName', email_attribute: 'mail',
  display_attribute: 'displayName', group_filter: '(objectclass=group)', skip_member_of: false, default_role: 'ADMIN',
  role_mapping_enabled: false, role_mapping_fallback: 'DERIVED',
  role_mappings: [
    { group: 'CN=SiteMonitor Yöneticileri Teknoloji Servis Yönetimi,OU=Uygulama Grupları,OU=Groups,DC=example,DC=com', role: 'ADMIN' },
    { group: 'CN=SM-Denetim', role: 'AUDIT' },
  ],
}
const PREVIEW = {
  ldap_users: 128, fallback: 'DERIVED',
  counts: { DEMOTE: 2, PROMOTE: 3, CHANGE: 0, LAST_ADMIN: 1, LOCKED: 1, PROTECTED: 0, SAME: 20, DERIVED: 101 },
  rules: [
    { index: 0, group: SETTINGS.role_mappings[0].group, role: 'ADMIN', groups_found: 1, group_dns: [], ad_members: 6, ad_members_truncated: false, matched_users: 4 },
    { index: 1, group: 'CN=SM-Denetim', role: 'AUDIT', groups_found: 0, group_dns: [], ad_members: 0, ad_members_truncated: false, matched_users: 0 },
  ],
  rows: [
    { username: 'N12345', display_name: LONG, role_before: 'TEAM_ADMIN', role_after: 'USER', outcome: 'DEMOTE', source: 'GROUP', rule_group: 'CN=SM-Denetim' },
    { username: 'N22222', display_name: 'Kullanıcı B', role_before: 'USER', role_after: 'ADMIN', outcome: 'PROMOTE', source: 'GROUP', rule_group: SETTINGS.role_mappings[0].group },
  ],
  rows_truncated: false,
}

async function mock(page) {
  await page.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const p = new URL(route.request().url()).pathname
    let body = { success: true, data: [] }
    if (p === '/api/me') {
      body = { success: true, username: 'admin', system_role: 'ADMIN', global_admin: true, bootstrap_admin: true,
        team_id: null, team_ids: [], tour: { status: 'dismissed', version: 99 } }
    } else if (p === '/api/admin/ldap/settings') body = { success: true, data: SETTINGS, configured: true, secret_key_set: true }
    else if (p === '/api/admin/ldap/role-mapping/preview') body = { success: true, data: PREVIEW }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, select, input:not([type="hidden"])')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || getComputedStyle(b).visibility === 'hidden') continue
    let h = r.height
    if (b.getAttribute('role') === 'switch') h = Math.max(h, b.parentElement.getBoundingClientRect().height)
    if (h < 39.5) out.push(`${b.tagName.toLowerCase()} "${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

function overflowIn(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const vw = document.documentElement.clientWidth
  const out = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.width > 1 && r.right > vw + 1) out.push(`${el.tagName.toLowerCase()} ${Math.round(r.right)}`)
    if (out.length > 5) break
  }
  return out
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`LDAP rol eşlemesi — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('kural listesi sığar, dokunma hedefleri, önizleme penceresi ekrana sığar ve adı kesmez', async ({ page }) => {
      test.setTimeout(90_000)
      await mock(page)
      await page.goto('/?tab=settings&sec=ldap')
      const section = page.locator('[data-slot="ldap-role-mapping"]')
      await expect(section).toBeVisible({ timeout: 20_000 })
      await expect(page.locator('[data-slot="ldap-rm-rule"]')).toHaveCount(2)
      await section.scrollIntoViewIfNeeded()
      expect(await page.evaluate(overflowIn, '[data-slot="ldap-role-mapping"]'), 'bölüm taşması').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="ldap-rm-rules"]'), 'küçük hedef').toEqual([])
      await section.screenshot({ path: `test-results/ldap-rm-${vp.name}.png` })

      // Açınca önizleme uyarısı; önizleme penceresi
      await section.getByRole('switch').click()
      await expect(page.locator('[data-slot="ldap-rm-needs-preview"]')).toBeVisible()
      await page.locator('[data-slot="ldap-rm-preview-btn"]').click()
      const dlg = page.getByRole('dialog')
      await expect(dlg.locator('[data-slot="ldap-rm-row"]')).toHaveCount(2)
      await page.waitForTimeout(400)
      const box = await dlg.boundingBox()
      expect(box.x).toBeGreaterThanOrEqual(-1)
      expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
      const clipped = await dlg.evaluate((root) => [...root.querySelectorAll('[data-slot="ldap-rm-row"] span, [data-slot="ldap-rm-rule-result"] span')]
        .filter((el) => el.scrollWidth > el.clientWidth + 1 || getComputedStyle(el).textOverflow === 'ellipsis')
        .map((el) => el.textContent.slice(0, 30)))
      expect(clipped, 'kesilen metin').toEqual([])
      await expect(dlg).toContainText('Bölüm Başkanı)')
      await page.screenshot({ path: `test-results/ldap-rm-preview-${vp.name}.png` })
    })
  })
}
