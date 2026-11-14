// Görsel motoru — tarayıcı yapıştırıcısı (platform.js): sahte OffscreenCanvas + createImageBitmap ile
// sözleşmeler: kullanılabilirlik denetimi, kodlama yeteneği (bellekli), şerit okuma (taşan yer bg ile dolar),
// küçük kopya, kodlama oturumu, aday çözme (boy uyuşmazlığı), önizleme çözmede yalan başlık reddi ve hata eşlemeleri.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'

/** Kayıt tutan sahte 2B bağlam: piksel okumada sabit RGBA döner. */
function fakeContext(canvas, cfg) {
  return {
    fillStyle: '',
    imageSmoothingEnabled: true,
    imageSmoothingQuality: 'low',
    calls: [],
    fillRect(...a) { this.calls.push(['fillRect', this.fillStyle, ...a]) },
    clearRect(...a) { this.calls.push(['clearRect', ...a]) },
    drawImage(...a) { this.calls.push(['drawImage', ...a.slice(1)]) },
    putImageData(img, x, y) { this.calls.push(['putImageData', img.width, img.height, x, y]) },
    getImageData(x, y, w, h) {
      if (cfg.failPixels) throw new Error('out of memory')
      const data = new Uint8ClampedArray(w * h * 4)
      for (let i = 0; i < data.length; i += 4) { data[i] = 255; data[i + 3] = 255 }
      return { data, width: w, height: h }
    },
  }
}

function installCanvas(cfg = {}) {
  const made = []
  class FakeOffscreenCanvas {
    constructor(w, h) {
      if (cfg.failCreate) throw new Error('allocation failed')
      this.width = w
      this.height = h
      this.cx = cfg.noContext ? null : fakeContext(this, cfg)
      made.push(this)
    }
    getContext() { return this.cx }
    convertToBlob({ type }) {
      if (cfg.encodeThrows) throw new Error('boom')
      if (cfg.encodeRejects) return Promise.reject(new Error('encoder busy'))
      const outType = cfg.encodeAs ? cfg.encodeAs : type
      return Promise.resolve(new Blob([new Uint8Array(cfg.emptyBlob ? 0 : 8)], { type: outType }))
    }
  }
  vi.stubGlobal('OffscreenCanvas', FakeOffscreenCanvas)
  return made
}

function installBitmap(cfg = {}) {
  const calls = []
  const fn = vi.fn(async (blob, options) => {
    calls.push(options)
    if (cfg.rejectOptions && options) throw new TypeError('unknown option')
    if (cfg.fail) throw new Error('decode failed')
    const w = options?.resizeWidth ?? cfg.width ?? 40
    const h = options?.resizeHeight ?? cfg.height ?? 20
    return { width: cfg.lieWidth ?? w, height: cfg.lieHeight ?? h, close: vi.fn() }
  })
  vi.stubGlobal('createImageBitmap', fn)
  return { fn, calls }
}

class FakeImageData {
  constructor(data, width, height) { this.data = data; this.width = width; this.height = height }
}

let platform
async function load() {
  vi.resetModules()
  platform = await import('../utils/imageEngine/platform.js')
  return platform
}

beforeEach(() => { vi.stubGlobal('ImageData', FakeImageData) })
afterEach(() => { vi.unstubAllGlobals() })

describe('imageEngine platform — kullanılabilirlik ve kodlama yeteneği', () => {
  it('çözücü yoksa available() false', async () => {
    installCanvas()
    vi.stubGlobal('createImageBitmap', undefined)
    const { browserPlatform } = await load()
    expect(await browserPlatform.available()).toBe(false)
  })

  it('tuval ve piksel okuma çalışınca available() true; bağlam yoksa false', async () => {
    installBitmap()
    installCanvas()
    let mod = await load()
    expect(await mod.browserPlatform.available()).toBe(true)
    installCanvas({ noContext: true })
    mod = await load()
    expect(await mod.browserPlatform.available()).toBe(false)
  })

  it('canEncode: dönen tür istenenle aynıysa true ve sonuç bellekte tutulur', async () => {
    installBitmap()
    const made = installCanvas()
    const { browserPlatform } = await load()
    expect(await browserPlatform.canEncode('image/webp')).toBe(true)
    const before = made.length
    expect(await browserPlatform.canEncode('image/webp')).toBe(true)
    expect(made.length).toBe(before)
    expect(made.every(c => c.width === 0 && c.height === 0)).toBe(true)
  })

  it('canEncode: tarayıcı başka tür (PNG) döndürürse ya da kodlayıcı reddederse false', async () => {
    installBitmap()
    installCanvas({ encodeAs: 'image/png' })
    let mod = await load()
    expect(await mod.browserPlatform.canEncode('image/webp')).toBe(false)
    installCanvas({ encodeRejects: true })
    mod = await load()
    expect(await mod.browserPlatform.canEncode('image/jpeg')).toBe(false)
    installCanvas({ emptyBlob: true })
    mod = await load()
    expect(await mod.browserPlatform.canEncode('image/jpeg')).toBe(false)
  })
})

