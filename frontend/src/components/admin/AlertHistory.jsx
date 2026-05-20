import { useState, useEffect } from 'react'
import { api, formatDate } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import {
  Check, ShieldAlert, TrendingUp, RefreshCcw, Bell, CheckCircle,
  ChevronUp, ChevronDown, Mail,
} from 'lucide-react'

const levelColor = { WARNING: '#f0a500', HIGH: '#e07b00', CRITICAL: '#c0392b' }

function AuditRow({ label, by, at, variant }) {
  const colors = {
    ack:     { bg: '#f0fdf4', border: '#86efac', text: '#15803d', iconBg: '#dcfce7' },
    resolve: { bg: '#eff6ff', border: '#93c5fd', text: '#1d4ed8', iconBg: '#dbeafe' },
    system:  { bg: '#f8fafc', border: '#cbd5e1', text: '#475569', iconBg: '#f1f5f9' },
  }
  const c = colors[variant] ?? colors.system
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 10,
      background: c.bg, border: `1px solid ${c.border}`,
      borderRadius: 8, padding: '8px 12px', fontSize: '.84em',
    }}>
      <span style={{
        width: 28, height: 28, borderRadius: '50%', background: c.iconBg,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        flexShrink: 0,
      }}><Check size={14} color={c.text} /></span>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
        <span style={{ color: c.text, fontWeight: 700 }}>{label}</span>
        <span style={{ color: '#64748b' }}>
          <strong>{by}</strong>
          {at && <> &nbsp;·&nbsp; {formatDate(at)}</>}
        </span>
      </div>
    </div>
  )
}

function EmailStatusBadge({ status }) {
  const t = useT()
  if (!status) return null
  if (status === 'SENT')
    return <span className="nl-status nl-status-ok">{t('alh.status.sent')}</span>
  if (status === 'SKIPPED_DISABLED')
    return <span className="nl-status nl-status-warn">{t('alh.status.skip')}</span>
  if (status.startsWith('FAILED'))
    return <span className="nl-status nl-status-err" title={status}>{t('alh.status.failed')}</span>
  return <span className="nl-status nl-status-muted">{status}</span>
}

function NotifLogCard({ log: l }) {
  const t = useT()
  const locale = useDateLocale()
  const [open, setOpen] = useState(false)

  const triggerMeta = {
    INITIAL:       { Icon: ShieldAlert,  textKey: 'alh.trigger.initial',    bg: '#fef2f2', border: '#fca5a5', color: '#c0392b' },
    ESCALATION:    { Icon: TrendingUp,   textKey: 'alh.trigger.escalation', bg: '#fff7ed', border: '#fdba74', color: '#c2410c' },
    DAILY_REALERT: { Icon: RefreshCcw,   textKey: 'alh.trigger.daily',      bg: '#fffbeb', border: '#fcd34d', color: '#92400e' },
    MANUAL:        { Icon: Bell,         textKey: 'alh.trigger.manual',     bg: '#eff6ff', border: '#93c5fd', color: '#1d4ed8' },
    RESOLUTION:    { Icon: CheckCircle,  textKey: 'alh.trigger.resolution', bg: '#f0fdf4', border: '#86efac', color: '#15803d' },
  }

  const trigBase = triggerMeta[l.trigger]
  const trig = trigBase
    ? { ...trigBase, text: t(trigBase.textKey) }
    : { Icon: Mail, text: l.trigger, bg: '#f8fafc', border: '#e2e8f0', color: '#475569' }

  function fmtDateTime(iso) {
    if (!iso) return '—'
    try {
      const s = iso.endsWith('Z') || iso.includes('+') ? iso : iso + 'Z'
      return new Date(s).toLocaleString(locale, {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit',
      })
    } catch { return iso }
  }

  const sentDate = fmtDateTime(l.sent_at)

  return (
    <div className="nl-card" style={{ borderColor: trig.border, background: open ? trig.bg : undefined }}>
      <div className="nl-card-header" onClick={() => setOpen(o => !o)}>
        <span className="nl-trigger-badge" style={{ background: trig.bg, color: trig.color, borderColor: trig.border }}>
          <trig.Icon size={11} /> {trig.text}
        </span>
        <div className="nl-recipient">
          <strong>{l.recipient_name}</strong>
          <span className="role-badge" style={{ marginLeft: 6, fontSize: '.75em' }}>{l.recipient_role}</span>
          <span className="nl-email">{l.recipient_email}</span>
        </div>
        <div className="nl-right">
          <EmailStatusBadge status={l.email_status} />
          <span className="nl-time">{sentDate}</span>
          <span className="nl-chevron">{open ? <ChevronUp size={12} /> : <ChevronDown size={12} />}</span>
        </div>
      </div>

      {open && (
        <div className="nl-card-body">
          <div className="nl-detail-row">
            <span className="nl-detail-label">{t('alh.notif.subject')}</span>
            <span className="nl-detail-val nl-subject">{l.subject || '—'}</span>
          </div>
          <div className="nl-detail-row">
            <span className="nl-detail-label">{t('alh.notif.content')}</span>
            <span className="nl-detail-val nl-message">{l.message || '—'}</span>
          </div>
          <div className="nl-detail-row">
            <span className="nl-detail-label">{t('alh.notif.emailStatus')}</span>
            <span className="nl-detail-val">
              <EmailStatusBadge status={l.email_status} />
              {l.email_status?.startsWith('FAILED') && (
                <span className="nl-error-detail">{l.email_status.replace('FAILED: ', '')}</span>
              )}
            </span>
          </div>
          {l.webhook_status && l.webhook_status !== 'SKIPPED' && (
            <div className="nl-detail-row">
              <span className="nl-detail-label">{t('alh.notif.webhook')}</span>
              <span className="nl-detail-val">{l.webhook_status}</span>
            </div>
          )}
          <div className="nl-detail-row">
            <span className="nl-detail-label">{t('alh.notif.sentAt')}</span>
            <span className="nl-detail-val">{sentDate}</span>
          </div>
        </div>
      )}
    </div>
  )
}

