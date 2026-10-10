import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { CERTS, PERMS, mockCertApi } from './support/certMocks.js'

// 2026-10-10 (kullanıcı: "TLS yapılandırma notu (A–F) … kartta rozet olarak görünür, not düşünce uyarı verir"; "mweb
// responsive"): Pano kartında not rozeti → dokununca "Neden B?" açıklaması ekrana sığar; "TLS notu ayrıntısı" pencerenin
// Sağlık sekmesindeki TLS notu bölümünü açar (nedenler, protokol desteği, yeniden tara); Tüm Sertifikalar'da dağılım
// şeridi + "Son düşüşler" penceresi. Telefon 390, tablet 768, masaüstü 1280: yatay taşma yok, dokunma hedefleri ≥ 40 px.
const iso = (msAgo = 0) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const GRADED = CERTS.map((c, i) => (i === 0
  ? { ...c, tls_grade: 'B', tls_grade_reasons: ['TLS10_ENABLED', 'NO_TLS13', 'HSTS_NOT_CHECKED'],
      tls_grade_drop: { from: 'A', to: 'B', at: iso(3_600_000) } }
  : i === 3 ? { ...c, tls_grade: 'A+' } : c))
const DOMAIN = GRADED[0].domain
const DETAIL = {
  domain: DOMAIN, port: 443, rubric_version: 1, state: 'graded', state_reason: null, grade: 'B',
  reasons: [
    { code: 'TLS10_ENABLED', cap: 'B' },
    { code: 'NO_TLS13', cap: 'A' },
    { code: 'HSTS_NOT_CHECKED', cap: 'A' },
  ],
  decisive: ['TLS10_ENABLED'],
  notes: [{ code: 'KEY_2030', cap: null, params: ['RSA', 2048] }],
  profile: {
    status: 'OK', probed_at: iso(7_200_000), via: 'direct', port: 443,
    protocols: { tls13: 'NO', tls12: 'YES', tls11: 'NO', tls10: 'YES' },
    ocsp_stapling: 'YES', weak_cipher: 'NO', weak_cipher_suite: null,
    preferred_cipher: 'TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384_WITH_A_VERY_LONG_SUFFIX_FOR_WRAPPING', error: null,
    duration_ms: 1840, trigger: 'SCHEDULED',
  },
  negotiated: { tls_version: 'TLSv1.2', cipher_suite: 'TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384', tls_mode_used: 'browser', checked_at: iso(600_000) },
  hsts: { status: null, checked_at: null, max_age_days: null, min_days: 180 },
  history: [{ from: 'A', to: 'B', direction: 'DROP', at: iso(3_600_000), reasons: ['TLS10_ENABLED'] }],
  drop: { from: 'A', to: 'B', at: iso(3_600_000) },
  can_rescan: true,
}
const FACETS = { all: 4, levels: { valid: 4 }, windows: {}, teams: [], no_team: 4, insecure: 0, tiers: {}, nonstd_port: 0,
  trust: {}, grades: { 'A+': 1, A: 0, B: 1, C: 0, D: 0, F: 0, none: 2 } }
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 800 },
]

async function mockTls(page) {
  await page.route((u) => new URL(u).pathname === '/api/certificates', (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: GRADED, timestamp: iso() }),
  }))
  await page.route((u) => /^\/api\/certificates\/[^/]+\/tls-grade$/.test(new URL(u).pathname), (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: DETAIL, timestamp: iso() }),
  }))
  await page.route((u) => new URL(u).pathname === '/api/tls-grade/drops', (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: {
      days: 30,
      rows: [{ domain: DOMAIN, from: 'A', to: 'B', direction: 'DROP', at: iso(3_600_000), reasons: ['TLS10_ENABLED'],
        team_id: 1, team_name: 'Takım A', current: 'B', recovered: false }],
      coverage: { endpoints: 4, ok: 3, partial: 0, failed: 0, pending: 1, latest_probe_at: iso(7_200_000) },
    }, timestamp: iso() }),
  }))
  await page.route((u) => new URL(u).pathname === '/api/certificates/list', (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({
      success: true, data: GRADED, facets: FACETS, shared: {}, scope: 'mine', visible_to_all: false,
      pagination: { current_page: 1, per_page: 25, total: GRADED.length, total_pages: 1 }, timestamp: iso(),
    }),
  }))
}

async function noPageOverflow(page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow, 'yatay taşma').toBeLessThanOrEqual(1)
}

async function fitsViewport(locator, vp) {
  const box = await locator.boundingBox()
  expect(box.x).toBeGreaterThanOrEqual(-1)
  expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
}

