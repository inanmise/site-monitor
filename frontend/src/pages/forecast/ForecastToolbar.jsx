import { useId, useState } from 'react'
import { ArrowUpDown, CalendarDays, Check, List, Search, SlidersHorizontal, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { Label } from '@/components/shadcn/label'
import { Sheet, SheetClose, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuLabel, DropdownMenuRadioGroup, DropdownMenuRadioItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import SearchableSelect from '../../components/ui/SearchableSelect.jsx'
import SegmentedControl from '../../components/ui/SegmentedControl.jsx'
import { cn } from '@/lib/utils'

export const SORT_KEYS = ['urgency', 'expiry', 'domain', 'team', 'issuer']

/**
 * Süzgeç çubuğu (2026-09-27):
 *  - Geniş ekran: arama + dört seçici (Takım · UG takımı · Kritiklik · Grup; URL `f_team/f_ug/f_tier/f_group`
 *    korunur) + plan durumu + sıralama menüsü + görünüm anahtarı (Takvim | Liste).
 *  - Telefon (`isMobile`): arama + "Süzgeçler (n)" → alttan açılan Sheet (aynı seçiciler etiketli ve 40 px), sıralama,
 *    görünüm anahtarı tam satır.
 *  - Etkin süzgeç çipleri (tek tek kaldırılır) + "Filtreleri temizle" + "X / Y kayıt".
 */
export default function ForecastToolbar({
  isMobile, filters, onFilter, teamOpts, groupOpts, search, onSearch, plan, onPlan,
  showSort = true, sortKey, onSort, view, onView, chips, onClearAll, shown, total,
}) {
  const t = useT()
  const [sheetOpen, setSheetOpen] = useState(false)
  const uid = useId()
  const facetCount = ['team', 'ugTeam', 'tier', 'group'].filter((k) => filters[k]).length + (plan ? 1 : 0)
  const any = [{ value: '', label: t('inv.filterAny') }]
  const tierOpts = [...any, { value: '1', label: 'T1' }, { value: '2', label: 'T2' }, { value: '3', label: 'T3' }, { value: '4', label: 'T4' }, { value: 'none', label: t('inv.tierNone') }]
  const sortLabel = t(`forecast.sort.${SORT_KEYS.includes(sortKey) ? sortKey : 'urgency'}`)

  const facet = (key, label, options, threshold) => {
    const id = `${uid}-${key}`
    return (
      <div key={key} className={cn('flex min-w-0 flex-col gap-1', isMobile ? 'w-full' : 'w-full sm:w-44')}>
        <Label htmlFor={id} className="text-xs text-muted-foreground">{label}</Label>
        <SearchableSelect id={id} value={filters[key]} onChange={(v) => onFilter({ [key]: v })} options={options} searchThreshold={threshold ?? 4} />
      </div>
    )
  }
  const facets = [
    facet('team', t('inv.filterTeam'), [...any, ...teamOpts]),
    facet('ugTeam', t('inv.filterUgTeam'), [...any, ...teamOpts]),
    facet('tier', t('inv.filterTier'), tierOpts, 99),
    facet('group', t('inv.filterGroup'), [...any, ...groupOpts]),
  ]
  const planCtl = (
    <div className={cn('flex min-w-0 flex-col gap-1', isMobile && 'w-full')}>
      <span className="text-xs text-muted-foreground">{t('forecast.planFacet')}</span>
      <SegmentedControl value={plan} onChange={onPlan} ariaLabel={t('forecast.planFacet')} className="w-fit"
        options={[{ value: '', label: t('forecast.planAny') }, { value: 'planned', label: t('forecast.planPlanned') }, { value: 'unplanned', label: t('forecast.planUnplanned') }]} />
    </div>
  )
  // Sıralama yalnız LİSTEyi etkiler — takvim görünümünde gösterilmez
  const sortMenu = showSort && (
    <DropdownMenu modal={false}>
      <DropdownMenuTrigger asChild>
        <Button type="button" variant="outline" size="sm" className="h-8 pointer-coarse:h-10" data-slot="fc-sort">
          <ArrowUpDown aria-hidden="true" />{t('forecast.sortBy', sortLabel)}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="z-(--z-menu) w-52">
        <DropdownMenuLabel>{t('flt.sort')}</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuRadioGroup value={sortKey} onValueChange={onSort}>
          {SORT_KEYS.map((k) => <DropdownMenuRadioItem key={k} value={k}>{t(`forecast.sort.${k}`)}</DropdownMenuRadioItem>)}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  )
  const viewCtl = (
    <SegmentedControl value={view} onChange={onView} ariaLabel={t('forecast.viewLabel')} className={cn(isMobile ? 'w-full [&>button]:flex-1' : 'ml-auto')}
      options={[{ value: 'calendar', label: t('forecast.viewCalendar'), icon: CalendarDays }, { value: 'list', label: t('forecast.viewList'), icon: List }]} />
  )

  return (
    <div data-slot="fc-toolbar" className="flex min-w-0 flex-col gap-2 print:hidden">
      <div className="flex min-w-0 flex-wrap items-end gap-2">
        <InputGroup className="w-full sm:w-64">
          <InputGroupInput type="search" placeholder={t('forecast.searchPh')} value={search} onChange={(e) => onSearch(e.target.value)} aria-label={t('forecast.searchPh')} />
          <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
        </InputGroup>
        {isMobile ? (
          <>
            <Button type="button" variant="outline" size="sm" className="h-10 flex-1" data-slot="fc-filters-open"
              aria-haspopup="dialog" aria-expanded={sheetOpen} onClick={() => setSheetOpen(true)}>
              <SlidersHorizontal aria-hidden="true" />{t('forecast.filtersOpen')}
              {facetCount > 0 && <Badge variant="secondary" className="rounded-sm px-1.5 font-mono tabular-nums">{facetCount}</Badge>}
            </Button>
            {sortMenu}
            {viewCtl}
          </>
        ) : (
          <>
            {facets}
            {planCtl}
            <div className="flex items-center gap-2 self-end">{sortMenu}</div>
            {viewCtl}
          </>
        )}
      </div>

      <div className="flex min-w-0 flex-wrap items-center gap-1.5">
        {chips.length > 0 && (
          <div role="group" aria-label={t('forecast.activeFilters')} data-slot="fc-chips" className="flex min-w-0 flex-wrap items-center gap-1.5">
            {chips.map((c) => (
              <Button key={c.key} type="button" variant="secondary" size="xs" data-slot="fc-chip"
                className="h-7 max-w-full rounded-full pr-1.5 font-normal pointer-coarse:h-9"
                aria-label={t('forecast.removeFilter', c.label)} title={t('forecast.removeFilter', c.label)} onClick={c.onRemove}>
                <span className="truncate">{c.label}</span><X aria-hidden="true" />
              </Button>
            ))}
            <Button type="button" variant="link" size="xs" className="h-7 px-1 pointer-coarse:h-9" onClick={onClearAll}>{t('app.clearFilters')}</Button>
          </div>
        )}
        <span role="status" data-slot="fc-count" className="text-xs text-muted-foreground sm:ml-auto">{t('inv.shownOf', shown, total)}</span>
      </div>

      {isMobile && (
        <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
          {/* Kendi X düğmemiz: Sheet'in yerleşik kapatması data-slot taşımadığından preflight'sız projede tarayıcı
              varsayılanıyla (gri kenarlı kutu) çiziliyordu. */}
          <SheetContent side="bottom" showCloseButton={false} className="max-h-[85dvh] gap-0 rounded-t-xl pb-[env(safe-area-inset-bottom)]">
            <SheetHeader className="relative border-b pr-14">
              <SheetTitle className="text-base">{t('forecast.filtersOpen')}</SheetTitle>
              <SheetDescription>{t('forecast.filtersDesc')}</SheetDescription>
              <SheetClose asChild>
                <Button type="button" variant="ghost" size="icon" className="absolute top-2.5 right-2.5 size-10" aria-label={t('app.close')}><X aria-hidden="true" /></Button>
              </SheetClose>
            </SheetHeader>
            <div data-slot="fc-filter-sheet" className="flex min-h-0 flex-col gap-4 overflow-y-auto p-4">
              {facets}
              {planCtl}
            </div>
            <SheetFooter className="flex-row gap-2 border-t">
              <Button type="button" variant="ghost" className="h-10" onClick={onClearAll}>{t('app.clearFilters')}</Button>
              <Button type="button" className="h-10 flex-1" onClick={() => setSheetOpen(false)}><Check aria-hidden="true" />{t('forecast.showResults', shown)}</Button>
            </SheetFooter>
          </SheetContent>
        </Sheet>
      )}
    </div>
  )
}