function NotifyResultModal({ alertId, alertInfo, currentResult, onClose }) {
  const t = useT()
  const [history, setHistory]       = useState([])
  const [loadingHistory, setLoading] = useState(true)

  useEffect(() => {
    api.admin.getAlertNotifications(alertId).then(res => {
      setLoading(false)
      if (res?.success) setHistory(res.data)
    })
  }, [alertId])

  const { notifications = [], contacts_attempted } = currentResult?.data ?? {}

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-box nl-modal" onClick={e => e.stopPropagation()}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
          <h3 style={{ margin: 0 }}>{t('alh.notifModal.title')}</h3>
          {alertInfo && (
            <span style={{
              fontSize: '.8em', fontWeight: 700, padding: '2px 9px',
              borderRadius: 12, background: alertInfo.resolved ? '#eff6ff' : '#fef2f2',
              color: alertInfo.resolved ? '#1d4ed8' : '#c0392b',
              border: `1px solid ${alertInfo.resolved ? '#93c5fd' : '#fca5a5'}`,
            }}>
              {alertInfo.resolved ? t('alh.notifModal.closed') : t('alh.notifModal.open')} · {alertInfo.domain}
            </span>
          )}
        </div>

        {notifications.length > 0 && (
          <div className="nl-section">
            <div className="nl-section-title">
              {t('alh.notifModal.lastSent')}
              <span className="nl-count">{t('alh.notifModal.recipients', contacts_attempted)}</span>
            </div>
            {notifications.some(n => n.email_status === 'SKIPPED_DISABLED') && (
              <div className="nl-banner-warn">{t('alh.notifModal.emailOff')}</div>
            )}
            {notifications.map((n, i) => (
              <div key={i} className="nl-quick-row">
                <strong>{n.name}</strong>
                <span className="role-badge">{n.role}</span>
                <span className="nl-email">{n.email}</span>
                <EmailStatusBadge status={n.email_status} />
              </div>
            ))}
          </div>
        )}

        <div className="nl-section" style={{ marginTop: notifications.length > 0 ? 20 : 0 }}>
          <div className="nl-section-title">
            {t('alh.notifModal.allHistory')}
            {!loadingHistory && <span className="nl-count">{t('alh.notifModal.records', history.length)}</span>}
          </div>

          {loadingHistory && <div className="nl-empty">{t('alh.loading')}</div>}

          {!loadingHistory && history.length === 0 && (
            <div className="nl-empty">{t('alh.notifModal.noNotifs')}</div>
          )}

          {!loadingHistory && history.map((l, i) => (
            <NotifLogCard key={l.id ?? i} log={l} />
          ))}
        </div>

        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose}>{t('alh.notifModal.close')}</button>
        </div>
      </div>
    </div>
  )
}

