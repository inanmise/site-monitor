import { useCallback, useEffect, useRef, useState } from 'react'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'

/** Kutu yoklama aralığı — sekme gizliyken durur; TV kipinde de aynı (sunucu uçları 10–60 sn paylaşımlı bellek tutar). */
export const WIDGET_POLL_MS = 60_000

export { sharedFetch, clearSharedFetches } from '../../utils/sharedFetch.js'
import { sharedFetch } from '../../utils/sharedFetch.js'

/**
 * Panom kutusu veri kancası: `fetcher()` → `{success, data}`. İlk yükleme + görünürken periyodik tazeleme; yığılma yok
 * (useVisibleInterval), geç gelen eski yanıt yenisini ezmez (sıra numarası). Hata son bilinen veriyi SİLMEZ — kutu eski
 * veriyi "güncellenemedi" notuyla göstermeye devam eder. `refreshKey` değişince (sayfanın Yenile düğmesi) hemen tazeler.
 */
export function useWidgetData(fetcher, { pollMs = WIDGET_POLL_MS, enabled = true, refreshKey = 0 } = {}) {
  const [state, setState] = useState({ data: null, error: null, loading: enabled, at: null })
  const seq = useRef(0)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])

  const load = useCallback(async (fresh = false) => {
    if (!enabled) return
    const my = ++seq.current
    try {
      const res = await sharedFetch(fetcher, { fresh: fresh === true })
      if (!alive.current || my !== seq.current) return
      // 401 / pasif / bakım: uygulama oturumu ele alır; kutu boş veriden "sorun yok" ÇİZMESİN — hata durumunda kalır (2026-10-12)
      if (res == null) { setState((s) => ({ ...s, loading: false, error: 'error', status: null })); return }
      if (res.success === false) {
        setState((s) => ({ ...s, loading: false, error: res.error || 'error', status: res.errorInfo?.status ?? res.status ?? null }))
        return
      }
      setState({ data: res.data ?? null, error: null, loading: false, at: Date.now(), status: null })
    } catch (e) {
      if (!alive.current || my !== seq.current) return
      setState((s) => ({ ...s, loading: false, error: e?.message || 'error', status: e?.status ?? null }))
    }
  }, [fetcher, enabled])

  useVisibleInterval(load, enabled ? pollMs : 0)
  // Getirici değişince (ör. kutu büyütülünce daha çok satır isteyen getirici) bir sonraki 60 sn'lik turu beklemeden yükle (2026-10-12)
  const lastFetcher = useRef(fetcher)
  useEffect(() => {
    if (lastFetcher.current === fetcher) return
    lastFetcher.current = fetcher
    load(true)
  }, [fetcher, load])
  const first = useRef(true)
  useEffect(() => {
    if (first.current) { first.current = false; return }
    load(true)
  }, [refreshKey]) // eslint-disable-line react-hooks/exhaustive-deps

  return { ...state, reload: load }
}
