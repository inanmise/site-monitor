/**
 * Nöbet Rutinleri — gün sayfası (bugün ya da seçilen gün). Kullanıcının önceliği: nöbetçi işini HIZLA bitirir — tek tık
 * işaret, satır içi not / sorun, bölümü tek tıkla tamamla (geri alınabilir), "Günü tamamla" tek kısayol. İşaretten sonra
 * sayfa yenilenmez (iyimser; sunucu yanıtı sessizce eşitler), geciken / bekleyen kaydırmadan görünür.
 *
 * Yerleşim (kapsayıcı sorgusu `@container/page`): ≥ 900 px iki sütun (liste + sağda yapışkan ray: nöbetçi, ilerleme, devir,
 * sorunlar, notlar); daha darda tek sütun (nöbetçi + ilerleme üstte — 42rem ile 900 px arasında yan yana, liste, paneller
 * altta). Altta yapışkan çubuk (`useStickyBottomBar`): ilerleme + "Günü tamamla".
 * Yan yana kuralı bir ARALIKTIR (`@min-[42rem]/page:@max-[900px]/page:`): Tailwind px cinsinden kapsayıcı sorgularını rem
 * cinsinden adlandırılmışlardan (`@md`) ÖNCE üretir, "≥ 900 px tek sütun" kuralı sonradan gelen `@md` iki sütun kuralına
 * yenilip ray kartları geniş ekranda iki dar sütuna sıkışıyordu (2026-11-26).
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  CheckCheck, RotateCcw, History, ChevronsDownUp, ChevronsUpDown, CalendarX2, CircleSlash, PauseCircle, Lock, ListTodo,
  TriangleAlert, Info, RefreshCw, CircleDashed,
} from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { Switch } from '@/components/shadcn/switch'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Spinner } from '@/components/shadcn/spinner'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import { cn } from '@/lib/utils'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import { useStickyBottomBar } from '../../hooks/useStickyBottomBar.js'
import { useLatestCallback } from '../../hooks/useLatestCallback.js'
import { personalKey } from '../../utils/personalStorage.js'
import { isUnsureResult } from '../../utils/unsureResult.js'
import {
  addDays, allItems, canComplete, codeOf, computeProgress, dayWritable, findItem, handoverWritable, hmOf, isCompletedStatus, keyOf, keyboardOrder,
  mergePolled, progressState, sectionKey, serverClock, shortDate, showYesterday, stepKey, isPendingVisible, replaceItem,
  sortProblems, thrownResult, weekWithDayCounts,
} from './routineModel.js'
import { serverText, useServerNow } from './routineUi.jsx'
import { useItemActions } from './useItemActions.js'
import { useRoutineKeys } from './routineKeys.js'
import { refreshRoutineBadge } from './useRoutineBadge.js'
import TaskRow, { openAllLinks, returnFocusToRow, rowCheckSelector } from './TaskRow.jsx'
import SectionCard from './SectionCard.jsx'
import RoutineBand from './RoutineBand.jsx'
import WeekStrip from './WeekStrip.jsx'
import DayProgress from './DayProgress.jsx'
import ResponsibleCard from './ResponsibleCard.jsx'
import HandoverPanel from './HandoverPanel.jsx'
import HandoverNote, { DayNote } from './HandoverNote.jsx'
import CompleteDayDialog from './CompleteDayDialog.jsx'
import ProblemsPanel from './ProblemsPanel.jsx'

/** Bugünün sayfası yoklama aralığı (görünürken). */
export const DAY_POLL_MS = 30_000
/** Canlı sayaçlar yoklama aralığı. */
export const SIGNALS_POLL_MS = 60_000
/** Bölümü tamamla → "Geri al" süresi. */
export const UNDO_MS = 10_000
const EMPTY_KEYS = Object.freeze(new Set())

/** Kapalı bölümler kişisel depoda (`sm.or.open.<takım>` = kapalı anahtarların listesi; varsayılan açık). */
function readCollapsed(teamId) {
  try {
    const raw = globalThis.localStorage?.getItem(personalKey(`sm.or.open.${teamId}`))
    const arr = raw ? JSON.parse(raw) : []
    return new Set(Array.isArray(arr) ? arr.filter((x) => typeof x === 'string').slice(0, 200) : [])
  } catch { return new Set() }
}
function writeCollapsed(teamId, set) {
  try {
    const k = personalKey(`sm.or.open.${teamId}`)
    if (!set.size) globalThis.localStorage?.removeItem(k)
    else globalThis.localStorage?.setItem(k, JSON.stringify([...set].slice(0, 200)))
  } catch { /* depolama kapalı */ }
}

/** Sayfanın başvurduğu canlı sayaç anahtarları. */
function signalKeysOf(sheet) {
  const s = new Set()
  for (const it of allItems(sheet)) for (const k of it.signals || []) s.add(k)
  return [...s]
}

