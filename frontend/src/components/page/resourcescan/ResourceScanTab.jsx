import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  CircleAlert, FileSpreadsheet, FileText, History, Hourglass, Info, LayoutDashboard, Link2, List, Play, RefreshCw, RotateCw, ScanSearch,
  Square, TriangleAlert,
} from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { useUrlQuerySync } from '../../../hooks/useUrlQuerySync.js'
import { clientFailure } from '../../../utils/errorMessages.js'
import { DOWNLOAD_FAILED } from '../../../utils/exportInventory.js'
import { applyTabView } from '../../../utils/navigate.js'
import { useToast } from '../../ui/Toast.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import { useCopyLink } from '../../ui/CopyLinkButton.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import HintPopover from '../../ui/HintPopover.jsx'
import { LoadingBlock, ProgressBar, Spinner } from '../../ui/Progress.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import ResourceScanDashboard from './ResourceScanDashboard.jsx'
import ResourceScanObjects from './ResourceScanObjects.jsx'
import ResourceScanHistory from './ResourceScanHistory.jsx'
import ResourceScanObjectDetail from './ResourceScanObjectDetail.jsx'
import {
  DEFAULT_FILTERS, POLL_MAX_FAILURES, POLL_MS, elapsedAtSighting, filtersForNewScan, formatMs, formatNumber, gradeLetter, isCancelled,
  isRunning, latestDoneId, monotonicPct, parseUrlState, pickDefaultScan, plural, pollCapMs, pollCapReached, previousDone, problemText,
  progressPct, recFiltersByFinding, recItemUrls, resourceScanLink, sectionOfView, urlStateValues, viewOf, withFindings,
} from './resourceScanModel.js'
import { forgetStarted, handOff, markStartedHere, startedHere, takeBack } from './resourceScanWatcher.js'
import { CHIP, Section, TOUCH, WRAP } from './ResourceScanParts.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'

/**
 * Sayfa bütünlüğü detayı → "Kaynak analizi" sekmesi (2026-10-31, kullanıcı isteği; r177 yeniden düzen). YALNIZ kullanıcı
 * isteyince koşar: "Analizi başlat" → sunucu sayfanın tüm nesnelerini (HTML, CSS, JS, görsel, yazı tipi, medya, belge,
 * bağlantı …) indirir, kategorize eder, boyut + süre ölçer; tahmini yüklenme süresi ve 100 üzerinden not üretir. Koşu
 * ARKA PLANDA sürer — sekmeden / pencereden çıkılabilir; BU SEKMEDE başlatılan analiz pencere kapalıyken biterse tost +
 * "Aç" (resourceScanWatcher). Süren analiz iptal edilebilir (sunucu ucu yoksa düğme gizlenir).
 *
 * Akış: geçmiş (`resourceScans`) → süren koşu varsa izlenir (`resourceScan` her ~1,5 sn; sıra korumalı, sökülünce durur,
 * art arda 5 hata ya da sunucu süre sınırı + pay aşılınca durur — sonsuz sorgu yok), yoksa adres çubuğundaki (`prs`) ya da
 * en yeni tamamlanmış analiz açılır. Bölümler: Pano / Nesneler (Liste | Şelale) / Geçmiş (Liste | Karşılaştır); durum URL'de
 * (`prs`, `prs_v`, `prs_b` — paylaşılabilir bağlantı). Bölüm geçişinde içerik başa kayar ve odak bölüm başlığına / sayaca
 * taşınır (r177 H1). Geçmiş okunamazsa "okunamadı" + yeniden dene (asla "analiz yok").
 *
 * `active` = dış detay sekmesi şu an "Kaynak analizi" mi (sekme bir kez açıldıktan sonra gizli de kalsa bağlı kalır —
 * durum kaybolmasın, r177 H10); gizliyken URL'e yazmaz, bitişte "Aç" düğmeli tost gösterir (`onShow`).
 */
/** Monoton saat (ms) — duvar saati ileri / geri atlasa da izleme süresi doğru ölçülür. */
const monoNow = () => (typeof performance !== 'undefined' && typeof performance.now === 'function' ? performance.now() : Date.now())

/** Kaydırılan en yakın ata (pencere gövdesi) — bölüm geçişinde kaydırma onun üzerinden yapılır. */
function scrollParent(el) {
  for (let p = el?.parentElement; p; p = p.parentElement) {
    const oy = getComputedStyle(p).overflowY
    if ((oy === 'auto' || oy === 'scroll') && p.scrollHeight > p.clientHeight) return p
  }
  return null
}

