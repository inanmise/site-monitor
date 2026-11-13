import { Suspense, lazy, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  BookMarked, BookmarkPlus, Database, DatabaseZap, Eraser, FileCode2, Gauge, History, ListTree, Network, OctagonX, PanelLeft, Play,
  ScanSearch, ShieldCheck, Table2, TableProperties, WandSparkles,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useIsMobile } from '@/hooks/use-mobile'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { runWithConcurrency } from '../../utils/concurrentQueue.js'
import { navigateTo } from '../../utils/navigate.js'
import { clientFailure } from '../../utils/errorMessages.js'
import { writeDbQueryDraft } from '../../utils/sqlDbQueryDraft.js'
import { useDialog } from '../ui/Dialog.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import PageHeader from '../ui/PageHeader.jsx'
import { LoadingBlock, Spinner } from '../ui/Progress.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Kbd, KbdGroup } from '@/components/shadcn/kbd'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'
import SchemaDiagramModal from './SchemaDiagramModal.jsx'
import TableDetailsModal from './TableDetailsModal.jsx'
import CompareView from './sql/CompareView.jsx'
import HistoryPanel from './sql/HistoryPanel.jsx'
import ParamsForm from './sql/ParamsForm.jsx'
import PlanView from './sql/PlanView.jsx'
import QueryPicker from './sql/QueryPicker.jsx'
import ResultsPanel from './sql/ResultsPanel.jsx'
import SaveQueryDialog from './sql/SaveQueryDialog.jsx'
import SavedQueriesPanel from './sql/SavedQueriesPanel.jsx'
import SchemaExplorer from './sql/SchemaExplorer.jsx'
import SqlEditor from './sql/SqlEditor.jsx'
import SqlTabsBar, { tabTitle } from './sql/SqlTabsBar.jsx'
import { pushRun, snapshotRun } from './sql/compareModel.js'
import { parseAliases } from './sql/sqlComplete.js'
import { paramTypesOf, savedIdFromSearch, unwrapExportJson } from './sql/sqlLibraryModel.js'
import { MAX_PARAMS, buildParamsBody, extractParams, paramErrorsFromResponse, syncParamState } from './sql/sqlParams.js'
import {
  MAX_TABS, addTab, closeOthers, closeTab, duplicateTab, findSavedTab, initialTabs, loadTabs, saveTabs, updateTab,
} from './sql/sqlTabs.js'
import { MAX_ROWS, QUERY_TIMEOUT_SEC, columnTypes, resultColumns, tableQuery, wordAt } from './sql/sqlUtils.js'

const ResultChart = lazy(() => import('./sql/ResultChart.jsx'))

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

