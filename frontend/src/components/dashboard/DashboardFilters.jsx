import { useId, useState } from 'react'
import {
  ArrowUpDown, CalendarClock, Check, ChevronDown, CircleDot, FilterX, Folder, Layers, Search, SlidersHorizontal, Tag, Users, X,
} from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { PLATFORM_NONE } from '../../utils/platformFilter.js'
import FacetedFilter from '../ui/FacetedFilter.jsx'
import Field from '../ui/Field.jsx'
import { Button } from '@/components/shadcn/button'
import { Command, CommandGroup, CommandInput, CommandItem, CommandList } from '@/components/shadcn/command'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { Separator } from '@/components/shadcn/separator'
import { Sheet, SheetClose, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle, SheetTrigger } from '@/components/shadcn/sheet'
import { cn } from '@/lib/utils'

/**
 * Genel Bakış — sertifika kartı listesinin araç çubuğu (2026-09-28 yeniden tasarım; kullanıcı: "Sırala / Durum / Süre /
 * Takım / Grup / Etiket satırı telefonda dağınık sarıyor"). Saf sunum: süzgeç DURUMU ve anlamı App.jsx'te (boru hattı,
 * URL eşlemesi `q` / `platform`, sayfalama sıfırlama); burası yalnız değerleri çizer ve ayarlayıcıları çağırır.
 *
 * Yerleşim (kırılma `useIsMobile` = 768 px — yalnız DAVRANIŞ farkı: Sheet mi satır içi mi):
 *  - Geniş ekran (≥ 768): başlık satırı (başlık + sonuç sayısı · sağda SSL Checker), görünüm satırı (kart görünümü ·
 *    arama · sağda Sıralama), süzgeç satırı (Platform · Durum · Kalan süre · Takım · Grup · Etiket — kendi satırında
 *    sarar), altında etkin süzgeç çipleri. Seçiciler shadcn "faceted filter" hapları (Popover + Command): etiket HAPIN
 *    İÇİNDE → tablette sarınca yetim etiket kalmaz; değer seçiliyse ayraçtan sonra görünür ve hap birincil tona döner.
 *  - Telefon (< 768): başlık satırı, tam genişlik arama, "Süzgeçler (N)" (satırı doldurur) + sağda kart görünümü (< 640
 *    yalnız ikon); seçiciler alttan açılan Sheet'te (40 px yerel seçiciler, "Temizle" / "Uygula (N sertifika)"), çipler
 *    altta, SSL Checker en sonda tam genişlik. Seçimler ANINDA uygulanır (sayaç canlı); "Uygula" yalnız kapatır.
 *
 * Çipin TAMAMI kaldırma düğmesidir (adı "<süzgeç>: <değer> süzgecini kaldır"); "Filtreleri temizle" arama dâhil hepsini
 * App'in `onClearAll`'u ile sıfırlar (istatistik kartı süzgeci de — eski düğmeyle aynı). Sheet'in "Temizle"si yalnız
 * Sheet'teki seçicileri varsayılana döndürür (aramaya dokunmaz).
 *
 * Test kancaları: `data-slot="dashboard-filters" | "dashboard-domain-search" | "dashboard-filter" (data-filter=<anahtar>) |
 * "dashboard-filter-chip" | "dashboard-result-count" | "dashboard-filters-trigger" | "dashboard-filter-sheet"`; tur kancaları
 * `data-tour="dash-filters"` (kök) ve `data-tour="add-domain"` (SSL Checker — komut paleti içindeki `input`'a odaklanır).
 *
 * @param values   { search, sort, status, expiry, team, group, tag, platform[] }
 * @param setters  aynı anahtarlarla App durum ayarlayıcıları
 * @param options  { team, group, tag, platform } — seçenek listeleri ({value,label}[]); `null` = seçici gizli (veride yok)
 * @param densityToggle  App'te çizilen `<CardDensityToggle>` (kart yoğunluğu standardı App.jsx'te pinli)
 * @param sslChecker     { value, onChange, onSubmit, busy }
 */
