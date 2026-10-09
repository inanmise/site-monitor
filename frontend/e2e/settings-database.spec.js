import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

// 2026-10-09 (kullanıcı: "Ayarlar sayfasındaki database information kısmının tasarımını yeniden shadcn ile yapalım. mweb
// responsive yapıda olsun"): Ayarlar → Veritabanı Bilgileri telefon (390), tablet (768) ve masaüstünde (1280) açılır;
// özet kutucukları + beş kart görünür, sayfa yatay kaymaz, uzun JDBC URL kendi kartının içinde sarar, Yenile yeni veriyi
// getirir, telefonda dokunma hedefleri ≥ 40 px. İlk yükleme hatasında hata bloğu + Tekrar dene sayfayı toparlar.
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 800 },
]

const LONG_URL = 'jdbc:postgresql://veritabani-birincil-sunucu-bolge-2.cok-uzun-bir-alt-alan-adi.example.com:5432,'
  + 'veritabani-yedek-sunucu-bolge-3.example.com:5433/sitemonitor_uygulama_veritabani?user=uygulama_kullanicisi'
  + '&password=***&sslmode=verify-full&ApplicationName=site-monitor-uygulama-sunucusu&targetServerType=primary'

function info(database) {
  return {
    database, user: 'uygulama_kullanicisi_cok_uzun_bir_ad', session_user: 'uygulama_giris', server_addr: '203.0.113.10',
    server_port: 5432, version: 'PostgreSQL 16.4',
    version_full: 'PostgreSQL 16.4 (Debian 16.4-1.pgdg120+2) on x86_64-pc-linux-gnu, compiled by gcc (Debian 12.2.0-14) 12.2.0, 64-bit',
    encoding: 'UTF8', collation: 'tr_TR.UTF-8', size: '1532 MB', start_time: '2026-10-01 08:00:00', uptime: '8 days 01:02:03',
    server_time: '2026-10-09 09:02:03', max_connections: '100', active_connections: 82, timezone: 'Europe/Istanbul',
    table_count: 187, ssl: true, ssl_version: 'TLSv1.3', jdbc_url: LONG_URL,
    driver_name: 'PostgreSQL JDBC Driver', driver_version: '42.7.4',
    pool: { name: 'SiteMonitorHikariPool', active: 14, idle: 4, total: 18, waiting: 2, max_size: 20, min_idle: 5,
      connection_timeout_ms: 30000, idle_timeout_ms: 600000, max_lifetime_ms: 1800000 },
    schema_patches: { applied: 4, noop: 612, failed: 1, locked: true, finished_at: '2026-10-09T06:00:00Z' },
    health: {
      status: 'DEGRADED', component: 'database', checked_at: new Date(Date.now() - 3000).toISOString(), duration_ms: 18, cached: false,
      checks: {
        connection: { status: 'UP', acquire_ms: 2 }, query: { status: 'DEGRADED', latency_ms: 1240 }, writable: { status: 'UP' },
        pool: { status: 'DEGRADED', active: 14, idle: 4, total: 18, max: 20, waiting: 2 }, schema: { status: 'DEGRADED', failed_patches: 1 },
      },
    },
  }
}

