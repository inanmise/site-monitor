import { useId, useMemo, useState } from 'react'
import { ChartSpline } from 'lucide-react'
import { formatDate } from '../../../api/client'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import ToneBadge from '../../admin/ToneBadge.jsx'
import { SeriesKey } from '../../responsechart/SeriesKey.jsx'
import { formatTick, maxTicksFor, niceTimeTicks } from '../../responsechart/responseChartModel.js'
import {
  GRADE_TICKS, formatMs, formatSize, gradeTone, niceByteTicks, niceMsTicks, plural, trendDomain, trendPoints, TREND_REFERENCE_MS,
  TREND_SPARSE_POINTS,
} from './resourceScanModel.js'
import { Section } from './ResourceScanParts.jsx'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { ChartContainer, ChartTooltip, ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid, ReferenceLine } from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'

/** Grafiğin iki kenarındaki pay (px) — en yeni (çoğu zaman seçili) nokta kenarda yarım kesilmesin (r177 H7). */
const X_PAD = { left: 14, right: 18 }
const SERIES = ['load', 'size', 'grade']

/**
 * Yüklenme süresi geçmişi (2026-10-31; r177) — Grafana tarzı alan grafiği (shadcn Chart + recharts): degrade dolgulu
 * tahmini yüklenme süresi, 3 sn (iyi sınırı) ve 5 sn (yavaş) kesikli başvuru çizgileri, YUVARLAK eksen adımları (r177 H8),
 * zaman ekseni iki kenarda paylı (H7). Başarısız / yarıda kalan analiz bir BOŞLUKTUR (çizgi kopar) ve altta "×" ile
 * işaretlenir. Bir noktaya basınca O ANALİZ açılır (`onSelect`). Seriler: yüklenme, sayfa boyutu, not (her biri ayrı küçük
 * grafik — tek eksen kuralı; aynı zaman ekseni). Seri düğmeleri başlığın ALTINDA (tablette sığmıyordu, H12).
 */
