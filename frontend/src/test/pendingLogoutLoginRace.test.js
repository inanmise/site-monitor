import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { api } from '../api/client.js'
import {
  LOGIN_LOGOUT_WAIT_MS, LOGOUT_INFLIGHT_PREFIX, LOGIN_HOLD_PREFIX, holdLoginStart, isLogoutPending, markLogoutPending,
  settleLogoutsBeforeLogin, settlePendingLogout, tryServerLogout, hasInflightLogout,
} from '../utils/pendingLogout.js'

/**
 * Bekleyen çıkış ↔ yeni giriş yarışı (2026-10-22, erişim incelemesi D1). `login()` bekleyen `/logout`'u 3 sn bekleyip
 * vazgeçiyordu; o istek girişten SONRA biterse yanıtındaki `Set-Cookie: SESSION=; Max-Age=0` ve remember-me silmesi yeni
 * oturumun çerezlerini siliyordu — taze giriş sessizce kayboluyordu. Artık giriş isteği, bu sekmenin uçuştaki çıkışları bitmeden
 * ya da İPTAL edilmeden (iptal edilen isteğin yanıtı tarayıcıda uygulanmaz) BAŞLAMAZ; öteki sekmelerin duyurduğu uçuştaki
 * çıkışlar beklenir; giriş sürerken yeni yeniden deneme başlamaz. Başarılı girişte işaret hemen düşer.
 */
const okResponse = (body) => ({ ok: true, status: 200, headers: { get: () => null }, json: () => Promise.resolve(body) })
const abortError = () => Object.assign(new Error('aborted'), { name: 'AbortError' })

/** Yalnız iptal edilince sonuçlanan çıkış isteği (yanıtı hiç gelmeyen ağ). */
function hangingLogout(log) {
  return (signal) => new Promise((resolve, reject) => {
    log.push('logout-start')
    signal?.addEventListener('abort', () => { log.push('logout-aborted'); reject(abortError()) })
  })
}

let originalFetch
beforeEach(() => {
  localStorage.clear()
  try { sessionStorage.removeItem('sm.session.active') } catch { /* yok */ }
  originalFetch = global.fetch
})
afterEach(() => {
  vi.useRealTimers()
  global.fetch = originalFetch
  localStorage.clear()
})

describe('settleLogoutsBeforeLogin', () => {
  it('hiçbir şey yoksa hemen döner (girişi geciktirmez)', async () => {
    const t0 = Date.now()
    await settleLogoutsBeforeLogin(vi.fn())
    expect(Date.now() - t0).toBeLessThan(50)
  })

  it('bu sekmenin uçuştaki çıkışı (elle çıkışın yavaş isteği) bütçe dolunca İPTAL edilir; giriş ondan sonra başlar', async () => {
    vi.useFakeTimers()
    const log = []
    const manual = tryServerLogout(hangingLogout(log))       // elle çıkış: 3 sn bekler, istek uçuşta kalır
    await vi.advanceTimersByTimeAsync(3100)
    expect((await manual).ok).toBe(false)
    expect(hasInflightLogout()).toBe(true)
    const settle = settleLogoutsBeforeLogin(vi.fn(), 1000).then(() => log.push('login-may-start'))
    await vi.advanceTimersByTimeAsync(1600)
    await settle
    expect(log).toEqual(['logout-start', 'logout-aborted', 'login-may-start'])
    expect(hasInflightLogout()).toBe(false)
  })

  it('iptal edilen isteğin sonradan gelen "başarılı" sonucu onay sayılmaz (yanıt uygulanmadı)', async () => {
    vi.useFakeTimers()
    let resolveLate
    const fn = () => new Promise((r) => { resolveLate = r })   // sinyali yok sayan istek
    const { late } = await (async () => { const p = tryServerLogout(fn, 50, { abortMs: 100 }); await vi.advanceTimersByTimeAsync(60); return p })()
    await vi.advanceTimersByTimeAsync(100)   // iptal anı geçti
    resolveLate({ success: true })
    expect(await late).toBe(false)
  })

  it('öteki sekmenin duyurduğu uçuştaki çıkış bitene kadar beklenir (en geç kendi iptal anına dek)', async () => {
    const key = `${LOGOUT_INFLIGHT_PREFIX}baska-sekme`
    localStorage.setItem(key, String(Date.now() + 5000))
    setTimeout(() => localStorage.removeItem(key), 200)
    const t0 = Date.now()
    await settleLogoutsBeforeLogin(vi.fn(), 100)
    const waited = Date.now() - t0
    expect(waited).toBeGreaterThanOrEqual(180)
    expect(waited).toBeLessThan(2000)
  })

  it('süresi dolmuş duyuru beklenmez ve temizlenir', async () => {
    const key = `${LOGOUT_INFLIGHT_PREFIX}olu-sekme`
    localStorage.setItem(key, String(Date.now() - 1))
    const t0 = Date.now()
    await settleLogoutsBeforeLogin(vi.fn())
    expect(Date.now() - t0).toBeLessThan(50)
    expect(localStorage.getItem(key)).toBeNull()
  })
})

