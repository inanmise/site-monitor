import { useMemo, useState } from 'react'
import { Table, Key, Link2, Hash, Fingerprint, Zap, Database, Rows3, HardDrive, Activity, ArrowRight, ArrowLeft, Play, Copy, GitBranch } from 'lucide-react'
import { formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { LoadingBlock, ProgressBar } from '../ui/Progress.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * SQL Playground — tablo şema detayları (salt-okunur). 2026-09-20 yeniden tasarım (kullanıcı bildirimi:
 * "çok basic, bilgiler düzgün sunulmamış"): ModalShell + KPI şeridi + dört sekme —
 *  • Genel bakış: kimlik (oluşturma, sürüm, açıklama, birincil anahtar), kullanım (satır, boyut, seq/idx tarama,
 *    ölü satır, ekleme/güncelleme/silme, bakım), zaman (son değişim, en son kayıt)
 *  • Kolonlar: PK/FK/UQ/IDX/IDENT rozetleri, tip + sınır, NULL, varsayılan, pg_stats (NULL %, ayrık, genişlik), açıklama
 *  • Bütünlük & indeksler: kısıtlar (yapısal: kolonlar, hedef, ON DELETE/UPDATE), indeksler (kolonlar, tarama, boyut), trigger'lar
 *  • İlişkiler: giden FK'ler, bu tabloya bakanlar, çıkarım (*_id) ilişkileri — tablo adları başka detayı açar (onOpenTable)
 * Veri /admin/sql/tables/{name}/details ucundan (Postgres katalogu); eski (dar) yanıtla geriye uyumlu.
 */
