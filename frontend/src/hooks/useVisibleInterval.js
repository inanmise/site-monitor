import { useEffect, useRef } from 'react'

/**
 * Bir önceki çağrının sözü (promise) bu kadar süredir bitmediyse yine de yeni tura izin verilir — hiç sonuçlanmayan
 * bir söz yoklamayı SONSUZA DEK durdurmasın (GET istekleri 90 sn'de zaman aşımına düşer; bu ondan uzun).
 */
export const BUSY_MAX_MS = 120_000

/**
 * Görünürlük-farkındalıklı interval. `fn`i her `ms`de çağırır AMA sekme gizliyken (document.hidden)
 * duraklatır (interval temizlenir) — arka plan sekmelerinde boşa polling + re-render'ı keser. Sekme
 * yeniden görünür olduğunda `fn()` bir kez çağrılıp interval yeniden başlar; unmount'ta her şey temizlenir.
 *
 * `ms <= 0` veya `null` → hiç kurulmaz. `fn` her render'da değişebilir (useRef ile en güncel tutulur;
 * effect yalnız `ms` değişince yeniden kurulur → gereksiz resubscribe yok).
 *
 * Yığılma yok (2026-10-09): `fn` bir söz (promise) döndürürse o söz bitene kadar sonraki turlar ATLANIR — yavaş
 * sunucuda 15–30 sn'lik yoklama, 90 sn'lik zaman aşımına kadar üst üste ağır istek biriktirmesin. Söz döndürmeyen
 * `fn` (saat tıkı, `setState`) eskisi gibi her turda çağrılır. Reddedilen söz turu serbest bırakır.
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
    let busy = null                                               // { since } — önceki çağrının sözü sürüyor
    const tick = () => {
      const f = fnRef.current
      if (!f) return
      if (busy && Date.now() - busy.since < BUSY_MAX_MS) return    // önceki istek sürüyor → bu tur atlanır
      busy = null
      const r = f()
      if (r && typeof r.then === 'function') {
        const mine = { since: Date.now() }
        busy = mine
        const done = () => { if (busy === mine) busy = null }
        // Ret yine yüzeye çıkar (eskisi gibi işlenmemiş ret) — kanca hatayı yutmaz, yalnız turu serbest bırakır
        r.then(done, (e) => { done(); throw e })
      }
    }
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
  }, [ms, immediate])
}
