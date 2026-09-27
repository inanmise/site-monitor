import { useState, useMemo } from 'react'
import { BarChart3, X } from 'lucide-react'
import { useT } from '../i18n/index.jsx'
import CollapsibleSection from './ui/CollapsibleSection.jsx'
import { formatDate } from '../api/client'
import SearchableSelect from './ui/SearchableSelect.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import { usePagination } from '../hooks/usePagination.js'
import ExecutiveSummary from './ExecutiveSummary.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/** Takım × katman ızgarası durum tonu (eski .ts-hdr-* / .ts-cell-*) — başlık ve dolu hücre aynı renk. */
const STATUS_INK = {
  valid: 'text-green-500', warning: 'text-amber-500', high: 'text-orange-500',
  critical: 'text-red-500', expired: 'text-zinc-400', error: 'text-red-500',
}
/** Etkin süzgeç çipi tonu (eski .sv-chip-*). */
const CHIP_TONE = {
  team: 'border-sky-600/25 bg-sky-600/10 text-sky-700 dark:text-sky-300',
  tier: 'border-indigo-600/25 bg-indigo-600/10 text-indigo-700 dark:text-indigo-300',
  status: 'border-orange-500/25 bg-orange-500/10 text-orange-700 dark:text-orange-300',
}
/** Satır/tablo durum noktası (eski .table-status.status-*). */
const STATUS_DOT = { 'status-valid': 'bg-success', 'status-warning': 'bg-warning', 'status-critical': 'bg-destructive', 'status-error': 'bg-destructive' }

// ── Tier meta ────────────────────────────────────────────────────────────────
const TIER_META = {
  1: { color: '#4f46e5', label: 'T1', descKey: 'tier.desc1' },
  2: { color: '#0284c7', label: 'T2', descKey: 'tier.desc2' },
  3: { color: '#0891b2', label: 'T3', descKey: 'tier.desc3' },
  4: { color: '#71717a', label: 'T4', descKey: 'tier.desc4' },
  0: { color: '#a1a1aa', label: '?',  descKey: 'tier.descNone' },
}

const TH = 'h-10 bg-muted/60 px-2.5 text-[.85em] font-medium text-muted-foreground'
const TD = 'px-2.5 py-2.5 text-[.9em]'

const CELL_STATUSES = [
  { key: 'valid',    labelKey: 'ts.valid'    },
  { key: 'warning',  labelKey: 'ts.warning'  },
  { key: 'high',     labelKey: 'ts.high'     },
  { key: 'critical', labelKey: 'ts.critical' },
  { key: 'expired',  labelKey: 'ts.expired'  },
  { key: 'error',    labelKey: 'ts.error'    },
]

const STATUS_OPTIONS = [
  { value: '',          labelKey: 'tbl.filterAll'       },
  { value: 'valid',     labelKey: 'tbl.filterValid'     },
  { value: 'critical',  labelKey: 'tbl.filterCritical'  },
  { value: 'high',      labelKey: 'tbl.filterHigh'      },
  { value: 'warning',   labelKey: 'tbl.filterWarning'   },
  { value: 'error',     labelKey: 'tbl.filterError'     },
  { value: 'expiring7', labelKey: 'tbl.filterExpiring7' },
]

// ── Helpers ──────────────────────────────────────────────────────────────────
const ALL_DOMAIN_KEYS = ['valid_domains', 'warning_domains', 'high_domains', 'critical_domains', 'expired_domains', 'error_domains']

function extractDomains(teamOrStats) {
  const domains = new Set()
  for (const role of ['sy_stats', 'ug_stats']) {
    for (const key of ALL_DOMAIN_KEYS) {
      for (const d of (teamOrStats[role]?.[key] ?? [])) domains.add(d)
    }
  }
  return domains
}

function buildTeams(teamStats) {
  if (!teamStats) return []
  if (teamStats.mode === 'all_teams') {
    return (teamStats.teams ?? []).map(team => ({
      id:      team.team_id,
      name:    team.team_name,
      domains: extractDomains(team),
    }))
  }
  if (teamStats.mode === 'personal') {
    return [{ id: 0, name: teamStats.team_name ?? '—', domains: extractDomains(teamStats) }]
  }
  return []
}

