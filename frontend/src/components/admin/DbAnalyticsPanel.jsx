import { useMemo, useRef, useState } from 'react'
import {
  Activity, Database, FileCode, Gauge, HardDrive, Maximize2, RefreshCw, Timer, XCircle,
} from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatDateSec } from '../../api/client'
import { usePagination } from '../../hooks/usePagination.js'
import ModalShell from '../ui/ModalShell.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import ToneBadge from './ToneBadge.jsx'
import { ToolbarSearch } from './ListToolbar.jsx'
import { TH, TH_NUM, TD, TD_NUM, DataTable, SortTh, KvField, KV_GRID } from './HealthUi.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import {
  ChartContainer, ChartTooltip, ChartTooltipContent, ChartLegend, ChartLegendContent,
  ComposedChart, Bar, Line, XAxis, YAxis, CartesianGrid,
} from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'

/**
 * Sistem Sağlığı → Veritabanı Analitiği (2026-09-26 yeniden tasarım, kullanıcı isteği).
 *
 * Veri: GET /admin/system/db-analytics?days=1|7|30 (DbAnalyticsService.getOverview — tek payload, 60 sn
 * önbellekli). Kaynaklar: sql_query_history (SQL Playground), pg_stat_statements (VARSA — DB geneli),
 * pg_stat_user_tables (tablo kullanımı/boyutları), pg_stat_activity + pg_settings (bağlantılar).
 * Eklenti yoksa `summary.pgss=false` → SQL listeleri yalnız Playground geçmişinden gelir; bu durum ekranda
 * AÇIKÇA söylenir (sessiz yanlış-negatif olmasın). `truncated` → pencere satır tavanına çarptı uyarısı.
 *
 * Yerleşim: başlık (kaynak rozeti + güncellenme + pencere + yenile) → KPI Card'ları (tıklanınca ilgili
 * sekme/ayrıntı) → sorgu yükü grafiği (ChartContainer) → Tabs: Tablolar / Sorgular / Başarısız /
 * Kullanıcılar — her liste arama + sıralanabilir başlık + PaginationBar; SQL satırı kopyala + ayrıntı
 * penceresi (ModalShell). Renkli sol şerit YOK; durum tonları Badge ile.
 */

/** Ortalama/azami sorgu süresi eşikleri (Sistem Sağlığı HEALTH_THRESHOLDS.dbMs ile aynı). */
const DB_MS = { warn: 200, crit: 500 }
const msTone = (ms) => (ms == null ? 'muted' : ms >= DB_MS.crit ? 'danger' : ms >= DB_MS.warn ? 'warning' : 'success')
const SERIES_COLORS = { count: '#3b82f6', failed: '#ef4444', avg_ms: '#f59e0b' }
const num = (v) => (v == null || v === '' ? '—' : Number(v).toLocaleString())
const snippet = (s, n = 48) => { const x = String(s || '').replace(/\s+/g, ' ').trim(); return x.length > n ? x.slice(0, n) + '…' : x }

/** null/boş en sonda; sayılar sayısal, metinler yerel sırayla karşılaştırılır. */
function compare(a, b) {
  const an = a == null || a === '', bn = b == null || b === ''
  if (an || bn) return an === bn ? 0 : an ? 1 : -1
  if (typeof a === 'number' && typeof b === 'number') return a - b
  return String(a).localeCompare(String(b), undefined, { numeric: true })
}

/** KPI kartı — tıklanır (ilgili sekme/ayrıntı) shadcn Button; ton rozeti + alt satır. */
function DbKpi({ kpiKey, icon: Icon, label, value, badge, badgeTone = 'muted', sub, valueTone, onClick, hint }) {
  const body = (
    <>
      <span className="flex w-full items-center gap-2 text-[11px] font-bold tracking-wider text-muted-foreground uppercase">
        <span className="grid size-7 shrink-0 place-items-center rounded-lg bg-primary/10 text-primary"><Icon size={15} aria-hidden="true" /></span>
        <span className="min-w-0 truncate">{label}</span>
      </span>
      <span className={cn('text-2xl leading-none font-extrabold tracking-tight tabular-nums', valueTone === 'danger' && 'text-destructive', valueTone === 'warning' && 'text-amber-700 dark:text-amber-300')}>
        {value}
      </span>
      <span className="flex w-full flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
        {badge != null && <ToneBadge tone={badgeTone} className="font-semibold">{badge}</ToneBadge>}
        {sub && <span className="min-w-0 truncate">{sub}</span>}
      </span>
    </>
  )
  const box = 'flex min-w-0 flex-col items-start gap-2.5 rounded-xl border border-border bg-card px-4 py-3.5 text-left shadow-xs'
  if (!onClick) return <Card data-kpi={kpiKey} className={box}>{body}</Card>
  return (
    <Button type="button" variant="outline" data-kpi={kpiKey} onClick={onClick} title={hint}
      className={cn(box, 'h-auto justify-start font-normal whitespace-normal transition-[border-color,box-shadow] hover:border-primary/60 hover:bg-card hover:shadow-md motion-reduce:transition-none')}>
      {body}
    </Button>
  )
}

