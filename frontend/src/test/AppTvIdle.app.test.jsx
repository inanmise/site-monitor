import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, fireEvent, waitFor } from './test-utils.jsx'

/**
 * TV / duvar ekranı kipi ↔ hareketsizlik çıkışı (Panom, 2026-10-11), App boru hattıyla: kip açıkken uyarı şeridi
 * görünmez ve oturum kapanmaz (duvar ekranında kimse fareye dokunmaz); kip kapanınca sayaç olağan haliyle işler.
 * Süreler sunucunun /me ayarından kısaltıldı: toplam 3 sn, uyarı 2 sn önce (1. saniyede).
 */
const fx = vi.hoisted(() => ({ logout: 0 }))

vi.mock('../api/client', () => {
  const overrides = {
    getMe: () => Promise.resolve({ success: true, username: 'duvar-ekrani', system_role: 'USER', tour: { status: 'dismissed' },
      inactivity_minutes: 0.05, inactivity_warn_seconds: 2 }),
    logout: () => { fx.logout++; return Promise.resolve({ success: true }) },
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
import { isTvModeActive } from '../utils/tvMode.js'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

describe('App — TV kipi hareketsizlik çıkışını erteler', () => {
  beforeEach(() => {
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    fx.logout = 0
    window.history.replaceState({}, '', '/?tab=mydash&md_tv=1')
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('TV açıkken uyarı yok, çıkış yok; TV kapanınca uyarı gelir', async () => {
    render(<App />)
    await waitFor(() => expect(document.querySelector('[data-slot="mydash-tv"]')).not.toBeNull(), { timeout: 8000 })
    // Bayrak katmanla aynı anda (yerleşim etkisi) — yine de yavaş ortamda tek okumaya güvenme
    await waitFor(() => expect(isTvModeActive()).toBe(true))

    await sleep(3600)   // toplam sürenin ötesi
    expect(document.querySelector('[data-slot="inactivity-warning"]')).toBeNull()
    expect(fx.logout).toBe(0)
    expect(document.querySelector('[data-slot="mydash-tv"]')).not.toBeNull()

    fireEvent.click(document.querySelector('[data-slot="mydash-tv-exit"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="mydash-tv"]')).toBeNull())
    expect(isTvModeActive()).toBe(false)
    // Tarayıcının kendiliğinden ürettiği kaydırma ve imleç dururken (AYNI koordinat) fare hareketi etkinlik sayılmaz
    // (2. / 3. inceleme turu 2026-10-18): uyarı yine gelir
    const ticker = setInterval(() => {
      document.dispatchEvent(new Event('scroll'))
      fireEvent.mouseMove(document, { screenX: 500, screenY: 400 })
    }, 200)
    try {
      await waitFor(() => expect(document.querySelector('[data-slot="inactivity-warning"]')).not.toBeNull(), { timeout: 2500 })
    } finally {
      clearInterval(ticker)
    }
    // Gerçek fare hareketi (≥ 4 px) uyarıyı kaldırır
    fireEvent.mouseMove(document, { screenX: 520, screenY: 400 })
    await waitFor(() => expect(document.querySelector('[data-slot="inactivity-warning"]')).toBeNull())
    // Kullanıcı olayı (tuş) da sayılır
    await waitFor(() => expect(document.querySelector('[data-slot="inactivity-warning"]')).not.toBeNull(), { timeout: 2500 })
    fireEvent.keyDown(document, { key: 'Shift' })
    await waitFor(() => expect(document.querySelector('[data-slot="inactivity-warning"]')).toBeNull())
    expect(fx.logout).toBe(0)
  }, 20_000)
})
