import { useId } from 'react'
import { UserCheck, CheckCircle2, BellPlus, CalendarClock, Mail, MailX, Clock, PhoneCall } from 'lucide-react'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { alertTypeLabel } from '../../../utils/alertTypeMeta.js'
import { durationMs, formatDuration, formatIncidentTime } from '../../../utils/incidentMeta.js'
import TeamBadge from '../../ui/TeamBadge.jsx'
import UserBadge from '../../ui/UserBadge.jsx'
import MaintenanceBadge from '../../ui/MaintenanceBadge.jsx'
import KebabMenu from '../../ui/KebabMenu.jsx'
import { AckBadge } from '../../incidents/IncidentBadges.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Label } from '@/components/shadcn/label'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import {
  AlertLevelBadge, AlertStateBadge, AlertTypeIcon, AlertTypeChip, AlertSourceLink, AlertResolvedBy, OpenDurationBadge,
  RepeatBadge, SendFailedBadge, WhyOpenChips, ActBlockedNote,
} from './AlertBadges.jsx'
import { levelClass, alertRowName } from './alertHistoryModel.js'
import { NocCallIndicator } from './NocCallLog.jsx'

/** Örtünün (başlık düğmesinin ::after'ı) ÜSTÜNDE kalması gereken etkileşimli bölge (MonitorCard / IncidentCard deseni). */
const LAYER = 'relative z-10'
/** Kart eylem düğmeleri: masaüstünde 36 px, dokunmatikte 40 px hedef. */
const ACT = 'h-9 pointer-coarse:h-10'
/** "Stretched button": kartın gerçek düğmesi; ::after tüm kartı örter, odak halkası örtüde çizilir. */
const STRETCHED = cn('flex h-auto w-full min-w-0 items-start justify-start gap-2 rounded-none px-3 py-0 text-left text-[15px] leading-snug font-bold whitespace-normal text-foreground sm:px-4',
  'hover:bg-transparent hover:text-foreground dark:hover:bg-transparent',
  'focus-visible:ring-0 after:absolute after:inset-0 after:z-0 after:rounded-xl focus-visible:after:ring-[3px] focus-visible:after:ring-ring/50')

/** Gün başlığı etiketi: Bugün / Dün / tam tarih. */
export function dayLabel(g, t, locale) {
  if (g.kind === 'today') return t('alh.day.today')
  if (g.kind === 'yesterday') return t('alh.day.yesterday')
  if (!g.date) return t('alh.ev.timeUnknown')
  try { return g.date.toLocaleDateString(locale, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }) } catch { return g.key }
}

/** Gün bölümü başlığı (liste içi h3 + sayı). */
function DayHeading({ id, label, count }) {
  return (
    <h3 id={id} data-slot="day-heading" className="mb-2 flex items-center gap-2 text-xs font-bold tracking-wide text-muted-foreground uppercase">
      {label}<Badge variant="secondary" className="h-5 rounded-full px-1.5 tabular-nums">{count}</Badge>
    </h3>
  )
}

/** Yükleme iskeleti — gerçek kart/satır yüksekliğinde (sayfa zıplamasın). */
export function ListSkeleton({ variant = 'cards' }) {
  return (
    <div data-slot="alert-skeleton" aria-hidden="true" className={cn('flex flex-col gap-2', variant === 'rows' && 'rounded-lg border bg-card p-2')}>
      {[0, 1, 2, 3, 4].map((k) => <Skeleton key={k} className={variant === 'rows' ? 'h-12 w-full' : 'h-[168px] w-full rounded-xl'} />)}
    </div>
  )
}

