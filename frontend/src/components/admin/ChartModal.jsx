import { useState, useEffect, useCallback } from 'react'
import { useT } from '../../i18n/index.jsx'

const RANGES = [
  { key: '15m',  labelKey: 'chart.range15m',  minutes: 15   },
  { key: '1h',   labelKey: 'chart.range1h',   minutes: 60   },
  { key: '6h',   labelKey: 'chart.range6h',   minutes: 360  },
  { key: '24h',  labelKey: 'chart.range24h',  minutes: 1440 },
]

const localHHMM = ts =>
  ts ? new Date(ts + 'Z').toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : ''

const localFull = ts =>
  ts ? new Date(ts + 'Z').toLocaleString([], {
    month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  }) : ''

// ── Big SVG chart ─────────────────────────────────────────────────────────────
function BigChart({ data, color, unit, maxY }) {
  const [hovered, setHovered] = useState(null)

  const W = 800, H = 200
  const PAD = { top: 15, bottom: 28, left: 48, right: 16 }
  const pw = W - PAD.left - PAD.right
  const ph = H - PAD.top  - PAD.bottom

  const vals   = data.map(d => d.value ?? 0)
  const yMax   = maxY ?? Math.max(...vals, 1)
  const gradId = `bgrad-${color.replace('#', '')}`

  const toX = i => PAD.left + (data.length < 2 ? pw / 2 : (i / (data.length - 1)) * pw)
  const toY = v => PAD.top  + ph - (Math.min(v, yMax) / yMax) * ph

  const pts  = data.map((d, i) => `${toX(i)},${toY(d.value ?? 0)}`).join(' ')
  const area = data.length > 1
    ? `M${toX(0)},${PAD.top + ph} ` +
      data.map((d, i) => `L${toX(i)},${toY(d.value ?? 0)}`).join(' ') +
      ` L${toX(data.length - 1)},${PAD.top + ph} Z`
    : ''

  // Y ticks
  const yTicks = [0, 0.2, 0.4, 0.6, 0.8, 1]

  // X labels — up to 8 evenly spaced
  const xLabelCount = Math.min(8, data.length)
  const xIndices = xLabelCount < 2 ? [] : Array.from({ length: xLabelCount }, (_, i) =>
    Math.round(i * (data.length - 1) / (xLabelCount - 1))
  )

  // Mouse tracking for tooltip
  const handleMouseMove = useCallback(e => {
    if (data.length < 2) return
    const svgEl = e.currentTarget
    const rect  = svgEl.getBoundingClientRect()
    const mx    = (e.clientX - rect.left) / rect.width * W
    const idx   = Math.max(0, Math.min(data.length - 1,
      Math.round((mx - PAD.left) / pw * (data.length - 1))
    ))
    setHovered({ idx, d: data[idx] })
  }, [data, pw])

  const hx = hovered ? toX(hovered.idx) : null
  const hy = hovered ? toY(hovered.d?.value ?? 0) : null

  return (
    <div style={{ position: 'relative' }}>
      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="big-chart-svg"
        onMouseMove={handleMouseMove}
        onMouseLeave={() => setHovered(null)}
      >
        <defs>
          <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%"   stopColor={color} stopOpacity="0.3" />
            <stop offset="100%" stopColor={color} stopOpacity="0.02" />
          </linearGradient>
        </defs>

        {/* Y grid + labels */}
        {yTicks.map(f => {
          const y   = PAD.top + ph * (1 - f)
          const val = Math.round(yMax * f)
          return (
            <g key={f}>
              <line x1={PAD.left} y1={y} x2={W - PAD.right} y2={y}
                stroke="var(--chart-grid)" strokeWidth="0.6" strokeDasharray="3,4" />
              <text x={PAD.left - 5} y={y + 4} textAnchor="end"
                fontSize="9" fill="var(--chart-label)">{val}</text>
            </g>
          )
        })}

        {/* Area fill */}
        {area && <path d={area} fill={`url(#${gradId})`} />}

        {/* Line */}
        {data.length > 1 && (
          <polyline points={pts} fill="none" stroke={color}
            strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
        )}

        {/* X labels */}
        {xIndices.map(i => (
          <text key={i} x={toX(i)} y={H - 6} textAnchor="middle"
            fontSize="8.5" fill="var(--chart-label)">
            {localHHMM(data[i]?.ts)}
          </text>
        ))}

        {/* Hover crosshair */}
        {hovered && (
          <g>
            <line x1={hx} y1={PAD.top} x2={hx} y2={PAD.top + ph}
              stroke={color} strokeWidth="1" strokeDasharray="3,3" opacity="0.6" />
            <circle cx={hx} cy={hy} r="4" fill={color}
              stroke="var(--chart-dot-bg)" strokeWidth="2" />
          </g>
        )}
      </svg>

      {/* Tooltip */}
      {hovered && hovered.d && (
        <div className="chart-tooltip">
          <strong>{localFull(hovered.d.ts)}</strong>
          <span>{hovered.d.value ?? '—'}{unit}</span>
        </div>
      )}
    </div>
  )
}

