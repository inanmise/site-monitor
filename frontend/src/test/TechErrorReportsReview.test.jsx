import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview, reportResponse, templateResponse, TEMPLATE } from './helpers/techErrorFixtures.js'

// Kanal teknik hata raporu — inceleme (2026-10-13) düzeltmelerinin kapıları: kayıt sürerken yazılan korunur, şablon
// uygulama kayıttan SONRAKİ sürümü kullanır, sunucu alan hatası alan düzenlenince kalkar, anomali sayı hataları ayrı,
// geçersiz alan varken PDF inmez, yeniden yükleme hatası görünür, yedekten geri yükleme sayı kutusunu yeniler,
// bilinmeyen takım süzgeci kalkar, şablon düzenlemesi yedeklenir, boş ipucu sahte "kaydedilmemiş" göstermez.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), createChannel: vi.fn(), updateChannel: vi.fn(), deleteChannel: vi.fn(), template: vi.fn(),
      saveTemplate: vi.fn(), defaultTemplate: vi.fn(), reports: vi.fn(), createReport: vi.fn(), report: vi.fn(),
      saveReport: vi.fn(), refreshTemplate: vi.fn(), deleteReport: vi.fn(), uploadImage: vi.fn(), deleteImage: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`, send: vi.fn(), downloadPdf: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { PAGE_STATE_PREFIXES } from '../hooks/useUrlQuerySync.js'
import { PERSONAL_PREFIXES, clearPersonalStorage } from '../utils/personalStorage.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'

function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().template.mockImplementation(async (id) => templateResponse(channel(Number(id), Number(id) === 10 ? 'Mobil' : 'POS')))
})

describe('rapor düzenleyici — inceleme düzeltmeleri', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
    te().report.mockResolvedValue(reportResponse())
  })

  it('kayıt sürerken yazılan değişiklik korunur; sonraki kayıt yeni sürümle gider', async () => {
    const first = deferred()
    te().saveReport.mockImplementationOnce(() => first.promise)
    te().saveReport.mockResolvedValueOnce({ success: true, data: { version: 6, changed: true, updated_at: '2026-10-13T09:01:00Z',
      updated_by_name: 'Ayşe Yılmaz', values: { fields: { 'error-rate': 0.42, 'total-tx': 8 }, issues: {}, texts: {} } } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const input = screen.getByLabelText('Toplam işlem adedi')
    fireEvent.change(input, { target: { value: '7' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    fireEvent.change(input, { target: { value: '8' } })              // kayıt sürerken
    first.resolve({ success: true, data: { version: 5, changed: true, updated_at: '2026-10-13T09:00:00Z',
      updated_by_name: 'Ayşe Yılmaz', values: { fields: { 'error-rate': 0.42, 'total-tx': 7 }, issues: {}, texts: {} } } })
    expect(await screen.findByText('Kaydedilmemiş değişiklikler var')).toBeInTheDocument()
    expect(screen.getByLabelText('Toplam işlem adedi')).toHaveValue('8')
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(2))
    const [, values, version] = te().saveReport.mock.calls[1]
    expect(version).toBe(5)
    expect(values.fields['total-tx']).toBe(8)
    expect(await screen.findByText('Tüm değişiklikler kaydedildi')).toBeInTheDocument()
  })

  it('şablonu uygula: önce kaydet, sonra TAZE önizleme ve kayıttan sonraki sürüm', async () => {
    te().report
      .mockResolvedValueOnce(reportResponse({ template_update: { differs: true, lost_values: 1, lost_images: 0 } }))
      .mockResolvedValueOnce(reportResponse({ report: { ...reportResponse().data.report, version: 5 },
        template_update: { differs: true, lost_values: 3, lost_images: 2 } }))
      .mockResolvedValue(reportResponse({ report: { ...reportResponse().data.report, version: 6 } }))
    te().saveReport.mockResolvedValue({ success: true, data: { version: 5, changed: true, updated_at: '2026-10-13T09:00:00Z',
      values: { fields: { 'error-rate': 0.42, 'total-tx': 7 }, issues: {}, texts: {} } } })
    te().refreshTemplate.mockResolvedValue({ success: true, data: { version: 6, removed_values: 3, removed_images: 2 } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '7' } })
    fireEvent.click(screen.getByRole('button', { name: 'Şablonun son hâlini uygula' }))
    const first = await screen.findByRole('dialog', { name: 'Önce kaydedilsin mi?' })
    fireEvent.click(within(first).getByRole('button', { name: 'Kaydet ve devam et' }))
    const dlg = await screen.findByRole('dialog', { name: 'Şablonun son hâli uygulansın mı?' })
    expect(within(dlg).getByText(/3 değer ve 2 görsel/)).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Uygula' }))
    await waitFor(() => expect(te().refreshTemplate).toHaveBeenCalledWith('100', 5))
  })

  it('sunucu alan hatası alanın altında; alan düzenlenince kalkar ve kaydı kilitlemez', async () => {
    te().saveReport
      .mockResolvedValueOnce({ success: false, code: 'VALIDATION_FAILED', field: 'values.total-tx',
        error: 'Toplam işlem adedi negatif olamaz; sıfır ya da pozitif bir sayı yazın.' })
      .mockResolvedValueOnce({ success: true, data: { version: 5, changed: true, updated_at: '2026-10-13T09:00:00Z',
        values: { fields: { 'error-rate': 0.42, 'total-tx': 9 }, issues: {}, texts: {} } } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const input = screen.getByLabelText('Toplam işlem adedi')
    fireEvent.change(input, { target: { value: '7' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    const err = await screen.findAllByText('Toplam işlem adedi negatif olamaz; sıfır ya da pozitif bir sayı yazın.')
    expect(err.length).toBeGreaterThan(0)
    fireEvent.change(input, { target: { value: '9' } })
    await waitFor(() => expect(screen.queryByRole('alert', { name: /negatif olamaz/ })).toBeNull())
    expect(input).not.toHaveAttribute('aria-invalid')
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(2))
  })

  it('anomali: müşteri ve hata adedi hataları ayrı; biri düzelince diğerininki kalır', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'Başlık ekle' }))
    const customers = screen.getByLabelText('Etkilenen müşteri sayısı')
    const errors = screen.getByLabelText('Bu sorundan kaynaklı teknik hata adedi')
    fireEvent.change(customers, { target: { value: 'çok' } })
    expect(await screen.findByText('Geçerli bir sayı yazın (ör. 1.250.000 ya da 0,42).')).toBeInTheDocument()
    fireEvent.change(errors, { target: { value: '12' } })
    expect(screen.getByText('Geçerli bir sayı yazın (ör. 1.250.000 ya da 0,42).')).toBeInTheDocument()
    expect(customers).toHaveAttribute('aria-invalid', 'true')
    expect(errors).not.toHaveAttribute('aria-invalid')
    // Konu silinince hatası da gider (kayıt kilidi kalmaz)
    fireEvent.click(screen.getByRole('button', { name: /Başlığı kaldır|kaldır/i }))
    await waitFor(() => expect(screen.queryByText('Geçerli bir sayı yazın (ör. 1.250.000 ya da 0,42).')).toBeNull())
  })

  it('geçersiz alan varken PDF inmez (değer değişmediği için "kaydedilmemiş" görünmese de)', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '12abc' } })
    expect(screen.getByText('Tüm değişiklikler kaydedildi')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'PDF indir' }))
    expect(await screen.findByText('Bazı alanlarda geçersiz değer var; işaretli alanları düzeltin.')).toBeInTheDocument()
    expect(te().downloadPdf).not.toHaveBeenCalled()
  })

  it('yeniden yükleme hatası görünür (son okunan hâl ekranda kalır)', async () => {
    te().report
      .mockResolvedValueOnce(reportResponse())
      .mockResolvedValueOnce({ success: false, status: 503, error: 'Sunucu şu anda yanıt vermiyor; birkaç dakika sonra deneyin.' })
    te().send.mockResolvedValue({ success: true, data: { state: 'SENT', to: ['dijital@example.com'] } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'Takıma gönder' }))
    const dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
    expect(await screen.findByText(/Sayfa yenilenemedi; son okunan hâli görüyorsunuz/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: TITLE })).toBeInTheDocument()
  })

  it('yedekten geri yükleme sayı kutusunu yeniler ve eski geçersiz metni / hatayı siler', async () => {
    localStorage.setItem('sm.te.draft.100', JSON.stringify({ version: 4, at: 1,
      values: { fields: { 'error-rate': 0.42, 'total-tx': 9 }, issues: {}, texts: {} } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const input = screen.getByLabelText('Toplam işlem adedi')
    fireEvent.change(input, { target: { value: 'x' } })
    expect(await screen.findByText('Geçerli bir sayı yazın (ör. 1.250.000 ya da 0,42).')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Geri yükle' }))
    await waitFor(() => expect(screen.getByLabelText('Toplam işlem adedi')).toHaveValue('9'))
    expect(screen.queryByText('Geçerli bir sayı yazın (ör. 1.250.000 ya da 0,42).')).toBeNull()
  })

  it('konu ekleyip silmek sahte "kaydedilmemiş" göstermez', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'Başlık ekle' }))
    expect(screen.getByText('Kaydedilmemiş değişiklikler var')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /kaldır/i }))
    expect(await screen.findByText('Tüm değişiklikler kaydedildi')).toBeInTheDocument()
  })
})

describe('rapor düzenleyici — 2. inceleme turu', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
    te().report.mockResolvedValue(reportResponse())
  })

  it('metin bölümünün uzun varsayılanı 300 karakterde kesilmez (PDF ile aynı metin)', async () => {
    const long = 'Yıllık trend ve detay grafikleri raporun altında paylaşılacak. '.repeat(10)
    const tpl = { ...TEMPLATE, sections: TEMPLATE.sections.map((x) => (x.kind === 'text' ? { ...x, text: long } : x)) }
    te().report.mockResolvedValue(reportResponse({ template: tpl }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(long.length).toBeGreaterThan(300)
    expect(screen.getByLabelText('Grafikler hakkında')).toHaveValue(long)
  })

  it('kaydedilmemiş taslak varken kayıt başkasınca değiştiyse taban ilerlemez: çakışma uyarısı, kayıt eski sürümle gider', async () => {
    te().report
      .mockResolvedValueOnce(reportResponse())
      .mockResolvedValueOnce({ success: false, status: 503, error: 'Sunucu şu anda yanıt vermiyor; birkaç dakika sonra deneyin.' })
      .mockResolvedValueOnce(reportResponse({ report: { ...reportResponse().data.report, version: 9 },
        values: { fields: { 'error-rate': 0.9 }, issues: {}, texts: {} } }))
    te().send.mockResolvedValue({ success: true, data: { state: 'SENT', to: ['dijital@example.com'] } })
    te().saveReport.mockResolvedValue({ success: false, code: 'CONCURRENT_MODIFICATION', status: 409, error: 'x' })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'Takıma gönder' }))
    const dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
    expect(await screen.findByText(/Sayfa yenilenemedi/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '7' } })   // kaydedilmemiş
    fireEvent.click(screen.getByRole('button', { name: 'Tekrar dene' }))
    expect(await screen.findByText('Rapor siz düzenlerken değişti')).toBeInTheDocument()
    expect(screen.getByLabelText('Toplam işlem adedi')).toHaveValue('7')                        // taslak korunur
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalled())
    expect(te().saveReport.mock.calls[0][2]).toBe(4)                                             // taban 9'a ilerlemedi
  })

  it('yüzde 1000 gruplamasız yazılır ve aynen geri okunur', async () => {
    te().report.mockResolvedValue(reportResponse({ values: { fields: { 'error-rate': 1000 }, issues: {}, texts: {} } }))
    te().saveReport.mockResolvedValue({ success: true, data: { version: 5, changed: true, updated_at: '2026-10-13T09:00:00Z',
      values: { fields: { 'error-rate': 1200 }, issues: {}, texts: {} } } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const input = screen.getByLabelText('Teknik hata oranı')
    expect(input).toHaveValue('1000')
    fireEvent.change(input, { target: { value: '1200' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalled())
    expect(te().saveReport.mock.calls[0][1].fields['error-rate']).toBe(1200)
  })

  it('yarım yazım ("-") yazarken hata göstermez; alan bırakılınca hâlâ yarımsa hata', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const input = screen.getByLabelText('Teknik hata oranı')
    fireEvent.change(input, { target: { value: '-' } })
    expect(screen.queryByText('Geçerli bir sayı yazın (ör. 1.250.000 ya da 0,42).')).toBeNull()
    expect(input).toHaveValue('-')
    fireEvent.blur(input)
    expect(await screen.findByText('Geçerli bir sayı yazın (ör. 1.250.000 ya da 0,42).')).toBeInTheDocument()
    fireEvent.change(input, { target: { value: '-0,5' } })
    await waitFor(() => expect(screen.queryByText('Geçerli bir sayı yazın (ör. 1.250.000 ya da 0,42).')).toBeNull())
  })
})

describe('rapor düzenleyici — 3. inceleme turu', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
    te().report.mockResolvedValue(reportResponse())
  })

  it('sayfadan çıkarken son saniye yedeklenir; oturum kapanmışsa (sahip yok) yazılmaz', async () => {
    localStorage.setItem('sm.storage.owner', 'ayse')
    const first = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '7' } })
    first.unmount()
    expect(JSON.parse(localStorage.getItem('sm.te.draft.100')).values.fields['total-tx']).toBe(7)

    localStorage.removeItem('sm.te.draft.100')
    localStorage.removeItem('sm.storage.owner')            // çıkış: kişisel kayıtlar silindi
    const second = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '8' } })
    second.unmount()
    expect(localStorage.getItem('sm.te.draft.100')).toBeNull()
  })

  it('kuyruktaki gönderim "kuyrukta" rozetiyle görünür, "gönderildi" (yeşil) değil', async () => {
    te().report.mockResolvedValue(reportResponse({ report: { ...reportResponse().data.report, status: 'queued',
      send_state: 'QUEUED', last_send_status: 'queued' } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(screen.getAllByText('Kuyrukta — teslim bekleniyor').length).toBeGreaterThan(0)
    expect(screen.queryByText('Gönderildi')).toBeNull()
  })
})

// 2026-10-14 (kullanıcı isteği): "kesinti yok işareti koyarsa bu alan muaf olsun" — kesinti alanları sorusuna bağlı
const OUTAGE_TPL = {
  ...TEMPLATE,
  sections: [
    { id: 'summary', kind: 'metrics', title: '{kanal} Kanalı — {ay} {yil} Özeti', fields: [
      { id: 'error-rate', label: 'Teknik hata oranı', type: 'percent' },
      { id: 'had-outage', label: 'Dönemde kesinti oldu mu?', type: 'yesno' },
      { id: 'error-rate-excl', label: 'Kesinti günleri hariç teknik hata oranı', type: 'percent', skip_if_no: 'had-outage' },
      { id: 'outage-errors', label: 'Kesinti kaynaklı teknik hata adedi', type: 'number', skip_if_no: 'had-outage' },
      { id: 'outage-share', label: 'Kesinti kaynaklı hataların toplam hataya oranı', type: 'percent', skip_if_no: 'had-outage' },
    ] },
    ...TEMPLATE.sections.slice(1),
  ],
}

describe('kesinti yok işareti — bağlı alanlar muaf', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  })

  it('cevapsızken kesinti alanları istenir; "Hayır" seçilince üçü gizlenir ve muaf notu çıkar; "Evet" geri getirir', async () => {
    te().report.mockResolvedValue(reportResponse({ template: OUTAGE_TPL, values: { fields: {}, issues: {}, texts: {} } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(screen.getByLabelText('Kesinti günleri hariç teknik hata oranı')).toBeInTheDocument()
    expect(screen.getByLabelText('Kesinti kaynaklı teknik hata adedi')).toBeInTheDocument()
    const q = screen.getByLabelText('Dönemde kesinti oldu mu?')
    fireEvent.click(within(q).getByRole('radio', { name: 'Hayır' }))
    await waitFor(() => expect(screen.queryByLabelText('Kesinti günleri hariç teknik hata oranı')).toBeNull())
    expect(screen.queryByLabelText('Kesinti kaynaklı teknik hata adedi')).toBeNull()
    expect(screen.queryByLabelText('Kesinti kaynaklı hataların toplam hataya oranı')).toBeNull()
    expect(screen.getByText(/buna bağlı 3 alan bu rapordan muaf/)).toBeInTheDocument()
    expect(screen.getByLabelText('Teknik hata oranı')).toBeInTheDocument()
    fireEvent.click(within(q).getByRole('radio', { name: 'Evet' }))
    expect(await screen.findByLabelText('Kesinti günleri hariç teknik hata oranı')).toBeInTheDocument()
  })

  it('gizlenen alanın geçersiz yazımı kaydı kilitlemez; kayıt "Hayır" ile gider', async () => {
    te().report.mockResolvedValue(reportResponse({ template: OUTAGE_TPL, values: { fields: {}, issues: {}, texts: {} } }))
    te().saveReport.mockResolvedValue({ success: true, data: { version: 5, changed: true, updated_at: '2026-10-14T09:00:00Z',
      values: { fields: { 'had-outage': false }, issues: {}, texts: {} } } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(screen.getByLabelText('Kesinti kaynaklı teknik hata adedi'), { target: { value: 'çok' } })
    expect(await screen.findByText('Geçerli bir sayı yazın (ör. 1.250.000 ya da 0,42).')).toBeInTheDocument()
    fireEvent.click(within(screen.getByLabelText('Dönemde kesinti oldu mu?')).getByRole('radio', { name: 'Hayır' }))
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalled())
    expect(te().saveReport.mock.calls[0][1].fields['had-outage']).toBe(false)
  })

  it('"Hayır" iken gizli alanın değeri gönderilmez (sunucu katı doğrulamaz); taslakta kalır, "Evet" geri getirir', async () => {
    te().report.mockResolvedValue(reportResponse({ template: OUTAGE_TPL, values: { fields: {}, issues: {}, texts: {} } }))
    te().saveReport.mockResolvedValue({ success: false, code: 'CONCURRENT_MODIFICATION', status: 409, error: 'x' })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(screen.getByLabelText('Kesinti kaynaklı teknik hata adedi'), { target: { value: '12' } })
    const q = screen.getByLabelText('Dönemde kesinti oldu mu?')
    fireEvent.click(within(q).getByRole('radio', { name: 'Hayır' }))
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalled())
    const sent = te().saveReport.mock.calls[0][1].fields
    expect(sent['had-outage']).toBe(false)
    expect(sent).not.toHaveProperty('outage-errors')
    fireEvent.click(within(q).getByRole('radio', { name: 'Evet' }))
    expect(await screen.findByLabelText('Kesinti kaynaklı teknik hata adedi')).toHaveValue('12')
  })

  it('bölümün tüm alanları muafsa boş kart yerine not', async () => {
    const tpl = { ...OUTAGE_TPL, sections: [OUTAGE_TPL.sections[0],
      { id: 'detail', kind: 'metrics', title: 'Kesinti Ayrıntıları', fields: [
        { id: 'outage-days', label: 'Kesinti gün sayısı', type: 'number', skip_if_no: 'had-outage' }] },
      ...OUTAGE_TPL.sections.slice(1)] }
    te().report.mockResolvedValue(reportResponse({ template: tpl, values: { fields: { 'had-outage': false }, issues: {}, texts: {} } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(screen.getByText('Bu bölümün alanları “Hayır” cevabıyla muaf; PDF\'e girmez.')).toBeInTheDocument()
    expect(screen.getByText(/buna bağlı 4 alan bu rapordan muaf/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Kesinti gün sayısı')).toBeNull()
  })

  it('temiz taslak: başka sekmede uygulanan şablon yeni bağ eklese de sahte çakışma çıkmaz, taze hâl alınır', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
    const linked = { ...TEMPLATE, sections: [{ ...TEMPLATE.sections[0], fields: [
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number', skip_if_no: 'sla-above' },
      { id: 'sla-above', label: 'SLA hedefin üstünde mi?', type: 'yesno' },
    ] }, ...TEMPLATE.sections.slice(1)] }
    te().report
      .mockResolvedValueOnce(reportResponse({ values: { fields: { 'error-rate': 0.42, 'total-tx': 5, 'sla-above': false }, issues: {}, texts: {} } }))
      .mockResolvedValue(reportResponse({ report: { ...reportResponse().data.report, version: 9 }, template: linked,
        values: { fields: { 'sla-above': false }, issues: {}, texts: {} } }))
    te().send.mockResolvedValue({ success: true, data: { state: 'SENT', to: ['dijital@example.com'] } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'Takıma gönder' }))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).getByRole('button', { name: 'Gönder' }))
    await waitFor(() => expect(te().report).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByLabelText('Toplam işlem adedi')).toBeNull())   // yeni bağla muaf
    expect(screen.queryByText('Rapor siz düzenlerken değişti')).toBeNull()
    expect(screen.getByText('Tüm değişiklikler kaydedildi')).toBeInTheDocument()
  })

  it('şablon: alan bir evet/hayır sorusuna bağlanır, kayda skip_if_no gider', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
    te().saveTemplate.mockResolvedValue({ success: true, data: { template: TEMPLATE, version: 4 } })
    render(<TechErrorReportsPage />)
    await screen.findByText('Rapor başlığı ve dili')
    // TEMPLATE: error-rate (yüzde), total-tx (sayı), sla-above (evet/hayır) → yüzde ve sayı alanı soruya bağlanabilir
    const selects = screen.getAllByLabelText('Bu soru “Hayır” ise alanı sorma')
    expect(selects).toHaveLength(2)
    fireEvent.change(selects[1], { target: { value: 'sla-above' } })
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalled())
    const sent = te().saveTemplate.mock.calls[0][1]
    expect(sent.sections[0].fields.find((f) => f.id === 'total-tx').skip_if_no).toBe('sla-above')
  })
})

describe('kanallar ve şablon — inceleme düzeltmeleri', () => {
  it('sayfa durumu paramları sekme değişince temizlenir (te_ ailesi kayıtlı); taslak yedekleri kişisel kayıt', () => {
    for (const k of ['te_view', 'te_ch', 'te_tab', 'te_rep', 'te_team']) {
      expect(PAGE_STATE_PREFIXES.some((p) => k.startsWith(p))).toBe(true)
    }
    expect(PERSONAL_PREFIXES).toEqual(expect.arrayContaining(['sm.te.draft.', 'sm.te.tpl.']))
    localStorage.setItem('sm.te.draft.100', '{}')
    localStorage.setItem('sm.te.tpl.10', '{}')
    clearPersonalStorage()
    expect(localStorage.getItem('sm.te.draft.100')).toBeNull()
    expect(localStorage.getItem('sm.te.tpl.10')).toBeNull()
  })

  it('görünmeyen takımın süzgeci kendiliğinden kalkar (boş sayfada mahsur kalınmaz)', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_team=999')
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(te().overview).toHaveBeenLastCalledWith(null))
    expect(te().overview).toHaveBeenCalledWith('999')
    await waitFor(() => expect(window.location.search).not.toContain('te_team'))
  })

  it('şablon: boş bırakılan ipucu sahte değişiklik sayılmaz; çakışmada düzenleme yedeklenir ve geri yüklenebilir', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
    te().saveTemplate.mockResolvedValueOnce({ success: false, code: 'CONCURRENT_MODIFICATION', status: 409, error: 'x' })
    render(<TechErrorReportsPage />)
    await screen.findByText('Rapor başlığı ve dili')
    const save = screen.getByRole('button', { name: 'Şablonu kaydet' })
    const hint = screen.getAllByLabelText('Açıklama (isteğe bağlı, alanın altında görünür)')[1]   // ipucusuz alan
    fireEvent.change(hint, { target: { value: 'x' } })
    expect(save).not.toBeDisabled()
    fireEvent.change(hint, { target: { value: '' } })
    expect(save).toBeDisabled()

    fireEvent.change(screen.getAllByLabelText('Alan adı')[0], { target: { value: 'Teknik hata oranı (aylık)' } })
    fireEvent.click(save)
    expect(await screen.findByText('Şablon siz düzenlerken değişti')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'En son hâli yükle' }))
    expect(await screen.findByText('Kaydedilmemiş bir şablon düzenlemesi bulundu')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Geri yükle' }))
    expect(screen.getAllByLabelText('Alan adı')[0]).toHaveValue('Teknik hata oranı (aylık)')
    expect(JSON.parse(localStorage.getItem('sm.te.tpl.10')).template.sections[0].fields[0].label).toBe('Teknik hata oranı (aylık)')
  })

  it('sabit şablon nesnesi değişmez', () => {
    expect(TEMPLATE.sections[0].fields[0].label).toBe('Teknik hata oranı')
  })
})
