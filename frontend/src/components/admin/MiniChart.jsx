import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

/** İhlal rozeti tonları (eski `.mini-chart-breach--*`). */
const BREACH_TONE = {
  ok: 'border-transparent bg-success/15 text-success',
  warn: 'border-transparent bg-amber-500/15 text-amber-700 dark:text-amber-300',
  crit: 'border-transparent bg-destructive/15 text-destructive',
}

// Pure-SVG sparkline chart — no external dependencies.
// props: data [{ts, value}], color, label, unit, maxY, gran ('day'|'hour'|'minute'|undefined)
// ts values come from the backend as UTC (no Z suffix) — add Z before parsing so
// toLocale* converts correctly to the browser's local timezone.
// gran='day': eksen tarih gösterir (kovalar yerel gece yarısı olduğundan saat hep 00:00).
// lastInclusive: son kovanın BİTİŞİNİ göster (saatlik → +59 dk: "23:00" yerine "23:59" → tüm saat olduğu anlaşılır).
const axisLabel = (ts, gran, lastInclusive) => {
  if (!ts) return ''
  let d = new Date(ts + 'Z')
  if (lastInclusive && gran === 'hour') d = new Date(d.getTime() + 59 * 60_000)
  return gran === 'day'
    ? d.toLocaleDateString([], { day: '2-digit', month: '2-digit' })
    : d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

/**
 * thresholds (2026-09-12, #23): { warn, crit } → grafikte kesikli uyarı/kritik bandı + başlıkta "ihlal: N"
 * rozeti (pencere içinde eşiği aşan nokta sayısı). Eşik yoksa görünüm eskisiyle aynı.
 */
export default function MiniChart({ data = [], color = '#4f9cf9', label, unit = '%', maxY, onClick, gran, thresholds = null, breachLabel = null }) {
  const t = useT()
  const W = 400, H = 82
  const PAD = { top: 8, bottom: 20, left: 34, right: 8 }
  const pw = W - PAD.left - PAD.right   // plot width
  const ph = H - PAD.top  - PAD.bottom  // plot height

  const valid   = data.filter(d => d.value != null)
  const values  = valid.map(d => d.value)
  const yMax    = maxY ?? Math.max(...values, 1)
  const current = values[values.length - 1] ?? null

  const toX = i => PAD.left + (data.length < 2 ? pw / 2 : (i / (data.length - 1)) * pw)
  const toY = v => PAD.top  + ph - (Math.min(v, yMax) / yMax) * ph

  const pts  = data.map((d, i) => `${toX(i)},${toY(d.value ?? 0)}`).join(' ')
  const area = data.length > 1
    ? `M${toX(0)},${PAD.top + ph} ` +
      data.map((d, i) => `L${toX(i)},${toY(d.value ?? 0)}`).join(' ') +
      ` L${toX(data.length - 1)},${PAD.top + ph} Z`
    : ''

  const gradId = `cg-${label?.replace(/\W/g, '')}`
  const warn = thresholds?.warn ?? null, crit = thresholds?.crit ?? null
  const critCount = crit != null ? values.filter(v => v >= crit).length : 0
  const warnCount = warn != null ? values.filter(v => v >= warn && (crit == null || v < crit)).length : 0
  const breachTone = critCount > 0 ? 'crit' : warnCount > 0 ? 'warn' : (warn != null || crit != null) ? 'ok' : null
  const yTicks = [0, 0.25, 0.5, 0.75, 1]

  const header = (
    <div className="mb-1 flex w-full items-baseline justify-between gap-2">
      <span className="text-[.78em] font-medium text-muted-foreground">{label}</span>
      {breachTone && (
        <Badge variant="outline" data-breach={breachTone}
          className={cn('mr-2 ml-auto rounded-full px-1.5 py-0 text-[.7em] leading-4 font-bold', BREACH_TONE[breachTone])}
          title={`warn ≥ ${warn ?? '—'}${unit} · crit ≥ ${crit ?? '—'}${unit}`}>
          {breachTone === 'ok' ? '✓' : `${critCount + warnCount}${breachLabel ? ' ' + breachLabel : ''}`}
        </Badge>
      )}
      {current != null
        // renk CSS özel değişkeniyle (--mc): seri rengi tek kaynaktan (color prop'u)
        ? <span data-slot="mini-chart-current" className="text-[.9em] font-bold text-(--mc) tabular-nums" style={{ '--mc': color }}>{current}{unit}</span>
        : <span data-slot="mini-chart-current" className="text-[.9em] font-bold text-muted-foreground">—</span>
      }
    </div>
  )
  const svg = (
      // `size-full h-auto`: sınıfta "size-" geçmesi ŞART — shadcn Button iç SVG'leri aksi halde 16 px'e (size-4) zorlar.
      <svg viewBox={`0 0 ${W} ${H}`} className="block size-full h-auto overflow-visible" aria-hidden="true">
        <defs>
          <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%"   stopColor={color} stopOpacity="0.25" />
            <stop offset="100%" stopColor={color} stopOpacity="0.02" />
          </linearGradient>
        </defs>

        {/* Y-axis grid + labels */}
        {yTicks.map(f => {
          const y   = PAD.top + ph * (1 - f)
          const val = Math.round(yMax * f)
          return (
            <g key={f}>
              <line
                x1={PAD.left} y1={y} x2={W - PAD.right} y2={y}
                stroke="var(--chart-grid)" strokeWidth="0.5" strokeDasharray="2,3"
              />
              <text x={PAD.left - 3} y={y + 3.5} textAnchor="end"
                fontSize="7" fill="var(--chart-label)">{val}</text>
            </g>
          )
        })}

        {/* Eşik bantları (2026-09-12, #23) — yMax içinde kalanlar çizilir */}
        {warn != null && warn <= yMax && <line x1={PAD.left} y1={toY(warn)} x2={W - PAD.right} y2={toY(warn)} stroke="#d97706" strokeWidth="0.8" strokeDasharray="4,3" pointerEvents="none" data-threshold="warn" />}
        {crit != null && crit <= yMax && <line x1={PAD.left} y1={toY(crit)} x2={W - PAD.right} y2={toY(crit)} stroke="#dc2626" strokeWidth="0.8" strokeDasharray="4,3" pointerEvents="none" data-threshold="crit" />}
        {/* Area fill */}
        {area && <path d={area} fill={`url(#${gradId})`} />}

        {/* Line */}
        {data.length > 1 && (
          <polyline
            points={pts} fill="none"
            stroke={color} strokeWidth="1.6"
            strokeLinejoin="round" strokeLinecap="round"
          />
        )}

        {/* Current-value dot */}
        {data.length > 0 && current != null && (
          <circle
            cx={toX(data.length - 1)} cy={toY(current)} r="3"
            fill={color} stroke="var(--chart-dot-bg)" strokeWidth="1.5"
          />
        )}

        {/* X-axis time labels — converted to browser local time */}
        {data.length > 1 && (
          <>
            <text x={PAD.left} y={H - 3} fontSize="7" fill="var(--chart-label)">
              {axisLabel(data[0].ts, gran, false)}
            </text>
            <text x={W - PAD.right} y={H - 3} fontSize="7"
              fill="var(--chart-label)" textAnchor="end">
              {axisLabel(data[data.length - 1].ts, gran, true)}
            </text>
          </>
        )}

        {/* No-data message */}
        {data.length === 0 && (
          <text x={W / 2} y={H / 2} textAnchor="middle"
            fontSize="9" fill="var(--chart-label)">{t('mini.collecting')}</text>
        )}
      </svg>
  )
  const box = 'flex flex-col items-stretch gap-0 rounded-lg border bg-card px-3 pt-2.5 pb-2 text-left shadow-none border-border'
  // Tıklanır grafik = shadcn Button (outline); değilse Card. Legacy `.mini-chart*` sınıfı taşımaz.
  if (!onClick) return <Card data-slot="mini-chart" className={box}>{header}{svg}</Card>
  return (
    <Button type="button" variant="outline" data-slot="mini-chart" onClick={onClick}
      aria-label={`${label} — ${t('mini.expand')}`} title={t('mini.expand')}
      className={cn(box, 'h-auto w-full font-normal whitespace-normal hover:border-primary hover:bg-card hover:shadow-[0_0_0_2px_color-mix(in_srgb,var(--primary)_25%,transparent)]')}>
      {header}{svg}
    </Button>
  )
}
