import { Activity, Check, X } from 'lucide-react'
import { formatDateSec } from '../../api/client'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { LEVEL_TONE, fmtDuration, itemTime, kindMeta, relativeTime, toMs } from './inboxModel.js'

/**
 * Tek bildirim satırı (v3, 2026-09-26): tonlu tür kutucuğu · başlık (+ seviye rozeti) · göreli zaman (tam zaman
 * ipucunda) · tek satır özet (tür · alt tür · izleme) · takım + zaman çizgisi ("başladı … · 3 sa 12 dk açık").
 *
 * "Stretched button" deseni: satırın kendisi tek bir shadcn Button (tıkla → hedefe git + okundu); eylemler
 * KARDEŞ öğedir (düğme içinde düğme yok). Masaüstünde eylem kümesi hover/odakla belirir (sağ üstte, göreli
 * zamanın üstüne biner); telefonda tek KebabMenu her zaman görünür (dokunmatikte hover yok — RESPONSIVE.md).
 * Test/tur kancası: `data-inbox-row={kind}` (v1'den beri), `data-inbox-open` (ana düğme), `data-unread`, `data-dismissed`.
 */
export default function InboxRow({ it, unread, dismissed = false, history = false, mobile = false, tick = 0,
  onOpen, onOpenMonitor, onMarkRead, onDismiss }) {
  const t = useT()
  const locale = useDateLocale()
  void tick   // dakikada bir yeniden çizim (canlı süre / göreli zaman)
  const meta = kindMeta(it.kind)
  const Icon = meta.icon
  const at = itemTime(it)
  const lvl = String(it.level || '').toLowerCase()
  const showLevel = it.kind === 'alert_open' && it.level
  const monitorName = it.monitor_name || it.title || ''
  const rel = relativeTime(at, t, locale)

  const summary = [t(`inbox.kind.${it.kind}`), it.sub, it.monitor_name && it.monitor_name !== it.title ? it.monitor_name : null]
    .filter(Boolean).join(' · ')
  // Seviye rozeti: masaüstünde başlık satırında; telefonda (dar satır, başlık kırpılmasın) özet satırının başında.
  const levelBadge = showLevel && (
    <Badge variant="secondary" className={cn('h-4 shrink-0 px-1.5 text-[10px] font-bold', LEVEL_TONE[lvl])}>{it.level}</Badge>
  )

  // Zaman çizgisi: başladı · süre (açıksa canlı) · çözüldü; diğer türlerde tam zaman.
  const parts = []
  const start = it.started_at || null
  if (it.kind === 'alert_open' && start) {
    parts.push(t('inbox.startedAt', formatDateSec(start)))
    const d = fmtDuration(Date.now() - toMs(start), t)
    if (d) parts.push(t('inbox.openFor', d))
  } else if (it.kind === 'alert_resolved' && start) {
    parts.push(t('inbox.startedAt', formatDateSec(start)))
    const end = it.ended_at || it.at
    const d = fmtDuration(toMs(end) - toMs(start), t)
    if (d) parts.push(t('inbox.lasted', d))
    if (end) parts.push(t('inbox.resolvedAt', formatDateSec(end)))
  } else if (at) {
    parts.push(formatDateSec(at))
  }
  const timeline = parts.join(' · ')

  const actions = [
    unread && !history && onMarkRead && { key: 'read', icon: Check, label: t('inbox.markRead', it.title), onClick: () => onMarkRead(it) },
    it.monitor_tab && onOpenMonitor && { key: 'monitor', icon: Activity, label: t('act.goMonitorFor', monitorName), onClick: () => onOpenMonitor(it) },
    !history && !dismissed && onDismiss && { key: 'dismiss', icon: X, label: t('inbox.dismiss', it.title), onClick: () => onDismiss(it) },
  ].filter(Boolean)

  return (
    <div data-inbox-row={it.kind} data-unread={unread || undefined} data-dismissed={dismissed || undefined}
      className={cn('group/row relative flex items-start gap-1 px-2', dismissed && 'opacity-55')}>
      <Button type="button" variant="ghost" data-inbox-open="" onClick={() => onOpen(it)}
        className={cn('h-auto min-w-0 flex-1 items-start justify-start gap-3 rounded-lg px-2.5 py-2.5 text-left font-normal whitespace-normal',
          'hover:bg-accent/60 focus-visible:z-10', unread && 'bg-primary/5 hover:bg-primary/8')}>
        <span aria-hidden="true" className={cn('mt-0.5 flex size-9 shrink-0 items-center justify-center rounded-lg', meta.tile)}>
          <Icon className="size-4" />
        </span>
        <span className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="flex min-w-0 items-center gap-2">
            <span className={cn('min-w-0 flex-1 truncate text-sm leading-5', unread ? 'font-semibold' : 'font-medium text-foreground/90')}>{it.title}</span>
            {!mobile && levelBadge}
            {rel && (
              <SimpleTooltip content={formatDateSec(at)}>
                <span className="shrink-0 text-[11px] leading-5 text-muted-foreground tabular-nums">{rel}</span>
              </SimpleTooltip>
            )}
          </span>
          <span className="flex min-w-0 items-center gap-1.5 text-xs text-muted-foreground">
            {mobile && levelBadge}
            <span className="min-w-0 truncate">{summary}</span>
          </span>
          {(it.team_name || timeline) && (
            <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5 text-[11px] text-muted-foreground">
              {it.team_name && <TeamBadge static teamId={it.team_id} teamName={it.team_name} size={11} className="text-[11px]" />}
              {timeline && <span className="min-w-0 truncate">{timeline}</span>}
            </span>
          )}
        </span>
        {unread && <span aria-hidden="true" className="mt-2 size-2 shrink-0 rounded-full bg-primary" />}
        {unread && <span className="sr-only">{t('inbox.unreadMark')}</span>}
      </Button>
      {actions.length > 0 && (mobile ? (
        <div data-slot="inbox-row-actions" className="flex shrink-0 items-center self-center">
          <KebabMenu label={t('inbox.rowActions')} rowLabel={it.title}
            items={actions.map(({ icon: AIcon, label, onClick }) => ({ label, icon: <AIcon aria-hidden="true" />, onClick }))} />
        </div>
      ) : (
        <div data-slot="inbox-row-actions"
          className="absolute top-2 right-3 flex items-center gap-0.5 rounded-md border bg-popover p-0.5 shadow-xs opacity-0 transition-opacity group-focus-within/row:opacity-100 group-hover/row:opacity-100 motion-reduce:transition-none">
          {actions.map(({ key, icon: AIcon, label, onClick }) => (
            <Button key={key} type="button" variant="ghost" size="icon-sm" className="size-7 text-muted-foreground hover:text-foreground"
              aria-label={label} title={label} onClick={onClick}>
              <AIcon aria-hidden="true" />
            </Button>
          ))}
        </div>
      ))}
    </div>
  )
}