describe('imageEngine platform — çözme', () => {
  it('readRows: tamamen içerideki bölge çizilir, bg ile doldurulmaz', async () => {
    installBitmap({ width: 40, height: 20 })
    const made = installCanvas()
    const { browserPlatform } = await load()
    const img = await browserPlatform.decode(new Blob([new Uint8Array(4)]))
    expect([img.width, img.height]).toEqual([40, 20])
    const data = await img.readRows(0, 0, 40, 10, [255, 255, 255, 255])
    expect(data.length).toBe(40 * 10 * 4)
    const cx = made[made.length - 1].cx
    expect(cx.calls.some(c => c[0] === 'fillRect')).toBe(false)
    expect(cx.calls.find(c => c[0] === 'drawImage')).toEqual(['drawImage', 0, 0, 40, 10, 0, 0, 40, 10])
    img.close()
    expect(made[made.length - 1].width).toBe(0)
  })

  it('readRows: görselin dışına taşan yer bg ile dolar, içerisi temizlenip çizilir; aynı genişlikte şerit yeniden kullanılır', async () => {
    installBitmap({ width: 40, height: 20 })
    const made = installCanvas()
    const { browserPlatform } = await load()
    const img = await browserPlatform.decode(new Blob([new Uint8Array(4)]))
    await img.readRows(-5, 15, 50, 10, [10, 20, 30, 255])
    const strip = made[made.length - 1]
    const fill = strip.cx.calls.find(c => c[0] === 'fillRect')
    expect(fill[1]).toBe('rgba(10,20,30,1)')
    expect(strip.cx.calls.find(c => c[0] === 'drawImage')).toEqual(['drawImage', 0, 15, 40, 5, 5, 0, 40, 5])
    const count = made.length
    await img.readRows(-5, 0, 50, 5, [10, 20, 30, 255])
    expect(made.length).toBe(count)
    // tamamen dışarıda: yalnız bg, çizim yok
    strip.cx.calls.length = 0
    await img.readRows(100, 100, 50, 5, [0, 0, 0, 0])
    expect(strip.cx.calls.some(c => c[0] === 'drawImage')).toBe(false)
    img.close()
  })

  it('thumbnail: uzun kenar maxSide olur, kopya tuvali hemen sıfırlanır', async () => {
    installBitmap({ width: 400, height: 100 })
    const made = installCanvas()
    const { browserPlatform } = await load()
    const img = await browserPlatform.decode(new Blob([new Uint8Array(4)]))
    const t = await img.thumbnail(100)
    expect([t.width, t.height]).toEqual([100, 25])
    expect(t.data.length).toBe(100 * 25 * 4)
    expect(made[made.length - 1].width).toBe(0)
    img.close()
  })

  it('imageOrientation seçeneğini tanımayan eski tarayıcı: seçeneksiz yeniden çözülür', async () => {
    const { fn } = installBitmap({ rejectOptions: true })
    installCanvas()
    const { browserPlatform } = await load()
    const img = await browserPlatform.decode(new Blob([new Uint8Array(4)]))
    expect(img.width).toBe(40)
    expect(fn).toHaveBeenCalledTimes(2)
    img.close()
  })

  it('çözücü hatası DECODE_FAILED/decoder olur; çözücü yoksa BROWSER_UNSUPPORTED', async () => {
    installBitmap({ fail: true })
    installCanvas()
    let mod = await load()
    await expect(mod.browserPlatform.decode(new Blob([]))).rejects.toMatchObject({ code: 'DECODE_FAILED', params: { reason: 'decoder' } })
    vi.stubGlobal('createImageBitmap', undefined)
    mod = await load()
    await expect(mod.browserPlatform.decode(new Blob([]))).rejects.toMatchObject({ code: 'BROWSER_UNSUPPORTED', params: { reason: 'decoder' } })
  })

  it('tuval ayrılamazsa BROWSER_UNSUPPORTED/memory, piksel okunamazsa da memory', async () => {
    installBitmap({ width: 10, height: 10 })
    installCanvas({ failCreate: true })
    let mod = await load()
    let img = await mod.browserPlatform.decode(new Blob([]))
    await expect(img.readRows(0, 0, 10, 10)).rejects.toMatchObject({ code: 'BROWSER_UNSUPPORTED', params: { reason: 'memory' } })
    installCanvas({ failPixels: true })
    mod = await load()
    img = await mod.browserPlatform.decode(new Blob([]))
    await expect(img.readRows(0, 0, 10, 10)).rejects.toMatchObject({ code: 'BROWSER_UNSUPPORTED', params: { reason: 'memory' } })
  })

  it('OffscreenCanvas yoksa DOM tuvaline düşer', async () => {
    installBitmap({ width: 10, height: 10 })
    vi.stubGlobal('OffscreenCanvas', undefined)
    const created = []
    const spy = vi.spyOn(document, 'createElement').mockImplementation((tag) => {
      const c = { tagName: tag, width: 0, height: 0 }
      c.cx = fakeContext(c, {})
      c.getContext = () => c.cx
      created.push(c)
      return c
    })
    try {
      const { browserPlatform } = await load()
      const img = await browserPlatform.decode(new Blob([]))
      await img.readRows(0, 0, 10, 2)
      expect(created[0].tagName).toBe('canvas')
      img.close()
    } finally {
      spy.mockRestore()
    }
  })
})

