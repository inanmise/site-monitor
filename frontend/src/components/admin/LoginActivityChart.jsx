import { useT } from '../../i18n/index.jsx'
import { formatDate } from '../../api/client'
import {
  ChartContainer, ChartTooltip, ChartLegend, ChartLegendContent,
  ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid,
} from '@/components/shadcn/chart'

// Kova ISO'su (UTC, Z'siz) → kısa yerel etiket. gün → GG.AA; saat/dakika → SS:dd.
function tickLabel(ts, gran) {
  // ResponseTimeChart:137 kuralı (2026-08 scripted çökme dersi): dış veri ts-string güvencesi
  // olmadan işlenmez — null/number bir kova tüm SystemHealth ekranını ErrorBoundary'ye düşürürdü.
  if (typeof ts !== 'string' || !ts) return ''
  const d = new Date(ts.endsWith('Z') ? ts : ts + 'Z')
  const p = (n) => String(n).padStart(2, '0')
  if (gran === 'day') return `${p(d.getDate())}.${p(d.getMonth() + 1)}`
  return `${p(d.getHours())}:${p(d.getMinutes())}`
}

/** Seri renkleri — önceki grafikle aynı (toplam mavi alan, başarılı yeşil, başarısız kırmızı çizgi). */
const C = { total: '#2563eb', totalFill: '#bfdbfe', success: '#16a34a', failed: '#dc2626' }

function Row({ label, val, color }) {
  return (
    <div className="flex justify-between gap-4">
      <span className="text-muted-foreground">{label}</span>
      {/* renk CSS özel değişkeniyle: seri rengi tek kaynaktan (C) */}
      <strong className="text-(--row) tabular-nums" style={{ '--row': color }}>{val}</strong>
    </div>
  )
}

function LoginTooltip({ active, payload, t }) {
  if (!active || !payload || !payload.length) return null
  const d = payload[0].payload
  return (
    <div className="rounded-lg border bg-card px-[11px] py-2 text-[.82em] leading-[1.7] shadow-lg border-border">
      <div className="mb-1 font-bold">{formatDate(d.ts)}</div>
      <Row label={t('uact.requests')} val={d.total} color={C.total} />
      <Row label={t('uact.success')} val={d.success} color={C.success} />
      {d.failed > 0 && <Row label={t('uact.failed')} val={d.failed} color={C.failed} />}
    </div>
  )
}

/**
 * Login aktivite zaman serisi grafiği (keyword/ping ResponseTimeChart deseni). X = zaman, Y = login
 * adedi; toplam (alan) + başarılı/başarısız (çizgi) → gün içi/günler arası artış-azalış (spike) izlenir.
 * Presentational: buckets + gran prop'ları SystemHealth'in esnek aralık/gün/özel-aralık fetch'inden gelir.
 * Çizim shadcn Chart (ChartContainer + ChartLegend); özel ipucu kutusu (üç satır, tarih başlığı) korunur.
 */
export default function LoginActivityChart({ buckets = [], gran = 'day' }) {
  const t = useT()
  const data = buckets.filter(b => typeof b?.ts === 'string' && b.ts).map(b => ({
    ts: b.ts, label: tickLabel(b.ts, gran),
    total: Number(b.total) || 0, success: Number(b.success) || 0, failed: Number(b.failed) || 0,
  }))
  const tickEvery = Math.max(0, Math.floor(data.length / 10))
  const config = {
    total: { label: t('uact.requests'), color: C.total },
    success: { label: t('uact.success'), color: C.success },
    failed: { label: t('uact.failed'), color: C.failed },
  }
  return (
    <ChartContainer config={config} className="aspect-auto h-[320px] w-full">
      <ComposedChart data={data} margin={{ top: 10, right: 12, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" vertical={false} />
        <XAxis dataKey="label" tick={{ fontSize: 10 }} tickLine={false} axisLine={false}
          interval={tickEvery} minTickGap={14} />
        <YAxis allowDecimals={false} tick={{ fontSize: 10 }} tickLine={false} axisLine={false} width={36} />
        <ChartTooltip content={<LoginTooltip t={t} />} />
        <ChartLegend content={<ChartLegendContent />} />
        <Area type="monotone" dataKey="total" name={t('uact.requests')}
          fill={C.totalFill} fillOpacity={0.5} stroke={C.total} strokeWidth={2} isAnimationActive={false} />
        <Line type="monotone" dataKey="success" name={t('uact.success')}
          stroke={C.success} strokeWidth={1.5} dot={false} isAnimationActive={false} />
        <Line type="monotone" dataKey="failed" name={t('uact.failed')}
          stroke={C.failed} strokeWidth={1.5} dot={false} isAnimationActive={false} />
      </ComposedChart>
    </ChartContainer>
  )
}
