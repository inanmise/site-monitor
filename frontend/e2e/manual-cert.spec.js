// MANUEL (DOSYADAN YÜKLENEN) SERTİFİKALAR — mobil web kapısı (2026-10-06, kullanıcı isteği: "ayrı bir sertifika ekleme
// sayfası … shadcn ile mweb responsive … profesyonel ui"). Telefon (390×844), tablet (768×1024) ve dizüstünde (1280×800):
//   - sayfa (özet kutuları, "Hangi dosyayı yüklemeliyim?" rehberi, liste — telefonda kart, dizüstünde tablo) yatay kaymaz,
//     görünür hiçbir öğe ekran dışına çıkmaz;
//   - yükleme sihirbazı: küçük bir PEM dosyası setInputFiles ile seçilir → analiz (mock) → İnceleme → Takip; her adımda
//     pencere ekrana sığar (telefonda tam ekran), gövde yatay kaymaz;
//   - sertifika penceresinin "Sürümler" sekmesi (güncel + önceki sürüm, PEM indir, yeni sürüm yükle) sığar;
//   - dokunmatikte başlık eylemleri, liste eylemleri, sihirbaz altlığı ve sürüm eylemleri ≥ 40 px.
// API tümüyle mock (route interception) — gerçek kişi/kurum adı yok (example.test).
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { ANALYSIS, DOMAIN, PEM, PERMS, routeManualCerts } from './support/manualCertMocks.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 800 },
]
/** Tarayıcıda: `sel` kökü içinde görünüm alanının sağına taşan en dıştaki görünür öğeler (kendi kaydırma kabı dışında). */
function measure(sel) {
  const vw = document.documentElement.clientWidth
  const root = (sel && document.querySelector(sel)) || document.body
  const pageOverflow = Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth,
    document.body.scrollWidth - document.body.clientWidth)
  const clips = (el) => getComputedStyle(el).overflowX !== 'visible'
  const hidden = (el, r) => {
    if (r.width <= 1 || r.height <= 1) return true
    const cs = getComputedStyle(el)
    return cs.visibility === 'hidden' || cs.display === 'none' || Number(cs.opacity) === 0
  }
  const offenders = []
  const flagged = new Set()
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.right <= vw + 1 || hidden(el, r)) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (clips(p) && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (contained) continue
    flagged.add(el)
    if (el.parentElement && flagged.has(el.parentElement)) continue
    const slot = el.getAttribute('data-slot')
    offenders.push({ el: `${el.tagName.toLowerCase()}${slot ? `[data-slot=${slot}]` : ''}`, right: Math.round(r.right),
      text: (el.textContent || '').trim().slice(0, 40) })
    if (offenders.length >= 8) break
  }
  return { vw, pageOverflow, offenders }
}

/** Dokunma hedefleri ≥ 40 px (görünür olanlar). */
async function expectTouch(locators, label) {
  for (const loc of locators) {
    if (!(await loc.isVisible())) continue
    await loc.scrollIntoViewIfNeeded()
    const b = await loc.boundingBox()
    expect(b.height, `${label} dokunma yüksekliği`).toBeGreaterThanOrEqual(39)
    expect(b.width, `${label} dokunma genişliği`).toBeGreaterThanOrEqual(39)
  }
}

