import { useMemo } from 'react'
import { Filter, Info } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { LEVELS, columnMax, heatStep } from './statsModel.js'
import { HEAT, LEVEL_DOT, LEVEL_TONE, TierBadge } from './statsUi.jsx'

/**
 * Takım × katman matrisi (2026-09-28 yeniden tasarım). Satırlar takım (dikkat isteyen önce); her takımın özet satırı
 * TÜM katmanları sayar, altındaki katman satırları seçili kapsamdadır (varsayılan üretim T1–T2 — 2026-05 ürün kararı;
 * "Tüm katmanlar" ile T3/T4/sınıflandırılmamış da açılır). Sütunlar sunucunun tier eşiklerine göre seviye.
 *
 * Her dolu hücre bir düğmedir (erişilebilir adı takım · katman · seviye · sayı taşır): tıklayınca tablo o takım +
 * katman + seviyeye süzülür, aynı hücreye tekrar basınca süzgeç kalkar. Hücre tonu ısı haritası gibi: o sütundaki en
 * büyük değere oranla üç adım (jeton/palet zemini, kenarlık yok). Sıfır hücre sönük "0", tıklanmaz.
 *
 * Geniş kapta shadcn Table (ilk sütun yapışkan, dar ekranda tablo KENDİ kabında kayar — sayfa taşmaz); dar kapta
 * (telefon) takım başına kart + katman başına seviye çipleri. Test kancaları: `data-slot="stats-matrix"` (tablo),
 * `stats-matrix-card` (kart), `stats-matrix-cell` (+ `data-level`, `aria-pressed`), `stats-matrix-team-filter`.
 */
export default function TeamTierMatrix({ rows, allTiers, onAllTiers, sel, onCell, onTeam, onTier, narrow }) {
  const t = useT()
  const scope = (
    <div className="flex min-w-0 flex-wrap items-center gap-2">
      <SegmentedControl value={allTiers ? 'all' : 'prod'} onChange={(v) => onAllTiers(v === 'all')} ariaLabel={t('stv.mxScope')}
        className="[&>button]:max-sm:h-10 [&>button]:pointer-coarse:h-10"
        options={[{ value: 'prod', label: t('stv.mxScopeProd') }, { value: 'all', label: t('stv.mxScopeAll') }]} />
      <HintPopover content={t('stv.mxInfo')} aria-label={t('stv.mxInfoLabel')} triggerClassName="size-8 justify-center max-sm:size-10 pointer-coarse:size-10">
        <Info aria-hidden="true" className="size-4 text-muted-foreground" />
      </HintPopover>
    </div>
  )
  if (!rows.length) {
    return (
      <div className="flex min-w-0 flex-col gap-3">
        {scope}
        <StatusBlock tone="neutral" title={t('stv.mxEmpty')} className="rounded-xl border border-dashed py-8 md:py-8" />
      </div>
    )
  }
  const isSel = (r, tier, level) => sel.teamId != null && String(sel.teamId) === String(r.id) && sel.tier === tier && sel.level === level
  const h = { t, isSel, onCell, onTeam, onTier }
  return (
    <div className="flex min-w-0 flex-col gap-3">
      {scope}
      {narrow ? <MatrixCards rows={rows} h={h} /> : <MatrixTable rows={rows} h={h} />}
    </div>
  )
}

function tierLabel(tier, t) {
  return tier === 0 ? t('tier.descNone') : `T${tier} — ${t(`tier.desc${tier}`)}`
}

function TierTag({ tier, t }) {
  return (
    <span className="inline-flex min-w-0 items-center gap-2">
      {tier === 0
        ? <Badge variant="outline" data-slot="stats-tier" className="rounded px-1.5 text-[11px] font-extrabold">?</Badge>
        : <TierBadge tier={tier} />}
      <span className="min-w-0 truncate text-xs text-muted-foreground">{tier === 0 ? t('tier.descNone') : t(`tier.desc${tier}`)}</span>
    </span>
  )
}

