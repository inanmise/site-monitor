// MANUEL (DOSYADAN YÜKLENEN) SERTİFİKALAR — mobil web kapısı (2026-10-06, kullanıcı isteği: "ayrı bir sertifika ekleme
// sayfası … shadcn ile mweb responsive … profesyonel ui"). Telefon (390×844), tablet (768×1024) ve dizüstünde (1280×800):
//   - sayfa (özet kutuları, "Hangi dosyayı yüklemeliyim?" rehberi, liste — telefonda kart, dizüstünde tablo) yatay kaymaz,
//     görünür hiçbir öğe ekran dışına çıkmaz;
//   - yükleme sihirbazı: küçük bir PEM dosyası setInputFiles ile seçilir → analiz (mock) → İnceleme → Takip; her adımda
//     pencere ekrana sığar (telefonda tam ekran), gövde yatay kaymaz;
//   - sertifika penceresinin SSL sekmesi (manuelde açılış sekmesi: çevrim-dışı önizleme, yaprak → ara → kök kartları,
//     2026-10-07) ve "Sürümler" sekmesi (güncel + önceki sürüm, PEM indir, eski sürümü sil, yeni sürüm yükle) sığar;
//   - dokunmatikte başlık eylemleri, liste eylemleri, sihirbaz altlığı ve sürüm eylemleri ≥ 40 px;
//   - 2026-10-08 (kullanıcı isteği: özel anahtar sunucuya hiç gitmez): seçilen dosya sahte bir PRIVATE KEY bloğu taşır;
//     dosya GERÇEK tarayıcıda (Web Worker) açılır, analiz isteğinin gövdesinde yalnız `extracted` vardır — "PRIVATE KEY",
//     anahtar gövdesi, `file` / `password` alanı YOK; İnceleme'de "özel anahtar (1) tarayıcınızda ayıklandı" notu.
//   - 2026-10-08 (kullanıcı isteği: "manuel yükleme yaparken yükleme durumunu gösterelim"): YAVAŞLATILMIŞ analiz yanıtında
//     (route gecikmesi) yükleme durumu paneli görünür (aşamalar, çubuk, "Vazgeç"), telefonda (390×844) ve dizüstünde
//     (1280×800) sığar, aşama değişirken altlık zıplamaz; "Vazgeç" isteği keser ve adıma temiz döner; ikinci deneme biter.
// API tümüyle mock (route interception) — gerçek kişi/kurum adı yok (example.test).
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { ANALYSIS, DOMAIN, FAKE_KEY_BODY, PEM, PEM_WITH_FAKE_KEY, PERMS, routeManualCerts } from './support/manualCertMocks.js'
import { routeHierarchy } from './support/certHierarchyMocks.js'

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

