import { useEffect, useId, useState } from 'react'
import { Search, SlidersHorizontal, X, FilterX, ChevronDown, ArrowUpDown } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import DateTimeField from '../ui/DateTimeField.jsx'
import { Button } from '@/components/shadcn/button'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { InputGroup, InputGroupInput, InputGroupAddon } from '@/components/shadcn/input-group'
import { Input } from '@/components/shadcn/input'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetFooter, SheetClose } from '@/components/shadcn/sheet'
import { Label } from '@/components/shadcn/label'
import { cn } from '@/lib/utils'
import {
  SEVERITIES, STATUSES, CATEGORIES, PROBLEM_TYPES, POSTMORTEM_FILTERS, SORTS, MAX_FILTER_TEXT, activeFilters, advancedCount,
  formatDay,
} from './incidentHistoryModel.js'

/** Seçicilerin kabı: NativeSelect sarmalayıcısı `w-fit` — telefonda tam genişlik için `*:w-full`. */
const SEL = 'w-full *:w-full sm:w-auto sm:*:min-w-[10.5rem]'

/** Seçenek listesi + seçili değer (listede yoksa — adresten gelen ya da silinmiş seçenek — yine gösterilir). */
function withCurrent(items, current) {
  const list = Array.isArray(items) ? items.filter((v) => typeof v === 'string' && v.trim() !== '') : []
  const cur = typeof current === 'string' ? current.trim() : ''
  return cur && !list.some((v) => v.toLowerCase() === cur.toLowerCase()) ? [cur, ...list] : list
}

/**
 * Etiket süzgeci — yazılan değer Enter'da ya da alandan çıkınca uygulanır (her tuşta istek yok). Dışarıdan gelen değer
 * (çip ×, "Temizle", kayıtlı görünüm) alana yansır.
 */
function TagFilterInput({ value, onCommit, label, className }) {
  const [text, setText] = useState(value ?? '')
  useEffect(() => { setText(value ?? '') }, [value])
  const commit = () => { const v = text.trim().slice(0, MAX_FILTER_TEXT); if (v !== (value ?? '')) onCommit(v) }
  return (
    <Input type="text" value={text} aria-label={label} placeholder={label} maxLength={MAX_FILTER_TEXT}
      data-slot="ih-filter-tag" className={className}
      onChange={(e) => setText(e.target.value)} onBlur={commit}
      onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); commit() } }} />
  )
}

/**
 * Süzgeç araç çubuğu — geniş ekranda tek sarılan satır (arama · önem · durum · kategori · kanal · takım · tarih aralığı ·
 * sıralama) + "Daha fazla süzgeç (N)" ile açılan ikinci satır (alan adı · problem tipi · hata kodu · etiket · benim
 * açtıklarım · postmortem — 2026-11-26, "Bul ve Raporla"); telefonda arama + "Süzgeçler (N)" düğmesi (alttan Sheet, 40 px
 * hedefler, tüm süzgeçler + sıralama). Etkin süzgeçler çip olarak altta: çipin TAMAMI kaldırma düğmesidir (adı "… süzgecini
 * kaldır"), "Temizle" hepsini kaldırır (sıralama süzgeç değildir, kalır).
 *
 * <p>Arama sayfada 300 ms gecikmeyle uygulanır (her tuşta istek yok); Enter hemen uygular. `busy`: yavaş yüklemede
 * satırın sonunda duran küçük Spinner — yeri HEP ayrılı, satır sarmaz (IncidentsToolbar deseni). `onAdvancedOpen`: gelişmiş
 * süzgeçler ilk açıldığında alan adı / hata kodu seçenek listeleri okunur (sayfa açılışında ek istek yok).
 */