/**
 * Aranır / sıralanır / sayfalı liste. `columns`: { key, label, num?, hide?, sortable?, sortValue?, render?,
 * cellClass?, headClass? }. Sayfalama standart usePagination + PaginationBar.
 */
function DataView({ rows, columns, searchKeys, searchLabel, listKey, initialSort, emptyText, testId, t }) {
  const [q, setQ] = useState('')
  const [sort, setSort] = useState(initialSort || null)
  const filtered = useMemo(() => {
    const n = q.trim().toLowerCase()
    if (!n) return rows
    return rows.filter((r) => searchKeys.some((k) => String(r[k] ?? '').toLowerCase().includes(n)))
  }, [rows, q, searchKeys])
  const sorted = useMemo(() => {
    if (!sort) return filtered
    const col = columns.find((c) => c.key === sort.key)
    const val = col?.sortValue || ((r) => r[sort.key])
    const dir = sort.dir === 'asc' ? 1 : -1
    return [...filtered].sort((a, b) => compare(val(a), val(b)) * dir)
  }, [filtered, sort, columns])
  // Sayfalama ön ayarı (2026-09-26 standardı): yönetim paneli alt listesi → 'panel' (25 / [25,50,100,200]); elle
  // `defaultSize: 25` + `compact` kalktı (compact yalnız 'modal' ön ayarında, kancadan gelir).
  const pager = usePagination(sorted, { listKey, preset: 'panel', resetDeps: [q, sort?.key, sort?.dir] })
  const onSort = (c) => setSort((s) => (s?.key === c.key
    ? { key: c.key, dir: s.dir === 'asc' ? 'desc' : 'asc' }
    : { key: c.key, dir: c.num ? 'desc' : 'asc' }))

  return (
    <div data-testid={testId} className="flex min-w-0 flex-col gap-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <ToolbarSearch value={q} onChange={setQ} placeholder={searchLabel} ariaLabel={searchLabel} clearLabel={t('app.clear')}
          className="w-full max-w-none sm:w-auto sm:max-w-xs" />
        <span className="text-xs text-muted-foreground tabular-nums" data-slot="dataview-count">{t('inv.shownOf', sorted.length, rows.length)}</span>
      </div>
      {rows.length === 0
        ? <StatusBlock tone="neutral" icon={Database} title={emptyText} />
        : sorted.length === 0
          ? <StatusBlock tone="neutral" icon={Database} title={t('db.noMatch')} />
          : (
            <DataTable>
              <TableHeader><TableRow>
                {columns.map((c) => (c.sortable === false
                  ? <TableHead key={c.key} className={cn(c.num ? TH_NUM : TH, c.hide, c.headClass)}>{c.label}</TableHead>
                  : <SortTh key={c.key} label={c.label} numeric={c.num} active={sort?.key === c.key} dir={sort?.dir}
                      className={cn(c.hide, c.headClass)} onSort={() => onSort(c)} />))}
              </TableRow></TableHeader>
              <TableBody>
                {pager.pageItems.map((r, i) => (
                  <TableRow key={i} data-row="">
                    {columns.map((c) => (
                      <TableCell key={c.key} className={cn(c.num ? TD_NUM : TD, c.hide, c.cellClass)}>
                        {c.render ? c.render(r) : (c.num ? num(r[c.key]) : (r[c.key] ?? '—'))}
                      </TableCell>
                    ))}
                  </TableRow>
                ))}
              </TableBody>
            </DataTable>
          )}
      {sorted.length > 0 && <PaginationBar {...pager} />}
    </div>
  )
}

