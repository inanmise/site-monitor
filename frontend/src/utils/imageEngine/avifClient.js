// Görsel motoru — AVIF kodlayıcı istemcisi (2026-11-15). Motorun Worker'ında koşar (çekirdek `core.js` platforma ekler;
// ana iş parçacığı yedeğinde AVIF HİÇ sunulmaz). AVIF kodlayıcısı (≈ 3,5 MB WASM) ayrı bir Worker'dadır ve YALNIZ bir AVIF
// kodlaması istendiğinde açılır: bu dosya yalnız Worker'ın ADRESİNİ alır (`?worker&url` — kod değil, dize).
//
// Neden ayrı Worker: WASM kodlaması eşzamanlıdır ve kendi iş parçacığında kesilemez. Ayrı Worker'da her kodlama
// `AVIF_ENCODE_TIMEOUT_MS` ile sınırlanır (aşılırsa Worker SONLANDIRILIR); yükleme `AVIF_LOAD_TIMEOUT_MS` ile. Çağıran
// (iş akışı) her kodlamaya / yüklemeye işin kalan süre bütçesini de verir: daha kısa sınır onunkidir, aşılırsa hata
// `detail: 'time_budget'` olur (iş akışı AVIF'i "süre sınırı" notuyla atlar). Motor işi iptal edilince motor Worker'ı
// sonlandırılır — iç içe AVIF Worker'ı onunla birlikte ölür. Boşta kalan AVIF Worker'ı `AVIF_IDLE_MS` sonra kapatılır.
//
// Hata sözleşmesi: AVIF üretilemiyorsa `EngineError('BROWSER_UNSUPPORTED', { reason: 'avif', detail })` — iş akışı bunu
// "AVIF atlandı" notuna çevirir. Worker betiği ya da WASM sunucuda yoksa (404 / 410 — yuvarlanan dağıtımda isteğin eski
// pod'a düşmesi ya da açık kalmış eski sekme) `detail: 'asset_missing'`: bu da YALNIZ AVIF'e özgüdür, motorun "eski paket"
// kilidini KOYMAZ (inceleme 2026-11-15). Bir YÜKLEME hatası `AVIF_FAIL_COOLDOWN_MS` boyunca hatırlanır: bu sürede AVIF
// denenmez (`avifAvailable()` false, oturum anında reddedilir) — motor Worker'ı kapanıp açılsa da `getAvifBlock` /
// `setAvifBlock` ile ana iş parçacığı üzerinden taşınır.
import avifWorkerUrl from './avif.worker.js?worker&url'
import { AVIF_FAIL_COOLDOWN_MS, EngineError, withTimeout } from './errors.js'

/** Kodlayıcının yüklenmesi (Worker + WASM indirme + derleme) için üst sınır. */
export const AVIF_LOAD_TIMEOUT_MS = 30_000
/** Tek AVIF kodlamasının üst sınırı (motorun öteki kodlayıcılarıyla aynı). */
export const AVIF_ENCODE_TIMEOUT_MS = 90_000
/** Boşta kalan AVIF Worker'ı bu süre sonra kapatılır. */
export const AVIF_IDLE_MS = 30_000
const STALE_CHECK_TIMEOUT_MS = 5000
const BLOCK_DETAIL = /^[a-z_]{1,40}$/

/** Boş (geçerli) WASM modülü: derleme bu bağlamda izinli mi (CSP 'wasm-unsafe-eval') — ağ yok, anında. */
const EMPTY_WASM = new Uint8Array([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00])

const unavailable = (detail) => new EngineError('BROWSER_UNSUPPORTED', { reason: 'avif', detail })

let worker = null
let ready = null
/** Yükleme hatası kaydı `{ until, detail }` — süresi dolunca geçersiz (TEK nesne). */
let block = null
let seq = 0
let idleTimer = null
/** Açık oturumun kuşağı: yeni oturum Worker'daki görseli değiştirir, eski oturumun kodlaması reddedilir (bekleme yok). */
let generation = 0
/** Yüklemenin sıra numarası: yalnız EN SON başlayan yüklemenin hatası hatırlanır (sonlandırılmış eski yükleme sayılmaz). */
let loadSeq = 0
const pending = new Map()           // id → { resolve, reject }
const spoke = new WeakSet()

/** Geçerli yükleme hatası kaydının nedeni (`detail`) ya da null (kayıt yok / süresi doldu). */
export function avifBlocked() {
  if (block && Date.now() < block.until) return block.detail
  block = null
  return null
}

/** Yükleme hatası kaydı (ana iş parçacığına taşınmak üzere) — kopya; yoksa null. */
export function getAvifBlock() {
  return avifBlocked() ? { until: block.until, detail: block.detail } : null
}

/** Ana iş parçacığının taşıdığı kaydı geri yükler (doğrulanır; üst sınır AVIF_FAIL_COOLDOWN_MS). Geçersiz → yok sayılır. */
export function setAvifBlock(b) {
  if (!b || typeof b !== 'object') return
  const until = Number(b.until)
  const now = Date.now()
  if (!Number.isFinite(until) || until <= now) return
  const detail = typeof b.detail === 'string' && BLOCK_DETAIL.test(b.detail) ? b.detail : 'unavailable'
  const capped = Math.min(until, now + AVIF_FAIL_COOLDOWN_MS)
  if (!avifBlocked() || block.until < capped) block = { until: capped, detail }
}

function remember(detail) {
  const d = typeof detail === 'string' && BLOCK_DETAIL.test(detail) ? detail : 'unavailable'
  block = { until: Date.now() + AVIF_FAIL_COOLDOWN_MS, detail: d }
}

