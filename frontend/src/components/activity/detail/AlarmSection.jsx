import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Siren, Mail, BellRing, UserCheck, CheckCircle2, CloudLightning, Moon, ExternalLink, Lock, RefreshCw, BellOff,
} from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { useAlertDeliveries } from '../../admin/alerts/AlertDetail.jsx'
import AlertNotificationsPanel, { EmailStatusBadge, mailTriggerText } from '../../admin/alerts/AlertNotifications.jsx'
import { StormPushSection, StormPushTimelineEntry } from '../../admin/alerts/AlertStormPush.jsx'
import { AlertLevelBadge, AlertStateBadge, AlertTypeChip, StormBadge, AlertResolvedBy } from '../../admin/alerts/AlertBadges.jsx'
import { buildAlertTimeline, groupPushRows, statusLabel as pushStatusLabel } from '../../admin/alerts/alertHistoryModel.js'
import { showStormPushSection } from '../../admin/alerts/stormPushModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import { ApproxBadge, Fact, LinkButton, SectionCard } from './detailParts.jsx'
import { formatIstanbul } from './activityDetailModel.js'

/** Zincir olay simgeleri — alarm ayrıntısındaki zaman çizelgesiyle aynı dil (sol renk şeridi YOK; tonlu daire). */
const EVENT_STYLE = {
  opened:       { Icon: Siren,          ink: 'border-destructive/40 bg-destructive/10 text-destructive' },
  mail:         { Icon: Mail,           ink: 'border-border bg-muted text-muted-foreground' },
  push:         { Icon: BellRing,       ink: 'border-border bg-muted text-muted-foreground' },
  storm:        { Icon: CloudLightning, ink: 'border-violet-500/40 bg-violet-500/10 text-violet-700 dark:text-violet-300' },
  stormPush:    { Icon: BellRing,       ink: 'border-violet-500/40 bg-violet-500/10 text-violet-700 dark:text-violet-300' },
  quiet:        { Icon: Moon,           ink: 'border-indigo-500/40 bg-indigo-500/10 text-indigo-700 dark:text-indigo-300' },
  acknowledged: { Icon: UserCheck,      ink: 'border-sky-500/40 bg-sky-500/10 text-sky-700 dark:text-sky-300' },
  resolved:     { Icon: CheckCircle2,   ink: 'border-success/40 bg-success/10 text-success' },
}

/**
 * Alarmın bildirim ZİNCİRİ — yalnız alarmı okuma yetkisi olana (`can_open`). Veri VAR OLAN alarm uçlarından
 * (`/api/admin/alerts/{id}`, `/notifications`, `/push-deliveries`, `/storm-push`): kişi maskelemesi (paylaşım yoluyla okuyan,
 * 7/24) orada uygulanır, bu sayfa kişi verisi üretmez. Zincir: açılış → e-posta / push / fırtınaya devir / sessiz saat →
 * sahiplenme → çözüm; altında teslimat ayrıntıları (e-posta önizlemesi, push partileri — alarm ayrıntısının paneli).
 */
