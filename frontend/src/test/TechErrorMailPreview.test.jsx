import { StrictMode } from 'react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, within, fireEvent, waitFor, act } from './test-utils.jsx'
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
      saveReport: vi.fn(), mailPreview: vi.fn(), mailPreviewTest: vi.fn(), send: vi.fn(), deleteImage: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
// A4: eylem çubuğunun ölçüsü (jsdom ölçmez) — test ayarlar: 0 = pb-24 tabanı, > 0 = çubuk yüksekliği + 16 px alt boşluk
const barHeight = vi.hoisted(() => ({ value: 0 }))
vi.mock('../hooks/useElementHeight.js', () => ({ useElementHeight: () => [() => {}, barHeight.value] }))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'
import MailPreviewDialog from '../components/techerror/MailPreviewDialog.jsx'
import { clearMailPreviewShares } from '../components/techerror/mailPreviewFetch.js'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

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
  clearMailPreviewShares()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  te().approval.mockResolvedValue({ success: true, data: { required: false, approval: { state: 'none' } } })
  te().mailPreview.mockResolvedValue(preview())
  te().deleteImage.mockResolvedValue({ success: true })
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

  it('4. tur: hazırlık sürerken kapat → alanı düzenle → yeniden önizle (kaydet, sürüm 4 → 5): kayıttan ÖNCE başlamış isteğe katılmaz, pencere kaydedilen içeriği gösterir (görülen = gönderilen)', async () => {
    te().saveReport.mockResolvedValue({ success: true, data: { version: 5, changed: true, updated_at: '2026-10-13T09:00:00Z',
      values: { fields: { 'error-rate': 0.42, 'total-tx': 1250000 }, issues: {}, texts: {} } } })
    await openEditor()
    let releaseOld
    te().mailPreview.mockImplementationOnce(() => new Promise((r) => { releaseOld = r }))
    let dlg = await openPreview()
    await waitFor(() => expect(releaseOld).toBeTypeOf('function'))
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '1.250.000' } })
    te().mailPreview.mockResolvedValueOnce(preview({ subject: 'Kayıttan sonraki içerik' }))
    fireEvent.click(screen.getByRole('button', { name: 'E-postayı önizle' }))
    const ask = await screen.findByRole('dialog', { name: 'Önce kaydedilsin mi?' })
    fireEvent.click(within(ask).getByRole('button', { name: 'Kaydet ve devam et' }))
    dlg = await screen.findByRole('dialog', { name: 'E-posta önizlemesi' })
    expect(await within(dlg).findByText('Kayıttan sonraki içerik')).toBeInTheDocument()
    expect(te().saveReport).toHaveBeenCalledTimes(1)
    expect(te().mailPreview).toHaveBeenCalledTimes(2)
    await act(async () => { releaseOld(preview({ subject: 'Kayıttan önceki içerik' })) })
    expect(within(dlg).queryByText('Kayıttan önceki içerik')).toBeNull()
    expect(within(dlg).getByText('Kayıttan sonraki içerik')).toBeInTheDocument()
  })

  it('5. tur: görsel değişikliği sürümü değiştirmez — hazırlık sürerken kapat → görseli kaldır → 5 sn içinde yeniden aç: ESKİ isteğe katılmaz (eski görseller gösterilmez), kimsenin beklemediği eski istek kesilir', async () => {
    await openEditor({ images: [{ id: 501, slot_id: 'monthly-tx', file_name: 'aylik.png', width: 800, height: 400, size_bytes: 2048 }] })
    let releaseOld
    te().mailPreview.mockImplementationOnce(() => new Promise((r) => { releaseOld = r }))
    let dlg = await openPreview()
    await waitFor(() => expect(releaseOld).toBeTypeOf('function'))
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
    // Görsel kaldırılır (rapor sürümü DEĞİŞMEZ — sunucu yalnız içerik sayacını artırır). Yarım yuvada "Kaldır" "Diğer
    // işlemler" menüsünde (ekrandan görsel alma, 2026-10-18)
    pressMenuTrigger(screen.getByRole('button', { name: 'Diğer işlemler: Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri' }))
    fireEvent.click(within(await screen.findByRole('menu')).getByRole('menuitem', { name: 'Kaldır: Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri' }))
    const ask = await screen.findByRole('dialog', { name: 'Görsel kaldırılsın mı?' })
    fireEvent.click(within(ask).getByRole('button', { name: 'Kaldır' }))
    await waitFor(() => expect(te().deleteImage).toHaveBeenCalledWith('100', 501))
    await waitFor(() => expect(document.querySelector('[data-slot-id="monthly-tx"]')).toHaveAttribute('data-has-image', 'false'))
    te().mailPreview.mockResolvedValueOnce(preview({ subject: 'Görsel kaldırıldıktan sonra', images_embedded: 0 }))
    fireEvent.click(screen.getByRole('button', { name: 'E-postayı önizle' }))
    dlg = await screen.findByRole('dialog', { name: 'E-posta önizlemesi' })
    expect(await within(dlg).findByText('Görsel kaldırıldıktan sonra')).toBeInTheDocument()
    expect(te().mailPreview).toHaveBeenCalledTimes(2)
    expect(te().mailPreview.mock.calls[0][1].signal.aborted).toBe(true)
    await act(async () => { releaseOld(preview({ subject: 'Kaldırılmadan önceki görseller' })) })
    expect(within(dlg).queryByText('Kaldırılmadan önceki görseller')).toBeNull()
  })

  it('5. tur: sunucu önceki hazırlığı uzun sürdürürse Retry-After kadar beklenip İKİ kez yeniden denenir (≤ 15 sn); sonra sunucunun iletisi pencerede', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const busy = { success: false, status: 429, code: 'RATE_LIMITED', retry_after_seconds: 5,
        error: 'Sizin için başka bir PDF ya da e-posta önizlemesi hazırlanıyor; kişi başına aynı anda bir tane hazırlanır.' }
      te().mailPreview.mockResolvedValue(busy)
      render(<MailPreviewDialog reportId="100" reportVersion={4} open onClose={() => {}} uiLang="tr" />)
      const dlg = await screen.findByRole('dialog', { name: 'E-posta önizlemesi' })
      await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
      await waitFor(() => expect(te().mailPreview).toHaveBeenCalledTimes(2))
      expect(within(dlg).queryByText('Önizleme hazırlanamadı')).toBeNull()        // hâlâ bekliyor
      await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
      await waitFor(() => expect(te().mailPreview).toHaveBeenCalledTimes(3))
      expect(await within(dlg).findByText('Önizleme hazırlanamadı')).toBeInTheDocument()
      expect(within(dlg).getByText(/kişi başına aynı anda bir tane hazırlanır/)).toBeInTheDocument()
      await act(async () => { await vi.advanceTimersByTimeAsync(20_000) })
      expect(te().mailPreview).toHaveBeenCalledTimes(3)
    } finally {
      vi.useRealTimers()
    }
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

  it('A3 (2026-10-17): takım adresinde 20’den fazla alıcı — to_dropped > 0 ise uyarı şeridi (sayı, Admin → Takımlar yönlendirmesi), posta yine gider (Gönder açık); 0 / eksik ise şerit yok', async () => {
    await openEditor()
    te().mailPreview.mockResolvedValueOnce(preview({ to_dropped: 3, to_count: 20 }))
    let dlg = await openPreview()
    const strip = dlg.querySelector('[data-slot="te-mail-dropped"]')
    expect(strip).not.toBeNull()
    expect(strip.getAttribute('data-count')).toBe('3')
    expect(strip).toHaveTextContent('Alıcı sınırı aşıldı — 3 adres bu postayı almaz')
    expect(strip).toHaveTextContent(/Takım adresinde 3 alıcı daha var; bir rapor postası en çok 20 adrese gider/)
    expect(strip).toHaveTextContent(/Admin → Takımlar/)
    expect(dlg.querySelector('[data-slot="te-mail-send"]')).not.toBeNull()          // posta 20 adrese yine gider — engel değil
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
    te().mailPreview.mockResolvedValueOnce(preview({ to_dropped: 0 }))
    dlg = await openPreview()
    expect(dlg.querySelector('[data-slot="te-mail-dropped"]')).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
    // Eski sunucu (alan yok) → şerit yok, ham anahtar yok
    te().mailPreview.mockResolvedValueOnce(preview())
    dlg = await openPreview()
    expect(dlg.querySelector('[data-slot="te-mail-dropped"]')).toBeNull()
    expect(dlg.querySelector('[data-slot="te-mail-cc-dropped"]')).toBeNull()
    expect(dlg.textContent).not.toMatch(/te\.mail\./)
  })

  it('inceleme 2026-10-17: yöneticilere gönderimde Bilgi (takım adresi) 20’yi aşarsa cc_dropped > 0 → ikinci şerit (Kime şeridinden ayrı), posta yine gider; 0 / eksik → yok', async () => {
    await openEditor()
    te().mailPreview.mockResolvedValueOnce(preview({ delivery: 'EXECUTIVE', to: ['ceo@example.com'], to_count: 1,
      cc: Array.from({ length: 20 }, (_, i) => `u${i + 1}@example.com`), cc_count: 20, cc_dropped: 4, to_dropped: 0 }))
    let dlg = await openPreview()
    const strip = dlg.querySelector('[data-slot="te-mail-cc-dropped"]')
    expect(strip).not.toBeNull()
    expect(strip.getAttribute('data-count')).toBe('4')
    expect(strip).toHaveTextContent('Bilgi (CC) sınırı aşıldı — 4 takım adresi bu postayı almaz')
    expect(strip).toHaveTextContent(/Takım adresinde \(Bilgi \/ CC\) 4 alıcı daha var; bir rapor postası en çok 20 Bilgi adresine gider, fazlası gitmez/)
    expect(strip).toHaveTextContent(/Admin → Takımlar/)
    expect(dlg.querySelector('[data-slot="te-mail-dropped"]')).toBeNull()             // Kime sınır içinde
    expect(dlg.querySelector('[data-slot="te-mail-send"]')).not.toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
    te().mailPreview.mockResolvedValueOnce(preview({ delivery: 'EXECUTIVE', cc_dropped: 0 }))
    dlg = await openPreview()
    expect(dlg.querySelector('[data-slot="te-mail-cc-dropped"]')).toBeNull()
  })
})

