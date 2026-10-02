import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from './test-utils.jsx'

/**
 * Sistem Bakım Modu — uygulama düzeyi (2026-10-02, kullanıcı kararı):
 *  - oturum açıkken bakım sinyali (401 MAINTENANCE) → kapatılamayan pencere; "Şimdi çıkış yap" → çıkış + /?session=maintenance;
 *  - açılışta /me 401 MAINTENANCE (çerez bakımda reddedildi) → pencere YOK, giriş sayfası bakım kartıyla;
 *  - /?session=maintenance → giriş sayfasında "oturumunuz bakım nedeniyle sonlandırıldı" kartı ("oturum süresi doldu" değil);
 *  - /me + yoklama EK bloğu → duyuru şeridi (kullanıcı) / kalıcı admin şeridi (global yönetici);
 *  - pasif hesap davranışı bozulmaz.
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
    sessionPing: vi.fn().mockResolvedValue({ success: true }),
    getSystemMaintenanceStatus: vi.fn().mockResolvedValue({ success: true, data: { state: 'none', server_now: new Date().toISOString() } }),
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
  return { api: deepMock(), formatDate: (v) => String(v ?? ''), formatDateSec: (v) => String(v ?? '') }
})

import App from '../App.jsx'
import './appLazyWarmup.js'
import { api } from '../api/client'
import { assignLocation, resetAccountInactiveSignal } from '../utils/accountInactive.js'
import { resetMaintenanceSignal, signalMaintenance } from '../utils/systemMaintenance.js'

const iso = (msFromNow) => new Date(Date.now() + msFromNow).toISOString()
const ME = (extra = {}) => ({ success: true, username: 'ALICE', system_role: 'USER', team_ids: [], ...extra })

describe('App — sistem bakımı', () => {
  beforeEach(() => {
    resetMaintenanceSignal()
    resetAccountInactiveSignal()
    vi.mocked(assignLocation).mockClear()
    vi.mocked(api.logout).mockClear()
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    window.history.replaceState({}, '', '/')
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('oturum AÇIKKEN bakım sinyali → kapatılamayan pencere (10 sn); "Şimdi çıkış yap" → çıkış + /?session=maintenance', async () => {
    api.getMe.mockResolvedValue(ME())
    render(<App />)
    await screen.findByText(/^v\d+\./)
    act(() => { signalMaintenance({ state: 'active', start_at: iso(-60_000), end_at: iso(3_600_000) }) })
    const dlg = await screen.findByRole('alertdialog')
    expect(dlg).toHaveAttribute('data-mode', 'ended')
    expect(dlg).toHaveTextContent(/The system is in maintenance/)
    fireEvent.click(document.querySelector('[data-slot="maint-dialog-logout"]'))
    await waitFor(() => expect(assignLocation).toHaveBeenCalledWith('/?session=maintenance'))
    expect(api.logout).toHaveBeenCalled()
    expect(sessionStorage.getItem('sm.session.active')).toBeNull()
  })

  it('AÇILIŞTA /me 401 MAINTENANCE → pencere YOK, giriş sayfası bakım kartıyla', async () => {
    api.getMe.mockImplementation(async () => {
      signalMaintenance({ state: 'active', start_at: iso(-60_000), end_at: iso(3_600_000) })
      return null
    })
    render(<App />)
    expect(await screen.findByLabelText(/username/i)).toBeDefined()
    await waitFor(() => expect(document.querySelector('[data-slot="login-maintenance"]')).not.toBeNull())
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(screen.queryByText(/session has expired/i)).toBeNull()
  })

  it('/?session=maintenance → giriş sayfasında "oturumunuz bakım nedeniyle sonlandırıldı" kartı', async () => {
    window.history.replaceState({}, '', '/?session=maintenance')
    api.getMe.mockResolvedValue({})
    render(<App />)
    expect(await screen.findByLabelText(/username/i)).toBeDefined()
    const card = document.querySelector('[data-slot="login-maintenance"]')
    expect(card).toHaveAttribute('data-state', 'ended')
    expect(card).toHaveTextContent(/closed for system maintenance/)
    expect(screen.queryByText(/session has expired/i)).toBeNull()
  })

  it('/me EK bloğu: duyuru penceresinde kullanıcıya duyuru şeridi; kapatılınca kalkar', async () => {
    api.getMe.mockResolvedValue(ME({
      server_now: iso(0),
      maintenance: { state: 'announced', id: 4, revision: 1, warn_minutes: 10, announce_hours: 24,
        start_at: iso(5 * 3_600_000), end_at: iso(6 * 3_600_000), message_tr: null, message_en: 'DB upgrade', contact: 'IT desk' },
    }))
    render(<App />)
    const strip = await screen.findByText((_, el) => el?.getAttribute?.('data-slot') === 'maint-announce')
    expect(strip).toHaveTextContent(/planned maintenance/i)
    expect(strip).toHaveTextContent(/DB upgrade/)
    fireEvent.click(strip.querySelector('[data-slot="maint-dismiss"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="maint-announce"]')).toBeNull())
  })

  it('global yönetici bakımda: kalıcı admin şeridi (Uzat · Hemen bitir), pencere yok', async () => {
    api.getMe.mockResolvedValue(ME({
      username: 'admin', system_role: 'ADMIN', global_admin: true, server_now: iso(0),
      maintenance: { state: 'active', id: 9, revision: 1, warn_minutes: 10, announce_hours: 0,
        start_at: iso(-600_000), end_at: iso(3_000_000) },
    }))
    render(<App />)
    const strip = await screen.findByText((_, el) => el?.getAttribute?.('data-slot') === 'maint-admin')
    expect(strip.querySelector('[data-slot="maint-extend"]')).not.toBeNull()
    expect(strip.querySelector('[data-slot="maint-end-now"]')).not.toBeNull()
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })
})
