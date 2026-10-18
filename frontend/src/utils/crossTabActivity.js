import { storageOwner } from './personalStorage.js'

/**
 * SEKMELER ARASI ETKİNLİK DAMGASI (2026-10-18, Görsel paneli): hareketsizlik çıkışı (App.jsx) eskiden yalnız KENDİ
 * belgesindeki olayları dinliyordu — kullanıcı ayrı görsel penceresinde çalışırken ana pencere paylaşılan oturumu
 * kapatırdı. Her sekme kendi etkinliğini `sm.activity.<sekme>` anahtarına en çok 15 sn'de bir yazar; bir sekme uyarı
 * göstermeden / çıkış yapmadan önce öteki sekmelerin en yeni damgasına bakar ve öteki sekme pencere içinde etkinse
 * sayacı yeniden kurar. `storage` olayı öteki sekmeye anında ulaşır (uyarı şeridi kalkar).
 *
 * <p>Damga en çok `ACTIVITY_THROTTLE_MS` eski olabilir: okunurken bu pay eklenir (öteki sekme son yazımdan sonra 15 sn
 * daha etkin olmuş olabilir; erken çıkış yapılmaz), ama "şimdi"yi geçmez. Tek sekmede davranış birebir aynıdır (kendi
 * damgası okunmaz). Uygulama içi eylemler (ekrandan alma, yapıştırma, yükleme) `markActivity()` ile kendi sekmesinin
 * sayacını da kurar.
 *
 * <p><b>İnceleme 2026-10-18:</b>
 * <ul>
 *   <li><b>Saat ileride / geri:</b> "şimdi + 5 sn"den ileri damga (çift açılışta UTC / yerel saat, sanal makine geri
 *   yükleme, NTP düzeltmesi) yok sayılır VE silinir — eskiden "şimdi" sayılıyordu ve o tarayıcı profilindeki her sekme
 *   saatlerce hareketsizlik çıkışı yapmıyordu. Saat geri gidince (`now < lastWrite`) kısma sayacı sıfırlanır; kendi
 *   damgası yeniden yazılır.</li>
 *   <li><b>Kullanıcıya bağlı:</b> damga `{u, t}` taşır (`u` = kullanıcı adının özeti, ad yazılmaz). Yalnız AYNI
 *   kullanıcının damgası sayılır ve tarayıcıdaki SON giriş (`sm.storage.owner`) bu sekmenin kullanıcısı değilse hiçbir
 *   öteki damga sayılmaz (o sekmenin oturumu zaten bitti; sonraki isteği 401 alır). Çıkış / oturum sıfırlama
 *   (`App.resetSessionData`) kendi damgasını siler ve sekmeyi kullanıcıdan ayırır (`clearOwnActivityStamp`); sonraki
 *   giriş `bindActivityUser` ile yeniden bağlar.</li>
 * </ul>
 */

export const ACTIVITY_PREFIX = 'sm.activity.'
export const ACTIVITY_THROTTLE_MS = 15_000
/** Bundan ileri tarihli damga (saat oynadı) yok sayılır ve silinir. */
export const FUTURE_SLACK_MS = 5_000
/** Kendi sekmesine "kullanıcı etkin" sinyali (uygulama içi eylem: belge olayı üretmeyen işler). */
export const ACTIVITY_EVENT = 'sm:activity'
/** Bundan eski damgalar silinir (kapanmış sekmeler). */
const STALE_MS = 24 * 60 * 60_000

