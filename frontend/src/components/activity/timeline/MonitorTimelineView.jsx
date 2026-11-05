import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import {
  ArrowLeft, RefreshCw, ExternalLink, ListFilter, ChevronLeft, ChevronRight, BellRing, FileSearch, Activity, XCircle,
  SearchX, Clock, Percent, Timer, Hourglass, ListChecks,
} from 'lucide-react'
import { api, formatDateSec } from '../../../api/client'
import { useT, useLanguage } from '../../../i18n/index.jsx'
import { navigateTo } from '../../../utils/navigate.js'
import { activityTarget } from '../../../utils/activityTarget.js'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Label } from '@/components/shadcn/label'
import {
  ChartContainer, ChartTooltip, ChartTooltipContent, ChartLegend, ChartLegendContent, ComposedChart, Line, Bar, XAxis, YAxis,
  CartesianGrid,
} from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import DateTimeField from '../../ui/DateTimeField.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import { ReasonBadge, TypeBadge } from '../ActivityParts.jsx'
import { durationText } from '../related/relatedModel.js'
import {
  TL_RANGES, availabilityTone, formatPct, hasSeriesData, outageSeconds, parseTime, readTlParams, seriesPoints,
  stripSegments, tlWindow, unwrapTimeline, writeTlParams,
} from './timelineModel.js'

const STATE_BG = {
  up: 'bg-success hover:bg-success/85',
  down: 'bg-destructive hover:bg-destructive/85',
  warn: 'bg-warning hover:bg-warning/85',
  unknown: 'bg-muted-foreground/35 hover:bg-muted-foreground/50',
}
const STATE_INK = {
  up: 'text-success', down: 'text-destructive', warn: 'text-amber-700 dark:text-amber-300', unknown: 'text-muted-foreground',
}
const TONE_INK = { good: 'text-success', warn: 'text-amber-700 dark:text-amber-300', bad: 'text-destructive', unknown: 'text-muted-foreground' }
const OUTAGE_PAGE = 10

/**
 * İzleme zaman çizelgesi görünümü (2026-11-05, D) — `?tab=activity&amon=<TYPE>:<id>`. Aktivite akışının kontrol
 * satırlarından tek izlemenin erişilebilirlik şeridi (yeşil / kırmızı / sarı / gri dönemler — dokunarak / ok tuşlarıyla
 * seçilir), kesinti listesi (süre + neden + alarm + ilk aktivite), MTTR / en uzun kesinti / erişilebilirlik kutucukları
 * (bilinmeyen "—", asla yeşil değil) ve yanıt süresi grafiği. Aralık 24 sa / 7 g / 30 g / 90 g / özel (URL `atlr`,
 * `atlf`, `atlt` — paylaşılabilir).
 *
 * Props: `{ type, id, onBack, onOpenActivity(id), onFilterList(type, id, name) }`.
 * Test kancaları: `data-slot="act-tl-view"` (+ `data-state`), `act-tl-kpi` (+ `data-key` / `data-tone`), `act-tl-strip`,
 * `act-tl-seg` (+ `data-state` / `data-index`), `act-tl-period`, `act-tl-outage`, `act-tl-chart`.
 */
