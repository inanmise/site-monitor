import { useEffect, useSyncExternalStore } from 'react'
import { useT } from '../i18n/index.jsx'
import { useBranding } from '../contexts/BrandingProvider.jsx'
import { META_BRAND, applyPageMeta, formatTitle, getSubPageMeta, subscribeSubPageMeta, tabMetaKey } from '../utils/pageMeta.js'

/**
 * Belge başlığını ve meta açıklamasını `metaKey`'e göre tutar (2026-10-08) — `metaKey` = `meta.tab.<sekme>` ya da
 * `meta.page.<ad>` (utils/pageMeta.js). Dil değişince (t yenilenir) ve marka adı gelince kendiliğinden tazelenir.
 *
 * Marka: beyaz-etiket `tab_title` → `app_name` → "SiteMonitor". BrandingProvider artık document.title YAZMAZ
 * (iki yazar birbirini ezerdi); tek yazar bu kancadır.
 *
 * Alt sayfa (2026-11-05): etkin sekmenin içindeki bir rota `setSubPageMeta({ tab, key, name })` ile kendi başlığını
 * isterse (Aktivite → ayrıntı / zaman çizelgesi) o anahtar kullanılır — sayfa `document.title`'a kendisi YAZMAZ.
 */
export function usePageMeta(metaKey) {
  const t = useT()
  const { get } = useBranding()
  const sub = useSyncExternalStore(subscribeSubPageMeta, getSubPageMeta, getSubPageMeta)
  const useSub = !!sub && metaKey === tabMetaKey(sub.tab)
  const key = useSub ? sub.key : metaKey
  const brand = String(get('tab_title', '') || get('app_name', '') || META_BRAND)
  const titleKey = `${key}.title`
  const descKey = `${key}.description`
  const rawTitle = key ? t(titleKey) : ''
  const rawDesc = key ? t(descKey) : null
  // Sözlükte yoksa t() anahtarın kendisini döner — ham anahtar sekmede görünmesin: yalnız marka / açıklamaya dokunma
  const pageTitle = rawTitle && rawTitle !== titleKey ? rawTitle : ''
  const description = rawDesc && rawDesc !== descKey ? rawDesc : null
  const title = formatTitle(useSub && sub.name ? (pageTitle ? `${sub.name} — ${pageTitle}` : sub.name) : pageTitle, brand)
  useEffect(() => {
    applyPageMeta(title, description)
  }, [title, description])
}
