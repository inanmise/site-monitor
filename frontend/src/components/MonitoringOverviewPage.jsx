import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Radar, RefreshCw, Globe, Radio, EthernetPort, Waypoints, CalendarClock, TextSearch, FileCheck, Gauge, Workflow,
  Search, PauseCircle, CircleAlert, Clock, HelpCircle, CheckCircle2, BellRing, ExternalLink, Trash2, Boxes, Activity,
  ListChecks, ShieldAlert, FilterX, ArrowUpRight,
} from 'lucide-react'
import { api } from '../api/client'
import { useT, useDateLocale } from '../i18n/index.jsx'
import { useIsMobile } from '../hooks/use-mobile.js'
import { usePagination } from '../hooks/usePagination.js'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { useUrlQuerySync, readUrlParam } from '../hooks/useUrlQuerySync.js'
import { navigateTo } from '../utils/navigate.js'
import { formatIncidentTime } from '../utils/incidentMeta.js'
import PageHeader from './ui/PageHeader.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import TeamBadge from './ui/TeamBadge.jsx'
import MonitorStatsBar from './MonitorStatsBar.jsx'
import { AlertLevelBadge } from './admin/alerts/AlertBadges.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent } from '@/components/shadcn/card'
import { Progress } from '@/components/shadcn/progress'
import { InputGroup, InputGroupInput, InputGroupAddon } from '@/components/shadcn/input-group'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'

/**
 * İZLEME PANOSU (2026-09-30, kullanıcı isteği): İzleme menüsünün en üstünde, 9 izleme türünün TEK ekranda durumu —
 * hangi izleme ne durumda, sorun var mı, ne zaman kontrol edilmiş, açık/çözülen alarmı var mı, çalışmayan (duraklatılmış /
 * kontrolü gecikmiş) ve silinmiş izlemeler, izleme adetleri ve pencere içi koşum sayıları.
 *
 * <p>Düzen (mobil-öncelikli, shadcn): başlık + pencere seçici (24 sa / 7 gün) · KPI şeridi (MonitorStatsBar — tıklanınca
 * listeyi duruma süzer) · tür kartları ızgarası (1/2/3 sütun; tıklanınca türe süzer, "Sayfayı aç" o izleme sayfasına
 * gider) · izleme listesi (≥768 px tablo, telefonda kartlar) + arama / tür / durum / takım süzgeçleri + sayfalama.
 * Veri: `GET /api/monitoring/overview` (dakikada bir; sekme gizliyken durur). URL durumu `mo_*` önekiyle.
 *
 * <p>Test kancaları: `data-slot="mo-page"`, `mo-type-card` (`data-type`), `mo-row` (`data-status`, `data-type`),
 * `mo-status-filter`, `mo-window`.
 */

export const TYPE_META = {
  http:      { Icon: Globe,         labelKey: 'nav.http',      tab: 'http' },
  ping:      { Icon: Radio,         labelKey: 'nav.ping',      tab: 'ping' },
  port:      { Icon: EthernetPort,  labelKey: 'nav.port',      tab: 'port' },
  dns:       { Icon: Waypoints,     labelKey: 'nav.dns',       tab: 'dns' },
  domain:    { Icon: CalendarClock, labelKey: 'nav.domainmon', tab: 'domain' },
  keyword:   { Icon: TextSearch,    labelKey: 'nav.keyword',   tab: 'keyword' },
  page:      { Icon: FileCheck,     labelKey: 'nav.page',      tab: 'page' },
  pagespeed: { Icon: Gauge,         labelKey: 'nav.pagespeed', tab: 'pagespeed' },
  scripted:  { Icon: Workflow,      labelKey: 'nav.scripted',  tab: 'scripted' },
}
export const TYPE_ORDER = Object.keys(TYPE_META)

/** Durum → ikon, ton ve sıralama ağırlığı (düşük en üstte). */
export const STATUS_META = {
  down:    { Icon: CircleAlert,  rank: 0, badge: 'border-transparent bg-destructive text-white',                          labelKey: 'mo.status.down' },
  stale:   { Icon: Clock,        rank: 1, badge: 'border-amber-500/40 bg-amber-500/10 text-amber-800 dark:text-amber-300', labelKey: 'mo.status.stale' },
  unknown: { Icon: HelpCircle,   rank: 2, badge: 'border-border bg-muted text-muted-foreground',                          labelKey: 'mo.status.unknown' },
  up:      { Icon: CheckCircle2, rank: 3, badge: 'border-success/40 bg-success/10 text-success',                           labelKey: 'mo.status.up' },
  paused:  { Icon: PauseCircle,  rank: 4, badge: 'border-border bg-muted text-muted-foreground',                          labelKey: 'mo.status.paused' },
  deleted: { Icon: Trash2,       rank: 5, badge: 'border-border bg-muted text-muted-foreground line-through',             labelKey: 'mo.status.deleted' },
}
const STATUS_ORDER = Object.keys(STATUS_META)
const WINDOWS = [24, 168]

