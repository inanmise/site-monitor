/**
 * Kopyalanan izlemenin adını türetir — "Kopyala" (duplicate) akışında kullanılır.
 *
 *   "Web Sunucu 1"           → "Web Sunucu 1 (Kopya)"
 *   "Web Sunucu 1 (Kopya)"   → "Web Sunucu 1 (Kopya 2)"
 *   "Web Sunucu 1 (Kopya 2)" → "Web Sunucu 1 (Kopya 3)"
 *   ""/null                  → "(Kopya)"
 *
 * Sayaç sondaki mevcut " (Kopya[ n])" ekinden okunur; ek yoksa ilk kopya üretilir.
 * Dil-bağımsız tutuldu (TR etiket sabit) — ad DB'de saklanan teknik bir alandır, i18n'e girmez.
 */
const SUFFIX_RE = / \(Kopya(?: (\d+))?\)$/

export function duplicateName(name) {
  const base = (name ?? '').trim()
  if (!base) return '(Kopya)'
  const m = base.match(SUFFIX_RE)
  if (!m) return `${base} (Kopya)`
  const next = m[1] ? Number(m[1]) + 1 : 2
  return `${base.replace(SUFFIX_RE, '')} (Kopya ${next})`
}

export default duplicateName
