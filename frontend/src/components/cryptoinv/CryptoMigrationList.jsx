import { ExternalLink, ListOrdered, Search, X } from 'lucide-react'
import { formatDateOnly } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import { usePagination } from '../../hooks/usePagination.js'
import { navigateTo } from '../../utils/navigate.js'
import { DEEP_OPEN, DEEP_OPEN_PARAM } from '../../utils/monitorDeepLink.js'
import TeamBadge from '../ui/TeamBadge.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { CategoryBadge, PqcBadge, PriorityChip, SourceBadge } from './CryptoBadges.jsx'
import {
  BANDS, CATEGORIES, DEFAULT_FILTERS, DUE_DAYS, PQC_STATES, SORTS, SOURCES, TIERS, bucketLabel, daysText, hashLabel, keyText,
} from './cryptoInventoryModel.js'

/** Liste kabı bu genişliğin altında kart görünümüne geçer (ölçüm yoksa — jsdom — tablo). */
const TABLE_MIN_WIDTH = 860

function daysInk(days) {
  if (days == null) return 'text-muted-foreground'
  if (days < 0) return 'font-semibold text-destructive'
  if (days <= 30) return 'font-semibold text-amber-700 dark:text-amber-300'
  return ''
}

function openCertificate(domain) {
  navigateTo('dashboard', { domain, [DEEP_OPEN_PARAM]: DEEP_OPEN.CERT })
}

function Remnants({ row }) {
  const t = useT()
  const list = row.remnants || []
  if (!list.length) return null
  return (
    <span className="mt-1 flex flex-wrap gap-1">
      {list.map((k) => <ToneBadge key={k} tone="danger" data-remnant={k} className="font-normal">{t(`cinv.rem.${k}`)}</ToneBadge>)}
    </span>
  )
}

function Expiry({ row, inline = false }) {
  const t = useT()
  return (
    <span className={cn('flex', inline ? 'flex-wrap items-baseline gap-x-1.5' : 'flex-col')} data-slot="cinv-expiry">
      <span>{row.not_after ? formatDateOnly(row.not_after) : '—'}</span>
      <span className={cn('text-xs', daysInk(row.days_remaining))}>{daysText(row.days_remaining, t)}</span>
    </span>
  )
}

function Target({ row }) {
  const t = useT()
  return (
    <span className="flex min-w-0 flex-col gap-0.5">
      <span className="text-sm">{t(`cinv.targetShort.${row.category}`)}</span>
      {row.migrate_by && (
        <span className="text-xs text-muted-foreground">{t(`cinv.actionBy.${row.action || 'none'}`, formatDateOnly(row.migrate_by))}</span>
      )}
      {row.exception && (
        <ToneBadge tone={row.exception.expired ? 'danger' : 'info'} className="w-fit font-normal">
          {row.exception.expired ? t('cinv.exceptionExpired', formatDateOnly(row.exception.until)) : t('cinv.exceptionUntil', formatDateOnly(row.exception.until))}
        </ToneBadge>
      )}
    </span>
  )
}

function OpenButton({ row }) {
  const t = useT()
  if (!row.domain) return null
  return (
    <Button type="button" variant="ghost" size="icon" onClick={() => openCertificate(row.domain)}
      aria-label={t('cinv.openCert', row.domain)} title={t('cinv.openCert', row.domain)} className="size-10 shrink-0 sm:size-8">
      <ExternalLink aria-hidden="true" />
    </Button>
  )
}

function TeamCell({ row }) {
  const t = useT()
  return (
    <span className="flex min-w-0 flex-col items-start gap-0.5">
      {row.team_name ? <TeamBadge teamId={row.team_id} teamName={row.team_name} /> : <span className="text-muted-foreground">{t('cinv.noTeam')}</span>}
      {row.ug_team_name && <span className="text-xs text-muted-foreground">{t('cinv.ugTeam', row.ug_team_name)}</span>}
      {row.owner && <span className="max-w-full truncate text-xs text-muted-foreground" title={row.owner}>{row.owner}</span>}
    </span>
  )
}

