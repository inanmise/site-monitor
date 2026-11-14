// Görsel motoru — tarayıcı yapıştırıcısı (İNCE katman: yalnız burası tuval / ImageBitmap API'lerine dokunur).
// Worker'da OffscreenCanvas, ana iş parçacığı yedeğinde OffscreenCanvas yoksa DOM tuvali kullanılır. Her çözme /
// kodlama sözü SÜREYLE sınırlıdır; bellek / tuval hatası "asılı kalma" değil EngineError olur; ImageBitmap ve tuvaller
// işi biten yerde kapatılır / sıfırlanır (iOS Safari'de toplam tuval belleği sınırlı).
import { EngineError, withTimeout } from './errors.js'

const DECODE_TIMEOUT_MS = 45_000
const ENCODE_TIMEOUT_MS = 90_000

function makeCanvas(width, height) {
  try {
    if (typeof OffscreenCanvas === 'function') return new OffscreenCanvas(width, height)
    if (typeof document !== 'undefined' && document.createElement) {
      const c = document.createElement('canvas')
      c.width = width
      c.height = height
      return c
    }
  } catch {
    throw new EngineError('BROWSER_UNSUPPORTED', { reason: 'memory' })
  }
  throw new EngineError('BROWSER_UNSUPPORTED', { reason: 'canvas' })
}

function context(canvas, options) {
  let cx = null
  try { cx = options ? canvas.getContext('2d', options) : canvas.getContext('2d') } catch { cx = null }
  // Bellek dolunca tarayıcı bağlam vermez: "dosya bozuk" değil, "bu tarayıcıda işlenemedi"
  if (!cx) throw new EngineError('BROWSER_UNSUPPORTED', { reason: 'canvas' })
  return cx
}

function release(canvas) {
  try { if (canvas) { canvas.width = 0; canvas.height = 0 } } catch { /* yok */ }
}

function pixels(cx, width, height) {
  try {
    return cx.getImageData(0, 0, width, height).data
  } catch {
    throw new EngineError('BROWSER_UNSUPPORTED', { reason: 'memory' })
  }
}

function canvasBlob(canvas, type, quality) {
  let p
  try {
    p = typeof canvas.convertToBlob === 'function'
      ? canvas.convertToBlob({ type, quality })
      : new Promise((resolve, reject) => {
        canvas.toBlob((b) => (b ? resolve(b) : reject(new EngineError('ENCODE_FAILED', { reason: 'encoder' }))), type, quality)
      })
  } catch {
    return Promise.reject(new EngineError('ENCODE_FAILED', { reason: 'encoder' }))
  }
  return withTimeout(p, ENCODE_TIMEOUT_MS, () => new EngineError('ENCODE_FAILED', { reason: 'timeout' }))
    .catch((e) => { throw e instanceof EngineError ? e : new EngineError('ENCODE_FAILED', { reason: 'encoder' }) })
}

async function bitmapOf(blob, options) {
  if (typeof createImageBitmap !== 'function') throw new EngineError('BROWSER_UNSUPPORTED', { reason: 'decoder' })
  const attempt = async () => {
    // EXIF yönü uygulanır. Seçeneği tanımayan eski tarayıcı TypeError atar: seçeneksiz açılır (varsayılanı da uygular).
    try {
      return await createImageBitmap(blob, { imageOrientation: 'from-image', ...options })
    } catch (e) {
      if (e instanceof TypeError) return createImageBitmap(blob)
      throw e
    }
  }
  try {
    return await withTimeout(attempt(), DECODE_TIMEOUT_MS, () => new EngineError('DECODE_FAILED', { reason: 'timeout' }))
  } catch (e) {
    if (e instanceof EngineError) throw e
    throw new EngineError('DECODE_FAILED', { reason: 'decoder' })
  }
}

const encodeMemo = new Map()

