// İzleme Panosu sütun süzgeçleri (2026-10-01): başlıkta süzgeç + sıralama, etkin süzgeç çipleri, telefonda süzgeç paneli.
// Hepsi shadcn (Popover + Checkbox / RadioGroup / Input, Sheet); seçenek listesi (FilterOptions) masaüstü açılır
// menüsü ile telefon paneli arasında ORTAK. Çoklu seçimde menü açık kalır (satır satır seçilir).
import { useId, useMemo, useState } from 'react'
import { ArrowDown, ArrowUp, ArrowUpDown, Filter, SlidersHorizontal, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { RadioGroup, RadioGroupItem } from '@/components/shadcn/radio-group'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import { TableHead } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

const ROW = 'flex min-h-9 w-full cursor-pointer items-center gap-2 rounded-md px-2 text-sm hover:bg-accent pointer-coarse:min-h-10'

/**
 * Bir sütunun seçenekleri. {@code kind}: `multi` (onay kutuları; değer dizi), `single` (radyo; değer dizge, "Tümü"
 * ile boşalır), `text` (serbest metin). 8'den çok seçenekte liste içi arama çıkar. Sayı = diğer süzgeçler etkinken kalan.
 */
export function FilterOptions({ group, autoFocus = false }) {
  const t = useT()
  const uid = useId()
  const [needle, setNeedle] = useState('')
  const { kind, options = [], value, onChange, name, label } = group
  const shown = useMemo(() => {
    const n = needle.trim().toLocaleLowerCase('tr')
    return n ? options.filter((o) => String(o.label).toLocaleLowerCase('tr').includes(n)) : options
  }, [options, needle])

  if (kind === 'text') {
    return (
      <Input value={value || ''} onChange={(e) => onChange(e.target.value)} autoFocus={autoFocus}
        placeholder={group.placeholder} aria-label={label} data-slot="mo-col-text" data-column={name} />
    )
  }
  if (kind === 'single') {
    return (
      <RadioGroup value={value || '__all'} onValueChange={(v) => onChange(v === '__all' ? '' : v)} aria-label={label}
        className="gap-0" data-slot="mo-col-options" data-column={name}>
        {[{ value: '__all', label: t('mo.colf.all') }, ...options].map((o) => {
          const id = `${uid}-${o.value}`
          return (
            <Label key={o.value} htmlFor={id} className={cn(ROW, 'font-normal')} data-option={o.value}>
              <RadioGroupItem id={id} value={o.value} />
              <span className="min-w-0 flex-1 truncate">{o.label}</span>
              {o.count != null && <span className="font-mono text-xs text-muted-foreground tabular-nums">{o.count}</span>}
            </Label>
          )
        })}
      </RadioGroup>
    )
  }
  const selected = new Set(value || [])
  const toggle = (v, on) => {
    const next = new Set(selected)
    if (on) next.add(v); else next.delete(v)
    onChange([...next])
  }
  return (
    <div className="flex flex-col gap-1" data-slot="mo-col-options" data-column={name}>
      {options.length > 8 && (
        <Input value={needle} onChange={(e) => setNeedle(e.target.value)} placeholder={t('mo.colf.search')}
          aria-label={t('mo.colf.searchIn', label)} className="h-8" autoFocus={autoFocus} />
      )}
      <div role="group" aria-label={label} className="flex max-h-64 flex-col overflow-y-auto">
        {shown.map((o) => {
          const id = `${uid}-${o.value}`
          const on = selected.has(o.value)
          return (
            <Label key={o.value} htmlFor={id} className={cn(ROW, 'font-normal', !o.count && !on && 'text-muted-foreground')} data-option={o.value}>
              <Checkbox id={id} checked={on} onCheckedChange={(c) => toggle(o.value, c === true)} />
              <span className="min-w-0 flex-1 truncate">{o.label}</span>
              {o.count != null && <span className="font-mono text-xs text-muted-foreground tabular-nums">{o.count}</span>}
            </Label>
          )
        })}
        {shown.length === 0 && <span className="px-2 py-1 text-xs text-muted-foreground">{t('mo.colf.noMatch')}</span>}
      </div>
    </div>
  )
}

export function isGroupActive(g) {
  return g.kind === 'multi' ? (g.value || []).length > 0 : !!g.value
}

/** Başlık süzgeç düğmesi + açılır seçenek listesi. */
export function ColumnFilterButton({ group }) {
  const t = useT()
  const on = isGroupActive(group)
  const n = group.kind === 'multi' ? (group.value || []).length : on ? 1 : 0
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button type="button" variant="ghost" size="icon-xs" data-slot="mo-col-filter" data-column={group.name} data-active={on || undefined}
          aria-label={on ? t('mo.colf.buttonActive', group.label, n) : t('mo.colf.button', group.label)}
          className={cn('relative size-7', on ? 'text-primary' : 'text-muted-foreground opacity-60 hover:opacity-100')}>
          <Filter aria-hidden="true" className={cn('size-3.5', on && 'fill-current')} />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" collisionPadding={8} className="z-(--z-menu) flex w-[min(18rem,calc(100vw-1rem))] flex-col gap-2 p-2">
        <div className="flex items-center justify-between gap-2 px-1">
          <span className="text-xs font-semibold text-muted-foreground">{group.label}</span>
          {on && (
            <Button type="button" variant="ghost" size="xs" className="h-7" onClick={() => group.onChange(group.kind === 'multi' ? [] : '')}>
              {t('mo.colf.clear')}
            </Button>
          )}
        </div>
        <FilterOptions group={group} autoFocus />
      </PopoverContent>
    </Popover>
  )
}

