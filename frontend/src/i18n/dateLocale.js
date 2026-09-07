/**
 * Tarih biçimlendirme yereli — TEK KAYNAK.
 *
 * <p>Neden ayrı bir modül: `api/client.js` içindeki `formatDate*` yardımcıları düz fonksiyon,
 * hook değil; `useDateLocale()`'i çağıramazlar. Sabit `'tr-TR'` yazdıkları için İngilizce
 * arayüzde AYNI EKRANDA iki farklı tarih biçimi görünüyordu: yereli bilen bileşenler
 * `21/09/2026`, `formatDate` kullananlar `21.09.2026`.
 *
 * <p>Çözüm neden burada: `client.js`'in 11 bin satırlık `i18n/index.jsx`'i import etmesi hem
 * ağır hem de katman olarak ters (API katmanı sözlüklere bağlanır). Bu küçük modülü İKİSİ de
 * import ediyor, döngü oluşmuyor.
 *
 * <p>Dil React state'inde yaşıyor; burada bir ayna tutuluyor ve `LangProvider` her değişimde
 * tazeliyor. Ayna henüz kurulmadıysa (ilk render, provider dışı çağrı, test) localStorage'dan
 * okunur — `i18n/index.jsx#storedLang` ile AYNI anahtar ve AYNI varsayılan ('en').
 */

/** Dil tercihinin localStorage anahtarı. i18n/index.jsx bunu buradan alır — iki yerde yazılmaz. */
export const LANG_STORAGE_KEY = 'site-monitor-lang'

/** Dil kodu → Intl yereli. Tek yerde tanımlı ki useDateLocale ile formatDate ayrışmasın. */
export function localeFor(lang) {
  return lang === 'en' ? 'en-GB' : 'tr-TR'
}

let mirrored = null

/** LangProvider tarafından çağrılır; düz fonksiyonların güncel dili görmesini sağlar. */
export function setDateLocale(lang) {
  mirrored = localeFor(lang)
}

/** Güncel Intl yereli. Ayna yoksa localStorage'a düşer (i18n ile aynı varsayılan: 'en'). */
export function dateLocale() {
  if (mirrored) return mirrored
  try {
    return localeFor(localStorage.getItem(LANG_STORAGE_KEY) || 'en')
  } catch {
    return localeFor('en')
  }
}
