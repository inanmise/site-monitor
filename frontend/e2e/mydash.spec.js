// Panom + TV / duvar ekranı kipi (2026-10-11, kullanıcı kararı "Yeni 'Panom' sekmesi"). Gerçek tarayıcıda 390 (dokunmatik) /
// 768 (dokunmatik) / 1280: varsayılan kutular verileriyle, uzun takım / kişi adları kesilmez, yatay taşma yok; düzenleme
// kipinde dokunmatik hedefler ≥ 40 px ve taşı / boyut / kaldır doğru tercih gövdesiyle kaydedilir; TV kipi ekranı kaplar,
// sayfalar arasında geçer, Esc / Çık ile kapanır. API mock'lu (bildirim yok).
import { test, expect } from '@playwright/test'
import { STAT_DEFS } from '../src/components/mydash/statDefs.js'

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const LONG_PERSON = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'
const LONG_SERVICE = 'Kurumsal İnternet Bankacılığı Ödeme ve Fatura Tahsilat Hizmetleri (Çağrı Merkezi Entegrasyonu)'

// Takım × tür matrisi için izleme satırları: uzun AD takım adları, her türden, sorunlu / gecikmiş / sağlıklı karışık
const MATRIX_TEAMS = [[5, LONG_TEAM], [6, 'Ödeme Sistemleri ve Kart Operasyonları (Bireysel / Kurumsal Kanallar)'], [7, 'Altyapı'], [8, 'Çağrı Merkezi']]
const MATRIX_MONITORS = MATRIX_TEAMS.flatMap(([id, name], i) => ['http', 'ping', 'dns', 'pagespeed', 'scripted', 'dbquery']
  .filter((_, j) => (i + j) % 4 !== 3)
  .map((type, j) => ({ id: id * 100 + j, team_id: id, team_name: name, type, active: true, status: (i + j) % 5 === 0 ? 'down' : (i + j) % 7 === 0 ? 'stale' : 'up' })))
  .concat([{ id: 999, team_id: null, team_name: null, type: 'http', active: true, status: 'up' }])

