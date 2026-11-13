import { useMemo } from 'react'
import {
  BellOff, CalendarClock, Headset, Mail, MailX, RefreshCw, ShieldX, Siren, Star, UsersRound,
} from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { formatDate } from '../../../api/client'
import MonitorStatsBar from '../../MonitorStatsBar.jsx'
import KebabMenu from '../../ui/KebabMenu.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { useElementWidthState } from '../../../hooks/useElementWidth.js'
import { useIsMobile } from '../../../hooks/use-mobile.js'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { COVERAGE_LEVELS, cellTone } from '../contactsCoverageModel.js'
import { BOARD_CARDS_TWO_COL_PX, BOARD_TABLE_MIN_PX, filterBoard, sortBoard } from './coverageBoardModel.js'

const CELL_CLASS = {
  covered: 'border-success/30 bg-success/10 text-success',
  danger: 'border-destructive/40 bg-destructive/10 text-destructive',
  warning: 'border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-300',
  muted: 'border-border bg-muted/40 text-muted-foreground',
}

/**
 * Takımlar → KAPSAM PANOSU (2026-11-13, yönetim zenginleştirmesi (h)). Her takım için "alarm kime ulaşır" tek satırda:
 * müdür → lider hiyerarşisi, takım e-postası + varsayılan bildirim grubu (ulaşım), eskalasyon kişileri seviye başına
 * (Uyarı / Yüksek / Kritik), şu anki nöbetçi, 7/24 kapsamı. Boşluklar vurgulanır; satırın "Düzelt" menüsü ilgili
 * düzeltmeyi açar (takım formu, varsayılan grup seçimi, kişi ekleme, nöbet çizelgesi, ilgili sekme). Okunamayan boyut
 * "—" / "bilinmiyor"dur — asla "eksik" ya da yeşil değil. Kap ≥ {@link BOARD_TABLE_MIN_PX} → tablo, aksi kartlar; adlar
 * SARAR. Test kancaları: `data-slot="tcb-board|tcb-row|tcb-delivery|tcb-esc|tcb-oncall|tcb-noc|tcb-fix|tcb-parts"`.
 */
