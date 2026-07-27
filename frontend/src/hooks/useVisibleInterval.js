import { useEffect, useRef } from 'react'

/**
 * Görünürlük-farkındalıklı interval. `fn`i her `ms`de çağırır AMA sekme gizliyken (document.hidden)
 * duraklatır (interval temizlenir) — arka plan sekmelerinde boşa polling + re-render'ı keser. Sekme
 * yeniden görünür olduğunda `fn()` bir kez çağrılıp interval yeniden başlar; unmount'ta her şey temizlenir.
 *
 * `ms <= 0` veya `null` → hiç kurulmaz. `fn` her render'da değişebilir (useRef ile en güncel tutulur;
 * effect yalnız `ms` değişince yeniden kurulur → gereksiz resubscribe yok).
 *
 * Not: bu hook ilk çağrıyı DA yapar (mount'ta bir kez), yani hem `load()` hem `setInterval(load, ms)`
 * yerine geçer. Çağrı sırasını korumak isteyen sayfa `immediate=false` ile yalnız interval alabilir.
 */
export function useVisibleInterval(fn, ms, immediate = true) {
  const fnRef = useRef(fn)
  fnRef.current = fn
  useEffect(() => {
    if (!ms || ms <= 0) return undefined
    let id = null
    const tick = () => { const f = fnRef.current; if (f) f() }
    const start = () => { if (id == null) id = setInterval(tick, ms) }
    const stop = () => { if (id != null) { clearInterval(id); id = null } }
    const onVis = () => {
      if (document.hidden) stop()
      else { tick(); start() }   // geri gelince 1 tazeleme + devam
    }
    if (immediate) tick()
    if (!document.hidden) start()
    document.addEventListener('visibilitychange', onVis)
    return () => { stop(); document.removeEventListener('visibilitychange', onVis) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ms, immediate])
}
