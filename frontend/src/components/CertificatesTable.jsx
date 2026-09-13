import { useState, useEffect, useCallback, useRef } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import TeamBadge from './ui/TeamBadge.jsx'
import { readPageSize, writePageSize } from '../hooks/usePagination.js'
import { isInsecure, securityTitle } from '../utils/certSecurity.js'

/**
 * Sütun seçici + kayıtlı görünüm (2026-09-12, zenginleştirme #10): hangi sütunların görüneceği ve
 * sıralama/sayfa boyutu/durum süzgeci localStorage'da ("benim görünümüm"). domain ve durum sabit.
 * Yeni isteğe bağlı sütunlar: takım, anahtar (tür + bit), imza algoritması — varsayılan kapalı.
 */
export const TABLE_COLUMNS = [
  { key: 'domain',    labelKey: 'tbl.colDomain',  fixed: true },
  { key: 'issuer',    labelKey: 'tbl.colIssuer',  def: true },
  { key: 'subject',   labelKey: 'tbl.colSubject', def: true },
  { key: 'team',      labelKey: 'tbl.colTeam',    def: false },
  { key: 'expiry',    labelKey: 'tbl.colExpiry',  def: true },
  { key: 'days',      labelKey: 'tbl.colDays',    def: true },
  { key: 'status',    labelKey: 'tbl.colStatus',  fixed: true },
  { key: 'key',       labelKey: 'tbl.colKey',     def: false },
  { key: 'signature', labelKey: 'tbl.colSig',     def: false },
  { key: 'checked',   labelKey: 'tbl.colChecked', def: true },
]
const VIEW_KEY = 'certtable-view'
function readView() {
  try { const v = JSON.parse(localStorage.getItem(VIEW_KEY) || 'null'); return v && typeof v === 'object' ? v : null } catch { return null }
}
function writeView(patch) {
  try { localStorage.setItem(VIEW_KEY, JSON.stringify({ ...(readView() || {}), ...patch })) } catch { /* yoksay */ }
}
function defaultCols() { return TABLE_COLUMNS.filter((c) => c.fixed || c.def).map((c) => c.key) }

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
  const [sortBy, setSortBy]         = useState(() => readView()?.sortBy || 'priority|asc')
  const [cols, setCols]             = useState(() => { const v = readView()?.cols; return Array.isArray(v) && v.length ? v : defaultCols() })
  const [colsOpen, setColsOpen]     = useState(false)
  const show = (k) => cols.includes(k)
  const toggleCol = (k) => setCols((c) => { const next = c.includes(k) ? c.filter((x) => x !== k) : [...c, k]; writeView({ cols: next }); return next })
  const [filterDomain, setFilterDomain] = useState('')
  const [filterIssuer, setFilterIssuer] = useState('')
  // Her tuşta istek atma — 300 ms sessizlikten sonra tek istek (AlertHistory/UserManager deseni).
  const [domainTerm, setDomainTerm] = useState('')
  const [issuerTerm, setIssuerTerm] = useState('')
  useEffect(() => { const id = setTimeout(() => setDomainTerm(filterDomain), 300); return () => clearTimeout(id) }, [filterDomain])
  useEffect(() => { const id = setTimeout(() => setIssuerTerm(filterIssuer), 300); return () => clearTimeout(id) }, [filterIssuer])
  // Fetch yarışı: "ba" yanıtı "ban" yanıtından SONRA gelirse tabloyu ve sayfa sayısını ezerdi.
  const loadSeq = useRef(0)
  const [filterStatus, setFilterStatus] = useState(() => readView()?.filterStatus || '')
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
    const seq = ++loadSeq.current
    setLoading(true)
    try {
      const [sb, sd] = sortBy.split('|')
      const data = await api.getCertificatesPaginated({
        page, per_page: perPage, sort_by: sb, sort_dir: sd,
        filter_domain: domainTerm, filter_issuer: issuerTerm, filter_status: filterStatus,
      })
      if (seq !== loadSeq.current) return   // bayat yanıt
      if (data?.success) {
        setCerts(data.data)
        setPagination(data.pagination)
      }
    } finally {
      if (seq === loadSeq.current) setLoading(false)
    }
  }, [page, perPage, sortBy, domainTerm, issuerTerm, filterStatus])

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
            onChange={v => { setSortBy(v); setPage(1); writeView({ sortBy: v }) }}
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
        <div className="colpick" style={{ marginTop: 24 }}>
          <button type="button" className="btn btn-secondary" onClick={() => setColsOpen((o) => !o)} aria-expanded={colsOpen} aria-haspopup="true">
            {t('tbl.columns')} ({cols.length}/{TABLE_COLUMNS.length})
          </button>
          {colsOpen && (
            <div className="colpick-menu" role="group" aria-label={t('tbl.columns')}>
              {TABLE_COLUMNS.map((c) => (
                <label key={c.key} className={`colpick-item${c.fixed ? ' is-fixed' : ''}`}>
                  <input type="checkbox" checked={show(c.key)} disabled={c.fixed} onChange={() => toggleCol(c.key)} /> {t(c.labelKey)}
                </label>
              ))}
              <button type="button" className="btn btn-sm btn-secondary" onClick={() => { const d = defaultCols(); setCols(d); writeView({ cols: d }) }}>{t('tbl.columnsReset')}</button>
              <span className="colpick-note">{t('tbl.viewSaved')}</span>
            </div>
          )}
        </div>
      </div>

      <div className="table-scroll">
      <table className="certificates-table">
        <thead>
          <tr>
            <th>{t('tbl.colDomain')}</th>
            {show('issuer') && <th>{t('tbl.colIssuer')}</th>}
            {show('subject') && <th>{t('tbl.colSubject')}</th>}
            {show('team') && <th>{t('tbl.colTeam')}</th>}
            {show('expiry') && <th>{t('tbl.colExpiry')}</th>}
            {show('days') && <th>{t('tbl.colDays')}</th>}
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
            {show('key') && <th>{t('tbl.colKey')}</th>}
            {show('signature') && <th>{t('tbl.colSig')}</th>}
            {show('checked') && <th>{t('tbl.colChecked')}</th>}
          </tr>
        </thead>
        <tbody>
          {loading ? (
            <tr><td colSpan={cols.length} className="loading">{t('tbl.loading')}</td></tr>
          ) : certs.length === 0 ? (
            <tr><td colSpan={cols.length} className="loading">{t('tbl.noCerts')}</td></tr>
          ) : certs.map((cert) => (
            <TableRow key={cert.domain} cert={cert} onClick={onRowClick} show={show} />
          ))}
        </tbody>
      </table>
      </div>

      <PaginationBar
        page={p.current_page} totalPages={p.total_pages} totalItems={p.total}
        rangeStart={p.total === 0 ? 0 : (p.current_page - 1) * perPage + 1}
        rangeEnd={Math.min(p.current_page * perPage, p.total)}
        pageSize={perPage}
        onPageChange={setPage}
        onPageSizeChange={n => { setPerPage(n); setPage(1); writePageSize('certificates-table', n); writeView({ perPage: n }) }}
      />
    </>
  )
}

function TableRow({ cert, onClick, show = () => true }) {
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
      {show('issuer') && <td>{cert.issuer_cn || cert.issuer || 'N/A'}</td>}
      {show('subject') && <td>{cert.subject || 'N/A'}</td>}
      {show('team') && <td>{cert.team_name ? <TeamBadge teamId={cert.team_id} teamName={cert.team_name} /> : '—'}</td>}
      {show('expiry') && <td>{formatDate(cert.not_after)}</td>}
      {show('days') && <td><strong>{days ?? 'N/A'}</strong></td>}
      <td>
        <span className={`table-status ${statusClass}`}></span>{statusText}
        {isInsecure(cert) && (
          <span className="cc-pill cc-pill-error" style={{ marginLeft: 6 }} title={securityTitle(cert, t)}>
            {t('cert.sec.insecure')}
          </span>
        )}
      </td>
      {show('key') && <td className="wa-mono">{cert.public_key_algorithm ? `${cert.public_key_algorithm}${cert.public_key_size ? ' ' + cert.public_key_size : ''}` : '—'}</td>}
      {show('signature') && <td className="wa-mono">{cert.signature_algorithm || '—'}</td>}
      {show('checked') && <td>{formatDate(cert.checked_at)}</td>}
    </tr>
  )
}

