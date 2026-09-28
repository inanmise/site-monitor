import { useEffect, useId, useState } from 'react'
import {
  Search, SlidersHorizontal, X, FilterX, ChevronDown, Shapes, Gauge, UserCheck, CalendarRange, Users,
} from 'lucide-react'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { ALERT_TYPES, alertTypeMeta, alertTypeLabel } from '../../../utils/alertTypeMeta.js'
import DateTimeField from '../../ui/DateTimeField.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Separator } from '@/components/shadcn/separator'
import { InputGroup, InputGroupInput, InputGroupAddon } from '@/components/shadcn/input-group'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuLabel, DropdownMenuRadioGroup, DropdownMenuRadioItem,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetFooter, SheetClose } from '@/components/shadcn/sheet'
import { RadioGroup, RadioGroupItem } from '@/components/shadcn/radio-group'
import { FieldLabel, Field as ShField, FieldContent, FieldTitle, FieldDescription } from '@/components/shadcn/field'
import { cn } from '@/lib/utils'
import {
  LEVELS, RANGE_ACTIVE, activeAlertFilters, activeRangeOn, quickRange, iso24hAgo, fmtFilterDate,
} from './alertHistoryModel.js'

/** Radix radyo öğesi değeri boş dize olamaz → "Tümü" için sabit belirteç. */
const ALL = '__all__'
const QUICK = [{ key: '24h', days: 1 }, { key: '7d', days: 7 }, { key: '30d', days: 30 }, { key: '90d', days: 90 }]

/**
 * Faset menüsü — shadcn DropdownMenu + RadioGroup (tek seçim). Tetik kesikli çerçeveli outline düğme; seçim varsa
 * değer tetikte görünür ve çerçeve düzleşir (shadcn "faceted filter" dili). Seçenek: {value, label, icon?, color?, count?}.
 * Test kancası: tetik `data-slot="facet-trigger"` + `data-facet=<ad>`, öğeler `menuitemradio`.
 */
