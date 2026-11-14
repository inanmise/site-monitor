// Görsel motoru — KAMU ARAYÜZÜ (2026-11-14, kaynak analizi "Görsel aracı"; ortak sözleşme image-tool-contract.md §2).
//
// Tümüyle TARAYICIDA çalışır (hiçbir bayt sunucuya ya da dış servise gitmez): başlık okuma → çözme → doğrusal ışıkta,
// önçarpımlı saydamlıkla küçültme → paletli PNG / otomatik kaliteli JPEG / WebP. Ağır iş bir modül Web Worker'ında
// (`engine.worker.js`) koşar; Worker ya da OffscreenCanvas yoksa aynı çekirdek ana iş parçacığında, tembel yüklenen
// ayrı parçadan (`import('./core.js')`) koşar. Bu modül hiçbir zaman açılış (eager) paketine statik içe aktarılmaz —
// yalnız `import()` ile (bkz. lazyChunks.test.js); kendisi yalnız küçük modülleri (sabitler, seçenek doğrulama, başlık
// okuma, önizleme çözmesi) statik alır.
//
// İş kuyruğu: Worker başına aynı anda TEK iş; sıradakiler bekler. İptal (`signal`) çalışan işte Worker'ı SONLANDIRIR
// (iş anında kesilir; bir sonraki iş için yenisi açılır), bekleyen işi kuyruktan çıkarır → EngineError('CANCELLED').
//
// Eski paket (dağıtım sonrası): Worker betiği ya da çekirdek parçası artık sunucuda yoksa (açık sekme, yeni sürüm)
// hata BROWSER_UNSUPPORTED { reason: 'stale_bundle' } olur — arayüz "sayfa güncellendi, yenileyin" diyebilsin. Ayrım:
// Worker HİÇ ileti göndermeden düştüyse / çekirdek içe aktarılamadıysa bu paketin KENDİ dosyası (import.meta.url)
// sunucuya sorulur: 404 / 410 → eski paket; erişilebiliyorsa gerçek Worker hatası (`worker_failed`).
import { ENGINE_LIMITS, ERROR_CODES, EngineError, toEngineError } from './errors.js'
import { normalizeOptions } from './options.js'
import { checkLimits, probeBlob } from './probe.js'
import { decodeBitmap } from './platform.js'

export { ENGINE_LIMITS, OUTPUT_FORMATS, RESIZE_METHODS, ERROR_CODES, NOTE_CODES, EngineError } from './errors.js'

/** Bir işin en uzun süresi: dolarsa Worker sonlandırılır (asılı kalan çözme / kodlama sayfayı bekletmesin). */
export const JOB_TIMEOUT_MS = 5 * 60_000
const SUPPORT_TIMEOUT_MS = 10_000
const IDLE_TERMINATE_MS = 30_000

let worker = null
let seq = 0
let current = null
let queue = []
let idleTimer = null
let supportPromise = null
let corePromise = null
let inlineChain = Promise.resolve()
let staleBundle = false
/** İleti göndermiş (betiği yüklenmiş) Worker'lar. */
const spoke = new WeakSet()
const STALE_CHECK_TIMEOUT_MS = 5000

const cancelled = () => new EngineError('CANCELLED')
const staleError = () => new EngineError('BROWSER_UNSUPPORTED', { reason: 'stale_bundle' })
function loadCore() {
  if (!corePromise) {
    // Başarısız içe aktarma bellekte tutulmaz (geçici ağ hatasından sonra yeniden denenebilsin)
    corePromise = import('./core.js').catch((e) => { corePromise = null; throw e })
  }
  return corePromise
}

/**
 * Bu paketin kendi dosyası sunucuda yok mu (404 / 410)? Evet ise sayfa eski sürümden kalmıştır. Ağ hatası, süre aşımı,
 * başka durum kodu → hayır (bilinmiyorsa eski paket denmez). Süre sınırlı.
 */
async function bundleGone() {
  if (typeof fetch !== 'function') return false
  const ac = typeof AbortController === 'function' ? new AbortController() : null
  const timer = setTimeout(() => { try { ac?.abort() } catch { /* yok */ } }, STALE_CHECK_TIMEOUT_MS)
  try {
    const res = await fetch(import.meta.url, { method: 'HEAD', cache: 'no-store', credentials: 'same-origin', signal: ac?.signal })
    return res?.status === 404 || res?.status === 410
  } catch {
    return false
  } finally {
    clearTimeout(timer)
  }
}

function emit(onProgress, phase, fraction) {
  if (typeof onProgress !== 'function') return
  try { onProgress(phase, fraction) } catch { /* dinleyici hatası işi durdurmaz */ }
}

function killWorker() {
  try { worker?.terminate() } catch { /* yok */ }
  worker = null
}

