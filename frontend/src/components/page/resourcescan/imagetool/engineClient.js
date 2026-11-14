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

/**
 * Bayat paket hatası (motorun `BROWSER_UNSUPPORTED { reason: 'stale_bundle' }` hatasıyla AYNI biçim): motor modülünün
 * kendisi yüklenemezse (dağıtımdan sonra eski sayfanın parça dosyası sunucuda yok) motor hiç inmediği için kendi hatasını
 * veremez — arayüz yine "sayfa güncellendi, yenileyin" desin ("tarayıcı desteklemiyor" değil).
 */
export function staleBundleError() {
  return Object.assign(new Error('BROWSER_UNSUPPORTED'), { name: 'EngineError', code: 'BROWSER_UNSUPPORTED', params: { reason: 'stale_bundle' } })
}
async function engineModule() {
  try {
    return await loadEngine()
  } catch {
    throw staleBundleError()
  }
}

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

/**
 * Bu tarayıcı ne yapabiliyor: `{ worker, webp }`. Sayfa eski sürümden kalmışsa (motor ya da onun parçaları sunucuda yok)
 * EK alan `staleBundle: true` — arayüz "sayfa güncellendi, yenileyin" der. Motor yüklenemezse de öyledir.
 */
export async function engineSupport() {
  let m
  try {
    m = await loadEngine()
  } catch {
    return { worker: false, webp: false, staleBundle: true }
  }
  try {
    const s = await m.engineSupport()
    return { worker: s?.worker === true, webp: s?.webp === true, ...(s?.staleBundle === true ? { staleBundle: true } : {}) }
  } catch {
    return { worker: false, webp: false }
  }
}

/** Başlık okuması (tam çözme yok) — `EngineError` fırlatabilir. */
export async function inspectImage(blob) {
  const m = await engineModule()
  return m.inspectImage(blob)
}

/** Tek görseli optimize eder — `EngineError` fırlatabilir (`code` ∈ ERROR_CODES). */
export async function processImage(blob, options, extra) {
  const m = await engineModule()
  return m.processImage(blob, options, extra)
}

/** Önce / sonra görünümü için: Blob → ImageBitmap (çağıran `close()` eder). */
export async function decodeForPreview(blob, extra) {
  const m = await engineModule()
  return m.decodeForPreview(blob, extra)
}

/** Hata bir motor hatası mı (`EngineError` — sınıf başka parçadan gelse de ad + kodla tanınır). */
export const isEngineError = (e) => e?.name === 'EngineError' && typeof e?.code === 'string'
