/**
 * Nöbet Rutinleri sayfası (`?tab=oncallroutines`) — kabuk: başlık, takım seçici, görünüm anahtarı (Bugün · Hafta · Geçmiş ·
 * İstatistik · Şablon · Ayarlar), adres eşitleme (`or_*`), kısayol bağlamı. Asıl iş görünümlerdedir; kabuk yalnız hangi
 * takımın hangi görünümünün açık olduğunu tutar.
 *
 * Adres: `or_team` (varsayılandan farklıysa), `or_view` (bugün yazılmaz), `or_day`, `or_week`, `or_pending=1`, `or_from` /
 * `or_to`, `or_problem`. Takım değişince görünüm kalır, gün / hafta düşer. Kaydedilmemiş şablon / ayar varken görünüm ya da
 * takım değiştirmek önce sorar.
 */
import { lazy, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ListTodo, CalendarCheck2, CalendarRange, History, BarChart3, ClipboardList, Settings2, TriangleAlert, RefreshCw, EyeOff, Lock,
  Users,
} from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import PageHeader from '../ui/PageHeader.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import LazySection from '../ui/LazySection.jsx'
import { useUrlQuerySync } from '../../hooks/useUrlQuerySync.js'
import { registerShortcutContext, OR_PAGE_CONTEXT } from '../../utils/shortcutContexts.js'
import { hasUnsavedWork } from '../../utils/unsavedWork.js'
import { VIEWS, clockOffsetOf, codeOf, parseUrlState, thrownResult } from './routineModel.js'
import { DayStatusBadge, serverText } from './routineUi.jsx'
import DayView from './DayView.jsx'
import WeekView from './WeekView.jsx'
import HistoryView from './HistoryView.jsx'
import RoutineStats from './RoutineStats.jsx'
import RoutineTeamSettings from './RoutineTeamSettings.jsx'

const RoutineTemplateEditor = lazy(() => import('./RoutineTemplateEditor.jsx'))

const VIEW_ICONS = {
  today: CalendarCheck2, week: CalendarRange, history: History, stats: BarChart3, template: ClipboardList, settings: Settings2,
}

/** Takım listesinde seçili takım: adresteki (listede varsa) → sunucunun varsayılanı → ilk takım. */
export function pickTeam(overview, wanted) {
  const teams = Array.isArray(overview?.teams) ? overview.teams : []
  if (!teams.length) return null
  const byId = (id) => teams.find((x) => String(x.team_id) === String(id)) || null
  return byId(wanted) || byId(overview?.default_team_id) || teams[0]
}

