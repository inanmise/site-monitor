import { useT } from '../../i18n/index.jsx'
import { trendRows } from './dataQualityModel.js'
import {
  ChartContainer, ChartTooltip, AreaChart, Area, XAxis, YAxis, CartesianGrid,
} from '@/components/shadcn/chart'

function TrendTip({ active, payload, t }) {
  if (!active || !payload?.length) return null
  const p = payload[0]?.payload || {}
  return (
    <div className="rounded-lg border bg-popover px-2.5 py-1.5 text-xs text-popover-foreground shadow-lg">
      <div className="font-semibold">{p.label}</div>
      <div className="tabular-nums">{t('dq.trendPoint', p.score)}</div>
    </div>
  )
}

/**
 * 30 günlük puan eğilimi (shadcn Chart + recharts Area). Günlük görüntü tablosundan; ikiden az nokta varsa grafik
 * yerine kısa bir açıklama (ilk nokta dağıtımdan sonraki ilk saatte, ikincisi ertesi gün oluşur). Grafik süs değil:
 * erişilebilir özet `aria-label`'da (ilk → son puan).
 */
export default function DataQualityTrend({ trend, className }) {
  const t = useT()
  const rows = trendRows(trend)
  if (rows.length < 2) {
    return <p data-slot="dq-trend-empty" className="text-xs text-muted-foreground">{t('dq.trendEmpty')}</p>
  }
  const first = rows[0]
  const last = rows[rows.length - 1]
  return (
    <ChartContainer config={{ score: { label: t('dq.scoreLabel'), color: 'var(--primary)' } }}
      data-slot="dq-trend" role="img" aria-label={t('dq.trendAria', rows.length, first.score, last.score)}
      className={className ?? 'aspect-auto h-28 w-full'}>
      <AreaChart data={rows} margin={{ top: 6, right: 6, bottom: 0, left: 0 }}>
        <CartesianGrid vertical={false} strokeDasharray="3 3" />
        <XAxis dataKey="label" tickLine={false} axisLine={false} minTickGap={24} fontSize={11} />
        <YAxis domain={[0, 100]} ticks={[0, 50, 100]} tickLine={false} axisLine={false} width={28} fontSize={11} />
        <ChartTooltip cursor={false} content={<TrendTip t={t} />} />
        <Area type="monotone" dataKey="score" stroke="var(--color-score)" fill="var(--color-score)" fillOpacity={0.15}
          strokeWidth={2} dot={false} isAnimationActive={false} />
      </AreaChart>
    </ChartContainer>
  )
}
