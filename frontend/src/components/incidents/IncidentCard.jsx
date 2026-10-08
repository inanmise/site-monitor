import { MessageSquare, Clock } from 'lucide-react'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { formatIncidentTime } from '../../utils/incidentMeta.js'
import TeamBadge from '../ui/TeamBadge.jsx'
import ReadOnlyBadge from '../ui/ReadOnlyBadge.jsx'
import { Card } from '@/components/shadcn/card'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { IncidentStatusBadge, AckBadge, SeverityBadge, RootCauseChip, MonitorTypeIcon } from './IncidentBadges.jsx'
import { isOpen, isAcked, laneOf, isForeign } from './incidentsModel.js'
import LiveDuration from './LiveDuration.jsx'

/** Örtünün (başlık düğmesinin ::after'ı) ÜSTÜNDE kalması gereken etkileşimli bölge (MonitorCard ile aynı desen). */
const LAYER = 'relative z-10'

/**
 * Olay kartı — pano şeritlerinde ve telefondaki liste görünümünde aynı kart.
 *
 * <p><b>"Stretched button"</b> (monitoring/MonitorCard deseni): kart `role="button"` DEĞİL; başlık gerçek bir
 * shadcn Button, `::after` katmanı kartın tamamını örter → kartın herhangi bir yerine tıklamak detayı açar,
 * klavye tek durakta (başlık) Enter/Space ile açar. Takım rozeti örtünün üstüne çıkar (üye penceresi).
 * Sol renk şeridi YOK; durum/önem rozetle. Seçili (açık detay / derin bağlantı) kart TÜM çerçevesiyle vurgulanır.
 * Başka ekibin olayı (`can_manage:false`, 2026-09-28) kilit rozeti taşır — kartın kendi eylemi yok, detay salt okunur.
 */
export default function IncidentCard({ inc, onOpen, selected = false, showStatus = false, className }) {
  const t = useT()
  const dateLocale = useDateLocale()
  const name = inc.monitor?.name || inc.domain || '—'
  const open = isOpen(inc)
  const n = inc.comment_count ?? 0
  return (
    <Card data-slot="incident-card" data-status={inc.status} data-lane={laneOf(inc)} data-incident-id={inc.id}
      data-selected={selected || undefined} data-foreign={isForeign(inc) || undefined}
      className={cn('relative min-w-0 gap-0 overflow-hidden py-0 shadow-xs transition-colors hover:border-primary/50',
        open && !selected && 'border-destructive/30',
        selected && 'border-primary ring-2 ring-primary/40', className)}>
      <div className="flex min-w-0 flex-wrap items-center gap-1.5 px-3 pt-2.5">
        <SeverityBadge level={inc.alert_level} />
        {showStatus && <IncidentStatusBadge status={inc.status} />}
        {open && isAcked(inc) && <AckBadge />}
        <RootCauseChip rc={inc.root_cause} withLabel={false} />
        {isForeign(inc) && <ReadOnlyBadge compact />}
        <span className="ml-auto inline-flex items-center gap-1 text-[11px] whitespace-nowrap text-muted-foreground tabular-nums" title={inc.started_at}>
          <Clock aria-hidden="true" className="size-3" />
          {/* Süre kendi saatiyle tazelenir (yaprak) — kart saniyede bir yeniden çizilmez (2026-10-09) */}
          <LiveDuration since={inc.started_at} until={inc.resolved_at} live={open}
            format={(dur) => (open ? t('incov.ongoingFor', dur) : t('incov.lastedFor', dur))} />
        </span>
      </div>

      {/* Kartın GERÇEK düğmesi — ::after tüm kartı örter; odak halkası da örtüde çizilir. */}
      <Button type="button" variant="ghost" data-incident-open onClick={onOpen}
        aria-label={t('incov.openIncident', name)} title={t('incov.openDetail')}
        className={cn('mt-2 flex h-auto w-full min-w-0 items-start justify-start gap-2 rounded-none px-3 py-0 text-left text-[14px] leading-snug font-bold whitespace-normal text-foreground',
          'hover:bg-transparent hover:text-foreground dark:hover:bg-transparent',
          'focus-visible:ring-0 after:absolute after:inset-0 after:z-0 after:rounded-xl focus-visible:after:ring-[3px] focus-visible:after:ring-ring/50')}>
        <MonitorTypeIcon type={inc.monitor?.type} className="mt-px" />
        <span className="line-clamp-2 min-w-0 [overflow-wrap:anywhere]" title={name}>{name}</span>
      </Button>

      {inc.message && <p className="mt-1.5 line-clamp-1 px-3 text-xs text-muted-foreground [overflow-wrap:anywhere]" title={inc.message}>{inc.message}</p>}

      <div className="mt-2.5 flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 border-t px-3 py-2 text-xs text-muted-foreground">
        {(inc.team_name || inc.team_id != null)
          ? <span className={cn(LAYER, 'min-w-0')}><TeamBadge as="span" teamId={inc.team_id} teamName={inc.team_name} /></span>
          : <span className="italic">{t('incov.noTeam')}</span>}
        <span className="ml-auto inline-flex items-center gap-2 whitespace-nowrap">
          <span className="inline-flex items-center gap-1" title={n === 1 ? t('incov.commentOne') : t('incov.comments', n)}>
            <MessageSquare aria-hidden="true" className="size-3.5" />
            <span className="tabular-nums">{n}</span>
            <span className="sr-only">{n === 1 ? t('incov.commentOne') : t('incov.comments', n)}</span>
          </span>
          <span aria-hidden="true">·</span>
          <span title={inc.started_at}>{formatIncidentTime(inc.started_at, dateLocale)}</span>
        </span>
      </div>
    </Card>
  )
}
