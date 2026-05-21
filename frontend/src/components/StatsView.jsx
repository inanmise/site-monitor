import { useState, useMemo } from 'react'
import { useT } from '../i18n/index.jsx'
import { formatDate } from '../api/client'
import SearchableSelect from './ui/SearchableSelect.jsx'

// ── Tier meta ────────────────────────────────────────────────────────────────
const TIER_META = {
  1: { color: '#4f46e5', label: 'T1', descKey: 'tier.desc1' },
  2: { color: '#0284c7', label: 'T2', descKey: 'tier.desc2' },
  3: { color: '#0891b2', label: 'T3', descKey: 'tier.desc3' },
  4: { color: '#6b7280', label: 'T4', descKey: 'tier.desc4' },
  0: { color: '#94a3b8', label: '?',  descKey: 'tier.descNone' },
}
const TIER_ORDER = [1, 2, 3, 4, 0]

const STATUS_OPTIONS = [
  { value: '',         labelKey: 'tbl.filterAll'      },
  { value: 'valid',    labelKey: 'tbl.filterValid'    },
  { value: 'warning',  labelKey: 'tbl.filterWarning'  },
  { value: 'critical', labelKey: 'tbl.filterCritical' },
  { value: 'error',    labelKey: 'tbl.filterError'    },
]