/** Yükleniyor iskeleti (ilk yüklemede; tazelemede eski veri yerinde kalır). */
function DbSkeleton() {
  const sk = 'motion-reduce:animate-none'
  return (
    <div data-testid="db-skeleton" className="flex flex-col gap-3" aria-hidden="true">
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(130px,100%),1fr))] gap-3">
        {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className={cn('h-[112px] rounded-xl', sk)} />)}
      </div>
      <Skeleton className={cn('h-[260px] rounded-xl', sk)} />
      <Skeleton className={cn('h-10 w-72 max-w-full', sk)} />
      <Skeleton className={cn('h-[220px] rounded-xl', sk)} />
    </div>
  )
}

export default function DbAnalyticsPanel({ data, loading = false, error = null, days = 7, onDaysChange, onRefresh, updatedAt = null }) {
  const t = useT()
  // Uç bazı testlerde/eski sürümlerde boş dizi dönebiliyor → boş nesne gibi davran (bölüm çökmez, sıfırlar görünür).
  const d = useMemo(() => (Array.isArray(data) ? {} : (data || null)), [data])
  const sum = d?.summary || {}
  const conn = d?.connections || {}
  const pgss = !!sum.pgss
  // Etiket/kova biçimi ÇİZİLEN verinin penceresinden (sunucu `summary.days`), seçiciden değil (BF2): seçici yeni
  // pencereye geçip istek uçuştayken ekrandaki eski veri saatlik etiketle (1 g) yanlış çizilmesin.
  const dataDays = Number(sum.days) || days
  const winLbl = dataDays === 1 ? t('uact.range1d') : dataDays === 7 ? t('uact.range7d') : t('uact.range30d')
  const gran = dataDays === 1 ? 'hour' : 'day'

  const [tab, setTab] = useState('tables')
  const [sqlView, setSqlView] = useState('top')
  const [sqlSort, setSqlSort] = useState(null)        // KPI'dan gelen sıralama (ör. ortalama → süreye göre)
  const [tableSeed, setTableSeed] = useState(0)       // KPI "Boyut" → tablo listesini boyuta göre sıfırla
  const [detail, setDetail] = useState(null)          // { row, kind }
  const [connOpen, setConnOpen] = useState(false)
  const tabsRef = useRef(null)

  // ── Türetimler ──
  const tables = useMemo(() => {
    const use = new Map((d?.top_tables || []).map((r) => [r.table_name, r]))
    const rows = (d?.table_sizes || []).map((r) => ({ ...r, reads: use.get(r.table_name)?.reads ?? null, writes: use.get(r.table_name)?.writes ?? null }))
    // pg_stat_user_tables tavanı yok; yine de kullanım listesinde olup boyut listesinde olmayan tablo kaybolmasın
    for (const [name, u] of use) if (!rows.some((r) => r.table_name === name)) rows.push({ table_name: name, row_count: u.row_count, reads: u.reads, writes: u.writes })
    return rows
  }, [d])
  const maxBytes = useMemo(() => Math.max(1, ...tables.map((r) => Number(r.total_size_bytes) || 0)), [tables])
  const chartData = useMemo(() => (d?.series || []).map((b) => {
    const dt = new Date(String(b.ts) + (String(b.ts).endsWith('Z') ? '' : 'Z'))
    const p = (n) => String(n).padStart(2, '0')
    return {
      ts: b.ts, count: Number(b.count) || 0, failed: Number(b.failed) || 0, avg_ms: Number(b.avg_ms) || 0,
      label: Number.isNaN(dt.getTime()) ? String(b.ts) : gran === 'hour' ? `${p(dt.getHours())}:00` : `${p(dt.getDate())}.${p(dt.getMonth() + 1)}`,
      full: Number.isNaN(dt.getTime()) ? String(b.ts) : dt.toLocaleString([], gran === 'hour'
        ? { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' } : { weekday: 'short', day: '2-digit', month: '2-digit' }),
    }
  }), [d, gran])
  const failedRows = d?.failed || []
  const users = d?.top_users || []

  const connActive = conn.active ?? sum.active_connections ?? null
  const connMax = conn.max ?? null
  const connPct = connMax > 0 && connActive != null ? Math.round((connActive * 100) / connMax) : null
  const connTone = connPct == null ? 'muted' : connPct >= 90 ? 'danger' : connPct >= 70 ? 'warning' : 'success'
  const resp = conn.response_ms
  const respTone = resp == null ? 'muted' : resp < 0 ? 'danger' : resp >= 200 ? 'danger' : resp >= 50 ? 'warning' : 'success'
  const succ = sum.success_rate ?? 100
  const succTone = succ >= 99 ? 'success' : succ >= 95 ? 'warning' : 'danger'
  const failedN = Number(sum.failed) || 0

  function goTab(next, opts = {}) {
    setTab(next)
    if (opts.view) setSqlView(opts.view)
    setSqlSort(opts.sort || null)
    if (opts.tableReset) setTableSeed((s) => s + 1)
    try { tabsRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' }) } catch { /* jsdom */ }
  }

  // ── Sütun tanımları ──
  const sqlCol = {
    key: 'sql', label: 'SQL', sortable: false, cellClass: 'min-w-[220px] max-w-[560px]',
    render: (r) => (
      <div className="flex items-start gap-1">
        <code className="line-clamp-2 min-w-0 flex-1 font-mono text-xs leading-relaxed break-all" title={r.sql || ''}>{r.sql || '—'}</code>
        <CopyButton value={r.sql || ''} variant="ghost" label={t('a11y.rowAction', t('db.copySql'), snippet(r.sql))} copiedLabel={t('db.copied')} className="-my-1 shrink-0" />
      </div>
    ),
  }
  const openCol = (kind) => ({
    key: '_open', label: <span className="sr-only">{t('db.openSql')}</span>, sortable: false, headClass: 'w-11',
    render: (r) => (
      <SimpleTooltip content={t('db.openSql')}>
        <Button type="button" variant="ghost" size="icon" aria-label={t('a11y.rowAction', t('db.openSql'), snippet(r.sql))}
          onClick={() => setDetail({ row: r, kind })}>
          <Maximize2 aria-hidden="true" />
        </Button>
      </SimpleTooltip>
    ),
  })
  const timeCol = (key = 'time', hide = 'hidden lg:table-cell') => ({
    key, label: key === 'last' ? t('db.colLastRun') : t('uact.colTime'), hide,
    cellClass: 'font-mono text-xs whitespace-nowrap', render: (r) => (r[key] ? formatDateSec(r[key]) : '—'),
  })
  const userCol = (hide = 'hidden md:table-cell') => ({ key: 'username', label: t('uact.colUser'), hide, cellClass: 'font-mono', render: (r) => r.username || '—' })
  const msCol = (key, label, hide) => ({
    key, label, num: true, hide,
    render: (r) => <span className={cn(msTone(r[key]) === 'danger' && 'font-semibold text-destructive', msTone(r[key]) === 'warning' && 'text-amber-700 dark:text-amber-300')}>{num(r[key])}</span>,
  })

  const tableCols = [
    { key: 'table_name', label: t('health.dbTable'), cellClass: 'font-mono' },
    { key: 'row_count', label: t('health.dbRows'), num: true, hide: 'hidden sm:table-cell' },
    { key: 'table_size', label: t('health.dbTableSize'), num: true, hide: 'hidden md:table-cell', sortValue: (r) => Number(r.table_size_bytes) || 0, render: (r) => <span className="text-muted-foreground">{r.table_size || '—'}</span> },
    {
      key: 'total_size', label: t('health.dbTotalSize'), num: true, sortValue: (r) => Number(r.total_size_bytes) || 0,
      render: (r) => (
        <span className="inline-flex items-center justify-end gap-2">
          {r.total_size_bytes != null && <span className="hidden w-16 sm:inline-block"><ProgressBar value={Number(r.total_size_bytes) || 0} max={maxBytes} size="sm" decorative /></span>}
          <span>{r.total_size || '—'}</span>
        </span>
      ),
    },
    { key: 'reads', label: t('db.colReads'), num: true, hide: 'hidden lg:table-cell' },
    { key: 'writes', label: t('db.colWrites'), num: true, hide: 'hidden lg:table-cell' },
  ]
  const sqlViews = {
    top: pgss
      ? [sqlCol, { key: 'calls', label: t('db.colCalls'), num: true }, msCol('avg_ms', t('db.colAvgMs')), msCol('max_ms', t('db.colMaxMs'), 'hidden md:table-cell'),
         { key: 'total_ms', label: t('db.colTotalMs'), num: true, hide: 'hidden lg:table-cell' }, { key: 'rows', label: t('health.dbRows'), num: true, hide: 'hidden lg:table-cell' }, openCol('top')]
      : [sqlCol, { key: 'calls', label: t('db.colCalls'), num: true }, msCol('avg_ms', t('db.colAvgMs')), msCol('max_ms', t('db.colMaxMs'), 'hidden md:table-cell'), timeCol('last'), openCol('top')],
    slowest: pgss
      ? [sqlCol, msCol('avg_ms', t('db.colAvgMs')), msCol('max_ms', t('db.colMaxMs')), { key: 'calls', label: t('db.colCalls'), num: true, hide: 'hidden md:table-cell' },
         { key: 'total_ms', label: t('db.colTotalMs'), num: true, hide: 'hidden lg:table-cell' }, openCol('slowest')]
      : [sqlCol, msCol('duration_ms', t('db.colDurMs')), userCol(), timeCol(), { key: 'rows', label: t('health.dbRows'), num: true, hide: 'hidden lg:table-cell' }, openCol('slowest')],
    recent: [timeCol(), userCol(), sqlCol, msCol('duration_ms', t('db.colDurMs')),
      { key: 'success', label: t('db.colResult'), sortValue: (r) => (r.success === false ? 0 : 1),
        render: (r) => <ToneBadge tone={r.success === false ? 'danger' : 'success'}>{r.success === false ? t('uact.failed') : t('uact.success')}</ToneBadge> },
      openCol('recent')],
  }
  const sqlRows = { top: d?.top_sql || [], slowest: d?.slowest_sql || [], recent: d?.recent_queries || [] }
  const sqlDefaultSort = {
    top: { key: 'calls', dir: 'desc' },
    slowest: pgss ? { key: 'avg_ms', dir: 'desc' } : { key: 'duration_ms', dir: 'desc' },
    recent: { key: 'time', dir: 'desc' },
  }
  const failedCols = [timeCol('time', 'hidden md:table-cell'), userCol(), sqlCol,
    { key: 'error', label: t('db.colError'), sortable: false, cellClass: 'min-w-[180px] max-w-[360px]',
      render: (r) => <span className="line-clamp-2 text-xs text-destructive" title={r.error || ''}>{r.error || '—'}</span> },
    openCol('failed')]
  const userCols = [
    { key: 'username', label: t('uact.colUser'), cellClass: 'font-mono' },
    { key: 'queries', label: t('db.colQueries'), num: true },
    msCol('avg_ms', t('db.colAvgMs')),
    { key: 'failed', label: t('db.colFailed'), num: true, render: (r) => <span className={cn(r.failed > 0 && 'font-semibold text-destructive')}>{num(r.failed || 0)}</span> },
    timeCol('last', 'hidden md:table-cell'),
  ]

  const chartConfig = {
    count: { label: t('db.queriesPer'), color: SERIES_COLORS.count },
    failed: { label: t('db.kpiFailed'), color: SERIES_COLORS.failed },
    avg_ms: { label: t('db.avgMsPer'), color: SERIES_COLORS.avg_ms },
  }
  const tabCount = (n, danger) => (
    <Badge variant={danger ? 'destructive' : 'secondary'} className="ml-1 h-5 min-w-5 rounded-full px-1.5 text-[11px] tabular-nums">{n}</Badge>
  )

  return (
    <div data-testid="db-analytics" className="flex min-w-0 flex-col gap-4">
      {/* Başlık */}
      <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
        <div className="flex min-w-0 flex-col gap-1">
          <span className="text-[11px] font-semibold tracking-[.12em] text-muted-foreground uppercase">{t('health.dbTitle')}</span>
          <h3 className="text-xl leading-tight font-semibold tracking-tight">{t('db.heroTitle')}</h3>
          <p className="text-sm text-muted-foreground">{t('db.subtitle')}</p>
          {d && (
            <div className="mt-1 flex flex-wrap items-center gap-1.5">
              <ToneBadge tone={pgss ? 'success' : 'warning'} data-testid="db-source" data-pgss={pgss ? 'true' : 'false'}>
                {pgss ? t('db.srcBadgePgss') : t('db.srcBadgeHistory')}
              </ToneBadge>
              {updatedAt && (
                <Badge variant="outline" className="font-normal text-muted-foreground tabular-nums">
                  {t('db.updatedAt', updatedAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }))}
                </Badge>
              )}
            </div>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <SegmentedControl value={days} onChange={onDaysChange} ariaLabel={t('sml.rangeLabel')}
            options={[1, 7, 30].map((n) => ({ value: n, label: t(`uact.range${n}d`) }))} />
          <Button type="button" variant="outline" className="h-9" onClick={() => onRefresh?.()} disabled={loading} aria-busy={loading || undefined}>
            <RefreshCw aria-hidden="true" className={cn(loading && 'animate-spin motion-reduce:animate-none')} />
            {loading ? t('health.dbRefreshing') : t('health.dbRefresh')}
          </Button>
        </div>
      </div>

      {error && (
        <AlertBanner tone="danger" role="alert" title={t('db.loadErrorTitle')} className="mb-0"
          actions={<Button type="button" variant="outline" size="sm" onClick={() => onRefresh?.()} disabled={loading}>{t('db.retry')}</Button>}>
          {typeof error === 'string' ? error : null}
        </AlertBanner>
      )}

      {!d ? (loading ? <DbSkeleton /> : !error && <StatusBlock tone="neutral" icon={Database} title={t('db.noRows')} />) : (
        <>
          {!pgss && (
            <AlertBanner tone="info" title={t('db.fallbackTitle')} className="mb-0">
              {t('db.fallbackBody')}
            </AlertBanner>
          )}
          {d.truncated && (
            <AlertBanner tone="warning" className="mb-0">{t('db.truncated', num(d.row_limit))}</AlertBanner>
          )}

          {/* KPI'lar — telefonda 2, çok dar ekranda 1 sütun, geniş ekranda tek sıra; tıklanınca ilgili sekme/ayrıntı */}
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(130px,100%),1fr))] gap-3">
            <DbKpi kpiKey="size" icon={HardDrive} label={t('db.connSize')} value={sum.db_size || conn.db_size || '—'}
              sub={t('db.kpiTables', num(sum.table_count ?? tables.length))} hint={t('uact.detailHint')}
              onClick={() => goTab('tables', { tableReset: true })} />
            <DbKpi kpiKey="connections" icon={Activity} label={t('db.secConn')}
              value={`${connActive ?? '—'} / ${connMax ?? '—'}`}
              badge={connPct != null ? t('db.kpiUsage', connPct) : null} badgeTone={connTone}
              sub={resp != null && resp >= 0 ? t('db.kpiConnSub', resp) : null} hint={t('uact.detailHint')}
              onClick={() => setConnOpen(true)} />
            <DbKpi kpiKey="queries" icon={Database} label={t('db.kpiQueries')} value={num(sum.queries ?? 0)}
              badge={t('db.kpiSuccess', succ)} badgeTone={succTone} sub={winLbl} hint={t('uact.detailHint')}
              onClick={() => goTab('sql', { view: 'recent' })} />
            <DbKpi kpiKey="avg" icon={Timer} label={t('db.kpiAvg')} value={`${num(sum.avg_ms ?? 0)} ms`}
              valueTone={msTone(sum.avg_ms) === 'success' ? undefined : msTone(sum.avg_ms)}
              sub={t('db.kpiMax', num(sum.max_ms ?? 0))} hint={t('uact.detailHint')}
              onClick={() => goTab('sql', { view: 'recent', sort: { key: 'duration_ms', dir: 'desc' } })} />
            <DbKpi kpiKey="slowest" icon={Gauge} label={t('db.kpiSlowest')} value={`${num(sum.max_ms ?? 0)} ms`}
              valueTone={msTone(sum.max_ms) === 'danger' ? 'danger' : undefined} sub={winLbl} hint={t('uact.detailHint')}
              onClick={() => goTab('sql', { view: 'slowest' })} />
            <DbKpi kpiKey="failed" icon={XCircle} label={t('db.kpiFailed')} value={num(failedN)}
              valueTone={failedN > 0 ? 'danger' : undefined}
              badge={failedN > 0 ? null : t('db.kpiNone')} badgeTone="success" sub={winLbl} hint={t('uact.detailHint')}
              onClick={() => goTab('failed')} />
          </div>

          {/* Sorgu yükü */}
          <Card className="gap-3 px-4 py-4 shadow-xs">
            <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
              <h4 className="font-semibold">{t('db.trendTitle')}</h4>
              <span className="text-xs text-muted-foreground">{t('db.trendHint')} · {winLbl}</span>
            </div>
            {chartData.length === 0 || chartData.every((b) => !b.count) ? (
              <StatusBlock tone="neutral" icon={Activity} title={t('db.trendEmpty')} />
            ) : (
              <ChartContainer config={chartConfig} className="aspect-auto h-[240px] w-full sm:h-[280px]" data-testid="db-trend">
                <ComposedChart data={chartData} margin={{ top: 8, right: 4, left: 0, bottom: 0 }}>
                  <CartesianGrid vertical={false} strokeDasharray="3 3" />
                  <XAxis dataKey="label" tickLine={false} axisLine={false} minTickGap={16} tick={{ fontSize: 11 }} />
                  <YAxis yAxisId="n" allowDecimals={false} tickLine={false} axisLine={false} width={36} tick={{ fontSize: 11 }} />
                  <YAxis yAxisId="ms" orientation="right" tickLine={false} axisLine={false} width={48} tick={{ fontSize: 11 }}
                    tickFormatter={(v) => `${v} ms`} />
                  <ChartTooltip content={<ChartTooltipContent labelFormatter={(_, p) => p?.[0]?.payload?.full || ''} />} />
                  <ChartLegend content={<ChartLegendContent />} />
                  <Bar yAxisId="n" dataKey="count" fill="var(--color-count)" radius={[4, 4, 0, 0]} maxBarSize={36} isAnimationActive={false} />
                  <Bar yAxisId="n" dataKey="failed" fill="var(--color-failed)" radius={[4, 4, 0, 0]} maxBarSize={36} isAnimationActive={false} />
                  <Line yAxisId="ms" type="monotone" dataKey="avg_ms" stroke="var(--color-avg_ms)" strokeWidth={2} dot={{ r: 2.5 }} isAnimationActive={false} />
                </ComposedChart>
              </ChartContainer>
            )}
          </Card>

          {/* Ayrıntı sekmeleri */}
          <div ref={tabsRef} className="scroll-mt-4">
            <Tabs value={tab} onValueChange={setTab} className="gap-3">
              <div className="-mx-1 overflow-x-auto px-1 pb-0.5">
                <TabsList className="w-max">
                  <TabsTrigger value="tables" data-tab="tables">{t('db.tabTables')}{tabCount(tables.length)}</TabsTrigger>
                  <TabsTrigger value="sql" data-tab="sql">{t('db.kpiQueries')}</TabsTrigger>
                  <TabsTrigger value="failed" data-tab="failed">{t('db.kpiFailed')}{tabCount(failedRows.length, failedRows.length > 0)}</TabsTrigger>
                  <TabsTrigger value="users" data-tab="users">{t('db.tabUsers')}{tabCount(users.length)}</TabsTrigger>
                </TabsList>
              </div>

              <TabsContent value="tables">
                <DataView key={`tables-${tableSeed}`} t={t} testId="db-tables" rows={tables} columns={tableCols} searchKeys={['table_name']}
                  searchLabel={t('db.searchTables')} listKey="db-tables" initialSort={{ key: 'total_size', dir: 'desc' }} emptyText={t('db.noRows')} />
              </TabsContent>

              <TabsContent value="sql" className="flex flex-col gap-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <SegmentedControl value={sqlView} onChange={(v) => { setSqlView(v); setSqlSort(null) }} ariaLabel={t('db.kpiQueries')}
                    options={[
                      { value: 'top', label: t('db.secTopSql') },
                      { value: 'slowest', label: t('db.secSlowest') },
                      { value: 'recent', label: t('db.secRecent') },
                    ]} className="max-w-full overflow-x-auto" />
                  {sqlView !== 'recent' && (
                    <span className="text-xs text-muted-foreground">{pgss ? t('db.srcPgss') : t('db.srcPlayground')}</span>
                  )}
                </div>
                <DataView key={`sql-${sqlView}-${sqlSort?.key || ''}-${pgss}`} t={t} testId="db-sql" rows={sqlRows[sqlView]} columns={sqlViews[sqlView]}
                  searchKeys={['sql', 'username']} searchLabel={t('db.searchSql')} listKey="db-sql"
                  initialSort={sqlSort || sqlDefaultSort[sqlView]} emptyText={t('db.noRows')} />
              </TabsContent>

              <TabsContent value="failed">
                <DataView t={t} testId="db-failed" rows={failedRows} columns={failedCols} searchKeys={['sql', 'username', 'error']}
                  searchLabel={t('db.searchSql')} listKey="db-failed" initialSort={{ key: 'time', dir: 'desc' }} emptyText={t('db.kpiNone')} />
              </TabsContent>

              <TabsContent value="users">
                <DataView t={t} testId="db-users" rows={users} columns={userCols} searchKeys={['username']}
                  searchLabel={t('db.searchUsers')} listKey="db-users" initialSort={{ key: 'queries', dir: 'desc' }} emptyText={t('db.noRows')} />
              </TabsContent>
            </Tabs>
          </div>
        </>
      )}

      {/* SQL ayrıntısı */}
      {detail && (() => {
        const r = detail.row
        const fields = [
          ['calls', t('db.colCalls'), num(r.calls)],
          ['avg_ms', t('db.colAvgMs'), num(r.avg_ms)],
          ['max_ms', t('db.colMaxMs'), num(r.max_ms)],
          ['total_ms', t('db.colTotalMs'), num(r.total_ms)],
          ['duration_ms', t('db.colDurMs'), num(r.duration_ms)],
          ['rows', t('health.dbRows'), num(r.rows)],
          ['username', t('uact.colUser'), r.username],
          ['time', t('uact.colTime'), r.time ? formatDateSec(r.time) : null],
          ['last', t('db.colLastRun'), r.last ? formatDateSec(r.last) : null],
        ].filter(([k]) => r[k] != null && r[k] !== '')
        return (
          <ModalShell open onClose={() => setDetail(null)} title={t('db.sqlDetail')} icon={FileCode} size="lg" scrollBody closeLabel={t('app.dismiss')}>
            {detail.row.error && <AlertBanner tone="danger" title={t('db.colError')}>{detail.row.error}</AlertBanner>}
            {r.success != null && (
              <div className="mb-3"><ToneBadge tone={r.success === false ? 'danger' : 'success'}>{r.success === false ? t('uact.failed') : t('uact.success')}</ToneBadge></div>
            )}
            <div className="relative">
              <pre data-testid="db-sql-full" className="max-h-[40vh] overflow-auto rounded-lg border border-border bg-muted/60 py-3 pr-12 pl-3 font-mono text-xs leading-relaxed break-words whitespace-pre-wrap">{r.sql || '—'}</pre>
              <CopyButton value={r.sql || ''} label={t('db.copySql')} copiedLabel={t('db.copied')} className="absolute top-2 right-2" />
            </div>
            {String(r.sql || '').endsWith('…') && <p className="mt-1.5 text-xs text-muted-foreground">{t('db.sqlPreviewNote')}</p>}
            {fields.length > 0 && (
              <div className={cn(KV_GRID, 'mt-4')}>
                {fields.map(([k, label, value]) => <KvField key={k} label={label} value={value} mono={k === 'username' || k === 'time' || k === 'last'} />)}
              </div>
            )}
          </ModalShell>
        )
      })()}

      {/* Bağlantı ayrıntısı */}
      {connOpen && (
        <ModalShell open onClose={() => setConnOpen(false)} title={t('db.secConn')} icon={Activity} size="md" closeLabel={t('app.dismiss')}>
          <div className="flex flex-col gap-4">
            {connPct != null && (
              <div className="flex flex-col gap-1.5">
                <div className="flex items-center justify-between text-sm">
                  <span className="text-muted-foreground">{t('db.connUsage')}</span>
                  <ToneBadge tone={connTone}>{t('db.kpiUsage', connPct)}</ToneBadge>
                </div>
                <ProgressBar value={connActive} max={connMax} size="sm" label={t('db.connUsage')}
                  tone={connTone === 'danger' ? 'crit' : connTone === 'warning' ? 'warn' : 'ok'} />
              </div>
            )}
            <div className={KV_GRID} data-testid="db-conn-detail">
              <KvField label={t('db.connActive')} value={num(connActive)} />
              <KvField label={t('db.connMax')} value={num(connMax)} />
              <KvField label={t('db.connSize')} value={conn.db_size || sum.db_size} />
              <KvField label={t('db.connResp')} value={resp == null ? null : resp < 0 ? '—' : (
                <span className="inline-flex items-center gap-2">{resp} ms <ToneBadge tone={respTone}>{respTone === 'success' ? 'OK' : '!'}</ToneBadge></span>
              )} />
              <KvField label={t('health.dbTable')} value={t('db.kpiTables', num(sum.table_count ?? tables.length))} />
            </div>
          </div>
        </ModalShell>
      )}
    </div>
  )
}