/** Tür kartındaki başarı oranı yüzdesi (0–100) — koşum yoksa null. */
export function successPct(type) {
  const v = type?.success_rate_window
  return v == null ? null : Math.max(0, Math.min(100, Number(v)))
}

/** Liste süzgeci — saf (test edilebilir). */
export function filterRows(rows, { type = '', status = '', team = '', q = '' } = {}) {
  const needle = q.trim().toLowerCase()
  return (rows || []).filter((r) => {
    if (type && r.type !== type) return false
    if (status && r.status !== status) return false
    if (team && String(r.team_id ?? '') !== String(team)) return false
    if (needle) {
      const hay = `${r.name ?? ''} ${r.target ?? ''} ${r.team_name ?? ''}`.toLowerCase()
      if (!hay.includes(needle)) return false
    }
    return true
  }).sort((a, b) => (STATUS_META[a.status]?.rank ?? 9) - (STATUS_META[b.status]?.rank ?? 9)
    || (b.open_alerts ?? 0) - (a.open_alerts ?? 0)
    || String(a.name ?? '').localeCompare(String(b.name ?? ''), 'tr'))
}

function StatusBadge({ status, className }) {
  const t = useT()
  const meta = STATUS_META[status] ?? STATUS_META.unknown
  return (
    <Badge variant="outline" data-slot="mo-status" data-status={status}
      className={cn('gap-1 rounded-full font-semibold whitespace-nowrap', meta.badge, className)}>
      <meta.Icon aria-hidden="true" className="size-3" />{t(meta.labelKey)}
    </Badge>
  )
}

/** Tür kartı — sayılar, başarı oranı, son kontrol; tıklanınca liste türe süzülür, "Sayfayı aç" izleme sayfasına gider. */
function TypeCard({ type, active, onSelect, onOpen }) {
  const t = useT()
  const locale = useDateLocale()
  const meta = TYPE_META[type.type]
  if (!meta) return null
  const pct = successPct(type)
  const down = Number(type.down || 0), open = Number(type.open_alerts || 0)
  const tone = down > 0 || Number(type.open_critical || 0) > 0 ? 'bad' : open > 0 || Number(type.stale || 0) > 0 ? 'warn' : 'ok'
  return (
    <Card data-slot="mo-type-card" data-type={type.type} data-tone={tone} data-active={active || undefined}
      className={cn('gap-0 py-0 shadow-none transition-colors', active && 'border-primary ring-2 ring-primary/30')}>
      <CardContent className="flex flex-col gap-3 p-3.5">
        <div className="flex items-start gap-2">
          <Button type="button" variant="ghost" onClick={() => onSelect(type.type)} aria-pressed={active}
            className="h-auto min-w-0 flex-1 justify-start gap-2 px-1 py-1 text-left whitespace-normal">
            <span className={cn('grid size-9 shrink-0 place-items-center rounded-lg',
              tone === 'bad' ? 'bg-destructive/10 text-destructive' : tone === 'warn' ? 'bg-amber-500/10 text-amber-700 dark:text-amber-300' : 'bg-primary/10 text-primary')}>
              <meta.Icon aria-hidden="true" className="size-5" />
            </span>
            <span className="min-w-0">
              <span className="block truncate text-sm font-semibold">{t(meta.labelKey)}</span>
              <span className="block text-xs text-muted-foreground">{t('mo.card.counts', type.active ?? 0, type.paused ?? 0)}</span>
            </span>
          </Button>
          <Button type="button" variant="outline" size="icon-sm" aria-label={t('mo.card.open', t(meta.labelKey))} title={t('mo.card.open', t(meta.labelKey))}
            onClick={() => onOpen(meta.tab)} className="shrink-0 pointer-coarse:size-10">
            <ArrowUpRight aria-hidden="true" />
          </Button>
        </div>
        <div className="grid grid-cols-3 gap-2 text-center">
          <Stat label={t('mo.card.down')} value={down} tone={down > 0 ? 'bad' : 'muted'} />
          <Stat label={t('mo.card.stale')} value={type.stale ?? 0} tone={Number(type.stale || 0) > 0 ? 'warn' : 'muted'} />
          <Stat label={t('mo.card.alerts')} value={open} tone={open > 0 ? 'bad' : 'muted'} />
        </div>
        <div className="flex flex-col gap-1">
          <div className="flex items-center justify-between text-[11px] text-muted-foreground">
            <span>{t('mo.card.checks', Number(type.checks_window || 0).toLocaleString(locale))}</span>
            <span className="tabular-nums">{pct == null ? t('mo.card.noRuns') : t('mo.card.success', pct)}</span>
          </div>
          <Progress value={pct ?? 0} aria-label={t('mo.card.successLabel')} className={cn('h-1.5', pct != null && pct < 90 && '[&>[data-slot=progress-indicator]]:bg-destructive', pct != null && pct >= 90 && pct < 99 && '[&>[data-slot=progress-indicator]]:bg-amber-500')} />
        </div>
        <div className="flex flex-wrap items-center justify-between gap-1 text-[11px] text-muted-foreground">
          <span className="inline-flex items-center gap-1"><Clock aria-hidden="true" className="size-3" />{type.last_checked_at ? formatIncidentTime(type.last_checked_at, locale) : t('mo.card.never')}</span>
          {Number(type.resolved_window || 0) > 0 && <span className="inline-flex items-center gap-1 text-success"><CheckCircle2 aria-hidden="true" className="size-3" />{t('mo.card.resolved', type.resolved_window)}</span>}
          {Number(type.deleted || 0) > 0 && <span className="inline-flex items-center gap-1"><Trash2 aria-hidden="true" className="size-3" />{t('mo.card.deleted', type.deleted)}</span>}
        </div>
      </CardContent>
    </Card>
  )
}

