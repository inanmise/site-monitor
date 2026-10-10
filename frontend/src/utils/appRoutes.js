/**
 * Uygulama adresleri — TEK kaynak (2026-10-08, markalı 404).
 *
 * SiteMonitor tek sayfalık bir uygulamadır: yalnız `/` (ve `/index.html`) kabuğu açar, sayfalar `?tab=<anahtar>` ile
 * seçilir. Sunucu bilinmeyen bir yola (`/foo`) da aynı kabuğu HTTP 404 ile döndürür (SpaNotFoundAdvice); ön yüz
 * `main.jsx`'te yolu burada sorar ve tanımıyorsa oturum açılışını hiç beklemeden markalı 404 sayfasını çizer.
 * Oturum açıkken bilinmeyen bir `?tab=` ise uygulama içi "Sayfa bulunamadı" panelini açar ({@link NOT_FOUND_TAB}).
 */

/** Bilinen sekme anahtarları (App.jsx gezinmesi, e-posta derin bağlantıları, palet, kayıtlı görünümler). */
export const VALID_TABS = new Set([
  'dashboard', 'all', 'domains', 'manualcerts', 'forecast', 'renewal', 'renewal-guide',
  'warnings', 'incidents', 'maintenance', 'alerthistory', 'noc', 'stats', 'weakalgo', 'dataquality', 'weeklyreports', 'incident-history', 'executive',
  'health', 'uptime', 'monitoring', 'status', 'storms', 'http', 'domain', 'port', 'dns', 'keyword', 'ping', 'page', 'pagespeed', 'scripted', 'activity', 'myactivity', 'system', 'monitorchanges',
  'admin', 'permissions', 'sqlplayground', 'login-issues', 'help', 'settings',
])

/**
 * Adresteki `?tab=` tanınmadığında App'in iç sekme değeri. VALID_TABS'ta YOK: hiçbir sekme içeriği çizilmez, kenar
 * çubuğunda hiçbir öğe seçili görünmez; yerine uygulama içi "Sayfa bulunamadı" paneli gelir.
 */
export const NOT_FOUND_TAB = '__not-found__'

/** Kabuğu açan yollar. Başka her yol (ör. `/foo`, `/x/y`) markalı 404 sayfasıdır. */
const APP_PATHS = new Set(['/', '/index.html'])

/** Yol uygulamanın kendi kabuğu mu? Sondaki eğik çizgi(ler) yok sayılır (`/index.html/` değil, `//` → `/`). */
export function isKnownAppPath(pathname) {
  const p = String(pathname ?? '/').replace(/\/+$/, '') || '/'
  return APP_PATHS.has(p)
}

/**
 * Adresteki sekme isteği: geçerli anahtar → kendisi; anahtar var ama tanınmıyor → {@link NOT_FOUND_TAB};
 * anahtar yok/boş → null (çağıran Pano'ya ya da açılış sekmesi tercihine düşer).
 */
export function tabFromSearch(search) {
  try {
    const t = new URLSearchParams(search ?? '').get('tab')
    if (t == null || t.trim() === '') return null
    return VALID_TABS.has(t) ? t : NOT_FOUND_TAB
  } catch {
    return null
  }
}

/** Adresteki ham `tab` değeri (panelde gösterilir) — en çok 80 karakter. */
export function requestedTabParam(search) {
  try {
    const t = new URLSearchParams(search ?? '').get('tab') ?? ''
    return t.length > 80 ? `${t.slice(0, 80)}…` : t
  } catch {
    return ''
  }
}
