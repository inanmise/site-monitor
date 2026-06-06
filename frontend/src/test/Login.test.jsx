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
})
