import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { CERT_DOMAINS, PERMS, mockCertApi } from './support/certMocks.js'

// 2026-10-12 (kullanıcı isteği: "otomatik çekilen sertifikalarda da tarayıcı gibi hiyerarşi; Root / Intermediate / Leaf
// çok net görünsün, her birinin bitiş tarihi ayrı ayrı izlensin; mevcut işlevler bozulmasın"): sertifika penceresinin SSL
// sekmesinde "Zincir" varsayılan kalır; "Hiyerarşi (tarayıcı gibi)" kök → ara → yaprak ağacını, her satırda rol rozeti +
// bitiş tarihiyle gösterir. Gerçek uzunlukta adlar kırpılmaz, pencere yatay taşmaz, telefon / tablette dokunma hedefleri
// ≥ 40 px. jsdom yerleşim hesaplamaz → gerçek tarayıcı.
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 900 },
]

const LONG_ROOT = 'Örnek Kurum Genel Amaçlı Güven Kökü Sertifika Yetkilisi G3 (Test Ortamı / Bilgi Teknolojileri)'
const LONG_INTER = 'Örnek Kurum Sunucu Kimlik Doğrulama Ara Sertifika Yetkilisi 2026 (Test / Operasyon Birimi)'

function node(over) {
  return {
    role: 'leaf', depth: 2, position: 0, head: true,
    subject_dn: `CN=${CERT_DOMAINS.healthy},O=Example Test`, subject: { cn: CERT_DOMAINS.healthy, o: 'Example Test' },
    issuer_dn: `CN=${LONG_INTER}`, issuer: { cn: LONG_INTER },
    serial_number: '0A1B2C', not_before: '2026-01-01T00:00:00', not_after: '2027-01-01T00:00:00', days_remaining: 81,
    expired: false, not_yet_valid: false, signature_algorithm: 'SHA256withRSA', public_key_algorithm: 'RSA', public_key_size: 2048,
    san: [CERT_DOMAINS.healthy], key_usage: ['Digital Signature'], ext_key_usage: ['TLS Web Server'],
    is_ca: false, path_length: null, self_signed: false, issuer_missing: false,
    sha256_fingerprint: 'AB'.repeat(32), sha1_fingerprint: 'CD'.repeat(20),
    pem: '-----BEGIN CERTIFICATE-----\nTUlJQkZBS0U=\n-----END CERTIFICATE-----\n', source: 'server', trust_store: null,
    ...over,
  }
}

const HIERARCHY = {
  success: true,
  data: {
    domain: CERT_DOMAINS.healthy, port: 443, via: 'direct', checked_at: '2026-10-12T09:00:00',
    presented_count: 2, root_source: 'trust_store', trust_store: 'system', order_ok: true,
    extra_certificates: [{ subject_dn: 'CN=Eski Çapraz İmzalı Kök (Test)', not_after: '2025-06-01T00:00:00', days_remaining: -133, expired: true }],
    certificate_count: 3, issuer_missing: false, missing_issuer_dn: null,
    nodes: [
      node({ role: 'root', depth: 0, position: 2, head: false, subject_dn: `CN=${LONG_ROOT}`, subject: { cn: LONG_ROOT },
        issuer_dn: `CN=${LONG_ROOT}`, issuer: { cn: LONG_ROOT }, not_after: '2038-01-15T00:00:00', days_remaining: 4113,
        is_ca: true, self_signed: true, san: [], source: 'trust_store', trust_store: 'system' }),
      node({ role: 'intermediate', depth: 1, position: 1, head: false, subject_dn: `CN=${LONG_INTER}`, subject: { cn: LONG_INTER },
        issuer_dn: `CN=${LONG_ROOT}`, issuer: { cn: LONG_ROOT }, not_after: '2026-10-30T00:00:00', days_remaining: 18,
        is_ca: true, path_length: 0, san: [] }),
      node({}),
    ],
  },
}

