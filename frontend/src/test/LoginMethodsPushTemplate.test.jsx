import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import LoginMethodsSettings, { changedTexts } from '../components/admin/LoginMethodsSettings.jsx'
import {
  insertAtCursor, phoneView, validateMessage, validateTitle, worstCaseLength, fillTemplate,
} from '../components/admin/loginmethods/pushTemplateModel.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const toastMock = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }))
vi.mock('../components/ui/Toast.jsx', async (orig) => ({ ...(await orig()), useToast: () => toastMock }))
vi.mock('../api/client', () => ({
  api: withApiFallback({ loginMethodsAdmin: { get: vi.fn(), save: vi.fn(), pushTest: vi.fn(), stats: vi.fn(), statsUsers: vi.fn(), statsUser: vi.fn() } }),
  getRecentFailures: () => [],
}))

import { api } from '../api/client'

/**
 * Giriş Yöntemleri → Push mesajı düzenleyicisi (2026-10-03): TR/EN sekmeleri, imlece yer tutucu ekleme, alanın yanında
 * doğrulama (diğer dildeki hata sekmeyi çevirir), canlı telefon önizlemesi (kanal süzgeci + örnek değerler), telefonda
 * değişecek karakter listesi, varsayılana dönüş, "kendime test gönder" ve kayıt gövdesi (yalnız değişen metin).
 */
const DEF = {
  tr: { title: 'SiteMonitor giriş kodu', message: 'SiteMonitor giriş kodunuz: {kod} - {sure} sn geçerli. Bu isteği siz yapmadıysanız dikkate almayın.' },
  en: { title: 'SiteMonitor sign-in code', message: 'Your SiteMonitor sign-in code: {kod} - valid for {sure} s. If you did not request it, ignore this message.' },
}
const SETTINGS = {
  ldap_enabled: true, push_enabled: true, email_enabled: true, push_ttl_seconds: 45, email_ttl_seconds: 60,
  max_attempts: 3, resend_cooldown_seconds: 30, max_requests_per_user: 5, max_requests_per_ip: 20,
  max_failed_verifications: 5, allow_global_admins: false, push_require_phone: true, email_require_email: true,
  max_contact_mismatches: 5,
  push_title_tr: DEF.tr.title, push_title_en: DEF.en.title, push_message_tr: DEF.tr.message, push_message_en: DEF.en.message,
}
const LIMITS = { ttl: [30, 300], max_attempts: [1, 10], resend_cooldown: [10, 300], max_requests_per_user: [1, 20],
  max_requests_per_ip: [1, 500], max_failed_verifications: [1, 20], max_contact_mismatches: [1, 20], window_minutes: 15 }

function payload(over = {}) {
  return {
    success: true,
    data: {
      settings: { ...SETTINGS, ...(over.settings || {}) },
      limits: LIMITS,
      status: { push_gateway_configured: true, smtp: { host: 'smtp.example.com', configured: true, alarm_mail_enabled: true },
        ldap_integration_enabled: true, secret_key_ephemeral: false, ...(over.status || {}) },
      public: { ldap: true, otp_push: true, otp_email: true },
      coverage: { active_users: 10, with_phone: 10, with_email: 10 },
      push_template: {
        defaults: DEF, title_max: 60, message_max: over.max ?? 200, charset: 'ISO-8859-9',
        placeholders: [{ key: 'kod', token: '{kod}', required: true }, { key: 'sure', token: '{sure}' }, { key: 'saat', token: '{saat}' }],
        stored_invalid: over.invalid || { tr: false, en: false },
      },
      activity: [],
      activity_types: [],
    },
  }
}

const editor = () => document.querySelector('[data-slot="lm-push-template"]')
const panel = (lang) => editor().querySelector(`[data-slot="lm-push-preview"][data-lang="${lang}"]`).closest('[role="tabpanel"]')
const field = (key) => document.querySelector(`[data-field="${key}"]`)
const message = (lang) => within(field(`push_message_${lang}`)).getByRole('textbox')
const title = (lang) => within(field(`push_title_${lang}`)).getByRole('textbox')
const preview = (lang) => document.querySelector(`[data-slot="lm-push-preview"][data-lang="${lang}"]`)
const saveBtn = () => screen.getByRole('button', { name: /^Save$|^Kaydet$/ })