/**
 * AÇIK alarm kartı — eylem odaklı. Üstte önem + sahiplenme + bakım/tekrar/gönderim rozetleri ve CANLI "açık kalma";
 * başlık (tür simgesi + hedef) kartın gerçek düğmesi, detay panelini açar (stretched button — kartın her yeri tıklanır,
 * klavye tek durakta). Altta hızlı eylemler: Sahiplen · Çöz (ikisi de gerekçe ister) · Tekrar bildir · İzlemeyi aç · menü.
 * Sol renk şeridi YOK: seviye rozetle; kritik alarm kartın TAMAMINI çerçeveler; seçili/derin-bağlantılı kart birincil çerçeve.
 * `actBlocked` (neden anahtarı ya da boş — alertHistoryModel.actBlockReason): 7/24 operatörü başka takımın uyarısını görür
 * ama sahiplenemez/çözemez/yeniden bildiremez (sunucu 403), ya da rolün `alerts.actions` izni yok (AUDIT, 2026-09-28) →
 * o üç düğme ve seçim kutusu YOK, yerine neden notu (ActBlockedNote); "Arama kaydet" kalır.
 * Test kancaları: `data-alert-card`, `data-level`, `data-linked`, `data-alert-open`, `data-alert-actions`, `data-audit-note`,
 * `data-slot="alert-act-blocked"`.
 */
