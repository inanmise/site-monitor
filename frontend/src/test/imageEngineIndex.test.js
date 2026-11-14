import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import * as engine from '../utils/imageEngine/index.js'
import { encodePng } from '../utils/imageEngine/pngWriter.js'
import { toEngineError, withTimeout, EngineError } from '../utils/imageEngine/errors.js'
import { solid } from './imageEngineKit.test.helpers.js'

/**
 * Kamu arayüzü (index.js): sözleşme adları, jsdom'da (Worker / tuval yok) dürüst hata, sahte Worker ile kuyruk — aynı
 * anda tek iş, ilerleme, iptal (çalışan işte Worker sonlandırılır + yenisi açılır; bekleyen iş kuyruktan çıkar), Worker
 * çökmesi, süre aşımı, boşta sonlandırma — ve kaynak düzeyinde tembel parça kapısı.
 */
const pngBlob = async (w = 8, h = 6) => new Blob([(await encodePng({ width: w, height: h, rgba: solid(w, h, [9, 8, 7, 255]) })).bytes], { type: 'image/png' })

class FakeWorker {
  static instances = []
  static auto = true
  constructor(url, opts) {
    this.url = String(url)
    this.opts = opts
    this.terminated = false
    this.posted = []
    FakeWorker.instances.push(this)
  }
  postMessage(m) {
    this.posted.push(m)
    if (m.kind === 'support' && FakeWorker.auto) queueMicrotask(() => this.reply({ id: m.id, type: 'support', support: { ok: true, webp: true } }))
  }
  terminate() { this.terminated = true }
  reply(data) { if (!this.terminated) this.onmessage?.({ data }) }
  get job() { return this.posted.filter((m) => m.kind === 'process').at(-1) }
}
const live = () => FakeWorker.instances.filter((w) => !w.terminated)
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve() }
const RESULT = { blob: new Blob([1, 2, 3], { type: 'image/webp' }), type: 'webp', mime: 'image/webp', width: 4, height: 3, bytes: 3, kept: false, notes: [] }

beforeEach(() => {
  engine._resetForTests()
  FakeWorker.instances = []
  FakeWorker.auto = true
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  engine._resetForTests()
})

