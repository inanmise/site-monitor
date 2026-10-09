import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../utils/accountInactive.js', async (importOriginal) => {
  const mod = await importOriginal()
  return { ...mod, assignLocation: vi.fn() }
})

import { api } from '../api/client.js'
import { assignLocation } from '../utils/accountInactive.js'
import {
  EXPIRED_REDIRECTS_KEY, EXPIRED_REDIRECT_WINDOW_MS, SESSION_EXPIRED_EVENT, claimExpiredRedirect,
} from '../utils/sessionExpiry.js'

/**
 * Oturum düşüşü yönlendirmesinin döngü sigortası (2026-10-09): çok kopyalı yanlış kurulumda (bellek içi oturum; /me bir
 * kopyada 200, yoklama ötekinde 401) yükle → 401 → /?session=expired → yükle döngüsü. 60 sn içinde 2 yönlendirmeden
 * sonra sayfa yeniden YÜKLENMEZ; uygulama giriş formunu yerinde açsın diye olay yayılır.
 */

describe('claimExpiredRedirect', () => {
  beforeEach(() => { sessionStorage.clear() })
  afterEach(() => { vi.restoreAllMocks() })

  it('60 sn içinde en çok 2 yönlendirme; pencere geçince yeniden izin', () => {
    const t0 = 1_000_000
    expect(claimExpiredRedirect(t0)).toBe(true)
    expect(claimExpiredRedirect(t0 + 10_000)).toBe(true)
    expect(claimExpiredRedirect(t0 + 20_000)).toBe(false)
    expect(claimExpiredRedirect(t0 + 59_000)).toBe(false)
    expect(claimExpiredRedirect(t0 + EXPIRED_REDIRECT_WINDOW_MS + 10_001)).toBe(true)   // ilk ikisi pencereden çıktı
  })

  it('bozuk kayıt taze sayılır; depolama yoksa eski davranış (yönlendir)', () => {
    sessionStorage.setItem(EXPIRED_REDIRECTS_KEY, '"x"')
    expect(claimExpiredRedirect(5)).toBe(true)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    sessionStorage.removeItem(EXPIRED_REDIRECTS_KEY)
    expect(claimExpiredRedirect(10)).toBe(true)
  })
})

describe('api/client — 401 yönlendirme döngüsü sigortası', () => {
  let events
  let off
  beforeEach(() => {
    sessionStorage.clear()
    vi.mocked(assignLocation).mockClear()
    events = 0
    const h = () => { events++ }
    window.addEventListener(SESSION_EXPIRED_EVENT, h)
    off = () => window.removeEventListener(SESSION_EXPIRED_EVENT, h)
    global.fetch = vi.fn().mockResolvedValue({ status: 401, ok: false, json: () => Promise.resolve({ success: false, error: 'Session superseded' }) })
  })
  afterEach(() => { off(); sessionStorage.clear() })

  it('iki yönlendirmeden sonra üçüncü 401 sayfayı yeniden YÜKLEMEZ — giriş formu yerinde açılsın diye olay', async () => {
    for (let i = 0; i < 3; i++) {
      sessionStorage.setItem('sm.session.active', '1')   // her yüklemede /me 200 bayrağı yeniden yazar
      expect(await api.getCertificates()).toBeNull()
    }
    expect(assignLocation).toHaveBeenCalledTimes(2)
    expect(assignLocation).toHaveBeenCalledWith('/?session=expired')
    expect(events).toBe(1)
    expect(sessionStorage.getItem('sm.session.active')).toBeNull()
  })

  it('tek düşüş eskisi gibi: yönlendirme, olay yok; bayrak yokken (açılış) hiçbir şey', async () => {
    expect(await api.getCertificates()).toBeNull()
    expect(assignLocation).not.toHaveBeenCalled()
    sessionStorage.setItem('sm.session.active', '1')
    await api.getCertificates()
    expect(assignLocation).toHaveBeenCalledTimes(1)
    expect(events).toBe(0)
  })
})
