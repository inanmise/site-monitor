// Denetim Logu — zincir bütünlüğü (2026-10-23 prod hatası: zincir yeniden başlatıldı). Doğrulama en yeni yeniden
// başlatma satırından başlar; arayüz "Zincir <tarih> tarihinden itibaren doğrulandı — öncesindeki N kayıt onarımdan önce
// yazıldı, doğrulanamaz" der. jsdom yerleşim yapmaz: burada gerçek tarayıcıda telefon / tablet / masaüstü boyunda şeridin
// ekrana sığdığı, metnin kırpılmadığı ve sayfanın yatay kaymadığı ölçülür. API tamamen mock'lu.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const json = (body) => (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 900 },
]

const STATS = {
  total_24h: 5, anomalies_24h: 0, failed_logins_24h: 0, total_7d: 40, anomalies_7d: 0, failed_logins_7d: 0,
  by_outcome_7d: [], by_event_type_7d: [], top_actors_7d: [], by_day_14d: [],
}

for (const vp of VIEWPORTS) {
  test(`denetim zinciri yeniden başlatma notu sığar, kırpılmaz — ${vp.name} ${vp.width}px`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    // mockApi'den SONRA kaydedilen yollar önceliklidir
    await page.route((u) => new URL(u).pathname === '/api/admin/audit/stats', json({ success: true, data: STATS }))
    await page.route((u) => new URL(u).pathname === '/api/admin/audit/integrity', json({ success: true, data: {
      ok: true, checked: 1234, broken_seq: '', broken_id: '', unverified: 4897,
      verified_from: { id: 5000, seq: 4899, event_time: '2026-10-23T10:15:00' },
    } }))

    await page.goto('/?tab=system')
    await page.getByRole('button', { name: /Bütünlüğü doğrula|Verify integrity/ }).click()

    const note = page.locator('[data-slot="audit-integrity-verified-from"]')
    await expect(note).toBeVisible()
    await expect(note).toContainText(/4[.,]897/)
    await expect(note).toContainText(/onarımdan önce yazıldı|written before the repair/)

    // Metin kırpılmaz: içerik kutusundan taşan (genişliği ölçülebilir) öğe yok
    const overflowing = await note.evaluate((root) => [...root.querySelectorAll('*')]
      .filter((el) => el.clientWidth > 0 && el.scrollWidth > el.clientWidth + 1)
      .map((el) => `${el.tagName}:${(el.textContent || '').slice(0, 40)}`))
    expect(overflowing).toEqual([])
    const box = await note.boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(0)
    expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 0.5)
    const hScroll = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(hScroll).toBeLessThanOrEqual(0)
  })
}
