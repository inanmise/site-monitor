// Olay kaydından kanal teknik hata raporuna (2026-11-20, ters giriş): olay ayrıntısında "Teknik hata raporuna ekle" penceresi
// (olayın takımının kanalı, kapanmış ay, var olan rapor) ve "Kullanıldığı raporlar" kartı. Gerçek tarayıcıda 390 (dokunmatik) /
// 768 (dokunmatik) / 1280: pencere ve kart sığar, uzun adlar sarar (kırpılmaz), dokunmatik hedefler ≥ 40 px; ekleme isteği
// sözleşmedeki gövdeyle gider ve rapora gidilir. API mock'lu (bildirim yok), saat sabit (İstanbul).
import { test, expect } from '@playwright/test'

const NOW = new Date('2026-11-16T12:42:00Z')
const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const LONG_CHANNEL = 'Bireysel İnternet Şubesi ve Mobil Bankacılık Ortak Kanalı (Dijital Kanallar / Bireysel Bankacılık)'
const LONG_INCIDENT = 'Ödeme API ve Kart Doğrulama Servislerinde Bağlantı Havuzu Tükenmesi (Mobil / İnternet Şubesi Ortak Altyapı)'
const INCIDENT = {
  id: 9, title: LONG_INCIDENT, occurred_at: '2026-09-12T11:00:00', detected_at: '2026-09-12T11:05:00', resolved_at: '2026-09-12T12:30:00',
  severity: 'CRITICAL', status: 'RESOLVED', category: 'NETWORK', team_id: 5, team_name: LONG_TEAM, sla_breached: false,
  tech_error_count: 1250, outage_related: true, outage_id: 'INC-2026-0915', affected_customers: 320,
}
const ISSUES = { id: 'issues', kind: 'issues', title: 'Anormal Artış Gösteren Hatalar', item_title: '{konu}: Hata Artışı',
  description_label: 'Ne oldu?', customers_label: 'Etkilenen müşteri sayısı', errors_label: 'Bu konudan kaynaklı teknik hata adedi',
  ask_time: true, ask_outage: true, ask_impact: true, no_impact_display: 'hide_count', allow_images: true }
const TEMPLATE = { v: 1, lang: 'tr', title: '{kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}', sections: [ISSUES] }
const channel = (id, name, teamId = 5) => ({ id, team_id: teamId, team_name: teamId === 5 ? LONG_TEAM : 'Kurumsal', name, description: null,
  active: true, sort_order: null, template_version: 3, lang: 'tr', can_edit: true, can_report: true, can_delete: false, report_count: 1,
  period_report: null })
const reportDetail = (links = []) => ({
  report: { id: 100, channel_id: 10, team_id: 5, year: 2026, month: 9, status: 'draft', send_state: null, last_send_status: null,
    updated_at: '2026-10-02T07:00:00Z', last_sent_at: null, version: 4, title: '', updated_by_name: 'Ayşe Yılmaz', template_version: 3 },
  channel: channel(10, LONG_CHANNEL), template: TEMPLATE, values: { fields: {}, issues: {}, texts: {} }, images: [], previous: null,
  can_edit: true, can_delete: true, team_email_set: true, template_update: { differs: false, lost_values: 0, lost_images: 0 },
  incident_links: links,
})
/** İçe alma sonrası rapor (sunucunun yanıtı ve sonraki okumalar): konu `values` içinde, bağı `incident_links` içinde. */
const importedDetail = () => {
  const d = reportDetail([{ section_id: 'issues', issue_id: 'i-9', incident_id: 9, state: 'ok', title: LONG_INCIDENT, status: 'RESOLVED',
    occurred_at: INCIDENT.occurred_at, pinned: [], notes: [] }])
  d.report.version = 5
  d.values.issues = { issues: [{ id: 'i-9', topic: LONG_INCIDENT, title: '', title_auto: true, description: '**Kök neden**\nHavuz doldu.',
    date: '2026-09-12', start: '14:00', end: '15:30', errors: 1250, outage: true, outage_id: 'INC-2026-0915', impact: true, customers: 320 }] }
  return d
}

let imports = []
let usageCalls = 0

