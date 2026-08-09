import { useState, useEffect, Fragment, lazy, Suspense } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useUrlQuerySync, readUrlParam } from '../hooks/useUrlQuerySync.js'
import CopyLinkButton from './ui/CopyLinkButton.jsx'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import { X, Activity, Clock, Server, FileText, Globe, Route } from 'lucide-react'
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid, ReferenceLine } from 'recharts'
import AlertHistory from './admin/AlertHistory'
import MonitorNotes from './MonitorNotes.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
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
  const [loading, setLoading] = useState(true)
  const [activeTab, setActiveTab] = useState(
    monitor?.record_type && RECORD_TYPES.includes(monitor.record_type) ? monitor.record_type : 'A'
  )
  const [detailTab, setDetailTab] = useState(() => readUrlParam('mtab', 'control'))   // üst tab: control | alerts | chart | notes

  // Paylaşılabilir URL: modal-içi konum (üst sekme) — geçmiş aralığı/filtreyi CheckHistoryTab senkronlar.
  useUrlQuerySync({
    mtab: detailTab !== 'control' ? detailTab : null,
  })

  // Details — bir kez yüklenir, monitor değişene kadar tutulur
  useEffect(() => {
    if (!monitor) return
    setLoading(true)
    if (monitor.record_type && RECORD_TYPES.includes(monitor.record_type)) {
      setActiveTab(monitor.record_type)
    }
    api.monitoring.getDnsDetails(monitor.id).then(d => {
      if (d?.success) setDetails(d.data)
      setLoading(false)
    })
  }, [monitor])

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
          <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
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
          <LoadingBlock label={t('dns.loadingDetails')} className="dns-modal-loading" />
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

            {/* Recent history — paylaşılan Kontrol Geçmişi (filterMode=changed: "Değişenler" chip'i) */}
            <div className="dns-section">
              <h4 className="dns-section-title">
                <Clock size={14} /> {t('dns.recentChecks')}
              </h4>
              <CheckHistoryTab kind="dns" monitorId={monitor.id} listKey="dns-history"
                filterMode="changed" gridClass="dns-rt-grid"
                columns={[t('dns.lastCheck'), t('dns.currentValue'), t('dns.ttl'), t('dns.responseMs'), t('dns.status')]}
                renderRow={(h) => {
                  const isChanged = h.changed
                  const isRotated = !isChanged && h.rotated
                  const prevVal = h.previous_value
                  const showDiff = (isChanged || isRotated) && prevVal && prevVal !== h.value
                  const diff = showDiff ? computeDiff(prevVal, h.value) : null
                  const isExpectedFlip = isChanged && withinExpected(monitor.expected_value, h.value)
                  return (<>
                    <span className="upt-rt-time">{formatDate(h.checked_at)}</span>
                    <span className="dns-history-value" title={h.value || '—'}><code>{h.value || '—'}</code></span>
                    <span className="upt-rt-ms">{h.ttl != null ? `${h.ttl}s` : '—'}</span>
                    <span className="upt-rt-ms">{h.response_ms != null ? `${h.response_ms}ms` : '—'}</span>
                    <span>
                      {isChanged && <span className="dns-changed-badge">{t('dns.changed')}</span>}
                      {isExpectedFlip && <span className="dns-expected-flip-badge" title={t('dns.withinExpectedTitle')}>{t('dns.withinExpected')}</span>}
                      {isRotated && <span className="dns-rotated-badge" title={t('dns.rotationTitle')}>{t('dns.rotated')}</span>}
                      {!isChanged && !isRotated && <span className="dns-nochange-badge">{t('dns.noChange')}</span>}
                    </span>
                    {showDiff && diff && (
                      <div className={isRotated ? 'dns-diff-cell dns-diff-row-rotated' : 'dns-diff-cell'}
                        style={{ gridColumn: '1 / -1' }}>
                        <div className="dns-diff-when">
                          <Clock size={13} />
                          <span>{t('dns.diffDetectedAt')}</span>
                          <strong>{formatDate(h.checked_at)}</strong>
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
                      </div>
                    )}
                  </>)
                }} />
            </div>
          </div>
        ))}

        {detailTab === 'chart' && (
          <div className="dns-modal-body">
            <Suspense fallback={<LoadingBlock label={t('dns.loadingDetails')} className="dns-modal-loading" />}>
              <ResponseTimeChart monitorId={monitor.id} kind="dns" />
            </Suspense>
          </div>
        )}
      </div>
    </div>,
    document.body
  )
}
