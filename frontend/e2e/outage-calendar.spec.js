// Alarm Geçmişi "Takvim" görünümü — takım kesinti takvimi (2026-11-19) — gerçek tarayıcıda.
//
// Alarm Geçmişi'nde "Liste | Takvim" anahtarı ile takvime geçilir (URL `cal=1`), birincil takımın bu ayı istenir; önceki
// aya geçilir (`calm`), günler kova rengiyle çizilir (kesintisiz gün nötr). Güne basınca liste görünümü o takım + o günle
// açılır: istek `teamId`, `since` / `until` (o günün sınırları) ve `range=active` taşır, görünüm Tümü (`resolved` yok).
// Telefon (390, dokunmatik), tablet (768, dokunmatik), dizüstü (1280): taşma yok, metin kesilmez (uzun takım adı sarar),
// gün hücreleri ve düğmeler dokunmatikte ≥ 40 px; dar ekranda "Kesintili günler" listesi, geniş ekranda hücre ayrıntısı.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { GATE_VIEWPORTS, expectLayout, pageOverflow, json } from './support/gateChecks.js'

const LONG_TEAM = 'Kurumsal ve Bireysel Bankacılık Ağ Operasyonları / Takım A (İstanbul Veri Merkezi Nöbet Ekibi)'
const TEAMS = [{ id: 1, name: LONG_TEAM }, { id: 2, name: 'Takım B' }]

const pad = (n) => String(n).padStart(2, '0')
/** İstanbul (UTC+3) ayı. */
function istMonth(offset = 0) {
  const d = new Date(Date.now() + 3 * 3_600_000)
  const idx = d.getUTCFullYear() * 12 + d.getUTCMonth() + offset
  return `${Math.floor(idx / 12)}-${pad((idx % 12) + 1)}`
}
const daysIn = (month) => new Date(Date.UTC(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0)).getUTCDate()

