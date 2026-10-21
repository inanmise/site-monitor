/**
 * Kullanıcı adı ön eki sinyali (2026-10-21, kullanıcı isteği: "yalnız N ile başlayan kullanıcıya login izni; MP'li ya da
 * başka harfli olana izin vermeyelim"). Pasif hesap sinyalinin (`utils/accountInactive.js`) kardeşi. Sunucu iki yerde aynı
 * gövdeyi döner — `{ success:false, code:'USERNAME_PREFIX_NOT_ALLOWED', error_code, error, allowed_prefixes:[…] }`:
 *
 * - `POST /api/login` → **403** (kimlik bilgisi DOĞRULANDIKTAN sonra — yanlış parola genel 401 alır; yazılan ad ve kayıtlı /
 *   AD adı kurala uymalı) → giriş formu hata alanında izinli ön ekleri adlandıran metni gösterir.
 * - Oturumlu her `/api/**` isteği → **401** (kural açıldı ya da değişti; canlı oturum ya da remember-me çerezi kurala uymuyor)
 *   → `api/client.js` bu modüle haber verir; uygulama kapatılamaz geri sayımlı pencereyi açar, sonra `/?session=prefix`.
 *
 * Sinyal oturum başına TEK kez yayılır ve `localStorage` ile öteki sekmelere duyurulur (her sekme kendi penceresini açar);
 * sunucu da aynı çerezle gelen her isteğe aynı sinyali verir (oturum mezar taşı).
 */

export const USERNAME_PREFIX_CODE = 'USERNAME_PREFIX_NOT_ALLOWED'
/** Aynı sekme içi olay adı. */
export const USERNAME_PREFIX_EVENT = 'sm:username-prefix'
/** Sekmeler arası duyuru anahtarı (değer = JSON {at, prefixes}). */
export const USERNAME_PREFIX_STORAGE_KEY = 'sm.account.usernamePrefix'
/** Bloklayan pencerenin geri sayımı (sn) — pasif hesap penceresiyle aynı. */
export const USERNAME_PREFIX_COUNTDOWN = 10
/** Geri sayım bitince gidilen giriş adresi — giriş sayfası ön ek bildirimini gösterir. */
export const USERNAME_PREFIX_REDIRECT = '/?session=prefix'

let signaled = false
let lastPrefixes = []

/** Yanıt gövdesi ön ek sinyali mi? (`code` ya da giriş ucunun `error_code` alanı) */
export function isUsernamePrefixPayload(body) {
  return !!body && typeof body === 'object'
    && (body.code === USERNAME_PREFIX_CODE || body.error_code === USERNAME_PREFIX_CODE)
}

/** Gövdedeki izinli ön ekler (yalnız kısa dizeler; bozuk değer boş liste). */
export function prefixesOf(body) {
  const list = Array.isArray(body?.allowed_prefixes) ? body.allowed_prefixes : []
  return list.filter((p) => typeof p === 'string' && p.length > 0 && p.length <= 10).slice(0, 20)
}

/**
 * Sinyali yayar — oturum başına YALNIZ ilk çağrı etkilidir (eşzamanlı 401'ler tek pencere açar; giriş / çıkış bayrağı
 * {@link resetUsernamePrefixSignal} ile sıfırlar).
 * @returns {boolean} ilk sinyal mi
 */
export function signalUsernamePrefix(prefixes = []) {
  if (signaled) return false
  signaled = true
  lastPrefixes = Array.isArray(prefixes) ? prefixes : []
  try { window.dispatchEvent(new CustomEvent(USERNAME_PREFIX_EVENT, { detail: { prefixes: lastPrefixes } })) } catch { /* olay yok */ }
  try { localStorage.setItem(USERNAME_PREFIX_STORAGE_KEY, JSON.stringify({ at: Date.now(), prefixes: lastPrefixes })) } catch { /* depolama kapalı */ }
  return true
}

/**
 * Sinyali dinler: aynı sekmedeki olay + öteki sekmeden gelen duyuru. Öteki sekmenin duyurusu bu sekmede de sinyali
 * "verilmiş" sayar (bu sekme kendi 401'ini aldığında ikinci pencere açılmaz).
 * @returns {() => void} aboneliği kaldırır
 */
export function onUsernamePrefix(handler) {
  const onEvent = (e) => handler('local', e?.detail?.prefixes || lastPrefixes)
  const onStorage = (e) => {
    if (e?.key !== USERNAME_PREFIX_STORAGE_KEY || !e.newValue || signaled) return
    signaled = true
    let prefixes = []
    try { prefixes = prefixesOf({ allowed_prefixes: JSON.parse(e.newValue)?.prefixes }) } catch { prefixes = [] }
    lastPrefixes = prefixes
    handler('remote', prefixes)
  }
  window.addEventListener(USERNAME_PREFIX_EVENT, onEvent)
  window.addEventListener('storage', onStorage)
  return () => {
    window.removeEventListener(USERNAME_PREFIX_EVENT, onEvent)
    window.removeEventListener('storage', onStorage)
  }
}

/** Bu sayfa ömründe sinyal verildi mi? (dinleyici sinyalden SONRA bağlandıysa açılış yarışı kaçmasın) */
export function usernamePrefixSignaled() {
  return signaled
}

/** Son sinyalin izinli ön ekleri. */
export function lastUsernamePrefixes() {
  return lastPrefixes
}

/** Adres ön ek bildirimi taşıyor mu (`?session=prefix`)? */
export function usernamePrefixFromUrl() {
  try { return new URLSearchParams(window.location.search).get('session') === 'prefix' } catch { return false }
}

/**
 * Sinyal bayrağını sıfırlar — başarılı girişte ve sekme içi çıkışta / oturum sıfırlamada (App.handleLogin,
 * App.resetSessionData; inceleme 2026-10-21): açılışta verilmiş sinyal (ör. reddedilen MP remember-me çerezi) sonra N
 * hesabıyla açılan oturumda kuralın yeni bir kesimini yutmasın. Testler de kullanır.
 */
export function resetUsernamePrefixSignal() {
  signaled = false
  lastPrefixes = []
}
