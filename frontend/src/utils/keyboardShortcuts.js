/**
 * Genel klavye kısayolları (2026-10-02, öneri 24) — saf model; bileşen `components/KeyboardShortcuts.jsx`.
 *
 * Yalnız EKLEME: mevcut kısayollar (Ctrl/⌘+K palet, Ctrl/⌘+B kenar çubuğu, Ctrl/⌘+Enter gönder, Esc) aynen kalır.
 * Yeni kısayollar değiştirici tuşsuzdur ve yalnız şu koşulda çalışır:
 *   - odak bir yazı alanında (input/textarea/select/contenteditable) ya da tuşları kendisi kullanan bir bileşende
 *     (menü, liste kutusu, açılır seçici, kaydırıcı, ızgara) DEĞİL — Radix Select tetiğinin harf araması bozulmaz;
 *   - açık bir pencere/çekmece/menü (`role=dialog|alertdialog|menu`) YOK — ürün turu balonu da `role=dialog`;
 *   - Ctrl/⌘/Alt basılı DEĞİL (tarayıcı ve işletim sistemi kısayollarıyla çakışmaz; AltGr = Ctrl+Alt da elenir);
 *   - olay başka bir işleyicide `preventDefault` edilmemiş ve IME bileşimi sürmüyor.
 * Escape ve Tab'a hiç dokunulmaz.
 *
 *   `?`        kısayol listesi (cheat sheet)
 *   `/`        sayfanın birincil arama kutusuna odak (`data-page-search`); sayfada yoksa HİÇBİR ŞEY yapmaz
 *              (preventDefault de yok → Firefox'un hızlı bulması gibi tarayıcı davranışı korunur)
 *   `g` + harf 1 sn içinde sekmeye git — yalnız kullanıcının açabildiği sekmeler (Nav'ın palet listesi)
 */

/** `g`'den sonra harfin beklendiği süre (ms). */
export const SEQUENCE_MS = 1000

/** Kısayol listesini açan pencere olayı (komut paleti ve kullanıcı menüsü yayınlar). */
export const SHORTCUTS_EVENT = 'sm:shortcuts'

/** Sayfanın birincil arama kutusunu işaretleyen öznitelik (`/` buna odaklanır). */
export const PAGE_SEARCH_ATTR = 'data-page-search'

/** `g` + harf → sekme kimliği. Sıra = kısayol listesindeki sıra. */
export const GO_SHORTCUTS = Object.freeze([
  Object.freeze({ key: 'd', tab: 'dashboard' }),
  Object.freeze({ key: 'm', tab: 'monitoring' }),
  Object.freeze({ key: 'a', tab: 'alerthistory' }),
  Object.freeze({ key: 's', tab: 'status' }),
  Object.freeze({ key: 'h', tab: 'http' }),
  Object.freeze({ key: 'i', tab: 'domains' }),
  Object.freeze({ key: 'c', tab: 'all' }),
])

/** Yalnız değiştirici tuşa basılması (Shift'i basılı tutmak) bekleyen `g` dizisini bozmaz. */
export const MODIFIER_KEYS = new Set(['Shift', 'Control', 'Alt', 'AltGraph', 'Meta', 'CapsLock', 'OS'])

// Tuşları kendisi kullanan bileşenler: yazı kutusu rolleri, menü / liste / açılır seçici, kaydırıcı, ızgara.
const KEY_OWNER_ROLES = [
  'textbox', 'searchbox', 'combobox', 'listbox', 'option', 'menu', 'menubar', 'menuitem', 'menuitemcheckbox',
  'menuitemradio', 'slider', 'spinbutton', 'grid', 'treegrid', 'tree',
].map((r) => `[role="${r}"]`).join(',')

/** Odak yazı alanında ya da tuşları kendisi işleyen bir bileşende mi? */
export function isTypingTarget(el) {
  if (!el || typeof el !== 'object') return false
  const tag = String(el.tagName || '').toUpperCase()
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  if (el.isContentEditable === true) return true
  if (typeof el.closest !== 'function') return false
  try {
    if (el.closest('[contenteditable]:not([contenteditable="false"])')) return true
    if (el.closest(KEY_OWNER_ROLES)) return true
  } catch { /* geçersiz seçici ortamı — yoksay */ }
  return false
}

/** Açık bir pencere / çekmece / açılır pencere / menü var mı (Radix bunları yalnız açıkken DOM'da tutar)? */
export function hasOpenOverlay(doc = typeof document !== 'undefined' ? document : null) {
  if (!doc || typeof doc.querySelector !== 'function') return false
  return !!doc.querySelector('[role="dialog"],[role="alertdialog"],[role="menu"]')
}

/** Genel (değiştiricisiz) kısayolun bu tuş olayında çalışmasına izin var mı? */
export function shortcutEligible(e, doc = typeof document !== 'undefined' ? document : null) {
  if (!e || e.defaultPrevented || e.isComposing || e.keyCode === 229) return false
  if (e.ctrlKey || e.metaKey || e.altKey) return false
  if (isTypingTarget(e.target)) return false
  if (doc && isTypingTarget(doc.activeElement)) return false
  if (hasOpenOverlay(doc)) return false
  return true
}

/** Tek karakterlik tuşun küçük harfi (Caps Lock açıkken de aynı kısayol); Türkçe 'İ' → 'i'. Değilse null. */
export function letterOf(e) {
  const k = e?.key
  if (typeof k !== 'string' || k.length !== 1) return null
  if (k === 'İ') return 'i'
  return k.toLowerCase()
}

/** `g` dizisinin ikinci tuşu → gidilecek sekme; harf eşleşmezse ya da sekme kullanıcıya açık değilse null. */
export function goTarget(letter, availableTabIds) {
  const hit = GO_SHORTCUTS.find((s) => s.key === letter)
  if (!hit) return null
  const ok = availableTabIds instanceof Set ? availableTabIds.has(hit.tab) : (availableTabIds || []).includes(hit.tab)
  return ok ? hit.tab : null
}

function isShown(el) {
  try {
    if (el.closest('[hidden],[inert],[aria-hidden="true"]')) return false
    if (typeof el.checkVisibility === 'function') return el.checkVisibility()
    return el.getClientRects().length > 0
  } catch { return false }
}

/** Sayfanın görünür, etkin birincil arama kutusu (yoksa null). Telefon/masaüstü çift çizimde görünen kazanır. */
export function findPageSearch(doc = typeof document !== 'undefined' ? document : null) {
  if (!doc || typeof doc.querySelectorAll !== 'function') return null
  for (const el of doc.querySelectorAll(`[${PAGE_SEARCH_ATTR}]`)) {
    if (el.disabled || el.readOnly) continue
    if (isShown(el)) return el
  }
  return null
}

/** `/`: arama kutusuna odaklanıp içeriğini seçer; kutu yoksa false (çağıran tuşu tüketmez). */
export function focusPageSearch(doc) {
  const el = findPageSearch(doc)
  if (!el) return false
  el.focus()
  try { el.select?.() } catch { /* select desteklenmeyen tür */ }
  return true
}

/** macOS / iOS: kısayol listesinde Ctrl yerine ⌘, Alt yerine ⌥ gösterilir (işleyiciler ikisini de kabul eder). */
export function isMacPlatform(nav = typeof navigator !== 'undefined' ? navigator : null) {
  const p = nav?.userAgentData?.platform || nav?.platform || ''
  return /mac|iphone|ipad|ipod/i.test(String(p))
}
