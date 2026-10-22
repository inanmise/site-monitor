import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * Çıkış ağ hatasında da tamamlanır (2026-10-22, UIM-1) ve sekmeler arası kimlik değişimi yakalanır (UIM-2).
 *  - Hareketsizlik çıkışında / elle "Çıkış yap"ta `/logout` reddederse (uykudan uyanış, Wi-Fi kopması) istemci oturumu YİNE
 *    kapanır: giriş formu açılır, `sm.logout.pending` bırakılır, kullanıcıya söylenir. Eskiden temizlik hiç koşmuyor, uygulama
 *    verisiyle ekranda kalıyordu (sunucu oturumu 24 sa geçerli).
 *  - Açılışta işaret varsa `/logout` `/me`'den ÖNCE çağrılır; başarıda işaret silinir, başarısızsa /me hiç sorulmaz.
 *  - Öteki sekmede başka kullanıcı girdi (`sm.storage.owner` değişti) → bu sekme yeniden yüklenir; sahip silindi → hemen yoklama;
 *    sahip silindi + çevrimdışı çıkış işareti → bu sekmede de yerel çıkış.
 *  - Süren "Şimdi Kontrol Et" koşusu çıkışta durur (UIM-26): kalan alan adlarına istek gitmez.
 */
const fx = vi.hoisted(() => ({
  order: [], logout: null, me: null, checks: [], holdChecks: false, pendingChecks: [], ping: 0, idle: false,
}))

vi.mock('../utils/accountInactive.js', async (importOriginal) => {
  const mod = await importOriginal()
  return { ...mod, assignLocation: vi.fn() }
})

vi.mock('../api/client', () => {
  const ME = () => ({
    success: true, username: 'kisi-a', system_role: 'ADMIN', global_admin: true, tour: { status: 'dismissed' },
    ...(fx.idle ? { inactivity_minutes: 0.05, inactivity_warn_seconds: 2 } : {}),
  })
  const overrides = {
    getMe: () => { fx.order.push('me'); return fx.me ? fx.me() : Promise.resolve(ME()) },
    login: () => Promise.resolve(ME()),
    logout: () => { fx.order.push('logout'); return fx.logout ? fx.logout() : Promise.resolve({ success: true }) },
    sessionPing: () => { fx.ping++; return Promise.resolve({ success: true }) },
    // 9 alan adı > eşzamanlılık sınırı (6): çıkış anında kuyrukta bekleyenler kalır
    getCertificates: () => Promise.resolve({ success: true, timestamp: '2026-10-22T08:00:00', data: 'abcdefghi'.split('').map((c) => (
      { domain: `${c}.example.com`, status: 'valid', warning: false, days_remaining: 90, alert_level: 'valid', team_id: 1, team_name: 'Takım A' })) }),
    checkDomain: (d) => {
      fx.checks.push(d)
      if (!fx.holdChecks) return Promise.resolve({ success: true, data: { status: 'valid' } })
      return new Promise((resolve) => fx.pendingChecks.push(() => resolve({ success: true, data: { status: 'valid' } })))
    },
  }
  function deepMock(ov = {}) {
    const cache = new Map()
    return new Proxy(function () {}, {
      get(_t, key) {
        if (key === 'then') return undefined
        if (typeof key !== 'string') return undefined
        if (key in ov && typeof ov[key] === 'function') return ov[key]
        if (!cache.has(key)) cache.set(key, deepMock(key in ov ? ov[key] : {}))
        return cache.get(key)
      },
      apply() { return Promise.resolve({ success: true, data: [] }) },
    })
  }
  return { api: deepMock(overrides), formatDate: (v) => String(v ?? ''), formatDateSec: (v) => String(v ?? '') }
})

import App from '../App.jsx'
import './appLazyWarmup.js'
import { assignLocation } from '../utils/accountInactive.js'
import { OWNER_KEY } from '../utils/personalStorage.js'
import { PENDING_LOGOUT_KEY } from '../utils/pendingLogout.js'

const loginForm = () => document.getElementById('lp-user')
const shell = () => screen.findByText(/^v\d+\./, {}, { timeout: 8000 })

async function manualLogout(container) {
  pressMenuTrigger(container.querySelector('[data-tour="nav-user"]'))
  fireEvent.click(document.querySelector('[data-slot="user-menu-logout"]'))
  const dlg = await screen.findByRole('alertdialog').catch(() => screen.findByRole('dialog'))
  fireEvent.click(within(dlg).getAllByRole('button', { name: /^(Logout|Çıkış Yap)$/ }).at(-1))
}

