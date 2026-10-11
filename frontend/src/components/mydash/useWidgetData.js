import { useCallback, useEffect, useRef, useState } from 'react'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'

/** Kutu yoklama aralığı — sekme gizliyken durur; TV kipinde de aynı (sunucu uçları 10–60 sn paylaşımlı bellek tutar). */
export const WIDGET_POLL_MS = 60_000

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

  const load = useCallback(async () => {
    if (!enabled) return
    const my = ++seq.current
    try {
      const res = await fetcher()
      if (!alive.current || my !== seq.current) return
      if (res == null) { setState((s) => ({ ...s, loading: false })); return }   // 401: uygulama oturumu ele alır
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
  const first = useRef(true)
  useEffect(() => {
    if (first.current) { first.current = false; return }
    load()
  }, [refreshKey]) // eslint-disable-line react-hooks/exhaustive-deps

  return { ...state, reload: load }
}
