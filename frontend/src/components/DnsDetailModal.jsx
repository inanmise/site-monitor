import { useState, useEffect, Fragment } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { X, Activity, Clock, Server, FileText, Globe } from 'lucide-react'
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from 'recharts'

function ChartTooltip({ active, payload, t }) {
  if (!active || !payload || !payload.length) return null
  const d = payload[0].payload
  return (
    <div className="dns-chart-tooltip">
      <div className="dns-chart-tt-time">{d.ts}</div>
      <div className="dns-chart-tt-row">
        <span>{t('dns.responseMs')}:</span> <strong>{d.ms}ms</strong>
      </div>
      {d.ttl != null && (
        <div className="dns-chart-tt-row">
          <span>{t('dns.ttl')}:</span> <strong>{d.ttl}s</strong>
        </div>
      )}
      {d.changed && <div className="dns-chart-tt-badge changed">{t('dns.changed')}</div>}
      {d.rotated && <div className="dns-chart-tt-badge rotated">{t('dns.rotated')}</div>}
    </div>
  )
}

const RECORD_TYPES = ['A', 'AAAA', 'CNAME', 'MX', 'TXT', 'NS']
const RANGE_OPTIONS = [
  { days: 1,  labelKey: 'dns.last1d'  },
  { days: 7,  labelKey: 'dns.last7d'  },
  { days: 10, labelKey: 'dns.last10d' },
  { days: 30, labelKey: 'dns.last30d' },
]

function computeDiff(prev, next) {
  const prevLines = (prev || '').split('\n').filter(Boolean)
  const nextLines = (next || '').split('\n').filter(Boolean)
  const prevSet = new Set(prevLines)
  const nextSet = new Set(nextLines)
  return {
    removed: prevLines.filter(x => !nextSet.has(x)),
    added:   nextLines.filter(x => !prevSet.has(x)),
  }
}

