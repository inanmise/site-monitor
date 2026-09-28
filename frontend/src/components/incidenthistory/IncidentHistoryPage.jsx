import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import {
  ListChecks, BarChart3, Sigma, AlertOctagon, AlertTriangle, AlertCircle, ArrowDownCircle, CircleDot, Search as SearchIcon,
  Shield, CheckCircle2, ShieldCheck, CalendarDays, CalendarRange, Calendar, Lock, RotateCcw, FilterX, Plus, ArrowRightLeft, X,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import { useUrlQuerySync, readUrlParam } from '../../hooks/useUrlQuerySync.js'
import { useDelayedFlag } from '../../hooks/useDelayedFlag.js'
import { useToast } from '../ui/Toast.jsx'
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
import IncidentFormModal from './IncidentFormModal.jsx'
import {
  EMPTY_FORM, DETAIL_KEY, LEGACY_DETAIL_KEY, FILTER_DEFAULTS, filtersFromUrl, filtersToUrl, initialDetailId, serverParams,
  localDayToUtcIso, activeFilters, patchFilters, isCardActive, applyCardFilter, toggleDayFilter, dailySeries, trendWindow,
} from './incidentHistoryModel.js'

/** Arama kutusu bu kadar duraklamadan sonra uygulanır (her tuşta istek yok); Enter hemen uygular. */
export const SEARCH_DEBOUNCE_MS = 300
/** Bu genişliğin altında (telefon + tablet) liste kart, süzgeçler alttan Sheet — 768 px'te kenar çubuğu açıkken tabloya
 *  ~460 px kalıyor, sütunlar sıkışıp yatay kayıyordu (2026-09-28 ölçümü). */
const COMPACT_BREAKPOINT = 1024

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

  // ── Seçim, ayrıntı, form ──
  const [selected, setSelected] = useState(() => new Set())
  const [transferTeam, setTransferTeam] = useState('')
  const [transferring, setTransferring] = useState(false)
  const [detailId, setDetailId] = useState(() => initialDetailId(readUrlParam))
  const [fallback, setFallback] = useState(null)       // sayfada olmayan (derin bağlantı / az önce kaydedilen) kayıt
  const [form, setForm] = useState(null)               // { mode: 'create'|'edit', initial }

  useUrlQuerySync({ ...filtersToUrl({ ...filters, q: qTerm }), [DETAIL_KEY]: detailId })

  const load = useCallback(async () => {
    if (!allowView) return
    const seq = ++loadSeq.current
    const key = requestKey
    setLoading(true)
    try {
      const res = await api.incidents.list({ ...params, page: apiPage, size })
      if (seq !== loadSeq.current) return   // bayat yanıt — daha yeni bir istek yolda
      if (res?.success) {
        setRows(res.data ?? []); sp.bind(res); setError(null); setEverLoaded(true)
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
    try {
      const res = await api.incidents.trends(localDayToUtcIso(filters.since, false), localDayToUtcIso(filters.until, true))
      if (my !== trendsSeq.current) return
      if (res?.success) setTrends(res.data)
    } catch { /* sessiz — özet boş kalır, liste yine çizilir */ }
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

  const loadOptions = useCallback(async () => {
    if (!allowView) return
    try {
      const [ch, dm, ec, fc, cc] = await Promise.all([
        api.incidents.options('CHANNEL'), api.incidents.options('DOMAIN'), api.incidents.options('ERROR_CODE'),
        api.incidents.options('FUNCTION_CODE'), api.incidents.options('CHANNEL_CODE')])
      if (ch?.success) setChannelOpts(ch.data ?? [])
      if (dm?.success) setDomainOpts(dm.data ?? [])
      if (ec?.success) setErrorCodeOpts(ec.data ?? [])
      if (fc?.success) setFunctionCodeOpts(fc.data ?? [])
      if (cc?.success) setChannelCodeOpts(cc.data ?? [])
    } catch { /* sessiz — seçim listeleri boş kalır, yeni değer yine eklenebilir */ }
  }, [allowView])

  const loadTeams = useCallback(async () => {
    if (!allowView) return
    try {
      const res = await api.admin.getTeams()
      if (res?.success) setTeams(res.data ?? [])
    } catch { /* sessiz — yetkisizse liste boş kalır ("tüm takımlar" gibi davranır) */ }
  }, [allowView])

  useEffect(() => { load() }, [load])
  useEffect(() => { loadTrends() }, [loadTrends])
  useEffect(() => { loadTrendDaily() }, [loadTrendDaily])
  useEffect(() => { loadOptions() }, [loadOptions])
  useEffect(() => { loadTeams() }, [loadTeams])
  // Sayfa/süzgeç değişince toplu seçim sıfırlanır (görünmeyen satır seçili kalmasın).
  useEffect(() => { setSelected(new Set()) }, [requestKey])

  const refreshAll = useCallback(() => { load(); loadTrends(); loadTrendDaily() }, [load, loadTrends, loadTrendDaily])

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

  const dailyChart = useMemo(() => dailySeries(trendDaily, trendDays), [trendDaily, trendDays])
  const settled = loadedKey === requestKey
  // Gösterge yalnız GÖRÜNÜMÜ bayat bir yükleme ~180 ms'yi aşarsa (hızlı yanıtta ekran yanıp sönmez).
  const busy = useDelayedFlag(loading && !settled, 180)
  const detail = useMemo(() => {
    if (detailId == null) return null
    return rows.find((r) => String(r.id) === String(detailId))
      || (fallback && String(fallback.id) === String(detailId) ? fallback : null)
  }, [rows, fallback, detailId])

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
  const openDetail = (r) => { setFallback(r); setDetailId(String(r.id)) }
  const closeDetail = () => setDetailId(null)
  const openCreate = () => setForm({ mode: 'create', initial: { ...EMPTY_FORM } })
  const openEdit = (r) => setForm({ mode: 'edit', initial: { ...EMPTY_FORM, ...r } })

  async function submitIncident(payload) {
    const mode = form?.mode
    try {
      const res = mode === 'create'
        ? await api.incidents.create(payload)
        : await api.incidents.update(payload.id, payload)
      if (!res?.success) return { ok: false, error: res?.error || t('inc.saveError') }
      toast.success(t('inc.saved'))
      // Ayrıntı açıksa (düzenleme ayrıntıdan açıldı) güncel kaydı hemen göster — liste yeniden yüklenene kadar beklemez.
      if (res.data && detailId != null && String(res.data.id) === String(detailId)) {
        setFallback(res.data)
        setRows((rs) => rs.map((r) => (String(r.id) === String(res.data.id) ? { ...r, ...res.data } : r)))
      }
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
    const all = rows.length > 0 && rows.every((r) => s.has(r.id))
    const n = new Set(s)
    rows.forEach((r) => (all ? n.delete(r.id) : n.add(r.id)))
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
  ].map(([k, v, cls, kind, Icon]) => ({ key: kind, label: t('inc.' + k), value: v ?? 0, cls, Icon, hint: t('inc.filterByCard') }))
  const activeKind = statItems.find((it) => isActive(it.key))?.key ?? null
  const selectedDay = filters.since && filters.since === filters.until ? filters.since : null

  // ── Sonuç bölgesi ──
  const hasFilters = activeFilters(effFilters).length > 0
  const initial = loadedKey == null && !error
  const retry = <Button type="button" variant="outline" className="max-sm:h-10" onClick={load}><RotateCcw aria-hidden="true" />{t('inc.retry')}</Button>
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
                actions={<Button type="button" variant="outline" className="max-sm:h-10" onClick={reset}><FilterX aria-hidden="true" />{t('inc.clearFilters')}</Button>} />
            )
            : (
              <StatusBlock tone="neutral" icon={ListChecks} title={t('inc.emptyTitle')} description={t('inc.emptyText')} className="rounded-xl border border-dashed py-14"
                actions={allowManage ? <Button type="button" className="max-sm:h-10" onClick={openCreate}><Plus aria-hidden="true" />{t('inc.new')}</Button> : null} />
            )
        ) : (
          <HistoryList rows={rows} phone={phone} allowManage={allowManage} selected={selected} onToggle={toggleSel} onToggleAll={toggleAll}
            onOpen={openDetail} onEdit={openEdit} activeId={detailId} />
        )}
        {/* Standart sayfalama çubuğu — yüklenirken de yerinde kalır (sayfa değişiminde zıplamasın) */}
        <PaginationBar {...sp.bar} />
      </div>
    )
  }

  return (
    <section data-slot="incident-history" className="mb-8 flex min-w-0 flex-col gap-4">
      <HistoryHeader summary={sum} byStatus={byStatus} filters={effFilters} total={sp.total} loading={loading}
        onRefresh={refreshAll} onNew={openCreate} allowManage={allowManage} isActive={isActive} onCard={onCard} />

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
              className="max-sm:h-10">
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

      {detail && (
        <HistoryDetailSheet key={detail.id} record={detail} onClose={closeDetail} onEdit={openEdit} onDelete={removeIncident}
          allowManage={allowManage} allowDelete={allowDelete} />
      )}

      {form && (
        <IncidentFormModal key={`${form.mode}:${form.initial.id ?? 'new'}`} mode={form.mode} initial={form.initial} teams={teams}
          options={{ channel: channelOpts, domain: domainOpts, errorCode: errorCodeOpts, functionCode: functionCodeOpts, channelCode: channelCodeOpts }}
          onAddOption={addOption} onDeleteOption={deleteOption} onSubmit={submitIncident} onClose={() => setForm(null)} />
      )}
    </section>
  )
}
