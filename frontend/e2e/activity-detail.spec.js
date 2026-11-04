// Aktivite AYRINTI SAYFASI (2026-11-05, kullanıcı isteği: "çok daha fazla detay istediğimde ilgili aktivite ile gitmek
// istiyorum"; shadcn, en üst düzey kullanıcı deneyimi, mobil 390 / 768 dokunmatik + 1280 masaüstü). Gerçek tarayıcıda,
// API mock'lu, izole harness üzerinden (e2e/harness/activity-detail.html — sayfayı `?tab=activity&act=<id>` rotasına liste
// bağlar; o dosya act fe2 dalında). Ölçülenler: yatay taşma yok, uzun ad / hedef kesilmez, kıyas hücreleri geniş kapta yan
// yana / dar kapta alt alta, grafik çizilir (bu olay kesikli çizgi), alarm zinciri, dokunma hedefleri ≥ 40 px, komşu
// aktiviteye geçiş, geri, 404 durumu.
import { test, expect } from '@playwright/test'

const LONG = 'Ödeme Sistemleri Entegrasyon Sağlık Kontrolü — Üretim Ortamı (Bölge 2 / Yedek Hat) — İstanbul Veri Merkezi Ağ Geçidi'
const TARGET = 'odeme-entegrasyon-saglik-kontrolu-uretim-bolge2-yedek.example.com'
const TEAM = 'Ödeme Sistemleri ve Dijital Kanallar Platform Ekibi (Ağ ve Altyapı Operasyonları)'

const H = 3_600_000
const anchor = Date.UTC(2026, 10, 5, 10, 0, 0)
const iso = (ms) => new Date(ms).toISOString().slice(0, 19)

function seriesPoints() {
  const pts = []
  for (let i = -24 * 6; i <= 24 * 6; i++) {
    const t = anchor + i * 10 * 60_000
    const fail = i >= 0 && i <= 2
    const gap = i > 40 && i < 50
    pts.push({ t: iso(t), ms: fail || gap ? null : 20 + ((i * 7) % 30), status: fail ? 'ERROR' : gap ? 'UNKNOWN' : (i === -30 ? 'WARNING' : 'SUCCESS') })
  }
  return pts.filter((_, k) => k % 1 === 0).slice(0, 290)
}

function detail(id) {
  const prevId = id - 1
  return {
    success: true,
    data: {
      id, activity_time: iso(anchor), monitor_type: 'PING', monitor_id: 9, monitor_name: id === 42 ? LONG : `Komşu aktivite ${id}`,
      target: TARGET, action: 'SCHEDULED_CHECK', result_status: 'ERROR', result_summary: '%100 kayıp · zaman aşımı',
      error_message: 'Request timed out after 5000 ms', error_class: 'NETWORK', team_id: 1, team_name: TEAM,
      failure_reason: 'ICMP_NO_REPLY', failure_detail: { phase: 'NETWORK', target: TARGET, packet_loss: 100, timeout_ms: 5000 },
      alert_event_id: 5, check_id: 7, http_status: null, request_id: null,
    },
    check: { type: 'ping', match: 'exact', row: { id: 7, up: false, rtt_ms: null, packet_loss: 100, failure_reason: 'ICMP_NO_REPLY',
      failure_detail: JSON.stringify({ phase: 'NETWORK', target: TARGET, packet_loss: 100, timeout_ms: 5000 }), error: 'Request timed out after 5000 ms',
      checked_at: iso(anchor) } },
    previous_check: { id: 6, up: true, rtt_ms: 23, checked_at: iso(anchor - 5 * 60_000), activity_id: prevId },
    next_check: { id: 8, up: true, rtt_ms: 31, checked_at: iso(anchor + 5 * 60_000) },
    series: { from: iso(anchor - 24 * H), to: iso(anchor + 24 * H), truncated: false, points: seriesPoints() },
    alarm: { id: 5, alert_type: 'PING_DOWN', level: 'CRITICAL', opened_at: iso(anchor + 90_000), resolved_at: iso(anchor + 6 * 60_000),
      resolved_by_kind: 'auto', acknowledged: true, storm_id: null, match: 'exact', can_open: true },
    change: null,
    links: {
      monitor: { tab: 'ping', params: { monitor: 9 } }, history: { tab: 'ping', params: { monitor: 9, mtab: 'control' } },
      changes: { tab: 'ping', params: { monitor: 9, mtab: 'changes' } }, alert: { tab: 'alerthistory', params: { alert: 5 } },
      can_diagnose: true,
    },
    actor: { name: 'scheduler', request_id: '7f3a9c1e-55b2-4c8d-9e0f-a1b2c3d4e5f6' },
  }
}

