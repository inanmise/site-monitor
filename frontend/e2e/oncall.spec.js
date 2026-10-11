// Takımlar → Nöbet çizelgesi (2026-10-11, kullanıcı kararı: "takım seçerse nöbetçiye de"). Gerçek tarayıcıda 390
// (dokunmatik) / 768 (dokunmatik) / 1280: takım satırında "Nöbetçi" (ad kesilmez), İşlemler menüsünden pencere; pencere
// ekrana sığar, uzun adlar sarar, sıra düğmeleri ≥ 40 px; sırayı değiştirip kaydetme doğru gövdeyle gider. API mock'lu.
import { test, expect } from '@playwright/test'

const LONG_A = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'
const LONG_B = 'Mehmet Emin Çelikkanat-Özdemiroğulları (Uygulama Geliştirme ve Entegrasyon Müdürü)'
const TEAMS = [
  { id: 1, name: 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi', active: true, leader_id: 2, manager_id: 3, email: 'dk@example.com' },
  { id: 2, name: 'Ödeme Sistemleri', active: true, leader_id: null, manager_id: null, email: '' },
]
const USERS = [
  { id: 2, username: 'KULLANICI_A', display_name: LONG_A, employee_id: '100002', email: 'a@example.com', system_role: 'USER', team_id: 1, team_ids: [1], active: true },
  { id: 3, username: 'KULLANICI_B', display_name: LONG_B, employee_id: '100003', email: 'b@example.com', system_role: 'USER', team_id: 1, team_ids: [1], active: true },
  { id: 4, username: 'KULLANICI_C', display_name: 'Kullanıcı C', employee_id: '100004', email: 'c@example.com', system_role: 'USER', team_id: 1, team_ids: [1], active: true },
]
const VIEW = {
  team_id: 1, configured: true, enabled: true, rotation: 'WEEKLY', anchor: '2026-10-05T09:00', notify_oncall: true, can_edit: true,
  members: [{ user_id: 2, name: LONG_A, active: true }, { user_id: 3, name: LONG_B, active: true }],
  current: { user_id: 2, name: LONG_A, starts_at: '2026-10-05T06:00:00', ends_at: '2026-10-12T06:00:00', override: false, active: true },
  upcoming: [
    { user_id: 2, name: LONG_A, starts_at: '2026-10-05T06:00:00', ends_at: '2026-10-12T06:00:00' },
    { user_id: 3, name: LONG_B, starts_at: '2026-10-12T06:00:00', ends_at: '2026-10-19T06:00:00' },
  ],
  overrides: [{ id: 9, user_id: 4, name: 'Kullanıcı C', starts_at: '2026-10-20T06:00:00', ends_at: '2026-10-21T06:00:00', note: 'Yıllık izin — takas' }],
}

let saved = null
async function mock(page) {
  saved = null
  await page.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    let body = { success: true, data: [] }
    if (p === '/api/me') {
      body = { success: true, username: 'demo', system_role: 'ADMIN', global_admin: true, team_id: 1, team_ids: [1], tour: { status: 'dismissed', version: 99 } }
    } else if (p === '/api/admin/teams') body = { success: true, data: TEAMS }
    else if (p === '/api/admin/users') body = { success: true, data: USERS }
    else if (p === '/api/admin/teams/1/users') body = { success: true, data: USERS }
    else if (p === '/api/oncall/current') body = { success: true, data: [{ team_id: 1, user_id: 2, name: LONG_A, until: '2026-10-12T06:00:00', active: true, override: false }] }
    else if (p === '/api/oncall/teams/1' && req.method() === 'PUT') {
      saved = JSON.parse(req.postData() || '{}')
      body = { success: true, data: { ...VIEW, members: saved.members.map((id) => USERS.find((u) => u.id === id)).map((u) => ({ user_id: u.id, name: u.display_name, active: true })) } }
    } else if (p === '/api/oncall/teams/1') body = { success: true, data: VIEW }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled) continue
    let h = r.height
    if (b.getAttribute('role') === 'switch') h = Math.max(h, b.parentElement.getBoundingClientRect().height)
    // Proje deseni (TOUCH_HIT): `::after` ile dikeyde büyütülmüş dokunma alanı (tarih tetikleri)
    const after = getComputedStyle(b, '::after')
    if (after.position === 'absolute' && after.height && after.height !== 'auto') h = Math.max(h, parseFloat(after.height) || 0)
    if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  // Seçici tetiğinin yer tutucusu tek satır (tasarım) — ad değil, ölçüm dışı
  return [...root.querySelectorAll('span, p, li')].filter((n) => !n.closest('[role="combobox"]'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 20
    && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')).map((n) => n.textContent.slice(0, 30))
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Nöbet çizelgesi — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('takım satırında nöbetçi; pencere sığar, adlar sarar, sıra değişip kaydedilir', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      await page.goto('/?tab=admin&g_tab=teams')
      const root = page.locator('[data-slot="team-manager"]')
      const oncallCell = root.locator('[data-slot="team-oncall"]').first()
      await expect(oncallCell).toContainText(LONG_A, { timeout: 20_000 })
      expect(await oncallCell.evaluate((el) => el.scrollWidth - el.clientWidth), 'nöbetçi adı kırpılmış').toBeLessThanOrEqual(1)

      const menuBtn = root.locator('[data-kebab]').first()
      await menuBtn.click()
      await page.getByRole('menuitem', { name: /On-call schedule|Nöbet çizelgesi/ }).click()
      const modal = page.locator('[data-slot="oncall-modal"]')
      await expect(modal.locator('[data-slot="oncall-member"]')).toHaveCount(2)
      await page.waitForTimeout(300)
      const dialog = page.getByRole('dialog')
      const box = await dialog.boundingBox()
      expect(box.x).toBeGreaterThanOrEqual(-1)
      expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
      expect(await page.evaluate(clipped, '[data-slot="oncall-modal"]'), 'kırpılan ad').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="oncall-modal"]'), 'küçük hedef').toEqual([])
      await dialog.screenshot({ path: `test-results/oncall-${vp.name}.png` })

      await modal.locator('[data-slot="oncall-member"][data-index="1"]').getByRole('button', { name: /Move up|Yukarı taşı/ }).click()
      await page.locator('[data-slot="oncall-save"]').click()
      await expect.poll(() => saved?.members).toEqual([3, 2])
      expect(saved).toMatchObject({ enabled: true, rotation: 'WEEKLY', anchor: '2026-10-05T09:00', notify_oncall: true })
    })
  })
}