describe('görsel motoru — kamu arayüzü (sözleşme §2)', () => {
  it('dışa aktarılan adlar ve sabitler', () => {
    expect(Object.keys(engine).sort()).toEqual(['ENGINE_LIMITS', 'ERROR_CODES', 'EngineError', 'JOB_TIMEOUT_MS', 'NOTE_CODES', 'OUTPUT_FORMATS',
      'RESIZE_METHODS', '_resetForTests', 'decodeForPreview', 'engineSupport', 'inspectImage', 'processImage'].sort())
    expect(engine.ENGINE_LIMITS).toEqual({ maxInputBytes: 25 * 1024 * 1024, maxPixels: 50_000_000, maxSide: 30_000 })
    const e = new engine.EngineError('TOO_LARGE', { bytes: 5 })
    expect(e).toBeInstanceOf(Error)
    expect([e.name, e.code, e.params, e.message]).toEqual(['EngineError', 'TOO_LARGE', { bytes: 5 }, 'TOO_LARGE'])
    expect(new engine.EngineError('CANCELLED').params).toEqual({})
  })

  it('inspectImage: yalnız başlık — { type, width, height, bytes, hasAlpha, animated }', async () => {
    const blob = await pngBlob(33, 21)
    expect(await engine.inspectImage(blob)).toEqual({ type: 'png', width: 33, height: 21, bytes: blob.size, hasAlpha: false, animated: false })
    await expect(engine.inspectImage(new Blob(['%PDF-1.4']))).rejects.toMatchObject({ name: 'EngineError', code: 'UNSUPPORTED_FORMAT', params: { detected: 'pdf' } })
    await expect(engine.inspectImage(undefined)).rejects.toMatchObject({ code: 'INVALID_OPTIONS' })
    await expect(engine.inspectImage({ size: 26 * 1024 * 1024 })).rejects.toMatchObject({ code: 'TOO_LARGE' })
  })

  it('Worker ve tuval olmayan ortamda (jsdom): destek yok; işlem BROWSER_UNSUPPORTED — asılı kalmaz', async () => {
    expect(await engine.engineSupport()).toEqual({ worker: false, webp: false })
    expect(engine.engineSupport()).toBe(engine.engineSupport())            // bir kez hesaplanır
    await expect(engine.processImage(await pngBlob(), {})).rejects.toMatchObject({ name: 'EngineError', code: 'BROWSER_UNSUPPORTED' })
    await expect(engine.decodeForPreview(await pngBlob())).rejects.toMatchObject({ code: 'BROWSER_UNSUPPORTED' })
  })

  it('seçenek / girdi hataları iş başlamadan: INVALID_OPTIONS, TOO_LARGE, CANCELLED; tür hatası çözmeden', async () => {
    const blob = await pngBlob()
    await expect(engine.processImage(blob, { format: 'heic' })).rejects.toMatchObject({ code: 'INVALID_OPTIONS', params: { field: 'format', reason: 'unknown' } })
    await expect(engine.processImage(blob, { resize: { method: 'scale' } })).rejects.toMatchObject({ code: 'INVALID_OPTIONS' })
    await expect(engine.processImage('metin', {})).rejects.toMatchObject({ code: 'INVALID_OPTIONS', params: { field: 'blob' } })
    await expect(engine.processImage({ size: 30 * 1024 * 1024, slice() {} }, {})).rejects.toMatchObject({ code: 'TOO_LARGE' })
    const ac = new AbortController()
    ac.abort()
    await expect(engine.processImage(blob, {}, { signal: ac.signal })).rejects.toMatchObject({ code: 'CANCELLED' })
    await expect(engine.processImage(new Blob(['<svg xmlns="http://www.w3.org/2000/svg"/>']), {})).rejects.toMatchObject({ code: 'UNSUPPORTED_FORMAT' })
  })

  it('decodeForPreview: sınırlar çözmeden önce; EXIF yönü seçeneği; maxSide küçültür; iptalde ImageBitmap kapatılır', async () => {
    const calls = []
    const bitmap = { width: 8, height: 6, close: vi.fn() }
    vi.stubGlobal('createImageBitmap', vi.fn(async (blob, opts) => { calls.push(opts); return bitmap }))
    const blob = await pngBlob(800, 600)
    expect(await engine.decodeForPreview(blob)).toBe(bitmap)
    expect(calls[0]).toEqual({ imageOrientation: 'from-image' })
    await engine.decodeForPreview(blob, { maxSide: 200 })
    expect(calls[1]).toEqual({ imageOrientation: 'from-image', resizeWidth: 200, resizeHeight: 150, resizeQuality: 'high' })
    await engine.decodeForPreview(blob, { maxSide: 5000 })
    expect(calls[2]).toEqual({ imageOrientation: 'from-image' })              // küçük görsel büyütülmez
    // dev görsel: createImageBitmap hiç çağrılmaz
    const bomb = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52, 0, 0, 0x75, 0x31, 0, 0, 0x75, 0x31, 8, 6, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0x49, 0x44, 0x41, 0x54, 0, 0, 0, 0])
    await expect(engine.decodeForPreview(new Blob([bomb]))).rejects.toMatchObject({ code: 'TOO_MANY_PIXELS' })
    expect(calls.length).toBe(3)
    // çözme sürerken iptal: bitmap kapatılır
    const ac = new AbortController()
    globalThis.createImageBitmap.mockImplementationOnce(async () => { ac.abort(); return bitmap })
    await expect(engine.decodeForPreview(blob, { signal: ac.signal })).rejects.toMatchObject({ code: 'CANCELLED' })
    expect(bitmap.close).toHaveBeenCalledTimes(1)
    // tarayıcı çözemezse DECODE_FAILED; seçeneği tanımayan eski tarayıcıda seçeneksiz yeniden denenir
    globalThis.createImageBitmap.mockImplementationOnce(async () => { throw new DOMException('bozuk', 'InvalidStateError') })
    await expect(engine.decodeForPreview(blob)).rejects.toMatchObject({ code: 'DECODE_FAILED', params: { reason: 'decoder' } })
    globalThis.createImageBitmap.mockImplementationOnce(async () => { throw new TypeError('seçenek yok') })
    expect(await engine.decodeForPreview(blob)).toBe(bitmap)
  })
})

