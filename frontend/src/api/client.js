// Tarih yereli i18n'den CANLI okunur: bu dosyadaki formatlayicilar duz fonksiyon,
// hook degil — sabit 'tr-TR' yazdiklari icin Ingilizce arayuzde ayni ekranda iki
// farkli tarih bicimi goruluyordu (bkz. i18n/dateLocale.js).
import { dateLocale, LANG_STORAGE_KEY, sessionLangOverride } from '../i18n/dateLocale.js'
import { toUtc, localDayKey } from '../utils/localDay.js'
import { announceNocCoverageChange, isNocCoverageWrite } from '../utils/nocCoverageEvent.js'
import { announceInventoryAdded, inventoryAddedDomain } from '../utils/inventoryEvent.js'
import { assignLocation, isAccountInactivePayload, signalAccountInactive } from '../utils/accountInactive.js'
import { claimExpiredRedirect, signalSessionExpired } from '../utils/sessionExpiry.js'
import { isMaintenancePayload, signalMaintenance } from '../utils/systemMaintenance.js'
import {
  ApiError, attachErrorInfo, errorInfoOf, networkMessage, nonJsonMessage, normalizeErrorBody, rememberErrorInfo,
} from '../utils/errorMessages.js'

/** Arayüz dili (tr|en) — i18n/index.jsx'teki storedLang ile aynı anahtar; i18n modülünü
 *  import etmemek için (React bağımlılığı, dairesel import riski) burada yalın okunur.
 *  Oturumluk zorlama yalnız açılışta İngilizce sözlük inemediğinde kurulur (dateLocale.setSessionLang). */
function uiLang() {
  const forced = sessionLangOverride()
  if (forced) return forced
  try { return localStorage.getItem(LANG_STORAGE_KEY) || 'en' } catch { return 'en' }
}

const BASE = import.meta.env.VITE_API_BASE ?? '/api'

/** Yanıt başlığı — test sahtelerinde `headers` olmayabilir. */
function headerOf(res, name) {
  try { return res?.headers?.get?.(name) ?? null } catch { return null }
}

/**
 * JSON olmayan yanıt (proxy HTML hata sayfası / boş gövde) → açıklayıcı yük (2026-10-08: eskiden "Hata (HTTP 502)",
 * "Geçersiz yanıt" gibi ne olduğunu da ne yapılacağını da söylemeyen ve İngilizce arayüzde de Türkçe çıkan metinler).
 */
function nonJsonErrorPayload(status, res) {
  const body = {
    success: false,
    status,
    error: nonJsonMessage(status, { lang: uiLang(), retryAfter: headerOf(res, 'Retry-After') }),
    code: status >= 400 ? `HTTP_${status}` : 'NON_JSON_RESPONSE',
  }
  const info = errorInfoOf({ ...body, requestId: headerOf(res, 'X-Request-Id') })
  attachErrorInfo(body, info)
  rememberErrorInfo(body.error, info)
  return body
}

/** Yanıt gelmedi (zaman aşımı / iptal) → yumuşak yük; `kind` 'timeout' | 'aborted'. */
function noResponsePayload(kind) {
  const body = {
    success: false,
    status: 0,
    error: networkMessage({ lang: uiLang(), kind }),
    code: kind === 'aborted' ? 'REQUEST_ABORTED' : 'REQUEST_TIMEOUT',
  }
  const info = { status: 0, code: body.code }
  attachErrorInfo(body, info)
  rememberErrorInfo(body.error, info)
  return body
}

/** Ağ hatası (sunucuya hiç ulaşılamadı) → kullanıcıya hazır metinli `ApiError` (eskiden ham "Failed to fetch"). */
function networkError(cause) {
  const offline = typeof navigator !== 'undefined' && navigator.onLine === false
  const err = new ApiError(networkMessage({ lang: uiLang(), kind: 'network', offline }), {
    status: 0, code: offline ? 'OFFLINE' : 'NETWORK_ERROR', kind: 'network', cause,
  })
  rememberErrorInfo(err.message, { status: 0, code: err.code })
  return err
}

/**
 * Geçici ağ kopmasında TEK yeniden deneme (2026-10-09, kullanıcı bildirimi: kurumsal yük dengeleyici arkasında açılışta
 * `GET /api/alerts/silent-domains` → `net::ERR_CONNECTION_RESET`). Yalnız güvenle tekrarlanabilen okumalar (GET / HEAD),
 * yalnız fetch'in REDDETTİĞİ ağ hatası (bugün `NETWORK_ERROR` olan) ve yalnız BİR kez, kısa bir beklemeden sonra.
 * Denenmez: yazma istekleri, çağıranın iptali, zaman aşımı, çevrimdışı (`OFFLINE`), her HTTP durumu, `transport`
 * (XHR yükleme) yolu. İkinci deneme de düşerse hata bugünkü yoldan aynen döner. Sınırlı: döngü değil, tek ek deneme.
 */
export const NETWORK_RETRY_DELAY_MS = 400

export function isRetryableNetworkFailure(e, method, signal) {
  if (method !== 'GET' && method !== 'HEAD') return false
  if (e?.name === 'AbortError' || e?.name === 'TimeoutError') return false   // iptal ya da süre sınırı
  if (signal?.aborted) return false
  if (typeof navigator !== 'undefined' && navigator.onLine === false) return false
  return true
}

/** Yeniden deneme beklemesi; çağıranın iptali beklemeyi keser (AbortError → request'in "iptal edildi" yükü). */
export function retryDelay(ms, signal) {
  return new Promise((resolve, reject) => {
    const abortError = () => {
      try { return new DOMException('aborted', 'AbortError') } catch { return Object.assign(new Error('aborted'), { name: 'AbortError' }) }
    }
    if (signal?.aborted) { reject(abortError()); return }
    const onAbort = () => { clearTimeout(timer); reject(abortError()) }
    const timer = setTimeout(() => { signal?.removeEventListener?.('abort', onAbort); resolve() }, ms)
    signal?.addEventListener?.('abort', onAbort, { once: true })
  })
}

/** İptal sinyalinin nedeni zaman sınırı mı (deadlineSignal) yoksa çağıranın kendisi mi? */
function abortKind(signal) {
  if (!signal?.aborted) return 'timeout'          // fetchWithTimeout'un kendi zamanlayıcısı
  return signal.reason?.name === 'TimeoutError' ? 'timeout' : 'aborted'
}

/** HTTP hata yanıtı mı (≥ 400)? Test sahteleri `ok` taşımayabilir; durum kodu esastır. */
function isHttpError(res) {
  return typeof res?.status === 'number' ? res.status >= 400 : res?.ok === false
}

/**
 * Bağlantı açılıp hiç yanıt vermezse (OpenShift pod restart / HAProxy bağlantıyı
 * RST'siz düşürürse oluşan yarı-açık socket) `fetch` süresiz asılı kalır. AbortController
 * + timeout ile bunu sınırlandırırız; özellikle açılış akışında (getMe/login) sonsuz
 * "Yükleniyor…" ekranını (pratikte beyaz ekran) önler. timeoutMs <= 0 → timeout uygulanmaz.
 */
const DEFAULT_TIMEOUT_MS = 15000
/**
 * Okuma isteklerinin (GET / HEAD) varsayılan üst süresi (2026-10-08, "zaman aşımı olmayan servis çağrısı" denetimi):
 * önceden `request()` varsayılanı 0'dı — yarı açık bir bağlantı (pod yeniden başladı, vekil RST göndermeden bıraktı)
 * döner simgeyi, kart kilidini ve uçuştaki-istek tekilleştirmesi olan yoklamaları (çevrimiçi sayısı, bakım rozeti, 7/24
 * durumu) SONSUZA dek dondurabiliyordu. Cömert seçildi; yazma istekleri (POST/PUT/PATCH/DELETE) etkilenmez, açıkça
 * `timeoutMs` veren çağrı (0 dahil) kendi değerini korur. Süre dolunca istek yumuşak `timeout` yüküyle döner.
 */
export const DEFAULT_READ_TIMEOUT_MS = 90_000

export async function fetchWithTimeout(url, options = {}, timeoutMs = DEFAULT_TIMEOUT_MS) {
  if (!(timeoutMs > 0) || typeof AbortController === 'undefined') {
    return fetch(url, options)
  }
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  // Çağıranın kendi iptal sinyali KORUNUR (2026-10-08): eskiden süre sınırı onu eziyordu — bileşen kapanınca iptal edilen
  // istek iptal edilmiyordu. İkisi bağlanır; hangisi önce gelirse istek kesilir (request() ayrımı abortKind ile yapar).
  const outer = options.signal
  const onOuterAbort = () => controller.abort()
  if (outer) {
    if (outer.aborted) controller.abort()
    else outer.addEventListener('abort', onOuterAbort, { once: true })
  }
  try {
    return await fetch(url, { ...options, signal: controller.signal })
  } finally {
    clearTimeout(timer)
    if (outer) outer.removeEventListener('abort', onOuterAbort)
  }
}

/**
 * Çağıranın iptal sinyali + üst süre sınırı TEK sinyalde (2026-10-02, HTTP tanılama). `request()` timeoutMs verildiğinde
 * kendi denetleyicisini kurup çağıranın sinyalini ezdiği için uzun ve iptal edilebilir çağrılar timeoutMs YERİNE bunu
 * kullanır: hangisi önce gelirse istek kesilir (AbortError → request'in yumuşak hata yükü). `done()` zamanlayıcıyı ve
 * dinleyiciyi söker.
 */
function deadlineSignal(signal, ms) {
  if (typeof AbortController === 'undefined') return { signal, done: () => {} }
  const c = new AbortController()
  // Neden taşınır (2026-10-08): süre dolması "zaman aşımı", çağıranın iptali "iptal edildi" metnini alsın (abortKind).
  const timeoutReason = () => {
    try { return new DOMException('deadline', 'TimeoutError') } catch { return Object.assign(new Error('deadline'), { name: 'TimeoutError' }) }
  }
  const timer = setTimeout(() => c.abort(timeoutReason()), ms)
  const onAbort = () => c.abort(signal.reason)
  if (signal) {
    if (signal.aborted) c.abort(signal.reason)
    else signal.addEventListener('abort', onAbort, { once: true })
  }
  return { signal: c.signal, done: () => { clearTimeout(timer); signal?.removeEventListener?.('abort', onAbort) } }
}

// Son başarısız API çağrıları (Sorun Bildir otomatik bağlamı) — yalnız yol + durum kodu + zaman.
// Gövde/başlık ASLA saklanmaz; query string de atılır (gizlilik). Halka tampon: en yeni 5 kayıt.
const FAILED_RING_MAX = 5
const failedRequests = []
function recordFailure(path, status) {
  try {
    failedRequests.push({ path: String(path).split('?')[0], status, at: new Date().toISOString().slice(0, 19) })
    if (failedRequests.length > FAILED_RING_MAX) failedRequests.shift()
  } catch { /* yoksay */ }
}
export function getRecentFailures() { return [...failedRequests] }

/** Kontrol Geçmişi v2 yol eşlemesi — uptime türleri domain-anahtarlıdır. */
function historyPath(kind, id) {
  if (kind === 'uptime-http') return `/monitoring/uptime/${encodeURIComponent(id)}/http-history`
  if (kind === 'uptime-ssl')  return `/monitoring/uptime/${encodeURIComponent(id)}/ssl-history`
  return `/monitoring/${kind}/${id}/history`
}

/** Boş/null paramları atarak query string üretir (mevcut get*ResponseSeries deseniyle aynı). */
function historyQuery(params) {
  return new URLSearchParams(
    Object.fromEntries(Object.entries(params).filter(([, v]) => v != null && v !== '')),
  ).toString()
}

async function request(path, options = {}) {
  // FormData gönderiminde Content-Type'ı tarayıcı belirler (multipart boundary)
  const isForm = options.body instanceof FormData
  // timeoutMs opsiyoneldir: varsayılan 0 (timeout yok) → uzun-süren çağrılar
  // (scheduler/diagnostics/checkDomain/upload/sql) ETKİLENMEZ. Açılış çağrıları
  // (getMe) açıkça bir timeout geçirir.
  // withStatus (isteğe bağlı): hata gövdesine HTTP durumu eklenir (`status`) — yalnız 403'ü ayırması gereken çağıranlar
  // için (7/24 arama listesi: 403 → salt okunur). Genel davranış DEĞİŞMEZ.
  // transport (isteğe bağlı, 2026-10-08): fetch yerine aynı sözleşmeli taşıyıcı (`xhrTransport` — yükleme ilerlemesi).
  // Yanıtın işlenişi (401 / bakım / pasif hesap / JSON / withStatus / başarısız çağrı halkası) AYNEN aşağıdaki yoldan geçer.
  const { timeoutMs: timeoutOpt, withStatus = false, transport = null, ...opts } = options
  // Açık değer (0 dahil) kazanır; yoksa okuma istekleri DEFAULT_READ_TIMEOUT_MS, yazma istekleri sınırsız (eski davranış).
  const method = String(opts.method || 'GET').toUpperCase()
  const timeoutMs = timeoutOpt != null ? timeoutOpt : (method === 'GET' || method === 'HEAD' ? DEFAULT_READ_TIMEOUT_MS : 0)
  let res
  try {
    const init = {
      credentials: 'include',
      // X-Lang: sunucu tost/hata metinlerini arayüz dilinde döner (backend Msg.t). Eskiden her
      // ayar sayfası İngilizce arayüzde Türkçe "Ayarlar kaydedildi…" basıyordu (QA ISSUE-001).
      headers: { ...(isForm ? {} : { 'Content-Type': 'application/json' }), 'X-Lang': uiLang(), ...opts.headers },
      ...opts,
    }
    if (transport) {
      res = await transport(`${BASE}${path}`, init)
    } else {
      try {
        res = await fetchWithTimeout(`${BASE}${path}`, init, timeoutMs)
      } catch (first) {
        if (!isRetryableNetworkFailure(first, method, opts.signal)) throw first
        await retryDelay(NETWORK_RETRY_DELAY_MS, opts.signal)   // TEK ek deneme (sınırlı; bkz. NETWORK_RETRY_DELAY_MS)
        res = await fetchWithTimeout(`${BASE}${path}`, init, timeoutMs)
      }
    }
  } catch (e) {
    // Timeout (abort) → asılı kalmak yerine yumuşak hata payload'ı döndür; böylece
    // çağıran (örn. App.jsx getMe.then) authChecked'i true yapıp login'i gösterir.
    // Diğer ağ hataları reject olmaya devam eder (çağıranın .catch'i) — ama ham "Failed to fetch" yerine kullanıcıya
    // hazır metinli ApiError ile (status 0, code NETWORK_ERROR / OFFLINE; özgün hata `cause`ta).
    if (e?.name === 'AbortError' || e?.name === 'TimeoutError') {
      recordFailure(path, 0)
      return noResponsePayload(e?.name === 'TimeoutError' ? 'timeout' : abortKind(opts.signal))
    }
    recordFailure(path, 0)
    throw e instanceof ApiError ? e : networkError(e)
  }
  if (!res.ok) recordFailure(path, res.status)
  if (res.status === 401) {
    // PASİF HESAP (2026-10-02, kullanıcı kararı): gövde ACCOUNT_INACTIVE taşıyorsa YÖNLENDİRME YOK — uygulama bloklayan
    // "Hesabınız pasife alındı" penceresini açar (utils/accountInactive.js). Bayrak hemen silinir: aynı anda düşen öteki
    // 401'ler pencerenin önüne geçip /?session=expired'a götüremez. Açılıştaki /me (bayrak yok) aynı sinyali verir; App
    // o durumda pencere değil giriş sayfası + pasif bildirimi gösterir.
    let body401 = null
    try { body401 = typeof res.json === 'function' ? await res.json() : null } catch { body401 = null }
    if (isAccountInactivePayload(body401)) {
      try { sessionStorage.removeItem('sm.session.active') } catch { /* sessionStorage yok */ }
      signalAccountInactive()
      return null
    }
    // SİSTEM BAKIMI (2026-10-02, kullanıcı kararı): bakım başladı ve oturum sunucuda kesildi (MAINTENANCE) — pasif hesap
    // deseninin aynısı: YÖNLENDİRME YOK, uygulama kısa geri sayımlı bakım penceresini açar, sonra /?session=maintenance.
    if (isMaintenancePayload(body401)) {
      try { sessionStorage.removeItem('sm.session.active') } catch { /* sessionStorage yok */ }
      signalMaintenance(body401.maintenance)
      return null
    }
    // Session expired or invalidated (typically: pod restart wiped in-memory
    // sessions). Don't redirect during the initial auth bootstrap or from the
    // login endpoint itself — App.jsx already handles user=null by rendering
    // the login form. Only force a hard reload when we previously had an
    // authenticated session (flag set by api.login on success).
    if (typeof window !== 'undefined' &&
        sessionStorage.getItem('sm.session.active') === '1') {
      try { sessionStorage.removeItem('sm.session.active') } catch {}
      // Döngü sigortası (2026-10-09): 60 sn içinde 2 yönlendirme olduysa sayfa yeniden YÜKLENMEZ; giriş formu yerinde
      // açılır (utils/sessionExpiry.js — çok kopyalı yanlış kurulumda yükle → 401 → yükle döngüsü olmasın)
      if (claimExpiredRedirect()) assignLocation('/?session=expired')
      else signalSessionExpired()
    }
    return null
  }
  let json
  try {
    json = await res.json()
  } catch {
    // Non-JSON response (HTML error page from proxy / empty body) — graceful fallback
    return nonJsonErrorPayload(res.status, res)
  }
  let plain = json != null && typeof json === 'object' && !Array.isArray(json)
  if (withStatus && !res.ok && plain && json.status === undefined) json.status = res.status
  // HTTP hata gövdesi (2026-10-08): anlamlı sunucu metni korunur; teknik/jenerik/eksik metin duruma göre açıklayıcı
  // metne çevrilir; künye (durum · kod · istek kimliği) sayılamaz `errorInfo`da ve ortak bildirimin "Teknik ayrıntı"sında.
  // Gövdeye sayılabilir `status` YİNE yalnız withStatus ile eklenir (yukarıda) — diğer uçların gövde şekli değişmez.
  if (isHttpError(res)) {
    json = normalizeErrorBody(json, {
      status: res.status,
      requestId: headerOf(res, 'X-Request-Id'),
      retryAfter: headerOf(res, 'Retry-After'),
      lang: uiLang(),
      exposeStatus: false,
    })
    plain = true
  }
  // 7/24 kapsamını değiştiren başarılı yazma → önbellekli yüzeyler (Pano şeridi, form seçenekleri) tazelensin
  if (res.ok && !(plain && json.success === false) && isNocCoverageWrite(path, opts)) announceNocCoverageChange()
  // Yeni kart doğuran başarılı yazma (ekle/aktar/geri yükle) → Genel Bakış o alan adının verisi gelene dek kısa aralıklarla tazelesin
  if (res.ok && !(plain && json.success === false)) announceInventoryAdded(inventoryAddedDomain(path, opts, json))
  return json
}

