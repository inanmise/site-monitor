import { useState, useEffect } from 'react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { AlertTriangle, ShieldAlert } from 'lucide-react'

function SeverityBadge({ severity }) {
  const cls = severity === 'CRITICAL' ? 'wa-sev-critical' : 'wa-sev-high'
  return <span className={`wa-severity ${cls}`}>{severity}</span>
}

function StatusBadge({ status }) {
  if (!status) return <span className="wa-status-unknown">—</span>
  const cls = status === 'error' ? 'wa-status-error'
            : status === 'ok'    ? 'wa-status-ok'
            : 'wa-status-warn'
  return <span className={`wa-status ${cls}`}>{status.toUpperCase()}</span>
}

export default function WeakAlgorithmReport() {
  const t = useT()
  const [data,    setData]    = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api.admin.getWeakAlgorithms().then(r => {
      if (r?.success) setData(r)
    }).finally(() => setLoading(false))
  }, [])

  if (loading) return <div className="wa-loading">{t('wa.loading')}</div>

  if (!data || data.total === 0) {
    return (
      <div className="wa-empty">
        <ShieldAlert size={40} style={{ marginBottom: 12, opacity: .4 }} />
        <p>{t('wa.empty')}</p>
      </div>
    )
  }

  const { data: rows, total, critical, high } = data

  return (
    <div className="wa-root">

      {/* ── Banner ── */}
      <div className="wa-banner">
        <div className="wa-banner-left">
          <AlertTriangle size={20} />
          <span>{t('wa.totalWeak', total)}</span>
        </div>
        <div className="wa-banner-badges">
          {critical > 0 && (
            <span className="wa-banner-badge critical">{t('wa.bannerCritical', critical)}</span>
          )}
          {high > 0 && (
            <span className="wa-banner-badge high">{t('wa.bannerHigh', high)}</span>
          )}
        </div>
      </div>
      <p className="wa-banner-info">{t('wa.bannerInfo')}</p>

      {/* ── Table ── */}
      <div className="wa-table-wrap">
        <table className="wa-table">
          <thead>
            <tr>
              <th>{t('wa.colSeverity')}</th>
              <th>{t('wa.colDomain')}</th>
              <th>{t('wa.colOwner')}</th>
              <th>{t('wa.colTeam')}</th>
              <th>{t('wa.colSigAlgo')}</th>
              <th>{t('wa.colKeyAlgo')}</th>
              <th>{t('wa.colWeakness')}</th>
              <th>{t('wa.colExpiry')}</th>
              <th>{t('wa.colStatus')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(row => (
              <tr key={row.domain} className={`wa-row wa-row-${(row.severity || '').toLowerCase()}`}>
                <td><SeverityBadge severity={row.severity} /></td>
                <td className="wa-cell-domain">{row.domain}</td>
                <td>
                  {row.owner && <div>{row.owner}</div>}
                  {row.description && <div className="wa-sub">{row.description}</div>}
                  {!row.owner && !row.description && <span className="wa-muted">{t('wa.noOwner')}</span>}
                </td>
                <td>
                  {row.team_name
                    ? <div>{row.team_name}{row.team_email && <div className="wa-sub">{row.team_email}</div>}</div>
                    : <span className="wa-muted">{t('wa.noTeam')}</span>}
                </td>
                <td className="wa-mono">{row.signature_algorithm || '—'}</td>
                <td className="wa-mono">
                  {row.public_key_algorithm || '—'}
                  {row.public_key_size && <span className="wa-sub">{row.public_key_size} bit</span>}
                </td>
                <td>
                  {(row.weaknesses || []).map((w, i) => (
                    <div key={i} className="wa-weakness-chip">{w}</div>
                  ))}
                </td>
                <td>
                  <div>{row.not_after ? formatDate(row.not_after) : '—'}</div>
                  {row.days_remaining != null && (
                    <div className={`wa-sub ${row.days_remaining < 0 ? 'wa-expired' : row.days_remaining <= 30 ? 'wa-expiring' : ''}`}>
                      {row.days_remaining < 0
                        ? `${Math.abs(row.days_remaining)}g geçti`
                        : `${row.days_remaining}g kaldı`}
                    </div>
                  )}
                </td>
                <td><StatusBadge status={row.status} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
