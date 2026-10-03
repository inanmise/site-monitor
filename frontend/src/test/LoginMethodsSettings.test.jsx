import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import LoginMethodsSettings, { validateLoginMethods } from '../components/admin/LoginMethodsSettings.jsx'
import { previewChannels } from '../components/admin/loginmethods/LoginMethodsPreview.jsx'
import { auditLink } from '../components/admin/loginmethods/LoginMethodsActivity.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ loginMethodsAdmin: { get: vi.fn(), save: vi.fn() } }),
  getRecentFailures: () => [],
}))

import { api } from '../api/client'

/**
 * Ayarlar → Giriş Yöntemleri (2026-10-02): yöntem kartları (şifre her zaman açık, LDAP anahtarı + kapatma onayı, push
 * ağ geçidi yoksa kullanılamaz, e-posta + SMTP durumu), süre / sınır doğrulaması ALAN YANINDA, canlı TR/EN önizleme,
 * son etkinlik listesi + Denetim Logu bağlantısı, kayıt gövdesi.
 */
const SETTINGS = {
  ldap_enabled: true, push_enabled: false, email_enabled: true, push_ttl_seconds: 45, email_ttl_seconds: 60,
  max_attempts: 3, resend_cooldown_seconds: 30, max_requests_per_user: 5, max_requests_per_ip: 20,
  max_failed_verifications: 5, allow_global_admins: false,
}
const LIMITS = { ttl: [30, 300], max_attempts: [1, 10], resend_cooldown: [10, 300], max_requests_per_user: [1, 20],
  max_requests_per_ip: [1, 500], max_failed_verifications: [1, 20], window_minutes: 15 }
const TYPES = ['LOGIN_OTP_REQUESTED', 'LOGIN_OTP_DELIVERY_FAILED', 'LOGIN_OTP_VERIFY_FAILED', 'LOGIN_OTP_EXPIRED', 'LOGIN_OTP_LOCKED']

function payload(over = {}) {
  return {
    success: true,
    data: {
      settings: { ...SETTINGS, ...(over.settings || {}) },
      limits: LIMITS,
      status: { push_gateway_configured: false, smtp: { host: 'smtp.example.com', configured: true, alarm_mail_enabled: false },
        ldap_integration_enabled: true, secret_key_ephemeral: false, ...(over.status || {}) },
      public: { ldap: true, otp_push: false, otp_email: true },
      activity: over.activity ?? [
        { id: 1, event_type: 'LOGIN_OTP_REQUESTED', event_time: '2026-10-02T10:00:00', actor: 'ALICE', outcome: 'SUCCESS',
          ip_address: '10.0.0.5', detail: '{"channel":"EMAIL","result":"SENT"}' },
        { id: 2, event_type: 'LOGIN_OTP_VERIFY_FAILED', event_time: '2026-10-02T10:01:00', actor: 'ALICE', outcome: 'FAILURE',
          ip_address: '10.0.0.5', failure_reason: 'OTP_INVALID: yanlış kod', detail: '{"channel":"EMAIL","attempts_left":2}' },
      ],
      activity_types: TYPES,
    },
  }
}

const card = (m) => document.querySelector(`[data-slot="lm-method"][data-method="${m}"]`)

