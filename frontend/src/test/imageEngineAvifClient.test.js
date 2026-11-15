import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// Vite'ın `?worker&url` biçimi testte yalnız bir adres dizgisidir (kod içe aktarılmaz)
vi.mock('../utils/imageEngine/avif.worker.js?worker&url', () => ({ default: '/assets/avif.worker-T3st.js' }))

import {
  AVIF_ENCODE_TIMEOUT_MS, AVIF_IDLE_MS, AVIF_LOAD_TIMEOUT_MS, _resetAvifForTests, avifAvailable, prepareAvif,
} from '../utils/imageEngine/avifClient.js'

/**
 * AVIF kodlayıcı istemcisi (2026-11-15): ayrı modül Worker'ı yalnız ilk AVIF kodlamasında açılır; yükleme 30 sn, her
 * kodlama 90 sn ile SINIRLI (aşılırsa Worker sonlandırılır); boşta 30 sn sonra kapanır; betik / WASM sunucuda yoksa
 * (404 / 410) "eski paket" — sonraki denemeler Worker açmadan biter; CSP derlemeyi engelliyorsa AVIF denenmez.
 */
class FakeWorker {
  static instances = []
  static mode = 'ok'          // ok | silent | crash | wasm404 | encodeFail
  constructor(url, opts) {
    this.url = String(url)
    this.opts = opts
    this.terminated = false
    this.posted = []
    FakeWorker.instances.push(this)
    if (FakeWorker.mode === 'crash') queueMicrotask(() => this.onerror?.({ preventDefault() {} }))
  }
  postMessage(m, transfer) {
    this.posted.push({ m, transfer })
    const mode = FakeWorker.mode
    if (m.kind === 'init') {
      if (mode === 'ok' || mode === 'encodeFail') queueMicrotask(() => this.reply({ id: m.id, type: 'ready' }))
      if (mode === 'wasm404') queueMicrotask(() => this.reply({ id: m.id, type: 'error', code: 'BROWSER_UNSUPPORTED', params: { reason: 'stale_bundle' } }))
    }
    if (m.kind === 'encode') {
      if (mode === 'ok') queueMicrotask(() => this.reply({ id: m.id, type: 'result', buffer: new Uint8Array([1, 2, 3, m.quality]).buffer }))
      if (mode === 'encodeFail') queueMicrotask(() => this.reply({ id: m.id, type: 'error', code: 'ENCODE_FAILED', params: {} }))
    }
  }
  terminate() { this.terminated = true }
  reply(data) { if (!this.terminated) this.onmessage?.({ data }) }
}
const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve() }
const image = () => ({ data: new Uint8ClampedArray(4 * 4 * 4).fill(200), width: 4, height: 4 })