export default function ResourceScanTab({ monitor, pollMs = POLL_MS, pollCap, active = true, onShow }) {
  const t = useT()
  const locale = useDateLocale()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const copyLink = useCopyLink()
  const monitorId = monitor?.id

  // Derin bağlantı (r177): ilk açılışta adres çubuğundaki seçim / görünüm bir kez okunur
  const initialUrl = useRef(null)
  if (initialUrl.current === null) initialUrl.current = parseUrlState(typeof window !== 'undefined' ? window.location.search : '')

  const [hist, setHist] = useState({ state: 'loading', data: null, error: null })
  const [selectedId, setSelectedId] = useState(null)
  const [detail, setDetail] = useState({ state: 'idle', id: null, data: null, error: null })
  const [running, setRunning] = useState(null)          // izlenen RUNNING koşu (özet)
  const [sighting, setSighting] = useState(null)        // { id, base, mono } — geçen süre tabanı (sunucu saati)
  const [pollState, setPollState] = useState('ok')      // ok | lost | capped
  const [pollNonce, setPollNonce] = useState(0)
  const [starting, setStarting] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [cancelRequested, setCancelRequested] = useState(null)   // iptal istenen koşu kimliği
  const [cancelSupported, setCancelSupported] = useState(true)
  const [exporting, setExporting] = useState(null)
  const initSection = sectionOfView(initialUrl.current.view)
  const [section, setSection] = useState(initSection)
  const [objectsMode, setObjectsMode] = useState(initialUrl.current.view === 'waterfall' ? 'waterfall' : 'list')
  const [historyMode, setHistoryMode] = useState(initialUrl.current.view === 'compare' ? 'compare' : 'list')
  const [otherId, setOtherId] = useState(initialUrl.current.otherId)
  const [filters, setFilters] = useState(DEFAULT_FILTERS)
  const [openItem, setOpenItem] = useState(null)
  const [focusReq, setFocusReq] = useState(null)        // { target, seq } — bölüm geçişinde odak / kaydırma isteği

  const alive = useRef(true)
  const histSeq = useRef(0)
  const detailSeq = useRef(0)
  const selectedRef = useRef(null)
  selectedRef.current = selectedId
  const runningRef = useRef(null)
  runningRef.current = running
  const activeRef = useRef(active)
  activeRef.current = active
  const ctxRef = useRef({})
  ctxRef.current = { toast, t, monitor, onShow }
  const cacheRef = useRef(new Map())                    // analiz kimliği → ayrıntı (tamamlanmış / bitmiş analizler)
  const rootRef = useRef(null)
  const countRef = useRef(null)
  const dashScroll = useRef(null)
  const focusSeq = useRef(0)
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
      timestamp: res.timestamp ?? null,
    }
    setHist({ state: 'ok', data, error: null })
    return data
  }, [monitorId])

  const loadDetail = useCallback(async (id) => {
    const seq = ++detailSeq.current
    const cached = cacheRef.current.get(id)
    if (cached) { setDetail({ state: 'ok', id, data: cached, error: null }); return }
    setDetail({ state: 'loading', id, data: null, error: null })
    let res = null
    let err = null
    try { res = await api.monitoring.resourceScan(monitorId, id) } catch (e) { err = e }
    if (!alive.current || seq !== detailSeq.current) return
    if (res?.success && res.data?.scan) {
      if (!isRunning(res.data.scan)) cacheRef.current.set(id, res.data)
      setDetail({ state: 'ok', id, data: res.data, error: null })
    } else setDetail({ state: 'error', id, data: null, error: res || err })
  }, [monitorId])

  /** Karşılaştırma için ayrıntı (önbellekten; yoksa okur) — `{ scan, … }` ya da hata gövdesi. */
  const getDetail = useCallback(async (id) => {
    const cached = cacheRef.current.get(id)
    if (cached) return cached
    let res = null
    try { res = await api.monitoring.resourceScan(monitorId, id) } catch (e) { return e }
    if (res?.success && res.data?.scan) {
      if (!isRunning(res.data.scan)) cacheRef.current.set(id, res.data)
      return res.data
    }
    return res
  }, [monitorId])

  /** Bir koşuyu izlemeye al (başlatma yanıtı, 409, ya da geçmişteki `running`). `serverTs` = yanıtın sunucu damgası. */
  const follow = useCallback((scan, serverTs) => {
    if (!scan || scan.id == null) return
    takeBack(monitorId, scan.id)                         // arka plan izlemesi varsa sekme devralır (çift sorgu yok)
    setRunning(scan)
    setSighting((s) => (s?.id === scan.id ? s : { id: scan.id, base: elapsedAtSighting(scan, serverTs), mono: monoNow() }))
    setPollState('ok')
    setPollNonce((n) => n + 1)
    setHist((h) => {
      if (!h.data) return h
      const items = h.data.items.some((s) => s.id === scan.id) ? h.data.items.map((s) => (s.id === scan.id ? scan : s)) : [scan, ...h.data.items]
      return { ...h, data: { ...h.data, items, running: scan } }
    })
  }, [monitorId])

  /** Seçimi değiştirir; seçim değişince bir önerinin süzgeci taşınmaz (r177 H5) ve açık nesne penceresi kapanır. */
  const changeSelection = useCallback((id) => {
    if (selectedRef.current !== id) { setFilters((f) => filtersForNewScan(f)); setOpenItem(null) }
    selectedRef.current = id
    setSelectedId(id)
  }, [])

  const select = useCallback((id) => {
    changeSelection(id)
    if (id == null) return
    loadDetail(id)
  }, [loadDetail, changeSelection])

  // İlk açılış: geçmiş → süren koşu izlenir; adres çubuğundaki analiz (bu izlemenin geçmişindeyse) ya da en yeni tamamlanmış.
  useEffect(() => {
    let cancelled = false
    ;(async () => {
      const data = await loadHistory()
      if (cancelled || !data) return
      const u = initialUrl.current
      const fromUrl = u.scanId != null && data.items.some((s) => s.id === u.scanId && !isRunning(s)) ? u.scanId : null
      const pick = fromUrl ?? pickDefaultScan(data.items)
      if (data.running) {
        follow(data.running, data.timestamp)
        if (pick != null && pick !== data.running.id) select(pick)
        else changeSelection(data.running.id)
      } else if (pick != null) select(pick)
    })()
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [monitorId])

  /**
   * Bitmiş koşuyu göster (izleme ya da iptal yanıtı): seçilir, geçmiş tazelenir, bildirim. İPTAL edilen koşu, kullanıcı
   * başka (tamamlanmış) bir analize bakıyorsa seçimi DEĞİŞTİRMEZ — iptal edenin elindeki sonuç ekrandan gitmesin.
   */
  const applyFinished = useCallback((scan, data) => {
    setRunning(null)
    setCancelRequested(null)
    setHist((h) => (h.data ? { ...h, data: { ...h.data, running: null, items: h.data.items.map((s) => (s.id === scan.id ? scan : s)) } } : h))
    if (data?.scan) cacheRef.current.set(scan.id, data)
    const keep = isCancelled(scan) && selectedRef.current != null && selectedRef.current !== scan.id
    if (!keep) {
      detailSeq.current += 1
      changeSelection(scan.id)
      setDetail({ state: 'ok', id: scan.id, data: data?.scan ? data : { scan, analysis: null, items: null, items_kept: false }, error: null })
      setSection('overview')
    }
    const wasHere = startedHere(monitorId, scan.id)
    forgetStarted(monitorId, scan.id)
    if (!isCancelled(scan) && (wasHere || activeRef.current || scan.status === 'DONE')) notifyDone(scan, { visible: activeRef.current })
    loadHistory()
  }, [monitorId, loadHistory, changeSelection])

  /** Bitiş bildirimi: sekme görünürse sade tost; gizliyse (dış sekme başka) "Aç" düğmeli tost. */
  function notifyDone(scan, { visible }) {
    const { toast: tt, t: tr, onShow: show } = ctxRef.current
    const text = doneText(scan, tr, ctxRef.current.monitor)
    if (visible || !show) {
      if (scan.status === 'DONE') tt.success(text)
      else tt.info(text)
      return
    }
    tt.info(<ToastWithAction text={text} action={tr('prs.toast.open')} onAction={show} />, 15_000)
  }

  // Süren koşunun ilerlemesi — setTimeout zinciri (çakışmaz), sıra korumalı, sınırlı.
  const runningId = running?.id ?? null
  const limits = hist.data?.limits
  const limitsRef = useRef(null)
  limitsRef.current = limits
  useEffect(() => {
    if (runningId == null || pollState !== 'ok') return undefined
    let stop = false
    let timer = null
    let failures = 0
    // Tavan BU istemcinin izlemeye başladığı andan ölçülür (Sürdür / yeni koşu sıfırlar) — sunucunun `started_at`
    // damgası tarayıcı saatiyle kıyaslanmaz (saat ileri olan tarayıcıda izleme hemen dururdu; 2026-10-31 inceleme).
    const cap = pollCap ?? pollCapMs(limits)
    const pollStartedAt = monoNow()
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
        if (pollCapReached(pollStartedAt, monoNow(), cap)) { setPollState('capped'); return }
        timer = setTimeout(tick, pollMs)
        return
      }
      // Bitti (DONE / FAILED / INTERRUPTED): sonucu göster, geçmişi tazele (grafik + liste).
      applyFinished(scan, res.data)
    }
    timer = setTimeout(tick, pollMs)
    return () => { stop = true; clearTimeout(timer) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [monitorId, runningId, pollState, pollNonce, pollMs, pollCap])

  // Sekme sökülürken BU SEKMEDE başlatılan analiz hâlâ sürüyorsa izleme arka plana devredilir (bitince "Aç" tostu).
  useEffect(() => () => {
    const r = runningRef.current
    if (!r || !startedHere(monitorId, r.id)) return
    const { toast: tt, t: tr, monitor: mon } = ctxRef.current
    handOff({
      monitorId, scanId: r.id, pollMs: Math.max(pollMs * 2, 300), capMs: pollCap ?? pollCapMs(limitsRef.current),
      poll: async () => { const res = await api.monitoring.resourceScan(monitorId, r.id); return res?.success ? res.data : null },
      onDone: (scan) => {
        if (isCancelled(scan)) return
        const text = doneText(scan, tr, mon)
        tt.info(<ToastWithAction text={text} action={tr('prs.toast.open')}
          onAction={() => applyTabView('page', { monitor: monitorId, mtab: 'resources', prs: scan.id })} />, 20_000)
      },
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [monitorId])
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
      markStartedHere(monitorId, res.data.id)
      follow(res.data, res.timestamp)
      if (!hasFinished) changeSelection(res.data.id)
      requestFocus('running')
    } else if (res.code === 'RESOURCE_SCAN_RUNNING' && res.data) {
      follow(res.data, res.timestamp)
      if (!hasFinished) changeSelection(res.data.id)
      toast.info(t('prs.toast.alreadyRunning'))
    } else {
      toast.error(res.error || t('prs.startFailed'), { details: res })
    }
  }

  async function cancel() {
    const r = running
    if (!r || cancelling) return
    const ok = await showConfirm({
      title: t('prs.cancel.confirmTitle'), message: t('prs.cancel.confirmBody'),
      confirmText: t('prs.cancel.confirm'), cancelText: t('prs.cancel.keep'), variant: 'danger',
    })
    if (!ok || !alive.current) return
    setCancelling(true)
    let res = null
    try {
      res = await api.monitoring.cancelResourceScan(monitorId, r.id)
    } catch (e) {
      if (alive.current) toast.error(e?.message || t('prs.cancel.notSentText'), { details: e })
      return
    } finally {
      if (alive.current) setCancelling(false)
    }
    if (!alive.current || !res) return
    // Sunucu (r177): 200 + özet (çoğunlukla INTERRUPTED / CANCELLED); çok pod'da koşan pod kalp atışında durur → RUNNING
    const summary = (d) => d?.scan ?? (d?.id != null ? d : null)
    if (res.success) {
      forgetStarted(monitorId, r.id)
      const scan = summary(res.data)
      if (scan && !isRunning(scan)) {
        toast.info(t('prs.cancel.done'))
        applyFinished(scan, res.data?.scan ? res.data : null)
      } else {
        setCancelRequested(r.id)
        toast.info(t('prs.cancel.requested'))
      }
      return
    }
    // Eski sunucu (uç yok): 405 ya da kodsuz / genel 404 → düğme gizlenir; analiz yine sürer
    if (res.status === 405 || (res.status === 404 && res.code !== 'RESOURCE_SCAN_NOT_FOUND')) {
      setCancelSupported(false)
      toast.info(t('prs.cancel.unsupported'))
      return
    }
    if (res.code === 'RESOURCE_SCAN_NOT_RUNNING' || res.code === 'RESOURCE_SCAN_NOT_FOUND') {
      toast.info(t('prs.cancel.notRunning'))
      // 409: gövdede güncel özet — bitmişse doğrudan gösterilir; 404: koşu yok → geçmiş tazelenir
      const scan = summary(res.data)
      if (scan && !isRunning(scan)) applyFinished(scan, null)
      else { setPollNonce((n) => n + 1); reload() }
      return
    }
    toast.error(res.error || t('prs.cancel.notSentText'), { details: res })
  }

  async function exportAs(kind) {
    const d = detail.data
    if (!d?.scan || exporting) return
    setExporting(kind)
    try {
      const mod = await import('./resourceScanExport.js')
      const args = { monitor, scan: d.scan, analysis: d.analysis, items: d.items_kept === false ? null : (enriched ?? d.items), t, locale,
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
  const enriched = useMemo(() => (objectsAvailable ? withFindings(sel.items, sel.analysis?.recommendations) : null), [objectsAvailable, sel])
  const latestDone = latestDoneId(items)
  const view = viewOf(section, objectsMode, historyMode)
  const urlScanId = selectedId != null && selectedId !== running?.id ? selectedId : null

  // Paylaşılabilir URL (yalnız sekme görünürken; gizliyken detay sayfası kendi anahtarlarını siler)
  useUrlQuerySync(urlStateValues({ scanId: urlScanId, view, otherId }), { enabled: active && hist.state !== 'loading' })

  /** Odak / kaydırma isteği (bölüm geçişi): `count`, `hero`, `history`, `running`, `rec:<KOD>`, `restoreDash`. */
  function requestFocus(target) {
    focusSeq.current += 1
    setFocusReq({ target, seq: focusSeq.current })
  }

  // Bölüm geçişinden sonra hedefi bul → pencere gövdesini başına kaydır + odakla (sınırlı sayıda kare dener).
  useEffect(() => {
    if (!focusReq) return undefined
    let frames = 0
    let raf = null
    const find = () => {
      const root = rootRef.current
      if (!root) return null
      const tg = focusReq.target
      if (tg === 'count') return countRef.current
      if (tg === 'hero') return root.querySelector('[data-focus-target="prs-hero"]')
      if (tg === 'history') return root.querySelector('[data-focus-target="prs-history"], [data-focus-target="prs-compare"]')
      if (tg === 'running') return root.querySelector('[data-slot="prs-running"]')
      if (tg === 'objects') return root.querySelector('[data-focus-target="prs-objects"]')
      // Öneri kodları yalnız [A-Z_] — seçicide kaçış gerekmez (jsdom'da CSS.escape yok)
      if (typeof tg === 'string' && tg.startsWith('rec:') && /^[A-Z_]+$/.test(tg.slice(4))) return root.querySelector(`[data-slot="prs-rec"][data-code="${tg.slice(4)}"]`)
      return null
    }
    const run = () => {
      raf = null
      if (focusReq.target === 'restoreDash') {
        const sp = scrollParent(rootRef.current)
        if (sp && dashScroll.current != null) sp.scrollTop = dashScroll.current
        dashScroll.current = null
        return
      }
      const el = find()
      if (!el) {
        frames += 1
        if (frames < 20) raf = (typeof requestAnimationFrame === 'function' ? requestAnimationFrame(run) : setTimeout(run, 16))
        return
      }
      // Hedefin bölüm başlığıyla birlikte görünmesi için: sayaç → nesne bölümünün başı; diğerleri kendisi
      const anchor = focusReq.target === 'count' ? (el.closest('[data-slot="prs-objects"]') || el) : el
      try { anchor.scrollIntoView?.({ block: 'start', behavior: 'auto' }) } catch { /* eski tarayıcı */ }
      try { el.focus?.({ preventScroll: true }) } catch { el.focus?.() }
    }
    raf = (typeof requestAnimationFrame === 'function' ? requestAnimationFrame(run) : setTimeout(run, 16))
    return () => {
      if (raf != null) {
        if (typeof cancelAnimationFrame === 'function') cancelAnimationFrame(raf)
        clearTimeout(raf)
      }
    }
  }, [focusReq])

  /** Panodan başka bölüme geçerken panonun kaydırma konumu saklanır — dönüşte kaldığı yere (r177 H1). */
  function rememberDash() {
    if (section !== 'overview') return
    const sp = scrollParent(rootRef.current)
    dashScroll.current = sp ? sp.scrollTop : null
  }

  function goObjects(nextFilters, mode = 'list') {
    rememberDash()
    setFilters(nextFilters)
    setObjectsMode(mode)
    setSection('objects')
    requestFocus('count')
  }

  function goRec(code) {
    setOpenItem(null)
    if (section !== 'overview') setSection('overview')
    requestFocus(`rec:${code}`)
  }

  function onSectionChange(next) {
    const back = next === 'overview' && section !== 'overview' && dashScroll.current != null
    if (section === 'overview' && next !== 'overview') rememberDash()
    setSection(next)
    if (back) requestFocus('restoreDash')
  }

  async function reload() {
    const data = await loadHistory()
    if (!data) return
    if (data.running) follow(data.running, data.timestamp)
    if (selectedRef.current == null) {
      const pick = pickDefaultScan(data.items)
      if (pick != null) select(pick)
      else if (data.running) changeSelection(data.running.id)
    }
  }

  function shareLink() {
    const origin = typeof window !== 'undefined' ? window.location.origin : ''
    copyLink(resourceScanLink({ origin, monitorId, scanId: urlScanId, view, otherId }))
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
  const finished = selScan && !isRunning(selScan)
  const showOld = done && latestDone != null && selectedId !== latestDone

  return (
    <div ref={rootRef} data-slot="prs-root" data-state="ok" data-view={view} className="flex min-w-0 flex-col gap-3">
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
          {finished && (
            <p data-slot="prs-meta" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>
              {t('prs.meta', formatDate(selScan.started_at), selScan.requested_by || t('prs.hist.byUnknownShort'))}
              {selScan.via && <> · {t(selScan.via === 'proxy' ? 'prs.via.proxy' : 'prs.via.direct')}</>}
              {(selScan.url || monitor?.url) && <> · <span className={WRAP}>{selScan.url || monitor?.url}</span></>}
            </p>
          )}
        </div>
        <div data-slot="prs-actions" className="flex flex-wrap items-center gap-2 sm:shrink-0 sm:flex-nowrap sm:justify-end">
          {(hasFinished || running) && startBtn}
          {urlScanId != null && (
            <Button type="button" variant="outline" size="icon" data-slot="prs-share" className={cn(TOUCH, 'pointer-coarse:min-w-10')}
              onClick={shareLink} aria-label={t('prs.share')} title={t('prs.share')}>
              <Link2 aria-hidden="true" />
            </Button>
          )}
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

      {running && <RunningCard scan={running} pollState={pollState} t={t} locale={locale} sighting={sighting}
        onResume={() => { setPollState('ok'); setPollNonce((n) => n + 1) }}
        onCancel={canRun && cancelSupported ? cancel : null} cancelling={cancelling} cancelRequested={cancelRequested === running.id} />}

      {!hasFinished && !running && (
        <EmptyState canRun={canRun} startBtn={startBtn} t={t} />
      )}

      {hasFinished && (
        <Tabs value={section} onValueChange={onSectionChange} data-slot="prs-sections" className="min-w-0 gap-3">
          {/* Telefonda 3 eşit sütun (r177 H11 — "Geçmiş" alt satıra düşüyordu) */}
          <TabsList className="grid h-auto w-full grid-cols-3 gap-1 group-data-[orientation=horizontal]/tabs:h-auto sm:inline-flex sm:w-fit">
            <TabsTrigger value="overview" className={cn('min-w-0 gap-1.5 px-2 sm:flex-none sm:px-3', TOUCH)}><LayoutDashboard aria-hidden="true" />{t('prs.sec.overview')}</TabsTrigger>
            <TabsTrigger value="objects" className={cn('min-w-0 gap-1.5 px-2 sm:flex-none sm:px-3', TOUCH)}>
              <List aria-hidden="true" />{t('prs.sec.objects')}
              {objectsAvailable && <Badge variant="secondary" className="h-4 min-w-4 rounded-full px-1.5 py-0 text-[10.5px] leading-4 font-bold tabular-nums max-sm:hidden">{formatNumber(sel.items.length, locale)}</Badge>}
            </TabsTrigger>
            <TabsTrigger value="history" className={cn('min-w-0 gap-1.5 px-2 sm:flex-none sm:px-3', TOUCH)}>
              <History aria-hidden="true" />{t('prs.sec.history')}
              <Badge variant="secondary" className="h-4 min-w-4 rounded-full px-1.5 py-0 text-[10.5px] leading-4 font-bold tabular-nums max-sm:hidden">{formatNumber(items.length, locale)}</Badge>
            </TabsTrigger>
          </TabsList>

          {showOld && (
            <div data-slot="prs-old-run">
              <AlertBanner tone="info" className="mb-0" icon={History}
                actions={<Button type="button" variant="outline" size="sm" className={TOUCH} data-slot="prs-latest"
                  onClick={() => { select(latestDone); setSection('overview'); requestFocus('hero') }}>{t('prs.old.latest')}</Button>}>
                {t('prs.old.body', formatDate(selScan.started_at))}
              </AlertBanner>
            </div>
          )}

          <TabsContent value="overview" className="min-w-0">
            <SelectedView detail={detail} selectedId={selectedId} t={t} canRun={canRun} running={running}
              onRetryDetail={() => loadDetail(selectedId)} onStart={start} starting={starting}
              latestDone={latestDone} onLatest={() => { select(latestDone); requestFocus('hero') }}>
              {done && (
                <ResourceScanDashboard scan={selScan} analysis={sel.analysis} prev={prev} history={items} selectedId={selectedId}
                  objectsAvailable={objectsAvailable} items={enriched}
                  onCategory={(c) => goObjects({ ...DEFAULT_FILTERS, sort: filters.sort, scope: 'all', types: [c] })}
                  onShowObjects={(rec) => goObjects(recFiltersByFinding(rec)
                    ? { ...DEFAULT_FILTERS, sort: filters.sort, scope: 'all', findings: [rec.code], rec: rec.code }
                    : { ...DEFAULT_FILTERS, sort: filters.sort, scope: 'all', urls: recItemUrls(rec), rec: rec.code })}
                  onSelectScan={(id) => { if (id !== selectedId) { select(id); requestFocus('hero') } }}
                  onGoRec={goRec} />
              )}
            </SelectedView>
          </TabsContent>

          <TabsContent value="objects" className="min-w-0">
            <SelectedView detail={detail} selectedId={selectedId} t={t} canRun={canRun} running={running}
              onRetryDetail={() => loadDetail(selectedId)} onStart={start} starting={starting} compact
              latestDone={latestDone} onLatest={() => select(latestDone)}>
              {done && (
                <ResourceScanObjects scanId={selectedId} items={enriched} timing={sel.analysis?.timing} countRef={countRef}
                  itemsKept={objectsAvailable} filters={filters} onFiltersChange={setFilters} detailRuns={hist.data?.limits?.detail_runs ?? 20}
                  recommendations={sel.analysis?.recommendations} mode={objectsMode} onModeChange={setObjectsMode} onOpenItem={setOpenItem} />
              )}
            </SelectedView>
          </TabsContent>

          <TabsContent value="history" className="min-w-0">
            <ResourceScanHistory items={items} selectedId={selectedId} limits={hist.data?.limits}
              mode={historyMode} onModeChange={(m) => { setHistoryMode(m); requestFocus('history') }}
              otherId={otherId} getDetail={getDetail}
              onCompareChange={({ scanId, otherId: o }) => { if (scanId != null && scanId !== selectedId) select(scanId); setOtherId(o ?? null) }}
              onShowRunning={() => requestFocus('running')}
              onSelect={(id) => {
                rememberDash()
                select(id)
                setSection('overview')
                requestFocus('hero')
              }} />
          </TabsContent>
        </Tabs>
      )}

      {openItem && (
        <ResourceScanObjectDetail item={openItem} onClose={() => setOpenItem(null)} recommendations={sel?.analysis?.recommendations}
          onGoRec={(code) => goRec(code)} />
      )}
    </div>
  )
}

/** Tamamlanan analizin bildirim metni (not + harf; başarısız / yarıda kalan ayrı). */
function doneText(scan, t, monitor) {
  const name = monitor?.name || monitor?.url || ''
  if (scan?.status === 'DONE') {
    const letter = gradeLetter(scan.grade, scan.grade_letter)
    return scan.grade != null ? t('prs.toast.doneGrade', name, String(scan.grade), letter || '—') : t('prs.toast.doneNamed', name)
  }
  return t('prs.toast.notDone', name)
}

/** Tost gövdesi + eylem düğmesi ("Aç"). Kutuya tıklamak zaten kapatır; düğme önce eylemi yapar. */
function ToastWithAction({ text, action, onAction }) {
  return (
    <span data-slot="prs-toast" className="flex min-w-0 flex-wrap items-center gap-2">
      <span className={WRAP}>{text}</span>
      <Button type="button" size="sm" variant="outline" data-slot="prs-toast-open" className={TOUCH}
        onClick={() => { try { onAction?.() } catch { /* gezinme başarısız — tost yine kapanır */ } }}>{action}</Button>
    </span>
  )
}

/** Yöntem açıklaması (başlıktaki bilgi düğmesi). */
function MethodText({ t }) {
  return (
    <ul data-slot="prs-method" className="m-0 flex max-w-xs list-disc flex-col gap-1 pl-4 text-xs">
      <li>{t('prs.method.server')}</li>
      <li>{t('prs.method.js')}</li>
      <li>{t('prs.method.notLighthouse')}</li>
      <li>{plural(t, 'prs.method.estimate', 6, 6)}</li>
      <li>{t('prs.method.lazy')}</li>
      <li>{t('prs.method.docs')}</li>
      <li>{t('prs.method.proxy')}</li>
      <li>{t('prs.method.integrity')}</li>
      <li>{t('prs.method.duration')}</li>
    </ul>
  )
}

function EmptyState({ canRun, startBtn, t }) {
  // Ana düğme açıklamanın HEMEN altında (r177 — eskiden olgu listesinin altında, ekranın dışında kalıyordu); yetkisiz
  // kullanıcıya davet başlığı yerine "Henüz analiz yok" ve not en üstte
  return (
    <div data-slot="prs-empty" className="flex min-w-0 flex-col items-center gap-4 rounded-xl border border-dashed px-4 py-6 text-center sm:py-8">
      <span className="flex size-12 items-center justify-center rounded-full bg-primary/10 text-primary"><ScanSearch aria-hidden="true" className="size-6" /></span>
      <div className="flex max-w-xl flex-col gap-1.5">
        <h4 className="m-0 text-base font-semibold text-foreground">{t(canRun ? 'prs.empty.title' : 'prs.empty.titleNoRun')}</h4>
        {canRun ? <p className="m-0 text-sm text-muted-foreground">{t('prs.empty.body')}</p>
          : <p data-slot="prs-cannot-run" className="m-0 text-sm text-muted-foreground">{t('prs.cannotRun')}</p>}
      </div>
      {canRun && startBtn}
      <ul data-slot="prs-empty-facts" className="m-0 grid w-full max-w-2xl list-none grid-cols-1 gap-2 p-0 text-left sm:grid-cols-2">
        {['server', 'js', 'estimate', 'docs'].map((k) => (
          <li key={k} className="flex min-w-0 gap-2 rounded-lg bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
            <Info aria-hidden="true" className="mt-px size-3.5 shrink-0" />
            <span className={WRAP}>{k === 'estimate' ? `${plural(t, 'prs.method.estimate', 6, 6)} ${t('prs.method.duration')}`
              : k === 'docs' ? `${t('prs.method.docs')} ${t('prs.method.lazy')}`
                : k === 'js' ? `${t('prs.method.js')} ${t('prs.method.notLighthouse')}` : t(`prs.method.${k}`)}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Süren analizin ilerlemesi — sayaç kendi yaprağında (üst bileşen saniyede bir çizilmez). */
function RunningCard({ scan, pollState, t, locale, sighting, onResume, onCancel, cancelling, cancelRequested }) {
  const maxRef = useRef({ id: null, pct: null })
  if (maxRef.current.id !== scan?.id) maxRef.current = { id: scan?.id, pct: null }
  // İlerleme geri gitmez (r177 H16): CSS'ten yeni nesne bulundukça toplam büyür; görülen en yüksek değer korunur
  const pct = monotonicPct(maxRef.current.pct, progressPct(scan))
  maxRef.current.pct = pct
  const done = scan?.progress?.done ?? 0
  const total = scan?.progress?.total ?? 0
  const stage = total > 0 ? 'measuring' : 'discovering'
  return (
    <Section slot="prs-running" icon={Hourglass} title={t('prs.run.title')} data-poll={pollState} data-stage={stage} tabIndex={-1}
      actions={onCancel && (
        <Button type="button" variant="outline" size="sm" data-slot="prs-cancel" className={TOUCH} onClick={onCancel}
          disabled={cancelling || cancelRequested} aria-busy={cancelling || undefined}>
          {cancelling ? <Spinner size={12} inline decorative /> : <Square aria-hidden="true" />}
          {cancelRequested ? t('prs.cancel.pending') : t('prs.cancel.button')}
        </Button>
      )}>
      <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-sm">
        {pollState === 'ok' && <Spinner size={14} label={t('prs.run.measuring')} className="text-primary" />}
        <span data-slot="prs-progress-text" className="font-semibold tabular-nums">
          {total > 0 ? plural(t, 'prs.run.progress', total, formatNumber(done, locale), formatNumber(total, locale)) : t('prs.run.discovering')}
        </span>
        <ToneBadge tone="muted" className={cn(CHIP, 'tabular-nums')}><Elapsed sighting={sighting?.id === scan?.id ? sighting : null} t={t} locale={locale} /></ToneBadge>
      </div>
      <ProgressBar value={pct} max={100} label={t('prs.run.progressLabel')} showValue={pct != null} />
      <p data-slot="prs-stage" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>
        {t(stage === 'measuring' ? 'prs.run.stageMeasuring' : 'prs.run.stageDiscovering')}
        {scan?.owner_pod ? ` · ${t('prs.run.pod', scan.owner_pod)}` : ''}
      </p>
      <p className="m-0 text-xs text-muted-foreground">{t('prs.run.background')}</p>
      {cancelRequested && <p data-slot="prs-cancel-pending" className="m-0 text-xs text-muted-foreground">{t('prs.cancel.pendingBody')}</p>}
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

/** Geçen süre (r177 H13): sunucu saatine göre görüldüğü andaki taban + o andan beri geçen MONOTON süre. */
function Elapsed({ sighting, t, locale }) {
  const [now, setNow] = useState(() => monoNow())
  useEffect(() => {
    const id = setInterval(() => setNow(monoNow()), 1000)
    return () => clearInterval(id)
  }, [])
  const ms = sighting && sighting.base != null ? sighting.base + Math.max(0, now - sighting.mono) : null
  return <span data-slot="prs-elapsed">{t('prs.run.elapsed', ms == null ? '—' : formatMs(Math.floor(ms / 1000) * 1000, locale))}</span>
}

/** Seçili analizin durumu: yükleniyor / okunamadı / başarısız / yarıda kaldı → aksi hâlde çocuklar (pano ya da nesneler). */
function SelectedView({ detail, selectedId, t, canRun, running, onRetryDetail, onStart, starting, compact = false, latestDone, onLatest, children }) {
  if (selectedId == null) return null
  if (running && selectedId === running.id) return null
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
  // Başarısız / yarıda kalan analizden son başarılı analize dönüş (r177)
  const latest = latestDone != null && latestDone !== selectedId && (
    <Button type="button" variant="outline" className={TOUCH} onClick={onLatest} data-slot="prs-show-latest">
      <History aria-hidden="true" />{t('prs.old.latestOk')}
    </Button>
  )
  const actions = (retry || latest) ? <>{latest}{retry}</> : undefined
  if (scan?.status === 'FAILED') {
    // Sayfaya ulaşılamadıysa (PAGE_FAILED) sunucu nedeni ham kod olarak gönderir — çevrilmiş metin gösterilir
    const failCause = scan.error_reason ? problemText(scan.error_reason, t) : ''
    return (
      <div data-slot="prs-failed">
        <StatusBlock tone="danger" icon={CircleAlert} title={t('prs.runFail.title')} className={compact ? 'py-6' : undefined}
          description={failCause ? (
            <>
              <span className="block">{scan.error || t('prs.runFail.body')}</span>
              <span data-slot="prs-failed-cause" className="mt-1 block font-medium text-foreground">{t('prs.runFail.cause', failCause)}</span>
            </>
          ) : (scan.error || t('prs.runFail.body'))} actions={actions} />
      </div>
    )
  }
  if (scan?.status === 'INTERRUPTED') {
    const cancelled = isCancelled(scan)
    return (
      <div data-slot="prs-interrupted" data-cancelled={cancelled ? 'true' : undefined}>
        <AlertBanner tone={cancelled ? 'info' : 'warning'} className="mb-0" icon={cancelled ? Square : TriangleAlert}
          title={t(cancelled ? 'prs.cancelled.title' : 'prs.interrupted.title')} actions={actions}>
          {scan.error || t(cancelled ? 'prs.cancelled.body' : 'prs.interrupted.body')}
        </AlertBanner>
      </div>
    )
  }
  return children
}
