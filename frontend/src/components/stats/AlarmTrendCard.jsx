import { useMemo, useState } from 'react'
import { ArrowUpRight, Siren } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { navigateTo } from '../../utils/navigate.js'
import RecentChangesLine from '../RecentChangesLine.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import SourceGate from './SourceGate.jsx'
import { alarmTrend, formatMinutes, statsNum, statsPct } from './statsInsights.js'
import { Button } from '@/components/shadcn/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Bar, BarChart, ChartContainer, ChartTooltip, ChartTooltipContent, XAxis, YAxis } from '@/components/shadcn/chart'

/**
 * Alarm eğilimi (2026-11-13, İstatistikler #9): son 30 günde günlük açılan alarm (tüm türler; Alarm Geçmişi gürültü
 * analizinin serisi), ortalama çözülme süresi (MTTR), çözülme oranı ve kritik sayısı + "son 7 / 30 günde ne değişti" şeridi
 * (Pano'daki RecentChangesLine). Yalnız `alerts.read` iznine çizilir (kaynak `hidden` → bölüm yok). Grafik görsel özet;
 * aynı sayılar yanındaki metinde. Test kancası: `data-slot="stats-alarm-trend"`.
 */
export default function AlarmTrendCard({ source, onRetry }) {
  const t = useT()
  const [days, setDays] = useState(7)
  if (!source || source.status === 'hidden') return null
  return (
    <Card data-slot="stats-alarm-trend" className="min-w-0 gap-3 py-4 shadow-xs">
      <CardHeader className="gap-1 px-4 sm:px-6">
        <CardTitle role="heading" aria-level={3} className="flex items-center gap-2 text-base"><Siren aria-hidden="true" className="size-4 text-primary" />{t('stx.trendTitle')}</CardTitle>
        <CardDescription>{t('stx.trendDesc')}</CardDescription>
        <CardAction>
          <Button type="button" variant="ghost" size="sm" className="pointer-coarse:h-10 max-sm:h-10" onClick={() => navigateTo('alerthistory')}>
            {t('stx.trendOpen')}<ArrowUpRight aria-hidden="true" />
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-3 px-4 sm:px-6">
        <SourceGate source={source} onRetry={onRetry} skeletonClassName="h-40 rounded-lg">
          {(noise) => <TrendBody noise={noise} />}
        </SourceGate>
        <div data-slot="stats-changes" className="flex min-w-0 flex-col gap-2 border-t pt-3 sm:flex-row sm:items-center">
          <SegmentedControl value={String(days)} onChange={(v) => setDays(Number(v))} ariaLabel={t('stx.changesRange')}
            className="[&>button]:max-sm:h-10 [&>button]:pointer-coarse:h-10"
            options={[{ value: '7', label: t('stx.days7') }, { value: '30', label: t('stx.days30') }]} />
          <div className="min-w-0 text-sm [&_.recent-changes]:m-0"><RecentChangesLine key={days} days={days} /></div>
        </div>
      </CardContent>
    </Card>
  )
}

function TrendBody({ noise }) {
  const t = useT()
  const tr = useMemo(() => alarmTrend(noise), [noise])
  const config = useMemo(() => ({ count: { label: t('stx.trendSeries'), color: 'var(--chart-1, var(--primary))' } }), [t])
  if (!tr) return null
  const data = tr.series.map((p) => ({ ...p, label: p.date.slice(5) }))
  return (
    <div className="flex min-w-0 flex-col gap-3">
      <dl className="m-0 grid grid-cols-2 gap-2 sm:grid-cols-4">
        <Stat label={t('stx.trendTotal')} value={statsNum(tr.total)} slot="total" />
        <Stat label={t('stx.trendCritical')} value={statsNum(tr.critical)} slot="critical" />
        <Stat label={t('stx.trendMttr')} value={formatMinutes(tr.mttr, t)} slot="mttr" />
        <Stat label={t('stx.trendResolved')} value={statsPct(tr.resolvedPct)} slot="resolved" />
      </dl>
      {tr.total === 0 ? (
        <p data-slot="stats-trend-none" className="m-0 text-sm text-muted-foreground">{t('stx.trendNone')}</p>
      ) : (
        <div aria-hidden="true" className="min-w-0">
          <ChartContainer config={config} className="aspect-auto h-36 w-full">
            <BarChart data={data} margin={{ top: 4, right: 4, bottom: 0, left: -24 }}>
              <XAxis dataKey="label" tickLine={false} axisLine={false} interval="preserveStartEnd" minTickGap={16} fontSize={11} />
              <YAxis allowDecimals={false} tickLine={false} axisLine={false} width={40} fontSize={11} />
              <ChartTooltip cursor={false} content={<ChartTooltipContent labelKey="date" />} />
              <Bar dataKey="count" fill="var(--color-count)" radius={[3, 3, 0, 0]} isAnimationActive={false} />
            </BarChart>
          </ChartContainer>
        </div>
      )}
      <p className="sr-only">{t('stx.trendSr', statsNum(tr.total), statsNum(tr.peak))}</p>
    </div>
  )
}

function Stat({ label, value, slot }) {
  return (
    <div data-slot="stats-trend-stat" data-stat={slot} className="flex min-w-0 flex-col rounded-md bg-muted/40 px-3 py-2">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="m-0 text-lg font-bold tabular-nums">{value}</dd>
    </div>
  )
}
