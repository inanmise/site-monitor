import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../../api/client'
import { paramsKey, shapeDaysSeries } from './certHistoryModel.js'

/**
 * Sertifika geçmişi özetinin iki ek verisi — ikisi de MEVCUT uçlar, ikisi de Kontrol Geçmişi'nin seçili aralığıyla:
 *
 * <p>`useCertDaysSeries` — `/ssl/response-series` (bütün aralığın kovaları): kalan gün eğilimi + yenileme sayısı.
 * `useLastFailure` — `/ssl-history?status=fail&size=1`: aralıktaki SON başarısız kontrol (kesin zaman + hata metni);
 * yalnız aralıkta hata VARSA sorulur.
 *
 * <p>Ortak sözleşme (useResponseSeries / useCheckHistory ile aynı dersler):
 * - Tur sayacı (`seq`): yalnız EN SON isteğin yanıtı yazılır; yükleme bayrağını da yalnız son tur indirir ve bunu
 *   `finally`'de yapar (hiçbir dalda asılı kalmaz). Hedef/aralık boşalınca (modal kapanışı) tur geçersizlenir ve
 *   bayrak KOŞULSUZ sıfırlanır.
 * - Yaşam bayrağı efekt KURULUMUNDA da true'ya çekilir (StrictMode kur→temizle→kur).
 * - Tazeleme: aralık anahtarı ya da `reloadSignal` değişince. Kendi zamanlayıcısı YOK — pencerenin 30 sn'lik yoklaması
 *   yeni kontrol yakalayınca `reloadSignal` artar; kendi aralıklı isteği gereksiz yük olurdu.
 * - Aralık değişirken önceki çizim KALIR (`stale`), iskelet yanıp sönmez; başka alan adına ait veri ASLA gösterilmez.
 */
export function useCertDaysSeries(domain, params, reloadSignal = 0) {
  const key = domain && params ? `${domain}|${paramsKey(params)}` : null
  const [view, setView] = useState(null)          // { key, domain, shape }
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const seq = useRef(0)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  const paramsRef = useRef(params)
  paramsRef.current = params

  const load = useCallback(async () => {
    const my = ++seq.current
    if (!key) { setLoading(false); setError(null); return }
    setLoading(true)
    setError(null)
    try {
      const res = await api.monitoring.getSslResponseSeries(domain, paramsRef.current)
      if (my !== seq.current || !alive.current) return
      if (res?.success) setView({ key, domain, shape: shapeDaysSeries(res.data) })
      else setError(res?.error || res?.message || 'error')
    } catch (e) {
      if (my === seq.current && alive.current) setError(e?.message || 'error')
    } finally {
      if (my === seq.current && alive.current) setLoading(false)
    }
  }, [domain, key])

  useEffect(() => { load() }, [load, reloadSignal])

  const shown = view && view.domain === domain ? view : null
  return {
    shape: shown?.shape ?? null,
    loading,
    error: loading ? null : error,
    // Anahtar YOKKEN (özel aralık seçildi ama uçlar uygulanmadı → istek atılmaz) eski çizim "bayat" SAYILMAZ: istek
    // olmadığı hâlde eğilim kartında sonsuza dek dönen gösterge + %60 soluk çizim kalıyordu (Ek 3/6, 2026-09-28).
    stale: !!key && !!shown && shown.key !== key,
    reload: load,
  }
}

export function useLastFailure(domain, params, failCount, reloadSignal = 0) {
  const fails = Number(failCount) || 0
  const key = domain && params && fails > 0 ? `${domain}|${paramsKey(params)}|${fails}` : null
  const [state, setState] = useState({ key: null, item: null })
  const [loading, setLoading] = useState(false)
  const seq = useRef(0)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  const paramsRef = useRef(params)
  paramsRef.current = params

  useEffect(() => {
    const my = ++seq.current
    if (!key) { setLoading(false); setState({ key: null, item: null }); return undefined }
    let cancelled = false
    ;(async () => {
      setLoading(true)
      try {
        const res = await api.monitoring.getCheckHistory('uptime-ssl', domain,
          { status: 'fail', page: 0, size: 1, ...paramsRef.current })
        if (cancelled || my !== seq.current || !alive.current) return
        const item = res?.success && Array.isArray(res.data?.items) ? res.data.items[0] ?? null : null
        setState({ key, item })
      } catch {
        // Sessiz: kutucuk "—" gösterir; asıl hata yüzeyi geçmiş listesinin kendisi (aynı uç).
        if (!cancelled && my === seq.current && alive.current) setState({ key, item: null })
      } finally {
        if (!cancelled && my === seq.current && alive.current) setLoading(false)
      }
    })()
    return () => { cancelled = true }
  }, [key, domain, reloadSignal])

  return { item: state.key === key ? state.item : null, loading: !!key && loading }
}
