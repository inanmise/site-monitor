import { useId, useState } from 'react'
import { Search, SlidersHorizontal, X } from 'lucide-react'
import { useT } from '../../../../i18n/index.jsx'
import FacetedFilter from '../../../ui/FacetedFilter.jsx'
import { OVER_MODAL_Z } from '../DirectoryParts.jsx'
import { FACETS, SORTS, facetCount, toggleValue } from './dormantModel.js'
import { Button } from '@/components/shadcn/button'
import { FieldLegend, FieldSet } from '@/components/shadcn/field'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Sheet, SheetClose, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle, SheetTrigger } from '@/components/shadcn/sheet'
import { Toggle } from '@/components/shadcn/toggle'
import { cn } from '@/lib/utils'

/**
 * Atıl hesaplar süzgeç çubuğu (2026-10-09) — Kullanıcı Dizini'nin (DirectoryToolbar) dağarcığı, TEK durum (`f`):
 *  • geniş (lg+): arama · dört faset (hareketsizlik, kimlik kaynağı, rol, takım — Popover + Command, çoklu seçim,
 *    seçenek başına "o seçenekle kalacak" sayı) · sıralama;
 *  • dar (< lg): arama + sayaçlı "Süzgeçler (n)" → shadcn Sheet (telefonda alttan, tablette sağdan), içinde sıralama ve
 *    her faset dokunmatik çip kümesi (Toggle, ≥ 40 px). Altında etkin süzgeç çipleri (× ile kaldırılır).
 */

/** Faset başlığı + değer etiketi (çipler, faset listeleri ve Sheet aynı sözlüğü kullanır). */
export function useDormantLabels(options, { roleLabel, sourceLabel }) {
  const t = useT()
  const title = { bucket: t('dorm.facet.bucket'), source: t('uact.colAuthSource'), role: t('uact.colRole'), team: t('uact.colTeam') }
  const teamName = new Map((options?.team || []).map((o) => [o.value, o.name]))
  const value = (facet, v) => {
    if (facet === 'bucket') return t(`dorm.bucket.${v}`)
    if (facet === 'source') return sourceLabel(v)
    if (facet === 'role') return roleLabel(v)
    if (facet === 'team') return v === 'none' ? t('dorm.noTeam') : (teamName.get(v) || String(v).replace(/^name:/, ''))
    return String(v)
  }
  return { title, value }
}

function SearchBox({ value, onChange, className }) {
  const t = useT()
  return (
    <InputGroup className={cn('h-10 lg:pointer-fine:h-8', className)}>
      <InputGroupInput type="search" enterKeyHint="search" value={value} placeholder={t('dorm.searchPh')} aria-label={t('uact.dirSearch')}
        onChange={(e) => onChange(e.target.value)} className="h-full [&::-webkit-search-cancel-button]:hidden" />
      <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
      {value && (
        <InputGroupAddon align="inline-end">
          <InputGroupButton size="icon-xs" className="size-10 lg:pointer-fine:size-6" onClick={() => onChange('')}
            aria-label={t('a11y.rowAction', t('uact.dirSearch'), t('app.clear'))}>
            <X />
          </InputGroupButton>
        </InputGroupAddon>
      )}
    </InputGroup>
  )
}

/** Sıralama seçicisi (NativeSelect — telefonda yerel seçici). */
export function DormantSortSelect({ id, sort, onSort, full = false, className }) {
  const t = useT()
  return (
    <div className={cn('min-w-0', full && 'w-full [&>[data-slot=native-select-wrapper]]:w-full')}>
      <NativeSelect id={id} size="sm" value={sort} aria-label={t('udir.sortLabel')} data-slot="dormant-sort"
        onChange={(e) => onSort(e.target.value)}
        className={cn('data-[size=sm]:h-10 text-base md:text-sm md:pointer-fine:data-[size=sm]:h-8', className)}>
        {SORTS.map((s) => <NativeSelectOption key={s} value={s}>{t(`dorm.sort.${s}`)}</NativeSelectOption>)}
      </NativeSelect>
    </div>
  )
}

/** Etkin süzgeç çipleri — her çip onu KALDIRAN düğme. */
export function DormantChips({ f, labels, onPatch, onClearAll }) {
  const t = useT()
  const chips = []
  if (String(f.q || '').trim()) chips.push({ key: 'q', label: `${t('uact.dirSearch')}: “${f.q.trim()}”`, remove: () => onPatch({ q: '' }) })
  for (const facet of FACETS) {
    for (const v of f[facet] || []) {
      chips.push({ key: `${facet}:${v}`, label: `${labels.title[facet]}: ${labels.value(facet, v)}`, remove: () => onPatch({ [facet]: toggleValue(f[facet], v) }) })
    }
  }
  if (!chips.length) return null
  return (
    <div role="group" aria-label={t('udir.activeFilters')} data-slot="dormant-chips" className="flex flex-wrap items-center gap-1.5">
      {chips.map((c) => (
        <Button key={c.key} type="button" variant="secondary" size="sm" data-chip={c.key} onClick={c.remove}
          aria-label={t('udir.removeFilter', c.label)}
          className="h-10 max-w-full gap-1 rounded-full px-3 font-normal lg:pointer-fine:h-7">
          <span className="min-w-0 truncate">{c.label}</span>
          <X aria-hidden="true" />
        </Button>
      ))}
      <Button type="button" variant="ghost" size="sm" className="h-10 lg:pointer-fine:h-7" onClick={onClearAll}>{t('uact.filterClear')}</Button>
    </div>
  )
}

