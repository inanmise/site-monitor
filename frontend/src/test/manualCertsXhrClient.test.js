import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../utils/accountInactive.js', async (importOriginal) => {
  const mod = await importOriginal()
  return { ...mod, assignLocation: vi.fn() }
})

import { api, getRecentFailures } from '../api/client.js'
import { assignLocation } from '../utils/accountInactive.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { MAINTENANCE_EVENT, resetMaintenanceSignal } from '../utils/systemMaintenance.js'
import { extractedFormData } from '../components/manualcert/manualCertModel.js'

/**
 * `api.manualCerts.*` yükleme uçlarının XMLHttpRequest yolu (2026-10-08, yükleme durumu): `onProgress` verilince istek
 * XHR ile gider — GERÇEK yükleme yüzdesi (`upload.onprogress`) + "sent" (gövde tamam) — ve `request()`'in yanıt işleyişi
 * AYNEN korunur: credentials (withCredentials), X-Lang, Content-Type YOK (multipart sınırı tarayıcının), withStatus gövdesi
 * (`status`), 401 → /?session=expired (oturum bayrağı varken), 401 MAINTENANCE → yönlendirme YOK + bakım sinyali,
 * başarısız çağrı halkasına yalnız yol + durum, "Vazgeç" (AbortSignal) → CANCELLED. Gövdede şifre / özel anahtar YOK.
 */
class FakeXHR {
  static instances = []
  constructor() {
    this.upload = {}
    this.headers = {}
    this.withCredentials = false
    this.status = 0
    this.responseText = ''
    this.aborted = false
    FakeXHR.instances.push(this)
  }
  open(method, url, async) { this.method = method; this.url = url; this.async = async }
  setRequestHeader(k, v) { this.headers[k] = v }
  getResponseHeader(n) { return n.toLowerCase() === 'content-type' ? 'application/json' : null }
  send(body) { this.body = body }
  abort() { this.aborted = true; this.onabort?.() }
  /** Sunucu yanıtı (gövde metni JSON ya da ham). */
  respond(status, body) {
    this.status = status
    this.responseText = typeof body === 'string' ? body : JSON.stringify(body)
    this.onload?.()
  }
  progress(loaded, total) { this.upload.onprogress?.({ loaded, total, lengthComputable: total != null }) }
  sent() { this.upload.onload?.() }
}
const lastXhr = () => FakeXHR.instances[FakeXHR.instances.length - 1]
const flush = () => new Promise((r) => setTimeout(r, 0))

const EXTRACTION = { format: 'PKCS12', file_name: 'a.pfx', size_bytes: 9, entries: [{ alias: 'srv', key_entry: true, certs: ['QUJD'] }],
  csr_pem: [], private_keys_removed: 1, password_used: true, notes: [] }

