import { useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertOctagon, ArrowDown, ArrowUp, ArrowUpDown, BarChart3, Braces, CheckCircle2, ChevronDown, Clipboard, Columns3, Download,
  FileJson, FileSpreadsheet, GitCompare, Lock, OctagonX, Play, ScanSearch, Search, ServerCog, ShieldAlert, Sheet as SheetIcon,
  Table2, Timer, WrapText, X,
} from 'lucide-react'
import { useDateLocale, useT } from '../../../i18n/index.jsx'
import { copyText } from '../../../utils/copyText.js'
import { downloadBlob } from '../../../utils/csvExport.js'
import { clientFailure } from '../../../utils/errorMessages.js'
import { usePagination } from '../../../hooks/usePagination.js'
import AlertBanner from '../../ui/AlertBanner.jsx'
import CopyButton from '../../ui/CopyButton.jsx'
import KebabMenu from '../../ui/KebabMenu.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import {
  DropdownMenu, DropdownMenuCheckboxItem, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import SqlRowDetailModal from '../SqlRowDetailModal.jsx'
import ChoiceToggle from './ChoiceToggle.jsx'
import { EXPORT_LIMITS, excelSheet } from './sqlLibraryModel.js'
import {
  MASK_TEXT, MAX_ROWS, QUERY_TIMEOUT_SEC, cellText, columnTypes, downloadText, filterRows, formatDuration, offsetToLineCol,
  parseSqlError, resultColumns, rowsToCsv, rowsToJson, rowsToTsv, sortRows, stampedFile,
} from './sqlUtils.js'

const TYPE_SHORT = { number: 'num', boolean: 'bool', datetime: 'time', json: 'json', text: 'text', null: 'null' }

/** Hücre içeriği: NULL rozeti, sayı sağa hizalı, mantıksal renkli, JSON eş aralıklı; maskeli kolon kilitli "••••". */
function Cell({ value, type, masked = false }) {
  if (masked && value != null) return <MaskedCell />
  if (value == null) return <Badge variant="outline" className="h-5 border-dashed px-1.5 text-[10px] font-medium text-muted-foreground">NULL</Badge>
  if (typeof value === 'boolean') return <span className={cn('font-mono text-xs', value ? 'text-success' : 'text-muted-foreground')}>{String(value)}</span>
  if (typeof value === 'object') return <span className="font-mono text-xs text-muted-foreground">{cellText(value)}</span>
  if (type === 'datetime') return <span className="font-mono text-xs">{String(value)}</span>
  return String(value)
}

function MaskedCell() {
  const t = useT()
  return (
    <span data-slot="sql-masked-cell" className="inline-flex items-center gap-1 text-muted-foreground">
      <Lock aria-hidden="true" className="size-3" /><span aria-hidden="true">{MASK_TEXT}</span><span className="sr-only">{t('sql.mask.cell')}</span>
    </span>
  )
}

