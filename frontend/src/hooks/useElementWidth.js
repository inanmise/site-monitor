import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'

/**
 * Kap genişliği ölçümü — TEK kaynak (2026-10-02, öneri 29: bileşenlerdeki ResizeObserver kopyaları buraya toplandı;
 * davranış birebir, kilit `test/useElementWidth.characterization.test.jsx`). İki biçim, ikisi de bilinçli korunur:
 *   - `useElementWidth()`      → `[ref, width]`: callback ref, `clientWidth` (dolgu dahil, kenarlık/kaydırma çubuğu hariç).
 *   - `useElementWidthState()` → `[width, setEl]`: öğe durumda, layout effect, `Math.round(getBoundingClientRect().width)`
 *     (kenarlık dahil). Haftalık raporlar / Yenileme Planı / Uyarılar / 7-24 Kapsamı / İstatistikler bunu kullanır.
 * Hangi ölçünün kullanıldığı değiştirilmez — eşikler (tablo ↔ kart) bu değerlere göre ayarlı.
 */

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

/**
 * Kabın GERÇEK genişliği (px) — `[width, setEl]`; `ref={setEl}` ile bağlanır (öğe sonradan mount olsa da ölçülür).
 * Görünümü GÖRÜNÜM ALANINA değil KABA göre seçmek için (768 px tablette kenar çubuğu açıkken içerik ~440 px: tablo
 * yerine kart). Ölçüm yoksa (jsdom, ilk boyama) 0 = bilinmiyor → çağıran `useIsMobile()`'a düşer.
 *
 * @returns {[number, (el: HTMLElement|null) => void]} [width, setEl]
 */
export function useElementWidthState() {
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
