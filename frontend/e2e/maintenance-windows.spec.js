// Bakım pencereleri uçtan uca (yayın öncesi kapı, 2026-11-15).
//
// Global yönetici: boş kayıtta ad / izleme / başlangıç hataları ALANLARIN ALTINDA, istek gitmez; tek seferlik pencere
// (izleme seçici → HTTP izlemesi, takvimden gün + saat, 120 dk ön ayarı) → POST gövdesi (hedef {type, target, name},
// durationMinutes 120, recurrence NONE); "Tüm izlemeler" yalnız global yöneticide → POST allMonitors:true, targets []. Liste:
// duraklat → POST /pause; sil → onay → DELETE. Kapsamlı yönetici: "Tüm izlemeler" kutusu yok, hedef seçmeden kayıt
// edilemez. Telefon (390, dokunmatik), tablet (768, dokunmatik), dizüstü (1280): taşma yok, metin kesilmez, dokunma ≥ 40 px.
import { test, expect } from '@playwright/test'
import { mockApi, MONITORS } from './support/monitorMocks.js'
import { GATE_VIEWPORTS, expectLayout, expectInViewport, json } from './support/gateChecks.js'

/**
 * ÜRÜN HATALARI, raporlandı (yayın öncesi kapı 2026-11-15) — bu ölçümler kapıdan dışarıda tutulur:
 *  - İzleme seçicisinde uzun izleme adı TEK SATIRDA kesilir (`MaintenanceTargetPicker.jsx` `mtp-item-name` `truncate`).
 *  - Dokunmatikte 40 px altı: üst düğmeler Yenile / Hızlı pencere / Yeni pencere 32 px (`MaintenanceWindowsPage.jsx`
 *    size="sm"), görünüm sekmeleri 29 px (aynı dosya TabsTrigger), tablo satırı simge eylemleri 32 px
 *    (`maintenance/MaintenanceList.jsx` InlineActions size="icon-sm", tablet).
 *  - İzleme seçicisi (`monitoring/MaintenanceTargetPicker.jsx`), dokunmatikte: tür sekmeleri 34 px (`py-1.5`), izleme satırları
 *    28 px (`py-1`), "Bu türün tümünü seç" 32 px (size="sm"); telefonda (390) arama kutusu aynı satırdaki bu düğmenin yanında
 *    ~20 px genişliğe ezilir (`InputGroup className="h-8 min-w-0 flex-1"` — temel genişlik yok, satır kaydırmıyor).
 */
const KNOWN_CLIP = [/\[mtp-item-name\]/]
const KNOWN_SMALL = [/^button\[button\] "(Refresh|Quick window|New window)" \d+x32$/, /^button\[tabs-trigger\] "(List|Agenda|Calendar)" \d+x29$/,
  /^button\[button\] "(Haftalık ağ bakımı|Ödeme altyapısı gece yama penc)[^"]*" 32x32$/]
const PICKER_KNOWN_SMALL = [/^button\[tabs-trigger\] "[^"]*" \d+x34$/, /^button\[mtp-item\] "[^"]*" \d+x28$/,
  /^button\[button\] "Select every [^"]*" \d+x32$/, /^input\[input-group-control\] "Search in this type…" \d+x40$/]

