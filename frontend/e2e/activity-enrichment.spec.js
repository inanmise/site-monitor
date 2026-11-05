// Kayıtlar → Aktivite zenginleştirmesi (2026-11-05, kullanıcı isteği: "aktivite logunu daha iyi seviyeye taşı; çok daha
// fazla detay istediğimde ilgili aktivite ile gitmek istiyorum"). Gerçek tarayıcıda 390 (dokunmatik) / 768 (dokunmatik) /
// 1280: satır rozetleri, olay grubu süzgeci, tam dışa aktarım (CSV + Excel indirmesi, tavan uyarısı), satırdan ayrıntı
// sayfasına gitme + "aynı anda ne oldu?" paneli + tarayıcı Geri ile süzgeçli listeye dönüş, izleme zaman çizelgesi
// (şerit seçimi, kesintiler). Sayfa yatay taşmaz, uzun ad kesilmez, dokunma hedefleri ≥ 40 px. API mock'lu.
import { test, expect } from '@playwright/test'

const LONG = 'Ödeme Sistemleri Entegrasyon Sağlık Kontrolü — Üretim Ortamı (Bölge 2 / Yedek Hat)'
const TEAM = 'Ödeme Sistemleri ve Dijital Kanallar Platform Ekibi'
const TARGET = 'https://odeme-entegrasyon-saglik.example.com/api/v2/health/deep?kontrol=tam'
const now = Date.now()
const iso = (msAgo) => new Date(now - msAgo).toISOString().slice(0, 19)

const ROWS = [
  { id: 1, monitor_type: 'HTTP', monitor_id: 4, monitor_name: LONG, target: TARGET, action: 'SCHEDULED_CHECK', result_status: 'ERROR',
    result_summary: '503 · 1200ms', error_message: 'HTTP 503 Service Unavailable', activity_time: iso(60_000), team_id: 1, team_name: TEAM,
    response_ms: 1200, http_status: 503, failure_reason: 'HTTP_STATUS' },
  { id: 2, monitor_type: 'HTTP', monitor_id: 4, monitor_name: LONG, target: TARGET, action: 'ALERT_TRIGGERED', result_status: 'ERROR',
    result_summary: 'alarm', activity_time: iso(90_000), team_id: 1, team_name: TEAM, alert_event_id: 77 },
  { id: 3, monitor_type: 'PING', monitor_id: 9, monitor_name: 'ag-gecidi', target: '10.0.0.1', action: 'SCHEDULED_CHECK', result_status: 'TIMEOUT',
    result_summary: 'zaman aşımı', activity_time: iso(7_200_000), team_id: 1, team_name: TEAM, failure_reason: 'ICMP_NO_REPLY' },
]

const RELATED = {
  anchor: { id: 1, time: ROWS[0].activity_time, type: 'HTTP', target: TARGET, host: 'odeme-entegrasyon-saglik.example.com', team_id: 1 },
  window_s: 120, total: 3,
  by_reason: [{ reason: 'DNS_NXDOMAIN', count: 2 }, { reason: 'HTTP_STATUS', count: 1 }],
  by_host: [{ host: 'odeme-entegrasyon-saglik.example.com', count: 2 }, { host: '10.0.0.1', count: 1 }],
  by_team: [{ team_id: 1, team_name: TEAM, count: 3 }],
  items: [{ id: 11, monitor_type: 'DNS', monitor_id: 5, monitor_name: 'odeme-entegrasyon-saglik.example.com', target: 'odeme-entegrasyon-saglik.example.com',
    activity_time: iso(30_000), result_status: 'ERROR', failure_reason: 'DNS_NXDOMAIN', team_id: 1, team_name: TEAM }],
  suspects: [{ code: 'DNS_WIDE', params: { monitors: 3, total_monitors: 4, share_pct: 75 } },
    { code: 'SAME_TEAM', params: { team_id: 1, team_name: TEAM, monitors: 3 } }],
  facts: [{ code: 'FIRST_FAILURE_IN_DAYS', params: { days: 21, at_least: true } }, { code: 'STILL_FAILING', params: { since: iso(60_000) } }],
}

// Zaman çizelgesi zamanları be2'de `…Z` ekli UTC
const isoZ = (msAgo) => iso(msAgo) + 'Z'

