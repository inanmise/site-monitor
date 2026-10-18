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
import {
  OPEN_REPORT_ACK, OPEN_REPORT_ANSWER_WINDOW_MS, OPEN_REPORT_REQUEST, OPEN_RESULT_BLOCKED, OPEN_RESULT_OPENED,
} from '../components/techerror/capture/panelWindow.js'
import { useUnsavedWork } from '../utils/unsavedWork.js'

/** Kaydedilmemiş iş tutan ekran (şablon / rapor taslağı) yerine. */
function UnsavedHolder() {
  useUnsavedWork(true)
  return null
}

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

  it('kendiliğinden kaydırma ve imleç dururken (AYNI koordinat) fare hareketi etkinlik sayılmaz: çıkış zamanında', async () => {
    render(<App />)
    await waitFor(() => expect(fx.certs).toBeGreaterThan(0), { timeout: 8000 })
    // Programla üretilen kaydırma ve yerinde duran imlecin mousemove'u toplam süreden (3 sn) uzun sürer → yine de çıkış
    for (let i = 0; i < 9 && fx.logout === 0; i++) {
      await act(async () => {
        document.dispatchEvent(new Event('scroll'))
        window.dispatchEvent(new Event('scroll'))
        document.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, screenX: 400, screenY: 300 }))
        document.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, screenX: 402, screenY: 301 }))   // < 4 px
        await sleep(500)
      })
    }
    await waitFor(() => expect(fx.logout).toBe(1), { timeout: 2500 })
  }, 20_000)

  it('GERÇEK fare hareketi (≥ 4 px) etkinlik sayılır: fareyi oynatarak okuyan kullanıcı çıkarılmaz (3. inceleme turu)', async () => {
    render(<App />)
    await waitFor(() => expect(fx.certs).toBeGreaterThan(0), { timeout: 8000 })
    for (let i = 0; i < 8; i++) {
      await act(async () => {
        document.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, screenX: 400 + i * 10, screenY: 300 }))
        await sleep(600)
      })
    }
    expect(warning()).toBeNull()
    expect(fx.logout).toBe(0)
  }, 20_000)

  it('kullanıcı olayları (tekerlek, işaretçi basışı, tuş, dokunma) sayacı yeniden kurar; yayılımı durdurulsa da görülür', async () => {
    render(<App />)
    await waitFor(() => expect(fx.certs).toBeGreaterThan(0), { timeout: 8000 })
    const stop = (e) => e.stopPropagation()
    document.body.addEventListener('pointerdown', stop)
    const kinds = [
      () => document.body.dispatchEvent(new WheelEvent('wheel', { bubbles: true })),
      () => document.body.dispatchEvent(new Event('pointerdown', { bubbles: true })),
      () => document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', bubbles: true })),
      () => document.body.dispatchEvent(new Event('touchstart', { bubbles: true })),
    ]
    try {
      for (let i = 0; i < 8; i++) {
        await act(async () => { kinds[i % kinds.length](); await sleep(600) })
      }
      expect(warning()).toBeNull()
      expect(fx.logout).toBe(0)
    } finally {
      document.body.removeEventListener('pointerdown', stop)
    }
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

  /** Panelin isteği (aynı köken; `sentAt` verilmezse şimdi). Kısa hareketsizlik süresi (3 sn) testin ortasında çıkış yapmasın. */
  const ask = ({ origin = window.location.origin, rid = 'r1', sentAt = Date.now() } = {}) => {
    markActivity()
    window.dispatchEvent(new MessageEvent('message',
      { origin, source: window, data: { type: OPEN_REPORT_REQUEST, rid, reportId: '100', channelId: '10', sentAt } }))
  }
  const search = () => new URLSearchParams(window.location.search)

  it('rapor UYGULAMA İÇİNDE açılır (sayfa yüklenmez); yanıt gezinmeden ÖNCE gider; başka kökenden istek yok sayılır', async () => {
    render(<App />)
    await waitFor(() => expect(fx.certs).toBeGreaterThan(0), { timeout: 8000 })
    const tabAtAck = []
    const post = vi.spyOn(window, 'postMessage').mockImplementation(() => { tabAtAck.push(search().get('tab')) })
    act(() => ask({ origin: 'https://qlik.example' }))
    expect(search().get('tab')).toBe('dashboard')
    expect(post).not.toHaveBeenCalled()
    act(() => ask())
    await waitFor(() => expect(search().get('te_rep')).toBe('100'))
    expect([search().get('tab'), search().get('te_view'), search().get('te_ch')]).toEqual(['channelreports', 'report', '10'])
    expect(post).toHaveBeenCalledWith({ type: OPEN_REPORT_ACK, rid: 'r1', result: OPEN_RESULT_OPENED }, window.location.origin)
    // Yanıt gönderildiğinde henüz gezinilmemişti (açan pencere meşgul olsa da panel süre sonu yoluna düşmez)
    expect(tabAtAck).toEqual(['dashboard'])
    post.mockRestore()
  }, 20_000)

  it('açık pencere (dialog) ya da kaydedilmemiş iş varsa rapor AÇILMAZ: arkada sayfa değişmez, panele "blocked", burada bildirim', async () => {
    render(<App />)
    await waitFor(() => expect(fx.certs).toBeGreaterThan(0), { timeout: 8000 })
    const post = vi.spyOn(window, 'postMessage').mockImplementation(() => {})
    const dlg = document.createElement('div')
    dlg.setAttribute('role', 'dialog')
    document.body.appendChild(dlg)
    act(() => ask({ rid: 'r-dlg' }))
    expect(post).toHaveBeenCalledWith({ type: OPEN_REPORT_ACK, rid: 'r-dlg', result: OPEN_RESULT_BLOCKED }, window.location.origin)
    expect(await screen.findByText(/Görsel panelindeki rapor burada açılmadı/)).toBeInTheDocument()
    expect(search().get('tab')).toBe('dashboard')
    dlg.remove()
    // Açılır kutu (popover) pencere sayılmaz
    const pop = document.createElement('div')
    pop.setAttribute('data-radix-popper-content-wrapper', '')
    pop.innerHTML = '<div role="dialog"></div>'
    document.body.appendChild(pop)
    // Kaydedilmemiş iş (şablon / rapor taslağı)
    const unsaved = render(<UnsavedHolder />)
    act(() => ask({ rid: 'r-dirty' }))
    expect(post).toHaveBeenCalledWith({ type: OPEN_REPORT_ACK, rid: 'r-dirty', result: OPEN_RESULT_BLOCKED }, window.location.origin)
    expect(search().get('tab')).toBe('dashboard')
    unsaved.unmount()
    act(() => ask({ rid: 'r-ok' }))
    expect(post).toHaveBeenCalledWith({ type: OPEN_REPORT_ACK, rid: 'r-ok', result: OPEN_RESULT_OPENED }, window.location.origin)
    await waitFor(() => expect(search().get('te_rep')).toBe('100'))
    pop.remove()
    post.mockRestore()
  }, 20_000)

  it('geç gelen istek (panel artık adres değiştirecek) yanıtlanmaz ve uygulama içinde açılmaz: tek gezinme', async () => {
    render(<App />)
    await waitFor(() => expect(fx.certs).toBeGreaterThan(0), { timeout: 8000 })
    const post = vi.spyOn(window, 'postMessage').mockImplementation(() => {})
    act(() => ask({ rid: 'r-late', sentAt: Date.now() - OPEN_REPORT_ANSWER_WINDOW_MS - 50 }))
    expect(post).not.toHaveBeenCalled()
    await new Promise((r) => setTimeout(r, 30))
    expect(search().get('tab')).toBe('dashboard')
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
