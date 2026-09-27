import { useMemo, useState } from 'react'
import { ChevronRight, Database, Network, RefreshCw, Search, Table2, TableProperties, X } from 'lucide-react'
import { formatDateSec } from '../../../api/client'
import { useDateLocale, useT } from '../../../i18n/index.jsx'
import KebabMenu from '../../ui/KebabMenu.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import SimpleTooltip from '../../ui/SimpleTooltip.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import { formatCompact, relativeFrom, shortType } from './sqlUtils.js'

function KeyTag({ kind, title }) {
  if (kind === 'pk') return <Badge variant="outline" title={title} className="h-4 shrink-0 rounded-sm border-amber-500/40 bg-amber-500/10 px-1 text-[9px] leading-none font-bold text-amber-700 dark:text-amber-300">PK</Badge>
  if (kind === 'fk') return <Badge variant="outline" title={title} className="h-4 shrink-0 rounded-sm border-emerald-500/40 bg-emerald-500/10 px-1 text-[9px] leading-none font-bold text-emerald-700 dark:text-emerald-300">FK</Badge>
  return <span aria-hidden="true" className="inline-block w-[22px] shrink-0" />
}

/**
 * Şema gezgini — tablolar (satır tahmini, kolon sayısı yüklendiyse, son değişim), arama, kolonlara açılım.
 *
 * Kolon satırında PK/FK rozeti: FK ilişki yanıtından (gerçek FK + `*_id` çıkarımı, hedef tabloyla), PK tablo ayrıntısı
 * açıldıysa oradan (liste ucu PK/FK döndürmüyor — API boşluğu). Tablo adına dokunmak `SELECT * … LIMIT 100` yazar,
 * kolona dokunmak adını imlecin yerine ekler. Satır menüsü: ayrıntı, diyagramda göster, adı ekle.
 */