describe('görsel motoru — Worker kuyruğu (sahte Worker)', () => {
  beforeEach(() => { vi.stubGlobal('Worker', FakeWorker) })

  it('destek sorgusu Worker a gider ve bir kez yapılır; modül Worker ı olarak kurulur', async () => {
    expect(await engine.engineSupport()).toEqual({ worker: true, webp: true })
    await engine.engineSupport()
    expect(FakeWorker.instances.length).toBe(1)
    expect(FakeWorker.instances[0].opts).toEqual({ type: 'module' })
    expect(FakeWorker.instances[0].url).toMatch(/engine\.worker\.js/)
    expect(FakeWorker.instances[0].posted.filter((m) => m.kind === 'support').length).toBe(1)
  })

  it('iş Worker a doğrulanmış seçeneklerle gider; ilerleme iletilir; sonuç döner', async () => {
    const blob = await pngBlob()
    const progress = []
    const p = engine.processImage(blob, { format: 'webp', quality: 70.2 }, { onProgress: (phase, f) => progress.push([phase, f]) })
    await flush()
    const w = live()[0]
    expect(w.job.blob).toBe(blob)
    expect(w.job.options).toMatchObject({ format: 'webp', quality: 70, resize: { method: 'none' }, targetBytes: null, background: '#ffffff' })
    w.reply({ id: w.job.id, type: 'progress', phase: 'decode', fraction: 0.5 })
    w.reply({ id: 99999, type: 'progress', phase: 'encode', fraction: 1 })      // başka işin iletisi: yok sayılır
    w.reply({ id: w.job.id, type: 'result', result: RESULT })
    expect(await p).toEqual(RESULT)
    expect(progress).toEqual([['decode', 0.5]])
  })

  it('özgün dosya korunduysa çağıranın verdiği Blob un KENDİSİ döner', async () => {
    const blob = await pngBlob()
    const p = engine.processImage(blob, {})
    await flush()
    const w = live()[0]
    w.reply({ id: w.job.id, type: 'result', result: { ...RESULT, blob: null, kept: true, type: 'png' } })
    const r = await p
    expect(r.blob).toBe(blob)
    expect(r.kept).toBe(true)
  })

  it('aynı anda TEK iş: ikinci iş birincisi bitince gönderilir', async () => {
    const a = engine.processImage(await pngBlob(), { quality: 50 })
    const b = engine.processImage(await pngBlob(), { quality: 60 })
    await flush()
    const w = live()[0]
    expect(w.posted.filter((m) => m.kind === 'process').length).toBe(1)
    const first = w.job
    w.reply({ id: first.id, type: 'result', result: { ...RESULT, quality: 50 } })
    expect((await a).quality).toBe(50)
    await flush()
    expect(w.posted.filter((m) => m.kind === 'process').length).toBe(2)
    expect(w.job.options.quality).toBe(60)
    w.reply({ id: w.job.id, type: 'result', result: { ...RESULT, quality: 60 } })
    expect((await b).quality).toBe(60)
    expect(FakeWorker.instances.length).toBe(1)                                   // Worker yeniden kullanılır
  })

  it('Worker dan gelen hata kodu EngineError olur; bilinmeyen kod ENCODE_FAILED', async () => {
    const p = engine.processImage(await pngBlob(), {})
    await flush()
    live()[0].reply({ id: live()[0].job.id, type: 'error', code: 'DECODE_FAILED', params: { reason: 'decoder' } })
    await expect(p).rejects.toMatchObject({ name: 'EngineError', code: 'DECODE_FAILED', params: { reason: 'decoder' } })
    const q = engine.processImage(await pngBlob(), {})
    await flush()
    live()[0].reply({ id: live()[0].job.id, type: 'error', code: 'NE_BU' })
    const err = await q.catch((e) => e)
    expect(err).toBeInstanceOf(EngineError)
    expect(err.code).toBe('ENCODE_FAILED')
  })

  it('İPTAL — çalışan iş: Worker SONLANDIRILIR, söz CANCELLED ile reddedilir, sıradaki iş yeni Worker da koşar', async () => {
    const ac = new AbortController()
    const a = engine.processImage(await pngBlob(), { quality: 50 }, { signal: ac.signal })
    const b = engine.processImage(await pngBlob(), { quality: 60 })
    await flush()
    const first = live()[0]
    ac.abort()
    await expect(a).rejects.toMatchObject({ code: 'CANCELLED' })
    expect(first.terminated).toBe(true)
    await flush()
    const second = live()[0]
    expect(second).not.toBe(first)
    expect(second.job.options.quality).toBe(60)
    first.reply({ id: first.posted.at(-1).id, type: 'result', result: RESULT })   // ölü Worker ın geç yanıtı: yok sayılır
    second.reply({ id: second.job.id, type: 'result', result: { ...RESULT, quality: 60 } })
    expect((await b).quality).toBe(60)
  })

  it('İPTAL — bekleyen iş: kuyruktan çıkar, çalışan işe dokunulmaz', async () => {
    const ac = new AbortController()
    const a = engine.processImage(await pngBlob(), { quality: 50 })
    const b = engine.processImage(await pngBlob(), { quality: 60 }, { signal: ac.signal })
    const c = engine.processImage(await pngBlob(), { quality: 70 })
    await flush()
    const w = live()[0]
    ac.abort()
    await expect(b).rejects.toMatchObject({ code: 'CANCELLED' })
    expect(w.terminated).toBe(false)
    w.reply({ id: w.job.id, type: 'result', result: { ...RESULT, quality: 50 } })
    expect((await a).quality).toBe(50)
    await flush()
    expect(w.job.options.quality).toBe(70)                                        // iptal edilen hiç gönderilmedi
    w.reply({ id: w.job.id, type: 'result', result: { ...RESULT, quality: 70 } })
    expect((await c).quality).toBe(70)
    // iptalden sonra gelen abort tekrarları sorun çıkarmaz
    ac.abort()
  })

  it('Worker çökerse (bellek): iş BROWSER_UNSUPPORTED ile biter, sonraki iş yeni Worker açar', async () => {
    const a = engine.processImage(await pngBlob(), {})
    await flush()
    const first = live()[0]
    first.onerror({ preventDefault() {} })
    await expect(a).rejects.toMatchObject({ code: 'BROWSER_UNSUPPORTED', params: { reason: 'worker_failed' } })
    expect(first.terminated).toBe(true)
    const b = engine.processImage(await pngBlob(), {})
    await flush()
    expect(live()[0]).not.toBe(first)
    live()[0].reply({ id: live()[0].job.id, type: 'result', result: RESULT })
    await expect(b).resolves.toBeTruthy()
  })

  it('süre aşımı: yanıt vermeyen iş sonlandırılır (ENCODE_FAILED / timeout); boşta kalan Worker kapatılır', async () => {
    await engine.engineSupport()
    vi.useFakeTimers()
    const a = engine.processImage(await pngBlob(), {})
    const failed = a.catch((e) => e)
    await vi.advanceTimersByTimeAsync(10)
    const w = live()[0]
    expect(w.job).toBeTruthy()
    await vi.advanceTimersByTimeAsync(engine.JOB_TIMEOUT_MS + 10)
    expect(await failed).toMatchObject({ code: 'ENCODE_FAILED', params: { reason: 'timeout' } })
    expect(w.terminated).toBe(true)
    // yeni iş biter → 30 sn boşta kalınca Worker kapanır (bellek tutmasın); sonraki iş yenisini açar
    const b = engine.processImage(await pngBlob(), {})
    await vi.advanceTimersByTimeAsync(10)
    const w2 = live()[0]
    w2.reply({ id: w2.job.id, type: 'result', result: RESULT })
    await b
    expect(w2.terminated).toBe(false)
    await vi.advanceTimersByTimeAsync(31000)
    expect(w2.terminated).toBe(true)
  })

  it('Worker destek vermezse (OffscreenCanvas yok) ya da yanıtsız kalırsa ana iş parçacığına düşülür', async () => {
    FakeWorker.auto = false
    const p = engine.engineSupport()
    await flush()
    const w = live()[0]
    w.reply({ id: w.posted[0].id, type: 'support', support: { ok: false, webp: false } })
    expect(await p).toEqual({ worker: false, webp: false })
    engine._resetForTests()
    // Worker kurulamıyor (CSP / eski tarayıcı)
    vi.stubGlobal('Worker', class { constructor() { throw new Error('engellendi') } })
    expect(await engine.engineSupport()).toEqual({ worker: false, webp: false })
  })
})

