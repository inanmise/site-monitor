import { useId, useMemo } from 'react'
import { Card } from '@/components/shadcn/card'
import {
  ChartContainer, ChartTooltip, ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid, ReferenceLine,
} from '@/components/shadcn/chart'
import { SeriesKey } from '../../responsechart/SeriesKey.jsx'
import { formatTick, niceTimeTicks } from '../../responsechart/responseChartModel.js'
import { dateLocale } from '../../../i18n/dateLocale.js'
import { peakValue } from './metricsModel.js'

/**
 * Metrikler sayfasının tek grafik kartı (2026-10-13) — shadcn Chart (ChartContainer + recharts), Sistem Sağlığı HTTP
 * grafikleriyle aynı dağarcık: başlık + "şimdi" değeri, tek eksen, isteğe bağlı uyarı / kritik eşik çizgileri (yalnız
 * verinin 3 katına kadar — ekseni uzatıp veriyi tabana ezmez), seri anahtarı, ekran okuyucu özeti. Seri anahtarları CSS
 * değişkeni adı olur, bu yüzden çağıran GÜVENLİ anahtar verir (pod kimliği değil, `p0`, `p1`…). Değer olmayan kova boşluk
 * kalır (`connectNulls=false`).
 */
const CHART_H = 'aspect-auto h-[136px] w-full sm:h-[148px]'
const VIEW_FACTOR = 3

