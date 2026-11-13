// Olaylar zenginleştirmesi (2026-11-13). Gerçek tarayıcıda 390 (dokunmatik) / 768 (dokunmatik) / 1280: özet kartları,
// müdahale süreleri şeridi, süzgeç araç çubuğu ve uzun adlı olay kartları ekrana sığar (yatay taşma yok, metin kırpılmaz,
// dokunmatik hedefler ≥ 40 px); önem süzgeci istek parametresine gider; detay penceresi (bağlam, kontrol kanıtları, yorum
// yazıcı) sığar; masaüstü listede toplu çöz gerekçe penceresi + satır başına sonuç; telefonda kartta hızlı Onayla / Çöz.
// API mock'lu (bildirim gönderilmez).
import { test, expect } from '@playwright/test'

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const LONG_NAME = 'https://kurumsal-internet-bankaciligi-odeme-servisleri.uzun-alan-adi.example.com/api/v2/health/check'
const LONG_PERSON = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'

const ROW = {
  id: 1, status: 'ongoing', monitor: { name: LONG_NAME, type: 'http', tab: 'http', monitor_id: 9 },
  root_cause: { code: '503', category: 'server_error' }, comment_count: 2, alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL',
  started_at: '2026-11-12T08:00:00', resolved_at: null, resolved_by: null, acknowledged: false, domain: LONG_NAME,
  message: 'HTTP 503 Service Unavailable — upstream gateway yanıt vermedi, yük dengeleyici arka uç havuzu boş', team_id: 5,
  team_name: LONG_TEAM, can_manage: true, can_act: true, can_comment: true, can_delete: false, can_notify: true, recurrence_7d: 4,
  acknowledged_by: LONG_PERSON, acknowledged_note: 'Ödeme servisinin yük dengeleyicisinde arka uç havuzu boşaldı; ağ ekibi müdahale ediyor.',
  realert_count: 3, last_re_alert_at: '2026-11-12T11:00:00', resolved_silently: false, in_maintenance: false,
  owner_team_ids: [5], storm_id: null,
}
const ROW2 = { ...ROW, id: 2, acknowledged: true, alert_level: 'HIGH', monitor: { ...ROW.monitor, name: 'api.example.com', monitor_id: 10 },
  domain: 'api.example.com', recurrence_7d: null, acknowledged_note: null, comment_count: 0 }
const ROW3 = { ...ROW, id: 3, status: 'resolved', alert_level: 'WARNING', resolved_at: '2026-11-12T09:00:00', resolved_by: 'system',
  monitor: { ...ROW.monitor, name: 'cdn.example.net', monitor_id: 11 }, domain: 'cdn.example.net', recurrence_7d: null }
const ROWS = [ROW, ROW2, ROW3]
const SUMMARY = {
  open: 2, acknowledged: 1, critical: 1, teamless: 0, mine: 2, resolved_24h: 1,
  mtta_24h_minutes: 12.4, mtta_7d_minutes: 38, mttr_24h_minutes: null, mttr_7d_minutes: 185, mtta_sample_7d: 9, mttr_sample_7d: 14,
  timing_capped: false, longest_open: { id: 1, name: LONG_NAME, started_at: '2026-11-12T08:00:00', alert_level: 'CRITICAL' },
  team_options: [{ id: 5, name: LONG_TEAM, count: 3 }],
}

