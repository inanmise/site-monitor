import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import Login from '../pages/Login.jsx'

// We mock the entire api module so the form's submit handler resolves
// without touching the network. The shape mirrors the real client.
vi.mock('../api/client', () => ({
  api: {
    login: vi.fn(),
    // Hero istatistikleri artık gerçek veriden (public endpoint) — testte sabit mock.
    getPublicStats: vi.fn(async () => ({ success: true, data: { monitored_targets: 512, availability_pct: 99.9 } })),
    sendLoginHelp: vi.fn(async () => ({ success: true })),
  },
}))

import { api } from '../api/client'

describe('Login', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('renders username and password inputs', () => {
    render(<Login onLogin={() => {}} />)
    expect(screen.getByLabelText(/username/i)).toBeDefined()
    // Password label may differ; assert by id used in Login.jsx
    expect(document.getElementById('lp-pass')).not.toBeNull()
  })

  it('submitting valid creds calls onLogin with the api response', async () => {
    const fakeResponse = { success: true, username: 'admin', systemRole: 'ADMIN' }
    api.login.mockResolvedValueOnce(fakeResponse)
    const onLogin = vi.fn()

    render(<Login onLogin={onLogin} />)
    fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'admin' } })
    fireEvent.change(document.getElementById('lp-pass'), { target: { value: 'secret' } })
    fireEvent.submit(document.querySelector('form'))

    await waitFor(() => expect(api.login).toHaveBeenCalledWith('admin', 'secret', false))
    await waitFor(() => expect(onLogin).toHaveBeenCalledWith(fakeResponse))
  })

  it('failed login shows an error and does not call onLogin', async () => {
    api.login.mockResolvedValueOnce({ success: false, error: 'Invalid credentials' })
    const onLogin = vi.fn()

    render(<Login onLogin={onLogin} />)
    fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'admin' } })
    fireEvent.change(document.getElementById('lp-pass'), { target: { value: 'wrong' } })
    fireEvent.submit(document.querySelector('form'))

    await waitFor(() => expect(api.login).toHaveBeenCalled())
    expect(onLogin).not.toHaveBeenCalled()
  })

  it('shows confirm modal on ACTIVE_SESSION_EXISTS and force-logs-in on confirm', async () => {
    api.login
      .mockResolvedValueOnce({ success: false, error_code: 'ACTIVE_SESSION_EXISTS' })
      .mockResolvedValueOnce({ success: true, username: 'admin' })
    const onLogin = vi.fn()

    render(<Login onLogin={onLogin} />)
    fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'admin' } })
    fireEvent.change(document.getElementById('lp-pass'), { target: { value: 'secret' } })
    fireEvent.submit(document.querySelector('form'))

    // Onay modalı çıkar, henüz login tamamlanmaz
    await waitFor(() => expect(screen.getByText(/Active Session Elsewhere/i)).toBeDefined())
    expect(onLogin).not.toHaveBeenCalled()

    // Onayla → force_login=true ile tekrar çağrılır ve giriş tamamlanır
    fireEvent.click(screen.getByText(/Continue and close the other/i))
    await waitFor(() => expect(api.login).toHaveBeenLastCalledWith('admin', 'secret', false, true))
    await waitFor(() => expect(onLogin).toHaveBeenCalled())
  })

  it('remember-me persists the username on successful login', async () => {
    api.login.mockResolvedValueOnce({ success: true, username: 'remember-user' })
    const onLogin = vi.fn()

    render(<Login onLogin={onLogin} />)
    fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'remember-user' } })
    fireEvent.change(document.getElementById('lp-pass'), { target: { value: 'p' } })
    // Toggle remember-me checkbox -- find by label OR fallback to first checkbox in the form.
    const rememberCheckbox = document.querySelector('input[type="checkbox"]')
    if (rememberCheckbox) fireEvent.click(rememberCheckbox)
    fireEvent.submit(document.querySelector('form'))

    await waitFor(() => expect(onLogin).toHaveBeenCalled())
    expect(localStorage.getItem('cert-monitor-remembered-user')).toBe('remember-user')
  })

  it('shows a session-expired notice when sessionExpired is set (AUTH-1)', () => {
    render(<Login onLogin={() => {}} sessionExpired />)
    expect(screen.getByText(/session has expired/i)).toBeDefined()
    // the normal login form is still available beneath the notice
    expect(screen.getByLabelText(/username/i)).toBeDefined()
  })

  it('does not show the session-expired notice by default', () => {
    render(<Login onLogin={() => {}} />)
    expect(screen.queryByText(/session has expired/i)).toBeNull()
  })

  it('renders the executive left panel: wordmark, badge, headline, capability bento (8 tiles), hero stats, rings + pulse', async () => {
    const { container } = render(<Login onLogin={() => {}} />)
    // Üst bölge: wordmark + ENTERPRISE rozeti
    expect(container.querySelector('.lp-wordmark')?.textContent).toBe('CertMonitor')
    expect(container.querySelector('.lp-badge')?.textContent).toBe('ENTERPRISE')
    // Orta bölge: başlık (EN default) + izleme yetenekleri bento ızgarası (8 kutu, flagship Sertifika)
    expect(screen.getByText(/under control on a single screen/i)).toBeDefined()
    expect(container.querySelectorAll('.lp-tile')).toHaveLength(8)
    expect(container.querySelector('.lp-tile--flag')).not.toBeNull()
    expect(screen.getByText('DNS')).toBeDefined()
    expect(screen.getByText('HTTP/Website')).toBeDefined()
    expect(screen.getByText('Uptime')).toBeDefined()
    // Her kutuda hover mikro-açıklama + flagship'te canlı nabız noktası
    expect(container.querySelectorAll('.lp-tile-desc')).toHaveLength(8)
    expect(container.querySelector('.lp-tile--flag .lp-tile-live')).not.toBeNull()
    // Operasyon grubu — 4 çip (Alarm/Olay/Rapor/Bakım)
    expect(container.querySelectorAll('.lp-chip')).toHaveLength(4)
    expect(screen.getByText('Weekly Report')).toBeDefined()
    // Alt bölge: hero istatistikler — public endpoint'ten gerçek veri (mock: 512 / 99.9%)
    expect(await screen.findByText('512')).toBeDefined()
    expect(await screen.findByText('99.9%')).toBeDefined()
    // Dekoratif konsantrik halkalar (SVG) + nabız noktası
    expect(container.querySelector('.lp-bg')).not.toBeNull()
    expect(container.querySelector('.lp-accent-dot')).not.toBeNull()
    // Dil düğmesi korunur (EN default → 'Türkçe' etiketi)
    expect(screen.getByText('Türkçe')).toBeDefined()
    // Eski öğeler kaldırıldı (kalkan başlığı + düz özellik listesi + eski nokta)
    expect(screen.queryByText(/Meet CertMonitor/i)).toBeNull()
    expect(container.querySelector('.lp-feature')).toBeNull()
    expect(container.querySelector('.lp-blink-dot')).toBeNull()
  })

  it('sorun bildir: pop-up açılır; kullanıcı adı zorunlu; dolu formla sendLoginHelp payload\'ı gider; teşekkür görünür', async () => {
    const { api } = await import('../api/client')
    const { within } = await import('./test-utils.jsx')
    render(<Login onLogin={() => {}} />)

    fireEvent.click(screen.getByRole('button', { name: /report it to the system administrator/i }))
    const dialog = await screen.findByRole('dialog')

    // Kullanıcı adı + email boş → gönder pasif + zorunluluk uyarıları
    const sendBtn = within(dialog).getByRole('button', { name: /^send report$/i })
    expect(sendBtn.disabled).toBe(true)
    expect(within(dialog).getByText(/username is required/i)).toBeDefined()
    expect(within(dialog).getByText(/email is required/i)).toBeDefined()

    fireEvent.change(within(dialog).getByLabelText(/username/i), { target: { value: 'N12345' } })
    fireEvent.change(within(dialog).getByPlaceholderText(/paste the error message/i), { target: { value: 'HTTP 423 Locked' } })
    fireEvent.change(within(dialog).getByPlaceholderText(/describe the issue in detail/i), { target: { value: 'My account is locked' } })
    // email zorunlu — hâlâ pasif
    expect(sendBtn.disabled).toBe(true)
    fireEvent.change(within(dialog).getByLabelText(/your email/i), { target: { value: 'user@akbank.com' } })
    expect(sendBtn.disabled).toBe(false)

    api.sendLoginHelp.mockResolvedValueOnce({ success: true, reference: 'LIR-2026-000042' })
    fireEvent.click(sendBtn)
    await waitFor(() => expect(api.sendLoginHelp).toHaveBeenCalledWith({
      username: 'N12345', email: 'user@akbank.com', errorText: 'HTTP 423 Locked',
      message: 'My account is locked', images: [],
    }))
    // Başarı ekranında referans numarası görünür (mesaj + vurgulu kod)
    expect((await within(dialog).findAllByText(/LIR-2026-000042/)).length).toBeGreaterThan(0)
  })

  it('sorun bildir: 429 → net oran mesajı gösterilir; "Detay gör" teknik hatayı açar', async () => {
    const { api } = await import('../api/client')
    const { within } = await import('./test-utils.jsx')
    api.sendLoginHelp.mockResolvedValueOnce({
      success: false, status: 429, error: 'Çok fazla bildirim gönderildi — lütfen daha sonra tekrar deneyin.',
    })
    render(<Login onLogin={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /report it to the system administrator/i }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText(/username/i), { target: { value: 'N1' } })
    fireEvent.change(within(dialog).getByLabelText(/your email/i), { target: { value: 'u@x.com' } })
    fireEvent.change(within(dialog).getByPlaceholderText(/describe the issue in detail/i), { target: { value: 'x' } })
    fireEvent.click(within(dialog).getByRole('button', { name: /^send report$/i }))

    // Net sebep (oran limiti) — HTTP kodu değil, kullanıcı-dostu açıklama
    expect(await within(dialog).findByText(/too many reports/i)).toBeInTheDocument()
    // "Detay gör" → teknik hata (HTTP 429 + sunucu mesajı)
    fireEvent.click(within(dialog).getByRole('button', { name: /show details/i }))
    expect(within(dialog).getByText(/HTTP 429/)).toBeInTheDocument()
  })

  it('sorun bildir: ağ hatası → net "sunucuya ulaşılamadı" mesajı gösterilir', async () => {
    const { api } = await import('../api/client')
    const { within } = await import('./test-utils.jsx')
    api.sendLoginHelp.mockResolvedValueOnce({ success: false, networkError: true, error: 'Failed to fetch' })
    render(<Login onLogin={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /report it to the system administrator/i }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText(/username/i), { target: { value: 'N1' } })
    fireEvent.change(within(dialog).getByLabelText(/your email/i), { target: { value: 'u@x.com' } })
    fireEvent.change(within(dialog).getByPlaceholderText(/describe the issue in detail/i), { target: { value: 'x' } })
    fireEvent.click(within(dialog).getByRole('button', { name: /^send report$/i }))

    expect(await within(dialog).findByText(/could not reach the server/i)).toBeInTheDocument()
  })

  it('sorun bildir: 500 → net "sunucu hatası" mesajı gösterilir', async () => {
    const { api } = await import('../api/client')
    const { within } = await import('./test-utils.jsx')
    api.sendLoginHelp.mockResolvedValueOnce({ success: false, status: 500, error: 'Internal Server Error' })
    render(<Login onLogin={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /report it to the system administrator/i }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText(/username/i), { target: { value: 'N1' } })
    fireEvent.change(within(dialog).getByLabelText(/your email/i), { target: { value: 'u@x.com' } })
    fireEvent.change(within(dialog).getByPlaceholderText(/describe the issue in detail/i), { target: { value: 'x' } })
    fireEvent.click(within(dialog).getByRole('button', { name: /^send report$/i }))

    expect(await within(dialog).findByText(/a server error occurred/i)).toBeInTheDocument()
  })
})
