import { useEffect, useId, useState } from 'react'
import {
  Search, SlidersHorizontal, X, FilterX, ChevronDown, Shapes, Gauge, UserCheck, CalendarRange, Users, ArrowUpDown,
} from 'lucide-react'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { ALERT_TYPES, alertTypeMeta, alertTypeLabel } from '../../../utils/alertTypeMeta.js'
import DateTimeField from '../../ui/DateTimeField.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Label } from '@/components/shadcn/label'
import { Separator } from '@/components/shadcn/separator'
import { InputGroup, InputGroupInput, InputGroupAddon } from '@/components/shadcn/input-group'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuLabel, DropdownMenuRadioGroup, DropdownMenuRadioItem,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetFooter, SheetClose } from '@/components/shadcn/sheet'
import { RadioGroup, RadioGroupItem } from '@/components/shadcn/radio-group'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { FieldLabel, Field as ShField, FieldContent, FieldTitle, FieldDescription } from '@/components/shadcn/field'
import { cn } from '@/lib/utils'
import {
  LEVELS, RANGE_ACTIVE, RANGE_RESOLVED, PRESETS, SORT_KEYS, activeAlertFilters, dateKind, dateKindOptions, rangeForKind,
  isPreset, sortParts, sortValue, fmtFilterDate,
} from './alertHistoryModel.js'

/** Radix radyo öğesi değeri boş dize olamaz → "Tümü" için sabit belirteç. */
const ALL = '__all__'
/** Kategori süzgeci (URL `src`) → menüdeki izleme türü adı (İzleme menüsü rozetleri, 2026-09-30). */
const SRC_NAV_KEY = {
  http: 'nav.http', ping: 'nav.ping', port: 'nav.port', dns: 'nav.dns', domain: 'nav.domainmon', keyword: 'nav.keyword',
  page: 'nav.page', pagespeed: 'nav.pagespeed', scripted: 'nav.scripted', cert: 'nav.groupCertificates',
}
/** Dönem çipi / dönem seçicisi etiketi (hızlı dönem belirteci → i18n). */
export const presetLabel = (t, p) => t(`alh.quick.${p}`)

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
 * "Tümü" görünümünde tarih aralığının KİPİ (2026-09-28, regresyon B3; 2026-10-01 üçüncü seçenek "aralıkta kapananlar"):
 * aralıkta AÇILANLAR (varsayılan), aralıkta AKTİF olanlar (daha önce açılıp aralığa devredenler dahil — haftalık
 * e-postanın "Haftanın alarmları" sayısıyla aynı küme) ya da aralıkta KAPANANLAR (kapanış anı). shadcn RadioGroup
 * "choice card" (FieldLabel + Field): kartın tamamı dokunma hedefi (≥ 40 px), fark görünür açıklamayla yazılı.
 */
