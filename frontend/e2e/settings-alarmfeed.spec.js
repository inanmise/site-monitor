import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

// 2026-10-30 (Alarm akışı): Ayarlar → Platform → Alarm akışı telefon (390, dokunmatik), tablet (768, dokunmatik), dizüstü (1280)
// ve geniş ekranda (1920): sayfa yatay kaymaz, öğe ekran dışına taşmaz, metin (uzun AD takım adı, IPv6, user agent, BODY)
// kırpılmaz, dokunmatikte hedefler ≥ 40 px; önizleme / çağrı günlüğü dar kapta kart, geniş ekranda tablo; çağrı ayrıntı
// paneli ekrana sığar ve girintili gövdeyi gösterir; akışı AÇMAK onay ister (pencere ekrana sığar) ve yalnız değişen alan
// kaydedilir.
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
  { name: 'wide', width: 1920, height: 1080, touch: false },
]

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi Direktörlüğü)'
const IPV6 = '2001:0db8:85a3:0000:0000:8a2e:0370:7334'
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 CentralMonitoringCollector/4.2.17'
const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)

const SETTINGS = {
  enabled: false, min_level: 'WARNING', team_ids: [3], types: ['http'], resolved_minutes: 15, resolved_mode: 'status', clear_text: 'Clear',
  severity_critical: 'Critical', severity_high: 'Major', severity_warning: 'Warning', rci_mode: 'target_type', source: 'SiteMonitor',
  rcitype: '', mail_separator: 'semicolon', allowed_ips: ['10.0.0.0/8', IPV6, '192.168.100.200/32'], rate_per_minute: 30, max_rows: 2000,
  store_body: true,
}
const ROWS = [
  { CREATEDTIME: '2026-10-30 11:02:00', SEVERITY: 'Critical', RCI: 'bireysel-internet-subesi-mobil-giris.dijitalkanallar.example.com.tr · SSL sertifika süresi',
    RCITYPE: 'SSL', CATEGORY: 'Sertifika', FILTERSOURCE: 'SiteMonitor', TEXT: 'Sertifika 3 gün içinde bitiyor (kurumsal ara sertifika zinciri eksik)',
    BODY: 'Hedef: bireysel-internet-subesi-mobil-giris.dijitalkanallar.example.com.tr:443\nKalan gün: 3\nSahip takım: ' + LONG_TEAM,
    MAILS: 'dijital.kanallar.mobil.bankacilik@example.com.tr;uygulama.gelistirme.ekibi.uzun.adres@example.com.tr', STATUS: 'OPEN',
    RESOLVEDTIME: null, ALERTID: 501, TEAM: LONG_TEAM, UGTEAM: 'Ödeme Sistemleri', LINK: 'https://sitemonitor.example.com/?tab=alerthistory&alert=501' },
  { CREATEDTIME: '2026-10-30 10:40:00', SEVERITY: 'Major', RCI: 'odeme-api.internal.example.com · HTTP', RCITYPE: 'HTTP', CATEGORY: 'HTTP',
    FILTERSOURCE: 'SiteMonitor', TEXT: 'HTTP 503 döndü', BODY: 'Durum: 503\nSüre: 12 sn', MAILS: 'odeme@example.com', STATUS: 'RESOLVED',
    RESOLVEDTIME: '2026-10-30 10:55:00', ALERTID: 498, TEAM: 'Ödeme Sistemleri', UGTEAM: null, LINK: 'https://sitemonitor.example.com/?tab=alerthistory&alert=498' },
]
const ITEMS = [
  { id: 11, requested_at: iso(40_000), client_ip: IPV6, remote_addr: '10.0.0.5', forwarded_for: `${IPV6}, 10.0.0.5, 172.16.20.30`, user_agent: UA,
    method: 'GET', query: 'format=json', status: 200, outcome: 'OK', row_count: 2, open_count: 1, resolved_count: 1, duration_ms: 84,
    response_bytes: 4096, response_hash: 'sha256:9f2c1e7a4b5d6c8e0f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f70', served_by: 'site-monitor-7c9d6f8b5-xk2lq',
    request_id: 'a1b2c3d4e5f6', format: 'json', error: null },
  { id: 10, requested_at: iso(100_000), client_ip: '203.0.113.77', remote_addr: '10.0.0.5', forwarded_for: '203.0.113.77', user_agent: 'curl/8.4.0',
    method: 'GET', query: '', status: 403, outcome: 'IP_DENIED', row_count: null, open_count: null, resolved_count: null, duration_ms: 2,
    response_bytes: 120, response_hash: null, served_by: 'site-monitor-7c9d6f8b5-xk2lq', request_id: 'f6e5d4c3b2a1', format: 'json',
    error: 'İstemci IP adresi izin listesinde değil (203.0.113.77)' },
  { id: 9, requested_at: iso(160_000), client_ip: IPV6, remote_addr: '10.0.0.5', forwarded_for: IPV6, user_agent: UA, method: 'GET', query: '',
    status: 429, outcome: 'RATE_LIMITED', row_count: null, open_count: null, resolved_count: null, duration_ms: 1, response_bytes: 90, response_hash: null,
    served_by: 'site-monitor-7c9d6f8b5-xk2lq', request_id: 'r-9', format: 'json', error: 'Dakikalık çağrı sınırı aşıldı (30)' },
]

