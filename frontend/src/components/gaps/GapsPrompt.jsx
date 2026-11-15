import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../../api/client'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { onSessionReset } from '../../utils/sessionResetBus.js'
import { hasOpenDialog } from '../techerror/capture/panelWindow.js'
import { useTour } from '../tour/TourProvider.jsx'
import { decideAutoStart } from '../tour/tourEngine.js'
import { MAIN_STEPS } from '../tour/tourSteps.js'
import { onGapsRefreshRequest, onOpenGapsDialog, publishGapsSummary } from './gapsBus.js'
import {
  AUTO_MAX_AGE_MS, AUTO_PROMPT_DELAY_MS, GAP_CATEGORIES, GAPS_SESSION_KEY, GAPS_STATE_KEY, MAX_LOADED_TEAMS, MAX_TEAMS,
  collectGaps, dismissState, hideState, markSeen, readSession, readState, seenDiff, shouldAutoCheck, shouldPrompt, snoozeState,
  unhideState,
} from './gapsModel.js'
import { askWho } from './gapsProfile.js'
import { contextOf, loadGapSources, loadMoreTeams, teamNameOf } from './gapsSources.js'

const GapsDialog = lazy(() => import('./GapsDialog.jsx'))
const GapsFix = lazy(() => import('./GapsFix.jsx'))

/** Bu sekmede kendiliğinden gösterme kararı verildi mi (oturum sıfırlanınca unutulur). */
const PROMPTED_KEY = 'sm.gaps.prompted'
const IDLE = { status: 'idle', src: null, at: null, dq: 'unknown', diff: null, extraTeams: [], loadingMore: false }

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

/** Üç değerli yetki: 'yes' | 'no' | 'unknown' (yetkiler okunamadı / henüz gelmedi — "yetkiniz yok" denmez). */
function tri(loaded, allowed) {
  if (!loaded) return 'unknown'
  return allowed ? 'yes' : 'no'
}

/** Kaynaklardan eksikler (pencere, rozet ve Panom kutusu aynı hesabı kullanır). */
function collectFrom(src, extraTeams, username, hidden) {
  if (!src) return null
  return collectGaps({
    profile: src.me, profileFailed: src.me === null, ctx: contextOf(src, username),
    prefs: src.prefs?.data ?? null, prefsStatus: src.prefs?.status ?? 'off',
    teams: [...(src.teams || []), ...(extraTeams || [])], groups: src.groups, forwards: src.forwards, channels: src.channels,
    actions: src.actions, hidden, autoTeamIds: src.autoTeamIds,
  })
}

/** Okunamayan kapsam adları (gösterme kaydı korunsun diye) — veri kalitesi dışı kaynaklar. */
function failedScopesOf(gaps) {
  const out = []
  for (const f of gaps?.failedChecks || []) {
    const prefix = { prefs: 'pp', oncall: 'o', groups: 'g', forwards: 'f', channels: 'c', actions: 'kt' }[f.check]
    if (!prefix) continue
    if (f.teamId != null) out.push(`${prefix}${f.teamId}`)
    else if (prefix === 'pp' || prefix === 'kt') out.push(prefix)
  }
  if ((gaps?.failedChecks || []).some((f) => f.check === 'channels')) out.push('ka')
  return out
}

/**
 * Giriş sonrası eksikler penceresi (2026-11-15; zenginleştirme aynı gün). Girişten kısa süre sonra kişinin profil
 * eksiklerini, KENDİ takımlarının veri kalitesi bulgularını, veri kalitesi dışı takım eksiklerini (bildirim grubu, nöbet,
 * alarm iletimi, kanal onaylayıcısı) ve bekleyen işlerini okur; eksik varsa, ertelenmemişse ve "bir daha gösterme" dendiğinden
 * beri yeni bir eksik çıktıysa pencereyi açar. Kullanıcı menüsündeki "Eksiklerim" her zaman açar; Kişisel Panom kutusu
 * pencere açmadan okutabilir. Her istek süre sınırlı; okunamayan kaynak "okunamadı" olarak söylenir (eksik yok sayılmaz).
 */
