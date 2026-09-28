import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { Badge } from '@/components/shadcn/badge'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import {
  ChartContainer, ChartTooltip, ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid, ReferenceLine,
} from '@/components/shadcn/chart'
import { Spinner } from '../../ui/Progress.jsx'
import { SeriesKey } from '../../responsechart/SeriesKey.jsx'
import { axisWidth, formatTick, maxTicksFor, niceTimeTicks, bucketEndLabel } from '../../responsechart/responseChartModel.js'
import { formatDate } from '../../../api/client'
import { HEALTH_THRESHOLDS } from '../health/healthModel.js'
import { CLASS_COLOR, LATENCY_COLOR, STATUS_CLASSES, fmtInt } from './httpMetricsModel.js'
import { Panel } from './HttpParts.jsx'

/**
 * İstek Gezgini'nin ana grafiği — shadcn Chart (ChartContainer + recharts), iki küçük katlı panel, ORTAK zaman ekseni
 * ve ortak imleç (`syncId`): üstte durum sınıfına göre yığılmış istek hacmi (2xx/3xx/4xx/5xx; hata katmanı 4xx+5xx),
 * altta yanıt süresi (ortalama alan + p95 kesikli + p99 noktalı, uyarı/kritik eşik çizgileri). İki ölçek TEK grafiğe
 * ikinci eksenle bindirilmez (sahte ilişki çizer). Seri aç/kapa: ToggleGroup (klavye + dokunmatik, 40 px). İpucu
 * kovanın zaman aralığını, her sınıfı, toplamı ve süreleri yazar. 60 noktadan azsa noktalar işaretlenir (tek nokta da
 * görünür). Durum süzgeci etkinse yalnız seçili sınıflar çizilir.
 */
const BUCKET_MS = { minute: 60_000, hour: 3_600_000 }
const VIEW_FACTOR = 3
const SPARSE = 60

function useWidth() {
  const [width, setWidth] = useState(0)
  const ro = useRef(null)
  const ref = useCallback((el) => {
    ro.current?.disconnect()
    ro.current = null
    if (!el) return
    const measure = () => setWidth(el.clientWidth || 0)
    measure()
    if (typeof ResizeObserver !== 'undefined') {
      ro.current = new ResizeObserver(measure)
      ro.current.observe(el)
    }
  }, [])
  useEffect(() => () => ro.current?.disconnect(), [])
  return [ref, width]
}

function Row({ label, value, shape, color, strong }) {
  return (
    <div data-slot="chart-tip-row" className="flex items-center gap-2">
      {shape ? <SeriesKey shape={shape} color={color} /> : <span aria-hidden="true" className="w-3.5 shrink-0" />}
      <span className="min-w-0 flex-1 truncate text-muted-foreground">{label}</span>
      <span className={strong ? 'font-bold text-foreground tabular-nums' : 'font-semibold text-foreground tabular-nums'}>{value}</span>
    </div>
  )
}