for (const vp of VIEWPORTS) {
  test.describe(`manuel sertifikalar @${vp.name}`, () => {
    test.use({ hasTouch: vp.width < 1024 })

    test(`sayfa + rehber + sihirbaz (dosya → inceleme → takip) + Sürümler sığar @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
      test.setTimeout(180_000)
      const touch = vp.width < 1024
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mockApi(page, { perms: PERMS })
      await routeManualCerts(page)

      // ── Sayfa ──
      await page.goto('/?tab=manualcerts')
      await page.locator('[data-slot="manualcerts-page"]').waitFor({ timeout: 20_000 })
      await page.locator('[data-mcert-row]').first().waitFor({ timeout: 20_000 })
      await expect(page.locator('[data-slot="mcert-guide-body"]')).toBeVisible()
      const view = await page.locator('[data-slot="mcert-list"]').getAttribute('data-view')
      if (vp.name === 'phone') expect(view, 'telefonda kart listesi').toBe('cards')
      if (vp.name === 'desktop') expect(view, 'dizüstünde tablo').toBe('table')
      await page.waitForTimeout(400)
      let m = await page.evaluate(measure, '.app-main')
      expect(m.offenders, `sayfa @${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `sayfa @${vp.name}: yatay kayma (px)`).toBeLessThanOrEqual(1)
      if (touch) {
        await expectTouch([
          ...(await page.locator('[data-slot="page-actions"] button').all()),
          page.locator('[data-slot="stats-toggle"]').first(),
          ...(await page.locator(`[data-mcert-row="${DOMAIN}"] :is([data-slot="mcert-open"], [data-slot="mcert-renew"])`).all()),
          page.getByRole('button', { name: `${DOMAIN} — Actions` }),
        ], `sayfa @${vp.name}`)
      }

      // ── Sihirbaz ──
      await page.locator('[data-slot="mcert-upload"]').first().click()
      const dlg = page.locator('[role="dialog"]:has([data-slot="mcert-wizard"])')
      await dlg.waitFor()
      const checkDialog = async (stage) => {
        await page.waitForTimeout(350)
        const r = await page.evaluate(measure, '[role="dialog"]:has([data-slot="mcert-wizard"])')
        expect(r.offenders, `sihirbaz ${stage} @${vp.name}: ekran dışına taşan öğe`).toEqual([])
        const box = await dlg.boundingBox()
        expect(box.x, `sihirbaz ${stage}: solda taşıyor`).toBeGreaterThanOrEqual(-1)
        expect(box.x + box.width, `sihirbaz ${stage}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
        expect(box.y + box.height, `sihirbaz ${stage}: altta taşıyor`).toBeLessThanOrEqual(vp.height + 1)
        const hScroll = await dlg.locator('[data-slot="modal-shell-body"]').first().evaluate((el) => el.scrollWidth - el.clientWidth)
        expect(hScroll, `sihirbaz ${stage}: gövde yatay kayıyor (px)`).toBeLessThanOrEqual(1)
        if (vp.name === 'phone') expect(Math.round(box.width), 'telefonda tam ekran').toBe(vp.width)
        if (touch) await expectTouch(await dlg.locator('[data-slot="mcert-wizard-actions"] button').all(), `sihirbaz ${stage} altlık`)
      }
      await page.locator('[data-slot="mcert-file-input"]').setInputFiles({ name: 'server.pem', mimeType: 'application/x-pem-file', buffer: Buffer.from(PEM) })
      await expect(dlg.locator('[data-slot="mcert-file-chip"]')).toContainText('server.pem')
      await checkDialog('dosya')
      await dlg.locator('[data-slot="mcert-analyze"]').click()
      await dlg.locator('[data-slot="mcert-wizard"][data-step="review"]').waitFor()
      await expect(dlg.locator('[data-slot="mcert-entry"]')).toHaveCount(1)
      await expect(dlg.locator('[data-slot="mcert-warning"][data-code="CHAIN_INCOMPLETE"]')).toBeVisible()
      await dlg.locator('[data-slot="mcert-chain"] button').first().click()
      await checkDialog('inceleme')
      await dlg.locator('[data-slot="mcert-next"]').click()
      await dlg.locator('[data-slot="mcert-wizard"][data-step="track"]').waitFor()
      await expect(dlg.locator('[data-slot="mcert-key"]')).toHaveValue(ANALYSIS.entries[0].suggested_key)
      await checkDialog('takip')
      await dlg.getByRole('button', { name: /^(Close|Kapat)$/ }).first().click()
      await expect(dlg).toHaveCount(0)

      // ── Sertifika penceresi → Sürümler ──
      await page.locator(`[data-mcert-row="${DOMAIN}"] [data-slot="mcert-open"]`).first().click()
      const box = page.locator('[role="dialog"]:has([data-slot="cert-modal-title"])')
      await box.waitFor()
      await expect(box.locator('[data-slot="cert-modal-title"] [data-slot="manual-cert-badge"]')).toBeVisible()
      if (vp.width < 640) await box.locator('select').first().selectOption('versions')
      else await box.locator('[role="tablist"]').first().locator('[role="tab"]')
        .filter({ has: page.locator('[data-slot="cert-tab-label"]', { hasText: /^(Versions|Sürümler)$/ }) }).click()
      await box.locator('[data-slot="mcert-version"][data-current="true"]').waitFor({ timeout: 15_000 })
      await expect(box.locator('[data-slot="mcert-version"]')).toHaveCount(2)
      await page.waitForTimeout(350)
      m = await page.evaluate(measure, '[role="dialog"]:has([data-slot="cert-modal-title"])')
      expect(m.offenders, `Sürümler @${vp.name}: ekran dışına taşan öğe`).toEqual([])
      const hScroll = await box.locator('[data-slot="modal-shell-body"]').first().evaluate((el) => el.scrollWidth - el.clientWidth)
      expect(hScroll, `Sürümler @${vp.name}: gövde yatay kayıyor (px)`).toBeLessThanOrEqual(1)
      const b = await box.boundingBox()
      expect(b.x + b.width, 'pencere sağda taşıyor').toBeLessThanOrEqual(vp.width + 1)
      if (touch) {
        await expectTouch([
          ...(await box.locator('[data-slot="mcert-pem"]').all()),
          box.locator('[data-slot="mcert-renew-btn"]'),
        ], `Sürümler @${vp.name}`)
      }
    })
  })
}
