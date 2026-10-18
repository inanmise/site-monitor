import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
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

  it('gönderemeyen (paylaşımla düzenleyen, can_send:false): gönder düğmesi ve pencere yok', async () => {
    te().report.mockResolvedValue(reportResponse({ can_send: false }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(document.querySelector('[data-slot="te-send"]')).toBeNull()
    expect(document.querySelector('[data-slot="te-send-dialog"]')).toBeNull()
  })
})
