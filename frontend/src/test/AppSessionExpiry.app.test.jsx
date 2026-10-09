import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen } from './test-utils.jsx'

/**
 * Oturum düşüşü yönlendirme sigortası — uygulama düzeyi (2026-10-09): `api/client.js` 60 sn içinde 2 kez
 * /?session=expired'a yönlendirdiyse üçüncüde sayfayı yeniden yüklemez, `sm:session-expired` olayı yayar; uygulama giriş
 * formunu YERİNDE, yönlendirmeyle aynı "oturum süresi doldu" bildirimiyle açar (yükle → 401 → yükle döngüsü yok).
 */

vi.mock('../utils/accountInactive.js', async (importOriginal) => {
  const mod = await importOriginal()
  return { ...mod, assignLocation: vi.fn() }
})

vi.mock('../api/client', () => {
  const overrides = {
    getMe: vi.fn(),
    login: vi.fn(),
    logout: vi.fn().mockResolvedValue({ success: true }),
  }
  function deepMock() {
    const cache = new Map()
    return new Proxy(function () {}, {
      get(_t, key) {
        if (key === 'then') return undefined
        if (typeof key !== 'string') return undefined
        if (key in overrides) return overrides[key]
        if (!cache.has(key)) cache.set(key, deepMock())
        return cache.get(key)
      },
      apply() { return Promise.resolve({ success: true, data: [] }) },
    })
  }
  return {
    api: deepMock(),
    formatDate: (v) => String(v ?? ''),
    formatDateSec: (v) => String(v ?? ''),
  }
})

import App from '../App.jsx'
import './appLazyWarmup.js'
import { api } from '../api/client'
import { assignLocation } from '../utils/accountInactive.js'
import { signalSessionExpired } from '../utils/sessionExpiry.js'

describe('App — oturum düşüşü yönlendirme sigortası', () => {
  beforeEach(() => {
    vi.mocked(assignLocation).mockClear()
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    window.history.replaceState({}, '', '/')
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('oturum açıkken olay → giriş formu yerinde, "oturum süresi doldu" bildirimiyle; yönlendirme ve /logout yok', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'ALICE', system_role: 'USER', team_ids: [] })
    render(<App />)
    await screen.findByText(/^v\d+\./)                  // girişli kabuk (Nav sürüm rozeti)
    expect(screen.queryByLabelText(/username/i)).toBeNull()

    act(() => { signalSessionExpired() })

    expect(await screen.findByLabelText(/username/i)).toBeDefined()
    expect(screen.getByText(/session has expired/i)).toBeDefined()
    expect(assignLocation).not.toHaveBeenCalled()
    expect(api.logout).not.toHaveBeenCalled()
  })

  it('oturum yokken olay hiçbir şey değiştirmez (giriş formu zaten açık, bildirim eklenmez)', async () => {
    api.getMe.mockResolvedValue({})
    render(<App />)
    expect(await screen.findByLabelText(/username/i)).toBeDefined()
    act(() => { signalSessionExpired() })
    expect(screen.queryByText(/session has expired/i)).toBeNull()
  })
})
