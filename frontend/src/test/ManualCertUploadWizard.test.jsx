import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act, fillGroupAndTags } from './test-utils.jsx'

/**
 * Yükleme sihirbazı (2026-10-06): Dosya → İnceleme → Takip → Sonuç. Hiçbir adım kendiliğinden gönderilmez; şifre
 * hatası alanın altında; uyarılar ve CSR kartı; tek / toplu / yenileme yolları; 400 alan hataları alana, 409 kodları
 * açıklamalı. API tümüyle mock — gerçek istek kaçmaz.
 *
 * <p>2026-10-08 (kullanıcı isteği: özel anahtar sunucuya hiç gitmez): dosya TARAYICIDA açılır (`extract/` — gerçek
 * çekirdek, ana iş parçacığında); her yükleme isteği yalnız `extracted` taşır — FormData'da `file` / `text` / `password`
 * YOK, gövdede "PRIVATE KEY" yok. Şifre gerekli / yanlış, BKS, tanınmayan biçim sunucuya gitmeden alanın altında.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  api: withApiFallback({
    manualCerts: { analyze: vi.fn(), create: vi.fn(), createBatch: vi.fn(), renew: vi.fn(), get: vi.fn() },
  }),
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: {}, canView: () => true, canEdit: () => true, canExecute: () => true, refresh: () => {} }),
}))
// Gerçek ayıklayıcı (Worker'sız) — tek tek testler sonucu `mockResolvedValueOnce` ile ezebilir
vi.mock('../components/manualcert/extract/index.js', async (importOriginal) => {
  const real = await importOriginal()
  return { ...real, extractCertificates: vi.fn((input, opts) => real.extractCertificates(input, { ...opts, inline: true })) }
})

import { api } from '../api/client'
import UploadWizard from '../components/manualcert/UploadWizard.jsx'
import { extractCertificates } from '../components/manualcert/extract/index.js'
import { uploadPreview } from './helpers/sslPreviewFixture.js'
import { bksLike, keyPem, makeChain, makeRoot, zipBytes } from './helpers/certFixtures.js'

let CH
let OTHER
beforeAll(() => {
  CH = makeChain('api.example.test')
  OTHER = makeRoot('Example Independent Root')
})

/** Gönderilen FormData'nın `extracted` gövdesi (JSON) — ve ham alanların YOKLUĞU. */
async function sentExtracted(fd) {
  expect(fd.get('file')).toBeNull()
  expect(fd.get('text')).toBeNull()
  expect(fd.get('password')).toBeNull()
  const part = fd.get('extracted')
  expect(part).toBeTruthy()
  expect(part.name).toBe('extracted.json')
  const raw = await part.text()
  expect(raw).not.toMatch(/PRIVATE KEY/)
  return JSON.parse(raw)
}

const entry = (over = {}) => ({
  ref: 'AB12', alias: null, is_key_entry: true, is_ca: false, self_signed: false,
  subject: 'CN=api.example.test', subject_dn: 'CN=api.example.test,O=Example Ltd', cn: 'api.example.test',
  issuer: 'Example CA', issuer_dn: 'CN=Example CA,O=Example Trust', serial_number: '01AF',
  not_before: '2026-01-01T00:00:00', not_after: '2027-01-01T00:00:00', days_remaining: 87, status: 'valid',
  san: ['api.example.test', 'a1.example.test', 'a2.example.test', 'a3.example.test', 'a4.example.test', 'a5.example.test', 'a6.example.test'],
  key_alg: 'RSA', key_size: 2048, signature_algorithm: 'SHA256withRSA', key_usage: [], ext_key_usage: ['serverAuth'], cert_type: 'LEAF',
  chain: [{ subject: 'CN=Example CA', issuer: 'CN=Example Root', not_after: '2030-01-01T00:00:00', fingerprint: 'CA1', is_ca: true, days_remaining: 1200 }],
  chain_complete: true, trust_status: 'TRUSTED', weak: { signature: 'OK', key_size: 'OK' }, suggested_key: 'api.example.test',
  warnings: [{ code: 'EXPIRES_SOON', severity: 'warn', params: { days: 87 } }],
  matches: { already_tracked: null, same_subject: [], network_monitored: [] },
  ...over,
})
const analysis = (over = {}) => ({
  format: 'PKCS12', file_name: 'store.pfx', size_bytes: 4096, needs_password: false, password_error: false,
  warnings: [{ code: 'PRIVATE_KEY_KEPT_LOCAL', severity: 'info', params: { count: 1 } }],
  csr: null, entries: [entry()], default_ref: 'AB12', ...over,
})
/** Tarayıcı ayıklamasının (mock) sonucu — `extractCertificates.mockResolvedValueOnce` için. */
const extraction = (over = {}) => ({
  format: 'PKCS12', file_name: 'store.pfx', size_bytes: 4096, entries: [{ alias: 'srv', key_entry: true, certs: ['QUJD'] }],
  csr_pem: [], private_keys_removed: 1, certificate_count: 1, needs_password: false, password_error: false, password_used: true,
  unsupported: null, notes: [], ...over,
})

