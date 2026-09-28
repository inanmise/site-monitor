import { useMemo } from 'react'
import {
  AlertOctagon, CalendarClock, CalendarRange, ChevronRight, Flame, Hourglass, Layers, ShieldCheck, TableProperties, WifiOff,
} from 'lucide-react'
import { formatDateOnly } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { formatPercent } from '../../i18n/dateLocale.js'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { navigateTo } from '../../utils/navigate.js'
import MonitorStatsBar from '../MonitorStatsBar.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/shadcn/card'
import { ChartContainer, ChartTooltip, ChartTooltipContent, Cell, Pie, PieChart } from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'
import { BUCKETS, UPCOMING_N, distribution, kpiCounts, upcoming } from './statsModel.js'
import { BUCKET_COLOR_VAR, BUCKET_VARS, DaysBadge, SWATCH, TierBadge } from './statsUi.jsx'

/**
 * Genel bakış (2026-09-28): KPI kutucukları (standart MonitorStatsBar — her kutucuk tablonun süzgeci; Toplam süzgeci
 * kaldırır) + kalan süre dağılımı (shadcn Chart halka grafiği + sayılı gösterge — göstergenin kendisi grafiğin tablo
 * görünümü) + yaklaşan bitişler (en yakın N; satır sertifika penceresini açar).
 *
 * Telefonda sıfır sayılı kutucuklar gizlenir (Toplam, Sağlıklı ve etkin olan kalır) — 2 sütunda 7 kutucuk listeyi
 * ekranın altına itiyordu (Uyarılar sayfasıyla aynı karar). Test kancaları: `data-slot="stats-overview"`,
 * `stats-dist`, `stats-dist-row` (+ `data-bucket`), `stats-upcoming`, `stats-upcoming-item` (+ `data-domain`).
 */
const KPI_META = {
  valid: { Icon: ShieldCheck, cls: 'valid' },
  d30: { Icon: CalendarClock, cls: 'warning' },
  d14: { Icon: Hourglass, cls: 'high' },
  d7: { Icon: Flame, cls: 'critical' },
  expired: { Icon: AlertOctagon, cls: 'expired' },
  error: { Icon: WifiOff, cls: 'error' },
}

export default function StatsOverview({ certs, kpi, onKpi, onClearKpi, onOpen, onShowUpcoming }) {
  const t = useT()
  const isMobile = useIsMobile()
  const counts = useMemo(() => kpiCounts(certs), [certs])
  const dist = useMemo(() => distribution(certs), [certs])
  const next = useMemo(() => upcoming(certs, UPCOMING_N), [certs])

  const pct = (n) => (counts.total > 0 ? formatPercent(Math.round((n * 1000) / counts.total) / 10) : null)
  const tiles = [
    {
      key: 'total', Icon: Layers, cls: 'total', value: counts.total, label: t('stv.kpi.total'), hint: t('stv.kpiHint.total'),
      sub: counts.avgDays != null ? t('stv.avgDays', counts.avgDays) : undefined, tip: t('stv.kpiAllTip'), onClick: onClearKpi,
    },
    ...Object.entries(KPI_META)
      .filter(([k]) => !isMobile || k === 'valid' || counts[k] > 0 || kpi === k)
      .map(([k, m]) => ({
        key: k, Icon: m.Icon, cls: m.cls, value: counts[k], label: t(`stv.kpi.${k}`), hint: t(`stv.kpiHint.${k}`), sub: pct(counts[k]) ?? undefined,
      })),
  ]

  return (
    <section data-slot="stats-overview" aria-label={t('stv.overview')} className="flex min-w-0 flex-col gap-4">
      <div className="[&>[data-slot=stats-panel]]:mb-0">
        <MonitorStatsBar items={tiles} activeFilter={kpi} onStatClick={onKpi} />
      </div>
      <div className="grid min-w-0 grid-cols-1 gap-4 lg:grid-cols-2">
        <DistributionCard dist={dist} />
        <UpcomingCard items={next} onOpen={onOpen} onShowUpcoming={onShowUpcoming} />
      </div>
    </section>
  )
}

