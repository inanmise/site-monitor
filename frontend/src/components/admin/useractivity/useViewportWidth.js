import { useEffect, useState } from 'react'

/**
 * Pencere genişliği (px) — Kullanıcı Dizini hangi BİLEŞENİ çizeceğine buradan karar verir: geniş ekranda tablo +
 * satır içi faset çubuğu, dar ekranda kart listesi + süzgeç Sheet'i; telefonda Sheet alttan açılır.
 * İkisini birden çizip CSS ile gizlemek aynı içeriği DOM'da iki kez bulundururdu (ekran okuyucu + testler).
 * `innerWidth` okunur (use-mobile ile aynı ilke): jsdom 1024 döner → testlerde varsayılan masaüstü düzeni;
 * test `window.innerWidth`'i değiştirip `resize` yayınlayarak telefon düzenini çizer. Aynı değer yeniden
 * yazılınca React çizimi atlar (yeniden boyutlandırmada gereksiz çizim yok).
 */
export function useViewportWidth() {
  const read = () => (typeof window === 'undefined' ? 1280 : window.innerWidth || 1280)
  const [w, setW] = useState(read)
  useEffect(() => {
    const on = () => setW(read())
    window.addEventListener('resize', on)
    on()
    return () => window.removeEventListener('resize', on)
  }, [])
  return w
}

/** Kırılım noktaları (Tailwind `md` / `lg`). */
export const PHONE_MAX = 768
export const WIDE_MIN = 1024

export default useViewportWidth
