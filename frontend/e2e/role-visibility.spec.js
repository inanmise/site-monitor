// Rol görünürlüğü (2026-11-15, prod kapısı e2e paketi): USER / AUDIT / kapsamlı yönetici ("müdür") / 7/24 operatörü (+ global
// yönetici denetim kolu) için gerçek uygulama rotasında, API taklitli. Ölçülenler:
//   1) menü — SQL Playground yalnız global yöneticide; Yönetici özeti yalnız ADMIN (kapsamlı dâhil) ve AUDIT'te; kanal
//      raporları modülü kapalı kapsamda (`scope.channel_reports:false`) çizilmez;
//   2) yazma düğmeleri — AUDIT'te "Yeni izleme" yok; satır `can_edit:false` iken kartta Düzenle / Kopyala / Sil yok;
//   3) YASAK İSTEK YOK (istek kaydıyla) — `alerts.forwards` görüntüleme izni yokken izleme detayı
//      `/api/alert-forwards/for-monitor` ÇAĞIRMAZ (2026-11-06 kuralı; izinli rolde çağrılır = karşı kanıt); İstatistikler
//      `alerts.read` olmadan gürültü / takım alarm uçlarını, `data_quality.view` olmadan veri kalitesini sormaz; rolüne kapalı
//      sekme (`?tab=sqlplayground|settings|executive`) "Erişim yok" panelini gösterir ve o sayfanın ucunu ÇAĞIRMAZ;
//   4) yerleşim — sayfa ve "Erişim yok" paneli: taşma / kırpma yok, dokunmatikte hedefler ≥ 40 px.
import { test, expect } from '@playwright/test'
import { mockApi, MONITORS } from './support/monitorMocks.js'
import { VIEWPORTS, json, pathIs, checkLayout, recordRequests } from './support/layoutAudit.js'

const READ_ALL = { 'monitoring.read': { view: true }, 'alerts.read': { view: true }, 'data_quality.view': { view: true }, 'monitoring.crud': { view: true, edit: true } }
const SCOPE = { global_admin: false, monitoring_all: false, noc_operator: false, noc_console: false }

const ROLES = [
  {
    key: 'user', label: 'USER',
    me: { system_role: 'USER', global_admin: false },
    perms: { 'monitoring.read': { view: true }, 'monitoring.crud': { view: true, edit: true }, 'alerts.read': { view: false },
      'data_quality.view': { view: false }, 'alerts.forwards': { view: false, edit: false } },
    scope: { ...SCOPE, channel_reports: false },
    rowsEditable: true,
    expect: { nav: { sqlplayground: 0, executive: 0, channelreports: 0, permissions: 1 }, newMonitor: true, cardEdit: true,
      forwards: false, statsAlerts: false, statsDq: false, restricted: ['sqlplayground', 'settings', 'executive'] },
  },
  {
    key: 'audit', label: 'AUDIT',
    me: { system_role: 'AUDIT', global_admin: false },
    perms: { ...READ_ALL, 'alerts.forwards': { view: false, edit: false } },
    scope: { ...SCOPE, monitoring_all: true },
    rowsEditable: false,
    expect: { nav: { sqlplayground: 0, executive: 1, channelreports: 1, permissions: 1 }, newMonitor: false, cardEdit: false,
      forwards: false, statsAlerts: true, statsDq: true, restricted: ['sqlplayground', 'settings'] },
  },
  {
    key: 'scoped', label: 'kapsamlı yönetici',
    me: { system_role: 'ADMIN', global_admin: false, manage_team_ids: [1], view_team_ids: [1] },
    perms: { ...READ_ALL, 'alerts.forwards': { view: true, edit: true } },
    scope: { ...SCOPE },
    rowsEditable: true,
    expect: { nav: { sqlplayground: 0, executive: 1, channelreports: 1, permissions: 1 }, newMonitor: true, cardEdit: true,
      forwards: true, statsAlerts: true, statsDq: true, restricted: ['sqlplayground'] },
  },
  {
    key: 'noc', label: '7/24 operatörü',
    me: { system_role: 'USER', global_admin: false, noc_operator: true, noc_can_write: true },
    perms: { ...READ_ALL, 'noc_calls.write': { view: true, edit: true }, 'alerts.forwards': { view: false, edit: false } },
    scope: { ...SCOPE, monitoring_all: true, noc_operator: true, noc_console: true },
    rowsEditable: false,
    expect: { nav: { sqlplayground: 0, executive: 0, channelreports: 1, permissions: 1 }, newMonitor: true, cardEdit: false,
      forwards: false, statsAlerts: true, statsDq: true, restricted: ['sqlplayground', 'settings', 'executive'] },
  },
  {
    key: 'global', label: 'global yönetici (denetim kolu)',
    me: { system_role: 'ADMIN', global_admin: true },
    perms: { ...READ_ALL, 'alerts.forwards': { view: true, edit: true } },
    scope: { ...SCOPE, global_admin: true, monitoring_all: true },
    rowsEditable: true,
    expect: { nav: { sqlplayground: 1, executive: 1, channelreports: 1, permissions: 1 }, newMonitor: true, cardEdit: true,
      forwards: true, statsAlerts: true, statsDq: true, restricted: [] },
  },
]

/** Rolüne kapalı sekmenin açılışta çağıracağı uç (çağrılmamalı). */
const FORBIDDEN_PREFIX = { sqlplayground: '/api/admin/sql', settings: '/api/admin/general', executive: '/api/executive-summary' }

