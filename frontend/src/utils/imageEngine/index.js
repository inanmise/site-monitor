// Görsel motoru — KAMU ARAYÜZÜ (2026-11-14, kaynak analizi "Görsel aracı").
//
// Bu dosya ortak sözleşmenin (image-tool-contract.md §2) taslağıdır: dışa aktarılan adlar ve imzalar SABİTTİR; iç
// gerçekleme motor paketiyle gelir. Ağır iş bir Web Worker'da koşar; bu modül hiçbir zaman açılış (eager) paketine
// statik içe aktarılmaz — yalnız `import()` ile (bkz. lazyChunks.test.js).

export const ENGINE_LIMITS = Object.freeze({ maxInputBytes: 25 * 1024 * 1024, maxPixels: 50_000_000, maxSide: 30_000 })
export const OUTPUT_FORMATS = Object.freeze(['keep', 'jpeg', 'png', 'webp', 'smallest'])
export const RESIZE_METHODS = Object.freeze(['none', 'scale', 'fit', 'cover', 'thumb'])
export const ERROR_CODES = Object.freeze(['UNSUPPORTED_FORMAT', 'TOO_LARGE', 'TOO_MANY_PIXELS', 'DECODE_FAILED',
  'BROWSER_UNSUPPORTED', 'ENCODE_FAILED', 'CANCELLED', 'INVALID_OPTIONS'])
export const NOTE_CODES = Object.freeze(['KEPT_ORIGINAL', 'NOT_UPSCALED', 'TRUECOLOR_KEPT', 'ALPHA_FLATTENED',
  'WEBP_UNAVAILABLE', 'TARGET_NOT_REACHED', 'METADATA_PRESERVED', 'METADATA_NOT_PRESERVED', 'ORIENTATION_APPLIED',
  'ANIMATION_DROPPED', 'SMART_CROP', 'THUMB_PADDED', 'COLOR_PROFILE_CONVERTED'])

/** Motor hatası: `code` ∈ ERROR_CODES, `params` iletide kullanılacak değerler (bayt, piksel …). */
export class EngineError extends Error {
  constructor(code, params = {}) {
    super(code)
    this.name = 'EngineError'
    this.code = code
    this.params = params
  }
}

const notReady = () => Promise.reject(new EngineError('BROWSER_UNSUPPORTED', { reason: 'engine_not_built' }))

/** Bu tarayıcı ne yapabiliyor (bir kez hesaplanır). `webp` = WebP KODLAYABİLİYOR. */
export function engineSupport() {
  return Promise.resolve({ worker: false, webp: false })
}

/** Yalnız başlık okuması (tam çözme yok): { type, width, height, bytes, hasAlpha, animated }. */
export function inspectImage(_blob) {
  return notReady()
}

/** TEK görseli optimize eder (istenirse yeniden boyutlandırır / dönüştürür) — sözleşme §2. */
export function processImage(_blob, _options, _extra) {
  return notReady()
}

/** Önce / sonra görünümü için: Blob → ImageBitmap (EXIF yönü uygulanmış). */
export function decodeForPreview(_blob, _extra) {
  return notReady()
}
