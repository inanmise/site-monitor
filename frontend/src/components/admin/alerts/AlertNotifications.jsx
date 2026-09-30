import { useState } from 'react'
import { ShieldAlert, TrendingUp, RefreshCcw, Bell, CheckCircle, Mail, ChevronUp, ChevronDown, CloudLightning } from 'lucide-react'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { mailPreviewSrcDoc, mailLogoVariant, MAIL_PREVIEW_SANDBOX } from '../../../utils/mailPreview.js'
import UserBadge from '../../ui/UserBadge.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import ToneBadge, { SystemRoleBadge } from '../ToneBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/shadcn/accordion'
import { cn } from '@/lib/utils'
import { fmtStamp, statusLabel } from './alertHistoryModel.js'

/**
 * Bildirim teslimatları — e-posta günlüğü (mail önizlemesi sandbox iframe'de) ve webhook (push) partileri.
 * Detay panelinin "Bildirimler" bölümü: iki akordiyon (ikisi de KAPALI açılır, tıklanan açılır — kullanıcı isteği).
 * Test kancaları: `data-notif-card=<ton>`, `data-notif-head`, `data-notif-body`, `data-push-status`, `data-who`,
 * `data-who-id`, `data-slot="accordion-trigger"` (iki bölüm).
 */

/** Bildirim kartı tetik tonları (koyu tema karşılıklarıyla). */
const NOTIF_TONE = {
  initial:    { card: 'border-red-300 dark:border-red-900',       open: 'bg-red-500/5',    badge: 'border-red-300 bg-red-500/10 text-red-700 dark:border-red-900 dark:text-red-300' },
  escalation: { card: 'border-orange-300 dark:border-orange-900', open: 'bg-orange-500/5', badge: 'border-orange-300 bg-orange-500/10 text-orange-700 dark:border-orange-900 dark:text-orange-300' },
  daily:      { card: 'border-amber-300 dark:border-amber-900',   open: 'bg-amber-500/5',  badge: 'border-amber-300 bg-amber-500/10 text-amber-800 dark:border-amber-900 dark:text-amber-300' },
  manual:     { card: 'border-blue-300 dark:border-blue-900',     open: 'bg-blue-500/5',   badge: 'border-blue-300 bg-blue-500/10 text-blue-700 dark:border-blue-900 dark:text-blue-300' },
  resolution: { card: 'border-green-300 dark:border-green-900',   open: 'bg-green-500/5',  badge: 'border-green-300 bg-green-500/10 text-green-700 dark:border-green-900 dark:text-green-300' },
  storm:      { card: 'border-violet-300 dark:border-violet-900', open: 'bg-violet-500/5', badge: 'border-violet-300 bg-violet-500/10 text-violet-700 dark:border-violet-900 dark:text-violet-300' },
  other:      { card: 'border-border',                           open: 'bg-muted/40',     badge: 'border-border bg-muted text-muted-foreground' },
}

/** Push tetiği → ton anahtarı. */
const PUSH_TRIGGER_CLS = { OPEN: 'initial', ESCALATION: 'escalation', RE_ALERT: 'daily', RESEND: 'manual', RESOLVE: 'resolution' }

/** E-posta tetikleyicisi → ikon + etiket + ton. */
const MAIL_TRIGGER = {
  INITIAL:       { Icon: ShieldAlert, textKey: 'alh.trigger.initial',    cls: 'initial' },
  ESCALATION:    { Icon: TrendingUp,  textKey: 'alh.trigger.escalation', cls: 'escalation' },
  DAILY_REALERT: { Icon: RefreshCcw,  textKey: 'alh.trigger.daily',      cls: 'daily' },
  MANUAL:        { Icon: Bell,        textKey: 'alh.trigger.manual',     cls: 'manual' },
  RESOLUTION:    { Icon: CheckCircle, textKey: 'alh.trigger.resolution', cls: 'resolution' },
  // 2026-09-30: fırtına devri KARARI (e-posta değil) ve fırtına postaları (açılış / günlük tekrar / çözüm) — üye alarmın
  // günlüğünde görünür; eskiden fırtına postası hiçbir yere yazılmıyordu.
  STORM:         { Icon: CloudLightning, textKey: 'alh.trigger.storm',        cls: 'storm' },
  STORM_INITIAL: { Icon: CloudLightning, textKey: 'alh.trigger.stormInitial', cls: 'storm' },
  STORM_REALERT: { Icon: CloudLightning, textKey: 'alh.trigger.stormRealert', cls: 'storm' },
  STORM_RESOLVE: { Icon: CloudLightning, textKey: 'alh.trigger.stormResolve', cls: 'resolution' },
}

