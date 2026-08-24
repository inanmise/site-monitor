/**
 * Uygulama sürümünün TEK kaynağı.
 *
 * <p>Sürüm eskiden yalnız derleme zamanında gömülüyordu: `vite.config.js` kök `VERSION` dosyasını
 * okuyup `__APP_VERSION__` define'ına yazıyor. Bu değer dev-server BAŞLARKEN bir kez çözülür ve
 * HMR onu yeniden okumaz — dolayısıyla bir sürüm yükseltmesinden sonra dev-server yeniden
 * başlatılmadıkça arayüz BAYAT sürüm gösteriyordu (kullanıcı v20.26.1 görürken depo v20.29.4'teydi,
 * üç sürüm geride). Artık doğruluk kaynağı ÇALIŞMA ANINDA sunucudan gelen `app_version`
 * (public `/api/branding`); gömülü değer yalnızca YEDEKTİR.
 *
 * <p>İki okuma biçimi var, çünkü iki farklı ihtiyaç var:
 *  - `useAppVersion()` — ekranda sürüm ÇİZEN bileşenler için (branding gelince yeniden render).
 *  - `currentVersion()` — React DIŞI / hata yüzeyleri için SENKRON okuma. ErrorBoundary ve hata
 *    bildirimi context'e bağlanamaz: provider yokken çökmemeleri gerekir (aynı gerekçe için bkz.
 *    `copyText`/`CopyButton`). Bunlar bir değeri BEKLEYEMEZ, o an ellerinde ne varsa onu yollar.
 */

/** Derleme zamanında gömülen yedek — sunucu okunamazsa/henüz gelmediyse kullanılır. */
export const BUILD_VERSION = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : ''

let runtimeVersion = ''

/** BrandingProvider sunucudan `app_version` alınca çağırır. Boş/anlamsız değer YOK SAYILIR. */
export function setRuntimeVersion(v) {
  if (typeof v === 'string' && v.trim() && v.trim() !== 'unknown') runtimeVersion = v.trim()
}

/** Senkron okuma: sunucudan gelen değer varsa o, yoksa gömülü yedek. Asla undefined dönmez. */
export function currentVersion() {
  return runtimeVersion || BUILD_VERSION
}

/** Testler arasında sızıntı olmasın diye sıfırlama (yalnız test kullanımı). */
export function resetRuntimeVersion() {
  runtimeVersion = ''
}
