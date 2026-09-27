import { useEffect, useId, useRef, useState } from 'react'
import { Globe, ListFilter, Search, ShieldAlert, SlidersHorizontal, User, X } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import DateTimeField from '../../ui/DateTimeField.jsx'
import FacetedFilter from '../../ui/FacetedFilter.jsx'
import { OUTCOME_KEYS } from './auditFormat.js'
import { OUTCOMES, RANGE_PRESETS, splitTypes } from './auditFilters.js'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Sheet, SheetClose, SheetContent, SheetFooter, SheetHeader, SheetTitle, SheetTrigger } from '@/components/shadcn/sheet'
import { Toggle } from '@/components/shadcn/toggle'
import { cn } from '@/lib/utils'

/** Metin süzgeçleri (arama/kullanıcı/IP) yazarken her tuşta istek atmasın: 500 ms sessizlikte ya da Enter'da uygulanır. */
const DEBOUNCE_MS = 500

/**
 * Gecikmeli metin süzgeci — shadcn InputGroup (ikon + doluysa temizle). Dışarıdan değer değişirse (çip kaldırıldı,
 * kart/görünüm uygulandı) kutu ona uyar; bekleyen gecikme o anda düşer.
 */
function TextFilter({ id, value, onCommit, placeholder, ariaLabel, icon: Icon, className, inputMode }) {
  const t = useT()
  const [local, setLocal] = useState(value)
  const committed = useRef(value)
  const commitRef = useRef(onCommit)
  commitRef.current = onCommit

  useEffect(() => {
    if (value !== committed.current) { committed.current = value; setLocal(value) }
  }, [value])

  useEffect(() => {
    if (local === committed.current) return undefined
    const timer = setTimeout(() => { committed.current = local; commitRef.current(local) }, DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [local])

  function commitNow(v) { committed.current = v; setLocal(v); commitRef.current(v) }

  return (
    <InputGroup className={cn('h-10 md:h-8', className)}>
      <InputGroupInput id={id} value={local} placeholder={placeholder} aria-label={ariaLabel} inputMode={inputMode}
        onChange={(e) => setLocal(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); commitNow(local) } }} />
      <InputGroupAddon><Icon aria-hidden="true" /></InputGroupAddon>
      {local && (
        <InputGroupAddon align="inline-end">
          <InputGroupButton size="icon-xs" className="max-md:size-8" onClick={() => commitNow('')}
            aria-label={t('a11y.rowAction', ariaLabel, t('app.clear'))}>
            <X />
          </InputGroupButton>
        </InputGroupAddon>
      )}
    </InputGroup>
  )
}

function RangeSelect({ id, value, onChange, className }) {
  const t = useT()
  return (
    <NativeSelect id={id} size="sm" value={value} aria-label={t('audit.range')} onChange={(e) => onChange(e.target.value)}
      className={cn('h-10 md:h-8', className)}>
      <NativeSelectOption value="">{t('audit.range.any')}</NativeSelectOption>
      {RANGE_PRESETS.map(r => <NativeSelectOption key={r.key} value={r.key}>{t(r.labelKey)}</NativeSelectOption>)}
      <NativeSelectOption value="custom">{t('audit.range.custom')}</NativeSelectOption>
    </NativeSelect>
  )
}

function OutcomeSelect({ id, value, onChange, className }) {
  const t = useT()
  return (
    <NativeSelect id={id} size="sm" value={value} aria-label={t('audit.outcomeFilter')} onChange={(e) => onChange(e.target.value)}
      className={cn('h-10 md:h-8', className)}>
      <NativeSelectOption value="">{t('audit.allOutcomesShort')}</NativeSelectOption>
      {OUTCOMES.map(o => <NativeSelectOption key={o} value={o}>{t(OUTCOME_KEYS[o])}</NativeSelectOption>)}
    </NativeSelect>
  )
}

/** Etkin süzgeç çipleri — her çip onu KALDIRAN bir düğme (adı ne kaldırdığını söyler) + "Tümünü temizle". */
export function ActiveFilterChips({ chips, onRemove, onClearAll }) {
  const t = useT()
  if (!chips.length) return null
  return (
    <div role="group" aria-label={t('audit.activeFilters')} className="flex flex-wrap items-center gap-1.5">
      {chips.map(c => (
        <Button key={c.key} type="button" variant="secondary" size="sm" data-chip={c.key} onClick={() => onRemove(c)}
          aria-label={t('audit.removeFilter', c.label)}
          className="h-10 max-w-full gap-1 rounded-full px-3 font-normal sm:h-7">
          <span className="min-w-0 truncate">{c.label}</span>
          <X aria-hidden="true" />
        </Button>
      ))}
      <Button type="button" variant="ghost" size="sm" className="h-10 sm:h-7" onClick={onClearAll}>{t('audit.clearAll')}</Button>
    </div>
  )
}

/**
 * Süzgeç çubuğu. Masaüstü/tablet: tek sarılan satır (arama · zaman · olay türü (faset) · sonuç · kullanıcı · IP ·
 * anomali). Telefon (<768, `isMobile`): arama + "Filtreler (n)" düğmesi → alttan Sheet (aynı kontroller, etiketli,
 * canlı uygulanır; "N sonucu göster" kapatır). Hepsi `filters`'ı `onChange(patch)` ile günceller.
 */
