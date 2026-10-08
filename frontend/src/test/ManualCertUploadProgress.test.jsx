import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act, fillGroupAndTags } from './test-utils.jsx'

/**
 * YÜKLEME DURUMU + AÇIKLAYICI HATALAR (2026-10-08, kullanıcı isteği: "manuel yükleme yaparken yükleme durumunu
 * gösterelim … hata mesajları çok açıklayıcı olsun"). Sihirbaz (UploadWizard):
 *  - analiz koşusu: Dosya okunuyor → Sertifikalar tarayıcınızda ayıklanıyor (ZIP "n / N", PKCS#12 notu) → Sunucuya
 *    gönderiliyor (GERÇEK yüzde, ProgressBar aria-valuenow) → Sunucu analiz ediyor; tek aria-live satırı;
 *  - "Vazgeç": ayıklama / istek kesilir (AbortSignal), panel kalkar, meşgul durum KALMAZ, alanlar yeniden açılır;
 *  - kayıt koşusu: istek sunucuya ulaşınca iptal kilitlenir ("bu aşamada durdurulamaz");
 *  - hata eşlemesi: alana ait olan alanın altında, olmayan bantta (kod `data-code`, "Teknik ayrıntı"); her sunucu kodu.
 * API ve ayıklayıcı tümüyle mock (denetlenebilir sözler) — gerçek istek kaçmaz.
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
vi.mock('../components/manualcert/extract/index.js', async (importOriginal) => {
  const real = await importOriginal()
  return { ...real, extractCertificates: vi.fn((input, opts) => real.extractCertificates(input, { ...opts, inline: true })) }
})

import { api } from '../api/client'
import UploadWizard from '../components/manualcert/UploadWizard.jsx'
import { extractCertificates } from '../components/manualcert/extract/index.js'
import { describeUploadFailure, describeVersionDeleteFailure, technicalDetail } from '../components/manualcert/manualCertErrors.js'
import { EN } from '../i18n/en.js'
import { TR } from '../i18n/tr.js'
import { makeChain } from './helpers/certFixtures.js'

let CH
beforeAll(() => { CH = makeChain('api.example.test') })

const entry = (over = {}) => ({
  ref: 'AB12', alias: null, is_key_entry: true, is_ca: false, self_signed: false, subject: 'CN=api.example.test',
  subject_dn: 'CN=api.example.test', cn: 'api.example.test', issuer: 'Example CA', issuer_dn: 'CN=Example CA',
  not_before: '2026-01-01T00:00:00', not_after: '2027-01-01T00:00:00', days_remaining: 87, status: 'valid', san: ['api.example.test'],
  key_alg: 'RSA', key_size: 2048, chain: [], chain_complete: true, trust_status: 'TRUSTED', suggested_key: 'api.example.test',
  warnings: [], matches: { already_tracked: null, same_subject: [], network_monitored: [] }, ...over,
})
const analysis = (over = {}) => ({ format: 'PEM', file_name: 'server.pem', size_bytes: 1200, warnings: [], csr: null,
  entries: [entry()], default_ref: 'AB12', ...over })
const extraction = (over = {}) => ({
  format: 'ZIP', file_name: 'paket.zip', size_bytes: 4096, entries: [{ alias: null, key_entry: false, certs: ['QUJD'] }],
  csr_pem: [], private_keys_removed: 0, certificate_count: 1, needs_password: false, password_error: false, password_used: false,
  unsupported: null, notes: [], ...over,
})

/** Denetlenebilir söz: `.resolve(v)` ile biter; çağrının argümanları `.args`. */
function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve: (v) => act(async () => { resolve(v); await promise }) }
}

const dialog = () => screen.getByRole('dialog')
const step = () => document.querySelector('[data-slot="mcert-wizard"]')?.getAttribute('data-step')
const panel = () => document.querySelector('[data-slot="mcert-progress"]')
const stages = () => [...document.querySelectorAll('[data-slot="mcert-progress-stage"]')].map((s) => `${s.dataset.stage}:${s.dataset.state}`)
const live = () => document.querySelector('[data-slot="mcert-progress-live"]')
const bar = () => panel()?.querySelector('[role="progressbar"]')
const btn = (re) => within(dialog()).getByRole('button', { name: re })
const pick = (name, body) => fireEvent.change(document.querySelector('[data-slot="mcert-file-input"]'),
  { target: { files: [new File([body ?? CH.leaf.pem], name)] } })
