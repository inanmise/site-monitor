import Sparkline from './ui/Sparkline.jsx'
import { ShieldCheck, CalendarClock, Bell, AlertTriangle, Activity } from 'lucide-react'

/** Sayı kartı için hafta-üstü delta (yön + yüzde + iyi/kötü polaritesi). goodWhenUp: artış iyi mi (uptime) kötü mü (alarm). */
function countDelta(cur, prev, goodWhenUp) {
  if (cur == null || prev == null) return null
  const diff = cur - prev
  if (diff === 0) return null
  const dir = diff > 0 ? 'up' : 'down'
  const pct = prev !== 0 ? Math.round((diff / prev) * 100) : null
  return { dir, text: pct != null ? `${Math.abs(pct)}%` : `${diff > 0 ? '+' : ''}${diff}`, good: (dir === 'up') === goodWhenUp }
}

function DeltaTag({ d }) {
  if (!d) return null
  return <span className={`wr-kpi-delta wr-kpi-delta--${d.good ? 'good' : 'bad'}`}>{d.dir === 'up' ? '▲' : '▼'} {d.text}</span>
}

/**
 * Executive KPI şeridi — canlı cert/alarm/uptime (bu hafta + önceki haftaya delta + 8-hafta sparkline).
 * Veri {@code api.weeklyReports.kpis(id)}'den; delta yalnız windowed kartlarda (dolan/alarm/uptime).
 */
export default function WeeklyKpiStrip({ kpis, loading, t }) {
  if (loading && !kpis) return <div className="wr-kpi-strip wr-kpi-strip--loading">…</div>
  if (!kpis?.current) return null
  const c = kpis.current
  const p = kpis.previous || {}
  const trend = kpis.trend8w || []
  const upDelta = (c.uptimePct != null && p.uptimePct != null && c.uptimePct !== p.uptimePct)
    ? { dir: c.uptimePct > p.uptimePct ? 'up' : 'down', text: `${Math.abs(c.uptimePct - p.uptimePct).toFixed(2)} ${t('wr.kpiPt')}`, good: c.uptimePct > p.uptimePct }
    : null

  const cards = [
    { key: 'total', icon: ShieldCheck, label: t('wr.kpiTotalCerts'), value: c.totalCerts },
    { key: 'expiring', icon: CalendarClock, label: t('wr.kpiExpiring'), value: c.expiringInWindow,
      sub: t('wr.kpiRenewedSub', c.renewedInWindow), delta: countDelta(c.expiringInWindow, p.expiringInWindow, false),
      spark: trend.map((w) => w.expiring), sparkColor: 'var(--severity-high, #e07b00)' },
    { key: 'alarms', icon: Bell, label: t('wr.kpiAlarms'), value: c.alarmsOpened,
      delta: countDelta(c.alarmsOpened, p.alarmsOpened, false),
      spark: trend.map((w) => w.alarmsOpened), sparkColor: 'var(--danger, #dc3545)' },
    { key: 'critical', icon: AlertTriangle, label: t('wr.kpiCritical'), value: c.criticalCerts,
      sub: t('wr.kpiCriticalDomainsSub', c.criticalDomains) },
    { key: 'uptime', icon: Activity, label: t('wr.kpiUptime'),
      value: c.uptimePct != null ? `${c.uptimePct.toFixed(2)}%` : '—', delta: upDelta },
  ]

  return (
    <div className="wr-kpi-strip">
      {cards.map((card) => {
        const Icon = card.icon
        return (
          <div key={card.key} className="wr-kpi">
            <div className="wr-kpi-top"><Icon size={14} className="wr-kpi-icon" /><span className="wr-kpi-label">{card.label}</span></div>
            <div className="wr-kpi-val">{card.value}</div>
            <div className="wr-kpi-foot">
              <DeltaTag d={card.delta} />
              {card.sub && <span className="wr-kpi-sub">{card.sub}</span>}
              {card.spark && card.spark.length > 1 && (
                <span className="wr-kpi-spark"><Sparkline data={card.spark} color={card.sparkColor} width={70} height={20} /></span>
              )}
            </div>
          </div>
        )
      })}
    </div>
  )
}