describe('görsel motoru — hata yardımcıları', () => {
  it('bilinmeyen hata motor hatasına çevrilir (ileti / yığın sızmaz)', () => {
    const known = new EngineError('TOO_LARGE', { bytes: 1 })
    expect(toEngineError(known)).toBe(known)
    expect(toEngineError({ code: 'CANCELLED', params: { a: 1 } })).toMatchObject({ code: 'CANCELLED', params: { a: 1 } })
    expect(toEngineError(new RangeError('Array buffer allocation failed'))).toMatchObject({ code: 'BROWSER_UNSUPPORTED', params: { reason: 'memory' } })
    expect(toEngineError(new DOMException('x', 'AbortError'))).toMatchObject({ code: 'CANCELLED' })
    const other = toEngineError(new TypeError('gizli ayrıntı /etc/passwd'))
    expect(other).toMatchObject({ code: 'ENCODE_FAILED', params: { reason: 'internal', name: 'TypeError' } })
    expect(JSON.stringify(other.params)).not.toMatch(/passwd/)
    expect(toEngineError(null, 'DECODE_FAILED').code).toBe('DECODE_FAILED')
    expect(toEngineError({ code: 'UYDURMA' }).code).toBe('ENCODE_FAILED')
  })

  it('süre sınırı: zamanında biten söz sonucu verir; asılı kalan söz verilen hatayla reddedilir', async () => {
    expect(await withTimeout(Promise.resolve(5), 1000, () => new Error('x'))).toBe(5)
    await expect(withTimeout(Promise.reject(new Error('iç')), 1000, () => new Error('x'))).rejects.toThrow('iç')
    vi.useFakeTimers()
    const p = withTimeout(new Promise(() => {}), 500, () => new EngineError('DECODE_FAILED', { reason: 'timeout' })).catch((e) => e)
    await vi.advanceTimersByTimeAsync(600)
    expect(await p).toMatchObject({ code: 'DECODE_FAILED', params: { reason: 'timeout' } })
  })
})

