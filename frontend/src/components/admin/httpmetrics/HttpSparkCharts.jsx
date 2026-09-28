import { useId, useMemo } from 'react'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import {
  ChartContainer, ChartTooltip, ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid, ReferenceLine,
} from '@/components/shadcn/chart'
import { SeriesKey } from '../../responsechart/SeriesKey.jsx'
import { formatTick, niceTimeTicks } from '../../responsechart/responseChartModel.js'
import { HEALTH_THRESHOLDS } from '../health/healthModel.js'
import { CLASS_COLOR, LATENCY_COLOR, breaches, clockLabel, downsample, fmtInt, fmtRate } from './httpMetricsModel.js'
import { cn } from '@/lib/utils'

/**
 * Sistem Sağlığı → HTTP istekleri bölümünün üç küçük grafiği (son 24 saat, dakikalık, bellek içi) — shadcn Chart
 * (ChartContainer + recharts), 20.89.0 süre grafiğiyle aynı dağarcık (SeriesKey, jeton renkleri, tek eksen):
 *  1) İstek hacmi: başarılı + hatalı (4xx/5xx) yığılmış alan
 *  2) Yanıt süresi: ortalama (alan) + p95 (kesikli) + uyarı/kritik eşik çizgileri, eşik üstü dakika rozeti
 *  3) Hata / dk: alan + eşik çizgileri, eşik üstü dakika rozeti (eski MiniChart'ın "N ihlal" rozetinin karşılığı)
 * Her biri kendi tek eksenli grafiği (iki ölçek tek grafiğe bindirilmez). İpucu imleci dokunmatikte de çalışır.
 */

const MINI_H = 'aspect-auto h-[112px] w-full sm:h-[120px]'
/** Eşik çizgisi yalnız verinin 3 katına kadar çizilir (ekseni uzatıp veriyi tabana ezmemek için; ChartPanel kuralı). */
const VIEW_FACTOR = 3

const hm = (t) => clockLabel(t)
/** Dilim tek dakikaysa "14:05", seyreltilmişse "14:00–14:05". */
const timeLabel = (p) => (p.span > 60_000 ? `${hm(p.t)}–${hm(p.t + p.span)}` : hm(p.t))

function MiniTip({ active, payload, rows, note }) {
  if (!active || !Array.isArray(payload) || !payload.length) return null
  const p = payload.find((x) => x?.payload)?.payload
  if (!p) return null
  return (
    <div data-slot="chart-tooltip" className="grid min-w-[9rem] gap-1 rounded-lg border border-border/60 bg-background px-2.5 py-2 text-xs shadow-xl">
      <div className="font-semibold text-foreground tabular-nums">{timeLabel(p)}</div>
      {note && p.span > 60_000 && <div className="text-[11px] text-muted-foreground">{note}</div>}
      {rows(p).map((r) => (
        <div key={r.label} className="flex items-center gap-2">
          {r.shape ? <SeriesKey shape={r.shape} color={r.color} /> : <span aria-hidden="true" className="w-3.5" />}
          <span className="min-w-0 flex-1 truncate text-muted-foreground">{r.label}</span>
          <span className="font-semibold text-foreground tabular-nums">{r.value}</span>
        </div>
      ))}
    </div>
  )
}

function BreachBadge({ b, t }) {
  if (!b) return null
  const ok = b.total === 0
  return (
    <Badge variant="outline" data-slot="hreq-breach" data-breach={b.tone}
      className={cn('h-6 rounded-full px-2 text-[11px] font-semibold',
        ok ? 'border-transparent bg-success/15 text-success'
          : b.tone === 'crit' ? 'border-transparent bg-destructive/15 text-destructive'
            : 'border-transparent bg-amber-500/15 text-amber-700 dark:text-amber-300')}>
      {ok ? t('hreq.chart.noBreach') : t('hreq.chart.breach', b.total)}
    </Badge>
  )
}

function MiniCard({ id, title, current, badge, legend, summary, children }) {
  const uid = useId().replace(/:/g, '')
  return (
    <Card data-slot="hreq-mini" data-chart-id={id} className="min-w-0 gap-2 px-3 py-3 shadow-xs">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <h4 id={`${uid}-t`} className="m-0 text-[13px] font-semibold text-foreground">{title}</h4>
        {badge}
        <span data-slot="hreq-mini-current" className="ml-auto text-sm font-bold text-foreground tabular-nums">{current}</span>
      </div>
      <figure aria-labelledby={`${uid}-t`} aria-describedby={`${uid}-s`} className="m-0 flex min-w-0 flex-col gap-1.5">
        {children}
        <p id={`${uid}-s`} className="sr-only">{summary}</p>
      </figure>
      {legend && <div className="flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-muted-foreground">{legend}</div>}
    </Card>
  )
}

