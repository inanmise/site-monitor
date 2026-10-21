import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from './test-utils.jsx'
import LoginMethodsSettings from '../components/admin/LoginMethodsSettings.jsx'
import { PREVIEW_DEBOUNCE_MS } from '../components/admin/loginmethods/UsernamePrefixCard.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ loginMethodsAdmin: { get: vi.fn(), save: vi.fn(), prefixPreview: vi.fn() } }),
  getRecentFailures: () => [],
}))

import { api } from '../api/client'

/**
 * Ayarlar → Giriş Yöntemleri → "Kullanıcı adı ön eki" kartı (2026-10-21, kullanıcı isteği: "yalnız N ile başlayan kullanıcıya
 * login izni; harfleri ayarlardan yöneteyim"): anahtar + izinli ön ek / muaf ad çipleri (geçersiz değer ALANIN altında), canlı
 * önizleme (kaydetmeden; hata "okunamadı" der, asla 0 uydurmaz; kendi hesabınız giremezse tehlike uyarısı), kayıt YALNIZ
 * değişen ön ek alanlarını gönderir, sıkılaştıran kayıtta onay (etkilenecek kişi sayısıyla), sunucu LOCKOUT_SELF reddi
 * alanın altında, sunucu bloğu yoksa kart çizilmez.
 */
const SETTINGS = {
  ldap_enabled: true, push_enabled: false, email_enabled: true, push_ttl_seconds: 45, email_ttl_seconds: 60,
  max_attempts: 3, resend_cooldown_seconds: 30, max_requests_per_user: 5, max_requests_per_ip: 20,
  max_failed_verifications: 5, allow_global_admins: false,
  push_require_phone: true, email_require_email: true, max_contact_mismatches: 5,
}
const LIMITS = { ttl: [30, 300], max_attempts: [1, 10], resend_cooldown: [10, 300], max_requests_per_user: [1, 20],
  max_requests_per_ip: [1, 500], max_failed_verifications: [1, 20], max_contact_mismatches: [1, 20], window_minutes: 15 }

function payload({ prefix = { enabled: false, allowed: [], exempt: [] }, withBlock = true } = {}) {
  const settings = { ...SETTINGS }
  if (withBlock) {
    settings.username_prefix_enabled = prefix.enabled
    settings.username_prefix_allowed = prefix.allowed
    settings.username_prefix_exempt = prefix.exempt
  }
  return {
    success: true,
    data: {
      settings,
      limits: LIMITS,
      status: { push_gateway_configured: false, smtp: { host: 'smtp.example.com', configured: true }, ldap_integration_enabled: true },
      public: { ldap: true, otp_push: false, otp_email: true },
      coverage: { active_users: 0 },
      activity: [],
      activity_types: [],
      ...(withBlock ? { username_prefix: { ...prefix, active: prefix.enabled && prefix.allowed.length > 0, bootstrap_admin: 'admin',
        limits: { prefix_max_len: 10, max_prefixes: 20, exempt_max_len: 100, max_exempt: 50, sample_limit: 50 } } } : {}),
    },
  }
}

const PREVIEW = {
  success: true,
  data: {
    allowed: ['N'], exempt: [], active_users: 120, exempt_users: 1, refused: 3, allowed_users: 117, truncated: false,
    self_refused: false, sample_limit: 50,
    sample: [
      { username: 'MP100', display_name: 'Mehmet Pınar (Dijital Kanallar Uygulama Geliştirme Müdürlüğü)', auth_source: 'LDAP' },
      { username: 'MP101', display_name: 'Ayşe Kara', auth_source: 'LDAP' },
      { username: 'X1', display_name: null, auth_source: 'LOCAL' },
    ],
  },
}

const card = () => document.querySelector('[data-slot="lm-prefix"]')
const allowedInput = () => within(document.querySelector('[data-field="username_prefix_allowed"]')).getByRole('textbox')
const exemptInput = () => within(document.querySelector('[data-field="username_prefix_exempt"]')).getByRole('textbox')
const chips = (field) => [...document.querySelectorAll(`[data-field="${field}"] [data-slot="token-chip"]`)].map((c) => c.getAttribute('data-value'))
const saveBtn = () => screen.getByRole('button', { name: /^Save$|^Kaydet$/ })