function feedData(settings) {
  return {
    endpoint: '/api/public/alarm-feed', base_url: 'https://sitemonitor.dijitalkanallar.example.com.tr', settings,
    limits: { resolved_minutes: [1, 1440], rate_per_minute: [1, 600], max_rows: [100, 10000], text_max: 60, allowed_ips_max: 100 },
    log_retention_days: 90,
    teams: [{ id: 3, name: LONG_TEAM }, { id: 7, name: 'Ödeme Sistemleri' }],
    types: ['cert', 'domain', 'http', 'ping', 'port', 'dns', 'keyword', 'page', 'pagespeed', 'scripted'].map((key) => ({ key, label: key })),
    status: { last_call_at: iso(40_000), last_call_ip: IPV6, last_outcome: 'OK', calls_24h: 1440, ok_24h: 1421, refused_24h: 17, errors_24h: 2,
      distinct_ips_24h: 3, avg_duration_ms_24h: 84, last_row_count: 2, log_write_failures: 0, served_by: 'site-monitor-7c9d6f8b5-xk2lq' },
    callers: [
      { ip: IPV6, first_seen_at: '2026-10-29T08:00:00', last_seen_at: iso(40_000), call_count: 1421, last_user_agent: UA, last_outcome: 'OK' },
      { ip: '203.0.113.77', first_seen_at: '2026-10-30T09:00:00', last_seen_at: iso(100_000), call_count: 17, last_user_agent: 'curl/8.4.0', last_outcome: 'IP_DENIED' },
    ],
  }
}

