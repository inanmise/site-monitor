import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, within } from './test-utils.jsx'
import Login from '../pages/Login.jsx'
import { sanitizeOtp } from '../components/login/OtpCodeInput.jsx'
import { announceMilestone, secondsLeft } from '../components/login/OtpCountdown.jsx'
import { otpErrorOf, availableChannels } from '../components/login/OtpLoginFlow.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    login: vi.fn(),
    getPublicStats: vi.fn(async () => ({ success: true, data: { monitored_targets: 1, availability_pct: 99.9 } })),
    getSystemMaintenanceStatus: vi.fn(async () => ({ success: true, data: { state: 'none' } })),
    getLoginMethods: vi.fn(),
    loginOtp: { request: vi.fn(), verify: vi.fn() },
  }),
}))

import { api } from '../api/client'

/**
 * Kodla giriş (2026-10-02): ana form AYNEN kalır; altına yalnız AÇIK yöntemlerin düğmeleri gelir. Akış: istek → 6 kutu +
 * geri sayım (sahte saat) → doğrulama; yanlış kod + kalan deneme; süre doldu → yeniden gönder; kilit → yeni kod; 6 haneyi
 * yapıştırma; 409 onayı aynı kodla forceLogin; bakım / pasif mesajları; LDAP girişi kapalı mesajı.
 */
const METHODS = { success: true, ldap: true, otp_push: true, otp_email: true, push_ttl: 45, email_ttl: 60, resend_cooldown: 30 }
const CHALLENGE = { success: true, status: 200, challenge_id: '11111111-2222-3333-4444-555555555555', channel: 'push', expires_in: 45, resend_in: 30 }

function codeInput() {
  return screen.getByRole('textbox', { name: /6-digit code|6 haneli kod/ })
}

async function openFlow(channel = /Get a code by push|Push ile kod al/) {
  render(<Login onLogin={onLogin} />)
  const btn = await screen.findByRole('button', { name: channel })
  fireEvent.click(btn)
  return btn
}

async function requestCode(name = 'alice') {
  const user = screen.getByRole('textbox', { name: /^Username$|^Kullanıcı adı$/ })
  fireEvent.change(user, { target: { value: name } })
  fireEvent.click(document.querySelector('[data-slot="otp-send"]'))
  await waitFor(() => expect(document.querySelector('[data-slot="otp-flow"]')).toHaveAttribute('data-step', 'code'))
}

let onLogin