function TipCard({ active, payload, t, fmt, bucketMs, classes, showOther, showUnclassified }) {
  if (!active || !Array.isArray(payload) || !payload.length) return null
  const p = payload.find((x) => x?.payload)?.payload
  if (!p) return null
  return (
    <div data-slot="chart-tooltip" className="grid w-max max-w-[17rem] min-w-[12rem] gap-1.5 rounded-lg border border-border/60 bg-background px-3 py-2 text-xs shadow-xl">
      <div className="font-semibold text-foreground tabular-nums">
        {formatDate(new Date(p.t).toISOString())}<span className="font-normal text-muted-foreground"> – {bucketEndLabel(p.t, bucketMs)}</span>
      </div>
      {p.count === 0 ? (
        <div className="text-muted-foreground">{t('hreq.tip.noRequests')}</div>
      ) : (
        <>
          <div className="grid gap-1">
            {classes.filter((c) => p[c] > 0).map((c) => (
              <Row key={c} label={t(`hreq.class.${c}`)} value={fmtInt(p[c])} shape="area" color={CLASS_COLOR[c]} />
            ))}
            {showOther && p.other > 0 && (
              <Row label={t('hreq.class.other')} value={fmtInt(p.other)} shape="area" color={CLASS_COLOR.other} />
            )}
            {showUnclassified && p.unclassified > 0 && (
              <Row label={t('hreq.class.unclassified')} value={fmtInt(p.unclassified)} shape="area" color={CLASS_COLOR.unclassified} />
            )}
            <Row label={t('hreq.tip.total')} value={fmtInt(p.count)} strong />
          </div>
          <div className="grid gap-1 border-t pt-1.5">
            <Row label={t('chart.avg')} value={fmt.value(p.avg)} shape="line" color={LATENCY_COLOR} />
            <Row label={t('chart.p95')} value={fmt.value(p.p95)} shape="dashed" color={LATENCY_COLOR} />
            <Row label={t('hreq.series.p99')} value={fmt.value(p.p99)} shape="dashed" color="var(--muted-foreground)" />
          </div>
        </>
      )}
    </div>
  )
}

