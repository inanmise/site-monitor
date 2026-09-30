import * as React from "react"

const MOBILE_BREAKPOINT = 768

/**
 * Telefon eşiği (< 768 px). TEMBEL BAŞLANGIÇ (2026-09-30, A6-D3): eskiden `useState(undefined)` ile ilk çizim hep
 * MASAÜSTÜ kabul ediliyor, efektten sonra telefona dönüyordu — tablo/kart, Sheet/Dialog kararını buna bağlayan her
 * yüzey (Genel Bakış istatistikleri, detay pencereleri, listeler) bir karelik titreme + ağaç remount'u yaşıyordu.
 * jsdom/SSR'da `matchMedia` yoksa false (eski varsayılan).
 */
function initialIsMobile() {
  try { return typeof window !== 'undefined' && window.innerWidth < MOBILE_BREAKPOINT } catch { return false }
}

export function useIsMobile() {
  const [isMobile, setIsMobile] = React.useState(initialIsMobile)

  React.useEffect(() => {
    const mql = window.matchMedia(`(max-width: ${MOBILE_BREAKPOINT - 1}px)`)
    const onChange = () => {
      setIsMobile(window.innerWidth < MOBILE_BREAKPOINT)
    }
    mql.addEventListener("change", onChange)
    setIsMobile(window.innerWidth < MOBILE_BREAKPOINT)
    return () => mql.removeEventListener("change", onChange)
  }, [])

  return !!isMobile
}
