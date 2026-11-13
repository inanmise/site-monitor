// Yönetim — toplu işlemler + Takımlar kapsam panosu (2026-11-13, yönetim zenginleştirmesi (d)/(h)). Üç boyutta (390 telefon
// + dokunmatik, 768 tablet + dokunmatik, 1280 dizüstü) gerçek tarayıcıda ölçülür: sayfa yatay taşmaz; seçim çubuğu ve onay
// penceresi ekrana sığar; uzun AD adları / grup adları önizlemede KESİLMEZ; dokunma hedefleri ≥ 40 px; onay tek istek
// gönderir ve sonuç satır satır çizilir; pano dar kapta kart, geniş kapta tablo. API tamamen mock'lu (yalnız /api/).
import { test, expect } from '@playwright/test'

const LONG_A = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'
const LONG_B = 'Mehmet Emin Çelikkanat-Özdemiroğulları (Uygulama Geliştirme ve Entegrasyon Müdürü)'
const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi'
const LONG_GROUP = 'Gece Vardiyası Nöbetçi Operasyon ve Ödeme Altyapısı Uyarı Alıcıları Grubu'
const USERS = [
  { id: 2, username: 'KULLANICI_A', display_name: LONG_A, email: 'a@example.com', system_role: 'USER', org_role: 'TECH', team_id: 1, team_ids: [1], active: true },
  { id: 3, username: 'KULLANICI_B', display_name: LONG_B, email: 'b@example.com', system_role: 'USER', org_role: 'MANAGER', team_id: 2, team_ids: [2], active: true },
]
const TEAMS = [
  { id: 1, name: LONG_TEAM, active: true, leader_id: 2, manager_id: 3, email: 'dijital@example.com' },
  { id: 2, name: 'Ödeme Sistemleri', active: true, leader_id: null, manager_id: null, email: '' },
  { id: 3, name: 'Kurumsal İnternet Şubesi Altyapı Ekibi', active: true, leader_id: 3, manager_id: 3, email: 'kis@example.com' },
]
const STATS = {
  1: { members: 2, domains: 4, monitors: 6, contacts: 2, groups: 1 },
  2: { members: 1, domains: 0, monitors: 3, contacts: 0, groups: 2 },
  3: { members: 1, domains: 1, monitors: 1, contacts: 0, groups: 0 },
}
const CONTACTS = [
  { id: 11, user_id: 2, name: LONG_A, email: 'a@example.com', role: 'PO', min_alert_level: 'WARNING', active: true, team_id: 1 },
  { id: 12, user_id: 3, name: LONG_B, email: 'b@example.com', role: 'TECH', min_alert_level: 'HIGH', active: false, team_id: 1 },
  { id: 13, user_id: 3, name: LONG_B, email: 'b@example.com', role: 'MANAGER', min_alert_level: 'CRITICAL', active: true, team_id: 3 },
]
const GROUPS = [
  { id: 21, team_id: 1, name: LONG_GROUP, emails: ['nobet@example.com'], inactive_emails: [], is_default: true, active: true, can_write: true },
  { id: 22, team_id: 2, name: 'Ödeme Alarm Alıcıları', emails: ['odeme@example.com'], inactive_emails: [], is_default: false, active: true, can_write: true },
  { id: 23, team_id: 2, name: 'Ödeme Yedek', emails: ['yedek@example.com'], inactive_emails: [], is_default: false, active: true, can_write: true },
]

