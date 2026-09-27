import { useMemo, useState } from 'react'
import { Lightbulb, Users } from 'lucide-react'
import { formatDateOnly } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { byIssuer, byTeam, coverage, teamBucketCerts } from '../forecastModel.js'
import CollapsibleSection from '../../components/ui/CollapsibleSection.jsx'
import StatusBlock from '../../components/ui/StatusBlock.jsx'
import TeamBadge from '../../components/ui/TeamBadge.jsx'
import { ChartContainer, ChartTooltip, ChartTooltipContent, BarChart, Bar, XAxis, YAxis } from '@/components/shadcn/chart'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

const TH = 'h-8 bg-muted/60 px-2 text-muted-foreground'
const NUM = 'min-w-11 text-center'

/** Takım tablosu kova etiketi (hücre paneli başlığı). */
function bucketLabel(bucket, th, t) {
  if (bucket === 'overdue') return t('forecast.cls.overdue')
  if (bucket === 'critical') return `≤${th.critical} ${t('forecast.daysLeft')}`
  if (bucket === 'high') return `≤${th.high} ${t('forecast.daysLeft')}`
  if (bucket === 'warning') return `≤${th.warning} ${t('forecast.daysLeft')}`
  if (bucket === 'later') return `>${th.warning} ${t('forecast.daysLeft')}`
  if (bucket === 'late') return t('forecast.rangeLate')
  return t('forecast.colTotal')
}

/**
 * İçgörüler (2026-09-27): ui/CollapsibleSection içinde iki kart — takıma göre dolacaklar (tıklanır hücreler → o
 * takım+kova listesi gün panelinde; "Süz" düğmesi takım süzgeci) ve yenileme zekâsı (verene göre · paylaşılan
 * sertifikalar · zamanında yenileme oranı + son olaylar). Eski 04/05/06 bölümlerinin toplandığı yer.
 */
