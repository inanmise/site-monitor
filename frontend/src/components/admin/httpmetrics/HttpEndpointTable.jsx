import { useId, useRef } from 'react'
import { ArrowDown, ArrowUp, ChevronRight, Download, Search } from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import PaginationBar from '../../ui/PaginationBar.jsx'
import { usePagination } from '../../../hooks/usePagination.js'
import { formatDate } from '../../../api/client'
import { shortDateTime } from '../../responsechart/responseChartModel.js'
import { cn } from '@/lib/utils'
import { EndpointLabel, Panel, ToneValue } from './HttpParts.jsx'
import { SORT_KEYS, defaultDir, errTone, fmtInt, fmtPct, msTone } from './httpMetricsModel.js'

/**
 * İstek Gezgini uç listesi: yöntem rozeti + yol şablonu (eş aralıklı, kırpılmadan sarılır), istek, hata %, ort. /
 * p95 / maks. süre, son görülme. Geniş ekranda (≥ 1024 px, `wide`) shadcn Table — başlıklar sıralanabilir
 * (`aria-sort`); daha dar ekranda (768 tablet + telefon) kart listesi + "Sırala" seçimi. Yol araması, sayfalama
 * (`usePagination` + `PaginationBar`, pencere ön ayarı) ve görünen (süzülmüş + sıralı, TÜM sayfalar) listenin CSV'si.
 * Satır/kart → ayrıntı paneli; seçili satır `data-state="selected"`. İki yerleşim aynı anda DOM'a girmez.
 */
const COLS = [
  { key: 'endpoint', label: 'hreq.col.endpoint' },
  { key: 'count', label: 'hreq.col.requests', num: true },
  { key: 'errorRate', label: 'hreq.col.errorRate', num: true },
  { key: 'avg', label: 'hreq.col.avg', num: true },
  { key: 'p95', label: 'hreq.col.p95', num: true },
  { key: 'max', label: 'hreq.col.max', num: true, cls: 'hidden xl:table-cell' },
  { key: 'lastSeen', label: 'hreq.col.lastSeen', num: true },
]