/** Ayın tüm günleri geçmişse dolu yanıt; bu ayda bugünden sonrası null (gelecek). */
function calendarFor(month, teamId) {
  const today = (() => { const d = new Date(Date.now() + 3 * 3_600_000); return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}` })()
  const minutesFor = [0, 10, 45, 135, 0, 0, 600, 1440, 30]
  const days = []
  let total = 0
  for (let d = 1; d <= daysIn(month); d++) {
    const day = `${month}-${pad(d)}`
    if (day > today) { days.push({ day, outage_minutes: null, alarms: 0, worst_level: null }); continue }
    const m = minutesFor[d % minutesFor.length]
    total += m
    days.push({ day, outage_minutes: m, alarms: m ? (d % 3) + 1 : 0, worst_level: m > 240 ? 'CRITICAL' : m > 30 ? 'HIGH' : m ? 'WARNING' : null })
  }
  return { success: true, data: { month, team_id: teamId, team_name: TEAMS.find((x) => x.id === teamId)?.name ?? 'Takım', days, total_minutes: total, truncated: false } }
}

/** Saklama süresi (inceleme 2026-11-19): ayın ilk 14 günü "veri yok", en eski ay bu ay. */
function partialFor(month, teamId) {
  const r = calendarFor(month, teamId)
  r.data.days = r.data.days.map((d) => (Number(d.day.slice(8)) <= 14
    ? { day: d.day, outage_minutes: null, alarms: 0, worst_level: null, no_data: true } : d))
  r.data.total_minutes = r.data.days.reduce((s, d) => s + (d.outage_minutes || 0), 0)
  Object.assign(r.data, { retention_days: 365, covered_from: `${month}-15`, oldest_month: month })
  return r
}

async function mockCalendar(page, { partialMonth = null } = {}) {
  const seen = { calendar: [], list: [] }
  await mockApi(page, { role: 'ADMIN', globalAdmin: true, teamIds: [1] })
  await page.route((u) => new URL(u).pathname === '/api/admin/teams', (route) => route.fulfill(json({ success: true, data: TEAMS })))
  await page.route((u) => new URL(u).pathname.startsWith('/api/admin/alerts'), async (route) => {
    const url = new URL(route.request().url())
    const p = url.pathname
    if (p === '/api/admin/alerts/outage-calendar') {
      const team = Number(url.searchParams.get('team'))
      const month = url.searchParams.get('month')
      seen.calendar.push({ team, month })
      return route.fulfill(json(month === partialMonth ? partialFor(month, team) : calendarFor(month, team)))
    }
    if (p === '/api/admin/alerts' && route.request().method() === 'GET') {
      const params = Object.fromEntries(url.searchParams.entries())
      if (params.size !== '1') seen.list.push(params)
      const rows = [{ id: 501, domain: 'https://odeme.example.com/', alert_type: 'HTTP_DOWN', alert_level: 'HIGH', resolved: true,
        acknowledged: false, created_at: '2026-01-01T07:00:00', resolved_at: '2026-01-01T09:15:00', team_id: 1, team_name: LONG_TEAM,
        message: 'HTTP 503', act_scope: true, notify_scope: true }]
      return route.fulfill(json({ success: true, data: params.size === '1' ? [] : rows, total: params.size === '1' ? 0 : 1,
        type_counts: { HTTP_DOWN: 1 }, level_counts: {}, unacked_total: 0, stale_total: 0, stale_hours: 24, can_act: true, noc_can_write: false }))
    }
    return route.fallback()
  })
  return seen
}

const param = (page, k) => new URL(page.url()).searchParams.get(k)

for (const vp of GATE_VIEWPORTS) {
  test.describe(`Alarm Geçmişi kesinti takvimi — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('anahtar → takvim (birincil takım), önceki ay, yerleşim; güne basınca liste o takım + o günle', async ({ page }) => {
      test.setTimeout(150_000)
      const seen = await mockCalendar(page)
      await page.goto('/?tab=alerthistory')
      const sw = page.locator('[data-slot="alert-history-view-switch"]')
      await expect(sw).toBeVisible({ timeout: 60_000 })
      await sw.getByRole('button', { name: 'Calendar' }).click()

      const cal = page.locator('[data-slot="outage-calendar"]')
      await expect(cal).toBeVisible({ timeout: 30_000 })
      await expect.poll(() => seen.calendar.at(-1)).toEqual({ team: 1, month: istMonth(0) })
      await expect.poll(() => param(page, 'cal')).toBe('1')
      // Liste sekmeleri takvimde yok
      await expect(page.getByRole('tablist', { name: 'Alert view' })).toHaveCount(0)

      // Önceki ay: tüm günler geçmiş — URL calm yazılır
      await page.locator('[data-slot="outage-cal-prev"]').click()
      const prev = istMonth(-1)
      await expect.poll(() => seen.calendar.at(-1)).toEqual({ team: 1, month: prev })
      await expect.poll(() => param(page, 'calm')).toBe(prev)
      await expect(cal).toHaveAttribute('data-month', prev)
      await expect(page.locator('[data-slot="outage-cal-day"][data-bucket="future"]')).toHaveCount(0)
      await expect(page.locator('[data-slot="outage-cal-day"]')).toHaveCount(daysIn(prev))
      // Kesintisiz gün nötr (renk yok); 1440 dk'lık gün en koyu kova
      await expect(page.locator(`[data-slot="outage-cal-day"][data-day="${prev}-05"]`)).toHaveAttribute('data-bucket', 'zero')
      await expect(page.locator(`[data-slot="outage-cal-day"][data-day="${prev}-07"]`)).toHaveAttribute('data-bucket', '4')

      // Uzun takım adı özet satırında sarar (kesilmez)
      const summary = page.locator('[data-slot="outage-cal-summary"]')
      await expect(summary).toContainText(LONG_TEAM)
      if (vp.width <= 768) {
        // Dar ekran: kesintili günler listesi görünür, hücre ayrıntısı gizli
        await expect(page.locator('[data-slot="outage-cal-list"]')).toBeVisible()
      } else {
        await expect(page.locator('[data-slot="outage-cal-list"]')).toBeHidden()
        await expect(page.locator(`[data-slot="outage-cal-day"][data-day="${prev}-03"]`)).toContainText('2 h 15 min')
      }
      await expectLayout(page, '[data-slot="outage-calendar"]', { touch: vp.touch, label: `takvim @${vp.name}` })
      // Başlık denetimleri (takım seçici, ay gezinmesi) kartın İÇİNDE kalır — sabit genişlikli seçici dar başlık sütununda taşıyordu
      const outside = await page.evaluate(() => {
        const card = document.querySelector('[data-slot="outage-calendar"]').getBoundingClientRect()
        return [...document.querySelectorAll('[data-slot="outage-cal-team"], [data-slot="outage-cal-team"] button, [data-slot="outage-cal-next"], [data-slot="outage-cal-refresh"]')]
          .map((n) => n.getBoundingClientRect()).filter((r) => r.width > 0 && (r.right > card.right - 1 || r.left < card.left + 1)).length
      })
      expect(outside, 'kartın dışına taşan başlık denetimi').toBe(0)
      // Gün hücreleri dokunmatikte ≥ 40 px (expectLayout de ölçer; burada açıkça bir hücre)
      const cellBox = await page.locator(`[data-slot="outage-cal-day"][data-day="${prev}-03"]`).boundingBox()
      if (vp.touch) { expect(cellBox.width).toBeGreaterThanOrEqual(39.5); expect(cellBox.height).toBeGreaterThanOrEqual(39.5) }

      // Güne bas → liste o takım + o gün
      const day = `${prev}-03`
      const before = seen.list.length
      await page.locator(`[data-slot="outage-cal-day"][data-day="${day}"]`).click()
      await expect(cal).toHaveCount(0)
      await expect.poll(() => seen.list.length).toBeGreaterThan(before)
      await expect.poll(() => seen.list.at(-1)?.range).toBe('active')
      const last = seen.list.at(-1)
      expect(last.teamId).toBe('1')
      expect(last.since).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
      expect(last.until).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
      expect(last.resolved).toBeUndefined()
      await expect.poll(() => param(page, 'from')).toBe(day)
      await expect.poll(() => param(page, 'cal')).toBeNull()
      expect(param(page, 'view')).toBe('all')
      await expect(page.getByRole('tab', { name: 'All' })).toHaveAttribute('aria-selected', 'true')
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
    })

    test('saklama süresi: veri yok günleri tıklanamaz ve nötr, bilgi bandı sığar, en eski aydan geri gidilmez', async ({ page }) => {
      test.setTimeout(150_000)
      const prev = istMonth(-1)
      const seen = await mockCalendar(page, { partialMonth: prev })
      await page.goto(`/?tab=alerthistory&cal=1&calm=${prev}`)
      const cal = page.locator('[data-slot="outage-calendar"]')
      await expect(cal).toBeVisible({ timeout: 60_000 })
      await expect.poll(() => seen.calendar.at(-1)).toEqual({ team: 1, month: prev })
      await expect(page.locator('[data-slot="outage-cal-day"][data-bucket="nodata"]')).toHaveCount(14)
      await expect(page.locator(`[data-slot="outage-cal-day"][data-day="${prev}-05"]`)).toHaveJSProperty('tagName', 'DIV')
      const banner = page.locator('[data-slot="outage-cal-partial"]')
      await expect(banner).toContainText('No data for part of this month')
      await expect(banner).toContainText('365 days')
      await expect(page.locator('[data-slot="outage-cal-prev"]')).toBeDisabled()
      await expect(page.locator('[data-slot="outage-cal-legend"]')).toContainText('No data (retention)')
      await expect(page.locator('[data-slot="outage-cal-summary"]')).toHaveAttribute('data-partial', 'true')
      await expectLayout(page, '[data-slot="outage-calendar"]', { touch: vp.touch, label: `takvim veri yok @${vp.name}` })
      // Veri yok gününe basmak listeye geçmez
      const before = seen.list.length
      await page.locator(`[data-slot="outage-cal-day"][data-day="${prev}-05"]`).click({ force: true })
      await page.waitForTimeout(300)
      await expect(cal).toBeVisible()
      expect(seen.list.length).toBe(before)
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
      await cal.screenshot({ path: `test-results/outage-calendar-partial-${vp.name}.png` })
    })
  })
}
