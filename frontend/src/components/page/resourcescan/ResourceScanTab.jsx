import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  CircleAlert, FileSpreadsheet, FileText, Hourglass, Info, LayoutDashboard, List, History, Play, RefreshCw, RotateCw, ScanSearch, TriangleAlert,
} from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { clientFailure } from '../../../utils/errorMessages.js'
import { DOWNLOAD_FAILED } from '../../../utils/exportInventory.js'
import { useToast } from '../../ui/Toast.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import HintPopover from '../../ui/HintPopover.jsx'
import { LoadingBlock, ProgressBar, Spinner } from '../../ui/Progress.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import ResourceScanDashboard from './ResourceScanDashboard.jsx'
import ResourceScanObjects from './ResourceScanObjects.jsx'
import ResourceScanHistory from './ResourceScanHistory.jsx'
import {
  DEFAULT_FILTERS, POLL_MAX_FAILURES, POLL_MS, elapsedMs, formatMs, formatNumber, isRunning, pickDefaultScan, plural, pollCapMs,
  previousDone, progressPct,
} from './resourceScanModel.js'
import { CHIP, Section, TOUCH, WRAP } from './ResourceScanParts.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'

/**
 * Sayfa bütünlüğü detayı → "Kaynak analizi" sekmesi (2026-10-31, kullanıcı isteği). YALNIZ kullanıcı isteyince koşar:
 * "Analizi başlat" → sunucu sayfanın tüm nesnelerini (HTML, CSS, JS, görsel, yazı tipi, medya, belge, bağlantı …) indirir,
 * kategorize eder, boyut + süre ölçer; tahmini yüklenme süresi ve 100 üzerinden not üretir. Koşu ARKA PLANDA sürer —
 * sekmeden çıkılabilir; dönünce geçmiş okunur, süren koşu yeniden izlenir.
 *
 * Akış: geçmiş (`resourceScans`) → süren koşu varsa izlenir (`resourceScan` her ~1,5 sn; sıra korumalı, sökülünce durur,
 * art arda 5 hata ya da sunucu süre sınırı + pay aşılınca durur — sonsuz sorgu yok), yoksa en yeni tamamlanmış analiz
 * açılır. 409 RESOURCE_SCAN_RUNNING → yanıttaki koşu izlenir; 429 → sunucunun metni. Geçmiş okunamazsa "okunamadı" +
 * yeniden dene (asla "analiz yok"). Dışa aktarım (Excel / PDF) tıklanınca tembel yüklenir.
 */