/**
 * Tablo başlığı: sıralama düğmesi (aria-sort `th` üzerinde) + isteğe bağlı süzgeç. Sıralanamayan sütunda yalnız etiket.
 */
export function OverviewColumnHead({ label, sortKey, sort, onSort, filter, className }) {
  const t = useT()
  const sortable = !!(sortKey && onSort)
  const active = sortable && sort?.key === sortKey
  const ariaSort = !sortable ? undefined : active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'
  const Icon = active ? (sort.dir === 'asc' ? ArrowUp : ArrowDown) : ArrowUpDown
  return (
    <TableHead aria-sort={ariaSort} data-sort-key={sortKey || undefined} className={cn('whitespace-nowrap', className)}>
      <span className={cn('inline-flex items-center gap-0.5', className?.includes('text-right') && 'justify-end')}>
        {sortable ? (
          <Button type="button" variant="ghost" size="sm" data-slot="mo-sort" data-sort-key={sortKey} data-active={active || undefined}
            onClick={() => onSort(sortKey)} aria-label={t('mo.sort.by', label)}
            className={cn('-ml-2 h-7 gap-1 px-2 font-medium', active ? 'text-foreground' : 'text-muted-foreground')}>
            {label}<Icon aria-hidden="true" className={cn('size-3.5', !active && 'opacity-50')} />
          </Button>
        ) : <span>{label}</span>}
        {filter && <ColumnFilterButton group={filter} />}
      </span>
    </TableHead>
  )
}

/** Etkin sütun süzgeçleri — çıkarılabilir çipler + "tümünü temizle". */
export function FilterChips({ chips, onClearAll }) {
  const t = useT()
  if (!chips.length) return null
  return (
    <div className="mb-3 flex flex-wrap items-center gap-1.5" data-slot="mo-chips">
      {chips.map((c) => (
        <Badge key={c.key} variant="secondary" data-slot="mo-chip" data-chip={c.key} className="h-7 gap-1 pr-0.5 pl-2 font-normal">
          <span className="text-muted-foreground">{c.label}:</span>
          <span className="max-w-56 truncate font-medium">{c.value}</span>
          <Button type="button" variant="ghost" size="icon-xs" className="size-6 rounded-full pointer-coarse:size-8"
            aria-label={t('mo.colf.remove', c.label)} onClick={c.onRemove}>
            <X aria-hidden="true" className="size-3" />
          </Button>
        </Badge>
      ))}
      <Button type="button" variant="ghost" size="sm" className="h-7 pointer-coarse:h-10" onClick={onClearAll}>{t('mo.colf.clearAll')}</Button>
    </div>
  )
}

/**
 * Süzgeç paneli: sütun süzgeçleri + sıralama tek panelde. Kart görünümünde (başlık yok) ve tablonun dar hâlinde (bazı
 * sütunlar gizli → başlık süzgeçleri de) kullanılır; `className` tetiğin görünürlüğünü kap sorgusuyla yönetir.
 */
export function PhoneFilterSheet({ groups, sort, sortOptions, onSortChange, activeCount, onClearAll, className }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button type="button" variant="outline" size="sm" className={cn('h-10 w-full sm:h-8 sm:w-auto pointer-coarse:h-10', className)} data-slot="mo-phone-filters" onClick={() => setOpen(true)}>
        <SlidersHorizontal aria-hidden="true" />{t('mo.colf.phoneButton')}
        {activeCount > 0 && <Badge variant="default" className="ml-0.5 h-5 min-w-5 rounded-full px-1 tabular-nums">{activeCount}</Badge>}
      </Button>
      <Sheet open={open} onOpenChange={setOpen}>
        <SheetContent side="right" className="w-full gap-0 sm:max-w-sm" data-slot="mo-filter-sheet">
          <SheetHeader className="border-b">
            <SheetTitle>{t('mo.colf.sheetTitle')}</SheetTitle>
            <SheetDescription>{t('mo.colf.sheetDesc')}</SheetDescription>
          </SheetHeader>
          <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-4">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="mo-phone-sort" className="text-xs text-muted-foreground">{t('mo.sort.label')}</Label>
              <NativeSelect id="mo-phone-sort" value={sort} onChange={(e) => onSortChange(e.target.value)} className="w-full" data-slot="mo-phone-sort">
                {sortOptions.map((o) => <NativeSelectOption key={o.value} value={o.value}>{o.label}</NativeSelectOption>)}
              </NativeSelect>
            </div>
            {groups.map((g) => (
              <section key={g.name} aria-label={g.label} className="flex flex-col gap-1" data-slot="mo-sheet-group" data-column={g.name}>
                <div className="flex items-center justify-between">
                  <h3 className="m-0 text-xs font-semibold text-muted-foreground">{g.label}</h3>
                  {isGroupActive(g) && (
                    <Button type="button" variant="ghost" size="sm" className="h-8" onClick={() => g.onChange(g.kind === 'multi' ? [] : '')}>{t('mo.colf.clear')}</Button>
                  )}
                </div>
                <FilterOptions group={g} />
              </section>
            ))}
          </div>
          <div className="flex gap-2 border-t p-4">
            <Button type="button" variant="outline" className="h-10 flex-1" onClick={onClearAll} disabled={!activeCount}>{t('mo.colf.clearAll')}</Button>
            <Button type="button" className="h-10 flex-1" onClick={() => setOpen(false)}>{t('mo.colf.apply')}</Button>
          </div>
        </SheetContent>
      </Sheet>
    </>
  )
}
