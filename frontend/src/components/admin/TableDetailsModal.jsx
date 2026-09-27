import { useMemo, useState } from 'react'
import {
  Activity, ArrowLeft, ArrowRight, Database, Fingerprint, GitBranch, Hash, HardDrive, Key, Link2, Network, Play, Rows3,
  Table, Zap,
} from 'lucide-react'
import { formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import { LoadingBlock, ProgressBar } from '../ui/Progress.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Table as UiTable, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'
import { tableQuery } from './sql/sqlUtils.js'

/** Kısıt/indeks türü rozet tonları (PK amber, FK yeşil, UQ mavi, CHECK turuncu, INDEX gri, TRIGGER/ID eflatun). */
const CT_TONE = {
  primary: 'border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-300',
  foreign: 'border-emerald-500/40 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300',
  unique: 'border-blue-500/40 bg-blue-500/10 text-blue-700 dark:text-blue-300',
  check: 'border-orange-500/40 bg-orange-500/10 text-orange-700 dark:text-orange-300',
  index: 'border-zinc-500/30 bg-zinc-500/10 text-zinc-700 dark:text-zinc-300',
  trigger: 'border-fuchsia-500/40 bg-fuchsia-500/10 text-fuchsia-700 dark:text-fuchsia-300',
}
function CtBadge({ kind, title, children }) {
  return (
    <Badge variant="outline" data-ct={kind} title={title}
      className={cn('rounded-sm px-1.5 text-[0.72em] font-bold', CT_TONE[kind] || CT_TONE.index)}>
      {children}
    </Badge>
  )
}

const MONO = 'font-mono'
const MUTED = 'text-muted-foreground'
const DL = 'grid grid-cols-1 gap-x-3.5 gap-y-1 text-[0.9em] sm:grid-cols-[max-content_1fr] [&_dd]:[overflow-wrap:anywhere] [&_dt]:font-semibold [&_dt]:whitespace-nowrap [&_dt]:text-muted-foreground [&_dd]:mb-1 sm:[&_dd]:mb-0'
const TH = 'h-8 px-2.5 text-[0.9em] font-bold text-muted-foreground'
const SECTION = 'min-w-0 rounded-xl border bg-card px-3.5 py-3'

/** Bölüm başlığı + sayaç (h4). */
function SecHead({ icon: Icon, label, count }) {
  return (
    <h4 className="mb-2 flex items-center gap-1.5 text-[0.9em] font-semibold">
      {Icon && <Icon size={14} aria-hidden="true" />} {label}
      {count != null && <Badge variant="secondary" className="rounded-full tabular-nums">{count}</Badge>}
    </h4>
  )
}

/**
 * SQL Playground — tablo şema ayrıntısı (salt okunur). 2026-09-27 shadcn yeniden tasarımı: ModalShell + KPI şeridi +
 * shadcn Tabs — Genel bakış · Kolonlar · Kısıtlar & indeksler · İlişkiler.
 *  • Kolonlar: PK/FK/UQ/IDX/ID rozetleri, tip + sınır, NOT NULL, varsayılan, pg_stats, açıklama (telefonda düşük öncelikli
 *    sütunlar gizli, tablo yatay kayar)
 *  • İlişkiler: giden FK, gelen FK, `*_id` çıkarımı — tablo adı o tablonun ayrıntısını açar (onOpenTable)
 *  • Eylemler: "Bu tabloyu sorgula" (onUseQuery), "Diyagramda göster" (onShowInDiagram), adı kopyala
 * Veri `/admin/sql/tables/{name}/details` (Postgres katalogu); eski (dar) yanıtla geriye uyumlu. Örnek satır sekmesi YOK:
 * uç örnek satır döndürmüyor ve sorgu çalıştırmak geçmişe/denetime yazar (API boşluğu).
 */
export default function TableDetailsModal({ table, details, loading, onClose, onOpenTable, onUseQuery, onShowInDiagram }) {
  const t = useT()
  const [tab, setTab] = useState('overview')
  const columns     = useMemo(() => details?.columns ?? [], [details])
  const constraints = useMemo(() => details?.constraints ?? [], [details])
  const indexes     = details?.indexes ?? []
  const triggers    = details?.triggers ?? []
  const act         = details?.activity ?? {}
  const stats       = details?.stats ?? null
  const refBy       = details?.referenced_by ?? []
  const inferred    = details?.inferred_relations ?? []
  const hasAct      = Object.keys(act).length > 0
  const fmt = (v) => (v ? formatDateSec(v) : '—')
  const num = (v) => (v == null ? '—' : Number(v).toLocaleString())
  const pct = (v) => (v == null ? '—' : `${Math.round(Number(v) * 100)}%`)
  const fks = useMemo(() => constraints.filter((c) => /FOREIGN/i.test(c.type)), [constraints])
  const pkCols = useMemo(() => columns.filter((c) => c.is_pk).map((c) => c.column_name), [columns])
  const ctKind = (type) => String(type || '').split(' ')[0].toLowerCase()
  const seq = Number(stats?.seq_scan ?? 0), idx = Number(stats?.idx_scan ?? 0), scanTotal = seq + idx
  const idxShare = scanTotal > 0 ? Math.round((idx / scanTotal) * 100) : null

  const tableLink = (name) => onOpenTable
    ? (
      <SimpleTooltip content={t('sql.td.openOther', name)}>
        <Button type="button" variant="link" size="xs" className="h-auto p-0 font-mono text-[1em] underline underline-offset-2"
          onClick={() => onOpenTable(name)}>{name}</Button>
      </SimpleTooltip>
    )
    : <span className={MONO}>{name}</span>

  const kpi = (key, Icon, val, label) => (
    <div data-kpi={key} className="flex min-w-0 flex-col gap-0.5 rounded-xl border bg-muted/40 px-3 py-2">
      <Icon size={14} aria-hidden="true" className={MUTED} />
      <span className="truncate text-[1.02em] font-bold tabular-nums">{val}</span>
      <span className={cn('truncate text-[0.74em]', MUTED)}>{label}</span>
    </div>
  )
  const badge = (c) => (
    <span className="inline-flex flex-wrap gap-[3px]">
      {c.is_pk && <CtBadge kind="primary" title="PRIMARY KEY">PK</CtBadge>}
      {c.is_fk && <CtBadge kind="foreign" title="FOREIGN KEY">FK</CtBadge>}
      {c.is_unique && !c.is_pk && <CtBadge kind="unique" title="UNIQUE">UQ</CtBadge>}
      {c.is_indexed && !c.is_pk && <CtBadge kind="index" title={t('sql.td.indexed')}>IDX</CtBadge>}
      {c.is_identity && <CtBadge kind="trigger" title="IDENTITY">ID</CtBadge>}
    </span>
  )
  const hasStats = columns.some((c) => c.null_frac != null || c.n_distinct != null)
  const hasComments = columns.some((c) => c.comment)
  const none = <p className={cn('px-0.5 py-1 text-[0.85em] italic', MUTED)}>{t('sql.td.none')}</p>
  const muted = (children) => <span className={MUTED}>{children}</span>
  const REL_LI = 'flex flex-wrap items-center gap-1.5 rounded-lg border bg-muted/40 px-2.5 py-1.5 text-[0.88em]'

  const footer = (
    <div className="flex w-full flex-wrap items-center justify-end gap-2 [&>*]:flex-1 sm:[&>*]:flex-none">
      <span className="hidden sm:mr-auto sm:inline-flex sm:flex-none">
        <CopyButton value={table} label={t('sql.td.copyName')} copiedLabel={t('sql.copied')} buttonSize="icon" />
      </span>
      {onShowInDiagram && (
        <Button type="button" variant="outline" onClick={() => onShowInDiagram(table)}><Network /> {t('sql.diag.showInDiagram')}</Button>
      )}
      {onUseQuery && (
        <Button type="button" variant="outline" onClick={() => { onUseQuery(tableQuery(table)); onClose?.() }}><Play /> {t('sql.ex.queryThis')}</Button>
      )}
      <Button type="button" onClick={onClose}>{t('sql.closeRowDetails')}</Button>
    </div>
  )

  const TAB_TRIGGER = 'h-full flex-none px-3 text-xs sm:text-sm'

  return (
    <ModalShell open onClose={onClose} title={t('sql.td.title', table)} icon={Table} size="xl" scrollBody footer={footer}>
      {loading ? (
        <LoadingBlock label={t('sql.td.loading')} size={18} />
      ) : !details ? (
        <StatusBlock tone="danger" title={t('sql.td.loadError')} />
      ) : (
        <div className="flex min-w-0 flex-col gap-3" data-testid="table-details">
          {/* KPI şeridi — telefonda 2'li, genişte otomatik sütunlar */}
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-[repeat(auto-fit,minmax(128px,1fr))]">
            {kpi('rows', Rows3, num(act.live_rows), t('sql.meta.liveRows'))}
            {kpi('size', HardDrive, act.size_total || '—', t('sql.meta.size'))}
            {kpi('columns', Database, columns.length, t('sql.td.columns'))}
            {kpi('indexes', Hash, indexes.length, t('sql.td.indexesShort'))}
            {kpi('constraints', Fingerprint, constraints.length, t('sql.td.constraintsShort'))}
            {kpi('triggers', Zap, triggers.length, t('sql.td.triggers'))}
            {kpi('lastChange', Activity, act.last_change_at ? fmt(act.last_change_at) : '—', t('sql.meta.lastChange'))}
          </div>

          <Tabs value={tab} onValueChange={setTab} className="min-w-0 gap-3">
            <TabsList aria-label={t('sql.td.sections')} className="h-10 w-full justify-start overflow-x-auto sm:w-fit">
              <TabsTrigger value="overview" className={TAB_TRIGGER}>{t('sql.td.tabOverview')}</TabsTrigger>
              <TabsTrigger value="columns" className={TAB_TRIGGER}>{t('sql.td.columns')} ({columns.length})</TabsTrigger>
              <TabsTrigger value="integrity" className={TAB_TRIGGER}>{t('sql.td.tabIntegrity')} ({constraints.length + indexes.length + triggers.length})</TabsTrigger>
              <TabsTrigger value="relations" className={TAB_TRIGGER}>{t('sql.td.tabRelations')} ({fks.length + refBy.length + inferred.length})</TabsTrigger>
            </TabsList>

            <TabsContent value="overview" className="grid grid-cols-1 gap-3 md:grid-cols-2">
              <section className={SECTION}>
                <SecHead icon={Key} label={t('sql.td.identity')} />
                <dl className={DL}>
                  <dt>{t('sql.td.tableName')}</dt><dd className={MONO}>{table}</dd>
                  <dt>{t('sql.td.primaryKey')}</dt><dd>{pkCols.length ? <code className={MONO}>{pkCols.join(', ')}</code> : muted(t('sql.td.none'))}</dd>
                  <dt>{t('sql.meta.created')}</dt>
                  <dd>
                    {act.first_seen_at ? <>{act.first_seen_approx ? '≈ ' : ''}{fmt(act.first_seen_at)}</> : t('sql.meta.unknown')}
                    {act.first_seen_approx && muted(<> · {t('sql.meta.createdApprox')}</>)}
                    {act.first_seen_version && muted(<> · v{act.first_seen_version}</>)}
                  </dd>
                  <dt>{t('sql.td.comment')}</dt><dd>{details.comment || muted('—')}</dd>
                  <dt>{t('sql.td.fkOut')}</dt><dd className="flex flex-wrap gap-x-2">{fks.length ? fks.map((c) => <span key={c.name}>{tableLink(c.ref_table)}</span>) : muted(t('sql.td.none'))}</dd>
                  <dt>{t('sql.td.fkIn')}</dt><dd className="flex flex-wrap gap-x-2">{refBy.length ? refBy.map((r) => <span key={r.name}>{tableLink(r.from_table)}</span>) : muted(t('sql.td.none'))}</dd>
                </dl>
              </section>

              <section className={SECTION}>
                <SecHead icon={Activity} label={t('sql.td.usage')} />
                <dl className={DL}>
                  <dt>{t('sql.meta.liveRows')}</dt><dd>{num(act.live_rows)}{stats?.dead_rows != null && muted(<> · {t('sql.td.deadRows', num(stats.dead_rows))}</>)}</dd>
                  <dt>{t('sql.meta.size')}</dt><dd>{act.size_total ? <>{act.size_total}{act.size_data && act.size_data !== act.size_total ? muted(<> (data {act.size_data})</>) : null}</> : '—'}</dd>
                  <dt>{t('sql.meta.counters')}</dt>
                  <dd>{act.tup_ins != null ? `${num(act.tup_ins)} / ${num(act.tup_upd ?? 0)} / ${num(act.tup_del ?? 0)}` : '—'}</dd>
                  <dt>{t('sql.td.scans')}</dt>
                  <dd>
                    {stats ? (
                      <span className="inline-flex flex-wrap items-center gap-2">
                        <span className="w-[120px]"><ProgressBar value={idxShare ?? 0} max={100} size="sm" label={t('sql.td.scanHint', num(seq), num(idx))} /></span>
                        {muted(idxShare == null ? t('sql.td.noScans') : t('sql.td.scanShare', idxShare, num(seq), num(idx)))}
                      </span>
                    ) : '—'}
                  </dd>
                  <dt>{t('sql.meta.lastAnalyze')}</dt>
                  <dd>{fmt(act.last_maintenance_at)}{stats?.mod_since_analyze != null && Number(stats.mod_since_analyze) > 0 && muted(<> · {t('sql.td.modSince', num(stats.mod_since_analyze))}</>)}</dd>
                </dl>
              </section>

              {hasAct && (
                <section className={cn(SECTION, 'md:col-span-2')}>
                  <SecHead icon={Database} label={t('sql.td.activity')} />
                  <dl className={DL}>
                    <dt>{t('sql.meta.lastChange')}</dt>
                    <dd>{fmt(act.last_change_at)} {muted(<>· {t('sql.meta.lastChangeHint')}</>)}</dd>
                    <dt>{t('sql.meta.lastDataMax')}</dt>
                    <dd>{fmt(act.last_record_at)}{act.last_record_column && muted(<> · <code className={MONO}>{act.last_record_column}</code></>)}</dd>
                    {stats && (<>
                      <dt>{t('sql.td.vacuum')}</dt>
                      <dd>{fmt(stats.last_autovacuum || stats.last_vacuum)} {muted(<>· {t('sql.td.analyze')}: {fmt(stats.last_autoanalyze || stats.last_analyze)}</>)}</dd>
                    </>)}
                  </dl>
                </section>
              )}
            </TabsContent>

            <TabsContent value="columns" className="min-w-0">
              <div className="overflow-hidden rounded-xl border">
                <UiTable data-testid="sqltd-columns" className="text-[0.84em]">
                  <TableHeader className="bg-muted/50">
                    <TableRow>
                      <TableHead className={TH}>{t('sql.td.colName')}</TableHead>
                      <TableHead className={TH}>{t('sql.td.colKeys')}</TableHead>
                      <TableHead className={TH}>{t('sql.td.colType')}</TableHead>
                      <TableHead className={cn(TH, 'hidden md:table-cell')}>{t('sql.td.colBounds')}</TableHead>
                      <TableHead className={TH}>{t('sql.td.colNull')}</TableHead>
                      <TableHead className={TH}>{t('sql.td.colDefault')}</TableHead>
                      {hasStats && <TableHead className={cn(TH, 'hidden md:table-cell')} title={t('sql.td.colStatsHint')}>{t('sql.td.colStats')}</TableHead>}
                      {hasComments && <TableHead className={cn(TH, 'hidden lg:table-cell')}>{t('sql.td.comment')}</TableHead>}
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {columns.map((c) => (
                      <TableRow key={c.column_name} data-pk={c.is_pk ? 'true' : undefined}>
                        <TableCell className={cn(MONO, 'align-top', c.is_pk && 'font-bold')}>{c.column_name}</TableCell>
                        <TableCell className="align-top">{badge(c)}</TableCell>
                        <TableCell className={cn(MONO, 'align-top text-primary')}>{c.udt_name || c.data_type}</TableCell>
                        <TableCell className={cn('hidden align-top md:table-cell', MUTED)}>{c.bounds || '—'}</TableCell>
                        <TableCell className="align-top">
                          {c.is_nullable === 'NO'
                            ? <CtBadge kind="check">NOT NULL</CtBadge>
                            : muted('NULL')}
                        </TableCell>
                        <TableCell className={cn(MONO, 'max-w-[220px] truncate align-top', MUTED)} title={c.column_default ?? undefined}>{c.column_default ?? '—'}</TableCell>
                        {hasStats && (
                          <TableCell className={cn('hidden align-top text-[0.92em] md:table-cell', MUTED)}>
                            {c.null_frac != null || c.n_distinct != null
                              ? <>{t('sql.td.nullPct', pct(c.null_frac))} · {t('sql.td.distinct', c.n_distinct == null ? '—' : Number(c.n_distinct) < 0 ? `${Math.round(-Number(c.n_distinct) * 100)}%` : num(c.n_distinct))}{c.avg_width != null ? ` · ${c.avg_width} B` : ''}</>
                              : '—'}
                          </TableCell>
                        )}
                        {hasComments && <TableCell className="hidden max-w-[260px] align-top whitespace-normal [overflow-wrap:anywhere] lg:table-cell">{c.comment || muted('—')}</TableCell>}
                      </TableRow>
                    ))}
                  </TableBody>
                </UiTable>
              </div>
            </TabsContent>

            <TabsContent value="integrity" className="flex flex-col gap-4">
              <section>
                <SecHead icon={Fingerprint} label={t('sql.td.integrity')} count={constraints.length} />
                {constraints.length === 0 ? none : (
                  <div className="overflow-hidden rounded-xl border">
                    <UiTable className="text-[0.84em]">
                      <TableHeader className="bg-muted/50">
                        <TableRow>
                          <TableHead className={TH}>{t('sql.td.ctType')}</TableHead><TableHead className={TH}>{t('sql.td.ctName')}</TableHead>
                          <TableHead className={TH}>{t('sql.td.columns')}</TableHead><TableHead className={TH}>{t('sql.td.ctTarget')}</TableHead>
                          <TableHead className={TH}>{t('sql.td.ctDef')}</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>{constraints.map((c) => (
                        <TableRow key={c.name}>
                          <TableCell className="align-top"><CtBadge kind={ctKind(c.type)}>{c.type}</CtBadge></TableCell>
                          <TableCell className={cn(MONO, 'align-top')}>{c.name}</TableCell>
                          <TableCell className={cn(MONO, 'align-top')}>{(c.columns || []).join(', ') || '—'}</TableCell>
                          <TableCell className="align-top whitespace-normal">{c.ref_table ? <>{tableLink(c.ref_table)}{muted(<> ({(c.ref_columns || []).join(', ')})</>)}{c.on_delete && c.on_delete !== 'NO ACTION' && muted(<> · ON DELETE {c.on_delete}</>)}</> : muted('—')}</TableCell>
                          <TableCell className="min-w-[220px] align-top whitespace-normal"><code className={cn(MONO, 'text-[0.9em] break-all')}>{c.definition}</code></TableCell>
                        </TableRow>
                      ))}</TableBody>
                    </UiTable>
                  </div>
                )}
              </section>

              <section>
                <SecHead icon={Hash} label={t('sql.td.indexes')} count={indexes.length} />
                {indexes.length === 0 ? none : (
                  <div className="overflow-hidden rounded-xl border">
                    <UiTable className="text-[0.84em]">
                      <TableHeader className="bg-muted/50">
                        <TableRow>
                          <TableHead className={TH}>{t('sql.td.ctType')}</TableHead><TableHead className={TH}>{t('sql.td.ctName')}</TableHead>
                          <TableHead className={TH}>{t('sql.td.columns')}</TableHead><TableHead className={TH}>{t('sql.td.ixScans')}</TableHead>
                          <TableHead className={TH}>{t('sql.meta.size')}</TableHead><TableHead className={TH}>{t('sql.td.ctDef')}</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>{indexes.map((ix) => {
                        const unused = ix.scans != null && Number(ix.scans) === 0 && !ix.is_primary && !ix.is_unique
                        return (
                          <TableRow key={ix.name} data-unused={unused ? 'true' : undefined} className="data-[unused]:opacity-75">
                            <TableCell className="align-top"><CtBadge kind={ix.is_primary ? 'primary' : ix.is_unique ? 'unique' : 'index'}>{ix.is_primary ? 'PRIMARY' : ix.is_unique ? 'UNIQUE' : 'INDEX'}</CtBadge></TableCell>
                            <TableCell className={cn(MONO, 'align-top')}>{ix.name}</TableCell>
                            <TableCell className={cn(MONO, 'align-top')}>{(ix.columns || []).join(', ') || '—'}</TableCell>
                            <TableCell className="align-top">{ix.scans != null ? <span className="inline-flex items-center gap-1.5">{num(ix.scans)}{unused && <CtBadge kind="check" title={t('sql.td.unusedHint')}>{t('sql.td.unused')}</CtBadge>}</span> : '—'}</TableCell>
                            <TableCell className="align-top">{ix.size || '—'}</TableCell>
                            <TableCell className="min-w-[220px] align-top whitespace-normal"><code className={cn(MONO, 'text-[0.9em] break-all')}>{ix.definition}</code></TableCell>
                          </TableRow>
                        )
                      })}</TableBody>
                    </UiTable>
                  </div>
                )}
              </section>

              <section>
                <SecHead icon={Zap} label={t('sql.td.triggers')} count={triggers.length} />
                {triggers.length === 0 ? none : (
                  <ul className="flex flex-col gap-1.5">
                    {triggers.map((tg, i) => (
                      <li key={i} className="flex flex-wrap items-center gap-2 text-[0.88em]"><CtBadge kind="trigger">{tg.timing} {tg.event}</CtBadge><span className={MONO}>{tg.name}</span></li>
                    ))}
                  </ul>
                )}
              </section>
            </TabsContent>

            <TabsContent value="relations" className="flex flex-col gap-4">
              <section>
                <SecHead icon={ArrowRight} label={t('sql.td.fkOut')} count={fks.length} />
                {fks.length === 0 ? none : (
                  <ul data-slot="rel-list" className="flex flex-col gap-1.5">
                    {fks.map((c) => (
                      <li key={c.name} className={REL_LI}>
                        <span className={cn(MONO, 'break-all')}>{table}.{(c.columns || []).join(', ')}</span>
                        <ArrowRight size={12} aria-hidden="true" />
                        {tableLink(c.ref_table)}{muted(<>.{(c.ref_columns || []).join(', ')}</>)}
                        {c.on_delete && <CtBadge kind="check">ON DELETE {c.on_delete}</CtBadge>}
                        <span className={cn('text-[0.85em] sm:ml-auto', MUTED)}>{c.name}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
              <section>
                <SecHead icon={ArrowLeft} label={t('sql.td.fkIn')} count={refBy.length} />
                {refBy.length === 0 ? none : (
                  <ul data-slot="rel-list" className="flex flex-col gap-1.5">
                    {refBy.map((r) => (
                      <li key={r.name} className={REL_LI}>
                        {tableLink(r.from_table)}{muted(<>.{(r.from_columns || []).join(', ')}</>)}
                        <ArrowRight size={12} aria-hidden="true" />
                        <span className={MONO}>{table}</span>
                        {r.on_delete && <CtBadge kind="check">ON DELETE {r.on_delete}</CtBadge>}
                        <span className={cn('text-[0.85em] sm:ml-auto', MUTED)}>{r.name}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
              <section>
                <SecHead icon={GitBranch} label={t('sql.td.inferred')} count={inferred.length} />
                <p className={cn('mb-2 text-xs', MUTED)}>{t('sql.td.inferredHint')}</p>
                {inferred.length === 0 ? none : (
                  <ul data-slot="rel-list" className="flex flex-col gap-1.5">
                    {inferred.map((e, i) => (
                      <li key={i} className={cn(REL_LI, 'border-dashed')}>
                        {e.from === table ? <span className={MONO}>{table}</span> : tableLink(e.from)}{muted(<>.{e.column}</>)}
                        <ArrowRight size={12} aria-hidden="true" />
                        {e.to === table ? <span className={MONO}>{table}</span> : tableLink(e.to)}
                      </li>
                    ))}
                  </ul>
                )}
              </section>
              <p className={cn('flex items-center gap-1.5 text-xs', MUTED)}><Link2 size={12} aria-hidden="true" /> {t('sql.td.relHint')}</p>
            </TabsContent>
          </Tabs>
        </div>
      )}
    </ModalShell>
  )
}
