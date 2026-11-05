import { MoveRight, TrendingDown, TrendingUp } from 'lucide-react'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { cn } from '@/lib/utils'
import { deltaText, sparkGeometry, valueTrendOf } from './valueTrend.js'

const ICON = { up: TrendingUp, down: TrendingDown, flat: MoveRight }
const W = 100
const H = 24

/**
 * Kartta DEĞER EĞİLİMİ (2026-11-05, dbq2 C1): son 24 saatin ölçülen değerleri mini grafik + yön oku + yüzde değişim.
 * NÖTR renk — eğilim iyi / kötü yorumu yapmaz (ihlal durumu rozettedir). Alan yoksa hiçbir şey çizilmez (eski sunucu).
 * Ekran okuyucu tek cümle duyar ("Son 24 saatte değer eğilimi: artış, +%12,5"); grafik ve ok `aria-hidden`.
 *
 * <p>`variant`: `spark` (mini grafik + ok + yüzde — zengin kartın değer kutusu) ya da `inline` (yalnız ok + yüzde —
 * kompakt kart özeti). Test kancaları: `data-slot="dbq-value-trend"` (+ `data-direction`, `data-variant`), mini grafik
 * `dbq-value-trend-spark`, yüzde `dbq-value-trend-delta`.
 */
export default function ValueTrend({ monitor, variant = 'spark', className }) {
  const t = useT()
  const { lang } = useLanguage()
  const trend = valueTrendOf(monitor)
  if (!trend) return null
  const Icon = trend.direction ? ICON[trend.direction] : null
  const pct = deltaText(trend.delta, lang)
  const dirText = trend.direction ? t(`dbq.card.trendDir.${trend.direction}`) : t('dbq.card.trendDir.unknown')
  const sr = pct ? t('dbq.card.trendSr', dirText, pct) : t('dbq.card.trendSrNoPct', dirText)
  const geo = variant === 'spark' ? sparkGeometry(trend.points, W, H) : null
  return (
    <span data-slot="dbq-value-trend" data-direction={trend.direction || 'none'} data-variant={variant} role="img" aria-label={sr}
      title={t('dbq.card.trendTitle')}
      className={cn('flex min-w-0 items-center gap-1.5 text-muted-foreground', variant === 'spark' ? 'mt-1.5' : 'inline-flex', className)}>
      {geo && (
        <svg data-slot="dbq-value-trend-spark" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" aria-hidden="true" focusable="false"
          className="h-5 min-w-0 flex-1 overflow-visible text-primary/70">
          {geo.segments.map((d, i) => (
            <path key={`s${i}`} d={d} fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" strokeLinecap="round"
              vectorEffect="non-scaling-stroke" />
          ))}
          {geo.dots.map((p, i) => <circle key={`d${i}`} cx={p.x} cy={p.y} r="1.6" fill="currentColor" />)}
        </svg>
      )}
      {(Icon || pct) && (
        <span aria-hidden="true" className="inline-flex shrink-0 items-center gap-0.5 text-[11px] leading-none font-semibold tabular-nums">
          {Icon && <Icon className="size-3.5" />}
          {pct && <span data-slot="dbq-value-trend-delta">{pct}</span>}
        </span>
      )}
    </span>
  )
}
