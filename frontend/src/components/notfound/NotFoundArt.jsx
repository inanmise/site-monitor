import { Lock, SearchX } from 'lucide-react'
import BrandLogo from '../BrandLogo.jsx'
import { cn } from '@/lib/utils'

/**
 * 404 çizimi (2026-10-08): "4 [turp] 4" — marka işareti sıfırın yerinde, çevresinde yavaş dönen kesik çizgili bir
 * "tarama yörüngesi" ve köşede bulunamadı rozeti (lucide SearchX). `restricted` çeşidinde rakam yok, rozet kilit.
 * Tamamen dekoratif (aria-hidden): anlamı başlık ve metin taşır. Raster indirmesi yok — logo BrandLogo'dan (tek kaynak),
 * gerisi jeton renkli CSS. Hareket `motion-safe` (azaltılmış hareket tercihinde durur).
 *
 * `compact`: uygulama içi panel boyu.
 */
export default function NotFoundArt({ variant = 'notFound', compact = false, className }) {
  const restricted = variant === 'restricted'
  const Badge = restricted ? Lock : SearchX
  const digit = cn(
    'font-black leading-none tracking-tighter text-foreground/85 tabular-nums select-none',
    compact ? 'text-[4.5rem] sm:text-[5.5rem]' : 'text-[5.25rem] min-[400px]:text-[6.5rem] sm:text-[8.5rem]',
  )
  const ring = compact ? 'size-[5.5rem] sm:size-28' : 'size-24 min-[400px]:size-28 sm:size-40'
  const logo = compact ? 56 : 96
  return (
    <div aria-hidden="true" data-slot="nf-art" data-variant={variant}
      className={cn('flex items-center justify-center gap-1 sm:gap-3', className)}>
      {!restricted && <span className={digit}>4</span>}
      <span className={cn('relative grid shrink-0 place-items-center', ring)}>
        <span className="absolute inset-0 rounded-full border-2 border-dashed border-primary/35 motion-safe:animate-[spin_40s_linear_infinite]" />
        <span className="absolute inset-[12%] rounded-full bg-primary/5 ring-1 ring-primary/10" />
        <BrandLogo status="ok" size={logo} className="relative size-[62%] object-contain" />
        <span className="absolute -right-1 -bottom-1 grid size-9 place-items-center rounded-full border bg-card text-muted-foreground shadow-sm sm:size-10">
          <Badge className="size-[18px] sm:size-5" />
        </span>
      </span>
      {!restricted && <span className={digit}>4</span>}
    </div>
  )
}
