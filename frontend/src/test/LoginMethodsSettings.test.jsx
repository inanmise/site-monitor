import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import LoginMethodsSettings, { validateLoginMethods } from '../components/admin/LoginMethodsSettings.jsx'
import { previewChannels, previewMethods } from '../components/admin/loginmethods/LoginMethodsPreview.jsx'
import { coverageOf } from '../components/admin/loginmethods/LoginMethodsCoverage.jsx'
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
  push_require_phone: true, email_require_email: true, max_contact_mismatches: 5,
}
const LIMITS = { ttl: [30, 300], max_attempts: [1, 10], resend_cooldown: [10, 300], max_requests_per_user: [1, 20],
  max_requests_per_ip: [1, 500], max_failed_verifications: [1, 20], max_contact_mismatches: [1, 20], window_minutes: 15 }
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
      coverage: over.coverage ?? { active_users: 120, with_phone: 90, with_email: 118 },
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
/** Kartın YÖNTEM anahtarı (2026-10-03'ten beri kartta ikinci bir "da sorulsun" anahtarı da var). */
const methodSwitch = (m) => within(card(m)).getByRole('switch', {
  name: m === 'push' ? /Sign-in with a push code is on|Push ile kodla giriş açık/ : /Sign-in with an email code is on|E-posta ile kodla giriş açık/,
})
const requireSwitch = (m) => within(card(m)).getByRole('switch', {
  name: m === 'push' ? /Also ask for the mobile number|Telefon numarası da sorulsun/ : /Also ask for the email address|E-posta adresi de sorulsun/,
})

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
    expect(methodSwitch('push')).toBeDisabled()
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
    fireEvent.click(methodSwitch('email'))
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
    fireEvent.click(methodSwitch('email'))
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

  // ── Kişi bilgisi doğrulaması (2026-10-03) ──────────────────────────────────────────────────────────────────────────

  it('"telefon / e-posta da sorulsun" anahtarları kartlarda (yardım metniyle); kapatılınca kayıt gövdesi boolean taşır', async () => {
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(card('push')).not.toBeNull())
    expect(requireSwitch('push')).toHaveAttribute('aria-checked', 'true')
    expect(requireSwitch('email')).toHaveAttribute('aria-checked', 'true')
    expect(card('push')).toHaveTextContent(/only if the username and the registered mobile number match|yalnız kullanıcı adı ve kayıtlı telefon eşleşirse/)
    expect(card('email')).toHaveTextContent(/only if the username and the registered email address match|yalnız kullanıcı adı ve kayıtlı e-posta adresi eşleşirse/)
    // ağ geçidi olmasa da telefon anahtarı önceden ayarlanabilir
    expect(requireSwitch('push')).not.toBeDisabled()
    fireEvent.click(requireSwitch('push'))
    fireEvent.click(screen.getByRole('button', { name: /^Save$|^Kaydet$/ }))
    await waitFor(() => expect(api.loginMethodsAdmin.save).toHaveBeenCalled())
    expect(api.loginMethodsAdmin.save.mock.calls[0][0]).toMatchObject({
      push_require_phone: false, email_require_email: true, max_contact_mismatches: 5,
    })
  })

  it('eşleşmeyen telefon / e-posta sınırı: 0 ve 21 → alanın YANINDA "1–20"; kayıt gitmez', async () => {
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(document.querySelector('[data-field="max_contact_mismatches"]')).not.toBeNull())
    const field = document.querySelector('[data-field="max_contact_mismatches"]')
    const input = within(field).getByRole('spinbutton')
    expect(field).toHaveTextContent(/Mismatched phone \/ email per user|Kullanıcı başına eşleşmeyen telefon \/ e-posta/)
    for (const bad of ['0', '21']) {
      fireEvent.change(input, { target: { value: bad } })
      fireEvent.click(screen.getByRole('button', { name: /^Save$|^Kaydet$/ }))
      await waitFor(() => expect(input).toHaveAttribute('aria-invalid', 'true'))
      expect(field).toHaveTextContent(/between 1 and 20|1–20 arasında/)
    }
    expect(api.loginMethodsAdmin.save).not.toHaveBeenCalled()
  })

  it('kapsam ipucu: telefon 90 / 120 (%75) → UYARI tonu + açıklama; e-posta 118 / 120 → normal; telefon anahtarı kapanınca telefon ipucu kalkar', async () => {
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(card('push')).not.toBeNull())
    const phone = card('push').querySelector('[data-slot="lm-coverage"][data-kind="phone"]')
    expect(phone).toHaveAttribute('data-tone', 'warn')
    expect(phone).toHaveTextContent(/90 \/ 120/)
    expect(phone).toHaveTextContent(/75/)
    expect(phone).toHaveTextContent(/cannot receive a code by push|push ile kod alamaz/)
    expect(within(phone).getByRole('progressbar')).toHaveAttribute('aria-valuenow', '75')
    const email = card('email').querySelector('[data-slot="lm-coverage"][data-kind="email"]')
    expect(email).toHaveAttribute('data-tone', 'ok')
    expect(email).toHaveTextContent(/118 \/ 120/)
    expect(email).not.toHaveTextContent(/cannot receive|kod alamaz/)
    fireEvent.click(requireSwitch('push'))
    expect(card('push').querySelector('[data-slot="lm-coverage"]')).toBeNull()
  })

  it('kapsam verisi yoksa (aktif kullanıcı 0) ipucu çizilmez', async () => {
    api.loginMethodsAdmin.get.mockResolvedValue(payload({ coverage: { active_users: 0, with_phone: 0, with_email: 0 } }))
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(card('push')).not.toBeNull())
    expect(document.querySelector('[data-slot="lm-coverage"]')).toBeNull()
  })

  it('önizleme istek adımı: telefon alanı (anahtar açık) → kapatınca kalkar; kanal seçilince e-posta alanı; TR/EN', async () => {
    api.loginMethodsAdmin.get.mockResolvedValue(payload({ settings: { push_enabled: true }, status: { push_gateway_configured: true } }))
    render(<LoginMethodsSettings />)
    const req = () => document.querySelector('[data-slot="lm-preview-request"]')
    await waitFor(() => expect(req()).not.toBeNull())
    expect(req()).toHaveAttribute('data-channel', 'push')
    expect(req()).toHaveAttribute('data-contact', 'phone')
    expect(req().querySelector('[data-slot="otp-contact"][data-kind="phone"] input')).toHaveAttribute('type', 'tel')
    fireEvent.click(requireSwitch('push'))
    await waitFor(() => expect(req()).toHaveAttribute('data-contact', 'none'))
    expect(req().querySelector('[data-slot="otp-contact"]')).toBeNull()
    // önizlemedeki kanal seçicisi: e-posta → "Kayıtlı e-posta adresi"
    fireEvent.click(within(req()).getByRole('button', { name: /^Email$|^E-posta$/ }))
    await waitFor(() => expect(req()).toHaveAttribute('data-contact', 'email'))
    expect(req().querySelector('[data-slot="otp-contact"][data-kind="email"] input')).toHaveAttribute('type', 'email')
    const preview = document.querySelector('[data-slot="lm-preview"]')
    fireEvent.click(within(preview).getByRole('button', { name: 'TR' }))
    await waitFor(() => expect(preview).toHaveAttribute('data-lang', 'tr'))
    expect(req()).toHaveTextContent(/Kayıtlı e-posta adresi/)
  })

  it('yardımcılar: doğrulama ve önizleme kanalları', () => {
    const msg = (a, b) => `${a}-${b}`
    expect(validateLoginMethods(SETTINGS, LIMITS, msg)).toEqual({})
    expect(validateLoginMethods({ ...SETTINGS, max_attempts: '11', push_ttl_seconds: '4.5' }, LIMITS, msg))
      .toEqual({ max_attempts: '1-10', push_ttl_seconds: '30-300' })
    expect(previewChannels({ push_enabled: true, email_enabled: true }, { push_gateway_configured: false })).toEqual(['email'])
    expect(previewChannels({ push_enabled: true, email_enabled: false }, { push_gateway_configured: true })).toEqual(['push'])
    // 2026-10-03: sunucu yeni sayısal alanı göndermediyse (eski sürüm) doğrulanmaz
    const legacy = { ...SETTINGS }
    delete legacy.max_contact_mismatches
    expect(validateLoginMethods(legacy, LIMITS, msg)).toEqual({})
    expect(validateLoginMethods({ ...SETTINGS, max_contact_mismatches: '' }, LIMITS, msg)).toEqual({ max_contact_mismatches: '1-20' })
    expect(coverageOf({ active_users: 10, with_phone: 7, with_email: 10 }, 'phone')).toEqual({ have: 7, total: 10, pct: 70, low: true })
    expect(coverageOf({ active_users: 1000, with_phone: 799, with_email: 0 }, 'phone')).toMatchObject({ pct: 79, low: true })
    expect(coverageOf({ active_users: 10, with_phone: 8, with_email: 12 }, 'email')).toEqual({ have: 10, total: 10, pct: 100, low: false })
    expect(coverageOf({ active_users: 0 }, 'email')).toBeNull()
    expect(previewMethods({ push_require_phone: true })).toEqual({ push_requires_phone: true, email_requires_email: false })
    expect(auditLink(['A', 'B'])).toBe('/?tab=system&a_eventType=A%2CB&a_range=7d')
  })
})