function FacetChips({ facet, options, labels, value, onChange }) {
  const t = useT()
  const opts = options?.[facet] || []
  if (!opts.length) return null
  return (
    <FieldSet className="min-w-0 gap-0" data-facet={facet}>
      <FieldLegend variant="label" className="mb-2 font-semibold">{labels.title[facet]}</FieldLegend>
      <div className="flex flex-wrap gap-1.5">
        {opts.map((o) => {
          const on = (value || []).includes(String(o.value))
          return (
            <Toggle key={o.value} variant="outline" size="sm" pressed={on} onPressedChange={() => onChange(toggleValue(value, String(o.value)))}
              aria-label={t('udir.facetOption', labels.value(facet, o.value), o.count)}
              className="h-10 gap-1.5 rounded-full px-3 font-normal data-[state=on]:border-primary data-[state=on]:bg-primary/10 data-[state=on]:text-primary">
              <span className="max-w-[14rem] truncate">{labels.value(facet, o.value)}</span>
              <span className="font-mono text-xs text-muted-foreground tabular-nums">{o.count}</span>
            </Toggle>
          )
        })}
      </div>
    </FieldSet>
  )
}

export default function DormantToolbar({ f, onPatch, onClearAll, options, labels, compact, phone, sort, onSort, shown }) {
  const t = useT()
  const uid = useId()
  const [sheetOpen, setSheetOpen] = useState(false)
  const n = facetCount(f)

  if (!compact) {
    return (
      <div role="group" aria-label={t('udir.filters')} data-slot="dormant-toolbar" className="flex flex-wrap items-center gap-2">
        <SearchBox value={f.q} onChange={(q) => onPatch({ q })} className="w-56" />
        {FACETS.map((facet) => (
          (options?.[facet] || []).length > 0 && (
            <FacetedFilter key={facet} title={labels.title[facet]} value={f[facet]} onChange={(next) => onPatch({ [facet]: next })}
              options={options[facet].map((o) => ({ value: String(o.value), label: labels.value(facet, o.value), count: o.count }))} />
          )
        ))}
        <div className="ml-auto flex items-center gap-2">
          <Label htmlFor={`${uid}-sort`} className="text-xs font-medium text-muted-foreground">{t('udir.sortLabel')}</Label>
          <DormantSortSelect id={`${uid}-sort`} sort={sort} onSort={onSort} />
        </div>
      </div>
    )
  }

  return (
    <div data-slot="dormant-toolbar" className="flex items-center gap-2">
      <SearchBox value={f.q} onChange={(q) => onPatch({ q })} className="min-w-0 flex-1" />
      <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
        <SheetTrigger asChild>
          <Button type="button" variant="outline" className="h-10 shrink-0" data-active={n ? 'true' : undefined}>
            <SlidersHorizontal aria-hidden="true" />
            {n ? t('udir.filtersCount', n) : t('udir.filters')}
          </Button>
        </SheetTrigger>
        <SheetContent side={phone ? 'bottom' : 'right'} showCloseButton data-slot="dormant-filter-sheet"
          overlayClassName={OVER_MODAL_Z}
          className={cn(OVER_MODAL_Z, 'gap-0 p-0', phone ? 'max-h-[92dvh] rounded-t-xl' : 'w-full sm:max-w-md')}>
          <SheetHeader className="border-b pr-14">
            <SheetTitle>{t('udir.filters')}</SheetTitle>
            <SheetDescription>{t('udir.resultCount', shown.count, shown.total)}</SheetDescription>
          </SheetHeader>
          <div className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto p-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${uid}-sort`} className="text-sm font-semibold">{t('udir.sortLabel')}</Label>
              <DormantSortSelect id={`${uid}-sort`} sort={sort} onSort={onSort} full className="w-full" />
            </div>
            {FACETS.map((facet) => (
              <FacetChips key={facet} facet={facet} options={options} labels={labels} value={f[facet]}
                onChange={(next) => onPatch({ [facet]: next })} />
            ))}
          </div>
          <SheetFooter className="flex-row gap-2 border-t pb-[max(1rem,env(safe-area-inset-bottom))]">
            <Button type="button" variant="outline" className="h-10 flex-1" disabled={!n && !String(f.q || '').trim()} onClick={onClearAll}>
              {t('uact.filterClear')}
            </Button>
            <SheetClose asChild>
              <Button type="button" className="h-10 flex-1">{t('udir.showResults', shown.count)}</Button>
            </SheetClose>
          </SheetFooter>
        </SheetContent>
      </Sheet>
    </div>
  )
}
