import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act, fireEvent, within } from './test-utils.jsx'

/**
 * SSL derin bağlantısı — App boru hattıyla uçtan uca (2026-09-28, 7/24 Kapsamı → SSL satırı).
 * `?tab=dashboard&domain=<d>&open=cert` Pano'yu alanla SÜZER (eski davranış) VE sertifika penceresini AÇAR; `open`
 * tüketilince adresten silinir. Pano süzgeci (platform) kartı gizlese de pencere açılır. Bulunamayan alan uyarı verir.
 * Gerçek App render edilir (DashboardPlatformFilter deseni: derin Proxy api mock'u).
 */
const fx = vi.hoisted(() => ({
  certs: [
    { domain: 'shop-a.example.com', status: 'valid', warning: false, days_remaining: 200, not_after: '2027-04-01T00:00:00', checked_at: '2026-09-25T08:00:00', platform: 'IIS', team_id: 1 },
    { domain: 'shop-b.example.com', status: 'valid', warning: false, days_remaining: 150, not_after: '2027-02-01T00:00:00', checked_at: '2026-09-25T08:00:00', platform: 'OPENSHIFT', team_id: 1 },
  ],
  history: vi.fn(),
  toast: { success: vi.fn(), error: vi.fn(), info: vi.fn(), dismiss: vi.fn() },
}))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => fx.toast, ToastProvider: ({ children }) => children }))
vi.mock('../api/client', () => {
  const overrides = {
    getMe: () => Promise.resolve({ success: true, username: 'tester', system_role: 'ADMIN', global_admin: true, team_ids: [1], tour: { status: 'dismissed' } }),
    getCertificates: () => Promise.resolve({ success: true, data: fx.certs, timestamp: '2026-09-25T08:00:00' }),
    getHistory: (d) => fx.history(d),
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

const NOT_FOUND = /^(Monitor not found, or you don’t have access to it|İzleme bulunamadı ya da erişiminiz yok)$/
const certDialog = (d) => screen.queryAllByRole('dialog').find((x) => x.textContent.includes(d)) || null
const param = (k) => new URLSearchParams(window.location.search).get(k)

describe('SSL derin bağlantısı (App)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    fx.history.mockResolvedValue({ success: true, data: [] })
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('open=cert: sertifika penceresi açılır — platform süzgeci kartı gizlese de; `open` silinir, `domain` süzgeç olarak kalır', async () => {
    window.history.replaceState({}, '', '/?tab=dashboard&domain=shop-b.example.com&open=cert&platform=IIS')
    render(<App />)
    await waitFor(() => expect(certDialog('shop-b.example.com')).not.toBeNull(), { timeout: 5000 })
    // kart panoda GÖRÜNMÜYOR (arama shop-b + platform IIS) — pencere yine de açık
    expect(screen.queryAllByText('shop-b.example.com').filter((el) => !el.closest('[role="dialog"]'))).toHaveLength(0)
    expect(param('open')).toBeNull()
    expect(param('domain')).toBe('shop-b.example.com')
    expect(fx.history).toHaveBeenCalledWith('shop-b.example.com')   // pencerenin kendi verisi (liste yedeği değil)
    expect(fx.toast.error).not.toHaveBeenCalled()
  })

  it('Pano listesinde olmayan ama görülebilir alan (UG takımı) → pencere alan adıyla açılır', async () => {
    window.history.replaceState({}, '', '/?tab=dashboard&domain=ug.example.com&open=cert')
    render(<App />)
    await waitFor(() => expect(certDialog('ug.example.com')).not.toBeNull(), { timeout: 5000 })
    expect(fx.toast.error).not.toHaveBeenCalled()
  })

  it('bulunamayan / erişilemeyen alan → "İzleme bulunamadı ya da erişiminiz yok", pencere yok', async () => {
    fx.history.mockResolvedValue({ success: false, error: 'Domain envanterde bulunamadı: gone.example.com' })
    window.history.replaceState({}, '', '/?tab=dashboard&domain=gone.example.com&open=cert')
    render(<App />)
    await waitFor(() => expect(fx.toast.error).toHaveBeenCalledWith(expect.stringMatching(NOT_FOUND)), { timeout: 5000 })
    expect(certDialog('gone.example.com')).toBeNull()
  })

  it('başka sekmeden (7/24 Kapsamı) uygulama içi gezinme: Pano sekmesi + pencere; yeni geçmiş kaydı, adreste open yok', async () => {
    window.history.replaceState({}, '', '/?tab=noc&n_q=shop')
    render(<App />)
    await screen.findByRole('heading', { name: /24\/7 Coverage|7\/24 Kapsamı/ }, { timeout: 5000 })
    const before = window.history.length
    act(() => {
      window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'dashboard', params: { domain: 'shop-a.example.com', open: 'cert' } } }))
    })
    await waitFor(() => expect(certDialog('shop-a.example.com')).not.toBeNull(), { timeout: 5000 })
    expect(window.history.length).toBe(before + 1)   // pushState → Geri kapsam sayfasına döner
    expect(param('tab')).toBe('dashboard')
    expect(param('open')).toBeNull()
    expect(param('n_q')).toBeNull()                   // kapsam süzgeci yeni kayda taşınmaz (eski kayıtta kalır)
  })

  const nav = (tab, params = {}) => act(() => { window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab, params } })) })

  it('Pano\'dan ayrılış: bağlantının açtığı pencere kapanır; kullanıcı onu KAPATIP elle başkasını açtıysa o AÇIK kalır', async () => {
    window.history.replaceState({}, '', '/?tab=dashboard&domain=shop-a.example.com&open=cert')
    render(<App />)
    await waitFor(() => expect(certDialog('shop-a.example.com')).not.toBeNull(), { timeout: 5000 })
    nav('noc')
    await waitFor(() => expect(certDialog('shop-a.example.com')).toBeNull())   // bağlantının açtığı → kapandı

    nav('dashboard', { domain: 'shop-a.example.com', open: 'cert' })
    await waitFor(() => expect(certDialog('shop-a.example.com')).not.toBeNull(), { timeout: 5000 })
    fireEvent.click(within(certDialog('shop-a.example.com')).getByRole('button', { name: /^(Close|Kapat)$/ }))   // kullanıcı kapattı
    await waitFor(() => expect(certDialog('shop-a.example.com')).toBeNull())
    nav('dashboard', { domain: 'shop-b.example.com' })   // `open`'sız: yalnız süzgeç
    fireEvent.click(await screen.findByRole('button', { name: /^shop-b\.example\.com — (open certificate details|sertifika detayını aç)$/ }, { timeout: 5000 }))
    await waitFor(() => expect(certDialog('shop-b.example.com')).not.toBeNull())
    nav('noc')
    await screen.findByRole('heading', { name: /24\/7 Coverage|7\/24 Kapsamı/ }, { timeout: 5000 })
    expect(certDialog('shop-b.example.com')).not.toBeNull()   // elle açılan pencere sessizce kapanmadı
  })
})
