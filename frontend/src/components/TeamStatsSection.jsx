import { Users } from 'lucide-react'
import TeamBadge from './ui/TeamBadge.jsx'
import { useT } from '../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

const STATUSES = [
  { key: 'valid',    countKey: 'valid_count',    domainsKey: 'valid_domains'    },
  { key: 'warning',  countKey: 'warning_count',  domainsKey: 'warning_domains'  },
  { key: 'high',     countKey: 'high_count',     domainsKey: 'high_domains'     },
  { key: 'critical', countKey: 'critical_count', domainsKey: 'critical_domains' },
  { key: 'expired',  countKey: 'expired',        domainsKey: 'expired_domains'  },
]

/** Durum sütunu rengi (başlık + sıfır olmayan sayı) — eski .ts-hdr-* / .ts-cell-*.ts-cell-active. */
const STATUS_INK = {
  valid: 'text-green-500',
  warning: 'text-amber-500',
  high: 'text-orange-500',
  critical: 'text-red-500',
  expired: 'text-zinc-400',
}

/**
 * Takım × kademe (T1/T2) sertifika durum ızgarası. Çizim shadcn Card + Table; sıfır olmayan sayı gerçek bir
 * shadcn Button (klavyeyle odaklanır, adı takım + kademe + durum + sayı) → tıklanınca o alan adları süzülür.
 */
function TierRow({ label, stats, teamName, onStatClick, t }) {
  function click(domainsKey, statusLabel) {
    const domains = stats?.[domainsKey] ?? []
    if (!onStatClick || !domains.length) return
    onStatClick(new Set(domains), `${teamName} — ${label} — ${statusLabel}`)
  }
  return (
    <TableRow className="border-0 hover:bg-transparent">
      <TableCell className="px-2.5 py-2 text-[.82em] font-bold text-muted-foreground">{label}</TableCell>
      {STATUSES.map(({ key, countKey, domainsKey }) => {
        const count = stats?.[countKey] ?? 0
        const statusLabel = t(`ts.${key}`)
        return (
          <TableCell key={key} data-status={key} className="p-1 text-center">
            {count > 0 ? (
              <Button type="button" variant="ghost" size="sm" data-status={key}
                aria-label={`${teamName} · ${label} · ${statusLabel}: ${count}`}
                className={cn('h-10 w-full min-w-10 px-1 text-[1.05em] font-bold tabular-nums', STATUS_INK[key])}
                onClick={() => click(domainsKey, statusLabel)}>
                {count}
              </Button>
            ) : (
              <span className="text-muted-foreground opacity-45">{count}</span>
            )}
          </TableCell>
        )
      })}
    </TableRow>
  )
}

function TeamCard({ team, onStatClick }) {
  const t = useT()
  const total = (team.sy_t1_stats?.total_certificates ?? 0) + (team.sy_t2_stats?.total_certificates ?? 0)
  return (
    <Card data-slot="team-stats-card" className={cn('gap-0 overflow-hidden py-0 shadow-none', total === 0 && 'opacity-40')}>
      <div className="flex items-center gap-[7px] border-b bg-muted/50 px-3.5 py-2 text-[.875em] font-bold">
        <Users size={13} aria-hidden="true" className="shrink-0 text-muted-foreground" />
        <span data-slot="team-stats-name" className="min-w-0 flex-1 truncate">
          <TeamBadge teamId={team.team_id} teamName={team.team_name} size={0} />
        </span>
        <span className="ml-auto shrink-0 rounded-md bg-primary/10 px-2 text-[1.15em] leading-[1.7] font-extrabold text-primary">{total}</span>
      </div>
      <Table className="text-[.92em]">
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            <TableHead className="w-11" />
            {STATUSES.map(({ key }) => (
              <TableHead key={key} className={cn('px-1.5 text-center text-[.76em] font-bold tracking-[.04em] uppercase', STATUS_INK[key])}>
                {t(`ts.${key}`)}
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          <TierRow label="T1" stats={team.sy_t1_stats} teamName={team.team_name} onStatClick={onStatClick} t={t} />
          <TierRow label="T2" stats={team.sy_t2_stats} teamName={team.team_name} onStatClick={onStatClick} t={t} />
        </TableBody>
      </Table>
    </Card>
  )
}

function AdminView({ teams, onStatClick }) {
  const t = useT()

  const visibleTeams = teams
    .map(team => ({
      team,
      total: (team.sy_t1_stats?.total_certificates ?? 0)
           + (team.sy_t2_stats?.total_certificates ?? 0),
    }))
    .filter(entry => entry.total > 0)
    .sort((a, b) => b.total - a.total)
    .map(entry => entry.team)

  return (
    <div className="ts-root">
      <Card className="gap-3 px-3.5 pt-3.5 pb-2.5 shadow-none">
        <div className="flex items-center gap-[7px] text-[.78em] font-bold tracking-[.06em] text-muted-foreground uppercase">
          <Users size={15} aria-hidden="true" className="shrink-0 opacity-65" />
          <span>{t('ts.teamSectionTitle')}</span>
          <Badge data-slot="team-stats-count" className="ml-auto rounded-[10px] px-[7px] font-extrabold">{visibleTeams.length}</Badge>
        </div>
        <div className="grid grid-cols-[repeat(auto-fill,minmax(min(440px,100%),1fr))] gap-3.5">
          {visibleTeams.map((team) => (
            <TeamCard key={team.team_id} team={team} onStatClick={onStatClick} />
          ))}
        </div>
      </Card>
    </div>
  )
}

function PersonalView({ data, onStatClick }) {
  const t = useT()
  const team = {
    team_id:     data.team_id,
    team_name:   data.team_name,
    sy_t1_stats: data.sy_t1_stats,
    sy_t2_stats: data.sy_t2_stats,
  }
  return (
    <div className="ts-root">
      <div className="mb-2 text-[.78em] font-bold tracking-[.07em] text-muted-foreground uppercase">
        {t('ts.title')}{data.team_name ? ` — ${data.team_name}` : ''}
      </div>
      <TeamCard team={team} onStatClick={onStatClick} />
    </div>
  )
}

export default function TeamStatsSection({ data, visible, onStatClick }) {
  if (!visible || !data || Object.keys(data).length === 0) return null

  if (data.mode === 'all_teams') {
    return <AdminView teams={data.teams ?? []} onStatClick={onStatClick} />
  }
  if (data.mode === 'personal') {
    return <PersonalView data={data} onStatClick={onStatClick} />
  }
  return null
}
