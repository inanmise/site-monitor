// Ayarlar → Anahtar Çözümleme → Anahtar döndürme (2026-10-11, kullanıcı isteği: "Gizli anahtar döndürme"). Gerçek
// tarayıcıda 390 (dokunmatik) / 768 (dokunmatik) / 1280: bölüm yatay taşmaz, uzun kayıt adı kesilmez, dokunma hedefleri
// ≥ 40 px; onay penceresi ekrana sığar; "Yeniden şifrele" sonrası sonuç bandı ve "done" aşaması. API mock'lu.
import { test, expect } from '@playwright/test'

const LONG = 'Ödeme Sistemleri Entegrasyon Sağlık Kontrolü — Üretim Ortamı (Bölge 2 / Yedek Hat) (#42)'
const LOCS = [
  { id: 'ldap_bind_password', label: 'LDAP bind parolası', column: 'ldap_settings.bind_password_enc', rows: 1, current: 0, previous: 1, unreadable: 0 },
  { id: 'smtp_password', label: 'SMTP parolası', column: 'smtp_settings.password_enc', rows: 1, current: 1, previous: 0, unreadable: 0 },
  { id: 'http_basic_auth', label: 'HTTP izleme — Basic auth parolası', column: 'http_monitors.basic_auth_pass_enc', rows: 3,
    current: 1, previous: 2, unreadable: 1, unreadable_records: [LONG] },
  { id: 'scripted_versions', label: 'Senaryo sürüm geçmişi — gizli ortam değişkenleri', column: 'scripted_script_versions.env_json', rows: 0, current: 0, previous: 0, unreadable: 0 },
]
const PENDING = {
  key: { current_configured: true, previous_configured: true, previous_same_as_current: false },
  totals: { values: 6, current: 2, previous: 3, unreadable: 1 },
  previous_removable: false, can_reencrypt: true, locations: LOCS,
}
const DONE = {
  ...PENDING,
  totals: { values: 6, current: 5, previous: 0, unreadable: 1 },
  previous_removable: true, can_reencrypt: false,
  locations: LOCS.map((l) => ({ ...l, current: l.current + l.previous, previous: 0 })),
}

async function mock(page) {
  await page.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  let reencrypted = false
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const p = new URL(route.request().url()).pathname
    let body = { success: true, data: [] }
    if (p === '/api/me') {
      body = { success: true, username: 'admin', system_role: 'ADMIN', global_admin: true, bootstrap_admin: true,
        team_id: null, team_ids: [], tour: { status: 'dismissed', version: 99 } }
    } else if (p === '/api/admin/secret-tools/info') {
      body = { success: true, dev_default_key: 'certmonitor-dev-secret-change-me', secret_key_set: true, previous_key_set: true }
    } else if (p === '/api/admin/secret-tools/rotation') {
      body = { success: true, data: reencrypted ? DONE : PENDING }
    } else if (p === '/api/admin/secret-tools/rotation/reencrypt') {
      reencrypted = true
      body = { success: true, data: { values_rewritten: 3, rows_rewritten: 2, changed_meanwhile: 0, unreadable: 1, status: DONE } }
    }
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
    if (r.height < 39.5) out.push(`${b.tagName.toLowerCase()} "${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(r.height)}`)
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
  test.describe(`Gizli anahtar döndürme — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('durum sığar, uzun ad kesilmez; yeniden şifrele → onay → sonuç + "done"', async ({ page }) => {
      test.setTimeout(90_000)
      await mock(page)
      await page.goto('/?tab=settings&sec=secrets')
      const panel = page.locator('[data-slot="secret-rotation"]')
      await expect(panel).toHaveAttribute('data-phase', 'pending', { timeout: 20_000 })
      await panel.scrollIntoViewIfNeeded()
      await expect(panel.locator('[data-slot="rotation-location"]')).toHaveCount(3)
      expect(await page.evaluate(overflowIn, '[data-slot="secret-rotation"]'), 'bölüm taşması').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="secret-rotation"]'), 'küçük hedef').toEqual([])
      const unread = panel.locator('[data-slot="rotation-unreadable"] li')
      await expect(unread).toHaveText(LONG)
      expect(await unread.evaluate((el) => el.scrollWidth - el.clientWidth), 'ad kırpılmış').toBeLessThanOrEqual(1)
      await panel.screenshot({ path: `test-results/secret-rotation-${vp.name}.png` })

      await panel.locator('[data-slot="rotation-reencrypt"]').click()
      const dlg = page.getByRole('dialog')
      await expect(dlg).toBeVisible()
      await page.waitForTimeout(300)
      const box = await dlg.boundingBox()
      expect(box.x).toBeGreaterThanOrEqual(-1)
      expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
      await dlg.getByRole('button', { name: /Re-encrypt|Yeniden şifrele/ }).click()
      await expect(panel).toHaveAttribute('data-phase', 'done')
      await expect(panel.locator('[data-slot="rotation-result"]')).toBeVisible()
      await expect(panel.locator('[data-slot="rotation-reencrypt"]')).toBeDisabled()
      expect(await page.evaluate(overflowIn, '[data-slot="secret-rotation"]'), 'sonuçtan sonra taşma').toEqual([])
    })
  })
}
