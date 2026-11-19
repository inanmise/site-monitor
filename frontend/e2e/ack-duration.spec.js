// Süreli alarm onayı (2026-11-19, kullanıcı kararı: "Süresiz + hazır süreler"). Gerçek tarayıcıda 390 (dokunmatik) / 768
// (dokunmatik) / 1280: onay penceresindeki süre seçici sığar (yatay taşma yok, metin kırpılmaz), dokunmatik hedefler ≥ 40 px,
// varsayılan "Süresiz"; seçilen süre `duration_minutes` olarak isteğe gider, yanıtın `ack_expires_at` değeri kartta "… sonra
// düşer" olarak görünür. API mock'lu (bildirim gönderilmez).
import { test, expect } from '@playwright/test'

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const LONG_NAME = 'https://kurumsal-internet-bankaciligi-odeme-servisleri.uzun-alan-adi.example.com/api/v2/health/check'

const iso = (ms) => new Date(Date.now() + ms).toISOString().slice(0, 19)

const ROW = {
  id: 1, status: 'ongoing', monitor: { name: LONG_NAME, type: 'http', tab: 'http', monitor_id: 9 },
  root_cause: { code: '503', category: 'server_error' }, comment_count: 0, alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL',
  started_at: iso(-3600_000), resolved_at: null, resolved_by: null, acknowledged: false, domain: LONG_NAME,
  message: 'HTTP 503 Service Unavailable', team_id: 5, team_name: LONG_TEAM, can_manage: true, can_act: true,
  can_comment: true, can_delete: false, can_notify: true, recurrence_7d: null, realert_count: 0,
  last_re_alert_at: iso(-3000_000), resolved_silently: false, in_maintenance: false, owner_team_ids: [5], storm_id: null,
}
const SUMMARY = {
  open: 1, acknowledged: 0, critical: 1, teamless: 0, mine: 1, resolved_24h: 0,
  mtta_24h_minutes: null, mtta_7d_minutes: null, mttr_24h_minutes: null, mttr_7d_minutes: null, mtta_sample_7d: 0, mttr_sample_7d: 0,
  timing_capped: false, longest_open: null, team_options: [{ id: 5, name: LONG_TEAM, count: 1 }],
}

let ackBody = null
async function mock(page) {
  ackBody = null
  let acked = false
  await page.addInitScript(() => {
    try {
      localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 }))
      localStorage.setItem('incidents-banner-dismissed', 'true')
    } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    let body = { success: true, data: [] }
    const expires = iso(4 * 3600_000)
    const row = acked ? { ...ROW, acknowledged: true, acknowledged_by: 'Demo', ack_expires_at: expires } : ROW
    if (p === '/api/me') body = { success: true, username: 'demo', display_name: 'Demo', system_role: 'USER', team_id: 5, team_ids: [5], tour: { status: 'dismissed', version: 99 } }
    else if (p === '/api/me/permissions') body = { success: true, data: { 'monitoring.read': { view: true }, 'alerts.read': { view: true }, 'alerts.actions': { execute: true } } }
    else if (p === '/api/monitoring/incidents/summary') body = { success: true, data: SUMMARY }
    else if (p === '/api/monitoring/incidents') body = { success: true, data: [row], total: 1, type_counts: { HTTP_DOWN: 1 }, server_facets: true }
    else if (/^\/api\/admin\/alerts\/\d+\/acknowledge$/.test(p) && req.method() === 'POST') {
      ackBody = JSON.parse(req.postData() || '{}')
      acked = true
      body = { success: true, data: { id: 1, acknowledged: true, acknowledged_by: 'Demo', ack_expires_at: expires } }
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

function smallTargets(sel) {
  const out = []
  for (const root of document.querySelectorAll(sel)) {
    for (const b of root.querySelectorAll('button, select, input, a[href]')) {
      const r = b.getBoundingClientRect()
      if (r.width === 0 || r.height === 0 || b.disabled) continue
      let h = r.height
      const after = getComputedStyle(b, '::after')
      if (after.position === 'absolute' && after.height && after.height !== 'auto') h = Math.max(h, parseFloat(after.height) || 0)
      if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(h)}`)
    }
  }
  return out
}

function clipped(sel) {
  const out = []
  for (const root of document.querySelectorAll(sel)) {
    for (const n of root.querySelectorAll('span, p, li, label, button')) {
      if (n.closest('.sr-only')) continue
      if (n.childElementCount === 0 && n.textContent.trim().length > 1
        && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')) out.push(n.textContent.slice(0, 30))
    }
  }
  return out
}

const overflow = (page) => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Süreli onay — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('süre seçici sığar; 4 sa seçimi isteğe gider; kartta kalan süre görünür', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      if (vp.name === 'phone') {
        await page.goto('/?tab=incidents&view=list')
        const quick = page.locator('[data-slot="incident-card"][data-incident-id="1"] [data-slot="incident-quick-actions"]')
        await expect(quick).toBeVisible({ timeout: 20_000 })
        await quick.getByRole('button', { name: /^(Acknowledge|Onayla)/ }).click()
      } else {
        await page.goto('/?tab=incidents')
        await page.locator('[data-slot="incident-card"][data-incident-id="1"] [data-incident-open]').click({ timeout: 20_000 })
        const sheet = page.locator('[data-slot="incident-detail"]')
        await sheet.getByRole('button', { name: /^(Acknowledge|Onayla)$/ }).click({ timeout: 20_000 })
      }
      const dlg = page.locator('[data-action-note]')
      const box = dlg.locator('[data-slot="action-note-ack-duration"]')
      await expect(box).toBeVisible()
      const group = box.getByRole('radiogroup')
      await expect(group.getByRole('radio')).toHaveCount(6)
      await expect(group.locator('[data-duration="none"]')).toHaveAttribute('aria-checked', 'true')
      await page.waitForTimeout(400)   // açılış animasyonu
      expect(await overflow(page), 'yatay taşma').toBeLessThanOrEqual(1)
      const bb = await dlg.boundingBox()
      expect(bb.x).toBeGreaterThanOrEqual(-1)
      expect(bb.x + bb.width).toBeLessThanOrEqual(vp.width + 1)
      expect(await page.evaluate(clipped, '[data-slot="action-note-ack-duration"]'), 'kırpılan metin').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="action-note-ack-duration"]'), 'küçük hedef').toEqual([])

      await group.locator('[data-duration="240"]').click()
      await expect(group.locator('[data-duration="240"]')).toHaveAttribute('aria-checked', 'true')
      await expect(box.locator('[data-slot="action-note-ack-duration-hint"]')).toContainText(/expires at|kendiliğinden düşer/)
      await dlg.screenshot({ path: `test-results/ack-duration-dialog-${vp.name}.png` })
      await dlg.getByRole('textbox', { name: /^(Reason|Gerekçe)/ }).fill('Bakım penceresine kadar izliyoruz')
      await dlg.locator('[data-action-submit]').click()
      await expect.poll(() => ackBody).toEqual({ note: 'Bakım penceresine kadar izliyoruz', duration_minutes: 240 })
      await expect(dlg).toHaveCount(0)

      const card = page.locator('[data-slot="incident-card"][data-incident-id="1"]')
      const expiry = card.locator('[data-slot="ack-expiry"]')
      await expect(expiry).toBeVisible({ timeout: 20_000 })
      await expect(expiry).toHaveAttribute('data-expired', 'false')
      expect(await overflow(page), 'yatay taşma (kart)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="incident-card"]'), 'kırpılan metin (kart)').toEqual([])
    })
  })
}