const analyzeBtn = () => document.querySelector('[data-slot="mcert-analyze"]')

function renderWizard(props = {}) {
  const handlers = { onClose: vi.fn(), onDone: vi.fn(), onOpenCert: vi.fn() }
  render(<UploadWizard teams={[{ id: 1, name: 'Takım A' }]} {...handlers} {...props} />)
  return handlers
}

/** Takip adımına kadar (gerçek PEM, analiz mock). */
async function toTrack() {
  api.manualCerts.analyze.mockResolvedValueOnce({ success: true, data: analysis() })
  pick('server.pem')
  fireEvent.click(btn(/^Analyse$/))
  await waitFor(() => expect(step()).toBe('review'))
  fireEvent.click(btn(/^Next$/))
  await waitFor(() => expect(step()).toBe('track'))
  await fillGroupAndTags({ root: dialog() })
}

describe('UploadWizard — yükleme durumu (analiz koşusu)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('aşamalar sırayla: okunuyor → ayıklanıyor (ZIP n / N, belirli çubuk) → gönderiliyor (gerçek %) → sunucu analiz ediyor → İnceleme', async () => {
    renderWizard()
    const ex = deferred()
    let exOpts = null
    extractCertificates.mockImplementationOnce((input, opts) => { exOpts = opts; return ex.promise })
    const up = deferred()
    let upOpts = null
    api.manualCerts.analyze.mockImplementationOnce((fd, opts) => { upOpts = opts; return up.promise })
    pick('paket.zip')
    fireEvent.click(btn(/^Analyse$/))

    // Koşu başladı: TÜM aşamalar baştan listede (yerleşim zıplamaz), ilki sürüyor; düğme meşgul, "Vazgeç" var
    await waitFor(() => expect(panel()).toBeTruthy())
    expect(panel()).toHaveAttribute('data-kind', 'analyze')
    expect(stages()).toEqual(['read:active', 'extract:pending', 'upload:pending', 'analyze:pending'])
    expect(analyzeBtn()).toBeDisabled()
    expect(analyzeBtn()).toHaveAttribute('aria-busy', 'true')
    expect(document.querySelector('[data-slot="mcert-cancel-run"]')).not.toBeDisabled()
    expect(within(dialog()).queryByRole('button', { name: /^Cancel$/ })).toBe(document.querySelector('[data-slot="mcert-cancel-run"]'))
    expect(live()).toHaveAttribute('aria-live', 'polite')
    expect(live()).toHaveTextContent('Reading the file')
    // Koşu sürerken dosya değiştirilemez
    expect(document.querySelector('[data-slot="mcert-file-input"]')).toBeDisabled()

    act(() => { exOpts.onProgress({ phase: 'extract' }) })
    expect(stages()).toEqual(['read:done', 'extract:active', 'upload:pending', 'analyze:pending'])
    expect(live()).toHaveTextContent('Extracting the certificates in your browser')
    act(() => { exOpts.onProgress({ phase: 'zip', done: 1, total: 4 }) })
    expect(document.querySelector('[data-slot="mcert-progress-detail"]')).toHaveTextContent('Files in the archive: 1 / 4')
    expect(bar()).toHaveAttribute('aria-valuenow', '1')
    expect(bar()).toHaveAttribute('aria-valuemax', '4')
    expect(live()).toHaveTextContent('25%')
    act(() => { exOpts.onProgress({ phase: 'pkcs12', step: 'kdf' }) })   // ZIP içindeki PFX: ZIP sayacı önde kalır
    expect(document.querySelector('[data-slot="mcert-progress-detail"]')).toHaveTextContent('Files in the archive: 1 / 4')

    await ex.resolve(extraction())
    expect(stages()).toEqual(['read:done', 'extract:done', 'upload:active', 'analyze:pending'])
    expect(upOpts?.signal).toBeTruthy()
    act(() => { upOpts.onProgress({ phase: 'upload', loaded: 1536, total: 2048 }) })
    expect(bar()).toHaveAttribute('aria-valuenow', '1536')
    expect(panel()).toHaveTextContent('75%')
    expect(document.querySelector('[data-slot="mcert-progress-detail"]')).toHaveTextContent('2 KB')
    expect(live()).toHaveTextContent('Sending to the server 75%')
    act(() => { upOpts.onProgress({ phase: 'sent' }) })
    expect(stages()).toEqual(['read:done', 'extract:done', 'upload:done', 'analyze:active'])
    expect(bar()).not.toHaveAttribute('aria-valuenow')           // sunucu aşaması belirsiz
    expect(live()).toHaveTextContent('The server is analysing')

    await up.resolve({ success: true, data: analysis() })
    await waitFor(() => expect(step()).toBe('review'))
    expect(panel()).toBeNull()
    expect(live()).toHaveTextContent('Done.')
    expect(document.querySelector('[data-slot="mcert-wizard"]')).not.toHaveAttribute('aria-busy')
  })

  it('PKCS#12: ayıklama aşamasında "şifre tabanlı çözme birkaç saniye sürebilir" notu; yavaş aşamada geçen süre', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      renderWizard()
      const ex = deferred()
      let exOpts = null
      extractCertificates.mockImplementationOnce((input, opts) => { exOpts = opts; return ex.promise })
      pick('store.pfx')
      fireEvent.change(document.querySelector('[data-slot="mcert-password"]'), { target: { value: 'p' } })
      fireEvent.click(btn(/^Analyse$/))
      await waitFor(() => expect(panel()).toBeTruthy())
      act(() => { exOpts.onProgress({ phase: 'extract' }); exOpts.onProgress({ phase: 'pkcs12', step: 'kdf' }) })
      expect(document.querySelector('[data-slot="mcert-progress-detail"]')).toHaveTextContent(/Password-based decryption is deliberately slow and can take a few seconds/)
      expect(document.querySelector('[data-slot="mcert-progress-elapsed"]')).toBeNull()
      await act(async () => { vi.advanceTimersByTime(3200) })
      expect(document.querySelector('[data-slot="mcert-progress-stage"][data-stage="extract"]')).toHaveTextContent(/In progress · \d+ s/)
      expect(document.querySelector('[data-slot="mcert-progress-cancel-note"]')).toHaveAttribute('data-cancellable', 'true')
      await ex.resolve(extraction({ format: 'PKCS12', unsupported: null, needs_password: false, password_error: true, entries: [] }))
      // Yanlış şifre: alanın altında, panel hangi aşamada durduğunu söyler (kapatılabilir)
      expect(await screen.findByText(/^The password is wrong: it didn’t open this file\./)).toBeInTheDocument()
      expect(panel()).toHaveAttribute('data-status', 'failed')
      expect(stages()).toEqual(['read:done', 'extract:error', 'upload:pending', 'analyze:pending'])
      expect(live()).toHaveTextContent('Stopped at “Extracting the certificates in your browser”')
      expect(analyzeBtn()).not.toBeDisabled()
      fireEvent.click(document.querySelector('[data-slot="mcert-progress-dismiss"]'))
      expect(panel()).toBeNull()
      expect(api.manualCerts.analyze).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })

  it('"Vazgeç" ayıklama sürerken: sinyal iptal, panel kalkar, meşgul durum KALMAZ, alanlar açılır; geç gelen sonuç yok sayılır', async () => {
    renderWizard()
    const ex = deferred()
    let exOpts = null
    extractCertificates.mockImplementationOnce((input, opts) => { exOpts = opts; return ex.promise })
    pick('paket.zip')
    fireEvent.click(btn(/^Analyse$/))
    await waitFor(() => expect(panel()).toBeTruthy())
    fireEvent.click(document.querySelector('[data-slot="mcert-cancel-run"]'))
    expect(exOpts.signal.aborted).toBe(true)
    expect(panel()).toBeNull()
    expect(analyzeBtn()).not.toBeDisabled()
    expect(analyzeBtn()).not.toHaveAttribute('aria-busy')
    expect(document.querySelector('[data-slot="mcert-cancel-run"]')).toBeNull()
    expect(document.querySelector('[data-slot="mcert-file-input"]')).not.toBeDisabled()
    const info = document.querySelector('[data-slot="mcert-banner"]')
    expect(info.querySelector('[data-slot="alert"]')).toHaveAttribute('data-tone', 'info')
    expect(within(info).getByText(/^Analysis stopped\. Nothing was saved/)).toBeInTheDocument()
    expect(live()).toHaveTextContent('Analysis stopped')
    // Geç gelen ayıklama sonucu yok sayılır: sunucuya istek gitmez, adım değişmez
    await ex.resolve(extraction())
    expect(api.manualCerts.analyze).not.toHaveBeenCalled()
    expect(step()).toBe('file')
    // Yeniden başlatılabilir
    api.manualCerts.analyze.mockResolvedValueOnce({ success: true, data: analysis() })
    fireEvent.click(btn(/^Analyse$/))
    await waitFor(() => expect(step()).toBe('review'))
  })

  it('"Vazgeç" gönderim sürerken: istek iptal edilir (signal), sonuç yok sayılır, adım Dosya\'da temiz kalır', async () => {
    renderWizard()
    const up = deferred()
    let upOpts = null
    api.manualCerts.analyze.mockImplementationOnce((fd, opts) => { upOpts = opts; return up.promise })
    pick('server.pem')
    fireEvent.click(btn(/^Analyse$/))
    await waitFor(() => expect(upOpts).toBeTruthy())
    act(() => { upOpts.onProgress({ phase: 'upload', loaded: 10, total: 100 }) })
    fireEvent.click(document.querySelector('[data-slot="mcert-cancel-run"]'))
    expect(upOpts.signal.aborted).toBe(true)
    await up.resolve({ success: false, status: 0, code: 'CANCELLED', cancelled: true })
    expect(step()).toBe('file')
    expect(panel()).toBeNull()
    expect(analyzeBtn()).not.toBeDisabled()
    expect(document.querySelector('[data-slot="field-error"]')).toBeNull()
  })
})

