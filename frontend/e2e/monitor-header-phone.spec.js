// İzleme sayfası başlık araçları telefonda ekrana sığar (2026-10-08, canlı uçtan uca turda bulundu): Alan Adı İzleme'de
// "Şimdi Kontrol Et (N)" + dışa aktarma + kayıtlı görünümler + "Diğer işlemler" tek satıra sığmıyordu ve menü düğmesi
// ekranın sağına taşıyordu (390 px'te 16 px dışarıda, düğmeye dokunulamıyordu). Araç kümesi artık telefonda da sarar.
// En dar desteklenen genişlikte (360 px) dokuz tür ölçülür — jsdom yerleşim hesaplamaz.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const TYPES = ['http', 'ping', 'port', 'dns', 'domain', 'keyword', 'page', 'pagespeed', 'scripted']

for (const vp of [{ width: 360, height: 780 }, { width: 390, height: 844 }]) {
  for (const type of TYPES) {
    test(`${type} @${vp.width}: başlık araçları görünüm alanında, dokunma hedefleri ≥ 40 px`, async ({ page }) => {
      await page.setViewportSize(vp)
      await mockApi(page)
      await page.goto(`/?tab=${type}`)
      const header = page.locator('.app-main [data-slot="page-header"]').first()
      await expect(header).toBeVisible({ timeout: 20_000 })
      const tools = header.locator('[data-slot="monitor-header-tools"]')
      await expect(tools).toBeVisible()
      await page.waitForTimeout(500)
      const out = await tools.evaluate((el) => {
        const vw = document.documentElement.clientWidth
        return [...el.querySelectorAll('button, a')].map((b) => {
          const r = b.getBoundingClientRect()
          return { name: b.getAttribute('aria-label') || b.textContent.trim().slice(0, 30), left: r.left, right: r.right, h: r.height, vw }
        }).filter((b) => b.h > 0)
      })
      expect(out.length).toBeGreaterThan(0)
      for (const b of out) {
        expect(b.right, `${type}: "${b.name}" sağdan taşıyor`).toBeLessThanOrEqual(b.vw + 0.5)
        expect(b.left, `${type}: "${b.name}" soldan taşıyor`).toBeGreaterThanOrEqual(-0.5)
        expect(b.h, `${type}: "${b.name}" dokunma hedefi`).toBeGreaterThanOrEqual(40)
      }
    })
  }
}
