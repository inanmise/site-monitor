import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import SmtpSettings from '../components/admin/SmtpSettings.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getSmtpSettings:  vi.fn(),
      saveSmtpSettings: vi.fn(),
      testSmtp:         vi.fn(),
      sendSmtpTest:     vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

/**
 * SMTP ayarlari — %0.47 kapsamla duruyordu, oysa uygulamadaki iki sirdan birini yonetiyor
 * (digeri LDAP bind parolasi). Testlerin odagi kozmetik degil, uc sozlesme:
 *
 * 1. PAROLA YAZ-ONLY. Kutu bos birakilirsa istek govdesine `password` HIC konulmamali.
 *    Bos dize gonderilirse saklanan parola SILINIR ve mail sessizce susar; bu, denetimde
 *    backend tarafinda duzeltilen A10 hatasinin (bozuk JSON sirlari sessizce siliyordu)
 *    onyuz ikizidir. Kaydedilmis parola ayrica ekrana HIC basilmaz.
 * 2. HOST YOKKEN test/gonderim yapilmaz — aksi halde kullanici bos yapilandirmayla
 *    "baglanti basarisiz" gorup sorunu agda arar.
 * 3. Basarisizliklar (kayit / baglanti testi) yutulmaz.
 *
 * Yukleme hatasi dali adminPanelLoadError.test.jsx'te 11 panelle birlikte pinleniyor.
 */
const SETTINGS = {
  enabled: true, host: 'smtp.example.com', port: 587,
  username: 'alerts', password_set: true,
  from_address: 'alerts@example.com', from_name: 'Site Monitor',
  auth_enabled: true, start_tls_enable: true, start_tls_required: false,
  ssl_trust: '', connection_timeout_ms: 5000, read_timeout_ms: 5000,
  write_timeout_ms: 5000, retry_delay_ms: 1000,
  inter_contact_delay_ms: 200, inter_domain_delay_ms: 300,
}

const pwBox = (c) => c.querySelector('input[type="password"]')
const saveBtn = (c) => c.querySelector('.ldap-actions [data-slot="button"][data-variant="default"]')
const testBtn = (c) => [...c.querySelectorAll('.ldap-actions button')].find(b => b.getAttribute('data-variant') === 'secondary')
const emailBox = (c) => c.querySelector('input[type="email"]')

