import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Bir öğenin YÜKSEKLİĞİNİ izleyen callback ref (ResizeObserver) — `useElementWidth()` ile aynı biçim (`[ref, height]`).
 * İlk kullanım (2026-10-17, inceleme A4): rapor düzenleyicinin yapışkan eylem çubuğu 390 px telefonda üç satıra
 * sarılabiliyor ve sabit `pb-24` (96 px) alt boşluk son bölüm kartını örtüyordu; kabın alt boşluğu artık çubuğun
 * GERÇEK yüksekliğini izler. Ölçü `getBoundingClientRect().height` (kenarlık dahil — çubuğun `border-t`'si de örter),
 * tam sayıya yuvarlanır. Ölçüm yoksa (jsdom, ilk boyama, öğe söküldü) 0 döner; çağıran o durumda kendi tabanına düşer.
 *
 * @returns {[(el: HTMLElement|null) => void, number]} [ref, height]
 */
export function useElementHeight() {
  const [height, setHeight] = useState(0)
  const roRef = useRef(null)
  const ref = useCallback((el) => {
    roRef.current?.disconnect()
    roRef.current = null
    if (!el) {
      setHeight(0)
      return
    }
    const measure = () => setHeight(Math.round(el.getBoundingClientRect().height) || 0)
    measure()
    if (typeof ResizeObserver !== 'undefined') {
      roRef.current = new ResizeObserver(measure)
      roRef.current.observe(el)
    }
  }, [])
  useEffect(() => () => roRef.current?.disconnect(), [])
  return [ref, height]
}