function buildDomainMap(teamStats) {
  const map = {}
  if (!teamStats) return map

  function addDomains(domains, teamName, kind) {
    for (const d of domains ?? []) {
      if (!map[d]) map[d] = { syTeams: new Set(), ugTeams: new Set() }
      map[d][kind].add(teamName)
    }
  }

  const processMember = (member, teamName) => {
    for (const domKey of ALL_DOMAIN_KEYS) {
      addDomains(member?.sy_stats?.[domKey], teamName, 'syTeams')
      addDomains(member?.ug_stats?.[domKey], teamName, 'ugTeams')
    }
  }

  if (teamStats.mode === 'all_teams') {
    for (const team of teamStats.teams ?? []) processMember(team, team.team_name)
  } else if (teamStats.mode === 'personal') {
    processMember(teamStats, teamStats.team_name ?? '—')
  }

  for (const d of Object.keys(map)) {
    map[d].syTeams = [...map[d].syTeams].sort()
    map[d].ugTeams = [...map[d].ugTeams].sort()
  }
  return map
}

function certCellStatus(c) {
  const d = c.days_remaining
  if (c.status === 'error') return 'error'
  if (d != null && d < 0) return 'expired'
  if (d != null && d <= 7) return 'critical'
  if (d != null && d <= 15) return 'high'
  if (c.warning) return 'warning'
  return 'valid'
}


function defaultPriority(c) {
  const tier = c.tier ?? 99
  if (c.status === 'error' || c.warning) return tier * 10 + (c.status === 'error' ? 0 : 1)
  return 1000 + tier
}

