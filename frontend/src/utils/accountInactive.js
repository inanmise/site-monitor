/**
 * Pasif hesap sinyali (2026-10-02, kullanıcı kararı: "pasif kullanıcı hiçbir yoldan giriş yapamaz; oturumu açıksa hemen
 * kapanır"). Sunucu iki yerde aynı gövdeyi döner — `{ success:false, code:'ACCOUNT_INACTIVE', error_code, error }`:
 *
 * - `POST /api/login` → **403** (kimlik bilgisi doğrulandıktan SONRA; yanlış parolada genel 401 değişmez) → giriş formu
 *   hata alanında pasif mesajını gösterir.
 * - Oturumlu her `/api/**` isteği → **401** (canlı oturum pasife alındı / remember-me çerezi pasif hesaba ait) →
 *   `api/client.js` bu modüle haber verir; uygulama "Hesabınız pasife alındı" penceresini açar (oturumu düşmüş sayıp
 *   `/?session=expired`'a YÖNLENDİRMEZ).
 *
 * Sinyal sayfa ömründe TEK kez yayılır (aynı anda düşen beş istek tek pencere açar) ve `localStorage` üzerinden aynı
 * tarayıcının öteki sekmelerine de duyurulur — her sekme kendi penceresini gösterir. Sunucu tarafı da aynı çerezle gelen
 * her isteğe aynı sinyali verir (oturum mezar taşı), yani duyuru kaçsa bile sekme bir sonraki yoklamada (~15 sn) yakalar.
 */

export const ACCOUNT_INACTIVE = 'ACCOUNT_INACTIVE'
/** Aynı sekme içi olay adı. */
export const ACCOUNT_INACTIVE_EVENT = 'sm:account-inactive'
/** Sekmeler arası duyuru anahtarı (değer = zaman damgası; `storage` olayı öteki sekmelerde tetiklenir). */
export const ACCOUNT_INACTIVE_STORAGE_KEY = 'sm.account.inactive'
/** Bloklayan pencerenin geri sayımı (sn). */
export const ACCOUNT_INACTIVE_COUNTDOWN = 10
/** Geri sayım bitince gidilen giriş adresi — giriş sayfası pasif bildirimini gösterir. */
export const ACCOUNT_INACTIVE_REDIRECT = '/?session=inactive'

let signaled = false

/** Yanıt gövdesi pasif hesap sinyali mi? (`code` ya da giriş ucunun `error_code` alanı) */
export function isAccountInactivePayload(body) {
  return !!body && typeof body === 'object'
    && (body.code === ACCOUNT_INACTIVE || body.error_code === ACCOUNT_INACTIVE)
}

/**
 * Sinyali yayar — sayfa ömründe YALNIZ ilk çağrı etkilidir (eşzamanlı 401'ler tek pencere açar).
 * @returns {boolean} ilk sinyal mi
 */
export function signalAccountInactive() {
  if (signaled) return false
  signaled = true
  try { window.dispatchEvent(new CustomEvent(ACCOUNT_INACTIVE_EVENT)) } catch { /* olay yok */ }
  try { localStorage.setItem(ACCOUNT_INACTIVE_STORAGE_KEY, String(Date.now())) } catch { /* depolama kapalı */ }
  return true
}

/**
 * Sinyali dinler: aynı sekmedeki olay + öteki sekmeden gelen duyuru. Öteki sekmenin duyurusu bu sekmede de sinyali
 * "verilmiş" sayar (bu sekme kendi 401'ini aldığında ikinci pencere açılmaz).
 * @returns {() => void} aboneliği kaldırır
 */
export function onAccountInactive(handler) {
  const onEvent = () => handler('local')
  const onStorage = (e) => {
    if (e?.key !== ACCOUNT_INACTIVE_STORAGE_KEY || !e.newValue || signaled) return
    signaled = true
    handler('remote')
  }
  window.addEventListener(ACCOUNT_INACTIVE_EVENT, onEvent)
  window.addEventListener('storage', onStorage)
  return () => {
    window.removeEventListener(ACCOUNT_INACTIVE_EVENT, onEvent)
    window.removeEventListener('storage', onStorage)
  }
}

/** Bu sayfa ömründe sinyal verildi mi? Dinleyici sinyalden SONRA bağlandıysa (açılış yarışı) kaçırmasın diye. */
export function accountInactiveSignaled() {
  return signaled
}

/** Adres pasif hesap bildirimi taşıyor mu (`?session=inactive`)? */
export function accountInactiveFromUrl() {
  try { return new URLSearchParams(window.location.search).get('session') === 'inactive' } catch { return false }
}

/**
 * Sert yönlendirme (tüm bellek durumu sıfırlanır). Tek nokta: oturum düşüşü (`/?session=expired`) ve pasif hesap
 * (`/?session=inactive`) aynı yoldan gider — testler bu dışa aktarımı taklit eder (jsdom `location.assign` taklit edilemez).
 */
export function assignLocation(url) {
  window.location.assign(url)
}

/** Test kancası: sayfa ömrü bayrağını sıfırlar. */
export function resetAccountInactiveSignal() {
  signaled = false
}
