import { ShieldAlert, MailWarning, BellOff } from 'lucide-react'
import { formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'

export default function CertificateCard({ cert, onClick, hasSilentAlert = false, hasMailFailure = false, onMailFailureClick, isWeak }) {
  const t = useT()
  const days = cert.days_remaining
  const al = cert.alert_level
  const isError    = al ? al === 'error'    : cert.status === 'error'
  const isExpired  = al ? al === 'expired'  : (days !== null && days !== undefined && days < 0)
  const isCritical = al ? al === 'critical' : (!isError && days !== null && days !== undefined && days >= 0 && days <= 7)
  const isHigh     = al ? al === 'high'     : (!isError && !isExpired && !isCritical && days !== null && days !== undefined && days <= 15)
  const isWarning  = al ? al === 'warning'  : (!isError && !isExpired && !isCritical && !isHigh && cert.warning === true)

  const state = isError ? 'error'
              : isExpired ? 'error'
              : isCritical ? 'critical'
              : isHigh ? 'high'
              : isWarning ? 'warning'
              : 'valid'

  const pillLabel = isError ? t('card.error')
                  : isExpired ? t('card.critical')
                  : isCritical ? t('card.critical')
                  : isHigh ? t('card.high')
                  : isWarning ? t('card.warning')
                  : t('card.valid')

  const daysDisplay = isError ? '—'
                    : isExpired ? Math.abs(days)
                    : (days !== null && days !== undefined) ? days
                    : '—'

  const heroLabel = isError ? t('card.notChecked')
                  : isExpired ? t('card.expiredAgo')
                  : t('card.daysLeft')

  const algoLabel = cert.public_key_algorithm
    ? `${cert.public_key_algorithm}${cert.public_key_size ? ' ' + cert.public_key_size : ''}`
    : null

  const issuerName = cert.issuer_cn || cert.issuer || 'N/A'
  const hasFooter  = hasSilentAlert || hasMailFailure || (isWeak === true)

  return (
    <div className={`cc-card cc-${state}`} data-domain={cert.domain} onClick={() => onClick(cert.domain)}>
      {/* ── Top bar — tier + pill + last check ── */}
      <div className="cc-top">
        {cert.tier && (
          <span className={`cc-tier cc-tier-${cert.tier}`}>T{cert.tier}</span>
        )}
        <span className={`cc-pill cc-pill-${state}`}>
          <span className="cc-pill-dot" />
          {pillLabel}
        </span>
        {cert.checked_at && (
          <span className="cc-meta">{formatDate(cert.checked_at)}</span>
        )}
      </div>

      {/* ── Hero — days remaining ── */}
      <div className="cc-hero">
        <div className="cc-hero-number">{daysDisplay}</div>
        <div className="cc-hero-label">{heroLabel}</div>
      </div>

      {/* ── Identity — domain + issuer/algo + error message ── */}
      <div className="cc-identity">
        <div className="cc-domain">{cert.domain || t('card.unknown')}</div>
        <div className="cc-meta-line">
          {issuerName}
          {algoLabel && <> &nbsp;·&nbsp; {algoLabel}</>}
        </div>
        {cert.error && <div className="cc-error-line">{cert.error}</div>}
      </div>

      {/* ── Footer (only when any alert/issue active) ── */}
      {hasFooter && (
        <div className="cc-footer">
          {hasSilentAlert && (
            <span className="cc-chip cc-chip-warn" title={t('card.silentAlert')}>
              <BellOff size={12} />
              <span>{t('card.silentAlert')}</span>
            </span>
          )}
          {hasMailFailure && (
            <button
              type="button"
              className="cc-chip cc-chip-danger"
              title={t('card.mailFailureTooltip')}
              onClick={(e) => { e.stopPropagation(); onMailFailureClick?.() }}
            >
              <MailWarning size={12} />
              <span>{t('card.mailFailure')}</span>
            </button>
          )}
          {isWeak === true && (
            <span className="cc-chip cc-chip-danger" title="Weak Algorithm">
              <ShieldAlert size={12} />
              <span>{algoLabel || 'Weak algorithm'}</span>
            </span>
          )}
        </div>
      )}
    </div>
  )
}
