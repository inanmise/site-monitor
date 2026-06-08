import { useState, useEffect } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { X, Activity, Clock, Server, FileText, Globe } from 'lucide-react'

const RECORD_TYPES = ['A', 'AAAA', 'CNAME', 'MX', 'TXT', 'NS']

export default function DnsDetailModal({ monitor, onClose }) {
  const t = useT()
  const [details, setDetails] = useState(null)
  const [history, setHistory] = useState([])
  const [loading, setLoading] = useState(true)
  const [activeTab, setActiveTab] = useState('A')

  useEffect(() => {
    if (!monitor) return
    setLoading(true)
    Promise.all([
      api.monitoring.getDnsDetails(monitor.id),
      api.monitoring.getDnsHistory(monitor.id, 50),
    ]).then(([d, h]) => {
      if (d?.success) setDetails(d.data)
      if (h?.success) setHistory(h.data || [])
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

            {/* Recent history */}
            {history.length > 0 && (
              <div className="dns-section">
                <h4 className="dns-section-title">
                  <Clock size={14} /> {t('dns.recentChecks')}
                </h4>
                <table className="dns-history-table">
                  <thead>
                    <tr>
                      <th>{t('dns.lastCheck')}</th>
                      <th>{t('dns.ttl')}</th>
                      <th>{t('dns.responseMs')}</th>
                      <th>{t('dns.status')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {history.slice(0, 15).map((h, i) => (
                      <tr key={i} className={h.changed ? 'dns-history-changed' : ''}>
                        <td className="dns-cell-time">{formatDate(h.checked_at || h.checkedAt)}</td>
                        <td className="dns-cell-num">{h.ttl != null ? `${h.ttl}s` : '—'}</td>
                        <td className="dns-cell-num">{h.response_ms != null ? `${h.response_ms}ms` : '—'}</td>
                        <td>
                          {h.changed
                            ? <span className="dns-changed-badge">{t('dns.changed')}</span>
                            : <span className="dns-nochange-badge">{t('dns.noChange')}</span>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}
      </div>
    </div>,
    document.body
  )
}
