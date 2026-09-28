import HintPopover from '../ui/HintPopover.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Kontrol Geçmişi özet kutucuğu — CheckHistoryTab'tan ayrıldı (2026-09-28) ki tür-özel özetler (sertifika:
 * `certmodal/CertHistoryInsights`) AYNI görsel dağarcığı kullansın. Çizim değişmedi.
 *
 * Süzgeç olan kutucuk shadcn Button (`aria-pressed`), diğerleri salt gösterim. Salt gösterim kutucuğun açıklaması
 * (`hint`) DOKUN-GÖR: kutucuk ui/HintPopover tetiği olur (fare, klavye VE dokunmatik; E4 2026-09-28e — eskiden
 * SimpleTooltip'li odaklanamayan bir div'di, açıklama ve "Son hata" metni yalnız fareyle görünüyordu).
 * Ton = değer/ikon rengi + hafif zemin; SOL ŞERİT YOK (kullanıcı kuralı 2026-09-26).
 * Test kancaları: `data-slot="hist-tile"` + `data-tone`, değer `data-slot="hist-tile-value"`, alt satır `hist-tile-sub`.
 */

// Kutucuk tonları — MonitorStatsBar sözlüğünün aynısı.
export const TILE_TONE = {
  total:   { text: 'text-foreground', bg: 'bg-muted/40' },
  success: { text: 'text-success', bg: 'bg-success/10 dark:bg-success/15' },
  warning: { text: 'text-amber-600 dark:text-amber-400', bg: 'bg-amber-500/10' },
  danger:  { text: 'text-red-600 dark:text-red-400', bg: 'bg-red-500/[0.08]' },
}

export default function HistTile({ icon: Icon, label, value, sub, tone = 'total', pressed, onClick, hint }) {
  const tn = TILE_TONE[tone] ?? TILE_TONE.total
  const body = (<>
    <Icon aria-hidden="true" className={cn('size-4 shrink-0', tn.text)} />
    <span className="flex min-w-0 flex-1 flex-col items-start gap-px text-left">
      <span data-slot="hist-tile-value" className={cn('text-lg leading-none font-extrabold tracking-[-.02em] tabular-nums', tn.text)}>{value}</span>
      <span className="text-[11px] leading-tight font-semibold text-muted-foreground">{label}</span>
      {sub && <span data-slot="hist-tile-sub" className="text-[10.5px] leading-tight text-muted-foreground">{sub}</span>}
    </span>
  </>)
  const cls = cn('flex min-h-14 min-w-0 items-center gap-2.5 rounded-lg border px-2.5 py-2', tn.bg)
  if (!onClick) {
    if (!hint) return <div data-slot="hist-tile" data-tone={tone} className={cls}>{body}</div>
    return (
      <HintPopover content={hint} data-slot="hist-tile" data-tone={tone}
        triggerClassName={cn(cls, 'w-full justify-start whitespace-normal pointer-coarse:min-h-14 hover:bg-accent/60 dark:hover:bg-accent/40')}>
        {body}
      </HintPopover>
    )
  }
  return (
    <Button type="button" variant="ghost" data-slot="hist-tile" data-tone={tone} aria-pressed={pressed} title={hint}
      onClick={onClick}
      className={cn(cls, 'h-auto justify-start whitespace-normal hover:bg-accent/60 dark:hover:bg-accent/40',
        pressed && 'border-primary ring-2 ring-primary/40')}>
      {body}
    </Button>
  )
}
