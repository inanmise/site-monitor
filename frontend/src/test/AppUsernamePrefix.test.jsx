import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from './test-utils.jsx'

/**
 * Kullanıcı adı ön eki — uygulama düzeyi (2026-10-21). Pasif hesap deseninin aynısı:
 *  - oturum açıkken sinyal (401 USERNAME_PREFIX_NOT_ALLOWED) → kapatılamaz geri sayımlı pencere, izinli ön ekleri söyler;
 *    "Şimdi çıkış yap" (ya da 0) → istemci temizliği + /?session=prefix (API çağrısı YOK);
 *  - açılışta (/me 401 — ör. kurala uymayan hesabın remember-me çerezi) → pencere DEĞİL, giriş sayfası bildirimiyle;
 *  - /?session=prefix → giriş sayfasında ön ek bildirimi ("oturum süresi doldu" değil).
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
import { assignLocation } from '../utils/accountInactive.js'
import { resetUsernamePrefixSignal, signalUsernamePrefix } from '../utils/usernamePrefix.js'

describe('App — kullanıcı adı ön eki sinyali', () => {
  beforeEach(() => {
    resetUsernamePrefixSignal()
    vi.mocked(assignLocation).mockClear()
    vi.mocked(api.logout).mockClear()
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    window.history.replaceState({}, '', '/')
  })
  afterEach(() => {
    window.history.replaceState({}, '', '/')
  })

  it('oturum AÇIKKEN sinyal → kapatılamaz pencere (ön ekleri söyler); "Şimdi çıkış yap" → temizlik + /?session=prefix, API yok', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'MP1', system_role: 'USER', team_ids: [] })
    localStorage.setItem(REMEMBER_KEY, 'MP1')
    render(<App />)
    await screen.findByText(/^v\d+\./)

    act(() => { signalUsernamePrefix(['N']) })

    const dlg = await waitFor(() => {
      const d = document.querySelector('[data-slot="username-prefix-dialog"]')
      expect(d).not.toBeNull()
      return d
    })
    expect(dlg).toHaveTextContent(/start with N|N ile başlayan/)
    expect(document.querySelector('[data-slot="account-inactive-dialog"]')).toBeNull()
    expect(document.querySelector('[data-slot="username-prefix-countdown"]')).toHaveTextContent('10')

    fireEvent.click(document.querySelector('[data-slot="username-prefix-logout"]'))

    expect(assignLocation).toHaveBeenCalledWith('/?session=prefix')
    expect(localStorage.getItem(REMEMBER_KEY)).toBeNull()
    expect(sessionStorage.getItem('sm.session.active')).toBeNull()
    expect(api.logout).not.toHaveBeenCalled()
  })

  it('AÇILIŞTA /me 401 USERNAME_PREFIX (remember-me çerezi reddedildi) → pencere YOK, giriş sayfası bildirimiyle', async () => {
    api.getMe.mockImplementation(async () => { signalUsernamePrefix(['N']); return null })
    render(<App />)
    expect(await screen.findByLabelText(/username/i)).toBeDefined()
    await waitFor(() => expect(document.querySelector('[data-slot="login-username-prefix"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="username-prefix-dialog"]')).toBeNull()
    expect(screen.queryByText(/session has expired/i)).toBeNull()
    expect(assignLocation).not.toHaveBeenCalled()
  })

  it('inceleme 2026-10-21: AÇILIŞTA MP remember-me reddi → N hesabıyla giriş → kural SONRA keserse pencere + /?session=prefix yine çalışır (sinyal girişte sıfırlanır)', async () => {
    api.getMe.mockResolvedValue({ success: true, username: 'N123', system_role: 'USER', team_ids: [] })
    api.getMe.mockImplementationOnce(async () => { signalUsernamePrefix(['N']); return null })
    api.login.mockResolvedValue({ success: true, username: 'N123', system_role: 'USER', team_ids: [] })
    render(<App />)
    await waitFor(() => expect(document.querySelector('[data-slot="login-username-prefix"]')).not.toBeNull())

    const userInput = document.getElementById('lp-user')
    fireEvent.change(userInput, { target: { value: 'N123' } })
    fireEvent.change(document.getElementById('lp-pass'), { target: { value: 'pw' } })
    fireEvent.submit(userInput.closest('form'))
    await screen.findByText(/^v\d+\./)
    expect(document.querySelector('[data-slot="username-prefix-dialog"]')).toBeNull()

    // kural sonradan N'yi de dışarıda bırakacak biçimde değişti → aynı sayfa ömründe yeni kesim
    let first
    act(() => { first = signalUsernamePrefix(['T']) })
    expect(first).toBe(true)
    const dlg = await waitFor(() => {
      const d = document.querySelector('[data-slot="username-prefix-dialog"]')
      expect(d).not.toBeNull()
      return d
    })
    expect(dlg).toHaveTextContent(/start with T|T ile başlayan/)
    fireEvent.click(document.querySelector('[data-slot="username-prefix-logout"]'))
    expect(assignLocation).toHaveBeenCalledWith('/?session=prefix')
  })

  it('/?session=prefix → giriş sayfasında ön ek bildirimi ("oturum süresi doldu" değil)', async () => {
    window.history.replaceState({}, '', '/?session=prefix')
    api.getMe.mockResolvedValue({})
    render(<App />)
    expect(await screen.findByLabelText(/username/i)).toBeDefined()
    expect(document.querySelector('[data-slot="login-username-prefix"]')).not.toBeNull()
    expect(screen.queryByText(/session has expired/i)).toBeNull()
  })
})