export default function ForecastInsights({ certs, th, today, range, renewals, teamFilter, onTeamFilter, onOpenBucket }) {
  const t = useT()
  const [open, setOpen] = useState(true)
  const teams = useMemo(() => byTeam(certs, th, range, today), [certs, th, range, today])
  const issuers = useMemo(() => byIssuer(certs, th, range, today), [certs, th, range, today])
  const shared = useMemo(() => coverage(certs), [certs])
  const onTimeTotal = (renewals.on_time ?? 0) + (renewals.late ?? 0)
  const onTimePct = onTimeTotal > 0 ? Math.round((renewals.on_time * 100) / onTimeTotal) : null

  return (
    <CollapsibleSection open={open} onOpenChange={setOpen} icon={Lightbulb} label={t('forecast.insights')} hint={t('forecast.insightsHint')}
      toggleLabel={t('forecast.insights')} contentClassName="pt-3" data-slot="fc-insights">
      <div className="grid min-w-0 grid-cols-1 items-start gap-3 lg:grid-cols-[3fr_2fr]">
        <Card className="gap-3 py-4">
          <CardHeader className="gap-1 px-4 sm:px-6">
            <CardTitle className="flex items-center gap-2 text-base"><Users aria-hidden="true" className="size-4 text-primary" />{t('forecast.teamTitle', range)}</CardTitle>
          </CardHeader>
          <CardContent className="px-4 sm:px-6">
            {teams.length === 0 ? <StatusBlock tone="neutral" title={t('forecast.noTeamData')} className="py-6 md:py-6" /> : (
              // Kap sorgusu (@container): dar kapta (telefon, kenar çubuklu tablet) ">eşik" ve "gecikti" sütunları gizlenir
              <div className="@container overflow-hidden rounded-md border">
                <Table data-slot="fc-team-table" className="text-[.84em]">
                  <TableHeader>
                    <TableRow className="hover:bg-transparent">
                      <TableHead className={TH}>{t('inv.colTeam')}</TableHead>
                      <TableHead className={cn(TH, NUM)} title={t('forecast.tileHint.overdue')}>{t('forecast.tile.overdue')}</TableHead>
                      <TableHead className={cn(TH, NUM)} title={t('forecast.legCritical')}>≤{th.critical}</TableHead>
                      <TableHead className={cn(TH, NUM)} title={t('forecast.legHigh')}>≤{th.high}</TableHead>
                      <TableHead className={cn(TH, NUM)} title={t('forecast.legWarning')}>≤{th.warning}</TableHead>
                      <TableHead className={cn(TH, NUM, 'hidden @2xl:table-cell')}>&gt;{th.warning}</TableHead>
                      <TableHead className={cn(TH, NUM, 'hidden @2xl:table-cell')} title={t('forecast.tileHint.late')}>{t('forecast.tile.late')}</TableHead>
                      <TableHead className={cn(TH, NUM)}>{t('forecast.colTotal')}</TableHead>
                      <TableHead className={TH}><span className="sr-only">{t('forecast.teamFilter')}</span></TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>{teams.map((r) => {
                    const teamLabel = r.name || t('inv.teamNoTeam')
                    const cell = (bucket, value, cls = '', extra = '') => (
                      <TableCell className={cn('px-2 py-1', NUM, cls, extra)}>
                        {value > 0
                          ? <Button type="button" variant="ghost" size="xs" data-slot="fc-cell-btn" title={t('forecast.cellOpen', teamLabel)}
                              className="h-auto px-2 py-px font-[inherit] text-inherit underline decoration-dotted underline-offset-[3px] hover:text-primary hover:no-underline"
                              onClick={() => onOpenBucket({ title: `${teamLabel} · ${bucketLabel(bucket, th, t)} (${value})`, certs: teamBucketCerts(certs, th, range, r.id, bucket, today) })}>{value}</Button>
                          : <span data-slot="fc-zero" className="text-muted-foreground">0</span>}
                      </TableCell>)
                    return (
                      <TableRow key={r.id} data-state={teamFilter === r.id ? 'selected' : undefined}>
                        <TableCell className="px-2 py-1">{r.name ? <TeamBadge teamId={Number(r.id)} teamName={r.name} /> : <span className="text-amber-700 dark:text-amber-400">{t('inv.teamNoTeam')}</span>}</TableCell>
                        {cell('overdue', r.overdue + r.unreachable, r.overdue + r.unreachable > 0 ? 'font-bold text-destructive' : '')}
                        {cell('critical', r.critical, r.critical > 0 ? 'font-bold text-red-600 dark:text-red-400' : '')}
                        {cell('high', r.high, r.high > 0 ? 'font-bold text-orange-600 dark:text-orange-400' : '')}
                        {cell('warning', r.warning)}
                        {cell('later', r.later, 'text-muted-foreground', 'hidden @2xl:table-cell')}
                        {cell('late', r.late, r.late > 0 ? 'font-bold text-amber-700 dark:text-amber-400' : '', 'hidden @2xl:table-cell')}
                        {cell('total', r.total, 'font-bold')}
                        <TableCell className="px-2 py-1 text-right">
                          {r.id !== 'none' && <Button type="button" variant="secondary" size="xs" className="h-7 pointer-coarse:h-9" onClick={() => onTeamFilter(teamFilter === r.id ? '' : r.id)}>{teamFilter === r.id ? t('inv.filterClear') : t('forecast.teamFilter')}</Button>}
                        </TableCell>
                      </TableRow>)
                  })}</TableBody>
                </Table>
              </div>
            )}
          </CardContent>
        </Card>

        <Card className="gap-3 py-4">
          <CardHeader className="gap-1 px-4 sm:px-6">
            <CardTitle className="text-base">{t('forecast.intelTitle')}</CardTitle>
            <CardDescription>{t('forecast.issuerTitle', range)}</CardDescription>
          </CardHeader>
          <CardContent className="flex min-w-0 flex-col gap-4 px-4 sm:px-6">
            <div className="flex flex-wrap gap-1.5">
              {issuers.length === 0 ? <span className="text-sm text-muted-foreground">—</span>
                : issuers.map((i) => <Badge key={i.issuer} variant="outline" className="max-w-full font-normal"><b className="text-primary tabular-nums">{i.count}</b> <span className="truncate">{i.issuer}</span></Badge>)}
            </div>
            <div className="min-w-0">
              <div className="mb-1 text-xs font-semibold tracking-wide text-muted-foreground uppercase">{t('forecast.coverageTitle')}</div>
              {shared.length === 0 ? <span className="text-sm text-muted-foreground">{t('forecast.coverageNone')}</span> : (
                <ul className="m-0 flex list-none flex-col gap-1 p-0 text-sm">
                  {shared.slice(0, 6).map((s, i) => <li key={i} className="min-w-0 break-words">{t('forecast.coverageRow', s.count)} <span className="text-muted-foreground">{s.domains.join(', ')}</span></li>)}
                </ul>
              )}
            </div>
            <div className="min-w-0" data-slot="fc-ontime">
              <div className="mb-1 flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
                <span className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{t('forecast.onTimeTitle', renewals.window_days ?? 90)}</span>
              </div>
              {onTimeTotal === 0 ? <span className="text-sm text-muted-foreground">{t('forecast.subOnTimeNone')}</span> : (
                <div className="flex min-w-0 flex-col gap-2">
                  <div className="flex items-baseline gap-2">
                    <span className="text-2xl font-extrabold text-teal-600 tabular-nums dark:text-teal-400">{onTimePct}%</span>
                    <span className="text-xs text-muted-foreground">{t('forecast.onTimeRate')} · {t('forecast.subOnTime', renewals.on_time, renewals.late, renewals.window_days ?? 90)}</span>
                  </div>
                  <ChartContainer config={{ on_time: { label: t('forecast.onTime'), color: '#16a34a' }, late: { label: t('forecast.lateRenewal'), color: '#dc2626' } }} className="aspect-auto h-[90px] w-full">
                    <BarChart data={renewals.months || []} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
                      <XAxis dataKey="month" tick={{ fontSize: 10 }} tickLine={false} axisLine={false} />
                      <YAxis allowDecimals={false} tick={{ fontSize: 10 }} width={24} tickLine={false} axisLine={false} />
                      <ChartTooltip content={<ChartTooltipContent />} />
                      <Bar dataKey="on_time" stackId="r" fill="var(--color-on_time)" /><Bar dataKey="late" stackId="r" fill="var(--color-late)" radius={[3, 3, 0, 0]} />
                    </BarChart>
                  </ChartContainer>
                  <ul className="m-0 flex list-none flex-col gap-1 p-0 text-xs">
                    {(renewals.events || []).slice(0, 5).map((e, i) => (
                      <li key={i} className="grid min-w-0 grid-cols-[auto_1fr] items-center gap-x-1.5 gap-y-0.5">
                        <Badge variant="secondary" data-slot="fc-renewal" data-state={e.on_time ? 'on_time' : 'late'}
                          className={e.on_time ? 'bg-success/15 text-success dark:bg-success/20' : 'bg-destructive/10 text-destructive dark:bg-destructive/20'}>
                          {e.on_time ? t('forecast.onTime') : t('forecast.lateRenewal')}
                        </Badge>
                        <span className="min-w-0 truncate font-medium">{e.domain}</span>
                        <span className="col-start-2 text-muted-foreground">{formatDateOnly(e.renewed_at)}{e.renew_by ? ` · ${t('forecast.renewBy')} ${formatDateOnly(e.renew_by)}` : ''}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          </CardContent>
        </Card>
      </div>
    </CollapsibleSection>
  )
}