/** E-posta tetiğinin okunur adı (bilinmeyen tetik ham adıyla). */
export function mailTriggerText(t, trigger) {
  const base = MAIL_TRIGGER[trigger]
  return base ? t(base.textKey) : (trigger || '')
}

export function EmailStatusBadge({ status }) {
  const t = useT()
  if (!status) return null
  if (status === 'SENT') return <ToneBadge tone="success">{t('alh.status.sent')}</ToneBadge>
  if (status === 'SKIPPED_DISABLED') return <ToneBadge tone="warning">{t('alh.status.skip')}</ToneBadge>
  if (status.startsWith('FAILED')) return <ToneBadge tone="danger" title={status}>{t('alh.status.failed')}</ToneBadge>
  // "SKIPPED: <neden>" — atlanan e-posta, nedeniyle (alıcı yok / kanal kapalı / fırtına #N / takım yok). 2026-09-30.
  if (status.startsWith('SKIPPED:')) {
    const reason = status.slice('SKIPPED:'.length).trim()
    return <ToneBadge tone="warning" title={status}>{t('alh.status.skipped')}{reason ? ' — ' + reason : ''}</ToneBadge>
  }
  return <ToneBadge tone="muted">{status}</ToneBadge>
}

/** Tıklanınca açılan teslimat kartı — Collapsible + Card; e-posta ve webhook aynı kabı kullanır. */
function NotifCard({ tone, open, onOpenChange, head, children }) {
  const style = NOTIF_TONE[tone] ?? NOTIF_TONE.other
  return (
    <Collapsible open={open} onOpenChange={onOpenChange} data-notif-card={tone} className="min-w-0">
      <Card className={cn('gap-0 overflow-hidden py-0 shadow-none', style.card, open && style.open)}>
        <CollapsibleTrigger data-notif-head=""
          className="flex w-full cursor-pointer flex-wrap items-center gap-2 px-3 py-2 text-left text-sm hover:bg-muted/40 focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none">
          {head}
          <span className="text-muted-foreground">{open ? <ChevronUp className="size-3" aria-hidden="true" /> : <ChevronDown className="size-3" aria-hidden="true" />}</span>
        </CollapsibleTrigger>
        <CollapsibleContent data-notif-body="" className="flex flex-col gap-1.5 border-t px-3 py-2.5 text-sm">
          {children}
        </CollapsibleContent>
      </Card>
    </Collapsible>
  )
}

/** Kart gövdesi: etiket–değer satırı (`body` → değer alt satıra, tam genişlik). */
function DetailRow({ label, body = false, children }) {
  return (
    <div className={cn('flex min-w-0 gap-3', body ? 'flex-col gap-1' : 'items-baseline')}>
      <span className="min-w-24 shrink-0 text-[0.72em] font-bold tracking-wider text-muted-foreground uppercase">{label}</span>
      <span className="min-w-0 break-words">{children}</span>
    </div>
  )
}