const ALERT = { id: 5, alert_type: 'PING_DOWN', alert_level: 'CRITICAL', domain: TARGET, created_at: iso(anchor + 90_000),
  resolved: true, resolved_at: iso(anchor + 6 * 60_000), resolved_by: 'Sistem (otomatik)', acknowledged: true,
  acknowledged_at: iso(anchor + 3 * 60_000), acknowledged_by: 'ayse.yilmaz' }

async function mock(page, { notFound = false } = {}) {
  await page.addInitScript(() => {
    try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const p = new URL(route.request().url()).pathname
    let status = 200
    let body = { success: true, data: [] }
    const m = /^\/api\/activity\/(\d+)\/detail$/.exec(p)
    if (m) {
      if (notFound) { status = 404; body = { success: false, error: 'not_found' } } else body = detail(Number(m[1]))
    } else if (/^\/api\/activity\/\d+$/.test(p)) {
      if (notFound) { status = 404; body = { success: false, error: 'not_found' } }
    } else if (p === '/api/admin/alerts/5') {
      body = { success: true, data: ALERT }
    } else if (p === '/api/admin/alerts/5/notifications') {
      body = { success: true, data: [
        { id: 100, trigger: 'INITIAL', recipient_name: TEAM, recipient_email: 'odeme-ag@example.com', email_status: 'SENT', sent_at: iso(anchor + 95_000) },
        { id: 101, trigger: 'RESOLUTION', recipient_name: TEAM, recipient_email: 'odeme-ag@example.com', email_status: 'SENT', sent_at: iso(anchor + 6 * 60_000 + 5000) },
      ] }
    } else if (p === '/api/admin/alerts/5/storm-push') {
      body = { success: true, data: { items: [] } }
    } else if (p === '/api/teams/directory') {
      body = { success: true, data: [{ id: 1, name: TEAM }] }
    }
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

function overflowIn(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const vw = document.documentElement.clientWidth
  const out = []
  if (document.documentElement.scrollWidth > vw + 1) out.push(`document ${document.documentElement.scrollWidth} > ${vw}`)
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.width > 1 && r.right > vw + 1) out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || ''}] ${Math.round(r.right)}`)
    if (out.length > 6) break
  }
  return out
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, a[href], select, input:not([type="hidden"])')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || getComputedStyle(b).visibility === 'hidden') continue
    if (b.closest('.recharts-wrapper')) continue
    // Takım rozeti dokunma alanını ::after ile büyütür (ui/TeamBadge TEAM_BADGE_TOUCH) — kutu ölçüsü küçük kalır
    if (b.matches('[data-slot="team-badge"]')) continue
    if (r.height < 39.5 || r.width < 39.5) out.push(`${b.tagName.toLowerCase()} "${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(r.width)}x${Math.round(r.height)}`)
  }
  return out
}

function clippedText(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('h2, span, p, code, dd, time, a, button')]
    .filter((n) => n.offsetParent !== null && n.childElementCount === 0 && !n.closest('.recharts-wrapper') && !n.closest('.sr-only')
      && n.scrollWidth > n.clientWidth + 1)
    .map((n) => n.textContent.slice(0, 40))
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true, sidebar: false },
  { name: 'tablet', width: 768, height: 1024, touch: true, sidebar: false },
  { name: 'laptop', width: 1280, height: 800, touch: false, sidebar: true },
]