async function routes(page, role) {
  await mockApi(page, { role: role.me.system_role, globalAdmin: role.me.global_admin, perms: role.perms })
  // mockApi'den SONRA kaydedilen yollar önceliklidir
  await page.route(pathIs('/api/me'), json({
    success: true, username: 'demo', weekly_reports_visible: true, team_id: 1, team_name: 'Takım A', team_ids: [1], team_names: ['Takım A'],
    tour: { status: 'dismissed', version: 99 }, ...role.me,
  }))
  await page.route(pathIs('/api/me/permissions'), json({ success: true, data: role.perms, scope: role.scope }))
  const flags = role.rowsEditable ? { can_edit: true, can_check: true, can_delete: true } : { can_edit: false, can_check: false, can_delete: false }
  await page.route(pathIs('/api/monitoring/http'), (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    return json({ success: true, data: MONITORS.http.map((m) => ({ ...m, ...flags })) })(route)
  })
  await page.route(pathIs('/api/alert-forwards/for-monitor'), json({ success: true, data: { items: [], owner_teams: [], can_add: false } }))
  return recordRequests(page)
}

const has = (log, path) => log.some((r) => r.path === path)
const hasPrefix = (log, prefix) => log.some((r) => r.path.startsWith(prefix))

for (const vp of VIEWPORTS) {
  test.describe(`Rol görünürlüğü — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    for (const role of ROLES) {
      test(`${role.label}: menü, yazma düğmeleri, yasak istek yok, kapalı sekmeler`, async ({ page }) => {
        test.setTimeout(150_000)
        const log = await routes(page, role)
        const ex = role.expect

        // ── HTTP izleme sayfası: menü + "Yeni izleme" + kart düğmeleri ──
        await page.goto('/?tab=http')
        const cards = page.locator('.upt-grid > [data-slot="card"]')
        await expect(cards.first()).toBeVisible({ timeout: 20_000 })
        await expect.poll(() => has(log, '/api/me/permissions')).toBe(true)
        if (vp.width < 768) {
          await page.locator('[data-sidebar="trigger"]').first().click()
          await expect(page.locator('[data-mobile="true"][data-slot="sidebar"]')).toBeVisible()
        }
        for (const [tab, n] of Object.entries(ex.nav)) {
          await expect(page.locator(`[data-tour="nav-tab-${tab}"]`), `${role.key} menü: ${tab}`).toHaveCount(n)
        }
        if (vp.width < 768) {
          // Çekmece kendi "Kapat" düğmesiyle kapanır (kenar çubuğu tetiği)
          await page.locator('[data-mobile="true"][data-slot="sidebar"] [data-sidebar="trigger"]').first().click()
          await expect(page.locator('[data-mobile="true"][data-slot="sidebar"]')).toHaveCount(0)
        }
        await expect(page.locator('[data-tour="mon-new"]'), `${role.key}: Yeni izleme`).toHaveCount(ex.newMonitor ? 1 : 0)
        const editButtons = cards.getByRole('button', { name: /— (Edit|Düzenle)$/ })
        const dupButtons = cards.getByRole('button', { name: /— (Duplicate|Kopyala)$/ })
        if (ex.cardEdit) {
          await expect(editButtons.first()).toBeVisible()
        } else {
          await expect(editButtons).toHaveCount(0)
          await expect(dupButtons).toHaveCount(0)
          await expect(cards.getByRole('button', { name: /— (Delete|Sil)$/ })).toHaveCount(0)
        }
        await checkLayout(page, `${role.key} http sayfası @${vp.name}`, null)

        // ── İzleme detayı: alarm iletimi rozeti izin yokken İSTEK ATMAZ ──
        await cards.first().locator('[data-monitor-open]').first().click()
        const dlg = page.getByRole('dialog').first()
        await expect(dlg.locator('[data-slot="detail-tabs-list"]')).toBeVisible({ timeout: 15_000 })
        if (ex.forwards) {
          await expect.poll(() => has(log, '/api/alert-forwards/for-monitor'), `${role.key}: iletim rozeti sormalı`).toBe(true)
        } else {
          await page.waitForTimeout(1500)
          expect(log.filter((r) => r.path === '/api/alert-forwards/for-monitor'), `${role.key}: iletim rozeti istek atmamalı`).toEqual([])
          await expect(dlg.locator('[data-slot="monitor-forwards"]')).toHaveCount(0)
        }
        await page.keyboard.press('Escape')

        // ── İstatistikler: izinsiz kaynak sorulmaz ──
        log.length = 0
        await page.goto('/?tab=stats')
        await expect.poll(() => has(log, '/api/stats/executive'), { timeout: 20_000 }).toBe(true)
        await page.waitForTimeout(1500)
        expect(has(log, '/api/admin/alerts/noise'), `${role.key}: gürültü ucu`).toBe(ex.statsAlerts)
        expect(has(log, '/api/admin/alerts/team-stats'), `${role.key}: takım alarm ucu`).toBe(ex.statsAlerts)
        expect(has(log, '/api/data-quality'), `${role.key}: veri kalitesi ucu`).toBe(ex.statsDq)

        // ── Rolüne kapalı sekmeler: "Erişim yok" + o sayfanın ucu çağrılmaz ──
        for (const tab of ex.restricted) {
          log.length = 0
          await page.goto(`/?tab=${tab}`)
          const panel = page.locator('[data-slot="not-found-panel"][data-kind="restricted"]')
          await expect(panel, `${role.key}: ${tab} erişim yok paneli`).toBeVisible({ timeout: 20_000 })
          await expect(panel.locator('[data-slot="not-found-title"]')).toHaveText(/access|erişim/i)
          await page.waitForTimeout(800)
          expect(hasPrefix(log, FORBIDDEN_PREFIX[tab]), `${role.key}: ${tab} ucu çağrıldı`).toBe(false)
          await checkLayout(page, `${role.key} ${tab} erişim yok @${vp.name}`, '[data-slot="not-found-panel"]')
        }
      })
    }
  })
}
