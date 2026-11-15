// Eksikler penceresini her yerden açma ve özetini paylaşma (2026-11-15). Bağımlılıksız küçük modül — kullanıcı menüsü ve
// Kişisel Panom kutusu ağır pencereyi / yükleyiciyi içe aktarmaz.
//
//   • openGapsDialog()            — kullanıcı menüsü "Eksiklerim" → App'teki GapsPrompt dinler (taze okuma + pencere)
//   • requestGapsRefresh()        — Panom kutusu "Denetle": pencere AÇILMADAN okuma (GapsPrompt yapar)
//   • publishGapsSummary(s)       — GapsPrompt her okumadan / gizlemeden sonra yayınlar (yalnız sayılar, ad / kimlik yok)
//   • subscribe / getGapsSummary  — useSyncExternalStore için; özet yoksa null (henüz denetlenmedi)
const EVENT = 'sm:open-gaps'
const REFRESH = 'sm:refresh-gaps'

export function openGapsDialog() {
  try { window.dispatchEvent(new CustomEvent(EVENT)) } catch { /* pencere yok */ }
}

export function onOpenGapsDialog(fn) {
  const h = () => fn()
  try { window.addEventListener(EVENT, h) } catch { return () => {} }
  return () => { try { window.removeEventListener(EVENT, h) } catch { /* yok */ } }
}

export function requestGapsRefresh() {
  try { window.dispatchEvent(new CustomEvent(REFRESH)) } catch { /* pencere yok */ }
}

export function onGapsRefreshRequest(fn) {
  const h = () => fn()
  try { window.addEventListener(REFRESH, h) } catch { return () => {} }
  return () => { try { window.removeEventListener(REFRESH, h) } catch { /* yok */ } }
}

/**
 * Yayınlanan özet: `{ status: 'loading'|'ready', at, total, high, byCategory: {kategori: sayı}, unknown: boolean,
 * promptable: boolean, complete: boolean }` — `unknown` = bir kaynak okunamadı (sayılar eksik olabilir), `complete` = her şey
 * okundu ve gerçekten eksik yok (yalnız o zaman "tamam" denebilir), `promptable` = "gösterme" kararından sonra yeni eksik var.
 */
let summary = null
const listeners = new Set()

export function publishGapsSummary(next) {
  summary = next && typeof next === 'object' ? Object.freeze({ ...next }) : null
  for (const fn of [...listeners]) {
    try { fn() } catch { /* dinleyici hatası diğerlerini durdurmasın */ }
  }
}

export function getGapsSummary() { return summary }

export function subscribeGapsSummary(fn) {
  listeners.add(fn)
  return () => { listeners.delete(fn) }
}
