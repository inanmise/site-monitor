import { useState } from 'react'
import { FileCode, Network, Table2 } from 'lucide-react'
import { api, formatDateSec } from '../../../api/client'
import { Button } from '@/components/shadcn/button'
import { usePermissions } from '../../../contexts/PermissionsProvider.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import CopyButton from '../../ui/CopyButton.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import ToneBadge from '../ToneBadge.jsx'
import TableDetailsModal from '../TableDetailsModal.jsx'
import { highlightSql } from '../sql/sqlHighlight.jsx'
import { relTime } from '../health/healthModel.js'
import { cn } from '@/lib/utils'
import { ErrorBadges } from './dbColumns.jsx'
import { Stat } from './DbParts.jsx'
import { deadTone, fmtBytes, msTone, needsVacuum, num, pct, seqHeavy } from './dbModel.js'

const MS_TEXT = { danger: 'text-destructive', warning: 'text-amber-700 dark:text-amber-300' }
const GRID = 'grid grid-cols-1 gap-2 min-[420px]:grid-cols-2 sm:grid-cols-3'

/**
 * SQL ayrıntısı: kaynak + sonuç + hata sınıfı rozetleri, hata iletisi (tam), TAM SQL (sözdizimi boyalı, kayıpsız —
 * `textContent` metnin kendisi), kopyala (dokunmatikte 40 px), ölçüler. Sunucu SQL'i 240 karakterde keser → not.
 */
export function DbSqlDetail({ t, row, kind, pgss, sqlMasked = false, onClose }) {
  const r = row
  const fromPgss = pgss && (kind === 'top' || kind === 'slowest')
  const unknown = t('dba.unknown')
  const fields = [
    ['calls', t('db.colCalls'), num(r.calls)],
    ['avg_ms', t('db.colAvgMs'), <span key="a" className={MS_TEXT[msTone(r.avg_ms)]}>{num(r.avg_ms)}</span>],
    ['max_ms', t('db.colMaxMs'), <span key="m" className={MS_TEXT[msTone(r.max_ms)]}>{num(r.max_ms)}</span>],
    ['total_ms', t('db.colTotalMs'), num(r.total_ms)],
    ['share_pct', t('dba.colShare'), pct(r.share_pct)],
    ['hit_pct', t('dba.colHit'), pct(r.hit_pct)],
    ['duration_ms', t('db.colDurMs'), <span key="d" className={MS_TEXT[msTone(r.duration_ms)]}>{num(r.duration_ms)}</span>],
    ['rows', t('health.dbRows'), num(r.rows)],
    ['username', t('uact.colUser'), r.username],
    ['time', t('uact.colTime'), r.time ? formatDateSec(r.time) : null],
    ['last', t('db.colLastRun'), r.last ? formatDateSec(r.last) : null],
  ].filter(([k]) => r[k] != null && r[k] !== '')
  return (
    <ModalShell open onClose={onClose} title={t('db.sqlDetail')} icon={FileCode} size="lg" scrollBody closeLabel={t('app.dismiss')}>
      <div className="flex min-w-0 flex-col gap-3">
        <div className="flex flex-wrap items-center gap-1.5">
          <ToneBadge tone={fromPgss ? 'info' : 'muted'}>{fromPgss ? t('db.srcPgss') : t('db.srcPlayground')}</ToneBadge>
          {r.success != null && (
            <ToneBadge tone={r.success === false ? 'danger' : 'success'}>{r.success === false ? t('uact.failed') : t('uact.success')}</ToneBadge>
          )}
          {(r.error || r.error_kind) && <ErrorBadges t={t} msg={r.error} kind={r.error_kind} code={r.sql_state} />}
        </div>
        {r.error && <AlertBanner tone="danger" title={t('db.colError')} className="mb-0">{r.error}</AlertBanner>}
        <div className="relative min-w-0">
          <pre data-testid="db-sql-full"
            className="m-0 max-h-[45dvh] overflow-auto rounded-lg border border-border bg-muted/60 py-3 pr-14 pl-3 font-mono text-xs leading-relaxed break-words whitespace-pre-wrap">
            {r.sql ? highlightSql(r.sql) : sqlMasked && !('sql' in r) ? t('dba.sqlMasked') : '—'}
          </pre>
          {r.sql && <CopyButton value={r.sql} label={t('db.copySql')} copiedLabel={t('db.copied')} buttonSize="icon-lg" className="absolute top-1.5 right-1.5" />}
        </div>
        {String(r.sql || '').endsWith('…') && <p className="m-0 text-xs text-muted-foreground">{t('db.sqlPreviewNote')}</p>}
        {fromPgss && <p className="m-0 text-xs text-muted-foreground">{t('dba.pgssCumulative')}</p>}
        {fields.length > 0 && (
          <div className={GRID} data-testid="db-sql-metrics">
            {fields.map(([k, label, value]) => <Stat key={k} label={label} value={value} unknownLabel={unknown}
              mono={k === 'username' || k === 'time' || k === 'last'} />)}
          </div>
        )}
      </div>
    </ModalShell>
  )
}