describe('UploadWizard — yükleme durumu (kayıt koşusu)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('Takibe al: Sunucuya gönderiliyor (%) → Kaydediliyor; istek sunucuya ulaşınca "Vazgeç" kilitlenir; Geri gizli; sonuç', async () => {
    renderWizard()
    await toTrack()
    const sv = deferred()
    let svOpts = null
    api.manualCerts.create.mockImplementationOnce((fd, opts) => { svOpts = opts; return sv.promise })
    fireEvent.click(btn(/^Start tracking$/))
    await waitFor(() => expect(panel()).toBeTruthy())
    expect(panel()).toHaveAttribute('data-kind', 'save')
    expect(stages()).toEqual(['upload:active', 'save:pending'])
    expect(within(dialog()).queryByRole('button', { name: /^Back$/ })).toBeNull()
    expect(document.querySelector('[data-slot="mcert-submit"]')).toHaveAttribute('aria-busy', 'true')
    act(() => { svOpts.onProgress({ phase: 'upload', loaded: 50, total: 100 }) })
    expect(bar()).toHaveAttribute('aria-valuenow', '50')
    expect(document.querySelector('[data-slot="mcert-cancel-run"]')).not.toBeDisabled()
    act(() => { svOpts.onProgress({ phase: 'sent' }) })
    expect(stages()).toEqual(['upload:done', 'save:active'])
    expect(document.querySelector('[data-slot="mcert-cancel-run"]')).toBeDisabled()
    expect(document.querySelector('[data-slot="mcert-progress-cancel-note"]')).toHaveTextContent('saving can’t be stopped at this point')
    fireEvent.click(document.querySelector('[data-slot="mcert-cancel-run"]'))   // kapalı: hiçbir şey olmaz
    expect(svOpts.signal.aborted).toBe(false)
    await sv.resolve({ success: true, data: { inventory_id: 12, domain: 'api.example.test', version: 1 } })
    await waitFor(() => expect(step()).toBe('result'))
    expect(panel()).toBeNull()
  })

  it('kayıt gövde giderken iptal: "gönderim durduruldu, hiçbir şey kaydedilmedi"; Takip adımı ve bilgiler korunur', async () => {
    renderWizard()
    await toTrack()
    const sv = deferred()
    let svOpts = null
    api.manualCerts.create.mockImplementationOnce((fd, opts) => { svOpts = opts; return sv.promise })
    fireEvent.click(btn(/^Start tracking$/))
    await waitFor(() => expect(svOpts).toBeTruthy())
    fireEvent.click(document.querySelector('[data-slot="mcert-cancel-run"]'))
    expect(svOpts.signal.aborted).toBe(true)
    expect(within(document.querySelector('[data-slot="mcert-banner"]'))
      .getByText(/^Sending stopped; the request was cut off before it reached the server, so nothing was saved\./)).toBeInTheDocument()
    await sv.resolve({ success: false, status: 0, code: 'CANCELLED', cancelled: true })
    expect(step()).toBe('track')
    expect(document.querySelector('[data-slot="mcert-key"]')).toHaveValue('api.example.test')
    expect(document.querySelector('[data-slot="mcert-submit"]')).not.toBeDisabled()
  })
})