export default function MonitorTimelineView({ type, id, onBack, onOpenActivity, onFilterList }) {
  const t = useT()
  const { lang } = useLanguage()
  const uid = useId()
  const [tl, setTl] = useState(() => readTlParams(window.location.search))
  const [draft, setDraft] = useState(() => ({ from: tl.from, to: tl.to }))
  const [state, setState] = useState({ status: 'loading', data: null, error: null })
  const seq = useRef(0)
  const win = useMemo(() => tlWindow(tl), [tl])

  // Aralık URL'ye yazılır (geçmiş kaydı eklenmez); geri / yenile aynı aralığı açar
  useEffect(() => {
    try {
      const s = writeTlParams(window.location.search, tl)
      window.history.replaceState(window.history.state, '', `${window.location.pathname}${s}${window.location.hash}`)
    } catch { /* history yok */ }
  }, [tl])

  const load = useCallback(() => {
    const my = ++seq.current
    const key = `${type}:${id}`
    // Pencere HER istekte o anki zamanla hesaplanır (Yenile "son 7 gün"ü dondurmasın)
    const w = tlWindow(tl)
    if (w.error) return Promise.resolve()
    setState((s) => ({ status: 'loading', data: s.data && s.key === `${type}:${id}` ? s.data : null, error: null, key: `${type}:${id}` }))
    return Promise.resolve().then(() => api.getActivityTimeline({ monitor: `${type}:${id}`, from: w.from, to: w.to }))
      .then((res) => {
        if (my !== seq.current) return
        if (res?.success) {
          const data = unwrapTimeline(res)
          setState(data ? { status: 'ok', data, error: null, key: `${type}:${id}` } : { status: 'error', data: null, error: null })
        } else if (res?.status === 404 || res?.status === 403) {
          setState({ status: 'notfound', data: null, error: null })
        } else {
          // Yenileme hatası son okunan veriyi SİLMEZ (üstte "yenilenemedi" bandı); ilk okuma hatası tam hata bloğu
          setState((s) => ({ status: 'error', data: s.key === key ? s.data : null, error: res?.error || null, key }))
        }
      })
      .catch((e) => { if (my === seq.current) setState((s) => ({ status: 'error', data: s.key === key ? s.data : null, error: e?.message || null, key })) })
  }, [type, id, tl])

  useEffect(() => { load() }, [load])
  useEffect(() => () => { seq.current++ }, [])

  const data = state.data
  const mon = data?.monitor || null
  const name = mon?.name || mon?.target || null
  const dest = activityTarget({ monitor_type: type, monitor_id: id, target: mon?.target })
  // Özel aralığa geçişte uçlar o anki pencereden dolar (boş alan + hata yerine aynı pencere açık kalır)
  const changeRange = (r) => {
    if (r === 'custom') {
      const from = draft.from || tl.from || win.from || ''
      const to = draft.to || tl.to || ''
      setDraft({ from, to })
      setTl({ range: 'custom', from, to })
      return
    }
    setTl({ range: r, from: '', to: '' })
  }
  const customErr = tl.range === 'custom' ? win.error : null

  return (
    <div data-slot="act-tl-view" data-state={state.status} className="flex min-w-0 flex-col gap-4">
      {/* Başlık — geri, izleme adı (sarar), tür / hedef / takım, eylemler */}
      <Card className="min-w-0 gap-3 py-4 shadow-xs">
        <CardHeader className="flex min-w-0 flex-col gap-3 px-4">
          <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
            <Button type="button" variant="ghost" size="sm" onClick={onBack} className="-ml-2 pointer-coarse:h-10" data-slot="act-tl-back">
              <ArrowLeft aria-hidden="true" /> {t('act.backToList')}
            </Button>
            <div className="flex flex-wrap items-center gap-2">
              <Button type="button" variant="outline" size="sm" onClick={() => load()} className="pointer-coarse:h-10"
                aria-label={t('act.refresh')} title={t('act.refresh')}>
                <RefreshCw aria-hidden="true" /> <span className="hidden sm:inline">{t('act.refresh')}</span>
              </Button>
              {onFilterList && (
                <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10"
                  onClick={() => onFilterList(type, id, name || '')}>
                  <ListFilter aria-hidden="true" /> {t('act.tl.mon.listActivities')}
                </Button>
              )}
              {dest && (
                <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10"
                  onClick={() => navigateTo(dest.tab, dest.params)}>
                  <ExternalLink aria-hidden="true" /> {t('act.goMonitor')}
                </Button>
              )}
            </div>
          </div>
          <div className="flex min-w-0 flex-col gap-1.5">
            <CardTitle role="heading" aria-level={2} className="flex min-w-0 flex-wrap items-center gap-2 text-lg font-semibold">
              <TypeBadge type={type} />
              <span data-slot="act-tl-name" className="min-w-0 [overflow-wrap:anywhere]">
                {name || t('act.tl.mon.title')}
              </span>
            </CardTitle>
            <CardDescription className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-sm">
              {mon?.target && mon.target !== name && <span className="min-w-0 font-mono text-xs break-all">{mon.target}</span>}
              {mon?.team_id != null && <TeamBadge teamId={mon.team_id} teamName={mon.team_name} static wrap />}
              <span>{t('act.tl.mon.desc')}</span>
            </CardDescription>
          </div>
        </CardHeader>
        <CardContent className="flex min-w-0 flex-col gap-3 px-4">
          <SegmentedControl value={tl.range} onChange={changeRange} ariaLabel={t('act.tl.mon.range')} className="max-w-full flex-wrap"
            options={TL_RANGES.map((r) => ({ value: r, label: t('act.tl.mon.r.' + r) }))} />
          {tl.range === 'custom' && (
            <div data-slot="act-tl-custom" className="flex min-w-0 flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-end">
              <div className="flex min-w-0 flex-col gap-1 sm:w-56">
                <Label htmlFor={`${uid}-from`} className="text-xs text-muted-foreground">{t('act.flt.from')}</Label>
                <DateTimeField id={`${uid}-from`} value={draft.from} onChange={(v) => setDraft((d) => ({ ...d, from: v }))}
                  max={draft.to || undefined} invalid={!!customErr} describedBy={customErr ? `${uid}-err` : undefined} />
              </div>
              <div className="flex min-w-0 flex-col gap-1 sm:w-56">
                <Label htmlFor={`${uid}-to`} className="text-xs text-muted-foreground">{t('act.flt.to')}</Label>
                <DateTimeField id={`${uid}-to`} value={draft.to} onChange={(v) => setDraft((d) => ({ ...d, to: v }))}
                  min={draft.from || undefined} clearable />
              </div>
              <Button type="button" className="pointer-coarse:h-10" onClick={() => setTl({ range: 'custom', from: draft.from, to: draft.to })}>
                {t('act.flt.apply')}
              </Button>
              {customErr && (
                <p id={`${uid}-err`} role="alert" data-slot="act-tl-custom-error" className="m-0 w-full text-sm text-destructive">{t('act.tl.mon.bad.' + customErr)}</p>
              )}
            </div>
          )}
        </CardContent>
      </Card>

      {state.status === 'notfound' ? (
        <StatusBlock tone="neutral" icon={SearchX} title={t('act.tl.mon.notFound')} description={t('act.tl.mon.notFoundHint')}
          actions={<Button type="button" variant="outline" onClick={onBack} className="pointer-coarse:h-10"><ArrowLeft aria-hidden="true" /> {t('act.backToList')}</Button>} />
      ) : state.status === 'error' && !data ? (
        <StatusBlock tone="danger" icon={XCircle} title={t('act.tl.mon.loadFailed')} role="alert"
          actions={<Button type="button" variant="outline" onClick={() => load()} className="pointer-coarse:h-10"><RefreshCw aria-hidden="true" /> {t('hist.retry')}</Button>} />
      ) : !data ? (
        <div className="flex flex-col gap-3" aria-busy="true">
          <span className="sr-only" role="status">{t('act.loading')}</span>
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-48 w-full" />
        </div>
      ) : (
        <TimelineBody data={data} t={t} lang={lang} loading={state.status === 'loading'} failedRefresh={state.status === 'error'}
          onRetry={() => load()} onOpenActivity={onOpenActivity} win={win} />
      )}
    </div>
  )
}

