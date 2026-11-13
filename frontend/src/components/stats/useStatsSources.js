import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../../api/client'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import { errorInfoOf } from '../../utils/errorMessages.js'

/**
 * İstatistikler sayfasının kendi veri kaynakları (2026-11-13). Sertifika listesi App'ten gelir; burada yönetici özeti,
 * zamanında yenileme, sağlık serisi ve İZNE BAĞLI kaynaklar (izleme panosu: `monitoring.read`; alarm eğilimi + takım alarm
 * kırılımı: `alerts.read`; veri kalitesi: `data_quality.view`) — izin yoksa istek GİTMEZ (`hidden`; 403 + güvenlik
 * denetim kaydı üretmesin).
 *
 * Her kaynak: `{ status: 'loading' | 'ok' | 'failed' | 'hidden' | 'unsupported', data }`. Tazeleme hatasında son veri
 * KALIR (status 'failed', data dolu → "güncellenemedi" notu); ilk okuma hatası "okunamadı + yeniden dene". Yeni uçlara
 * eski sunucu 404 / 405 verirse `unsupported` (bölüm "bu sürümde yok" der, asla "veri yok"). Geç yanıt sıra numarasıyla
 * düşer. 5 dakikada bir (görünür sekmede) ve `reload()` ile yenilenir.
 */
const EMPTY = { status: 'loading', data: null }
const HIDDEN = { status: 'hidden', data: null }

export const SOURCE_KEYS = ['exec', 'renewal', 'health', 'overview', 'noise', 'teamAlerts', 'dq']

function unsupported(res) {
  const s = errorInfoOf(res)?.status
  return s === 404 || s === 405 || s === 501
}

export function useStatsSources({ permsLoaded = true, canMonitoring = false, canAlerts = false, canDataQuality = false, healthTeam = null }) {
  const [state, setState] = useState(() => Object.fromEntries(SOURCE_KEYS.map((k) => [k, EMPTY])))
  const [loadedAt, setLoadedAt] = useState(null)
  const [busy, setBusy] = useState(false)
  const seq = useRef(Object.fromEntries(SOURCE_KEYS.map((k) => [k, 0])))
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])

  const run = useCallback(async (key, fetcher) => {
    const my = ++seq.current[key]
    setState((s) => (s[key].data == null && s[key].status !== 'loading' ? { ...s, [key]: { ...s[key], status: 'loading' } } : s))
    let res
    try { res = await fetcher() } catch (e) { res = e }
    if (!alive.current || my !== seq.current[key]) return
    setState((s) => {
      const prev = s[key]
      if (res?.success && res.data != null) return { ...s, [key]: { status: 'ok', data: res.data } }
      if (unsupported(res)) return { ...s, [key]: { status: 'unsupported', data: null } }
      return { ...s, [key]: { status: 'failed', data: prev.data } }
    })
  }, [])

  const hide = useCallback((key) => {
    seq.current[key]++
    setState((s) => (s[key].status === 'hidden' ? s : { ...s, [key]: HIDDEN }))
  }, [])

  const loadHealth = useCallback((team) => run('health', () => api.getHealthSeries(90, team)), [run])

  const reload = useCallback(async (fresh = false) => {
    if (!permsLoaded) return
    setBusy(true)
    try {
    const jobs = [
      run('exec', () => api.getExecutiveStats(fresh)),
      run('renewal', () => api.getRenewalTimeliness()),
      loadHealth(healthTeam),
    ]
    if (canMonitoring) jobs.push(run('overview', () => api.monitoring.getOverview(24, fresh)))
    else hide('overview')
    if (canAlerts) {
      jobs.push(run('noise', () => api.admin.getAlertNoise(30)))
      jobs.push(run('teamAlerts', () => api.admin.getAlertTeamStats()))
    } else { hide('noise'); hide('teamAlerts') }
    if (canDataQuality) jobs.push(run('dq', () => api.dataQuality.summary(fresh)))
    else hide('dq')
    await Promise.allSettled(jobs)
    if (alive.current) setLoadedAt(Date.now())
    } finally {
      // Kaynak kapısı (busyFlagFinally): beklenmedik bir hata da bayrağı temizler.
      if (alive.current) setBusy(false)
    }
  }, [permsLoaded, canMonitoring, canAlerts, canDataQuality, run, hide, loadHealth, healthTeam])

  // İlk yükleme + izinler değişince; 5 dakikada bir görünür sekmede
  const reloadRef = useRef(reload)
  useEffect(() => { reloadRef.current = reload }, [reload])
  useEffect(() => { reload(false) }, [permsLoaded, canMonitoring, canAlerts, canDataQuality])   // eslint-disable-line react-hooks/exhaustive-deps
  useVisibleInterval(() => reloadRef.current(false), 300_000, false)

  return { ...state, loadedAt, busy, reload, loadHealth }
}