for (const vp of VIEWPORTS) {
  test(`ağ sertifikası hiyerarşisi (${vp.name} ${vp.width}): zincir varsayılan, ağaç rol + bitiş tarihi, taşma / kırpma yok`, async ({ page }) => {
    test.setTimeout(120_000)
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page, { perms: PERMS })
    await mockCertApi(page)
    let calls = 0
    await page.route((u) => /\/api\/certificates\/[^/]+\/hierarchy$/.test(new URL(u).pathname), async (route) => {
      calls++
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(HIERARCHY) })
    })

    await page.goto('/?tab=dashboard')
    await page.locator(`[data-slot="card"][data-domain="${CERT_DOMAINS.healthy}"] [data-cert-open]`).first().click({ timeout: 20_000 })
    const box = page.locator('[data-slot="dialog-content"]').first()
    await expect(box.locator('[data-slot="ssl-verdict"]')).toBeVisible({ timeout: 15_000 })

    // Varsayılan: zincir kartları (mevcut işlev), istek yok
    const area = box.locator('[data-slot="ssl-chain-area"]')
    await expect(area).toHaveAttribute('data-view', 'chain')
    await expect(box.locator('[data-slot="ssl-chain"]')).toBeVisible()
    expect(calls).toBe(0)

    await area.getByText('Hierarchy (browser style)').click()
    const nodes = box.locator('[data-slot="cert-hierarchy-node"]')
    await expect(nodes).toHaveCount(3, { timeout: 15_000 })
    expect(calls).toBe(1)
    await expect(nodes.nth(0).locator('[data-slot="cert-hierarchy-role"]')).toHaveText('Root')
    await expect(nodes.nth(1).locator('[data-slot="cert-hierarchy-role"]')).toHaveText('Intermediate')
    await expect(nodes.nth(2).locator('[data-slot="cert-hierarchy-role"]')).toHaveText('Leaf')
    for (let i = 0; i < 3; i++) {
      await expect(nodes.nth(i).locator('[data-slot="cert-hierarchy-expiry"]')).toContainText('Expires:')
    }
    await expect(nodes.nth(0).locator('[data-slot="cert-hierarchy-source"]')).toBeVisible()
    await expect(box.locator('[data-slot="cert-hierarchy-summary"]')).toHaveAttribute('data-root-source', 'trust_store')

    // Uzun adlar tam görünür (kırpma yok) ve pencere gövdesi yatay taşmaz
    for (let i = 0; i < 3; i++) {
      const clipped = await nodes.nth(i).evaluate((li) => [...li.querySelectorAll('span')]
        .some((s) => s.scrollWidth > s.clientWidth + 1 && getComputedStyle(s).overflow !== 'visible'))
      expect(clipped, `düğüm ${i} adı kırpılıyor`).toBe(false)
    }
    await expect(nodes.nth(0)).toContainText(LONG_ROOT)
    const overflow = await box.evaluate((el) => {
      const body = el.querySelector('[data-slot="ssl-panel"]')
      return body ? body.scrollWidth - body.clientWidth : 0
    })
    expect(overflow, 'SSL paneli yatay taşıyor').toBeLessThanOrEqual(1)

    // Kök seçilince ayrıntıda kaynak; görünüm değiştirip dönmek yeniden bağlanmaz
    await nodes.nth(0).click()
    await expect(box.locator('[data-slot="cert-hierarchy-details-source"]')).toBeVisible()
    await area.getByText('Chain', { exact: true }).click()
    await expect(area).toHaveAttribute('data-view', 'chain')
    await area.getByText('Hierarchy (browser style)').click()
    await expect(nodes).toHaveCount(3)
    expect(calls).toBe(1)

    // Telefon / tablette ağaç satırları ve seçici düğmeleri dokunmaya uygun (≥ 40 px)
    if (vp.width < 1024) {
      const heights = await nodes.evaluateAll((els) => els.map((e) => e.getBoundingClientRect().height))
      for (const h of heights) expect(h).toBeGreaterThanOrEqual(40)
    }
  })
}
