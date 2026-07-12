/**
 * Skoru dolduran ince dairesel gösterge (0-100) — inline SVG, kütüphane yok. Sadece halka çizer;
 * merkezdeki büyük rakamı ebeveyn HTML olarak üstüne bindirir (tema/font kontrolü kolay olsun diye).
 * Renk band'a göre CSS token'ından gelir (yeşil/amber/kırmızı).
 */
export default function CircularGauge({ value = 0, size = 96, stroke = 8, color = 'var(--ok, #16a34a)', label }) {
  const v = Math.max(0, Math.min(100, Number(value) || 0))
  const r = (size - stroke) / 2
  const circ = 2 * Math.PI * r
  const dash = (v / 100) * circ
  const c = size / 2
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="wr-gauge"
      role="img" aria-label={label || `${v}/100`}>
      <circle cx={c} cy={c} r={r} fill="none" stroke="var(--border, #ddd)" strokeWidth={stroke} />
      <circle cx={c} cy={c} r={r} fill="none" stroke={color} strokeWidth={stroke} strokeLinecap="round"
        strokeDasharray={`${dash} ${circ - dash}`} transform={`rotate(-90 ${c} ${c})`} />
    </svg>
  )
}