function Stat({ label, value, tone }) {
  return (
    <div className="rounded-md bg-muted/40 px-1 py-1.5">
      <div className={cn('text-lg leading-none font-extrabold tabular-nums',
        tone === 'bad' ? 'text-destructive' : tone === 'warn' ? 'text-amber-700 dark:text-amber-300' : 'text-foreground')}>{value}</div>
      <div className="mt-0.5 text-[10px] font-semibold tracking-wide text-muted-foreground uppercase">{label}</div>
    </div>
  )
}

/** Satır eylemleri: izleme sayfasında aç (arama ile) · açık alarm varsa Alarm Geçmişi. */
function RowActions({ row, compact = false }) {
  const t = useT()
  const meta = TYPE_META[row.type]
  return (
    <div className="flex shrink-0 items-center gap-1">
      {Number(row.open_alerts) > 0 && (
        <Button type="button" variant="outline" size={compact ? 'sm' : 'icon-sm'} className="pointer-coarse:h-10"
          aria-label={t('mo.row.alerts', row.name)} title={t('mo.row.alerts', row.name)}
          onClick={() => navigateTo('alerthistory', { view: 'open', src: row.type, q: row.target })}>
          <BellRing aria-hidden="true" />{compact && t('mo.row.alertsShort')}
        </Button>
      )}
      <Button type="button" variant="outline" size={compact ? 'sm' : 'icon-sm'} className="pointer-coarse:h-10"
        aria-label={t('mo.row.open', row.name)} title={t('mo.row.open', row.name)}
        onClick={() => navigateTo(meta?.tab || 'http', { q: row.target })}>
        <ExternalLink aria-hidden="true" />{compact && t('mo.row.openShort')}
      </Button>
    </div>
  )
}

