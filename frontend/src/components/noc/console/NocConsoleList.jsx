import { BellRing, CheckCircle2, ExternalLink, Mail, PhoneCall, PhoneMissed, Smartphone, Webhook } from 'lucide-react'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { navigateTo } from '../../../utils/navigate.js'
import { AlertLevelBadge, AlertTypeIcon, NocSentBadge } from '../../admin/alerts/AlertBadges.jsx'
import { NocOutcomeBadge } from '../../admin/alerts/NocCallLog.jsx'
import { alertTypeLabel } from '../../../utils/alertTypeMeta.js'
import { relTime, fullTime } from '../../admin/alerts/nocCallModel.js'
import { alertHref, channelSummary, monitorHref, monitorTarget, rowUrgency } from './nocConsoleModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

const CH_ICON = { email: Mail, webhook: Webhook, push: Smartphone }

/** Düz tık uygulama içinde gezinir; Ctrl/⌘/orta tık yeni sekme (tarayıcıya bırakılır). */
function goOnPlainClick(e, tab, params) {
  if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
  e.preventDefault()
  navigateTo(tab, params)
}

/** Kanal özeti: takım e-postası / webhook / kişisel push sayıları (+ başarısız) ve 7/24 iletimi. */
function Channels({ row, t }) {
  const items = channelSummary(row).filter((c) => c.key !== 'noc')
  return (
    <span data-slot="noc-con-channels" className="flex min-w-0 flex-wrap items-center gap-1">
      {items.map(({ key, sent, failed }) => {
        const Icon = CH_ICON[key]
        const label = t(`noc.con.ch.${key}`, sent, failed)
        return (
          <Badge key={key} variant="outline" data-ch={key} data-sent={sent} title={label}
            className={cn('gap-1 font-normal tabular-nums', sent === 0 && 'text-muted-foreground', failed > 0 && 'border-destructive/40 text-destructive')}>
            <Icon aria-hidden="true" className="size-3" />{sent}
            {failed > 0 && <span aria-hidden="true" className="font-semibold">/{failed}</span>}
            <span className="sr-only">{label}</span>
          </Badge>
        )
      })}
      {row.noc
        ? <NocSentBadge sentAt={row.noc.sent_at} viaStorm={row.noc.via_storm} />
        : <Badge variant="outline" data-slot="noc-con-not-sent" className="font-normal text-muted-foreground">{t('noc.con.notSent')}</Badge>}
    </span>
  )
}

/** Son arama: kim arandı · sonuç · ne zaman (göreli; tam an title'da) · kim girdi; arama yoksa "aranmadı". */
function LastCall({ row, nowMs, t, locale }) {
  const c = row.last_call
  if (!c) {
    const urgent = rowUrgency(row) === 'needs_call'
    return (
      <span data-slot="noc-con-no-call" className={cn('inline-flex items-center gap-1 text-xs', urgent ? 'font-semibold text-amber-700 dark:text-amber-300' : 'text-muted-foreground')}>
        <PhoneMissed aria-hidden="true" className="size-3.5" />{urgent ? t('noc.con.needsCall') : t('noc.con.noCall')}
      </span>
    )
  }
  return (
    <span data-slot="noc-con-last-call" className="flex min-w-0 flex-col gap-0.5 text-xs">
      <span className="flex min-w-0 flex-wrap items-center gap-1">
        <NocOutcomeBadge outcome={c.outcome} className="text-[0.95em]" />
        <span className="truncate font-medium" title={c.contacted_name}>{c.contacted_name}</span>
      </span>
      <span className="text-muted-foreground" title={fullTime(c.contacted_at, locale)}>
        {relTime(c.contacted_at, nowMs, t)}{c.created_by_name ? ` · ${t('noc.con.by', c.created_by_name)}` : ''}
        {Number(row.call_count) > 1 ? ` · ${t('noc.con.calls', row.call_count)}` : ''}
      </span>
    </span>
  )
}

/**
 * "Ara / Arama kaydı gir". İzni yoksa (arama kaydı yalnız 7/24 operatörüne açık) düğme yerine GÖRÜNÜR kısa neden —
 * dokunmatikte açılmayan ipucuna bırakılmaz; tam açıklama sayfanın üstündeki bilgi şeridinde.
 */
function CallButton({ row, onCall, t, block = false }) {
  const name = row.monitor?.name || row.domain
  if (!row.can_call) {
    return <span data-slot="noc-con-call-disabled" className="text-xs text-muted-foreground">{t('noc.con.callDisabledShort')}</span>
  }
  return (
    <Button type="button" size="sm" variant={rowUrgency(row) === 'needs_call' ? 'default' : 'outline'} data-action="noc-con-call"
      aria-label={t('noc.con.callFor', name)} onClick={() => onCall(row)}
      className={cn('h-10 gap-1.5 sm:h-9 sm:pointer-coarse:h-10', block && 'w-full')}>
      <PhoneCall aria-hidden="true" />{t('noc.con.call')}
    </Button>
  )
}

