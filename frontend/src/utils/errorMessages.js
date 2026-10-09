/**
 * Kullanıcıya görünen API hata metinleri — tek kaynak (2026-10-08, "hata mesajları çok açıklayıcı olsun").
 *
 * Her metin üç soruyu yanıtlar: NE oldu (sade dille), büyük olasılıkla NEDEN, kullanıcı ŞİMDİ NE yapmalı. Dil arayüz
 * dilidir (tr|en); çağıran verir (api/client.js `uiLang()`). Bu modül SAF: React/i18n bağlamı yok — istemci katmanı
 * (fetch) React ağacının dışında çalışır ve İngilizce sözlük tembel yüklendiği için i18n'den okuyamaz. Metinler bu
 * yüzden burada çift dilli tutulur (backend `Msg.t` deseninin karşılığı); kalite kapısı `errorMessageQuality.test.js`.
 *
 * Sözleşme:
 *  - Sunucunun ANLAMLI, özel metni KORUNUR ("Bu alan adı zaten kayıtlı" gibi) — üzerine yazılmaz.
 *  - Teknik metin (yığın izi, Java sınıf adı, `org.springframework…`, HTML hata sayfası, "Unexpected token",
 *    "Failed to fetch"…) ve anlamsız jenerik metin ("Hata", "Sunucu hatası", "Forbidden"…) duruma göre açıklayıcı
 *    metinle DEĞİŞTİRİLİR.
 *  - BÜYÜK_HARF_KOD biçimli metin (VERSION_CONFLICT, DUPLICATE_WEEK…) KORUNUR: bazı ekranlar metinle dallanıyor.
 *  - Teknik künye (HTTP durumu · hata kodu · istek kimliği) metne gömülmez; `errorInfo` olarak taşınır ve ortak
 *    bildirim/afiş bileşenlerinde katlanır "Teknik ayrıntı" olarak gösterilir (`ui/ErrorDetails.jsx`).
 */

