import { api } from '../../api/client'
import { storageOwner } from '../../utils/personalStorage.js'

/**
 * E-posta önizlemesi isteğinin PAYLAŞIMI (inceleme 2026-10-18, 3. tur). Sunucu kişi başına aynı anda TEK PDF / önizleme
 * hazırlar (`TechErrorReportController.rendering` işareti; ikinci istek 429 `RATE_LIMITED`). Pencere iki kez açılırsa —
 * React StrictMode'da efekt iki kez koşar, ya da pencere kapatılıp hemen yeniden açılır — ikinci istek ilkinin işaretine
 * takılıyor, ilk yanıt da sıra korumasıyla düşüyordu: pencere `npm run dev`'de hep "okunamadı" diyordu.
 *
 * Kural: aynı kullanıcı × rapor için SÜREN bir istek varsa yenisi atılmaz, ona katılınır. Her çağıranın kendi
 * `AbortController`'ı vardır: iptal eden çağıran yalnız kendi sonucunu bırakır (`{ aborted: true }` ile çözülür, yok
 * sayılır); son çağıran da ayrılınca ağ isteği {@link MAIL_PREVIEW_KEEP_MS} sonra iptal edilir — bu sürede yeniden açılan
 * pencere aynı isteğe katılır. Biten yanıt tutulmaz (her açılış taze okur). Anahtar kullanıcıyı da taşır: çıkıştan sonra
 * giren başka kullanıcı öncekinin isteğine katılmaz.
 */
export const MAIL_PREVIEW_KEEP_MS = 5000

const ABORTED = Object.freeze({ success: false, status: 0, aborted: true, code: 'REQUEST_ABORTED', error: null })
const shares = new Map()

/** Süren paylaşılan önizleme istekleri (test kancası). */
export function mailPreviewShareCount() { return shares.size }

/** Bütün paylaşımları unutur ve ağ isteklerini iptal eder (test kancası). */
export function clearMailPreviewShares() {
  for (const e of shares.values()) {
    if (e.timer) clearTimeout(e.timer)
    try { e.controller.abort() } catch { /* yok */ }
  }
  shares.clear()
}

function start(key, reportId) {
  const controller = new AbortController()
  const entry = { users: 0, controller, timer: null, promise: null }
  entry.promise = (async () => api.techErrors.mailPreview(reportId, { signal: controller.signal }))()
    .finally(() => {
      if (entry.timer) { clearTimeout(entry.timer); entry.timer = null }
      if (shares.get(key) === entry) shares.delete(key)
    })
  shares.set(key, entry)
  return entry
}

/**
 * Önizlemeyi okur; aynı kullanıcı × rapor için süren istek varsa ona katılır. `signal` iptal edilirse dönen söz
 * `{ aborted: true }` ile çözülür (çağıran yok sayar); ağ isteği başka çağıran yoksa kısa süre sonra kesilir.
 */
export function fetchMailPreview(reportId, { signal = null } = {}) {
  if (signal?.aborted) return Promise.resolve(ABORTED)
  const key = `${storageOwner() || ''}|${reportId}`
  const entry = shares.get(key) || start(key, reportId)
  if (entry.timer) { clearTimeout(entry.timer); entry.timer = null }
  entry.users++
  return new Promise((resolve, reject) => {
    let done = false
    const leave = () => {
      if (done) return false
      done = true
      signal?.removeEventListener?.('abort', onAbort)
      entry.users--
      if (entry.users <= 0 && shares.get(key) === entry && !entry.timer) {
        entry.timer = setTimeout(() => {
          entry.timer = null
          if (entry.users > 0 || shares.get(key) !== entry) return
          shares.delete(key)
          try { entry.controller.abort() } catch { /* yok */ }
        }, MAIL_PREVIEW_KEEP_MS)
      }
      return true
    }
    function onAbort() {
      if (leave()) resolve(ABORTED)
    }
    signal?.addEventListener?.('abort', onAbort, { once: true })
    entry.promise.then(
      (v) => { if (leave()) resolve(v) },
      (e) => { if (leave()) reject(e) },
    )
  })
}