export default function OnCallRoutinesPage({ meName = null }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const initial = useMemo(() => parseUrlState(typeof window !== 'undefined' ? window.location.search : ''), [])
  const [overview, setOverview] = useState(null)
  const [ovState, setOvState] = useState('loading')
  const [ovErr, setOvErr] = useState(null)
  const [teamId, setTeamId] = useState(initial.team)
  const [view, setView] = useState(initial.view)
  const [day, setDay] = useState(initial.day)
  const [week, setWeek] = useState(initial.week)
  const [pending, setPending] = useState(initial.pending)
  const [range, setRange] = useState({ from: initial.from, to: initial.to })
  const [problem, setProblem] = useState(initial.problem)
  const [tplFocus, setTplFocus] = useState(null)
  const [tplDirty, setTplDirty] = useState(false)
  // Sunucu saati − tarayıcı saati (ms): genel bakış yanıtının anından. İstatistik / geçmiş "bugün"ü bununla İstanbul günü
  // olarak hesaplar (tarayıcı saati kaymış olabilir); bilinmiyorsa 0 (İstanbul günü, tarayıcı saatiyle).
  const [clockOffsetMs, setClockOffsetMs] = useState(0)
  // Gün sayfasının canlı bugün özeti (tikten sonra): başlıktaki sayı genel bakışın bağlanma anındaki değerinde kalmasın
  const [liveToday, setLiveToday] = useState(null)
  const seq = useRef(0)

  useEffect(() => registerShortcutContext(OR_PAGE_CONTEXT), [])

  const loadOverview = useCallback(async ({ silent = false } = {}) => {
    const my = ++seq.current
    if (!silent) { setOvState('loading'); setOvErr(null) }
    let res
    try { res = await api.oncallRoutines.overview() } catch (e) { res = thrownResult(e) }
    if (my !== seq.current || res === null) return
    if (res?.success && res.data) {
      const off = clockOffsetOf(res.data.generated_at || res.timestamp)
      if (off != null) setClockOffsetMs(off)
      setOverview(res.data); setOvState('ok'); return
    }
    if (!silent) { setOvState('error'); setOvErr(res) }
  }, [])
  useEffect(() => { loadOverview() }, [loadOverview])

  const baseTeam = useMemo(() => pickTeam(overview, teamId), [overview, teamId])
  const team = useMemo(() => {
    if (!baseTeam?.today || !liveToday) return baseTeam
    if (String(liveToday.team_id) !== String(baseTeam.team_id) || liveToday.date !== baseTeam.today.date) return baseTeam
    return { ...baseTeam, today: { ...baseTeam.today, ...liveToday.today } }
  }, [baseTeam, liveToday])
  const tid = team?.team_id ?? null
  const defaultTid = overview?.default_team_id ?? overview?.teams?.[0]?.team_id ?? null

  useUrlQuerySync({
    // Genel bakış okunana dek adresteki takım korunur (yükleme sırasında silinip yanlış takıma düşülmesin)
    or_team: overview == null
      ? (teamId != null ? String(teamId) : null)
      : (tid != null && String(tid) !== String(defaultTid) ? String(tid) : null),
    or_view: view === 'today' ? null : view,
    or_day: view === 'today' ? day : null,
    or_week: view === 'week' ? week : null,
    or_pending: pending ? '1' : null,
    or_from: view === 'history' || view === 'stats' ? range.from : null,
    or_to: view === 'history' || view === 'stats' ? range.to : null,
    or_problem: view === 'today' ? problem : null,
  })

  /** Kaydedilmemiş şablon / ayar varken önce sorar; true = devam. */
  const confirmLeave = useCallback(async () => {
    if (!(tplDirty || hasUnsavedWork())) return true
    return showConfirm({
      title: t('or.page.leaveTitle'), message: t('or.page.leaveBody'), confirmText: t('or.page.leaveConfirm'), variant: 'warning',
    })
  }, [showConfirm, t, tplDirty])

  const changeView = useCallback(async (v) => {
    if (!VIEWS.includes(v) || v === view) return
    if ((view === 'template' || view === 'settings') && !(await confirmLeave())) return
    setTplDirty(false)
    if (v !== 'template') setTplFocus(null)
    setView(v)
  }, [confirmLeave, view])

  const changeTeam = useCallback(async (id) => {
    if (id == null || String(id) === String(tid)) return
    if ((view === 'template' || view === 'settings') && !(await confirmLeave())) return
    setTplDirty(false)
    setTplFocus(null)
    setTeamId(String(id))
    setDay(null)
    setWeek(null)
    setProblem(null)
  }, [confirmLeave, tid, view])

  const openDay = useCallback((date) => {
    const today = team?.today?.date
    setDay(date && date !== today ? date : null)
    setView('today')
  }, [team?.today?.date])

  const editTemplate = useCallback((taskId) => {
    setTplFocus(taskId || null)
    setView('template')
  }, [])

  // ── Durumlar ────────────────────────────────────────────────────────────────
  const header = (
    <div data-slot="or-header" className="min-w-0">
    <PageHeader icon={ListTodo} title={t('or.page.title')} description={t('or.page.desc')} className="mb-0"
      meta={team?.today?.workday && team?.configured ? (
        <span className="inline-flex flex-wrap items-center gap-2 text-xs text-muted-foreground" data-slot="or-header-today">
          <DayStatusBadge status={team.today.status || 'NONE'} />
          {team.today.required != null && <span className="tabular-nums">{t('or.page.todayCount', team.today.resolved ?? 0, team.today.required)}</span>}
          {Number(team.today.overdue) > 0 && <span className="font-medium text-destructive">{t('or.bar.overdue', team.today.overdue)}</span>}
        </span>
      ) : null}
      actions={overview?.teams?.length > 1 ? (
        <div className="w-full min-w-0 sm:w-72" data-slot="or-team-picker">
          <span id="or-team-picker-label" className="sr-only">{t('or.page.team')}</span>
          <SearchableSelect value={tid != null ? String(tid) : ''} wrapLabels ariaLabelledBy="or-team-picker-label"
            onChange={(v) => changeTeam(v)} placeholder={t('or.page.teamPlaceholder')}
            options={overview.teams.map((x) => ({ value: String(x.team_id), label: x.team_name || t('or.page.teamUnnamed', x.team_id) }))} />
          {overview.truncated === true && (
            <p className="m-0 mt-1 text-xs text-muted-foreground" data-slot="or-teams-truncated">{t('or.page.teamsTruncated', overview.teams.length)}</p>
          )}
        </div>
      ) : null} />
    </div>
  )

  if (ovState === 'loading' && !overview) {
    return (
      <div data-slot="or-page" className="flex flex-col gap-4">
        {header}
        <div className="flex flex-col gap-3" aria-busy="true"><Skeleton className="h-10 w-full max-w-xl" /><Skeleton className="h-64 w-full rounded-xl" /></div>
      </div>
    )
  }
  if (ovState === 'error' && !overview) {
    const code = codeOf(ovErr)
    const noAccess = code === 'ROUTINE_MODULE_HIDDEN' || code === 'ACCESS_DENIED' || code === 'FORBIDDEN'
    return (
      <div data-slot="or-page" className="flex flex-col gap-4">
        {header}
        {noAccess ? (
          <StatusBlock icon={EyeOff} title={t('or.page.noAccessTitle')} description={serverText(ovErr, t, 'or.page.noAccess')} className="rounded-xl border" />
        ) : (
          <div data-slot="or-load-failed">
            <StatusBlock tone="danger" icon={TriangleAlert} role="alert" details={ovErr} className="rounded-xl border"
              title={t('or.page.loadFailedTitle')} description={serverText(ovErr, t, 'or.page.loadFailed')}
              actions={<Button type="button" onClick={() => loadOverview()} data-slot="or-load-retry"><RefreshCw aria-hidden="true" />{t('or.retry')}</Button>} />
          </div>
        )}
      </div>
    )
  }
  if (overview && overview.module_visible === false) {
    return (
      <div data-slot="or-page" className="flex flex-col gap-4">
        {header}
        <StatusBlock icon={EyeOff} title={t('or.page.hiddenTitle')} description={t('or.page.hidden')} className="rounded-xl border" />
      </div>
    )
  }
  if (!team) {
    return (
      <div data-slot="or-page" className="flex flex-col gap-4">
        {header}
        <div data-slot="or-no-teams">
          <StatusBlock icon={Users} title={t('or.page.noTeamsTitle')} description={t('or.page.noTeams')} className="rounded-xl border" />
        </div>
      </div>
    )
  }

  const viewOptions = VIEWS.map((v) => ({ value: v, label: t(`or.view.${v}`), icon: VIEW_ICONS[v] }))
  const canEditTemplate = !!team.can_edit_template

  return (
    <div data-slot="or-page" data-team={tid} data-view={view} className="flex min-w-0 flex-col gap-4">
      {header}
      <div className="flex min-w-0 flex-col gap-2" data-slot="or-view-switch-wrap">
        <div className="hidden sm:block">
          <SegmentedControl value={view} onChange={changeView} options={viewOptions} ariaLabel={t('or.page.viewSwitch')}
            className="flex-wrap" itemClassName="pointer-coarse:h-10" />
        </div>
        <div className="sm:hidden">
          <label htmlFor="or-view-select" className="sr-only">{t('or.page.viewSwitch')}</label>
          <NativeSelect id="or-view-select" data-slot="or-view-select" value={view} onChange={(e) => changeView(e.target.value)} className="w-full">
            {viewOptions.map((o) => <NativeSelectOption key={o.value} value={o.value}>{o.label}</NativeSelectOption>)}
          </NativeSelect>
        </div>
      </div>

      {!team.can_work && (view === 'today' || view === 'week') && (
        <AlertBanner tone="info" icon={Lock} className="mb-0"><span data-slot="or-readonly">{t('or.page.readOnly')}</span></AlertBanner>
      )}

      {view === 'today' && (
        <DayView key={`d-${tid}`} teamId={tid} teamName={team.team_name} member={!!team.member} canManage={!!team.can_manage}
          date={day} pendingOnly={pending} onPendingChange={setPending} onDateChange={setDay}
          onEditTemplate={canEditTemplate ? editTemplate : undefined} focusProblemId={problem} meName={meName}
          onTodayProgress={setLiveToday} />
      )}
      {view === 'week' && (
        <WeekView key={`w-${tid}`} teamId={tid} week={week} onWeekChange={setWeek} onOpenDay={openDay} meName={meName}
          canEditTemplate={canEditTemplate} onEditTemplate={canEditTemplate ? editTemplate : undefined} />
      )}
      {view === 'history' && (
        <HistoryView key={`h-${tid}`} teamId={tid} teamName={team.team_name} from={range.from} to={range.to}
          onRangeChange={(r) => setRange({ from: r?.from ?? null, to: r?.to ?? null })} onOpenDay={openDay} clockOffsetMs={clockOffsetMs} />
      )}
      {view === 'stats' && (
        <RoutineStats key={`s-${tid}`} teamId={tid} teamName={team.team_name} from={range.from} to={range.to}
          onRangeChange={(r) => setRange({ from: r?.from ?? null, to: r?.to ?? null })} clockOffsetMs={clockOffsetMs} />
      )}
      {view === 'template' && (
        <LazySection sectionKey={`tpl-${tid}`} label={t('or.view.template')}>
          <RoutineTemplateEditor key={`t-${tid}`} teamId={tid} teamName={team.team_name} canEdit={canEditTemplate}
            focusTaskId={tplFocus} onDirtyChange={setTplDirty} onSaved={() => loadOverview({ silent: true })} meName={meName} />
        </LazySection>
      )}
      {view === 'settings' && (
        <RoutineTeamSettings key={`c-${tid}`} teamId={tid} canEdit={canEditTemplate} onSaved={() => loadOverview({ silent: true })}
          meName={meName} />
      )}
    </div>
  )
}
