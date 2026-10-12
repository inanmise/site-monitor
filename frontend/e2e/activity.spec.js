// Kayıtlar → Aktivite yeniden tasarımı (2026-10-11, kullanıcı isteği: "mevcut fonksiyonaliteyi bozmadan daha profesyonel,
// shadcn, mweb responsive"). Gerçek tarayıcıda 390 (dokunmatik) / 768 (dokunmatik) / 1280: sayfa yatay taşmaz; liste
// kap genişliğine göre kart/tablo; uzun ad ve hedef KESİLMEZ; zaman çizelgesi çizilir; tür çipleri telefonda katlanır;
// süzgeç çipi eklenir/kaldırılır; dokunma hedefleri ≥ 40 px. API mock'lu.
import { test, expect } from '@playwright/test'

const LONG = 'Ödeme Sistemleri Entegrasyon Sağlık Kontrolü — Üretim Ortamı (Bölge 2 / Yedek Hat)'
const TARGET = 'https://odeme-entegrasyon-saglik.example.com/api/v2/health/deep?kontrol=tam'
const now = Date.now()
const iso = (msAgo) => new Date(now - msAgo).toISOString().slice(0, 19)

const ROWS = [
  { id: 1, monitor_type: 'HTTP', monitor_id: 4, monitor_name: LONG, target: TARGET, action: 'SCHEDULED_CHECK', result_status: 'ERROR',
    result_summary: '503 · 1200ms', error_message: 'HTTP 503 Service Unavailable', activity_time: iso(60_000), team_id: 1, response_ms: 1200 },
  { id: 2, monitor_type: 'CERT', monitor_name: 'odeme.example.com', target: 'odeme.example.com', action: 'SCHEDULED_CHECK', result_status: 'WARNING',
    result_summary: '12d', activity_time: iso(3_600_000), team_id: 1, days_remaining: 12 },
  { id: 3, monitor_type: 'PING', monitor_id: 9, monitor_name: 'ag-gecidi', target: '10.0.0.1', action: 'SCHEDULED_CHECK', result_status: 'SUCCESS',
    result_summary: '0% kayıp · 2ms', activity_time: iso(7_200_000), team_id: 1 },
]

function series() {
  const points = []
  const start = new Date(now); start.setUTCMinutes(0, 0, 0)
  for (let i = 24; i >= 0; i--) {
    const t = new Date(start.getTime() - i * 3_600_000).toISOString().slice(0, 19) + 'Z'
    points.push({ t, success: 20 + (i % 5), warning: i % 4 === 0 ? 2 : 0, error: i % 7 === 0 ? 3 : 0, unknown: 0 })
  }
  return { bucket: 'hour', points }
}