describe('Giriş Yöntemleri — push mesajı düzenleyicisi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.loginMethodsAdmin.get.mockResolvedValue(payload())
    api.loginMethodsAdmin.save.mockImplementation(async (s) => payload({ settings: s }))
  })

  it('TR sekmesi açık; telefon önizlemesi örnek kod + süreyle dolu; sayaçlar sınırı gösterir', async () => {
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(editor()).not.toBeNull())
    expect(editor()).toHaveAttribute('data-lang', 'tr')
    expect(preview('tr').querySelector('[data-slot="lm-push-preview-title"]')).toHaveTextContent('SiteMonitor giriş kodu')
    expect(preview('tr').querySelector('[data-slot="lm-push-preview-message"]'))
      .toHaveTextContent('SiteMonitor giriş kodunuz: 123456 - 45 sn geçerli.')
    const counters = field('push_message_tr').querySelector('[data-slot="lm-push-counter"]')
    expect(counters).toHaveTextContent(/\/ 200/)
    expect(counters).not.toHaveAttribute('data-over')
    // EN içerik DOM'da (forceMount) ama gizli
    expect(panel('en')).toHaveAttribute('hidden')
  })

  it('yer tutucu çipi İMLECE ekler; {kod} varken {kod} çipi pasif', async () => {
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(editor()).not.toBeNull())
    const ta = message('tr')
    fireEvent.change(ta, { target: { value: 'Kodunuz  hazır' } })
    const chipKod = () => panel('tr').querySelector('[data-slot="lm-push-chip"][data-token="{kod}"]')
    expect(chipKod()).not.toBeDisabled()
    ta.setSelectionRange(8, 8)
    fireEvent.click(chipKod())
    expect(message('tr')).toHaveValue('Kodunuz {kod} hazır')
    expect(chipKod()).toBeDisabled()
    message('tr').setSelectionRange(0, 0)
    fireEvent.click(panel('tr').querySelector('[data-slot="lm-push-chip"][data-token="{saat}"]'))
    expect(message('tr')).toHaveValue('{saat}Kodunuz {kod} hazır')
  })

  it('önizleme TELEFONUN gördüğünü gösterir: — → -, ✓ → OK, emoji düşer; değişen karakterler listelenir', async () => {
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(editor()).not.toBeNull())
    fireEvent.change(message('tr'), { target: { value: 'Kod {kod} — hazır ✓ 😀' } })
    fireEvent.change(title('tr'), { target: { value: 'Giriş “kodu”' } })
    expect(preview('tr').querySelector('[data-slot="lm-push-preview-message"]')).toHaveTextContent(/^Kod 123456 - hazır OK$/)
    expect(preview('tr').querySelector('[data-slot="lm-push-preview-title"]')).toHaveTextContent('Giriş "kodu"')
    const chars = panel('tr').querySelector('[data-slot="lm-push-chars"]')
    expect(chars).not.toBeNull()
    const conv = [...chars.querySelectorAll('[data-kind="converted"]')].map((b) => b.textContent)
    expect(conv.join(' ')).toMatch(/— → -/)
    expect(conv.join(' ')).toMatch(/✓ → OK/)
    expect(chars.querySelector('[data-kind="dropped"]')).toHaveTextContent(/😀/)
  })

  it('doğrulama ALAN YANINDA: {kod} silinince mesaj hatası; kayıt gitmez', async () => {
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(editor()).not.toBeNull())
    fireEvent.change(message('tr'), { target: { value: 'Kodunuz hazır' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(message('tr')).toHaveAttribute('aria-invalid', 'true'))
    expect(field('push_message_tr')).toHaveTextContent(/must contain the \{kod\} placeholder|\{kod\} yer tutucusunu içermeli/)
    expect(api.loginMethodsAdmin.save).not.toHaveBeenCalled()
  })

  it('diğer dildeki hata sekmeyi O DİLE çevirir (alan görünür olsun)', async () => {
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(editor()).not.toBeNull())
    fireEvent.mouseDown(within(editor()).getByRole('tab', { name: /English|İngilizce/ }))
    await waitFor(() => expect(editor()).toHaveAttribute('data-lang', 'en'))
    fireEvent.change(title('en'), { target: { value: 'Code {kod}' } })
    fireEvent.mouseDown(within(editor()).getByRole('tab', { name: /Turkish|Türkçe/ }))
    await waitFor(() => expect(editor()).toHaveAttribute('data-lang', 'tr'))
    fireEvent.click(saveBtn())
    await waitFor(() => expect(editor()).toHaveAttribute('data-lang', 'en'))
    expect(field('push_title_en')).toHaveTextContent(/cannot be used in the title|başlıkta kullanılamaz/)
    expect(panel('en')).not.toHaveAttribute('hidden')
    expect(api.loginMethodsAdmin.save).not.toHaveBeenCalled()
  })

  it('kayıt gövdesi YALNIZ değişen push metnini taşır (kırpılmış); sunucu alan reddi alanın altında', async () => {
    api.loginMethodsAdmin.save.mockResolvedValueOnce({ success: false, status: 400, field: 'push_message_tr', error: 'Mesaj en uzun değerlerle 210 karakter; push mesaj sınırı 200.' })
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(editor()).not.toBeNull())
    fireEvent.change(message('tr'), { target: { value: '  Kurum kodu: {kod} ({saat})  ' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.loginMethodsAdmin.save).toHaveBeenCalled())
    const sent = api.loginMethodsAdmin.save.mock.calls[0][0]
    expect(sent.push_message_tr).toBe('Kurum kodu: {kod} ({saat})')
    expect(sent).not.toHaveProperty('push_title_tr')
    expect(sent).not.toHaveProperty('push_message_en')
    await waitFor(() => expect(field('push_message_tr')).toHaveTextContent(/210 karakter/))
  })

  it('"Varsayılana dön" metni yerleşik varsayılana çevirir; varsayılandayken pasif', async () => {
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(editor()).not.toBeNull())
    const reset = () => panel('tr').querySelector('[data-slot="lm-push-reset"]')
    expect(reset()).toBeDisabled()
    fireEvent.change(title('tr'), { target: { value: 'Özel başlık' } })
    expect(reset()).not.toBeDisabled()
    expect(within(editor()).getByRole('tab', { name: /Turkish|Türkçe/ })).toHaveTextContent(/Custom|Özel/)
    fireEvent.click(reset())
    expect(title('tr')).toHaveValue(DEF.tr.title)
    expect(message('tr')).toHaveValue(DEF.tr.message)
  })

  it('kendime test gönder: KAYDEDİLMEMİŞ taslak + dil gider; başarı tostu; alan hatası alanın altında', async () => {
    api.loginMethodsAdmin.pushTest.mockResolvedValueOnce({ success: true, code: 'OK', target: 'ADMIN', message: 'Test bildirimi ADMIN kullanıcısına gönderildi.' })
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(editor()).not.toBeNull())
    fireEvent.change(message('tr'), { target: { value: 'Taslak {kod}' } })
    fireEvent.click(panel('tr').querySelector('[data-slot="lm-push-test"]'))
    await waitFor(() => expect(api.loginMethodsAdmin.pushTest).toHaveBeenCalledWith({ lang: 'tr', title: DEF.tr.title, message: 'Taslak {kod}' }))
    await waitFor(() => expect(toastMock.success).toHaveBeenCalledWith('Test bildirimi ADMIN kullanıcısına gönderildi.'))
    expect(api.loginMethodsAdmin.save).not.toHaveBeenCalled()

    api.loginMethodsAdmin.pushTest.mockResolvedValueOnce({ success: false, status: 400, field: 'push_message_tr', error: 'Bilinmeyen yer tutucu: {x}.' })
    fireEvent.click(panel('tr').querySelector('[data-slot="lm-push-test"]'))
    await waitFor(() => expect(field('push_message_tr')).toHaveTextContent('Bilinmeyen yer tutucu: {x}.'))

    api.loginMethodsAdmin.pushTest.mockResolvedValueOnce({ success: false, code: 'HTTP 503', error: 'Push ağ geçidi HTTP 503 döndürdü.' })
    fireEvent.click(panel('tr').querySelector('[data-slot="lm-push-test"]'))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith('Push ağ geçidi HTTP 503 döndürdü.'))
  })

  it('ağ geçidi yoksa test düğmesi PASİF ve nedeni yazılı; kayıtlı metin geçersizse uyarı', async () => {
    api.loginMethodsAdmin.get.mockResolvedValue(payload({ status: { push_gateway_configured: false }, invalid: { tr: false, en: true } }))
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(editor()).not.toBeNull())
    expect(panel('tr').querySelector('[data-slot="lm-push-test"]')).toBeDisabled()
    expect(panel('tr').querySelector('[data-slot="lm-push-test-hint"]')).toHaveAttribute('data-reason', 'no-gateway')
    expect(document.querySelector('[data-slot="lm-push-stored-invalid"]')).toHaveTextContent(/English|İngilizce/)
    fireEvent.click(panel('tr').querySelector('[data-slot="lm-push-test"]'))
    expect(api.loginMethodsAdmin.pushTest).not.toHaveBeenCalled()
  })

  it('eski sunucu (push metni alanları yok) → düzenleyici çizilmez', async () => {
    const p = payload()
    for (const k of ['push_title_tr', 'push_title_en', 'push_message_tr', 'push_message_en']) delete p.data.settings[k]
    delete p.data.push_template
    api.loginMethodsAdmin.get.mockResolvedValue(p)
    render(<LoginMethodsSettings />)
    await waitFor(() => expect(document.querySelector('[data-slot="lm-method"]')).not.toBeNull())
    expect(editor()).toBeNull()
  })
})