const TAB_ID = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`
const OWN_KEY = ACTIVITY_PREFIX + TAB_ID
let lastWrite = 0
let boundUser = ''

const store = () => {
  try { return globalThis.localStorage || null } catch { return null }
}

/**
 * Kullanıcı adının damgada taşınan özeti (ad tarayıcı deposuna damga olarak yazılmaz). 53 bitlik karışım — gizlilik
 * değil ayırt etme amaçlı: aynı ad aynı özeti verir, farklı adlar pratikte çakışmaz.
 */
export function activityUserKey(username) {
  const s = String(username ?? '').trim()
  if (!s) return ''
  let h1 = 0xdeadbeef
  let h2 = 0x41c6ce57
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    h1 = Math.imul(h1 ^ c, 2654435761)
    h2 = Math.imul(h2 ^ c, 1597334677)
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909)
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909)
  return `u${(4294967296 * (2097151 & h2) + (h1 >>> 0)).toString(36)}`
}

/** Damga metnini çözer: `{u, t}` (geçerli) ya da null (bozuk / eski biçim). */
export function parseActivityStamp(raw) {
  if (typeof raw !== 'string' || !raw || raw.length > 200) return null
  try {
    const v = JSON.parse(raw)
    const t = Number(v?.t)
    const u = typeof v?.u === 'string' ? v.u : ''
    return u && Number.isFinite(t) && t > 0 ? { u, t } : null
  } catch {
    return null
  }
}

/** Bu sekmenin anahtarı (testler). */
export const ownActivityKey = () => OWN_KEY

/** Bu sekmenin bağlı olduğu kullanıcı özeti ('' = oturum yok). */
export const boundActivityUser = () => boundUser

/** Sekmeyi oturumdaki kullanıcıya bağlar (App hareketsizlik etkisi, her girişte); kısma sayacı sıfırlanır. */
export function bindActivityUser(username) {
  const u = activityUserKey(username)
  if (u !== boundUser) lastWrite = 0
  boundUser = u
}

/** Çıkış / oturum sıfırlama: kendi damgası silinir, sekme kullanıcıdan ayrılır (sonraki giriş yeniden bağlar). */
export function clearOwnActivityStamp() {
  boundUser = ''
  lastWrite = 0
  try { store()?.removeItem(OWN_KEY) } catch { /* depolama yok */ }
}

/** Tarayıcıdaki son girişin kullanıcısı bu sekmeninki mi? Değilse (ya da çıkış yapıldıysa) öteki damgalar sayılmaz. */
function newestLoginIsMine() {
  if (!boundUser) return false
  const owner = storageOwner()
  return !!owner && activityUserKey(owner) === boundUser
}

/** Öteki sekmelerin damgalarını gezer; bozuk, bir günden eski ve "şimdi + 5 sn"den ileri olanlar silinir. */
function scanOthers(ls, now, visit) {
  const doomed = []
  for (let i = 0; i < ls.length; i++) {
    const k = ls.key(i)
    if (!k || !k.startsWith(ACTIVITY_PREFIX) || k === OWN_KEY) continue
    const s = parseActivityStamp(ls.getItem(k))
    if (!s || now - s.t > STALE_MS || s.t > now + FUTURE_SLACK_MS) { doomed.push(k); continue }
    visit?.(s)
  }
  for (const k of doomed) {
    try { ls.removeItem(k) } catch { /* yok */ }
  }
}

/** Kendi etkinliğini yazar (en çok 15 sn'de bir; geçersiz damgalar temizlenir). `force`: kısma yok. Oturum yoksa yazmaz. */
export function writeActivityStamp(now = Date.now(), force = false) {
  if (!boundUser) return false
  // Saat geri gitti: son yazım "gelecekte" kalır, kısma bir daha hiç yazdırmazdı
  if (now < lastWrite) lastWrite = 0
  if (!force && lastWrite && now - lastWrite < ACTIVITY_THROTTLE_MS) return false
  const ls = store()
  if (!ls) return false
  try {
    ls.setItem(OWN_KEY, JSON.stringify({ u: boundUser, t: now }))
    lastWrite = now
  } catch {
    return false
  }
  try { scanOthers(ls, now) } catch { /* yok */ }
  return true
}

/**
 * AYNI kullanıcının öteki sekmelerindeki en yeni etkinlik (ms, kısma payı eklenmiş, `now`'u geçmez); yoksa 0. Kendi
 * damgası, başka kullanıcının damgası ve ileri tarihli damga sayılmaz; tarayıcıdaki son giriş başka kullanıcınınsa 0.
 */
export function latestOtherActivity(now = Date.now()) {
  const ls = store()
  if (!ls || !newestLoginIsMine()) return 0
  let best = 0
  try {
    scanOthers(ls, now, (s) => { if (s.u === boundUser && s.t > best) best = s.t })
  } catch {
    return 0
  }
  return best > 0 ? Math.min(now, best + ACTIVITY_THROTTLE_MS) : 0
}

/** Aynı kullanıcının öteki sekmesi etkinlik yazınca çağrılır (geçerli damga, son giriş bu kullanıcı); dönen işlev kaldırır. */
export function onOtherActivity(cb) {
  const h = (e) => {
    const k = e?.key
    if (!k || !k.startsWith(ACTIVITY_PREFIX) || k === OWN_KEY || !e.newValue) return
    const s = parseActivityStamp(e.newValue)
    if (!s || s.u !== boundUser || s.t > Date.now() + FUTURE_SLACK_MS || !newestLoginIsMine()) return
    cb(s.t)
  }
  try { globalThis.addEventListener?.('storage', h) } catch { /* yok */ }
  return () => { try { globalThis.removeEventListener?.('storage', h) } catch { /* yok */ } }
}

/** Fare hareketinin etkinlik sayılması için işaretçinin en az bu kadar (ekran pikseli) yer değiştirmesi gerekir. */
export const POINTER_MOVE_MIN_PX = 4

/**
 * Gerçek fare hareketi süzgeci (3. inceleme turu 2026-10-18): tarayıcı imleç dururken de `mousemove` üretir (içerik imlecin
 * altında değişince, kaydırma / yerleşim sonrası) — bu olaylar AYNI ekran koordinatını taşır. Dönen işlev bir olay için
 * yalnız işaretçi son SAYILAN konumdan en az `minPx` uzaklaştıysa true döner (yavaş gerçek hareket birikerek sayılır);
 * ilk olay yalnız başlangıç konumunu alır, koordinatsız olay sayılmaz.
 */
export function createPointerMoveFilter(minPx = POINTER_MOVE_MIN_PX) {
  let last = null
  return (e) => {
    const x = Number(e?.screenX)
    const y = Number(e?.screenY)
    if (!Number.isFinite(x) || !Number.isFinite(y)) return false
    if (!last) { last = { x, y }; return false }
    if (Math.hypot(x - last.x, y - last.y) < minPx) return false
    last = { x, y }
    return true
  }
}

/** Uygulama içi eylem (ekrandan alma, yapıştırma, yükleme): kendi sekmesinin hareketsizlik sayacı yeniden kurulur. */
export function markActivity() {
  try { globalThis.dispatchEvent?.(new Event(ACTIVITY_EVENT)) } catch { /* yok */ }
}

/** Testler: kısma sayacını ve kullanıcı bağını sıfırlar. */
export function resetActivityThrottleForTests() {
  lastWrite = 0
  boundUser = ''
}
