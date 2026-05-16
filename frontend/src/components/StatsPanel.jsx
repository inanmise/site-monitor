import { useT } from '../i18n/index.jsx'
import { BarChart3, CheckCircle, AlertTriangle, XCircle, Clock, Ban } from 'lucide-react'

export default function StatsPanel({ stats, visible, onStatClick, activeFilter }) {
  const t = useT()
  if (!visible || !stats) return null

  const items = [
    { key: 'total',      Icon: BarChart3,     labelKey: 'stat.total',      value: stats.total_certificates,  cls: 'total' },
    { key: 'valid',      Icon: CheckCircle,   labelKey: 'stat.valid',       value: stats.valid_count,          cls: 'valid' },
    { key: 'warning',    Icon: AlertTriangle, labelKey: 'stat.warning',     value: stats.warning_count,        cls: 'warning' },
    { key: 'error',      Icon: XCircle,       labelKey: 'stat.error',       value: stats.error_count,          cls: 'error' },
    { key: 'expiring30', Icon: Clock,         labelKey: 'stat.expiring30',  value: stats.expiring_in_30_days,  cls: 'alert' },
    { key: 'expired',    Icon: Ban,           labelKey: 'stat.expired',     value: stats.expired,              cls: 'expired' },
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
            onClick={() => onStatClick(item.key)}
            title={isActive ? t('stat.clearTip') : t('stat.filterTip', label)}
          >
            <span className="stat-icon"><item.Icon size={24} /></span>
            <span className={`stat-value stat-value-${item.cls}`}>{item.value ?? 0}</span>
            <span className="stat-label">{label}</span>
            {isActive && <span className="stat-active-dot" />}
          </div>
        )
      })}
    </div>
  )
}
