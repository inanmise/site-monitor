import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../../../api/client'
import { useVisibleInterval } from '../../../hooks/useVisibleInterval.js'
import { resolveRange } from '../../ui/TimeRangePicker.jsx'
import { LIVE_INTERVAL_MS, isLiveRange, normOverview } from './httpMetricsModel.js'

/**
 * İstek Gezgini veri yükleyicisi — yarış korumalı, "eskiyi göster, yeniyi bekle" (useResponseSeries ile aynı sözleşme).
 *
 * - Her istek bir tur numarası alır; yalnız EN SON turun yanıtı yazılır ve yükleme bayrağını yalnız o indirir
 *   (hızlı aralık/süzgeç değişiminde yavaş dönen eski yanıt yeniyi ezmez; son tur her yolda `finally`'ye ulaşır).
 * - Parametre değişince önceki görünüm DURUR ve `stale` işaretlenir (çağıran soluk çizer); aynı parametreyle tazeleme
 *   (elle / otomatik) görünümü soldurmaz (`refreshing`). Hata: `success:false` da istisna da; önceki görünüm korunur.
 * - Otomatik yenileme: ≤ 24 saatlik göreli aralıkta dakikada bir, sekme görünürken (`useVisibleInterval` — gizli
 *   sekmede durur). Süzgeç/sıralama/seçim state'i çağıranda: yenileme onlara dokunmaz.
 * - `params`: sunucunun GERÇEKTEN taradığı UTC aralık (yanıttaki from/to — 31 güne kırpılmış olabilir); yanıt aralık
 *   taşımıyorsa isteğin çözülmüş aralığı. Ayrıntı paneli AYNI aralığı ister (2026-09-28c ek-2: eskiden kırpılmamış istek
 *   aralığıyla `/series` çağrılıyordu → kutucuk 31 gün, grafik 90 gün).
 * - Hata: sunucu iletisi varsa (`success:false`, ör. 400 "başlangıç bitişten sonra") `server: true` ile taşınır.
 */
export function useHttpOverview({ range, endpoint, methods, auto }) {
  const key = JSON.stringify({ range, endpoint: endpoint || '', methods: methods || [] })
  const [view, setView] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const seqRef = useRef(0)
  const aliveRef = useRef(true)
  useEffect(() => {
    aliveRef.current = true   // KURULUMDA da (StrictMode kur → temizle → kur)
    return () => { aliveRef.current = false }
  }, [])

  const argsRef = useRef(null)
  argsRef.current = { range, endpoint, methods, key }

  const load = useCallback(async () => {
    const a = argsRef.current
    const seq = ++seqRef.current
    setLoading(true)
    try {
      const params = resolveRange(a.range)
      const res = await api.admin.getHttpMetricsOverview({ ...params, endpoint: a.endpoint || undefined, methods: a.methods })
      if (seq !== seqRef.current || !aliveRef.current) return
      if (res?.success) {
        // rev: her başarılı yüklemede artar (aynı milisaniyedeki iki yükleme de ayrıntı panelini yeniler)
        const data = normOverview(res.data)
        const served = data.from && data.to ? { from: data.from, to: data.to } : params
        setView({ key: a.key, params: served, data, at: Date.now(), rev: seq })
        setError(null)
      } else {
        const message = res?.error || res?.message || null
        setError({ message, server: !!message })
      }
    } catch (e) {
      if (seq === seqRef.current && aliveRef.current) setError({ message: e?.message || null })
    } finally {
      if (seq === seqRef.current && aliveRef.current) setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [key, load])
  useVisibleInterval(load, auto && isLiveRange(range) ? LIVE_INTERVAL_MS : 0, false)

  const stale = !!view && view.key !== key
  return {
    view,
    loading,
    error: loading ? null : error,
    stale,
    refreshing: loading && !!view && !stale,
    reload: load,
  }
}

export default useHttpOverview
