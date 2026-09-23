import { useT } from '../i18n/index.jsx'
import {
  LayoutDashboard, ShieldCheck, TriangleAlert, OctagonAlert, Siren,
  ServerCrash, AlarmClock, CalendarClock, CalendarX, ShieldAlert, ShieldX, Building2
} from 'lucide-react'

export default function StatsPanel({ stats, visible, onStatClick, activeFilter, weakStats, issuerStats, certIssueStats, onCaClick }) {
  const t = useT()
  if (!visible || !stats) return null

  const items = [
    { key: 'total',      Icon: LayoutDashboard, labelKey: 'stat.total',      value: stats.total_certificates,  cls: 'total'    },
    { key: 'valid',      Icon: ShieldCheck,     labelKey: 'stat.valid',      value: stats.valid_count,         cls: 'valid'    },
    { key: 'warning',    Icon: TriangleAlert,   labelKey: 'stat.warning',    value: stats.warning_count,       cls: 'warning'  },
    { key: 'high',       Icon: OctagonAlert,    labelKey: 'stat.high',       value: stats.high_count,          cls: 'high'     },
    { key: 'critical',   Icon: Siren,           labelKey: 'stat.critical',   value: stats.critical_count,      cls: 'critical' },
    { key: 'error',      Icon: ServerCrash,     labelKey: 'stat.error',      value: stats.error_count,         cls: 'error'    },
    { key: 'expiring7',  Icon: AlarmClock,      labelKey: 'stat.expiring7',  value: stats.expiring_in_7_days,  cls: 'critical' },
    { key: 'expiring30', Icon: CalendarClock,   labelKey: 'stat.expiring30', value: stats.expiring_in_30_days, cls: 'alert'    },
    { key: 'expired',    Icon: CalendarX,       labelKey: 'stat.expired',    value: stats.expired,             cls: 'expired'  },
  ]

  return (
    <div className="stats-panel">
      {items.map((item) => {
        const isActive = activeFilter === item.key
        const label = t(item.labelKey)
        return (
          <div
            key={item.key}
            className={`stat-item stat-item-${item.cls} stat-clickable${isActive ? ' stat-active' : ''}`}
            role="button" tabIndex={0}
            aria-pressed={isActive}
            aria-label={isActive ? t('stat.clearTip') : t('stat.filterTip', label)}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onStatClick(item.key) } }}
            onClick={() => onStatClick(item.key)}
            title={isActive ? t('stat.clearTip') : t('stat.filterTip', label)}
          >
            <span className="stat-icon"><item.Icon size={32} /></span>
            <span className={`stat-value stat-value-${item.cls}`}>{item.value ?? 0}</span>
            <span className="stat-label">{label}</span>
            {isActive && <span className="stat-active-dot" />}
          </div>
        )
      })}
      {issuerStats != null && (() => {
        const clsMap = { 1: 'critical', 2: 'warning' }
        const cls = clsMap[issuerStats.uniqueCount] ?? 'valid'
        const sub = `${issuerStats.dominantIssuer} (${issuerStats.dominantCount}, %${issuerStats.dominantPct})`
        return (
          <div className={`stat-item stat-item-${cls} stat-clickable`} title={sub}
            role="button" tabIndex={0} aria-label={`${t('stat.caDiv')} — ${sub}`}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onCaClick() } }}
            onClick={onCaClick}>
            <span className="stat-icon"><Building2 size={32} /></span>
            <span className={`stat-value stat-value-${cls}`}>{issuerStats.uniqueCount}</span>
            <span className="stat-label">{t('stat.caDiv')}</span>
            <span className="stat-issuer-sub">{sub}</span>
          </div>
        )
      })()}
      {weakStats != null && (() => {
        const isActive = activeFilter === 'weak'
        const label = t('stat.weak')
        return (
          <div
            className={`stat-item stat-item-weak stat-clickable${isActive ? ' stat-active' : ''}`}
            role="button" tabIndex={0}
            aria-pressed={isActive}
            aria-label={isActive ? t('stat.clearTip') : t('stat.filterTip', label)}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onStatClick('weak') } }}
            onClick={() => onStatClick('weak')}
            title={isActive ? t('stat.clearTip') : t('stat.filterTip', label)}
          >
            <span className="stat-icon"><ShieldAlert size={32} /></span>
            <span className="stat-value stat-value-weak">{weakStats.total ?? 0}</span>
            <span className="stat-label">{label}</span>
            {(weakStats.critical > 0 || weakStats.high > 0) && (
              <span className="stat-weak-sub">
                {weakStats.critical > 0 ? `${weakStats.critical} CRITICAL` : ''}
                {weakStats.critical > 0 && weakStats.high > 0 ? ' · ' : ''}
                {weakStats.high > 0 ? `${weakStats.high} HIGH` : ''}
              </span>
            )}
            {isActive && <span className="stat-active-dot" />}
          </div>
        )
      })()}
      {certIssueStats != null && (() => {
        const isActive = activeFilter === 'certissue'
        const label = t('stat.certIssue')
        const parts = []
        if (certIssueStats.revoked > 0)    parts.push(`${certIssueStats.revoked} ${t('stat.ciRevoked')}`)
        if (certIssueStats.chain > 0)      parts.push(`${certIssueStats.chain} ${t('stat.ciChain')}`)
        if (certIssueStats.trust > 0)      parts.push(`${certIssueStats.trust} ${t('stat.ciTrust')}`)
        if (certIssueStats.deployment > 0) parts.push(`${certIssueStats.deployment} ${t('stat.ciDeploy')}`)
        return (
          <div
            className={`stat-item stat-item-certissue stat-clickable${isActive ? ' stat-active' : ''}`}
            role="button" tabIndex={0}
            aria-pressed={isActive}
            aria-label={isActive ? t('stat.clearTip') : t('stat.filterTip', label)}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onStatClick('certissue') } }}
            onClick={() => onStatClick('certissue')}
            title={isActive ? t('stat.clearTip') : t('stat.filterTip', label)}
          >
            <span className="stat-icon"><ShieldX size={32} /></span>
            <span className="stat-value stat-value-certissue">{certIssueStats.total ?? 0}</span>
            <span className="stat-label">{label}</span>
            {parts.length > 0 && (
              <span className="stat-certissue-sub">{parts.join(' · ')}</span>
            )}
            {isActive && <span className="stat-active-dot" />}
          </div>
        )
      })()}
    </div>
  )
}
