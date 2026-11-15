import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// Vite'ın `?worker&url` biçimi testte yalnız bir adres dizgisidir (kod içe aktarılmaz)
vi.mock('../utils/imageEngine/avif.worker.js?worker&url', () => ({ default: '/assets/avif.worker-T3st.js' }))

import {
  AVIF_ENCODE_TIMEOUT_MS, AVIF_IDLE_MS, AVIF_LOAD_TIMEOUT_MS, _resetAvifForTests, avifAvailable, avifBlocked, getAvifBlock, prepareAvif,
  setAvifBlock,
} from '../utils/imageEngine/avifClient.js'
import { AVIF_FAIL_COOLDOWN_MS } from '../utils/imageEngine/errors.js'

/**
 * AVIF kodlayıcı istemcisi (2026-11-15): ayrı modül Worker'ı yalnız ilk AVIF kodlamasında açılır; yükleme 30 sn, her
 * kodlama 90 sn ile SINIRLI (aşılırsa Worker sonlandırılır); boşta 30 sn sonra kapanır; betik / WASM sunucuda yoksa
 * (404 / 410) "eski paket" — sonraki denemeler Worker açmadan biter; CSP derlemeyi engelliyorsa AVIF denenmez.
 */
class FakeWorker {
  static instances = []
  static mode = 'ok'          // ok | silent | crash | wasm404 | legacyStale | loadFail | encodeFail
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
      if (mode === 'wasm404') queueMicrotask(() => this.reply({ id: m.id, type: 'error', code: 'BROWSER_UNSUPPORTED', params: { reason: 'avif', detail: 'asset_missing' } }))
      // önceki sürümün AVIF Worker'ı 404'ü stale_bundle diye bildirirdi
      if (mode === 'legacyStale') queueMicrotask(() => this.reply({ id: m.id, type: 'error', code: 'BROWSER_UNSUPPORTED', params: { reason: 'stale_bundle' } }))
      if (mode === 'loadFail') queueMicrotask(() => this.reply({ id: m.id, type: 'error', code: 'BROWSER_UNSUPPORTED', params: { reason: 'avif', detail: 'wasm_http' } }))
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

  it('yükleme 30 sn içinde bitmezse: load_timeout, Worker sonlandırılır; hata HATIRLANIR — 5 dk yeniden beklenmez, sonra yeni Worker', async () => {
    vi.useFakeTimers()
    FakeWorker.mode = 'silent'
    const p = prepareAvif(image(), { speed: 6 })
    const caught = p.catch((e) => e)
    await vi.advanceTimersByTimeAsync(AVIF_LOAD_TIMEOUT_MS - 1)
    expect(FakeWorker.instances[0].terminated).toBe(false)
    await vi.advanceTimersByTimeAsync(2)
    expect(await caught).toMatchObject({ code: 'BROWSER_UNSUPPORTED', params: { reason: 'avif', detail: 'load_timeout' } })
    expect(FakeWorker.instances[0].terminated).toBe(true)
    // yakın zamandaki yükleme hatası: AVIF denenmez, oturum ANINDA reddedilir (Worker açılmaz, 30 sn beklenmez)
    FakeWorker.mode = 'ok'
    expect(avifAvailable()).toBe(false)
    expect(avifBlocked()).toBe('load_timeout')
    await expect(prepareAvif(image(), { speed: 6 })).rejects.toMatchObject({ params: { reason: 'avif', detail: 'load_timeout' } })
    expect(FakeWorker.instances).toHaveLength(1)
    // süre dolunca yeniden denenir
    await vi.advanceTimersByTimeAsync(AVIF_FAIL_COOLDOWN_MS)
    expect(avifAvailable()).toBe(true)
    const s = await prepareAvif(image(), { speed: 6 })
    expect(FakeWorker.instances).toHaveLength(2)
    s.close()
  })

