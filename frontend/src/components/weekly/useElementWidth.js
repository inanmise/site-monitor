import { useLayoutEffect, useState } from 'react'

/**
 * Kap genişliği (px) — görünümü GÖRÜNÜM ALANINA değil KABA göre seçmek için (768 px tablette kenar çubuğu açıkken
 * içerik ~440 px: tablo yerine kart). Ölçüm yoksa (jsdom, ilk boyama) 0 döner → çağıran `useIsMobile()`'a düşer.
 * Kullanım: `const [width, ref] = useElementWidth()` → `<div ref={ref}>`.
 */
export function useElementWidth() {
  const [el, setEl] = useState(null)
  const [width, setWidth] = useState(0)
  useLayoutEffect(() => {
    if (!el) return undefined
    const measure = () => setWidth(Math.round(el.getBoundingClientRect().width))
    measure()
    if (typeof ResizeObserver === 'undefined') return undefined
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [el])
  return [width, setEl]
}