function AlarmChain({ alarmId, locale }) {
  const t = useT()
  const [st, setSt] = useState({ phase: 'loading', alert: null })
  const seq = useRef(0)
  const load = useCallback(() => {
    const my = ++seq.current
    setSt({ phase: 'loading', alert: null })
    Promise.resolve().then(() => api.admin.getAlert(alarmId)).catch(() => null).then((res) => {
      if (my !== seq.current) return
      const one = res?.success && res.data && !Array.isArray(res.data) && String(res.data.id) === String(alarmId) ? res.data : null
      setSt(one ? { phase: 'ready', alert: one } : { phase: 'error', alert: null, message: res?.error || null })
    })
  }, [alarmId])
  useEffect(() => { load(); const s = seq; return () => { s.current++ } }, [load])
  const deliveries = useAlertDeliveries(alarmId)
  const pushGroups = useMemo(() => groupPushRows(deliveries.push), [deliveries.push])
  const timeline = useMemo(
    () => (st.alert ? buildAlertTimeline({ alert: st.alert, notifications: deliveries.history, pushGroups, stormPush: deliveries.stormPush }) : []),
    [st.alert, deliveries.history, pushGroups, deliveries.stormPush],
  )
  const when = (iso) => (iso ? formatIstanbul(iso, locale) : t('alh.ev.timeUnknown'))

  if (st.phase === 'loading') {
    return (
      <div className="flex flex-col gap-2" aria-busy="true">
        <span className="sr-only" role="status">{t('act.dt.al.chainLoading')}</span>
        <Skeleton className="h-10 w-full rounded-lg" /><Skeleton className="h-10 w-full rounded-lg" />
      </div>
    )
  }
  if (st.phase === 'error') {
    return (
      <AlertBanner tone="danger" role="alert" className="mb-0"
        actions={<Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={load}><RefreshCw aria-hidden="true" />{t('act.dt.retry')}</Button>}>
        {t('act.dt.al.chainLoadError')}
      </AlertBanner>
    )
  }
  const a = st.alert
  return (
    <div data-slot="act-dt-alarm-chain" className="flex min-w-0 flex-col gap-3">
      <h4 className="m-0 text-xs font-bold tracking-wide text-muted-foreground uppercase">{t('act.dt.al.chain')}</h4>
      <ol data-slot="act-dt-alarm-timeline"
        className="relative m-0 flex list-none flex-col gap-2.5 p-0 pl-9 before:absolute before:top-3 before:bottom-3 before:left-[15px] before:w-px before:bg-border">
        {timeline.map((ev) => {
          const s = EVENT_STYLE[ev.kind] || EVENT_STYLE.mail
          return (
            <li key={ev.id} data-slot="act-dt-alarm-event" data-kind={ev.kind} className="relative min-w-0">
              <span aria-hidden="true" className={cn('absolute top-0 -left-9 grid size-8 place-items-center rounded-full border', s.ink)}>
                <s.Icon className="size-4" />
              </span>
              <div className="min-w-0 rounded-lg border bg-card px-3 py-2">
                {ev.kind === 'stormPush' ? <StormPushTimelineEntry item={ev.item} /> : (
                  <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5">
                    <span className="text-sm font-semibold [overflow-wrap:anywhere]">
                      {ev.kind === 'opened' && t('alh.ev.opened')}
                      {ev.kind === 'mail' && t('alh.ev.mail', ev.recipient || '—')}
                      {ev.kind === 'push' && (ev.people > 0 ? t('alh.ev.push', ev.people) : t('alh.ev.pushSystem'))}
                      {ev.kind === 'storm' && t(ev.mailOnly ? 'alh.ev.stormMail' : 'alh.ev.storm')}
                      {ev.kind === 'quiet' && (ev.resolution ? t('alh.ev.quietResolution') : t('alh.ev.quiet'))}
                      {ev.kind === 'acknowledged' && t('alh.ev.ack')}
                      {ev.kind === 'resolved' && t('alh.ev.resolved')}
                    </span>
                    {ev.kind === 'storm' && <StormBadge stormId={ev.stormId} className="text-[0.7em]" />}
                    {ev.kind === 'opened' && <AlertLevelBadge level={ev.level} className="text-[0.7em]" />}
                    {ev.kind === 'mail' && ev.trigger && <Badge variant="outline" className="h-auto text-[0.7em] whitespace-normal">{mailTriggerText(t, ev.trigger)}</Badge>}
                    {ev.kind === 'mail' && <EmailStatusBadge status={ev.status} />}
                    {ev.kind === 'push' && ev.trigger && <Badge variant="outline" className="h-auto text-[0.7em] whitespace-normal">{t('userpush.trigger.' + ev.trigger)}</Badge>}
                    {ev.kind === 'push' && ev.status && <Badge variant="outline" className="h-auto text-[0.7em] font-normal whitespace-normal text-muted-foreground">{pushStatusLabel(t, ev.status)}</Badge>}
                    <span className="ml-auto text-xs whitespace-nowrap text-muted-foreground tabular-nums">{when(ev.when)}</span>
                  </div>
                )}
                {ev.kind === 'resolved' && ev.by && <p className="m-0 mt-1 text-xs"><AlertResolvedBy by={ev.by} /></p>}
                {(ev.kind === 'acknowledged' || ev.kind === 'resolved') && ev.note && (
                  <p className="m-0 mt-1.5 rounded-md bg-muted/50 px-2 py-1 text-[0.85em] whitespace-pre-wrap text-muted-foreground italic [overflow-wrap:anywhere]">{ev.note}</p>
                )}
              </div>
            </li>
          )
        })}
        {deliveries.loading && (
          <li aria-hidden="true" className="relative"><Skeleton className="h-10 w-full rounded-lg" /></li>
        )}
      </ol>
      <h4 className="m-0 text-xs font-bold tracking-wide text-muted-foreground uppercase">{t('act.dt.al.deliveries')}</h4>
      {/* Alarm ayrıntısının teslimat paneli AYNEN. Kart başlığının orta sütunu (`flex-1`, taban 0) dar kapta (telefon,
          ~340 px) satıra sarmak yerine harf genişliğine eziliyordu — taban 12 rem verilir, sığmazsa alt satıra iner.
          Kalıcı düzeltme AlertNotifications.jsx `NotifCard` başlığında (alarm ayrıntısı telefonda da aynı). */}
      <div data-slot="act-dt-deliveries" className="flex min-w-0 flex-col gap-3 [&_[data-notif-head]>div]:basis-48">
        {showStormPushSection(a, deliveries.loading ? null : deliveries.stormPush) && (
          <StormPushSection data={deliveries.stormPush} loading={deliveries.loading} />
        )}
        <AlertNotificationsPanel history={deliveries.history} loadingHistory={deliveries.loading} loadFailed={deliveries.failed}
          pushGroups={pushGroups} alertLevel={a.alert_level} />
      </div>
    </div>
  )
}

