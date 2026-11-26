import { useEffect, useMemo, useRef, useState } from 'react'
import {
  BarChart3, CalendarCheck, CalendarDays, CalendarX, CircleDashed, FileSpreadsheet, FilterX, RotateCcw, TriangleAlert, Users,
} from 'lucide-react'
import { api } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { localeFor } from '../../i18n/dateLocale.js'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import { usePagination } from '../../hooks/usePagination.js'
import { PAGINATION_PRESETS } from '../../hooks/paginationPresets.js'
import { importWithDeadline } from '../../utils/importWithDeadline.js'
import { clientFailure } from '../../utils/errorMessages.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Progress } from '@/components/shadcn/progress'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Spinner } from '@/components/shadcn/spinner'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import MonitorStatsBar from '../MonitorStatsBar.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { useToast } from '../ui/Toast.jsx'
import { RangePicker, failureText, istanbulToday, resolveRange, validateRange } from './HistoryView.jsx'
import { isIsoDate, istanbulDayOfStamp, istanbulParts, shortDate } from './routineModel.js'
import { cn } from '@/lib/utils'

/**
 * Nöbet Rutinleri — "İstatistik" görünümü (`or_view=stats`, 2026-11-26). `GET /teams/{id}/stats` (aralık ≤ 366 gün, bitiş
 * ≤ bugün): üstte KPI kutucukları (`MonitorStatsBar dense`: iş günü, zamanında tamamlanan %, kısmi, kaçırılan, açık sorun —
 * kutucuklar görev tablosunu süzer; "zamanında" kişi tablosuna götürür), görev başına tablo (`or-stats-tasks`; kap
 * ≥ 860 px tablo, dar kapta kart), kişi başına tablo (`or-stats-people`), sorun özeti ve Excel dışa aktarımı (`or-export`
 * → TEMBEL `routineExport.js`).
 *
 * Kurallar: bilinmeyen yüzde "—" (asla 0 ya da yeşil; sunucu yüzdeyi aşağı yuvarlar — kaçırılan varken 100 olmaz), okunamayan
 * istatistik tehlike bloğu + "Tekrar dene"; 400 `EXPORT_TOO_LARGE` aralık alanının altında, 429 `EXPORT_BUSY` / `RATE_LIMITED`
 * bekleme ipucuyla tostta; tarayıcı indirmeyi başlatamazsa hata tostu (başarı denmez).
 */

export const TASKS_TABLE_MIN_PX = 860
export const PEOPLE_TABLE_MIN_PX = 640
/** Görev / kişi tabloları standart sayfalamayla (panel ön ayarı) — çubuk yalnız ilk sayfa boyutu aşılınca çizilir. */
export const LIST_PAGE_MIN = PAGINATION_PRESETS.panel.sizeOptions[0]
const FREQUENCIES = ['DAILY', 'WEEKLY', 'MONTHLY']
const SEVERITY_ORDER = ['HIGH', 'MEDIUM', 'LOW']
const SORTS = ['template', 'done_asc', 'missed_desc', 'problems_desc']
/** KPI kutucuğu → görev süzgeci. */
const TASK_FILTERS = {
  all: () => true,
  partial: (x) => Number(x?.skipped) > 0,
  missed: (x) => Number(x?.missed) > 0,
  problems: (x) => Number(x?.problems) > 0,
}

const finite = (v) => v !== null && v !== undefined && v !== '' && Number.isFinite(Number(v))

/** Sayı metni (yerel ayırıcı); bilinmeyen "—". */
export function numText(v, locale) {
  return finite(v) ? Number(v).toLocaleString(locale) : '—'
}

/** Yüzde metni: bir ondalığa AŞAĞI yuvarlanır (sunucu kuralı), TR "%93,6", EN "93.6%"; bilinmeyen "—". */
export function pctText(v, lang) {
  if (!finite(v)) return '—'
  const floored = Math.floor(Number(v) * 10) / 10
  const s = floored.toLocaleString(localeFor(lang), { minimumFractionDigits: 0, maximumFractionDigits: 1 })
  return lang === 'en' ? `${s}%` : `%${s}`
}

