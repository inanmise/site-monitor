import { cn } from '@/lib/utils'

/**
 * Kenar çubuğu satır görünümü (2026-09-26 tur 2, kullanıcı geri bildirimi: "menü olduğu belli olsun").
 *
 * Her satır bir DÜĞME gibi: 40 px, yuvarlak köşe, hover zemini, odak halkası; ikon küçük yuvarlak bir kutucukta.
 * Kutucuk ayrı bir sarmalayıcı değil, ikonun (ilk svg çocuğun) KENDİSİ: dolgu + zemin alan svg kutu olur — böylece
 * InboxBell gibi yalnız `className` alan tetikler de aynı görünümü sınıf üzerinden giyer. Etkin durum DOLU
 * (`bg-primary/10` + birincil renk + yarı kalın + boyalı kutucuk); sol şerit YOK (kalıcı kullanıcı kararı).
 * Seçiciler tek katmanlı arbitrary variant'lar (`[&:hover>svg:first-child]`) — bölüm okunun (son svg) kutucuğa
 * dönüşmemesi için yalnız İLK svg hedeflenir.
 */
const ICON_TILE = [
  '[&>svg:first-child]:size-7 [&>svg:first-child]:shrink-0 [&>svg:first-child]:rounded-md [&>svg:first-child]:p-1.5',
  '[&>svg:first-child]:bg-sidebar-accent/70 [&>svg:first-child]:text-sidebar-foreground/85 [&>svg:first-child]:transition-colors',
  '[&:hover>svg:first-child]:bg-sidebar-accent-foreground/10 [&:hover>svg:first-child]:text-sidebar-accent-foreground',
  '[&[data-active=true]>svg:first-child]:bg-primary/15 [&[data-active=true]>svg:first-child]:text-primary',
]

/** Ana menü satırı (bölümler, Pano, Yardım, Ara, Bildirimler). */
export const NAV_ITEM = cn(
  'h-10 gap-2.5 rounded-md px-1.5 text-sm text-sidebar-foreground',
  'hover:bg-sidebar-accent hover:text-sidebar-accent-foreground',
  'data-[active=true]:bg-primary/10 data-[active=true]:font-semibold data-[active=true]:text-primary data-[active=true]:hover:bg-primary/15',
  // İkon kipi: 32 px düğmede 28 px kutucuk ortada
  'group-data-[collapsible=icon]:size-8! group-data-[collapsible=icon]:p-0.5!',
  ...ICON_TILE,
)

/** Açık bölüm başlığı hafifçe vurgulu (Collapsible / yana açılan menü tetiği `data-state=open`). */
export const NAV_SECTION_OPEN = 'data-[state=open]:bg-sidebar-accent/60 data-[state=open]:text-sidebar-accent-foreground'

/** Alt sekme satırı: 36 px (telefon çekmecesinde 40 px dokunma hedefi), daha küçük kutucuk; kılavuz çizgi SidebarMenuSub'dan. */
export const NAV_SUB_ITEM = cn(
  'h-9 w-full gap-2.5 rounded-md px-1.5 text-left text-sm text-sidebar-foreground in-data-[mobile=true]:h-10',
  'hover:bg-sidebar-accent hover:text-sidebar-accent-foreground',
  'data-[active=true]:bg-primary/10 data-[active=true]:font-semibold data-[active=true]:text-primary',
  '[&>svg:first-child]:size-6 [&>svg:first-child]:shrink-0 [&>svg:first-child]:rounded-sm [&>svg:first-child]:p-1',
  '[&>svg:first-child]:bg-sidebar-accent/60 [&>svg:first-child]:text-sidebar-foreground/80 [&>svg:first-child]:transition-colors',
  '[&:hover>svg:first-child]:bg-sidebar-accent-foreground/10 [&:hover>svg:first-child]:text-sidebar-accent-foreground',
  '[&[data-active=true]>svg:first-child]:bg-primary/15 [&[data-active=true]>svg:first-child]:text-primary',
)

/** Grup etiketi: küçük, büyük harf, harf aralıklı. */
export const NAV_GROUP_LABEL = 'h-7 px-2 text-[11px] font-semibold tracking-wider text-sidebar-foreground/60 uppercase'
