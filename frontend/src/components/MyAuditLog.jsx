import { useState, useEffect, useCallback } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'

const EVENT_TYPES = [
  'LOGIN', 'LOGIN_FAILED', 'LOGOUT',
  'SELF_PASSWORD_CHANGE',
  'DOMAIN_ADD', 'DOMAIN_DELETE', 'DOMAIN_EDIT',
]
const OUTCOMES = ['SUCCESS', 'FAILURE', 'BLOCKED']
const EMPTY_FILTERS = { eventType: '', outcome: '', since: '', until: '' }
const PAGE_SIZE = 50

function eventClass(et) {
  if (!et) return ''
  if (et === 'LOGIN')         return 'ev-login'
  if (et === 'LOGIN_FAILED')  return 'ev-failed'
  if (et === 'LOGOUT')        return 'ev-logout'
  if (et.endsWith('_DELETE')) return 'ev-delete'
  if (et.endsWith('_CREATE') || et.endsWith('_ADD')) return 'ev-create'
  if (et.endsWith('_EDIT')   || et.endsWith('_UPDATE')) return 'ev-edit'
  return 'ev-other'
}

export default function MyAuditLog() {
  const t = useT()
  const [rows, setRows] = useState([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(false)
  const [filters, setFilters] = useState(EMPTY_FILTERS)

  const loadLogs = useCallback((p = 0, f = filters) => {
    setLoading(true)
    api.me.getMyAudit({ page: p, size: PAGE_SIZE, ...f }).then(r => {
      if (r?.success) { setRows(r.data || []); setTotal(r.total || 0); setPage(r.page ?? p) }
    }).finally(() => setLoading(false))
  }, [filters])

  useEffect(() => { loadLogs(0) }, [])

  function applyFilters() { loadLogs(0, filters) }
  function clearFilters() { setFilters(EMPTY_FILTERS); loadLogs(0, EMPTY_FILTERS) }

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  return (
    <div className="audit-viewer">
      <div className="audit-filters">
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
        <button className="audit-filter-btn primary" onClick={applyFilters}>{t('audit.apply')}</button>
        <button className="audit-filter-btn" onClick={clearFilters}>{t('audit.clear')}</button>
      </div>

      <div className="audit-table-wrap">
        {loading && <div className="audit-loading">{t('app.loading')}</div>}
        <table className="audit-table">
          <thead>
            <tr>
              <th>{t('audit.colTime')}</th>
              <th>{t('audit.colEvent')}</th>
              <th>{t('audit.colOutcome')}</th>
              <th>{t('audit.colResource')}</th>
              <th>{t('audit.colIp')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && !loading && (
              <tr><td colSpan={5} className="audit-empty">{t('audit.empty')}</td></tr>
            )}
            {rows.map(row => (
              <tr key={row.id}>
                <td className="audit-cell-time">{formatDate(row.event_time)}</td>
                <td>
                  <span className={`audit-event-badge ${eventClass(row.event_type)}`}>
                    {row.event_type}
                  </span>
                </td>
                <td>
                  <span className={`audit-outcome-badge ${(row.outcome || '').toLowerCase()}`}>
                    {row.outcome || '—'}
                  </span>
                  {row.failure_reason && <div className="audit-sub">{row.failure_reason}</div>}
                </td>
                <td>
                  {row.resource_type && <span className="audit-sub">{row.resource_type}: </span>}
                  {row.resource_id || '—'}
                </td>
                <td className="audit-mono">{row.ip_address || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="audit-pagination">
        <button disabled={page === 0} onClick={() => loadLogs(page - 1)}>
          {t('app.prevPage')}
        </button>
        <span>{t('audit.pageInfo', page + 1, totalPages, total)}</span>
        <button disabled={page + 1 >= totalPages} onClick={() => loadLogs(page + 1)}>
          {t('app.nextPage')}
        </button>
      </div>
    </div>
  )
}
