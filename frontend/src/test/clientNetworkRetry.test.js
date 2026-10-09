import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { api, NETWORK_RETRY_DELAY_MS, isRetryableNetworkFailure, retryDelay } from '../api/client.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { ApiError, clearErrorRegistry, networkMessage } from '../utils/errorMessages.js'

/**
 * Geçici ağ kopmasında TEK yeniden deneme (2026-10-09, kullanıcı bildirimi: kurumsal yük dengeleyici arkasında açılışta
 * `GET /api/alerts/silent-domains` → `net::ERR_CONNECTION_RESET`). Yalnız GET / HEAD, yalnız fetch'in reddettiği ağ
 * hatası, yalnız bir kez; iptal, zaman aşımı, çevrimdışı, HTTP durumu ve yazma istekleri denenmez. İkinci deneme de
 * düşerse hata bugünkü ApiError'dır.
 */
const okResponse = (body = { success: true, data: [] }) => ({
  ok: true, status: 200, headers: { get: () => null }, json: () => Promise.resolve(body),
})
const reset = () => new TypeError('Failed to fetch')
const abortErr = () => Object.assign(new Error('aborted'), { name: 'AbortError' })

let originalFetch
beforeEach(() => {
  originalFetch = global.fetch
  clearErrorRegistry()
  try { localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  try { sessionStorage.removeItem('sm.session.active') } catch { /* yok */ }
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  global.fetch = originalFetch
  try { localStorage.removeItem(LANG_STORAGE_KEY) } catch { /* yok */ }
})

describe('request — geçici ağ hatasında tek yeniden deneme', () => {
  it('GET: bağlantı sıfırlanırsa kısa beklemeden sonra BİR kez yeniden dener ve yanıtı döner', async () => {
    vi.useFakeTimers()
    global.fetch = vi.fn()
      .mockRejectedValueOnce(reset())
      .mockResolvedValueOnce(okResponse({ success: true, data: ['a.example.com'] }))
    const p = api.getSilentAlertDomains()
    await vi.advanceTimersByTimeAsync(0)
    expect(global.fetch).toHaveBeenCalledTimes(1)          // bekleme bitmeden ikinci istek yok
    await vi.advanceTimersByTimeAsync(NETWORK_RETRY_DELAY_MS - 1)
    expect(global.fetch).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    const res = await p
    expect(res).toEqual({ success: true, data: ['a.example.com'] })
    expect(global.fetch).toHaveBeenCalledTimes(2)
    // İkinci deneme aynı adrese aynı seçeneklerle gider (X-Lang, credentials)
    expect(global.fetch.mock.calls[1][0]).toBe('/api/alerts/silent-domains')
    expect(global.fetch.mock.calls[1][1]).toMatchObject({ credentials: 'include', headers: { 'X-Lang': 'tr' } })
  })

  it('bekleme kısa ve sınırlı: 300–500 ms', () => {
    expect(NETWORK_RETRY_DELAY_MS).toBeGreaterThanOrEqual(300)
    expect(NETWORK_RETRY_DELAY_MS).toBeLessThanOrEqual(500)
  })

  it('ikinci deneme de düşerse bugünkü ApiError (NETWORK_ERROR) — üçüncü deneme YOK', async () => {
    const cause = reset()
    global.fetch = vi.fn().mockRejectedValue(cause)
    const err = await api.getCertificates().catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.status).toBe(0)
    expect(err.code).toBe('NETWORK_ERROR')
    expect(err.message).toBe(networkMessage({ lang: 'tr', offline: false }))
    expect(global.fetch).toHaveBeenCalledTimes(2)
  })

  it('yazma isteği (POST) asla yeniden denenmez', async () => {
    global.fetch = vi.fn().mockRejectedValue(reset())
    const err = await api.runScheduler().catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.code).toBe('NETWORK_ERROR')
    expect(global.fetch).toHaveBeenCalledTimes(1)
  })

  it('çevrimdışı (navigator.onLine=false) → denenmez, OFFLINE', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    global.fetch = vi.fn().mockRejectedValue(reset())
    const err = await api.getStats().catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.code).toBe('OFFLINE')
    expect(global.fetch).toHaveBeenCalledTimes(1)
  })

  it('iptal (AbortError) → denenmez, yumuşak REQUEST_ABORTED/TIMEOUT yükü', async () => {
    global.fetch = vi.fn().mockRejectedValue(abortErr())
    const res = await api.getCertificates()
    expect(res).toMatchObject({ success: false, status: 0 })
    expect(global.fetch).toHaveBeenCalledTimes(1)
  })

  it('zaman aşımı (90 sn varsayılan okuma sınırı) → denenmez, REQUEST_TIMEOUT', async () => {
    vi.useFakeTimers()
    global.fetch = vi.fn((url, opts) => new Promise((_, reject) => {
      opts.signal.addEventListener('abort', () => reject(abortErr()))
    }))
    const p = api.getCardExtras()
    await vi.advanceTimersByTimeAsync(90_000)
    await vi.advanceTimersByTimeAsync(NETWORK_RETRY_DELAY_MS * 2)
    expect(await p).toMatchObject({ success: false, status: 0, code: 'REQUEST_TIMEOUT' })
    expect(global.fetch).toHaveBeenCalledTimes(1)
  })

  it('HTTP hata durumu (503) yeniden denenmez — gövde bugünkü gibi normalleşir', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false, status: 503, headers: { get: () => null }, json: () => Promise.resolve({ success: false }),
    })
    const res = await api.getCertificates()
    expect(res).toMatchObject({ success: false })
    expect(global.fetch).toHaveBeenCalledTimes(1)
  })
})

describe('yeniden deneme kuralları (birim)', () => {
  const ctl = () => new AbortController()

  it('yalnız GET / HEAD ve yalnız gerçek ağ reddi', () => {
    expect(isRetryableNetworkFailure(reset(), 'GET', undefined)).toBe(true)
    expect(isRetryableNetworkFailure(reset(), 'HEAD', undefined)).toBe(true)
    for (const m of ['POST', 'PUT', 'PATCH', 'DELETE']) expect(isRetryableNetworkFailure(reset(), m, undefined)).toBe(false)
    expect(isRetryableNetworkFailure(abortErr(), 'GET', undefined)).toBe(false)
    expect(isRetryableNetworkFailure(Object.assign(new Error('t'), { name: 'TimeoutError' }), 'GET', undefined)).toBe(false)
  })

  it('çağıran iptal ettiyse denenmez', () => {
    const c = ctl(); c.abort()
    expect(isRetryableNetworkFailure(reset(), 'GET', c.signal)).toBe(false)
    expect(isRetryableNetworkFailure(reset(), 'GET', ctl().signal)).toBe(true)
  })

  it('bekleme çağıranın iptaliyle kesilir (AbortError) — ikinci istek gitmez', async () => {
    vi.useFakeTimers()
    const c = ctl()
    const p = retryDelay(NETWORK_RETRY_DELAY_MS, c.signal).then(() => 'bitti', (e) => e)
    await vi.advanceTimersByTimeAsync(100)
    c.abort()
    const out = await p
    expect(out?.name).toBe('AbortError')

    const pre = ctl(); pre.abort()
    expect((await retryDelay(NETWORK_RETRY_DELAY_MS, pre.signal).catch((e) => e))?.name).toBe('AbortError')

    const q = retryDelay(NETWORK_RETRY_DELAY_MS, ctl().signal)
    await vi.advanceTimersByTimeAsync(NETWORK_RETRY_DELAY_MS)
    await expect(q).resolves.toBeUndefined()
  })
})
