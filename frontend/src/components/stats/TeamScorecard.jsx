import { useMemo, useState } from 'react'
import { ArrowDown, ArrowUp, ArrowUpDown, Users } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { useElementWidthState } from '../../hooks/useElementWidth.js'
import StatusBlock from '../ui/StatusBlock.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { healthTone, sortScorecard, statsNum, statsPct } from './statsInsights.js'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * Birleşik takım karnesi (2026-11-13, İstatistikler #13) — eski "takım karşılaştırması"nın yerine. Takım başına:
 * sertifika sağlığı (tablonun takım süzgeciyle AYNI kural: YÜKLÜ sertifikalar içinde SY ∪ UG; kapsamlı kullanıcıda liste yalnız
 * birincil takımın (SY) dilimidir, sunucu özetleri ise SY ∪ UG sayar — sayfa bunu not olarak söyler), 30 gün altı, dolmuş + hatalı,
 * açık alarm (TÜM türler, `alerts.read`), son 30 günde açılan alarm, SLA ihlali (izleme / ihlal) ve veri kalitesi puanı
 * (`data_quality.view`). Okunamayan / izinsiz parça "—" (asla 0); 7/24 kapsamı ucuz bir kaynağı olmadığı için yok.
 * Başlıktan sıralama (bilinmeyen her yönde sona). Satır tıklaması bugünkü davranış: tabloyu o takıma süzer. KAP ≥ 860 px
 * tablo, altında kart; takım adları kırpılmaz (TeamBadge `wrap`).
 * Test kancaları: `data-slot="stats-scorecard"` (+ `data-layout`), satır / kart `stats-score-row` + `data-team`,
 * sıralama `stats-score-sort` + `data-col`.
 */
const TABLE_MIN_PX = 860
const INK = { ok: 'text-success', warn: 'text-amber-700 dark:text-amber-300', bad: 'text-destructive', neutral: 'text-muted-foreground' }
const COLS = [
  { key: 'name', label: 'stx.scTeam' },
  { key: 'health', label: 'stx.scHealth' },
  { key: 'under30', label: 'stx.scUnder30' },
  { key: 'problems', label: 'stx.scProblems' },
  { key: 'alerts', label: 'stx.scAlerts' },
  { key: 'sla', label: 'stx.scSla' },
  { key: 'dq', label: 'stx.scDq' },
]