  it('işin süre bütçesi: kısaltılmış YÜKLEME beklemesi time_budget ile biter ama HATIRLANMAZ (yükleme arkada sürer); kısaltılmış kodlama da time_budget', async () => {
    vi.useFakeTimers()
    FakeWorker.mode = 'silent'
    const caught = prepareAvif(image(), { speed: 6, loadTimeoutMs: 2000 }).catch((e) => e)
    await vi.advanceTimersByTimeAsync(2001)
    expect(await caught).toMatchObject({ params: { reason: 'avif', detail: 'time_budget' } })
    expect(avifBlocked()).toBeNull()
    expect(FakeWorker.instances[0].terminated).toBe(false)                // yükleme sürüyor (kendi 30 sn sınırıyla)
    // yükleme bütçeden SONRA biter (kendi 30 sn sınırının içinde): kimse kullanmazsa boşta kalma süresi sonunda kapanır
    // (bellek tutulmaz) ve bu kapanış yükleme hatası sayılmaz
    const w0 = FakeWorker.instances[0]
    w0.reply({ id: w0.posted[0].m.id, type: 'ready' })
    await flush()
    await vi.advanceTimersByTimeAsync(AVIF_IDLE_MS + 1)
    expect(w0.terminated).toBe(true)
    expect(avifBlocked()).toBeNull()
    _resetAvifForTests()
    FakeWorker.instances = []
    FakeWorker.mode = 'ok'
    const s = await prepareAvif(image(), { speed: 6 })
    FakeWorker.mode = 'silent'
    const slow = s.encode('image/avif', 0.5, { timeoutMs: 5000 }).catch((e) => e)
    await vi.advanceTimersByTimeAsync(5001)
    expect(await slow).toMatchObject({ params: { reason: 'avif', detail: 'time_budget' } })
    expect(FakeWorker.instances[0].terminated).toBe(true)
    expect(avifBlocked()).toBeNull()                                     // kodlama hatası yükleme hatası değildir
    // istenen sınır 90 sn'den uzunsa 90 sn'ye kırpılır (encode_timeout)
    FakeWorker.mode = 'ok'
    const s2 = await prepareAvif(image(), { speed: 6 })
    FakeWorker.mode = 'silent'
    const capped = s2.encode('image/avif', 0.5, { timeoutMs: 10 * AVIF_ENCODE_TIMEOUT_MS }).catch((e) => e)
    await vi.advanceTimersByTimeAsync(AVIF_ENCODE_TIMEOUT_MS + 1)
    expect(await capped).toMatchObject({ params: { reason: 'avif', detail: 'encode_timeout' } })
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

describe('AVIF istemcisi — kodlayıcı dosyası sunucuda yok (dağıtım sonrası 404, inceleme 2026-11-15)', () => {
  it('WASM bulunamadı (Worker asset_missing der): YALNIZ AVIF e özgü hata — eski paket (stale_bundle) DEĞİL; 5 dk hatırlanır, sonra yeniden denenir', async () => {
    vi.useFakeTimers()
    FakeWorker.mode = 'wasm404'
    await expect(prepareAvif(image(), { speed: 6 })).rejects.toMatchObject({ code: 'BROWSER_UNSUPPORTED', params: { reason: 'avif', detail: 'asset_missing' } })
    expect(avifAvailable()).toBe(false)
    expect(avifBlocked()).toBe('asset_missing')
    FakeWorker.mode = 'ok'
    await expect(prepareAvif(image(), { speed: 6 })).rejects.toMatchObject({ params: { reason: 'avif', detail: 'asset_missing' } })
    expect(FakeWorker.instances).toHaveLength(1)
    // kalıcı kilit YOK: süre dolunca (dağıtım bitti) AVIF yeniden çalışır
    await vi.advanceTimersByTimeAsync(AVIF_FAIL_COOLDOWN_MS + 1)
    expect(avifAvailable()).toBe(true)
    const s = await prepareAvif(image(), { speed: 6 })
    expect(FakeWorker.instances).toHaveLength(2)
    s.close()
  })

  it('önceki sürümün AVIF Worker ı stale_bundle bildirse de istemci bunu asset_missing e çevirir (motor kilidi konmaz)', async () => {
    FakeWorker.mode = 'legacyStale'
    await expect(prepareAvif(image(), { speed: 6 })).rejects.toMatchObject({ params: { reason: 'avif', detail: 'asset_missing' } })
  })

  it('her YÜKLEME hatası hatırlanır (5xx de): kayıt ana iş parçacığına taşınabilir — get / set, üst sınır, geçersiz değer yok sayılır', async () => {
    vi.useFakeTimers()
    FakeWorker.mode = 'loadFail'
    await expect(prepareAvif(image(), { speed: 6 })).rejects.toMatchObject({ params: { reason: 'avif', detail: 'wasm_http' } })
    const b = getAvifBlock()
    expect(b).toMatchObject({ detail: 'wasm_http' })
    expect(b.until).toBeGreaterThan(Date.now())
    // yeni bir Worker'da (motor Worker'ı boşta kapanıp açıldı) kayıt geri yüklenir
    _resetAvifForTests()
    expect(getAvifBlock()).toBeNull()
    setAvifBlock(b)
    expect(avifBlocked()).toBe('wasm_http')
    expect(avifAvailable()).toBe(false)
    // üst sınır: süre AVIF_FAIL_COOLDOWN_MS'yi aşamaz; geçersiz / geçmiş kayıt yok sayılır, tuhaf neden 'unavailable' olur
    _resetAvifForTests()
    setAvifBlock({ until: Date.now() + 10 * AVIF_FAIL_COOLDOWN_MS, detail: 'asset_missing' })
    expect(getAvifBlock().until).toBeLessThanOrEqual(Date.now() + AVIF_FAIL_COOLDOWN_MS)
    _resetAvifForTests()
    setAvifBlock({ until: Date.now() - 1, detail: 'x' })
    setAvifBlock(null)
    setAvifBlock({ until: 'yarın' })
    expect(getAvifBlock()).toBeNull()
    setAvifBlock({ until: Date.now() + 1000, detail: '<script>' })
    expect(avifBlocked()).toBe('unavailable')
  })

  it('Worker betiği konuşmadan düştü + HEAD 404 → asset_missing; HEAD ağ hatası → worker_failed — ikisi de hatırlanır', async () => {
    FakeWorker.mode = 'crash'
    const head = vi.fn(async () => ({ status: 404 }))
    vi.stubGlobal('fetch', head)
    await expect(prepareAvif(image(), { speed: 6 })).rejects.toMatchObject({ params: { reason: 'avif', detail: 'asset_missing' } })
    expect(head).toHaveBeenCalledTimes(1)
    expect(head.mock.calls[0][1]).toMatchObject({ method: 'HEAD', cache: 'no-store' })
    expect(String(head.mock.calls[0][0])).toMatch(/\/assets\/avif\.worker-T3st\.js$/)
    expect(avifBlocked()).toBe('asset_missing')

    _resetAvifForTests()
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('ağ yok') }))
    await expect(prepareAvif(image(), { speed: 6 })).rejects.toMatchObject({ params: { reason: 'avif', detail: 'worker_failed' } })
    expect(avifBlocked()).toBe('worker_failed')
    expect(avifAvailable()).toBe(false)
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
