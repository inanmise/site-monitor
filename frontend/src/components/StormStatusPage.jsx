// Alarm Fırtınası sayfası (2026-09-30, kullanıcı isteği): takım bazında "eşiğe ne kadar yakın / açık fırtına var mı /
// kim ne zaman hangi eşikle aştı", geçmiş fırtınalar (süzgeç + sayfalama) ve analiz (gün serisi, takım özeti, kapanış
// nedenleri). Görüş kapsamı sunucuda (Alarm Geçmişi kuralı); kullanıcı yalnız kendi takımlarını görür. URL: sf_*.
import { useCallback, useEffect, useMemo, useState } from 'react'
import { CloudLightning, RefreshCw, Users, Radar, Gauge, Timer, ShieldCheck, Settings2, FilterX, Eye, Activity } from 'lucide-react'
import { api } from '../api/client'
import { useT, useDateLocale } from '../i18n/index.jsx'
import { useIsMobile } from '../hooks/use-mobile.js'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import { useServerPagination } from '../hooks/useServerPagination.js'
import { navigateTo } from '../utils/navigate.js'
import { formatDuration, formatIncidentTime } from '../utils/incidentMeta.js'
import PageHeader from './ui/PageHeader.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import TeamBadge from './ui/TeamBadge.jsx'
import MonitorStatsBar from './MonitorStatsBar.jsx'
import StormTeamCards, { ReasonBadge, StormSummary } from './storm/StormTeamCards.jsx'
import StormDetailModal from './storm/StormDetailModal.jsx'
import StormRulesCard, { thresholdPhrase } from './storm/StormRulesCard.jsx'
import { ANALYTICS_DAYS, chartRows, isDay } from './storm/stormModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { Input } from '@/components/shadcn/input'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/shadcn/tabs'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Label } from '@/components/shadcn/label'
import {
  ChartContainer, ChartTooltip, ChartLegend, ChartLegendContent, BarChart, Bar, XAxis, YAxis, CartesianGrid,
} from '@/components/shadcn/chart'

const TABS = ['status', 'history', 'analytics']
const PALETTE = ['var(--chart-1)', 'var(--chart-2)', 'var(--chart-3)', 'var(--chart-4)', 'var(--chart-5)']

function dayLabel(day) { return day ? `${day.slice(8, 10)}.${day.slice(5, 7)}` : '' }

