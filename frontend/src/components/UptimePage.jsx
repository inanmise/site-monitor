import { useState, useEffect, useRef, useCallback } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { RefreshCw } from 'lucide-react'

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

  async function selectDomain(item) {
    if (selected?.domain === item.domain) {
      setSelected(null)
      setHistory(null)
      return
    }
    setSelected(item)
    setHistoryLoading(true)
    const res = await api.monitoring.getUptimeHistory(item.domain, 24)
    if (res?.success) setHistory(res.data)
    setHistoryLoading(false)
  }

  function statusColor(status) {
    if (status === 'up')      return '#22c55e'
    if (status === 'down')    return '#ef4444'
    return '#94a3b8'
  }

  function statusLabel(status) {
    if (status === 'up')      return t('uptime.statusUp')
    if (status === 'down')    return t('uptime.statusDown')
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
    if (item.ssl_valid_days <= 30)   return '#f59e0b'
    return 'var(--text-muted)'
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
        <>
          <div className="upt-grid">
            {items.map(item => (
              <div
                key={item.domain}
                className={`upt-card${selected?.domain === item.domain ? ' upt-card-selected' : ''}`}
                onClick={() => selectDomain(item)}
              >
                <div className="upt-card-header">
                  <span className="upt-status-dot" style={{ background: statusColor(item.status) }} />
                  <span className="upt-domain">{item.domain}</span>
                  <span className="upt-port">:{item.port}</span>
                </div>
                <div className="upt-card-status" style={{ color: statusColor(item.status) }}>
                  {statusLabel(item.status)}
                </div>
                <div className="upt-card-ssl" style={{ color: sslColor(item) }}>
                  {sslLabel(item)}
                </div>
                <div className="upt-card-stats">
                  {item.uptime_7d != null && (
                    <span className="upt-stat">{item.uptime_7d}% <small>{t('uptime.uptime7d')}</small></span>
                  )}
                  {item.uptime_30d != null && (
                    <span className="upt-stat">{item.uptime_30d}% <small>{t('uptime.uptime30d')}</small></span>
                  )}
                </div>
                {item.checked_at && (
                  <div className="upt-card-time">{formatDate(item.checked_at)}</div>
                )}
              </div>
            ))}
          </div>

          {selected && (
            <div className="upt-detail">
              <div className="upt-detail-title">
                <span className="upt-status-dot" style={{ background: statusColor(selected.status) }} />
                {selected.domain}
                {historyLoading && <span className="upt-detail-loading"> ...</span>}
              </div>

              {history && (
                <>
                  <div className="upt-bar-section">
                    <div className="upt-bar-label">{t('uptime.barTitle')}</div>
                    <div className="upt-bars">
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
                    <div className="upt-bar-labels">
                      <span>{history.bars[0]?.hour?.substring(11, 16)}</span>
                      <span>{history.bars[history.bars.length - 1]?.hour?.substring(11, 16)}</span>
                    </div>
                  </div>

                  {history.response_times?.length > 0 && (
                    <div className="upt-rt-section">
                      <div className="upt-rt-list">
                        {history.response_times.slice(-10).map((pt, i) => (
                          <div key={i} className="upt-rt-row">
                            <span className="upt-rt-time">{pt.ts?.substring(11, 19)}</span>
                            <span className={`upt-rt-status ${pt.status === 'error' ? 'upt-rt-down' : 'upt-rt-up'}`}>
                              {pt.status === 'error' ? t('uptime.statusDown') : t('uptime.statusUp')}
                            </span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </>
              )}
            </div>
          )}
        </>
      )}
    </div>
  )
}