export const browserPlatform = {
  /** Çözme + tuval + piksel okuma var mı (1 piksellik deneme). */
  async available() {
    if (typeof createImageBitmap !== 'function') return false
    let c = null
    try {
      c = makeCanvas(1, 1)
      const cx = context(c, { willReadFrequently: true })
      cx.fillStyle = '#ff0000'
      cx.fillRect(0, 0, 1, 1)
      return cx.getImageData(0, 0, 1, 1).data[0] === 255
    } catch {
      return false
    } finally {
      release(c)
    }
  },

  /** Tarayıcı bu MIME'ı KODLAYABİLİYOR mu (1 piksel kodlanır, dönen tür denetlenir). */
  async canEncode(mime) {
    if (encodeMemo.has(mime)) return encodeMemo.get(mime)
    let ok = false
    let c = null
    try {
      c = makeCanvas(1, 1)
      context(c).fillRect(0, 0, 1, 1)
      const b = await canvasBlob(c, mime, 0.8)
      ok = !!b && b.type === mime && b.size > 0
    } catch {
      ok = false
    } finally {
      release(c)
    }
    encodeMemo.set(mime, ok)
    return ok
  },

  /** Görseli çözer; pikseller ŞERİT ŞERİT okunur (tam boy ikinci bir kopya istenmedikçe ayrılmaz). */
  async decode(blob) {
    const bitmap = await bitmapOf(blob)
    let strip = null
    let sx = null
    let stripW = 0
    let stripH = 0
    return {
      width: bitmap.width,
      height: bitmap.height,
      /** [x, x+w) × [y, y+h) bölgesi; görselin dışına taşan yer `bg` (RGBA) ile dolar. */
      async readRows(x, y, w, h, bg) {
        if (!strip || stripW !== w || stripH < h) {
          release(strip)
          strip = makeCanvas(w, h)
          stripW = w
          stripH = h
          sx = context(strip, { willReadFrequently: true })
          sx.imageSmoothingEnabled = false
        }
        sx.clearRect(0, 0, w, h)
        const x0 = Math.max(0, x)
        const y0 = Math.max(0, y)
        const x1 = Math.min(bitmap.width, x + w)
        const y1 = Math.min(bitmap.height, y + h)
        const inside = x0 === x && y0 === y && x1 === x + w && y1 === y + h
        if (!inside && bg && bg[3] > 0) {
          sx.fillStyle = `rgba(${bg[0]},${bg[1]},${bg[2]},${bg[3] / 255})`
          sx.fillRect(0, 0, w, h)
          if (x1 > x0 && y1 > y0) sx.clearRect(x0 - x, y0 - y, x1 - x0, y1 - y0)
        }
        if (x1 > x0 && y1 > y0) sx.drawImage(bitmap, x0, y0, x1 - x0, y1 - y0, x0 - x, y0 - y, x1 - x0, y1 - y0)
        return pixels(sx, w, h)
      },
      /** Çözümleme (akıllı kırpma) için küçük kopya. */
      async thumbnail(maxSide) {
        const f = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height))
        const tw = Math.max(1, Math.round(bitmap.width * f))
        const th = Math.max(1, Math.round(bitmap.height * f))
        const c = makeCanvas(tw, th)
        try {
          const cx = context(c, { willReadFrequently: true })
          cx.imageSmoothingEnabled = true
          cx.imageSmoothingQuality = 'high'
          cx.drawImage(bitmap, 0, 0, bitmap.width, bitmap.height, 0, 0, tw, th)
          return { data: pixels(cx, tw, th), width: tw, height: th }
        } finally {
          release(c)
        }
      },
      close() {
        try { bitmap.close?.() } catch { /* yok */ }
        release(strip)
        strip = null
      },
    }
  },

  /** Kodlama oturumu: pikseller tuvale BİR KEZ konur, farklı kalitelerle yeniden kodlanır. */
  async prepare(image) {
    const canvas = makeCanvas(image.width, image.height)
    try {
      const cx = context(canvas)
      const data = image.data instanceof Uint8ClampedArray ? image.data
        : new Uint8ClampedArray(image.data.buffer, image.data.byteOffset, image.data.byteLength)
      cx.putImageData(new ImageData(data, image.width, image.height), 0, 0)
    } catch (e) {
      release(canvas)
      throw e instanceof EngineError ? e : new EngineError('BROWSER_UNSUPPORTED', { reason: 'memory' })
    }
    return {
      async encode(mime, quality) {
        const blob = await canvasBlob(canvas, mime, quality)
        if (!blob || blob.type !== mime) throw new EngineError('ENCODE_FAILED', { reason: 'unsupported_type' })
        return blob
      },
      close() { release(canvas) },
    }
  },

  /** Kodlanmış adayı çözer ve istenen bölgelerin piksellerini döner (benzerlik ölçümü). */
  async decodeRegions(blob, rects, expectW, expectH) {
    const bitmap = await bitmapOf(blob)
    let c = null
    try {
      if (bitmap.width !== expectW || bitmap.height !== expectH) throw new EngineError('ENCODE_FAILED', { reason: 'size_mismatch' })
      let mw = 1
      let mh = 1
      for (const r of rects) { if (r.w > mw) mw = r.w; if (r.h > mh) mh = r.h }
      c = makeCanvas(mw, mh)
      const cx = context(c, { willReadFrequently: true })
      cx.imageSmoothingEnabled = false
      const out = []
      for (const r of rects) {
        cx.clearRect(0, 0, mw, mh)
        cx.drawImage(bitmap, r.x, r.y, r.w, r.h, 0, 0, r.w, r.h)
        out.push(pixels(cx, r.w, r.h))
      }
      return out
    } finally {
      try { bitmap.close?.() } catch { /* yok */ }
      release(c)
    }
  },
}

/** Önizleme için çözme (ana iş parçacığı): EXIF yönü uygulanmış ImageBitmap; `maxSide` verilirse küçültülmüş. */
export function decodeBitmap(blob, info, maxSide) {
  let options
  if (maxSide && info && Math.max(info.width, info.height) > maxSide) {
    const f = maxSide / Math.max(info.width, info.height)
    options = { resizeWidth: Math.max(1, Math.round(info.width * f)), resizeHeight: Math.max(1, Math.round(info.height * f)), resizeQuality: 'high' }
  }
  return bitmapOf(blob, options)
}
