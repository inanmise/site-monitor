import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../../api/client'
import { readPageSize, writePageSize, DEFAULT_PAGE_SIZE } from '../../hooks/usePagination.js'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import { readUrlParam } from '../../hooks/useUrlQuerySync.js'

/** Date → 19 karakterlik UTC ISO (backend checked_at ile aynı format; ResponseTimeChart.toIso deseni). */
export const toUtcIso = (d) => new Date(d).toISOString().slice(0, 19)

/**
 * Kontrol Geçmişi v2 veri hook'u — server-side sayfalı aralık + durum filtresi + canlı yenileme.
 *
 * Kurallar:
 * - Aralık/filtre/boyut değişince sayfa HER ZAMAN 1'e döner (Page sayfasındaki "reset unutuldu"
 *   bug sınıfı burada kökten ölür).
 * - Canlı yenileme (30 sn, useVisibleInterval) YALNIZ 1. sayfada ve aralık "şimdi"ye yaslıyken
 *   (preset modunda) çalışır — kullanıcının incelediği derin sayfa altından kaymaz.
 * - pageSize localStorage'a listKey ile yazılır (usePagination ile aynı kalıcılık).
 * - İlk preset URL'deki `range` paramından okunur (eski paylaşılan linkler çalışmaya devam eder).
 */
export function useCheckHistory({ kind, id, listKey, presets = [1, 7, 15, 30], defaultPreset = 1,
                                  filterMode = 'fail', extraParams = null, live = true }) {
  const initialRange = readUrlParam('range', null)
  const initialPreset = presets.includes(Number(initialRange)) ? Number(initialRange)
    : (initialRange === 'custom' ? 'custom' : defaultPreset)

  const [preset, setPresetRaw] = useState(initialPreset)
  const [customFrom, setCustomFrom] = useState(() => {
    const v = readUrlParam('hfrom', null)
    return initialPreset === 'custom' && v ? new Date(v + 'Z') : null
  })
  const [customTo, setCustomTo] = useState(() => {
    const v = readUrlParam('hto', null)
    return initialPreset === 'custom' && v ? new Date(v + 'Z') : null
  })
  const [status, setStatus] = useState(() => {
    const v = readUrlParam('hst', null)
    return (v === 'fail' || v === 'changed') ? v : 'all'
  })
  const [page, setPageRaw] = useState(1)                 // 1-tabanlı UI; API'ye page-1 gider
  const [pageSize, setPageSizeRaw] = useState(() => readPageSize(listKey, DEFAULT_PAGE_SIZE))

  const [data, setData] = useState(null)                 // { items, counts, buckets, alerts, range, total }
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const seqRef = useRef(0)                               // yarış koruması: yalnız son isteğin yanıtı işlenir

  const isCustom = preset === 'custom' && customFrom && customTo

  const load = useCallback((silent = false) => {
    if (!id) return
    const seq = ++seqRef.current
    if (!silent) setLoading(true)
    const params = {
      status: status !== 'all' ? status : undefined,
      page: page - 1,
      size: pageSize,
      ...(isCustom ? { from: toUtcIso(customFrom), to: toUtcIso(customTo) } : { days: preset }),
      ...(extraParams || {}),
    }
    api.monitoring.getCheckHistory(kind, id, params)
      .then(r => {
        if (seq !== seqRef.current) return
        if (r?.success) { setData(r.data); setError(null) }
        else setError(r?.error || 'load failed')
      })
      .catch(e => { if (seq === seqRef.current) setError(String(e?.message || e)) })
      .finally(() => { if (seq === seqRef.current) setLoading(false) })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kind, id, preset, customFrom, customTo, status, page, pageSize, JSON.stringify(extraParams)])

  useEffect(() => { load() }, [load])

  // Canlı yenileme: sekme görünürken 30 sn'de bir SESSİZ tazeleme (spinner yakmadan).
  const liveActive = live && page === 1 && preset !== 'custom'
  useVisibleInterval(() => load(true), liveActive ? 30000 : 0, false)

  // Aralık/filtre/boyut değişiminde sayfa 1'e döner — id değişimi de (başka monitör açıldı).
  const firstRun = useRef(true)
  useEffect(() => {
    if (firstRun.current) { firstRun.current = false; return }
    setPageRaw(1)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kind, id, preset, customFrom, customTo, status, pageSize])

  const setPreset = (p) => { setPresetRaw(p); if (p !== 'custom') { setCustomFrom(null); setCustomTo(null) } }
  const setCustomRange = (fromDate, toDate) => {
    setCustomFrom(fromDate); setCustomTo(toDate); setPresetRaw('custom')
  }
  const setPageSize = (n) => { setPageSizeRaw(n); writePageSize(listKey, n) }

  return {
    // veri
    items: data?.items ?? [],
    counts: data?.counts ?? { total: 0, fail: 0 },
    buckets: data?.buckets ?? [],
    alerts: data?.alerts ?? [],
    range: data?.range ?? null,
    total: data?.total ?? 0,
    loading, error,
    // durum + eylemler
    preset, setPreset, customFrom, customTo, setCustomRange,
    status, setStatus, filterMode,
    page, setPage: setPageRaw, pageSize, setPageSize,
    liveActive, reload: load,
    // CSV
    csvParams: {
      status: status !== 'all' ? status : undefined,
      ...(isCustom ? { from: toUtcIso(customFrom), to: toUtcIso(customTo) } : { days: preset }),
      ...(extraParams || {}),
    },
  }
}

export default useCheckHistory
