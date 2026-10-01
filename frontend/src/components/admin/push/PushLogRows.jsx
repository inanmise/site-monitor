import { Eye, RotateCcw, ChevronRight } from 'lucide-react'
import { formatDate } from '../../../api/client'
import TeamBadge from '../../ui/TeamBadge.jsx'
import UserBadge from '../../ui/UserBadge.jsx'
import SimpleTooltip from '../../ui/SimpleTooltip.jsx'
import { SortHead, KindBadge, TriggerBadge, LevelBadge, TypeBadge, ErrorClassBadge, MUTED_SM } from '../LogViewParts.jsx'
import { STATUS_META, triggerLabel, statusLabel, retryable } from './pushLogModel.js'
import { Button } from '@/components/shadcn/button'
import { TableBody, TableCell, TableHead, TableHeader, TableRow, Table } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/** Push gönderim durumu rozeti + (isteğe bağlı) hata metni ve HTTP / deneme satırı. */
export function PushStatusBadge({ row, t, compact = false }) {
  const m = STATUS_META[row.kind] ?? STATUS_META.UNKNOWN
  const detail = [row.http_status ? `HTTP ${row.http_status}` : null, row.attempts > 1 ? t('pl.attempts', row.attempts) : null].filter(Boolean).join(' · ')
  return (
    <div className="flex min-w-0 flex-col items-start gap-0.5">
      <KindBadge tone={m.tone} icon={m.Icon} title={row.status || ''}>{statusLabel(row.kind, t)}</KindBadge>
      {!compact && row.error && <div className="w-full max-w-[260px] truncate text-xs text-destructive" title={row.error}>{row.error}</div>}
      {!compact && detail && <div className={MUTED_SM}>{detail}</div>}
    </div>
  )
}

const TD = 'px-2 py-2 align-top'
const monitorOf = (row) => row.monitor_name || row.title || '—'
const actionName = (row, t, what) => t('a11y.rowAction', `${monitorOf(row)} · ${formatDate(row.at)}`, what)

function RowActions({ row, t, canRequeue, busy, onOpen, onRequeue }) {
  // Adlar kaydı ayırır (izleme/başlık + zaman): yeniden kuyruğa alma YAN ETKİLİ ve her satırda aynı adla
  // duyuluyordu (2026-09-25, R15). İpucu kısa kalır.
  return (
    <span className="inline-flex gap-1">
      <SimpleTooltip content={t('pl.detail')}>
        <Button type="button" variant="outline" size="icon-sm" aria-label={actionName(row, t, t('pl.detail'))}
          onClick={() => onOpen(row.id)} className="pointer-coarse:size-10"><Eye aria-hidden="true" /></Button>
      </SimpleTooltip>
      {canRequeue && retryable(row) && (
        <SimpleTooltip content={t('pl.requeue')}>
          <Button type="button" variant="outline" size="icon-sm" data-action="resend" aria-label={actionName(row, t, t('pl.requeue'))}
            disabled={busy} onClick={() => onRequeue(row)} className="pointer-coarse:size-10"><RotateCcw aria-hidden="true" /></Button>
        </SimpleTooltip>
      )}
    </span>
  )
}

/** Masaüstü: sıralanabilir başlıklı tablo (satır tıklaması ayrıntıyı açar; takım / alıcı rozetleri açmaz). */
export function PushLogTable({ rows, t, sort, onSort, canRequeue, busy, onOpen, onRequeue }) {
  return (
    // Sabit sütun düzeni (2026-10-01): uzun takım / alıcı / izleme adı sütunu genişletip eylemleri kabın DIŞINA itiyordu
    // (tablo yatay kayıyordu). Genişlikler başlıkta; uzun metinler tek satır kırpılır, tam metin `title`'da.
    <Table data-testid="sml-table" className="table-fixed text-[0.85em]">
      <TableHeader className="bg-muted/50">
        <TableRow>
          <SortHead label={t('health.smtpLogDate')} field="at" sort={sort} onSort={onSort} className="w-[128px]" />
          <SortHead label={t('sml.colTeam')} field="team" sort={sort} onSort={onSort} className="hidden w-[170px] @5xl/pl:table-cell" />
          <SortHead label={t('pl.colUser')} field="user" sort={sort} onSort={onSort} className="w-[180px]" />
          <SortHead label={t('pl.colMonitor')} field="monitor" sort={sort} onSort={onSort} />
          <SortHead label={t('pl.colLevel')} field="level" sort={sort} onSort={onSort} className="hidden w-[92px] @4xl/pl:table-cell" />
          <SortHead label={t('health.emailDetailTrigger')} field="trigger" sort={sort} onSort={onSort} className="hidden w-[120px] @6xl/pl:table-cell" />
          <SortHead label={t('health.smtpLogStatus')} field="status" sort={sort} onSort={onSort} className="w-[190px]" />
          <TableHead className="w-[84px] px-2"><span className="sr-only">{t('audit.colDetail')}</span></TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((row) => (
          <TableRow key={row.id} tabIndex={0} data-slot="pl-row" data-kind={row.kind} className="cursor-pointer"
            aria-label={t('a11y.openRow', formatDate(row.at))}
            onClick={() => onOpen(row.id)}
            onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); onOpen(row.id) } }}>
            <TableCell className={cn(TD, 'font-mono whitespace-nowrap text-muted-foreground')}>{formatDate(row.at)}</TableCell>
            <TableCell className={cn(TD, 'hidden overflow-hidden @5xl/pl:table-cell')} title={row.team_name || ''}>{row.team_name ? <span className="flex min-w-0" data-row-stop="" onClick={(e) => e.stopPropagation()}><TeamBadge teamId={row.team_id} teamName={row.team_name} className="max-w-full min-w-0" /></span> : <span className="text-muted-foreground">—</span>}</TableCell>
            <TableCell className={cn(TD, 'overflow-hidden')} title={row.display_name || row.username || ''}><span className="flex min-w-0 overflow-hidden" data-row-stop="" onClick={(e) => e.stopPropagation()}><UserBadge username={row.username} displayName={row.display_name} inline size="sm" /></span></TableCell>
            <TableCell className={cn(TD, 'truncate')} title={[row.monitor_name, row.title].filter(Boolean).join(' · ')}><TypeBadge>{row.monitor_type || '—'}</TypeBadge> <span>{monitorOf(row)}</span></TableCell>
            <TableCell className={cn(TD, 'hidden @4xl/pl:table-cell')}>{row.alert_level ? <LevelBadge level={row.alert_level} /> : '—'}</TableCell>
            <TableCell className={cn(TD, 'hidden @6xl/pl:table-cell')}><TriggerBadge trigger={row.trigger}>{triggerLabel(row.trigger, t)}</TriggerBadge></TableCell>
            <TableCell className={cn(TD, 'overflow-hidden')}><PushStatusBadge row={row} t={t} />{row.error_class && <ErrorClassBadge>{t(`pl.cls.${row.error_class}`)}</ErrorClassBadge>}</TableCell>
            <TableCell className={cn(TD, 'whitespace-nowrap')} onClick={(e) => e.stopPropagation()}>
              <RowActions row={row} t={t} canRequeue={canRequeue} busy={busy} onOpen={onOpen} onRequeue={onRequeue} />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

