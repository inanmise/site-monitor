// Eksikler penceresini her yerden açma (2026-11-15): kullanıcı menüsü "Eksiklerim" → App'teki GapsPrompt dinler.
// Bağımlılıksız küçük modül — menü ağır pencereyi içe aktarmaz.
const EVENT = 'sm:open-gaps'

export function openGapsDialog() {
  try { window.dispatchEvent(new CustomEvent(EVENT)) } catch { /* pencere yok */ }
}

export function onOpenGapsDialog(fn) {
  const h = () => fn()
  try { window.addEventListener(EVENT, h) } catch { return () => {} }
  return () => { try { window.removeEventListener(EVENT, h) } catch { /* yok */ } }
}
