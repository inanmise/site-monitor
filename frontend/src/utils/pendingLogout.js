/**
 * Bekleyen sunucu çıkışı (2026-10-22, hata UIM-1). Hareketsizlik çıkışı ya da elle "Çıkış yap" sırasında `/logout`
 * isteği düşerse (uykudan uyanış, Wi-Fi / VPN kopması, dağıtım sırasında sunucu yeniden başlıyor) istemci oturumu YİNE
 * kapatır — giriş formu açılır, bellek ve kişisel kayıtlar temizlenir. Sunucu oturumu ise hâlâ geçerlidir (24 sa) ve
 * çerez duruyor: F5 kullanıcıyı geri getirirdi. Bu yüzden bir işaret (`sm.logout.pending`, localStorage — sekmeler
 * görür) bırakılır; açılış `/me`'den ÖNCE çıkışı yeniden dener, giriş formu açıkken de çevrimiçi olunca / aralıkla
 * yeniden denenir. Başarıda işaret silinir. Döngü sınırlı: istek süre sınırlı, yeniden deneme sayısı sınırlı.
 *
 * <p>İşaretin değeri bir jetondur: geç biten eski bir çıkış isteği SONRAKİ bir çıkışın işaretini silemez.
 */

export const PENDING_LOGOUT_KEY = 'sm.logout.pending'
/** Tek çıkış denemesinin üst süresi — istemci temizliği bundan uzun beklemez. */
export const LOGOUT_TIMEOUT_MS = 3000
/** Giriş formu açıkken yeniden deneme aralığı ve en çok deneme sayısı (sonsuz döngü yok). */
export const LOGOUT_RETRY_MS = 30_000
export const LOGOUT_RETRY_MAX = 20

function store() {
  try { return globalThis.localStorage || null } catch { return null }
}

/** Bekleyen (sunucuda onaylanmamış) bir çıkış var mı? */
export function isLogoutPending() {
  try { return !!store()?.getItem(PENDING_LOGOUT_KEY) } catch { return false }
}

/** İşareti bırakır; jetonu döner (yalnız aynı jetonla silinir — bkz. {@link clearLogoutPending}). */
export function markLogoutPending(now = Date.now()) {
  const token = `${now}-${Math.random().toString(36).slice(2, 10)}`
  try { store()?.setItem(PENDING_LOGOUT_KEY, token) } catch { /* depolama kapalı: işaret yok, temizlik yine yapıldı */ }
  return token
}

/** İşareti siler. `token` verilirse yalnız işaret hâlâ o jetonu taşıyorsa (geç biten eski istek yenisini silemez). */
export function clearLogoutPending(token = null) {
  try {
    const s = store()
    if (!s) return
    if (token == null || s.getItem(PENDING_LOGOUT_KEY) === token) s.removeItem(PENDING_LOGOUT_KEY)
  } catch { /* yoksay */ }
}

/** Mevcut jeton (yoksa null). */
export function pendingLogoutToken() {
  try { return store()?.getItem(PENDING_LOGOUT_KEY) || null } catch { return null }
}

/**
 * Sunucu çıkışı onaylandı mı? `{success:true}` → evet. `null` = request()'in 401 dalı: sunucuda oturum zaten yok → evet.
 * Ağ hatası, 5xx, JSON olmayan proxy sayfası, zaman aşımı → hayır.
 */
export function logoutConfirmed(res) {
  return res === null || res?.success === true
}

/**
 * Sunucu çıkışını SÜRE SINIRLI dener; ASLA reddetmez. `{ ok, late }`: `ok` süre içinde onaylandı mı; `late` isteğin
 * kendisinin sonucu (süre dolduktan sonra da biter — çağıran işareti o zaman silebilir).
 */
export async function tryServerLogout(logoutFn, timeoutMs = LOGOUT_TIMEOUT_MS) {
  let late
  try {
    late = Promise.resolve().then(() => logoutFn()).then(logoutConfirmed, () => false)
  } catch {
    late = Promise.resolve(false)
  }
  let timer
  const timeout = new Promise((resolve) => { timer = setTimeout(() => resolve(false), timeoutMs) })
  try {
    const ok = await Promise.race([late, timeout])
    return { ok, late }
  } finally {
    clearTimeout(timer)
  }
}

/**
 * Bekleyen çıkışı bir kez kapatmayı dener: işaret yoksa hemen `true`. Onaylanırsa işaret (aynı jetonla) silinir.
 * Süre dolsa da istek sonradan onaylanırsa işaret yine silinir.
 * @returns {Promise<boolean>} sunucuda kapanmamış oturum kalmadı mı
 */
export async function settlePendingLogout(logoutFn, timeoutMs = LOGOUT_TIMEOUT_MS) {
  const token = pendingLogoutToken()
  if (!token) return true
  const { ok, late } = await tryServerLogout(logoutFn, timeoutMs)
  if (ok) { clearLogoutPending(token); return true }
  late.then((v) => { if (v) clearLogoutPending(token) }).catch(() => {})
  return false
}

/**
 * Giriş formu açıkken bekleyen çıkışı yeniden dener: tarayıcı çevrimiçi olunca (`online`) ve her `intervalMs`'de bir,
 * en çok `max` kez; işaret kalkınca (başarı, başka sekmede giriş) durur. Aynı anda tek deneme.
 * @returns {() => void} durdurur
 */
export function startPendingLogoutRetry(logoutFn, { intervalMs = LOGOUT_RETRY_MS, max = LOGOUT_RETRY_MAX, timeoutMs = LOGOUT_TIMEOUT_MS } = {}) {
  let stopped = false
  let busy = false
  let attempts = 0
  let timer = null
  const stop = () => {
    stopped = true
    if (timer) clearInterval(timer)
    timer = null
    try { window.removeEventListener('online', attempt) } catch { /* yok */ }
  }
  async function attempt() {
    if (stopped || busy) return
    if (!isLogoutPending()) { stop(); return }
    if (attempts >= max) { stop(); return }
    attempts += 1
    busy = true
    try {
      if (await settlePendingLogout(logoutFn, timeoutMs)) stop()
    } finally {
      busy = false
    }
  }
  if (!isLogoutPending()) return () => {}
  timer = setInterval(attempt, intervalMs)
  try { window.addEventListener('online', attempt) } catch { /* yok */ }
  return stop
}