export default function AuditFilterBar({ filters, onChange, eventOptions, isMobile, activeCount, total, onClearAll }) {
  const t = useT()
  const uid = useId()
  const [sheetOpen, setSheetOpen] = useState(false)
  const types = splitTypes(filters.eventType)
  const custom = filters.range === 'custom'
  const set = (patch) => onChange(patch)

  const search = (cls) => (
    <TextFilter id={`${uid}-q`} value={filters.q} onCommit={(v) => set({ q: v })} icon={Search}
      placeholder={t('audit.searchPh')} ariaLabel={t('audit.searchLabel')} className={cls} />
  )
  const range = (cls) => (
    <RangeSelect id={`${uid}-range`} value={filters.range} className={cls}
      onChange={(v) => set(v === 'custom' ? { range: 'custom' } : { range: v, since: '', until: '' })} />
  )
  const customPickers = (cls) => custom && (
    <>
      <DateTimeField className={cls} clearable placeholder={t('audit.since')}
        value={filters.since} onChange={(v) => set({ range: 'custom', since: v ? v.slice(0, 19) : '' })} />
      <DateTimeField className={cls} clearable placeholder={t('audit.until')} min={filters.since || undefined}
        value={filters.until} onChange={(v) => set({ range: 'custom', until: v ? v.slice(0, 19) : '' })} />
    </>
  )
  const events = (cls) => (
    <FacetedFilter title={t('audit.eventTypes')} icon={ListFilter} options={eventOptions} value={types}
      searchPlaceholder={t('audit.eventTypesSearch')} className={cn('h-10 md:h-8', cls)}
      onChange={(next) => set({ eventType: next.join(',') })} />
  )
  const outcome = (cls) => (
    <OutcomeSelect id={`${uid}-outcome`} value={filters.outcome} className={cls} onChange={(v) => set({ outcome: v })} />
  )
  const actor = (cls) => (
    <TextFilter id={`${uid}-actor`} value={filters.actor} onCommit={(v) => set({ actor: v })} icon={User}
      placeholder={t('audit.filterActor')} ariaLabel={t('audit.actorLabel')} className={cls} />
  )
  const ip = (cls) => (
    <TextFilter id={`${uid}-ip`} value={filters.ip} onCommit={(v) => set({ ip: v })} icon={Globe} inputMode="decimal"
      placeholder={t('audit.filterIp')} ariaLabel={t('audit.ipLabel')} className={cls} />
  )
  const anomalies = (cls) => (
    <Toggle variant="outline" size="sm" pressed={!!filters.anomalyOnly} onPressedChange={(v) => set({ anomalyOnly: v })}
      className={cn('h-10 px-3 md:h-8 data-[state=on]:border-primary data-[state=on]:bg-primary/10 data-[state=on]:text-primary', cls)}>
      <ShieldAlert aria-hidden="true" /> {t('audit.anomalyOnly')}
    </Toggle>
  )

  if (isMobile) {
    return (
      <div className="flex gap-2">
        {search('min-w-0 flex-1')}
        <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
          <SheetTrigger asChild>
            <Button type="button" variant="outline" className="h-10 shrink-0" data-active={activeCount ? 'true' : undefined}>
              <SlidersHorizontal aria-hidden="true" />
              {activeCount ? t('audit.filtersCount', activeCount) : t('audit.filters')}
            </Button>
          </SheetTrigger>
          <SheetContent side="bottom" aria-describedby={undefined}
            className="max-h-[92dvh] gap-0 rounded-t-xl p-0">
            <SheetHeader className="border-b pr-12">
              <SheetTitle>{t('audit.filters')}</SheetTitle>
            </SheetHeader>
            <div className="flex min-h-0 flex-col gap-4 overflow-y-auto p-4">
              <MobileField label={t('audit.range')} htmlFor={`${uid}-range`}>
                {range('w-full')}
                {custom && <div className="mt-2 grid grid-cols-1 gap-2">{customPickers('w-full')}</div>}
              </MobileField>
              <MobileField label={t('audit.eventTypes')}>{events('w-full justify-start')}</MobileField>
              <MobileField label={t('audit.outcomeFilter')} htmlFor={`${uid}-outcome`}>{outcome('w-full')}</MobileField>
              <MobileField label={t('audit.actorLabel')} htmlFor={`${uid}-actor`}>{actor('w-full')}</MobileField>
              <MobileField label={t('audit.ipLabel')} htmlFor={`${uid}-ip`}>{ip('w-full')}</MobileField>
              {anomalies('w-full justify-start')}
            </div>
            <SheetFooter className="flex-row gap-2 border-t pb-[max(1rem,env(safe-area-inset-bottom))]">
              <Button type="button" variant="outline" className="h-10 flex-1" disabled={!activeCount} onClick={onClearAll}>
                {t('audit.clearAll')}
              </Button>
              <SheetClose asChild>
                <Button type="button" className="h-10 flex-1">{t('audit.showResults', total ?? 0)}</Button>
              </SheetClose>
            </SheetFooter>
          </SheetContent>
        </Sheet>
      </div>
    )
  }

  return (
    <div role="group" aria-label={t('audit.filters')} className="flex flex-wrap items-center gap-2">
      {search('w-full sm:w-64')}
      {range('')}
      {customPickers('w-full sm:w-auto sm:min-w-44')}
      {events('')}
      {outcome('')}
      {actor('w-full sm:w-44')}
      {ip('w-full sm:w-40')}
      {anomalies('')}
    </div>
  )
}

function MobileField({ label, htmlFor, children }) {
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <Label htmlFor={htmlFor} className="text-sm font-semibold">{label}</Label>
      {children}
    </div>
  )
}