export function OpenAlertCard({
  alert: a, nowMs, staleHours, teamName, push, selectable = true, selected = false, onToggleSelect, highlighted = false,
  linked = false, onOpen, onAck, onResolve, onReNotify, notifying = false, menuItems, onLogCall, actBlocked = false,
}) {
  const t = useT()
  const locale = useDateLocale()
  const checkId = useId()
  const lvl = levelClass(a.alert_level)
  const name = alertRowName(a, t)
  const rowLabel = (label) => t('a11y.rowAction', name, label)
  const team = a.team_id != null
    ? <TeamBadge as="span" teamId={a.team_id} teamName={teamName || undefined} />
    : a.sy_team_name ? <TeamBadge as="span" teamId={a.sy_team_id} teamName={a.sy_team_name} /> : null

  return (
    <Card data-alert-card="" data-status="open" data-level={lvl} data-alert-id={a.id}
      data-linked={linked ? 'true' : undefined} data-selected={highlighted || undefined}
      className={cn('relative min-w-0 gap-0 overflow-hidden py-0 shadow-xs transition-colors hover:border-primary/50',
        lvl === 'critical' && 'border-(--severity-critical)',
        (highlighted || linked) && 'border-primary ring-2 ring-primary/40')}>
      <div className="flex min-w-0 flex-wrap items-center gap-1.5 px-3 pt-3 sm:px-4">
        {selectable && !actBlocked && (
          // Etiket kutunun dokunma alanını büyütür (dokunmatikte 40 px); ad alarmı ayırır (R5).
          <Label htmlFor={checkId} className={cn(LAYER, '-m-1.5 cursor-pointer p-1.5 pointer-coarse:-m-3 pointer-coarse:p-3')}>
            <Checkbox id={checkId} checked={selected} onCheckedChange={() => onToggleSelect?.(a.id)}
              aria-label={t('alh.bulk.selectOneFor', a.domain, alertTypeLabel(t, a.alert_type))} />
          </Label>
        )}
        <AlertLevelBadge level={a.alert_level} />
        {a.acknowledged && <AckBadge />}
        <MaintenanceBadge target={a.domain} />
        <RepeatBadge count={a.repeat_count} />
        <SendFailedBadge count={a.email_failed_count} />
        {a.days_remaining != null && (
          <Badge variant="outline" className="rounded-full border-destructive/30 bg-destructive/10 font-bold text-destructive">{t('alh.days', a.days_remaining)}</Badge>
        )}
        <OpenDurationBadge className="ml-auto" createdAt={a.created_at} staleHours={staleHours} nowMs={nowMs} />
      </div>

      <Button type="button" variant="ghost" data-alert-open="" onClick={() => onOpen(a)}
        aria-label={t('alh.openAlert', name)} title={t('alh.openDetail')} className={cn(STRETCHED, 'mt-2')}>
        <AlertTypeIcon type={a.alert_type} className="mt-px" />
        <span className="line-clamp-2 min-w-0 pt-0.5 [overflow-wrap:anywhere]">{a.domain}</span>
      </Button>

      {a.message && <p className="mt-1 line-clamp-2 px-3 text-[13px] text-muted-foreground [overflow-wrap:anywhere] sm:px-4" title={a.message}>{a.message}</p>}

      <div className="mt-2 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 px-3 text-xs text-muted-foreground sm:px-4">
        <AlertTypeChip type={a.alert_type} />
        {team ? <span className={cn(LAYER, 'min-w-0')}>{team}</span> : <span className="italic">{t('alh.noTeam')}</span>}
        <span className="inline-flex items-center gap-1 whitespace-nowrap" title={a.created_at}>
          <CalendarClock aria-hidden="true" className="size-3.5" />{formatIncidentTime(a.created_at, locale)}
        </span>
        {a.last_re_alert_at && (
          <span className="inline-flex items-center gap-1 whitespace-nowrap" title={a.last_re_alert_at}>
            <Mail aria-hidden="true" className="size-3.5" />{t('alh.lastNotif')} {formatIncidentTime(a.last_re_alert_at, locale)}
          </span>
        )}
      </div>

      <WhyOpenChips alert={a} push={push} className="mt-2 px-3 sm:px-4" />

      {Number(a.noc_call_count) > 0 && (
        <div className="mt-2 flex min-w-0 px-3 sm:px-4">
          <NocCallIndicator count={a.noc_call_count} last={a.noc_last_call} />
        </div>
      )}

      {a.acknowledged && (
        <div data-slot="alert-ack-line" className="mx-3 mt-2 rounded-md border border-sky-500/20 bg-sky-500/5 px-2.5 py-1.5 text-xs sm:mx-4">
          <span className="inline-flex flex-wrap items-center gap-1.5">
            <UserCheck aria-hidden="true" className="size-3.5 text-sky-700 dark:text-sky-300" />
            <span className="font-semibold">{t('alh.ackedBy')}</span>
            <UserBadge username={a.acknowledged_by} inline size="sm" />
            {a.acknowledged_at && <span className="text-muted-foreground">· {formatIncidentTime(a.acknowledged_at, locale)}</span>}
          </span>
          {a.acknowledged_note && (
            <span data-audit-note="" className="mt-1 block whitespace-pre-wrap text-muted-foreground italic">{a.acknowledged_note}</span>
          )}
        </div>
      )}

      <div data-alert-actions="" className={cn(LAYER, 'mt-3 flex flex-wrap items-center gap-2 border-t bg-muted/20 px-3 py-2.5 sm:px-4')}>
        {actBlocked ? (
          <ActBlockedNote reason={actBlocked === true ? 'team' : actBlocked} />
        ) : (
          <>
            {!a.acknowledged && (
              <Button type="button" variant="outline" size="sm" className={ACT} aria-label={rowLabel(t('alh.ack'))} onClick={() => onAck(a)}>
                <UserCheck aria-hidden="true" />{t('alh.ack')}
              </Button>
            )}
            <Button type="button" variant="success" size="sm" className={ACT} aria-label={rowLabel(t('alh.resolveAction'))} onClick={() => onResolve(a)}>
              <CheckCircle2 aria-hidden="true" />{t('alh.resolveAction')}
            </Button>
            <Button type="button" variant="outline" size="sm" className={ACT} aria-label={rowLabel(t('alh.renotify'))}
              onClick={() => onReNotify(a)} disabled={notifying} aria-busy={notifying || undefined}>
              <BellPlus aria-hidden="true" />{notifying ? t('alh.sending') : t('alh.renotify')}
            </Button>
          </>
        )}
        {onLogCall && (
          <Button type="button" variant="outline" size="sm" className={ACT} data-noc-log-call="" aria-label={rowLabel(t('nocCall.logCall'))}
            onClick={() => onLogCall(a)}>
            <PhoneCall aria-hidden="true" />{t('nocCall.logCall')}
          </Button>
        )}
        <span className="ml-auto flex items-center gap-1">
          <AlertSourceLink alert={a} iconOnly variant="ghost" size="icon" className="size-9 text-muted-foreground pointer-coarse:size-10"
            ariaLabel={rowLabel(t('alh.openMonitor'))} />
          <KebabMenu items={menuItems(a)} label={t('alh.moreActions')} rowLabel={name} />
        </span>
      </div>
    </Card>
  )
}