function Kpi({ k, icon: Icon, label, value, sub, tone = 'unknown' }) {
  return (
    <div data-slot="act-tl-kpi" data-key={k} data-tone={tone} className="flex min-w-0 flex-col gap-1 rounded-lg border bg-card px-3 py-2.5">
      <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground [overflow-wrap:anywhere]">
        <Icon aria-hidden="true" className="size-3.5 shrink-0" /> {label}
      </span>
      <span className={cn('text-xl font-semibold tabular-nums', TONE_INK[tone])}>{value}</span>
      {sub && <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{sub}</span>}
    </div>
  )
}

function TimelineBody({ data, t, lang, loading, failedRefresh, onRetry, onOpenActivity, win }) {
  const avTone = availabilityTone(data.availability_pct)
  const dur = (s) => (s == null ? '—' : durationText(s, t))
  const fromMs = parseTime(data.from || win.from)
  const toMs = data.to ? parseTime(data.to) : Date.now()
  const spanMs = Number.isFinite(fromMs) && Number.isFinite(toMs) ? toMs - fromMs : undefined

  return (
    <div className={cn('flex min-w-0 flex-col gap-4', loading && 'opacity-70')} aria-busy={loading || undefined}>
      {failedRefresh && (
        <AlertBanner tone="danger" className="mb-0" role="alert"
          actions={<Button type="button" variant="outline" size="sm" onClick={onRetry} className="pointer-coarse:h-10"><RefreshCw aria-hidden="true" /> {t('hist.retry')}</Button>}>
          {t('act.tl.mon.refreshFailed')}
        </AlertBanner>
      )}
      {data.monitor?.exists === false && (
        <div data-slot="act-tl-deleted"><AlertBanner tone="info" className="mb-0">{t('act.tl.mon.deleted')}</AlertBanner></div>
      )}
      {data.truncated && (
        <div data-slot="act-tl-truncated"><AlertBanner tone="warning" className="mb-0">{t('act.tl.mon.truncated')}</AlertBanner></div>
      )}

      <div className="grid min-w-0 grid-cols-2 gap-2 sm:grid-cols-3 xl:grid-cols-5">
        <Kpi k="availability" icon={Percent} label={t('act.tl.mon.kpi.availability')} tone={avTone}
          value={data.availability_pct == null ? '—' : (lang === 'en' ? `${formatPct(data.availability_pct, lang)}%` : `%${formatPct(data.availability_pct, lang)}`)}
          sub={data.availability_pct == null ? t('act.tl.mon.kpi.noScheduled') : t('act.tl.mon.kpi.availabilityHint')} />
        <Kpi k="outages" icon={XCircle} label={t('act.tl.mon.kpi.outages')}
          tone={data.checks ? (data.outage_count > 0 ? 'bad' : 'good') : 'unknown'}
          value={data.checks ? String(data.outage_count) : '—'} />
        <Kpi k="mttr" icon={Timer} label={t('act.tl.mon.kpi.mttr')} value={dur(data.mttr_s)} sub={t('act.tl.mon.kpi.mttrHint')} />
        <Kpi k="longest" icon={Hourglass} label={t('act.tl.mon.kpi.longest')} value={dur(data.longest_outage_s)} />
        <Kpi k="checks" icon={ListChecks} label={t('act.tl.mon.kpi.checks')} value={String(data.checks)}
          sub={data.scheduled_checks != null ? t('act.tl.mon.kpi.scheduled', data.scheduled_checks) : null} />
      </div>

      <AvailabilityStrip periods={data.periods} from={data.from || win.from} to={data.to} t={t} merged={data.periods_merged} />

      <OutageList outages={data.outages} t={t} onOpenActivity={onOpenActivity} />

      <ResponseChart series={data.series} t={t} lang={lang} spanMs={spanMs} />
    </div>
  )
}