// ── TeamTierSection ───────────────────────────────────────────────────────────
function TeamTierSection({ certs, teamStats, tierFilter, setTierFilter, teamFilter, setTeamFilter, statusFilter, setStatusFilter }) {
  const t     = useT()
  const teams = useMemo(() => buildTeams(teamStats), [teamStats])

  const teamData = useMemo(() => teams.map(team => {
    const teamCerts = certs.filter(c => team.domains.has(c.domain))
    const tierMap   = {}
    for (const c of teamCerts) {
      const k = c.tier ?? 0
      if (k !== 1 && k !== 2) continue
      if (!tierMap[k]) tierMap[k] = { total: 0, valid: 0, warning: 0, high: 0, critical: 0, expired: 0, error: 0 }
      tierMap[k].total++
      tierMap[k][certCellStatus(c)]++
    }
    const tierRows = [1, 2].filter(k => tierMap[k]).map(k => ({ tier: k, ...tierMap[k] }))
    return { ...team, tierRows, total: teamCerts.length }
  })
    .filter(team => team.total > 0)
    .sort((a, b) => b.total - a.total),
  [teams, certs])

  if (teams.length === 0) return null

  function handleCellClick(team, tierKey, statusKey) {
    const same = teamFilter?.label === team.name && tierFilter === tierKey && statusFilter === statusKey
    if (same) {
      setTeamFilter(null); setTierFilter(null); setStatusFilter(null)
    } else {
      setTeamFilter({ domains: team.domains, label: team.name })
      setTierFilter(tierKey)
      setStatusFilter(statusKey)
    }
  }

  function handleTeamHeaderClick(team) {
    if (teamFilter?.label === team.name && tierFilter === null && statusFilter === null) {
      setTeamFilter(null)
    } else {
      setTeamFilter({ domains: team.domains, label: team.name })
      setTierFilter(null)
      setStatusFilter(null)
    }
  }

  return (
    // Takım kartları — shadcn Card; başlık gerçek düğme (Button), ızgara shadcn Table. Telefonda tek sütun.
    <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-2">
      {teamData.map(team => {
        const cardActive = teamFilter?.label === team.name
        return (
          <Card key={team.id} data-slot="ttg-card" data-active={cardActive ? 'true' : undefined}
            className={cn('min-w-0 gap-0 overflow-hidden rounded-[10px] py-0 shadow-none transition-colors motion-reduce:transition-none',
              cardActive && 'border-primary ring-[3px] ring-primary/10')}>
            <Button type="button" variant="ghost" aria-pressed={cardActive} onClick={() => handleTeamHeaderClick(team)}
              className="h-auto w-full justify-between rounded-none border-b bg-muted px-4 py-3 text-left whitespace-normal hover:bg-muted hover:brightness-95 sm:px-[18px]">
              <span className="min-w-0 text-[.95em] font-bold [overflow-wrap:anywhere]">{team.name}</span>
              <span className="shrink-0 text-[.78em] font-semibold text-muted-foreground">{team.total} {t('ts.total')}</span>
            </Button>

            {team.tierRows.length === 0 ? (
              <div className="px-3.5 py-3 text-center text-[.8em] text-muted-foreground">{t('sv.noData')}</div>
            ) : (
              <Table className="text-[.92em]">
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    <TableHead className="w-11" />
                    {CELL_STATUSES.map(({ key, labelKey }) => (
                      <TableHead key={key} className={cn('px-1.5 py-2.5 text-center text-[.76em] font-bold tracking-[.04em] uppercase', STATUS_INK[key])}>{t(labelKey)}</TableHead>
                    ))}
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {team.tierRows.map(row => (
                    <TableRow key={row.tier} className="hover:bg-transparent">
                      <TableCell className="px-2.5 py-3">
                        <Badge className="rounded px-1.5 font-extrabold text-white" style={{ background: TIER_META[row.tier].color }}>
                          {TIER_META[row.tier].label}
                        </Badge>
                      </TableCell>
                      {CELL_STATUSES.map(({ key }) => {
                        const count    = row[key] ?? 0
                        const selected = cardActive && tierFilter === row.tier && statusFilter === key
                        return (
                          <TableCell
                            key={key}
                            data-status={key} data-selected={selected ? 'true' : undefined}
                            className={cn('rounded px-1.5 py-3 text-center text-[1.05em]',
                              count > 0 ? cn('cursor-pointer font-bold hover:opacity-70', STATUS_INK[key]) : 'font-normal text-muted-foreground opacity-45',
                              selected && 'outline-2 -outline-offset-2 outline-current')}
                            tabIndex={count > 0 ? 0 : undefined}
                            aria-label={count > 0 ? `${team.name} — ${t(`ts.${key}`)}: ${count}` : undefined}
                            onClick={() => count > 0 && handleCellClick(team, row.tier, key)}
                            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); count > 0 && handleCellClick(team, row.tier, key) } }}
                          >{count}</TableCell>
                        )
                      })}
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </Card>
        )
      })}
    </div>
  )
}

// ── StatsView (main) ──────────────────────────────────────────────────────────
export default function StatsView({ certs = [], teamStats, onRowClick, onAddDomain, canAddDomain = false }) {
  const t = useT()

  const domainMap = useMemo(() => buildDomainMap(teamStats), [teamStats])

  const [showTeamStats, setShowTeamStats] = useState(false)
  const [tierFilter, setTierFilter]     = useState(null)
  const [teamFilter, setTeamFilter]     = useState(null)
  const [statusFilter, setStatusFilter] = useState(null)
  const [filterDomain, setFilterDomain] = useState('')
  const [filterIssuer, setFilterIssuer] = useState('')
  const [filterStatus, setFilterStatus] = useState('')
  const [sortBy, setSortBy]             = useState('priority|asc')

  const filtered = useMemo(() => {
    let result = certs

    if (tierFilter !== null)
      result = result.filter(c => (c.tier ?? null) === (tierFilter === 0 ? null : tierFilter))
    if (teamFilter)
      result = result.filter(c => teamFilter.domains.has(c.domain))
    if (statusFilter)
      result = result.filter(c => certCellStatus(c) === statusFilter)
    if (filterDomain) {
      const s = filterDomain.toLowerCase()
      result = result.filter(c => c.domain?.toLowerCase().includes(s))
    }
    if (filterIssuer) {
      const s = filterIssuer.toLowerCase()
      result = result.filter(c =>
        c.issuer?.toLowerCase().includes(s) || c.issuer_cn?.toLowerCase().includes(s)
      )
    }
    if (filterStatus === 'valid')    result = result.filter(c => !c.warning && c.status !== 'error')
    if (filterStatus === 'warning')  result = result.filter(c => c.warning && c.status !== 'error')
    if (filterStatus === 'error')    result = result.filter(c => c.status === 'error')
    if (filterStatus === 'critical') result = result.filter(c => {
      const d = c.days_remaining
      return c.status !== 'error' && d !== null && d >= 0 && d <= 30
    })

    const [sb, sd] = sortBy.split('|')
    return [...result].sort((a, b) => {
      let av, bv
      if      (sb === 'priority')       { av = defaultPriority(a); bv = defaultPriority(b) }
      else if (sb === 'days_remaining') { av = a.days_remaining ?? 999999; bv = b.days_remaining ?? 999999 }
      else if (sb === 'domain')         { av = a.domain ?? ''; bv = b.domain ?? '' }
      else if (sb === 'issuer')         { av = a.issuer_cn ?? a.issuer ?? ''; bv = b.issuer_cn ?? b.issuer ?? '' }
      else if (sb === 'checked_at')     { av = a.checked_at ?? ''; bv = b.checked_at ?? '' }
      const cmp = typeof av === 'number' ? av - bv : String(av).localeCompare(String(bv))
      return sd === 'desc' ? -cmp : cmp
    })
  }, [certs, tierFilter, teamFilter, statusFilter, filterDomain, filterIssuer, filterStatus, sortBy])

  // Sayfalama standardı: filtre/sıralama değişince hook kendisi 1. sayfaya döner.
  const pager = usePagination(filtered, {
    listKey: 'stats-table', preset: 'page',
    resetDeps: [tierFilter, teamFilter, statusFilter, filterDomain, filterIssuer, filterStatus, sortBy],
  })

  function clearWidgetFilters() {
    setTierFilter(null); setTeamFilter(null); setStatusFilter(null)
  }
  function resetAll() {
    setTierFilter(null); setTeamFilter(null); setStatusFilter(null)
    setFilterDomain(''); setFilterIssuer(''); setFilterStatus('')
    setSortBy('priority|asc')
  }

  const hasTableFilter  = filterDomain || filterIssuer || filterStatus
  const hasWidgetFilter = tierFilter !== null || teamFilter || statusFilter

  return (
    <div className="sv-root">
      {/* Yönetici özeti (2026-09-12, #20): 4 KPI + takım karşılaştırması + 30 gün delta — tablonun üstünde */}
      <ExecutiveSummary onOpenTeam={(id) => {
        // Takım süzgeci {domains, label} şeklinde (TeamTierSection ile aynı sözleşme); id → sertifikaların takımı
        const domains = new Set(certs.filter((c) => c.team_id === id).map((c) => c.domain))
        const label = certs.find((c) => c.team_id === id)?.team_name ?? String(id)
        setTeamFilter((cur) => (cur?.label === label ? null : { domains, label }))
      }} />

      {/* ── Team × Tier cards — ui/CollapsibleSection (Pano istatistik şeridiyle aynı shadcn Collapsible) ── */}
      <CollapsibleSection open={showTeamStats} onOpenChange={setShowTeamStats}
        icon={BarChart3} label={t('sv.teamStats')} hint={t('sv.showTeamStats')}
        toggleLabel={showTeamStats ? t('sv.hideTeamStats') : t('sv.showTeamStats')}
        triggerClassName="my-3">
        <TeamTierSection
          certs={certs}
          teamStats={teamStats}
          tierFilter={tierFilter}
          setTierFilter={setTierFilter}
          teamFilter={teamFilter}
          setTeamFilter={setTeamFilter}
          statusFilter={statusFilter}
          setStatusFilter={setStatusFilter}
        />
      </CollapsibleSection>

      {/* ── Active widget filter chips ── */}
      {hasWidgetFilter && (
        // Etkin süzgeç çipleri — shadcn Badge + kaldır düğmesi (Button, adı "Süzgeci kaldır: <çip>")
        <div data-slot="sv-filter-bar" className="my-3 flex flex-wrap items-center gap-2">
          <span className="text-[.82em] font-semibold text-muted-foreground">{t('sv.activeFilter')}</span>
          {teamFilter && (
            <Badge variant="outline" data-slot="sv-chip" className={cn('gap-1 py-0.5 pr-0.5', CHIP_TONE.team)}>
              {teamFilter.label}
              <Button type="button" variant="ghost" size="icon-xs" className="size-5 text-inherit opacity-70 hover:bg-transparent hover:opacity-100"
                aria-label={t('a11y.rowAction', teamFilter.label, t('tbl.removeFilter'))} onClick={() => { setTeamFilter(null) }}><X aria-hidden="true" /></Button>
            </Badge>
          )}
          {tierFilter !== null && (
            <Badge variant="outline" data-slot="sv-chip" className={cn('gap-1 py-0.5 pr-0.5', CHIP_TONE.tier)}>
              {tierFilter === 0
                ? t('tier.descNone')
                : `${TIER_META[tierFilter]?.label} — ${t(TIER_META[tierFilter]?.descKey)}`}
              <Button type="button" variant="ghost" size="icon-xs" className="size-5 text-inherit opacity-70 hover:bg-transparent hover:opacity-100"
                aria-label={t('a11y.rowAction', tierFilter === 0 ? t('tier.descNone') : TIER_META[tierFilter]?.label, t('tbl.removeFilter'))}
                onClick={() => { setTierFilter(null) }}><X aria-hidden="true" /></Button>
            </Badge>
          )}
          {statusFilter && (
            <Badge variant="outline" data-slot="sv-chip" className={cn('gap-1 py-0.5 pr-0.5', CHIP_TONE.status)}>
              {t(`ts.${statusFilter}`)}
              <Button type="button" variant="ghost" size="icon-xs" className="size-5 text-inherit opacity-70 hover:bg-transparent hover:opacity-100"
                aria-label={t('a11y.rowAction', t(`ts.${statusFilter}`), t('tbl.removeFilter'))} onClick={() => { setStatusFilter(null) }}><X aria-hidden="true" /></Button>
            </Badge>
          )}
          <Button type="button" variant="outline" size="xs" className="text-muted-foreground hover:border-destructive hover:text-destructive" onClick={clearWidgetFilters}>{t('app.clearFilter')}</Button>
        </div>
      )}

      {/* ── Table filters ── */}
      {/* Tablo süzgeçleri — shadcn Input + Label, ui/SearchableSelect; telefonda tek sütun */}
      <div data-slot="sv-table-filters" className="mb-4 grid grid-cols-1 items-end gap-3 rounded-lg border bg-muted/40 p-3 sm:grid-cols-2 sm:p-4 lg:grid-cols-[repeat(auto-fit,minmax(180px,1fr))]">
        <div className="flex min-w-0 flex-col gap-1.5">
          <Label htmlFor="stats-f-domain" className="text-[.88em] font-semibold">{t('tbl.domainSearch')}</Label>
          <div className="flex gap-2">
            <Input id="stats-f-domain" className="min-w-0 flex-1" placeholder={t('tbl.domainPh')} value={filterDomain}
              onChange={e => { setFilterDomain(e.target.value) }} />
            {canAddDomain && onAddDomain && (
              <Button variant="success" className="shrink-0" onClick={onAddDomain}>{t('inv.addBtn')}</Button>
            )}
          </div>
        </div>
        <div className="flex min-w-0 flex-col gap-1.5">
          <Label htmlFor="stats-f-issuer" className="text-[.88em] font-semibold">{t('tbl.issuerSearch')}</Label>
          <Input id="stats-f-issuer" placeholder={t('tbl.issuerPh')} value={filterIssuer}
            onChange={e => { setFilterIssuer(e.target.value) }} />
        </div>
        <div className="flex min-w-0 flex-col gap-1.5">
          <Label htmlFor="stats-f-status" className="text-[.88em] font-semibold">{t('tbl.colStatus')}</Label>
          <SearchableSelect
            id="stats-f-status"
            value={filterStatus}
            onChange={v => { setFilterStatus(v) }}
            options={STATUS_OPTIONS.map(o => ({ value: o.value, label: t(o.labelKey) }))}
          />
        </div>
        <div className="flex min-w-0 flex-col gap-1.5">
          <Label htmlFor="stats-f-sort" className="text-[.88em] font-semibold">{t('tbl.sort')}</Label>
          <SearchableSelect
            id="stats-f-sort"
            value={sortBy}
            onChange={v => { setSortBy(v) }}
            options={[
              { value: 'priority|asc',        label: t('tbl.sortPriority') },
              { value: 'domain|asc',          label: t('tbl.sortDomainAsc') },
              { value: 'domain|desc',         label: t('tbl.sortDomainDesc') },
              { value: 'issuer|asc',          label: t('tbl.sortIssuerAsc') },
              { value: 'issuer|desc',         label: t('tbl.sortIssuerDesc') },
              { value: 'days_remaining|asc',  label: t('tbl.sortDaysAsc') },
              { value: 'days_remaining|desc', label: t('tbl.sortDaysDesc') },
              { value: 'checked_at|desc',     label: t('tbl.sortChecked') },
            ]}
          />
        </div>
        {hasTableFilter && (
          <Button variant="secondary" className="self-end" onClick={resetAll}>
            {t('tbl.reset')}
          </Button>
        )}
      </div>

      {/* ── Certificate table ── shadcn Table (yatay kayar; ekip/veren sütunları dar ekranda gizli) */}
      <div className="rounded-lg border bg-card">
      <Table data-slot="sv-table">
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            <TableHead className={TH}>{t('tbl.colDomain')}</TableHead>
            <TableHead className={cn(TH, 'hidden md:table-cell')}>{t('sv.colSyTeam')}</TableHead>
            <TableHead className={cn(TH, 'hidden lg:table-cell')}>{t('sv.colUgTeam')}</TableHead>
            <TableHead className={TH}>{t('sv.colTier')}</TableHead>
            <TableHead className={cn(TH, 'hidden md:table-cell')}>{t('tbl.colIssuer')}</TableHead>
            <TableHead className={TH}>{t('tbl.colExpiry')}</TableHead>
            <TableHead className={TH}>{t('tbl.colDays')}</TableHead>
            <TableHead className={TH}>{t('tbl.colStatus')}</TableHead>
            <TableHead className={cn(TH, 'hidden lg:table-cell')}>{t('tbl.colChecked')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {pager.pageItems.length === 0 ? (
            <TableRow className="hover:bg-transparent"><TableCell colSpan={9} className="py-8 text-center text-muted-foreground">{t('tbl.noCerts')}</TableCell></TableRow>
          ) : pager.pageItems.map(cert => {
            const days = cert.days_remaining
            const isCritical  = cert.status !== 'error' && days !== null && days >= 0 && days <= 30
            const statusClass = cert.status === 'error' ? 'status-error'
              : isCritical ? 'status-critical'
              : cert.warning  ? 'status-warning' : 'status-valid'
            const statusText  = cert.status === 'error' ? t('tbl.statusError')
              : isCritical ? t('tbl.statusCritical')
              : cert.warning  ? t('tbl.statusWarning') : t('tbl.statusValid')
            const teamInfo    = domainMap[cert.domain]
            return (
              <TableRow key={cert.domain} tabIndex={0} data-domain={cert.domain}
                className="cursor-pointer focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary"
                aria-label={t('a11y.openRow', cert.domain)}
                onClick={() => onRowClick?.(cert.domain)}
                onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); onRowClick?.(cert.domain) } }}>
                <TableCell className={TD}><strong>{cert.domain}</strong></TableCell>
                <TableCell className={cn(TD, 'hidden whitespace-normal md:table-cell')}>
                  {teamInfo?.syTeams?.length
                    ? teamInfo.syTeams.join(', ')
                    : <span className="text-muted-foreground">—</span>}
                </TableCell>
                <TableCell className={cn(TD, 'hidden whitespace-normal lg:table-cell')}>
                  {teamInfo?.ugTeams?.length
                    ? teamInfo.ugTeams.join(', ')
                    : <span className="text-muted-foreground">—</span>}
                </TableCell>
                <TableCell className={TD}>
                  {cert.tier != null
                    ? <Badge className="rounded px-1.5 font-extrabold text-white" style={{ background: (TIER_META[cert.tier] || TIER_META[0]).color }}>
                        {(TIER_META[cert.tier] || TIER_META[0]).label}
                      </Badge>
                    : <span className="text-muted-foreground">—</span>}
                </TableCell>
                <TableCell className={cn(TD, 'hidden md:table-cell')}>{cert.issuer_cn || cert.issuer || 'N/A'}</TableCell>
                <TableCell className={TD}>{formatDate(cert.not_after)}</TableCell>
                <TableCell className={TD}><strong>{days ?? 'N/A'}</strong></TableCell>
                <TableCell className={TD}>
                  <span className="inline-flex items-center gap-1.5" data-status={statusClass.replace('status-', '')}>
                    <span aria-hidden="true" className={cn('size-2 shrink-0 rounded-full', STATUS_DOT[statusClass])} />{statusText}
                  </span>
                </TableCell>
                <TableCell className={cn(TD, 'hidden lg:table-cell')}>{formatDate(cert.checked_at)}</TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
      </div>

      {/* ── Pagination ── */}
      <PaginationBar {...pager} />
    </div>
  )
}
