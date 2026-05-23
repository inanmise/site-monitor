import { useState, useEffect, useRef, useCallback } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { RefreshCw, X } from 'lucide-react'

const REFRESH_INTERVAL = 60

export default function UptimePage() {
  const t = useT()
  const [items, setItems] = useState([])
  const [loading, setLoading] = useState(true)
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
    if (item.ssl_valid_days <= 14)   return '#ef4444'
    if (item.ssl_valid_days <= 30)   return '#f59e0b'
    return '#22c55e'
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

      {loading ? (
        <div className="loading">{t('uptime.checking')}</div>
      ) : items.length === 0 ? (
        <div className="loading">{t('uptime.noData')}</div>
      ) : (
        <div className="upt-grid">
          {items.map(item => (
            <div
              key={item.domain}
              className={`upt-card upt-card--${item.status}`}
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

              {item.checked_at && (
                <div className="upt-card-foot">{formatDate(item.checked_at)}</div>
              )}
            </div>
          ))}
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
              {selected.checked_at && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val upt-modal-metric-time">{formatDate(selected.checked_at)}</span>
                  <span className="upt-modal-metric-lbl">{t('uptime.lastCheck').replace(': {0}s ago', '').replace(': {0}s önce', '')}</span>
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
