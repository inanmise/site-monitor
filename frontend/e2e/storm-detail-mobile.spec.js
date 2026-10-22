// Alarm Fırtınası ayrıntı penceresi telefonda (2026-09-30): pencere ekrana sığar, içindeki hiçbir görünür öğe sağa taşmaz;
// üyeler kart listesi olarak çizilir (tablo değil).
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

for (const vp of [{ name: 'phone', width: 390, height: 844 }, { name: 'tablet', width: 768, height: 1024 }]) {
  test(`fırtına ayrıntı penceresi — ${vp.name}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.goto('/?tab=storms&sf_storm=7')
    const dlg = page.getByRole('dialog').first()
    await dlg.waitFor({ timeout: 20_000 })
    await page.locator('[data-slot="sf-member"]').first().waitFor()
    const box = await dlg.boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(0)
    expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
    const overflow = await page.evaluate((vw) => [...document.querySelectorAll('[role="dialog"] *')]
      .filter((el) => { const r = el.getBoundingClientRect(); return r.width > 1 && r.right > vw + 1 && getComputedStyle(el).visibility !== 'hidden' })
      .filter((el) => { for (let p = el.parentElement; p; p = p.parentElement) { if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= vw + 1) return false } return true })
      .map((el) => el.tagName + '.' + String(el.className).slice(0, 40)).slice(0, 5), vp.width)
    expect(overflow).toEqual([])
    if (vp.name === 'phone') expect(await page.locator('li [data-slot="sf-member"]').count()).toBe(2)
  })
}

// 7/24 "Ara" penceresi — uzun AD adları ve unvanlar KIRPILMAZ (2026-10-22, UIM-38 / L-N6): ad ve unvan `truncate` ile tek
// satıra kırpılıyordu (dokunmatikte title görünmez). 60+ karakterlik gerçek biçimli AD adı: sarar, taşmaz, üç nokta yok.
const NOC_LONG_PERSON = 'Ayşe Yılmaz-Karaosmanoğlu (Dijital Kanallar Direktörlüğü / Kıdemli Platform Mühendisi)'
const NOC_LONG_TITLE = 'Kıdemli Platform Mühendisi — Ödeme Altyapısı ve Kurumsal Entegrasyon Nöbet Ekibi'
const NOC_TEAM = 'Platform Ödeme Altyapısı ve Kurumsal Entegrasyon Nöbet Takımı'
const at = (min) => new Date(Date.now() - min * 60_000).toISOString().slice(0, 19)
const NOC_ROW = { id: 500, alert_type: 'HTTP_DOWN', family: 'http', level: 'CRITICAL', domain: 'checkout.example.com', created_at: at(30),
  resolved: false, team_id: 3, team_name: NOC_TEAM, ug_team_id: 1, ug_team_name: 'Takım A',
  monitor: { type: 'HTTP', tab: 'http', id: 70, name: 'Ödeme ağ geçidi' },
  channels: { email_sent: 1, email_failed: 0, webhook_sent: 0, push_sent: 1, push_failed: 0, noc: true },
  noc: { sent_at: at(25), via_storm: false, groups: 'NOC Ana' }, call_count: 1, can_call: true,
  last_call: { contacted_name: NOC_LONG_PERSON, outcome: 'NO_ANSWER', contacted_at: at(5), channel: 'PHONE', created_by_name: 'Operatör A' } }

for (const vp of [{ name: 'phone', width: 390, height: 844 }, { name: 'tablet', width: 768, height: 1024 }]) {
  test(`7/24 arama penceresi — uzun AD adı kırpılmaz — ${vp.name}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    const json = (r, body) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
    await page.route((u) => new URL(u).pathname === '/api/noc/console', (r) => json(r, { success: true, data: {
      window: '24h', generated_at: at(0), truncated: false, max_rows: 1000, can_write: true, total: 1, page: 0, size: 25,
      kpis: { open: 1, open_critical: 1, noc_sent: 1, not_called: 0, called_last_hour: 1 },
      facets: { teams: [{ id: 3, name: NOC_TEAM, count: 1 }], types: { http: 1 }, levels: { CRITICAL: 1 } }, items: [NOC_ROW] } }))
    await page.route((u) => /^\/api\/noc\/console\/alerts\/\d+\/call-sheet$/.test(new URL(u).pathname), (r) => json(r, { success: true, data: {
      alert_id: 500, team_id: 3, team_name: NOC_TEAM, call_list_defined: true,
      call_list: [{ name: NOC_LONG_PERSON, title: NOC_LONG_TITLE, phone: '+90 (500) 000 00 00', position: 1 }],
      manager: { name: 'Müdür A', title: 'Müdür', phone: '0500 000 00 01' }, escalation: [], call_instructions: '' } }))
    await page.goto('/?tab=noc&n_view=console')
    await page.locator('[data-slot="noc-con-row"]').first().waitFor({ timeout: 20_000 })
    await page.locator('[data-action="noc-con-call"]').first().click()
    const name = page.locator('[role="dialog"] [data-slot="noc-sheet-person-name"]').first()
    await name.waitFor({ timeout: 20_000 })
    const m = await name.evaluate((el) => {
      const cs = getComputedStyle(el)
      const vw = document.documentElement.clientWidth
      const r = el.getBoundingClientRect()
      const title = el.nextElementSibling
      return { text: el.textContent, sw: el.scrollWidth, cw: el.clientWidth, ellipsis: cs.textOverflow === 'ellipsis' && cs.overflow !== 'visible',
        right: r.right, vw, titleText: title?.textContent || '', titleEllipsis: title ? getComputedStyle(title).textOverflow === 'ellipsis' : false,
        titleSw: title?.scrollWidth ?? 0, titleCw: title?.clientWidth ?? 0 }
    })
    expect(m.text).toBe(NOC_LONG_PERSON)
    expect(m.sw, 'ad kendi kutusundan taşıyor').toBeLessThanOrEqual(m.cw + 1)
    expect(m.ellipsis, 'ad üç noktayla kırpılıyor').toBe(false)
    expect(m.right, 'ad ekran dışına çıkıyor').toBeLessThanOrEqual(m.vw + 1)
    expect(m.titleText).toBe(NOC_LONG_TITLE)
    expect(m.titleEllipsis, 'unvan üç noktayla kırpılıyor').toBe(false)
    expect(m.titleSw).toBeLessThanOrEqual(m.titleCw + 1)
  })
}