/**
 * Kodla giriş uçları için ham POST (2026-10-02) — 401 / 403 / 409 / 423 / 429 gövdeleri çağırana AYNEN döner (+ `status`).
 * Kod gövdede taşınır; bu yardımcı hiçbir şeyi saklamaz ve loglamaz (başarısız çağrı halkasına yalnız yol + durum düşer).
 */
async function otpPost(path, payload) {
  let r
  try {
    r = await fetchWithTimeout(`${BASE}${path}`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-Lang': uiLang() },
      body: JSON.stringify(payload),
    })
  } catch (e) {
    recordFailure(path, 0)
    const timeout = e?.name === 'AbortError'
    return {
      success: false, status: 0, networkError: true, timeout,
      error: networkMessage({ lang: uiLang(), kind: timeout ? 'timeout' : 'network' }),
    }
  }
  if (!r.ok) recordFailure(path, r.status)
  let body
  try { body = await r.json() } catch { body = nonJsonErrorPayload(r.status, r) }
  if (body == null || typeof body !== 'object' || Array.isArray(body)) body = nonJsonErrorPayload(r.status, r)
  return { ...body, status: r.status }
}

/**
 * XMLHttpRequest taşıyıcısı (2026-10-08, manuel sertifika yükleme durumu): fetch'in veremediği GERÇEK yükleme ilerlemesi
 * (`upload.onprogress`). `request()`'in `transport` kancasıyla kullanılır — 401 (oturum bitti / bakım / pasif hesap), JSON,
 * withStatus, başarısız çağrı halkası işleyişi fetch yoluyla AYNIDIR. Dönen nesne Response'un request()'in kullandığı yüzü
 * (`ok`, `status`, `headers.get`, `json()`). Ağ hatası TypeError, iptal (signal) AbortError olarak reddedilir (fetch gibi).
 * İlerleme yalnız BAYT SAYISI taşır — gövde (sertifikalar) hiçbir yere yazılmaz.
 *
 * @param {{ onProgress?: Function, signal?: AbortSignal }} opts `onProgress({ phase: 'upload', loaded, total })` gövde
 *   giderken, `onProgress({ phase: 'sent' })` gövde tamamen gönderildiğinde (artık sunucu işliyor)
 */
function xhrTransport({ onProgress, signal } = {}) {
  return (url, init = {}) => new Promise((resolve, reject) => {
    const abortError = () => { const e = new Error('aborted'); e.name = 'AbortError'; return e }
    if (signal?.aborted) { reject(abortError()); return }
    const xhr = new XMLHttpRequest()
    const onAbortSignal = () => { try { xhr.abort() } catch { /* yok */ } }
    const cleanup = () => { try { signal?.removeEventListener?.('abort', onAbortSignal) } catch { /* yok */ } }
    const report = (p) => { if (typeof onProgress === 'function') { try { onProgress(p) } catch { /* yok say */ } } }
    xhr.open(init.method || 'GET', url, true)
    xhr.withCredentials = init.credentials === 'include'
    for (const [k, v] of Object.entries(init.headers || {})) { if (v != null) xhr.setRequestHeader(k, String(v)) }
    // Yükleme dinleyicileri send()'den ÖNCE bağlanmalı (yoksa tarayıcı upload olaylarını hiç üretmez)
    if (xhr.upload) {
      xhr.upload.onprogress = (e) => report({ phase: 'upload', loaded: e.loaded, total: e.lengthComputable ? e.total : null })
      xhr.upload.onload = () => report({ phase: 'sent' })
    }
    xhr.onload = () => {
      cleanup()
      const status = xhr.status
      const text = xhr.responseText
      resolve({
        ok: status >= 200 && status < 300,
        status,
        headers: { get: (n) => { try { return xhr.getResponseHeader(n) } catch { return null } } },
        json: async () => JSON.parse(text),
      })
    }
    xhr.onerror = () => { cleanup(); reject(new TypeError('Network request failed')) }
    xhr.ontimeout = () => { cleanup(); reject(abortError()) }
    xhr.onabort = () => { cleanup(); reject(abortError()) }
    signal?.addEventListener?.('abort', onAbortSignal, { once: true })
    xhr.send(init.body ?? null)
  })
}

/**
 * Manuel sertifika yükleme uçları (analiz / oluştur / toplu / yeni sürüm; 2026-10-08 yükleme durumu). `opts.onProgress`
 * verilirse (sihirbaz) istek XMLHttpRequest ile gider — gerçek yükleme yüzdesi ({@link xhrTransport}); verilmezse fetch
 * (aynı sonuç). `opts.signal` iptal eder → `{ success: false, status: 0, code: 'CANCELLED', cancelled: true }`. Sonuç
 * biçimi iki yolda da `request()`'inki (`withStatus`: `{ success, status, code, error, errors, data }`; 401'de null).
 */
async function manualUpload(path, formData, { onProgress, signal } = {}) {
  const useXhr = typeof onProgress === 'function' && typeof XMLHttpRequest !== 'undefined'
  const res = await request(path, {
    method: 'POST', body: formData, withStatus: true,
    ...(useXhr ? { transport: xhrTransport({ onProgress, signal }) } : signal ? { signal } : {}),
  })
  if (signal?.aborted && res && res.success !== true) return { success: false, status: 0, code: 'CANCELLED', cancelled: true }
  return res
}

