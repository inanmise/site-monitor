import { useState } from 'react'
import { ArrowUpDown, Check, List, Table2, CalendarDays, Search, SlidersHorizontal, X } from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { FieldLegend, FieldSet } from '@/components/shadcn/field'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { Label } from '@/components/shadcn/label'
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuLabel, DropdownMenuRadioGroup, DropdownMenuRadioItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import FacetedFilter from '../ui/FacetedFilter.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'

/**
 * Yenileme Önerileri süzgeç çubuğu (2026-09-26).
 *  - Geniş ekran: arama + dört faset süzgeci (ui/FacetedFilter: Neden · Takım · Grup · Etiket, sayılı çoklu seçim) +
 *    sıralama menüsü + görünüm anahtarı.
 *  - Telefon (`isMobile`, <768): arama + "Süzgeçler (n)" düğmesi → alttan açılan shadcn Sheet (aynı fasetler dokunmaya
 *    uygun onay kutusu listesi olarak; 40 px satırlar). Görünüm farkı davranış olduğu için hook, yerleşim CSS.
 *  - Etkin süzgeç çipleri (tek tek kaldırılır) + "Filtreleri temizle" + "X / Y öneri" sayacı.
 *
 * @param {Array<{key, title, icon, options, value, onChange}>} facets
 * @param {Array<{key, label, onRemove}>} chips
 */
export default function AdviceToolbar({
  t, isMobile, search, onSearch, facets, sortKey, sortOptions, onSort, view, onView, chips, onClearAll, shown, total,
}) {
  const [sheetOpen, setSheetOpen] = useState(false)
  const facetCount = facets.reduce((n, f) => n + f.value.length, 0)
  const sortLabel = sortOptions.find((o) => o.value === sortKey)?.label ?? sortKey

  return (
    <div data-slot="rn-toolbar" className="flex min-w-0 flex-col gap-2">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <InputGroup className="w-full sm:w-64">
          <InputGroupInput type="search" placeholder={t('renewal.searchPlaceholder')} value={search}
            onChange={(e) => onSearch(e.target.value)} aria-label={t('renewal.searchPlaceholder')} />
          <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
        </InputGroup>

        {isMobile ? (
          <Button type="button" variant="outline" size="sm" className="h-10" data-slot="rn-filters-open"
            aria-haspopup="dialog" aria-expanded={sheetOpen} onClick={() => setSheetOpen(true)}>
            <SlidersHorizontal aria-hidden="true" />{t('renewal.filters')}
            {facetCount > 0 && <Badge variant="secondary" className="rounded-sm px-1.5 font-mono tabular-nums">{facetCount}</Badge>}
          </Button>
        ) : (
          facets.map((f) => (
            <FacetedFilter key={f.key} title={f.title} icon={f.icon} options={f.options} value={f.value} onChange={f.onChange} />
          ))
        )}

        <DropdownMenu modal={false}>
          <DropdownMenuTrigger asChild>
            <Button type="button" variant="outline" size="sm" className="h-8 pointer-coarse:h-10" data-slot="rn-sort">
              <ArrowUpDown aria-hidden="true" />{t('renewal.sortBy', sortLabel)}
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="z-(--z-menu) w-56">
            <DropdownMenuLabel>{t('renewal.sort')}</DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuRadioGroup value={sortKey} onValueChange={onSort}>
              {sortOptions.map((o) => <DropdownMenuRadioItem key={o.value} value={o.value}>{o.label}</DropdownMenuRadioItem>)}
            </DropdownMenuRadioGroup>
          </DropdownMenuContent>
        </DropdownMenu>

        <SegmentedControl value={view} onChange={onView} ariaLabel={t('renewal.viewLabel')} className="md:ml-auto"
          options={[
            { value: 'list', label: t('renewal.viewList'), icon: List },
            { value: 'table', label: t('renewal.viewTable'), icon: Table2 },
            { value: 'calendar', label: t('renewal.viewCalendar'), icon: CalendarDays },
          ]} />
      </div>

      <div className="flex min-w-0 flex-wrap items-center gap-1.5">
        {chips.length > 0 && (
          <div role="group" aria-label={t('renewal.activeFilters')} data-slot="rn-chips" className="flex min-w-0 flex-wrap items-center gap-1.5">
            {chips.map((c) => (
              <Button key={c.key} type="button" variant="secondary" size="xs" data-slot="rn-chip"
                className="h-7 max-w-full rounded-full pr-1.5 font-normal pointer-coarse:h-9"
                aria-label={t('renewal.removeFilter', c.label)} title={t('renewal.removeFilter', c.label)} onClick={c.onRemove}>
                <span className="truncate">{c.label}</span><X aria-hidden="true" />
              </Button>
            ))}
            <Button type="button" variant="link" size="xs" className="h-7 px-1 pointer-coarse:h-9" onClick={onClearAll}>{t('app.clearFilters')}</Button>
          </div>
        )}
        <span role="status" data-slot="rn-count" className="text-xs text-muted-foreground sm:ml-auto">{t('renewal.shown', shown, total)}</span>
      </div>

      {isMobile && (
        <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
          <SheetContent side="bottom" className="max-h-[85dvh] gap-0 rounded-t-xl pb-[env(safe-area-inset-bottom)]">
            <SheetHeader className="border-b">
              <SheetTitle>{t('renewal.filters')}</SheetTitle>
              <SheetDescription>{t('renewal.filtersDesc')}</SheetDescription>
            </SheetHeader>
            <div data-slot="rn-filter-sheet" className="flex min-h-0 flex-col gap-5 overflow-y-auto p-4">
              {facets.map((f) => <FacetChecklist key={f.key} facet={f} />)}
            </div>
            <SheetFooter className="flex-row gap-2 border-t">
              <Button type="button" variant="ghost" className="h-10" onClick={onClearAll}>{t('app.clearFilters')}</Button>
              <Button type="button" className="h-10 flex-1" onClick={() => setSheetOpen(false)}>
                <Check aria-hidden="true" />{t('renewal.showResults', shown)}
              </Button>
            </SheetFooter>
          </SheetContent>
        </Sheet>
      )}
    </div>
  )
}

/** Telefon süzgeç çekmecesindeki faset: dokunmaya uygun onay kutusu listesi (satır 40 px), sayılı. */
function FacetChecklist({ facet }) {
  if (!facet.options.length) return null
  const toggle = (v, on) => facet.onChange(on ? [...facet.value, v] : facet.value.filter((x) => x !== v))
  return (
    <FieldSet className="gap-1.5">
      <FieldLegend variant="label" className="mb-1 flex items-center gap-1.5">
        {facet.icon && <facet.icon aria-hidden="true" className="size-4 text-muted-foreground" />}{facet.title}
      </FieldLegend>
      <div className="flex flex-col">
        {facet.options.map((o) => {
          const on = facet.value.includes(o.value)
          return (
            <Label key={String(o.value)} className="min-h-10 cursor-pointer gap-3 rounded-md px-2 font-normal hover:bg-muted/60">
              <Checkbox checked={on} onCheckedChange={(v) => toggle(o.value, v === true)} />
              <span className="min-w-0 flex-1 truncate">{o.label}</span>
              {o.count != null && <span className="font-mono text-xs text-muted-foreground tabular-nums">{o.count}</span>}
            </Label>
          )
        })}
      </div>
    </FieldSet>
  )
}
