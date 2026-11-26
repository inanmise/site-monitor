import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { restoreRealTimers } from './helpers/fakeTimers.js'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import Login from '../pages/Login.jsx'
import { otpErrorOf } from '../components/login/OtpLoginFlow.jsx'

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
 * Kodla girişte AD reddi (H paketi 2026-11-26, yönetim B4): LDAP hesabı AD'de devre dışı / bulunamadı → 403
 * `AD_ACCOUNT_DISABLED`; AD'ye ulaşılamadı ve hesap son 24 saatte doğrulanmamış → `AD_UNVERIFIED`. Ekran sunucunun
 * metnini (arayüz dilinde, Msg.t) gösterir; metin yoksa kendi açıklamasına düşer (boş hata yok). Yeni kod da reddedilir:
 * kod kutusu kapanır, "Yeniden gönder" yok, "Şifreyle giriş yap" birincil eylem.
 */
const METHODS = { success: true, ldap: true, otp_push: true, otp_email: false, push_ttl: 45, resend_cooldown: 30 }
const CHALLENGE = { success: true, status: 200, challenge_id: '11111111-2222-3333-4444-555555555555', channel: 'push', expires_in: 45, resend_in: 30 }

async function toCodeStep() {
  render(<Login onLogin={() => {}} />)
  fireEvent.click(await screen.findByRole('button', { name: /Get a code by push|Push ile kod al/ }))
  fireEvent.change(screen.getByRole('textbox', { name: /^Username$|^Kullanıcı adı$/ }), { target: { value: 'ldapuser' } })
  fireEvent.click(document.querySelector('[data-slot="otp-send"]'))
  await waitFor(() => expect(document.querySelector('[data-slot="otp-flow"]')).toHaveAttribute('data-step', 'code'))
}
function typeCode(v = '123456') {
  fireEvent.change(screen.getByRole('textbox', { name: /6-digit code|6 haneli kod/ }), { target: { value: v } })
}

describe('OTP — AD reddi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    api.getLoginMethods.mockResolvedValue(METHODS)
    api.loginOtp.request.mockResolvedValue(CHALLENGE)
  })
  afterEach(() => { restoreRealTimers() })

  it('otpErrorOf: iki AD kodu ayrı tür + sunucu metni; metin yoksa null', () => {
    expect(otpErrorOf({ status: 403, code: 'AD_ACCOUNT_DISABLED', error: '  AD kapalı  ' })).toEqual({ kind: 'adDisabled', message: 'AD kapalı' })
    expect(otpErrorOf({ status: 401, error_code: 'AD_UNVERIFIED', message: 'Parolayla girin' })).toEqual({ kind: 'adUnverified', message: 'Parolayla girin' })
    expect(otpErrorOf({ status: 403, code: 'AD_ACCOUNT_DISABLED' })).toEqual({ kind: 'adDisabled', message: null })
  })

  it('AD_ACCOUNT_DISABLED: sunucu metni gösterilir, kod kutusu kapalı, yeniden gönder YOK, şifreyle giriş birincil', async () => {
    api.loginOtp.verify.mockResolvedValue({ success: false, status: 403, code: 'AD_ACCOUNT_DISABLED',
      error: 'Hesabınız Active Directory\'de devre dışı bırakılmış. Yöneticinize başvurun.' })
    await toCodeStep()
    typeCode()
    const err = await waitFor(() => {
      const el = document.querySelector('[data-slot="otp-error"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(err).toHaveAttribute('data-kind', 'adDisabled')
    expect(err.textContent).toContain('devre dışı bırakılmış')
    expect(screen.getByRole('textbox', { name: /6-digit code|6 haneli kod/ })).toBeDisabled()
    expect(document.querySelector('[data-slot="otp-resend"]')).toBeNull()
    expect(document.querySelector('[data-slot="otp-verify"]')).toBeNull()
    const back = document.querySelectorAll('[data-slot="otp-back"]')
    expect(back).toHaveLength(1)   // tek "şifreyle giriş" (alt bağlantı tekrar edilmez)
    fireEvent.click(back[0])
    await waitFor(() => expect(document.querySelector('[data-slot="otp-flow"]')).toBeNull())
    expect(document.getElementById('lp-pass')).not.toBeNull()
  })

  it('AD_UNVERIFIED metinsiz: kendi açıklaması (boş hata yok), parolayla giriş önerilir', async () => {
    api.loginOtp.verify.mockResolvedValue({ success: false, status: 401, code: 'AD_UNVERIFIED' })
    await toCodeStep()
    typeCode()
    await waitFor(() => expect(document.querySelector('[data-slot="otp-error"]')).toHaveAttribute('data-kind', 'adUnverified'))
    expect(document.querySelector('[data-slot="otp-error"]').textContent).toMatch(/Şifrenizle giriş yapın|Sign in with your password/)
    expect(document.querySelector('[data-slot="otp-resend"]')).toBeNull()
  })
})
