import { useEffect } from 'react'

const CRIT_PREFIX = '(!) '

/**
 * Dinamik favicon + sekme başlığı — izleme ürünlerinde standart desen: kullanıcı sekmeye bakarak filo sağlığını görür.
 *
 * <p>2026-10-08 (favicon seti, BRAND.md §7): `ok` STATİK SETİ OLDUĞU GİBİ BIRAKIR — index.html'deki favicon-optimize
 * `favicon.svg` + `favicon.ico` bağlantılarına dokunulmaz (eskiden `ok` bile onları ayrıntılı logonun 32 px PNG'siyle
 * eziyordu; 16 px'te okunmayan leke geri geliyordu). Yalnız `warning` / `critical` / `muted` her `link[rel=icon]`'u
 * `/brand/logo-{durum}-32.png`'ye çevirir; asıl değerler `data-sm-href` / `data-sm-type`'ta saklanır ve `ok`'a dönünce
 * geri yazılır. Uygulama bugün yalnız `ok` ile çağırır (kullanıcı kararı: marka durumla kızarmaz) — altyapı hazır bekler.
 *
 * <p>Başlık: critical'da mevcut başlığın önüne "(!) " eklenir, normale dönünce kaldırılır (usePageMeta'yı ezmez).
 */
export function useStatusFavicon(status) {
  useEffect(() => {
    const links = [...document.querySelectorAll('link[rel="icon"]')]
    if (status === 'ok') {
      for (const link of links) {
        if (link.hasAttribute('data-sm-created')) { link.remove(); continue }   // durum için yaratılmıştı → kaldır
        if (!link.hasAttribute('data-sm-href')) continue
        link.setAttribute('href', link.getAttribute('data-sm-href'))
        const type = link.getAttribute('data-sm-type')
        if (type) link.setAttribute('type', type)
        else link.removeAttribute('type')
        link.removeAttribute('data-sm-href')
        link.removeAttribute('data-sm-type')
      }
    } else {
      if (links.length === 0) {
        const link = document.createElement('link')
        link.rel = 'icon'
        link.setAttribute('data-sm-created', '')
        document.head.appendChild(link)
        links.push(link)
      }
      const href = `/brand/logo-${status}-32.png`
      for (const link of links) {
        if (!link.hasAttribute('data-sm-href')) {
          link.setAttribute('data-sm-href', link.getAttribute('href') ?? '')
          link.setAttribute('data-sm-type', link.getAttribute('type') ?? '')
        }
        link.setAttribute('href', href)
        link.setAttribute('type', 'image/png')
      }
    }

    const bare = document.title.startsWith(CRIT_PREFIX)
      ? document.title.slice(CRIT_PREFIX.length)
      : document.title
    document.title = status === 'critical' ? CRIT_PREFIX + bare : bare
  }, [status])
}
