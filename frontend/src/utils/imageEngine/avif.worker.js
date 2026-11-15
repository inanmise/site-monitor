// Görsel motoru — AVIF kodlayıcı Worker'ı (2026-11-15, kullanıcı kararı: "gerçek AVIF üret"). YALNIZ AVIF istendiğinde
// (ya da "en küçük biçim" AVIF'i denediğinde) motorun kendi Worker'ından açılır (`avifClient.js`, `?worker&url`) — açılış
// paketine, motor Worker'ına ve ana iş parçacığı yedeğine GİRMEZ. Kodlayıcı: @jsquash/avif 2.1.1 (Apache-2.0; squoosh'un
// libavif v1.0.1 + aom derlemesi), TEK İŞ PARÇACIKLI sürüm (`codec/enc/avif_enc.js` — çok iş parçacıklısı
// SharedArrayBuffer / çapraz köken yalıtımı ister; paketin `encode.js` girişi ona dinamik içe aktarmayla geçtiği için
// HİÇ kullanılmaz). WASM dosyası Vite'ın hash'li varlığıdır: kendi kökenimizden gelir, yuvarlanan dağıtımda taşınır.
//
// İleti: { id, kind: 'init' } → { id, type: 'ready' } | { id, type: 'error', code, params }
//        { kind: 'image', width, height, buffer } → (yanıt yok; sıradaki 'encode'lar bu görseli kodlar)
//        { id, kind: 'encode', quality, speed } → { id, type: 'result', buffer } | { id, type: 'error', code, params }
// WASM yüklemesi süreyle sınırlıdır (`WASM_FETCH_TIMEOUT_MS`); kodlama süresini çağıran sınırlar (aşılırsa bu Worker'ı
// sonlandırır — WASM içindeki eşzamanlı kodlama başka türlü kesilemez). Görselin baytları yalnız sonuç olarak döner.
import createAvifModule from '@jsquash/avif/codec/enc/avif_enc.js'
import { defaultOptions } from '@jsquash/avif/meta.js'
import wasmUrl from '@jsquash/avif/codec/enc/avif_enc.wasm?url'

/** WASM indirme + derleme üst sınırı (çağıranın 30 sn'lik yükleme sınırının içinde kalır). Worker girişi dışa aktarmaz. */
const WASM_FETCH_TIMEOUT_MS = 25_000

let modulePromise = null
let image = null

const fail = (code, params) => Object.assign(new Error(code), { engineCode: code, params })
const unavailable = (detail) => fail('BROWSER_UNSUPPORTED', { reason: 'avif', detail })

const gone = (res) => res.status === 404 || res.status === 410

async function get(signal, cache) {
  try {
    return await fetch(wasmUrl, { credentials: 'same-origin', signal, ...(cache ? { cache } : {}) })
  } catch {
    throw signal.aborted ? unavailable('load_timeout') : unavailable('wasm_fetch')
  }
}

/**
 * WASM'ı indirir (süre sınırlı — `signal`). 404 / 410 BİR KEZ yeniden denenir (önbelleksiz): yuvarlanan dağıtımda istek
 * dosyayı henüz taşımayan eski pod'a düşmüş olabilir (inceleme 2026-11-15). İkincisi de 404 / 410 → `asset_missing`
 * (yalnız AVIF'e özgü: motorun "eski paket" kilidi DEĞİL). Toplam süre çağıranın tek sınırının içinde kalır.
 */
async function fetchWasm(signal) {
  let res = await get(signal, null)
  if (gone(res)) {
    try { await res.body?.cancel?.() } catch { /* gövde yok */ }
    res = await get(signal, 'no-store')
    if (gone(res)) throw unavailable('asset_missing')
  }
  if (!res.ok) throw unavailable('wasm_http')
  return res
}

/** Akışla derleme (MIME application/wasm ise); değilse ya da tarayıcıda yoksa tampondan derleme. */
async function instantiate(res, imports) {
  const type = String(res.headers?.get?.('Content-Type') || '').toLowerCase()
  if (typeof WebAssembly.instantiateStreaming === 'function' && type.startsWith('application/wasm')) {
    return WebAssembly.instantiateStreaming(res, imports)
  }
  return WebAssembly.instantiate(await res.arrayBuffer(), imports)
}

function compileFailure(e, signal) {
  // CSP 'wasm-unsafe-eval' yoksa derleme reddedilir (CompileError / EvalError); bellek yetmezse RangeError
  if (e && e.engineCode) return e
  if (signal && signal.aborted) return unavailable('load_timeout')
  if (e instanceof RangeError) return unavailable('memory')
  return unavailable('wasm_blocked')
}

function loadModule() {
  if (modulePromise) return modulePromise
  modulePromise = (async () => {
    const ac = new AbortController()
    const timer = setTimeout(() => ac.abort(), WASM_FETCH_TIMEOUT_MS)
    try {
      const res = await fetchWasm(ac.signal)
      let rejectInit
      const failed = new Promise((_, reject) => { rejectInit = reject })
      const factory = createAvifModule({
        noInitialRun: true,
        print: () => {},
        printErr: () => {},
        // Emscripten'in kendi (süresiz, XHR'li) yüklemesi yerine: indirdiğimiz yanıtı derle
        instantiateWasm(imports, receive) {
          instantiate(res, imports).then((r) => receive(r.instance, r.module), (e) => rejectInit(compileFailure(e, ac.signal)))
          return {}
        },
      })
      const mod = await Promise.race([factory, failed])
      if (!mod || typeof mod.encode !== 'function') throw unavailable('encoder')
      return mod
    } catch (e) {
      throw compileFailure(e, ac.signal)
    } finally {
      clearTimeout(timer)
    }
  })()
  modulePromise.catch(() => { modulePromise = null })
  return modulePromise
}

function post(msg, transfer) {
  try { self.postMessage(msg, transfer || []) } catch { /* ana taraf zaten gitmiş */ }
}

function errorMessage(id, e) {
  const code = e && e.engineCode ? e.engineCode : 'BROWSER_UNSUPPORTED'
  const params = e && e.engineCode ? e.params : { reason: 'avif', detail: e instanceof RangeError ? 'memory' : 'encoder' }
  return { id, type: 'error', code, params }
}

self.onmessage = async (e) => {
  const m = e.data || {}
  if (m.kind === 'init') {
    try { await loadModule(); post({ id: m.id, type: 'ready' }) } catch (err) { post(errorMessage(m.id, err)) }
    return
  }
  if (m.kind === 'image') {
    image = { width: m.width, height: m.height, data: new Uint8Array(m.buffer) }
    return
  }
  if (m.kind !== 'encode') return
  try {
    const mod = await loadModule()
    if (!image) throw unavailable('no_image')
    const out = mod.encode(image.data, image.width, image.height, {
      ...defaultOptions,
      quality: m.quality,
      qualityAlpha: -1,
      speed: m.speed,
      subsample: 1,              // 4:2:0 (squoosh / libavif varsayılanı)
      bitDepth: 8,
      lossless: false,
    })
    if (!out || !out.byteLength) throw unavailable('encoder')
    // Dönen dizi WASM belleğinin kopyasıdır; yine de belleğin kendisine bakıyorsa ayrı kopya al (aktarılabilsin)
    const bytes = out.buffer && out.buffer.byteLength === out.byteLength ? out : out.slice()
    post({ id: m.id, type: 'result', buffer: bytes.buffer }, [bytes.buffer])
  } catch (err) {
    post(errorMessage(m.id, err))
  }
}
