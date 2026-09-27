import { ArrowUp, ArrowDown, ArrowUpDown, MessageSquare, Eye, ExternalLink, UserCheck, CheckCircle2, Trash2 } from 'lucide-react'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { durationMs, formatDuration, formatIncidentTime } from '../../utils/incidentMeta.js'
import TeamBadge from '../ui/TeamBadge.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import IncidentCard from './IncidentCard.jsx'
import { IncidentStatusBadge, AckBadge, SeverityBadge, RootCauseChip, MonitorTypeIcon } from './IncidentBadges.jsx'
import { isOpen, isAcked, incidentHref, rowName } from './incidentsModel.js'

const TH = 'h-9 px-3 text-[0.74em] font-semibold tracking-wide text-muted-foreground uppercase'
/** Sütun → sunucu sıralama anahtarı (IncidentsController.sortFor). */
const SORT_KEY = { severity: 'severity', status: 'status', cause: 'type', started: 'started' }

/** Yükleme iskeleti — altı satır, gerçek satır yüksekliğinde. */
export function TableSkeleton({ phone = false }) {
  return (
    <div data-slot="incidents-skeleton" aria-hidden="true" className={cn('flex flex-col gap-2', !phone && 'rounded-lg border bg-card p-2')}>
      {[0, 1, 2, 3, 4, 5].map((k) => <Skeleton key={k} className={phone ? 'h-[118px] w-full rounded-xl' : 'h-12 w-full'} />)}
    </div>
  )
}

/**
 * Liste görünümü — shadcn Table (md+), telefonda kart yığını (aynı IncidentCard). Satır tıklaması detayı açar;
 * satır klavyeyle odaklanır (Enter/Space açar, ↑/↓ satırlar arasında gezer); satır eylemleri tek KebabMenu'de
 * (adı satırı ayırır). Sıralama başlıkta `aria-sort` ile duyurulur. Seçili satır TÜM zeminle vurgulanır.
 */
