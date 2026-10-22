import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  LOGOUT_TIMEOUT_MS, PENDING_LOGOUT_KEY, clearLogoutPending, isLogoutPending, logoutConfirmed, markLogoutPending,
  settlePendingLogout, startPendingLogoutRetry, tryServerLogout,
} from '../utils/pendingLogout.js'

/**
 * Bekleyen sunucu çıkışı (2026-10-22, UIM-1): `/logout` düşse de istemci oturumu kapanır; işaret (jeton) bırakılır ve
 * sınırlı biçimde yeniden denenir. Geç biten eski istek SONRAKİ çıkışın işaretini silemez.
 */
describe('pendingLogout', () => {
  beforeEach(() => { localStorage.clear() })
  afterEach(() => { vi.useRealTimers() })

  it('logoutConfirmed: {success:true} ve 401 (null) onay; ağ hatası / 5xx / undefined değil', () => {
    expect(logoutConfirmed({ success: true })).toBe(true)
    expect(logoutConfirmed(null)).toBe(true)
    expect(logoutConfirmed({ success: false, status: 502 })).toBe(false)
    expect(logoutConfirmed(undefined)).toBe(false)
  })

  it('tryServerLogout reddetmez: ret → ok false; asılı istek süre dolunca ok false, sonradan biten `late` onaylar', async () => {
    expect((await tryServerLogout(() => Promise.reject(new TypeError('Failed to fetch')))).ok).toBe(false)
    vi.useFakeTimers()
    let resolve
    const p = tryServerLogout(() => new Promise((r) => { resolve = r }))
    await vi.advanceTimersByTimeAsync(LOGOUT_TIMEOUT_MS + 10)
    const { ok, late } = await p
    expect(ok).toBe(false)
    resolve({ success: true })
    expect(await late).toBe(true)
  })

  it('işaret jetonu: eski jetonla silme yeni işareti SİLMEZ; jetonsuz silme her şeyi siler', () => {
    const a = markLogoutPending(1)
    const b = markLogoutPending(2)
    expect(a).not.toBe(b)
    clearLogoutPending(a)
    expect(isLogoutPending()).toBe(true)
    clearLogoutPending(b)
    expect(isLogoutPending()).toBe(false)
    markLogoutPending()
    clearLogoutPending()
    expect(localStorage.getItem(PENDING_LOGOUT_KEY)).toBeNull()
  })

  it('settlePendingLogout: işaret yoksa çağrı YOK; başarıda işaret silinir; başarısızlıkta kalır', async () => {
    const fn = vi.fn(() => Promise.resolve({ success: true }))
    expect(await settlePendingLogout(fn)).toBe(true)
    expect(fn).not.toHaveBeenCalled()
    markLogoutPending()
    expect(await settlePendingLogout(fn)).toBe(true)
    expect(isLogoutPending()).toBe(false)
    markLogoutPending()
    expect(await settlePendingLogout(() => Promise.reject(new Error('x')))).toBe(false)
    expect(isLogoutPending()).toBe(true)
  })

  it('yeniden deneme: aralıkla ve `online` olayında; başarıda durur; sınırı aşmaz', async () => {
    vi.useFakeTimers()
    markLogoutPending()
    let calls = 0
    const fn = vi.fn(() => { calls++; return calls < 3 ? Promise.reject(new Error('down')) : Promise.resolve({ success: true }) })
    const stop = startPendingLogoutRetry(fn, { intervalMs: 1000, max: 5, timeoutMs: 100 })
    await vi.advanceTimersByTimeAsync(1000)
    expect(fn).toHaveBeenCalledTimes(1)
    window.dispatchEvent(new Event('online'))
    await vi.advanceTimersByTimeAsync(0)
    expect(fn).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1000)
    expect(fn).toHaveBeenCalledTimes(3)
    expect(isLogoutPending()).toBe(false)
    await vi.advanceTimersByTimeAsync(5000)
    expect(fn).toHaveBeenCalledTimes(3)   // durdu
    stop()

    markLogoutPending()
    const never = vi.fn(() => Promise.reject(new Error('down')))
    startPendingLogoutRetry(never, { intervalMs: 1000, max: 2, timeoutMs: 100 })
    await vi.advanceTimersByTimeAsync(10_000)
    expect(never).toHaveBeenCalledTimes(2)   // sınırlı — sonsuz döngü yok
  })
})
