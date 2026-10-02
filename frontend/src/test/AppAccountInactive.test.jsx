import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from './test-utils.jsx'

/**
 * Pasif hesap — uygulama düzeyi (2026-10-02, kullanıcı kararı):
 *  - oturum açıkken sinyal → bloklayan "Hesabınız pasife alındı" penceresi; "Şimdi çıkış yap" (ya da 0) → istemci
 *    temizliği + /?session=inactive (API çağrısı YOK);
 *  - açılışta (/me 401 ACCOUNT_INACTIVE — ör. pasif hesabın remember-me çerezi) → pencere DEĞİL, giriş sayfası + pasif bildirimi;
 *  - /?session=inactive → giriş sayfasında pasif bildirimi ("oturum süresi doldu" değil).
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
import { REMEMBER_KEY } from '../pages/Login'
import { assignLocation, resetAccountInactiveSignal, signalAccountInactive } from '../utils/accountInactive.js'

describe('App — pasif hesap sinyali', () => {
  beforeEach(() => {
    resetAccountInactiveSignal()
    vi.mocked(assignLocation).mockClear()
    vi.mocked(api.logout).mockClear()
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    window.history.replaceState({}, '', '/')
  })
  afterEach(() => {
    window.history.replaceState({}, '', '/')
  })

  it('oturum AÇIKKEN sinyal → bloklayan pencere; "Şimdi çıkış yap" → temizlik + /?session=inactive, API çağrısı yok', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'ALICE', system_role: 'USER', team_ids: [] })
    localStorage.setItem(REMEMBER_KEY, 'ALICE')
    render(<App />)
    await screen.findByText(/^v\d+\./)   // girişli kabuk (Nav sürüm rozeti)
    expect(sessionStorage.getItem('sm.session.active')).toBe('1')

    act(() => { signalAccountInactive() })

    const dlg = await screen.findByRole('alertdialog')
    expect(dlg).toHaveTextContent(/deactivated/i)
    expect(document.querySelector('[data-slot="account-inactive-countdown"]')).toHaveTextContent('10')

    fireEvent.click(document.querySelector('[data-slot="account-inactive-logout"]'))

    expect(assignLocation).toHaveBeenCalledWith('/?session=inactive')
    expect(localStorage.getItem(REMEMBER_KEY)).toBeNull()
    expect(sessionStorage.getItem('sm.session.active')).toBeNull()
    expect(api.logout).not.toHaveBeenCalled()
  })

  it('AÇILIŞTA /me 401 ACCOUNT_INACTIVE (pasif hesabın çerezi) → pencere YOK, giriş sayfası pasif bildirimiyle', async () => {
    api.getMe.mockImplementation(async () => { signalAccountInactive(); return null })

    render(<App />)

    expect(await screen.findByLabelText(/username/i)).toBeDefined()
    await waitFor(() => expect(document.querySelector('[data-slot="login-account-inactive"]')).not.toBeNull())
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(screen.queryByText(/session has expired/i)).toBeNull()
    expect(assignLocation).not.toHaveBeenCalled()
  })

  it('/?session=inactive → giriş sayfasında pasif bildirimi ("oturum süresi doldu" değil)', async () => {
    window.history.replaceState({}, '', '/?session=inactive')
    api.getMe.mockResolvedValue({})

    render(<App />)

    expect(await screen.findByLabelText(/username/i)).toBeDefined()
    expect(document.querySelector('[data-slot="login-account-inactive"]')).toHaveTextContent(/deactivated/i)
    expect(screen.queryByText(/session has expired/i)).toBeNull()
  })
})
