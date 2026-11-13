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
 *
 * Hata taraması 2026-11-13: bölümün "Tekrar dene"si `retry(key)` — YALNIZ o kaynağı, destekleyen uçta `fresh=1` ile okur
 * (sunucu belleğindeki eski yanıt yerine yeniden hesap; sunucu en sık 5 sn'de bir). 30 günlük alarm eğilimi (`noise`) 5 dk
 * yoklamasına girmez — her açık sekme her 5 dk'da 30 günlük alarm satırlarını taramasın; ilk yükleme, "Yenile" ve
 * "Tekrar dene" okur (son okuma başarısızsa yoklama yine dener).
 */
const EMPTY = { status: 'loading', data: null }
const HIDDEN = { status: 'hidden', data: null }

export const SOURCE_KEYS = ['exec', 'renewal', 'health', 'overview', 'noise', 'teamAlerts', 'dq']

/** Kaynak → okuyucu (`fresh` destekleyen uçlar bayrağı iletir); sağlık serisi `loadHealth` ile ayrı (takım seçimi). */
const FETCHERS = {
  exec: (fresh) => api.getExecutiveStats(fresh),
  renewal: () => api.getRenewalTimeliness(),
  overview: (fresh) => api.monitoring.getOverview(24, fresh),
  noise: () => api.admin.getAlertNoise(30),
  teamAlerts: () => api.admin.getAlertTeamStats(),
  dq: (fresh) => api.dataQuality.summary(fresh),
}

/** 5 dk yoklamasında son okuması başarılıysa atlanan kaynaklar (yavaş değişen, okuması pahalı). */
const POLL_SKIP_WHEN_OK = new Set(['noise'])

function unsupported(res) {
  const s = errorInfoOf(res)?.status
  return s === 404 || s === 405 || s === 501
}

export function useStatsSources({ permsLoaded = true, canMonitoring = false, canAlerts = false, canDataQuality = false, healthTeam = null }) {
  const [state, setState] = useState(() => Object.fromEntries(SOURCE_KEYS.map((k) => [k, EMPTY])))
  const stateRef = useRef(state)
  useEffect(() => { stateRef.current = state }, [state])
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

  const reload = useCallback(async (fresh = false, { poll = false } = {}) => {
    if (!permsLoaded) return
    setBusy(true)
    try {
    const load = (key) => (poll && POLL_SKIP_WHEN_OK.has(key) && stateRef.current[key]?.status === 'ok'
      ? null : run(key, () => FETCHERS[key](fresh)))
    const jobs = [load('exec'), load('renewal'), loadHealth(healthTeam)]
    if (canMonitoring) jobs.push(load('overview'))
    else hide('overview')
    if (canAlerts) { jobs.push(load('noise')); jobs.push(load('teamAlerts')) } else { hide('noise'); hide('teamAlerts') }
    if (canDataQuality) jobs.push(load('dq'))
    else hide('dq')
    await Promise.allSettled(jobs)
    if (alive.current) setLoadedAt(Date.now())
    } finally {
      // Kaynak kapısı (busyFlagFinally): beklenmedik bir hata da bayrağı temizler.
      if (alive.current) setBusy(false)
    }
  }, [permsLoaded, canMonitoring, canAlerts, canDataQuality, run, hide, loadHealth, healthTeam])

  /** Bölümün "Tekrar dene"si: yalnız o kaynak, `fresh` ile (sağlık serisi seçili takımla). */
  const retry = useCallback((key) => {
    if (key === 'health') return loadHealth(healthTeam)
    const f = FETCHERS[key]
    return f ? run(key, () => f(true)) : Promise.resolve()
  }, [run, loadHealth, healthTeam])

  // İlk yükleme + izinler değişince; 5 dakikada bir görünür sekmede
  const reloadRef = useRef(reload)
  useEffect(() => { reloadRef.current = reload }, [reload])
  useEffect(() => { reload(false) }, [permsLoaded, canMonitoring, canAlerts, canDataQuality])   // eslint-disable-line react-hooks/exhaustive-deps
  useVisibleInterval(() => reloadRef.current(false, { poll: true }), 300_000, false)

  return { ...state, loadedAt, busy, reload, retry, loadHealth }
}