/** Geçmiş listesi (sunucu sayfalı): ≥768 tablo, telefonda kartlar. */
function HistoryList({ items, onDetail }) {
  const t = useT()
  const locale = useDateLocale()
  const phone = useIsMobile()
  const cell = (s) => ({
    id: <Badge variant="outline" className="gap-1 font-semibold"><CloudLightning aria-hidden="true" className="size-3" />#{s.id}</Badge>,
    team: s.team_id != null ? <TeamBadge teamId={s.team_id} teamName={s.team_name} size={12} static /> : <Badge variant="outline">{t('sf.legacy')}</Badge>,
    opened: <span className="whitespace-nowrap text-xs">{formatIncidentTime(s.created_at, locale)}</span>,
    duration: <span className="text-xs tabular-nums">{s.duration_ms != null ? formatDuration(s.duration_ms, t) : '—'}</span>,
    members: <span className="text-xs tabular-nums">{t('sf.history.membersText', s.members_total ?? s.member_count ?? 0, s.members_recovered ?? '—')}</span>,
    reason: <ReasonBadge storm={s} />,
    cause: <span className="text-xs">{s.root_cause || '—'}{s.targets_at_open != null && <span className="text-muted-foreground"> · {t('sf.history.openedWith', s.targets_at_open, s.threshold_effective ?? '—')}</span>}</span>,
    action: <Button type="button" size="sm" variant="outline" className="pointer-coarse:h-10" onClick={() => onDetail(s.id)}><Eye aria-hidden="true" />{t('sf.action.detail')}</Button>,
  })
  if (phone) {
    return (
      <ul className="m-0 flex list-none flex-col gap-2 p-0" data-slot="sf-history">
        {items.map((s) => { const c = cell(s); return (
          <li key={s.id}>
            <Card data-slot="sf-history-row" data-storm-id={s.id} className="gap-0 py-0 shadow-none">
              <CardContent className="flex flex-col gap-1.5 p-3">
                <div className="flex flex-wrap items-center gap-1.5">{c.id}{c.team}<span className="ml-auto">{c.reason}</span></div>
                <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-muted-foreground">{c.opened}{c.duration}{c.members}</div>
                <div>{c.cause}</div>
                <div>{c.action}</div>
              </CardContent>
            </Card>
          </li>) })}
      </ul>
    )
  }
  return (
    <div className="overflow-x-auto rounded-lg border">
      <Table data-slot="sf-history">
        <TableHeader>
          <TableRow>
            <TableHead>{t('sf.history.col.storm')}</TableHead>
            <TableHead>{t('sf.history.col.team')}</TableHead>
            <TableHead>{t('sf.history.col.opened')}</TableHead>
            <TableHead>{t('sf.history.col.duration')}</TableHead>
            <TableHead className="hidden lg:table-cell">{t('sf.history.col.members')}</TableHead>
            <TableHead>{t('sf.history.col.reason')}</TableHead>
            <TableHead className="hidden md:table-cell">{t('sf.history.col.cause')}</TableHead>
            <TableHead className="text-right">{t('sf.history.col.actions')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.map((s) => { const c = cell(s); return (
            <TableRow key={s.id} data-slot="sf-history-row" data-storm-id={s.id}>
              <TableCell>{c.id}</TableCell><TableCell>{c.team}</TableCell><TableCell>{c.opened}</TableCell><TableCell>{c.duration}</TableCell>
              <TableCell className="hidden lg:table-cell">{c.members}</TableCell><TableCell>{c.reason}</TableCell>
              <TableCell className="hidden md:table-cell">{c.cause}</TableCell><TableCell className="text-right">{c.action}</TableCell>
            </TableRow>) })}
        </TableBody>
      </Table>
    </div>
  )
}

function ChartTip({ active, payload, keys, t }) {
  if (!active || !payload?.length) return null
  const p = payload[0]?.payload || {}
  return (
    <div className="rounded-lg border bg-popover px-2.5 py-1.5 text-xs text-popover-foreground shadow-lg">
      <div className="font-bold">{dayLabel(p.day)}</div>
      {keys.filter((k) => p[k.key] > 0).map((k) => <div key={k.key}>{k.name || t('sf.legacy')}: <b>{p[k.key]}</b></div>)}
      {p.other > 0 && <div>{t('sf.chart.other')}: <b>{p.other}</b></div>}
      <div className="text-muted-foreground">{t('sf.chart.total')}: <b>{p.total}</b></div>
    </div>
  )
}