describe('A4 (2026-10-17): eylem çubuğunun yüksekliği kabın alt boşluğunu sürer', () => {
  afterEach(() => { barHeight.value = 0 })

  it('ölçüm yokken (jsdom / ilk boyama) pb-24 tabanı, satır içi alt boşluk yok; çubuk ve önizleme düğmesi yerinde', async () => {
    barHeight.value = 0
    await openEditor()
    const editor = document.querySelector('[data-slot="te-report"]')
    expect(editor.className).toContain('pb-24')
    expect(editor.style.paddingBottom).toBe('')
    expect(document.querySelector('[data-slot="te-report-actions"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="te-mail-preview-btn"]')).not.toBeNull()   // e2e seçicisi her biçimde var
  })

  it('ölçüm varsa (çubuk 3 satıra sarıldı, 172 px) satır içi alt boşluk = yükseklik + 16, pb-24 yok', async () => {
    barHeight.value = 172
    await openEditor()
    const editor = document.querySelector('[data-slot="te-report"]')
    expect(editor.className).not.toContain('pb-24')
    expect(editor.style.paddingBottom).toBe('188px')
  })
})

// "Bana test gönder" (R13 / B5, 2026-10-17): önizlenen posta YALNIZ bana — her okuyana (gönderemeyen de), PDF sınırı aşıyorsa
// yok (gönderim gibi test de gidemez), alıcı eksikliği engellemez; meşgulken kapalı + "gönderiliyor", sonuç toast (sunucu
// adresi), hata sunucu metniyle; pencere kendi düğmeleriyle kapanır, test gönderimi rapor gönderimini ÇAĞIRMAZ.
describe('e-posta önizlemesi — bana test gönder', () => {
  it('düğme pencerede; tıklanınca uç çağrılır, meşgulken düğme kapalı ve "gönderiliyor", Gönder kapalı; başarıda toast sunucunun adresiyle; gönderim çağrılmaz', async () => {
    let resolveTest
    te().mailPreviewTest.mockReturnValueOnce(new Promise((r) => { resolveTest = r }))
    await openEditor()
    const dlg = await openPreview()
    const btn = dlg.querySelector('[data-slot="te-mail-test"]')
    expect(btn).not.toBeNull()
    expect(btn).toHaveTextContent('Bana test gönder')
    fireEvent.click(btn)
    expect(String(te().mailPreviewTest.mock.calls[0][0])).toBe('100')
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-mail-test"]')).toBeDisabled())
    expect(dlg.querySelector('[data-slot="te-mail-test"]')).toHaveTextContent('Test postası gönderiliyor…')
    expect(dlg.querySelector('[data-slot="te-mail-test"]')).toHaveAttribute('aria-busy', 'true')
    expect(dlg.querySelector('[data-slot="te-mail-send"]')).toBeDisabled()
    resolveTest({ success: true, data: { sent_to: 'ayse@example.com', status: 'SENT' } })
    expect(await screen.findByText('Test postası ayse@example.com adresine gönderildi — yalnız size; takıma / yöneticilere gönderilmedi.')).toBeInTheDocument()
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-mail-test"]')).not.toBeDisabled())
    expect(dlg.querySelector('[data-slot="te-mail-test"]')).toHaveTextContent('Bana test gönder')
    expect(dlg.querySelector('[data-slot="te-mail-send"]')).not.toBeDisabled()
    expect(te().send).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog', { name: 'E-posta önizlemesi' })).toBeInTheDocument()   // pencere açık kalır
    // Kuyruk cevabı: bilgi toast'u, "gönderildi" denmez
    te().mailPreviewTest.mockResolvedValueOnce({ success: true, data: { sent_to: 'ayse@example.com', status: 'QUEUED' } })
    fireEvent.click(dlg.querySelector('[data-slot="te-mail-test"]'))
    expect(await screen.findByText(/Test postası posta sunucusuna verildi \(ayse@example.com\)/)).toBeInTheDocument()
  })

  it('hata: sunucu metni toast olur (NO_EMAIL 409, 429); ağ hatası mesajıyla; success:false gövdesi hata sayılır; pencere açık kalır', async () => {
    await openEditor()
    const dlg = await openPreview()
    te().mailPreviewTest.mockResolvedValueOnce({ success: false, status: 409, code: 'NO_EMAIL',
      error: 'Hesabınızda e-posta adresi yok; test postası gönderilemez. Yöneticinizden adres eklemesini isteyin.' })
    fireEvent.click(dlg.querySelector('[data-slot="te-mail-test"]'))
    expect(await screen.findByText(/Hesabınızda e-posta adresi yok/)).toBeInTheDocument()
    te().mailPreviewTest.mockRejectedValueOnce(new Error('Test sınırı: dakikada en çok 3 test postası. 42 saniye sonra yeniden deneyin.'))
    fireEvent.click(dlg.querySelector('[data-slot="te-mail-test"]'))
    expect(await screen.findByText(/dakikada en çok 3 test postası/)).toBeInTheDocument()
    te().mailPreviewTest.mockResolvedValueOnce({ success: false })
    fireEvent.click(dlg.querySelector('[data-slot="te-mail-test"]'))
    expect(await screen.findByText(/Test postası gönderilemedi/)).toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: 'E-posta önizlemesi' })).toBeInTheDocument()
    expect(te().send).not.toHaveBeenCalled()
  })

  it('görünürlük: PDF sınırı aşıyorsa düğme YOK; takım adresi yoksa VAR (yalnız bana gider); gönderemeyen okuyucu ve paylaşımla okuyan da görür; yük hatasında yok', async () => {
    await openEditor({ can_edit: false, can_send: false, shared_access: true })
    te().mailPreview.mockResolvedValueOnce(preview({ problem: 'PDF_TOO_LARGE', pdf_bytes: 16_000_000, max_mail_mb: 15 }))
    let dlg = await openPreview()
    await within(dlg).findByText('PDF e-posta sınırını aşıyor — bu posta gönderilemez')
    expect(dlg.querySelector('[data-slot="te-mail-test"]')).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
    te().mailPreview.mockResolvedValueOnce(preview({ to: [], to_count: 0, problem: 'TEAM_EMAIL_MISSING' }))
    dlg = await openPreview()
    await within(dlg).findByText('Alıcı yok — bu posta gönderilemez')
    expect(dlg.querySelector('[data-slot="te-mail-test"]')).not.toBeNull()
    expect(dlg.querySelector('[data-slot="te-mail-send"]')).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
    te().mailPreview.mockResolvedValueOnce(preview({ to: [], to_count: 1, recipients_hidden: true }))
    dlg = await openPreview()
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-mail-frame"]')).not.toBeNull())
    expect(dlg.querySelector('[data-slot="te-mail-test"]')).not.toBeNull()       // paylaşımla okuyan: yalnız kendine
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
    te().mailPreview.mockRejectedValueOnce(new Error('okunamadı'))
    dlg = await openPreview()
    await within(dlg).findByText('Önizleme hazırlanamadı')
    expect(dlg.querySelector('[data-slot="te-mail-test"]')).toBeNull()
  })

  it('inceleme 2026-10-17: alıcı sorunu VE PDF sınırı birlikte — problem alıcıyı söyler ama pdf_too_large ayrı: iki şerit, test düğmesi YOK, Gönder yok; eski sunucu (bayrak yok) PDF boyutundan anlar', async () => {
    await openEditor()
    te().mailPreview.mockResolvedValueOnce(preview({ to: [], to_count: 0, problem: 'TEAM_EMAIL_MISSING', pdf_too_large: true,
      pdf_bytes: 16_000_000, max_mail_mb: 15 }))
    let dlg = await openPreview()
    await within(dlg).findByText('Alıcı yok — bu posta gönderilemez')
    expect(dlg.querySelector('[data-slot="te-mail-problem"]')).toHaveAttribute('data-problem', 'TEAM_EMAIL_MISSING')
    expect(dlg.querySelector('[data-slot="te-mail-pdf-too-large"]')).toHaveTextContent('PDF e-posta sınırını aşıyor — bu posta gönderilemez')
    expect(dlg.querySelector('[data-slot="te-mail-pdf-too-large"]')).toHaveTextContent(/PDF 15,3 MB; e-posta sınırı 15 MB/)
    expect(dlg.querySelector('[data-slot="te-mail-test"]')).toBeNull()
    expect(dlg.querySelector('[data-slot="te-mail-send"]')).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
    // Eski sunucu: bayrak yok, problem alıcıyı söylüyor — PDF boyutu sınırın üstünde → yine düğme yok, PDF şeridi var
    te().mailPreview.mockResolvedValueOnce(preview({ to: [], to_count: 0, problem: 'TEAM_EMAIL_MISSING', pdf_bytes: 16_000_000, max_mail_mb: 15 }))
    dlg = await openPreview()
    await within(dlg).findByText('Alıcı yok — bu posta gönderilemez')
    expect(dlg.querySelector('[data-slot="te-mail-pdf-too-large"]')).not.toBeNull()
    expect(dlg.querySelector('[data-slot="te-mail-test"]')).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
    // Bayrak false (yeni sunucu) → PDF şeridi yok, test düğmesi var (alıcı eksikliği testi engellemez)
    te().mailPreview.mockResolvedValueOnce(preview({ to: [], to_count: 0, problem: 'TEAM_EMAIL_MISSING', pdf_too_large: false }))
    dlg = await openPreview()
    await within(dlg).findByText('Alıcı yok — bu posta gönderilemez')
    expect(dlg.querySelector('[data-slot="te-mail-pdf-too-large"]')).toBeNull()
    expect(dlg.querySelector('[data-slot="te-mail-test"]')).not.toBeNull()
  })
})

