import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Bir öğenin genişliğini izleyen callback ref (ResizeObserver) — kap genişliğine göre tablo ↔ kart kararı için
 * (görünüm alanı değil: aynı liste pencerede, kenar çubuğuyla daralmış sayfada ya da tam ekranda farklı genişlikte).
 * Ölçüm yoksa (jsdom, ilk boyama) 0 döner; çağıran o durumda kendi tabanına düşer.
 *
 * @returns {[(el: HTMLElement|null) => void, number]} [ref, width]
 */
export function useElementWidth() {
  const [width, setWidth] = useState(0)
  const roRef = useRef(null)
  const ref = useCallback((el) => {
    roRef.current?.disconnect()
    roRef.current = null
    if (!el) return
    const measure = () => setWidth(el.clientWidth || 0)
    measure()
    if (typeof ResizeObserver !== 'undefined') {
      roRef.current = new ResizeObserver(measure)
      roRef.current.observe(el)
    }
  }, [])
  useEffect(() => () => roRef.current?.disconnect(), [])
  return [ref, width]
}
