import { useState } from 'react'
import { LineChart as LineChartIcon } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { Card } from '@/components/shadcn/card'
import {
  ChartContainer, ChartTooltip, ChartTooltipContent,
  AreaChart, Area, XAxis, YAxis, CartesianGrid,
} from '@/components/shadcn/chart'

const RANGES = [
  { key: '15m',  labelKey: 'chart.range15m',  minutes: 15   },
  { key: '1h',   labelKey: 'chart.range1h',   minutes: 60   },
  { key: '6h',   labelKey: 'chart.range6h',   minutes: 360  },
  { key: '24h',  labelKey: 'chart.range24h',  minutes: 1440 },
]

// gran: 'day' → tarih ekseni (kovalar yerel gece yarısı); 'hour'/'minute'/undefined → saat ekseni.
const hm = d => d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })

// Eksen etiketi (kova başlangıcı).
const axisLabel = (ts, gran) => {
  if (!ts) return ''
  const d = new Date(ts + 'Z')
  return gran === 'day'
    ? d.toLocaleDateString([], { day: '2-digit', month: '2-digit' })
    : hm(d)
}

// Son kova bitişi: saatlik → +59 dk (23:00 → 23:59, tüm saat olduğu anlaşılır).
const endLabel = (ts, gran) => {
  if (!ts) return ''
  if (gran === 'hour') return hm(new Date(new Date(ts + 'Z').getTime() + 59 * 60_000))
  return axisLabel(ts, gran)
}

// Tooltip: saatlik → "23:00 – 23:59" aralığı; günlük → tarih; diğer → tarih+saat.
const tipLabel = (ts, gran) => {
  if (!ts) return ''
  const d = new Date(ts + 'Z')
  if (gran === 'hour') return `${hm(d)} – ${hm(new Date(d.getTime() + 59 * 60_000))}`
  if (gran === 'day')  return d.toLocaleDateString([], { weekday: 'short', day: '2-digit', month: '2-digit' })
  return d.toLocaleString([], { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

// ── Büyük grafik — shadcn Chart (ChartContainer + recharts AreaChart) ────────────
function BigChart({ data, color, unit, maxY, gran, label }) {
  const t = useT()
  const config = { value: { label: label || t('chart.value'), color } }
  const rows = data.map((d) => ({ ts: d.ts, value: d.value ?? null }))
  const gradId = `bgrad-${String(color).replace(/[^a-zA-Z0-9]/g, '')}`
  return (
    <ChartContainer config={config} className="aspect-auto h-[220px] w-full sm:h-[260px]">
      <AreaChart data={rows} margin={{ top: 12, right: 12, left: 0, bottom: 0 }}>
        <defs>
          <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={color} stopOpacity={0.3} />
            <stop offset="100%" stopColor={color} stopOpacity={0.02} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="3 4" vertical={false} />
        <XAxis dataKey="ts" tickFormatter={(v) => axisLabel(v, gran)} tickLine={false} axisLine={false}
          minTickGap={24} tick={{ fontSize: 10 }} />
        <YAxis domain={[0, maxY ?? 'auto']} allowDecimals={false} tickLine={false} axisLine={false} width={40} tick={{ fontSize: 10 }} />
        <ChartTooltip content={<ChartTooltipContent
          labelFormatter={(_, p) => tipLabel(p?.[0]?.payload?.ts, gran)}
          formatter={(v) => <span className="font-mono font-medium tabular-nums">{v ?? '—'}{unit}</span>} />} />
        <Area type="monotone" dataKey="value" stroke={color} strokeWidth={2} fill={`url(#${gradId})`}
          isAnimationActive={false} connectNulls />
      </AreaChart>
    </ChartContainer>
  )
}

// ── Modal ─────────────────────────────────────────────────────────────────────
/**
 * Mini grafik → büyük görünüm. Çizim shadcn: ModalShell (Dialog; Escape/odak/scrim kabukta), aralık
 * SegmentedControl, istatistik Card'ları, grafik ChartContainer.
 */
export default function ChartModal({ chart, onClose }) {
  const t = useT()
  const [range, setRange] = useState('6h')

  if (!chart) return null

  const { label, unit, color, maxY, data, gran } = chart
  // gran set ise (Giriş Trendi grafikleri) aralık paneldeki kontrollerle yönetilir → modal'da
  // 15dk/1s/6s/24s aralık butonları gösterme, gelen tüm aralığı çiz. Yalnız legacy zaman serileri
  // (http/cpu — gran yok) intraday filtre + aralık butonları kullanır.
  const showRange = !gran
  const rangeMs  = (RANGES.find(r => r.key === range)?.minutes ?? Infinity) * 60_000
  const cutoff   = Date.now() - rangeMs
  const filtered = data.filter(d => d.ts && new Date(d.ts + 'Z').getTime() >= cutoff)
  const display  = showRange ? (filtered.length > 0 ? filtered : data) : data

  // Stats for selected range
  const vals    = display.map(d => d.value).filter(v => v != null)
  const current = vals[vals.length - 1] ?? null
  const minVal  = vals.length ? Math.min(...vals) : null
  const maxVal  = vals.length ? Math.max(...vals) : null
  const avgVal  = vals.length ? Math.round(vals.reduce((a, b) => a + b, 0) / vals.length * 10) / 10 : null

  const stat = (key, lbl, val, colored) => (
    <Card key={key} data-stat={key} className="min-w-0 flex-[1_1_96px] items-center gap-0.5 px-3 py-2 shadow-none">
      {/* renk CSS özel değişkeniyle (--sc): seri rengi tek kaynaktan */}
      <span className={colored ? 'text-lg font-bold text-(--sc) tabular-nums' : 'text-lg font-bold tabular-nums'}
        style={colored ? { '--sc': color } : undefined}>{val}</span>
      <span className="text-[0.72em] font-semibold tracking-wide text-muted-foreground uppercase">{lbl}</span>
    </Card>
  )

  return (
    <ModalShell open onClose={onClose} title={label} icon={LineChartIcon} size="lg" scrollBody>
      {/* Aralık düğmeleri — yalnız legacy zaman serileri (http/cpu). Trend grafiklerinde panel kontrol eder. */}
      {showRange && (
        <div className="mb-3">
          <SegmentedControl value={range} onChange={setRange} ariaLabel={t('sml.rangeLabel')}
            options={RANGES.map((r) => ({ value: r.key, label: t(r.labelKey) }))} />
        </div>
      )}

      {/* İstatistik şeridi */}
      <div className="mb-3 flex flex-wrap gap-2">
        {stat('current', t('chart.statCurrent'), current != null ? `${current}${unit}` : '—', true)}
        {stat('min', t('chart.statMin'), minVal != null ? `${minVal}${unit}` : '—', true)}
        {stat('avg', t('chart.statAvg'), avgVal != null ? `${avgVal}${unit}` : '—', true)}
        {stat('max', t('chart.statMax'), maxVal != null ? `${maxVal}${unit}` : '—', true)}
        {stat('points', t('chart.statPoints'), display.length, false)}
      </div>

      <BigChart data={display} color={color} unit={unit} maxY={maxY} gran={gran} label={label} />

      {/* Zaman aralığı etiketi — son kova bitiş-dahil (saatlik → 23:59) */}
      {display.length > 1 && (
        <p className="mt-2 text-center text-xs text-muted-foreground tabular-nums">
          {axisLabel(display[0].ts, gran)} — {endLabel(display[display.length - 1].ts, gran)}
        </p>
      )}
    </ModalShell>
  )
}
