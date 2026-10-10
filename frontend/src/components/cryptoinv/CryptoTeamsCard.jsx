import { ListFilter, Users } from 'lucide-react'
import { formatDateOnly } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import TeamBadge from '../ui/TeamBadge.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { CATEGORIES } from './cryptoInventoryModel.js'

/** Kap bu genişliğin altında kart görünümüne geçer (ölçüm yoksa — jsdom — tablo). */
const TABLE_MIN_WIDTH = 880
const NUM = 'text-right tabular-nums'
/** Sayı sütunu başlığı: dar kapta iki satıra sarar ("Below 2030", "Top score") — tablo kaba sığsın. */
const HEAD_NUM = 'text-right align-bottom leading-tight whitespace-normal'
const CAT_INK = { BROKEN: 'text-destructive', LEGACY: 'text-amber-700 dark:text-amber-300', MODERN: '', PQC_READY: 'text-success', UNKNOWN: 'text-muted-foreground' }

function Count({ n, ink }) {
  return <span className={cn('font-semibold tabular-nums', n > 0 ? ink : 'font-normal text-muted-foreground')}>{n}</span>
}

/**
 * Takım bazlı geçiş özeti (2026-10-10): takım başına kategori sayıları, P1/P2, en yüksek puan, en yakın geçiş tarihi.
 * "Listele" geçiş listesini o takıma süzer ve listeye kaydırır. Geniş kapta tablo, dar kapta kart. Takımsız kayıtlar
 * (yalnız global görünümde var) en sonda. Test kancaları: `data-slot="cinv-teams"`, satır `cinv-team-row` (`data-team`).
 */
