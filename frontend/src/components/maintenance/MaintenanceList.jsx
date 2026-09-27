import { CircleStop, History, Pause, Pencil, Play, Trash2, UserRound } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { nextOccurrence, parseIso } from './maintenanceSchedule.js'
import { activeInterval } from './MaintenanceActiveStrip.jsx'
import { RecurringMark, StatusBadge, TargetsSummary, useScheduleText } from './maintenanceUi.jsx'

const TH = 'h-9 px-3 text-[0.78em] font-semibold tracking-wide text-muted-foreground uppercase'

/**
 * Satır eylemleri — tek liste, iki çizim: masaüstü tablosunda ikon düğmeleri, telefonda KebabMenu (DropdownMenu).
 * Adlar satırı ayırır (`a11y.rowAction`: "pencere adı — eylem"); gate rowAccessibleNames.
 */
export function rowActionItems(w, t, { onEdit, onTogglePause, onEndNow, onHistory, onDelete }) {
  const paused = w.status === 'paused'
  return [
    { key: 'end', label: t('mw.endNow'), Icon: CircleStop, onClick: () => onEndNow(w), hidden: w.status !== 'active' },
    { key: 'pause', label: paused ? t('mw.resume') : t('mw.pause'), Icon: paused ? Play : Pause, onClick: () => onTogglePause(w), hidden: w.status === 'completed' },
    { key: 'edit', label: t('mw.edit'), Icon: Pencil, onClick: () => onEdit(w) },
    // Pencerenin GEÇMİŞİ: planlı kesinti alarmları susturur → "bu pencereyi kim genişletti" izlenebilir olmalı.
    { key: 'history', label: t('chg.tab'), Icon: History, onClick: () => onHistory(w) },
    { key: 'delete', label: t('mw.delete'), Icon: Trash2, onClick: () => onDelete(w), danger: true },
  ]
}

function InlineActions({ w, t, actions }) {
  return (
    <span className="inline-flex items-center gap-0.5">
      {rowActionItems(w, t, actions).filter((a) => !a.hidden).map(({ key, label, Icon, onClick, danger }) => (
        <Button key={key} type="button" variant="ghost" size="icon-sm" title={label}
          aria-label={t('a11y.rowAction', w.name, label)} onClick={onClick}
          className={cn('text-muted-foreground', danger ? 'hover:bg-destructive/10 hover:text-destructive' : 'hover:text-foreground',
            key === 'end' && 'text-success hover:text-success')}>
          <Icon aria-hidden="true" />
        </Button>
      ))}
    </span>
  )
}

/** Telefon: tüm eylemler tek DropdownMenu'de (dokunma hedefi 40 px, ad satırı ayırır). */
function MenuActions({ w, t, actions }) {
  return (
    <KebabMenu label={t('mw.colActions')} rowLabel={w.name}
      items={rowActionItems(w, t, actions).map((a) => ({
        label: a.label, icon: <a.Icon aria-hidden="true" />, onClick: a.onClick, danger: a.danger, hidden: a.hidden,
      }))} />
  )
}

/**
 * Pencere listesi — masaüstünde shadcn Table, telefonda kart listesi. Sütunlar: ad (+açıklama, oluşturan), durum,
 * zamanlama (düz sözcükler + saat dilimi), sıradaki (süren pencerede "bitiş HH:mm"), hedefler (+takım), eylemler.
 * Test kancası: satır/kart `data-status`, `data-id`.
 */