async function mock(page) {
  imports = []
  usageCalls = 0
  await page.addInitScript(() => {
    try {
      localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 }))
      localStorage.setItem('site-monitor-lang', 'tr')
      // Rapora gidince içe alınan konu görünsün: bölümler ve konular açık (kişisel kayıt sahibi yazılır — yoksa silinir)
      localStorage.setItem('sm.storage.owner', 'demo')
      localStorage.setItem('sm.te.open.r.100', JSON.stringify({ all: true }))
      localStorage.setItem('sm.te.open.ri.100', JSON.stringify({ all: true }))
      // Girişten sonra açılan "eksikler" penceresi (kanalın dönem raporu eksik = görev) tıklamaları örtmesin
      sessionStorage.setItem('sm.gaps.prompted', 'demo')
    } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    let body = { success: true, data: [] }
    if (p === '/api/me') body = { success: true, username: 'demo', system_role: 'USER', global_admin: false, team_id: 5, team_ids: [5], tour: { status: 'dismissed', version: 99 } }
    else if (p === '/api/me/permissions') {
      body = { success: true, data: { 'incidents.view': { view: true }, 'tech_error_reports.read': { view: true }, 'tech_error_reports.crud': { edit: true } } }
    } else if (p === '/api/incidents/9') body = { success: true, data: INCIDENT }
    else if (p === '/api/incidents') body = { success: true, data: [INCIDENT], total: 1, page: 0, size: 20 }
    else if (p === '/api/incidents/trends') body = { success: true, data: { summary: { total: 1, open: 0, resolved: 1 }, by_severity: {}, by_status: {}, daily: [] } }
    else if (p === '/api/tech-error-reports/overview') {
      body = { success: true, data: { period: { year: 2026, month: 10 }, can_create: true, teams: [{ id: 5, name: LONG_TEAM }],
        channels: [channel(10, LONG_CHANNEL), channel(20, 'POS', 6)] } }
    } else if (p === '/api/tech-error-reports/channels/10/reports') {
      body = { success: true, data: { channel: channel(10, LONG_CHANNEL), default_period: { year: 2026, month: 10 },
        reports: [{ id: 100, year: 2026, month: 9, status: 'draft', title: `${LONG_CHANNEL} Kanalı Teknik Hata Mesajları - Eylül 2026` }] } }
    } else if (p === '/api/tech-error-reports/channels/10/template') {
      body = { success: true, data: { channel: channel(10, LONG_CHANNEL), template: TEMPLATE, version: 3 } }
    } else if (p === '/api/tech-error-reports/reports/100' && req.method() === 'GET') {
      body = { success: true, data: imports.length ? importedDetail() : reportDetail([]) }
    } else if (p === '/api/tech-error-reports/reports/100/incident-imports' && req.method() === 'POST') {
      imports.push(JSON.parse(req.postData() || '{}'))
      body = { success: true, data: { ...importedDetail(), imported: [{ incident_id: 9, issue_id: 'i-9', notes: [] }] } }
    } else if (p === '/api/tech-error-reports/incident-usage') {
      usageCalls++
      body = { success: true, data: { items: [
        { report_id: 80, channel_id: 10, channel_name: LONG_CHANNEL, team_name: LONG_TEAM, year: 2026, month: 8, status: 'sent',
          topic: LONG_INCIDENT, state: 'frozen_stale' },
        // hidden: olay başka bir takıma taşındı ya da okuma izni yok — rozet sığar, sarar
        { report_id: 81, channel_id: 10, channel_name: LONG_CHANNEL, team_name: LONG_TEAM, year: 2026, month: 7, status: 'draft',
          topic: LONG_INCIDENT, state: 'hidden' },
      ] } }
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

/** Görünür etkileşimli öğelerden 40 px'in altında kalanlar (dokunmatik): yükseklik, ya da ::after vuruş alanı. */
function smallTargets(root) {
  const out = []
  for (const b of root.querySelectorAll('button, select, input')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled) continue
    let h = r.height
    const after = getComputedStyle(b, '::after')
    if (after.position === 'absolute' && after.height && after.height !== 'auto') h = Math.max(h, parseFloat(after.height) || 0)
    if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

/** Kırpılan / taşan metin (tek düğümlü yaprak öğeler); ekran okuyucuya ayrılmış görünmez öğeler (≤ 2 px) sayılmaz. */
function clipped(root) {
  return [...root.querySelectorAll('span, p, label, button')]
    .filter((n) => { const r = n.getBoundingClientRect(); return r.width > 2 && r.height > 2 })
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 3
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis'))
    .map((n) => n.textContent.slice(0, 40))
}

const overflowX = () => document.documentElement.scrollWidth - document.documentElement.clientWidth

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Olay kaydından rapora — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch, timezoneId: 'Europe/Istanbul' })

    test('kullanıldığı raporlar kartı; "Teknik hata raporuna ekle" penceresi sığar, eklenir ve rapora gidilir', async ({ page }) => {
      test.setTimeout(120_000)
      await page.clock.setFixedTime(NOW)
      await mock(page)
      await page.goto('/?tab=incident-history&incident=9')
      const add = page.locator('[data-slot="ih-add-to-report"]')
      await expect(add).toBeVisible({ timeout: 20_000 })

      // Kullanıldığı raporlar: kapalı başlar, ilk açılışta okunur; satır uzun adları sarar
      const usage = page.locator('[data-slot="ih-usage"]')
      await expect(usage).toBeVisible()
      expect(usageCalls).toBe(0)
      await usage.locator('[data-slot="ih-usage-toggle"]').click()
      const row = usage.locator('[data-slot="ih-usage-row"]')
      await expect(row).toHaveCount(2)
      await expect(row.first()).toContainText(LONG_CHANNEL)
      await expect(row.first().locator('[data-slot="ih-usage-state"]')).toHaveText('Olay değişti')
      // Bu kartta hidden = olay başka takıma taşındı, o rapor artık izlemiyor (kendi cümlesi; uzun metin sarar)
      await expect(row.nth(1).locator('[data-slot="ih-usage-state"]')).toHaveText('Olay başka bir takıma taşındı; bu rapor artık onu izlemiyor')
      expect(usageCalls).toBe(1)
      await page.waitForTimeout(200)
      expect(await page.evaluate(overflowX), 'yatay taşma (kullanım kartı)').toBeLessThanOrEqual(1)
      expect(await usage.evaluate(clipped), 'kırpılan metin (kullanım kartı)').toEqual([])
      if (vp.touch) expect(await usage.evaluate(smallTargets), 'küçük hedef (kullanım kartı)').toEqual([])

      // Pencere: olayın takımının tek kanalı seçili, ay olayın ayı (Eylül 2026), var olan rapor
      await add.click()
      const dlg = page.getByRole('dialog', { name: 'Teknik hata raporuna ekle' })
      await expect(dlg).toBeVisible()
      const channelSelect = dlg.getByLabel('Kanal', { exact: true })
      await expect(channelSelect).toHaveValue('10')
      await expect(channelSelect.locator('option')).toHaveCount(2)
      await expect(dlg.getByLabel('Rapor ayı', { exact: true })).toHaveValue('2026-09')
      await expect(dlg.locator('[data-slot="inc-to-report-target"]')).toHaveAttribute('data-exists', 'true')
      await expect(dlg).toContainText('Bu ayın raporu var')
      await page.waitForTimeout(200)
      expect(await page.evaluate(overflowX), 'yatay taşma (pencere)').toBeLessThanOrEqual(1)
      expect(await dlg.evaluate(clipped), 'kırpılan metin (pencere)').toEqual([])
      if (vp.touch) expect(await dlg.evaluate(smallTargets), 'küçük hedef (pencere)').toEqual([])
      const box = await dlg.boundingBox()
      expect(box.x, 'pencere ekranın içinde').toBeGreaterThanOrEqual(-1)
      expect(box.x + box.width, 'pencere ekranın içinde').toBeLessThanOrEqual(vp.width + 1)

      await dlg.locator('[data-slot="inc-to-report-submit"]').click()
      await expect.poll(() => imports.length).toBe(1)
      expect(imports[0]).toEqual({ version: 4, section_id: 'issues', incident_ids: [9], parts: ['rca', 'impact', 'resolution'], images: true })
      // Rapora gidilir: içe alınan konu olay kaydına bağlı görünür
      await expect(page).toHaveURL(/tab=channelreports/, { timeout: 20_000 })
      await expect(page).toHaveURL(/te_rep=100/)
      await expect(page.locator('[data-slot="te-issue-incident"][data-incident="9"]')).toBeVisible({ timeout: 20_000 })
      await page.screenshot({ path: `test-results/incident-to-report-${vp.name}.png` })
    })
  })
}
