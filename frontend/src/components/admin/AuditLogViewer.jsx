import { useState, useEffect, useCallback } from 'react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import UserBadge from '../ui/UserBadge.jsx'

const EVENT_TYPES = [
  'LOGIN', 'LOGIN_FAILED', 'LOGOUT',
  'DOMAIN_ADD', 'DOMAIN_DELETE', 'DOMAIN_EDIT',
  'TEAM_CREATE', 'TEAM_DELETE',
  'USER_CREATE', 'USER_DELETE', 'USER_UNLOCK',
]

const OUTCOMES = ['SUCCESS', 'FAILURE', 'BLOCKED']

const ANOMALY_COLORS = {
  OFF_HOURS:    '#f59e0b',
  UNUSUAL_IP:   '#8b5cf6',
  GEO_VELOCITY: '#ef4444',
  BRUTE_FORCE:  '#dc2626',
  RATE_LIMITED: '#64748b',
}

function isoMinus(seconds) {
  return new Date(Date.now() - seconds * 1000).toISOString().slice(0, 19)
}

const CARD_DEFS = [
  { key: 'total_24h',        statKey: 'total_24h',         warn: false, filter: () => ({ since: isoMinus(86400),       anomalyOnly: false, eventType: '' }) },
  { key: 'anomalies_24h',    statKey: 'anomalies_24h',     warn: true,  filter: () => ({ since: isoMinus(86400),       anomalyOnly: true,  eventType: '' }) },
  { key: 'failed_logins_24h',statKey: 'failed_logins_24h', warn: true,  filter: () => ({ since: isoMinus(86400),       anomalyOnly: false, eventType: 'LOGIN_FAILED' }) },
  { key: 'total_7d',         statKey: 'total_7d',          warn: false, filter: () => ({ since: isoMinus(7*86400),     anomalyOnly: false, eventType: '' }) },
  { key: 'anomalies_7d',     statKey: 'anomalies_7d',      warn: true,  filter: () => ({ since: isoMinus(7*86400),     anomalyOnly: true,  eventType: '' }) },
  { key: 'failed_logins_7d', statKey: 'failed_logins_7d',  warn: true,  filter: () => ({ since: isoMinus(7*86400),     anomalyOnly: false, eventType: 'LOGIN_FAILED' }) },
]

function parseBrowser(ua) {
  if (!ua) return '—'
  let browser = 'Unknown'
  if (ua.includes('Edg/') || ua.includes('EdgA/'))       browser = 'Edge'
  else if (ua.includes('OPR/') || ua.includes('Opera/')) browser = 'Opera'
  else if (ua.includes('Chrome/'))                        browser = 'Chrome'
  else if (ua.includes('Firefox/'))                       browser = 'Firefox'
  else if (ua.includes('Safari/'))                        browser = 'Safari'

  let os = ''
  if (ua.includes('Windows NT'))                            os = 'Windows'
  else if (ua.includes('Android'))                          os = 'Android'
  else if (ua.includes('iPhone') || ua.includes('iPad'))    os = 'iOS'
  else if (ua.includes('Mac OS'))                           os = 'macOS'
  else if (ua.includes('Linux'))                            os = 'Linux'

  return os ? `${browser} · ${os}` : browser
}

function AnomalyChips({ flags }) {
  if (!flags) return null
  return (
    <span className="audit-anomalies">
      {flags.split(',').map(f => (
        <span key={f} className="audit-anomaly-chip"
          style={{ background: ANOMALY_COLORS[f] || '#64748b' }}>
          {f.replace(/_/g, ' ')}
        </span>
      ))}
    </span>
  )
}

function StatCard({ label, value, warn, active, onClick }) {
  const hasWarn = warn && value > 0
  return (
    <button
      className={`audit-stat-card${hasWarn ? ' warn' : ''}${active ? ' active' : ''}`}
      onClick={onClick}
      title={label}
    >
      <div className="audit-stat-value">{value ?? '—'}</div>
      <div className="audit-stat-label">{label}</div>
    </button>
  )
}

const EMPTY_FILTERS = { actor: '', eventType: '', outcome: '', since: '', until: '', anomalyOnly: false }

function parseDiff(detail) {
  if (!detail) return null
  try {
    const obj = JSON.parse(detail)
    const entries = Object.entries(obj)
    if (entries.length === 0) return null
    return entries
  } catch { return null }
}

