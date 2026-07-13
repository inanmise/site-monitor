import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import Login from '../pages/Login.jsx'

// We mock the entire api module so the form's submit handler resolves
// without touching the network. The shape mirrors the real client.
vi.mock('../api/client', () => ({
  api: {
    login: vi.fn(),
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

  it('renders the executive left panel: wordmark, badge, headline, capability bento (8 tiles), hero stats, rings + pulse', () => {
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
    // Alt bölge: hero istatistikler (hardcoded)
    expect(screen.getByText('500+')).toBeDefined()
    expect(screen.getByText('99.9%')).toBeDefined()
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
})