/** İstemci çalıştırma kimliği (sunucu kuralı `[A-Za-z0-9-]{8,64}`). */
function newRunId() {
  try { if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID() } catch { /* yok */ }
  return `run-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

/** Eski sunucu: uç yok (404 / 405). */
const isOldServer = (r) => r?.status === 404 || r?.status === 405

/**
 * SQL Playground (Yönetim → SQL Playground, yalnız global yönetici). 2026-09-27 shadcn yeniden tasarımı + 2026-11-13
 * zenginleştirme (16 öğe, sözleşme: scratchpad `sql-playground-api.md`):
 *
 * - Çoklu sekme + kişisel taslak (B3, `sql/sqlTabs.js`, `sm.sql.tabs:<kullanıcı>` — çıkışta silinir), her sekmenin sonucu /
 *   planı / son 5 çalıştırması bellekte. Otomatik tamamlama (B1) ve biçimlendir (B2, Ctrl/⌘+Shift+F) düzenleyicide.
 * - `:ad` parametre formu (B4) — sunucu JDBC ile türüyle bağlar; değerler yalnız sekmede.
 * - Çalıştır ⇄ Durdur (A2, `run_id` + `/cancel`), maskeli kolonlar (A1), Excel + sunucuda tam dışa aktarım (A3 / C3),
 *   plan (C1, EXPLAIN / ANALYZE), sonuçtan grafik (C2, tembel), karşılaştırma (C4) — sonuç panelinin görünüm seçicisinde.
 * - Geçmiş paneli (A4), kayıtlı sorgular + `?sq=<id>` bağlantısı (D1), kategorili örnekler (D2), veri sözlüğü (D3),
 *   "Veritabanı sorgu izlemesi oluştur" (D4, `utils/sqlDbQueryDraft`).
 * Eski sunucu (yeni uç / alan yok, 404 / 405) → özellik nötr gizlenir ya da "bu sürümde yok" der; asla kırılmaz.
 * Arka uç salt okunur (tek SELECT/WITH, kara liste, JDBC read-only bağlantı, 30 sn, 1000 satır) — DEĞİŞMEDİ.
 */
export default function SqlPlayground() {
  const t = useT()
  const toast = useToast()
  const { showConfirm, showPrompt } = useDialog()
  const layout = useLayoutMode()
  const { canEdit } = usePermissions()

  const [tables, setTables] = useState([])
  const [tablesLoading, setTablesLoading] = useState(false)
  const [tablesError, setTablesError] = useState(null)
  const [columnsMap, setColumnsMap] = useState({})
  const [pkMap, setPkMap] = useState({})
  const [rel, setRel] = useState({ data: null, loading: false, error: null })
  const [dbInfo, setDbInfo] = useState(null)
  const [samples, setSamples] = useState([])
  const [history, setHistory] = useState([])
  const [dictionary, setDictionary] = useState(null)

  // ── Sekmeler (kalıcı taslak) + sekme başına çalışma durumu (bellek) ──
  const [tabsState, setTabsState] = useState(() => loadTabs() ?? initialTabs())
  const [rt, setRt] = useState({})   // { [tabId]: { result, runs, plan, view, paramErrors } }
  const [draftWarn, setDraftWarn] = useState(false)
  const tabsRef = useRef(tabsState)
  tabsRef.current = tabsState
  const activeTab = tabsState.tabs.find((x) => x.id === tabsState.active) ?? tabsState.tabs[0]
  const activeRef = useRef(activeTab)
  activeRef.current = activeTab
  const sql = activeTab.sql
  const art = rt[activeTab.id] || {}
  const setRtTab = useCallback((id, patch) => setRt((prev) => ({ ...prev, [id]: { ...(prev[id] || {}), ...(typeof patch === 'function' ? patch(prev[id] || {}) : patch) } })), [])

  useEffect(() => {
    const h = setTimeout(() => { setDraftWarn(!saveTabs(tabsState).ok) }, 400)
    return () => clearTimeout(h)
  }, [tabsState])

  const setSql = useCallback((v) => setTabsState((s) => updateTab(s, s.active, (tb) => (tb.sql === v ? tb : { ...tb, sql: v }))), [])
  const paramNames = useMemo(() => extractParams(sql), [sql])
  useEffect(() => {
    setTabsState((s) => updateTab(s, s.active, (tb) => {
      const p = syncParamState(paramNames, tb.params)
      return p === tb.params ? tb : { ...tb, params: p }
    }))
  }, [paramNames])
  const setParam = useCallback((name, value) => {
    setTabsState((s) => updateTab(s, s.active, (tb) => ({ ...tb, params: { ...tb.params, [name]: value } })))
    setRt((prev) => {
      const cur = prev[activeRef.current.id]
      if (!cur?.paramErrors?.[`params.${name}`]) return prev
      return { ...prev, [activeRef.current.id]: { ...cur, paramErrors: { ...cur.paramErrors, [`params.${name}`]: undefined } } }
    })
  }, [])

  const [selection, setSelection] = useState('')
  const [running, setRunning] = useState(null)   // { tabId, runId, kind: 'execute' | 'explain' }
  const [stopping, setStopping] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const [exportState, setExportState] = useState(null)   // { busy, format, runId }
  const seq = useRef(0)
  const runningRef = useRef(false)
  const tick = useRef(null)
  useEffect(() => () => clearInterval(tick.current), [])

  const [tableDetail, setTableDetail] = useState(null)   // { table, details, loading }
  const [diagram, setDiagram] = useState(null)           // { focus, n }
  const [phoneTab, setPhoneTab] = useState('editor')
  const [explorerOpen, setExplorerOpen] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [savedOpen, setSavedOpen] = useState(false)
  const [savedSupported, setSavedSupported] = useState(true)
  const [savedReload, setSavedReload] = useState(0)
  const [saveDlg, setSaveDlg] = useState(null)   // { mode, base, sql, paramTypes, tabId }
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

  const openSavedRow = useCallback((row) => {
    if (!row || row.sql_text == null) return
    const cur = tabsRef.current
    const existing = findSavedTab(cur, row.id)
    if (!existing && cur.tabs.length >= MAX_TABS) { toast.error(t('sql.tabs.full', MAX_TABS)); return }
    const params = {}
    if (row.params && typeof row.params === 'object') for (const [k, v] of Object.entries(row.params)) params[k] = { value: '', type: typeof v === 'string' ? v : v?.type || 'text' }
    setTabsState((st) => {
      const ex = findSavedTab(st, row.id)
      if (ex) return { ...st, active: ex.id }
      return addTab(st, { sql: row.sql_text, title: row.title, savedId: row.id, savedVersion: row.version, savedSql: row.sql_text, savedMine: row.mine ?? null, params })
    })
    setSavedOpen(false)
    if (layout === 'phone') setPhoneTab('editor')
  }, [layout, t, toast])

  useEffect(() => {
    refreshTables()
    loadRelations()
    loadHistory()
    api.admin.sqlSamples().then((r) => { if (r?.success) setSamples(r.data ?? []) }).catch(() => {})
    // Veritabanı sürümü/adı — ayrı izin (settings.database); yoksa rozet çizilmez, hata gösterilmez.
    api.admin.getDatabaseInfo?.().then((r) => { if (r?.success && r.data && !Array.isArray(r.data)) setDbInfo(r.data) }).catch(() => {})
    // Veri sözlüğü (D3) — uç yoksa (eski sunucu) sessizce gizli
    api.admin.sqlDictionary?.().then((r) => {
      const tablesDict = r?.success ? (r.tables ?? r.data?.tables ?? null) : null
      if (tablesDict && typeof tablesDict === 'object') setDictionary({ tables: tablesDict })
    }).catch(() => {})
    // Kayıtlı sorgu bağlantısı (?tab=sqlplayground&sq=<id>) — bir kez okunur, URL'den silinir, yeni sekmede açılır
    const sq = savedIdFromSearch(typeof window !== 'undefined' ? window.location.search : '')
    if (sq) {
      try {
        const url = new URL(window.location.href)
        url.searchParams.delete('sq')
        window.history.replaceState(window.history.state, '', url.toString())
      } catch { /* yok */ }
      // 404: kayıt yok / başkasının paylaşılmamış kaydı — ya da eski sunucu (uç yok); ikisi de "açılamadı"
      api.admin.sqlSavedGet?.(sq).then((r) => {
        if (r?.success) openSavedRow(r.data ?? r)
        else toast.error(isOldServer(r) ? t('sql.saved.notFound') : (r?.error || t('sql.saved.notFound')))
      }).catch((e) => toast.error(e?.message || t('sql.saved.notFound')))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** Kolon önbelleği: eksikler en fazla 6 eşzamanlı istekle, sonuç TEK durum güncellemesiyle. */
  const columnsRef = useRef(columnsMap)
  columnsRef.current = columnsMap
  const pendingCols = useRef(new Set())
  const ensureColumns = useCallback(async (names) => {
    const known = new Set((tables || []).map((x) => x.table_name))
    const need = [...new Set(names)].filter((n) => !columnsRef.current[n] && !pendingCols.current.has(n) && (!known.size || known.has(n)))
    if (!need.length) return
    need.forEach((n) => pendingCols.current.add(n))
    const got = {}
    await runWithConcurrency(need, async (n) => {
      try {
        const r = await api.admin.sqlListColumns(n)
        if (r?.success) got[n] = r.data ?? []
      } catch { /* tek tablo düşerse diğerleri sürer */ }
    }, { limit: 6 })
    need.forEach((n) => pendingCols.current.delete(n))
    if (Object.keys(got).length) setColumnsMap((m) => ({ ...m, ...got }))
  }, [tables])

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
    if (!ok) setSql(replace || !activeRef.current.sql ? text : `${activeRef.current.sql}${/\s$/.test(activeRef.current.sql) ? '' : ' '}${text}`)
    if (layout === 'phone') setPhoneTab('editor')
    if (layout === 'tablet') setExplorerOpen(false)
  }, [layout, setSql])

  /** Parametre denetimi; hatada alanları işaretler ve null döner. */
  const checkParams = useCallback((tabId, text, params) => {
    const names = extractParams(text)
    const pb = buildParamsBody(names, params)
    if (pb.tooMany) { toast.error(t('sql.param.tooMany', MAX_PARAMS)); return null }
    if (pb.errors) {
      setRtTab(tabId, { paramErrors: pb.errors })
      toast.error(t('sql.param.fixFirst'))
      if (layout === 'phone') setPhoneTab('editor')
      return null
    }
    return { body: pb.body }
  }, [layout, setRtTab, t, toast])

  const startTimer = () => {
    setElapsed(0)
    const t0 = Date.now()
    clearInterval(tick.current)
    tick.current = setInterval(() => setElapsed(Date.now() - t0), 100)
  }

  const run = useCallback(async (override) => {
    const tab = activeRef.current
    const text = typeof override === 'string' ? override : tab.sql
    if (!text?.trim() || runningRef.current) return
    const checked = checkParams(tab.id, text, tab.params)
    if (!checked) return
    const runId = newRunId()
    runningRef.current = true
    setRunning({ tabId: tab.id, runId, kind: 'execute' })
    setRtTab(tab.id, { view: 'table', paramErrors: null })
    startTimer()
    let next
    try {
      const r = await api.admin.sqlExecute(text, { runId, params: checked.body })
      next = r || { success: false, error: t('sql.err.noResponse') }
      loadHistory()
    } catch (e) {
      next = { success: false, error: e?.message || t('sql.err.noResponse') }
    } finally {
      clearInterval(tick.current)
      runningRef.current = false
      setRunning(null)
      setStopping(false)
    }
    seq.current += 1
    const res = { ...next, ranSql: text, seq: seq.current, finishedAt: Date.now(), params: checked.body }
    const perr = paramErrorsFromResponse(next)
    setRtTab(tab.id, (cur) => ({ result: res, runs: pushRun(cur.runs, snapshotRun(res)), paramErrors: perr }))
    if (layout === 'phone') setPhoneTab('results')
  }, [layout, loadHistory, t, checkParams, setRtTab])

  const stop = useCallback(async () => {
    const r = running
    if (!r?.runId) return
    setStopping(true)
    // İptal kabul edildiyse "durduruluyor" çalıştırma bitene dek kalır (run'ın finally'si temizler); aksi her yolda temizlenir
    let accepted = false
    try {
      const res = await api.admin.sqlCancel(r.runId)
      accepted = Boolean(res?.success && res.cancelled)
      if (accepted) toast.info(t('sql.stop.requested'))
      else if (res?.success) toast.info(t(res.reason === 'other_pod_unreachable' ? 'sql.stop.otherPod' : 'sql.stop.alreadyDone'))
      else if (isOldServer(res)) toast.info(t('sql.stop.unsupported'))
      else toast.error(res?.error || t('sql.stop.failed'))
    } catch (e) {
      toast.error(e?.message || t('sql.stop.failed'))
    } finally {
      if (!accepted || !runningRef.current) setStopping(false)
    }
  }, [running, t, toast])

  const explain = useCallback(async (analyze = false) => {
    const tab = activeRef.current
    const text = selection.trim() ? selection : tab.sql
    if (!text?.trim() || runningRef.current) return
    if (analyze) {
      const ok = await showConfirm({ title: t('sql.plan.analyzeConfirmTitle'), message: t('sql.plan.analyzeConfirm'), variant: 'warning', confirmText: t('sql.plan.analyze') })
      if (!ok) return
    }
    const checked = checkParams(tab.id, text, tab.params)
    if (!checked) return
    const runId = newRunId()
    runningRef.current = true
    setRunning({ tabId: tab.id, runId, kind: 'explain' })
    setRtTab(tab.id, { view: 'plan', plan: { loading: true }, paramErrors: null })
    startTimer()
    if (layout === 'phone') setPhoneTab('results')
    let plan
    let perr = null
    try {
      const r = await api.admin.sqlExplain(text, { runId, params: checked.body, analyze })
      // 200 + ok:false = EXPLAIN sorgusu veritabanında düştü ya da durduruldu (execute ile aynı biçim)
      if (r?.success && r.ok === false) plan = r.cancelled ? { cancelled: true } : { error: r.error || t('sql.plan.failed') }
      else if (r?.success) plan = { data: { plan: r.plan ?? r.data?.plan, summary: r.summary ?? r.data?.summary, analyzed: Boolean(r.analyzed ?? analyze) } }
      else if ((r?.code ?? r?.errorInfo?.code) === 'SQL_EXPLAIN_UNSUPPORTED') plan = { unsupported: true, message: r.error }
      else if (isOldServer(r)) plan = { unsupported: true, message: t('sql.plan.oldServer') }
      else { plan = { error: r?.error || t('sql.plan.failed') }; perr = paramErrorsFromResponse(r) }
    } catch (e) {
      plan = { error: e?.message || t('sql.plan.failed') }
    } finally {
      clearInterval(tick.current)
      runningRef.current = false
      setRunning(null)
      setStopping(false)
    }
    setRtTab(tab.id, { plan, paramErrors: perr })
  }, [selection, checkParams, setRtTab, layout, showConfirm, t])

  const serverExport = useCallback(async (format) => {
    const tab = activeRef.current
    const res = rt[tab.id]?.result
    if (!res?.ranSql || exportState) return
    const runId = newRunId()
    setExportState({ busy: true, format, runId })
    try {
      const r = await api.admin.sqlExport(res.ranSql, { runId, params: res.params, format: format === 'csv' ? 'csv' : 'json' })
      if (!r?.success) {
        if (r?.code === 'ABORTED') return
        if (r?.code === 'SQL_CANCELLED') { toast.info(t('sql.exp.cancelled')); return }
        if (isOldServer(r)) toast.error(t('sql.exp.unsupported'))
        else toast.error(r?.error || t('sql.exp.failed'), { details: r })
        return
      }
      const note = r.truncated ? ` ${t('sql.exp.truncated', (r.maxRows ?? '').toLocaleString?.() ?? r.maxRows)}` : ''
      if (format === 'csv') { toast.success(`${t('sql.exp.done', 'CSV', r.rows ?? '—')}${note}`); return }
      const body = unwrapExportJson(r.json)
      if (!body) { toast.error(t('sql.exp.failed')); return }
      const cols = body.columns ?? resultColumns(body.rows)
      const { downloadSqlExcel } = await import('./sql/sqlExcelExport.js')
      if (!downloadSqlExcel(body.rows, cols, columnTypes(body.rows, cols), 'sql')) { toast.error(t('sql.res.downloadFailed')); return }
      const renamedNote = body.renamed?.length
        ? ` ${t('sql.exp.dupRenamed', body.renamed.slice(0, 5).map((x) => `${x.from} → ${x.to}`).join(', ') + (body.renamed.length > 5 ? ` +${body.renamed.length - 5}` : ''))}`
        : ''
      toast.success(`${t('sql.exp.done', 'Excel', body.rows.length)}${note}${renamedNote}`)
    } catch (e) {
      const f = clientFailure(e, { fallback: t('sql.exp.failed'), chunk: t('te.pageUpdated') })
      toast.error(f.message, { details: f.details })
    } finally {
      setExportState(null)
    }
  }, [rt, exportState, t, toast])

  const cancelExport = useCallback(() => {
    if (!exportState?.runId) return
    api.admin.sqlCancel(exportState.runId).then((res) => {
      if (res?.success && res.cancelled) toast.info(t('sql.stop.requested'))
      else if (isOldServer(res)) toast.info(t('sql.stop.unsupported'))
    }).catch(() => {})
  }, [exportState, t, toast])

  const runSelection = selection.trim() ? selection : null
  const runNow = () => run(runSelection ?? undefined)

  const revealError = useCallback((offset) => {
    const [a, b] = wordAt(activeRef.current.sql, offset)
    if (layout === 'phone') setPhoneTab('editor')
    requestAnimationFrame(() => requestAnimationFrame(() => editorRef.current?.select(a, b)))
  }, [layout])

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

  // ── Sekme işlemleri ──
  const addNewTab = useCallback((init = {}) => {
    if (tabsRef.current.tabs.length >= MAX_TABS) { toast.error(t('sql.tabs.full', MAX_TABS)); return }
    setTabsState((s) => addTab(s, init))
    setSelection('')
    if (layout === 'phone') setPhoneTab('editor')
  }, [layout, t, toast])
  const closeOneTab = useCallback(async (id) => {
    const tab = tabsState.tabs.find((x) => x.id === id)
    if (!tab) return
    const unsavedText = tab.sql.trim() && (tab.savedId == null || tab.sql !== tab.savedSql)
    if (unsavedText) {
      const ok = await showConfirm({ title: t('sql.tabs.closeTitle'), message: t('sql.tabs.closeMsg', tabTitle(t, tab)), confirmText: t('sql.tabs.closeConfirm') })
      if (!ok) return
    }
    setTabsState((s) => closeTab(s, id))
    setRt((prev) => { const n = { ...prev }; delete n[id]; return n })
  }, [tabsState.tabs, showConfirm, t])
  const renameTab = useCallback(async (id) => {
    const tab = tabsState.tabs.find((x) => x.id === id)
    const name = await showPrompt({ title: t('sql.tabs.renameTitle'), defaultValue: tab?.title || '', placeholder: t('sql.tabs.untitled', tab?.n ?? 1), confirmText: t('sql.tabs.renameBtn') })
    if (name == null) return
    setTabsState((s) => updateTab(s, id, { title: String(name).trim().slice(0, 120) }))
  }, [tabsState.tabs, showPrompt, t])
  const closeOtherTabs = useCallback(async (id) => {
    const others = tabsState.tabs.filter((x) => x.id !== id && x.sql.trim())
    if (others.length) {
      const ok = await showConfirm({ title: t('sql.tabs.closeOthersTitle'), message: t('sql.tabs.closeOthersMsg', others.length), confirmText: t('sql.tabs.closeConfirm') })
      if (!ok) return
    }
    setTabsState((s) => closeOthers(s, id))
    setRt((prev) => (prev[id] ? { [id]: prev[id] } : {}))
  }, [tabsState.tabs, showConfirm, t])

  // ── Kayıtlı sorgular ──
  const openSave = useCallback(async (mode) => {
    const tab = activeRef.current
    if (!tab.sql.trim()) { toast.error(t('sql.saved.err.sqlRequired')); return }
    const types = paramTypesOf(extractParams(tab.sql), tab.params)
    if (mode === 'update' && tab.savedId != null && tab.savedMine !== false) {
      const r = await api.admin.sqlSavedGet(tab.savedId)
      if (r?.success) { setSaveDlg({ mode: 'update', base: r.data ?? r, sql: tab.sql, paramTypes: types, tabId: tab.id }); return }
      // Kayıt artık yok / erişilemiyor → yeni kayıt olarak kaydetme penceresi (bağ kopar)
      toast.info(t('sql.saved.linkLost'))
      setTabsState((s) => updateTab(s, tab.id, { savedId: null, savedVersion: null, savedSql: null, savedMine: null }))
    }
    setSaveDlg({ mode: 'create', base: null, sql: tab.sql, paramTypes: types, tabId: tab.id })
  }, [t, toast])
  const onSaved = useCallback((row) => {
    const dlg = saveDlg
    setSavedReload((n) => n + 1)
    if (!dlg?.tabId || !row?.id) return
    setTabsState((s) => updateTab(s, dlg.tabId, (tb) => ({
      ...tb, savedId: row.id, savedVersion: row.version ?? null, savedSql: row.sql_text ?? dlg.sql, savedMine: true,
      title: row.title ?? tb.title,
    })))
  }, [saveDlg])

  const editSavedRow = useCallback((row) => {
    setSaveDlg({ mode: 'update', base: row, sql: row.sql_text, paramTypes: null, tabId: findSavedTab(tabsRef.current, row.id)?.id ?? null })
  }, [])
  const markSavedUnsupported = useCallback(() => setSavedSupported(false), [])

  // ── D4 ──
  const canCreateMonitor = canEdit('monitoring.crud')
  const toDbQuery = useCallback(() => {
    const text = (runSelection ?? activeRef.current.sql).trim()
    if (!text) return
    if (extractParams(text).length) { toast.error(t('sql.dq.params')); return }
    if (!writeDbQueryDraft(text)) { toast.error(t('sql.dq.failed')); return }
    navigateTo('dbquery')
  }, [runSelection, t, toast])

  const completion = useMemo(() => ({ tables, columnsMap, onNeedColumns: ensureColumns }), [tables, columnsMap, ensureColumns])

  const pkCols = useMemo(() => {
    const ran = art.result?.ranSql
    if (!ran) return []
    const tbls = [...new Set(parseAliases(ran).values())]
    return tbls.length === 1 ? (pkMap[tbls[0]] || []) : []
  }, [art.result, pkMap])

  const isRunningHere = running?.tabId === activeTab.id && running?.kind === 'execute'

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
      {running ? (
        <Button type="button" variant="destructive" onClick={stop} disabled={stopping} aria-busy={stopping || undefined}
          data-slot="sql-stop" className="pointer-coarse:h-10">
          {stopping ? <Spinner size={14} inline decorative /> : <OctagonX />}
          {stopping ? t('sql.stop.stopping') : t('sql.stop.label')}
        </Button>
      ) : (
        <Button type="button" onClick={runNow} disabled={!sql.trim()}
          aria-keyshortcuts={IS_MAC ? 'Meta+Enter' : 'Control+Enter'} className="pointer-coarse:h-10">
          <Play />
          {runSelection ? t('sql.runSelection') : t('sql.run')}
          <KbdGroup className="ml-1 hidden sm:inline-flex" aria-hidden="true">
            <Kbd className="bg-primary-foreground/15 text-primary-foreground">{IS_MAC ? '⌘' : 'Ctrl'}</Kbd>
            <Kbd className="bg-primary-foreground/15 text-primary-foreground">↵</Kbd>
          </KbdGroup>
        </Button>
      )}
    </>
  )

  const explorerProps = {
    tables, loading: tablesLoading, error: tablesError, onRefresh: () => { refreshTables(); loadRelations() },
    columnsMap, onLoadColumns: ensureColumns, fkMap, pkMap, dictionary,
    onQuery: queryTable, onInsert: (text) => writeEditor(text), onDetails: openTableDetails, onDiagram: openDiagram,
  }

  const moreItems = [
    { label: t('sql.saved.saveAs'), icon: <BookmarkPlus />, onClick: () => openSave('create'), hidden: !savedSupported },
    { label: t('sql.hist.open'), icon: <History />, onClick: () => setHistoryOpen(true) },
    { label: t('sql.plan.run'), icon: <ScanSearch />, onClick: () => explain(false), disabled: !sql.trim() || Boolean(running) },
    { label: t('sql.plan.analyze'), icon: <ScanSearch />, onClick: () => explain(true), disabled: !sql.trim() || Boolean(running) },
    { label: t('sql.dq.create'), icon: <DatabaseZap />, onClick: toDbQuery, hidden: !canCreateMonitor, disabled: !sql.trim() },
  ]

  const tabsBar = (
    <SqlTabsBar state={tabsState} running={running?.tabId} onSelect={(id) => { setTabsState((s) => ({ ...s, active: id })); setSelection('') }}
      onAdd={() => addNewTab()} onClose={closeOneTab} onRename={renameTab}
      onDuplicate={(id) => setTabsState((s) => duplicateTab(s, id))} onCloseOthers={closeOtherTabs} />
  )

  const editorCard = (cls) => (
    <section id="sql-editor-card" data-slot="sql-editor-card" aria-label={t('sql.ed.title')}
      className={cn('flex min-h-0 min-w-0 flex-col overflow-hidden rounded-xl border bg-card text-card-foreground shadow-sm', cls)}>
      <div className="flex flex-wrap items-center gap-2 border-b px-3 py-2">
        <FileCode2 aria-hidden="true" className="size-4 text-muted-foreground" />
        <h3 className="text-sm font-semibold">{t('sql.ed.title')}</h3>
        {layout === 'tablet' && (
          <Button type="button" variant="outline" size="sm" onClick={() => setExplorerOpen(true)} className="pointer-coarse:h-10">
            <PanelLeft /> {t('sql.schema')}
          </Button>
        )}
        {/* Telefonda iki sütunlu ızgara (6 eylem tek satıra sıkışıp etiketleri 0 genişliğe inmesin); sm ve üstünde satır */}
        <div data-slot="sql-editor-actions" className="grid w-full grid-cols-2 gap-2 sm:ml-auto sm:flex sm:w-auto sm:flex-wrap sm:items-center">
          <QueryPicker kind="samples" items={samples} onPick={(s) => writeEditor(s, { replace: true })} className="w-full pointer-coarse:min-h-10 sm:w-auto" />
          <QueryPicker kind="history" items={history} onPick={(s) => writeEditor(s, { replace: true })} className="w-full pointer-coarse:min-h-10 sm:w-auto" />
          <Button type="button" variant="outline" size="sm" onClick={() => editorRef.current?.format()} disabled={!sql.trim()}
            data-slot="sql-format" aria-keyshortcuts={IS_MAC ? 'Meta+Shift+F' : 'Control+Shift+F'} className="w-full pointer-coarse:h-10 sm:w-auto">
            <WandSparkles /> {t('sql.fmt.label')}
          </Button>
          {savedSupported && (
            <>
              <Button type="button" variant="outline" size="sm" onClick={() => setSavedOpen(true)} data-slot="sql-saved-open" className="w-full pointer-coarse:h-10 sm:w-auto">
                <BookMarked /> {t('sql.saved.short')}
              </Button>
              <Button type="button" variant="outline" size="sm" onClick={() => openSave('update')} disabled={!sql.trim()} data-slot="sql-save"
                className="w-full pointer-coarse:h-10 sm:w-auto">
                <BookmarkPlus /> {activeTab.savedId != null && activeTab.savedMine !== false ? t('sql.saved.saveShort') : t('sql.saved.saveNew')}
              </Button>
            </>
          )}
          <KebabMenu variant="labeled" label={t('sql.more')} items={moreItems} />
        </div>
      </div>
      <SqlEditor key={activeTab.id} ref={editorRef} value={sql} onChange={setSql} onRun={(sel) => run(sel ?? undefined)} onCaret={onCaret}
        label={t('sql.editorLabel')} placeholder={t('sql.editorPlaceholder')} className="min-h-0 flex-1"
        statusExtra={t('sql.ed.status2', IS_MAC ? '⌘' : 'Ctrl')} completion={completion}
        onFormatted={(changed) => { if (!changed) toast.info(t('sql.fmt.unchanged')) }} />
      {draftWarn && (
        <p data-slot="sql-draft-warn" role="status" className="m-0 border-t bg-amber-500/10 px-3 py-1.5 text-xs text-amber-800 dark:text-amber-200">
          {t('sql.tabs.draftTooLarge')}
        </p>
      )}
    </section>
  )

  const paramsForm = paramNames.length > 0 && (
    <ParamsForm names={paramNames} state={activeTab.params} errors={art.paramErrors} onChange={setParam}
      className="max-h-64 shrink-0 overflow-y-auto" />
  )

  const resultRows = art.result && !art.result.error && !art.result.cancelled && Array.isArray(art.result.rows) ? art.result.rows : []
  const resultsPanel = (cls, phone = false) => (
    <ResultsPanel result={art.result} running={isRunningHere} elapsedMs={elapsed} phone={phone} samples={samples}
      onPickSample={(s) => writeEditor(s, { replace: true })} onRevealError={revealError} className={cls}
      resultView={art.view ?? 'table'} onViewChange={(v) => setRtTab(activeTab.id, { view: v })}
      onStop={stop} stopping={stopping}
      chartSlot={resultRows.length ? (
        <Suspense fallback={<LoadingBlock label={t('sql.chart.loading')} size={18} />}>
          <ResultChart key={art.result?.seq} rows={resultRows} masked={art.result?.masked_columns} />
        </Suspense>
      ) : (
        <div data-slot="sql-chart-none" className="p-3 text-sm text-muted-foreground">{t('sql.chart.needRows')}</div>
      )}
      planSlot={<PlanView state={art.plan} onExplain={explain} canRun={Boolean(sql.trim()) && !running} />}
      compareSlot={<CompareView key={activeTab.id} runs={art.runs} pkCols={pkCols} />}
      onServerExport={resultRows.length ? serverExport : null} serverExport={exportState} onCancelExport={cancelExport} />
  )

  let body
  if (layout === 'phone') {
    const resultCount = art.result && !art.result.error && !art.result.cancelled ? (art.result.rowCount ?? art.result.rows?.length ?? 0) : null
    body = (
      <Tabs value={phoneTab} onValueChange={setPhoneTab} className="gap-3">
        <TabsList aria-label={t('sql.pg.sections')} className="grid h-11 w-full grid-cols-3">
          <TabsTrigger value="editor" className="h-full"><FileCode2 /> {t('sql.ed.tab')}</TabsTrigger>
          <TabsTrigger value="results" className="h-full">
            <ListTree /> {t('sql.res.tab')}
            {resultCount != null && <Badge variant="secondary" className="h-4 rounded-full px-1.5 text-[10px] tabular-nums">{resultCount}</Badge>}
            {art.result?.error && !art.result?.cancelled && <span aria-hidden="true" className="size-1.5 rounded-full bg-destructive" />}
          </TabsTrigger>
          <TabsTrigger value="schema" className="h-full"><TableProperties /> {t('sql.schema')}</TabsTrigger>
        </TabsList>
        <TabsContent value="editor" forceMount className="flex flex-col gap-3 data-[state=inactive]:hidden">
          {tabsBar}
          {editorCard('h-[55dvh] min-h-72')}
          {paramsForm}
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
        {tabsBar}
        {editorCard('h-80')}
        {paramsForm}
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
      <div className="grid h-[calc(100dvh-14.5rem)] min-h-[40rem] grid-cols-[18rem_minmax(0,1fr)] gap-3 xl:grid-cols-[20rem_minmax(0,1fr)]">
        <SchemaExplorer {...explorerProps} />
        <div className="flex min-h-0 min-w-0 flex-col gap-3">
          {tabsBar}
          {editorCard('h-[36%] min-h-44 shrink-0')}
          {paramsForm}
          {resultsPanel('min-h-56 flex-1')}
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
          dictionary={dictionary?.tables?.[tableDetail.table] ?? null}
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

      <HistoryPanel open={historyOpen} onOpenChange={setHistoryOpen}
        onUse={(s) => { setHistoryOpen(false); writeEditor(s, { replace: true }) }}
        onOpenTab={(s) => { setHistoryOpen(false); addNewTab({ sql: s }) }}
        onRun={(s) => { setHistoryOpen(false); writeEditor(s, { replace: true }); run(s) }} />

      <SavedQueriesPanel open={savedOpen} onOpenChange={setSavedOpen} onOpen={openSavedRow} reloadKey={savedReload}
        onEdit={editSavedRow} onUnsupported={markSavedUnsupported} />
      {saveDlg && (
        <SaveQueryDialog open mode={saveDlg.mode} base={saveDlg.base} sql={saveDlg.sql} paramTypes={saveDlg.paramTypes}
          onClose={() => setSaveDlg(null)} onSaved={onSaved} />
      )}
    </div>
  )
}