export const DASH_FILTER_DEFAULTS = Object.freeze({
  search: '', sort: 'default', status: 'all', expiry: 'all', team: 'all', group: 'all', tag: 'all', platform: Object.freeze([]),
})

/** Telefon Sheet'indeki seçiciler — "Süzgeçler (N)" ve Sheet'in "Temizle"si bunları kapsar (arama satırda kalır). */
const SHEET_KEYS = ['sort', 'status', 'expiry', 'team', 'group', 'tag', 'platform']

const labelOf = (opts, v) => opts?.find((o) => String(o.value) === String(v))?.label ?? String(v)

export default function DashboardFilters({
  values, setters, options = {}, onClearAll, shown, total, densityToggle = null, sslChecker = null,
}) {
  const t = useT()
  const phone = useIsMobile()
  const titleId = useId()

  const sortOptions = [
    { value: 'default', label: t('app.sortDefault') },
    { value: 'asc', label: t('app.sortAsc') },
    { value: 'desc', label: t('app.sortDesc') },
  ]
  const statusOptions = [
    { value: 'all', label: t('app.all') },
    { value: 'valid', label: t('app.valid') },
    { value: 'warning', label: t('app.warning') },
    { value: 'error', label: t('app.error') },
  ]
  const expiryOptions = [
    { value: 'all', label: t('app.all') },
    { value: 'expired', label: t('app.expired') },
    { value: 'days7', label: t('app.days7') },
    { value: 'days30', label: t('app.days30') },
    { value: 'days90', label: t('app.days90') },
  ]

  /** Tek seçimli süzgeçler — hap (geniş ekran) ve yerel seçici (Sheet) AYNI tanımdan çizilir. */
  const single = [
    { key: 'status', label: t('dash.flt.status'), icon: CircleDot, options: statusOptions },
    { key: 'expiry', label: t('dash.flt.expiry'), icon: CalendarClock, options: expiryOptions },
    options.team && { key: 'team', label: t('dash.flt.team'), icon: Users, options: options.team, searchPlaceholder: t('dash.flt.teamSearch') },
    options.group && { key: 'group', label: t('dash.flt.group'), icon: Folder, options: options.group, searchPlaceholder: t('dash.flt.groupSearch') },
    options.tag && { key: 'tag', label: t('dash.flt.tag'), icon: Tag, options: options.tag, searchPlaceholder: t('dash.flt.tagSearch') },
  ].filter(Boolean)
  const sortDef = { key: 'sort', label: t('dash.flt.sort'), icon: ArrowUpDown, options: sortOptions, showValue: true }

  // ── Etkin süzgeç çipleri: arama · tek seçimliler · platform (seçili her kod ayrı çip) · sıralama ──
  const platformSel = Array.isArray(values.platform) ? values.platform : []
  const chips = []
  if (values.search) chips.push({ id: 'search', key: 'search', label: t('dash.flt.search'), value: values.search, onRemove: () => setters.search('') })
  const labels = { status: statusOptions, expiry: expiryOptions, team: options.team, group: options.group, tag: options.tag }
  const noneLabel = { team: t('app.noTeam'), group: t('app.noGroup'), tag: t('mon.noTags') }
  for (const key of ['status', 'expiry', 'team', 'group', 'tag']) {
    const v = values[key]
    if (v === DASH_FILTER_DEFAULTS[key]) continue
    // Seçici gizliyken de (seçenek listesi yok — ör. bildirimden gelen takım süzgeci) değer çipte görünür ve kaldırılabilir
    const shownValue = labels[key]?.find((o) => String(o.value) === String(v))?.label ?? (v === '__none__' ? noneLabel[key] : String(v))
    chips.push({ id: key, key, label: t(`dash.flt.${key}`), value: shownValue, onRemove: () => setters[key](DASH_FILTER_DEFAULTS[key]) })
  }
  for (const code of platformSel) {
    const value = code === PLATFORM_NONE ? t('app.platformNone') : labelOf(options.platform, code)
    chips.push({
      id: `platform:${code}`, key: 'platform', label: t('app.platformFilter'), value,
      onRemove: () => setters.platform(platformSel.filter((c) => c !== code)),
    })
  }
  if (values.sort !== DASH_FILTER_DEFAULTS.sort) {
    chips.push({ id: 'sort', key: 'sort', label: t('dash.flt.sort'), value: labelOf(sortOptions, values.sort), onRemove: () => setters.sort(DASH_FILTER_DEFAULTS.sort) })
  }
  const sheetActive = chips.filter((c) => c.key !== 'search').length
  // Yalnız etkin olanlar sıfırlanır (değişmeyen süzgeç için ayarlayıcı çağrılmaz — sayfalama boşuna başa dönmesin)
  const clearSheet = () => {
    for (const k of SHEET_KEYS) {
      if (k === 'platform') { if (platformSel.length) setters.platform([]) }
      else if (values[k] !== DASH_FILTER_DEFAULTS[k]) setters[k](DASH_FILTER_DEFAULTS[k])
    }
  }

  const countText = shown === total ? t('app.certCount', total) : t('dash.flt.count', shown, total)

  // ── Parçalar ──
  const search = (
    <InputGroup data-slot="dashboard-domain-search"
      className={phone ? 'h-12 w-full' : 'h-8 w-full sm:w-80 lg:w-[26rem] pointer-coarse:h-10'}>
      <InputGroupInput type="text" placeholder={t('app.searchPlaceholder')} aria-label={t('app.searchPlaceholder')}
        value={values.search} className={phone ? 'h-full text-base' : undefined}
        onChange={(e) => setters.search(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Escape' && values.search) { e.preventDefault(); e.stopPropagation(); setters.search('') } }} />
      <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
      {values.search && (
        <InputGroupAddon align="inline-end">
          <InputGroupButton size="icon-xs" className={phone ? 'size-10' : undefined} onClick={() => setters.search('')}
            title={t('app.clearFilter')} aria-label={t('app.clearFilter')}>
            <X aria-hidden="true" />
          </InputGroupButton>
        </InputGroupAddon>
      )}
    </InputGroup>
  )
  // Platform (çoklu seçim) — ortak faset süzgeci. Geniş ekranda haplarla aynı dil (seçim varsa birincil ton); Sheet'te
  // diğer alanlar gibi etiketli ve tetik o anki seçimi yazar ("Tümü" / "IIS, OpenShift").
  const platformTitle = platformSel.length
    ? platformSel.map((c) => (c === PLATFORM_NONE ? t('app.platformNone') : labelOf(options.platform, c))).join(', ')
    : t('app.all')
  const platform = options.platform && (
    <FacetedFilter title={phone ? <span className="min-w-0 truncate">{platformTitle}</span> : t('app.platformFilter')}
      icon={Layers} tooltip={t('app.platformFilterTip')}
      searchPlaceholder={t('app.platformSearch')} options={options.platform} value={platformSel}
      onChange={(next) => setters.platform(next)}
      className={cn(phone ? 'h-10 w-full justify-start border-solid font-normal' : 'pointer-coarse:h-10',
        !phone && platformSel.length > 0 && 'border-solid border-primary/50 bg-primary/5 hover:bg-primary/10 dark:bg-primary/10 dark:hover:bg-primary/20')} />
  )
  const ssl = sslChecker && <SslChecker {...sslChecker} phone={phone} />

  const title = (
    <div className="flex min-w-0 flex-wrap items-baseline gap-x-2.5 gap-y-1">
      <h2 id={titleId} data-slot="dashboard-cards-title" className="m-0 text-lg leading-tight font-semibold text-foreground">
        {t('app.cardsTitle')}
      </h2>
      <span role="status" data-slot="dashboard-result-count" className="text-sm text-muted-foreground tabular-nums">{countText}</span>
    </div>
  )

  const chipRow = chips.length > 0 && (
    <div data-slot="dashboard-active-filters" role="group" aria-label={t('dash.flt.active')}
      className="flex min-w-0 flex-wrap items-center gap-1.5">
      {chips.map((c) => {
        const text = t('dash.flt.pair', c.label, c.value)
        return (
          <Button key={c.id} type="button" variant="outline" size="xs" data-slot="dashboard-filter-chip" data-filter={c.key}
            aria-label={t('dash.flt.remove', text)} title={t('dash.flt.remove', text)}
            className="h-7 max-w-full gap-1 rounded-full border-primary/30 bg-primary/5 pr-1.5 pl-2.5 font-normal hover:bg-primary/10 max-md:h-10 max-md:px-3 dark:bg-primary/10 dark:hover:bg-primary/20 pointer-coarse:h-10"
            onClick={c.onRemove}>
            <span className="min-w-0 truncate"><span className="text-muted-foreground">{c.label}:</span> <span className="font-medium">{c.value}</span></span>
            <X aria-hidden="true" className="size-3.5 opacity-70" />
          </Button>
        )
      })}
      <Button type="button" variant="link" size="xs" data-slot="dashboard-clear-filters"
        className="h-7 px-1.5 text-muted-foreground max-md:h-10 pointer-coarse:h-10" onClick={onClearAll}>
        {t('app.clearFilters')}
      </Button>
    </div>
  )

  if (phone) {
    return (
      <section data-slot="dashboard-filters" data-layout="phone" data-tour="dash-filters" aria-labelledby={titleId}
        className="mb-4 flex min-w-0 flex-col gap-3">
        {title}
        {search}
        {/* "Süzgeçler (N)" satırı doldurur; kart görünümü sağda (< 640 px yalnız ikon — App `hideLabelsOnPhone` verir) */}
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <FilterSheet t={t} active={sheetActive} shown={shown} onClear={clearSheet}>
            <SheetSelect label={sortDef.label} value={values.sort} options={sortDef.options} onChange={setters.sort} />
            {single.map((f) => (
              <SheetSelect key={f.key} label={f.label} value={values[f.key]} options={f.options} onChange={setters[f.key]} />
            ))}
            {platform && (
              <div role="group" aria-labelledby={`${titleId}-pf`} className="flex flex-col gap-1.5">
                <span id={`${titleId}-pf`} className="text-sm leading-none font-semibold">{t('app.platformFilter')}</span>
                {platform}
              </div>
            )}
          </FilterSheet>
          {densityToggle && <div className="shrink-0 [&_[data-slot=toggle-group-item]]:h-10">{densityToggle}</div>}
        </div>
        {chipRow}
        {ssl}
      </section>
    )
  }

  return (
    <section data-slot="dashboard-filters" data-layout="inline" data-tour="dash-filters" aria-labelledby={titleId}
      className="mb-4 flex min-w-0 flex-col gap-3">
      <div className="flex min-w-0 flex-wrap items-center gap-x-4 gap-y-2">
        {title}
        {ssl}
      </div>
      <div role="group" aria-label={t('dash.flt.toolbar')} className="flex min-w-0 flex-col gap-2">
        {/* Görünüm satırı: kart görünümü · arama · sağda Sıralama (görünüm denetimleri bir arada) */}
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          {densityToggle}
          {search}
          <div className="ml-auto max-w-full">
            <FilterPill id="sort" label={sortDef.label} icon={sortDef.icon} value={values.sort} options={sortDef.options}
              defaultValue={DASH_FILTER_DEFAULTS.sort} showValue align="end" onChange={setters.sort} />
          </div>
        </div>
        {/* Süzgeç satırı: haplar KENDİ satırında sarar — görünüm satırıyla rastgele bölünmez */}
        <div data-slot="dashboard-filter-pills" className="flex min-w-0 flex-wrap items-center gap-2">
          {platform}
          {single.map((f) => (
            <FilterPill key={f.key} id={f.key} label={f.label} icon={f.icon} value={values[f.key]} options={f.options}
              defaultValue={DASH_FILTER_DEFAULTS[f.key]} searchPlaceholder={f.searchPlaceholder} onChange={setters[f.key]} />
          ))}
        </div>
      </div>
      {chipRow}
    </section>
  )
}

/**
 * Tek seçimli süzgeç hapı — shadcn faceted filter tetiği (outline Button; seçim yokken kesikli kenar) + Popover + Command
 * listesi. Etiket hapın içinde; değer seçiliyse (ya da `showValue`) ayraçtan sonra görünür. Seçim listeyi kapatır. Uzun
 * listelerde (> 7 seçenek) arama kutusu. Ad: "<etiket>: <değer>" (ekran okuyucu değeri de duyar; görünür etiket adın içinde).
 */
function FilterPill({ id, label, icon: Icon, value, options, onChange, defaultValue, showValue = false, searchPlaceholder, align = 'start' }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const valueLabel = labelOf(options, value)
  const active = String(value) !== String(defaultValue)
  const searchable = options.length > 7
  const q = query.trim().toLocaleLowerCase()
  const visible = searchable && q ? options.filter((o) => String(o.label ?? '').toLocaleLowerCase().includes(q)) : options
  const pick = (v) => { onChange(v); setOpen(false); setQuery('') }
  return (
    <Popover open={open} onOpenChange={(next) => { setOpen(next); if (!next) setQuery('') }}>
      <PopoverTrigger asChild>
        <Button type="button" variant="outline" size="sm" data-slot="dashboard-filter" data-filter={id}
          data-active={active ? 'true' : undefined} aria-label={t('dash.flt.pair', label, valueLabel)}
          className={cn('h-8 max-w-full gap-1.5 font-normal pointer-coarse:h-10',
            !active && !showValue && 'border-dashed',
            active && 'border-primary/50 bg-primary/5 hover:bg-primary/10 dark:bg-primary/10 dark:hover:bg-primary/20')}>
          <Icon aria-hidden="true" className="text-muted-foreground" />
          <span className="font-medium">{label}</span>
          {(active || showValue) && (
            <>
              <Separator orientation="vertical" className="mx-0.5 h-4" />
              <span className="min-w-0 max-w-[14rem] truncate">{valueLabel}</span>
            </>
          )}
          <ChevronDown aria-hidden="true" className="opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align={align} className="z-(--z-menu) w-64 max-w-[calc(100vw-2rem)] p-0">
        <Command shouldFilter={false} loop>
          {searchable && <CommandInput placeholder={searchPlaceholder || label} value={query} onValueChange={setQuery} />}
          <CommandList>
            {visible.length === 0 ? (
              <div className="py-6 text-center text-sm text-muted-foreground" role="status">{t('ss.noResult')}</div>
            ) : (
              <CommandGroup>
                {visible.map((o) => {
                  const checked = String(o.value) === String(value)
                  return (
                    <CommandItem key={String(o.value)} value={`o:${String(o.value)}`} data-checked={checked ? 'true' : undefined}
                      className={cn(checked && 'font-medium')} onSelect={() => pick(o.value)}>
                      <Check aria-hidden="true" className={cn('text-primary', checked ? 'opacity-100' : 'opacity-0')} />
                      <span className="min-w-0 flex-1 truncate">{o.label}</span>
                    </CommandItem>
                  )
                })}
              </CommandGroup>
            )}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  )
}

/** Telefon: "Süzgeçler (N)" düğmesi + alttan Sheet (40 px hedefler, güvenli alan payı; yardım düğmesinin üstünde). */
function FilterSheet({ t, active, shown, onClear, children }) {
  const [open, setOpen] = useState(false)
  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetTrigger asChild>
        <Button type="button" variant="outline" data-slot="dashboard-filters-trigger" data-active={active ? 'true' : undefined}
          className={cn('h-10 min-w-0 flex-[1_1_9rem] gap-1.5', active > 0 && 'border-primary/50 bg-primary/5 dark:bg-primary/10')}>
          <SlidersHorizontal aria-hidden="true" />
          <span className="truncate">{active > 0 ? t('dash.flt.filtersN', active) : t('dash.flt.filters')}</span>
        </Button>
      </SheetTrigger>
      {/* z-[1001]: yüzen yardım düğmesinin (.help-fab 900) üstünde — "Uygula" örtülmesin (HistoryToolbar ile aynı). ÖRTÜ de
          1000'e çıkar (Ek 3/8): varsayılan z-50 örtü, 50–1000 katmanındaki öğeleri (tur çipi z-890 gibi) karartmadan üstte ve
          tıklanabilir bırakıyordu. Kardeş süzgeç çekmeceleri de aynı (kapı: filterSheetOverlay.test.js). */}
      <SheetContent overlayClassName="z-[1000]" side="bottom" showCloseButton={false} data-slot="dashboard-filter-sheet"
        className="z-[1001] max-h-[90dvh] gap-0 rounded-t-xl p-0">
        <SheetHeader className="flex-row items-start gap-3 border-b p-4">
          <div className="flex min-w-0 flex-1 flex-col gap-1">
            <SheetTitle className="flex items-center gap-2"><SlidersHorizontal aria-hidden="true" className="size-4" />{t('dash.flt.filters')}</SheetTitle>
            <SheetDescription>{t('dash.flt.sheetDesc')}</SheetDescription>
          </div>
          <SheetClose asChild>
            <Button type="button" variant="ghost" size="icon" className="-mt-1 -mr-1 size-10 shrink-0 text-muted-foreground" aria-label={t('app.close')}>
              <X aria-hidden="true" />
            </Button>
          </SheetClose>
        </SheetHeader>
        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-4">{children}</div>
        <SheetFooter className="flex-row gap-2 border-t p-4 pb-[max(1rem,env(safe-area-inset-bottom))]">
          <Button type="button" variant="outline" className="h-10" disabled={active === 0} onClick={onClear}>
            <FilterX aria-hidden="true" />{t('dash.flt.clear')}
          </Button>
          <SheetClose asChild>
            <Button type="button" data-slot="dashboard-filter-apply" className="h-10 min-w-0 flex-1">
              <span className="truncate">{shown === 1 ? t('dash.flt.applyOne') : t('dash.flt.apply', shown)}</span>
            </Button>
          </SheetClose>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

/** Sheet içindeki etiketli yerel seçici — telefonda işletim sisteminin seçicisi açılır; 40 px, 16 px yazı (iOS büyütmesin). */
function SheetSelect({ label, value, options, onChange }) {
  return (
    <Field label={label} className="mb-0">
      {({ id }) => (
        <div className="w-full *:w-full">
          <NativeSelect id={id} value={value} onChange={(e) => onChange(e.target.value)} className="h-10 text-base">
            {options.map((o) => <NativeSelectOption key={String(o.value)} value={o.value}>{o.label}</NativeSelectOption>)}
          </NativeSelect>
        </div>
      )}
    </Field>
  )
}

/**
 * SSL Checker — alan adı kutusu + düğme TEK birim (shadcn InputGroup; Enter da düğme de `onSubmit`). Geniş ekranda başlık
 * satırının EN SAĞINDA kalan yeri doldurur (en az 22rem, en çok 34rem; 2026-09-26 kullanıcı isteği), telefonda araç
 * çubuğunun en altında tam genişlik (48 px kutu, 40 px düğme). Tüm Sertifikalar başlığındaki kutuyla aynı görünüm.
 */
function SslChecker({ value, onChange, onSubmit, busy, phone }) {
  const t = useT()
  return (
    <InputGroup data-tour="add-domain"
      className={phone ? 'h-12 w-full' : 'h-9 w-full md:ml-auto md:w-auto md:min-w-[22rem] md:max-w-[34rem] md:flex-1'}>
      <InputGroupInput type="text" placeholder={t('app.newDomainPlaceholder')} aria-label={t('app.newDomainPlaceholder')}
        className={phone ? 'h-full text-base' : undefined}
        value={value} onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && onSubmit()} />
      <InputGroupAddon align="inline-end">
        <InputGroupButton variant="success" size="sm" className={phone ? 'h-10' : 'h-7 pointer-coarse:h-8'}
          onClick={onSubmit} disabled={busy} aria-busy={busy || undefined}>
          {busy ? t('app.checkingDomain') : t('app.checkBtn')}
        </InputGroupButton>
      </InputGroupAddon>
    </InputGroup>
  )
}
