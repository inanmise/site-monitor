import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import {
  ListChecks, BarChart3, Sigma, AlertOctagon, AlertTriangle, AlertCircle, ArrowDownCircle, CircleDot, Search as SearchIcon,
  Shield, CheckCircle2, ShieldCheck, CalendarDays, CalendarRange, Calendar, Lock, RotateCcw, FilterX, Plus, ArrowRightLeft, X,
  ClipboardList,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import { useUrlQuerySync, readUrlParam } from '../../hooks/useUrlQuerySync.js'
import { useDelayedFlag } from '../../hooks/useDelayedFlag.js'
import { useToast } from '../ui/Toast.jsx'
import { serverFieldErrors } from '../../utils/formErrors.js'
import { useDialog } from '../ui/Dialog.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import CollapsibleSection from '../ui/CollapsibleSection.jsx'
import { Spinner } from '../ui/Progress.jsx'
import MonitorStatsBar from '../MonitorStatsBar.jsx'
import { Button } from '@/components/shadcn/button'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'
import HistoryHeader from './HistoryHeader.jsx'
import HistoryToolbar from './HistoryToolbar.jsx'
import HistoryList, { HistorySkeleton } from './HistoryList.jsx'
import HistoryTrendChart from './HistoryTrendChart.jsx'
import HistoryDetailSheet from './HistoryDetailSheet.jsx'
import IncidentFormModal, { FRESH_TIMEOUT_MS } from './IncidentFormModal.jsx'
import { errorInfoOf } from '../../utils/errorMessages.js'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import PostmortemModal from './postmortem/PostmortemModal.jsx'
import ActionItemsView from './postmortem/ActionItemsView.jsx'
import {
  EMPTY_FORM, DETAIL_KEY, LEGACY_DETAIL_KEY, FILTER_DEFAULTS, filtersFromUrl, filtersToUrl, initialDetailId, serverParams,
  localDayToUtcIso, activeFilters, patchFilters, isCardActive, applyCardFilter, toggleDayFilter, dailySeries, trendWindow,
  changedKeys, olderRecord, MARKDOWN_KEYS,
} from './incidentHistoryModel.js'
import {
  quickInitial, nowMinuteIso, duplicateForm, mergeFull, keepMarkdown, markdownOmitted, pageKeyEligible,
} from './quickEntryModel.js'
import { letterOf, SEQUENCE_MS } from '../../utils/keyboardShortcuts.js'
import { registerShortcutContext, INCIDENT_HISTORY_CONTEXT } from '../../utils/shortcutContexts.js'

/** Arama kutusu bu kadar duraklamadan sonra uygulanır (her tuşta istek yok); Enter hemen uygular. */
export const SEARCH_DEBOUNCE_MS = 300
/** Bu genişliğin altında (telefon + tablet) liste kart, süzgeçler alttan Sheet — 768 px'te kenar çubuğu açıkken tabloya
 *  ~460 px kalıyor, sütunlar sıkışıp yatay kayıyordu (2026-09-28 ölçümü). */
const COMPACT_BREAKPOINT = 1024

/** Seçenek tipleri (yönetilen açılır listeler). Kanal süzgeç çubuğunda da kullanıldığı için açılışta okunur; diğer dördü
 *  yalnız formda — ilk form açılışında okunur ("Hızlı Giriş", 2026-11-20: sayfa açılışında 5 yerine 1 istek). */
const OPTION_TYPES = Object.freeze(['CHANNEL', 'DOMAIN', 'ERROR_CODE', 'FUNCTION_CODE', 'CHANNEL_CODE'])
const FORM_OPTION_TYPES = Object.freeze(['DOMAIN', 'ERROR_CODE', 'FUNCTION_CODE', 'CHANNEL_CODE'])

/** Görünüm alanı `COMPACT_BREAKPOINT`'in altında mı (yapı farkı — useIsMobile deseni: innerWidth + matchMedia değişimi). */
function useCompactLayout() {
  const [compact, setCompact] = useState(false)
  useEffect(() => {
    const mql = window.matchMedia(`(max-width: ${COMPACT_BREAKPOINT - 1}px)`)
    const onChange = () => setCompact(window.innerWidth < COMPACT_BREAKPOINT)
    mql.addEventListener('change', onChange)
    onChange()
    return () => mql.removeEventListener('change', onChange)
  }, [])
  return compact
}

/**
 * SRE Olay & Hata Geçmişi — manuel kayıt defteri (Raporlar menüsü). Parçalar `components/incidenthistory/*`; bu dosya
 * veri + durum + eylemleri tutar. Yetki: incidents.view (görüntüleme), incidents.manage (yaz; toplu takım aktarımı),
 * incidents.delete (sil — TEAM_ADMIN/ADMIN). Sunucu her uçta kendi kapısını ve takım kapsamını ayrıca uygular.
 *
 * <p><b>Titremesiz geçiş (IncidentsPage deseni).</b> İskelet YALNIZ ilk yüklemede; sonraki her süzgeç/sayfa değişiminde
 * ekrandaki sonuç yerinde kalır, ~180 ms'yi aşan yüklemede soluklaşır + araç çubuğunda küçük Spinner; "sonuç yok" ancak
 * YENİ yanıt sıfır derse çizilir. Yalnız EN SON isteğin yanıtı uygulanır (loadSeq); özet ve günlük trend kendi sıra
 * sayaçlarıyla korunur (trendsSeq / trendDailySeq — `IncidentHistoryPage.trendRace.test.jsx`).
 *
 * <p><b>URL.</b> Süzgeçler `ih_*` (PAGE_STATE_PREFIXES), sayfa `page`/`ps`, açık ayrıntı `ih_id`; e-posta derin
 * bağlantısı `incident=<id>` açılışta okunur ve adresten silinir.
 *
 * <p>DİKKAT (hook sırası): yetkiler asenkron yüklenir, `allowView` false→true döner — TÜM hook'lar aşağıdaki
 * `allowView` erken-return'ünün ÜSTÜNDE kalmalı.
 */
export default function IncidentHistoryPage() {
  const t = useT()
  const toast = useToast()
  // Memo'suz context değeri (useToast her render'da yeni nesne) yükleyicilerin bağımlılığı OLMAZ — ref'ten okunur.
  const toastRef = useRef(toast)
  toastRef.current = toast
  const tRef = useRef(t)
  tRef.current = t
  const { showConfirm } = useDialog()
  const { canView, canEdit, canExecute } = usePermissions()
  const allowView = canView('incidents.view')
  const allowManage = canEdit('incidents.manage')
  const allowDelete = canExecute('incidents.delete')
  const phone = useCompactLayout()   // telefon + tablet: kart listesi + süzgeç çekmecesi

  // ── Süzgeçler (URL'den başlar) + gecikmeli arama ──
  const [filters, setFilters] = useState(() => filtersFromUrl(readUrlParam))
  const [qTerm, setQTerm] = useState(() => filters.q.trim())
  useEffect(() => {
    const id = setTimeout(() => setQTerm(filters.q.trim()), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(id)
  }, [filters.q])
  // Etkin süzgeç: yazılan değil YERLEŞEN arama terimi. TÜM boyutlar bağımlılıkta (eskiden slaBreached/open eksikti:
  // "SLA İhlali" / "Açık" kartı tek başına tıklanınca liste hiç süzülmüyordu).
  const effFilters = useMemo(() => ({ ...filters, q: qTerm }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [filters.severity, filters.category, filters.status, filters.channel, filters.team_id, filters.since, filters.until,
      filters.slaBreached, filters.open, filters._preset, qTerm])
  const params = useMemo(() => serverParams(effFilters), [effFilters])

  // Sayfalama standardı: süzgeç DEĞERİ değişince sayfa 1 (mount'ta değil — `?page=3` derin bağlantısı korunur).
  const sp = useServerPagination({ listKey: 'incident-history', preset: 'page', resetDeps: [params],
    url: { pageKey: 'page', sizeKey: 'ps' }, apiBase: 0 })
  const { apiPage, pageSize: size } = sp
  const requestKey = JSON.stringify([params, apiPage, size])

  // ── Liste durumu (stale-while-revalidate) ──
  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [loadedKey, setLoadedKey] = useState(null)     // son YERLEŞEN (başarılı ya da hatalı) isteğin anahtarı
  const [everLoaded, setEverLoaded] = useState(false)  // en az bir başarılı yanıt geldi mi (hata ekranı seçimi)
  const loadSeq = useRef(0)

  // ── Özet, trend, seçenekler, takımlar ──
  const [trends, setTrends] = useState(null)
  const trendsSeq = useRef(0)
  // Özet okunamadı (UIA-G8, 2026-10-22): eskiden hata yutuluyor, başlık KPI'ları sonsuza dek iskelette kalıyor, döküm
  // kartları "0" gösteriyor ve tarih aralığı değişip okuma düşünce ESKİ dönemin sayıları yeni dönemin etiketiyle
  // kalıyordu. Artık "—" + "özet okunamadı"; başka aralığın verisi temizlenir (aynı aralığın tazelemesi düşerse eski kalır).
  const [trendsFailed, setTrendsFailed] = useState(false)
  const trendsKeyRef = useRef(null)
  const [trendDays, setTrendDays] = useState(30)
  const [trendDaily, setTrendDaily] = useState([])
  const trendDailySeq = useRef(0)
  const [showSummary, setShowSummary] = useState(false)
  const [channelOpts, setChannelOpts] = useState([])
  const [domainOpts, setDomainOpts] = useState([])
  const [errorCodeOpts, setErrorCodeOpts] = useState([])
  const [functionCodeOpts, setFunctionCodeOpts] = useState([])
  const [channelCodeOpts, setChannelCodeOpts] = useState([])
  const [teams, setTeams] = useState([])
  // Takım listesi okundu mu (okunmadıysa / okunamadıysa çoğaltma kaynak takımı korur — bilinmeyen ret değildir)
  const [teamsKnown, setTeamsKnown] = useState(false)

  // ── Seçim, ayrıntı, form ──
  const [selected, setSelected] = useState(() => new Set())
  const [transferTeam, setTransferTeam] = useState('')
  const [transferring, setTransferring] = useState(false)
  const [detailId, setDetailId] = useState(() => initialDetailId(readUrlParam))
  const [fallback, setFallback] = useState(null)       // sayfada olmayan (derin bağlantı / az önce kaydedilen) kayıt
  const [form, setForm] = useState(null)               // { mode: 'create'|'edit', initial }
  // Postmortem + aksiyon takibi (2026-10-11): görünüm (olaylar | olaylar arası aksiyonlar) ve açık postmortem penceresi
  const [view, setView] = useState(() => (readUrlParam('ih_view') === 'actions' ? 'actions' : 'incidents'))
  const [pmFor, setPmFor] = useState(null)

  useUrlQuerySync({ ...filtersToUrl({ ...filters, q: qTerm }), [DETAIL_KEY]: detailId, ih_view: view === 'actions' ? 'actions' : null })

  const load = useCallback(async () => {
    if (!allowView) return
    const seq = ++loadSeq.current
    const key = requestKey
    setLoading(true)
    try {
      // fields=summary (2026-11-20, "Hızlı Giriş"): liste satırları dört uzun markdown metni OLMADAN gelir
      // (`markdown_omitted`); ayrıntı / düzenleme tam kaydı kendisi okur. Eski sunucu parametreyi yok sayar → tam satır.
      const res = await api.incidents.list({ ...params, page: apiPage, size, fields: 'summary' })
      if (seq !== loadSeq.current) return   // bayat yanıt — daha yeni bir istek yolda
      if (res?.success) {
        // Aynı sürümdeki tam satırın markdown'ı korunur (kaydettikten sonraki yenileme ayrıntıyı "yükleniyor"a düşürmez)
        setRows((prev) => keepMarkdown(prev, Array.isArray(res.data) ? res.data : [])); sp.bind(res); setError(null); setEverLoaded(true)
      } else {
        setError(res?.error || tRef.current('inc.loadError'))
      }
      setLoadedKey(key)
    } catch {
      // Başarısız istek de YERLEŞİR (eski satırlar yerinde kalır, hata şeridi + Yeniden dene).
      if (seq === loadSeq.current) { setError(tRef.current('inc.loadError')); setLoadedKey(key) }
    } finally {
      if (seq === loadSeq.current) setLoading(false)
    }
  }, [requestKey, allowView]) // eslint-disable-line react-hooks/exhaustive-deps

  // Özet (KPI + döküm kartları) — tablo süzgecinin TARİH aralığına bağlı; yalnız en son isteğin yanıtı uygulanır.
  const loadTrends = useCallback(async () => {
    if (!allowView) return
    const my = ++trendsSeq.current
    const key = `${filters.since || ''}|${filters.until || ''}`
    const fail = () => {
      if (trendsKeyRef.current !== key) { setTrends(null); trendsKeyRef.current = null }
      setTrendsFailed(true)
    }
    try {
      const res = await api.incidents.trends(localDayToUtcIso(filters.since, false), localDayToUtcIso(filters.until, true))
      if (my !== trendsSeq.current) return
      if (res?.success) { setTrends(res.data); trendsKeyRef.current = key; setTrendsFailed(false) } else fail()
    } catch {
      // liste yine çizilir; özet "okunamadı" der
      if (my === trendsSeq.current) fail()
    }
  }, [filters.since, filters.until, allowView])

  // Günlük trend: son trendDays gün, tablo süzgecinden BAĞIMSIZ (bugünle biten kayan pencere).
  const loadTrendDaily = useCallback(async () => {
    if (!allowView) return
    const w = trendWindow(trendDays)
    const my = ++trendDailySeq.current
    try {
      const res = await api.incidents.trends(localDayToUtcIso(w.since, false), localDayToUtcIso(w.until, true))
      if (my !== trendDailySeq.current) return   // bayat yanıt — daha yeni bir pencere istendi
      if (res?.success) setTrendDaily(res.data?.daily ?? [])
    } catch { /* sessiz — grafik boş kalır */ }
  }, [trendDays, allowView])

  /** Seçenek listelerini okur (verilen tipler; varsayılan hepsi). En az biri okunduysa true. */
  const loadOptions = useCallback(async (types = OPTION_TYPES) => {
    if (!allowView) return false
    const setters = {
      CHANNEL: setChannelOpts, DOMAIN: setDomainOpts, ERROR_CODE: setErrorCodeOpts,
      FUNCTION_CODE: setFunctionCodeOpts, CHANNEL_CODE: setChannelCodeOpts,
    }
    let any = false
    try {
      const all = await Promise.all(types.map((ty) => Promise.resolve().then(() => api.incidents.options(ty)).catch(() => null)))
      all.forEach((res, i) => { if (res?.success) { setters[types[i]](Array.isArray(res.data) ? res.data : []); any = true } })
    } catch { /* sessiz — seçim listeleri boş kalır, yeni değer yine eklenebilir */ }
    return any
  }, [allowView])
  // Formun dört listesi ilk form açılışında bir kez okunur; hiçbiri okunamadıysa sonraki açılış yeniden dener.
  const formOptsState = useRef('idle')   // idle | loading | done

  const loadTeams = useCallback(async () => {
    if (!allowView) return
    try {
      const res = await api.admin.getTeams()
      if (res?.success) { setTeams(res.data ?? []); setTeamsKnown(true) }
    } catch { /* sessiz — yetkisizse liste boş kalır ("tüm takımlar" gibi davranır) */ }
  }, [allowView])

  useEffect(() => { load() }, [load])
  useEffect(() => { loadTrends() }, [loadTrends])
  // Günlük trend yalnız "Ayrıntılı döküm" açıkken okunur (sayfa açılışında tek trends isteği — "Hızlı Giriş", 2026-11-20)
  useEffect(() => { if (showSummary) loadTrendDaily() }, [loadTrendDaily, showSummary])
  useEffect(() => { loadOptions(['CHANNEL']) }, [loadOptions])
  useEffect(() => { loadTeams() }, [loadTeams])
  useEffect(() => {
    if (!form || formOptsState.current !== 'idle') return
    formOptsState.current = 'loading'
    loadOptions(FORM_OPTION_TYPES).then((ok) => { formOptsState.current = ok ? 'done' : 'idle' })
  }, [form, loadOptions])
  // Sayfa/süzgeç değişince toplu seçim sıfırlanır (görünmeyen satır seçili kalmasın).
  useEffect(() => { setSelected(new Set()) }, [requestKey])

  const refreshAll = useCallback(() => {
    load(); loadTrends()
    if (showSummary) loadTrendDaily()
  }, [load, loadTrends, loadTrendDaily, showSummary])
  const loadRef = useRef(load)
  loadRef.current = load

  // ── Ayrıntının taze hâli (2026-11-20, "Sağlam Kayıt") ──
  // Ayrıntı liste satırıyla HEMEN açılır; kaydın son hâli arkada okunur (sınırlı süre, iptal edilebilir, yalnız EN SON
  // açılışın yanıtı uygulanır). Farklıysa satır + yedek güncellenir ve Sheet "liste açıldıktan sonra değişti" der; okunamadıysa
  // satır kalır (sessiz not); 404 → "silinmiş" (düzenleme kapalı, liste yenilenir).
  const [detailFresh, setDetailFresh] = useState(null)   // { id, state: loading|same|updated|failed|gone, changed }
  const detailSeq = useRef(0)
  const detailAbort = useRef(null)
  useEffect(() => () => { detailAbort.current?.abort() }, [])

  /**
   * Sunucudan gelen kaydı listeye ve ayrıntı yedeğine işler (liste satırının yalnız listede olan alanları korunur). Elde
   * tutulandan DAHA ESKİ sürümlü kayıt (kayıttan önce başlayıp sonra dönen okuma) yazılmaz — kaydedilen hâli geri almaz.
   */
  const mergeRecord = useCallback((rec) => {
    if (!rec || typeof rec !== 'object' || Array.isArray(rec) || rec.id == null) return
    const merge = (held) => (olderRecord(held, rec) ? held : mergeFull(held, rec))
    setRows((rs) => rs.map((r) => (String(r.id) === String(rec.id) ? merge(r) : r)))
    setFallback((f) => (f && String(f.id) === String(rec.id) ? merge(f) : f))
  }, [])

  // Özet satırın markdown'ı için taze okuma (kimlik:sürüm) başına bir kez — bkz. aşağıdaki etki
  const mdFetchedFor = useRef(null)
  const refreshDetail = useCallback((r) => {
    if (!r || r.id == null) return
    mdFetchedFor.current = `${r.id}:${r.version ?? ''}`
    const my = ++detailSeq.current
    detailAbort.current?.abort()
    const ctl = typeof AbortController === 'function' ? new AbortController() : null
    detailAbort.current = ctl
    const id = r.id
    setDetailFresh({ id, state: 'loading', changed: [] })
    Promise.resolve()
      .then(() => api.incidents.get(id, { timeoutMs: FRESH_TIMEOUT_MS, signal: ctl?.signal }))
      .then((res) => {
        if (my !== detailSeq.current) return
        const d = res?.success ? res.data : null
        if (d && typeof d === 'object' && !Array.isArray(d) && String(d.id) === String(id)) {
          // Özet satırda markdown hiç gelmemişti — tam kayıtta "var" olması değişiklik değildir
          const changed = olderRecord(r, d) ? [] : changedKeys(r, d).filter((k) => !(markdownOmitted(r) && MARKDOWN_KEYS.includes(k)))
          mergeRecord(d)
          setDetailFresh({ id, state: changed.length ? 'updated' : 'same', changed })
        } else if (errorInfoOf(res)?.status === 404) {
          setDetailFresh({ id, state: 'gone', changed: [] })
          loadRef.current?.()
        } else if (!ctl?.signal?.aborted) {
          setDetailFresh({ id, state: 'failed', changed: [] })
        }
      })
      .catch(() => { if (my === detailSeq.current) setDetailFresh({ id, state: 'failed', changed: [] }) })
  }, [mergeRecord])

  // Derin bağlantı: açılışta `ih_id` / e-postanın `incident`'ı. Kayıt listede olmayabilir → tekil uçtan çekilir.
  // `incident` tüketilir (uygulama anahtarı; sayfa YAZMAZ), açık ayrıntı `ih_id` olarak adreste kalır.
  useEffect(() => {
    if (!allowView) return undefined
    const id = initialDetailId(readUrlParam)
    try {
      const url = new URL(window.location.href)
      if (url.searchParams.has(LEGACY_DETAIL_KEY)) {
        url.searchParams.delete(LEGACY_DETAIL_KEY)
        const qs = url.searchParams.toString()
        window.history.replaceState(window.history.state, '', url.pathname + (qs ? `?${qs}` : '') + url.hash)
      }
    } catch { /* yoksay */ }
    if (!id) return undefined
    let alive = true
    setDetailId(id)
    api.incidents.get(id).then((res) => {
      if (!alive) return
      if (res?.success && res.data) setFallback(res.data)
      else { setDetailId(null); toastRef.current.error(res?.error || tRef.current('inc.deepLinkFail')) }
    }).catch(() => { if (alive) { setDetailId(null); toastRef.current.error(tRef.current('inc.deepLinkFail')) } })
    return () => { alive = false }
  }, [allowView])

  // Sayfa kısayolları (2026-11-20, "Hızlı Giriş"): N → yeni olay (tam form), E → açık ayrıntıyı düzenle. Yazı alanında,
  // açık pencerede / menüde (ayrıntı Sheet'i hariç), değiştiriciyle ve genel "g + harf" dizisinin ikinci tuşunda çalışmaz.
  // Eylemler render'da ref'e yazılır (erken dönüşün altındaki işlevler).
  const keyActionsRef = useRef(null)
  const keyState = useRef({ gAt: 0 })
  const dupBusy = useRef(false)   // çoğaltma için tam kayıt okunuyor (çift tıklama tek istek)
  useEffect(() => (allowView ? registerShortcutContext(INCIDENT_HISTORY_CONTEXT) : undefined), [allowView])
  useEffect(() => {
    if (!allowView) return undefined
    const onKey = (e) => {
      const now = Date.now()
      const st = keyState.current
      const afterG = st.gAt > 0 && now - st.gAt <= SEQUENCE_MS
      if (!pageKeyEligible(e)) return
      const letter = letterOf(e)
      if (letter === 'g') { st.gAt = now; return }
      st.gAt = 0
      if (afterG || e.shiftKey) return
      const a = keyActionsRef.current
      if (!a) return
      if (letter === 'n' && a.canCreate) { e.preventDefault(); a.create(); return }
      if (letter === 'e' && a.canEditDetail) { e.preventDefault(); a.editDetail() }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [allowView])

  const dailyChart = useMemo(() => dailySeries(trendDaily, trendDays), [trendDaily, trendDays])
  const settled = loadedKey === requestKey
  // Gösterge yalnız GÖRÜNÜMÜ bayat bir yükleme ~180 ms'yi aşarsa (hızlı yanıtta ekran yanıp sönmez).
  const busy = useDelayedFlag(loading && !settled, 180)
  const detail = useMemo(() => {
    if (detailId == null) return null
    return rows.find((r) => String(r.id) === String(detailId))
      || (fallback && String(fallback.id) === String(detailId) ? fallback : null)
  }, [rows, fallback, detailId])
  // Açık ayrıntı özet satıra döndüyse (liste yenilendi, kayıt başkasınca değişti → markdown korunamadı) tam kayıt yeniden
  // okunur; aynı (kimlik, sürüm) için bir kez (başarısızsa ayrıntı "okunamadı + yeniden dene" gösterir).
  useEffect(() => {
    if (!detail || !markdownOmitted(detail)) return
    if (mdFetchedFor.current === `${detail.id}:${detail.version ?? ''}`) return
    refreshDetail(detail)
  }, [detail, refreshDetail])

  if (!allowView) return <StatusBlock tone="neutral" icon={ListChecks} title={t('inc.noAccess')} />

  // ── Süzgeç eylemleri ──
  const patch = (p) => {
    setFilters((f) => patchFilters(f, p))
    if ('q' in p) setQTerm(String(p.q ?? '').trim())   // çip × / Temizle aramayı HEMEN uygular
  }
  const reset = () => { setFilters({ ...FILTER_DEFAULTS }); setQTerm('') }
  const onCard = (kind) => {
    setFilters((f) => applyCardFilter(f, kind))
    setQTerm('')
  }
  const isActive = (kind) => isCardActive(kind, effFilters)
  const toggleDay = (day) => setFilters((f) => toggleDayFilter(f, day))

  // ── Kayıt eylemleri ──
  // Açılan kayıt yedeğe de yazılır: liste yeniden yüklenip kayıt süzgeçten düşse bile ayrıntı açık kalır.
  // Ayrıntı satırla hemen açılır, kaydın son hâli arkada okunur (refreshDetail).
  const openDetail = (r) => { setFallback(r); setDetailId(String(r.id)); refreshDetail(r) }
  /** Aksiyonlar görünümünden olay aç: kayıt sayfada olmayabilir → sunucudan alınır (derin bağlantı deseni). */
  const openIncidentById = (id) => {
    setDetailId(String(id))
    api.incidents.get(id).then((res) => {
      if (res?.success && res.data) setFallback(res.data)
      else { setDetailId(null); toast.error(res?.error || t('inc.deepLinkFail')) }
    }).catch(() => { setDetailId(null); toast.error(t('inc.deepLinkFail')) })
  }
  const closeDetail = () => {
    detailSeq.current++                 // yoldaki taze okuma artık uygulanmaz
    detailAbort.current?.abort()
    setDetailFresh(null)
    setDetailId(null)
  }
  const openCreate = () => setForm({ mode: 'create', initial: { ...EMPTY_FORM } })
  const openEdit = (r) => setForm({ mode: 'edit', initial: { ...EMPTY_FORM, ...r } })
  /** Hızlı kayıt (2026-11-20): AYNI form sade görünümde — oluş zamanı şimdi, tek takım varsa o takım. */
  const openQuick = () => setForm({ mode: 'create', quick: true, initial: quickInitial(teams, nowMinuteIso()) })
  /**
   * Çoğalt (2026-11-20): kaydın sınıflandırma / kod / açıklama alanları yeni kayıt formuna kopyalanır (zamanlar, durum,
   * kök neden, çözüm, sayılar, görseller kopyalanmaz). Özet satırın açıklaması yoksa önce tam kayıt okunur; okunamazsa form
   * açılmaz (eksik açıklamayla kopya yaratılmaz). Bu arada başka bir form açıldıysa üzerine yazılmaz.
   */
  const openDuplicate = async (r) => {
    if (!r || r.id == null || dupBusy.current) return
    let rec = r
    if (markdownOmitted(r)) {
      dupBusy.current = true
      let res = null
      try { res = await api.incidents.get(r.id, { timeoutMs: FRESH_TIMEOUT_MS }) } catch { res = null }
      dupBusy.current = false
      const d = res?.success ? res.data : null
      if (!d || typeof d !== 'object' || String(d.id) !== String(r.id)) { toast.error(res?.error || t('inc.copy.loadFailed')); return }
      rec = d
      mergeRecord(d)
    }
    const { form: init, imagesRemoved, teamDropped } = duplicateForm(rec, teams, { known: teamsKnown })
    setForm((f) => f ?? { mode: 'create', initial: init, copyOf: { id: rec.id, imagesRemoved, teamDropped } })
  }
  /** Ayrıntı ile listenin ortak "kaydedildi" adımı: açık ayrıntının yoldaki taze okuması iptal edilir, kayıt işlenir. */
  const applySaved = (rec) => {
    if (!rec || typeof rec !== 'object' || Array.isArray(rec) || rec.id == null) return
    // Ayrıntı aynı kayıt için açıksa kayıttan ÖNCE başlamış taze okuma iptal edilir: geç dönerse kaydedilen hâli eski
    // hâliyle ezmesin.
    if (detailId != null && String(rec.id) === String(detailId)) {
      detailSeq.current++
      detailAbort.current?.abort()
      setFallback((f) => (f && String(f.id) === String(rec.id) ? f : rec))   // birleştirme: mergeRecord
      setDetailFresh((f) => (f && String(f.id) === String(rec.id) ? { ...f, state: 'same', changed: [] } : f))
    }
    mergeRecord(rec)
  }
  /** Tek tıkla durum güncellemesi kaydedildi (liste satırı / ayrıntı). */
  const onStatusSaved = (rec) => {
    applySaved(rec)
    refreshAll()
  }
  /** Durum güncellemesi çakıştı / kayıt bulunamadı → "son hâli yükle": liste + açık ayrıntı yeniden okunur. */
  const onStatusReload = (r) => {
    load()
    if (detail && r && String(detail.id) === String(r.id)) refreshDetail(detail)
  }
  keyActionsRef.current = {
    canCreate: allowManage,
    create: openCreate,
    canEditDetail: !!detail && allowManage && detail.can_edit !== false
      && !(detailFresh && String(detailFresh.id) === String(detail.id) && detailFresh.state === 'gone'),
    editDetail: () => { if (detail) openEdit(detail) },
  }

  async function submitIncident(payload) {
    const mode = form?.mode
    try {
      const res = mode === 'create'
        ? await api.incidents.create(payload)
        : await api.incidents.update(payload.id, payload)
      // fields: sunucunun alan hataları (400 VALIDATION_FAILED) — form iletiyi ilgili alanın altında gösterir.
      // code / status / conflict (2026-11-20, "Sağlam Kayıt"): 409 CONCURRENT_MODIFICATION → form çakışma bandını (kim /
      // ne zaman) gösterir; 404 → kayıt silinmiş.
      if (!res?.success) {
        const info = errorInfoOf(res)
        return {
          ok: false, error: res?.error || t('inc.saveError'), fields: serverFieldErrors(res),
          code: info?.code ?? null, status: info?.status ?? null,
          conflict: info?.code === 'CONCURRENT_MODIFICATION'
            ? { by: res?.updated_by ?? null, at: res?.updated_at ?? null, version: res?.current_version ?? null }
            : null,
        }
      }
      toast.success(t('inc.saved'))
      // Kaydedilen kayıt listeye HEMEN işlenir (liste yeniden yüklenene kadar beklemez). Ayrıntı aynı kayıt için açıksa
      // kayıttan ÖNCE başlamış taze okuma iptal edilir: geç dönerse kaydedilen hâli eski hâliyle ezmesin.
      if (res.data && typeof res.data === 'object' && res.data.id != null) applySaved(res.data)
      setForm(null)
      refreshAll()
      return { ok: true, data: res.data }
    } catch {
      return { ok: false, error: t('inc.saveError') }
    }
  }

  async function removeIncident(rec) {
    const ok = await showConfirm({
      title: t('inc.deleteTitle'), message: t('inc.deleteConfirm', rec.title),
      variant: 'danger', confirmText: t('inc.delete'), cancelText: t('inc.cancel'),
    })
    if (!ok) return
    let res = null
    try { res = await api.incidents.remove(rec.id) } catch { res = null }
    if (!res?.success) { toast.error(res?.error || t('inc.saveError')); return }
    toast.success(t('inc.deleted'))
    if (String(detailId) === String(rec.id)) closeDetail()
    refreshAll()
  }

  const addOption = async (type, value) => {
    let res = null
    try { res = await api.incidents.addOption(type, value) } catch { res = null }
    if (res?.success) await loadOptions()
    else toast.error(res?.error || t('inc.saveError'))
  }
  const deleteOption = async (type, value) => {
    const ok = await showConfirm({
      title: t('inc.optDeleteTitle'), message: t('inc.optDeleteConfirm', value),
      variant: 'danger', confirmText: t('inc.delete'), cancelText: t('inc.cancel'),
    })
    if (!ok) return
    let res = null
    try { res = await api.incidents.deleteOption(type, value) } catch { res = null }
    if (res?.success) await loadOptions()
    else toast.error(res?.error || t('inc.saveError'))
  }

  const toggleSel = (id) => setSelected((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })
  const toggleAll = () => setSelected((s) => {
    const writable = rows.filter((r) => r?.can_edit !== false)   // yazılamayan satır seçilmez (2026-10-12)
    const all = writable.length > 0 && writable.every((r) => s.has(r.id))
    const n = new Set(s)
    writable.forEach((r) => (all ? n.delete(r.id) : n.add(r.id)))
    return n
  })

  async function doTransfer() {
    const tm = teams.find((x) => String(x.id) === String(transferTeam))
    if (!tm || selected.size === 0 || transferring) return
    const ok = await showConfirm({
      title: t('inc.transferTitle'), message: t('inc.transferConfirm', selected.size, tm.name),
      confirmText: t('inc.transferBtn'), cancelText: t('inc.cancel'),
    })
    if (!ok) return
    setTransferring(true)
    try {
      const res = await api.incidents.transfer([...selected], tm.id, tm.name)
      if (res?.success) {
        toast.success(t('inc.transferred', res.data?.transferred ?? selected.size))
        setSelected(new Set()); setTransferTeam(''); refreshAll()
      } else toast.error(res?.error || t('inc.saveError'))
    } catch {
      toast.error(t('inc.saveError'))
    } finally {
      setTransferring(false)
    }
  }

  // ── Döküm kartları (MonitorStatsBar) — tıklanınca süzgeç; etkin kart tekrar tıklanınca kalkar ──
  const sum = trends?.summary || null
  const bySev = trends?.by_severity || {}
  const byStatus = trends?.by_status || {}
  const s = sum || {}
  const statItems = [
    ['sumTotal', s.total, 'total', 'total', Sigma],
    ['sumCritical', s.critical, 'critical', 'critical', AlertOctagon],
    ['sumHigh', bySev.HIGH, 'high', 'high', AlertTriangle],
    ['sumMedium', bySev.MEDIUM, 'warning', 'medium', AlertCircle],
    ['sumLow', bySev.LOW, 'valid', 'low', ArrowDownCircle],
    ['sumOpen', s.open, 'alert', 'open', CircleDot],
    ['sumInvestigating', byStatus.INVESTIGATING, 'total', 'investigating', SearchIcon],
    ['sumMitigated', byStatus.MITIGATED, 'total', 'mitigated', Shield],
    ['sumResolved', s.resolved, 'valid', 'resolved', CheckCircle2],
    ['sumSla', s.sla_breached, 'expired', 'sla', Lock],
    ['sumResolvedSla', s.resolved_within_sla, 'valid', 'resolved_sla', ShieldCheck],
    ['sumToday', s.today, 'total', 'today', Calendar],
    ['sumLast7d', s.last_7d, 'total', 'last7d', CalendarRange],
    ['sumLast30d', s.last_30d, 'total', 'last30d', CalendarDays],
  ].map(([k, v, cls, kind, Icon]) => ({ key: kind, label: t('inc.' + k), value: sum ? (v ?? 0) : (trendsFailed ? '—' : v ?? 0), cls, Icon, hint: t('inc.filterByCard') }))
  const activeKind = statItems.find((it) => isActive(it.key))?.key ?? null
  const selectedDay = filters.since && filters.since === filters.until ? filters.since : null

  // ── Sonuç bölgesi ──
  const hasFilters = activeFilters(effFilters).length > 0
  const initial = loadedKey == null && !error
  const retry = <Button type="button" variant="outline" className="max-sm:h-10 pointer-coarse:h-10" onClick={load}><RotateCcw aria-hidden="true" />{t('inc.retry')}</Button>
  let results
  if (initial) {
    results = <HistorySkeleton phone={phone} />
  } else if (error && !everLoaded) {
    results = <StatusBlock tone="danger" icon={AlertOctagon} role="alert" title={t('inc.loadError')} description={error} actions={retry} className="rounded-xl border py-14" />
  } else {
    results = (
      // Sonuç kabı süzgeç değişiminde SÖKÜLMEZ (yükseklik çökmez, kaydırma zıplamaz): eski içerik soluk kalır.
      <div data-slot="ih-results" data-stale={!settled || undefined} aria-busy={loading || undefined}
        className={cn('flex min-w-0 flex-col gap-3 transition-opacity motion-reduce:transition-none', busy && 'opacity-60')}>
        {rows.length === 0 ? (
          hasFilters
            ? (
              <StatusBlock tone="neutral" icon={FilterX} title={t('inc.noMatchTitle')} description={t('inc.noMatchText')} className="rounded-xl border border-dashed py-14"
                actions={<Button type="button" variant="outline" className="max-sm:h-10 pointer-coarse:h-10" onClick={reset}><FilterX aria-hidden="true" />{t('inc.clearFilters')}</Button>} />
            )
            : (
              <StatusBlock tone="neutral" icon={ListChecks} title={t('inc.emptyTitle')} description={t('inc.emptyText')} className="rounded-xl border border-dashed py-14"
                actions={allowManage ? <Button type="button" className="max-sm:h-10 pointer-coarse:h-10" onClick={openCreate}><Plus aria-hidden="true" />{t('inc.new')}</Button> : null} />
            )
        ) : (
          <HistoryList rows={rows} phone={phone} allowManage={allowManage} selected={selected} onToggle={toggleSel} onToggleAll={toggleAll}
            onOpen={openDetail} onEdit={openEdit} activeId={detailId} onStatusSaved={onStatusSaved} onStatusReload={onStatusReload} />
        )}
        {/* Standart sayfalama çubuğu — yüklenirken de yerinde kalır (sayfa değişiminde zıplamasın) */}
        <PaginationBar {...sp.bar} />
      </div>
    )
  }

  return (
    <section data-slot="incident-history" className="mb-8 flex min-w-0 flex-col gap-4">
      <HistoryHeader summary={sum} summaryFailed={trendsFailed && !sum} byStatus={byStatus} filters={effFilters} total={sp.total} loading={loading}
        onRefresh={refreshAll} onNew={openCreate} onQuick={openQuick} allowManage={allowManage} isActive={isActive} onCard={onCard} />

      <div className="flex flex-wrap items-center gap-2" data-slot="ih-view-switch">
        <SegmentedControl value={view} onChange={setView} ariaLabel={t('pm.viewLabel')}
          options={[{ value: 'incidents', label: t('pm.viewIncidents'), icon: ListChecks }, { value: 'actions', label: t('pm.viewActions'), icon: ClipboardList }]} />
      </div>

      {view === 'actions' ? <ActionItemsView onOpenIncident={openIncidentById} /> : (<>

      {/* Ayrıntılı döküm + günlük trend — projenin tek katlanır şeridi (ui/CollapsibleSection); varsayılan kapalı */}
      <CollapsibleSection open={showSummary} onOpenChange={setShowSummary} icon={BarChart3} label={t('inc.analysis')}
        hint={t('inc.analysisHint')} toggleLabel={showSummary ? t('inc.analysisHide') : t('inc.analysisShow')}
        contentClassName="mt-3 flex min-w-0 flex-col gap-3">
        <MonitorStatsBar items={statItems} activeFilter={activeKind} onStatClick={onCard} />
        <HistoryTrendChart data={dailyChart} days={trendDays} onDays={setTrendDays} selectedDay={selectedDay} onDayClick={toggleDay} />
      </CollapsibleSection>

      <HistoryToolbar filters={filters} qApplied={qTerm} onQuery={(v) => setFilters((f) => patchFilters(f, { q: v }))}
        onQueryCommit={() => setQTerm(filters.q.trim())} patch={patch} reset={reset} phone={phone}
        teams={teams} channels={channelOpts} resultCount={settled ? sp.total : null}
        busy={busy ? <Spinner size={14} label={t('inc.loading')} /> : null} />

      {/* Toplu takım aktarımı — seçim varken */}
      {allowManage && selected.size > 0 && (
        <div data-slot="incident-bulk" role="group" aria-label={t('inc.selectedN', selected.size)}
          className="flex flex-wrap items-center gap-2 rounded-xl border bg-muted/50 px-3 py-2 max-sm:*:h-10">
          <span className="text-sm font-bold">{t('inc.selectedN', selected.size)}</span>
          <div className="min-w-0 flex-1 *:w-full sm:flex-none sm:*:w-auto">
            <NativeSelect size="sm" aria-label={t('inc.transferTo')} value={transferTeam} onChange={(e) => setTransferTeam(e.target.value)}
              className="max-sm:h-10 pointer-coarse:h-10">
              <NativeSelectOption value="">{t('inc.transferTo')}</NativeSelectOption>
              {teams.map((tm) => <NativeSelectOption key={tm.id} value={String(tm.id)}>{tm.name}</NativeSelectOption>)}
            </NativeSelect>
          </div>
          <Button size="sm" disabled={!transferTeam || transferring} aria-busy={transferring || undefined} onClick={doTransfer}>
            <ArrowRightLeft aria-hidden="true" />{t('inc.transferBtn')}
          </Button>
          <Button variant="ghost" size="sm" onClick={() => setSelected(new Set())}><X aria-hidden="true" />{t('inc.clearSel')}</Button>
        </div>
      )}

      {error && everLoaded && (
        <AlertBanner tone="danger" role="alert" title={t('inc.loadError')} className="mb-0" actions={retry}>{error}</AlertBanner>
      )}

      {results}
      </>)}

      {detail && (() => {
        const freshState = detailFresh && String(detailFresh.id) === String(detail.id) ? detailFresh : null
        const gone = freshState?.state === 'gone'
        // Özet satırdan açılan ayrıntının markdown'ı tam kayıt gelene dek "yükleniyor"; okunamadıysa "okunamadı + yeniden dene"
        const mdState = !markdownOmitted(detail) ? 'ready'
          : (freshState?.state === 'failed' || gone ? 'failed' : 'loading')
        return (
          <HistoryDetailSheet key={`detail:${detail.id}`} record={detail} onClose={closeDetail} onEdit={openEdit} onDelete={removeIncident}
            onPostmortem={(r) => setPmFor(r)} allowManage={allowManage && detail.can_edit !== false && !gone}
            allowDelete={allowDelete && detail.can_edit !== false && !gone} fresh={freshState}
            onDuplicate={allowManage ? openDuplicate : undefined} onStatusSaved={onStatusSaved} onStatusReload={onStatusReload}
            mdState={mdState} onRetryMarkdown={() => refreshDetail(detail)} />
        )
      })()}

      {pmFor && (
        <PostmortemModal key={`pm:${pmFor.id}`} incident={pmFor} onClose={() => setPmFor(null)} onChanged={refreshAll} />
      )}

      {form && (
        <IncidentFormModal key={`${form.mode}:${form.copyOf ? `copy:${form.copyOf.id}` : (form.initial.id ?? 'new')}${form.quick ? ':quick' : ''}`}
          mode={form.mode} initial={form.initial} teams={teams} quick={!!form.quick} copyOf={form.copyOf ?? null}
          options={{ channel: channelOpts, domain: domainOpts, errorCode: errorCodeOpts, functionCode: functionCodeOpts, channelCode: channelCodeOpts }}
          onAddOption={addOption} onDeleteOption={deleteOption} onSubmit={submitIncident} onClose={() => setForm(null)}
          onFresh={mergeRecord} />
      )}
    </section>
  )
}
