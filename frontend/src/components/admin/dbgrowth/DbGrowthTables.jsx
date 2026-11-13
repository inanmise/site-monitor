import { useMemo, useRef } from 'react'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import { usePagination } from '../../../hooks/usePagination.js'
import PaginationBar from '../../ui/PaginationBar.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import {
  formatBytes, formatNumber, formatPct, formatSignedBytes, layoutFor, retentionText, severityTone,
} from './dbGrowthModel.js'

/**
 * Tablo listesi (2026-11-13): boyut, 30 günlük büyüme, satır / gün, saklama (gün + kip), doygunluk, denge boyutu ve öneri
 * rozetleri. Kap genişliği ≥ 860 px → tablo, altında kart; adlar sarar, kırpılmaz. Bilinmeyen değer "—" (0 değil).
 */
const CODE_SEVERITY = {
  SHORTEN_CANDIDATE: 'warn', NOT_SATURATED: 'info', NO_RULE: 'warn', BOUNDED_GROWING: 'warn', CLEANUP_BEHIND: 'warn',
  DEAD_TUPLES: 'info', TOAST_HEAVY: 'info',
}

function FindingChips({ codes, t }) {
  if (!Array.isArray(codes) || !codes.length) return null
  return (
    <span className="flex flex-wrap gap-1">
      {codes.map((c) => (
        <ToneBadge key={c} tone={severityTone(CODE_SEVERITY[c] || 'info')} data-slot="dbg-table-finding" data-code={c}
          className="h-auto rounded-md whitespace-normal">{t(`dbg.adv.${c}.title`)}</ToneBadge>
      ))}
    </span>
  )
}

function rowsPerDay(r, t) {
  if (r.rows_per_day == null) return '—'
  const v = formatNumber(r.rows_per_day, r.rows_per_day < 10 ? 1 : 0)
  return r.rows_per_day_method === 'histogram' || r.rows_per_day_method === 'uniform' ? `${v} ${t('dbg.estMark')}` : v
}

function saturation(r, t) {
  if (r.saturation_pct == null) return '—'
  return r.saturated ? t('dbg.tbl.saturated') : formatPct(r.saturation_pct)
}

function growth(r, t) {
  if (r.growth_30d_bytes != null) return formatSignedBytes(r.growth_30d_bytes)
  if (r.daily_growth_bytes != null) return `${formatSignedBytes(r.daily_growth_bytes)}${t('dbg.perDaySuffix')} ${t('dbg.estMark')}`
  return '—'
}

export default function DbGrowthTables({ tables, t }) {
  const [ref, width] = useElementWidth()
  const topRef = useRef(null)
  const list = useMemo(() => (Array.isArray(tables) ? tables : []), [tables])
  const pager = usePagination(list, { listKey: 'dbg-tables', preset: 'panel' })
  const layout = layoutFor(width)
  const pageRows = pager.pageItems

  return (
    <div ref={ref} data-slot="dbg-tables" data-layout={layout} className="flex min-w-0 flex-col gap-2">
      <span ref={topRef} />
      {!list.length ? (
        <p data-slot="dbg-tables-empty" className="m-0 rounded-md bg-muted/40 px-3 py-2.5 text-sm text-muted-foreground">{t('dbg.tbl.none')}</p>
      ) : layout === 'table' ? (
        <div className="min-w-0 rounded-lg border">
          <Table className="table-fixed">
            <TableHeader>
              <TableRow>
                <TableHead className="w-[26%] whitespace-normal">{t('dbg.tbl.name')}</TableHead>
                <TableHead className="w-[10%] whitespace-normal text-right">{t('dbg.tbl.size')}</TableHead>
                <TableHead className="w-[11%] whitespace-normal text-right">{t('dbg.tbl.growth30')}</TableHead>
                <TableHead className="w-[10%] whitespace-normal text-right">{t('dbg.tbl.rowsPerDay')}</TableHead>
                <TableHead className="w-[14%] whitespace-normal">{t('dbg.tbl.retention')}</TableHead>
                <TableHead className="w-[9%] whitespace-normal text-right">{t('dbg.tbl.saturation')}</TableHead>
                <TableHead className="w-[10%] whitespace-normal text-right">{t('dbg.tbl.equilibrium')}</TableHead>
                <TableHead className="w-[10%] whitespace-normal text-right">{t('dbg.tbl.projection90')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {pageRows.map((r) => (
                <TableRow key={r.name} data-slot="dbg-table-row" data-table={r.name}>
                  <TableCell className="align-top whitespace-normal">
                    <div className="flex min-w-0 flex-col gap-1">
                      <code className="font-mono text-xs font-semibold [overflow-wrap:anywhere]">{r.name}</code>
                      <FindingChips codes={r.findings} t={t} />
                    </div>
                  </TableCell>
                  <TableCell className="text-right align-top whitespace-normal tabular-nums">
                    <div className="font-semibold">{formatBytes(r.total_bytes)}</div>
                    {r.index_bytes != null && <div className="text-[11px] text-muted-foreground">{t('dbg.tbl.indexShort', formatBytes(r.index_bytes))}</div>}
                  </TableCell>
                  <TableCell className="text-right align-top whitespace-normal tabular-nums">{growth(r, t)}</TableCell>
                  <TableCell className="text-right align-top whitespace-normal tabular-nums">{rowsPerDay(r, t)}</TableCell>
                  <TableCell className="align-top whitespace-normal">{retentionText(r.retention, t)}</TableCell>
                  <TableCell className="text-right align-top whitespace-normal tabular-nums">{saturation(r, t)}</TableCell>
                  <TableCell className="text-right align-top whitespace-normal tabular-nums">{formatBytes(r.equilibrium_bytes)}</TableCell>
                  <TableCell className="text-right align-top whitespace-normal tabular-nums">{formatBytes(r.projection_90d_bytes)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      ) : (
        <ul className="m-0 grid list-none grid-cols-1 gap-2 p-0 sm:grid-cols-2">
          {pageRows.map((r) => (
            <li key={r.name} data-slot="dbg-table-card" data-table={r.name}
              className="flex min-w-0 flex-col gap-2 rounded-lg border bg-card px-3 py-2.5 shadow-xs">
              <div className="flex min-w-0 flex-col gap-1">
                <code className="font-mono text-xs font-semibold [overflow-wrap:anywhere]">{r.name}</code>
                <FindingChips codes={r.findings} t={t} />
              </div>
              <dl className="m-0 grid grid-cols-2 gap-x-3 gap-y-1.5 text-xs">
                {[
                  ['size', formatBytes(r.total_bytes)],
                  ['growth30', growth(r, t)],
                  ['rowsPerDay', rowsPerDay(r, t)],
                  ['projection90', formatBytes(r.projection_90d_bytes)],
                  ['saturation', saturation(r, t)],
                  ['equilibrium', formatBytes(r.equilibrium_bytes)],
                  ['retention', retentionText(r.retention, t)],
                ].map(([k, v]) => (
                  <div key={k} className={cn('flex min-w-0 flex-col', k === 'retention' && 'col-span-2')}>
                    <dt className="text-[11px] text-muted-foreground">{t(`dbg.tbl.${k}`)}</dt>
                    <dd className="m-0 font-semibold tabular-nums [overflow-wrap:anywhere]">{v}</dd>
                  </div>
                ))}
              </dl>
            </li>
          ))}
        </ul>
      )}
      {list.length > 25 && <PaginationBar {...pager} scrollTargetRef={topRef} />}
    </div>
  )
}