/** UTC damgası → İstanbul "dd.MM.yyyy HH:mm"; okunamazsa null. */
export function stampText(iso) {
  if (typeof iso !== 'string' || !iso) return null
  const ms = Date.parse(/[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : `${iso}Z`)
  if (!Number.isFinite(ms)) return null
  const p = istanbulParts(ms)
  return `${shortDate(p.date, true)} ${p.hm}`
}

/** Yapılma yüzdesinin tonu (gösterge çubuğu). Bilinmeyen tonsuz. */
function doneTone(v) {
  if (!finite(v)) return null
  const n = Number(v)
  if (n >= 95) return 'bg-success'
  if (n >= 80) return 'bg-warning'
  return 'bg-destructive'
}

/** Görev listesinin sıralaması (şablon sırası = sunucu sırası). Bilinmeyen değerler sona. */
export function sortTasks(tasks, sort) {
  const list = Array.isArray(tasks) ? [...tasks] : []
  const num = (v, missing) => (finite(v) ? Number(v) : missing)
  if (sort === 'done_asc') return list.sort((a, b) => num(a.done_pct, Infinity) - num(b.done_pct, Infinity))
  if (sort === 'missed_desc') return list.sort((a, b) => num(b.missed, -1) - num(a.missed, -1))
  if (sort === 'problems_desc') return list.sort((a, b) => num(b.problems, -1) - num(a.problems, -1))
  return list
}

/** Görev süzgeci (KPI kutucuğu). */
export function filterTasks(tasks, filter) {
  const fn = TASK_FILTERS[filter] || TASK_FILTERS.all
  return (Array.isArray(tasks) ? tasks : []).filter(fn)
}

/** Zamanında yüzdesinin KPI tonu — bilinmeyen nötr (asla yeşil). */
function onTimeTone(v) {
  if (!finite(v)) return 'total'
  const n = Number(v)
  if (n >= 95) return 'valid'
  if (n >= 80) return 'warning'
  return 'critical'
}

/** Dışa aktarım sonucunun kullanıcı bildirimi (çağıranın tostu). Ham kod göstermez. */
export function exportOutcome(out, t) {
  if (!out) return { kind: 'error', message: t('or.stats.exportNotDone') }
  if (out.ok) return { kind: 'success', message: t('or.stats.exportDone', out.name || '') }
  switch (out.code) {
    case 'SESSION_ENDED':
    case 'REQUEST_ABORTED':
    case 'CANCELLED':
      return { kind: 'none' }
    case 'EXPORT_TOO_LARGE':
      return { kind: 'range', message: failureText(out.res, t, 'or.err.EXPORT_TOO_LARGE') }
    case 'EXPORT_BUSY':
    case 'RATE_LIMITED': {
      const base = failureText(out.res, t, `or.err.${out.code}`)
      const raw = out.res?.retry_after_seconds ?? out.res?.retryAfter
      const sec = finite(raw) && Number(raw) > 0 ? Math.ceil(Number(raw)) : null
      return { kind: 'error', message: `${base} ${sec ? t('or.stats.exportRetryIn', sec) : t('or.stats.exportRetryLater')}` }
    }
    case 'DOWNLOAD_FAILED':
      return { kind: 'error', message: t('or.stats.downloadBlocked') }
    default:
      return { kind: 'error', message: failureText(out.res, t, 'or.stats.exportNotDone') }
  }
}

function freqText(t, f) {
  return FREQUENCIES.includes(f) ? t(`or.freq.${f}`) : '—'
}

function DonePct({ value, lang }) {
  const tone = doneTone(value)
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <span className={cn('tabular-nums', !finite(value) && 'text-muted-foreground')}>{pctText(value, lang)}</span>
      {tone && (
        <Progress aria-hidden="true" value={Math.max(0, Math.min(100, Number(value)))} max={100}
          className="h-1.5 bg-muted" indicatorClassName={tone} />
      )}
    </div>
  )
}