export default function DayView({
  teamId, teamName, member = false, canManage = false, date = null, pendingOnly = false, onPendingChange, onDateChange,
  onEditTemplate, focusProblemId = null, meName = null, onTodayProgress,
}) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [sheet, setSheetState] = useState(null)
  const [state, setState] = useState('loading')
  const [loadError, setLoadError] = useState(null)
  const [signals, setSignals] = useState(null)
  const [signalsLoading, setSignalsLoading] = useState(false)
  const [collapsed, setCollapsed] = useState(() => readCollapsed(teamId))
  const [panel, setPanel] = useState(null)
  const [completeOpen, setCompleteOpen] = useState(false)
  const [completedBanner, setCompletedBanner] = useState(false)
  const [bulkBusy, setBulkBusy] = useState(null)
  const [eventsFor, setEventsFor] = useState(null)
  // "Yalnız bekleyenler" açıkken az önce işaretlenen satır kaybolmaz (odak, geri alma ve ritim bozulmasın); süzgeç / gün /
  // takım değişince sıfırlanır — kapsam anahtarıyla, etkisiz (render sırasında) türetilir
  const keepScope = `${teamId}|${date || ''}|${pendingOnly ? 1 : 0}`
  const [keep, setKeep] = useState(() => ({ scope: keepScope, keys: EMPTY_KEYS }))
  const keepKeys = keep.scope === keepScope ? keep.keys : EMPTY_KEYS
  const sheetRef = useRef(null)
  const seq = useRef(0)
  const abort = useRef(null)
  const problemsRef = useRef(null)
  const bottomRef = useStickyBottomBar()

  const setSheet = useCallback((fn) => {
    setSheetState((prev) => {
      const next = typeof fn === 'function' ? fn(prev) : fn
      sheetRef.current = next
      return next
    })
  }, [])

  const clock = useMemo(() => serverClock(sheet?.now, Date.now()), [sheet?.now])
  const nowMs = useServerNow(clock)

  // ── Okuma ─────────────────────────────────────────────────────────────────────
  const actionsRef = useRef(null)
  const load = useCallback(async ({ silent = false } = {}) => {
    const my = ++seq.current
    abort.current?.abort()
    const ac = new AbortController()
    abort.current = ac
    if (!silent) { setState('loading'); setLoadError(null) }
    // Bu okuma çıktıktan SONRA yazımı biten maddeler yanıtla ezilmez (yanıt yazımdan önceki durumu taşır)
    const settleAt = actionsRef.current?.settleMark?.() ?? 0
    let res
    try { res = await api.oncallRoutines.day(teamId, date || undefined, { signal: ac.signal }) } catch (e) {
      res = thrownResult(e)
    }
    if (my !== seq.current) return null
    if (res === null) return null
    if (res?.success && res.data) {
      const next = res.data
      setSheet((prev) => (silent && prev
        ? mergePolled(prev, next, actionsRef.current?.inFlightKeys?.() || new Set(), actionsRef.current?.settledSince?.(settleAt) || null)
        : next))
      setState('ok')
      return next
    }
    if (!silent || !sheetRef.current) {
      setState('error')
      setLoadError(res)
    }
    return null
  }, [date, setSheet, teamId])

  // Bölüm tamamlamanın "Geri al" bildirimi (gün / takım değişince kapanır: eski günün görevleri yeni günle gitmesin)
  const undoToast = useRef(null)
  useEffect(() => {
    setSheetState(null)
    sheetRef.current = null
    setSignals(null)
    setPanel(null)
    setCompletedBanner(false)
    setEventsFor(null)
    if (undoToast.current) { toast.dismiss?.(undoToast.current.id); undoToast.current = null }
    load()
    return () => { abort.current?.abort() }
  }, [load]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => { setCollapsed(readCollapsed(teamId)) }, [teamId])

  const isToday = !!sheet && sheet.date === sheet.today
  useVisibleInterval(() => load({ silent: true }), isToday ? DAY_POLL_MS : 0, false)

  // ── Canlı sayaçlar ────────────────────────────────────────────────────────────
  const sigKeys = useMemo(() => signalKeysOf(sheet), [sheet])
  // Canlı sayaçlar ŞİMDİKİ durumu söyler: yalnız bugünün sayfasında okunur / gösterilir (geçmiş güne bugünün sayısı yazılmaz)
  const sigNeeded = sigKeys.length > 0 && !!sheet?.workday && !!sheet && sheet.date === sheet.today
  const sigSeq = useRef(0)
  const loadSignals = useCallback(async (fresh = false) => {
    const my = ++sigSeq.current
    setSignalsLoading(true)
    let res
    try { res = await api.oncallRoutines.signals(teamId, { fresh }) } catch { res = null } finally {
      if (my === sigSeq.current) setSignalsLoading(false)
    }
    if (my !== sigSeq.current) return
    if (res?.success) setSignals(res.data?.signals || {})
    else setSignals((prev) => {
      // okunamadı: eski değerler kalır, hiç yoksa her anahtar "okunamadı" (gri "—")
      if (prev) return prev
      const out = {}
      for (const k of sigKeys) out[k] = { value: null, error: 'unavailable' }
      return out
    })
  }, [sigKeys, teamId])
  useEffect(() => { if (sigNeeded) loadSignals(false) }, [sigNeeded, teamId]) // eslint-disable-line react-hooks/exhaustive-deps
  useVisibleInterval(() => loadSignals(false), sigNeeded ? SIGNALS_POLL_MS : 0, false)

  // ── İşaretler ─────────────────────────────────────────────────────────────────
  const editable = !!sheet?.editable && !!sheet?.can_work
  const contextDate = sheet?.date || date
  // İyimser işarette ad: verilen ad, yoksa sorumlu "benim" ise onun adı; ikisi de yoksa boş ("Siz" çipi)
  const myName = meName || (sheet?.responsible?.is_me ? sheet.responsible.name || null : null)
  const actions = useItemActions({
    teamId, contextDate, sheetRef, setSheet, me: myName,
    reload: () => load({ silent: true }),
    onDayCompleted: () => setCompletedBanner(true),
    onChanged: () => refreshRoutineBadge(),
  })
  actionsRef.current = actions

  const rememberKeep = useLatestCallback((item) => {
    if (!pendingOnly || !item) return
    const k = keyOf(item)
    setKeep((prev) => {
      const base = prev.scope === keepScope ? prev.keys : EMPTY_KEYS
      if (base.has(k) && prev.scope === keepScope) return prev
      const keys = new Set(base)
      keys.add(k)
      return { scope: keepScope, keys }
    })
  })
  const onToggle = useLatestCallback((item, state) => {
    if (!editable) return
    rememberKeep(item)
    actions.setState(item, state)
  })
  const onSkip = useLatestCallback((item, reason) => {
    if (!editable) return
    rememberKeep(item)
    actions.setState(item, 'SKIPPED', { skipReason: reason })
  })
  const onPanel = useLatestCallback((key, p) => {
    setPanel(p ? { key, panel: p } : null)
    if (!p) returnFocusToRow(key)
  })
  const onNoteSaved = useLatestCallback((key, data) => {
    const item = data?.item
    if (item) setSheet((s) => replaceItem(s, item))
  })
  const onClearNote = useLatestCallback(async (item) => {
    let res
    try {
      res = await api.oncallRoutines.saveItemNote(teamId, {
        date: contextDate, task_id: item.task_id, period_kind: item.period_kind, period_key: item.period_key,
        note: '', note_version: item.note_version || 0,
      })
    } catch (e) { res = thrownResult(e) }
    if (res === null) return
    if (res?.success && res.data?.item) setSheet((s) => replaceItem(s, res.data.item))
    else if (res?.success) setSheet((s) => replaceItem(s, { ...item, note: '', note_version: (item.note_version || 0) + 1 }))
    else toast.error(serverText(res, t, 'or.note.saveFailed'), { details: res })
  })
  const applyProblemData = useCallback((data) => {
    if (!data) return
    setSheet((s) => {
      if (!s) return s
      let next = data.item ? replaceItem(s, data.item) : s
      if (data.problem) {
        const list = (next.open_problems || []).filter((p) => p.id !== data.problem.id)
        const open = data.problem.status === 'OPEN'
        next = {
          ...next,
          open_problems: sortProblems(open ? [data.problem, ...list] : list),
          open_problems_total: Math.max(0, (next.open_problems_total ?? list.length) + (open && !(next.open_problems || []).some((p) => p.id === data.problem.id) ? 1 : 0)),
        }
      }
      return next
    })
    refreshRoutineBadge()
  }, [setSheet])
  const onProblemCreated = useLatestCallback((data) => applyProblemData(data))
  const onImagesDone = useLatestCallback((problem) => applyProblemData({ problem }))
  const onProblemChange = useCallback((problem) => {
    if (!problem) return
    setSheet((s) => {
      if (!s) return s
      const list = (s.open_problems || []).filter((p) => p.id !== problem.id)
      const wasOpen = (s.open_problems || []).some((p) => p.id === problem.id)
      const open = problem.status === 'OPEN'
      const total = (s.open_problems_total ?? list.length) + (open && !wasOpen ? 1 : 0) - (!open && wasOpen ? 1 : 0)
      return { ...s, open_problems: sortProblems(open ? [problem, ...list] : list), open_problems_total: Math.max(0, total) }
    })
    // Görev satırlarının açık sorun sayısı sunucudan (sessiz yeniden okuma)
    load({ silent: true })
  }, [load, setSheet])
  const onProblemDelete = useCallback((id) => {
    setSheet((s) => {
      if (!s) return s
      const wasOpen = (s.open_problems || []).some((p) => p.id === id)
      return { ...s, open_problems: (s.open_problems || []).filter((p) => p.id !== id),
        open_problems_total: Math.max(0, (s.open_problems_total ?? 0) - (wasOpen ? 1 : 0)) }
    })
    load({ silent: true })
  }, [load, setSheet])
  const onHistory = useLatestCallback((item) => setEventsFor({ taskId: item.task_id, title: item.title }))
  const onEditTemplateRow = useLatestCallback((item) => onEditTemplate?.(item.task_id))

  // ── Bölümü tamamla + geri al ──────────────────────────────────────────────────
  const onCompleteSection = useLatestCallback(async (kind, section) => {
    if (!editable) return
    const items = (section.items || []).filter((it) => !it.removed && isPendingVisible(it))
    if (!items.length) return
    const sk = sectionKey(kind, section.id)
    setBulkBusy(sk)
    const changed = await actions.bulk(items, 'DONE')
    setBulkBusy(null)
    if (!changed || !changed.length) return
    refreshRoutineBadge()
    const at = { team: String(teamId), date: sheetRef.current?.date || contextDate }
    const id = toast.action(t('or.sec.doneToast', changed.length, section.title), {
      label: t('or.undo'), duration: UNDO_MS,
      onClose: () => { if (undoToast.current?.id === id) undoToast.current = null },
      onAction: async () => {
        // Bildirim kapanırken gün / takım değiştiyse geri alınmaz (yeni günün bağlamıyla eski görevler gönderilmez)
        if (String(teamId) !== at.team || sheetRef.current?.date !== at.date) return
        const cur = changed.map((it) => findItem(sheetRef.current, keyOf(it))).filter((it) => it && it.state === 'DONE')
        const undone = await actions.bulk(cur, 'PENDING')
        if (undone && undone.length) toast.info(t('or.sec.undone', undone.length))
        refreshRoutineBadge()
      },
    })
    undoToast.current = id != null ? { id, ...at } : null
  })

  // ── Bölüm aç / kapat ──────────────────────────────────────────────────────────
  const onOpenChange = useCallback((kind, id, open) => {
    setCollapsed((prev) => {
      const next = new Set(prev)
      const k = sectionKey(kind, id)
      if (open) next.delete(k); else next.add(k)
      writeCollapsed(teamId, next)
      return next
    })
  }, [teamId])
  const allKeys = useMemo(() => (sheet?.groups || []).flatMap((g) => (g.sections || []).map((s) => sectionKey(g.kind, s.id))), [sheet?.groups])
  const allCollapsed = allKeys.length > 0 && allKeys.every((k) => collapsed.has(k))
  const toggleAll = () => {
    const next = allCollapsed ? new Set() : new Set(allKeys)
    writeCollapsed(teamId, next)
    setCollapsed(next)
  }

  // ── Günü tamamla / yeniden aç ─────────────────────────────────────────────────
  const progress = useMemo(() => (sheet ? computeProgress(sheet, { nowMs }) : null), [sheet, nowMs])
  const cResolved = progress ? Number(progress.done || 0) + Number(progress.skipped || 0) : null
  const cRequired = progress?.required ?? null
  const cOverdue = progress?.overdue ?? null
  const cStatus = sheet?.day?.status || null
  const dayCounts = useMemo(() => (cResolved == null ? null : { resolved: cResolved, required: cRequired, status: cStatus }),
    [cResolved, cRequired, cStatus])
  const stripWeek = useMemo(() => weekWithDayCounts(sheet?.week, sheet?.date, dayCounts), [sheet?.week, sheet?.date, dayCounts])
  const reportToday = useLatestCallback((v) => onTodayProgress?.(v))
  const sheetDate = sheet?.date
  const sheetToday = sheet?.today
  const sheetWorkday = !!sheet?.workday
  useEffect(() => {
    if (!sheetDate || sheetDate !== sheetToday || !sheetWorkday || cResolved == null) return
    reportToday({ team_id: teamId, date: sheetDate, today: { resolved: cResolved, required: cRequired, overdue: cOverdue, status: cStatus } })
  }, [teamId, sheetDate, sheetToday, sheetWorkday, cResolved, cRequired, cOverdue, cStatus, reportToday])
  const onStripSelect = useLatestCallback((d) => onDateChange?.(d === sheetRef.current?.today ? null : d))
  const onStripShift = useLatestCallback((dir) => {
    const sh = sheetRef.current
    if (!sh) return
    const n = addDays(sh.date, dir * 7)
    onDateChange?.(n >= sh.today ? null : n)
  })
  const onStripToday = useLatestCallback(() => onDateChange?.(null))
  const completed = isCompletedStatus(sheet?.day?.status)
  const canDoComplete = canComplete(sheet)
  // Alt çubuğun tonu ilerleme kartıyla aynı kural (kısmen tamamlanan gün yeşil "Tamam" değil)
  const barState = progressState(sheet, progress)
  const allResolved = !completed && Number(progress?.required) > 0 && cResolved >= Number(progress.required)
  const barAnnounce = completed
    ? (barState === 'partial' ? t('or.status.COMPLETED_PARTIAL') : t('or.bar.done'))
    : allResolved ? t('or.bar.allResolved') : ''
  const onCompleted = useCallback((data) => {
    setSheet((s) => (s ? { ...s, day: { ...s.day, ...(data?.day || {}) }, editable: false, locked_reason: 'COMPLETED', can_reopen: true } : s))
    setPanel(null)
    refreshRoutineBadge()
    load({ silent: true })
  }, [load, setSheet])
  const reopen = useCallback(async () => {
    const ok = await showConfirm({
      title: t('or.complete.reopenTitle'), message: t('or.complete.reopenBody'), confirmText: t('or.complete.reopen'), variant: 'info',
    })
    if (!ok) return
    let res
    try { res = await api.oncallRoutines.reopenDay(teamId, sheet.date, { version: sheet.day?.version ?? 0 }) } catch (e) {
      res = thrownResult(e)
    }
    if (res === null) return
    if (res?.success) { toast.success(t('or.complete.reopened')); setCompletedBanner(false); load({ silent: false }); refreshRoutineBadge(); return }
    if (isUnsureResult(res)) { load({ silent: true }); toast.info(t('or.task.unsure')); return }
    toast.error(serverText(res, t, 'or.complete.reopenFailed'), { details: res })
    if (codeOf(res) === 'CONCURRENT_MODIFICATION') load({ silent: true })
  }, [load, sheet, showConfirm, t, teamId, toast])

  // ── Klavye ────────────────────────────────────────────────────────────────────
  const order = useMemo(() => keyboardOrder(sheet, { pendingOnly, collapsed, keep: keepKeys }), [sheet, pendingOnly, collapsed, keepKeys])
  const focusRow = (key) => {
    if (!key) return
    const el = document.querySelector(rowCheckSelector(key))
    if (el) { el.focus(); el.scrollIntoView?.({ block: 'nearest' }) }
  }
  const keyActions = useRef(null)
  keyActions.current = {
    next: (k) => focusRow(stepKey(order, k, 1)),
    prev: (k) => focusRow(stepKey(order, k, -1)),
    toggle: (k) => { const it = k && findItem(sheetRef.current, k); if (!it || !editable) return false; onToggle(it, it.state === 'DONE' ? 'PENDING' : 'DONE') },
    note: (k) => { if (!k) return false; setPanel({ key: k, panel: 'note' }) },
    problem: (k) => { if (!k || !editable) return false; setPanel({ key: k, panel: 'problem' }) },
    skip: (k) => { if (!k || !editable) return false; setPanel({ key: k, panel: 'skip' }) },
    links: (k) => { const it = k && findItem(sheetRef.current, k); if (!it?.links?.length) return false; openAllLinks(it.links) },
    section: (k) => {
      if (!k) return false
      for (const g of sheetRef.current?.groups || []) for (const s of g.sections || []) {
        if ((s.items || []).some((it) => keyOf(it) === k)) { onCompleteSection(g.kind, s); return }
      }
      return false
    },
    complete: () => { if (!canDoComplete) return false; setCompleteOpen(true) },
    dayPrev: () => { if (!sheet) return false; onDateChange?.(addDays(sheet.date, -1)) },
    dayNext: () => { if (!sheet || sheet.date >= sheet.today) return false; const n = addDays(sheet.date, 1); onDateChange?.(n === sheet.today ? null : n) },
    today: () => { if (!sheet || sheet.date === sheet.today) return false; onDateChange?.(null) },
  }
  useRoutineKeys(state === 'ok' && !completeOpen, keyActions)

  // ── Satır çizimi ──────────────────────────────────────────────────────────────
  const liveSignals = !!sheet && sheet.date === sheet.today
  const renderRow = useCallback((item) => {
    const key = keyOf(item)
    return (
      <TaskRow key={key} item={item} today={sheet?.today} nowMs={nowMs} contextDate={contextDate} teamId={teamId}
        editable={editable} canEditTemplate={!!sheet?.can_edit_template} signals={liveSignals ? signals : null}
        signalsLoading={liveSignals && signalsLoading} showSignals={liveSignals}
        panel={panel?.key === key ? panel.panel : null}
        onToggle={onToggle} onSkip={onSkip} onPanel={onPanel} onNoteSaved={onNoteSaved} onClearNote={onClearNote}
        onProblemCreated={onProblemCreated} onImagesDone={onImagesDone} onHistory={onHistory} onEditTemplate={onEditTemplateRow} />
    )
  }, [sheet?.today, sheet?.can_edit_template, nowMs, contextDate, teamId, editable, signals, signalsLoading, panel, liveSignals,
    onToggle, onSkip, onPanel, onNoteSaved, onClearNote, onProblemCreated, onImagesDone, onHistory, onEditTemplateRow])

  // ── Durumlar ──────────────────────────────────────────────────────────────────
  if (state === 'loading' && !sheet) return <DaySkeleton />
  if (state === 'error' && !sheet) {
    const code = codeOf(loadError)
    return (
      <div data-slot="or-load-failed"><StatusBlock tone="danger" icon={TriangleAlert} role="alert" details={loadError}
        title={t('or.day.loadFailedTitle')}
        description={serverText(loadError, t, code === 'NETWORK_ERROR' ? 'or.day.loadFailedNetwork' : 'or.day.loadFailed')}
        actions={<Button type="button" onClick={() => load()} data-slot="or-load-retry"><RefreshCw aria-hidden="true" />{t('or.retry')}</Button>}
        className="rounded-xl border" /></div>
    )
  }
  if (!sheet) return null

  if (sheet.configured === false || sheet.locked_reason === 'NOT_CONFIGURED') {
    return (
      <StatusBlock icon={ListTodo} title={t('or.day.notConfiguredTitle')} description={sheet.can_edit_template ? t('or.day.notConfiguredEdit') : t('or.day.notConfigured')}
        actions={sheet.can_edit_template ? <Button type="button" onClick={() => onEditTemplate?.(null)} data-slot="or-create-template">{t('or.day.createTemplate')}</Button> : null}
        className="rounded-xl border" />
    )
  }

  const weekStrip = (
    <WeekStrip week={stripWeek} date={sheet.date} today={sheet.today}
      onSelect={onStripSelect} onShiftWeek={onStripShift} onToday={onStripToday} />
  )
  const lockBanner = <LockBanner sheet={sheet} onToday={() => onDateChange?.(null)} />
  const prev = sheet.previous_workday
  const groups = (sheet.groups || []).filter((g) => (g.sections || []).some((s) => (s.items || []).length))
  const rail = (
    <>
      <div data-slot="or-rail-cards" className="grid gap-3 @min-[42rem]/page:@max-[900px]/page:grid-cols-2">
        <ResponsibleCard teamId={teamId} teamName={teamName} sheet={sheet} member={member} canManage={canManage}
          onChanged={() => { load({ silent: true }); refreshRoutineBadge() }} />
        <DayProgress sheet={sheet} progress={progress} canComplete={canDoComplete} canReopen={!!sheet.can_reopen}
          onComplete={() => setCompleteOpen(true)} onReopen={reopen} />
      </div>
      {sheet.handover?.show && (
        <HandoverPanel teamId={teamId} handover={sheet.handover} isResponsibleMe={!!sheet.responsible?.is_me}
          onAcked={(h) => setSheet((s) => (s ? { ...s, handover: h } : s))}
          onShowProblems={() => problemsRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })} />
      )}
    </>
  )
  const panels = (
    <>
      <div ref={problemsRef}>
        <ProblemsPanel teamId={teamId} teamName={teamName} date={sheet.date} problems={sheet.open_problems || []}
          total={sheet.open_problems_total ?? (sheet.open_problems || []).length} canWork={!!sheet.can_work}
          canCreate={dayWritable(sheet) && sheet.workday !== false}
          focusProblemId={focusProblemId} onProblemCreated={applyProblemData} onProblemChange={onProblemChange} onProblemDelete={onProblemDelete} />
      </div>
      {sheet.workday && (
        <DayNote teamId={teamId} date={sheet.date} day={sheet.day} canWrite={dayWritable(sheet)}
          onSaved={(d) => { const dd = d?.day || d; if (dd) setSheet((s) => (s ? { ...s, day: { ...s.day, note: dd.note ?? s.day.note, note_version: dd.note_version ?? s.day.note_version, note_by_name: dd.note_by_name ?? s.day.note_by_name, note_at: dd.note_at ?? s.day.note_at } } : s)) }} />
      )}
      {sheet.handover_current && (
        <HandoverNote teamId={teamId} handoverCurrent={sheet.handover_current}
          canWrite={!!sheet.can_work && handoverWritable(sheet.handover_current, sheet.today)}
          onSaved={(d) => { const h = d?.handover || d; if (h) setSheet((s) => (s ? { ...s, handover_current: { ...s.handover_current, ...h } } : s)) }} />
      )}
    </>
  )

  return (
    <div data-slot="or-day" data-date={sheet.date} data-status={sheet.day?.status || 'NONE'} className="@container/page flex flex-col gap-4 pb-2">
      {weekStrip}
      {/* Tek kopya, yerleşimi ızgara belirler: dar ekranda ray → liste → paneller; ≥ 900 px liste solda iki satıra yayılır,
          ray sağ üstte, paneller hemen altında. (Ray iki kez çizilseydi not editörü ve sorun paneli ikişer kez bağlanırdı.) */}
      <div className="grid gap-4 @min-[900px]/page:grid-cols-[minmax(0,1fr)_minmax(18rem,22rem)] @min-[900px]/page:grid-rows-[auto_1fr]">
        <div data-slot="or-rail" className="flex min-w-0 flex-col gap-3 @min-[900px]/page:col-start-2 @min-[900px]/page:row-start-1">{rail}</div>
        <div className="flex min-w-0 flex-col gap-4 @min-[900px]/page:col-start-1 @min-[900px]/page:row-span-2 @min-[900px]/page:row-start-1">
          {completedBanner && (
            <AlertBanner tone="warning" className="mb-0" onDismiss={() => setCompletedBanner(false)} dismissLabel={t('or.close')}>
              {t('or.day.completedMeanwhile')}
            </AlertBanner>
          )}
          {lockBanner}
          {showYesterday(prev) && sheet.date === sheet.today && (
            <AlertBanner tone="warning" className="mb-0"
              title={t('or.day.yesterdayTitle', shortDate(prev.date), prev.pending_total)}
              actions={<Button type="button" size="sm" variant="outline" data-slot="or-yesterday-open" className="pointer-coarse:h-10" onClick={() => onDateChange?.(prev.date)}>{t('or.day.yesterdayOpen')}</Button>}>
              <span data-slot="or-yesterday">
                {(prev.pending || []).slice(0, 3).map((p) => p.title).join(' · ')}
                {prev.pending_total > 3 ? ` ${t('or.day.andMore', prev.pending_total - 3)}` : ''}
              </span>
            </AlertBanner>
          )}

          {sheet.workday === false ? (
            <StatusBlock icon={CalendarX2} title={sheet.holiday_name ? t('or.day.holiday', sheet.holiday_name) : t('or.day.notWorkday')}
              description={t('or.day.notWorkdayHint')} className="rounded-xl border" />
          ) : sheet.recorded === false ? (
            <StatusBlock icon={CircleSlash} title={t('or.day.notRecordedTitle')} description={t('or.day.notRecorded')} className="rounded-xl border" />
          ) : (
            <>
              <div className="flex flex-wrap items-center justify-between gap-2" data-slot="or-controls">
                <label className="inline-flex min-h-9 cursor-pointer items-center gap-2 text-sm pointer-coarse:min-h-10">
                  <Switch checked={pendingOnly} onCheckedChange={(v) => onPendingChange?.(v === true)} data-slot="or-pending-toggle" />
                  {t('or.day.pendingOnly')}
                </label>
                <div className="flex flex-wrap items-center gap-1">
                  <Button type="button" variant="ghost" size="sm" className="h-8 gap-1.5 pointer-coarse:h-10" data-slot="or-day-history"
                    onClick={() => setEventsFor({ taskId: null, title: null })}>
                    <History aria-hidden="true" />{t('or.day.history')}
                  </Button>
                  {allKeys.length > 1 && (
                    <Button type="button" variant="ghost" size="sm" className="h-8 gap-1.5 pointer-coarse:h-10" data-slot="or-sections-all" onClick={toggleAll}>
                      {allCollapsed ? <ChevronsUpDown aria-hidden="true" /> : <ChevronsDownUp aria-hidden="true" />}
                      {allCollapsed ? t('or.day.expandAll') : t('or.day.collapseAll')}
                    </Button>
                  )}
                </div>
              </div>
              {groups.length === 0 ? (
                <StatusBlock icon={Info} title={t('or.day.emptyTitle')} description={t('or.day.empty')} className="rounded-xl border" />
              ) : groups.map((g) => (
                <RoutineBand key={g.kind} group={g} date={sheet.date}>
                  {(g.sections || []).filter((s) => (s.items || []).length).map((s) => (
                    <SectionCard key={s.id} kind={g.kind} section={s} date={sheet.date} today={sheet.today} nowMs={nowMs}
                      editable={editable} open={!collapsed.has(sectionKey(g.kind, s.id))} onOpenChange={onOpenChange}
                      pendingOnly={pendingOnly} keepKeys={keepKeys} busy={bulkBusy === sectionKey(g.kind, s.id)}
                      onCompleteSection={onCompleteSection} renderRow={renderRow} />
                  ))}
                </RoutineBand>
              ))}
            </>
          )}
        </div>
        <div data-slot="or-panels" className="flex min-w-0 flex-col gap-3 self-start @min-[900px]/page:col-start-2 @min-[900px]/page:row-start-2">{panels}</div>
      </div>

      {sheet.workday !== false && sheet.recorded !== false && (canDoComplete || completed) && (
        <div ref={bottomRef} data-slot="or-bottom-bar"
          data-state={barState}
          className="sticky bottom-0 z-10 -mx-1 flex flex-wrap items-center justify-between gap-3 rounded-xl border bg-card/95 px-4 pt-2.5 pb-[max(0.625rem,env(safe-area-inset-bottom))] shadow-sm backdrop-blur supports-[backdrop-filter]:bg-card/80">
          {/* Ekran okuyucuya yalnız durum değişince söylenir (her tikte "x/18" okunmaz) */}
          <span className="sr-only" aria-live="polite" data-slot="or-bar-live">{barAnnounce}</span>
          <p className="m-0 text-sm tabular-nums">
            {completed
              ? t('or.bar.completed', sheet.day?.completed_by_name || '—', hmOf(sheet.day?.completed_at) || '')
              : t('or.bar.progress', (progress?.done || 0) + (progress?.skipped || 0), progress?.required ?? 0)}
            {!completed && Number(progress?.overdue) > 0 && (
              <span className="ml-2 font-medium text-destructive">{t('or.bar.overdue', progress.overdue)}</span>
            )}
          </p>
          {completed ? (
            <div className="flex items-center gap-2">
              <span data-slot="or-bar-state" data-state={barState}
                className={cn('inline-flex items-center gap-1 text-sm font-medium',
                  barState === 'partial' ? 'text-amber-700 dark:text-amber-300' : 'text-success')}>
                {barState === 'partial' ? <CircleDashed aria-hidden="true" className="size-4" /> : <CheckCheck aria-hidden="true" className="size-4" />}
                {barState === 'partial' ? t('or.status.COMPLETED_PARTIAL') : t('or.bar.done')}
              </span>
              {sheet.can_reopen && (
                <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" data-slot="or-reopen" onClick={reopen}>
                  <RotateCcw aria-hidden="true" />{t('or.complete.reopen')}
                </Button>
              )}
            </div>
          ) : (
            <Button type="button" data-slot="or-complete" disabled={!canDoComplete} onClick={() => setCompleteOpen(true)}
              aria-keyshortcuts="c" className="pointer-coarse:h-10">
              <CheckCheck aria-hidden="true" />{t('or.complete.button')}
            </Button>
          )}
        </div>
      )}

      {completeOpen && (
        <CompleteDayDialog open={completeOpen} onClose={() => setCompleteOpen(false)} teamId={teamId} sheet={sheet} progress={progress}
          onCompleted={onCompleted} onReload={() => load({ silent: true })} />
      )}
      <DayEventsSheet teamId={teamId} date={sheet.date} filter={eventsFor} onClose={() => setEventsFor(null)} />
    </div>
  )
}

