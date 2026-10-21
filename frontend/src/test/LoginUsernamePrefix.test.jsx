import { describe, it, expect, vi, beforeEach } from 'vitest'
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
 * Giriş sayfası + kullanıcı adı ön eki (2026-10-21): kural uygulanırken public uç izinli ön ekleri taşır → kullanıcı adı
 * alanının altında "N ile başlayan kullanıcı adınızla giriş yapın" ipucu (kodla giriş formunda da); 403
 * USERNAME_PREFIX_NOT_ALLOWED → hata alanında izinli ön ekleri adlandıran metin; `?session=prefix` → bildirim.
 */
const METHODS = { success: true, ldap: true, otp_push: true, otp_email: false, push_ttl: 45, email_ttl: 60, resend_cooldown: 30 }
const DENIED = {
  success: false, status: 403, code: 'USERNAME_PREFIX_NOT_ALLOWED', error_code: 'USERNAME_PREFIX_NOT_ALLOWED',
  error: 'server text', allowed_prefixes: ['N'],
}

function fill(user, pass = 'pw') {
  fireEvent.change(screen.getByLabelText(/username|kullanıcı adı/i), { target: { value: user } })
  fireEvent.change(document.getElementById('lp-pass'), { target: { value: pass } })
}

describe('Login — kullanıcı adı ön eki', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    api.getLoginMethods.mockResolvedValue({ ...METHODS, username_prefix: { allowed: ['N'] } })
  })

  it('kural uygulanırken kullanıcı adı alanının altında ipucu; alan ipucuna bağlı (aria-describedby)', async () => {
    render(<Login onLogin={vi.fn()} />)
    const hint = await waitFor(() => {
      const h = document.querySelector('[data-slot="login-prefix-hint"]')
      expect(h).not.toBeNull()
      return h
    })
    expect(hint).toHaveTextContent(/starts with N|N ile başlayan/)
    expect(document.getElementById('lp-user')).toHaveAttribute('aria-describedby', 'lp-user-hint')
  })

  it('kural yokken ipucu YOK (public yanıt bugünkü gibi)', async () => {
    api.getLoginMethods.mockResolvedValue(METHODS)
    render(<Login onLogin={vi.fn()} />)
    await screen.findByRole('button', { name: /Get a code by push|Push ile kod al/ })
    expect(document.querySelector('[data-slot="login-prefix-hint"]')).toBeNull()
  })

  it('403 USERNAME_PREFIX_NOT_ALLOWED → hata alanında izinli ön ekleri adlandıran metin (data-code)', async () => {
    api.login.mockResolvedValue(DENIED)
    const onLogin = vi.fn()
    render(<Login onLogin={onLogin} />)
    fill('MP123')
    fireEvent.submit(document.querySelector('form'))
    const alert = await waitFor(() => {
      const a = document.querySelector('[data-code="USERNAME_PREFIX_NOT_ALLOWED"]')
      expect(a).not.toBeNull()
      return a
    })
    expect(alert).toHaveTextContent(/start with N|N ile başlayan/)
    expect(onLogin).not.toHaveBeenCalled()
  })

  it('?session=prefix (usernamePrefixDenied) → bildirim, ön ekler bilinince adlandırır', async () => {
    render(<Login onLogin={vi.fn()} usernamePrefixDenied />)
    await waitFor(() => expect(document.querySelector('[data-slot="login-username-prefix"]')).toHaveTextContent(/starts with N|N ile başlayan/))
  })

  it('kodla giriş: ipucu kullanıcı adı alanında; doğru koddan sonra 403 ön ek → anlaşılır hata türü', async () => {
    render(<Login onLogin={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: /Get a code by push|Push ile kod al/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="otp-flow"]')).toHaveTextContent(/starts with N|N ile başlayan/))
    expect(otpErrorOf(DENIED)).toEqual({ kind: 'usernamePrefix', prefixes: ['N'] })
  })
})