async function renderReady(opts) {
  api.loginMethodsAdmin.get.mockResolvedValue(payload(opts))
  render(<LoginMethodsSettings />)
  await waitFor(() => expect(card()).not.toBeNull())
}

function addToken(input, text) {
  fireEvent.change(input, { target: { value: text } })
  fireEvent.keyDown(input, { key: 'Enter' })
}

describe('LoginMethodsSettings — kullanıcı adı ön eki kartı', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useRealTimers()
    api.loginMethodsAdmin.save.mockImplementation(async () => payload())
    api.loginMethodsAdmin.prefixPreview.mockResolvedValue(PREVIEW)
  })

  it('sunucu bloğu yoksa (eski sürüm) kart çizilmez', async () => {
    api.loginMethodsAdmin.get.mockResolvedValue(payload({ withBlock: false }))
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(document.querySelector('[data-slot="lm-method"]')).not.toBeNull())
    expect(card()).toBeNull()
  })

  it('çip girişi: küçük harf büyür, tekrar atlanır, geçersiz ön ek ALANIN altında söylenir ve kutuda kalır; × kaldırır', async () => {
    await renderReady()
    addToken(allowedInput(), 'n')
    addToken(allowedInput(), 'N')
    expect(chips('username_prefix_allowed')).toEqual(['N'])
    addToken(allowedInput(), 'Ç1')
    expect(document.querySelector('[data-field="username_prefix_allowed"]')).toHaveTextContent(/Invalid prefix: "Ç1"|Geçersiz ön ek: "Ç1"/)
    expect(allowedInput()).toHaveValue('Ç1')
    expect(allowedInput()).toHaveAttribute('aria-invalid', 'true')
    // yapıştırılan liste
    fireEvent.change(allowedInput(), { target: { value: '' } })
    fireEvent.paste(allowedInput(), { clipboardData: { getData: () => 'mp, t2' } })
    expect(chips('username_prefix_allowed')).toEqual(['N', 'MP', 'T2'])
    fireEvent.click(screen.getByRole('button', { name: /Remove prefix MP|MP ön ekini kaldır/ }))
    expect(chips('username_prefix_allowed')).toEqual(['N', 'T2'])
  })

  it('kutuda geçersiz yazı kalmışsa kayıt GİTMEZ (sessizce atlanıp "kaydedildi" denmez), hata alanda kalır', async () => {
    await renderReady({ prefix: { enabled: false, allowed: ['N'], exempt: [] } })
    addToken(allowedInput(), 'N-1')
    fireEvent.click(within(card()).getByRole('switch'))
    fireEvent.click(saveBtn())
    await waitFor(() => expect(document.querySelector('[data-field="username_prefix_allowed"]')).toHaveTextContent(/N-1/))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(api.loginMethodsAdmin.save).not.toHaveBeenCalled()
  })

  it('açıkken boş liste → kayıt GİTMEZ, hata izinli ön ek alanının altında', async () => {
    await renderReady()
    fireEvent.click(within(card()).getByRole('switch'))
    fireEvent.click(saveBtn())
    await waitFor(() => expect(document.querySelector('[data-field="username_prefix_allowed"]'))
      .toHaveTextContent(/at least one allowed prefix|en az bir izinli ön ek/))
    expect(api.loginMethodsAdmin.save).not.toHaveBeenCalled()
  })

  it('önizleme: ön ek girilince (bekleyip) verilen değerlerle sorulur; sayı, liste (uzun adlar sarar), oturum notu', async () => {
    await renderReady()
    expect(document.querySelector('[data-slot="lm-prefix-preview"]')).toHaveAttribute('data-state', 'idle')
    addToken(allowedInput(), 'n')
    await waitFor(() => expect(api.loginMethodsAdmin.prefixPreview).toHaveBeenCalledWith(['N'], []), { timeout: PREVIEW_DEBOUNCE_MS + 1500 })
    await waitFor(() => expect(document.querySelector('[data-slot="lm-prefix-preview"]')).toHaveAttribute('data-state', 'ready'))
    const refused = document.querySelector('[data-slot="lm-prefix-refused"]')
    expect(refused).toHaveAttribute('data-count', '3')
    expect(refused).toHaveTextContent(/3 of 120|120 aktif kullanıcıdan 3/)
    const users = document.querySelectorAll('[data-slot="lm-prefix-user"]')
    expect(users).toHaveLength(3)
    expect(users[0]).toHaveTextContent('Mehmet Pınar (Dijital Kanallar Uygulama Geliştirme Müdürlüğü)')
    expect(users[0].className).toContain('[overflow-wrap:anywhere]')
    expect(document.querySelector('[data-slot="lm-prefix-self"]')).toBeNull()
  })

  it('önizleme okunamazsa "okunamadı" + yeniden dene — asla "0 kullanıcı" değil', async () => {
    api.loginMethodsAdmin.prefixPreview.mockResolvedValue({ success: false, status: 500, error: 'x' })
    await renderReady({ prefix: { enabled: true, allowed: ['N'], exempt: [] } })
    await waitFor(() => expect(document.querySelector('[data-slot="lm-prefix-preview"]')).toHaveAttribute('data-state', 'error'),
      { timeout: PREVIEW_DEBOUNCE_MS + 1500 })
    expect(document.querySelector('[data-slot="lm-prefix-refused"]')).toBeNull()
    expect(document.querySelector('[data-slot="lm-prefix-preview-error"]')).toHaveTextContent(/couldn’t be read|okunamadı/)
    api.loginMethodsAdmin.prefixPreview.mockResolvedValue(PREVIEW)
    fireEvent.click(within(document.querySelector('[data-slot="lm-prefix-preview-error"]')).getByRole('button'))
    await waitFor(() => expect(document.querySelector('[data-slot="lm-prefix-preview"]')).toHaveAttribute('data-state', 'ready'))
  })

  it('kendi hesabınız giremezse tehlike uyarısı', async () => {
    api.loginMethodsAdmin.prefixPreview.mockResolvedValue({ ...PREVIEW, data: { ...PREVIEW.data, self_refused: true } })
    await renderReady({ prefix: { enabled: false, allowed: ['N'], exempt: [] } })
    await waitFor(() => expect(document.querySelector('[data-slot="lm-prefix-self"]')).not.toBeNull(), { timeout: PREVIEW_DEBOUNCE_MS + 1500 })
    expect(document.querySelector('[data-slot="lm-prefix-self"]')).toHaveTextContent(/own account|kendi hesabınız/i)
  })

  it('kayıt: kuralı açmak ONAY ister (kişi sayısıyla); onaylanınca YALNIZ değişen ön ek alanları gider', async () => {
    await renderReady({ prefix: { enabled: false, allowed: ['N'], exempt: [] } })
    await waitFor(() => expect(document.querySelector('[data-slot="lm-prefix-preview"]')).toHaveAttribute('data-state', 'ready'),
      { timeout: PREVIEW_DEBOUNCE_MS + 1500 })
    fireEvent.click(within(card()).getByRole('switch'))
    fireEvent.click(saveBtn())
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveTextContent(/3 active users|3 aktif kullanıcı/)
    fireEvent.click(within(dlg).getByRole('button', { name: /Apply rule|Kuralı uygula/ }))
    await waitFor(() => expect(api.loginMethodsAdmin.save).toHaveBeenCalled())
    const sent = api.loginMethodsAdmin.save.mock.calls[0][0]
    expect(sent.username_prefix_enabled).toBe(true)
    expect(sent).not.toHaveProperty('username_prefix_allowed')   // değişmedi
    expect(sent).not.toHaveProperty('username_prefix_exempt')
  })

  it('ilgisiz kayıt (LDAP dışı bir alan) ön ek alanlarını HİÇ göndermez; onay sorulmaz', async () => {
    await renderReady({ prefix: { enabled: true, allowed: ['N'], exempt: ['OPS'] } })
    const ttl = within(document.querySelector('[data-slot="lm-method"][data-method="email"]')).getByRole('spinbutton')
    fireEvent.change(ttl, { target: { value: '90' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.loginMethodsAdmin.save).toHaveBeenCalled())
    const sent = api.loginMethodsAdmin.save.mock.calls[0][0]
    expect(Object.keys(sent).filter((k) => k.startsWith('username_prefix'))).toEqual([])
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('sunucu LOCKOUT_SELF reddi izinli ön ek alanının altında gösterilir', async () => {
    api.loginMethodsAdmin.save.mockResolvedValueOnce({ success: false, status: 400, code: 'LOCKOUT_SELF', field: 'username_prefix_allowed',
      error: 'With this setting your own account (MP7) couldn’t sign in.' })
    await renderReady({ prefix: { enabled: true, allowed: ['N'], exempt: [] } })
    addToken(exemptInput(), 'ops')
    fireEvent.click(saveBtn())
    const dlg = await screen.findByRole('dialog')
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: /Apply rule|Kuralı uygula/ })) })
    await waitFor(() => expect(document.querySelector('[data-field="username_prefix_allowed"]')).toHaveTextContent(/own account \(MP7\)/))
    expect(api.loginMethodsAdmin.save.mock.calls[0][0].username_prefix_exempt).toEqual(['OPS'])
  })

  it('muaf ad: bootstrap yönetici ipucunda adıyla; boşluk ayırıcıdır (iki ad), 100+ karakter alanın altında reddedilir', async () => {
    await renderReady()
    expect(document.querySelector('[data-field="username_prefix_exempt"]')).toHaveTextContent(/\(admin\)/)
    fireEvent.change(exemptInput(), { target: { value: 'servis.hesap\tb' } })
    expect(chips('username_prefix_exempt')).toEqual(['SERVIS.HESAP', 'B'])
    addToken(exemptInput(), 'x'.repeat(101))
    expect(document.querySelector('[data-field="username_prefix_exempt"]')).toHaveTextContent(/Invalid username|Geçersiz kullanıcı adı/)
    expect(chips('username_prefix_exempt')).toEqual(['SERVIS.HESAP', 'B'])
  })

  it('sınır (inceleme 2026-10-21): 60 muaf ad yapıştırılınca 50 çip olur, FAZLA 10 ad kutuda kalır ve "en çok 50" alanın altında; kayıt gitmez', async () => {
    await renderReady({ prefix: { enabled: true, allowed: ['N'], exempt: [] } })
    const names = Array.from({ length: 60 }, (_, i) => `svc${String(i + 1).padStart(2, '0')}`)
    fireEvent.paste(exemptInput(), { clipboardData: { getData: () => names.join(', ') } })
    expect(chips('username_prefix_exempt')).toHaveLength(50)
    expect(chips('username_prefix_exempt')[49]).toBe('SVC50')
    expect(exemptInput()).toHaveValue(names.slice(50).join(' '))
    expect(exemptInput()).toHaveAttribute('aria-invalid', 'true')
    expect(document.querySelector('[data-field="username_prefix_exempt"]'))
      .toHaveTextContent(/At most 50 exempt usernames|En çok 50 muaf kullanıcı adı/)
    fireEvent.click(saveBtn())
    await act(async () => {})
    expect(api.loginMethodsAdmin.save).not.toHaveBeenCalled()
    // izinli ön ek sınırı da aynı (20)
    const prefixes = Array.from({ length: 22 }, (_, i) => `P${i + 1}`)
    fireEvent.paste(allowedInput(), { clipboardData: { getData: () => prefixes.join(' ') } })
    expect(chips('username_prefix_allowed')).toHaveLength(20)
    expect(allowedInput()).toHaveValue('P20 P21 P22')
    expect(document.querySelector('[data-field="username_prefix_allowed"]'))
      .toHaveTextContent(/At most 20 prefixes|En çok 20 ön ek/)
  })
})
