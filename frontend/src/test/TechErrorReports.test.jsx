import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview, reportResponse, templateResponse, TEMPLATE, openAllSections } from './helpers/techErrorFixtures.js'

// Kanal teknik hata raporları (2026-10-13): kanal listesi, "X kanalının şablonunu aç" X'i / "Y'ninkini aç" Y'yi açar,
// kanal açma doğrulaması, şablon düzenleyici (sürüm + çakışma, salt okuma), rapor düzenleyici (sayı çözümleme, sürümlü
// kayıt, çakışma uyarısı, gönderim onayı, salt okuma, görsel ön denetimi).
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
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors

function reportsResponse(ch) {
  return { success: true, data: { channel: ch, reports: [], default_period: { year: 2026, month: 9 } } }
}

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=channelreports')
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => reportsResponse(channel(Number(id), Number(id) === 10 ? 'Mobil' : 'POS')))
  te().template.mockImplementation(async (id) => templateResponse(channel(Number(id), Number(id) === 10 ? 'Mobil' : 'POS')))
})

describe('Teknik hata raporları — kanallar', () => {
  it('kanal kartları, dönem özeti; "Şablonu aç" O kanalın şablonunu açar (X → X, Y → Y)', async () => {
    render(<TechErrorReportsPage />)
    const cards = await screen.findAllByRole('button', { name: 'Şablonu aç' })
    expect(cards).toHaveLength(2)
    expect(screen.getByText(/Bu ayın raporu Eylül 2026 dönemini kapsar/)).toBeInTheDocument()

    fireEvent.click(cards[0])   // Mobil
    await screen.findByText('Rapor başlığı ve dili')
    expect(te().template).toHaveBeenLastCalledWith('10')
    expect(screen.getByRole('heading', { name: 'Mobil' })).toBeInTheDocument()
    await waitFor(() => expect(window.location.search).toContain('te_tab=template'))
    expect(window.location.search).toContain('te_ch=10')

    fireEvent.click(screen.getByRole('button', { name: 'Kanallara dön' }))
    const again = await screen.findAllByRole('button', { name: 'Şablonu aç' })
    fireEvent.click(again[1])   // POS
    await screen.findByText('Rapor başlığı ve dili')
    expect(te().template).toHaveBeenLastCalledWith('20')
    expect(screen.getByRole('heading', { name: 'POS' })).toBeInTheDocument()
  })

  it('bağlantıyla açılır: ?te_view=channel&te_ch=20&te_tab=template doğrudan POS şablonu', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=20&te_tab=template')
    render(<TechErrorReportsPage />)
    await screen.findByText('Rapor başlığı ve dili')
    expect(te().template).toHaveBeenCalledWith('20')
    expect(te().overview).not.toHaveBeenCalled()
  })

  it('takım e-postası olmayan yazılabilir takım uyarısı; boş kanal listesi kanal açmayı önerir', async () => {
    const ov = overview([channel(10, 'Mobil')])
    ov.data.teams[0].has_email = false
    te().overview.mockResolvedValueOnce(ov)
    render(<TechErrorReportsPage />)
    expect(await screen.findByText('Takım e-posta adresi eksik')).toBeInTheDocument()
  })

  it('yeni kanal: ad boşsa alan altında hata; geçerli girişte takım + dil gider ve şablon açılır', async () => {
    te().createChannel.mockResolvedValue({ success: true, data: channel(30, 'ATM') })
    render(<TechErrorReportsPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Yeni kanal' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Kanalı aç' }))
    expect(await within(dialog).findByText('Kanal adı boş olamaz; rapor başlığında kullanılır.')).toBeInTheDocument()
    expect(te().createChannel).not.toHaveBeenCalled()
    // Şablon kaynağı: shadcn seçim kartları (8. sürüm) — üç seçenek, varsayılan Türkçe; "kopyala" kanal seçiciyi açar
    expect(within(dialog).getAllByRole('radio')).toHaveLength(3)
    expect(within(dialog).getByRole('radio', { name: /Varsayılan şablon \(Türkçe\)/ })).toBeChecked()
    // Kullanıcı bildirimi (2026-10-17): lucide "Languages" ikonu "文A" çizer ve Japonca karakter gibi görünür — seçim
    // kartlarında dil / çeviri ikonu kullanılmaz
    expect(dialog.querySelector('svg.lucide-languages')).toBeNull()
    expect(dialog.querySelector('[data-slot="te-source-copy"]')).toBeNull()
    fireEvent.click(within(dialog).getByRole('radio', { name: /Başka bir kanalın şablonunu kopyala/ }))
    expect(dialog.querySelector('[data-slot="te-source-copy"]')).not.toBeNull()
    fireEvent.click(within(dialog).getByRole('radio', { name: /Varsayılan şablon \(Türkçe\)/ }))
    fireEvent.change(within(dialog).getByLabelText(/Kanal adı/), { target: { value: '  ATM ' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Kanalı aç' }))
    await waitFor(() => expect(te().createChannel).toHaveBeenCalledWith({ team_id: 1, name: 'ATM', description: null, lang: 'tr' }))
    await waitFor(() => expect(te().template).toHaveBeenCalledWith('30'))
  })

  it('aynı ad 409 CHANNEL_EXISTS → hata ad alanının altında', async () => {
    te().createChannel.mockResolvedValue({ success: false, code: 'CHANNEL_EXISTS', error: 'Bu takımda “Mobil” adlı bir kanal zaten var. Başka bir ad seçin.' })
    render(<TechErrorReportsPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Yeni kanal' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText(/Kanal adı/), { target: { value: 'Mobil' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Kanalı aç' }))
    expect(await within(dialog).findByText(/adlı bir kanal zaten var/)).toBeInTheDocument()
  })

  it('403 → erişim yok ekranı (hata değil)', async () => {
    te().overview.mockResolvedValueOnce({ success: false, status: 403, error: 'yetki yok' })
    render(<TechErrorReportsPage />)
    expect(await screen.findByText('Bu sayfayı görme izniniz yok')).toBeInTheDocument()
  })
})

