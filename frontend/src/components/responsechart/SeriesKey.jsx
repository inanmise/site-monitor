/**
 * Seri anahtarı — işaretin kendisini yansıtır: çizgi (ortalama, yardımcı seri), kesikli çizgi (p95, eşik),
 * yarı saydam alan (min–maks aralığı), halkalı nokta (başarısız kontrol). SVG: düğme içinde de boyutu korunsun
 * diye `size-auto` + sabit genişlik/yükseklik (shadcn Button/Toggle `size-` taşımayan SVG'yi 16 px'e ezer).
 */
export function SeriesKey({ shape = 'line', color }) {
  const common = { 'aria-hidden': true, focusable: 'false', className: 'size-auto h-2.5 w-3.5 shrink-0 overflow-visible' }
  if (shape === 'dot') {
    return (
      <svg viewBox="0 0 14 10" {...common}>
        <circle cx="7" cy="5" r="3.5" fill={color} stroke="var(--background)" strokeWidth="1.5" />
      </svg>
    )
  }
  if (shape === 'area') {
    return (
      <svg viewBox="0 0 14 10" {...common}>
        <rect x="0.5" y="1" width="13" height="8" rx="2" fill={color} fillOpacity="0.28" />
      </svg>
    )
  }
  return (
    <svg viewBox="0 0 14 10" {...common}>
      <line x1="0.5" y1="5" x2="13.5" y2="5" stroke={color} strokeWidth="2" strokeLinecap="round"
        strokeDasharray={shape === 'dashed' ? '3 2.5' : undefined} />
    </svg>
  )
}
