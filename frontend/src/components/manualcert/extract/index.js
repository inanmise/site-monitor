/**
 * Yükleme sihirbazının TEK giriş noktası: dosyayı / yapıştırılan metni TARAYICIDA açar, yalnız açık sertifikaları
 * döner (2026-10-08, kullanıcı isteği: özel anahtar ve parola sunucuya ASLA gitmez).
 *
 * <p>Ağır iş (PKCS#12 anahtar türetme / şifre çözme, ZIP açma) mümkünse bir Web Worker'da koşar — sayfa donmaz; Worker
 * kurulamazsa (eski tarayıcı, test ortamı) aynı çekirdek ana iş parçacığında, tembel yüklenen ayrı bir parçadan koşar.
 * Bu dosya forge / fflate İÇE AKTARMAZ (yalnız `import()` ile) — sihirbaz parçası küçük kalır, açılış paketine hiç girmez.
 *
 * <p>Yükleme durumu (2026-10-08): `opts.onProgress` aşamaları bildirir — `{ phase: 'read' }` (dosya okunuyor),
 * `{ phase: 'extract' }` (ayıklama başladı), çekirdeğin ayrıntıları (`zip` n / N, `pkcs12` kdf / decrypt / done, `keystore`).
 * `opts.signal` (AbortSignal) "Vazgeç"tir: Worker hemen sonlandırılır, sonuç `unsupported.reason = 'CANCELLED'` olur.
 */

export const EXTRACT_TIMEOUT_MS = 60_000
const MAX_BYTES = 5 * 1024 * 1024

async function readBytes(file) {
  if (typeof file.arrayBuffer === 'function') return new Uint8Array(await file.arrayBuffer())
  return new Promise((resolve, reject) => {
    const fr = new FileReader()
    fr.onload = () => resolve(new Uint8Array(fr.result))
    fr.onerror = () => reject(fr.error || new Error('read'))
    fr.readAsArrayBuffer(file)
  })
}

/** İlerleme dinleyicisini güvenle çağırır (dinleyici hatası ayıklamayı durdurmaz). */
function emit(onProgress, p) {
  if (typeof onProgress !== 'function') return
  try { onProgress(p) } catch { /* yok say */ }
}

/**
 * Worker'da koşturur; Worker kurulamaz / yüklenemezse null (çağıran ana iş parçacığına düşer). İlerleme mesajları
 * (`type: 'progress'`) dinleyiciye iletilir; iptalde Worker sonlandırılır → `{ cancelled: true }`.
 */
function runInWorker(message, timeoutMs, { onProgress, signal } = {}) {
  return new Promise((resolve) => {
    let worker
    try {
      worker = new Worker(new URL('./extract.worker.js', import.meta.url), { type: 'module' })
    } catch {
      resolve(null)
      return
    }
    let done = false
    const onAbort = () => finish({ cancelled: true })
    const finish = (value) => {
      if (done) return
      done = true
      clearTimeout(timer)
      try { signal?.removeEventListener?.('abort', onAbort) } catch { /* yok */ }
      try { worker.terminate() } catch { /* yok */ }
      resolve(value)
    }
    const timer = setTimeout(() => finish({ timeout: true }), timeoutMs)
    worker.onmessage = (e) => {
      if (e.data?.type === 'progress') { emit(onProgress, e.data.progress); return }
      finish(e.data && !e.data.worker_error ? e.data : null)
    }
    worker.onerror = (e) => { try { e.preventDefault?.() } catch { /* yok */ } finish(null) }
    worker.onmessageerror = () => finish(null)
    if (signal) {
      if (signal.aborted) { finish({ cancelled: true }); return }
      signal.addEventListener('abort', onAbort, { once: true })
    }
    try { worker.postMessage(message) } catch { finish(null) }
  })
}

const failure = (head, unsupported) => ({
  ...head, entries: [], csr_pem: [], private_keys_removed: 0, certificate_count: 0, needs_password: false,
  password_error: false, password_used: false, unsupported, notes: [],
})

/**
 * @param {{ file?: File|Blob|null, text?: string|null, password?: string }} input
 * @param {{ inline?: boolean, timeoutMs?: number, onProgress?: Function, signal?: AbortSignal }} [opts]
 *   `inline` — Worker'ı hiç deneme (testler); `onProgress` — aşama bildirimleri; `signal` — iptal
 * @returns {Promise<object>} `{ format, file_name, size_bytes, entries:[{alias,key_entry,certs:[b64]}], csr_pem,
 *   private_keys_removed, certificate_count, needs_password, password_error, password_used, unsupported, notes }`
 */
export async function extractCertificates({ file = null, text = null, password = '' } = {}, opts = {}) {
  const { onProgress, signal } = opts
  const head = { format: file ? null : 'TEXT', file_name: file?.name ?? null, size_bytes: file ? Number(file.size) || 0 : 0 }
  const cancelled = () => failure(head, { reason: 'CANCELLED' })
  if (signal?.aborted) return cancelled()
  if (file && Number(file.size) > MAX_BYTES) return failure(head, { reason: 'TOO_LARGE', max_mb: 5 })
  let bytes = null
  if (file) {
    emit(onProgress, { phase: 'read', total: Number(file.size) || 0 })
    try { bytes = await readBytes(file) } catch { return failure(head, { reason: 'UNREADABLE' }) }
    if (signal?.aborted) return cancelled()
  }
  emit(onProgress, { phase: 'extract' })
  const message = { bytes, name: file?.name ?? null, text: file ? null : String(text ?? ''), password: password || '' }
  const timeoutMs = opts.timeoutMs ?? EXTRACT_TIMEOUT_MS
  if (!opts.inline && typeof Worker !== 'undefined') {
    const out = await runInWorker(message, timeoutMs, { onProgress, signal })
    if (out?.cancelled) return cancelled()
    if (out?.timeout) return failure(head, { reason: 'TIMEOUT', seconds: Math.round(timeoutMs / 1000) })
    if (out) return out
  }
  const { extractCore } = await import('./core.js')
  if (signal?.aborted) return cancelled()
  let out
  try {
    out = await extractCore(message, { onProgress })
  } catch {
    out = failure(head, { reason: 'UNREADABLE' })
  }
  // Ana iş parçacığında iş kesilemez; iptal edildiyse sonuç atılır (çağıran eski sonucu kullanmaz)
  return signal?.aborted ? cancelled() : out
}