export default function TeamScorecard({ rows, onTeam, selectedTeam, alertsKnown, slaKnown, dqKnown, status = 'ok', onRetry }) {
  const t = useT()
  const isMobile = useIsMobile()
  const [width, setEl] = useElementWidthState()
  const [sort, setSort] = useState({ key: 'health', dir: 'asc' })
  const sorted = useMemo(() => sortScorecard(rows, sort.key, sort.dir), [rows, sort])
  const cards = isMobile || (width > 0 && width < TABLE_MIN_PX)
  // Satır yok: takım listesi yükleniyor / okunamadı / gerçekten boş — üçü ayrı, matristeki gibi (inceleme 2026-11-13;
  // eskiden kart sessizce kayboluyordu)
  if (!rows?.length) {
    let body
    if (status === 'loading') {
      body = (
        <div data-slot="stats-score-loading" aria-busy="true">
          <span role="status" className="sr-only">{t('stv.loading')}</span>
          <Skeleton className="h-28 rounded-lg" />
        </div>
      )
    } else if (status === 'failed') {
      body = (
        <div data-slot="stats-score-failed">
          <StatusBlock tone="danger" title={t('stx.scFailed')} description={t('stx.failedHint')} className="rounded-xl border border-dashed py-8 md:py-8"
            actions={onRetry ? <Button type="button" variant="outline" className="pointer-coarse:h-10 max-sm:h-10" onClick={onRetry}>{t('stx.retry')}</Button> : null} />
        </div>
      )
    } else {
      body = <div data-slot="stats-score-empty"><StatusBlock tone="neutral" title={t('stx.scEmpty')} className="rounded-xl border border-dashed py-8 md:py-8" /></div>
    }
    return (
      <Card data-slot="stats-scorecard" data-layout="empty" className="min-w-0 gap-3 py-4 shadow-xs">
        <CardHeader className="gap-1 px-4 sm:px-6">
          <CardTitle role="heading" aria-level={3} className="flex items-center gap-2 text-base"><Users aria-hidden="true" className="size-4 text-primary" />{t('stx.scTitle')}</CardTitle>
          <CardDescription>{t('stx.scDesc')}</CardDescription>
        </CardHeader>
        <CardContent className="px-4 sm:px-6">{body}</CardContent>
      </Card>
    )
  }
  const onSort = (key) => setSort((s) => (s.key === key ? { key, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: key === 'name' ? 'asc' : (key === 'health' || key === 'dq' ? 'asc' : 'desc') }))
  const nameOf = (r) => (r.teamless ? t('stv.teamless') : r.name || t('stv.teamUnknown'))
  const cell = { t, alertsKnown, slaKnown, dqKnown }
  return (
    <Card data-slot="stats-scorecard" data-layout={cards ? 'cards' : 'table'} className="min-w-0 gap-3 py-4 shadow-xs">
      <CardHeader className="gap-1 px-4 sm:px-6">
        <CardTitle role="heading" aria-level={3} className="flex items-center gap-2 text-base"><Users aria-hidden="true" className="size-4 text-primary" />{t('stx.scTitle')}</CardTitle>
        <CardDescription>{t('stx.scDesc')}</CardDescription>
      </CardHeader>
      <CardContent className="px-2 sm:px-4">
        <div ref={setEl} className="min-w-0">
          {cards ? (
            <div className="flex min-w-0 flex-col gap-2">
              <div role="group" aria-label={t('stx.scSortBy')} className="flex min-w-0 flex-wrap gap-1.5 px-2">
                {COLS.map((c) => <SortButton key={c.key} col={c} sort={sort} onSort={onSort} t={t} chip />)}
              </div>
              <ul className="m-0 flex min-w-0 list-none flex-col gap-2 p-0">
                {sorted.map((r) => (
                  <li key={String(r.id)} className="@container min-w-0">
                    <Button type="button" variant="outline" data-slot="stats-score-row" data-team={r.id} aria-pressed={selectedTeam != null && String(selectedTeam) === String(r.id)}
                      aria-label={t('stx.scFilter', nameOf(r))} onClick={() => onTeam(r)}
                      className="h-auto w-full flex-col items-stretch gap-2 rounded-lg px-3 py-2.5 text-left font-normal whitespace-normal">
                      <span className="min-w-0 font-semibold [overflow-wrap:anywhere]">
                        {r.teamless || !r.name ? nameOf(r) : <TeamBadge teamId={r.id} teamName={r.name} static wrap />}
                      </span>
                      <span className="grid min-w-0 grid-cols-2 gap-x-3 gap-y-1 text-xs @sm:grid-cols-3">
                        {COLS.slice(1).map((c) => (
                          <span key={c.key} className="flex min-w-0 items-baseline justify-between gap-2">
                            <span className="text-muted-foreground">{t(c.label)}</span>
                            <ScoreValue r={r} col={c.key} {...cell} />
                          </span>
                        ))}
                      </span>
                    </Button>
                  </li>
                ))}
              </ul>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg border bg-card">
              <Table className="text-sm">
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    {COLS.map((c) => (
                      <TableHead key={c.key} className={cn('h-10 bg-muted/60 px-2 text-xs whitespace-normal', c.key === 'name' ? 'min-w-48' : 'text-right')}
                        aria-sort={sort.key === c.key ? (sort.dir === 'asc' ? 'ascending' : 'descending') : undefined}>
                        <SortButton col={c} sort={sort} onSort={onSort} t={t} />
                      </TableHead>
                    ))}
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {sorted.map((r) => {
                    const sel = selectedTeam != null && String(selectedTeam) === String(r.id)
                    return (
                      <TableRow key={String(r.id)} data-slot="stats-score-row" data-team={r.id} tabIndex={0} aria-selected={sel}
                        aria-label={t('stx.scFilter', nameOf(r))}
                        className={cn('cursor-pointer focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary', sel && 'bg-primary/5')}
                        onClick={() => onTeam(r)}
                        onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); onTeam(r) } }}>
                        <TableCell className="px-2 py-2 align-top whitespace-normal">
                          <span className="min-w-0 font-semibold [overflow-wrap:anywhere]">
                            {r.teamless || !r.name ? nameOf(r) : <TeamBadge teamId={r.id} teamName={r.name} static wrap />}
                          </span>
                        </TableCell>
                        {COLS.slice(1).map((c) => (
                          <TableCell key={c.key} className="px-2 py-2 text-right align-top"><ScoreValue r={r} col={c.key} {...cell} /></TableCell>
                        ))}
                      </TableRow>
                    )
                  })}
                </TableBody>
              </Table>
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  )
}

