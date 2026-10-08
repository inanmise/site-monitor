import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { api } from '../api/client.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { ApiError, clearErrorRegistry, lookupErrorInfo, statusMessage, networkMessage } from '../utils/errorMessages.js'

/**
 * İstemci hata normalleştirmesi (2026-10-08): HTTP hata gövdeleri, JSON olmayan yanıtlar, ağ hatası, zaman aşımı ve
 * iptal kullanıcıya hazır metinle döner; teknik künye (durum · kod · istek kimliği) taşınır. 401 / bakım / pasif hesap
 * akışları DEĞİŞMEZ (clientMaintenance / accountInactive testleri ayrıca korur).
 */
function headers(map = {}) {
  const low = Object.fromEntries(Object.entries(map).map(([k, v]) => [k.toLowerCase(), v]))
  return { get: (n) => low[String(n).toLowerCase()] ?? null }
}

function respond(status, body, hdrs = {}) {
  global.fetch = vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    headers: headers(hdrs),
    json: body instanceof Error ? () => Promise.reject(body) : () => Promise.resolve(body),
  })
}

beforeEach(() => {
  clearErrorRegistry()
  try { localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  try { sessionStorage.removeItem('sm.session.active') } catch { /* yok */ }
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
  try { localStorage.removeItem(LANG_STORAGE_KEY) } catch { /* yok */ }
})

describe('HTTP hata gövdesi', () => {
  it('anlamlı sunucu metni korunur; künye (durum, kod, istek kimliği başlıktan) sayılamaz errorInfo\'da', async () => {
    respond(403, { success: false, error: 'Bu takımın izlemesini düzenleyemezsiniz', code: 'FORBIDDEN' }, { 'X-Request-Id': 'rid-403' })
    const res = await api.admin.getInventory()
    expect(res).toEqual({ success: false, error: 'Bu takımın izlemesini düzenleyemezsiniz', code: 'FORBIDDEN' })   // gövde şekli aynı
    expect(res.errorInfo).toEqual({ status: 403, code: 'FORBIDDEN', requestId: 'rid-403' })
    expect(lookupErrorInfo(res.error)).toEqual(res.errorInfo)
  })

  it('withStatus isteyen çağrıda gövdeye sayılabilir status eklenir (eski sözleşme)', async () => {
    respond(429, { success: false, error: 'Dakikada en çok 3 test gönderebilirsiniz' })
    const res = await api.me.pushSelfTest()
    expect(res.status).toBe(429)
    expect(res.error).toBe('Dakikada en çok 3 test gönderebilirsiniz')
  })

  it('teknik metin (yığın izi / sınıf adı) açıklayıcı metinle değişir; gövdedeki request_id künyeye girer', async () => {
    respond(500, { success: false, error: 'java.lang.NullPointerException at com.sitemonitor.X', request_id: 'abc' })
    const res = await api.admin.getInventory()
    expect(res.error).toBe(statusMessage(500, { lang: 'tr' }))
    expect(res.error).not.toMatch(/NullPointer|java\./)
    expect(res.errorInfo).toEqual({ status: 500, requestId: 'abc' })
  })

  it('jenerik "Sunucu hatası" / boş gövde duruma göre açıklanır (EN arayüz → EN metin)', async () => {
    localStorage.setItem(LANG_STORAGE_KEY, 'en')
    respond(503, { success: false, error: 'Sunucu hatası' })
    expect((await api.admin.getInventory()).error).toBe(statusMessage(503, { lang: 'en' }))
    respond(404, {})
    expect((await api.admin.getInventory()).error).toBe(statusMessage(404, { lang: 'en' }))
  })

  it('429 Retry-After başlığı bekleme süresini metne yazar', async () => {
    respond(429, { success: false }, { 'Retry-After': '30' })
    const res = await api.admin.getInventory()
    expect(res.error).toContain('30 saniye')
  })

  it('kod biçimli metin (VERSION_CONFLICT) korunur — ekranlar metinle dallanıyor', async () => {
    respond(409, { success: false, error: 'VERSION_CONFLICT' })
    const res = await api.admin.getInventory()
    expect(res.error).toBe('VERSION_CONFLICT')
    expect(res.errorInfo).toEqual({ status: 409, code: 'VERSION_CONFLICT' })
  })

  it('başarılı gövdeye dokunulmaz (status/künye eklenmez)', async () => {
    respond(200, { success: true, data: [] })
    const res = await api.admin.getInventory()
    expect(res).toEqual({ success: true, data: [] })
    expect(res.errorInfo).toBeUndefined()
  })

  it('JSON olmayan 502 (proxy HTML sayfası) → açıklayıcı metin + HTTP_502 kodu + başlıktaki istek kimliği', async () => {
    respond(502, new SyntaxError('Unexpected token < in JSON at position 0'), { 'X-Request-Id': 'gw-1' })
    const res = await api.admin.getInventory()
    expect(res).toMatchObject({ success: false, status: 502, code: 'HTTP_502', error: statusMessage(502, { lang: 'tr' }) })
    expect(res.errorInfo).toEqual({ status: 502, code: 'HTTP_502', requestId: 'gw-1' })
  })
})

describe('yanıt gelmeyen durumlar', () => {
  it('ağ hatası: ham "Failed to fetch" yerine kullanıcı metinli ApiError (status 0, cause korunur)', async () => {
    const cause = new TypeError('Failed to fetch')
    global.fetch = vi.fn().mockRejectedValue(cause)
    const err = await api.admin.getInventory().catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.message).not.toMatch(/Failed to fetch/)
    expect(err.message).toBe(networkMessage({ lang: 'tr', offline: false }))
    expect(err.status).toBe(0)
    expect(['NETWORK_ERROR', 'OFFLINE']).toContain(err.code)
    expect(err.cause).toBe(cause)
    expect(lookupErrorInfo(err.message)).toEqual({ status: 0, code: err.code })
  })

  it('zaman aşımı (getMe): yumuşak yük + REQUEST_TIMEOUT + açıklayıcı metin', async () => {
    vi.useFakeTimers()
    global.fetch = vi.fn((url, opts) => new Promise((_, reject) => {
      opts.signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })))
    }))
    const p = api.getMe()
    await vi.advanceTimersByTimeAsync(15000)
    const res = await p
    expect(res).toMatchObject({ success: false, status: 0, code: 'REQUEST_TIMEOUT', error: networkMessage({ lang: 'tr', kind: 'timeout' }) })
  })

  it('çağıranın iptali "iptal edildi", üst süre sınırı "zaman aşımı" metnini alır', async () => {
    global.fetch = vi.fn((url, opts) => new Promise((_, reject) => {
      const s = opts.signal
      const fail = () => reject(s.reason ?? Object.assign(new Error('aborted'), { name: 'AbortError' }))
      if (s.aborted) fail(); else s.addEventListener('abort', fail)
    }))
    const user = new AbortController()
    const p = api.monitoring.diagnosePort(7, {}, { signal: user.signal })
    user.abort()
    expect(await p).toMatchObject({ success: false, status: 0, code: 'REQUEST_ABORTED' })

    vi.useFakeTimers()
    const q = api.monitoring.diagnosePort(7, {}, { signal: new AbortController().signal })
    await vi.advanceTimersByTimeAsync(75000)
    expect(await q).toMatchObject({ success: false, status: 0, code: 'REQUEST_TIMEOUT' })
  })
})

describe('akışlar değişmez', () => {
  it('401 gövdesi normalleştirilmez: null döner (giriş sayfası / yönlendirme akışı aynen)', async () => {
    respond(401, { success: false, error: 'Not authenticated' })
    expect(await api.admin.getInventory()).toBeNull()
  })

  it('login: X-Lang gönderir; ağ hatasında açıklayıcı metin + networkError', async () => {
    respond(200, { success: true })
    await api.login('u', 'p')
    expect(global.fetch.mock.calls[0][1].headers['X-Lang']).toBe('tr')

    global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    const res = await api.login('u', 'p')
    expect(res).toMatchObject({ success: false, status: 0, networkError: true })
    expect(res.error).toBe(networkMessage({ lang: 'tr', kind: 'network' }))
  })

  it('login: JSON olmayan 504 → açıklayıcı ağ geçidi metni', async () => {
    respond(504, new SyntaxError('Unexpected token <'))
    const res = await api.login('u', 'p')
    expect(res.error).toBe(statusMessage(504, { lang: 'tr' }))
  })
})
