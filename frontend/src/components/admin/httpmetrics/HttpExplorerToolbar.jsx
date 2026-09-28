import { useId, useState } from 'react'
import { RotateCw, SlidersHorizontal, X } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { Toggle } from '@/components/shadcn/toggle'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import {
  Sheet, SheetClose, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle, SheetTrigger,
} from '@/components/shadcn/sheet'
import TimeRangePicker from '../../ui/TimeRangePicker.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import FacetedFilter from '../../ui/FacetedFilter.jsx'
import SimpleTooltip from '../../ui/SimpleTooltip.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { dateLocale } from '../../../i18n/dateLocale.js'
import { cn } from '@/lib/utils'
import { OVER_MODAL_Z } from './HttpParts.jsx'
import { CLASS_COLOR, STATUS_CLASSES, activeFilterCount } from './httpMetricsModel.js'

/**
 * İstek Gezgini süzgeç çubuğu (pencerenin gövdesinde YAPIŞKAN): aralık (TimeRangePicker — hızlı aralıklar + özel),
 * uç (aranabilir seçim), yöntem ve durum sınıfı (faset süzgeçleri), etkin süzgeç çipleri, otomatik yenileme,
 * son güncelleme ve elle yenileme. Telefonda (< 768 px, `phone`) uç / yöntem / durum tek "Süzgeçler (N)" düğmesinin
 * açtığı alt Sheet'e taşınır (pencerenin ÜSTÜNDE açılır); aralık ve yenile çubukta kalır. Dokunma hedefleri 40 px.
 */
/** Dokunma hedefi: 1024 px altında (telefon + tablet) ve kaba işaretçide 40 px. */
const TOUCH = 'max-lg:h-10 pointer-coarse:h-10'

function Chip({ label, onRemove, t }) {
  return (
    <Button type="button" variant="outline" size="sm" onClick={onRemove} aria-label={t('hreq.f.remove', label)}
      data-slot="hreq-chip"
      className={cn('h-8 max-w-full gap-1 rounded-full border-primary/40 bg-primary/10 px-2.5 text-xs font-normal text-primary hover:bg-primary/15 hover:text-primary', TOUCH)}>
      <span className="min-w-0 truncate">{label}</span>
      <X aria-hidden="true" className="size-3.5 shrink-0" />
    </Button>
  )
}