function Endpoint({ row }) {
  const t = useT()
  return (
    <span className="flex min-w-0 flex-col gap-1">
      <span className="font-semibold break-all">{row.domain}</span>
      <span className="flex flex-wrap items-center gap-1">
        <SourceBadge row={row} />
        <Badge variant="outline" className="font-normal" data-tier={row.tier ?? 'none'}>{row.tier ? `T${row.tier}` : t('cinv.tierNone')}</Badge>
        {row.group_name && <span className="text-xs text-muted-foreground">{row.group_name}</span>}
      </span>
    </span>
  )
}

function Crypto({ row }) {
  const t = useT()
  return (
    <span className="flex min-w-0 flex-col gap-0.5">
      <span className="font-mono text-[0.92em]">{keyText(row)}</span>
      <span className="text-xs text-muted-foreground">{bucketLabel(row.key_bucket, t)}</span>
      <span className="font-mono text-xs break-words">{row.signature_algorithm || hashLabel(row.sig_hash, t)}</span>
      <Remnants row={row} />
    </span>
  )
}

/** Süzgeç seçimi — etiket görünür (telefonda da), seçim NativeSelect (yerel, dokunmatik dostu). */
function FilterSelect({ id, label, value, onChange, children, slot, className }) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-1 [&>[data-slot=native-select-wrapper]]:w-full', className)}>
      <Label htmlFor={id} className="text-xs font-medium text-muted-foreground">{label}</Label>
      <NativeSelect id={id} value={value} onChange={(e) => onChange(e.target.value)} data-slot={slot} className="h-10 w-full">
        {children}
      </NativeSelect>
    </div>
  )
}

/**
 * Takım bazlı geçiş listesi (2026-10-10): arama (`data-page-search` — "/" kısayolu) + takım / kategori / PQC / katman /
 * kaynak / bant süzgeçleri + sıralama; grafiklerden gelen süzgeçler (anahtar kovası, imza özeti, kalıntı, yenileme
 * penceresi) kaldırılabilir çip olarak. Geniş kapta tablo, dar kapta kart; sayfalama URL'de (`ci_page`, `ci_ps`).
 * Test kancaları: `data-slot="cinv-list"`, satır `cinv-row` (`data-domain`, `data-category`), kart `cinv-card`.
 */