describe('Login — kodla giriş', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    onLogin = vi.fn()
    api.getLoginMethods.mockResolvedValue(METHODS)
    api.loginOtp.request.mockResolvedValue(CHALLENGE)
  })
  afterEach(() => { vi.useRealTimers() })

  it('yöntemler kapalıyken düğme YOK; ana form aynen duruyor', async () => {
    api.getLoginMethods.mockResolvedValue({ ...METHODS, otp_push: false, otp_email: false })
    render(<Login onLogin={onLogin} />)
    await waitFor(() => expect(api.getLoginMethods).toHaveBeenCalled())
    expect(document.querySelector('[data-slot="login-otp-methods"]')).toBeNull()
    expect(document.getElementById('lp-pass')).not.toBeNull()
    expect(document.querySelector('form.lp-form')).not.toBeNull()
  })

  it('LDAP girişi kapalıyken "AD hesabınızla da girebilirsiniz" ipucu gizlenir; açıkken görünür', async () => {
    api.getLoginMethods.mockResolvedValue({ ...METHODS, ldap: false })
    const { unmount } = render(<Login onLogin={onLogin} />)
    await screen.findByRole('button', { name: /Get a code by push|Push ile kod al/ })
    expect(document.querySelector('.lp-ldap-hint')).toBeNull()
    unmount()
    api.getLoginMethods.mockResolvedValue(METHODS)
    render(<Login onLogin={onLogin} />)
    await screen.findByRole('button', { name: /Get a code by push|Push ile kod al/ })
    expect(document.querySelector('.lp-ldap-hint')).not.toBeNull()
  })

  it('yalnız açık yöntemlerin düğmesi çizilir (push kapalı → yalnız e-posta)', async () => {
    api.getLoginMethods.mockResolvedValue({ ...METHODS, otp_push: false })
    render(<Login onLogin={onLogin} />)
    await screen.findByRole('button', { name: /Get a code by email|E-posta ile kod al/ })
    expect(screen.queryByRole('button', { name: /Get a code by push|Push ile kod al/ })).toBeNull()
    // ana form yerinde
    expect(document.getElementById('lp-pass')).not.toBeNull()
  })

  it('istek → kod adımı: kullanıcı adı ana formdan dolar, genel bilgi metni, geri sayım; 6 hane girilince OTOMATİK doğrulama → onLogin', async () => {
    const ok = { success: true, username: 'ALICE', status: 200 }
    api.loginOtp.verify.mockResolvedValue(ok)
    render(<Login onLogin={onLogin} />)
    fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'alice' } })
    fireEvent.click(await screen.findByRole('button', { name: /Get a code by push|Push ile kod al/ }))
    expect(screen.getByRole('textbox', { name: /^Username$|^Kullanıcı adı$/ })).toHaveValue('alice')
    fireEvent.click(document.querySelector('[data-slot="otp-send"]'))
    await waitFor(() => expect(api.loginOtp.request).toHaveBeenCalledWith('alice', 'push'))
    await screen.findByText(/If your account is eligible, a 6-digit code has been sent by push|Hesabınız uygunsa push/)
    expect(document.querySelector('[data-slot="otp-countdown"]')).toHaveTextContent(/45 s|45 sn/)
    fireEvent.change(codeInput(), { target: { value: '123456' } })
    await waitFor(() => expect(api.loginOtp.verify).toHaveBeenCalledWith(CHALLENGE.challenge_id, '123456', false, false))
    await waitFor(() => expect(onLogin).toHaveBeenCalledWith(ok))
  })

  it('geri sayım (sahte saat): 45 → 30 → son 10 sn uyarı tonu → süre doldu; kutu kapanır, "Yeni kod iste" ile yeniden gönderilir', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    await openFlow()
    await requestCode()
    const cd = () => document.querySelector('[data-slot="otp-countdown"]')
    expect(cd()).toHaveAttribute('data-state', 'ok')
    await act(async () => { vi.advanceTimersByTime(15_500) })
    expect(cd()).toHaveTextContent(/30 s|30 sn|29 s|29 sn/)
    await act(async () => { vi.advanceTimersByTime(20_000) })
    expect(cd()).toHaveAttribute('data-state', 'warn')
    expect(document.querySelector('[data-slot="otp-countdown-announce"]')).toHaveTextContent(/10/)
    await act(async () => { vi.advanceTimersByTime(10_500) })
    expect(cd()).toHaveAttribute('data-state', 'expired')
    expect(codeInput()).toBeDisabled()
    expect(document.querySelector('[data-slot="otp-verify"]')).toBeNull()
    const resend = document.querySelector('[data-slot="otp-resend"]')
    expect(resend).not.toBeDisabled()
    expect(resend).toHaveTextContent(/Request a new code|Yeni kod iste/)
    fireEvent.click(resend)
    await waitFor(() => expect(api.loginOtp.request).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(cd()).toHaveAttribute('data-state', 'ok'))
  })

  it('yeniden gönder bekleme süresi: ilk 30 sn düğme pasif ve sayaçlı', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    await openFlow()
    await requestCode()
    const resend = () => document.querySelector('[data-slot="otp-resend"]')
    expect(resend()).toBeDisabled()
    expect(resend()).toHaveTextContent(/Resend \(30 s\)|Yeniden gönder \(30 sn\)/)
    await act(async () => { vi.advanceTimersByTime(31_000) })
    expect(resend()).not.toBeDisabled()
  })

  it('yanlış kod: "Kalan deneme: 2", kutular temizlenir; kilit (OTP_LOCKED) → kutu kapanır, yeni kod istenir', async () => {
    api.loginOtp.verify.mockResolvedValueOnce({ success: false, status: 401, code: 'OTP_INVALID', attempts_left: 2 })
    await openFlow()
    await requestCode()
    fireEvent.change(codeInput(), { target: { value: '000000' } })
    const err = await waitFor(() => {
      const e = document.querySelector('[data-slot="otp-error"]')
      expect(e).not.toBeNull()
      return e
    })
    expect(err).toHaveAttribute('data-kind', 'invalid')
    expect(err).toHaveTextContent(/Attempts left: 2|Kalan deneme: 2/)
    expect(codeInput()).toHaveValue('')
    api.loginOtp.verify.mockResolvedValueOnce({ success: false, status: 401, code: 'OTP_LOCKED', attempts_left: 0 })
    fireEvent.change(codeInput(), { target: { value: '111111' } })
    await waitFor(() => expect(document.querySelector('[data-slot="otp-error"]')).toHaveAttribute('data-kind', 'locked'))
    expect(codeInput()).toBeDisabled()
    expect(document.querySelector('[data-slot="otp-resend"]')).toHaveTextContent(/Request a new code|Yeni kod iste/)
    expect(onLogin).not.toHaveBeenCalled()
  })

  it('6 haneyi YAPIŞTIRMA: süsler ("Kod: 123 456") atılır, tek seferde doğrulanır', async () => {
    api.loginOtp.verify.mockResolvedValue({ success: true, status: 200, username: 'ALICE' })
    await openFlow()
    await requestCode()
    fireEvent.paste(codeInput(), { clipboardData: { getData: () => 'Kod: 123 456' } })
    await waitFor(() => expect(api.loginOtp.verify).toHaveBeenCalledWith(CHALLENGE.challenge_id, '123456', false, false))
    expect([...document.querySelectorAll('[data-slot="otp-box"]')].map((b) => b.textContent).join('')).toBe('123456')
  })

  it('409 başka yerde oturum → onay ekranı; onaylanınca AYNI kod forceLogin=true ile gönderilir', async () => {
    api.loginOtp.verify
      .mockResolvedValueOnce({ success: false, status: 409, error_code: 'ACTIVE_SESSION_EXISTS' })
      .mockResolvedValueOnce({ success: true, status: 200, username: 'ALICE' })
    await openFlow()
    await requestCode()
    fireEvent.change(codeInput(), { target: { value: '654321' } })
    await waitFor(() => expect(document.querySelector('[data-slot="otp-confirm"]')).not.toBeNull())
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    fireEvent.click(document.querySelector('[data-slot="otp-confirm-ok"]'))
    await waitFor(() => expect(api.loginOtp.verify).toHaveBeenLastCalledWith(CHALLENGE.challenge_id, '654321', false, true))
    await waitFor(() => expect(onLogin).toHaveBeenCalled())
  })

  it('doğru koddan sonra bakım (403 MAINTENANCE) ve pasif hesap (403 ACCOUNT_INACTIVE) mesajları', async () => {
    api.loginOtp.verify.mockResolvedValueOnce({ success: false, status: 403, code: 'MAINTENANCE', error_code: 'MAINTENANCE',
      maintenance: { state: 'active', start_at: '2026-10-02T19:00:00Z', end_at: '2026-10-02T20:00:00Z' } })
    await openFlow()
    await requestCode()
    fireEvent.change(codeInput(), { target: { value: '123456' } })
    await waitFor(() => expect(document.querySelector('[data-slot="otp-error"]')).toHaveAttribute('data-kind', 'maintenance'))
    await waitFor(() => expect(document.querySelector('[data-slot="login-maintenance"]')).not.toBeNull())
    api.loginOtp.verify.mockResolvedValueOnce({ success: false, status: 403, code: 'ACCOUNT_INACTIVE', error_code: 'ACCOUNT_INACTIVE' })
    fireEvent.change(codeInput(), { target: { value: '123457' } })
    await waitFor(() => expect(document.querySelector('[data-slot="otp-error"]')).toHaveAttribute('data-kind', 'inactive'))
    expect(onLogin).not.toHaveBeenCalled()
  })

  it('IP sınırı (429) istek adımında açık mesaj; boş ad alanın altında hata, istek gitmez', async () => {
    await openFlow()
    const user = screen.getByRole('textbox', { name: /^Username$|^Kullanıcı adı$/ })
    fireEvent.change(user, { target: { value: '   ' } })
    fireEvent.click(document.querySelector('[data-slot="otp-send"]'))
    await waitFor(() => expect(user).toHaveAttribute('aria-invalid', 'true'))
    // hata alanın ALTINDA (tost değil), kontrol aria-describedby ile bağlı
    const fieldErr = document.getElementById(user.getAttribute('aria-describedby'))
    expect(fieldErr).toHaveTextContent(/Enter your username|Kullanıcı adınızı girin/)
    expect(api.loginOtp.request).not.toHaveBeenCalled()
    api.loginOtp.request.mockResolvedValueOnce({ success: false, status: 429, code: 'OTP_RATE_LIMITED' })
    fireEvent.change(user, { target: { value: 'alice' } })
    fireEvent.click(document.querySelector('[data-slot="otp-send"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="otp-error"]')).toHaveAttribute('data-kind', 'rateLimited'))
  })

  it('"Şifreyle giriş yap" ana forma döner (form ve parola alanı yerinde)', async () => {
    await openFlow()
    fireEvent.click(document.querySelector('[data-slot="otp-back"]'))
    expect(document.querySelector('[data-slot="otp-flow"]')).toBeNull()
    expect(document.getElementById('lp-pass')).not.toBeNull()
  })

  it('LDAP girişi kapalı (401 LDAP_LOGIN_DISABLED) → yerelleştirilmiş mesaj; kod düğmeleri altta', async () => {
    api.login.mockResolvedValueOnce({ success: false, error_code: 'LDAP_LOGIN_DISABLED', error: 'srv' })
    render(<Login onLogin={onLogin} />)
    fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'n12345' } })
    fireEvent.change(document.getElementById('lp-pass'), { target: { value: 'x' } })
    fireEvent.submit(document.querySelector('form'))
    const alert = await waitFor(() => {
      const a = document.querySelector('[data-code="LDAP_LOGIN_DISABLED"]')
      expect(a).not.toBeNull()
      return a
    })
    expect(alert).toHaveTextContent(/LDAP sign-in is currently disabled|LDAP ile giriş şu an kapalı/)
    expect(document.querySelector('[data-slot="login-otp-methods"]')).not.toBeNull()
  })
})

