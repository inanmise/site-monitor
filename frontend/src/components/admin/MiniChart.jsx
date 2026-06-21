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

export default function MiniChart({ data = [], color = '#4f9cf9', label, unit = '%', maxY, onClick, gran }) {
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
  const yTicks = [0, 0.25, 0.5, 0.75, 1]

  return (
    <div
      className={`mini-chart${onClick ? ' mini-chart-clickable' : ''}`}
      onClick={onClick}
      title={onClick ? 'Click to expand' : undefined}
    >
      <div className="mini-chart-hdr">
        <span className="mini-chart-lbl">{label}</span>
        {current != null
          ? <span className="mini-chart-cur" style={{ color }}>{current}{unit}</span>
          : <span className="mini-chart-cur mini-chart-na">—</span>
        }
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} className="mini-chart-svg" aria-hidden="true">
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
            fontSize="9" fill="var(--chart-label)">collecting…</text>
        )}
      </svg>
    </div>
  )
}
