import { useEffect, useRef } from 'react'

/**
 * Bir önceki çağrının sözü (promise) bu kadar süredir bitmediyse yine de yeni tura izin verilir — hiç sonuçlanmayan
 * bir söz yoklamayı SONSUZA DEK durdurmasın (GET istekleri 90 sn'de zaman aşımına düşer; bu ondan uzun).
 */
export const BUSY_MAX_MS = 120_000

/**
 * Görünürlük-farkındalıklı interval. `fn`i her `ms`de çağırır AMA sekme gizliyken (document.hidden)
 * duraklatır (interval temizlenir) — arka plan sekmelerinde boşa polling + re-render'ı keser. Sekme
 * yeniden görünür olduğunda son çağrının üstünden `ms` GEÇTİYSE `fn()` hemen bir kez çağrılıp interval yeniden
 * başlar; geçmediyse kalan süre dolunca çağrılır (2026-11-06, performans: kısa bir sekme değişiminden dönüşte
 * sayfadaki 7–8 yoklamanın HEPSİ aynı anda istek atıyordu; artık her biri kendi temposunda). Unmount'ta her şey temizlenir.
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
    let resume = null                                             // görünürlüğe dönüşte kalan süreyi bekleyen zamanlayıcı
    let busy = null                                               // { since } — önceki çağrının sözü sürüyor
    let lastAt = Date.now()                                       // son çağrı (ya da kurulum) anı — tempo çapası
    const tick = () => {
      const f = fnRef.current
      if (!f) return
      if (busy && Date.now() - busy.since < BUSY_MAX_MS) return    // önceki istek sürüyor → bu tur atlanır
      busy = null
      lastAt = Date.now()
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
    const stop = () => {
      if (id != null) { clearInterval(id); id = null }
      if (resume != null) { clearTimeout(resume); resume = null }
    }
    const onVis = () => {
      if (document.hidden) { stop(); return }
      // Gizlenmeden gelen "görünür" olayı (gerçek tarayıcı değişim olmadan olay atmaz; testler ve elle tazeleme
      // kancası bunu "şimdi tazele" diye kullanır): eskisi gibi anında 1 tur, ikinci interval kurulmaz.
      if (id != null) { tick(); return }
      if (resume != null) return                                  // kalan süre zaten bekleniyor
      // Geri gelince: süre dolduysa 1 tazeleme + devam; dolmadıysa kalan süre sonra (tempo korunur).
      // Saat geri alındıysa (elapsed < 0) beklemeden tazele.
      const elapsed = Date.now() - lastAt
      if (elapsed >= ms || elapsed < 0) { tick(); start(); return }
      resume = setTimeout(() => { resume = null; tick(); start() }, ms - elapsed)
    }
    if (immediate) tick()
    if (!document.hidden) start()
    document.addEventListener('visibilitychange', onVis)
    return () => { stop(); document.removeEventListener('visibilitychange', onVis) }
  }, [ms, immediate])
}
