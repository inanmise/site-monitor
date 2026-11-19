import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'

/**
 * İzleme sayfası "İzlemeler | Alarmlar" görünümü — App düzeyi (2026-11-19, yayın sonrası düzeltme):
 *  - `?tab=http&mal=open` derin bağlantısı oturum okunurken (App `tab` durumu henüz 'dashboard') `mal`'ı YİTİRMEZ —
 *    pano kancası adresin sekmesi de pano değilken yazmaz;
 *  - Sertifika Panosu'nda `alerts.read` izni yoksa Alarmlar seçeneği çizilmez ve özet / liste istenmez (izin App
 *    gövdesinde değil, sağlayıcının ALTINDAN okunur).
 */
const fx = vi.hoisted(() => ({ meDelay: 0, perms: {}, calls: [] }))

vi.mock('../api/client', () => {
  const overrides = {
    getMe: () => new Promise((r) => setTimeout(() => r({ success: true, username: 'ali', system_role: 'USER', global_admin: false,
      team_ids: [5], tour: { status: 'dismissed' } }), fx.meDelay)),
    'me.getPermissions': () => Promise.resolve({ success: true, data: fx.perms }),
    'admin.getAlertTypeSummary': (...a) => { fx.calls.push('type-summary'); return Promise.resolve({ success: true, data: null }) },
    'admin.getAlerts': (...a) => { fx.calls.push('alerts'); return Promise.resolve({ success: true, data: [], total: 0 }) },
  }
  function deepMock(path) {
    const cache = new Map()
    return new Proxy(function () {}, {
      get(_t, key) {
        if (key === 'then') return undefined
        if (typeof key !== 'string') return undefined
        const p = path ? `${path}.${key}` : key
        if (p in overrides) return overrides[p]
        if (!cache.has(key)) cache.set(key, deepMock(p))
        return cache.get(key)
      },
      apply() { return Promise.resolve({ success: true, data: [] }) },
    })
  }
  return {
    api: deepMock(''),
    formatDate: (v) => String(v ?? ''),
    formatDateSec: (v) => String(v ?? ''),
    formatDateOnly: (v) => String(v ?? ''),
    getRecentFailures: () => [],
  }
})

import App from '../App.jsx'
import './appLazyWarmup.js'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const malParam = () => new URLSearchParams(window.location.search).get('mal')

beforeEach(() => {
  localStorage.clear()
  try { sessionStorage.clear() } catch { /* jsdom */ }
  fx.meDelay = 0
  fx.perms = {}
  fx.calls = []
})
afterEach(() => { window.history.replaceState({}, '', '/') })

describe('App — izleme sayfasının Alarmlar görünümü', () => {
  it('?tab=http&mal=open: oturum okunurken (300 ms yazım gecikmesinden uzun) `mal` adreste kalır; sayfa Alarmlar görünümüyle açılır', async () => {
    fx.meDelay = 700
    window.history.replaceState({}, '', '/?tab=http&mal=open')
    render(<App />)
    await sleep(550)
    expect(malParam()).toBe('open')
    await waitFor(() => expect(document.querySelector('[data-slot="monitor-alarms"][data-src="http"]')).not.toBeNull(), { timeout: 8000 })
    expect(malParam()).toBe('open')
  })

  it('Sertifika Panosu: alerts.read izni yoksa Alarmlar seçeneği yok, ?mal=open görünümü kapanır ve alarm isteği sürmez', async () => {
    fx.perms = { 'alerts.read': { view: false }, 'inventory.list': { view: true } }
    window.history.replaceState({}, '', '/?mal=open')
    render(<App />)
    await screen.findByText(/^v\d+\./)
    await waitFor(() => expect(malParam()).toBeNull(), { timeout: 3000 })
    expect(document.querySelector('[data-slot="monitor-alarms"]')).toBeNull()
    expect(document.querySelector('[data-slot="monitor-view-switch"]')).toBeNull()
    // Anlık görüntü okunmadan önce en çok bir deneme (izleme sayfalarıyla aynı kabul edilen kural); sonra istek yok
    const n = fx.calls.length
    expect(n).toBeLessThanOrEqual(2)
    await sleep(300)
    expect(fx.calls.length).toBe(n)
  })

  it('Sertifika Panosu: izin varsa (ya da kaynak anlık görüntüde yoksa) Alarmlar seçeneği çizilir', async () => {
    fx.perms = { 'alerts.read': { view: true } }
    window.history.replaceState({}, '', '/')
    render(<App />)
    await screen.findByText(/^v\d+\./)
    await waitFor(() => expect(document.querySelector('[data-slot="monitor-view-switch"]')).not.toBeNull())
  })
})
