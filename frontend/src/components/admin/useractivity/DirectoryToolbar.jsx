import { useId, useState } from 'react'
import { Search, SlidersHorizontal, X } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import FacetedFilter from '../../ui/FacetedFilter.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import { OVER_MODAL_Z } from './DirectoryParts.jsx'
import { FACETS, SORT_OPTIONS, facetCount, sortFromOption, sortToOption, toggleValue } from './directoryModel.js'
import { Button } from '@/components/shadcn/button'
import { FieldLegend, FieldSet } from '@/components/shadcn/field'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Sheet, SheetClose, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle, SheetTrigger } from '@/components/shadcn/sheet'
import { Toggle } from '@/components/shadcn/toggle'
import { cn } from '@/lib/utils'

/**
 * Kullanıcı Dizini süzgeç çubuğu (2026-09-28). İki düzen, TEK durum (`f`):
 *  • geniş (lg+): arama · Tümü/Çevrimiçi/Çevrimdışı · altı faset (Hesap, Rol, Takım, Kimlik kaynağı, Tur, Son giriş —
 *    shadcn "faceted filter": Popover + Command, çoklu seçim, seçenek başına sayı);
 *  • dar (< lg): arama + sayaçlı "Süzgeçler (n)" düğmesi → shadcn Sheet (telefonda alttan, tablette sağdan); içinde
 *    görünüm, sıralama ve her faset dokunmatik çip kümesi (Toggle, `aria-pressed`) — iç içe açılır liste yok.
 * Altında etkin süzgeç çipleri (× ile kaldırılır) + "Süzgeçleri temizle". Arama telefonda 16 px (iOS yakınlaştırmaz).
 */

/** Faset başlığı + değer etiketi (çipler, faset listeleri ve Sheet aynı sözlüğü kullanır). */
export function useFacetLabels(options) {
  const t = useT()
  const title = {
    account: t('uact.colAccount'), role: t('uact.colRole'), team: t('uact.colTeam'),
    provider: t('uact.colAuthSource'), tour: t('uact.colTour'), login: t('udir.lastSignIn'),
  }
  const teamName = new Map((options?.team || []).map((o) => [o.value, o.name]))
  const value = (facet, v) => {
    if (facet === 'account') return t({ active: 'usr.active', inactive: 'usr.inactive', locked: 'usr.permLocked' }[v] || v)
    if (facet === 'provider') return v === 'LDAP' ? 'LDAP' : t('usr.authLocal')
    if (facet === 'tour') return t(`uact.tour.${v}`)
    if (facet === 'login') return t(`udir.login.${v}`)
    if (facet === 'team') return teamName.get(String(v)) || String(v)
    return String(v)
  }
  return { title, value }
}