/** Kapsayıcıdaki tüm katlanır bölümleri açar (SAN listesi, teknik ayrıntılar) — her tıklamada liste yeniden sayılır. */
async function expandAll(container) {
  const closed = container.locator('button[aria-expanded="false"]')
  for (let i = 0; i < 12 && (await closed.count()) > 0; i++) await closed.first().click()
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
      // Rehber varsayılan KAPALI (2026-10-07); ölçüm açık hâliyle yapılır.
      await expect(page.locator('[data-slot="mcert-guide-body"]')).toHaveCount(0)
      await page.locator('[data-slot="mcert-guide"]').getByRole('button', { name: /Which file should I upload|Hangi dosyayı/ }).click()
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
      await page.locator('[data-slot="mcert-file-input"]').setInputFiles({ name: 'server.pem', mimeType: 'application/x-pem-file', buffer: Buffer.from(PEM_WITH_FAKE_KEY) })
      await expect(dlg.locator('[data-slot="mcert-file-chip"]')).toContainText('server.pem')
      await expect(dlg.locator('[data-slot="mcert-local-hint"]')).toBeVisible()
      await checkDialog('dosya')
      const analyzeReq = page.waitForRequest((r) => new URL(r.url()).pathname === '/api/manual-certs/analyze' && r.method() === 'POST')
      const worker = page.waitForEvent('worker', { timeout: 15_000 })
      await dlg.locator('[data-slot="mcert-analyze"]').click()
      // Ayıklama ana iş parçacığını dondurmaz: Web Worker'da koşar
      expect((await worker).url()).toContain('extract.worker')
      // Özel anahtar tarayıcıdan çıkmaz: gövdede yalnız `extracted` (açık sertifika), anahtar / parola / ham dosya YOK
      const sent = (await analyzeReq).postData() || ''
      expect(sent).toContain('name="extracted"')
      expect(sent).toContain(PEM.split('\n')[1])                       // sertifikanın DER'i (Base64) — ilk satır aynı
      expect(sent).not.toContain('PRIVATE KEY')
      expect(sent).not.toContain(FAKE_KEY_BODY)
      expect(sent).not.toContain('name="file"')
      expect(sent).not.toContain('name="password"')
      expect(sent).not.toContain('-----BEGIN')
      await dlg.locator('[data-slot="mcert-wizard"][data-step="review"]').waitFor()
      await expect(dlg.locator('[data-slot="mcert-kept-local"]')).toHaveAttribute('data-keys', '1')
      // 2026-10-07: yaprak + ara + kök TEK girdi; zincir SSL sekmesiyle AYNI kartlarla (yaprak → ara → kök)
      await expect(dlg.locator('[data-slot="mcert-entry"]')).toHaveCount(1)
      await expect(dlg.locator('[data-slot="mcert-grouped"]')).toBeVisible()
      const chainView = dlg.locator('[data-slot="mcert-chain"] [data-slot="ssl-chain"]')
      const nodes = chainView.locator('[data-slot="ssl-chain-node"]')
      await expect(nodes).toHaveCount(3)
      expect(await nodes.evaluateAll((els) => els.map((e) => e.getAttribute('data-role')))).toEqual(['leaf', 'intermediate', 'root'])
      // En geniş hâl: SAN listesi ve teknik ayrıntılar açık (uzun DN'ler, 9 ad)
      await expandAll(chainView)
      await checkDialog('inceleme')
      await dlg.locator('[data-slot="mcert-next"]').click()
      await dlg.locator('[data-slot="mcert-wizard"][data-step="track"]').waitFor()
      await expect(dlg.locator('[data-slot="mcert-key"]')).toHaveValue(ANALYSIS.entries[0].suggested_key)
      await checkDialog('takip')
      await dlg.getByRole('button', { name: /^(Close|Kapat)$/ }).first().click()
      await expect(dlg).toHaveCount(0)

      // ── Sertifika penceresi → SSL (açılış sekmesi, çevrim-dışı önizleme) → Sürümler ──
      await page.locator(`[data-mcert-row="${DOMAIN}"] [data-slot="mcert-open"]`).first().click()
      const box = page.locator('[role="dialog"]:has([data-slot="cert-modal-title"])')
      await box.waitFor()
      await expect(box.locator('[data-slot="cert-modal-title"] [data-slot="manual-cert-badge"]')).toBeVisible()
      const ssl = box.locator('[data-slot="ssl-panel"][data-source="upload"]')
      await ssl.waitFor({ timeout: 15_000 })
      expect(await ssl.locator('[data-slot="ssl-chain-node"]').evaluateAll((els) => els.map((e) => e.getAttribute('data-role'))))
        .toEqual(['leaf', 'intermediate', 'root'])
      await expect(ssl.locator('[data-slot="ssl-check-group"][data-group="conn"]')).toHaveCount(0)
      await expandAll(ssl.locator('[data-slot="ssl-chain"]'))
      await page.waitForTimeout(350)
      m = await page.evaluate(measure, '[role="dialog"]:has([data-slot="cert-modal-title"])')
      expect(m.offenders, `SSL (manuel) @${vp.name}: ekran dışına taşan öğe`).toEqual([])
      const sslScroll = await box.locator('[data-slot="modal-shell-body"]').first().evaluate((el) => el.scrollWidth - el.clientWidth)
      expect(sslScroll, `SSL (manuel) @${vp.name}: gövde yatay kayıyor (px)`).toBeLessThanOrEqual(1)
      // Paneldeki "Yeniden değerlendir" ağ sertifikasının "Yeniden kontrol et"iyle AYNI düğme (telefonda 40 px)
      if (vp.name === 'phone') await expectTouch(await ssl.locator('[data-slot="ssl-verdict"] button').all(), `SSL (manuel) @${vp.name}`)
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
          ...(await box.locator('[data-slot="mcert-version-delete"]').all()),
          box.locator('[data-slot="mcert-renew-btn"]'),
        ], `Sürümler @${vp.name}`)
        await expect(box.locator('[data-slot="mcert-version-delete"]'), 'yalnız eski sürümde "Sil"').toHaveCount(1)
      }
    })

    // Tarayıcı gibi sertifika hiyerarşisi (2026-10-07): SSL sekmesinde "Hiyerarşi" görünümü (kök → ara → yaprak ağacı +
    // seçili sertifikanın ayrıntısı) ve Sürümler'de "Görüntüle" iç içe penceresi — en geniş hâlde (9 SAN açık, uzun DN'ler)
    // ekrana sığar, yatay kaymaz; ağaç satırları ≥ 40 px; telefonda ağaç üstte, ayrıntı altta.
    test(`sertifika hiyerarşisi — SSL sekmesi + Sürümler "Görüntüle" sığar @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
      test.setTimeout(120_000)
      const touch = vp.width < 1024
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mockApi(page, { perms: PERMS })
      await routeManualCerts(page)
      await routeHierarchy(page)

      await page.goto('/?tab=manualcerts')
      const open = page.locator(`[data-mcert-row="${DOMAIN}"] [data-slot="mcert-open"]`).first()
      await open.waitFor({ timeout: 20_000 })
      await open.click()
      const box = page.locator('[role="dialog"]:has([data-slot="cert-modal-title"])')
      await box.waitFor()
      const ssl = box.locator('[data-slot="ssl-panel"][data-source="upload"]')
      await ssl.waitFor({ timeout: 15_000 })
      await expect(ssl.locator('[data-slot="ssl-chain"]'), 'varsayılan görünüm ağ tarzı zincir').toBeVisible()
      const toggle = ssl.getByRole('group', { name: /^(Chain view|Zincir görünümü)$/ })
      await toggle.getByRole('button', { name: /^(Hierarchy \(browser style\)|Hiyerarşi \(tarayıcı gibi\))$/ }).click()

      const view = ssl.locator('[data-slot="cert-hierarchy"]')
      await view.waitFor({ timeout: 15_000 })
      const items = view.getByRole('treeitem')
      await expect(items).toHaveCount(3)
      expect(await items.evaluateAll((els) => els.map((e) => e.getAttribute('data-role')))).toEqual(['root', 'intermediate', 'leaf'])
      await expect(items.nth(2)).toHaveAttribute('aria-selected', 'true')
      const details = view.locator('[data-slot="cert-hierarchy-details"]')
      await expect(details).toHaveAttribute('data-role', 'leaf')
      await expandAll(details)
      await expect(details.locator('[data-slot="cert-hierarchy-san"] li')).toHaveCount(9)
      await page.waitForTimeout(350)
      let m = await page.evaluate(measure, '[role="dialog"]:has([data-slot="cert-modal-title"])')
      expect(m.offenders, `hiyerarşi (SSL) @${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `hiyerarşi (SSL) @${vp.name}: yatay kayma (px)`).toBeLessThanOrEqual(1)
      const sslScroll = await box.locator('[data-slot="modal-shell-body"]').first().evaluate((el) => el.scrollWidth - el.clientWidth)
      expect(sslScroll, `hiyerarşi (SSL) @${vp.name}: gövde yatay kayıyor (px)`).toBeLessThanOrEqual(1)
      for (const it of await items.all()) {
        const b = await it.boundingBox()
        expect(b.height, `hiyerarşi @${vp.name}: ağaç satırı yüksekliği`).toBeGreaterThanOrEqual(40)
      }
      if (vp.name === 'phone') {
        const tb = await view.locator('[data-slot="cert-hierarchy-tree"]').boundingBox()
        const db = await details.boundingBox()
        expect(db.y, 'telefonda ayrıntı ağacın ALTINDA').toBeGreaterThanOrEqual(tb.y + tb.height - 1)
      }
      if (touch) {
        await expectTouch([
          ...(await toggle.getByRole('button').all()),
          details.locator('[data-slot="cert-hierarchy-pem"]'),
          ...(await details.locator('[data-slot="copyable-ref"] button').all()),
        ], `hiyerarşi (SSL) @${vp.name}`)
      }
      // seçim: kök sertifika → ayrıntı köke geçer
      await items.nth(0).click()
      await expect(items.nth(0)).toHaveAttribute('aria-selected', 'true')
      await expect(details).toHaveAttribute('data-role', 'root')

      // ── Sürümler → eski sürümde "Görüntüle" → iç içe pencere ──
      if (vp.width < 640) await box.locator('select').first().selectOption('versions')
      else await box.locator('[role="tablist"]').first().locator('[role="tab"]')
        .filter({ has: page.locator('[data-slot="cert-tab-label"]', { hasText: /^(Versions|Sürümler)$/ }) }).click()
      await box.locator('[data-slot="mcert-version"][data-current="true"]').waitFor({ timeout: 15_000 })
      if (touch) await expectTouch(await box.locator('[data-slot="mcert-version-view"]').all(), `Sürümler "Görüntüle" @${vp.name}`)
      await box.locator('[data-slot="mcert-version"][data-current="false"] [data-slot="mcert-version-view"]').click()
      const viewer = page.locator('[role="dialog"]:has([data-slot="mcert-version-viewer"])')
      await viewer.waitFor()
      await expect(viewer.getByRole('heading', { level: 2 })).toContainText(/v2/)
      const vView = viewer.locator('[data-slot="cert-hierarchy"]')
      await vView.waitFor({ timeout: 15_000 })
      await expect(vView.getByRole('treeitem')).toHaveCount(3)
      await expandAll(vView.locator('[data-slot="cert-hierarchy-details"]'))
      await page.waitForTimeout(350)
      m = await page.evaluate(measure, '[role="dialog"]:has([data-slot="mcert-version-viewer"])')
      expect(m.offenders, `Görüntüle penceresi @${vp.name}: ekran dışına taşan öğe`).toEqual([])
      const vb = await viewer.boundingBox()
      expect(vb.x, 'Görüntüle penceresi solda taşıyor').toBeGreaterThanOrEqual(-1)
      expect(vb.x + vb.width, 'Görüntüle penceresi sağda taşıyor').toBeLessThanOrEqual(vp.width + 1)
      expect(vb.y + vb.height, 'Görüntüle penceresi altta taşıyor').toBeLessThanOrEqual(vp.height + 1)
      const vScroll = await viewer.locator('[data-slot="modal-shell-body"]').first().evaluate((el) => el.scrollWidth - el.clientWidth)
      expect(vScroll, `Görüntüle penceresi @${vp.name}: gövde yatay kayıyor (px)`).toBeLessThanOrEqual(1)
      const vtb = await vView.locator('[data-slot="cert-hierarchy-tree"]').boundingBox()
      const vdb = await vView.locator('[data-slot="cert-hierarchy-details"]').boundingBox()
      if (vp.name === 'desktop') expect(vdb.x, 'geniş pencerede ayrıntı ağacın YANINDA').toBeGreaterThanOrEqual(vtb.x + vtb.width - 1)
      if (vp.name === 'phone') expect(vdb.y, 'telefonda ayrıntı ağacın ALTINDA').toBeGreaterThanOrEqual(vtb.y + vtb.height - 1)
      for (const it of await vView.getByRole('treeitem').all()) {
        const b = await it.boundingBox()
        expect(b.height, `Görüntüle @${vp.name}: ağaç satırı yüksekliği`).toBeGreaterThanOrEqual(40)
      }
      await viewer.getByRole('button', { name: /^(Close|Kapat)$/ }).first().click()
      await expect(viewer).toHaveCount(0)
      await expect(box, 'iç pencere kapanınca sertifika penceresi yerinde').toBeVisible()
    })
  })
}

