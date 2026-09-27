import { Tooltip as TooltipPrimitive } from 'radix-ui'
import { Tooltip, TooltipContent, TooltipProvider } from '@/components/shadcn/tooltip'
import { cn } from '@/lib/utils'

/**
 * Kısa ipucu (hover / klavye odağı) — ikon düğmelerindeki eski `title=` ipucunun shadcn Tooltip karşılığı.
 *
 * Neden sarmalayıcı: yerel `shadcn/tooltip.jsx` `Tooltip`'i kendi sağlayıcısıyla SARMAZ ve uygulamadaki
 * tek `TooltipProvider` SidebarProvider'ın içinde. O ağacın dışında çizilen bir ekran (giriş sayfası,
 * ErrorBoundary, bileşeni tek başına sınayan testler) "`Tooltip` must be used within `TooltipProvider`"
 * ile çökerdi. Radix sağlayıcıları iç içe geçebilir; burada her ipucu kendi sağlayıcısını taşır
 * (upstream shadcn'in güncel `Tooltip`'i de böyle yapar).
 *
 * - Çocuk TEK öğe olmalı ve ref almalı (asChild): shadcn Button/Badge forwardRef, DOM öğeleri doğal.
 * - İçerik modal içinde de görünsün diye katman `--z-menu` (SHADCN.md §2.5; `z-50` ModalShell'in altında kalır).
 * - Temel bilgi TAŞIMAZ: ikon düğmesinin erişilebilir adı yine `aria-label`'dadır; ipucu görenler içindir.
 * - `content` boşsa çocuk olduğu gibi döner (koşullu ipucu için çağıranda dallanma gerekmez).
 * - Tetik Radix'in ÇIPLAK Trigger'ı: shadcn `TooltipTrigger` asChild çocuğa `data-slot="tooltip-trigger"` geçirir ve
 *   çocuğun kendi kancasını (Button → "button", Badge → "badge") EZERDİ — testler ve `[data-slot=button]` CSS köprüsü
 *   sarılan düğmeyi tanımazdı. Çıplak tetik yalnız olay/aria/data-state ekler, çocuğun `data-slot`'u kalır.
 */
export default function SimpleTooltip({ content, side = 'top', align, className, children }) {
  if (content == null || content === '' || content === false) return children
  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipPrimitive.Trigger asChild>{children}</TooltipPrimitive.Trigger>
        <TooltipContent side={side} align={align} sideOffset={4}
          className={cn('z-(--z-menu) max-w-xs', className)}>
          {content}
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  )
}
