// Takımlar → Nöbet çizelgesi → "Haftalık nöbet bildirimi" (2026-11-15, kullanıcı isteği: "takımın nöbetçi bilgisini her
// hafta pazartesi 08:30'da push ve mail ile ekibe bildirelim; takım kendisi açsın, ayarlarını kendisi yapsın"). Gerçek
// tarayıcıda 390 (dokunmatik) / 768 (dokunmatik) / 1280: bölüm pencereye sığar, uzun metinler sarar, anahtar / onay kutuları /
// gün / saat dokunmatikte ≥ 40 px; açma + gün + saat değiştirip kaydetmek YALNIZ `weekly_notice` gövdesini gönderir;
// çizelge kapalıyken anahtar kilitli ve nedeni yazılı. API mock'lu.
import { test, expect } from '@playwright/test'

const LONG_A = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'
const TEAMS = [
  { id: 1, name: 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi', active: true, leader_id: 2, manager_id: null, email: 'dk@example.com' },
]
const USERS = [
  { id: 2, username: 'KULLANICI_A', display_name: LONG_A, employee_id: '100002', email: 'a@example.com', system_role: 'USER', team_id: 1, team_ids: [1], active: true },
]
const NOTICE = { enabled: false, mail: true, push: true, day: 1, time: '08:30' }
const BASE_VIEW = {
  team_id: 1, configured: true, enabled: true, rotation: 'WEEKLY', anchor: '2026-10-05T09:00', notify_oncall: false, can_edit: true,
  members: [{ user_id: 2, name: LONG_A, active: true }],
  current: { user_id: 2, name: LONG_A, starts_at: '2026-10-05T06:00:00', ends_at: '2026-10-12T06:00:00', override: false, active: true },
  upcoming: [{ user_id: 2, name: LONG_A, starts_at: '2026-10-05T06:00:00', ends_at: '2026-10-12T06:00:00' }],
  overrides: [],
  weekly_notice: NOTICE,
  weekly_notice_next: null,
  weekly_notice_last: { week: '2026-11-09', status: 'SKIPPED', reason: 'NO_RECIPIENT', at: '2026-11-09T05:30:07', mail: 'skipped', push_count: 0 },
}

let saved = null
async function mock(page, view = BASE_VIEW, { oldServer = false } = {}) {
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
    else if (p === '/api/oncall/teams/1' && req.method() === 'PUT' && oldServer) {
      // eski pod: yalnız-bildirim gövdesini çizelge kaydı sanar ve "ilk devir" ister
      saved = JSON.parse(req.postData() || '{}')
      await route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ success: false,
        code: 'VALIDATION_FAILED', field: 'anchor', error: 'İlk devir anını tarih ve saatiyle girin (yyyy-AA-GG SS:dd).' }) })
      return
    } else if (p === '/api/oncall/teams/1' && req.method() === 'PUT') {
      saved = JSON.parse(req.postData() || '{}')
      body = { success: true, data: { ...view, weekly_notice: { ...view.weekly_notice, ...(saved.weekly_notice || {}) },
        weekly_notice_next: '2026-11-18T06:15:00' } }
    } else if (p === '/api/oncall/teams/1') body = { success: true, data: view }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, select, input')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled) continue
    let h = r.height
    if (b.getAttribute('role') === 'switch') h = Math.max(h, b.parentElement.getBoundingClientRect().height)
    const after = getComputedStyle(b, '::after')
    if (after.position === 'absolute' && after.height && after.height !== 'auto') h = Math.max(h, parseFloat(after.height) || 0)
    if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || b.getAttribute('data-slot') || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('span, p, legend, label, div')]
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 20
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')).map((n) => n.textContent.slice(0, 30))
}

