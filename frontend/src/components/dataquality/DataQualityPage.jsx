// Takım Veri Kalitesi Puanı (2026-10-10, kullanıcı isteği: "sahipsiz kayıt, eksik iletişim bilgisi, yanlış katman, 7/24'e
// bildirilmeyen kritik izleme gibi sorunları tek bir skora ve düzeltme listesine çevir"). Kurum puanı (kıyas) + görünür
// takımların sıralaması + takım başına düzeltme listesi. Kapsam ve puan sunucuda (GET /api/data-quality). URL: dq_*.
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ClipboardCheck, RefreshCw, Search, Info, BadgeCheck, ThumbsUp, TriangleAlert, OctagonAlert, UserX, ListChecks,
  Users, MoonStar, CircleHelp,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { useElementWidthState } from '../../hooks/useElementWidth.js'
import { useUrlQuerySync, readUrlParam } from '../../hooks/useUrlQuerySync.js'
import { navigateTo } from '../../utils/navigate.js'
import { relTimeOrRaw } from '../../utils/relativeTime.js'
import PageHeader from '../ui/PageHeader.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { LoadingBlock, ProgressBar } from '../ui/Progress.jsx'
import MonitorStatsBar from '../MonitorStatsBar.jsx'
import ScoreRing from './ScoreRing.jsx'
import DataQualityTrend from './DataQualityTrend.jsx'
import DataQualityTeamDetail, { BandBadge, DeltaChip, SeverityBadge } from './DataQualityTeamDetail.jsx'
import {
  DEFAULT_SORT, NOC_SETTINGS_LINK, SORTS, bandCounts, bandOf, filterTeams, hasScore, healthPercent, normalizeSummary,
  sortTeams,
} from './dataQualityModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'

const BAND_TILES = [
  { key: 'EXCELLENT', Icon: BadgeCheck, cls: 'valid' },
  { key: 'GOOD', Icon: ThumbsUp, cls: 'total' },
  { key: 'NEEDS_ATTENTION', Icon: TriangleAlert, cls: 'warning' },
  { key: 'POOR', Icon: OctagonAlert, cls: 'critical' },
]
/** Kart ↔ tablo eşiği (liste KABININ genişliği — 768 px tablette kenar çubuğu açıkken içerik daralır). */
const TABLE_MIN_PX = 720

/** "Nasıl hesaplanır" balonu — formül + bantlar (eşikler sunucu yapılandırmasından). Dokunmatikte de açılır (Popover). */
function HowItWorks({ config }) {
  const t = useT()
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button type="button" variant="outline" size="sm" data-action="dq-how" className="max-sm:h-10">
          <CircleHelp aria-hidden="true" />{t('dq.howButton')}
        </Button>
      </PopoverTrigger>
      <PopoverContent side="bottom" align="end" sideOffset={6} collisionPadding={8} aria-label={t('dq.howTitle')}
        data-slot="dq-how" className="z-(--z-menu) flex w-[min(24rem,calc(100vw-2rem))] flex-col gap-2 text-sm">
        <p className="font-semibold">{t('dq.howTitle')}</p>
        <p>{t('dq.howFormula')}</p>
        <p>{t('dq.howWeights')}</p>
        <p>{t('dq.howBands', config.band_excellent ?? 90, config.band_good ?? 75, config.band_fair ?? 50)}</p>
        <p className="text-muted-foreground">{t('dq.howScope')}</p>
      </PopoverContent>
    </Popover>
  )
}

/** Kurum puanı kartı: halka + bant + 7 gün farkı + sayılar + 30 günlük eğilim. */
function OrgScoreCard({ org }) {
  const t = useT()
  return (
    <Card data-slot="dq-org" className="gap-0 py-0">
      <CardContent className="flex flex-col gap-4 p-4 sm:p-5">
        <div className="flex items-center gap-4">
          <ScoreRing score={org.score} band={org.band} size="lg"
            label={hasScore(org.score) ? t('dq.orgScoreAria', org.score) : t('dq.band.NO_DATA')} />
          <div className="min-w-0">
            <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{t('dq.orgScore')}</div>
            <div className="mt-1 flex flex-wrap items-center gap-2">
              <BandBadge band={org.band} />
              <DeltaChip delta={org.delta_7d} />
            </div>
            <p className="mt-2 text-sm text-muted-foreground">{t('dq.orgCounts', org.findings ?? 0, org.items ?? 0)}</p>
          </div>
        </div>
        <div>
          <div className="mb-1 text-xs font-semibold text-muted-foreground">{t('dq.trendTitle')}</div>
          <DataQualityTrend trend={org.trend} />
        </div>
      </CardContent>
    </Card>
  )
}

