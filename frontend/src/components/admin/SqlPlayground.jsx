import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Database, Eraser, FileCode2, Gauge, ListTree, Network, PanelLeft, Play, ShieldCheck, Table2, TableProperties,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useIsMobile } from '@/hooks/use-mobile'
import { runWithConcurrency } from '../../utils/concurrentQueue.js'
import HintPopover from '../ui/HintPopover.jsx'
import PageHeader from '../ui/PageHeader.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Kbd, KbdGroup } from '@/components/shadcn/kbd'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'
import SchemaDiagramModal from './SchemaDiagramModal.jsx'
import TableDetailsModal from './TableDetailsModal.jsx'
import QueryPicker from './sql/QueryPicker.jsx'
import ResultsPanel from './sql/ResultsPanel.jsx'
import SchemaExplorer from './sql/SchemaExplorer.jsx'
import SqlEditor from './sql/SqlEditor.jsx'
import { MAX_ROWS, QUERY_TIMEOUT_SEC, tableQuery, wordAt } from './sql/sqlUtils.js'

/** Yerleşim sınıfı: telefon (< 768, useIsMobile) · tablet (< 1024) · masaüstü. Davranış farkı olduğu için JS'te. */
function useLayoutMode() {
  const phone = useIsMobile()
  const [wide, setWide] = useState(() => typeof window === 'undefined' || window.innerWidth >= 1024)
  useEffect(() => {
    const on = () => setWide(window.innerWidth >= 1024)
    on()
    window.addEventListener('resize', on)
    return () => window.removeEventListener('resize', on)
  }, [])
  return phone ? 'phone' : wide ? 'desktop' : 'tablet'
}

const IS_MAC = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent || '')

/**
 * SQL Playground (Yönetim → SQL Playground, yalnız global yönetici). 2026-09-27 shadcn yeniden tasarımı:
 *
 * - `ui/PageHeader`: salt-okunur rozeti (kural metni dokun-gör), veritabanı sürümü (varsa), tablo sayısı, tavanlar;
 *   eylemler Diyagram · Temizle · ÇALIŞTIR (Ctrl/⌘+Enter; seçim varsa yalnız seçim).
 * - Masaüstü: solda şema gezgini, sağda düzenleyici (sözdizimi boyası + satır cetveli) ve sonuç paneli üst üste.
 *   Tablet: gezgin soldan açılan Sheet. Telefon: Düzenleyici / Sonuçlar / Şema sekmeleri (durum korunur).
 * - Sonuç: sıralama, süzgeç, sütun görünürlüğü, CSV/JSON indir, TSV/JSON kopyala, satır ayrıntısı, 1000 satır uyarısı.
 * - Hata: veritabanı mesajı + konum (düzenleyicide göster) + teknik ayrıntı.
 * - Tablo ayrıntısı (TableDetailsModal) ve ilişki diyagramı (SchemaDiagramModal) buradan açılır; kolon listeleri tek
 *   önbellekte (sınırlı eşzamanlılık) paylaşılır.
 * Arka uç salt okunur (tek SELECT/WITH, kara liste, JDBC read-only bağlantı, 30 sn, 1000 satır) — burada DEĞİŞMEDİ.
 */
