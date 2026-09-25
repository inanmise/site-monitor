import { useState } from 'react'
import { ListFilter } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Command, CommandGroup, CommandInput, CommandItem, CommandList, CommandSeparator } from '@/components/shadcn/command'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { Separator } from '@/components/shadcn/separator'
import { useT } from '../../i18n/index.jsx'

/**
 * Faset süzgeci — shadcn'in "data table faceted filter" deseni (Tasks örneği): kesikli kenarlı Button tetik,
 * Popover + Command (cmdk) listesi, her seçenekte onay kutusu + o anki sayı, seçim varsa "Seçimi temizle".
 * Çoklu seçim; `value` = seçili değer dizisi, `onChange(next)`. Seçenek: {value, label, count?, hint?}.
 *
 * Tetikte seçili sayısı Badge olarak görünür (ekran okuyucuya "N seçili"); seçili adlar tetiğin ipucunda.
 * Liste seçimde KAPANMAZ (birden çok seçilir). Satıra basış odağı arama kutusundan ÇALMAZ (mousedown
 * preventDefault) — ok tuşları/Enter cmdk kökünde işlemeye devam eder; seçim yalnız cmdk onSelect'te
 * (tık ya da Enter) → bir basış bir kez çevirir.
 */
export default function FacetedFilter({
  title, icon: Icon = ListFilter, options = [], value = [], onChange, searchPlaceholder, tooltip, className,
}) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const selected = Array.isArray(value) ? value : []
  const isSel = (v) => selected.includes(v)
  const toggle = (v) => onChange(isSel(v) ? selected.filter((x) => x !== v) : [...selected, v])

  const q = query.trim().toLowerCase()
  const visible = q
    ? options.filter((o) => String(o.label ?? '').toLowerCase().includes(q) || String(o.value).toLowerCase().includes(q))
    : options
  const n = selected.length
  const selectedLabels = options.filter((o) => isSel(o.value)).map((o) => o.label)

  return (
    <Popover open={open} onOpenChange={(next) => { setOpen(next); if (!next) setQuery('') }}>
      <PopoverTrigger asChild>
        <Button type="button" variant="outline" size="sm"
          className={cn('h-8 border-dashed', className)}
          data-active={n > 0 ? 'true' : undefined}
          title={n > 0 ? selectedLabels.join(', ') : tooltip}>
          <Icon />
          {title}
          {n > 0 && (
            <>
              <Separator orientation="vertical" className="mx-0.5 h-4" />
              <Badge variant="secondary" className="rounded-sm px-1.5 font-mono tabular-nums" aria-hidden="true">{n}</Badge>
              <span className="sr-only">{t('ff.selectedCount', n)}</span>
            </>
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="z-(--z-menu) w-64 p-0">
        <Command shouldFilter={false} loop>
          <CommandInput placeholder={searchPlaceholder || title} value={query} onValueChange={setQuery} />
          <CommandList aria-multiselectable="true">
            {visible.length === 0 && (
              <div className="py-6 text-center text-sm text-muted-foreground" role="status">{t('ss.noResult')}</div>
            )}
            {visible.length > 0 && (
              <CommandGroup>
                {visible.map((o) => {
                  const checked = isSel(o.value)
                  return (
                    <CommandItem
                      key={String(o.value)}
                      value={`o:${String(o.value)}`}
                      title={o.hint || undefined}
                      aria-checked={checked}
                      data-checked={checked ? 'true' : undefined}
                      onMouseDown={(e) => e.preventDefault()}
                      onSelect={() => toggle(o.value)}
                      className={cn(!checked && o.count === 0 && 'text-muted-foreground')}
                    >
                      {/* Görsel işaret: satırın kendisi seçenek; kutu ayrıca odak/tık almaz (MultiTeamSelect ile aynı). */}
                      <Checkbox checked={checked} tabIndex={-1} aria-hidden="true" className="pointer-events-none" />
                      <span className="min-w-0 flex-1 truncate">{o.label}</span>
                      {o.count != null && (
                        <span className="ml-auto pl-2 font-mono text-xs tabular-nums text-muted-foreground" data-slot="facet-count">{o.count}</span>
                      )}
                    </CommandItem>
                  )
                })}
              </CommandGroup>
            )}
            {n > 0 && (
              <>
                <CommandSeparator />
                <CommandGroup>
                  <CommandItem value="__clear__" onMouseDown={(e) => e.preventDefault()} onSelect={() => onChange([])}
                    className="justify-center text-center">
                    {t('ff.clear')}
                  </CommandItem>
                </CommandGroup>
              </>
            )}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  )
}
