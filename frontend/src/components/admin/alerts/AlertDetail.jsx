import { useEffect, useMemo, useState } from 'react'
import { createPortal } from 'react-dom'
import {
  X, Siren, Mail, BellRing, UserCheck, CheckCircle2, Clock, Hash, CalendarClock, BellPlus, PhoneCall, CloudLightning, Moon,
} from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { durationMs, formatDuration, formatIncidentTime } from '../../../utils/incidentMeta.js'
import TeamBadge from '../../ui/TeamBadge.jsx'
import UserBadge from '../../ui/UserBadge.jsx'
import MaintenanceBadge from '../../ui/MaintenanceBadge.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import CopyLinkButton from '../../ui/CopyLinkButton.jsx'
import CopyableRef from '../../ui/CopyableRef.jsx'
import { AckBadge } from '../../incidents/IncidentBadges.jsx'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetClose } from '@/components/shadcn/sheet'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import AlertSignatureStrip from './AlertSignatureStrip.jsx'
import AlertIncidentLink from './AlertIncidentLink.jsx'
import { NocCallSection } from './NocCallLog.jsx'
import AlertNotificationsPanel, { EmailStatusBadge, mailTriggerText } from './AlertNotifications.jsx'
import {
  AlertLevelBadge, AlertStateBadge, AlertTypeIcon, AlertTypeChip, AlertSourceLink, AlertResolvedBy, RepeatBadge,
  SendFailedBadge, WhyOpenChips, ActBlockedNote, StormBadge,
} from './AlertBadges.jsx'
import { alertExpiryIso, alertLink, buildAlertTimeline, groupPushRows, parseContacts, statusLabel } from './alertHistoryModel.js'

/** Tier rozeti tonları (sertifika kademesi). */
const TIER_BG = { 1: 'bg-indigo-600', 2: 'bg-sky-600', 3: 'bg-cyan-600', 4: 'bg-zinc-500' }

/** Zaman çizelgesi olay tonları — SOL RENK ŞERİDİ YOK: nötr bağlantı çizgisi + tonlu ikon daireleri (IncidentDetailSheet ile aynı). */
const EVENT_STYLE = {
  opened:       { Icon: Siren,        ink: 'border-destructive/40 bg-destructive/10 text-destructive' },
  mail:         { Icon: Mail,         ink: 'border-border bg-muted text-muted-foreground' },
  push:         { Icon: BellRing,     ink: 'border-border bg-muted text-muted-foreground' },
  // Bildirim fırtınaya devredildi (2026-09-30): bireysel e-posta/push gitmedi — neden burada okunur.
  storm:        { Icon: CloudLightning, ink: 'border-violet-500/40 bg-violet-500/10 text-violet-700 dark:text-violet-300' },
  // Bildirim takımın sessiz saat özetine devredildi (2026-10-01): ŞİMDİ gitmedi, pencere sonunda özetle gider.
  quiet:        { Icon: Moon,         ink: 'border-indigo-500/40 bg-indigo-500/10 text-indigo-700 dark:text-indigo-300' },
  acknowledged: { Icon: UserCheck,    ink: 'border-sky-500/40 bg-sky-500/10 text-sky-700 dark:text-sky-300' },
  resolved:     { Icon: CheckCircle2, ink: 'border-success/40 bg-success/10 text-success' },
}

/** Bir alarmın teslimat kayıtları — e-posta günlüğü + push partileri (tek istek çifti; push patlarsa bölüm boş kalır). */
export function useAlertDeliveries(id) {
  const [state, setState] = useState({ loading: true, failed: false, history: [], push: [] })
  useEffect(() => {
    if (id == null) return undefined
    let alive = true
    setState((s) => ({ ...s, loading: true, failed: false }))
    Promise.all([
      Promise.resolve().then(() => api.admin.getAlertNotifications(id)).catch(() => null),
      Promise.resolve().then(() => api.admin.getAlertPushDeliveries(id)).catch(() => null),
    ]).then(([n, p]) => {
      if (!alive) return
      setState({
        loading: false,
        failed: !n?.success,
        history: n?.success && Array.isArray(n.data) ? n.data : [],
        push: p?.success && Array.isArray(p.data) ? p.data : [],
      })
    })
    return () => { alive = false }
  }, [id])
  return state
}

