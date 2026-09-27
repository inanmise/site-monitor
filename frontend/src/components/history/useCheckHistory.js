import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../../api/client'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import { readUrlParam } from '../../hooks/useUrlQuerySync.js'

/** Date → 19 karakterlik UTC ISO (backend checked_at ile aynı format; ResponseTimeChart.toIso deseni). */
export const toUtcIso = (d) => new Date(d).toISOString().slice(0, 19)

/**
 * Kontrol Geçmişi v2 veri hook'u — server-side sayfalı aralık + durum filtresi + canlı yenileme.
 *
 * Kurallar:
 * - Aralık/filtre/boyut değişince sayfa HER ZAMAN 1'e döner (Page sayfasındaki "reset unutuldu"
 *   bug sınıfı burada kökten ölür). Sayfalama 2026-09-26'dan beri standart `useServerPagination`
 *   (modal ön ayarı: izleme detay penceresinde; değer karşılaştırmalı sıfırlama — eski `firstRun` ref'i
 *   StrictMode çift-mount'ta sıfırlıyordu).
 * - Canlı yenileme (30 sn, useVisibleInterval) YALNIZ 1. sayfada ve aralık "şimdi"ye yaslıyken
 *   (preset modunda) çalışır — kullanıcının incelediği derin sayfa altından kaymaz.
 * - pageSize localStorage'a listKey ile yazılır (standart kalıcılık, sm.pageSize.<listKey>).
 * - İlk preset URL'deki `range` paramından okunur (eski paylaşılan linkler çalışmaya devam eder).
 */
