import { useState } from 'react'
import { Search, SlidersHorizontal, X, FilterX } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import DateTimeField from '../ui/DateTimeField.jsx'
import { Button } from '@/components/shadcn/button'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { InputGroup, InputGroupInput, InputGroupAddon } from '@/components/shadcn/input-group'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetFooter, SheetClose } from '@/components/shadcn/sheet'
import { Label } from '@/components/shadcn/label'
import { cn } from '@/lib/utils'
import { SEVERITIES, STATUSES, CATEGORIES, activeFilters, formatDay } from './incidentHistoryModel.js'

/** Seçicilerin kabı: NativeSelect sarmalayıcısı `w-fit` — telefonda tam genişlik için `*:w-full`. */
const SEL = 'w-full *:w-full sm:w-auto sm:*:min-w-[10.5rem]'

/**
 * Süzgeç araç çubuğu — geniş ekranda tek sarılan satır (arama · önem · durum · kategori · kanal · takım · tarih
 * aralığı); telefonda arama + "Süzgeçler (N)" düğmesi (alttan Sheet, 40 px hedefler). Etkin süzgeçler çip olarak altta:
 * çipin TAMAMI kaldırma düğmesidir (adı "… süzgecini kaldır"), "Temizle" hepsini kaldırır.
 *
 * <p>Arama sayfada 300 ms gecikmeyle uygulanır (her tuşta istek yok); Enter hemen uygular. `busy`: yavaş yüklemede
 * satırın sonunda duran küçük Spinner — yeri HEP ayrılı, satır sarmaz (IncidentsToolbar deseni).
 */