async function mock(page, calls) {
  await page.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    let body = { success: true, data: [] }
    if (p === '/api/me') {
      body = { success: true, username: 'demo', system_role: 'ADMIN', global_admin: true, team_id: 1, team_name: LONG_TEAM,
        team_ids: [1], team_names: [LONG_TEAM], tour: { status: 'dismissed', version: 99 } }
    } else if (p === '/api/admin/teams') body = { success: true, data: TEAMS }
    else if (p === '/api/admin/users') body = { success: true, data: USERS }
    else if (p === '/api/admin/teams/stats') body = { success: true, data: STATS }
    else if (p === '/api/admin/contacts/all') body = { success: true, data: CONTACTS }
    else if (p === '/api/admin/contacts/webhook-status') body = { success: true, data: {} }
    else if (p === '/api/notification-groups') body = { success: true, data: { groups: GROUPS, writable_team_ids: ['1', '2', '3'], team_emails: { 1: 'dijital@example.com', 2: '', 3: 'kis@example.com' } } }
    else if (p === '/api/notification-groups/usage-counts') body = { success: true, data: { counts: { 22: 4 }, stamped_open_alerts: {} } }
    else if (p === '/api/notification-groups/history') body = { success: true, data: { items: [], truncated: false, hidden: 0 } }
    else if (p === '/api/noc/coverage') body = { success: true, data: { summary: { by_team: { 1: { total: 10, covered: 10, paused: 0 }, 2: { total: 3, covered: 1, paused: 0 } } } } }
    else if (p === '/api/oncall/current') body = { success: true, data: [{ team_id: 1, name: LONG_A, until: '2026-11-14T06:00:00', active: true }] }
    else if (p === '/api/admin/contacts/bulk' && req.method() === 'POST') {
      const b = JSON.parse(req.postData() || '{}')
      calls.push(['contacts', b])
      body = { success: true, data: { action: b.action, done: 1, skipped: 1, failed: 0, results: [
        { id: 11, name: LONG_A, team_id: 1, team_name: LONG_TEAM, status: 'done', reason: 'done' },
        { id: 12, name: LONG_B, team_id: 1, team_name: LONG_TEAM, status: 'skipped', reason: 'unchanged' }] } }
    } else if (p === '/api/notification-groups/bulk' && req.method() === 'POST') {
      const b = JSON.parse(req.postData() || '{}')
      calls.push(['groups', b])
      body = { success: true, data: { action: b.action, done: 1, skipped: 1, failed: 0, results: (b.ids || []).map((id, i) => ({
        id, name: GROUPS.find((g) => g.id === id)?.name || String(id), team_id: GROUPS.find((g) => g.id === id)?.team_id ?? null,
        status: i === 0 ? 'done' : 'skipped', reason: i === 0 ? 'done' : 'in_use' })) } }
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

async function pageOverflow(page) {
  return page.evaluate(() => {
    const main = document.querySelector('.app-main') || document.body
    return Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth, main.scrollWidth - main.clientWidth)
  })
}

/** 40 px altındaki görünür dokunma hedefleri (kutu; onay kutusu için saran etiket; `::after` ile büyütülmüş alan). */
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
    if (role === 'checkbox') h = Math.max(h, b.closest('label')?.getBoundingClientRect().height ?? 0)
    const after = getComputedStyle(b, '::after')
    if (after.position === 'absolute') h = Math.max(h, r.height - (parseFloat(after.top) || 0) - (parseFloat(after.bottom) || 0))
    if (h < 39.5) out.push(`${b.tagName.toLowerCase()}[${b.getAttribute('data-slot') || role || ''}] "${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

/** Kesilen ya da kabından taşan metin (üç nokta, kaydırma taşması, sağ kenar dışı). */
function clippedText(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const rootR = root.getBoundingClientRect()
  const out = []
  for (const el of root.querySelectorAll('span, p, li, dd, dt, h4')) {
    if (el.children.length > 0 && el.tagName !== 'LI') continue
    const r = el.getBoundingClientRect()
    if (r.width === 0) continue
    const cs = getComputedStyle(el)
    if (cs.position === 'absolute' && (cs.clip !== 'auto' || el.classList.contains('sr-only'))) continue
    if (cs.textOverflow === 'ellipsis' || el.scrollWidth > el.clientWidth + 1 || r.right > rootR.right + 1) {
      out.push(`${el.getAttribute('data-slot') || el.tagName.toLowerCase()} "${el.textContent.trim().slice(0, 40)}" ${el.scrollWidth}>${el.clientWidth}`)
    }
  }
  return out
}

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
  test.describe(`Yönetim toplu işlem — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('eskalasyon kişileri: seç → pasifleştir önizlemesi → tek istek → satır satır sonuç', async ({ page }) => {
      test.setTimeout(90_000)
      const calls = []
      await mock(page, calls)
      await page.goto('/?tab=admin&g_tab=contacts')
      const row = (id) => page.locator(`[data-contact-id="${id}"]`).first()
      await expect(row(11)).toBeVisible({ timeout: 20_000 })
      expect(await page.evaluate(() => matchMedia('(pointer: coarse)').matches)).toBe(vp.touch)
      await row(11).locator('[data-slot="ec-select"]').click()
      await row(12).locator('[data-slot="ec-select"]').click()
      const bar = page.locator('[data-slot="ec-bulk-bar"]')
      await expect(bar).toBeVisible()
      await inViewport(bar, vp.width)
      expect(await pageOverflow(page), 'sayfa yatay taşması').toBeLessThanOrEqual(1)
      if (vp.touch) {
        const bad = await page.evaluate(smallTargets, '[data-slot="ec-bulk-bar"]')
        expect(bad, `40 px altı hedefler: ${bad.join(', ')}`).toEqual([])
      }
      await bar.locator('[data-action="deactivate"]').click()
      const dlg = page.getByRole('dialog')
      await expect(dlg.locator('[data-slot="bulk-preview-row"]')).toHaveCount(2)
      await expect(dlg).toContainText(LONG_A)
      await inViewport(dlg, vp.width)
      await page.waitForTimeout(400)   // açılış animasyonu
      expect(await page.evaluate(clippedText, '[role="dialog"] [data-slot="bulk-dialog"]'), 'kesilen ad').toEqual([])
      if (vp.touch) {
        const bad = await page.evaluate(smallTargets, '[role="dialog"]')
        expect(bad, `40 px altı hedefler (pencere): ${bad.join(', ')}`).toEqual([])
      }
      await page.screenshot({ path: `test-results/admin-bulk-contacts-preview-${vp.name}.png` })
      await dlg.locator('[data-slot="bulk-confirm"]').click()
      await expect(dlg.locator('[data-slot="bulk-result-row"]')).toHaveCount(2)
      expect(calls).toEqual([['contacts', { action: 'deactivate', ids: [11, 12] }]])
      expect(await page.evaluate(clippedText, '[role="dialog"] [data-slot="bulk-dialog"]'), 'kesilen ad (sonuç)').toEqual([])
      await page.screenshot({ path: `test-results/admin-bulk-contacts-result-${vp.name}.png` })
      await dlg.locator('[data-slot="bulk-close"]').click()
      await expect(bar).toHaveCount(0)
    })

    test('bildirim grupları: sil önizlemesi kullanılan / varsayılan grubu atlayacağını söyler', async ({ page }) => {
      test.setTimeout(90_000)
      const calls = []
      await mock(page, calls)
      await page.goto('/?tab=admin&g_tab=notifyGroups')
      const row = (id) => page.locator(`[data-group-id="${id}"]`).first()
      await expect(row(23)).toBeVisible({ timeout: 20_000 })
      for (const id of [21, 22, 23]) await row(id).locator('[data-slot="ng-select"]').click()
      const bar = page.locator('[data-slot="ng-bulk-bar"]')
      await expect(bar).toBeVisible()
      await inViewport(bar, vp.width)
      expect(await pageOverflow(page), 'sayfa yatay taşması').toBeLessThanOrEqual(1)
      if (vp.touch) {
        const bad = await page.evaluate(smallTargets, '[data-slot="ng-bulk-bar"]')
        expect(bad, `40 px altı hedefler: ${bad.join(', ')}`).toEqual([])
      }
      await bar.locator('[data-action="delete"]').click()
      const dlg = page.getByRole('dialog')
      await expect(dlg.locator('[data-slot="bulk-preview-row"]')).toHaveCount(3)
      await expect(dlg.locator('[data-slot="bulk-preview-row"][data-reason="is_default"]')).toHaveCount(1)
      await expect(dlg.locator('[data-slot="bulk-preview-row"][data-reason="in_use"]')).toHaveCount(1)
      await expect(dlg).toContainText(LONG_GROUP)
      await inViewport(dlg, vp.width)
      await page.waitForTimeout(400)
      expect(await page.evaluate(clippedText, '[role="dialog"] [data-slot="bulk-dialog"]'), 'kesilen ad').toEqual([])
      if (vp.touch) {
        const bad = await page.evaluate(smallTargets, '[role="dialog"]')
        expect(bad, `40 px altı hedefler (pencere): ${bad.join(', ')}`).toEqual([])
      }
      await page.screenshot({ path: `test-results/admin-bulk-groups-preview-${vp.name}.png` })
      await dlg.locator('[data-slot="bulk-confirm"]').click()
      await expect(dlg.locator('[data-slot="bulk-result-row"]')).toHaveCount(3)
      expect(calls).toEqual([['groups', { action: 'delete', ids: [21, 22, 23] }]])
    })

    test('takımlar kapsam panosu: görünüm, taşma yok, ad kesilmez, dokunma hedefleri; toplu varsayılan grup', async ({ page }) => {
      test.setTimeout(90_000)
      const calls = []
      await mock(page, calls)
      await page.goto('/?tab=admin&g_tab=teams&g_tview=board')
      const board = page.locator('[data-slot="tcb-board"]')
      await expect(board.locator('[data-slot="tcb-row"]')).toHaveCount(3, { timeout: 20_000 })
      await expect(board.locator('[data-slot="tcb-row"][data-team-id="1"] [data-slot="tcb-noc"]')).toHaveAttribute('data-state', 'all')
      await expect(board.locator('[data-slot="tcb-row"][data-team-id="2"] [data-slot="tcb-delivery"]')).toHaveAttribute('data-delivery', 'none')
      // Takım 3: yalnız KRİTİK eşikli kişi → kısmi; takım 2: kişi yok → boşluk
      await expect(board.locator('[data-slot="tcb-row"][data-team-id="3"] [data-slot="tcb-esc"]')).toHaveAttribute('data-state', 'partial')
      await expect(board.locator('[data-slot="tcb-row"][data-team-id="2"] [data-slot="tcb-esc"]')).toHaveAttribute('data-state', 'gap')
      const listWidth = await page.locator('[data-slot="tcb-list"]').evaluate((el) => el.clientWidth)
      await expect(page.locator('[data-slot="tcb-list"]')).toHaveAttribute('data-view', listWidth >= 1000 ? 'table' : 'cards')
      if (vp.width < 900) await expect(page.locator('[data-slot="tcb-list"]')).toHaveAttribute('data-view', 'cards')
      await page.waitForTimeout(300)
      expect(await pageOverflow(page), 'sayfa yatay taşması').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clippedText, '[data-slot="tcb-list"]'), 'kesilen ad').toEqual([])
      if (vp.touch) {
        const bad = await page.evaluate(smallTargets, '[data-slot="tcb-board"]')
        expect(bad, `40 px altı hedefler: ${bad.join(', ')}`).toEqual([])
      }
      // Pano satırından toplu düzeltme: iki takım seç → varsayılan grup penceresi (tek aday yok → seçim istenir)
      await board.locator('[data-slot="tcb-row"][data-team-id="2"] [data-slot="tcb-select"]').click()
      await board.locator('[data-slot="tcb-row"][data-team-id="1"] [data-slot="tcb-select"]').click()
      await page.locator('[data-testid="tm-bulk-bar"] [data-action="fix-default-group"]').click()
      const dlg = page.getByRole('dialog')
      await expect(dlg.locator('[data-slot="bulk-preview-row"]')).toHaveCount(2)
      await expect(dlg.locator('[data-slot="bulk-preview-row"][data-reason="unchanged"]')).toHaveCount(1)
      await expect(dlg.locator('[data-slot="bulk-preview-row"][data-reason="no_choice"]')).toHaveCount(1)
      await inViewport(dlg, vp.width)
      await page.waitForTimeout(400)
      expect(await page.evaluate(clippedText, '[role="dialog"] [data-slot="bulk-dialog"]'), 'kesilen ad (pencere)').toEqual([])
      if (vp.touch) {
        const bad = await page.evaluate(smallTargets, '[role="dialog"]')
        expect(bad, `40 px altı hedefler (pencere): ${bad.join(', ')}`).toEqual([])
      }
      await page.screenshot({ path: `test-results/admin-bulk-team-default-${vp.name}.png` })
      await dlg.locator('[data-slot="bulk-preview-row"][data-reason="no_choice"]').getByRole('combobox').click()
      await page.getByRole('option', { name: /Ödeme Yedek/ }).click()
      await dlg.locator('[data-slot="bulk-confirm"]').click()
      await expect(dlg.locator('[data-slot="bulk-result-row"]').first()).toBeVisible()
      expect(calls).toEqual([['groups', { action: 'make_default', ids: [23] }]])
      await dlg.locator('[data-slot="bulk-close"]').click()
      // Tam sayfa görüntüsü EN SONDA (fullPage dokunmatik öykünmesini sıfırlar)
      await page.screenshot({ path: `test-results/admin-bulk-board-${vp.name}.png`, fullPage: true })
    })
  })
}