/** Hata görünümü: özet + ipucu + konum ("düzenleyicide göster") + teknik ayrıntı (ham metin, kopyalanabilir). */
function ErrorView({ result, onRevealError, t }) {
  const code = result.code ?? result.errorInfo?.code
  // Hassas kolon reddi (A1): sorgu ÇALIŞMADI — kolonun yalnız en dış seçimde çıplak seçilebildiği anlatılır
  if (code === 'SQL_SENSITIVE_COLUMN') {
    return (
      <div className="flex flex-col gap-3 p-3" data-slot="sql-error-sensitive">
        <AlertBanner tone="danger" role="alert" title={t('sql.err.sensitiveTitle')} icon={Lock} className="mb-0">
          <span data-slot="sql-error-summary" className="block break-words">{result.error}</span>
          <span className="mt-1 block text-xs">{t('sql.err.sensitiveHint')}</span>
        </AlertBanner>
      </div>
    )
  }
  if (code === 'SQL_PARAM_MISSING' || code === 'SQL_PARAM_INVALID' || code === 'SQL_PARAM_TOO_MANY') {
    return (
      <div className="flex flex-col gap-3 p-3" data-slot="sql-error-param">
        <AlertBanner tone="danger" role="alert" title={t('sql.err.paramTitle')} icon={Braces} className="mb-0">
          <span data-slot="sql-error-summary" className="block break-words">{result.error}</span>
          <span className="mt-1 block text-xs">{t('sql.err.paramHint')}</span>
        </AlertBanner>
      </div>
    )
  }
  const info = parseSqlError(result.error, { rejected: result.success === false, executedSql: result.executedSql || '', sql: result.ranSql || '' })
  const title = {
    guard: t('sql.err.guardTitle'), grammar: t('sql.err.grammarTitle'),
    timeout: t('sql.err.timeoutTitle', QUERY_TIMEOUT_SEC), db: t('sql.err.dbTitle'),
  }[info.kind]
  const where = info.editorOffset != null ? offsetToLineCol(result.ranSql || '', info.editorOffset) : null
  const summary = info.summary
    || (info.kind === 'grammar' ? t('sql.err.grammarNoDetail') : info.kind === 'timeout' ? t('sql.err.timeoutHint') : t('sql.err.dbNoDetail'))
  return (
    <div className="flex flex-col gap-3 p-3">
      <AlertBanner tone="danger" role="alert" title={title} icon={info.kind === 'guard' ? ShieldAlert : AlertOctagon} className="mb-0"
        actions={where && onRevealError && (
          <Button type="button" variant="outline" size="sm" className="bg-background" onClick={() => onRevealError(info.editorOffset)}>
            {t('sql.err.showInEditor')}
          </Button>
        )}>
        <span data-slot="sql-error-summary" className="block font-mono text-[13px] break-words whitespace-pre-wrap">{summary}</span>
        {info.detail && <span className="mt-1 block text-xs">{t('sql.err.detail', info.detail)}</span>}
        {info.hint && <span className="mt-1 block text-xs">{t('sql.err.hint', info.hint)}</span>}
        {where && <span className="mt-1 block text-xs font-medium">{t('sql.err.position', where.line, where.col)}</span>}
      </AlertBanner>
      {info.kind === 'guard' && (
        <AlertBanner tone="info" title={t('sql.pg.rulesTitle')} className="mb-0">{t('sql.pg.rules', MAX_ROWS.toLocaleString(), QUERY_TIMEOUT_SEC)}</AlertBanner>
      )}
      {info.raw && info.raw !== summary && (
        <Collapsible>
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost" size="sm" className="group/raw -ml-2 text-muted-foreground">
              <ChevronDown className="transition-transform group-data-[state=open]/raw:rotate-180 motion-reduce:transition-none" /> {t('sql.err.technical')}
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent>
            <div className="relative mt-1 rounded-md border bg-muted/40 p-3 pr-12">
              <pre className="m-0 font-mono text-xs break-all whitespace-pre-wrap text-muted-foreground">{info.raw}</pre>
              <CopyButton value={info.raw} label={t('sql.err.copyRaw')} copiedLabel={t('sql.copied')} className="absolute top-2 right-2" />
            </div>
          </CollapsibleContent>
        </Collapsible>
      )}
    </div>
  )
}

/**
 * Sorgu SONUÇ paneli — shadcn Table (yapışkan başlık, satır no., tip ipucu, NULL rozeti, sıralama, sütun görünürlüğü,
 * metni sar), hızlı süzgeç, CSV/JSON indir + TSV/JSON kopyala (o anki görünüm), 1000 satır tavanı uyarısı, standart
 * sayfalama (usePagination + PaginationBar). Satıra tıklama / Enter → satır ayrıntısı (önceki/sonraki gezintisiyle);
 * Ctrl/⌘+C odaklı satırı TSV kopyalar. Telefonda tablo yerine satır kartları (ilk 3 alan + "N alan daha").
 */