async function mock(page) {
  await page.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const url = new URL(route.request().url())
    const p = url.pathname
    let body = { success: true, data: [] }
    if (p === '/api/me') {
      body = { success: true, username: 'admin', system_role: 'ADMIN', global_admin: true, bootstrap_admin: true,
        team_id: null, team_ids: [], tour: { status: 'dismissed', version: 99 } }
    } else if (p === '/api/activity') {
      const type = url.searchParams.get('type') || ''
      const data = type ? ROWS.filter((r) => type.split(',').includes(r.monitor_type)) : ROWS
      body = { success: true, data, total: data.length, page: 0, size: 50, total_pages: 1 }
    } else if (p === '/api/activity/summary') {
      body = { success: true, total: 3, success_count: 1, warning: 1, error: 1, unknown: 0, last_activity: ROWS[0].activity_time,
        type_counts: { HTTP: 41, CERT: 12, PING: 230, DNS: 0 }, series: series() }
    } else if (p.startsWith('/api/activity/')) {
      body = { success: true, data: ROWS[0], recent: [] }
    } else if (p === '/api/teams/directory') {
      body = { success: true, data: [{ id: 1, name: 'Ödeme Sistemleri ve Dijital Kanallar Platform Ekibi' }] }
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

function overflowIn(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const vw = document.documentElement.clientWidth
  const out = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.width > 1 && r.right > vw + 1) out.push(`${el.tagName.toLowerCase()}.${String(el.className).slice(0, 30)} ${Math.round(r.right)}`)
    if (out.length > 5) break
  }
  return out
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

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Aktivite — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('yerleşim, kesilmeyen ad, çizelge, süzgeç çipleri', async ({ page }) => {
      test.setTimeout(90_000)
      await mock(page)
      await page.goto('/?tab=activity')
      const root = page.locator('[data-slot="activity-log"]')
      await expect(root.locator('[data-slot="act-item"]').first()).toBeVisible({ timeout: 20_000 })
      if (vp.touch) expect(await page.evaluate(() => matchMedia('(pointer: coarse)').matches)).toBe(true)

      // Liste: telefonda kart, 1280'de tablo
      const layout = await root.locator('[data-slot="act-feed"]').getAttribute('data-layout')
      if (vp.width === 390) expect(layout).toBe('cards')
      if (vp.width === 1280) expect(layout).toBe('table')

      // Zaman çizelgesi gerçek tarayıcıda çubuk çizer
      const tl = root.locator('[data-slot="act-timeline"]')
      await expect(tl).toHaveAttribute('data-bucket', 'hour')
      await expect(tl.locator('.recharts-bar-rectangle').first()).toBeVisible()

      // Uzun ad ve hedef tam ve kırpılmadan
      const first = root.locator('[data-slot="act-item"]').first()
      await expect(first).toContainText(LONG)
      await expect(first).toContainText(TARGET)
      const clipped = await first.evaluate((el) => [...el.querySelectorAll('span, a, button')]
        .filter((n) => n.childElementCount === 0 && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis'))
        .map((n) => n.textContent.slice(0, 30)))
      expect(clipped, 'kırpılan metin').toEqual([])

      expect(await page.evaluate(overflowIn, '[data-slot="activity-log"]'), 'yatay taşma').toEqual([])
      await page.screenshot({ path: `test-results/activity-${vp.name}.png`, fullPage: false })

      // Tür çipleri: telefonda katlanır düğme; açılınca çipler (sayılı)
      const toggle = root.locator('[data-slot="act-types-toggle"]')
      // Süzgeç kartı 640 px'ten darsa (telefon; kenar çubuğu açık tablet) çipler katlanır; 1280'de satır içi
      if (vp.width === 390) await expect(toggle).toBeVisible()
      if (vp.width === 1280) await expect(toggle).toHaveCount(0)
      if (await toggle.count()) {
        await toggle.click()
        await expect(toggle).toHaveAttribute('aria-expanded', 'true')
      }
      const http = root.locator('[data-slot="act-type-chips"] [data-type="HTTP"]')
      await expect(http).toBeVisible()
      await expect(http.locator('[data-slot="act-type-count"]')).toHaveText('41')
      await http.click()
      const chip = root.locator('[data-slot="act-active-filters"] [data-slot="act-chip"][data-kind="type"]')
      await expect(chip).toHaveText(/HTTP/)
      await expect(root.locator('[data-slot="act-item"]')).toHaveCount(1)
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="act-filters"]'), 'küçük hedef').toEqual([])
      expect(await page.evaluate(overflowIn, '[data-slot="activity-log"]'), 'süzgeç sonrası taşma').toEqual([])
      await chip.getByRole('button').click()
      await expect(root.locator('[data-slot="act-active-filters"]')).toHaveCount(0)
      await expect(root.locator('[data-slot="act-item"]')).toHaveCount(3)

      // Satır ayrıntısı açılır
      if (layout === 'cards') await first.locator('button[aria-expanded]').first().click()
      else await first.click()
      await expect(page.locator('[data-slot="act-detail"]').first()).toBeVisible()
      expect(await page.evaluate(overflowIn, '[data-slot="activity-log"]'), 'ayrıntı açıkken taşma').toEqual([])
    })
  })
}