const TEAMS = [{ id: 1, name: 'Takım A' }]
const dialog = () => screen.getByRole('dialog')
const step = () => document.querySelector('[data-slot="mcert-wizard"]')?.getAttribute('data-step')
const fileInput = () => document.querySelector('[data-slot="mcert-file-input"]')
/** Varsayılan içerik GERÇEK bir PEM sertifikası (tarayıcıdaki ayıklayıcı onu okur). */
const pick = (name, body) => fireEvent.change(fileInput(), { target: { files: [new File([body ?? CH.leaf.pem], name)] } })
const btn = (re) => within(dialog()).getByRole('button', { name: re })

async function toReview(over) {
  api.manualCerts.analyze.mockResolvedValueOnce({ success: true, data: analysis(over) })
  pick('server.pem')
  fireEvent.click(btn(/^(Analyse|Analiz et)$/))
  await waitFor(() => expect(step()).toBe('review'))
}

function renderWizard(props = {}) {
  const handlers = { onClose: vi.fn(), onDone: vi.fn(), onOpenCert: vi.fn() }
  render(<UploadWizard teams={TEAMS} {...handlers} {...props} />)
  return handlers
}

describe('UploadWizard — Dosya adımı', () => {
  beforeEach(() => vi.clearAllMocks())

  it('dosyasız "Analiz et" istek atmaz, hata alanın altında; .pfx seçilince şifre alanı baştan görünür', async () => {
    renderWizard()
    expect(step()).toBe('file')
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await screen.findByText(/Choose a certificate file\.|Bir sertifika dosyası seçin\./)
    expect(api.manualCerts.analyze).not.toHaveBeenCalled()
    expect(document.querySelector('[data-slot="mcert-password"]')).toBeNull()
    pick('store.pfx')
    expect(document.querySelector('[data-slot="mcert-file-chip"]')).toHaveTextContent('store.pfx')
    expect(document.querySelector('[data-slot="mcert-password"]')).toBeTruthy()
    expect(screen.queryByText(/Choose a certificate file\./)).toBeNull()
  })

  it('CSR uzantısı uyarılır; 5 MB üstü dosya sunucuya gitmeden reddedilir', async () => {
    renderWizard()
    pick('request.csr')
    expect(within(dialog()).getByText(/may not be a certificate|sertifika olmayabilir/)).toBeInTheDocument()
    const big = new File(['x'], 'big.pem')
    Object.defineProperty(big, 'size', { value: 6 * 1024 * 1024 })
    fireEvent.change(fileInput(), { target: { files: [big] } })
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await screen.findByText(/The file is too large|Dosya çok büyük/)
    expect(api.manualCerts.analyze).not.toHaveBeenCalled()
  })

  it('PKCS#12 şifre gerekli / yanlış → TARAYICIDA anlaşılır, alanın altında hata, sunucuya HİÇ istek yok; doğru şifreyle yalnız açık sertifikalar gider', async () => {
    renderWizard()
    extractCertificates
      .mockResolvedValueOnce(extraction({ entries: [], needs_password: true, password_used: false }))
      .mockResolvedValueOnce(extraction({ entries: [], password_error: true }))
      .mockResolvedValueOnce(extraction())
    api.manualCerts.analyze.mockResolvedValueOnce({ success: true, data: analysis() })
    pick('store.pfx')
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await screen.findByText(/This file is password-protected|Bu dosya şifreli/)
    expect(step()).toBe('file')
    expect(api.manualCerts.analyze).not.toHaveBeenCalled()
    fireEvent.change(document.querySelector('[data-slot="mcert-password"]'), { target: { value: 'yanlis' } })
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await screen.findByText(/The password is wrong|Şifre yanlış/)
    expect(document.querySelector('[data-slot="mcert-password"]')).toHaveAttribute('aria-invalid', 'true')
    expect(api.manualCerts.analyze).not.toHaveBeenCalled()
    fireEvent.change(document.querySelector('[data-slot="mcert-password"]'), { target: { value: 'dogru' } })
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await waitFor(() => expect(step()).toBe('review'))
    // Şifre yalnız TARAYICIDAKİ ayıklayıcıya verildi; sunucu isteğinde yok
    expect(extractCertificates.mock.calls[2][0]).toMatchObject({ password: 'dogru' })
    expect(api.manualCerts.analyze).toHaveBeenCalledTimes(1)
    const fd = api.manualCerts.analyze.mock.calls[0][0]
    const body = await sentExtracted(fd)
    expect(body).toEqual({ format: 'PKCS12', file_name: 'store.pfx', size_bytes: 4096,
      entries: [{ alias: 'srv', key_entry: true, certs: ['QUJD'] }], csr_pem: [], private_keys_removed: 1 })
    expect(JSON.stringify(body)).not.toContain('dogru')
    // İnceleme: "özel anahtar (1) tarayıcınızda ayıklandı … parola da yalnız tarayıcıda" notu; sunucunun aynı bilgili uyarısı listede tekrarlanmaz
    const note = document.querySelector('[data-slot="mcert-kept-local"]')
    expect(note).toHaveAttribute('data-keys', '1')
    expect(note).toHaveAttribute('data-password', 'used')
    expect(note).toHaveTextContent(/Private keys \(1\) were removed in your browser; they were not sent to the server\. The password was also used only in your browser\./)
    expect(document.querySelector('[data-slot="mcert-warning"][data-code="PRIVATE_KEY_KEPT_LOCAL"]')).toBeNull()
  })

  it('özel anahtarlı PEM: anahtar tarayıcıda ayıklanır — gövdede "PRIVATE KEY" yok, yalnız sertifika; not (1)', async () => {
    renderWizard()
    api.manualCerts.analyze.mockResolvedValueOnce({ success: true, data: analysis({ format: 'PEM', file_name: 'with-key.pem' }) })
    pick('with-key.pem', CH.leaf.pem + keyPem(CH.leaf.keys))
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await waitFor(() => expect(step()).toBe('review'))
    const body = await sentExtracted(api.manualCerts.analyze.mock.calls[0][0])
    expect(body.format).toBe('PEM')
    expect(body.file_name).toBe('with-key.pem')
    expect(body.private_keys_removed).toBe(1)
    expect(body.entries).toHaveLength(1)
    expect(body.entries[0].certs[0]).toBe(Buffer.from(CH.leaf.der).toString('base64'))
    expect(document.querySelector('[data-slot="mcert-kept-local"]')).toHaveAttribute('data-keys', '1')
    expect(document.querySelector('[data-slot="mcert-kept-local"]')).not.toHaveAttribute('data-password')
  })

  it('metin yapıştır: metin TARAYICIDA ayıklanır — gövdede metin yok, yalnız extracted (biçim TEXT)', async () => {
    renderWizard()
    fireEvent.click(btn(/^(Paste text|Metin yapıştır)$/))
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await screen.findByText(/Paste the certificate text\.|Sertifika metnini yapıştırın\./)
    fireEvent.change(document.querySelector('[data-slot="mcert-paste"]'), { target: { value: CH.root.pem } })
    api.manualCerts.analyze.mockResolvedValueOnce({ success: true, data: analysis({ format: 'TEXT', file_name: null }) })
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await waitFor(() => expect(step()).toBe('review'))
    const body = await sentExtracted(api.manualCerts.analyze.mock.calls[0][0])
    expect(body).toMatchObject({ format: 'TEXT', file_name: null, private_keys_removed: 0 })
    expect(body.entries[0].certs[0]).toBe(Buffer.from(CH.root.der).toString('base64'))
    expect(document.querySelector('[data-slot="mcert-kept-local"]')).toHaveTextContent(/only the public certificates were sent/)
  })

  it('BKS: tarayıcıda açılamaz → dosya alanının altında hata + keytool yönergesi; sunucuya istek yok', async () => {
    renderWizard()
    pick('truststore.bks', bksLike())
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await screen.findByText(/BKS \/ UBER keystores cannot be opened in the browser/)
    expect(document.querySelector('[data-slot="mcert-bks-help"]')).toHaveTextContent(/keytool -exportcert -rfc/)
    expect(api.manualCerts.analyze).not.toHaveBeenCalled()
    expect(step()).toBe('file')
    // Tanınmayan içerik de alanın altında
    pick('rastgele.bin', new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8, 9]))
    expect(document.querySelector('[data-slot="mcert-bks-help"]')).toBeNull()
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await screen.findByText(/The file format was not recognised/)
    expect(api.manualCerts.analyze).not.toHaveBeenCalled()
  })

  it('ZIP: girdiler tarayıcıda toplanır (2 sertifika, .key sayılır); tarayıcının notları sunucu uyarılarının önünde', async () => {
    renderWizard()
    api.manualCerts.analyze.mockResolvedValueOnce({ success: true, data: analysis({ format: 'ZIP', file_name: 'paket.zip',
      warnings: [{ code: 'PRIVATE_KEY_KEPT_LOCAL', severity: 'info', params: { count: 1 } }, { code: 'MULTIPLE_LEAVES', severity: 'info', params: { count: 2 } }] }) })
    pick('paket.zip', zipBytes({ 'leaf.pem': CH.leaf.pem, 'other.pem': OTHER.pem, 'leaf.key': keyPem(CH.leaf.keys), 'README.txt': 'not' }))
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await waitFor(() => expect(step()).toBe('review'))
    const body = await sentExtracted(api.manualCerts.analyze.mock.calls[0][0])
    expect(body.format).toBe('ZIP')
    expect(body.entries).toHaveLength(2)
    expect(body.private_keys_removed).toBe(1)
    const codes = [...document.querySelectorAll('[data-slot="mcert-warning"]')].map((w) => w.getAttribute('data-code'))
    expect(codes).toEqual(expect.arrayContaining(['ZIP_SKIPPED_ENTRY', 'MULTIPLE_LEAVES']))
    expect(codes).not.toContain('PRIVATE_KEY_KEPT_LOCAL')
    expect(document.querySelector('[data-slot="mcert-warning"][data-code="ZIP_SKIPPED_ENTRY"]')).toHaveTextContent('README.txt')
  })

  it('429 → bilgilendirici bant (yalnız bildirim değil); BUSY / PARSE_TIMEOUT istemcinin açıklamasıyla, kod "Teknik ayrıntı"da', async () => {
    renderWizard()
    api.manualCerts.analyze.mockResolvedValueOnce({ success: false, status: 429, code: 'RATE_LIMITED' })
    pick('a.pem')
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await screen.findByText(/Too many analyse or save requests were sent in the last minute/)
    expect(document.querySelector('[data-slot="mcert-banner"]')).toHaveAttribute('data-code', 'RATE_LIMITED')
    api.manualCerts.analyze.mockResolvedValueOnce({ success: false, status: 429, code: 'BUSY', error: 'Çözümleyici meşgul (sunucu)' })
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await screen.findByText(/The certificate analyser on the server is busy with other files/)
    expect(document.querySelector('[data-slot="mcert-banner"]')).toHaveAttribute('data-code', 'BUSY')
    api.manualCerts.analyze.mockResolvedValueOnce({ success: false, status: 422, code: 'PARSE_TIMEOUT' })
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await screen.findByText(/could not analyse the certificates within 10 seconds/)
    expect(step()).toBe('file')
  })

  it('JKS yanlış şifre: tarayıcı bütünlük notu (PASSWORD_WRONG) — sertifikalar yine okunur → İnceleme; "Şifreyi gir" Dosya adımına döner, alan işaretli', async () => {
    renderWizard()
    extractCertificates.mockResolvedValueOnce(extraction({ format: 'JKS', file_name: 'trust.jks',
      notes: [{ code: 'PASSWORD_WRONG', severity: 'warn', params: { format: 'JKS' } }] }))
    api.manualCerts.analyze.mockResolvedValueOnce({ success: true, data: analysis({ format: 'JKS', file_name: 'trust.jks', warnings: [] }) })
    pick('trust.jks')
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await waitFor(() => expect(step()).toBe('review'))
    expect(document.querySelector('[data-slot="mcert-warning"][data-code="PASSWORD_WRONG"]')).toHaveTextContent('JKS')
    expect(btn(/^(Next|İleri)$/)).not.toBeDisabled()
    fireEvent.click(btn(/Enter the password and analyse again|Şifreyi girip yeniden analiz et/))
    await waitFor(() => expect(step()).toBe('file'))
    expect(document.querySelector('[data-slot="mcert-password"]')).toHaveAttribute('aria-invalid', 'true')
    expect(await screen.findByText(/The password is wrong|Şifre yanlış/)).toBeInTheDocument()
  })
})