const json = (body, status = 200) => (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

/** mockApi'den SONRA kaydedilen yollar önceliklidir. Arayüz dili TR. */
async function routes(page) {
  await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yok */ } })
  await mockApi(page, { role: 'ADMIN', globalAdmin: true })
  const calls = { puts: [] }
  let settings = { ...SETTINGS }
  await page.route((u) => new URL(u).pathname === '/api/admin/alarm-feed', json({ success: true, data: feedData(settings) }))
  await page.route((u) => new URL(u).pathname === '/api/admin/alarm-feed/settings', (route) => {
    const body = JSON.parse(route.request().postData() || '{}')
    calls.puts.push(body)
    settings = { ...settings, ...body }
    return json({ success: true, message: 'Alarm akışı ayarları kaydedildi.', data: feedData(settings) })(route)
  })
  await page.route((u) => new URL(u).pathname === '/api/admin/alarm-feed/preview', json({ success: true, data: {
    columns: Object.keys(ROWS[0]), rows: ROWS, count: 2, open: 1, resolved: 1, truncated: false, generated_at: iso(0) } }))
  await page.route((u) => new URL(u).pathname === '/api/admin/alarm-feed/requests',
    json({ success: true, data: { items: ITEMS, total: 3, page: 0, size: 25 } }))
  await page.route((u) => /\/api\/admin\/alarm-feed\/requests\/\d+$/.test(new URL(u).pathname), (route) => {
    const id = Number(new URL(route.request().url()).pathname.split('/').pop())
    const item = ITEMS.find((x) => x.id === id) || ITEMS[0]
    const body = id === 11 ? JSON.stringify(ROWS) : null
    return json({ success: true, data: { ...item, body, body_available: body != null, body_content_type: body ? 'application/json' : null } })(route)
  })
  return calls
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, select, input:not([type="hidden"]), textarea, [role="combobox"]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled) continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none' || b.getAttribute('aria-hidden') === 'true') continue
    const role = b.getAttribute('role')
    if (role === 'checkbox') continue
    let h = r.height
    // Anahtar ve seçim kartındaki radyo: vuruş alanı satır / kart (etiketiyle birlikte)
    if (role === 'switch') h = Math.max(h, b.parentElement.getBoundingClientRect().height)
    if (role === 'radio') h = Math.max(h, b.closest('label')?.getBoundingClientRect().height || 0)
    const af = getComputedStyle(b, '::after')
    if (af.position === 'absolute' && af.content !== 'none') {
      const top = parseFloat(af.top) || 0
      const bottom = parseFloat(af.bottom) || 0
      h = Math.max(h, r.height - Math.min(0, top) - Math.min(0, bottom))
    }
    if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || b.id || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('span, p, li, h3, h4, h5, label, button, dt, dd, code, td, th')].filter((n) => !n.closest('[role="combobox"]'))
    .filter((n) => !n.closest('.sr-only') && !n.closest('pre') && !n.closest('select'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 12
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')).map((n) => n.textContent.slice(0, 40))
}

function offenders(sel) {
  const vw = document.documentElement.clientWidth
  const root = document.querySelector(sel) || document.body
  const out = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    const cs = getComputedStyle(el)
    if (r.width <= 1 || r.height <= 1 || cs.visibility === 'hidden' || cs.display === 'none') continue
    if (r.right <= vw + 1 && r.left >= -1) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (!contained) out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || ''}] ${Math.round(r.left)}..${Math.round(r.right)}`)
    if (out.length >= 8) break
  }
  return out
}

const overflowX = () => document.documentElement.scrollWidth - document.documentElement.clientWidth
const ROOT = '[data-slot="af-root"]'

for (const vp of VIEWPORTS) {
  test.describe(`Ayarlar → Alarm akışı — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('sığar, kırpılmaz, dokunma hedefleri; durum + ayarlar + önizleme + çağıranlar + günlük (dar kap kart, geniş ekran tablo)', async ({ page }) => {
      test.setTimeout(120_000)
      await routes(page)
      await page.goto('/?tab=settings&sec=alarmfeed')
      const root = page.locator(ROOT)
      await expect(root).toHaveAttribute('data-state', 'ok', { timeout: 20_000 })
      await expect(root.locator('[data-slot="af-url"]')).toHaveText('https://sitemonitor.dijitalkanallar.example.com.tr/api/public/alarm-feed')
      await expect(root.locator('[data-slot="af-req-row"]')).toHaveCount(3)
      await expect(root.locator('[data-slot="af-caller"]')).toHaveCount(2)
      await expect(root.locator('[data-slot="token-chip"]')).toHaveCount(3)
      await root.locator('[data-slot="af-preview-run"]').click()
      await expect(root.locator('[data-slot="af-pv-row"]')).toHaveCount(2)
      // İlk önizleme satırını aç: BODY ve bütün kolonlar (uzun AD takım adı dahil) kendi kabında sarar
      await root.locator('[data-slot="af-pv-toggle"]').first().click()
      await expect(root.locator('[data-slot="af-pv-body"]').first()).toBeVisible()
      await expect(root.locator('[data-slot="af-pv-detail"]').first()).toContainText(LONG_TEAM)
      const pvList = root.locator('[data-slot="af-pv-list"]')
      const reqList = root.locator('[data-slot="af-req-list"]')
      const callers = root.locator('[data-slot="af-callers-list"]')
      if (vp.width >= 1920) {
        await expect(pvList).toHaveAttribute('data-layout', 'table')
        await expect(reqList).toHaveAttribute('data-layout', 'table')
        await expect(callers).toHaveAttribute('data-layout', 'table')
      }
      if (vp.width <= 768) {
        await expect(pvList).toHaveAttribute('data-layout', 'cards')
        await expect(reqList).toHaveAttribute('data-layout', 'cards')
        await expect(callers).toHaveAttribute('data-layout', 'cards')
      }
      await page.waitForTimeout(300)

      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(offenders, ROOT), `@${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(await page.evaluate(clipped, ROOT), `@${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, ROOT), `@${vp.name}: küçük dokunma hedefi`).toEqual([])
      await page.screenshot({ path: `test-results/settings-alarmfeed-${vp.name}.png`, fullPage: false })
      // Tam sayfa görüntüsü EN SONDA — Chromium'un dokunmatik öykünmesini sıfırlar (CLAUDE.md, e2e fullPage tuzağı)
      await page.screenshot({ path: `test-results/settings-alarmfeed-${vp.name}-full.png`, fullPage: true })
    })

    test('çağrı ayrıntı paneli ekrana sığar: X-Forwarded-For zinciri, girintili JSON gövde; gövdesiz çağrı "saklanmadı"', async ({ page }) => {
      test.setTimeout(120_000)
      await routes(page)
      await page.goto('/?tab=settings&sec=alarmfeed')
      const root = page.locator(ROOT)
      await expect(root).toHaveAttribute('data-state', 'ok', { timeout: 20_000 })
      await expect(root.locator('[data-slot="af-req-row"]')).toHaveCount(3)
      const open = root.locator('[data-slot="af-req-row"][data-id="11"] [data-slot="af-req-open"]')
      await open.scrollIntoViewIfNeeded()
      await open.click()
      const sheet = page.locator('[data-slot="af-req-detail"]')
      await expect(sheet).toBeVisible()
      await expect(sheet.locator('[data-slot="af-req-body"]')).toHaveAttribute('data-pretty', 'true')
      await expect(sheet.locator('[data-slot="af-req-xff"] li')).toHaveCount(3)
      await page.waitForTimeout(400)   // açılış geçişi bitsin
      const box = await sheet.boundingBox()
      expect(box.x, `@${vp.name}: panel solda taşıyor`).toBeGreaterThanOrEqual(-1)
      expect(box.x + box.width, `@${vp.name}: panel sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
      expect(box.y + box.height, `@${vp.name}: panel altta taşıyor`).toBeLessThanOrEqual(vp.height + 1)
      if (vp.width <= 390) expect(box.width, `@${vp.name}: telefonda panel tam genişlik`).toBeGreaterThanOrEqual(vp.width - 1)
      expect(await page.evaluate(clipped, '[data-slot="af-req-detail"]'), `@${vp.name}: panelde kırpılan metin`).toEqual([])
      expect(await page.evaluate(offenders, '[data-slot="af-req-detail"]'), `@${vp.name}: panelde taşan öğe`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="af-req-detail"]'), `@${vp.name}: panelde küçük hedef`).toEqual([])
      await page.screenshot({ path: `test-results/settings-alarmfeed-${vp.name}-detail.png`, fullPage: false })
      await sheet.locator('[data-slot="af-req-detail-close"]').click()
      await expect(sheet).toHaveCount(0)
      await root.locator('[data-slot="af-req-row"][data-id="10"] [data-slot="af-req-open"]').click()
      await expect(page.locator('[data-slot="af-req-body-none"]')).toContainText('Yanıt gövdesi saklanmadı')
    })

    test('akışı açmak onay ister (pencere sığar); yalnız değişen alanlar kaydedilir', async ({ page }) => {
      test.setTimeout(120_000)
      const calls = await routes(page)
      await page.goto('/?tab=settings&sec=alarmfeed')
      const root = page.locator(ROOT)
      await expect(root).toHaveAttribute('data-state', 'ok', { timeout: 20_000 })
      const sw = root.locator('[data-slot="af-f-enabled"] [role="switch"]')
      await sw.scrollIntoViewIfNeeded()
      await sw.click()
      await root.locator('[data-slot="af-f-source"]').fill('SiteMonitor-Prod')
      const bar = root.locator('[data-slot="af-settings"] [data-slot="settings-save-bar"]')
      await bar.getByRole('button', { name: /^Kaydet$/ }).click()
      const dlg = page.getByRole('dialog')
      await expect(dlg).toBeVisible()
      await expect(dlg).toContainText('https://sitemonitor.dijitalkanallar.example.com.tr/api/public/alarm-feed')
      await page.waitForTimeout(400)
      const box = await dlg.boundingBox()
      expect(box.x, `@${vp.name}: pencere solda taşıyor`).toBeGreaterThanOrEqual(0)
      expect(box.x + box.width, `@${vp.name}: pencere sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
      expect(box.y + box.height, `@${vp.name}: pencere altta taşıyor`).toBeLessThanOrEqual(vp.height + 1)
      await dlg.getByRole('button', { name: 'Kaydet ve aç' }).click()
      await expect.poll(() => calls.puts.length).toBe(1)
      expect(calls.puts[0]).toEqual({ enabled: true, source: 'SiteMonitor-Prod' })
      await expect(bar).not.toHaveAttribute('data-dirty', 'true')
      await expect(root.locator('[data-slot="af-enabled"]')).toHaveAttribute('data-state', 'on')
      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma (kayıt sonrası)`).toBeLessThanOrEqual(1)
    })
  })
}