/** Tek webhook gönderimi (ya da aynı mesajı alan alıcı grubu) — açılınca GERÇEKTEN giden metin. */
export function PushDeliveryGroup({ rows }) {
  const t = useT()
  const locale = useDateLocale()
  const [open, setOpen] = useState(false)
  const head = rows[0]
  const many = rows.length > 1
  const uniqueRecipients = new Set(rows.map((r) => r.username)).size
  const uniqueRows = rows.filter((r, i) => r.username === '-' || rows.findIndex((x) => x.username === r.username) === i)
  const cls = PUSH_TRIGGER_CLS[head.trigger] ?? 'other'
  const statusTone = head.status === 'SENT' ? 'success' : (head.status === 'FAILED' || head.status === 'CIRCUIT_OPEN') ? 'danger' : 'muted'
  const who = (p) => (p.username === '-'
    ? <Badge key={p.id} variant="outline" data-who="system" className="font-normal text-muted-foreground"><em>{t('userpush.systemRow')}</em></Badge>
    : (
      <Badge key={p.id} variant="outline" data-who="" className="gap-1.5 font-normal">
        <UserBadge username={p.username} displayName={p.display_name} size="sm" inline nameOnly />
        <span data-who-id="" className="font-mono text-muted-foreground">{p.username}</span>
      </Badge>
    ))

  return (
    <NotifCard tone={cls} open={open} onOpenChange={setOpen} head={<>
      <ToneBadge tone={statusTone} data-push-status="" title={head.status} className="font-bold">{statusLabel(t, head.status)}</ToneBadge>
      <div className="flex min-w-0 flex-1 flex-wrap items-center gap-1.5">
        {many ? <strong>{t('alh.push.recipients', uniqueRecipients)}</strong> : who(head)}
      </div>
      <span className="text-[0.78em] text-muted-foreground">{t('userpush.trigger.' + head.trigger)}</span>
      <span className="text-xs whitespace-nowrap text-muted-foreground tabular-nums">{fmtStamp(head.sent_at || head.created_at, locale)}</span>
    </>}>
      <DetailRow label={t('alh.push.message')} body><span className="whitespace-pre-wrap">{head.message || '—'}</span></DetailRow>
      {many && <DetailRow label={t('alh.push.who')} body><span className="flex flex-wrap gap-1.5">{uniqueRows.map(who)}</span></DetailRow>}
      {head.http_status != null && <DetailRow label="HTTP">{head.http_status}</DetailRow>}
    </NotifCard>
  )
}

/** Tek e-posta gönderimi — konu, alıcılar, içerik (HTML ise sandbox iframe önizlemesi). */
export function NotifLogCard({ log: l, alertLevel }) {
  const t = useT()
  const locale = useDateLocale()
  const [open, setOpen] = useState(false)
  const base = MAIL_TRIGGER[l.trigger]
  const trig = base ? { ...base, text: t(base.textKey) } : { Icon: Mail, text: l.trigger, cls: 'other' }
  const tone = NOTIF_TONE[trig.cls] ?? NOTIF_TONE.other
  const sentDate = fmtStamp(l.sent_at, locale)

  return (
    <NotifCard tone={trig.cls} open={open} onOpenChange={setOpen} head={<>
      <Badge variant="outline" className={cn('font-bold', tone.badge)}><trig.Icon aria-hidden="true" className="size-3" /> {trig.text}</Badge>
      <div className="flex min-w-0 flex-1 flex-wrap items-center gap-1.5">
        <strong className="truncate">{l.recipient_name}</strong>
        {l.recipient_role && l.recipient_role !== 'COMBINED' && <SystemRoleBadge role={l.recipient_role} className="text-[0.75em]" />}
        <span className="truncate text-xs text-muted-foreground">{l.recipient_email}</span>
      </div>
      <EmailStatusBadge status={l.email_status} />
      <span className="text-xs whitespace-nowrap text-muted-foreground tabular-nums">{sentDate}</span>
    </>}>
      {l.email_from && <DetailRow label={t('alh.notif.from')}>{l.email_from}</DetailRow>}
      {l.recipient_email && <DetailRow label={t('alh.notif.to')}>{l.recipient_email}</DetailRow>}
      {l.cc && <DetailRow label={t('alh.notif.cc')}>{l.cc}</DetailRow>}
      <DetailRow label={t('alh.notif.subject')}><span className="font-semibold">{l.subject || '—'}</span></DetailRow>
      <DetailRow label={t('alh.notif.content')} body>
        {l.message && l.message.trimStart().startsWith('<') ? (
          <iframe className="h-[420px] w-full rounded-md border bg-white" title={l.subject}
            srcDoc={mailPreviewSrcDoc(l.message, { logoVariant: mailLogoVariant({ trigger: l.trigger, level: alertLevel }) })}
            sandbox={MAIL_PREVIEW_SANDBOX} />
        ) : <span className="whitespace-pre-wrap">{l.message || '—'}</span>}
      </DetailRow>
      <DetailRow label={t('alh.notif.emailStatus')}>
        <EmailStatusBadge status={l.email_status} />
        {l.email_status?.startsWith('FAILED') && <span className="ml-2 text-xs break-all text-destructive">{l.email_status.replace('FAILED: ', '')}</span>}
      </DetailRow>
      {l.webhook_status && l.webhook_status !== 'SKIPPED' && <DetailRow label={t('alh.notif.webhook')}>{l.webhook_status}</DetailRow>}
      <DetailRow label={t('alh.notif.sentAt')}>{sentDate}</DetailRow>
    </NotifCard>
  )
}

