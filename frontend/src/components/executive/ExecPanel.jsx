import { useId } from 'react'
import { Card, CardContent } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

/** Ayar panelinin başlığı: ikon kutusu + başlık + açıklama (+ sağda isteğe bağlı rozet/eylem). */
export function PanelTitle({ icon: Icon, title, description, aside = null, className }) {
  return (
    <div data-slot="ex-panel-title" className={cn('flex min-w-0 flex-wrap items-start justify-between gap-3', className)}>
      <div className="flex min-w-0 items-start gap-3">
        {Icon && (
          <span aria-hidden="true" className="inline-flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
            <Icon className="size-5" />
          </span>
        )}
        <div className="min-w-0">
          <h3 className="m-0 text-lg leading-tight font-semibold tracking-tight [overflow-wrap:anywhere]">{title}</h3>
          {description && <p className="m-0 mt-1 max-w-[72ch] text-sm text-muted-foreground">{description}</p>}
        </div>
      </div>
      {aside}
    </div>
  )
}

/** Ayar bölümü — başlıklı kart (ikon + başlık + açıklama), içerik dikey yığın. */
export function PanelSection({ icon: Icon, title, description, children, className, slot }) {
  const id = useId()
  return (
    <Card role="region" aria-labelledby={id} data-slot={slot} className={cn('min-w-0 gap-3 py-4 shadow-xs', className)}>
      <CardContent className="flex min-w-0 flex-col gap-3 px-4 sm:px-5">
        <div className="flex min-w-0 flex-col gap-0.5">
          <h4 id={id} className="m-0 flex items-center gap-2 text-sm font-semibold">
            {Icon && <Icon aria-hidden="true" className="size-4 shrink-0 text-primary" />}{title}
          </h4>
          {description && <p className="m-0 text-xs leading-relaxed text-muted-foreground">{description}</p>}
        </div>
        {children}
      </CardContent>
    </Card>
  )
}
