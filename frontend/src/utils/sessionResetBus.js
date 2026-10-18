/**
 * Oturum sıfırlama veri yolu (2026-10-18): App.resetSessionData (çıkış, hareketsizlik çıkışı, oturum düşmesi) bunu
 * çağırır; tembel yüklenen modüller (ör. Kanal raporlarının ekran paylaşımı oturumu) kendi durumlarını kapatmak için
 * abone olur. App bu modülleri içe aktarmaz — açılış paketi büyümez; modül hiç yüklenmediyse kapatılacak şey de yoktur.
 */
const listeners = new Set()

/** Abone olur; dönen işlev aboneliği kaldırır. */
export function onSessionReset(fn) {
  listeners.add(fn)
  return () => { listeners.delete(fn) }
}

/** Oturum sıfırlandı: her abone çağrılır (biri hata verse de diğerleri çalışır). */
export function emitSessionReset() {
  for (const fn of [...listeners]) {
    try { fn() } catch { /* dinleyici hatası diğerlerini durdurmaz */ }
  }
}