/** Açık kartların gün gruplu listesi. */
export function OpenAlertList({ groups, renderCard }) {
  const t = useT()
  const locale = useDateLocale()
  const uid = useId()
  return (
    <div data-slot="alert-list" data-variant="cards" className="flex min-w-0 flex-col gap-4">
      {groups.map((g, i) => (
        <section key={g.key} aria-labelledby={`${uid}-${i}`} className="min-w-0">
          <DayHeading id={`${uid}-${i}`} label={dayLabel(g, t, locale)} count={g.items.length} />
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {g.items.map((a) => <li key={a.id} className="min-w-0">{renderCard(a)}</li>)}
          </ul>
        </section>
      ))}
    </div>
  )
}

/** Kapalı alarmın özet gövdesi (satır ve telefon kartı ortak): süre + kim/ne kapattı + not önizlemesi. */
function ResolutionSummary({ alert: a, nowMs, withDuration = false }) {
  const t = useT()
  const locale = useDateLocale()
  return (
    <span className="flex min-w-0 flex-col gap-0.5">
      {a.resolved_by ? <AlertResolvedBy by={a.resolved_by} /> : <span className="text-muted-foreground">—</span>}
      {a.resolved_note && (
        <span data-slot="resolve-note" className="line-clamp-1 text-xs text-muted-foreground italic [overflow-wrap:anywhere]" title={a.resolved_note}>{a.resolved_note}</span>
      )}
      {a.resolved_at && (
        <span className="text-[11px] text-muted-foreground tabular-nums" title={a.resolved_at}>
          {formatIncidentTime(a.resolved_at, locale)}{withDuration && <> · {t('alh.lastedFor', formatDuration(durationMs(a.created_at, a.resolved_at, nowMs), t))}</>}
        </span>
      )}
    </span>
  )
}

/** Gönderim sayacı: e-posta başarılı / başarısız (kırmızı). */
function MailCounts({ alert: a }) {
  const t = useT()
  const sent = Number(a.email_sent_count ?? 0), failed = Number(a.email_failed_count ?? 0)
  return (
    <span className="inline-flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs whitespace-nowrap tabular-nums">
      <span className="inline-flex items-center gap-1"><Mail aria-hidden="true" className="size-3.5 text-muted-foreground" />{t('alh.mailsSent', sent)}</span>
      {failed > 0 && <span className="inline-flex items-center gap-1 text-destructive"><MailX aria-hidden="true" className="size-3.5" />{t('alh.mailsFailed', failed)}</span>}
    </span>
  )
}

const TH = 'h-9 px-3 text-[0.74em] font-semibold tracking-wide text-muted-foreground uppercase'

/**
 * KAPALI / TÜMÜ görünümü — özet satırlar. md+ shadcn Table (gün başlık satırlarıyla), telefonda aynı özet kart olarak.
 * Satır tıklaması/Enter detayı açar; ↑/↓ listeyi gezer (çağıranın kabı); satır eylemleri tek KebabMenu'de (adı satırı ayırır).
 * Sütunlar: seviye · alarm (tür + hedef + mesaj) · [durum] · açıldı · süre · çözen (+ not önizlemesi) · bildirim · işlemler.
 * Test kancaları: `data-alert-row` + `data-history-card` (satır), `data-slot="day-group"`, `data-slot="resolve-note"`.
 */
