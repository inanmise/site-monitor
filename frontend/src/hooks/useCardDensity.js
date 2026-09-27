import { useCallback, useState } from 'react'

/**
 * Kart yoğunluğu — "Kompakt" / "Zengin" (2026-09-27): Genel Bakış'taki sertifika kartı seçicisinin izleme
 * sayfalarına genişletilmiş hâli.
 *
 * İzleme sayfaları HER AÇILIŞTA Zengin başlar (kullanıcı kararı 2026-09-27: "izleme sayfaları açıldığında default
 * seçili ekran zengin olsun"): Kompakt seçimi yalnız sayfada kalındığı sürece geçerlidir, tarayıcıya YAZILMAZ (sayfa
 * değişip geri gelince ya da yenileyince yeniden Zengin). Genel Bakış kendi durumunu App'te tutar: ilk açılışta
 * Zengin, oturum içinde son seçim hatırlanır, çıkışta Zengin'e döner.
 *
 * Zengin = tam kart (trend, SLA, ayrıntı döşemeleri); Kompakt = durum + hedef + ana ölçü + eylemler, ızgarada satır
 * başına daha çok kart.
 */
export const CARD_DENSITIES = /** @type {const} */ (['compact', 'rich'])
export const DEFAULT_DENSITY = 'rich'

/**
 * @param {string} page  sayfa anahtarı (ping, http, dns …) — kapı ve okunabilirlik için (kalıcılık üretmez)
 * @returns {['compact'|'rich', (next: 'compact'|'rich') => void]}
 */
// eslint-disable-next-line no-unused-vars
export function useCardDensity(page) {
  const [density, setState] = useState(DEFAULT_DENSITY)
  const setDensity = useCallback((next) => { if (CARD_DENSITIES.includes(next)) setState(next) }, [])
  return [density, setDensity]
}
