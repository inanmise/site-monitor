import { Lock, Maximize2 } from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { formatDateSec } from '../../../api/client'
import CopyButton from '../../ui/CopyButton.jsx'
import { ProgressBar } from '../../ui/Progress.jsx'
import SimpleTooltip from '../../ui/SimpleTooltip.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { relTime } from '../health/healthModel.js'
import { cn } from '@/lib/utils'
import { deadTone, errorKind, msTone, num, pct, seqHeavy, snippet, sqlState } from './dbModel.js'

/**
 * Dört listenin (Tablolar · Sorgular [en çok / en yavaş / son] · Başarısız · Kullanıcılar) sütun tanımları ve
 * telefon/tablet KART çizimleri — aynı alanlar, aynı eylemler. Satır eylemlerinin erişilebilir adı satırı taşır
 * (`a11y.rowAction`: "Ayrıntı — SELECT …"). Dokunma hedefleri kartlarda 40 px.
 */

const MS_TEXT = { danger: 'font-semibold text-destructive', warning: 'text-amber-700 dark:text-amber-300' }
const msText = (v) => MS_TEXT[msTone(v)]
const TOUCH_ICON = 'max-lg:size-10 pointer-coarse:size-10'
const CARD = 'relative flex min-w-0 flex-col gap-2 rounded-xl border-border px-3.5 py-3 shadow-xs'

/** Kart içi küçük ölçü çipi: "etiket değer". */
function Chip({ label, value, className }) {
  return (
    <span className={cn('inline-flex max-w-full items-baseline gap-1 rounded-md bg-muted/60 px-1.5 py-0.5 text-xs', className)}>
      <span className="text-muted-foreground">{label}</span>
      <span className="font-semibold tabular-nums">{value}</span>
    </span>
  )
}

/**
 * Hata sınıfı rozeti (+ SQLSTATE) — metin uydurmaz, iletiden sınıflar. İleti bu görüntüleyiciye gelmediyse (SQL metni
 * maskesi, 2026-09-28c) sunucunun gönderdiği `error_kind` / `sql_state` kullanılır — sınıf herkese görünür kalır.
 */
export function ErrorBadges({ t, msg, kind: kindIn, code: codeIn }) {
  const kind = kindIn ?? errorKind(msg)
  const code = codeIn ?? sqlState(msg)
  if (!kind && !code) return null
  return (
    <span className="inline-flex flex-wrap items-center gap-1">
      {kind && <ToneBadge tone="danger" data-err-kind={kind} className="font-semibold">{t(`dba.err.${kind}`)}</ToneBadge>}
      {code && <Badge variant="outline" className="font-mono text-[11px] text-muted-foreground">{t('dba.sqlState', code)}</Badge>}
    </span>
  )
}

/** Metin sunucuda düşürüldü (SQL metni / hata / kullanıcı adı yalnız global yönetici + denetçiye) — boş değil, açık durum. */
export function HiddenText({ label }) {
  return (
    <span data-slot="db-masked" className="inline-flex items-center gap-1 text-xs text-muted-foreground">
      <Lock className="size-3 shrink-0" aria-hidden="true" />{label}
    </span>
  )
}

/** Kısa SQL + kopyala (tablo hücresi ve kart). `hidden`: metin bu görüntüleyiciye gelmedi → durum metni, kopyala YOK. */
function SqlText({ t, sql, lines = 2, copy = true, hidden = false }) {
  if (hidden) return <HiddenText label={t('dba.sqlMasked')} />
  return (
    <div className="flex min-w-0 items-start gap-1">
      <code className={cn('min-w-0 flex-1 font-mono text-xs leading-relaxed break-all', lines === 3 ? 'line-clamp-3' : 'line-clamp-2')}
        title={sql || ''}>{sql || '—'}</code>
      {copy && (
        <CopyButton value={sql || ''} variant="ghost" label={t('a11y.rowAction', t('db.copySql'), snippet(sql))} copiedLabel={t('db.copied')}
          className={cn('-my-1 shrink-0', TOUCH_ICON)} />
      )}
    </div>
  )
}

