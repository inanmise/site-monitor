/**
 * Klavye odak tuzağı (2026-11-20, "Hızlı Giriş"): Tab / Shift+Tab bir kabın odaklanabilir öğeleri arasında döner, arkadaki
 * sayfaya geçmez. Radix `modal={false}` Sheet'ler (olay ayrıntısı) kendi tuzaklarını kurmaz — üstlerinde açılan pencereler
 * tıklanabilir kalsın diye — bu yardımcı onların yerine geçer.
 *
 * <p>Yalnız olayın HEDEFİ kabın içindeyse çalışır: React olayları portal'dan da kabarır, kabın içinden açılmış ayrı bir
 * pencerenin (iç içe diyalog, açılır pencere) Tab'ı o pencerede kalır. Gizli (`hidden`, `aria-hidden`, `inert`) ve kapalı
 * öğeler sayılmaz.
 */

const FOCUSABLE = [
  'a[href]', 'button:not([disabled])', 'input:not([disabled]):not([type="hidden"])', 'select:not([disabled])',
  'textarea:not([disabled])', '[contenteditable="true"]', '[tabindex]:not([tabindex="-1"])',
].join(',')

/** Kabın içindeki, sırası Tab ile gelen ve görünür sayılan öğeler. */
export function focusablesIn(root) {
  if (!root || typeof root.querySelectorAll !== 'function') return []
  return [...root.querySelectorAll(FOCUSABLE)].filter((el) => !el.closest('[hidden],[aria-hidden="true"],[inert]')
    && el.getAttribute('tabindex') !== '-1')
}

/**
 * Tab tuşu olayını kabın içinde tutar. Tuş Tab değilse, değiştirici (Ctrl / Alt / Meta) basılıysa ya da hedef kabın dışındaysa
 * hiçbir şey yapmaz ve `false` döner; odağı kendisi taşıdığında `true`.
 */
export function trapTab(e, root) {
  if (!e || e.key !== 'Tab' || e.ctrlKey || e.altKey || e.metaKey || !root) return false
  const target = e.target
  if (!target || typeof root.contains !== 'function' || !root.contains(target)) return false
  const items = focusablesIn(root)
  const doc = root.ownerDocument || (typeof document !== 'undefined' ? document : null)
  const active = doc ? doc.activeElement : null
  if (items.length === 0) {
    e.preventDefault()
    if (typeof root.focus === 'function') root.focus()
    return true
  }
  const first = items[0]
  const last = items[items.length - 1]
  if (e.shiftKey && (active === first || active === root || !root.contains(active))) {
    e.preventDefault(); last.focus(); return true
  }
  if (!e.shiftKey && (active === last || !root.contains(active))) {
    e.preventDefault(); first.focus(); return true
  }
  return false
}
