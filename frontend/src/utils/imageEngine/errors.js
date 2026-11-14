// Görsel motoru — sabitler ve hata sınıfı (2026-11-14). Kamu arayüzü `index.js` bunları yeniden dışa aktarır; Worker
// ve ana iş parçacığı yedeği AYNI modülü kullanır (ana iş parçacığında `instanceof EngineError` çalışsın).

export const ENGINE_LIMITS = Object.freeze({ maxInputBytes: 25 * 1024 * 1024, maxPixels: 50_000_000, maxSide: 30_000 })
export const OUTPUT_FORMATS = Object.freeze(['keep', 'jpeg', 'png', 'webp', 'smallest'])
export const RESIZE_METHODS = Object.freeze(['none', 'scale', 'fit', 'cover', 'thumb'])
export const ERROR_CODES = Object.freeze(['UNSUPPORTED_FORMAT', 'TOO_LARGE', 'TOO_MANY_PIXELS', 'DECODE_FAILED',
  'BROWSER_UNSUPPORTED', 'ENCODE_FAILED', 'CANCELLED', 'INVALID_OPTIONS'])
// Sözleşmedeki 13 not + inceleme sonrası EKLENENLER (2026-11-14, sona eklenir, adlar kalıcı):
//  METADATA_STRIPPED    — özgün dosya korundu (KEPT_ORIGINAL) ama üstverisi kayıpsız ayıklandı; görüntü baytları aynı
//  ALPHA_COLORS_ROUNDED — kayıpsız PNG, ama yarı saydam piksellerin renk değerleri tarayıcı çözmesinde yuvarlandı
//                         (görünüm aynı); benzerlik bu yüzden null
export const NOTE_CODES = Object.freeze(['KEPT_ORIGINAL', 'NOT_UPSCALED', 'TRUECOLOR_KEPT', 'ALPHA_FLATTENED',
  'WEBP_UNAVAILABLE', 'TARGET_NOT_REACHED', 'METADATA_PRESERVED', 'METADATA_NOT_PRESERVED', 'ORIENTATION_APPLIED',
  'ANIMATION_DROPPED', 'SMART_CROP', 'THUMB_PADDED', 'COLOR_PROFILE_CONVERTED', 'METADATA_STRIPPED', 'ALPHA_COLORS_ROUNDED'])

/** Çıktı / girdi türü → MIME. */
export const MIME = Object.freeze({ jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp', gif: 'image/gif',
  bmp: 'image/bmp', avif: 'image/avif' })

/** Hedef boyut kipinde bir kenarın inebileceği en küçük ölçü (piksel). */
export const MIN_TARGET_SIDE = 16

/** Motor hatası: `code` ∈ ERROR_CODES, `params` iletide kullanılacak değerler (bayt, piksel …). */
export class EngineError extends Error {
  constructor(code, params = {}) {
    super(code)
    this.name = 'EngineError'
    this.code = code
    this.params = params
  }
}

/** Bilinmeyen bir hatayı motor hatasına çevirir (ileti / yığın dışarı sızmaz; yalnız hata adı). */
export function toEngineError(err, fallbackCode = 'ENCODE_FAILED') {
  if (err instanceof EngineError) return err
  if (err && typeof err.code === 'string' && ERROR_CODES.includes(err.code)) return new EngineError(err.code, err.params || {})
  const name = String(err?.name || 'Error').slice(0, 40)
  // Bellek dolunca tarayıcı RangeError atar ("Array buffer allocation failed", "Invalid typed array length")
  if (err instanceof RangeError) return new EngineError('BROWSER_UNSUPPORTED', { reason: 'memory' })
  if (name === 'AbortError') return new EngineError('CANCELLED')
  return new EngineError(fallbackCode, { reason: 'internal', name })
}

/** Bir sözü süreyle sınırlar: süre dolarsa `onTimeout()` hatasıyla reddeder (asılı kalan çözme / kodlama). */
export function withTimeout(promise, ms, onTimeout) {
  return new Promise((resolve, reject) => {
    let done = false
    const timer = setTimeout(() => { if (!done) { done = true; reject(onTimeout()) } }, ms)
    Promise.resolve(promise).then(
      (v) => { if (!done) { done = true; clearTimeout(timer); resolve(v) } },
      (e) => { if (!done) { done = true; clearTimeout(timer); reject(e) } },
    )
  })
}