describe('App — çıkış ağ hatasında da tamamlanır (UIM-1)', () => {
  beforeEach(() => {
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    Object.assign(fx, { order: [], logout: null, me: null, checks: [], holdChecks: false, pendingChecks: [], ping: 0, idle: false })
    vi.mocked(assignLocation).mockClear()
    window.history.replaceState({}, '', '/')
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('elle çıkış: /logout ağ hatasıyla reddederse giriş formu YİNE açılır, bekleyen çıkış işareti bırakılır', async () => {
    fx.logout = () => Promise.reject(new TypeError('Failed to fetch'))
    const { container } = render(<App />)
    await shell()
    await manualLogout(container)
    await waitFor(() => expect(loginForm()).not.toBeNull(), { timeout: 5000 })
    expect(localStorage.getItem(PENDING_LOGOUT_KEY)).toBeTruthy()
    expect(localStorage.getItem(OWNER_KEY)).toBeNull()   // kişisel kayıtlar da temizlendi
    expect(await screen.findByText(/server could not be reached/i)).toBeInTheDocument()
  })

  it('elle çıkış: /logout yanıtsız kalsa da (asılı istek) ≤ 3 sn sonra istemci oturumu kapanır', async () => {
    fx.logout = () => new Promise(() => {})
    const { container } = render(<App />)
    await shell()
    await manualLogout(container)
    await waitFor(() => expect(loginForm()).not.toBeNull(), { timeout: 6000 })
    expect(localStorage.getItem(PENDING_LOGOUT_KEY)).toBeTruthy()
  }, 15_000)

  it('başarılı çıkışta işaret bırakılmaz (bugünkü davranış)', async () => {
    const { container } = render(<App />)
    await shell()
    await manualLogout(container)
    await waitFor(() => expect(loginForm()).not.toBeNull(), { timeout: 5000 })
    expect(localStorage.getItem(PENDING_LOGOUT_KEY)).toBeNull()
  })

  it('hareketsizlik çıkışı: /logout reddederse de giriş formu açılır ve işaret bırakılır', async () => {
    fx.idle = true
    fx.logout = () => Promise.reject(new TypeError('Failed to fetch'))
    render(<App />)
    await shell()
    await waitFor(() => expect(loginForm()).not.toBeNull(), { timeout: 8000 })
    expect(fx.order).toContain('logout')
    expect(localStorage.getItem(PENDING_LOGOUT_KEY)).toBeTruthy()
  }, 20_000)

  it('açılışta bekleyen çıkış: /logout /me\'den ÖNCE çağrılır; başarıda işaret silinir', async () => {
    localStorage.setItem(PENDING_LOGOUT_KEY, 'eski-jeton')
    fx.me = () => Promise.resolve(null)   // sunucu oturumu kapandı → /me 401 (request() null döner)
    render(<App />)
    await waitFor(() => expect(loginForm()).not.toBeNull(), { timeout: 5000 })
    expect(fx.order.slice(0, 2)).toEqual(['logout', 'me'])
    expect(localStorage.getItem(PENDING_LOGOUT_KEY)).toBeNull()
  })

  it('açılışta bekleyen çıkış yine kapanamazsa /me SORULMAZ — eski oturum çerezle geri gelmez', async () => {
    localStorage.setItem(PENDING_LOGOUT_KEY, 'eski-jeton')
    fx.logout = () => Promise.reject(new TypeError('Failed to fetch'))
    render(<App />)
    await waitFor(() => expect(loginForm()).not.toBeNull(), { timeout: 5000 })
    expect(fx.order).not.toContain('me')
    expect(localStorage.getItem(PENDING_LOGOUT_KEY)).toBe('eski-jeton')
  })

  it('çıkışta süren "Şimdi Kontrol Et" koşusu durur — kalan alan adlarına istek gitmez (UIM-26)', async () => {
    fx.holdChecks = true
    const { container } = render(<App />)
    await shell()
    await waitFor(() => expect(document.querySelector('[data-slot="cert-grid"] [data-slot="card"]')).not.toBeNull(), { timeout: 5000 })
    // Koşu: takım seçici → başlat. Eşzamanlılık sınırı 6, üç alan adının hepsi uçuşta
    fireEvent.click(document.querySelector('[data-tour="check-now"]'))
    const picker = await screen.findByRole('dialog', { name: 'Which teams should be checked?' })
    fireEvent.click(within(picker).getByRole('button', { name: 'Start check' }))
    await waitFor(() => expect(fx.checks.length).toBe(6))
    const sent = fx.checks.length
    await manualLogout(container)
    await waitFor(() => expect(loginForm()).not.toBeNull(), { timeout: 5000 })
    await act(async () => { fx.pendingChecks.forEach((r) => r()) })
    await new Promise((r) => setTimeout(r, 50))
    expect(fx.checks.length).toBe(sent)
  })
})

describe('App — sekmeler arası kimlik (UIM-2)', () => {
  beforeEach(() => {
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    Object.assign(fx, { order: [], logout: null, me: null, checks: [], holdChecks: false, pendingChecks: [], ping: 0, idle: false })
    vi.mocked(assignLocation).mockClear()
    window.history.replaceState({}, '', '/')
  })

  const ownerEvent = (newValue) => act(() => {
    if (newValue == null) localStorage.removeItem(OWNER_KEY)
    else localStorage.setItem(OWNER_KEY, newValue)
    window.dispatchEvent(new StorageEvent('storage', { key: OWNER_KEY, newValue, oldValue: 'kisi-a' }))
  })

  it('öteki sekmede BAŞKA kullanıcı girdi → bu sekme yeniden yüklenir (eski kimlikle kalmaz)', async () => {
    render(<App />)
    await shell()
    await ownerEvent('kisi-c')
    expect(assignLocation).toHaveBeenCalledWith('/?session=expired')
  })

  it('aynı kullanıcı yeniden girdi → hiçbir şey olmaz', async () => {
    render(<App />)
    await shell()
    await ownerEvent('kisi-a')
    expect(assignLocation).not.toHaveBeenCalled()
  })

  it('öteki sekmede çıkış (sahip silindi) → hemen oturum yoklaması (401 yolu yönlendirir)', async () => {
    render(<App />)
    await shell()
    const before = fx.ping
    await ownerEvent(null)
    await waitFor(() => expect(fx.ping).toBeGreaterThan(before))
    expect(assignLocation).not.toHaveBeenCalled()
    expect(loginForm()).toBeNull()
  })

  it('öteki sekmede ÇEVRİMDIŞI çıkış (işaret var) → bu sekmede de yerel çıkış', async () => {
    render(<App />)
    await shell()
    localStorage.setItem(PENDING_LOGOUT_KEY, 'baska-sekme')
    await ownerEvent(null)
    await waitFor(() => expect(loginForm()).not.toBeNull(), { timeout: 5000 })
  })
})
