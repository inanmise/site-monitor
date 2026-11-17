import { useCallback, useLayoutEffect, useRef } from 'react'

/**
 * Sayfa düzeyinde YAPIŞKAN ALT EYLEM ÇUBUĞU kaydı (2026-11-17, performans).
 *
 * <p>Eskiden App.css `body:has(.app-main .sticky.bottom-0)` ile "sayfada yapışkan alt çubuk var mı?" sorusunu soruyordu (alt
 * boşluk küçülür, Yardım düğmesi çubuğun üstüne kalkar). `body`'ye bağlı bir `:has()` her DOM eklemesinde/silmesinde sayfanın
 * TAMAMINDA stil geçersizleştirmesi tetikler: SQL Playground editöründe her tuş ~1480 öğenin stilini yeniden hesaplatıyordu
 * (30 tuşta ~567 ms stil hesabı; kural kalkınca ~32 ms). Şimdi çubuk takılınca `body`'ye `data-sticky-bar` konur, son çubuk
 * sökülünce kaldırılır; CSS öznitelik seçicisi yalnız öznitelik değişince (takma/sökme anı) çalışır.
 *
 * <p>Kapsam eskisiyle AYNI: yalnız `.app-main` İÇİNDEKİ çubuk sayılır (portal'daki diyalog / açılır pencere çubukları
 * sayılmaz). Birden çok çubuk sayaçla izlenir; StrictMode'un ref'i söküp yeniden takması sayacı bozmaz.
 * YENİ bir sayfa düzeyi `sticky bottom-0` çubuğu bu kancanın ref'ini almalı (kapı `stickyBottomBar.test.jsx`).
 */
export const STICKY_BAR_ATTR = 'data-sticky-bar'

let count = 0

/**
 * Bir yapışkan alt çubuğu kaydeder; dönen fonksiyon kaydı bir kez geri alır (ikinci çağrı etkisiz).
 * @returns {() => void}
 */
export function registerStickyBottomBar() {
  if (typeof document === 'undefined' || !document.body) return () => {}
  count += 1
  if (count === 1) document.body.setAttribute(STICKY_BAR_ATTR, '')
  let done = false
  return () => {
    if (done) return
    done = true
    count = Math.max(0, count - 1)
    if (count === 0) document.body?.removeAttribute(STICKY_BAR_ATTR)
  }
}

/** Test yardımcısı: kayıtlı çubuk sayısı. */
export function stickyBottomBarCount() {
  return count
}

/**
 * Yapışkan çubuğa verilecek KARARLI callback ref. Öğe `.app-main` içindeyse ve `active` doğruysa kaydeder; öğe sökülünce,
 * `active` yanlışa dönünce ya da bileşen kalkınca geri alır.
 *
 * @param {((el: HTMLElement|null) => void) | { current: HTMLElement|null } | null} [forwardRef] aynı öğenin gitmesi gereken
 *        başka bir ref (callback ya da nesne) — ona da iletilir.
 * @param {boolean} [active=true] çubuk yalnız bazen yapışkansa (ör. kaydedilmemiş değişiklik varken) o koşul.
 * @returns {(el: HTMLElement|null) => void}
 */
export function useStickyBottomBar(forwardRef, active = true) {
  const fwd = useRef(forwardRef)
  fwd.current = forwardRef
  const activeRef = useRef(active)
  activeRef.current = active
  const elRef = useRef(null)
  const reg = useRef({ el: null, off: null })

  const apply = useCallback(() => {
    const el = elRef.current
    const want = Boolean(activeRef.current && el && typeof el.closest === 'function' && el.closest('.app-main'))
    const s = reg.current
    if (want && s.el === el) return
    if (!want && !s.off) return
    s.off?.()
    s.off = null
    s.el = null
    if (want) {
      s.off = registerStickyBottomBar()
      s.el = el
    }
  }, [])

  const ref = useCallback((node) => {
    const r = fwd.current
    if (typeof r === 'function') r(node)
    else if (r && typeof r === 'object') r.current = node
    elRef.current = node
    apply()
  }, [apply])

  useLayoutEffect(() => { apply() }, [active, apply])
  useLayoutEffect(() => () => {
    reg.current.off?.()
    reg.current = { el: null, off: null }
  }, [])

  return ref
}