function LockBanner({ sheet, onToday }) {
  const t = useT()
  const r = sheet.locked_reason
  // Tamamlanan gün alt çubukta / ilerleme kartında söylenir (yeniden aç orada); burada yalnız kilit nedenleri
  if (!r || r === 'NOT_CONFIGURED' || r === 'NO_PERMISSION' || r === 'NOT_WORKDAY' || r === 'COMPLETED') return null
  const icon = r === 'PAUSED' ? PauseCircle : Lock
  return (
    <AlertBanner tone={r === 'PAUSED' ? 'warning' : 'info'} icon={icon} className="mb-0"
      actions={r === 'FUTURE' || r === 'TOO_OLD'
        ? <Button type="button" size="sm" variant="outline" className="pointer-coarse:h-10" onClick={onToday}>{t('or.week.today')}</Button>
        : null}>
      <span data-slot="or-locked" data-reason={r}>{t(`or.lock.${r}`)}</span>
    </AlertBanner>
  )
}

function DaySkeleton() {
  return (
    <div data-slot="or-day-loading" className="flex flex-col gap-4" aria-busy="true">
      <Skeleton className="h-24 w-full rounded-xl" />
      <div className="grid gap-3 md:grid-cols-2">
        <Skeleton className="h-28 rounded-xl" />
        <Skeleton className="h-28 rounded-xl" />
      </div>
      {[0, 1, 2].map((i) => <Skeleton key={i} className="h-40 w-full rounded-xl" />)}
    </div>
  )
}