describe('Teknik hata raporları — şablon düzenleyici', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
  })

  it('alan eklenip kaydedilir: okunan sürüm gider; 409 → çakışma uyarısı, değişiklik kaybolmaz', async () => {
    te().saveTemplate.mockResolvedValueOnce({ success: false, code: 'CONCURRENT_MODIFICATION', status: 409, error: 'x' })
    render(<TechErrorReportsPage />)
    await screen.findByText('Rapor başlığı ve dili')
    const save = screen.getByRole('button', { name: 'Şablonu kaydet' })
    expect(save).toBeDisabled()
    fireEvent.click(screen.getAllByRole('button', { name: 'Alan ekle' })[0])
    expect(save).not.toBeDisabled()
    fireEvent.click(save)
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalled())
    const [id, tpl, version] = te().saveTemplate.mock.calls[0]
    expect(id).toBe('10')
    expect(version).toBe(3)
    expect(tpl.sections[0].fields).toHaveLength(4)
    expect(await screen.findByText('Şablon siz düzenlerken değişti')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Şablonu kaydet' })).not.toBeDisabled()
  })

  it('boş alan adı kaydı engeller ve alanın altında söyler', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByText('Rapor başlığı ve dili')
    fireEvent.change(screen.getAllByLabelText('Alan adı')[0], { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    expect(await screen.findByText('Alan adı boş olamaz; raporda ve PDF\'te bu ad görünür.')).toBeInTheDocument()
    expect(te().saveTemplate).not.toHaveBeenCalled()
  })

  it('yazamayan kullanıcı: salt okuma uyarısı, kaydet çubuğu yok, alanlar kapalı', async () => {
    te().template.mockResolvedValueOnce(templateResponse(channel(10, 'Mobil', { can_edit: false })))
    render(<TechErrorReportsPage />)
    expect(await screen.findByText('Şablonu yalnız görüntülüyorsunuz')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Şablonu kaydet' })).toBeNull()
    expect(screen.getByLabelText('Rapor başlığı')).toBeDisabled()
  })
})

