import { useId, useMemo } from 'react'
import { Card } from '@/components/shadcn/card'
import {
  ChartContainer, ChartTooltip, ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid, ReferenceLine,
} from '@/components/shadcn/chart'
import { SeriesLegend } from '../metrics/MetricChart.jsx'
import {
  TOTAL_COLOR, FORECAST_COLOR, formatBytes, formatBytesAxis, formatDay, totalChartRows, tableChartData,
} from './dbGrowthModel.js'

/**
 * Veritabanı büyümesi grafikleri (2026-11-13) — shadcn Chart (ChartContainer + recharts), Metrikler sayfasının dağarcığı:
 * eksik gün boşluk kalır (`connectNulls=false`), yalnız başına kalan değer nokta olarak çizilir (sıfır uzunluklu çizgi
 * görünmezdi), renkler durum rengi değil (mavi / mor), ekran okuyucuya metin özeti.
 */
const CHART_H = 'aspect-auto h-[200px] w-full sm:h-[230px]'
const DAY = 86_400_000

function iso(ms) {
  return new Date(ms).toISOString().slice(0, 10)
}

/** Gün ekseni işaretleri: en fazla ~5 eşit aralıklı gün. */
function dayTicks(rows, count = 5) {
  if (!rows.length) return []
  const a = rows[0].t
  const b = rows.at(-1).t
  if (a === b) return [a]
  const span = Math.max(1, Math.round((b - a) / DAY))
  const step = Math.max(1, Math.ceil(span / (count - 1)))
  const out = []
  for (let d = 0; d <= span; d += step) out.push(a + d * DAY)
  if (out.at(-1) !== b && (b - out.at(-1)) / DAY >= step / 2) out.push(b)
  return out
}

function isolatedDot(rows, key, color) {
  return function IsolatedDot(props) {
    const { cx, cy, index, value } = props
    const k = `${key}-${index}`
    const v = Array.isArray(value) ? value[1] : value
    if (typeof v !== 'number' || cx == null || cy == null) return <g key={k} />
    if (typeof rows[index - 1]?.[key] === 'number' || typeof rows[index + 1]?.[key] === 'number') return <g key={k} />
    return <circle key={k} data-slot="dbg-dot" cx={cx} cy={cy} r={2.5} fill={color} stroke="none" />
  }
}

function TotalTip({ active, payload, t }) {
  if (!active || !Array.isArray(payload) || !payload.length) return null
  const row = payload.find((x) => x?.payload)?.payload
  if (!row) return null
  return (
    <div data-slot="chart-tooltip" className="grid max-w-[18rem] min-w-[11rem] gap-1 rounded-lg border border-border/60 bg-background px-2.5 py-2 text-xs shadow-xl">
      <div className="font-semibold text-foreground tabular-nums">{formatDay(iso(row.t))}</div>
      {typeof row.actual === 'number' && (
        <div className="flex justify-between gap-3"><span className="text-muted-foreground">{t('dbg.chart.actual')}</span>
          <span className="font-semibold tabular-nums">{formatBytes(row.actual)}</span></div>
      )}
      {typeof row.forecast === 'number' && typeof row.actual !== 'number' && (
        <>
          <div className="flex justify-between gap-3"><span className="text-muted-foreground">{t('dbg.chart.forecast')}</span>
            <span className="font-semibold tabular-nums">{formatBytes(row.forecast)}</span></div>
          {Array.isArray(row.band) && (
            <div className="flex justify-between gap-3"><span className="text-muted-foreground">{t('dbg.chart.band')}</span>
              <span className="tabular-nums">{formatBytes(row.band[0])} – {formatBytes(row.band[1])}</span></div>
          )}
        </>
      )}
    </div>
  )
}

