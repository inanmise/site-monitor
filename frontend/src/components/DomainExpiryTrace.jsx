import { useT } from '../i18n/index.jsx'
import { CheckCircle2, XCircle, MinusCircle } from 'lucide-react'

// Adım anahtarı → i18n etiket anahtarı
const STEP_LABEL = {
  PSL: 'dexp.stepPsl',
  IANA_BOOTSTRAP: 'dexp.stepBootstrap',
  RDAP_REGISTRY: 'dexp.stepRegistry',
  RDAP_ORG: 'dexp.stepRdapOrg',
  WHOIS: 'dexp.stepWhois',
}

// .tr WHOIS kaynak anahtarı → gösterim etiketi (cevabın nereden geldiği)
const PROVIDER_LABEL = {
  isimtescil: 'isimtescil.net',
  trabis: 'TRABIS · trabis.gov.tr',
  trabis43: 'TRABIS · whois :43',
}
const providerLabel = (p) => (p ? (PROVIDER_LABEL[p] || p) : null)

// Hata sınıfı → Türkçe ipucu i18n anahtarı
const ERR_HINT = {
  PKIX_TRUST: 'dexp.hintPkix',
  CONNECT_TIMEOUT: 'dexp.hintConnTimeout',
  TIMEOUT: 'dexp.hintTimeout',
  PROXY: 'dexp.hintProxy',
  DNS: 'dexp.hintDns',
  REFUSED: 'dexp.hintRefused',
  NO_EXPIRY: 'dexp.hintNoExpiry',
  NO_SERVER: 'dexp.hintNoServer',
  PSL: 'dexp.hintPsl',
  PARSE: 'dexp.hintParse',
  EMPTY: 'dexp.hintEmpty',
}

function sourceBadgeClass(source) {
  if (source === 'RDAP_REGISTRY' || source === 'RDAP_ORG') return 'badge badge-ok'
  if (source === 'WHOIS') return 'badge'
  return 'badge badge-err'
}

/** Alan adı süre bitişi tanılama sonucu — belirgin özet + adım adım zaman çizelgesi. Panel ve modalda paylaşılır. */
export default function DomainExpiryTrace({ data }) {
  const t = useT()
  if (!data) return null
  const steps = Array.isArray(data.steps) ? data.steps : []
  const found = data.expiry_date != null && data.source !== 'FAILED'

  return (
    <div className="dexp-result">
      {/* Özet */}
      <div className="dexp-summary">
        <div className="dexp-summary-main">
          <div className="dexp-summary-days" style={{ color: found ? 'var(--success, #15803d)' : 'var(--danger, #b91c1c)' }}>
            {data.days_remaining != null ? data.days_remaining : '—'}
          </div>
          <div className="dexp-summary-lbl">{t('dexp.daysLeft')}</div>
        </div>
        <dl className="dexp-summary-grid">
          <dt>{t('dexp.expiry')}</dt><dd>{data.expiry_date || '—'}</dd>
          <dt>{t('dexp.registrar')}</dt><dd>{data.registrar || '—'}</dd>
          <dt>{t('dexp.source')}</dt>
          <dd>
            <span className={sourceBadgeClass(data.source)}>{data.source || '—'}</span>
            {providerLabel(data.whois_provider) && (
              <span style={{ marginLeft: 6, color: 'var(--text-light)', fontSize: '.85em' }}>· {providerLabel(data.whois_provider)}</span>
            )}
          </dd>
          <dt>{t('dexp.registrable')}</dt><dd>{data.registrable || '—'}{data.tld ? ` · .${data.tld}` : ''}</dd>
          {data.persisted != null && data.persisted > 0 && (
            <><dt>{t('dexp.persisted')}</dt><dd>{t('dexp.persistedN').replace('{0}', data.persisted)}</dd></>
          )}
        </dl>
      </div>

      {/* Adım zaman çizelgesi */}
      <div className="dexp-steps">
        {steps.map((s, i) => {
          const label = t(STEP_LABEL[s.step] || 'dexp.stepUnknown')
          const isOk = s.status === 'ok'
          const isSkip = s.status === 'skip'
          const hintKey = s.error_class ? ERR_HINT[s.error_class] : null
          const hint = hintKey ? t(hintKey) : null
          return (
            <div key={`${s.step}-${i}`} className={`dexp-step dexp-step--${s.status}`}>
              <span className="dexp-step-icon">
                {isOk ? <CheckCircle2 size={16} className="dexp-ok" />
                  : isSkip ? <MinusCircle size={16} className="dexp-skip" />
                  : <XCircle size={16} className="dexp-err" />}
              </span>
              <div className="dexp-step-body">
                <div className="dexp-step-head">
                  <strong>{label}</strong>
                  {s.provider && <span className="badge badge-ok dexp-step-code">{providerLabel(s.provider)}</span>}
                  {s.error_class && <span className="badge badge-err dexp-step-code">{s.error_class}</span>}
                  {s.http_status != null && <span className="dexp-step-http">HTTP {s.http_status}</span>}
                  {s.elapsed_ms != null && <span className="dexp-step-ms">{s.elapsed_ms} ms</span>}
                </div>
                {s.detail && <div className="dexp-step-detail">{s.detail}</div>}
                {s.error && <div className="dexp-step-error">{s.error}</div>}
                {hint && <div className="dexp-step-hint">💡 {hint}</div>}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
