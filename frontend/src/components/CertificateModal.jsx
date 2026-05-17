import { useEffect, useState, useCallback } from 'react'
import { api, formatDate, formatDateOnly } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { ChevronUp, ChevronDown } from 'lucide-react'

const LEVEL_CLS = { WARNING: 'badge-warn', HIGH: 'badge-high', CRITICAL: 'badge-crit' }

function AlertCard({ alert }) {
  const t = useT()
  const [open, setOpen]       = useState(false)
  const [notifs, setNotifs]   = useState(null)
  const [loading, setLoading] = useState(false)

  const LEVEL_LABEL = { WARNING: t('modal.alertAcked'), HIGH: t('modal.alertAcked'), CRITICAL: t('modal.alertOpen') }
  const TYPE_LABEL  = {
    EXPIRY: t('alh.type.expiry'), CHAIN_BROKEN: t('alh.type.chain'),
    REVOKED: t('alh.type.revoked'), MISMATCH: t('alh.type.mismatch'),
  }

  async function toggle() {
    if (!open && notifs === null) {
      setLoading(true)
      const res = await api.admin.getAlertNotifications(alert.id)
      setNotifs(res?.data ?? [])
      setLoading(false)
    }
    setOpen((v) => !v)
  }

  const statusBadge = alert.resolved
    ? <span className="badge badge-ok">{t('modal.alertResolved')}</span>
    : alert.acknowledged
      ? <span className="badge badge-warn">{t('modal.alertAcked')}</span>
      : <span className="badge badge-crit">{t('modal.alertOpen')}</span>

  return (
    <div className="alh-card">
      <button className="alh-header" onClick={toggle}>
        <div className="alh-left">
          <span className={`badge ${LEVEL_CLS[alert.alertLevel] ?? 'badge-warn'}`}>
            {LEVEL_LABEL[alert.alertLevel] ?? alert.alertLevel}
          </span>
          <span className="alh-type">{TYPE_LABEL[alert.alertType] ?? alert.alertType}</span>
          {alert.daysRemaining != null && (
            <span className="alh-days">{t('modal.alertDays', alert.daysRemaining)}</span>
          )}
        </div>
        <div className="alh-right">
          {statusBadge}
          <span className="alh-date">{formatDate(alert.createdAt)}</span>
          <span className="alh-toggle">{open ? <ChevronUp size={13} /> : <ChevronDown size={13} />}</span>
        </div>
      </button>

      {open && (
        <div className="alh-body">
          {alert.message && <p className="alh-msg">{alert.message}</p>}

          <div className="alh-meta-row">
            {alert.acknowledged && (
              <span className="alh-meta">
                {t('modal.ackedBy')} <strong>{alert.acknowledgedBy}</strong> ({formatDate(alert.acknowledgedAt)})
              </span>
            )}
            {alert.resolved && (
              <span className="alh-meta">
                {t('modal.resolvedBy')} <strong>{alert.resolvedBy}</strong> ({formatDate(alert.resolvedAt)})
              </span>
            )}
          </div>

          <div className="alh-notifs">
            <strong>{t('modal.notifications')}</strong>
            {loading ? (
              <div className="alh-notif-empty">{t('modal.notifLoading')}</div>
            ) : notifs && notifs.length === 0 ? (
              <div className="alh-notif-empty">{t('modal.notifNone')}</div>
            ) : notifs ? (
              <table className="alh-notif-table">
                <thead>
                  <tr>
                    <th>{t('modal.notifTime')}</th>
                    <th>{t('modal.notifRecipient')}</th>
                    <th>{t('modal.notifResult')}</th>
                  </tr>
                </thead>
                <tbody>
                  {notifs.map((n, i) => (
                    <tr key={i}>
                      <td>{formatDate(n.sent_at ?? n.sentAt)}</td>
                      <td>{n.contact_email ?? n.contactEmail ?? n.recipient ?? '—'}</td>
                      <td>
                        <span className={n.success ? 'alh-ok' : 'alh-err'}>
                          {n.success ? t('modal.notifSent') : t('modal.notifError')}
                        </span>
                        {n.error_message && (
                          <span className="alh-err-msg" title={n.error_message}> {n.error_message}</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : null}
          </div>
        </div>
      )}
    </div>
  )
}

export default function CertificateModal({ domain, onClose }) {
  const t = useT()
  const [certData, setCertData]   = useState(null)
  const [alerts, setAlerts]       = useState(null)
  const [alertsLoading, setAlertsLoading] = useState(false)
  const [activeTab, setActiveTab] = useState('details')

  useEffect(() => {
    if (!domain) return
    setCertData(null)
    setAlerts(null)
    setActiveTab('details')
    api.getHistory(domain).then((res) => {
      if (res?.success && res.data.length > 0) setCertData(res.data[0])
    })
  }, [domain])

  const loadAlerts = useCallback(async () => {
    if (alerts !== null) return
    setAlertsLoading(true)
    const res = await api.getDomainAlerts(domain)
    setAlerts(res?.data ?? [])
    setAlertsLoading(false)
  }, [domain, alerts])

  function switchTab(tab) {
    setActiveTab(tab)
    if (tab === 'alerts') loadAlerts()
  }

  if (!domain) return null

  const d = certData

  return (
    <div className="modal show" onClick={(e) => e.target.classList.contains('modal') && onClose()}>
      <div className="modal-content modal-wide">
        <div className="modal-header-row">
          <h2 className="modal-title">{domain}</h2>
          <span className="close" onClick={onClose}>&times;</span>
        </div>

        <div className="modal-tabs">
          <button
            className={`modal-tab${activeTab === 'details' ? ' active' : ''}`}
            onClick={() => switchTab('details')}
          >
            {t('modal.detailsTab')}
          </button>
          <button
            className={`modal-tab${activeTab === 'alerts' ? ' active' : ''}`}
            onClick={() => switchTab('alerts')}
          >
            {t('modal.alertsTab')}
            {alerts && alerts.length > 0 && (
              <span className="modal-tab-badge">{alerts.length}</span>
            )}
          </button>
        </div>

        {activeTab === 'details' && (
          !d ? (
            <div className="loading">{t('modal.loading')}</div>
          ) : (
            <div className="modal-body">

              {/* ── Identity ── */}
              <SectionTitle>{t('modal.secIdentity')}</SectionTitle>
              <Row label={t('modal.domain')}   value={d.domain}
                   label2={t('modal.status')}  value2={statusText(d, t)} />
              <Row label={t('modal.subject')}  value={d.subject}
                   label2={t('modal.issuer')}  value2={d.issuer_cn || d.issuer} />
              {d.subject_dn && <FullRow label={t('modal.subjectDn')} value={d.subject_dn} mono />}
              {d.issuer_dn  && <FullRow label={t('modal.issuerDn')}  value={d.issuer_dn}  mono />}

              {/* ── Validity ── */}
              <SectionTitle>{t('modal.secValidity')}</SectionTitle>
              <Row label={t('modal.notBefore')}  value={formatDate(d.not_before)}
                   label2={t('modal.notAfter')}  value2={formatDate(d.not_after)} />
              <Row label={t('modal.daysRemain')} value={d.days_remaining ?? 'N/A'}
                   label2={t('modal.lastCheck')} value2={formatDate(d.checked_at)} />
              {d.serial_number && (
                <FullRow label={t('modal.serialNumber')} value={d.serial_number} mono />
              )}

              {/* ── Key Information ── */}
              <SectionTitle>{t('modal.secKey')}</SectionTitle>
              <Row
                label={t('modal.pubKeyAlgo')}
                value={d.public_key_algorithm
                  ? `${d.public_key_algorithm}${d.public_key_size ? ' / ' + d.public_key_size + ' bit' : ''}`
                  : 'N/A'}
                label2={t('modal.sigAlgo')}
                value2={d.signature_algorithm || 'N/A'}
              />
              <Row
                label={t('modal.isCA')}
                value={d.is_ca == null ? 'N/A' : d.is_ca ? t('modal.yes') : t('modal.no')}
                label2=""
                value2=""
              />
              {d.key_usage?.length > 0 && (
                <FullRow label={t('modal.keyUsage')} value={d.key_usage.join(' · ')} />
              )}
              {d.ext_key_usage?.length > 0 && (
                <FullRow label={t('modal.extKeyUsage')} value={d.ext_key_usage.join(' · ')} />
              )}

              {/* ── Security ── */}
              <SectionTitle>{t('modal.secSecurity')}</SectionTitle>
              {d.fingerprint && (
                <FullRow label={t('modal.fingerprint')} value={d.fingerprint} mono />
              )}
              {(d.chain_status || d.revocation_status || d.deployment_status) && (
                <Row
                  label={t('modal.chainStatus')}      value={d.chain_status || 'N/A'}
                  label2={t('modal.revocationStatus')} value2={d.revocation_status || 'N/A'}
                />
              )}
              {d.deployment_status && (
                <Row
                  label={t('modal.deploymentStatus')} value={d.deployment_status || 'N/A'}
                  label2="" value2=""
                />
              )}

              {/* ── Infrastructure ── */}
              {(d.ocsp_url || d.crl_url) && (
                <>
                  <SectionTitle>{t('modal.secInfra')}</SectionTitle>
                  {d.ocsp_url && <FullRow label={t('modal.ocspUrl')} value={d.ocsp_url} />}
                  {d.crl_url  && <FullRow label={t('modal.crlUrl')}  value={d.crl_url}  />}
                </>
              )}

              {/* ── SAN ── */}
              {d.san?.length > 0 && (
                <>
                  <SectionTitle>{t('modal.san')}</SectionTitle>
                  <div className="modal-row full">
                    <div className="modal-field">
                      <div className="modal-field-value modal-san-list">
                        {d.san.map((s, i) => <span key={i} className="modal-san-chip">{s}</span>)}
                      </div>
                    </div>
                  </div>
                </>
              )}

              {/* ── Error ── */}
              {d.error && (
                <div className="modal-row full">
                  <div className="modal-field" style={{ background: '#ffe8e8', borderLeft: '4px solid var(--danger-color)' }}>
                    <div className="modal-field-label" style={{ color: 'var(--danger-color)' }}>{t('modal.errorMsg')}</div>
                    <div className="modal-field-value">{d.error}</div>
                  </div>
                </div>
              )}
            </div>
          )
        )}

        {activeTab === 'alerts' && (
          <div className="modal-body">
            {alertsLoading ? (
              <div className="loading">{t('modal.loading')}</div>
            ) : alerts && alerts.length === 0 ? (
              <div className="loading">{t('modal.noAlerts')}</div>
            ) : alerts ? (
              <div className="alh-list">
                {alerts.map((a) => <AlertCard key={a.id} alert={a} />)}
              </div>
            ) : null}
          </div>
        )}
      </div>
    </div>
  )
}

function SectionTitle({ children }) {
  return (
    <div className="modal-section-title">{children}</div>
  )
}

function Row({ label, value, label2, value2 }) {
  return (
    <div className="modal-row">
      <div className="modal-field">
        <div className="modal-field-label">{label}</div>
        <div className="modal-field-value">{value || (value === 0 ? 0 : 'N/A')}</div>
      </div>
      {label2 ? (
        <div className="modal-field">
          <div className="modal-field-label">{label2}</div>
          <div className="modal-field-value">{value2 || (value2 === 0 ? 0 : 'N/A')}</div>
        </div>
      ) : <div className="modal-field" />}
    </div>
  )
}

function FullRow({ label, value, mono = false }) {
  return (
    <div className="modal-row full">
      <div className="modal-field">
        <div className="modal-field-label">{label}</div>
        <div className={`modal-field-value${mono ? ' modal-mono' : ''}`}>{value || 'N/A'}</div>
      </div>
    </div>
  )
}

function statusText(cert, t) {
  if (cert.status === 'error') return t('modal.statusError')
  if (cert.warning) return t('modal.statusWarn')
  return t('modal.statusOk')
}
