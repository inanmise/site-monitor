// Sistem Sağlığı → Kendi kendini izleme (2026-10-11, kullanıcı isteği). Gerçek tarayıcıda 390 (dokunmatik) / 768
// (dokunmatik) / 1280: bölüm en üstte; açılınca kontrol satırları, kanal özeti ve dış izleyici adresi sığar, yatay taşma
// yok, dokunma hedefleri ≥ 40 px; onaylı kritik sorun genel bandı kritiğe çeker. API mock'lu.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const now = Date.now()
const iso = (msAgo) => new Date(now - msAgo).toISOString().slice(0, 19)
const C = (key, label, over = {}) => ({ key, label, problem_text: 'Açıklama', default_severity: 'HIGH', status: 'OK', last_result: 'OK',
  severity: null, since: iso(3 * 3_600_000), evaluated_at: iso(30_000), detail: `${label}: ölçüm normal aralıkta.`, facts: {}, ...over })
const WATCHDOG = {
  status: 'DOWN', enabled: true, stale: false, interval_seconds: 60,
  checks: [
    C('sweep_leader', 'Tarama liderliği', { status: 'PROBLEM', last_result: 'PROBLEM', severity: 'CRITICAL', since: iso(12 * 60_000),
      detail: 'Liderlik kirası 8 dk önce doldu ve hiçbir pod devralmadı.', notified_at: iso(11 * 60_000), notified_kind: 'ALERT' }),
    C('monitor_sweep', 'İzleme taramaları', { last_result: 'PROBLEM',
      detail: 'Zamanında kontrol edilmeyen izlemeler (gecikmiş/etkin): http 12/20, keyword 9/11, pagespeed 4/6, scripted 7/9' }),
    C('cert_sweep', 'Sertifika taraması'),
    C('notifications', 'Bildirim gönderimi'),
    C('push_outbox', 'Push kuyruğu', { last_result: 'NA', detail: 'Uygulanmaz: kişisel push kapalı.' }),
    C('database', 'Veritabanı'),
  ],
  channels: { email_recipients: 3, push_users: 2, webhook: true, reminder_hours: 6, confirm_checks: 2 },
}

async function mock(page) {
  await mockApi(page)
  await page.route((u) => new URL(u).pathname === '/api/admin/system/watchdog', (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: WATCHDOG }),
  }))
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

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0) continue
    if (r.height < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(r.height)}`)
  }
  return out
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Kendi kendini izleme — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('bölüm en üstte, kritik sorun bandı, satırlar sığar ve kesilmez', async ({ page }) => {
      test.setTimeout(90_000)
      await mock(page)
      await page.goto('/?tab=health&sec=watchdog')
      const sec = page.locator('[data-slot="watchdog-section"]')
      await expect(sec).toHaveAttribute('data-status', 'DOWN', { timeout: 30_000 })
      await expect(page.locator('[data-slot="health-overall"]')).toHaveAttribute('data-level', 'down')
      const sections = await page.locator('[data-section]').evaluateAll((els) => els.map((e) => e.getAttribute('data-section')))
      expect(sections[0]).toBe('watchdog')
      await expect(sec.locator('[data-slot="watchdog-check"]')).toHaveCount(6)
      await expect(sec.locator('[data-key="sweep_leader"]')).toHaveAttribute('data-state', 'problem')
      await sec.scrollIntoViewIfNeeded()
      expect(await page.evaluate(overflowIn, '[data-slot="watchdog-section"]'), 'yatay taşma').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="watchdog-section"]'), 'küçük hedef').toEqual([])
      const clipped = await sec.evaluate((root) => [...root.querySelectorAll('[data-slot="watchdog-check"] p, [data-slot="watchdog-check"] [role="heading"]')]
        .filter((n) => n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis').map((n) => n.textContent.slice(0, 30)))
      expect(clipped, 'kırpılan metin').toEqual([])
      await expect(sec.locator('[data-slot="watchdog-public-url"]')).toContainText('/api/public/health/watchdog')
      await sec.screenshot({ path: `test-results/watchdog-${vp.name}.png` })
    })
  })
}