/**
 * AVIF burada denenebilir mi (UCUZ — kodlayıcıyı yüklemez): Worker + WebAssembly var, derleme CSP ile engelli değil ve
 * yakın zamanda yükleme hatası yok.
 */
export function avifAvailable() {
  if (avifBlocked()) return false
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
    // AVIF Worker'ının her hatası AVIF'e özgüdür (`reason: 'avif'`); `stale_bundle` (eski bir AVIF Worker'ı) da dosyanın
    // bulunamadığı anlamına gelir — motorun eski paket kilidine DÖNÜŞMEZ
    const params = m.params || {}
    const detail = params.reason === 'stale_bundle' ? 'asset_missing'
      : m.code === 'BROWSER_UNSUPPORTED' && params.reason === 'avif' && typeof params.detail === 'string' ? params.detail : 'encoder'
    p.reject(unavailable(detail))
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
  // Hiç ileti gelmeden düştü: betik yüklenemedi — sunucuda yok mu (404 / 410: dağıtım), gerçek bir hata mı? (≤ 5 sn)
  workerScriptGone().then((gone) => {
    all.forEach((p) => p.reject(unavailable(gone ? 'asset_missing' : 'worker_failed')))
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
  const blocked = avifBlocked()
  if (blocked) return Promise.reject(unavailable(blocked))
  if (ready) return ready
  let w
  try {
    w = new Worker(avifWorkerUrl, { type: 'module' })
  } catch {
    remember('worker')                                          // CSP worker-src / eski tarayıcı: her işte yeniden denenmez
    return Promise.reject(unavailable('worker'))
  }
  worker = w
  const mine = ++loadSeq
  w.onmessage = (e) => onMessage(w, e)
  w.onerror = (e) => { try { e?.preventDefault?.() } catch { /* yok */ } onWorkerError(w) }
  w.onmessageerror = () => onWorkerError(w)
  const p = withTimeout(request({ kind: 'init' }), AVIF_LOAD_TIMEOUT_MS, () => unavailable('load_timeout'))
    .catch((e) => {
      if (worker === w) kill()
      // Yükleme hatası hatırlanır: sonraki işler AVIF_FAIL_COOLDOWN_MS boyunca yeniden beklemez. Bizim kapattığımız
      // Worker (worker_closed) ve yerine yenisi başlamış eski yükleme hatırlanmaz
      const detail = e instanceof EngineError && e.params?.reason === 'avif' ? e.params.detail : 'unavailable'
      if (mine === loadSeq && detail !== 'worker_closed') remember(detail)
      throw e
    })
  ready = p
  return p
}

/** Çağıranın süre bütçesi (ms) üst sınırdan kısaysa onu, değilse üst sınırı döndürür (en az 1 ms). */
const budget = (requested, cap) => {
  const r = Number(requested)
  return Number.isFinite(r) && r > 0 ? Math.max(1, Math.min(cap, Math.floor(r))) : cap
}

/**
 * Kodlama oturumu (platformun `prepare`ına eş): görsel AVIF Worker'ına BİR KEZ (kopya olarak, aktarılarak) gönderilir,
 * ardından farklı kalitelerle kodlanır. `encode(mime, q, { timeoutMs })`: `q` 0..1 (tuval kodlayıcılarıyla aynı ölçek) →
 * Blob; `timeoutMs` (isteğe bağlı) kodlama sınırını kısaltır.
 * @param {{ data: Uint8ClampedArray, width: number, height: number }} image
 * @param {{ speed: number, loadTimeoutMs?: number }} tuning `loadTimeoutMs` yükleme beklemesini kısaltır (işin bütçesi)
 */
export async function prepareAvif(image, tuning) {
  // Motor işi sırayla koşar (aynı anda tek oturum); yine de yeni oturum eskisinin görselini değiştirdiği için eski
  // oturumun kodlaması "kapandı" ile reddedilir — beklenen bir kilit yok (sınırsız bekleme olmasın)
  const gen = ++generation
  clearTimeout(idleTimer)
  try {
    const wait = budget(tuning?.loadTimeoutMs, AVIF_LOAD_TIMEOUT_MS)
    // Kısaltılmış bekleme dolarsa YÜKLEME HATASI sayılmaz (hatırlanmaz): yükleme arkada sürer, sonraki oturum kullanır
    await (wait < AVIF_LOAD_TIMEOUT_MS ? withTimeout(load(), wait, () => unavailable('time_budget')) : load())
    const copy = new Uint8Array(image.data.length)
    copy.set(image.data)
    worker.postMessage({ kind: 'image', width: image.width, height: image.height, buffer: copy.buffer }, [copy.buffer])
  } catch (e) {
    // Bütçe dolduğunda yükleme arkada sürebilir: Worker kimse kullanmazsa AVIF_IDLE_MS sonra kapanır (bellek tutulmaz)
    if (worker && gen === generation) armIdle()
    throw e instanceof EngineError ? e : unavailable('worker')
  }
  let closed = false
  return {
    async encode(mime, quality, opts) {
      if (closed || gen !== generation || !worker) throw unavailable('worker_closed')
      const q = Math.max(0, Math.min(100, Math.round(quality * 100)))
      const limit = budget(opts?.timeoutMs, AVIF_ENCODE_TIMEOUT_MS)
      let res
      try {
        res = await withTimeout(request({ kind: 'encode', quality: q, speed: tuning.speed }), limit,
          () => unavailable(limit < AVIF_ENCODE_TIMEOUT_MS ? 'time_budget' : 'encode_timeout'))
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
  loadSeq += 1
  block = null
  seq = 0
  generation = 0
}