function timeline() {
  const iso = isoZ
  const from = iso(7 * 86_400_000)
  return {
    monitor: { type: 'HTTP', id: 4, name: LONG, target: TARGET, team_id: 1, team_name: TEAM },
    from, to: iso(0), checks: 2016, scheduled_checks: 2000, availability_pct: 99.42, mttr_s: 900, longest_outage_s: 2400, outage_count: 2,
    periods: [
      { from, to: iso(3 * 86_400_000), state: 'up', checks: 1150 },
      { from: iso(3 * 86_400_000), to: iso(3 * 86_400_000 - 2_400_000), state: 'down', checks: 8, reason: 'READ_TIMEOUT' },
      { from: iso(3 * 86_400_000 - 2_400_000), to: iso(86_400_000), state: 'up', checks: 560 },
      { from: iso(86_400_000), to: iso(86_400_000 - 600_000), state: 'warn', checks: 2 },
      { from: iso(86_400_000 - 600_000), to: iso(120_000), state: 'up', checks: 280 },
      { from: iso(120_000), to: null, state: 'down', checks: 2, reason: 'HTTP_STATUS' },
    ],
    outages: [
      { from: iso(3 * 86_400_000), to: iso(3 * 86_400_000 - 2_400_000), duration_s: 2400, checks: 8, reason: 'READ_TIMEOUT', first_activity_id: 1, alert_event_id: 77 },
      { from: iso(120_000), to: null, duration_s: null, checks: 2, reason: 'HTTP_STATUS', first_activity_id: 1, alert_event_id: null },
    ],
    series: Array.from({ length: 48 }, (_, i) => ({ t: new Date(now - (48 - i) * 3_600_000).toISOString().slice(0, 19) + 'Z',
      ms_avg: 200 + (i % 7) * 20, ms_max: 400 + (i % 5) * 60, fail: i % 11 === 0 ? 2 : 0 })),
    truncated: false,
  }
}

async function mock(page) {
  await page.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const url = new URL(route.request().url())
    const p = url.pathname
    const json = (body, status = 200, headers = {}) => route.fulfill({ status, contentType: 'application/json', headers, body: JSON.stringify(body) })
    if (p === '/api/me') {
      return json({ success: true, username: 'admin', system_role: 'ADMIN', global_admin: true, bootstrap_admin: true,
        team_id: null, team_ids: [], tour: { status: 'dismissed', version: 99 } })
    }
    if (p === '/api/activity') {
      const group = url.searchParams.get('group') || ''
      const data = group === 'alarm' ? ROWS.filter((r) => r.action.startsWith('ALERT_')) : ROWS
      return json({ success: true, data, total: data.length, page: 0, size: 50, total_pages: 1 })
    }
    if (p === '/api/activity/summary') {
      return json({ success: true, total: 3, success_count: 0, warning: 0, error: 3, unknown: 0, last_activity: ROWS[0].activity_time,
        type_counts: { HTTP: 2, PING: 1 }, group_counts: { check: 2, alarm: 1, notification: 0, change: 0, other: 0 },
        series: { bucket: 'hour', points: [] } })
    }
    if (p === '/api/activity/timeline') return json({ success: true, data: timeline(), timestamp: iso(0) })
    if (p === '/api/activity/export') {
      if (url.searchParams.get('format') === 'json') {
        const columns = ['time', 'monitor_type', 'monitor_name', 'target', 'team_name', 'action', 'result_status', 'result_summary',
          'error_message', 'failure_reason', 'http_status', 'response_ms', 'actor', 'request_id', 'alert_event_id', 'id']
        const rows = ROWS.map((r) => columns.map((c) => (c === 'time' ? '2026-11-05 11:00:00' : (r[c] ?? null))))
        return json({ success: true, data: { columns, time_zone: 'Europe/Istanbul', rows, row_count: rows.length, max_rows: 20000, truncated: false } })
      }
      return route.fulfill({ status: 200, contentType: 'text/csv; charset=utf-8',
        headers: { 'Content-Disposition': 'attachment; filename="aktivite-test.csv"', 'X-Export-Truncated': 'true', 'X-Export-Max-Rows': '50000' },
        body: '﻿Zaman,Tür\n05.11.2026 11:00:00,HTTP\n' })
    }
    if (/^\/api\/activity\/\d+\/related$/.test(p)) return json({ success: true, data: RELATED, timestamp: iso(0) })
    if (/^\/api\/activity\/\d+(\/detail)?$/.test(p)) {
      const id = Number(p.split('/')[3])
      return json({ success: true, data: ROWS.find((r) => r.id === id) || { ...RELATED.items[0], id }, recent: [] })
    }
    if (p === '/api/teams/directory') return json({ success: true, data: [{ id: 1, name: TEAM }] })
    return json({ success: true, data: [] })
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
  if (document.documentElement.scrollWidth > window.innerWidth + 1) out.push(`page ${document.documentElement.scrollWidth}`)
  return out
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, a[href], select, input:not([type="hidden"])')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || getComputedStyle(b).visibility === 'hidden') continue
    if (b.closest('[data-slot="act-tl-strip"]')) continue   // şerit bölümleri oransal; dokunmatikte şerit 48 px yüksek + önceki/sonraki düğmeleri
    if (r.height < 39.5) out.push(`${b.tagName.toLowerCase()} "${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(r.height)}`)
  }
  return out
}

