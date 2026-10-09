import { useId, useState, useRef } from 'react'
import { X, Download, Bookmark, Columns3, GripVertical, ShieldAlert, ListFilter, Search, Building2, SlidersHorizontal, Hash, Rows3, AlignJustify } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useIsMobile } from '../../hooks/use-mobile.js'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import CopyLinkButton from '../ui/CopyLinkButton.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import TeamScopeSwitch from '../ui/TeamScopeSwitch.jsx'
import { TABLE_COLUMNS, COLUMN_BY_KEY, STATUS_OPTIONS, WINDOW_OPTIONS, TIER_OPTIONS, EMPTY_FILTERS,
  activeFilterChips, defaultCols, moveCol, trustFilterOptions } from './certTableModel.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent } from '@/components/shadcn/card'
import { Input } from '@/components/shadcn/input'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Label } from '@/components/shadcn/label'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Toggle } from '@/components/shadcn/toggle'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { Sheet, SheetClose, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle, SheetTrigger } from '@/components/shadcn/sheet'
import { cn } from '@/lib/utils'

/**
 * Tüm Sertifikalar — süzgeç çubuğu (2026-09-27 yeniden tasarım; kullanıcı: "daha profesyonel, daha zengin").
 *
 * Üç katman, hepsi shadcn:
 *  1) Süzgeç çubuğu (Card): baskın arama (InputGroup: büyüteç, temizle ×, Esc temizler — alan adı VEYA SAN; veren
 *     araması sunucuda ayrı parametre olduğu için ikincil, küçük bir kutu), "Takımlarım | Tüm takımlar" anahtarı ve
 *     facet kontrolleri (takım · vade · kademe · durum · sıralama — ui/SearchableSelect; tetik seçili değeri gösterir)
 *     + sayaç rozetli bas-bırak süzgeçler (yalnız güvensiz, 443 dışı port). Telefonda facet'ler bir Sheet'e taşınır
 *     ("Süzgeçler (n)"); arama satırda kalır.
 *  2) Aktif süzgeç çipleri ("Takım: Takım A ×" …) + "Tümünü temizle" + sonuç sayacı rozeti ("8 / 42 sertifika").
 *  3) Araç satırı: sol = görünüm (yoğunluk, sütunlar, kolon süzgeçleri), sağ = eylemler (ön ayarlar, CSV, bağlantı);
 *     md+ ikon + etiket, telefonda ikon + ipucu (ad aria-label'da).
 *
 * Sözleşmeler DEĞİŞMEDİ: `data-filter-chip="<anahtar>"` / `"clear"`, `data-preset-apply`, `data-col-item`, tur kancaları
 * (`ct-filters`, `ct-presets`, `ct-columns`, `ct-csv`), alan kimlikleri `ct-f-*`, URL `c_*`, ön ayar / görünüm kayıtları.
 * Saf sunum: durum ve eylemler dışarıdan (CertificatesTable).
 */
/** Aktif süzgeç çipi (eski .ct-chip): birincil tonlu hap düğme. En fazla satır genişliği (uzun alan adı/veren taşmaz,
 *  metin "…" ile kısalır); telefon/dokunmatikte 40 px yükseklik (2026-10-09). */
const CHIP = 'h-auto max-w-full gap-1 rounded-full border-primary bg-primary/10 px-2.5 py-0.5 text-[.82em] font-semibold text-primary shadow-none hover:bg-primary/20 hover:text-primary dark:border-primary dark:bg-primary/15 dark:hover:bg-primary/25 max-md:h-10 pointer-coarse:h-10'
/** Bas-bırak süzgeç: basılıyken birincil dolgu; sayaç rozeti içinde. */
const TOGGLE_CHIP = 'h-9 gap-1.5 rounded-md px-3 text-[.86em] font-medium data-[state=on]:border-primary data-[state=on]:bg-primary data-[state=on]:text-primary-foreground data-[state=on]:[&_[data-slot=badge]]:bg-primary-foreground/20 data-[state=on]:[&_[data-slot=badge]]:text-primary-foreground'
/** Facet kutusu: küçük büyük-harf başlık + kontrol. */
const FACET = 'flex min-w-0 flex-col gap-1'
const FACET_LABEL = 'text-[10.5px] font-bold tracking-[.06em] text-muted-foreground uppercase'
/** Sayaç rozeti (süzgeç çipi içinde). */
const COUNT = 'h-4 min-w-4 px-1 text-[10px] font-semibold tabular-nums'

