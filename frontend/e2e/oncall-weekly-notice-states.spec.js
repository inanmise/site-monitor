// Haftalık nöbet bildirimi — son sonucun DURUMLARI (2026-11-17, inceleme: gönderildi / e-posta kuyrukta / kısmen +
// yeniden denenecek / gönderilemedi + yeniden denenmeyecek / sonuç bilinmiyor — e2e kapsamı yoktu). Gerçek tarayıcıda
// 390 (dokunmatik) / 768 (dokunmatik) / 1280: her durumun rozeti (`data-status`), nedeni, yeniden deneme satırı ve kanal
// satırı görünür; kuyruktaki e-posta "Gönderildi" diye yeşil OKUNMAZ; uzun metinler sarar, bölüm pencereden taşmaz.
// API mock'lu.
import { test, expect } from '@playwright/test'

const LONG_A = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'
const TEAMS = [
  { id: 1, name: 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi', active: true, leader_id: 2, manager_id: null, email: 'dk@example.com' },
]
const USERS = [
  { id: 2, username: 'KULLANICI_A', display_name: LONG_A, employee_id: '100002', email: 'a@example.com', system_role: 'USER', team_id: 1, team_ids: [1], active: true },
]
const BASE_VIEW = {
  team_id: 1, configured: true, enabled: true, rotation: 'WEEKLY', anchor: '2026-10-05T09:00', notify_oncall: false, can_edit: true,
  members: [{ user_id: 2, name: LONG_A, active: true }],
  current: { user_id: 2, name: LONG_A, starts_at: '2026-10-05T06:00:00', ends_at: '2026-10-12T06:00:00', override: false, active: true },
  upcoming: [{ user_id: 2, name: LONG_A, starts_at: '2026-10-05T06:00:00', ends_at: '2026-10-12T06:00:00' }],
  overrides: [],
  weekly_notice: { enabled: true, mail: true, push: true, day: 1, time: '08:30' },
  weekly_notice_next: '2026-11-23T05:30:00',
}

// Sunucunun gönderebileceği son sonuç biçimleri (OnCallService.lastView) → ekranın beklenen durumu
const STATES = [
  {
    name: 'gönderildi', expect: 'SENT', text: /^Sent$|^Gönderildi$/,
    last: { week: '2026-11-16', status: 'SENT', reason: null, at: '2026-11-16T05:30:04', mail: 'delivered', push: 'delivered', push_count: 7, attempts: 1, max_attempts: 3, retry: false, stale: false },
    channels: /sent to 7 people|7 kişiye gönderildi/,
  },
  {
    name: 'e-posta kuyrukta', expect: 'QUEUED', text: /queued|kuyrukta/,
    last: { week: '2026-11-16', status: 'SENT', reason: null, at: '2026-11-16T05:30:04', mail: 'queued', push: 'delivered', push_count: 3, attempts: 1, max_attempts: 3, retry: false, stale: false },
    channels: /mail server will retry|posta sunucusu yeniden deneyecek/,
  },
  {
    name: 'kısmen + yeniden denenecek', expect: 'PARTIAL', text: /Partly sent|Kısmen gönderildi/,
    last: { week: '2026-11-16', status: 'PARTIAL', reason: null, at: '2026-11-16T05:30:04', mail: 'failed', push: 'delivered', push_count: 2, attempts: 1, max_attempts: 3, retry: true, stale: false },
    retry: 'pending', retryText: /attempt 1 of 3|deneme 1\/3/,
  },
  {
    name: 'gönderilemedi + yeniden denenmeyecek', expect: 'FAILED', text: /Could not be sent|Gönderilemedi/,
    last: { week: '2026-11-16', status: 'FAILED', reason: 'ERROR', at: '2026-11-16T06:10:00', mail: 'failed', push: 'failed', push_count: 0, attempts: 3, max_attempts: 3, retry: false, stale: false },
    retry: 'done', reason: /application error|uygulama hatası/,
  },
  {
    name: 'sonuç bilinmiyor (yarıda kaldı)', expect: 'UNKNOWN', text: /Result unknown|Sonuç bilinmiyor/,
    last: { week: '2026-11-16', status: 'SENDING', reason: null, at: null, mail: null, push: null, push_count: null, attempts: 1, max_attempts: 3, retry: null, stale: true },
    reason: /stopped halfway|yarıda kaldı/,
  },
]

async function mock(page, view) {
  await page.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const p = new URL(route.request().url()).pathname
    let body = { success: true, data: [] }
    if (p === '/api/me') {
      body = { success: true, username: 'demo', system_role: 'ADMIN', global_admin: true, team_id: 1, team_ids: [1], tour: { status: 'dismissed', version: 99 } }
    } else if (p === '/api/admin/teams') body = { success: true, data: TEAMS }
    else if (p === '/api/admin/users' || p === '/api/admin/teams/1/users') body = { success: true, data: USERS }
    else if (p === '/api/oncall/current') body = { success: true, data: [{ team_id: 1, user_id: 2, name: LONG_A, until: '2026-10-12T06:00:00', active: true, override: false }] }
    else if (p === '/api/oncall/teams/1') body = { success: true, data: view }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
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

async function openSection(page) {
  await page.goto('/?tab=admin&g_tab=teams')
  const root = page.locator('[data-slot="team-manager"]')
  await expect(root.locator('[data-slot="team-oncall"]').first()).toContainText(LONG_A, { timeout: 20_000 })
  await root.locator('[data-kebab]').first().click()
  await page.getByRole('menuitem', { name: /On-call schedule|Nöbet çizelgesi/ }).click()
  const sec = page.locator('[data-slot="oncall-weekly-notice"]')
  await expect(sec).toBeVisible()
  await sec.scrollIntoViewIfNeeded()
  return sec
}

for (const vp of VIEWPORTS) {
  test.describe(`Haftalık nöbet bildirimi — son sonuç durumları ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    for (const s of STATES) {
      test(`${s.name}: rozet, neden / yeniden deneme / kanal satırı sığar`, async ({ page }) => {
        test.setTimeout(120_000)
        await mock(page, { ...BASE_VIEW, weekly_notice_last: s.last })
        const sec = await openSection(page)
        const last = sec.locator('[data-slot="oncall-wn-last"]')
        await expect(last).toHaveAttribute('data-status', s.expect)
        await expect(last.locator('[data-slot="badge"]').first()).toHaveText(s.text)
        // Kuyruktaki e-posta yeşil "Gönderildi" değildir; sonuç bilinmiyor da yeşil değildir
        if (s.expect === 'QUEUED' || s.expect === 'UNKNOWN') {
          await expect(last.locator('[data-slot="badge"]').first()).not.toHaveText(/^Sent$|^Gönderildi$/)
        }
        if (s.retry) {
          const retry = last.locator('[data-slot="oncall-wn-retry"]')
          await expect(retry).toHaveAttribute('data-retry', s.retry)
          if (s.retryText) await expect(retry).toContainText(s.retryText)
        } else {
          await expect(last.locator('[data-slot="oncall-wn-retry"]')).toHaveCount(0)
        }
        if (s.reason) await expect(last).toContainText(s.reason)
        if (s.channels) await expect(last.locator('[data-slot="oncall-wn-channels"]')).toContainText(s.channels)

        const dialog = page.getByRole('dialog')
        const box = await dialog.boundingBox()
        expect(box.x).toBeGreaterThanOrEqual(-1)
        expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
        expect(await page.evaluate(overflowX, '[data-slot="oncall-weekly-notice"]'), 'taşan öğe').toEqual([])
        expect(await page.evaluate(clipped, '[data-slot="oncall-weekly-notice"]'), 'kırpılan metin').toEqual([])
        if (s.expect === 'PARTIAL') await sec.screenshot({ path: `test-results/oncall-wn-state-${vp.name}.png` })
      })
    }
  })
}
