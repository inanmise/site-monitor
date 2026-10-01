// Takım fırtına durum kartları (2026-09-30) — Alarm Fırtınası sayfası (tam) ve Ayarlar → Alarm Fırtınası canlı paneli (compact).
import { CloudLightning, BellRing, Eye, Clock, Gauge } from 'lucide-react'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { formatIncidentTime } from '../../utils/incidentMeta.js'
import TeamBadge from '../ui/TeamBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { Progress } from '@/components/shadcn/progress'
import { cn } from '@/lib/utils'
import { STATUS_META, sortTeams, windowPct, sealRemainingMs, formatShortDuration, reasonKey } from './stormModel.js'

const TONE = {
  critical: 'border-destructive/40 bg-destructive/10 text-destructive',
  warning:  'border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-300',
  info:     'border-sky-500/40 bg-sky-500/10 text-sky-700 dark:text-sky-300',
  ok:       'border-emerald-500/40 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300',
}

export function StormStatusBadge({ status, className }) {
  const t = useT()
  const meta = STATUS_META[status] || STATUS_META.CALM
  return (
    <Badge variant="outline" data-slot="sf-status" data-status={status} className={cn('gap-1', TONE[meta.tone], className)}>
      {status === 'STORM' && <CloudLightning aria-hidden="true" className="size-3" />}
      {t(meta.key)}
    </Badge>
  )
}

/** Tek fırtına özeti (açık): kimlik, açılış, üye/hedef, mühür geri sayımı, sonraki tekrar. */
export function StormSummary({ storm, onDetail, compact = false }) {
  const t = useT()
  const locale = useDateLocale()
  const remain = sealRemainingMs(storm)
  return (
    <div data-slot="sf-storm" data-storm-id={storm.id} data-sealed={storm.sealed ? 'true' : 'false'}
      className="flex flex-col gap-1.5 rounded-md border bg-muted/30 p-2.5 text-xs">
      <div className="flex flex-wrap items-center gap-1.5">
        <Badge variant="outline" className="gap-1 font-semibold"><CloudLightning aria-hidden="true" className="size-3" />#{storm.id}</Badge>
        {storm.sealed
          ? <Badge variant="secondary" data-slot="sf-sealed">{t('sf.storm.sealed')}</Badge>
          : remain != null && <Badge variant="outline" data-slot="sf-seal-in" className="tabular-nums">{t('sf.storm.sealIn', formatShortDuration(remain, t))}</Badge>}
        {storm.root_cause && <Badge variant="outline">{t('sf.storm.rootCause')}: {storm.root_cause}</Badge>}
        <span className="ml-auto text-muted-foreground"><Clock aria-hidden="true" className="mr-1 inline size-3" />{formatIncidentTime(storm.created_at, locale)}</span>
      </div>
      <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-muted-foreground">
        <span>{t('sf.storm.activeDown', storm.active_down ?? storm.member_count ?? 0)}</span>
        <span>{t('sf.storm.members', storm.member_count ?? storm.active_members ?? 0)}</span>
        {storm.resolve_floor != null && <span>{t('sf.storm.floor', storm.resolve_floor)}</span>}
        {storm.threshold_effective != null && <span>{t('sf.storm.thresholdSnap', storm.threshold_effective, storm.targets_at_open ?? '—', storm.peak_targets ?? '—')}</span>}
        {storm.trigger?.domain && <span className="truncate">{t('sf.storm.trigger')}: {storm.trigger.domain}</span>}
      </div>
      {!compact && (
        <div className="flex flex-wrap gap-1.5 pt-0.5">
          <Button type="button" size="sm" variant="outline" className="pointer-coarse:h-10" onClick={() => onDetail?.(storm.id)}>
            <Eye aria-hidden="true" />{t('sf.action.detail')}
          </Button>
          {storm.team_id != null && (
            <Button type="button" size="sm" variant="ghost" className="pointer-coarse:h-10"
              onClick={() => navigateTo('alerthistory', { view: 'open', team: String(storm.team_id) })}>
              <BellRing aria-hidden="true" />{t('sf.action.teamAlerts')}
            </Button>
          )}
        </div>
      )}
    </div>
  )
}

