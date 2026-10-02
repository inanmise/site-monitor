import { useId, useMemo } from 'react'
import { Info } from 'lucide-react'
import { Card } from '@/components/shadcn/card'
import { Badge } from '@/components/shadcn/badge'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import {
  ChartContainer, ChartTooltip, ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid, ReferenceLine, ReferenceArea,
} from '@/components/shadcn/chart'
import HintPopover from '../ui/HintPopover.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { dateLocale } from '../../i18n/dateLocale.js'
import { TooltipCard } from './ChartTooltipCard.jsx'
import { SeriesKey } from './SeriesKey.jsx'
import { axisWidth, formatLoss, formatTick, maxTicksFor, niceTimeTicks } from './responseChartModel.js'
// Kabın genişliği (ResizeObserver, clientWidth) — x etiket yoğunluğu çizim alanına göre seçilir. Tek kaynak (öneri 29).
import { useElementWidth } from '../../hooks/useElementWidth.js'

/** Etiket çipte cümle başı: çağıranın etiketi küçük harfle gelebilir (`pspd.budgetLine` "bütçe (eşik)"). */
const capitalise = (s) => (s ? s.charAt(0).toLocaleUpperCase(dateLocale()) + s.slice(1) : s)

/** Eşik çizgisi yalnız verinin 3 katına kadar çizilir; daha yukarıdaysa ekseni uzatıp veriyi tabana ezmek yerine etikette söylenir. */
const THRESHOLD_VIEW_FACTOR = 3
/** Bu kadar ya da daha az nokta varsa ortalama çizgisinde her nokta işaretlenir (seyrek veri okunur kalsın). */
const SPARSE_POINTS = 60

/**
 * Grafik paneli — shadcn Chart (ChartContainer + recharts). Tek eksen kuralı: ping paket kaybı ve sertifika kalan
 * gün ANA grafiğe ikinci y ekseniyle bindirilmez (iki ölçeğin hizası rastgele, sahte ilişki çizer); altta aynı
 * zaman eksenini paylaşan küçük ikinci grafik (small multiple, `syncId` ile ortak imleç) olarak çizilir.
 *
 * Katmanlar: başarısız kova koşuları (soluk kırmızı gölge) → min–maks bandı → ortalama (degrade dolgulu alan)
 * → p95 (kesikli) → eşik çizgisi (etiketli) → başarısız kontrol işaretleri (halkalı kırmızı nokta; değer yoksa
 * tabanda). Kontrol yapılmayan dilimde çizgi KOPAR (model boşluk noktası ekler).
 */
