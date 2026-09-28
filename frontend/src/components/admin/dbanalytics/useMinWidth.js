import { useEffect, useState } from 'react'

/**
 * Görünüm alanı en az `px` genişlikte mi (davranış farkı için; RESPONSIVE.md §1 — görünüm farkı CSS'le).
 *
 * Neden JS: liste ya TABLO ya KART olarak çizilir; ikisini birden DOM'a koyup CSS ile gizlemek her satır düğmesini
 * iki kez üretir (ekran okuyucu + testlerde çift ad). İlk değer eşzamanlı okunur (ilk boyamada yanlış düzen
 * sıçraması yok); `resize` (yön değişimi dâhil) izlenir. jsdom varsayılanı 1024 → masaüstü düzeni.
 */
export function useMinWidth(px) {
  const read = () => (typeof window === 'undefined' ? true : window.innerWidth >= px)
  const [ok, setOk] = useState(read)
  useEffect(() => {
    const on = () => setOk(typeof window === 'undefined' ? true : window.innerWidth >= px)
    on()
    window.addEventListener('resize', on)
    return () => window.removeEventListener('resize', on)
  }, [px])
  return ok
}
