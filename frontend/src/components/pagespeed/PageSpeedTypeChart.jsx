import { useId, useMemo } from 'react'
import { useT } from '../../i18n/index.jsx'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import { SeriesKey } from '../responsechart/SeriesKey.jsx'
import { axisWidth, formatTick, maxTicksFor, niceTimeTicks } from '../responsechart/responseChartModel.js'
import { formatBytes } from '../../utils/formatBytes.js'
import { formatDateSec } from '../../api/client'
import { ChartContainer, ChartTooltip, BarChart, Bar, XAxis, YAxis, CartesianGrid } from '@/components/shadcn/chart'
import { MIN_BAR_PX, TYPE_KEYS, hasTypeData, rebucketTypeSeries, typeSeriesData } from './pageSpeedInsightsModel.js'

/** Tür renkleri — durum tonu DEĞİL (yeşil / kırmızı / amber yok): mavi / mor ve karışımları, gri. */
export const TYPE_COLOR = {
  html: 'var(--muted-foreground)',
  js: 'var(--chart-1)',
  css: 'var(--chart-5)',
  img: 'color-mix(in oklab, var(--chart-1) 50%, var(--chart-5))',
  font: 'color-mix(in oklab, var(--chart-5) 55%, var(--foreground))',
  other: 'color-mix(in oklab, var(--chart-1) 40%, var(--background))',
}

function Tip({ active, payload, t }) {
  if (!active || !Array.isArray(payload) || payload.length === 0) return null
  const p = payload.find((x) => x?.payload)?.payload
  if (!p || TYPE_KEYS.every((k) => p[k] == null)) return null
  return (
    <div data-slot="chart-tooltip" className="grid w-max max-w-[16rem] min-w-[10rem] gap-1 rounded-lg border border-border/60 bg-background px-3 py-2 text-xs shadow-xl">
      <div className="font-semibold text-foreground tabular-nums">{formatDateSec(new Date(p.x).toISOString())}</div>
      {TYPE_KEYS.map((k) => (
        <div key={k} className="flex items-center justify-between gap-3">
          <span className="inline-flex items-center gap-1.5 text-muted-foreground">
            <SeriesKey shape="area" color={TYPE_COLOR[k]} />{t(`pspd.type.${k}`)}
          </span>
          <span className="font-semibold tabular-nums">{p[k] == null ? '—' : formatBytes(Math.round(p[k] * 1024))}</span>
        </div>
      ))}
    </div>
  )
}

/**
 * Tür kırılımı zaman içinde (2026-11-14, madde 15) — yığın çubuk; her kova o aralıktaki ölçümlerin ORTALAMA baytı (KB).
 * Kırılımı olmayan kova (eski ölçüm, sayfa alınamadı) BOŞLUK kalır — 0 çizilmez. Recharts yalnız bu tembel modülde.
 * Test kancaları: `pspd-type-chart` (+ `data-state` ready|empty), `pspd-type-chart-gap-note`.
 */
export default function PageSpeedTypeChart({ points, truncated = false }) {
  const t = useT()
  const uid = useId().replace(/:/g, '')
  const [measureRef, width] = useElementWidth()
  const raw = useMemo(() => typeSeriesData(points), [points])
  const ready = hasTypeData(raw)
  const gaps = ready && raw.some((r) => TYPE_KEYS.every((k) => r[k] == null))
  const rawMaxKb = raw.reduce((mx, r) => Math.max(mx, TYPE_KEYS.reduce((s, k) => s + (r[k] ?? 0), 0)), 0)
  const yWidth = axisWidth([formatBytes(Math.round(rawMaxKb * 1024)), '0 B'])
  const plotWidth = Math.max(0, (width || 640) - yWidth - 40)
  // Çubuk başına en az MIN_BAR_PX: fazla kova gruplanır (inceleme F3) — yoksa 168–720 kovada çubuklar görünmez oluyordu
  const data = useMemo(() => rebucketTypeSeries(raw, Math.floor(plotWidth / MIN_BAR_PX)), [raw, plotWidth])
  const domain = [data[0]?.x ?? 0, data.at(-1)?.x ?? 1]
  const { ticks, step, span } = useMemo(() => niceTimeTicks(domain[0], domain[1], maxTicksFor(plotWidth)), [domain[0], domain[1], plotWidth]) // eslint-disable-line react-hooks/exhaustive-deps
  const config = Object.fromEntries(TYPE_KEYS.map((k) => [k, { label: t(`pspd.type.${k}`), color: TYPE_COLOR[k] }]))

  if (!ready) {
    return (
      <p data-slot="pspd-type-chart" data-state="empty" className="m-0 text-sm text-muted-foreground">{t('pspd.typeChart.empty')}</p>
    )
  }
  return (
    <figure data-slot="pspd-type-chart" data-state="ready" className="m-0 min-w-0" ref={measureRef} aria-describedby={`pspd-tc-${uid}`}>
      <ChartContainer config={config} className="aspect-auto h-[200px] w-full sm:h-[240px]">
        <BarChart data={data} margin={{ top: 8, right: 16, left: 0, bottom: 0 }} barCategoryGap={1}>
          <CartesianGrid vertical={false} />
          <XAxis dataKey="x" type="number" scale="time" domain={domain} ticks={ticks} interval={0} allowDataOverflow padding={{ left: 8, right: 8 }}
            tickFormatter={(v) => formatTick(v, { step, span })} tickLine={false} axisLine={false} tickMargin={8} tick={{ fontSize: 11 }} />
          <YAxis domain={[0, 'auto']} tickCount={4} width={yWidth} tickLine={false} axisLine={false} tick={{ fontSize: 11 }}
            tickFormatter={(v) => formatBytes(Math.round(v * 1024))} />
          <ChartTooltip isAnimationActive={false} cursor={{ fill: 'var(--muted)', fillOpacity: 0.35 }} content={<Tip t={t} />} />
          {TYPE_KEYS.map((k) => (
            <Bar key={k} dataKey={k} stackId="t" fill={`var(--color-${k})`} isAnimationActive={false} maxBarSize={18} />
          ))}
        </BarChart>
      </ChartContainer>
      <figcaption className="mt-2 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
        {TYPE_KEYS.map((k) => (
          <span key={k} className="inline-flex items-center gap-1.5"><SeriesKey shape="area" color={TYPE_COLOR[k]} />{t(`pspd.type.${k}`)}</span>
        ))}
      </figcaption>
      {gaps && <p data-slot="pspd-type-chart-gap-note" className="m-0 mt-1.5 text-xs text-muted-foreground">{t('pspd.typeChart.gapNote')}</p>}
      {truncated && <p className="m-0 mt-1 text-xs text-muted-foreground">{t('pspd.typeChart.truncated')}</p>}
      <p id={`pspd-tc-${uid}`} className="sr-only">{t('pspd.typeChart.aria')}</p>
      {/* Değerlerin metin karşılığı (inceleme F13): ekran okuyucu yalnız genel cümleyi değil, her kovanın tür baytlarını okur */}
      <ul data-slot="pspd-type-chart-values" className="sr-only">
        {data.filter((r) => TYPE_KEYS.some((k) => r[k] != null)).map((r) => (
          <li key={r.x}>
            {formatDateSec(new Date(r.x).toISOString())}: {TYPE_KEYS.filter((k) => r[k] != null)
              .map((k) => `${t(`pspd.type.${k}`)} ${formatBytes(Math.round(r[k] * 1024))}`).join(', ')}
          </li>
        ))}
      </ul>
    </figure>
  )
}