describe('giriş işareti — süren girişte yeni çıkış denemesi başlamaz', () => {
  it('yeniden deneme / açılış çıkışı (respectLoginHold) işaret varken istek ATMAZ; işaret kalkınca atar', async () => {
    markLogoutPending()
    const fn = vi.fn(() => Promise.resolve({ success: true }))
    const release = holdLoginStart()
    expect(Object.keys(localStorage).some((k) => k.startsWith(LOGIN_HOLD_PREFIX))).toBe(true)
    expect(await settlePendingLogout(fn, 100, { respectLoginHold: true })).toBe(false)
    expect(fn).not.toHaveBeenCalled()
    release()
    expect(await settlePendingLogout(fn, 100, { respectLoginHold: true })).toBe(true)
    expect(fn).toHaveBeenCalledTimes(1)
  })
})

describe('api.login — bekleyen çıkışla yarış (D1)', () => {
  it('asılı /logout İPTAL edilmeden /login başlamaz; başarılı girişte işaret hemen düşer', async () => {
    vi.useFakeTimers()
    markLogoutPending()
    const log = []
    global.fetch = vi.fn((url, init) => {
      const u = String(url)
      if (u.endsWith('/logout')) {
        log.push('logout-sent')
        return new Promise((_, reject) => init?.signal?.addEventListener('abort', () => { log.push('logout-aborted'); reject(abortError()) }))
      }
      if (u.endsWith('/login')) { log.push('login-sent'); return Promise.resolve(okResponse({ success: true, username: 'kisi' })) }
      return Promise.resolve(okResponse({ success: true }))
    })
    const p = api.login('kisi', 'parola')
    await vi.advanceTimersByTimeAsync(LOGIN_LOGOUT_WAIT_MS + 1000)
    const res = await p
    expect(res.success).toBe(true)
    expect(log).toEqual(['logout-sent', 'logout-aborted', 'login-sent'])
    expect(isLogoutPending()).toBe(false)
    expect(Object.keys(localStorage).some((k) => k.startsWith(LOGIN_HOLD_PREFIX))).toBe(false)   // işaret bırakıldı
  })

  it('bekleyen çıkış hızlı onaylanırsa giriş hemen ardından gider (beklemesiz)', async () => {
    markLogoutPending()
    const log = []
    global.fetch = vi.fn((url) => {
      const u = String(url)
      log.push(u.endsWith('/logout') ? 'logout' : u.endsWith('/login') ? 'login' : u)
      return Promise.resolve(okResponse({ success: true }))
    })
    const res = await api.login('kisi', 'parola')
    expect(res.success).toBe(true)
    expect(log).toEqual(['logout', 'login'])
    expect(isLogoutPending()).toBe(false)
  })

  it('bekleyen çıkış yoksa /logout atılmaz; başarısız giriş işareti silmez', async () => {
    const log = []
    global.fetch = vi.fn((url) => { log.push(String(url).endsWith('/login') ? 'login' : 'other'); return Promise.resolve(okResponse({ success: false, error: 'x' })) })
    await api.login('kisi', 'yanlis')
    expect(log).toEqual(['login'])
    markLogoutPending()
    global.fetch = vi.fn((url) => Promise.resolve(String(url).endsWith('/logout') ? { ok: false, status: 502, headers: { get: () => null }, json: () => Promise.resolve({ success: false }) }
      : okResponse({ success: false, error: 'x' })))
    await api.login('kisi', 'yanlis')
    expect(isLogoutPending()).toBe(true)
  })
})
