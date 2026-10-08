import { useEffect } from 'react'
import { useT } from '../i18n/index.jsx'
import { useBranding } from '../contexts/BrandingProvider.jsx'
import { META_BRAND, applyPageMeta, formatTitle } from '../utils/pageMeta.js'

/**
 * Belge başlığını ve meta açıklamasını `metaKey`'e göre tutar (2026-10-08) — `metaKey` = `meta.tab.<sekme>` ya da
 * `meta.page.<ad>` (utils/pageMeta.js). Dil değişince (t yenilenir) ve marka adı gelince kendiliğinden tazelenir.
 *
 * Marka: beyaz-etiket `tab_title` → `app_name` → "SiteMonitor". BrandingProvider artık document.title YAZMAZ
 * (iki yazar birbirini ezerdi); tek yazar bu kancadır.
 */
export function usePageMeta(metaKey) {
  const t = useT()
  const { get } = useBranding()
  const brand = String(get('tab_title', '') || get('app_name', '') || META_BRAND)
  const titleKey = `${metaKey}.title`
  const descKey = `${metaKey}.description`
  const rawTitle = metaKey ? t(titleKey) : ''
  const rawDesc = metaKey ? t(descKey) : null
  // Sözlükte yoksa t() anahtarın kendisini döner — ham anahtar sekmede görünmesin: yalnız marka / açıklamaya dokunma
  const pageTitle = rawTitle && rawTitle !== titleKey ? rawTitle : ''
  const description = rawDesc && rawDesc !== descKey ? rawDesc : null
  const title = formatTitle(pageTitle, brand)
  useEffect(() => {
    applyPageMeta(title, description)
  }, [title, description])
}
