/**
 * Hafif inline-SVG sparkline — kütüphane yok. `data` sayı dizisi; renk CSS token'ından gelir (tema uyumlu).
 * Son nokta bir işaret dairesiyle vurgulanır. Genişlik/yükseklik viewBox ile ölçeklenir.
 */
export default function Sparkline({ data = [], width = 100, height = 28, color = 'var(--sb-accent, #3b82f6)', label }) {
  const nums = (Array.isArray(data) ? data : []).map((v) => (typeof v === 'number' && isFinite(v) ? v : 0))
  if (nums.length < 2) {
    return <svg width={width} height={height} className="spark" aria-hidden="true" />
  }
  const max = Math.max(...nums, 1)
  const min = Math.min(...nums, 0)
  const range = max - min || 1
  const stepX = width / (nums.length - 1)
  const pts = nums.map((v, i) => {
    const x = i * stepX
    const y = height - ((v - min) / range) * (height - 4) - 2
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })
  const [lx, ly] = pts[pts.length - 1].split(',')
  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} className="spark"
      role="img" aria-label={label || 'trend'} preserveAspectRatio="none">
      <polyline points={pts.join(' ')} fill="none" stroke={color} strokeWidth="1.5"
        strokeLinejoin="round" strokeLinecap="round" />
      <circle cx={lx} cy={ly} r="2.2" fill={color} />
    </svg>
  )
}