const TEXTS = {
  tr: {
    network: 'Sunucuya ulaşılamadı. İnternet ya da VPN bağlantınız kopmuş olabilir ya da sunucu şu an yeniden başlıyor olabilir. Bağlantınızı kontrol edip birkaç saniye sonra tekrar deneyin.',
    offline: 'İnternet bağlantınız yok görünüyor, bu yüzden istek gönderilemedi. Bağlantınız (ya da VPN) geri geldiğinde işlemi tekrar deneyin.',
    timeout: 'Sunucu zamanında yanıt vermedi; sunucu yoğun ya da bağlantınız yavaş olabilir. İşlem arka planda tamamlanmış olabilir: sayfayı yenileyip sonucu kontrol edin, gerekirse tekrar deneyin.',
    aborted: 'İstek tamamlanmadan iptal edildi (sayfadan ayrıldınız ya da işlemi durdurdunuz). Hâlâ gerekiyorsa işlemi yeniden başlatın.',
    nonJson: 'Sunucudan beklenmeyen bir yanıt geldi (veri yerine bir web sayfası). Araya giren bir ağ geçidi, bakım sayfası ya da süresi dolmuş bir oturum olabilir. Sayfayı yenileyip tekrar deneyin; sorun sürerse sistem yöneticilerine bildirin.',
    http400: 'İstek kabul edilmedi: gönderilen bilgilerden biri eksik ya da geçersiz. Formdaki alanları kontrol edip tekrar deneyin.',
    http401: 'Oturumunuz sona ermiş ya da bulunamadı. Sayfayı yenileyip yeniden giriş yapın; kaydedilmemiş değişiklikleriniz varsa önce bir kenara kopyalayın.',
    http403: 'Bu işlem için yetkiniz yok. Gerekiyorsa takım yöneticinizden ya da sistem yöneticisinden bu izni isteyin.',
    http404: 'Aradığınız kayıt ya da adres bulunamadı; silinmiş, taşınmış ya da adı değişmiş olabilir. Listeyi yenileyip tekrar deneyin.',
    http405: 'Bu işlem bu adreste desteklenmiyor; uygulama yeni bir sürüme güncellenmiş olabilir. Sayfayı tamamen yenileyip (Ctrl+F5) tekrar deneyin.',
    http408: 'Sunucu isteğin tamamını zamanında alamadı; bağlantınız yavaş ya da kesintili olabilir. Birkaç saniye sonra tekrar deneyin.',
    http409: 'İşlem bir çakışma yüzünden yapılamadı: kayıt bu arada başka biri tarafından değiştirilmiş ya da aynı kayıt zaten var olabilir. Sayfayı yenileyip güncel hâli üzerinden tekrar deneyin.',
    http413: 'Gönderilen dosya ya da veri izin verilen boyutu aşıyor. Dosyayı küçültüp ya da parçalara bölüp tekrar deneyin.',
    http413Limit: 'Gönderilen dosya ya da veri izin verilen boyutu aşıyor (en fazla {0} MB). Dosyayı küçültüp ya da parçalara bölüp tekrar deneyin.',
    http415: 'Gönderilen içerik türü desteklenmiyor. Dosya yüklüyorsanız desteklenen bir biçim seçin; değilse sayfayı yenileyip tekrar deneyin.',
    http422: 'Gönderilen bilgiler işlenemedi: bazı alanlar geçersiz ya da birbiriyle uyumsuz. Alanları kontrol edip tekrar deneyin.',
    http429: 'Kısa sürede çok fazla istek gönderildi ve sunucu bu isteği geçici olarak reddetti. Bir dakika kadar bekleyip tekrar deneyin.',
    http429Wait: 'Kısa sürede çok fazla istek gönderildi ve sunucu bu isteği geçici olarak reddetti. {0} saniye bekleyip tekrar deneyin.',
    http500: 'Sunucuda beklenmeyen bir hata oluştu ve işlem tamamlanamadı. Birkaç saniye sonra tekrar deneyin; sorun sürerse sistem yöneticilerine teknik ayrıntıdaki istek kimliğiyle bildirin.',
    http502: 'Sunucuya şu anda ulaşılamıyor (ağ geçidi hatası); uygulama yeniden başlıyor ya da güncelleniyor olabilir. Bir dakika sonra tekrar deneyin; sorun sürerse sistem yöneticilerine bildirin.',
    http503: 'Hizmet geçici olarak kullanılamıyor (bakım, yeniden başlatma ya da yoğunluk). Bir dakika sonra tekrar deneyin; sorun sürerse sistem yöneticilerine bildirin.',
    http504: 'Sunucu zamanında yanıt vermedi (ağ geçidi zaman aşımı); işlem arka planda sürüyor olabilir. Bir dakika sonra sonucu kontrol edin, gerekirse tekrar deneyin; sorun sürerse sistem yöneticilerine bildirin.',
    http4xx: 'İstek sunucu tarafından reddedildi (HTTP {0}). Sayfayı yenileyip tekrar deneyin; sorun sürerse sistem yöneticilerine bildirin.',
    http5xx: 'Sunucu isteği işleyemedi (HTTP {0}). Birkaç saniye sonra tekrar deneyin; sorun sürerse sistem yöneticilerine bildirin.',
    unexpected: 'Sunucudan anlaşılamayan bir yanıt geldi. Sayfayı yenileyip tekrar deneyin; sorun sürerse sistem yöneticilerine bildirin.',
  },
  en: {
    network: 'Couldn’t reach the server. Your internet or VPN connection may have dropped, or the server may be restarting right now. Check your connection and try again in a few seconds.',
    offline: 'You appear to be offline, so the request couldn’t be sent. Try again once your internet (or VPN) connection is back.',
    timeout: 'The server didn’t respond in time; it may be busy or your connection may be slow. The action may still have completed in the background: reload the page to check, then try again if needed.',
    aborted: 'The request was cancelled before it finished (you left the page or stopped the action). Start it again if you still need it.',
    nonJson: 'The server sent an unexpected response (a web page instead of data). A gateway, a maintenance page or an expired session may be in the way. Reload the page and try again; if it keeps happening, tell the system administrators.',
    http400: 'The request was not accepted: some of the information sent is missing or invalid. Check the fields in the form and try again.',
    http401: 'Your session has ended or could not be found. Reload the page and sign in again; copy any unsaved changes somewhere safe first.',
    http403: 'You don’t have permission for this action. If you need it, ask your team manager or a system administrator to grant it.',
    http404: 'The record or address you asked for wasn’t found; it may have been deleted, moved or renamed. Refresh the list and try again.',
    http405: 'This action isn’t supported at this address; the app may have been updated to a new version. Fully reload the page (Ctrl+F5) and try again.',
    http408: 'The server didn’t receive the whole request in time; your connection may be slow or unstable. Try again in a few seconds.',
    http409: 'The action couldn’t be completed because of a conflict: someone may have changed the record in the meantime, or the same record already exists. Reload the page and try again on the latest version.',
    http413: 'The file or data you sent is larger than allowed. Make the file smaller or split it into parts, then try again.',
    http413Limit: 'The file or data you sent is larger than allowed (at most {0} MB). Make the file smaller or split it into parts, then try again.',
    http415: 'The content type you sent isn’t supported. If you’re uploading a file, choose a supported format; otherwise reload the page and try again.',
    http422: 'The information you sent couldn’t be processed: some fields are invalid or don’t fit together. Check the fields and try again.',
    http429: 'Too many requests were sent in a short time, so the server turned this one away for now. Wait about a minute and try again.',
    http429Wait: 'Too many requests were sent in a short time, so the server turned this one away for now. Wait {0} seconds and try again.',
    http500: 'An unexpected error occurred on the server and the action wasn’t completed. Try again in a few seconds; if it keeps happening, tell the system administrators and include the request ID from the technical details.',
    http502: 'The server can’t be reached right now (gateway error); the app may be restarting or being updated. Try again in a minute; if it keeps happening, tell the system administrators.',
    http503: 'The service is temporarily unavailable (maintenance, a restart or heavy load). Try again in a minute; if it keeps happening, tell the system administrators.',
    http504: 'The server didn’t answer in time (gateway timeout); the action may still be running in the background. Check the result in a minute and try again if needed; if it keeps happening, tell the system administrators.',
    http4xx: 'The server refused the request (HTTP {0}). Reload the page and try again; if it keeps happening, tell the system administrators.',
    http5xx: 'The server couldn’t process the request (HTTP {0}). Try again in a few seconds; if it keeps happening, tell the system administrators.',
    unexpected: 'The server sent a response that couldn’t be understood. Reload the page and try again; if it keeps happening, tell the system administrators.',
  },
}