/**
 * Kişi bilgisi doğrulaması (2026-10-03, kullanıcı isteği): ayar açıksa push isteğinde "Kayıtlı cep telefonu", e-posta
 * isteğinde "Kayıtlı e-posta adresi" de sorulur. Alan kanal başına, bayrak kapalıyken yok; doğrulama alanın YANINDA;
 * telefon yazarken hafif biçimlenir, başka biçimin yapıştırılması engellenmez; değer isteğe eklenir; eşleşip eşleşmediğini
 * ekran asla söylemez (sunucu her durumda aynı 200'ü döner → aynı "hesabınız uygunsa" metni).
 */
describe('Login — kodla giriş: kişi bilgisi', () => {
  const FLAGS = { ...METHODS, push_requires_phone: true, email_requires_email: true }
  const flow = () => document.querySelector('[data-slot="otp-flow"]')
  const contact = (kind) => document.querySelector(`[data-slot="otp-contact"][data-kind="${kind}"]`)
  const phoneInput = () => screen.getByRole('textbox', { name: /^Registered mobile number$|^Kayıtlı cep telefonu$/ })
  const emailInput = () => screen.getByRole('textbox', { name: /^Registered email address$|^Kayıtlı e-posta adresi$/ })
  const userInput = () => screen.getByRole('textbox', { name: /^Username$|^Kullanıcı adı$/ })
  const send = () => fireEvent.click(document.querySelector('[data-slot="otp-send"]'))

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    onLogin = vi.fn()
    api.getLoginMethods.mockResolvedValue(FLAGS)
    api.loginOtp.request.mockResolvedValue(CHALLENGE)
  })
  afterEach(() => { vi.useRealTimers() })

  it('push: kullanıcı adının altında "Kayıtlı cep telefonu" (tel, autocomplete tel) + yardım metni; e-posta alanı YOK', async () => {
    await openFlow()
    const input = phoneInput()
    expect(input).toHaveAttribute('type', 'tel')
    expect(input).toHaveAttribute('inputmode', 'tel')
    expect(input).toHaveAttribute('autocomplete', 'tel')
    expect(input).toHaveAttribute('placeholder', '05xx xxx xx xx')
    expect(contact('phone')).not.toBeNull()
    expect(contact('email')).toBeNull()
    expect(flow()).toHaveTextContent(/registered in the system; if it matches|Sistemde kayıtlı numaranızı girin; eşleşirse kod gönderilir/)
    expect(document.querySelector('[data-slot="otp-desc"]')).toHaveTextContent(/registered mobile number|kayıtlı cep telefonu/)
    // DOM sırası: kullanıcı adı → telefon
    expect(userInput().compareDocumentPosition(input) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('kanal değişince kullanıcı adı ve yazılan değerler korunur, doğru alan görünür', async () => {
    await openFlow()
    fireEvent.change(userInput(), { target: { value: 'alice' } })
    fireEvent.change(phoneInput(), { target: { value: '05000000000' } })
    fireEvent.click(within(flow()).getByRole('button', { name: /^Email$|^E-posta$/ }))
    expect(contact('phone')).toBeNull()
    expect(emailInput()).toHaveAttribute('type', 'email')
    expect(emailInput()).toHaveAttribute('autocomplete', 'email')
    expect(userInput()).toHaveValue('alice')
    fireEvent.change(emailInput(), { target: { value: 'alice@example.com' } })
    fireEvent.click(within(flow()).getByRole('button', { name: /^Push notification$|^Push bildirimi$/ }))
    expect(phoneInput()).toHaveValue('0500 000 00 00')
    fireEvent.click(within(flow()).getByRole('button', { name: /^Email$|^E-posta$/ }))
    expect(emailInput()).toHaveValue('alice@example.com')
  })

  it('bayrak kapalı → alan yok, istek eskisi gibi yalnız (ad, kanal)', async () => {
    api.getLoginMethods.mockResolvedValue({ ...METHODS, push_requires_phone: false, email_requires_email: false })
    await openFlow()
    expect(document.querySelector('[data-slot="otp-contact"]')).toBeNull()
    fireEvent.change(userInput(), { target: { value: 'alice' } })
    send()
    await waitFor(() => expect(api.loginOtp.request).toHaveBeenCalledWith('alice', 'push'))
    expect(api.loginOtp.request.mock.calls[0]).toHaveLength(2)
  })

  it('doğrulama ALANIN YANINDA: boş → "girin", kısa → "geçerli numara"; istek gitmez; düzeltince hata kalkar', async () => {
    await openFlow()
    fireEvent.change(userInput(), { target: { value: 'alice' } })
    send()
    await waitFor(() => expect(phoneInput()).toHaveAttribute('aria-invalid', 'true'))
    const describedBy = phoneInput().getAttribute('aria-describedby').split(' ')
    expect(describedBy.map((id) => document.getElementById(id)?.textContent).join(' '))
      .toMatch(/Enter your registered mobile number|Kayıtlı cep telefonu numaranızı girin/)
    expect(document.querySelector('[data-field="phone"]')).toHaveAttribute('data-invalid', 'true')
    expect(document.querySelector('[data-slot="otp-error"]')).toBeNull()   // tost / genel hata değil
    fireEvent.change(phoneInput(), { target: { value: '0500 000' } })
    expect(phoneInput()).not.toHaveAttribute('aria-invalid')                // düzenleyince kalkar
    send()
    await waitFor(() => expect(document.querySelector('[data-field="phone"]')).toHaveTextContent(/valid mobile number|Geçerli bir cep telefonu/))
    expect(api.loginOtp.request).not.toHaveBeenCalled()
  })

  it('hata odak: ilk hatalı alana odaklanır (ad boş → ad; ad dolu, telefon boş → telefon)', async () => {
    await openFlow()
    send()
    await waitFor(() => expect(userInput()).toHaveAttribute('aria-invalid', 'true'))
    await waitFor(() => expect(document.activeElement).toBe(userInput()))
    fireEvent.change(userInput(), { target: { value: 'alice' } })
    send()
    await waitFor(() => expect(document.activeElement).toBe(phoneInput()))
  })

  it('telefon hafif biçim: düz rakam gruplanır; "+90 (500) …" yapıştırma AYNEN kalır; istek gövdesi { phone } taşır', async () => {
    await openFlow()
    fireEvent.change(userInput(), { target: { value: 'alice' } })
    fireEvent.change(phoneInput(), { target: { value: '05000000000' } })
    expect(phoneInput()).toHaveValue('0500 000 00 00')
    fireEvent.change(phoneInput(), { target: { value: '+90 (500) 000-00-00' } })
    expect(phoneInput()).toHaveValue('+90 (500) 000-00-00')
    // Enter (form gönderimi) isteği atar
    fireEvent.submit(phoneInput().closest('form'))
    await waitFor(() => expect(api.loginOtp.request).toHaveBeenCalledWith('alice', 'push', { phone: '+90 (500) 000-00-00' }))
    await waitFor(() => expect(flow()).toHaveAttribute('data-step', 'code'))
    // "Bilgileri değiştir" istek adımına döner; ad ve telefon korunur
    const change = document.querySelector('[data-slot="otp-change-user"]')
    expect(change).toHaveTextContent(/Change details|Bilgileri değiştir/)
    fireEvent.click(change)
    expect(flow()).toHaveAttribute('data-step', 'request')
    expect(userInput()).toHaveValue('alice')
    expect(phoneInput()).toHaveValue('+90 (500) 000-00-00')
  })

  it('e-posta: biçim doğrulaması alan yanında; geçerli adres { email } olarak gider', async () => {
    await openFlow(/Get a code by email|E-posta ile kod al/)
    fireEvent.change(userInput(), { target: { value: 'alice' } })
    fireEvent.change(emailInput(), { target: { value: 'alice@' } })
    send()
    await waitFor(() => expect(document.querySelector('[data-field="email"]')).toHaveTextContent(/valid email address|Geçerli bir e-posta/))
    expect(api.loginOtp.request).not.toHaveBeenCalled()
    fireEvent.change(emailInput(), { target: { value: ' Alice@Example.com ' } })
    send()
    await waitFor(() => expect(api.loginOtp.request).toHaveBeenCalledWith('alice', 'email', { email: 'Alice@Example.com' }))
  })

  it('genel mesaj DEĞİŞMEZ: sunucu (eşleşse de eşleşmese de) aynı 200 → aynı "hesabınız uygunsa" metni; numara ima edilmez', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    await openFlow()
    fireEvent.change(userInput(), { target: { value: 'alice' } })
    fireEvent.change(phoneInput(), { target: { value: '0500 000 00 01' } })
    send()
    const info = await waitFor(() => {
      const el = document.querySelector('[data-slot="otp-sent-info"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(info).toHaveTextContent(/If your account is eligible, a 6-digit code has been sent by push|Hesabınız uygunsa push bildirimiyle 6 haneli bir kod gönderildi/)
    expect(flow()).not.toHaveTextContent(/0500 000 00 01/)
    expect(flow()).not.toHaveTextContent(/match|eşleş/i)
    // "Yeniden gönder" aynı telefonla tekrar ister
    await act(async () => { vi.advanceTimersByTime(31_000) })
    expect(document.querySelector('[data-slot="otp-resend"]')).not.toBeDisabled()
    fireEvent.click(document.querySelector('[data-slot="otp-resend"]'))
    await waitFor(() => expect(api.loginOtp.request).toHaveBeenCalledTimes(2))
    expect(api.loginOtp.request).toHaveBeenLastCalledWith('alice', 'push', { phone: '0500 000 00 01' })
  })

  it('sunucu 400 PHONE_REQUIRED (ayar sayfa açıkken açıldı) → alan yine de çizilir, hata alanın altında', async () => {
    api.getLoginMethods.mockResolvedValue({ ...METHODS })   // bayraklar yok: alan çizilmedi
    api.loginOtp.request.mockResolvedValueOnce({ success: false, status: 400, code: 'PHONE_REQUIRED', error_code: 'PHONE_REQUIRED', field: 'phone' })
    await openFlow()
    expect(document.querySelector('[data-slot="otp-contact"]')).toBeNull()
    fireEvent.change(userInput(), { target: { value: 'alice' } })
    send()
    await waitFor(() => expect(contact('phone')).not.toBeNull())
    await waitFor(() => expect(phoneInput()).toHaveAttribute('aria-invalid', 'true'))
    expect(document.querySelector('[data-field="phone"]')).toHaveTextContent(/Enter your registered mobile number|Kayıtlı cep telefonu numaranızı girin/)
  })

  it('kullanıcı adı ana formdan dolu gelirse odak doğrudan telefon alanında', async () => {
    render(<Login onLogin={onLogin} />)
    fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'alice' } })
    fireEvent.click(await screen.findByRole('button', { name: /Get a code by push|Push ile kod al/ }))
    await waitFor(() => expect(document.activeElement).toBe(phoneInput()))
  })
})

