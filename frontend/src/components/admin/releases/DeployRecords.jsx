import { Trash2 } from 'lucide-react'
import { formatDate, formatDateSec } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import HintPopover from '../../ui/HintPopover.jsx'
import { SortTh, TH } from '../HealthUi.jsx'
import { CurrentBadge, KindBadge, ShaRef, SourceBadge, fmtAgo, fmtDur } from './DeployBadges.jsx'
import { DeployCard, deployLabel } from './DeployTimeline.jsx'
import { recordDuration } from './releaseModel.js'
import { Button } from '@/components/shadcn/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * Sürüm & Dağıtım → "Kayıtlar" görünümü (2026-09-27 yeniden tasarım): sunucu sayfalı ham kayıtlar (yeniden
 * başlatmalar dâhil, tüm ortamlar). Geniş KAPTA shadcn Table (sunucunun izin verdiği sütunlarda sıralama:
 * başlangıç / sürüm / ortam / kaynak), dar kapta (telefon, kenar çubuğu açık tablet) aynı kart (`DeployCard`).
 * Düşük öncelikli sütunlar kap genişliğine göre açılır (`@container/deploy`). Sayfalama çağıranda (PaginationBar).
 * Test kancaları: tablo `data-testid="deploy-table"`, satırda `data-current`, kart listesi `data-slot="deploy-record-cards"`.
 */
const TD = 'px-3 py-2 align-top whitespace-normal'

export default function DeployRecords({ rows, narrow, canEdit, onDelete, sort, dir, onSort }) {
  const t = useT()
  const now = Date.now()
  if (narrow) {
    return (
      <ul data-slot="deploy-record-cards" className="m-0 flex list-none flex-col gap-2.5 p-0">
        {rows.map((r) => (
          <li key={r.id} className="min-w-0">
            <DeployCard d={r} duration={recordDuration(r, now)} showEnv canEdit={canEdit} onDelete={onDelete} />
          </li>
        ))}
      </ul>
    )
  }
  const th = (key, label, className) => (
    <SortTh label={label} active={sort === key} dir={dir} onSort={() => onSort(key)} className={className} />
  )
  return (
    <div className="overflow-hidden rounded-lg border">
      <Table data-testid="deploy-table" className="text-[0.86em]">
        <TableHeader className="bg-muted/50">
          <TableRow className="hover:bg-transparent">
            {th('started_at', t('deploy.col.started'))}
            {th('version', t('deploy.col.version'))}
            <TableHead className={TH}>{t('deploy.col.kind')}</TableHead>
            {th('environment', t('deploy.env'))}
            <TableHead className={cn(TH, 'hidden @4xl/deploy:table-cell')}>{t('deploy.col.duration')}</TableHead>
            <TableHead className={cn(TH, 'hidden @3xl/deploy:table-cell')}>{t('deploy.col.commit')}</TableHead>
            <TableHead className={cn(TH, 'hidden @5xl/deploy:table-cell')}>{t('deploy.col.pod')}</TableHead>
            {th('source', t('deploy.col.source'))}
            {canEdit && <TableHead className={cn(TH, 'w-12')}><span className="sr-only">{t('deploy.col.actions')}</span></TableHead>}
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((r) => {
            const dur = recordDuration(r, now)
            const label = deployLabel(r)
            return (
              <TableRow key={r.id} data-current={r.current ? 'true' : undefined} className={cn(r.current && 'bg-success/5 hover:bg-success/10')}>
                <TableCell className={cn(TD, 'whitespace-nowrap')}>
                  <div className="font-mono text-xs" title={formatDateSec(r.startedAt)}>{formatDate(r.startedAt)}</div>
                  <div className="text-xs text-muted-foreground">{fmtAgo(r.startedAt)}</div>
                </TableCell>
                <TableCell className={TD}>
                  <span className="flex flex-wrap items-center gap-1.5">
                    <span className={cn('font-mono font-bold', !r.version && 'text-muted-foreground')}>{r.version ? `v${r.version}` : '—'}</span>
                    {r.current && <CurrentBadge />}
                  </span>
                </TableCell>
                <TableCell className={TD}>
                  <span className="flex flex-col items-start gap-0.5">
                    <KindBadge kind={r.kind} />
                    {r.previousVersion && r.kind !== 'RESTART' && (
                      <span className="text-xs text-muted-foreground">{t('deploy.fromVersion', `v${r.previousVersion}`)}</span>
                    )}
                  </span>
                </TableCell>
                <TableCell className={cn(TD, 'font-medium')}>{r.environment}</TableCell>
                <TableCell className={cn(TD, 'hidden text-xs @4xl/deploy:table-cell')}>
                  {dur ? (
                    <span className={cn(dur.running && 'font-semibold text-success')}>
                      {dur.running ? t('deploy.runningFor', fmtDur(dur.seconds, t)) : fmtDur(dur.seconds, t)}
                    </span>
                  ) : r.source === 'STARTUP' ? (
                    <span className="text-amber-700 dark:text-amber-300" title={t('deploy.endUnrecorded')}>{t('deploy.endUnrecorded')}</span>
                  ) : <span className="text-muted-foreground">—</span>}
                  {r.endReason && <div className="text-muted-foreground">{r.endReason}</div>}
                </TableCell>
                <TableCell className={cn(TD, 'hidden text-xs @3xl/deploy:table-cell')}>
                  {r.commit ? <ShaRef value={r.commit} short={r.commitShort || r.commit.slice(0, 8)} /> : <span className="text-muted-foreground">—</span>}
                </TableCell>
                <TableCell className={cn(TD, 'hidden text-xs @5xl/deploy:table-cell')} title={r.instanceId || undefined}>
                  <div className="max-w-56 truncate font-mono">{r.pod || r.hostname || '—'}</div>
                  {(r.node || r.helm?.revision != null) && (
                    <div className="text-muted-foreground">{[r.node, r.helm?.revision != null && `rev ${r.helm.revision}`].filter(Boolean).join(' · ')}</div>
                  )}
                </TableCell>
                <TableCell className={cn(TD, 'max-w-72 min-w-48')}>
                  <SourceBadge source={r.source} />
                  {r.createdBy && <div className="mt-0.5 text-xs text-muted-foreground">{t('deploy.by', r.createdBy)}</div>}
                  {r.note && (
                    // Uzun not dokunmatikte de okunur: ui/HintPopover (yalnız-hover bilgi YOK).
                    <HintPopover content={r.note.length > 110 ? r.note : null} triggerClassName="mt-0.5 block h-auto w-full text-left whitespace-normal">
                      <span className="line-clamp-2 text-xs [overflow-wrap:anywhere] text-muted-foreground">{r.note}</span>
                    </HintPopover>
                  )}
                </TableCell>
                {canEdit && (
                  <TableCell className={cn(TD, 'text-right')}>
                    {r.source === 'MANUAL' && (
                      <Button type="button" variant="ghost" size="icon-sm" data-action="delete"
                        className="text-destructive hover:bg-destructive/10 hover:text-destructive"
                        title={t('deploy.delete')} aria-label={t('a11y.rowAction', label, t('deploy.delete'))} onClick={() => onDelete?.(r)}>
                        <Trash2 aria-hidden="true" />
                      </Button>
                    )}
                  </TableCell>
                )}
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}
