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

// 2026-09-27 (kullanıcı): sürüm çipinin penceresinde commit satırı pencerenin DIŞINA taşıyordu (40 karakterlik SHA
// kırılamıyordu) ve "devreye alma" satırında ham "unknown" yazıyordu. jsdom yerleşim hesaplamaz → gerçek tarayıcı.
test('sürüm penceresi: uzun commit ve uzun başlıklar pencereden taşmaz; "unknown" ortam anlaşılır etiketle', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  const sha = 'cb6fe53a0123456789abcdef0123456789abcdef'
  const version = {
    version: '20.87.0', commit: sha, commitShort: sha.slice(0, 8), environment: 'unknown', mismatch: false,
    helm: { release: 'site-monitor', revision: 7, chartVersion: '20.87.0' },
    instance: { id: 'i1', hostname: 'h', pod: 'p', node: 'n' }, startedAt: '2026-09-27T10:00:00Z', uptimeSeconds: 3600,
    live: { version: '20.87.0', since: '2026-09-27T10:00:05Z', kind: 'UPGRADE', previousVersion: '20.86.0', restartsSince: 0 },
    release: { version: '20.87.0', releasedAt: '2026-09-27T09:00:00Z', bump: 'minor', breaking: false, counts: { feat: 1 },
      highlights: [], changes: [{ type: 'fix', scope: 'test', subject: 'SpringTestPropertiesTestAndAVeryLongIdentifierWithoutSpacesThatMustWrap', sha: '1' }] },
    releaseLagSeconds: 3600,
  }
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const url = route.request().url()
    let body = { success: true, data: [] }
    if (/\/api\/me(\?|$)/.test(url)) body = { success: true, username: 'demo', system_role: 'USER', team_ids: [1], team_names: ['Takım A'] }
    else if (/\/api\/system\/version/.test(url)) body = { success: true, data: version }
    else if (/\/api\/system\/releases\/notes/.test(url)) body = { success: true, data: { items: [] } }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.goto('/?tab=dashboard')
  await page.getByRole('button', { name: /v20\.87\.0|v\d+\.\d+\.\d+/ }).first().click()
  const pop = page.locator('[data-slot="popover-content"]')
  await expect(pop.locator('code', { hasText: sha.slice(0, 8) })).toBeVisible()
  const overflow = await pop.evaluate((root) => {
    const r = root.getBoundingClientRect()
    return [...root.querySelectorAll('*')].filter((el) => {
      const b = el.getBoundingClientRect()
      return b.width > 0 && (b.right > r.right + 1 || b.left < r.left - 1)
    }).map((el) => `${el.tagName.toLowerCase()} ${Math.round(el.getBoundingClientRect().right)}>${Math.round(r.right)}`)
  })
  expect(overflow, 'pencereden taşan öğe').toEqual([])
  await expect(pop.locator('[data-env="unknown"]')).toHaveText(/^(Ortam adı yok|No environment name)$/)
})
