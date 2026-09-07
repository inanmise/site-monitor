import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import SecretTools from '../components/admin/SecretTools.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      secretToolsInfo: vi.fn(),
      decryptSecrets:  vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

/**
 * Anahtar Çözümleme aracı — %0.76 kapsamla duruyordu.
 *
 * Bu ekran DB'de şifreli duran SMTP/LDAP parolalarını düz metne çeviriyor; yani tüm yüzeyler
 * içinde sır sızıntısına en yakın olanı. Testlerin odağı kozmetik değil, üç sözleşme:
 *
 * 1. Çözülen değer VARSAYILAN OLARAK gizli (`type="password"`); yalnız kullanıcı açıkça
 *    "göster" derse düz metne döner. Varsayılan açık olsaydı ekran paylaşımı/omuz sörfü ile
 *    sır sızardı ve bunu kimse fark etmezdi.
 * 2. Anahtar girdisi de `type="password"` — operatörün yazdığı ana anahtar ekranda durmaz.
 * 3. Boş anahtarla istek ATILMAZ; hatalı anahtarda sunucu hatası yutulmaz.
 */
// Satir sekli KAYNAKTAN: label + column ekranda gorunur, `present` ve `ok` dallanmayi belirler
// (SecretTools.jsx:137-152). Bunlar olmadan satir "deger yok" dalina duser.
const rows = [
  { label: 'SMTP parolasi', column: 'smtp_password', present: true, ok: true, value: 'gercek-parola' },
  { label: 'LDAP bind',     column: 'ldap_bind',     present: true, ok: true, value: 'ldap-parola' },
]

describe('SecretTools', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.secretToolsInfo.mockResolvedValue({ success: true, secret_key_set: true, dev_default_key: false })
    api.admin.decryptSecrets.mockResolvedValue({ success: true, data: rows })
  })

  it('açılışta anahtar durumu sorulur', async () => {
    render(<SecretTools />)
    await waitFor(() => expect(api.admin.secretToolsInfo).toHaveBeenCalled())
  })

  it('anahtar girdisi PAROLA tipinde (operatörün yazdığı anahtar ekranda durmaz)', async () => {
    const { container } = render(<SecretTools />)
    await waitFor(() => expect(api.admin.secretToolsInfo).toHaveBeenCalled())

    const keyInput = container.querySelector('input[placeholder="SITE_MONITOR_SECRET_KEY"]')
    expect(keyInput).not.toBeNull()
    expect(keyInput.getAttribute('type')).toBe('password')
    expect(keyInput.getAttribute('autocomplete')).toBe('off')
  })

  it('BOŞ anahtarla çözümleme isteği ATILMAZ', async () => {
    const { container } = render(<SecretTools />)
    await waitFor(() => expect(api.admin.secretToolsInfo).toHaveBeenCalled())

    const btn = container.querySelector('.ldap-actions .btn-primary')
    if (btn) fireEvent.click(btn)

    await waitFor(() => expect(api.admin.decryptSecrets).not.toHaveBeenCalled())
  })

  it('anahtar girilince çözümleme YAPILIR ve satırlar listelenir', async () => {
    const { container } = render(<SecretTools />)
    await waitFor(() => expect(api.admin.secretToolsInfo).toHaveBeenCalled())

    fireEvent.change(container.querySelector('input[placeholder="SITE_MONITOR_SECRET_KEY"]'),
      { target: { value: 'ANAHTAR' } })
    fireEvent.click(container.querySelector('.ldap-actions .btn-primary'))

    await waitFor(() => expect(api.admin.decryptSecrets).toHaveBeenCalledWith('ANAHTAR'))
    expect(await screen.findByText(/smtp_password/)).toBeInTheDocument()
  })

  it('SIZINTI KAPISI: çözülen değer VARSAYILAN olarak gizli gösterilir', async () => {
    const { container } = render(<SecretTools />)
    await waitFor(() => expect(api.admin.secretToolsInfo).toHaveBeenCalled())

    fireEvent.change(container.querySelector('input[placeholder="SITE_MONITOR_SECRET_KEY"]'),
      { target: { value: 'ANAHTAR' } })
    fireEvent.click(container.querySelector('.ldap-actions .btn-primary'))
    await waitFor(() => expect(api.admin.decryptSecrets).toHaveBeenCalled())

    // Değer alanları password tipinde olmalı; düz metin olan HİÇBİR alan sırrı taşımamalı.
    const valueInputs = [...container.querySelectorAll('input[readonly]')]
      .filter(i => i.value === 'gercek-parola')
    expect(valueInputs.length, 'çözülen değer alanı bulunamadı').toBeGreaterThan(0)
    for (const i of valueInputs) {
      expect(i.getAttribute('type'), 'sır varsayılan olarak DÜZ METİN gösteriliyor').toBe('password')
    }
  })

  it('"göster" düğmesi değeri düz metne çevirir (kullanıcı AÇIKÇA isterse)', async () => {
    const { container } = render(<SecretTools />)
    await waitFor(() => expect(api.admin.secretToolsInfo).toHaveBeenCalled())

    fireEvent.change(container.querySelector('input[placeholder="SITE_MONITOR_SECRET_KEY"]'),
      { target: { value: 'ANAHTAR' } })
    fireEvent.click(container.querySelector('.ldap-actions .btn-primary'))
    await waitFor(() => expect(api.admin.decryptSecrets).toHaveBeenCalled())

    const before = container.querySelector('input[readonly][type="password"]')
    expect(before).not.toBeNull()

    // Satırdaki göz düğmesi
    const eye = before.parentElement?.querySelector('button')
    expect(eye, 'göster/gizle düğmesi bulunamadı').not.toBeNull()
    fireEvent.click(eye)

    await waitFor(() => {
      const revealed = [...container.querySelectorAll('input[readonly]')]
        .some(i => i.getAttribute('type') === 'text' && i.value === 'gercek-parola')
      expect(revealed).toBe(true)
    })
  })

  it('sunucu hatası YUTULMAZ ve satırlar boş kalır', async () => {
    api.admin.decryptSecrets.mockResolvedValue({ success: false, error: 'anahtar yanlış' })

    const { container } = render(<SecretTools />)
    await waitFor(() => expect(api.admin.secretToolsInfo).toHaveBeenCalled())

    fireEvent.change(container.querySelector('input[placeholder="SITE_MONITOR_SECRET_KEY"]'),
      { target: { value: 'YANLIS' } })
    fireEvent.click(container.querySelector('.ldap-actions .btn-primary'))

    await waitFor(() => expect(api.admin.decryptSecrets).toHaveBeenCalled())
    expect(screen.queryByText(/smtp_password/)).toBeNull()
  })

  it('istek REJECT ederse ekran çökmez', async () => {
    api.admin.decryptSecrets.mockRejectedValue(new Error('network'))

    const { container } = render(<SecretTools />)
    await waitFor(() => expect(api.admin.secretToolsInfo).toHaveBeenCalled())

    fireEvent.change(container.querySelector('input[placeholder="SITE_MONITOR_SECRET_KEY"]'),
      { target: { value: 'ANAHTAR' } })
    fireEvent.click(container.querySelector('.ldap-actions .btn-primary'))

    await waitFor(() => expect(api.admin.decryptSecrets).toHaveBeenCalled())
    expect(container.querySelector('.ldap-settings')).not.toBeNull()
  })
})
