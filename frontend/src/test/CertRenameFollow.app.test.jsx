import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act } from './test-utils.jsx'

/**
 * Açık sertifika penceresi yeniden adlandırmayı İZLER — App boru hattıyla (2026-10-08, kullanıcı: "takip adı değiştirince
 * Sağlık bilgisi yüklenemedi … 404", "Kontrol geçmişi yüklenemedi / Domain envanterde bulunamadı … 404", "takip adı
 * değişikliği hemen modala yansımıyor"). Envanter formu `sm:inventory-renamed` yayar (InventoryFormModal.manual testi);
 * burada App'in dinleyicisi: pencere YENİ adla sürer, eski adla istek atmaz; başka bir kaydın yeniden adlandırılması açık
 * pencereye dokunmaz. Sunucu taklidi: yeniden adlandırmadan sonra eski ad 404 döner.
 */
const fx = vi.hoisted(() => ({
  certs: [
    { domain: 'eski-ad', cert_source: 'MANUAL', status: 'valid', warning: false, days_remaining: 200, not_after: '2027-04-01T00:00:00', checked_at: '2026-10-08T08:00:00', team_id: 1 },
    { domain: 'shop.example.com', status: 'valid', warning: false, days_remaining: 150, not_after: '2027-02-01T00:00:00', checked_at: '2026-10-08T08:00:00', team_id: 1 },
  ],
  renamed: false,
  history: vi.fn(),
  health: vi.fn(),
  toast: { success: vi.fn(), error: vi.fn(), info: vi.fn(), dismiss: vi.fn() },
}))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => fx.toast, ToastProvider: ({ children }) => children }))
vi.mock('../api/client', () => {
  const overrides = {
    getMe: () => Promise.resolve({ success: true, username: 'tester', system_role: 'ADMIN', global_admin: true, team_ids: [1], tour: { status: 'dismissed' } }),
    getCertificates: () => Promise.resolve({ success: true, data: fx.certs, timestamp: '2026-10-08T08:00:00' }),
    getHistory: (d) => fx.history(d),
    getCertificateHealth: (d) => fx.health(d),
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
import { announceInventoryRenamed } from '../utils/inventoryEvent.js'

const certDialog = () => screen.queryAllByRole('dialog')[0] || null
const named = (fn) => fn.mock.calls.map((c) => c[0])

describe('Açık sertifika penceresi yeniden adlandırmayı izler (App)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    fx.renamed = false
    fx.history.mockImplementation(async (d) => (fx.renamed && d === 'eski-ad'
      ? { success: false, status: 404, error: 'Domain envanterde bulunamadı' }
      : { success: true, data: [{ domain: d, status: 'valid', days_remaining: 200, san: [] }] }))
    fx.health.mockImplementation(async (d) => (fx.renamed && d === 'eski-ad'
      ? { success: false, status: 404, error: 'Kayıt bulunamadı' }
      : { success: true, data: { domain: d, checks: [], summary: {} } }))
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('ad değişince pencere YENİ adla sürer: başlık yeni ad, veri yeni adla okunur, eski adla istek yok, hata yok', async () => {
    window.history.replaceState({}, '', '/?tab=dashboard&domain=eski-ad&open=cert')
    render(<App />)
    await waitFor(() => expect(certDialog()?.textContent).toContain('eski-ad'), { timeout: 5000 })
    await waitFor(() => expect(fx.history).toHaveBeenCalledWith('eski-ad'))

    fx.renamed = true
    fx.history.mockClear(); fx.health.mockClear()
    act(() => announceInventoryRenamed('eski-ad', 'yeni-ad'))

    await waitFor(() => expect(certDialog()?.textContent).toContain('yeni-ad'))
    await waitFor(() => expect(fx.history).toHaveBeenCalledWith('yeni-ad'))
    expect(certDialog().textContent).not.toContain('eski-ad')
    expect(named(fx.history)).not.toContain('eski-ad')
    expect(named(fx.health)).not.toContain('eski-ad')
    expect(fx.toast.error).not.toHaveBeenCalled()
  })

  it('başka bir kaydın yeniden adlandırılması açık pencereye dokunmaz', async () => {
    window.history.replaceState({}, '', '/?tab=dashboard&domain=eski-ad&open=cert')
    render(<App />)
    await waitFor(() => expect(certDialog()?.textContent).toContain('eski-ad'), { timeout: 5000 })
    act(() => announceInventoryRenamed('shop.example.com', 'magaza.example.com'))
    await new Promise((r) => setTimeout(r, 30))
    expect(certDialog().textContent).toContain('eski-ad')
    expect(certDialog().textContent).not.toContain('magaza.example.com')
  })
})