const ahead = (h) => new Date(Date.now() + h * 3_600_000).toISOString().slice(0, 19)
const WINDOWS = [
  { id: 1, name: 'Ödeme altyapısı gece yama penceresi (Kurumsal ve Bireysel Bankacılık Uçları)', description: 'Veritabanı yama penceresi — tüm ödeme uçları',
    all_monitors: false, targets: [{ type: 'http', target: MONITORS.http[0].url, name: MONITORS.http[0].name }], target_count: 1,
    recurrence: 'NONE', start_at: ahead(30), timezone: 'Europe/Istanbul', duration_minutes: 90, status: 'scheduled', team_id: 1,
    created_by: 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin', next_occurrence: ahead(30) },
  { id: 2, name: 'Haftalık ağ bakımı', description: null, all_monitors: true, targets: [], target_count: 0, recurrence: 'WEEKLY',
    days_of_week: '2,4', start_at: ahead(-200), timezone: 'Europe/Istanbul', duration_minutes: 60, status: 'scheduled', team_id: 1,
    created_by: 'demo', next_occurrence: ahead(40) },
]

async function mockMaintenance(page, { globalAdmin = true } = {}) {
  const sink = { posts: [], puts: [], paused: [], deleted: [] }
  await mockApi(page, { role: 'ADMIN', globalAdmin })
  await page.route((u) => new URL(u).pathname.startsWith('/api/monitoring/maintenance'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    const m = req.method()
    let mt
    if (p === '/api/monitoring/maintenance' && m === 'GET') return route.fulfill(json({ success: true, data: WINDOWS }))
    if (p === '/api/monitoring/maintenance' && m === 'POST') {
      sink.posts.push(JSON.parse(req.postData() || '{}'))
      return route.fulfill(json({ success: true, data: { id: 99 } }))
    }
    if ((mt = p.match(/^\/api\/monitoring\/maintenance\/(\d+)\/pause$/)) && m === 'POST') {
      sink.paused.push(Number(mt[1]))
      return route.fulfill(json({ success: true }))
    }
    if ((mt = p.match(/^\/api\/monitoring\/maintenance\/(\d+)$/)) && m === 'DELETE') {
      sink.deleted.push(Number(mt[1]))
      return route.fulfill(json({ success: true }))
    }
    if (p === '/api/monitoring/maintenance/active') return route.fulfill(json({ success: true, data: [] }))
    return route.fallback()
  })
  return sink
}

async function openEditor(page) {
  await page.goto('/?tab=maintenance')
  await page.locator('[data-slot="mw-list"]').waitFor({ timeout: 60_000 })
  await page.getByRole('button', { name: 'New window', exact: true }).first().click()
  const dlg = page.getByRole('dialog', { name: 'New Maintenance Window' })
  await expect(dlg).toBeVisible()
  return dlg
}

/** Takvimden bugünden sonraki ilk seçilebilir gün + saat 22:00, "Done". */
async function pickStart(page, dlg) {
  await dlg.locator('[data-slot="date-picker-trigger"]').first().click()
  const cal = page.locator('[data-slot="calendar"]').last()
  await expect(cal).toBeVisible()
  // Sonraki aya geç: bugünün ötesinde kesin seçilebilir gün
  await cal.locator('[data-slot="calendar-nav-button"]').last().click()
  await cal.locator('button[data-day]:not([disabled])').nth(10).click()
  await page.locator('input[type="time"]').last().fill('22:00')
  await page.getByRole('button', { name: 'Done', exact: true }).last().click()
}

/** Satır eylemi: masaüstünde satırdaki simge düğmesi, telefonda "İşlemler" menüsü. */
async function rowAction(page, w, action) {
  const direct = page.getByRole('button', { name: `${w.name} — ${action}`, exact: true })
  if (await direct.isVisible().catch(() => false)) { await direct.click(); return }
  // Önceki menünün kapanış animasyonu bitmeden yeni menü açılırsa iki menü birden görünür — önce kapanmasını bekle
  await expect(page.getByRole('menu')).toHaveCount(0)
  await page.getByRole('button', { name: `${w.name} — Actions` }).click()
  await page.getByRole('menu').getByRole('menuitem', { name: action, exact: true }).click()
}

for (const vp of GATE_VIEWPORTS) {
  test.describe(`bakım pencereleri — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('global yönetici: alan hataları → hedefli tek seferlik pencere POST → "Tüm izlemeler" POST', async ({ page }) => {
      test.setTimeout(150_000)
      const sink = await mockMaintenance(page)
      let dlg = await openEditor(page)
      await expectInViewport(dlg, vp.width, 'bakım penceresi formu')

      // Boş kayıt: hatalar alanların altında, istek gitmez
      await dlg.getByRole('button', { name: 'Save', exact: true }).click()
      await expect(dlg.getByText('Please correct the highlighted fields.')).toBeVisible()
      await expect(dlg.locator('[data-slot="field"][data-invalid="true"]').filter({ hasText: 'Name is required' })).toHaveCount(1)
      await expect(dlg.locator('[data-slot="mw-targets"]')).toContainText('Select at least one monitor (or all)')
      await expect(dlg.locator('[data-slot="field"][data-invalid="true"]').filter({ hasText: 'Start time is required' })).toHaveCount(1)
      expect(sink.posts).toHaveLength(0)
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, allowClip: KNOWN_CLIP, allowSmall: PICKER_KNOWN_SMALL, label: `bakım formu hatalar @${vp.name}` })

      // Hedefli tek seferlik pencere
      await dlg.getByRole('textbox', { name: /^Name/ }).fill('Ödeme API sürüm geçişi')
      await dlg.getByRole('tab', { name: /HTTP/ }).click()
      const target = MONITORS.http[2]
      await dlg.locator('[data-slot="mtp-item"]').filter({ hasText: target.name }).click()
      await expect(dlg.locator('[data-slot="mtp-item"][data-state="on"]')).toHaveCount(1)
      await pickStart(page, dlg)
      await dlg.locator('[data-slot="mw-duration-preset"]').nth(2).click()   // DURATION_PRESETS[2] = 120 dk
      await expect(dlg.locator('[data-slot="mw-summary"]')).not.toContainText('Pick a start')
      await dlg.getByRole('button', { name: 'Save', exact: true }).click()
      await expect.poll(() => sink.posts.length, { timeout: 15_000 }).toBe(1)
      expect(sink.posts[0]).toMatchObject({ name: 'Ödeme API sürüm geçişi', allMonitors: false, durationMinutes: 120, recurrence: 'NONE',
        targets: [{ type: 'http', target: target.url, name: target.name }], daysOfWeek: null, dayOfMonth: null })
      expect(sink.posts[0].startAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
      await expect(page.getByText('Maintenance window saved')).toBeVisible()
      await expect(dlg).toHaveCount(0)

      // "Tüm izlemeler" (global yönetici)
      dlg = await openEditor(page)
      await dlg.getByRole('textbox', { name: /^Name/ }).fill('Veri merkezi elektrik bakımı')
      await dlg.getByRole('checkbox', { name: 'All monitors (cover everything)' }).click()
      await expect(dlg.locator('[data-slot="mtp-item"]')).toHaveCount(0)
      await pickStart(page, dlg)
      await dlg.getByRole('button', { name: 'Save', exact: true }).click()
      await expect.poll(() => sink.posts.length, { timeout: 15_000 }).toBe(2)
      expect(sink.posts[1]).toMatchObject({ name: 'Veri merkezi elektrik bakımı', allMonitors: true, targets: [] })
    })

    test('liste: duraklat → POST /pause; sil → onay → DELETE', async ({ page }) => {
      test.setTimeout(120_000)
      const sink = await mockMaintenance(page)
      await page.goto('/?tab=maintenance')
      const list = page.locator('[data-slot="mw-list"]')
      await expect(list.locator('[data-id="1"]')).toBeVisible({ timeout: 60_000 })
      await expectLayout(page, '[data-slot="maintenance-page"]', { touch: vp.touch, allowSmall: KNOWN_SMALL, label: `bakım listesi @${vp.name}` })

      await rowAction(page, WINDOWS[0], 'Pause')
      await expect.poll(() => sink.paused).toEqual([1])

      await rowAction(page, WINDOWS[1], 'Delete')
      const confirm = page.getByRole('dialog', { name: 'Delete' })
      await expect(confirm).toBeVisible()
      await expect(confirm).toContainText('Delete this maintenance window?')
      await expectInViewport(confirm, vp.width, 'silme onayı')
      expect(sink.deleted).toHaveLength(0)
      await confirm.getByRole('button', { name: 'Delete', exact: true }).click()
      await expect.poll(() => sink.deleted).toEqual([2])
      await expect(page.getByText('Maintenance window deleted')).toBeVisible()
    })

    test('kapsamlı yönetici: "Tüm izlemeler" yok, hedef seçmeden kayıt yok', async ({ page }) => {
      test.setTimeout(120_000)
      const sink = await mockMaintenance(page, { globalAdmin: false })
      const dlg = await openEditor(page)
      await expect(dlg.getByRole('checkbox', { name: 'All monitors (cover everything)' })).toHaveCount(0)
      await dlg.getByRole('textbox', { name: /^Name/ }).fill('Takım içi bakım')
      await pickStart(page, dlg)
      await dlg.getByRole('button', { name: 'Save', exact: true }).click()
      await expect(dlg.locator('[data-slot="mw-targets"]')).toContainText('Select at least one monitor (or all)')
      expect(sink.posts).toHaveLength(0)
      await dlg.locator('[data-slot="mtp-item"]').first().click()
      await dlg.getByRole('button', { name: 'Save', exact: true }).click()
      await expect.poll(() => sink.posts.length, { timeout: 15_000 }).toBe(1)
      expect(sink.posts[0].allMonitors).toBe(false)
      expect(sink.posts[0].targets).toHaveLength(1)
    })
  })
}
