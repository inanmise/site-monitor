import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../../api/client'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { errorInfoOf } from '../../utils/errorMessages.js'
import { onSessionReset } from '../../utils/sessionResetBus.js'
import { hasOpenDialog } from '../techerror/capture/panelWindow.js'
import { useTour } from '../tour/TourProvider.jsx'
import { decideAutoStart } from '../tour/tourEngine.js'
import { MAIN_STEPS } from '../tour/tourSteps.js'
import { onOpenGapsDialog } from './gapsBus.js'
import {
  AUTO_MAX_AGE_MS, AUTO_PROMPT_DELAY_MS, DEADLINE, GAPS_SESSION_KEY, GAPS_STATE_KEY, LOAD_DEADLINE_MS, TEAM_FETCH_TIMEOUT_MS,
  collectGaps, dismissState, ownTeamIds, readSession, readState, shouldAutoCheck, shouldPrompt, snoozeState, withDeadline,
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

function readSessionStore() {
  try { return readSession(localStorage.getItem(GAPS_SESSION_KEY)) } catch { return readSession(null) }
}

function writeSessionStore(patch) {
  // Oturuma bağlı (çıkışta kişisel depolamayla silinir), sekmeler arası; tercih aynasına GİRMEZ
  try { localStorage.setItem(GAPS_SESSION_KEY, JSON.stringify({ ...readSessionStore(), ...patch })) } catch { /* yok */ }
}

function promptedFor(username) {
  try { return sessionStorage.getItem(PROMPTED_KEY) === username } catch { return false }
}

function markPrompted(username) {
  try { sessionStorage.setItem(PROMPTED_KEY, username) } catch { /* yok */ }
}

/** Karşılama turu bu girişte açılacak mı / açık mı (yeni kullanıcıya önce tur; iki pencere üst üste binmesin). */
function tourBusy(t) {
  if (!t) return false
  if (t.welcome || t.active) return true
  try { return decideAutoStart(t.tourState, { steps: MAIN_STEPS }) !== null } catch { return false }
}

/**
 * Giriş sonrası eksikler penceresi (2026-11-15). Girişten kısa süre sonra kişinin profil eksiklerini ve KENDİ takımlarının
 * veri kalitesi bulgularını okur; eksik varsa, ertelenmemişse ve "bir daha gösterme" dendiğinden beri yeni bir eksik
 * çıktıysa pencereyi açar. Kullanıcı menüsündeki "Eksiklerim" her zaman açar. Her istek süre sınırlı (`timeoutMs` +
 * gövde dahil üst süre); okunamayan takım "okunamadı" olarak söylenir (eksik yok sayılmaz).
 */
export default function GapsPrompt({ username, myTeamIds, manageTeamIds, globalAdmin = false, enabled = true, prefsSettled = true }) {
  const { canView, loaded: permsLoaded = true } = usePermissions()
  const tour = useTour()
  const [open, setOpen] = useState(false)
  const [state, setState] = useState({ status: 'idle', gaps: null, more: 0, dq: 'unknown' })
  const seq = useRef(0)
  const alive = useRef(true)
  const autoFor = useRef(null)        // bu kullanıcı için kendiliğinden yol başlatıldı mı (bağımlılık değişince yinelenmez)
  const userActed = useRef(false)     // kullanıcı menüden açtı / kapattı → kendiliğinden yol pencereyi AÇMAZ
  const inputs = useRef({ myTeamIds, manageTeamIds })
  inputs.current = { myTeamIds, manageTeamIds }
  const tourRef = useRef(tour)
  tourRef.current = tour
  // Veri kalitesi yetkisi: 'yes' | 'no' | 'unknown' (yetkiler okunamadı / henüz gelmedi — "yetkiniz yok" denmez)
  const dq = !permsLoaded ? 'unknown' : canView('data_quality.view') ? 'yes' : 'no'

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
      const res = await withDeadline(api.getMe({ timeoutMs: TEAM_FETCH_TIMEOUT_MS }), LOAD_DEADLINE_MS)
      me = res && res !== DEADLINE && typeof res === 'object' && !res.error && res.success !== false ? res : null
    } catch {
      me = null
    }
    if (!alive.current || my !== seq.current) return null
    const { myTeamIds: mine, manageTeamIds: managed } = inputs.current
    const { ids, more } = ownTeamIds(me && Array.isArray(me.team_ids) ? me.team_ids : mine,
      me && Array.isArray(me.manage_team_ids) ? me.manage_team_ids : managed)
    const teams = dq === 'yes'
      ? await Promise.all(ids.map(async (id) => {
        try {
          const res = await withDeadline(api.dataQuality.team(id, false, { timeoutMs: TEAM_FETCH_TIMEOUT_MS }), LOAD_DEADLINE_MS)
          if (res === DEADLINE) return { id, failed: true }
          if (res?.success) return { id, data: res.data }
          // 404 = takım pasif / artık görünmüyor (sunucu değerlendirmez) → uygulanamaz, "okunamadı" değil
          return errorInfoOf(res)?.status === 404 ? { id, skipped: true } : { id, failed: true }
        } catch {
          return { id, failed: true }
        }
      }))
      : []
    if (!alive.current || my !== seq.current) return null
    const gaps = collectGaps({ profile: me, profileFailed: me === null, teams })
    setState({ status: 'ready', gaps, more, dq })
    return gaps
  }, [dq])

  // Kendiliğinden gösterme: girişten (bu sekmede) bir kez, yetkiler VE kişisel tercihler okununca (başka cihazda verilen
  // "gösterme" / "sonra hatırlat" kararı sunucudan gelsin), kısa beklemeden sonra. Ertelendiyse, bu oturumda kapatıldıysa
  // ya da başka sekme yakın zamanda denetlediyse AĞA ÇIKILMAZ. Karşılama turu açılacaksa / açıksa bu girişte sorulmaz.
  useEffect(() => {
    if (!enabled || !username || !permsLoaded || !prefsSettled || promptedFor(username) || autoFor.current === username) return undefined
    const timer = setTimeout(async () => {
      if (!alive.current || autoFor.current === username) return
      autoFor.current = username
      markPrompted(username)
      if (tourBusy(tourRef.current)) return
      if (!shouldAutoCheck(readStored(), readSessionStore(), Date.now())) return
      const started = Date.now()
      const gaps = await load()
      if (!gaps || !alive.current || userActed.current) return
      writeSessionStore({ checkedAt: Date.now() })
      if (Date.now() - started > AUTO_MAX_AGE_MS) return       // geç kalan okuma kullanıcının işini bölmesin
      if (!shouldPrompt(readStored(), gaps.marks, Date.now())) return
      if (tourBusy(tourRef.current) || hasOpenDialog()) return  // başka pencere açık: bu oturumda sorulmaz (menüden açılır)
      setOpen(true)
    }, AUTO_PROMPT_DELAY_MS)
    return () => clearTimeout(timer)
  }, [enabled, username, permsLoaded, prefsSettled, load])

  // Menüden açma: her zaman (erteleme / gösterme seçimi yok sayılır), taze okuma
  useEffect(() => onOpenGapsDialog(() => {
    if (!enabled) return
    userActed.current = true
    setOpen(true)
    load()
  }), [enabled, load])

  // Pencere açıkken yetki durumu değiştiyse (ör. yetkiler sonradan okundu) yeniden okunur
  useEffect(() => {
    if (open && state.status === 'ready' && state.dq !== dq) load()
  }, [open, dq, state.status, state.dq, load])

  // Çıkış / kullanıcı değişimi: bu sekmenin "gösterildi" işareti ve açık pencere sıfırlanır
  useEffect(() => onSessionReset(() => {
    try { sessionStorage.removeItem(PROMPTED_KEY) } catch { /* yok */ }
    seq.current += 1
    autoFor.current = null
    userActed.current = false
    setOpen(false)
    setState({ status: 'idle', gaps: null, more: 0, dq: 'unknown' })
  }), [])

  const close = useCallback(() => {
    userActed.current = true
    writeSessionStore({ closedAt: Date.now() })   // bu oturumda (sekmeler arası) bir süre kendiliğinden açılmaz
    setOpen(false)
  }, [])
  const snooze = useCallback(() => {
    userActed.current = true
    writeStored(snoozeState(readStored(), Date.now()))
    setOpen(false)
  }, [])
  const dismiss = useCallback(() => {
    userActed.current = true
    const g = state.gaps
    writeStored(dismissState(readStored(), g?.marks ?? [], (g?.failedTeams ?? []).map((t) => t.id)))
    setOpen(false)
  }, [state.gaps])

  if (!open) return null
  return (
    <Suspense fallback={null}>
      <GapsDialog status={state.status} gaps={state.gaps} moreTeams={state.more} dq={state.dq}
        globalAdmin={globalAdmin} username={username} onClose={close} onSnooze={snooze} onDismiss={dismiss} onRetry={load} />
    </Suspense>
  )
}
