import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { reportResponse, openAllSections } from './helpers/techErrorFixtures.js'

// R18 / C13 (2026-10-18): gönderim penceresi — eski onay sorusunun metni + "Bu gönderime ek bilgi (CC)" alanı. İstemci
// biçim / sayı denetimi alanın altında (istek gitmez); boş alan gövdesiz eski isteği atar; dolu alan yalnız `extra_cc`
// taşır; sunucunun alan hatası (`extra_cc`) pencereyi açık bırakır ve alanın altında yazar, düzenleyince kalkar.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      report: vi.fn(), saveReport: vi.fn(), send: vi.fn(), downloadPdf: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  te().report.mockResolvedValue(reportResponse())
})

async function openSendDialog() {
  render(<TechErrorReportsPage />)
  await screen.findByRole('heading', { name: TITLE })
  fireEvent.click(screen.getByRole('button', { name: 'Takıma gönder' }))
  return screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
}

const ccInput = (dlg) => within(dlg).getByLabelText('Bu gönderime ek bilgi (CC)')

describe('gönderim penceresi — ek Bilgi (CC)', () => {
  it('alan, ipucu ve eski onay metni; boş alanla gövdesiz eski istek', async () => {
    te().send.mockResolvedValue({ success: true, data: { state: 'SENT', to: ['dijital@example.com'], cc: [] } })
    const dlg = await openSendDialog()
    expect(dlg).toHaveAttribute('data-slot', 'te-send-dialog')
    expect(within(dlg).getByText(/Konu: Mobil Kanalı Teknik Hata Mesajları - Eylül 2026/)).toBeInTheDocument()
    const input = ccInput(dlg)
    expect(input).toHaveAttribute('data-slot', 'te-send-extra-cc')
    expect(within(dlg).getByText('Virgülle ya da satır satır ayırın; en çok 10 adres; kaydedilmez, yalnız bu gönderim için. Göndermek için Ctrl+Enter (Mac: ⌘+Enter); Enter yeni satırdır.')).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
    await waitFor(() => expect(te().send).toHaveBeenCalledTimes(1))
    expect(te().send.mock.calls[0]).toEqual(['100'])
    expect(await screen.findByText('Rapor gönderildi: dijital@example.com')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
  })

  it('istemci denetimi: geçersiz adres ve 10\'dan fazla adres alanın altında, istek gitmez; düzenleyince hata kalkar', async () => {
    const dlg = await openSendDialog()
    const input = ccInput(dlg)
    fireEvent.change(input, { target: { value: 'ek@example.com, bozuk' } })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
    expect(await within(dlg).findByText('«bozuk» geçerli bir e-posta adresi değil. Adresleri virgülle ayırın, ör. ad.soyad@kurum.com.tr.')).toBeInTheDocument()
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(te().send).not.toHaveBeenCalled()
    fireEvent.change(input, { target: { value: Array.from({ length: 11 }, (_, i) => `e${i}@example.com`).join(', ') } })
    expect(within(dlg).queryByText(/geçerli bir e-posta adresi değil/)).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
    expect(await within(dlg).findByText('Bir gönderime en çok 10 ek adres eklenebilir; 11 adres yazdınız. Fazlasını çıkarın.')).toBeInTheDocument()
    expect(te().send).not.toHaveBeenCalled()
  })

  it('dolu alan: tekilleşmiş adresler yalnız extra_cc ile gider; bildirim Bilgi\'yi de söyler', async () => {
    te().send.mockResolvedValue({ success: true, data: { state: 'SENT', to: ['dijital@example.com'], cc: ['ek@example.com', 'iki@example.com'] } })
    const dlg = await openSendDialog()
    fireEvent.change(ccInput(dlg), { target: { value: 'ek@example.com; iki@example.com  EK@example.com' } })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
    await waitFor(() => expect(te().send).toHaveBeenCalledWith('100', { extra_cc: 'ek@example.com, iki@example.com' }))
    expect(await screen.findByText('Rapor gönderildi: dijital@example.com · Bilgi (CC): ek@example.com, iki@example.com')).toBeInTheDocument()
  })

  it('sunucunun alan hatası (extra_cc): pencere açık, metin alanın altında, rapor yenilenmez; düzenleyince kalkar; ikinci deneme başarılı → kapanır', async () => {
    const msg = 'Bilgi (CC) listesi en çok 20 adres olabilir: takımın Bilgi listesinde 18 adres var, 3 ek adresle 21 oluyor. Ek adresleri 2 adrese indirin.'
    te().send
      .mockResolvedValueOnce({ success: false, status: 400, code: 'VALIDATION_FAILED', field: 'extra_cc', error: msg })
      .mockResolvedValueOnce({ success: true, data: { state: 'SENT', to: ['dijital@example.com'], cc: ['a@example.com'] } })
    const dlg = await openSendDialog()
    const input = ccInput(dlg)
    fireEvent.change(input, { target: { value: 'a@example.com, b@example.com, c@example.com' } })
    const loadsBefore = te().report.mock.calls.length
    fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
    expect(await within(dlg).findByText(msg)).toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeInTheDocument()
    expect(input).toHaveValue('a@example.com, b@example.com, c@example.com')        // yazılan korunur
    expect(te().report.mock.calls.length).toBe(loadsBefore)
    await waitFor(() => expect(input).not.toBeDisabled())
    fireEvent.change(input, { target: { value: 'a@example.com' } })
    expect(within(dlg).queryByText(msg)).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
    await waitFor(() => expect(te().send).toHaveBeenLastCalledWith('100', { extra_cc: 'a@example.com' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
  })

  it('inceleme 2026-10-18 (2. tur): gönderim geri alınamaz — düz Enter yeni satır (gönderim yok), Ctrl/⌘+Enter gönderir; Shift / IME gönderim yapmaz; geçersiz girişte Ctrl+Enter alan hatası verir', async () => {
    te().send.mockResolvedValue({ success: true, data: { state: 'SENT', to: ['dijital@example.com'], cc: ['ek@example.com'] } })
    const dlg = await openSendDialog()
    const input = ccInput(dlg)
    fireEvent.change(input, { target: { value: 'bozuk' } })
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', ctrlKey: true })
    expect(await within(dlg).findByText(/«bozuk» geçerli bir e-posta adresi değil/)).toBeInTheDocument()
    expect(te().send).not.toHaveBeenCalled()
    fireEvent.change(input, { target: { value: 'ek@example.com' } })
    // Düz Enter: tarayıcının yeni satırı (varsayılan engellenmez) — satır başına bir adres yazılır, gönderim YOK
    const plain = fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' })
    expect(plain).toBe(true)
    // Shift+Enter de yeni satır
    const shift = fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', shiftKey: true })
    expect(shift).toBe(true)
    // IME birleştirmesi sürerken Ctrl+Enter gönderim yapmaz
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', ctrlKey: true, keyCode: 229 })
    // Ctrl+Shift+Enter gönderim yapmaz (yalnız Ctrl/⌘+Enter)
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', ctrlKey: true, shiftKey: true })
    expect(te().send).not.toHaveBeenCalled()
    // İpucu kısayolu söyler
    expect(within(dlg).getByText(/Ctrl\+Enter/)).toBeInTheDocument()
    const ctrl = fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', ctrlKey: true })
    expect(ctrl).toBe(false)                                    // varsayılan engellendi, form gönderildi
    await waitFor(() => expect(te().send).toHaveBeenCalledWith('100', { extra_cc: 'ek@example.com' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
  })

  it('⌘+Enter (macOS) de gönderir', async () => {
    te().send.mockResolvedValue({ success: true, data: { state: 'SENT', to: ['dijital@example.com'], cc: [] } })
    const dlg = await openSendDialog()
    const input = ccInput(dlg)
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', metaKey: true })
    await waitFor(() => expect(te().send).toHaveBeenCalledTimes(1))
  })

  it('dönüşümlü dağıtım: ek Bilgi yazıldı ama yanıtta cc yok (eski sunucu) → ek adreslerin onaylanmadığı söylenir; ek Bilgi yoksa uyarı yok', async () => {
    te().send.mockResolvedValue({ success: true, data: { state: 'SENT', to: ['dijital@example.com'] } })   // eski pod: cc alanı yok
    let dlg = await openSendDialog()
    fireEvent.change(ccInput(dlg), { target: { value: 'ek@example.com' } })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
    await waitFor(() => expect(te().send).toHaveBeenCalledWith('100', { extra_cc: 'ek@example.com' }))
    expect(await screen.findByText('Rapor gönderildi: dijital@example.com')).toBeInTheDocument()
    expect(await screen.findByText(/sunucu ek Bilgi \(CC\) adreslerini onaylamadı/)).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
    // Ek Bilgi'siz gönderimde (yanıtta cc olmasa da) uyarı yok
    te().send.mockClear()
    fireEvent.click(screen.getByRole('button', { name: /Takıma gönder|Yeniden gönder/ }))
    dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
    await waitFor(() => expect(te().send).toHaveBeenCalledWith('100'))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
    expect(screen.getAllByText(/sunucu ek Bilgi \(CC\) adreslerini onaylamadı/)).toHaveLength(1)   // yalnız ilk gönderimden
  })

  it('inceleme 2026-10-18 (2. tur): Kime boş döndü (yöneticilerin hepsi pasif, posta yalnız Bilgi alıcılarına gitti) → bildirim boş liste yazmaz, Bilgi alıcılarını söyler; ikisi de boşsa düz "gönderildi"', async () => {
    te().send.mockResolvedValue({ success: true, data: { state: 'SENT', to: [], cc: ['dijital@example.com'] } })
    let dlg = await openSendDialog()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
    expect(await screen.findByText('Rapor yalnız Bilgi (CC) alıcılarına gönderildi: dijital@example.com. Kime adreslerinin hepsi pasif kullanıcılara ait.')).toBeInTheDocument()
    expect(screen.queryByText(/^Rapor gönderildi: $/)).toBeNull()
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
    te().send.mockResolvedValue({ success: true, data: { state: 'SENT', to: [], cc: [] } })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Takıma gönder' })).not.toBeDisabled())
    fireEvent.click(screen.getByRole('button', { name: 'Takıma gönder' }))
    dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
    expect(await screen.findByText('Rapor gönderildi.')).toBeInTheDocument()
  })

  it('başka hata (ör. 502): pencere kapanır, bildirim; vazgeç istek atmaz ve bir sonraki açılış boş başlar', async () => {
    te().send.mockResolvedValue({ success: false, status: 502, code: 'MAIL_FAILED', error: 'E-posta sunucusu raporu kabul etmedi. Birkaç dakika sonra tekrar deneyin.' })
    let dlg = await openSendDialog()
    fireEvent.change(ccInput(dlg), { target: { value: 'ek@example.com' } })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
    expect(te().send).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Takıma gönder' }))
    dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
    expect(ccInput(dlg)).toHaveValue('')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
    expect(await screen.findByText('E-posta sunucusu raporu kabul etmedi. Birkaç dakika sonra tekrar deneyin.')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
  })

  it('inceleme 2026-10-18: yeniden denenebilir hata (429 PDF sırası) pencereyi KAPATMAZ — hata pencerenin içinde, yazılan ek Bilgi korunur, rapor arkada yenilenir; ikinci deneme aynı ek Bilgi ile gider', async () => {
    const busyMsg = 'PDF sırası dolu; birkaç saniye sonra yeniden deneyin.'
    te().send
      .mockResolvedValueOnce({ success: false, status: 429, code: 'HTTP_429', error: busyMsg })
      .mockResolvedValueOnce({ success: true, data: { state: 'SENT', to: ['dijital@example.com'], cc: ['ek@example.com'] } })
    const dlg = await openSendDialog()
    const input = ccInput(dlg)
    fireEvent.change(input, { target: { value: 'ek@example.com' } })
    const loadsBefore = te().report.mock.calls.length
    fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
    const failure = await within(dlg).findByText(new RegExp(busyMsg.replace(/[.;]/g, '.')))
    expect(failure.closest('[data-slot="te-send-failure"]')).toHaveAttribute('data-kind', 'retry')
    expect(within(dlg).getByText('Rapor gönderilmedi — yeniden deneyebilirsiniz')).toBeInTheDocument()
    expect(within(dlg).getByText(/Yazdığınız ek Bilgi \(CC\) adresleri korundu/)).toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeInTheDocument()
    expect(input).toHaveValue('ek@example.com')
    // Alan hatası DEĞİL: alan geçersiz işaretlenmez; odak alana döner (Enter gönder düğmesinde kalmasın)
    expect(input).not.toHaveAttribute('aria-invalid', 'true')
    await waitFor(() => expect(input).toHaveFocus())
    await waitFor(() => expect(te().report.mock.calls.length).toBeGreaterThan(loadsBefore))
    // 429 kilitlemez: ikinci deneme aynı ek Bilgi ile gider ve pencere kapanır
    expect(within(dlg).getByRole('button', { name: 'Gönder' })).not.toBeDisabled()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
    await waitFor(() => expect(te().send).toHaveBeenCalledTimes(2))
    expect(te().send.mock.calls.map((c) => c[1])).toEqual([{ extra_cc: 'ek@example.com' }, { extra_cc: 'ek@example.com' }])
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
  })

  describe('inceleme 2026-10-18 (3. tur): sonucu BİLİNMEYEN gönderim — rapor yeniden okunup durum bilinene kadar "Gönder" ve Ctrl+Enter KİLİTLİ', () => {
    const base = () => reportResponse().data.report
    const reportWith = (patch) => reportResponse({ report: { ...base(), ...patch } })
    const sentReport = () => reportWith({ status: 'sent', send_state: 'SENT', last_sent_at: '2026-10-18T07:00:05Z', last_attempt_at: '2026-10-18T07:00:05Z', send_count: 1 })
    const netError = async () => {
      const { ApiError } = await import('../utils/errorMessages.js')
      return new ApiError('Sunucuya ulaşılamadı. Bağlantınızı kontrol edip yeniden deneyin.', { status: 0, code: 'NETWORK_ERROR' })
    }
    const failureBox = (dlg) => dlg.querySelector('[data-slot="te-send-failure"]')

    it('ağ hatası: okuma sürerken kilitli (düğme kapalı, Ctrl+Enter göndermez, düzenleyicinin gönder düğmesi de bekler); rapor GÖNDERİLMİŞ okunursa pencere kapanır, "gönderilmiş" bildirimi — ikinci gönderim YOK', async () => {
      te().send.mockRejectedValueOnce(await netError())
      const dlg = await openSendDialog()
      const input = ccInput(dlg)
      fireEvent.change(input, { target: { value: 'ek@example.com' } })
      let release
      te().report.mockImplementationOnce(() => new Promise((r) => { release = r }))
      fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
      await waitFor(() => expect(failureBox(dlg)).not.toBeNull())
      const box = failureBox(dlg)
      expect(box).toHaveAttribute('data-kind', 'unsure')
      expect(within(box).getByText('Gönderimin sonucu bilinmiyor')).toBeInTheDocument()
      expect(box).toHaveTextContent(/Sunucuya ulaşılamadı/)
      expect(box).toHaveTextContent(/raporun durumu yeniden okunuyor/)
      await waitFor(() => expect(release).toBeTypeOf('function'))
      const confirm = within(dlg).getByRole('button', { name: 'Gönder' })
      expect(confirm).toBeDisabled()
      expect(confirm).toHaveAttribute('data-locked', 'true')
      expect(confirm).toHaveAttribute('aria-busy', 'true')
      fireEvent.click(confirm)
      fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', ctrlKey: true })
      fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', metaKey: true })
      expect(te().send).toHaveBeenCalledTimes(1)
      expect(input).toHaveValue('ek@example.com')
      expect(document.querySelector('[data-slot="te-send"]')).toBeDisabled()
      await act(async () => { release(sentReport()) })
      expect(await screen.findByText('Rapor gönderilmiş: yanıt gelmeden önce gönderim sunucuda tamamlanmıştı. Rapor yeniden gönderilmedi.')).toBeInTheDocument()
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
      expect(te().send).toHaveBeenCalledTimes(1)
    })

    it.each([
      ['409 SEND_IN_PROGRESS (gönderim sürüyor)', () => ({ success: false, status: 409, code: 'SEND_IN_PROGRESS', error: 'Bu rapor şu anda gönderiliyor.' })],
      ['503 DB_UNAVAILABLE (posta gitmiş, sonuç yazılamamış olabilir)', () => ({ success: false, status: 503, code: 'DB_UNAVAILABLE', error: 'Veritabanına şu anda ulaşılamıyor.' })],
    ])('%s da "yeniden dene" değil, belirsiz: rapor gönderilmiş okunursa pencere kapanır', async (_label, res) => {
      te().send.mockResolvedValueOnce(res())
      const dlg = await openSendDialog()
      fireEvent.change(ccInput(dlg), { target: { value: 'ek@example.com' } })
      te().report.mockResolvedValueOnce(sentReport())
      fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
      expect(await screen.findByText(/Rapor gönderilmiş: yanıt gelmeden önce/)).toBeInTheDocument()
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
      expect(te().send).toHaveBeenCalledTimes(1)
    })

    it('409 SEND_IN_PROGRESS + rapor "gönderiliyor" okunur → pencere kapanır, "sürüyor, yeniden göndermeyin" bildirimi; düzenleyicinin gönder düğmesi kapalı', async () => {
      te().send.mockResolvedValueOnce({ success: false, status: 409, code: 'SEND_IN_PROGRESS', error: 'Bu rapor şu anda gönderiliyor.' })
      const dlg = await openSendDialog()
      te().report.mockResolvedValue(reportWith({ status: 'sending', send_state: 'SENDING' }))
      fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
      expect(await screen.findByText(/Rapor şu anda gönderiliyor; sonuç birkaç dakika içinde raporda/)).toBeInTheDocument()
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
      await waitFor(() => expect(document.querySelector('[data-slot="te-send"]')).toBeDisabled())
      expect(te().send).toHaveBeenCalledTimes(1)
    })

    it('ağ hatası + rapor KUYRUKTA okunur → pencere kapanır, kuyruk bildirimi', async () => {
      te().send.mockRejectedValueOnce(await netError())
      const dlg = await openSendDialog()
      te().report.mockResolvedValueOnce(reportWith({ status: 'queued', send_state: 'QUEUED' }))
      fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
      expect(await screen.findByText(/Posta sunucusu yoğun; rapor sıraya alındı/)).toBeInTheDocument()
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
      expect(te().send).toHaveBeenCalledTimes(1)
    })

    it('ağ hatası + rapor açıkça GİTMEMİŞ okunur → kilit kalkar, "gönderilmemiş" bandı, yazılan ek Bilgi korunur; ikinci deneme aynı ek Bilgi ile gider', async () => {
      te().send
        .mockRejectedValueOnce(await netError())
        .mockResolvedValueOnce({ success: true, data: { state: 'SENT', to: ['dijital@example.com'], cc: ['ek@example.com'] } })
      const dlg = await openSendDialog()
      const input = ccInput(dlg)
      fireEvent.change(input, { target: { value: 'ek@example.com' } })
      const loadsBefore = te().report.mock.calls.length
      fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
      expect(await within(dlg).findByText('Rapor gönderilmemiş — yeniden gönderebilirsiniz')).toBeInTheDocument()
      expect(failureBox(dlg)).toHaveAttribute('data-kind', 'retry')
      expect(failureBox(dlg)).toHaveTextContent(/bu gönderim gitmemiş/)
      expect(te().report.mock.calls.length).toBe(loadsBefore + 1)      // tek, beklenen okuma
      expect(input).toHaveValue('ek@example.com')
      const confirm = within(dlg).getByRole('button', { name: 'Gönder' })
      await waitFor(() => expect(confirm).not.toBeDisabled())
      expect(confirm).not.toHaveAttribute('data-locked')
      fireEvent.click(confirm)
      await waitFor(() => expect(te().send).toHaveBeenCalledTimes(2))
      expect(te().send.mock.calls.map((c) => c[1])).toEqual([{ extra_cc: 'ek@example.com' }, { extra_cc: 'ek@example.com' }])
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
    })

    it('ağ hatası + rapor OKUNAMADI → kilit kalır, "durumu yeniden denetle"; pencere kapatılıp yeniden açılsa da kilitli açılır ve durumu yeniden denetler; gitmemiş okununca açılır', async () => {
      te().send
        .mockRejectedValueOnce(await netError())
        .mockResolvedValueOnce({ success: true, data: { state: 'SENT', to: ['dijital@example.com'], cc: [] } })
      let dlg = await openSendDialog()
      te().report.mockResolvedValueOnce({ success: false, status: 0, code: 'NETWORK_ERROR', error: 'Sunucuya ulaşılamadı.' })
      fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
      expect(await within(dlg).findByText(/Raporun durumu okunamadı, gönderimin gidip gitmediği hâlâ bilinmiyor/)).toBeInTheDocument()
      expect(failureBox(dlg)).toHaveAttribute('data-kind', 'unsure')
      expect(within(dlg).getByRole('button', { name: 'Gönder' })).toBeDisabled()
      const recheck = within(dlg).getByRole('button', { name: 'Durumu yeniden denetle' })
      expect(recheck).toHaveAttribute('data-slot', 'te-send-recheck')
      // Yeniden denetim de okunamazsa kilit sürer
      te().report.mockResolvedValueOnce({ success: false, status: 503, code: 'DB_UNAVAILABLE', error: 'Veritabanı yok.' })
      const loads = te().report.mock.calls.length
      fireEvent.click(recheck)
      await waitFor(() => expect(te().report.mock.calls.length).toBe(loads + 1))
      expect(await within(dlg).findByRole('button', { name: 'Durumu yeniden denetle' })).toBeInTheDocument()
      expect(within(dlg).getByRole('button', { name: 'Gönder' })).toBeDisabled()
      // Kapatıp yeniden açmak kilidi KALDIRMAZ: pencere kilitli açılır ve durum hemen yeniden okunur
      fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
      let releaseReopen
      te().report.mockImplementationOnce(() => new Promise((r) => { releaseReopen = r }))
      await waitFor(() => expect(document.querySelector('[data-slot="te-send"]')).not.toBeDisabled())
      fireEvent.click(screen.getByRole('button', { name: 'Takıma gönder' }))
      dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
      await waitFor(() => expect(releaseReopen).toBeTypeOf('function'))
      expect(failureBox(dlg)).toHaveAttribute('data-kind', 'unsure')
      expect(within(dlg).getByRole('button', { name: 'Gönder' })).toBeDisabled()
      fireEvent.keyDown(ccInput(dlg), { key: 'Enter', code: 'Enter', ctrlKey: true })
      expect(te().send).toHaveBeenCalledTimes(1)
      await act(async () => { releaseReopen(reportResponse()) })
      expect(await within(dlg).findByText('Rapor gönderilmemiş — yeniden gönderebilirsiniz')).toBeInTheDocument()
      await waitFor(() => expect(within(dlg).getByRole('button', { name: 'Gönder' })).not.toBeDisabled())
      fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
      await waitFor(() => expect(te().send).toHaveBeenCalledTimes(2))
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
    })
  })

  it('inceleme 2026-10-18 (3. tur): PDF geldi ama tarayıcı indirmeyi başlatmadı (DOWNLOAD_FAILED) → sessiz başarı değil, çevrilmiş açıklayıcı metin', async () => {
    te().downloadPdf.mockResolvedValue({ success: false, status: 0, code: 'DOWNLOAD_FAILED', error: null })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'PDF indir' }))
    expect(await screen.findByText('PDF hazırlandı ama tarayıcı indirmeyi başlatmadı. Sayfayı yenileyip yeniden “PDF indir”e basın; sorun sürerse tarayıcının indirme iznini ve boş disk alanını denetleyin.')).toBeInTheDocument()
    expect(te().downloadPdf).toHaveBeenCalledWith('100')
  })

  it('gönderemeyen (paylaşımla düzenleyen, can_send:false): gönder düğmesi ve pencere yok', async () => {
    te().report.mockResolvedValue(reportResponse({ can_send: false }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(document.querySelector('[data-slot="te-send"]')).toBeNull()
    expect(document.querySelector('[data-slot="te-send-dialog"]')).toBeNull()
  })
})
