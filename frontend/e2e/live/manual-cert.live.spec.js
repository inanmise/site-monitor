// CANLI: dosyadan sertifika takibi (20.110.0) — GERÇEK arayüz, GERÇEK yerel backend, route mock'u YOK. Mock'lu kapı
// (e2e/manual-cert.spec.js) yerleşimi ölçer; bu suite frontend ↔ backend SÖZLEŞMESİNİ uçtan uca sürer:
//   1. sayfa + rehber → chain-v1.pem → İnceleme (CN, zincir, EXPIRES_SOON) → YENİ kayıt (gerçek takım/grup/etiket seçicileri)
//      → Sonuç → sertifika penceresi: "Manuel" rozeti, SSL sekmesi yok, Sürümler v1;
//   2. Sürümler → yeni sürüm: önce YANLIŞ PFX şifresi (hata şifre alanının altında), sonra doğru → 2 sürüm, v2 güncel,
//      "Aynı anahtar";
//   3. aynı kayıt Pano'da (Manuel + "Yüklenen dosya"), Tüm Sertifikalar'da ve Envanter'de (Manuel + "Yüklenen dosya · sürüm 2");
//   4. sihirbazın olumsuz yolları gerçek yanıtlarla: CSR açıklama kartı, özel anahtar uyarısı, JKS yanlış şifre + "zaten
//      takipte" engeli, truststore.jks çoklu seçim → 2 kayıt (toplu);
//   5. 390×844: sayfa + sihirbaz adımları (Dosya → İnceleme → Takip) ekrana sığar, yatay kayma yok.
// TEMİZLİK: afterAll HER durumda (hata dahil) `e2e-live-*` kayıtlarını siler + kalıcı temizler ve kalmadığını doğrular.
// Sertifikalar SAHTE test CA'sından (*.example.test) — `bash e2e/live/make-cert-fixtures.sh <dizin>` + E2E_CERT_DIR.
import fs from 'node:fs'
import path from 'node:path'
import {
  test, expect, liveGate, openApp, apiGet, liveRequest, purgeLiveRecords, liveInventoryRows, waitPastHourlySweep,
  expectFits, expectDialogFits, expectCleanText, stopwatch, LIVE_PREFIX,
} from './fixtures.js'

const CERT_DIR = process.env.E2E_CERT_DIR || ''
const cert = (name) => path.join(CERT_DIR, name)
const PFX_PASS = 'Test1234'   // SAHTE test keystore'unun şifresi (make-cert-fixtures.sh) — gerçek bir sır değil
const RUN = `${LIVE_PREFIX}${Date.now().toString(36)}`
const KEY = `${RUN}.example.test`
const CA_KEYS = [`${RUN}-ca-1`, `${RUN}-ca-2`]
const CN = 'odeme-api.example.test'
const DESKTOP = { width: 1280, height: 800 }
const PHONE = { width: 390, height: 844 }

const WIZARD = '[role="dialog"]:has([data-slot="mcert-wizard"])'
const CERT_MODAL = '[role="dialog"]:has([data-slot="cert-modal-title"])'
const isPath = (p, method = 'POST') => (r) => new URL(r.url()).pathname === p && r.request().method() === method

/** Takip adımında kullanılacak gerçek veriler (yerel veritabanından): cert grubu olan bir takım, o grubun adı, bir etiket. */
const pick = { teamId: null, teamName: null, group: null, tag: null }

/** SearchableSelect (Popover + Command): tetikleyiciyi aç, adı tutan seçeneği seç. */
async function choose(page, trigger, label) {
  await trigger.click()
  const opt = page.getByRole('option').filter({ hasText: label }).first()
  await opt.waitFor({ timeout: 10_000 })
  await opt.click()
  await expect(trigger).toContainText(label)
}

/** Yeni kayıt alanları: takım → grup → etiket (gerçek seçiciler). */
async function fillTracking(page, dlg) {
  await choose(page, dlg.locator('[data-field="team_id"] [role="combobox"]'), pick.teamName)
  const group = dlg.locator('[data-field="group_name"] [role="combobox"]')
  await expect(group).toBeEnabled()
  if (pick.group) {
    await choose(page, group, pick.group)
  } else {
    // Takımın cert grubu yok: yeni ad yazılır (creatable)
    await group.click()
    await page.getByRole('combobox').last().fill('e2e-live')
    await page.getByRole('option').filter({ hasText: 'e2e-live' }).first().click()
  }
  const tagBox = dlg.locator('[data-field="tags"] input[type="text"]')
  await tagBox.fill(pick.tag || 'e2e-live')
  await tagBox.press('Enter')
  await expect(dlg.locator('[data-field="tags"]')).toContainText(pick.tag || 'e2e-live')
  // Öneri listesi (etiket zaten ekliyse açık kalır) altlığı örtmesin: pencere başlığına tıklayıp kapat
  await dlg.locator('[data-slot="mcert-wizard-title"]').click()
  await expect(page.locator('[data-radix-popper-content-wrapper] [role="option"]')).toHaveCount(0)
}

