/**
 * TV / duvar ekranı kipi bayrağı (2026-10-11, Panom). TV kipi AÇIKKEN uygulamanın hareketsizlik zamanlayıcısı
 * (App.jsx) uyarı göstermez ve oturumu kapatmaz: duvar ekranında kimse fareye dokunmaz. Kip kapanınca (Esc, "Çık",
 * sekme değişimi) sayaç olağan haliyle yeniden başlar. Sunucu tarafı oturum kuralları (tek oturum, pasif hesap, bakım,
 * yönetici oturum sonlandırma) DEĞİŞMEZ — bu bayrak yalnız istemcideki hareketsizlik çıkışını erteler.
 *
 * <p>Modül düzeyinde tek değer + abone listesi; React dışı okuma (`isTvModeActive`) zamanlayıcı geri çağrılarında.
 */
let active = false
const listeners = new Set()

export function isTvModeActive() { return active }

export function setTvModeActive(on) {
  const next = !!on
  if (next === active) return
  active = next
  for (const fn of [...listeners]) {
    try { fn(active) } catch { /* dinleyici hatası diğerlerini durdurmaz */ }
  }
}

/** Değişimde çağrılır; dönen işlev aboneliği kaldırır. */
export function onTvModeChange(fn) {
  listeners.add(fn)
  return () => { listeners.delete(fn) }
}