/** "Günün geçmişi" / "Görevin geçmişi" — olay listesi (kim ne zaman ne yaptı), daha eskiler için "Daha fazla". */
function DayEventsSheet({ teamId, date, filter, onClose }) {
  const t = useT()
  const open = !!filter
  const [items, setItems] = useState([])
  const [more, setMore] = useState(false)
  const [state, setState] = useState('idle')
  const seq = useRef(0)
  const fetchPage = useCallback(async (before = null) => {
    const my = ++seq.current
    setState('loading')
    let res
    try { res = await api.oncallRoutines.events(teamId, { date, before: before || undefined, limit: 100 }) } catch { res = { success: false } }
    if (my !== seq.current || res === null) return
    if (res?.success) {
      const page = res.data?.items || []
      setItems((prev) => (before ? [...prev, ...page] : page))
      setMore(!!res.data?.has_more)
      setState('ok')
    } else setState('error')
  }, [date, teamId])
  // Gün / takım değişti (panel kapalıyken de): eski günün olayları bir sonraki açılışa taşınmaz. Açılış okumasından ÖNCE
  // bildirilir (etkiler bildirim sırasıyla çalışır): açıkken gün değişirse sıfırlama yeni okumayı düşürmez.
  useEffect(() => { seq.current += 1; setItems([]); setMore(false); setState('idle') }, [date, teamId])
  useEffect(() => { if (open) { setItems([]); setMore(false); fetchPage(null) } }, [open, fetchPage])
  const shown = filter?.taskId ? items.filter((e) => e.task_id === filter.taskId) : items
  return (
    <Sheet open={open} onOpenChange={(v) => { if (!v) onClose() }}>
      <SheetContent side="right" className="w-full gap-0 sm:max-w-md">
        <SheetHeader className="border-b">
          <SheetTitle className="[overflow-wrap:anywhere]">{filter?.title ? t('or.ev.taskTitle', filter.title) : t('or.ev.dayTitle', shortDate(date, true))}</SheetTitle>
          <SheetDescription>{t('or.ev.description')}</SheetDescription>
        </SheetHeader>
        <div className="flex-1 overflow-y-auto px-4 py-3" data-slot="or-events">
          {state === 'error' && !items.length ? (
            <StatusBlock tone="danger" title={t('or.ev.loadFailedTitle')} description={t('or.ev.loadFailed')}
              actions={<Button type="button" size="sm" className="pointer-coarse:h-10" onClick={() => fetchPage(null)}>{t('or.retry')}</Button>} />
          ) : state === 'loading' && !items.length ? (
            <div className="flex justify-center py-8"><Spinner /></div>
          ) : shown.length === 0 ? (
            <p className="m-0 py-6 text-center text-sm text-muted-foreground">{t('or.ev.empty')}</p>
          ) : (
            <ol className="m-0 flex list-none flex-col gap-0 p-0">
              {shown.map((e) => (
                <li key={e.id} data-slot="or-event" data-kind={e.kind} className="flex gap-3 border-b py-2.5 last:border-b-0">
                  <span className="w-12 shrink-0 pt-0.5 text-xs text-muted-foreground tabular-nums">{hmOf(e.at) || ''}</span>
                  <div className="min-w-0 flex-1 text-sm">
                    <p className="m-0 [overflow-wrap:anywhere]"><span className="font-medium">{e.actor_name || t('or.ev.system')}</span> {t(`or.ev.${e.kind}`)}</p>
                    {e.task_title && <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{e.task_title}</p>}
                    {e.detail?.reason && <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">“{e.detail.reason}”</p>}
                  </div>
                </li>
              ))}
            </ol>
          )}
          {more && (
            <Button type="button" variant="outline" size="sm" className={cn('mt-3 w-full pointer-coarse:h-10')} disabled={state === 'loading'}
              onClick={() => fetchPage(items[items.length - 1]?.id)}>
              {state === 'loading' && <Spinner />}{t('or.ev.more')}
            </Button>
          )}
        </div>
      </SheetContent>
    </Sheet>
  )
}
