import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { api } from '../api/client.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { clearErrorRegistry } from '../utils/errorMessages.js'

/**
 * Görsel aracı — sunucu uçlarının istemcisi (sözleşme §1): görsel ucu başarıda İKİLİ gövde döner (ad / tür / ölçü
 * başlıklarda), hatada JSON hata sözleşmesi (kod, `Retry-After`, `max_bytes`, `http_status` …); iptal hata değildir.
 * Not projeksiyonu olağan JSON ucudur (hata gövdesi HTTP durumuyla döner — eski sunucu ayırt edilebilsin).
 */
const headers = (map) => ({ get: (h) => map[String(h).toLowerCase()] ?? null })
const imageResponse = (map = {}) => ({
  ok: true, status: 200,
  headers: headers({ 'content-disposition': 'attachment; filename="hero.jpg"', 'x-image-type': 'jpeg', 'x-image-width': '3000', 'x-image-height': '2000', ...map }),
  blob: () => Promise.resolve(new Blob([new Uint8Array(1234)], { type: 'image/jpeg' })),
})
const jsonError = (status, body, map = {}) => ({ ok: false, status, headers: headers(map), json: () => Promise.resolve(body) })

let originalFetch
beforeEach(() => {
  originalFetch = global.fetch
  clearErrorRegistry()
  try { localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
})
afterEach(() => {
  vi.restoreAllMocks()
  global.fetch = originalFetch
  try { localStorage.removeItem(LANG_STORAGE_KEY) } catch { /* yok */ }
})

describe('görsel ucu (POST …/resource-scans/{scan}/image)', () => {
  it('istek: POST + JSON gövde { url } (adres sorguya / günlüğe yazılmaz), oturum çerezi, arayüz dili', async () => {
    global.fetch = vi.fn().mockResolvedValue(imageResponse())
    await api.monitoring.resourceScanImage(100, 12, 'https://ornek.example.com/img/hero.jpg?v=3')
    const [url, init] = global.fetch.mock.calls[0]
    expect(url).toBe('/api/monitoring/page/100/resource-scans/12/image')
    expect(init.method).toBe('POST')
    expect(init.credentials).toBe('include')
    expect(init.headers).toMatchObject({ 'Content-Type': 'application/json', 'X-Lang': 'tr' })
    expect(JSON.parse(init.body)).toEqual({ url: 'https://ornek.example.com/img/hero.jpg?v=3' })
  })

  it('başarı: ikili gövde Blob olarak; ad, tür ve ölçü başlıklardan (bilinmeyen / bozuk başlık → null, uydurulmaz)', async () => {
    global.fetch = vi.fn().mockResolvedValue(imageResponse())
    const res = await api.monitoring.resourceScanImage(100, 12, 'https://ornek.example.com/a.jpg')
    expect(res).toMatchObject({ success: true, name: 'hero.jpg', type: 'jpeg', width: 3000, height: 2000 })
    expect(res.blob.size).toBe(1234)
    global.fetch = vi.fn().mockResolvedValue(imageResponse({ 'content-disposition': "attachment; filename*=UTF-8''kapak%20g%C3%B6rseli.png", 'x-image-type': 'PNG', 'x-image-width': 'abc', 'x-image-height': '0' }))
    expect(await api.monitoring.resourceScanImage(100, 12, 'u')).toMatchObject({ success: true, name: 'kapak görseli.png', type: 'png', width: null, height: null })
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, blob: () => Promise.resolve(new Blob(['x'])) })   // başlıksız sahte
    expect(await api.monitoring.resourceScanImage(100, 12, 'u')).toMatchObject({ success: true, name: null, type: null, width: null, height: null })
  })

  it('hata: JSON hata gövdesi kodu, HTTP durumu ve alanlarıyla döner (413 sınır, 502 neden, 409 maskeli)', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonError(413, { success: false, code: 'RESOURCE_IMAGE_TOO_LARGE', error: 'Görsel indirme sınırından büyük.', max_bytes: 26214400 }))
    expect(await api.monitoring.resourceScanImage(100, 12, 'u')).toMatchObject({ success: false, status: 413, code: 'RESOURCE_IMAGE_TOO_LARGE', max_bytes: 26214400 })
    global.fetch = vi.fn().mockResolvedValue(jsonError(502, { success: false, code: 'RESOURCE_IMAGE_FETCH_FAILED', error: 'Site görseli vermedi.', reason: 'CONNECT_TIMEOUT', http_status: null }))
    expect(await api.monitoring.resourceScanImage(100, 12, 'u')).toMatchObject({ success: false, status: 502, code: 'RESOURCE_IMAGE_FETCH_FAILED', reason: 'CONNECT_TIMEOUT' })
    global.fetch = vi.fn().mockResolvedValue(jsonError(409, { success: false, code: 'RESOURCE_IMAGE_URL_MASKED', error: 'Adres maskeli.' }))
    expect(await api.monitoring.resourceScanImage(100, 12, 'u')).toMatchObject({ success: false, status: 409, code: 'RESOURCE_IMAGE_URL_MASKED' })
  })

  it('429: bekleme süresi başlıktan, yoksa gövdeden okunur (toplu indirme buna uyar)', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonError(429, { success: false, code: 'RATE_LIMITED', error: 'Çok fazla istek.' }, { 'retry-after': '37' }))
    expect(await api.monitoring.resourceScanImage(100, 12, 'u')).toMatchObject({ success: false, status: 429, code: 'RATE_LIMITED', retryAfter: 37 })
    global.fetch = vi.fn().mockResolvedValue(jsonError(429, { success: false, code: 'RESOURCE_IMAGE_BUSY', error: 'Meşgul.', retry_after_seconds: 2 }))
    expect(await api.monitoring.resourceScanImage(100, 12, 'u')).toMatchObject({ success: false, status: 429, code: 'RESOURCE_IMAGE_BUSY', retryAfter: 2 })
  })

  it('JSON olmayan hata yanıtı (vekil sayfası) ve ağ hatası çevrilmiş açıklayıcı metinle döner — ham tarayıcı metni yok', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 502, headers: headers({}), json: () => Promise.reject(new Error('Unexpected token <')) })
    const proxy = await api.monitoring.resourceScanImage(100, 12, 'u')
    expect(proxy).toMatchObject({ success: false, status: 502 })
    expect(proxy.error).not.toMatch(/Unexpected token/)
    expect(proxy.error.length).toBeGreaterThan(20)
    global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    const net = await api.monitoring.resourceScanImage(100, 12, 'u')
    expect(net).toMatchObject({ success: false, status: 0, code: 'NETWORK_ERROR' })
    expect(net.error).not.toMatch(/Failed to fetch/)
  })

  it('iptal (AbortSignal) hata değildir: { aborted: true }; gövde okunurken kesilse de', async () => {
    const ctl = new AbortController()
    global.fetch = vi.fn((_u, init) => new Promise((_, reject) => { init.signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' }))) }))
    const p = api.monitoring.resourceScanImage(100, 12, 'u', { signal: ctl.signal })
    ctl.abort()
    expect(await p).toMatchObject({ success: false, status: 0, aborted: true })
    const ctl2 = new AbortController()
    global.fetch = vi.fn().mockResolvedValue({ ...imageResponse(), blob: () => { ctl2.abort(); return Promise.reject(Object.assign(new Error('aborted'), { name: 'AbortError' })) } })
    expect(await api.monitoring.resourceScanImage(100, 12, 'u', { signal: ctl2.signal })).toMatchObject({ success: false, aborted: true })
  })
})

describe('not projeksiyonu ucu (POST …/image-projection)', () => {
  it('gövde aynen gider; başarı verisi ve hata durumu (eski sunucu 404) çağırana ulaşır', async () => {
    const data = { capped: false, applied: 2, before: { grade: 61 }, after: { grade: 78 } }
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, headers: headers({ 'content-type': 'application/json' }), json: () => Promise.resolve({ success: true, data }), text: () => Promise.resolve(JSON.stringify({ success: true, data })) })
    const ok = await api.monitoring.resourceScanImageProjection(100, 12, { use_measured: true })
    expect(ok).toMatchObject({ success: true, data })
    const [url, init] = global.fetch.mock.calls[0]
    expect(url).toBe('/api/monitoring/page/100/resource-scans/12/image-projection')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body)).toEqual({ use_measured: true })
    const body = { success: false, code: 'NOT_FOUND', error: 'Bulunamadı.' }
    global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 404, headers: headers({ 'content-type': 'application/json' }), json: () => Promise.resolve(body), text: () => Promise.resolve(JSON.stringify(body)) })
    expect(await api.monitoring.resourceScanImageProjection(100, 12, { use_measured: true })).toMatchObject({ success: false, status: 404, code: 'NOT_FOUND' })
  })
})