function spawn() {
  if (typeof Worker === 'undefined') return null
  try {
    const w = new Worker(new URL('./engine.worker.js', import.meta.url), { type: 'module' })
    w.onmessage = (e) => onMessage(w, e)
    w.onerror = (e) => { try { e?.preventDefault?.() } catch { /* yok */ } onWorkerError(w) }
    w.onmessageerror = () => onWorkerError(w)
    return w
  } catch {
    return null
  }
}

function settle(job, fn) {
  if (job.done) return
  job.done = true
  clearTimeout(job.timer)
  if (job.signal && job.onAbort) { try { job.signal.removeEventListener('abort', job.onAbort) } catch { /* yok */ } }
  fn()
}

function pump() {
  if (current) return
  if (staleBundle && queue.length) {                       // eski paket: Worker açılmaz, sıradakiler hemen biter
    const rest = queue
    queue = []
    rest.forEach((job) => settle(job, () => job.reject(staleError())))
  }
  if (!queue.length) {
    clearTimeout(idleTimer)
    if (worker) idleTimer = setTimeout(killWorker, IDLE_TERMINATE_MS)      // boşta bellek tutmasın
    return
  }
  clearTimeout(idleTimer)
  const job = queue.shift()
  if (!worker) worker = spawn()
  if (!worker) {
    settle(job, () => job.reject(new EngineError('BROWSER_UNSUPPORTED', { reason: 'worker' })))
    pump()
    return
  }
  current = job
  job.timer = setTimeout(() => failCurrent(job, new EngineError('ENCODE_FAILED', { reason: 'timeout' })), job.timeoutMs)
  try {
    worker.postMessage({ id: job.id, kind: job.kind, ...job.payload })
  } catch {
    failCurrent(job, new EngineError('BROWSER_UNSUPPORTED', { reason: 'worker' }))
  }
}

/** Çalışan işi hatayla bitirir; Worker sonlandırılır (içindeki iş yarıda kalır), sıradaki iş yeni Worker'la başlar. */
function failCurrent(job, error) {
  if (current !== job) return
  current = null
  killWorker()
  settle(job, () => job.reject(error))
  pump()
}

function cancelJob(job) {
  if (job.done) return
  if (current === job) { failCurrent(job, cancelled()); return }
  queue = queue.filter((j) => j !== job)
  settle(job, () => job.reject(cancelled()))
}

function onMessage(w, e) {
  spoke.add(w)
  if (w !== worker) return
  const m = e?.data || {}
  const job = current
  if (!job || m.id !== job.id) return
  if (m.type === 'progress') { emit(job.onProgress, m.phase, m.fraction); return }
  current = null
  if (m.type === 'result') settle(job, () => job.resolve(m.result))
  else if (m.type === 'support') settle(job, () => job.resolve(m.support))
  else settle(job, () => job.reject(new EngineError(ERROR_CODES.includes(m.code) ? m.code : 'ENCODE_FAILED', m.params || {})))
  pump()
}

function onWorkerError(w) {
  if (w !== worker) return
  const job = current
  if (!job) { killWorker(); return }
  if (spoke.has(w)) { failCurrent(job, new EngineError('BROWSER_UNSUPPORTED', { reason: 'worker_failed' })); return }
  // Hiç ileti gelmeden düştü: betik yüklenemedi. Eski paket mi (dağıtım sonrası 404), gerçek bir Worker hatası mı?
  current = null
  killWorker()
  clearTimeout(job.timer)
  bundleGone().then((gone) => {
    if (gone) staleBundle = true
    settle(job, () => job.reject(gone ? staleError() : new EngineError('BROWSER_UNSUPPORTED', { reason: 'worker_failed' })))
    pump()
  })
}

function submit(kind, payload, { signal, onProgress, timeoutMs }) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) { reject(cancelled()); return }
    const job = { id: ++seq, kind, payload, resolve, reject, onProgress, signal, timeoutMs, done: false, timer: null, onAbort: null }
    if (signal) {
      job.onAbort = () => cancelJob(job)
      signal.addEventListener('abort', job.onAbort, { once: true })
    }
    queue.push(job)
    pump()
  })
}

async function detectSupport() {
  if (typeof Worker !== 'undefined') {
    try {
      const s = await submit('support', {}, { timeoutMs: SUPPORT_TIMEOUT_MS })
      if (s && s.ok) return { worker: true, webp: !!s.webp }
    } catch { /* Worker kurulamadı / yanıt vermedi: ana iş parçacığı */ }
  }
  if (staleBundle) return { worker: false, webp: false, staleBundle: true }
  let webp = false
  try {
    const core = await loadCore()
    const s = await core.browserSupport()
    webp = !!(s.ok && s.webp)
  } catch {
    // çekirdek yüklenemedi: eski paket mi?
    if (await bundleGone()) {
      staleBundle = true
      return { worker: false, webp: false, staleBundle: true }
    }
  }
  return { worker: false, webp }
}

/**
 * Bu tarayıcı ne yapabiliyor (bir kez hesaplanır). `worker` = iş Web Worker'da koşar; `webp` = WebP KODLAYABİLİYOR.
 * `staleBundle: true` (yalnız o durumda, EK alan) = sayfa eski sürümden kalmış, motor dosyaları sunucuda yok.
 */
