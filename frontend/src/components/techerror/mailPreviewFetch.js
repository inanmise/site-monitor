import { api } from '../../api/client'
import { storageOwner } from '../../utils/personalStorage.js'
import { LANG_STORAGE_KEY, sessionLangOverride } from '../../i18n/dateLocale.js'
import { parseRetryAfter } from '../../utils/errorMessages.js'

/**
 * E-posta önizlemesi isteğinin PAYLAŞIMI (inceleme 2026-10-18, 3. tur). Sunucu kişi başına aynı anda TEK PDF / önizleme
 * hazırlar (`TechErrorReportController.rendering` işareti; ikinci istek 429 `RATE_LIMITED`). Pencere iki kez açılırsa —
 * React StrictMode'da efekt iki kez koşar, ya da pencere kapatılıp hemen yeniden açılır — ikinci istek ilkinin işaretine
 * takılıyor, ilk yanıt da sıra korumasıyla düşüyordu: pencere `npm run dev`'de hep "okunamadı" diyordu.
 *
 * Kural: aynı kullanıcı × rapor × rapor SÜRÜMÜ × görsel revizyonu × arayüz dili için SÜREN bir istek varsa yenisi atılmaz,
 * ona katılınır. Her çağıranın kendi `AbortController`'ı vardır: iptal eden çağıran yalnız kendi sonucunu bırakır (`{ aborted: true }` ile
 * çözülür, yok sayılır); son çağıran da ayrılınca ağ isteği {@link MAIL_PREVIEW_KEEP_MS} sonra iptal edilir — bu sürede
 * yeniden açılan pencere aynı isteğe katılır. Biten yanıt tutulmaz (her açılış taze okur). Anahtar kullanıcıyı da taşır:
 * çıkıştan sonra giren başka kullanıcı öncekinin isteğine katılmaz.
 *
 * <p><b>Sürüm + dil anahtarda (inceleme 2026-10-18, 4. tur):</b> önizlemeyi kapat → düzenle → yeniden aç akışında açılış
 * `ensureSaved` ile kaydeder ve kayıttan ÖNCE başlamış isteğe katılıyordu: pencere eski içeriği gösterirken "Gönder" yenisini
 * yolluyordu. Artık anahtar raporun `version`'ını ve isteğin `X-Lang` dilini taşır; uyuşmayan isteğe katılınmaz (kimsenin
 * beklemediği eski istek hemen kesilir).
 *
 * <p><b>Görsel revizyonu da anahtarda (inceleme 2026-10-18, 5. tur):</b> görsel yükleme / silme raporun sürümünü değiştirmez
 * (sunucuda yalnız içerik sayacı `touchContent`; istemci onu bilmez) — görsel değişikliğinden sonra 5 sn içinde yeniden açılan
 * pencere eski isteğe katılıp ESKİ görselleri gösteriyordu. Düzenleyici her görsel değişikliğinde
 * {@link bumpMailPreviewContent} çağırır; sayaç rapor başına modül düzeyindedir (düzenleyici yeniden kurulsa da sıfırlanmaz).
 *
 * <p><b>Eşzamanlılık reddi:</b> sunucu eski isteği hâlâ hazırlıyor olabilir: yeni istek kişi başı eşzamanlılık reddini (429
 * `RATE_LIMITED` + `Retry-After`) alırsa önerilen süre beklenip yeniden denenir — en çok {@link MAIL_PREVIEW_BUSY_RETRIES} kez,
 * beklemeler toplamı ≤ {@link MAIL_PREVIEW_BUSY_RETRY_TOTAL_S} sn (5. tur: tek 5 sn'lik deneme, önceki hazırlık daha uzun
 * sürünce düşüyordu). Bütçeyi aşan öneri (dakikalık sınırın uzun beklemesi) denenmez: sunucunun yanıtı (iletisi) olduğu gibi
 * döner ve pencerede gösterilir.
 */
export const MAIL_PREVIEW_KEEP_MS = 5000
/** Eşzamanlılık reddinden sonra en çok kaç kez yeniden denenir. */
export const MAIL_PREVIEW_BUSY_RETRIES = 2
/** Yeniden denemelerin beklemeleri toplamı en çok (sn) — sunucunun `RENDER_BUSY_RETRY_SECONDS`'i (5) iki kez sığar. */
export const MAIL_PREVIEW_BUSY_RETRY_TOTAL_S = 15

const ABORTED = Object.freeze({ success: false, status: 0, aborted: true, code: 'REQUEST_ABORTED', error: null })
const shares = new Map()
/** Rapor → görsel revizyonu (düzenleyicinin görsel değişikliği sayacı). */
const contentRevs = new Map()

/** Raporun önizlenen içeriği görsel değişikliğiyle değişti: yeni revizyonu döner (sonraki açılış eski isteğe katılmaz). */
export function bumpMailPreviewContent(reportId) {
  const k = String(reportId)
  const next = (contentRevs.get(k) || 0) + 1
  contentRevs.set(k, next)
  return next
}

/** Raporun şu anki görsel revizyonu (hiç değişmediyse 0). */
export function mailPreviewContentRev(reportId) {
  return contentRevs.get(String(reportId)) || 0
}

/** Süren paylaşılan önizleme istekleri (test kancası). */
export function mailPreviewShareCount() { return shares.size }

