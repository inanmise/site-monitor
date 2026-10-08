/**
 * Sayfa başlığı + meta açıklaması (2026-10-08, kullanıcı isteği: "her sayfada meta başlığı ve açıklaması olsun").
 *
 * Metinler i18n'de: sekmeler `meta.tab.<sekme>.title|description`, sekme dışı ekranlar (giriş, oturum bildirimleri,
 * 404…) `meta.page.<ad>.title|description` — TR ve EN birlikte. Tarayıcı sekmesindeki başlık
 * "<Sayfa> · <marka>" biçimindedir; marka beyaz-etiket `tab_title` (yoksa `app_name`, o da yoksa "SiteMonitor").
 * Kapı: test/pageMeta-gate.test.js (her VALID_TABS anahtarında iki dilde başlık ≤ 60, açıklama 50–160 karakter).
 */

export const META_BRAND = 'SiteMonitor'
export const META_SEPARATOR = ' · '
/** Tam başlık ("<Sayfa> · SiteMonitor") üst sınırı — arama motoru/sekme kesme sınırı. */
export const META_TITLE_MAX = 60
export const META_DESCRIPTION_MIN = 50
export const META_DESCRIPTION_MAX = 160

/** Sekme dışı ekranlar. */
export const META_PAGES = Object.freeze([
  'loading', 'login', 'sessionExpired', 'accountInactive', 'maintenance', 'changePassword', 'notFound', 'restricted',
])

export const tabMetaKey = (tab) => `meta.tab.${tab}`
export const pageMetaKey = (page) => `meta.page.${page}`

/** "<Sayfa> · <marka>"; sayfa adı yoksa yalnız marka. */
export function formatTitle(pageTitle, brand) {
  const b = String(brand ?? '').trim() || META_BRAND
  const p = String(pageTitle ?? '').trim()
  return p ? `${p}${META_SEPARATOR}${b}` : b
}

/**
 * App'in o anki durumundan meta anahtarı. Öncelik giriş sayfasındaki bildirimlerle aynı: pasif hesap > bakım >
 * oturum süresi doldu > düz giriş.
 */
export function appMetaKey({
  authChecked, user, mustChangePwd, tab, validTabs, restricted = false,
  accountInactive = false, maintenance = false, sessionExpired = false,
}) {
  if (!authChecked) return pageMetaKey('loading')
  if (!user) {
    if (accountInactive) return pageMetaKey('accountInactive')
    if (maintenance) return pageMetaKey('maintenance')
    if (sessionExpired) return pageMetaKey('sessionExpired')
    return pageMetaKey('login')
  }
  if (mustChangePwd) return pageMetaKey('changePassword')
  if (!validTabs?.has?.(tab)) return pageMetaKey('notFound')
  if (restricted) return pageMetaKey('restricted')
  return tabMetaKey(tab)
}

/**
 * Başlık ve açıklamayı belgeye yazar. `<meta name="description">` yoksa oluşturulur (index.html'de var; testler ve
 * harness sayfaları için savunmacı). Değişmeyen değer yeniden yazılmaz.
 */
export function applyPageMeta(title, description, doc = globalThis.document) {
  if (!doc) return
  if (title && doc.title !== title) doc.title = title
  if (description == null) return
  let tag = doc.head?.querySelector('meta[name="description"]')
  if (!tag) {
    tag = doc.createElement('meta')
    tag.setAttribute('name', 'description')
    doc.head?.appendChild(tag)
  }
  if (tag.getAttribute('content') !== description) tag.setAttribute('content', description)
}
