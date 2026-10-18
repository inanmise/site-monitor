import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act } from './test-utils.jsx'
import { reportResponse } from './helpers/techErrorFixtures.js'

/**
 * Sekmeler arası hareketsizlik (2026-10-18, Görsel paneli): kullanıcı ayrı panel penceresinde çalışırken ana pencere
 * paylaşılan oturumu KAPATMAZ. İki sekme `storage` olayıyla taklit edilir: öteki sekmenin damgası (`sm.activity.<id>`)
 * uyarıyı kaldırır ve sayacı yeniden kurar; olay kaçsa da uyarı / çıkış öncesi damgaya bakılır. Süreler kısaltıldı
 * (/me: toplam 3 sn, uyarı son 2 sn). Görsel paneli kabuğu da burada: kenar çubuksuz, pano verisi yüklenmez, kendi başlığı.
 */
const fx = vi.hoisted(() => ({ logout: 0, certs: 0 }))

vi.mock('../api/client', async () => {
  const { reportResponse: rr } = await import('./helpers/techErrorFixtures.js')
  const overrides = {
    getMe: () => Promise.resolve({ success: true, username: 'panel-kullanicisi', system_role: 'USER', tour: { status: 'dismissed' },
      inactivity_minutes: 0.05, inactivity_warn_seconds: 2 }),
    logout: () => { fx.logout++; return Promise.resolve({ success: true }) },
    getCertificates: () => { fx.certs++; return Promise.resolve({ success: true, data: [] }) },
    techErrors: {
      report: () => Promise.resolve(rr()),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
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
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import {
  ACTIVITY_PREFIX, ACTIVITY_THROTTLE_MS, activityUserKey, markActivity, ownActivityKey, resetActivityThrottleForTests,
} from '../utils/crossTabActivity.js'
import { OWNER_KEY } from '../utils/personalStorage.js'
import { OPEN_REPORT_ACK, OPEN_REPORT_REQUEST } from '../components/techerror/capture/panelWindow.js'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const OTHER = `${ACTIVITY_PREFIX}gorsel-paneli`
const ME = 'panel-kullanicisi'
const warning = () => document.querySelector('[data-slot="inactivity-warning"]')

/**
 * Öteki sekmenin "şimdi" etkinliği: damga kısma payı kadar geriden yazılır (okuyan pay ekler → tam şimdi). `user`: damgayı
 * yazan sekmenin kullanıcısı; `at`: damga zamanı (verilirse kısma payı eklenmez).
 */
function otherTabActive({ event = true, user = ME, at = null } = {}) {
  const v = JSON.stringify({ u: activityUserKey(user), t: at ?? Date.now() - ACTIVITY_THROTTLE_MS })
  localStorage.setItem(OTHER, v)
  if (event) window.dispatchEvent(new StorageEvent('storage', { key: OTHER, newValue: v }))
}

describe('App — sekmeler arası hareketsizlik', () => {
  beforeEach(() => {
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    localStorage.setItem(LANG_STORAGE_KEY, 'tr')
    resetActivityThrottleForTests()
    fx.logout = 0
    fx.certs = 0
    window.history.replaceState({}, '', '/')
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('öteki sekme etkinken uyarı ve çıkış yok; öteki sekme durunca uyarı gelir', async () => {
    render(<App />)
    await waitFor(() => expect(fx.certs).toBeGreaterThan(0), { timeout: 8000 })
    for (let i = 0; i < 6; i++) {
      await act(async () => { otherTabActive(); await sleep(700) })
    }
    expect(warning()).toBeNull()
    expect(fx.logout).toBe(0)
    await waitFor(() => expect(warning()).not.toBeNull(), { timeout: 2500 })
  }, 20_000)

  it('uyarı görünürken öteki sekme etkin olursa uyarı kalkar (bir sekmenin "devam" kararı ötekiyle çelişmez)', async () => {
    render(<App />)
    await waitFor(() => expect(warning()).not.toBeNull(), { timeout: 8000 })
    act(() => otherTabActive())
    await waitFor(() => expect(warning()).toBeNull())
    // Uygulama içi eylem (ekrandan alma / yapıştırma / yükleme) kendi sekmesinin sayacını kurar
    await waitFor(() => expect(warning()).not.toBeNull(), { timeout: 2500 })
    act(() => markActivity())
    await waitFor(() => expect(warning()).toBeNull())
    expect(fx.logout).toBe(0)
  }, 20_000)

  it('storage olayı kaçsa da uyarı / çıkış öncesi öteki sekmenin damgasına bakılır', async () => {
    render(<App />)
    await waitFor(() => expect(fx.certs).toBeGreaterThan(0), { timeout: 8000 })
    for (let i = 0; i < 6; i++) {
      await act(async () => { otherTabActive({ event: false }); await sleep(700) })
    }
    expect(warning()).toBeNull()
    expect(fx.logout).toBe(0)
    await waitFor(() => expect(warning()).not.toBeNull(), { timeout: 3000 })
  }, 20_000)

  it('tek sekmede davranış aynı: uyarı ~1 sn sonra, çıkış toplam sürede (kendi damgası okunmaz); çıkışta kendi damgası silinir', async () => {
    render(<App />)
    await waitFor(() => expect(localStorage.getItem(ownActivityKey())).not.toBeNull(), { timeout: 8000 })
    // Damga kullanıcının özetini taşır, adını değil
    expect(localStorage.getItem(ownActivityKey())).toContain(activityUserKey(ME))
    expect(localStorage.getItem(ownActivityKey())).not.toContain(ME)
    await waitFor(() => expect(warning()).not.toBeNull(), { timeout: 8000 })
    await waitFor(() => expect(fx.logout).toBe(1), { timeout: 4000 })
    // Sonraki kullanıcı bu sekmede girerse önceki kişinin damgası öteki sekmelerini açık tutmaz
    await waitFor(() => expect(localStorage.getItem(ownActivityKey())).toBeNull())
  }, 20_000)

  it('3 saat ileri tarihli damga (saat oynadı) sekmeyi açık tutmaz ve silinir; uyarı ve çıkış zamanında gelir', async () => {
    render(<App />)
    await waitFor(() => expect(fx.certs).toBeGreaterThan(0), { timeout: 8000 })
    act(() => otherTabActive({ at: Date.now() + 3 * 3600_000 }))
    // Eskiden damga "şimdi" sayılıyordu: sayaç her seferinde yeniden kuruluyor, çıkış hiç olmuyordu
    await waitFor(() => expect(fx.logout).toBe(1), { timeout: 5000 })
    expect(localStorage.getItem(OTHER)).toBeNull()
  }, 20_000)

  it('BAŞKA kullanıcının sekmesi (aynı tarayıcıda yeni giriş) bu sekmeyi açık tutmaz', async () => {
    render(<App />)
    await waitFor(() => expect(fx.certs).toBeGreaterThan(0), { timeout: 8000 })
    // Öteki sekme toplam süreden (3 sn) uzun etkin kalır; damgası sayılmaz → bu sekme zamanında çıkar
    for (let i = 0; i < 7 && fx.logout === 0; i++) {
      await act(async () => { otherTabActive({ user: 'baska-kullanici' }); await sleep(700) })
    }
    await waitFor(() => expect(fx.logout).toBe(1), { timeout: 2500 })
  }, 20_000)

  it('tarayıcıdaki son giriş başka kullanıcınınsa aynı kullanıcının öteki sekmesi de bu sekmeyi açık tutmaz (oturum zaten bitti)', async () => {
    render(<App />)
    await waitFor(() => expect(fx.certs).toBeGreaterThan(0), { timeout: 8000 })
    act(() => localStorage.setItem(OWNER_KEY, 'baska-kullanici'))
    for (let i = 0; i < 7 && fx.logout === 0; i++) {
      await act(async () => { otherTabActive(); await sleep(700) })
    }
    await waitFor(() => expect(fx.logout).toBe(1), { timeout: 2500 })
  }, 20_000)
})

describe('App — Görsel panelinin "Ana pencerede aç" isteği', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem(LANG_STORAGE_KEY, 'tr')
    resetActivityThrottleForTests()
    fx.certs = 0
    window.history.replaceState({}, '', '/?tab=dashboard')
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('rapor UYGULAMA İÇİNDE açılır (sayfa yüklenmez) ve panele yanıt gider; başka kökenden istek yok sayılır', async () => {
    render(<App />)
    await waitFor(() => expect(fx.certs).toBeGreaterThan(0), { timeout: 8000 })
    const post = vi.spyOn(window, 'postMessage').mockImplementation(() => {})
    const ask = (origin) => window.dispatchEvent(new MessageEvent('message', { origin, source: window,
      data: { type: OPEN_REPORT_REQUEST, rid: 'r1', reportId: '100', channelId: '10' } }))
    act(() => ask('https://qlik.example'))
    expect(new URLSearchParams(window.location.search).get('tab')).toBe('dashboard')
    expect(post).not.toHaveBeenCalled()
    act(() => ask(window.location.origin))
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('te_rep')).toBe('100'))
    const p = new URLSearchParams(window.location.search)
    expect([p.get('tab'), p.get('te_view'), p.get('te_ch')]).toEqual(['channelreports', 'report', '10'])
    expect(post).toHaveBeenCalledWith({ type: OPEN_REPORT_ACK, rid: 'r1' }, window.location.origin)
    post.mockRestore()
  }, 20_000)
})

describe('App — Görsel paneli kabuğu', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem(LANG_STORAGE_KEY, 'tr')
    fx.certs = 0
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=images&te_rep=100')
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('kenar çubuğu / gezinme yok; pano verisi yüklenmez; raporun görsel bölümü ve sayfa başlığı', async () => {
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1, name: reportResponse().data.report.title }, { timeout: 8000 })).toBeInTheDocument()
    expect(document.querySelector('[data-slot="sidebar"]')).toBeNull()
    expect(document.querySelectorAll('[data-slot="te-image-slot"]').length).toBe(3)
    expect(fx.certs).toBe(0)
    await waitFor(() => expect(document.title).toBe('Görsel paneli · SiteMonitor'))
  }, 20_000)
})