function overflowX(sel) {
  const root = document.querySelector(sel)
  if (!root) return 'NO_ROOT'
  const box = root.getBoundingClientRect()
  return [...root.querySelectorAll('*')].filter((n) => {
    const r = n.getBoundingClientRect()
    return r.width > 0 && (r.right > box.right + 1 || r.left < box.left - 1)
  }).map((n) => n.getAttribute('data-slot') || n.tagName).slice(0, 5)
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

async function openModal(page) {
  await page.goto('/?tab=admin&g_tab=teams')
  const root = page.locator('[data-slot="team-manager"]')
  await expect(root.locator('[data-slot="team-oncall"]').first()).toContainText(LONG_A, { timeout: 20_000 })
  await root.locator('[data-kebab]').first().click()
  await page.getByRole('menuitem', { name: /On-call schedule|Nöbet çizelgesi/ }).click()
  const sec = page.locator('[data-slot="oncall-weekly-notice"]')
  await expect(sec).toBeVisible()
  return sec
}

for (const vp of VIEWPORTS) {
  test.describe(`Haftalık nöbet bildirimi — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('bölüm sığar, metinler sarar; aç + gün + saat → yalnız weekly_notice gönderilir', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      const sec = await openModal(page)
      await sec.scrollIntoViewIfNeeded()
      await page.waitForTimeout(300)
      const dialog = page.getByRole('dialog')
      const box = await dialog.boundingBox()
      expect(box.x).toBeGreaterThanOrEqual(-1)
      expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
      expect(await page.evaluate(overflowX, '[data-slot="oncall-weekly-notice"]'), 'taşan öğe').toEqual([])
      expect(await page.evaluate(clipped, '[data-slot="oncall-weekly-notice"]'), 'kırpılan metin').toEqual([])
      await expect(sec.locator('[data-slot="oncall-wn-last"]')).toContainText(/Not sent|Gönderilmedi/)

      await sec.getByRole('switch', { name: /every week|Her hafta/ }).click()
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="oncall-weekly-notice"]'), 'küçük hedef').toEqual([])
      await sec.locator('[data-slot="oncall-wn-day"]').selectOption('3')
      await sec.locator('[data-slot="oncall-wn-time"]').fill('09:15')
      await sec.getByRole('checkbox', { name: /Push/ }).click()
      await sec.screenshot({ path: `test-results/oncall-weekly-notice-${vp.name}.png` })
      await page.locator('[data-slot="oncall-save"]').click()
      await expect.poll(() => saved).toEqual({ weekly_notice: { enabled: true, push: false, day: 3, time: '09:15' } })
      await expect(sec.locator('[data-slot="oncall-wn-next"]')).not.toContainText(/recalculated|yeniden hesaplanır/)
    })

    test('eski sunucu (dağıtım sürüyor): "bu sürüm tanımıyor" uyarısı sığar, anlamsız ilk devir hatası çıkmaz', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page, BASE_VIEW, { oldServer: true })
      const sec = await openModal(page)
      await sec.getByRole('switch', { name: /every week|Her hafta/ }).click()
      await page.locator('[data-slot="oncall-save"]').click()
      const warn = sec.locator('[data-slot="oncall-wn-oldserver"]')
      await expect(warn).toBeVisible()
      await expect(warn).toContainText(/does not know|henüz tanımıyor/)
      expect(saved).toEqual({ weekly_notice: { enabled: true } })
      expect(await page.evaluate(overflowX, '[data-slot="oncall-weekly-notice"]'), 'taşan öğe').toEqual([])
      expect(await page.evaluate(clipped, '[data-slot="oncall-weekly-notice"]'), 'kırpılan metin').toEqual([])
      await expect(sec.getByText(/İlk devir anını/)).toHaveCount(0)
    })

    test('çizelge kapalıyken anahtar kilitli ve nedeni yazılı', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page, { ...BASE_VIEW, enabled: false, current: null })
      const sec = await openModal(page)
      await expect(sec.getByRole('switch', { name: /every week|Her hafta/ })).toBeDisabled()
      await expect(sec.locator('[data-slot="oncall-wn-unusable"]')).toBeVisible()
      expect(await page.evaluate(clipped, '[data-slot="oncall-weekly-notice"]'), 'kırpılan metin').toEqual([])
    })
  })
}
