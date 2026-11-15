// Yetki Yönetimi (`?tab=permissions`) — prod kapısı e2e (2026-11-15). Telefon (390, dokunmatik), tablet (768,
// dokunmatik) ve dizüstünde (1280) GERÇEK uygulama rotasında:
//  - hücre çevirmek "bekleyen" kümeye yazar; sunucudaki değere dönen hücre kümeden çıkar → Kaydet YALNIZ değişen hücreleri
//    hücre başına PUT eder (`{ role, resource_key, action, allowed }`), geri alınan hücre gönderilmez;
//  - hassas yetki VERİLİRKEN onay sorulur: Vazgeç → bekleyen değişiklik oluşmaz, "Evet, ver" → oluşur;
//  - kaydedilmemiş değişiklikle sayfadan çıkış tarayıcı uyarısı (beforeunload) alır; Vazgeç onay ister;
//  - sunucu `can_edit: false` dediğinde salt okunur: anahtarlar kilitli, Kaydet / Vazgeç / Sıfırla yok, hiç PUT gitmez;
//  - kısmi hata: başarısız hücre bekleyende kalır, gözden geçirme paneli sunucu metniyle açılır;
//  - yerleşim: sayfa taşmaz, ad kırpılmaz, dokunmatikte hedefler ≥ 40 px (ortak layoutAudit).
import { test, expect } from '@playwright/test'
import { mockApi, PERMISSION_MATRIX } from './support/monitorMocks.js'
import { VIEWPORTS, json, pathIs, checkLayout, expectInViewport, recordRequests } from './support/layoutAudit.js'

const ROOT = '[data-testid="permission-matrix"]'

/** mockApi'den SONRA kaydedilen yollar önceliklidir. PUT'lar kaydedilir; `failKey` verilen hücre sunucuda reddedilir. */
async function routes(page, { role = 'ADMIN', globalAdmin = true, canEdit = true, failKey = null } = {}) {
  await mockApi(page, { role, globalAdmin })
  const puts = []
  let resets = 0
  await page.route(pathIs('/api/admin/permissions'), (route) => {
    const req = route.request()
    if (req.method() === 'GET') return json({ ...PERMISSION_MATRIX, can_edit: canEdit })(route)
    const body = JSON.parse(req.postData() || '{}')
    puts.push(body)
    if (failKey && `${body.role}|${body.resource_key}|${body.action}` === failKey) {
      return json({ success: false, code: 'VALIDATION_FAILED', error: 'ADMIN rolünün yetkisi kısılamaz — bu hücre sunucuda reddedildi.' }, 400)(route)
    }
    return json({ success: true, data: { ...body, updated_by: 'demo', updated_at: '2026-11-15T10:00:00' } })(route)
  })
  await page.route(pathIs('/api/admin/permissions/reset-to-defaults'), (route) => { resets++; return json({ success: true })(route) })
  return { puts, resets: () => resets }
}

/** Hücre anahtarı (rol — kaynak — tür). Telefonda yalnız seçili rolün anahtarları çizilir: önce rol kartı seçilir. */
async function cell(page, vp, role, resource, actionLabel) {
  if (vp.width < 768) {
    const card = page.locator(`[data-slot="perm-role-card"][data-role="${role}"]`)
    await card.click()
    await expect(card).toHaveAttribute('aria-pressed', 'true')
  }
  return page.getByRole('switch', { name: `${role} — ${resource} — ${actionLabel}`, exact: true })
}

async function openGroup(page, group) {
  const trigger = page.locator(`[data-group="${group}"] button[aria-expanded]`).first()
  if ((await trigger.getAttribute('aria-expanded')) !== 'true') await trigger.click()
  await expect(trigger).toHaveAttribute('aria-expanded', 'true')
}

/** Uygulamanın beforeunload dinleyicisi sayfadan çıkışı engelliyor mu (sentetik, iptal edilebilir olay). */
const unloadBlocked = (page) => page.evaluate(() => {
  const e = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(e)
  return e.defaultPrevented
})

const confirmDialog = (page) => page.getByRole('dialog').filter({ has: page.getByRole('button', { name: /Yes, grant|Evet, ver|Yes, discard|Evet, vazgeç/ }) })