function DistributionCard({ dist }) {
  const t = useT()
  const config = useMemo(() => Object.fromEntries(BUCKETS.map((k) => [k, { label: t(`stv.bucket.${k}`), color: BUCKET_COLOR_VAR[k] }])), [t])
  const data = dist.rows.filter((r) => r.count > 0)
  return (
    <Card data-slot="stats-dist" className={cn('min-w-0 gap-4 py-4 shadow-xs', BUCKET_VARS)}>
      <CardHeader className="gap-1 px-4 sm:px-6">
        <CardTitle role="heading" aria-level={3} className="flex items-center gap-2 text-base"><CalendarRange aria-hidden="true" className="size-4 text-primary" />{t('stv.distTitle')}</CardTitle>
        <CardDescription>{t('stv.distDesc', dist.known)}</CardDescription>
      </CardHeader>
      <CardContent className="px-4 sm:px-6">
        {dist.known === 0 ? (
          <StatusBlock tone="neutral" title={t('stv.distEmpty')} className="py-6 md:py-6" />
        ) : (
          <div className="flex min-w-0 flex-col items-center gap-4 sm:flex-row sm:items-center sm:gap-6">
            {/* Grafik görsel özet; aynı bilgi yanındaki gösterge listesinde metin olarak (ekran okuyucu listeyi okur) */}
            <div aria-hidden="true" className="relative size-44 shrink-0">
              <ChartContainer config={config} className="aspect-square size-44">
                <PieChart>
                  <ChartTooltip cursor={false} content={<ChartTooltipContent nameKey="key" hideLabel />} />
                  <Pie data={data} dataKey="count" nameKey="key" innerRadius={52} outerRadius={80} paddingAngle={data.length > 1 ? 2 : 0}
                    stroke="var(--card)" strokeWidth={2} isAnimationActive={false}>
                    {data.map((r) => <Cell key={r.key} fill={`var(--color-${r.key})`} />)}
                  </Pie>
                </PieChart>
              </ChartContainer>
              <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                <span className="text-2xl leading-none font-extrabold tabular-nums">{dist.known}</span>
                <span className="mt-1 text-xs text-muted-foreground">{t('stv.distCenter')}</span>
              </div>
            </div>
            <ul className="m-0 flex w-full min-w-0 list-none flex-col gap-1 p-0">
              {dist.rows.map((r) => (
                <li key={r.key} data-slot="stats-dist-row" data-bucket={r.key}
                  className={cn('grid grid-cols-[auto_minmax(0,1fr)_auto_3.5rem] items-center gap-x-2.5 rounded-md px-2 py-1.5 text-sm', r.count === 0 && 'text-muted-foreground')}>
                  <span aria-hidden="true" className={cn('size-3 rounded-sm', SWATCH[r.key])} />
                  <span className="min-w-0 truncate">{t(`stv.bucket.${r.key}`)}</span>
                  <span className="font-semibold tabular-nums">{r.count}</span>
                  <span className="text-right text-xs text-muted-foreground tabular-nums">{formatPercent(r.pct)}</span>
                </li>
              ))}
              {(dist.error > 0 || dist.unknown > 0) && (
                <li data-slot="stats-dist-unknown" className="mt-1 flex items-start gap-2 border-t px-2 pt-2 text-xs text-muted-foreground">
                  <WifiOff aria-hidden="true" className="mt-px size-3.5 shrink-0 text-destructive" />
                  <span>{t('stv.distUnknown', dist.error, dist.unknown)}</span>
                </li>
              )}
            </ul>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function UpcomingCard({ items, onOpen, onShowUpcoming }) {
  const t = useT()
  return (
    <Card data-slot="stats-upcoming" className="min-w-0 gap-3 py-4 shadow-xs">
      <CardHeader className="gap-1 px-4 sm:px-6">
        <CardTitle role="heading" aria-level={3} className="flex items-center gap-2 text-base"><CalendarClock aria-hidden="true" className="size-4 text-primary" />{t('stv.upTitle')}</CardTitle>
        <CardDescription>{t('stv.upDesc', UPCOMING_N)}</CardDescription>
      </CardHeader>
      <CardContent className="min-w-0 flex-1 px-2 sm:px-4">
        {items.length === 0 ? (
          <StatusBlock tone="success" icon={ShieldCheck} title={t('stv.upEmpty')} className="py-6 md:py-6" />
        ) : (
          <ul className="m-0 flex min-w-0 list-none flex-col p-0">
            {items.map((c) => (
              <li key={c.domain} className="min-w-0 border-b last:border-b-0">
                <Button type="button" variant="ghost" data-slot="stats-upcoming-item" data-domain={c.domain}
                  aria-label={t('card.openDetailFor', c.domain)} onClick={() => onOpen(c.domain)}
                  className="h-auto min-h-11 w-full justify-start gap-3 rounded-md px-2 py-2 text-left font-normal whitespace-normal">
                  <DaysBadge cert={c} unit className="min-w-14" />
                  <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                    <span className="min-w-0 truncate font-semibold" title={c.domain}>{c.domain}</span>
                    <span className="flex min-w-0 flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                      {c.tier != null && <TierBadge tier={c.tier} className="text-[10px]" />}
                      <span className="tabular-nums">{t('stv.expiresOn', formatDateOnly(c.not_after))}</span>
                    </span>
                  </span>
                  <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
                </Button>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
      <CardFooter className="flex-wrap gap-2 px-4 sm:px-6">
        <Button type="button" variant="outline" size="sm" className="max-sm:h-10 pointer-coarse:h-10" onClick={onShowUpcoming} data-slot="stats-upcoming-table">
          <TableProperties aria-hidden="true" />{t('stv.upInTable')}
        </Button>
        <Button type="button" variant="ghost" size="sm" className="max-sm:h-10 pointer-coarse:h-10" onClick={() => navigateTo('forecast')}>
          {t('nav.forecast')}<ChevronRight aria-hidden="true" />
        </Button>
      </CardFooter>
    </Card>
  )
}
