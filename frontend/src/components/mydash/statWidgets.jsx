import {
  AlarmClockOff, BellOff, CalendarX, Clock, FileWarning, Gauge, PhoneOff, ServerCrash, ShieldAlert, ShieldX, Siren, TimerOff,
  TriangleAlert, Users,
} from 'lucide-react'
import { useLanguage, useT } from '../../i18n/index.jsx'
import WidgetCard from './WidgetCard.jsx'
import { useWidgetData } from './useWidgetData.js'
import { fetchOpenAlerts, fetchOverview, fetchStats, fetchStatus, linkTo } from './widgetParts.jsx'
import { fetchNocUncalled } from './nocWidgets.jsx'
import { formatPct } from './myDashModel.js'
import { STAT_DEFS, statDef, statReading } from './statModel.js'

/**
 * Renkli panel kutuları (2026-10-13, kullanıcı isteği: "renkli panel adetlerini arttıralım"): her kutu TEK gösterge, HER
 * ZAMAN renkli panel olarak çizilir (görünüm düğmesi yok). Aynı kaynağı okuyan kutular tek istek paylaşır (useWidgetData
 * eşzamanlı istekleri birleştirir). Bilinmeyen değer "—" ve gri; okunamayan veri WidgetCard'ın hata durumunda.
 */

const FETCHERS = { alerts: fetchOpenAlerts, overview: fetchOverview, stats: fetchStats, status: fetchStatus, noc: fetchNocUncalled }

const ICONS = {
  stat_alarms_critical: Siren,
  stat_noc_overdue: PhoneOff,
  stat_alarm_oldest: AlarmClockOff,
  stat_alarms_unacked: BellOff,
  stat_monitors_down: ServerCrash,
  stat_monitors_stale: TimerOff,
  stat_success_rate: Gauge,
  stat_teams_failing: Users,
  stat_service_outages: TriangleAlert,
  stat_incidents_severe: FileWarning,
  stat_certs_expired: ShieldX,
  stat_certs_week: CalendarX,
  stat_certs_month: Clock,
  stat_cert_errors: ShieldAlert,
}

/** Dakika → kısa süre metni (TV'den okunur: "45 dk", "3 sa", "2 gün"). */
function durationText(t, min) {
  if (min < 60) return t('mydash.st.minutes', min)
  if (min < 48 * 60) return t('mydash.st.hours', Math.floor(min / 60))
  return t('mydash.st.days', Math.floor(min / 1440))
}

function StatWidget({ type, frame, refreshKey }) {
  const t = useT()
  const { lang } = useLanguage()
  const def = statDef(type)
  const { data, loading, error, at } = useWidgetData(FETCHERS[def.source], { refreshKey })
  const r = statReading(type, data)
  const value = r.value == null ? '—'
    : r.none ? t('mydash.st.none')
      : r.percent ? formatPct(r.value, lang)
        : r.minutes ? durationText(t, r.value)
          : r.atLeast ? `${r.value}+` : r.value
  const sub = r.reason === 'noaccess' ? t('mydash.noAccess')
    : r.reason === 'partial' ? t('mydash.noc.partial')
      : r.reason === 'nodata' && data != null ? t('mydash.st.noData')
        : r.sub ? t(...r.sub) : null
  const onClick = r.link ? linkTo(frame, r.link[0], r.link[1]) : undefined
  return (
    <WidgetCard frame={frame} Icon={ICONS[type]} title={t('mydash.w.' + type)} tab={def.tab} tabParams={def.tabParams} loading={loading}
      error={error} at={at} tone={r.tone} forceStat toneLabel={r.tone !== 'muted' ? t('mydash.st.tone.' + r.tone) : null}
      stat={{ value, label: t('mydash.st.' + type), sub, onClick, hint: t('mydash.linkHint'), text: !!(r.minutes || r.none) }}>
      {null}
    </WidgetCard>
  )
}

/** Kutu → bileşen eşlemesi (widgets.jsx WIDGETS kaydına eklenir). */
export const STAT_WIDGETS = Object.fromEntries(STAT_DEFS.map((d) => {
  const Component = (props) => <StatWidget type={d.type} {...props} />
  Component.displayName = `StatWidget(${d.type})`
  return [d.type, { Icon: ICONS[d.type], Component }]
}))