// ── Helpers ──────────────────────────────────────────────────────────────────
function extractDomains(teamOrStats) {
  const domains = new Set()
  for (const role of ['sy_stats', 'ug_stats']) {
    for (const key of ['valid_domains', 'warning_domains', 'error_domains']) {
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

// domain → { teamName, role: 'SY'|'UG' }
function buildDomainMap(teamStats) {
  const map = {}
  if (!teamStats) return map

  function addDomains(domains, teamName, role) {
    for (const d of domains ?? []) {
      if (!map[d]) map[d] = { teamName, role }
    }
  }

  const processMember = (member, teamName) => {
    for (const domKey of ['valid_domains', 'warning_domains', 'error_domains']) {
      addDomains(member?.sy_stats?.[domKey], teamName, 'SY')
      addDomains(member?.ug_stats?.[domKey], teamName, 'UG')
    }
  }

  if (teamStats.mode === 'all_teams') {
    for (const team of teamStats.teams ?? []) processMember(team, team.team_name)
  } else if (teamStats.mode === 'personal') {
    processMember(teamStats, teamStats.team_name ?? '—')
  }
  return map
}

function buildPageRange(current, total, max = 5) {
  let start = Math.max(1, current - Math.floor(max / 2))
  let end   = Math.min(total, start + max - 1)
  if (end - start + 1 < max) start = Math.max(1, end - max + 1)
  return Array.from({ length: end - start + 1 }, (_, i) => start + i)
}

function defaultPriority(c) {
  const tier = c.tier ?? 99
  if (c.status === 'error' || c.warning) return tier * 10 + (c.status === 'error' ? 0 : 1)
  return 1000 + tier
}

// ── TeamTierSection ───────────────────────────────────────────────────────────
function TeamTierSection({ certs, teamStats, tierFilter, setTierFilter, teamFilter, setTeamFilter, setPage }) {
  const t     = useT()
  const teams = useMemo(() => buildTeams(teamStats), [teamStats])

  const teamData = useMemo(() => teams.map(team => {
    const teamCerts = certs.filter(c => team.domains.has(c.domain))
    const tierMap   = {}
    for (const c of teamCerts) {
      const k = c.tier ?? 0
      if (!tierMap[k]) tierMap[k] = { total: 0, valid: 0, warning: 0, error: 0 }
      tierMap[k].total++
      if (c.status === 'error') tierMap[k].error++
      else if (c.warning)       tierMap[k].warning++
      else                      tierMap[k].valid++
    }
    const tierRows     = TIER_ORDER.filter(k => tierMap[k]).map(k => ({ tier: k, ...tierMap[k] }))
    const maxTierCount = Math.max(1, ...tierRows.map(r => r.total))
    return { ...team, tierRows, total: teamCerts.length, maxTierCount }
  }), [teams, certs])

  if (teams.length === 0) return null

  function handleTierRowClick(team, tierKey) {
    const sameTeam = teamFilter?.label === team.name
    const sameTier = tierFilter === tierKey
    if (sameTeam && sameTier) {
      setTeamFilter(null); setTierFilter(null)
    } else {
      setTeamFilter({ domains: team.domains, label: team.name })
      setTierFilter(tierKey)
    }
    setPage(1)
  }

  function handleTeamHeaderClick(team) {
    if (teamFilter?.label === team.name && tierFilter === null) {
      setTeamFilter(null)
    } else {
      setTeamFilter({ domains: team.domains, label: team.name })
      setTierFilter(null)
    }
    setPage(1)
  }

  return (
    <div className="ttg-root">
      {teamData.map(team => {
        const cardActive = teamFilter?.label === team.name
        return (
          <div key={team.id} className={`ttg-card${cardActive ? ' ttg-card-active' : ''}`}>

            {/* Card header — click = filter by whole team */}
            <div className="ttg-header" onClick={() => handleTeamHeaderClick(team)}>
              <span className="ttg-team-name">{team.name}</span>
              <span className="ttg-team-total">{team.total} {t('ts.total')}</span>
            </div>

            {team.total === 0 ? (
              <div className="ttg-empty">{t('sv.noData')}</div>
            ) : (
              <div className="ttg-rows">
                {/* Column headers */}
                <div className="ttg-row ttg-row-head">
                  <span className="ttg-col-badge" />
                  <span className="ttg-col-bar" />
                  <span className="ttg-col-count">#</span>
                  <span className="ttg-col-chips">
                    <span className="ttg-chip-head ttg-ok">✓</span>
                    <span className="ttg-chip-head ttg-warn">⚠</span>
                    <span className="ttg-chip-head ttg-err">✗</span>
                  </span>
                </div>

                {team.tierRows.map(row => {
                  const meta     = TIER_META[row.tier]
                  const barPct   = Math.round((row.total / team.maxTierCount) * 100)
                  const isActive = cardActive && tierFilter === row.tier
                  return (
                    <div
                      key={row.tier}
                      className={`ttg-row${isActive ? ' ttg-row-active' : ''}`}
                      onClick={() => handleTierRowClick(team, row.tier)}
                      title={t(meta.descKey)}
                    >
                      <span className="ttg-col-badge">
                        <span className="ttg-badge" style={{ background: meta.color }}>{meta.label}</span>
                      </span>
                      <span className="ttg-col-bar">
                        <span className="ttg-bar-track">
                          <span className="ttg-bar-fill" style={{ width: `${barPct}%`, background: meta.color }} />
                        </span>
                      </span>
                      <span className="ttg-col-count">{row.total}</span>
                      <span className="ttg-col-chips">
                        <span className={`ttg-chip ttg-ok${row.valid === 0 ? ' ttg-chip-zero' : ''}`}>{row.valid}</span>
                        <span className={`ttg-chip ttg-warn${row.warning === 0 ? ' ttg-chip-zero' : ''}`}>{row.warning}</span>
                        <span className={`ttg-chip ttg-err${row.error === 0 ? ' ttg-chip-zero' : ''}`}>{row.error}</span>
                      </span>
                    </div>
                  )
                })}
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}

// ── StatsView (main) ──────────────────────────────────────────────────────────
export default function StatsView({ certs = [], teamStats, onRowClick }) {
  const t = useT()

  const domainMap = useMemo(() => buildDomainMap(teamStats), [teamStats])

  const [tierFilter, setTierFilter]     = useState(null)
  const [teamFilter, setTeamFilter]     = useState(null)
  const [filterDomain, setFilterDomain] = useState('')
  const [filterIssuer, setFilterIssuer] = useState('')
  const [filterStatus, setFilterStatus] = useState('')
  const [sortBy, setSortBy]             = useState('priority|asc')
  const [perPage, setPerPage]           = useState(20)
  const [page, setPage]                 = useState(1)

  const filtered = useMemo(() => {
    let result = certs

    if (tierFilter !== null)
      result = result.filter(c => (c.tier ?? null) === (tierFilter === 0 ? null : tierFilter))
    if (teamFilter)
      result = result.filter(c => teamFilter.domains.has(c.domain))
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
  }, [certs, tierFilter, teamFilter, filterDomain, filterIssuer, filterStatus, sortBy])

  const totalPages = Math.max(1, Math.ceil(filtered.length / perPage))
  const safePage   = Math.min(page, totalPages)
  const pageItems  = filtered.slice((safePage - 1) * perPage, safePage * perPage)

  function clearWidgetFilters() {
    setTierFilter(null); setTeamFilter(null); setPage(1)
  }
  function resetAll() {
    setTierFilter(null); setTeamFilter(null)
    setFilterDomain(''); setFilterIssuer(''); setFilterStatus('')
    setSortBy('priority|asc'); setPerPage(20); setPage(1)
  }

  const hasTableFilter = filterDomain || filterIssuer || filterStatus

  return (
    <div className="sv-root">

      {/* ── Team × Tier cards ── */}
      <TeamTierSection
        certs={certs}
        teamStats={teamStats}
        tierFilter={tierFilter}
        setTierFilter={setTierFilter}
        teamFilter={teamFilter}
        setTeamFilter={setTeamFilter}
        setPage={setPage}
      />

      {/* ── Active widget filter chips ── */}
      {(tierFilter !== null || teamFilter) && (
        <div className="sv-filter-bar">
          <span className="sv-filter-label">{t('sv.activeFilter')}</span>
          {teamFilter && (
            <span className="sv-chip sv-chip-team">
              {teamFilter.label}
              <button className="sv-chip-x" onClick={() => { setTeamFilter(null); setPage(1) }}>✕</button>
            </span>
          )}
          {tierFilter !== null && (
            <span className="sv-chip sv-chip-tier">
              {tierFilter === 0
                ? t('tier.descNone')
                : `${TIER_META[tierFilter]?.label} — ${t(TIER_META[tierFilter]?.descKey)}`}
              <button className="sv-chip-x" onClick={() => { setTierFilter(null); setPage(1) }}>✕</button>
            </span>
          )}
          <button className="sv-clear-all" onClick={clearWidgetFilters}>{t('app.clearFilter')}</button>
        </div>
      )}

      {/* ── Table filters ── */}
      <div className="advanced-filters sv-table-filters">
        <div className="filter-group">
          <label>{t('tbl.domainSearch')}</label>
          <input className="filter-input" placeholder={t('tbl.domainPh')} value={filterDomain}
            onChange={e => { setFilterDomain(e.target.value); setPage(1) }} />
        </div>
        <div className="filter-group">
          <label>{t('tbl.issuerSearch')}</label>
          <input className="filter-input" placeholder={t('tbl.issuerPh')} value={filterIssuer}
            onChange={e => { setFilterIssuer(e.target.value); setPage(1) }} />
        </div>
        <div className="filter-group">
          <label>{t('tbl.colStatus')}</label>
          <SearchableSelect
            value={filterStatus}
            onChange={v => { setFilterStatus(v); setPage(1) }}
            options={STATUS_OPTIONS.map(o => ({ value: o.value, label: t(o.labelKey) }))}
          />
        </div>
        <div className="filter-group">
          <label>{t('tbl.sort')}</label>
          <SearchableSelect
            value={sortBy}
            onChange={v => { setSortBy(v); setPage(1) }}
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
        <div className="filter-group">
          <label>{t('tbl.perPage')}</label>
          <SearchableSelect
            value={perPage}
            onChange={v => { setPerPage(Number(v)); setPage(1) }}
            options={[
              { value: 10,  label: '10' },
              { value: 20,  label: '20' },
              { value: 50,  label: '50' },
              { value: 100, label: '100' },
            ]}
          />
        </div>
        {hasTableFilter && (
          <button className="btn btn-secondary" style={{ marginTop: 24 }} onClick={resetAll}>
            {t('tbl.reset')}
          </button>
        )}
      </div>

      {/* ── Certificate table ── */}
      <table className="certificates-table">
        <thead>
          <tr>
            <th>{t('tbl.colDomain')}</th>
            <th>{t('sv.colTeam')}</th>
            <th>{t('sv.colRole')}</th>
            <th>{t('tbl.colIssuer')}</th>
            <th>{t('tbl.colExpiry')}</th>
            <th>{t('tbl.colDays')}</th>
            <th>{t('tbl.colStatus')}</th>
            <th>{t('tbl.colChecked')}</th>
          </tr>
        </thead>
        <tbody>
          {pageItems.length === 0 ? (
            <tr><td colSpan={8} className="loading">{t('tbl.noCerts')}</td></tr>
          ) : pageItems.map(cert => {
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
              <tr key={cert.domain} onClick={() => onRowClick?.(cert.domain)} style={{ cursor: 'pointer' }}>
                <td><strong>{cert.domain}</strong></td>
                <td className="sv-team-cell">{teamInfo?.teamName ?? <span className="sv-cell-muted">—</span>}</td>
                <td>
                  {teamInfo?.role
                    ? <span className={`sv-role-badge sv-role-${teamInfo.role.toLowerCase()}`}>{teamInfo.role}</span>
                    : <span className="sv-cell-muted">—</span>}
                </td>
                <td>{cert.issuer_cn || cert.issuer || 'N/A'}</td>
                <td>{formatDate(cert.not_after)}</td>
                <td><strong>{days ?? 'N/A'}</strong></td>
                <td><span className={`table-status ${statusClass}`} />{statusText}</td>
                <td>{formatDate(cert.checked_at)}</td>
              </tr>
            )
          })}
        </tbody>
      </table>

      {/* ── Pagination ── */}
      <div className="pagination-controls">
        <div className="pagination-info">
          {t('tbl.total', filtered.length, safePage, totalPages)}
        </div>
        <div className="pagination-buttons">
          <button className="btn btn-secondary" disabled={safePage <= 1}
            onClick={() => setPage(p => p - 1)}>{t('tbl.prev')}</button>
          <span className="page-numbers">
            {buildPageRange(safePage, totalPages).map(n => (
              <button key={n}
                className={`page-btn${n === safePage ? ' active' : ''}`}
                disabled={n === safePage}
                onClick={() => setPage(n)}
              >{n}</button>
            ))}
          </span>
          <button className="btn btn-secondary" disabled={safePage >= totalPages}
            onClick={() => setPage(p => p + 1)}>{t('tbl.next')}</button>
        </div>
      </div>
    </div>
  )
}