function LastDone({ task, t }) {
  const when = stampText(task?.last_done_at)
  if (!task?.last_done_by_name && !when) return <span className="text-muted-foreground">{t('or.stats.never')}</span>
  return (
    <span className="flex min-w-0 flex-col">
      {task?.last_done_by_name && <span className="[overflow-wrap:anywhere]">{task.last_done_by_name}</span>}
      {when && <span className="text-xs text-muted-foreground tabular-nums">{when}</span>}
    </span>
  )
}

const WRAP = 'whitespace-normal align-top [overflow-wrap:anywhere]'

function TasksTable({ rows, t, lang, locale }) {
  return (
    <Table className="table-fixed">
      <TableHeader>
        <TableRow>
          <TableHead className={cn('w-[23%]', WRAP)}>{t('or.stats.col.task')}</TableHead>
          <TableHead className={cn('w-[14%]', WRAP)}>{t('or.stats.col.section')}</TableHead>
          <TableHead className={cn('w-[9%]', WRAP)}>{t('or.stats.col.frequency')}</TableHead>
          <TableHead className={cn('w-[11%]', WRAP)}>{t('or.stats.col.donePct')}</TableHead>
          <TableHead className={cn('w-[8%] text-right', WRAP)}>{t('or.stats.col.skipped')}</TableHead>
          <TableHead className={cn('w-[8%] text-right', WRAP)}>{t('or.stats.col.missed')}</TableHead>
          <TableHead className={cn('w-[7%] text-right', WRAP)}>{t('or.stats.col.problems')}</TableHead>
          <TableHead className={cn('w-[13%]', WRAP)}>{t('or.stats.col.lastDone')}</TableHead>
          <TableHead className={cn('w-[7%]', WRAP)}>{t('or.stats.col.medianTime')}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((x, i) => (
          <TableRow key={x.task_id || i} data-slot="or-stats-task" data-task={x.task_id || undefined}>
            <TableCell className={cn('font-medium', WRAP)}>{x.title || '—'}</TableCell>
            <TableCell className={cn('text-muted-foreground', WRAP)}>{x.section_title || '—'}</TableCell>
            <TableCell className={WRAP}>{freqText(t, x.frequency)}</TableCell>
            <TableCell className={WRAP}><DonePct value={x.done_pct} lang={lang} /></TableCell>
            <TableCell className={cn('text-right tabular-nums', WRAP)}>{numText(x.skipped, locale)}</TableCell>
            <TableCell className={cn('text-right tabular-nums', WRAP, Number(x.missed) > 0 && 'font-semibold text-destructive')}>{numText(x.missed, locale)}</TableCell>
            <TableCell className={cn('text-right tabular-nums', WRAP)}>{numText(x.problems, locale)}</TableCell>
            <TableCell className={WRAP}><LastDone task={x} t={t} /></TableCell>
            <TableCell className={cn('tabular-nums', WRAP)}>{x.median_done_time || '—'}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

function Metric({ label, value, tone }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <dt className="text-[11px] leading-tight text-muted-foreground">{label}</dt>
      <dd className={cn('text-sm font-semibold tabular-nums', tone)}>{value}</dd>
    </div>
  )
}

function TaskCards({ rows, t, lang, locale }) {
  return (
    <ul className="grid gap-2 @xl:grid-cols-2">
      {rows.map((x, i) => (
        <li key={x.task_id || i} data-slot="or-stats-task" data-task={x.task_id || undefined}
          className="flex min-w-0 flex-col gap-2 rounded-lg border bg-card p-3">
          <div className="min-w-0">
            <p className="text-sm font-medium [overflow-wrap:anywhere]">{x.title || '—'}</p>
            <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
              {[x.section_title, freqText(t, x.frequency)].filter(Boolean).join(' · ')}
            </p>
          </div>
          <DonePct value={x.done_pct} lang={lang} />
          <dl className="grid grid-cols-3 gap-2">
            <Metric label={t('or.stats.col.skipped')} value={numText(x.skipped, locale)} />
            <Metric label={t('or.stats.col.missed')} value={numText(x.missed, locale)} tone={Number(x.missed) > 0 ? 'text-destructive' : undefined} />
            <Metric label={t('or.stats.col.problems')} value={numText(x.problems, locale)} />
          </dl>
          <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-1 text-xs">
            <span className="min-w-0"><span className="text-muted-foreground">{t('or.stats.col.lastDone')}: </span><LastDoneInline task={x} t={t} /></span>
            <span className="tabular-nums"><span className="text-muted-foreground">{t('or.stats.col.medianTime')}: </span>{x.median_done_time || '—'}</span>
          </div>
        </li>
      ))}
    </ul>
  )
}

function LastDoneInline({ task, t }) {
  const when = stampText(task?.last_done_at)
  const parts = [task?.last_done_by_name, when].filter(Boolean)
  return <span className="[overflow-wrap:anywhere]">{parts.length ? parts.join(' · ') : t('or.stats.never')}</span>
}

function PeopleTable({ rows, t, locale }) {
  return (
    <Table className="table-fixed">
      <TableHeader>
        <TableRow>
          <TableHead className={cn('w-[30%]', WRAP)}>{t('or.stats.pcol.name')}</TableHead>
          <TableHead className={cn('w-[14%] text-right', WRAP)}>{t('or.stats.pcol.daysResponsible')}</TableHead>
          <TableHead className={cn('w-[14%] text-right', WRAP)}>{t('or.stats.pcol.daysCompleted')}</TableHead>
          <TableHead className={cn('w-[14%] text-right', WRAP)}>{t('or.stats.pcol.onTime')}</TableHead>
          <TableHead className={cn('w-[14%] text-right', WRAP)}>{t('or.stats.pcol.itemsDone')}</TableHead>
          <TableHead className={cn('w-[14%] text-right', WRAP)}>{t('or.stats.pcol.problemsOpened')}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((p, i) => (
          <TableRow key={p.user_id || p.name || i} data-slot="or-stats-person">
            <TableCell className={cn('font-medium', WRAP)}>{p.name || '—'}</TableCell>
            <TableCell className={cn('text-right tabular-nums', WRAP)}>{numText(p.days_responsible, locale)}</TableCell>
            <TableCell className={cn('text-right tabular-nums', WRAP)}>{numText(p.days_completed, locale)}</TableCell>
            <TableCell className={cn('text-right tabular-nums', WRAP)}>{numText(p.days_completed_on_time, locale)}</TableCell>
            <TableCell className={cn('text-right tabular-nums', WRAP)}>{numText(p.items_done, locale)}</TableCell>
            <TableCell className={cn('text-right tabular-nums', WRAP)}>{numText(p.problems_opened, locale)}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

function PeopleCards({ rows, t, locale }) {
  return (
    <ul className="grid gap-2 @xl:grid-cols-2">
      {rows.map((p, i) => (
        <li key={p.user_id || p.name || i} data-slot="or-stats-person" className="flex min-w-0 flex-col gap-2 rounded-lg border bg-card p-3">
          <p className="text-sm font-medium [overflow-wrap:anywhere]">{p.name || '—'}</p>
          <dl className="grid grid-cols-3 gap-2 @md:grid-cols-5">
            <Metric label={t('or.stats.pcol.daysResponsible')} value={numText(p.days_responsible, locale)} />
            <Metric label={t('or.stats.pcol.daysCompleted')} value={numText(p.days_completed, locale)} />
            <Metric label={t('or.stats.pcol.onTime')} value={numText(p.days_completed_on_time, locale)} />
            <Metric label={t('or.stats.pcol.itemsDone')} value={numText(p.items_done, locale)} />
            <Metric label={t('or.stats.pcol.problemsOpened')} value={numText(p.problems_opened, locale)} />
          </dl>
        </li>
      ))}
    </ul>
  )
}

function SectionHeader({ icon: Icon, title, count, children }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <h3 className="flex min-w-0 items-center gap-2 text-sm font-semibold">
        <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
        <span className="[overflow-wrap:anywhere]">{title}</span>
        {count != null && <Badge variant="secondary" className="tabular-nums">{count}</Badge>}
      </h3>
      {children}
    </div>
  )
}

function TasksSection({ tasks, filter, onClearFilter, t, lang, locale }) {
  const [ref, width] = useElementWidth()
  const [sort, setSort] = useState('template')
  const rows = useMemo(() => sortTasks(filterTasks(tasks, filter), sort), [tasks, filter, sort])
  const pager = usePagination(rows, { listKey: 'or-stats-tasks', preset: 'panel', resetDeps: [filter, sort] })
  const visible = pager.pageItems
  const layout = width === 0 || width >= TASKS_TABLE_MIN_PX ? 'table' : 'cards'
  return (
    <Card className="gap-3 px-4 py-4 sm:px-5">
      <SectionHeader icon={BarChart3} title={t('or.stats.tasksTitle')} count={rows.length}>
        <NativeSelect size="sm" data-slot="or-stats-sort" aria-label={t('or.stats.sortAria')} value={sort}
          onChange={(e) => setSort(SORTS.includes(e.target.value) ? e.target.value : 'template')}>
          {SORTS.map((s) => <NativeSelectOption key={s} value={s}>{t(`or.stats.sort.${s}`)}</NativeSelectOption>)}
        </NativeSelect>
      </SectionHeader>
      {filter !== 'all' && (
        <div data-slot="or-stats-filter" className="flex flex-wrap items-center gap-2 text-xs">
          <Badge variant="outline" className="h-auto overflow-visible rounded-md whitespace-normal">{t(`or.stats.filter.${filter}`)}</Badge>
          <Button type="button" variant="ghost" size="sm" className="h-8 pointer-coarse:h-10" onClick={onClearFilter} data-slot="or-stats-filter-clear">
            <FilterX aria-hidden="true" />{t('or.stats.filterClear')}
          </Button>
        </div>
      )}
      <div ref={ref} data-slot="or-stats-tasks" data-layout={layout} className="@container flex min-w-0 flex-col gap-3">
        {rows.length === 0 ? (
          <p data-slot="or-stats-tasks-empty" className="rounded-lg border border-dashed px-3 py-6 text-center text-sm text-muted-foreground">
            {filter === 'all' ? t('or.stats.tasksEmpty') : t('or.stats.tasksEmptyFiltered')}
          </p>
        ) : layout === 'table'
          ? <TasksTable rows={visible} t={t} lang={lang} locale={locale} />
          : <TaskCards rows={visible} t={t} lang={lang} locale={locale} />}
        {rows.length > LIST_PAGE_MIN && <PaginationBar {...pager} />}
      </div>
    </Card>
  )
}

function PeopleSection({ people, t, locale, sectionRef }) {
  const [ref, width] = useElementWidth()
  const rows = useMemo(() => (Array.isArray(people) ? people : []), [people])
  const pager = usePagination(rows, { listKey: 'or-stats-people', preset: 'panel' })
  const visible = pager.pageItems
  const layout = width === 0 || width >= PEOPLE_TABLE_MIN_PX ? 'table' : 'cards'
  return (
    <div ref={sectionRef} tabIndex={-1} className="scroll-mt-4 rounded-xl outline-none focus-visible:ring-2 focus-visible:ring-ring">
      <Card className="gap-3 px-4 py-4 sm:px-5">
        <SectionHeader icon={Users} title={t('or.stats.peopleTitle')} count={rows.length} />
        <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('or.stats.peopleHint')}</p>
        <div ref={ref} data-slot="or-stats-people" data-layout={layout} className="@container flex min-w-0 flex-col gap-3">
          {rows.length === 0 ? (
            <p data-slot="or-stats-people-empty" className="rounded-lg border border-dashed px-3 py-6 text-center text-sm text-muted-foreground">
              {t('or.stats.peopleEmpty')}
            </p>
          ) : layout === 'table'
            ? <PeopleTable rows={visible} t={t} locale={locale} />
            : <PeopleCards rows={visible} t={t} locale={locale} />}
          {rows.length > LIST_PAGE_MIN && <PaginationBar {...pager} />}
        </div>
      </Card>
    </div>
  )
}

function ProblemsSection({ problems, t, locale }) {
  const p = problems && typeof problems === 'object' ? problems : null
  const hours = finite(p?.median_resolve_hours)
    ? t('or.stats.hours', (Math.floor(Number(p.median_resolve_hours) * 10) / 10).toLocaleString(locale, { maximumFractionDigits: 1 }))
    : '—'
  const sev = p?.by_severity && typeof p.by_severity === 'object' ? p.by_severity : {}
  return (
    <Card data-slot="or-stats-problems" className="gap-3 px-4 py-4 sm:px-5">
      <SectionHeader icon={TriangleAlert} title={t('or.stats.problemsTitle')} />
      {!p ? (
        <p className="text-sm text-muted-foreground">{t('or.stats.problemsUnknown')}</p>
      ) : (
        <div className="@container flex flex-col gap-3">
          <dl className="grid grid-cols-2 gap-3 @md:grid-cols-4">
            <Metric label={t('or.stats.prob.opened')} value={numText(p.opened, locale)} />
            <Metric label={t('or.stats.prob.resolved')} value={numText(p.resolved, locale)} />
            <Metric label={t('or.stats.prob.openNow')} value={numText(p.open_now, locale)} tone={Number(p.open_now) > 0 ? 'text-destructive' : undefined} />
            <Metric label={t('or.stats.prob.medianResolve')} value={hours} />
          </dl>
          <ul className="flex flex-wrap gap-2" aria-label={t('or.stats.prob.bySeverity')}>
            {SEVERITY_ORDER.map((s) => (
              <li key={s}>
                <Badge variant={s === 'HIGH' ? 'destructive' : s === 'MEDIUM' ? 'warning' : 'outline'} data-severity={s}
                  className="h-auto gap-1.5 overflow-visible rounded-md px-2 py-1 whitespace-normal">
                  <span>{t(`or.stats.sev.${s}`)}</span>
                  <span className="font-semibold tabular-nums">{numText(sev[s] ?? 0, locale)}</span>
                </Badge>
              </li>
            ))}
          </ul>
        </div>
      )}
    </Card>
  )
}

function StatsLoading({ t }) {
  return (
    <div data-slot="or-stats-loading" aria-busy="true" aria-label={t('or.stats.loading')} className="flex flex-col gap-4">
      <div className="flex flex-wrap gap-2">
        {[0, 1, 2, 3, 4].map((i) => <Skeleton key={i} className="h-14 min-w-32 flex-1 rounded-lg" />)}
      </div>
      <Skeleton className="h-64 w-full rounded-xl" />
      <Skeleton className="h-40 w-full rounded-xl" />
    </div>
  )
}

export default function RoutineStats({ teamId, teamName, from = null, to = null, onRangeChange = () => {}, clockOffsetMs = 0 }) {
  const t = useT()
  const { lang } = useLanguage()
  const locale = localeFor(lang)
  const toast = useToast()
  // "Bugün" sunucu saatinden (İstanbul günü): önce sayfanın genel bakışından gelen saat farkı, sonra bu uç yanıtının
  // anı (`timestamp`). İkisi de yoksa İstanbul günü tarayıcı saatiyle — kayık tarayıcı saati aralığı bir gün kaydırmasın.
  const [serverToday, setServerToday] = useState(null)
  const today = serverToday || istanbulToday(Date.now() + (Number(clockOffsetMs) || 0))
  const range = resolveRange(from, to, today)
  const rangeError = validateRange(range, today)
  const hasTeam = teamId != null && teamId !== ''
  const blocked = !hasTeam || !!rangeError
  const [state, setState] = useState({ status: 'idle', data: null, res: null })
  const [nonce, setNonce] = useState(0)
  const [filter, setFilter] = useState('all')
  const seqRef = useRef(0)
  const peopleRef = useRef(null)

  // Aralık değişince dışa aktarım hatası (aralığa ait) kalkar — render sırasında ayarlanır
  const rangeKey = `${teamId}|${range.from}|${range.to}`
  const [exportError, setExportError] = useState(null)
  const [exportErrorFor, setExportErrorFor] = useState(rangeKey)
  if (exportErrorFor !== rangeKey) {
    setExportErrorFor(rangeKey)
    setExportError(null)
  }

  useEffect(() => {
    const seq = ++seqRef.current
    if (blocked) {
      setState({ status: 'idle', data: null, res: null })
      return undefined
    }
    const ctrl = new AbortController()
    setState({ status: 'loading', data: null, res: null })
    ;(async () => {
      try {
        const res = await api.oncallRoutines.stats(teamId, { from: range.from, to: range.to, signal: ctrl.signal })
        if (seq !== seqRef.current || ctrl.signal.aborted) return
        if (res === null) return
        if (res?.success === true && res.data && typeof res.data === 'object') {
          const day = istanbulDayOfStamp(res.timestamp)
          if (day) setServerToday((prev) => (prev === day ? prev : day))
          setState({ status: 'ok', data: res.data, res: null })
        }
        else setState({ status: 'error', data: null, res })
      } catch (e) {
        if (seq !== seqRef.current || ctrl.signal.aborted) return
        setState({ status: 'error', data: null, res: e })
      }
    })()
    return () => ctrl.abort()
  }, [teamId, range.from, range.to, blocked, nonce])

  // Dışa aktarım: tembel modül + iptal; sayfa kapanırsa sonuç yutulur
  const [exporting, setExporting] = useState(false)
  const exportCtrl = useRef(null)
  const mountedRef = useRef(true)
  useEffect(() => {
    mountedRef.current = true
    return () => { mountedRef.current = false; exportCtrl.current?.abort() }
  }, [])

  const data = state.status === 'ok' ? state.data : null
  const exportFrom = isIsoDate(data?.from) ? data.from : range.from
  const exportTo = isIsoDate(data?.to) ? data.to : range.to

  const onExport = async () => {
    if (exporting || blocked) return
    setExporting(true)
    setExportError(null)
    const ctrl = new AbortController()
    exportCtrl.current = ctrl
    try {
      const mod = await importWithDeadline(() => import('./routineExport.js'))
      const out = await mod.exportRoutineXlsx({ teamId, teamName, from: exportFrom, to: exportTo, t, lang, signal: ctrl.signal })
      if (!mountedRef.current) return
      const o = exportOutcome(out, t)
      if (o.kind === 'success') toast.success(o.message)
      else if (o.kind === 'range') setExportError(o.message)
      else if (o.kind === 'error') toast.error(o.message, { details: out?.res && typeof out.res === 'object' ? out.res : undefined })
    } catch (e) {
      if (!mountedRef.current) return
      const { message, details } = clientFailure(e, { fallback: t('or.stats.exportNotDone'), chunk: t('or.stats.pageUpdated') })
      toast.error(message, { details })
    } finally {
      if (mountedRef.current) setExporting(false)
    }
  }

  const days = data?.days && typeof data.days === 'object' ? data.days : {}
  const problems = data?.problems && typeof data.problems === 'object' ? data.problems : null
  const completedDays = (Number(days.completed) || 0) + (Number(days.partial) || 0)
  const incomplete = Number(days.incomplete) || 0
  const kpis = data ? [
    {
      key: 'all', Icon: CalendarDays, label: t('or.stats.kpi.workdays'), value: numText(data.workdays, locale), cls: 'total',
      sub: finite(data.workdays) ? t('or.stats.kpi.workdaysSub', completedDays.toLocaleString(locale)) : undefined,
      hint: t('or.stats.kpi.workdaysHint'),
    },
    {
      key: 'ontime', Icon: CalendarCheck, label: t('or.stats.kpi.onTime'), value: pctText(data.on_time_pct, lang),
      cls: onTimeTone(data.on_time_pct), hint: t('or.stats.kpi.onTimeHint'), tip: t('or.stats.kpi.onTimeTip'),
      onClick: () => {
        const el = peopleRef.current
        if (!el) return
        try { el.scrollIntoView({ behavior: 'smooth', block: 'start' }) } catch { /* jsdom */ }
        el.focus?.({ preventScroll: true })
      },
    },
    {
      key: 'partial', Icon: CircleDashed, label: t('or.stats.kpi.partial'), value: numText(days.partial, locale),
      cls: Number(days.partial) > 0 ? 'warning' : 'total', hint: t('or.stats.kpi.partialHint'),
    },
    {
      key: 'missed', Icon: CalendarX, label: t('or.stats.kpi.missed'), value: numText(days.missed, locale),
      cls: Number(days.missed) > 0 ? 'critical' : 'total', hint: t('or.stats.kpi.missedHint'),
      sub: incomplete > 0 ? t('or.stats.kpi.incompleteSub', incomplete.toLocaleString(locale)) : undefined,
    },
    {
      key: 'problems', Icon: TriangleAlert, label: t('or.stats.kpi.openProblems'), value: numText(problems?.open_now, locale),
      cls: Number(problems?.open_now) > 0 ? 'alert' : 'total', hint: t('or.stats.kpi.problemsHint'),
    },
  ] : []

  return (
    <section data-slot="or-stats" data-state={state.status} className="flex min-w-0 flex-col gap-4">
      <Card className="gap-3 px-4 py-4 sm:px-5">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="flex min-w-0 items-start gap-2">
            <BarChart3 aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
            <div className="min-w-0">
              <h2 className="text-sm font-semibold">{t('or.stats.title')}</h2>
              <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('or.stats.subtitle')}</p>
            </div>
          </div>
          <Button type="button" variant="outline" data-slot="or-export" onClick={onExport} disabled={exporting || blocked}
            aria-busy={exporting || undefined}>
            {exporting ? <Spinner aria-hidden="true" /> : <FileSpreadsheet aria-hidden="true" />}
            {exporting ? t('or.stats.exporting') : t('or.stats.export')}
          </Button>
        </div>
        <RangePicker from={from} to={to} today={today} onRangeChange={onRangeChange} error={exportError} disabled={!hasTeam} />
      </Card>

      {state.status === 'loading' && <StatsLoading t={t} />}

      {state.status === 'error' && (
        <div data-slot="or-stats-failed">
          <StatusBlock tone="danger" title={t('or.stats.readTitle')}
            description={failureText(state.res, t, 'or.stats.readBody')}
            details={state.res && typeof state.res === 'object' ? state.res : undefined}
            actions={(
              <Button type="button" variant="outline" data-slot="or-stats-retry" onClick={() => setNonce((n) => n + 1)}>
                <RotateCcw aria-hidden="true" />{t('or.stats.retry')}
              </Button>
            )} />
        </div>
      )}

      {data && (
        <>
          {data.truncated && (
            <AlertBanner tone="info" className="mb-0">{t('or.stats.truncated')}</AlertBanner>
          )}
          <div data-slot="or-stats-kpis">
            <MonitorStatsBar dense items={kpis} activeFilter={filter}
              onStatClick={(key) => setFilter((prev) => (key === 'all' || key === prev ? 'all' : key))} className="mb-0" />
          </div>
          <TasksSection tasks={data.tasks} filter={filter} onClearFilter={() => setFilter('all')} t={t} lang={lang} locale={locale} />
          <PeopleSection people={data.people} t={t} locale={locale} sectionRef={peopleRef} />
          <ProblemsSection problems={problems} t={t} locale={locale} />
        </>
      )}
    </section>
  )
}