function SearchBox({ value, onChange, className }) {
  const t = useT()
  return (
    <InputGroup className={cn('h-10 lg:pointer-fine:h-8', className)}>
      {/* type=search: rol "searchbox" + mobil klavyede "Ara"; tarayıcının kendi × düğmesi gizli (bizimki var — çift × olmasın). */}
      <InputGroupInput type="search" enterKeyHint="search" value={value} placeholder={t('uact.dirSearchPh')} aria-label={t('uact.dirSearch')}
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

function ViewControl({ value, onChange, className }) {
  const t = useT()
  return (
    <SegmentedControl ariaLabel={t('uact.dirView')} value={value} onChange={onChange} className={className}
      options={[{ value: 'all', label: t('uact.dirAll') }, { value: 'online', label: t('uact.dirOnline') }, { value: 'offline', label: t('uact.dirOffline') }]} />
  )
}

/** Etkin süzgeç çipleri — her çip onu KALDIRAN düğme (adı neyi kaldırdığını söyler). */
export function ActiveFilterChips({ f, labels, onPatch, onClearAll }) {
  const t = useT()
  const chips = []
  if (String(f.q || '').trim()) chips.push({ key: 'q', label: `${t('uact.dirSearch')}: “${f.q.trim()}”`, remove: () => onPatch({ q: '' }) })
  if (f.view && f.view !== 'all') chips.push({ key: 'view', label: f.view === 'online' ? t('uact.dirOnline') : t('uact.dirOffline'), remove: () => onPatch({ view: 'all' }) })
  for (const facet of FACETS) {
    for (const v of f[facet] || []) {
      chips.push({ key: `${facet}:${v}`, label: `${labels.title[facet]}: ${labels.value(facet, v)}`, remove: () => onPatch({ [facet]: toggleValue(f[facet], v) }) })
    }
  }
  if (!chips.length) return null
  return (
    <div role="group" aria-label={t('udir.activeFilters')} data-slot="udir-chips" className="flex flex-wrap items-center gap-1.5">
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

/** Sıralama seçicisi (kart görünümü + Sheet): `kolon:yön` seçenekleri. */
export function SortSelect({ id, sort, onSort, full = false, className }) {
  const t = useT()
  return (
    // NativeSelect sarmalayıcısı `w-fit`: tam genişlik istenince sarmalayıcı da genişler (sınıf `<select>`'e gider).
    <div className={cn('min-w-0', full && 'w-full [&>[data-slot=native-select-wrapper]]:w-full')}>
      <NativeSelect id={id} size="sm" value={sortToOption(sort)} aria-label={t('udir.sortLabel')}
        onChange={(e) => onSort(sortFromOption(e.target.value))}
        className={cn('data-[size=sm]:h-10 text-base md:text-sm md:pointer-fine:data-[size=sm]:h-8', className)}>
        {SORT_OPTIONS.map((o) => <NativeSelectOption key={o} value={o}>{t(`udir.sortOpt.${o.replace(':', '_')}`)}</NativeSelectOption>)}
      </NativeSelect>
    </div>
  )
}

/** Telefon/tablet Sheet'inde bir faset: başlık + dokunmatik çip kümesi (sayılı). */
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
            <Toggle key={o.value} variant="outline" size="sm" pressed={on} onPressedChange={() => onChange(toggleValue(value, o.value))}
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

export default function DirectoryToolbar({ f, onPatch, onClearAll, options, labels, compact, phone, sort, onSort, shown }) {
  const t = useT()
  const uid = useId()
  const [sheetOpen, setSheetOpen] = useState(false)
  const n = facetCount(f)

  if (!compact) {
    return (
      <div role="group" aria-label={t('udir.filters')} data-slot="udir-toolbar" className="flex flex-wrap items-center gap-2">
        <SearchBox value={f.q} onChange={(q) => onPatch({ q })} className="w-52 xl:w-56" />
        <ViewControl value={f.view} onChange={(view) => onPatch({ view })} />
        {FACETS.map((facet) => (
          (options?.[facet] || []).length > 0 && (
            <FacetedFilter key={facet} title={labels.title[facet]} value={f[facet]} onChange={(next) => onPatch({ [facet]: next })}
              options={options[facet].map((o) => ({ value: String(o.value), label: labels.value(facet, o.value), count: o.count }))} />
          )
        ))}
      </div>
    )
  }

  return (
    <div data-slot="udir-toolbar" className="flex items-center gap-2">
      <SearchBox value={f.q} onChange={(q) => onPatch({ q })} className="min-w-0 flex-1" />
      <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
        <SheetTrigger asChild>
          <Button type="button" variant="outline" className="h-10 shrink-0" data-active={n ? 'true' : undefined}>
            <SlidersHorizontal aria-hidden="true" />
            {n ? t('udir.filtersCount', n) : t('udir.filters')}
          </Button>
        </SheetTrigger>
        <SheetContent side={phone ? 'bottom' : 'right'} showCloseButton data-slot="udir-filter-sheet"
          overlayClassName={OVER_MODAL_Z}
          className={cn(OVER_MODAL_Z, 'gap-0 p-0', phone ? 'max-h-[92dvh] rounded-t-xl' : 'w-full sm:max-w-md')}>
          <SheetHeader className="border-b pr-14">
            <SheetTitle>{t('udir.filters')}</SheetTitle>
            <SheetDescription>{t('udir.resultCount', shown.count, shown.total)}</SheetDescription>
          </SheetHeader>
          <div className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto p-4">
            <div className="flex flex-col gap-2">
              <span className="text-sm font-semibold">{t('uact.dirView')}</span>
              <ViewControl value={f.view} onChange={(view) => onPatch({ view })}
                className="w-full [&>[data-slot=toggle-group-item]]:h-10 [&>[data-slot=toggle-group-item]]:flex-1" />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${uid}-sort`} className="text-sm font-semibold">{t('udir.sortLabel')}</Label>
              <SortSelect id={`${uid}-sort`} sort={sort} onSort={onSort} full className="w-full" />
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