export default function MaintenanceList({ items, phone, canManage, now, actions, teamId, teamName }) {
  const t = useT()
  const { sentence, timeOf, dateTimeOf, dayOf } = useScheduleText()

  /** "Sıradaki" hücresi: süren → bitiş; yaklaşan → sıradaki oluşum; duraklatılmış → —; bitmiş → bittiği gün. */
  const nextText = (w) => {
    if (w.status === 'active') {
      const iv = activeInterval(w, now)
      return iv ? `${t('mw.inProgress')} · ${t('mw.endsAt', timeOf(iv.end, w.timezone))}` : t('mw.inProgress')
    }
    if (w.status === 'completed') {
      const s = parseIso(w.start_at)
      return s == null ? '—' : t('mw.endedOn', dayOf(s, w.timezone))
    }
    if (w.status === 'paused') return '—'
    const next = nextOccurrence(w, now)
    const ms = next ? next.start : parseIso(w.next_occurrence)
    return ms == null ? '—' : dateTimeOf(ms, w.timezone)
  }
  const team = (w) => (w.team_id != null
    ? <TeamBadge teamId={w.team_id} teamName={w.team_id === teamId ? teamName : undefined} size={11} className="text-[0.95em]" />
    : null)
  const creator = (w) => (w.created_by
    ? <span className="inline-flex items-center gap-1 text-[0.8em] text-muted-foreground"><UserRound size={11} aria-hidden="true" />{t('mw.createdBy', w.created_by)}</span>
    : null)

  if (phone) {
    return (
      <ul data-slot="mw-list" className="flex list-none flex-col gap-2 p-0">
        {items.map((w) => (
          <li key={w.id} data-status={w.status} data-id={w.id}
            className={cn('flex min-w-0 flex-col gap-1.5 rounded-lg border bg-card px-3 py-2.5', w.status === 'active' && 'border-success/40 bg-success/[0.04]')}>
            <div className="flex min-w-0 items-start justify-between gap-2">
              <span className="flex min-w-0 items-center gap-1.5 font-semibold [overflow-wrap:anywhere]">{w.name}<RecurringMark w={w} /></span>
              <StatusBadge status={w.status} />
            </div>
            {w.description && <p className="m-0 text-[0.85em] text-muted-foreground [overflow-wrap:anywhere]">{w.description}</p>}
            <div className="text-[0.86em]">
              <span>{sentence(w)}</span>
              <span className="ml-1.5 text-[0.9em] text-muted-foreground">{w.timezone}</span>
            </div>
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[0.86em]">
              <TargetsSummary w={w} />
              {team(w)}
              <span aria-hidden="true" className="text-muted-foreground">·</span>
              <span className="text-muted-foreground">{t('mw.colNext')}: {nextText(w)}</span>
            </div>
            <div className="flex items-center justify-between gap-2 border-t pt-1.5">
              {creator(w) || <span />}
              {canManage && <MenuActions w={w} t={t} actions={actions} />}
            </div>
          </li>
        ))}
      </ul>
    )
  }

  return (
    <div data-slot="mw-list" className="overflow-hidden rounded-lg border bg-card">
      <Table className="text-[0.88em]">
        <TableHeader className="bg-muted/50">
          <TableRow className="hover:bg-transparent">
            <TableHead className={TH}>{t('mw.colName')}</TableHead>
            <TableHead className={TH}>{t('mw.colStatus')}</TableHead>
            <TableHead className={TH}>{t('mw.colSchedule')}</TableHead>
            <TableHead className={cn(TH, 'hidden lg:table-cell')}>{t('mw.colNext')}</TableHead>
            <TableHead className={TH}>{t('mw.colMonitors')}</TableHead>
            {canManage && <TableHead className={cn(TH, 'text-right')}>{t('mw.colActions')}</TableHead>}
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.map((w) => (
            <TableRow key={w.id} data-status={w.status} data-id={w.id} className={cn(w.status === 'active' && 'bg-success/[0.05]')}>
              <TableCell className="max-w-[24rem] whitespace-normal">
                <div className="flex items-center gap-1.5 font-semibold [overflow-wrap:anywhere]">{w.name}<RecurringMark w={w} /></div>
                {w.description && <div className="mt-0.5 text-[0.85em] text-muted-foreground [overflow-wrap:anywhere]">{w.description}</div>}
                {creator(w) && <div className="mt-0.5">{creator(w)}</div>}
              </TableCell>
              <TableCell><StatusBadge status={w.status} /></TableCell>
              <TableCell className="max-w-[18rem] whitespace-normal">
                <div>{sentence(w)}</div>
                <div className="text-[0.82em] text-muted-foreground">{w.timezone}</div>
              </TableCell>
              <TableCell className="hidden text-[0.92em] whitespace-nowrap text-muted-foreground lg:table-cell">{nextText(w)}</TableCell>
              <TableCell className="whitespace-normal">
                <div className="flex flex-wrap items-center gap-x-2 gap-y-1"><TargetsSummary w={w} />{team(w)}</div>
              </TableCell>
              {canManage && <TableCell className="text-right whitespace-nowrap"><InlineActions w={w} t={t} actions={actions} /></TableCell>}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