/** Takım süzgeci düğmesi (yalnız ikon; adı takımı taşır). */
function TeamFilterButton({ r, h, className }) {
  const pressed = h.isSel(r, null, null)
  const label = h.t('stv.mxFilterTeam', r.name)
  return (
    <Button type="button" variant={pressed ? 'secondary' : 'ghost'} size="icon-sm" data-slot="stats-matrix-team-filter"
      aria-pressed={pressed} aria-label={label} title={label} onClick={() => h.onTeam(r)}
      className={cn('shrink-0 pointer-coarse:size-10', pressed && 'text-primary ring-2 ring-primary/40', className)}>
      <Filter aria-hidden="true" />
    </Button>
  )
}

function HeatCell({ r, tier, level, count, max, h }) {
  if (!count) {
    return (
      <TableCell className="px-1 py-1 text-center">
        <span data-slot="stats-matrix-zero" className="text-muted-foreground/50 tabular-nums">0</span>
      </TableCell>
    )
  }
  const selected = h.isSel(r, tier, level)
  const label = h.t('stv.mxCell', r.name, tier == null ? h.t('stv.mxAllTiers') : tierLabel(tier, h.t), h.t(`ts.${level}`), count)
  return (
    <TableCell className="px-1 py-1 text-center">
      <Button type="button" variant="ghost" data-slot="stats-matrix-cell" data-level={level} aria-pressed={selected}
        aria-label={label} title={label} onClick={() => h.onCell(r, tier, level)}
        className={cn('h-8 w-full min-w-11 p-0 hover:ring-2 hover:ring-ring/40 pointer-coarse:h-10', selected && 'ring-2 ring-primary')}>
        <span className={cn('flex size-full items-center justify-center rounded-md font-bold tabular-nums', HEAT[level][heatStep(count, max[level])])}>{count}</span>
      </Button>
    </TableCell>
  )
}

function MatrixTable({ rows, h }) {
  const { t } = h
  const teamMax = useMemo(() => columnMax(rows), [rows])
  const tierMax = useMemo(() => columnMax(rows.flatMap((r) => r.tiers)), [rows])
  const stickyHead = 'sticky left-0 z-[2] bg-muted'
  return (
    <div className="overflow-hidden rounded-lg border bg-card">
      <Table data-slot="stats-matrix" className="text-sm">
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            <TableHead className={cn('h-10 min-w-48 text-muted-foreground', stickyHead)}>{t('stv.mxColTeam')}</TableHead>
            {LEVELS.map((l) => (
              <TableHead key={l} className="h-10 min-w-16 bg-muted text-center text-xs font-semibold text-muted-foreground">
                <span className="inline-flex items-center gap-1.5">
                  <span aria-hidden="true" className={cn('size-2 rounded-full', LEVEL_DOT[l])} />{t(`ts.${l}`)}
                </span>
              </TableHead>
            ))}
            <TableHead className="h-10 min-w-14 bg-muted text-center text-xs font-semibold text-muted-foreground">{t('ts.total')}</TableHead>
          </TableRow>
        </TableHeader>
        {rows.map((r) => (
          <TableBody key={r.id} data-slot="stats-matrix-team" data-team={r.id} className="border-b-2 last:border-b-0">
            <TableRow className="bg-muted hover:bg-muted">
              <TableCell className="sticky left-0 z-[1] bg-muted py-1.5 pr-2">
                <div className="flex min-w-0 items-center justify-between gap-2">
                  <span className="min-w-0 font-semibold"><TeamBadge teamId={r.id > 0 ? r.id : undefined} teamName={r.name} /></span>
                  <TeamFilterButton r={r} h={h} />
                </div>
              </TableCell>
              {LEVELS.map((l) => <HeatCell key={l} r={r} tier={null} level={l} count={r.counts[l]} max={teamMax} h={h} />)}
              <TableCell className="text-center font-bold tabular-nums">{r.total}</TableCell>
            </TableRow>
            {r.tiers.map((tr) => {
              const pressed = h.isSel(r, tr.tier, null)
              const label = t('stv.mxTierCell', r.name, tierLabel(tr.tier, t))
              return (
                <TableRow key={tr.tier} className="hover:bg-transparent">
                  <TableCell className="sticky left-0 z-[1] bg-card py-1 pr-2 pl-4">
                    <Button type="button" variant="ghost" size="sm" aria-pressed={pressed} aria-label={label} title={label}
                      onClick={() => h.onTier(r, tr.tier)} data-slot="stats-matrix-tier"
                      className={cn('h-8 max-w-full justify-start px-2 font-normal pointer-coarse:h-10', pressed && 'ring-2 ring-primary')}>
                      <TierTag tier={tr.tier} t={t} />
                    </Button>
                  </TableCell>
                  {LEVELS.map((l) => <HeatCell key={l} r={r} tier={tr.tier} level={l} count={tr.counts[l]} max={tierMax} h={h} />)}
                  <TableCell className="text-center text-muted-foreground tabular-nums">{tr.total}</TableCell>
                </TableRow>
              )
            })}
            {r.tiers.length === 0 && (
              <TableRow className="hover:bg-transparent">
                <TableCell className="sticky left-0 z-[1] bg-card py-2 pl-6 text-xs text-muted-foreground">{t('stv.mxNoTier')}</TableCell>
                <TableCell colSpan={LEVELS.length + 1} />
              </TableRow>
            )}
          </TableBody>
        ))}
      </Table>
    </div>
  )
}

