import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor } from './test-utils.jsx'

/**
 * Açılış ön isteği (2026-11-06, performans — PF4 Y3a): `main.jsx` `/api/me` ve `/api/branding`'i ilk çizimden ÖNCE başlatır
 * (saklı dil İngilizce ise sözlük parçası inerken bekleme yok). App (`bootMe`) ve BrandingProvider aynı sözü alır — ağda TEK
 * istek. Bekleyen çıkışta ön istek kullanılmaz (çıkıştan önceki oturum geri gelmez); süre aşımında bayat sayılır.
 */
const fx = vi.hoisted(() => ({ me: [], meCalls: 0, branding: 0, logout: 0 }))

vi.mock('../api/client', () => {
  const overrides = {
    getMe: () => { fx.meCalls++; const next = fx.me.shift(); return Promise.resolve(next ?? { success: false }) },
    getBranding: () => { fx.branding++; return Promise.resolve({ success: true, data: { app_name: 'Marka' } }) },
    logout: () => { fx.logout++; return Promise.resolve({ success: true }) },
    getCertificates: () => Promise.resolve({ success: true, timestamp: '2026-11-06T08:00:00', data: [] }),
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
import { BrandingProvider, useBranding } from '../contexts/BrandingProvider.jsx'
import {
  BOOT_PREFETCH_MAX_AGE_MS, discardBootMe, resetBootPrefetch, startBootPrefetch, takeBootBranding, takeBootMe,
} from '../utils/bootPrefetch.js'
import { PENDING_LOGOUT_KEY, isLogoutPending } from '../utils/pendingLogout.js'

const ME = (username) => ({ success: true, username, system_role: 'ADMIN', global_admin: true, tour: { status: 'dismissed' } })
const loginForm = () => document.getElementById('lp-user')

describe('bootPrefetch — modül', () => {
  beforeEach(() => { resetBootPrefetch(); fx.me = []; fx.meCalls = 0; fx.branding = 0 })
  afterEach(() => { vi.restoreAllMocks(); resetBootPrefetch() })

  it('istekleri BİR kez başlatır; söz yalnız bir kez alınır; ikinci başlatma yeni istek atmaz', async () => {
    fx.me = [ME('ali')]
    startBootPrefetch()
    startBootPrefetch()
    expect(fx.meCalls).toBe(1)
    expect(fx.branding).toBe(1)
    const p = takeBootMe()
    expect(p).not.toBeNull()
    await expect(p).resolves.toMatchObject({ username: 'ali' })
    expect(takeBootMe()).toBeNull()
    expect(takeBootBranding()).not.toBeNull()
    expect(takeBootBranding()).toBeNull()
  })

  it('me: false → oturum isteği başlamaz (bilinmeyen yol / bekleyen çıkış); discard ön sözü bırakır', () => {
    startBootPrefetch({ me: false })
    expect(fx.meCalls).toBe(0)
    expect(takeBootMe()).toBeNull()
    expect(fx.branding).toBe(1)
    startBootPrefetch()
    discardBootMe()
    expect(takeBootMe()).toBeNull()
  })

  it('bayat ön istek alınmaz (açılıştan çok sonra bağlanan bileşen kendi isteğini atar)', () => {
    const now = Date.now()
    const clock = vi.spyOn(Date, 'now').mockReturnValue(now)
    startBootPrefetch()
    clock.mockReturnValue(now + BOOT_PREFETCH_MAX_AGE_MS + 1)
    expect(takeBootMe()).toBeNull()
    expect(takeBootBranding()).toBeNull()
  })
})

describe('bootPrefetch — App ve BrandingProvider aynı sözü alır', () => {
  beforeEach(() => {
    resetBootPrefetch()
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    fx.me = []; fx.meCalls = 0; fx.branding = 0; fx.logout = 0
    window.history.replaceState({}, '', '/')
  })
  afterEach(() => { vi.restoreAllMocks(); resetBootPrefetch(); window.history.replaceState({}, '', '/') })

  it('App: ön istek varken /api/me AĞDA BİR KEZ; oturum açılır', async () => {
    fx.me = [ME('ali')]
    startBootPrefetch({ me: !isLogoutPending() })
    render(<App />)
    await waitFor(() => expect(document.querySelector('[data-slot="sidebar"]') || document.querySelector('[data-sidebar="sidebar"]')).not.toBeNull(), { timeout: 8000 })
    expect(fx.meCalls).toBe(1)
    expect(loginForm()).toBeNull()
  })

  it('BrandingProvider: ön istek varken /api/branding AĞDA BİR KEZ; değer bağlama düşer', async () => {
    startBootPrefetch({ me: false })
    function Probe() { const { get } = useBranding(); return <span data-testid="b">{get('app_name', '-')}</span> }
    const { findByTestId } = render(<BrandingProvider><Probe /></BrandingProvider>)
    expect(await findByTestId('b')).toHaveTextContent('Marka')
    expect(fx.branding).toBe(1)
  })

  it('bekleyen çıkış: ön /api/me (eski oturum) KULLANILMAZ — çıkış kapanır, taze /me sorulur, giriş formu açılır', async () => {
    // Ön istek ESKİ oturumu döndürür (çıkış işareti sonradan görüldü); çıkıştan sonraki taze /me oturumsuz
    fx.me = [ME('eski'), { success: false }]
    startBootPrefetch({ me: true })
    localStorage.setItem(PENDING_LOGOUT_KEY, 'eski-jeton')
    render(<App />)
    await waitFor(() => expect(loginForm()).not.toBeNull(), { timeout: 8000 })
    expect(fx.logout).toBe(1)
    expect(fx.meCalls).toBe(2)   // ön istek (atıldı) + çıkıştan sonra taze istek
  })
})