export default function SchemaExplorer({
  tables, loading, error, onRefresh, columnsMap, onLoadColumns, fkMap, pkMap,
  onQuery, onInsert, onDetails, onDiagram, className, headerless = false,
}) {
  const t = useT()
  const locale = useDateLocale()
  const [q, setQ] = useState('')
  const [open, setOpen] = useState(() => new Set())
  const [loadingCols, setLoadingCols] = useState(() => new Set())

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase()
    if (!needle) return tables || []
    return (tables || []).filter((tb) => tb.table_name.toLowerCase().includes(needle)
      || (columnsMap?.[tb.table_name] || []).some((c) => c.column_name.toLowerCase().includes(needle)))
  }, [tables, q, columnsMap])

  const toggle = async (name) => {
    const next = new Set(open)
    if (next.has(name)) { next.delete(name); setOpen(next); return }
    next.add(name)
    setOpen(next)
    if (!columnsMap?.[name]) {
      setLoadingCols((s) => new Set(s).add(name))
      try { await onLoadColumns?.([name]) } finally {
        setLoadingCols((s) => { const n = new Set(s); n.delete(name); return n })
      }
    }
  }

  const metaTip = (tb) => (tb.first_seen_at || tb.last_change_at)
    ? `${t('sql.meta.created')}: ${tb.first_seen_at ? (tb.first_seen_approx ? '≈ ' : '') + formatDateSec(tb.first_seen_at) : t('sql.meta.unknown')} · ${t('sql.meta.lastChange')}: ${tb.last_change_at ? formatDateSec(tb.last_change_at) : t('sql.meta.unknown')}`
    : null

  return (
    <section data-slot="sql-schema" aria-label={t('sql.schema')}
      className={cn('flex min-h-0 min-w-0 flex-col overflow-hidden rounded-xl border bg-card text-card-foreground shadow-sm', className)}>
      {!headerless && (
        <div className="flex items-center gap-2 border-b px-3 py-2">
          <Database aria-hidden="true" className="size-4 text-muted-foreground" />
          <h3 className="text-sm font-semibold">{t('sql.schema')}</h3>
          <Badge variant="secondary" className="rounded-full tabular-nums">{(tables || []).length}</Badge>
          <div className="ml-auto flex items-center gap-0.5">
            <SimpleTooltip content={t('sql.diag.open')}>
              <Button type="button" variant="ghost" size="icon-sm" className="pointer-coarse:size-10" onClick={() => onDiagram?.()} aria-label={t('sql.diag.open')}>
                <Network />
              </Button>
            </SimpleTooltip>
            <SimpleTooltip content={t('sql.refreshTables')}>
              <Button type="button" variant="ghost" size="icon-sm" className="pointer-coarse:size-10" onClick={onRefresh} disabled={loading}
                aria-busy={loading || undefined} aria-label={t('sql.refreshTables')}>
                {loading ? <Spinner size={14} inline decorative /> : <RefreshCw />}
              </Button>
            </SimpleTooltip>
          </div>
        </div>
      )}
      <div className="border-b p-2">
        <InputGroup className="h-10 sm:h-9">
          <InputGroupInput value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('sql.ex.search')} aria-label={t('sql.ex.search')} />
          <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
          {q && (
            <InputGroupAddon align="inline-end">
              <InputGroupButton size="icon-xs" aria-label={t('sql.ex.clearSearch')} onClick={() => setQ('')}><X /></InputGroupButton>
            </InputGroupAddon>
          )}
        </InputGroup>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain py-1">
        {loading && !(tables || []).length ? (
          <div className="flex flex-col gap-2 p-3" role="status" aria-label={t('sql.td.loading')}>
            {Array.from({ length: 8 }, (_, i) => <Skeleton key={i} className="h-9 w-full" />)}
          </div>
        ) : error ? (
          <StatusBlock tone="danger" title={t('sql.ex.loadError')} description={error} className="py-6"
            actions={<Button type="button" variant="outline" size="sm" onClick={onRefresh}>{t('sql.retry')}</Button>} />
        ) : filtered.length === 0 ? (
          <StatusBlock icon={Search} title={q ? t('sql.ex.noMatch') : t('sql.ex.empty')} className="py-6" />
        ) : (
          <ul data-slot="sql-schema-list" aria-label={t('sql.ex.listLabel')} className="flex flex-col">
            {filtered.map((tb) => {
              const name = tb.table_name
              const isOpen = open.has(name)
              const cols = columnsMap?.[name]
              const rows = tb.live_rows != null ? formatCompact(tb.live_rows, locale) : null
              const changed = relativeFrom(tb.last_change_at, locale)
              const fks = fkMap?.get(name)
              const pks = pkMap?.[name] ? new Set(pkMap[name]) : null
              return (
                <li key={name} data-state={isOpen ? 'open' : undefined} className="data-[state=open]:bg-muted/30">
                  <div className="flex min-w-0 items-center gap-0.5 px-1.5 hover:bg-muted/50">
                    <Button type="button" variant="ghost" size="icon-sm" aria-expanded={isOpen}
                      aria-label={t('sql.ex.toggleCols', name)} onClick={() => toggle(name)}
                      className="size-7 shrink-0 text-muted-foreground pointer-coarse:size-10">
                      <ChevronRight className={cn('transition-transform duration-150 motion-reduce:transition-none', isOpen && 'rotate-90')} />
                    </Button>
                    <SimpleTooltip content={metaTip(tb)} side="right">
                      <Button type="button" variant="ghost" onClick={() => onQuery(name)} aria-label={t('sql.ex.queryTable', name)}
                        className="h-auto min-w-0 flex-1 flex-col items-start gap-0 px-1.5 py-1.5 text-left font-normal hover:bg-transparent pointer-coarse:min-h-10">
                        <span className="w-full truncate font-mono text-[13px] font-medium">{name}</span>
                        <span className="flex w-full flex-wrap gap-x-2 text-[11px] leading-tight text-muted-foreground">
                          {rows != null && <span className="tabular-nums">{t('sql.diag.rowsShort', rows)}</span>}
                          {cols && <span className="tabular-nums">{t('sql.ex.colsShort', cols.length)}</span>}
                          {changed && <span>{t('sql.ex.changed', changed)}</span>}
                        </span>
                      </Button>
                    </SimpleTooltip>
                    <SimpleTooltip content={t('sql.td.open')}>
                      <Button type="button" variant="ghost" size="icon-sm" onClick={() => onDetails(name)}
                        aria-label={t('a11y.rowAction', name, t('sql.td.open'))} className="size-7 shrink-0 text-muted-foreground pointer-coarse:size-10">
                        <TableProperties />
                      </Button>
                    </SimpleTooltip>
                    <KebabMenu label={t('sql.res.rowActions')} rowLabel={name} items={[
                      { label: t('sql.ex.queryThis'), icon: <Table2 />, onClick: () => onQuery(name) },
                      { label: t('sql.ex.insertName'), onClick: () => onInsert(name) },
                      { label: t('sql.diag.showInDiagram'), icon: <Network />, onClick: () => onDiagram?.(name) },
                    ]} />
                  </div>
                  {isOpen && (
                    <ul data-slot="sql-schema-columns" aria-label={t('sql.ex.columnsOf', name)} className="mr-2 mb-1 ml-5 border-l py-0.5 pl-1.5">
                      {loadingCols.has(name) && !cols ? (
                        <li className="flex items-center gap-2 px-2 py-1.5 text-xs text-muted-foreground"><Spinner size={12} inline decorative />{t('sql.td.loading')}</li>
                      ) : (cols || []).map((c) => {
                        const fk = fks?.get(c.column_name)
                        const kind = pks?.has(c.column_name) ? 'pk' : fk ? 'fk' : null
                        return (
                          <li key={c.column_name}>
                            <Button type="button" variant="ghost" size="xs" onClick={() => onInsert(c.column_name)}
                              aria-label={t('sql.ex.insertCol', `${name}.${c.column_name}`)}
                              title={fk ? `${c.column_name} → ${fk.to}${fk.inferred ? ` (${t('sql.diag.inferredShort')})` : ''}` : c.data_type}
                              className="h-7 w-full justify-start gap-2 px-2 font-normal pointer-coarse:h-9">
                              <KeyTag kind={kind} />
                              <span className="min-w-0 flex-1 truncate text-left font-mono text-xs">{c.column_name}</span>
                              {fk && <span className="max-w-[40%] truncate font-mono text-[10.5px] text-emerald-700 dark:text-emerald-300">→ {fk.to}</span>}
                              {!fk && <span className="shrink-0 text-[10.5px] text-muted-foreground">{shortType(c.data_type)}{c.is_nullable === 'YES' ? '?' : ''}</span>}
                            </Button>
                          </li>
                        )
                      })}
                    </ul>
                  )}
                </li>
              )
            })}
          </ul>
        )}
      </div>
      {(tables || []).length > 0 && (
        <p className="border-t px-3 py-1.5 text-[11px] text-muted-foreground tabular-nums" aria-live="polite">
          {t('sql.ex.showing', filtered.length, (tables || []).length)}
        </p>
      )}
    </section>
  )
}
