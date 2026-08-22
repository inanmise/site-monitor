/**
 * Şablon kütüphanesi kategori anahtarları — sunucudaki
 * {@code ScriptedTemplateCategories.ORDER} ile AYNI SIRADA olmak zorundadır.
 *
 * <p>Sıra anlamlıdır ve ağaçtaki dal sırasıdır: bir siteyi izlemeye baştan başlayan birinin
 * ilerleyeceği yol — önce ayakta mı, sonra girilebiliyor mu, sonra iş akışları, en sonda uçtan
 * uca yolculuklar. Alfabetik sıralamak bu bilgiyi yok ederdi.
 *
 * <p>Etiketler BURADA DEĞİL i18n'de (`tpl.cat.<anahtar>`): dal başlıkları iki dilde gösteriliyor.
 */
export const CATEGORY_ORDER = [
  'availability',
  'identity',
  'search',
  'checkout',
  'forms',
  'api',
  'content-seo',
  'performance',
  'security',
  'journey',
]

/** Editördeki kapsam seçicisi için: anahtar + i18n'den çözülen etiket. */
export function categoryOptions(t) {
  return CATEGORY_ORDER.map(key => ({ value: key, label: t('tpl.cat.' + key) }))
}
