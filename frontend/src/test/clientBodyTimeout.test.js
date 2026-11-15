import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { api, DEFAULT_READ_TIMEOUT_MS, fetchWithTimeout } from '../api/client.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

/**
 * Yanıt GÖVDESİ de süre sınırlı (2026-11-15, kullanıcı kuralı "zaman aşımı olmayan istek yok"). Eski `fetchWithTimeout`
 * zamanlayıcıyı başlıklar gelince sökerdi; `request()` ardından `res.json()`'ı süresiz beklerdi — başlıklardan sonra duran
 * bir bağlantı (yarı açık soket) isteği SONSUZA dek asardı ve ona katılan ortak istekler (çevrimiçi sayısı, Panom kutuları,
 * bakım rozeti, 7/24 durumu) sekmenin ömrü boyunca beklerdi. Artık son tarih gövde okunana dek sürer.
 */

/** Başlıklar hemen gelir; `json()` YALNIZ iptalde reddeder (gerçek tarayıcı gibi) ya da hiç bitmez (sinyali dinlemeyen). */
function headersThenStall({ honoursAbort = true } = {}) {
  return vi.fn((url, init) => Promise.resolve({
    ok: true, status: 200, headers: { get: () => null },
    json: () => new Promise((_, reject) => {
      if (honoursAbort) init?.signal?.addEventListener('abort', () => reject(init.signal.reason))
    }),
  }))
}

describe('gövde okuması son tarihle sınırlı', () => {
  const realFetch = global.fetch
  beforeEach(() => {
    vi.useFakeTimers()
    try { localStorage.setItem(LANG_STORAGE_KEY, 'en') } catch { /* yok */ }
  })
  afterEach(() => {
    vi.useRealTimers()
    global.fetch = realFetch
    try { localStorage.removeItem(LANG_STORAGE_KEY) } catch { /* yok */ }
    sessionStorage.clear()
  })

  it.each([
    ['gövde iptalde reddeder (tarayıcı)', true],
    ['gövde sinyali hiç dinlemez (sahte / eski tarayıcı)', false],
  ])('GET: başlıklar gelir, gövde durur → 90 sn sonra yumuşak "timeout" yükü (%s)', async (_n, honoursAbort) => {
    global.fetch = headersThenStall({ honoursAbort })
    const p = api.getCertificates()
    let settled = false
    p.then(() => { settled = true })
    await vi.advanceTimersByTimeAsync(DEFAULT_READ_TIMEOUT_MS - 1000)
    expect(settled).toBe(false)
    await vi.advanceTimersByTimeAsync(1500)
    const res = await p
    expect(res).toEqual(expect.objectContaining({ success: false, status: 0, code: 'REQUEST_TIMEOUT' }))
    expect(res.error).toMatch(/didn.t respond in time/)
  })

  it('çağıran gövde okunurken iptal ederse "iptal edildi" yükü (zaman aşımı değil)', async () => {
    global.fetch = headersThenStall()
    const ctl = new AbortController()
    const p = api.techErrors.mailPreview(7, { signal: ctl.signal })
    await vi.advanceTimersByTimeAsync(1000)
    ctl.abort()
    expect(await p).toEqual(expect.objectContaining({ success: false, code: 'REQUEST_ABORTED' }))
  })

  it('başarılı istekten sonra bekleyen son tarih zamanlayıcısı kalmaz', async () => {
    global.fetch = vi.fn(() => Promise.resolve({
      ok: true, status: 200, headers: { get: () => null }, json: () => Promise.resolve({ success: true, data: [] }),
    }))
    // Taban: beforeEach'teki localStorage yazımı jsdom'da sahte saate bir olay zamanlayıcısı koyar — istemciden gelmez
    const base = vi.getTimerCount()
    expect(await api.getCertificates()).toEqual({ success: true, data: [] })
    expect(vi.getTimerCount()).toBe(base)
  })

  it('giriş: başlıklar gelir, gövde durur → 15 sn sonra çevrilmiş zaman aşımı; oturum bayrağı yazılmaz', async () => {
    global.fetch = headersThenStall({ honoursAbort: false })
    const p = api.login('u', 'p')
    await vi.advanceTimersByTimeAsync(15_500)
    const res = await p
    expect(res).toEqual(expect.objectContaining({ success: false, status: 0 }))
    expect(res.error).toMatch(/didn.t respond in time/)
    expect(sessionStorage.getItem('sm.session.active')).toBeNull()
  })

  it('fetchWithTimeout: dönen yanıtın json() okuması da son tarihle sınırlı (TimeoutError)', async () => {
    global.fetch = headersThenStall({ honoursAbort: false })
    const res = await fetchWithTimeout('/api/x', {}, 5000)
    const body = res.json()
    const caught = body.catch((e) => e)
    await vi.advanceTimersByTimeAsync(5500)
    expect((await caught).name).toBe('TimeoutError')
  })

  it('fetchWithTimeout: 0 artık sınırsız DEĞİL — varsayılan 15 sn', async () => {
    global.fetch = vi.fn(() => new Promise(() => {}))
    const p = fetchWithTimeout('/api/x', {}, 0)
    const caught = p.catch((e) => e)
    await vi.advanceTimersByTimeAsync(15_500)
    expect((await caught).name).toBe('TimeoutError')
  })
})
