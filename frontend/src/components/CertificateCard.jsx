import { ShieldAlert, ShieldCheck, MailWarning, BellOff, Clock, Calendar } from 'lucide-react'
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
  const showAlgo   = !!algoLabel && isWeak !== undefined && !isError
  const hasDetail  = !!cert.not_after || showAlgo
  const hasFooter  = hasSilentAlert || hasMailFailure

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
          <span className="cc-meta" title={t('card.lastCheck')}>
            <Clock size={11} />
            {formatDate(cert.checked_at)}
          </span>
        )}
      </div>

      {/* ── Hero — days remaining ── */}
      <div className="cc-hero">
        <div className="cc-hero-number">{daysDisplay}</div>
        <div className="cc-hero-label">{heroLabel}</div>
      </div>

      {/* ── Identity — domain + issuer + error message ── */}
      <div className="cc-identity">
        <div className="cc-domain">{cert.domain || t('card.unknown')}</div>
        <div className="cc-meta-line">{issuerName}</div>
        {cert.error && <div className="cc-error-line">{cert.error}</div>}
      </div>

      {/* ── Detail row — expiry date + algo chip ── */}
      {hasDetail && (
        <div className="cc-detail-row">
          {cert.not_after ? (
            <span className="cc-expires" title={t('card.expires')}>
              <Calendar size={12} />
              <span>{t('card.expiresShort')} {formatDate(cert.not_after)}</span>
            </span>
          ) : <span />}
          {showAlgo && (
            <span
              className={`cc-algo-chip cc-algo-${isWeak ? 'weak' : 'strong'}`}
              title={isWeak ? 'Weak Algorithm' : 'Strong Algorithm'}
            >
              {isWeak ? <ShieldAlert size={13} /> : <ShieldCheck size={13} />}
              <span>{algoLabel}</span>
            </span>
          )}
        </div>
      )}

      {/* ── Footer (only when an alert is active) ── */}
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
        </div>
      )}
    </div>
  )
}