/** Anahtar-değer satırı (temel bilgiler kartı). */
function Fact({ label, children, className }) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-0.5', className)}>
      <dt className="text-[10.5px] font-semibold tracking-wider text-muted-foreground uppercase">{label}</dt>
      <dd className="m-0 min-w-0 text-sm [overflow-wrap:anywhere]">{children}</dd>
    </div>
  )
}

const SECTION = 'mt-5 mb-2 text-xs font-bold tracking-wide text-muted-foreground uppercase'

/**
 * Alarm detayının GÖVDESİ — durum şeridi, eylemler, "neden hâlâ açık", temel bilgiler, imza geçmişi, zaman çizelgesi
 * (açılış → e-posta/push → sahiplenme → çözüm, notlarıyla) ve bildirim teslimatları (mail önizlemesi + push partileri).
 * Bağımsız sayfada Sheet'te (AlertDetailSheet), izleme pencerelerine gömülü kullanımda iç içe ModalShell'de çizilir.
 */
export function AlertDetailBody({
  alert: a, nowMs, teamName, push, onAck, onResolve, onReNotify, notifying = false, onShowHistory,
  nocCanWrite = false, nocFocusKey = 0, onNocChanged, actBlocked = false,
}) {
  const t = useT()
  const locale = useDateLocale()
  const deliveries = useAlertDeliveries(a?.id)
  const [localNocFocus, setLocalNocFocus] = useState(0)   // eylem satırındaki "Arama kaydet" → forma kaydır + odakla
  const pushGroups = useMemo(() => groupPushRows(deliveries.push), [deliveries.push])
  const timeline = useMemo(
    () => buildAlertTimeline({ alert: a, notifications: deliveries.history, pushGroups }),
    [a, deliveries.history, pushGroups],
  )
  if (!a) return null
  const open = !a.resolved
  const dur = formatDuration(durationMs(a.created_at, a.resolved_at, nowMs), t)
  const contacts = parseContacts(a.notified_contacts)
  const expIso = a.alert_type === 'EXPIRY' ? alertExpiryIso(a) : null
  const renewedIso = expIso && a.current_not_after && a.current_not_after !== expIso ? a.current_not_after : null
  const hasCert = Boolean(expIso || a.cert_tier != null || a.days_remaining != null)
  const when = (iso) => (iso ? formatIncidentTime(iso, locale) : t('alh.ev.timeUnknown'))
  const mails = Number(a.email_sent_count ?? 0), mailsFailed = Number(a.email_failed_count ?? 0)

  return (
    <div data-slot="alert-detail-body" className="min-w-0">
      {/* Durum şeridi */}
      <div className="flex flex-wrap items-center gap-1.5">
        <AlertStateBadge resolved={a.resolved} />
        <AlertLevelBadge level={a.alert_level} />
        {open && a.acknowledged && <AckBadge />}
        <MaintenanceBadge target={a.domain} />
        <RepeatBadge count={a.repeat_count} />
        <SendFailedBadge count={a.email_failed_count} />
        <StormBadge stormId={a.storm_id} />
        <span className="ml-auto inline-flex items-center gap-1 text-xs whitespace-nowrap text-muted-foreground tabular-nums">
          <Clock aria-hidden="true" className="size-3.5" />{open ? t('alh.openFor', dur) : t('alh.lastedFor', dur)}
        </span>
      </div>

      {/* Eylemler — açık alarmda sahiplen / çöz (gerekçeli) / tekrar bildir; her zaman kaynağa git + bağlantı */}
      <div data-slot="alert-detail-actions" className="mt-3 flex flex-wrap items-center gap-2">
        {open && !a.acknowledged && onAck && (
          <Button type="button" variant="outline" size="sm" className="h-9 pointer-coarse:h-10" onClick={() => onAck(a)}>
            <UserCheck aria-hidden="true" />{t('alh.ack')}
          </Button>
        )}
        {open && onResolve && (
          <Button type="button" variant="success" size="sm" className="h-9 pointer-coarse:h-10" onClick={() => onResolve(a)}>
            <CheckCircle2 aria-hidden="true" />{t('alh.resolveAction')}
          </Button>
        )}
        {open && onReNotify && (
          <Button type="button" variant="outline" size="sm" className="h-9 pointer-coarse:h-10" onClick={() => onReNotify(a)}
            disabled={notifying} aria-busy={notifying || undefined}>
            <BellPlus aria-hidden="true" />{notifying ? t('alh.sending') : t('alh.renotify')}
          </Button>
        )}
        {nocCanWrite && (
          <Button type="button" variant="outline" size="sm" className="h-9 pointer-coarse:h-10" onClick={() => setLocalNocFocus((n) => n + 1)}>
            <PhoneCall aria-hidden="true" />{t('nocCall.logCall')}
          </Button>
        )}
        <AlertSourceLink alert={a} className="h-9 pointer-coarse:h-10" />
        {/* Olay kaydı (2026-10-01): "Olay kaydı aç" (incidents.manage) ya da bağlı kaydın bağlantısı "Olay kaydı #N" */}
        <AlertIncidentLink alert={a} teamName={teamName} />
        {/* Sahiplen/çöz/tekrar bildir kapalı (7/24 operatörü başka takımın uyarısında ya da alerts.actions izni yok) — nedeni yazılır */}
        {open && actBlocked && <ActBlockedNote reason={actBlocked === true ? 'team' : actBlocked} />}
        <CopyLinkButton variant="outline" className="h-9 pointer-coarse:h-10" url={alertLink(a)} />
      </div>

      {open && <WhyOpenChips alert={a} push={push} className="mt-3" />}

      {/* Temel bilgiler */}
      <Card data-slot="alert-facts" className="mt-4 gap-0 py-0 shadow-none">
        <h3 className="px-4 pt-3 text-xs font-bold tracking-wide text-muted-foreground uppercase">{t('alh.facts')}</h3>
        <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-3 px-4 py-3 sm:grid-cols-2">
          <Fact label={t('alh.fact.target')} className="sm:col-span-2">
            <span className="font-semibold">{a.domain || '—'}</span>
            {a.message && <div className="mt-0.5 text-xs text-muted-foreground">{a.message}</div>}
          </Fact>
          <Fact label={t('alh.fact.type')}><AlertTypeChip type={a.alert_type} className="text-[1em]" /></Fact>
          <Fact label={t('alh.fact.team')}>
            {a.team_id != null
              ? <TeamBadge teamId={a.team_id} teamName={teamName || undefined} />
              : (a.sy_team_name || a.ug_team_name)
                ? (
                  <span className="flex flex-col gap-1">
                    {a.sy_team_name && <span className="inline-flex items-center gap-1 text-xs">{t('alh.syTeam')}: <TeamBadge teamId={a.sy_team_id} teamName={a.sy_team_name} size={11} /></span>}
                    {a.ug_team_name && <span className="inline-flex items-center gap-1 text-xs">{t('alh.ugTeam')}: <TeamBadge teamId={a.ug_team_id} teamName={a.ug_team_name} size={11} /></span>}
                  </span>
                )
                : <span className="text-muted-foreground">{t('alh.noTeam')}</span>}
          </Fact>
          <Fact label={t('alh.fact.opened')}><span title={a.created_at}>{when(a.created_at)}</span></Fact>
          <Fact label={t('alh.fact.duration')}><span className="tabular-nums">{dur}</span></Fact>
          {a.last_re_alert_at && <Fact label={t('alh.fact.lastNotif')}><span title={a.last_re_alert_at}>{when(a.last_re_alert_at)}</span></Fact>}
          {!open && (
            <Fact label={t('alh.fact.resolved')}>
              <span title={a.resolved_at || undefined}>{when(a.resolved_at)}</span>
              {a.resolved_by && <div className="mt-1 text-xs"><AlertResolvedBy by={a.resolved_by} /></div>}
            </Fact>
          )}
          {a.acknowledged && (
            <Fact label={t('alh.fact.ack')}>
              <UserBadge username={a.acknowledged_by} inline size="sm" />
              {a.acknowledged_at && <div className="mt-0.5 text-xs text-muted-foreground">{when(a.acknowledged_at)}</div>}
            </Fact>
          )}
          <Fact label={t('alh.fact.mails')}>
            <span className="tabular-nums">{t('alh.mailsSent', mails)}</span>
            <span className={cn('ml-2 tabular-nums', mailsFailed > 0 ? 'text-destructive' : 'text-muted-foreground')}>{t('alh.mailsFailed', mailsFailed)}</span>
          </Fact>
          {contacts.length > 0 && (
            <Fact label={t('alh.fact.notified')} className="sm:col-span-2">
              <span className="flex flex-wrap gap-1.5">
                {contacts.map((c, i) => (
                  <Badge key={c.email ?? `nc-${i}`} variant="outline" className="gap-1 rounded-full font-normal" title={c.email}>
                    <UserBadge displayName={c.name} email={c.email} inline size="sm" />{c.role && <em className="text-muted-foreground">({c.role})</em>}
                  </Badge>
                ))}
              </span>
            </Fact>
          )}
          {hasCert && (
            <Fact label={t('alh.fact.cert')} className="sm:col-span-2">
              <span className="flex flex-wrap items-center gap-1.5">
                {expIso && (
                  <Badge variant="outline" className="gap-1 rounded-full font-normal">
                    <CalendarClock aria-hidden="true" className="size-3" />{t('alh.expiryWas')}: <strong>{formatDate(expIso)}</strong>
                  </Badge>
                )}
                {renewedIso && (
                  <Badge variant="outline" data-renewed="" className="gap-1 rounded-full border-dashed font-normal" title={t('alh.expiryNowHint')}>
                    <CalendarClock aria-hidden="true" className="size-3" />{t('alh.expiryNow')}: <strong>{formatDate(renewedIso)}</strong>
                  </Badge>
                )}
                {a.days_remaining != null && <Badge variant="outline" className="rounded-full font-semibold">{t('alh.days', a.days_remaining)}</Badge>}
                {a.cert_tier != null && (
                  <Badge data-tier={a.cert_tier} className={cn('rounded-full font-semibold text-white', TIER_BG[a.cert_tier] || 'bg-zinc-500')}>
                    T{a.cert_tier}
                  </Badge>
                )}
              </span>
            </Fact>
          )}
          <Fact label={t('alh.fact.id')}>
            <span className="inline-flex items-center gap-1 font-mono text-xs"><Hash aria-hidden="true" className="size-3 text-muted-foreground" />
              <CopyableRef value={String(a.id)} copyLabel={t('share.copyLink')} copiedLabel={t('share.copied')} />
            </span>
          </Fact>
        </dl>
        {/* İmza geçmişi: aynı hedef + tip kaçıncı kez, önceki oluşum, ne zamandır sessiz, geçmişine geçiş */}
        <div className="border-t px-4 py-2.5 empty:hidden">
          <AlertSignatureStrip alert={a} teamName={teamName} onShowHistory={onShowHistory} />
        </div>
      </Card>

      {/* 7/24 arama kayıtları (2026-09-27): izni olana hızlı giriş formu + herkese zaman çizelgesi / boş durum */}
      <NocCallSection alert={a} canWrite={nocCanWrite} focusKey={nocFocusKey + localNocFocus} onChanged={onNocChanged} />

      {/* Zaman çizelgesi — açılış/sahiplenme/çözüm alarm kaydından HEMEN; teslimatlar yüklenince araya girer */}
      <h3 className={SECTION}>{t('alh.timeline')}</h3>
      <ol data-slot="alert-timeline" data-timeline=""
        className="relative m-0 flex list-none flex-col gap-3 p-0 pl-9 before:absolute before:top-3 before:bottom-3 before:left-[15px] before:w-px before:bg-border">
        {timeline.map((ev) => {
          const s = EVENT_STYLE[ev.kind]
          return (
            <li key={ev.id} data-slot="timeline-event" data-kind={ev.kind} className="relative min-w-0">
              <span aria-hidden="true" className={cn('absolute top-0 -left-9 grid size-8 place-items-center rounded-full border', s.ink)}>
                <s.Icon className="size-4" />
              </span>
              <div className="min-w-0 rounded-lg border bg-card px-3 py-2">
                <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5">
                  <span className="text-sm font-semibold">
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
                  {ev.kind === 'mail' && ev.trigger && <Badge variant="outline" className="text-[0.7em]">{mailTriggerText(t, ev.trigger)}</Badge>}
                  {ev.kind === 'mail' && <EmailStatusBadge status={ev.status} />}
                  {ev.kind === 'push' && ev.trigger && <Badge variant="outline" className="text-[0.7em]">{t('userpush.trigger.' + ev.trigger)}</Badge>}
                  {ev.kind === 'push' && ev.status && <Badge variant="outline" className="text-[0.7em] font-normal text-muted-foreground" title={ev.status}>{statusLabel(t, ev.status)}</Badge>}
                  <span className="ml-auto text-xs whitespace-nowrap text-muted-foreground" title={ev.when || undefined}>{when(ev.when)}</span>
                </div>
                {ev.kind === 'opened' && a.message && <p className="mt-1 text-xs text-muted-foreground [overflow-wrap:anywhere]">{a.message}</p>}
                {ev.kind === 'storm' && (
                  <p data-tl-storm="" data-mail-only={ev.mailOnly ? 'true' : 'false'} className="mt-1 text-xs text-muted-foreground [overflow-wrap:anywhere]">
                    {t(ev.mailOnly ? 'alh.ev.stormMailDetail' : 'alh.ev.stormDetail', ev.stormId ?? '?')}{ev.backfilled ? ' ' + t('alh.ev.stormBackfilled') : ''}
                  </p>
                )}
                {ev.kind === 'quiet' && (
                  <p data-tl-quiet="" className="mt-1 text-xs text-muted-foreground [overflow-wrap:anywhere]">
                    {ev.resolution ? t('alh.ev.quietResolutionDetail', ev.recipient || '—') : t('alh.ev.quietDetail', ev.recipient || '—')}
                  </p>
                )}
                {ev.kind === 'acknowledged' && ev.by && <p className="mt-1 text-xs"><UserBadge username={ev.by} inline size="sm" /></p>}
                {ev.kind === 'resolved' && ev.by && <p className="mt-1 text-xs"><AlertResolvedBy by={ev.by} /></p>}
                {(ev.kind === 'acknowledged' || ev.kind === 'resolved') && ev.note && (
                  <p data-tl-note="" className="mt-1.5 rounded-md bg-muted/50 px-2 py-1 text-[0.85em] whitespace-pre-wrap text-muted-foreground italic">{ev.note}</p>
                )}
              </div>
            </li>
          )
        })}
        {deliveries.loading && (
          <li aria-hidden="true" className="relative">
            <Skeleton className="h-10 w-full rounded-lg" />
          </li>
        )}
      </ol>

      {/* Bildirim teslimatları — e-posta (önizleme sandbox iframe'de) + webhook partileri */}
      <h3 className={SECTION}>{t('alh.notifications')}</h3>
      <AlertNotificationsPanel history={deliveries.history} loadingHistory={deliveries.loading} loadFailed={deliveries.failed}
        pushGroups={pushGroups} alertLevel={a.alert_level} />
    </div>
  )
}

