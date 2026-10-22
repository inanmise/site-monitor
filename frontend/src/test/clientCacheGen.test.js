import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  api, rememberCacheGen, cacheMinGenHeaders, CACHE_GEN_TTL_MS, __resetCacheGenForTests,
} from '../api/client.js'

/**
 * Yazdığını oku — önbellek kuşağı (2026-10-22, çok pod R2-A, MP-30). Yazma yanıtındaki `X-Cache-Gen` saklanır ve sonraki
 * GET'lerde `X-Cache-Min-Gen` olarak gider; istek öteki pod'a düşerse o pod önce önbelleğini boşaltır. Eski sunucu başlığı
 * hiç göndermez → istemci de göndermez (bugünkü istek baytları).
 */
function fetchReturning(headers = {}) {
  const low = Object.fromEntries(Object.entries(headers).map(([k, v]) => [k, v]))
  return vi.fn().mockResolvedValue({
    ok: true, status: 200,
    headers: { get: (n) => low[n] ?? null },
    json: async () => ({ success: true }),
  })
}

describe('api request → önbellek kuşağı (X-Cache-Gen / X-Cache-Min-Gen)', () => {
  const originalFetch = global.fetch
  beforeEach(() => { __resetCacheGenForTests() })
  afterEach(() => { global.fetch = originalFetch; __resetCacheGenForTests(); vi.useRealTimers() })

  it('eski sunucu (başlık yok): GET başlıksız gider', async () => {
    global.fetch = fetchReturning()
    await api.getMe()
    await api.getMe()
    for (const [, opts] of global.fetch.mock.calls) expect(opts.headers['X-Cache-Min-Gen']).toBeUndefined()
  })

  it('yazma yanıtının kuşağı saklanır ve sonraki GET X-Cache-Min-Gen ile gider; yazma isteği başlığı taşımaz', async () => {
    global.fetch = fetchReturning({ 'X-Cache-Gen': '41' })
    await api.checkDomain('a.example.com')
    global.fetch = fetchReturning()
    await api.getCertificates()
    expect(global.fetch.mock.calls[0][1].headers['X-Cache-Min-Gen']).toBe('41')
    expect(cacheMinGenHeaders('POST')).toEqual({})
    expect(cacheMinGenHeaders('PUT')).toEqual({})
    expect(cacheMinGenHeaders('HEAD')).toEqual({ 'X-Cache-Min-Gen': '41' })
  })

  it('en yüksek değer kazanır; bozuk / sayı olmayan / negatif değer yok sayılır', () => {
    rememberCacheGen('12', 1000)
    rememberCacheGen('7', 1100)
    rememberCacheGen('abc', 1200)
    rememberCacheGen('-3', 1200)
    rememberCacheGen('1e9', 1200)
    rememberCacheGen('application/json', 1200)
    rememberCacheGen(null, 1200)
    rememberCacheGen('9'.repeat(16), 1200)
    expect(cacheMinGenHeaders('GET', 1300)).toEqual({ 'X-Cache-Min-Gen': '12' })
    rememberCacheGen(' 15 ', 1400)
    expect(cacheMinGenHeaders('GET', 1500)).toEqual({ 'X-Cache-Min-Gen': '15' })
  })

  it('süre dolunca gönderilmez; dolduktan sonra gelen KÜÇÜK değer (sunucu sayacı sıfırlandı) benimsenir', () => {
    rememberCacheGen('500', 0)
    expect(cacheMinGenHeaders('GET', CACHE_GEN_TTL_MS - 1)).toEqual({ 'X-Cache-Min-Gen': '500' })
    expect(cacheMinGenHeaders('GET', CACHE_GEN_TTL_MS)).toEqual({})
    rememberCacheGen('3', CACHE_GEN_TTL_MS + 10)
    expect(cacheMinGenHeaders('GET', CACHE_GEN_TTL_MS + 20)).toEqual({ 'X-Cache-Min-Gen': '3' })
  })

  it('hata yanıtındaki kuşak da saklanır (kısmi yazma boşaltma yaptıysa)', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false, status: 409,
      headers: { get: (n) => (n === 'X-Cache-Gen' ? '8' : null) },
      json: async () => ({ success: false, error: 'çakışma — kayıt başka biri tarafından değişti, yeniden yükleyin' }),
    })
    await api.checkDomain('a.example.com')
    expect(cacheMinGenHeaders('GET')).toEqual({ 'X-Cache-Min-Gen': '8' })
  })
})