/**
 * Telefon / dar kap: kart listesi. Kartın gövdesi ayrıntıyı açan TEK düğme (ekran okuyucu için ad: zaman + izleme);
 * eylemler (ayrıntı, yeniden kuyruğa al) ayrı düğmeler — iç içe etkileşimli öğe yok.
 */
export function PushLogCards({ rows, t, canRequeue, busy, onOpen, onRequeue }) {
  return (
    <ul data-testid="pl-cards" className="m-0 flex list-none flex-col p-0">
      {rows.map((row) => {
        const m = STATUS_META[row.kind] ?? STATUS_META.UNKNOWN
        return (
          <li key={row.id} data-slot="pl-row" data-kind={row.kind} className="flex min-w-0 items-start gap-2 border-b px-3 py-2.5 last:border-b-0">
            <Button type="button" variant="ghost" onClick={() => onOpen(row.id)} aria-label={t('a11y.openRow', `${monitorOf(row)} · ${formatDate(row.at)}`)}
              className="h-auto min-h-12 min-w-0 flex-1 flex-col items-stretch gap-1 px-1.5 py-1 text-left font-normal whitespace-normal">
              <span className="flex min-w-0 items-center justify-between gap-2">
                <span className="flex min-w-0 items-center gap-1.5">
                  <TypeBadge>{row.monitor_type || '—'}</TypeBadge>
                  <span className="min-w-0 truncate text-sm font-semibold">{monitorOf(row)}</span>
                </span>
                <KindBadge tone={m.tone} icon={m.Icon} title={row.status || ''}>{statusLabel(row.kind, t)}</KindBadge>
              </span>
              <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
                <span className="min-w-0 truncate font-medium text-foreground">{row.display_name || row.username || '—'}</span>
                {row.team_name && <span className="min-w-0 truncate">· {row.team_name}</span>}
              </span>
              {(row.error || row.http_status) && row.kind !== 'SENT' && (
                <span className="min-w-0 truncate text-xs text-destructive">{[row.http_status ? `HTTP ${row.http_status}` : null, row.error].filter(Boolean).join(' · ')}</span>
              )}
              <span className="flex min-w-0 flex-wrap items-center gap-1.5 text-[11px] text-muted-foreground">
                <span className="font-mono">{formatDate(row.at)}</span>
                {row.alert_level && <LevelBadge level={row.alert_level} />}
                <TriggerBadge trigger={row.trigger}>{triggerLabel(row.trigger, t)}</TriggerBadge>
                {row.attempts > 1 && <span>{t('pl.attempts', row.attempts)}</span>}
              </span>
            </Button>
            <span className="flex shrink-0 flex-col items-center gap-1">
              {canRequeue && retryable(row) ? (
                <SimpleTooltip content={t('pl.requeue')}>
                  <Button type="button" variant="outline" size="icon" data-action="resend" aria-label={actionName(row, t, t('pl.requeue'))}
                    disabled={busy} onClick={() => onRequeue(row)} className="size-10"><RotateCcw aria-hidden="true" /></Button>
                </SimpleTooltip>
              ) : <ChevronRight aria-hidden="true" className="mt-3 size-4 text-muted-foreground" />}
            </span>
          </li>
        )
      })}
    </ul>
  )
}