// İnceleme #3 (2026-11-13): geniş ekranda pano TABLO dalı kabını taşmaz (sütunlar kap içinde %100; sabit px sütun yok).
for (const width of [1440, 1920]) {
  test.describe(`Takımlar kapsam panosu tablo — ${width}`, () => {
    test.use({ viewport: { width, height: 900 } })

    test('tablo dalı: kap taşmaz, ad kesilmez', async ({ page }) => {
      test.setTimeout(60_000)
      await mock(page, [])
      await page.goto('/?tab=admin&g_tab=teams&g_tview=board')
      const list = page.locator('[data-slot="tcb-list"]')
      await expect(page.locator('[data-slot="tcb-row"]')).toHaveCount(3, { timeout: 20_000 })
      await expect(list).toHaveAttribute('data-view', 'table')
      await page.waitForTimeout(300)
      const over = await page.evaluate(() => {
        const out = []
        const box = document.querySelector('[data-slot="tcb-list"]')
        for (const el of [box, ...box.querySelectorAll('[data-slot="table-container"], table, td, th')]) {
          if (el.scrollWidth > el.clientWidth + 1) out.push(`${el.tagName.toLowerCase()} ${el.scrollWidth}>${el.clientWidth}`)
        }
        return out
      })
      expect(over, 'taşan tablo öğesi').toEqual([])
      expect(await pageOverflow(page), 'sayfa yatay taşması').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clippedText, '[data-slot="tcb-list"]'), 'kesilen ad').toEqual([])
      await page.screenshot({ path: `test-results/admin-bulk-board-table-${width}.png`, fullPage: true })
    })
  })
}
