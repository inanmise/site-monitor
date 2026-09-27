import { useEffect, useState } from 'react'
import { Search, SlidersHorizontal, X, FilterX } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import DateTimeField from '../ui/DateTimeField.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { InputGroup, InputGroupInput, InputGroupAddon } from '@/components/shadcn/input-group'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetFooter, SheetClose } from '@/components/shadcn/sheet'
import { Label } from '@/components/shadcn/label'
import { cn } from '@/lib/utils'
import { statusOf, activeFilters, SEVERITY_ORDER, severityMeta } from './incidentsModel.js'

/** Seçicilerin ortak kabı: NativeSelect sarmalayıcısı `w-fit` — telefonda tam genişlik için `*:w-full`. */
const SEL = 'w-full *:w-full sm:w-auto'

/**
 * Süzgeç araç çubuğu — masaüstünde tek sarılan satır; telefonda arama + "Süzgeçler" düğmesi (alt Sheet).
 * Etkin süzgeçler aşağıda çip olarak listelenir (× ile tek tek, "Temizle" ile hepsi). Kök neden çipleri
 * (canlı sayı, sunucudan) telefonda yatay kayan tek satır, geniş ekranda sarar.
 *
 * Sunucu yalnız durum / kök neden / arama / tarih süzer; önem ve takım YÜKLENEN SAYFAYA uygulanır — bunu
 * sayfa gövdesindeki not söyler (`incov.pageFacetNote`), araç çubuğu ayrım yapmadan aynı görünümde sunar.
 */