describe('OtpCodeInput', () => {
  it('tek gerçek giriş: one-time-code + rakam klavyesi; ok tuşlarıyla imleç → etkin kutu; rakam dışı atılır', async () => {
    const { default: OtpCodeInput } = await import('../components/login/OtpCodeInput.jsx')
    const onChange = vi.fn()
    render(<OtpCodeInput value="123" onChange={onChange} label="6-digit code" />)
    const input = screen.getByRole('textbox', { name: '6-digit code' })
    expect(input).toHaveAttribute('autocomplete', 'one-time-code')
    expect(input).toHaveAttribute('inputmode', 'numeric')
    fireEvent.focus(input)
    input.setSelectionRange(1, 1)
    fireEvent.keyUp(input, { key: 'ArrowLeft' })
    const boxes = [...document.querySelectorAll('[data-slot="otp-box"]')]
    expect(boxes).toHaveLength(6)
    expect(boxes[1]).toHaveAttribute('data-active', 'true')
    expect(boxes.filter((b) => b.getAttribute('data-filled') === 'true')).toHaveLength(3)
    fireEvent.change(input, { target: { value: '12a3x4' } })
    expect(onChange).toHaveBeenLastCalledWith('1234')
  })
})

describe('kod akışı yardımcıları', () => {
  it('sanitizeOtp: yalnız rakam, en çok 6', () => {
    expect(sanitizeOtp('Kod: 123-456 7')).toBe('123456')
    expect(sanitizeOtp(null)).toBe('')
    expect(sanitizeOtp('00a1')).toBe('001')
  })
  it('geri sayım: kalan saniye yukarı yuvarlanır; duyuru yalnız 30 / 10 / doldu eşiklerinde', () => {
    expect(secondsLeft(10_500, 10_000)).toBe(1)
    expect(secondsLeft(10_000, 10_000)).toBe(0)
    expect(announceMilestone(45, 45)).toBeNull()
    expect(announceMilestone(30, 45)).toBe(30)
    expect(announceMilestone(12, 45)).toBe(30)
    expect(announceMilestone(10, 45)).toBe(10)
    expect(announceMilestone(0, 45)).toBe(0)
  })
  it('hata eşlemesi ve kanal listesi', () => {
    expect(otpErrorOf({ code: 'OTP_INVALID', attempts_left: 1 })).toEqual({ kind: 'invalid', attemptsLeft: 1 })
    expect(otpErrorOf({ status: 429 })).toEqual({ kind: 'rateLimited' })
    expect(otpErrorOf({ status: 423 })).toEqual({ kind: 'accountLocked' })
    expect(otpErrorOf({ networkError: true })).toEqual({ kind: 'network' })
    expect(availableChannels({ otp_push: false, otp_email: true })).toEqual(['email'])
    expect(availableChannels(null)).toEqual([])
  })
})
