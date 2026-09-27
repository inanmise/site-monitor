import { useEffect, useId, useMemo, useState } from 'react'
import { Search, ListFilter, SlidersHorizontal, Columns3, Rows3, Bookmark, Link2, Users, Table2, X, Layers, ShieldCheck, Server, FolderOpen, Tag } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import TeamScopeSwitch from '../ui/TeamScopeSwitch.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import FacetedFilter from '../ui/FacetedFilter.jsx'
import { INVENTORY_FLAGS } from '../../utils/inventoryFlags.js'
import { INVENTORY_COLUMNS, EMPTY_FILTERS, hasActiveFilter, defaultCols, activeFilterChips, removeFilterChip } from './inventoryModel.js'
import InventoryFilterSheet from './InventoryFilterSheet.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Input } from '@/components/shadcn/input'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Label } from '@/components/shadcn/label'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { cn } from '@/lib/utils'

/** Çip etiketleri: süzgeç anahtarı → görünür ad (i18n anahtarı). */
const CHIP_LABEL = {
  q: 'inv.search', team: 'inv.filterTeam', ugTeam: 'inv.filterUgTeam', tier: 'inv.filterTier', group: 'inv.filterGroup', notifGroup: 'inv.filterNotifGroup',
  cert: 'inv.filterCert', contacts: 'inv.filterContacts', domainExp: 'inv.filterDomainExpiry', hygiene: 'inv.hyTitle', proxy: 'inv.filterProxy', flags: 'inv.colFlags',
  domain: 'inv.colDomain', port: 'inv.colPort', days: 'inv.colDays', checked: 'inv.colLastCheck', flag: 'inv.colFlags', interval: 'inv.colInterval',
  tag: 'inv.facetTag', updated: 'inv.colUpdated', active: 'inv.colActive', platform: 'inv.facetPlatform',
}
const CERT_LABEL = { problem: 'inv.certProblem', error: 'inv.certError', critical: 'inv.certCritical', high: 'inv.certHigh', warning: 'inv.certWarning', valid: 'inv.certValid', never: 'inv.certNever' }
const CONTACT_LABEL = { none: 'inv.contactsNone', partial: 'inv.contactsPartial', full: 'inv.contactsFull' }
const AGE_LABEL = { 24: 'inv.colFilterLast24h', 168: 'inv.colFilterLast7d', 720: 'inv.colFilterLast30d', never: 'inv.certNever' }

/**
 * Tek seçimli faset: shadcn "data table faceted filter" deseni (ui/FacetedFilter — Popover + Command + onay kutulu satırlar
 * + sayaç). Süzgeç modeli tek değerli olduğundan yeni seçim öncekinin yerine geçer; aynı satıra basmak kaldırır.
 */
function Facet({ title, icon, options, value, onChange }) {
  return (
    <FacetedFilter title={title} icon={icon} options={options} value={value ? [value] : []}
      onChange={(next) => onChange(next.filter((v) => v !== value)[0] ?? '')} />
  )
}

/**
 * Envanter araç çubuğu (2026-09-27 yeniden tasarım): arama · fasetler (md+: takım, kritiklik, durum, platform, grup,
 * etiket — sayaçlı) · "Süzgeçler (n)" yan paneli (telefonda tek süzgeç yeri, geniş ekranda diğer süzgeçler) · kapsam
 * anahtarı · Tablo/Takıma göre · kolon süzgeç satırı · yoğunluk · sütunlar · görünümler + bağlantı. Altında sayaç +
 * etkin süzgeç ÇİPLERİ (tek tek kaldır, "Tümünü temizle"). Durum sahibi InventoryManager; burası yalnız kontrol çizer.
 *
 * Çizim shadcn: InputGroup, ui/FacetedFilter, Sheet (InventoryFilterSheet), ui/SegmentedControl, Popover + Checkbox
 * (sütunlar), Popover + Input (görünümler), Badge (çipler). Mobil-önce: satır sarar, arama telefonda tam genişlik;
 * fasetler/yoğunluk/sütunlar md altında gizli (kart listesinde sütun yok). Test kancaları: sayaç `data-slot="inv-count"`,
 * çip `data-slot="inv-chip"` + `data-key`.
 */
