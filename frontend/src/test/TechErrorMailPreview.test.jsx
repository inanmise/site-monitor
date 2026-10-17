import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, fireEvent, waitFor } from './test-utils.jsx'
import { reportResponse, openAllSections } from './helpers/techErrorFixtures.js'

// E-posta önizlemesi (2026-10-17, 10. sürüm — kullanıcı isteği: "mail göndermeden önce overview edebilmeliyiz; oluşturan ve
// onaylayan kişi bilgileri gözükmeli"): "E-postayı önizle" her okuyana açılır; pencere konu / alıcı / teslim yolu / ek / görsel
// sayımı / kimlik satırlarını ve gövdeyi kumsandık iframe'de gösterir; "Gönder" yalnız düzenleyicinin kendi gönderimi açıkken
// ve pencereden mevcut onay sorusuna geçer. Yük hatası "okunamadı" + tekrar dene; alıcı yoksa uyarı ve gönder yok;
// paylaşımla okuyan alıcı adreslerini değil sayısını görür.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), template: vi.fn(), report: vi.fn(), approval: vi.fn(), createReport: vi.fn(),
      saveReport: vi.fn(), mailPreview: vi.fn(), send: vi.fn(), imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors

const PROVENANCE = {
  created_by: 'Ayşe Yılmaz', created_at: '2026-09-03T11:05:00Z', updated_by: 'Ali Veli', updated_at: '2026-09-05T06:12:00Z',
  approval_state: null, approved_by: null, approved_at: null,
}

function preview(extra = {}) {
  return {
    success: true,
    data: {
      subject: 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026', delivery: 'TEAM', to: ['dijital-kanallar@example.com'], cc: [],
      to_count: 1, cc_count: 0, recipients_hidden: false, problem: null, file_name: 'mobil-teknik-hata-2026-09.pdf',
      images_embedded: 2, images_skipped: 1, provenance: PROVENANCE, pdf_bytes: 1_258_291, max_mail_mb: 15,
      html: '<!doctype html><html><body><div data-slot="te-mail-band">TEKNİK HATA RAPORU · EYLÜL 2026</div></body></html>',
      text: 'metin',
      ...extra,
    },
  }
}