export default function CryptoMigrationList({ rows, total, filters, onChange, onClear, teamOpts, filtered }) {
  const t = useT()
  const [measureRef, width] = useElementWidth()
  const tableMode = width === 0 || width >= TABLE_MIN_WIDTH
  const pager = usePagination(rows, {
    listKey: 'crypto-inventory', preset: 'page', resetDeps: [JSON.stringify({ ...filters, sort: undefined })],
    url: { pageKey: 'ci_page', sizeKey: 'ci_ps' },
  })
  const set = (k) => (v) => onChange({ [k]: v })
  const chips = [
    filters.bucket && ['bucket', `${t('cinv.f.key')}: ${bucketLabel(filters.bucket, t)}`],
    filters.hash && ['hash', `${t('cinv.f.hash')}: ${hashLabel(filters.hash, t)}`],
    filters.remnant && ['remnant', t('cinv.f.remnantOn')],
    filters.due && ['due', t('cinv.f.dueOn', DUE_DAYS)],
  ].filter(Boolean)

  return (
    <Card data-slot="cinv-list" className="min-w-0 gap-0 overflow-hidden p-0 shadow-xs">
      <CardHeader className="gap-1 border-b px-4 pt-4 pb-3 sm:px-5">
        <CardTitle role="heading" aria-level={3} className="flex flex-wrap items-center gap-2 text-base">
          <ListOrdered aria-hidden="true" className="size-4 shrink-0 text-primary" />{t('cinv.listTitle')}
          <span className="text-xs font-normal text-muted-foreground tabular-nums" data-slot="cinv-count">{t('cinv.listCount', rows.length, total)}</span>
        </CardTitle>
        <CardDescription>{t('cinv.listDesc')}</CardDescription>
        <div className="@container mt-2 flex min-w-0 flex-col gap-2.5">
          <div className="relative min-w-0">
            <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input type="search" value={filters.q} onChange={(e) => onChange({ q: e.target.value })} data-page-search
              aria-label={t('cinv.f.search')} placeholder={t('cinv.f.searchPh')} className="h-10 w-full pl-8" />
          </div>
          <div className="grid min-w-0 grid-cols-2 gap-2 @lg:grid-cols-3 @4xl:grid-cols-4 @6xl:grid-cols-7">
            <FilterSelect id="cinv-f-team" label={t('cinv.f.team')} value={filters.team} onChange={set('team')} slot="cinv-f-team">
              <NativeSelectOption value="">{t('cinv.f.all')}</NativeSelectOption>
              {teamOpts.map((o) => <NativeSelectOption key={o.value} value={o.value}>{o.value === 'none' ? t('cinv.noTeam') : o.label}</NativeSelectOption>)}
            </FilterSelect>
            <FilterSelect id="cinv-f-cat" label={t('cinv.f.category')} value={filters.category} onChange={set('category')} slot="cinv-f-category">
              <NativeSelectOption value="">{t('cinv.f.all')}</NativeSelectOption>
              {CATEGORIES.map((c) => <NativeSelectOption key={c} value={c}>{t(`cinv.cat.${c}`)}</NativeSelectOption>)}
            </FilterSelect>
            <FilterSelect id="cinv-f-pqc" label={t('cinv.f.pqc')} value={filters.pqc} onChange={set('pqc')} slot="cinv-f-pqc">
              <NativeSelectOption value="">{t('cinv.f.all')}</NativeSelectOption>
              {PQC_STATES.map((c) => <NativeSelectOption key={c} value={c}>{t(`cinv.pqc.${c}`)}</NativeSelectOption>)}
            </FilterSelect>
            <FilterSelect id="cinv-f-band" label={t('cinv.f.band')} value={filters.band} onChange={set('band')} slot="cinv-f-band">
              <NativeSelectOption value="">{t('cinv.f.all')}</NativeSelectOption>
              {BANDS.map((b) => <NativeSelectOption key={b} value={b}>{t(`cinv.band.${b}`)}</NativeSelectOption>)}
            </FilterSelect>
            <FilterSelect id="cinv-f-tier" label={t('cinv.f.tier')} value={filters.tier} onChange={set('tier')} slot="cinv-f-tier">
              <NativeSelectOption value="">{t('cinv.f.all')}</NativeSelectOption>
              {TIERS.map((x) => <NativeSelectOption key={x} value={x}>{x === 'none' ? t('cinv.tierNone') : t(`cinv.tier.${x}`)}</NativeSelectOption>)}
            </FilterSelect>
            <FilterSelect id="cinv-f-src" label={t('cinv.f.source')} value={filters.source} onChange={set('source')} slot="cinv-f-source">
              <NativeSelectOption value="">{t('cinv.f.all')}</NativeSelectOption>
              {SOURCES.map((x) => <NativeSelectOption key={x} value={x}>{t(`cinv.src.${x}`)}</NativeSelectOption>)}
            </FilterSelect>
            <FilterSelect id="cinv-f-sort" label={t('cinv.f.sort')} value={filters.sort} onChange={set('sort')} slot="cinv-f-sort" className="col-span-2 @lg:col-span-1">
              {SORTS.map((x) => <NativeSelectOption key={x} value={x}>{t(`cinv.sort.${x}`)}</NativeSelectOption>)}
            </FilterSelect>
          </div>
          {(chips.length > 0 || filtered) && (
            <div className="flex min-w-0 flex-wrap items-center gap-2" data-slot="cinv-chips">
              {chips.map(([k, label]) => (
                <Button key={k} type="button" variant="secondary" size="sm" onClick={() => onChange({ [k]: DEFAULT_FILTERS[k] })}
                  aria-label={t('cinv.f.removeChip', label)} data-chip={k} className="min-h-10 max-w-full sm:min-h-8">
                  <span className="truncate">{label}</span><X aria-hidden="true" />
                </Button>
              ))}
              {filtered && (
                <Button type="button" variant="ghost" size="sm" onClick={onClear} data-slot="cinv-clear" className="min-h-10 sm:min-h-8">
                  {t('cinv.f.clear')}
                </Button>
              )}
            </div>
          )}
        </div>
      </CardHeader>
      <CardContent className="min-w-0 p-0">
        <div ref={measureRef} className="min-w-0">
          {rows.length === 0 ? (
            <div data-slot="cinv-empty" className="p-3">
              <StatusBlock tone="neutral" icon={Search} title={filtered ? t('cinv.emptyFiltered') : t('cinv.emptyList')}
                actions={filtered ? <Button type="button" variant="outline" onClick={onClear} className="min-h-10">{t('cinv.f.clear')}</Button> : null} />
            </div>
          ) : tableMode ? (
            <Table className="text-[0.88em]">
              <TableHeader className="bg-muted/50">
                <TableRow>
                  <TableHead className="w-30 min-w-30">{t('cinv.col.priority')}</TableHead>
                  <TableHead className="min-w-52">{t('cinv.col.domain')}</TableHead>
                  <TableHead className="min-w-32">{t('cinv.col.team')}</TableHead>
                  <TableHead className="min-w-32">{t('cinv.col.crypto')}</TableHead>
                  <TableHead className="min-w-44">{t('cinv.col.expiryTarget')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {pager.pageItems.map((r) => (
                  <TableRow key={r.domain} data-slot="cinv-row" data-domain={r.domain} data-category={r.category} data-band={r.priority?.band}>
                    <TableCell className="align-top whitespace-normal">
                      <span className="flex flex-col items-start gap-1.5">
                        <span className="text-xs text-muted-foreground tabular-nums">#{r.rank}</span>
                        <PriorityChip row={r} />
                        <CategoryBadge category={r.category} />
                        <PqcBadge pqc={r.pqc} />
                      </span>
                    </TableCell>
                    <TableCell className="align-top whitespace-normal"><div className="flex min-w-0 items-start gap-1"><div className="min-w-0 flex-1"><Endpoint row={r} /></div><OpenButton row={r} /></div></TableCell>
                    <TableCell className="align-top whitespace-normal"><div className="max-w-40 min-w-0 [&_[data-slot=team-badge]]:max-w-full"><TeamCell row={r} /></div></TableCell>
                    <TableCell className="align-top whitespace-normal"><Crypto row={r} /></TableCell>
                    <TableCell className="align-top whitespace-normal">
                      <span className="flex flex-col gap-1.5"><Expiry row={r} inline /><Target row={r} /></span>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <ul className="m-0 flex list-none flex-col gap-2 p-2.5">
              {pager.pageItems.map((r) => (
                <li key={r.domain} data-slot="cinv-card" data-domain={r.domain} data-category={r.category}
                  className="@container min-w-0 rounded-lg border bg-card p-3">
                  <div className="flex min-w-0 items-start justify-between gap-2">
                    <div className="min-w-0 flex-1"><Endpoint row={r} /></div>
                    <OpenButton row={r} />
                  </div>
                  <div className="mt-2 flex flex-wrap items-center gap-2">
                    <span className="text-xs text-muted-foreground tabular-nums">#{r.rank}</span>
                    <PriorityChip row={r} />
                    <CategoryBadge category={r.category} />
                    <PqcBadge pqc={r.pqc} />
                  </div>
                  <dl className="m-0 mt-2.5 grid grid-cols-2 gap-x-3 gap-y-2 text-sm @xl:grid-cols-4">
                    <div className="min-w-0"><dt className="text-xs text-muted-foreground">{t('cinv.col.team')}</dt><dd className="m-0 min-w-0 [&_[data-slot=team-badge]]:max-w-full"><TeamCell row={r} /></dd></div>
                    <div className="min-w-0"><dt className="text-xs text-muted-foreground">{t('cinv.col.notAfter')}</dt><dd className="m-0"><Expiry row={r} /></dd></div>
                    <div className="col-span-2 min-w-0"><dt className="text-xs text-muted-foreground">{t('cinv.col.crypto')}</dt><dd className="m-0"><Crypto row={r} /></dd></div>
                    <div className="col-span-2 min-w-0"><dt className="text-xs text-muted-foreground">{t('cinv.col.target')}</dt><dd className="m-0"><Target row={r} /></dd></div>
                  </dl>
                </li>
              ))}
            </ul>
          )}
        </div>
        {rows.length > 0 && <div className="border-t px-2 py-2 sm:px-3"><PaginationBar {...pager} /></div>}
      </CardContent>
    </Card>
  )
}