describe('görsel motoru — tembel parça kapısı (kaynak düzeyinde)', () => {
  const DIR = path.resolve(__dirname, '..', 'utils', 'imageEngine')
  const SRC = path.resolve(__dirname, '..')
  const files = fs.readdirSync(DIR).filter((f) => f.endsWith('.js')).map((f) => ({ name: f, src: fs.readFileSync(path.join(DIR, f), 'utf8') }))
  const staticImports = (src) => [...src.matchAll(/^\s*(?:import|export)\s+(?:[^'"]*?\s+from\s+)?['"]([^'"]+)['"]/gm)].map((m) => m[1])
  // yorum satırları sayılmaz (açıklamalarda `import('./core.js')` geçer); aynı hedef bir kez sayılır
  const codeOnly = (src) => src.split('\n').filter((l) => !/^\s*(\/\/|\/\*|\*)/.test(l)).join('\n')
  const dynamicImports = (src) => [...new Set([...codeOnly(src).matchAll(/import\(\s*['"]([^'"]+)['"]\s*\)/g)].map((m) => m[1]))]
  const of = (name) => files.find((f) => f.name === name)

  it('index.js yalnız küçük modülleri statik alır; ağır çekirdek Worker ya da import() ile gelir', () => {
    expect([...new Set(staticImports(of('index.js').src))].sort()).toEqual(['./errors.js', './options.js', './platform.js', './probe.js'])
    expect(dynamicImports(of('index.js').src)).toEqual(['./core.js'])
    expect(of('index.js').src).toMatch(/new Worker\(new URL\('\.\/engine\.worker\.js', import\.meta\.url\), \{ type: 'module' \}\)/)
    // index'in statik aldığı modüller de ağır parçaya (nicemleyici, PNG yazıcı, fflate, iş akışı) uzanmaz
    for (const light of ['errors.js', 'options.js', 'probe.js', 'platform.js']) {
      expect(staticImports(of(light).src).filter((s) => !['./errors.js'].includes(s)), light).toEqual([])
    }
  })

  it('Worker paketi tek dosya kalır: çekirdekte dinamik içe aktarma yok; fflate yalnız PNG yazıcıda', () => {
    for (const f of files) if (f.name !== 'index.js') expect(dynamicImports(f.src), f.name).toEqual([])
    expect(staticImports(of('engine.worker.js').src).sort()).toEqual(['./core.js', './errors.js'])
    expect(files.filter((f) => staticImports(f.src).includes('fflate')).map((f) => f.name)).toEqual(['pngWriter.js'])
    // motor kendi içine kapalı: klasör dışından yalnız fflate
    for (const f of files) for (const s of staticImports(f.src)) expect(s === 'fflate' || /^\.\/[A-Za-z.]+\.js$/.test(s), `${f.name} → ${s}`).toBe(true)
  })

  it('motorun iç modülleri klasör dışından statik içe aktarılmaz (yalnız index.js kamuya açık)', () => {
    const walk = (dir, acc = []) => {
      for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        if (e.isDirectory()) { if (e.name !== 'test' && e.name !== 'assets' && e.name !== 'imageEngine') walk(path.join(dir, e.name), acc) } else if (/\.(jsx?|mjs)$/.test(e.name)) acc.push(path.join(dir, e.name))
      }
      return acc
    }
    const offenders = walk(SRC).filter((f) => staticImports(fs.readFileSync(f, 'utf8')).some((s) => /imageEngine\/(?!index(\.js)?$)/.test(s)))
    expect(offenders.map((f) => path.relative(SRC, f))).toEqual([])
  })
})
