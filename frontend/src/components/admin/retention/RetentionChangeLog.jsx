import { Loader2, ArrowRight } from 'lucide-react'
import { formatDateSec } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'

/**
 * "Kim, ne zaman, hangi politikayı, hangi değerden hangi değere çekti."
 * Kaynak audit_log (hash-zinciri korumalı); kendi uç noktasından okunur çünkü denetim uçları
 * audit_log.read ister, bu sayfa settings.retention ile açılır — ikisi ayrık.
 */
export default function RetentionChangeLog({ rows, compact }) {
  const t = useT()

  if (!rows) return <div className="ret-loading"><Loader2 className="spin" size={16} /></div>
  if (rows.length === 0) return <p className="field-hint">{t('ret.changesEmpty')}</p>

  return (
    <ul className={`ret-changelog${compact ? ' ret-changelog--compact' : ''}`}>
      {rows.map((r, i) => (
        <li key={`${r.correlation_id || ''}-${r.policy_id}-${i}`} className="ret-change">
          <span className="ret-change-when">{formatDateSec(r.at)}</span>
          <span className="ret-change-who">{r.actor}</span>
          {!compact && <span className="ret-change-table">{r.table || r.policy_id}</span>}
          {r.from != null && r.to != null ? (
            <span className="ret-change-diff">
              <span className="audit-diff-from">{r.from}</span>
              <ArrowRight size={12} />
              <span className="audit-diff-to">{r.to}</span>
              <span className="ret-change-unit">{t('ret.daysShort')}</span>
            </span>
          ) : (
            <span className="ret-change-detail">{r.detail}</span>
          )}
          {!compact && r.ip && <span className="ret-change-ip">{r.ip}</span>}
        </li>
      ))}
    </ul>
  )
}