export function useCheckHistory({ kind, id, listKey, presets = [1, 7, 15, 30], defaultPreset = 1,
                                  filterMode = 'fail', extraParams = null, live = true,
                                  fixed = null /* {from: Date, to: Date} — sayfa aralığı kontrol eder (Uptime) */,
                                  reloadSignal = 0 /* dışarıdan tazeleme sayacı; 0 = hiç */ }) {
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
  const [data, setData] = useState(null)                 // { items, counts, buckets, alerts, range, total }
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const seqRef = useRef(0)                               // yarış koruması: yalnız son isteğin yanıtı işlenir

  const isCustom = preset === 'custom' && customFrom && customTo
  const fixedFrom = fixed?.from ? toUtcIso(fixed.from) : null
  const fixedTo = fixed?.to ? toUtcIso(fixed.to) : null
  // Aralık/filtre değişiminde sayfa 1 — id değişimi de (başka monitör açıldı). Boyut değişimini kanca kendisi sıfırlar.
  const sp = useServerPagination({ listKey, preset: 'modal', apiBase: 0,
    resetDeps: [kind, id, preset, customFrom ? customFrom.getTime() : null, customTo ? customTo.getTime() : null, status, fixedFrom, fixedTo] })
  const { apiPage, pageSize, setTotal } = sp
  const page = sp.page

  const load = useCallback((silent = false) => {
    if (!id) return
    // "Özel Aralık" seçildi ama tarihler henüz uygulanmadı → İSTEK ATMA (picker açık; önceki
    // veri ekranda kalır). Aksi halde days='custom' gidiyordu → backend 500 (2026-08 test ortamı).
    if (!fixedFrom && preset === 'custom' && !isCustom) return
    const seq = ++seqRef.current
    if (!silent) setLoading(true)
    const params = {
      status: status !== 'all' ? status : undefined,
      page: apiPage,
      size: pageSize,
      ...(fixedFrom ? { from: fixedFrom, to: fixedTo }
        : isCustom ? { from: toUtcIso(customFrom), to: toUtcIso(customTo) }
        : { days: Number.isFinite(Number(preset)) ? Number(preset) : undefined }),
      ...(extraParams || {}),
    }
    api.monitoring.getCheckHistory(kind, id, params)
      .then(r => {
        if (seq !== seqRef.current) return
        if (r?.success) { setData(r.data); setTotal(r.data?.total ?? 0); setError(null) }
        else setError(r?.error || 'load failed')
      })
      .catch(e => { if (seq === seqRef.current) setError(String(e?.message || e)) })
      .finally(() => { if (seq === seqRef.current) setLoading(false) })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kind, id, preset, customFrom, customTo, status, apiPage, pageSize, fixedFrom, fixedTo, JSON.stringify(extraParams)])

  useEffect(() => { load() }, [load])

  // Canlı yenileme: sekme görünürken 30 sn'de bir SESSİZ tazeleme (spinner yakmadan).
  const liveActive = live && page === 1 && preset !== 'custom' && !fixedFrom
  useVisibleInterval(() => load(true), liveActive ? 30000 : 0, false)

  /**
   * Dışarıdan tazeleme. Sertifika modalındaki "Çalıştır" kontrolü SENKRON koşturuyor; koşu
   * bitince yeni kayıt burada da görünmeli, yoksa kullanıcı kontrolü tetikleyip geçmişte
   * hiçbir şey değişmediğini görüyor (canlı yenileme 30 sn'ye kadar bekletirdi; 1. sayfa
   * dışında ya da özel aralıkta ise HİÇ gelmezdi).
   *
   * <p>Sinyal, bileşeni remount ETMEDEN çalışır: `key` ile yeniden kurmak kullanıcının seçtiği
   * aralığı/sayfayı/filtreyi sıfırlardı. `load` bilerek bağımlılık DEĞİL — kimliği her
   * filtre değişiminde değişiyor ve buraya konsaydı her filtre değişimi ikinci bir istek
   * daha atardı (üstteki `useEffect(load)` zaten atıyor).
   */
  const loadRef = useRef(load); loadRef.current = load
  const firstSignal = useRef(true)
  useEffect(() => {
    if (firstSignal.current) { firstSignal.current = false; return }
    loadRef.current(true)
  }, [reloadSignal])

  const setPreset = (p) => { setPresetRaw(p); if (p !== 'custom') { setCustomFrom(null); setCustomTo(null) } }
  const setCustomRange = (fromDate, toDate) => {
    setCustomFrom(fromDate); setCustomTo(toDate); setPresetRaw('custom')
  }

  return {
    // veri
    items: data?.items ?? [],
    counts: data?.counts ?? { total: 0, fail: 0 },
    buckets: data?.buckets ?? [],
    alerts: data?.alerts ?? [],
    range: data?.range ?? null,
    // Saklama şeffaflığı — sunucu zarfından: veri ne kadar tutuluyor, elde en eski/en yeni kayıt.
    retentionDays: data?.retention_days ?? null,
    oldestAt: data?.oldest_at ?? null,
    newestAt: data?.newest_at ?? null,
    total: data?.total ?? 0,
    loading, error,
    // durum + eylemler
    preset, setPreset, customFrom, customTo, setCustomRange,
    status, setStatus, filterMode,
    page, setPage: sp.setPage, pageSize, setPageSize: sp.setPageSize,
    bar: sp.bar,                     // <PaginationBar {...h.bar} /> — standart çubuk (modal ön ayarı, compact)
    liveActive, reload: load,
    fixedMode: !!fixedFrom,
    // CSV — `days` ifadesi load()'takiyle BİREBİR aynı olmalı: ikisi tek kuralın iki yüzü.
    // "Özel Aralık" seçilip tarihler henüz uygulanmadıysa isCustom false kalır ve ham `preset`
    // yazılırsa CSV bağlantısı `?days=custom` üretip backend'i 500'e düşürüyordu (load() bu
    // durumu :57'de guard'lıyor, csvParams guard'sızdı). undefined historyQuery'de düşürülür.
    csvParams: {
      status: status !== 'all' ? status : undefined,
      ...(fixedFrom ? { from: fixedFrom, to: fixedTo }
        : isCustom ? { from: toUtcIso(customFrom), to: toUtcIso(customTo) }
        : { days: Number.isFinite(Number(preset)) ? Number(preset) : undefined }),
      ...(extraParams || {}),
    },
  }
}

export default useCheckHistory