export default function MonitoringOverviewPage() {
  const t = useT()
  const locale = useDateLocale()
  const phone = useIsMobile()
  const [windowHours, setWindowHours] = useState(() => (Number(readUrlParam('mo_win', 24)) === 168 ? 168 : 24))
  const [state, setState] = useState({ loading: true, error: null, data: null, at: null })
  const [type, setType] = useState(() => (TYPE_META[readUrlParam('mo_type', '')] ? readUrlParam('mo_type', '') : ''))
  const [status, setStatus] = useState(() => (STATUS_META[readUrlParam('mo_status', '')] ? readUrlParam('mo_status', '') : ''))
  const [team, setTeam] = useState(() => readUrlParam('mo_team', ''))
  const [q, setQ] = useState(() => readUrlParam('mo_q', ''))
  useUrlQuerySync({ mo_win: windowHours === 24 ? null : String(windowHours), mo_type: type || null, mo_status: status || null, mo_team: team || null, mo_q: q || null })

  const load = useCallback(async () => {
    try {
      const res = await api.monitoring.getOverview(windowHours)
      if (res?.success && res.data) setState({ loading: false, error: null, data: res.data, at: new Date() })
      else setState((s) => ({ ...s, loading: false, error: res?.error || t('mo.loadError') }))
    } catch (e) {
      setState((s) => ({ ...s, loading: false, error: e?.message || t('mo.loadError') }))
    }
  }, [windowHours, t])
  // İlk yükleme + pencere değişince yeniden yükleme (load kimliği windowHours ile değişir); dakikalık yoklama ayrı.
  useEffect(() => { load() }, [load])
  useVisibleInterval(load, 60_000, false)

  const data = state.data
  const rows = data?.monitors || []
  const teams = useMemo(() => {
    const m = new Map()
    for (const r of rows) if (r.team_id != null && r.team_name) m.set(String(r.team_id), r.team_name)
    return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1], 'tr'))
  }, [rows])
  const filtered = useMemo(() => filterRows(rows, { type, status, team, q }), [rows, type, status, team, q])
  const pager = usePagination(filtered, { listKey: 'monitoring-overview', preset: 'page', resetDeps: [type, status, team, q, windowHours] })
  const totals = data?.totals || {}
  const anyFilter = !!(type || status || team || q)
  const clearFilters = () => { setType(''); setStatus(''); setTeam(''); setQ('') }

  const kpis = [
    { key: 'total',   Icon: Boxes,       label: t('mo.kpi.total'),   value: totals.total ?? 0,   cls: 'total',    onClick: () => setStatus('') },
    { key: 'up',      Icon: CheckCircle2, label: t('mo.kpi.up'),      value: Math.max(0, (totals.active ?? 0) - (totals.down ?? 0) - (totals.stale ?? 0) - (totals.unknown ?? 0)), cls: 'valid', onClick: () => setStatus((s) => (s === 'up' ? '' : 'up')) },
    { key: 'down',    Icon: CircleAlert, label: t('mo.kpi.down'),    value: totals.down ?? 0,    cls: 'critical', onClick: () => setStatus((s) => (s === 'down' ? '' : 'down')) },
    { key: 'stale',   Icon: Clock,       label: t('mo.kpi.stale'),   value: totals.stale ?? 0,   cls: 'warning',  onClick: () => setStatus((s) => (s === 'stale' ? '' : 'stale')) },
    { key: 'paused',  Icon: PauseCircle, label: t('mo.kpi.paused'),  value: totals.paused ?? 0,  cls: 'paused',   onClick: () => setStatus((s) => (s === 'paused' ? '' : 'paused')) },
    { key: 'alerts',  Icon: BellRing,    label: t('mo.kpi.alerts'),  value: totals.open_alerts ?? 0, cls: 'alert', hint: t('mo.kpi.alertsHint', totals.open_critical ?? 0), onClick: () => navigateTo('alerthistory', { view: 'open' }) },
    { key: 'checks',  Icon: ListChecks,  label: t('mo.kpi.checks', windowHours === 24 ? t('mo.win.24') : t('mo.win.168')), value: Number(totals.checks_window ?? 0).toLocaleString(locale), cls: 'total',
      sub: t('mo.kpi.failed', Number(totals.failed_window ?? 0).toLocaleString(locale)), onClick: () => {} },
    { key: 'resolved', Icon: ShieldAlert, label: t('mo.kpi.resolved'), value: totals.resolved_window ?? 0, cls: 'valid', onClick: () => navigateTo('alerthistory', { view: 'closed' }) },
  ]

  return (
    <div data-slot="mo-page" className="min-w-0">
      <PageHeader icon={Radar} title={t('mo.title')} description={t('mo.subtitle')}
        meta={state.at ? <span className="text-xs text-muted-foreground">{t('mo.updated', state.at.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' }))}</span> : null}
        actions={<>
          <ToggleGroup type="single" value={String(windowHours)} onValueChange={(v) => { if (v) { setWindowHours(Number(v)); setState((s) => ({ ...s, loading: true })) } }}
            variant="outline" size="sm" data-slot="mo-window" aria-label={t('mo.win.label')}>
            {WINDOWS.map((w) => <ToggleGroupItem key={w} value={String(w)} className="pointer-coarse:h-10">{t(w === 24 ? 'mo.win.24' : 'mo.win.168')}</ToggleGroupItem>)}
          </ToggleGroup>
          <Button type="button" variant="outline" size="sm" onClick={load} className="pointer-coarse:h-10">
            <RefreshCw aria-hidden="true" className={cn(state.loading && 'animate-spin motion-reduce:animate-none')} />{t('mo.refresh')}
          </Button>
        </>} />

      {state.loading && !data && <LoadingBlock label={t('mo.loading')} fullWidth />}
      {state.error && !data && <StatusBlock tone="danger" icon={CircleAlert} title={t('mo.loadError')} description={state.error}
        actions={<Button type="button" variant="outline" onClick={load}>{t('mo.refresh')}</Button>} />}

      {data && (
        <>
          <MonitorStatsBar items={kpis} activeFilter={status || (!status ? 'total' : null)} onStatClick={() => {}} />

          {/* Tür kartları — 1 / 2 / 3 sütun */}
          <section aria-label={t('mo.types')} className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {(data.types || []).map((ty) => (
              <TypeCard key={ty.type} type={ty} active={type === ty.type}
                onSelect={(k) => setType((cur) => (cur === k ? '' : k))} onOpen={(tab) => navigateTo(tab)} />
            ))}
          </section>

          {/* Liste araç çubuğu */}
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <InputGroup className="w-full sm:w-72">
              <InputGroupAddon><Search aria-hidden="true" className="size-4" /></InputGroupAddon>
              <InputGroupInput value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('mo.searchPlaceholder')} aria-label={t('mo.search')} />
            </InputGroup>
            <NativeSelect value={type} onChange={(e) => setType(e.target.value)} aria-label={t('mo.filter.type')} className="w-full sm:w-44">
              <NativeSelectOption value="">{t('mo.filter.allTypes')}</NativeSelectOption>
              {TYPE_ORDER.map((k) => <NativeSelectOption key={k} value={k}>{t(TYPE_META[k].labelKey)}</NativeSelectOption>)}
            </NativeSelect>
            <NativeSelect value={status} onChange={(e) => setStatus(e.target.value)} aria-label={t('mo.filter.status')} data-slot="mo-status-filter" className="w-full sm:w-40">
              <NativeSelectOption value="">{t('mo.filter.allStatuses')}</NativeSelectOption>
              {STATUS_ORDER.map((k) => <NativeSelectOption key={k} value={k}>{t(STATUS_META[k].labelKey)}</NativeSelectOption>)}
            </NativeSelect>
            {teams.length > 1 && (
              <NativeSelect value={team} onChange={(e) => setTeam(e.target.value)} aria-label={t('mo.filter.team')} className="w-full sm:w-48">
                <NativeSelectOption value="">{t('mo.filter.allTeams')}</NativeSelectOption>
                {teams.map(([id, name]) => <NativeSelectOption key={id} value={id}>{name}</NativeSelectOption>)}
              </NativeSelect>
            )}
            <span className="text-xs text-muted-foreground">{t('mo.count', filtered.length, rows.length)}</span>
            {anyFilter && (
              <Button type="button" variant="ghost" size="sm" onClick={clearFilters} className="ml-auto pointer-coarse:h-10">
                <FilterX aria-hidden="true" />{t('mo.clearFilters')}
              </Button>
            )}
          </div>

          {filtered.length === 0 ? (
            <StatusBlock tone={rows.length === 0 ? 'neutral' : 'neutral'} icon={rows.length === 0 ? Activity : FilterX}
              title={rows.length === 0 ? t('mo.empty') : t('mo.emptyFiltered')}
              description={rows.length === 0 ? t('mo.emptyText') : t('mo.emptyFilteredText')} className="py-12"
              actions={anyFilter ? <Button type="button" variant="outline" onClick={clearFilters}><FilterX aria-hidden="true" />{t('mo.clearFilters')}</Button> : null} />
          ) : phone ? (
            <ul className="m-0 flex list-none flex-col gap-2 p-0" data-slot="mo-list">
              {pager.pageItems.map((r) => {
                const meta = TYPE_META[r.type]
                return (
                  <li key={`${r.type}-${r.id}`}>
                    <Card data-slot="mo-row" data-type={r.type} data-status={r.status} className="gap-0 py-0 shadow-none">
                      <CardContent className="flex flex-col gap-2 p-3">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <StatusBadge status={r.status} />
                          {r.open_alert_level && <AlertLevelBadge level={r.open_alert_level} className="text-[0.7em]" />}
                          <Badge variant="outline" className="ml-auto gap-1 text-[11px] font-normal">{meta && <meta.Icon aria-hidden="true" className="size-3" />}{meta ? t(meta.labelKey) : r.type}</Badge>
                        </div>
                        <div className="min-w-0">
                          <div className="truncate text-sm font-semibold">{r.name}</div>
                          {r.target && r.target !== r.name && <div className="truncate font-mono text-xs text-muted-foreground">{r.target}</div>}
                        </div>
                        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                          {r.team_name && <TeamBadge teamId={r.team_id} teamName={r.team_name} size={12} />}
                          <span className="inline-flex items-center gap-1"><Clock aria-hidden="true" className="size-3" />{r.last_checked_at ? formatIncidentTime(r.last_checked_at, locale) : t('mo.card.never')}</span>
                          <span>{t('mo.row.checks', r.checks_window ?? 0, r.failed_window ?? 0)}</span>
                        </div>
                        {r.last_error && r.status === 'down' && <p className="line-clamp-2 text-xs text-destructive [overflow-wrap:anywhere]">{r.last_error}</p>}
                        <RowActions row={r} compact />
                      </CardContent>
                    </Card>
                  </li>
                )
              })}
            </ul>
          ) : (
            <div className="overflow-x-auto rounded-lg border">
              <Table data-slot="mo-table">
                <TableHeader>
                  <TableRow>
                    <TableHead>{t('mo.col.status')}</TableHead>
                    <TableHead>{t('mo.col.monitor')}</TableHead>
                    <TableHead>{t('mo.col.type')}</TableHead>
                    <TableHead>{t('mo.col.team')}</TableHead>
                    <TableHead>{t('mo.col.lastCheck')}</TableHead>
                    <TableHead className="text-right">{t('mo.col.checks')}</TableHead>
                    <TableHead>{t('mo.col.alert')}</TableHead>
                    <TableHead className="text-right">{t('mo.col.actions')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {pager.pageItems.map((r) => {
                    const meta = TYPE_META[r.type]
                    return (
                      <TableRow key={`${r.type}-${r.id}`} data-slot="mo-row" data-type={r.type} data-status={r.status}>
                        <TableCell><StatusBadge status={r.status} /></TableCell>
                        <TableCell className="max-w-[22rem] min-w-0">
                          <div className="truncate font-semibold" title={r.name}>{r.name}</div>
                          {r.target && r.target !== r.name && <div className="truncate font-mono text-xs text-muted-foreground" title={r.target}>{r.target}</div>}
                          {r.last_error && r.status === 'down' && <div className="line-clamp-1 text-xs text-destructive" title={r.last_error}>{r.last_error}</div>}
                        </TableCell>
                        <TableCell><span className="inline-flex items-center gap-1 text-xs">{meta && <meta.Icon aria-hidden="true" className="size-3.5 text-muted-foreground" />}{meta ? t(meta.labelKey) : r.type}</span></TableCell>
                        <TableCell>{r.team_name ? <TeamBadge teamId={r.team_id} teamName={r.team_name} size={12} /> : <span className="text-muted-foreground">—</span>}</TableCell>
                        <TableCell className="whitespace-nowrap text-xs">{r.last_checked_at ? formatIncidentTime(r.last_checked_at, locale) : <span className="text-muted-foreground">{t('mo.card.never')}</span>}</TableCell>
                        <TableCell className="text-right text-xs tabular-nums">{t('mo.row.checks', r.checks_window ?? 0, r.failed_window ?? 0)}</TableCell>
                        <TableCell>{r.open_alert_level ? <AlertLevelBadge level={r.open_alert_level} className="text-[0.75em]" /> : <span className="text-xs text-muted-foreground">—</span>}</TableCell>
                        <TableCell className="text-right"><RowActions row={r} /></TableCell>
                      </TableRow>
                    )
                  })}
                </TableBody>
              </Table>
            </div>
          )}
          {filtered.length > 0 && <PaginationBar {...pager} />}
        </>
      )}
    </div>
  )
}