export default function HttpTrafficChart({ t, model, fmt, statusFilter, busy, summaryText }) {
  const uid = useId().replace(/:/g, '')
  const gradId = `hreq-lat-${uid}`
  const [measureRef, width] = useWidth()
  const [hidden, setHidden] = useState(() => new Set())
  const points = model.points
  const bucketMs = BUCKET_MS[model.granularity] ?? 60_000
  const allowed = statusFilter?.length ? STATUS_CLASSES.filter((c) => statusFilter.includes(c)) : STATUS_CLASSES
  const present = allowed.filter((c) => (model.summary.classes[c] || 0) > 0)
  const classes = present.length ? present : allowed.slice(0, 1)
  const showUnclassified = !statusFilter?.length && model.summary.unclassified > 0
  // 100–599 dışı kodlar ("Diğer", status_other) toplamda vardı ama yığında ve ipucunda yoktu → üst çizgi toplamı
  // tutmuyordu (2026-09-28c ek-9). Durum süzgeci 2xx…5xx'e göredir; süzgeç etkinken çizilmez (sınıfsız gibi).
  const showOther = !statusFilter?.length && (model.summary.classes.other || 0) > 0
  const volumeKeys = [...classes, ...(showOther ? ['other'] : []), ...(showUnclassified ? ['unclassified'] : [])]
  // Yığın sırası: hatalar TABANDA (5xx → 4xx → 3xx → 2xx) — küçük hata katmanı taban çizgisine yaslanır, okunur kalır;
  // en üst çizgi 2xx'in sınırı = toplam hacim (5xx üstte olsaydı toplamın çevresi kırmızı çizilir, "her yer hata" okunurdu).
  const stackKeys = [...[...classes].reverse(), ...(showOther ? ['other'] : []), ...(showUnclassified ? ['unclassified'] : [])]
  const on = (k) => !hidden.has(k)

  const config = useMemo(() => Object.fromEntries([
    ...STATUS_CLASSES.map((c) => [c, { label: t(`hreq.class.${c}`), color: CLASS_COLOR[c] }]),
    ['other', { label: t('hreq.class.other'), color: CLASS_COLOR.other }],
    ['unclassified', { label: t('hreq.class.unclassified'), color: CLASS_COLOR.unclassified }],
    ['avg', { label: t('chart.avg'), color: LATENCY_COLOR }],
    ['p95', { label: t('chart.p95'), color: LATENCY_COLOR }],
    ['p99', { label: t('hreq.series.p99'), color: 'var(--muted-foreground)' }],
  ]), [t])

  const domain = useMemo(() => {
    if (!points.length) return [0, 1]
    const a = points[0].t
    const b = points.at(-1).t + bucketMs
    return [a, Math.max(b, a + bucketMs)]
  }, [points, bucketMs])
  const latTop = points.reduce((m, p) => Math.max(m, on('p99') ? (p.p99 ?? 0) : 0, on('p95') ? (p.p95 ?? 0) : 0, p.avg ?? 0), 0)
  const volTop = points.reduce((m, p) => Math.max(m, p.count), 0)
  const yLat = axisWidth([fmt.axis(latTop), fmt.axis(latTop / 2)])
  const yVol = axisWidth([fmtInt(volTop), fmtInt(volTop / 2)])
  const yWidth = Math.max(yLat, yVol)
  const plotWidth = Math.max(0, (width || 640) - yWidth - 20)
  const { ticks, step, span } = useMemo(() => niceTimeTicks(domain[0], domain[1], maxTicksFor(plotWidth)), [domain, plotWidth])
  const sparse = points.length <= SPARSE
  const thr = HEALTH_THRESHOLDS.httpMs
  const margin = { top: 10, right: 12, left: 0, bottom: 0 }
  const cursor = { stroke: 'var(--muted-foreground)', strokeOpacity: 0.35, strokeWidth: 1 }
  const xAxis = (hide) => (
    <XAxis dataKey="t" type="number" scale="time" domain={domain} ticks={ticks} interval={0} allowDataOverflow
      tickFormatter={(v) => formatTick(v, { step, span })} tickLine={false} axisLine={false} tickMargin={8} tick={{ fontSize: 11 }} hide={hide} />
  )
  const dot = (color) => (sparse ? { r: 3, fill: color, stroke: 'var(--card)', strokeWidth: 1.5 } : false)
  const toggleItems = [
    ...volumeKeys.map((k) => ({ key: k, label: k === 'unclassified' || k === 'other' ? t(`hreq.class.${k}`) : k, shape: 'area', color: CLASS_COLOR[k] })),
    { key: 'avg', label: t('chart.avg'), shape: 'line', color: LATENCY_COLOR },
    { key: 'p95', label: t('chart.p95'), shape: 'dashed', color: LATENCY_COLOR },
    { key: 'p99', label: t('hreq.series.p99'), shape: 'dashed', color: 'var(--muted-foreground)' },
  ]
  const visible = toggleItems.map((s) => s.key).filter((k) => !hidden.has(k))

  return (
    <Panel data-slot="hreq-traffic" aria-busy={busy || undefined}
      title={<span className="inline-flex flex-wrap items-center gap-2">{t('hreq.chart.traffic')}
        <Badge variant="outline" className="font-normal text-muted-foreground">{t(`hreq.chart.bucket.${model.granularity}`)}</Badge>
        {busy && <Spinner size={14} label={t('rtc.updating')} className="text-primary" />}</span>}
      right={(
        <ToggleGroup type="multiple" variant="outline" size="sm" spacing={1.5} data-slot="chart-series" aria-label={t('chart.seriesLabel')}
          value={visible} className="flex-wrap"
          onValueChange={(vals) => setHidden(new Set(toggleItems.map((s) => s.key).filter((k) => !vals.includes(k))))}>
          {toggleItems.map((s) => (
            <ToggleGroupItem key={s.key} value={s.key} data-series={s.key}
              className="h-8 gap-1.5 rounded-md px-2 text-xs font-medium data-[state=off]:text-muted-foreground data-[state=off]:line-through data-[state=off]:opacity-70 data-[state=on]:bg-muted max-lg:h-10 pointer-coarse:h-10">
              <SeriesKey shape={s.shape} color={s.color} />{s.label}
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
      )}>
      <figure aria-labelledby={`${uid}-cap`} aria-describedby={`${uid}-sum`} className="m-0 flex min-w-0 flex-col gap-2" ref={measureRef}>
        <figcaption id={`${uid}-cap`} className="sr-only">{t('hreq.chart.traffic')}</figcaption>
        <span className="text-xs font-medium text-muted-foreground">{t('hreq.chart.volume')}</span>
        <ChartContainer config={config} data-slot="hreq-chart-volume" className="aspect-auto h-[180px] w-full sm:h-[220px]">
          <ComposedChart data={points} syncId={`hreq-${uid}`} margin={margin}>
            <CartesianGrid vertical={false} />
            {xAxis(true)}
            <YAxis width={yWidth} tickCount={4} allowDecimals={false} tickLine={false} axisLine={false} tick={{ fontSize: 11 }}
              tickFormatter={(v) => fmtInt(v)} domain={[0, 'auto']} />
            <ChartTooltip isAnimationActive={false} cursor={cursor}
              content={<TipCard t={t} fmt={fmt} bucketMs={bucketMs} classes={classes} showOther={showOther} showUnclassified={showUnclassified} />} />
            {stackKeys.map((k) => (
              <Area key={k} dataKey={k} stackId="vol" type="monotone" hide={!on(k)} stroke={`var(--color-${k})`} strokeWidth={1.5}
                fill={`var(--color-${k})`} fillOpacity={k === '2xx' ? 0.16 : 0.5} dot={dot(`var(--color-${k})`)}
                activeDot={{ r: 3.5, stroke: 'var(--card)', strokeWidth: 1.5 }} isAnimationActive={false} />
            ))}
          </ComposedChart>
        </ChartContainer>

        <span className="border-t pt-2 text-xs font-medium text-muted-foreground">{t('hreq.chart.latency')}</span>
        <ChartContainer config={config} data-slot="hreq-chart-latency" className="aspect-auto h-[140px] w-full sm:h-[170px]">
          <ComposedChart data={points} syncId={`hreq-${uid}`} margin={{ ...margin, top: 6 }}>
            <defs>
              <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="var(--color-avg)" stopOpacity={0.28} />
                <stop offset="95%" stopColor="var(--color-avg)" stopOpacity={0.02} />
              </linearGradient>
            </defs>
            <CartesianGrid vertical={false} />
            {xAxis(false)}
            <YAxis width={yWidth} tickCount={3} tickLine={false} axisLine={false} tick={{ fontSize: 11 }} tickFormatter={fmt.axis} domain={[0, 'auto']} />
            <ChartTooltip isAnimationActive={false} cursor={cursor} content={() => null} />
            <Area dataKey="avg" type="monotone" hide={!on('avg')} stroke="var(--color-avg)" strokeWidth={2} fill={`url(#${gradId})`}
              dot={dot('var(--color-avg)')} activeDot={{ r: 3.5, stroke: 'var(--card)', strokeWidth: 1.5 }} connectNulls={false} isAnimationActive={false} />
            <Line dataKey="p95" type="monotone" hide={!on('p95')} stroke="var(--color-p95)" strokeWidth={1.5} strokeDasharray="5 3"
              dot={false} activeDot={false} connectNulls={false} isAnimationActive={false} />
            <Line dataKey="p99" type="monotone" hide={!on('p99')} stroke="var(--color-p99)" strokeWidth={1.25} strokeDasharray="2 3"
              dot={false} activeDot={false} connectNulls={false} isAnimationActive={false} />
            {thr.warn <= Math.max(latTop, 1) * VIEW_FACTOR && (
              <ReferenceLine y={thr.warn} stroke="var(--chart-3)" strokeDasharray="6 4" strokeWidth={1.25} ifOverflow="extendDomain"
                label={{ value: fmt.value(thr.warn), position: 'insideTopRight', fill: 'var(--muted-foreground)', fontSize: 10 }} />
            )}
            {thr.crit <= Math.max(latTop, 1) * VIEW_FACTOR && (
              <ReferenceLine y={thr.crit} stroke="var(--destructive)" strokeDasharray="6 4" strokeWidth={1.25} ifOverflow="extendDomain"
                label={{ value: fmt.value(thr.crit), position: 'insideTopRight', fill: 'var(--muted-foreground)', fontSize: 10 }} />
            )}
          </ComposedChart>
        </ChartContainer>
        <p id={`${uid}-sum`} data-slot="chart-summary" className="sr-only">{summaryText}</p>
      </figure>
    </Panel>
  )
}
