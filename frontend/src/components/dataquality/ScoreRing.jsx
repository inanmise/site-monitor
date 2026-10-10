import { ProgressRing } from '../ui/Progress.jsx'
import { cn } from '@/lib/utils'
import { bandColor, hasScore } from './dataQualityModel.js'

/**
 * Veri kalitesi puan halkası — proje `ProgressRing`'i (role="progressbar", aria-valuenow) + ortada büyük puan metni.
 * Halka rengi bandın jetonundan (iyi → yeşil, zayıf → kırmızı); renk TEK bilgi taşıyıcı değildir: puan metin olarak
 * ortada, bant yanında rozet olarak yazılır. Puanı olmayan takımda "—".
 *
 * Boyutlar: `lg` (kurum kartı, 112 px), `md` (takım kartı, 64 px), `sm` (tablo satırı, 44 px).
 */
const SIZES = {
  lg: { px: 112, stroke: 10, text: 'text-3xl' },
  md: { px: 64, stroke: 7, text: 'text-lg' },
  sm: { px: 44, stroke: 5, text: 'text-sm' },
}

export default function ScoreRing({ score, band, size = 'md', label, className }) {
  const s = SIZES[size] ?? SIZES.md
  const has = hasScore(score)
  return (
    <span data-slot="dq-score-ring" data-band={band || 'NO_DATA'}
      className={cn('relative inline-grid shrink-0 place-items-center', className)}
      style={{ width: s.px, height: s.px }}>
      <ProgressRing value={has ? score : 0} max={100} size={s.px} stroke={s.stroke} showValue={false}
        color={has ? bandColor(band) : 'transparent'} label={label} />
      <span aria-hidden="true"
        className={cn('pointer-events-none absolute inset-0 grid place-items-center font-bold tabular-nums leading-none', s.text)}>
        {has ? score : '—'}
      </span>
    </span>
  )
}
