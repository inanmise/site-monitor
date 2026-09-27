import { useId, useState } from 'react'
import { ListFilter, Search, SlidersHorizontal, X } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import DateTimeField from '../../ui/DateTimeField.jsx'
import FacetedFilter from '../../ui/FacetedFilter.jsx'
import { RANGES, SOURCES, TIMELINE_KINDS } from './releaseModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Sheet, SheetClose, SheetContent, SheetFooter, SheetHeader, SheetTitle, SheetTrigger } from '@/components/shadcn/sheet'
import { cn } from '@/lib/utils'

/**
 * Sürüm & Dağıtım süzgeç çubuğu (2026-09-27 yeniden tasarım; Denetim Kaydı `audit/AuditFilterBar` deseni).
 * Geniş KAP: tek sarılan satır (arama · ortam · kaynak · tür (yalnız zaman çizelgesi) · zaman aralığı). Dar kap
 * (telefon, kenar çubuğu açık tablet — `narrow`): arama + "Süzgeçler (n)" → alttan Sheet (etiketli, canlı uygulanır).
 * Etkin süzgeçler her iki yerleşimde X'li çip; "Süzgeçleri temizle" aramayı da siler.
 *
 * `filters` = { q, env, source, kinds[], range, from, to } (from/to DateTimeField değeri, UTC eksiz);
 * `onChange(patch)`.
 */

const H = 'h-10 @2xl/deploy:h-8'

function SearchBox({ value, onChange, className }) {
  const t = useT()
  return (
    <InputGroup className={cn(H, className)}>
      <InputGroupInput type="search" value={value} placeholder={t('deploy.search')} aria-label={t('deploy.searchLabel')}
        onChange={(e) => onChange(e.target.value)} />
      <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
      {value && (
        <InputGroupAddon align="inline-end">
          <InputGroupButton size="icon-xs" className="size-8 @2xl/deploy:size-6" onClick={() => onChange('')}
            aria-label={t('a11y.rowAction', t('deploy.searchLabel'), t('app.clear'))}>
            <X />
          </InputGroupButton>
        </InputGroupAddon>
      )}
    </InputGroup>
  )
}

/** Etkin süzgeç çipleri — her çip onu KALDIRAN bir düğme (adı ne kaldırdığını söyler) + "Süzgeçleri temizle". */
export function ActiveChips({ chips, onClearAll }) {
  const t = useT()
  if (!chips.length) return null
  return (
    <div role="group" aria-label={t('deploy.activeFilters')} data-slot="deploy-active-filters" className="flex min-w-0 flex-wrap items-center gap-1.5">
      {chips.map((c) => (
        <Button key={c.key} type="button" variant="secondary" size="sm" data-chip={c.key} onClick={c.onRemove}
          aria-label={t('deploy.removeFilter', c.label)}
          className="h-10 max-w-full gap-1 rounded-full px-3 font-normal @2xl/deploy:h-7">
          <span className="min-w-0 truncate">{c.label}</span>
          <X aria-hidden="true" />
        </Button>
      ))}
      <Button type="button" variant="ghost" size="sm" className="h-10 text-muted-foreground hover:text-destructive @2xl/deploy:h-7"
        onClick={onClearAll}>
        {t('deploy.clearFilters')}
      </Button>
    </div>
  )
}