/**
 * ALARM VE BİLDİRİM ZİNCİRİ (B): aktivitenin alarmı — özet (durum, önem, tür, açılış / kapanış, kapatan türü, sahiplenme,
 * fırtına) ve okuma yetkisi varsa tam zincir + teslimatlar. Eşleşme yaklaşıksa (alarm kimliği aktivitede yok; kontrol anını
 * kapsayan / 10 dk içinde açılan alarm) rozet + açıklama. `can_open` false → zincir okunmaz, "yetkiniz yok" notu.
 * Alarm yokken hatalı kontrolde nötr not (alarm açılmamış olabilir — onay zinciri, bastırma, kapalı tür). Test kancaları:
 * `act-dt-alarm` (`data-state` linked|none, `data-match`), `act-dt-alarm-locked`, `act-dt-alarm-chain`.
 */
export default function AlarmSection({ alarm, alertLink, locale, failed }) {
  const t = useT()
  if (!alarm) {
    if (!failed) return null
    return (
      <SectionCard slot="act-dt-alarm" icon={BellOff} title={t('act.dt.al.title')} data-state="none">
        <p className="m-0 text-sm text-muted-foreground">{t('act.dt.al.none')}</p>
      </SectionCard>
    )
  }
  const resolved = !!alarm.resolved_at
  const target = alertLink || { tab: 'alerthistory', params: { alert: alarm.id } }
  return (
    <SectionCard slot="act-dt-alarm" icon={Siren} title={t('act.dt.al.title')} data-state="linked" data-match={alarm.match}
      description={alarm.match === 'approximate' ? t('act.dt.al.approximate') : t('act.dt.al.exact')}
      action={alarm.can_open ? (
        <LinkButton target={target} slot="alert-open">
          <ExternalLink aria-hidden="true" />{t('act.dt.al.open', alarm.id)}
        </LinkButton>
      ) : null}>
      <div className="flex min-w-0 flex-wrap items-center gap-1.5">
        <AlertStateBadge resolved={resolved} />
        {alarm.level && <AlertLevelBadge level={alarm.level} />}
        {alarm.alert_type && <AlertTypeChip type={alarm.alert_type} />}
        {alarm.storm_id != null && <StormBadge stormId={alarm.storm_id} />}
        {alarm.match === 'approximate' && <ApproxBadge />}
      </div>
      <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-3 sm:grid-cols-2 lg:grid-cols-4">
        <Fact label={t('act.dt.al.opened')}><span className="tabular-nums">{alarm.opened_at ? formatIstanbul(alarm.opened_at, locale) : '—'}</span></Fact>
        <Fact label={t('act.dt.al.resolved')}>
          {resolved ? <span className="tabular-nums">{formatIstanbul(alarm.resolved_at, locale)}</span> : <span className="text-muted-foreground">{t('act.dt.al.stillOpen')}</span>}
        </Fact>
        {resolved && (
          <Fact label={t('act.dt.al.closedBy')}>
            {alarm.resolved_by_kind ? t('act.dt.al.by.' + alarm.resolved_by_kind) : <span className="text-muted-foreground">{t('act.st.UNKNOWN')}</span>}
          </Fact>
        )}
        <Fact label={t('act.dt.al.ack')}>{alarm.acknowledged ? t('act.dt.al.ackYes') : t('act.dt.al.ackNo')}</Fact>
      </dl>
      {alarm.can_open ? <AlarmChain alarmId={alarm.id} locale={locale} /> : (
        <div data-slot="act-dt-alarm-locked">
          <AlertBanner tone="info" icon={Lock} className="mb-0">{t('act.dt.al.locked')}</AlertBanner>
        </div>
      )}
    </SectionCard>
  )
}
