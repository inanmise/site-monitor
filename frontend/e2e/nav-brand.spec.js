// Sol üstteki marka (turp logosu + ad) Pano'yu açar — gerçek tarayıcıda, API mock'lu, girişsiz.
// 2026-09-25 kullanıcı isteği: "sol üst köşedeki turp logosuna tıkladığınızda dashboard açılsın".
import { test, expect } from '@playwright/test'

async function mockApi(page) {
  // YALNIZ /api/ yolları (kaynak modülü /src/api/client.js'e dokunma)
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const url = route.request().url()
    let body = { success: true, data: [] }
    if (/\/api\/me(\?|$)/.test(url)) body = { success: true, username: 'demo', system_role: 'USER', team_ids: [1], team_names: ['Takım A'] }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

// Etkin sekme: `aria-current="page"` — üst öğe (Pano) de alt menü öğesi (Uyarılar, Alarmlar bölümünde) de taşır
// (2026-09-26 kenar çubuğu yeniden tasarımı: sekmeler bölümlerin SidebarMenuSub'ına taşındı).
const activeTab = (page, rx) => page.locator('[data-slot="sidebar"] [aria-current="page"]', { hasText: rx })
const dashboardActive = (page) => activeTab(page, /^(Pano|Dashboard)$/)
// Başlangıç sekmesi URL'den uygulanana kadar bekle — yoksa tıklama, uygulamanın ?tab= okumasıyla yarışır.
const warningsReady = (page) => expect(activeTab(page, /^(Uyarılar|Warnings)/)).toHaveCount(1)

test('turp logosuna tıklamak Pano\'yu açar', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await mockApi(page)
  await page.goto('/?tab=warnings')
  const logo = page.getByTestId('nav-brand')
  await expect(logo).toBeVisible()
  await warningsReady(page)                                     // başlangıç: Uyarılar sekmesi
  await expect(dashboardActive(page)).toHaveCount(0)
  await logo.locator('img, svg').first().click()               // logonun KENDİSİNE tıkla (sarmalayıcıya değil)
  await expect(dashboardActive(page)).toHaveCount(1)
  await expect(page).not.toHaveURL(/tab=warnings/)
})

test('kenar çubuğu daraltılmışken de logo Pano\'yu açar', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await mockApi(page)
  await page.goto('/?tab=warnings')
  await warningsReady(page)
  await page.keyboard.press('Control+b')                        // kenar çubuğunu daralt
  const logo = page.getByTestId('nav-brand')
  await expect(logo).toBeVisible()
  await logo.click()
  await expect(dashboardActive(page)).toHaveCount(1)
})
