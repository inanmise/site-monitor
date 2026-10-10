import { useMemo } from 'react'
import { ListChecks, Trophy, Target, ChevronRight, Layers } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import ScoreRing from './ScoreRing.jsx'
import DataQualityTrend from './DataQualityTrend.jsx'
import { BandBadge, DeltaChip, SeverityBadge } from './DataQualityTeamDetail.jsx'
import { DATA_QUALITY_BANDS } from './dataQualityCodes.js'
import { bandColor, bandCounts, hasScore, healthPercent, sortTeams } from './dataQualityModel.js'

/** Bant iyiden kötüye (dağılım çubuğu ve lejant sırası) — katalog sırası. */
const BAND_ORDER = DATA_QUALITY_BANDS

/** Puan → ilerleme çubuğu tonu (bant eşikleriyle aynı yön: yüksek iyi). */
function scoreTone(score) {
  if (!hasScore(score)) return undefined
  return score >= 75 ? 'ok' : score >= 50 ? 'warn' : 'crit'
}

/** Kart başlığı: ikon + başlık + (isteğe bağlı) açıklama. */
function CardHead({ icon: Icon, title, description, id }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <h3 id={id} className="m-0 flex items-center gap-2 text-sm font-semibold">
        <Icon aria-hidden="true" className="size-4 shrink-0 text-primary" />{title}
      </h3>
      {description && <p className="m-0 text-xs leading-relaxed text-muted-foreground">{description}</p>}
    </div>
  )
}

/**
 * Takımların bant dağılımı — tek yatay yığılmış çubuk (iyiden kötüye) + sayılı lejant. Renk tek taşıyıcı değil: lejant
 * her bandı metinle ve sayıyla söyler; çubuk süs (aria-hidden), özet cümlesi metin.
 */
