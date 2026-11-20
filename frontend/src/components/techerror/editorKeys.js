/**
 * Rapor düzenleyicisinin bölüm gezintisi (2026-11-20, A4 — Alt+↓ / Alt+↑). Saf DOM yardımcısı: hiçbir bölümü açmaz, yalnız
 * hedef başlık düğmesini döndürür (çağıran odaklar).
 */
const TOGGLE = '[data-slot="te-section-toggle"]'

function shown(el) {
  try {
    if (el.closest('[hidden],[inert],[aria-hidden="true"]')) return false
    return !el.disabled
  } catch { return false }
}

/**
 * Odaktaki öğeye göre bir sonraki (`dir` +1) ya da önceki (−1) bölümün başlık düğmesi. Odak bir bölüm kartının içindeyse o
 * kartın komşusu; kartların dışındaysa (başlık, eylem çubuğu) belge sırasına göre ilk sonraki / son önceki düğme. Yoksa null.
 */
export function sectionToggleStep(root, active, dir) {
  if (!root || typeof root.querySelectorAll !== 'function' || !(dir === 1 || dir === -1)) return null
  const toggles = [...root.querySelectorAll(TOGGLE)].filter(shown)
  if (!toggles.length) return null
  const card = active && typeof active.closest === 'function' ? active.closest('[data-section]') : null
  if (card && root.contains(card)) {
    const idx = toggles.findIndex((t) => t.closest('[data-section]') === card)
    if (idx >= 0) return toggles[idx + dir] || null
  }
  // Kart dışı odak: belge sırası (odak kökün dışında / gövdede ise uçlar)
  if (!active || active === root.ownerDocument?.body || typeof active.compareDocumentPosition !== 'function' || !root.contains(active)) {
    return dir > 0 ? toggles[0] : toggles[toggles.length - 1]
  }
  const PRECEDING = 2 // Node.DOCUMENT_POSITION_PRECEDING
  const FOLLOWING = 4 // Node.DOCUMENT_POSITION_FOLLOWING
  if (dir > 0) return toggles.find((t) => t !== active && (active.compareDocumentPosition(t) & FOLLOWING) !== 0) || null
  const before = toggles.filter((t) => t !== active && (active.compareDocumentPosition(t) & PRECEDING) !== 0 && !t.contains(active))
  return before[before.length - 1] || null
}

/**
 * Ctrl/⌘+S mi (inceleme 2026-11-20)? Alt / Shift ile değil. Harf `e.key`'den okunur; Latin olmayan düzende (Rusça, Yunanca …)
 * `e.key` başka bir harf olduğundan fiziksel S tuşu (`e.code === 'KeyS'`) da sayılır — ama `e.key` LATİN bir harfse (ör. Dvorak'ta
 * fiziksel S = "o") o harf kazanır: Ctrl+O kayıt yapmaz.
 */
export function isSaveShortcut(e) {
  if (!e || !(e.ctrlKey || e.metaKey) || e.altKey || e.shiftKey) return false
  const key = typeof e.key === 'string' ? e.key : ''
  if (key === 's' || key === 'S') return true
  return e.code === 'KeyS' && !/^[a-z]$/i.test(key)
}