/** Kapı testleri ve belgeler için salt-okunur görünüm. */
export const ERROR_TEXTS = TEXTS

/** 'en' ya da 'tr' — bilinmeyen değer İngilizceye düşer (arayüzün varsayılan dili). */
export function errorLang(lang) {
  return String(lang || '').toLowerCase().startsWith('tr') ? 'tr' : 'en'
}

function fmt(str, ...args) {
  let s = str
  args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a ?? '')) })
  return s
}

function text(lang, key, ...args) {
  return fmt(TEXTS[errorLang(lang)][key], ...args)
}

/**
 * `Retry-After` değerini saniyeye çevirir: tam sayı saniye ya da HTTP tarihi. Anlamsız/negatif değer → null.
 * Çok uzun bekleme (1 saatten fazla) metne yazılmaz: kullanıcıya "3600 saniye" demek yardımcı olmaz.
 */
export function parseRetryAfter(value, now = Date.now()) {
  if (value == null || value === '') return null
  const n = Number(value)
  let secs = null
  if (Number.isFinite(n)) secs = Math.ceil(n)
  else {
    const at = Date.parse(String(value))
    if (Number.isFinite(at)) secs = Math.ceil((at - now) / 1000)
  }
  if (secs == null || secs <= 0 || secs > 3600) return null
  return secs
}

/** HTTP durumuna göre açıklayıcı metin. `retryAfter` (sn) 429'da, `limitMb` 413'te kullanılır. */
export function statusMessage(status, { lang, retryAfter, limitMb } = {}) {
  const s = Number(status) || 0
  switch (s) {
    case 400: return text(lang, 'http400')
    case 401: return text(lang, 'http401')
    case 403: return text(lang, 'http403')
    case 404: return text(lang, 'http404')
    case 405: return text(lang, 'http405')
    case 408: return text(lang, 'http408')
    case 409: return text(lang, 'http409')
    case 413: {
      const mb = Number(limitMb)
      return Number.isFinite(mb) && mb > 0 ? text(lang, 'http413Limit', formatMb(mb)) : text(lang, 'http413')
    }
    case 415: return text(lang, 'http415')
    case 422: return text(lang, 'http422')
    case 429: {
      const secs = parseRetryAfter(retryAfter)
      return secs ? text(lang, 'http429Wait', secs) : text(lang, 'http429')
    }
    case 500: return text(lang, 'http500')
    case 502: return text(lang, 'http502')
    case 503: return text(lang, 'http503')
    case 504: return text(lang, 'http504')
    default:
      if (s >= 500) return text(lang, 'http5xx', s)
      if (s >= 400) return text(lang, 'http4xx', s)
      return text(lang, 'unexpected')
  }
}