async function openEditor(reportExtra = {}) {
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_rep=100&te_ch=10')
  te().report.mockResolvedValue(reportResponse({ ...reportExtra }))
  render(<TechErrorReportsPage />)
  await screen.findByRole('heading', { name: 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026' })
}

async function openPreview() {
  fireEvent.click(screen.getByRole('button', { name: 'E-postayı önizle' }))
  const dlg = await screen.findByRole('dialog', { name: 'E-posta önizlemesi' })
  return dlg
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  te().approval.mockResolvedValue({ success: true, data: { required: false, approval: { state: 'none' } } })
  te().mailPreview.mockResolvedValue(preview())
})

describe('e-posta önizlemesi — pencere', () => {
  it('düğme PDF indir\'in yanında; pencere konu / Kime + teslim rozeti / ek + görsel sayımı / kimlik satırlarını ve gövdeyi (kumsandık iframe) gösterir; rapor başlığında Oluşturan', async () => {
    await openEditor({ report: { ...reportResponse().data.report, created_at: '2026-09-03T11:05:00Z', created_by_name: 'Ayşe Yılmaz' } })
    expect(document.querySelector('[data-slot="te-created"]')).toHaveTextContent(/Oluşturan .*Ayşe Yılmaz/)
    const dlg = await openPreview()
    expect(String(te().mailPreview.mock.calls[0][0])).toBe('100')
    expect(within(dlg).getByText('Mobil Kanalı Teknik Hata Mesajları - Eylül 2026')).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="te-mail-to"]')).toHaveTextContent('dijital-kanallar@example.com')
    expect(dlg.querySelector('[data-slot="te-mail-delivery"]')).toHaveTextContent('Takıma')
    expect(dlg.querySelector('[data-slot="te-mail-cc"]')).toBeNull()                       // Bilgi yok → satır yok
    expect(within(dlg).getByText('mobil-teknik-hata-2026-09.pdf')).toBeInTheDocument()
    expect(within(dlg).getByText('2 görsel gövdede · 1 yalnız PDF ekinde')).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="te-mail-pdf-size"]')).toHaveTextContent('1,2 MB')
    expect(dlg.querySelector('[data-slot="te-mail-created"]')).toHaveTextContent(/Ayşe Yılmaz · /)
    expect(dlg.querySelector('[data-slot="te-mail-updated"]')).toHaveTextContent(/Ali Veli · /)
    expect(dlg.querySelector('[data-slot="te-mail-approval"]')).toBeNull()                 // onay akışı yok → onay satırı yok
    expect(dlg.querySelector('[data-slot="te-mail-approved"]')).toBeNull()
    const frame = dlg.querySelector('[data-slot="te-mail-frame"]')
    expect(frame.tagName).toBe('IFRAME')
    expect(frame.getAttribute('sandbox')).toBe('')
    expect(frame.getAttribute('srcdoc')).toContain('te-mail-band')
    expect(frame.getAttribute('data-width')).toBe('desktop')
    // Genişlik: telefon
    fireEvent.click(within(dlg).getByRole('button', { name: 'Telefon' }))
    expect(frame.getAttribute('data-width')).toBe('phone')
    // Kapat → pencere gider
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
  })

  it('aynı kişi aynı anda oluşturup kaydettiyse "Son düzenleyen" satırı yok; onaylı raporda Onaylayan; bekleyen onayda durum metni; bilinmeyen durum ham anahtar göstermez', async () => {
    await openEditor()
    te().mailPreview.mockResolvedValueOnce(preview({ provenance: { ...PROVENANCE, updated_by: 'Ayşe Yılmaz', updated_at: PROVENANCE.created_at,
      approval_state: 'approved', approved_by: 'Mehmet PO', approved_at: '2026-09-06T07:00:00Z' } }))
    let dlg = await openPreview()
    expect(dlg.querySelector('[data-slot="te-mail-updated"]')).toBeNull()
    expect(dlg.querySelector('[data-slot="te-mail-approved"]')).toHaveTextContent(/Mehmet PO · /)
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
    te().mailPreview.mockResolvedValueOnce(preview({ delivery: 'EXECUTIVE', to: ['ceo@example.com'], cc: ['dijital-kanallar@example.com'], cc_count: 1,
      provenance: { ...PROVENANCE, approval_state: 'pending' } }))
    dlg = await openPreview()
    expect(dlg.querySelector('[data-slot="te-mail-delivery"]')).toHaveTextContent('Yöneticilere')
    expect(dlg.querySelector('[data-slot="te-mail-cc"]')).toHaveTextContent('dijital-kanallar@example.com')
    expect(dlg.querySelector('[data-slot="te-mail-approval"]')).toHaveTextContent('PO onayı bekleniyor')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
    te().mailPreview.mockResolvedValueOnce(preview({ provenance: { ...PROVENANCE, approval_state: 'weird' } }))
    dlg = await openPreview()
    expect(dlg.querySelector('[data-slot="te-mail-approval"]')).toHaveTextContent('henüz onaya gönderilmedi')
    expect(dlg.textContent).not.toMatch(/te\.mail\./)
  })

  it('"Gönder" pencereden: pencere kapanır, mevcut onay sorusu açılır, onaylanınca gönderim çağrılır', async () => {
    te().send.mockResolvedValue({ success: true, data: { report: { ...reportResponse().data.report, status: 'sent', last_sent_at: '2026-10-03T09:00:00Z' } } })
    await openEditor()
    const dlg = await openPreview()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Takıma gönder' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
    const confirm = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
    fireEvent.click(within(confirm).getByRole('button', { name: 'Gönder' }))
    await waitFor(() => expect(te().send).toHaveBeenCalled()); expect(String(te().send.mock.calls[0][0])).toBe('100')
  })

  it('gönderemeyen okuyucu (can_send:false / salt okunur) önizler ama pencerede Gönder yok; takım adresi yoksa uyarı ve Gönder yok', async () => {
    await openEditor({ can_edit: false, can_send: false, can_delete: false })
    let dlg = await openPreview()
    expect(dlg.querySelector('[data-slot="te-mail-send"]')).toBeNull()
    expect(within(dlg).getByRole('button', { name: 'Kapat' })).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
    // Düzenleyebilen ama takım adresi olmayan: sunucu problem kodu → uyarı, gönder yok
    te().mailPreview.mockResolvedValueOnce(preview({ to: [], to_count: 0, problem: 'TEAM_EMAIL_MISSING' }))
    dlg = await openPreview()
    expect(within(dlg).getByText('Alıcı yok — bu posta gönderilemez')).toBeInTheDocument()
    expect(within(dlg).getByText(/Takımın kayıtlı e-posta adresi yok/)).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="te-mail-send"]')).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
    // PDF sınırı aşıyor (gönderimdeki kuralın aynısı): uyarı MB'larla, gövde yine var, gönder yok
    te().mailPreview.mockResolvedValueOnce(preview({ problem: 'PDF_TOO_LARGE', pdf_bytes: 16_000_000, max_mail_mb: 15, images_embedded: 0, images_skipped: 3 }))
    dlg = await openPreview()
    expect(within(dlg).getByText('PDF e-posta sınırını aşıyor — bu posta gönderilemez')).toBeInTheDocument()
    expect(within(dlg).getByText(/PDF 15,3 MB; e-posta sınırı 15 MB/)).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="te-mail-frame"]')).not.toBeNull()
    expect(dlg.querySelector('[data-slot="te-mail-send"]')).toBeNull()
  })

  it('paylaşımla okuyan: adresler yerine sayı ("1 alıcı (adresler paylaşımla görünmez)"); kimlik satırları yine görünür', async () => {
    await openEditor({ can_edit: false, can_send: false, shared_access: true })
    te().mailPreview.mockResolvedValueOnce(preview({ to: [], to_count: 1, recipients_hidden: true }))
    const dlg = await openPreview()
    expect(dlg.querySelector('[data-slot="te-mail-to"]')).toHaveTextContent('1 alıcı (adresler paylaşımla görünmez)')
    expect(dlg.querySelector('[data-slot="te-mail-to"]')).not.toHaveTextContent('@')
    expect(dlg.querySelector('[data-slot="te-mail-created"]')).toHaveTextContent('Ayşe Yılmaz')
  })

  it('kaydedilmemiş taslak: önizleme ÖNCE "Önce kaydedilsin mi?" sorar; kaydedilince açılır (görülen = gönderilen); vazgeçilirse açılmaz', async () => {
    te().saveReport.mockResolvedValue({ success: true, data: { version: 5, changed: true, updated_at: '2026-10-13T09:00:00Z',
      values: { fields: { 'error-rate': 0.42, 'total-tx': 1250000 }, issues: {}, texts: {} } } })
    await openEditor()
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '1.250.000' } })
    fireEvent.click(screen.getByRole('button', { name: 'E-postayı önizle' }))
    const ask = await screen.findByRole('dialog', { name: 'Önce kaydedilsin mi?' })
    expect(te().mailPreview).not.toHaveBeenCalled()
    fireEvent.click(within(ask).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Önce kaydedilsin mi?' })).toBeNull())
    expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull()
    expect(te().saveReport).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'E-postayı önizle' }))
    const ask2 = await screen.findByRole('dialog', { name: 'Önce kaydedilsin mi?' })
    fireEvent.click(within(ask2).getByRole('button', { name: 'Kaydet ve devam et' }))
    await screen.findByRole('dialog', { name: 'E-posta önizlemesi' })
    expect(te().saveReport).toHaveBeenCalledTimes(1)
    expect(te().mailPreview).toHaveBeenCalledTimes(1)
  })

  it('yük hatası: "Önizleme hazırlanamadı" + sunucu metni + Tekrar dene → ikinci okuma gövdeyi getirir; Gönder hata ekranında yok', async () => {
    await openEditor()
    te().mailPreview.mockRejectedValueOnce(new Error('Şu anda başka raporların PDF\'i hazırlanıyor. Birkaç saniye sonra tekrar deneyin.'))
    const dlg = await openPreview()
    expect(await within(dlg).findByText('Önizleme hazırlanamadı')).toBeInTheDocument()
    expect(within(dlg).getByText(/başka raporların PDF/)).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="te-mail-send"]')).toBeNull()
    expect(dlg.querySelector('[data-slot="te-mail-frame"]')).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Tekrar dene' }))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-mail-frame"]')).not.toBeNull())
    expect(te().mailPreview).toHaveBeenCalledTimes(2)
    // success:false gövdesi de hata sayılır (başarılı/"tamam" durumu asla eksik veriden türetilmez)
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
    te().mailPreview.mockResolvedValueOnce({ success: false, error: 'Rapor bulunamadı.' })
    const dlg2 = await openPreview()
    expect(await within(dlg2).findByText('Rapor bulunamadı.')).toBeInTheDocument()
  })
})