function SortButton({ col, sort, onSort, t, chip = false }) {
  const active = sort.key === col.key
  const Icon = !active ? ArrowUpDown : sort.dir === 'asc' ? ArrowUp : ArrowDown
  return (
    <Button type="button" variant={chip && active ? 'secondary' : 'ghost'} size="sm" data-slot="stats-score-sort" data-col={col.key}
      aria-pressed={chip ? active : undefined} title={t('stv.sortCol', t(col.label))}
      onClick={() => onSort(col.key)}
      className={cn('h-auto min-h-8 gap-1 px-1.5 text-xs font-semibold whitespace-normal text-muted-foreground hover:text-foreground pointer-coarse:min-h-10', chip && 'rounded-full border')}>
      {t(col.label)}<Icon aria-hidden="true" className={cn('size-3.5 shrink-0', !active && 'opacity-50')} />
    </Button>
  )
}

/** Bir karne hücresi — bilinmeyen "—" (gri); kaynak okunmadıysa da "—". */
function ScoreValue({ r, col, t, alertsKnown, slaKnown, dqKnown }) {
  const dash = <span className="text-muted-foreground">—</span>
  switch (col) {
    case 'health': {
      if (!r.certs) return dash
      const tone = healthTone(r.certs.health)
      return (
        <span className="inline-flex flex-col items-end">
          <b className={cn('tabular-nums', INK[tone])}>{statsPct(r.certs.health)}</b>
          <span className="text-[11px] text-muted-foreground tabular-nums">{t('stx.scHealthSub', statsNum(r.certs.ok), statsNum(r.certs.evaluated))}</span>
        </span>
      )
    }
    case 'under30':
      return r.certs ? <b className={cn('tabular-nums', r.certs.under30 > 0 && INK.warn)}>{statsNum(r.certs.under30)}</b> : dash
    case 'problems': {
      if (!r.certs) return dash
      const n = r.certs.expired + r.certs.error
      return <b className={cn('tabular-nums', n > 0 && INK.bad)}>{statsNum(n)}</b>
    }
    case 'alerts':
      if (!alertsKnown || !r.alerts) return dash
      return (
        <span className="inline-flex flex-col items-end">
          <b className={cn('tabular-nums', r.alerts.open > 0 && INK.bad)}>{statsNum(r.alerts.open)}</b>
          <span className="text-[11px] text-muted-foreground tabular-nums">{t('stx.scAlerts30', statsNum(r.alerts.last30))}</span>
        </span>
      )
    case 'sla':
      if (!slaKnown || !r.sla) return dash
      return <b className={cn('tabular-nums', r.sla.breaches > 0 && INK.bad)}>{t('stx.scSlaVal', statsNum(r.sla.breaches), statsNum(r.sla.monitors))}</b>
    case 'dq':
      if (!dqKnown || !r.dq || r.dq.score == null) return dash
      return <b className={cn('tabular-nums', r.dq.score >= 90 ? INK.ok : r.dq.score >= 75 ? INK.warn : INK.bad)}>{statsNum(r.dq.score)}</b>
    default:
      return dash
  }
}
