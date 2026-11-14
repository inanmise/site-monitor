/**
 * Görsel motoru bağdaştırıcısı (2026-11-14, kaynak analizi "Görsel aracı" — sözleşme §2).
 *
 * Motor (`utils/imageEngine` — Web Worker'da koşan sıkıştırma / yeniden boyutlandırma) YALNIZ buradan ve YALNIZ
 * `import()` ile yüklenir: hiçbir ekran parçası onu statik içe aktarmaz (`lazyChunks.test.js`). Ekran bileşenleri ve
 * depo (`imageToolStore.js`) bu dosyanın işlevlerini çağırır; vitest bu dosyayı (ya da motoru) taklit eder.
 *
 * Sınırlar ve kod listeleri motorun dışa aktardığı değerlerdir; motor inmeden önce gereken yerde (dosya bırakma
 * denetimi) sözleşmedeki AYNI değerler yedek olarak kullanılır — motor inince onun değerleri geçerlidir.
 */

/** Sözleşme §2 `ENGINE_LIMITS` (yedek — motor yüklenince onunki kullanılır). */
export const FALLBACK_LIMITS = Object.freeze({ maxInputBytes: 25 * 1024 * 1024, maxPixels: 50_000_000, maxSide: 30_000 })

let modulePromise = null

/** Motor modülü — tek sefer yüklenir; yükleme BAŞARISIZ olursa (bayat paket) sonraki çağrı yeniden dener. */
export function loadEngine() {
  if (!modulePromise) {
    modulePromise = import('../../../../utils/imageEngine/index.js').catch((e) => {
      modulePromise = null
      throw e
    })
  }
  return modulePromise
}

/** Motorun sınırları (yüklenemezse sözleşme değerleri). */
export async function engineLimits() {
  try {
    const m = await loadEngine()
    return m.ENGINE_LIMITS || FALLBACK_LIMITS
  } catch {
    return FALLBACK_LIMITS
  }
}

/** Bu tarayıcı ne yapabiliyor: `{ worker, webp }` (motor yüklenemezse ikisi de false — WebP seçeneği kapalı görünür). */
export async function engineSupport() {
  try {
    const m = await loadEngine()
    const s = await m.engineSupport()
    return { worker: s?.worker === true, webp: s?.webp === true }
  } catch {
    return { worker: false, webp: false }
  }
}

/** Başlık okuması (tam çözme yok) — `EngineError` fırlatabilir. */
export async function inspectImage(blob) {
  const m = await loadEngine()
  return m.inspectImage(blob)
}

/** Tek görseli optimize eder — `EngineError` fırlatabilir (`code` ∈ ERROR_CODES). */
export async function processImage(blob, options, extra) {
  const m = await loadEngine()
  return m.processImage(blob, options, extra)
}

/** Önce / sonra görünümü için: Blob → ImageBitmap (çağıran `close()` eder). */
export async function decodeForPreview(blob, extra) {
  const m = await loadEngine()
  return m.decodeForPreview(blob, extra)
}

/** Hata bir motor hatası mı (`EngineError` — sınıf başka parçadan gelse de ad + kodla tanınır). */
export const isEngineError = (e) => e?.name === 'EngineError' && typeof e?.code === 'string'
