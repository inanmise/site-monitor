import { useMemo } from 'react'
import { BarChart3, Clock } from 'lucide-react'
import { formatDateSec } from '../../api/client'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Badge } from '@/components/shadcn/badge'
import { Skeleton } from '@/components/shadcn/skeleton'
import {
  ChartContainer, ChartTooltip, ChartTooltipContent, ChartLegend, ChartLegendContent, BarChart, Bar, XAxis, YAxis, CartesianGrid,
} from '@/components/shadcn/chart'
import { chartPoints, seriesTotal } from './activityModel.js'

/**
 * Aktivite zaman çizelgesi (2026-10-11): seçili aralıkta sonuç dağılımı — yığılmış çubuk (başarılı / uyarı / hata /
 * bilinmeyen). ≤ 48 saat saatlik, daha uzun İstanbul günü (sunucu belirler). Sağ üstte son aktivite ve "canlı"
 * göstergesi (1. sayfada 30 sn'de bir sessiz tazeleme). Test kancaları: `data-slot="act-timeline"` (+ `data-bucket`),
 * `act-live`, `act-timeline-empty`.
 */
export default function ActivityTimeline({ series, loading, failed = false, lastActivity, live }) {
  const t = useT()
  const { lang } = useLanguage()
  const points = useMemo(() => chartPoints(series, lang), [series, lang])
  const total = seriesTotal(points)
  const config = {
    success: { label: t('act.st.SUCCESS'), color: 'var(--success)' },
    warning: { label: t('act.st.WARNING'), color: 'var(--warning)' },
    error: { label: t('act.st.ERROR'), color: 'var(--destructive)' },
    unknown: { label: t('act.st.UNKNOWN'), color: 'var(--muted-foreground)' },
  }
  return (
    <Card data-slot="act-timeline" data-bucket={series?.bucket || undefined} className="min-w-0 gap-2 py-3 shadow-xs">
      <CardHeader className="flex min-w-0 flex-col gap-2 px-3 sm:flex-row sm:items-start sm:justify-between sm:px-4">
        <div className="flex min-w-0 flex-col gap-0.5">
          <CardTitle role="heading" aria-level={3} className="flex items-center gap-1.5 text-sm font-semibold">
            <BarChart3 aria-hidden="true" className="size-4 text-muted-foreground" /> {t('act.tl.title')}
          </CardTitle>
          <CardDescription className="text-xs">
            {series?.bucket === 'day' ? t('act.tl.descDay') : t('act.tl.descHour')}
          </CardDescription>
        </div>
        <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground sm:justify-end">
          {live && (
            <Badge variant="outline" data-slot="act-live" className="gap-1.5 border-success/30 bg-success/10 text-success"
              title={t('act.tl.liveHint')}>
              <span aria-hidden="true" className="relative flex size-2">
                <span className="absolute inline-flex size-full animate-ping rounded-full bg-success opacity-60 motion-reduce:animate-none" />
                <span className="relative inline-flex size-2 rounded-full bg-success" />
              </span>
              {t('act.tl.live')}
            </Badge>
          )}
          {lastActivity && (
            <span className="inline-flex items-center gap-1">
              <Clock aria-hidden="true" className="size-3" /> {t('act.lastActivity')}: {formatDateSec(lastActivity)}
            </span>
          )}
        </div>
      </CardHeader>
      <CardContent className="px-1.5 sm:px-3">
        {failed ? (
          <p data-slot="act-timeline-error" role="status" className="m-0 flex h-[72px] items-center justify-center px-3 text-center text-sm text-muted-foreground">
            {t('act.tl.loadError')}
          </p>
        ) : loading && !series ? (
          <Skeleton className="h-[150px] w-full" />
        ) : total === 0 ? (
          <p data-slot="act-timeline-empty" className="m-0 flex h-[72px] items-center justify-center text-sm text-muted-foreground">
            {t('act.tl.empty')}
          </p>
        ) : (
          <ChartContainer config={config} className="aspect-auto h-[170px] w-full"
            role="img" aria-label={t('act.tl.aria', total)}>
            <BarChart data={points} margin={{ top: 6, right: 6, bottom: 0, left: -18 }} barCategoryGap="18%">
              <CartesianGrid strokeDasharray="3 3" vertical={false} />
              <XAxis dataKey="label" tickLine={false} axisLine={false} tick={{ fontSize: 10 }} interval="preserveStartEnd" minTickGap={14} />
              <YAxis allowDecimals={false} width={38} tickLine={false} axisLine={false} tick={{ fontSize: 10 }} />
              <ChartTooltip cursor={{ fillOpacity: 0.08 }}
                content={<ChartTooltipContent labelFormatter={(_, p) => p?.[0]?.payload?.full ?? ''} />} />
              <ChartLegend itemSorter={null} content={<ChartLegendContent className="flex-wrap pt-2 text-xs" />} />
              <Bar dataKey="success" stackId="a" fill="var(--color-success)" />
              <Bar dataKey="warning" stackId="a" fill="var(--color-warning)" />
              <Bar dataKey="error" stackId="a" fill="var(--color-error)" />
              <Bar dataKey="unknown" stackId="a" fill="var(--color-unknown)" radius={[3, 3, 0, 0]} />
            </BarChart>
          </ChartContainer>
        )}
      </CardContent>
    </Card>
  )
}
