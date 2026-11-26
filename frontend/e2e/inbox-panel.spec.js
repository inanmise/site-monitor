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
// İlk açık alarm GERÇEK uzunlukta ad + özet taşır (H paketi 2026-11-26: başlık / özet / zaman kesilmez, sarar)
const LONG_TITLE = 'cok-uzun-bir-alt-alan-adi.kurumsal-dijital-kanallar-ve-musteri-deneyimi.example.com.tr'
const items = Array.from({ length: 30 }, (_, i) => ({
  key: 'k' + i, kind: i % 2 ? 'alert_open' : 'alert_resolved', level: i % 2 ? 'CRITICAL' : 'OK',
  title: i === 1 ? LONG_TITLE : `site-${i}.example.com`,
  sub: i === 1 ? 'HTTP 503 Service Unavailable — upstream connect error or disconnect/reset before headers' : 'HTTP 503',
  at: iso((i + 1) * 5 * HOUR), started_at: iso((i + 1) * 5 * HOUR + HOUR),
  ended_at: i % 2 ? null : iso((i + 1) * 5 * HOUR), tab: 'alerthistory', params: { alert: i }, team_id: 1,
  team_name: i === 1 ? 'Dijital Kanallar Teknik Servis Yönetimi (Bölüm / Müdürlük)' : 'Takım A',
  monitor_tab: i === 1 ? 'http' : undefined, monitor_id: i === 1 ? 7 : undefined,
  monitor_params: i === 1 ? { monitor: 7 } : undefined,
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

/** Satır metni ölçümü: başlık / özet / zaman — üç nokta yok, kırpılmaz, satırdan taşmaz. */
function rowText() {
  const row = [...document.querySelectorAll('[data-inbox-row]')].find((r) => r.textContent.includes('cok-uzun-bir-alt-alan-adi'))
  if (!row) return { missing: true }
  const out = { clipped: [], ellipsis: [], titleLines: 0 }
  const box = row.getBoundingClientRect()
  for (const slot of ['inbox-row-title', 'inbox-row-summary', 'inbox-row-time']) {
    const el = row.querySelector(`[data-slot="${slot}"]`)
    if (!el) { out.clipped.push(slot + ' yok'); continue }
    const cs = getComputedStyle(el)
    if (cs.textOverflow === 'ellipsis' || cs.whiteSpace === 'nowrap') out.ellipsis.push(slot)
    if (el.scrollWidth > el.clientWidth + 1) out.clipped.push(`${slot} ${el.scrollWidth}>${el.clientWidth}`)
    const r = el.getBoundingClientRect()
    if (r.right > box.right + 1) out.clipped.push(`${slot} satırdan taşıyor`)
  }
  const title = row.querySelector('[data-slot="inbox-row-title"]')
  out.titleLines = Math.round(title.getBoundingClientRect().height / parseFloat(getComputedStyle(title).lineHeight))
  return out
}

// Dokunmatik tablet 768 (H paketi 2026-11-26, deneyim 8): telefon değil (Popover) ama dokunmatik — satır eylemleri
// hover'da GİZLİ kalmamalı; her zaman görünen tek menü düğmesi ≥ 40 px. Ad / özet / zaman sarar (deneyim 9).
for (const vp of [
  { name: 'tablet-768-dokunmatik', width: 768, height: 1024, touch: true },
  { name: 'telefon-390-dokunmatik', width: 390, height: 844, touch: true },
  { name: 'laptop-1280', width: 1280, height: 800, touch: false },
]) {
  test.describe(`bildirim satırı — ${vp.name}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })
    test('eylemler dokunmatikte görünür ve 40 px; uzun ad / özet / zaman sarar, taşma yok', async ({ page }) => {
      await mock(page)
      await page.goto('/')
      await page.getByRole('button', { name: /^(Bildirimler|Notifications)$/ }).first().click()
      const panelSel = vp.width < 768 ? '[data-slot="sheet-content"]' : '[data-slot="popover-content"]'
      await expect(page.locator(panelSel)).toBeVisible()
      await settled(page, panelSel)
      const row = page.locator('[data-inbox-row]').filter({ hasText: 'cok-uzun-bir-alt-alan-adi' }).first()
      await expect(row).toBeVisible()
      const actions = row.locator('[data-slot="inbox-row-actions"]')
      if (vp.touch) {
        const btn = actions.locator('button')
        await expect(btn).toHaveCount(1)
        await expect(btn).toBeVisible()
        const b = await btn.boundingBox()
        expect(b.height, 'menü düğmesi yüksekliği').toBeGreaterThanOrEqual(39.5)
        expect(b.width, 'menü düğmesi genişliği').toBeGreaterThanOrEqual(39.5)
        // Hover gerekmeden menü açılır ve "İzlemeye git" seçilebilir
        await btn.click()
        await expect(page.getByRole('menuitem').first()).toBeVisible()
        await page.keyboard.press('Escape')
      } else {
        // Fare: küme hover'da belirir (üç simge düğmesi)
        await row.hover()
        await expect(actions.locator('button')).toHaveCount(3)
      }
      const m = await page.evaluate(rowText)
      expect(m.missing).toBeFalsy()
      expect(m.ellipsis, 'üç nokta / tek satır zorlaması').toEqual([])
      expect(m.clipped, 'kırpılan metin').toEqual([])
      expect(m.titleLines, 'uzun başlık en az iki satıra sarar').toBeGreaterThanOrEqual(2)
      const geo = await page.evaluate(geometry, panelSel)
      expect(geo.offenders, 'panel içinde görünüm alanı dışına taşan öğe').toEqual([])
    })
  })
}

// Dokunmatik satır menüsü GERÇEKTEN çalışır (H paketi 2026-11-26 inceleme boşluğu): 768 dokunmatikte Popover içindeki,
// 390'da Sheet içindeki KebabMenu — "okundu" satırın okunmamış işaretini kaldırır ve panel AÇIK kalır; "kaldır" satırı
// listeden çıkarır (sayaç "temizlenenleri göster"), panel açık ve kullanılabilir; "izlemeye git" izleme sayfasına
// `monitor=` ile gider, panel kapanır; zil yeniden açılır ve liste çalışır.
for (const vp of [
  { name: 'tablet-768-dokunmatik', width: 768, height: 1024 },
  { name: 'telefon-390-dokunmatik', width: 390, height: 844 },
]) {
  test.describe(`bildirim satırı menüsü — ${vp.name}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: true, isMobile: true })
    test('okundu / kaldır / izlemeye git menüden çalışır; panel çalışmaya devam eder', async ({ page }) => {
      test.setTimeout(90_000)
      await mock(page)
      await page.goto('/')
      const bell = page.getByRole('button', { name: /^(Bildirimler|Notifications)$/ }).first()
      await bell.click()
      const panelSel = vp.width < 768 ? '[data-slot="sheet-content"]' : '[data-slot="popover-content"]'
      const panel = page.locator(panelSel)
      await expect(panel).toBeVisible()
      await settled(page, panelSel)
      const rowOf = (text) => page.locator('[data-inbox-row]').filter({ hasText: text }).first()
      const menuOf = (row) => row.locator('[data-slot="inbox-row-actions"] button')
      // Aynı testte menü yeniden açılmadan önce bir önceki menü içeriği tamamen kapanmış olmalı (kapanış animasyonu)
      const menuGone = () => expect(page.locator('[data-slot="dropdown-menu-content"]')).toHaveCount(0)

      // 1) Okundu: okunmamış işareti kalkar, panel açık kalır
      const longRow = rowOf('cok-uzun-bir-alt-alan-adi')
      await expect(longRow).toHaveAttribute('data-unread', 'true')
      await menuOf(longRow).click()
      const read = page.getByRole('menuitem', { name: `${LONG_TITLE} — mark as read` })
      await expect(read).toBeVisible()
      // Menü açılış animasyonu (zoom-in-95) bitene dek ölçülen yükseklik küçük çıkar: son boyut beklenir
      await expect.poll(async () => (await read.boundingBox())?.height ?? 0,
        { message: 'menü öğesi dokunma yüksekliği', timeout: 5_000 }).toBeGreaterThanOrEqual(39.5)
      await read.click()
      // Açılış sekmesi "Okunmamış": okunan satır buradan çıkar (sayaç düşer); "Tümü" sekmesinde işaretsiz görünür
      await expect(page.locator('[data-inbox-row]').filter({ hasText: 'cok-uzun-bir-alt-alan-adi' })).toHaveCount(0)
      await expect(panel).toBeVisible()
      await menuGone()
      await panel.getByRole('tab', { name: /^All\b/ }).click()
      await expect(longRow).toBeVisible()
      await expect(longRow).not.toHaveAttribute('data-unread', 'true')

      // 2) Kaldır: satır listeden çıkar, "temizlenenleri göster" belirir, panel açık ve kullanılabilir
      const third = rowOf('site-3.example.com')
      await expect(third).toBeVisible()
      await menuOf(third).click()
      await page.getByRole('menuitem', { name: 'site-3.example.com — dismiss' }).click()
      await expect(page.locator('[data-inbox-row]').filter({ hasText: 'site-3.example.com' })).toHaveCount(0)
      await expect(panel).toBeVisible()
      await menuGone()
      // "Temizlenenleri göster (1)" panel başlığındaki "Diğer işlemler" menüsünde belirir
      await panel.getByRole('button', { name: 'More actions' }).click()
      await expect(page.getByRole('menuitem', { name: /Show cleared \(1\)/ })).toBeVisible()
      await page.keyboard.press('Escape')
      await menuGone()
      await expect(panel).toBeVisible()
      await expect(rowOf('site-5.example.com')).toBeVisible()

      // 3) İzlemeye git: izleme sayfası monitor= ile, panel kapanır
      await menuOf(longRow).click()
      await page.getByRole('menuitem', { name: /go to the monitor/ }).click()
      await expect(page).toHaveURL(/tab=http/)
      await expect.poll(() => new URL(page.url()).searchParams.get('monitor'), { timeout: 10_000 }).toBe('7')
      await expect(panel).toHaveCount(0)

      // 4) Zil yeniden açılır, liste çalışır (kaldırılan satır hâlâ gizli)
      await bell.click()
      await expect(page.locator(panelSel)).toBeVisible()
      await expect(rowOf('site-5.example.com')).toBeVisible()
      await expect(page.locator('[data-inbox-row]').filter({ hasText: 'site-3.example.com' })).toHaveCount(0)
      await settled(page, panelSel)
      const geo = await page.evaluate(geometry, panelSel)
      expect(geo.offenders, 'panel içinde görünüm alanı dışına taşan öğe').toEqual([])
    })
  })
}
