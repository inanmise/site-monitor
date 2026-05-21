import { useState, useEffect, useCallback, useRef } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'

export default function CertificatesTable({ onRowClick }) {
  const t = useT()

  const STATUS_OPTIONS = [
    { value: '',         labelKey: 'tbl.filterAll',      icon: null },
    { value: 'valid',    labelKey: 'tbl.filterValid',    icon: '✓', cls: 'cf-opt-valid'  },
    { value: 'warning',  labelKey: 'tbl.filterWarning',  icon: '⚠', cls: 'cf-opt-warn'   },
    { value: 'critical', labelKey: 'tbl.filterCritical', icon: '🔴', cls: 'cf-opt-crit'  },
    { value: 'error',    labelKey: 'tbl.filterError',    icon: '✗', cls: 'cf-opt-err'   },
  ]

  const [certs, setCerts]           = useState([])
  const [pagination, setPagination] = useState({ current_page: 1, total: 0, total_pages: 1 })
  const [page, setPage]             = useState(1)
  const [perPage, setPerPage]       = useState(20)
  const [sortBy, setSortBy]         = useState('priority|asc')
  const [filterDomain, setFilterDomain] = useState('')
  const [filterIssuer, setFilterIssuer] = useState('')
  const [filterStatus, setFilterStatus] = useState('')
  const [loading, setLoading]       = useState(false)
  const [statusDropOpen, setStatusDropOpen] = useState(false)
  const statusDropRef = useRef(null)

  useEffect(() => {
    function handleClick(e) {
      if (statusDropRef.current && !statusDropRef.current.contains(e.target)) {
        setStatusDropOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    const [sb, sd] = sortBy.split('|')
    const data = await api.getCertificatesPaginated({
      page, per_page: perPage, sort_by: sb, sort_dir: sd,
      filter_domain: filterDomain, filter_issuer: filterIssuer, filter_status: filterStatus,
    })
    if (data?.success) {
      setCerts(data.data)
      setPagination(data.pagination)
    }
    setLoading(false)
  }, [page, perPage, sortBy, filterDomain, filterIssuer, filterStatus])

  useEffect(() => { load() }, [load])

  function reset() {
    setFilterDomain(''); setFilterIssuer(''); setFilterStatus('')
    setSortBy('priority|asc'); setPerPage(20); setPage(1)
    setStatusDropOpen(false)
  }

  function selectStatus(val) {
    setFilterStatus(val)
    setStatusDropOpen(false)
    setPage(1)
  }

  const activeStatusLabel = t(STATUS_OPTIONS.find(o => o.value === filterStatus)?.labelKey ?? 'tbl.filterAll')
  const p         = pagination
  const pageRange = buildPageRange(p.current_page, p.total_pages)

  return (
    <>
      <div className="advanced-filters">
        <div className="filter-group">
          <label>{t('tbl.domainSearch')}</label>
          <input className="filter-input" placeholder={t('tbl.domainPh')} value={filterDomain}
            onChange={(e) => { setFilterDomain(e.target.value); setPage(1) }} />
        </div>
        <div className="filter-group">
          <label>{t('tbl.issuerSearch')}</label>
          <input className="filter-input" placeholder={t('tbl.issuerPh')} value={filterIssuer}
            onChange={(e) => { setFilterIssuer(e.target.value); setPage(1) }} />
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
        <button className="btn btn-secondary" style={{ marginTop: 24 }} onClick={reset}>
          {t('tbl.reset')}
        </button>
      </div>

      <table className="certificates-table">
        <thead>
          <tr>
            <th>{t('tbl.colDomain')}</th>
            <th>{t('tbl.colIssuer')}</th>
            <th>{t('tbl.colSubject')}</th>
            <th>{t('tbl.colExpiry')}</th>
            <th>{t('tbl.colDays')}</th>
            <th>
              <div className="cf-wrap" ref={statusDropRef}>
                <button
                  className={`cf-th-btn${filterStatus ? ' cf-active' : ''}`}
                  onClick={() => setStatusDropOpen((v) => !v)}
                  title={t('tbl.filterTitle')}
                >
                  {t('tbl.colStatus')}
                  {filterStatus && (
                    <span className="cf-active-label"> · {activeStatusLabel}</span>
                  )}
                  <span className="cf-arrow">{statusDropOpen ? '▲' : '▼'}</span>
                </button>

                {statusDropOpen && (
                  <div className="cf-menu">
                    <div className="cf-menu-title">{t('tbl.filterTitle')}</div>
                    {STATUS_OPTIONS.map((opt) => (
                      <div
                        key={opt.value}
                        className={`cf-opt${opt.cls ? ' ' + opt.cls : ''}${filterStatus === opt.value ? ' cf-opt-selected' : ''}`}
                        onClick={() => selectStatus(opt.value)}
                      >
                        <span className="cf-opt-check">{filterStatus === opt.value ? '✔' : ''}</span>
                        {opt.icon && <span className="cf-opt-icon">{opt.icon}</span>}
                        <span>{t(opt.labelKey)}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </th>
            <th>{t('tbl.colChecked')}</th>
          </tr>
        </thead>
        <tbody>
          {loading ? (
            <tr><td colSpan={7} className="loading">{t('tbl.loading')}</td></tr>
          ) : certs.length === 0 ? (
            <tr><td colSpan={7} className="loading">{t('tbl.noCerts')}</td></tr>
          ) : certs.map((cert) => (
            <TableRow key={cert.domain} cert={cert} onClick={onRowClick} />
          ))}
        </tbody>
      </table>

      <div className="pagination-controls">
        <div className="pagination-info">
          {t('tbl.total', p.total, p.current_page, p.total_pages)}
        </div>
        <div className="pagination-buttons">
          <button className="btn btn-secondary" disabled={p.current_page <= 1}
            onClick={() => setPage(p => p - 1)}>{t('tbl.prev')}</button>
          <span className="page-numbers">
            {pageRange.map((n) => (
              <button key={n} className={`page-btn${n === p.current_page ? ' active' : ''}`}
                disabled={n === p.current_page} onClick={() => setPage(n)}>{n}</button>
            ))}
          </span>
          <button className="btn btn-secondary" disabled={p.current_page >= p.total_pages}
            onClick={() => setPage(p => p + 1)}>{t('tbl.next')}</button>
        </div>
      </div>
    </>
  )
}

function TableRow({ cert, onClick }) {
  const t = useT()
  const days = cert.days_remaining
  const isCritical = cert.status !== 'error' && days !== null && days !== undefined && days >= 0 && days <= 30
  let statusClass = cert.status === 'error' ? 'status-error' : (cert.warning ? 'status-warning' : 'status-valid')
  let statusText  = cert.status === 'error' ? t('tbl.statusError') : (cert.warning ? t('tbl.statusWarning') : t('tbl.statusValid'))
  if (isCritical) { statusClass = 'status-critical'; statusText = t('tbl.statusCritical') }

  return (
    <tr data-domain={cert.domain} onClick={() => onClick(cert.domain)} style={{ cursor: 'pointer' }}>
      <td><strong>{cert.domain}</strong></td>
      <td>{cert.issuer_cn || cert.issuer || 'N/A'}</td>
      <td>{cert.subject || 'N/A'}</td>
      <td>{formatDate(cert.not_after)}</td>
      <td><strong>{days ?? 'N/A'}</strong></td>
      <td><span className={`table-status ${statusClass}`}></span>{statusText}</td>
      <td>{formatDate(cert.checked_at)}</td>
    </tr>
  )
}

function buildPageRange(current, total, max = 5) {
  let start = Math.max(1, current - Math.floor(max / 2))
  let end   = Math.min(total, start + max - 1)
  if (end - start + 1 < max) start = Math.max(1, end - max + 1)
  return Array.from({ length: end - start + 1 }, (_, i) => start + i)
}
