import { useState, useEffect, Fragment, lazy, Suspense } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { usePagination } from '../hooks/usePagination.js'
import PaginationBar from './ui/PaginationBar.jsx'
import { X, Activity, Clock, Server, FileText, Globe, Route } from 'lucide-react'
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid, ReferenceLine } from 'recharts'
import AlertHistory from './admin/AlertHistory'
import MonitorNotes from './MonitorNotes.jsx'
// Süre grafiği artık paylaşımlı ResponseTimeChart (ping/keyword/port ile aynı: 90g/özel aralık + avg/min-max/p95).
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))

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
  { days: 15, labelKey: 'dns.last15d' },
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

// Beklenen-set flip'i (backend withinExpected aynası): satırın TÜM değerleri monitörün beklenen listesindeyse
// true — "beklenen değerler arasında" rozetiyle gösterilir. Not: geçmiş satırlar GÜNCEL beklenen sete göre değerlendirilir.
function withinExpected(expectedJoined, valueJoined) {
  const expected = new Set((expectedJoined || '').split('\n').map(s => s.trim()).filter(Boolean))
  if (expected.size === 0) return false
  const values = (valueJoined || '').split('\n').map(s => s.trim()).filter(Boolean)
  return values.length > 0 && values.every(v => expected.has(v))
}