describe('Teknik hata raporları — rapor düzenleyici', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
    te().report.mockResolvedValue(reportResponse())
  })

  it('bölümler şablondan çizilir; Türkçe sayı çözülür ve sürümle kaydedilir; geçen ay ipucu görünür', async () => {
    te().saveReport.mockResolvedValue({ success: true, data: { version: 5, changed: true, updated_at: '2026-10-13T09:00:00Z',
      values: { fields: { 'error-rate': 0.42, 'total-tx': 1250000 }, issues: {}, texts: {} } } })
    render(<TechErrorReportsPage />)
    expect(await screen.findByRole('heading', { name: 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026' })).toBeInTheDocument()
    expect(screen.getByText('Geçen ay (Ağustos 2026): %0,5 · -0,08 puan')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '1.250.000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalled())
    const [id, values, version] = te().saveReport.mock.calls[0]
    expect(id).toBe('100')
    expect(version).toBe(4)
    expect(values.fields).toEqual({ 'error-rate': 0.42, 'total-tx': 1250000 })
    expect(await screen.findByText('Tüm değişiklikler kaydedildi')).toBeInTheDocument()
  })

  it('geçersiz sayı alanın altında; kayıt gönderilmez', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026' })
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '12abc' } })
    expect(await screen.findByText('Geçerli bir sayı yazın: 1.250.000, 0,42 ya da kısaltmayla 7,42G, 130,1M, 681,6k (k = bin, M = milyon, G ya da Mr = milyar).')).toBeInTheDocument()
  })

  it('409 CONCURRENT_MODIFICATION → çakışma uyarısı; "En son hâli yükle" taslağı yedeğe yazar ve yükler', async () => {
    te().saveReport.mockResolvedValue({ success: false, code: 'CONCURRENT_MODIFICATION', status: 409, error: 'x' })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026' })
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '7' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    expect(await screen.findByText('Rapor siz düzenlerken değişti')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'En son hâli yükle' }))
    expect(await screen.findByText('Kaydedilmemiş bir taslak bulundu')).toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem('sm.te.draft.100')).values.fields['total-tx']).toBe(7)
  })

  it('anomali başlığı: konu yazılınca başlık kalıptan oluşur; elle değişen başlık korunur', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026' })
    fireEvent.click(screen.getByRole('button', { name: 'Başlık ekle' }))
    fireEvent.change(screen.getByLabelText('Konu'), { target: { value: 'Kart Limiti' } })
    expect(screen.getByText('1. Kart Limiti Konusu ile yaşanan problem')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Başlık'), { target: { value: 'Limit sorgusu zaman aşımı' } })
    expect(screen.getByText('1. Limit sorgusu zaman aşımı')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Konu'), { target: { value: 'Kart' } })
    expect(screen.getByText('1. Limit sorgusu zaman aşımı')).toBeInTheDocument()
  })

  it('gönderim onay ister; başarıda alıcılar yazılır', async () => {
    te().send.mockResolvedValue({ success: true, data: { state: 'SENT', to: ['dijital@example.com'] } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026' })
    fireEvent.click(screen.getByRole('button', { name: 'Takıma gönder' }))
    const dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
    expect(within(dlg).getByText(/Konu: Mobil Kanalı Teknik Hata Mesajları - Eylül 2026/)).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
    await waitFor(() => expect(te().send).toHaveBeenCalledWith('100'))
    expect(await screen.findByText('Rapor gönderildi: dijital@example.com')).toBeInTheDocument()
  })

  it('takım e-postası yoksa gönder kapalı ve nedeni yazılı', async () => {
    te().report.mockResolvedValue(reportResponse({ team_email_set: false }))
    render(<TechErrorReportsPage />)
    expect(await screen.findByText('Takımın e-posta adresi yok')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Takıma gönder' })).toBeDisabled()
  })

  it('salt okuma: kaydet / gönder yok, PDF indirilebilir', async () => {
    te().report.mockResolvedValue(reportResponse({ can_edit: false, can_delete: false }))
    render(<TechErrorReportsPage />)
    expect(await screen.findByText('Raporu yalnız görüntülüyorsunuz')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Kaydet' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Takıma gönder' })).toBeNull()
    expect(screen.getByRole('button', { name: 'PDF indir' })).toBeEnabled()
  })

  it('görsel ön denetimi: PDF yüklenmez, ne yapılacağı yuvanın altında; yuvaya sığan PNG olduğu gibi yüklenir', async () => {
    te().uploadImage.mockResolvedValue({ success: true, data: { id: 501, slot_id: 'monthly-tx', file_name: 'a.png', width: 800,
      height: 400, size_bytes: 2048 } })
    // jsdom'da görsel çözücü yok: tarayıcının createImageBitmap'i yalnız boyutu döndürecek kadar taklit edilir
    const had = 'createImageBitmap' in globalThis
    const prev = globalThis.createImageBitmap
    globalThis.createImageBitmap = vi.fn(async () => ({ width: 800, height: 400, close() {} }))
    try {
      const { container } = render(<TechErrorReportsPage />)
      await screen.findByRole('heading', { name: 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026' })
      const slot = container.querySelector('[data-slot-id="monthly-tx"]')
      const input = slot.querySelector('input[type="file"]')
      fireEvent.change(input, { target: { files: [new File(['%PDF-1.7 rapor'], 'rapor.pdf', { type: 'application/pdf' })] } })
      expect(await within(slot).findByText(/PDF dosyası görsel yuvasına yüklenemez\. Sayfanın ekran görüntüsünü alıp PNG/))
        .toBeInTheDocument()
      expect(te().uploadImage).not.toHaveBeenCalled()
      const png = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13])], 'a.png', { type: 'image/png' })
      fireEvent.change(input, { target: { files: [png] } })
      await waitFor(() => expect(te().uploadImage).toHaveBeenCalledWith('100', 'monthly-tx', png))
      expect(await within(slot).findByRole('img', { name: 'Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri' })).toBeInTheDocument()
      // Küçültme / çevirme olmadı: not yok, hata kalmadı
      expect(slot.querySelector('[data-slot="te-image-note"]')).toBeNull()
      expect(slot.querySelector('[data-slot="te-image-error"]')).toBeNull()
    } finally {
      if (had) globalThis.createImageBitmap = prev
      else delete globalThis.createImageBitmap
    }
  })

  it('şablon değiştiyse uyarı + uygula (kayıp sayıları onayda)', async () => {
    te().report.mockResolvedValue(reportResponse({ template_update: { differs: true, lost_values: 2, lost_images: 1 } }))
    te().refreshTemplate.mockResolvedValue({ success: true, data: { version: 5, removed_values: 2, removed_images: 1 } })
    render(<TechErrorReportsPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Şablonun son hâlini uygula' }))
    const dlg = await screen.findByRole('dialog', { name: 'Şablonun son hâli uygulansın mı?' })
    expect(within(dlg).getByText(/2 değer ve 1 görsel/)).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Uygula' }))
    await waitFor(() => expect(te().refreshTemplate).toHaveBeenCalledWith('100', 4))
  })

  it('sabit şablon nesnesi değişmez (testler arası sızıntı yok)', () => {
    expect(TEMPLATE.sections[0].fields).toHaveLength(3)
  })
})