export default function ResourceScanTab({ monitor, pollMs = POLL_MS }) {
  const t = useT()
  const locale = useDateLocale()
  const toast = useToast()
  const monitorId = monitor?.id

  const [hist, setHist] = useState({ state: 'loading', data: null, error: null })
  const [selectedId, setSelectedId] = useState(null)
  const [detail, setDetail] = useState({ state: 'idle', id: null, data: null, error: null })
  const [running, setRunning] = useState(null)          // izlenen RUNNING koşu (özet)
  const [pollState, setPollState] = useState('ok')      // ok | lost | capped
  const [pollNonce, setPollNonce] = useState(0)
  const [starting, setStarting] = useState(false)
  const [exporting, setExporting] = useState(null)
  const [section, setSection] = useState('overview')
  const [filters, setFilters] = useState(DEFAULT_FILTERS)

  const alive = useRef(true)
  const histSeq = useRef(0)
  const detailSeq = useRef(0)
  const selectedRef = useRef(null)
  selectedRef.current = selectedId
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])

  const loadHistory = useCallback(async () => {
    const seq = ++histSeq.current
    setHist((h) => ({ ...h, state: h.data ? 'refreshing' : 'loading' }))
    let res = null
    let err = null
    try { res = await api.monitoring.resourceScans(monitorId, 200) } catch (e) { err = e }
    if (!alive.current || seq !== histSeq.current) return null
    if (!res?.success || !res.data) {
      setHist((h) => ({ state: 'error', data: h.data, error: res || err }))
      return null
    }
    const data = {
      items: Array.isArray(res.data.items) ? res.data.items : [],
      running: res.data.running && isRunning(res.data.running) ? res.data.running : null,
      can_run: res.data.can_run === true,
      limits: res.data.limits || null,
    }
    setHist({ state: 'ok', data, error: null })
    return data
  }, [monitorId])

  const loadDetail = useCallback(async (id) => {
    const seq = ++detailSeq.current
    setDetail({ state: 'loading', id, data: null, error: null })
    let res = null
    let err = null
    try { res = await api.monitoring.resourceScan(monitorId, id) } catch (e) { err = e }
    if (!alive.current || seq !== detailSeq.current) return
    if (res?.success && res.data?.scan) setDetail({ state: 'ok', id, data: res.data, error: null })
    else setDetail({ state: 'error', id, data: null, error: res || err })
  }, [monitorId])

  /** Bir koşuyu izlemeye al (başlatma yanıtı, 409, ya da geçmişteki `running`). */
  const follow = useCallback((scan) => {
    if (!scan || scan.id == null) return
    setRunning(scan)
    setPollState('ok')
    setPollNonce((n) => n + 1)
    setHist((h) => {
      if (!h.data) return h
      const items = h.data.items.some((s) => s.id === scan.id) ? h.data.items.map((s) => (s.id === scan.id ? scan : s)) : [scan, ...h.data.items]
      return { ...h, data: { ...h.data, items, running: scan } }
    })
  }, [])

  const select = useCallback((id) => {
    setSelectedId(id)
    if (id == null) return
    loadDetail(id)
  }, [loadDetail])

  // İlk açılış: geçmiş → süren koşu izlenir; yoksa en yeni tamamlanmış analiz.
  useEffect(() => {
    let cancelled = false
    ;(async () => {
      const data = await loadHistory()
      if (cancelled || !data) return
      const pick = pickDefaultScan(data.items)
      if (data.running) {
        follow(data.running)
        if (pick != null && pick !== data.running.id) select(pick)
        else setSelectedId(data.running.id)
      } else if (pick != null) select(pick)
    })()
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [monitorId])

  // Süren koşunun ilerlemesi — setTimeout zinciri (çakışmaz), sıra korumalı, sınırlı.
  const runningId = running?.id ?? null
  const limits = hist.data?.limits
  useEffect(() => {
    if (runningId == null || pollState !== 'ok') return undefined
    let stop = false
    let timer = null
    let failures = 0
    const cap = pollCapMs(limits)
    const tick = async () => {
      let res = null
      try { res = await api.monitoring.resourceScan(monitorId, runningId) } catch { res = null }
      if (stop || !alive.current) return
      const scan = res?.success ? res.data?.scan : null
      if (!scan) {
        failures += 1
        if (failures >= POLL_MAX_FAILURES) { setPollState('lost'); return }
        timer = setTimeout(tick, pollMs * 2)
        return
      }
      failures = 0
      if (isRunning(scan)) {
        setRunning(scan)
        setHist((h) => (h.data ? { ...h, data: { ...h.data, running: scan, items: h.data.items.map((s) => (s.id === scan.id ? scan : s)) } } : h))
        if ((elapsedMs(scan) ?? 0) > cap) { setPollState('capped'); return }
        timer = setTimeout(tick, pollMs)
        return
      }
      // Bitti (DONE / FAILED / INTERRUPTED): sonucu göster, geçmişi tazele (grafik + liste).
      setRunning(null)
      setHist((h) => (h.data ? { ...h, data: { ...h.data, running: null, items: h.data.items.map((s) => (s.id === scan.id ? scan : s)) } } : h))
      detailSeq.current += 1
      setSelectedId(scan.id)
      setDetail({ state: 'ok', id: scan.id, data: res.data, error: null })
      setSection('overview')
      if (scan.status === 'DONE') toast.success(t('prs.toast.done'))
      loadHistory()
    }
    timer = setTimeout(tick, pollMs)
    return () => { stop = true; clearTimeout(timer) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [monitorId, runningId, pollState, pollNonce, pollMs])

  async function start() {
    if (starting || running) return
    setStarting(true)
    let res = null
    try {
      res = await api.monitoring.startResourceScan(monitorId)
    } catch (e) {
      if (alive.current) toast.error(e?.message || t('prs.startFailed'), { details: e })
      return
    } finally {
      if (alive.current) setStarting(false)
    }
    if (!alive.current || !res) return
    if (res.success && res.data) {
      follow(res.data)
      if (!hasFinished) setSelectedId(res.data.id)
    } else if (res.code === 'RESOURCE_SCAN_RUNNING' && res.data) {
      follow(res.data)
      if (!hasFinished) setSelectedId(res.data.id)
      toast.info(t('prs.toast.alreadyRunning'))
    } else {
      toast.error(res.error || t('prs.startFailed'), { details: res })
    }
  }

  async function exportAs(kind) {
    const d = detail.data
    if (!d?.scan || exporting) return
    setExporting(kind)
    try {
      const mod = await import('./resourceScanExport.js')
      const args = { monitor, scan: d.scan, analysis: d.analysis, items: d.items_kept === false ? null : d.items, t, locale,
        detailRuns: hist.data?.limits?.detail_runs ?? 20 }
      if (kind === 'xlsx') mod.exportResourceScanXlsx(args)
      else await mod.exportResourceScanPdf(args)
      if (alive.current) toast.success(t(kind === 'xlsx' ? 'prs.export.xlsxDone' : 'prs.export.pdfDone'))
    } catch (e) {
      if (!alive.current) return
      if (e?.code === DOWNLOAD_FAILED) toast.error(t('prs.export.downloadFailed'))
      else {
        const f = clientFailure(e, { fallback: t('prs.export.failed'), chunk: t('prs.export.pageUpdated') })
        toast.error(f.message, { details: f.details })
      }
    } finally {
      if (alive.current) setExporting(null)
    }
  }

  const items = useMemo(() => hist.data?.items ?? [], [hist.data])
  const hasFinished = items.some((s) => !isRunning(s))
  const canRun = hist.data?.can_run === true
  const sel = detail.data?.scan && detail.id === selectedId ? detail.data : null
  const selScan = sel?.scan ?? null
  const done = selScan?.status === 'DONE'
  const prev = useMemo(() => (selectedId != null ? previousDone(items, selectedId) : null), [items, selectedId])
  const objectsAvailable = done && sel?.items_kept !== false && Array.isArray(sel?.items)
  const showingRunning = running && selectedId === running.id

  async function reload() {
    const data = await loadHistory()
    if (!data) return
    if (data.running) follow(data.running)
    if (selectedRef.current == null) {
      const pick = pickDefaultScan(data.items)
      if (pick != null) select(pick)
      else if (data.running) setSelectedId(data.running.id)
    }
  }

  // ── İlk okuma / okunamayan geçmiş ───────────────────────────────────────
  if (hist.state === 'loading' && !hist.data) {
    return <div data-slot="prs-root" data-state="loading"><LoadingBlock label={t('prs.loading')} /></div>
  }
  if (hist.state === 'error' && !hist.data) {
    return (
      <div data-slot="prs-root" data-state="error">
        <div data-slot="prs-history-error">
          <StatusBlock tone="danger" icon={CircleAlert} title={t('prs.hist.unreadable')}
            description={hist.error?.error || hist.error?.message || t('prs.hist.unreadableBody')} details={hist.error || undefined}
            actions={<Button type="button" variant="outline" className={TOUCH} onClick={() => reload()}><RefreshCw aria-hidden="true" />{t('prs.retry')}</Button>} />
        </div>
      </div>
    )
  }

  const startBtn = canRun && (
    <Button type="button" onClick={start} disabled={starting || Boolean(running)} data-slot="prs-start" className={TOUCH}
      aria-busy={starting || undefined}>
      {starting ? <Spinner size={14} inline decorative /> : hasFinished ? <RotateCw aria-hidden="true" /> : <Play aria-hidden="true" />}
      {running ? t('prs.runningShort') : hasFinished ? t('prs.restart') : t('prs.start')}
    </Button>
  )

  return (
    <div data-slot="prs-root" data-state="ok" className="flex min-w-0 flex-col gap-3">
      {/* Başlık: ad + seçili analizin künyesi, eylemler. sm+ genişlikte eylemler TEK satır (shrink-0, sarmaz); kalan genişliği
          ad + künye sütunu alır ve metnini sarar — uzun künye düğmeleri alt satıra itmez. Telefonda düğmeler sarabilir. */}
      <div data-slot="prs-head" className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex min-w-0 flex-col gap-1 sm:flex-1">
          <h3 className="m-0 flex flex-wrap items-center gap-2 text-base font-semibold text-foreground">
            <ScanSearch aria-hidden="true" className="size-4 text-muted-foreground" />{t('prs.title')}
            <HintPopover content={<MethodText t={t} />} triggerClassName={cn('px-1 text-muted-foreground', TOUCH, 'pointer-coarse:min-w-10 justify-center')}
              aria-label={t('prs.method.open')}>
              <Info aria-hidden="true" className="size-4" />
            </HintPopover>
          </h3>
          {done && (
            <p data-slot="prs-meta" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>
              {t('prs.meta', formatDate(selScan.started_at), selScan.requested_by || t('prs.hist.byUnknownShort'))}
              {selScan.via && <> · {t(selScan.via === 'proxy' ? 'prs.via.proxy' : 'prs.via.direct')}</>}
              {(selScan.url || monitor?.url) && <> · <span className={WRAP}>{selScan.url || monitor?.url}</span></>}
            </p>
          )}
        </div>
        <div data-slot="prs-actions" className="flex flex-wrap items-center gap-2 sm:shrink-0 sm:flex-nowrap sm:justify-end">
          {(hasFinished || running) && startBtn}
          {done && (
            <>
              <Button type="button" variant="outline" data-slot="prs-export-xlsx" className={TOUCH} disabled={Boolean(exporting)}
                onClick={() => exportAs('xlsx')} aria-label={t('prs.export.xlsxAria')} aria-busy={exporting === 'xlsx' || undefined}>
                {exporting === 'xlsx' ? <Spinner size={14} inline decorative /> : <FileSpreadsheet aria-hidden="true" />}Excel
              </Button>
              <Button type="button" variant="outline" data-slot="prs-export-pdf" className={TOUCH} disabled={Boolean(exporting)}
                onClick={() => exportAs('pdf')} aria-label={t('prs.export.pdfAria')} aria-busy={exporting === 'pdf' || undefined}>
                {exporting === 'pdf' ? <Spinner size={14} inline decorative /> : <FileText aria-hidden="true" />}PDF
              </Button>
            </>
          )}
        </div>
      </div>

      {hist.state === 'error' && hist.data && (
        <div data-slot="prs-history-stale">
          <AlertBanner tone="warning" className="mb-0" actions={<Button type="button" variant="outline" size="sm" className={TOUCH} onClick={() => reload()}>{t('prs.retry')}</Button>}>
            {t('prs.hist.stale')}
          </AlertBanner>
        </div>
      )}

      {running && <RunningCard scan={running} pollState={pollState} t={t} locale={locale}
        onResume={() => { setPollState('ok'); setPollNonce((n) => n + 1) }}
        onShow={!showingRunning ? () => setSelectedId(running.id) : null} />}

      {!hasFinished && !running && (
        <EmptyState canRun={canRun} startBtn={startBtn} t={t} />
      )}

      {hasFinished && !showingRunning && (
        <Tabs value={section} onValueChange={setSection} data-slot="prs-sections" className="min-w-0 gap-3">
          <TabsList className="h-auto w-full flex-wrap justify-start gap-1 group-data-[orientation=horizontal]/tabs:h-auto sm:w-fit">
            <TabsTrigger value="overview" className={cn('flex-none gap-1.5 px-3', TOUCH)}><LayoutDashboard aria-hidden="true" />{t('prs.sec.overview')}</TabsTrigger>
            <TabsTrigger value="objects" className={cn('flex-none gap-1.5 px-3', TOUCH)}>
              <List aria-hidden="true" />{t('prs.sec.objects')}
              {objectsAvailable && <Badge variant="secondary" className="h-4 min-w-4 rounded-full px-1.5 py-0 text-[10.5px] leading-4 font-bold tabular-nums">{formatNumber(sel.items.length, locale)}</Badge>}
            </TabsTrigger>
            <TabsTrigger value="history" className={cn('flex-none gap-1.5 px-3', TOUCH)}>
              <History aria-hidden="true" />{t('prs.sec.history')}
              <Badge variant="secondary" className="h-4 min-w-4 rounded-full px-1.5 py-0 text-[10.5px] leading-4 font-bold tabular-nums">{formatNumber(items.length, locale)}</Badge>
            </TabsTrigger>
          </TabsList>

          <TabsContent value="overview" className="min-w-0">
            <SelectedView detail={detail} selectedId={selectedId} t={t} canRun={canRun} running={running}
              onRetryDetail={() => loadDetail(selectedId)} onStart={start} starting={starting}>
              {done && (
                <ResourceScanDashboard scan={selScan} analysis={sel.analysis} prev={prev} history={items} selectedId={selectedId}
                  objectsAvailable={objectsAvailable}
                  onCategory={(c) => { setFilters((f) => ({ ...DEFAULT_FILTERS, sort: f.sort, types: [c] })); setSection('objects') }}
                  onShowObjects={(urls) => { setFilters((f) => ({ ...DEFAULT_FILTERS, sort: f.sort, urls })); setSection('objects') }} />
              )}
            </SelectedView>
          </TabsContent>

          <TabsContent value="objects" className="min-w-0">
            <SelectedView detail={detail} selectedId={selectedId} t={t} canRun={canRun} running={running}
              onRetryDetail={() => loadDetail(selectedId)} onStart={start} starting={starting} compact>
              {done && (
                <ResourceScanObjects scanId={selectedId} items={sel.items} timing={sel.analysis?.timing}
                  itemsKept={objectsAvailable} filters={filters} onFiltersChange={setFilters} detailRuns={hist.data?.limits?.detail_runs ?? 20} />
              )}
            </SelectedView>
          </TabsContent>

          <TabsContent value="history" className="min-w-0">
            <ResourceScanHistory items={items} selectedId={selectedId} limits={hist.data?.limits}
              onSelect={(id) => {
                if (running && id === running.id) { setSelectedId(id); return }
                select(id)
                setSection('overview')
              }} />
          </TabsContent>
        </Tabs>
      )}
    </div>
  )
}

/** Yöntem açıklaması (başlıktaki bilgi düğmesi ve boş durum aynı metni kullanır). */
function MethodText({ t }) {
  return (
    <ul className="m-0 flex max-w-xs list-disc flex-col gap-1 pl-4 text-xs">
      <li>{t('prs.method.server')}</li>
      <li>{t('prs.method.js')}</li>
      <li>{plural(t, 'prs.method.estimate', 6, 6)}</li>
      <li>{t('prs.method.lazy')}</li>
      <li>{t('prs.method.docs')}</li>
    </ul>
  )
}

function EmptyState({ canRun, startBtn, t }) {
  return (
    <div data-slot="prs-empty" className="flex min-w-0 flex-col items-center gap-4 rounded-xl border border-dashed px-4 py-8 text-center">
      <span className="flex size-12 items-center justify-center rounded-full bg-primary/10 text-primary"><ScanSearch aria-hidden="true" className="size-6" /></span>
      <div className="flex max-w-xl flex-col gap-1.5">
        <h4 className="m-0 text-base font-semibold text-foreground">{t('prs.empty.title')}</h4>
        <p className="m-0 text-sm text-muted-foreground">{t('prs.empty.body')}</p>
      </div>
      <ul data-slot="prs-empty-facts" className="m-0 grid w-full max-w-2xl list-none grid-cols-1 gap-2 p-0 text-left sm:grid-cols-2">
        {['server', 'js', 'estimate', 'docs'].map((k) => (
          <li key={k} className="flex min-w-0 gap-2 rounded-lg bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
            <Info aria-hidden="true" className="mt-px size-3.5 shrink-0" />
            <span className={WRAP}>{k === 'estimate' ? plural(t, 'prs.method.estimate', 6, 6) : k === 'docs' ? `${t('prs.method.docs')} ${t('prs.method.lazy')}` : t(`prs.method.${k}`)}</span>
          </li>
        ))}
      </ul>
      {canRun ? startBtn : (
        <p data-slot="prs-cannot-run" className="m-0 max-w-md text-sm text-muted-foreground">{t('prs.cannotRun')}</p>
      )}
    </div>
  )
}

/** Süren analizin ilerlemesi — sayaç kendi yaprağında (üst bileşen saniyede bir çizilmez). */
function RunningCard({ scan, pollState, t, locale, onResume, onShow }) {
  const pct = progressPct(scan)
  const done = scan?.progress?.done ?? 0
  const total = scan?.progress?.total ?? 0
  return (
    <Section slot="prs-running" icon={Hourglass} title={t('prs.run.title')} data-poll={pollState}
      actions={onShow && <Button type="button" variant="outline" size="sm" className={TOUCH} onClick={onShow}>{t('prs.run.show')}</Button>}>
      <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-sm">
        {pollState === 'ok' && <Spinner size={14} label={t('prs.run.measuring')} className="text-primary" />}
        <span data-slot="prs-progress-text" className="font-semibold tabular-nums">
          {total > 0 ? plural(t, 'prs.run.progress', total, formatNumber(done, locale), formatNumber(total, locale)) : t('prs.run.discovering')}
        </span>
        <ToneBadge tone="muted" className={cn(CHIP, 'tabular-nums')}><Elapsed scan={scan} t={t} locale={locale} /></ToneBadge>
      </div>
      <ProgressBar value={pct} max={100} label={t('prs.run.progressLabel')} showValue={pct != null} />
      <p className="m-0 text-xs text-muted-foreground">{t('prs.run.background')}</p>
      {pollState !== 'ok' && (
        <div data-slot="prs-poll-stopped">
          <AlertBanner tone="warning" className="mb-0" icon={TriangleAlert}
            actions={<Button type="button" variant="outline" size="sm" className={TOUCH} onClick={onResume}>{t('prs.run.resume')}</Button>}>
            {pollState === 'capped' ? t('prs.run.capped') : t('prs.run.lost')}
          </AlertBanner>
        </div>
      )}
    </Section>
  )
}

function Elapsed({ scan, t, locale }) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [])
  const ms = elapsedMs(scan, now)
  return <span data-slot="prs-elapsed">{t('prs.run.elapsed', ms == null ? '—' : formatMs(Math.floor(ms / 1000) * 1000, locale))}</span>
}