export default function DnsDetailModal({ monitor, onClose }) {
  const t = useT()
  const [details, setDetails] = useState(null)
  const [history, setHistory] = useState([])
  const [loading, setLoading] = useState(true)
  const [activeTab, setActiveTab] = useState(
    monitor?.record_type && RECORD_TYPES.includes(monitor.record_type) ? monitor.record_type : 'A'
  )
  const [rangeDays, setRangeDays] = useState(1)
  const [changedOnly, setChangedOnly] = useState(false)   // "Sadece Değişenler" — sunucu taraflı filtre
  const [detailTab, setDetailTab] = useState('control')   // üst tab: control | alerts | chart | notes

  // Details — bir kez yüklenir, monitor değişene kadar tutulur
  useEffect(() => {
    if (!monitor) return
    setLoading(true)
    setDetailTab('control')
    if (monitor.record_type && RECORD_TYPES.includes(monitor.record_type)) {
      setActiveTab(monitor.record_type)
    }
    api.monitoring.getDnsDetails(monitor.id).then(d => {
      if (d?.success) setDetails(d.data)
      setLoading(false)
    })
  }, [monitor])

  // History — monitor, rangeDays veya changedOnly değişince yeniden yüklenir
  useEffect(() => {
    if (!monitor) return
    api.monitoring.getDnsHistory(monitor.id, rangeDays, changedOnly).then(h => {
      if (h?.success) setHistory(h.data || [])
    })
  }, [monitor, rangeDays, changedOnly])

  const activeRangeOption = RANGE_OPTIONS.find(r => r.days === rangeDays) || RANGE_OPTIONS[0]
  // Sayfalama standardı: aralık/filtre/monitör değişince başa döner; boyut tercihi kalıcı.
  const histPager = usePagination(history, { listKey: 'dns-history', resetDeps: [rangeDays, changedOnly, monitor] })

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

        <div className="dns-modal-summary">
          {live?.success
            ? <span className="dns-status-ok">✓ SUCCESS</span>
            : <span className="dns-status-err">✗ {live?.error || 'ERROR'}</span>}
          <span className="dns-summary-metric" title={t('dns.responseMs')}><Activity size={13} /> {live?.response_ms != null ? `${live.response_ms}ms` : '—'}</span>
          <span className="dns-summary-metric" title={t('dns.ttl')}><Clock size={13} /> {live?.ttl != null ? `${live.ttl}s` : '—'}</span>
          <span className="dns-summary-metric" title={t('dns.recordType')}><Server size={13} /> {monitor.record_type}</span>
        </div>

        <div className="modal-tabs">
          <button className={`modal-tab${detailTab === 'control' ? ' active' : ''}`} onClick={() => setDetailTab('control')}>{t('dns.tabControl')}</button>
          <button className={`modal-tab${detailTab === 'alerts' ? ' active' : ''}`} onClick={() => setDetailTab('alerts')}>{t('dns.tabAlerts')}</button>
          <button className={`modal-tab${detailTab === 'chart' ? ' active' : ''}`} onClick={() => setDetailTab('chart')}>{t('dns.tabChart')}</button>
          <button className={`modal-tab${detailTab === 'notes' ? ' active' : ''}`} onClick={() => setDetailTab('notes')}>{t('dns.tabGuide')}</button>
        </div>

        {detailTab === 'alerts' && <AlertHistory domain={monitor.domain} />}
        {detailTab === 'notes' && <MonitorNotes type="DNS" target={monitor.domain} />}

        {detailTab === 'control' && (loading ? (
          <div className="dns-modal-loading">{t('dns.loadingDetails')}</div>
        ) : (
          <div className="dns-modal-body">
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

            {/* Resolver şeffaflığı: sorguların hangi DNS sunucularına gittiği (yapılandırma görünümü) */}
            {details?.resolver_config && (
              <div className="dns-section">
                <h4 className="dns-section-title">
                  <Route size={14} /> {t('dns.resolverConfigTitle')}
                </h4>
                <dl className="dns-soa-grid">
                  <dt>{t('dns.resolverServers')}</dt>
                  <dd>
                    {(details.resolver_config.servers || []).length > 0
                      ? details.resolver_config.servers.map((s, i) => (
                          <Fragment key={i}>
                            {i > 0 && <span className="dns-resolver-arrow"> → </span>}
                            <code>{s}</code>
                          </Fragment>
                        ))
                      : '—'}
                  </dd>
                  <dt>{t('dns.resolverSource')}</dt>
                  <dd>{t('dns.resolverSourceOs')}</dd>
                  <dt>{t('dns.resolverTimeout')}</dt>
                  <dd>{details.resolver_config.timeout_ms != null ? `${details.resolver_config.timeout_ms}ms` : '—'}</dd>
                  {details.resolver_config.propagation_enabled && (
                    <>
                      <dt>{t('dns.resolverPropagation')}</dt>
                      <dd>
                        {(details.resolver_config.propagation_resolvers || []).map((s, i) => (
                          <Fragment key={i}>
                            {i > 0 && ', '}
                            <code>{s}</code>
                          </Fragment>
                        ))}
                      </dd>
                    </>
                  )}
                </dl>
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

            {/* Recent history */}
            <div className="dns-section">
              <div className="dns-section-header-with-filter">
                <h4 className="dns-section-title">
                  <Clock size={14} /> {t('dns.recentChecks')}
                </h4>
                <div className="dns-range-filter">
                  <button
                    type="button"
                    className={`dns-range-btn${!changedOnly ? ' active' : ''}`}
                    onClick={() => setChangedOnly(false)}
                  >
                    {t('dns.filterAll')}
                  </button>
                  <button
                    type="button"
                    className={`dns-range-btn dns-range-btn--changed${changedOnly ? ' active' : ''}`}
                    onClick={() => setChangedOnly(true)}
                  >
                    {t('dns.filterChangedOnly')}
                  </button>
                  <span className="dns-filter-sep" aria-hidden="true" />
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
                <div className="dns-history-empty">{t(changedOnly ? 'dns.noChangesInRange' : 'dns.noHistoryInRange')}</div>
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
                    {histPager.pageItems.map((h, i) => {
                      const isChanged = h.changed
                      const isRotated = !isChanged && h.rotated
                      const prevVal = h.previous_value ?? h.previousValue
                      const showDiff = (isChanged || isRotated) && prevVal && prevVal !== h.value
                      const diff = showDiff ? computeDiff(prevVal, h.value) : null
                      const isExpectedFlip = isChanged && withinExpected(monitor.expected_value, h.value)
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
                              {isExpectedFlip && <span className="dns-expected-flip-badge" title={t('dns.withinExpectedTitle')}>{t('dns.withinExpected')}</span>}
                              {isRotated && <span className="dns-rotated-badge" title={t('dns.rotationTitle')}>{t('dns.rotated')}</span>}
                              {!isChanged && !isRotated && <span className="dns-nochange-badge">{t('dns.noChange')}</span>}
                            </td>
                          </tr>
                          {showDiff && diff && (
                            <tr className={isRotated ? 'dns-diff-row dns-diff-row-rotated' : 'dns-diff-row'}>
                              <td colSpan={5}>
                                <div className="dns-diff-when">
                                  <Clock size={13} />
                                  <span>{t('dns.diffDetectedAt')}</span>
                                  <strong>{formatDate(h.checked_at || h.checkedAt)}</strong>
                                </div>
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
              <PaginationBar {...histPager} compact />
            </div>
          </div>
        ))}

        {detailTab === 'chart' && (
          <div className="dns-modal-body">
            <Suspense fallback={<div className="dns-modal-loading">{t('dns.loadingDetails')}</div>}>
              <ResponseTimeChart monitorId={monitor.id} kind="dns" />
            </Suspense>
          </div>
        )}
      </div>
    </div>,
    document.body
  )
}
