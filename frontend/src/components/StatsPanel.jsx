import { useT } from '../i18n/index.jsx'
import {
  LayoutDashboard, ShieldCheck, TriangleAlert, OctagonAlert, Siren,
  ServerCrash, AlarmClock, CalendarClock, CalendarX, ShieldAlert, ShieldX, Building2
} from 'lucide-react'
import MonitorStatsBar from './MonitorStatsBar.jsx'

/**
 * Genel Bakış (Pano) sertifika sayım kartları. 2026-09-26: eski elle kurulmuş `div.stat-item[role=button]` ailesi
 * (App.css `.stats-panel`: üstte renkli şerit, telefonda 4 sıkışık sütun) yerine izleme sayfalarıyla AYNI shadcn
 * kart ızgarası `MonitorStatsBar` (Button + aria-pressed, telefonda 2 / tablette 3 sütun). Pano'ya özgü:
 * "{0} sertifikaları filtrele" ipucu (`tip`), CA çeşitliliği kartı süzgeç değil PENCERE açar (`onClick`),
 * zayıf algoritma / sertifika sorunu kartlarının alt satırı (`sub`).
 */
export default function StatsPanel({ stats, visible, onStatClick, activeFilter, weakStats, issuerStats, certIssueStats, onCaClick }) {
  const t = useT()
  if (!visible || !stats) return null

  const filterItem = (key, Icon, labelKey, value, cls, sub) => {
    const label = t(labelKey)
    return { key, Icon, label, value, cls, sub, tip: activeFilter === key ? t('stat.clearTip') : t('stat.filterTip', label) }
  }

  const items = [
    filterItem('total',      LayoutDashboard, 'stat.total',      stats.total_certificates,  'total'),
    filterItem('valid',      ShieldCheck,     'stat.valid',      stats.valid_count,         'valid'),
    filterItem('warning',    TriangleAlert,   'stat.warning',    stats.warning_count,       'warning'),
    filterItem('high',       OctagonAlert,    'stat.high',       stats.high_count,          'high'),
    filterItem('critical',   Siren,           'stat.critical',   stats.critical_count,      'critical'),
    filterItem('error',      ServerCrash,     'stat.error',      stats.error_count,         'error'),
    filterItem('expiring7',  AlarmClock,      'stat.expiring7',  stats.expiring_in_7_days,  'critical'),
    filterItem('expiring30', CalendarClock,   'stat.expiring30', stats.expiring_in_30_days, 'alert'),
    filterItem('expired',    CalendarX,       'stat.expired',    stats.expired,             'expired'),
  ]

  if (issuerStats != null) {
    const cls = { 1: 'critical', 2: 'warning' }[issuerStats.uniqueCount] ?? 'valid'
    const sub = `${issuerStats.dominantIssuer} (${issuerStats.dominantCount}, %${issuerStats.dominantPct})`
    items.push({ key: 'ca', Icon: Building2, label: t('stat.caDiv'), value: issuerStats.uniqueCount, cls, sub,
      tip: `${t('stat.caDiv')} — ${sub}`, onClick: onCaClick })
  }
  if (weakStats != null) {
    const parts = []
    if (weakStats.critical > 0) parts.push(`${weakStats.critical} CRITICAL`)
    if (weakStats.high > 0) parts.push(`${weakStats.high} HIGH`)
    items.push(filterItem('weak', ShieldAlert, 'stat.weak', weakStats.total ?? 0, 'weak', parts.join(' · ') || undefined))
  }
  if (certIssueStats != null) {
    const parts = []
    if (certIssueStats.revoked > 0)    parts.push(`${certIssueStats.revoked} ${t('stat.ciRevoked')}`)
    if (certIssueStats.chain > 0)      parts.push(`${certIssueStats.chain} ${t('stat.ciChain')}`)
    if (certIssueStats.trust > 0)      parts.push(`${certIssueStats.trust} ${t('stat.ciTrust')}`)
    if (certIssueStats.deployment > 0) parts.push(`${certIssueStats.deployment} ${t('stat.ciDeploy')}`)
    items.push(filterItem('certissue', ShieldX, 'stat.certIssue', certIssueStats.total ?? 0, 'certissue', parts.join(' · ') || undefined))
  }

  return <MonitorStatsBar items={items} activeFilter={activeFilter} onStatClick={onStatClick} />
}