describe('imageEngine platform — kodlama oturumu ve aday çözme', () => {
  it('prepare: pikseller bir kez konur; istenen türde kodlar, başka tür dönerse ENCODE_FAILED/unsupported_type', async () => {
    installBitmap()
    const made = installCanvas()
    const { browserPlatform } = await load()
    const data = new Uint8Array(4 * 4 * 4)
    const session = await browserPlatform.prepare({ width: 4, height: 4, data })
    const canvas = made[made.length - 1]
    expect(canvas.cx.calls[0]).toEqual(['putImageData', 4, 4, 0, 0])
    const blob = await session.encode('image/jpeg', 0.8)
    expect(blob.type).toBe('image/jpeg')
    session.close()
    expect(canvas.width).toBe(0)

    installCanvas({ encodeAs: 'image/png' })
    const mod = await load()
    const s2 = await mod.browserPlatform.prepare({ width: 2, height: 2, data: new Uint8ClampedArray(16) })
    await expect(s2.encode('image/webp', 0.8)).rejects.toMatchObject({ code: 'ENCODE_FAILED', params: { reason: 'unsupported_type' } })
  })

  it('prepare: kodlayıcı atarsa ENCODE_FAILED/encoder; bağlam yoksa tuval sıfırlanıp BROWSER_UNSUPPORTED', async () => {
    installBitmap()
    installCanvas({ encodeThrows: true })
    let mod = await load()
    const s = await mod.browserPlatform.prepare({ width: 2, height: 2, data: new Uint8ClampedArray(16) })
    await expect(s.encode('image/jpeg', 0.8)).rejects.toMatchObject({ code: 'ENCODE_FAILED', params: { reason: 'encoder' } })
    const made = installCanvas({ noContext: true })
    mod = await load()
    await expect(mod.browserPlatform.prepare({ width: 2, height: 2, data: new Uint8ClampedArray(16) }))
      .rejects.toMatchObject({ code: 'BROWSER_UNSUPPORTED', params: { reason: 'canvas' } })
    expect(made[0].width).toBe(0)
  })

  it('decodeRegions: her bölge ayrı okunur; boyut beklenenden farklıysa ENCODE_FAILED/size_mismatch', async () => {
    installBitmap({ width: 20, height: 10 })
    installCanvas()
    let mod = await load()
    const out = await mod.browserPlatform.decodeRegions(new Blob([]), [{ x: 0, y: 0, w: 4, h: 2 }, { x: 4, y: 2, w: 6, h: 3 }], 20, 10)
    expect(out.map(d => d.length)).toEqual([4 * 2 * 4, 6 * 3 * 4])
    mod = await load()
    await expect(mod.browserPlatform.decodeRegions(new Blob([]), [{ x: 0, y: 0, w: 1, h: 1 }], 21, 10))
      .rejects.toMatchObject({ code: 'ENCODE_FAILED', params: { reason: 'size_mismatch' } })
  })
})

describe('imageEngine platform — önizleme çözme (decodeBitmap)', () => {
  it('maxSide aşılırsa küçültülmüş boyut istenir', async () => {
    const { calls } = installBitmap()
    installCanvas()
    const { decodeBitmap } = await load()
    const bm = await decodeBitmap(new Blob([]), { width: 2000, height: 1000 }, 500)
    expect(calls[0]).toMatchObject({ imageOrientation: 'from-image', resizeWidth: 500, resizeHeight: 250, resizeQuality: 'high' })
    expect([bm.width, bm.height]).toEqual([500, 250])
  })

  it('yön uygulanınca en/boy yer değiştirse de kabul; başlıktan BÜYÜK çıkan bitmap kapatılıp TOO_MANY_PIXELS/header_mismatch', async () => {
    installBitmap({ width: 20, height: 40 })
    installCanvas()
    let mod = await load()
    const ok = await mod.decodeBitmap(new Blob([]), { width: 40, height: 20 })
    expect([ok.width, ok.height]).toEqual([20, 40])

    installBitmap({ lieWidth: 8000, lieHeight: 8000 })
    mod = await load()
    await expect(mod.decodeBitmap(new Blob([]), { width: 40, height: 20 }))
      .rejects.toMatchObject({ code: 'TOO_MANY_PIXELS', params: { reason: 'header_mismatch', width: 8000, height: 8000 } })
  })
})