export const api = {
  /** Komut paleti (2026-09-12, #1): alan / izleme / takım — takım kapsamlı. */
  search: (q) => request(`/search?q=${encodeURIComponent(q)}`),
  // Hafif kullanıcı dizini (her authenticated kullanıcı) — UserDirectory bağlamı bununla beslenir.
  /** Kurum-geneli takım rehberi (oturum açmış herkes) — ad→id ve üye listesi (beyaz-listeli). */
  teams: {
    directory: () => request('/teams/directory'),
    members: (id) => request(`/teams/${id}/members`),
  },
  users: {
    directory: () => request('/users/directory'),
  },
  /** Çevrimiçi kullanıcı özeti (2026-10-02) — oturum açmış HERKES; yalnız sayılar (toplam + birincil takıma göre). */
  presence: {
    online: () => request('/presence/online'),
  },
  /** Kurum içi Durum Sayfası (2026-10-01) — oturum açmış HERKES; sunucu 30 sn paylaşır, `fresh=true` (Yenile) belleği atlar. */
  statusPage: {
    get: (fresh = false) => request(fresh === true ? '/status-page?fresh=1' : '/status-page'),
  },
  /** Takım veri kalitesi puanı (2026-10-10) — data_quality.view; kapsam sunucuda, 60 sn paylaşılan bellek (`fresh` Yenile). */
  dataQuality: {
    summary: (fresh = false) => request(fresh === true ? '/data-quality?fresh=1' : '/data-quality'),
    team: (key, fresh = false) => request(`/data-quality/teams/${encodeURIComponent(key)}${fresh === true ? '?fresh=1' : ''}`),
  },
  /**
   * Kripto envanteri / PQC hazırlık (2026-10-10) — Zayıf Algoritma sayfasının sekmesi; `weak_algo.read` + görüş kapsamı.
   * Sunucu 120 sn paylaşır, `fresh=true` (Yenile) belleği en fazla 5 sn'de bir atlar. `auditExport` dışa aktarım
   * sonrası denetim izi (CRYPTO_INVENTORY_EXPORT) — dosya istemcide üretilir.
   */
  cryptoInventory: {
    get: (fresh = false) => request(fresh === true ? '/crypto-inventory?fresh=1' : '/crypto-inventory'),
    auditExport: (body) => request('/crypto-inventory/export-audit', { method: 'POST', body: JSON.stringify(body) }),
  },
  /**
   * Aylık Yönetici Özeti (2026-10-10) — okuma: global yönetici + AUDIT (`executive_summary.view`); ayar/test/gönderim:
   * global yönetici. `withStatus`: 403 → "erişim yok" ekranı, 429 → test sınırı.
   */
  executiveSummary: {
    /** `team`: takım kimliği ya da `org`; boş = sunucunun varsayılan kapsamı (kurum ya da müdürün ilk takımı). */
    get: ({ month, team = null, live = false, fresh = false } = {}) => {
      const qs = new URLSearchParams()
      if (month) qs.set('month', month)
      if (team != null && team !== '') qs.set('team', String(team))
      if (live) qs.set('live', '1')
      if (fresh) qs.set('fresh', '1')
      const s = qs.toString()
      return request(`/executive-summary${s ? `?${s}` : ''}`, { withStatus: true })
    },
    // Takım özeti alıcıları (2026-10-10): global yönetici her takım, takım müdürü yönettiği takımlar.
    teams: () => request('/executive-summary/teams', { withStatus: true }),
    team: (teamId) => request(`/executive-summary/teams/${encodeURIComponent(teamId)}`, { withStatus: true }),
    saveTeam: (teamId, body) => request(`/executive-summary/teams/${encodeURIComponent(teamId)}`,
      { method: 'PUT', body: JSON.stringify(body), withStatus: true }),
    sendTeamTest: (teamId, month) => request(`/executive-summary/teams/${encodeURIComponent(teamId)}/send-test`,
      { method: 'POST', body: JSON.stringify({ month }), withStatus: true }),
    runTeamNow: (teamId, month) => request(`/executive-summary/teams/${encodeURIComponent(teamId)}/run`,
      { method: 'POST', body: JSON.stringify({ month }), withStatus: true }),
    getSettings: () => request('/executive-summary/settings', { withStatus: true }),
    saveSettings: (body) => request('/executive-summary/settings', { method: 'PUT', body: JSON.stringify(body), withStatus: true }),
    sendTest: (month) => request('/executive-summary/send-test', { method: 'POST', body: JSON.stringify({ month }), withStatus: true }),
    runNow: (month) => request('/executive-summary/run', { method: 'POST', body: JSON.stringify({ month }), withStatus: true }),
    /**
     * PDF'i indirir (e-posta ekinin aynısı). Düz `<a href>` yerine blob: üretim hatası (503) ya da yetki (403) tarayıcıyı
     * JSON sayfasına götürmesin, kullanıcıya söylenebilsin.
     */
    downloadPdf: async ({ month, team = null, live = false } = {}) => {
      const qs = new URLSearchParams()
      if (month) qs.set('month', month)
      if (team != null && team !== '') qs.set('team', String(team))
      if (live) qs.set('live', '1')
      try {
        const res = await fetch(`${BASE}/executive-summary/pdf?${qs.toString()}`, { credentials: 'include', headers: { 'X-Lang': uiLang() } })
        if (!res.ok) return { success: false, status: res.status }
        const blob = await res.blob()
        const disp = res.headers.get('Content-Disposition') || ''
        const match = /filename="?([^";]+)"?/.exec(disp)
        const objectUrl = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = objectUrl
        a.download = match ? match[1] : `site-monitor-yonetici-ozeti-${month || ''}.pdf`
        a.click()
        setTimeout(() => URL.revokeObjectURL(objectUrl), 0)
        return { success: true }
      } catch (e) {
        return { success: false, status: 0, error: e?.message || 'NETWORK' }
      }
    },
  },
  /** Sürüm & yayın yüzeyi — kimlikli HERKES (K9). Nav çipi popover'ı + Yardım → Yenilikler. */
  system: {
    getVersion: () => request('/system/version'),
    getReleases: (params = {}) => {
      const qs = new URLSearchParams()
      for (const [k, v] of Object.entries(params)) if (v != null && v !== '' && v !== false) qs.set(k, String(v))
      const s = qs.toString()
      return request(`/system/releases${s ? `?${s}` : ''}`)
    },
    getReleaseNotes: (since) => request(`/system/releases/notes${since ? `?since=${encodeURIComponent(since)}` : ''}`),
  },
  me: {
    /** "Sizin için — bugün" paneli (2026-09-12, #3) */
    today: (opts = {}) => request(opts.full ? '/me/today?full=true' : '/me/today'),
    /** Bildirim kutusu (2026-09-12, #2) */
    inbox: () => request('/me/inbox'),
    /** İzleme menüsü rozetleri (2026-09-30): görüş kapsamındaki açık alarmların izleme türü başına özeti.
     *  Sunucu sonucu kapsam başına ~15 sn paylaşır (2026-10-01); `fresh=true` (alarm eylemi sonrası) belleği atlar. */
    openAlerts: (fresh = false) => request(fresh === true ? '/me/open-alerts?fresh=1' : '/me/open-alerts'),
    /** Geçmiş (2026-09-20): çözülmüş alarmlar 30 gün, sayfalı. */
    inboxHistory: (page = 0, size = 25) => request(`/me/inbox?view=history&page=${page}&size=${size}`),
    // 2026-09-10: yol '/auth/me/push-opt-out' idi — AuthController '/api' tabanlı, uç '/api/me/push-opt-out'
    // → 404; sunucu onayı gelmediği için "Webhook push istemiyorum" kutusu HİÇ işaretlenmiyordu.
    setPushOptOut: (optOut) => request('/me/push-opt-out', { method: 'POST', body: JSON.stringify({ opt_out: optOut }) }),
    // 2026-10-01: kişisel push sessiz saati — gövde { start, end, days[], min_level }; start+end boş = kaldır.
    setPushQuietHours: (body) => request('/me/push-quiet-hours', { method: 'POST', body: JSON.stringify(body) }),
    // 2026-10-04: kişisel push tercihleri (seviye / aileler / dil), susturma, kendine test, push geçmişim — yalnız kendi
    // kaydı (kimlik oturumdan). withStatus: 400 (alan hatası, `field`) ve 429 (test sınırı) ağ hatasından ayrılsın.
    getPushPreferences: () => request('/me/push-preferences'),
    savePushPreferences: (body) => request('/me/push-preferences', { method: 'PUT', body: JSON.stringify(body), withStatus: true }),
    pushSnooze: (body) => request('/me/push-snooze', { method: 'POST', body: JSON.stringify(body), withStatus: true }),
    pushSelfTest: () => request('/me/push-test', { method: 'POST', withStatus: true }),
    getPushHistory: (params = {}) => {
      const qs = new URLSearchParams()
      Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') qs.append(k, v) })
      const s = qs.toString()
      return request(`/me/push-history${s ? `?${s}` : ''}`)
    },
    // 2026-10-02 (öneri 23): kişisel tercihler — yalnız oturumdaki kullanıcının belgesi. PUT kısmi: üst düzey anahtar
    // değiştirilir, `local` girdi bazında birleşir. withStatus: 4xx (doğrulama) ile ağ hatası ayrılsın (hooks/useUserPrefs).
    getPreferences: () => request('/me/preferences'),
    // keepalive: sayfa gizlenirken / kapanırken gönderilen son toplu yazım tarayıcı gezinmesiyle iptal olmasın.
    savePreferences: (patch, opts = {}) => request('/me/preferences', {
      method: 'PUT', body: JSON.stringify(patch), withStatus: true, ...(opts.keepalive ? { keepalive: true } : {}),
    }),
    changePassword: (currentPwd, newPwd) => request('/me/change-password', {
      method: 'POST',
      body: JSON.stringify({ current_password: currentPwd, new_password: newPwd }),
    }),
    // ── Cihaz Gecmisi / Oturum Guvenligi (self-scope) ────────────────────────
    // Hicbirinde KULLANICI parametresi YOKTUR — kimlik sunucuda oturumdan okunur.
    getMyDevices: () => request('/me/devices'),
    getMyDeviceLogins: (params = {}) => {
      const qs = new URLSearchParams()
      Object.entries(params).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') qs.append(k, v)
      })
      const s = qs.toString()
      return request(`/me/devices/logins${s ? `?${s}` : ''}`)
    },
    revokeRememberedDevice: (id) => request(`/me/devices/remembered/${id}`, { method: 'DELETE' }),
    logoutOtherDevices: () => request('/me/devices/logout-others', { method: 'POST' }),
    reportSuspiciousLogin: (auditId) => request('/me/devices/report-login', {
      method: 'POST',
      body: JSON.stringify({ auditId }),
    }),

    getMyAudit: (params = {}) => {
      const qs = new URLSearchParams()
      Object.entries(params).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') qs.append(k, v)
      })
      const s = qs.toString()
      return request(`/me/audit${s ? `?${s}` : ''}`)
    },
    getPermissions: () => request('/me/permissions'),
  },

  login: async (username, password, rememberMe = false, forceLogin = false) => {
    let r
    try {
      r = await fetchWithTimeout(`${BASE}/login`, {
        method: 'POST',
        credentials: 'include',
        // X-Lang (2026-10-08): giriş hata metni de arayüz dilinde gelsin — başlık yokken sunucu tarayıcının
        // Accept-Language'ına düşüyor, İngilizce arayüzde Türkçe "hatalı parola" çıkabiliyordu.
        headers: { 'Content-Type': 'application/json', 'X-Lang': uiLang() },
        body: JSON.stringify({
          username, password,
          remember_me: String(rememberMe),
          force_login: String(forceLogin),
        }),
      })
    } catch (e) {
      // Login isteği asılır/başarısız olursa buton sonsuz "bekliyor"da kalmasın
      const kind = e?.name === 'AbortError' ? 'timeout' : 'network'
      return {
        success: false,
        status: 0,
        networkError: kind === 'network',
        error: networkMessage({ lang: uiLang(), kind }),
      }
    }
    let body
    try { body = await r.json() } catch { body = nonJsonErrorPayload(r.status, r) }
    // Flag a successful login so the 401 handler in request() knows that any
    // subsequent 401 is a *lost* session (worth a hard reload to /?session=
    // expired), not the initial unauthenticated bootstrap.
    if (r.ok && body?.success && typeof window !== 'undefined') {
      try { sessionStorage.setItem('sm.session.active', '1') } catch {}
    }
    return body
  },

  logout: async () => {
    if (typeof window !== 'undefined') {
      try { sessionStorage.removeItem('sm.session.active') } catch {}
    }
    return request('/logout', { method: 'POST' })
  },

  getMe: () => request('/me', { timeoutMs: DEFAULT_TIMEOUT_MS }),

  // Branding (beyaz etiket) — PUBLIC, auth gerekmez (login sayfası açılışta çeker).
  // fresh=true: kaydet sonrası çağrı — 60 sn'lik Cache-Control'ü query ile bust'la (anında yansıma).
  getBranding: (fresh = false) => request('/branding' + (fresh ? `?_=${Date.now()}` : '')),

  // Login hero istatistikleri — PUBLIC (izlenen hedef adedi + 7g erişilebilirlik %).
  getPublicStats: () => request('/public-stats'),

  // Sistem Bakım Modu (2026-10-02) — giriş sayfasının bakım kartı. PUBLIC: yalnız durum, pencere saatleri, TR/EN mesaj ve
  // iletişim (kimlik/sayaç yok). Yanıt `no-store` — paylaşımlı önbellek bayat tutmaz.
  getSystemMaintenanceStatus: () => request('/public/system-maintenance', { timeoutMs: DEFAULT_TIMEOUT_MS }),

  // Giriş Yöntemleri (2026-10-02) — giriş sayfası hangi yöntemlerin açık olduğunu oturumsuz okur: { ldap, otp_push,
  // otp_email, push_ttl, email_ttl, resend_cooldown, push_requires_phone, email_requires_email (2026-10-03) }. Yalnız
  // yapılandırma (kişi bilgisi yok); yanıt `no-store`.
  getLoginMethods: () => request('/public/login-methods', { timeoutMs: DEFAULT_TIMEOUT_MS }),

  /**
   * Kodla giriş (push / e-posta tek kullanımlık kod, 2026-10-02). `request()` KULLANILMAZ: doğrulama hatası 401 döner
   * (OTP_INVALID / OTP_EXPIRED / OTP_LOCKED) ve request() 401 gövdesini yutar. Dönen nesne sunucu gövdesi + `status`
   * (HTTP durumu); ağ hatası `{ success:false, status:0, networkError:true }`. Başarılı doğrulama /api/login ile AYNI
   * gövdeyi döner ve oturum bayrağını kurar (sonraki 401'ler "oturum düştü" sayılsın).
   */
  loginOtp: {
    // 2026-10-03: `contact` = { phone } (push) / { email } (e-posta) — yalnız ayar o kanalda kişi bilgisi istiyorsa.
    // Eşleşmeme de AYNI 200'dür; boşsa 400 PHONE_REQUIRED / EMAIL_REQUIRED (+ field). Gövde hiçbir yerde loglanmaz.
    request: (username, channel, contact) => otpPost('/login/otp/request', { username, channel, ...(contact || {}) }),
    verify: async (challengeId, code, rememberMe = false, forceLogin = false) => {
      const body = await otpPost('/login/otp/verify', {
        challenge_id: challengeId, code, remember_me: !!rememberMe, forceLogin: !!forceLogin,
      })
      if (body?.success && typeof window !== 'undefined') {
        try { sessionStorage.setItem('sm.session.active', '1') } catch { /* sessionStorage yok */ }
      }
      return body
    },
  },

  /**
   * Ayarlar → Görünüm → Temalar (2026-10-05): açık temalar + varsayılan. Okuma Ayarlar'a giren herkese (kapsamlı müdür
   * `read_only: true`), kayıt YALNIZ global yönetici (403). Doğrulama hatası 400 + `field` (`enabled` / `default`).
   */
  themesAdmin: {
    get: () => request('/admin/themes'),
    save: (body) => request('/admin/themes', { method: 'PUT', body: JSON.stringify(body), withStatus: true }),
  },

  /** Ayarlar → Güvenlik → Giriş Yöntemleri — YALNIZ global yönetici (sunucu 403). Kayıt hatası 400 + `field`. */
  loginMethodsAdmin: {
    get: () => request('/admin/login-methods'),
    save: (settings) => request('/admin/login-methods', { method: 'PUT', body: JSON.stringify({ settings }), withStatus: true }),
    /** Push metni taslağını YALNIZ oturumdaki yöneticiye gönderir (2026-10-03): 400 + field, 429 dakikalık tavan. */
    pushTest: (body) => request('/admin/login-methods/push-test', { method: 'POST', body: JSON.stringify(body), withStatus: true }),
    /** Giriş istatistikleri (2026-10-03): days 1|7|30|90; fresh → 30 sn önbelleği atla (sunucu en sık 5 sn'de bir). */
    stats: (days, fresh = false) => request(`/admin/login-methods/stats?${historyQuery({ days, fresh: fresh ? 1 : null })}`),
    /** Kullanıcı bazlı giriş satırları: { days, q, channel, sort, page (1-tabanlı), size, fresh }. */
    statsUsers: (params = {}) => request(`/admin/login-methods/stats/users?${historyQuery(params)}`),
    /** Tek kullanıcının giriş istatistiği + son olaylar. */
    statsUser: (username, days) => request(`/admin/login-methods/stats/users/${encodeURIComponent(username)}?${historyQuery({ days })}`),
  },

  /**
   * Sistem Bakım Modu yönetimi (Ayarlar → Platform → Sistem Bakımı) — YALNIZ global yönetici (sunucu 403). Yazma uçları
   * doğrulama hatasında 400 + `field` döner (alanın altında gösterilir); `withStatus` 409'u (durum çakışması) ayırır.
   */
  systemMaintenance: {
    overview: () => request('/admin/system-maintenance'),
    history: (page = 1, size = 25) => request(`/admin/system-maintenance/history?page=${page}&size=${size}`),
    detail: (id) => request(`/admin/system-maintenance/${id}`),
    schedule: (body) => request('/admin/system-maintenance', { method: 'POST', body: JSON.stringify(body), withStatus: true }),
    startNow: (body) => request('/admin/system-maintenance/start-now', { method: 'POST', body: JSON.stringify(body), withStatus: true }),
    update: (id, body) => request(`/admin/system-maintenance/${id}`, { method: 'PUT', body: JSON.stringify(body), withStatus: true }),
    extend: (id, body) => request(`/admin/system-maintenance/${id}/extend`, { method: 'POST', body: JSON.stringify(body), withStatus: true }),
    endNow: (id) => request(`/admin/system-maintenance/${id}/end-now`, { method: 'POST', withStatus: true }),
    cancel: (id) => request(`/admin/system-maintenance/${id}/cancel`, { method: 'POST', withStatus: true }),
  },

  // Login "sorun bildir" — PUBLIC; sistem yöneticisi e-postasına iletilir (IP rate-limit'li).
  // Zengin sonuç döner: {success, status, reference?, error?, networkError?} — modal, sebebi +
  // "Detay gör" ile teknik hatayı (HTTP kodu/sunucu mesajı/ağ istisnası) gösterebilsin.
  sendLoginHelp: async (dto) => {
    try {
      const res = await fetch(`${BASE}/login-help`, {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-Lang': uiLang() },
        body: JSON.stringify(dto),
      })
      let data = null
      try { data = await res.json() } catch { /* gövde JSON değil (proxy HTML hata sayfası vb.) */ }
      return {
        success: !!(data && data.success),
        status: res.status,
        reference: data?.reference,
        error: data?.error,
      }
    } catch (e) {
      // Ağ hatası (sunucuya ulaşılamadı / yeniden başlatma / CORS) — status yok.
      return { success: false, status: 0, networkError: true, error: String(e?.message || e?.name || e) }
    }
  },

  // Oturum içi "Sorun Bildir" (USER_REPORT) — kayıt sorun-bildirimleri ekranına düşer + admin maili.
  // Kimlik sunucuda OTURUMDAN okunur; dto yalnız kullanıcının bilebileceklerini + otomatik bağlamı taşır.
  sendIssueReport: (dto) => request('/issue-reports', { method: 'POST', body: JSON.stringify(dto) }),

  // "Bildirimlerim" (2026-09-26): kullanıcının KENDİ sorun bildirimleri — kimlik sunucuda oturumdan;
  // başkasının kaydı 404. Yorum: çözülmüş rapora yazınca sunucu raporu yeniden açar (yanıt: reopened).
  issueReports: {
    mine: (params = {}) => {
      const qs = new URLSearchParams()
      if (params.status) qs.set('status', params.status)
      if (params.page != null) qs.set('page', params.page)
      if (params.size != null) qs.set('size', params.size)
      const q = qs.toString()
      return request(`/issue-reports/mine${q ? '?' + q : ''}`)
    },
    mineDetail: (id) => request(`/issue-reports/mine/${id}`),
    addComment: (id, body) => request(`/issue-reports/mine/${id}/comments`, { method: 'POST', body: JSON.stringify({ body }) }),
  },

  // Hafif oturum geçerlilik yoklaması — süpersede ise 401 → request() otomatik /?session=expired.
  // Sayfa kullanımı (System Health #1): görünür sekme anahtarı ping'e eklenir — yalnız `tab`, URL parametreleri değil
  sessionPing: (tab) => request(`/session/ping${tab ? '?tab=' + encodeURIComponent(tab) : ''}`, { timeoutMs: DEFAULT_TIMEOUT_MS }),

  getCertificates: () => request('/certificates'),
  /** İzlemesi durdurulmuş (pasif) sertifikalar — yalnız Pano kartları (2026-10-08); her satır `paused: true`. */
  getPausedCertificates: () => request('/certificates/paused'),

  // Paylaşılan sertifika ayrıntısı (2026-09-22): kart çipi → pencere
  getSharedCertificate: (domain) => request('/certificates/shared?domain=' + encodeURIComponent(domain)),
  getCertificatesPaginated: (params) => {
    const q = new URLSearchParams(params).toString()
    return request(`/certificates/list?${q}`)
  },
  /** Ürün turu durumu (2026-09-13): yalnız kendi kaydı; {status, version, last_step, seen_page, checklist, checklist_hidden, reset} */
  setTourState: (patch) => request('/me/tour', { method: 'POST', body: JSON.stringify(patch || {}) }),
  /** Tüm Sertifikalar CSV (2026-09-13): aynı süzgeç, tüm sayfalar; tarayıcı indirir, sunucu CERT_LIST_EXPORT yazar. */
  certExportUrl: (params, cols) => {
    const q = new URLSearchParams(Object.fromEntries(Object.entries({ ...params, cols: (cols || []).join(',') })
      .filter(([k, v]) => v !== '' && v != null && v !== false && k !== 'page' && k !== 'per_page'))).toString()
    return `${BASE}/certificates/export.csv?${q}`
  },

  getWarnings: () => request('/warnings'),

  getHistory: (domain) => request(`/history/${encodeURIComponent(domain)}`),

  getDomainAlerts: (domain) => request(`/history/${encodeURIComponent(domain)}/alerts`),

  // Canlı TLS kontrolü (OCSP/CRL/HSTS dahil) — sunucu tarafı süre sınırlı; istemci eskisi gibi beklemeyi kesmez.
  checkDomain: (domain) => request(`/check/${encodeURIComponent(domain)}`, { timeoutMs: 0 }),
  checkDomainPreview: (domain) => request(`/check-preview/${encodeURIComponent(domain)}`, { timeoutMs: 0 }),
  // Envanter formundaki "Test et": YAZILAN degerlerle canli el sikismasi, KAYIT YOK.
  // check-preview'dan farki portu/TLS modunu/proxy'yi envanterden degil GOVDEDEN almasi —
  // henuz kaydedilmemis bir kayitta formdaki 8443 ancak boyle test edilebiliyor.
  testCertificate: (body) => request('/certificates/test', { method: 'POST', body: JSON.stringify(body) }),
  // Sertifika sağlık kontrol listesi: KALICI son kontrolden anında gelir (ağ beklemez).
  getCertificateHealth: (domain) => request(`/certificates/${encodeURIComponent(domain)}/health`),
  // "Şimdi kontrol et" — canlı el sıkışması koşar, sonucu kalıcılaştırır, listeyi tazeler.
  refreshCertificateHealth: (domain) =>
    request(`/certificates/${encodeURIComponent(domain)}/health/refresh`, { method: 'POST' }),
  // "Planlı yenilemeydi" onayı: sabitlenen parmak izi için kalıcı onay yazar, satır yeşile döner.
  confirmCertificateRenewal: (domain) =>
    request(`/certificates/${encodeURIComponent(domain)}/health/confirm-renewal`, { method: 'POST' }),
  // TLS yapılandırma notu (2026-10-10): not + nedenler + TLS profili (kalıcı veriden, ağ beklemez).
  getTlsGrade: (domain) => request(`/certificates/${encodeURIComponent(domain)}/tls-grade`),
  // TLS profilini ŞİMDİ yeniden tarar (protokol sürümleri, OCSP zımbalama) — uç başına ≤ 30 sn; sunucu süre sınırlı.
  rescanTlsProfile: (domain) =>
    request(`/certificates/${encodeURIComponent(domain)}/tls-grade/rescan`, { method: 'POST', timeoutMs: 0 }),
  // Son not düşüşleri + profil kapsaması (kullanıcının izleme kapsamında).
  getTlsGradeDrops: (days = 30) => request(`/tls-grade/drops?days=${encodeURIComponent(days)}`),

  // Manuel (dosyadan yüklenen) sertifikalar (2026-10-06): ağ üzerinden erişilemeyen sertifika dosyadan yüklenir, süresi
  // ağdakilerle AYNI kurallarla izlenir. Yükleme uçları çok parçalı (FormData: yalnız `extracted` — tarayıcıda ayıklanan
  // AÇIK sertifikalar — + ek alanlar) — Content-Type'ı tarayıcı belirler, X-Lang yine gider. Özel anahtar ve şifre gövdede
  // YOKTUR (2026-10-08). withStatus: 400 alan hataları / 409 kodları / 429 sınırı ayırt edilsin. Yükleme uçlarının son
  // argümanı `{ onProgress, signal }` (isteğe bağlı): gerçek yükleme yüzdesi (XMLHttpRequest) + "Vazgeç" ({@link manualUpload}).
  manualCerts: {
    /** Dosyayı çözümler — kayıt YAZMAZ (dakikada 30 sınırı). */
    analyze: (formData, opts) => manualUpload('/manual-certs/analyze', formData, opts),
    /** Tek kayıt: yükleme alanları + ref + domain (takip adı) + inventory (JSON) + note. */
    create: (formData, opts) => manualUpload('/manual-certs', formData, opts),
    /** Toplu (truststore): yükleme alanları + items [{ref, domain}] (en çok 20) + ortak inventory — hep ya da hiç. */
    createBatch: (formData, opts) => manualUpload('/manual-certs/batch', formData, opts),
    /** Yeni sürüm: yükleme alanları + ref + note + confirm (daha eski bitişli sürüm için). */
    renew: (inventoryId, formData, opts) =>
      manualUpload(`/manual-certs/${encodeURIComponent(inventoryId)}/versions`, formData, opts),
    list: () => request('/manual-certs', { withStatus: true }),
    get: (inventoryId) => request(`/manual-certs/${encodeURIComponent(inventoryId)}`, { withStatus: true }),
    /** Şimdi yeniden değerlendir — ağsız; yalnız toparlanma (yeni alarm açmaz). */
    evaluate: (inventoryId) => request(`/manual-certs/${encodeURIComponent(inventoryId)}/evaluate`, { method: 'POST' }),
    /** ESKİ (güncel olmayan) bir sürümü kalıcı siler (2026-10-07) — güncel sürümde 409 CURRENT_VERSION. */
    deleteVersion: (inventoryId, versionId) =>
      request(`/manual-certs/${encodeURIComponent(inventoryId)}/versions/${encodeURIComponent(versionId)}`, { method: 'DELETE', withStatus: true }),
    /**
     * Sürümün sertifika HİYERARŞİSİ (2026-10-07) — tarayıcı gibi kök → ara → yaprak düğümleri (kök ilk), çevrim-dışı;
     * düğüm başına tek açık sertifika PEM'i. 404: kapsam dışı / başka kaydın sürümü; 422 CHAIN_UNREADABLE.
     */
    versionChain: (inventoryId, versionId) =>
      request(`/manual-certs/${encodeURIComponent(inventoryId)}/versions/${encodeURIComponent(versionId)}/chain`, { withStatus: true }),
    /** Sürümün PUBLIC zinciri (PEM) — indirme bağlantısı; özel anahtar hiçbir zaman saklanmaz. */
    pemUrl: (inventoryId, versionId) =>
      `${BASE}/manual-certs/${encodeURIComponent(inventoryId)}/versions/${encodeURIComponent(versionId)}/pem`,
  },

  getStats: () => request('/stats'),
  getExecutiveStats: () => request('/stats/executive'),   // yönetici özeti (2026-09-12, #20)
  getRecentChanges: (days = 7) => request(`/stats/changes?days=${days}`),   // "ne değişti" satırı (2026-09-12, #7)

  getTeamStats: () => request('/stats/teams'),

  runScheduler: () => request('/scheduler/run', { method: 'POST' }),

  getSchedulerStatus: () => request('/scheduler/status'),

  getRenewalAdvice: () => request('/renewal-advice'),
  // Vade takvimi (2026-09-12): tek gövde + planlanan yenileme
  getForecast: () => request('/forecast'),
  forecastPlan: (domain, date, note) => request(`/forecast/${encodeURIComponent(domain)}/plan`, { method: 'POST', body: JSON.stringify({ date, note }) }),
  forecastUnplan: (domain) => request(`/forecast/${encodeURIComponent(domain)}/plan`, { method: 'DELETE' }),

  // Birleşik aktivite akışı (Kayıtlar → Aktivite) — sayfalı/filtreli/takım-izole. Boş filtreler düşürülür.
  getActivity: (params = {}) => {
    const qs = new URLSearchParams()
    Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') qs.append(k, v) })
    const s = qs.toString()
    return request(`/activity${s ? `?${s}` : ''}`)
  },
  getActivityDetail: (id) => request(`/activity/${id}`),
  getActivitySummary: (params = {}) => {
    const qs = new URLSearchParams()
    Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') qs.append(k, v) })
    const s = qs.toString()
    return request(`/activity/summary${s ? `?${s}` : ''}`)
  },

  getSilentAlertDomains: () => request('/alerts/silent-domains'),

  getMailFailureDomains: () => request('/notifications/failure-domains'),
  // Genel Bakış kartı zenginleştirmeleri (2026-09-19): alan adı → {health, alerts, uptime, change, renewal, shared, maintenance, contacts}
  getCardExtras: () => request('/certificates/card-extras'),

  getNetworkStatus: () => request('/system/network-status'),

  getNetworkOutageHistory: (limit = 50) => request(`/system/network-outage-history?limit=${limit}`),

  // ── Guide links (Sertifika Değişim Rehberi) ──────────────────────────────

  guideLinks: {
    list:   () => request('/guide-links'),
    create: (data) => request('/guide-links', { method: 'POST', body: JSON.stringify(data) }),
    update: (id, data) => request(`/guide-links/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (id) => request(`/guide-links/${id}`, { method: 'DELETE' }),
  },

  // ── Haftalık Raporlar ────────────────────────────────────────────────────

  incidents: {
    list: (params = {}) => {
      const q = new URLSearchParams()
      for (const k of ['q', 'severity', 'category', 'status', 'service', 'channel', 'since', 'until', 'team_id', 'page', 'size']) {
        if (params[k] != null && params[k] !== '') q.set(k, params[k])
      }
      if (params.slaBreached != null) q.set('sla_breached', params.slaBreached)
      if (params.open != null) q.set('open', params.open)
      const qs = q.toString()
      return request(`/incidents${qs ? '?' + qs : ''}`)
    },
    get: (id) => request(`/incidents/${id}`),
    /** Bir alarmdan açılmış (takım kapsamında görünen) olay kayıtları — yalnız alarm DETAYI çağırır. */
    byAlert: (alertId) => request(`/incidents/by-alert/${encodeURIComponent(alertId)}`),
    options: (type) => request(`/incidents/options?type=${encodeURIComponent(type)}`),
    addOption: (type, value) =>
      request('/incidents/options', { method: 'POST', body: JSON.stringify({ type, value }) }),
    deleteOption: (type, value) =>
      request(`/incidents/options?type=${encodeURIComponent(type)}&value=${encodeURIComponent(value)}`, { method: 'DELETE' }),
    trends: (since, until) => {
      const q = new URLSearchParams()
      if (since) q.set('since', since)
      if (until) q.set('until', until)
      const qs = q.toString()
      return request(`/incidents/trends${qs ? '?' + qs : ''}`)
    },
    create: (payload) => request('/incidents', { method: 'POST', body: JSON.stringify(payload) }),
    update: (id, payload) => request(`/incidents/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
    previewNotification: (payload) => request('/incidents/preview-notification', { method: 'POST', body: JSON.stringify(payload) }),
    remove: (id) => request(`/incidents/${id}`, { method: 'DELETE' }),
    transfer: (ids, teamId, teamName) => request('/incidents/transfer', {
      method: 'POST', body: JSON.stringify({ ids, team_id: teamId, team_name: teamName }),
    }),
    uploadImage: (id, file, caption) => {
      const form = new FormData()
      form.append('file', file)
      if (caption) form.append('caption', caption)
      // id yoksa (create modu) taslak yükleme; kaydedince backend markdown'daki görseli olaya bağlar
      const path = id ? `/incidents/${id}/images` : '/incidents/images'
      return request(path, { method: 'POST', body: form })
    },
  },

  weeklyReports: {
    list: (params = {}) => {
      const q = new URLSearchParams()
      if (params.teamId) q.set('teamId', params.teamId)
      if (params.year) q.set('year', params.year)
      const qs = q.toString()
      return request(`/weekly-reports${qs ? '?' + qs : ''}`)
    },
    get: (id) => request(`/weekly-reports/${id}`),
    kpis: (id) => request(`/weekly-reports/${id}/kpis`),
    monitoringStats: (id) => request(`/weekly-reports/${id}/monitoring-stats`),
    years: (teamId) => request(`/weekly-reports/years${teamId ? '?teamId=' + teamId : ''}`),
    deadline: () => request('/weekly-reports/deadline'),   // son giriş günü/saati — canlı ayar (2026-09-12)
    completion: (year) => request(`/weekly-reports/completion${year ? '?year=' + year : ''}`),   // takım × hafta panosu (2026-09-12, #21)
    thisWeek: () => request('/weekly-reports/this-week'),                    // "bu hafta" şeridi (2026-09-13)
    suggestions: (id) => request(`/weekly-reports/${id}/suggestions`),       // sistemden öneriler (2026-09-13)
    previous: (id) => request(`/weekly-reports/${id}/previous`),             // önceki hafta (Δ + not paneli) (2026-09-13)
    mails: (id) => request(`/weekly-reports/${id}/mails`),
    comments: (id) => request(`/weekly-reports/${id}/comments`),             // yorum dizisi (2026-09-13, ikinci tur)
    addComment: (id, text) => request(`/weekly-reports/${id}/comments`, {
      method: 'POST', body: JSON.stringify({ text }),
    }),
    remindersStatus: () => request('/weekly-reports/reminders/status'),      // hatırlatma görünürlüğü (admin/AUDIT)
    accessTeams: () => request('/weekly-reports/access/teams'),              // modül görünürlüğü: takım listesi (2026-09-16)
    setAccess: (teamId, enabled) => request(`/weekly-reports/access/teams/${teamId}`, {
      method: 'PUT', body: JSON.stringify({ enabled }),
    }),
    create: (payload) => request('/weekly-reports', {
      method: 'POST', body: JSON.stringify(payload),
    }),
    save: (id, contentJson, version) => request(`/weekly-reports/${id}`, {
      method: 'PUT', body: JSON.stringify({ content_json: contentJson, version }),
    }),
    lock: (id, force = false) => request(`/weekly-reports/${id}/lock${force ? '?force=true' : ''}`, { method: 'POST' }),
    unlock: (id) => request(`/weekly-reports/${id}/unlock`, { method: 'POST' }),
    remove: (id) => request(`/weekly-reports/${id}`, { method: 'DELETE' }),
    submit: (id) => request(`/weekly-reports/${id}/submit`, { method: 'POST' }),
    triggerReminder: () => request('/weekly-reports/reminders/trigger', { method: 'POST' }),
    reopen: (id) => request(`/weekly-reports/${id}/reopen`, { method: 'POST' }),
    resend: (id) => request(`/weekly-reports/${id}/resend`, { method: 'POST' }),
    approve: (id) => request(`/weekly-reports/${id}/approve`, { method: 'POST' }),
    reject: (id, note) => request(`/weekly-reports/${id}/reject`, {
      method: 'POST', body: JSON.stringify({ note }),
    }),
    preview: (id) => request(`/weekly-reports/${id}/preview`),
    transfer: (ids, targetTeamId) => request('/weekly-reports/transfer', {
      method: 'POST', body: JSON.stringify({ ids, target_team_id: targetTeamId }),
    }),
    uploadImage: (id, file, caption) => {
      const form = new FormData()
      form.append('file', file)
      if (caption) form.append('caption', caption)
      return request(`/weekly-reports/${id}/images`, { method: 'POST', body: form })
    },
    deleteImage: (imageId) => request(`/weekly-reports/images/${imageId}`, { method: 'DELETE' }),
  },

  // ── Admin ────────────────────────────────────────────────────────────────

  /**
   * Takim Bildirim Gruplari (/api/notification-groups).
   *
   * admin blogunun DISINDA: uc admin-only degil -- takimin her uyesi kendi takiminin
   * alici listesini yonetir (K2). api.admin altina konsaydi cagri yerlerinde yanlis
   * bir yetki cagrisimi yaratirdi.
   */
  notificationGroups: {
    /** teamId verilirse yalniz o takim; includeInactive silinmisleri de getirir (rozet icin). */
    list: (teamId, includeInactive = false) => {
      const qs = new URLSearchParams()
      if (teamId != null && teamId !== '') qs.set('teamId', String(teamId))
      if (includeInactive) qs.set('includeInactive', 'true')
      const q = qs.toString()
      return request(`/notification-groups${q ? `?${q}` : ''}`)
    },
    create: (body) => request('/notification-groups', { method: 'POST', body: JSON.stringify(body) }),
    update: (id, body) => request(`/notification-groups/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
    remove: (id) => request(`/notification-groups/${id}`, { method: 'DELETE' }),
    makeDefault: (id) => request(`/notification-groups/${id}/make-default`, { method: 'POST' }),
    /** Grup nerede kullaniliyor — silme onayindan ONCE gosterilen ozet. */
    usage: (id) => request(`/notification-groups/${id}/usage`),
    /** Degisiklik gecmisi (kim/ne zaman/ne degisti) — SILINMIS gruplar dahil. Kaynak audit_log;
     *  denetim uclarindan ayri, cunku bu ekran notification.groups yetkisiyle acilir. */
    history: (groupId, { page = 0, size = 25 } = {}) => {   // sayfalı (2026-09-20)
      const qs = new URLSearchParams({ page: String(page), size: String(size) })
      if (groupId != null) qs.set('groupId', String(groupId))
      return request(`/notification-groups/history?${qs.toString()}`)
    },
    /** Tum referanslari baska gruba tasi; targetGroupId null => takim varsayilani. */
    reassign: (id, targetGroupId) => request(`/notification-groups/${id}/reassign`, {
      method: 'POST', body: JSON.stringify({ target_group_id: targetGroupId ?? null }),
    }),
  },

  /**
   * 7/24 İzleme Ekibi (NOC) — kimliği doğrulanmış HERKES (2026-09-27; `.migration/noc/CONTRACT.md`).
   * Kapsam görüş kapsamıyla sınırlı (viewTeamIds); yazma uçları izlemeyi düzenleyebilene / takım yöneticisine açık.
   * Yanıtlar snake_case, istek gövdeleri camelCase.
   */
  noc: {
    /** İzleme formu seçicisi — sunucu `{ groups: [{ id, name, is_default, active }], disabled_types }` döner
     *  (sözleşmedeki düz dizi DEĞİL; NocController.groupOptions). E-posta YOK. */
    groupOptions: () => request('/noc/groups/options'),
    /** { summary: { total, covered, not_covered, paused, by_type, active_groups, disabled_types }, items: [...] } */
    coverage: ({ teamId, type, summary } = {}) => {
      const qs = new URLSearchParams()
      if (teamId != null && teamId !== '') qs.set('team_id', String(teamId))
      if (type) qs.set('type', String(type))
      if (summary) qs.set('summary', '1')   // yalnız sayılar (Genel Bakış şeridi) — items gelmez
      const q = qs.toString()
      return request(`/noc/coverage${q ? `?${q}` : ''}`)
    },
    /** Tek izlemenin 7/24 bildirimi — güncel satırı döner. groupIds verilmezse gövdeye yazılmaz (sunucu korur). */
    setMonitor: (type, id, { enabled, groupIds } = {}) => request(`/noc/monitors/${encodeURIComponent(type)}/${encodeURIComponent(id)}`, {
      method: 'PUT', body: JSON.stringify({ enabled: !!enabled, ...(groupIds !== undefined ? { groupIds } : {}) }),
    }),
    /** Toplu: items = [{ type, id }] → { updated, skipped: [{ type, id, reason }] } */
    bulk: (items, enabled) => request('/noc/monitors/bulk', {
      method: 'POST', body: JSON.stringify({ items: (items || []).map(({ type, id }) => ({ type, id })), enabled: !!enabled }),
    }),
    /** Takım arama listesi (sıralı): [{ user_id, display_name, title, has_phone }] — telefon UI'ye DÖNMEZ. */
    getCallList: (teamId) => request(`/noc/teams/${encodeURIComponent(teamId)}/call-list`, { withStatus: true }),
    saveCallList: (teamId, userIds) => request(`/noc/teams/${encodeURIComponent(teamId)}/call-list`, {
      method: 'PUT', body: JSON.stringify({ userIds: userIds || [] }), withStatus: true,
    }),
    /** Arama listesi seçicisi: takım üyeleri [{ user_id, display_name, title, has_phone }]. */
    teamMembers: (teamId) => request(`/noc/teams/${encodeURIComponent(teamId)}/members`),
    /**
     * 7/24 KONSOLU (2026-10-04) — yalnız TÜM alarmları görebilene (7/24 operatörü / global görücü; diğerleri 403).
     * params: { window: 1h|24h|7d, team_id, level, type, noc: sent|not_sent, called: yes|no, state: open|resolved, q,
     * page (0 tabanlı), size, fresh } → { kpis, facets, items, total, page, size, window, generated_at, truncated, can_write }.
     */
    console: (params = {}) => {
      const qs = new URLSearchParams()
      Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '' && v !== false) qs.append(k, String(v)) })
      const s = qs.toString()
      return request(`/noc/console${s ? `?${s}` : ''}`)
    },
    /** "Ara" kartı: alarmın sahibi takımının arama listesi TELEFONLA + müdür + eskalasyon + talimat (yalnız arama kaydı girebilene). */
    callSheet: (alertId) => request(`/noc/console/alerts/${encodeURIComponent(alertId)}/call-sheet`),
  },

  /**
   * 7/24 ARAMA KAYDI (2026-09-27; CONTRACT.md "Arama kaydı") — uyarının üzerinden "kim, ne zaman arandı, sonuç, not".
   * Okuma: uyarıyı görebilen herkes. Yazma/seçici: `noc_calls.write` (global yönetici her zaman; kapsamlı müdür hayır).
   * Silme: kaydı giren 15 dk içinde ya da global yönetici. Yanıtlar snake_case, gövde camelCase; telefon HİÇ dönmez.
   */
  nocCalls: {
    /** [{ id, contacted_user_id, contacted_name, contacted_at, channel, outcome, note, created_by_name, created_at, can_delete, delete_until }] — en yeni önce */
    list: (alertId) => request(`/alerts/${encodeURIComponent(alertId)}/noc-calls`),
    /** body: { contactedUserId?, contactedName?, contactedAt?, channel?, outcome, note? } → oluşan satır */
    create: (alertId, body) => request(`/alerts/${encodeURIComponent(alertId)}/noc-calls`, {
      method: 'POST', body: JSON.stringify(body || {}),
    }),
    remove: (alertId, id) => request(`/alerts/${encodeURIComponent(alertId)}/noc-calls/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    /** Arama seçicisi: [{ user_id, display_name, title, has_phone, source: CALL_LIST|MANAGER|MEMBER, is_manager }] */
    contacts: (alertId) => request(`/alerts/${encodeURIComponent(alertId)}/noc-contacts`),
  },

  admin: {
    // 7/24 İzleme Ekibi (NOC) yönetimi — YALNIZ global yönetici yazar; kapsamlı müdür okur (e-postalar 403/maskeli).
    noc: {
      getConfig:   () => request('/admin/noc/config'),
      saveConfig:  (body) => request('/admin/noc/config', { method: 'PUT', body: JSON.stringify(body) }),
      listGroups:  () => request('/admin/noc/groups'),
      createGroup: (body) => request('/admin/noc/groups', { method: 'POST', body: JSON.stringify(body) }),
      updateGroup: (id, body) => request(`/admin/noc/groups/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(body) }),
      /** → { affected_monitors }: bu grubu seçen izlemeler varsayılan gruplara düşer. */
      deleteGroup: (id) => request(`/admin/noc/groups/${encodeURIComponent(id)}`, { method: 'DELETE' }),
      /** → { sent, failed: [...] } */
      testGroup:   (id) => request(`/admin/noc/groups/${encodeURIComponent(id)}/test`, { method: 'POST' }),
      /** 7/24 izleme ekibi takımları (2026-10-04) → { team_ids, teams, operator_count, updated_at, updated_by_name, max_teams } */
      getOperatorTeams: () => request('/admin/noc/operator-teams'),
      /** Kaydetmeden önizleme → { teams: [{id, name, active, member_count}], user_count, users: [...], truncated } */
      previewOperatorTeams: (ids) => request(`/admin/noc/operator-teams/preview?teamIds=${encodeURIComponent((ids || []).join(','))}`),
      /** Seçimi baştan yazar (yalnız global yönetici) → güncel ayar görüntüsü */
      saveOperatorTeams: (ids) => request('/admin/noc/operator-teams', { method: 'PUT', body: JSON.stringify({ teamIds: ids || [] }) }),
    },
    // Kişi-webhook (push) bildirim kanalı — yalnız admin
    userPush: {
      getSettings:  () => request('/admin/user-push/settings'),
      saveSettings: (data) => request('/admin/user-push/settings', { method: 'POST', body: JSON.stringify(data) }),
      saveScopes:   (rows) => request('/admin/user-push/scopes', { method: 'POST', body: JSON.stringify(rows) }),
      sendTest:     (data) => request('/admin/user-push/test', { method: 'POST', body: JSON.stringify(data) }),
      getDeliveries: (params) => request(`/admin/user-push/deliveries?${new URLSearchParams(params)}`),
      getStats:     () => request('/admin/user-push/stats'),
      explain:      (teamId, level) => request(`/admin/user-push/explain?teamId=${encodeURIComponent(teamId)}&level=${encodeURIComponent(level || 'HIGH')}`),
      exportUrl:    (params) => `/api/admin/user-push/deliveries/export?${new URLSearchParams(params)}`,
    },
    // Inventory
    // scope 'mine' (varsayılan) | 'all' (org geneli görünürlük, 2026-09-26): yanıt `scope` + `visible_to_all` taşır.
    // 2026-10-07: silme KALICI — çöp kutusu yok, `showDeleted` parametresi kalktı.
    getInventory: (scope = 'mine') =>
      request(`/admin/inventory${scope === 'all' ? '?scope=all' : ''}`),
    // Envanter zenginleştirme (2026-09-12): hijyen bandı + CSV içe aktarma (dry_run varsayılan true)
    getInventoryHygiene: () => request('/admin/inventory/hygiene'),
    importInventory: (rows, dryRun = true) =>
      request('/admin/inventory/import', { method: 'POST', body: JSON.stringify({ rows, dry_run: dryRun }) }),
    getInventoryByDomain: (domain) => request(`/admin/inventory/by-domain?domain=${encodeURIComponent(domain)}`),
    // Platform kataloğu (2026-09-22): aktifler her oturuma (form seçicisi); all=true + yazma Ayarlar yetkisi
    listPlatforms: (all = false) => request('/admin/platforms' + (all ? '?all=true' : '')),
    createPlatform: (body) => request('/admin/platforms', { method: 'POST', body: JSON.stringify(body) }),
    updatePlatform: (id, body) => request('/admin/platforms/' + id, { method: 'PUT', body: JSON.stringify(body) }),
    deletePlatform: (id) => request('/admin/platforms/' + id, { method: 'DELETE' }),
    // withStatus (2026-09-28): hata gövdesi HTTP durumunu taşır — form 409'u satır içi gösterir (DOMAIN_EXISTS ayrıca `code` ile)
    addInventory: (item) => request('/admin/inventory', { method: 'POST', body: JSON.stringify(item), withStatus: true }),
    updateInventory: (id, item) => request(`/admin/inventory/${id}`, { method: 'PUT', body: JSON.stringify(item), withStatus: true }),
    // KALICI silme (2026-10-07): kayıt + kontrol geçmişi + notlar + sürümler + türev Port/DNS izlemeleri gider; çöp kutusu,
    // geri yükleme ve "kalıcı sil" uçları kaldırıldı.
    deleteInventory: (id) => request(`/admin/inventory/${id}`, { method: 'DELETE' }),
    /** extra: yalniz set-contacts icin — GONDERILEN alanlar yazilir, otekilere dokunulmaz. */
    bulkInventory: (ids, action, extra) => request('/admin/inventory/bulk', {
      method: 'POST', body: JSON.stringify({ ids, action, ...(extra ?? {}) }),
    }),
    runDiagnostics: (domain, port = 443) => request('/admin/diagnostics', {
      method: 'POST', body: JSON.stringify({ domain, port }),
    }),
    runOpensslDiagnostics: (domain, port = 443) => request('/admin/diagnostics/openssl', {
      method: 'POST', body: JSON.stringify({ domain, port }),
    }),
    runNetworkDiagnostics: (domain, port = 443) => request('/admin/diagnostics/network', {
      method: 'POST', body: JSON.stringify({ domain, port }),
    }),
    runHstsDiagnostics: (domain, port = 443) => request('/admin/diagnostics/hsts', {
      method: 'POST', body: JSON.stringify({ domain, port }),
    }),
    // Alan adı (registrar) süre bitişi tanılaması — adım adım RDAP/WHOIS trace.
    runDomainExpiryDiagnostics: (domain) => request('/admin/diagnostics/domain-expiry', {
      method: 'POST', body: JSON.stringify({ domain }),
    }),
    // Proxy'nin sunduğu CA zincirini yakala → yapıştırılmaya hazır PEM (varsayılan host: data.iana.org).
    captureProxyCaChain: (host) => request('/admin/diagnostics/proxy-ca-chain', {
      method: 'POST', body: JSON.stringify(host ? { host } : {}),
    }),
    diagHistory: (domain) => request(`/admin/diagnostics/history?domain=${encodeURIComponent(domain)}`),
    diagHistoryDetail: (id) => request(`/admin/diagnostics/history/${id}`),
    clientIpDebug: () => request('/admin/client-ip-debug'),

    // Genel Ayarlar — küratörlü runtime config (admin-only Settings page)
    getGeneralSettings: () => request('/admin/general/settings'),
    saveGeneralSettings: (dto) => request('/admin/general/settings', { method: 'PUT', body: JSON.stringify(dto) }),

    // Branding (beyaz etiket) — login/uygulama kimliği + duyuru şeridi
    // Veri Saklama (retention) — politika matrisi, dry-run, elle temizlik, çalışma geçmişi.
    getRetentionOverview: (estimate = false) =>
      request(`/admin/retention/overview${estimate ? '?estimate=true' : ''}`),
    saveRetentionSettings: (values) =>
      request('/admin/retention/settings', { method: 'PUT', body: JSON.stringify({ values }) }),
    retentionDryRun: () => request('/admin/retention/dry-run', { method: 'POST' }),
    retentionRunNow: () => request('/admin/retention/run', { method: 'POST' }),
    /** Koşum listesi — sayı verilirse eski `limit` biçimi; nesne verilirse sunucu-taraflı süzgeç/sayfa param'ları. */
    getRetentionRuns: (params = 10) => {
      if (typeof params === 'number') return request(`/admin/retention/runs?limit=${params}`)
      const qs = new URLSearchParams()
      for (const [k, v] of Object.entries(params)) if (v != null && v !== '' && v !== false) qs.set(k, String(v))
      const s = qs.toString()
      return request(`/admin/retention/runs${s ? `?${s}` : ''}`)
    },
    getRetentionRunsCsvUrl: (params = {}) => {
      const qs = new URLSearchParams()
      for (const [k, v] of Object.entries(params)) if (v != null && v !== '' && v !== false) qs.set(k, String(v))
      const s = qs.toString()
      return `/api/admin/retention/runs/export${s ? `?${s}` : ''}`
    },
    // ── Sürüm & Dağıtım geçmişi (release_history.read / .edit) ── (aynı nesnede iki kez tanımlıydı; birebir kopya 2026-09-26'da silindi)
    getDeployments: (params = {}) => {
      const qs = new URLSearchParams()
      for (const [k, v] of Object.entries(params)) if (v != null && v !== '' && v !== false) qs.set(k, String(v))
      const s = qs.toString()
      return request(`/admin/deployments${s ? `?${s}` : ''}`)
    },
    getDeploymentsCsvUrl: (params = {}) => {
      const qs = new URLSearchParams()
      for (const [k, v] of Object.entries(params)) if (v != null && v !== '' && v !== false) qs.set(k, String(v))
      const s = qs.toString()
      return `/api/admin/deployments/export${s ? `?${s}` : ''}`
    },
    getDeploymentTimeline: (env) => request(`/admin/deployments/timeline${env ? `?env=${encodeURIComponent(env)}` : ''}`),
    getDeploymentMatrix: (all = false) => request(`/admin/deployments/matrix${all ? '?all=true' : ''}`),
    createDeployment: (body) => request('/admin/deployments', { method: 'POST', body: JSON.stringify(body) }),
    backfillDeployments: (environment) => request('/admin/deployments/backfill', { method: 'POST', body: JSON.stringify({ environment }) }),
    deleteDeployment: (id) => request(`/admin/deployments/${id}`, { method: 'DELETE' }),
    /** Saklama süresi değişiklik geçmişi (kim/ne zaman/eski→yeni) — audit_log kaynaklı. */
    getRetentionChanges: (limit = 25, policyId) =>
      request(`/admin/retention/changes?limit=${limit}${policyId ? `&policyId=${encodeURIComponent(policyId)}` : ''}`),
    // Saatlik özeti geriye doldur — ham seri kısaltılmadan ÖNCE çalıştırılır (yalnız yazar, silmez).
    retentionBackfillHourly: (days = 0) =>
      request(`/admin/retention/backfill-hourly${days > 0 ? `?days=${days}` : ''}`, { method: 'POST' }),
    saveRetentionApproval: (policyId, note) =>
      request('/admin/retention/approval', { method: 'PUT', body: JSON.stringify({ policy_id: policyId, note }) }),

    // Cihaz Gecmisi — ADMIN salt-okunur gorunumu (K8). Eylem ucu YOKTUR ve olmamalidir:
    // iptal/cikis yalniz kullanicinin KENDI self-scope ucundadir (sunucu tarafinda da oyle).
    getUserDevices: (userId) => request(`/admin/users/${userId}/devices`),
    getUserDeviceLogins: (userId, params = {}) => {
      const qs = new URLSearchParams()
      Object.entries(params).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') qs.append(k, v)
      })
      const s = qs.toString()
      return request(`/admin/users/${userId}/devices/logins${s ? `?${s}` : ''}`)
    },

    getBrandingSettings: () => request('/admin/branding/settings'),
    saveBrandingSettings: (dto) => request('/admin/branding/settings', { method: 'PUT', body: JSON.stringify(dto) }),

    // Login Sorun Bildirimleri — admin triyaj (listele/detay/durum)
    getLoginIssues: (params = {}) => {
      const qs = new URLSearchParams()
      if (params.status) qs.set('status', params.status)
      if (params.source) qs.set('source', params.source)
      if (params.category) qs.set('category', params.category)
      if (params.impact) qs.set('impact', params.impact)   // "Ne yaşıyorsunuz?" etki süzgeci (2026-09-28)
      if (params.q) qs.set('q', params.q)
      if (params.since) qs.set('since', params.since)
      if (params.until) qs.set('until', params.until)
      if (params.page != null) qs.set('page', params.page)
      if (params.size != null) qs.set('size', params.size)
      const q = qs.toString()
      return request(`/admin/login-issues${q ? '?' + q : ''}`)
    },
    getLoginIssue: (id) => request(`/admin/login-issues/${id}`),
    updateLoginIssueStatus: (id, dto) =>
      request(`/admin/login-issues/${id}/status`, { method: 'PUT', body: JSON.stringify(dto) }),
    // KALICI silme: kayit + gorselleri + giden maillerin saklanan kopyalari. Ayri ve
    // "hassas" bir yetki ister (issues.login-reports.purge).
    purgeLoginIssue: (id) => request(`/admin/login-issues/${id}`, { method: 'DELETE' }),
    // Konuşma dizisi (2026-09-26): iç notlar dâhil; internal=true → bildiren görmez, bildirim/mail yok.
    getLoginIssueComments: (id) => request(`/admin/login-issues/${id}/comments`),
    addLoginIssueComment: (id, dto) =>
      request(`/admin/login-issues/${id}/comments`, { method: 'POST', body: JSON.stringify(dto) }),


    // Anahtar çözümleme aracı — verilen SITE_MONITOR_SECRET_KEY ile şifreli alanları çöz
    secretToolsInfo: () => request('/admin/secret-tools/info'),
    decryptSecrets: (key) => request('/admin/secret-tools/decrypt', { method: 'POST', body: JSON.stringify({ key }) }),

    // Veritabanı bilgileri (admin-only Settings sayfası) — bağlı PostgreSQL meta verisi
    getDatabaseInfo: () => request('/admin/database/info'),

    // LDAP / Active Directory settings (admin-only Settings page)
    getLdapSettings: () => request('/admin/ldap/settings'),
    saveLdapSettings: (dto) => request('/admin/ldap/settings', { method: 'PUT', body: JSON.stringify(dto) }),
    // verify=true: kayıtlı "doğrulamayı atla" ayarı DEĞİŞMEDEN sertifika doğrulaması açık denenir
    testLdap: (verify = false) => request(`/admin/ldap/test${verify ? '?verify=true' : ''}`, { method: 'POST' }),
    queryLdapUser: (value, attr) => request('/admin/ldap/query-user', {
      method: 'POST', body: JSON.stringify({ value, attr }),
    }),

    // SMTP / outbound mail settings (admin-only Settings page)
    getSmtpSettings: () => request('/admin/smtp/settings'),
    saveSmtpSettings: (dto) => request('/admin/smtp/settings', { method: 'PUT', body: JSON.stringify(dto) }),
    testSmtp: () => request('/admin/smtp/test', { method: 'POST' }),
    sendSmtpTest: (recipient) => request('/admin/smtp/test-email', {
      method: 'POST', body: JSON.stringify({ recipient }),
    }),

    // Başarısız-login anomali uyarısı (Ayarlar → Login Anomali sayfası)
    getLoginAnomalySettings: () => request('/admin/login-anomaly/settings'),
    saveLoginAnomalySettings: (dto) => request('/admin/login-anomaly/settings', {
      method: 'PUT', body: JSON.stringify(dto),
    }),
    testLoginAnomalyEmail: (recipient) => request('/admin/login-anomaly/test-email', {
      method: 'POST', body: JSON.stringify({ recipient }),
    }),
    getLoginAnomalyIncidents: (page = 0, size = 20) =>
      request(`/admin/login-anomaly/incidents?page=${page}&size=${size}`),

    // Aylık sertifika envanteri raporu (Ayarlar → Envanter Raporu) — ayın son cuması 10:00
    getCertInvReportStatus:  () => request('/admin/system/cert-inventory-report/status'),
    getCertInvReportPreview: () => request('/admin/system/cert-inventory-report/preview'),
    runCertInvReport:        () => request('/admin/system/cert-inventory-report/run', { method: 'POST' }),
    sendCertInvReportTest:   (email) => request('/admin/system/cert-inventory-report/send-test', {
      method: 'POST', body: JSON.stringify({ email }),
    }),
    getCertInvReportHistory: (limit = 24) => request(`/admin/system/cert-inventory-report/history?limit=${limit}`),
    saveCertInvReportSettings: (values) => request('/admin/system/cert-inventory-report/settings', {
      method: 'PUT', body: JSON.stringify(values),
    }),

    // Haftalık erişilebilirlik e-postası (Ayarlar → Haftalık E-posta sayfası)
    getWeeklyAvailStatus: () => request('/admin/system/weekly-availability/status'),
    getWeeklyAvailPreview: (teamId, weekOffset) =>
      request(`/admin/system/weekly-availability/preview?teamId=${encodeURIComponent(teamId)}`
        + (weekOffset != null ? `&weekOffset=${encodeURIComponent(weekOffset)}` : '')),
    /**
     * Haftalık kesinti PDF'ini indirir — mail ekinin BİREBİR aynısı.
     *
     * <p>Düz `<a href>` yerine blob: uç PDF üretilemediğinde 503 döner ve bağlantı olsaydı
     * tarayıcı boş/bozuk bir sayfaya giderdi. Burada hata yakalanıp kullanıcıya söylenebiliyor.
     * Dosya küçük (tipik 30 KB – birkaç MB), belleğe almak sorun değil.
     *
     * @returns {Promise<{success: boolean, error?: string}>}
     */
    downloadWeeklyOutagePdf: async (teamId, weekOffset) => {
      const url = `${BASE}/admin/system/weekly-availability/outage-pdf`
        + `?teamId=${encodeURIComponent(teamId)}`
        + (weekOffset != null ? `&weekOffset=${encodeURIComponent(weekOffset)}` : '')
      try {
        const res = await fetch(url, { credentials: 'include' })
        if (!res.ok) {
          return { success: false, status: res.status,
            error: res.status === 503 ? 'PDF_GENERATION_FAILED' : `HTTP_${res.status}` }
        }
        const blob = await res.blob()
        // Dosya adını sunucunun Content-Disposition'ından al — mailde giden adla aynı olsun.
        const disp = res.headers.get('Content-Disposition') || ''
        const match = /filename="?([^";]+)"?/.exec(disp)
        const objectUrl = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = objectUrl
        a.download = match ? match[1] : 'haftalik-kesinti-raporu.pdf'
        a.click()
        // Serbest bırakmayı ERTELE: click() indirmeyi eşzamanlı başlatmıyor ve URL hemen
        // geçersiz kılınırsa bazı tarayıcılar dosyayı boş indiriyor ya da hiç indirmiyor.
        setTimeout(() => URL.revokeObjectURL(objectUrl), 0)
        return { success: true }
      } catch (e) {
        return { success: false, error: e?.message || 'NETWORK' }
      }
    },
    sendWeeklyAvailTest: (teamId, email) => request('/admin/system/weekly-availability/send-test', {
      method: 'POST', body: JSON.stringify({ teamId, email }),
    }),
    setWeeklyAvailEnabled: (enabled) => request('/admin/system/weekly-availability/enabled', {
      method: 'PUT', body: JSON.stringify({ enabled }),
    }),
    getWeeklyAvailHistory: (limit = 50, includeTest = false) =>
      request(`/admin/system/weekly-availability/history?limit=${limit}&includeTest=${includeTest}`),
    getWeeklyAvailHistoryItem: (id) =>
      request(`/admin/system/weekly-availability/history/${encodeURIComponent(id)}`),
    // 2026-10-07: silme KALICI — çöp kutusu yok, "geri yükle + aktar" (`restore`) seçeneği kalktı.
    transferCertSy: (id, teamId) => request(`/admin/inventory/${id}/transfer`, {
      method: 'POST', body: JSON.stringify({ team_id: teamId }),
    }),
    transferCertUg: (id, ugTeamId) => request(`/admin/inventory/${id}/transfer-ug`, {
      method: 'POST', body: JSON.stringify({ ug_team_id: ugTeamId }),
    }),

    // Thresholds
    getThresholds: () => request('/admin/thresholds'),
    updateThreshold: (id, data) => request(`/admin/thresholds/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    // "Kim bilgilendirilir?" simülatörü + eskalasyon webhook testi / son teslimat (2026-09-20)
    simulateRecipients: ({ teamId, level = 'HIGH', kind = 'CERT', groupId = null, ugTeamId = null }) => {
      const qs = new URLSearchParams({ teamId: String(teamId), level, kind })
      if (groupId != null) qs.set('groupId', String(groupId))
      // Sertifika senaryosunda envanterin UG takımı (2026-09-28): UG adresi + UG'nin KENDİ eskalasyon kişileri.
      if (ugTeamId != null) qs.set('ugTeamId', String(ugTeamId))
      return request(`/admin/recipients/simulate?${qs.toString()}`)
    },
    testContactWebhook: (id) => request(`/admin/contacts/${id}/webhook-test`, { method: 'POST' }),
    contactWebhookStatus: () => request('/admin/contacts/webhook-status'),
    // Yönetim Paneli değişiklik geçmişi (2026-09-20): resource = ALERT_THRESHOLD | ESCALATION_CONTACT | TEAM | USER
    history: (resource, resourceId = null, { page = 0, size = 25, types = [] } = {}) => {
      const qs = new URLSearchParams({ resource, page: String(page), size: String(size) })
      if (resourceId != null) qs.set('resourceId', String(resourceId))
      for (const ty of types || []) qs.append('types', ty)
      return request(`/admin/history?${qs.toString()}`)
    },
    // Tier bazlı eşikler + etki önizleme (2026-09-20)
    createThreshold: (data) => request('/admin/thresholds', { method: 'POST', body: JSON.stringify(data) }),
    deleteThreshold: (id) => request(`/admin/thresholds/${id}`, { method: 'DELETE' }),
    previewThreshold: ({ tier, warning, high, critical }) => {
      const p = new URLSearchParams({ warning: String(warning), high: String(high), critical: String(critical) })
      if (tier != null) p.set('tier', String(tier))
      return request(`/admin/thresholds/preview?${p.toString()}`)
    },

    // Contacts
    getContacts: () => request('/admin/contacts/all'),
    addContact: (contact) => request('/admin/contacts', { method: 'POST', body: JSON.stringify(contact) }),
    updateContact: (id, contact) => request(`/admin/contacts/${id}`, { method: 'PUT', body: JSON.stringify(contact) }),
    deleteContact: (id) => request(`/admin/contacts/${id}`, { method: 'DELETE' }),

    // Alert Events
    /**
     * Alarm Geçmişi CSV bağlantısı — indirme <a href> ile yapılır, fetch ile DEĞİL.
     *
     * <p>Sunucu dosyayı Content-Disposition ile akıtıyor; tarayıcının indirme akışını kullanmak
     * hem büyük dosyayı belleğe almamayı hem de oturum çerezinin kendiliğinden gitmesini sağlar
     * (Kontrol Geçmişi CSV'siyle aynı desen).
     */
    getAlertsCsvUrl: (params = {}) => {
      const qs = new URLSearchParams()
      Object.entries(params).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') qs.append(k, v)
      })
      const s = qs.toString()
      return `${BASE}/admin/alerts/export${s ? `?${s}` : ''}`
    },

    getAlerts: (params = {}) => {
      const opts = typeof params === 'object' && params !== null ? params : { onlyOpen: params }
      const qs = new URLSearchParams()
      Object.entries(opts).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') qs.append(k, v)
      })
      const s = qs.toString()
      return request(`/admin/alerts${s ? `?${s}` : ''}`)
    },
    // note = zorunlu gerekçe (en az 3 kelime). Sunucu da doğruluyor; geçersizse 400 döner.
    acknowledgeAlert: (id, note) => request(`/admin/alerts/${id}/acknowledge`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    }),
    resolveAlert: (id, note) => request(`/admin/alerts/${id}/resolve`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    }),
    reNotifyAlert: (id, body) => request(`/admin/alerts/${id}/re-notify`, {
      method: 'POST', ...(body ? { body: JSON.stringify(body) } : {}),
    }),
    // "Tekrar Bildir" onay pop-up'ı: gönderim yapmadan alıcı listesini döner
    previewReNotify: (id) => request(`/admin/alerts/${id}/re-notify/preview`),
    // Toplu işlem: action ∈ {acknowledge, resolve, re-notify}, ids = alarm id listesi
    // note yalnız acknowledge/resolve için gerekli; re-notify'da null geçilir (sunucu da aramaz).
    bulkAlertAction: (action, ids, note) => request('/admin/alerts/bulk', {
      method: 'POST', body: JSON.stringify({ action, ids, ...(note ? { note } : {}) }),
    }),
    getAlertNotifications: (id) => request(`/admin/alerts/${id}/notifications`),
    getAlertNoise: (days = 7, teamId) => request(`/admin/alerts/noise?days=${days}${teamId ? `&team=${encodeURIComponent(teamId)}` : ''}`),
    // Isı haritası hücresi (gün × saat, İstanbul) ayrıntısı — 2026-10-01
    getAlertNoiseSlot: (days, dow, hour, teamId) => request(`/admin/alerts/noise/slot?days=${encodeURIComponent(days)}&dow=${encodeURIComponent(dow)}&hour=${encodeURIComponent(hour)}${teamId ? `&team=${encodeURIComponent(teamId)}` : ''}`),   // gürültü analizi (2026-09-12, #18); takım süzgeci (2026-10-01)
    getAlertTeamStats: () => request('/admin/alerts/team-stats'),               // takım kırılımı (2026-09-16)
    getAlertPushDeliveries: (id) => request(`/admin/alerts/${id}/push-deliveries`),
    /** Alarmı KAPSAYAN toplu fırtına push'ları + alıcı durumları + "henüz gitmedi" (2026-10-04, fırtına push'u ↔ alarm bağı). */
    getAlertStormPush: (id) => request(`/admin/alerts/${encodeURIComponent(id)}/storm-push`),
    /** Tekil uyarı (listeyle aynı zenginleştirme + 7/24 arama özeti + noc_can_write) — derin bağlantı yedeği (2026-09-27). */
    getAlert: (id) => request(`/admin/alerts/${encodeURIComponent(id)}`),

    // Teams
    getTeams: () => request('/admin/teams'),
    /** Ürün turunu sıfırla (2026-09-13): kullanıcı bir sonraki girişte karşılama kartını yeniden görür. */
    resetUserTour: (id) => request(`/admin/users/${id}/tour-reset`, { method: 'POST' }),
    createTeam: (data) => request('/admin/teams', { method: 'POST', body: JSON.stringify(data) }),
    updateTeam: (id, data) => request(`/admin/teams/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteTeam: (id) => request(`/admin/teams/${id}`, { method: 'DELETE' }),
    // Haftalık e-posta anahtarları — takım ÜYELERİNE açık dar uç (ad/e-posta/aktifliğe dokunmaz).
    updateTeamWeeklyNotifications: (id, data) =>
      request(`/admin/teams/${id}/weekly-notifications`, { method: 'PUT', body: JSON.stringify(data) }),
    getTeamUsers: (id) => request(`/admin/teams/${id}/users`),
    // Yönetim Paneli özet şeridi (2026-09-20)
    overview: () => request('/admin/overview'),
    // Toplu kullanıcı işlemi (2026-09-20): action = activate | deactivate | assign_team | set_org_role
    bulkUsers: (body) => request('/admin/users/bulk', { method: 'POST', body: JSON.stringify(body) }),
    // Sistem geneli toplu pasife alma (2026-10-02, yalnız global yönetici): önizleme → uygula (409 BULK_LIST_CHANGED) → geri al
    bulkDeactivatePreview: (criteria) => request('/admin/users/bulk-deactivate/preview', { method: 'POST', body: JSON.stringify({ criteria }) }),
    bulkDeactivate: (body) => request('/admin/users/bulk-deactivate', { method: 'POST', body: JSON.stringify(body) }),
    bulkDeactivateUndo: (opId) => request(`/admin/users/bulk-deactivate/${opId}/undo`, { method: 'POST' }),
    bulkOperations: () => request('/admin/users/bulk-operations'),
    // Takım sayaçları / etki / taşıma / üyelik (2026-09-20)
    teamStats: () => request('/admin/teams/stats'),
    bulkTeams: (body) => request('/admin/teams/bulk', { method: 'POST', body: JSON.stringify(body) }),   // toplu takım işlemi (2026-09-20)
    teamImpact: (id) => request(`/admin/teams/${id}/impact`),
    teamMove: (id, targetTeamId) => request(`/admin/teams/${id}/move`, { method: 'POST', body: JSON.stringify({ target_team_id: targetTeamId }) }),
    addTeamMember: (id, userId) => request(`/admin/teams/${id}/members`, { method: 'POST', body: JSON.stringify({ user_id: userId }) }),
    removeTeamMember: (id, userId) => request(`/admin/teams/${id}/members/${userId}`, { method: 'DELETE' }),
    // Üyelik kaynağı + AD ile karşılaştır / yeniden eşitle (2026-09-26, prod hatası: üye olmayan kullanıcı takımda)
    userTeamMembership: (id) => request(`/admin/users/${id}/team-membership`),
    userLdapCheck: (id) => request(`/admin/users/${id}/ldap-check`),
    userLdapResync: (id) => request(`/admin/users/${id}/ldap-resync`, { method: 'POST' }),
    teamLdapCheck: (id) => request(`/admin/teams/${id}/ldap-check`),
    teamLdapResync: (id) => request(`/admin/teams/${id}/ldap-resync`, { method: 'POST' }),

    // Users
    getUsers: () => request('/admin/users'),
    // Filtreli + sayfalı liste (Admin Users ekranı). Boş/null filtreler atlanır.
    searchUsers: (params) => {
      const q = new URLSearchParams(
        Object.fromEntries(
          Object.entries(params || {}).filter(([, v]) => v !== '' && v != null && v !== false),
        ),
      ).toString()
      return request(`/admin/users/search?${q}`)
    },
    createUser: (data) => request('/admin/users', { method: 'POST', body: JSON.stringify(data) }),
    updateUser: (id, data) => request(`/admin/users/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteUser: (id) => request(`/admin/users/${id}`, { method: 'DELETE' }),
    autoResetPassword: (id, adminPassword) => request(`/admin/users/${id}/auto-reset-password`, {
      method: 'POST', body: JSON.stringify({ admin_password: adminPassword }),
    }),
    unlockUser: (id) => request(`/admin/users/${id}/unlock`, { method: 'POST' }),
    unlockUserRole: (id) => request(`/admin/users/${id}/role-unlock`, { method: 'POST' }),
    unlockUserOrgRole: (id) => request(`/admin/users/${id}/org-role-unlock`, { method: 'POST' }),
    unlockUserTeams: (id) => request(`/admin/users/${id}/team-unlock`, { method: 'POST' }),   // takım kilidi (2026-09-18)
    // LDAP alan kilidi (2026-09-30): elle düzenlenen AD alanını AD yönetimine geri ver — yalnız global yönetici
    unlockUserField: (id, field) => request(`/admin/users/${id}/field-unlock`, { method: 'POST', body: JSON.stringify({ field }) }),
    // 2026-10-04: kişinin etkin push susturmasını kaldır (kullanıcı detayı → Bildirimler; PUSH_SNOOZE_CLEAR denetimi)
    clearUserPushSnooze: (id) => request(`/admin/users/${id}/push-snooze/clear`, { method: 'POST' }),

    // Cert transfer
    transferCert: (id, teamId) => request(`/admin/inventory/${id}/transfer`, {
      method: 'POST', body: JSON.stringify({ team_id: teamId }),
    }),

    // Certificate notes
    getNotes: (domain) => request(`/admin/notes/${encodeURIComponent(domain)}`),
    addNote: (domain, note, category = 'NOTE') => request(`/admin/notes/${encodeURIComponent(domain)}`, {
      method: 'POST', body: JSON.stringify({ note, category }),
    }),
    updateNote: (domain, noteId, note) => request(`/admin/notes/${encodeURIComponent(domain)}/${noteId}`, {
      method: 'PUT', body: JSON.stringify({ note }),
    }),
    deleteNote: (domain, noteId) => request(`/admin/notes/${encodeURIComponent(domain)}/${noteId}`, {
      method: 'DELETE',
    }),
    getNoteRevisions: (domain, noteId) =>
      request(`/admin/notes/${encodeURIComponent(domain)}/${noteId}/revisions`),
    restoreNote: (domain, noteId) =>
      request(`/admin/notes/${encodeURIComponent(domain)}/${noteId}/restore`, { method: 'POST' }),

    // Weak algorithm report
    // Yapılandırma sağlığı kartı (2026-09-12, #25)
    getConfigHealth: () => request('/admin/config-health'),
    getWeakAlgorithms: () => request('/admin/audit/weak-algorithms'),
    // 2026-09-12 zenginleştirme: CSV indirme <a href> ile (same-origin cookie), istisna ve takıma bildir uçları
    weakAlgorithmsExportUrl: () => `${BASE}/admin/audit/weak-algorithms/export`,
    setWeakAlgorithmException: (domain, body) =>
      request(`/admin/audit/weak-algorithms/${encodeURIComponent(domain)}/exception`, { method: 'POST', body: JSON.stringify(body) }),
    clearWeakAlgorithmException: (domain) =>
      request(`/admin/audit/weak-algorithms/${encodeURIComponent(domain)}/exception`, { method: 'DELETE' }),
    notifyWeakAlgorithm: (domain) =>
      request(`/admin/audit/weak-algorithms/${encodeURIComponent(domain)}/notify`, { method: 'POST' }),

    // Audit log
    getAuditLogs: (params) => {
      const q = new URLSearchParams(
        Object.fromEntries(
          Object.entries(params).filter(([, v]) => v !== '' && v != null && v !== false)
        )
      ).toString()
      return request(`/admin/audit?${q}`)
    },
    getAuditStats: () => request('/admin/audit/stats'),
    getAuditIntegrity: () => request('/admin/audit/integrity'),
    // Olay türü kataloğu (tür + kategori + son 90 günün sayısı). Filtre listesi buradan gelir;
    // elle tutulan liste 162 türün yalnız 32'sini tanıyacak kadar sürüklenmişti.
    getAuditEventTypes: () => request('/admin/audit/event-types'),
    getAuditEntry: (id) => request(`/admin/audit/${id}`),
    getAuditResourceHistory: (type, id, limit = 100) =>
      request(`/admin/audit/resource/${encodeURIComponent(type)}/${encodeURIComponent(id)}?limit=${limit}`),
    getAuditActorHistory: (actorId, limit = 100) => request(`/admin/audit/actor/${actorId}?limit=${limit}`),
    getAuditCorrelated: (cid) => request(`/admin/audit/correlation/${encodeURIComponent(cid)}`),
    // Filtreli dışa aktarma URL'i (tarayıcı indirir; işlem sunucuda AUDIT_EXPORT olarak denetlenir).
    auditExportUrl: (format, params) => {
      const q = new URLSearchParams(
        Object.fromEntries(
          Object.entries({ ...params, format }).filter(([, v]) => v !== '' && v != null && v !== false)
        )
      ).toString()
      return `${BASE}/admin/audit/export?${q}`
    },

    // Permission matrix
    getPermissionMatrix: () => request('/admin/permissions'),
    updatePermissionGrant: (body) => request('/admin/permissions', {
      method: 'PUT', body: JSON.stringify(body),
    }),
    resetPermissionsToDefaults: () => request('/admin/permissions/reset-to-defaults', {
      method: 'POST',
    }),

    // SQL Playground
    sqlListTables:  () => request('/admin/sql/tables'),
    sqlListColumns: (table) => request(`/admin/sql/tables/${encodeURIComponent(table)}/columns`),
    sqlTableDetails: (table) => request(`/admin/sql/tables/${encodeURIComponent(table)}/details`),
    sqlRelations:   () => request('/admin/sql/relationships'),
    sqlExecute:     (sql) => request('/admin/sql/execute', { method: 'POST', body: JSON.stringify({ sql }) }),
    sqlHistory:     () => request('/admin/sql/history'),
    sqlSamples:     () => request('/admin/sql/samples'),

    // System health
    getSystemHealth: () => request('/admin/system'),
    forceReleaseLock: () => request('/admin/system/scheduler-lock', { method: 'DELETE' }),
    getMetrics: () => request('/admin/system/metrics'),
    getHttpMetrics: () => request('/admin/system/http-metrics'),
    getHttpMetricsEndpoints: (from, to) =>
      request(`/admin/system/http-metrics/endpoints?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
    getHttpMetricsSeries: (from, to, endpoint, granularity) => {
      const q = new URLSearchParams({ from, to })
      if (endpoint) q.set('endpoint', endpoint)
      if (granularity) q.set('granularity', granularity)
      return request(`/admin/system/http-metrics/series?${q.toString()}`)
    },
    // İstek Gezgini (2026-09-28): tek çağrıda uç tablosu + süzülmüş seri/özet/durum kodları.
    // { from, to, endpoint?, methods?: string[], granularity? } — boşlar atılır.
    getHttpMetricsOverview: ({ from, to, endpoint, methods, granularity } = {}) => {
      const q = new URLSearchParams({ from, to })
      if (endpoint) q.set('endpoint', endpoint)
      if (Array.isArray(methods) && methods.length) q.set('method', methods.join(','))
      if (granularity) q.set('granularity', granularity)
      return request(`/admin/system/http-metrics/overview?${q.toString()}`)
    },
    getDbStats: () => request('/admin/system/db-stats'),
    getDbAnalytics: (days = 7) => request(`/admin/system/db-analytics?days=${days}`),
    getSmtpLogs: (days) =>
      request(`/admin/system/smtp-logs${days ? `?days=${days}` : ''}`),
    // SMTP Gönderim Logu v2 (2026-09-19): sunucu taraflı arama/özet/dışa aktarma/detay/yeniden gönderim.
    // params: { from, to, status, trigger, teamId, domain, recipient, errorClass, q, sort, page, size } — boşlar atılır.
    smtpLog: {
      _qs: (params = {}) => {
        const q = new URLSearchParams()
        Object.entries(params).forEach(([k, v]) => { if (v != null && v !== '') q.set(k, String(v)) })
        const s = q.toString()
        return s ? `?${s}` : ''
      },
      search: (params) => request(`/admin/smtp-log/search${api.admin.smtpLog._qs(params)}`),
      summary: (params) => request(`/admin/smtp-log/summary${api.admin.smtpLog._qs(params)}`),
      export: (params) => request(`/admin/smtp-log/export${api.admin.smtpLog._qs(params)}`),
      detail: (id) => request(`/admin/smtp-log/${id}`),
      resend: (id) => request(`/admin/smtp-log/${id}/resend`, { method: 'POST' }),
    },
    // Webhook Push Gönderim Logu (2026-09-19): SMTP'nin push karşılığı — aynı sözleşme (+ username/monitorType/level).
    pushLog: {
      search: (params) => request(`/admin/push-log/search${api.admin.smtpLog._qs(params)}`),
      summary: (params) => request(`/admin/push-log/summary${api.admin.smtpLog._qs(params)}`),
      export: (params) => request(`/admin/push-log/export${api.admin.smtpLog._qs(params)}`),
      detail: (id) => request(`/admin/push-log/${id}`),
      requeue: (id) => request(`/admin/push-log/${id}/requeue`, { method: 'POST' }),
    },
    triggerHeartbeat: () => request('/admin/system/heartbeat', { method: 'POST' }),
    getHeartbeatTimeline: (days = 1) => request(`/admin/system/heartbeat-timeline?days=${days}`),
    // Kullanıcı / oturum izleme
    getUserActivity: () => request('/admin/system/user-activity'),
    // Atıl hesaplar TAM listesi (≤ 5000; yoklanan özet ilk 500'ü taşır) — yalnız pencere açılınca / Yenile ile, yoklanmaz
    getDormantAccounts: () => request('/admin/system/user-activity/dormant'),
    // Esnek login serisi — aralık seçimi (1g/7g/30g), gün-navigasyonu, zoom
    getLoginSeries: (from, to, granularity = 'day') =>
      request(`/admin/system/user-activity/series?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&granularity=${granularity}`),
    terminateUserSession: (username, reason) => request('/admin/system/terminate-session', {
      method: 'POST', body: JSON.stringify(reason ? { username, reason } : { username }),
    }),
    // Kullanıcı etkinliği zenginleştirmesi (2026-09-13): kullanıcı zaman çizelgesi + anomali onayı
    getUserTimeline: (username, limit = 20) => request(`/admin/system/user-activity/user/${encodeURIComponent(username)}?limit=${limit}`),
    ackAnomaly: (auditId, acknowledge = true, note) => request(`/admin/system/user-activity/anomalies/${auditId}/ack`, {
      method: 'POST', body: JSON.stringify({ acknowledge, note: note || null }),
    }),
  },

  // ── Monitoring ───────────────────────────────────────────────────────────

  monitoring: {
    /** İzleme Panosu (2026-09-30): 9 türün tek ekranda özeti + izleme satırları; pencere saat (24 | 168).
     *  `fresh` (2026-10-01, sayfanın Yenile düğmesi): sunucunun 30 sn'lik belleğini atlar (sunucu en fazla 5 sn'de bir izin verir). */
    getOverview: (hours = 24, fresh = false) =>
      request(`/monitoring/overview?hours=${encodeURIComponent(hours)}${fresh ? '&fresh=1' : ''}`),
    // İzleme Grupları (TAKIM + izleme TÜRÜ bazlı) — autocomplete + yeniden adlandırma; server-side takım filtresi
    listGroups: (teamId, type) => {
      const p = new URLSearchParams()
      if (teamId != null && teamId !== '') p.set('teamId', teamId)
      if (type) p.set('type', type)
      const q = p.toString()
      return request('/monitoring/groups' + (q ? '?' + q : ''))
    },
    renameGroup: (id, newName) => request('/monitoring/groups/' + id, { method: 'PUT', body: JSON.stringify({ new_name: newName }) }),
    // Takımın kullanımdaki etiketleri (tüm türler + envanter) → form autocomplete (2026-09-22)
    listTags: (teamId) => request('/monitoring/tags?teamId=' + encodeURIComponent(teamId)),
    // Monitor guide + notes (hedef-bazlı: type = KEYWORD|PING, target = url/host)
    getMonitorNotes: (type, target) =>
      request(`/monitoring/notes?type=${encodeURIComponent(type)}&target=${encodeURIComponent(target)}`),
    saveMonitorGuide: (type, target, guide) =>
      request('/monitoring/notes/guide', { method: 'PUT', body: JSON.stringify({ type, target, guide }) }),
    addMonitorNote: (data) => request('/monitoring/notes', { method: 'POST', body: JSON.stringify(data) }),
    updateMonitorNote: (id, data) => request(`/monitoring/notes/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteMonitorNote: (id) => request(`/monitoring/notes/${id}`, { method: 'DELETE' }),

    // Incidents Overview (AlertEvent tabanlı — tüm monitörlerin olayları)
    incidents: {
      list: (params = {}) => {
        const q = new URLSearchParams()
        // scope: mine | others | all (2026-09-28, org geneli salt okunur olaylar; varsayılan mine gönderilmez)
        for (const k of ['status', 'rootCause', 'q', 'since', 'until', 'sort', 'dir', 'page', 'size', 'scope']) {
          if (params[k] != null && params[k] !== '') q.set(k, params[k])
        }
        const qs = q.toString()
        return request(`/monitoring/incidents${qs ? '?' + qs : ''}`)
      },
      // Tekil olay — e-posta derin linki (?incident=<id>) sayfalı listede olmayan olayı da açabilsin.
      get:           (id) => request(`/monitoring/incidents/${id}`),
      comments:      (id) => request(`/monitoring/incidents/${id}/comments`),
      addComment:    (id, body) => request(`/monitoring/incidents/${id}/comments`, { method: 'POST', body: JSON.stringify({ body }) }),
      deleteComment: (commentId) => request(`/monitoring/incidents/comments/${commentId}`, { method: 'DELETE' }),
      remove:        (id) => request(`/monitoring/incidents/${id}`, { method: 'DELETE' }),
    },

    // Maintenance Windows (bakım penceresi)
    maintenance: {
      list:   () => request('/monitoring/maintenance'),
      active: () => request('/monitoring/maintenance/active'),
      create: (data) => request('/monitoring/maintenance', { method: 'POST', body: JSON.stringify(data) }),
      update: (id, data) => request(`/monitoring/maintenance/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
      remove: (id) => request(`/monitoring/maintenance/${id}`, { method: 'DELETE' }),
      pause:  (id) => request(`/monitoring/maintenance/${id}/pause`, { method: 'POST' }),
      resume: (id) => request(`/monitoring/maintenance/${id}/resume`, { method: 'POST' }),
      quick:  (data) => request('/monitoring/maintenance/quick', { method: 'POST', body: JSON.stringify(data) }),
    },

    // Alarm fırtınası (alert storm) ayarları — "Alert Settings" bölümü
    storm: {
      // Takım bazlı fırtına gözlemi (2026-09-30): alerts.read kapısı, görüş kapsamı sunucuda
      status:    (fresh = false) => request('/monitoring/storm/status' + (fresh ? '?fresh=1' : '')),
      history:   (p = {}) => { const q = new URLSearchParams(); for (const [k, v] of Object.entries(p)) if (v !== undefined && v !== null && v !== '') q.set(k, v); const s = q.toString(); return request('/monitoring/storm/history' + (s ? '?' + s : '')) },
      analytics: (p = {}) => { const q = new URLSearchParams(); for (const [k, v] of Object.entries(p)) if (v !== undefined && v !== null && v !== '') q.set(k, v); const s = q.toString(); return request('/monitoring/storm/analytics' + (s ? '?' + s : '')) },
      detail:    (id) => request(`/monitoring/storm/${encodeURIComponent(id)}`),
      getSettings:  () => request('/monitoring/storm/settings'),
      saveSettings: (data) => request('/monitoring/storm/settings', { method: 'PUT', body: JSON.stringify(data) }),
    },

    // Uptime
    // scope 'mine' (varsayılan) | 'all' (org geneli görünürlük, 2026-09-26): kartlar `can_manage` + `team_id` taşır
    getUptimeOverview:    (scope = 'mine') => request(`/monitoring/uptime/overview${scope === 'all' ? '?scope=all' : ''}`),
    getUptimeHistory:     (domain, hours = 24) => request(`/monitoring/uptime/${encodeURIComponent(domain)}/history?hours=${hours}`),

    // ── Kontrol Geçmişi v2 — TÜM türlerin tek history istemcisi (CheckHistoryTab kullanır) ──
    // kind: keyword|ping|port|http|domain|page|scripted|dns|uptime-http|uptime-ssl
    // (uptime türlerinde id = domain, params.port yalnız uptime-http'de anlamlı)
    // params: { from, to, days, status, changedOnly, page (0-tabanlı), size, port }
    getCheckHistory: (kind, id, params = {}) => {
      const q = historyQuery(params)
      return request(`${historyPath(kind, id)}${q ? `?${q}` : ''}`)
    },
    // CSV indirme <a href download> ile yapılır (session cookie same-origin) — fetch değil.
    getCheckHistoryCsvUrl: (kind, id, params = {}) => {
      const q = historyQuery({ ...params, format: 'csv' })
      return `${BASE}${historyPath(kind, id)}${q ? `?${q}` : ''}`
    },

    // Port
    getPortMonitors:   () => request('/monitoring/port'),
    createPortMonitor: (data) => request('/monitoring/port', { method: 'POST', body: JSON.stringify(data) }),
    updatePortMonitor: (id, data) => request(`/monitoring/port/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deletePortMonitor: (id) => request(`/monitoring/port/${id}`, { method: 'DELETE' }),
    triggerPortCheck:  (id) => request(`/monitoring/port/${id}/check`, { method: 'POST' }),
    testPortMonitor:   (data) => request('/monitoring/port/test', { method: 'POST', body: JSON.stringify(data) }),
    // Vekil bilgisi (2026-09-24): tanımlı mı + CONNECT tüneline izin verilen portlar (form notları)
    getPortProxyInfo:  () => request('/monitoring/port/proxy-info'),
    getPortResponseSeries: (id, { from, to, days } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/port/${id}/response-series${q ? `?${q}` : ''}`)
    },
    // Port uçtan uca tanılama (2026-10-05) — gövdesiz POST; vekil tanımlıysa sunucu iki yolu da dener. 75 sn tavan, withStatus.
    diagnosePort: async (id, _body, { signal } = {}) => {
      const dl = deadlineSignal(signal, 75000)
      try {
        return await request(`/monitoring/port/${id}/diagnose`, {
          method: 'POST', withStatus: true, ...(dl.signal ? { signal: dl.signal } : {}),
        })
      } finally {
        dl.done()
      }
    },
    portDiagnoseHistory: (id) => request(`/monitoring/port/${id}/diagnose/history`, { withStatus: true }),
    portDiagnoseRun: (id, runId) => request(`/monitoring/port/${id}/diagnose/history/${encodeURIComponent(runId)}`, { withStatus: true }),

    // DNS
    // Canlı teyit zincirleri ("Teyit denemesi X/N") — detay modalları 30sn'de bir poll eder
    getConfirmations:  (domain) => request(`/monitoring/confirmations${domain ? `?domain=${encodeURIComponent(domain)}` : ''}`),
    getDnsMonitors:    () => request('/monitoring/dns'),
    createDnsMonitor:  (data) => request('/monitoring/dns', { method: 'POST', body: JSON.stringify(data) }),
    updateDnsMonitor:  (id, data) => request(`/monitoring/dns/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteDnsMonitor:  (id) => request(`/monitoring/dns/${id}`, { method: 'DELETE' }),
    triggerDnsCheck:   (id) => request(`/monitoring/dns/${id}/check`, { method: 'POST' }),
    getDnsResponseSeries: (id, { from, to, days } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/dns/${id}/response-series${q ? `?${q}` : ''}`)
    },
    getDnsDetails:     (id) => request(`/monitoring/dns/${id}/details`),
    testDnsMonitor:    (data) => request('/monitoring/dns/test', { method: 'POST', body: JSON.stringify(data) }),
    // DNS uçtan uca tanılama (2026-10-05) — gövdesiz POST; çözücüler + yetkili ad sunucuları. 75 sn tavan, withStatus.
    diagnoseDns: async (id, _body, { signal } = {}) => {
      const dl = deadlineSignal(signal, 75000)
      try {
        return await request(`/monitoring/dns/${id}/diagnose`, {
          method: 'POST', withStatus: true, ...(dl.signal ? { signal: dl.signal } : {}),
        })
      } finally {
        dl.done()
      }
    },
    dnsDiagnoseHistory: (id) => request(`/monitoring/dns/${id}/diagnose/history`, { withStatus: true }),
    dnsDiagnoseRun: (id, runId) => request(`/monitoring/dns/${id}/diagnose/history/${encodeURIComponent(runId)}`, { withStatus: true }),

    // Keyword
    getKeywordMonitors:   () => request('/monitoring/keyword'),
    createKeywordMonitor: (data) => request('/monitoring/keyword', { method: 'POST', body: JSON.stringify(data) }),
    updateKeywordMonitor: (id, data) => request(`/monitoring/keyword/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteKeywordMonitor: (id) => request(`/monitoring/keyword/${id}`, { method: 'DELETE' }),
    triggerKeywordCheck:  (id) => request(`/monitoring/keyword/${id}/check`, { method: 'POST' }),
    testKeyword:          (data) => request('/monitoring/keyword/test', { method: 'POST', body: JSON.stringify(data) }),
    // Keyword uçtan uca tanılama (2026-10-04) — HTTP tanılamasının aynası: 60 sn sunucu tavanı → istemci 75 sn bekler;
    // withStatus: 429 / 403 / 404 pencerede ayırt edilsin.
    diagnoseKeyword: async (id, { compare = true } = {}, { signal } = {}) => {
      const dl = deadlineSignal(signal, 75000)
      try {
        return await request(`/monitoring/keyword/${id}/diagnose`, {
          method: 'POST', body: JSON.stringify({ compare: compare !== false }), withStatus: true, ...(dl.signal ? { signal: dl.signal } : {}),
        })
      } finally {
        dl.done()
      }
    },
    keywordDiagnoseHistory: (id) => request(`/monitoring/keyword/${id}/diagnose/history`, { withStatus: true }),
    keywordDiagnoseRun: (id, runId) => request(`/monitoring/keyword/${id}/diagnose/history/${encodeURIComponent(runId)}`, { withStatus: true }),
    getKeywordResponseSeries: (id, { from, to, days } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/keyword/${id}/response-series${q ? `?${q}` : ''}`)
    },

    // Page Integrity (Sayfa Bütünlüğü) — 9. tür
    getPageMonitors:   () => request('/monitoring/page'),
    createPageMonitor: (data) => request('/monitoring/page', { method: 'POST', body: JSON.stringify(data) }),
    updatePageMonitor: (id, data) => request(`/monitoring/page/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deletePageMonitor: (id) => request(`/monitoring/page/${id}`, { method: 'DELETE' }),
    triggerPageCheck:  (id) => request(`/monitoring/page/${id}/check`, { method: 'POST' }),
    testPage:          (data) => request('/monitoring/page/test', { method: 'POST', body: JSON.stringify(data) }),
    getPageResponseSeries: (id, { from, to, days } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/page/${id}/response-series${q ? `?${q}` : ''}`)
    },
    getPageIssues:     (id, { issueType, days, limit } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ issueType, days, limit }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/page/${id}/issues${q ? `?${q}` : ''}`)
    },
    // Sayfa Bütünlüğü uçtan uca tanılama (2026-10-05) — keyword tanılamasının aynası: 60 sn sunucu tavanı → istemci 75 sn
    // bekler; withStatus: 429 / 403 / 404 pencerede ayırt edilsin.
    diagnosePage: async (id, { compare = true } = {}, { signal } = {}) => {
      const dl = deadlineSignal(signal, 75000)
      try {
        return await request(`/monitoring/page/${id}/diagnose`, {
          method: 'POST', body: JSON.stringify({ compare: compare !== false }), withStatus: true, ...(dl.signal ? { signal: dl.signal } : {}),
        })
      } finally {
        dl.done()
      }
    },
    pageDiagnoseHistory: (id) => request(`/monitoring/page/${id}/diagnose/history`, { withStatus: true }),
    pageDiagnoseRun: (id, runId) => request(`/monitoring/page/${id}/diagnose/history/${encodeURIComponent(runId)}`, { withStatus: true }),

    // Sayfa Hızı (Page Speed)
    getPageSpeedMonitors:   () => request('/monitoring/pagespeed'),
    createPageSpeedMonitor: (data) => request('/monitoring/pagespeed', { method: 'POST', body: JSON.stringify(data) }),
    updatePageSpeedMonitor: (id, data) => request(`/monitoring/pagespeed/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deletePageSpeedMonitor: (id) => request(`/monitoring/pagespeed/${id}`, { method: 'DELETE' }),
    triggerPageSpeedCheck:  (id) => request(`/monitoring/pagespeed/${id}/check`, { method: 'POST' }),
    testPageSpeed:          (data) => request('/monitoring/pagespeed/test', { method: 'POST', body: JSON.stringify(data) }),
    getPageSpeedSeries: (id, { from, to, days, metric } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days, metric }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/pagespeed/${id}/response-series${q ? `?${q}` : ''}`)
    },
    getPageSpeedResources: (id, { checkId, limit } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ checkId, limit }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/pagespeed/${id}/resources${q ? `?${q}` : ''}`)
    },
    // Sayfa Hızı uçtan uca tanılama (2026-10-05) — Sayfa Bütünlüğü ile aynı sözleşme.
    diagnosePageSpeed: async (id, { compare = true } = {}, { signal } = {}) => {
      const dl = deadlineSignal(signal, 75000)
      try {
        return await request(`/monitoring/pagespeed/${id}/diagnose`, {
          method: 'POST', body: JSON.stringify({ compare: compare !== false }), withStatus: true, ...(dl.signal ? { signal: dl.signal } : {}),
        })
      } finally {
        dl.done()
      }
    },
    pageSpeedDiagnoseHistory: (id) => request(`/monitoring/pagespeed/${id}/diagnose/history`, { withStatus: true }),
    pageSpeedDiagnoseRun: (id, runId) => request(`/monitoring/pagespeed/${id}/diagnose/history/${encodeURIComponent(runId)}`, { withStatus: true }),

    // Senaryo İzleme (Scripted Check / k6) — 10. tür
    getScriptedMonitors:   () => request('/monitoring/scripted'),
    createScriptedMonitor: (data) => request('/monitoring/scripted', { method: 'POST', body: JSON.stringify(data) }),
    updateScriptedMonitor: (id, data) => request(`/monitoring/scripted/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteScriptedMonitor: (id) => request(`/monitoring/scripted/${id}`, { method: 'DELETE' }),
    // withStatus: kayıt sonrası doğrulama bandı bekleme süresini (429) hata tostundan AYIRIR (ScriptedMonitorPage.runSmokeCheck)
    triggerScriptedCheck:  (id) => request(`/monitoring/scripted/${id}/check`, { method: 'POST', withStatus: true }),
    testScripted:          (data) => request('/monitoring/scripted/test', { method: 'POST', body: JSON.stringify(data) }),
    // Bağlantı teşhisi: aynı hedefe vekil/CA kombinasyonlarıyla k6 sondası — "Java çekiyor,
    // k6 çekmiyor" ayrımını ÖLÇER. İzleme havuzunu tüketmez (ayrı semafor), bacaklar sırayla koşar.
    diagnoseScripted:      (id, url) => request(`/monitoring/scripted/${id}/diagnose`,
                             { method: 'POST', body: JSON.stringify(url ? { url } : {}) }),
    // Sürüm geçmişi: liste gövde taşımaz (yüzlerce sürümde yanıt şişmesin), önizleme ayrı çağrı.
    getScriptedVersions:   (id) => request(`/monitoring/scripted/${id}/versions`),
    getScriptedVersion:    (id, versionId) => request(`/monitoring/scripted/${id}/versions/${versionId}`),
    // ── Yapılandırma değişiklik geçmişi (kim/ne zaman/hangi IP/neyi değiştirdi) ──
    // Script SÜRÜMLERİYLE karıştırmayın: orası script gövdesinin sürümleri, burası ayarların geçmişi.
    getChanges: (kind, id, params = {}) => {
      const q = new URLSearchParams()
      Object.keys(params).forEach(k => { if (params[k] != null && params[k] !== '') q.set(k, params[k]) })
      const qs = q.toString()
      return request(`/monitoring/changes/${kind}/${id}${qs ? '?' + qs : ''}`)
    },
    getChangeDetail: (kind, id, seq) => request(`/monitoring/changes/${kind}/${id}/${seq}`),
    getRecentChanges: (params = {}) => {
      const q = new URLSearchParams()
      Object.keys(params).forEach(k => { if (params[k] != null && params[k] !== '') q.set(k, params[k]) })
      const qs = q.toString()
      return request(`/monitoring/changes/recent${qs ? '?' + qs : ''}`)
    },
    // İzleme Değişiklikleri özet kartları (2026-09-28): pencere + takım için olay dağılımı, günlük eğri (tz = istemcinin
    // IANA saat dilimi), en çok değişen izlemeler, kişiler. Liste ucundan AYRI — sayfa çevirmek bunu yeniden istemez.
    getChangeSummary: (params = {}) => {
      const q = new URLSearchParams()
      Object.keys(params).forEach(k => { if (params[k] != null && params[k] !== '') q.set(k, params[k]) })
      const qs = q.toString()
      return request(`/monitoring/changes/summary${qs ? '?' + qs : ''}`)
    },
    // Geri döndürme: geçmişi EZMEZ, RESTORE olaylı yeni bir satır üretir (sunucu tarafında).
    restoreChange: (kind, id, seq, note) => request(`/monitoring/changes/${kind}/${id}/${seq}/restore`,
      { method: 'POST', body: JSON.stringify(note ? { changeNote: note } : {}) }),
    // Otomatik taslak — DOĞRULAMA YAPMAYAN ayrı uç (PUT /scripted/{id} her çağrıda k6 çalıştırıyor).
    saveScriptedDraft:     (data) => request('/monitoring/scripted/draft', { method: 'PUT', body: JSON.stringify(data) }),
    getScriptedDrafts:     () => request('/monitoring/scripted/drafts'),
    deleteScriptedDraft:   (monitorKey) => request(`/monitoring/scripted/draft/${monitorKey}`, { method: 'DELETE' }),
    getScriptedResponseSeries: (id, { from, to, days } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/scripted/${id}/response-series${q ? `?${q}` : ''}`)
    },

    // Şablon kütüphanesi — Genel (teamId null) + Takım katmanları.
    // Yanıt satır başına can_edit/can_delete/can_promote/... taşır; UI yetkiyi YENİDEN HESAPLAMAZ.
    getScriptedTemplates:   (scope) => request(`/monitoring/scripted/templates${scope ? `?scope=${scope}` : ''}`),
    getScriptedTemplate:    (id) => request(`/monitoring/scripted/templates/${id}`),
    createScriptedTemplate: (data) => request('/monitoring/scripted/templates', { method: 'POST', body: JSON.stringify(data) }),
    updateScriptedTemplate: (id, data) => request(`/monitoring/scripted/templates/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    // permanent=true yalnız admin'e ve yerleşik OLMAYAN şablona açık (yerleşiği seeder diriltir).
    deleteScriptedTemplate: (id, permanent) => request(`/monitoring/scripted/templates/${id}${permanent ? '?permanent=true' : ''}`, { method: 'DELETE' }),
    // Çöpten geri alma. Adı bilinçli `undelete`: bu ailede "restore" SÜRÜM geri yüklemedir.
    undeleteScriptedTemplate: (id) => request(`/monitoring/scripted/templates/${id}/undelete`, { method: 'POST' }),
    promoteScriptedTemplate:  (id) => request(`/monitoring/scripted/templates/${id}/promote`, { method: 'POST' }),
    demoteScriptedTemplate:   (id, teamId) => request(`/monitoring/scripted/templates/${id}/demote`, { method: 'POST', body: JSON.stringify({ teamId }) }),
    getScriptedTemplateVersions: (id) => request(`/monitoring/scripted/templates/${id}/versions`),
    getScriptedTemplateVersion:  (id, versionId) => request(`/monitoring/scripted/templates/${id}/versions/${versionId}`),

    // HTTP / Website
    getHttpMonitors:   () => request('/monitoring/http'),
    // Kart mini trendi (2026-09-12): tür başına tek toplu istek — saatlik kovalar + son 5 kontrol
    getSparklines: (type, hours = 24) => request(`/monitoring/sparklines?type=${encodeURIComponent(type)}&hours=${hours}`),
    // Kullanılabilirlik / SLA (2026-09-12, #11): 30 günlük oran + hedef
    getSla: (type, days = 30) => request(`/monitoring/sla?type=${encodeURIComponent(type)}&days=${days}`),
    createHttpMonitor: (data) => request('/monitoring/http', { method: 'POST', body: JSON.stringify(data) }),
    updateHttpMonitor: (id, data) => request(`/monitoring/http/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteHttpMonitor: (id) => request(`/monitoring/http/${id}`, { method: 'DELETE' }),
    triggerHttpCheck:  (id) => request(`/monitoring/http/${id}/check`, { method: 'POST' }),
    testHttp:          (data) => request('/monitoring/http/test', { method: 'POST', body: JSON.stringify(data) }),
    // HTTP uçtan uca tanılama (2026-10-02): izlemenin kendi yolu + vekil tanımlıysa öteki yol, adım adım. Sunucu tüm
    // çalıştırmayı 60 sn'de keser → istemci 75 sn bekler (çağıranın `signal`'i İptal için; ikisi tek sinyalde birleşir).
    // withStatus: 429 / 403 / 404 hata gövdesinde ayırt edilsin (pencere hız sınırı şeridini buna göre çizer).
    diagnoseHttp: async (id, { compare = true } = {}, { signal } = {}) => {
      const dl = deadlineSignal(signal, 75000)
      try {
        return await request(`/monitoring/http/${id}/diagnose`, {
          method: 'POST', body: JSON.stringify({ compare: compare !== false }), withStatus: true, ...(dl.signal ? { signal: dl.signal } : {}),
        })
      } finally {
        dl.done()
      }
    },
    httpDiagnoseHistory: (id) => request(`/monitoring/http/${id}/diagnose/history`, { withStatus: true }),
    httpDiagnoseRun: (id, runId) => request(`/monitoring/http/${id}/diagnose/history/${encodeURIComponent(runId)}`, { withStatus: true }),
    getHttpResponseSeries: (id, { from, to, days } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/http/${id}/response-series${q ? `?${q}` : ''}`)
    },

    // Domain (alan adı süre bitişi)
    getDomainMonitors:   () => request('/monitoring/domain'),
    createDomainMonitor: (data) => request('/monitoring/domain', { method: 'POST', body: JSON.stringify(data) }),
    updateDomainMonitor: (id, data) => request(`/monitoring/domain/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteDomainMonitor: (id) => request(`/monitoring/domain/${id}`, { method: 'DELETE' }),
    triggerDomainCheck:  (id) => request(`/monitoring/domain/${id}/check`, { method: 'POST' }),
    testDomain:          (data) => request('/monitoring/domain/test', { method: 'POST', body: JSON.stringify(data) }),
    // Domain Kaydı (registration) — DB'deki son bilgi; live=true → anlık RDAP sorgusu.
    getDomainRegistration: (id, { live } = {}) =>
      request(`/monitoring/domain/${id}/registration${live ? '?live=true' : ''}`),
    // Kalan-gün trendi (2026-09-22): günlük seri + değişiklik işaretleri
    getDomainTrend: (id, days = 90) => request(`/monitoring/domain/${id}/trend?days=${days}`),
    // Gönderilen süre-bitişi hatırlatmaları (2026-09-22): eşikler + kayıtlar
    getDomainReminders: (id) => request(`/monitoring/domain/${id}/reminders`),
    // Yenileme planı (2026-09-22, H) — sertifikadaki forecastPlan/forecastUnplan eşi, izleme kimliğiyle
    domainRenewalPlan:   (id, date, note) => request(`/monitoring/domain/${id}/renewal-plan`, { method: 'POST', body: JSON.stringify({ date, note }) }),
    domainRenewalUnplan: (id) => request(`/monitoring/domain/${id}/renewal-plan`, { method: 'DELETE' }),

    // Ping
    getPingMonitors:   () => request('/monitoring/ping'),
    monitorDefaults: () => request('/monitoring/defaults'),
    createPingMonitor: (data) => request('/monitoring/ping', { method: 'POST', body: JSON.stringify(data) }),
    updatePingMonitor: (id, data) => request(`/monitoring/ping/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deletePingMonitor: (id) => request(`/monitoring/ping/${id}`, { method: 'DELETE' }),
    triggerPingCheck:  (id) => request(`/monitoring/ping/${id}/check`, { method: 'POST' }),
    testPingMonitor:   (data) => request('/monitoring/ping/test', { method: 'POST', body: JSON.stringify(data) }),
    getPingResponseSeries: (id, { from, to, days } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/ping/${id}/response-series${q ? `?${q}` : ''}`)
    },
    // Ping uçtan uca tanılama (2026-10-05) — HTTP tanılamasının aynası: 60 sn sunucu tavanı → istemci 75 sn bekler;
    // `traceroute: true` yol üzerindeki atlamaları da listeler (~20 sn ekler). withStatus: 429 / 403 / 404 ayırt edilsin.
    diagnosePing: async (id, { traceroute = false } = {}, { signal } = {}) => {
      const dl = deadlineSignal(signal, 75000)
      try {
        return await request(`/monitoring/ping/${id}/diagnose`, {
          method: 'POST', body: JSON.stringify({ traceroute: traceroute === true }), withStatus: true, ...(dl.signal ? { signal: dl.signal } : {}),
        })
      } finally {
        dl.done()
      }
    },
    pingDiagnoseHistory: (id) => request(`/monitoring/ping/${id}/diagnose/history`, { withStatus: true }),
    pingDiagnoseRun: (id, runId) => request(`/monitoring/ping/${id}/diagnose/history/${encodeURIComponent(runId)}`, { withStatus: true }),

    // Sertifika serisi: id yerine DOMAIN (cert domain-anahtarlı) → nokta içerdiği için encodeURIComponent
    // şart (historyPath'teki uptime-ssl ile aynı kural). İki seri döner: avg/p95 = ms, days = kalan gün.
    getSslResponseSeries: (domain, { from, to, days } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/uptime/${encodeURIComponent(domain)}/ssl/response-series${q ? `?${q}` : ''}`)
    },
  },
}

/**
 * Sunucu damgasını `new Date(...)`'ın UTC olarak okuyacağı biçime getirir.
 *
 * Backend damgaları saat dilimi eki OLMADAN gelir ("2026-09-07T10:00:00"); JS bunları YEREL
 * saat sayar, o yüzden sonuna `Z` eklenir. Eski hâli iki girdide bozuluyordu:
 *
 * 1. NEGATİF ofset — yalnız `'+'` aranıyordu, "2026-09-07T10:00:00-03:00" onu içermediği için
 *    sonuna `Z` ekleniyor ve "…-03:00Z" çıkıyordu → `Invalid Date`. `toLocaleString`
 *    FIRLATMADIĞI için catch dalı da çalışmıyor, ekrana düpedüz "Invalid Date" yazılıyordu.
 * 2. YALNIZ TARİH — "2026-09-07" + "Z" = "2026-09-07Z", yine `Invalid Date`
 *    (formatDateOnly tam da bu biçimi alıyor).
 *
 * Ayrıca dize olmayan girdide `.endsWith` FIRLATIYORDU; artık olduğu gibi geçiriliyor
 * (`new Date` zaten Date/number kabul eder).
 */
// toUtc / localDayKey: utils/localDay.js (yeniden dışa aktarılır — eski import yolları geçerli)
export { toUtc, localDayKey }

export function formatDate(iso) {
  if (!iso) return 'N/A'
  try {
    return new Date(toUtc(iso)).toLocaleString(dateLocale(), {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit',
    })
  } catch {
    return iso
  }
}

// formatDate gibi ama saniye dahil (login/oturum zamanları için — saniye hassasiyeti gerekir).
export function formatDateSec(iso) {
  if (!iso) return 'N/A'
  try {
    return new Date(toUtc(iso)).toLocaleString(dateLocale(), {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })
  } catch {
    return iso
  }
}

export function formatTime(iso) {
  if (!iso) return '—'
  try {
    return new Date(toUtc(iso)).toLocaleTimeString(dateLocale(), {
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })
  } catch {
    return iso.substring(11, 19)
  }
}


export function formatDateOnly(iso) {
  if (!iso) return '—'
  try {
    return new Date(toUtc(iso)).toLocaleDateString(dateLocale(), {
      year: 'numeric', month: '2-digit', day: '2-digit',
    })
  } catch {
    return iso.substring(0, 10)
  }
}
