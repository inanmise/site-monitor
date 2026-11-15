// Görsel motoru — AVIF kodlayıcı istemcisi (2026-11-15). Motorun Worker'ında koşar (çekirdek `core.js` platforma ekler;
// ana iş parçacığı yedeğinde AVIF HİÇ sunulmaz). AVIF kodlayıcısı (≈ 3,5 MB WASM) ayrı bir Worker'dadır ve YALNIZ bir AVIF
// kodlaması istendiğinde açılır: bu dosya yalnız Worker'ın ADRESİNİ alır (`?worker&url` — kod değil, dize).
//
// Neden ayrı Worker: WASM kodlaması eşzamanlıdır ve kendi iş parçacığında kesilemez. Ayrı Worker'da her kodlama
// `AVIF_ENCODE_TIMEOUT_MS` ile sınırlanır (aşılırsa Worker SONLANDIRILIR); yükleme `AVIF_LOAD_TIMEOUT_MS` ile. Motor işi
// iptal edilince motor Worker'ı sonlandırılır — iç içe AVIF Worker'ı onunla birlikte ölür. Boşta kalan AVIF Worker'ı
// `AVIF_IDLE_MS` sonra kapatılır (WASM belleği tutulmasın).
//
// Hata sözleşmesi: AVIF üretilemiyorsa `EngineError('BROWSER_UNSUPPORTED', { reason: 'avif', detail })` — iş akışı bunu
// "AVIF atlandı" notuna çevirir (iş düşmez). Worker betiği ya da WASM sunucuda yoksa (404 / 410, dağıtım sonrası eski
// sekme) `BROWSER_UNSUPPORTED { reason: 'stale_bundle' }` — iş düşer, arayüz "sayfayı yenileyin" der.
import avifWorkerUrl from './avif.worker.js?worker&url'
import { EngineError, withTimeout } from './errors.js'

/** Kodlayıcının yüklenmesi (Worker + WASM indirme + derleme) için üst sınır. */
export const AVIF_LOAD_TIMEOUT_MS = 30_000
/** Tek AVIF kodlamasının üst sınırı (motorun öteki kodlayıcılarıyla aynı). */
export const AVIF_ENCODE_TIMEOUT_MS = 90_000
/** Boşta kalan AVIF Worker'ı bu süre sonra kapatılır. */
export const AVIF_IDLE_MS = 30_000
const STALE_CHECK_TIMEOUT_MS = 5000

/** Boş (geçerli) WASM modülü: derleme bu bağlamda izinli mi (CSP 'wasm-unsafe-eval') — ağ yok, anında. */
const EMPTY_WASM = new Uint8Array([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00])

const unavailable = (detail) => new EngineError('BROWSER_UNSUPPORTED', { reason: 'avif', detail })
const staleError = () => new EngineError('BROWSER_UNSUPPORTED', { reason: 'stale_bundle' })

let worker = null
let ready = null
let stale = false
let seq = 0
let idleTimer = null
/** Açık oturumun kuşağı: yeni oturum Worker'daki görseli değiştirir, eski oturumun kodlaması reddedilir (bekleme yok). */
let generation = 0
const pending = new Map()           // id → { resolve, reject }
const spoke = new WeakSet()

/** AVIF burada denenebilir mi (UCUZ — kodlayıcıyı yüklemez): Worker + WebAssembly var ve derleme CSP ile engelli değil. */
export function avifAvailable() {
  if (stale) return false
  if (typeof Worker !== 'function' || typeof WebAssembly !== 'object' || WebAssembly === null) return false
  if (typeof WebAssembly.Module !== 'function' || typeof WebAssembly.instantiate !== 'function') return false
  try {
    new WebAssembly.Module(EMPTY_WASM)                 // CSP'de 'wasm-unsafe-eval' yoksa CompileError / EvalError
    return true
  } catch {
    return false
  }
}

function kill() {
  clearTimeout(idleTimer)
  idleTimer = null
  try { worker?.terminate() } catch { /* yok */ }
  worker = null
  ready = null
  const all = [...pending.values()]
  pending.clear()
  all.forEach((p) => p.reject(unavailable('worker_closed')))
}

function armIdle() {
  clearTimeout(idleTimer)
  idleTimer = setTimeout(kill, AVIF_IDLE_MS)
}

/** Bu Worker betiği sunucuda yok mu (404 / 410)? Ağ hatası / süre aşımı / başka kod → hayır. */
async function workerScriptGone() {
  if (typeof fetch !== 'function') return false
  const ac = typeof AbortController === 'function' ? new AbortController() : null
  const timer = setTimeout(() => { try { ac?.abort() } catch { /* yok */ } }, STALE_CHECK_TIMEOUT_MS)
  try {
    const res = await fetch(new URL(avifWorkerUrl, self.location.href).href, { method: 'HEAD', cache: 'no-store', credentials: 'same-origin', signal: ac?.signal })
    return res?.status === 404 || res?.status === 410
  } catch {
    return false
  } finally {
    clearTimeout(timer)
  }
}