function formatMb(mb) {
  return Number.isInteger(mb) ? String(mb) : String(Math.round(mb * 10) / 10)
}

/** Yanıt hiç gelmediğinde (ağ, zaman aşımı, iptal) gösterilecek metin. */
export function networkMessage({ lang, kind = 'network', offline } = {}) {
  if (kind === 'timeout') return text(lang, 'timeout')
  if (kind === 'aborted') return text(lang, 'aborted')
  const isOffline = offline ?? (typeof navigator !== 'undefined' && navigator.onLine === false)
  return text(lang, isOffline ? 'offline' : 'network')
}

/** JSON olmayan yanıt (proxy HTML hata sayfası, boş gövde): 4xx/5xx durum metnine, aksi halde "beklenmeyen yanıt"a düşer. */
export function nonJsonMessage(status, { lang, retryAfter } = {}) {
  const s = Number(status) || 0
  if (s >= 400) return statusMessage(s, { lang, retryAfter })
  return text(lang, 'nonJson')
}

// ── Sunucu metni sınıflandırma ───────────────────────────────────────────────

// Paket adları HEDEFLİ: düz "net.ornek.com.tr" gibi alan adları (bu uygulamanın iletilerinde sık) teknik sayılmasın.
const TECHNICAL = [
  /\b(?:java|javax|jakarta|sun)\.[a-z]+\.[\w$.]+|\borg\.(?:springframework|hibernate|apache|postgresql|slf4j|bouncycastle|h2)\.|\bcom\.(?:sitemonitor|fasterxml|zaxxer|sun)\.|\bio\.(?:micrometer|netty)\./,
  /\bat [\w$.<>]+\([\w$]+\.(?:java|kt):\d+\)/,                                  // Java yığın çerçevesi
  /(?:^|[\s:(])[A-Z][A-Za-z0-9]+(?:Exception|Error)\b/,                         // NullPointerException, TypeError, SyntaxError
  /could not execute statement|SQLState|SQL \[|\bJDBC\b|PSQLException|LazyInitialization|No EntityManager|\bHibernate\b/i,
  /Unexpected token|JSON\.parse|is not valid JSON|Unexpected end of JSON|Unexpected character/i,
  /<!doctype|<html[\s>]|<body[\s>]|<head[\s>]|<\/(?:html|body|pre|h1)>/i,       // HTML hata sayfası
  /Request failed with status code|Failed to fetch|NetworkError when attempting|^Load failed$|net::ERR_|ECONNREFUSED|ECONNRESET|ETIMEDOUT/i,
  /Cannot read propert|is not a function|undefined is not|null is not an object/i,
  { test: (s) => /Whitelabel Error Page|No message available/i.test(s) || timestampThenStatus(s) },
]

/**
 * Spring varsayılan hata gövdesi: aynı satırda `"timestamp":` ardından `"status":` — eski
 * `/"timestamp"\s*:.*"status"\s*:/i` ile AYNI sonuç, ama doğrusal (2026-10-09): eski ifade "status"suz bir satırda
 * her "timestamp" için satır sonuna kadar geri izliyordu (k tekrar × satır boyu → sekme donar). `.` satır sonlarını
 * (\n \r \u2028 \u2029) geçmez; anahtarlar birbirinin içinde başlayamaz, bu yüzden global tarama tüm konumları bulur.
 */
const TIMESTAMP_KEY = /"timestamp"\s*:/gi
const STATUS_KEY = /"status"\s*:/gi
const LINE_BREAK = /[\n\r\u2028\u2029]/g
function timestampThenStatus(s) {
  const statusAt = []
  STATUS_KEY.lastIndex = 0
  for (let m = STATUS_KEY.exec(s); m; m = STATUS_KEY.exec(s)) statusAt.push(m.index)
  if (!statusAt.length) return false
  let si = 0
  let lineEnd = -1
  TIMESTAMP_KEY.lastIndex = 0
  for (let m = TIMESTAMP_KEY.exec(s); m; m = TIMESTAMP_KEY.exec(s)) {
    const from = m.index + m[0].length                            // `.*` buradan başlar
    if (from > lineEnd) {                                         // önceki satır sonu geride kaldı → yenisini bul
      LINE_BREAK.lastIndex = from
      const br = LINE_BREAK.exec(s)
      lineEnd = br ? br.index : s.length
    }
    while (si < statusAt.length && statusAt[si] < from) si++
    if (si < statusAt.length && statusAt[si] < lineEnd) return true
  }
  return false
}

// Sondaki boşluk / noktalama — sondan geriye tek geçiş (eski `/[\s.!…:;]+$/u` uzun bir iç dizide O(N²) idi)
const TRAILING_NOISE = /[\s.!…:;]/u
function stripTrailingNoise(s) {
  let e = s.length
  while (e > 0 && TRAILING_NOISE.test(s[e - 1])) e--
  return e === s.length ? s : s.slice(0, e)
}

// Karşılaştırma: kırpılmış, küçük harf, sondaki noktalama atılmış. Tam eşleşme — "Hata: alan boş" gibi açıklamalı metin KALIR.
const VAGUE = new Set([
  'hata', 'error', 'errors', 'bir hata oluştu', 'hata oluştu', 'bir şey ters gitti', 'beklenmeyen hata', 'bilinmeyen hata',
  'something went wrong', 'an error occurred', 'error occurred', 'an unexpected error occurred', 'unexpected error', 'unknown error',
  'işlem başarısız', 'işlem başarısız oldu', 'başarısız', 'operation failed', 'failed', 'request failed',
  'sunucu hatası', 'server error', 'internal server error', 'internal error',
  'bad request', 'unauthorized', 'forbidden', 'not found', 'method not allowed', 'conflict', 'too many requests',
  'payload too large', 'request entity too large', 'service unavailable', 'bad gateway', 'gateway timeout',
  'erişim reddedildi', 'access denied', 'geçersiz istek', 'invalid request', 'geçersiz yanıt', 'invalid response',
  'no value present', 'null', 'undefined', '[object object]', 'kayıt bulunamadı', 'kaynak bulunamadı',
])

// "İ".toLowerCase() = "i̇" (i + U+0307 birleşik nokta) — nokta atılır ki "İşlem başarısız" kümeyle eşleşsin.
function normalize(msg) {
  return stripTrailingNoise(String(msg).trim().toLowerCase().replace(/̇/g, ''))
}

/** Kullanıcıya gösterilmemesi gereken teknik metin mi (yığın izi, sınıf adı, HTML sayfası, tarayıcı ağ iletisi…)? */
export function isTechnicalMessage(msg) {
  if (typeof msg !== 'string' || !msg.trim()) return false
  return TECHNICAL.some((re) => re.test(msg))
}

/** Bilgi taşımayan jenerik metin mi ("Hata", "Sunucu hatası", "Forbidden"…)? */
export function isVagueMessage(msg) {
  if (typeof msg !== 'string') return true
  const n = normalize(msg)
  return n === '' || VAGUE.has(n)
}

/** BÜYÜK_HARF_KOD (VERSION_CONFLICT) — ekranlar bununla dallanabildiği için değiştirilmez. */
export function isCodeLike(msg) {
  return typeof msg === 'string' && /^[A-Z][A-Z0-9_]{2,}$/.test(msg.trim())
}

/**
 * Sunucu metnini korur ya da açıklayıcı metinle değiştirir. Dönen değer DAİMA dolu bir metindir.
 * İyi metin = dolu, teknik değil, jenerik değil (kod biçimli metin de korunur).
 */
export function friendlyServerMessage(raw, status, opts = {}) {
  if (typeof raw === 'string' && raw.trim() && (isCodeLike(raw) || (!isTechnicalMessage(raw) && !isVagueMessage(raw)))) {
    return raw
  }
  return statusMessage(status, opts)
}

// ── Teknik künye (HTTP durumu · kod · istek kimliği) ─────────────────────────

/** `ApiError` — yanıt gelmeyen (ağ/zaman aşımı) ya da açıkça fırlatılan API hatası; `message` kullanıcıya hazırdır. */
export class ApiError extends Error {
  constructor(message, { status = 0, code, kind = 'network', requestId, cause } = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.kind = kind
    this.requestId = requestId
    if (cause !== undefined) this.cause = cause
  }

  /** `String(e)` de yalnız kullanıcı metnini versin ("ApiError: …" öneki tosta düşmesin). */
  toString() { return this.message }
}

/** Gövde/hata nesnesinden künye: { status, code, requestId } — boş alanlar düşer; hiçbiri yoksa null. */
export function errorInfoOf(x) {
  if (x == null || typeof x !== 'object') return null
  const pre = x.errorInfo && typeof x.errorInfo === 'object' ? x.errorInfo : null
  const status = pre?.status ?? (typeof x.status === 'number' ? x.status : undefined)
  const rawCode = pre?.code ?? x.code ?? x.error_code ?? (isCodeLike(x.error) ? x.error : undefined)
  const code = typeof rawCode === 'string' && rawCode.trim() ? rawCode.trim() : undefined
  const rid = pre?.requestId ?? x.requestId ?? x.request_id
  const requestId = typeof rid === 'string' && rid.trim() ? rid.trim() : undefined
  if (status === undefined && !code && !requestId) return null
  const out = {}
  if (status !== undefined) out.status = status
  if (code) out.code = code
  if (requestId) out.requestId = requestId
  return out
}

/** Künyeyi gövdeye SAYILAMAZ (non-enumerable) özellik olarak iliştirir: toEqual/JSON/spread sonuçları değişmez. */
export function attachErrorInfo(body, info) {
  if (!body || typeof body !== 'object' || !info) return body
  try {
    Object.defineProperty(body, 'errorInfo', { value: info, enumerable: false, configurable: true, writable: true })
  } catch { /* dondurulmuş nesne — künyesiz devam */ }
  return body
}

// Son hata metinleri → künye (ortak bildirim/afiş "Teknik ayrıntı"yı çağrı yerine dokunmadan bulsun diye).
// Yalnız metin + durum/kod/istek kimliği tutulur; gövde ya da kişisel veri ASLA. Kısa ömürlü, küçük halka.
const REGISTRY_TTL_MS = 120000
const REGISTRY_MAX = 20
const registry = new Map()

export function rememberErrorInfo(message, info, now = Date.now()) {
  if (typeof message !== 'string' || !message.trim() || !info) return
  registry.delete(message)
  registry.set(message, { info, at: now })
  while (registry.size > REGISTRY_MAX) registry.delete(registry.keys().next().value)
}

export function lookupErrorInfo(message, now = Date.now()) {
  if (typeof message !== 'string') return null
  const hit = registry.get(message)
  if (!hit) return null
  if (now - hit.at > REGISTRY_TTL_MS) { registry.delete(message); return null }
  return hit.info
}

/** Testler için. */
export function clearErrorRegistry() { registry.clear() }

/**
 * HTTP hata gövdesini (status ≥ 400) kullanıcıya hazır hâle getirir — YERİNDE değiştirir ve aynı nesneyi döner:
 *  - `error`: iyi sunucu metni korunur; yoksa `message` (iyi ise) kullanılır; teknik/jenerik/eksikse duruma göre metin;
 *  - `status`: `exposeStatus` ise (varsayılan) ve gövdede yoksa HTTP durumu eklenir. İstemci bunu KAPATIR: gövdeye
 *    sayılabilir `status` yalnız `withStatus` isteyen çağrılara eklenir (eski sözleşme — nocCoverageEvent testi);
 *  - künye (`errorInfo`, sayılamaz; HTTP durumu DAİMA içinde) + kısa ömürlü metin→künye kaydı.
 * Düz nesne olmayan gövde (null, dizi, sayı) `{ success:false, error }` (+ status) ile değiştirilir.
 */
export function normalizeErrorBody(body, { status, requestId, retryAfter, lang, exposeStatus = true } = {}) {
  const plain = body != null && typeof body === 'object' && !Array.isArray(body)
  const out = plain ? body : { success: false }
  if (exposeStatus && out.status === undefined && status) out.status = status
  const limitMb = out.max_mb ?? out.limit_mb ?? out.maxMb
  const ra = retryAfter ?? out.retry_after_seconds ?? out.retry_after ?? out.retryAfter
  const raw = typeof out.error === 'string' && out.error.trim() ? out.error
    : (typeof out.message === 'string' && out.message.trim() ? out.message : null)
  const friendly = friendlyServerMessage(raw, status, { lang, retryAfter: ra, limitMb })
  if (out.error !== friendly) out.error = friendly
  const info = errorInfoOf({ ...out, status: Number(status) || undefined, requestId: out.request_id ?? requestId })
  attachErrorInfo(out, info)
  rememberErrorInfo(out.error, info)
  return out
}
