import { useMemo } from 'react'
import { BarChart3 } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { ChartContainer, ChartTooltip, ChartTooltipContent, ChartLegend, ChartLegendContent, BarChart, Bar, XAxis, YAxis, CartesianGrid } from '@/components/shadcn/chart'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import StatusBlock from '../../components/ui/StatusBlock.jsx'
import SegmentedControl from '../../components/ui/SegmentedControl.jsx'
import { horizonBuckets } from './forecastUi.jsx'

const RANGES = [90, 180, 365]
const SERIES = ['critical', 'high', 'warning', 'later']

/**
 * Vade ufku (2026-09-27): önümüzdeki 90 / 180 / 365 günün hafta (ya da ay) kovaları, aciliyete göre yığılı shadcn
 * Chart (ChartContainer + recharts). Bir çubuğa (ya da altındaki "en yoğun dönem" düğmelerine — klavye/dokunmatik
 * ve jsdom yolu) basmak listeyi o döneme daraltır (`onBucket`). Hiç bitiş yoksa grafik yerine küçük boş durum.
 */
export default function ForecastHorizon({ certs, th, today, locale, range, onRange, onBucket }) {
  const t = useT()
  const data = useMemo(() => horizonBuckets(certs, th, range, today, locale, t), [certs, th, range, today, locale, t])
  const total = data.reduce((n, b) => n + b.total, 0)
  const peaks = useMemo(() => data.filter((b) => b.total > 0).sort((a, b) => b.total - a.total || a.from.localeCompare(b.from)).slice(0, 3), [data])
  const config = useMemo(() => ({
    critical: { label: t('forecast.legCritical'), color: '#dc2626' },
    high: { label: t('forecast.legHigh'), color: '#ea580c' },
    warning: { label: t('forecast.legWarning'), color: '#f59e0b' },
    later: { label: t('forecast.legLater'), color: '#3b82f6' },
  }), [t])
  const pick = (b) => { if (b && b.total > 0) onBucket({ from: b.from, to: b.to, label: b.title || b.label }) }

  return (
    <Card data-slot="fc-horizon" className="gap-3 py-4">
      {/* Başlık + aralık seçici esnek satırda: dar kapta seçici alta sarar (ızgara CardAction dar ekranda başlığı ezerdi) */}
      <CardHeader className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 px-4 sm:px-6">
        <div className="flex min-w-0 flex-[1_1_18rem] flex-col gap-1">
          <CardTitle className="flex items-center gap-2 text-base"><BarChart3 aria-hidden="true" className="size-4 text-primary" />{t('forecast.horizonTitle')}</CardTitle>
          <CardDescription>{total > 0 ? t('forecast.horizonDesc', range, total) : t('forecast.horizonNone', range)}</CardDescription>
        </div>
        <SegmentedControl value={range} onChange={onRange} ariaLabel={t('forecast.horizonRange')}
          options={RANGES.map((d) => ({ value: d, label: t('forecast.chartDays', d) }))} />
      </CardHeader>
      {total > 0 ? (
        <CardContent className="flex min-w-0 flex-col gap-2 px-2 sm:px-6">
          <ChartContainer config={config} className="aspect-auto h-[170px] w-full sm:h-[210px]">
            <BarChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: 0 }} barCategoryGap="22%">
              <CartesianGrid vertical={false} strokeDasharray="3 3" />
              <XAxis dataKey="label" tickLine={false} axisLine={false} tick={{ fontSize: 11 }} interval="preserveStartEnd" minTickGap={14} />
              <YAxis allowDecimals={false} width={28} tickLine={false} axisLine={false} tick={{ fontSize: 11 }} />
              <ChartTooltip cursor={{ fill: 'var(--muted)' }} content={<ChartTooltipContent labelFormatter={(label, payload) => payload?.[0]?.payload?.title ?? label} />} />
              {/* Açıklama aciliyet sırasıyla (recharts varsayılanı ada göre alfabetik: critical, high, later, warning) */}
              <ChartLegend itemSorter={(item) => SERIES.indexOf(item?.dataKey)} content={<ChartLegendContent className="flex-wrap" />} />
              {SERIES.map((k, i) => (
                <Bar key={k} dataKey={k} stackId="h" fill={`var(--color-${k})`} radius={i === SERIES.length - 1 ? [3, 3, 0, 0] : 0}
                  cursor="pointer" onClick={(d) => pick(d?.payload ?? d)} />
              ))}
            </BarChart>
          </ChartContainer>
          {peaks.length > 0 && (
            <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
              <span>{t('forecast.horizonPeaks')}:</span>
              {peaks.map((b) => (
                <Button key={b.key} type="button" variant="outline" size="xs" data-slot="fc-horizon-peak" className="h-7 rounded-full font-normal pointer-coarse:h-9"
                  aria-label={t('forecast.horizonPeakBtn', b.title || b.label, b.total)} title={t('forecast.horizonPeakBtn', b.title || b.label, b.total)}
                  onClick={() => pick(b)}>
                  {b.title || b.label} <span className="font-semibold tabular-nums">{b.total}</span>
                </Button>
              ))}
            </div>
          )}
        </CardContent>
      ) : (
        <CardContent className="px-4 sm:px-6">
          <StatusBlock tone="success" title={t('forecast.horizonNone', range)} className="py-4 md:py-4" />
        </CardContent>
      )}
    </Card>
  )
}
