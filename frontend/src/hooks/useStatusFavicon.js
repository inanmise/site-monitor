import { useEffect } from 'react'

const CRIT_PREFIX = '(!) '

/**
 * Dinamik favicon + sekme başlığı — izleme ürünlerinde standart desen: kullanıcı sekmeye
 * bakarak filo sağlığını görür. BrandingProvider'ın tab_title yönetimini EZMEZ: critical'da
 * mevcut başlığın önüne "(!) " ekler, normale dönünce kaldırır.
 */
export function useStatusFavicon(status) {
  useEffect(() => {
    let link = document.querySelector('link[rel="icon"]')
    if (!link) {
      link = document.createElement('link')
      link.rel = 'icon'
      document.head.appendChild(link)
    }
    link.href = `/brand/logo-${status}-32.png`

    const bare = document.title.startsWith(CRIT_PREFIX)
      ? document.title.slice(CRIT_PREFIX.length)
      : document.title
    document.title = status === 'critical' ? CRIT_PREFIX + bare : bare
  }, [status])
}