let saved = []
async function mock(page, { dashboard = null, noc = false } = {}) {
  saved = []
  await page.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    let body = { success: true, data: [] }
    if (p === '/api/me') {
      body = { success: true, username: 'demo', system_role: 'ADMIN', global_admin: true, team_id: 5, team_ids: [5], tour: { status: 'dismissed', version: 99 },
        ...(noc ? { noc_operator: true, noc_can_write: true } : {}) }
    } else if (p === '/api/me/permissions') {
      body = { success: true, data: { 'alerts.read': { view: true }, 'monitoring.read': { view: true }, 'oncall.read': { view: true }, 'system_health.read': { view: true }, 'data_quality.view': { view: true },
        ...(noc ? { 'maintenance.view': { view: true }, 'incidents.view': { view: true } } : {}) } }
    } else if (p === '/api/me/preferences') {
      if (req.method() === 'PUT') {
        const b = JSON.parse(req.postData() || '{}')
        saved.push(b)
        body = { success: true, prefs: { ...(dashboard ? { dashboard } : {}), ...b } }
      } else body = { success: true, prefs: dashboard ? { dashboard } : {} }
    } else if (p === '/api/me/open-alerts') {
      body = { success: true, data: { total: 3, oldest_open_at: '2026-10-11T05:00:00', oldest_severe_at: '2026-10-11T07:00:00', tabs: { http: { count: 3, unacked: 2, levels: { critical: 1, high: 1, warning: 1, other: 0 }, items: [
        { id: 7, domain: 'odeme-servisleri-uzun-alan-adi.kurumsal.example.com', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', created_at: '2026-10-11T08:00:00', team_name: LONG_TEAM },
        { id: 8, domain: 'kart.example.com', alert_type: 'PING_DOWN', alert_level: 'HIGH', created_at: '2026-10-11T07:00:00', team_name: 'Kartlar' }] } } } }
    } else if (p === '/api/monitoring/overview') {
      body = { success: true, data: { totals: { active: 120, down: 3, stale: 2, unknown: 1, success_rate_window: 98.4 },
        types: [{ type: 'http', total: 60, active: 60, down: 2, stale: 0 }, { type: 'ping', total: 40, active: 40, down: 1, stale: 2 },
          { type: 'dns', total: 20, active: 20, down: 0, stale: 0 }, { type: 'pagespeed', total: 4, active: 4 }, { type: 'scripted', total: 3, active: 3, unknown: 1 },
          { type: 'dbquery', total: 5, active: 5, down: 1 }],
        monitors: MATRIX_MONITORS } }
    } else if (p === '/api/stats') {
      body = { success: true, data: { total_certificates: 240, valid_count: 220, expired: 1, expiring_in_7_days: 2, expiring_in_30_days: 6, error_count: 1,
        expired_domains: ['eski-sertifika.example.com'], critical_domains: ['kart-odeme-yonetim-paneli.kurumsal.example.com'] } }
    } else if (p === '/api/status-page') {
      body = { success: true, data: { overall: { state: 'partial_outage' }, services: [
        { key: 'a', name: LONG_SERVICE, team_name: LONG_TEAM, state: 'partial_outage', since: '2026-10-11T07:00:00' },
        { key: 'b', name: 'Mobil Bankacılık', team_name: 'Mobil', state: 'degraded', maintenance: 3, monitors_visible: true }, { key: 'c', name: 'Kartlar', state: 'operational' }],
        maintenance: { active: [{ id: 1, name: 'Veri merkezi ağ anahtarlarının yazılım güncellemesi ve yedekli hat geçiş testi (gece bakımı)', state: 'active',
          starts_at: '2026-10-11T06:00:00Z', ends_at: '2026-10-11T10:00:00Z', all_monitors: false, monitor_count: 3, team_name: LONG_TEAM }],
          active_total: 1, active_hidden: 0, upcoming: [{ id: 2, name: 'Kart sistemi', state: 'upcoming', starts_at: '2026-10-14T21:00:00Z',
            ends_at: '2026-10-14T23:00:00Z', all_monitors: true, team_name: 'Kartlar' }], upcoming_total: 1, upcoming_hidden: 0, days: 7 },
        incidents: { active: [{ id: 41, title: 'Mobil uygulamada giriş yapılamıyor — kimlik doğrulama servisi zaman aşımına düşüyor', severity: 'CRITICAL',
          status: 'INVESTIGATING', started_at: '2026-10-11T07:10:00', team_name: LONG_TEAM }], active_total: 2, active_visible: 1, active_hidden: 1 } } }
    } else if (p === '/api/noc/console') {
      body = { success: true, data: { kpis: { not_called: 2 }, total: 2, items: [
        { id: 8, domain: 'kart.example.com', level: 'HIGH', team_name: 'Kartlar', call_count: 0, created_at: new Date().toISOString(),
          noc: { sent_at: new Date().toISOString() } },
        { id: 7, domain: 'odeme-servisleri-uzun-alan-adi.kurumsal.example.com', level: 'CRITICAL', team_name: LONG_TEAM, call_count: 0,
          created_at: '2026-10-11T07:00:00', noc: { sent_at: '2026-10-11T07:01:00' } }] } }
    } else if (p === '/api/monitoring/storm/status') {
      body = { success: true, data: { settings: { enabled: true }, totals: { storming: 1, near: 0 }, teams: [{ team_id: 5, team_name: LONG_TEAM, status: 'STORM', window_targets: 7 }] } }
    } else if (p === '/api/teams/directory') {
      body = { success: true, data: [{ id: 5, name: LONG_TEAM, active: true }, { id: 6, name: 'Ödeme Sistemleri', active: true }] }
    } else if (p === '/api/oncall/current') {
      body = { success: true, data: [{ team_id: 5, user_id: 'u-1', name: LONG_PERSON, until: '2026-10-12T06:00:00', active: true, override: false }] }
    } else if (p === '/api/activity') {
      body = { success: true, data: [
        { id: 1, monitor_type: 'http', monitor_name: 'Kurumsal Ödeme Servisi Sağlık Kontrolü (Üretim / Dış Erişim)', target: 'odeme.example.com', result_status: 'ERROR', activity_time: '2026-10-11T08:00:00' },
        { id: 2, monitor_type: 'ping', monitor_name: 'Kart Ağ Geçidi', target: '10.0.0.1', result_status: 'SUCCESS', activity_time: '2026-10-11T07:59:00' }] }
    } else if (p === '/api/admin/system/watchdog') {
      body = { success: true, data: { enabled: true, status: 'DEGRADED', stale: false, checks: [{ key: 'push', label: 'Push kuyruğu', status: 'PROBLEM', severity: 'WARNING', detail: 'Kuyrukta 15 dakikadan eski 12 bildirim bekliyor.' }] } }
    } else if (p === '/api/data-quality') {
      body = { success: true, data: { org: { score: 81, band: 'NEEDS_ATTENTION', findings: 34, delta_7d: -2 }, teams: [{ id: 5, name: LONG_TEAM, score: 62, band: 'POOR' }] } }
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, select')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled) continue
    if (r.height < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(r.height)}`)
  }
  return out
}

function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('span, p, li')]
    // Ekran okuyucuya özel metin (sr-only) kasıtlı olarak 1 px'tir — kırpılma sayılmaz
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 20 && !n.closest('.sr-only')
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')).map((n) => n.textContent.slice(0, 30))
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Panom — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('varsayılan kutular; adlar kesilmez, sayfa taşmaz; düzenleme kaydedilir', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      await page.goto('/?tab=mydash')
      const root = page.locator('[data-slot="mydash-page"]')
      await expect(root.locator('[data-slot="mydash-widget"]')).toHaveCount(7, { timeout: 20_000 })
      await expect(root.locator('[data-slot="mydash-widget"][data-type="oncall"]')).toContainText(LONG_PERSON)
      await expect(root.locator('[data-slot="mydash-widget"][data-type="status_services"]')).toContainText(LONG_SERVICE)
      await expect(root.locator('[data-slot="mydash-widget"][data-type="alerts_open"] [data-slot="mydash-alerts-total"]')).toContainText('3')
      await page.waitForTimeout(300)
      expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth), 'yatay taşma').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="mydash-page"]'), 'kırpılan metin').toEqual([])
      // Kutular kabın dışına taşmaz
      const overflow = await page.evaluate(() => [...document.querySelectorAll('[data-slot="mydash-widget"]')]
        .filter((w) => w.scrollWidth > w.clientWidth + 1).map((w) => w.getAttribute('data-type')))
      expect(overflow, 'kutu içi taşma').toEqual([])

      await page.locator('[data-slot="mydash-edit"]').click()
      await expect(root.locator('[data-slot="mydash-widget-tools"]')).toHaveCount(7)
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="mydash-page"]'), 'küçük hedef').toEqual([])
      const first = root.locator('[data-slot="mydash-widget"]').first()
      await first.getByRole('button', { name: /one place later|bir sonraya/ }).click()
      await expect.poll(() => saved.at(-1)?.dashboard?.widgets?.map((w) => w.type).slice(0, 2)).toEqual(['cert_expiring', 'alerts_open'])
      await root.locator('[data-slot="mydash-widget"][data-type="cert_expiring"]').getByRole('radio', { name: /^(Wide|Geniş)$/ }).click()
      await expect.poll(() => saved.at(-1)?.dashboard?.widgets?.find((w) => w.type === 'cert_expiring')?.size).toBe('lg')
      await root.locator('[data-slot="mydash-widget"][data-type="storms"] [data-slot="mydash-remove"]').click()
      await expect(root.locator('[data-slot="mydash-widget"]')).toHaveCount(6)
      await expect.poll(() => saved.at(-1)?.dashboard?.widgets?.some((w) => w.type === 'storms')).toBe(false)

      // Katalog: ekranda sığar, kaldırılan kutu yeniden eklenebilir
      await page.locator('[data-slot="mydash-add"]').click()
      const cat = page.locator('[data-slot="mydash-catalog"]')
      await expect(cat.locator('[data-slot="mydash-catalog-item"][data-type="storms"]')).toBeVisible()
      const box = await page.getByRole('dialog').boundingBox()
      expect(box.x).toBeGreaterThanOrEqual(-1)
      expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="mydash-catalog"]'), 'katalog küçük hedef').toEqual([])
      await cat.locator('[data-slot="mydash-catalog-item"][data-type="watchdog"]').getByRole('button').click()
      await expect.poll(() => saved.at(-1)?.dashboard?.widgets?.at(-1)?.type).toBe('watchdog')
      // Tam sayfa görüntüsü EN SONDA: Chromium'da fullPage çekim dokunmatik öykünmeyi sıfırlıyor (sonraki ölçümler yanılırdı)
      await page.keyboard.press('Escape')
      await page.screenshot({ path: `test-results/mydash-${vp.name}.png`, fullPage: true })
    })

    test('2026-10-13: 7/24 komuta düzeni + renkli panel görünümü; kesilme / taşma yok, hedefler ≥ 40 px', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page, { noc: true })
      await page.goto('/?tab=mydash')
      const root = page.locator('[data-slot="mydash-page"]')
      await expect(root.locator('[data-slot="mydash-widget"]')).toHaveCount(7, { timeout: 20_000 })
      await page.locator('[data-slot="mydash-edit"]').click()
      await page.locator('[data-slot="mydash-add"]').click()
      await page.locator('[data-slot="mydash-preset-apply"]').click()
      await page.getByRole('dialog', { name: /Apply the 24.7 command layout|7.24 komuta düzeni uygulansın/ })
        .getByRole('button', { name: /Apply this layout|Bu düzeni uygula/ }).click()
      await expect.poll(() => saved.at(-1)?.dashboard?.widgets?.map((w) => w.type)).toEqual(['noc_light', 'noc_uncalled', 'alerts_open', 'maintenance',
        'monitor_health', 'storms', 'incidents_open', 'oncall', 'status_services'])
      await expect(root.locator('[data-slot="mydash-widget"]')).toHaveCount(9)
      const light = root.locator('[data-slot="mydash-widget"][data-type="noc_light"]')
      await expect(light.locator('[data-slot="mydash-light"]')).toHaveAttribute('data-tone', 'danger')
      await expect(root.locator('[data-slot="mydash-widget"][data-type="noc_uncalled"]')).toHaveAttribute('data-tone', 'danger')
      // Bakım kutusu küçük (sm): liste yok, sayılar var — süren 1, bakımdaki izleme 3
      await expect(root.locator('[data-slot="mydash-widget"][data-type="maintenance"] [data-slot="mydash-maint-active"]')).toContainText('1')
      await expect(root.locator('[data-slot="mydash-widget"][data-type="maintenance"] [data-slot="mydash-maint-monitors"]')).toContainText('3')
      await expect(root.locator('[data-slot="mydash-widget"][data-type="maintenance"]')).toHaveAttribute('data-tone', 'warning')
      await expect(root.locator('[data-slot="mydash-widget"][data-type="incidents_open"]')).toContainText('Mobil uygulamada giriş yapılamıyor')
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="mydash-page"]'), 'küçük hedef (düzenleme)').toEqual([])
      // Renkli panel: kutu başına; kaydedilir; zemin durum renginde
      const alerts = root.locator('[data-slot="mydash-widget"][data-type="alerts_open"]')
      await alerts.locator('[data-slot="mydash-style"]').click()
      await expect.poll(() => saved.at(-1)?.dashboard?.widgets?.find((w) => w.type === 'alerts_open')?.style).toBe('stat')
      await expect(alerts).toHaveAttribute('data-stat', 'on')
      await page.locator('[data-slot="mydash-edit"]').click()
      await expect(alerts.locator('[data-slot="mydash-stat-value"]')).toHaveText('3')
      await page.waitForTimeout(300)
      expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth), 'yatay taşma').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="mydash-page"]'), 'kırpılan metin').toEqual([])
      const overflow = await page.evaluate(() => [...document.querySelectorAll('[data-slot="mydash-widget"]')]
        .filter((w) => w.scrollWidth > w.clientWidth + 1).map((w) => w.getAttribute('data-type')))
      expect(overflow, 'kutu içi taşma').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="mydash-page"]'), 'küçük hedef').toEqual([])
      // Bağlantı düğmeleri tarayıcının varsayılan (2 px kabarık) kenarlığını almaz — sıfırlama `[data-slot]` üzerinden
      expect(await page.evaluate(() => [...document.querySelectorAll('[data-slot="mydash-page"] [data-link="true"]')]
        .filter((b) => !b.hasAttribute('data-slot') || getComputedStyle(b).borderTopWidth !== '0px').map((b) => b.textContent.slice(0, 30))), 'kenarlıklı bağlantı').toEqual([])
      // Tam sayfa görüntüsü dokunmatik ölçümlerden SONRA (fullPage dokunmatik öykünmeyi sıfırlar); tıklama bundan etkilenmez
      await page.screenshot({ path: `test-results/mydash-noc-${vp.name}.png`, fullPage: true })
      // Renkli panele tıklamak süzülmüş listeyi açar
      await alerts.locator('[data-slot="mydash-stat"]').click()
      await expect(page).toHaveURL(/tab=alerthistory/)
    })

    test('2026-10-13: Grafana ızgaraları — hizmet ızgarası, tür ısı haritası, takım × tür matrisi; taşma / kesilme yok, hedefler ≥ 40 px', async ({ page }) => {
      test.setTimeout(120_000)
      const dashboard = { v: 1, widgets: [
        { id: 'team-matrix', type: 'team_matrix', size: 'lg' }, { id: 'service-grid', type: 'service_grid', size: 'md' },
        { id: 'type-heatmap', type: 'type_heatmap', size: 'sm' }] }
      await mock(page, { dashboard })
      await page.goto('/?tab=mydash')
      const root = page.locator('[data-slot="mydash-page"]')
      const matrix = root.locator('[data-slot="mydash-widget"][data-type="team_matrix"]')
      await expect(matrix.locator('[data-slot="mydash-matrix-card"]').first()).toBeAttached({ timeout: 20_000 })
      // Geniş kapta matris, dar kapta takım kartları (yatay kaydırma yok)
      if (vp.width >= 1024) {
        await expect(matrix.locator('[data-slot="mydash-matrix"]')).toBeVisible()
        await expect(matrix.locator('[data-slot="mydash-matrix-cards"]')).toBeHidden()
      } else {
        await expect(matrix.locator('[data-slot="mydash-matrix-cards"]')).toBeVisible()
        await expect(matrix.locator('[data-slot="mydash-matrix"]')).toBeHidden()
      }
      await expect(matrix).toContainText(LONG_TEAM)
      await expect(root.locator('[data-slot="mydash-widget"][data-type="service_grid"] [data-slot="mydash-grid-service"]').first()).toBeVisible()
      await expect(root.locator('[data-slot="mydash-widget"][data-type="type_heatmap"] [data-slot="mydash-heat-cell"]')).toHaveCount(6)   // 6 tür (veritabanı sorgusu dahil; izlemesi olmayan tür gizli)
      await page.waitForTimeout(300)
      expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth), 'yatay taşma').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="mydash-page"]'), 'kırpılan metin').toEqual([])
      const overflow = await page.evaluate(() => [...document.querySelectorAll('[data-slot="mydash-widget"]')]
        .filter((w) => w.scrollWidth > w.clientWidth + 1).map((w) => w.getAttribute('data-type')))
      expect(overflow, 'kutu içi taşma').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="mydash-page"]'), 'küçük hedef').toEqual([])
      expect(await page.evaluate(() => [...document.querySelectorAll('[data-slot="mydash-page"] [data-link="true"]')]
        .filter((b) => !b.hasAttribute('data-slot') || getComputedStyle(b).borderTopWidth !== '0px').map((b) => b.textContent.slice(0, 30))), 'kenarlıklı bağlantı').toEqual([])
      await page.screenshot({ path: `test-results/mydash-grids-${vp.name}.png`, fullPage: true })
      // Hücre tıklaması süzülmüş İzleme Panosu'nu açar
      await root.locator('[data-slot="mydash-widget"][data-type="type_heatmap"] [data-slot="mydash-heat-cell"][data-type="http"] button').click()
      await expect(page).toHaveURL(/tab=monitoring/)
      await expect(page).toHaveURL(/mo_type=http/)
    })

    test('2026-10-13: renkli panel duvarı — 14 tek göstergeli panel; renkli zemin, kesilme / taşma yok, hedefler ≥ 40 px', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page, { noc: true })
      await page.goto('/?tab=mydash')
      const root = page.locator('[data-slot="mydash-page"]')
      await expect(root.locator('[data-slot="mydash-widget"]')).toHaveCount(7, { timeout: 20_000 })
      await page.locator('[data-slot="mydash-edit"]').click()
      await page.locator('[data-slot="mydash-add"]').click()
      await expect(page.locator('[data-slot="mydash-catalog-group"][data-group="stats"]')).toBeVisible()
      await page.locator('[data-slot="mydash-preset-stat-apply"]').click()
      await page.getByRole('dialog', { name: /Apply the colour panel wall|Renkli panel duvarı uygulansın/ })
        .getByRole('button', { name: /^(Apply this layout|Bu düzeni uygula)$/ }).click()
      await expect.poll(() => saved.at(-1)?.dashboard?.widgets?.length).toBe(14)
      await page.locator('[data-slot="mydash-edit"]').click()
      await expect(root.locator('[data-slot="mydash-widget"][data-type^="stat_"]')).toHaveCount(14)
      // Kritik alarm kırmızı, 30 gün turuncu, 7 gün kırmızı; renkli zeminli paneller
      await expect(root.locator('[data-slot="mydash-widget"][data-type="stat_alarms_critical"]')).toHaveAttribute('data-stat', 'on')
      await expect(root.locator('[data-slot="mydash-widget"][data-type="stat_alarms_critical"]')).toHaveAttribute('data-tone', 'danger')
      await expect(root.locator('[data-slot="mydash-widget"][data-type="stat_certs_month"]')).toHaveAttribute('data-tone', 'warning')
      await page.waitForTimeout(300)
      expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth), 'yatay taşma').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="mydash-page"]'), 'kırpılan metin').toEqual([])
      const overflow = await page.evaluate(() => [...document.querySelectorAll('[data-slot="mydash-widget"]')]
        .filter((w) => w.scrollWidth > w.clientWidth + 1).map((w) => w.getAttribute('data-type')))
      expect(overflow, 'kutu içi taşma').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="mydash-page"]'), 'küçük hedef').toEqual([])
      await page.screenshot({ path: `test-results/mydash-statwall-${vp.name}.png`, fullPage: true })
      await root.locator('[data-slot="mydash-widget"][data-type="stat_certs_month"] [data-slot="mydash-stat"]').click()
      await expect(page).toHaveURL(/c_win=30/)
    })

    test('TV kipi ekranı kaplar, sayfalar döner, Esc ile kapanır', async ({ page }) => {
      test.setTimeout(120_000)
      const dashboard = { v: 1, widgets: [
        { id: 'alerts-open', type: 'alerts_open', size: 'md' }, { id: 'monitor-health', type: 'monitor_health', size: 'md' },
        { id: 'status-services', type: 'status_services', size: 'lg' }, { id: 'oncall', type: 'oncall', size: 'sm' },
        { id: 'watchdog', type: 'watchdog', size: 'sm' }, { id: 'data-quality', type: 'data_quality', size: 'sm' },
        { id: 'cert-expiring', type: 'cert_expiring', size: 'sm' }, { id: 'activity', type: 'activity', size: 'md' }] }
      await mock(page, { dashboard })
      await page.goto('/?tab=mydash')
      await expect(page.locator('[data-slot="mydash-widget"]')).toHaveCount(8, { timeout: 20_000 })
      await page.locator('[data-slot="mydash-tv-open"]').click()
      const tv = page.locator('[data-slot="mydash-tv"]')
      await expect(tv).toBeVisible()
      await expect(page).toHaveURL(/md_tv=1/)
      const box = await tv.boundingBox()
      expect(Math.round(box.width)).toBe(vp.width)
      expect(Math.round(box.height)).toBe(vp.height)
      const pages = Number(await tv.getAttribute('data-pages'))
      expect(pages).toBeGreaterThan(1)
      await expect(tv.locator('[data-slot="mydash-tv-page"]:visible [data-slot="mydash-widget"]').first()).toBeVisible()
      // 2026-10-13: TV'de renkli paneller varsayılan (büyük sayısı olan kutular); düğmeyle ayrıntılıya döner
      await expect(tv.locator('[data-slot="mydash-widget"][data-stat="on"]').first()).toBeVisible()
      expect(await page.evaluate(clipped, '[data-slot="mydash-tv"]'), 'TV kırpılan metin').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="mydash-tv-bar"]'), 'TV küçük hedef').toEqual([])
      await page.screenshot({ path: `test-results/mydash-tv-${vp.name}.png` })
      await tv.locator('[data-slot="mydash-tv-next"]').click()
      await expect(tv).toHaveAttribute('data-page', '2')
      await expect(tv.locator('[data-slot="mydash-tv-page"][data-index="1"]')).toBeVisible()
      await expect(tv.locator('[data-slot="mydash-tv-page"][data-index="0"]')).toBeHidden()
      // Denetimler hareketsizlikte gizlenir, dokunuş/fare geri getirir
      await page.waitForTimeout(4500)
      await expect(tv).toHaveAttribute('data-controls', 'hidden')
      await page.mouse.move(vp.width / 2, vp.height / 2)
      await expect(tv).toHaveAttribute('data-controls', 'shown')
      await tv.locator('[data-slot="mydash-tv-panels"]').click()
      await expect(tv.locator('[data-slot="mydash-widget"][data-stat="on"]')).toHaveCount(0)
      await tv.locator('[data-slot="mydash-tv-panels"]').click()
      await expect(tv.locator('[data-slot="mydash-tv-page"][data-active="true"] [data-slot="mydash-widget"][data-stat="on"]').first()).toBeVisible()
      await page.keyboard.press('Escape')
      await expect(tv).toHaveCount(0)
      await expect(page).not.toHaveURL(/md_tv=1/)
    })
  })
}

// Duvar ekranı (1920×1080): her sayfa ekrana sığar — kaydırma gerekmez (duvarda kimse kaydırmaz), metin kesilmez.
test.describe('Panom TV — 1920×1080 duvar ekranı', () => {
  test.use({ viewport: { width: 1920, height: 1080 } })
  test('her sayfa kaydırmasız sığar', async ({ page }) => {
    test.setTimeout(120_000)
    const dashboard = { v: 1, widgets: [
      { id: 'alerts-open', type: 'alerts_open', size: 'md' }, { id: 'cert-expiring', type: 'cert_expiring', size: 'sm' },
      { id: 'oncall', type: 'oncall', size: 'sm' }, { id: 'monitor-health', type: 'monitor_health', size: 'md' },
      { id: 'storms', type: 'storms', size: 'sm' }, { id: 'watchdog', type: 'watchdog', size: 'sm' },
      { id: 'status-services', type: 'status_services', size: 'lg' }, { id: 'data-quality', type: 'data_quality', size: 'sm' },
      { id: 'activity', type: 'activity', size: 'md' }, { id: 'team-matrix', type: 'team_matrix', size: 'lg' },
      { id: 'service-grid', type: 'service_grid', size: 'md' }, { id: 'type-heatmap', type: 'type_heatmap', size: 'sm' }] }
    await mock(page, { dashboard })
    await page.goto('/?tab=mydash&md_tv=1')
    const tv = page.locator('[data-slot="mydash-tv"]')
    await expect(tv.locator('[data-slot="mydash-widget"]').first()).toBeVisible({ timeout: 20_000 })
    await expect(tv.locator('[data-slot="mydash-widget-foot"]').first()).toBeVisible()
    const pages = Number(await tv.getAttribute('data-pages'))
    for (let i = 0; i < pages; i++) {
      await expect(tv).toHaveAttribute('data-page', String(i + 1))
      await page.waitForTimeout(250)
      // Görsel sığma: etkin sayfanın (ölçekli) alt kenarı ekranın içinde; ölçek okunur sınırın üstünde
      const fit = await page.evaluate(() => {
        const m = document.querySelector('[data-slot="mydash-tv"] main')
        const g = m.querySelector('[data-slot="mydash-tv-page"][data-active="true"]')
        return { over: Math.round(g.getBoundingClientRect().bottom - m.getBoundingClientRect().bottom), scale: Number(m.getAttribute('data-fit')) }
      })
      expect(fit.over, `sayfa ${i + 1} taşıyor`).toBeLessThanOrEqual(1)
      expect(fit.scale, `sayfa ${i + 1} çok küçültüldü`).toBeGreaterThanOrEqual(0.85)
      expect(await page.evaluate(clipped, '[data-slot="mydash-tv"]'), 'kırpılan metin').toEqual([])
      await page.screenshot({ path: `test-results/mydash-tv-1080-p${i + 1}.png` })
      if (i < pages - 1) await tv.locator('[data-slot="mydash-tv-next"]').click()
    }
  })
})

// TV yoğunluğu (2026-10-13, kullanıcı isteği: "TV modunda bir ekranda daha fazla kutu; büyüklüğü ona göre; boş alanlar
// kullanılsın"): 1920 × 1080'de 14 renkli panel varsayılan "Sık"ta TEK ekrana sığar (eski düzende iki sayfa), "Rahat" iki
// sayfa, "Çok sık" tek; az kutulu sayfa ekranı doldurur; yazılar hücreyle ölçeklenir, kesilmez, taşmaz.
test.describe('Panom TV — yoğunluk 1920×1080', () => {
  test.use({ viewport: { width: 1920, height: 1080 } })
  test('daha çok kutu tek ekranda; seyrek sayfa ekranı doldurur; okunur yazı', async ({ page }) => {
    test.setTimeout(120_000)
    const dashboard = { v: 1, widgets: STAT_DEFS.map((d) => ({ id: d.type.replace(/_/g, '-'), type: d.type, size: 'sm' })) }
    await mock(page, { dashboard, noc: true })
    await page.goto('/?tab=mydash&md_tv=1')
    const tv = page.locator('[data-slot="mydash-tv"]')
    await expect(tv.locator('[data-slot="mydash-widget"][data-stat="on"]').first()).toBeVisible({ timeout: 20_000 })
    await expect(tv.locator('[data-slot="mydash-tv-density"]')).toHaveValue('compact')
    await expect(tv).toHaveAttribute('data-pages', '1')
    const active = tv.locator('[data-slot="mydash-tv-page"][data-active="true"]')
    await expect(active.locator('[data-slot="mydash-widget"]')).toHaveCount(14)
    await page.waitForTimeout(400)
    const check = async (label) => {
      const r = await page.evaluate(() => {
        const m = document.querySelector('[data-slot="mydash-tv"] main')
        const g = m.querySelector('[data-slot="mydash-tv-page"][data-active="true"]')
        const mr = m.getBoundingClientRect()
        const cs = getComputedStyle(m)
        const innerBottom = mr.bottom - parseFloat(cs.paddingBottom)
        const values = [...g.querySelectorAll('[data-slot="mydash-stat-value"]')].map((v) => parseFloat(getComputedStyle(v).fontSize))
        const outside = [...g.querySelectorAll('[data-slot="mydash-widget"]')]
          .filter((w) => { const b = w.getBoundingClientRect(); return b.bottom > innerBottom + 1 || b.right > mr.right + 1 }).length
        const overflow = [...g.querySelectorAll('[data-slot="mydash-widget"] [data-slot="card"]')]
          .filter((c) => c.scrollHeight > c.clientHeight + 1 || c.scrollWidth > c.clientWidth + 1)
          .map((c) => `${c.closest('[data-slot="mydash-widget"]').getAttribute('data-type')} ${c.scrollHeight}/${c.clientHeight}`)
        return { fit: Number(m.getAttribute('data-fit')), gap: Math.round(innerBottom - g.getBoundingClientRect().bottom),
          minFont: Math.min(...values), outside, overflow }
      })
      expect(r.fit, `${label}: ölçeklenmedi`).toBe(1)
      expect(r.outside, `${label}: ekran dışında kutu`).toBe(0)
      expect(r.overflow, `${label}: kart içi taşma`).toEqual([])
      expect(r.gap, `${label}: sayfa ekranı doldurur (alt boşluk)`).toBeLessThanOrEqual(24)
      expect(r.minFont, `${label}: değer okunur`).toBeGreaterThanOrEqual(28)
      expect(await page.evaluate(clipped, '[data-slot="mydash-tv"]'), `${label}: kırpılan metin`).toEqual([])
    }
    await check('Sık')
    await page.screenshot({ path: 'test-results/mydash-tv-density-compact.png' })

    await tv.locator('[data-slot="mydash-tv-density"]').selectOption('comfortable')
    await expect(tv).toHaveAttribute('data-pages', '2')
    await expect(tv).toHaveAttribute('data-page', '1')
    await page.waitForTimeout(300)
    await check('Rahat s1')
    await tv.locator('[data-slot="mydash-tv-next"]').click()
    await expect(tv).toHaveAttribute('data-page', '2')
    // Son sayfadaki 6 panel 4 × 2 ızgarada iki boş hücre bırakmaz — kendi ızgarası (3 × 2) ekranı doldurur
    await expect(tv.locator('[data-slot="mydash-tv-page"][data-active="true"]')).toHaveAttribute('data-cols', '3')
    await page.waitForTimeout(300)
    await check('Rahat s2')
    await page.screenshot({ path: 'test-results/mydash-tv-density-comfortable-p2.png' })

    await tv.locator('[data-slot="mydash-tv-density"]').selectOption('dense')
    await expect(tv).toHaveAttribute('data-pages', '1')
    await page.waitForTimeout(300)
    await check('Çok sık')
    expect(await page.evaluate(() => JSON.parse(localStorage.getItem('sm.mydash.tv')).density)).toBe('dense')
  })
})

// Küçük duvar ekranları (inceleme 2026-10-13): Çok sık / Sık yoğunlukta 1366 × 768 ve 1280 × 720'de uzun alt satırlı paneller
// (bakım, olaylar, izleme sağlığı) dahil HİÇBİR kart taşmaz / kırpılmaz, sayfa küçültülmez; panel hücresinde liste gövdesi yok.
for (const vp of [{ width: 1366, height: 768 }, { width: 1280, height: 720 }, { width: 1440, height: 900, failAlerts: true }]) {
  test.describe(`Panom TV — küçük duvar ${vp.width}×${vp.height}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height } })
    test('Çok sık ve Sık: taşma, kırpılma, küçültme yok', async ({ page }) => {
      test.setTimeout(120_000)
      const extra = ['maintenance', 'incidents_open', 'monitor_health', 'noc_light', 'storms', 'data_quality']
      const dashboard = { v: 1, widgets: [
        ...STAT_DEFS.map((d) => ({ id: d.type.replace(/_/g, '-'), type: d.type, size: 'sm' })),
        ...extra.map((type) => ({ id: type.replace(/_/g, '-'), type, size: 'md' })),
      ] }
      await mock(page, { dashboard, noc: true })
      // 1440 × 900: alarm özeti ilk yüklemede 500 — panel hücrelerinde hata durumu da sığmalı (inceleme 2026-10-13)
      if (vp.failAlerts) {
        await page.route((u) => new URL(u).pathname === '/api/me/open-alerts', (route) => route.fulfill({
          status: 500, contentType: 'application/json', body: JSON.stringify({ success: false, code: 'INTERNAL', error: 'Sunucu şu an yanıt vermiyor; birkaç dakika sonra yeniden deneyin.' }),
        }))
      }
      await page.addInitScript(() => { try { localStorage.setItem('sm.mydash.tv', JSON.stringify({ interval: 0, panels: 'stat', density: 'dense' })) } catch { /* yok */ } })
      await page.goto('/?tab=mydash&md_tv=1')
      const tv = page.locator('[data-slot="mydash-tv"]')
      await expect(tv.locator('[data-slot="mydash-widget"]').first()).toBeVisible({ timeout: 20_000 })
      for (const density of ['dense', 'compact']) {
        await tv.locator('[data-slot="mydash-tv-density"]').selectOption(density)
        const pages = Number(await tv.getAttribute('data-pages'))
        for (let i = 0; i < pages; i++) {
          await expect(tv).toHaveAttribute('data-page', String(i + 1))
          await page.waitForTimeout(350)
          const r = await page.evaluate(() => {
            const m = document.querySelector('[data-slot="mydash-tv"] main')
            const g = m.querySelector('[data-slot="mydash-tv-page"][data-active="true"]')
            // Dikdörtgen denetimi: kartın HER görünür öğesi kartın içinde (scrollHeight, içeriğin alt satırla çakışmasını görmez)
            const over = []
            for (const c of g.querySelectorAll('[data-slot="mydash-widget"] [data-slot="card"]')) {
              const cr = c.getBoundingClientRect()
              const bad = [...c.querySelectorAll('*')].filter((el) => {
                if (el.closest('.sr-only')) return false
                const r = el.getBoundingClientRect()
                return r.width > 0 && r.height > 0 && (r.bottom > cr.bottom + 1 || r.right > cr.right + 1 || r.top < cr.top - 1)
              })
              if (bad.length) over.push(`${c.closest('[data-slot="mydash-widget"]').getAttribute('data-type')} ${bad[0].tagName}`)
            }
            const listInCell = [...g.querySelectorAll('[data-slot="mydash-widget"]')]
              .filter((w) => w.querySelector('[data-slot="card"]')?.style.containerType === 'size'
                && w.querySelector('[data-slot="mydash-widget-body"]:not([data-placeholder]) ul, [data-slot="mydash-widget-body"]:not([data-placeholder]) table'))
              .map((w) => w.getAttribute('data-type'))
            return { fit: Number(m.getAttribute('data-fit')), clipped: m.hasAttribute('data-clipped'), over, listInCell }
          })
          const label = `${density} s${i + 1}`
          expect(r.over, `${label}: kart taşması`).toEqual([])
          expect(r.listInCell, `${label}: panel hücresinde liste`).toEqual([])
          expect(r.clipped, `${label}: sayfa kayıyor`).toBe(false)
          expect(r.fit, `${label}: çok küçültüldü`).toBeGreaterThanOrEqual(0.85)
          expect(await page.evaluate(clipped, '[data-slot="mydash-tv"]'), `${label}: kırpılan metin`).toEqual([])
          if (i === 0) await page.screenshot({ path: `test-results/mydash-tv-small-${vp.width}-${density}.png` })
          if (vp.failAlerts && i === 0) await expect(tv.locator('[data-slot="mydash-widget-failed"][data-short="true"]').first()).toBeVisible()
          if (i < pages - 1) await tv.locator('[data-slot="mydash-tv-next"]').click()
        }
      }
    })
  })
}