for (const vp of VIEWPORTS) {
  test(`TLS notu rozeti + ayrıntı bölümü (${vp.name} ${vp.width})`, async ({ page }) => {
    test.setTimeout(120_000)
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page, { perms: { ...PERMS, 'diagnostics.run': { execute: true } } })
    await mockCertApi(page)
    await mockTls(page)
    await page.goto('/?tab=dashboard')
    const card = page.locator(`[data-slot="cert-grid"] [data-slot="card"][data-domain="${DOMAIN}"]`)
    await expect(card).toBeVisible({ timeout: 20_000 })

    // 1) Rozet kartta: harf + düşüş işareti; dokunma hedefi telefonda ≥ 40 px
    const trigger = card.locator('[data-slot="tls-grade-trigger"]')
    await expect(trigger.locator('[data-slot="tls-grade"]')).toHaveAttribute('data-grade', 'B')
    await expect(trigger.locator('[data-slot="tls-grade"]')).toHaveAttribute('data-dropped', 'true')
    if (vp.width < 768) expect((await trigger.boundingBox()).height).toBeGreaterThanOrEqual(39)
    await noPageOverflow(page)

    // 2) "Neden B?" açıklaması ekrana sığar; kart penceresi açılmaz
    await trigger.click()
    const detail = page.locator('[data-slot="tls-grade-detail"]')
    await expect(detail).toBeVisible()
    await expect(detail.locator('[data-slot="tls-grade-reason"][data-decisive="true"]')).toHaveAttribute('data-code', 'TLS10_ENABLED')
    await expect(detail.locator('[data-slot="tls-grade-drop"]')).toBeVisible()
    await fitsViewport(detail, vp)
    const open = detail.locator('[data-slot="tls-grade-open"]')
    if (vp.width < 768) expect((await open.boundingBox()).height).toBeGreaterThanOrEqual(39)
    await page.screenshot({ path: `test-results/tls-grade-popover-${vp.name}.png` })

    // 3) Ayrıntı → pencere Sağlık sekmesinde, TLS notu bölümü ilk sırada
    await open.click()
    const section = page.locator('[data-slot="tls-grade-section"]')
    await expect(section).toBeVisible({ timeout: 15_000 })
    await expect(section).toHaveAttribute('data-grade', 'B')
    await expect(section.locator('[data-slot="tls-grade-reason-row"]')).toHaveCount(3)
    await expect(section.locator('[data-slot="tls-proto"][data-proto="tls10"]')).toHaveAttribute('data-tone', 'bad')
    await fitsViewport(section, vp)
    const rescan = section.locator('[data-slot="tls-grade-rescan"]')
    await expect(rescan).toBeVisible()
    if (vp.width < 768) expect((await rescan.boundingBox()).height).toBeGreaterThanOrEqual(39)
    // Bölümün içinde yatay kaydırma yok (uzun takım adı kırılır)
    const inner = await section.evaluate((el) => el.scrollWidth - el.clientWidth)
    expect(inner, 'bölüm içi taşma').toBeLessThanOrEqual(1)
    await noPageOverflow(page)
    await section.screenshot({ path: `test-results/tls-grade-section-${vp.name}.png` })
  })

  test(`Tüm Sertifikalar: TLS notu dağılımı + son düşüşler (${vp.name} ${vp.width})`, async ({ page }) => {
    test.setTimeout(120_000)
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page, { perms: PERMS })
    await mockCertApi(page)
    await mockTls(page)
    await page.goto('/?tab=all')
    const strip = page.locator('[data-slot="tls-grade-overview"]')
    await expect(strip).toBeVisible({ timeout: 20_000 })
    await fitsViewport(strip, vp)
    const chip = strip.locator('[data-slot="tls-grade-chip"][data-grade="B"]')
    if (vp.width < 768) expect((await chip.boundingBox()).height).toBeGreaterThanOrEqual(39)
    await noPageOverflow(page)

    await strip.locator('[data-slot="tls-grade-drops-open"]').click()
    const dialog = page.getByRole('dialog', { name: /TLS grade dropped|TLS notu düşen/ })
    await expect(dialog).toBeVisible()
    await expect(dialog.locator('[data-slot="tls-grade-drop-row"]')).toHaveCount(1)
    await expect(dialog.locator('[data-slot="tls-grade-coverage"]')).toBeVisible()
    await fitsViewport(dialog, vp)
    const rowBtn = dialog.getByRole('button', { name: new RegExp(`${DOMAIN.replace(/\./g, '\\.')} — `) })
    if (vp.width < 768) expect((await rowBtn.boundingBox()).height).toBeGreaterThanOrEqual(39)
    await noPageOverflow(page)
    await dialog.screenshot({ path: `test-results/tls-grade-drops-${vp.name}.png` })
  })
}
