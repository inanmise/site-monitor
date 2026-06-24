// Peak login ısı haritası — saf SVG, 7 (hafta-günü) × 24 (saat). MiniChart gibi bağımsız.
// props: matrix [7][24] login sayıları, failed [7][24] başarısız login sayıları, max (en yoğun hücre),
//        dayLabels (7 kısa ad), title, hourLabel, onCellClick(weekday, hour),
//        todayDow (bugünün hafta-günü 0..6, yoksa -1 → satır vurgusu),
//        rowTotals [7] (gün toplamları — sağda), colTotals [24] (saat toplamları — altta), total (hafta),
//        cell (hücre boyutu px — büyük tek-hafta görünümünde 32; SVG viewBox ile kaba ölçeklenir).
// Renk yoğunluğu = count/max. Hücrede başarısız login varsa KIRMIZI, yoksa mavi.
export default function LoginHeatmap({
  matrix = [], failed = [], max = 0, dayLabels = [], title, hourLabel, onCellClick,
  todayDow = -1, rowTotals = [], colTotals = [], total = 0, cell = 32,
}) {
  const cols = 24, rows = 7
  const gap = cell > 22 ? 3 : 2
  const leftPad = Math.round(cell * 1.3), topPad = 8
  const rightPad = Math.round(cell * 1.2), bottomPad = Math.round(cell * 1.5)
  const W = leftPad + cols * cell + rightPad
  const H = topPad + rows * cell + bottomPad
  const dayFs = Math.max(8, Math.round(cell * 0.34))   // gün etiketi
  const hourFs = Math.max(8, Math.round(cell * 0.34))  // saat etiketi
  const totFs = Math.max(9, Math.round(cell * 0.40))   // toplam rakamlar (gün/saat)
  const hourStep = cell >= 26 ? 2 : 3                  // büyük hücrede her 2 saatte etiket
  const hourY = topPad + rows * cell + Math.round(cell * 0.52)
  const colTotY = hourY + totFs + 4

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
      <svg viewBox={`0 0 ${W} ${H}`} className="mini-chart-svg" role="img" aria-label={title}
           style={{ width: '100%', height: 'auto' }}>
        {/* Bugün satırı vurgusu (arka plan + çerçeve) */}
        {todayDow >= 0 && todayDow < rows && (
          <rect x={1} y={topPad + todayDow * cell - 1} width={W - 2} height={cell} rx="3"
            fill="rgba(245,158,11,0.12)" stroke="rgba(245,158,11,0.55)" strokeWidth="1" />
        )}
        {/* Gün etiketleri (sol) + satır hücreleri + gün toplamı (sağ) */}
        {Array.from({ length: rows }).map((_, r) => {
          const y = topPad + r * cell
          const baseline = y + Math.round(cell * 0.62)
          const row = matrix[r] || []
          const isToday = r === todayDow
          return (
            <g key={r}>
              <text x={leftPad - 7} y={baseline} textAnchor="end" fontSize={dayFs}
                fontWeight={isToday ? 700 : 500} fill={isToday ? '#b45309' : 'var(--chart-label)'}>{dayLabels[r] || ''}</text>
              {Array.from({ length: cols }).map((__, c) => {
                const v = Number(row[c] || 0)
                const f = Number((failed[r] || [])[c] || 0)
                const clickable = onCellClick && v > 0
                const hh = String(c).padStart(2, '0')
                return (
                  <rect key={c}
                    x={leftPad + c * cell} y={y}
                    width={cell - gap} height={cell - gap} rx="3"
                    fill={color(v, f > 0)}
                    style={clickable ? { cursor: 'pointer' } : undefined}
                    onClick={clickable ? () => onCellClick(r, c) : undefined}>
                    <title>{`${dayLabels[r] || ''} ${hh}:00–${hh}:59 — ${v}${f > 0 ? ` (${f} başarısız)` : ''}`}</title>
                  </rect>
                )
              })}
              {/* Gün sonu toplamı (sağ) */}
              <text x={leftPad + cols * cell + 6} y={baseline} fontSize={totFs}
                fontWeight={isToday ? 700 : 600} fill={isToday ? '#b45309' : 'var(--chart-text, #64748b)'}>
                {rowTotals[r] != null ? rowTotals[r] : ''}
              </text>
            </g>
          )
        })}
        {/* Saat etiketleri (büyük hücrede her 2 saatte) + saat toplamları (altta, mavi kalın) */}
        {Array.from({ length: cols }).map((_, c) => (
          <g key={c}>
            {c % hourStep === 0 && (
              <text x={leftPad + c * cell + (cell - gap) / 2} y={hourY}
                textAnchor="middle" fontSize={hourFs} fontWeight="600" fill="#64748b">{c}</text>
            )}
            {colTotals[c] > 0 && (
              <text x={leftPad + c * cell + (cell - gap) / 2} y={colTotY}
                textAnchor="middle" fontSize={totFs} fontWeight="700" fill="#2563eb">{colTotals[c]}</text>
            )}
          </g>
        ))}
      </svg>
    </div>
  )
}