export default function InventoryToolbar({
  filters, onFilters, teams = [], groupNames = [], notifGroups = [], tagNames = [], platformCodes = [], platformNames = {}, facets = null,
  shown = 0, total = 0,
  scope = 'mine', onScope = null, visibleToAll = false,   // "Takımlarım | Tüm takımlar" (org geneli görünürlük, 2026-09-26)
  colFilters = false, onColFilters = null,   // kolon süzgeç satırı anahtarı (2026-09-22)
  tableMode = true,   // false → kart listesi (telefon / dar kap): sütun, yoğunluk ve kolon süzgeci düğmeleri çizilmez
  cols, onCols, sort, onSort, density, onDensity, view, onView, savedViews = [], onSaveView, onApplyView, onDeleteView, onCopyLink,
}) {
  const t = useT()
  const [sheetOpen, setSheetOpen] = useState(false)
  const [colsOpen, setColsOpen] = useState(false)
  const [viewsOpen, setViewsOpen] = useState(false)
  const [viewName, setViewName] = useState('')
  const [qDraft, setQDraft] = useState(filters.q || '')
  const idBase = useId()

  // Arama: 250 ms sessizlikten sonra uygula (her tuşta 1000 satırı süzme)
  useEffect(() => { const id = setTimeout(() => { if (qDraft !== (filters.q || '')) onFilters({ ...filters, q: qDraft }) }, 250); return () => clearTimeout(id) }, [qDraft]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { setQDraft(filters.q || '') }, [filters.q])

  const set = (patch) => onFilters({ ...filters, ...patch })
  const active = hasActiveFilter(filters)
  const chips = useMemo(() => activeFilterChips(filters), [filters])
  const teamName = (id) => teams.find((tm) => String(tm.id) === String(id))?.name ?? id
  const count = (kind, key) => facets?.[kind]?.[key]
  const saveView = () => { onSaveView(viewName.trim()); setViewName('') }

  // Faset seçenekleri (sayaçlı) — durum süzgecinden geçmiş satırlardan
  const teamOpts = teams.map((tm) => ({ value: String(tm.id), label: tm.name, count: count('team', String(tm.id)) ?? 0 }))
  const tierOpts = [...[1, 2, 3, 4].map((n) => ({ value: String(n), label: `T${n} — ${t(`inv.tier${n}`)}`, count: count('tier', String(n)) ?? 0 })), { value: 'none', label: t('inv.tierNone'), count: count('tier', 'none') ?? 0 }]
  const certOpts = Object.entries(CERT_LABEL).map(([v, k]) => ({ value: v, label: t(k), count: count('cert', v) ?? 0 }))
  const platformOpts = [...platformCodes.map((c) => ({ value: c, label: platformNames[c] || c, count: count('platform', c) ?? 0 })), { value: 'none', label: t('inv.filterNone'), count: count('platform', 'none') ?? 0 }]
  const groupOpts = [...groupNames.map((g) => ({ value: g, label: g, count: count('group', g) ?? 0 })), { value: 'none', label: t('inv.filterNone'), count: count('group', 'none') ?? 0 }]
  const tagOpts = tagNames.map((x) => ({ value: x, label: x, count: count('tag', x.toLowerCase()) ?? 0 }))

  /** Çip metni "Ad: değer" — değer i18n'li ada çevrilir. */
  const chipText = (chip) => {
    const { key, value } = chip
    const name = t(CHIP_LABEL[key] || key).replace(/:$/, '')
    let val = value
    if (key === 'team' || key === 'ugTeam') val = teamName(value)
    else if (key === 'tier') val = value === 'none' ? t('inv.tierNone') : `T${value}`
    else if (key === 'cert') val = t(CERT_LABEL[value] || value)
    else if (key === 'contacts') val = t(CONTACT_LABEL[value] || value)
    else if (key === 'flags' || key === 'flag') val = t(INVENTORY_FLAGS.find((f) => f.key === value)?.labelKey || value)
    else if (key === 'hygiene') val = t(`inv.hy.${value}`)
    else if (key === 'proxy') val = t(value === 'on' ? 'inv.filterProxyOn' : 'inv.filterProxyOff')
    else if (key === 'notifGroup') val = value === 'none' ? t('inv.filterTeamDefault') : (notifGroups.find((g) => String(g.id) === value)?.name ?? value)
    else if (key === 'group') val = value === 'none' ? t('inv.filterNone') : value
    else if (key === 'platform') val = value === 'none' ? t('inv.filterNone') : (platformNames[value] || value)
    else if (key === 'days') val = value === 'expired' ? t('inv.colFilterExpired') : value === 'unknown' ? t('inv.colFilterUnknown') : t('inv.colFilterWithinDays', value)
    else if (key === 'checked' || key === 'updated') val = t(AGE_LABEL[value] || value)
    else if (key === 'interval') val = value === 'global' ? t('inv.colFilterIntervalGlobal') : t('inv.colFilterIntervalH', value)
    else if (key === 'active') val = t(value === 'yes' ? 'inv.colFilterActiveYes' : 'inv.colFilterActiveNo')
    else if (key === 'domainExp') val = value === 'unknown' ? t('inv.domainExpUnknown') : t('inv.domainExpIn', value)
    return `${name}: ${val}`
  }

  return (
    <div data-slot="inv-toolbar" className="mb-3 flex min-w-0 flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <InputGroup className="w-full sm:w-auto sm:max-w-[360px] sm:flex-[1_1_220px]">
          <InputGroupInput type="search" value={qDraft} onChange={(e) => setQDraft(e.target.value)} placeholder={t('inv.searchPh')} aria-label={t('inv.search')} />
          <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
          {qDraft && (
            <InputGroupAddon align="inline-end">
              <InputGroupButton size="icon-xs" onClick={() => { setQDraft(''); set({ q: '' }) }} aria-label={t('inv.filterClear')}>
                <X aria-hidden="true" />
              </InputGroupButton>
            </InputGroupAddon>
          )}
        </InputGroup>

        {/* Fasetler — yalnız md+ (telefonda hepsi yan panelde) */}
        <div data-slot="inv-facets" className="hidden flex-wrap items-center gap-2 md:flex">
          {teamOpts.length > 1 && <Facet title={t('inv.filterTeam')} icon={Users} options={teamOpts} value={filters.team} onChange={(v) => set({ team: v })} />}
          <Facet title={t('inv.filterTier')} icon={Layers} options={tierOpts} value={filters.tier} onChange={(v) => set({ tier: v })} />
          <Facet title={t('inv.facetStatus')} icon={ShieldCheck} options={certOpts} value={filters.cert} onChange={(v) => set({ cert: v })} />
          {platformCodes.length > 0 && <Facet title={t('inv.facetPlatform')} icon={Server} options={platformOpts} value={filters.platform} onChange={(v) => set({ platform: v })} />}
          {groupNames.length > 0 && <Facet title={t('inv.filterGroup')} icon={FolderOpen} options={groupOpts} value={filters.group} onChange={(v) => set({ group: v })} />}
          {tagNames.length > 0 && <Facet title={t('inv.facetTag')} icon={Tag} options={tagOpts} value={filters.tag} onChange={(v) => set({ tag: v })} />}
        </div>

        {/* Süzgeç paneli tetiği: telefonda "Süzgeçler (n)", geniş ekranda "Diğer süzgeçler" */}
        <Button type="button" variant={active ? 'default' : 'outline'} size="sm" className="h-8" onClick={() => setSheetOpen(true)}
          aria-haspopup="dialog" aria-expanded={sheetOpen} data-active={active ? 'true' : undefined}>
          <SlidersHorizontal aria-hidden="true" />
          <span className="md:hidden">{chips.length ? t('inv.filtersCount', chips.length) : t('inv.filters')}</span>
          <span className="hidden md:inline">{t('inv.moreFilters')}</span>
          {chips.length > 0 && <Badge variant="secondary" className="hidden rounded-sm px-1.5 font-mono tabular-nums md:inline-flex" aria-hidden="true">{chips.length}</Badge>}
        </Button>

        <div className="hidden flex-1 sm:block" />
        <TeamScopeSwitch value={scope} onChange={onScope} visible={visibleToAll && !!onScope} />
        <SegmentedControl value={view} onChange={onView} ariaLabel={t('inv.viewLabel')}
          options={[{ value: 'table', label: t('inv.viewTable'), icon: Table2 }, { value: 'team', label: t('inv.viewByTeam'), icon: Users }]} />
        {onColFilters && view === 'table' && tableMode && (
          <Button type="button" variant={colFilters ? 'default' : 'outline'} size="sm" className="hidden h-8 md:inline-flex" onClick={() => onColFilters(!colFilters)}
            title={t('inv.colFiltersHint')} aria-pressed={colFilters}>
            <ListFilter aria-hidden="true" /> {t('inv.colFilters')}
          </Button>
        )}
        {view === 'table' && tableMode && (
          <Button type="button" variant="outline" size="sm" className="hidden h-8 md:inline-flex" onClick={() => onDensity(density === 'compact' ? 'comfortable' : 'compact')}
            title={t('inv.density')} aria-pressed={density === 'compact'}>
            <Rows3 aria-hidden="true" /> {density === 'compact' ? t('inv.densityCompact') : t('inv.densityComfortable')}
          </Button>
        )}

        {/* Sütun seçici — shadcn Popover + Checkbox (yalnız tablo görünümü, md+) */}
        {view === 'table' && tableMode && (
          <Popover open={colsOpen} onOpenChange={(o) => { setColsOpen(o); if (o) setViewsOpen(false) }}>
            <PopoverTrigger asChild>
              <Button type="button" variant="outline" size="sm" className="hidden h-8 md:inline-flex">
                <Columns3 aria-hidden="true" /> {t('tbl.columns')} ({cols.length}/{INVENTORY_COLUMNS.length})
              </Button>
            </PopoverTrigger>
            <PopoverContent align="end" collisionPadding={8} aria-label={t('tbl.columns')}
              className="z-(--z-menu) flex max-h-[min(70vh,32rem)] w-60 max-w-[calc(100vw-2rem)] flex-col gap-1 overflow-y-auto p-2">
              {INVENTORY_COLUMNS.map((c) => {
                const id = `${idBase}-col-${c.key}`
                return (
                  <div key={c.key} data-col-item={c.key} className={cn('flex items-center gap-2 rounded-sm px-1 py-1 text-[.86em]', c.fixed && 'opacity-60')}>
                    <Checkbox id={id} checked={cols.includes(c.key)} disabled={c.fixed}
                      onCheckedChange={() => onCols(cols.includes(c.key) ? cols.filter((x) => x !== c.key) : [...cols, c.key])} />
                    <Label htmlFor={id} className="cursor-pointer font-normal">{t(c.labelKey)}</Label>
                  </div>
                )
              })}
              <Button type="button" variant="secondary" size="sm" className="mt-1" onClick={() => onCols(defaultCols())}>{t('tbl.columnsReset')}</Button>
              <span className="px-1 text-xs text-muted-foreground">{t('tbl.viewSaved')}</span>
            </PopoverContent>
          </Popover>
        )}

        {/* Kayıtlı görünümler + bağlantı — shadcn Popover; kayıttan sonra açık kalır (hemen uygulanabilsin) */}
        <Popover open={viewsOpen} onOpenChange={(o) => { setViewsOpen(o); if (o) setColsOpen(false) }}>
          <PopoverTrigger asChild>
            <Button type="button" variant="outline" size="sm" className="h-8">
              <Bookmark aria-hidden="true" /> {t('inv.views')}{savedViews.length ? ` (${savedViews.length})` : ''}
            </Button>
          </PopoverTrigger>
          <PopoverContent align="end" collisionPadding={8} aria-label={t('inv.views')}
            className="z-(--z-menu) flex w-72 max-w-[calc(100vw-2rem)] flex-col gap-1.5 p-2">
            {savedViews.length === 0 && <span className="px-1 text-xs text-muted-foreground">{t('inv.viewsEmpty')}</span>}
            {savedViews.map((v) => (
              <div key={v.name} className="flex items-center gap-1">
                <Button type="button" variant="ghost" size="sm" className="h-8 min-w-0 flex-1 justify-start truncate px-1.5 font-normal"
                  onClick={() => { onApplyView(v); setViewsOpen(false) }}>{v.name}</Button>
                <Button type="button" variant="ghost" size="icon-sm" className="text-muted-foreground hover:text-destructive"
                  onClick={() => onDeleteView(v.name)} aria-label={t('inv.viewDeleteFor', v.name)}>
                  <X aria-hidden="true" className="size-3" />
                </Button>
              </div>
            ))}
            <div className="mt-1 flex gap-1.5">
              <Input className="h-8 min-w-0 flex-1" value={viewName} onChange={(e) => setViewName(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter' && viewName.trim()) saveView() }}
                placeholder={t('inv.viewName')} aria-label={t('inv.viewName')} maxLength={40} />
              <Button type="button" size="sm" disabled={!viewName.trim()} onClick={saveView}>{t('inv.viewSave')}</Button>
            </div>
            <Button type="button" variant="secondary" size="sm" onClick={() => { onCopyLink(); setViewsOpen(false) }}><Link2 aria-hidden="true" /> {t('inv.copyLink')}</Button>
          </PopoverContent>
        </Popover>
      </div>

      {/* Sayaç + etkin süzgeç çipleri */}
      <div className="flex flex-wrap items-center gap-1.5 text-[.84em] text-muted-foreground">
        <span data-slot="inv-count" className="whitespace-nowrap">{t('inv.shownOf', shown, total)}</span>
        {chips.length > 0 && (
          <div role="group" aria-label={t('inv.activeFilters')} className="flex flex-wrap items-center gap-1.5">
            {chips.map((chip) => {
              const label = chipText(chip)
              return (
                <Badge key={`${chip.key}:${chip.value}`} variant="secondary" data-slot="inv-chip" data-key={chip.key} className="h-7 gap-0.5 pr-0.5 pl-2.5 font-normal text-foreground">
                  <span className="max-w-[16rem] truncate">{label}</span>
                  <Button type="button" variant="ghost" size="icon-xs" className="size-6 rounded-full text-muted-foreground hover:text-foreground"
                    onClick={() => onFilters(removeFilterChip(filters, chip))} aria-label={t('inv.chipRemove', label)}>
                    <X aria-hidden="true" className="size-3" />
                  </Button>
                </Badge>
              )
            })}
            <Button type="button" variant="link" size="sm" className="h-7 px-1.5" onClick={() => onFilters({ ...EMPTY_FILTERS })}>{t('inv.clearAll')}</Button>
          </div>
        )}
      </div>

      <InventoryFilterSheet open={sheetOpen} onOpenChange={setSheetOpen} filters={filters} onFilters={onFilters} teams={teams}
        groupNames={groupNames} notifGroups={notifGroups} platformCodes={platformCodes} platformNames={platformNames} tagNames={tagNames}
        sort={sort} onSort={onSort} activeCount={chips.length} />
    </div>
  )
}