export default function ResultsPanel({
  result, running, elapsedMs, phone = false, samples, onPickSample, onRevealError, className,
  resultView = 'table', onViewChange, chartSlot = null, planSlot = null, compareSlot = null,
  onServerExport = null, serverExport = null, onCancelExport, onStop, stopping = false,
}) {
  const t = useT()
  const locale = useDateLocale()
  const toast = useToast()
  const rows = useMemo(() => (result && !result.error && !result.cancelled && Array.isArray(result.rows) ? result.rows : []), [result])
  const cols = useMemo(() => resultColumns(rows), [rows])
  const types = useMemo(() => columnTypes(rows, cols), [rows, cols])
  const masked = useMemo(() => new Set(Array.isArray(result?.masked_columns) ? result.masked_columns : []), [result])
  const rowNo = useMemo(() => new Map(rows.map((r, i) => [r, i + 1])), [rows])

  const [filter, setFilter] = useState('')
  const [sort, setSort] = useState({ col: null, dir: 'asc' })
  const [hidden, setHidden] = useState(() => new Set())
  const [wrap, setWrap] = useState(false)
  const [detail, setDetail] = useState(null)   // görünümdeki sıra (0-tabanlı)
  const seqRef = useRef(result?.seq)
  // Yeni sonuç → görünüm ayarları sıfırlanır (eski sütun adları yeni sonuca uymaz).
  useEffect(() => {
    if (seqRef.current === result?.seq) return
    seqRef.current = result?.seq
    setFilter(''); setSort({ col: null, dir: 'asc' }); setHidden(new Set()); setDetail(null)
  }, [result?.seq])

  const visibleCols = useMemo(() => cols.filter((c) => !hidden.has(c)), [cols, hidden])
  const filtered = useMemo(() => filterRows(rows, visibleCols, filter), [rows, visibleCols, filter])
  const view = useMemo(() => sortRows(filtered, sort.col, sort.dir, types[sort.col]), [filtered, sort, types])
  // Telefonda kart listesi: pencere ön ayarı (10 / [10, 25, 50], kısa çubuk) — 50 kart 7000 px'lik sayfa demekti.
  const pager = usePagination(view, {
    listKey: phone ? 'sql-results-phone' : 'sql-results', preset: phone ? 'modal' : 'page',
    resetDeps: [result?.seq ?? 0, filter, sort.col, sort.dir],
  })
  const offset = (pager.page - 1) * pager.pageSize

  const toggleSort = (c) => setSort((s) => (s.col !== c ? { col: c, dir: 'asc' } : s.dir === 'asc' ? { col: c, dir: 'desc' } : { col: null, dir: 'asc' }))
  const copyRows = async (list, kind) => {
    const text = kind === 'json' ? rowsToJson(list, visibleCols) : rowsToTsv(list, visibleCols)
    if (await copyText(text)) toast.success(t('sql.res.copied', list.length))
    else toast.error(t('sql.res.copyFailed'))
  }
  const exportFile = async (kind) => {
    if (kind === 'xlsx') {
      try {
        const mod = await import('../../../utils/xlsxWriter.js')
        const bytes = mod.buildXlsx([excelSheet(view, visibleCols, types)])
        if (!downloadBlob(stampedFile('query', 'xlsx'), new Blob([bytes], { type: mod.XLSX_MIME }))) { toast.error(t('sql.res.downloadFailed')); return }
      } catch (e) {
        const f = clientFailure(e, { fallback: t('sql.res.xlsxFailed'), chunk: t('te.pageUpdated') })
        toast.error(f.message, { details: f.details })
        return
      }
      toast.success(t('sql.res.exported', 'Excel', view.length))
      return
    }
    const ok = kind === 'csv'
      ? downloadText(stampedFile('query', 'csv'), rowsToCsv(view, visibleCols), 'text/csv')
      : downloadText(stampedFile('query', 'json'), rowsToJson(view, visibleCols), 'application/json')
    if (ok === false) { toast.error(t('sql.res.downloadFailed')); return }
    toast.success(t('sql.res.exported', kind.toUpperCase(), view.length))
  }
  const openAt = (i) => setDetail(i)

  const hasRows = rows.length > 0
  const capped = hasRows && (result?.rowCount ?? rows.length) >= MAX_ROWS
  const status = running ? (
    <span className="inline-flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground tabular-nums" aria-live="polite">
      <Timer aria-hidden="true" className="size-3.5" />{t('sql.res.running', formatDuration(elapsedMs))}
      {onStop && (
        <Button type="button" variant="outline" size="sm" onClick={onStop} disabled={stopping} data-slot="sql-stop-inline"
          className="h-7 text-destructive pointer-coarse:h-10">
          <OctagonX /> {stopping ? t('sql.stop.stopping') : t('sql.stop.label')}
        </Button>
      )}
    </span>
  ) : result?.cancelled ? (
    <Badge variant="outline" className="gap-1 text-muted-foreground" data-slot="sql-cancelled-badge"><OctagonX aria-hidden="true" />{t('sql.stop.stopped')}</Badge>
  ) : result?.error ? (
    <Badge variant="destructive" className="gap-1"><AlertOctagon aria-hidden="true" />{t('sql.res.failed')}</Badge>
  ) : result ? (
    <span className="flex flex-wrap items-center gap-1.5">
      <Badge variant="outline" className="gap-1 border-success/30 bg-success/10 text-success tabular-nums">
        <CheckCircle2 aria-hidden="true" />{t('sql.rowsCount', (result.rowCount ?? rows.length).toLocaleString(locale))}
      </Badge>
      <Badge variant="outline" className="gap-1 font-normal text-muted-foreground tabular-nums"><Timer aria-hidden="true" />{formatDuration(result.durationMs)}</Badge>
      {filter && <Badge variant="secondary" className="font-normal tabular-nums">{t('sql.res.matching', view.length.toLocaleString(locale))}</Badge>}
      {masked.size > 0 && (
        <Badge variant="outline" className="gap-1 font-normal" data-slot="sql-masked-badge"><Lock aria-hidden="true" />{t('sql.mask.badge', masked.size)}</Badge>
      )}
    </span>
  ) : null

  const toolbar = hasRows && !running && (
    <div className="flex w-full flex-wrap items-center gap-2 sm:w-auto">
      <InputGroup className="h-10 w-full sm:h-8 sm:w-52 pointer-coarse:h-10">
        <InputGroupInput value={filter} onChange={(e) => setFilter(e.target.value)} placeholder={t('sql.res.filter')} aria-label={t('sql.res.filter')} />
        <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
        {filter && (
          <InputGroupAddon align="inline-end">
            <InputGroupButton size="icon-xs" aria-label={t('sql.ex.clearSearch')} onClick={() => setFilter('')}><X /></InputGroupButton>
          </InputGroupAddon>
        )}
      </InputGroup>
      <DropdownMenu modal={false}>
        <DropdownMenuTrigger asChild>
          <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10">
            <Columns3 /> {t('sql.res.columns', visibleCols.length, cols.length)}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="z-(--z-menu) max-h-[min(24rem,60dvh)] w-60 overflow-y-auto">
          <DropdownMenuLabel>{t('sql.res.visibleColumns')}</DropdownMenuLabel>
          {cols.map((c) => (
            <DropdownMenuCheckboxItem key={c} checked={!hidden.has(c)} onSelect={(e) => e.preventDefault()}
              disabled={!hidden.has(c) && visibleCols.length === 1}
              onCheckedChange={(v) => setHidden((s) => { const n = new Set(s); if (v) n.delete(c); else n.add(c); return n })}
              className="font-mono text-xs">
              {c}
            </DropdownMenuCheckboxItem>
          ))}
          {hidden.size > 0 && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuItem onSelect={() => setHidden(new Set())}>{t('sql.res.resetColumns')}</DropdownMenuItem>
            </>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      {!phone && (
        <Button type="button" variant="outline" size="sm" aria-pressed={wrap} onClick={() => setWrap((w) => !w)}
          className="aria-pressed:bg-accent aria-pressed:text-foreground">
          <WrapText /> {t('sql.res.wrap')}
        </Button>
      )}
      <DropdownMenu modal={false}>
        <DropdownMenuTrigger asChild>
          <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10">
            <Download /> {t('sql.res.export')}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="z-(--z-menu) w-64">
          <DropdownMenuLabel className="font-normal text-muted-foreground">{t('sql.res.exportScope', view.length.toLocaleString(locale))}</DropdownMenuLabel>
          <DropdownMenuItem onSelect={() => exportFile('csv')}><FileSpreadsheet /> {t('sql.res.downloadCsv')}</DropdownMenuItem>
          <DropdownMenuItem onSelect={() => exportFile('xlsx')} data-slot="sql-export-xlsx"><SheetIcon /> {t('sql.res.downloadXlsx')}</DropdownMenuItem>
          <DropdownMenuItem onSelect={() => exportFile('json')}><FileJson /> {t('sql.res.downloadJson')}</DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem onSelect={() => copyRows(view, 'tsv')}><Clipboard /> {t('sql.res.copyTsv')}</DropdownMenuItem>
          <DropdownMenuItem onSelect={() => copyRows(view, 'json')}><Clipboard /> {t('sql.res.copyJson')}</DropdownMenuItem>
          {onServerExport && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuLabel className="font-normal whitespace-normal text-muted-foreground">{t('sql.exp.fullHeading')}</DropdownMenuLabel>
              <DropdownMenuItem onSelect={() => onServerExport('csv')} disabled={Boolean(serverExport?.busy)} data-slot="sql-export-full-csv">
                <ServerCog /> {t('sql.exp.fullCsv', EXPORT_LIMITS.csv.toLocaleString(locale))}
              </DropdownMenuItem>
              <DropdownMenuItem onSelect={() => onServerExport('xlsx')} disabled={Boolean(serverExport?.busy)} data-slot="sql-export-full-xlsx">
                <ServerCog /> {t('sql.exp.fullXlsx', EXPORT_LIMITS.json.toLocaleString(locale))}
              </DropdownMenuItem>
            </>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  )

  let body
  if (resultView === 'chart' && !running) {
    body = chartSlot
  } else if (resultView === 'plan') {
    body = planSlot
  } else if (resultView === 'compare' && !running) {
    body = compareSlot
  } else if (running) {
    body = <LoadingBlock label={t('sql.res.running', formatDuration(elapsedMs))} size={20} />
  } else if (result?.cancelled) {
    body = (
      <div data-slot="sql-result-cancelled">
        <StatusBlock icon={OctagonX} title={t('sql.stop.stoppedTitle')} description={t('sql.stop.stoppedHint')} className="py-8" />
      </div>
    )
  } else if (!result) {
    body = (
      <StatusBlock icon={Play} title={t('sql.res.emptyTitle')} description={t('sql.res.emptyHint')} className="py-8"
        actions={(samples || []).slice(0, 3).map((s, i) => (
          <Button key={i} type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => onPickSample?.(s.sql)}>
            {s.label}
          </Button>
        ))} />
    )
  } else if (result.error) {
    body = <ErrorView result={result} onRevealError={onRevealError} t={t} />
  } else if (!hasRows) {
    body = <StatusBlock icon={CheckCircle2} title={t('sql.noRows')} description={t('sql.res.noRowsHint', formatDuration(result.durationMs))} className="py-8" />
  } else if (view.length === 0) {
    body = (
      <StatusBlock icon={Search} title={t('sql.res.noMatch')} className="py-8"
        actions={<Button type="button" variant="outline" size="sm" onClick={() => setFilter('')}>{t('sql.res.clearFilter')}</Button>} />
    )
  } else if (phone) {
    body = (
      <ul data-slot="sql-result-cards" className="flex flex-col gap-2 p-3">
        {pager.pageItems.map((row, j) => {
          const i = offset + j
          const n = rowNo.get(row)
          const lead = visibleCols.slice(0, 3)
          const rest = visibleCols.length - lead.length
          return (
            <li key={n}>
              <Button type="button" variant="outline" onClick={() => openAt(i)}
                className="h-auto w-full flex-col items-stretch gap-1.5 p-3 text-left font-normal whitespace-normal">
                <span className="flex items-center justify-between text-xs text-muted-foreground">
                  <span className="font-semibold text-foreground">{t('sql.rowLabel', n)}</span>
                  {rest > 0 && <span>{t('sql.res.moreFields', rest)}</span>}
                </span>
                {lead.map((c) => (
                  <span key={c} className="grid grid-cols-[minmax(0,7.5rem)_minmax(0,1fr)] gap-2 text-sm">
                    <span className="truncate font-mono text-xs text-muted-foreground">{c}</span>
                    <span className="min-w-0 truncate"><Cell value={row[c]} type={types[c]} masked={masked.has(c)} /></span>
                  </span>
                ))}
              </Button>
            </li>
          )
        })}
      </ul>
    )
  } else {
    body = (
      <div data-slot="sql-result-scroll" className="min-h-0 flex-1 overflow-auto [&_[data-slot=table-container]]:overflow-visible">
        <Table data-slot="sql-result-table" className="text-[13px]">
          <TableHeader className="sticky top-0 z-10 bg-muted shadow-[0_1px_0_var(--border)]">
            <TableRow className="hover:bg-transparent">
              <TableHead className="w-12 px-3 text-right text-xs font-medium text-muted-foreground">#</TableHead>
              {visibleCols.map((c) => {
                const active = sort.col === c
                const Icon = !active ? ArrowUpDown : sort.dir === 'asc' ? ArrowUp : ArrowDown
                return (
                  <TableHead key={c} aria-sort={active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'}
                    className={cn('h-auto p-0', types[c] === 'number' && 'text-right')}>
                    <Button type="button" variant="ghost" size="sm" onClick={() => toggleSort(c)}
                      aria-label={t('sql.res.sortBy', c, masked.has(c) ? t('sql.mask.short') : (TYPE_SHORT[types[c]] ?? types[c]))}
                      className={cn('h-auto w-full gap-1.5 rounded-none px-3 py-1.5 font-normal', types[c] === 'number' ? 'justify-end' : 'justify-start')}>
                      <span className="flex min-w-0 flex-col items-start leading-tight">
                        <span className="max-w-[18rem] text-xs font-semibold whitespace-normal text-foreground [overflow-wrap:anywhere]">{c}</span>
                        <span className="inline-flex items-center gap-1 text-[10px] tracking-wide text-muted-foreground uppercase">
                          {masked.has(c) && <Lock aria-hidden="true" className="size-3" data-slot="sql-masked-head" />}
                          {masked.has(c) ? t('sql.mask.short') : (TYPE_SHORT[types[c]] ?? types[c])}
                        </span>
                      </span>
                      <Icon aria-hidden="true" className={cn('size-3.5 shrink-0', active ? 'text-foreground' : 'text-muted-foreground/60')} />
                    </Button>
                  </TableHead>
                )
              })}
              <TableHead className="w-10"><span className="sr-only">{t('sql.res.rowActions')}</span></TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {pager.pageItems.map((row, j) => {
              const i = offset + j
              const n = rowNo.get(row)
              return (
                <TableRow key={n} tabIndex={0} data-row={n} className="cursor-pointer focus-visible:bg-muted focus-visible:outline-none"
                  onClick={() => openAt(i)}
                  onKeyDown={(e) => {
                    if (e.target !== e.currentTarget) return
                    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openAt(i) }
                    if ((e.ctrlKey || e.metaKey) && (e.key === 'c' || e.key === 'C')) { e.preventDefault(); copyRows([row], 'tsv') }
                  }}>
                  <TableCell className="px-3 text-right align-top text-xs text-muted-foreground tabular-nums">{n}</TableCell>
                  {visibleCols.map((c) => (
                    <TableCell key={c}
                      className={cn('px-3 align-top', types[c] === 'number' && 'text-right tabular-nums',
                        wrap ? 'max-w-[28rem] break-words whitespace-pre-wrap' : 'max-w-[20rem] truncate')}
                      title={!wrap && row[c] != null && !masked.has(c) ? cellText(row[c]) : undefined}>
                      <Cell value={row[c]} type={types[c]} masked={masked.has(c)} />
                    </TableCell>
                  ))}
                  <TableCell className="px-1 py-0.5 align-top" onClick={(e) => e.stopPropagation()}>
                    <KebabMenu label={t('sql.res.rowActions')} rowLabel={t('sql.rowLabel', n)} items={[
                      { label: t('sql.res.openRow'), onClick: () => openAt(i) },
                      { label: t('sql.res.copyRowTsv'), onClick: () => copyRows([row], 'tsv') },
                      { label: t('sql.res.copyRowJson'), onClick: () => copyRows([row], 'json') },
                    ]} />
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      </div>
    )
  }

  const detailRow = detail != null ? view[detail] : null
  return (
    <section data-slot="sql-result" aria-label={t('sql.res.title')}
      className={cn('flex min-h-0 min-w-0 flex-col overflow-hidden rounded-xl border bg-card text-card-foreground shadow-sm', className)}>
      <div className="flex flex-wrap items-center gap-2 border-b px-3 py-2">
        <h3 className="text-sm font-semibold">{t('sql.res.title')}</h3>
        {onViewChange && (
          <ChoiceToggle ariaLabel={t('sql.view.label')} value={resultView} onChange={onViewChange} options={[
            { value: 'table', label: t('sql.view.table'), icon: Table2, hideLabel: phone, ariaLabel: t('sql.view.table') },
            { value: 'chart', label: t('sql.view.chart'), icon: BarChart3, hideLabel: phone, ariaLabel: t('sql.view.chart') },
            { value: 'plan', label: t('sql.view.plan'), icon: ScanSearch, hideLabel: phone, ariaLabel: t('sql.view.plan') },
            { value: 'compare', label: t('sql.view.compare'), icon: GitCompare, hideLabel: phone, ariaLabel: t('sql.view.compare') },
          ]} />
        )}
        {status}
        {result?.finishedAt && !running && (
          <span className="text-xs text-muted-foreground">{new Date(result.finishedAt).toLocaleTimeString(locale)}</span>
        )}
        <div className="ml-auto flex min-w-0 flex-wrap items-center gap-2">{resultView === 'table' && toolbar}</div>
      </div>
      {serverExport?.busy && (
        <div data-slot="sql-export-busy" className="flex flex-wrap items-center gap-2 border-b bg-muted/40 px-3 py-1.5 text-xs">
          <Timer aria-hidden="true" className="size-3.5 text-muted-foreground" />
          <span role="status" className="min-w-0">{t('sql.exp.busy', serverExport.format === 'csv' ? 'CSV' : 'Excel')}</span>
          {onCancelExport && (
            <Button type="button" variant="outline" size="sm" className="ml-auto h-7 pointer-coarse:h-10" onClick={onCancelExport}>
              <OctagonX /> {t('sql.stop.label')}
            </Button>
          )}
        </div>
      )}
      {resultView === 'table' && masked.size > 0 && hasRows && !running && (
        <p data-slot="sql-masked-note" className="m-0 flex flex-wrap items-center gap-1.5 border-b px-3 py-1.5 text-xs text-muted-foreground [overflow-wrap:anywhere]">
          <Lock aria-hidden="true" className="size-3.5 shrink-0" />{t('sql.mask.note', [...masked].join(', '))}
        </p>
      )}
      {resultView === 'table' && capped && !running && (
        <AlertBanner tone="warning" title={t('sql.res.cappedTitle', MAX_ROWS.toLocaleString(locale))} className="m-3 mb-0">
          {t('sql.res.cappedHint')}
        </AlertBanner>
      )}
      <div className={cn('flex min-h-0 flex-1 flex-col', phone && 'overflow-y-auto')}>{body}</div>
      {resultView === 'table' && hasRows && !running && view.length > 0 && (
        <div className="shrink-0 border-t px-3 py-2"><PaginationBar {...pager} /></div>
      )}
      {resultView === 'table' && result?.executedSql && !running && (
        <Collapsible className="shrink-0 border-t">
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost" size="sm" className="group/exec h-8 w-full justify-start rounded-none px-3 text-xs font-normal text-muted-foreground">
              <ChevronDown className="size-3.5 transition-transform group-data-[state=open]/exec:rotate-180 motion-reduce:transition-none" />
              {t('sql.res.executedAs')}
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent>
            <div className="relative px-3 pb-3">
              <pre className="m-0 max-h-40 overflow-auto rounded-md bg-muted/50 p-2.5 pr-11 font-mono text-xs break-all whitespace-pre-wrap">{result.executedSql}</pre>
              <CopyButton value={result.executedSql} label={t('sql.res.copyExecuted')} copiedLabel={t('sql.copied')} className="absolute top-1.5 right-4.5" />
            </div>
          </CollapsibleContent>
        </Collapsible>
      )}
      {detailRow && (
        <SqlRowDetailModal row={detailRow} cols={cols} index={(rowNo.get(detailRow) ?? 1) - 1} types={types}
          position={`${(detail + 1).toLocaleString(locale)} / ${view.length.toLocaleString(locale)}`}
          onPrev={detail > 0 ? () => setDetail(detail - 1) : undefined}
          onNext={detail < view.length - 1 ? () => setDetail(detail + 1) : undefined}
          onClose={() => setDetail(null)} />
      )}
    </section>
  )
}
