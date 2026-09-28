import { ArrowUpDown, CalendarClock, Download, Landmark, Search, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import FacetedFilter from '../ui/FacetedFilter.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuLabel, DropdownMenuRadioGroup, DropdownMenuRadioItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { KPI_KEYS, SORT_KEYS } from './statsModel.js'

/** Radix radyo değeri boş dize olamaz — "Tümü" seçeneğinin iç değeri. */
const ALL = '__all__'

/**
 * Sertifika tablosunun süzgeç çubuğu (2026-09-28): gecikmeli arama (alan adı · sağlayıcı · takım · hata; Esc temizler) +
 * Kalan süre (KPI kutucuklarıyla AYNI durum, radyo menüsü) + Sağlayıcı (faset: çoklu seçim + sayılar) + sıralama menüsü
 * + CSV. Altında etkin süzgeç çipleri (tek tek kaldırılır), "Temizle" ve "X / Y" sayacı.
 *
 * Mobil-önce: arama telefonda tam satır, kontroller sarar, dokunmatikte 40 px. Saf sunum — durum StatsView'da.
 * Test kancaları: `data-slot="stats-toolbar" | "stats-window" | "stats-sort" | "stats-chip" | "stats-count" | "stats-export"`.
 */
export default function StatsToolbar({
  q, onQ, kpi, onKpi, issuerOpts, issuers, onIssuers, sort, onSort, chips, onClearAll, shown, total, onExport,
}) {
  const t = useT()
  const sortKey = SORT_KEYS.includes(sort) ? sort : SORT_KEYS[0]
  const sortLabel = (k) => t(`stv.sort.${k.replace('|', '_')}`)
  return (
    <div data-slot="stats-toolbar" className="flex min-w-0 flex-col gap-2 print:hidden">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <InputGroup className="w-full max-sm:h-10 sm:w-72 pointer-coarse:h-10">
          <InputGroupInput type="search" value={q} placeholder={t('stv.searchPh')} aria-label={t('stv.searchPh')}
            className="h-full [&::-webkit-search-cancel-button]:hidden"
            onChange={(e) => onQ(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Escape' && q) { e.preventDefault(); e.stopPropagation(); onQ('') } }} />
          <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
          {q && (
            <InputGroupAddon align="inline-end">
              <InputGroupButton size="icon-xs" onClick={() => onQ('')} aria-label={t('stv.clearSearch')} title={t('stv.clearSearch')}>
                <X aria-hidden="true" />
              </InputGroupButton>
            </InputGroupAddon>
          )}
        </InputGroup>
        <DropdownMenu modal={false}>
          <DropdownMenuTrigger asChild>
            <Button type="button" variant="outline" size="sm" data-slot="stats-window" data-active={kpi ? 'true' : undefined}
              className="h-8 border-dashed max-sm:h-10 pointer-coarse:h-10">
              <CalendarClock aria-hidden="true" />{t('stv.window')}
              {kpi && <Badge variant="secondary" className="rounded-sm px-1.5 font-normal">{t(`stv.kpi.${kpi}`)}</Badge>}
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="z-(--z-menu) w-56">
            <DropdownMenuLabel>{t('stv.window')}</DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuRadioGroup value={kpi || ALL} onValueChange={(v) => onKpi(v === ALL ? null : v)}>
              <DropdownMenuRadioItem value={ALL}>{t('stv.windowAll')}</DropdownMenuRadioItem>
              {KPI_KEYS.map((k) => <DropdownMenuRadioItem key={k} value={k}>{t(`stv.kpi.${k}`)}</DropdownMenuRadioItem>)}
            </DropdownMenuRadioGroup>
          </DropdownMenuContent>
        </DropdownMenu>
        {issuerOpts.length > 0 && (
          <FacetedFilter title={t('stv.issuer')} icon={Landmark} options={issuerOpts} value={issuers} onChange={onIssuers}
            searchPlaceholder={t('stv.issuerSearch')} className="max-sm:h-10 pointer-coarse:h-10" />
        )}
        <DropdownMenu modal={false}>
          <DropdownMenuTrigger asChild>
            <Button type="button" variant="outline" size="sm" className="h-8 max-sm:h-10 pointer-coarse:h-10" data-slot="stats-sort">
              <ArrowUpDown aria-hidden="true" />{t('stv.sortBy', sortLabel(sortKey))}
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="z-(--z-menu) w-64">
            <DropdownMenuLabel>{t('flt.sort')}</DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuRadioGroup value={sortKey} onValueChange={onSort}>
              {SORT_KEYS.map((k) => <DropdownMenuRadioItem key={k} value={k}>{sortLabel(k)}</DropdownMenuRadioItem>)}
            </DropdownMenuRadioGroup>
          </DropdownMenuContent>
        </DropdownMenu>
        <Button type="button" variant="outline" size="sm" className="h-8 max-sm:h-10 sm:ml-auto pointer-coarse:h-10" data-slot="stats-export"
          onClick={onExport} disabled={shown === 0} title={t('stv.export')}>
          <Download aria-hidden="true" />{t('stv.export')}
        </Button>
      </div>

      <div className="flex min-w-0 flex-wrap items-center gap-1.5">
        {chips.length > 0 && (
          <div role="group" aria-label={t('stv.activeFilters')} className="flex min-w-0 flex-wrap items-center gap-1.5">
            {chips.map((c) => (
              <Button key={c.key} type="button" variant="secondary" size="xs" data-slot="stats-chip" data-chip={c.key}
                className="h-7 max-w-full rounded-full pr-1.5 font-normal max-sm:h-10 pointer-coarse:h-10"
                aria-label={t('stv.removeFilter', c.label)} title={t('stv.removeFilter', c.label)} onClick={c.onRemove}>
                <span className="truncate">{c.label}</span><X aria-hidden="true" />
              </Button>
            ))}
            <Button type="button" variant="link" size="xs" data-slot="stats-clear" className="h-7 px-1 max-sm:h-10 pointer-coarse:h-10" onClick={onClearAll}>{t('stv.clearAll')}</Button>
          </div>
        )}
        <span role="status" data-slot="stats-count" className="text-xs text-muted-foreground tabular-nums sm:ml-auto">{t('stv.shown', shown, total)}</span>
      </div>
    </div>
  )
}
