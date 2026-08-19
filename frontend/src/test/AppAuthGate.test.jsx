import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen } from './test-utils.jsx'

// Mock the API so getMe controls the auth gate and nothing hits the network.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    getMe: vi.fn().mockResolvedValue({}),   // no active session → user stays null
    logout: vi.fn().mockResolvedValue({}),
    login: vi.fn(),
    checkDomain: vi.fn(),
  }),
  formatDate: (v) => String(v ?? ''),
}))

import App from '../App.jsx'
import { api } from '../api/client'

describe('App auth gate — logged out shows the login form', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.getMe.mockResolvedValue({})
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
  })

  afterEach(() => {
    // Reset the URL so tests don't leak the query param into each other.
    window.history.replaceState({}, '', '/')
  })

  it('when logged out, renders the login form directly — no landing page', async () => {
    window.history.replaceState({}, '', '/')

    render(<App />)

    // The login form is the first screen…
    expect(await screen.findByLabelText(/username/i)).toBeDefined()
    // …with no session-expired notice by default.
    expect(screen.queryByText(/session has expired/i)).toBeNull()
  })

  it('with ?session=expired shows the login form WITH the expired notice (AUTH-1)', async () => {
    window.history.replaceState({}, '', '/?session=expired')

    render(<App />)

    expect(await screen.findByLabelText(/username/i)).toBeDefined()
    expect(screen.getByText(/session has expired/i)).toBeDefined()
  })
})