export default function ResourceScanTrend({ items, selectedId, onSelect }) {
  const t = useT()
  const locale = useDateLocale()
  const uid = useId().replace(/:/g, '')
  const gradId = `prs-grad-${uid}`
  const sizeGradId = `prs-sgrad-${uid}`
  const syncId = `prs-sync-${uid}`
  const titleId = `prs-trend-title-${uid}`
  const summaryId = `prs-trend-sum-${uid}`
  const [measureRef, width] = useElementWidth()
  const [visible, setVisible] = useState(['load'])

  const points = useMemo(() => trendPoints(items), [items])
  const values = points.filter((p) => p.load !== null)
  const gaps = points.filter((p) => p.gap)
  const domain = useMemo(() => trendDomain(points), [points])
  const sparse = values.length <= TREND_SPARSE_POINTS
  const plotWidth = Math.max(0, (width || 640) - 70 - X_PAD.left - X_PAD.right)
  const ticks = useMemo(() => (domain ? niceTimeTicks(domain[0], domain[1], maxTicksFor(plotWidth)) : null), [domain, plotWidth])
  const tickFmt = (v) => (ticks ? formatTick(v, { step: ticks.step, span: ticks.span }, locale) : '')
  const shown = SERIES.filter((k) => visible.includes(k))

  const config = {
    load: { label: t('prs.trend.load'), color: 'var(--chart-1)' },
    size: { label: t('prs.trend.size'), color: 'var(--chart-5)' },
    grade: { label: t('prs.trend.grade'), color: 'var(--chart-3)' },
  }
  const loadMax = Math.max(0, ...values.map((p) => p.load), TREND_REFERENCE_MS.at(-1))
  const loadAxis = useMemo(() => niceMsTicks(loadMax * 1.05, 4), [loadMax])
  const sizeAxis = useMemo(() => niceByteTicks(Math.max(0, ...values.map((p) => p.size ?? 0)) * 1.05, 3), [values])

  const latest = values.at(-1)
  const best = values.reduce((m, p) => (m == null || p.load < m ? p.load : m), null)
  const worst = values.reduce((m, p) => (m == null || p.load > m ? p.load : m), null)
  const summary = values.length
    ? plural(t, 'prs.trend.summary', values.length, values.length, formatMs(latest?.load, locale), formatMs(best, locale), formatMs(worst, locale))
    : t('prs.trend.empty')

  const pick = (state) => {
    const id = state?.activePayload?.find((x) => x?.payload)?.payload?.id
    if (id != null && onSelect) onSelect(id)
  }
  const dot = (key) => function TrendDot(props) {
    const { cx, cy, payload, index } = props
    if (cy == null || payload?.[key] == null) return <g key={`${key}${index}`} />
    const sel = payload.id != null && payload.id === selectedId
    if (!(sparse || payload.isolated || sel)) return <g key={`${key}${index}`} />
    return (
      <circle key={`${key}${index}`} cx={cx} cy={cy} r={sel ? 5 : 3} fill={`var(--color-${key})`} stroke="var(--card)"
        strokeWidth={sel ? 2.5 : 1.5} data-slot={payload.isolated ? 'prs-trend-dot-lone' : sel ? 'prs-trend-dot-selected' : undefined}
        style={onSelect ? { cursor: 'pointer' } : undefined} />
    )
  }
  const margin = { top: 12, right: 8, left: 0, bottom: 0 }
  const xAxis = (hide) => (
    <XAxis dataKey="t" type="number" scale="time" domain={domain || ['auto', 'auto']} ticks={ticks?.ticks} interval={0} padding={X_PAD}
      allowDataOverflow tickFormatter={tickFmt} tickLine={false} axisLine={false} tickMargin={8} tick={{ fontSize: 11 }} hide={hide} />
  )
  const tooltip = (
    <ChartTooltip isAnimationActive={false} cursor={{ stroke: 'var(--muted-foreground)', strokeOpacity: 0.35, strokeWidth: 1 }}
      content={<TrendTooltip t={t} locale={locale} clickable={Boolean(onSelect)} />} />
  )
  // Başarısız / yarıda kalan analiz: grafiğin TABANINDA "×" (çizgi kopuyor; nedenini göstermek için)
  const gapMark = function GapMark(props) {
    const { cx, cy, payload, index } = props
    if (cx == null || cy == null || !payload?.gap) return <g key={`gap${index}`} />
    return (
      <g key={`gap${index}`} data-slot="prs-trend-gap" aria-hidden="true">
        <text x={cx} y={cy} dy={4} textAnchor="middle" fontSize={13} fontWeight={700} fill="var(--destructive)">×</text>
      </g>
    )
  }
  const gapLine = (dataKey, axisTop) => gaps.length > 0 && (
    <Line dataKey={(p) => (p.gap ? axisTop * 0.04 : null)} stroke="none" isAnimationActive={false} legendType="none"
      dot={gapMark} activeDot={false} name={`${dataKey}-gap`} connectNulls={false} />
  )

  const head = (k, label) => (
    <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
      <SeriesKey shape="line" color={config[k].color} />{label}
    </span>
  )

  return (
    <Section slot="prs-trend" icon={ChartSpline} title={<span id={titleId}>{t('prs.trend.title')}</span>} description={t('prs.trend.desc')}>
      {values.length > 0 && (
        <ToggleGroup type="multiple" variant="outline" size="sm" spacing={1.5} data-slot="prs-trend-series" className="flex-wrap justify-start"
          aria-label={t('prs.trend.series')} value={visible} onValueChange={(v) => setVisible(v)}>
          {SERIES.map((k) => (
            <ToggleGroupItem key={k} value={k} data-series={k}
              className={cn('h-8 gap-1.5 rounded-md px-2 text-xs font-medium data-[state=off]:text-muted-foreground data-[state=off]:opacity-75 data-[state=on]:bg-muted', 'pointer-coarse:h-10')}>
              <SeriesKey shape="line" color={config[k].color} />{config[k].label}
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
      )}
      <figure aria-labelledby={titleId} aria-describedby={summaryId} ref={measureRef} className="m-0 flex min-w-0 flex-col gap-2">
        {values.length === 0 ? (
          <p data-slot="prs-trend-empty" className="m-0 rounded-md bg-muted/50 px-3 py-2 text-xs text-muted-foreground">{t('prs.trend.empty')}</p>
        ) : (
          <>
            {shown.includes('load') && (
              <ChartContainer config={config} data-slot="prs-trend-load" className="aspect-auto h-[200px] w-full sm:h-[240px]">
                <ComposedChart data={points} syncId={syncId} margin={margin} onClick={pick}>
                  <defs>
                    <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="var(--color-load)" stopOpacity={0.32} />
                      <stop offset="95%" stopColor="var(--color-load)" stopOpacity={0.03} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid vertical={false} />
                  {xAxis(false)}
                  <YAxis domain={[0, loadAxis.top]} ticks={loadAxis.ticks} interval={0} width={58} tickLine={false} axisLine={false}
                    tickFormatter={(v) => formatMs(v, locale)} tick={{ fontSize: 11 }} />
                  {tooltip}
                  {TREND_REFERENCE_MS.map((ms, i) => (
                    <ReferenceLine key={ms} y={ms} stroke={i === 0 ? 'var(--success)' : 'var(--destructive)'} strokeDasharray="6 4"
                      strokeWidth={1.25} ifOverflow="extendDomain"
                      label={{ value: formatMs(ms, locale), position: 'insideTopRight', fill: 'var(--muted-foreground)', fontSize: 11 }} />
                  ))}
                  <Area dataKey="load" type="monotone" stroke="var(--color-load)" strokeWidth={2} fill={`url(#${gradId})`}
                    dot={dot('load')} activeDot={{ r: 4, fill: 'var(--color-load)', stroke: 'var(--card)', strokeWidth: 2 }}
                    isAnimationActive={false} connectNulls={false} />
                  {gapLine('load', loadAxis.top)}
                </ComposedChart>
              </ChartContainer>
            )}
            {shown.includes('size') && (
              <div data-slot="prs-trend-size" className={cn('flex flex-col gap-1', shown[0] !== 'size' && 'border-t pt-2')}>
                {head('size', t('prs.trend.size'))}
                <ChartContainer config={config} className="aspect-auto h-[110px] w-full sm:h-[130px]">
                  <ComposedChart data={points} syncId={syncId} margin={{ ...margin, top: 6 }} onClick={pick}>
                    <defs>
                      <linearGradient id={sizeGradId} x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stopColor="var(--color-size)" stopOpacity={0.25} />
                        <stop offset="95%" stopColor="var(--color-size)" stopOpacity={0.02} />
                      </linearGradient>
                    </defs>
                    <CartesianGrid vertical={false} />
                    {xAxis(shown.at(-1) !== 'size')}
                    <YAxis domain={[0, sizeAxis.top]} ticks={sizeAxis.ticks} interval={0} width={58} tickLine={false} axisLine={false}
                      tickFormatter={(v) => formatSize(v, { locale })} tick={{ fontSize: 11 }} />
                    {shown[0] === 'size'
                      ? tooltip
                      : <ChartTooltip isAnimationActive={false} cursor={{ stroke: 'var(--muted-foreground)', strokeOpacity: 0.35 }} content={() => null} />}
                    <Area dataKey="size" type="monotone" stroke="var(--color-size)" strokeWidth={1.75} fill={`url(#${sizeGradId})`}
                      dot={dot('size')} activeDot={{ r: 3.5, fill: 'var(--color-size)', stroke: 'var(--card)', strokeWidth: 2 }}
                      isAnimationActive={false} connectNulls={false} />
                  </ComposedChart>
                </ChartContainer>
              </div>
            )}
            {shown.includes('grade') && (
              <div data-slot="prs-trend-grade" className={cn('flex flex-col gap-1', shown[0] !== 'grade' && 'border-t pt-2')}>
                {head('grade', t('prs.trend.grade'))}
                <ChartContainer config={config} className="aspect-auto h-[110px] w-full sm:h-[130px]">
                  <ComposedChart data={points} syncId={syncId} margin={{ ...margin, top: 6 }} onClick={pick}>
                    <CartesianGrid vertical={false} />
                    {xAxis(shown.at(-1) !== 'grade')}
                    <YAxis domain={[0, 100]} ticks={GRADE_TICKS} interval={0} width={58} tickLine={false} axisLine={false} tick={{ fontSize: 11 }} />
                    {shown[0] === 'grade'
                      ? tooltip
                      : <ChartTooltip isAnimationActive={false} cursor={{ stroke: 'var(--muted-foreground)', strokeOpacity: 0.35 }} content={() => null} />}
                    <Line dataKey="grade" type="monotone" stroke="var(--color-grade)" strokeWidth={2} dot={dot('grade')}
                      activeDot={{ r: 3.5, fill: 'var(--color-grade)', stroke: 'var(--card)', strokeWidth: 2 }} isAnimationActive={false} connectNulls={false} />
                  </ComposedChart>
                </ChartContainer>
              </div>
            )}
            {shown.length === 0 && (
              <p className="m-0 rounded-md bg-muted/50 px-3 py-2 text-xs text-muted-foreground">{t('prs.trend.noSeries')}</p>
            )}
            <div data-slot="prs-trend-legend" className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
              <span className="inline-flex items-center gap-1.5"><SeriesKey shape="dashed" color="var(--success)" />{t('prs.trend.ref3')}</span>
              <span className="inline-flex items-center gap-1.5"><SeriesKey shape="dashed" color="var(--destructive)" />{t('prs.trend.ref5')}</span>
              {gaps.length > 0 && <span className="inline-flex items-center gap-1.5"><span aria-hidden="true" className="font-bold text-destructive">×</span>{t('prs.trend.gapLegend')}</span>}
              <span>{t('prs.trend.estimateNote')}</span>
              {onSelect && <span>{t('prs.trend.clickHint')}</span>}
            </div>
          </>
        )}
        <p id={summaryId} data-slot="prs-trend-summary" className="sr-only">{summary}</p>
      </figure>
    </Section>
  )
}

/** İpucu: analiz zamanı, tahmini yüklenme, not, sayfa boyutu; tamamlanmamış analiz "tamamlanmadı". */
function TrendTooltip({ active, payload, t, locale, clickable }) {
  if (!active || !Array.isArray(payload) || payload.length === 0) return null
  const p = payload.find((x) => x?.payload)?.payload
  if (!p) return null
  // Sınırlı ölçüm notu soluk (asla yeşil değil) ve ipucunda söylenir
  const tone = p.capped ? 'muted' : gradeTone(p.letter)
  return (
    <div data-slot="prs-trend-tooltip" className="grid w-max max-w-[17rem] min-w-[11rem] gap-1.5 rounded-lg border border-border/60 bg-background px-3 py-2 text-xs shadow-xl">
      <div className="font-semibold text-foreground tabular-nums">{formatDate(p.ts)}</div>
      {p.gap ? (
        <div className="text-muted-foreground">{t('prs.trend.tipNotDone')}</div>
      ) : (
        <div className="grid gap-1">
          <TipRow label={t('prs.trend.load')} value={formatMs(p.load, locale)} color="var(--chart-1)" />
          <TipRow label={t('prs.trend.size')} value={formatSize(p.size, { locale })} color="var(--chart-5)" />
          {p.grade != null && (
            <div className="flex items-center justify-between gap-2">
              <span className="text-muted-foreground">{t('prs.grade')}</span>
              <ToneBadge tone={tone} className="font-bold tabular-nums">{p.grade}{p.letter ? ` · ${p.letter}` : ''}</ToneBadge>
            </div>
          )}
          {p.capped && <div data-slot="prs-trend-tip-capped" className="text-muted-foreground">{t('prs.trend.tipCapped')}</div>}
          {clickable && <div className="text-muted-foreground">{t('prs.trend.tipOpen')}</div>}
        </div>
      )}
    </div>
  )
}

function TipRow({ label, value, color }) {
  return (
    <div className="flex items-center gap-2">
      <SeriesKey shape="line" color={color} />
      <span className="min-w-0 flex-1 text-muted-foreground">{label}</span>
      <span className="font-semibold text-foreground tabular-nums">{value}</span>
    </div>
  )
}
