import { CommandItem } from '@/components/shadcn/command'
import { Kbd } from '@/components/shadcn/kbd'
import { cn } from '@/lib/utils'

/**
 * Komut paleti sonuç satırı (2026-09-26): ikon kutusu · başlık + alt satır + meta çipleri · sağda durum/tür.
 * cmdk `CommandItem` (role="option"); telefonda 44 px dokunma yüksekliği, masaüstünde 40 px. Seçili satırda
 * sağ uçta ↵ ipucu belirir (yalnız masaüstü — telefonda klavye yok).
 *
 * @param {object} props
 * @param {string} props.value      cmdk değeri (tekil)
 * @param {import('react').ElementType} [props.Icon]
 * @param {import('react').ReactNode} props.label
 * @param {import('react').ReactNode} [props.sub]
 * @param {import('react').ReactNode} [props.meta]      çip satırı (takım / grup / etiket / tier)
 * @param {import('react').ReactNode} [props.trailing]  sağ sütun (durum rozeti, tür adı)
 * @param {() => void} props.onSelect
 */
export default function PaletteRow({ value, Icon, label, sub, meta, trailing, onSelect, className, ...rest }) {
  return (
    <CommandItem value={value} onSelect={onSelect}
      className={cn('group min-h-11 cursor-pointer gap-3 rounded-md px-2.5 py-2 md:min-h-10 md:py-1.5', className)}
      {...rest}>
      <span aria-hidden="true"
        className="flex size-8 shrink-0 items-center justify-center rounded-md border bg-muted/60 group-data-[selected=true]:bg-background [&_svg]:size-4">
        {Icon ? <Icon /> : null}
      </span>
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="truncate text-sm font-medium">{label}</span>
        {sub ? <span className="truncate text-xs text-muted-foreground">{sub}</span> : null}
        {meta ? <span className="mt-1 flex flex-wrap items-center gap-1">{meta}</span> : null}
      </span>
      {trailing ? <span className="ml-auto flex shrink-0 items-center gap-1.5 text-xs text-muted-foreground">{trailing}</span> : null}
      <Kbd aria-hidden="true" className="hidden shrink-0 opacity-0 group-data-[selected=true]:opacity-100 md:inline-flex">↵</Kbd>
    </CommandItem>
  )
}