/** Sheet içindeki çoklu seçim: ToggleGroup (her seçenek 40 px, sarar). */
function ChoiceGroup({ id, label, options, value, onChange }) {
  return (
    <div className="flex flex-col gap-2">
      <span id={id} className="text-sm font-semibold">{label}</span>
      <ToggleGroup type="multiple" variant="outline" spacing={1.5} aria-labelledby={id} value={value} onValueChange={onChange}
        className="flex w-full flex-wrap justify-start">
        {options.map((o) => (
          <ToggleGroupItem key={o.value} value={o.value} aria-label={o.label}
            className="h-10 min-w-16 gap-1.5 rounded-md px-3 text-sm data-[state=on]:border-primary data-[state=on]:bg-primary/10 data-[state=on]:text-primary">
            {o.dot && <span aria-hidden="true" className="size-2 rounded-full bg-(--dot)" style={{ '--dot': o.dot }} />}
            {o.short ?? o.label}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
    </div>
  )
}

export default function HttpExplorerToolbar({
  t, phone, range, onRange, filters, onFilters, endpointOptions, methodOptions,
  live, liveAvailable, onLive, updatedAt, busy, onRefresh, shownCount,
}) {
  const uid = useId().replace(/:/g, '')
  const [sheetOpen, setSheetOpen] = useState(false)
  const n = activeFilterCount(filters)
  const patch = (p) => onFilters({ ...filters, ...p })
  const classOptions = STATUS_CLASSES.map((c) => ({ value: c, label: t(`hreq.class.${c}`), short: c, dot: CLASS_COLOR[c] }))
  const epOptions = [{ value: '', label: t('hreq.f.allEndpoints') }, ...endpointOptions.map((e) => ({ value: e, label: e }))]
  const updated = updatedAt ? new Date(updatedAt).toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit' }) : null

  const endpointSelect = (
    <div className={cn('min-w-0 sm:w-[min(340px,40vw)]', phone ? 'w-full [&_[role=combobox]]:h-10' : 'max-lg:[&_[role=combobox]]:h-10 pointer-coarse:[&_[role=combobox]]:h-10')}>
      <SearchableSelect value={filters.endpoint} onChange={(v) => patch({ endpoint: v || '' })} options={epOptions}
        searchThreshold={2} placeholder={t('hreq.f.allEndpoints')} ariaLabel={t('hreq.f.endpoint')} />
    </div>
  )

  const chips = n > 0 && (
    <div data-slot="hreq-chips" className="flex flex-wrap items-center gap-1.5" role="group" aria-label={t('hreq.f.active')}>
      {filters.endpoint && <Chip t={t} label={filters.endpoint} onRemove={() => patch({ endpoint: '' })} />}
      {filters.methods.map((m) => <Chip key={m} t={t} label={m} onRemove={() => patch({ methods: filters.methods.filter((x) => x !== m) })} />)}
      {filters.classes.map((c) => <Chip key={c} t={t} label={t(`hreq.class.${c}`)} onRemove={() => patch({ classes: filters.classes.filter((x) => x !== c) })} />)}
      <Button type="button" variant="ghost" size="sm" className={cn('h-8 px-2 text-xs text-muted-foreground', TOUCH)}
        onClick={() => onFilters({ endpoint: '', methods: [], classes: [] })}>
        {t('hreq.f.clearAll')}
      </Button>
    </div>
  )

  return (
    <div data-slot="hreq-toolbar"
      className="sticky top-0 z-10 flex flex-col gap-2 border-b bg-background/95 pt-0.5 pb-2.5 backdrop-blur supports-[backdrop-filter]:bg-background/85">
      <div className="flex flex-wrap items-center gap-2">
        <div className={cn('min-w-0 max-sm:basis-full max-sm:[&_[data-slot=time-range-trigger]]:w-full', 'max-lg:[&_[data-slot=time-range-trigger]]:h-10 pointer-coarse:[&_[data-slot=time-range-trigger]]:h-10')}>
          <TimeRangePicker value={range} onChange={onRange} />
        </div>

        {phone ? (
          <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
            <SheetTrigger asChild>
              <Button type="button" variant="outline" className="h-10 shrink-0" data-active={n ? 'true' : undefined}>
                <SlidersHorizontal aria-hidden="true" />
                {n ? t('hreq.f.filtersCount', n) : t('hreq.f.filters')}
              </Button>
            </SheetTrigger>
            <SheetContent side="bottom" showCloseButton data-slot="hreq-filter-sheet" overlayClassName={OVER_MODAL_Z}
              className={cn(OVER_MODAL_Z, 'max-h-[92dvh] gap-0 rounded-t-xl p-0')}>
              <SheetHeader className="border-b pr-14">
                <SheetTitle>{t('hreq.f.filters')}</SheetTitle>
                <SheetDescription>{t('hreq.f.sheetDesc')}</SheetDescription>
              </SheetHeader>
              <div className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto p-4">
                <div className="flex flex-col gap-2">
                  <span className="text-sm font-semibold">{t('hreq.f.endpoint')}</span>
                  {endpointSelect}
                </div>
                <ChoiceGroup id={`${uid}-m`} label={t('hreq.f.method')} value={filters.methods}
                  options={methodOptions.map((m) => ({ value: m, label: m }))} onChange={(v) => patch({ methods: v })} />
                <ChoiceGroup id={`${uid}-c`} label={t('hreq.f.status')} value={filters.classes} options={classOptions}
                  onChange={(v) => patch({ classes: v })} />
              </div>
              <SheetFooter className="flex-row gap-2 border-t pb-[max(1rem,env(safe-area-inset-bottom))]">
                <Button type="button" variant="outline" className="h-10 flex-1" disabled={!n}
                  onClick={() => onFilters({ endpoint: '', methods: [], classes: [] })}>
                  {t('hreq.f.clearAll')}
                </Button>
                <SheetClose asChild>
                  <Button type="button" className="h-10 flex-1">{t('hreq.f.showResults', shownCount)}</Button>
                </SheetClose>
              </SheetFooter>
            </SheetContent>
          </Sheet>
        ) : (
          <>
            {endpointSelect}
            <FacetedFilter title={t('hreq.f.method')} value={filters.methods} onChange={(v) => patch({ methods: v })}
              options={methodOptions.map((m) => ({ value: m, label: m }))} className="h-9 max-lg:h-10 pointer-coarse:h-10" />
            <FacetedFilter title={t('hreq.f.status')} value={filters.classes} onChange={(v) => patch({ classes: v })}
              options={classOptions.map((o) => ({ value: o.value, label: o.label }))} className="h-9 max-lg:h-10 pointer-coarse:h-10" />
          </>
        )}

        <div className="ml-auto flex shrink-0 items-center gap-2">
          <SimpleTooltip content={liveAvailable ? t('hreq.liveHint') : t('hreq.liveOff')}>
            <span className="inline-flex">
              <Toggle variant="outline" size="sm" pressed={live && liveAvailable} disabled={!liveAvailable} onPressedChange={onLive}
                data-slot="hreq-live" aria-label={t('hreq.live')} className="h-9 min-w-9 gap-1.5 px-2.5 max-lg:h-10 max-lg:min-w-10 pointer-coarse:h-10 pointer-coarse:min-w-10">
                <span aria-hidden="true"
                  className={cn('size-1.5 rounded-full', live && liveAvailable ? 'bg-success motion-safe:animate-pulse' : 'bg-muted-foreground/50')} />
                <span className="text-xs max-sm:sr-only">{t('hreq.live')}</span>
              </Toggle>
            </span>
          </SimpleTooltip>
          {updated && (
            <span data-slot="hreq-updated" className="text-xs text-muted-foreground tabular-nums">
              <span className="max-md:sr-only">{t('rtc.updatedLabel')} </span>{updated}
            </span>
          )}
          <SimpleTooltip content={t('http.exp.refresh')}>
            {/* disabled YOK (2026-09-28c ek-5): zaman aşımsız asılı bir istek düğmeyi kalıcı kilitliyordu; yükleyici tur
                korumalı — yeni tıklama eskisini bayat yapar, gösterge yalnız son turda söner. */}
            <Button type="button" variant="outline" size="icon" onClick={onRefresh}
              aria-label={t('http.exp.refresh')} aria-busy={busy || undefined} className="size-9 max-lg:size-10 pointer-coarse:size-10">
              {busy ? <Spinner size={14} decorative /> : <RotateCw aria-hidden="true" className="size-4" />}
            </Button>
          </SimpleTooltip>
        </div>
      </div>
      {chips}
    </div>
  )
}
