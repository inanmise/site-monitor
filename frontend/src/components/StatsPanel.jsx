import { useT } from '../i18n/index.jsx'
import {
  LayoutDashboard, ShieldCheck, TriangleAlert, OctagonAlert, Siren,
  ServerCrash, AlarmClock, CalendarClock, CalendarX
} from 'lucide-react'

export default function StatsPanel({ stats, visible, onStatClick, activeFilter }) {
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
    </div>
  )
}