export default function AlertHistory() {
  const t = useT()
  const { showConfirm } = useDialog()
  const [alerts,       setAlerts]       = useState([])
  const [onlyOpen,     setOnlyOpen]     = useState(true)
  const [loading,      setLoading]      = useState(false)
  const [notifyModal,  setNotifyModal]  = useState(null)
  const [notifying,    setNotifying]    = useState(null)

  const levelLabel = {
    WARNING: t('alh.level.warning'), HIGH: t('alh.level.high'), CRITICAL: t('alh.level.critical'),
  }
  const typeLabel = {
    EXPIRY: t('alh.type.expiry'), CHAIN_BROKEN: t('alh.type.chain'),
    REVOKED: t('alh.type.revoked'), MISMATCH: t('alh.type.mismatch'),
  }

  useEffect(() => { load() }, [onlyOpen])

  async function load() {
    setLoading(true)
    const res = await api.admin.getAlerts(onlyOpen)
    setLoading(false)
    if (res?.success) setAlerts(res.data)
  }

  async function ack(id) {
    const alert = alerts.find(a => a.id === id)
    const confirmed = await showConfirm({
      title: t('alh.ackDialog.title'),
      message: t('alh.ackDialog.msg', alert?.domain ?? ''),
      confirmText: t('alh.ackDialog.confirm'),
      cancelText: t('alh.ackDialog.cancel'),
    })
    if (!confirmed) return
    await api.admin.acknowledgeAlert(id)
    load()
  }

  async function resolve(id) {
    const alert = alerts.find(a => a.id === id)
    const confirmed = await showConfirm({
      title: t('alh.resolveDialog.title'),
      message: t('alh.resolveDialog.msg', alert?.domain ?? ''),
      confirmText: t('alh.resolveDialog.confirm'),
      cancelText: t('alh.resolveDialog.cancel'),
      variant: 'info',
    })
    if (!confirmed) return
    await api.admin.resolveAlert(id)
    load()
  }

  async function reNotify(id) {
    setNotifying(id)
    const res = await api.admin.reNotifyAlert(id)
    setNotifying(null)
    if (res?.success) {
      const alert = alerts.find(a => a.id === id)
      setNotifyModal({ alertId: id, alertInfo: alert, result: res })
      load()
    }
  }

  function openNotifyHistory(id) {
    const alert = alerts.find(a => a.id === id)
    setNotifyModal({ alertId: id, alertInfo: alert, result: null })
  }

  function parseContacts(json) {
    try { return JSON.parse(json) } catch { return [] }
  }

  const open   = alerts.filter(a => !a.resolved)
  const closed = alerts.filter(a =>  a.resolved)

  return (
    <div className="admin-section">
      <div className="admin-section-header">
        <h3>{t('alh.title')}</h3>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <label className="checkbox-label">
            <input type="checkbox" checked={onlyOpen} onChange={e => setOnlyOpen(e.target.checked)} />
            {t('alh.onlyOpen')}
          </label>
          <button className="btn btn-secondary" onClick={load}>{t('alh.refresh')}</button>
        </div>
      </div>

      {loading && <div className="loading">{t('alh.loading')}</div>}

      {!loading && open.length === 0 && onlyOpen && (
        <div className="empty-state">{t('alh.noOpen')}</div>
      )}

      {open.length > 0 && (
        <div className="alert-list">
          {open.map(a => {
            const notifiedList = parseContacts(a.notified_contacts)
            return (
              <div key={a.id} className={`alert-card alert-${a.alert_level?.toLowerCase()}`}>

                <div className="alert-card-header">
                  <span className="alert-level-badge" style={{ background: levelColor[a.alert_level] }}>
                    {levelLabel[a.alert_level] || a.alert_level}
                  </span>
                  <span className="alert-type">{typeLabel[a.alert_type] || a.alert_type}</span>
                  <strong className="alert-domain">{a.domain}</strong>
                  {a.days_remaining != null && (
                    <span className="alert-days">{t('alh.days', a.days_remaining)}</span>
                  )}
                </div>

                <p className="alert-message">{a.message}</p>

                <div className="alert-meta">
                  <span>{t('alh.created')} {formatDate(a.created_at)}</span>
                  {a.last_re_alert_at && (
                    <span>{t('alh.lastNotif')} {formatDate(a.last_re_alert_at)}</span>
                  )}
                </div>

                {notifiedList.length > 0 && (
                  <div className="alert-notified">
                    <span className="notified-label">{t('alh.notified')}</span>
                    {notifiedList.map((c, i) => (
                      <span key={i} className="notified-chip" title={c.email}>
                        {c.name} <em>({c.role})</em>
                      </span>
                    ))}
                  </div>
                )}

                {a.acknowledged && (
                  <div style={{ margin: '10px 0' }}>
                    <AuditRow
                      label={t('alh.acknowledged')}
                      by={a.acknowledged_by}
                      at={a.acknowledged_at}
                      variant="ack"
                    />
                  </div>
                )}

                <div className="alert-actions">
                  {!a.acknowledged && (
                    <button className="btn btn-secondary btn-sm-p" onClick={() => ack(a.id)}>
                      {t('alh.ack')}
                    </button>
                  )}
                  <button
                    className="btn btn-warning btn-sm-p"
                    onClick={() => reNotify(a.id)}
                    disabled={notifying === a.id}
                  >
                    {notifying === a.id ? t('alh.sending') : t('alh.renotify')}
                  </button>
                  <button
                    className="btn btn-secondary btn-sm-p"
                    onClick={() => openNotifyHistory(a.id)}
                    title={t('alh.notifHistory')}
                  >
                    {t('alh.history')}
                  </button>
                  <button className="btn btn-primary btn-sm-p" onClick={() => resolve(a.id)}>
                    {t('alh.resolve')}
                  </button>
                </div>

              </div>
            )
          })}
        </div>
      )}

      {!onlyOpen && closed.length > 0 && (
        <div style={{ marginTop: 28 }}>
          <h4 style={{ marginBottom: 12, color: '#374151' }}>
            {t('alh.closedTitle')} <span style={{ color: '#9ca3af', fontWeight: 400 }}>({closed.length})</span>
          </h4>
          <div className="alert-history-cards">
            {closed.map(a => (
              <div key={a.id} className="alert-history-card">
                <div className="ahc-stripe" style={{ background: levelColor[a.alert_level] ?? '#ccc' }} />

                <div className="ahc-body">
                  <div className="ahc-top">
                    <strong className="ahc-domain">{a.domain}</strong>
                    <span className="ahc-type">{typeLabel[a.alert_type] || a.alert_type}</span>
                    <span className="ahc-level" style={{ color: levelColor[a.alert_level] }}>
                      {levelLabel[a.alert_level] || a.alert_level}
                    </span>
                    {a.days_remaining != null && (
                      <span className="ahc-days">{t('alh.days', a.days_remaining)}</span>
                    )}
                  </div>

                  <div className="ahc-timeline">
                    <div className="ahc-tl-item">
                      <span className="ahc-tl-icon"><ShieldAlert size={13} /></span>
                      <div>
                        <div className="ahc-tl-label">{t('alh.tlCreated')}</div>
                        <div className="ahc-tl-val">{formatDate(a.created_at)}</div>
                      </div>
                    </div>

                    {a.acknowledged ? (
                      <div className="ahc-tl-item ahc-tl-ack">
                        <span className="ahc-tl-icon"><Check size={13} /></span>
                        <div>
                          <div className="ahc-tl-label">{t('alh.tlAck')}</div>
                          <div className="ahc-tl-val">
                            <strong>{a.acknowledged_by}</strong>
                            {a.acknowledged_at && <> &nbsp;·&nbsp; {formatDate(a.acknowledged_at)}</>}
                          </div>
                        </div>
                      </div>
                    ) : (
                      <div className="ahc-tl-item ahc-tl-noack">
                        <span className="ahc-tl-icon" style={{ color: '#9ca3af' }}>—</span>
                        <div>
                          <div className="ahc-tl-label">{t('alh.tlAckLabel')}</div>
                          <div className="ahc-tl-val" style={{ color: '#9ca3af' }}>{t('alh.tlNotAcked')}</div>
                        </div>
                      </div>
                    )}

                    <div className="ahc-tl-item ahc-tl-resolve">
                      <span className="ahc-tl-icon"><CheckCircle size={13} /></span>
                      <div>
                        <div className="ahc-tl-label">{t('alh.tlResolved')}</div>
                        <div className="ahc-tl-val">
                          <strong>{a.resolved_by === 'system' ? t('alh.autoResolved') : (a.resolved_by ?? '—')}</strong>
                          {a.resolved_at && <> &nbsp;·&nbsp; {formatDate(a.resolved_at)}</>}
                        </div>
                      </div>
                    </div>
                  </div>

                  <div className="ahc-footer">
                    <button
                      className="btn btn-secondary btn-sm-p"
                      onClick={() => openNotifyHistory(a.id)}
                    >
                      {t('alh.notifHistory')}
                    </button>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {notifyModal && (
        <NotifyResultModal
          alertId={notifyModal.alertId}
          alertInfo={notifyModal.alertInfo}
          currentResult={notifyModal.result}
          onClose={() => setNotifyModal(null)}
        />
      )}
    </div>
  )
}
