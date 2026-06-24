import {
  ResponsiveContainer, ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend,
} from 'recharts'
import { useT } from '../../i18n/index.jsx'
import { formatDate } from '../../api/client'

// Kova ISO'su (UTC, Z'siz) → kısa yerel etiket. gün → GG.AA; saat/dakika → SS:dd.
function tickLabel(ts, gran) {
  const d = new Date(ts.endsWith('Z') ? ts : ts + 'Z')
  const p = (n) => String(n).padStart(2, '0')
  if (gran === 'day') return `${p(d.getDate())}.${p(d.getMonth() + 1)}`
  return `${p(d.getHours())}:${p(d.getMinutes())}`
}

function Row({ label, val, color }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16 }}>
      <span style={{ color: 'var(--text-muted)' }}>{label}</span><strong style={{ color }}>{val}</strong>
    </div>
  )
}

function LoginTooltip({ active, payload, t }) {
  if (!active || !payload || !payload.length) return null
  const d = payload[0].payload
  return (
    <div style={{ background: 'var(--bg-card, #fff)', border: '1px solid var(--border)', borderRadius: 8,
      padding: '8px 11px', fontSize: '.82em', lineHeight: 1.7, boxShadow: '0 4px 16px rgba(0,0,0,.12)' }}>
      <div style={{ fontWeight: 700, marginBottom: 4 }}>{formatDate(d.ts)}</div>
      <Row label={t('uact.requests')} val={d.total} color="#2563eb" />
      <Row label={t('uact.success')} val={d.success} color="#16a34a" />
      {d.failed > 0 && <Row label={t('uact.failed')} val={d.failed} color="#dc2626" />}
    </div>
  )
}

/**
 * Login aktivite zaman serisi grafiği (keyword/ping ResponseTimeChart deseni). X = zaman, Y = login
 * adedi; toplam (alan) + başarılı/başarısız (çizgi) → gün içi/günler arası artış-azalış (spike) izlenir.
 * Presentational: buckets + gran prop'ları SystemHealth'in esnek aralık/gün/özel-aralık fetch'inden gelir.
 */
export default function LoginActivityChart({ buckets = [], gran = 'day' }) {
  const t = useT()
  const data = buckets.map(b => ({
    ts: b.ts, label: tickLabel(b.ts, gran),
    total: Number(b.total) || 0, success: Number(b.success) || 0, failed: Number(b.failed) || 0,
  }))
  const tickEvery = Math.max(0, Math.floor(data.length / 10))
  return (
    <ResponsiveContainer width="100%" height={320}>
      <ComposedChart data={data} margin={{ top: 10, right: 12, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
        <XAxis dataKey="label" tick={{ fontSize: 10, fill: 'var(--text-light)' }} stroke="var(--border)"
          interval={tickEvery} minTickGap={14} />
        <YAxis allowDecimals={false} tick={{ fontSize: 10, fill: 'var(--text-light)' }} stroke="var(--border)" width={36} />
        <Tooltip content={<LoginTooltip t={t} />} />
        <Legend wrapperStyle={{ fontSize: '.78em' }} />
        <Area type="monotone" dataKey="total" name={t('uact.requests')}
          fill="#bfdbfe" fillOpacity={0.5} stroke="#2563eb" strokeWidth={2} isAnimationActive={false} />
        <Line type="monotone" dataKey="success" name={t('uact.success')}
          stroke="#16a34a" strokeWidth={1.5} dot={false} isAnimationActive={false} />
        <Line type="monotone" dataKey="failed" name={t('uact.failed')}
          stroke="#dc2626" strokeWidth={1.5} dot={false} isAnimationActive={false} />
      </ComposedChart>
    </ResponsiveContainer>
  )
}
