import { useEffect, useId, useMemo } from 'react'
import { ArrowDownWideNarrow, ArrowUpNarrowWide, Database } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { usePagination } from '../../../hooks/usePagination.js'
import PaginationBar from '../../ui/PaginationBar.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { ToolbarSearch } from '../ListToolbar.jsx'
import { cn } from '@/lib/utils'
import { SortHead, TD, TD_NUM, TH, TH_NUM } from './DbParts.jsx'
import { filterSort, num } from './dbModel.js'

/**
 * Aranır / sıralanır / sayfalı liste — geniş ekranda (≥ 1024) shadcn Table, tablet ve telefonda KART listesi (aynı
 * veri, aynı arama/sıralama/sayfa). Kart kipinde sıralama başlığa değil "Sırala" seçicisine + yön düğmesine taşınır.
 *
 * Durum DENETİMLİ (`state` = { q, sort, page, nonce }, `onState(patch)`): panel tutar → veri yenilemesi ve sekme
 * değişimi arama/sıralama/sayfayı KORUR; özet kutucuğu `nonce`u artırarak listeyi süzgeçle sıfırlar.
 * `columns`: { key, label, num?, hide?, sortable?, sortValue?, render?, cellClass?, headClass? }.
 * Sayfalama standart `usePagination` + `PaginationBar` (paginationBase kapısı).
 */
export default function DbDataList({
  t, testId, listKey, rows, columns, searchKeys, searchLabel, defaultSort, emptyText, state = {}, onState, wide, renderCard,
}) {
  const sortId = useId()
  const q = state.q ?? ''
  const sortable = useMemo(() => columns.filter((c) => c.sortable !== false), [columns])
  // Kaynak değişince (ör. pg_stat_statements açıldı) sütun kümesi değişir: bilinmeyen sütuna göre sıralama varsayılana döner.
  const sort = state.sort && sortable.some((c) => c.key === state.sort.key) ? state.sort : defaultSort
  const sorted = useMemo(() => filterSort(rows, { q, keys: searchKeys, sort, columns }), [rows, q, searchKeys, sort, columns])
  const pager = usePagination(sorted, {
    listKey, preset: 'panel', initialPage: state.page || 1,
    resetDeps: [q, sort?.key, sort?.dir, state.nonce || 0],
  })
  // Sayfa panel durumuna geri yazılır: sekme değişip liste yeniden kurulunca aynı sayfadan açılır.
  useEffect(() => {
    if (pager.page !== (state.page || 1)) onState?.({ page: pager.page })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pager.page])

  const setSort = (next) => onState?.({ sort: next })
  const onHead = (c) => setSort(sort?.key === c.key
    ? { key: c.key, dir: sort.dir === 'asc' ? 'desc' : 'asc' }
    : { key: c.key, dir: c.num ? 'desc' : 'asc' })

  return (
    <div data-testid={testId} data-layout={wide ? 'table' : 'cards'} className="flex min-w-0 flex-col gap-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <ToolbarSearch value={q} onChange={(v) => onState?.({ q: v })} placeholder={searchLabel} ariaLabel={searchLabel}
          clearLabel={t('app.clear')} className="h-10 w-full max-w-none flex-auto sm:w-auto sm:max-w-xs sm:flex-none lg:h-9 pointer-coarse:h-10" />
        {!wide && sortable.length > 1 && (
          <div className="flex min-w-0 flex-auto items-center gap-1.5 sm:flex-none">
            <Label htmlFor={sortId} className="shrink-0 text-xs font-normal text-muted-foreground">{t('dba.sortBy')}</Label>
            <NativeSelect id={sortId} value={sort?.key || ''} className="h-10 min-w-0"
              onChange={(e) => {
                const c = sortable.find((x) => x.key === e.target.value)
                if (c) setSort({ key: c.key, dir: c.num ? 'desc' : 'asc' })
              }}>
              {sortable.map((c) => <NativeSelectOption key={c.key} value={c.key}>{c.sortLabel || c.label}</NativeSelectOption>)}
            </NativeSelect>
            <Button type="button" variant="outline" size="icon" className="size-10 shrink-0"
              aria-label={sort?.dir === 'asc' ? t('dba.sortAscNow') : t('dba.sortDescNow')}
              onClick={() => setSort({ key: sort.key, dir: sort.dir === 'asc' ? 'desc' : 'asc' })}>
              {sort?.dir === 'asc' ? <ArrowUpNarrowWide aria-hidden="true" /> : <ArrowDownWideNarrow aria-hidden="true" />}
            </Button>
          </div>
        )}
        <span className="text-xs text-muted-foreground tabular-nums sm:ml-auto" data-slot="dataview-count">{t('inv.shownOf', num(sorted.length), num(rows.length))}</span>
      </div>

      {rows.length === 0
        ? <StatusBlock tone="neutral" icon={Database} title={emptyText} />
        : sorted.length === 0
          ? <StatusBlock tone="neutral" icon={Database} title={t('db.noMatch')} />
          : wide
            ? (
              <div className="overflow-hidden rounded-lg border border-border">
                <Table className="text-[0.84em]">
                  <TableHeader><TableRow>
                    {columns.map((c) => (c.sortable === false
                      ? <TableHead key={c.key} className={cn(c.num ? TH_NUM : TH, c.hide, c.headClass)}>{c.label}</TableHead>
                      : <SortHead key={c.key} label={c.label} numeric={c.num} active={sort?.key === c.key} dir={sort?.dir}
                          className={cn(c.hide, c.headClass)} onSort={() => onHead(c)} />))}
                  </TableRow></TableHeader>
                  <TableBody>
                    {pager.pageItems.map((r, i) => (
                      <TableRow key={r._key ?? i} data-row="">
                        {columns.map((c) => (
                          <TableCell key={c.key} className={cn(c.num ? TD_NUM : TD, c.hide, c.cellClass)}>
                            {c.render ? c.render(r) : (c.num ? num(r[c.key]) : (r[c.key] ?? '—'))}
                          </TableCell>
                        ))}
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            )
            : (
              <ul data-slot="db-cards" className="m-0 grid list-none grid-cols-1 gap-2.5 p-0 @xl:grid-cols-2">
                {pager.pageItems.map((r, i) => <li key={r._key ?? i} className="min-w-0">{renderCard(r)}</li>)}
              </ul>
            )}
      {sorted.length > 0 && <PaginationBar {...pager} />}
    </div>
  )
}
