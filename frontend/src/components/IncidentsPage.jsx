import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import {
  Siren, Info, UserCheck, CheckCircle2, OctagonAlert, Users, UserX, RotateCcw, FilterX, Download, FileText, FileSpreadsheet,
  Activity, Keyboard, Sparkles, X,
} from 'lucide-react'
import { api } from '../api/client'
import { useT, useDateLocale } from '../i18n/index.jsx'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import { useIsMobile } from '../hooks/use-mobile.js'
import { useServerPagination } from '../hooks/useServerPagination.js'
import { useUrlQuerySync, readUrlParam } from '../hooks/useUrlQuerySync.js'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { useDelayedFlag } from '../hooks/useDelayedFlag.js'
import { VIEW_SPECS } from '../hooks/userPrefsModel.js'
import { navigateTo } from '../utils/navigate.js'
import { downloadBlob } from '../utils/csvExport.js'
import { clientFailure, errorInfoOf } from '../utils/errorMessages.js'
import { shortcutEligible, letterOf, SEQUENCE_MS } from '../utils/keyboardShortcuts.js'
import { Spinner } from './ui/Progress.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import SavedViewsMenu from './ui/SavedViewsMenu.jsx'
import MonitorStatsBar from './MonitorStatsBar.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { Kbd } from '@/components/shadcn/kbd'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { cn } from '@/lib/utils'
import IncidentsHeader from './incidents/IncidentsHeader.jsx'
import IncidentsToolbar from './incidents/IncidentsToolbar.jsx'
import IncidentBoard, { BoardSkeleton } from './incidents/IncidentBoard.jsx'
import IncidentsTable, { TableSkeleton } from './incidents/IncidentsTable.jsx'
import IncidentDetailSheet from './incidents/IncidentDetailSheet.jsx'
import IncidentScopeFilter from './incidents/IncidentScopeFilter.jsx'
import IncidentMetricsStrip from './incidents/IncidentMetricsStrip.jsx'
import ActionNoteDialog from './incidents/ActionNoteDialog.jsx'   // gerekçeli onayla / çöz penceresi (2026-09-28)
import ReNotifyConfirmModal from './admin/alerts/ReNotifyConfirmModal.jsx'
import { contextFromIncident } from './incidents/actionNoteModel.js'
import { incidentSheet, incidentExportName } from './incidents/incidentsExport.js'
import {
  FILTER_DEFAULTS, serverParams, clientFacet, hasClientFacet, summarize, summaryFromServer, activeFilters, teamOptions,
  iso24hAgo, filtersFromUrl, filtersToUrl, sortFromUrl, severityMeta, rowName, isOpen, isAcked,
  SCOPE_MINE, SCOPE_OTHERS, normalizeIncidentScope, scopeParam, canActOn, canDeleteIncident, canCommentOn, isSelectable,
  ackable,
} from './incidents/incidentsModel.js'

const BANNER_KEY = 'incidents-banner-dismissed'
const VIEW_KEY = 'sm.incidents.view'
/** Eski sunucu yedeği: açık küme için tek istekte alınan en fazla satır (sunucu tavanı 200). */
const OPEN_SAMPLE = 200
const REFRESH_MS = 60_000
/** xlsxWriter yalnız Excel istenince yüklenir (açılış parçasına girmez — lazyChunks kapısı). */
const loadXlsxWriter = () => import('../utils/xlsxWriter.js')
/** Okunamayan sayı "—" (asla 0). */
const shown = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : '—')
/** Toplu işlem satır sonuçları (sunucu kodları) — bilinmeyen "unknown". */
const BULK_OUTCOMES = ['processed', 'skipped', 'already_acknowledged', 'closed', 'failed']
/**
 * Eski pod (uç yok): 404 / 405, ya da `/{id}` (Long) eşlemesine düşen istek → 400 `INVALID_PARAMETER` (Aktivite deseni).
 * Bu yanıt "bu sürümde yok" sayılır, sunucu hatası değil.
 */
export function isOldServerAnswer(res) {
  const info = errorInfoOf(res)
  const status = Number(res?.status ?? info?.status)
  const code = res?.code ?? info?.code
  return status === 404 || status === 405 || (status === 400 && code === 'INVALID_PARAMETER')
}

/**
 * Olaylar konsolu (2026-09-26 yeniden tasarım) — tüm izlemelerin makine-üretimi olayları: özet kartları (süzer),
 * Pano | Liste görünümü, süzgeç araç çubuğu + etkin çipler, sağdan açılan detay (zaman çizelgesi + yorum) ve
 * standart sunucu sayfalaması. Parçalar `components/incidents/*`; bu dosya veri + durum + eylemleri tutar.
 *
 * Yetki: satır başına SUNUCU bayrakları (`can_manage` / `can_act` / `can_delete` / `can_comment` / `can_notify`) — eylem
 * düğmeleri yalnız hakkı olan satırda çizilir; sunucu her yazma ucunda yine kendi kapısını uygular. Uygulama düzeyi
 * `incident` URL anahtarı (e-posta derin bağlantısı) okunur; detay KAPANINCA adresten silinir (2026-11-13 — kalsaydı
 * yenilemede / geri dönüşte pencere yeniden açılırdı).
 *
 * <p>Org geneli salt okunur görünürlük (2026-09-28): "Takımımın olayları | Diğer ekiplerin olayları | Tümü" süzgeci.
 *
 * <p><b>Zenginleştirme (2026-11-13):</b> süzgeçler SUNUCUDA (önem CSV, takım, onaylı, takımsız, takımım, tekrarlayanlar,
 * "Çözüldü (24 sa)" çözülme anıyla — yanıt `server_facets: true`); eski sunucuda önceki istemci yolu + not. Özet kartları
 * `/summary` ucundan KESİN (okunamayan "—"); müdahale süreleri şeridi (MTTA / MTTR, en uzun açık). Liste görünümünde toplu
 * onayla / çöz (satır başına sonuç), telefonda kartta hızlı Onayla / Çöz. Detayda yeniden bildir. Dakikalık sessiz
 * yenilemede gelen yeni açık olaylar vurgulanır ("N yeni olay", aria-live). Klavye: j / k, Enter, a, r, c. CSV / Excel
 * dışa aktarım, kayıtlı görünümler, Alarm Geçmişi gürültü analizine bağlantı.
 *
 * <p><b>Titremesiz geçiş (stale-while-revalidate):</b> iskelet YALNIZ ilk yüklemede; sonraki süzgeç/kapsam/sayfa değişiminde
 * ekrandaki sonuç yerinde kalır, ~180 ms'den uzun yüklemede soluklaşır. Yalnız EN SON isteğin yanıtı uygulanır.
 */