/** Toplam boyut: gerçek (düz) + tahmin (kesikli) + belirsizlik bandı + varsa kapasite çizgisi. */
export function TotalSizeChart({ view, range, t }) {
  const uid = useId().replace(/:/g, '')
  const rows = useMemo(() => totalChartRows(view, range), [view, range])
  const ticks = useMemo(() => dayTicks(rows), [rows])
  const capBytes = view?.capacity_gb ? Number(view.capacity_gb) * 1024 ** 3 : null
  const peak = rows.reduce((m, r) => Math.max(m, r.actual ?? 0, r.forecast ?? 0, Array.isArray(r.band) ? r.band[1] : 0), 0)
  // Kapasite çizgisi yalnız verinin 3 katına kadar çizilir (ekseni uzatıp veriyi tabana ezmez)
  const showCap = capBytes != null && peak > 0 && capBytes <= peak * 3
  const hasForecast = rows.some((r) => typeof r.forecast === 'number')
  const config = {
    actual: { label: t('dbg.chart.actual'), color: TOTAL_COLOR },
    forecast: { label: t('dbg.chart.forecast'), color: FORECAST_COLOR },
    band: { label: t('dbg.chart.band'), color: FORECAST_COLOR },
  }
  const last = [...rows].reverse().find((r) => typeof r.actual === 'number')
  const summary = t('dbg.chart.totalSummary', last ? formatBytes(last.actual) : '—',
    view?.horizons?.find((h) => h.days === 90) ? formatBytes(view.horizons.find((h) => h.days === 90).value) : '—')
  const legend = [
    { key: 'a', label: t('dbg.chart.actual'), color: TOTAL_COLOR, shape: 'line' },
    ...(hasForecast ? [
      { key: 'f', label: t('dbg.chart.forecast'), color: FORECAST_COLOR, shape: 'dashed' },
      { key: 'b', label: t('dbg.chart.band'), color: FORECAST_COLOR, shape: 'area' },
    ] : []),
    ...(showCap ? [{ key: 'c', label: t('dbg.chart.capacity', formatBytes(capBytes)), color: 'var(--destructive)', shape: 'dashed' }] : []),
  ]
  return (
    <Card data-slot="dbg-chart" data-chart-id="total" data-empty={rows.length ? undefined : true} className="min-w-0 gap-2 px-3 py-3 shadow-xs">
      <h4 id={`${uid}-t`} className="m-0 text-[13px] font-semibold break-words text-foreground">{t('dbg.chart.totalTitle')}</h4>
      <figure aria-labelledby={`${uid}-t`} aria-describedby={`${uid}-s`} className="m-0 flex min-w-0 flex-col gap-1.5">
        {!rows.length ? (
          <p data-slot="dbg-chart-empty" className="m-0 flex h-[200px] items-center justify-center rounded-md bg-muted/40 px-3 text-center text-xs text-muted-foreground">
            {t('dbg.chart.noData')}
          </p>
        ) : (
          <ChartContainer config={config} className={CHART_H}>
            <ComposedChart data={rows} margin={{ top: 6, right: 8, left: 0, bottom: 0 }}>
              <CartesianGrid vertical={false} />
              <XAxis dataKey="t" type="number" scale="time" domain={[rows[0].t, rows.at(-1).t]} ticks={ticks} interval={0}
                tickLine={false} axisLine={false} tickMargin={6} tick={{ fontSize: 10 }} tickFormatter={(v) => formatDay(iso(v), false)} />
              <YAxis width={54} tickCount={4} tickLine={false} axisLine={false} tick={{ fontSize: 10 }} tickFormatter={formatBytesAxis}
                domain={['auto', 'auto']} />
              <ChartTooltip isAnimationActive={false} cursor={{ stroke: 'var(--muted-foreground)', strokeOpacity: 0.35, strokeWidth: 1 }}
                content={<TotalTip t={t} />} />
              <Area dataKey="band" type="monotone" stroke="none" fill={FORECAST_COLOR} fillOpacity={0.14} connectNulls={false}
                isAnimationActive={false} activeDot={false} />
              <Line dataKey="actual" type="monotone" stroke={TOTAL_COLOR} strokeWidth={1.75} dot={isolatedDot(rows, 'actual', TOTAL_COLOR)}
                activeDot={{ r: 3 }} connectNulls={false} isAnimationActive={false} />
              <Line dataKey="forecast" type="monotone" stroke={FORECAST_COLOR} strokeWidth={1.5} strokeDasharray="5 4" dot={false}
                activeDot={{ r: 3 }} connectNulls={false} isAnimationActive={false} />
              {showCap && <ReferenceLine y={capBytes} stroke="var(--destructive)" strokeDasharray="4 3" strokeWidth={1} ifOverflow="extendDomain" />}
            </ComposedChart>
          </ChartContainer>
        )}
        <p id={`${uid}-s`} className="sr-only">{summary}</p>
      </figure>
      <SeriesLegend series={legend} />
    </Card>
  )
}

