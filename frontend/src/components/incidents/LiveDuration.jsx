import { useState } from 'react'
import { useT } from '../../i18n/index.jsx'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import { durationMs, formatDuration } from '../../utils/incidentMeta.js'
import { cn } from '@/lib/utils'

/**
 * Canlı olay süresi — kendi saatini taşıyan YAPRAK (2026-10-09).
 *
 * <p>Eskiden Olaylar sayfası süren olay varken saniyede bir sayfa düzeyinde `setNowMs` çağırıyor, pano/tablonun
 * TAMAMI (kartlar, rozetler, menüler) her saniye yeniden çiziliyordu. Artık yalnız bu küçük `<span>` saniyede bir
 * yenilenir. Değer aynı hesaptır: `formatDuration(durationMs(since, until, şimdi))`.
 *
 * <p>`live` (süren olay) iken saniyede bir tazelenir; sekme gizliyken durur, geri gelince hemen tazelenir
 * (useVisibleInterval). `live` değilken saat çalışmaz (çözülmüş olayın süresi `until`'den hesaplanır).
 * `format(dur)` metni sarar (ör. "3 dk sürüyor"); verilmezse yalnız süre.
 */
export default function LiveDuration({ since, until, live = false, format, className, ...rest }) {
  const t = useT()
  const [now, setNow] = useState(() => Date.now())
  useVisibleInterval(() => setNow(Date.now()), live ? 1000 : 0, false)
  const dur = formatDuration(durationMs(since, until, now), t)
  return (
    <span data-slot="live-duration" className={cn('tabular-nums', className)} {...rest}>
      {format ? format(dur) : dur}
    </span>
  )
}
