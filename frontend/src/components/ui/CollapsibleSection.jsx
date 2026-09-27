import { ChevronDown } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'

/**
 * Katlanır bölüm başlığı — projenin TEK "İstatistikler ▾" şeridi (2026-09-26). shadcn Collapsible; tetik tam genişlik
 * outline Button (aria-expanded Radix'ten, klavye yerleşik), içerik kapalıyken DOM'da yok.
 *
 * <p>Eski el yapımı `div.stats-collapse-bar[role=button]` (Pano, İstatistikler, Değişiklik Konsolu, Saklama) ve iki
 * kopya shadcn tetik (MonitorStatsSection, SystemHealth HealthSection) bunun yerine bunu kullanır. Test kancası
 * `data-slot="stats-toggle"` (headless QA notları da buna bakar).
 *
 * @param {boolean}  open           açık mı
 * @param {Function} onOpenChange   (next) => void
 * @param {import('react').ComponentType} icon  başlık ikonu (lucide)
 * @param {import('react').ReactNode} label     başlık
 * @param {import('react').ReactNode} [hint]    KAPALIYKEN başlığın yanında (telefonda alt satırda) görünen ipucu
 * @param {string}   [toggleLabel]  tetiğin erişilebilir adı + title (ör. "İstatistikleri göster"); yoksa içerikten
 * @param {boolean}  [showTrigger=true] false → başlık çizilmez (ör. henüz veri yokken), içerik yine open'a bağlı
 * @param {string}   [className]    Collapsible kökü
 * @param {string}   [triggerClassName]
 * @param {string}   [contentClassName]
 */
export default function CollapsibleSection({
  open, onOpenChange, icon: Icon, label, hint, toggleLabel, showTrigger = true,
  className, triggerClassName, contentClassName, children, ...rest
}) {
  return (
    <Collapsible open={!!open} onOpenChange={onOpenChange} className={cn('min-w-0', className)} {...rest}>
      {showTrigger && (
        <CollapsibleTrigger asChild>
          <Button type="button" variant="outline" data-slot="stats-toggle"
            aria-label={toggleLabel} title={toggleLabel}
            // whitespace-normal: shadcn Button'ın nowrap'ı uzun etiket/ipucunu telefonda ekran dışına taşırıyordu
            // (B2 bulgusu 2026-09-26); etiket telefonda satırın kalanını alır → ikon + etiket + ok aynı satırda kalır
            className={cn('h-auto min-h-11 w-full flex-wrap justify-start gap-2 rounded-[10px] bg-card px-4 py-3 text-left font-normal whitespace-normal shadow-xs sm:flex-nowrap', triggerClassName)}>
            {Icon && <Icon aria-hidden="true" className="size-[18px] shrink-0 text-primary" />}
            <span className="min-w-0 flex-1 text-[15px] font-semibold break-words sm:flex-none">{label}</span>
            {!open && hint && (
              <span className="order-3 ml-[26px] min-w-0 basis-full text-[13px] break-words text-muted-foreground sm:order-none sm:ml-2 sm:basis-auto sm:flex-1">
                {hint}
              </span>
            )}
            <ChevronDown aria-hidden="true"
              className={cn('ml-auto size-[18px] text-muted-foreground transition-transform duration-200 motion-reduce:transition-none', open && 'rotate-180')} />
          </Button>
        </CollapsibleTrigger>
      )}
      <CollapsibleContent className={contentClassName}>{children}</CollapsibleContent>
    </Collapsible>
  )
}
