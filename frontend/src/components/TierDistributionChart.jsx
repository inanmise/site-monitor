import { useState } from 'react'
import { useT } from '../i18n/index.jsx'

const TIER_GROUPS = [1, 2, 3, 4, null]

const TIER_COLORS = {
  1:    '#4f46e5',
  2:    '#0284c7',
  3:    '#0891b2',
  4:    '#6b7280',
  null: '#94a3b8',
}

const TIER_DESC_KEYS = {
  1:    'tier.desc1',
  2:    'tier.desc2',
  3:    'tier.desc3',
  4:    'tier.desc4',
  null: 'tier.descNone',
}

const CX = 90, CY = 90, R_OUT = 72, R_IN = 44
const GAP_DEG = 2.5
const PUSH_HOVER  = 4
const PUSH_ACTIVE = 7

// angle: 0 = top (12 o'clock), clockwise
function polarXY(cx, cy, r, deg) {
  const rad = (deg - 90) * Math.PI / 180
  return { x: cx + r * Math.cos(rad), y: cy + r * Math.sin(rad) }
}

function donutPath(cx, cy, rOut, rIn, startDeg, endDeg) {
  const s = startDeg + GAP_DEG / 2
  const e = endDeg   - GAP_DEG / 2
  if (e - s < 1) return null
  const p1 = polarXY(cx, cy, rOut, s)
  const p2 = polarXY(cx, cy, rOut, e)
  const p3 = polarXY(cx, cy, rIn,  e)
  const p4 = polarXY(cx, cy, rIn,  s)
  const large = (e - s) > 180 ? 1 : 0
  const f = n => n.toFixed(2)
  return [
    `M ${f(p1.x)} ${f(p1.y)}`,
    `A ${rOut} ${rOut} 0 ${large} 1 ${f(p2.x)} ${f(p2.y)}`,
    `L ${f(p3.x)} ${f(p3.y)}`,
    `A ${rIn} ${rIn} 0 ${large} 0 ${f(p4.x)} ${f(p4.y)}`,
    'Z',
  ].join(' ')
}

export default function TierDistributionChart({ certs, visible, onTierClick, activeTier }) {
  const t = useT()
  const [hovered, setHovered] = useState(null)

  if (!visible || !certs?.length) return null

  const groups = TIER_GROUPS.map(tier => {
    const group = certs.filter(c => (c.tier ?? null) === tier)
    if (group.length === 0) return null
    const error   = group.filter(c => c.status === 'error').length
    const warning = group.filter(c => c.warning && c.status !== 'error').length
    const valid   = group.length - error - warning
    return { tier, total: group.length, valid, warning, error, filterKey: tier ?? 0 }
  }).filter(Boolean)

  if (groups.length === 0) return null

  const totalCerts = groups.reduce((s, g) => s + g.total, 0)

  let angle = 0
  const segments = groups.map(g => {
    const sweep = (g.total / totalCerts) * 360
    const seg = { ...g, startDeg: angle, endDeg: angle + sweep }
    angle += sweep
    return seg
  })

  function handleClick(filterKey) {
    onTierClick?.(activeTier === filterKey ? null : filterKey)
  }

  const focusKey = hovered !== null ? hovered : activeTier
  const focusGroup = focusKey !== null ? groups.find(g => g.filterKey === focusKey) : null

  return (
    <div className="tier-chart">
      <div className="tier-chart-title">{t('tier.chartTitle')}</div>
      <div className="tier-pie-layout">

        {/* ── SVG Donut ── */}
        <div className="tier-pie-wrap">
          <svg width="90" height="90" viewBox="0 0 180 180" role="img">
            {segments.map(seg => {
              const isActive  = activeTier === seg.filterKey
              const isHovered = hovered   === seg.filterKey
              const path = donutPath(CX, CY, R_OUT, R_IN, seg.startDeg, seg.endDeg)
              if (!path) return null

              const midDeg = (seg.startDeg + seg.endDeg) / 2
              const midRad = (midDeg - 90) * Math.PI / 180
              const push   = isActive ? PUSH_ACTIVE : isHovered ? PUSH_HOVER : 0
              const tx = (Math.cos(midRad) * push).toFixed(2)
              const ty = (Math.sin(midRad) * push).toFixed(2)
              const dimmed = activeTier !== null && !isActive

              return (
                <path
                  key={seg.filterKey}
                  d={path}
                  fill={TIER_COLORS[seg.tier]}
                  style={{
                    transform: `translate(${tx}px, ${ty}px)`,
                    transition: 'transform .18s ease, opacity .2s',
                    opacity: dimmed ? 0.28 : 1,
                    cursor: 'pointer',
                  }}
                  onClick={() => handleClick(seg.filterKey)}
                  onMouseEnter={() => setHovered(seg.filterKey)}
                  onMouseLeave={() => setHovered(null)}
                >
                  <title>
                    {seg.tier ? `T${seg.tier} — ` : '? — '}
                    {t(TIER_DESC_KEYS[seg.tier])}: {seg.total}
                  </title>
                </path>
              )
            })}

            {/* center number */}
            <text
              x={CX} y={CY - 6}
              textAnchor="middle"
              fontSize="26" fontWeight="700"
              style={{ fill: 'var(--text)', fontFamily: 'inherit', pointerEvents: 'none' }}
            >
              {focusGroup ? focusGroup.total : totalCerts}
            </text>
            <text
              x={CX} y={CY + 12}
              textAnchor="middle"
              fontSize="10"
              style={{ fill: 'var(--text-light)', fontFamily: 'inherit', pointerEvents: 'none' }}
            >
              {focusGroup
                ? (focusGroup.tier ? `T${focusGroup.tier}` : '?')
                : t('tier.centerTotal')}
            </text>
          </svg>
        </div>

        {/* ── Legend ── */}
        <div className="tier-pie-legend">
          {groups.map(g => {
            const isActive = activeTier === g.filterKey
            return (
              <div
                key={g.filterKey}
                className={`tier-legend-row${isActive ? ' tier-legend-row-active' : ''}`}
                onClick={() => handleClick(g.filterKey)}
                onMouseEnter={() => setHovered(g.filterKey)}
                onMouseLeave={() => setHovered(null)}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && handleClick(g.filterKey)}
              >
                <div className="tier-legend-swatch" style={{ background: TIER_COLORS[g.tier] }} />
                <div className="tier-legend-info">
                  <div className="tier-legend-name">
                    {g.tier
                      ? <span className={`tier-badge tier-badge-${g.tier}`}>T{g.tier}</span>
                      : <span className="tier-badge-none">?</span>}
                    <span className="tier-legend-desc">{t(TIER_DESC_KEYS[g.tier])}</span>
                  </div>
                  <div className="tier-legend-counts">
                    {g.valid   > 0 && <span className="tc-valid">{g.valid}</span>}
                    {g.warning > 0 && <span className="tc-warning">{g.warning}</span>}
                    {g.error   > 0 && <span className="tc-error">{g.error}</span>}
                    <span className="tier-legend-total">{g.total}</span>
                  </div>
                </div>
              </div>
            )
          })}
          <div className="tier-chart-hint">{t('tier.clickHint')}</div>
        </div>

      </div>
    </div>
  )
}