export default function DeployToolbar({ filters, onChange, environments = [], kindCounts = {}, showKinds, narrow, activeCount, onClearAll }) {
  const t = useT()
  const uid = useId()
  const [sheetOpen, setSheetOpen] = useState(false)
  const custom = filters.range === 'custom'

  const envSelect = (cls) => (
    <NativeSelect id={`${uid}-env`} size={narrow ? 'default' : 'sm'} value={filters.env} aria-label={t('deploy.env')} className={cls}
      onChange={(e) => onChange({ env: e.target.value })}>
      <NativeSelectOption value="">{t('deploy.envAll')}</NativeSelectOption>
      {environments.map((e) => <NativeSelectOption key={e} value={e}>{e}</NativeSelectOption>)}
    </NativeSelect>
  )
  const sourceSelect = (cls) => (
    <NativeSelect id={`${uid}-src`} size={narrow ? 'default' : 'sm'} value={filters.source} aria-label={t('deploy.source')} className={cls}
      onChange={(e) => onChange({ source: e.target.value })}>
      <NativeSelectOption value="">{t('deploy.sourceAllLong')}</NativeSelectOption>
      {SOURCES.map((s) => <NativeSelectOption key={s} value={s}>{t('version.source.' + s)}</NativeSelectOption>)}
    </NativeSelect>
  )
  const rangeSelect = (cls) => (
    <NativeSelect id={`${uid}-range`} size={narrow ? 'default' : 'sm'} value={filters.range} aria-label={t('deploy.range')} className={cls}
      onChange={(e) => onChange({ range: e.target.value })}>
      <NativeSelectOption value="">{t('deploy.range.any')}</NativeSelectOption>
      {RANGES.map((r) => <NativeSelectOption key={r} value={r}>{t('deploy.range.' + r)}</NativeSelectOption>)}
    </NativeSelect>
  )
  const customPickers = (cls) => custom && (
    <>
      <DateTimeField className={cls} clearable placeholder={t('deploy.rangeFrom')} value={filters.from}
        onChange={(v) => onChange({ from: v || '' })} />
      <DateTimeField className={cls} clearable placeholder={t('deploy.rangeTo')} value={filters.to} min={filters.from || undefined}
        onChange={(v) => onChange({ to: v || '' })} />
    </>
  )
  const kindFacet = (cls) => showKinds && (
    <FacetedFilter title={t('deploy.col.kind')} icon={ListFilter} value={filters.kinds} searchPlaceholder={t('deploy.kindSearch')}
      className={cls} onChange={(next) => onChange({ kinds: next })}
      options={TIMELINE_KINDS.map((k) => ({ value: k, label: t('version.kind.' + k), count: kindCounts[k] ?? 0 }))} />
  )

  if (narrow) {
    return (
      <div className="flex min-w-0 gap-2">
        <SearchBox value={filters.q} onChange={(q) => onChange({ q })} className="min-w-0 flex-1" />
        <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
          <SheetTrigger asChild>
            <Button type="button" variant="outline" className="h-10 shrink-0" data-filters-trigger=""
              data-active={activeCount ? 'true' : undefined}>
              <SlidersHorizontal aria-hidden="true" />
              {t('deploy.filters')}
              {activeCount > 0 && (
                <>
                  <Badge variant="secondary" aria-hidden="true" className="rounded-sm px-1.5 tabular-nums">{activeCount}</Badge>
                  <span className="sr-only">{t('deploy.filtersActive', activeCount)}</span>
                </>
              )}
            </Button>
          </SheetTrigger>
          <SheetContent side="bottom" aria-describedby={undefined} data-slot="deploy-filter-sheet"
            className="max-h-[92dvh] gap-0 rounded-t-xl p-0">
            <SheetHeader className="border-b pr-12">
              <SheetTitle>{t('deploy.filters')}</SheetTitle>
            </SheetHeader>
            <div className="flex min-h-0 flex-col gap-4 overflow-y-auto p-4 *:data-[slot=native-select-wrapper]:w-full">
              <SheetField label={t('deploy.env')} htmlFor={`${uid}-env`}>{envSelect('h-10 w-full')}</SheetField>
              <SheetField label={t('deploy.source')} htmlFor={`${uid}-src`}>{sourceSelect('h-10 w-full')}</SheetField>
              {showKinds && <SheetField label={t('deploy.col.kind')}>{kindFacet('h-10 w-full justify-start')}</SheetField>}
              <SheetField label={t('deploy.range')} htmlFor={`${uid}-range`}>
                {rangeSelect('h-10 w-full')}
                {custom && <div className="mt-2 grid grid-cols-1 gap-2">{customPickers('w-full')}</div>}
              </SheetField>
            </div>
            <SheetFooter className="flex-row gap-2 border-t pb-[max(1rem,env(safe-area-inset-bottom))]">
              <Button type="button" variant="outline" className="h-10 flex-1" disabled={!activeCount} onClick={onClearAll}>
                {t('deploy.clearFilters')}
              </Button>
              <SheetClose asChild>
                <Button type="button" className="h-10 flex-1">{t('deploy.showResults')}</Button>
              </SheetClose>
            </SheetFooter>
          </SheetContent>
        </Sheet>
      </div>
    )
  }

  return (
    <div role="group" aria-label={t('deploy.filters')} className="flex min-w-0 flex-wrap items-center gap-2">
      <SearchBox value={filters.q} onChange={(q) => onChange({ q })} className="w-64 max-w-full" />
      {envSelect('h-8')}
      {sourceSelect('h-8')}
      {kindFacet('h-8')}
      {rangeSelect('h-8')}
      {customPickers('w-auto min-w-44')}
    </div>
  )
}

/** Sheet içindeki etiketli alan (kontrol `htmlFor` ile bağlı; faset tetiği kendi adını taşır). */
function SheetField({ label, htmlFor, children }) {
  return (
    <div className="flex min-w-0 flex-col gap-1.5 *:data-[slot=native-select-wrapper]:w-full">
      <Label htmlFor={htmlFor} className="text-sm font-semibold">{label}</Label>
      {children}
    </div>
  )
}