function clippedText(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('span, a, button, h2, h3, p')]
    .filter((n) => n.childElementCount === 0 && n.textContent.trim() && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis'))
    .map((n) => n.textContent.slice(0, 30))
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Aktivite zenginleştirme — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('rozetler, olay grubu, dışa aktarım, ayrıntı sayfası + ilgili panel, Geri ile süzgeçli liste, zaman çizelgesi', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      await page.goto('/?tab=activity&atype=HTTP,PING')
      const root = page.locator('[data-slot="activity-log"]')
      const first = root.locator('[data-slot="act-item"]').first()
      await expect(first).toBeVisible({ timeout: 20_000 })

      // Satır rozetleri: HTTP kodu + neden; alarm satırında alarm no
      await expect(first.locator('[data-slot="act-http"]')).toHaveText(/503/)
      await expect(first.locator('[data-slot="act-reason"]')).toBeVisible()
      await expect(root.locator('[data-slot="act-alarm-badge"]').first()).toContainText('77')
      await expect(first).toContainText(LONG)
      expect(await page.evaluate(clippedText, '[data-slot="act-feed"]'), 'kırpılan metin').toEqual([])

      // Olay grubu çipi: sayılı, süzer, etkin süzgeç çipi eklenir
      const alarm = root.locator('[data-slot="act-group-chips"] [data-group="alarm"]')
      await expect(alarm.locator('[data-slot="act-group-count"]')).toHaveText('1')
      await alarm.click()
      await expect(root.locator('[data-slot="act-chip"][data-kind="group"]')).toBeVisible()
      await expect(root.locator('[data-slot="act-item"]')).toHaveCount(1)
      await root.locator('[data-slot="act-chip"][data-kind="group"]').getByRole('button').click()
      await expect(root.locator('[data-slot="act-item"]')).toHaveCount(3)

      // Dar kapta ek süzgeçler katlanır; açılınca dokunma hedefleri ≥ 40 px
      const more = root.locator('[data-slot="act-more-toggle"]')
      if (await more.count()) await more.click()
      await expect(root.locator('[data-slot="act-facets"]')).toBeVisible()
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="act-filters"]'), 'küçük hedef (süzgeç)').toEqual([])
      expect(await page.evaluate(overflowIn, '[data-slot="activity-log"]'), 'yatay taşma (liste)').toEqual([])
      await page.screenshot({ path: `test-results/activity-enrich-list-${vp.name}.png`, fullPage: false })

      // Tam dışa aktarım — CSV (sunucunun dosyası; tavan başlığı → kalıcı uyarı) ve Excel
      await root.locator('[data-slot="act-export-trigger"]').click()
      const [csv] = await Promise.all([page.waitForEvent('download'), page.locator('[data-slot="act-export-csv"]').click()])
      expect(csv.suggestedFilename()).toBe('aktivite-test.csv')
      await expect(page.locator('[data-slot="act-export-truncated"]')).toBeVisible()
      await root.locator('[data-slot="act-export-trigger"]').click()
      const [xlsx] = await Promise.all([page.waitForEvent('download'), page.locator('[data-slot="act-export-xlsx"]').click()])
      expect(xlsx.suggestedFilename()).toMatch(/^aktivite-\d{8}-\d{4}\.xlsx$/)

      // Satırdan ayrıntı sayfasına: "Ayrıntı" bağlantısı gerçek adres (paylaşılabilir), tık uygulama içinde
      const link = first.locator('[data-slot="act-detail-link"]')
      expect(await link.getAttribute('href')).toMatch(/act=1/)
      await link.click()
      await expect(page).toHaveURL(/[?&]act=1(&|$)/)
      expect(new URL(page.url()).searchParams.get('atype')).toBe('HTTP,PING')
      const related = page.locator('[data-slot="act-related"]')
      await expect(related).toHaveAttribute('data-state', 'ok', { timeout: 15_000 })
      await expect(related.locator('[data-slot="act-rel-suspect"][data-code="DNS_WIDE"]')).toBeVisible()
      await expect(related.locator('[data-slot="act-rel-fact"]')).toHaveCount(2)
      await expect(related).toContainText(TEAM)
      expect(await page.evaluate(overflowIn, '[data-slot="activity-page"]'), 'yatay taşma (ayrıntı)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="act-related"]'), 'küçük hedef (ilgili)').toEqual([])
      expect(await page.evaluate(clippedText, '[data-slot="act-related"]'), 'kırpılan metin (ilgili)').toEqual([])
      // Pencere ±5 dk → yeniden okunur
      await related.getByRole('button', { name: /±5/ }).click()
      await expect(related).toHaveAttribute('data-state', 'ok')
      // İlgili satır → o aktivitenin sayfası; tarayıcı Geri iki kez → süzgeçli liste, kaydırma yerinde
      await related.locator('[data-slot="act-rel-item"]').first().getByRole('button').click()
      await expect(page).toHaveURL(/[?&]act=11(&|$)/)
      await page.goBack()
      await expect(page).toHaveURL(/[?&]act=1(&|$)/)
      await page.goBack()
      await expect(page).not.toHaveURL(/[?&]act=/)
      await expect(root.locator('[data-slot="act-item"]')).toHaveCount(3)
      expect(new URL(page.url()).searchParams.get('atype')).toBe('HTTP,PING')
      await page.screenshot({ path: `test-results/activity-enrich-related-${vp.name}.png`, fullPage: false })

      // İzleme zaman çizelgesi: şerit + seçili dönem + kesintiler + grafik
      await first.locator('[data-slot="act-timeline-link"]').click()
      await expect(page).toHaveURL(/[?&]amon=HTTP%3A4|[?&]amon=HTTP:4/)
      const tl = page.locator('[data-slot="act-tl-view"]')
      await expect(tl).toHaveAttribute('data-state', 'ok', { timeout: 15_000 })
      await expect(tl.locator('[data-slot="act-tl-name"]')).toHaveText(LONG)
      await expect(tl.locator('[data-slot="act-tl-kpi"][data-key="availability"]')).toHaveAttribute('data-tone', 'warn')
      await expect(tl.locator('[data-slot="act-tl-seg"]')).toHaveCount(6)
      const period = tl.locator('[data-slot="act-tl-period"]')
      await expect(period).toHaveAttribute('data-state', 'down')
      await tl.getByRole('button', { name: /Önceki dönem|Previous period/ }).click()
      await expect(period).toHaveAttribute('data-state', 'up')
      await expect(tl.locator('[data-slot="act-tl-outage"]')).toHaveCount(2)
      await expect(tl.locator('[data-slot="act-tl-chart"] .recharts-line').first()).toBeVisible()
      expect(await page.evaluate(overflowIn, '[data-slot="activity-page"]'), 'yatay taşma (zaman çizelgesi)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="act-tl-view"]'), 'küçük hedef (zaman çizelgesi)').toEqual([])
      expect(await page.evaluate(clippedText, '[data-slot="act-tl-view"]'), 'kırpılan metin (zaman çizelgesi)').toEqual([])
      // Kesintinin "İlk aktivite"si → ayrıntı sayfası; uygulama içi Geri → liste
      await tl.locator('[data-slot="act-tl-outage"]').first().getByRole('button', { name: /İlk aktivite|First activity/ }).click()
      await expect(page).toHaveURL(/[?&]act=1(&|$)/)
      await page.screenshot({ path: `test-results/activity-enrich-timeline-${vp.name}.png`, fullPage: false })
      await page.goBack()
      await expect(tl).toBeVisible()
      await tl.getByRole('button', { name: /Aktivite listesine dön|Back to the activity list/ }).click()
      await expect(page).not.toHaveURL(/[?&](amon|act)=/)
      await expect(root.locator('[data-slot="act-item"]')).toHaveCount(3)
    })
  })
}
