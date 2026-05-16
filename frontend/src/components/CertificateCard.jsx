import { formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'

export default function CertificateCard({ cert, onClick }) {
  const t = useT()
  const days = cert.days_remaining
  const isError = cert.status === 'error'
  const isCritical = !isError && days !== null && days !== undefined && days >= 0 && days <= 30
  const isExpired = days !== null && days !== undefined && days < 0

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
  } else if (cert.warning) {
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
        <div className="card-title">{cert.domain || t('card.unknown')}</div>
        <span className={`card-badge ${badgeClass}`}>{badgeText}</span>
      </div>
      {daysNode}
      <div className="card-info"><span className="card-info-label">{t('card.issuer')}</span> {cert.issuer_cn || cert.issuer || 'N/A'}</div>
      <div className="card-info"><span className="card-info-label">{t('card.subject')}</span> {cert.subject || 'N/A'}</div>
      <div className="card-info"><span className="card-info-label">{t('card.expires')}</span> {formatDate(cert.not_after)}</div>
      {cert.error && <div className="card-info" style={{ color: 'var(--danger-color)' }}><strong>{t('card.errorLbl')}</strong> {cert.error}</div>}
      <div className="card-footer">{t('card.lastCheck')} {formatDate(cert.checked_at)}</div>
    </div>
  )
}
