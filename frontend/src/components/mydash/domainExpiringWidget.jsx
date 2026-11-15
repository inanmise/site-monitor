import { CalendarClock } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import WidgetCard from './WidgetCard.jsx'
import { useWidgetData } from './useWidgetData.js'
import { Empty, List, More, Row, Tile, linkTo, listLimit } from './widgetParts.jsx'
import { domainExpiring } from './domainExpiringModel.js'

/**
 * Alan adı izlemelerinin listesi (mevcut uç; kapsam sunucuda). sharedFetch aynı getiriciyle tek uçuşta istek yapar.
 * 403 = okuma yetkisi yok → `visible:false` (kutu gri "yetki yok", asla yeşil).
 */
export const fetchDomainMonitors = async () => {
  const res = await api.monitoring.getDomainMonitors()
  if (res && res.success === false && (res.status === 403 || res.errorInfo?.status === 403)) return { success: true, data: { visible: false } }
  return res
}

const ROW_TONE = { expired: 'danger', critical: 'danger', warning: 'warning', ok: 'muted' }

/** "N gün önce doldu" / "N gün" — İngilizcede tekil. */
function daysText(t, d) {
  if (d < 0) return Math.abs(d) === 1 ? t('mydash.dexp.expiredAgo.one') : t('mydash.dexp.expiredAgo', Math.abs(d))
  return d === 1 ? t('mydash.dexp.daysLeft.one') : t('mydash.dexp.daysLeft', d)
}

/**
 * "Yakında bitecek alan adları" (2026-11-15, C2): alan adı izlemeleri en az kalan günden — canlı gün, izlemenin kendi
 * eşikleri. Renkli panel: eşikteki (dolmuş + kritik + uyarı) sayısı. Satır izlemenin ayrıntısını açar.
 */
export function DomainExpiringWidget({ frame, refreshKey }) {
  const t = useT()
  const { data, loading, error, at } = useWidgetData(fetchDomainMonitors, { refreshKey })
  const s = domainExpiring(data, listLimit(frame.widget.size, frame.tv, 5))
  const urgent = s.expired + s.critical
  const inWindow = urgent + s.warning
  const toneLabel = s.hidden ? t('mydash.noAccess')
    : s.expired > 0 ? t('mydash.dexp.expiredCount', s.expired)
    : s.critical > 0 ? t('mydash.dexp.criticalCount', s.critical)
    : s.warning > 0 ? t('mydash.dexp.warningCount', s.warning)
    : s.active === 0 ? t('mydash.dexp.none')
    : s.unknown > 0 ? t('mydash.dexp.unknownCount', s.unknown)
    : t('mydash.allClear')
  const open = linkTo(frame, 'domain')
  return (
    <WidgetCard frame={frame} Icon={CalendarClock} title={t('mydash.w.domain_expiring')} tab="domain"
      loading={loading} error={error} at={at} tone={s.tone} toneLabel={toneLabel}
      stat={s.hidden ? null : {
        value: inWindow, label: t('mydash.dexp.statLabel'), sub: t('mydash.dexp.statSub', s.expired, s.critical, s.unknown),
        onClick: open, hint: t('mydash.linkHint'),
      }}>
      {s.hidden ? <Empty tone="muted" text={t('mydash.noAccessBody')} tv={frame.tv} /> : (<>
        <div className="grid min-w-0 grid-cols-2 gap-2 @md:grid-cols-4" data-slot="mydash-dexp-tiles">
          <Tile value={s.expired} label={t('mydash.dexp.expired')} tone={s.expired ? 'danger' : 'muted'} tv={frame.tv} slot="mydash-dexp-expired"
            onClick={linkTo(frame, 'domain', { dq: 'active', dx: 'expired' })} hint={t('mydash.linkHint')} />
          <Tile value={s.critical} label={t('mydash.dexp.critical')} tone={s.critical ? 'danger' : 'muted'} tv={frame.tv} slot="mydash-dexp-critical"
            onClick={open} hint={t('mydash.linkHint')} />
          <Tile value={s.warning} label={t('mydash.dexp.warning')} tone={s.warning ? 'warning' : 'muted'} tv={frame.tv} slot="mydash-dexp-warning"
            onClick={open} hint={t('mydash.linkHint')} />
          <Tile value={s.unknown} label={t('mydash.dexp.unknown')} tone="muted" tv={frame.tv} slot="mydash-dexp-unknown"
            onClick={linkTo(frame, 'domain', { dq: 'active', dx: 'unknown' })} hint={t('mydash.linkHint')} />
        </div>
        {s.active === 0 && <Empty tone="muted" text={t('mydash.dexp.noneBody')} tv={frame.tv} />}
        {s.active > 0 && s.tone === 'success' && <Empty text={t('mydash.dexp.allClean')} tv={frame.tv} />}
        {s.rows.length > 0 && (
          <List slot="mydash-dexp-list">
            {s.rows.map((r) => (
              <Row key={r.id ?? r.domain} title={r.domain || r.name || '—'} meta={[r.name !== r.domain ? r.name : null, r.team].filter(Boolean).join(' · ') || null}
                onClick={r.id != null ? linkTo(frame, 'domain', { monitor: r.id }) : undefined} hint={t('mydash.linkDetail')}
                trail={<ToneBadge tone={ROW_TONE[r.state] || 'muted'} className="h-auto rounded-md whitespace-normal tabular-nums">{daysText(t, r.days)}</ToneBadge>} />
            ))}
          </List>
        )}
        <More n={s.more} t={t} />
      </>)}
    </WidgetCard>
  )
}

export const DOMAIN_WIDGETS = {
  domain_expiring: { Icon: CalendarClock, Component: DomainExpiringWidget },
}