describe('pushTemplateModel — backend OtpPushTemplate aynası', () => {
  const t = (k, ...a) => `${k}${a.length ? ':' + a.join('|') : ''}`

  it('mesaj kuralları: boş = geçerli; {kod} yok / iki kez / bilinmeyen; en kötü dolumla tavan', () => {
    expect(validateMessage('', 200, t)).toBeNull()
    expect(validateMessage('Kod {kod}', 200, t)).toBeNull()
    expect(validateMessage('Kod yok', 200, t)).toBe('lm.push.err.noCode')
    expect(validateMessage('{kod}{kod}', 200, t)).toBe('lm.push.err.multiCode')
    expect(validateMessage('{kod} {ad} { sure }', 200, t)).toBe('lm.push.err.unknown:{ad}, { sure }')
    expect(worstCaseLength('{kod} {sure} {saat}')).toBe(16)
    const s = 'a'.repeat(74) + ' {kod}'
    expect(validateMessage(s, 81, t)).toBeNull()
    expect(validateMessage(s, 80, t)).toBe('lm.push.err.tooLong:81|80')
  })

  it('başlık kuralları: {kod} / yer tutucu yasak, 60 (süzgeçten sonra), yalnız emoji boş kalır', () => {
    expect(validateTitle('Kod {kod}', t)).toBe('lm.push.err.titleCode')
    expect(validateTitle('Saat {saat}', t)).toBe('lm.push.err.titlePlaceholder')
    expect(validateTitle('x'.repeat(60), t)).toBeNull()
    expect(validateTitle('x'.repeat(59) + '…', t)).toBe('lm.push.err.titleLong:60|62')
    expect(validateTitle('🔐🔑', t)).toBe('lm.push.err.titleEmpty')
  })

  it('imlece ekleme, dolum ve telefon görünümü (boş = varsayılan, tavanı aşan metin kırpılır)', () => {
    expect(insertAtCursor('ab', '{kod}', 1, 1)).toEqual({ value: 'a{kod}b', caret: 6 })
    expect(insertAtCursor('abc', '{x}', 1, 2)).toEqual({ value: 'a{x}c', caret: 4 })
    expect(insertAtCursor('ab', '{x}')).toEqual({ value: 'ab{x}', caret: 5 })
    expect(fillTemplate('{kod}-{sure}-{saat}-{ad}', { kod: '1', sure: '2', saat: '3' })).toBe('1-2-3-{ad}')
    expect(phoneView({ title: '', message: '', defaults: DEF.en, samples: { kod: '123456', sure: 45 } }))
      .toEqual({ title: 'SiteMonitor sign-in code', message: 'Your SiteMonitor sign-in code: 123456 - valid for 45 s. If you did not request it, ignore this message.' })
    expect(phoneView({ title: 'T', message: 'bir iki üç dört beş {kod}', defaults: DEF.tr, samples: { kod: '123456' }, max: 15 }).message)
      .toBe('bir iki üç...')
    expect(changedTexts({ push_title_tr: ' a ', push_message_tr: 'b' }, { push_title_tr: 'a', push_message_tr: 'c' }))
      .toEqual({ push_message_tr: 'b' })
  })
})
