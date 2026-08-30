import { useState, useEffect, useCallback, useRef } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import { readPageSize, writePageSize } from '../hooks/usePagination.js'
import { isInsecure, securityTitle } from '../utils/certSecurity.js'

export default function CertificatesTable({ onRowClick }) {
  const t = useT()

  const STATUS_OPTIONS = [
    { value: '',          labelKey: 'tbl.filterAll',      icon: null  },
    { value: 'valid',     labelKey: 'tbl.filterValid',    icon: '✓',  cls: 'cf-opt-valid' },
    { value: 'critical',  labelKey: 'tbl.filterCritical', icon: '🔴', cls: 'cf-opt-crit'  },
    { value: 'high',      labelKey: 'tbl.filterHigh',     icon: '🟠', cls: 'cf-opt-high'  },
    { value: 'warning',   labelKey: 'tbl.filterWarning',  icon: '⚠',  cls: 'cf-opt-warn'  },
    { value: 'error',     labelKey: 'tbl.filterError',    icon: '✗',  cls: 'cf-opt-err'   },
    { value: 'expiring7', labelKey: 'tbl.filterExpiring7',icon: '⏱',  cls: 'cf-opt-crit'  },
  ]

  const [certs, setCerts]           = useState([])
  const [pagination, setPagination] = useState({ current_page: 1, total: 0, total_pages: 1 })
  const [page, setPage]             = useState(1)
  const [perPage, setPerPage]       = useState(() => readPageSize('certificates-table'))
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
    setSortBy('priority|asc'); setPerPage(readPageSize('certificates-table')); setPage(1)
    setStatusDropOpen(false)
  }

  function selectStatus(val) {
    setFilterStatus(val)
    setStatusDropOpen(false)
    setPage(1)
  }

  const activeStatusLabel = t(STATUS_OPTIONS.find(o => o.value === filterStatus)?.labelKey ?? 'tbl.filterAll')
  const p         = pagination

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

      <PaginationBar
        page={p.current_page} totalPages={p.total_pages} totalItems={p.total}
        rangeStart={p.total === 0 ? 0 : (p.current_page - 1) * perPage + 1}
        rangeEnd={Math.min(p.current_page * perPage, p.total)}
        pageSize={perPage}
        onPageChange={setPage}
        onPageSizeChange={n => { setPerPage(n); setPage(1); writePageSize('certificates-table', n) }}
      />
    </>
  )
}

function TableRow({ cert, onClick }) {
  const t = useT()
  const days = cert.days_remaining
  // Hüküm SUNUCUDAN gelir (CertificateService.computeAlertLevel: expired/critical/high/warning/valid)
  // ve eşikler ayarlanabilir. Tablo kendi sabit 30 gün merdivenini kullanıyordu: days=20 satırda
  // "Kritik", kartta "Yüksek" görünüyordu; daha kötüsü days<0 (SÜRESİ DOLMUŞ) sertifika
  // days>=0 koşuluna takılmadığı için "Uyarı" görünüyor ve tabloda "Süresi doldu" hiç yoktu.
  const level = cert.alert_level
    ?? (cert.status === 'error' ? 'error'
      : days == null ? 'valid'
      : days < 0 ? 'expired'
      : cert.warning ? 'warning' : 'valid')
  const LEVEL_CLASS = { error: 'status-error', expired: 'status-critical', critical: 'status-critical',
    high: 'status-warning', warning: 'status-warning', valid: 'status-valid' }
  const LEVEL_TEXT = { error: 'tbl.statusError', expired: 'tbl.statusExpired', critical: 'tbl.statusCritical',
    high: 'tbl.statusHigh', warning: 'tbl.statusWarning', valid: 'tbl.statusValid' }
  const statusClass = LEVEL_CLASS[level] ?? 'status-valid'
  const statusText  = t(LEVEL_TEXT[level] ?? 'tbl.statusValid')

  return (
    <tr data-domain={cert.domain} onClick={() => onClick(cert.domain)} style={{ cursor: 'pointer' }}>
      <td><strong>{cert.domain}</strong></td>
      <td>{cert.issuer_cn || cert.issuer || 'N/A'}</td>
      <td>{cert.subject || 'N/A'}</td>
      <td>{formatDate(cert.not_after)}</td>
      <td><strong>{days ?? 'N/A'}</strong></td>
      <td>
        <span className={`table-status ${statusClass}`}></span>{statusText}
        {isInsecure(cert) && (
          <span className="cc-pill cc-pill-error" style={{ marginLeft: 6 }} title={securityTitle(cert, t)}>
            {t('cert.sec.insecure')}
          </span>
        )}
      </td>
      <td>{formatDate(cert.checked_at)}</td>
    </tr>
  )
}