// Kaydırma kilidi sayaçlı (ModalShell ile aynı sözleşme): iç içe pencerede erken açılmaz.
let scrollLocks = 0
let savedOverflow = ''

/**
 * Bağımsız sayfanın detayı — sağdan açılan Sheet (telefonda tam genişlik). IncidentDetailSheet ile aynı gerekçeyle
 * `modal={false}` + kendi scrim'i: içinden açılan pencereler (Tekrar Bildir, takım üyeleri, gerekçe onayı) body'ye
 * portal'lanır ve tıklanabilir kalmalı. Kapatma: scrim, X, Escape.
 */
export function AlertDetailSheet({ alert: a, onClose, ...rest }) {
  const t = useT()
  useEffect(() => {
    const previous = document.activeElement
    return () => { if (previous && typeof previous.focus === 'function' && document.contains(previous)) previous.focus() }
  }, [])
  useEffect(() => {
    if (scrollLocks === 0) { savedOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden' }
    scrollLocks += 1
    return () => { scrollLocks -= 1; if (scrollLocks === 0) document.body.style.overflow = savedOverflow }
  }, [])
  if (!a) return null
  return (
    <Sheet open modal={false} onOpenChange={(next) => { if (!next) onClose?.() }}>
      {/* Katman: shadcn Sheet'in z-50'si yüzen yardım düğmesinin (.help-fab 900) altında kalır → 1000/1001;
          ModalShell (2000+) ve onay diyalogları (--z-dialog) yine üstte. */}
      {createPortal(
        <div data-slot="dialog-overlay" aria-hidden="true"
          className="fixed inset-0 z-[1000] bg-black/50 animate-in fade-in-0 motion-reduce:animate-none"
          onClick={(e) => { if (e.target === e.currentTarget) onClose?.() }} />,
        document.body,
      )}
      <SheetContent side="right" showCloseButton={false} data-slot="alert-detail" data-alert-id={a.id}
        aria-modal="true"
        onInteractOutside={(e) => e.preventDefault()}
        onCloseAutoFocus={(e) => e.preventDefault()}
        className="z-[1001] flex h-[100dvh] w-full flex-col gap-0 p-0 sm:w-[min(44rem,calc(100vw-2rem))] sm:max-w-none">
        <SheetHeader className="shrink-0 flex-row items-start justify-between gap-3 border-b px-4 py-3 text-left">
          <div className="min-w-0">
            <SheetTitle className="flex items-center gap-2 leading-snug">
              <AlertTypeIcon type={a.alert_type} />
              <span className="truncate">{t('alh.detailTitle', a.id)}</span>
            </SheetTitle>
            <SheetDescription className="mt-0.5 line-clamp-2 [overflow-wrap:anywhere]">{a.domain}</SheetDescription>
          </div>
          <SheetClose asChild>
            <Button type="button" variant="ghost" size="icon" className="-mt-1 -mr-2 shrink-0 text-muted-foreground" aria-label={t('app.close')}>
              <X aria-hidden="true" />
            </Button>
          </SheetClose>
        </SheetHeader>
        <div className="min-h-0 flex-1 overflow-y-auto px-4 pt-3 pb-[max(1rem,env(safe-area-inset-bottom))]">
          <AlertDetailBody alert={a} {...rest} />
        </div>
      </SheetContent>
    </Sheet>
  )
}

/**
 * Gömülü kullanımın detayı (izleme / sertifika penceresinin "Alarm Geçmişi" sekmesi) — iç içe ModalShell: derinliği
 * React ağacından okur, dış pencerenin ÜSTÜNDE açılır; içinden açılan pencereler de ondan bir kat yukarıda.
 */
export function AlertDetailModal({ alert: a, onClose, ...rest }) {
  const t = useT()
  if (!a) return null
  return (
    <ModalShell open onClose={onClose} size="lg" scrollBody
      title={(
        <span className="flex min-w-0 items-center gap-2">
          <AlertTypeIcon type={a.alert_type} />
          <span className="truncate">{t('alh.detailTitle', a.id)} · {a.domain}</span>
        </span>
      )}
      footer={<Button type="button" variant="secondary" onClick={onClose}>{t('app.close')}</Button>}>
      <div data-slot="alert-detail" data-alert-id={a.id}>
        <AlertDetailBody alert={a} {...rest} />
      </div>
    </ModalShell>
  )
}