/** Tam tarih (UTC ISO → yerel, saniyeli) ya da '—'. */
const when = (iso) => (iso ? formatDateSec(iso) : '—')

export function buildColumns({ t, pgss, maxBytes, openSql, openTable, sqlMasked = false }) {
  // ── SQL metni maskesi (2026-09-28c): alan yoksa "yalnız yöneticiler" durumu; ayrıntı / kopyala düğmeleri çizilmez ──
  const gone = (r, key) => sqlMasked && !(key in (r || {}))
  const sqlGone = (r) => gone(r, 'sql')
  const userText = (r) => (gone(r, 'username') ? <HiddenText label={t('uact.idMasked')} /> : (r.username || '—'))
  const errText = (r) => (gone(r, 'error') ? <HiddenText label={t('dba.errorMasked')} /> : (r.error || '—'))
  // ── Ortak sütunlar ──
  const openBtn = (kind, r) => (
    <SimpleTooltip content={t('db.openSql')}>
      <Button type="button" variant="ghost" size="icon" className={TOUCH_ICON}
        aria-label={t('a11y.rowAction', t('db.openSql'), snippet(r.sql))} onClick={() => openSql(r, kind)}>
        <Maximize2 aria-hidden="true" />
      </Button>
    </SimpleTooltip>
  )
  const openCol = (kind) => ({ key: '_open', label: <span className="sr-only">{t('db.openSql')}</span>, sortable: false, headClass: 'w-11', render: (r) => openBtn(kind, r) })
  // Metin yoksa ayrıntı penceresinin gösterecek bir şeyi yok → sütun hiç yok.
  const openCols = (kind) => (sqlMasked ? [] : [openCol(kind)])
  const sqlCol = { key: 'sql', label: 'SQL', sortable: false, cellClass: 'min-w-[220px] max-w-[560px]', render: (r) => <SqlText t={t} sql={r.sql} hidden={sqlGone(r)} /> }
  const timeCol = (key = 'time', hide) => ({
    key, label: key === 'last' ? t('db.colLastRun') : t('uact.colTime'), hide,
    cellClass: 'font-mono text-xs whitespace-nowrap', render: (r) => when(r[key]),
  })
  const userCol = (hide = 'hidden @3xl:table-cell') => ({ key: 'username', label: t('uact.colUser'), hide, cellClass: 'font-mono', render: userText })
  const msCol = (key, label, hide) => ({ key, label, num: true, hide, render: (r) => <span className={msText(r[key])}>{num(r[key])}</span> })
  const numCol = (key, label, hide) => ({ key, label, num: true, hide })
  const shareCol = (hide = 'hidden @5xl:table-cell') => ({
    key: 'share_pct', label: t('dba.colShare'), num: true, hide,
    render: (r) => (r.share_pct == null ? '—' : (
      <span className="inline-flex items-center justify-end gap-2">
        <span className="hidden w-12 @6xl:inline-block"><ProgressBar value={Number(r.share_pct)} max={100} size="sm" decorative /></span>
        {pct(r.share_pct)}
      </span>
    )),
  })
  const resultBadge = (r) => (
    <ToneBadge tone={r.success === false ? 'danger' : 'success'}>{r.success === false ? t('uact.failed') : t('uact.success')}</ToneBadge>
  )

  // ── Tablolar ──
  const deadCell = (r) => (r.dead_pct == null ? <span className="text-muted-foreground">—</span> : (
    <span title={t('dba.deadRowsN', num(r.dead_rows))}
      className={cn(deadTone(r) === 'danger' && 'font-semibold text-destructive', deadTone(r) === 'warning' && 'text-amber-700 dark:text-amber-300')}>
      {pct(r.dead_pct)}
    </span>
  ))
  const idxCell = (r) => (r.idx_scan_pct == null ? <span className="text-muted-foreground">—</span>
    : <span className={cn(seqHeavy(r) && 'text-amber-700 dark:text-amber-300')}>{pct(r.idx_scan_pct)}</span>)
  const vacuumText = (r) => (!('last_vacuum' in r) ? '—' : r.last_vacuum ? relTime(r.last_vacuum, t) : t('dba.never'))
  const tableName = (r) => (
    <span className="inline-flex min-w-0 flex-wrap items-center gap-1.5">
      <Button type="button" variant="link" size="xs" onClick={() => openTable(r)}
        aria-label={t('a11y.rowAction', t('dba.openTable'), r.table_name)}
        className="h-auto min-w-0 justify-start p-0 text-left font-mono text-[1em] break-all whitespace-normal">{r.table_name}</Button>
      {r.schema_name && r.schema_name !== 'public' && <Badge variant="outline" className="font-mono text-[10px] text-muted-foreground">{r.schema_name}</Badge>}
    </span>
  )
  const tables = [
    { key: 'table_name', label: t('health.dbTable'), render: tableName, cellClass: 'min-w-[160px]' },
    numCol('row_count', t('health.dbRows'), 'hidden @3xl:table-cell'),
    {
      key: 'total_size', label: t('health.dbTotalSize'), num: true, sortValue: (r) => Number(r.total_size_bytes) || 0,
      render: (r) => (
        <span className="inline-flex items-center justify-end gap-2">
          {r.total_size_bytes != null && <span className="hidden w-16 @4xl:inline-block"><ProgressBar value={Number(r.total_size_bytes) || 0} max={maxBytes} size="sm" decorative /></span>}
          <span>{r.total_size || '—'}</span>
        </span>
      ),
    },
    { key: 'table_size', label: t('health.dbTableSize'), num: true, hide: 'hidden @6xl:table-cell', sortValue: (r) => (r.table_size_bytes == null ? null : Number(r.table_size_bytes)), render: (r) => <span className="text-muted-foreground">{r.table_size || '—'}</span> },
    { key: 'index_size', label: t('dba.colIndexSize'), num: true, hide: 'hidden @4xl:table-cell', sortValue: (r) => (r.index_size_bytes == null ? null : Number(r.index_size_bytes)), render: (r) => <span className="text-muted-foreground">{r.index_size || '—'}</span> },
    { key: 'dead_pct', label: t('dba.colDeadPct'), num: true, render: deadCell },
    { key: 'idx_scan_pct', label: t('dba.colIdxPct'), num: true, render: idxCell },
    { key: 'last_vacuum', label: t('dba.colVacuum'), hide: 'hidden @3xl:table-cell', cellClass: 'text-xs whitespace-nowrap', render: vacuumText },
    numCol('reads', t('db.colReads'), 'hidden @5xl:table-cell'),
    numCol('writes', t('db.colWrites'), 'hidden @6xl:table-cell'),
  ]
  const tableCard = (r) => (
    <Card data-slot="db-card" className={CARD}>
      <div className="flex min-w-0 items-start justify-between gap-3">
        <Button type="button" variant="ghost" onClick={() => openTable(r)} aria-label={t('a11y.rowAction', t('dba.openTable'), r.table_name)}
          className="h-auto min-h-10 min-w-0 flex-1 justify-start p-0 text-left font-mono text-sm font-semibold break-all whitespace-normal hover:bg-transparent after:absolute after:inset-0 after:rounded-xl focus-visible:ring-0 focus-visible:after:ring-[3px] focus-visible:after:ring-ring/50">
          {r.table_name}
        </Button>
        <span className="shrink-0 pt-2 text-sm font-bold tabular-nums">{r.total_size || '—'}</span>
      </div>
      {r.total_size_bytes != null && <ProgressBar value={Number(r.total_size_bytes) || 0} max={maxBytes} size="sm" decorative />}
      <div className="flex flex-wrap gap-1.5">
        <Chip label={t('health.dbRows')} value={num(r.row_count)} />
        {r.dead_pct != null && <Chip label={t('dba.colDeadPct')} value={pct(r.dead_pct)}
          className={cn(deadTone(r) === 'danger' && 'bg-destructive/10', deadTone(r) === 'warning' && 'bg-amber-500/15')} />}
        {r.idx_scan_pct != null && <Chip label={t('dba.colIdxPct')} value={pct(r.idx_scan_pct)} className={cn(seqHeavy(r) && 'bg-amber-500/15')} />}
        {'last_vacuum' in r && <Chip label={t('dba.colVacuum')} value={vacuumText(r)} />}
        {r.reads != null && <Chip label={t('db.colReads')} value={num(r.reads)} />}
      </div>
    </Card>
  )

  // ── Sorgular ──
  const sqlCardFor = (kind, r) => (
    <Card data-slot="db-card" className={CARD}>
      {(r.time || r.success != null || r.username) && (
        <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
          {r.success != null && resultBadge(r)}
          {(r.time || r.last) && <span className="font-mono">{when(r.time || r.last)}</span>}
          {r.username && <span className="min-w-0 truncate font-mono">{r.username}</span>}
        </div>
      )}
      <SqlText t={t} sql={r.sql} lines={3} copy={false} hidden={sqlGone(r)} />
      <div className="flex flex-wrap gap-1.5">
        {r.calls != null && <Chip label={t('db.colCalls')} value={num(r.calls)} />}
        {r.avg_ms != null && <Chip label={t('db.colAvgMs')} value={<span className={msText(r.avg_ms)}>{num(r.avg_ms)}</span>} />}
        {r.max_ms != null && <Chip label={t('db.colMaxMs')} value={<span className={msText(r.max_ms)}>{num(r.max_ms)}</span>} />}
        {r.duration_ms != null && <Chip label={t('db.colDurMs')} value={<span className={msText(r.duration_ms)}>{num(r.duration_ms)}</span>} />}
        {r.share_pct != null && <Chip label={t('dba.colShare')} value={pct(r.share_pct)} />}
        {r.rows != null && <Chip label={t('health.dbRows')} value={num(r.rows)} />}
      </div>
      {!sqlGone(r) && (
        <div className="flex items-center justify-end gap-2">
          <CopyButton value={r.sql || ''} label={t('a11y.rowAction', t('db.copySql'), snippet(r.sql))} copiedLabel={t('db.copied')} buttonSize="icon-lg" />
          <Button type="button" variant="outline" className="h-10" aria-label={t('a11y.rowAction', t('db.openSql'), snippet(r.sql))} onClick={() => openSql(r, kind)}>
            <Maximize2 aria-hidden="true" />{t('db.openSql')}
          </Button>
        </div>
      )}
    </Card>
  )
  const sql = {
    top: pgss
      ? [sqlCol, numCol('calls', t('db.colCalls')), msCol('avg_ms', t('db.colAvgMs')), msCol('max_ms', t('db.colMaxMs'), 'hidden @4xl:table-cell'),
         numCol('total_ms', t('db.colTotalMs'), 'hidden @6xl:table-cell'), shareCol(), numCol('rows', t('health.dbRows'), 'hidden @6xl:table-cell'), ...openCols('top')]
      : [sqlCol, numCol('calls', t('db.colCalls')), msCol('avg_ms', t('db.colAvgMs')), msCol('max_ms', t('db.colMaxMs'), 'hidden @4xl:table-cell'), timeCol('last', 'hidden @5xl:table-cell'), ...openCols('top')],
    slowest: pgss
      ? [sqlCol, msCol('avg_ms', t('db.colAvgMs')), msCol('max_ms', t('db.colMaxMs')), numCol('calls', t('db.colCalls'), 'hidden @4xl:table-cell'),
         numCol('total_ms', t('db.colTotalMs'), 'hidden @6xl:table-cell'), shareCol(), ...openCols('slowest')]
      : [sqlCol, msCol('duration_ms', t('db.colDurMs')), userCol(), timeCol('time', 'hidden @4xl:table-cell'), numCol('rows', t('health.dbRows'), 'hidden @6xl:table-cell'), ...openCols('slowest')],
    recent: [timeCol(), userCol(), sqlCol, msCol('duration_ms', t('db.colDurMs')),
      { key: 'success', label: t('db.colResult'), sortValue: (r) => (r.success === false ? 0 : 1), render: resultBadge },
      ...openCols('recent')],
  }

  // ── Başarısız ──
  const failed = [timeCol('time'), userCol(), sqlCol,
    {
      key: 'error', label: t('db.colError'), sortable: false, cellClass: 'min-w-[200px] max-w-[380px]',
      render: (r) => (
        <div className="flex min-w-0 flex-col gap-1">
          <ErrorBadges t={t} msg={r.error} kind={r.error_kind} code={r.sql_state} />
          {gone(r, 'error') ? errText(r) : <span className="line-clamp-2 text-xs text-destructive" title={r.error || ''}>{r.error || '—'}</span>}
        </div>
      ),
    },
    ...openCols('failed')]
  const failedCard = (r) => (
    <Card data-slot="db-card" className={CARD}>
      <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
        <ErrorBadges t={t} msg={r.error} kind={r.error_kind} code={r.sql_state} />
        <span className="font-mono">{when(r.time)}</span>
        {r.username && <span className="min-w-0 truncate font-mono">{r.username}</span>}
      </div>
      {gone(r, 'error') ? errText(r) : <p className="m-0 line-clamp-3 text-sm break-words text-destructive">{r.error || '—'}</p>}
      <SqlText t={t} sql={r.sql} lines={2} copy={false} hidden={sqlGone(r)} />
      {!sqlGone(r) && (
        <div className="flex items-center justify-end gap-2">
          <CopyButton value={r.sql || ''} label={t('a11y.rowAction', t('db.copySql'), snippet(r.sql))} copiedLabel={t('db.copied')} buttonSize="icon-lg" />
          <Button type="button" variant="outline" className="h-10" aria-label={t('a11y.rowAction', t('db.openSql'), snippet(r.sql))} onClick={() => openSql(r, 'failed')}>
            <Maximize2 aria-hidden="true" />{t('db.openSql')}
          </Button>
        </div>
      )}
    </Card>
  )

  // ── Kullanıcılar ──
  const users = [
    { key: 'username', label: t('uact.colUser'), cellClass: 'font-mono', render: userText },
    numCol('queries', t('db.colQueries')),
    msCol('avg_ms', t('db.colAvgMs')),
    { key: 'failed', label: t('db.colFailed'), num: true, render: (r) => <span className={cn(r.failed > 0 && 'font-semibold text-destructive')}>{num(r.failed || 0)}</span> },
    timeCol('last'),
  ]
  const userCard = (r) => (
    <Card data-slot="db-card" className={CARD}>
      <div className="flex min-w-0 items-baseline justify-between gap-3">
        <span className="min-w-0 truncate font-mono text-sm font-semibold">{userText(r)}</span>
        <span className="shrink-0 text-sm font-bold tabular-nums">{t('dba.queriesN', num(r.queries))}</span>
      </div>
      <div className="flex flex-wrap gap-1.5">
        <Chip label={t('db.colAvgMs')} value={<span className={msText(r.avg_ms)}>{num(r.avg_ms)}</span>} />
        <Chip label={t('db.colFailed')} value={num(r.failed || 0)} className={cn(r.failed > 0 && 'bg-destructive/10 text-destructive')} />
        <Chip label={t('db.colLastRun')} value={when(r.last)} />
      </div>
    </Card>
  )

  const sqlCard = (kind) => (r) => sqlCardFor(kind, r)

  return { tables, tableCard, sql, sqlCard, failed, failedCard, users, userCard }
}