export default function IncidentsTable({
  rows, nowMs, sort, onSort, onOpen, selectedId, phone = false, isAdmin = false, onAck, onResolve, onDelete,
}) {
  const t = useT()
  const dateLocale = useDateLocale()

  if (phone) {
    return (
      <ul data-slot="incident-list" className="m-0 flex list-none flex-col gap-2 p-0">
        {rows.map((inc) => (
          <li key={inc.id} className="min-w-0">
            <IncidentCard inc={inc} nowMs={nowMs} showStatus onOpen={() => onOpen(inc)} selected={String(selectedId) === String(inc.id)} />
          </li>
        ))}
      </ul>
    )
  }

  const ariaSort = (col) => (sort.by === SORT_KEY[col] ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none')
  const sortIcon = (col) => (sort.by === SORT_KEY[col]
    ? (sort.dir === 'asc' ? <ArrowUp aria-hidden="true" className="size-3" /> : <ArrowDown aria-hidden="true" className="size-3" />)
    : <ArrowUpDown aria-hidden="true" className="size-3 opacity-40" />)
  const sortHead = (col, labelKey, className) => (
    <TableHead className={cn(TH, className)} aria-sort={ariaSort(col)}>
      <Button type="button" variant="ghost" size="xs" onClick={() => onSort(SORT_KEY[col])}
        className="-ml-1.5 h-auto gap-1 px-1.5 py-0.5 text-[1em] font-semibold tracking-wide uppercase hover:text-primary">
        {t(labelKey)}{sortIcon(col)}
      </Button>
    </TableHead>
  )

  /** ↑/↓ satırlar arasında odak gezdirir; Enter/Space satırı açar (yalnız satırın kendisinde — hücredeki düğmeler kendi işini yapar). */
  const onRowKey = (e, inc) => {
    if (e.target !== e.currentTarget) return
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpen(inc); return }
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      const sib = e.key === 'ArrowDown' ? e.currentTarget.nextElementSibling : e.currentTarget.previousElementSibling
      if (sib && typeof sib.focus === 'function') sib.focus()
    }
  }

  const menuItems = (inc) => [
    { label: t('incov.openDetail'), icon: <Eye aria-hidden="true" />, onClick: () => onOpen(inc) },
    { label: t('incov.openMonitor'), icon: <ExternalLink aria-hidden="true" />, onClick: () => window.location.assign(incidentHref(inc)) },
    { label: t('incov.ack'), icon: <UserCheck aria-hidden="true" />, onClick: () => onAck?.(inc), hidden: !isOpen(inc) || isAcked(inc) || !onAck },
    { label: t('incov.resolve'), icon: <CheckCircle2 aria-hidden="true" />, onClick: () => onResolve?.(inc), hidden: !isOpen(inc) || !onResolve },
    { label: t('incov.delete'), icon: <Trash2 aria-hidden="true" />, onClick: () => onDelete?.(inc), danger: true, hidden: !isAdmin || !onDelete },
  ]

  return (
    <div data-slot="incident-list" className="overflow-hidden rounded-lg border bg-card">
      <Table className="text-[0.88em]">
        <TableHeader className="bg-muted/50">
          <TableRow className="hover:bg-transparent">
            {sortHead('severity', 'incov.severity', 'w-[7.5rem]')}
            {/* Olay sütunu: otomatik tablo yerleşimi nowrap komşular yüzünden bunu 12 karaktere sıkıştırıyordu → alt sınır */}
            <TableHead className={cn(TH, 'min-w-[20rem]')}>{t('incov.colIncident')}</TableHead>
            {sortHead('status', 'incov.colStatus', 'w-[8.5rem]')}
            <TableHead className={cn(TH, 'hidden lg:table-cell')}>{t('incov.colTeam')}</TableHead>
            {sortHead('started', 'incov.colStarted', 'whitespace-nowrap')}
            <TableHead className={cn(TH, 'whitespace-nowrap')}>{t('incov.colDuration')}</TableHead>
            <TableHead className={cn(TH, 'hidden w-16 text-center xl:table-cell')}><span className="sr-only">{t('incov.colComments')}</span><MessageSquare aria-hidden="true" className="inline size-3.5" /></TableHead>
            <TableHead className={cn(TH, 'w-12')}><span className="sr-only">{t('incov.colActions')}</span></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((inc) => {
            const selected = String(selectedId) === String(inc.id)
            const name = inc.monitor?.name || inc.domain || '—'
            const open = isOpen(inc)
            return (
              <TableRow key={inc.id} data-status={inc.status} data-incident-id={inc.id} data-state={selected ? 'selected' : undefined}
                tabIndex={0} title={t('incov.openDetail')}
                className={cn('cursor-pointer outline-none focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary',
                  open && !selected && 'bg-destructive/[0.04]',
                  selected && 'bg-primary/10 outline outline-2 -outline-offset-2 outline-primary/60 hover:bg-primary/15')}
                onClick={() => onOpen(inc)}
                onKeyDown={(e) => onRowKey(e, inc)}>
                <TableCell><SeverityBadge level={inc.alert_level} /></TableCell>
                <TableCell className="min-w-[20rem] max-w-[32rem] whitespace-normal">
                  <div className="flex min-w-0 items-start gap-2">
                    <MonitorTypeIcon type={inc.monitor?.type} />
                    <div className="min-w-0">
                      <div className="line-clamp-2 font-semibold [overflow-wrap:anywhere]" title={name}>{name}</div>
                      <div className="mt-0.5 flex min-w-0 flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                        <RootCauseChip rc={inc.root_cause} />
                        {inc.message && <span className="line-clamp-1 [overflow-wrap:anywhere]" title={inc.message}>{inc.message}</span>}
                      </div>
                    </div>
                  </div>
                </TableCell>
                <TableCell>
                  <span className="flex flex-wrap gap-1">
                    <IncidentStatusBadge status={inc.status} />
                    {open && isAcked(inc) && <AckBadge />}
                  </span>
                </TableCell>
                {/* Takım rozeti kendi tıklamasını durdurur (üye penceresi); hücrenin BOŞ alanı satır gibi detayı açar —
                    eski hücre-düzeyi stopPropagation satırın ortasına tıklayanı sessizce yutuyordu (1440 ölçümü) */}
                <TableCell className="hidden lg:table-cell">
                  {(inc.team_name || inc.team_id != null)
                    ? <TeamBadge teamId={inc.team_id} teamName={inc.team_name} />
                    : <span className="text-muted-foreground">—</span>}
                </TableCell>
                <TableCell className="text-[0.92em] whitespace-nowrap text-muted-foreground" title={inc.started_at}>{formatIncidentTime(inc.started_at, dateLocale)}</TableCell>
                <TableCell className="text-[0.95em] whitespace-nowrap">
                  <span className="tabular-nums">{formatDuration(durationMs(inc.started_at, inc.resolved_at, nowMs), t)}</span>
                  {!open && inc.resolved_at && (
                    <span className="block text-[0.82em] whitespace-nowrap text-muted-foreground" title={inc.resolved_at}>
                      {t('incov.resolvedAt')} {formatIncidentTime(inc.resolved_at, dateLocale)}
                    </span>
                  )}
                </TableCell>
                <TableCell className="hidden text-center text-muted-foreground tabular-nums xl:table-cell">
                  {inc.comment_count ?? 0}
                  <span className="sr-only"> {(inc.comment_count ?? 0) === 1 ? t('incov.commentOne') : t('incov.comments', inc.comment_count ?? 0)}</span>
                </TableCell>
                <TableCell className="text-right" onClick={(e) => e.stopPropagation()}>
                  <KebabMenu items={menuItems(inc)} label={t('incov.colActions')} rowLabel={rowName(inc, dateLocale)} />
                </TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}
