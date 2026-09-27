import { ArrowUpDown, Layers3, LayoutGrid, List, Search, Users, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import FacetedFilter from '../../components/ui/FacetedFilter.jsx'
import SegmentedControl from '../../components/ui/SegmentedControl.jsx'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuLabel, DropdownMenuRadioGroup, DropdownMenuRadioItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { SORT_KEYS } from './warningsModel.js'

/**
 * Süzgeç çubuğu (2026-09-27): arama (alan adı · takım · CA · hata; Esc temizler) + Takım / Kritiklik faset süzgeçleri
 * (shadcn faset deseni, çoklu seçim + sayılar) + sıralama menüsü + görünüm anahtarı (Liste | Kartlar). Altında etkin
 * süzgeç çipleri (tek tek kaldırılır), "Filtreleri temizle" ve "X / Y" sayacı.
 *
 * Mobil-önce: arama telefonda tam satır; kontroller sarar, dokunmatikte 40 px. Saf sunum — durum sayfada.
 * Test kancaları: `data-slot="attn-toolbar" | "attn-sort" | "attn-chip" | "attn-count"`.
 */
export default function WarningsToolbar({
  q, onQ, teamOpts, teams, onTeams, tierOpts, tiers, onTiers, sort, onSort, view, onView, chips, onClearAll, shown, total,
}) {
  const t = useT()
  const sortKey = SORT_KEYS.includes(sort) ? sort : 'urgency'
  return (
    <div data-slot="attn-toolbar" className="flex min-w-0 flex-col gap-2 print:hidden">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <InputGroup className="w-full sm:w-72 pointer-coarse:h-10">
          <InputGroupInput type="search" value={q} placeholder={t('attn.searchPh')} aria-label={t('attn.searchPh')}
            className="[&::-webkit-search-cancel-button]:hidden"
            onChange={(e) => onQ(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Escape' && q) { e.preventDefault(); e.stopPropagation(); onQ('') } }} />
          <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
          {q && (
            <InputGroupAddon align="inline-end">
              <InputGroupButton size="icon-xs" onClick={() => onQ('')} aria-label={t('attn.clearSearch')} title={t('attn.clearSearch')}>
                <X aria-hidden="true" />
              </InputGroupButton>
            </InputGroupAddon>
          )}
        </InputGroup>
        {teamOpts.length > 0 && (
          <FacetedFilter title={t('inv.filterTeam')} icon={Users} options={teamOpts} value={teams} onChange={onTeams}
            searchPlaceholder={t('attn.teamSearch')} className="pointer-coarse:h-10" />
        )}
        {tierOpts.length > 0 && (
          <FacetedFilter title={t('inv.filterTier')} icon={Layers3} options={tierOpts} value={tiers} onChange={onTiers}
            searchPlaceholder={t('inv.filterTier')} className="pointer-coarse:h-10" />
        )}
        <DropdownMenu modal={false}>
          <DropdownMenuTrigger asChild>
            <Button type="button" variant="outline" size="sm" className="h-8 pointer-coarse:h-10" data-slot="attn-sort">
              <ArrowUpDown aria-hidden="true" />{t('attn.sortBy', t(`attn.sort.${sortKey}`))}
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="z-(--z-menu) w-56">
            <DropdownMenuLabel>{t('flt.sort')}</DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuRadioGroup value={sortKey} onValueChange={onSort}>
              {SORT_KEYS.map((k) => <DropdownMenuRadioItem key={k} value={k}>{t(`attn.sort.${k}`)}</DropdownMenuRadioItem>)}
            </DropdownMenuRadioGroup>
          </DropdownMenuContent>
        </DropdownMenu>
        <SegmentedControl value={view} onChange={onView} ariaLabel={t('attn.view')}
          className="sm:ml-auto [&>button]:pointer-coarse:h-9"
          options={[{ value: 'list', label: t('attn.viewList'), icon: List }, { value: 'cards', label: t('attn.viewCards'), icon: LayoutGrid }]} />
      </div>

      <div className="flex min-w-0 flex-wrap items-center gap-1.5">
        {chips.length > 0 && (
          <div role="group" aria-label={t('attn.activeFilters')} className="flex min-w-0 flex-wrap items-center gap-1.5">
            {chips.map((c) => (
              <Button key={c.key} type="button" variant="secondary" size="xs" data-slot="attn-chip" data-chip={c.key}
                className="h-7 max-w-full rounded-full pr-1.5 font-normal pointer-coarse:h-9"
                aria-label={t('attn.removeFilter', c.label)} title={t('attn.removeFilter', c.label)} onClick={c.onRemove}>
                <span className="truncate">{c.label}</span><X aria-hidden="true" />
              </Button>
            ))}
            <Button type="button" variant="link" size="xs" className="h-7 px-1 pointer-coarse:h-9" onClick={onClearAll}>{t('app.clearFilters')}</Button>
          </div>
        )}
        <span role="status" data-slot="attn-count" className="text-xs text-muted-foreground sm:ml-auto">{t('attn.shown', shown, total)}</span>
      </div>
    </div>
  )
}
