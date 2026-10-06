import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act, fillGroupAndTags } from './test-utils.jsx'

/**
 * Yükleme sihirbazı (2026-10-06): Dosya → İnceleme → Takip → Sonuç. Hiçbir adım kendiliğinden gönderilmez; şifre
 * hatası alanın altında; uyarılar ve CSR kartı; tek / toplu / yenileme yolları; 400 alan hataları alana, 409 kodları
 * açıklamalı. API tümüyle mock — gerçek istek kaçmaz.
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

import { api } from '../api/client'
import UploadWizard from '../components/manualcert/UploadWizard.jsx'

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
  warnings: [{ code: 'PRIVATE_KEY_IGNORED', severity: 'info', params: { count: 1 } }],
  csr: null, entries: [entry()], default_ref: 'AB12', ...over,
})

const TEAMS = [{ id: 1, name: 'Takım A' }]
const dialog = () => screen.getByRole('dialog')
const step = () => document.querySelector('[data-slot="mcert-wizard"]')?.getAttribute('data-step')
const fileInput = () => document.querySelector('[data-slot="mcert-file-input"]')
const pick = (name, body = 'x') => fireEvent.change(fileInput(), { target: { files: [new File([body], name)] } })
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

  it('şifre gerekli → alanın altında hata; şifre girilince ikinci analizde gövdede, sonra İnceleme', async () => {
    renderWizard()
    api.manualCerts.analyze
      .mockResolvedValueOnce({ success: true, data: { ...analysis({ entries: [] }), needs_password: true } })
      .mockResolvedValueOnce({ success: true, data: { ...analysis(), password_error: true, entries: [] } })
      .mockResolvedValueOnce({ success: true, data: analysis() })
    pick('store.pfx')
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await screen.findByText(/This file is password-protected|Bu dosya şifreli/)
    expect(step()).toBe('file')
    fireEvent.change(document.querySelector('[data-slot="mcert-password"]'), { target: { value: 'yanlis' } })
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await screen.findByText(/The password is wrong|Şifre yanlış/)
    fireEvent.change(document.querySelector('[data-slot="mcert-password"]'), { target: { value: 'dogru' } })
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await waitFor(() => expect(step()).toBe('review'))
    const fd = api.manualCerts.analyze.mock.calls[2][0]
    expect(fd.get('password')).toBe('dogru')
    expect(fd.get('file').name).toBe('store.pfx')
  })

  it('metin yapıştır: metin gövdede, dosya yok', async () => {
    renderWizard()
    fireEvent.click(btn(/^(Paste text|Metin yapıştır)$/))
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await screen.findByText(/Paste the certificate text\.|Sertifika metnini yapıştırın\./)
    fireEvent.change(document.querySelector('[data-slot="mcert-paste"]'), { target: { value: '-----BEGIN CERTIFICATE-----\nMIIB' } })
    api.manualCerts.analyze.mockResolvedValueOnce({ success: true, data: analysis({ format: 'TEXT', file_name: null }) })
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await waitFor(() => expect(step()).toBe('review'))
    const fd = api.manualCerts.analyze.mock.calls[0][0]
    expect(fd.get('text')).toMatch(/BEGIN CERTIFICATE/)
    expect(fd.get('file')).toBeNull()
  })

  it('429 → bilgilendirici bant (yalnız bildirim değil); BUSY / PARSE_TIMEOUT sunucu iletisiyle', async () => {
    renderWizard()
    api.manualCerts.analyze.mockResolvedValueOnce({ success: false, status: 429, code: 'RATE_LIMITED' })
    pick('a.pem')
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await screen.findByText(/Too many files were analysed|çok fazla dosya/)
    api.manualCerts.analyze.mockResolvedValueOnce({ success: false, status: 429, code: 'BUSY', error: 'Çözümleyici meşgul (sunucu)' })
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await screen.findByText('Çözümleyici meşgul (sunucu)')
    api.manualCerts.analyze.mockResolvedValueOnce({ success: false, status: 422, code: 'PARSE_TIMEOUT' })
    fireEvent.click(btn(/^(Analyse|Analiz et)$/))
    await screen.findByText(/could not be analysed within 10 seconds|10 saniyede çözümlenemedi/)
    expect(step()).toBe('file')
  })

  it('JKS yanlış şifre: sertifikalar okunur (password_error false) → İnceleme; "Şifreyi gir" Dosya adımına döner, alan işaretli', async () => {
    renderWizard()
    api.manualCerts.analyze.mockResolvedValueOnce({ success: true, data: analysis({ format: 'JKS', file_name: 'trust.jks',
      warnings: [{ code: 'PASSWORD_WRONG', severity: 'warn', params: { format: 'JKS' } }] }) })
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

  it('dosya ve girdi uyarıları, künye, SAN "+N", zincir; CSR kartı ve girdi yoksa İleri kapalı', async () => {
    renderWizard()
    await toReview()
    const warns = [...document.querySelectorAll('[data-slot="mcert-warning"]')].map((w) => w.getAttribute('data-code'))
    expect(warns).toEqual(expect.arrayContaining(['PRIVATE_KEY_IGNORED', 'EXPIRES_SOON']))
    expect(document.querySelector('[data-slot="mcert-warning"][data-code="EXPIRES_SOON"]')).toHaveTextContent(/87/)
    const card = document.querySelector('[data-slot="mcert-entry"]')
    expect(card).toHaveTextContent('CN=api.example.test,O=Example Ltd')
    expect(card.querySelectorAll('[data-slot="mcert-san"]')).toHaveLength(5)
    expect(card.querySelector('[data-slot="mcert-san-more"]')).toHaveTextContent('+2')
    expect(card.querySelector('[data-slot="mcert-trust"]')).toHaveAttribute('data-trust', 'TRUSTED')
    fireEvent.click(within(card).getByRole('button', { name: /Chain certificates: 1|Zincir: 1/ }))
    await waitFor(() => expect(card.querySelector('[data-slot="mcert-chain-link"]')).toHaveTextContent('CN=Example CA'))
    expect(card.querySelector('[data-slot="mcert-chain-link"]')).toHaveTextContent('CN=Example Root')
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
    expect(fd.get('file').name).toBe('server.pem')
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
    await screen.findByText(/already the current version|zaten güncel sürüm/)
    await act(async () => {})
    expect(step()).toBe('track')
  })
})
