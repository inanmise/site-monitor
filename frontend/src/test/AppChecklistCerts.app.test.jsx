import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act } from './test-utils.jsx'

/**
 * Başlangıç listesinin "kart" maddesi (H paketi inceleme bulgusu 8): "takımın sertifikası yok" kararı ancak AKTİF ve PASİF
 * sertifika listesi okununca verilir. Eskiden aktif liste boş gelir gelmez madde gizleniyor, pasif kartlar sonra gelince
 * geri dönüyordu; pasif liste okunamazsa yanlışlıkla gizli kalıyordu. Kurallar:
 *  - yalnız pasif kartı olan takım → madde görünür (sertifikası var);
 *  - aktif ve pasif ikisi de boş okundu → madde gizli;
 *  - pasif liste henüz gelmedi ya da okunamadı → bilinmiyor → madde görünür.
 */
const state = { me: null, certs: null, paused: null }
function deferred() { let resolve; const promise = new Promise((r) => { resolve = r }); return { promise, resolve } }

vi.mock('../api/client', () => {
  const overrides = {
    getMe: () => Promise.resolve(state.me),
    'me.getPreferences': () => Promise.resolve({ success: true, prefs: {} }),
    'me.savePreferences': (patch) => Promise.resolve({ success: true, prefs: patch }),
    getCertificates: () => Promise.resolve(state.certs),
    getPausedCertificates: () => (state.paused?.promise ? state.paused.promise : Promise.resolve(state.paused)),
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

const TOUR = { status: 'completed', version: 99, checklist: { tour: true }, seen_pages: [] }
const PAUSED = { domain: 'paused.example.com', paused: true, active: false, status: 'valid', days_remaining: 40 }

beforeEach(() => {
  localStorage.clear()
  try { sessionStorage.clear() } catch { /* jsdom */ }
  state.me = { success: true, username: 'ali', system_role: 'USER', global_admin: false, team_ids: [1], weekly_reports_visible: false, tour: TOUR }
  state.certs = { success: true, data: [], timestamp: '2026-11-26T08:00:00Z' }
  state.paused = { success: true, data: [] }
})
afterEach(() => { window.history.replaceState({}, '', '/') })

async function boot() {
  window.history.replaceState({}, '', '/?tab=dashboard')
  const r = render(<App />)
  await screen.findByText(/^v\d+\./)
  return r
}
const checklist = () => document.querySelector('[data-slot="onboarding-checklist"]')
const cardItem = () => document.querySelector('[data-checklist-item="card"]')
const settle = () => new Promise((r) => setTimeout(r, 250))

describe('App — başlangıç listesi "kart" maddesi aktif + pasif listeye göre', () => {
  it('aktif ve pasif ikisi de BOŞ okundu → "kart" maddesi gizli', async () => {
    await boot()
    await waitFor(() => expect(checklist()).not.toBeNull())
    await waitFor(() => expect(cardItem()).toBeNull())
  })

  it('yalnız PASİF kartı olan takım → "kart" maddesi görünür (sertifikası var)', async () => {
    state.paused = { success: true, data: [PAUSED] }
    await boot()
    await waitFor(() => expect(checklist()).not.toBeNull())
    await settle()
    expect(cardItem()).not.toBeNull()
  })

  it('pasif liste okunamadı → bilinmiyor → "kart" maddesi görünür (yanlış gizlenmez)', async () => {
    state.paused = { success: false, error: 'db down' }
    await boot()
    await waitFor(() => expect(checklist()).not.toBeNull())
    await settle()
    expect(cardItem()).not.toBeNull()
  })

  it('pasif liste henüz gelmedi → bilinmiyor (görünür); boş gelince gizlenir — aktif boş gelir gelmez gizlenmez', async () => {
    state.paused = deferred()
    await boot()
    await waitFor(() => expect(checklist()).not.toBeNull())
    await settle()
    expect(cardItem()).not.toBeNull()
    await act(async () => { state.paused.resolve({ success: true, data: [] }) })
    await waitFor(() => expect(cardItem()).toBeNull())
  })
})
