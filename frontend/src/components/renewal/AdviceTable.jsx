import { CalendarPlus, Copy, ExternalLink, Stethoscope } from 'lucide-react'
import { formatDateOnly } from '../../api/client'
import { Button } from '@/components/shadcn/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import TeamBadge from '../ui/TeamBadge.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import { PriorityBadge, ReasonBadge, SharedBadge, PRIORITY_TEXT } from './AdviceParts.jsx'
import { cn } from '@/lib/utils'

const TH = 'bg-muted/60 font-medium text-muted-foreground'

/**
 * Sıkı tablo görünümü (shadcn Table — kendi kabında yatay kayar). Düşük öncelikli sütunlar dar ekranda gizlenir;
 * satır eylemleri tek KebabMenu'de (ad satırı ayırt eder).
 */
export default function AdviceTable({ rows, t, codeLabel, fpCount, onOpen, onPlan, onDiagnose, onCopy }) {
  return (
    <div data-slot="rn-table" className="overflow-hidden rounded-xl border bg-card">
      <Table className="text-sm">
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            <TableHead className={TH}>{t('renewal.colPriority')}</TableHead>
            <TableHead className={TH}>{t('renewal.colDomain')}</TableHead>
            <TableHead className={cn(TH, 'hidden md:table-cell')}>{t('renewal.reason')}</TableHead>
            <TableHead className={TH}>{t('renewal.colDays')}</TableHead>
            <TableHead className={cn(TH, 'hidden md:table-cell')}>{t('renewal.expiry')}</TableHead>
            <TableHead className={cn(TH, 'hidden lg:table-cell')}>{t('inv.colTeam')}</TableHead>
            <TableHead className={cn(TH, 'hidden xl:table-cell')}>{t('renewal.colIssuer')}</TableHead>
            <TableHead className={cn(TH, 'w-12')}><span className="sr-only">{t('renewal.more')}</span></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((a) => {
            const d = a.days_remaining
            const shared = (fpCount.get(a.fingerprint) || 0) > 1 ? fpCount.get(a.fingerprint) : 0
            return (
              <TableRow key={a.domain + a.code} data-priority={a.priority}>
                <TableCell><PriorityBadge priority={a.priority} label={t(`renewal.pri.${a.priority}`)} /></TableCell>
                <TableCell className="max-w-[18rem] whitespace-normal">
                  <span className="flex min-w-0 flex-wrap items-center gap-1.5">
                    {/* `shrink`: shadcn Button `shrink-0` taşır — uzun alan adı sarmak yerine komşu hücrelerin üstüne taşıyordu */}
                    <Button type="button" variant="link" className="h-auto min-w-0 shrink justify-start p-0 text-left font-semibold [overflow-wrap:anywhere] whitespace-normal text-foreground hover:text-primary"
                      onClick={() => onOpen(a.domain)}>
                      {a.domain}{a.port && a.port !== 443 ? `:${a.port}` : ''}
                    </Button>
                    <SharedBadge count={shared} label={shared ? t('renewal.shared', shared) : ''} hint={t('renewal.batchTip')} />
                  </span>
                </TableCell>
                <TableCell className="hidden md:table-cell"><ReasonBadge code={a.code} label={codeLabel(a.code)} /></TableCell>
                <TableCell>
                  {d == null
                    ? <span className="text-muted-foreground">{t('renewal.unknown')}</span>
                    : <span className={cn('whitespace-nowrap', PRIORITY_TEXT[a.priority])}><b className="tabular-nums">{Math.abs(d)}</b> {d < 0 ? t('renewal.daysAgo') : t('renewal.daysLeft')}</span>}
                </TableCell>
                <TableCell className="hidden text-muted-foreground tabular-nums md:table-cell">{a.not_after ? formatDateOnly(a.not_after) : '—'}</TableCell>
                <TableCell className="hidden lg:table-cell">{a.team_name ? <TeamBadge teamId={a.team_id} teamName={a.team_name} /> : <span className="text-muted-foreground">—</span>}</TableCell>
                <TableCell className="hidden max-w-[14rem] truncate text-muted-foreground xl:table-cell" title={a.issuer_cn || ''}>{a.issuer_cn || '—'}</TableCell>
                <TableCell className="text-right">
                  <KebabMenu rowLabel={a.domain} label={t('renewal.more')}
                    items={[
                      { label: t('renewal.openCert'), icon: <ExternalLink aria-hidden="true" />, onClick: () => onOpen(a.domain) },
                      { label: t('renewal.diagnoseShort'), icon: <Stethoscope aria-hidden="true" />, onClick: () => onDiagnose(a), hidden: a.code !== 'UNREACHABLE' },
                      { label: t('renewal.plan'), icon: <CalendarPlus aria-hidden="true" />, onClick: () => onPlan?.(a), hidden: !onPlan },
                      { label: t('renewal.copy'), icon: <Copy aria-hidden="true" />, onClick: () => onCopy(a.domain) },
                    ]} />
                </TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}