// ── Yükleme durumu (2026-10-08): yavaş sunucu yanıtında panel görünür, sığar, "Vazgeç" temiz döner ──────────────────
for (const vp of VIEWPORTS.filter((v) => v.name !== 'tablet')) {
  test.describe(`manuel sertifika yükleme durumu @${vp.name}`, () => {
    test.use({ hasTouch: vp.width < 1024 })

    test(`yavaş analiz: aşamalar + çubuk + Vazgeç görünür ve sığar; Vazgeç temiz döner; ikinci deneme biter @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
      test.setTimeout(120_000)
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mockApi(page, { perms: PERMS })
      await routeManualCerts(page)
      // Sunucu yavaş: her analiz yanıtı 4 sn gecikir (kesilen istekte fulfill hata verir — yok sayılır)
      let analyzeCalls = 0
      await page.route((u) => new URL(u).pathname === '/api/manual-certs/analyze', async (route) => {
        analyzeCalls++
        await new Promise((r) => setTimeout(r, 4000))
        try {
          await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: ANALYSIS }) })
        } catch { /* istemci "Vazgeç" ile kesti */ }
      })

      await page.goto('/?tab=manualcerts')
      await page.locator('[data-slot="manualcerts-page"]').waitFor({ timeout: 20_000 })
      await page.locator('[data-slot="mcert-upload"]').first().click()
      const dlg = page.locator('[role="dialog"]:has([data-slot="mcert-wizard"])')
      await dlg.waitFor()
      await page.locator('[data-slot="mcert-file-input"]').setInputFiles({ name: 'server.pem', mimeType: 'application/x-pem-file', buffer: Buffer.from(PEM_WITH_FAKE_KEY) })
      await dlg.locator('[data-slot="mcert-analyze"]').click()

      // Panel: dört aşama baştan listede; ayıklama (Web Worker) bitti, istek yolda. Route kesmesinde tarayıcı istek gövdesini
      // ağa vermeden durdurduğu için yükleme baytı bildirilmez: "gönderiliyor" ya da "sunucu analiz ediyor" sürer (gerçek
      // sunucuda yüzde + aşama geçişi canlı suite'te). Gecikme boyunca panel ekranda kalır.
      const panel = dlg.locator('[data-slot="mcert-progress"]')
      await expect(panel).toBeVisible()
      await expect(panel).toHaveAttribute('data-kind', 'analyze')
      const stages = panel.locator('[data-slot="mcert-progress-stage"]')
      await expect(stages).toHaveCount(4)
      await expect(panel.locator('[data-slot="mcert-progress-stage"][data-stage="extract"]')).toHaveAttribute('data-state', 'done', { timeout: 10_000 })
      const states = await stages.evaluateAll((els) => els.map((e) => `${e.dataset.stage}:${e.dataset.state}`))
      expect(states.slice(0, 2)).toEqual(['read:done', 'extract:done'])
      expect([['upload:active', 'analyze:pending'], ['upload:done', 'analyze:active']]).toContainEqual(states.slice(2))
      await expect(panel.getByRole('progressbar')).toBeVisible()
      await expect(dlg.locator('[data-slot="mcert-progress-live"]')).toHaveText(/Sending to the server|Sunucuya gönderiliyor|The server is analysing|Sunucu analiz ediyor/)
      await expect(dlg.locator('[data-slot="mcert-analyze"]')).toBeDisabled()
      const cancel = dlg.locator('[data-slot="mcert-cancel-run"]')
      await expect(cancel).toBeEnabled()

      // Sığar: pencere / panel ekran dışına taşmaz, gövde yatay kaymaz; altlık aşama sürerken zıplamaz; dokunma ≥ 40 px
      const r = await page.evaluate(measure, '[role="dialog"]:has([data-slot="mcert-wizard"])')
      expect(r.offenders, `yükleme durumu @${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(r.pageOverflow, `yükleme durumu @${vp.name}: yatay kayma (px)`).toBeLessThanOrEqual(1)
      const pb = await panel.boundingBox()
      expect(pb.x, 'panel solda taşıyor').toBeGreaterThanOrEqual(-1)
      expect(pb.x + pb.width, 'panel sağda taşıyor').toBeLessThanOrEqual(vp.width + 1)
      const hScroll = await dlg.locator('[data-slot="modal-shell-body"]').first().evaluate((el) => el.scrollWidth - el.clientWidth)
      expect(hScroll, `yükleme durumu @${vp.name}: gövde yatay kayıyor (px)`).toBeLessThanOrEqual(1)
      const actions = dlg.locator('[data-slot="mcert-wizard-actions"]')
      const y1 = (await actions.boundingBox()).y
      const panelH1 = pb.height
      await page.waitForTimeout(2200)                                      // saniye sayacı işledi ("· 2 sn")
      await expect(panel.locator('[data-slot="mcert-progress-elapsed"]')).toBeVisible()
      expect(Math.abs((await actions.boundingBox()).y - y1), 'altlık zıplamaz').toBeLessThanOrEqual(1)
      expect(Math.abs((await panel.boundingBox()).height - panelH1), 'panel yüksekliği sabit').toBeLessThanOrEqual(1)
      if (vp.width < 1024) {
        const cb = await cancel.boundingBox()
        expect(cb.height, 'Vazgeç dokunma yüksekliği').toBeGreaterThanOrEqual(39)
      }

      // "Vazgeç": istek kesilir, panel kalkar, Dosya adımına temiz dönülür (düğme açık, meşgul değil)
      await cancel.click()
      await expect(panel).toHaveCount(0)
      await expect(dlg.locator('[data-slot="mcert-wizard"]')).toHaveAttribute('data-step', 'file')
      await expect(dlg.locator('[data-slot="mcert-analyze"]')).toBeEnabled()
      await expect(dlg.locator('[data-slot="mcert-analyze"]')).not.toHaveAttribute('aria-busy', 'true')
      await expect(dlg.locator('[data-slot="mcert-banner"]')).toContainText(/Analysis stopped|Analiz durduruldu/)
      await page.waitForTimeout(4500)                                      // kesilen isteğin geç yanıtı adımı değiştirmez
      await expect(dlg.locator('[data-slot="mcert-wizard"]')).toHaveAttribute('data-step', 'file')

      // İkinci deneme sonuna kadar: panel İnceleme'ye geçişte kalkar
      await dlg.locator('[data-slot="mcert-analyze"]').click()
      await expect(panel).toBeVisible()
      await dlg.locator('[data-slot="mcert-wizard"][data-step="review"]').waitFor({ timeout: 15_000 })
      await expect(panel).toHaveCount(0)
      expect(analyzeCalls, 'iki analiz isteği (biri kesildi)').toBe(2)
    })
  })
}
