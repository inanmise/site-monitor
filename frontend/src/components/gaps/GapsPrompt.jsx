import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../../api/client'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { onSessionReset } from '../../utils/sessionResetBus.js'
import { hasOpenDialog } from '../techerror/capture/panelWindow.js'
import { onOpenGapsDialog } from './gapsBus.js'
import {
  AUTO_PROMPT_DELAY_MS, GAPS_STATE_KEY, TEAM_FETCH_TIMEOUT_MS, collectGaps, dismissState, ownTeamIds, readState, shouldPrompt,
  snoozeState,
} from './gapsModel.js'

const GapsDialog = lazy(() => import('./GapsDialog.jsx'))

/** Bu sekmede kendiliğinden gösterme kararı verildi mi (oturum sıfırlanınca unutulur). */
const PROMPTED_KEY = 'sm.gaps.prompted'

function readStored() {
  try { return readState(localStorage.getItem(GAPS_STATE_KEY)) } catch { return readState(null) }
}

function writeStored(state) {
  // Kullanıcı tercihlerinin yerel aynası (useUserPrefs bu anahtarı sunucuya da yazar — başka cihazda da geçerli)
  try { localStorage.setItem(GAPS_STATE_KEY, JSON.stringify(state)) } catch { /* depolama yok: bu oturumda geçerli */ }
}

function promptedFor(username) {
  try { return sessionStorage.getItem(PROMPTED_KEY) === username } catch { return false }
}

function markPrompted(username) {
  try { sessionStorage.setItem(PROMPTED_KEY, username) } catch { /* yok */ }
}

/**
 * Giriş sonrası eksikler penceresi (2026-11-15). Girişten kısa süre sonra kişinin profil eksiklerini ve KENDİ takımlarının
 * veri kalitesi bulgularını okur; eksik varsa, ertelenmemişse ve "bir daha gösterme" dendiğinden beri yeni bir eksik
 * çıktıysa pencereyi açar. Kullanıcı menüsündeki "Eksiklerim" her zaman açar. Her istek süre sınırlı; okunamayan takım
 * "okunamadı" olarak söylenir (eksik yok sayılmaz).
 */
export default function GapsPrompt({ username, myTeamIds, manageTeamIds, globalAdmin = false, enabled = true, prefsSettled = true }) {
  const { canView, loaded: permsLoaded = true } = usePermissions()
  const [open, setOpen] = useState(false)
  const [state, setState] = useState({ status: 'idle', gaps: null, more: 0, dqAllowed: true })
  const seq = useRef(0)
  const alive = useRef(true)
  const inputs = useRef({ myTeamIds, manageTeamIds })
  inputs.current = { myTeamIds, manageTeamIds }
  const dqAllowed = permsLoaded && canView('data_quality.view')

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false; seq.current += 1 }
  }, [])

  const load = useCallback(async () => {
    const my = ++seq.current
    setState((s) => ({ ...s, status: 'loading' }))
    // Profil (e-posta, telefon) ve takımlar taze /me'den — giriş yanıtında e-posta / telefon yok; okunamazsa uygulamanın
    // bildiği takımlar kullanılır, profil "okunamadı" sayılır (eksik denmez)
    let me = null
    try {
      const res = await api.getMe({ timeoutMs: TEAM_FETCH_TIMEOUT_MS })
      me = res && typeof res === 'object' && !res.error && res.success !== false ? res : null
    } catch {
      me = null
    }
    if (!alive.current || my !== seq.current) return null
    const { myTeamIds: mine, manageTeamIds: managed } = inputs.current
    const { ids, more } = ownTeamIds(me && Array.isArray(me.team_ids) ? me.team_ids : mine,
      me && Array.isArray(me.manage_team_ids) ? me.manage_team_ids : managed)
    const teams = dqAllowed
      ? await Promise.all(ids.map(async (id) => {
        try {
          const res = await api.dataQuality.team(id, false, { timeoutMs: TEAM_FETCH_TIMEOUT_MS })
          return res?.success ? { id, data: res.data } : { id, failed: true }
        } catch {
          return { id, failed: true }
        }
      }))
      : []
    if (!alive.current || my !== seq.current) return null
    const gaps = collectGaps({ profile: me, profileFailed: me === null, teams })
    setState({ status: 'ready', gaps, more, dqAllowed })
    return gaps
  }, [dqAllowed])

  // Kendiliğinden gösterme: girişten (bu sekmede) bir kez, yetkiler VE kişisel tercihler okununca (başka cihazda verilen
  // "gösterme" / "sonra hatırlat" kararı sunucudan gelsin), kısa beklemeden sonra
  useEffect(() => {
    if (!enabled || !username || !permsLoaded || !prefsSettled || promptedFor(username)) return undefined
    let cancelled = false
    const timer = setTimeout(async () => {
      if (cancelled) return
      markPrompted(username)
      const gaps = await load()
      if (cancelled || !gaps) return
      if (!shouldPrompt(readStored(), gaps.keys, Date.now())) return
      if (hasOpenDialog()) return   // başka bir pencere açık: bu oturumda sorulmaz (menüden açılabilir)
      setOpen(true)
    }, AUTO_PROMPT_DELAY_MS)
    return () => { cancelled = true; clearTimeout(timer) }
  }, [enabled, username, permsLoaded, prefsSettled, load])

  // Menüden açma: her zaman (erteleme / gösterme seçimi yok sayılır), taze okuma
  useEffect(() => onOpenGapsDialog(() => {
    if (!enabled) return
    setOpen(true)
    load()
  }), [enabled, load])

  // Çıkış / kullanıcı değişimi: bu sekmenin "gösterildi" işareti ve açık pencere sıfırlanır
  useEffect(() => onSessionReset(() => {
    try { sessionStorage.removeItem(PROMPTED_KEY) } catch { /* yok */ }
    seq.current += 1
    setOpen(false)
    setState({ status: 'idle', gaps: null, more: 0, dqAllowed: true })
  }), [])

  const close = useCallback(() => setOpen(false), [])
  const snooze = useCallback(() => { writeStored(snoozeState(readStored(), Date.now())); setOpen(false) }, [])
  const dismiss = useCallback(() => {
    writeStored(dismissState(readStored(), state.gaps?.keys ?? []))
    setOpen(false)
  }, [state.gaps])

  if (!open) return null
  return (
    <Suspense fallback={null}>
      <GapsDialog status={state.status} gaps={state.gaps} moreTeams={state.more} dqAllowed={state.dqAllowed}
        globalAdmin={globalAdmin} username={username} onClose={close} onSnooze={snooze} onDismiss={dismiss} onRetry={load} />
    </Suspense>
  )
}