function SortHeader({ col, sort, onSort, t }) {
  const active = sort.key === col.key
  const Arrow = sort.dir === 'asc' ? ArrowUp : ArrowDown
  return (
    <TableHead aria-sort={active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'}
      className={cn('h-10 px-3 text-xs font-semibold text-muted-foreground', col.num && 'text-right', col.cls)}>
      <Button type="button" variant="ghost" size="sm" onClick={() => onSort(col.key)}
        className={cn('h-8 gap-1 px-1.5 text-xs font-semibold', col.num ? '-mr-1.5 ml-auto' : '-ml-1.5', active ? 'text-foreground' : 'text-muted-foreground')}>
        {t(col.label)}
        {/* ok yeri hep ayrılır: başlık metni sayılarla (ton simgesi yeri dahil) aynı hizada kalır */}
        {active ? <Arrow aria-hidden="true" className="size-3.5" /> : <span aria-hidden="true" className="size-3.5" />}
      </Button>
    </TableHead>
  )
}

function LastSeen({ iso }) {
  if (!iso) return <span className="text-muted-foreground">—</span>
  return <time dateTime={iso} title={formatDate(iso)} className="whitespace-nowrap">{shortDateTime(iso)}</time>
}

export default function HttpEndpointTable({
  t, rows, totalCount, truncatedTotal, wide, fmt, q, onQ, sort, onSort, selected, onSelect, onCsv, resetKey,
}) {
  const uid = useId().replace(/:/g, '')
  const listRef = useRef(null)
  const pager = usePagination(rows, { listKey: 'http-endpoints', preset: 'modal', resetDeps: [resetKey] })
  const pickSort = (key) => onSort(sort.key === key ? { key, dir: sort.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: defaultDir(key) })
  const countText = rows.length === totalCount ? t('hreq.tbl.count', fmtInt(totalCount)) : t('hreq.tbl.countOf', fmtInt(rows.length), fmtInt(totalCount))

  const toolbar = (
    <div className="flex w-full flex-wrap items-center gap-2 sm:w-auto">
      <InputGroup className="h-10 w-full min-w-0 basis-full sm:w-64 sm:flex-none sm:basis-auto lg:h-9 pointer-coarse:h-10">
        <InputGroupInput type="search" className="h-full" value={q} onChange={(e) => onQ(e.target.value)} placeholder={t('hreq.tbl.search')}
          aria-label={t('hreq.tbl.search')} />
        <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
      </InputGroup>
      {!wide && (
        <div className="min-w-0 flex-1 sm:flex-none [&>[data-slot=native-select-wrapper]]:w-full">
          <NativeSelect aria-label={t('hreq.sort.label')} value={`${sort.key}:${sort.dir}`} className="h-10 text-base sm:text-sm"
            onChange={(e) => { const [key, dir] = e.target.value.split(':'); onSort({ key, dir }) }}>
            {SORT_KEYS.map((k) => (
              <NativeSelectOption key={k} value={`${k}:${defaultDir(k)}`}>{t(`hreq.sort.${k}`)}</NativeSelectOption>
            ))}
            {!SORT_KEYS.some((k) => sort.key === k && sort.dir === defaultDir(k)) && (
              <NativeSelectOption value={`${sort.key}:${sort.dir}`}>
                {t(COLS.find((c) => c.key === sort.key)?.label ?? 'hreq.col.requests')} {sort.dir === 'asc' ? '↑' : '↓'}
              </NativeSelectOption>
            )}
          </NativeSelect>
        </div>
      )}
      <Button type="button" variant="outline" onClick={onCsv} disabled={!rows.length} aria-label={t('hreq.tbl.csvLabel')}
        className="h-10 gap-1.5 lg:h-9 pointer-coarse:h-10">
        <Download aria-hidden="true" />{t('hreq.tbl.csv')}
      </Button>
    </div>
  )

  return (
    <Panel data-slot="hreq-endpoints" titleId={`${uid}-t`}
      title={<span className="inline-flex flex-wrap items-center gap-2">{t('hreq.tbl.title')}
        <Badge variant="secondary" className="font-normal tabular-nums">{countText}</Badge></span>}
      right={toolbar}>
      {truncatedTotal > 0 && <p className="m-0 text-xs text-muted-foreground">{t('hreq.tbl.truncated', fmtInt(totalCount), fmtInt(truncatedTotal))}</p>}
      {rows.length === 0 ? (
        <p data-slot="hreq-endpoints-empty" className="m-0 rounded-md bg-muted/50 px-3 py-6 text-center text-sm text-muted-foreground">{t('hreq.tbl.none')}</p>
      ) : wide ? (
        <div ref={listRef} className="overflow-hidden rounded-lg border">
          <Table aria-labelledby={`${uid}-t`} className="text-[13px]">
            <TableHeader className="bg-muted/40">
              <TableRow className="hover:bg-transparent">
                {COLS.map((c) => <SortHeader key={c.key} col={c} sort={sort} onSort={pickSort} t={t} />)}
              </TableRow>
            </TableHeader>
            <TableBody>
              {pager.pageItems.map((e) => {
                const sel = selected === e.endpoint
                return (
                  <TableRow key={e.endpoint} data-endpoint={e.endpoint} data-state={sel ? 'selected' : undefined}
                    className="cursor-pointer" onClick={() => onSelect(e.endpoint)}>
                    <TableCell className="max-w-0 min-w-[16rem] px-2 py-1.5 whitespace-normal">
                      <Button type="button" variant="ghost" onClick={(ev) => { ev.stopPropagation(); onSelect(e.endpoint) }}
                        aria-label={t('hreq.tbl.open', e.endpoint)} aria-pressed={sel}
                        className="h-auto min-h-9 w-full justify-start px-1.5 py-1 text-left font-normal whitespace-normal hover:bg-transparent">
                        <EndpointLabel endpoint={e.endpoint} method={e.method} path={e.path} />
                      </Button>
                    </TableCell>
                    <TableCell className="px-3 text-right tabular-nums">{fmtInt(e.count)}<span aria-hidden="true" className="ml-1 inline-block size-3.5" /></TableCell>
                    <TableCell className="px-3 text-right"><ToneValue t={t} tone={errTone(e.errorRate, e.count)} reserve className="justify-end">{fmtPct(e.errorRate)}</ToneValue></TableCell>
                    <TableCell className="px-3 text-right"><ToneValue t={t} tone={msTone(e.avg)} reserve className="justify-end">{fmt.value(e.avg)}</ToneValue></TableCell>
                    <TableCell className="px-3 text-right"><ToneValue t={t} tone={msTone(e.p95)} reserve className="justify-end">{fmt.value(e.p95)}</ToneValue></TableCell>
                    <TableCell className="hidden px-3 text-right tabular-nums xl:table-cell">{fmt.value(e.max)}<span aria-hidden="true" className="ml-1 inline-block size-3.5" /></TableCell>
                    <TableCell className="px-3 text-right text-muted-foreground"><LastSeen iso={e.lastSeen} /></TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </div>
      ) : (
        <ul ref={listRef} data-slot="hreq-endpoint-cards" className="m-0 flex list-none flex-col gap-2 p-0">
          {pager.pageItems.map((e, i) => {
            const sel = selected === e.endpoint
            return (
              <li key={e.endpoint} data-endpoint={e.endpoint}>
                <Button type="button" variant="outline" onClick={() => onSelect(e.endpoint)} aria-pressed={sel}
                  aria-label={t('hreq.tbl.open', e.endpoint)} aria-describedby={`${uid}-m${i}`} data-state={sel ? 'selected' : undefined}
                  className={cn('h-auto w-full flex-col items-stretch gap-2 px-3 py-2.5 text-left font-normal whitespace-normal',
                    sel && 'border-primary bg-primary/5')}>
                  <span className="flex items-start justify-between gap-2">
                    <EndpointLabel endpoint={e.endpoint} method={e.method} path={e.path} />
                    <ChevronRight aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                  </span>
                  <span id={`${uid}-m${i}`} className="grid grid-cols-3 gap-x-3 gap-y-1 text-xs">
                    <Metric label={t('hreq.col.requests')}>{fmtInt(e.count)}</Metric>
                    <Metric label={t('hreq.col.errorRate')}><ToneValue t={t} reserve tone={errTone(e.errorRate, e.count)}>{fmtPct(e.errorRate)}</ToneValue></Metric>
                    <Metric label={t('hreq.col.p95')}><ToneValue t={t} tone={msTone(e.p95)}>{fmt.value(e.p95)}</ToneValue></Metric>
                    <Metric label={t('hreq.col.avg')}><ToneValue t={t} tone={msTone(e.avg)}>{fmt.value(e.avg)}</ToneValue></Metric>
                    <Metric label={t('hreq.col.max')}>{fmt.value(e.max)}</Metric>
                    <Metric label={t('hreq.col.lastSeen')}><LastSeen iso={e.lastSeen} /></Metric>
                  </span>
                </Button>
              </li>
            )
          })}
        </ul>
      )}
      {/* Dokunmatikte / 1024 px altında (tablet dâhil) sayfa düğmeleri 40 px — ortak PaginationBar'ın pencere ön ayarı 24–32 px çizer */}
      <div className="max-lg:[&_[data-slot=pagination-bar]_button]:min-h-10 max-lg:[&_[data-slot=pagination-bar]_button]:min-w-10 max-lg:[&_[data-slot=pagination-bar]_input]:h-10 pointer-coarse:[&_[data-slot=pagination-bar]_button]:min-h-10 pointer-coarse:[&_[data-slot=pagination-bar]_button]:min-w-10 pointer-coarse:[&_[data-slot=pagination-bar]_input]:h-10">
        <PaginationBar {...pager} scrollTargetRef={listRef} />
      </div>
    </Panel>
  )
}

function Metric({ label, children }) {
  return (
    <span className="flex min-w-0 flex-col">
      <span className="truncate text-[11px] text-muted-foreground">{label}</span>
      <span className="truncate font-semibold text-foreground tabular-nums">{children}</span>
    </span>
  )
}