export default function HistoryToolbar({
  filters, qApplied, onQuery, onQueryCommit, patch, reset, phone, teams = [], channels = [], busy = null, resultCount = null,
}) {
  const t = useT()
  const [sheetOpen, setSheetOpen] = useState(false)
  const active = activeFilters({ ...filters, q: qApplied })

  const teamName = (id) => teams.find((tm) => String(tm.id) === String(id))?.name ?? `#${id}`
  const chipLabel = (f) => {
    switch (f.key) {
      case 'q': return t('inc.chip.q', f.value)
      case 'severity': return t('inc.chip.severity', t('inc.sev' + f.value))
      case 'status': return t('inc.chip.status', t('inc.st' + f.value))
      case 'category': return t('inc.chip.category', t('inc.cat' + f.value))
      case 'channel': return t('inc.chip.channel', f.value)
      case 'team_id': return t('inc.chip.team', teamName(f.value))
      case 'since': return t('inc.chip.since', formatDay(f.value))
      case 'until': return t('inc.chip.until', formatDay(f.value))
      case 'slaBreached': return f.value ? t('inc.chip.slaYes') : t('inc.chip.slaNo')
      case 'open': return t('inc.chip.open')
      default: return String(f.value)
    }
  }

  const select = (key, label, allLabel, items) => (
    <div className={SEL}>
      <NativeSelect aria-label={label} value={filters[key] ?? ''} onChange={(e) => patch({ [key]: e.target.value })}>
        <NativeSelectOption value="">{allLabel}</NativeSelectOption>
        {items.map(([v, l]) => <NativeSelectOption key={v} value={v}>{l}</NativeSelectOption>)}
      </NativeSelect>
    </div>
  )
  const severity = select('severity', t('inc.colSeverity'), t('inc.allSeverities'), SEVERITIES.map((s) => [s, t('inc.sev' + s)]))
  const status = select('status', t('inc.colStatus'), t('inc.allStatuses'), STATUSES.map((s) => [s, t('inc.st' + s)]))
  const category = select('category', t('inc.colCategory'), t('inc.allCategories'), CATEGORIES.map((c) => [c, t('inc.cat' + c)]))
  const channel = select('channel', t('inc.colChannel'), t('inc.allChannels'), channels.map((c) => [c, c]))
  const team = select('team_id', t('inc.colTeam'), t('inc.allTeams'), teams.map((tm) => [String(tm.id), tm.name]))
  const dates = (
    <div className={cn('flex flex-wrap items-center gap-2', phone && 'grid grid-cols-2 [&_[data-slot=date-picker-trigger]]:h-10')}>
      <DateTimeField dateOnly clearable className={phone ? '' : 'dtf-inline'} placeholder={t('inc.since')}
        value={filters.since} onChange={(v) => patch({ since: v || '' })} />
      <DateTimeField dateOnly clearable className={phone ? '' : 'dtf-inline'} placeholder={t('inc.until')}
        value={filters.until} min={filters.since || undefined} onChange={(v) => patch({ until: v || '' })} />
    </div>
  )
  const search = (
    <InputGroup className={cn('w-full sm:w-72', phone && 'h-10')}>
      <InputGroupInput type="search" placeholder={t('inc.search')} aria-label={t('inc.search')} value={filters.q}
        className={phone ? 'h-full' : undefined}
        onChange={(e) => onQuery(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); onQueryCommit() } }} />
      <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
    </InputGroup>
  )
  // Yükleme göstergesinin yeri HEP ayrılı (16 px): belirip kaybolurken satır sarmaz, içerik zıplamaz.
  const busySlot = (
    <span className="inline-flex size-4 shrink-0 items-center justify-center">
      {busy && <span data-slot="ih-busy" className="inline-flex">{busy}</span>}
    </span>
  )
  const field = (label, control) => (
    <Label className="flex flex-col items-stretch gap-1.5 text-xs font-semibold text-muted-foreground [&_[data-slot=native-select]]:h-10">
      {label}{control}
    </Label>
  )

  return (
    <div data-slot="ih-toolbar" className="flex min-w-0 flex-col gap-2">
      {phone ? (
        <div className="flex items-center gap-2">
          <div className="min-w-0 flex-1">{search}</div>
          {busySlot}
          <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
            <Button type="button" variant="outline" className="h-10 shrink-0 gap-1.5" onClick={() => setSheetOpen(true)}
              aria-expanded={sheetOpen} data-slot="ih-filters-button">
              <SlidersHorizontal aria-hidden="true" />
              {active.length > 0 ? t('inc.filtersN', active.length) : t('inc.filters')}
            </Button>
            {/* z-[1001]: yüzen yardım düğmesinin (.help-fab 900) üstünde — "Sonuçları göster" örtülmesin */}
            <SheetContent side="bottom" showCloseButton={false} data-slot="ih-filters-sheet"
              className="z-[1001] max-h-[92dvh] gap-0 overflow-y-auto rounded-t-2xl pb-[env(safe-area-inset-bottom)]">
              <SheetHeader className="flex-row items-center justify-between">
                <div>
                  <SheetTitle className="flex items-center gap-2"><SlidersHorizontal aria-hidden="true" className="size-4" />{t('inc.filters')}</SheetTitle>
                  <SheetDescription>{t('inc.filtersHint')}</SheetDescription>
                </div>
                <SheetClose asChild>
                  <Button type="button" variant="ghost" size="icon" className="size-10" aria-label={t('app.close')}><X aria-hidden="true" /></Button>
                </SheetClose>
              </SheetHeader>
              <div data-slot="incident-filters" className="flex flex-col gap-3 px-4">
                {field(t('inc.colSeverity'), severity)}
                {field(t('inc.colStatus'), status)}
                {field(t('inc.colCategory'), category)}
                {field(t('inc.colChannel'), channel)}
                {field(t('inc.colTeam'), team)}
                <div className="flex flex-col gap-1.5 text-xs font-semibold text-muted-foreground">{t('inc.dateRange')}{dates}</div>
              </div>
              <SheetFooter className="mt-2 flex-row justify-between gap-2">
                <Button type="button" variant="ghost" className="h-10" onClick={reset} disabled={active.length === 0}>
                  <FilterX aria-hidden="true" />{t('inc.clearFilters')}
                </Button>
                <SheetClose asChild>
                  <Button type="button" className="h-10 flex-1">
                    {resultCount != null ? t('inc.showResults', resultCount) : t('inc.applyFilters')}
                  </Button>
                </SheetClose>
              </SheetFooter>
            </SheetContent>
          </Sheet>
        </div>
      ) : (
        <div data-slot="incident-filters" className="flex flex-wrap items-center gap-2">
          {search}{severity}{status}{category}{channel}{team}{dates}
          {busySlot}
        </div>
      )}

      {/* Etkin süzgeç çipleri — çipin tamamı kaldırma düğmesi */}
      {active.length > 0 && (
        <div data-slot="ih-active-filters" role="group" aria-label={t('inc.activeFilters')} className="flex flex-wrap items-center gap-1.5">
          {active.map((f) => {
            const label = chipLabel(f)
            return (
              <Button key={f.key} type="button" variant="outline" size="xs" data-filter={f.key}
                aria-label={t('inc.removeFilter', label)} title={t('inc.removeFilter', label)}
                className="h-7 max-w-full gap-1 rounded-full bg-primary/5 pr-1.5 pl-2.5 font-medium max-sm:h-10 max-sm:px-3"
                onClick={() => patch(f.patch)}>
                <span className="max-w-[16rem] truncate">{label}</span>
                <X aria-hidden="true" className="size-3.5 opacity-70" />
              </Button>
            )
          })}
          <Button type="button" variant="link" size="xs" className="h-7 px-1.5 text-muted-foreground max-sm:h-10" onClick={reset}>
            {t('inc.clearFilters')}
          </Button>
        </div>
      )}
    </div>
  )
}
