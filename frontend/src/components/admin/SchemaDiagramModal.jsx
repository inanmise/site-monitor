import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowDownToLine, ArrowRightToLine, Download, Image as ImageIcon, Info, KeyRound, List, Network, Rows3, Columns3,
  Search, SlidersHorizontal, X, FileCode2,
} from 'lucide-react'
import { useDateLocale, useT } from '../../i18n/index.jsx'
import { useIsMobile } from '@/hooks/use-mobile'
import { LoadingBlock, Spinner } from '../ui/Progress.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from '@/components/shadcn/command'
import {
  DropdownMenu, DropdownMenuCheckboxItem, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuRadioGroup,
  DropdownMenuRadioItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { Label } from '@/components/shadcn/label'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { Switch } from '@/components/shadcn/switch'
import ChoiceToggle from './sql/ChoiceToggle.jsx'
import DiagramCanvas from './sql/diagram/DiagramCanvas.jsx'
import DiagramListView from './sql/diagram/DiagramListView.jsx'
import TablePanel from './sql/diagram/TablePanel.jsx'
import { computeDiagram, neighbourhood } from './sql/diagram/layout.js'
import { buildDiagramSvg, readPalette, svgToPngBlob } from './sql/diagram/exportDiagram.js'
import { downloadBlob, downloadText, formatCompact, shortType, stampedFile } from './sql/sqlUtils.js'

/** Arama: tablo bul → vurgula + ortala (Popover + Command; cmdk kendi süzer). */
function TableFinder({ names, onPick, t }) {
  const [open, setOpen] = useState(false)
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button type="button" variant="outline" size="sm" className="gap-1.5 pointer-coarse:h-10">
          <Search /> {t('sql.diag.find')}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="z-(--z-menu) w-[min(20rem,calc(100vw-2rem))] p-0">
        <Command>
          <CommandInput placeholder={t('sql.diag.findPlaceholder')} className="text-base md:text-sm" />
          <CommandList className="max-h-[min(20rem,50dvh)]">
            <CommandEmpty>{t('sql.diag.noMatch')}</CommandEmpty>
            <CommandGroup>
              {names.map((n) => (
                <CommandItem key={n} value={n} onSelect={() => { setOpen(false); onPick(n) }} className="font-mono text-xs">
                  {n}
                </CommandItem>
              ))}
            </CommandGroup>
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  )
}

/**
 * SQL Playground — tablolar arası İLİŞKİ (hiyerarşi) diyagramı. 2026-09-27 yeniden tasarım (shadcn, mobil duyarlı):
 *
 * - Kartlar: tablo adı + satır sayısı; kipe göre kolonlar (Ad · Anahtarlar · Tüm kolonlar), PK/FK rozetleri, tipler;
 *   uzun tablo "N kolon daha" ile katlanır. Kenarlar FK satırından PK satırına eğri, ok (başvurulan) + kaz ayağı (çok).
 *   Düz çizgi gerçek FK, kesik çizgi `*_id` çıkarımı (bu şemada DB seviyesinde FK yok denecek kadar az).
 * - Otomatik katmanlı düzen (`sql/diagram/layout.js`): başvurulan tablo üstte/solda; yön değiştirilebilir; ilişkisiz
 *   tablolar ayrı grupta.
 * - Etkileşim: tekerlek/kıstır yakınlaştırma, sürükle kaydır, sığdır, %100, küçük harita; tablo ARA → ortala + kendisi
 *   ve doğrudan komşuları vurgulu, diğerleri soluk; karta dokun → yan panel (kolonlar, ilişkiler, ayrıntı / sorgula).
 * - Dışa aktar: SVG ve PNG (istemci tarafı, o anki temayla).
 * - Telefonda (< 768 px) varsayılan LİSTE görünümü (başvurduğu / ona başvuranlar); diyagram yine kıstır/sürükle ile kullanılır.
 * - Erişilebilirlik: kartlar gerçek düğme (Tab + Enter), kenar katmanı role="img" + metin karşılığı listesi.
 */
