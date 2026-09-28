import { useEffect, useRef, useState } from 'react'
import { INVENTORY_ADDED_EVENT } from '../utils/inventoryEvent.js'

/** Deneme aralıkları (ms) — 7 deneme, toplam ~3 dk. İlk sertifika kontrolü çoğunlukla ilk iki denemede biter. */
export const WARMUP_DELAYS = Object.freeze([2500, 6000, 12000, 20000, 30000, 45000, 60000])

/**
 * Yeni eklenen alan adı "ısınıyor" (2026-09-28, kullanıcı: "yeni eklenen alan adının kartında sağlık / açık alarm /
 * sorumlu kişi bir süre boş, sonradan geliyor"). Ekleme ucu ilk sertifika kontrolünü ARKA PLANDA başlatır; Genel
 * Bakış'ın tek seferlik tazelemesi kontrol bitmeden gelir, sonraki tazeleme 5 dk sonradır. `sm:inventory-added`
 * (api/client.js tek kaynak) gelince `refresh(domain, isAlive)` o alan adının verisi hazır olana dek
 * {@link WARMUP_DELAYS} aralıklarıyla yeniden çağrılır; bu sürede alan adı döndürülen kümededir (kart "İlk kontrol
 * yapılıyor…" / "hesaplanıyor" gösterir). Hazır olunca `onReady()` (tam tazeleme) çağrılır.
 *
 * <p><b>Oturuma bağlı</b> (regresyon taraması 2026-09-28): `active` false olunca (çıkış / oturum düştü) zamanlayıcılar
 * durur, küme boşalır ve yoldaki yanıt `isAlive()` false gördüğü için yeni oturumun durumuna YAZILMAZ — `refresh`
 * durum yazmadan önce `isAlive()`'a bakmalıdır. Aynı alan adı yeniden gelirse eski döngü kendiliğinden biter (nesil).
 *
 * @param {boolean} active oturum açık mı
 * @param {(domain: string, isAlive: () => boolean) => Promise<boolean>} refresh veriyi çeker + yazar; hazırsa true
 * @param {() => void} [onReady] alan adının verisi hazır olunca (ör. tam `loadData`)
 * @returns {Set<string>} ısınan alan adları
 */
export function useNewDomainWarmup(active, refresh, onReady, delays = WARMUP_DELAYS) {
  const [warming, setWarming] = useState(() => new Set())
  const refreshRef = useRef(refresh)
  const readyRef = useRef(onReady)
  useEffect(() => { refreshRef.current = refresh; readyRef.current = onReady })

  useEffect(() => {
    if (!active) return undefined
    let alive = true
    const isAlive = () => alive
    const timers = new Map()
    const gens = new Map()
    const stop = (d) => {
      clearTimeout(timers.get(d)); timers.delete(d)
      setWarming((s) => { if (!s.has(d)) return s; const n = new Set(s); n.delete(d); return n })
    }
    const onAdded = (e) => {
      const d = e?.detail?.domain
      if (!d) return
      clearTimeout(timers.get(d))
      const gen = (gens.get(d) || 0) + 1
      gens.set(d, gen)
      setWarming((s) => (s.has(d) ? s : new Set(s).add(d)))
      let attempt = 0
      const tick = async () => {
        let ready = false
        try { ready = Boolean(await refreshRef.current?.(d, isAlive)) } catch { ready = false }
        if (!alive || gens.get(d) !== gen) return
        attempt += 1
        if (ready || attempt >= delays.length) { stop(d); if (ready) readyRef.current?.(); return }
        timers.set(d, setTimeout(tick, delays[attempt]))
      }
      timers.set(d, setTimeout(tick, delays[0]))
    }
    window.addEventListener(INVENTORY_ADDED_EVENT, onAdded)
    return () => {
      alive = false
      window.removeEventListener(INVENTORY_ADDED_EVENT, onAdded)
      for (const t of timers.values()) clearTimeout(t)
      setWarming((s) => (s.size ? new Set() : s))
    }
  }, [active, delays])

  return warming
}