/** Sihirbazı aç (sayfa başlığındaki "Sertifika yükle"). */
async function openWizard(page) {
  await page.locator('[data-slot="mcert-upload"]').first().click()
  const dlg = page.locator(WIZARD)
  await dlg.locator('[data-slot="mcert-wizard"][data-step="file"]').waitFor()
  return dlg
}

/** Dosya seç (+ isteğe bağlı şifre) → Analiz et; analiz yanıtını döndürür. */
async function analyzeFile(page, dlg, file, password) {
  await dlg.locator('[data-slot="mcert-file-input"]').setInputFiles(cert(file))
  await expect(dlg.locator('[data-slot="mcert-file-chip"]')).toContainText(file)
  if (password != null) {
    const pw = dlg.locator('[data-slot="mcert-password"]')
    await pw.waitFor()
    await pw.fill(password)
  }
  const resp = page.waitForResponse(isPath('/api/manual-certs/analyze'))
  await dlg.locator('[data-slot="mcert-analyze"]').click()
  const r = await resp
  expect(r.status(), `analyze ${file}`).toBe(200)
  return r.json()
}

/** Sertifika penceresinde bir sekmeye geç (geniş ekranda sekme çubuğu, telefonda seçici). */
async function certTab(page, box, value) {
  const vw = page.viewportSize().width
  if (vw < 640) await box.locator('[data-slot="cert-modal-section-picker"]').selectOption(value)
  else await box.locator(`[role="tab"][id$="-trigger-${value}"]`).click()
}

test.describe.configure({ mode: 'serial' })