describe('api.manualCerts — XMLHttpRequest yolu (yükleme ilerlemesi)', () => {
  const realXhr = globalThis.XMLHttpRequest
  const realFetch = global.fetch
  beforeEach(() => {
    FakeXHR.instances = []
    globalThis.XMLHttpRequest = FakeXHR
    global.fetch = vi.fn(() => { throw new Error('onProgress verilince fetch KULLANILMAZ') })
    try { localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
    vi.mocked(assignLocation).mockClear()
    resetMaintenanceSignal()
  })
  afterEach(() => {
    globalThis.XMLHttpRequest = realXhr
    global.fetch = realFetch
    try { localStorage.removeItem(LANG_STORAGE_KEY) } catch { /* yok */ }
    sessionStorage.clear()
  })

  it('analyze: POST, withCredentials, X-Lang, Content-Type YOK, gövde aynı FormData; yükleme yüzdesi + "sent" → sonuç', async () => {
    const events = []
    const fd = extractedFormData(EXTRACTION)
    const p = api.manualCerts.analyze(fd, { onProgress: (e) => events.push(e) })
    const x = lastXhr()
    expect(x.method).toBe('POST')
    expect(x.url).toBe('/api/manual-certs/analyze')
    expect(x.async).toBe(true)
    expect(x.withCredentials).toBe(true)
    expect(x.headers['X-Lang']).toBe('tr')
    expect(x.headers['Content-Type']).toBeUndefined()
    expect(x.body).toBe(fd)
    // Gövdede yalnız `extracted`: şifre / dosya / metin / özel anahtar yok
    for (const k of ['file', 'text', 'password']) expect(x.body.has(k), k).toBe(false)
    const raw = await x.body.get('extracted').text()
    expect(raw).not.toMatch(/PRIVATE KEY|password/)
    x.progress(256, 1024)
    x.progress(1024, 1024)
    x.sent()
    x.respond(200, { success: true, data: { entries: [] } })
    await expect(p).resolves.toEqual({ success: true, data: { entries: [] } })
    expect(events).toEqual([
      { phase: 'upload', loaded: 256, total: 1024 }, { phase: 'upload', loaded: 1024, total: 1024 }, { phase: 'sent' },
    ])
    expect(global.fetch).not.toHaveBeenCalled()
  })

  it('toplam bilinmiyorsa total null; create / batch / renew yolları aynı taşıyıcıyla', async () => {
    const events = []
    const p = api.manualCerts.create(new FormData(), { onProgress: (e) => events.push(e) })
    lastXhr().upload.onprogress({ loaded: 10, total: 0, lengthComputable: false })
    lastXhr().respond(200, { success: true, data: { inventory_id: 1 } })
    await p
    expect(events).toEqual([{ phase: 'upload', loaded: 10, total: null }])
    const pb = api.manualCerts.createBatch(new FormData(), { onProgress: () => {} })
    lastXhr().respond(200, { success: true, data: {} })
    await pb
    const pr = api.manualCerts.renew(42, new FormData(), { onProgress: () => {} })
    lastXhr().respond(200, { success: true, data: {} })
    await pr
    expect(FakeXHR.instances.map((x) => `${x.method} ${x.url}`)).toEqual([
      'POST /api/manual-certs', 'POST /api/manual-certs/batch', 'POST /api/manual-certs/42/versions',
    ])
  })

  it('withStatus gövdesi korunur: 409 KEY_EXISTS (status + code), 400 alan hataları; halkada yalnız yol + durum', async () => {
    const p = api.manualCerts.create(new FormData(), { onProgress: () => {} })
    lastXhr().respond(409, { success: false, code: 'KEY_EXISTS', field: 'domain', domain: 'x', error: 'var' })
    expect(await p).toMatchObject({ success: false, status: 409, code: 'KEY_EXISTS', field: 'domain' })
    const fd = new FormData()
    fd.append('note', 'gizli-not-metni')
    const p2 = api.manualCerts.create(fd, { onProgress: () => {} })
    lastXhr().respond(400, { success: false, errors: { domain: 'geçersiz' } })
    expect(await p2).toMatchObject({ success: false, status: 400, errors: { domain: 'geçersiz' } })
    const ring = JSON.stringify(getRecentFailures())
    expect(ring).toContain('/manual-certs')
    expect(ring).not.toContain('gizli-not-metni')
  })

  it('JSON olmayan yanıt (proxy HTML 413) yumuşak hata yüküne döner (fetch yoluyla aynı)', async () => {
    const p = api.manualCerts.analyze(new FormData(), { onProgress: () => {} })
    lastXhr().respond(413, '<html>Request Entity Too Large</html>')
    const res = await p
    expect(res).toMatchObject({ success: false, status: 413 })
    expect(typeof res.error).toBe('string')
  })

  it('401 (oturum bitti, bayrak varken) → null + /?session=expired; bayrak silinir', async () => {
    sessionStorage.setItem('sm.session.active', '1')
    const p = api.manualCerts.analyze(new FormData(), { onProgress: () => {} })
    lastXhr().respond(401, { success: false, error: 'Session superseded' })
    expect(await p).toBeNull()
    expect(assignLocation).toHaveBeenCalledWith('/?session=expired')
    expect(sessionStorage.getItem('sm.session.active')).toBeNull()
  })

  it('401 MAINTENANCE → null, YÖNLENDİRME YOK, bakım sinyali (fetch yoluyla aynı)', async () => {
    sessionStorage.setItem('sm.session.active', '1')
    let events = 0
    const h = () => { events++ }
    window.addEventListener(MAINTENANCE_EVENT, h)
    try {
      const p = api.manualCerts.create(new FormData(), { onProgress: () => {} })
      lastXhr().respond(401, { success: false, code: 'MAINTENANCE', error_code: 'MAINTENANCE', error: 'bakım',
        maintenance: { state: 'active', start_at: '2026-10-08T19:00:00Z', end_at: '2026-10-08T20:00:00Z' } })
      expect(await p).toBeNull()
      expect(assignLocation).not.toHaveBeenCalled()
      expect(events).toBe(1)
    } finally {
      window.removeEventListener(MAINTENANCE_EVENT, h)
    }
  })

  it('"Vazgeç": AbortSignal XHR\'ı keser → { cancelled, code: CANCELLED }; önceden iptal edilmişse istek hiç açılmaz', async () => {
    const ctrl = new AbortController()
    const p = api.manualCerts.analyze(new FormData(), { onProgress: () => {}, signal: ctrl.signal })
    const x = lastXhr()
    x.progress(1, 10)
    ctrl.abort()
    expect(x.aborted).toBe(true)
    expect(await p).toEqual({ success: false, status: 0, code: 'CANCELLED', cancelled: true })
    const pre = new AbortController()
    pre.abort()
    const n = FakeXHR.instances.length
    expect(await api.manualCerts.create(new FormData(), { onProgress: () => {}, signal: pre.signal }))
      .toMatchObject({ cancelled: true, code: 'CANCELLED' })
    expect(FakeXHR.instances.length).toBe(n)              // istek hiç kurulmaz
  })

  it('ağ hatası (sunucuya ulaşılamadı) → istek reddedilir (fetch yoluyla aynı: çağıran yakalar)', async () => {
    const p = api.manualCerts.analyze(new FormData(), { onProgress: () => {} })
    lastXhr().onerror()
    await expect(p).rejects.toBeTruthy()
    await flush()
  })

  it('onProgress verilmezse eski fetch yolu (aynı sonuç); XMLHttpRequest yoksa da fetch', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => ({ success: true }) })
    await api.manualCerts.analyze(new FormData())
    expect(global.fetch).toHaveBeenCalledTimes(1)
    expect(FakeXHR.instances).toHaveLength(0)
    globalThis.XMLHttpRequest = undefined
    await api.manualCerts.create(new FormData(), { onProgress: () => {} })
    expect(global.fetch).toHaveBeenCalledTimes(2)
  })
})