/** Kurumda en çok puan kaybettiren kurallar (sağlık çubuğuyla). */
function OrgIssues({ rules }) {
  const t = useT()
  const top = [...(rules || [])].filter((r) => (r.failing ?? 0) > 0)
    .sort((a, b) => (b.points ?? 0) - (a.points ?? 0)).slice(0, 5)
  return (
    <Card data-slot="dq-org-issues" className="gap-0 py-0">
      <CardContent className="flex flex-col gap-3 p-4 sm:p-5">
        <div className="flex items-center gap-2">
          <ListChecks aria-hidden="true" className="size-4 text-muted-foreground" />
          <h3 className="text-sm font-semibold">{t('dq.orgIssuesTitle')}</h3>
        </div>
        {top.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('dq.orgIssuesNone')}</p>
        ) : (
          <ul className="m-0 flex list-none flex-col gap-3 p-0">
            {top.map((r) => {
              const pct = healthPercent(r)
              return (
                <li key={r.code} data-slot="dq-org-issue" data-code={r.code} className="flex flex-col gap-1.5">
                  <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                    <span className="min-w-0 flex-1 text-sm font-medium">{t(`dq.rule.${r.code}.title`)}</span>
                    <SeverityBadge severity={r.severity} />
                    <span className="text-xs tabular-nums text-muted-foreground">{t('dq.failingOf', r.failing, r.eligible)}</span>
                  </div>
                  <ProgressBar value={pct} max={100} size="sm" decorative tone={pct >= 90 ? 'ok' : pct >= 50 ? 'warn' : 'crit'} />
                </li>
              )
            })}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}

/** Takımın en çok puan kaybettiren sorunları (kısa liste). */
function TopIssues({ issues, compact = false }) {
  const t = useT()
  if (!issues?.length) return <span className="text-xs text-success">{t('dq.noIssues')}</span>
  const list = compact ? issues.slice(0, 1) : issues
  return (
    <ul className="m-0 flex list-none flex-col gap-1 p-0">
      {list.map((i) => (
        <li key={i.code} className="flex min-w-0 items-baseline gap-2 text-xs">
          <span className="min-w-0 truncate" title={t(`dq.rule.${i.code}.title`)}>{t(`dq.rule.${i.code}.title`)}</span>
          <span className="shrink-0 font-semibold text-destructive tabular-nums">{t('dq.pointsLost', i.points)}</span>
        </li>
      ))}
    </ul>
  )
}

/** Telefon / dar kap: takım kartları. */
function TeamCards({ teams, onOpen }) {
  const t = useT()
  return (
    <ul className="m-0 grid list-none grid-cols-1 gap-3 p-0 sm:grid-cols-[repeat(auto-fit,minmax(min(320px,100%),1fr))]" data-slot="dq-team-cards">
      {teams.map((tm) => (
        <li key={tm.id}>
          <Card data-slot="dq-team-card" data-team-id={String(tm.id)} data-band={bandOf(tm.band)} className="h-full gap-0 py-0">
            <CardContent className="flex h-full flex-col gap-3 p-4">
              <div className="flex items-center gap-3">
                <ScoreRing score={tm.score} band={tm.band} size="md"
                  label={hasScore(tm.score) ? t('dq.teamScoreAria', tm.name, tm.score) : t('dq.band.NO_DATA')} />
                <div className="min-w-0 flex-1">
                  <div className="truncate font-semibold" title={tm.name}>{tm.name}</div>
                  <div className="mt-1 flex flex-wrap items-center gap-1.5">
                    <BandBadge band={tm.band} />
                    <DeltaChip delta={tm.delta_7d} />
                  </div>
                  <div className="mt-1 text-xs text-muted-foreground">{t('dq.findingsCount', tm.findings ?? 0)}</div>
                </div>
              </div>
              <div className="flex-1"><TopIssues issues={tm.top_issues} /></div>
              <Button type="button" variant="outline" className="h-10 w-full" data-action="dq-team-open"
                aria-label={t('dq.openTeamAria', tm.name)} onClick={() => onOpen(tm)}>
                <ListChecks aria-hidden="true" />{t('dq.openTeam')}
              </Button>
            </CardContent>
          </Card>
        </li>
      ))}
    </ul>
  )
}

/** Geniş kap: sıralama tablosu. */
function TeamTable({ teams, onOpen }) {
  const t = useT()
  return (
    <div className="overflow-x-auto rounded-xl border">
      <Table data-slot="dq-team-table" className="table-fixed">
        <TableHeader>
          <TableRow>
            <TableHead className="w-14 text-right">{t('dq.col.rank')}</TableHead>
            <TableHead className="w-[24%]">{t('dq.col.team')}</TableHead>
            <TableHead className="w-[72px]">{t('dq.col.score')}</TableHead>
            <TableHead className="w-40">{t('dq.col.band')}</TableHead>
            <TableHead className="w-16 text-right">{t('dq.col.findings')}</TableHead>
            <TableHead>{t('dq.col.topIssue')}</TableHead>
            <TableHead className="w-32 text-right">{t('dq.col.actions')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {teams.map((tm, i) => (
            <TableRow key={tm.id} data-slot="dq-team-row" data-team-id={String(tm.id)} data-band={bandOf(tm.band)}>
              <TableCell className="text-right text-muted-foreground tabular-nums">{i + 1}</TableCell>
              <TableCell><span className="block truncate font-medium" title={tm.name}>{tm.name}</span></TableCell>
              <TableCell>
                <ScoreRing score={tm.score} band={tm.band} size="sm"
                  label={hasScore(tm.score) ? t('dq.teamScoreAria', tm.name, tm.score) : t('dq.band.NO_DATA')} />
              </TableCell>
              <TableCell>
                <div className="flex flex-wrap items-center gap-1.5"><BandBadge band={tm.band} /><DeltaChip delta={tm.delta_7d} compact /></div>
              </TableCell>
              <TableCell className="text-right tabular-nums">{tm.findings ?? 0}</TableCell>
              <TableCell className="min-w-0"><TopIssues issues={tm.top_issues} compact /></TableCell>
              <TableCell className="text-right">
                <Button type="button" size="sm" variant="outline" data-action="dq-team-open"
                  aria-label={t('dq.openTeamAria', tm.name)} onClick={() => onOpen(tm)}>
                  <ListChecks aria-hidden="true" />{t('dq.openTeam')}
                </Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

export default function DataQualityPage({ globalAdmin = false }) {
  const t = useT()
  const phone = useIsMobile()
  const [boxWidth, setBoxEl] = useElementWidthState()
  const [q, setQ] = useState(() => readUrlParam('dq_q', ''))
  const [band, setBand] = useState(() => readUrlParam('dq_band', ''))
  const [sort, setSort] = useState(() => (SORTS.includes(readUrlParam('dq_sort', '')) ? readUrlParam('dq_sort', '') : DEFAULT_SORT))
  const [teamKey, setTeamKey] = useState(() => readUrlParam('dq_team', null))
  useUrlQuerySync({ dq_q: q || null, dq_band: band || null, dq_sort: sort === DEFAULT_SORT ? null : sort, dq_team: teamKey })

  const [state, setState] = useState({ loading: true, error: null, data: null })
  const seq = useRef(0)
  const load = useCallback(async (fresh = false) => {
    const mine = ++seq.current
    setState((s) => ({ ...s, loading: true, error: null }))
    try {
      const res = await api.dataQuality.summary(fresh)
      if (mine !== seq.current) return
      if (!res?.success) throw new Error(res?.error || t('dq.loadFailed'))
      setState({ loading: false, error: null, data: normalizeSummary(res.data) })
    } catch (e) {
      if (mine !== seq.current) return
      setState((s) => ({ loading: false, error: e?.message || t('dq.loadFailed'), data: s.data }))
    }
  }, [t])
  useEffect(() => { load(false) }, [load])

  const data = state.data
  const teams = useMemo(() => data?.teams ?? [], [data])
  const counts = useMemo(() => bandCounts(teams), [teams])
  const shown = useMemo(() => sortTeams(filterTeams(teams, { q, band }), sort), [teams, q, band, sort])
  const cards = boxWidth > 0 ? boxWidth < TABLE_MIN_PX : phone
  const teamName = (key) => teams.find((x) => String(x.id) === String(key))?.name ?? ''

  const tiles = BAND_TILES.map((b) => ({
    key: b.key, Icon: b.Icon, cls: b.cls, value: counts[b.key] ?? 0, label: t(`dq.band.${b.key}`),
    hint: t(`dq.bandHint.${b.key}`),
  }))

  const nocNote = data?.notes?.find((n) => n.code === 'NOC_NOT_CONFIGURED')
  const hygieneNote = data?.notes?.some((n) => n.code === 'HYGIENE_UNAVAILABLE')
  const filtersActive = !!(q || band)

  return (
    <div className="flex min-w-0 flex-col gap-4" data-slot="dq-page">
      <PageHeader icon={ClipboardCheck} title={t('dq.title')} description={t('dq.desc')}
        meta={data ? (
          <>
            <Badge variant="outline" className="gap-1"><Users aria-hidden="true" className="size-3" />{t('dq.teamsCount', teams.length)}</Badge>
            {data.generatedAt && <span>{t('dq.generatedAt', relTimeOrRaw(data.generatedAt, t))}</span>}
          </>
        ) : null}
        actions={(
          <>
            <HowItWorks config={data?.config ?? {}} />
            <Button type="button" variant="outline" size="sm" onClick={() => load(true)} disabled={state.loading}
              aria-busy={state.loading || undefined} data-action="dq-refresh">
              <RefreshCw aria-hidden="true" />{t('dq.refresh')}
            </Button>
          </>
        )} />

      {state.loading && !data && <LoadingBlock label={t('dq.loading')} />}
      {state.error && !data && (
        <StatusBlock tone="danger" title={t('dq.loadFailedTitle')} description={state.error}
          actions={<Button type="button" variant="outline" onClick={() => load(true)}>{t('dq.retry')}</Button>} />
      )}
      {state.error && data && (
        <AlertBanner tone="danger" className="mb-0" title={t('dq.refreshFailed')}>{state.error}</AlertBanner>
      )}

      {data && (
        <>
          {nocNote && (
            <AlertBanner tone="warning" icon={MoonStar} className="mb-0" title={t('dq.note.NOC_NOT_CONFIGURED')}>
              <span className="block">
                {t(`dq.nocReason.${nocNote.params?.reason ?? 'UNKNOWN'}`)} {t('dq.nocNoteBody', nocNote.params?.critical ?? 0)}
              </span>
              {globalAdmin && (
                <Button type="button" variant="outline" size="sm" className="mt-2 h-10 sm:h-8"
                  onClick={() => navigateTo(NOC_SETTINGS_LINK.tab, NOC_SETTINGS_LINK.params)}>{t('dq.nocSettings')}</Button>
              )}
            </AlertBanner>
          )}
          {hygieneNote && (
            <AlertBanner tone="info" icon={Info} className="mb-0" title={t('dq.note.HYGIENE_UNAVAILABLE')}>
              {t('dq.hygieneNoteBody')}
            </AlertBanner>
          )}

          <section aria-label={t('dq.orgSection')} className="grid min-w-0 grid-cols-1 gap-4 lg:grid-cols-2">
            {data.org ? <OrgScoreCard org={data.org} /> : null}
            <OrgIssues rules={data.org?.rules} />
          </section>

          {data.unassigned && (data.unassigned.findings ?? 0) > 0 && (
            <AlertBanner tone="danger" icon={UserX} className="mb-0" title={t('dq.unassignedTitle')}>
              <span className="block">{t('dq.unassignedSummary', data.unassigned.findings)}</span>
              <Button type="button" variant="outline" size="sm" className="mt-2 h-10 sm:h-8" data-action="dq-unassigned-open"
                onClick={() => setTeamKey('unassigned')}>{t('dq.unassignedOpen')}</Button>
            </AlertBanner>
          )}

          <section aria-label={t('dq.teamsSection')} className="flex min-w-0 flex-col gap-3">
            <MonitorStatsBar items={tiles} activeFilter={band || null}
              onStatClick={(k) => setBand((cur) => (cur === k ? '' : k))} />

            <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
              <InputGroup className="w-full max-sm:h-10 sm:max-w-xs">
                <InputGroupInput type="search" value={q} onChange={(e) => setQ(e.target.value)} data-page-search
                  placeholder={t('dq.searchTeams')} aria-label={t('dq.searchTeams')} />
                <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
              </InputGroup>
              <NativeSelect value={sort} onChange={(e) => setSort(e.target.value)} aria-label={t('dq.sortLabel')}
                className="w-full max-sm:h-10 sm:w-auto">
                {SORTS.map((s) => <NativeSelectOption key={s} value={s}>{t(`dq.sort.${s}`)}</NativeSelectOption>)}
              </NativeSelect>
              {filtersActive && (
                <Button type="button" variant="ghost" size="sm" className="h-10 sm:h-8"
                  onClick={() => { setQ(''); setBand('') }}>{t('dq.clearFilters')}</Button>
              )}
              <span className="text-xs text-muted-foreground sm:ml-auto" aria-live="polite">
                {t('dq.shownCount', shown.length, teams.length)}
              </span>
            </div>

            <div ref={setBoxEl} className="min-w-0">
              {teams.length === 0 ? (
                <StatusBlock tone="neutral" icon={Users} title={t('dq.noTeamsTitle')} description={t('dq.noTeamsBody')} />
              ) : shown.length === 0 ? (
                <StatusBlock tone="neutral" icon={Search} title={t('dq.noMatchTitle')} description={t('dq.noMatchTeams')} />
              ) : cards ? (
                <TeamCards teams={shown} onOpen={(tm) => setTeamKey(String(tm.id))} />
              ) : (
                <TeamTable teams={shown} onOpen={(tm) => setTeamKey(String(tm.id))} />
              )}
            </div>
          </section>
        </>
      )}

      {teamKey && (
        <DataQualityTeamDetail key={teamKey} teamKey={teamKey} teamName={teamName(teamKey)}
          onClose={() => setTeamKey(null)} />
      )}
    </div>
  )
}
