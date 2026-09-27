// Bildirimler paneli yerleşimi — gerçek tarayıcıda (jsdom yerleşim yapmaz). API mock'lu, girişsiz.
//
// 2026-09-25 kullanıcı bildirimi: "Notifications ekranı tam olmamış, ekranın yarısı kullanılıyor". Kök neden:
// Radix Tabs pasif sekme içeriğini `hidden` öznitelikle DOM'da tutar; projede Tailwind preflight olmadığından
// `flex` sınıfı UA'nın `display:none`'ını eziyordu → görünmez pasif panel yüksekliğin YARISINI kaplıyordu.
// Düzeltme globals.css'teki `[hidden] { display:none !important }` kuralı; bu test onu tarayıcıda pinler.
//
// v3 (2026-09-26, shadcn yeniden tasarım): masaüstünde zile bağlı Popover (kendi kaydırması, en çok 70vh), telefonda
// tam yükseklikte Sheet. İki yüzeyde de aynı ölçüm: pasif sekme yer kaplamaz, etkin liste sekme alanının dibine kadar.
import { test, expect } from '@playwright/test'

const HOUR = 3_600_000
const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const items = Array.from({ length: 30 }, (_, i) => ({
  key: 'k' + i, kind: i % 2 ? 'alert_open' : 'alert_resolved', level: i % 2 ? 'CRITICAL' : 'OK',
  title: `site-${i}.example.com`, sub: 'HTTP 503', at: iso((i + 1) * 5 * HOUR), started_at: iso((i + 1) * 5 * HOUR + HOUR),
  ended_at: i % 2 ? null : iso((i + 1) * 5 * HOUR), tab: 'alerthistory', params: { alert: i }, team_id: 1, team_name: 'Takım A',
}))

async function mock(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ } })
  // YALNIZ /api/ yolları (kaynak modülü /src/api/client.js'e dokunma)
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const url = route.request().url()
    let body = { success: true, data: [] }
    if (/\/api\/me(\?|$)/.test(url)) body = { success: true, username: 'demo', system_role: 'USER', team_ids: [1], team_names: ['Takım A'] }
    else if (/\/api\/me\/inbox/.test(url)) body = { success: true, data: items, total: items.length, total_pages: 1 }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

/** Açılış animasyonu (slide/zoom transform) bitmeden ölçme: bounding rect'ler kayık çıkar. */
async function settled(page, sel) {
  await page.locator(sel).evaluate((el) => Promise.all(el.getAnimations({ subtree: true }).map((a) => a.finished.catch(() => {}))))
}

/** Etkin/pasif sekme geometrisi + panel içinde yatay taşma. */
function geometry(panelSel) {
  const box = (e) => e.getBoundingClientRect()
  const panel = document.querySelector(panelSel)
  const tabs = panel.querySelector('[data-slot="tabs"]')
  const active = tabs.querySelector('[data-slot="tabs-content"][data-state="active"]')
  const inactive = tabs.querySelector('[data-slot="tabs-content"][data-state="inactive"]')
  const vw = document.documentElement.clientWidth
  const offenders = []
  for (const el of panel.querySelectorAll('*')) {
    const r = box(el)
    const cs = getComputedStyle(el)
    if (r.width > 1 && r.right > vw + 1 && cs.display !== 'none' && cs.visibility !== 'hidden' && Number(cs.opacity) > 0) {
      offenders.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || ''}] right=${Math.round(r.right)} "${(el.textContent || '').trim().slice(0, 30)}"`)
    }
  }
  return {
    panel: box(panel), tabsBottom: box(tabs).bottom, activeBottom: box(active).bottom,
    inactiveDisplay: inactive ? getComputedStyle(inactive).display : 'none',
    scrolls: active.scrollHeight > active.clientHeight + 1, offenders: offenders.slice(0, 6),
  }
}

test('masaüstü: zile bağlı Popover — liste panelin tüm yüksekliğini kullanır, pasif sekme yer kaplamaz, kendi içinde kayar', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await mock(page)
  await page.goto('/')
  await page.getByRole('button', { name: /^(Bildirimler|Notifications)$/ }).first().click()
  const panel = page.locator('[data-slot="popover-content"]')
  await expect(panel).toBeVisible()
  await expect(page.locator('[data-inbox-row]').first()).toBeVisible()
  expect(await page.locator('[data-slot="sheet-content"]').count()).toBe(0)
  await settled(page, '[data-slot="popover-content"]')

  const geo = await page.evaluate(geometry, '[data-slot="popover-content"]')
  expect(geo.inactiveDisplay, 'pasif sekme içeriği gizli olmalı (hidden → display:none)').toBe('none')
  // Etkin liste sekme alanının dibine kadar uzanır (1 px yuvarlama payı) ve 30 satır panelin İÇİNDE kayar
  expect(geo.activeBottom).toBeGreaterThanOrEqual(geo.tabsBottom - 1)
  expect(geo.scrolls, '30 satır: liste panelin içinde kaymalı').toBe(true)
  expect(geo.panel.height).toBeLessThanOrEqual(900 * 0.7 + 1)
  expect(geo.panel.width).toBeGreaterThanOrEqual(400)
  expect(geo.panel.width).toBeLessThanOrEqual(440)
  expect(geo.offenders, 'panel içinde görünüm alanı dışına taşan öğe').toEqual([])
})

test('telefon (390×844): tam yükseklikte Sheet — başlık sabit, liste kayar, yatay taşma yok', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mock(page)
  await page.goto('/')
  await page.getByRole('button', { name: /^(Bildirimler|Notifications)$/ }).first().click()
  const sheet = page.locator('[data-slot="sheet-content"]')
  await expect(sheet).toBeVisible()
  await expect(page.locator('[data-inbox-row]').first()).toBeVisible()
  expect(await page.locator('[data-slot="popover-content"]').count()).toBe(0)
  await settled(page, '[data-slot="sheet-content"]')

  const geo = await page.evaluate(geometry, '[data-slot="sheet-content"]')
  expect(geo.inactiveDisplay).toBe('none')
  expect(geo.activeBottom).toBeGreaterThanOrEqual(geo.tabsBottom - 1)
  expect(geo.scrolls).toBe(true)
  expect(Math.round(geo.panel.width)).toBe(390)
  expect(Math.round(geo.panel.height)).toBe(844)
  expect(geo.offenders, 'panel içinde görünüm alanı dışına taşan öğe').toEqual([])
  // Dokunma hedefleri: satır menüsü ve sekmeler ≥ 40 px yüksek
  const tab = await page.getByRole('tab').first().boundingBox()
  expect(tab.height).toBeGreaterThanOrEqual(39)
})