const json = (body, status = 200) => (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

/** Sayfa düzeyinde taşma + görünür öğelerin görünüm alanı dışına çıkması (responsive.spec.js `measure` ile aynı kural). */
function measure() {
  const vw = document.documentElement.clientWidth
  const root = document.querySelector('[data-testid="database-info"]') || document.body
  const pageOverflow = Math.max(...[document.documentElement, document.body].map((el) => el.scrollWidth - el.clientWidth))
  const offenders = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    const cs = getComputedStyle(el)
    if (r.width <= 1 || r.height <= 1 || cs.visibility === 'hidden' || cs.display === 'none') continue
    if (r.right <= vw + 1 && r.left >= -1) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (!contained) offenders.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || ''}] ${Math.round(r.left)}..${Math.round(r.right)}`)
    if (offenders.length >= 8) break
  }
  return { vw, pageOverflow, scroll: document.documentElement.scrollWidth, inner: window.innerWidth, offenders }
}

for (const vp of VIEWPORTS) {
  test(`Ayarlar → Veritabanı Bilgileri (${vp.name} ${vp.width})`, async ({ page }) => {
    test.setTimeout(120_000)
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    // Geliştirme sunucusunda React.StrictMode bileşeni iki kez bağlar (iki ilk istek) → sayaç değil EVRE: Yenile'ye
    // basılana kadar ilk veri, sonra yenilenmiş veri. mockApi'den SONRA kaydedilen yol önceliklidir.
    let refreshed = false
    let calls = 0
    await page.route((u) => new URL(u).pathname === '/api/admin/database/info', (route) => {
      calls += 1
      return json({ success: true, data: info(refreshed ? 'sitemonitor_yenilendi' : 'sitemonitor_uygulama') })(route)
    })
    await page.goto('/?tab=settings&sec=database')
    const root = page.locator('[data-testid="database-info"]')
    await expect(root.locator('[data-slot="dbinfo-card"]').first()).toBeVisible({ timeout: 20_000 })

    // İçerik: dört özet kutucuğu + beş kart
    await expect(root.locator('[data-slot="dbinfo-kpi"]')).toHaveCount(4)
    await expect(root.locator('[data-slot="dbinfo-card"]')).toHaveCount(5)
    await expect(root.locator('[data-slot="dbinfo-row"][data-key="database"]')).toContainText('sitemonitor_uygulama')
    for (const card of ['connection', 'health', 'server', 'pool', 'jdbc']) {
      const c = root.locator(`[data-slot="dbinfo-card"][data-card="${card}"]`)
      await c.scrollIntoViewIfNeeded()
      await expect(c, `${card} kartı görünür`).toBeVisible()
    }
    await page.waitForTimeout(400)

    // Sayfa yatay kaymaz; hiçbir görünür öğe ekran dışına çıkmaz
    const m = await page.evaluate(measure)
    expect(m.scroll, `@${vp.name}: document scrollWidth ≤ innerWidth + 1`).toBeLessThanOrEqual(m.inner + 1)
    expect(m.pageOverflow, `@${vp.name}: sayfa taşması (px)`).toBeLessThanOrEqual(1)
    expect(m.offenders, `@${vp.name}: ekran dışına taşan öğe`).toEqual([])

    // Uzun JDBC URL kendi kartının içinde sarar
    const jdbcCard = root.locator('[data-slot="dbinfo-card"][data-card="jdbc"]')
    const urlValue = jdbcCard.locator('[data-slot="dbinfo-row"][data-key="jdbc_url"] [data-slot="dbinfo-value"]')
    await urlValue.scrollIntoViewIfNeeded()
    await expect(urlValue).toContainText('password=***')
    const cardBox = await jdbcCard.boundingBox()
    const urlBox = await urlValue.boundingBox()
    expect(urlBox.x, `@${vp.name}: URL kartın solunda değil`).toBeGreaterThanOrEqual(cardBox.x - 1)
    expect(urlBox.x + urlBox.width, `@${vp.name}: URL kartın sağından taşmıyor`).toBeLessThanOrEqual(cardBox.x + cardBox.width + 1)
    expect(cardBox.x + cardBox.width, `@${vp.name}: kart ekranda`).toBeLessThanOrEqual(vp.width + 1)
    expect(urlBox.height, `@${vp.name}: URL birden çok satıra sarar`).toBeGreaterThan(20)
    const urlOverflow = await urlValue.evaluate((el) => el.scrollWidth - el.clientWidth)
    expect(urlOverflow, `@${vp.name}: URL öğesinin içinde yatay taşma`).toBeLessThanOrEqual(1)

    // Dokunma hedefleri (telefon): Yenile, Sorgu analitiği ve kopyala düğmeleri ≥ 40 px
    const refresh = page.locator('[data-slot="dbinfo-refresh"]')
    if (vp.width < 768) {
      for (const sel of ['[data-slot="dbinfo-refresh"]', '[data-slot="dbinfo-analytics"]']) {
        const b = await page.locator(sel).boundingBox()
        expect(b.height, `${sel} @${vp.name}: dokunma hedefi (px)`).toBeGreaterThanOrEqual(40)
        expect(b.x + b.width, `${sel} @${vp.name}: sağda taşmıyor`).toBeLessThanOrEqual(vp.width + 1)
      }
      const copy = jdbcCard.getByRole('button', { name: /(Copy|Kopyala) — JDBC URL/ })
      const cb = await copy.boundingBox()
      expect(cb.height, `kopyala @${vp.name}: dokunma hedefi (px)`).toBeGreaterThanOrEqual(40)
      expect(cb.width, `kopyala @${vp.name}: dokunma hedefi genişliği (px)`).toBeGreaterThanOrEqual(40)
    }

    await page.screenshot({ path: `test-results/settings-database-${vp.name}.png`, fullPage: true })

    // Yenile: yeni veri gelir, istek sayısı artar
    await refresh.scrollIntoViewIfNeeded()
    const before = calls
    refreshed = true
    await refresh.click()
    await expect(root.locator('[data-slot="dbinfo-row"][data-key="database"]')).toContainText('sitemonitor_yenilendi')
    expect(calls).toBe(before + 1)
    await expect(page.locator('[data-slot="dbinfo-updated"]')).toBeVisible()
  })
}

test('Ayarlar → Veritabanı Bilgileri: ilk yükleme hatası + Tekrar dene (phone 390)', async ({ page }) => {
  test.setTimeout(120_000)
  await page.setViewportSize({ width: 390, height: 844 })
  await mockApi(page)
  let healthy = false
  await page.route((u) => new URL(u).pathname === '/api/admin/database/info', (route) => {
    return !healthy
      ? json({ success: false, code: 'DB_UNAVAILABLE', error: 'The database is not reachable right now. Wait a moment and try again; if it persists, check the database server.' }, 503)(route)
      : json({ success: true, data: info('sitemonitor_uygulama') })(route)
  })
  await page.goto('/?tab=settings&sec=database')
  const block = page.locator('[data-testid="database-info"] [data-slot="empty"][data-tone="danger"]')
  await expect(block).toBeVisible({ timeout: 20_000 })
  const retry = block.getByRole('button', { name: /Try again|Tekrar dene/ })
  const rb = await retry.boundingBox()
  expect(rb.height).toBeGreaterThanOrEqual(40)
  const m = await page.evaluate(measure)
  expect(m.pageOverflow).toBeLessThanOrEqual(1)
  expect(m.offenders).toEqual([])
  healthy = true
  await retry.click()
  await expect(page.locator('[data-slot="dbinfo-row"][data-key="database"]')).toContainText('sitemonitor_uygulama')
  await expect(block).toHaveCount(0)
})