export default function CertTableToolbar({
  filters, onFilter, onReset, facets, cols, onCols, density, onDensity, sortBy, onSort,
  presets, onSavePreset, onApplyPreset, onDeletePreset, exportUrl, total, teamNames = {},
  colFilters = false, onColFilters = null,   // kolon süzgeç satırı anahtarı (2026-09-22)
  scope = 'mine', onScope = null, visibleToAll = false,   // "Takımlarım | Tüm takımlar" (org geneli görünürlük, 2026-09-26)
}) {
  const t = useT()
  const isMobile = useIsMobile()   // yalnız DAVRANIŞ farkı: facet'ler telefonda Sheet'te
  const [colsOpen, setColsOpen] = useState(false)
  const [presetOpen, setPresetOpen] = useState(false)
  const [sheetOpen, setSheetOpen] = useState(false)
  const [presetName, setPresetName] = useState('')
  const dragFrom = useRef(null)
  const idBase = useId()

  const set = (k, v) => onFilter({ ...filters, [k]: v })
  const win = facets?.windows || {}
  const teamOpts = [{ value: '', label: `${t('app.allTeams')}${facets ? ` (${facets.all ?? ''})` : ''}` }]
  for (const tm of facets?.teams || []) teamOpts.push({ value: String(tm.id), label: `${tm.name} (${tm.count})` })
  if (facets && facets.no_team > 0) teamOpts.push({ value: '__none__', label: `${t('app.noTeam')} (${facets.no_team})` })
  // Seçili takım facet'te yoksa (öteki süzgeçler onu sıfırladıysa) seçenek yine görünsün — kullanıcı süzgeci kaldırabilsin
  if (filters.team && !teamOpts.some((o) => o.value === filters.team)) teamOpts.push({ value: filters.team, label: filters.team === '__none__' ? t('app.noTeam') : (teamNames[filters.team] || `#${filters.team}`) })

  const chips = activeFilterChips(filters)
  /** Telefon Sheet rozeti: satırdaki iki metin alanı dışındaki aktif süzgeçler. */
  const facetCount = chips.filter((c) => c.key !== 'domain' && c.key !== 'issuer').length
  const chipLabel = (c) => {
    switch (c.key) {
      case 'domain': return t('tbl.chipSearch', c.value)
      case 'issuer': return t('tbl.chipIssuer', c.value)
      case 'status': return t('tbl.chipStatus', t(STATUS_OPTIONS.find((o) => o.value === c.value)?.labelKey || 'tbl.filterAll'))
      case 'team': return t('tbl.chipTeam', teamOpts.find((o) => o.value === c.value)?.label?.replace(/ \(\d+\)$/, '') || c.value)
      case 'window': return t('tbl.chipWindow', c.value === 'expired' ? t('tbl.winExpired') : t('tbl.winDays', c.value))
      case 'insecure': return t('tbl.onlyInsecure')
      case 'tier': return t('tbl.chipTier', `T${c.value}`)
      case 'port': return c.value === 'nonstd' ? t('tbl.portNonstd') : `${t('tbl.colPort')} ${c.value}`
      case 'fp': return t('tbl.sameCert')
      case 'trust': return t('tbl.chipTrust', t(`tbl.trust.${c.value}`))
      default: return c.value
    }
  }

  const sortOptions = [
    { value: 'priority|asc',        label: t('tbl.sortPriority') },
    { value: 'domain|asc',          label: t('tbl.sortDomainAsc') },
    { value: 'domain|desc',         label: t('tbl.sortDomainDesc') },
    { value: 'issuer|asc',          label: t('tbl.sortIssuerAsc') },
    { value: 'issuer|desc',         label: t('tbl.sortIssuerDesc') },
    { value: 'days_remaining|asc',  label: t('tbl.sortDaysAsc') },
    { value: 'days_remaining|desc', label: t('tbl.sortDaysDesc') },
    { value: 'checked_at|desc',     label: t('tbl.sortChecked') },
    { value: 'shared|desc',         label: t('tbl.sortShared') },
  ]
  if (sortBy && !sortOptions.some((o) => o.value === sortBy)) {
    const [k, d] = sortBy.split('|')
    const col = TABLE_COLUMNS.find((c) => c.sort === k)
    sortOptions.push({ value: sortBy, label: `${col ? t(col.labelKey) : k} ${d === 'desc' ? '↓' : '↑'}` })
  }
  const statusOpts = STATUS_OPTIONS.map((o) => ({
    value: o.value,
    label: o.value === '' ? t('tbl.filterAll') : `${t(o.labelKey)}${facets?.levels?.[o.value] != null ? ` (${facets.levels[o.value]})` : ''}`,
  }))
  const windowOpts = WINDOW_OPTIONS.map((w) => ({
    value: w,
    label: w === '' ? t('tbl.filterAll') : `${w === 'expired' ? t('tbl.winExpired') : t('tbl.winDays', w)}${win[w] != null ? ` (${win[w]})` : ''}`,
  }))
  const tierOpts = TIER_OPTIONS.map((x) => ({ value: x, label: x === '' ? t('tbl.tierAll') : `T${x}${facets?.tiers?.[x] != null ? ` (${facets.tiers[x]})` : ''}` }))
  const trustOpts = [{ value: '', label: t('tbl.filterAll') }, ...trustFilterOptions(t, facets)]

  const savePreset = () => { onSavePreset(presetName.trim()); setPresetName('') }
  const clearSearch = () => set('domain', '')
  /** Esc: yazılanı temizler (kullanıcı imleci kutudayken ikinci Esc pencereyi kapatmaz — yalnız dolu kutuda tüketilir). */
  const escClears = (key) => (e) => { if (e.key === 'Escape' && filters[key]) { e.preventDefault(); e.stopPropagation(); set(key, '') } }

  /** Facet kontrolleri — masaüstünde çubuğun ikinci satırı, telefonda Sheet gövdesi (tek sütun). */
  const facetControls = (mobile) => (
    <div data-slot="ct-facets" className={cn('grid gap-3', mobile ? 'grid-cols-1' : 'grid-cols-2 lg:grid-cols-[repeat(auto-fit,minmax(170px,1fr))]')}>
      <div className={FACET}>
        <Label htmlFor="ct-f-team" className={FACET_LABEL}>{t('tbl.facetTeam')}</Label>
        <SearchableSelect id="ct-f-team" value={filters.team} onChange={(v) => set('team', v)} options={teamOpts} />
      </div>
      <div className={FACET}>
        <Label htmlFor="ct-f-window" className={FACET_LABEL}>{t('tbl.facetWindow')}</Label>
        <SearchableSelect id="ct-f-window" value={filters.window} onChange={(v) => set('window', v)} options={windowOpts} />
      </div>
      <div className={FACET}>
        <Label htmlFor="ct-f-tier" className={FACET_LABEL}>{t('tbl.facetTier')}</Label>
        <SearchableSelect id="ct-f-tier" value={filters.tier} onChange={(v) => set('tier', v)} options={tierOpts} />
      </div>
      <div className={FACET}>
        <Label htmlFor="ct-f-status" className={FACET_LABEL}>{t('tbl.facetStatus')}</Label>
        <SearchableSelect id="ct-f-status" value={filters.status} onChange={(v) => set('status', v)} options={statusOpts} />
      </div>
      <div className={FACET}>
        <Label htmlFor="ct-f-trust" className={FACET_LABEL}>{t('tbl.facetTrust')}</Label>
        <SearchableSelect id="ct-f-trust" value={filters.trust} onChange={(v) => set('trust', v)} options={trustOpts} />
      </div>
      <div className={FACET}>
        <Label htmlFor="ct-f-sort" className={FACET_LABEL}>{t('tbl.facetSort')}</Label>
        <SearchableSelect id="ct-f-sort" value={sortBy} onChange={onSort} options={sortOptions} />
      </div>
      {/* Bas-bırak süzgeçler masaüstünde KENDİ satırında (tam genişlik, etiket solda) — dar ızgara hücresinde alt alta
          düşüyorlardı; telefonda Sheet'te facet'lerin altında. */}
      <div className={cn(FACET, !mobile && 'col-span-full sm:flex-row sm:items-center sm:gap-3')} role="group" aria-labelledby={`${idBase}-more`}>
        <span id={`${idBase}-more`} className={FACET_LABEL}>{t('tbl.moreFilters').replace(/:\s*$/, '')}</span>
        <div className="flex flex-wrap items-center gap-2">
          <Toggle variant="outline" size="sm" className={TOGGLE_CHIP} pressed={!!filters.insecure} data-slot="ct-toggle-insecure"
            onPressedChange={(on) => set('insecure', on)} title={t('tbl.onlyInsecureTitle')}>
            <ShieldAlert aria-hidden="true" className="size-3.5" /> {t('tbl.onlyInsecure')}
            {facets && <Badge variant="secondary" className={COUNT}>{facets.insecure ?? 0}</Badge>}
          </Toggle>
          <Toggle variant="outline" size="sm" className={TOGGLE_CHIP} pressed={filters.port === 'nonstd'} data-slot="ct-toggle-port"
            onPressedChange={(on) => set('port', on ? 'nonstd' : '')}>
            <Hash aria-hidden="true" className="size-3.5" /> {t('tbl.portNonstd')}
            {facets && <Badge variant="secondary" className={COUNT}>{facets.nonstd_port ?? 0}</Badge>}
          </Toggle>
        </div>
      </div>
    </div>
  )

  const resultCount = facets?.all != null && total != null && facets.all !== total
    ? t('tbl.resultCount', total, facets.all)
    : t('tbl.resultCountPlain', total ?? 0)

  /** Araç düğmesi içeriği: md+ ikon + etiket, telefonda yalnız ikon (ad aria-label'da; ipucu tetiğin DIŞINA sarılır —
   *  Tooltip → PopoverTrigger → Button zinciri asChild ile çalışır, tersi tetiği kaybeder). */
  const colsLabel = `${t('tbl.columns')} (${cols.length}/${TABLE_COLUMNS.length})`
  const presetsLabel = `${t('tbl.presets')}${presets.length ? ` (${presets.length})` : ''}`
  const toolContent = (Icon, label) => (<><Icon size={14} aria-hidden="true" /><span className="hidden md:inline">{label}</span></>)

  return (
    <>
      {/* ── 1) Süzgeç çubuğu ── */}
      <Card data-slot="ct-filter-bar" data-tour="ct-filters" className="mb-2.5 gap-0 rounded-lg py-0 shadow-none">
        <CardContent className="flex flex-col gap-3 p-3 sm:p-4">
          <div className="flex flex-wrap items-center gap-2">
            <InputGroup className="w-full sm:w-auto sm:min-w-[240px] sm:flex-[1_1_280px] sm:max-w-[560px]">
              <InputGroupInput id="ct-f-domain" type="search" value={filters.domain} placeholder={t('tbl.searchPh')}
                aria-label={t('tbl.searchLabel')} onChange={(e) => set('domain', e.target.value)} onKeyDown={escClears('domain')} data-page-search="" />
              <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
              {filters.domain && (
                <InputGroupAddon align="inline-end">
                  <InputGroupButton size="icon-xs" onClick={clearSearch} aria-label={t('tbl.clearSearch')}><X aria-hidden="true" /></InputGroupButton>
                </InputGroupAddon>
              )}
            </InputGroup>
            {/* Veren araması: sunucuda AYRI parametre (filter_issuer) → ana aramaya katlanamaz; ikincil, dar kutu */}
            <InputGroup data-slot="ct-issuer" className="w-full sm:w-auto sm:flex-[0_1_220px]">
              <InputGroupInput id="ct-f-issuer" type="search" value={filters.issuer} placeholder={t('tbl.issuerPh')}
                aria-label={t('tbl.issuerLabel')} onChange={(e) => set('issuer', e.target.value)} onKeyDown={escClears('issuer')} />
              <InputGroupAddon><Building2 aria-hidden="true" /></InputGroupAddon>
              {filters.issuer && (
                <InputGroupAddon align="inline-end">
                  <InputGroupButton size="icon-xs" onClick={() => set('issuer', '')} aria-label={t('tbl.clearIssuer')}><X aria-hidden="true" /></InputGroupButton>
                </InputGroupAddon>
              )}
            </InputGroup>
            <TeamScopeSwitch value={scope} onChange={onScope} visible={visibleToAll && !!onScope} />
            {isMobile && (
              // Telefon: facet'ler alttan açılan Sheet'te; tetik aktif facet sayısını taşır
              <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
                <SheetTrigger asChild>
                  <Button type="button" variant={facetCount ? 'default' : 'secondary'} size="sm" data-slot="ct-filters-trigger" className="pointer-coarse:h-10">
                    <SlidersHorizontal size={14} aria-hidden="true" /> {t('tbl.filters')}
                    {facetCount > 0 && <Badge variant="secondary" className={cn(COUNT, 'bg-primary-foreground/20 text-primary-foreground')}>{facetCount}</Badge>}
                  </Button>
                </SheetTrigger>
                <SheetContent side="bottom" className="max-h-[85dvh] overflow-y-auto rounded-t-lg pb-[env(safe-area-inset-bottom)]">
                  <SheetHeader>
                    <SheetTitle>{t('tbl.filters')}</SheetTitle>
                    <SheetDescription>{t('tbl.filtersSheetHint')}</SheetDescription>
                  </SheetHeader>
                  <div className="px-4">{facetControls(true)}</div>
                  {/* Sola yaslı: sağ alt köşede uygulamanın yardım düğmesi (sabit) duruyor — altına düğme koyma */}
                  <SheetFooter className="flex-row justify-start gap-2">
                    <Button type="button" variant="outline" onClick={onReset} disabled={chips.length === 0}>{t('tbl.clearAll')}</Button>
                    <SheetClose asChild><Button type="button">{t('app.close')}</Button></SheetClose>
                  </SheetFooter>
                </SheetContent>
              </Sheet>
            )}
          </div>
          {!isMobile && facetControls(false)}
        </CardContent>
      </Card>

      {/* ── 2) Aktif süzgeç çipleri + sonuç sayacı ── */}
      <div data-slot="ct-chips" className="mb-2.5 flex min-h-7 flex-wrap items-center gap-1.5" aria-live="polite">
        <Badge variant="outline" data-slot="ct-result-count" className="font-semibold tabular-nums">{resultCount}</Badge>
        {chips.map((c) => (
          <Button key={c.key} type="button" variant="outline" size="xs" data-filter-chip={c.key} className={CHIP}
            onClick={() => set(c.key, EMPTY_FILTERS[c.key])} title={t('tbl.removeFilter')}>
            <span className="min-w-0 truncate" title={chipLabel(c)}>{chipLabel(c)}</span> <X aria-hidden="true" className="size-3" />
          </Button>
        ))}
        {chips.length > 0 && (
          <Button type="button" variant="outline" size="xs" data-filter-chip="clear" onClick={onReset}
            className="h-auto rounded-full px-2.5 py-0.5 text-[.82em] font-semibold text-muted-foreground shadow-none max-md:h-10 pointer-coarse:h-10">
            {t('tbl.clearAll')}
          </Button>
        )}
      </div>

      {/* ── 3) Araç satırı: sol görünüm · sağ eylemler ── */}
      <div data-slot="ct-toolbar" className="mb-2.5 flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label={t('tbl.viewGroup')}>
          <SegmentedControl value={density} onChange={onDensity} ariaLabel={t('tbl.density')}
            options={[{ value: 'comfortable', label: t('tbl.densityComfortable'), icon: Rows3 }, { value: 'compact', label: t('tbl.densityCompact'), icon: AlignJustify }]} />

          {/* Sütun seçici — shadcn Popover + Checkbox; görünen sütunlar sürükle-bırak ile sıralanır */}
          <div className="flex" data-tour="ct-columns">
            <Popover open={colsOpen} onOpenChange={(o) => { setColsOpen(o); if (o) setPresetOpen(false) }}>
              <SimpleTooltip content={isMobile ? colsLabel : null}>
                <PopoverTrigger asChild>
                  <Button type="button" variant="secondary" size="sm" aria-label={colsLabel} className="pointer-coarse:h-10">{toolContent(Columns3, colsLabel)}</Button>
                </PopoverTrigger>
              </SimpleTooltip>
              <PopoverContent align="start" collisionPadding={8} aria-label={t('tbl.columns')}
                className="z-(--z-menu) flex max-h-[min(70vh,32rem)] w-60 max-w-[calc(100vw-2rem)] flex-col gap-1 overflow-y-auto p-2">
                <span className="px-1 text-xs text-muted-foreground">{t('tbl.columnsDragHint')}</span>
                {cols.map((k, i) => {
                  const c = COLUMN_BY_KEY[k]
                  const id = `${idBase}-col-${k}`
                  return (
                    <div key={k} data-col-item={k} draggable={k !== 'domain'}
                      className={cn('flex items-center gap-1.5 rounded-sm px-1 py-1 text-[.86em]', c.fixed && 'opacity-60')}
                      onDragStart={() => { dragFrom.current = i }}
                      onDragOver={(e) => e.preventDefault()}
                      onDrop={() => { if (dragFrom.current != null && dragFrom.current !== i) onCols(moveCol(cols, dragFrom.current, i)); dragFrom.current = null }}>
                      <GripVertical aria-hidden="true" className="size-3 shrink-0 cursor-grab text-muted-foreground" />
                      <Checkbox id={id} checked disabled={c.fixed} onCheckedChange={() => onCols(cols.filter((x) => x !== k))} />
                      <Label htmlFor={id} className="cursor-pointer font-normal">{t(c.labelKey)}</Label>
                    </div>
                  )
                })}
                {TABLE_COLUMNS.filter((c) => !cols.includes(c.key)).map((c) => {
                  const id = `${idBase}-col-${c.key}`
                  return (
                    <div key={c.key} data-col-item={c.key} className="flex items-center gap-1.5 rounded-sm px-1 py-1 text-[.86em]">
                      <span aria-hidden="true" className="inline-block w-3 shrink-0" />
                      <Checkbox id={id} checked={false} onCheckedChange={() => onCols([...cols, c.key])} />
                      <Label htmlFor={id} className="cursor-pointer font-normal">{t(c.labelKey)}</Label>
                    </div>
                  )
                })}
                <Button type="button" variant="secondary" size="sm" className="mt-1" onClick={() => onCols(defaultCols())}>{t('tbl.columnsReset')}</Button>
                <span className="px-1 text-xs text-muted-foreground">{t('tbl.viewSaved')}</span>
              </PopoverContent>
            </Popover>
          </div>
          {onColFilters && (
            <SimpleTooltip content={isMobile ? t('inv.colFilters') : null}>
              <Button type="button" variant={colFilters ? 'default' : 'secondary'} size="sm" onClick={() => onColFilters(!colFilters)}
                title={t('inv.colFiltersHint')} aria-pressed={colFilters} aria-label={t('inv.colFilters')} className="pointer-coarse:h-10">
                <ListFilter size={14} aria-hidden="true" /><span className="hidden md:inline">{t('inv.colFilters')}</span>
              </Button>
            </SimpleTooltip>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label={t('tbl.actionsGroup')}>
          {/* Ön ayarlar — shadcn Popover: kayıtlı listeler + yeni ad */}
          <div className="flex" data-tour="ct-presets">
            <Popover open={presetOpen} onOpenChange={(o) => { setPresetOpen(o); if (o) setColsOpen(false) }}>
              <SimpleTooltip content={isMobile ? presetsLabel : null}>
                <PopoverTrigger asChild>
                  <Button type="button" variant="secondary" size="sm" aria-label={presetsLabel} className="pointer-coarse:h-10">{toolContent(Bookmark, presetsLabel)}</Button>
                </PopoverTrigger>
              </SimpleTooltip>
              <PopoverContent align="end" collisionPadding={8} aria-label={t('tbl.presets')}
                className="z-(--z-menu) flex w-72 max-w-[calc(100vw-2rem)] flex-col gap-1.5 p-2">
                {presets.length === 0 && <span className="px-1 text-xs text-muted-foreground">{t('tbl.presetsEmpty')}</span>}
                {presets.map((p) => (
                  <div key={p.name} className="flex items-center gap-1">
                    <Button type="button" variant="ghost" size="sm" data-preset-apply={p.name}
                      className="h-8 min-w-0 flex-1 justify-start truncate px-1.5 font-normal"
                      onClick={() => { onApplyPreset(p); setPresetOpen(false) }}>{p.name}</Button>
                    <Button type="button" variant="ghost" size="icon-sm" className="text-muted-foreground hover:text-destructive"
                      aria-label={t('tbl.presetDelete', p.name)} onClick={() => onDeletePreset(p.name)}>
                      <X aria-hidden="true" className="size-3" />
                    </Button>
                  </div>
                ))}
                <div className="mt-1 flex gap-1.5">
                  <Input className="h-8 min-w-0 flex-1" placeholder={t('tbl.presetNamePh')} aria-label={t('tbl.presetNamePh')}
                    value={presetName} maxLength={40}
                    onChange={(e) => setPresetName(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter' && presetName.trim()) savePreset() }} />
                  <Button type="button" size="sm" disabled={!presetName.trim()} onClick={savePreset}>{t('tbl.presetSave')}</Button>
                </div>
                <span className="px-1 text-xs text-muted-foreground">{t('tbl.presetNote')}</span>
              </PopoverContent>
            </Popover>
          </div>
          <SimpleTooltip content={isMobile ? t('tbl.csvTitle') : null}>
            <Button asChild variant="secondary" size="sm" className="pointer-coarse:h-10">
              <a href={exportUrl} download title={t('tbl.csvTitle')} aria-label={t('tbl.csvLabel')} data-tour="ct-csv">
                <Download size={14} aria-hidden="true" /><span className="hidden md:inline">CSV</span>
              </a>
            </Button>
          </SimpleTooltip>
          <CopyLinkButton iconOnly />
        </div>
      </div>
    </>
  )
}