export default function TableDetailsModal({ table, details, loading, onClose, onOpenTable, onUseQuery }) {
  const t = useT()
  const [tab, setTab] = useState('overview')
  const columns     = details?.columns ?? []
  const constraints = details?.constraints ?? []
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
  const ctClass = (type) => `sqlpg-td-ct sqlpg-td-ct-${String(type || '').split(' ')[0].toLowerCase()}`
  const seq = Number(stats?.seq_scan ?? 0), idx = Number(stats?.idx_scan ?? 0), scanTotal = seq + idx
  const idxShare = scanTotal > 0 ? Math.round((idx / scanTotal) * 100) : null

  const tableLink = (name) => onOpenTable
    ? <button type="button" className="sqltd-tlink sqlpg-td-mono" onClick={() => onOpenTable(name)} title={t('sql.td.openOther', name)}>{name}</button>
    : <span className="sqlpg-td-mono">{name}</span>

  const kpi = (Icon, val, label, hint) => (
    <div className="sqltd-kpi" title={hint || ''}>
      <span className="sqltd-kpi-ico"><Icon size={14} aria-hidden="true" /></span>
      <span className="sqltd-kpi-val">{val}</span>
      <span className="sqltd-kpi-lbl">{label}</span>
    </div>
  )
  const badge = (c) => (
    <span className="sqltd-badges">
      {c.is_pk && <span className="sqlpg-td-ct sqlpg-td-ct-primary" title="PRIMARY KEY">PK</span>}
      {c.is_fk && <span className="sqlpg-td-ct sqlpg-td-ct-foreign" title="FOREIGN KEY">FK</span>}
      {c.is_unique && !c.is_pk && <span className="sqlpg-td-ct sqlpg-td-ct-unique" title="UNIQUE">UQ</span>}
      {c.is_indexed && !c.is_pk && <span className="sqlpg-td-ct sqlpg-td-ct-index" title={t('sql.td.indexed')}>IDX</span>}
      {c.is_identity && <span className="sqlpg-td-ct sqlpg-td-ct-trigger" title="IDENTITY">ID</span>}
    </span>
  )
  const hasStats = columns.some((c) => c.null_frac != null || c.n_distinct != null)
  const hasComments = columns.some((c) => c.comment)

  const footer = (
    <>
      {onUseQuery && <Button type="button" variant="secondary" onClick={() => { onUseQuery(`SELECT * FROM ${table} LIMIT 100`); onClose?.() }}><Play size={14} /> {t('sql.td.useQuery')}</Button>}
      <Button type="button" variant="secondary" onClick={() => { try { navigator.clipboard?.writeText(table) } catch { /* jsdom */ } }}><Copy size={14} /> {t('sql.td.copyName')}</Button>
      <Button type="button" onClick={onClose}>{t('sql.closeRowDetails')}</Button>
    </>
  )

  return (
    <ModalShell open onClose={onClose} title={t('sql.td.title', table)} icon={Table} size="xl" scrollBody footer={footer} className="sqlpg-tdmodal">
      {loading ? (
        <LoadingBlock label={t('sql.td.loading')} className="sqlpg-td-loading" size={18} />
      ) : !details ? (
        <StatusBlock tone="danger" title={t('sql.td.loadError')} />
      ) : (
        <div className="sqltd" data-testid="table-details">
          {/* KPI şeridi */}
          <div className="sqltd-kpis">
            {kpi(Rows3, num(act.live_rows), t('sql.meta.liveRows'))}
            {kpi(HardDrive, act.size_total || '—', t('sql.meta.size'), act.size_data ? `data ${act.size_data}` : '')}
            {kpi(Database, columns.length, t('sql.td.columns'))}
            {kpi(Hash, indexes.length, t('sql.td.indexesShort'))}
            {kpi(Fingerprint, constraints.length, t('sql.td.constraintsShort'))}
            {kpi(Zap, triggers.length, t('sql.td.triggers'))}
            {kpi(Activity, act.last_change_at ? fmt(act.last_change_at) : '—', t('sql.meta.lastChange'), t('sql.meta.lastChangeHint'))}
          </div>

          <SegmentedControl className="sqltd-tabs" ariaLabel={t('sql.td.sections')} value={tab} onChange={setTab} options={[
            { value: 'overview', label: t('sql.td.tabOverview') },
            { value: 'columns', label: `${t('sql.td.columns')} (${columns.length})` },
            { value: 'integrity', label: `${t('sql.td.tabIntegrity')} (${constraints.length + indexes.length + triggers.length})` },
            { value: 'relations', label: `${t('sql.td.tabRelations')} (${fks.length + refBy.length + inferred.length})` },
          ]} />

          {tab === 'overview' && (
            <div className="sqltd-grid">
              <section className="sqltd-card">
                <h4><Key size={14} /> {t('sql.td.identity')}</h4>
                <dl className="sqlpg-td-dl">
                  <dt>{t('sql.td.tableName')}</dt><dd className="sqlpg-td-mono">{table}</dd>
                  <dt>{t('sql.td.primaryKey')}</dt><dd>{pkCols.length ? <code>{pkCols.join(', ')}</code> : <span className="sqlpg-td-muted">{t('sql.td.none')}</span>}</dd>
                  <dt>{t('sql.meta.created')}</dt>
                  <dd>
                    {act.first_seen_at ? <>{act.first_seen_approx ? '≈ ' : ''}{fmt(act.first_seen_at)}</> : t('sql.meta.unknown')}
                    {act.first_seen_approx && <span className="sqlpg-td-muted"> · {t('sql.meta.createdApprox')}</span>}
                    {act.first_seen_version && <span className="sqlpg-td-muted"> · v{act.first_seen_version}</span>}
                  </dd>
                  <dt>{t('sql.td.comment')}</dt><dd>{details.comment || <span className="sqlpg-td-muted">—</span>}</dd>
                  <dt>{t('sql.td.fkOut')}</dt><dd>{fks.length ? fks.map((c) => <span key={c.name} className="sqltd-inline">{tableLink(c.ref_table)}</span>) : <span className="sqlpg-td-muted">{t('sql.td.none')}</span>}</dd>
                  <dt>{t('sql.td.fkIn')}</dt><dd>{refBy.length ? refBy.map((r) => <span key={r.name} className="sqltd-inline">{tableLink(r.from_table)}</span>) : <span className="sqlpg-td-muted">{t('sql.td.none')}</span>}</dd>
                </dl>
              </section>

              <section className="sqltd-card">
                <h4><Activity size={14} /> {t('sql.td.usage')}</h4>
                <dl className="sqlpg-td-dl">
                  <dt>{t('sql.meta.liveRows')}</dt><dd>{num(act.live_rows)}{stats?.dead_rows != null && <span className="sqlpg-td-muted"> · {t('sql.td.deadRows', num(stats.dead_rows))}</span>}</dd>
                  <dt>{t('sql.meta.size')}</dt><dd>{act.size_total ? <>{act.size_total}{act.size_data && act.size_data !== act.size_total ? <span className="sqlpg-td-muted"> (data {act.size_data})</span> : null}</> : '—'}</dd>
                  <dt>{t('sql.meta.counters')}</dt>
                  <dd>{act.tup_ins != null ? `${num(act.tup_ins)} / ${num(act.tup_upd ?? 0)} / ${num(act.tup_del ?? 0)}` : '—'}</dd>
                  <dt>{t('sql.td.scans')}</dt>
                  <dd>
                    {stats ? (
                      <span className="sqltd-scan">
                        <ProgressBar value={idxShare ?? 0} max={100} size="sm" label={t('sql.td.scanHint', num(seq), num(idx))} className="sqltd-scan-bar" />
                        <span className="sqlpg-td-muted">{idxShare == null ? t('sql.td.noScans') : t('sql.td.scanShare', idxShare, num(seq), num(idx))}</span>
                      </span>
                    ) : '—'}
                  </dd>
                  <dt>{t('sql.meta.lastAnalyze')}</dt>
                  <dd>{fmt(act.last_maintenance_at)}{stats?.mod_since_analyze != null && Number(stats.mod_since_analyze) > 0 && <span className="sqlpg-td-muted"> · {t('sql.td.modSince', num(stats.mod_since_analyze))}</span>}</dd>
                </dl>
              </section>

              {hasAct && (
                <section className="sqltd-card sqltd-card--wide">
                  <h4><Database size={14} /> {t('sql.td.activity')}</h4>
                  <dl className="sqlpg-td-dl">
                    <dt>{t('sql.meta.lastChange')}</dt>
                    <dd>{fmt(act.last_change_at)} <span className="sqlpg-td-muted">· {t('sql.meta.lastChangeHint')}</span></dd>
                    <dt>{t('sql.meta.lastDataMax')}</dt>
                    <dd>{fmt(act.last_record_at)}{act.last_record_column && <span className="sqlpg-td-muted"> · <code>{act.last_record_column}</code></span>}</dd>
                    {stats && (<>
                      <dt>{t('sql.td.vacuum')}</dt>
                      <dd>{fmt(stats.last_autovacuum || stats.last_vacuum)} <span className="sqlpg-td-muted">· {t('sql.td.analyze')}: {fmt(stats.last_autoanalyze || stats.last_analyze)}</span></dd>
                    </>)}
                  </dl>
                </section>
              )}
            </div>
          )}

          {tab === 'columns' && (
            <div className="sqlpg-td-table-wrap">
              <table className="sqlpg-td-table sqltd-cols">
                <thead>
                  <tr>
                    <th>{t('sql.td.colName')}</th>
                    <th>{t('sql.td.colKeys')}</th>
                    <th>{t('sql.td.colType')}</th>
                    <th>{t('sql.td.colBounds')}</th>
                    <th>{t('sql.td.colNull')}</th>
                    <th>{t('sql.td.colDefault')}</th>
                    {hasStats && <th title={t('sql.td.colStatsHint')}>{t('sql.td.colStats')}</th>}
                    {hasComments && <th>{t('sql.td.comment')}</th>}
                  </tr>
                </thead>
                <tbody>
                  {columns.map((c) => (
                    <tr key={c.column_name} className={c.is_pk ? 'is-pk' : ''}>
                      <td className="sqlpg-td-mono">{c.column_name}</td>
                      <td>{badge(c)}</td>
                      <td className="sqlpg-td-mono sqlpg-td-type">{c.udt_name || c.data_type}</td>
                      <td className="sqlpg-td-bounds">{c.bounds || '—'}</td>
                      <td>
                        {c.is_nullable === 'NO'
                          ? <span className="sqlpg-td-badge sqlpg-td-nn">NOT NULL</span>
                          : <span className="sqlpg-td-muted">NULL</span>}
                      </td>
                      <td className="sqlpg-td-mono sqlpg-td-default" title={c.column_default || ''}>{c.column_default ?? '—'}</td>
                      {hasStats && (
                        <td className="sqltd-stat">
                          {c.null_frac != null || c.n_distinct != null
                            ? <>{t('sql.td.nullPct', pct(c.null_frac))} · {t('sql.td.distinct', c.n_distinct == null ? '—' : Number(c.n_distinct) < 0 ? `${Math.round(-Number(c.n_distinct) * 100)}%` : num(c.n_distinct))}{c.avg_width != null ? ` · ${c.avg_width} B` : ''}</>
                            : <span className="sqlpg-td-muted">—</span>}
                        </td>
                      )}
                      {hasComments && <td className="sqltd-comment-cell">{c.comment || <span className="sqlpg-td-muted">—</span>}</td>}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {tab === 'integrity' && (
            <div className="sqltd-stack">
              <section className="sqlpg-td-sec">
                <h4>{t('sql.td.integrity')} <span className="sqlpg-td-count">{constraints.length}</span></h4>
                {constraints.length === 0 ? <div className="sqlpg-td-empty">{t('sql.td.none')}</div> : (
                  <div className="sqlpg-td-table-wrap">
                    <table className="sqlpg-td-table">
                      <thead><tr><th>{t('sql.td.ctType')}</th><th>{t('sql.td.ctName')}</th><th>{t('sql.td.columns')}</th><th>{t('sql.td.ctTarget')}</th><th>{t('sql.td.ctDef')}</th></tr></thead>
                      <tbody>{constraints.map((c) => (
                        <tr key={c.name}>
                          <td><span className={ctClass(c.type)}>{c.type}</span></td>
                          <td className="sqlpg-td-mono">{c.name}</td>
                          <td className="sqlpg-td-mono">{(c.columns || []).join(', ') || '—'}</td>
                          <td>{c.ref_table ? <>{tableLink(c.ref_table)}<span className="sqlpg-td-muted"> ({(c.ref_columns || []).join(', ')})</span>{c.on_delete && c.on_delete !== 'NO ACTION' && <span className="sqlpg-td-muted"> · ON DELETE {c.on_delete}</span>}</> : <span className="sqlpg-td-muted">—</span>}</td>
                          <td><code className="sqlpg-td-cdef">{c.definition}</code></td>
                        </tr>
                      ))}</tbody>
                    </table>
                  </div>
                )}
              </section>

              <section className="sqlpg-td-sec">
                <h4>{t('sql.td.indexes')} <span className="sqlpg-td-count">{indexes.length}</span></h4>
                {indexes.length === 0 ? <div className="sqlpg-td-empty">{t('sql.td.none')}</div> : (
                  <div className="sqlpg-td-table-wrap">
                    <table className="sqlpg-td-table">
                      <thead><tr><th>{t('sql.td.ctType')}</th><th>{t('sql.td.ctName')}</th><th>{t('sql.td.columns')}</th><th>{t('sql.td.ixScans')}</th><th>{t('sql.meta.size')}</th><th>{t('sql.td.ctDef')}</th></tr></thead>
                      <tbody>{indexes.map((ix) => (
                        <tr key={ix.name} className={ix.scans != null && Number(ix.scans) === 0 && !ix.is_primary && !ix.is_unique ? 'is-unused' : ''}>
                          <td><span className={`sqlpg-td-ct ${ix.is_primary ? 'sqlpg-td-ct-primary' : ix.is_unique ? 'sqlpg-td-ct-unique' : 'sqlpg-td-ct-index'}`}>{ix.is_primary ? 'PRIMARY' : ix.is_unique ? 'UNIQUE' : 'INDEX'}</span></td>
                          <td className="sqlpg-td-mono">{ix.name}</td>
                          <td className="sqlpg-td-mono">{(ix.columns || []).join(', ') || '—'}</td>
                          <td className="sqltd-nowrap">{ix.scans != null ? <>{num(ix.scans)}{Number(ix.scans) === 0 && !ix.is_primary && !ix.is_unique && <span className="sqlpg-td-badge sqlpg-td-nn" title={t('sql.td.unusedHint')}> {t('sql.td.unused')}</span>}</> : '—'}</td>
                          <td className="sqltd-nowrap">{ix.size || '—'}</td>
                          <td><code className="sqlpg-td-cdef">{ix.definition}</code></td>
                        </tr>
                      ))}</tbody>
                    </table>
                  </div>
                )}
              </section>

              <section className="sqlpg-td-sec">
                <h4>{t('sql.td.triggers')} <span className="sqlpg-td-count">{triggers.length}</span></h4>
                {triggers.length === 0 ? <div className="sqlpg-td-empty">{t('sql.td.none')}</div> : (
                  <ul className="sqlpg-td-list">
                    {triggers.map((tg, i) => (
                      <li key={i}><span className="sqlpg-td-ct sqlpg-td-ct-trigger">{tg.timing} {tg.event}</span><span className="sqlpg-td-cname">{tg.name}</span></li>
                    ))}
                  </ul>
                )}
              </section>
            </div>
          )}

          {tab === 'relations' && (
            <div className="sqltd-stack">
              <section className="sqlpg-td-sec">
                <h4><ArrowRight size={14} /> {t('sql.td.fkOut')} <span className="sqlpg-td-count">{fks.length}</span></h4>
                {fks.length === 0 ? <div className="sqlpg-td-empty">{t('sql.td.none')}</div> : (
                  <ul className="sqltd-rel-list">
                    {fks.map((c) => (
                      <li key={c.name}>
                        <span className="sqlpg-td-mono">{table}.{(c.columns || []).join(', ')}</span>
                        <ArrowRight size={12} aria-hidden="true" />
                        {tableLink(c.ref_table)}<span className="sqlpg-td-muted">.{(c.ref_columns || []).join(', ')}</span>
                        {c.on_delete && <span className="sqlpg-td-ct sqlpg-td-ct-check">ON DELETE {c.on_delete}</span>}
                        <span className="sqlpg-td-muted sqltd-rel-name">{c.name}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
              <section className="sqlpg-td-sec">
                <h4><ArrowLeft size={14} /> {t('sql.td.fkIn')} <span className="sqlpg-td-count">{refBy.length}</span></h4>
                {refBy.length === 0 ? <div className="sqlpg-td-empty">{t('sql.td.none')}</div> : (
                  <ul className="sqltd-rel-list">
                    {refBy.map((r) => (
                      <li key={r.name}>
                        {tableLink(r.from_table)}<span className="sqlpg-td-muted">.{(r.from_columns || []).join(', ')}</span>
                        <ArrowRight size={12} aria-hidden="true" />
                        <span className="sqlpg-td-mono">{table}</span>
                        {r.on_delete && <span className="sqlpg-td-ct sqlpg-td-ct-check">ON DELETE {r.on_delete}</span>}
                        <span className="sqlpg-td-muted sqltd-rel-name">{r.name}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
              <section className="sqlpg-td-sec">
                <h4><GitBranch size={14} /> {t('sql.td.inferred')} <span className="sqlpg-td-count">{inferred.length}</span></h4>
                <p className="field-hint">{t('sql.td.inferredHint')}</p>
                {inferred.length === 0 ? <div className="sqlpg-td-empty">{t('sql.td.none')}</div> : (
                  <ul className="sqltd-rel-list sqltd-rel-list--inferred">
                    {inferred.map((e, i) => (
                      <li key={i}>
                        {e.from === table ? <span className="sqlpg-td-mono">{table}</span> : tableLink(e.from)}<span className="sqlpg-td-muted">.{e.column}</span>
                        <ArrowRight size={12} aria-hidden="true" />
                        {e.to === table ? <span className="sqlpg-td-mono">{table}</span> : tableLink(e.to)}
                      </li>
                    ))}
                  </ul>
                )}
              </section>
              <p className="field-hint"><Link2 size={12} /> {t('sql.td.relHint')}</p>
            </div>
          )}
        </div>
      )}
    </ModalShell>
  )
}
