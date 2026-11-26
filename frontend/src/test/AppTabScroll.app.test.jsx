import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act } from './test-utils.jsx'
import { scrollPageTopForTab } from '../utils/tabScroll.js'

/**
 * Sekme değişiminde sayfa başa kaydırılır (H paketi 2026-11-26, deneyim 4). Yalnız GERÇEK sekme değişiminde (menü /
 * navigateTo); aynı sekmede param değişiminde hayır; tarayıcı Geri / İleri'de hayır (tarayıcı o kaydın konumunu kendisi
 * geri yükler — inceleme bulgusu 3); zaten baştaysa çağrı yok. Eski sekmenin `#çapa`sı yeni sekmeye taşınmaz ve başa
 * dönmeyi engellemez (inceleme bulgusu 2 — önceki sürüm bu hatayı kilitliyordu). Gerçek tarayıcı ölçümü:
 * e2e/h-package-navigation.spec.js.
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

const realScrollTo = window.scrollTo
let scrollTo
function setScrollY(y) { Object.defineProperty(window, 'scrollY', { value: y, configurable: true, writable: true }) }

beforeEach(() => {
  localStorage.clear()
  try { sessionStorage.clear() } catch { /* jsdom */ }
  state.me = { success: true, username: 'ali', system_role: 'USER', global_admin: false, team_ids: [] }
  state.prefs = {}
  scrollTo = vi.fn()
  window.scrollTo = scrollTo
  setScrollY(0)
})
afterEach(() => {
  window.scrollTo = realScrollTo
  setScrollY(0)
  window.history.replaceState({}, '', '/')
})

async function boot(url) {
  window.history.replaceState({}, '', url)
  const r = render(<App />)
  await screen.findByText(/^v\d+\./)
  return r
}
const navigate = (tab, params) => act(() => { window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab, params } })) })
const tabParam = () => new URLSearchParams(window.location.search).get('tab')

describe('scrollPageTopForTab (saf)', () => {
  const win = (over = {}) => ({ location: { hash: '' }, scrollY: 0, scrollX: 0, scrollTo: vi.fn(), ...over })
  it('aşağıdaysa başa (instant); baştaysa çağrı yok', () => {
    const w = win({ scrollY: 800 })
    expect(scrollPageTopForTab(w)).toBe(true)
    expect(w.scrollTo).toHaveBeenCalledWith({ top: 0, left: 0, behavior: 'instant' })
    const top = win()
    expect(scrollPageTopForTab(top)).toBe(false)
    expect(top.scrollTo).not.toHaveBeenCalled()
  })
  it('#çapa denetimi YOK (bu yolda çapa olmaz — handleTabChange siler); scrollTo atarsa sessiz; pencere yoksa false', () => {
    const w = win({ scrollY: 800, location: { hash: '#sec-db' } })
    expect(scrollPageTopForTab(w)).toBe(true)
    expect(w.scrollTo).toHaveBeenCalledTimes(1)
    expect(scrollPageTopForTab(win({ scrollY: 5, scrollTo: () => { throw new Error('x') } }))).toBe(false)
    expect(scrollPageTopForTab(null)).toBe(false)
  })
})

describe('App — sekme değişiminde başa kaydırma', () => {
  it('navigateTo başka sekmeye → başa; aynı sekmede param → kaydırma YOK', async () => {
    await boot('/?tab=dashboard')
    setScrollY(900)
    navigate('help')
    await waitFor(() => expect(tabParam()).toBe('help'))
    expect(scrollTo).toHaveBeenCalledTimes(1)
    expect(scrollTo).toHaveBeenCalledWith({ top: 0, left: 0, behavior: 'instant' })
    setScrollY(400)
    navigate('help', { view: 'releases' })   // aynı sekme + param → sm:tab-params, kaydırma yok
    expect(scrollTo).toHaveBeenCalledTimes(1)
  })

  it('tarayıcı Geri / İleri başa KAYDIRMAZ (tarayıcı konumu kendisi geri yükler) — başka sekmeye de, aynı sekmeye de', async () => {
    await boot('/?tab=dashboard')
    navigate('help')
    await waitFor(() => expect(tabParam()).toBe('help'))
    scrollTo.mockClear()
    setScrollY(700)
    // Tarayıcı Geri (jsdom'da geçmiş gezintisi yok — adres + popstate taklit edilir, AppNotFound deseni)
    act(() => {
      window.history.replaceState({}, '', '/?tab=dashboard')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    await waitFor(() => expect(document.querySelector('[data-tab="dashboard"], .app-main')).not.toBeNull())
    expect(scrollTo).not.toHaveBeenCalled()
    act(() => {
      window.history.replaceState({}, '', '/?tab=dashboard&q=x')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    expect(scrollTo).not.toHaveBeenCalled()
  })

  it('eski sekmenin #çapası (içindekiler) yeni sekmeye TAŞINMAZ ve başa dönmeyi engellemez; sonraki geçişler de başa döner', async () => {
    await boot('/?tab=dashboard')
    scrollTo.mockClear()
    // Ör. Yönetici özeti içindekilerden bir başlığa atlandı: adreste #çapa
    window.history.replaceState(window.history.state, '', '/?tab=dashboard#ex-sec-availability')
    setScrollY(700)
    navigate('help')
    await waitFor(() => expect(tabParam()).toBe('help'))
    expect(window.location.hash).toBe('')
    expect(scrollTo).toHaveBeenCalledTimes(1)
    // Oturumun geri kalanında da: bir sonraki gerçek sekme değişimi yine başa
    setScrollY(500)
    navigate('dashboard')
    await waitFor(() => expect(tabParam()).toBe('dashboard'))
    expect(window.location.hash).toBe('')
    expect(scrollTo).toHaveBeenCalledTimes(2)
  })
})
