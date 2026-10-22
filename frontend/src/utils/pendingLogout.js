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
/**
 * Tek `/logout` isteğinin MUTLAK ömrü (2026-10-22, D1): süre dolunca istek İPTAL edilir (AbortController) — iptal edilen isteğin
 * yanıtı tarayıcıda uygulanmaz. Geç biten bir çıkış yanıtı (`Set-Cookie: SESSION=; Max-Age=0`, remember-me silme) yeni girişin
 * çerezini silemesin diye giriş, bu sekmenin uçuştaki çıkışlarını bitirir / iptal eder ve öteki sekmelerinkini bekler.
 */
export const LOGOUT_ABORT_MS = 10_000
/** Giriş isteğinden önce bekleyen çıkış(lar)ın kapanmasını en çok bu kadar bekler; sonra uçuştakiler iptal edilir. */
export const LOGIN_LOGOUT_WAIT_MS = 5000
/** Giriş sürerken öteki sekmelerin YENİ çıkış denemesi başlatmaması için bırakılan işaretin ömrü (sekme ölürse kendiliğinden düşer). */
export const LOGIN_HOLD_MS = 30_000
/** Uçuştaki çıkış isteklerinin sekmeler arası duyurusu: anahtar başına bir deneme, değer = iptal anı (ms). */
export const LOGOUT_INFLIGHT_PREFIX = 'sm.logout.inflight.'
/** Süren giriş işareti: anahtar başına bir giriş, değer = işaretin bitiş anı (ms). */
export const LOGIN_HOLD_PREFIX = 'sm.login.hold.'

/** Bu sekmenin uçuştaki çıkış istekleri: id → { controller, done } */
const inflight = new Map()

const newId = () => `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`

// Sekme kapanırken / gezinirken tarayıcı uçuştaki istekleri zaten iptal eder — duyurular da hemen kalksın (öteki sekmenin girişi
// ölü bir duyuruyu iptal anına kadar beklemesin).
try {
  globalThis.addEventListener?.('pagehide', () => {
    for (const id of inflight.keys()) { try { store()?.removeItem(LOGOUT_INFLIGHT_PREFIX + id) } catch { /* yoksay */ } }
  })
} catch { /* pencere yok */ }

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

/** Önekli canlı (süresi dolmamış) anahtarlar; süresi dolanlar temizlenir. `except` = yok sayılacak anahtarlar. */
function liveKeys(prefix, except = null, now = Date.now()) {
  const st = store()
  if (!st) return []
  const live = [], stale = []
  try {
    for (let i = 0; i < st.length; i++) {
      const k = st.key(i)
      if (!k || !k.startsWith(prefix) || (except && except.has(k))) continue
      const until = Number(st.getItem(k))
      if (Number.isFinite(until) && until > now) live.push({ key: k, until })
      else stale.push(k)
    }
    for (const k of stale) st.removeItem(k)
  } catch { /* depolama okunamadı — duyuru yok sayılır */ }
  return live
}

/** Başka bir sekmede (ya da bu sekmede) giriş sürüyor mu — yeni çıkış denemesi başlatılmaz. */
export function loginHoldActive(now = Date.now()) {
  return liveKeys(LOGIN_HOLD_PREFIX, null, now).length > 0
}

/** Giriş başlıyor: öteki sekmelerin yeni çıkış denemesini durdurur. Bırakma işlevi döner (girişten sonra çağrılır). */
export function holdLoginStart(now = Date.now()) {
  const key = LOGIN_HOLD_PREFIX + newId()
  try { store()?.setItem(key, String(now + LOGIN_HOLD_MS)) } catch { /* depolama kapalı */ }
  return () => { try { store()?.removeItem(key) } catch { /* yoksay */ } }
}

/** Bu sekmede uçuşta çıkış isteği var mı. */
export function hasInflightLogout() {
  return inflight.size > 0
}

const sleep = (ms) => new Promise((r) => { setTimeout(r, ms) })
const within = (p, ms) => Promise.race([p, sleep(Math.max(0, ms))])

/**
 * Bu sekmenin uçuştaki BÜTÜN çıkış isteklerini iptal eder; yanıtları artık uygulanmaz. İptalin işlenmesini en çok `ms` bekler.
 */
export async function abortInflightLogouts(ms = 500) {
  const pending = []
  for (const e of inflight.values()) {
    try { e.controller?.abort() } catch { /* yoksay */ }
    pending.push(e.done)
  }
  if (pending.length) await within(Promise.all(pending), ms)
}

/**
 * Sunucu çıkışını SÜRE SINIRLI dener; ASLA reddetmez. `{ ok, late }`: `ok` süre içinde onaylandı mı; `late` isteğin
 * kendisinin sonucu (süre dolduktan sonra da biter — çağıran işareti o zaman silebilir).
 */