export default function IncidentsToolbar({ filters, patch, reset, typeCounts, total, teams, phone, labelsFor }) {
  const t = useT()
  const [sheetOpen, setSheetOpen] = useState(false)
  // Arama kutusu kontrollü: dış süzgeç (çip ×, Temizle, URL) değişince taslak da onu izler — ref gerekmez
  // (InputGroupInput forwardRef DEĞİL; ref verilse düşer). Uygulama Enter / odak kaybında (her tuşta istek yok).
  const [draft, setDraft] = useState(filters.q)
  useEffect(() => { setDraft(filters.q) }, [filters.q])
  const applySearch = () => { const v = draft.trim(); if (v !== filters.q) patch({ q: v }) }
  const active = activeFilters(filters)
  const status = statusOf(filters.stat)
  const pills = Object.entries(typeCounts || {}).sort((a, b) => b[1] - a[1])
  const typeLabel = (type) => (t(`incov.type.${type}`) !== `incov.type.${type}` ? t(`incov.type.${type}`) : type)

  const statusSelect = (
    <div className={SEL}>
      <NativeSelect aria-label={t('incov.colStatus')} value={status}
        onChange={(e) => patch({ stat: e.target.value === 'ongoing' ? 'open' : e.target.value === 'resolved' ? 'resolved' : '' })}>
        <NativeSelectOption value="all">{t('incov.statusAll')}</NativeSelectOption>
        <NativeSelectOption value="ongoing">{t('incov.ongoing')}</NativeSelectOption>
        <NativeSelectOption value="resolved">{t('incov.resolved')}</NativeSelectOption>
      </NativeSelect>
    </div>
  )
  const severitySelect = (
    <div className={SEL}>
      <NativeSelect aria-label={t('incov.severity')} value={filters.level} onChange={(e) => patch({ level: e.target.value })}>
        <NativeSelectOption value="">{t('incov.severityAll')}</NativeSelectOption>
        {SEVERITY_ORDER.map((lvl) => <NativeSelectOption key={lvl} value={lvl}>{t(severityMeta(lvl).key)}</NativeSelectOption>)}
      </NativeSelect>
    </div>
  )
  const teamSelect = (
    <div className={SEL}>
      <NativeSelect aria-label={t('incov.colTeam')} value={filters.team} onChange={(e) => patch({ team: e.target.value })}>
        <NativeSelectOption value="">{t('incov.teamAll')}</NativeSelectOption>
        {teams.map((tm) => <NativeSelectOption key={tm.id} value={tm.id}>{tm.name}</NativeSelectOption>)}
      </NativeSelect>
    </div>
  )
  const dates = (
    <div className="flex flex-wrap items-center gap-2">
      <DateTimeField dateOnly clearable className={phone ? '' : 'dtf-inline'} placeholder={t('incov.since')}
        value={filters.since} onChange={(v) => patch({ since: v || '' })} />
      <DateTimeField dateOnly clearable className={phone ? '' : 'dtf-inline'} placeholder={t('incov.until')}
        value={filters.until} onChange={(v) => patch({ until: v || '' })} />
    </div>
  )
  const search = (
    <InputGroup className="w-full sm:w-64">
      <InputGroupInput type="search" placeholder={t('incov.searchPlaceholder')} aria-label={t('incov.searchPlaceholder')}
        value={draft} onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); applySearch() } }}
        onBlur={applySearch} />
      <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
    </InputGroup>
  )
  const clearAll = () => { setDraft(''); reset() }

  return (
    <div data-slot="incidents-toolbar" className="flex min-w-0 flex-col gap-2">
      {phone ? (
        <div className="flex items-center gap-2">
          <div className="min-w-0 flex-1">{search}</div>
          <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
            <Button type="button" variant="outline" className="h-10 shrink-0 gap-1.5" onClick={() => setSheetOpen(true)}
              aria-label={t('incov.filters')} aria-expanded={sheetOpen}>
              <SlidersHorizontal aria-hidden="true" />{t('incov.filters')}
              {active.length > 0 && <Badge data-slot="filter-count" className="h-5 min-w-5 justify-center px-1.5 tabular-nums">{active.length}</Badge>}
            </Button>
            {/* z-[1001]: yüzen yardım düğmesinin (.help-fab 900) üstünde — "Uygula" örtülmesin */}
            <SheetContent side="bottom" showCloseButton={false} data-slot="incident-filters-sheet"
              className="z-[1001] max-h-[92dvh] gap-0 overflow-y-auto rounded-t-2xl pb-[env(safe-area-inset-bottom)]">
              <SheetHeader className="flex-row items-center justify-between">
                <div>
                  <SheetTitle className="flex items-center gap-2"><SlidersHorizontal aria-hidden="true" className="size-4" />{t('incov.filters')}</SheetTitle>
                  <SheetDescription>{t('incov.filtersHint')}</SheetDescription>
                </div>
                <SheetClose asChild>
                  <Button type="button" variant="ghost" size="icon" aria-label={t('app.close')}><X aria-hidden="true" /></Button>
                </SheetClose>
              </SheetHeader>
              <div className="flex flex-col gap-3 px-4">
                <Label className="flex flex-col items-stretch gap-1 text-xs font-semibold text-muted-foreground">{t('incov.colStatus')}{statusSelect}</Label>
                <Label className="flex flex-col items-stretch gap-1 text-xs font-semibold text-muted-foreground">{t('incov.severity')}{severitySelect}</Label>
                <Label className="flex flex-col items-stretch gap-1 text-xs font-semibold text-muted-foreground">{t('incov.colTeam')}{teamSelect}</Label>
                <div className="flex flex-col gap-1 text-xs font-semibold text-muted-foreground">{t('incov.colStarted')}{dates}</div>
              </div>
              <SheetFooter className="flex-row justify-between">
                <Button type="button" variant="ghost" onClick={clearAll} disabled={active.length === 0}><FilterX aria-hidden="true" />{t('incov.clearFilters')}</Button>
                <SheetClose asChild><Button type="button">{t('incov.applyFilters')}</Button></SheetClose>
              </SheetFooter>
            </SheetContent>
          </Sheet>
        </div>
      ) : (
        <div className="flex flex-wrap items-center gap-2">
          {statusSelect}{severitySelect}{teamSelect}{search}{dates}
          {active.length > 0 && (
            <Button type="button" variant="secondary" size="sm" onClick={clearAll}><FilterX aria-hidden="true" />{t('incov.clearFilters')}</Button>
          )}
        </div>
      )}

      {/* Etkin süzgeç çipleri */}
      {active.length > 0 && (
        <div data-slot="active-filters" role="group" aria-label={t('incov.activeFilters')} className="flex flex-wrap items-center gap-1.5">
          {active.map((f) => {
            const label = labelsFor(f)
            return (
              <Badge key={f.key} variant="outline" data-filter={f.key} className="h-7 gap-1 rounded-full bg-primary/5 pr-1 pl-2.5 font-medium">
                <span className="max-w-[14rem] truncate">{label}</span>
                <Button type="button" variant="ghost" size="icon-xs" className="size-5 rounded-full pointer-coarse:size-7"
                  aria-label={t('incov.removeFilter', label)}
                  onClick={() => patch(f.patch)}>
                  <X aria-hidden="true" className="size-3" />
                </Button>
              </Badge>
            )
          })}
          <Button type="button" variant="link" size="xs" className="h-7 px-1 text-muted-foreground" onClick={clearAll}>{t('incov.clearFilters')}</Button>
        </div>
      )}

      {/* Kök neden çipleri (canlı sayı) — telefonda yatay kayar, geniş ekranda sarar */}
      {pills.length > 0 && (
        <div role="group" aria-label={t('incov.colRootCause')}
          className={cn('-mx-1 flex gap-1.5 overflow-x-auto px-1 pb-1 [scrollbar-width:thin] sm:mx-0 sm:flex-wrap sm:overflow-visible sm:px-0 sm:pb-0')}>
          {[['', t('incov.allCauses'), total], ...pills.map(([type, count]) => [type, typeLabel(type), count])].map(([type, label, count]) => {
            const on = type ? filters.rootCause === type : !filters.rootCause
            return (
              <Button key={type || 'all'} type="button" variant={on ? 'default' : 'outline'} size="sm" aria-pressed={on}
                className="h-8 shrink-0 gap-1.5 rounded-full px-3 font-medium pointer-coarse:h-10"
                onClick={() => patch({ rootCause: type && filters.rootCause !== type ? type : '' })}>
                {label} <Badge variant={on ? 'secondary' : 'outline'} className="h-5 px-1.5 text-[0.85em] tabular-nums">{count}</Badge>
              </Button>
            )
          })}
        </div>
      )}
    </div>
  )
}