export default function DnsDetailModal({ monitor, onClose }) {
  const t = useT()
  const [details, setDetails] = useState(null)
  const [history, setHistory] = useState([])
  const [loading, setLoading] = useState(true)
  const [activeTab, setActiveTab] = useState('A')
  const [rangeDays, setRangeDays] = useState(1)
  const [historyPage, setHistoryPage] = useState(0)
  const [historyPageSize, setHistoryPageSize] = useState(50)

  // Details — bir kez yüklenir, monitor değişene kadar tutulur
  useEffect(() => {
    if (!monitor) return
    setLoading(true)
    api.monitoring.getDnsDetails(monitor.id).then(d => {
      if (d?.success) setDetails(d.data)
      setLoading(false)
    })
  }, [monitor])

  // History — monitor veya rangeDays değişince yeniden yüklenir
  useEffect(() => {
    if (!monitor) return
    api.monitoring.getDnsHistory(monitor.id, rangeDays).then(h => {
      if (h?.success) setHistory(h.data || [])
    })
  }, [monitor, rangeDays])

  // Paging: aralık veya sayfa boyutu değişince başa dön
  useEffect(() => {
    setHistoryPage(0)
  }, [rangeDays, historyPageSize, monitor])

  const activeRangeOption = RANGE_OPTIONS.find(r => r.days === rangeDays) || RANGE_OPTIONS[0]
  const totalPages = Math.max(1, Math.ceil(history.length / historyPageSize))
  const safePage = Math.min(historyPage, totalPages - 1)
  const pageStart = safePage * historyPageSize
  const pageEnd = Math.min(pageStart + historyPageSize, history.length)
  const pagedHistory = history.slice(pageStart, pageEnd)

  if (!monitor) return null

  const rec = details?.records?.[activeTab]
  const soa = details?.soa
  const ns  = details?.authoritative_servers || []
  const live = details?.records?.[monitor.record_type]

  return createPortal(
    <div className="dns-modal-overlay" onClick={onClose}>
      <div className="dns-modal-box" onClick={e => e.stopPropagation()}>
        <div className="dns-modal-header">
          <div className="dns-modal-title">
            <Globe size={18} />
            <strong>{monitor.domain}</strong>
            <span className="dns-modal-subtitle">— {t('dns.detailTitle')}</span>
          </div>
          <button className="dns-modal-close" onClick={onClose} aria-label={t('dns.close')}>
            <X size={18} />
          </button>
        </div>

        {loading ? (
          <div className="dns-modal-loading">{t('dns.loadingDetails')}</div>
        ) : (
          <div className="dns-modal-body">
            {/* Metric Bar */}
            <div className="dns-metric-bar">
              <div className="dns-metric-item">
                <Clock size={14} />
                <span className="dns-metric-label">{t('dns.ttl')}</span>
                <span className="dns-metric-value">
                  {live?.ttl != null ? `${live.ttl}s` : '—'}
                </span>
              </div>
              <div className="dns-metric-item">
                <Activity size={14} />
                <span className="dns-metric-label">{t('dns.responseMs')}</span>
                <span className="dns-metric-value">
                  {live?.response_ms != null ? `${live.response_ms}ms` : '—'}
                </span>
              </div>
              <div className="dns-metric-item">
                <Server size={14} />
                <span className="dns-metric-label">{t('dns.recordType')}</span>
                <span className="dns-metric-value">{monitor.record_type}</span>
              </div>
              {live?.success ? (
                <span className="dns-status-ok">✓ SUCCESS</span>
              ) : (
                <span className="dns-status-err">✗ {live?.error || 'ERROR'}</span>
              )}
            </div>

            {/* Record type tabs */}
            <div className="dns-section">
              <h4 className="dns-section-title">{t('dns.recordTypes')}</h4>
              <div className="dns-record-tabs">
                {RECORD_TYPES.map(rt => {
                  const has = details?.records?.[rt]?.values?.length > 0
                  return (
                    <button
                      key={rt}
                      className={`dns-record-tab${activeTab === rt ? ' active' : ''}${!has ? ' empty' : ''}`}
                      onClick={() => setActiveTab(rt)}
                    >
                      {rt}
                      {has && <span className="dns-tab-count">{details.records[rt].values.length}</span>}
                    </button>
                  )
                })}
              </div>
              <div className="dns-record-list">
                {rec?.values?.length > 0 ? (
                  <>
                    <div className="dns-record-meta">
                      {rec.ttl != null && <span>TTL: {rec.ttl}s</span>}
                      {rec.response_ms != null && <span>· {rec.response_ms}ms</span>}
                    </div>
                    {rec.values.map((v, i) => (
                      <div key={i} className="dns-record-row">
                        <span className="dns-record-type-mini">{activeTab}</span>
                        <code className="dns-record-value">{v}</code>
                      </div>
                    ))}
                  </>
                ) : (
                  <div className="dns-record-empty">{t('dns.noRecords')}</div>
                )}
              </div>
            </div>

            {/* Authoritative Servers */}
            {ns.length > 0 && (
              <div className="dns-section">
                <h4 className="dns-section-title">
                  <Server size={14} /> {t('dns.authServers')}
                </h4>
                <ul className="dns-ns-list">
                  {ns.map((n, i) => (
                    <li key={i}><code>{n}</code></li>
                  ))}
                </ul>
              </div>
            )}

            {/* SOA */}
            {soa?.success && (
              <div className="dns-section">
                <h4 className="dns-section-title">
                  <FileText size={14} /> {t('dns.soaTitle')}
                </h4>
                <dl className="dns-soa-grid">
                  <dt>{t('dns.primaryNs')}</dt>     <dd><code>{soa.primary_ns}</code></dd>
                  <dt>{t('dns.adminEmail')}</dt>    <dd><code>{soa.admin_email}</code></dd>
                  <dt>{t('dns.serial')}</dt>        <dd>{soa.serial}</dd>
                  <dt>{t('dns.refresh')}</dt>       <dd>{soa.refresh}s</dd>
                  <dt>{t('dns.retry')}</dt>         <dd>{soa.retry}s</dd>
                  <dt>{t('dns.expire')}</dt>        <dd>{soa.expire}s</dd>
                  <dt>{t('dns.minimumTtl')}</dt>    <dd>{soa.minimum_ttl}s</dd>
                </dl>
              </div>
            )}

            {/* Response time trend chart */}
            {(() => {
              const chartData = history.slice().reverse().map(h => ({
                ts: new Date(h.checked_at || h.checkedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
                ms: h.response_ms,
                ttl: h.ttl,
                changed: h.changed,
                rotated: h.rotated,
              })).filter(p => p.ms != null)
              if (chartData.length < 2) return null
              return (
                <div className="dns-section">
                  <h4 className="dns-section-title">
                    <Activity size={14} /> {t('dns.responseTrend')}
                    <span className="dns-range-hint">· {t(activeRangeOption.labelKey)}</span>
                  </h4>
                  <div className="dns-chart-wrap">
                    <ResponsiveContainer width="100%" height={200}>
                      <LineChart data={chartData} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
                        <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
                        <XAxis
                          dataKey="ts"
                          tick={{ fontSize: 11, fill: 'var(--text-light)' }}
                          stroke="var(--border)"
                        />
                        <YAxis
                          tick={{ fontSize: 11, fill: 'var(--text-light)' }}
                          stroke="var(--border)"
                          width={48}
                          tickFormatter={(v) => `${v}ms`}
                        />
                        <Tooltip content={<ChartTooltip t={t} />} />
                        <Line
                          type="monotone"
                          dataKey="ms"
                          stroke="#2563eb"
                          strokeWidth={2}
                          dot={{ r: 3, fill: '#2563eb' }}
                          activeDot={{ r: 5 }}
                          isAnimationActive={false}
                        />
                      </LineChart>
                    </ResponsiveContainer>
                  </div>
                </div>
              )
            })()}

            {/* Recent history */}
            <div className="dns-section">
              <div className="dns-section-header-with-filter">
                <h4 className="dns-section-title">
                  <Clock size={14} /> {t('dns.recentChecks')}
                </h4>
                <div className="dns-range-filter">
                  {RANGE_OPTIONS.map(opt => (
                    <button
                      key={opt.days}
                      type="button"
                      className={`dns-range-btn${rangeDays === opt.days ? ' active' : ''}`}
                      onClick={() => setRangeDays(opt.days)}
                    >
                      {t(opt.labelKey)}
                    </button>
                  ))}
                </div>
              </div>
              {history.length === 0 ? (
                <div className="dns-history-empty">{t('dns.noHistoryInRange')}</div>
              ) : (
              <div className="dns-history-scroll">
                <table className="dns-history-table">
                  <thead>
                    <tr>
                      <th>{t('dns.lastCheck')}</th>
                      <th>{t('dns.currentValue')}</th>
                      <th>{t('dns.ttl')}</th>
                      <th>{t('dns.responseMs')}</th>
                      <th>{t('dns.status')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {pagedHistory.map((h, i) => {
                      const isChanged = h.changed
                      const isRotated = !isChanged && h.rotated
                      const prevVal = h.previous_value ?? h.previousValue
                      const showDiff = (isChanged || isRotated) && prevVal && prevVal !== h.value
                      const diff = showDiff ? computeDiff(prevVal, h.value) : null
                      return (
                        <Fragment key={i}>
                          <tr className={isChanged ? 'dns-history-changed' : (isRotated ? 'dns-history-rotated' : '')}>
                            <td className="dns-cell-time">{formatDate(h.checked_at || h.checkedAt)}</td>
                            <td className="dns-history-value" title={h.value || '—'}>
                              <code>{h.value || '—'}</code>
                            </td>
                            <td className="dns-cell-num">{h.ttl != null ? `${h.ttl}s` : '—'}</td>
                            <td className="dns-cell-num">{h.response_ms != null ? `${h.response_ms}ms` : '—'}</td>
                            <td>
                              {isChanged && <span className="dns-changed-badge">{t('dns.changed')}</span>}
                              {isRotated && <span className="dns-rotated-badge" title={t('dns.rotationTitle')}>{t('dns.rotated')}</span>}
                              {!isChanged && !isRotated && <span className="dns-nochange-badge">{t('dns.noChange')}</span>}
                            </td>
                          </tr>
                          {showDiff && diff && (
                            <tr className={isRotated ? 'dns-diff-row dns-diff-row-rotated' : 'dns-diff-row'}>
                              <td colSpan={5}>
                                <div className="dns-diff-grid">
                                  <div className="dns-diff-col">
                                    <div className="dns-diff-col-title">{t('dns.previousValue')}</div>
                                    <pre className="dns-diff-pre">{prevVal || '—'}</pre>
                                  </div>
                                  <div className="dns-diff-col">
                                    <div className="dns-diff-col-title">{t('dns.newValue')}</div>
                                    <pre className="dns-diff-pre">{h.value || '—'}</pre>
                                  </div>
                                </div>
                                {(diff.added.length > 0 || diff.removed.length > 0) && (
                                  <div className="dns-diff-summary">
                                    <span className="dns-diff-label">{t('dns.lineDiff')}</span>
                                    {diff.removed.map((line, idx) => (
                                      <div key={'rm'+idx} className="dns-diff-line dns-diff-removed">
                                        <span className="dns-diff-sign">−</span><code>{line}</code>
                                      </div>
                                    ))}
                                    {diff.added.map((line, idx) => (
                                      <div key={'ad'+idx} className="dns-diff-line dns-diff-added">
                                        <span className="dns-diff-sign">+</span><code>{line}</code>
                                      </div>
                                    ))}
                                  </div>
                                )}
                              </td>
                            </tr>
                          )}
                        </Fragment>
                      )
                    })}
                  </tbody>
                </table>
              </div>
              )}
              {history.length > 0 && (
                <div className="dns-history-pagination">
                  <div className="dash-page-sizer">
                    <span className="dash-page-sizer-label">{t('app.perPage')}</span>
                    {[50, 100, 200].map(n => (
                      <button
                        key={n}
                        type="button"
                        className={`dash-size-btn${historyPageSize === n ? ' active' : ''}`}
                        onClick={() => setHistoryPageSize(n)}
                      >{n}</button>
                    ))}
                  </div>
                  {totalPages > 1 && (
                    <div className="dash-page-nav">
                      <button type="button" className="page-btn" disabled={safePage <= 0}
                        onClick={() => setHistoryPage(0)}>«</button>
                      <button type="button" className="page-btn" disabled={safePage <= 0}
                        onClick={() => setHistoryPage(safePage - 1)}>{t('app.prevPage')}</button>
                      <span className="dash-page-info-mini">{safePage + 1} / {totalPages}</span>
                      <button type="button" className="page-btn" disabled={safePage >= totalPages - 1}
                        onClick={() => setHistoryPage(safePage + 1)}>{t('app.nextPage')}</button>
                      <button type="button" className="page-btn" disabled={safePage >= totalPages - 1}
                        onClick={() => setHistoryPage(totalPages - 1)}>»</button>
                    </div>
                  )}
                  <span className="dash-page-info">
                    {t('dns.pageInfo', pageStart + 1, pageEnd, history.length)}
                  </span>
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>,
    document.body
  )
}