export default function IncidentsPage({ systemRole, teamId }) {
  const t = useT()
  const dateLocale = useDateLocale()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  // Telefonda pano sekmeler, liste kartlar, süzgeçler alt Sheet (yapı farkı → useIsMobile; görünüm farkı CSS'te)
  const phone = useIsMobile()

  const [filters, setFilters] = useState(() => filtersFromUrl(readUrlParam))
  // Olay kapsamı: URL (scope) > "Takımımın olayları". Sunucu gerçekte uygulananı `scope` ile söyler (ayar kapalıysa mine).
  const [scope, setScope] = useState(() => normalizeIncidentScope(readUrlParam('scope')))
  const [scopeCounts, setScopeCounts] = useState(null)   // null = süzgeç yok (ayar kapalı / global görüntüleyici)
  const [sort, setSort] = useState(() => sortFromUrl(readUrlParam))
  const [view, setView] = useState(() => {
    const fromUrl = readUrlParam('view')
    if (fromUrl === 'list' || fromUrl === 'board') return fromUrl
    try { return localStorage.getItem(VIEW_KEY) === 'list' ? 'list' : 'board' } catch { return 'board' }
  })
  const [rows, setRows] = useState([])
  const [typeCounts, setTypeCounts] = useState({})
  const [serverFacets, setServerFacets] = useState(true)   // yanıt `server_facets` taşımazsa (eski sunucu) istemci süzer
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [summary, setSummary] = useState(null)          // null = henüz bilinmiyor / alınamadı → kartlar çizilmez (uydurma sayı yok)
  const [selectedId, setSelectedId] = useState(null)    // açık detay
  const [fallback, setFallback] = useState(null)        // sayfada olmayan (derin bağlantı / en uzun açık) olay
  const [focusComposer, setFocusComposer] = useState(false)
  const [noteDialog, setNoteDialog] = useState(null)   // gerekçeli pencere: { action, items, submit(note) }
  const [bannerOpen, setBannerOpen] = useState(() => { try { return localStorage.getItem(BANNER_KEY) !== 'true' } catch { return true } })
  const [selectedIds, setSelectedIds] = useState(() => new Set())   // toplu işlem seçimi (liste görünümü)
  const [bulkResult, setBulkResult] = useState(null)   // { action, items: [{ id, name, outcome }] }
  const [newIds, setNewIds] = useState(() => new Set())   // sessiz yenilemede gelen yeni açık olaylar
  const [renotify, setRenotify] = useState(null)       // { alertId, domain, recipients, webhook, emailBlockReason }
  const [renotifySending, setRenotifySending] = useState(false)
  const [notifyingId, setNotifyingId] = useState(null)
  const [exporting, setExporting] = useState(null)     // null | 'csv' | 'xlsx'
  const [cursorId, setCursorId] = useState(null)       // klavye imleci (j / k)
  const reqIdRef = useRef(0)
  const summarySeqRef = useRef(0)   // özet de yarışır (dakikalık tazeleme + teamId değişimi + elle tazeleme)
  const prevIdsRef = useRef({ key: null, maxId: null })   // son yüklemedeki EN BÜYÜK kimlik (yeni olay tespiti)
  const resultsRef = useRef(null)
  // Son BAŞARILI yanıtın istek anahtarı + o an ekranda uygulanan süzgeçler (key null = hiç yüklenmedi → iskelet).
  const [loaded, setLoaded] = useState(() => ({ key: null, filters: null }))

  // "Son 24 saat" sınırı istek anında hesaplanır; süzgeç/sıralama değişmedikçe sabit kalır (her saniye yeni istek YOK).
  const params = useMemo(() => serverParams(filters, sort, Date.now(), scope), [filters, sort, scope])
  // Sayfalama standardı: süzgeç/sıralama değişince sayfa 1 AYNI render'da; boyut ön ayardan; URL page/ps.
  const sp = useServerPagination({ listKey: 'incidents', preset: 'page', resetDeps: [params], apiBase: 0, url: { pageKey: 'page', sizeKey: 'ps' } })
  const { apiPage, pageSize } = sp
  const total = sp.total ?? 0
  const requestKey = JSON.stringify([params, apiPage, pageSize])

  useUrlQuerySync({ ...filtersToUrl(filters, view, sort), scope: scope === SCOPE_MINE ? null : scope })
  useEffect(() => { try { localStorage.setItem(VIEW_KEY, view) } catch { /* yoksay */ } }, [view])
  // Süzgeç / sayfa / görünüm değişimi = etkileşim: seçim, toplu sonuç bandı ve "yeni" vurgusu temizlenir.
  useEffect(() => { setSelectedIds(new Set()); setNewIds(new Set()); setBulkResult(null) }, [requestKey, view])

  const load = useCallback(async ({ silent = false } = {}) => {
    const myId = ++reqIdRef.current
    const key = JSON.stringify([params, apiPage, pageSize])
    setLoading(true)
    try {
      const res = await api.monitoring.incidents.list({ ...params, page: apiPage, size: pageSize })
      if (myId !== reqIdRef.current) return   // yalnız EN SON isteğin yanıtı (bayat yanıt grid'i ezmesin)
      if (res?.success) {
        const data = Array.isArray(res.data) ? res.data : []
        // Yeni olay tespiti (2026-11-13 inceleme): YALNIZ aynı istek anahtarının sessiz yenilemesinde, kimliği önceki
        // yüklemede görülen EN BÜYÜK kimlikten büyük AÇIK olaylar — sayfa 2+'ye kayan eski satırlar "yeni" sayılmaz.
        const prev = prevIdsRef.current
        const ids = data.map((r) => Number(r.id)).filter(Number.isFinite)
        const maxNow = ids.length ? Math.max(...ids) : null
        if (silent && prev.key === key && prev.maxId != null) {
          const fresh = data.filter((r) => isOpen(r) && Number(r.id) > prev.maxId).map((r) => String(r.id))
          if (fresh.length) setNewIds((cur) => new Set([...cur, ...fresh]))
        }
        const keepMax = prev.key === key && prev.maxId != null ? Math.max(prev.maxId, maxNow ?? prev.maxId) : maxNow
        prevIdsRef.current = { key, maxId: keepMax }
        setRows(data); sp.bind(res); setTypeCounts(res.type_counts ?? {}); setError(null)
        setServerFacets(res.server_facets === true)
        setLoaded((l) => ({ ...l, key }))
        setScopeCounts(res.scope_counts && typeof res.scope_counts === 'object' ? res.scope_counts : null)
        // Sunucu isteneni uygulamadıysa (ayar kapalı, global görüntüleyici) seçim gerçeğe döner — tek ek istek, döngü yok.
        if (res.scope != null) { const eff = normalizeIncidentScope(res.scope); setScope((cur) => (cur === eff ? cur : eff)) }
      }
      else { setError(res?.error || t('incov.loadError')); setLoaded((l) => ({ ...l, key })) }
    } catch (e) {
      // Başarısız istek de YERLEŞİR (eski satırlar yerinde kalır).
      if (myId === reqIdRef.current) { setError(e?.message || t('incov.loadError')); setLoaded((l) => ({ ...l, key })) }
    } finally {
      if (myId === reqIdRef.current) setLoading(false)
    }
  }, [params, apiPage, pageSize]) // eslint-disable-line react-hooks/exhaustive-deps

  /**
   * Özet: önce kesin uç (`/summary`); tanınmayan yanıt (eski sunucu) → eski yol: açık küme (toplam + ≤200 satır) ve son 24
   * saatte çözülen toplamı (çözülme anıyla). Okunamayan alan `null` — kartta "—".
   */
  const loadSummary = useCallback(async ({ fresh = false } = {}) => {
    const my = ++summarySeqRef.current
    try {
      const res = await Promise.resolve()
        .then(() => api.monitoring.incidents.summary({ ...scopeParam(scope), ...(fresh ? { fresh: true } : {}) }))
        .catch(() => null)
      if (my !== summarySeqRef.current) return
      const exact = summaryFromServer(res)
      if (exact) { setSummary(exact); return }
      // Yedek "Çözüldü (24 sa)" YALNIZ çözülme anıyla (`resolvedSince`); sunucu bu süzgeci uyguladığını söylemezse
      // (`server_facets` yok — eski pod tüm çözülmüşleri sayardı) değer bilinmiyor: "—".
      const [open, res24] = await Promise.all([
        api.monitoring.incidents.list({ status: 'ongoing', page: 0, size: OPEN_SAMPLE, ...scopeParam(scope) }),
        api.monitoring.incidents.list({ status: 'resolved', resolvedSince: iso24hAgo(Date.now()), page: 0, size: 1, ...scopeParam(scope) }),
      ])
      if (my !== summarySeqRef.current) return   // bayat yanıt — daha yeni bir özet isteği yolda
      if (!open?.success) { setSummary(null); return }
      const resolved24 = res24?.success && res24.server_facets === true ? Number(res24.total ?? 0) : null
      setSummary(summarize(open.data ?? [], Number(open.total ?? 0), resolved24, teamId))
    } catch {
      if (my === summarySeqRef.current) setSummary(null)
    }
  }, [teamId, scope])

  useEffect(() => { load() }, [load])
  useEffect(() => { loadSummary() }, [loadSummary])
  const refreshAll = useCallback(() => { load(); loadSummary({ fresh: true }) }, [load, loadSummary])
  // Sekme görünürken dakikada bir SESSİZ tazele (yeni olayları vurgular) — arka planda durur.
  const silentRefresh = useCallback(() => { load({ silent: true }); loadSummary() }, [load, loadSummary])
  useVisibleInterval(silentRefresh, REFRESH_MS, false)

  // E-posta derin bağlantısı: ?incident=<id>[&action=comment] — olay sayfalı listede olmayabilir → tekil uçtan.
  // `action` tüketilince URL'den silinir; `incident` detay kapanınca silinir (closeDetail).
  useEffect(() => {
    let id = null, action = null
    try { const p = new URLSearchParams(window.location.search); id = p.get('incident'); action = p.get('action') } catch { /* yoksay */ }
    if (!id) return undefined
    let alive = true
    api.monitoring.incidents.get(id).then((res) => {
      if (!alive) return
      if (res?.success && res.data) {
        setFallback(res.data)
        setSelectedId(String(res.data.id))
        setFocusComposer(action === 'comment')
      } else if (res && res.success === false) {
        toast.error(res.error || t('inc.deepLinkFail'))
      }
      try { const url = new URL(window.location.href); url.searchParams.delete('action'); window.history.replaceState(window.history.state, '', url) } catch { /* yoksay */ }
    }).catch(() => { if (alive) toast.error(t('inc.deepLinkFail')) })
    return () => { alive = false }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  // Değişmeyen yama durumu YENİLEMEZ: aynı değerle yeni nesne yeni istek demekti (gereksiz yeniden yükleme = titreme).
  const patchFilters = useCallback((patch) => setFilters((f) => (Object.keys(patch).every((k) => f[k] === patch[k]) ? f : { ...f, ...patch })), [])
  const resetFilters = useCallback(() => setFilters({ ...FILTER_DEFAULTS }), [])
  const toggleSort = useCallback((key) => setSort((s) => (s.by === key ? { by: key, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { by: key, dir: 'desc' })), [])
  const dismissBanner = () => { setBannerOpen(false); try { localStorage.setItem(BANNER_KEY, 'true') } catch { /* yoksay */ } }

  /** Satırı yerinde güncelle (iyimser güncelleme + sunucu yanıtı); derin bağlantı olayı da aynı kimlikteyse. */
  const patchRow = useCallback((id, patch) => {
    setRows((rs) => rs.map((r) => (String(r.id) === String(id) ? { ...r, ...patch } : r)))
    setFallback((f) => (f && String(f.id) === String(id) ? { ...f, ...patch } : f))
  }, [])

  // Yerleşmiş mi: ekrandaki satırlar GÜNCEL isteğin yanıtı mı? Değilse son yerleşmiş görünüm aynen kalır.
  const settled = loaded.key === requestKey
  if (settled && loaded.filters !== filters) setLoaded((l) => ({ ...l, filters }))
  const shownFilters = settled ? filters : (loaded.filters ?? filters)
  // Sunucu süzdüyse satırlar olduğu gibi; eski sunucuda hızlı süzgeçler yüklenen sayfaya uygulanır.
  const visibleRows = useMemo(() => (serverFacets ? rows : clientFacet(rows, shownFilters, teamId)), [serverFacets, rows, shownFilters, teamId])
  const teams = useMemo(() => summary?.teamOptions ?? teamOptions(rows), [summary, rows])
  const shownActive = activeFilters(shownFilters)
  const busy = useDelayedFlag(loading && !settled, 180)
  const selected = useMemo(() => {
    if (selectedId == null) return null
    return rows.find((r) => String(r.id) === String(selectedId)) || (fallback && String(fallback.id) === String(selectedId) ? fallback : null)
  }, [rows, fallback, selectedId])

  const openDetail = useCallback((inc, { composer = false } = {}) => {
    setFocusComposer(composer); setSelectedId(String(inc.id)); setCursorId(String(inc.id))
    setNewIds((cur) => { if (!cur.has(String(inc.id))) return cur; const n = new Set(cur); n.delete(String(inc.id)); return n })
  }, [])
  const closeDetail = useCallback(() => {
    setSelectedId(null); setFocusComposer(false)
    // Derin bağlantı anahtarı kapanınca silinir (yenileme / geri dönüş pencereyi yeniden açmasın) — 2026-11-13
    try {
      const url = new URL(window.location.href)
      if (url.searchParams.has('incident') || url.searchParams.has('action')) {
        url.searchParams.delete('incident'); url.searchParams.delete('action')
        window.history.replaceState(window.history.state, '', url)
      }
    } catch { /* yoksay */ }
  }, [])
  /** Kimlikle aç (en uzun açık olay şeridi): sayfada yoksa tekil uçtan. */
  const openById = useCallback(async (id) => {
    const inPage = rows.find((r) => String(r.id) === String(id))
    if (inPage) { openDetail(inPage); return }
    let res
    try { res = await api.monitoring.incidents.get(id) } catch { res = null }
    if (res?.success && res.data) { setFallback(res.data); openDetail(res.data) }
    else toast.error(res?.error || t('inc.deepLinkFail'))
  }, [rows, openDetail, toast, t])

  // ── Eylemler (gerekçe zorunlu — sunucu AlertActionNote ile aynı kural) ──
  function ack(inc) {
    if (!canActOn(inc)) return
    setNoteDialog({ action: 'ack', items: [contextFromIncident(inc)], submit: (note) => submitAck(inc, note) })
  }
  async function submitAck(inc, note) {
    patchRow(inc.id, { acknowledged: true })   // iyimser
    let r
    try { r = await api.admin.acknowledgeAlert(inc.id, note) } catch { r = null }
    if (!r?.success) { patchRow(inc.id, { acknowledged: inc.acknowledged ?? false }); return { ok: false, error: r?.error || t('incov.actionError') } }
    patchRow(inc.id, { acknowledged: true, acknowledged_by: r.data?.acknowledged_by ?? null, acknowledged_at: r.data?.acknowledged_at ?? null })
    toast.success(t('incov.ackDone'))
    loadSummary({ fresh: true })
    return { ok: true }
  }
  function resolve(inc) {
    if (!canActOn(inc)) return
    setNoteDialog({ action: 'resolve', items: [contextFromIncident(inc)], submit: (note) => submitResolve(inc, note) })
  }
  async function submitResolve(inc, note) {
    const nowIso = new Date().toISOString().slice(0, 19)
    patchRow(inc.id, { status: 'resolved', resolved_at: nowIso })   // iyimser
    let r
    try { r = await api.admin.resolveAlert(inc.id, note) } catch { r = null }
    if (!r?.success) { patchRow(inc.id, { status: inc.status, resolved_at: inc.resolved_at ?? null }); return { ok: false, error: r?.error || t('incov.actionError') } }
    patchRow(inc.id, { status: 'resolved', resolved_at: r.data?.resolved_at || nowIso, resolved_by: r.data?.resolved_by ?? null })
    toast.success(t('incov.resolveDone'))
    loadSummary({ fresh: true })
    return { ok: true }
  }
  async function del(inc) {
    if (!canDeleteIncident(inc, isAdmin)) return
    // Açık olay silinirse izleme sorun sürdükçe yeni olay açar — kullanıcı bunu bilerek silsin (2026-11-13)
    const msg = `${rowName(inc, dateLocale)}\n\n${t('incov.deleteConfirm')}${isOpen(inc) ? `\n\n${t('incov.deleteOpenWarn')}` : ''}`
    if (!await showConfirm({ title: t('incov.delete'), message: msg, confirmText: t('incov.delete'), variant: 'danger' })) return
    let r
    try { r = await api.monitoring.incidents.remove(inc.id) } catch { r = null }
    if (!r?.success) { toast.error(r?.error || t('incov.deleteError')); return }
    toast.success(t('incov.deleted'))
    if (String(selectedId) === String(inc.id)) closeDetail()
    refreshAll()
  }
  const onCommentDelta = (delta) => { if (selected) patchRow(selected.id, { comment_count: Math.max(0, (selected.comment_count ?? 0) + delta) }) }

  // ── Toplu onayla / çöz (liste görünümü; yalnız işlem yapılabilen açık satırlar) ──
  const selection = useMemo(() => ({
    ids: selectedIds,
    setMany: (ids, on) => setSelectedIds((cur) => {
      const next = new Set(cur)
      for (const id of ids) { if (on) next.add(String(id)); else next.delete(String(id)) }
      return next
    }),
  }), [selectedIds])
  const selectedRows = useMemo(() => visibleRows.filter((r) => selectedIds.has(String(r.id)) && isSelectable(r)), [visibleRows, selectedIds])
  // Toplu ONAY zaten onaylı olayları göndermez (ilk sahiplenen / an / gerekçe korunur, MTTA şişmez); sonuç listesinde
  // "zaten onaylı — atlandı" olarak görünürler. Sunucu da aynı kuralı uygular (already_acknowledged).
  const ackSkipped = useMemo(() => selectedRows.filter((r) => !ackable(r)), [selectedRows])
  function bulk(action) {
    const rowsFor = action === 'ack' ? selectedRows.filter(ackable) : selectedRows
    if (rowsFor.length === 0) return
    const items = rowsFor.map(contextFromIncident)
    const ids = rowsFor.map((r) => r.id)
    const names = new Map(selectedRows.map((r) => [String(r.id), r.monitor?.name || r.domain || `#${r.id}`]))
    const preSkipped = action === 'ack' ? ackSkipped.map((r) => r.id) : []
    setNoteDialog({ action, items, submit: (note) => runBulk(action, ids, names, note, preSkipped) })
  }
  async function runBulk(action, ids, names, note, preSkipped = []) {
    let res
    try { res = await api.admin.bulkAlertAction(action === 'resolve' ? 'resolve' : 'acknowledge', ids, note) } catch { res = null }
    if (!res?.success) return { ok: false, error: res?.error || t('incov.bulk.failed') }
    const byId = new Map((Array.isArray(res.data?.results) ? res.data.results : []).map((x) => [String(x.id), x.outcome]))
    const items = [
      ...ids.map((id) => ({ id, name: names.get(String(id)), outcome: byId.get(String(id)) || 'unknown' })),
      ...preSkipped.map((id) => ({ id, name: names.get(String(id)), outcome: 'already_acknowledged' })),
    ]
    const { processed = 0, failed = 0 } = res.data ?? {}
    const skipped = Number(res.data?.skipped ?? 0) + preSkipped.length
    setBulkResult({ action, items, processed, skipped, failed })
    const text = t('incov.bulk.summary', processed, skipped, failed)
    if (failed) toast.error(text); else toast.success(text)
    setSelectedIds(new Set())
    refreshAll()
    return { ok: true }
  }

  // ── Yeniden bildir (detaydan; sunucu `can_notify`) — Alarm Geçmişi'nin önizleme → onay penceresi akışı ──
  async function notify(inc) {
    setNotifyingId(String(inc.id))
    let res
    try { res = await api.admin.previewReNotify(inc.id) } catch { res = null } finally { setNotifyingId(null) }
    if (res?.success) {
      setRenotify({ alertId: inc.id, domain: inc.domain || '', recipients: res.data?.recipients || [], webhook: res.data?.webhook || null,
        emailBlockReason: res.data?.email_block_reason || null })
    } else {
      toast.error(res?.error || t('alh.renotifyModal.previewError'))
    }
  }
  async function sendReNotify(excludeEmails, excludeUsernames = [], { pushCount = 0 } = {}) {
    if (!renotify) return
    setRenotifySending(true)
    try {
      const body = {}
      if (excludeEmails.length) body.excludeEmails = excludeEmails
      if (excludeUsernames.length) body.excludeUsernames = excludeUsernames
      const res = await api.admin.reNotifyAlert(renotify.alertId, Object.keys(body).length ? body : undefined)
      if (res?.success) {
        const mails = res.data?.recipients_queued ?? res.data?.contacts_queued ?? 0
        toast.success(Number(mails) === 0 && pushCount > 0 ? t('alh.notifyQueuedPush', pushCount) : t('alh.notifyQueued', mails))
        setRenotify(null)
      } else {
        toast.error(res?.error || t('alh.renotifyFailed'))
      }
    } catch (e) {
      toast.error(e?.message || t('alh.renotifyFailed'))
    } finally {
      setRenotifySending(false)
    }
  }

  // ── Dışa aktarım (CSV sunucudan; Excel sunucunun JSON'undan tarayıcıda) — o anki süzgeç + kapsam, tüm sayfalar ──
  async function exportCsv() {
    setExporting('csv')
    try {
      const res = await api.monitoring.incidents.exportCsv(params)
      if (res?.success) toast.success(res.truncated ? t('incov.exportTruncated', res.maxRows ?? 10000) : t('incov.exportDone', res.name))
      else if (isOldServerAnswer(res)) toast.error(t('incov.exportUnsupported'))
      else toast.error(res?.code === 'DOWNLOAD_FAILED' ? t('incov.exportFailed') : (res?.error || t('incov.exportFailed')), { details: res })
    } finally { setExporting(null) }
  }
  async function exportXlsx() {
    setExporting('xlsx')
    try {
      const res = await api.monitoring.incidents.exportJson(params)
      if (!res?.success && isOldServerAnswer(res)) { toast.error(t('incov.exportUnsupported')); return }
      if (!res?.success || !Array.isArray(res.data?.rows)) { toast.error(res?.error || t('incov.exportFailed'), { details: res }); return }
      let mod
      try { mod = await loadXlsxWriter() } catch (e) { toast.error(clientFailure(e, t).message, { details: e }); return }
      const name = incidentExportName('xlsx')
      const bytes = mod.buildXlsx([incidentSheet(res.data.rows, t)])
      if (!downloadBlob(name, new Blob([bytes], { type: mod.XLSX_MIME }))) { toast.error(t('incov.exportFailed')); return }
      toast.success(res.data.truncated ? t('incov.exportTruncated', res.data.max_rows ?? 10000) : t('incov.exportDone', name))
    } catch (e) {
      toast.error(e?.message || t('incov.exportFailed'))
    } finally { setExporting(null) }
  }

  // ── Klavye: j / k imleç, Enter aç, a onayla, r çöz, c yorum (yazı alanı / menü / pencere açıkken çalışmaz) ──
  const keyState = useRef({ rows: visibleRows, cursorId, gAt: 0 })
  keyState.current.rows = visibleRows
  keyState.current.cursorId = cursorId
  const actionsRef = useRef(null)
  actionsRef.current = { ack, resolve, openDetail }
  const focusIncident = useCallback((id) => {
    const root = resultsRef.current
    if (!root) return
    const el = root.querySelector(`[data-incident-id="${String(id).replace(/[^\w-]/g, '')}"]`)
    const target = el?.matches?.('tr') ? el : el?.querySelector?.('[data-incident-open]') || el
    try { target?.focus?.({ preventScroll: false }); target?.scrollIntoView?.({ block: 'nearest' }) } catch { /* jsdom */ }
  }, [])
  useEffect(() => {
    const onKey = (e) => {
      const now = Date.now()
      const st = keyState.current
      // Genel "g + harf" dizisinin ikinci tuşu bu sayfanın kısayolu sayılmaz (a = Alarm Geçmişi, c = …)
      const afterG = st.gAt > 0 && now - st.gAt <= SEQUENCE_MS
      if (!shortcutEligible(e)) return
      const letter = letterOf(e)
      if (letter === 'g') { st.gAt = now; return }
      st.gAt = 0
      if (afterG || selectedId != null) return   // detay açıkken pencere içindeki kısayollar geçerli
      const list = st.rows
      if (!list.length) return
      const idx = list.findIndex((r) => String(r.id) === String(st.cursorId))
      const current = idx >= 0 ? list[idx] : null
      if (letter === 'j' || letter === 'k') {
        e.preventDefault()
        const next = letter === 'j' ? Math.min(list.length - 1, idx + 1) : Math.max(0, idx < 0 ? 0 : idx - 1)
        const id = String(list[next].id)
        setCursorId(id); focusIncident(id)
        return
      }
      if (!current) return
      const a = actionsRef.current
      if (e.key === 'Enter') {
        // Enter YALNIZ odak gövdedeyken imleçteki olayı açar (2026-11-13 inceleme): odak bir düğme / bağlantı / satır /
        // kartta ise o öğenin kendi Enter'ı çalışır (sayfalama düğmesi sayfayı değiştirir, satır kendini açar).
        const ae = document.activeElement
        if (ae && ae !== document.body && ae !== document.documentElement) return
        e.preventDefault(); a.openDetail(current); return
      }
      if (letter === 'a' && isOpen(current) && !isAcked(current) && canActOn(current)) { e.preventDefault(); a.ack(current); return }
      if (letter === 'r' && isOpen(current) && canActOn(current)) { e.preventDefault(); a.resolve(current); return }
      if (letter === 'c' && canCommentOn(current)) { e.preventDefault(); a.openDetail(current, { composer: true }) }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [selectedId, focusIncident])
  // Odak / tıklama bir olaya geçince imleç onu izler (a / r / c odaktaki olaya uygulanır).
  const onResultsFocus = (e) => {
    const id = e.target?.closest?.('[data-incident-id]')?.getAttribute('data-incident-id')
    if (id) setCursorId(id)
  }

  // ── Özet kartları (MonitorStatsBar) — sunucunun döndürdüğü sayılar; okunamayan "—" ──
  const tiles = useMemo(() => {
    if (!summary) return []
    const sub = summary.exact ? undefined : t('incov.tile.countedNote', summary.sampled)
    return [
      { key: 'open',       Icon: Siren,        cls: 'error',    label: t('incov.tile.open'),       value: shown(summary.open),       hint: t('incov.tile.openHint') },
      { key: 'ack',        Icon: UserCheck,    cls: 'total',    label: t('incov.tile.ack'),        value: shown(summary.ack),        hint: t('incov.tile.ackHint'), sub },
      { key: 'resolved24', Icon: CheckCircle2, cls: 'valid',    label: t('incov.tile.resolved24'), value: shown(summary.resolved24), hint: t('incov.tile.resolved24Hint') },
      { key: 'critical',   Icon: OctagonAlert, cls: 'critical', label: t('incov.tile.critical'),   value: shown(summary.critical),   hint: t('incov.tile.criticalHint'), sub },
      { key: 'unassigned', Icon: UserX,        cls: 'warning',  label: t('incov.tile.unassigned'), value: shown(summary.unassigned), hint: t('incov.tile.unassignedHint'), sub },
      ...(teamId != null ? [{ key: 'mine', Icon: Users, cls: 'total', label: t('incov.tile.mine'), value: shown(summary.mine), hint: t('incov.tile.mineHint'), sub }] : []),
    ]
  }, [summary, teamId, t])

  const typeLabel = (type) => (t(`incov.type.${type}`) !== `incov.type.${type}` ? t(`incov.type.${type}`) : type)
  const labelsFor = (f) => {
    switch (f.key) {
      case 'stat': return f.value === 'resolved' ? t('incov.resolved') : t(`incov.tile.${f.value}`)
      case 'rootCause': return typeLabel(f.value)
      case 'q': return t('incov.chip.search', f.value)
      case 'since': return t('incov.chip.since', f.value)
      case 'until': return t('incov.chip.until', f.value)
      case 'level': return f.value.includes(',') ? t('incov.severityCritHigh') : t(severityMeta(f.value).key)
      case 'team': {
        const tm = teams.find((x) => x.id === String(f.value))
        return tm ? t('incov.chip.team', tm.name) : t('incov.chip.teamUnknown')
      }
      case 'recurring': return t('incov.chip.recurring')
      default: return f.value
    }
  }

  // İskelet YALNIZ ilk yüklemede (hiç başarılı yanıt yokken); sonrasında eski sonuç yerinde kalır.
  const initial = loaded.key == null && !error
  const facetNote = !serverFacets && hasClientFacet(shownFilters) && visibleRows.length < rows.length
  const paged = total > visibleRows.length
  const cardProps = useCallback((inc) => ({
    isNew: newIds.has(String(inc.id)),
    ...(phone ? { quickActions: true, onAck: ack, onResolve: resolve } : {}),
  }), [newIds, phone]) // eslint-disable-line react-hooks/exhaustive-deps
  const bulkMode = view === 'list' && !phone

  // Boş durum dürüst: kapsam / süzgeç söylenir; "her şey yolunda" yalnız kapsam süzgeci olmayan ve süzgeçsiz görünümde.
  const emptyState = () => {
    if (shownActive.length > 0) {
      return (
        <StatusBlock tone="neutral" icon={Siren} title={t('incov.emptyTitle')} description={t('incov.emptyFiltered')} className="py-14"
          actions={<Button type="button" variant="outline" className="pointer-coarse:h-10" onClick={resetFilters}><FilterX aria-hidden="true" />{t('incov.clearFilters')}</Button>} />
      )
    }
    if (scopeCounts && scope === SCOPE_MINE) {
      return <StatusBlock tone="neutral" icon={Siren} title={t('incov.emptyScopeMine')} description={t('incov.emptyScopeMineText')} className="py-14" />
    }
    if (scopeCounts && scope === SCOPE_OTHERS) {
      return <StatusBlock tone="neutral" icon={Siren} title={t('incov.emptyScopeOthers')} description={t('incov.emptyScopeOthersText')} className="py-14" />
    }
    return <StatusBlock tone="success" icon={CheckCircle2} title={t('incov.emptyAllClear')} description={t('incov.emptyAllClearText')} className="py-14" />
  }

  const firstNew = visibleRows.find((r) => newIds.has(String(r.id)))
  const headerActions = (
    <>
      <SavedViewsMenu listKey="incidents" tab="incidents" {...VIEW_SPECS.incidents} size="sm" className="h-8 pointer-coarse:h-10" />
      <Button asChild variant="outline" size="sm" className="h-8 pointer-coarse:h-10" title={t('incov.noiseHint')}>
        <a data-slot="incidents-noise-link" href={`?tab=alerthistory&nz_open=1${filters.team ? `&nz_team=${encodeURIComponent(filters.team)}` : ''}`}
          onClick={(e) => {
            if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button === 1) return
            e.preventDefault()
            navigateTo('alerthistory', { nz_open: '1', ...(filters.team ? { nz_team: filters.team } : {}) })
          }}>
          <Activity aria-hidden="true" />{t('incov.noise')}
        </a>
      </Button>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button type="button" variant="outline" size="sm" className="h-8 pointer-coarse:h-10" data-slot="incidents-export"
            disabled={exporting != null} aria-busy={exporting != null || undefined}>
            {exporting ? <Spinner size={14} label={t('incov.exportBusy')} /> : <Download aria-hidden="true" />}{t('incov.export')}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem data-slot="incidents-export-csv" className="pointer-coarse:min-h-10" onSelect={() => exportCsv()}>
            <FileText aria-hidden="true" />{t('incov.exportCsv')}
          </DropdownMenuItem>
          <DropdownMenuItem data-slot="incidents-export-xlsx" className="pointer-coarse:min-h-10" onSelect={() => exportXlsx()}>
            <FileSpreadsheet aria-hidden="true" />{t('incov.exportXlsx')}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      {!phone && (
        <Popover>
          <PopoverTrigger asChild>
            <Button type="button" variant="ghost" size="icon" className="size-8 pointer-coarse:size-10" aria-label={t('incov.kbd.title')} data-slot="incidents-kbd">
              <Keyboard aria-hidden="true" />
            </Button>
          </PopoverTrigger>
          <PopoverContent align="end" className="w-72">
            <p className="m-0 mb-2 text-sm font-semibold">{t('incov.kbd.title')}</p>
            <dl data-slot="incidents-kbd-list" className="m-0 grid grid-cols-[auto_1fr] items-center gap-x-3 gap-y-1.5 text-xs">
              <dt className="flex gap-1"><Kbd>J</Kbd><Kbd>K</Kbd></dt><dd className="m-0">{t('incov.kbd.move')}</dd>
              <dt><Kbd>↵</Kbd></dt><dd className="m-0">{t('incov.kbd.open')}</dd>
              <dt><Kbd>A</Kbd></dt><dd className="m-0">{t('incov.kbd.ack')}</dd>
              <dt><Kbd>R</Kbd></dt><dd className="m-0">{t('incov.kbd.resolve')}</dd>
              <dt><Kbd>C</Kbd></dt><dd className="m-0">{t('incov.kbd.comment')}</dd>
            </dl>
            <p className="m-0 mt-2 text-[11px] text-muted-foreground">{t('incov.kbd.hint')}</p>
          </PopoverContent>
        </Popover>
      )}
    </>
  )

  return (
    <div data-slot="incidents-page" className="flex min-w-0 flex-col gap-3">
      <IncidentsHeader summary={summary} view={view} onView={setView} onRefresh={refreshAll} loading={loading} actions={headerActions} />

      {bannerOpen && (
        <AlertBanner tone="info" icon={Info} title={t('incov.bannerTitle')} className="mb-0" onDismiss={dismissBanner} dismissLabel={t('incov.dismiss')}>
          {t('incov.bannerText')}
        </AlertBanner>
      )}

      {scopeCounts && (
        <IncidentScopeFilter value={scope} counts={scopeCounts} onChange={(v) => setScope(normalizeIncidentScope(v))} />
      )}

      {tiles.length > 0 && (
        <MonitorStatsBar items={tiles} activeFilter={filters.stat}
          onStatClick={(key) => patchFilters({ stat: filters.stat === key ? '' : key })} />
      )}

      <IncidentMetricsStrip summary={summary} onOpenIncident={openById} />

      <IncidentsToolbar filters={filters} patch={patchFilters} reset={resetFilters} typeCounts={typeCounts} total={total}
        teams={teams} phone={phone} labelsFor={labelsFor}
        busy={busy ? <Spinner size={14} label={t('tbl.loading')} /> : null} />

      {/* Yeni olay bildirimi — sessiz yenilemede gelen açık olaylar (ekran okuyucuya bir kez duyurulur) */}
      <div aria-live="polite" className="min-w-0">
        {newIds.size > 0 && firstNew && (
          <div data-slot="incidents-new" className="flex flex-wrap items-center gap-2">
            <Button type="button" size="sm" className="h-8 gap-1.5 rounded-full pointer-coarse:h-10" title={t('incov.newPillHint')}
              onClick={() => { setCursorId(String(firstNew.id)); focusIncident(firstNew.id) }}>
              <Sparkles aria-hidden="true" />{newIds.size === 1 ? t('incov.newPillOne') : t('incov.newPill', newIds.size)}
            </Button>
            <Button type="button" variant="ghost" size="icon" className="size-8 pointer-coarse:size-10" aria-label={t('incov.dismiss')}
              onClick={() => setNewIds(new Set())}>
              <X aria-hidden="true" />
            </Button>
          </div>
        )}
      </div>

      {error && (
        <AlertBanner tone="danger" title={t('incov.loadError')} className="mb-0" role="alert"
          actions={<Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => load()}><RotateCcw aria-hidden="true" />{t('incov.retry')}</Button>}>
          {error}
        </AlertBanner>
      )}

      {facetNote && (
        <p data-slot="facet-note" className="text-xs text-muted-foreground">{t('incov.pageFacetNote', visibleRows.length, rows.length)}</p>
      )}

      {bulkMode && selectedRows.length > 0 && (
        <Card data-slot="incidents-bulk-bar" className="flex-row flex-wrap items-center gap-2 px-3 py-2 shadow-none">
          <span className="text-sm font-semibold">{t('incov.bulk.selected', selectedRows.length)}</span>
          <Button type="button" variant="outline" size="sm" className="h-8 pointer-coarse:h-10" onClick={() => bulk('ack')}
            disabled={!selectedRows.some(ackable)}>
            <UserCheck aria-hidden="true" />{t('incov.bulk.ack')}
          </Button>
          <Button type="button" variant="success" size="sm" className="h-8 pointer-coarse:h-10" onClick={() => bulk('resolve')}>
            <CheckCircle2 aria-hidden="true" />{t('incov.bulk.resolve')}
          </Button>
          <Button type="button" variant="ghost" size="sm" className="ml-auto h-8 pointer-coarse:h-10" onClick={() => setSelectedIds(new Set())}>
            {t('incov.bulk.clear')}
          </Button>
          {ackSkipped.length > 0 && (
            <p data-slot="incidents-bulk-ack-skip" className="m-0 w-full text-xs text-muted-foreground">{t('incov.bulk.ackSkipNote', ackSkipped.length)}</p>
          )}
        </Card>
      )}

      {bulkResult && (
        <AlertBanner tone={bulkResult.failed ? 'warning' : 'info'} title={t('incov.bulk.resultTitle')} className="mb-0"
          onDismiss={() => setBulkResult(null)} dismissLabel={t('incov.dismiss')}>
          <p className="m-0">{t('incov.bulk.summary', bulkResult.processed, bulkResult.skipped, bulkResult.failed)}</p>
          <ul data-slot="incidents-bulk-result" className="m-0 mt-1.5 flex list-none flex-col gap-1 p-0">
            {bulkResult.items.map((it) => (
              <li key={it.id} data-outcome={it.outcome} className="flex min-w-0 flex-wrap items-center gap-x-2 text-xs">
                <span className="min-w-0 font-semibold [overflow-wrap:anywhere]">{it.name}</span>
                <Badge variant="outline" className="h-auto overflow-visible rounded-md whitespace-normal">
                  {t(`incov.bulk.outcome.${BULK_OUTCOMES.includes(it.outcome) ? it.outcome : 'unknown'}`)}
                </Badge>
              </li>
            ))}
          </ul>
        </AlertBanner>
      )}

      {initial ? (
        view === 'board' ? <BoardSkeleton /> : <TableSkeleton phone={phone} />
      ) : (
        <div ref={resultsRef} data-slot="incidents-results" data-stale={!settled || undefined} aria-busy={loading || undefined}
          onFocusCapture={onResultsFocus}
          className={cn('flex min-w-0 flex-col gap-3 transition-opacity motion-reduce:transition-none', busy && 'opacity-60')}>
          {visibleRows.length === 0 ? (
            !error && emptyState()
          ) : (
            <>
              {view === 'board'
                ? <IncidentBoard rows={visibleRows} onOpen={openDetail} selectedId={selectedId} phone={phone} paged={paged} cardProps={cardProps} />
                : (
                  <IncidentsTable rows={visibleRows} sort={sort} onSort={toggleSort} onOpen={openDetail} selectedId={selectedId}
                    phone={phone} isAdmin={isAdmin} onAck={ack} onResolve={resolve} onDelete={del}
                    newIds={newIds} selection={bulkMode ? selection : null} />
                )}
              {/* Standart sayfalama çubuğu — pano da yüklenen sayfayı dağıtır */}
              <PaginationBar {...sp.bar} />
            </>
          )}
        </div>
      )}

      {selected && (
        <IncidentDetailSheet key={selected.id} incident={selected} onClose={closeDetail} isAdmin={isAdmin}
          onAck={ack} onResolve={resolve} onDelete={del} onCommentDelta={onCommentDelta} focusComposer={focusComposer}
          onNotify={notify} notifying={notifyingId === String(selected.id)} />
      )}

      {noteDialog && (
        <ActionNoteDialog key={`${noteDialog.action}:${noteDialog.items.map((i) => i.id).join(',')}`} action={noteDialog.action} subject="incident"
          items={noteDialog.items} onSubmit={noteDialog.submit} onClose={() => setNoteDialog(null)} />
      )}

      {renotify && (
        <ReNotifyConfirmModal domain={renotify.domain} recipients={renotify.recipients} webhook={renotify.webhook}
          emailBlockReason={renotify.emailBlockReason}
          sending={renotifySending} onSend={sendReNotify} onClose={() => { if (!renotifySending) setRenotify(null) }} />
      )}
    </div>
  )
}