test.describe('manuel sertifika — canlı uçtan uca', () => {
  liveGate()
  test.skip(!CERT_DIR || !fs.existsSync(cert('chain-v1.pem')),
    'E2E_CERT_DIR yok ya da boş: önce `bash e2e/live/make-cert-fixtures.sh <dizin>` (depo dışında) ve E2E_CERT_DIR=<dizin>')

  test.beforeAll(async ({ playwright }) => {
    test.setTimeout(20 * 60_000)
    // Saat başına az kaldıysa saatlik tarama geçene dek bekle (20 günlük sertifika gerçek alarm açmasın)
    await waitPastHourlySweep(12)
    const req = await liveRequest(playwright)
    try {
      // Önceki koşudan kalan e2e-live-* kayıtları (yarıda kesilmiş koşu) — önce temizle
      const left = await purgeLiveRecords(req, (m) => console.log(`[live] ön temizlik: ${m}`))
      expect(left, 'önceki koşudan kalan e2e-live-* kayıtları temizlenemedi').toBe(0)
      // Gerçek veri: cert grubu olan ilk takım (yoksa ilk takım + yeni grup adı)
      const teams = await apiGet(req, '/api/admin/teams')
      const list = (Array.isArray(teams.json?.data) ? teams.json.data : []).filter((t) => t?.id != null && t?.name)
      expect(list.length, 'yerel veritabanında takım yok').toBeGreaterThan(0)
      for (const tm of list.slice(0, 25)) {
        const g = await apiGet(req, `/api/monitoring/groups?teamId=${tm.id}&type=cert`)
        const groups = (Array.isArray(g.json?.data) ? g.json.data : []).map((x) => x?.name).filter(Boolean)
        if (groups.length) { Object.assign(pick, { teamId: tm.id, teamName: tm.name, group: groups[0] }); break }
      }
      if (!pick.teamId) Object.assign(pick, { teamId: list[0].id, teamName: list[0].name, group: null })
      const tags = await apiGet(req, `/api/monitoring/tags?teamId=${pick.teamId}`)
      pick.tag = (Array.isArray(tags.json?.data) ? tags.json.data : []).map((x) => (typeof x === 'string' ? x : x?.name)).find(Boolean) || null
      console.log(`[live] takip alanları: takım="${pick.teamName}" grup="${pick.group ?? '(yeni: e2e-live)'}" etiket="${pick.tag ?? '(yeni: e2e-live)'}"`)
    } finally {
      await req.dispose()
    }
  })

  test.afterAll(async ({ playwright }) => {
    test.setTimeout(120_000)
    const req = await liveRequest(playwright)
    try {
      const left = await purgeLiveRecords(req, (m) => console.log(`[live] temizlik: ${m}`))
      const manual = await apiGet(req, '/api/manual-certs')
      const stray = (Array.isArray(manual.json?.data) ? manual.json.data : []).filter((r) => String(r.domain || '').startsWith(LIVE_PREFIX))
      console.log(`[live] temizlik sonrası kalan e2e-live-* kaydı: envanter=${left} manuel liste=${stray.length}`)
      expect(left, 'temizlik sonrası e2e-live-* envanter kaydı kaldı').toBe(0)
      expect(stray.length, 'temizlik sonrası manuel listede e2e-live-* kaldı').toBe(0)
    } finally {
      await req.dispose()
    }
  })

  test('1 · yükle → incele → yeni kayıt → sonuç → pencere (Manuel, SSL yok, Sürümler v1) @1280', async ({ page }) => {
    const done = stopwatch('1 yeni kayıt')
    await page.setViewportSize(DESKTOP)
    await openApp(page, '/?tab=manualcerts')
    await page.locator('[data-slot="manualcerts-page"]').waitFor()

    // Rehber varsayılan KAPALI; tetikleyiciyle açılır
    const guide = page.locator('[data-slot="mcert-guide"]')
    await expect(guide).toBeVisible()
    await expect(page.locator('[data-slot="mcert-guide-body"]')).toBeHidden()
    await guide.getByRole('button', { name: /Which file should I upload|Hangi dosyayı/ }).first().click()
    await expect(page.locator('[data-slot="mcert-guide-body"]')).toBeVisible()

    const dlg = await openWizard(page)
    const analysis = await analyzeFile(page, dlg, 'chain-v1.pem')
    expect(analysis?.data?.format, 'biçim').toBe('PEM')
    await dlg.locator('[data-slot="mcert-wizard"][data-step="review"]').waitFor()
    await expect(dlg.locator('[data-slot="mcert-format"]')).toHaveText('PEM')

    // Varsayılan seçili girdi = yaprak (CN), zincir tam, EXPIRES_SOON
    const leaf = dlg.locator('[data-slot="mcert-entry"][data-selected="true"]')
    await expect(leaf).toHaveCount(1)
    await expect(leaf).toContainText(CN)
    await expect(leaf.locator('[data-slot="mcert-chain-state"]')).toHaveAttribute('data-complete', 'true')
    await expect(leaf.locator('[data-slot="mcert-warning"][data-code="EXPIRES_SOON"]')).toBeVisible()
    await leaf.locator('[data-slot="mcert-chain"] button').first().click()
    await expect(leaf.locator('[data-slot="mcert-chain-link"]')).toHaveCount(2)
    await expect(leaf.locator('[data-slot="mcert-chain"]')).toContainText('Test Issuing CA')
    await expectCleanText(dlg.locator('[data-slot="mcert-wizard"]'), 'İnceleme adımı')

    await dlg.locator('[data-slot="mcert-next"]').click()
    await dlg.locator('[data-slot="mcert-wizard"][data-step="track"]').waitFor()
    await expect(dlg.locator('[data-slot="mcert-picked"]')).toContainText(CN)
    // Aynı konu adlı eski bir manuel kayıt varsa sihirbaz "yenile" önerir — burada açıkça YENİ kayıt
    await dlg.locator('[data-slot="mcert-mode"][data-mode="new"] [role="radio"]').click()
    await expect(dlg.locator('[data-slot="mcert-mode"][data-mode="new"]')).toHaveAttribute('data-selected', 'true')
    const key = dlg.locator('[data-slot="mcert-key"]')
    await expect(key, 'sunucunun önerdiği takip adı').toHaveValue(/odeme-api\.example\.test/)
    await key.fill(KEY)
    await fillTracking(page, dlg)

    const created = page.waitForResponse(isPath('/api/manual-certs'))
    await dlg.locator('[data-slot="mcert-submit"]').click()
    const cr = await created
    const body = await cr.json()
    expect(cr.status(), `oluştur: ${JSON.stringify(body?.errors || body?.error || '')}`).toBe(200)
    expect(body.data).toMatchObject({ domain: KEY, version: 1 })
    expect(body.data.inventory_id).toBeTruthy()

    await expect(dlg.locator('[data-slot="mcert-result"][data-kind="created"]')).toBeVisible()
    await expect(dlg.locator('[data-slot="mcert-result"]')).toContainText(KEY)
    await expectCleanText(dlg.locator('[data-slot="mcert-wizard"]'), 'Sonuç adımı')
    await dlg.locator('[data-slot="mcert-open-result"]').click()
    await expect(dlg).toHaveCount(0)

    const box = page.locator(CERT_MODAL)
    await box.waitFor()
    await expect(box.locator('[data-slot="cert-modal-title"]')).toContainText(KEY)
    await expect(box.locator('[data-slot="cert-modal-title"] [data-slot="manual-cert-badge"]')).toContainText('Manuel')
    await expect(box.locator('[role="tab"][id$="-trigger-ssl"]'), 'manuel kayıtta SSL sekmesi yok').toHaveCount(0)
    // Detaylar (manuel kayıt burada açılır): gerçek veriyle temiz metin
    await expect(box.locator('[role="tab"][id$="-trigger-details"]')).toHaveAttribute('data-state', 'active')
    await expect(box.locator('[role="tabpanel"][data-state="active"]')).toContainText(CN)
    await expectCleanText(box, 'Sertifika penceresi · Detaylar')
    // Sağlık: ağa özgü satırlar "Uygulanmaz — dosyadan yüklendi", sertifika satırları değerlendirilir
    await certTab(page, box, 'health')
    await box.locator('[data-slot="hlth-value"]').first().waitFor({ timeout: 20_000 })
    await expect(box.locator('[data-slot="hlth-value"]').filter({ hasText: 'Uygulanmaz — dosyadan yüklendi' }).first()).toBeVisible()
    await expectCleanText(box.locator('[role="tabpanel"][data-state="active"]'), 'Sertifika penceresi · Sağlık')
    await certTab(page, box, 'versions')
    await box.locator('[data-slot="mcert-version"][data-current="true"]').waitFor()
    await expect(box.locator('[data-slot="mcert-version"]')).toHaveCount(1)
    await expect(box.locator('[data-slot="mcert-version"][data-current="true"]')).toHaveAttribute('data-version', '1')
    await expectCleanText(box.locator('[data-slot="mcert-versions"]'), 'Sürümler')

    // Liste satırı (tablo) — kayıt sayfada görünür
    await box.getByRole('button', { name: /^(Kapat|Close)$/ }).first().click()
    await expect(page.locator(`[data-mcert-row="${KEY}"]`)).toBeVisible()
    done()
  })

  test('2 · Sürümler → yanlış şifre alanın altında → PFX v2 → 2 sürüm, v2 güncel, aynı anahtar @1280', async ({ page }) => {
    const done = stopwatch('2 yeni sürüm')
    await page.setViewportSize(DESKTOP)
    await openApp(page, `/?tab=manualcerts&mc_q=${encodeURIComponent(KEY)}`)
    const row = page.locator(`[data-mcert-row="${KEY}"]`)
    await row.waitFor()
    await row.locator('[data-slot="mcert-open"]').first().click()
    const box = page.locator(CERT_MODAL)
    await box.waitFor()
    await certTab(page, box, 'versions')
    await box.locator('[data-slot="mcert-renew-btn"]').click()

    const dlg = page.locator(WIZARD)
    await dlg.locator('[data-slot="mcert-wizard"][data-step="file"]').waitFor()
    // Yanlış şifre → sunucu password_error; hata ŞİFRE alanının altında, adım değişmez
    const bad = await analyzeFile(page, dlg, 'leaf-v2.pfx', 'yanlis-sifre')
    expect(bad?.data?.password_error, 'sunucu: password_error').toBe(true)
    await expect(dlg.locator('[data-slot="mcert-wizard"]')).toHaveAttribute('data-step', 'file')
    const pwField = dlg.locator('[data-field="password"]')
    await expect(pwField).toHaveAttribute('data-invalid', 'true')
    await expect(pwField).toContainText(/Şifre yanlış/)
    await expect(dlg.locator('[data-slot="mcert-password"]')).toHaveAttribute('aria-invalid', 'true')

    // Doğru şifre → İnceleme → Takip (kip sabit: yenile) → karşılaştırma → kaydet
    await dlg.locator('[data-slot="mcert-password"]').fill(PFX_PASS)
    const resp = page.waitForResponse(isPath('/api/manual-certs/analyze'))
    await dlg.locator('[data-slot="mcert-analyze"]').click()
    const good = await (await resp).json()
    expect(good?.data?.format).toBe('PKCS12')
    await dlg.locator('[data-slot="mcert-wizard"][data-step="review"]').waitFor()
    await expect(dlg.locator('[data-slot="mcert-entry"][data-selected="true"]')).toContainText(CN)
    await dlg.locator('[data-slot="mcert-next"]').click()
    await dlg.locator('[data-slot="mcert-wizard"][data-step="track"]').waitFor()
    await expect(dlg.locator('[data-slot="mcert-compare"]')).toBeVisible()
    await expect(dlg.locator('[data-slot="mcert-compare-row"][data-key="fp"]')).toHaveAttribute('data-changed', 'true')
    await expect(dlg.locator('[data-slot="mcert-compare-row"][data-key="notAfter"]')).toHaveAttribute('data-changed', 'true')
    await expectCleanText(dlg.locator('[data-slot="mcert-wizard"]'), 'Yenileme karşılaştırması')

    const renewed = page.waitForResponse((r) => /\/api\/manual-certs\/\d+\/versions$/.test(new URL(r.url()).pathname) && r.request().method() === 'POST')
    await dlg.locator('[data-slot="mcert-submit"]').click()
    const rr = await renewed
    const rb = await rr.json()
    expect(rr.status(), `yeni sürüm: ${JSON.stringify(rb?.error || rb?.code || '')}`).toBe(200)
    expect(rb.data).toMatchObject({ version: 2, previous_version: 1 })
    expect((rb.data.warnings || []).map((w) => w.code)).toContain('KEY_SAME')

    // Sonuç adımı görünür kalmalı (kaydın sürümleri arkada tazelenirken sihirbaz sıfırlanmamalı)
    await expect(dlg.locator('[data-slot="mcert-result"][data-kind="renewed"]')).toBeVisible()
    await expect(dlg.locator('[data-slot="mcert-wizard"]')).toHaveAttribute('data-step', 'result')
    await dlg.locator('[data-slot="mcert-wizard-actions"]').getByRole('button').last().click()
    await expect(dlg).toHaveCount(0)

    await expect(box.locator('[data-slot="mcert-version"]')).toHaveCount(2)
    const cur = box.locator('[data-slot="mcert-version"][data-current="true"]')
    await expect(cur).toHaveAttribute('data-version', '2')
    await expect(cur.locator('[data-slot="mcert-key-same"]')).toBeVisible()
    await expect(box.locator('[data-slot="mcert-version"][data-current="false"]')).toHaveAttribute('data-version', '1')
    await expect(box.locator('[data-slot="cert-modal-title"] [data-slot="manual-cert-badge"]')).toBeVisible()
    await expectCleanText(box.locator('[data-slot="mcert-versions"]'), 'Sürümler (2)')

    // Eski sürümün PEM'i (yalnız AÇIK zincir) indirilebilir: <takip adı>-v1.pem, özel anahtar yok
    const dl = page.waitForEvent('download')
    await box.locator('[data-slot="mcert-version"][data-version="1"] [data-slot="mcert-pem"]').click()
    const file = await dl
    expect(file.suggestedFilename()).toBe(`${KEY}-v1.pem`)
    const pem = fs.readFileSync(await file.path(), 'utf8')
    expect(pem).toContain('-----BEGIN CERTIFICATE-----')
    expect(pem).not.toContain('PRIVATE KEY')
    done()
  })

  test('3 · aynı kayıt Pano, Tüm Sertifikalar ve Envanter\'de (Manuel + Yüklenen dosya) @1280', async ({ page }) => {
    const done = stopwatch('3 yüzeyler')
    await page.setViewportSize(DESKTOP)
    // Pano kartı
    await openApp(page, `/?q=${encodeURIComponent(KEY)}`)
    const card = page.locator(`[data-slot="card"][data-domain="${KEY}"]`)
    await card.waitFor({ timeout: 30_000 })
    await expect(card.locator('[data-slot="manual-cert-badge"]')).toContainText('Manuel')
    await expect(card.locator('[data-slot="cert-via"][data-via="upload"]')).toContainText('Yüklenen dosya')
    await expectCleanText(card, 'Pano kartı')
    // "Yeniden değerlendir" = ağsız değerlendirme (GET /api/check/{alan}: via=upload); yeni alarm açmaz, bildirim yok
    const evalResp = page.waitForResponse((r) => new URL(r.url()).pathname === `/api/check/${KEY}`)
    await card.getByRole('button', { name: `${KEY} — Yeniden değerlendir` }).click()
    const ev = await evalResp
    const evBody = await ev.json()
    expect(ev.status()).toBe(200)
    expect(evBody?.data?.via ?? evBody?.via, 'manuel kayıt ağsız değerlendirilir').toBe('upload')
    await expect(page.getByText(`${KEY} kontrol edildi`).first()).toBeVisible()
    await expect(card.locator('[data-slot="manual-cert-badge"]')).toBeVisible()

    // Tüm Sertifikalar tablosu
    await openApp(page, `/?tab=all&c_q=${encodeURIComponent(KEY)}`)
    const trow = page.locator(`tr[data-domain="${KEY}"]`)
    await trow.waitFor({ timeout: 30_000 })
    await expect(trow.locator('[data-slot="manual-cert-badge"]')).toContainText('Manuel')

    // Envanter satırı + ayrıntı çekmecesi (kaynak: Yüklenen dosya · sürüm 2)
    await openApp(page, `/?tab=domains&i_q=${encodeURIComponent(KEY)}`)
    const irow = page.locator(`[data-inv-row="${KEY}"]`).first()
    await irow.waitFor({ timeout: 30_000 })
    await expect(irow.locator('[data-slot="manual-cert-badge"]')).toContainText('Manuel')
    await irow.click()
    const drawer = page.getByRole('dialog').filter({ hasText: KEY }).first()
    await drawer.waitFor()
    await expect(drawer.locator('[data-slot="inv-summary"] [data-slot="manual-cert-badge"]')).toContainText('Manuel')
    await expect(drawer).toContainText('Yüklenen dosya · sürüm 2')

    // Envanter formu (manuel kip: takip adı kilitli, ağ alanları yok) → açıklama değiştir → Kaydet (PUT) → kayıt manuel kalır
    await drawer.locator('[data-slot="inv-drawer-actions"]').getByRole('button', { name: `${KEY} — Düzenle` }).click()
    const form = page.locator('[role="dialog"]:has([data-slot="inv-form-actions"])')
    await form.locator('[data-slot="inv-form-manual"]').waitFor()
    await expect(form.getByRole('button', { name: /^Test et$/ }), 'manuel kayıtta ağ testi yok').toHaveCount(0)
    const desc = form.getByLabel('Açıklama', { exact: true })
    await desc.fill('e2e canlı test — envanter formundan düzenlendi')
    const put = page.waitForResponse((r) => /\/api\/admin\/inventory\/\d+$/.test(new URL(r.url()).pathname) && r.request().method() === 'PUT')
    await form.locator('[data-slot="inv-form-actions"]').getByRole('button', { name: 'Kaydet', exact: true }).click()
    const pr = await put
    const pb = await pr.json()
    expect(pr.status(), `envanter kaydet: ${JSON.stringify(pb?.errors || pb?.error || '')}`).toBe(200)
    await expect(form).toHaveCount(0)
    const after = (await liveInventoryRows(page.request)).find((r) => r.domain === KEY)
    expect(after?.description).toBe('e2e canlı test — envanter formundan düzenlendi')
    expect(after?.cert_source, 'düzenleme sonrası kaynak').toBe('MANUAL')
    expect(after?.manual_version, 'düzenleme sürümü değiştirmez').toBe(2)
    done()
  })

  test('4 · olumsuz yollar: CSR, özel anahtar, JKS yanlış şifre + zaten takipte, eski sürüm onayı, KEY_EXISTS (tekil + toplu), truststore toplu → 2 kayıt @1280', async ({ page }) => {
    const done = stopwatch('4 olumsuz yollar + toplu')
    await page.setViewportSize(DESKTOP)
    // Giriş noktası: Envanter → "Ekle ▾" → "Dosyadan sertifika ekle" → Manuel Sertifikalar sayfası + sihirbaz açık
    await openApp(page, '/?tab=domains')
    await page.locator('[data-slot="add-cert-more"]').click()
    await page.locator('[data-slot="add-cert-file"]').click()
    await page.locator('[data-slot="manualcerts-page"]').waitFor()
    const dlg = page.locator(WIZARD)
    await dlg.locator('[data-slot="mcert-wizard"][data-step="file"]').waitFor()
    await expect(page).toHaveURL(/tab=manualcerts/)
    await expect(page, 'mc_upload isteği tüketildi (yenilemede sihirbaz yeniden açılmaz)').not.toHaveURL(/mc_upload/)
    const back = async () => {
      await dlg.locator('[data-slot="mcert-wizard-actions"]').getByRole('button').first().click()   // Geri
      await dlg.locator('[data-slot="mcert-wizard"][data-step="file"]').waitFor()
    }

    // a) CSR: açıklama kartı (sertifika değil), girdi yok, İleri kapalı
    const csr = await analyzeFile(page, dlg, 'leaf.csr')
    expect((csr.data.warnings || []).map((w) => w.code)).toContain('CSR_NOT_CERTIFICATE')
    await dlg.locator('[data-slot="mcert-wizard"][data-step="review"]').waitFor()
    await expect(dlg.locator('[data-slot="mcert-csr"]')).toContainText('Bu bir CSR')
    await expect(dlg.locator('[data-slot="mcert-csr"]')).toContainText(CN)
    await expect(dlg.locator('[data-slot="mcert-entry"]')).toHaveCount(0)
    await expect(dlg.locator('[data-slot="mcert-next"]')).toBeDisabled()
    await back()

    // b) PEM + özel anahtar: "özel anahtar yok sayıldı" uyarısı; anahtar yanıtta yok
    const wk = await analyzeFile(page, dlg, 'with-key.pem')
    expect(JSON.stringify(wk)).not.toContain('PRIVATE KEY')
    await dlg.locator('[data-slot="mcert-wizard"][data-step="review"]').waitFor()
    await expect(dlg.locator('[data-slot="mcert-warning"][data-code="PRIVATE_KEY_IGNORED"]')).toBeVisible()
    await expect(dlg.locator('[data-slot="mcert-warning"][data-code="PRIVATE_KEY_IGNORED"]')).toContainText('özel anahtar')
    await expectCleanText(dlg.locator('[data-slot="mcert-wizard"]'), 'İnceleme · özel anahtar')
    await back()

    // c) JKS + YANLIŞ şifre: sertifikalar yine okunur (PASSWORD_WRONG uyarısı + "Şifreyi düzelt"); güncel sürümle aynı
    //    sertifika → "zaten takipte" kartı; Takip adımında yeni kayıt engellenir
    await dlg.locator('[data-slot="mcert-file-input"]').setInputFiles(cert('leaf-v2.jks'))
    const pw = dlg.locator('[data-slot="mcert-password"]')
    await pw.waitFor()
    await pw.fill('yanlis-sifre')
    const jr = page.waitForResponse(isPath('/api/manual-certs/analyze'))
    await dlg.locator('[data-slot="mcert-analyze"]').click()
    const jks = await (await jr).json()
    expect(jks.data.format).toBe('JKS')
    expect((jks.data.warnings || []).map((w) => w.code)).toContain('PASSWORD_WRONG')
    await dlg.locator('[data-slot="mcert-wizard"][data-step="review"]').waitFor()
    await expect(dlg.locator('[data-slot="mcert-warning"][data-code="PASSWORD_WRONG"]')).toBeVisible()
    await expect(dlg.locator('[data-slot="mcert-fix-password"]')).toBeVisible()
    const tracked = dlg.locator('[data-slot="mcert-entry"][data-selected="true"] [data-slot="mcert-match"][data-kind="tracked"]')
    await expect(tracked).toContainText('Bu sertifika zaten takipte')
    await expect(tracked).toContainText(KEY)
    await expectCleanText(dlg.locator('[data-slot="mcert-wizard"]'), 'İnceleme · JKS yanlış şifre')
    await dlg.locator('[data-slot="mcert-next"]').click()
    await dlg.locator('[data-slot="mcert-wizard"][data-step="track"]').waitFor()
    await dlg.locator('[data-slot="mcert-mode"][data-mode="new"] [role="radio"]').click()
    await expect(dlg.locator('[data-slot="mcert-submit"]'), 'zaten takipteki sertifika yeni kayıt olamaz').toBeDisabled()
    await dlg.locator('[data-slot="mcert-wizard-actions"]').getByRole('button').first().click()   // İnceleme
    await dlg.locator('[data-slot="mcert-wizard"][data-step="review"]').waitFor()
    await back()

    // c2) eski sürüm (v1, DER): "Bu bir yenileme mi?" → Takip (kip: yenile, hedef KEY) → daha eski bitiş onayı ister
    //     (onaysız kayıt İSTEK ATMAZ, hata onay kutusunun altında); kip "yeni" + mevcut takip adı → 409 KEY_EXISTS →
    //     hata TAKİP ADI alanının altında (sunucu `field:"domain"`)
    await analyzeFile(page, dlg, 'leaf-v1.der')
    await dlg.locator('[data-slot="mcert-wizard"][data-step="review"]').waitFor()
    await expect(dlg.locator('[data-slot="mcert-match"][data-kind="same-subject"]')).toContainText(KEY)
    await dlg.locator('[data-slot="mcert-next"]').click()
    await dlg.locator('[data-slot="mcert-wizard"][data-step="track"]').waitFor()
    await expect(dlg.locator('[data-slot="mcert-mode"][data-mode="renew"]')).toHaveAttribute('data-selected', 'true')
    await expect(dlg.locator('[data-slot="mcert-compare-row"][data-key="notAfter"]')).toBeVisible()
    const confirm = dlg.locator('[data-field="confirm"]')
    await expect(confirm).toContainText('Yüklenen sertifika daha eski')
    let renewCalls = 0
    const countRenew = (r) => { if (/\/api\/manual-certs\/\d+\/versions$/.test(new URL(r.url()).pathname)) renewCalls++ }
    page.on('request', countRenew)
    await dlg.locator('[data-slot="mcert-submit"]').click()
    await expect(confirm).toContainText('Devam etmek için daha eski bitişli sürümü onaylayın.')
    await page.waitForTimeout(300)
    page.off('request', countRenew)
    expect(renewCalls, 'onaysız eski sürüm sunucuya gönderilmez').toBe(0)
    await dlg.locator('[data-slot="mcert-mode"][data-mode="new"] [role="radio"]').click()
    await dlg.locator('[data-slot="mcert-key"]').fill(KEY)
    await fillTracking(page, dlg)
    const clash = page.waitForResponse(isPath('/api/manual-certs'))
    await dlg.locator('[data-slot="mcert-submit"]').click()
    const kr = await clash
    const kb = await kr.json()
    expect(kr.status(), 'mevcut takip adı → 409').toBe(409)
    expect(kb).toMatchObject({ code: 'KEY_EXISTS', field: 'domain' })
    await expect(dlg.locator('[data-field="domain"]')).toHaveAttribute('data-invalid', 'true')
    await expect(dlg.locator('[data-field="domain"]')).toContainText('Bu takip adı zaten kullanılıyor')
    await expect(dlg.locator('[data-slot="mcert-wizard"]')).toHaveAttribute('data-step', 'track')
    await dlg.locator('[data-slot="mcert-wizard-actions"]').getByRole('button').first().click()   // İnceleme
    await dlg.locator('[data-slot="mcert-wizard"][data-step="review"]').waitFor()
    await back()

    // d) truststore.jks: iki CA → "birden çok seç" → iki takip adı → toplu oluştur (hep ya da hiç)
    const ts = await analyzeFile(page, dlg, 'truststore.jks', PFX_PASS)
    expect(ts.data.entries.length, 'truststore girdileri').toBe(2)
    await dlg.locator('[data-slot="mcert-wizard"][data-step="review"]').waitFor()
    await expect(dlg.locator('[data-slot="mcert-entry"]')).toHaveCount(2)
    await dlg.getByRole('button', { name: /^Birden çok/ }).click()
    const boxes = dlg.locator('[data-slot="mcert-entry"] [role="checkbox"]')
    await expect(boxes).toHaveCount(2)
    for (const b of await boxes.all()) {
      if ((await b.getAttribute('aria-checked')) !== 'true') await b.click()
    }
    await expect(dlg.locator('[data-slot="mcert-entry"][data-selected="true"]')).toHaveCount(2)
    await dlg.locator('[data-slot="mcert-next"]').click()
    await dlg.locator('[data-slot="mcert-wizard"][data-step="track"]').waitFor()
    const keys = dlg.locator('[data-slot="mcert-batch-key"]')
    await expect(keys).toHaveCount(2)
    // önce 1. satırda MEVCUT takip adı → 409 KEY_EXISTS → hata o satırın altında, hiçbir kayıt oluşmaz (hep ya da hiç)
    await keys.nth(0).fill(KEY)
    await keys.nth(1).fill(CA_KEYS[1])
    await fillTracking(page, dlg)
    await expect(dlg.locator('[data-slot="mcert-submit"]')).toContainText('2')
    const bclash = page.waitForResponse(isPath('/api/manual-certs/batch'))
    await dlg.locator('[data-slot="mcert-submit"]').click()
    const bcr = await bclash
    expect(bcr.status(), 'toplu: mevcut takip adı → 409').toBe(409)
    expect(await bcr.json()).toMatchObject({ code: 'KEY_EXISTS' })
    await expect(dlg.locator('[data-field="item_0"]')).toContainText('Bu takip adı zaten kullanılıyor')
    expect((await liveInventoryRows(page.request)).map((r) => r.domain), 'hep ya da hiç: 409 sonrası CA kaydı yok').toEqual([KEY])
    await keys.nth(0).fill(CA_KEYS[0])
    await expect(dlg.locator('[data-field="item_0"]')).not.toContainText('Bu takip adı zaten kullanılıyor')
    const br = page.waitForResponse(isPath('/api/manual-certs/batch'))
    await dlg.locator('[data-slot="mcert-submit"]').click()
    const bres = await br
    const bb = await bres.json()
    expect(bres.status(), `toplu: ${JSON.stringify(bb?.errors || bb?.error || '')}`).toBe(200)
    expect((bb.data.created || []).map((c) => c.domain).sort()).toEqual([...CA_KEYS].sort())
    await expect(dlg.locator('[data-slot="mcert-result"][data-kind="batch"]')).toBeVisible()
    await dlg.getByRole('button', { name: /^(Kapat|Close)$/ }).first().click()
    await expect(dlg).toHaveCount(0)
    for (const k of CA_KEYS) await expect(page.locator(`[data-mcert-row="${k}"]`)).toBeVisible()
    done()
  })

  test('5 · telefon 390×844: sayfa + sihirbaz adımları ekrana sığar (gerçek veriyle)', async ({ page }) => {
    const done = stopwatch('5 telefon')
    await page.setViewportSize(PHONE)
    await openApp(page, '/?tab=manualcerts')
    await page.locator('[data-slot="manualcerts-page"]').waitFor()
    await page.locator(`[data-mcert-row="${KEY}"]`).first().waitFor()
    await expect(page.locator('[data-slot="mcert-list"]')).toHaveAttribute('data-view', 'cards')
    const guide = page.locator('[data-slot="mcert-guide"]')
    await guide.getByRole('button', { name: /Which file should I upload|Hangi dosyayı/ }).first().click()
    await expect(page.locator('[data-slot="mcert-guide-body"]')).toBeVisible()
    await expectFits(page, '.app-main', 'sayfa @390')

    const dlg = await openWizard(page)
    await expectDialogFits(page, dlg, PHONE, 'sihirbaz dosya @390')
    await analyzeFile(page, dlg, 'chain-v1.p7b')
    await dlg.locator('[data-slot="mcert-wizard"][data-step="review"]').waitFor()
    await dlg.locator('[data-slot="mcert-entry"][data-selected="true"] [data-slot="mcert-chain"] button').first().click()
    await expectDialogFits(page, dlg, PHONE, 'sihirbaz inceleme @390')
    await dlg.locator('[data-slot="mcert-next"]').click()
    await dlg.locator('[data-slot="mcert-wizard"][data-step="track"]').waitFor()
    await page.waitForTimeout(500)   // yenileme kipi: güncel sürüm + karşılaştırma yüklensin
    await expectDialogFits(page, dlg, PHONE, 'sihirbaz takip @390')
    const box = await dlg.boundingBox()
    expect(Math.round(box.width), 'telefonda tam ekran').toBe(PHONE.width)
    // Kaydetmeden kapat (yalnız yerleşim)
    await dlg.getByRole('button', { name: /^(Kapat|Close)$/ }).first().click()
    await expect(dlg).toHaveCount(0)

    // Sertifika penceresi → Sürümler (gerçek 2 sürüm: uzun DN'ler, parmak izleri) telefonda sığar
    await page.locator(`[data-mcert-row="${KEY}"] [data-slot="mcert-open"]`).first().click()
    const certBox = page.locator(CERT_MODAL)
    await certBox.waitFor()
    await certTab(page, certBox, 'versions')
    await expect(certBox.locator('[data-slot="mcert-version"]')).toHaveCount(2)
    await expectDialogFits(page, certBox, PHONE, 'Sürümler @390')
    done()
  })

  test('6 · temizlik öncesi doğrulama: kayıtlar sunucuda (3 adet e2e-live-*)', async ({ page }) => {
    const rows = await liveInventoryRows(page.request)
    expect(rows.map((r) => r.domain).sort()).toEqual([KEY, ...CA_KEYS].sort())
    const list = await apiGet(page.request, '/api/manual-certs')
    const mine = (list.json?.data || []).find((r) => r.domain === KEY)
    expect(mine?.current_version?.version).toBe(2)
    expect(mine?.versions_count).toBe(2)
    // Yükleme / yeni sürüm / yeniden değerlendirme yalnız toparlanma yapar: açık alarm YOK
    const alerts = await apiGet(page.request, `/api/history/${encodeURIComponent(KEY)}/alerts`)
    const open = (Array.isArray(alerts.json?.data) ? alerts.json.data : Array.isArray(alerts.json) ? alerts.json : [])
      .filter((a) => !a.resolved && !a.resolved_at)
    expect(open.length, 'manuel kayıt için açılmış alarm').toBe(0)
  })
})