export function AlertRowsList({ groups, tab, nowMs, onOpen, activeId, linkedId, menuItems, phone = false }) {
  const t = useT()
  const locale = useDateLocale()
  const all = tab === 'all'

  const onRowKey = (e, a) => {
    if (e.target !== e.currentTarget) return
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpen(a) }
  }

  if (phone) {
    return (
      <div data-slot="alert-list" data-variant="rows" className="flex min-w-0 flex-col gap-4">
        {groups.map((g) => (
          <section key={g.key} className="min-w-0">
            <DayHeading label={dayLabel(g, t, locale)} count={g.items.length} />
            <ul className="m-0 flex list-none flex-col gap-2 p-0">
              {g.items.map((a) => {
                const name = alertRowName(a, t)
                const on = String(activeId) === String(a.id) || String(linkedId) === String(a.id)
                return (
                  <li key={a.id} className="min-w-0">
                    <Card data-alert-row="" data-history-card="" data-status={a.resolved ? 'resolved' : 'open'} data-alert-id={a.id}
                      data-linked={String(linkedId) === String(a.id) ? 'true' : undefined}
                      className={cn('relative min-w-0 gap-0 overflow-hidden py-0 shadow-xs', on && 'border-primary ring-2 ring-primary/40')}>
                      <div className="flex min-w-0 flex-wrap items-center gap-1.5 px-3 pt-2.5">
                        <AlertLevelBadge level={a.alert_level} />
                        {all && <AlertStateBadge resolved={a.resolved} />}
                        <RepeatBadge count={a.repeat_count} />
                        <span className={cn(LAYER, 'ml-auto')}><KebabMenu items={menuItems(a)} label={t('alh.moreActions')} rowLabel={name} /></span>
                      </div>
                      <Button type="button" variant="ghost" data-alert-open="" onClick={() => onOpen(a)}
                        aria-label={t('alh.openAlert', name)} title={t('alh.openDetail')} className={cn(STRETCHED, 'mt-1.5 px-3 sm:px-3')}>
                        <AlertTypeIcon type={a.alert_type} className="mt-px" />
                        <span className="line-clamp-2 min-w-0 pt-0.5 [overflow-wrap:anywhere]">{a.domain}</span>
                      </Button>
                      {a.message && <p className="mt-1 line-clamp-1 px-3 text-xs text-muted-foreground [overflow-wrap:anywhere]">{a.message}</p>}
                      <div className="mt-2 flex min-w-0 flex-col gap-1.5 border-t px-3 py-2 text-xs">
                        {a.resolved
                          ? <ResolutionSummary alert={a} nowMs={nowMs} withDuration />
                          : <span className="inline-flex items-center gap-1 text-muted-foreground"><Clock aria-hidden="true" className="size-3.5" />{t('alh.openFor', formatDuration(durationMs(a.created_at, null, nowMs), t))}</span>}
                        <MailCounts alert={a} />
                        <NocCallIndicator count={a.noc_call_count} last={a.noc_last_call} className="self-start" />
                      </div>
                    </Card>
                  </li>
                )
              })}
            </ul>
          </section>
        ))}
      </div>
    )
  }

  const cols = all ? 8 : 7
  return (
    <div data-slot="alert-list" data-variant="rows" className="overflow-hidden rounded-lg border bg-card">
      <Table className="text-[0.88em]">
        <TableHeader className="bg-muted/50">
          <TableRow className="hover:bg-transparent">
            <TableHead className={cn(TH, 'w-[7.5rem]')}>{t('alh.col.level')}</TableHead>
            <TableHead className={cn(TH, 'min-w-[18rem]')}>{t('alh.col.alert')}</TableHead>
            {all && <TableHead className={cn(TH, 'w-[8rem]')}>{t('alh.col.state')}</TableHead>}
            <TableHead className={cn(TH, 'hidden whitespace-nowrap lg:table-cell')}>{t('alh.col.opened')}</TableHead>
            <TableHead className={cn(TH, 'whitespace-nowrap')}>{t('alh.col.duration')}</TableHead>
            <TableHead className={cn(TH, 'min-w-[11rem]')}>{t('alh.col.resolvedBy')}</TableHead>
            <TableHead className={cn(TH, 'hidden xl:table-cell')}>{t('alh.col.notifs')}</TableHead>
            <TableHead className={cn(TH, 'w-12')}><span className="sr-only">{t('alh.col.actions')}</span></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {groups.map((g) => [
            <TableRow key={`g-${g.key}`} data-slot="day-group" className="bg-muted/30 hover:bg-muted/30">
              <TableCell colSpan={cols} className="py-1.5 text-xs font-bold tracking-wide text-muted-foreground uppercase">
                {dayLabel(g, t, locale)} <Badge variant="secondary" className="ml-1.5 h-5 rounded-full px-1.5 tabular-nums">{g.items.length}</Badge>
              </TableCell>
            </TableRow>,
            ...g.items.map((a) => {
              const name = alertRowName(a, t)
              const active = String(activeId) === String(a.id)
              const linked = String(linkedId) === String(a.id)
              return (
                <TableRow key={a.id} data-alert-row="" data-history-card="" data-alert-open="" data-alert-id={a.id}
                  data-status={a.resolved ? 'resolved' : 'open'} data-linked={linked ? 'true' : undefined}
                  data-state={active || linked ? 'selected' : undefined}
                  tabIndex={0} title={t('alh.openDetail')}
                  className={cn('cursor-pointer outline-none focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary',
                    (active || linked) && 'bg-primary/10 outline outline-2 -outline-offset-2 outline-primary/60 hover:bg-primary/15')}
                  onClick={() => onOpen(a)} onKeyDown={(e) => onRowKey(e, a)}>
                  <TableCell><AlertLevelBadge level={a.alert_level} /></TableCell>
                  <TableCell className="max-w-[30rem] min-w-[18rem] whitespace-normal">
                    <div className="flex min-w-0 items-start gap-2">
                      <AlertTypeIcon type={a.alert_type} />
                      <div className="min-w-0">
                        <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                          <span className="line-clamp-2 font-semibold [overflow-wrap:anywhere]" title={a.domain}>{a.domain}</span>
                          <MaintenanceBadge target={a.domain} />
                          <RepeatBadge count={a.repeat_count} />
                        </div>
                        <div className="mt-0.5 flex min-w-0 flex-wrap items-center gap-x-2 text-xs text-muted-foreground">
                          <AlertTypeChip type={a.alert_type} />
                          {a.message && <span className="line-clamp-1 [overflow-wrap:anywhere]" title={a.message}>{a.message}</span>}
                        </div>
                        {Number(a.noc_call_count) > 0 && (
                          <div className="mt-1 flex min-w-0"><NocCallIndicator count={a.noc_call_count} last={a.noc_last_call} /></div>
                        )}
                      </div>
                    </div>
                  </TableCell>
                  {all && (
                    <TableCell>
                      <span className="flex flex-wrap gap-1">
                        <AlertStateBadge resolved={a.resolved} />
                        {!a.resolved && a.acknowledged && <AckBadge />}
                      </span>
                    </TableCell>
                  )}
                  <TableCell className="hidden text-[0.92em] whitespace-nowrap text-muted-foreground lg:table-cell" title={a.created_at}>
                    {formatIncidentTime(a.created_at, locale)}
                  </TableCell>
                  <TableCell className="whitespace-nowrap tabular-nums">
                    {formatDuration(durationMs(a.created_at, a.resolved_at, nowMs), t)}
                  </TableCell>
                  <TableCell className="max-w-[16rem] whitespace-normal">
                    {a.resolved ? <ResolutionSummary alert={a} nowMs={nowMs} /> : <span className="text-muted-foreground">—</span>}
                  </TableCell>
                  <TableCell className="hidden xl:table-cell"><MailCounts alert={a} /></TableCell>
                  <TableCell className="text-right" onClick={(e) => e.stopPropagation()}>
                    <KebabMenu items={menuItems(a)} label={t('alh.moreActions')} rowLabel={name} />
                  </TableCell>
                </TableRow>
              )
            }),
          ])}
        </TableBody>
      </Table>
    </div>
  )
}