export default function TeamCoverageBoard({
  rows, summary, parts = {}, onRetry, filter = 'all', onFilter, selected, onToggle, canSelect = () => false,
  fixActionsFor = () => [],
}) {
  const t = useT()
  const phone = useIsMobile()
  const [width, setBoxEl] = useElementWidthState()
  const cards = width > 0 ? width < BOARD_TABLE_MIN_PX : phone
  const twoCol = width >= BOARD_CARDS_TWO_COL_PX

  const shown = useMemo(() => sortBoard(filterBoard(rows, filter)), [rows, filter])
  const failedParts = Object.entries(parts).filter(([, s]) => s === 'failed').map(([k]) => k)
  const loadingParts = Object.entries(parts).filter(([, s]) => s === 'loading').map(([k]) => k)
  const kpiValue = (n, known, part) => (parts[part] === 'loading' ? '…' : known ? n : '—')
  const kpis = [
    { key: 'all', Icon: UsersRound, cls: 'total', label: t('tcb.kpi.all'), value: summary.teams },
    { key: 'noRecipient', Icon: MailX, cls: 'critical', label: t('tcb.kpi.noRecipient'), value: kpiValue(summary.noRecipient, summary.deliveryKnown, 'groups'), hint: t('tcb.kpi.noRecipientHint') },
    { key: 'critGap', Icon: ShieldX, cls: 'critical', label: t('tcb.kpi.critGap'), value: kpiValue(summary.critGap, summary.escalationKnown, 'contacts'), hint: t('tcb.kpi.critGapHint') },
    { key: 'noDefault', Icon: BellOff, cls: 'warning', label: t('tcb.kpi.noDefault'), value: kpiValue(summary.noDefault, summary.groupKnown, 'groups'), hint: t('tcb.kpi.noDefaultHint') },
    { key: 'noOncall', Icon: CalendarClock, cls: 'paused', label: t('tcb.kpi.noOncall'), value: kpiValue(summary.noOncall, summary.oncallKnown, 'oncall'), hint: t('tcb.kpi.noOncallHint') },
  ]

  const selectCell = (r) => (canSelect(r.teamId) ? (
    <label className="-m-2 flex size-10 shrink-0 cursor-pointer items-center justify-center">
      <Checkbox checked={selected?.has(r.teamId) ?? false} onCheckedChange={() => onToggle?.(r.teamId)}
        aria-label={t('team.selectOne', r.teamName)} data-slot="tcb-select" />
    </label>
  ) : null)

  return (
    <section data-slot="tcb-board" className="flex min-w-0 flex-col gap-3" aria-label={t('tcb.title')}>
      <div className="min-w-0">
        <h4 className="text-sm font-semibold">{t('tcb.title')}</h4>
        <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('tcb.desc')}</p>
      </div>
      <MonitorStatsBar dense items={kpis} activeFilter={filter === 'all' ? null : filter}
        onStatClick={(k) => onFilter?.(k === 'all' || k === filter ? 'all' : k)} className="mb-0" />
      {(failedParts.length > 0 || loadingParts.length > 0) && (
        <AlertBanner tone={failedParts.length > 0 ? 'warning' : 'info'} className="mb-0">
          <span data-slot="tcb-parts" className="flex flex-wrap items-center gap-x-2 gap-y-1">
            {failedParts.length > 0 && <span>{t('tcb.partsFailed', failedParts.map((p) => t(`tcb.part.${p}`)).join(', '))}</span>}
            {loadingParts.length > 0 && <span className="text-muted-foreground">{t('tcb.partsLoading', loadingParts.map((p) => t(`tcb.part.${p}`)).join(', '))}</span>}
            {failedParts.length > 0 && onRetry && (
              <Button type="button" variant="outline" size="sm" className="max-sm:h-10 pointer-coarse:h-10" onClick={onRetry}>
                <RefreshCw aria-hidden="true" /> {t('tcb.retry')}
              </Button>
            )}
          </span>
        </AlertBanner>
      )}

      <div ref={setBoxEl} data-slot="tcb-list" data-view={cards ? 'cards' : 'table'} className="min-w-0">
        {shown.length === 0 ? (
          <p data-slot="tcb-empty" className="rounded-lg border bg-card p-6 text-center text-sm text-muted-foreground">
            {filter === 'all' ? t('team.noResults') : t('tcb.emptyFilter')}
          </p>
        ) : cards ? (
          <ul aria-label={t('tcb.title')} className={cn('grid gap-3', twoCol && 'grid-cols-2')}>
            {shown.map((r) => (
              <li key={r.teamId} data-slot="tcb-row" data-team-id={r.teamId} data-state={selected?.has(r.teamId) ? 'selected' : undefined}
                className="flex min-w-0 flex-col gap-3 rounded-lg border bg-card p-3 shadow-xs data-[state=selected]:border-primary">
                <div className="flex items-start gap-2">
                  {selectCell(r)}
                  <div className="min-w-0 flex-1"><TeamCell r={r} t={t} /></div>
                </div>
                <dl className="grid min-w-0 grid-cols-1 gap-2.5 text-[13px]">
                  <Block label={t('tcb.col.hierarchy')}><Hierarchy r={r} t={t} /></Block>
                  <Block label={t('tcb.col.delivery')}><Delivery r={r} t={t} /></Block>
                  <Block label={t('tcb.col.escalation')}><Escalation r={r} t={t} /></Block>
                  <div className="grid grid-cols-2 gap-2.5">
                    <Block label={t('tcb.col.oncall')}><Oncall r={r} t={t} /></Block>
                    <Block label={t('tcb.col.noc')}><Noc r={r} t={t} /></Block>
                  </div>
                </dl>
                <div className="flex justify-end"><FixMenu r={r} t={t} items={fixActionsFor(r)} /></div>
              </li>
            ))}
          </ul>
        ) : (
          <div className="overflow-hidden rounded-lg border bg-card">
            <Table className="table-fixed text-[13px]">
              <TableHeader className="bg-muted/50">
                <TableRow className="hover:bg-transparent [&>th]:h-auto [&>th]:py-2 [&>th]:align-bottom [&>th]:text-xs [&>th]:whitespace-normal">
                  <TableHead className="w-10 px-2"><span className="sr-only">{t('team.selectAll')}</span></TableHead>
                  <TableHead className="w-[18%]">{t('tcb.col.team')}</TableHead>
                  <TableHead className="w-[19%]">{t('tcb.col.hierarchy')}</TableHead>
                  <TableHead className="w-[22%]">{t('tcb.col.delivery')}</TableHead>
                  <TableHead className="w-[15%]">{t('tcb.col.escalation')}</TableHead>
                  <TableHead className="w-[12%]">{t('tcb.col.oncall')}</TableHead>
                  <TableHead className="w-[9%]">{t('tcb.col.noc')}</TableHead>
                  <TableHead className="w-28"><span className="sr-only">{t('tcb.fix')}</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {shown.map((r) => (
                  <TableRow key={r.teamId} data-slot="tcb-row" data-team-id={r.teamId} data-state={selected?.has(r.teamId) ? 'selected' : undefined}
                    className="align-top [&>td]:whitespace-normal">
                    <TableCell className="px-2">{selectCell(r)}</TableCell>
                    <TableCell><TeamCell r={r} t={t} /></TableCell>
                    <TableCell><Hierarchy r={r} t={t} /></TableCell>
                    <TableCell><Delivery r={r} t={t} /></TableCell>
                    <TableCell><Escalation r={r} t={t} /></TableCell>
                    <TableCell><Oncall r={r} t={t} /></TableCell>
                    <TableCell><Noc r={r} t={t} /></TableCell>
                    <TableCell className="text-right"><FixMenu r={r} t={t} items={fixActionsFor(r)} /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </div>
    </section>
  )
}

function Block({ label, children }) {
  return (
    <div className="min-w-0">
      <dt className="mb-0.5 text-xs text-muted-foreground">{label}</dt>
      <dd className="min-w-0">{children}</dd>
    </div>
  )
}

function TeamCell({ r, t }) {
  return (
    <span className="flex min-w-0 flex-col items-start gap-1">
      <strong className="min-w-0 text-[0.95em]"><TeamBadge teamId={r.teamId} teamName={r.teamName} size={13} wrap /></strong>
      {!r.active && <ToneBadge tone="danger">{t('team.inactive')}</ToneBadge>}
    </span>
  )
}

/** Kişi satırı: ad (sarar) ya da durum metni — atanmamış (eksik), görünmüyor / okunamadı (bilinmiyor). */
function Person({ who, t }) {
  if (who?.state === 'ok') {
    return (
      <span className="[overflow-wrap:anywhere]">
        {who.label}{who.manual && <span className="ml-1 text-xs text-muted-foreground">{t('team.managerManual')}</span>}
      </span>
    )
  }
  if (who?.state === 'missing') return <span data-missing="true" className="font-medium text-destructive">{t('tcb.person.missing')}</span>
  return <span className="text-muted-foreground">{who?.state === 'hidden' ? t('tcb.person.hidden') : t('tcb.person.unknown')}</span>
}

function Hierarchy({ r, t }) {
  return (
    <dl data-slot="tcb-hierarchy" className="grid min-w-0 grid-cols-1 gap-y-0.5 [&>dd]:mb-1 [&>dd:last-child]:mb-0">
      <dt className="text-xs text-muted-foreground">{t('team.colManager')}</dt>
      <dd className="min-w-0"><Person who={r.manager} t={t} /></dd>
      <dt className="text-xs text-muted-foreground">↳ {t('team.leaderShort')}</dt>
      <dd className="min-w-0"><Person who={r.leader} t={t} /></dd>
    </dl>
  )
}

function Delivery({ r, t }) {
  const dg = r.defaultGroup
  const deliveryTone = r.delivery === 'ok' ? 'success' : r.delivery === 'none' ? 'danger' : 'muted'
  return (
    <span data-slot="tcb-delivery" data-delivery={r.delivery} data-group={dg.state} className="flex min-w-0 flex-col items-start gap-1">
      <ToneBadge tone={deliveryTone} className="h-auto rounded-md overflow-visible whitespace-normal text-left">
        {t(`tcb.delivery.${r.delivery}`)}
      </ToneBadge>
      <span className="flex min-w-0 items-start gap-1 text-xs">
        <Mail aria-hidden="true" className="mt-0.5 size-3 shrink-0 text-muted-foreground" />
        {r.email
          ? <span className="[overflow-wrap:anywhere]">{r.email}</span>
          : <span className="font-medium text-destructive">{t('team.gap.email.label')}</span>}
      </span>
      <span className="flex min-w-0 items-start gap-1 text-xs">
        <Star aria-hidden="true" className="mt-0.5 size-3 shrink-0 text-muted-foreground" />
        <span className={cn('[overflow-wrap:anywhere]', (dg.state === 'allPassive' || dg.state === 'noEmails') && 'font-medium text-destructive',
          dg.state === 'unknown' && 'text-muted-foreground')}>
          {dg.state === 'ok' ? ((dg.delivered ?? dg.count) === 1 ? t('tcb.group.ok.one', dg.name) : t('tcb.group.ok', dg.name, dg.delivered ?? dg.count)) : t(`tcb.group.${dg.state}`, dg.name || '')}
        </span>
      </span>
    </span>
  )
}

function Escalation({ r, t }) {
  const e = r.escalation
  if (!e) return <span data-slot="tcb-esc" data-state="unknown" className="text-muted-foreground">{t('tcb.unknown')}</span>
  return (
    <span data-slot="tcb-esc" data-state={e.tone} className="flex min-w-0 flex-col gap-1">
      <span className="flex flex-wrap gap-1">
        {COVERAGE_LEVELS.map((lvl) => {
          const n = e.counts[lvl]
          const tone = e.tone === 'unknown' ? 'muted' : cellTone(n, lvl, e.assets ?? 0)
          return (
            <span key={lvl} data-level={lvl} data-tone={tone} title={t(`ec.level.${lvl.toLowerCase()}`)}
              className={cn('inline-flex min-w-11 flex-col items-center rounded-md border px-1.5 py-0.5 leading-tight', CELL_CLASS[tone])}>
              <span className="text-[10px] font-semibold tracking-wide uppercase">{t(`tcb.lvl.${lvl}`)}</span>
              <span className="text-sm font-bold tabular-nums">{n}</span>
            </span>
          )
        })}
      </span>
      {e.tone === 'gap' && <span className="text-xs font-medium text-destructive">{t('tcb.esc.gap')}</span>}
      {e.tone === 'partial' && <span className="text-xs text-amber-700 dark:text-amber-300">{t('tcb.esc.partial')}</span>}
      {e.tone === 'noAssets' && <span className="text-xs text-muted-foreground">{t('tcb.esc.noAssets')}</span>}
    </span>
  )
}

function Oncall({ r, t }) {
  const o = r.oncall
  if (o.state === 'on') {
    return (
      <span data-slot="tcb-oncall" data-state="on" className="flex min-w-0 flex-col gap-0.5">
        <span className="flex min-w-0 items-start gap-1">
          <Siren aria-hidden="true" className="mt-0.5 size-3 shrink-0 text-muted-foreground" />
          <span className="[overflow-wrap:anywhere]">{o.name || t('tcb.person.unknown')}</span>
        </span>
        {o.until && <span className="text-xs text-muted-foreground">{t('tcb.oncall.until', formatDate(o.until))}</span>}
        {o.inactive && <ToneBadge tone="danger">{t('team.memberInactive')}</ToneBadge>}
      </span>
    )
  }
  if (o.state === 'none') return <span data-slot="tcb-oncall" data-state="none" className="text-muted-foreground">{t('tcb.oncall.none')}</span>
  return <span data-slot="tcb-oncall" data-state="unknown" className="text-muted-foreground">{t('tcb.unknown')}</span>
}

function Noc({ r, t }) {
  const n = r.noc
  if (n.state === 'unknown') return <span data-slot="tcb-noc" data-state="unknown" className="text-muted-foreground">{t('tcb.unknown')}</span>
  if (n.state === 'noMonitors') return <span data-slot="tcb-noc" data-state="noMonitors" className="text-muted-foreground">{t('tcb.noc.noMonitors')}</span>
  const tone = n.state === 'all' ? 'success' : n.state === 'partial' ? 'info' : 'muted'
  return (
    <span data-slot="tcb-noc" data-state={n.state} className="flex min-w-0 flex-col items-start gap-0.5">
      <ToneBadge tone={tone} className="gap-1 tabular-nums"><Headset aria-hidden="true" />{n.covered}/{n.active}</ToneBadge>
      <span className="text-xs text-muted-foreground">{t(`tcb.noc.${n.state}`)}</span>
    </span>
  )
}

function FixMenu({ r, t, items }) {
  if (!items || items.length === 0) return null
  return (
    <span data-slot="tcb-fix">
      <KebabMenu variant="labeled" label={t('tcb.fix')} rowLabel={r.teamName} items={items} />
    </span>
  )
}