/** Boyut dağılımı çubuğu: tablo verisi / indeksler / TOAST+diğer (metinli gösterge; renk tek başına değil). */
function SizeBreakdown({ t, r }) {
  const parts = [
    { key: 'table', label: t('dba.partTable'), bytes: Number(r.table_size_bytes), text: r.table_size, dot: 'bg-chart-1' },
    { key: 'index', label: t('dba.partIndex'), bytes: Number(r.index_size_bytes), text: r.index_size, dot: 'bg-chart-5' },
    { key: 'other', label: t('dba.partToast'), bytes: Number(r.other_size_bytes), text: null, dot: 'bg-muted-foreground/40' },
  ].filter((p) => Number.isFinite(p.bytes) && p.bytes > 0)
  if (!parts.length) return null
  return (
    <div className="flex flex-col gap-2" data-testid="db-size-breakdown">
      <span className="text-xs font-semibold text-muted-foreground">{t('dba.sizeBreakdown')}</span>
      <div aria-hidden="true" className="flex h-3 w-full gap-0.5 overflow-hidden rounded-full">
        {parts.map((p) => <span key={p.key} className={cn('h-full min-w-1 first:rounded-l-full last:rounded-r-full', p.dot)} style={{ flexGrow: p.bytes, flexBasis: 0 }} />)}
      </div>
      <ul className="m-0 flex list-none flex-wrap gap-x-4 gap-y-1 p-0 text-xs">
        {parts.map((p) => (
          <li key={p.key} className="flex items-center gap-1.5">
            <span aria-hidden="true" className={cn('size-2.5 rounded-full', p.dot)} />
            <span className="text-muted-foreground">{p.label}</span>
            <span className="font-semibold tabular-nums">{p.text || fmtBytes(p.bytes)}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/**
 * Tablo ayrıntısı: boyut dağılımı, satır/ölü satır, tarama dağılımı (sıralı ↔ indeks), okuma/yazma, son
 * vacuum/analyze (göreli + tam), bakım önerileri. SQL Playground yetkisi olan için "Şema ayrıntısı" mevcut
 * TableDetailsModal'ı açar (kolonlar/kısıtlar/ilişkiler — ayrı uç, `sql_playground.execute`).
 */
export function DbTableDetail({ t, row, onClose }) {
  const r = row
  const { canExecute } = usePermissions()
  const canSchema = canExecute('sql_playground.execute')
  const [schema, setSchema] = useState(null)   // { table, details, loading }
  const unknown = t('dba.unknown')
  const when = (iso) => (iso ? `${relTime(iso, t)} · ${formatDateSec(iso)}` : null)
  const known = (k) => k in r
  const dTone = deadTone(r)
  const advice = []
  if (needsVacuum(r)) advice.push(<AlertBanner key="vac" tone="warning" className="mb-0">{t('dba.adviceVacuum', pct(r.dead_pct))}</AlertBanner>)
  if (seqHeavy(r)) advice.push(<AlertBanner key="seq" tone="info" className="mb-0">{t('dba.adviceSeq', pct(r.idx_scan_pct))}</AlertBanner>)

  async function openSchema(name) {
    setSchema({ table: name, details: null, loading: true })
    try {
      const res = await api.admin.sqlTableDetails(name)
      setSchema((cur) => (cur?.table === name ? { table: name, details: res?.success ? res.data : null, loading: false } : cur))
    } catch {
      setSchema((cur) => (cur?.table === name ? { table: name, details: null, loading: false } : cur))
    }
  }

  const footer = (
    <div className="flex w-full flex-wrap items-center justify-end gap-2 [&>*]:flex-1 sm:[&>*]:flex-none">
      <span className="hidden sm:mr-auto sm:inline-flex sm:flex-none">
        <CopyButton value={r.table_name} label={t('sql.td.copyName')} copiedLabel={t('db.copied')} buttonSize="icon-lg" />
      </span>
      {canSchema && (
        <Button type="button" variant="outline" className="h-10 lg:h-9 pointer-coarse:h-10" onClick={() => openSchema(r.table_name)}>
          <Network aria-hidden="true" />{t('dba.openSchema')}
        </Button>
      )}
      <Button type="button" className="h-10 lg:h-9 pointer-coarse:h-10" onClick={onClose}>{t('app.dismiss')}</Button>
    </div>
  )

  return (
    <>
      <ModalShell open onClose={onClose} title={t('dba.tableTitle', r.table_name)} icon={Table2} size="lg" scrollBody footer={footer}
        closeLabel={t('app.dismiss')}>
        <div className="flex min-w-0 flex-col gap-4" data-testid="db-table-detail">
          <div className={GRID}>
            <Stat label={t('health.dbTotalSize')} value={r.total_size} unknownLabel={unknown} />
            <Stat label={t('health.dbRows')} value={r.row_count != null ? num(r.row_count) : null} unknownLabel={unknown} />
            <Stat label={t('dba.colDeadPct')} unknownLabel={unknown}
              value={r.dead_pct != null ? pct(r.dead_pct) : known('dead_rows') && r.dead_rows != null ? '—' : null}
              sub={r.dead_rows != null ? t('dba.deadRowsN', num(r.dead_rows)) : null}
              aside={r.dead_pct != null ? <ToneBadge tone={dTone}>{t(dTone === 'success' ? 'dba.lvlGood' : dTone === 'warning' ? 'dba.lvlWatch' : 'dba.lvlPoor')}</ToneBadge> : null} />
          </div>
          <SizeBreakdown t={t} r={r} />
          <section className="flex flex-col gap-2">
            <h5 className="m-0 text-xs font-semibold text-muted-foreground">{t('dba.activity')}</h5>
            <div className={GRID}>
              <Stat label={t('dba.colSeq')} value={known('seq_scan') ? num(r.seq_scan) : null} unknownLabel={unknown} />
              <Stat label={t('dba.colIdx')} value={known('idx_scan') ? num(r.idx_scan) : null} unknownLabel={unknown}
                sub={r.idx_scan_pct != null ? t('dba.idxShare', pct(r.idx_scan_pct)) : null} />
              <Stat label={t('db.colReads')} value={r.reads != null ? num(r.reads) : null} unknownLabel={unknown} />
              <Stat label={t('db.colWrites')} value={r.writes != null ? num(r.writes) : null} unknownLabel={unknown} />
              <Stat label={t('dba.modSinceAnalyze')} value={r.mod_since_analyze != null ? num(r.mod_since_analyze) : null} unknownLabel={unknown} />
            </div>
          </section>
          <section className="flex flex-col gap-2">
            <h5 className="m-0 text-xs font-semibold text-muted-foreground">{t('dba.maintenance')}</h5>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <Stat label={t('dba.colVacuum')} unknownLabel={unknown} value={known('last_vacuum') ? (when(r.last_vacuum) || t('dba.never')) : null} />
              <Stat label={t('dba.colAnalyze')} unknownLabel={unknown} value={known('last_analyze') ? (when(r.last_analyze) || t('dba.never')) : null} />
            </div>
            {advice.length ? advice : known('dead_rows') && <p className="m-0 text-xs text-muted-foreground">{t('dba.adviceNone')}</p>}
          </section>
          <p className="m-0 text-xs text-muted-foreground">{t('dba.statsNote')}</p>
        </div>
      </ModalShell>
      {schema && (
        <TableDetailsModal table={schema.table} details={schema.details} loading={schema.loading}
          onClose={() => setSchema(null)} onOpenTable={openSchema} />
      )}
    </>
  )
}