export default function GapsPrompt({ username, myTeamIds, manageTeamIds, globalAdmin = false, enabled = true, prefsSettled = true }) {
  const { canView, canEdit, loaded: permsLoaded = true, scope } = usePermissions()
  const tour = useTour()
  const [open, setOpen] = useState(false)
  const [fix, setFix] = useState(null)              // pencereden düzeltme sürerken eksikler penceresi gizli
  const [state, setState] = useState(IDLE)
  const [stateRev, setStateRev] = useState(0)      // saklanan durum (gizlenenler) değişince yeniden hesap
  const seq = useRef(0)
  const alive = useRef(true)
  const autoFor = useRef(null)        // bu kullanıcı için kendiliğinden yol başlatıldı mı (bağımlılık değişince yinelenmez)
  const userActed = useRef(false)     // kullanıcı menüden açtı / kapattı → kendiliğinden yol pencereyi AÇMAZ
  const seenFor = useRef(null)        // "son görülen" kaydının yazıldığı okuma (her okumada bir kez)
  const inputs = useRef({ myTeamIds, manageTeamIds })
  inputs.current = { myTeamIds, manageTeamIds }
  const tourRef = useRef(tour)
  tourRef.current = tour
  const can = useMemo(() => ({
    dq: tri(permsLoaded, canView('data_quality.view')),
    groups: tri(permsLoaded, canView('notification.groups')),
    oncall: tri(permsLoaded, canView('oncall.read')),
    forwards: tri(permsLoaded, canView('alerts.forwards')),
    channels: tri(permsLoaded, canView('tech_error_reports.read') && scope?.channel_reports !== false),
    actions: tri(permsLoaded, canView('incidents.view')),
  }), [permsLoaded, canView, scope?.channel_reports])
  const canKey = Object.values(can).join(',')
  const dq = can.dq
  const canRef = useRef(can)
  canRef.current = can

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false; seq.current += 1 }
  }, [])

  const hidden = useMemo(() => readStored().hidden, [stateRev, state.src])  // eslint-disable-line react-hooks/exhaustive-deps
  const gaps = useMemo(() => collectFrom(state.src, state.extraTeams, username, hidden),
    [state.src, state.extraTeams, username, hidden])
  const ask = useMemo(() => {
    if (globalAdmin || !state.src) return null
    const dir = state.src.directory?.status === 'ok' ? state.src.directory.data : null
    return askWho(dir, state.src.me?.team_id, state.src.me?.user_id)
  }, [globalAdmin, state.src])

  // Özeti yayınla (kullanıcı menüsü rozeti, Kişisel Panom kutusu) — yalnız sayılar
  useEffect(() => {
    if (state.status === 'idle') return
    if (!gaps) { publishGapsSummary({ status: 'loading', at: state.at }); return }
    const byCategory = Object.fromEntries(GAP_CATEGORIES.map((c) => [c, gaps.byCategory[c].length]))
    const unknown = gaps.failedTeams.length > 0 || gaps.failedChecks.length > 0 || (gaps.unsupportedChecks?.length ?? 0) > 0
      || gaps.profileFailed || !gaps.profileKnown
      || dq !== 'yes' || (state.src?.moreTeams ?? 0) > 0
    publishGapsSummary({
      status: state.status === 'loading' ? 'loading' : 'ready', at: state.at, total: gaps.total,
      high: GAP_CATEGORIES.reduce((n, c) => n + gaps.byCategory[c].filter((g) => g.severity === 'HIGH').length, 0),
      byCategory, hidden: gaps.hiddenList.length, unknown, complete: gaps.total === 0 && !unknown,
      promptable: shouldPrompt(readStored(), gaps.marks, Date.now()),
    })
    writeSessionStore({ count: gaps.total })
  }, [gaps, state.status, state.at, state.src, dq, stateRev])

  const load = useCallback(async () => {
    const my = ++seq.current
    setState((s) => ({ ...s, status: 'loading' }))
    const { myTeamIds: mine, manageTeamIds: managed } = inputs.current
    const src = await loadGapSources({
      api, can: canRef.current, myTeamIds: mine, manageTeamIds: managed, globalAdmin,
      isCurrent: () => alive.current && my === seq.current,
    })
    if (!src || !alive.current || my !== seq.current) return null
    const g = collectFrom(src, [], username, readStored().hidden)
    const diff = seenDiff(readStored(), g.allMarks, g.readScopes)
    seenFor.current = null
    setState({ ...IDLE, status: 'ready', src, at: Date.now(), dq: canRef.current.dq, diff })
    return g
  }, [globalAdmin, username, canKey])  // eslint-disable-line react-hooks/exhaustive-deps

  /** Ek takımları denetle (pencerede "sonraki takımlar" / takım seçici) — toplam MAX_LOADED_TEAMS ile sınırlı. */
  const loadMore = useCallback(async (ids) => {
    const src = state.src
    if (!src || state.loadingMore) return
    const loadedIds = new Set([...(src.teams || []), ...(state.extraTeams || [])].map((t) => String(t.id)))
    const room = Math.max(0, MAX_LOADED_TEAMS - loadedIds.size)
    const want = (Array.isArray(ids) ? ids : []).filter((id) => !loadedIds.has(String(id))).slice(0, room)
    if (want.length === 0) return
    const my = seq.current
    setState((s) => ({ ...s, loadingMore: true }))
    try {
      const more = await loadMoreTeams({ api, can: canRef.current, teamIds: want, me: src.me, directory: src.directory,
        isCurrent: () => alive.current && my === seq.current })
      if (!more || !alive.current || my !== seq.current) return
      setState((s) => ({ ...s, extraTeams: [...(s.extraTeams || []), ...more] }))
    } finally {
      if (alive.current && my === seq.current) setState((s) => ({ ...s, loadingMore: false }))
    }
  }, [state.src, state.extraTeams, state.loadingMore])

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
      const g = await load()
      if (!g || !alive.current || userActed.current) return
      writeSessionStore({ checkedAt: Date.now() })
      if (Date.now() - started > AUTO_MAX_AGE_MS) return       // geç kalan okuma kullanıcının işini bölmesin
      if (!shouldPrompt(readStored(), g.marks, Date.now())) return
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

  // Kişisel Panom kutusu "Denetle": pencere açılmadan okuma (sürmekte olan okuma varsa yenisi başlamaz)
  useEffect(() => onGapsRefreshRequest(() => {
    if (!enabled || state.status === 'loading') return
    load()
  }), [enabled, load, state.status])

  // Pencere açıkken yetki durumu değiştiyse (ör. yetkiler sonradan okundu) yeniden okunur
  useEffect(() => {
    if (open && state.status === 'ready' && state.dq !== dq) load()
  }, [open, dq, state.status, state.dq, load])

  // Kullanıcı sonucu GÖRDÜ: "son görülen" kaydı bu okuma için bir kez güncellenir (Yeni / düzeldi karşılaştırmasının tabanı)
  useEffect(() => {
    if (!open || state.status !== 'ready' || !gaps || seenFor.current === state.at) return
    seenFor.current = state.at
    writeStored(markSeen(readStored(), gaps.allMarks, gaps.readScopes))
  }, [open, state.status, state.at, gaps])

  // Çıkış / kullanıcı değişimi: bu sekmenin "gösterildi" işareti, açık pencere ve yayınlanan özet sıfırlanır
  useEffect(() => onSessionReset(() => {
    try { sessionStorage.removeItem(PROMPTED_KEY) } catch { /* yok */ }
    seq.current += 1
    autoFor.current = null
    userActed.current = false
    seenFor.current = null
    setOpen(false)
    setFix(null)
    setState(IDLE)
    publishGapsSummary(null)
  }), [])

  const close = useCallback(() => {
    userActed.current = true
    writeSessionStore({ closedAt: Date.now() })   // bu oturumda (sekmeler arası) bir süre kendiliğinden açılmaz
    setOpen(false)
  }, [])
  const snooze = useCallback((ms) => {
    userActed.current = true
    writeStored(snoozeState(readStored(), Date.now(), ms))
    setStateRev((n) => n + 1)
    setOpen(false)
  }, [])
  const dismiss = useCallback(() => {
    userActed.current = true
    writeStored(dismissState(readStored(), gaps?.marks ?? [], (gaps?.failedTeams ?? []).map((t) => t.id), failedScopesOf(gaps)))
    setStateRev((n) => n + 1)
    setOpen(false)
  }, [gaps])
  const hide = useCallback((key) => { writeStored(hideState(readStored(), key)); setStateRev((n) => n + 1) }, [])
  const unhide = useCallback((key) => { writeStored(unhideState(readStored(), key)); setStateRev((n) => n + 1) }, [])

  /** Kalan (denetlenmemiş) takımlar: kimlik + ad (seçici ve "sonraki takımlar" için). */
  const restTeams = useMemo(() => {
    const src = state.src
    if (!src) return []
    const loadedIds = new Set((state.extraTeams || []).map((t) => String(t.id)))
    return (src.restTeamIds || []).filter((id) => !loadedIds.has(String(id)))
      .map((id) => ({ id, name: teamNameOf(src.me, id, src.directory) }))
  }, [state.src, state.extraTeams])

  /**
   * Pencereden düzeltme (E paketi) — yalnız o takımı YÖNETENE (global yönetici ya da yönetim kapsamı) ve ilgili düzenleme
   * izni olana: eskalasyon kişisi (`contacts.crud`), varsayılan bildirim grubu (`notification.groups`), nöbet çizelgesi
   * (`oncall.manage`). Sunucu aynı kuralları yine uygular; buradaki kapı yalnız düğmeyi çizer.
   */
  const canFix = useCallback((teamId, kind) => {
    const fresh = state.src?.me?.manage_team_ids
    const manage = Array.isArray(fresh) ? fresh : inputs.current.manageTeamIds
    const manages = globalAdmin || (Array.isArray(manage) && manage.some((x) => String(x) === String(teamId)))
    if (!manages || !permsLoaded) return false
    if (kind === 'contact') return canEdit('contacts.crud')
    if (kind === 'group') return canEdit('notification.groups')
    if (kind === 'oncall') return canEdit('oncall.manage')
    return false
  }, [globalAdmin, permsLoaded, canEdit, state.src])

  const startFix = useCallback((kind, team) => {
    if (kind && team?.id != null) setFix({ kind, team: { id: team.id, name: team.name || '' } })
  }, [])
  const endFix = useCallback(() => setFix(null), [])

  if (!open) return null
  if (fix) {
    return (
      <Suspense fallback={null}>
        <GapsFix fix={fix} onClose={endFix} onChanged={load} />
      </Suspense>
    )
  }
  const moreTeams = restTeams.length + Math.max(0, (state.src?.moreTeams ?? 0) - (state.src?.restTeamIds?.length ?? 0))
  return (
    <Suspense fallback={null}>
      <GapsDialog status={state.status === 'ready' && gaps ? 'ready' : 'loading'} gaps={gaps} diff={state.diff} at={state.at}
        moreTeams={moreTeams} restTeams={restTeams} loadingMore={state.loadingMore} maxTeams={MAX_TEAMS}
        dq={state.status === 'ready' ? state.dq : dq} globalAdmin={globalAdmin} username={username} ask={ask} canFix={canFix}
        onClose={close} onSnooze={snooze} onDismiss={dismiss} onRetry={load} onRefresh={load} onLoadMore={loadMore}
        onHide={hide} onUnhide={unhide} onFix={startFix} />
    </Suspense>
  )
}
