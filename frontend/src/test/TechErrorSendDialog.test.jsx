import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
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

    it('ağ hatası + rapor OKUNAMADI → kilit kalır, "durumu yeniden denetle"; pencere kapatılıp yeniden açılsa da kilitli açılır, yazılan ek Bilgi ALANDA (4. tur) ve durumu yeniden denetler; gitmemiş okununca açılır, ikinci gönderim aynı ek Bilgi ile', async () => {
      te().send
        .mockRejectedValueOnce(await netError())
        .mockResolvedValueOnce({ success: true, data: { state: 'SENT', to: ['dijital@example.com'], cc: ['ek@example.com', 'iki@example.com'] } })
      let dlg = await openSendDialog()
      fireEvent.change(ccInput(dlg), { target: { value: 'ek@example.com; iki@example.com' } })
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
      // Bant "ek Bilgi korundu" der — alan da o denemenin adresleriyle açılır (eskiden boş açılıyor, rapor ek adreslersiz gidiyordu)
      expect(failureBox(dlg)).toHaveTextContent(/Yazdığınız ek Bilgi \(CC\) adresleri korundu/)
      expect(ccInput(dlg)).toHaveValue('ek@example.com, iki@example.com')
      expect(within(dlg).getByRole('button', { name: 'Gönder' })).toBeDisabled()
      fireEvent.keyDown(ccInput(dlg), { key: 'Enter', code: 'Enter', ctrlKey: true })
      expect(te().send).toHaveBeenCalledTimes(1)
      await act(async () => { releaseReopen(reportResponse()) })
      expect(await within(dlg).findByText('Rapor gönderilmemiş — yeniden gönderebilirsiniz')).toBeInTheDocument()
      expect(ccInput(dlg)).toHaveValue('ek@example.com, iki@example.com')
      await waitFor(() => expect(within(dlg).getByRole('button', { name: 'Gönder' })).not.toBeDisabled())
      fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
      await waitFor(() => expect(te().send).toHaveBeenCalledTimes(2))
      expect(te().send.mock.calls.map((c) => c[1])).toEqual([
        { extra_cc: 'ek@example.com, iki@example.com' }, { extra_cc: 'ek@example.com, iki@example.com' }])
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
    })

    it('4. tur: pencere denetim sürerken kapatıldı ve rapor GİTMEMİŞ okundu → bildirim söyler; yeniden açılış "gitmemiş" bandı + yazılan ek Bilgi ile, kilitsiz', async () => {
      te().send
        .mockRejectedValueOnce(await netError())
        .mockResolvedValueOnce({ success: true, data: { state: 'SENT', to: ['dijital@example.com'], cc: ['ek@example.com'] } })
      let dlg = await openSendDialog()
      fireEvent.change(ccInput(dlg), { target: { value: 'ek@example.com' } })
      let release
      te().report.mockImplementationOnce(() => new Promise((r) => { release = r }))
      fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
      await waitFor(() => expect(release).toBeTypeOf('function'))
      fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
      await act(async () => { release(reportResponse()) })
      expect(await screen.findByText('Raporun durumu yeniden okundu: son gönderim gitmemiş. Yeniden göndermek için gönder düğmesine basın; pencere yazdığınız ek Bilgi (CC) adresleriyle açılır.')).toBeInTheDocument()
      await waitFor(() => expect(document.querySelector('[data-slot="te-send"]')).not.toBeDisabled())
      fireEvent.click(screen.getByRole('button', { name: 'Takıma gönder' }))
      dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
      expect(within(dlg).getByText('Rapor gönderilmemiş — yeniden gönderebilirsiniz')).toBeInTheDocument()
      expect(ccInput(dlg)).toHaveValue('ek@example.com')
      expect(within(dlg).getByRole('button', { name: 'Gönder' })).not.toBeDisabled()
      fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
      await waitFor(() => expect(te().send).toHaveBeenCalledTimes(2))
      expect(te().send.mock.calls[1][1]).toEqual({ extra_cc: 'ek@example.com' })
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
      // Kesin sonuçtan sonra yeni açılış boş ve bantsız
      fireEvent.click(await screen.findByRole('button', { name: /Takıma gönder|Yeniden gönder/ }))
      dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
      expect(ccInput(dlg)).toHaveValue('')
      expect(failureBox(dlg)).toBeNull()
    })

    describe('20.157.0 incelemesi: "gitmemiş" bandından ÖNCE rapor yeniden okunur — başka sekme / kişi arada göndermiş olabilir', () => {
      /** Belirsiz gönderim → "gitmemiş" okunur, pencere kapatılır (yeniden açılış bandı bekliyor). */
      async function notSentThenClosed() {
        te().send.mockRejectedValueOnce(await netError())
        const dlg = await openSendDialog()
        fireEvent.change(ccInput(dlg), { target: { value: 'ek@example.com' } })
        fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
        expect(await within(dlg).findByText('Rapor gönderilmemiş — yeniden gönderebilirsiniz')).toBeInTheDocument()
        await waitFor(() => expect(within(dlg).getByRole('button', { name: 'Vazgeç' })).not.toBeDisabled())
        fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
        await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
        await waitFor(() => expect(document.querySelector('[data-slot="te-send"]')).not.toBeDisabled())
      }

      it('arada başkası GÖNDERDİ (taze satırda yeni gönderim) → bant YOK, pencere taze ve boş; satırın gönderim durumu güncellenir', async () => {
        await notSentThenClosed()
        const loads = te().report.mock.calls.length
        te().report.mockResolvedValue(sentReport())
        fireEvent.click(screen.getByRole('button', { name: 'Takıma gönder' }))
        const dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
        expect(te().report.mock.calls.length).toBe(loads + 1)           // açılıştan önce taze okuma
        expect(failureBox(dlg)).toBeNull()
        expect(within(dlg).queryByText('Rapor gönderilmemiş — yeniden gönderebilirsiniz')).toBeNull()
        expect(ccInput(dlg)).toHaveValue('')
        await waitFor(() => expect(document.querySelector('[data-slot="te-status"]')).toHaveAttribute('data-status', 'sent'))
        expect(te().send).toHaveBeenCalledTimes(1)
      })

      it('taze satır hâlâ yeni gönderim göstermiyor → bant + yazılan ek Bilgi; okuma sürerken düğme bekler', async () => {
        await notSentThenClosed()
        let release
        te().report.mockImplementationOnce(() => new Promise((r) => { release = r }))
        fireEvent.click(screen.getByRole('button', { name: 'Takıma gönder' }))
        await waitFor(() => expect(release).toBeTypeOf('function'))
        expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull()
        expect(document.querySelector('[data-slot="te-send"]')).toBeDisabled()
        await act(async () => { release(reportResponse()) })
        const dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
        expect(within(dlg).getByText('Rapor gönderilmemiş — yeniden gönderebilirsiniz')).toBeInTheDocument()
        expect(ccInput(dlg)).toHaveValue('ek@example.com')
      })

      it('taze okuma BAŞARISIZ → "gitmemiş" denmez (bilinmiyor): pencere bantsız ama yazılan ek Bilgi ALANDA; sonraki açılış yeniden denetler ve bandı getirir', async () => {
        await notSentThenClosed()
        te().report.mockResolvedValueOnce({ success: false, status: 503, code: 'DB_UNAVAILABLE', error: 'Veritabanı yok.' })
        fireEvent.click(screen.getByRole('button', { name: 'Takıma gönder' }))
        let dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
        expect(failureBox(dlg)).toBeNull()
        // Boş açılış ek adresleri sessizce düşürürdü (gönderim geri alınamaz)
        expect(ccInput(dlg)).toHaveValue('ek@example.com')
        fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
        await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
        await waitFor(() => expect(document.querySelector('[data-slot="te-send"]')).not.toBeDisabled())
        fireEvent.click(screen.getByRole('button', { name: 'Takıma gönder' }))
        dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
        expect(within(dlg).getByText('Rapor gönderilmemiş — yeniden gönderebilirsiniz')).toBeInTheDocument()
        expect(ccInput(dlg)).toHaveValue('ek@example.com')
      })
    })

    it('4. tur: 500 (posta servise verildikten sonra düşmüş olabilir) da belirsiz — "yeniden dene" / kapanma değil, rapor yeniden okunur', async () => {
      te().send.mockResolvedValueOnce({ success: false, status: 500, code: 'INTERNAL_ERROR', error: 'Beklenmeyen bir sunucu hatası oluştu.' })
      const dlg = await openSendDialog()
      fireEvent.change(ccInput(dlg), { target: { value: 'ek@example.com' } })
      te().report.mockResolvedValueOnce(sentReport())
      fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
      expect(await screen.findByText(/Rapor gönderilmiş: yanıt gelmeden önce/)).toBeInTheDocument()
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
      expect(te().send).toHaveBeenCalledTimes(1)
    })

    it('4. tur: tıklamadan ÖNCE kuyrukta bekleyen gönderim vardı; yanıt kayboldu ve okuma ESKİ kuyruğu teslim etti (last_attempt_at aynı) → "zaten gönderilmiş" DEĞİL, "gitmemiş" bandı + ek Bilgi', async () => {
      const queued = { status: 'sent', send_state: 'QUEUED', last_sent_at: '2026-10-01T08:00:00Z', last_attempt_at: '2026-10-17T09:00:00Z', send_count: 1 }
      te().report.mockResolvedValue(reportWith(queued))
      te().send.mockRejectedValueOnce(await netError())
      render(<TechErrorReportsPage />)
      await screen.findByRole('heading', { name: TITLE })
      fireEvent.click(screen.getByRole('button', { name: /Takıma gönder|Yeniden gönder/ }))
      const dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
      fireEvent.change(ccInput(dlg), { target: { value: 'ek@example.com' } })
      te().report.mockResolvedValueOnce(reportWith({ ...queued, send_state: 'SENT', last_sent_at: '2026-10-17T09:00:00Z', send_count: 2 }))
      fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
      expect(await within(dlg).findByText('Rapor gönderilmemiş — yeniden gönderebilirsiniz')).toBeInTheDocument()
      expect(screen.queryByText(/Rapor gönderilmiş: yanıt gelmeden önce/)).toBeNull()
      expect(ccInput(dlg)).toHaveValue('ek@example.com')
      expect(te().send).toHaveBeenCalledTimes(1)
    })

    describe('4. tur: "sürüyor" / "kuyrukta" sonrası gönderim durumu yoklanır', () => {
      beforeEach(() => { vi.useFakeTimers({ shouldAdvanceTime: true }) })
      afterEach(() => { vi.useRealTimers() })

      it('sürüyor okununca 10 sn arayla yoklanır; gönderildi okununca "Gönder" açılır, bildirim, yoklama durur; taslağa dokunulmaz', async () => {
        te().send.mockResolvedValueOnce({ success: false, status: 409, code: 'SEND_IN_PROGRESS', error: 'Bu rapor şu anda gönderiliyor.' })
        const dlg = await openSendDialog()
        te().report.mockResolvedValue(reportWith({ status: 'sending', send_state: 'SENDING' }))
        fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
        expect(await screen.findByText(/Rapor şu anda gönderiliyor; sonuç birkaç dakika içinde raporda/)).toBeInTheDocument()
        await waitFor(() => expect(document.querySelector('[data-slot="te-send"]')).toBeDisabled())
        const loads = te().report.mock.calls.length
        // Bir tur: hâlâ sürüyor → yoklama sürer
        await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
        await waitFor(() => expect(te().report.mock.calls.length).toBe(loads + 1))
        expect(document.querySelector('[data-slot="te-send"]')).toBeDisabled()
        // Sonraki tur: gönderildi (sürüm DE değişmiş — taban ilerletilmez; yalnız gönderim alanları)
        te().report.mockResolvedValue(reportWith({ status: 'sent', send_state: 'SENT', last_sent_at: '2026-10-18T07:00:05Z', last_attempt_at: '2026-10-18T07:00:05Z', send_count: 1, version: 9 }))
        await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
        expect(await screen.findByText('Gönderim tamamlandı: rapor gönderildi. Alıcılar ve ayrıntılar kanalın gönderim günlüğünde.')).toBeInTheDocument()
        await waitFor(() => expect(document.querySelector('[data-slot="te-send"]')).not.toBeDisabled())
        // Yoklama durdu: süre geçse de yeni okuma yok
        const after = te().report.mock.calls.length
        await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
        expect(te().report.mock.calls.length).toBe(after)
        expect(te().saveReport).not.toHaveBeenCalled()
      })

      it('kuyrukta okunur, posta servisi sonunda başarısız → "gönderilemedi" bildirimi; 10 dakikada sonuç gelmezse yoklama durur ve söyler', async () => {
        te().send.mockRejectedValueOnce(await netError())
        const dlg = await openSendDialog()
        te().report.mockResolvedValue(reportWith({ status: 'queued', send_state: 'QUEUED', last_attempt_at: '2026-10-18T07:00:05Z' }))
        fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
        expect(await screen.findByText(/Posta sunucusu yoğun; rapor sıraya alındı/)).toBeInTheDocument()
        te().report.mockResolvedValue(reportWith({ status: 'draft', send_state: 'FAILED', last_attempt_at: '2026-10-18T07:00:05Z' }))
        await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
        expect(await screen.findByText(/Gönderim tamamlanamadı: rapor gönderilmedi/)).toBeInTheDocument()
      })

      it('10 dakikada kesinleşmeyen gönderim: yoklama durur, "sonuç gelmedi" bildirimi', async () => {
        te().send.mockResolvedValueOnce({ success: true, data: { state: 'QUEUED', to: ['dijital@example.com'], cc: [] } })
        const dlg = await openSendDialog()
        te().report.mockResolvedValue(reportWith({ status: 'queued', send_state: 'QUEUED', last_attempt_at: '2026-10-18T07:00:05Z' }))
        fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
        expect(await screen.findByText(/Posta sunucusu yoğun; rapor sıraya alındı/)).toBeInTheDocument()
        await act(async () => { await vi.advanceTimersByTimeAsync(10 * 60_000 + 10_000) })
        expect(await screen.findByText('Gönderimin sonucu 10 dakika içinde kesinleşmedi. Durumu sayfayı yenileyerek ya da kanalın gönderim günlüğünden izleyin; yeniden göndermeden önce günlüğe bakın.')).toBeInTheDocument()
        const after = te().report.mock.calls.length
        await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
        expect(te().report.mock.calls.length).toBe(after)
      })

      // İnceleme 2026-10-18 (5. tur): yoklama raporun ŞU ANKİ durumuna değil gönderimden ÖNCEKİ satıra (ya da bu denemenin
      // kuyruğuna) bakar. Bırakılan talep (releaseClaim — PDF sırası dolu, onay eskidi) satırı önceki durumuna döndürür.
      describe('5. tur: bırakılan talep satırı önceki durumuna döndürür — karar gönderimden ÖNCEKİ satıra göre', () => {
        const sentBefore = { status: 'sent', send_state: 'SENT', last_send_status: 'SENT', last_sent_at: '2026-10-01T08:00:00Z',
          last_attempt_at: '2026-10-01T08:00:00Z', send_count: 1 }
        const queuedBefore = { status: 'sent', send_state: 'QUEUED', last_send_status: 'QUEUED', last_sent_at: '2026-10-01T08:00:00Z',
          last_attempt_at: '2026-10-17T09:00:00Z', send_count: 1 }
        const NOT_SENT_CC = 'Gönderim tamamlanamadı: rapor gönderilmedi. Nedeni kanalın gönderim günlüğünde; yeniden göndermek için gönder düğmesine basın — pencere yazdığınız ek Bilgi (CC) adresleriyle açılır.'
        const NOT_SENT = 'Gönderim tamamlanamadı: rapor gönderilmedi. Nedeni kanalın gönderim günlüğünde; sorunu giderip raporu yeniden gönderebilirsiniz.'

        /** Sonucu bilinmeyen gönderim; denetim okuması "sürüyor" der → yoklama başlar. Sonraki okumalar `beforePatch` satırı. */
        async function unsureThenSending(beforePatch, cc) {
          if (beforePatch) te().report.mockResolvedValue(reportWith(beforePatch))
          te().send.mockRejectedValueOnce(await netError())
          render(<TechErrorReportsPage />)
          await screen.findByRole('heading', { name: TITLE })
          fireEvent.click(screen.getByRole('button', { name: /Takıma gönder|Yeniden gönder/ }))
          const dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
          if (cc) fireEvent.change(ccInput(dlg), { target: { value: cc } })
          te().report.mockResolvedValueOnce(reportWith({ ...(beforePatch || {}), status: 'sending', send_state: 'SENDING' }))
          fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
          expect(await screen.findByText(/Rapor şu anda gönderiliyor; sonuç birkaç dakika içinde raporda/)).toBeInTheDocument()
          await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
          await waitFor(() => expect(document.querySelector('[data-slot="te-send"]')).toBeDisabled())
        }

        it('rapor önceden GÖNDERİLMİŞTİ, talep bırakıldı (SENT\'e döndü) → "gönderildi" DEĞİL: "gitmedi" (ek Bilgi ile), yoklama durur; yeniden açılış "gitmemiş" bandı + yazılan ek Bilgi', async () => {
          await unsureThenSending(sentBefore, 'ek@example.com')
          await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
          expect(await screen.findByText(NOT_SENT_CC)).toBeInTheDocument()
          expect(screen.queryByText(/Gönderim tamamlandı: rapor gönderildi/)).toBeNull()
          await waitFor(() => expect(document.querySelector('[data-slot="te-send"]')).not.toBeDisabled())
          const after = te().report.mock.calls.length
          await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
          expect(te().report.mock.calls.length).toBe(after)
          fireEvent.click(screen.getByRole('button', { name: 'Yeniden gönder' }))
          const dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
          expect(within(dlg).getByText('Rapor gönderilmemiş — yeniden gönderebilirsiniz')).toBeInTheDocument()
          expect(failureBox(dlg)).toHaveTextContent(/Yazdığınız ek Bilgi \(CC\) adresleri korundu/)
          expect(ccInput(dlg)).toHaveValue('ek@example.com')
          expect(within(dlg).getByRole('button', { name: 'Gönder' })).not.toBeDisabled()
          expect(te().send).toHaveBeenCalledTimes(1)
        })

        it.each([
          ['eski kuyruğa döndü (değişmedi)', {}],
          ['okuma ESKİ kuyruğu teslim etti (confirmQueued — last_attempt_at aynı)',
            { send_state: 'SENT', last_send_status: 'SENT', last_sent_at: '2026-10-17T09:00:00Z', send_count: 2 }],
        ])('rapor önceden KUYRUKTA, talep bırakıldı; %s → eski kuyruğun sonucu bu gönderimin sonucu DEĞİL: "gitmedi"', async (_name, patch) => {
          await unsureThenSending(queuedBefore, 'ek@example.com')
          te().report.mockResolvedValue(reportWith({ ...queuedBefore, ...patch }))
          await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
          expect(await screen.findByText(NOT_SENT_CC)).toBeInTheDocument()
          expect(screen.queryByText(/Gönderim tamamlandı: rapor gönderildi/)).toBeNull()
          await waitFor(() => expect(document.querySelector('[data-slot="te-send"]')).not.toBeDisabled())
          fireEvent.click(screen.getByRole('button', { name: 'Yeniden gönder' }))
          const dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
          expect(ccInput(dlg)).toHaveValue('ek@example.com')
        })

        it('hiç GÖNDERİLMEMİŞ rapor, talep bırakıldı (taslağa döndü) → İLK turda "gitmedi" — "10 dakikada kesinleşmedi" DEĞİL; ek Bilgi yoksa bant "korundu" demez', async () => {
          await unsureThenSending(null, '')
          await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
          expect(await screen.findByText(NOT_SENT)).toBeInTheDocument()
          expect(screen.queryByText(/dakika içinde kesinleşmedi/)).toBeNull()
          await waitFor(() => expect(document.querySelector('[data-slot="te-send"]')).not.toBeDisabled())
          fireEvent.click(screen.getByRole('button', { name: 'Takıma gönder' }))
          const dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
          expect(failureBox(dlg)).toHaveTextContent('Raporun durumu yeniden okundu: bu gönderim gitmemiş. Yeniden göndermek için “Gönder”e basın.')
          expect(failureBox(dlg)).not.toHaveTextContent(/korundu/)
          expect(ccInput(dlg)).toHaveValue('')
        })

        it('normal kuyruk yanıtı: taban yanıttan HEMEN SONRAKİ satır (bu denemenin kuyruğu) — eldeki satır bayat olsa da kuyruğun başarısızlığı "gönderildi" sayılmaz; kuyrukta kaldıkça sürer', async () => {
          // Düzenleyicinin satırı bayat: arada başka biri göndermiş (sunucuda 2. gönderim); bu gönderim kuyruğa alınır
          te().report.mockResolvedValue(reportWith(sentBefore))
          te().send.mockResolvedValueOnce({ success: true, data: { state: 'QUEUED', to: ['dijital@example.com'], cc: ['ek@example.com'] } })
          render(<TechErrorReportsPage />)
          await screen.findByRole('heading', { name: TITLE })
          fireEvent.click(screen.getByRole('button', { name: 'Yeniden gönder' }))
          const dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
          fireEvent.change(ccInput(dlg), { target: { value: 'ek@example.com' } })
          const ownQueue = { ...sentBefore, send_state: 'QUEUED', last_send_status: 'QUEUED', last_sent_at: '2026-10-15T08:00:00Z',
            send_count: 2, last_attempt_at: '2026-10-18T07:00:05Z' }
          te().report.mockResolvedValue(reportWith(ownQueue))
          const loads = te().report.mock.calls.length
          fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
          expect(await screen.findByText(/Posta sunucusu yoğun; rapor sıraya alındı/)).toBeInTheDocument()
          await waitFor(() => expect(te().report.mock.calls.length).toBe(loads + 1))
          // Tur 1: hâlâ kuyrukta → sürer (eski kuyruk sayılıp "gitmedi" denmez)
          await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
          await waitFor(() => expect(te().report.mock.calls.length).toBe(loads + 2))
          expect(screen.queryByText(/Gönderim tamamlanamadı/)).toBeNull()
          // Tur 2: kuyruk başarısız (failQueued — son gönderim alanları aynı); bayat satıra göre "son gönderim değişti" sanılırdı
          te().report.mockResolvedValue(reportWith({ ...ownQueue, send_state: 'FAILED', last_send_status: 'FAILED: kuyruk' }))
          await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
          expect(await screen.findByText(NOT_SENT_CC)).toBeInTheDocument()
          expect(screen.queryByText(/Gönderim tamamlandı: rapor gönderildi/)).toBeNull()
          await waitFor(() => expect(document.querySelector('[data-slot="te-send"]')).not.toBeDisabled())
          fireEvent.click(screen.getByRole('button', { name: 'Yeniden gönder' }))
          const again = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
          expect(ccInput(again)).toHaveValue('ek@example.com')
          expect(within(again).getByText('Rapor gönderilmemiş — yeniden gönderebilirsiniz')).toBeInTheDocument()
        })

        it('normal kuyruk yanıtı + kuyruk teslim edildi (confirmQueued — deneme damgası aynı) → "gönderildi"', async () => {
          te().send.mockResolvedValueOnce({ success: true, data: { state: 'QUEUED', to: ['dijital@example.com'], cc: [] } })
          const dlg = await openSendDialog()
          const ownQueue = { status: 'queued', send_state: 'QUEUED', last_send_status: 'QUEUED', last_attempt_at: '2026-10-18T07:00:05Z' }
          te().report.mockResolvedValue(reportWith(ownQueue))
          fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
          expect(await screen.findByText(/Posta sunucusu yoğun; rapor sıraya alındı/)).toBeInTheDocument()
          te().report.mockResolvedValue(reportWith({ ...ownQueue, status: 'sent', send_state: 'SENT', last_send_status: 'SENT',
            last_sent_at: '2026-10-18T07:00:00Z', send_count: 1 }))
          await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
          expect(await screen.findByText('Gönderim tamamlandı: rapor gönderildi. Alıcılar ve ayrıntılar kanalın gönderim günlüğünde.')).toBeInTheDocument()
        })

        it('yoklama yanıtı kayıttan ÖNCE okunduysa (kayıt istekten önce başladı, yanıtı istekten sonra geldi): yanıttaki "sent" yerel "changed"ın üstüne YAZILMAZ', async () => {
          te().report.mockResolvedValue(reportWith(sentBefore))
          te().send.mockResolvedValueOnce({ success: true, data: { state: 'QUEUED', to: ['dijital@example.com'], cc: [] } })
          render(<TechErrorReportsPage />)
          await screen.findByRole('heading', { name: TITLE })
          fireEvent.click(screen.getByRole('button', { name: 'Yeniden gönder' }))
          const dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
          const queuedRow = { ...sentBefore, send_state: 'QUEUED', last_send_status: 'QUEUED', last_attempt_at: '2026-10-18T07:00:05Z' }
          te().report.mockResolvedValue(reportWith(queuedRow))
          const loads = te().report.mock.calls.length
          fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
          expect(await screen.findByText(/Posta sunucusu yoğun; rapor sıraya alındı/)).toBeInTheDocument()
          await waitFor(() => expect(te().report.mock.calls.length).toBe(loads + 1))
          const status = () => document.querySelector('[data-slot="te-status"]')
          await waitFor(() => expect(status()).toHaveAttribute('data-status', 'sent'))
          // Kayıt başlar; yanıtı bekletilir
          let releaseSave
          te().saveReport.mockImplementationOnce(() => new Promise((r) => { releaseSave = r }))
          fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '1.250.000' } })
          fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
          await waitFor(() => expect(releaseSave).toBeTypeOf('function'))
          // Yoklama isteği kayıttan SONRA gider; sunucu onu kaydın işlenmesinden önce okur ("sent")
          let releasePoll
          te().report.mockImplementationOnce(() => new Promise((r) => { releasePoll = r }))
          await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
          await waitFor(() => expect(releasePoll).toBeTypeOf('function'))
          await act(async () => {
            releaseSave({ success: true, data: { version: 5, changed: true, updated_at: '2026-10-18T07:00:20Z',
              values: { fields: { 'error-rate': 0.42, 'total-tx': 1250000 }, issues: {}, texts: {} } } })
          })
          await waitFor(() => expect(status()).toHaveAttribute('data-status', 'changed'))
          await act(async () => { releasePoll(reportWith(queuedRow)) })
          expect(status()).toHaveAttribute('data-status', 'changed')
          expect(screen.getByText('Rapor gönderildikten sonra değişti')).toBeInTheDocument()
          // Yoklama sürüyor (kuyruk): sonraki tur yine aynı kuralla
          await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
          expect(status()).toHaveAttribute('data-status', 'sent')   // istekten sonra yerel değişiklik yok: sunucunun durumu geçer
        })

        it('20.157.0 incelemesi: hiçbir şey DEĞİŞTİRMEYEN kayıt (changed: false) "yerel değişiklik" sayılmaz — yoklamanın "sent"i bayat "changed" rozetini kaldırır', async () => {
          te().report.mockResolvedValue(reportWith(sentBefore))
          te().send.mockResolvedValueOnce({ success: true, data: { state: 'QUEUED', to: ['dijital@example.com'], cc: [] } })
          render(<TechErrorReportsPage />)
          await screen.findByRole('heading', { name: TITLE })
          fireEvent.click(screen.getByRole('button', { name: 'Yeniden gönder' }))
          const dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
          const queuedRow = { ...sentBefore, send_state: 'QUEUED', last_send_status: 'QUEUED', last_attempt_at: '2026-10-18T07:00:05Z' }
          te().report.mockResolvedValue(reportWith(queuedRow))
          const loads = te().report.mock.calls.length
          fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
          expect(await screen.findByText(/Posta sunucusu yoğun; rapor sıraya alındı/)).toBeInTheDocument()
          await waitFor(() => expect(te().report.mock.calls.length).toBe(loads + 1))
          const status = () => document.querySelector('[data-slot="te-status"]')
          // İçeriği değiştiren kayıt (yoklama isteğinden ÖNCE bitti) yerelde "changed" yapar
          te().saveReport.mockResolvedValueOnce({ success: true, data: { version: 5, changed: true, updated_at: '2026-10-18T07:00:10Z',
            values: { fields: { 'error-rate': 0.42, 'total-tx': 1250000 }, issues: {}, texts: {} } } })
          fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '1.250.000' } })
          fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
          await waitFor(() => expect(status()).toHaveAttribute('data-status', 'changed'))
          // İkinci kayıt sunucuda hiçbir şey değiştirmez (changed: false); yanıtı yoklama isteği gittikten SONRA gelir
          let releaseSave
          te().saveReport.mockImplementationOnce(() => new Promise((r) => { releaseSave = r }))
          fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '1.300.000' } })
          fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
          await waitFor(() => expect(releaseSave).toBeTypeOf('function'))
          let releasePoll
          te().report.mockImplementationOnce(() => new Promise((r) => { releasePoll = r }))
          await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
          await waitFor(() => expect(releasePoll).toBeTypeOf('function'))
          await act(async () => {
            releaseSave({ success: true, data: { version: 6, changed: false, updated_at: '2026-10-18T07:00:20Z',
              values: { fields: { 'error-rate': 0.42, 'total-tx': 1300000 }, issues: {}, texts: {} } } })
          })
          expect(status()).toHaveAttribute('data-status', 'changed')
          // Kuyruk teslim edildi: sunucu "sent" der — değişmeyen kayıt bunu engellemez (eskiden bayat "değişti" kalıyordu)
          await act(async () => { releasePoll(reportWith({ ...queuedRow, send_state: 'SENT', last_send_status: 'SENT',
            last_sent_at: '2026-10-18T07:00:00Z', send_count: 2 })) })
          await waitFor(() => expect(status()).toHaveAttribute('data-status', 'sent'))
          expect(await screen.findByText('Gönderim tamamlandı: rapor gönderildi. Alıcılar ve ayrıntılar kanalın gönderim günlüğünde.')).toBeInTheDocument()
        })
      })
    })

    it('5. tur: "gitmemiş" sonrası yeniden açılış İLK kez o denemenin ek Bilgi\'siyle, sonra kullanıcının SON yazdığıyla (hatadan önceki adresler geri gelmez); alan boşaltılınca boş açılır, bant "korundu" demez; gönderim kesinleşince taze', async () => {
      te().send
        .mockRejectedValueOnce(await netError())
        .mockResolvedValueOnce({ success: true, data: { state: 'SENT', to: ['dijital@example.com'], cc: ['son@example.com'] } })
      let dlg = await openSendDialog()
      fireEvent.change(ccInput(dlg), { target: { value: 'ek@example.com' } })
      fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
      expect(await within(dlg).findByText('Rapor gönderilmemiş — yeniden gönderebilirsiniz')).toBeInTheDocument()
      expect(ccInput(dlg)).toHaveValue('ek@example.com')
      // Kullanıcı adresi değiştirir ve kapatır
      fireEvent.change(ccInput(dlg), { target: { value: 'yeni@example.com' } })
      fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
      await waitFor(() => expect(document.querySelector('[data-slot="te-send"]')).not.toBeDisabled())
      fireEvent.click(screen.getByRole('button', { name: 'Takıma gönder' }))
      dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
      expect(ccInput(dlg)).toHaveValue('yeni@example.com')
      expect(failureBox(dlg)).toHaveTextContent(/Yazdığınız ek Bilgi \(CC\) adresleri korundu/)
      // Alanı boşaltır, kapatır: sonraki açılış boş ve bant "korundu" demez
      fireEvent.change(ccInput(dlg), { target: { value: '' } })
      fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
      fireEvent.click(screen.getByRole('button', { name: 'Takıma gönder' }))
      dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
      expect(ccInput(dlg)).toHaveValue('')
      expect(within(dlg).getByText('Rapor gönderilmemiş — yeniden gönderebilirsiniz')).toBeInTheDocument()
      expect(failureBox(dlg)).not.toHaveTextContent(/korundu/)
      // Gönderim kesinleşir: sonraki açılış taze (boş, bantsız)
      fireEvent.change(ccInput(dlg), { target: { value: 'son@example.com' } })
      fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
      await waitFor(() => expect(te().send).toHaveBeenCalledTimes(2))
      expect(te().send.mock.calls[1][1]).toEqual({ extra_cc: 'son@example.com' })
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })).toBeNull())
      fireEvent.click(await screen.findByRole('button', { name: /Takıma gönder|Yeniden gönder/ }))
      dlg = await screen.findByRole('dialog', { name: 'Rapor takıma gönderilsin mi?' })
      expect(ccInput(dlg)).toHaveValue('')
      expect(failureBox(dlg)).toBeNull()
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