export default function HistoryToolbar({
  filters, qApplied, onQuery, onQueryCommit, patch, reset, phone, teams = [], channels = [], domains = [], errorCodes = [],
  sort, onSort, onAdvancedOpen, busy = null, resultCount = null,
}) {
  const t = useT()
  const [sheetOpen, setSheetOpen] = useState(false)
  const advCount = advancedCount(filters)
  const [moreOpen, setMoreOpen] = useState(() => advCount > 0)
  const mineId = useId()
  const mineSheetId = useId()
  const active = activeFilters({ ...filters, q: qApplied })
  // Gelişmiş bölüm açılınca (geniş ekran) ya da Sheet açılınca (telefon) seçenek listeleri bir kez okunur; adreste gelişmiş
  // süzgeç varsa açılışta da
  useEffect(() => {
    if ((moreOpen && !phone) || (sheetOpen && phone)) onAdvancedOpen?.()
  }, [moreOpen, sheetOpen, phone, onAdvancedOpen])

  const teamName = (id) => teams.find((tm) => String(tm.id) === String(id))?.name ?? t('inc.teamUnknown')
  const chipLabel = (f) => {
    switch (f.key) {
      case 'q': return t('inc.chip.q', f.value)
      case 'severity': return t('inc.chip.severity', t('inc.sev' + f.value))
      case 'status': return t('inc.chip.status', t('inc.st' + f.value))
      case 'category': return t('inc.chip.category', t('inc.cat' + f.value))
      case 'channel': return t('inc.chip.channel', f.value)
      case 'channel_exact': return t('inc.chip.channelExact', f.value)
      case 'team_id': return t('inc.chip.team', teamName(f.value))
      case 'since': return t('inc.chip.since', formatDay(f.value))
      case 'until': return t('inc.chip.until', formatDay(f.value))
      case 'slaBreached': return f.value ? t('inc.chip.slaYes') : t('inc.chip.slaNo')
      case 'open': return t('inc.chip.open')
      case 'service': return t('inc.chip.service', f.value)
      case 'problem_type': return t('inc.chip.problemType', f.value)
      case 'error_code': return t('inc.chip.ecode', f.value)
      case 'tag': return t('inc.chip.tag', f.value)
      case 'mine': return t('inc.chip.mine')
      case 'postmortem': return t('inc.chip.postmortem', t('inc.pmFilter.' + f.value))
      default: return String(f.value)
    }
  }

  const select = (key, label, allLabel, items, extra = {}) => (
    <div className={SEL}>
      <NativeSelect aria-label={label} value={filters[key] ?? ''} onChange={(e) => patch({ [key]: e.target.value })} {...extra}>
        <NativeSelectOption value="">{allLabel}</NativeSelectOption>
        {items.map(([v, l]) => <NativeSelectOption key={v} value={v}>{l}</NativeSelectOption>)}
      </NativeSelect>
    </div>
  )
  const severity = select('severity', t('inc.colSeverity'), t('inc.allSeverities'), SEVERITIES.map((s) => [s, t('inc.sev' + s)]))
  const status = select('status', t('inc.colStatus'), t('inc.allStatuses'), STATUSES.map((s) => [s, t('inc.st' + s)]))
  const category = select('category', t('inc.colCategory'), t('inc.allCategories'), CATEGORIES.map((c) => [c, t('inc.cat' + c)]))
  const channel = select('channel', t('inc.colChannel'), t('inc.allChannels'), withCurrent(channels, filters.channel).map((c) => [c, c]))
  const team = select('team_id', t('inc.colTeam'), t('inc.allTeams'), teams.map((tm) => [String(tm.id), tm.name]))
  // Gelişmiş süzgeçler (2026-11-26)
  const service = select('service', t('inc.f.service'), t('inc.f.serviceAll'),
    withCurrent(domains, filters.service).map((v) => [v, v]), { 'data-slot': 'ih-filter-service' })
  const problemType = select('problem_type', t('inc.f.problemType'), t('inc.f.problemTypeAll'),
    withCurrent(PROBLEM_TYPES, filters.problem_type).map((v) => [v, v]), { 'data-slot': 'ih-filter-ptype' })
  const errorCode = select('error_code', t('inc.f.ecode'), t('inc.f.ecodeAll'),
    withCurrent(errorCodes, filters.error_code).map((v) => [v, v]), { 'data-slot': 'ih-filter-ecode' })
  const postmortem = select('postmortem', t('inc.f.postmortem'), t('inc.f.postmortemAll'),
    POSTMORTEM_FILTERS.map((v) => [v, t('inc.pmFilter.' + v)]), { 'data-slot': 'ih-filter-pm' })
  const tag = (cls) => <TagFilterInput value={filters.tag} label={t('inc.f.tag')} onCommit={(v) => patch({ tag: v })} className={cls} />
  const mine = (id, cls) => (
    <div className={cn('flex min-h-9 items-center gap-2 pointer-coarse:min-h-10', cls)} data-slot="ih-filter-mine">
      <Checkbox id={id} checked={filters.mine === true} onCheckedChange={(v) => patch({ mine: v === true ? true : undefined })} />
      <Label htmlFor={id} className="cursor-pointer text-sm font-medium">{t('inc.f.mine')}</Label>
    </div>
  )
  const sortSelect = (
    <div className={SEL} data-slot="ih-sort">
      <NativeSelect aria-label={t('inc.sort.label')} value={sort} onChange={(e) => onSort?.(e.target.value)}>
        {SORTS.map((k) => <NativeSelectOption key={k} value={k}>{t('inc.sort.' + k)}</NativeSelectOption>)}
      </NativeSelect>
    </div>
  )

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
        data-page-search="" className={phone ? 'h-full' : undefined} maxLength={MAX_FILTER_TEXT}
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
            <SheetContent overlayClassName="z-[1000]" side="bottom" showCloseButton={false} data-slot="ih-filters-sheet"
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
                {field(t('inc.sort.label'), sortSelect)}
                {field(t('inc.colSeverity'), severity)}
                {field(t('inc.colStatus'), status)}
                {field(t('inc.colCategory'), category)}
                {field(t('inc.colChannel'), channel)}
                {field(t('inc.colTeam'), team)}
                <div className="flex flex-col gap-1.5 text-xs font-semibold text-muted-foreground">{t('inc.dateRange')}{dates}</div>
                <div className="mt-1 border-t pt-3 text-xs font-semibold tracking-wide text-muted-foreground uppercase">{t('inc.f.more')}</div>
                {field(t('inc.f.service'), service)}
                {field(t('inc.f.problemType'), problemType)}
                {field(t('inc.f.ecode'), errorCode)}
                {field(t('inc.f.tag'), tag('h-10'))}
                {field(t('inc.f.postmortem'), postmortem)}
                {mine(mineSheetId, 'min-h-10')}
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
        <Collapsible open={moreOpen} onOpenChange={setMoreOpen} className="flex min-w-0 flex-col gap-2">
          <div data-slot="incident-filters" className="flex flex-wrap items-center gap-2">
            {search}{severity}{status}{category}{channel}{team}{dates}
            <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
              <ArrowUpDown aria-hidden="true" className="size-3.5" />{sortSelect}
            </span>
            <CollapsibleTrigger asChild>
              <Button type="button" variant="outline" size="sm" data-slot="ih-more-filters" className="gap-1.5 pointer-coarse:h-10">
                <SlidersHorizontal aria-hidden="true" />
                {advCount > 0 ? t('inc.f.moreN', advCount) : t('inc.f.more')}
                <ChevronDown aria-hidden="true" className={cn('size-3.5 transition-transform motion-reduce:transition-none', moreOpen && 'rotate-180')} />
              </Button>
            </CollapsibleTrigger>
            {busySlot}
          </div>
          <CollapsibleContent>
            <div data-slot="ih-advanced-filters" role="group" aria-label={t('inc.f.more')}
              className="flex flex-wrap items-center gap-2 rounded-xl border border-dashed bg-muted/30 p-2">
              {service}{problemType}{errorCode}
              {tag('w-full sm:w-48 pointer-coarse:h-10')}
              {postmortem}
              {mine(mineId)}
            </div>
          </CollapsibleContent>
        </Collapsible>
      )}

      {/* Etkin süzgeç çipleri — çipin tamamı kaldırma düğmesi */}
      {active.length > 0 && (
        <div data-slot="ih-active-filters" role="group" aria-label={t('inc.activeFilters')} className="flex flex-wrap items-center gap-1.5">
          {active.map((f) => {
            const label = chipLabel(f)
            return (
              <Button key={f.key} type="button" variant="outline" size="xs" data-filter={f.key}
                aria-label={t('inc.removeFilter', label)} title={t('inc.removeFilter', label)} onClick={() => patch(f.patch)}
                className="h-auto min-h-7 max-w-full gap-1 rounded-xl bg-primary/5 pr-1.5 pl-2.5 font-medium whitespace-normal max-sm:min-h-10 max-sm:px-3 pointer-coarse:min-h-10">
                <span className="min-w-0 text-left [overflow-wrap:anywhere]">{label}</span>
                <X aria-hidden="true" className="size-3.5 shrink-0 opacity-70" />
              </Button>
            )
          })}
          <Button type="button" variant="link" size="xs" className="h-7 px-1.5 text-muted-foreground max-sm:h-10 pointer-coarse:h-10" onClick={reset}>
            {t('inc.clearFilters')}
          </Button>
        </div>
      )}
    </div>
  )
}