describe('SmtpSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getSmtpSettings.mockResolvedValue({ success: true, data: { ...SETTINGS }, secret_key_set: true })
    api.admin.saveSmtpSettings.mockResolvedValue({ success: true, data: { ...SETTINGS } })
  })

  const ready = async (c) => { await waitFor(() => expect(pwBox(c)).not.toBeNull()); return c }

  it('acilista ayarlar yuklenir ve alanlar doldurulur', async () => {
    const { container } = render(<SmtpSettings />)
    await ready(container)
    expect(api.admin.getSmtpSettings).toHaveBeenCalled()
    expect(container.querySelector('input[placeholder="smtp.example.com"]').value).toBe('smtp.example.com')
  })

  it('SIZINTI KAPISI: parola alani password tipinde ve KAYITLI PAROLA ekrana basilmaz', async () => {
    const { container } = render(<SmtpSettings />)
    await ready(container)

    const box = pwBox(container)
    expect(box.getAttribute('autocomplete')).toBe('new-password')
    // Kutu bos baslar; sunucu parolayi hic gondermedigi icin ekranda hicbir yerde gorunmez.
    expect(box.value).toBe('')
  })

  it('SIR KORUMA: parola kutusu BOS ise istek govdesinde `password` HIC yok', async () => {
    const { container } = render(<SmtpSettings />)
    await ready(container)

    fireEvent.click(saveBtn(container))
    await waitFor(() => expect(api.admin.saveSmtpSettings).toHaveBeenCalled())

    const dto = api.admin.saveSmtpSettings.mock.calls[0][0]
    // Bos dize gonderilseydi saklanan parola silinir ve tum mail bildirimleri sessizce dusordu.
    expect(Object.prototype.hasOwnProperty.call(dto, 'password')).toBe(false)
    expect(dto.host).toBe('smtp.example.com')
  })

  it('SIR KORUMA: yalnizca BOSLUK girilirse de `password` gonderilmez', async () => {
    const { container } = render(<SmtpSettings />)
    await ready(container)

    fireEvent.change(pwBox(container), { target: { value: '   ' } })
    fireEvent.click(saveBtn(container))
    await waitFor(() => expect(api.admin.saveSmtpSettings).toHaveBeenCalled())

    const dto = api.admin.saveSmtpSettings.mock.calls[0][0]
    expect(Object.prototype.hasOwnProperty.call(dto, 'password')).toBe(false)
  })

  it('gercek parola girilirse gonderilir ve kaydedince kutu TEMIZLENIR', async () => {
    const { container } = render(<SmtpSettings />)
    await ready(container)

    fireEvent.change(pwBox(container), { target: { value: 'yeni-parola' } })
    fireEvent.click(saveBtn(container))

    await waitFor(() => expect(api.admin.saveSmtpSettings).toHaveBeenCalled())
    expect(api.admin.saveSmtpSettings.mock.calls[0][0].password).toBe('yeni-parola')
    // Kutu temizlenmezse parola DOM'da asili kalir ve sonraki kayitta tekrar gonderilir.
    await waitFor(() => expect(pwBox(container).value).toBe(''))
  })

  it('HOST YOKKEN baglanti testi KAPALI (bos yapilandirmayla ag suclanmasin)', async () => {
    api.admin.getSmtpSettings.mockResolvedValue({
      success: true, data: { ...SETTINGS, host: '' }, secret_key_set: true,
    })
    const { container } = render(<SmtpSettings />)
    await ready(container)

    expect(testBtn(container)).toBeDisabled()
    // Alici girilse bile gonderim acilmaz.
    fireEvent.change(emailBox(container), { target: { value: 'ops@example.com' } })
    expect(container.querySelector('.ldap-lookup-row [data-slot="button"][data-variant="default"]')).toBeDisabled()
  })

  it('BOS alici ile test maili gonderilmez', async () => {
    const { container } = render(<SmtpSettings />)
    await ready(container)

    fireEvent.keyDown(emailBox(container), { key: 'Enter' })
    await waitFor(() => expect(api.admin.sendSmtpTest).not.toHaveBeenCalled())
  })

  it('alici girilince Enter ile test maili gonderilir (kirpilmis)', async () => {
    api.admin.sendSmtpTest.mockResolvedValue({ success: true, message: 'gonderildi' })
    const { container } = render(<SmtpSettings />)
    await ready(container)

    fireEvent.change(emailBox(container), { target: { value: '  ops@example.com  ' } })
    fireEvent.keyDown(emailBox(container), { key: 'Enter' })

    await waitFor(() => expect(api.admin.sendSmtpTest).toHaveBeenCalledWith('ops@example.com'))
  })

  it('baglanti testi BASARISIZLIGI ekranda gosterilir (yutulmaz)', async () => {
    api.admin.testSmtp.mockResolvedValue({ success: false, error: 'baglanti reddedildi' })
    const { container } = render(<SmtpSettings />)
    await ready(container)

    fireEvent.click(testBtn(container))
    // Hata iki yerde birden cikiyor: gecici toast VE kalici satir ici sonuc. Kalici olani
    // hedefliyoruz — toast kaybolduktan sonra kullanicinin elinde kalan tek sinyal o.
    await waitFor(() => expect(container.querySelector('.ldap-test-result.fail')).not.toBeNull())
    expect(container.querySelector('.ldap-test-result.fail').textContent).toContain('baglanti reddedildi')
  })

  it('kaydetme HATASI yutulmaz, ekran acik kalir', async () => {
    api.admin.saveSmtpSettings.mockResolvedValue({ success: false, error: 'kaydedilemedi' })
    const { container } = render(<SmtpSettings />)
    await ready(container)

    fireEvent.click(saveBtn(container))
    await waitFor(() => expect(api.admin.saveSmtpSettings).toHaveBeenCalled())
    expect(container.querySelector('.smtp-settings')).not.toBeNull()
  })
})
