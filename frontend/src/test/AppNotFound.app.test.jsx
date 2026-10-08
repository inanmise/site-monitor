import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, act } from './test-utils.jsx'

/**
 * App düzeyinde markalı 404 + sayfa meta'sı (2026-10-08):
 *  - oturum açıkken tanınmayan `?tab=` → uygulama içi "Sayfa bulunamadı" paneli (eskiden sessizce Pano); "Panoya git"
 *    geçmişe kayıt bırakarak Pano'ya götürür; Geri ile panel geri gelir;
 *  - bayat açılış sekmesi tercihi kullanıcıyı panele hapsetmez (Pano açılır);
 *  - rolüne kapalı sekme (Ayarlar, USER) boş sayfa yerine "Erişim yok" paneli;
 *  - belge başlığı her durumda "<Sayfa> · SiteMonitor" (giriş, oturum süresi doldu, sekme, panel).
 */
const state = { me: null, prefs: {} }

vi.mock('../api/client', () => {
  const overrides = {
    getMe: () => Promise.resolve(state.me),
    'me.getPreferences': () => Promise.resolve({ success: true, prefs: state.prefs }),
    'me.savePreferences': (patch) => Promise.resolve({ success: true, prefs: { ...state.prefs, ...patch } }),
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

const ME = (extra = {}) => ({ success: true, username: 'ali', system_role: 'USER', global_admin: false, team_ids: [], ...extra })
const tabParam = () => new URLSearchParams(window.location.search).get('tab')
const panel = () => document.querySelector('[data-slot="not-found-panel"]')

beforeEach(() => {
  localStorage.clear()
  try { sessionStorage.clear() } catch { /* jsdom */ }
  state.me = ME()
  state.prefs = {}
  document.title = ''
})
afterEach(() => { window.history.replaceState({}, '', '/') })

async function boot(url) {
  window.history.replaceState({}, '', url)
  const r = render(<App />)
  await screen.findByText(/^v\d+\./)   // girişli kabuk (Nav sürüm rozeti)
  return r
}

describe('App — bilinmeyen sekme paneli', () => {
  it('?tab=<bilinmeyen> → "Page not found" paneli, istenen değer ve başlık; Panoya git → Pano; Geri → panel', async () => {
    await boot('/?tab=eski-rapor')
    await waitFor(() => expect(panel()).not.toBeNull())
    expect(panel().getAttribute('data-kind')).toBe('notFound')
    expect(panel().querySelector('[data-slot="nf-requested"]').textContent).toBe('?tab=eski-rapor')
    expect(screen.queryByRole('heading', { name: 'Dashboard' })).toBeNull()   // Pano içeriği SESSİZCE açılmadı
    await waitFor(() => expect(document.title).toBe('Page not found · SiteMonitor'))

    fireEvent.click(screen.getByRole('button', { name: /Go to dashboard/ }))
    await waitFor(() => expect(panel()).toBeNull())
    expect(tabParam()).toBe('dashboard')
    await waitFor(() => expect(document.title).toBe('Dashboard · SiteMonitor'))

    // Tarayıcı Geri: önceki (tanınmayan) adrese dönülünce panel yeniden görünür
    act(() => {
      window.history.replaceState({}, '', '/?tab=eski-rapor')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    await waitFor(() => expect(panel()).not.toBeNull())
  })

  it('bayat açılış sekmesi tercihi panele düşürmez — Pano açılır, adrese ?tab yazılmaz', async () => {
    state.prefs = { landingTab: 'kaldirilmis-sekme' }
    await boot('/')
    await waitFor(() => expect(document.title).toBe('Dashboard · SiteMonitor'))
    expect(panel()).toBeNull()
    expect(tabParam()).toBeNull()
  })

  it('rolüne kapalı sekme (Ayarlar, USER) → boş sayfa yerine "Erişim yok" paneli', async () => {
    await boot('/?tab=settings')
    await waitFor(() => expect(panel()?.getAttribute('data-kind')).toBe('restricted'))
    expect(screen.getByRole('heading', { name: "You don't have access to this page" })).toBeInTheDocument()
    await waitFor(() => expect(document.title).toBe('No access · SiteMonitor'))
  })

  it('geçerli sekme kendi başlığını ve açıklamasını taşır', async () => {
    await boot('/?tab=stats')
    await waitFor(() => expect(document.title).toBe('Statistics · SiteMonitor'))
    expect(document.head.querySelector('meta[name="description"]')?.getAttribute('content'))
      .toMatch(/spread across teams/)
    expect(panel()).toBeNull()
  })
})

describe('App — oturumsuz ekranların başlığı', () => {
  it('giriş → "Sign in", ?session=expired → "Session ended"', async () => {
    state.me = {}
    window.history.replaceState({}, '', '/')
    const first = render(<App />)
    await screen.findByLabelText(/username/i)
    await waitFor(() => expect(document.title).toBe('Sign in · SiteMonitor'))
    first.unmount()

    window.history.replaceState({}, '', '/?session=expired')
    render(<App />)
    await screen.findByLabelText(/username/i)
    await waitFor(() => expect(document.title).toBe('Session ended · SiteMonitor'))
  })
})
