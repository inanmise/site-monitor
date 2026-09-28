import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { CERT_DOMAINS, PERMS, mockCertApi } from './support/certMocks.js'

// 2026-09-28 (kullanıcı: "pop up sayfada gezinirken sayfa küçülüyor, değişiyor, titriyor — genel olarak her izlemede"):
// izleme detay pencerelerinin (monitor-detail-stability.spec.js) kardeşi — Genel Bakış'taki SERTİFİKA kartının penceresi
// (CertificateModal). Kutu yüksekliği sekme içeriğine göre değişiyor, dikey ortalı pencere her geçişte yeniden
// konumlanıyordu. Artık sabit yükseklik (sm+ 88vh tavanı, telefonda neredeyse tam ekran), başlık + sekme şeridi sabit,
// yalnız gövde kayar. Pencere TÜM sekmelerinde gezilir (masaüstü/tablet: sekme şeridi, telefon: bölüm seçicisi); kutu
// boyutu/konumu her geçişte beş kez örneklenir; oynama ≤ 2 px. jsdom yerleşim hesaplamaz → gerçek tarayıcı.
const VIEWPORTS = [
  { name: 'desktop', width: 1440, height: 900 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'phone', width: 390, height: 844 },
]

for (const vp of VIEWPORTS) {
  test(`sertifika penceresi (${vp.name} ${vp.width}×${vp.height}): tüm sekmelerde boyut/konum sabit`, async ({ page }) => {
    test.setTimeout(180_000)
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page, { perms: PERMS })
    await mockCertApi(page)
    const phone = vp.width < 640
    const box = page.locator('[data-slot="dialog-content"]').first()
    const picker = box.locator('select').first()
    const open = async () => {
      await page.goto('/?tab=dashboard')
      await page.locator(`[data-slot="card"][data-domain="${CERT_DOMAINS.healthy}"] [data-cert-open]`).first().click({ timeout: 20_000 })
      await expect(box).toBeVisible()
      await expect(box.locator('[data-slot="ssl-verdict"]')).toBeVisible({ timeout: 10_000 })
    }
    const tabValues = () => (phone
      ? picker.locator('option').evaluateAll((os) => os.map((o) => o.value))
      : box.locator('[role="tablist"]').first().locator('[role="tab"]').evaluateAll((ts) => ts.map((el) => el.id)))
    const select = (values, i) => (phone ? picker.selectOption(values[i])
      : box.locator('[role="tablist"]').first().locator('[role="tab"]').nth(i).click())

    // Isınma turu: tembel yüklenen sekme parçaları (grafik, sağlık) bir kez yüklenir. Soğuk Vite geliştirme sunucusu
    // yeni bağımlılık bulunca sayfayı YENİDEN YÜKLEYEBİLİR (ölçüm değil ortam) — pencere kapandıysa yeniden açılır.
    await open()
    const warm = await tabValues()
    for (let i = 0; i < warm.length; i++) await select(warm, i).catch(() => {})
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(1500)
    if (!(await box.isVisible())) await open()

    const values = await tabValues()
    expect(values.length, 'sekme sayısı').toBeGreaterThanOrEqual(7)

    const rows = []
    for (let i = 0; i < values.length; i++) {
      await select(values, i)
      for (let k = 0; k < 5; k++) {
        const b = await box.boundingBox({ timeout: 5000 })
        if (b) rows.push({ i, h: Math.round(b.height), y: Math.round(b.y), w: Math.round(b.width) })
        await page.waitForTimeout(60)
      }
    }
    const span = (f) => Math.max(...rows.map(f)) - Math.min(...rows.map(f))
    console.log(`cert ${vp.name}: tabs=${values.length} H ${Math.min(...rows.map((r) => r.h))}-${Math.max(...rows.map((r) => r.h))} Y ${Math.min(...rows.map((r) => r.y))}-${Math.max(...rows.map((r) => r.y))} W ${Math.min(...rows.map((r) => r.w))}-${Math.max(...rows.map((r) => r.w))}`)
    expect(span((r) => r.h), 'yükseklik değişiyor').toBeLessThanOrEqual(2)
    expect(span((r) => r.y), 'dikey konum oynuyor').toBeLessThanOrEqual(2)
    expect(span((r) => r.w), 'genişlik değişiyor').toBeLessThanOrEqual(2)
    // Telefonda pencere neredeyse tam ekran ve ekrandan taşmıyor
    const last = await box.boundingBox()
    expect(last.y + last.height, 'pencere ekranın altına taşıyor').toBeLessThanOrEqual(vp.height + 1)
    if (phone) expect(last.height, 'telefonda neredeyse tam ekran').toBeGreaterThanOrEqual(vp.height - 40)
  })
}

// Ek 3/9 (2026-09-28): /api/history mock'u GERÇEK geçmiş satırı döner (envanter alanları yok, `tls_version` +
// `cipher_suite` + `tls_assessment` var) — Detaylar'ın zayıf protokol / şifre rozetleri sunucu hükmünden (tls_assessment)
// çizilir. Mock eskiden liste satırı dönüyordu; bu yol e2e'de hiç sınanmıyordu.
test('sertifika penceresi Detaylar: zayıf protokol + şifre rozeti geçmiş satırının tls_assessment hükmünden', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await mockApi(page, { perms: PERMS })
  await mockCertApi(page)
  const box = page.locator('[data-slot="dialog-content"]').first()
  const details = async (domain) => {
    await page.goto('/?tab=dashboard')
    await page.locator(`[data-slot="card"][data-domain="${domain}"] [data-cert-open]`).first().click({ timeout: 20_000 })
    await expect(box).toBeVisible()
    await box.locator('[role="tablist"]').first().locator('[role="tab"]').filter({ hasText: /Details|Detaylar/ }).click()
    await expect(box.locator('[data-slot="cert-details"]')).toBeVisible({ timeout: 10_000 })
  }
  await details(CERT_DOMAINS.problem)
  await expect(box.locator('[data-slot="cert-weak-protocol"]')).toBeVisible()
  await expect(box.locator('[data-slot="cert-weak-cipher"]')).toBeVisible()
  await expect(box.locator('[data-slot="cert-details"]')).toContainText('TLS_RSA_WITH_3DES_EDE_CBC_SHA')
  await details(CERT_DOMAINS.healthy)
  await expect(box.locator('[data-slot="cert-details"]')).toContainText('TLS_AES_256_GCM_SHA384')
  await expect(box.locator('[data-slot="cert-weak-protocol"]')).toHaveCount(0)
  await expect(box.locator('[data-slot="cert-weak-cipher"]')).toHaveCount(0)
})
