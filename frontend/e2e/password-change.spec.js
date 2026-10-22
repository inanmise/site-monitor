// Zorunlu şifre değişimi (UIA-H1, 2026-10-22): istemci 6–10 karakter diyor, alanlara maxLength=10 koyuyordu — yapıştırılan
// ya da parola yöneticisinin ürettiği uzun parola iki alanda da SESSİZCE 10 karaktere kesiliyor, sunucuya ilk 10 karakter
// yazılıyordu. Sunucu kuralı 6–64. Gerçek tarayıcıda: 30 karakterlik parola klavyeyle yazılınca alanlarda tam kalır, istek
// gövdesi tamamını taşır; pencere 390 / 768 / 1280 genişlikte ekrana sığar, düğme dokunulabilir.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 800 },
]
const PASSPHRASE = 'uzun-ve-guvenli-bir-parola-30c'

for (const vp of VIEWPORTS) {
  test(`zorunlu şifre değişimi: 30 karakter kesilmez @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    let mustChange = true
    const sent = []
    // mockApi'den SONRA kaydedilen rota önce eşleşir
    await page.route((u) => new URL(u).pathname === '/api/me', (route) => route.fulfill({ json: {
      success: true, username: 'demo', system_role: 'USER', global_admin: false, weekly_reports_visible: false,
      team_id: 1, team_name: 'Takım A', team_ids: [1], team_names: ['Takım A'], must_change_password: mustChange,
      tour: { status: 'dismissed', version: 99 },
    } }))
    await page.route((u) => new URL(u).pathname === '/api/me/change-password', async (route) => {
      sent.push(JSON.parse(route.request().postData() || '{}'))
      mustChange = false
      await route.fulfill({ json: { success: true, message: 'Password changed' } })
    })
    await page.goto('/?tab=dashboard')
    const dlg = page.getByRole('dialog')
    await expect(dlg).toBeVisible({ timeout: 20_000 })
    const inputs = dlg.locator('input[type="password"]')
    await expect(inputs).toHaveCount(3)
    await inputs.nth(0).fill('Gecici123')
    await inputs.nth(1).click()
    await page.keyboard.type(PASSPHRASE)
    await inputs.nth(2).click()
    await page.keyboard.type(PASSPHRASE)
    await expect(inputs.nth(1)).toHaveValue(PASSPHRASE)
    await expect(inputs.nth(2)).toHaveValue(PASSPHRASE)
    // Pencere ekrana sığar
    const box = await dlg.boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(-1)
    expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
    const save = dlg.locator('[data-slot="button"][data-variant="default"]')
    await expect(save).toBeEnabled()
    const sb = await save.boundingBox()
    expect(sb.y + sb.height, 'Kaydet ekranda').toBeLessThanOrEqual(vp.height + 1)
    if (vp.name === 'phone') expect(sb.height, 'Kaydet dokunma hedefi').toBeGreaterThanOrEqual(36)
    await save.click()
    await expect.poll(() => sent.length).toBe(1)
    expect(sent[0].new_password).toBe(PASSPHRASE)
    expect(sent[0].new_password).toHaveLength(30)
    await expect(dlg).toBeHidden()
  })
}

test('65 karakterlik parola: Kaydet kapalı, alanın altında "en fazla 64" uyarısı', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockApi(page)
  await page.route((u) => new URL(u).pathname === '/api/me', (route) => route.fulfill({ json: {
    success: true, username: 'demo', system_role: 'USER', global_admin: false, team_id: 1, team_ids: [1],
    must_change_password: true, tour: { status: 'dismissed', version: 99 },
  } }))
  await page.goto('/?tab=dashboard')
  const dlg = page.getByRole('dialog')
  await expect(dlg).toBeVisible({ timeout: 20_000 })
  const inputs = dlg.locator('input[type="password"]')
  const long = 'a'.repeat(65)
  await inputs.nth(0).fill('Gecici123')
  await inputs.nth(1).fill(long)
  await inputs.nth(2).fill(long)
  await expect(inputs.nth(1)).toHaveValue(long)
  await expect(dlg.locator('[data-slot="button"][data-variant="default"]')).toBeDisabled()
  await expect(dlg).toContainText(/at most 64|en fazla 64/)
})