for (const vp of VIEWPORTS) {
  test.describe(`Aktivite ayrıntısı — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('bölümler, kesilmeyen metin, kıyas yerleşimi, grafik, alarm zinciri, gezinme', async ({ page }) => {
      test.setTimeout(90_000)
      await mock(page)
      await page.goto(`/e2e/harness/activity-detail.html?id=42${vp.sidebar ? '&sidebar=1' : ''}`)
      const root = page.locator('[data-slot="act-detail-page"]')
      await expect(root.getByRole('heading', { level: 2, name: LONG })).toBeVisible({ timeout: 20_000 })
      if (vp.touch) expect(await page.evaluate(() => matchMedia('(pointer: coarse)').matches)).toBe(true)

      // Bölümler
      for (const slot of ['act-dt-header', 'act-dt-check', 'act-dt-compare', 'act-dt-series', 'act-dt-alarm']) {
        await expect(root.locator(`[data-slot="${slot}"]`)).toBeVisible()
      }
      await expect(root.locator('[data-slot="act-dt-check"] [data-slot="chkfail-panel"]')).toHaveAttribute('data-code', 'ICMP_NO_REPLY')
      // Uzun ad, hedef ve takım adı tam
      await expect(root.locator('[data-slot="act-dt-target"]')).toHaveText(TARGET)
      await expect(root.locator('[data-slot="act-dt-header"]')).toContainText(TEAM)

      // Grafik: çizgi + bu olayın kesikli çizgisi + hatalı nokta işaretleri
      const chart = root.locator('[data-slot="act-dt-series-chart"]')
      await expect(chart.locator('.recharts-line-curve').first()).toBeVisible()
      await expect(chart.locator('.recharts-reference-line')).toHaveCount(1)
      expect(await chart.locator('circle[fill="var(--color-fail)"]').count()).toBeGreaterThanOrEqual(1)

      // Alarm zinciri: açılış → e-posta → sahiplenme → çözüm (e-postalar) — var olan uçlardan
      await expect(root.locator('[data-slot="act-dt-alarm-event"]').first()).toBeVisible()
      const kinds = await root.locator('[data-slot="act-dt-alarm-event"]').evaluateAll((els) => els.map((e) => e.getAttribute('data-kind')))
      expect(kinds).toEqual(['opened', 'mail', 'acknowledged', 'resolved', 'mail'])

      // Kıyas hücreleri: geniş kapta yan yana (aynı üst), dar kapta alt alta
      const tops = await root.locator('[data-slot="act-dt-cmp-cell"]').evaluateAll((els) => els.map((e) => Math.round(e.getBoundingClientRect().top)))
      if (vp.width === 1280) expect(new Set(tops).size).toBe(1)
      if (vp.width === 390) expect(new Set(tops).size).toBe(3)

      expect(await page.evaluate(overflowIn, '[data-slot="act-detail-page"]'), 'yatay taşma').toEqual([])
      expect(await page.evaluate(clippedText, '[data-slot="act-detail-page"]'), 'kırpılan metin').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="act-detail-page"]'), 'küçük dokunma hedefi').toEqual([])

      // Teslimat ayrıntıları (alarmın kendi paneli) açılır, taşma yok
      await root.getByRole('button', { name: /Tüm Bildirim Geçmişi/ }).click()
      await expect(root.locator('[data-slot="act-dt-alarm-chain"]')).toContainText('odeme-ag@example.com')
      expect(await page.evaluate(overflowIn, '[data-slot="act-detail-page"]'), 'teslimat açıkken taşma').toEqual([])
      await page.screenshot({ path: `test-results/activity-detail-${vp.name}.png`, fullPage: true })

      // Önceki kontrolün aktivitesine geçiş → sayfa o aktiviteyi yükler, odak başlıkta
      await root.locator('[data-slot="act-dt-cmp-cell"][data-key="prev"]').getByRole('button').click()
      await expect(root.getByRole('heading', { level: 2, name: 'Komşu aktivite 41' })).toBeVisible()
      await expect(root.getByRole('heading', { level: 2, name: 'Komşu aktivite 41' })).toBeFocused()
      // Zaman çizelgesi + geri
      await root.getByRole('button', { name: 'Bu izlemenin zaman çizelgesi' }).click()
      await root.locator('[data-slot="act-dt-back"]').click()
      expect(await page.evaluate(() => window.__actNav)).toEqual([['open', 41], ['timeline', 'PING', 9], ['back']])
    })

    test('bulunamadı: açıklama + geri, taşma yok', async ({ page }) => {
      await mock(page, { notFound: true })
      await page.goto(`/e2e/harness/activity-detail.html?id=999${vp.sidebar ? '&sidebar=1' : ''}`)
      const root = page.locator('[data-slot="act-detail-page"]')
      await expect(root.getByText('Aktivite bulunamadı ya da görme yetkiniz yok')).toBeVisible({ timeout: 20_000 })
      await expect(root).toHaveAttribute('data-phase', 'notFound')
      expect(await page.evaluate(overflowIn, '[data-slot="act-detail-page"]'), 'yatay taşma').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="act-detail-page"]'), 'küçük dokunma hedefi').toEqual([])
    })
  })
}