function onMessage(w, e) {
  spoke.add(w)
  if (w !== worker) return
  const m = e?.data || {}
  const p = pending.get(m.id)
  if (!p) return
  pending.delete(m.id)
  if (m.type === 'result' || m.type === 'ready') p.resolve(m)
  else {
    const params = m.params || {}
    if (m.code === 'BROWSER_UNSUPPORTED' && params.reason === 'stale_bundle') stale = true
    p.reject(new EngineError(m.code === 'BROWSER_UNSUPPORTED' ? m.code : 'BROWSER_UNSUPPORTED',
      m.code === 'BROWSER_UNSUPPORTED' ? params : { reason: 'avif', detail: 'encoder' }))
  }
}

function onWorkerError(w) {
  if (w !== worker) return
  const heard = spoke.has(w)
  const all = [...pending.values()]
  pending.clear()
  try { w.terminate() } catch { /* yok */ }
  worker = null
  ready = null
  if (heard) { all.forEach((p) => p.reject(unavailable('worker_failed'))); return }
  // Hiç ileti gelmeden düştü: betik yüklenemedi — eski paket mi (dağıtım sonrası 404), gerçek bir hata mı?
  workerScriptGone().then((gone) => {
    if (gone) stale = true
    all.forEach((p) => p.reject(gone ? staleError() : unavailable('worker_failed')))
  })
}

/** Worker'a ileti gönderir ve yanıtını bekler (süre sınırı yoksa çağıran sınırlar). */
function request(msg, transfer) {
  return new Promise((resolve, reject) => {
    const id = ++seq
    pending.set(id, { resolve, reject })
    try {
      worker.postMessage({ ...msg, id }, transfer || [])
    } catch {
      pending.delete(id)
      reject(unavailable('worker'))
    }
  })
}

function load() {
  if (stale) return Promise.reject(staleError())
  if (ready) return ready
  let w
  try {
    w = new Worker(avifWorkerUrl, { type: 'module' })
  } catch {
    return Promise.reject(unavailable('worker'))
  }
  worker = w
  w.onmessage = (e) => onMessage(w, e)
  w.onerror = (e) => { try { e?.preventDefault?.() } catch { /* yok */ } onWorkerError(w) }
  w.onmessageerror = () => onWorkerError(w)
  const p = withTimeout(request({ kind: 'init' }), AVIF_LOAD_TIMEOUT_MS, () => unavailable('load_timeout'))
    .catch((e) => {
      if (worker === w) kill()
      throw e
    })
  ready = p
  return p
}

/**
 * Kodlama oturumu (platformun `prepare`ına eş): görsel AVIF Worker'ına BİR KEZ (kopya olarak, aktarılarak) gönderilir,
 * ardından farklı kalitelerle kodlanır. `encode(mime, q)`: `q` 0..1 (tuval kodlayıcılarıyla aynı ölçek) → Blob.
 * @param {{ data: Uint8ClampedArray, width: number, height: number }} image
 * @param {{ speed: number }} tuning
 */
export async function prepareAvif(image, tuning) {
  // Motor işi sırayla koşar (aynı anda tek oturum); yine de yeni oturum eskisinin görselini değiştirdiği için eski
  // oturumun kodlaması "kapandı" ile reddedilir — beklenen bir kilit yok (sınırsız bekleme olmasın)
  const gen = ++generation
  clearTimeout(idleTimer)
  try {
    await load()
    const copy = new Uint8Array(image.data.length)
    copy.set(image.data)
    worker.postMessage({ kind: 'image', width: image.width, height: image.height, buffer: copy.buffer }, [copy.buffer])
  } catch (e) {
    throw e instanceof EngineError ? e : unavailable('worker')
  }
  let closed = false
  return {
    async encode(mime, quality) {
      if (closed || gen !== generation || !worker) throw unavailable('worker_closed')
      const q = Math.max(0, Math.min(100, Math.round(quality * 100)))
      let res
      try {
        res = await withTimeout(request({ kind: 'encode', quality: q, speed: tuning.speed }), AVIF_ENCODE_TIMEOUT_MS,
          () => unavailable('encode_timeout'))
      } catch (e) {
        // Süre aşımı / kodlayıcı hatası: WASM durumu bilinmiyor — Worker kapatılır (sonraki oturum yenisini açar)
        kill()
        throw e instanceof EngineError ? e : unavailable('encoder')
      }
      return new Blob([res.buffer], { type: mime })
    },
    close() {
      if (closed) return
      closed = true
      if (worker && gen === generation) armIdle()
    },
  }
}

/** Yalnız testler: modül durumunu sıfırlar. */
export function _resetAvifForTests() {
  kill()
  stale = false
  seq = 0
  generation = 0
}