/** Erişilebilirlik şeridi — dönemler oransal bölümler; seçili dönemin ayrıntısı altında (önceki / sonraki ile gezilir). */
function AvailabilityStrip({ periods, from, to, t, merged = false }) {
  const segs = useMemo(() => stripSegments(periods, from, to), [periods, from, to])
  const order = useMemo(() => [...segs].sort((a, b) => a.index - b.index).map((s) => s.index), [segs])
  const defaultSel = useMemo(() => {
    for (let i = periods.length - 1; i >= 0; i--) if (periods[i]?.state === 'down') return i
    return order.length ? order[order.length - 1] : null
  }, [periods, order])
  const [sel, setSel] = useState(defaultSel)
  useEffect(() => { setSel(defaultSel) }, [defaultSel])
  const refs = useRef(new Map())
  const p = sel != null ? periods[sel] : null
  const pos = sel != null ? order.indexOf(sel) : -1

  const move = (delta, focus = false) => {
    if (!order.length) return
    const next = order[Math.min(order.length - 1, Math.max(0, (pos < 0 ? 0 : pos) + delta))]
    setSel(next)
    if (focus) requestAnimationFrame(() => refs.current.get(next)?.focus())
  }
  const onKey = (e) => {
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') { e.preventDefault(); move(1, true) }
    else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') { e.preventDefault(); move(-1, true) }
    else if (e.key === 'Home') { e.preventDefault(); move(-order.length, true) }
    else if (e.key === 'End') { e.preventDefault(); move(order.length, true) }
  }
  const periodLabel = (x) => `${t('act.tl.mon.state.' + (x?.state || 'unknown'))} · ${formatDateSec(x?.from)} – ${x?.to ? formatDateSec(x.to) : t('act.tl.mon.ongoing')}`

  return (
    <Card className="min-w-0 gap-3 py-4 shadow-xs">
      <CardHeader className="px-4">
        <CardTitle role="heading" aria-level={3} className="flex items-center gap-1.5 text-sm font-semibold">
          <Activity aria-hidden="true" className="size-4 text-muted-foreground" /> {t('act.tl.mon.strip')}
        </CardTitle>
        <CardDescription className="text-xs">{t('act.tl.mon.stripHint')}{merged ? ` ${t('act.tl.mon.merged')}` : ''}</CardDescription>
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-3 px-4">
        {segs.length === 0 ? (
          <p data-slot="act-tl-strip-empty" className="m-0 text-sm text-muted-foreground">{t('act.tl.mon.noChecks')}</p>
        ) : (
          <>
            <div data-slot="act-tl-strip" role="group" aria-label={t('act.tl.mon.strip')}
              className="relative h-10 w-full overflow-hidden rounded-md bg-muted pointer-coarse:h-12">
              {segs.map((s) => (
                <Button key={s.index} type="button" variant="ghost" data-slot="act-tl-seg" data-state={s.state} data-index={s.index}
                  ref={(el) => { if (el) refs.current.set(s.index, el); else refs.current.delete(s.index) }}
                  tabIndex={s.index === (sel ?? order[0]) ? 0 : -1} aria-pressed={s.index === sel}
                  aria-label={periodLabel(periods[s.index])} onKeyDown={onKey} onClick={() => setSel(s.index)}
                  className={cn('absolute top-0 h-full min-w-0 rounded-none border-0 p-0 shadow-none focus-visible:z-10 focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1',
                    STATE_BG[s.state], s.index === sel && 'z-[5] outline-2 outline-offset-[-2px] outline-foreground')}
                  style={{ left: `${s.left}%`, width: `max(3px, ${s.width}%)` }} />
              ))}
            </div>
            <ul className="m-0 flex list-none flex-wrap gap-x-4 gap-y-1 p-0 text-xs text-muted-foreground" aria-label={t('act.tl.mon.legend')}>
              {['up', 'down', 'warn', 'unknown'].map((st) => (
                <li key={st} className="flex items-center gap-1.5">
                  <span aria-hidden="true" className={cn('inline-block size-2.5 rounded-sm', STATE_BG[st])} /> {t('act.tl.mon.state.' + st)}
                </li>
              ))}
            </ul>
            {p && (
              <div data-slot="act-tl-period" data-state={p.state} className="flex min-w-0 flex-col gap-2 rounded-lg border bg-muted/30 p-3 sm:flex-row sm:items-center sm:justify-between">
                <div className="flex min-w-0 flex-col gap-1" aria-live="polite">
                  <span className={cn('font-semibold', STATE_INK[p.state] || STATE_INK.unknown)}>{t('act.tl.mon.state.' + (p.state || 'unknown'))}</span>
                  <span className="text-sm tabular-nums [overflow-wrap:anywhere]">
                    {formatDateSec(p.from)} – {p.to ? formatDateSec(p.to) : t('act.tl.mon.ongoing')}
                  </span>
                  <span className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                    {t('act.tl.mon.periodChecks', p.checks ?? 0)}
                    {/* NO_DATA = uzun veri boşluğu (state unknown) — hata nedeni değil, nötr metin */}
                    {p.reason === 'NO_DATA'
                      ? <span data-slot="act-tl-nodata">· {t('act.tl.mon.noData')}</span>
                      : p.reason && <ReasonBadge code={p.reason} t={t} />}
                  </span>
                </div>
                <div className="flex shrink-0 items-center gap-1.5">
                  <Button type="button" variant="outline" size="icon" className="pointer-coarse:size-10" disabled={pos <= 0}
                    aria-label={t('act.tl.mon.prevPeriod')} onClick={() => move(-1)}><ChevronLeft aria-hidden="true" /></Button>
                  <span className="text-xs text-muted-foreground tabular-nums">{pos + 1} / {order.length}</span>
                  <Button type="button" variant="outline" size="icon" className="pointer-coarse:size-10" disabled={pos >= order.length - 1}
                    aria-label={t('act.tl.mon.nextPeriod')} onClick={() => move(1)}><ChevronRight aria-hidden="true" /></Button>
                </div>
              </div>
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}

/** Kesinti listesi — en yeni üstte; süre + kontrol sayısı + neden; ilk aktiviteye / alarma git. */
function OutageList({ outages, t, onOpenActivity }) {
  const [all, setAll] = useState(false)
  const sorted = useMemo(() => [...(outages || [])].sort((a, b) => (parseTime(b.from) || 0) - (parseTime(a.from) || 0)), [outages])
  const shown = all ? sorted : sorted.slice(0, OUTAGE_PAGE)
  return (
    <Card className="min-w-0 gap-3 py-4 shadow-xs">
      <CardHeader className="px-4">
        <CardTitle role="heading" aria-level={3} className="flex items-center gap-1.5 text-sm font-semibold">
          <XCircle aria-hidden="true" className="size-4 text-muted-foreground" /> {t('act.tl.mon.outages')}
          <Badge variant="secondary" className="tabular-nums">{sorted.length}</Badge>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-2 px-4">
        {sorted.length === 0 ? (
          <p data-slot="act-tl-no-outage" className="m-0 text-sm text-muted-foreground">{t('act.tl.mon.noOutage')}</p>
        ) : (
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {shown.map((o, i) => {
              const secs = outageSeconds(o)
              const ongoing = o.ongoing === true || !o.to
              return (
                <li key={`${o.from}-${i}`} data-slot="act-tl-outage" data-ongoing={ongoing ? 'true' : undefined}
                  className="flex min-w-0 flex-col gap-2 rounded-lg border px-3 py-2.5 sm:flex-row sm:items-center sm:justify-between">
                  <div className="flex min-w-0 flex-col gap-1">
                    <span className="flex min-w-0 flex-wrap items-center gap-2 text-sm font-semibold tabular-nums">
                      <Clock aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground" />
                      <span className="[overflow-wrap:anywhere]">{formatDateSec(o.from)} – {ongoing ? t('act.tl.mon.ongoing') : formatDateSec(o.to)}</span>
                      {ongoing && <Badge variant="destructive">{t('act.tl.mon.ongoingBadge')}</Badge>}
                    </span>
                    <span className="flex min-w-0 flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                      {o.open_start && <Badge variant="outline" className="h-auto rounded-md whitespace-normal" data-slot="act-tl-open-start">{t('act.tl.mon.openStart')}</Badge>}
                      <span>{secs != null ? durationText(secs, t) : '—'}</span>
                      <span aria-hidden="true">·</span>
                      <span>{t('act.tl.mon.periodChecks', o.checks ?? 0)}</span>
                      {o.reason && <ReasonBadge code={o.reason} t={t} />}
                    </span>
                  </div>
                  <div className="flex shrink-0 flex-wrap items-center gap-2">
                    {o.alert_event_id != null && (
                      <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10"
                        onClick={() => navigateTo('alerthistory', { alert: o.alert_event_id })}
                        aria-label={t('act.tl.mon.openAlertFor', o.alert_event_id)}>
                        <BellRing aria-hidden="true" /> {t('act.alarmNo', o.alert_event_id)}
                      </Button>
                    )}
                    {o.first_activity_id != null && onOpenActivity && (
                      <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10"
                        onClick={() => onOpenActivity(o.first_activity_id)}>
                        <FileSearch aria-hidden="true" /> {t('act.tl.mon.firstActivity')}
                      </Button>
                    )}
                  </div>
                </li>
              )
            })}
          </ul>
        )}
        {sorted.length > OUTAGE_PAGE && (
          <Button type="button" variant="ghost" size="sm" className="self-start pointer-coarse:h-10" onClick={() => setAll((x) => !x)}>
            {all ? t('act.tl.mon.showLess') : t('act.tl.mon.showAll', sorted.length)}
          </Button>
        )}
      </CardContent>
    </Card>
  )
}

/** Yanıt süresi grafiği — ortalama ve en yüksek (ms) çizgi, başarısız kontrol sayısı sütun; boş kova boşluk. */
function ResponseChart({ series, t, lang, spanMs }) {
  const points = useMemo(() => seriesPoints(series, lang, spanMs), [series, lang, spanMs])
  const config = {
    avg: { label: t('act.tl.mon.chart.avg'), color: 'var(--chart-1)' },
    max: { label: t('act.tl.mon.chart.max'), color: 'var(--chart-5)' },
    fail: { label: t('act.tl.mon.chart.fail'), color: 'var(--destructive)' },
  }
  return (
    <Card data-slot="act-tl-chart" className="min-w-0 gap-2 py-4 shadow-xs">
      <CardHeader className="px-4">
        <CardTitle role="heading" aria-level={3} className="flex items-center gap-1.5 text-sm font-semibold">
          <Timer aria-hidden="true" className="size-4 text-muted-foreground" /> {t('act.tl.mon.chart.title')}
        </CardTitle>
        <CardDescription className="text-xs">{t('act.tl.mon.chart.desc')}</CardDescription>
      </CardHeader>
      <CardContent className="px-1.5 sm:px-3">
        {!hasSeriesData(points) ? (
          <p data-slot="act-tl-chart-empty" className="m-0 flex h-[72px] items-center justify-center text-sm text-muted-foreground">{t('act.tl.mon.chart.empty')}</p>
        ) : (
          <ChartContainer config={config} className="aspect-auto h-[220px] w-full" role="img" aria-label={t('act.tl.mon.chart.aria', points.length)}>
            <ComposedChart data={points} margin={{ top: 6, right: 6, bottom: 0, left: -10 }}>
              <CartesianGrid strokeDasharray="3 3" vertical={false} />
              <XAxis dataKey="label" tickLine={false} axisLine={false} tick={{ fontSize: 10 }} interval="preserveStartEnd" minTickGap={18} />
              <YAxis yAxisId="ms" width={48} tickLine={false} axisLine={false} tick={{ fontSize: 10 }} />
              <YAxis yAxisId="fail" orientation="right" allowDecimals={false} width={28} tickLine={false} axisLine={false} tick={{ fontSize: 10 }} />
              <ChartTooltip content={<ChartTooltipContent labelFormatter={(_, p) => p?.[0]?.payload?.full ?? ''} />} />
              <ChartLegend content={<ChartLegendContent className="flex-wrap pt-2 text-xs" />} />
              <Bar yAxisId="fail" dataKey="fail" fill="var(--color-fail)" fillOpacity={0.55} radius={[2, 2, 0, 0]} maxBarSize={14} />
              <Line yAxisId="ms" dataKey="avg" type="monotone" stroke="var(--color-avg)" strokeWidth={2} dot={false} connectNulls={false} />
              <Line yAxisId="ms" dataKey="max" type="monotone" stroke="var(--color-max)" strokeWidth={1.5} strokeDasharray="4 3" dot={false} connectNulls={false} />
            </ComposedChart>
          </ChartContainer>
        )}
      </CardContent>
    </Card>
  )
}
