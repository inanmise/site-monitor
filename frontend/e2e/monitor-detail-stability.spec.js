import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

// 2026-09-28 (kullanıcı: "detay penceresinde sekmeler arasında gezinince pencere küçülüyor, değişiyor, titriyor — her
// izlemede"): kutu yüksekliği sekme içeriğine göre değişip dikey ortalı pencere yeniden konumlanıyordu (HTTP: yükseklik
// 499–868, üst kenar 16–200 px). MonitorDetailModal artık sabit yükseklik + yalnız gövde kayar. Her türün ANA sekme
// şeridi gezilir, kutu boyutu/konumu örneklenir; oynama ≤ 2 px. jsdom yerleşim hesaplamaz → gerçek tarayıcı.
const TYPES = ['http', 'keyword', 'page', 'pagespeed', 'domain', 'ping', 'port', 'dns', 'scripted']
for (const type of TYPES) {
  test(`${type}: detay penceresi sekme geçişinde boyut/konum sabit`, async ({ page }) => {
    test.setTimeout(180_000)
    await page.setViewportSize({ width: 1440, height: 900 })
    await mockApi(page)
    await page.goto(`/?tab=${type}`)
    const box = page.locator('[data-slot="dialog-content"]').first()
    const open = async () => {
      await page.locator('.upt-grid [data-slot="card"] [data-monitor-open]').first().click({ timeout: 20_000 })
      await expect(box).toBeVisible()
    }
    const tabs = box.locator('[role="tablist"]').first().locator('[role="tab"]')
    // Isınma turu (cert-detail-stability ile aynı): tembel yüklenen sekme parçaları bir kez yüklenir; soğuk Vite
    // geliştirme sunucusu yeni bağımlılık bulunca sayfayı YENİDEN YÜKLEYEBİLİR (ölçüm değil ortam) → pencere yeniden açılır.
    await open()
    const warmN = await tabs.count()
    for (let i = 0; i < warmN; i++) await tabs.nth(i).click().catch(() => {})
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(1000)
    if (!(await box.isVisible())) await open()
    const n = await tabs.count()
    const rows = []
    for (let round = 0; round < 1; round++) {
      for (let i = 0; i < n; i++) {
        await tabs.nth(i).click()
        for (let k = 0; k < 5; k++) {
          const b = await box.boundingBox({ timeout: 5000 })
          if (b) rows.push({ i, h: Math.round(b.height), y: Math.round(b.y), w: Math.round(b.width) })
          await page.waitForTimeout(60)
        }
      }
    }
    const span = (f) => Math.max(...rows.map(f)) - Math.min(...rows.map(f))
    console.log(`${type}: tabs=${n} H ${Math.min(...rows.map((r) => r.h))}-${Math.max(...rows.map((r) => r.h))} Y ${Math.min(...rows.map((r) => r.y))}-${Math.max(...rows.map((r) => r.y))} W ${Math.min(...rows.map((r) => r.w))}-${Math.max(...rows.map((r) => r.w))}`)
    expect(span((r) => r.h), 'yükseklik değişiyor').toBeLessThanOrEqual(2)
    expect(span((r) => r.y), 'dikey konum oynuyor').toBeLessThanOrEqual(2)
    expect(span((r) => r.w), 'genişlik değişiyor').toBeLessThanOrEqual(2)
  })
}