let listQueries = []
let bulkBody = null
async function mock(page) {
  listQueries = []; bulkBody = null
  await page.addInitScript(() => {
    try {
      localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 }))
      localStorage.setItem('incidents-banner-dismissed', 'true')
    } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const p = url.pathname
    let body = { success: true, data: [] }
    if (p === '/api/me') body = { success: true, username: 'demo', display_name: 'Demo', system_role: 'USER', team_id: 5, team_ids: [5], tour: { status: 'dismissed', version: 99 } }
    else if (p === '/api/me/permissions') body = { success: true, data: { 'monitoring.read': { view: true }, 'alerts.read': { view: true }, 'alerts.actions': { execute: true } } }
    else if (p === '/api/monitoring/incidents/summary') body = { success: true, data: SUMMARY }
    else if (p === '/api/monitoring/incidents') {
      listQueries.push(Object.fromEntries(url.searchParams))
      body = { success: true, data: ROWS, total: ROWS.length, type_counts: { HTTP_DOWN: 3 }, server_facets: true }
    } else if (/^\/api\/monitoring\/incidents\/\d+\/comments$/.test(p)) {
      body = { success: true, data: [
        { id: 11, author_name: LONG_PERSON, author_username: 'ayse', body: 'Arka uç havuzu yeniden dolduruldu, izlemeye devam ediyoruz.', created_at: '2026-11-12T08:30:00', can_delete: true },
      ] }
    } else if (/^\/api\/admin\/alerts\/\d+\/notifications$/.test(p)) {
      body = { success: true, data: [{ id: 8, sent_at: '2026-11-12T08:01:00', recipient_name: LONG_TEAM, recipient_email: 'odeme@example.com', trigger: 'INITIAL', email_status: 'SENT' }] }
    } else if (p === '/api/activity') {
      body = { success: true, total: 1, data: [{ id: 501, activity_time: '2026-11-12T07:58:00', result_status: 'ERROR', failure_reason: 'HTTP_STATUS', http_status: 503 }] }
    } else if (p === '/api/admin/alerts/bulk' && req.method() === 'POST') {
      bulkBody = JSON.parse(req.postData() || '{}')
      body = { success: true, data: { processed: 1, skipped: 0, failed: 0, results: bulkBody.ids.map((id, i) => ({ id, outcome: i === 0 ? 'processed' : 'closed' })) } }
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
    for (const n of root.querySelectorAll('span, p, li, h2, h3, dd, label, a')) {
      if (n.closest('[role="combobox"], select, .sr-only')) continue
      if (n.childElementCount === 0 && n.textContent.trim().length > 20
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
  test.describe(`Olaylar — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('pano: özet + müdahale şeridi + kartlar sığar, adlar sarar; önem süzgeci isteğe gider', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      await page.goto('/?tab=incidents')
      await expect(page.locator('[data-slot="incident-card"]').first()).toBeVisible({ timeout: 20_000 })
      await expect(page.locator('[data-slot="incident-metrics"]')).toBeVisible()
      await expect(page.locator('[data-slot="incident-recur"]').first()).toBeVisible()
      await page.waitForTimeout(300)
      expect(await overflow(page), 'yatay taşma').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="incidents-page"]'), 'kırpılan metin').toEqual([])
      if (vp.touch) {
        expect(await page.evaluate(smallTargets, '[data-slot="incidents-header"], [data-slot="incidents-toolbar"], [data-slot="incident-metrics"], [data-slot="incident-card"]'),
          'küçük hedef').toEqual([])
      }
      if (vp.name === 'phone') {
        await page.getByRole('button', { name: /^(Filters|Süzgeçler)/ }).click()
        const sheet = page.locator('[data-slot="incident-filters-sheet"]')
        await sheet.getByRole('combobox', { name: /Severity|Önem/ }).selectOption('CRITICAL,HIGH')
        expect(await page.evaluate(smallTargets, '[data-slot="incident-filters-sheet"]'), 'küçük hedef (süzgeç)').toEqual([])
        await sheet.getByRole('button', { name: /^(Apply|Uygula)/ }).click()
      } else {
        await page.getByRole('combobox', { name: /Severity|Önem/ }).selectOption('CRITICAL,HIGH')
      }
      await expect.poll(() => listQueries.at(-1)?.level).toBe('CRITICAL,HIGH')
      await page.locator('[data-slot="incident-card"]').first().scrollIntoViewIfNeeded()
      await page.screenshot({ path: `test-results/incidents-board-${vp.name}.png` })
    })

    test('detay penceresi: bağlam + kontrol kanıtı + yorum sığar; adlar sarar; dokunmatik hedefler', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      await page.goto('/?tab=incidents')
      await page.locator('[data-slot="incident-card"][data-incident-id="1"] [data-incident-open]').click()
      const sheet = page.locator('[data-slot="incident-detail"]')
      await expect(sheet.locator('[data-slot="incident-ack-note"]')).toBeVisible({ timeout: 20_000 })
      await expect(sheet.locator('[data-slot="incident-evidence-row"]')).toHaveCount(1)
      await expect(sheet.locator('[data-slot="timeline-event"][data-kind="comment"]')).toHaveCount(1)
      // Açılış kayma animasyonu bitene dek bekle (yarıda ölçülen kutu ekran dışına taşmış görünür)
      await expect.poll(async () => { const bb = await sheet.boundingBox(); return bb.x + bb.width }).toBeLessThanOrEqual(vp.width + 1)
      const box = await sheet.boundingBox()
      expect(box.x).toBeGreaterThanOrEqual(-1)
      expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
      expect(await page.evaluate(clipped, '[data-slot="incident-detail"]'), 'kırpılan metin (detay)').toEqual([])
      if (vp.touch) {
        expect(await page.evaluate(smallTargets,
          '[data-slot="incident-actions"], [data-slot="incident-context"], [data-slot="incident-check-evidence"], [data-slot="incident-composer"], [data-slot="incident-timeline"]'),
        'küçük hedef (detay)').toEqual([])
      }
      await expect(sheet.locator('[data-slot="incident-open-alert"]')).toHaveAttribute('href', '?tab=alerthistory&alert=1')
      await sheet.screenshot({ path: `test-results/incidents-detail-${vp.name}.png` })
      await sheet.getByRole('button', { name: /^(Close|Kapat)$/ }).click()
      await expect(sheet).toHaveCount(0)
    })

    test('liste: masaüstünde toplu çöz (gerekçe + satır sonucu); dokunmatikte kartta hızlı Onayla / Çöz', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      await page.goto('/?tab=incidents&view=list')
      if (vp.name === 'phone') {
        const quick = page.locator('[data-slot="incident-card"][data-incident-id="1"] [data-slot="incident-quick-actions"]')
        await expect(quick).toBeVisible({ timeout: 20_000 })
        expect(await page.evaluate(smallTargets, '[data-slot="incident-quick-actions"]'), 'küçük hedef (hızlı)').toEqual([])
        expect(await overflow(page), 'yatay taşma').toBeLessThanOrEqual(1)
        await quick.getByRole('button', { name: /^(Resolve|Çöz)/ }).click()
        await expect(page.locator('[data-action-note]')).toBeVisible()
        await page.screenshot({ path: `test-results/incidents-list-${vp.name}.png` })
        return
      }
      await expect(page.locator('[data-slot="incidents-select-row"]')).toHaveCount(2, { timeout: 20_000 })   // çözülmüş satır seçilemez
      // Uzun takım adı sarar ama sütun tek harfe çökmez (tablo min-content tuzağı)
      if (vp.width >= 1024) {
        const w = await page.locator('[data-slot="incident-team-cell"]').first().evaluate((n) => n.getBoundingClientRect().width)
        expect(w, 'takım sütunu genişliği').toBeGreaterThanOrEqual(140)
        // 1280'de tablo kabına sığar (son sütunlar kaydırmanın arkasında kalmaz)
        if (vp.width >= 1280) {
          const over = await page.locator('[data-slot="incident-list"] [data-slot="table-container"]').evaluate((n) => n.scrollWidth - n.clientWidth)
          expect(over, 'tablo yatay kaydırma').toBeLessThanOrEqual(1)
        }
      }
      await page.locator('[data-slot="incidents-select-all"]').click()
      const bar = page.locator('[data-slot="incidents-bulk-bar"]')
      await expect(bar).toBeVisible()
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="incidents-bulk-bar"], [data-slot="incident-list"]'), 'küçük hedef (liste)').toEqual([])
      await bar.getByRole('button', { name: /Resolve selected|Toplu çöz/ }).click()
      const dlg = page.locator('[data-action-note]')
      await dlg.getByRole('textbox', { name: /^(Reason|Gerekçe)/ }).fill('Yük dengeleyici havuzu yeniden yapılandırıldı')
      await dlg.locator('[data-action-submit]').click()
      await expect.poll(() => bulkBody).toEqual({ action: 'resolve', ids: [1, 2], note: 'Yük dengeleyici havuzu yeniden yapılandırıldı' })
      const result = page.locator('[data-slot="incidents-bulk-result"]')
      await expect(result.locator('li')).toHaveCount(2)
      await expect(result.locator('[data-outcome="closed"]')).toBeVisible()
      expect(await overflow(page), 'yatay taşma').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="incidents-bulk-result"]'), 'kırpılan metin (sonuç)').toEqual([])
      await page.locator('[data-slot="incident-list"]').scrollIntoViewIfNeeded()
      await page.screenshot({ path: `test-results/incidents-list-${vp.name}.png` })
    })
  })
}