export default function CryptoTeamsCard({ teams, unowned, activeTeam, onPick }) {
  const t = useT()
  const [measureRef, width] = useElementWidth()
  const tableMode = width === 0 || width >= TABLE_MIN_WIDTH
  const rows = [...(teams || [])]
  if ((unowned?.total ?? 0) > 0) rows.push({ ...unowned, team_id: null, team_name: null, _none: true })
  const keyOf = (r) => (r._none ? 'none' : String(r.team_id))

  const pickButton = (r) => {
    const name = r._none ? t('cinv.noTeam') : r.team_name
    const on = activeTeam === keyOf(r)
    return (
      <Button type="button" variant={on ? 'secondary' : 'outline'} size="sm" aria-pressed={on} onClick={() => onPick(keyOf(r))}
        aria-label={t('cinv.teamShow', name)} className="min-h-10 sm:min-h-8">
        <ListFilter aria-hidden="true" />{t('cinv.teamShowShort')}
      </Button>
    )
  }
  const teamCell = (r) => (r._none
    ? <span className="text-muted-foreground">{t('cinv.noTeam')}</span>
    : <TeamBadge teamId={r.team_id} teamName={r.team_name} />)

  return (
    <Card data-slot="cinv-teams" className="min-w-0 gap-3 py-4 shadow-xs">
      <CardHeader className="gap-1 px-4 sm:px-5">
        <CardTitle role="heading" aria-level={3} className="flex items-center gap-2 text-base">
          <Users aria-hidden="true" className="size-4 shrink-0 text-primary" />{t('cinv.teamsTitle')}
        </CardTitle>
        <CardDescription>{t('cinv.teamsDesc')}</CardDescription>
      </CardHeader>
      <CardContent className="min-w-0 px-2 sm:px-3">
        <div ref={measureRef} className="min-w-0">
        {rows.length === 0 ? <StatusBlock tone="neutral" icon={Users} title={t('cinv.noData')} className="py-6 md:py-6" /> : tableMode ? (
          <Table className="text-[0.88em]">
            <TableHeader className="bg-muted/50">
              <TableRow>
                <TableHead className="min-w-40">{t('cinv.col.team')}</TableHead>
                <TableHead className={HEAD_NUM}>{t('cinv.col.total')}</TableHead>
                {CATEGORIES.map((c) => <TableHead key={c} className={HEAD_NUM}>{t(`cinv.catShort.${c}`)}</TableHead>)}
                <TableHead className={HEAD_NUM} title={t('cinv.band.P1')}>P1</TableHead>
                <TableHead className={HEAD_NUM} title={t('cinv.band.P2')}>P2</TableHead>
                <TableHead className={HEAD_NUM}>{t('cinv.col.topScore')}</TableHead>
                <TableHead className="align-bottom leading-tight whitespace-normal">{t('cinv.col.nextMigrate')}</TableHead>
                <TableHead><span className="sr-only">{t('cinv.col.actions')}</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((r) => (
                <TableRow key={keyOf(r)} data-slot="cinv-team-row" data-team={keyOf(r)} data-state={activeTeam === keyOf(r) ? 'selected' : undefined}>
                  <TableCell className="whitespace-normal"><div className="max-w-56 min-w-0 [&_[data-slot=team-badge]]:max-w-full">{teamCell(r)}</div></TableCell>
                  <TableCell className={NUM}>{r.total ?? 0}</TableCell>
                  {CATEGORIES.map((c) => <TableCell key={c} className={NUM}><Count n={r.by_category?.[c] ?? 0} ink={CAT_INK[c]} /></TableCell>)}
                  <TableCell className={NUM}><Count n={r.by_band?.P1 ?? 0} ink="text-destructive" /></TableCell>
                  <TableCell className={NUM}><Count n={r.by_band?.P2 ?? 0} ink="text-amber-700 dark:text-amber-300" /></TableCell>
                  <TableCell className={cn(NUM, 'font-semibold')}>{r.top_score ?? 0}</TableCell>
                  <TableCell className="whitespace-nowrap">{r.next_migrate_by ? formatDateOnly(r.next_migrate_by) : '—'}</TableCell>
                  <TableCell className="text-right">{pickButton(r)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ) : (
          <ul className="m-0 flex list-none flex-col gap-2 p-0 px-1">
            {rows.map((r) => (
              <li key={keyOf(r)} data-slot="cinv-team-row" data-team={keyOf(r)}
                className={cn('min-w-0 rounded-lg border bg-card p-3', activeTeam === keyOf(r) && 'border-primary')}>
                <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
                  <span className="min-w-0">{teamCell(r)}</span>
                  {pickButton(r)}
                </div>
                <dl className="m-0 mt-2 grid grid-cols-3 gap-x-3 gap-y-1.5 text-sm">
                  <div className="flex flex-col"><dt className="text-xs text-muted-foreground">{t('cinv.col.total')}</dt><dd className="m-0 font-semibold tabular-nums">{r.total ?? 0}</dd></div>
                  {CATEGORIES.map((c) => (
                    <div key={c} className="flex min-w-0 flex-col">
                      <dt className="truncate text-xs text-muted-foreground">{t(`cinv.catShort.${c}`)}</dt>
                      <dd className="m-0"><Count n={r.by_category?.[c] ?? 0} ink={CAT_INK[c]} /></dd>
                    </div>
                  ))}
                  <div className="flex flex-col"><dt className="text-xs text-muted-foreground">{t('cinv.band.P1')}</dt><dd className="m-0"><Count n={r.by_band?.P1 ?? 0} ink="text-destructive" /></dd></div>
                  <div className="flex flex-col"><dt className="text-xs text-muted-foreground">{t('cinv.col.topScore')}</dt><dd className="m-0 font-semibold tabular-nums">{r.top_score ?? 0}</dd></div>
                  <div className="col-span-2 flex flex-col"><dt className="text-xs text-muted-foreground">{t('cinv.col.nextMigrate')}</dt><dd className="m-0">{r.next_migrate_by ? formatDateOnly(r.next_migrate_by) : '—'}</dd></div>
                </dl>
              </li>
            ))}
          </ul>
        )}
        </div>
      </CardContent>
    </Card>
  )
}