/** Bütün paylaşımları unutur ve ağ isteklerini iptal eder (test kancası). */
export function clearMailPreviewShares() {
  for (const e of shares.values()) {
    if (e.timer) clearTimeout(e.timer)
    try { e.controller.abort() } catch { /* yok */ }
  }
  shares.clear()
  contentRevs.clear()
}

/** İsteğin `X-Lang` dili — `api/client.js` `uiLang()` ile AYNI kural (oturumluk zorlama → saklı tercih → en). */
function requestLang() {
  const forced = sessionLangOverride()
  if (forced) return forced
  try { return localStorage.getItem(LANG_STORAGE_KEY) || 'en' } catch { return 'en' }
}

/**
 * Eşzamanlılık reddi mi (429 `RATE_LIMITED` + `Retry-After`)? Bekleme (ms) ya da `null` (yeniden denenmez: başka hata, öneri
 * yok, ya da öneri kalan bütçeyi `budgetS` aşıyor — ör. dakikalık sınırın uzun beklemesi).
 */
export function busyRetryWaitMs(res, budgetS = MAIL_PREVIEW_BUSY_RETRY_TOTAL_S) {
  if (!res || typeof res !== 'object' || res.success || res.aborted) return null
  const status = typeof res.status === 'number' ? res.status : res.errorInfo?.status
  if (status !== 429 || res.code !== 'RATE_LIMITED') return null
  const secs = parseRetryAfter(res.retry_after_seconds ?? res.retryAfter ?? null)
  if (secs == null || secs <= 0 || secs > budgetS) return null
  return Math.ceil(secs) * 1000
}

function sleep(ms, signal) {
  return new Promise((resolve) => {
    if (signal.aborted) { resolve(false); return }
    const id = setTimeout(() => { signal.removeEventListener('abort', onAbort); resolve(true) }, ms)
    function onAbort() { clearTimeout(id); resolve(false) }
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

function start(key, base, reportId) {
  const controller = new AbortController()
  const entry = { users: 0, controller, timer: null, promise: null, base }
  const read = () => api.techErrors.mailPreview(reportId, { signal: controller.signal })
  entry.promise = (async () => {
    let res = await read()
    let spentS = 0
    for (let i = 0; i < MAIL_PREVIEW_BUSY_RETRIES; i++) {
      const wait = busyRetryWaitMs(res, MAIL_PREVIEW_BUSY_RETRY_TOTAL_S - spentS)
      if (wait == null || controller.signal.aborted) return res
      if (!(await sleep(wait, controller.signal))) return ABORTED
      spentS += wait / 1000
      res = await read()
    }
    return res
  })()
    .finally(() => {
      if (entry.timer) { clearTimeout(entry.timer); entry.timer = null }
      if (shares.get(key) === entry) shares.delete(key)
    })
  shares.set(key, entry)
  return entry
}

/** Aynı kullanıcı × rapor için BAŞKA sürüm / dilin kimsenin beklemediği isteği kesilir (sonucu eskidir). */
function dropStale(base, key) {
  for (const [k, e] of shares) {
    if (k === key || e.base !== base || e.users > 0) continue
    if (e.timer) { clearTimeout(e.timer); e.timer = null }
    shares.delete(k)
    try { e.controller.abort() } catch { /* yok */ }
  }
}

/**
 * Önizlemeyi okur; aynı kullanıcı × rapor × sürüm × görsel revizyonu × dil için süren istek varsa ona katılır. `signal` iptal
 * edilirse dönen söz `{ aborted: true }` ile çözülür (çağıran yok sayar); ağ isteği başka çağıran yoksa kısa süre sonra
 * kesilir. `version`: raporun önizlenen sürümü (kayıttan sonra açılan pencere kayıttan önceki isteğe katılmasın);
 * `contentRev`: çağıranın bildiği görsel revizyonu — anahtar onunla modülün kendi sayacının ({@link bumpMailPreviewContent})
 * büyüğünü taşır (biri ilerlediyse eski isteğe katılınmaz).
 */
export function fetchMailPreview(reportId, { signal = null, version = null, contentRev = null } = {}) {
  if (signal?.aborted) return Promise.resolve(ABORTED)
  const base = `${storageOwner() || ''}|${reportId}`
  const rev = Math.max(Number(contentRev) || 0, mailPreviewContentRev(reportId))
  const key = `${base}|v${version ?? ''}|i${rev}|${requestLang()}`
  let entry = shares.get(key)
  if (!entry) {
    dropStale(base, key)
    entry = start(key, base, reportId)
  }
  if (entry.timer) { clearTimeout(entry.timer); entry.timer = null }
  entry.users++
  const mine = entry
  return new Promise((resolve, reject) => {
    let done = false
    const leave = () => {
      if (done) return false
      done = true
      signal?.removeEventListener?.('abort', onAbort)
      mine.users--
      if (mine.users <= 0 && shares.get(key) === mine && !mine.timer) {
        mine.timer = setTimeout(() => {
          mine.timer = null
          if (mine.users > 0 || shares.get(key) !== mine) return
          shares.delete(key)
          try { mine.controller.abort() } catch { /* yok */ }
        }, MAIL_PREVIEW_KEEP_MS)
      }
      return true
    }
    function onAbort() {
      if (leave()) resolve(ABORTED)
    }
    signal?.addEventListener?.('abort', onAbort, { once: true })
    mine.promise.then(
      (v) => { if (leave()) resolve(v) },
      (e) => { if (leave()) reject(e) },
    )
  })
}
