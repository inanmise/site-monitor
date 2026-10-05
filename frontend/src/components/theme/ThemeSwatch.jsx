import { schemeOf } from '../../theme/themes.js'
import { cn } from '@/lib/utils'

/**
 * Tema renk örneği — kendi `data-theme` / `data-scheme` kabında çizilir, yani renkler doğrudan o temanın CSS
 * jetonlarından gelir (hex kopyası yok, tek kaynak CSS). Sol yarı zemin, sağ yarı kart, nokta vurgu rengi.
 * Süs: ekran okuyucudan gizli (adı ve şeması yanındaki metinde).
 */
export default function ThemeSwatch({ id, className }) {
  return (
    <span aria-hidden="true" data-slot="theme-swatch" data-theme={id} data-scheme={schemeOf(id)}
      className={cn('relative inline-flex size-6 shrink-0 overflow-hidden rounded-md border border-border bg-background', className)}>
      <span className="absolute inset-y-0 right-0 w-1/2 bg-card" />
      <span className="absolute bottom-1 left-1 size-2.5 rounded-full bg-primary" />
    </span>
  )
}