/** Bölüm başlığındaki sayaç rozeti. */
function Count({ muted = false, children }) {
  return <Badge variant="secondary" className={cn('rounded-full font-semibold', muted && 'text-muted-foreground')}>{children}</Badge>
}
const EMPTY = 'py-2.5 text-sm text-muted-foreground'
const SECTION_TRIGGER = 'justify-start gap-2 py-3 font-semibold hover:no-underline [&>svg:last-child]:ml-auto'

/**
 * Bildirimler bölümü — e-posta geçmişi + webhook partileri; her ikisi kapalı açılır, tıklanan açılır.
 * Veriyi detay paneli yükler (tek istek çifti), burası yalnız çizer.
 */
export default function AlertNotificationsPanel({ history, loadingHistory, loadFailed, pushGroups, alertLevel }) {
  const t = useT()
  const [openSection, setOpenSection] = useState('')
  const sentGroups = pushGroups.filter((g) => g[0]?.status === 'SENT').length
  const skippedGroups = pushGroups.length - sentGroups

  return (
    <Accordion type="single" collapsible value={openSection} onValueChange={setOpenSection} data-slot="alert-notifications">
      <AccordionItem value="email">
        <AccordionTrigger className={SECTION_TRIGGER}>
          {t('alh.notifModal.allHistory')}
          {!loadingHistory && <Count>{t('alh.notifModal.records', history.length)}</Count>}
        </AccordionTrigger>
        <AccordionContent className="flex flex-col gap-2">
          {loadingHistory && <div className={EMPTY}>{t('alh.loading')}</div>}
          {!loadingHistory && loadFailed && <AlertBanner tone="danger" role="alert" className="mb-0">{t('alh.loadError')}</AlertBanner>}
          {!loadingHistory && !loadFailed && history.length === 0 && <div className={EMPTY}>{t('alh.notifModal.noNotifs')}</div>}
          {!loadingHistory && history.map((l, i) => <NotifLogCard key={l.id ?? i} log={l} alertLevel={alertLevel} />)}
        </AccordionContent>
      </AccordionItem>
      <AccordionItem value="push">
        <AccordionTrigger className={SECTION_TRIGGER}>
          {t('alh.webhookSection')}
          <Count>{t('alh.push.sendCount', sentGroups)}</Count>
          {skippedGroups > 0 && <Count muted>{t('alh.push.skipCount', skippedGroups)}</Count>}
        </AccordionTrigger>
        <AccordionContent className="flex flex-col gap-2">
          {pushGroups.length === 0 && <div className={EMPTY}>{t('alh.webhookNone')}</div>}
          {pushGroups.map((g, i) => <PushDeliveryGroup key={g[0].id ?? i} rows={g} />)}
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  )
}
