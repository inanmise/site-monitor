import { Check, X } from 'lucide-react'
import { formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'

function parseDn(dn, field) {
  const m = dn?.match(new RegExp(`(?:^|,)\\s*${field}=([^,]+)`))
  return m ? m[1].trim() : null
}

function CheckRow({ ok, text }) {
  return (
    <div className={`ssl-check-row ${ok ? 'ssl-ok' : 'ssl-fail'}`}>
      <span className={`ssl-badge ${ok ? 'ssl-badge-ok' : 'ssl-badge-fail'}`}>
        {ok
          ? <Check size={11} strokeWidth={3} />
          : <X    size={11} strokeWidth={2.5} />}
      </span>
      <span className="ssl-check-text">{text}</span>
    </div>
  )
}

function CertBlock({ label, cert, ok }) {
  const cn       = parseDn(cert.subject, 'CN') || cert.subject
  const org      = parseDn(cert.subject, 'O')
  const country  = parseDn(cert.subject, 'C')
  const locality = parseDn(cert.subject, 'L')
  const issuerCn = parseDn(cert.issuer,  'CN') || cert.issuer
  const location = [locality, country].filter(Boolean).join(', ')
  return (
    <div className="ssl-cert-block">
      <div className="ssl-cert-block-header">
        <span className={`ssl-badge ssl-badge-sm ${ok ? 'ssl-badge-ok' : 'ssl-badge-fail'}`}>
          {ok
            ? <Check size={9} strokeWidth={3} />
            : <X    size={9} strokeWidth={2.5} />}
        </span>
        <span className="ssl-cert-block-label">{label}</span>
      </div>
      <div className="ssl-cert-row"><span className="ssl-cert-key">CN</span><span>{cn}</span></div>
      {org      && <div className="ssl-cert-row"><span className="ssl-cert-key">Org</span><span>{org}</span></div>}
      {location && <div className="ssl-cert-row"><span className="ssl-cert-key">Loc</span><span>{location}</span></div>}
      <div className="ssl-cert-row">
        <span className="ssl-cert-key">Valid</span>
        <span>
          {cert.not_before ? `${formatDate(cert.not_before)} – ` : ''}
          {formatDate(cert.not_after)}
        </span>
      </div>
      {cert.serial_number && (
        <div className="ssl-cert-row">
          <span className="ssl-cert-key">Serial</span>
          <span className="ssl-cert-mono">{cert.serial_number.toLowerCase()}</span>
        </div>
      )}
      {cert.signature_algorithm && (
        <div className="ssl-cert-row"><span className="ssl-cert-key">Sig</span><span>{cert.signature_algorithm}</span></div>
      )}
      <div className="ssl-cert-row"><span className="ssl-cert-key">Issuer</span><span>{issuerCn}</span></div>
    </div>
  )
}

export default function SslCheckerPanel({ data }) {
  const t = useT()

  if (!data) return null

  if (data?.status === 'error') {
    return (
      <div className="ssl-error-state">
        <span className="ssl-badge ssl-badge-fail" style={{ width: 36, height: 36 }}>
          <X size={16} strokeWidth={2.5} />
        </span>
        <div className="ssl-error-title">{t('ssl.errorTitle')}</div>
        {data.error && <div className="ssl-error-msg">{data.error}</div>}
      </div>
    )
  }

  const days = data.days_remaining ?? 0

  const dnsOk   = !!data.resolved_ip
  const dnsText = dnsOk
    ? t('ssl.dnsOk',   data.domain, data.resolved_ip)
    : t('ssl.dnsFail', data.domain)

  const trustOk = data.chain_status === 'VALID'

  const caName = data.issuer || data.issuer_cn || '?'

  const expiryOk   = days > 0
  const expiryText = expiryOk
    ? t('ssl.expiresIn', days)
    : t('ssl.expired',   Math.abs(days))

  const hostnameOk = data.san?.some(s =>
    s === data.domain ||
    (s.startsWith('*.') && data.domain.endsWith(s.slice(1)))
  ) ?? false

  const revOk   = data.revocation_status === 'VALID'
  const revText = revOk
    ? t('ssl.revOk')
    : t('ssl.revFail', data.revocation_status ?? 'Unknown')

  const hstsOk = data.hsts === true

  const org      = parseDn(data.subject_dn, 'O')
  const locality = parseDn(data.subject_dn, 'L')
  const state    = parseDn(data.subject_dn, 'ST')
  const country  = parseDn(data.subject_dn, 'C')
  const location = [locality, state, country].filter(Boolean).join(', ')

  const chainEntries = (data.chain ?? []).filter(c => !c.is_leaf)
  const serverOk = expiryOk && hostnameOk

  return (
    <div className="ssl-checker-panel">
      <div className="ssl-checks">
        <CheckRow ok={dnsOk}      text={dnsText} />
        <CheckRow ok={trustOk}    text={t(trustOk ? 'ssl.trustOk' : 'ssl.trustFail')} />
        <CheckRow ok={!!caName}   text={t('ssl.issuedBy', caName)} />
        <CheckRow ok={expiryOk}   text={expiryText} />
        <CheckRow ok={hostnameOk} text={t(hostnameOk ? 'ssl.hostnameOk' : 'ssl.hostnameFail', data.domain)} />
        <CheckRow ok={revOk}      text={revText} />
        <CheckRow ok={hstsOk}     text={t(hstsOk ? 'ssl.hstsOk' : 'ssl.hstsFail')} />
      </div>

      <div className="ssl-section-header">
        <span className={`ssl-badge ssl-badge-sm ${serverOk ? 'ssl-badge-ok' : 'ssl-badge-fail'}`}>
          {serverOk ? <Check size={9} strokeWidth={3} /> : <X size={9} strokeWidth={2.5} />}
        </span>
        <span className="ssl-section-title">{t('ssl.server')}</span>
      </div>
      <div className="ssl-cert-block">
        <div className="ssl-cert-row"><span className="ssl-cert-key">CN</span><span>{data.subject || data.domain}</span></div>
        {data.san?.length > 0 && (
          <div className="ssl-cert-row">
            <span className="ssl-cert-key">SAN</span>
            <span>
              <span className="ssl-cert-muted">({data.san.length}) </span>
              <span className="ssl-san-list">{data.san.join(', ')}</span>
            </span>
          </div>
        )}
        {org      && <div className="ssl-cert-row"><span className="ssl-cert-key">Org</span><span>{org}</span></div>}
        {location && <div className="ssl-cert-row"><span className="ssl-cert-key">Loc</span><span>{location}</span></div>}
        <div className="ssl-cert-row">
          <span className="ssl-cert-key">Valid</span>
          <span>{formatDate(data.not_before)} – {formatDate(data.not_after)}</span>
        </div>
        {data.cert_type && (
          <div className="ssl-cert-row"><span className="ssl-cert-key">Type</span><span>{data.cert_type}</span></div>
        )}
        {data.public_key_algorithm && (
          <div className="ssl-cert-row">
            <span className="ssl-cert-key">Key</span>
            <span>{data.public_key_algorithm}{data.public_key_size ? ` ${data.public_key_size} bits` : ''}</span>
          </div>
        )}
        {data.tls_version && (
          <div className="ssl-cert-row"><span className="ssl-cert-key">TLS</span><span>{data.tls_version}</span></div>
        )}
        {data.serial_number && (
          <div className="ssl-cert-row">
            <span className="ssl-cert-key">Serial</span>
            <span className="ssl-cert-mono">{data.serial_number}</span>
          </div>
        )}
        {data.signature_algorithm && (
          <div className="ssl-cert-row"><span className="ssl-cert-key">Sig</span><span>{data.signature_algorithm}</span></div>
        )}
        <div className="ssl-cert-row">
          <span className="ssl-cert-key">Issuer</span>
          <span>{data.issuer_cn || data.issuer}</span>
        </div>
      </div>

      {chainEntries.length > 0 && (
        <>
          <div className="ssl-section-header">
            <span className="ssl-section-title">{t('ssl.chain')}</span>
          </div>
          {chainEntries.map((c, i) => (
            <CertBlock
              key={i}
              label={c.is_root ? 'Root CA' : `Intermediate ${i + 1}`}
              cert={c}
              ok={!c.expired}
            />
          ))}
        </>
      )}
    </div>
  )
}