describe('UploadWizard — hata iletileri: alana ait olan alanın altında, olmayan bantta', () => {
  beforeEach(() => vi.clearAllMocks())

  const bannerEl = () => document.querySelector('[data-slot="mcert-banner"]')

  it('tarayıcı ayıklama hataları alanın altında, ne olduğu + neden + yapılacak şey: boş dosya, süre aşımı, PFX yöntemi, BKS (iki komut)', async () => {
    renderWizard()
    pick('bos.pem', '')
    fireEvent.click(btn(/^Analyse$/))
    expect(await screen.findByText(/^The file is empty \(0 bytes\), so there is nothing to read\./)).toBeInTheDocument()
    extractCertificates.mockResolvedValueOnce(extraction({ entries: [], unsupported: { reason: 'TIMEOUT', seconds: 60 } }))
    pick('buyuk.zip')
    fireEvent.click(btn(/^Analyse$/))
    expect(await screen.findByText(/could not be opened in your browser within 60 seconds/)).toBeInTheDocument()
    extractCertificates.mockResolvedValueOnce(extraction({ entries: [], unsupported: { reason: 'PKCS12_ALGORITHM' } }))
    pick('eski.pfx')
    fireEvent.click(btn(/^Analyse$/))
    expect(await screen.findByText(/openssl pkcs12 -in file\.pfx -nokeys -out cert\.pem \(add -legacy/)).toBeInTheDocument()
    extractCertificates.mockResolvedValueOnce(extraction({ entries: [], unsupported: { reason: 'BKS' } }))
    pick('trust.bks')
    fireEvent.click(btn(/^Analyse$/))
    await screen.findByText(/BKS \/ UBER keystores cannot be opened in the browser/)
    expect(document.querySelector('[data-slot="mcert-bks-convert"]')).toHaveTextContent('-deststoretype PKCS12')
    expect(document.querySelector('[data-slot="mcert-bks-export"]')).toHaveTextContent('keytool -exportcert -rfc')
    expect(api.manualCerts.analyze).not.toHaveBeenCalled()
    expect(bannerEl()).toBeNull()                           // alan hatası; bant yok
  })

  it('EXTRACTED_INVALID / PRIVATE_KEY_NOT_ACCEPTED (400 + errors.file) → açıklamalı BANT (sayfayı yenile), kod "Teknik ayrıntı"da', async () => {
    renderWizard()
    api.manualCerts.analyze.mockResolvedValueOnce({ success: false, status: 400, code: 'EXTRACTED_INVALID',
      error: 'Gönderilen sertifika bilgisi geçersiz (bilinmeyen alan).', errors: { file: 'Gönderilen sertifika bilgisi geçersiz (bilinmeyen alan).' } })
    pick('server.pem')
    fireEvent.click(btn(/^Analyse$/))
    expect(await screen.findByText('The server did not accept the extracted certificates')).toBeInTheDocument()
    expect(bannerEl()).toHaveAttribute('data-code', 'EXTRACTED_INVALID')
    expect(bannerEl().querySelector('[data-slot="alert"]')).toHaveAttribute('data-tone', 'danger')
    expect(bannerEl()).toHaveTextContent(/Fully reload the page \(Ctrl\+F5\)/)
    const toggle = within(bannerEl()).getByRole('button', { name: /Technical details/ })
    fireEvent.click(toggle)
    expect(bannerEl().querySelector('[data-detail="status"]')).toHaveTextContent('400')
    expect(bannerEl().querySelector('[data-detail="code"]')).toHaveTextContent('EXTRACTED_INVALID')
    expect(document.querySelector('[data-slot="field-error"]')).toBeNull()
    api.manualCerts.analyze.mockResolvedValueOnce({ success: false, status: 400, code: 'PRIVATE_KEY_NOT_ACCEPTED', error: 'x', errors: { file: 'x' } })
    fireEvent.click(btn(/^Analyse$/))
    expect(await screen.findByText('The server rejected an upload containing a private key')).toBeInTheDocument()
  })

  it('ağ hatası (istek fırlattı): analizde "bilgiler duruyor", kayıtta "kayıt oluşmuş olabilir, önce listeyi yenileyin"', async () => {
    renderWizard()
    api.manualCerts.analyze.mockRejectedValueOnce(Object.assign(new Error('Sunucuya ulaşılamadı'), { code: 'NETWORK_ERROR' }))
    pick('server.pem')
    fireEvent.click(btn(/^Analyse$/))
    expect(await screen.findByText(/^The request didn’t reach the server or no reply came back\./)).toBeInTheDocument()
    expect(panel()).toHaveAttribute('data-status', 'failed')
    expect(stages()).toContain('upload:error')
    api.manualCerts.analyze.mockResolvedValueOnce({ success: true, data: analysis() })
    fireEvent.click(btn(/^Analyse$/))
    await waitFor(() => expect(step()).toBe('review'))
    fireEvent.click(btn(/^Next$/))
    await fillGroupAndTags({ root: dialog() })
    api.manualCerts.create.mockRejectedValueOnce(new TypeError('Network request failed'))
    fireEvent.click(btn(/^Start tracking$/))
    expect(await screen.findByText(/The request may still have reached the server and created the record: refresh the list/)).toBeInTheDocument()
    expect(bannerEl()).toHaveAttribute('data-code', 'NETWORK_ERROR')
  })

  it('kayıt: 403 (yetki), 404 (yenileme hedefi yok), 413 (çok büyük), 500 (sunucu) — her biri neden + yapılacak şeyle bantta', async () => {
    renderWizard()
    await toTrack()
    const cases = [
      [{ success: false, status: 403, error: 'Bu işlem için yetkiniz yok: inventory.crud/edit' }, /needs inventory management edit permission \(inventory\.crud\)/],
      [{ success: false, status: 413, error: 'too big' }, /larger than the server accepts \(HTTP 413\)/],
      [{ success: false, status: 500, error: 'Sunucu hatası' }, /unexpected error while processing the request \(HTTP 500\)/],
    ]
    for (const [res, re] of cases) {
      api.manualCerts.create.mockResolvedValueOnce(res)
      fireEvent.click(btn(/^Start tracking$/))
      expect(await screen.findByText(re)).toBeInTheDocument()
      expect(step()).toBe('track')
    }
  })

  it('katlanmış ref (400 errors.ref): "bu kayıtla takip edilemez" bandı + sunucunun açıklaması + "İncelemeye dön"', async () => {
    renderWizard()
    await toTrack()
    api.manualCerts.create.mockResolvedValueOnce({ success: false, status: 400, errors: {
      ref: 'This certificate is part of the chain of “api.example.test” in the file (intermediate / root); it isn’t tracked on its own.' } })
    fireEvent.click(btn(/^Start tracking$/))
    expect(await screen.findByText('The selected certificate can’t be tracked with this record')).toBeInTheDocument()
    expect(bannerEl()).toHaveTextContent('part of the chain of “api.example.test”')
    expect(bannerEl().querySelector('[data-slot="mcert-banner-hint"]')).toHaveTextContent(/Go back to the review step/)
    fireEvent.click(within(bannerEl()).getByRole('button', { name: 'Back to review' }))
    await waitFor(() => expect(step()).toBe('review'))
  })

  it('400 alan hataları (takım, takip adı) yalnız alanların altında — bant yok', async () => {
    renderWizard()
    await toTrack()
    api.manualCerts.create.mockResolvedValueOnce({ success: false, status: 400, errors: { domain: 'Geçersiz takip adı', 'inventory.team_id': 'Takım seçimi zorunludur.' } })
    fireEvent.click(btn(/^Start tracking$/))
    expect(await screen.findByText('Geçersiz takip adı')).toBeInTheDocument()
    expect(screen.getByText('Takım seçimi zorunludur.')).toBeInTheDocument()
    expect(bannerEl()).toBeNull()
  })

  it('yenileme 404 → "kayıt bulunamadı, listeyi yenileyin"; 403 → kaydın takımında yazma yetkisi', async () => {
    renderWizard({ renewTarget: { inventory_id: 5, domain: 'api.example.test' } })
    api.manualCerts.get.mockResolvedValue({ success: false })
    api.manualCerts.analyze.mockResolvedValueOnce({ success: true, data: analysis() })
    pick('server.pem')
    fireEvent.click(btn(/^Analyse$/))
    await waitFor(() => expect(step()).toBe('review'))
    fireEvent.click(btn(/^Next$/))
    await waitFor(() => expect(step()).toBe('track'))
    api.manualCerts.renew.mockResolvedValueOnce({ success: false, status: 404, error: 'Manuel sertifika kaydı bulunamadı.' })
    fireEvent.click(btn(/^Save new version$/))
    expect(await screen.findByText(/The record for the new version was not found/)).toBeInTheDocument()
    api.manualCerts.renew.mockResolvedValueOnce({ success: false, status: 403, error: 'yetki' })
    fireEvent.click(btn(/^Save new version$/))
    expect(await screen.findByText(/write access to the record’s team; another team’s record can only be viewed/)).toBeInTheDocument()
  })
})

describe('manualCertErrors — saf eşleme (her kod / durum)', () => {
  const t = (key, ...args) => {
    let s = EN[key] ?? key
    args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a)) })
    return s
  }
  const d = (res, op = 'analyze', opts) => describeUploadFailure(res, op, t, opts)

  it('kodlar ve durumlar doğru başlık / tona; metin istemcinin (neden + sonraki adım), sunucu iletisi künyede', () => {
    const table = [
      [{ thrown: true, status: 0 }, 'analyze', 'warning', 'mcert.err.networkTitle', 'mcert.err.network'],
      [{ thrown: true, status: 0 }, 'create', 'warning', 'mcert.err.networkTitle', 'mcert.err.networkSave'],
      [{ status: 400, code: 'PRIVATE_KEY_NOT_ACCEPTED' }, 'analyze', 'danger', 'mcert.err.privateKeyTitle', 'mcert.err.privateKey'],
      [{ status: 400, code: 'EXTRACTED_INVALID' }, 'create', 'danger', 'mcert.err.extractedTitle', 'mcert.err.extracted'],
      [{ status: 422, code: 'PARSE_TIMEOUT' }, 'analyze', 'warning', 'mcert.err.timeoutTitle', 'mcert.err.timeout'],
      [{ status: 429, code: 'BUSY' }, 'analyze', 'warning', 'mcert.err.busyTitle', 'mcert.err.busy'],
      [{ status: 429, code: 'RATE_LIMITED' }, 'batch', 'warning', 'mcert.err.rateLimitTitle', 'mcert.err.rateLimit'],
      [{ status: 429 }, 'renew', 'warning', 'mcert.err.rateLimitTitle', 'mcert.err.rateLimit'],
      [{ status: 403 }, 'create', 'danger', 'mcert.err.forbiddenTitle', 'mcert.err.forbidden'],
      [{ status: 403 }, 'renew', 'danger', 'mcert.err.forbiddenTitle', 'mcert.err.forbiddenRenew'],
      [{ status: 404 }, 'renew', 'danger', 'mcert.err.notFoundTitle', 'mcert.err.notFound'],
      [{ status: 413 }, 'analyze', 'danger', 'mcert.err.tooLargeTitle', 'mcert.err.tooLarge'],
    ]
    for (const [res, op, tone, title, text] of table) {
      const out = d({ success: false, error: 'sunucu iletisi', ...res }, op)
      expect(out.banner, JSON.stringify(res)).toMatchObject({ tone, title: EN[title], text: EN[text] })
      expect(out.fields).toEqual({})
    }
    expect(d({ status: 502 }).banner.text).toBe(t('mcert.err.server', 502))
  })

  it('400: görünen alana alan hatası, görünmeyen (ör. Takip adımında şifre) bantta; metin kaynağında file → text', () => {
    const res = { status: 400, errors: { domain: 'ad', password: 'şifre', 'items[1].domain': 'satır' } }
    const out = d(res, 'create', { fields: new Set(['domain']) })
    expect(out.fields).toEqual({ domain: 'ad' })
    expect(out.rows).toEqual({ 1: 'satır' })
    expect(out.banner).toMatchObject({ tone: 'danger', title: EN['mcert.err.invalidTitle'], text: 'şifre', hint: EN['mcert.err.invalidHint'] })
    const text = d({ status: 400, errors: { file: 'boş' } }, 'analyze', { fields: new Set(['text']), textSource: true })
    expect(text.fields).toEqual({ text: 'boş' })
    expect(text.banner).toBeNull()
    const ref = d({ status: 400, errors: { ref: 'zincirde' } }, 'renew', { fields: new Set(['note']) })
    expect(ref.banner).toMatchObject({ title: EN['mcert.err.refTitle'], text: 'zincirde', action: 'review' })
  })

  it('kodsuz bilinmeyen başarısızlık: sunucunun metni ya da istemcinin genel (neden + yapılacak şey) metni', () => {
    expect(d({ status: 409, code: 'OTHER', error: 'Sunucunun açıklaması' }, 'create').banner.text).toBe('Sunucunun açıklaması')
    expect(d({ status: 409 }, 'create').banner).toMatchObject({ title: EN['mcert.err.saveTitle'], text: EN['mcert.err.save'] })
    expect(d({}, 'analyze').banner).toMatchObject({ title: EN['mcert.err.analyzeTitle'], text: EN['mcert.err.analyze'] })
  })

  it('teknik künye: durum + kod + istek kimliği + sunucu iletisi; gövde / sertifika / şifre alanları ASLA', () => {
    expect(technicalDetail({ success: false, status: 409, code: 'KEY_EXISTS', error: 'var', request_id: 'r-1', password: 'x', data: { pem: 'y' } }))
      .toEqual({ status: 409, code: 'KEY_EXISTS', requestId: 'r-1', message: 'var' })
    expect(technicalDetail(null)).toBeNull()
  })

  it('sürüm silme: güncel sürüm / yok / yetki / sınır / ağ / sunucu ayrı açıklanır', () => {
    expect(describeVersionDeleteFailure({ status: 409, code: 'CURRENT_VERSION' }, t)).toBe(EN['mcert.ver.deleteCurrent'])
    expect(describeVersionDeleteFailure({ status: 404 }, t)).toBe(EN['mcert.ver.deleteNotFound'])
    expect(describeVersionDeleteFailure({ status: 403 }, t)).toBe(EN['mcert.ver.deleteForbidden'])
    expect(describeVersionDeleteFailure({ status: 429, code: 'RATE_LIMITED' }, t)).toBe(EN['mcert.ver.deleteRateLimit'])
    expect(describeVersionDeleteFailure({ thrown: true, status: 0 }, t)).toBe(EN['mcert.ver.deleteNetwork'])
    expect(describeVersionDeleteFailure({ status: 503 }, t)).toBe(t('mcert.ver.deleteServer', 503))
    expect(describeVersionDeleteFailure({ status: 400 }, t)).toBe(EN['mcert.ver.deleteFailedHint'])
  })

  it('her yeni hata / ilerleme anahtarı TR + EN sözlükte dolu (yer tutucular aynı)', () => {
    const keys = Object.keys(EN).filter((k) => /^mcert\.(err|prog|extract|ver\.delete)/.test(k))
    expect(keys.length).toBeGreaterThan(60)
    for (const k of keys) {
      expect(TR[k], k).toBeTruthy()
      const ph = (s) => (s.match(/\{\d+\}/g) || []).sort().join(',')
      expect(ph(TR[k]), k).toBe(ph(EN[k]))
    }
  })
})
