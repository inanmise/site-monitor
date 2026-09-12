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

/**
 * Kullanılabilirlik / SLA (2026-09-12, #11): tür başına 30 günlük oran + filo hedefi; 5 dk'da bir.
 * Dönen: { target, days, data: { [id]: { n, fail, up_pct, bad_hours } } }
 */
export function useSla(type, days = 30, refreshMs = 300_000) {
  const [sla, setSla] = useState({ target: null, days, data: {} })
  const load = useCallback(async () => {
    try {
      const r = await api.monitoring.getSla(type, days)
      if (r?.success && r.data && typeof r.data === 'object') setSla({ target: r.target_pct ?? null, days: r.days ?? days, data: r.data })
    } catch { /* süs */ }
  }, [type, days])
  useVisibleInterval(load, refreshMs, true)
  return sla
}

export default useSparklines