const Key = ({ shape, color, label }) => (
  <span className="inline-flex items-center gap-1.5"><SeriesKey shape={shape} color={color} />{label}</span>
)

const ThrKeys = ({ t, warn, crit }) => (
  <>
    <Key shape="dashed" color="var(--chart-3)" label={t('hreq.chart.warnAt', warn)} />
    <Key shape="dashed" color="var(--destructive)" label={t('hreq.chart.critAt', crit)} />
  </>
)

export default function HttpSparkCharts({ t, points: raw, fmt }) {
  const gradId = `hreq-g-${useId().replace(/:/g, '')}`
  // Çizim seyreltilmiş dilimlerle; ihlal sayıları / son dakika / tepe HAM dakikalardan.
  const points = useMemo(() => downsample(raw), [raw])
  const domain = useMemo(() => (points.length ? [points[0].t, points.at(-1).t] : [0, 1]), [points])
  const { ticks, step, span } = useMemo(() => niceTimeTicks(domain[0], Math.max(domain[1], domain[0] + 60_000), 4), [domain])
  const lat = HEALTH_THRESHOLDS.httpMs
  const errThr = HEALTH_THRESHOLDS.httpErr
  const latBreach = useMemo(() => breaches(raw.map((p) => p.avg), lat), [raw, lat])
  const errBreach = useMemo(() => breaches(raw.map((p) => p.errors), errThr), [raw, errThr])
  const maxAvg = points.reduce((m, p) => Math.max(m, p.p95 ?? p.avg ?? 0), 0)
  const maxErr = points.reduce((m, p) => Math.max(m, p.errPeak), 0)
  const peak = raw.reduce((m, p) => Math.max(m, p.count), 0)
  const lastRaw = raw.at(-1)
  const sparse = points.length <= 60

  if (points.length === 0) {
    return <p data-slot="hreq-collecting" className="m-0 rounded-md bg-muted/50 px-3 py-2 text-xs text-muted-foreground">{t('hreq.chart.collecting')}</p>
  }

  const xAxis = <XAxis dataKey="t" type="number" scale="time" domain={domain} ticks={ticks} interval={0} tickLine={false} axisLine={false}
    tickMargin={6} tick={{ fontSize: 10 }} tickFormatter={(v) => formatTick(v, { step, span })} />
  const grid = <CartesianGrid vertical={false} />
  const cursor = { stroke: 'var(--muted-foreground)', strokeOpacity: 0.35, strokeWidth: 1 }
  const margin = { top: 6, right: 6, left: 0, bottom: 0 }
  const thrLines = (thr, top) => [
    thr.warn <= top * VIEW_FACTOR && <ReferenceLine key="w" y={thr.warn} stroke="var(--chart-3)" strokeDasharray="4 3" strokeWidth={1} ifOverflow="extendDomain" />,
    thr.crit <= top * VIEW_FACTOR && <ReferenceLine key="c" y={thr.crit} stroke="var(--destructive)" strokeDasharray="4 3" strokeWidth={1} ifOverflow="extendDomain" />,
  ]
  const dot = sparse ? { r: 2, strokeWidth: 0 } : false

  return (
    <div data-slot="http-charts" className="grid grid-cols-1 gap-3 md:grid-cols-[repeat(auto-fit,minmax(min(280px,100%),1fr))]">
      <MiniCard id="volume" title={t('hreq.chart.volume')}
        current={t('hreq.chart.perMin', fmtInt(lastRaw?.count))}
        summary={t('hreq.chart.volumeSummary', fmtInt(lastRaw?.count), fmtInt(peak))}
        legend={<><Key shape="area" color={CLASS_COLOR['2xx']} label={t('hreq.series.ok')} /><Key shape="area" color={CLASS_COLOR['5xx']} label={t('hreq.series.failed')} /></>}>
        <ChartContainer config={{ ok: { label: t('hreq.series.ok'), color: CLASS_COLOR['2xx'] }, errors: { label: t('hreq.series.failed'), color: CLASS_COLOR['5xx'] } }}
          className={MINI_H}>
          <ComposedChart data={points} margin={margin}>
            {grid}{xAxis}
            <YAxis width={34} tickCount={3} allowDecimals={false} tickLine={false} axisLine={false} tick={{ fontSize: 10 }} />
            <ChartTooltip isAnimationActive={false} cursor={cursor} content={<MiniTip note={t('hreq.tip.avgPerMin')} rows={(p) => [
              { label: t('hreq.series.ok'), value: fmtRate(p.ok), shape: 'area', color: CLASS_COLOR['2xx'] },
              { label: t('hreq.series.failed'), value: fmtRate(p.errors), shape: 'area', color: CLASS_COLOR['5xx'] },
              { label: t('hreq.tip.total'), value: fmtRate(p.count) },
            ]} />} />
            {/* Hatalar TABANDA (taban çizgisine yaslı, okunur); üst sınır = toplam hacim (yeşil) */}
            <Area dataKey="errors" stackId="v" type="monotone" stroke="var(--color-errors)" fill="var(--color-errors)" fillOpacity={0.5}
              strokeWidth={1.25} dot={false} activeDot={{ r: 3 }} isAnimationActive={false} />
            <Area dataKey="ok" stackId="v" type="monotone" stroke="var(--color-ok)" fill="var(--color-ok)" fillOpacity={0.16}
              strokeWidth={1.5} dot={dot} activeDot={{ r: 3 }} isAnimationActive={false} />
          </ComposedChart>
        </ChartContainer>
      </MiniCard>

      <MiniCard id="latency" title={t('hreq.chart.latency')} badge={<BreachBadge b={latBreach} t={t} />}
        current={fmt.value(lastRaw?.avg)}
        summary={t('hreq.chart.latencySummary', fmt.value(lastRaw?.avg), latBreach.total)}
        legend={<><Key shape="line" color={LATENCY_COLOR} label={t('chart.avg')} /><Key shape="dashed" color={LATENCY_COLOR} label={t('chart.p95')} />
          <ThrKeys t={t} warn={fmt.value(lat.warn)} crit={fmt.value(lat.crit)} /></>}>
        <ChartContainer config={{ avg: { label: t('chart.avg'), color: LATENCY_COLOR }, p95: { label: t('chart.p95'), color: LATENCY_COLOR } }} className={MINI_H}>
          <ComposedChart data={points} margin={margin}>
            <defs>
              <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="var(--color-avg)" stopOpacity={0.28} />
                <stop offset="95%" stopColor="var(--color-avg)" stopOpacity={0.02} />
              </linearGradient>
            </defs>
            {grid}{xAxis}
            <YAxis width={46} tickCount={3} tickLine={false} axisLine={false} tick={{ fontSize: 10 }} tickFormatter={fmt.axis} domain={[0, 'auto']} />
            <ChartTooltip isAnimationActive={false} cursor={cursor} content={<MiniTip rows={(p) => [
              { label: t('chart.avg'), value: fmt.value(p.avg), shape: 'line', color: LATENCY_COLOR },
              { label: p.span > 60_000 ? t('hreq.tip.p95Peak') : t('chart.p95'), value: fmt.value(p.p95), shape: 'dashed', color: LATENCY_COLOR },
              { label: t('hreq.tip.requests'), value: fmtRate(p.count) },
            ]} />} />
            <Area dataKey="avg" type="monotone" stroke="var(--color-avg)" strokeWidth={1.75} fill={`url(#${gradId})`} dot={dot}
              activeDot={{ r: 3 }} connectNulls={false} isAnimationActive={false} />
            <Line dataKey="p95" type="monotone" stroke="var(--color-p95)" strokeWidth={1.25} strokeDasharray="4 3" dot={false}
              activeDot={false} connectNulls={false} isAnimationActive={false} />
            {thrLines(lat, maxAvg)}
          </ComposedChart>
        </ChartContainer>
      </MiniCard>

      <MiniCard id="errors" title={t('hreq.chart.errors')} badge={<BreachBadge b={errBreach} t={t} />}
        current={fmtInt(lastRaw?.errors)}
        summary={t('hreq.chart.errorsSummary', fmtInt(lastRaw?.errors), errBreach.total)}
        legend={<><Key shape="area" color={CLASS_COLOR['5xx']} label={t('hreq.series.errors')} />
          <ThrKeys t={t} warn={errThr.warn} crit={errThr.crit} /></>}>
        <ChartContainer config={{ errors: { label: t('hreq.series.errors'), color: CLASS_COLOR['5xx'] } }} className={MINI_H}>
          <ComposedChart data={points} margin={margin}>
            {grid}{xAxis}
            <YAxis width={34} tickCount={3} allowDecimals={false} tickLine={false} axisLine={false} tick={{ fontSize: 10 }} domain={[0, 'auto']} />
            <ChartTooltip isAnimationActive={false} cursor={cursor} content={<MiniTip rows={(p) => [
              { label: p.span > 60_000 ? t('hreq.tip.errPeak') : t('hreq.series.errors'), value: fmtInt(p.errPeak), shape: 'area', color: CLASS_COLOR['5xx'] },
              { label: t('hreq.tip.requests'), value: fmtRate(p.count) },
            ]} />} />
            <Area dataKey="errPeak" type="monotone" stroke="var(--color-errors)" fill="var(--color-errors)" fillOpacity={0.2}
              strokeWidth={1.5} dot={dot} activeDot={{ r: 3 }} isAnimationActive={false} />
            {thrLines(errThr, Math.max(1, maxErr))}
          </ComposedChart>
        </ChartContainer>
      </MiniCard>
    </div>
  )
}