// ── Modal ─────────────────────────────────────────────────────────────────────
export default function ChartModal({ chart, onClose }) {
  const t = useT()
  const [range, setRange] = useState('6h')

  // Close on Escape
  useEffect(() => {
    const onKey = e => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  if (!chart) return null

  const { label, unit, color, maxY, data } = chart

  // Filter data to selected range
  const rangeMs  = (RANGES.find(r => r.key === range)?.minutes ?? Infinity) * 60_000
  const cutoff   = Date.now() - rangeMs
  const filtered = data.filter(d => d.ts && new Date(d.ts + 'Z').getTime() >= cutoff)
  const display  = filtered.length > 0 ? filtered : data

  // Stats for selected range
  const vals    = display.map(d => d.value).filter(v => v != null)
  const current = vals[vals.length - 1] ?? null
  const minVal  = vals.length ? Math.min(...vals) : null
  const maxVal  = vals.length ? Math.max(...vals) : null
  const avgVal  = vals.length ? Math.round(vals.reduce((a, b) => a + b, 0) / vals.length * 10) / 10 : null

  return (
    <div className="chart-modal-overlay" onClick={e => { if (e.target === e.currentTarget) onClose() }}>
      <div className="chart-modal" role="dialog" aria-modal="true">

        {/* Header */}
        <div className="chart-modal-hdr">
          <h2 className="chart-modal-title">{label}</h2>
          <button className="chart-modal-close" onClick={onClose} aria-label="Kapat">✕</button>
        </div>

        {/* Time range buttons */}
        <div className="chart-range-bar">
          {RANGES.map(r => (
            <button
              key={r.key}
              className={`chart-range-btn ${range === r.key ? 'chart-range-btn-active' : ''}`}
              onClick={() => setRange(r.key)}
            >
              {t(r.labelKey)}
            </button>
          ))}
        </div>

        {/* Stats row */}
        <div className="chart-stats-row">
          {[
            { lbl: t('chart.statCurrent'), val: current },
            { lbl: t('chart.statMin'),     val: minVal  },
            { lbl: t('chart.statAvg'),     val: avgVal  },
            { lbl: t('chart.statMax'),     val: maxVal  },
          ].map(({ lbl, val }) => (
            <div key={lbl} className="chart-stat-pill">
              <span className="chart-stat-pill-val" style={{ color }}>
                {val != null ? `${val}${unit}` : '—'}
              </span>
              <span className="chart-stat-pill-lbl">{lbl}</span>
            </div>
          ))}
          <div className="chart-stat-pill">
            <span className="chart-stat-pill-val">{display.length}</span>
            <span className="chart-stat-pill-lbl">{t('chart.statPoints')}</span>
          </div>
        </div>

        {/* Big chart */}
        <BigChart data={display} color={color} unit={unit} maxY={maxY} />

        {/* Time span label */}
        {display.length > 1 && (
          <p className="chart-timespan">
            {localFull(display[0].ts)} — {localFull(display[display.length - 1].ts)}
          </p>
        )}
      </div>
    </div>
  )
}