/** Seçili analizin durumu: yükleniyor / okunamadı / başarısız / yarıda kaldı → aksi hâlde çocuklar (pano ya da nesneler). */
function SelectedView({ detail, selectedId, t, canRun, running, onRetryDetail, onStart, starting, compact = false, children }) {
  if (selectedId == null) return null
  if (detail.id !== selectedId || detail.state === 'loading' || detail.state === 'idle') {
    return <LoadingBlock label={t('prs.loadingRun')} />
  }
  if (detail.state === 'error') {
    return (
      <div data-slot="prs-detail-error">
        <StatusBlock tone="danger" icon={CircleAlert} title={t('prs.detailUnreadable')}
          description={detail.error?.error || detail.error?.message || t('prs.detailUnreadableBody')} details={detail.error || undefined}
          actions={<Button type="button" variant="outline" className={TOUCH} onClick={onRetryDetail}><RefreshCw aria-hidden="true" />{t('prs.retry')}</Button>} />
      </div>
    )
  }
  const scan = detail.data?.scan
  const retry = canRun && !running && (
    <Button type="button" variant="outline" className={TOUCH} onClick={onStart} disabled={starting} data-slot="prs-retry-run">
      <RotateCw aria-hidden="true" />{t('prs.restart')}
    </Button>
  )
  if (scan?.status === 'FAILED') {
    return (
      <div data-slot="prs-failed">
        <StatusBlock tone="danger" icon={CircleAlert} title={t('prs.runFail.title')} className={compact ? 'py-6' : undefined}
          description={scan.error || t('prs.runFail.body')} actions={retry || undefined} />
      </div>
    )
  }
  if (scan?.status === 'INTERRUPTED') {
    return (
      <div data-slot="prs-interrupted">
        <AlertBanner tone="warning" className="mb-0" icon={TriangleAlert} title={t('prs.interrupted.title')} actions={retry || undefined}>
          {scan.error || t('prs.interrupted.body')}
        </AlertBanner>
      </div>
    )
  }
  return children
}
