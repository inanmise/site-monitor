import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils'

// Giriş sayfası "Giriş sorunu bildir" penceresi (2026-09-27): rehberli form — oturumsuz LOGIN kaynağı.
// Gönderim gövdesi DEĞİŞMEDİ ({ username, email, errorText, message, images }); sebep eşlemesi Login.test'te de sınanır.

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ api: withApiFallback({ sendLoginHelp: vi.fn() }) }))
vi.mock('../utils/imageDownscale', () => ({ downscaleImage: vi.fn(async (f) => f) }))

import { api } from '../api/client'
import LoginHelpDialog from '../components/issues/report/LoginHelpDialog.jsx'

const png = (name = 'a.png') => new File(['x'], name, { type: 'image/png' })

function fill(dlg, { user = 'N1', email = 'u@example.com', msg = 'Giriş olmuyor' } = {}) {
  if (user != null) fireEvent.change(within(dlg).getByLabelText(/^Username/), { target: { value: user } })
  if (email != null) fireEvent.change(within(dlg).getByLabelText(/Your email address/), { target: { value: email } })
  if (msg != null) fireEvent.change(within(dlg).getByPlaceholderText(/describe the issue in detail/i), { target: { value: msg } })
}

describe('LoginHelpDialog', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('giriş formundaki kullanıcı adı önyüklenir; üç numaralı bölüm + gizlilik notu (parola asla istenmez)', async () => {
    render(<LoginHelpDialog open onClose={() => {}} initialUsername="kullanici.x" />)
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByLabelText(/^Username/).value).toBe('kullanici.x')
    expect(dlg.querySelectorAll('[data-slot="report-section"]')).toHaveLength(3)
    expect(within(dlg).getByText(/never ask for your password/i)).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="char-counter"]').textContent).toBe('0 / 5000')
  })

  it('panodan yapıştırılan ekran görüntüsü eklenir ve gönderime girer', async () => {
    api.sendLoginHelp.mockResolvedValue({ success: true, reference: 'LIR-2026-000050' })
    render(<LoginHelpDialog open onClose={() => {}} />)
    const dlg = await screen.findByRole('dialog')
    fill(dlg)
    fireEvent.paste(within(dlg).getByPlaceholderText(/describe the issue in detail/i), { clipboardData: { files: [png()], items: [] } })
    expect(await within(dlg).findByRole('button', { name: /Remove screenshot 1/ })).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: /^send report$/i }))
    await waitFor(() => expect(api.sendLoginHelp).toHaveBeenCalled())
    expect(api.sendLoginHelp.mock.calls[0][0].images).toHaveLength(1)
  })

  it('oran sınırı (429): net sebep + "Show details" + "Try again" — yazılanlar korunur ve tekrar gönderilir', async () => {
    api.sendLoginHelp
      .mockResolvedValueOnce({ success: false, status: 429, error: 'Çok fazla bildirim' })
      .mockResolvedValueOnce({ success: true, reference: 'LIR-2026-000051' })
    render(<LoginHelpDialog open onClose={() => {}} />)
    const dlg = await screen.findByRole('dialog')
    fill(dlg, { msg: 'korunmalı' })
    fireEvent.click(within(dlg).getByRole('button', { name: /^send report$/i }))
    const alert = await within(dlg).findByRole('alert')
    expect(alert).toHaveTextContent(/too many reports/i)
    expect(alert).toHaveTextContent(/has been kept/i)
    fireEvent.click(within(alert).getByRole('button', { name: /show details/i }))
    expect(within(alert).getByText(/HTTP 429/)).toBeInTheDocument()
    expect(within(dlg).getByPlaceholderText(/describe the issue in detail/i).value).toBe('korunmalı')
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(api.sendLoginHelp).toHaveBeenCalledTimes(2))
    expect(api.sendLoginHelp.mock.calls[1][0].message).toBe('korunmalı')
    expect(await within(dlg).findByText('LIR-2026-000051')).toBeInTheDocument()
  })

  it('başarı: referans (kopyala) + sırada ne var (onay e-postası adresiyle, giriş yapınca izleme) — "View my report" YOK', async () => {
    api.sendLoginHelp.mockResolvedValue({ success: true, reference: 'LIR-2026-000052' })
    render(<LoginHelpDialog open onClose={() => {}} />)
    const dlg = await screen.findByRole('dialog')
    fill(dlg, { email: 'kisi@example.com' })
    fireEvent.click(within(dlg).getByRole('button', { name: /^send report$/i }))
    const ok = await waitFor(() => { const el = dlg.querySelector('[data-slot="report-success"]'); expect(el).not.toBeNull(); return el })
    expect(within(ok).getByText('LIR-2026-000052')).toBeInTheDocument()
    expect(within(ok).getByRole('button', { name: 'Copy reference' })).toBeInTheDocument()
    expect(within(ok).getByText(/kisi@example\.com/)).toBeInTheDocument()
    expect(within(ok).getByText(/When you can sign in/i)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /View my report/ })).toBeNull()
  })

  it('geçersiz e-posta: satır içi hata, gönderim yok', async () => {
    render(<LoginHelpDialog open onClose={() => {}} />)
    const dlg = await screen.findByRole('dialog')
    fill(dlg, { email: 'bozuk' })
    fireEvent.click(within(dlg).getByRole('button', { name: /^send report$/i }))
    expect(await within(dlg).findByText(/valid email address/i)).toBeInTheDocument()
    expect(within(dlg).getByLabelText(/Your email address/).getAttribute('aria-invalid')).toBe('true')
    expect(api.sendLoginHelp).not.toHaveBeenCalled()
  })
})