export async function tryServerLogout(logoutFn, timeoutMs = LOGOUT_TIMEOUT_MS, { abortMs = LOGOUT_ABORT_MS, respectLoginHold = false } = {}) {
  // Duyuru ÖNCE yazılır, giriş işareti SONRA okunur (giriş tarafı tersini yapar) — iki sekme de birbirini görür (D1)
  const id = newId()
  const key = LOGOUT_INFLIGHT_PREFIX + id
  try { store()?.setItem(key, String(Date.now() + abortMs)) } catch { /* depolama kapalı */ }
  const unannounce = () => { try { store()?.removeItem(key) } catch { /* yoksay */ } }
  if (respectLoginHold && loginHoldActive()) {
    unannounce()
    return { ok: false, late: Promise.resolve(false), skipped: true }
  }
  const controller = typeof AbortController !== 'undefined' ? new AbortController() : null
  const abortTimer = controller ? setTimeout(() => controller.abort(), abortMs) : null
  let late
  try {
    // İptal edilen isteğin (sonradan) çözülen sonucu onay SAYILMAZ — tarayıcı o yanıtı uygulamadı
    late = Promise.resolve().then(() => logoutFn(controller?.signal))
      .then((res) => (controller?.signal.aborted ? false : logoutConfirmed(res)), () => false)
  } catch {
    late = Promise.resolve(false)
  }
  const done = late.then(() => {}, () => {}).then(() => {
    if (abortTimer) clearTimeout(abortTimer)
    inflight.delete(id)
    unannounce()
  })
  inflight.set(id, { controller, done })
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
export async function settlePendingLogout(logoutFn, timeoutMs = LOGOUT_TIMEOUT_MS, opts = {}) {
  const token = pendingLogoutToken()
  if (!token) return true
  const { ok, late } = await tryServerLogout(logoutFn, timeoutMs, opts)
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
      // Süren bir giriş varken (bu ya da öteki sekme) yeni deneme başlamaz — yanıtı yeni oturumun çerezini silebilirdi (D1)
      if (await settlePendingLogout(logoutFn, timeoutMs, { respectLoginHold: true })) stop()
    } finally {
      busy = false
    }
  }
  if (!isLogoutPending()) return () => {}
  timer = setInterval(attempt, intervalMs)
  try { window.addEventListener('online', attempt) } catch { /* yok */ }
  return stop
}

/**
 * Giriş isteğinden ÖNCE (2026-10-22, D1): bekleyen / uçuştaki çıkışların yanıtı yeni girişin çerezlerini silemesin.
 *   1. bu sekmenin uçuştaki çıkış istekleri `budgetMs` içinde bitmeye bırakılır;
 *   2. bekleyen çıkış işareti varsa bir deneme (kalan süreyle sınırlı, o sürede iptal edilir);
 *   3. hâlâ uçuşta olan her istek İPTAL edilir — yanıtı artık uygulanmaz;
 *   4. öteki sekmelerin duyurduğu uçuştaki çıkışlar bitene kadar beklenir (her biri en geç kendi `LOGOUT_ABORT_MS`'inde
 *      kendini iptal eder; bekleme bununla sınırlı).
 * Hiçbir şey yoksa hemen döner (girişi geciktirmez). ASLA reddetmez.
 * @param {(signal?: AbortSignal) => Promise<any>} logoutFn
 */
export async function settleLogoutsBeforeLogin(logoutFn, budgetMs = LOGIN_LOGOUT_WAIT_MS) {
  try {
    const deadline = Date.now() + budgetMs
    const left = () => Math.max(0, deadline - Date.now())
    if (inflight.size) await within(Promise.all([...inflight.values()].map((e) => e.done)), left())
    if (isLogoutPending() && left() > 0) {
      const ms = left()
      await settlePendingLogout(logoutFn, ms, { abortMs: ms })
    }
    await abortInflightLogouts()
    const own = new Set([...inflight.keys()].map((id) => LOGOUT_INFLIGHT_PREFIX + id))
    const cap = Date.now() + LOGOUT_ABORT_MS + 500
    for (let foreign = liveKeys(LOGOUT_INFLIGHT_PREFIX, own); foreign.length && Date.now() < cap; foreign = liveKeys(LOGOUT_INFLIGHT_PREFIX, own)) {
      const until = Math.min(cap, Math.max(...foreign.map((e) => e.until)))
      await sleep(Math.min(100, Math.max(10, until - Date.now())))
    }
  } catch { /* giriş asla bu yüzden düşmez */ }
}