function BandDistribution({ teams }) {
  const t = useT()
  const counts = useMemo(() => bandCounts(teams), [teams])
  const total = teams.length
  const healthy = (counts.EXCELLENT ?? 0) + (counts.GOOD ?? 0)
  const segs = BAND_ORDER.filter((b) => (counts[b] ?? 0) > 0)
  if (total === 0) return null              // takım yoksa liste bölümünün boş durumu söyler (tekrar yok)
  return (
    <div data-slot="dq-band-dist" className="flex min-w-0 flex-col gap-2.5">
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="text-xs font-semibold text-muted-foreground">{t('dq.bandDist.title')}</span>
        <span className="text-sm">{t('dq.bandDist.summary', healthy, total)}</span>
      </div>
      {(
        <>
          <div aria-hidden="true" className="flex h-3 w-full gap-0.5 overflow-hidden rounded-full bg-muted">
            {segs.map((b) => (
              <span key={b} data-slot="dq-band-seg" data-band={b} className="h-full min-w-1 basis-0 first:rounded-l-full last:rounded-r-full"
                style={{ flexGrow: counts[b], background: bandColor(b) }} />
            ))}
          </div>
          <ul className="m-0 grid list-none grid-cols-2 gap-x-4 gap-y-1.5 p-0 text-xs sm:flex sm:flex-wrap sm:gap-x-6">
            {BAND_ORDER.filter((b) => b !== 'NO_DATA' || (counts.NO_DATA ?? 0) > 0).map((b) => (
              <li key={b} data-slot="dq-band-legend" data-band={b} className="flex min-w-0 items-center gap-1.5">
                <span aria-hidden="true" className="size-2 shrink-0 rounded-full" style={{ background: bandColor(b) }} />
                <span className="min-w-0 truncate text-muted-foreground">{t(`dq.band.${b}`)}</span>
                <span className="ml-auto font-semibold tabular-nums sm:ml-0">{counts[b] ?? 0}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}

/**
 * Kurum özeti kartı (`dq-org`): üst satır puan halkası + bant + 7 gün farkı + sayılar | 30 günlük eğilim (geniş),
 * alt satır takımların bant dağılımı (tam genişlik). Telefonda alt alta.
 */
export function OrgHero({ org, teams }) {
  const t = useT()
  return (
    <Card data-slot="dq-org" className="min-w-0 gap-0 overflow-hidden py-0 shadow-xs">
      <CardContent className="grid min-w-0 gap-6 p-4 sm:p-6 md:grid-cols-[auto_minmax(0,1fr)] md:items-center md:gap-x-8">
        <div className="flex min-w-0 items-center gap-4">
          <ScoreRing score={org?.score} band={org?.band} size="lg"
            label={hasScore(org?.score) ? t('dq.orgScoreAria', org.score) : t('dq.band.NO_DATA')} />
          <div className="flex min-w-0 flex-col gap-1.5">
            <span className="text-xs font-semibold text-muted-foreground">{t('dq.orgScore')}</span>
            <div className="flex flex-wrap items-center gap-2">
              <BandBadge band={org?.band} />
              <DeltaChip delta={org?.delta_7d} />
            </div>
            <p className="m-0 text-sm text-muted-foreground">{t('dq.orgCounts', org?.findings ?? 0, org?.items ?? 0)}</p>
          </div>
        </div>
        <div className="flex min-w-0 flex-col gap-1.5 md:border-l md:pl-8">
          <span className="text-xs font-semibold text-muted-foreground">{t('dq.trendTitle')}</span>
          <DataQualityTrend trend={org?.trend} className="aspect-auto h-36 w-full" />
        </div>
        <div className="min-w-0 md:col-span-2 md:border-t md:pt-5">
          <BandDistribution teams={teams} />
        </div>
      </CardContent>
    </Card>
  )
}

/** Kurumda en çok puan kaybettiren kurallar — sıra, önem, kusurlu/uygun, kaybedilen puan, sağlık çubuğu. */
export function OrgIssues({ rules }) {
  const t = useT()
  const top = [...(rules || [])].filter((r) => (r.failing ?? 0) > 0)
    .sort((a, b) => (b.points ?? 0) - (a.points ?? 0)).slice(0, 5)
  return (
    <Card data-slot="dq-org-issues" role="region" aria-labelledby="dq-org-issues-title" className="min-w-0 gap-3 py-4 shadow-xs">
      <CardContent className="flex min-w-0 flex-col gap-3 px-4 sm:px-5">
        <CardHead icon={ListChecks} id="dq-org-issues-title" title={t('dq.orgIssuesTitle')} description={t('dq.orgIssuesDesc')} />
        {top.length === 0 ? (
          <p className="m-0 rounded-lg border border-dashed px-3 py-3 text-sm text-muted-foreground">{t('dq.orgIssuesNone')}</p>
        ) : (
          <ol className="m-0 flex list-none flex-col gap-2 p-0">
            {top.map((r, i) => {
              const pct = healthPercent(r)
              return (
                <li key={r.code} data-slot="dq-org-issue" data-code={r.code}
                  className="flex min-w-0 gap-3 rounded-lg border bg-card px-3 py-2.5">
                  <span aria-hidden="true"
                    className="inline-flex size-6 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-semibold tabular-nums text-muted-foreground">
                    {i + 1}
                  </span>
                  <div className="flex min-w-0 flex-1 flex-col gap-1.5">
                    <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                      <span className="min-w-0 flex-1 text-sm font-medium [overflow-wrap:anywhere]">{t(`dq.rule.${r.code}.title`)}</span>
                      <SeverityBadge severity={r.severity} />
                    </div>
                    <ProgressBar value={pct} max={100} size="sm" decorative tone={pct >= 90 ? 'ok' : pct >= 50 ? 'warn' : 'crit'} />
                    <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
                      <span className="tabular-nums text-muted-foreground">{t('dq.failingOf', r.failing, r.eligible)}</span>
                      {(r.points ?? 0) > 0 && (
                        <span className="font-semibold text-destructive tabular-nums">{t('dq.pointsLost', r.points)}</span>
                      )}
                    </div>
                  </div>
                </li>
              )
            })}
          </ol>
        )}
      </CardContent>
    </Card>
  )
}

/** Karşılaştırma satırı: ad + puan + ince çubuk; tıklayınca düzeltme listesi. */
function LeaderRow({ team, rank, onOpen }) {
  const t = useT()
  return (
    <li>
      <Button type="button" variant="ghost" onClick={() => onOpen(team)} data-slot="dq-leader" data-team-id={String(team.id)}
        aria-label={t('dq.leaders.openAria', team.name, team.score)}
        className="h-auto min-h-12 w-full justify-start gap-3 rounded-lg px-2 py-2 text-left font-normal whitespace-normal">
        <span aria-hidden="true" className="w-4 shrink-0 text-right text-xs font-semibold tabular-nums text-muted-foreground">{rank}</span>
        <span className="flex min-w-0 flex-1 flex-col gap-1">
          <span className="flex min-w-0 items-baseline gap-2">
            <span className="min-w-0 flex-1 truncate text-sm font-medium">{team.name}</span>
            <span className="shrink-0 text-sm font-semibold tabular-nums" style={{ color: bandColor(team.band) }}>{team.score}</span>
          </span>
          <ProgressBar value={team.score} max={100} size="sm" decorative tone={scoreTone(team.score)} />
        </span>
        <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
      </Button>
    </li>
  )
}

/** Karşılaştırma sütunu: başlık + sıralı satırlar. */
function LeaderColumn({ icon: Icon, title, list, tone, onOpen, slot }) {
  return (
    <div className="flex min-w-0 flex-col gap-1.5" data-slot="dq-leader-col" data-kind={slot}>
      <p className={cn('m-0 flex items-center gap-1.5 px-2 text-xs font-semibold', tone)}>
        <Icon aria-hidden="true" className="size-3.5" />{title}
      </p>
      <ol className="m-0 flex list-none flex-col gap-0.5 p-0">
        {list.map((tm, i) => <LeaderRow key={tm.id} team={tm} rank={i + 1} onOpen={onOpen} />)}
      </ol>
    </div>
  )
}

/**
 * Takım karşılaştırması — en yüksek puanlı 3 takım ve öncelikli (en düşük puanlı) 3 takım. Puanı olmayan takım
 * girmez; iki takımdan azsa kart çizilmez (karşılaştırma anlamsız).
 */
export function TeamLeaders({ teams, onOpen }) {
  const t = useT()
  const scored = useMemo(() => (teams || []).filter((x) => hasScore(x.score)), [teams])
  if (scored.length < 2) return null
  const n = Math.min(3, Math.floor(scored.length / 2) || 1)
  const best = sortTeams(scored, 'score_desc').slice(0, n)
  const worst = sortTeams(scored, 'score_asc').slice(0, n)
  return (
    <Card data-slot="dq-leaders" role="region" aria-labelledby="dq-leaders-title" className="min-w-0 gap-3 py-4 shadow-xs">
      <CardContent className="flex min-w-0 flex-col gap-3 px-3 sm:px-4">
        <div className="px-1"><CardHead icon={Layers} id="dq-leaders-title" title={t('dq.leaders.title')} description={t('dq.leaders.desc')} /></div>
        <div className="grid min-w-0 gap-3 sm:grid-cols-2 lg:grid-cols-1 2xl:grid-cols-2">
          <LeaderColumn icon={Target} title={t('dq.leaders.worst')} list={worst} tone="text-destructive" onOpen={onOpen} slot="worst" />
          <LeaderColumn icon={Trophy} title={t('dq.leaders.best')} list={best} tone="text-success" onOpen={onOpen} slot="best" />
        </div>
      </CardContent>
    </Card>
  )
}