function FacetMenu({ name, title, icon: Icon, value, options, onChange, block = false }) {
  const current = options.find((o) => o.value === value && o.value !== '')
  return (
    <DropdownMenu modal={false}>
      <DropdownMenuTrigger asChild>
        <Button type="button" variant="outline" size="sm" data-slot="facet-trigger" data-facet={name}
          data-active={current ? 'true' : undefined}
          className={cn('h-9 gap-1.5 pointer-coarse:h-10', !current && 'border-dashed', current && 'border-primary/40 bg-primary/5',
            block && 'w-full justify-start')}>
          <Icon aria-hidden="true" className="text-muted-foreground" />
          <span className={cn(current && 'text-muted-foreground')}>{title}</span>
          {current && (
            <>
              <Separator orientation="vertical" className="mx-0.5 h-4" />
              <span className="max-w-[11rem] truncate font-semibold">{current.label}</span>
            </>
          )}
          <ChevronDown aria-hidden="true" className={cn('text-muted-foreground', block && 'ml-auto')} />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" collisionPadding={8} className="z-(--z-menu) max-h-80 min-w-56">
        <DropdownMenuLabel className="text-xs text-muted-foreground">{title}</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuRadioGroup value={value || ALL} onValueChange={(v) => onChange(v === ALL ? '' : v)}>
          {options.map((o) => (
            <DropdownMenuRadioItem key={o.value || ALL} value={o.value || ALL} data-facet-value={o.value || ALL}>
              {o.icon && <o.icon aria-hidden="true" style={o.color ? { color: o.color } : undefined} />}
              <span className="min-w-0 flex-1 truncate">{o.label}</span>
              {o.count != null && <span data-slot="facet-count" className="ml-3 font-mono text-xs text-muted-foreground tabular-nums">{o.count}</span>}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/**
 * "Tümü" görünümünde tarih aralığının KİPİ (2026-09-28, regresyon B3): aralıkta AÇILANLAR (varsayılan) ya da aralıkta
 * AKTİF olanlar (daha önce açılıp aralığa devredenler dahil — haftalık e-postanın "Haftanın alarmları" sayısıyla aynı
 * küme). shadcn RadioGroup "choice card" (FieldLabel + Field): kartın tamamı dokunma hedefi (≥ 40 px), fark görünür
 * açıklamayla yazılı (dokunmatikte tooltip açılmaz).
 */
function RangeModePicker({ filters, patch }) {
  const t = useT()
  const base = useId()
  const value = filters.range === RANGE_ACTIVE ? RANGE_ACTIVE : 'opened'
  const options = [
    { value: 'opened', title: t('alh.range.opened'), hint: t('alh.range.openedHint') },
    { value: RANGE_ACTIVE, title: t('alh.range.active'), hint: t('alh.range.activeHint') },
  ]
  return (
    <div className="flex flex-col gap-1.5">
      <span id={`${base}-legend`} className="text-xs font-semibold text-muted-foreground">{t('alh.range.legend')}</span>
      <RadioGroup value={value} onValueChange={(v) => patch({ range: v === RANGE_ACTIVE ? RANGE_ACTIVE : '' })}
        aria-labelledby={`${base}-legend`} data-slot="alert-range-mode" className="grid grid-cols-1 gap-1.5">
        {options.map((o) => {
          const id = `${base}-${o.value}`
          return (
            <FieldLabel key={o.value} htmlFor={id} className="w-full cursor-pointer">
              <ShField orientation="horizontal" className="min-h-10 items-start gap-2 px-3! py-2!">
                <RadioGroupItem value={o.value} id={id} className="mt-0.5" />
                <FieldContent className="gap-0.5">
                  <FieldTitle className="text-sm">{o.title}</FieldTitle>
                  <FieldDescription className="m-0 text-xs leading-snug">{o.hint}</FieldDescription>
                </FieldContent>
              </ShField>
            </FieldLabel>
          )
        })}
      </RadioGroup>
    </div>
  )
}

/** Tarih aralığı gövdesi: ("Tümü"nde kip seçimi) + hızlı aralıklar + Başlangıç / Bitiş (ortak gün seçici, ui/DateTimeField). */
function DateRangeBody({ filters, patch, tab }) {
  const t = useT()
  return (
    <div className="flex flex-col gap-2.5">
      {tab === 'all' && <RangeModePicker filters={filters} patch={patch} />}
      <div className="grid grid-cols-2 gap-1.5">
        {QUICK.map(({ key, days }) => (
          <Button key={key} type="button" variant="outline" size="sm" className="h-9 font-normal"
            onClick={() => patch(days === 1 ? { from: iso24hAgo(Date.now()), to: '' } : quickRange(days))}>
            {t(`alh.quick.${key}`)}
          </Button>
        ))}
      </div>
      <div className="flex flex-col gap-1 text-xs font-semibold text-muted-foreground">
        {t('alh.facet.from')}
        <DateTimeField dateOnly clearable placeholder={t('alh.facet.from')}
          value={filters.from && filters.from.length === 10 ? filters.from : ''} onChange={(v) => patch({ from: v || '' })} />
      </div>
      <div className="flex flex-col gap-1 text-xs font-semibold text-muted-foreground">
        {t('alh.facet.to')}
        <DateTimeField dateOnly clearable placeholder={t('alh.facet.to')}
          value={filters.to} onChange={(v) => patch({ to: v || '' })} />
      </div>
    </div>
  )
}

/**
 * Alarm Geçmişi süzgeç araç çubuğu — Olaylar konsoluyla (incidents/IncidentsToolbar) aynı düzen: geniş ekranda tek
 * sarılan satır (arama + faset menüleri + tarih), telefonda arama + "Süzgeçler (n)" düğmesi (alt Sheet). Etkin
 * süzgeçler altta çip (× ile tek tek, "Temizle" ile hepsi). Hepsi SUNUCUYA gider (sayfalama + sayaçlar doğru kalsın).
 * Arama 300 ms sessizlikten sonra uygulanır (her tuşta istek yok); Enter hemen uygular.
 */
export default function AlertToolbar({ tab, filters, patch, reset, typeCounts = {}, teams = [], phone = false }) {
  const t = useT()
  const locale = useDateLocale()
  const [sheetOpen, setSheetOpen] = useState(false)
  const [draft, setDraft] = useState(filters.q)
  useEffect(() => { setDraft(filters.q) }, [filters.q])
  useEffect(() => {
    const v = draft.trim()
    if (v === filters.q) return undefined
    const id = setTimeout(() => patch({ q: v }), 300)
    return () => clearTimeout(id)
  }, [draft]) // eslint-disable-line react-hooks/exhaustive-deps
  const applyNow = () => { const v = draft.trim(); if (v !== filters.q) patch({ q: v }) }

  const active = activeAlertFilters(filters, tab)
  const typeTotal = Object.values(typeCounts).reduce((s, n) => s + n, 0)
  const knownTypes = ALERT_TYPES.filter((type) => (typeCounts[type] ?? 0) > 0 || filters.type === type)
  const unknownTypes = Object.keys(typeCounts).filter((type) => !ALERT_TYPES.includes(type) && typeCounts[type] > 0)
  const typeOptions = [
    { value: '', label: t('alh.typeAll'), count: typeTotal },
    ...[...knownTypes, ...unknownTypes].map((type) => {
      const meta = alertTypeMeta(type)
      return { value: type, label: alertTypeLabel(t, type), icon: meta.icon, color: meta.color, count: typeCounts[type] ?? 0 }
    }),
  ]
  const levelOptions = [{ value: '', label: t('alh.allLevels') }, ...LEVELS.map((l) => ({ value: l, label: t(`alh.level.${l.toLowerCase()}`) }))]
  const ackOptions = [
    { value: '', label: t('alh.allAckStates') },
    { value: 'unack', label: t('alh.unackedOnly') },
    { value: 'ack', label: t('alh.ackOnly') },
  ]
  const teamOptions = [{ value: '', label: t('alh.allTeams') }, ...teams.map((tm) => ({ value: String(tm.id), label: tm.name }))]
  const teamName = (id) => teams.find((tm) => String(tm.id) === String(id))?.name ?? id
  const datesShown = tab !== 'open'
  const rangeTitle = tab === 'closed' ? t('alh.facet.resolvedRange')
    : activeRangeOn(filters, tab) ? t('alh.facet.activeRange') : t('alh.facet.openedRange')
  const rangeValue = filters.from || filters.to
    ? `${filters.from ? fmtFilterDate(filters.from, locale) : '…'} → ${filters.to ? fmtFilterDate(filters.to, locale) : '…'}`
    : null

  const labelFor = (f) => {
    switch (f.key) {
      case 'type': return alertTypeLabel(t, f.value)
      case 'level': return t(`alh.level.${String(f.value).toLowerCase()}`)
      case 'ack': return f.value === 'ack' ? t('alh.ackOnly') : t('alh.unackedOnly')
      case 'team': return t('alh.chip.team', teamName(f.value))
      case 'q': return t('alh.chip.search', f.value)
      case 'range': return t('alh.chip.active')
      case 'from': return t('alh.chip.from', fmtFilterDate(f.value, locale))
      case 'to': return t('alh.chip.to', fmtFilterDate(f.value, locale))
      default: return String(f.value)
    }
  }
  const clearAll = () => { setDraft(''); reset() }

  const typeFacet = (block) => (
    <FacetMenu name="type" title={t('alh.facet.type')} icon={Shapes} value={filters.type} options={typeOptions} block={block}
      onChange={(v) => patch({ type: v })} />
  )
  const levelFacet = (block) => (
    <FacetMenu name="level" title={t('alh.facet.level')} icon={Gauge} value={filters.level} options={levelOptions} block={block}
      onChange={(v) => patch({ level: v })} />
  )
  const ackFacet = (block) => (
    <FacetMenu name="ack" title={t('alh.facet.ack')} icon={UserCheck} value={filters.ack} options={ackOptions} block={block}
      onChange={(v) => patch({ ack: v })} />
  )
  const teamFacet = teams.length > 0 && (
    <span className="inline-flex w-full items-center gap-1.5 sm:w-auto sm:max-w-[15rem] sm:min-w-[11rem]">
      <Users aria-hidden="true" className="hidden size-4 shrink-0 text-muted-foreground sm:block" />
      <SearchableSelect value={filters.team} onChange={(v) => patch({ team: v || '' })} options={teamOptions}
        searchThreshold={6} ariaLabel={t('alh.facet.team')} />
    </span>
  )
  const search = (
    <InputGroup className="w-full sm:w-64">
      <InputGroupInput type="search" placeholder={t('alh.searchPlaceholder')} aria-label={t('alh.searchPlaceholder')}
        value={draft} onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); applyNow() } }} />
      <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
    </InputGroup>
  )
  const datesFacet = datesShown && (
    <Popover>
      <PopoverTrigger asChild>
        <Button type="button" variant="outline" size="sm" data-slot="facet-trigger" data-facet="dates"
          data-active={rangeValue ? 'true' : undefined}
          className={cn('h-9 gap-1.5 pointer-coarse:h-10', !rangeValue && 'border-dashed', rangeValue && 'border-primary/40 bg-primary/5')}>
          <CalendarRange aria-hidden="true" className="text-muted-foreground" />
          <span className={cn(rangeValue && 'text-muted-foreground')}>{rangeTitle}</span>
          {rangeValue && (<><Separator orientation="vertical" className="mx-0.5 h-4" /><span className="font-semibold tabular-nums">{rangeValue}</span></>)}
          <ChevronDown aria-hidden="true" className="text-muted-foreground" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" collisionPadding={8} className="z-(--z-menu) w-[min(20rem,calc(100vw-1rem))]">
        <p className="mb-2 text-sm font-semibold">{rangeTitle}</p>
        <DateRangeBody filters={filters} patch={patch} tab={tab} />
      </PopoverContent>
    </Popover>
  )

  return (
    <div data-slot="alert-toolbar" data-testid="alh-toolbar" className="flex min-w-0 flex-col gap-2">
      {phone ? (
        <div className="flex items-center gap-2">
          <div className="min-w-0 flex-1">{search}</div>
          <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
            <Button type="button" variant="outline" className="h-10 shrink-0 gap-1.5" onClick={() => setSheetOpen(true)}
              aria-expanded={sheetOpen} data-slot="filters-button">
              <SlidersHorizontal aria-hidden="true" />{t('alh.filters')}
              {active.length > 0 && <Badge data-slot="filter-count" className="h-5 min-w-5 justify-center px-1.5 tabular-nums">{active.length}</Badge>}
            </Button>
            <SheetContent overlayClassName="z-[1000]" side="bottom" showCloseButton={false} data-slot="alert-filters-sheet"
              className="z-[1001] max-h-[92dvh] gap-0 overflow-y-auto rounded-t-2xl pb-[env(safe-area-inset-bottom)]">
              <SheetHeader className="flex-row items-center justify-between">
                <div>
                  <SheetTitle className="flex items-center gap-2"><SlidersHorizontal aria-hidden="true" className="size-4" />{t('alh.filters')}</SheetTitle>
                  <SheetDescription>{t('alh.filtersHint')}</SheetDescription>
                </div>
                <SheetClose asChild>
                  <Button type="button" variant="ghost" size="icon" aria-label={t('app.close')}><X aria-hidden="true" /></Button>
                </SheetClose>
              </SheetHeader>
              <div className="flex flex-col gap-3 px-4">
                {typeFacet(true)}
                {levelFacet(true)}
                {ackFacet(true)}
                {teamFacet}
                {datesShown && (
                  <div className="flex flex-col gap-1.5">
                    <span className="text-xs font-semibold text-muted-foreground">{rangeTitle}</span>
                    <DateRangeBody filters={filters} patch={patch} tab={tab} />
                  </div>
                )}
              </div>
              <SheetFooter className="flex-row justify-between">
                <Button type="button" variant="ghost" onClick={clearAll} disabled={active.length === 0}><FilterX aria-hidden="true" />{t('alh.clearFilters')}</Button>
                <SheetClose asChild><Button type="button">{t('alh.applyFilters')}</Button></SheetClose>
              </SheetFooter>
            </SheetContent>
          </Sheet>
        </div>
      ) : (
        <div className="flex flex-wrap items-center gap-2">
          {search}{typeFacet(false)}{levelFacet(false)}{ackFacet(false)}{teamFacet}{datesFacet}
        </div>
      )}

      {active.length > 0 && (
        <div data-slot="active-filters" role="group" aria-label={t('alh.activeFilters')} className="flex flex-wrap items-center gap-1.5">
          {active.map((f) => {
            const label = labelFor(f)
            return (
              <Badge key={f.key} variant="outline" data-filter={f.key} className="h-7 gap-1 rounded-full bg-primary/5 pr-1 pl-2.5 font-medium pointer-coarse:h-11 pointer-coarse:py-0">
                <span className="max-w-[16rem] truncate">{label}</span>
                <Button type="button" variant="ghost" size="icon-xs" className="size-5 rounded-full pointer-coarse:size-10"
                  aria-label={t('alh.removeFilter', label)} onClick={() => { if (f.key === 'q') setDraft(''); patch(f.patch) }}>
                  <X aria-hidden="true" className="size-3" />
                </Button>
              </Badge>
            )
          })}
          <Button type="button" variant="link" size="xs" className="h-7 px-1 text-muted-foreground pointer-coarse:h-10" onClick={clearAll}>
            {t('alh.clearFilters')}
          </Button>
        </div>
      )}
    </div>
  )
}
