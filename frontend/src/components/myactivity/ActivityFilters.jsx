import { useState } from 'react'
import { Search, SlidersHorizontal, X } from 'lucide-react'
import { formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import DateTimeField from '../ui/DateTimeField.jsx'
import Field from '../ui/Field.jsx'
import { eventLabel } from '../admin/audit/auditFormat.js'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import { cn } from '@/lib/utils'

/**
 * Etkinliklerim süzgeçleri — aralık (24 saat / 7 gün / 30 gün / özel), olay türü, sonuç, sayfa içi arama; etkin
 * süzgeç çipleri + "Tümünü temizle". Değişiklik ANINDA uygulanır (eski "Uygula" düğmesi kalktı).
 *
 * Telefonda (useIsMobile — yapı farkı) aralık görünür kalır, tür/sonuç/arama "Süzgeçler (n)" düğmesinin açtığı alt
 * Sheet'e taşınır; çipler her boyda görünür. Sunucu sözleşmesi: tür ve sonuç TEK değer (tam eşleşme) — çoklu tür
 * seçimi uçta yok. Arama sunucuda yok → yalnız görünen sayfada süzer ve bunu açıkça söyler.
 */

/** SegmentedControl öğeleri h-7; dokunmatikte 40 px hedef (öğe sınıfını üst seçiciyle ezer). */
const SEG_TOUCH = 'max-w-full flex-wrap [&_[data-slot=toggle-group-item]]:pointer-coarse:h-10'
/** Tarih tetiği h-9; dokunmatikte 40 px. */
const DATE_FIELD = 'w-full md:w-auto md:min-w-[200px] [&_[data-slot=date-picker-trigger]]:pointer-coarse:h-10'

function outcomeLabel(o, t) {
  if (o === 'SUCCESS') return t('audit.outcome.success')
  if (o === 'FAILURE') return t('audit.outcome.failure')
  if (o === 'BLOCKED') return t('audit.outcome.blocked')
  return t('myact.outcome.all')
}

export default function ActivityFilters({
  range, onRange, eventType, onEventType, outcome, onOutcome, search, onSearch,
  typeOptions = [], onClearAll, phone = false,
}) {
  const t = useT()
  const [sheetOpen, setSheetOpen] = useState(false)

  const rangeOptions = [
    { value: '24h', label: t('myact.range.24h') },
    { value: '7d', label: t('myact.range.7d') },
    { value: '30d', label: t('myact.range.30d') },
    { value: 'custom', label: t('myact.range.custom') },
  ]
  const outcomeOptions = ['', 'SUCCESS', 'FAILURE', 'BLOCKED'].map((o) => ({ value: o, label: outcomeLabel(o, t) }))
  const eventOptions = [
    { value: '', label: t('myact.allEvents') },
    ...typeOptions.map((type) => ({ value: type, label: eventLabel(type, t) })),
  ]

  // Etkin süzgeç çipleri — her biri tek dokunuşla kaldırılır (tam düğme, ≥ 40 px dokunmatikte)
  const chips = [
    eventType && { key: 'type', label: t('myact.chip.event', eventLabel(eventType, t)), clear: () => onEventType('') },
    outcome && { key: 'outcome', label: t('myact.chip.outcome', outcomeLabel(outcome, t)), clear: () => onOutcome('') },
    search && { key: 'search', label: t('myact.chip.search', search), clear: () => onSearch('') },
    range.preset === 'custom' && range.since && { key: 'since', label: t('myact.chip.from', formatDate(range.since)), clear: () => onRange({ ...range, since: '' }) },
    range.preset === 'custom' && range.until && { key: 'until', label: t('myact.chip.to', formatDate(range.until)), clear: () => onRange({ ...range, until: '' }) },
  ].filter(Boolean)
  const sheetCount = [eventType, outcome, search].filter(Boolean).length

  // ariaLabel her iki yerde de: masaüstünde tek ad kaynağı; alt panelde Field etiketi de bağlı (id) — aynı metin.
  const typeSelect = (id) => (
    <SearchableSelect id={id} ariaLabel={t('flt.eventType')} value={eventType} onChange={(v) => onEventType(v || '')}
      options={eventOptions} placeholder={t('myact.allEvents')} />
  )
  const outcomeControl = (className) => (
    <SegmentedControl value={outcome} onChange={onOutcome} options={outcomeOptions}
      ariaLabel={t('flt.outcome')} className={cn(SEG_TOUCH, className)} />
  )
  const searchBox = (props = {}) => (
    <InputGroup className={props.className}>
      <InputGroupInput type="search" value={search} placeholder={t('myact.searchPh')} id={props.id}
        aria-label={props.id ? undefined : t('myact.searchLabel')} aria-describedby={props.describedBy}
        onChange={(e) => onSearch(e.target.value)} />
      <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
    </InputGroup>
  )

  return (
    <div data-slot="my-activity-filters" className="flex min-w-0 flex-col gap-2.5">
      <div className="flex min-w-0 flex-col gap-2 md:flex-row md:flex-wrap md:items-center">
        <SegmentedControl value={range.preset} options={rangeOptions} ariaLabel={t('act.rangeFilter')} className={SEG_TOUCH}
          onChange={(p) => onRange(p === 'custom' ? { preset: 'custom', since: range.since, until: range.until } : p)} />
        {phone ? (
          <Button type="button" variant="outline" className="h-10 w-full justify-center" onClick={() => setSheetOpen(true)}
            aria-haspopup="dialog">
            <SlidersHorizontal aria-hidden="true" />
            {sheetCount ? t('myact.filtersCount', sheetCount) : t('myact.filters')}
          </Button>
        ) : (
          <>
            <div className="w-full min-w-0 md:w-56">{typeSelect()}</div>
            {outcomeControl()}
            {searchBox({ className: 'w-full md:w-64 md:flex-none' })}
          </>
        )}
      </div>

      {range.preset === 'custom' && (
        <div data-slot="my-activity-custom-range" className="grid min-w-0 grid-cols-1 gap-2 sm:grid-cols-2 md:flex md:flex-wrap md:items-center">
          <DateTimeField className={DATE_FIELD} clearable placeholder={t('audit.since')}
            value={range.since} onChange={(v) => onRange({ ...range, since: v ? v.slice(0, 19) : '' })} />
          <DateTimeField className={DATE_FIELD} clearable placeholder={t('audit.until')}
            value={range.until} min={range.since || undefined}
            onChange={(v) => onRange({ ...range, until: v ? v.slice(0, 19) : '' })} />
        </div>
      )}

      {chips.length > 0 && (
        <div role="group" aria-label={t('myact.activeFilters')} className="flex min-w-0 flex-wrap items-center gap-1.5">
          {chips.map((c) => (
            <Button key={c.key} type="button" variant="secondary" size="sm" data-slot="filter-chip"
              className="h-8 max-w-full rounded-full px-3 font-normal pointer-coarse:h-10"
              aria-label={t('myact.removeFilter', c.label)} onClick={c.clear}>
              <span className="min-w-0 truncate">{c.label}</span>
              <X aria-hidden="true" />
            </Button>
          ))}
          <Button type="button" variant="ghost" size="sm" className="h-8 text-muted-foreground hover:text-destructive pointer-coarse:h-10"
            onClick={onClearAll}>
            {t('myact.clearAll')}
          </Button>
        </div>
      )}

      {phone && (
        <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
          <SheetContent side="bottom" showCloseButton={false}
            className="max-h-[85dvh] gap-0 overflow-y-auto rounded-t-xl p-0 pb-[env(safe-area-inset-bottom)]">
            <SheetHeader className="flex-row items-center justify-between gap-2 border-b px-4 py-2">
              <SheetTitle>{t('myact.filters')}</SheetTitle>
              <SheetDescription className="sr-only">{t('myact.filtersDesc')}</SheetDescription>
              <Button type="button" variant="ghost" size="icon" className="size-10" aria-label={t('app.close')}
                onClick={() => setSheetOpen(false)}>
                <X aria-hidden="true" />
              </Button>
            </SheetHeader>
            <div className="flex flex-col p-4">
              <Field label={t('flt.eventType')}>
                {({ id }) => typeSelect(id)}
              </Field>
              <div className="mb-3.5 flex flex-col gap-1.5">
                <span className="text-sm font-semibold">{t('flt.outcome')}</span>
                {outcomeControl('w-full [&>*]:flex-1')}
              </div>
              <Field label={t('myact.searchLabel')} hint={t('myact.searchScope')} className="mb-0">
                {({ id, describedBy }) => searchBox({ id, describedBy, className: 'h-10' })}
              </Field>
            </div>
            <SheetFooter className="flex-row gap-2 border-t p-4">
              <Button type="button" variant="outline" className="h-10 flex-1" onClick={onClearAll}>{t('myact.clearAll')}</Button>
              <Button type="button" className="h-10 flex-1" onClick={() => setSheetOpen(false)}>{t('myact.showResults')}</Button>
            </SheetFooter>
          </SheetContent>
        </Sheet>
      )}
    </div>
  )
}
