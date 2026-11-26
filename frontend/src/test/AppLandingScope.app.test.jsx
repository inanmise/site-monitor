import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act } from './test-utils.jsx'

/**
 * Açılış sekmesi seçenekleri menü kuralını izler (H paketi 2026-11-26, deneyim 12):
 *  - Yönetici Özeti kapsamlı yönetici (rol ADMIN, global değil) için de açılır (menüde görünüyordu, seçicide yoktu);
 *  - Kanal Teknik Hata Raporları `scope.channel_reports` false ise açılmaz (Pano'da kalınır, kayıt silinmez); izin
 *    okunana dek karar EN ÇOK LANDING_SCOPE_WAIT_MS BEKLER, okununca görünürse açılır; süre dolar da okunamazsa
 *    açılış sekmesi uygulanmaz (Pano) — kullanıcı geç taşınmaz (inceleme bulgusu 6);
 *  - kullanıcı beklerken kendisi gezindiyse (Pano'ya geri dönse de) karar hiç uygulanmaz;
 *  - görünmeyen kayıtlı sekme → Pano.
 * Bu dosyada bekleme 1 sn'ye indirilir (gerçek değer 5 sn — landingTabs.test.js sabitler).
 */
const state = { me: null, prefs: {}, perms: null }
function deferred() { let resolve; const promise = new Promise((r) => { resolve = r }); return { promise, resolve } }

vi.mock('../api/client', () => {
  const overrides = {
    getMe: () => Promise.resolve(state.me),
    'me.getPreferences': () => Promise.resolve({ success: true, prefs: state.prefs }),
    'me.savePreferences': (patch) => Promise.resolve({ success: true, prefs: { ...state.prefs, ...patch } }),
    'me.getPermissions': () => (state.perms ? state.perms.promise : Promise.resolve({ success: true, data: {}, scope: null })),
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

vi.mock('../utils/landingTabs.js', async (importOriginal) => ({ ...(await importOriginal()), LANDING_SCOPE_WAIT_MS: 1000 }))

import App from '../App.jsx'
import './appLazyWarmup.js'

const ME = (extra = {}) => ({ success: true, username: 'ali', system_role: 'USER', global_admin: false, team_ids: [], ...extra })
const tabParam = () => new URLSearchParams(window.location.search).get('tab')

beforeEach(() => {
  localStorage.clear()
  try { sessionStorage.clear() } catch { /* jsdom */ }
  state.me = ME()
  state.prefs = {}
  state.perms = null
})
afterEach(() => { window.history.replaceState({}, '', '/') })

async function boot(url = '/') {
  window.history.replaceState({}, '', url)
  const r = render(<App />)
  await screen.findByText(/^v\d+\./)
  return r
}
const settle = () => new Promise((r) => setTimeout(r, 200))

describe('App — açılış sekmesi menü kuralıyla', () => {
  it('kapsamlı yönetici (ADMIN, global değil): kayıtlı Yönetici Özeti açılır', async () => {
    state.me = ME({ system_role: 'ADMIN', global_admin: false, view_team_ids: [5], manage_team_ids: [5] })
    state.prefs = { landingTab: 'executive' }
    await boot()
    await waitFor(() => expect(tabParam()).toBe('executive'))
  })

  it('USER: kayıtlı Yönetici Özeti görünmez → Pano', async () => {
    state.prefs = { landingTab: 'executive' }
    await boot()
    await settle()
    expect(tabParam() ?? 'dashboard').toBe('dashboard')
  })

  it('Kanal raporları kapsamı false → Pano', async () => {
    state.perms = deferred()
    state.perms.resolve({ success: true, data: {}, scope: { channel_reports: false } })
    state.prefs = { landingTab: 'channelreports' }
    await boot()
    await settle()
    expect(tabParam() ?? 'dashboard').toBe('dashboard')
  })

  it('izin okunana dek karar bekler; okununca görünürse Kanal raporları açılır', async () => {
    state.perms = deferred()
    state.prefs = { landingTab: 'channelreports' }
    await boot()
    await settle()
    expect(tabParam() ?? 'dashboard').toBe('dashboard')   // henüz karar yok — Pano'ya da DÜŞÜLMEDİ, bekleniyor
    await act(async () => { state.perms.resolve({ success: true, data: {}, scope: { channel_reports: true } }) })
    await waitFor(() => expect(tabParam()).toBe('channelreports'))
  })

  it('izin okuması başarısız → bilinmiyor: sınırda (bu dosyada 1 sn) Pano kalır, açılış sekmesi uygulanmaz', async () => {
    state.perms = deferred()
    state.prefs = { landingTab: 'channelreports' }
    await boot()
    await act(async () => { state.perms.resolve({ success: false }) })
    await new Promise((r) => setTimeout(r, 1400))
    expect(tabParam() ?? 'dashboard').toBe('dashboard')
  }, 15_000)

  it('beklerken kullanıcı başka sekmeye geçtiyse izin gelince açılış uygulanmaz', async () => {
    state.perms = deferred()
    state.prefs = { landingTab: 'channelreports' }
    await boot()
    await settle()
    act(() => { window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'help' } })) })
    await waitFor(() => expect(tabParam()).toBe('help'))
    await act(async () => { state.perms.resolve({ success: true, data: {}, scope: { channel_reports: true } }) })
    await settle()
    expect(tabParam()).toBe('help')
  })

  it('beklerken kullanıcı başka sekmeye gidip Pano\'ya DÖNSE de izin gelince açılış uygulanmaz', async () => {
    state.perms = deferred()
    state.prefs = { landingTab: 'channelreports' }
    await boot()
    await settle()
    act(() => { window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'help' } })) })
    await waitFor(() => expect(tabParam()).toBe('help'))
    act(() => { window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'dashboard' } })) })
    await waitFor(() => expect(tabParam()).toBe('dashboard'))
    await act(async () => { state.perms.resolve({ success: true, data: {}, scope: { channel_reports: true } }) })
    await settle()
    expect(tabParam()).toBe('dashboard')
  })

  it('inceleme bulgusu 6: izin süre sınırında okunamazsa (asılı) açılış sekmesi UYGULANMAZ, Pano kalır; geç gelen izin taşımaz', async () => {
    state.perms = deferred()   // hiç çözülmüyor (asılı istek)
    state.prefs = { landingTab: 'channelreports' }
    await boot()
    await new Promise((r) => setTimeout(r, 1400))   // sınır (bu dosyada 1 sn) doldu
    expect(tabParam() ?? 'dashboard').toBe('dashboard')
    // İzin GEÇ gelir ve görünür der: karar zaten verildi — kullanıcı taşınmaz
    await act(async () => { state.perms.resolve({ success: true, data: {}, scope: { channel_reports: true } }) })
    await settle()
    expect(tabParam() ?? 'dashboard').toBe('dashboard')
  }, 15_000)
})