export function engineSupport() {
  if (!supportPromise) supportPromise = detectSupport()
  return supportPromise
}

const isBlobLike = (b) => !!b && typeof b === 'object' && Number.isFinite(Number(b.size))
  && (typeof b.arrayBuffer === 'function' || typeof b.slice === 'function')

/** Yalnız başlık okuması (tam çözme yok): { type, width, height, bytes, hasAlpha|null, animated }. */
export async function inspectImage(blob) {
  try {
    const info = await probeBlob(blob)
    return { type: info.type, width: info.width, height: info.height, bytes: info.bytes, hasAlpha: info.hasAlpha, animated: info.animated }
  } catch (e) {
    throw toEngineError(e, 'DECODE_FAILED')
  }
}

/** Ana iş parçacığı yedeği: işler sırayla koşar (aynı anda tek iş); iş kesilemez, araya `signal` denetimi girer. */
function runInline(blob, opts, { signal, onProgress }) {
  const job = async () => {
    if (signal?.aborted) throw cancelled()
    let core
    try {
      core = await loadCore()
    } catch (e) {
      if (await bundleGone()) { staleBundle = true; throw staleError() }
      throw e
    }
    return core.processInBrowser(blob, opts, { signal, onProgress, cooperative: true })
  }
  const p = inlineChain.then(job, job)
  inlineChain = p.catch(() => {})
  return p
}

/**
 * TEK görseli optimize eder (istenirse yeniden boyutlandırır / dönüştürür) — sözleşme §2.
 * @param {Blob} blob PNG / JPEG / WebP / GIF / BMP / AVIF
 * @param {object} options { resize, format, quality, targetBytes, background, preserve }
 * @param {{ signal?: AbortSignal, onProgress?: (phase: string, fraction: number) => void }} [extra]
 * @returns {Promise<object>} EngineResult
 */
export async function processImage(blob, options, extra) {
  const { signal, onProgress } = extra || {}
  try {
    const opts = normalizeOptions(options)
    if (!isBlobLike(blob)) throw new EngineError('INVALID_OPTIONS', { field: 'blob', reason: 'type' })
    if (Number(blob.size) > ENGINE_LIMITS.maxInputBytes) {
      throw new EngineError('TOO_LARGE', { bytes: Number(blob.size), maxBytes: ENGINE_LIMITS.maxInputBytes })
    }
    if (signal?.aborted) throw cancelled()
    const support = await engineSupport()
    if (signal?.aborted) throw cancelled()
    if (staleBundle || support.staleBundle) throw staleError()
    const raw = support.worker
      ? await submit('process', { blob, options: opts }, { signal, onProgress, timeoutMs: JOB_TIMEOUT_MS })
      : await runInline(blob, opts, { signal, onProgress })
    if (signal?.aborted) throw cancelled()
    // Özgün dosya DEĞİŞMEDEN korunduysa çağıranın verdiği Blob'un KENDİSİ döner (kopya yok); üstverisi ayıklanmış
    // özgün dosya (METADATA_STRIPPED) çekirdekten yeni Blob olarak gelir
    return raw.kept && !raw.blob ? { ...raw, blob } : raw
  } catch (e) {
    throw toEngineError(e)
  }
}

/**
 * Önce / sonra görünümü için: Blob → ImageBitmap (EXIF yönü uygulanmış). Sınırlar çözmeden ÖNCE başlıktan denetlenir
 * (TOO_MANY_PIXELS); çözülen bitmap başlıktakinden büyükse kapatılır → TOO_MANY_PIXELS `reason: 'header_mismatch'`.
 * `extra.maxSide` (isteğe bağlı): uzun kenarı bundan büyük görsel tarayıcıda küçültülerek çözülür (önizleme belleği).
 */
export async function decodeForPreview(blob, extra) {
  const { signal, maxSide } = extra || {}
  try {
    if (signal?.aborted) throw cancelled()
    const info = await probeBlob(blob)
    checkLimits(info.width, info.height)                    // başlıktaki ölçü sınırı aşıyorsa hiç çözülmez
    if (signal?.aborted) throw cancelled()
    const bitmap = await decodeBitmap(blob, info, maxSide)
    if (signal?.aborted) {
      try { bitmap.close?.() } catch { /* yok */ }
      throw cancelled()
    }
    return bitmap
  } catch (e) {
    throw toEngineError(e, 'DECODE_FAILED')
  }
}

/** Yalnız testler: modül durumunu sıfırlar (Worker, kuyruk, destek belleği). */
export function _resetForTests() {
  clearTimeout(idleTimer)
  killWorker()
  const pending = current ? [current, ...queue] : [...queue]
  current = null
  queue = []
  pending.forEach((job) => settle(job, () => job.reject(cancelled())))
  supportPromise = null
  corePromise = null
  inlineChain = Promise.resolve()
  staleBundle = false
}