beforeEach(() => {
  _resetAvifForTests()
  FakeWorker.instances = []
  FakeWorker.mode = 'ok'
  vi.stubGlobal('Worker', FakeWorker)
})
afterEach(() => {
  _resetAvifForTests()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('AVIF istemcisi — ucuz denetim', () => {
  it('Worker + WebAssembly + derleme izni varsa true; Worker yok / CSP derlemeyi engelliyor → false (kodlayıcı açılmaz)', () => {
    expect(avifAvailable()).toBe(true)
    expect(FakeWorker.instances).toHaveLength(0)
    vi.stubGlobal('Worker', undefined)
    expect(avifAvailable()).toBe(false)
    vi.stubGlobal('Worker', FakeWorker)
    const Real = WebAssembly.Module
    vi.stubGlobal('WebAssembly', { ...WebAssembly, instantiate: WebAssembly.instantiate,
      Module: function Blocked() { throw new WebAssembly.CompileError('Refused to compile: wasm-unsafe-eval') } })
    expect(avifAvailable()).toBe(false)
    vi.stubGlobal('WebAssembly', { ...WebAssembly, Module: Real, instantiate: WebAssembly.instantiate })
    expect(avifAvailable()).toBe(true)
  })
})

describe('AVIF istemcisi — oturum', () => {
  it('ilk kodlamada modül Worker\'ı açılır; görsel bir kez KOPYA olarak aktarılır; kalite 0..100 + hız gider', async () => {
    const s = await prepareAvif(image(), { speed: 6 })
    const w = FakeWorker.instances[0]
    expect(w.url).toBe('/assets/avif.worker-T3st.js')
    expect(w.opts).toEqual({ type: 'module' })
    const img = w.posted.find((p) => p.m.kind === 'image')
    expect(img.m).toMatchObject({ width: 4, height: 4 })
    expect(img.transfer).toEqual([img.m.buffer])
    const blob = await s.encode('image/avif', 0.62)
    expect(blob.type).toBe('image/avif')
    expect(blob.size).toBe(4)
    expect(w.posted.find((p) => p.m.kind === 'encode').m).toMatchObject({ quality: 62, speed: 6 })
    s.close()
    // ikinci oturum aynı Worker'ı kullanır (WASM yeniden yüklenmez)
    const s2 = await prepareAvif(image(), { speed: 6 })
    expect(FakeWorker.instances).toHaveLength(1)
    s2.close()
  })

  it('yeni oturum eskisinin görselini değiştirir: eski oturumun kodlaması BEKLEMEDEN reddedilir', async () => {
    const a = await prepareAvif(image(), { speed: 6 })
    await prepareAvif(image(), { speed: 6 })
    await expect(a.encode('image/avif', 0.5)).rejects.toMatchObject({ code: 'BROWSER_UNSUPPORTED', params: { reason: 'avif', detail: 'worker_closed' } })
  })

  it('yükleme 30 sn içinde bitmezse: load_timeout, Worker sonlandırılır; sonraki deneme yeni Worker açar', async () => {
    vi.useFakeTimers()
    FakeWorker.mode = 'silent'
    const p = prepareAvif(image(), { speed: 6 })
    const caught = p.catch((e) => e)
    await vi.advanceTimersByTimeAsync(AVIF_LOAD_TIMEOUT_MS - 1)
    expect(FakeWorker.instances[0].terminated).toBe(false)
    await vi.advanceTimersByTimeAsync(2)
    expect(await caught).toMatchObject({ code: 'BROWSER_UNSUPPORTED', params: { reason: 'avif', detail: 'load_timeout' } })
    expect(FakeWorker.instances[0].terminated).toBe(true)
    FakeWorker.mode = 'ok'
    const s = await prepareAvif(image(), { speed: 6 })
    expect(FakeWorker.instances).toHaveLength(2)
    s.close()
  })

  it('kodlama 90 sn içinde bitmezse: encode_timeout, Worker sonlandırılır (WASM durumu bilinmiyor)', async () => {
    vi.useFakeTimers()
    FakeWorker.mode = 'ok'
    const s = await prepareAvif(image(), { speed: 6 })
    FakeWorker.mode = 'silent'
    const caught = s.encode('image/avif', 0.5).catch((e) => e)
    await vi.advanceTimersByTimeAsync(AVIF_ENCODE_TIMEOUT_MS + 1)
    expect(await caught).toMatchObject({ params: { reason: 'avif', detail: 'encode_timeout' } })
    expect(FakeWorker.instances[0].terminated).toBe(true)
  })

  it('kodlayıcı hatası: BROWSER_UNSUPPORTED avif/encoder; Worker kapatılır', async () => {
    FakeWorker.mode = 'encodeFail'
    const s = await prepareAvif(image(), { speed: 6 })
    await expect(s.encode('image/avif', 0.5)).rejects.toMatchObject({ code: 'BROWSER_UNSUPPORTED', params: { reason: 'avif', detail: 'encoder' } })
    expect(FakeWorker.instances[0].terminated).toBe(true)
  })

  it('boşta 30 sn sonra AVIF Worker\'ı kapatılır (WASM belleği tutulmaz); kapanmadan yeni oturum zamanlayıcıyı iptal eder', async () => {
    vi.useFakeTimers()
    const s = await prepareAvif(image(), { speed: 6 })
    s.close()
    s.close()                                               // ikinci kapatma etkisiz
    await vi.advanceTimersByTimeAsync(AVIF_IDLE_MS - 10)
    expect(FakeWorker.instances[0].terminated).toBe(false)
    const s2 = await prepareAvif(image(), { speed: 6 })
    await vi.advanceTimersByTimeAsync(AVIF_IDLE_MS * 2)
    expect(FakeWorker.instances[0].terminated).toBe(false)  // oturum açıkken kapanmaz
    s2.close()
    await vi.advanceTimersByTimeAsync(AVIF_IDLE_MS + 1)
    expect(FakeWorker.instances[0].terminated).toBe(true)
  })
})

describe('AVIF istemcisi — eski paket (dağıtım sonrası 404)', () => {
  it('WASM sunucuda yok (Worker stale_bundle der): iş stale_bundle ile biter; sonra Worker AÇILMAZ, denetim false', async () => {
    FakeWorker.mode = 'wasm404'
    await expect(prepareAvif(image(), { speed: 6 })).rejects.toMatchObject({ code: 'BROWSER_UNSUPPORTED', params: { reason: 'stale_bundle' } })
    expect(avifAvailable()).toBe(false)
    FakeWorker.mode = 'ok'
    await expect(prepareAvif(image(), { speed: 6 })).rejects.toMatchObject({ params: { reason: 'stale_bundle' } })
    expect(FakeWorker.instances).toHaveLength(1)
  })

  it('Worker betiği konuşmadan düştü + HEAD 404 → stale_bundle; HEAD ağ hatası → worker_failed (eski paket sayılmaz)', async () => {
    FakeWorker.mode = 'crash'
    const head = vi.fn(async () => ({ status: 404 }))
    vi.stubGlobal('fetch', head)
    await expect(prepareAvif(image(), { speed: 6 })).rejects.toMatchObject({ params: { reason: 'stale_bundle' } })
    expect(head).toHaveBeenCalledTimes(1)
    expect(head.mock.calls[0][1]).toMatchObject({ method: 'HEAD', cache: 'no-store' })
    expect(String(head.mock.calls[0][0])).toMatch(/\/assets\/avif\.worker-T3st\.js$/)

    _resetAvifForTests()
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('ağ yok') }))
    await expect(prepareAvif(image(), { speed: 6 })).rejects.toMatchObject({ params: { reason: 'avif', detail: 'worker_failed' } })
    expect(avifAvailable()).toBe(true)
  })

  it('HEAD denetimi de sınırlı: yanıt gelmezse 5 sn sonra kesilir → worker_failed (asılı kalmaz)', async () => {
    vi.useFakeTimers()
    FakeWorker.mode = 'crash'
    vi.stubGlobal('fetch', vi.fn((_u, init) => new Promise((_r, reject) => {
      init.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    })))
    const caught = prepareAvif(image(), { speed: 6 }).catch((e) => e)
    await vi.advanceTimersByTimeAsync(5001)
    await flush()
    expect(await caught).toMatchObject({ params: { reason: 'avif', detail: 'worker_failed' } })
  })
})