function tipTime(ms) {
  try {
    return new Date(ms).toLocaleString(dateLocale(), { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' })
  } catch { return '' }
}

function Tip({ active, payload, series, format }) {
  if (!active || !Array.isArray(payload) || !payload.length) return null
  const row = payload.find((x) => x?.payload)?.payload
  if (!row) return null
  const items = series.map((s) => ({ ...s, v: row[s.key] })).filter((s) => typeof s.v === 'number')
    .sort((a, b) => b.v - a.v)
  return (
    <div data-slot="chart-tooltip" className="grid max-w-[18rem] min-w-[10rem] gap-1 rounded-lg border border-border/60 bg-background px-2.5 py-2 text-xs shadow-xl">
      <div className="font-semibold text-foreground tabular-nums">{tipTime(row.t)}</div>
      {items.map((s) => (
        <div key={s.key} className="flex items-start gap-2">
          <span className="mt-0.5"><SeriesKey shape={s.shape || 'line'} color={s.color} /></span>
          <span className="min-w-0 flex-1 text-muted-foreground [overflow-wrap:anywhere]">{s.label}</span>
          <span className="font-semibold text-foreground tabular-nums">{format(s.v)}</span>
        </div>
      ))}
    </div>
  )
}

export function SeriesLegend({ series }) {
  return (
    <div className="flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-muted-foreground">
      {series.map((s) => (
        <span key={s.key} className="inline-flex min-w-0 items-center gap-1.5">
          <SeriesKey shape={s.shape || 'line'} color={s.color} />
          <span className="[overflow-wrap:anywhere]">{s.label}</span>
        </span>
      ))}
    </div>
  )
}

export default function MetricChart({
  id, t, title, rows, series, format = (v) => String(v), axisFormat, thresholds, current, summary, domain, from, to,
  yMax, allowDecimals = true,
}) {
  const uid = useId().replace(/:/g, '')
  const keys = useMemo(() => series.map((s) => s.key), [series])
  const peak = useMemo(() => peakValue(rows, keys), [rows, keys])
  const x0 = from ?? rows[0]?.t ?? 0
  const x1 = Math.max(to ?? rows.at(-1)?.t ?? 1, x0 + 60_000)
  const { ticks, step, span } = useMemo(() => niceTimeTicks(x0, x1, 4), [x0, x1])
  const config = useMemo(() => Object.fromEntries(series.map((s) => [s.key, { label: s.label, color: s.color }])), [series])
  const empty = peak == null

  // Eşik çizgisi yalnız verinin 3 katına kadar çizilir; anahtar da yalnız çizilen çizgiyi gösterir
  const showWarn = !!thresholds && !empty && thresholds.warn != null && thresholds.warn <= Math.max(peak, 1) * VIEW_FACTOR
  const showCrit = !!thresholds && !empty && thresholds.crit != null && thresholds.crit <= Math.max(peak, 1) * VIEW_FACTOR
  const thrLines = [
    showWarn && <ReferenceLine key="w" y={thresholds.warn} stroke="var(--chart-3)" strokeDasharray="4 3" strokeWidth={1} ifOverflow="extendDomain" />,
    showCrit && <ReferenceLine key="c" y={thresholds.crit} stroke="var(--destructive)" strokeDasharray="4 3" strokeWidth={1} ifOverflow="extendDomain" />,
  ]
  // Nokta yalnız YALNIZ BAŞINA kalan değerde: iki yanı boş (null) bir değer connectNulls=false ile sıfır uzunlukta çizgi
  // olur ve görünmezdi (dağıtımdan sonra yeni podun ilk kovası); her noktaya nokta koymak çizgiyi kesikli gösteriyordu.
  const isolatedDot = (key, color) => function IsolatedDot(props) {
    const { cx, cy, index, value } = props
    const k = `${key}-${index}`
    const v = Array.isArray(value) ? value[1] : value
    if (typeof v !== 'number' || cx == null || cy == null) return <g key={k} />
    if (typeof rows[index - 1]?.[key] === 'number' || typeof rows[index + 1]?.[key] === 'number') return <g key={k} />
    return <circle key={k} data-slot="mx-dot" cx={cx} cy={cy} r={2.5} fill={color} stroke="none" />
  }

  return (
    <Card data-slot="mx-chart" data-chart-id={id} data-empty={empty || undefined} className="min-w-0 gap-2 px-3 py-3 shadow-xs">
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <h4 id={`${uid}-t`} className="m-0 min-w-0 text-[13px] font-semibold break-words text-foreground">{title}</h4>
        <span data-slot="mx-chart-current" className="ml-auto text-sm font-bold text-foreground tabular-nums">{current ?? '—'}</span>
      </div>
      <figure aria-labelledby={`${uid}-t`} aria-describedby={`${uid}-s`} className="m-0 flex min-w-0 flex-col gap-1.5">
        {empty ? (
          <p data-slot="mx-chart-empty" className="m-0 flex h-[136px] items-center justify-center rounded-md bg-muted/40 px-3 text-center text-xs text-muted-foreground sm:h-[148px]">
            {t('mx.chart.noData')}
          </p>
        ) : (
          <ChartContainer config={config} className={CHART_H}>
            <ComposedChart data={rows} margin={{ top: 6, right: 6, left: 0, bottom: 0 }}>
              <CartesianGrid vertical={false} />
              <XAxis dataKey="t" type="number" scale="time" domain={domain || [x0, x1]} ticks={ticks} interval={0} tickLine={false}
                axisLine={false} tickMargin={6} tick={{ fontSize: 10 }} tickFormatter={(v) => formatTick(v, { step, span })} />
              <YAxis width={44} tickCount={3} allowDecimals={allowDecimals} tickLine={false} axisLine={false} tick={{ fontSize: 10 }}
                tickFormatter={axisFormat || format} domain={[0, yMax ?? 'auto']} />
              <ChartTooltip isAnimationActive={false} cursor={{ stroke: 'var(--muted-foreground)', strokeOpacity: 0.35, strokeWidth: 1 }}
                content={<Tip series={series} format={format} />} />
              {series.map((s) => (s.shape === 'area' ? (
                <Area key={s.key} dataKey={s.key} type="monotone" stroke={s.color} fill={s.color} fillOpacity={0.14} strokeWidth={1.5}
                  dot={isolatedDot(s.key, s.color)} activeDot={{ r: 3 }} connectNulls={false} isAnimationActive={false} />
              ) : (
                <Line key={s.key} dataKey={s.key} type="monotone" stroke={s.color} strokeWidth={s.shape === 'dashed' ? 1.25 : 1.75}
                  strokeDasharray={s.shape === 'dashed' ? '4 3' : undefined} dot={isolatedDot(s.key, s.color)}
                  activeDot={{ r: 3 }} connectNulls={false} isAnimationActive={false} />
              )))}
              {thrLines}
            </ComposedChart>
          </ChartContainer>
        )}
        <p id={`${uid}-s`} className="sr-only">{summary || title}</p>
      </figure>
      <SeriesLegend series={[...series,
        ...(showWarn ? [{ key: '__w', shape: 'dashed', color: 'var(--chart-3)', label: t('mx.chart.warnAt', format(thresholds.warn)) }] : []),
        ...(showCrit ? [{ key: '__c', shape: 'dashed', color: 'var(--destructive)', label: t('mx.chart.critAt', format(thresholds.crit)) }] : []),
      ]} />
    </Card>
  )
}
