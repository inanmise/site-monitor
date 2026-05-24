import { formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'

export default function CertificateCard({ cert, onClick, hasSilentAlert = false }) {
  const t = useT()
  const days = cert.days_remaining
  const al = cert.alert_level
  const isError    = al ? al === 'error'    : cert.status === 'error'
  const isExpired  = al ? al === 'expired'  : (days !== null && days !== undefined && days < 0)
  const isCritical = al ? al === 'critical' : (!isError && days !== null && days !== undefined && days >= 0 && days <= 7)
  const isHigh     = al ? al === 'high'     : (!isError && !isExpired && !isCritical && days !== null && days !== undefined && days <= 15)
  const isWarning  = al ? al === 'warning'  : (!isError && !isExpired && !isCritical && !isHigh && cert.warning === true)

  let cardClass = 'certificate-card'
  let badgeClass = 'badge-valid'
  let badgeText = t('card.valid')

  if (isError) {
    cardClass += ' error'
    badgeClass = 'badge-error'
    badgeText = t('card.error')
  } else if (isCritical || isExpired) {
    cardClass += ' critical'
    badgeClass = 'badge-critical'
    badgeText = t('card.critical')
  } else if (isHigh) {
    cardClass += ' high'
    badgeClass = 'badge-high'
    badgeText = t('card.high')
  } else if (isWarning) {
    cardClass += ' warning'
    badgeClass = 'badge-warning'
    badgeText = t('card.warning')
  } else {
    cardClass += ' valid'
  }

  let daysNode = null
  if (isExpired) {
    daysNode = <div className="days-remaining error">{t('card.expired', Math.abs(days))}</div>
  } else if (isCritical) {
    daysNode = <div className="days-remaining critical">{t('card.critical_days', days)}</div>
  } else if (days !== null && days !== undefined) {
    daysNode = <div className="days-remaining">{t('card.days', days)}</div>
  }

  return (
    <div className={cardClass} data-domain={cert.domain} onClick={() => onClick(cert.domain)}>
      <div className="card-header">
        <div className="card-title">
          {cert.tier && <span className={`tier-badge tier-badge-${cert.tier}`} style={{ marginRight: 5 }}>T{cert.tier}</span>}
          {cert.domain || t('card.unknown')}
        </div>
        <span className={`card-badge ${badgeClass}`}>{badgeText}</span>
      </div>
      {daysNode}
      <div className="card-info"><span className="card-info-label">{t('card.issuer')}</span> {cert.issuer_cn || cert.issuer || 'N/A'}</div>
      <div className="card-info"><span className="card-info-label">{t('card.subject')}</span> {cert.subject || 'N/A'}</div>
      <div className="card-info"><span className="card-info-label">{t('card.expires')}</span> {formatDate(cert.not_after)}</div>
      {cert.error && <div className="card-info" style={{ color: 'var(--danger-color)' }}><strong>{t('card.errorLbl')}</strong> {cert.error}</div>}
      <div className="card-footer">{t('card.lastCheck')} {formatDate(cert.checked_at)}</div>
      {hasSilentAlert && (
        <div className="card-silent-alert">
          <span className="card-silent-alert-icon">🔕</span>
          {t('card.silentAlert')}
        </div>
      )}
    </div>
  )
}
