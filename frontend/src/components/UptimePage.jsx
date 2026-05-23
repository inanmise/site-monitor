import { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { RefreshCw, X } from 'lucide-react'

const REFRESH_INTERVAL = 60
const PAGE_SIZE = 12

export default function UptimePage() {
  const t = useT()
  const [items, setItems] = useState([])
  const [loading, setLoading] = useState(true)
  const [filterStatus, setFilterStatus] = useState('all')
  const [sortKey, setSortKey]           = useState('default')
  const [search, setSearch]             = useState('')
  const [page, setPage]                 = useState(1)
  const [selected, setSelected] = useState(null)
  const [history, setHistory] = useState(null)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [secondsSince, setSecondsSince] = useState(0)
  const lastFetched = useRef(null)
  const countdownRef = useRef(null)

  const fetchOverview = useCallback(async () => {
    const res = await api.monitoring.getUptimeOverview()
    if (res?.success) {
      setItems(res.data)
      lastFetched.current = Date.now()
      setSecondsSince(0)
    }
    setLoading(false)
  }, [])

  useEffect(() => {
    fetchOverview()
    const interval = setInterval(fetchOverview, REFRESH_INTERVAL * 1000)
    return () => clearInterval(interval)
  }, [fetchOverview])

  useEffect(() => {
    countdownRef.current = setInterval(() => {
      setSecondsSince(s => s + 1)
    }, 1000)
    return () => clearInterval(countdownRef.current)
  }, [])

  async function openModal(item) {
    setSelected(item)
    setHistory(null)
    setHistoryLoading(true)
    const res = await api.monitoring.getUptimeHistory(item.domain, 24)
    if (res?.success) setHistory(res.data)
    setHistoryLoading(false)
  }

  function closeModal() {
    setSelected(null)
    setHistory(null)
  }

  const STATUS_ORDER = { down: 0, unknown: 1, up: 2 }
  const displayItems = useMemo(() => {
    let list = [...items]
    if (filterStatus === 'down') list = list.filter(x => x.status !== 'up')
    if (filterStatus === 'up')   list = list.filter(x => x.status === 'up')
    if (search.trim()) {
      const q = search.trim().toLowerCase()
      list = list.filter(x => x.domain.toLowerCase().includes(q))
    }
    list.sort((a, b) => {
      if (sortKey === 'default') {
        const sd = (STATUS_ORDER[a.status] ?? 1) - (STATUS_ORDER[b.status] ?? 1)
        if (sd !== 0) return sd
        return (a.ssl_valid_days ?? -9999) - (b.ssl_valid_days ?? -9999)
      }
      if (sortKey === 'ssl-asc')        return (a.ssl_valid_days ?? -9999) - (b.ssl_valid_days ?? -9999)
      if (sortKey === 'domain')         return a.domain.localeCompare(b.domain)
      if (sortKey === 'uptime-asc')     return (a.uptime_7d ?? 100) - (b.uptime_7d ?? 100)
      if (sortKey === 'incidents-desc') return (b.incidents_30d ?? 0) - (a.incidents_30d ?? 0)
      return 0
    })
    return list
  }, [items, filterStatus, sortKey, search])

  useEffect(() => { setPage(1) }, [filterStatus, sortKey, search])

  const totalPages = Math.max(1, Math.ceil(displayItems.length / PAGE_SIZE))
  const safePage   = Math.min(page, totalPages)
  const pagedItems = displayItems.slice((safePage - 1) * PAGE_SIZE, safePage * PAGE_SIZE)

  function pageNumbers(total, current) {
    if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1)
    const pages = new Set([1, total, current, current - 1, current + 1])
    return [...pages].filter(p => p >= 1 && p <= total).sort((a, b) => a - b)
      .reduce((acc, p, i, arr) => {
        if (i > 0 && p - arr[i - 1] > 1) acc.push('…')
        acc.push(p)
        return acc
      }, [])
  }

  function statusColor(status) {
    if (status === 'up')   return '#22c55e'
    if (status === 'down') return '#ef4444'
    return '#94a3b8'
  }

  function statusLabel(status) {
    if (status === 'up')   return t('uptime.statusUp')
    if (status === 'down') return t('uptime.statusDown')
    return t('uptime.statusUnknown')
  }

  function sslLabel(item) {
    if (item.ssl_valid_days == null) return t('uptime.sslUnknown')
    if (item.ssl_valid_days < 0)     return t('uptime.sslExpired')
    return t('uptime.sslDays').replace('{0}', item.ssl_valid_days)
  }

  function sslColor(item) {
    if (item.ssl_valid_days == null) return 'var(--text-muted)'
    if (item.ssl_valid_days < 0)     return '#ef4444'
    if (item.ssl_valid_days <= 7)    return '#ef4444'
    if (item.ssl_valid_days <= 14)   return '#f97316'
    if (item.ssl_valid_days <= 30)   return '#f59e0b'
    return '#22c55e'
  }

  function cardSslClass(item) {
    if (item.status !== 'up') return `upt-card--${item.status}`
    const d = item.ssl_valid_days
    if (d == null || d > 30) return 'upt-card--up'
    if (d < 0 || d <= 7)     return 'upt-card--ssl-critical'
    if (d <= 14)              return 'upt-card--ssl-high'
    return 'upt-card--ssl-warning'
  }

  return (
    <div className="upt-page">
      <div className="upt-header">
        <div>
          <h2 className="upt-title">{t('uptime.title')}</h2>
          <p className="upt-subtitle">{t('uptime.subtitle')}</p>
        </div>
        <div className="upt-header-right">
          <span className="upt-last-check">
            {t('uptime.autoRefresh').replace('{0}', Math.max(0, REFRESH_INTERVAL - secondsSince))}
          </span>
          <button className="btn btn-sm upt-refresh-btn" onClick={fetchOverview}>
            <RefreshCw size={14} />
            {t('uptime.refresh')}
          </button>
        </div>
      </div>

      {!loading && items.length > 0 && (
        <div className="upt-toolbar">
          <div className="upt-toolbar-left">
            <div className="upt-filter-pills">
              {['all', 'down', 'up'].map(f => (
                <button key={f}
                  className={`upt-filter-pill${filterStatus === f ? ' upt-filter-pill--active' : ''}`}
                  onClick={() => setFilterStatus(f)}>
                  {t(`uptime.filter${f.charAt(0).toUpperCase() + f.slice(1)}`)}
                </button>
              ))}
            </div>
            <select className="upt-sort-select" value={sortKey} onChange={e => setSortKey(e.target.value)}>
              <option value="default">{t('uptime.sortDefault')}</option>
              <option value="ssl-asc">{t('uptime.sortSslAsc')}</option>
              <option value="domain">{t('uptime.sortDomain')}</option>
              <option value="uptime-asc">{t('uptime.sortUptimeAsc')}</option>
              <option value="incidents-desc">{t('uptime.sortIncidentsDesc')}</option>
            </select>
          </div>
          <input className="upt-search" type="text"
            placeholder={t('uptime.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? (
        <div className="loading">{t('uptime.checking')}</div>
      ) : items.length === 0 ? (
        <div className="loading">{t('uptime.noData')}</div>
      ) : (
        <div className="upt-grid">
          {pagedItems.map(item => (
            <div
              key={item.domain}
              className={`upt-card ${cardSslClass(item)}`}
              onClick={() => openModal(item)}
            >
              <div className="upt-card-top">
                <div className={`upt-badge upt-badge--${item.status}`}>
                  <span className="upt-badge-dot" />
                  {statusLabel(item.status)}
                </div>
                <span className="upt-port-tag">:{item.port}</span>
              </div>

              <div className="upt-card-domain">{item.domain}</div>

              <div className="upt-card-divider" />

              <div className="upt-card-metrics">
                <div className="upt-metric">
                  <span className="upt-metric-val" style={{ color: sslColor(item) }}>{sslLabel(item)}</span>
                  <span className="upt-metric-lbl">SSL</span>
                </div>
                {item.uptime_7d != null && (
                  <div className="upt-metric">
                    <span className="upt-metric-val">{item.uptime_7d}%</span>
                    <span className="upt-metric-lbl">{t('uptime.uptime7d')}</span>
                  </div>
                )}
                {item.uptime_30d != null && (
                  <div className="upt-metric">
                    <span className="upt-metric-val">{item.uptime_30d}%</span>
                    <span className="upt-metric-lbl">{t('uptime.uptime30d')}</span>
                  </div>
                )}
                {item.incidents_30d > 0 && (
                  <div className="upt-metric">
                    <span className="upt-metric-val upt-metric-incident">{item.incidents_30d}</span>
                    <span className="upt-metric-lbl">{t('uptime.incidents').replace('{0}', '').trim()}</span>
                  </div>
                )}
              </div>

              {(item.uptime_checked_at || item.ssl_checked_at) && (
                <div className="upt-card-foot">{formatDate(item.uptime_checked_at || item.ssl_checked_at)}</div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* ── Pagination ── */}
      {!loading && displayItems.length > PAGE_SIZE && (
        <div className="upt-pagination">
          <span className="upt-page-info">
            {t('uptime.pageInfo')
              .replace('{0}', (safePage - 1) * PAGE_SIZE + 1)
              .replace('{1}', Math.min(safePage * PAGE_SIZE, displayItems.length))
              .replace('{2}', displayItems.length)}
          </span>
          <div className="upt-page-btns">
            <button className="upt-page-btn" disabled={safePage === 1} onClick={() => setPage(p => p - 1)}>
              {t('uptime.pagePrev')}
            </button>
            {pageNumbers(totalPages, safePage).map((p, i) =>
              p === '…'
                ? <span key={`ellipsis-${i}`} className="upt-page-ellipsis">…</span>
                : <button
                    key={p}
                    className={`upt-page-btn${safePage === p ? ' upt-page-btn--active' : ''}`}
                    onClick={() => setPage(p)}
                  >{p}</button>
            )}
            <button className="upt-page-btn" disabled={safePage === totalPages} onClick={() => setPage(p => p + 1)}>
              {t('uptime.pageNext')}
            </button>
          </div>
        </div>
      )}

      {/* ── Detail Modal (portal → document.body, bypasses overflow stacking context) ── */}
      {selected && createPortal(
        <div className="upt-modal-overlay" onClick={closeModal}>
          <div
            className={`upt-modal upt-modal--${selected.status}`}
            onClick={e => e.stopPropagation()}
          >
            {/* Header */}
            <div className="upt-modal-header">
              <div className="upt-modal-header-left">
                <div className={`upt-badge upt-badge--${selected.status}`}>
                  <span className="upt-badge-dot" />
                  {statusLabel(selected.status)}
                </div>
                <span className="upt-modal-domain">{selected.domain}</span>
                <span className="upt-port-tag">:{selected.port}</span>
              </div>
              <button className="upt-modal-close" onClick={closeModal}>
                <X size={18} />
              </button>
            </div>

            <div className="upt-modal-divider" />

            {/* Summary metrics */}
            <div className="upt-modal-summary">
              <div className="upt-modal-metric">
                <span className="upt-modal-metric-val" style={{ color: sslColor(selected) }}>{sslLabel(selected)}</span>
                <span className="upt-modal-metric-lbl">SSL</span>
              </div>
              {selected.uptime_7d != null && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val">{selected.uptime_7d}%</span>
                  <span className="upt-modal-metric-lbl">{t('uptime.uptime7d')}</span>
                </div>
              )}
              {selected.uptime_30d != null && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val">{selected.uptime_30d}%</span>
                  <span className="upt-modal-metric-lbl">{t('uptime.uptime30d')}</span>
                </div>
              )}
              {selected.incidents_30d > 0 && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val upt-metric-incident">{selected.incidents_30d}</span>
                  <span className="upt-modal-metric-lbl">{t('uptime.incidents').replace('{0}', '').trim()}</span>
                </div>
              )}
              {selected.response_ms != null && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val">{selected.response_ms}ms</span>
                  <span className="upt-modal-metric-lbl">{t('uptime.responseMs')}</span>
                </div>
              )}
              {selected.uptime_checked_at && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val upt-modal-metric-time">{formatDate(selected.uptime_checked_at)}</span>
                  <span className="upt-modal-metric-lbl">{t('uptime.lastHttpCheck')}</span>
                </div>
              )}
              {selected.ssl_checked_at && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val upt-modal-metric-time">{formatDate(selected.ssl_checked_at)}</span>
                  <span className="upt-modal-metric-lbl">{t('uptime.lastSslCheck')}</span>
                </div>
              )}
            </div>

            <div className="upt-modal-divider" />

            {/* Bar chart + response times */}
            {historyLoading ? (
              <div className="upt-modal-loading">{t('uptime.checking')}</div>
            ) : history ? (
              <>
                <div className="upt-modal-section-title">{t('uptime.barTitle')}</div>
                <div className="upt-bars upt-bars--modal">
                  {history.bars.map((bar, i) => (
                    <div
                      key={i}
                      className="upt-bar"
                      title={`${bar.hour?.substring(11, 16)} — ${bar.status}`}
                      style={{
                        background: bar.status === 'up'   ? '#22c55e'
                                  : bar.status === 'down' ? '#ef4444'
                                  : 'var(--border)',
                      }}
                    />
                  ))}
                </div>
                <div className="upt-bar-labels" style={{ marginBottom: '20px' }}>
                  <span>{history.bars[0]?.hour?.substring(11, 16)}</span>
                  <span>{history.bars[history.bars.length - 1]?.hour?.substring(11, 16)}</span>
                </div>

                {history.response_times?.length > 0 && (
                  <>
                    <div className="upt-modal-divider" />
                    <div className="upt-modal-section-title">{t('uptime.lastCheck').split(':')[0]}</div>
                    <div className="upt-rt-list">
                      {history.response_times.slice(-20).reverse().map((pt, i) => (
                        <div key={i} className="upt-rt-row">
                          <span className="upt-rt-time">{pt.ts?.substring(0, 19).replace('T', ' ')}</span>
                          <span className={`upt-rt-status ${pt.status === 'error' ? 'upt-rt-down' : 'upt-rt-up'}`}>
                            {pt.status === 'error' ? t('uptime.statusDown') : t('uptime.statusUp')}
                          </span>
                        </div>
                      ))}
                    </div>
                  </>
                )}
              </>
            ) : null}
          </div>
        </div>,
        document.body
      )}
    </div>
  )
}
