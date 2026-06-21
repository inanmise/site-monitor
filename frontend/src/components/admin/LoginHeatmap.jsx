// Peak login ısı haritası — saf SVG, 7 (hafta-günü) × 24 (saat). MiniChart gibi bağımsız.
// props: matrix [7][24] login sayıları, max (en yoğun hücre), dayLabels (i18n'den 7 kısa ad),
//        title, hourLabel (saat ekseni başlığı), onCellClick(weekday, hour) — hücreye tıklayınca.
// Renk yoğunluğu = count/max.
export default function LoginHeatmap({ matrix = [], max = 0, dayLabels = [], title, hourLabel, onCellClick }) {
  const cell = 16, leftPad = 38, topPad = 6, bottomPad = 18
  const cols = 24, rows = 7
  const W = leftPad + cols * cell + 6
  const H = topPad + rows * cell + bottomPad

  const color = (v) => {
    if (!v || max <= 0) return 'rgba(79,156,249,0.06)'
    const o = 0.18 + 0.82 * (v / max)
    return `rgba(79,156,249,${o.toFixed(3)})`
  }

  return (
    <div className="mini-chart">
      {title && (
        <div className="mini-chart-hdr">
          <span className="mini-chart-lbl">{title}</span>
          <span className="mini-chart-cur mini-chart-na">{hourLabel}</span>
        </div>
      )}
      <svg viewBox={`0 0 ${W} ${H}`} className="mini-chart-svg" role="img" aria-label={title}>
        {/* Gün etiketleri (sol) + satır hücreleri */}
        {Array.from({ length: rows }).map((_, r) => {
          const y = topPad + r * cell
          const row = matrix[r] || []
          return (
            <g key={r}>
              <text x={leftPad - 6} y={y + cell - 5} textAnchor="end"
                fontSize="8" fill="var(--chart-label)">{dayLabels[r] || ''}</text>
              {Array.from({ length: cols }).map((__, c) => {
                const v = Number(row[c] || 0)
                const clickable = onCellClick && v > 0
                return (
                  <rect key={c}
                    x={leftPad + c * cell} y={y}
                    width={cell - 2} height={cell - 2} rx="2"
                    fill={color(v)}
                    style={clickable ? { cursor: 'pointer' } : undefined}
                    onClick={clickable ? () => onCellClick(r, c) : undefined}>
                    <title>{`${dayLabels[r] || ''} ${String(c).padStart(2, '0')}:00 — ${v}`}</title>
                  </rect>
                )
              })}
            </g>
          )
        })}
        {/* Saat etiketleri (alt) — her 3 saatte bir */}
        {Array.from({ length: cols }).map((_, c) => (
          c % 3 === 0 ? (
            <text key={c} x={leftPad + c * cell + (cell - 2) / 2} y={topPad + rows * cell + 11}
              textAnchor="middle" fontSize="7" fill="var(--chart-label)">{c}</text>
          ) : null
        ))}
      </svg>
    </div>
  )
}
