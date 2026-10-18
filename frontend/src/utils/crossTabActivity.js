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
 */

export const ACTIVITY_PREFIX = 'sm.activity.'
export const ACTIVITY_THROTTLE_MS = 15_000
/** Kendi sekmesine "kullanıcı etkin" sinyali (uygulama içi eylem: belge olayı üretmeyen işler). */
export const ACTIVITY_EVENT = 'sm:activity'
/** Bundan eski damgalar silinir (kapanmış sekmeler). */
const STALE_MS = 24 * 60 * 60_000

const TAB_ID = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`
const OWN_KEY = ACTIVITY_PREFIX + TAB_ID
let lastWrite = 0

const store = () => {
  try { return globalThis.localStorage || null } catch { return null }
}

/** Bu sekmenin anahtarı (testler). */
export const ownActivityKey = () => OWN_KEY

/** Kendi etkinliğini yazar (en çok 15 sn'de bir; eski sekme damgaları temizlenir). `force`: kısma yok. */
export function writeActivityStamp(now = Date.now(), force = false) {
  if (!force && now - lastWrite < ACTIVITY_THROTTLE_MS) return false
  const ls = store()
  if (!ls) return false
  try {
    ls.setItem(OWN_KEY, String(now))
    lastWrite = now
  } catch {
    return false
  }
  try {
    for (let i = ls.length - 1; i >= 0; i--) {
      const k = ls.key(i)
      if (!k || !k.startsWith(ACTIVITY_PREFIX) || k === OWN_KEY) continue
      const v = Number(ls.getItem(k))
      if (!Number.isFinite(v) || now - v > STALE_MS) ls.removeItem(k)
    }
  } catch { /* yok */ }
  return true
}

/**
 * Öteki sekmelerin en yeni etkinliği (ms, kısma payı eklenmiş, `now`'u geçmez); yoksa 0. Kendi damgası sayılmaz.
 */
export function latestOtherActivity(now = Date.now()) {
  const ls = store()
  if (!ls) return 0
  let best = 0
  try {
    for (let i = 0; i < ls.length; i++) {
      const k = ls.key(i)
      if (!k || !k.startsWith(ACTIVITY_PREFIX) || k === OWN_KEY) continue
      const v = Number(ls.getItem(k))
      if (Number.isFinite(v) && v > best) best = v
    }
  } catch {
    return 0
  }
  return best > 0 ? Math.min(now, best + ACTIVITY_THROTTLE_MS) : 0
}

/** Öteki sekme etkinlik yazınca çağrılır; dönen işlev aboneliği kaldırır. */
export function onOtherActivity(cb) {
  const h = (e) => {
    const k = e?.key
    if (!k || !k.startsWith(ACTIVITY_PREFIX) || k === OWN_KEY || !e.newValue) return
    cb(Number(e.newValue))
  }
  try { globalThis.addEventListener?.('storage', h) } catch { /* yok */ }
  return () => { try { globalThis.removeEventListener?.('storage', h) } catch { /* yok */ } }
}

/** Uygulama içi eylem (ekrandan alma, yapıştırma, yükleme): kendi sekmesinin hareketsizlik sayacı yeniden kurulur. */
export function markActivity() {
  try { globalThis.dispatchEvent?.(new Event(ACTIVITY_EVENT)) } catch { /* yok */ }
}

/** Testler: kısma sayacını sıfırlar. */
export function resetActivityThrottleForTests() {
  lastWrite = 0
}
