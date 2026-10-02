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

/**
 * Oturumluk arayüz dili zorlaması — YALNIZ açılışta İngilizce sözlük indirilemediğinde kurulur
 * (i18n/index.jsx LangProvider, 2026-10-02 öneri 22). O durumda arayüz Türkçe açılır ama saklı tercih 'en'
 * kalır (geçici bir ağ hatası kullanıcının tercihini kalıcı değiştirmesin). `api/client.js` X-Lang'i önce
 * buradan okur ki sunucu iletileri arayüzle aynı dilde kalsın (QA ISSUE-001 sınıfı). null = zorlama yok;
 * başarılı her dil geçişi temizler. Normal akışta HİÇ kurulmaz → X-Lang eskisi gibi saklı tercihten gelir.
 */
let sessionLang = null

export function setSessionLang(lang) {
  sessionLang = lang || null
}

export function sessionLangOverride() {
  return sessionLang
}

/**
 * Yüzde biçimi (QA 2026-09-12, ISSUE-001/007/010): Türkçe "%100", İngilizce "100%".
 * `%${v}` şablonu İngilizce arayüzde Türkçe sırayı sızdırıyordu (Statistics, SMTP, gürültü tablosu…).
 * null/undefined/'' → tire.
 */
export function formatPercent(v, dash = '—') {
  if (v === null || v === undefined || v === '') return dash
  return dateLocale() === 'tr-TR' ? `%${v}` : `${v}%`
}

/**
 * Oran metni (başarı oranı / erişilebilirlik kutucukları) — 2 ondalık, YEREL ondalık ayırıcı + yerel yüzde sırası:
 * TR "%97,50", EN "97.50%"; 99,995 ve üstü "100". Sayı değilse tire. (E5, 2026-09-28e: `${r.toFixed(2)}%` TR arayüzde
 * "97.50%" yazıyordu; HTTP ekranlarının `fmtPct` deseni.)
 */
export function formatRatePercent(r, dash = '—') {
  if (r === null || r === undefined || r === '' || !Number.isFinite(Number(r))) return dash
  const v = Number(r)
  return formatPercent(v >= 99.995 ? '100' : v.toLocaleString(dateLocale(), { minimumFractionDigits: 2, maximumFractionDigits: 2 }))
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