export default function SchemaDiagramModal({
  data, loading, error, onClose, onRetry, tables, columnsMap, ensureColumns, onOpenDetails, onQuery, initialFocus = null,
  focusRequest = null,
}) {
  const t = useT()
  const locale = useDateLocale()
  const toast = useToast()
  const isMobile = useIsMobile()
  const [view, setView] = useState('diagram')
  const viewPicked = useRef(false)
  const [mode, setMode] = useState('keys')
  const [direction, setDirection] = useState('TB')
  const [includeIsolated, setIncludeIsolated] = useState(true)
  const [expanded, setExpanded] = useState(() => new Set())
  const [focus, setFocus] = useState(initialFocus)
  const [panel, setPanel] = useState(null)
  const [colsLoading, setColsLoading] = useState(false)
  const [exporting, setExporting] = useState(false)
  const canvasRef = useRef(null)

  // Telefonda liste varsayılan (kullanıcı elle seçtiyse dokunulmaz).
  useEffect(() => { if (isMobile && !viewPicked.current) setView('list') }, [isMobile])
  const pickView = (v) => { viewPicked.current = true; setView(v) }

  const rowCounts = useMemo(() => Object.fromEntries((tables || []).map((r) => [r.table_name, r.live_rows ?? null])), [tables])
  const model = useMemo(() => computeDiagram(data, {
    mode, direction, columns: columnsMap, expanded, includeIsolated, rowCounts,
  }), [data, mode, direction, columnsMap, expanded, includeIsolated, rowCounts])
  const highlight = useMemo(() => neighbourhood(model.graph, focus), [model.graph, focus])
  const allNames = useMemo(() => model.nodes.map((n) => n.name).sort(), [model.nodes])

  // "Tüm kolonlar" kipi: eksik kolonlar tek seferde (sınırlı eşzamanlılıkla) yüklenir → tek yeniden yerleşim.
  const columnsRef = useRef(columnsMap)
  columnsRef.current = columnsMap
  useEffect(() => {
    if (mode !== 'columns' || !data || !ensureColumns) return undefined
    const need = model.nodes.map((n) => n.name).filter((n) => !columnsRef.current?.[n])
    if (!need.length) return undefined
    let live = true
    setColsLoading(true)
    Promise.resolve(ensureColumns(need)).finally(() => { if (live) setColsLoading(false) })
    return () => { live = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode, data, includeIsolated])

  // Panel açılınca o tablonun kolonları.
  const [panelLoading, setPanelLoading] = useState(false)
  useEffect(() => {
    if (!panel || columnsRef.current?.[panel] || !ensureColumns) return undefined
    let live = true
    setPanelLoading(true)
    Promise.resolve(ensureColumns([panel])).finally(() => { if (live) setPanelLoading(false) })
    return () => { live = false }
  }, [panel, ensureColumns])

  const select = useCallback((name) => { setFocus(name); setPanel(name) }, [])
  const toggleExpand = useCallback((name) => {
    setExpanded((s) => { const n = new Set(s); if (n.has(name)) n.delete(name); else n.add(name); return n })
  }, [])
  const jumpTo = useCallback((name, { openPanel = false } = {}) => {
    setFocus(name)
    if (openPanel) setPanel(name)
    if (!model.nodes.some((n) => n.name === name) && !includeIsolated) setIncludeIsolated(true)
    requestAnimationFrame(() => canvasRef.current?.centreOn(name))
  }, [model.nodes, includeIsolated])
  const clearFocus = () => { setFocus(null); setPanel(null) }

  // Açıkken gelen yeni odak isteği (ör. ayrıntı penceresinden "Diyagramda göster") — ilk açılış initialFocus'ta.
  const lastRequest = useRef(focusRequest?.n)
  useEffect(() => {
    if (!focusRequest || focusRequest.n === lastRequest.current) return
    lastRequest.current = focusRequest.n
    if (!focusRequest.focus) return
    viewPicked.current = true
    setView('diagram')
    const id = setTimeout(() => jumpTo(focusRequest.focus), 60)
    return () => clearTimeout(id)
  }, [focusRequest, jumpTo])

  const openDetails = (name) => { setPanel(null); onOpenDetails?.(name) }
  const query = (name) => { setPanel(null); onQuery?.(name) }
  const showInDiagram = (name) => { pickView('diagram'); setTimeout(() => jumpTo(name), 60) }

  const exportAs = async (kind) => {
    setExporting(true)
    try {
      const svg = buildDiagramSvg(model, {
        palette: readPalette(), title: t('sql.diag.title'),
        rowsLabel: (n) => (n == null ? '' : t('sql.diag.rowsShort', formatCompact(n, locale))),
        typeLabel: shortType, unrelatedLabel: t('sql.diag.unrelatedGroup', model.stats.isolated),
        moreLabel: (n) => t('sql.diag.moreCols', n),
      })
      if (kind === 'svg') downloadText(stampedFile('schema-diagram', 'svg'), svg, 'image/svg+xml')
      else {
        const blob = await svgToPngBlob(svg, Math.ceil(model.width), Math.ceil(model.height))
        if (!blob) throw new Error('png')
        downloadBlob(stampedFile('schema-diagram', 'png'), blob)
      }
      toast.success(t('sql.diag.exported', kind.toUpperCase()))
    } catch {
      toast.error(t('sql.diag.exportFailed'))
    } finally {
      setExporting(false)
    }
  }

  const modeOptions = [
    { value: 'names', label: t('sql.diag.modeNames'), icon: Rows3 },
    { value: 'keys', label: t('sql.diag.modeKeys'), icon: KeyRound },
    { value: 'columns', label: t('sql.diag.modeColumns'), icon: Columns3 },
  ]
  const dirOptions = [
    { value: 'TB', label: t('sql.diag.dirTB'), icon: ArrowDownToLine, hideLabel: true },
    { value: 'LR', label: t('sql.diag.dirLR'), icon: ArrowRightToLine, hideLabel: true },
  ]
  const hasData = !loading && !error && data && model.stats.tables > 0
  const diagramView = view === 'diagram'

  const exportMenuItems = (
    <>
      <DropdownMenuItem onSelect={() => exportAs('png')} disabled={exporting}><ImageIcon /> {t('sql.diag.exportPng')}</DropdownMenuItem>
      <DropdownMenuItem onSelect={() => exportAs('svg')} disabled={exporting}><FileCode2 /> {t('sql.diag.exportSvg')}</DropdownMenuItem>
    </>
  )

  const toolbar = hasData && (
    <div data-slot="diagram-toolbar" className="flex flex-wrap items-center gap-2">
      <ChoiceToggle ariaLabel={t('sql.diag.viewLabel')} value={view} onChange={pickView} options={[
        { value: 'diagram', label: t('sql.diag.viewDiagram'), icon: Network },
        { value: 'list', label: t('sql.diag.viewList'), icon: List },
      ]} />
      {diagramView && <TableFinder names={allNames} onPick={(n) => jumpTo(n)} t={t} />}
      {diagramView && !isMobile && (
        <>
          <ChoiceToggle ariaLabel={t('sql.diag.detailLabel')} value={mode} onChange={setMode} options={modeOptions} />
          <ChoiceToggle ariaLabel={t('sql.diag.dirLabel')} value={direction} onChange={setDirection} options={dirOptions} />
        </>
      )}
      {!isMobile && (
        <div className="flex items-center gap-2 px-1">
          <Switch id="sqlpg-diag-isolated" checked={includeIsolated} onCheckedChange={setIncludeIsolated} />
          <Label htmlFor="sqlpg-diag-isolated" className="text-xs font-medium text-muted-foreground">
            {t('sql.diag.showUnrelated', model.stats.isolated)}
          </Label>
        </div>
      )}
      {colsLoading && <span className="flex items-center gap-1.5 text-xs text-muted-foreground"><Spinner size={12} inline decorative />{t('sql.diag.loadingCols')}</span>}
      <div className="ml-auto flex items-center gap-2">
        {focus && (
          <Badge variant="secondary" data-slot="diagram-focus" className="h-8 gap-1 pr-1 pl-2.5 font-mono text-xs">
            <span className="max-w-[9rem] truncate">{focus}</span>
            <Button type="button" variant="ghost" size="icon-xs" className="pointer-coarse:size-8" aria-label={t('sql.diag.clearFocus', focus)} onClick={clearFocus}><X /></Button>
          </Badge>
        )}
        {diagramView && !isMobile && (
          <DropdownMenu modal={false}>
            <DropdownMenuTrigger asChild>
              <Button type="button" variant="outline" size="sm" disabled={exporting} aria-busy={exporting || undefined}>
                {exporting ? <Spinner size={14} inline decorative /> : <Download />} {t('sql.diag.export')}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="z-(--z-menu)">{exportMenuItems}</DropdownMenuContent>
          </DropdownMenu>
        )}
        {isMobile && (
          <DropdownMenu modal={false}>
            <DropdownMenuTrigger asChild>
              <Button type="button" variant="outline" size="icon" className="size-10" aria-label={t('sql.diag.options')}>
                <SlidersHorizontal />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="z-(--z-menu) w-60">
              {diagramView && (
                <>
                  <DropdownMenuLabel>{t('sql.diag.detailLabel')}</DropdownMenuLabel>
                  <DropdownMenuRadioGroup value={mode} onValueChange={setMode}>
                    {modeOptions.map((o) => <DropdownMenuRadioItem key={o.value} value={o.value} className="min-h-10">{o.label}</DropdownMenuRadioItem>)}
                  </DropdownMenuRadioGroup>
                  <DropdownMenuSeparator />
                  <DropdownMenuLabel>{t('sql.diag.dirLabel')}</DropdownMenuLabel>
                  <DropdownMenuRadioGroup value={direction} onValueChange={setDirection}>
                    {dirOptions.map((o) => <DropdownMenuRadioItem key={o.value} value={o.value} className="min-h-10">{o.label}</DropdownMenuRadioItem>)}
                  </DropdownMenuRadioGroup>
                  <DropdownMenuSeparator />
                </>
              )}
              <DropdownMenuCheckboxItem checked={includeIsolated} onCheckedChange={(v) => setIncludeIsolated(!!v)}
                onSelect={(e) => e.preventDefault()} className="min-h-10">
                {t('sql.diag.showUnrelated', model.stats.isolated)}
              </DropdownMenuCheckboxItem>
              {diagramView && <><DropdownMenuSeparator />{exportMenuItems}</>}
            </DropdownMenuContent>
          </DropdownMenu>
        )}
      </div>
    </div>
  )

  const legend = (
    <div data-slot="diagram-legend" onPointerDown={(e) => e.stopPropagation()}
      className="absolute bottom-3 left-3 hidden max-w-[calc(100%-14rem)] flex-wrap items-center gap-x-3 gap-y-1 rounded-md border bg-background/90 px-2.5 py-1.5 text-[11px] text-muted-foreground shadow-sm backdrop-blur-sm sm:flex">
      <span className="inline-flex items-center gap-1"><Badge variant="outline" className="h-4 rounded-sm border-amber-500/40 bg-amber-500/10 px-1 text-[9px] font-bold text-amber-700 dark:text-amber-300">PK</Badge>{t('sql.diag.legendPk')}</span>
      <span className="inline-flex items-center gap-1"><Badge variant="outline" className="h-4 rounded-sm border-emerald-500/40 bg-emerald-500/10 px-1 text-[9px] font-bold text-emerald-700 dark:text-emerald-300">FK</Badge>{t('sql.diag.legendFk')}</span>
      <span className="inline-flex items-center gap-1.5"><i aria-hidden="true" className="w-6 border-t-2 border-muted-foreground" />{t('sql.diag.realFk')}</span>
      <span className="inline-flex items-center gap-1.5"><i aria-hidden="true" className="w-6 border-t-2 border-dashed border-muted-foreground" />{t('sql.diag.inferred')}</span>
      <span className="inline-flex items-center gap-1">
        <svg aria-hidden="true" width="34" height="10" className="overflow-visible">
          <path d="M1,5 L33,5" className="fill-none stroke-muted-foreground [stroke-width:1.3]" />
          <path d="M1,0 L9,5 M1,5 L9,5 M1,10 L9,5" className="fill-none stroke-muted-foreground [stroke-width:1.3]" />
          <path d="M26,1 L33,5 L26,9 z" className="fill-muted-foreground" />
        </svg>
        {t('sql.diag.legendEdge')}
      </span>
    </div>
  )

  const footer = (
    <div className="flex w-full flex-wrap items-center gap-2">
      {hasData && (
        <p data-slot="diagram-stats" className="mr-auto text-xs text-muted-foreground">
          {t('sql.diag.stats', model.stats.connected, model.stats.edges, model.stats.real, model.stats.inferred, model.stats.isolated)}
        </p>
      )}
      <Button type="button" variant="secondary" onClick={onClose}>{t('sql.closeRowDetails')}</Button>
    </div>
  )

  return (
    <ModalShell open onClose={onClose} title={t('sql.diag.title')} icon={Network} size="full" scrollBody
      dismissOnEscape={!focus && !panel}
      className="h-[calc(100dvh-1rem)] max-h-[calc(100dvh-1rem)] max-w-[calc(100%-1rem)] gap-3 p-3 sm:h-[calc(100dvh-2rem)] sm:max-h-[calc(100dvh-2rem)] sm:p-5"
      footer={footer}>
      <div className="flex h-full min-h-0 flex-col gap-2.5"
        // Radix'in belge düzeyindeki Escape dinleyicisi (ModalShell, dismissOnEscape=false) olayı ÖNCE preventDefault
        // eder — burada defaultPrevented'a bakılmaz; vurgu varken Escape pencereyi değil vurguyu kapatır.
        onKeyDown={(e) => { if (e.key === 'Escape' && (focus || panel)) { e.preventDefault(); clearFocus() } }}>
        {toolbar}
        {hasData && (
          <p className="flex items-start gap-1.5 text-xs text-muted-foreground">
            <Info aria-hidden="true" className="mt-px size-3.5 shrink-0" />
            <span>{t('sql.diag.note')}</span>
          </p>
        )}
        {loading ? (
          <LoadingBlock label={t('sql.td.loading')} size={18} />
        ) : error || !data ? (
          <StatusBlock tone="danger" icon={Network} title={t('sql.diag.loadError')} description={error || undefined}
            actions={onRetry && <Button type="button" variant="outline" onClick={onRetry}>{t('sql.retry')}</Button>} />
        ) : model.stats.tables === 0 ? (
          <StatusBlock icon={Network} title={t('sql.diag.empty')} />
        ) : diagramView ? (
          <DiagramCanvas ref={canvasRef} model={model} focus={focus} highlight={highlight} expanded={expanded}
            onSelect={select} onToggleExpand={toggleExpand} fitKey={`${direction}|${mode}|${includeIsolated}`}
            initialCentre={focus ?? initialFocus} className="flex-1">
            {legend}
          </DiagramCanvas>
        ) : (
          <DiagramListView model={model} focus={focus} prefix="sqlpg-dl"
            onFocus={(n) => setFocus(n)} onOpenDetails={openDetails} onQuery={query} onShowInDiagram={showInDiagram} />
        )}
      </div>
      <TablePanel name={panel} model={model} columns={panel ? columnsMap?.[panel] ?? null : null} columnsLoading={panelLoading}
        side={isMobile ? 'bottom' : 'right'} onClose={() => setPanel(null)}
        onFocusTable={(n) => jumpTo(n, { openPanel: true })} onOpenDetails={openDetails} onQuery={query} />
    </ModalShell>
  )
}