describe('e-posta önizlemesi — çift açılış (inceleme 2026-10-18, 3. tur)', () => {
  // Sunucu kişi başına aynı anda TEK önizleme hazırlar: ilk istek sürerken gelen ikincisi 429 RATE_LIMITED alır
  function serverWithPerUserMarker() {
    const gate = { inFlight: 0, release: null }
    te().mailPreview.mockImplementation(() => {
      if (gate.inFlight > 0) {
        return Promise.resolve({ success: false, status: 429, code: 'RATE_LIMITED',
          error: 'Sizin için başka bir PDF ya da e-posta önizlemesi hazırlanıyor; kişi başına aynı anda bir tane hazırlanır.' })
      }
      gate.inFlight++
      return new Promise((resolve) => { gate.release = (v) => { gate.inFlight--; resolve(v) } })
    })
    return gate
  }

  it('StrictMode (efekt iki kez koşar): TEK istek gider, ilk okumanın sonucu pencereye ulaşır — "okunamadı" yok', async () => {
    const gate = serverWithPerUserMarker()
    render(<StrictMode><MailPreviewDialog reportId="100" open onClose={() => {}} uiLang="tr" /></StrictMode>)
    const dlg = await screen.findByRole('dialog', { name: 'E-posta önizlemesi' })
    await waitFor(() => expect(gate.release).toBeTypeOf('function'))
    expect(te().mailPreview).toHaveBeenCalledTimes(1)
    expect(te().mailPreview.mock.calls[0][1].signal).toBeInstanceOf(AbortSignal)
    await act(async () => { gate.release(preview()) })
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-mail-frame"]')).not.toBeNull())
    expect(within(dlg).queryByText('Önizleme hazırlanamadı')).toBeNull()
    expect(within(dlg).getByText('Mobil Kanalı Teknik Hata Mesajları - Eylül 2026')).toBeInTheDocument()
    expect(te().mailPreview).toHaveBeenCalledTimes(1)
    expect(te().mailPreview.mock.calls[0][1].signal.aborted).toBe(false)
  })

  it('pencere kapatılıp hazırlık sürerken hemen yeniden açılır → yeni istek atılmaz (429 yok), süren okumaya katılır', async () => {
    const gate = serverWithPerUserMarker()
    const view = render(<MailPreviewDialog reportId="100" open onClose={() => {}} uiLang="tr" />)
    await waitFor(() => expect(gate.release).toBeTypeOf('function'))
    view.rerender(<MailPreviewDialog reportId="100" open={false} onClose={() => {}} uiLang="tr" />)
    view.rerender(<MailPreviewDialog reportId="100" open onClose={() => {}} uiLang="tr" />)
    const dlg = await screen.findByRole('dialog', { name: 'E-posta önizlemesi' })
    expect(te().mailPreview).toHaveBeenCalledTimes(1)
    await act(async () => { gate.release(preview()) })
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-mail-frame"]')).not.toBeNull())
    expect(within(dlg).queryByText('Önizleme hazırlanamadı')).toBeNull()
  })

  it('4. tur: kapat → düzenle (sürüm 4 → 5) → yeniden aç: kayıttan önce başlamış isteğe KATILMAZ (eski içerik gösterilmez); sunucu eskisini hâlâ hazırlıyorsa 429 sonrası Retry-After kadar bekleyip bir kez yeniden dener', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const gate = { inFlight: 0, release: null }
      te().mailPreview.mockImplementation(() => {
        if (gate.inFlight > 0) {
          return Promise.resolve({ success: false, status: 429, code: 'RATE_LIMITED', retry_after_seconds: 5,
            error: 'Sizin için başka bir PDF ya da e-posta önizlemesi hazırlanıyor; kişi başına aynı anda bir tane hazırlanır.' })
        }
        gate.inFlight++
        return new Promise((resolve) => { gate.release = (v) => { gate.inFlight--; resolve(v) } })
      })
      const view = render(<MailPreviewDialog reportId="100" reportVersion={4} open onClose={() => {}} uiLang="tr" />)
      await waitFor(() => expect(gate.release).toBeTypeOf('function'))
      const releaseOld = gate.release
      view.rerender(<MailPreviewDialog reportId="100" reportVersion={4} open={false} onClose={() => {}} uiLang="tr" />)
      view.rerender(<MailPreviewDialog reportId="100" reportVersion={5} open onClose={() => {}} uiLang="tr" />)
      const dlg = await screen.findByRole('dialog', { name: 'E-posta önizlemesi' })
      await waitFor(() => expect(te().mailPreview).toHaveBeenCalledTimes(2))     // katılmadı: yeni istek (429 aldı)
      expect(te().mailPreview.mock.calls[0][1].signal.aborted).toBe(true)        // eski istek kesildi
      // Sunucu eski hazırlığı bitirir (yanıtı kimse okumaz), önerilen 5 sn sonra tek yeniden deneme
      await act(async () => { releaseOld(preview({ subject: 'Eski içerik' })) })
      await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
      await waitFor(() => expect(te().mailPreview).toHaveBeenCalledTimes(3))
      await act(async () => { gate.release(preview({ subject: 'Yeni içerik' })) })
      expect(await within(dlg).findByText('Yeni içerik')).toBeInTheDocument()
      expect(within(dlg).queryByText('Eski içerik')).toBeNull()
      expect(within(dlg).queryByText('Önizleme hazırlanamadı')).toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })

  it('kapatılan pencerenin geç gelen sonucu yok sayılır; yeniden açılış taze okur', async () => {
    const gate = serverWithPerUserMarker()
    const view = render(<MailPreviewDialog reportId="100" open onClose={() => {}} uiLang="tr" />)
    await waitFor(() => expect(gate.release).toBeTypeOf('function'))
    view.rerender(<MailPreviewDialog reportId="100" open={false} onClose={() => {}} uiLang="tr" />)
    await act(async () => { gate.release(preview({ subject: 'Eski yanıt' })) })
    expect(screen.queryByText('Eski yanıt')).toBeNull()
    te().mailPreview.mockResolvedValueOnce(preview({ subject: 'Taze yanıt' }))
    view.rerender(<MailPreviewDialog reportId="100" open onClose={() => {}} uiLang="tr" />)
    expect(await screen.findByText('Taze yanıt')).toBeInTheDocument()
    expect(te().mailPreview).toHaveBeenCalledTimes(2)
  })
})