function RangeModePicker({ filters, patch }) {
  const t = useT()
  const base = useId()
  const value = filters.range === RANGE_ACTIVE ? RANGE_ACTIVE : filters.range === RANGE_RESOLVED ? RANGE_RESOLVED : 'opened'
  const options = [
    { value: 'opened', title: t('alh.range.opened'), hint: t('alh.range.openedHint') },
    { value: RANGE_ACTIVE, title: t('alh.range.active'), hint: t('alh.range.activeHint') },
    { value: RANGE_RESOLVED, title: t('alh.range.resolved'), hint: t('alh.range.resolvedHint') },
  ]
  return (
    <div className="flex flex-col gap-1.5">
      <span id={`${base}-legend`} className="text-xs font-semibold text-muted-foreground">{t('alh.range.legend')}</span>
      <RadioGroup value={value} onValueChange={(v) => patch({ range: v === 'opened' ? '' : v })}
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

/** Tarih aralığı gövdesi: ("Tümü"nde kip seçimi) + Başlangıç / Bitiş (ortak gün seçici, ui/DateTimeField). Hızlı dönemler araç çubuğunda. */
function DateRangeBody({ filters, patch, tab }) {
  const t = useT()
  return (
    <div className="flex flex-col gap-2.5">
      {tab === 'all' && <RangeModePicker filters={filters} patch={patch} />}
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
 * Hızlı dönem seçici (2026-10-01): Tüm zamanlar · Son 1 saat · Son 24 saat · Son 7 gün · Son 30 gün · Özel — shadcn
 * ToggleGroup (tek seçim, outline). Belirteç URL `from`'a yazılır ve istek anında göreli hesaplanır (paylaşılan bağlantı
 * "son 7 gün"ü hep bugüne göre açar). "Özel" tarih seçiciyi açar (masaüstü Popover / telefon Sheet). Telefonda satır
 * yatay kayar (kırpan kapsayıcı içinde `overflow-x-auto`), öğeler ≥ 40 px dokunma hedefi.
 * Test kancası: `data-slot="alert-presets"`, öğeler `radio` rolü + `data-preset`.
 */
function PeriodPresets({ filters, patch, onCustom }) {
  const t = useT()
  const value = isPreset(filters.from) ? filters.from : (filters.from || filters.to) ? 'custom' : 'all'
  const items = [['all', t('alh.quick.all')], ...PRESETS.map((p) => [p, presetLabel(t, p)]), ['custom', t('alh.quick.custom')]]
  return (
    <div data-slot="alert-presets" className="min-w-0 max-w-full overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
      <ToggleGroup type="single" variant="outline" size="sm" value={value} aria-label={t('alh.preset.legend')} className="w-max"
        onValueChange={(v) => {
          if (!v) return   // etkin öğeye yeniden basmak Radix'te seçimi boşaltır — tek seçim sözleşmesi: yok say
          if (v === 'custom') onCustom?.()
          else if (v === 'all') patch({ from: '', to: '' })
          else patch({ from: v, to: '' })
        }}>
        {items.map(([v, label]) => (
          <ToggleGroupItem key={v} value={v} data-preset={v}
            className="h-9 px-3 text-xs font-medium whitespace-nowrap pointer-coarse:h-10 data-[state=on]:bg-primary/10 data-[state=on]:text-primary">
            {label}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
    </div>
  )
}

/** Aralığın uygulandığı tarih alanı (Kapalı: Kapanış | Açılış · Tümü: Açılış | Kapanış | Aktif olduğu dönem) — açık görünümde yok. */
function DateKindSelect({ filters, patch, tab, className }) {
  const t = useT()
  const id = useId()
  const opts = dateKindOptions(tab)
  if (opts.length === 0) return null
  return (
    <span className={cn('inline-flex items-center gap-1.5', className)}>
      <Label htmlFor={id} className="text-xs font-semibold whitespace-nowrap text-muted-foreground max-sm:sr-only">{t('alh.dateKind.label')}</Label>
      <NativeSelect id={id} data-slot="alert-date-kind" value={dateKind(filters, tab)}
        onChange={(e) => patch({ range: rangeForKind(e.target.value, tab) })} className="h-9 pointer-coarse:h-10">
        {opts.map((k) => <NativeSelectOption key={k} value={k}>{t(`alh.dateKind.${k}`)}</NativeSelectOption>)}
      </NativeSelect>
    </span>
  )
}

/** Sıralama seçici — açık görünümde (kartlar) ve telefonda tek sıralama yüzeyi; tabloda başlıklarla aynı seçenekler. */
function SortSelect({ filters, patch, tab, className }) {
  const t = useT()
  const id = useId()
  const cur = sortParts(filters.sort, tab)
  const keys = SORT_KEYS.filter((k) => k !== 'resolved' || tab !== 'open')
  return (
    <span className={cn('inline-flex items-center gap-1.5', className)}>
      <Label htmlFor={id} className="inline-flex items-center gap-1 text-xs font-semibold whitespace-nowrap text-muted-foreground max-sm:sr-only">
        <ArrowUpDown aria-hidden="true" className="size-3.5" />{t('alh.sort.label')}
      </Label>
      <NativeSelect id={id} data-slot="alert-sort" value={`${cur.key}_${cur.dir}`}
        onChange={(e) => { const [k, d] = e.target.value.split('_'); patch({ sort: sortValue(k, d, tab) }) }}
        className="h-9 pointer-coarse:h-10">
        {keys.flatMap((k) => ['desc', 'asc'].map((d) => (
          <NativeSelectOption key={`${k}_${d}`} value={`${k}_${d}`}>{t(`alh.sort.${k}.${d}`)}</NativeSelectOption>
        )))}
      </NativeSelect>
    </span>
  )
}

/**
 * Alarm Geçmişi süzgeç araç çubuğu — Olaylar konsoluyla (incidents/IncidentsToolbar) aynı düzen: üstte hızlı dönem
 * seçici + tarih alanı + sıralama; altta geniş ekranda tek sarılan satır (arama + faset menüleri + tarih), telefonda
 * arama + "Süzgeçler (n)" düğmesi (alt Sheet). Etkin süzgeçler altta çip (× ile tek tek, "Temizle" ile hepsi). Hepsi
 * SUNUCUYA gider (sayfalama + sayaçlar doğru kalsın). Arama 300 ms sessizlikten sonra uygulanır; Enter hemen uygular.
 */
export default function AlertToolbar({ tab, filters, patch, reset, typeCounts = {}, teams = [], phone = false }) {
  const t = useT()
  const locale = useDateLocale()
  const [sheetOpen, setSheetOpen] = useState(false)
  const [datesOpen, setDatesOpen] = useState(false)
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
  const kind = dateKind(filters, tab)
  const rangeTitle = kind === 'active' ? t('alh.facet.activeRange') : kind === 'resolved' ? t('alh.facet.resolvedRange') : t('alh.facet.openedRange')
  const rangeValue = isPreset(filters.from) ? presetLabel(t, filters.from)
    : filters.from || filters.to
      ? `${filters.from ? fmtFilterDate(filters.from, locale) : '…'} → ${filters.to ? fmtFilterDate(filters.to, locale) : '…'}`
      : null

  const labelFor = (f) => {
    switch (f.key) {
      case 'src': return t('alh.chip.src', t(SRC_NAV_KEY[f.value] || 'nav.groupMonitoring'))
      case 'type': return alertTypeLabel(t, f.value)
      case 'level': return t(`alh.level.${String(f.value).toLowerCase()}`)
      case 'ack': return f.value === 'ack' ? t('alh.ackOnly') : t('alh.unackedOnly')
      case 'team': return t('alh.chip.team', teamName(f.value))
      case 'q': return t('alh.chip.search', f.value)
      case 'range': return f.value === RANGE_ACTIVE ? t('alh.chip.active') : f.value === RANGE_RESOLVED ? t('alh.chip.resolvedRange') : t('alh.chip.openedRange')
      case 'preset': return t('alh.chip.preset', presetLabel(t, f.value))
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
      <InputGroupInput type="search" placeholder={t('alh.searchPlaceholder')} aria-label={t('alh.searchPlaceholder')} data-page-search=""
        value={draft} onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); applyNow() } }} />
      <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
    </InputGroup>
  )
  const datesFacet = (
    <Popover open={datesOpen} onOpenChange={setDatesOpen}>
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
        <>
          <PeriodPresets filters={filters} patch={patch} onCustom={() => setSheetOpen(true)} />
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
                  <div className="flex flex-col gap-1.5">
                    <span className="text-xs font-semibold text-muted-foreground">{rangeTitle}</span>
                    {tab === 'closed' && <DateKindSelect filters={filters} patch={patch} tab={tab} />}
                    <DateRangeBody filters={filters} patch={patch} tab={tab} />
                  </div>
                </div>
                <SheetFooter className="flex-row justify-between">
                  <Button type="button" variant="ghost" onClick={clearAll} disabled={active.length === 0}><FilterX aria-hidden="true" />{t('alh.clearFilters')}</Button>
                  <SheetClose asChild><Button type="button">{t('alh.applyFilters')}</Button></SheetClose>
                </SheetFooter>
              </SheetContent>
            </Sheet>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <SortSelect filters={filters} patch={patch} tab={tab} />
            <DateKindSelect filters={filters} patch={patch} tab={tab} />
          </div>
        </>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-2">
            <PeriodPresets filters={filters} patch={patch} onCustom={() => setDatesOpen(true)} />
            <DateKindSelect filters={filters} patch={patch} tab={tab} />
            <SortSelect filters={filters} patch={patch} tab={tab} className="sm:ml-auto" />
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {search}{typeFacet(false)}{levelFacet(false)}{ackFacet(false)}{teamFacet}{datesFacet}
          </div>
        </>
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