describe('UploadWizard — İnceleme adımı', () => {
  beforeEach(() => vi.clearAllMocks())

  it('dosya ve girdi uyarıları, güven rozeti, zincir SSL sekmesiyle AYNI kartlarla (önizleme yoksa girdinin alanlarından); SAN katlanır', async () => {
    renderWizard()
    await toReview()
    const warns = [...document.querySelectorAll('[data-slot="mcert-warning"]')].map((w) => w.getAttribute('data-code'))
    expect(warns).toEqual(expect.arrayContaining(['EXPIRES_SOON']))
    // Sunucunun PRIVATE_KEY_KEPT_LOCAL bilgisi listede değil, tarayıcının "ayıklandı" notunda
    expect(warns).not.toContain('PRIVATE_KEY_KEPT_LOCAL')
    expect(document.querySelector('[data-slot="mcert-kept-local"]')).toBeTruthy()
    expect(document.querySelector('[data-slot="mcert-warning"][data-code="EXPIRES_SOON"]')).toHaveTextContent(/87/)
    const card = document.querySelector('[data-slot="mcert-entry"]')
    expect(card.querySelector('[data-slot="mcert-trust"]')).toHaveAttribute('data-trust', 'TRUSTED')
    const chain = card.querySelector('[data-slot="mcert-chain"] [data-slot="ssl-chain"]')
    expect([...chain.querySelectorAll('[data-slot="ssl-chain-node"]')].map((n) => n.dataset.role)).toEqual(['leaf', 'intermediate', 'root-store'])
    expect(chain.querySelector('[data-slot="ssl-chain-node"][data-role="intermediate"]')).toHaveTextContent('Example CA')
    // 7 SAN: liste KAPALI başlar, tetik sayıyı söyler (ağ sertifikasının zincir kartıyla aynı)
    const sanBtn = within(chain).getByRole('button', { name: /Alternative names \(SAN\)\s*7/ })
    expect(sanBtn).toHaveAttribute('aria-expanded', 'false')
    expect(btn(/^(Next|İleri)$/)).not.toBeDisabled()
    // Tek girdi: "birden çok" anahtarı yok; gruplama notu yok (dosyada tek sertifika sayılmadı)
    expect(within(dialog()).queryByRole('button', { name: /Several \(truststore\)|Birden çok/ })).toBeNull()
    expect(document.querySelector('[data-slot="mcert-grouped"]')).toBeNull()
  })

  it('yaprak + ara + kök TEK girdi: sunucu önizlemesiyle 3 kartlı zincir (yaprak → ara → kök), gruplama notu, sertifika sayısı', async () => {
    renderWizard()
    await toReview({ certificate_count: 3, entries: [entry({ preview: uploadPreview({ domain: 'api.example.test' }) })] })
    expect(document.querySelectorAll('[data-slot="mcert-entry"]')).toHaveLength(1)
    const card = document.querySelector('[data-slot="mcert-entry"]')
    const roles = [...card.querySelectorAll('[data-slot="ssl-chain-node"]')].map((n) => n.dataset.role)
    expect(roles).toEqual(['leaf', 'intermediate', 'root'])
    expect(card.querySelector('[data-slot="ssl-chain"]')).toHaveAttribute('data-source', 'upload')
    expect(document.querySelector('[data-slot="mcert-file-summary"]')).toHaveTextContent('Certificates found: 3')
    expect(document.querySelector('[data-slot="mcert-grouped"]')).toHaveTextContent(/The 3 certificates in the file were grouped into 1 tracking record/)
    expect(within(dialog()).queryByRole('button', { name: /Several \(truststore\)|Birden çok/ })).toBeNull()
    // Tek girdi varsayılan seçili → İleri açık
    expect(card).toHaveAttribute('data-selected', 'true')
    expect(btn(/^(Next|İleri)$/)).not.toBeDisabled()
  })

  it('CSR yüklendiyse açıklama kartı + CSR içeriği; takip edilecek girdi yok → İleri kapalı', async () => {
    renderWizard()
    await toReview({ entries: [], default_ref: null, csr: { cn: 'api.example.test', subject_dn: 'CN=api.example.test', san: ['api.example.test'], key_alg: 'RSA', key_size: 2048 },
      warnings: [{ code: 'CSR_NOT_CERTIFICATE', severity: 'error', params: { cn: 'api.example.test' } }] })
    const csr = document.querySelector('[data-slot="mcert-csr"]')
    expect(csr).toHaveTextContent(/certificate signing request|imzalama isteği/)
    expect(csr).toHaveTextContent('RSA 2048')
    expect(document.querySelector('[data-slot="mcert-warning"][data-code="CSR_NOT_CERTIFICATE"]')).toHaveAttribute('data-severity', 'error')
    expect(btn(/^(Next|İleri)$/)).toBeDisabled()
  })
})

