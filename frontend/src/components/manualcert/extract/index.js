/**
 * Yükleme sihirbazının TEK giriş noktası: dosyayı / yapıştırılan metni TARAYICIDA açar, yalnız açık sertifikaları
 * döner (2026-10-08, kullanıcı isteği: özel anahtar ve parola sunucuya ASLA gitmez).
 *
 * <p>Ağır iş (PKCS#12 anahtar türetme / şifre çözme, ZIP açma) mümkünse bir Web Worker'da koşar — sayfa donmaz; Worker
 * kurulamazsa (eski tarayıcı, test ortamı) aynı çekirdek ana iş parçacığında, tembel yüklenen ayrı bir parçadan koşar.
 * Bu dosya forge / fflate İÇE AKTARMAZ (yalnız `import()` ile) — sihirbaz parçası küçük kalır, açılış paketine hiç girmez.
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

/** Worker'da koşturur; Worker kurulamaz / yüklenemezse null (çağıran ana iş parçacığına düşer). */
function runInWorker(message, timeoutMs) {
  return new Promise((resolve) => {
    let worker
    try {
      worker = new Worker(new URL('./extract.worker.js', import.meta.url), { type: 'module' })
    } catch {
      resolve(null)
      return
    }
    let done = false
    const finish = (value) => {
      if (done) return
      done = true
      clearTimeout(timer)
      try { worker.terminate() } catch { /* yok */ }
      resolve(value)
    }
    const timer = setTimeout(() => finish({ timeout: true }), timeoutMs)
    worker.onmessage = (e) => finish(e.data && !e.data.worker_error ? e.data : null)
    worker.onerror = (e) => { try { e.preventDefault?.() } catch { /* yok */ } finish(null) }
    worker.onmessageerror = () => finish(null)
    try { worker.postMessage(message) } catch { finish(null) }
  })
}

const failure = (head, unsupported) => ({
  ...head, entries: [], csr_pem: [], private_keys_removed: 0, certificate_count: 0, needs_password: false,
  password_error: false, password_used: false, unsupported, notes: [],
})

/**
 * @param {{ file?: File|Blob|null, text?: string|null, password?: string }} input
 * @param {{ inline?: boolean, timeoutMs?: number }} [opts] `inline` — Worker'ı hiç deneme (testler)
 * @returns {Promise<object>} `{ format, file_name, size_bytes, entries:[{alias,key_entry,certs:[b64]}], csr_pem,
 *   private_keys_removed, certificate_count, needs_password, password_error, password_used, unsupported, notes }`
 */
export async function extractCertificates({ file = null, text = null, password = '' } = {}, opts = {}) {
  const head = { format: file ? null : 'TEXT', file_name: file?.name ?? null, size_bytes: file ? Number(file.size) || 0 : 0 }
  if (file && Number(file.size) > MAX_BYTES) return failure(head, { reason: 'TOO_LARGE', max_mb: 5 })
  let bytes = null
  if (file) {
    try { bytes = await readBytes(file) } catch { return failure(head, { reason: 'UNREADABLE' }) }
  }
  const message = { bytes, name: file?.name ?? null, text: file ? null : String(text ?? ''), password: password || '' }
  const timeoutMs = opts.timeoutMs ?? EXTRACT_TIMEOUT_MS
  if (!opts.inline && typeof Worker !== 'undefined') {
    const out = await runInWorker(message, timeoutMs)
    if (out?.timeout) return failure(head, { reason: 'TIMEOUT' })
    if (out) return out
  }
  const { extractCore } = await import('./core.js')
  try {
    return await extractCore(message)
  } catch {
    return failure(head, { reason: 'UNREADABLE' })
  }
}