export default function StormStatusPage() {
  const t = useT()
  const locale = useDateLocale()
  const phone = useIsMobile()
  const [tab, setTab] = useState(() => (TABS.includes(readUrlParam('sf_tab', '')) ? readUrlParam('sf_tab', '') : 'status'))
  const [team, setTeam] = useState(() => readUrlParam('sf_team', ''))
  const [from, setFrom] = useState(() => (isDay(readUrlParam('sf_from', '')) ? readUrlParam('sf_from', '') : ''))
  const [to, setTo] = useState(() => (isDay(readUrlParam('sf_to', '')) ? readUrlParam('sf_to', '') : ''))
  const [resolvedOnly, setResolvedOnly] = useState(() => readUrlParam('sf_res', '') === '1')
  const [days, setDays] = useState(() => (ANALYTICS_DAYS.includes(readUrlInt('sf_days', 30)) ? readUrlInt('sf_days', 30) : 30))
  const [stormId, setStormId] = useState(() => { const v = readUrlInt('sf_storm', 0); return v > 0 ? v : null })
  useUrlQuerySync({
    sf_tab: tab === 'status' ? null : tab, sf_team: team || null, sf_from: from || null, sf_to: to || null,
    sf_res: resolvedOnly ? '1' : null, sf_days: days === 30 ? null : String(days),
    sf_storm: stormId != null ? String(stormId) : null,
  })

  // Geçmiş: sunucu sayfalı liste standardı (useServerPagination + PaginationBar) — süzgeç değişince sayfa 1
  const sp = useServerPagination({ listKey: 'storm-history', preset: 'page', resetDeps: [team, from, to, resolvedOnly],
    apiBase: 0, url: { pageKey: 'sf_page', sizeKey: 'sf_ps' } })
  const [status, setStatus] = useState({ loading: true, error: null, data: null })
  const [history, setHistory] = useState({ loading: false, error: null, data: null })
  const [analytics, setAnalytics] = useState({ loading: false, error: null, data: null })

  const loadStatus = useCallback(async (fresh = false) => {
    try {
      const res = await api.monitoring.storm.status(fresh === true)   // "Yenile" sunucu ezberini atlar (10 sn)
      if (res?.success && res.data && !Array.isArray(res.data)) setStatus({ loading: false, error: null, data: res.data })
      else setStatus((s) => ({ loading: false, error: res?.error || t('sf.loadError'), data: s.data }))
    } catch (e) { setStatus((s) => ({ loading: false, error: e?.message || t('sf.loadError'), data: s.data })) }
  }, [t])
  useEffect(() => { loadStatus() }, [loadStatus])
  useVisibleInterval(loadStatus, 30_000, false)

  const loadHistory = useCallback(async () => {
    setHistory((h) => ({ ...h, loading: true, error: null }))
    try {
      const res = await api.monitoring.storm.history({ teamId: team || undefined, from: from || undefined, to: to || undefined, resolvedOnly, page: sp.apiPage, size: sp.pageSize })
      if (res?.success && res.data && !Array.isArray(res.data)) { sp.bind(res); setHistory({ loading: false, error: null, data: res.data }) }
      else setHistory({ loading: false, error: res?.error || t('sf.loadError'), data: null })
    } catch (e) { setHistory({ loading: false, error: e?.message || t('sf.loadError'), data: null }) }
  }, [team, from, to, resolvedOnly, sp.apiPage, sp.pageSize, t])
  useEffect(() => { if (tab === 'history') loadHistory() }, [tab, loadHistory])

  const loadAnalytics = useCallback(async () => {
    setAnalytics((a) => ({ ...a, loading: true, error: null }))
    try {
      const res = await api.monitoring.storm.analytics({ teamId: team || undefined, days })
      if (res?.success && res.data && !Array.isArray(res.data)) setAnalytics({ loading: false, error: null, data: res.data })
      else setAnalytics({ loading: false, error: res?.error || t('sf.loadError'), data: null })
    } catch (e) { setAnalytics({ loading: false, error: e?.message || t('sf.loadError'), data: null }) }
  }, [team, days, t])
  useEffect(() => { if (tab === 'analytics') loadAnalytics() }, [tab, loadAnalytics])

  const data = status.data
  const teams = data?.teams || []
  const teamOptions = useMemo(() => teams.map((x) => ({ id: String(x.team_id), name: x.team_name })), [teams])
  const totals = data?.totals || {}
  const an = analytics.data
  const chart = useMemo(() => chartRows(an), [an])
  const chartConfig = useMemo(() => {
    const cfg = {}
    chart.keys.forEach((k, i) => { cfg[k.key] = { label: k.name || t('sf.legacy'), color: PALETTE[i % PALETTE.length] } })
    if (chart.hasOther) cfg.other = { label: t('sf.chart.other'), color: 'var(--muted-foreground)' }
    return cfg
  }, [chart, t])

  const kpis = [
    { key: 'teams',    Icon: Users,          label: t('sf.kpi.teams'),    value: totals.teams ?? teams.length, cls: 'total' },
    { key: 'storming', Icon: CloudLightning, label: t('sf.kpi.storming'), value: totals.storming ?? 0, cls: 'critical', onClick: () => setTab('status') },
    { key: 'near',     Icon: Gauge,          label: t('sf.kpi.near'),     value: totals.near ?? 0,     cls: 'warning', onClick: () => setTab('status') },
    { key: 'open',     Icon: Radar,          label: t('sf.kpi.open'),     value: totals.open_storms ?? 0, cls: 'alert', onClick: () => setTab('status') },
    { key: 'total30',  Icon: Activity,       label: t('sf.kpi.total30'),  value: teams.reduce((a, x) => a + (x.storms_30d || 0), 0), cls: 'weak', onClick: () => setTab('analytics') },
    ...(an ? [
      { key: 'avgdur', Icon: Timer,       label: t('sf.kpi.avgDuration'), value: an.avg_duration_ms != null ? formatDuration(an.avg_duration_ms, t) : '—', cls: 'valid' },
      { key: 'sealed', Icon: ShieldCheck, label: t('sf.kpi.sealed'),      value: an.sealed ?? 0, cls: 'paused' },
    ] : []),
  ]

  const settings = data?.settings
  // Açıklama GERÇEK ayarlardan (2026-10-01): pencere ve eşik sunucunun uyguladığı değerler; veri gelmeden genel metin.
  const description = settings
    ? t('sf.descWith', settings.window_minutes, thresholdPhrase(settings, t))
    : t('sf.descGeneric')
  const meta = settings?.enabled === false ? <Badge variant="destructive">{t('sf.settings.disabled')}</Badge> : null

  const hist = history.data
  const anyHistFilter = !!(team || from || to || resolvedOnly)
  const clearHist = () => { setTeam(''); setFrom(''); setTo(''); setResolvedOnly(false); sp.reset() }

  return (
    <div className="flex min-w-0 flex-col gap-4" data-slot="sf-page">
      <PageHeader icon={CloudLightning} title={t('sf.title')} description={description} meta={meta}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => navigateTo('settings', { sec: 'storm' })}>
              <Settings2 aria-hidden="true" />{t('sf.action.settings')}
            </Button>
            <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => { loadStatus(true); if (tab === 'history') loadHistory(); if (tab === 'analytics') loadAnalytics() }}>
              <RefreshCw aria-hidden="true" />{t('sf.refresh')}
            </Button>
          </div>
        } />

      {status.loading && !data && <LoadingBlock label={t('sf.loading')} fullWidth />}
      {status.error && !data && <StatusBlock tone="danger" icon={CloudLightning} title={t('sf.loadError')} description={status.error}
        actions={<Button type="button" variant="outline" onClick={() => loadStatus(true)}>{t('sf.refresh')}</Button>} />}

      {data && (
        <>
          <MonitorStatsBar items={kpis} activeFilter={null} onStatClick={() => {}} />

          {/* Geçerli fırtına kuralları — Ayarlar → Alarm Fırtınası'ndaki değerlerin özeti */}
          <StormRulesCard settings={settings} />

          <Tabs value={tab} onValueChange={(v) => setTab(TABS.includes(v) ? v : 'status')}>
            <TabsList className="h-auto w-full flex-wrap justify-start gap-1">
              <TabsTrigger value="status" className="pointer-coarse:h-10">{t('sf.tab.status')}</TabsTrigger>
              <TabsTrigger value="history" className="pointer-coarse:h-10">{t('sf.tab.history')}</TabsTrigger>
              <TabsTrigger value="analytics" className="pointer-coarse:h-10">{t('sf.tab.analytics')}</TabsTrigger>
            </TabsList>

            {/* ── Durum ── */}
            <TabsContent value="status" className="mt-3 flex flex-col gap-3">
              {teams.length === 0 ? (
                <StatusBlock tone="neutral" icon={Users} title={t('sf.empty')} description={t('sf.emptyDesc')} className="py-12" />
              ) : (
                <StormTeamCards teams={teams} onDetail={setStormId} />
              )}
              {(data.legacy_open || []).length > 0 && (
                <section aria-label={t('sf.legacyOpen')} className="flex flex-col gap-2">
                  <h3 className="m-0 text-sm font-semibold">{t('sf.legacyOpen')}</h3>
                  {data.legacy_open.map((s) => <StormSummary key={s.id} storm={s} onDetail={setStormId} />)}
                </section>
              )}
            </TabsContent>

            {/* ── Geçmiş ── */}
            <TabsContent value="history" className="mt-3 flex flex-col gap-3">
              <div className="flex flex-wrap items-end gap-2" data-slot="sf-history-toolbar">
                {teamOptions.length > 1 && (
                  <NativeSelect value={team} onChange={(e) => setTeam(e.target.value)} aria-label={t('sf.history.team')} className="w-full sm:w-48">
                    <NativeSelectOption value="">{t('sf.history.allTeams')}</NativeSelectOption>
                    {teamOptions.map((o) => <NativeSelectOption key={o.id} value={o.id}>{o.name}</NativeSelectOption>)}
                  </NativeSelect>
                )}
                <div className="flex w-full flex-col gap-1 sm:w-auto">
                  <Label htmlFor="sf-from" className="text-xs text-muted-foreground">{t('sf.history.from')}</Label>
                  <Input id="sf-from" type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} className="w-full sm:w-40" />
                </div>
                <div className="flex w-full flex-col gap-1 sm:w-auto">
                  <Label htmlFor="sf-to" className="text-xs text-muted-foreground">{t('sf.history.to')}</Label>
                  <Input id="sf-to" type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} className="w-full sm:w-40" />
                </div>
                <div className="flex h-10 items-center gap-2 sm:h-9">
                  <Checkbox id="sf-res" checked={resolvedOnly} onCheckedChange={(v) => setResolvedOnly(!!v)} />
                  <Label htmlFor="sf-res" className="text-sm">{t('sf.history.resolvedOnly')}</Label>
                </div>
                {anyHistFilter && (
                  <Button type="button" variant="ghost" size="sm" className="ml-auto pointer-coarse:h-10" onClick={clearHist}><FilterX aria-hidden="true" />{t('sf.history.clear')}</Button>
                )}
              </div>
              {history.loading && !hist && <LoadingBlock label={t('sf.loading')} fullWidth />}
              {history.error && <StatusBlock tone="danger" icon={CloudLightning} title={t('sf.loadError')} description={history.error} actions={<Button type="button" variant="outline" onClick={loadHistory}>{t('sf.refresh')}</Button>} />}
              {hist && (hist.items || []).length === 0 && (
                <StatusBlock tone="neutral" icon={CloudLightning} title={t('sf.history.empty')} className="py-12"
                  actions={anyHistFilter ? <Button type="button" variant="outline" onClick={clearHist}><FilterX aria-hidden="true" />{t('sf.history.clear')}</Button> : null} />
              )}
              {hist && (hist.items || []).length > 0 && (
                <>
                  <HistoryList items={hist.items} onDetail={setStormId} />
                  <PaginationBar {...sp.bar} />
                </>
              )}
            </TabsContent>

            {/* ── Analiz ── */}
            <TabsContent value="analytics" className="mt-3 flex flex-col gap-3">
              <div className="flex flex-wrap items-center gap-2" data-slot="sf-analytics-toolbar">
                <ToggleGroup type="single" variant="outline" size="sm" value={String(days)} onValueChange={(v) => { if (v) setDays(Number(v)) }} aria-label={t('sf.analytics.window')}>
                  {ANALYTICS_DAYS.map((d) => <ToggleGroupItem key={d} value={String(d)} className="pointer-coarse:h-10">{t('sf.analytics.days', d)}</ToggleGroupItem>)}
                </ToggleGroup>
                {teamOptions.length > 1 && (
                  <NativeSelect value={team} onChange={(e) => setTeam(e.target.value)} aria-label={t('sf.history.team')} className="w-full sm:w-48">
                    <NativeSelectOption value="">{t('sf.history.allTeams')}</NativeSelectOption>
                    {teamOptions.map((o) => <NativeSelectOption key={o.id} value={o.id}>{o.name}</NativeSelectOption>)}
                  </NativeSelect>
                )}
              </div>
              {analytics.loading && !an && <LoadingBlock label={t('sf.loading')} fullWidth />}
              {analytics.error && <StatusBlock tone="danger" icon={CloudLightning} title={t('sf.loadError')} description={analytics.error} actions={<Button type="button" variant="outline" onClick={loadAnalytics}>{t('sf.refresh')}</Button>} />}
              {an && (
                <div className="grid grid-cols-1 gap-3 xl:grid-cols-3">
                  <Card className="min-w-0 gap-3 px-3 py-3 sm:px-4 xl:col-span-2">
                    <h3 className="m-0 text-sm font-semibold">{t('sf.analytics.perDay')} <span className="font-normal text-muted-foreground">({an.from} – {an.to}, {t('sf.analytics.totalText', an.total ?? 0)})</span></h3>
                    {(an.total ?? 0) === 0 ? (
                      <p className="m-0 py-6 text-center text-sm text-muted-foreground">{t('sf.analytics.empty')}</p>
                    ) : (
                      <ChartContainer config={chartConfig} className="aspect-auto h-[220px] w-full" data-slot="sf-chart">
                        <BarChart accessibilityLayer data={chart.rows} margin={{ left: 0, right: 8, top: 8, bottom: 0 }}>
                          <CartesianGrid vertical={false} strokeDasharray="3 3" />
                          <XAxis dataKey="day" tickFormatter={dayLabel} minTickGap={18} tickLine={false} axisLine={false} />
                          <YAxis allowDecimals={false} width={32} tickLine={false} axisLine={false} />
                          <ChartTooltip content={<ChartTip keys={chart.keys} t={t} />} />
                          <ChartLegend content={<ChartLegendContent />} />
                          {chart.keys.map((k) => <Bar key={k.key} dataKey={k.key} stackId="s" fill={`var(--color-${k.key})`} isAnimationActive={false} />)}
                          {chart.hasOther && <Bar dataKey="other" stackId="s" fill="var(--color-other)" isAnimationActive={false} />}
                        </BarChart>
                      </ChartContainer>
                    )}
                  </Card>
                  <Card className="min-w-0 gap-2 px-3 py-3 sm:px-4">
                    <h3 className="m-0 text-sm font-semibold">{t('sf.analytics.reasons')}</h3>
                    <div className="flex flex-wrap gap-1.5" data-slot="sf-reasons">
                      {Object.entries(an.reasons || {}).map(([k, v]) => (
                        <span key={k} className="inline-flex items-center gap-1 text-xs"><ReasonBadge storm={{ resolved: k !== 'OPEN', resolve_reason: k }} /><b className="tabular-nums">{v}</b></span>
                      ))}
                      {Object.keys(an.reasons || {}).length === 0 && <span className="text-xs text-muted-foreground">—</span>}
                    </div>
                    <h3 className="m-0 mt-2 text-sm font-semibold">{t('sf.analytics.causes')}</h3>
                    <div className="flex flex-wrap gap-1.5" data-slot="sf-causes">
                      {Object.entries(an.root_causes || {}).sort((a, b) => b[1] - a[1]).map(([k, v]) => <Badge key={k} variant="outline" className="gap-1">{k}<b className="tabular-nums">{v}</b></Badge>)}
                      {Object.keys(an.root_causes || {}).length === 0 && <span className="text-xs text-muted-foreground">—</span>}
                    </div>
                    <h3 className="m-0 mt-2 text-sm font-semibold">{t('sf.analytics.hours')}</h3>
                    <div className="grid grid-cols-12 gap-0.5" data-slot="sf-hours" aria-label={t('sf.analytics.hours')}>
                      {(an.hours || []).map((n, h) => {
                        const max = Math.max(1, ...(an.hours || [0]))
                        return <span key={h} title={`${String(h).padStart(2, '0')}:00 · ${n}`} className="h-4 rounded-[2px] bg-primary" style={{ opacity: n ? 0.25 + 0.75 * (n / max) : 0.08 }} />
                      })}
                    </div>
                  </Card>
                  <Card className="min-w-0 gap-2 px-0 py-3 xl:col-span-3">
                    <h3 className="m-0 px-3 text-sm font-semibold sm:px-4">{t('sf.analytics.byTeam')}</h3>
                    {(an.teams || []).length === 0 ? <p className="m-0 px-3 text-sm text-muted-foreground sm:px-4">{t('sf.analytics.empty')}</p> : phone ? (
                      <ul className="m-0 flex list-none flex-col gap-2 px-3 p-0" data-slot="sf-team-stats">
                        {an.teams.map((x) => (
                          <li key={String(x.team_id)} className="rounded-md border p-2.5 text-xs">
                            <div className="mb-1 flex items-center gap-1.5">{x.team_id != null ? <TeamBadge teamId={x.team_id} teamName={x.team_name} size={12} static /> : <Badge variant="outline">{t('sf.legacy')}</Badge>}<b className="ml-auto tabular-nums">{x.storms}</b></div>
                            <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-muted-foreground">
                              <span>{t('sf.analytics.col.open')}: {x.open}</span><span>{t('sf.analytics.col.sealed')}: {x.sealed}</span><span>{t('sf.analytics.col.floor')}: {x.floor}</span>
                              <span>{t('sf.analytics.col.avgDur')}: {x.avg_duration_ms != null ? formatDuration(x.avg_duration_ms, t) : '—'}</span>
                              <span>{t('sf.analytics.col.peak')}: {x.max_peak_targets ?? '—'}</span>
                            </div>
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <div className="overflow-x-auto">
                        <Table data-slot="sf-team-stats">
                          <TableHeader>
                            <TableRow>
                              <TableHead>{t('sf.history.col.team')}</TableHead><TableHead className="text-right">{t('sf.analytics.col.storms')}</TableHead>
                              <TableHead className="text-right">{t('sf.analytics.col.open')}</TableHead><TableHead className="text-right">{t('sf.analytics.col.sealed')}</TableHead>
                              <TableHead className="text-right">{t('sf.analytics.col.floor')}</TableHead><TableHead className="text-right">{t('sf.analytics.col.avgDur')}</TableHead>
                              <TableHead className="text-right hidden lg:table-cell">{t('sf.analytics.col.avgMembers')}</TableHead><TableHead className="text-right hidden lg:table-cell">{t('sf.analytics.col.peak')}</TableHead>
                              <TableHead className="hidden md:table-cell">{t('sf.analytics.col.last')}</TableHead>
                            </TableRow>
                          </TableHeader>
                          <TableBody>
                            {an.teams.map((x) => (
                              <TableRow key={String(x.team_id)}>
                                <TableCell>{x.team_id != null ? <TeamBadge teamId={x.team_id} teamName={x.team_name} size={12} static /> : <Badge variant="outline">{t('sf.legacy')}</Badge>}</TableCell>
                                <TableCell className="text-right tabular-nums font-semibold">{x.storms}</TableCell>
                                <TableCell className="text-right tabular-nums">{x.open}</TableCell>
                                <TableCell className="text-right tabular-nums">{x.sealed}</TableCell>
                                <TableCell className="text-right tabular-nums">{x.floor}</TableCell>
                                <TableCell className="text-right tabular-nums">{x.avg_duration_ms != null ? formatDuration(x.avg_duration_ms, t) : '—'}</TableCell>
                                <TableCell className="text-right tabular-nums hidden lg:table-cell">{x.avg_members ?? '—'}</TableCell>
                                <TableCell className="text-right tabular-nums hidden lg:table-cell">{x.max_peak_targets ?? '—'}</TableCell>
                                <TableCell className="hidden whitespace-nowrap text-xs md:table-cell">{x.last_storm_at ? formatIncidentTime(x.last_storm_at, locale) : '—'}</TableCell>
                              </TableRow>
                            ))}
                          </TableBody>
                        </Table>
                      </div>
                    )}
                  </Card>
                  {(an.recent || []).length > 0 && (
                    <Card className="min-w-0 gap-2 px-3 py-3 sm:px-4 xl:col-span-3">
                      <h3 className="m-0 text-sm font-semibold">{t('sf.analytics.recent')}</h3>
                      <HistoryList items={an.recent} onDetail={setStormId} />
                    </Card>
                  )}
                </div>
              )}
            </TabsContent>
          </Tabs>
        </>
      )}

      <StormDetailModal stormId={stormId} onClose={() => setStormId(null)} />
    </div>
  )
}