export default function SqlPlayground() {
  const t = useT()
  const toast = useToast()
  const layout = useLayoutMode()

  const [tables, setTables] = useState([])
  const [tablesLoading, setTablesLoading] = useState(false)
  const [tablesError, setTablesError] = useState(null)
  const [columnsMap, setColumnsMap] = useState({})
  const [pkMap, setPkMap] = useState({})
  const [rel, setRel] = useState({ data: null, loading: false, error: null })
  const [dbInfo, setDbInfo] = useState(null)
  const [samples, setSamples] = useState([])
  const [history, setHistory] = useState([])

  const [sql, setSql] = useState('')
  const [selection, setSelection] = useState('')
  const [running, setRunning] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const [result, setResult] = useState(null)
  const seq = useRef(0)
  const runningRef = useRef(false)
  const tick = useRef(null)
  useEffect(() => () => clearInterval(tick.current), [])

  const [tableDetail, setTableDetail] = useState(null)   // { table, details, loading }
  const [diagram, setDiagram] = useState(null)           // { focus, n }
  const [phoneTab, setPhoneTab] = useState('editor')
  const [explorerOpen, setExplorerOpen] = useState(false)
  const editorRef = useRef(null)

  const refreshTables = useCallback(async () => {
    setTablesLoading(true)
    setTablesError(null)
    try {
      const r = await api.admin.sqlListTables()
      if (r?.success) { setTables(r.data ?? []); setColumnsMap({}) }
      else setTablesError(r?.error || t('sql.ex.loadError'))
    } catch (e) {
      setTablesError(e?.message || t('sql.ex.loadError'))
    } finally {
      setTablesLoading(false)
    }
  }, [t])

  const loadRelations = useCallback(async () => {
    setRel((s) => ({ ...s, loading: true, error: null }))
    try {
      const r = await api.admin.sqlRelations()
      setRel({ data: r?.success ? r.data : null, loading: false, error: r?.success ? null : (r?.error || t('sql.diag.loadError')) })
    } catch (e) {
      setRel({ data: null, loading: false, error: e?.message || t('sql.diag.loadError') })
    }
  }, [t])

  const loadHistory = useCallback(() => {
    api.admin.sqlHistory().then((r) => { if (r?.success) setHistory(r.data ?? []) }).catch(() => {})
  }, [])

  useEffect(() => {
    refreshTables()
    loadRelations()
    loadHistory()
    api.admin.sqlSamples().then((r) => { if (r?.success) setSamples(r.data ?? []) }).catch(() => {})
    // Veritabanı sürümü/adı — ayrı izin (settings.database); yoksa rozet çizilmez, hata gösterilmez.
    api.admin.getDatabaseInfo?.().then((r) => { if (r?.success && r.data && !Array.isArray(r.data)) setDbInfo(r.data) }).catch(() => {})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** Kolon önbelleği: eksikler en fazla 6 eşzamanlı istekle, sonuç TEK durum güncellemesiyle. */
  const columnsRef = useRef(columnsMap)
  columnsRef.current = columnsMap
  const ensureColumns = useCallback(async (names) => {
    const need = [...new Set(names)].filter((n) => !columnsRef.current[n])
    if (!need.length) return
    const got = {}
    await runWithConcurrency(need, async (n) => {
      try {
        const r = await api.admin.sqlListColumns(n)
        if (r?.success) got[n] = r.data ?? []
      } catch { /* tek tablo düşerse diğerleri sürer */ }
    }, { limit: 6 })
    if (Object.keys(got).length) setColumnsMap((m) => ({ ...m, ...got }))
  }, [])

  /** FK haritası (tablo → kolon → { to, inferred }) — ilişki yanıtından; gezginde FK rozeti ve hedefi. */
  const fkMap = useMemo(() => {
    const m = new Map()
    for (const e of rel.data?.edges ?? []) {
      if (!e?.from || !e?.column) continue
      if (!m.has(e.from)) m.set(e.from, new Map())
      m.get(e.from).set(e.column, { to: e.to, inferred: !!e.inferred })
    }
    return m
  }, [rel.data])

  /** Düzenleyiciye yaz — tarayıcının geri alma yığını korunur; düzenleyici görünür değilse durum üzerinden. */
  const writeEditor = useCallback((text, { replace = false } = {}) => {
    const ed = editorRef.current
    const ok = ed ? (replace ? ed.replaceAll(text) : ed.insert(text)) : false
    if (!ok) setSql((s) => (replace || !s ? text : `${s}${/\s$/.test(s) ? '' : ' '}${text}`))
    if (layout === 'phone') setPhoneTab('editor')
    if (layout === 'tablet') setExplorerOpen(false)
  }, [layout])

  const run = useCallback(async (override) => {
    const text = typeof override === 'string' ? override : sql
    if (!text?.trim() || runningRef.current) return
    runningRef.current = true
    setRunning(true)
    setElapsed(0)
    const t0 = Date.now()
    clearInterval(tick.current)
    tick.current = setInterval(() => setElapsed(Date.now() - t0), 100)
    let next
    try {
      const r = await api.admin.sqlExecute(text)
      next = r || { success: false, error: t('sql.err.noResponse') }
      loadHistory()
    } catch (e) {
      next = { success: false, error: e?.message || t('sql.err.noResponse') }
    } finally {
      clearInterval(tick.current)
      runningRef.current = false
      setRunning(false)
    }
    seq.current += 1
    setResult({ ...next, ranSql: text, seq: seq.current, finishedAt: Date.now() })
    if (layout === 'phone') setPhoneTab('results')
  }, [sql, layout, loadHistory, t])

  const runSelection = selection.trim() ? selection : null
  const runNow = () => run(runSelection ?? undefined)

  const revealError = useCallback((offset) => {
    const [a, b] = wordAt(sql, offset)
    if (layout === 'phone') setPhoneTab('editor')
    requestAnimationFrame(() => requestAnimationFrame(() => editorRef.current?.select(a, b)))
  }, [sql, layout])

  const openTableDetails = useCallback(async (name) => {
    setExplorerOpen(false)
    setTableDetail({ table: name, details: null, loading: true })
    try {
      const r = await api.admin.sqlTableDetails(name)
      setTableDetail((cur) => (cur?.table === name ? { table: name, details: r?.success ? r.data : null, loading: false } : cur))
      if (r?.success) {
        const pks = (r.data?.columns ?? []).filter((c) => c.is_pk).map((c) => c.column_name)
        if (pks.length) setPkMap((m) => ({ ...m, [name]: pks }))
      } else toast.error(r?.error || t('sql.td.loadError'))
    } catch {
      setTableDetail((cur) => (cur?.table === name ? { table: name, details: null, loading: false } : cur))
      toast.error(t('sql.td.loadError'))
    }
  }, [t, toast])

  const openDiagram = useCallback((focus = null) => {
    setExplorerOpen(false)
    setDiagram((d) => ({ focus, n: (d?.n ?? 0) + 1 }))
    if (!rel.data && !rel.loading) loadRelations()
  }, [rel.data, rel.loading, loadRelations])

  const queryTable = useCallback((name) => {
    setDiagram(null)
    setTableDetail(null)
    writeEditor(tableQuery(name), { replace: true })
  }, [writeEditor])

  const onCaret = useCallback((c) => setSelection(c.text || ''), [])

  // ── Parçalar ────────────────────────────────────────────────────────────────────
  const rules = t('sql.pg.rules', MAX_ROWS.toLocaleString(), QUERY_TIMEOUT_SEC)
  const meta = (
    <>
      <HintPopover content={rules} aria-label={t('sql.pg.readOnlyHint')}>
        <Badge variant="outline" className="gap-1 border-success/30 bg-success/10 font-medium text-success">
          <ShieldCheck aria-hidden="true" /> {t('sql.pg.readOnly')}
        </Badge>
      </HintPopover>
      {dbInfo?.version && (
        <Badge variant="outline" className="gap-1 font-normal" title={dbInfo.version_full || undefined}>
          <Database aria-hidden="true" /> {dbInfo.version}{dbInfo.database ? ` · ${dbInfo.database}` : ''}{dbInfo.size ? ` · ${dbInfo.size}` : ''}
        </Badge>
      )}
      <Badge variant="outline" className="gap-1 font-normal tabular-nums"><Table2 aria-hidden="true" /> {t('sql.pg.tablesCount', tables.length)}</Badge>
      <Badge variant="outline" className="gap-1 font-normal tabular-nums"><Gauge aria-hidden="true" /> {t('sql.pg.limits', MAX_ROWS.toLocaleString(), QUERY_TIMEOUT_SEC)}</Badge>
    </>
  )
  const actions = (
    <>
      <Button type="button" variant="outline" onClick={() => openDiagram()} className="pointer-coarse:h-10">
        <Network /> {t('sql.pg.diagram')}
      </Button>
      <Button type="button" variant="outline" onClick={() => writeEditor('', { replace: true })} disabled={!sql} className="pointer-coarse:h-10">
        <Eraser /> {t('sql.clear')}
      </Button>
      <Button type="button" onClick={runNow} disabled={running || !sql.trim()} aria-busy={running || undefined}
        aria-keyshortcuts={IS_MAC ? 'Meta+Enter' : 'Control+Enter'} className="pointer-coarse:h-10">
        {running ? <Spinner size={14} inline decorative /> : <Play />}
        {runSelection ? t('sql.runSelection') : t('sql.run')}
        <KbdGroup className="ml-1 hidden sm:inline-flex" aria-hidden="true">
          <Kbd className="bg-primary-foreground/15 text-primary-foreground">{IS_MAC ? '⌘' : 'Ctrl'}</Kbd>
          <Kbd className="bg-primary-foreground/15 text-primary-foreground">↵</Kbd>
        </KbdGroup>
      </Button>
    </>
  )

  const explorerProps = {
    tables, loading: tablesLoading, error: tablesError, onRefresh: () => { refreshTables(); loadRelations() },
    columnsMap, onLoadColumns: ensureColumns, fkMap, pkMap,
    onQuery: queryTable, onInsert: (text) => writeEditor(text), onDetails: openTableDetails, onDiagram: openDiagram,
  }

  const editorCard = (cls) => (
    <section data-slot="sql-editor-card" aria-label={t('sql.ed.title')}
      className={cn('flex min-h-0 min-w-0 flex-col overflow-hidden rounded-xl border bg-card text-card-foreground shadow-sm', cls)}>
      <div className="flex flex-wrap items-center gap-2 border-b px-3 py-2">
        <FileCode2 aria-hidden="true" className="size-4 text-muted-foreground" />
        <h3 className="text-sm font-semibold">{t('sql.ed.title')}</h3>
        {layout === 'tablet' && (
          <Button type="button" variant="outline" size="sm" onClick={() => setExplorerOpen(true)}>
            <PanelLeft /> {t('sql.schema')}
          </Button>
        )}
        <div className="flex w-full items-center gap-2 sm:ml-auto sm:w-auto">
          <QueryPicker kind="samples" items={samples} onPick={(s) => writeEditor(s, { replace: true })} className="flex-1 pointer-coarse:h-10 sm:flex-none" />
          <QueryPicker kind="history" items={history} onPick={(s) => writeEditor(s, { replace: true })} className="flex-1 pointer-coarse:h-10 sm:flex-none" />
        </div>
      </div>
      <SqlEditor ref={editorRef} value={sql} onChange={setSql} onRun={(sel) => run(sel ?? undefined)} onCaret={onCaret}
        label={t('sql.editorLabel')} placeholder={t('sql.editorPlaceholder')} className="min-h-0 flex-1"
        statusExtra={t('sql.ed.status', IS_MAC ? '⌘' : 'Ctrl')} />
    </section>
  )

  const resultsPanel = (cls, phone = false) => (
    <ResultsPanel result={result} running={running} elapsedMs={elapsed} phone={phone} samples={samples}
      onPickSample={(s) => writeEditor(s, { replace: true })} onRevealError={revealError} className={cls} />
  )

  let body
  if (layout === 'phone') {
    const resultCount = result && !result.error ? (result.rowCount ?? result.rows?.length ?? 0) : null
    body = (
      <Tabs value={phoneTab} onValueChange={setPhoneTab} className="gap-3">
        <TabsList aria-label={t('sql.pg.sections')} className="grid h-11 w-full grid-cols-3">
          <TabsTrigger value="editor" className="h-full"><FileCode2 /> {t('sql.ed.tab')}</TabsTrigger>
          <TabsTrigger value="results" className="h-full">
            <ListTree /> {t('sql.res.tab')}
            {resultCount != null && <Badge variant="secondary" className="h-4 rounded-full px-1.5 text-[10px] tabular-nums">{resultCount}</Badge>}
            {result?.error && <span aria-hidden="true" className="size-1.5 rounded-full bg-destructive" />}
          </TabsTrigger>
          <TabsTrigger value="schema" className="h-full"><TableProperties /> {t('sql.schema')}</TabsTrigger>
        </TabsList>
        <TabsContent value="editor" forceMount className="data-[state=inactive]:hidden">
          {editorCard('h-[55dvh] min-h-72')}
        </TabsContent>
        <TabsContent value="results" forceMount className="data-[state=inactive]:hidden">
          {resultsPanel('', true)}
        </TabsContent>
        <TabsContent value="schema" forceMount className="data-[state=inactive]:hidden">
          <SchemaExplorer {...explorerProps} className="h-[70dvh]" />
        </TabsContent>
      </Tabs>
    )
  } else if (layout === 'tablet') {
    body = (
      <div className="flex min-w-0 flex-col gap-3">
        {editorCard('h-80')}
        {resultsPanel('max-h-[80dvh] min-h-80')}
        <Sheet open={explorerOpen} onOpenChange={setExplorerOpen}>
          <SheetContent side="left" className="w-[min(24rem,90vw)] gap-0 p-0 sm:max-w-sm">
            <SheetHeader className="border-b pr-12">
              <SheetTitle>{t('sql.schema')}</SheetTitle>
              <SheetDescription className="text-xs">{t('sql.ex.sheetHint')}</SheetDescription>
            </SheetHeader>
            <SchemaExplorer {...explorerProps} headerless className="min-h-0 flex-1 rounded-none border-0 shadow-none" />
          </SheetContent>
        </Sheet>
      </div>
    )
  } else {
    body = (
      <div className="grid h-[calc(100dvh-14.5rem)] min-h-[36rem] grid-cols-[18rem_minmax(0,1fr)] gap-3 xl:grid-cols-[20rem_minmax(0,1fr)]">
        <SchemaExplorer {...explorerProps} />
        <div className="flex min-h-0 min-w-0 flex-col gap-3">
          {editorCard('h-[36%] min-h-44 shrink-0')}
          {resultsPanel('min-h-0 flex-1')}
        </div>
      </div>
    )
  }

  return (
    <div data-slot="sql-playground" data-layout={layout} className="flex min-w-0 flex-col">
      <PageHeader icon={Database} title={t('app.sqlPlaygroundTitle')} description={t('sql.pg.desc')} meta={meta} actions={actions}
        className="mb-3" />
      {body}

      {tableDetail && (
        <TableDetailsModal table={tableDetail.table} details={tableDetail.details} loading={tableDetail.loading}
          onClose={() => setTableDetail(null)} onOpenTable={openTableDetails}
          onUseQuery={(q) => { setDiagram(null); writeEditor(q, { replace: true }) }}
          onShowInDiagram={(name) => { setTableDetail(null); openDiagram(name) }} />
      )}

      {diagram && (
        <SchemaDiagramModal data={rel.data} loading={rel.loading} error={rel.error} onRetry={loadRelations}
          onClose={() => setDiagram(null)} tables={tables} columnsMap={columnsMap} ensureColumns={ensureColumns}
          initialFocus={diagram.focus} focusRequest={diagram}
          onOpenDetails={openTableDetails} onQuery={queryTable} />
      )}
    </div>
  )
}
