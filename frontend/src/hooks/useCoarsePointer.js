import { useSyncExternalStore } from 'react'

/**
 * Birincil işaretçi dokunmatik mi (`(pointer: coarse)`) — yalnız "hangi BİLEŞEN çizilecek" kararı için (2026-10-09):
 * ör. ölçü ipucu fareyle hover Tooltip'i, dokunmatikte dokun-gör Popover'ı (`ui/HintPopover`; Tooltip telefonda hiç
 * açılmaz — RESPONSIVE.md §4 "yalnız-hover bilgi YOK"). Görünüm farkları CSS'te kalır (`pointer-coarse:`).
 *
 * <p>Tek paylaşılan MediaQueryList: 50 kartlık ızgarada her ölçü ayrı `matchMedia` nesnesi kurmaz. Liste,
 * `window.matchMedia` değişirse (testler sorguyu ezer) yeniden kurulur. `matchMedia` yoksa (eski tarayıcı, SSR) false →
 * masaüstü davranışı (fare kullanıcısını gereksiz bir tıklamaya zorlamaz; jsdom kurulumunda da false).
 */
const QUERY = '(pointer: coarse)'
let cached = null
let cachedFor = null

function mediaList() {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return null
  if (cached && cachedFor === window.matchMedia) return cached
  try {
    cached = window.matchMedia(QUERY)
    cachedFor = window.matchMedia
  } catch {
    cached = null
    cachedFor = null
  }
  return cached
}

function subscribe(onChange) {
  const mql = mediaList()
  if (!mql) return () => {}
  if (typeof mql.addEventListener === 'function') {
    mql.addEventListener('change', onChange)
    return () => mql.removeEventListener('change', onChange)
  }
  if (typeof mql.addListener === 'function') {   // Safari 13 ve altı
    mql.addListener(onChange)
    return () => mql.removeListener(onChange)
  }
  return () => {}
}

const getSnapshot = () => !!mediaList()?.matches
const getServerSnapshot = () => false

export function useCoarsePointer() {
  return useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot)
}

export default useCoarsePointer