export default function ChartPanel({
  t, title, shape, stats, fmt, aux, auxLabel, threshold, thresholdLabel, hidden, onHiddenChange, summary, busy,
}) {
  const uid = useId().replace(/:/g, '')
  const titleId = `rtc-title-${uid}`
  const summaryId = `rtc-sum-${uid}`
  const gradId = `rtc-grad-${uid}`
  const syncId = `rtc-sync-${uid}`
  const [measureRef, width] = useElementWidth()

  const failures = stats.failed > 0
  const series = [
    shape.hasValues && { key: 'avg', label: t('chart.avg'), shape: 'line', color: 'var(--chart-1)' },
    shape.hasValues && !shape.raw && { key: 'p95', label: t('chart.p95'), shape: 'dashed', color: 'var(--chart-3)' },
    shape.hasValues && !shape.raw && { key: 'band', label: t('chart.minmax'), shape: 'area', color: 'var(--chart-1)' },
    failures && { key: 'down', label: t('rtc.kpi.failed'), shape: 'dot', color: 'var(--destructive)' },
    aux && shape.hasAux && { key: 'aux', label: auxLabel, shape: 'line', color: 'var(--chart-5)' },
  ].filter(Boolean)
  const visible = series.map((s) => s.key).filter((k) => !hidden.has(k))
  const on = (k) => series.some((s) => s.key === k) && !hidden.has(k)
  const config = Object.fromEntries([
    ...series.map((s) => [s.key, { label: s.label, color: s.color }]),
    ['threshold', { label: thresholdLabel, color: 'var(--destructive)' }],
  ])

  const dataMax = stats.max ?? 0
  const thrInView = threshold != null && (dataMax <= 0 || threshold <= dataMax * THRESHOLD_VIEW_FACTOR)
  const yTop = Math.max(dataMax, thrInView ? threshold : 0) * 1.1
  const auxLabels = aux === 'loss' ? [formatLoss(100)] : aux === 'days' ? [`${Math.max(0, ...shape.real.map((p) => p.days ?? 0))} ${t('chart.unitDays')}`] : []
  const yWidth = axisWidth([fmt.axis(yTop), fmt.axis(yTop / 2), ...(on('aux') ? auxLabels : [])])
  const plotWidth = Math.max(0, (width || 640) - yWidth - 20)
  const { ticks, step, span } = useMemo(
    () => niceTimeTicks(shape.domain[0], shape.domain[1], maxTicksFor(plotWidth)),
    [shape.domain, plotWidth],
  )
  const tickFmt = (v) => formatTick(v, { step, span })
  const sparse = shape.real.length <= SPARSE_POINTS

  const avgDot = (props) => {
    const { cx, cy, payload, index } = props
    if (cy == null || payload?.avg == null || !(sparse || payload.isolated)) return <g key={`a${index}`} />
    return <circle key={`a${index}`} cx={cx} cy={cy} r={2.75} fill="var(--color-avg)" stroke="var(--card)" strokeWidth={1.5} />
  }
  const downDot = (props) => {
    const { cx, cy, payload, index } = props
    if (cy == null || payload?.downY == null) return <g key={`d${index}`} />
    return <circle key={`d${index}`} cx={cx} cy={cy} r={4} fill="var(--color-down)" stroke="var(--card)" strokeWidth={2} />
  }
  const auxDot = (props) => {
    const { cx, cy, payload, index } = props
    if (cy == null || payload?.[aux] == null || !sparse) return <g key={`x${index}`} />
    return <circle key={`x${index}`} cx={cx} cy={cy} r={2.5} fill="var(--color-aux)" stroke="var(--card)" strokeWidth={1.5} />
  }

  const margin = { top: 14, right: 16, left: 0, bottom: 0 }
  const xAxis = (hide) => (
    <XAxis dataKey="t" type="number" scale="time" domain={shape.domain} ticks={ticks} interval={0} allowDataOverflow
      tickFormatter={tickFmt} tickLine={false} axisLine={false} tickMargin={8} tick={{ fontSize: 11 }} hide={hide} />
  )
  const tooltip = (
    <ChartTooltip isAnimationActive={false} cursor={{ stroke: 'var(--muted-foreground)', strokeOpacity: 0.35, strokeWidth: 1 }}
      content={<TooltipCard t={t} fmt={fmt} bucketMs={shape.bucketMs} title={title} aux={on('aux') ? aux : null}
        threshold={threshold} />} />
  )
  const showMain = shape.hasValues || failures

  return (
    <Card data-slot="chart-panel" aria-busy={busy || undefined} className="gap-3 px-3 py-3 shadow-xs sm:px-4 sm:py-4">
      {/* Başlık + seri anahtarları: tablette (768) anahtarlar başlığı ezmesin diye alt alta; geniş ekranda yan yana. */}
      <div className="flex flex-col gap-2 lg:flex-row lg:items-start lg:justify-between">
        <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5">
          <h4 id={titleId} className="m-0 min-w-0 text-sm font-semibold break-words text-foreground">{title}</h4>
          <HintPopover content={shape.raw ? t('rtc.bucketHintRaw') : t('rtc.bucketHint')}
            triggerClassName="shrink-0 gap-1 rounded-md px-1.5 text-xs font-normal text-muted-foreground hover:text-foreground pointer-coarse:min-h-10">
            <span>{t(`rtc.bucket.${shape.bucket}`)}</span>
            <Info aria-hidden="true" className="size-3" />
          </HintPopover>
          {busy && <Spinner size={14} label={t('rtc.updating')} className="text-primary" />}
        </div>
        <div className="flex flex-wrap items-center gap-1.5 lg:justify-end">
          {series.length > 1 && (
            <ToggleGroup type="multiple" variant="outline" size="sm" spacing={1.5} data-slot="chart-series"
              aria-label={t('chart.seriesLabel')} value={visible} className="flex-wrap"
              onValueChange={(vals) => onHiddenChange(new Set(series.map((s) => s.key).filter((k) => !vals.includes(k))))}>
              {series.map((s) => (
                <ToggleGroupItem key={s.key} value={s.key} data-series={s.key}
                  className="h-8 gap-1.5 rounded-md px-2 text-xs font-medium data-[state=off]:text-muted-foreground data-[state=off]:line-through data-[state=off]:opacity-70 data-[state=on]:bg-muted pointer-coarse:h-10">
                  <SeriesKey shape={s.shape} color={s.color} />{s.label}
                </ToggleGroupItem>
              ))}
            </ToggleGroup>
          )}
          {threshold != null && (
            <Badge variant="outline" data-slot="chart-threshold" data-in-view={thrInView ? 'true' : 'false'}
              className="h-8 gap-1.5 rounded-md px-2 font-medium text-muted-foreground pointer-coarse:h-10">
              <SeriesKey shape="dashed" color="var(--destructive)" />
              {t(thrInView ? 'rtc.threshold' : 'rtc.thresholdAbove', capitalise(thresholdLabel), fmt.value(threshold))}
            </Badge>
          )}
        </div>
      </div>

      <figure aria-labelledby={titleId} aria-describedby={summaryId} className="m-0 flex min-w-0 flex-col gap-2" ref={measureRef}>
        {showMain ? (
          <ChartContainer config={config} data-slot="chart-main" className="aspect-auto h-[220px] w-full sm:h-[300px]">
            <ComposedChart data={shape.points} syncId={syncId} margin={margin}>
              <defs>
                <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="var(--color-avg)" stopOpacity={0.3} />
                  <stop offset="95%" stopColor="var(--color-avg)" stopOpacity={0.02} />
                </linearGradient>
              </defs>
              <CartesianGrid vertical={false} />
              {on('down') && shape.downRuns.map((r) => (
                <ReferenceArea key={`r${r.x1}`} yAxisId="v" x1={r.x1} x2={r.x2} fill="var(--color-down)" fillOpacity={0.09}
                  strokeOpacity={0} ifOverflow="hidden" />
              ))}
              {xAxis(false)}
              <YAxis yAxisId="v" domain={[0, 'auto']} tickCount={4} width={yWidth} tickLine={false} axisLine={false}
                tickFormatter={fmt.axis} tick={{ fontSize: 11 }} />
              {tooltip}
              {series.some((s) => s.key === 'band') && (
                <Area yAxisId="v" dataKey="band" type="monotone" hide={hidden.has('band')} fill="var(--color-band)" fillOpacity={0.14}
                  stroke="none" activeDot={false} isAnimationActive={false} connectNulls={false} />
              )}
              {shape.hasValues && (
                <Area yAxisId="v" dataKey="avg" type="monotone" hide={hidden.has('avg')} stroke="var(--color-avg)" strokeWidth={2}
                  fill={`url(#${gradId})`} dot={avgDot} activeDot={{ r: 4, fill: 'var(--color-avg)', stroke: 'var(--card)', strokeWidth: 2 }}
                  isAnimationActive={false} connectNulls={false} />
              )}
              {series.some((s) => s.key === 'p95') && (
                <Line yAxisId="v" dataKey="p95" type="monotone" hide={hidden.has('p95')} stroke="var(--color-p95)" strokeWidth={1.5}
                  strokeDasharray="4 3" dot={false} activeDot={false} isAnimationActive={false} connectNulls={false} />
              )}
              {threshold != null && thrInView && (
                <ReferenceLine yAxisId="v" y={threshold} stroke="var(--color-threshold)" strokeDasharray="6 4" strokeWidth={1.5}
                  ifOverflow="extendDomain"
                  label={{ value: fmt.value(threshold), position: 'insideTopRight', fill: 'var(--muted-foreground)', fontSize: 11 }} />
              )}
              {failures && (
                <Line yAxisId="v" dataKey="downY" hide={hidden.has('down')} stroke="none" dot={downDot} activeDot={false}
                  legendType="none" isAnimationActive={false} connectNulls={false} />
              )}
            </ComposedChart>
          </ChartContainer>
        ) : (
          <p className="m-0 rounded-md bg-muted/50 px-3 py-2 text-xs text-muted-foreground">{t('rtc.noMetric')}</p>
        )}

        {on('aux') && (
          <div data-slot="chart-aux" className="flex flex-col gap-1 border-t pt-2">
            <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
              <SeriesKey shape="line" color="var(--chart-5)" />{auxLabel}
            </span>
            <ChartContainer config={config} className="aspect-auto h-[96px] w-full sm:h-[112px]">
              <ComposedChart data={shape.points} syncId={syncId} margin={{ ...margin, top: 6 }}>
                <CartesianGrid vertical={false} />
                {xAxis(showMain)}
                <YAxis yAxisId="x" width={yWidth} tickCount={3} tickLine={false} axisLine={false} allowDecimals={false}
                  tick={{ fontSize: 11 }}
                  domain={aux === 'loss' ? [0, 100] : ['auto', 'auto']} ticks={aux === 'loss' ? [0, 50, 100] : undefined}
                  tickFormatter={(v) => (aux === 'loss' ? formatLoss(v) : `${v} ${t('chart.unitDays')}`)} />
                {showMain
                  ? <ChartTooltip isAnimationActive={false} cursor={{ stroke: 'var(--muted-foreground)', strokeOpacity: 0.35 }} content={() => null} />
                  : tooltip}
                <Line yAxisId="x" dataKey={aux} type="monotone" stroke="var(--color-aux)" strokeWidth={1.75} dot={auxDot}
                  activeDot={{ r: 3.5, fill: 'var(--color-aux)', stroke: 'var(--card)', strokeWidth: 2 }}
                  isAnimationActive={false} connectNulls={false} />
              </ComposedChart>
            </ChartContainer>
          </div>
        )}
        <p id={summaryId} data-slot="chart-summary" className="sr-only">{summary}</p>
      </figure>
    </Card>
  )
}