describe('UploadWizard — Takip adımı ve gönderim', () => {
  beforeEach(() => vi.clearAllMocks())

  it('tek kayıt: önerilen takip adı, istemci doğrulaması alanın altında, oluştur → Sonuç; gövde sözleşmeye uygun', async () => {
    const h = renderWizard()
    await toReview()
    fireEvent.click(btn(/^(Next|İleri)$/))
    await waitFor(() => expect(step()).toBe('track'))
    const key = document.querySelector('[data-slot="mcert-key"]')
    expect(key).toHaveValue('api.example.test')
    fireEvent.change(key, { target: { value: 'bad name' } })
    fireEvent.click(btn(/^(Start tracking|Takibe al)$/))
    await screen.findByText(/cannot contain spaces|boşluk olamaz/)
    await screen.findByText(/A group is required/)
    expect(api.manualCerts.create).not.toHaveBeenCalled()
    fireEvent.change(key, { target: { value: 'api.example.test-manuel' } })
    await fillGroupAndTags({ root: dialog() })
    api.manualCerts.create.mockResolvedValueOnce({ success: true, data: { inventory_id: 12, domain: 'api.example.test-manuel', version: 1 } })
    fireEvent.click(btn(/^(Start tracking|Takibe al)$/))
    await waitFor(() => expect(step()).toBe('result'))
    const fd = api.manualCerts.create.mock.calls[0][0]
    expect(fd.get('ref')).toBe('AB12')
    expect(fd.get('domain')).toBe('api.example.test-manuel')
    // Oluşturma da analizle AYNI ayıklanmış gövdeyi gönderir (dosya yeniden okunmaz, ham dosya gitmez)
    const body = await sentExtracted(fd)
    expect(body).toMatchObject({ format: 'PEM', file_name: 'server.pem' })
    expect(body.entries[0].certs[0]).toBe(Buffer.from(CH.leaf.der).toString('base64'))
    expect(JSON.parse(fd.get('inventory'))).toMatchObject({ team_id: 1, group_name: 'Grup A', tags: 't1', port: 443, use_proxy: false })
    expect(document.querySelector('[data-slot="mcert-result"]')).toHaveAttribute('data-kind', 'created')
    expect(h.onDone).toHaveBeenCalledTimes(1)
    fireEvent.click(btn(/^(Open certificate|Sertifikayı aç)$/))
    expect(h.onOpenCert).toHaveBeenCalledWith(expect.objectContaining({ domain: 'api.example.test-manuel', inventory_id: 12 }))
    expect(h.onClose).toHaveBeenCalled()
  })

  it('400 alan hatası alanın altında; 409 KEY_EXISTS takip adında; 409 ALREADY_TRACKED kaydı aç bandı', async () => {
    const h = renderWizard()
    await toReview()
    fireEvent.click(btn(/^(Next|İleri)$/))
    await fillGroupAndTags({ root: dialog() })
    api.manualCerts.create.mockResolvedValueOnce({ success: false, status: 400, errors: { domain: 'Sunucu: geçersiz takip adı' } })
    fireEvent.click(btn(/^(Start tracking|Takibe al)$/))
    await screen.findByText('Sunucu: geçersiz takip adı')
    api.manualCerts.create.mockResolvedValueOnce({ success: false, status: 409, code: 'KEY_EXISTS' })
    fireEvent.click(btn(/^(Start tracking|Takibe al)$/))
    await screen.findByText(/already in use|zaten kullanılıyor/)
    api.manualCerts.create.mockResolvedValueOnce({ success: false, status: 409, code: 'ALREADY_TRACKED', inventory_id: 4, domain: 'eski.example.test' })
    fireEvent.click(btn(/^(Start tracking|Takibe al)$/))
    const banner = await screen.findByText(/tracked as “eski\.example\.test”|“eski\.example\.test” kaydıyla/)
    expect(banner).toBeInTheDocument()
    expect(step()).toBe('track')
    fireEvent.click(btn(/^(Open record|Kaydı aç)$/))
    expect(h.onOpenCert).toHaveBeenCalledWith({ domain: 'eski.example.test', inventory_id: 4 })
  })

  it('takip adımında alanı olmayan sunucu hatası (ör. şifre) bantta gösterilir; toplu satır hatası satırın altında', async () => {
    renderWizard()
    await toReview()
    fireEvent.click(btn(/^(Next|İleri)$/))
    await fillGroupAndTags({ root: dialog() })
    api.manualCerts.create.mockResolvedValueOnce({ success: false, status: 400, errors: { password: 'Dosya şifreli — şifreyi girin.' } })
    fireEvent.click(btn(/^(Start tracking|Takibe al)$/))
    const banner = await screen.findByText('Dosya şifreli — şifreyi girin.')
    expect(banner.closest('[data-slot="alert"]')).toHaveAttribute('data-tone', 'danger')
  })

  it('toplu (truststore): "Birden çok" ile iki CA seçilir; her biri ayrı takip adı; tek istek, items JSON', async () => {
    renderWizard()
    const ca = (ref, cn) => entry({ ref, cn, subject: `CN=${cn}`, subject_dn: `CN=${cn}`, is_ca: true, is_key_entry: false, suggested_key: cn.toLowerCase().replace(/\s+/g, '-'), warnings: [], san: [] })
    await toReview({ format: 'JKS', entries: [ca('R1', 'Root One'), ca('R2', 'Root Two')], default_ref: 'R1' })
    expect(document.querySelector('[data-slot="mcert-entry"]').closest('[role="radiogroup"]')).toBeTruthy()
    expect(within(dialog()).getByText(/looks like a truststore|truststore gibi/)).toBeInTheDocument()
    fireEvent.click(btn(/Several \(truststore\)|Birden çok/))
    const boxes = within(dialog()).getAllByRole('checkbox')
    expect(boxes[0]).toBeChecked()
    fireEvent.click(boxes[1])
    expect(boxes[1]).toBeChecked()
    fireEvent.click(btn(/^(Next|İleri)$/))
    await waitFor(() => expect(step()).toBe('track'))
    const keys = [...document.querySelectorAll('[data-slot="mcert-batch-key"]')].map((i) => i.value)
    expect(keys).toEqual(['root-one', 'root-two'])
    await fillGroupAndTags({ root: dialog() })
    api.manualCerts.createBatch.mockResolvedValueOnce({ success: true, data: { created: [{ inventory_id: 1, domain: 'root-one' }, { inventory_id: 2, domain: 'root-two' }] } })
    fireEvent.click(btn(/Track selected \(2\)|2 sertifikayı takibe al/))
    await waitFor(() => expect(step()).toBe('result'))
    const fd = api.manualCerts.createBatch.mock.calls[0][0]
    expect(JSON.parse(fd.get('items'))).toEqual([{ ref: 'R1', domain: 'root-one' }, { ref: 'R2', domain: 'root-two' }])
    expect((await sentExtracted(fd)).format).toBe('PEM')
    expect(document.querySelector('[data-slot="mcert-result"]')).toHaveAttribute('data-kind', 'batch')
    expect(document.querySelector('[data-slot="mcert-result"]')).toHaveTextContent('root-two')
  })

  it('aynı konu adı → "Bu bir yenileme mi?"; İleri yenileme kipini ve hedefi önceden seçer', async () => {
    renderWizard()
    api.manualCerts.get.mockResolvedValue({ success: true, data: { versions: [{ id: 3, version: 1, current: true, not_after: '2026-11-01T00:00:00', fingerprint: 'OLD', issuer: 'Example CA', subject: 'CN=api.example.test', key_alg: 'RSA', key_size: 2048, san: ['api.example.test'] }] } })
    await toReview({ entries: [entry({ matches: { already_tracked: null, same_subject: [{ inventory_id: 9, domain: 'old.example.test', not_after: '2026-11-01T00:00:00' }], network_monitored: [{ domain: 'www.example.test' }] } })] })
    expect(document.querySelector('[data-slot="mcert-match"][data-kind="same-subject"]')).toHaveTextContent('old.example.test')
    expect(document.querySelector('[data-slot="mcert-match"][data-kind="network"]')).toHaveTextContent('www.example.test')
    fireEvent.click(btn(/^(Next|İleri)$/))
    await waitFor(() => expect(step()).toBe('track'))
    expect(document.querySelector('[data-slot="mcert-mode"][data-mode="renew"]')).toHaveAttribute('data-selected', 'true')
    await waitFor(() => expect(api.manualCerts.get).toHaveBeenCalledWith(9))
    const cmp = await waitFor(() => { const el = document.querySelector('[data-slot="mcert-compare"]'); if (!el) throw new Error('yok'); return el })
    expect(cmp.querySelector('[data-key="san"]')).toHaveTextContent('a1.example.test')
    expect(btn(/^(Save new version|Yeni sürümü kaydet)$/)).toBeInTheDocument()
  })

  it('satırdan yenileme (sabit hedef): daha eski bitişte onay zorunlu; onayla → renew(confirm=true) → değişiklik uyarıları', async () => {
    renderWizard({ renewTarget: { inventory_id: 5, domain: 'api.example.test' } })
    expect(screen.getByText(/Upload new version: api\.example\.test|Yeni sürüm yükle: api\.example\.test/)).toBeInTheDocument()
    api.manualCerts.get.mockResolvedValue({ success: true, data: { versions: [{ id: 30, version: 2, current: true, not_after: '2027-06-01T00:00:00', fingerprint: 'OLD', issuer: 'Example CA', subject: 'CN=api.example.test', key_alg: 'RSA', key_size: 2048, san: ['api.example.test'] }] } })
    await toReview()
    fireEvent.click(btn(/^(Next|İleri)$/))
    await waitFor(() => expect(step()).toBe('track'))
    expect(document.querySelector('[data-slot="mcert-mode"]')).toBeNull()
    await waitFor(() => expect(document.querySelector('[data-slot="mcert-confirm-older"]')).toBeTruthy())
    expect(document.querySelector('[data-slot="mcert-compare-row"][data-key="notAfter"]')).toHaveAttribute('data-changed', 'true')
    fireEvent.click(btn(/^(Save new version|Yeni sürümü kaydet)$/))
    await screen.findByText(/Confirm the version with the earlier expiry|daha eski bitişli sürümü onaylayın/)
    expect(api.manualCerts.renew).not.toHaveBeenCalled()
    fireEvent.click(document.querySelector('[data-slot="mcert-confirm-older"]'))
    api.manualCerts.renew.mockResolvedValueOnce({ success: true, data: { version: 3, previous_version: 2, warnings: [{ code: 'KEY_CHANGED', severity: 'info', params: {} }] } })
    fireEvent.click(btn(/^(Save new version|Yeni sürümü kaydet)$/))
    await waitFor(() => expect(step()).toBe('result'))
    const [id, fd] = api.manualCerts.renew.mock.calls[0]
    expect(id).toBe(5)
    expect(fd.get('confirm')).toBe('true')
    expect(fd.get('ref')).toBe('AB12')
    expect((await sentExtracted(fd)).entries).toHaveLength(1)
    expect(document.querySelector('[data-slot="mcert-result"]')).toHaveAttribute('data-kind', 'renewed')
    expect(document.querySelector('[data-slot="mcert-warning"][data-code="KEY_CHANGED"]')).toBeTruthy()
  })

  it('409 SAME_CERTIFICATE ve OLDER_THAN_CURRENT sunucudan gelirse açıklanır', async () => {
    renderWizard({ renewTarget: { inventory_id: 5, domain: 'api.example.test' } })
    api.manualCerts.get.mockResolvedValue({ success: false })
    await toReview()
    fireEvent.click(btn(/^(Next|İleri)$/))
    await screen.findByText(/current version could not be read|Güncel sürüm okunamadı/)
    api.manualCerts.renew.mockResolvedValueOnce({ success: false, status: 409, code: 'OLDER_THAN_CURRENT' })
    fireEvent.click(btn(/^(Save new version|Yeni sürümü kaydet)$/))
    await screen.findByText(/Confirm the version with the earlier expiry|daha eski bitişli sürümü onaylayın/)
    // Sunucu "daha eski" dedi: istemci karşılaştıramasa da onay kutusu çıkar; onaysız ikinci gönderim yok
    fireEvent.click(btn(/^(Save new version|Yeni sürümü kaydet)$/))
    expect(api.manualCerts.renew).toHaveBeenCalledTimes(1)
    fireEvent.click(document.querySelector('[data-slot="mcert-confirm-older"]'))
    api.manualCerts.renew.mockResolvedValueOnce({ success: false, status: 409, code: 'SAME_CERTIFICATE' })
    fireEvent.click(btn(/^(Save new version|Yeni sürümü kaydet)$/))
    await waitFor(() => expect(api.manualCerts.renew).toHaveBeenCalledTimes(2))
    expect(api.manualCerts.renew.mock.calls[1][1].get('confirm')).toBe('true')
    expect(api.manualCerts.renew.mock.calls[1][1].get('allow_same')).toBeNull()
    await screen.findByText(/already the current version|zaten güncel sürüm/)
    await act(async () => {})
    expect(step()).toBe('track')
    // 2026-10-07: sunucu 409'u engel değil — uyarı bandındaki "Yine de yükle" allow_same ile yeniden gönderir
    const same = document.querySelector('[data-slot="mcert-same"]')
    expect(same.querySelector('[data-slot="alert"]')).toHaveAttribute('data-tone', 'warning')
    expect(btn(/^(Save new version|Yeni sürümü kaydet)$/)).toBeDisabled()
    api.manualCerts.renew.mockResolvedValueOnce({ success: true, data: { version: 4, previous_version: 3, same_certificate: true,
      warnings: [{ code: 'KEY_SAME', severity: 'info', params: {} }] } })
    fireEvent.click(within(same).getByRole('button', { name: /^(Upload anyway|Yine de yükle)$/ }))
    await waitFor(() => expect(step()).toBe('result'))
    expect(api.manualCerts.renew).toHaveBeenCalledTimes(3)
    expect(api.manualCerts.renew.mock.calls[2][1].get('allow_same')).toBe('true')
    const result = document.querySelector('[data-slot="mcert-result"]')
    expect(result).toHaveAttribute('data-same', 'true')
    expect(result).toHaveTextContent('The same certificate was saved as a new version (version 4)')
    expect(result).toHaveTextContent('the expiry date did not change')
  })

  it('istemci aynı sertifikayı görürse (aynı parmak izi): uyarı + "Yine de yükle" → allow_same=true, onay sorulmaz → sonuç "aynı sertifika"', async () => {
    renderWizard({ renewTarget: { inventory_id: 5, domain: 'api.example.test' } })
    api.manualCerts.get.mockResolvedValue({ success: true, data: { versions: [{ id: 30, version: 2, current: true, not_after: '2027-01-01T00:00:00',
      fingerprint: 'AB12', issuer: 'Example CA', subject: 'CN=api.example.test', key_alg: 'RSA', key_size: 2048, san: entry().san }] } })
    await toReview()
    fireEvent.click(btn(/^(Next|İleri)$/))
    await waitFor(() => expect(step()).toBe('track'))
    const same = await waitFor(() => { const el = document.querySelector('[data-slot="mcert-same"]'); if (!el) throw new Error('yok'); return el })
    expect(same).toHaveTextContent(/This certificate is already the current version/)
    expect(same).toHaveTextContent(/Upload anyway/)
    expect(document.querySelector('[data-slot="mcert-confirm-older"]')).toBeNull()   // aynı sertifikada "eski bitiş" sorulmaz
    expect(btn(/^(Save new version|Yeni sürümü kaydet)$/)).toBeDisabled()
    api.manualCerts.renew.mockResolvedValueOnce({ success: true, data: { version: 3, previous_version: 2, same_certificate: true, warnings: [] } })
    fireEvent.click(within(same).getByRole('button', { name: /^(Upload anyway|Yine de yükle)$/ }))
    await waitFor(() => expect(step()).toBe('result'))
    const [id, fd] = api.manualCerts.renew.mock.calls[0]
    expect(id).toBe(5)
    expect(fd.get('allow_same')).toBe('true')
    expect(fd.get('confirm')).toBeNull()
    expect(fd.get('ref')).toBe('AB12')
    expect(document.querySelector('[data-slot="mcert-result"]')).toHaveAttribute('data-same', 'true')
  })
})
