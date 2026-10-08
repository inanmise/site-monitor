// CANLI: dosyadan sertifika takibi (20.110.0) — GERÇEK arayüz, GERÇEK yerel backend, route mock'u YOK. Mock'lu kapı
// (e2e/manual-cert.spec.js) yerleşimi ölçer; bu suite frontend ↔ backend SÖZLEŞMESİNİ uçtan uca sürer:
//   1. sayfa + rehber → chain-v1.pem → İnceleme: yaprak + ara + kök TEK girdi (2026-10-07), zincir SSL sekmesiyle aynı 3 kart
//      (yaprak → ara → kök), EXPIRES_SOON → YENİ kayıt (gerçek takım/grup/etiket seçicileri) → Sonuç → sertifika penceresi:
//      "Manuel" rozeti, SSL sekmesi AÇILIŞ sekmesi (çevrim-dışı önizleme, 3 zincir kartı, bağlantı grubu yok), Sürümler v1;
//   2. Sürümler → yeni sürüm: önce YANLIŞ PFX şifresi (TARAYICIDA anlaşılır — sunucuya istek YOK; hata şifre alanının
//      altında), sonra doğru → 2 sürüm, v2 güncel, "Aynı anahtar"; 2026-10-08: gerçek PFX analizinde ve kayıtta yükleme
//      durumu paneli (Dosya okunuyor → ayıklanıyor → gönderiliyor → sunucu / kaydediliyor) göründü, yalnız ileri gitti, bitti;
//   3. aynı kayıt Pano'da (Manuel + "Yüklenen dosya"), Tüm Sertifikalar'da ve Envanter'de (Manuel + "Yüklenen dosya · sürüm 2");
//   3b. takip adı sonradan değişir (listeden Düzenle);
//   3c. güncel sürümün dosyası yeniden → "zaten güncel sürüm" uyarısı → "Yine de yükle" → v3 (aynı parmak izi, "Aynı sertifika
//      yeniden yüklendi"); eski v1 Sürümler'den KALICI silinir → 2 sürüm (v3 güncel, v2);
//   4. sihirbazın olumsuz yolları gerçek yanıtlarla: CSR açıklama kartı, özel anahtar uyarısı, JKS yanlış şifre + "zaten
//      takipte" engeli, truststore.jks (ara + kök) TEK girdi, roots.jks (iki bağımsız kök) çoklu seçim → 2 kayıt (toplu);
//   5. 390×844: sayfa + sihirbaz adımları (Dosya → İnceleme → Takip) ve pencerenin SSL / Sürümler sekmeleri ekrana sığar.
// ÖZEL ANAHTAR TARAYICIDAN ÇIKMAZ (2026-10-08, kullanıcı isteği): her testte TÜM yükleme istekleri (analiz, oluştur, toplu,
//   yeni sürüm) yakalanır (`watchUploads`); hiçbirinin gövdesinde "PRIVATE KEY", PFX/JKS dosya baytları, with-key.pem'in
//   anahtar satırı, şifre, `file` / `text` / `password` alanı yoktur — yalnız `extracted`. PFX / JKS / with-key.pem
//   uçtan uca çalışmaya devam eder.
// TEMİZLİK: afterAll HER durumda (hata dahil) `e2e-live-*` kayıtlarını siler + kalıcı temizler ve kalmadığını doğrular.
// Sertifikalar SAHTE test CA'sından (*.example.test) — `bash e2e/live/make-cert-fixtures.sh <dizin>` + E2E_CERT_DIR.
import fs from 'node:fs'
import path from 'node:path'
import {
  test, expect, liveGate, openApp, apiGet, apiCall, liveRequest, purgeLiveRecords, liveInventoryRows, waitPastHourlySweep,
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

/**
 * Dosya + YANLIŞ / eksik şifre → hata TARAYICIDA (2026-10-08): şifre alanının altında, adım değişmez ve sunucuya HİÇ
 * yükleme isteği gitmez.
 */
async function analyzeExpectPasswordError(page, dlg, file, password, re) {
  await dlg.locator('[data-slot="mcert-file-input"]').setInputFiles(cert(file))
  await expect(dlg.locator('[data-slot="mcert-file-chip"]')).toContainText(file)
  const pw = dlg.locator('[data-slot="mcert-password"]')
  await pw.waitFor()
  await pw.fill(password)
  let sent = 0
  const count = (r) => { if (r.method() === 'POST' && new URL(r.url()).pathname.startsWith('/api/manual-certs')) sent++ }
  page.on('request', count)
  await dlg.locator('[data-slot="mcert-analyze"]').click()
  const pwField = dlg.locator('[data-field="password"]')
  await expect(pwField).toHaveAttribute('data-invalid', 'true')
  await expect(pwField).toContainText(re)
  await expect(dlg.locator('[data-slot="mcert-password"]')).toHaveAttribute('aria-invalid', 'true')
  await expect(dlg.locator('[data-slot="mcert-wizard"]')).toHaveAttribute('data-step', 'file')
  await page.waitForTimeout(300)
  page.off('request', count)
  expect(sent, `${file}: yanlış şifrede sunucuya yükleme isteği gitmez`).toBe(0)
}

/** Sunucuya ASLA gitmemesi gereken baytlar: anahtar depolarının ortasından 48 bayt + with-key.pem'in anahtar satırı. */
function secretNeedles() {
  const out = []
  for (const f of ['leaf-v2.pfx', 'leaf-v2.jks', 'truststore.jks', 'roots.jks']) {
    const b = fs.readFileSync(cert(f))
    const mid = Math.floor(b.length / 2)
    out.push({ label: `${f} baytları`, buf: b.subarray(mid, mid + 48) })
  }
  const keyText = fs.readFileSync(cert('with-key.pem'), 'utf8').split(/-----BEGIN [A-Z ]*PRIVATE KEY-----/)[1] || ''
  const keyLine = keyText.split(/\r?\n/).map((l) => l.trim()).find((l) => l.length >= 40)
  if (keyLine) out.push({ label: 'with-key.pem anahtar satırı', buf: Buffer.from(keyLine) })
  return out
}

/**
 * Sayfanın TÜM manuel sertifika yükleme isteklerini (analiz / oluştur / toplu / yeni sürüm) yakalar. `check()`: her gövde
 * yalnız `extracted` taşır; "PRIVATE KEY", şifre, anahtar deposu baytları, ham `file` / `text` / `password` alanı YOK.
 * Döner: denetlenen istek sayısı.
 *
 * <p>Gövde `page.route` ile yakalanır: Chromium, Blob içeren multipart gövdeyi `page.on('request')` olayında GÖSTERMEZ
 * (`postDataBuffer()` null → denetim boşa çalışırdı). İstek değiştirilmeden gerçek backend'e iletilir.
 */
async function watchUploads(page) {
  const bodies = []
  const pattern = '**/api/manual-certs**'
  const handler = async (route) => {
    const r = route.request()
    const p = new URL(r.url()).pathname
    if (r.method() === 'POST' && /^\/api\/manual-certs(\/analyze|\/batch|\/\d+\/versions)?$/.test(p)) {
      bodies.push({ path: p, body: r.postDataBuffer() || Buffer.alloc(0) })
    }
    await route.continue()
  }
  await page.route(pattern, handler)
  return {
    async check(label) {
      await page.unroute(pattern, handler)
      expect(bodies.every((b) => b.body.length > 0), `${label}: yakalanan gövde boş (denetim boşa çalışmasın)`).toBe(true)
      const needles = secretNeedles()
      for (const { path: p, body } of bodies) {
        const text = body.toString('latin1')
        expect(text, `${label} ${p}: extracted alanı`).toContain('name="extracted"')
        for (const bad of ['PRIVATE KEY', PFX_PASS, 'name="password"', 'name="file"', 'name="text"']) {
          expect(text, `${label} ${p}: "${bad}" gövdede olmamalı`).not.toContain(bad)
        }
        for (const n of needles) expect(body.indexOf(n.buf), `${label} ${p}: ${n.label} gövdede olmamalı`).toBe(-1)
      }
      return bodies.length
    },
  }
}

/**
 * YÜKLEME DURUMU kaydı (2026-10-08): sihirbazın ilerleme panelindeki aşama durumlarını (ve ayrıntı satırını) her DOM
 * değişiminde anlık görüntü olarak toplar — gerçek koşu hızlı olduğu için ara durumlar beklemeyle yakalanamaz.
 * `take()`: [{ stages: 'read:done,extract:active,…', detail }] (ardışık aynılar tek) + canlı bölgenin son metni.
 */
async function recordProgress(page) {
  await page.evaluate(() => {
    const log = []
    const snap = () => {
      const p = document.querySelector('[data-slot="mcert-progress"]')
      if (!p || p.dataset.status !== 'running') return          // önceki başarısız koşunun özeti sayılmaz
      const stages =[...p.querySelectorAll('[data-slot="mcert-progress-stage"]')].map((e) => `${e.dataset.stage}:${e.dataset.state}`).join(',')
      const detail = p.querySelector('[data-slot="mcert-progress-detail"]')?.textContent || ''
      const last = log[log.length - 1]
      if (!last || last.stages !== stages || last.detail !== detail) log.push({ kind: p.dataset.kind, stages, detail })
    }
    const mo = new MutationObserver(snap)
    mo.observe(document.body, { subtree: true, childList: true, attributes: true, characterData: true, attributeFilter: ['data-state'] })
    window.__mcertProgress = { log, stop: () => mo.disconnect() }
  })
  return {
    async take() {
      return page.evaluate(() => {
        window.__mcertProgress?.stop()
        return {
          log: window.__mcertProgress?.log || [],
          live: document.querySelector('[data-slot="mcert-progress-live"]')?.textContent || '',
          panel: !!document.querySelector('[data-slot="mcert-progress"]'),
        }
      })
    },
  }
}

/** Aşamalar YALNIZ ileri gider (bekliyor → sürüyor → tamam) — kayıttaki her görüntü bir öncekinin gerisine düşmez. */
function expectForwardOnly(log, label) {
  const rank = { pending: 0, active: 1, done: 2, error: 2 }
  const prev = {}
  for (const { stages } of log) {
    for (const part of stages.split(',')) {
      const [s, st] = part.split(':')
      expect(rank[st] ?? -1, `${label}: ${s} geri gitti (${stages})`).toBeGreaterThanOrEqual(prev[s] ?? 0)
      prev[s] = rank[st]
    }
  }
}

/** Zincir görünümündeki kartların rolleri (SslChainView: leaf | intermediate | root | root-store). */
const chainRoles = (chain) => chain.locator('[data-slot="ssl-chain-node"]').evaluateAll((els) => els.map((e) => e.getAttribute('data-role')))

/** Katlanır bölümlerin (SAN listesi, teknik ayrıntılar) hepsini açar — her tıklamada liste yeniden sayılır. */
async function expandAll(container) {
  const closed = container.locator('button[aria-expanded="false"]')
  for (let i = 0; i < 12 && (await closed.count()) > 0; i++) await closed.first().click()
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

  test('1 · yükle → incele (tek girdi, 3 kartlı zincir) → yeni kayıt → sonuç → pencere (Manuel, SSL açılış sekmesi + zincir, Sürümler v1) @1280', async ({ page }) => {
    const done = stopwatch('1 yeni kayıt')
    const uploads = await watchUploads(page)
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
    // 2026-10-07: yaprak + ara + kök TEK takip girdisi (ara ve kök yaprağın zincirinde); önizleme SSL sekmesi biçiminde
    expect(analysis?.data?.entries?.length, 'zincir başına tek girdi').toBe(1)
    expect(analysis?.data?.certificate_count, 'dosyadaki tekil sertifika').toBe(3)
    expect(analysis?.data?.entries?.[0]?.preview?.via, 'girdi önizlemesi (çevrim-dışı)').toBe('upload')
    await dlg.locator('[data-slot="mcert-wizard"][data-step="review"]').waitFor()
    await expect(dlg.locator('[data-slot="mcert-format"]')).toHaveText('PEM')
    await expect(dlg.locator('[data-slot="mcert-entry"]')).toHaveCount(1)
    await expect(dlg.locator('[data-slot="mcert-grouped"]')).toContainText('3 sertifika')
    await expect(dlg.getByRole('button', { name: /^Birden çok/ }), 'tek zincir: "birden çok" anahtarı yok').toHaveCount(0)

    // Varsayılan seçili girdi = yaprak (CN), zincir tam, EXPIRES_SOON; zincir ağ sertifikasının SSL sekmesiyle AYNI kartlar
    const leaf = dlg.locator('[data-slot="mcert-entry"][data-selected="true"]')
    await expect(leaf).toHaveCount(1)
    await expect(leaf).toContainText(CN)
    await expect(leaf.locator('[data-slot="mcert-chain-state"]')).toHaveAttribute('data-complete', 'true')
    await expect(leaf.locator('[data-slot="mcert-warning"][data-code="EXPIRES_SOON"]')).toBeVisible()
    const wizChain = leaf.locator('[data-slot="mcert-chain"] [data-slot="ssl-chain"]')
    expect(await chainRoles(wizChain), 'İnceleme: yaprak → ara → kök').toEqual(['leaf', 'intermediate', 'root'])
    await expect(wizChain.locator('[data-slot="ssl-chain-node"][data-role="intermediate"]')).toContainText('Test Issuing CA')
    await expect(wizChain.locator('[data-slot="ssl-chain-node"][data-role="root"]')).toContainText('Test Root CA')
    await expandAll(wizChain)
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
    // SSL sekmesi (2026-10-07): manuelde de AÇILIŞ sekmesi — çevrim-dışı önizleme, ağ sertifikasıyla aynı zincir kartları,
    // bağlantı grubu / alan adı eşleşmesi yok, canlı kontrol dili yok
    await expect(box.locator('[role="tab"][id$="-trigger-ssl"]')).toHaveAttribute('data-state', 'active')
    const ssl = box.locator('[data-slot="ssl-panel"][data-source="upload"]')
    await ssl.waitFor({ timeout: 20_000 })
    expect(await chainRoles(ssl.locator('[data-slot="ssl-chain"]')), 'SSL sekmesi: yaprak → ara → kök').toEqual(['leaf', 'intermediate', 'root'])
    await expect(ssl.locator('[data-slot="ssl-check-group"][data-group="conn"]')).toHaveCount(0)
    await expect(ssl.locator('[data-slot="ssl-check"][data-check="hostname"]')).toHaveCount(0)
    await expect(ssl).not.toContainText('Canlı kontrol')
    await expect(ssl.locator('[data-slot="ssl-verdict"]').getByRole('button', { name: 'Yeniden değerlendir' })).toBeVisible()
    // İptal (2026-10-08): fikstürde OCSP/CRL adresi YOK → bilgi satırı, "denetlenemedi" sayılmaz, yanıltıcı "ulaşılamadı" yok
    await expect(ssl.locator('[data-slot="ssl-check"][data-check="revocation"]')).toContainText('Sertifikada OCSP/CRL adresi yok')
    await expect(ssl).not.toContainText('denetlenemedi')
    await expect(ssl).not.toContainText('OCSP/CRL hizmetine ulaşılamadı')
    await expandAll(ssl.locator('[data-slot="ssl-chain"]'))
    await expectCleanText(ssl, 'Sertifika penceresi · SSL (manuel)')
    // Hiyerarşi görünümü (2026-10-07): tarayıcı gibi kök → ara → yaprak — GERÇEK sürüm zinciri ucundan
    // (`/api/manual-certs/{id}/versions/{vid}/chain`, önizlemenin inventory_id + manual_version_id'si); yaprağı seçince SAN
    const viewSwitch = ssl.getByRole('group', { name: 'Zincir görünümü' })
    const chainResp = page.waitForResponse((r) => /\/api\/manual-certs\/\d+\/versions\/\d+\/chain$/.test(new URL(r.url()).pathname))
    await viewSwitch.getByRole('button', { name: 'Hiyerarşi (tarayıcı gibi)' }).click()
    expect((await chainResp).status(), 'sürüm zinciri ucu').toBe(200)
    const hier = ssl.locator('[data-slot="cert-hierarchy"]')
    await hier.waitFor({ timeout: 20_000 })
    const hNodes = hier.getByRole('treeitem')
    expect(await hNodes.evaluateAll((els) => els.map((e) => e.getAttribute('data-role'))), 'hiyerarşi: kök → ara → yaprak')
      .toEqual(['root', 'intermediate', 'leaf'])
    await expect(hNodes.nth(0)).toContainText('Test Root CA')
    await expect(hNodes.nth(1)).toContainText('Test Issuing CA')
    await expect(hNodes.nth(2)).toContainText(CN)
    const hDetails = hier.locator('[data-slot="cert-hierarchy-details"]')
    await hNodes.nth(0).click()
    await expect(hDetails).toHaveAttribute('data-role', 'root')
    await hNodes.nth(2).click()
    await expect(hNodes.nth(2)).toHaveAttribute('aria-selected', 'true')
    await expect(hDetails).toHaveAttribute('data-role', 'leaf')
    await expect(hDetails.locator('[data-slot="cert-hierarchy-san"]')).toContainText('odeme-api-internal.example.test')
    await expectCleanText(hier, 'Sertifika penceresi · SSL (hiyerarşi)')
    // Sonraki adımlar ağ tarzı zinciri bekler — görünüm geri alınır (seçim pencere oturumunda hatırlanır)
    await viewSwitch.getByRole('button', { name: 'Zincir', exact: true }).click()
    await expect(ssl.locator('[data-slot="ssl-chain"]')).toBeVisible()
    // Detaylar: gerçek veriyle temiz metin
    await certTab(page, box, 'details')
    await expect(box.locator('[role="tabpanel"][data-state="active"]')).toContainText(CN)
    await expectCleanText(box, 'Sertifika penceresi · Detaylar')
    // Adres alanları boşken de görünür (2026-10-08, kullanıcı: "boş da olsa ekleyelim, boş olduğunu bilelim")
    for (const slot of ['ocsp', 'crl']) {
      await expect(box.locator(`[data-slot="cert-detail-field"][data-field="${slot}"]`)).toContainText('Sertifikada tanımlı değil')
    }
    await expect(box.locator('[data-slot="cert-detail-field"][data-field="revocation"]')).toContainText('İptal adresi yok')
    // Sağlık: ağa özgü satırlar "Uygulanmaz — dosyadan yüklendi", sertifika satırları değerlendirilir
    await certTab(page, box, 'health')
    await box.locator('[data-slot="hlth-value"]').first().waitFor({ timeout: 20_000 })
    await expect(box.locator('[data-slot="hlth-value"]').filter({ hasText: 'Uygulanmaz — dosyadan yüklendi' }).first()).toBeVisible()
    // İptal satırı: adres yok → "Adres yok — denetlenemez" (NA), "erişimi kontrol edin" önerisi YOK; açılınca iki adres alanı
    const revHead = box.locator('[data-slot="hlth-row-head"]').filter({ hasText: 'Sertifika iptal edilmemiş' })
    await expect(revHead).toContainText('Adres yok — denetlenemez')
    await expect(revHead).toContainText('İşlem gerekmez')
    await expect(box).not.toContainText('OCSP/CRL adresine ulaşılamadı')
    await revHead.click()
    await expect(box.locator('[data-slot="hlth-ev-empty"]')).toHaveCount(2)
    await expect(box.locator('[data-slot="hlth-ev-reason"]')).toHaveAttribute('data-reason', 'NO_ENDPOINTS')
    await expect(box.locator('[data-slot="hlth-ev-reason"]')).toContainText('OCSP ya da CRL adresi yayımlamıyor')
    await expectCleanText(box.locator('[role="tabpanel"][data-state="active"]'), 'Sertifika penceresi · Sağlık')
    await certTab(page, box, 'versions')
    await box.locator('[data-slot="mcert-version"][data-current="true"]').waitFor()
    await expect(box.locator('[data-slot="mcert-version"]')).toHaveCount(1)
    await expect(box.locator('[data-slot="mcert-version"][data-current="true"]')).toHaveAttribute('data-version', '1')
    await expectCleanText(box.locator('[data-slot="mcert-versions"]'), 'Sürümler')

    // Liste satırı (tablo) — kayıt sayfada görünür
    await box.getByRole('button', { name: /^(Kapat|Close)$/ }).first().click()
    await expect(page.locator(`[data-mcert-row="${KEY}"]`)).toBeVisible()
    expect(await uploads.check('1'), 'analiz + oluştur denetlendi').toBe(2)
    done()
  })

  test('2 · Sürümler → yanlış şifre alanın altında → PFX v2 → 2 sürüm, v2 güncel, aynı anahtar @1280', async ({ page }) => {
    const done = stopwatch('2 yeni sürüm')
    const uploads = await watchUploads(page)
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
    // Yanlış şifre → PKCS#12 MAC'i TARAYICIDA tutmaz; hata ŞİFRE alanının altında, adım değişmez, sunucuya istek YOK
    await analyzeExpectPasswordError(page, dlg, 'leaf-v2.pfx', 'yanlis-sifre', /Şifre yanlış/)

    // Yanlış şifrede yükleme durumu paneli "ayıklama" aşamasında durduğunu söyler (hata alanın altında)
    await expect(dlg.locator('[data-slot="mcert-progress"]')).toHaveAttribute('data-status', 'failed')
    await expect(dlg.locator('[data-slot="mcert-progress-stage"][data-stage="extract"]')).toHaveAttribute('data-state', 'error')

    // Doğru şifre → (tarayıcıda açılır, yalnız açık sertifikalar gider) İnceleme → Takip (kip sabit: yenile) → kaydet
    await dlg.locator('[data-slot="mcert-password"]').fill(PFX_PASS)
    const progress = await recordProgress(page)
    const resp = page.waitForResponse(isPath('/api/manual-certs/analyze'))
    await dlg.locator('[data-slot="mcert-analyze"]').click()
    const good = await (await resp).json()
    expect(good?.data?.format).toBe('PKCS12')
    expect((good?.data?.warnings || []).find((w) => w.code === 'PRIVATE_KEY_KEPT_LOCAL')?.params?.count, 'anahtar tarayıcıda kaldı').toBe(1)
    await dlg.locator('[data-slot="mcert-wizard"][data-step="review"]').waitFor()
    // YÜKLEME DURUMU (2026-10-08): gerçek PFX koşusunda aşamalar göründü, yalnız ileri gitti ve bitti (panel kalktı)
    const ap = await progress.take()
    expect(ap.log.length, 'yükleme durumu paneli göründü').toBeGreaterThan(0)
    expect(ap.log[0].kind).toBe('analyze')
    expect(ap.log[0].stages.split(',').map((s) => s.split(':')[0])).toEqual(['read', 'extract', 'upload', 'analyze'])
    expectForwardOnly(ap.log, 'analiz')
    expect(ap.log.some((e) => /extract:active/.test(e.stages)), 'ayıklama aşaması görüldü').toBe(true)
    expect(ap.log.some((e) => /upload:active|analyze:active/.test(e.stages)), 'gönderim / sunucu aşaması görüldü').toBe(true)
    expect(ap.panel, 'koşu bitince panel kalkar').toBe(false)
    expect(ap.live).toBe('Tamamlandı.')
    await expect(dlg.locator('[data-slot="mcert-kept-local"]')).toHaveAttribute('data-keys', '1')
    await expect(dlg.locator('[data-slot="mcert-kept-local"]')).toHaveAttribute('data-password', 'used')
    await expect(dlg.locator('[data-slot="mcert-kept-local"]')).toContainText('Parola da yalnız tarayıcıda kullanıldı')
    await expect(dlg.locator('[data-slot="mcert-entry"][data-selected="true"]')).toContainText(CN)
    await dlg.locator('[data-slot="mcert-next"]').click()
    await dlg.locator('[data-slot="mcert-wizard"][data-step="track"]').waitFor()
    await expect(dlg.locator('[data-slot="mcert-compare"]')).toBeVisible()
    await expect(dlg.locator('[data-slot="mcert-compare-row"][data-key="fp"]')).toHaveAttribute('data-changed', 'true')
    await expect(dlg.locator('[data-slot="mcert-compare-row"][data-key="notAfter"]')).toHaveAttribute('data-changed', 'true')
    await expectCleanText(dlg.locator('[data-slot="mcert-wizard"]'), 'Yenileme karşılaştırması')

    const saveProgress = await recordProgress(page)
    const renewed = page.waitForResponse((r) => /\/api\/manual-certs\/\d+\/versions$/.test(new URL(r.url()).pathname) && r.request().method() === 'POST')
    await dlg.locator('[data-slot="mcert-submit"]').click()
    const rr = await renewed
    const rb = await rr.json()
    expect(rr.status(), `yeni sürüm: ${JSON.stringify(rb?.error || rb?.code || '')}`).toBe(200)
    expect(rb.data).toMatchObject({ version: 2, previous_version: 1 })
    expect((rb.data.warnings || []).map((w) => w.code)).toContain('KEY_SAME')

    // Sonuç adımı görünür kalmalı (kaydın sürümleri arkada tazelenirken sihirbaz sıfırlanmamalı)
    await expect(dlg.locator('[data-slot="mcert-result"][data-kind="renewed"]')).toBeVisible()
    // Kayıt koşusu: "Sunucuya gönderiliyor" → "Yeni sürüm kaydediliyor"; bitti, panel kalktı
    const sp = await saveProgress.take()
    expect(sp.log.length, 'kayıt koşusunda panel göründü').toBeGreaterThan(0)
    expect(sp.log[0].kind).toBe('save')
    expect(sp.log[0].stages.split(',').map((s) => s.split(':')[0])).toEqual(['upload', 'save'])
    expectForwardOnly(sp.log, 'kayıt')
    expect(sp.panel).toBe(false)
    expect(sp.live).toBe('Tamamlandı.')
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
    expect(await uploads.check('2'), 'analiz + yeni sürüm denetlendi (yanlış şifrede istek yok)').toBe(2)
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

  test('3b · takip adı sonradan değişir: listeden Düzenle → geçersiz ad alanın altında → yeni ad + onay → sürümler korunur → eski ada dönülür @1280', async ({ page }) => {
    const done = stopwatch('3b yeniden adlandırma')
    await page.setViewportSize(DESKTOP)
    const NEW_KEY = `${RUN}-yeni-ad.example.test`
    await openApp(page, `/?tab=manualcerts&mc_q=${encodeURIComponent(KEY)}`)
    const row = page.locator(`[data-mcert-row="${KEY}"]`)
    await row.waitFor()
    await row.getByRole('button', { name: `${KEY} — İşlemler` }).first().click()
    await page.getByRole('menuitem', { name: /Düzenle \(takip adı/ }).click()
    const form = page.locator('[role="dialog"]:has([data-slot="inv-form-actions"])')
    await form.locator('[data-slot="inv-form-manual"]').waitFor()
    const key = form.getByLabel(/^Takip adı/)
    await expect(key).toHaveValue(KEY)
    await expect(key).toBeEditable()
    // Geçersiz ad: istemci kuralı, alanın altında; istek gitmez
    let puts = 0
    const countPut = (r) => { if (/\/api\/admin\/inventory\/\d+$/.test(new URL(r.url()).pathname) && r.method() === 'PUT') puts++ }
    page.on('request', countPut)
    await key.fill('Yanlis Ad')
    await form.locator('[data-slot="inv-form-actions"]').getByRole('button', { name: 'Kaydet', exact: true }).click()
    await expect(key).toHaveAttribute('aria-invalid', 'true')
    expect(puts, 'geçersiz adla istek gitmemeli').toBe(0)
    page.off('request', countPut)
    // Geçerli yeni ad → onay ("Takip adı değiştirilsin mi?") → PUT 200
    await key.fill(NEW_KEY)
    const put = page.waitForResponse((r) => /\/api\/admin\/inventory\/\d+$/.test(new URL(r.url()).pathname) && r.request().method() === 'PUT')
    await form.locator('[data-slot="inv-form-actions"]').getByRole('button', { name: 'Kaydet', exact: true }).click()
    // Onay penceresi (ui/Dialog confirm → role=dialog; yalnız alert tipi alertdialog) — başlığından bulunur
    const confirm = page.getByRole('dialog').filter({ hasText: 'Takip adı değiştirilsin mi?' })
    await expect(confirm).toContainText('Takip adı değiştirilsin mi?')
    await expect(confirm).toContainText(NEW_KEY)
    await confirm.getByRole('button', { name: 'Adı değiştir' }).click()
    const pr = await put
    expect(pr.status(), `yeniden adlandır: ${JSON.stringify((await pr.json())?.error || '')}`).toBe(200)
    await expect(form).toHaveCount(0)
    // Liste yeni adla; sürümler (2) ve güncel sürüm korunur
    await openApp(page, `/?tab=manualcerts&mc_q=${encodeURIComponent(NEW_KEY)}`)
    await expect(page.locator(`[data-mcert-row="${NEW_KEY}"]`)).toBeVisible()
    const list = await apiGet(page.request, '/api/manual-certs')
    const mine = (list.json?.data || []).find((r) => r.domain === NEW_KEY)
    expect(mine?.current_version?.version, 'yeniden adlandırma sürümü değiştirmez').toBe(2)
    expect(mine?.versions_count).toBe(2)
    expect((list.json?.data || []).some((r) => r.domain === KEY), 'eski ad listede kalmamalı').toBe(false)
    // Sonraki adımlar KEY'e bağlı — API ile eski ada geri dön (aynı uç, aynı kural)
    const rec = await apiGet(page.request, `/api/admin/inventory/by-domain?domain=${encodeURIComponent(NEW_KEY)}`)
    const back = await apiCall(page.request, 'PUT', `/api/admin/inventory/${rec.json?.data?.id}`, { ...rec.json?.data, domain: KEY })
    expect(back.status, 'eski ada dönüş').toBe(200)
    done()
  })

  test('3c · aynı sertifika "Yine de yükle" → v3 (aynı parmak izi) → eski v1 kalıcı silinir → 2 sürüm, güncel v3 @1280', async ({ page }) => {
    const done = stopwatch('3c yine de yükle + sürüm sil')
    const uploads = await watchUploads(page)
    await page.setViewportSize(DESKTOP)
    const mineNow = async () => ((await apiGet(page.request, '/api/manual-certs')).json?.data || []).find((r) => r.domain === KEY)
    const before = await mineNow()
    expect(before?.current_version?.version, 'başlangıç: v2 güncel').toBe(2)
    const fpV2 = before?.current_version?.fingerprint
    expect(fpV2).toBeTruthy()

    await openApp(page, `/?tab=manualcerts&mc_q=${encodeURIComponent(KEY)}`)
    const row = page.locator(`[data-mcert-row="${KEY}"]`)
    await row.waitFor()
    await row.locator('[data-slot="mcert-open"]').first().click()
    const box = page.locator(CERT_MODAL)
    await box.waitFor()
    await certTab(page, box, 'versions')
    await box.locator('[data-slot="mcert-version"][data-current="true"]').waitFor()
    await expect(box.locator('[data-slot="mcert-version"]')).toHaveCount(2)
    await box.locator('[data-slot="mcert-renew-btn"]').click()

    // Güncel sürümün AYNISI (leaf-v2.pfx): engel değil uyarı — "Yine de yükle"
    const dlg = page.locator(WIZARD)
    await dlg.locator('[data-slot="mcert-wizard"][data-step="file"]').waitFor()
    await analyzeFile(page, dlg, 'leaf-v2.pfx', PFX_PASS)
    await dlg.locator('[data-slot="mcert-wizard"][data-step="review"]').waitFor()
    await dlg.locator('[data-slot="mcert-next"]').click()
    await dlg.locator('[data-slot="mcert-wizard"][data-step="track"]').waitFor()
    const same = dlg.locator('[data-slot="mcert-same"]')
    await expect(same).toContainText('Bu sertifika zaten güncel sürüm')
    await expect(same).toContainText('Yine de yükle')
    await expect(dlg.locator('[data-slot="mcert-submit"]'), 'aynı sertifikada normal kaydet kapalı').toBeDisabled()
    await expectCleanText(dlg.locator('[data-slot="mcert-wizard"]'), 'Takip · aynı sertifika uyarısı')
    const renewed = page.waitForResponse((r) => /\/api\/manual-certs\/\d+\/versions$/.test(new URL(r.url()).pathname) && r.request().method() === 'POST')
    await same.locator('[data-slot="mcert-upload-anyway"]').click()
    const rr = await renewed
    const rb = await rr.json()
    expect(rr.status(), `yine de yükle: ${JSON.stringify(rb?.error || rb?.code || '')}`).toBe(200)
    expect(rb.data).toMatchObject({ version: 3, previous_version: 2, same_certificate: true })
    const result = dlg.locator('[data-slot="mcert-result"][data-same="true"]')
    await expect(result).toContainText('Aynı sertifika yeni sürüm olarak kaydedildi (sürüm 3)')
    await expect(result).toContainText('bitiş tarihi değişmedi')
    await dlg.locator('[data-slot="mcert-wizard-actions"]').getByRole('button').last().click()
    await expect(dlg).toHaveCount(0)

    await expect(box.locator('[data-slot="mcert-version"]')).toHaveCount(3)
    const cur = box.locator('[data-slot="mcert-version"][data-current="true"]')
    await expect(cur).toHaveAttribute('data-version', '3')
    await expect(cur.locator('[data-slot="mcert-same-reupload"]')).toContainText('Aynı sertifika yeniden yüklendi')
    const mid = await mineNow()
    expect(mid?.current_version?.fingerprint, 'yeni sürüm aynı parmak izi').toBe(fpV2)
    expect(mid?.versions_count).toBe(3)

    // Eski v1 KALICI silinir: "Sil" yalnız eski sürümlerde; onay → DELETE → sayı düşer, güncel v3 aynen kalır
    await expect(cur.locator('[data-slot="mcert-version-delete"]'), 'güncel sürümde Sil yok').toHaveCount(0)
    await expect(box.locator('[data-slot="mcert-version-delete"]')).toHaveCount(2)
    const deleted = page.waitForResponse((r) => /\/api\/manual-certs\/\d+\/versions\/\d+$/.test(new URL(r.url()).pathname) && r.request().method() === 'DELETE')
    await box.locator('[data-slot="mcert-version"][data-version="1"] [data-slot="mcert-version-delete"]').click()
    const confirm = page.getByRole('dialog').filter({ hasText: 'Sürüm v1 silinsin mi?' })
    await expect(confirm).toContainText('kalıcı olarak silinecek; geri alınamaz')
    await confirm.getByRole('button', { name: 'Kalıcı olarak sil' }).click()
    const dr = await deleted
    const db = await dr.json()
    expect(dr.status(), `sürüm sil: ${JSON.stringify(db?.error || db?.code || '')}`).toBe(200)
    expect(db.data).toMatchObject({ deleted_version: 1, versions_count: 2 })
    await expect(box.locator('[data-slot="mcert-version"]')).toHaveCount(2)
    await expect(box.locator('[data-slot="mcert-version"][data-version="1"]')).toHaveCount(0)
    await expect(cur).toHaveAttribute('data-version', '3')
    await expect(page.getByText('Sürüm v1 silindi').first()).toBeVisible()
    await expectCleanText(box.locator('[data-slot="mcert-versions"]'), 'Sürümler (v1 silindi)')
    const after = await mineNow()
    expect(after?.current_version?.version, 'güncel sürüm değişmedi').toBe(3)
    expect(after?.current_version?.fingerprint).toBe(fpV2)
    expect(after?.versions_count).toBe(2)
    expect(await uploads.check('3c'), 'analiz + yine de yükle denetlendi').toBe(2)
    done()
  })

  test('3d · takip adı PENCEREDEN değişir: pencere yeni adla sürer (sekme korunur, Sağlık / Kontrol geçmişi hatasız, eski adla istek yok), liste yenilemeden yeni adı gösterir @1280', async ({ page }) => {
    // 2026-10-08 (kullanıcı: "takip adı değiştirince Sağlık bilgisi yüklenemedi … 404", "Kontrol geçmişi yüklenemedi / Domain
    // envanterde bulunamadı … 404", "Manuel Sertifikalar sayfasında takip adı değişikliği hemen görülmüyor"). 3b listeden
    // düzenleyip sayfayı YENİDEN açıyordu — açık pencere ve açık liste yolunu hiç görmüyordu.
    const done = stopwatch('3d pencereden yeniden adlandırma')
    await page.setViewportSize(DESKTOP)
    const NEW_KEY = `${RUN}-pencere-ad.example.test`
    await openApp(page, `/?tab=manualcerts&mc_q=${encodeURIComponent(RUN)}`)
    const row = page.locator(`[data-mcert-row="${KEY}"]`)
    await row.waitFor()
    await row.locator('[data-slot="mcert-open"]').first().click()
    const box = page.locator(CERT_MODAL)
    await box.waitFor()
    await certTab(page, box, 'health')
    const healthTab = box.locator('[role="tab"][id$="-trigger-health"]')
    await expect(healthTab).toHaveAttribute('aria-selected', 'true')

    // PUT yanıtından SONRA eski adla giden her API isteği ve her 4xx/5xx API yanıtı kaydedilir (olay sırası korunur)
    const oldEnc = encodeURIComponent(KEY)
    const isPut = (r) => /\/api\/admin\/inventory\/\d+$/.test(new URL(r.url()).pathname) && r.request().method() === 'PUT'
    let renamed = false
    const stale = []
    const failed = []
    const onReq = (r) => {
      const u = new URL(r.url())
      if (renamed && u.pathname.startsWith('/api/') && (u.pathname.includes(`/${oldEnc}`) || u.search.includes(`=${oldEnc}`))) stale.push(`${r.method()} ${u.pathname}${u.search}`)
    }
    const onResp = (r) => {
      if (isPut(r)) { renamed = true; return }
      const u = new URL(r.url())
      if (renamed && u.pathname.startsWith('/api/') && r.status() >= 400) failed.push(`${r.status()} ${u.pathname}`)
    }
    page.on('request', onReq)
    page.on('response', onResp)

    await box.getByRole('button', { name: 'Düzenle', exact: true }).first().click()
    const form = page.locator('[role="dialog"]:has([data-slot="inv-form-actions"])')
    await form.locator('[data-slot="inv-form-manual"]').waitFor()
    const key = form.getByLabel(/^Takip adı/)
    await expect(key).toHaveValue(KEY)
    await key.fill(NEW_KEY)
    const put = page.waitForResponse(isPut)
    const newHealth = page.waitForResponse((r) => new URL(r.url()).pathname === `/api/certificates/${encodeURIComponent(NEW_KEY)}/health`)
    await form.locator('[data-slot="inv-form-actions"]').getByRole('button', { name: 'Kaydet', exact: true }).click()
    const confirm = page.getByRole('dialog').filter({ hasText: 'Takip adı değiştirilsin mi?' })
    await confirm.getByRole('button', { name: 'Adı değiştir' }).click()
    const pr = await put
    expect(pr.status(), `yeniden adlandır: ${JSON.stringify((await pr.json())?.error || '')}`).toBe(200)
    await expect(form).toHaveCount(0)

    // Pencere açık kalır: başlık YENİ ad, kullanıcı Sağlık sekmesinde kalır, sağlık YENİ adla 200, hata yok
    await expect(box.locator('[data-slot="cert-modal-title"]')).toContainText(NEW_KEY)
    await expect(box.locator('[data-slot="cert-modal-title"]')).not.toContainText(KEY)
    await expect(healthTab).toHaveAttribute('aria-selected', 'true')
    expect((await newHealth).status(), 'sağlık yeni adla').toBe(200)
    await expect(box).not.toContainText('Sağlık bilgisi yüklenemedi')

    // Kontrol geçmişi YENİ adla 200, hata yok
    const newHist = page.waitForResponse((r) => new URL(r.url()).pathname === `/api/monitoring/uptime/${encodeURIComponent(NEW_KEY)}/ssl-history`)
    await certTab(page, box, 'history')
    expect((await newHist).status(), 'kontrol geçmişi yeni adla').toBe(200)
    await expect(box.locator('[data-slot="check-history"]')).toBeVisible()
    await expect(box).not.toContainText('Kontrol geçmişi yüklenemedi')
    await expect(box).not.toContainText('Domain envanterde bulunamadı')

    // Pencere kapanır: liste sayfa YENİLENMEDEN yeni adı gösterir, eski ad yok
    await page.keyboard.press('Escape')
    await expect(box).toHaveCount(0)
    await expect(page.locator(`[data-mcert-row="${NEW_KEY}"]`)).toBeVisible()
    await expect(page.locator(`[data-mcert-row="${KEY}"]`)).toHaveCount(0)

    page.off('request', onReq)
    page.off('response', onResp)
    expect(stale, 'yeniden adlandırmadan sonra eski adla API isteği gitmemeli').toEqual([])
    expect(failed, 'yeniden adlandırmadan sonra başarısız API yanıtı olmamalı').toEqual([])

    // Sonraki adımlar KEY'e bağlı — API ile eski ada geri dön (3b ile aynı)
    const rec = await apiGet(page.request, `/api/admin/inventory/by-domain?domain=${encodeURIComponent(NEW_KEY)}`)
    const back = await apiCall(page.request, 'PUT', `/api/admin/inventory/${rec.json?.data?.id}`, { ...rec.json?.data, domain: KEY })
    expect(back.status, 'eski ada dönüş').toBe(200)
    done()
  })

  test('4 · olumsuz yollar: CSR, özel anahtar, JKS yanlış şifre + zaten takipte, eski sürüm onayı, KEY_EXISTS (tekil + toplu), truststore tek girdi, iki bağımsız kök toplu → 2 kayıt @1280', async ({ page }) => {
    const done = stopwatch('4 olumsuz yollar + toplu')
    const uploads = await watchUploads(page)
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

    // b) PEM + özel anahtar: anahtar TARAYICIDA ayıklanır (sunucu yalnız sayıyı bilir: PRIVATE_KEY_KEPT_LOCAL 1);
    //    İnceleme'de "Özel anahtar (1) tarayıcınızda ayıklandı; sunucuya gönderilmedi." notu; anahtar yanıtta da yok
    const wk = await analyzeFile(page, dlg, 'with-key.pem')
    expect(JSON.stringify(wk)).not.toContain('PRIVATE KEY')
    expect((wk.data.warnings || []).find((w) => w.code === 'PRIVATE_KEY_KEPT_LOCAL')?.params?.count).toBe(1)
    await dlg.locator('[data-slot="mcert-wizard"][data-step="review"]').waitFor()
    const kept = dlg.locator('[data-slot="mcert-kept-local"]')
    await expect(kept).toHaveAttribute('data-keys', '1')
    await expect(kept).toContainText('Özel anahtar (1) tarayıcınızda ayıklandı; sunucuya gönderilmedi.')
    await expect(dlg.locator('[data-slot="mcert-warning"][data-code="PRIVATE_KEY_KEPT_LOCAL"]'), 'aynı bilgi listede tekrarlanmaz').toHaveCount(0)
    await expectCleanText(dlg.locator('[data-slot="mcert-wizard"]'), 'İnceleme · özel anahtar')
    await back()
    await expect(dlg.locator('[data-slot="mcert-kept-local"]'), 'Dosya adımına dönünce de not görünür').toHaveAttribute('data-keys', '1')

    // c) JKS + YANLIŞ şifre: sertifikalar parolasız okunur; bütünlük denetimi TARAYICIDA tutmaz → PASSWORD_WRONG notu +
    //    "Şifreyi düzelt"; sunucuya şifre gitmez. Güncel sürümle aynı sertifika → "zaten takipte"; yeni kayıt engellenir
    await dlg.locator('[data-slot="mcert-file-input"]').setInputFiles(cert('leaf-v2.jks'))
    const pw = dlg.locator('[data-slot="mcert-password"]')
    await pw.waitFor()
    await pw.fill('yanlis-sifre')
    const jr = page.waitForResponse(isPath('/api/manual-certs/analyze'))
    await dlg.locator('[data-slot="mcert-analyze"]').click()
    const jks = await (await jr).json()
    expect(jks.data.format).toBe('JKS')
    expect((jks.data.warnings || []).map((w) => w.code), 'sunucu şifreyi bilmez — bütünlük notu tarayıcının').not.toContain('PASSWORD_WRONG')
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

    // d) truststore.jks (ara CA + onu imzalayan kök): 2026-10-07 → TEK girdi (ara), kök onun zincirinde; "birden çok" yok
    const ts = await analyzeFile(page, dlg, 'truststore.jks', PFX_PASS)
    expect(ts.data.entries.length, 'truststore: ara + kök tek zincir').toBe(1)
    expect(ts.data.certificate_count).toBe(2)
    await dlg.locator('[data-slot="mcert-wizard"][data-step="review"]').waitFor()
    await expect(dlg.locator('[data-slot="mcert-entry"]')).toHaveCount(1)
    await expect(dlg.locator('[data-slot="mcert-entry"]')).toContainText('Test Issuing CA')
    expect(await chainRoles(dlg.locator('[data-slot="mcert-entry"] [data-slot="ssl-chain"]')), 'ara → kök').toEqual(['intermediate', 'root'])
    await expect(dlg.getByRole('button', { name: /^Birden çok/ })).toHaveCount(0)
    await back()

    // e) roots.jks: iki BAĞIMSIZ kök → iki girdi → "birden çok seç" → iki takip adı → toplu oluştur (hep ya da hiç)
    const rs = await analyzeFile(page, dlg, 'roots.jks', PFX_PASS)
    expect(rs.data.entries.length, 'iki bağımsız kök').toBe(2)
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
    expect(await uploads.check('4'), 'olumsuz yolların yükleme istekleri denetlendi').toBeGreaterThanOrEqual(9)
    done()
  })

  test('5 · telefon 390×844: sayfa + sihirbaz adımları ekrana sığar (gerçek veriyle)', async ({ page }) => {
    const done = stopwatch('5 telefon')
    const uploads = await watchUploads(page)
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
    // Zincir kartları (yaprak → ara → kök) en geniş hâliyle: SAN + teknik ayrıntılar açık
    const phoneChain = dlg.locator('[data-slot="mcert-entry"][data-selected="true"] [data-slot="mcert-chain"]')
    expect(await chainRoles(phoneChain)).toEqual(['leaf', 'intermediate', 'root'])
    await expandAll(phoneChain)
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
    expect(await uploads.check('5'), 'P7B analizi denetlendi').toBe(1)

    // Sertifika penceresi → SSL (açılış sekmesi, zincir kartları açık) ve Sürümler (gerçek 2 sürüm: v3 güncel + v2;
    // uzun DN'ler, parmak izleri, "Sil") telefonda sığar
    await page.locator(`[data-mcert-row="${KEY}"] [data-slot="mcert-open"]`).first().click()
    const certBox = page.locator(CERT_MODAL)
    await certBox.waitFor()
    const ssl = certBox.locator('[data-slot="ssl-panel"][data-source="upload"]')
    await ssl.waitFor({ timeout: 20_000 })
    expect(await chainRoles(ssl.locator('[data-slot="ssl-chain"]'))).toEqual(['leaf', 'intermediate', 'root'])
    await expandAll(ssl.locator('[data-slot="ssl-chain"]'))
    await expectDialogFits(page, certBox, PHONE, 'SSL (manuel) @390')
    await certTab(page, certBox, 'versions')
    await expect(certBox.locator('[data-slot="mcert-version"]')).toHaveCount(2)
    await expect(certBox.locator('[data-slot="mcert-version-delete"]')).toHaveCount(1)
    await expectDialogFits(page, certBox, PHONE, 'Sürümler @390')
    done()
  })

  test('6 · temizlik öncesi doğrulama: kayıtlar sunucuda (3 adet e2e-live-*)', async ({ page }) => {
    const rows = await liveInventoryRows(page.request)
    expect(rows.map((r) => r.domain).sort()).toEqual([KEY, ...CA_KEYS].sort())
    const list = await apiGet(page.request, '/api/manual-certs')
    const mine = (list.json?.data || []).find((r) => r.domain === KEY)
    // 3c: "Yine de yükle" → v3 güncel; eski v1 kalıcı silindi → v3 + v2
    expect(mine?.current_version?.version).toBe(3)
    expect(mine?.versions_count).toBe(2)
    // Yükleme / yeni sürüm / yeniden değerlendirme yalnız toparlanma yapar: açık alarm YOK
    const alerts = await apiGet(page.request, `/api/history/${encodeURIComponent(KEY)}/alerts`)
    const open = (Array.isArray(alerts.json?.data) ? alerts.json.data : Array.isArray(alerts.json) ? alerts.json : [])
      .filter((a) => !a.resolved && !a.resolved_at)
    expect(open.length, 'manuel kayıt için açılmış alarm').toBe(0)
  })
})
