import { CircleSlash, Layers, Search, Users, X } from 'lucide-react'
import FacetedFilter from '../ui/FacetedFilter.jsx'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'

/**
 * Kapsam süzgeç çubuğu (2026-09-27): arama (ad · hedef · takım · grup; Esc temizler) + Takım / Tür / Neden faset
 * süzgeçleri (çoklu seçim + sayılar — Uyarılar sayfasıyla aynı desen). Altında etkin süzgeç çipleri (tek tek kaldırılır),
 * "Filtreleri temizle" ve "X / Y" sayacı. Mobil-önce: arama telefonda tam satır, kontroller sarar, 40 px.
 * Saf sunum — durum sayfada. Test kancaları: `data-slot="noc-toolbar" | "noc-chip" | "noc-count"`.
 */
export default function NocCoverageToolbar({
  t, q, onQ, teamOpts, teams, onTeams, typeOpts, types, onTypes, reasonOpts, reasons, onReasons, chips, onClearAll, shown, total,
}) {
  return (
    <div data-slot="noc-toolbar" className="flex min-w-0 flex-col gap-2">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <InputGroup className="h-10 w-full sm:h-9 sm:w-72 sm:pointer-coarse:h-10">
          <InputGroupInput type="search" value={q} placeholder={t('noc.searchPh')} aria-label={t('noc.searchPh')}
            className="h-full [&::-webkit-search-cancel-button]:hidden"
            onChange={(e) => onQ(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Escape' && q) { e.preventDefault(); e.stopPropagation(); onQ('') } }} />
          <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
          {q && (
            <InputGroupAddon align="inline-end">
              <InputGroupButton size="icon-xs" className="size-8 sm:size-6" onClick={() => onQ('')} aria-label={t('noc.clearSearch')} title={t('noc.clearSearch')}>
                <X aria-hidden="true" />
              </InputGroupButton>
            </InputGroupAddon>
          )}
        </InputGroup>
        {teamOpts.length > 0 && (
          <FacetedFilter title={t('noc.fTeam')} icon={Users} options={teamOpts} value={teams} onChange={onTeams}
            searchPlaceholder={t('noc.fTeamSearch')} className="h-10 sm:h-8 sm:pointer-coarse:h-10" />
        )}
        {typeOpts.length > 0 && (
          <FacetedFilter title={t('noc.fType')} icon={Layers} options={typeOpts} value={types} onChange={onTypes}
            searchPlaceholder={t('noc.fType')} className="h-10 sm:h-8 sm:pointer-coarse:h-10" />
        )}
        {reasonOpts.length > 0 && (
          <FacetedFilter title={t('noc.fReason')} icon={CircleSlash} options={reasonOpts} value={reasons} onChange={onReasons}
            searchPlaceholder={t('noc.fReason')} className="h-10 sm:h-8 sm:pointer-coarse:h-10" />
        )}
      </div>

      <div className="flex min-w-0 flex-wrap items-center gap-1.5">
        {chips.length > 0 && (
          <div role="group" aria-label={t('noc.activeFilters')} className="flex min-w-0 flex-wrap items-center gap-1.5">
            {chips.map((c) => (
              <Button key={c.key} type="button" variant="secondary" size="xs" data-action="noc-chip" data-chip={c.key}
                className="h-10 max-w-full rounded-full pr-2 font-normal sm:h-7 sm:pr-1.5 sm:pointer-coarse:h-10"
                aria-label={t('noc.removeFilter', c.label)} title={t('noc.removeFilter', c.label)} onClick={c.onRemove}>
                <span className="truncate">{c.label}</span><X aria-hidden="true" />
              </Button>
            ))}
            <Button type="button" variant="link" size="xs" className="h-10 px-1 sm:h-7 sm:pointer-coarse:h-10" onClick={onClearAll}>{t('app.clearFilters')}</Button>
          </div>
        )}
        <span role="status" data-slot="noc-count" className="text-xs text-muted-foreground sm:ml-auto">{t('noc.shown', shown, total)}</span>
      </div>
    </div>
  )
}