for (const vp of VIEWPORTS) {
  test.describe(`Permission matrix — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('only changed cells are PUT, sensitive grant asks, unsaved-changes guard, layout', async ({ page }) => {
      test.setTimeout(120_000)
      const calls = await routes(page)
      await page.goto('/?tab=permissions')
      const root = page.locator(ROOT)
      await expect(root.locator('[data-slot="perm-role-card"]')).toHaveCount(4, { timeout: 20_000 })
      expect(await page.evaluate(() => matchMedia('(pointer: coarse)').matches)).toBe(vp.touch)
      if (vp.width < 768) await expect(root.locator('[data-slot="perm-mobile-list"]')).toBeVisible()
      else await expect(root.locator('[data-testid="perm-matrix"]')).toBeVisible()
      await openGroup(page, 'certificates')

      // 1) Var olan izni kaldır (onay sorulmaz)
      const crud = await cell(page, vp, 'TEAM_ADMIN', 'inventory.crud', 'Edit')
      await expect(crud).toBeChecked()
      await crud.click()
      await expect(crud).not.toBeChecked()

      // 2) Hassas yetki VERMEK onay ister — Vazgeç: bekleyen değişiklik oluşmaz
      const transfer = await cell(page, vp, 'USER', 'inventory.transfer', 'Execute')
      await expect(transfer).not.toBeChecked()
      await transfer.click()
      const dlg = confirmDialog(page)
      await expect(dlg).toBeVisible()
      await expect(dlg).toContainText(/sensitive|hassas/i)
      await expectInViewport(dlg, vp, `${vp.name}: sensitive confirm`)
      await dlg.getByRole('button', { name: /^(Cancel|Vazgeç)$/ }).click()
      await expect(dlg).toHaveCount(0)
      await expect(transfer).not.toBeChecked()
      // … "Evet, ver" → bekleyen
      await transfer.click()
      await confirmDialog(page).getByRole('button', { name: /Yes, grant|Evet, ver/ }).click()
      await expect(transfer).toBeChecked()

      // 3) Aç + geri kapat: sunucudaki değere dönen hücre kümeden düşer, gönderilmez
      const listView = await cell(page, vp, 'AUDIT', 'inventory.list', 'View')
      await expect(listView).not.toBeChecked()
      await listView.click()
      await expect(listView).toBeChecked()
      await listView.click()
      await expect(listView).not.toBeChecked()

      const bar = page.locator('[data-slot="perm-changes-bar"]')
      await expect(bar).toContainText(/2 unsaved changes|2 kaydedilmemiş/)
      await expectInViewport(bar, vp, `${vp.name}: changes bar`)

      // Kaydedilmemiş değişiklik → çıkış uyarısı (gerçek tarayıcı iletişim kutusu + sentetik olay)
      expect(await unloadBlocked(page), 'beforeunload must warn while dirty').toBe(true)
      const dialogP = page.waitForEvent('dialog')
      await page.close({ runBeforeUnload: true })
      const unload = await dialogP
      expect(unload.type()).toBe('beforeunload')
      await unload.dismiss()
      await expect(bar).toBeVisible()

      // Gözden geçirme paneli: tam iki değişiklik (geri alınan yok)
      await bar.getByRole('button', { name: /^(Review|Gözden geçir)$/ }).click()
      const review = page.locator('[data-slot="perm-review"]')
      await expect(review).toBeVisible()
      await expect(review.locator('[data-slot="perm-change"]')).toHaveCount(2)
      await expect(review.locator('[data-slot="perm-change"][data-key="TEAM_ADMIN|inventory.crud|edit"]')).toBeVisible()
      await expect(review.locator('[data-slot="perm-change"][data-key="USER|inventory.transfer|execute"]')).toBeVisible()
      await checkLayout(page, `review sheet@${vp.name}`, '[data-slot="perm-review"]')
      await page.keyboard.press('Escape')
      await expect(review).toHaveCount(0)

      await checkLayout(page, `matrix dirty@${vp.name}`, ROOT)
      await page.screenshot({ path: `test-results/permission-matrix-${vp.name}.png`, fullPage: false })

      // 4) Kaydet → hücre başına PUT, yalnız değişen iki hücre
      await bar.getByRole('button', { name: /Save changes|Değişiklikleri kaydet/ }).click()
      await expect.poll(() => calls.puts.length).toBe(2)
      expect(calls.puts).toEqual([
        { role: 'TEAM_ADMIN', resource_key: 'inventory.crud', action: 'edit', allowed: false },
        { role: 'USER', resource_key: 'inventory.transfer', action: 'execute', allowed: true },
      ])
      await expect(page.locator('[data-sonner-toast]').filter({ hasText: /2 permission changes saved|2 yetki değişikliği kaydedildi/ })).toBeVisible()
      await expect(bar).toHaveCount(0)
      expect(await unloadBlocked(page), 'no warning after save').toBe(false)
      // Kaydedilen değerler ekranda kalır
      await expect(await cell(page, vp, 'TEAM_ADMIN', 'inventory.crud', 'Edit')).not.toBeChecked()
      await expect(await cell(page, vp, 'USER', 'inventory.transfer', 'Execute')).toBeChecked()

      // 5) Vazgeç onay ister; "Evet" bekleyeni siler, hiçbir PUT gitmez
      const again = await cell(page, vp, 'TEAM_ADMIN', 'inventory.list', 'View')
      const was = await again.isChecked()
      await again.click()
      await expect(bar).toBeVisible()
      await bar.getByRole('button', { name: /^(Discard|Vazgeç)$/ }).click()
      const disc = confirmDialog(page)
      await expect(disc).toContainText(/1 unsaved|1 kaydedilmemiş/)
      await disc.getByRole('button', { name: /Yes, discard|Evet, vazgeç/ }).click()
      await expect(bar).toHaveCount(0)
      if (was) await expect(again).toBeChecked(); else await expect(again).not.toBeChecked()
      expect(calls.puts).toHaveLength(2)
      // Tam sayfa görüntüsü EN SONDA — dokunmatik öykünmesini sıfırlar
      await page.screenshot({ path: `test-results/permission-matrix-${vp.name}-full.png`, fullPage: true })
    })

    test('read-only for a non-editor: switches locked, no write actions, no PUT', async ({ page }) => {
      test.setTimeout(90_000)
      const log = recordRequests(page)
      const calls = await routes(page, { role: 'USER', globalAdmin: false, canEdit: false })
      await page.goto('/?tab=permissions')
      const root = page.locator(ROOT)
      await expect(root.locator('[data-slot="perm-role-card"]')).toHaveCount(4, { timeout: 20_000 })
      await expect(root).toContainText(/Read-only view|Salt okunur görünüm/)
      await expect(root.getByRole('button', { name: /Save changes|Değişiklikleri kaydet/ })).toHaveCount(0)
      await expect(root.getByRole('button', { name: /Reset to Defaults|Varsayılanlara/ })).toHaveCount(0)
      await openGroup(page, 'certificates')
      const sw = await cell(page, vp, 'TEAM_ADMIN', 'inventory.crud', 'Edit')
      await expect(sw).toBeDisabled()
      await expect(sw).toBeChecked()   // durum soluk değil, okunur
      await sw.click({ force: true })
      await expect(sw).toBeChecked()
      await expect(page.locator('[data-slot="perm-changes-bar"]')).toHaveCount(0)
      await checkLayout(page, `matrix read-only@${vp.name}`, ROOT)
      expect(calls.puts).toEqual([])
      expect(log.filter((r) => r.path.startsWith('/api/admin/permissions') && r.method !== 'GET')).toEqual([])
    })
  })
}

test.describe('Permission matrix — partial save failure (1280)', () => {
  test.use({ viewport: { width: 1280, height: 900 } })
  test('failed cell stays pending, review sheet opens with the server text', async ({ page }) => {
    test.setTimeout(90_000)
    const vp = VIEWPORTS[2]
    const calls = await routes(page, { failKey: 'USER|inventory.list|view' })
    await page.goto('/?tab=permissions')
    await expect(page.locator('[data-slot="perm-role-card"]')).toHaveCount(4, { timeout: 20_000 })
    await openGroup(page, 'certificates')
    await (await cell(page, vp, 'TEAM_ADMIN', 'inventory.crud', 'Edit')).click()
    await (await cell(page, vp, 'USER', 'inventory.list', 'View')).click()
    await page.locator('[data-slot="perm-changes-bar"]').getByRole('button', { name: /Save changes|Değişiklikleri kaydet/ }).click()
    await expect.poll(() => calls.puts.length).toBe(2)
    await expect(page.locator('[data-sonner-toast]').filter({ hasText: /1 of 2 changes could not be saved|2 değişiklikten 1/ })).toBeVisible()
    const review = page.locator('[data-slot="perm-review"]')
    await expect(review).toBeVisible()
    const failed = review.locator('[data-slot="perm-change"][data-failed="true"]')
    await expect(failed).toHaveCount(1)
    await expect(failed).toHaveAttribute('data-key', 'USER|inventory.list|view')
    await expect(failed).toContainText('sunucuda reddedildi')
    await expect(review.locator('[data-slot="perm-change"]')).toHaveCount(1)
    expect(await unloadBlocked(page), 'failed cell still dirty').toBe(true)
  })
})