export default function AuditLogViewer() {
  const t = useT()
  const [stats, setStats]         = useState(null)
  const [rows, setRows]           = useState([])
  const [total, setTotal]         = useState(0)
  const [page, setPage]           = useState(0)
  const [loading, setLoading]     = useState(false)
  const [activeCard, setActiveCard] = useState(null)
  const [filters, setFilters]     = useState(EMPTY_FILTERS)
  const [expandedId, setExpandedId] = useState(null)

  const loadStats = useCallback(() => {
    api.admin.getAuditStats().then(r => { if (r?.success) setStats(r.data) })
  }, [])

  const loadLogs = useCallback((p = 0, f = filters) => {
    setLoading(true)
    api.admin.getAuditLogs({ page: p, size: 50, ...f }).then(r => {
      if (r?.success) { setRows(r.data); setTotal(r.total); setPage(r.page) }
    }).finally(() => setLoading(false))
  }, [filters])

  useEffect(() => { loadStats(); loadLogs(0) }, [])

  function handleCardClick(card) {
    if (activeCard === card.key) {
      // second click → deselect, reset to empty
      setActiveCard(null)
      const next = EMPTY_FILTERS
      setFilters(next)
      loadLogs(0, next)
    } else {
      setActiveCard(card.key)
      const extra = card.filter()
      const next = { ...EMPTY_FILTERS, ...extra }
      setFilters(next)
      loadLogs(0, next)
    }
  }

  function applyFilters() {
    setActiveCard(null)
    loadStats()
    loadLogs(0, filters)
  }

  function clearFilters() {
    setActiveCard(null)
    setFilters(EMPTY_FILTERS)
    loadLogs(0, EMPTY_FILTERS)
    loadStats()
  }

  const totalPages = Math.ceil(total / 50)

  return (
    <div className="audit-viewer">
      {/* Stats cards */}
      {stats && (
        <div className="audit-stats-row">
          {CARD_DEFS.map(card => (
            <StatCard
              key={card.key}
              label={t(`audit.${card.key}`)}
              value={stats[card.statKey]}
              warn={card.warn}
              active={activeCard === card.key}
              onClick={() => handleCardClick(card)}
            />
          ))}
        </div>
      )}

      {/* Filters */}
      <div className="audit-filters">
        <input
          className="audit-filter-input"
          placeholder={t('audit.filterActor')}
          value={filters.actor}
          onChange={e => setFilters(f => ({ ...f, actor: e.target.value }))}
        />
        <SearchableSelect
          value={filters.eventType}
          onChange={v => setFilters(f => ({ ...f, eventType: v }))}
          placeholder={t('audit.allEvents')}
          options={[
            { value: '', label: t('audit.allEvents') },
            ...EVENT_TYPES.map(et => ({ value: et, label: et })),
          ]}
        />
        <SearchableSelect
          value={filters.outcome}
          onChange={v => setFilters(f => ({ ...f, outcome: v }))}
          placeholder={t('audit.allOutcomes')}
          options={[
            { value: '', label: t('audit.allOutcomes') },
            ...OUTCOMES.map(o => ({ value: o, label: o })),
          ]}
        />
        <input
          type="datetime-local"
          className="audit-filter-input"
          value={filters.since ? filters.since.replace(' ', 'T') : ''}
          onChange={e => setFilters(f => ({ ...f, since: e.target.value ? e.target.value.slice(0, 19) : '' }))}
        />
        <input
          type="datetime-local"
          className="audit-filter-input"
          value={filters.until ? filters.until.replace(' ', 'T') : ''}
          onChange={e => setFilters(f => ({ ...f, until: e.target.value ? e.target.value.slice(0, 19) : '' }))}
        />
        <label className="audit-filter-check">
          <input
            type="checkbox"
            checked={!!filters.anomalyOnly}
            onChange={e => setFilters(f => ({ ...f, anomalyOnly: e.target.checked }))}
          />
          {t('audit.anomalyOnly')}
        </label>
        <button className="audit-filter-btn primary" onClick={applyFilters}>{t('audit.apply')}</button>
        <button className="audit-filter-btn" onClick={clearFilters}>{t('audit.clear')}</button>
      </div>

      {/* Table */}
      <div className="audit-table-wrap">
        {loading && <div className="audit-loading">{t('app.loading')}</div>}
        <table className="audit-table">
          <thead>
            <tr>
              <th>{t('audit.colTime')}</th>
              <th>{t('audit.colEvent')}</th>
              <th>{t('audit.colActor')}</th>
              <th>{t('audit.colIp')}</th>
              <th>{t('audit.colBrowser')}</th>
              <th>{t('audit.colGeo')}</th>
              <th>{t('audit.colResource')}</th>
              <th>{t('audit.colOutcome')}</th>
              <th>{t('audit.colAnomalies')}</th>
              <th>{t('audit.colDetail')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && !loading && (
              <tr><td colSpan={10} className="audit-empty">{t('audit.empty')}</td></tr>
            )}
            {rows.map(row => {
              const diff = parseDiff(row.detail)
              const isExpanded = expandedId === row.id
              return [
                <tr key={row.id} className={row.anomaly_flags ? 'audit-row-anomaly' : ''}>
                  <td className="audit-cell-time">{formatDate(row.event_time)}</td>
                  <td>
                    <span className={`audit-event-badge ${eventClass(row.event_type)}`}>
                      {row.event_type}
                    </span>
                  </td>
                  <td>
                    <div>{row.actor ? <UserBadge username={row.actor} inline size="sm" /> : '—'}</div>
                    {row.actor_role && <div className="audit-sub">{row.actor_role}</div>}
                  </td>
                  <td className="audit-mono">{row.ip_address || '—'}</td>
                  <td>
                    {row.user_agent
                      ? <span title={row.user_agent}>{parseBrowser(row.user_agent)}</span>
                      : '—'}
                  </td>
                  <td>
                    {row.ip_country && (
                      <div>{row.ip_country}{row.ip_city ? `, ${row.ip_city}` : ''}</div>
                    )}
                    {row.ip_org && <div className="audit-sub">{row.ip_org}</div>}
                  </td>
                  <td>
                    {row.resource_type && <span className="audit-sub">{row.resource_type}: </span>}
                    {row.resource_id || '—'}
                  </td>
                  <td>
                    <span className={`audit-outcome-badge ${row.outcome?.toLowerCase()}`}>
                      {row.outcome || '—'}
                    </span>
                    {row.failure_reason && <div className="audit-sub">{row.failure_reason}</div>}
                  </td>
                  <td><AnomalyChips flags={row.anomaly_flags} /></td>
                  <td>
                    {diff ? (
                      <button
                        className={`audit-detail-toggle${isExpanded ? ' active' : ''}`}
                        onClick={() => setExpandedId(isExpanded ? null : row.id)}
                        title={t('audit.showChanges')}
                      >
                        {diff.length} {t('audit.changesCount')}
                        <span className="audit-detail-arrow">{isExpanded ? '▲' : '▼'}</span>
                      </button>
                    ) : '—'}
                  </td>
                </tr>,
                isExpanded && diff && (
                  <tr key={`${row.id}-detail`} className="audit-detail-tr">
                    <td colSpan={10}>
                      <table className="audit-diff-table">
                        <thead>
                          <tr>
                            <th>{t('audit.diffField')}</th>
                            <th>{t('audit.diffFrom')}</th>
                            <th>{t('audit.diffTo')}</th>
                          </tr>
                        </thead>
                        <tbody>
                          {diff.map(([field, change]) => (
                            <tr key={field}>
                              <td className="audit-diff-field">{field}</td>
                              <td className="audit-diff-from">{String(change.from ?? '—')}</td>
                              <td className="audit-diff-to">{String(change.to ?? '—')}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </td>
                  </tr>
                )
              ]
            })}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      <div className="audit-pagination">
        <button disabled={page === 0} onClick={() => loadLogs(page - 1)}>
          {t('app.prevPage')}
        </button>
        <span>{t('audit.pageInfo', page + 1, totalPages || 1, total)}</span>
        <button disabled={page + 1 >= totalPages} onClick={() => loadLogs(page + 1)}>
          {t('app.nextPage')}
        </button>
      </div>
    </div>
  )
}

function eventClass(et) {
  if (!et) return ''
  if (et === 'LOGIN')          return 'ev-login'
  if (et === 'LOGIN_FAILED')   return 'ev-failed'
  if (et === 'LOGOUT')         return 'ev-logout'
  if (et.endsWith('_DELETE'))  return 'ev-delete'
  if (et.endsWith('_CREATE') || et.endsWith('_ADD')) return 'ev-create'
  if (et.endsWith('_EDIT') || et.endsWith('_UPDATE')) return 'ev-edit'
  return 'ev-other'
}