/** Telefon: seviye çipleri (yalnız dolu seviyeler). */
function LevelChips({ r, tier, counts, h }) {
  const levels = LEVELS.filter((l) => counts[l] > 0)
  if (!levels.length) return <span className="text-xs text-muted-foreground">{h.t('stv.mxNoTier')}</span>
  return (
    <div className="flex min-w-0 flex-wrap gap-1.5">
      {levels.map((l) => {
        const selected = h.isSel(r, tier, l)
        const label = h.t('stv.mxCell', r.name, tier == null ? h.t('stv.mxAllTiers') : tierLabel(tier, h.t), h.t(`ts.${l}`), counts[l])
        return (
          <Button key={l} type="button" variant="ghost" data-slot="stats-matrix-cell" data-level={l} aria-pressed={selected}
            aria-label={label} title={label} onClick={() => h.onCell(r, tier, l)}
            className={cn('h-auto min-h-10 rounded-full p-0', selected && 'ring-2 ring-primary')}>
            <span className={cn('inline-flex min-h-10 items-center gap-1.5 rounded-full px-3 text-xs font-semibold', LEVEL_TONE[l])}>
              {h.t(`ts.${l}`)}<b className="tabular-nums">{counts[l]}</b>
            </span>
          </Button>
        )
      })}
    </div>
  )
}

function MatrixCards({ rows, h }) {
  const { t } = h
  return (
    <div className="flex min-w-0 flex-col gap-3">
      {rows.map((r) => (
        <Card key={r.id} data-slot="stats-matrix-card" data-team={r.id} className="min-w-0 gap-3 py-3.5 shadow-none">
          <CardHeader className="gap-1 px-4">
            <CardTitle role="heading" aria-level={4} className="min-w-0 text-sm"><TeamBadge teamId={r.id > 0 ? r.id : undefined} teamName={r.name} /></CardTitle>
            <CardDescription className="tabular-nums">{t('stv.mxCardTotal', r.total)}</CardDescription>
            <CardAction><TeamFilterButton r={r} h={h} className="size-10" /></CardAction>
          </CardHeader>
          <CardContent className="flex min-w-0 flex-col gap-3 px-4">
            <div className="flex min-w-0 flex-col gap-1.5">
              <span className="text-[11px] font-bold tracking-[.06em] text-muted-foreground uppercase">{t('stv.mxAllTiers')}</span>
              <LevelChips r={r} tier={null} counts={r.counts} h={h} />
            </div>
            {r.tiers.map((tr) => {
              const pressed = h.isSel(r, tr.tier, null)
              const label = t('stv.mxTierCell', r.name, tierLabel(tr.tier, t))
              return (
                <div key={tr.tier} className="flex min-w-0 flex-col gap-1.5 border-t pt-3">
                  <Button type="button" variant="ghost" aria-pressed={pressed} aria-label={label} title={label}
                    onClick={() => h.onTier(r, tr.tier)} data-slot="stats-matrix-tier"
                    className={cn('h-10 max-w-full justify-start self-start px-1.5 font-normal', pressed && 'ring-2 ring-primary')}>
                    <TierTag tier={tr.tier} t={t} /><span className="text-xs text-muted-foreground tabular-nums">· {tr.total}</span>
                  </Button>
                  <LevelChips r={r} tier={tr.tier} counts={tr.counts} h={h} />
                </div>
              )
            })}
          </CardContent>
        </Card>
      ))}
    </div>
  )
}