/** Takım kartı: durum, pencere doluluk çubuğu, kural, son fırtına, açık fırtınalar. */
export function TeamStormCard({ team, onDetail, compact = false }) {
  const t = useT()
  const locale = useDateLocale()
  const pct = windowPct(team)
  return (
    <Card data-slot="sf-team" data-team-id={team.team_id} data-status={team.status} className="gap-0 py-0 shadow-none">
      <CardContent className={cn('flex flex-col gap-2', compact ? 'p-3' : 'p-3.5')}>
        <div className="flex flex-wrap items-center gap-1.5">
          <TeamBadge teamId={team.team_id} teamName={team.team_name} size={12} static />
          <StormStatusBadge status={team.status} className="ml-auto" />
        </div>
        <div className="flex flex-col gap-1">
          <div className="flex items-center justify-between gap-2 text-xs">
            <span className="text-muted-foreground">{t('sf.window')} · {t('sf.rule', team.window_minutes ?? '—', team.threshold ?? '—')}</span>
            <span className="tabular-nums font-semibold" data-slot="sf-window">{t('sf.windowTargets', team.window_targets ?? 0, team.threshold ?? 0)}</span>
          </div>
          <Progress value={pct} aria-label={t('sf.window')} className="h-2" data-tone={team.status} />
        </div>
        {!compact && (
          <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
            <span><Gauge aria-hidden="true" className="mr-1 inline size-3" />{t('sf.activeMonitors', team.active_monitors ?? 0)}</span>
            <span>{t('sf.windowAlerts', team.window_alerts ?? 0)}</span>
            <span>{t('sf.lastStorm')}: {team.last_storm_at ? formatIncidentTime(team.last_storm_at, locale) : t('sf.never')}</span>
            <span>{t('sf.storms30', team.storms_30d ?? 0)}</span>
          </div>
        )}
        {(team.storms || []).map((s) => <StormSummary key={s.id} storm={s} onDetail={onDetail} compact={compact} />)}
        {!compact && team.status !== 'STORM' && (team.window_items || []).length > 0 && (
          <ul className="m-0 flex list-none flex-col gap-0.5 p-0 text-xs" data-slot="sf-window-items">
            {team.window_items.slice(0, 5).map((it) => (
              <li key={it.id} className="flex items-center gap-1.5 truncate text-muted-foreground">
                <span className="truncate">{it.domain}</span>
                <Badge variant="outline" className="text-[10px]">{it.alert_type}</Badge>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}

/** Kart ızgarası (RESPONSIVE.md: auto-fit ≥ 340 px). Boş → sayfanın StatusBlock'u. */
export default function StormTeamCards({ teams, onDetail, compact = false }) {
  const sorted = sortTeams(teams)
  return (
    <div data-slot="sf-teams" className={cn('grid gap-3', compact ? 'grid-cols-1 lg:grid-cols-2' : 'grid-cols-[repeat(auto-fit,minmax(min(340px,100%),1fr))]')}>
      {sorted.map((tm) => <TeamStormCard key={tm.team_id} team={tm} onDetail={onDetail} compact={compact} />)}
    </div>
  )
}

/** Kapanış nedeni rozeti (geçmiş/analiz). */
export function ReasonBadge({ storm }) {
  const t = useT()
  const key = reasonKey(storm)
  const tone = !storm?.resolved ? 'critical' : storm.resolve_reason === 'SEALED' ? 'info' : storm.resolve_reason === 'FLOOR' ? 'ok' : 'warning'
  return <Badge variant="outline" data-slot="sf-reason" data-reason={storm?.resolved ? storm.resolve_reason || 'UNKNOWN' : 'OPEN'} className={TONE[tone]}>{t(key)}</Badge>
}
