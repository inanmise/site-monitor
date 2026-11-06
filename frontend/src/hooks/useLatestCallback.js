import { useCallback, useRef } from 'react'

/**
 * KARARLI başvurulu geri çağrı (2026-11-06, performans): dönen fonksiyonun kimliği bileşen ömrü boyunca DEĞİŞMEZ, çağrıldığında
 * EN SON çizimdeki `fn`i çalıştırır. `memo` bileşenlere (sertifika kartı, menü) verilen işleyiciler her çizimde yeni fonksiyon
 * olunca memo boşa çıkıyordu; `useCallback` ise güncel durumu okuyan işleyicide bağımlılık zinciri gerektirirdi.
 *
 * <p>Davranış eski satır içi fonksiyonla aynıdır: işleyici olay anında çağrılır ve son çizimin değerlerini görür. Çizim sırasında
 * ÇAĞRILMAMALI (olay / efekt içinden çağrılır).
 *
 * @template {Function} F
 * @param {F} fn
 * @returns {F}
 */
export function useLatestCallback(fn) {
  const ref = useRef(fn)
  ref.current = fn
  return useCallback((...args) => ref.current(...args), [])
}

/**
 * Anahtar başına KARARLI, argümansız işleyici üretir (2026-11-06, performans): `get(key)` her çağrıda aynı anahtar için aynı
 * fonksiyonu döner; o fonksiyon çağrılınca en son çizimdeki `fn(key)` çalışır. Kart listesinde satır içi `() => fn(domain)`
 * her çizimde yeni fonksiyon üretip kartların memo'sunu bozuyordu. İşleyici olay argümanını İLETMEZ (eski satır içi kapanış da
 * iletmiyordu). Önbellek anahtar sayısıyla sınırlı (listedeki alan adları).
 *
 * @template K
 * @param {(key: K) => any} fn
 * @returns {(key: K) => (() => any)}
 */
export function useKeyedHandler(fn) {
  const ref = useRef(fn)
  ref.current = fn
  const cache = useRef(null)
  return useCallback((key) => {
    if (!cache.current) cache.current = new Map()
    let h = cache.current.get(key)
    if (!h) {
      h = () => ref.current(key)
      cache.current.set(key, h)
    }
    return h
  }, [])
}