describe('LoginMethodsSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.loginMethodsAdmin.get.mockResolvedValue(payload())
    api.loginMethodsAdmin.save.mockImplementation(async (s) => payload({ settings: s }))
  })

  it('yöntem kartları: şifre her zaman açık; LDAP açık; push ağ geçidi yok → kullanılamaz + anahtar pasif; e-posta açık + SMTP durumu', async () => {
    render(<LoginMethodsSettings onOpenSection={() => {}} />)
    await waitFor(() => expect(card('password')).not.toBeNull())
    expect(card('password')).toHaveAttribute('data-status', 'always')
    expect(card('ldap')).toHaveAttribute('data-status', 'on')
    expect(card('push')).toHaveAttribute('data-status', 'unavailable')
    expect(within(card('push')).getByRole('switch')).toBeDisabled()
    expect(document.querySelector('[data-slot="lm-push-no-gateway"]')).not.toBeNull()
    expect(card('email')).toHaveAttribute('data-status', 'on')
    expect(document.querySelector('[data-slot="lm-smtp"]')).toHaveTextContent('smtp.example.com')
  })

  it('süre doğrulaması ALAN YANINDA: 20 sn → "30 ile 300 arasında"; kayıt gitmez', async () => {
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(card('email')).not.toBeNull())
    const ttl = within(card('email')).getByRole('spinbutton')
    fireEvent.change(ttl, { target: { value: '20' } })
    fireEvent.click(screen.getByRole('button', { name: /^Save$|^Kaydet$/ }))
    await waitFor(() => expect(ttl).toHaveAttribute('aria-invalid', 'true'))
    expect(document.querySelector('[data-field="email_ttl_seconds"]')).toHaveTextContent(/between 30 and 300|30–300 arasında/)
    expect(api.loginMethodsAdmin.save).not.toHaveBeenCalled()
  })

  it('kayıt: değerler sayı/boolean olarak gider; sunucu alan reddi alanın altında gösterilir', async () => {
    api.loginMethodsAdmin.save.mockResolvedValueOnce({ success: false, status: 400, field: 'max_requests_per_ip', error: 'Değer 1–500 aralığında olmalı' })
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(card('email')).not.toBeNull())
    fireEvent.click(within(card('email')).getByRole('switch'))
    fireEvent.click(screen.getByRole('button', { name: /^Save$|^Kaydet$/ }))
    await waitFor(() => expect(api.loginMethodsAdmin.save).toHaveBeenCalled())
    const sent = api.loginMethodsAdmin.save.mock.calls[0][0]
    expect(sent).toMatchObject({ email_enabled: false, ldap_enabled: true, email_ttl_seconds: 60, max_attempts: 3 })
    expect(typeof sent.max_requests_per_ip).toBe('number')
    await waitFor(() => expect(document.querySelector('[data-field="max_requests_per_ip"]')).toHaveTextContent(/1–500/))
  })

  it('LDAP kapatma ONAY penceresi ister; açık kod yöntemi yoksa uyarı da içerir; vazgeçilirse kaydedilmez', async () => {
    api.loginMethodsAdmin.get.mockResolvedValue(payload({ settings: { email_enabled: false } }))
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(card('ldap')).not.toBeNull())
    fireEvent.click(within(card('ldap')).getByRole('switch'))
    // LDAP kapalı + kod yöntemi yok → sayfa üstünde kalıcı uyarı
    expect(document.querySelector('[data-slot="lm-no-code-warning"]')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /^Save$|^Kaydet$/ }))
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveTextContent(/only be able to sign in with a code|yalnız kodla girebilir/)
    expect(dlg).toHaveTextContent(/No code method is enabled|açık bir kod yöntemi YOK/)
    fireEvent.click(within(dlg).getByRole('button', { name: /Cancel|Vazgeç|İptal/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.loginMethodsAdmin.save).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: /^Save$|^Kaydet$/ }))
    const dlg2 = await screen.findByRole('dialog')
    fireEvent.click(within(dlg2).getByRole('button', { name: /Switch off LDAP sign-in|LDAP girişini kapat/ }))
    await waitFor(() => expect(api.loginMethodsAdmin.save).toHaveBeenCalledWith(expect.objectContaining({ ldap_enabled: false })))
  })

  it('canlı önizleme: kaydedilmemiş değerleri yansıtır (e-posta kapatılınca düğme kalkar) ve TR/EN geçişi', async () => {
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(document.querySelector('[data-slot="lm-preview"]')).not.toBeNull())
    const stage = () => document.querySelector('[data-slot="lm-preview-stage"]')
    expect(stage().querySelector('[data-slot="login-otp-methods"] [data-channel="email"]')).not.toBeNull()
    expect(stage().querySelector('[data-channel="push"]')).toBeNull()   // ağ geçidi yok
    expect(stage().querySelector('[data-slot="otp-countdown"]')).toHaveTextContent(/60/)
    fireEvent.click(within(card('email')).getByRole('switch'))
    await waitFor(() => expect(stage().querySelector('[data-slot="lm-preview-none"]')).not.toBeNull())
    const preview = document.querySelector('[data-slot="lm-preview"]')
    fireEvent.click(within(preview).getByRole('button', { name: 'TR' }))
    await waitFor(() => expect(preview).toHaveAttribute('data-lang', 'tr'))
    expect(stage()).toHaveTextContent(/Kodla giriş kapalı/)
  })

  it('son etkinlik: olay etiketleri + sonuç + kullanıcı/IP + iç neden; Denetim Logu bağlantısı süzgeçli', async () => {
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="lm-activity-row"]')).toHaveLength(2))
    const rows = [...document.querySelectorAll('[data-slot="lm-activity-row"]')]
    expect(rows[0]).toHaveTextContent(/Sign-in code requested|Giriş kodu istendi/)
    expect(rows[0]).toHaveTextContent('10.0.0.5')
    expect(rows[1]).toHaveTextContent(/Incorrect sign-in code entered|Giriş kodu hatalı girildi/)
    expect(rows[1]).toHaveTextContent('OTP_INVALID')
    const link = document.querySelector('[data-slot="lm-activity-audit-link"]')
    expect(link.getAttribute('href')).toContain('tab=system')
    expect(decodeURIComponent(link.getAttribute('href'))).toContain('a_eventType=LOGIN_OTP_REQUESTED,LOGIN_OTP_DELIVERY_FAILED')
  })

  it('boş etkinlik ve gizli anahtar uyarısı', async () => {
    api.loginMethodsAdmin.get.mockResolvedValue(payload({ activity: [], status: { secret_key_ephemeral: true } }))
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(document.querySelector('[data-slot="lm-activity-empty"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="lm-secret-warning"]')).not.toBeNull()
  })

  it('yardımcılar: doğrulama ve önizleme kanalları', () => {
    const msg = (a, b) => `${a}-${b}`
    expect(validateLoginMethods(SETTINGS, LIMITS, msg)).toEqual({})
    expect(validateLoginMethods({ ...SETTINGS, max_attempts: '11', push_ttl_seconds: '4.5' }, LIMITS, msg))
      .toEqual({ max_attempts: '1-10', push_ttl_seconds: '30-300' })
    expect(previewChannels({ push_enabled: true, email_enabled: true }, { push_gateway_configured: false })).toEqual(['email'])
    expect(previewChannels({ push_enabled: true, email_enabled: false }, { push_gateway_configured: true })).toEqual(['push'])
    expect(auditLink(['A', 'B'])).toBe('/?tab=system&a_eventType=A%2CB&a_range=7d')
  })
})
