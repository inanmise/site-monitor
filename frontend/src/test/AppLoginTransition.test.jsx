import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'

/**
 * Hook-sırası regresyonu (v19.94.0, 2026-08-05): App girişsizken erken-return ile Login'i basar;
 * girişte dashboard render edilir. Bir hook (usePagination) yanlışlıkla erken-return'lerin ALTINA
 * konursa girişsiz render'da çağrılmaz, girişli render'da çağrılır → React "Rendered more hooks
 * than during the previous render" ile ÇÖKER. Sayfa testleri bileşenleri hep "girişli" render
 * ettiği için bu geçiş hiçbir testte yoktu ve hata 374 yeşil testle release'e girdi.
 *
 * Bu test tam o geçişi aynı bileşen instance'ında oynatır: girişsiz ilk render → form submit →
 * girişli render. Erken-return'lerden sonra hook eklenirse burada kırılır.
 */

// Derin Proxy mock: App girişli render'da onlarca farklı api.* ucu çağırır — hepsine zararsız
// {success:true, data:[]} döner; yalnız getMe (girişsiz başlangıç) ve login (geçiş) özelleştirilir.
vi.mock('../api/client', () => {
  const overrides = {
    getMe: vi.fn().mockResolvedValue({}),                     // aktif oturum yok → user null → Login
    login: vi.fn().mockResolvedValue({ success: true, username: 'ADMIN', system_role: 'ADMIN' }),
  }
  function deepMock() {
    const cache = new Map()
    return new Proxy(function () {}, {
      get(_t, key) {
        if (key === 'then') return undefined                  // Promise sanılmasın
        if (typeof key !== 'string') return undefined
        if (key in overrides) return overrides[key]
        if (!cache.has(key)) cache.set(key, deepMock())
        return cache.get(key)
      },
      apply() { return Promise.resolve({ success: true, data: [] }) },
    })
  }
  return {
    api: deepMock(),
    __loginMock: overrides.login,
    formatDate: (v) => String(v ?? ''),
    formatDateSec: (v) => String(v ?? ''),
  }
})

import App from '../App.jsx'
import { api } from '../api/client'

describe('App login geçişi — hook sırası regresyonu', () => {
  beforeEach(() => {
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
    window.history.replaceState({}, '', '/')
  })
  afterEach(() => {
    window.history.replaceState({}, '', '/')
  })

  it('girişsiz render → login → dashboard render ÇÖKMEDEN tamamlanır (Rendered more hooks regresyonu)', async () => {
    render(<App />)

    // 1) Girişsiz: erken-return yolundan Login formu basılır (hook sayısı: N).
    await screen.findByLabelText(/username|kullanıcı/i)
    const userInput = document.getElementById('lp-user')
    fireEvent.change(userInput, { target: { value: 'ADMIN' } })
    fireEvent.change(document.getElementById('lp-pass'), { target: { value: 'pw' } })

    // 2) Giriş: aynı App instance'ı user state'iyle yeniden render edilir (hook sayısı: N+M).
    //    Hatalı sürümde React tam burada "Rendered more hooks" ile fırlatıyordu.
    fireEvent.submit(userInput.closest('form'))
    await expect(screen.findByText(/^v\d+\./)).resolves.toBeDefined()   // Nav sürüm rozeti = girişli kabuk

    expect(api.login).toHaveBeenCalled()
    // Login formu artık DOM'da değil → geçiş gerçekten yaşandı (erken-return değil girişli dal).
    expect(document.getElementById('lp-pass')).toBeNull()
  })
})