function TablesTip({ active, payload, series }) {
  if (!active || !Array.isArray(payload) || !payload.length) return null
  const row = payload.find((x) => x?.payload)?.payload
  if (!row) return null
  const items = series.map((s) => ({ ...s, v: row[s.key] })).filter((s) => typeof s.v === 'number').sort((a, b) => b.v - a.v)
  return (
    <div data-slot="chart-tooltip" className="grid max-w-[20rem] min-w-[11rem] gap-1 rounded-lg border border-border/60 bg-background px-2.5 py-2 text-xs shadow-xl">
      <div className="font-semibold text-foreground tabular-nums">{formatDay(iso(row.t))}</div>
      {items.map((s) => (
        <div key={s.key} className="flex items-start gap-2">
          <span className="min-w-0 flex-1 font-mono text-[11px] text-muted-foreground [overflow-wrap:anywhere]">{s.label}</span>
          <span className="font-semibold text-foreground tabular-nums">{formatBytes(s.v)}</span>
        </div>
      ))}
    </div>
  )
}

/** En büyük 8 tablo — her tablo kendi çizgisi (4 renk × düz / kesikli). */
export function TopTablesChart({ view, t }) {
  const uid = useId().replace(/:/g, '')
  const { rows, series } = useMemo(() => tableChartData(view), [view])
  const ticks = useMemo(() => dayTicks(rows), [rows])
  const config = useMemo(() => Object.fromEntries(series.map((s) => [s.key, { label: s.label, color: s.color }])), [series])
  return (
    <Card data-slot="dbg-chart" data-chart-id="tables" data-empty={rows.length ? undefined : true} className="min-w-0 gap-2 px-3 py-3 shadow-xs">
      <h4 id={`${uid}-t`} className="m-0 text-[13px] font-semibold break-words text-foreground">{t('dbg.chart.tablesTitle')}</h4>
      <figure aria-labelledby={`${uid}-t`} aria-describedby={`${uid}-s`} className="m-0 flex min-w-0 flex-col gap-1.5">
        {!rows.length ? (
          <p data-slot="dbg-chart-empty" className="m-0 flex h-[200px] items-center justify-center rounded-md bg-muted/40 px-3 text-center text-xs text-muted-foreground">
            {t('dbg.chart.noData')}
          </p>
        ) : (
          <ChartContainer config={config} className={CHART_H}>
            <ComposedChart data={rows} margin={{ top: 6, right: 8, left: 0, bottom: 0 }}>
              <CartesianGrid vertical={false} />
              <XAxis dataKey="t" type="number" scale="time" domain={[rows[0].t, rows.at(-1).t]} ticks={ticks} interval={0}
                tickLine={false} axisLine={false} tickMargin={6} tick={{ fontSize: 10 }} tickFormatter={(v) => formatDay(iso(v), false)} />
              <YAxis width={54} tickCount={4} tickLine={false} axisLine={false} tick={{ fontSize: 10 }} tickFormatter={formatBytesAxis} />
              <ChartTooltip isAnimationActive={false} cursor={{ stroke: 'var(--muted-foreground)', strokeOpacity: 0.35, strokeWidth: 1 }}
                content={<TablesTip series={series} />} />
              {series.map((s) => (
                <Line key={s.key} dataKey={s.key} type="monotone" stroke={s.color} strokeWidth={s.shape === 'dashed' ? 1.25 : 1.75}
                  strokeDasharray={s.shape === 'dashed' ? '4 3' : undefined} dot={isolatedDot(rows, s.key, s.color)}
                  activeDot={{ r: 3 }} connectNulls={false} isAnimationActive={false} />
              ))}
            </ComposedChart>
          </ChartContainer>
        )}
        <p id={`${uid}-s`} className="sr-only">{t('dbg.chart.tablesSummary', series.map((s) => s.label).join(', ') || '—')}</p>
      </figure>
      <SeriesLegend series={series} />
    </Card>
  )
}
