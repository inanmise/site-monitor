import { useId, useMemo } from 'react'
import { BarChart3 } from 'lucide-react'
import { useT, useDateLocale } from '../../../../i18n/index.jsx'
import StatusBlock from '../../../ui/StatusBlock.jsx'
import { Card } from '@/components/shadcn/card'
import { ChartContainer, ChartTooltip, BarChart, Bar, XAxis, YAxis, CartesianGrid } from '@/components/shadcn/chart'
import { CHANNEL_COLOR, activeSeries, channelLabel, chartPoints, fmtNum } from './loginStatsModel.js'
import { InfoHint } from './StatsParts.jsx'

/** Seri anahtarı (kare). */
function Key({ color }) {
  return (
    <svg viewBox="0 0 10 10" aria-hidden="true" focusable="false" className="size-auto h-2.5 w-2.5 shrink-0">
      <rect width="10" height="10" rx="2" fill={color} />
    </svg>
  )
}

/** İpucu: kovanın tam tarihi + seri değerleri + toplam başarılı. */
function Tip({ active, payload, keys, labelOf, locale }) {
  if (!active || !Array.isArray(payload) || !payload.length) return null
  const p = payload.find((x) => x?.payload)?.payload
  if (!p) return null
  return (
    <div data-slot="chart-tooltip" className="grid w-max max-w-[16rem] min-w-[11rem] gap-1 rounded-lg border border-border/60 bg-background px-3 py-2 text-xs shadow-xl">
      <div className="font-semibold text-foreground">{p.full}</div>
      {keys.map((k) => (
        <div key={k} className="flex items-center gap-2">
          <Key color={CHANNEL_COLOR[k]} />
          <span className="min-w-0 flex-1 truncate text-muted-foreground">{labelOf(k)}</span>
          <span className="font-semibold tabular-nums">{fmtNum(p[k], locale)}</span>
        </div>
      ))}
    </div>
  )
}

/**
 * Giriş trendi (2026-10-03) — projenin shadcn Chart'ı (recharts): kova başına kanal başarıları YIĞILMIŞ çubuk + yanında
 * başarısız denemeler (ayrı çubuk). 24 saatte saatlik, diğer dönemlerde günlük (İstanbul). BOZUK nokta düşer
 * (`chartPoints`), grafik çökmez; hiç veri yoksa boş durum. Erişilebilirlik: görünür özet cümlesi + anahtar listesi.
 * Test kancaları: `data-slot="lm-trend"`, `lm-trend-summary`, boşta `lm-trend-empty`.
 */
export default function LoginTrendChart({ series, granularity, totals }) {
  const t = useT()
  const locale = useDateLocale()
  const uid = useId().replace(/:/g, '')
  const points = useMemo(() => chartPoints(series, granularity, locale), [series, granularity, locale])
  const keys = useMemo(() => activeSeries(points), [points])
  const labelOf = (k) => (k === 'failed' ? t('lm.stats.trend.failed') : channelLabel(k, t))
  const empty = points.length === 0 || points.every((p) => p.success === 0 && p.failed === 0)
  const config = Object.fromEntries(keys.map((k) => [k, { label: labelOf(k), color: CHANNEL_COLOR[k] }]))
  const successKeys = keys.filter((k) => k !== 'failed')
  const maxY = Math.max(1, ...points.map((p) => Math.max(p.success, p.failed)))
  const yWidth = Math.max(28, String(maxY).length * 7 + 12)
  return (
    <Card data-slot="lm-trend" className="min-w-0 gap-3 px-3.5 py-4 shadow-xs sm:px-5">
      <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
        <h4 id={`lm-trend-title-${uid}`} className="m-0 text-sm font-semibold">{t('lm.stats.trend.title')}</h4>
        <InfoHint text={t('lm.stats.trend.def')} label={t('lm.stats.defLabel', t('lm.stats.trend.title'))} />
      </div>
      {empty ? (
        <div data-slot="lm-trend-empty"><StatusBlock tone="neutral" icon={BarChart3} title={t('lm.stats.trend.empty')} /></div>
      ) : (
        <figure aria-labelledby={`lm-trend-title-${uid}`} aria-describedby={`lm-trend-sum-${uid}`} className="m-0 flex min-w-0 flex-col gap-2">
          <p id={`lm-trend-sum-${uid}`} data-slot="lm-trend-summary" className="m-0 text-xs text-muted-foreground">
            {t('lm.stats.trend.summary', fmtNum(totals?.success ?? 0, locale), fmtNum(totals?.failed ?? 0, locale))}
          </p>
          <ul className="m-0 flex list-none flex-wrap gap-x-3 gap-y-1 p-0 text-xs" data-slot="lm-trend-legend">
            {keys.map((k) => (
              <li key={k} data-series={k} className="inline-flex items-center gap-1.5"><Key color={CHANNEL_COLOR[k]} />{labelOf(k)}</li>
            ))}
          </ul>
          <ChartContainer config={config} className="aspect-auto h-[200px] w-full sm:h-[240px]">
            <BarChart data={points} margin={{ top: 8, right: 8, left: 0, bottom: 0 }} barCategoryGap="18%" accessibilityLayer>
              <CartesianGrid vertical={false} strokeDasharray="3 3" />
              <XAxis dataKey="label" tickLine={false} axisLine={false} minTickGap={14} tickMargin={6} tick={{ fontSize: 11 }} />
              <YAxis allowDecimals={false} tickLine={false} axisLine={false} width={yWidth} tick={{ fontSize: 11 }} />
              <ChartTooltip isAnimationActive={false} cursor={{ fill: 'var(--muted)', fillOpacity: 0.6 }}
                content={<Tip keys={keys} labelOf={labelOf} locale={locale} />} />
              {successKeys.map((k, i) => (
                <Bar key={k} dataKey={k} stackId="ok" fill={`var(--color-${k})`} maxBarSize={30} isAnimationActive={false}
                  radius={i === successKeys.length - 1 ? [3, 3, 0, 0] : undefined} />
              ))}
              <Bar dataKey="failed" fill="var(--color-failed)" maxBarSize={30} radius={[3, 3, 0, 0]} isAnimationActive={false} />
            </BarChart>
          </ChartContainer>
        </figure>
      )}
    </Card>
  )
}
