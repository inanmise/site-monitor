import { ShieldAlert, ShieldCheck, MailWarning, BellOff, Clock, Calendar, Network, Globe, Users, Building2 } from 'lucide-react'
import { formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'

/** DN içinden bir alanı çıkar (örn. O=...) — SslCheckerPanel ile aynı desen. */
function parseDn(dn, field) {
  const m = dn?.match(new RegExp(`(?:^|,)\\s*${field}=([^,]+)`))
  return m ? m[1].trim() : null
}

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

  // İhraç eden (CA) organizasyonu — issuer_dn'deki O alanı; yoksa CA CN'ine düş.
  const issuerName = parseDn(cert.issuer_dn, 'O') || cert.issuer_cn || cert.issuer || 'N/A'
  const showAlgo   = !!algoLabel && isWeak !== undefined && !isError
  const hasDetail  = !!cert.not_after || showAlgo
  const hasFooter  = hasSilentAlert || hasMailFailure

  // Kontrol yolu rozeti: hata kartlarında her zaman, sağlıklı kartlarda
  // sadece proxy ile kontrol edilenlerde (direct varsayılan — gürültü yapma)
  const showVia  = !!cert.via   // tutarlı: via (Doğrudan/Proxy) dolu olan her kartta göster
  const viaLabel = cert.via === 'proxy' ? t('card.viaProxy') : t('card.viaDirect')

  const beforeMs = cert.not_before ? Date.parse(cert.not_before) : NaN
  const afterMs  = cert.not_after  ? Date.parse(cert.not_after)  : NaN
  const totalDays = Number.isFinite(beforeMs) && Number.isFinite(afterMs)
    ? Math.round((afterMs - beforeMs) / 86400000)
    : 0
  const elapsedDays = totalDays > 0 && days != null
    ? Math.max(0, Math.min(totalDays, totalDays - days))
    : 0
  const percentUsed = totalDays > 0
    ? Math.max(0, Math.min(100, (elapsedDays / totalDays) * 100))
    : 0
  const showLife = totalDays > 0
    && days != null && days >= 0
    && state !== 'error'

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
        <div className="cc-hero-row" title={showLife ? t('card.lifetimeTooltip', elapsedDays, totalDays) : undefined}>
          <div className="cc-hero-number">{daysDisplay}</div>
          {showLife && (
            <div className="cc-life-block">
              <div className="cc-life-bar">
                <div className="cc-life-fill" style={{ width: `${percentUsed}%` }} />
              </div>
              <div className="cc-life-caption">
                {elapsedDays} / {totalDays} {t('card.daysUnit')}
              </div>
            </div>
          )}
        </div>
        <div className="cc-hero-label">{heroLabel}</div>
      </div>

      {/* ── Identity — domain + issuer + error message ── */}
      <div className="cc-identity">
        <div className="cc-domain" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <Globe size={14} style={{ flexShrink: 0, opacity: .65 }} />
          <span>{cert.domain || t('card.unknown')}</span>
        </div>
        <div className="cc-meta-line" style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
          <Building2 size={12} style={{ flexShrink: 0, opacity: .7 }} />
          <span>{issuerName}</span>
        </div>
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

      {/* ── Takım (sol) ↔ kontrol yolu (sağ) — aynı satırda karşılıklı ── */}
      {(cert.team_name || showVia) && (
        <div className="cc-detail-row">
          {cert.team_name ? (
            <span className="cc-meta" title={t('card.team')} style={{ marginLeft: 0 }}>
              <Users size={12} />
              {cert.team_name}
            </span>
          ) : <span />}
          {showVia && (
            <span className="cc-meta" title={t('card.viaTooltip', viaLabel, cert.tls_mode_used || '—')}>
              {cert.via === 'proxy' ? <Network size={11} /> : <Globe size={11} />}
              {viaLabel}
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