/** Alarm başlığı: izleme adı (derin bağlantı → alarm detayı) + hedef + tür. */
function AlarmTitle({ row, t }) {
  const name = row.monitor?.name || row.domain
  const mon = monitorTarget(row)
  return (
    <span className="flex min-w-0 items-start gap-2">
      <AlertTypeIcon type={row.alert_type} className="mt-0.5 shrink-0" />
      <span className="flex min-w-0 flex-col gap-0.5">
        <a href={alertHref(row)} data-slot="noc-con-alert-link" className="line-clamp-2 font-semibold text-foreground [overflow-wrap:anywhere] hover:underline"
          title={t('noc.con.openAlert', name)} onClick={(e) => goOnPlainClick(e, 'alerthistory', { alert: String(row.id) })}>
          {name}
        </a>
        <span className="flex min-w-0 flex-wrap items-center gap-x-1.5 text-xs text-muted-foreground">
          {row.monitor?.name && row.monitor.name !== row.domain && <span className="truncate [overflow-wrap:anywhere]" title={row.domain}>{row.domain}</span>}
          <span>{alertTypeLabel(t, row.alert_type)}</span>
          {mon && (
            <a href={monitorHref(row)} className="inline-flex items-center gap-0.5 text-primary hover:underline"
              onClick={(e) => goOnPlainClick(e, mon.tab, mon.params)} aria-label={t('noc.con.openMonitor', name)}>
              <ExternalLink aria-hidden="true" className="size-3" />{t('noc.con.monitor')}
            </a>
          )}
        </span>
      </span>
    </span>
  )
}

function Team({ row, t }) {
  if (!row.team_name) return <span className="text-xs text-muted-foreground">{t('app.noTeam')}</span>
  return (
    <span className="flex min-w-0 flex-col">
      <span className="truncate text-sm" title={row.team_name}>{row.team_name}</span>
      {row.ug_team_name && <span className="truncate text-xs text-muted-foreground" title={row.ug_team_name}>{t('noc.con.ug', row.ug_team_name)}</span>}
    </span>
  )
}

function StateBadge({ row, nowMs, t, locale }) {
  return row.resolved ? (
    <Badge variant="outline" className="gap-1 font-normal text-success"><CheckCircle2 aria-hidden="true" className="size-3" />{t('noc.con.resolved')}</Badge>
  ) : (
    <span className="text-xs text-muted-foreground" title={fullTime(row.created_at, locale)}>
      <BellRing aria-hidden="true" className="mr-1 inline size-3" />{relTime(row.created_at, nowMs, t)}
    </span>
  )
}

/**
 * Konsol listesi — geniş kapta tablo, dar kapta (telefon / kenar çubuklu tablet) kart. Satır başına: önem, alarm (izleme
 * adı → alarm detayı; izlemeye git), takım, kanallar + 7/24 iletimi, son arama, "Ara" eylemi. Sol renk şeridi YOK:
 * acil (7/24'e gitti, aranmadı) satır `data-urgency="needs_call"` + vurgulu "Ara" düğmesiyle belirtilir.
 */
export default function NocConsoleList({ items, narrow, nowMs, onCall }) {
  const t = useT()
  const locale = useDateLocale()
  if (narrow) {
    return (
      <ul data-slot="noc-con-cards" className="m-0 grid list-none grid-cols-1 gap-2 p-0">
        {items.map((row) => (
          <li key={row.id} className="min-w-0">
            <Card data-slot="noc-con-row" data-urgency={rowUrgency(row)} data-alert-id={row.id}
              className={cn('gap-2 px-3 py-3 shadow-none', rowUrgency(row) === 'needs_call' && 'border-amber-500/60')}>
              <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                <AlertLevelBadge level={row.level} />
                <StateBadge row={row} nowMs={nowMs} t={t} locale={locale} />
              </div>
              <AlarmTitle row={row} t={t} />
              <Team row={row} t={t} />
              <Channels row={row} t={t} />
              <LastCall row={row} nowMs={nowMs} t={t} locale={locale} />
              <CallButton row={row} onCall={onCall} t={t} block />
            </Card>
          </li>
        ))}
      </ul>
    )
  }
  return (
    <Table data-slot="noc-con-table" className="table-fixed">
      <TableHeader>
        <TableRow>
          <TableHead className="w-[6.5rem]">{t('noc.con.col.level')}</TableHead>
          <TableHead>{t('noc.con.col.alarm')}</TableHead>
          <TableHead className="w-[10rem]">{t('noc.con.col.team')}</TableHead>
          <TableHead className="w-[13rem]">{t('noc.con.col.channels')}</TableHead>
          <TableHead className="w-[12rem]">{t('noc.con.col.call')}</TableHead>
          <TableHead className="w-[6.5rem] text-right"><span className="sr-only">{t('noc.con.col.action')}</span></TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {items.map((row) => (
          <TableRow key={row.id} data-slot="noc-con-row" data-urgency={rowUrgency(row)} data-alert-id={row.id}
            className={cn(rowUrgency(row) === 'needs_call' && 'bg-amber-500/5')}>
            <TableCell className="align-top">
              <div className="flex flex-col items-start gap-1">
                <AlertLevelBadge level={row.level} />
                <StateBadge row={row} nowMs={nowMs} t={t} locale={locale} />
              </div>
            </TableCell>
            <TableCell className="align-top whitespace-normal"><AlarmTitle row={row} t={t} /></TableCell>
            <TableCell className="align-top whitespace-normal"><Team row={row} t={t} /></TableCell>
            <TableCell className="align-top whitespace-normal"><Channels row={row} t={t} /></TableCell>
            <TableCell className="align-top whitespace-normal"><LastCall row={row} nowMs={nowMs} t={t} locale={locale} /></TableCell>
            <TableCell className="text-right align-top"><CallButton row={row} onCall={onCall} t={t} /></TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}
