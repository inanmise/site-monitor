import { useCallback, useState } from 'react'
import { api } from '../api/client'
import { useVisibleInterval } from './useVisibleInterval.js'

/**
 * İzleme kartı mini trendi (2026-09-12, #4 + #14): tür başına TEK toplu istek; sekme görünürken 60 sn'de
 * bir tazelenir (useVisibleInterval — arka planda durur). Hata sessiz: sparkline süs, liste asıl.
 * Dönen harita: { [monitorId]: { n, fail, up_pct, buckets:[{t,n,fail,ms}], last:[{at,ok,ms}] } }
 */
export function useSparklines(type, hours = 24, refreshMs = 60_000) {
  const [map, setMap] = useState({})
  const load = useCallback(async () => {
    try {
      const r = await api.monitoring.getSparklines(type, hours)
      if (r?.success && r.data && typeof r.data === 'object') setMap(r.data)
    } catch { /* süs veri — liste etkilenmez */ }
  }, [type, hours])
  useVisibleInterval(load, refreshMs, true)
  return map
}

export default useSparklines
