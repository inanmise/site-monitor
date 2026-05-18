import { useState, useEffect, useCallback } from 'react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'

const EVENT_TYPES = [
  'LOGIN', 'LOGIN_FAILED', 'LOGOUT',
  'DOMAIN_ADD', 'DOMAIN_DELETE',
  'TEAM_CREATE', 'TEAM_DELETE',
  'USER_CREATE', 'USER_DELETE',
]

const OUTCOMES = ['SUCCESS', 'FAILURE', 'BLOCKED']

const ANOMALY_COLORS = {
  OFF_HOURS:   '#f59e0b',
  UNUSUAL_IP:  '#8b5cf6',
  GEO_VELOCITY:'#ef4444',
  BRUTE_FORCE: '#dc2626',
  RATE_LIMITED:'#64748b',
}

function parseBrowser(ua) {
  if (!ua) return null
  let browser = 'Unknown'
  if (ua.includes('Edg/') || ua.includes('EdgA/'))      browser = 'Edge'
  else if (ua.includes('OPR/') || ua.includes('Opera/')) browser = 'Opera'
  else if (ua.includes('Chrome/'))                       browser = 'Chrome'
  else if (ua.includes('Firefox/'))                      browser = 'Firefox'
  else if (ua.includes('Safari/'))                       browser = 'Safari'

  let os = ''
  if (ua.includes('Windows NT'))      os = 'Windows'
  else if (ua.includes('Android'))    os = 'Android'
  else if (ua.includes('iPhone') || ua.includes('iPad')) os = 'iOS'
  else if (ua.includes('Mac OS'))     os = 'macOS'
  else if (ua.includes('Linux'))      os = 'Linux'

  return os ? `${browser} · ${os}` : browser
}

function AnomalyChips({ flags }) {
  if (!flags) return null
  return (
    <span className="audit-anomalies">
      {flags.split(',').map(f => (
        <span key={f} className="audit-anomaly-chip"
          style={{ background: ANOMALY_COLORS[f] || '#64748b' }}>
          {f.replace('_', ' ')}
        </span>
      ))}
    </span>
  )
}

function StatCard({ label, value, warn }) {
  return (
    <div className={`audit-stat-card${warn && value > 0 ? ' warn' : ''}`}>
      <div className="audit-stat-value">{value ?? '—'}</div>
      <div className="audit-stat-label">{label}</div>
    </div>
  )
}

export default function AuditLogViewer() {
  const t = useT()
  const [stats, setStats]   = useState(null)
  const [rows, setRows]     = useState([])
  const [total, setTotal]   = useState(0)
  const [page, setPage]     = useState(0)
  const [loading, setLoading] = useState(false)

  const [filters, setFilters] = useState({
    actor: '', eventType: '', outcome: '', since: '', until: '',
  })

  const loadStats = useCallback(() => {
    api.admin.getAuditStats().then(r => { if (r?.success) setStats(r.data) })
  }, [])

  const loadLogs = useCallback((p = 0, f = filters) => {
    setLoading(true)
    api.admin.getAuditLogs({ page: p, size: 50, ...f }).then(r => {
      if (r?.success) {
        setRows(r.data)
        setTotal(r.total)
        setPage(r.page)
      }
    }).finally(() => setLoading(false))
  }, [filters])

  useEffect(() => {
    loadStats()
    loadLogs(0)
  }, [])

  function applyFilters() {
    loadStats()
    loadLogs(0, filters)
  }

  function clearFilters() {
    const empty = { actor: '', eventType: '', outcome: '', since: '', until: '' }
    setFilters(empty)
    loadLogs(0, empty)
    loadStats()
  }

  const totalPages = Math.ceil(total / 50)

  return (
    <div className="audit-viewer">
      {/* Stats row */}
      {stats && (
        <div className="audit-stats-row">
          <StatCard label={t('audit.total24h')}       value={stats.total_24h} />
          <StatCard label={t('audit.anomalies24h')}   value={stats.anomalies_24h}    warn />
          <StatCard label={t('audit.failLogins24h')}  value={stats.failed_logins_24h} warn />
          <StatCard label={t('audit.total7d')}        value={stats.total_7d} />
          <StatCard label={t('audit.anomalies7d')}    value={stats.anomalies_7d}     warn />
          <StatCard label={t('audit.failLogins7d')}   value={stats.failed_logins_7d}  warn />
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
        <select
          className="audit-filter-input"
          value={filters.eventType}
          onChange={e => setFilters(f => ({ ...f, eventType: e.target.value }))}
        >
          <option value="">{t('audit.allEvents')}</option>
          {EVENT_TYPES.map(et => <option key={et} value={et}>{et}</option>)}
        </select>
        <select
          className="audit-filter-input"
          value={filters.outcome}
          onChange={e => setFilters(f => ({ ...f, outcome: e.target.value }))}
        >
          <option value="">{t('audit.allOutcomes')}</option>
          {OUTCOMES.map(o => <option key={o} value={o}>{o}</option>)}
        </select>
        <input
          type="datetime-local"
          className="audit-filter-input"
          value={filters.since}
          onChange={e => setFilters(f => ({ ...f, since: e.target.value ? e.target.value.replace('T', ' ').substring(0, 19) : '' }))}
        />
        <input
          type="datetime-local"
          className="audit-filter-input"
          value={filters.until}
          onChange={e => setFilters(f => ({ ...f, until: e.target.value ? e.target.value.replace('T', ' ').substring(0, 19) : '' }))}
        />
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
              <th>{t('audit.colGeo')}</th>
              <th>{t('audit.colResource')}</th>
              <th>{t('audit.colOutcome')}</th>
              <th>{t('audit.colAnomalies')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && !loading && (
              <tr><td colSpan={8} className="audit-empty">{t('audit.empty')}</td></tr>
            )}
            {rows.map(row => (
              <tr key={row.id} className={row.anomaly_flags ? 'audit-row-anomaly' : ''}>
                <td className="audit-cell-time">{formatDate(row.event_time)}</td>
                <td>
                  <span className={`audit-event-badge ${eventClass(row.event_type)}`}>
                    {row.event_type}
                  </span>
                </td>
                <td>
                  <div>{row.actor || '—'}</div>
                  {row.actor_role && <div className="audit-sub">{row.actor_role}</div>}
                </td>
                <td>
                  <div className="audit-mono">{row.ip_address || '—'}</div>
                  {row.user_agent && (
                    <div className="audit-sub" title={row.user_agent}>
                      {parseBrowser(row.user_agent)}
                    </div>
                  )}
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
              </tr>
            ))}
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
  if (et === 'LOGIN') return 'ev-login'
  if (et === 'LOGIN_FAILED') return 'ev-failed'
  if (et === 'LOGOUT') return 'ev-logout'
  if (et.endsWith('_DELETE')) return 'ev-delete'
  if (et.endsWith('_CREATE') || et.endsWith('_ADD')) return 'ev-create'
  return 'ev-other'
}
