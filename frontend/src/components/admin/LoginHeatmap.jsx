// Peak login ısı haritası — saf SVG, 7 (hafta-günü) × 24 (saat). MiniChart gibi bağımsız.
// props: matrix [7][24] login sayıları, failed [7][24] başarısız login sayıları, max (en yoğun hücre),
//        dayLabels (7 kısa ad), title, hourLabel, onCellClick(weekday, hour),
//        todayDow (bugünün hafta-günü 0..6, yoksa -1 → satır vurgusu),
//        rowTotals [7] (gün toplamları — sağda), colTotals [24] (saat toplamları — altta), total (hafta).
// Renk yoğunluğu = count/max. Hücrede başarısız login varsa KIRMIZI, yoksa mavi.
export default function LoginHeatmap({
  matrix = [], failed = [], max = 0, dayLabels = [], title, hourLabel, onCellClick,
  todayDow = -1, rowTotals = [], colTotals = [], total = 0,
}) {
  const cell = 16, leftPad = 38, topPad = 6, rightPad = 30, bottomPad = 30
  const cols = 24, rows = 7
  const W = leftPad + cols * cell + rightPad
  const H = topPad + rows * cell + bottomPad

  const color = (v, hasFailed) => {
    if (!v || max <= 0) return 'rgba(79,156,249,0.06)'
    const o = (0.18 + 0.82 * (v / max)).toFixed(3)
    return hasFailed ? `rgba(239,68,68,${o})` : `rgba(79,156,249,${o})`   // kırmızı : mavi
  }

  return (
    <div className="mini-chart">
      {title && (
        <div className="mini-chart-hdr">
          <span className="mini-chart-lbl">{title}</span>
          <span className="mini-chart-cur mini-chart-na">{hourLabel}{total ? ` · Σ ${total}` : ''}</span>
        </div>
      )}
      <svg viewBox={`0 0 ${W} ${H}`} className="mini-chart-svg" role="img" aria-label={title}>
        {/* Bugün satırı vurgusu (arka plan + çerçeve) */}
        {todayDow >= 0 && todayDow < rows && (
          <rect x={1} y={topPad + todayDow * cell - 1} width={W - 2} height={cell} rx="3"
            fill="rgba(245,158,11,0.12)" stroke="rgba(245,158,11,0.55)" strokeWidth="1" />
        )}
        {/* Gün etiketleri (sol) + satır hücreleri + gün toplamı (sağ) */}
        {Array.from({ length: rows }).map((_, r) => {
          const y = topPad + r * cell
          const row = matrix[r] || []
          const isToday = r === todayDow
          return (
            <g key={r}>
              <text x={leftPad - 6} y={y + cell - 5} textAnchor="end" fontSize="8"
                fontWeight={isToday ? 700 : 400} fill={isToday ? '#b45309' : 'var(--chart-label)'}>{dayLabels[r] || ''}</text>
              {Array.from({ length: cols }).map((__, c) => {
                const v = Number(row[c] || 0)
                const f = Number((failed[r] || [])[c] || 0)
                const clickable = onCellClick && v > 0
                const hh = String(c).padStart(2, '0')
                return (
                  <rect key={c}
                    x={leftPad + c * cell} y={y}
                    width={cell - 2} height={cell - 2} rx="2"
                    fill={color(v, f > 0)}
                    style={clickable ? { cursor: 'pointer' } : undefined}
                    onClick={clickable ? () => onCellClick(r, c) : undefined}>
                    <title>{`${dayLabels[r] || ''} ${hh}:00–${hh}:59 — ${v}${f > 0 ? ` (${f} başarısız)` : ''}`}</title>
                  </rect>
                )
              })}
              {/* Gün sonu toplamı (sağ) */}
              <text x={leftPad + cols * cell + 5} y={y + cell - 5} fontSize="8"
                fontWeight={isToday ? 700 : 600} fill={isToday ? '#b45309' : 'var(--chart-text, #64748b)'}>
                {rowTotals[r] != null ? rowTotals[r] : ''}
              </text>
            </g>
          )
        })}
        {/* Saat etiketleri (her 3 saatte) + saat toplamları (altta) */}
        {Array.from({ length: cols }).map((_, c) => (
          <g key={c}>
            {c % 3 === 0 && (
              <text x={leftPad + c * cell + (cell - 2) / 2} y={topPad + rows * cell + 10}
                textAnchor="middle" fontSize="7" fill="var(--chart-label)">{c}</text>
            )}
            {colTotals[c] > 0 && (
              <text x={leftPad + c * cell + (cell - 2) / 2} y={topPad + rows * cell + 22}
                textAnchor="middle" fontSize="6" fill="var(--text-muted, #94a3b8)">{colTotals[c]}</text>
            )}
          </g>
        ))}
      </svg>
    </div>
  )
}
