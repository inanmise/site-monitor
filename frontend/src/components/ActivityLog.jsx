import { LoadingBlock } from './ui/Progress.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import { Suspense, lazy, useEffect, useState, useCallback, useMemo, useRef } from 'react'
import { api, formatDateSec } from '../api/client'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useBranding } from '../contexts/BrandingProvider.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { useServerPagination } from '../hooks/useServerPagination.js'
import { VIEW_SPECS } from '../hooks/userPrefsModel.js'
import {
  Activity, CheckCircle, AlertTriangle, XCircle, ChevronRight, X, RefreshCw, Inbox, ExternalLink, FileSearch, ChartNoAxesGantt,
  BellRing,
} from 'lucide-react'
import { relTimeOrRaw } from '../utils/relativeTime.js'
import { META_BRAND, applyPageMeta, formatTitle, pageMetaKey, tabMetaKey } from '../utils/pageMeta.js'
import TeamBadge from './ui/TeamBadge.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import SavedViewsMenu from './ui/SavedViewsMenu.jsx'
import MonitorStatsBar from './MonitorStatsBar.jsx'
import { navigateTo } from '../utils/navigate.js'
import { useIsMobile } from '../hooks/use-mobile.js'
import { useElementWidthState } from '../hooks/useElementWidth.js'
import ActivityTimeline from './activity/ActivityTimeline.jsx'
import ActivityFilterBar from './activity/ActivityFilterBar.jsx'
import ActivityExportMenu from './activity/ActivityExportMenu.jsx'
import { ReasonBadge, STATUS_META, TypeBadge, isFailure } from './activity/ActivityParts.jsx'
import {
  CHIPS_INLINE_MIN_PX, KPI_STATUS, formatMonitorRef, isAlarmAction, isFiltered, istanbulDayStartMs, kpiKeyOf, localizeSummary, monitorRefOf,
  pickLayout, rangeToFromTo, readFilters, readRoute, routeSearch, sameRoute, toApiParams, writeFilters,
} from './activity/activityModel.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { activityTarget } from '../utils/activityTarget.js'

// Satır → izleme sekmesi hedefi: tek kaynak utils/activityTarget.js (Kişisel Panom da kullanır); testler buradan da alır
export { activityTarget }
// Zaman penceresi yardımcıları modele taşındı (2026-11-05); eski içe aktarımlar çalışsın
export { istanbulDayStartMs, rangeToFromTo }

// Ayrıntı sayfası (fe1) ve izleme zaman çizelgesi YALNIZ açılınca yüklenir (lazyChunks kapısı)
const ActivityDetailPage = lazy(() => import('./activity/detail/ActivityDetailPage.jsx'))
const MonitorTimelineView = lazy(() => import('./activity/timeline/MonitorTimelineView.jsx'))

/** App.handleTabChange aynı sekmede param yazınca yayılır (App.jsx TAB_PARAMS_EVENT; App'i içe aktarmamak için yerel). */
const TAB_PARAMS_EVENT = 'sm:tab-params'

// Sunucu özetinin jeton çevirisi modelde (2026-11-05: ayrıntı sayfası da kullanır); eski içe aktarımlar çalışsın
export { localizeSummary }

/** Sonuç özeti (ikon + yerelleştirilmiş özet). */
function ResultSummary({ row, t, className }) {
  const sm = STATUS_META[row.result_status] || STATUS_META.UNKNOWN
  return (
    <span data-slot="act-result" className={cn('inline-flex min-w-0 items-center gap-1 font-semibold tabular-nums', sm.ink, className)}>
      <sm.Icon aria-hidden="true" className="size-3.5 shrink-0" />
      <span className="min-w-0 break-words">{localizeSummary(row.result_summary, t, row) || t('act.st.' + row.result_status)}</span>
    </span>
  )
}

/** Satırın ek rozetleri: alarm olayı, HTTP kodu, hata nedeni (2026-11-05, G). Yoksa hiçbir şey. */
function RowBadges({ row, t }) {
  const alarm = row.alert_event_id != null || isAlarmAction(row.action)
  if (!alarm && row.http_status == null && !row.failure_reason) return null
  return (
    <span data-slot="act-badges" className="flex min-w-0 flex-wrap items-center gap-1">
      {alarm && (
        <Badge variant="outline" data-slot="act-alarm-badge" className="h-auto gap-1 rounded-md px-1.5 py-0 text-[0.78em] font-medium whitespace-normal">
          <BellRing aria-hidden="true" className="size-3" />
          {row.alert_event_id != null ? t('act.alarmNo', row.alert_event_id) : t('act.alarmEvent')}
        </Badge>
      )}
      {row.http_status != null && (
        <Badge variant="outline" data-slot="act-http" className="h-auto rounded-md px-1.5 py-0 font-mono text-[0.78em]">
          {t('act.httpCode', row.http_status)}
        </Badge>
      )}
      <ReasonBadge row={row} t={t} />
    </span>
  )
}

/**
 * Belge başlığı / açıklaması — rota değişince. App'in kendi yazımından SONRA (setTimeout) uygulanır (iki yazar sırası).
 * Ayrıntı sayfası izlemenin adını verirse (`onTitle`, fe1) başlık "<ad> — Aktivite ayrıntısı".
 */
function useRouteMeta(kind, name) {
  const t = useT()
  const { get } = useBranding()
  const brand = String(get('tab_title', '') || get('app_name', '') || META_BRAND)
  const key = kind === 'detail' ? pageMetaKey('activityDetail') : kind === 'timeline' ? pageMetaKey('activityTimeline') : tabMetaKey('activity')
  const rawTitle = t(`${key}.title`)
  const rawDesc = t(`${key}.description`)
  const pageTitle = rawTitle !== `${key}.title` ? rawTitle : ''
  const title = formatTitle(kind === 'detail' && name ? `${name} — ${pageTitle}` : pageTitle, brand)
  const description = rawDesc !== `${key}.description` ? rawDesc : null
  useEffect(() => {
    const id = setTimeout(() => applyPageMeta(title, description), 0)
    return () => clearTimeout(id)
  }, [title, description])
}

const scrollEl = () => (typeof document !== 'undefined' ? document.scrollingElement || document.documentElement : null)

/**
 * Kayıtlar → Aktivite (2026-11-05 zenginleştirme). Rota (URL'de, paylaşılabilir):
 *   - `?tab=activity&act=<id>` → ayrıntı SAYFASI (fe1 `activity/detail/ActivityDetailPage.jsx`, tembel parça),
 *   - `?tab=activity&amon=<TYPE>:<id>` → izleme zaman çizelgesi (`activity/timeline/MonitorTimelineView.jsx`, tembel),
 *   - ikisi de yoksa liste. Liste süzgeçleri URL'de kalır; ayrıntıya geçişte liste SÖKÜLMEZ (gizlenir: veri, sayfa,
 *     katlama ve kaydırma konumu korunur). "Geri" listeye süzgeçleriyle döner: listeden itilen geçmiş kayıtları
 *     (`history.state.actDepth`) kadar geri gidilir; doğrudan açılan bağlantıda (derinlik yok) `act` / `amon` silinir.
 * Tarayıcı Geri / İleri ve aynı sekmeye param yazan gezinme (`sm:tab-params`) rotayı URL'den yeniden okur.
 */
export default function ActivityLog({ refreshTrigger }) {
  const t = useT()
  const [route, setRoute] = useState(() => readRoute(window.location.search))
  const routeRef = useRef(route)
  routeRef.current = route
  const [listMounted, setListMounted] = useState(route.kind === 'list')
  const [listNonce, setListNonce] = useState(0)
  const listScroll = useRef(0)
  const [detailTitle, setDetailTitle] = useState(null)
  useEffect(() => { setDetailTitle(null) }, [route])
  useRouteMeta(route.kind, detailTitle)
  const onDetailTitle = useCallback((name) => setDetailTitle(name ? String(name).slice(0, 120) : null), [])

  useEffect(() => { if (route.kind === 'list') setListMounted(true) }, [route.kind])

  // Listeye dönüşte kaydırma konumu geri gelir; başka rotaya geçişte sayfa başı (ilk açılışta dokunulmaz)
  const prevKind = useRef(route.kind)
  useEffect(() => {
    const was = prevKind.current
    prevKind.current = route.kind
    const el = scrollEl()
    if (!el) return undefined
    if (route.kind === 'list') {
      if (was === 'list') return undefined
      const y = listScroll.current
      const id = requestAnimationFrame(() => { el.scrollTop = y })
      return () => cancelAnimationFrame(id)
    }
    el.scrollTop = 0
    return undefined
  }, [route])

  useEffect(() => {
    const sync = () => {
      try {
        if (new URLSearchParams(window.location.search).get('tab') !== 'activity') return
      } catch { return }
      const next = readRoute(window.location.search)
      setRoute((cur) => (sameRoute(cur, next) ? cur : next))
    }
    window.addEventListener('popstate', sync)
    window.addEventListener(TAB_PARAMS_EVENT, sync)
    return () => { window.removeEventListener('popstate', sync); window.removeEventListener(TAB_PARAMS_EVENT, sync) }
  }, [])

  const go = useCallback((next) => {
    if (sameRoute(next, routeRef.current)) return
    if (routeRef.current.kind === 'list') listScroll.current = scrollEl()?.scrollTop || 0
    try {
      const depth = (Number(window.history.state?.actDepth) || 0) + 1
      const url = window.location.pathname + routeSearch(window.location.search, next) + window.location.hash
      window.history.pushState({ ...(window.history.state || {}), tab: 'activity', actDepth: depth }, '', url)
    } catch { /* history yok */ }
    setRoute(next)
  }, [])

  const openActivity = useCallback((id) => { if (id != null) go({ kind: 'detail', id: Number(id) }) }, [go])
  const openTimeline = useCallback((type, id) => { if (type && id != null) go({ kind: 'timeline', type: String(type).toUpperCase(), id: Number(id) }) }, [go])

  const backToList = useCallback(() => {
    const depth = Number(window.history.state?.actDepth) || 0
    if (depth > 0 && typeof window.history.go === 'function') {
      window.history.go(-depth)   // popstate → rota listeye döner (liste girdisinin süzgeçleriyle)
      return
    }
    try {
      const url = window.location.pathname + routeSearch(window.location.search, { kind: 'list' }) + window.location.hash
      window.history.replaceState({ ...(window.history.state || {}), actDepth: 0 }, '', url)
    } catch { /* history yok */ }
    setRoute({ kind: 'list' })
  }, [])

  // Zaman çizelgesinden "Bu izlemenin aktiviteleri": liste o izlemeye süzülür (liste URL'den yeniden kurulur)
  const filterListByMonitor = useCallback((type, id, label) => {
    try {
      const base = new URLSearchParams(writeFilters({ ...readFilters(window.location.search), monitor: formatMonitorRef(type, id),
        monitorLabel: String(label || '').slice(0, 160) }, window.location.search))
      const url = window.location.pathname + routeSearch(`?${base.toString()}`, { kind: 'list' }) + window.location.hash
      window.history.pushState({ ...(window.history.state || {}), tab: 'activity', actDepth: 0 }, '', url)
    } catch { /* history yok */ }
    listScroll.current = 0
    setListNonce((n) => n + 1)
    setRoute({ kind: 'list' })
  }, [])

  const fallback = <LoadingBlock label={t('act.loading')} fullWidth />

  return (
    <div data-slot="activity-page" data-route={route.kind} className="min-w-0">
      {listMounted && (
        <div hidden={route.kind !== 'list'} className="min-w-0">
          <ActivityList key={listNonce} refreshTrigger={refreshTrigger} active={route.kind === 'list'}
            onOpenActivity={openActivity} onOpenTimeline={openTimeline} />
        </div>
      )}
      {route.kind === 'detail' && (
        <Suspense fallback={fallback}>
          <ActivityDetailPage key={route.id} id={route.id} onBack={backToList} onOpenActivity={openActivity} onOpenTimeline={openTimeline}
            onTitle={onDetailTitle} />
        </Suspense>
      )}
      {route.kind === 'timeline' && (
        <Suspense fallback={fallback}>
          <MonitorTimelineView key={`${route.type}:${route.id}`} type={route.type} id={route.id} onBack={backToList}
            onOpenActivity={openActivity} onFilterList={filterListByMonitor} />
        </Suspense>
      )}
    </div>
  )
}

/** Aktivite listesi (özet, süzgeçler, akış, sayfalama) — rota liste değilken gizli ama bağlı kalır. */
function ActivityList({ refreshTrigger, active, onOpenActivity, onOpenTimeline }) {
  const t = useT()
  const { lang } = useLanguage()
  // Tablo ↔ kart KAP genişliğiyle (2026-10-11 yeniden tasarım): 768 px tablette kenar çubuğu açıkken içerik dar.
  // Ölçüm yoksa (jsdom, ilk boyama) görünüm alanı sorgusuna düşer — TEK varyant çizilir.
  const phone = useIsMobile()
  const [feedWidth, setFeedEl] = useElementWidthState()
  const layout = pickLayout(feedWidth, phone)
  const [filterWidth, setFilterEl] = useElementWidthState()
  const compactFilters = filterWidth > 0 && filterWidth < CHIPS_INLINE_MIN_PX
  const [filters, setFilters] = useState(() => readFilters(window.location.search))
  const [data, setData] = useState([])
  const [teams, setTeams] = useState({ status: 'loading', list: [] })
  const [exportNotice, setExportNotice] = useState(null)   // { format, max } — tavan aşıldı
  // Zaman grupları + ardışık aynı-hedef katlama (2026-09-12, #22). Katlama: aynı monitör türü + hedef, arka
  // arkaya ≥ 3 satır → tek "N kontrol" satırı; kullanıcı açınca (unfolded) satırlar tek tek döner.
  const [unfolded, setUnfolded] = useState(() => new Set())
  const groupedFeed = useMemo(() => {
    const out = []
    const startToday = istanbulDayStartMs()
    const startYesterday = startToday - 86400000, startWeek = startToday - 6 * 86400000
    const groupOf = (iso) => { const ms = new Date(iso && !iso.endsWith('Z') && !iso.includes('+') ? iso + 'Z' : iso).getTime()
      return !Number.isFinite(ms) ? 'older' : ms >= startToday ? 'today' : ms >= startYesterday ? 'yesterday' : ms >= startWeek ? 'week' : 'older' }
    let lastGroup = null
    let i = 0
    while (i < data.length) {
      const row = data[i]
      const g = groupOf(row.activity_time)
      if (g !== lastGroup) { const count = data.filter((r) => groupOf(r.activity_time) === g).length; out.push({ kind: 'head', key: 'h-' + g, group: g, count }); lastGroup = g }
      let j = i + 1
      while (j < data.length && data[j].monitor_type === row.monitor_type && data[j].target === row.target && groupOf(data[j].activity_time) === g) j++
      const run = data.slice(i, j)
      const key = 'f-' + row.monitor_type + '-' + row.target + '-' + row.id
      if (run.length >= 3 && !unfolded.has(key)) out.push({ kind: 'fold', key, rows: run })
      else run.forEach((r) => out.push({ kind: 'row', key: 'r-' + r.id, row: r }))
      i = j
    }
    return out
  }, [data, unfolded])

  // Sayfalama standardı (2026-09-26): el yapımı "Önceki/Sonraki" yerine useServerPagination + PaginationBar.
  // Süzgeç değişince sayfa 1 (değer karşılaştırmalı, mount'ta değil); API 0-tabanlı.
  const sp = useServerPagination({ listKey: 'activity-log', preset: 'page', resetDeps: [filters], apiBase: 0 })
  const [summary, setSummary] = useState(null)
  const [summaryError, setSummaryError] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError]     = useState(null)   // null | { message, details } — okuma hatası (sunucunun metni)

  // Ortak yardımcı (utils/relativeTime.relTimeOrRaw — öneri 29): boş damga "—", çözülemeyen damga olduğu gibi; çıktı aynı.
  const rel = useCallback((iso) => relTimeOrRaw(iso, t), [t])

  // Yarış koruması: sayfa/süzgeç hızlı değişirse yalnız SON isteğin yanıtı uygulanır.
  const loadSeq = useRef(0)
  const summaryKey = useRef(null)   // özetin ait olduğu süzgeç — sayfa değişimi özeti SİLMEZ (özet sayfadan bağımsız)
  const { apiPage, pageSize } = sp
  const load = useCallback((silent) => {
    const seq = ++loadSeq.current
    // Aralık (from/to) her istekte hesaplanır: 30 sn'lik canlı tazeleme ve Yenile hep "şimdi"ye göre süzer.
    const params = toApiParams(filters)
    const key = JSON.stringify(filters)
    const filterChanged = summaryKey.current !== key
    summaryKey.current = key
    if (!silent) setLoading(true)
    if (filterChanged) {
      // Süzgeç değişti: önceki süzgecin sayaçları / grafiği yeni liste yüklenirken GÖSTERİLMEZ (2026-10-12). Sayfa
      // değişimi / Yenile aynı süzgecin özetini yeni yanıt gelene kadar tutar.
      setSummary(null)
      setSummaryError(false)
    }
    setError(null)
    const list = api.getActivity({ ...params, page: apiPage, size: pageSize })
      .then((res) => {
        if (seq !== loadSeq.current) return
        if (res?.success) { setData(res.data); sp.bind(res) }
        else setError({ message: res?.error || null, details: res })
      })
      .catch((e) => { if (seq === loadSeq.current) setError({ message: e?.message || null, details: e }) })
      .finally(() => { if (seq === loadSeq.current) setLoading(false) })
    // Özet de aynı sıra damgasıyla (2026-10-09): eski süzgecin geç gelen özeti yeni listenin sayaçlarını ezmesin. Hata
    // durumunda sayaçlar "0" değil "—" ve grafik hata satırı gösterir (2026-10-12).
    const sum = api.getActivitySummary(params)
      .then((res) => {
        if (seq !== loadSeq.current) return
        if (res?.success) { setSummary(res); setSummaryError(false) } else setSummaryError(true)
      })
      .catch(() => { if (seq === loadSeq.current) setSummaryError(true) })
    // İkisi bitene kadar söz döner: canlı tazeleme (useVisibleInterval) süren istek varken YENİSİNİ başlatmaz
    return Promise.allSettled([list, sum])
  }, [filters, apiPage, pageSize]) // eslint-disable-line react-hooks/exhaustive-deps

  // Süzgeç değişince URL'yi güncelle (geçmiş kaydı eklenmez; mevcut kaydın durumu korunur); sayfa sıfırlamasını
  // useServerPagination yapar. Liste gizliyken (ayrıntı açık) süzgeç değişmez — yazım rota anahtarlarına dokunmaz.
  useEffect(() => {
    try {
      const s = writeFilters(filters, window.location.search)
      window.history.replaceState(window.history.state, '', `${window.location.pathname}${s ? '?' + s : ''}${window.location.hash}`)
    } catch { /* history yok */ }
    setExportNotice(null)
  }, [filters])
  useEffect(() => { load(false) }, [load])
  useEffect(() => { if (refreshTrigger && active) load(true) }, [refreshTrigger]) // eslint-disable-line react-hooks/exhaustive-deps

  // Takım süzgecinin adları (görüş kapsamı sunucuda); okunamazsa seçici "takımlar okunamadı" der, liste etkilenmez
  useEffect(() => {
    let alive = true
    Promise.resolve().then(() => api.teams.directory())
      .then((res) => { if (alive) setTeams(res?.success ? { status: 'ok', list: Array.isArray(res.data) ? res.data : [] } : { status: 'error', list: [] }) })
      .catch(() => { if (alive) setTeams({ status: 'error', list: [] }) })
    return () => { alive = false }
  }, [])

  // Görünür sekmede, liste açıkken ve 1. sayfadayken sessiz otomatik tazeleme (gizli sekmede / ayrıntı açıkken durur).
  useVisibleInterval(() => (active && sp.page === 1 ? load(true) : undefined), 30000, false)

  const anyFilter = isFiltered(filters)
  const apiParams = useMemo(() => toApiParams(filters), [filters])
  // Satırların "Ayrıntı" bağlantısı: o anki süzgeçler + `act` (yeni sekmede açılınca Geri aynı listeye döner)
  const listSearch = useMemo(() => writeFilters(filters, window.location.search), [filters])
  const detailHref = (id) => `${window.location.pathname}${routeSearch(listSearch ? '?' + listSearch : '', { kind: 'detail', id })}`

  // Özet kalemleri = durum süzgeci (MonitorStatsBar). Etkin kart tekrar tıklanınca süzgeç kalkar; "Toplam" temizler.
  // "Hata" kartı ERROR + TIMEOUT sayar ve İKİSİNİ süzer (2026-11-05, G).
  const kpiKey = kpiKeyOf(filters.statuses)
  const statItems = [
    ['ALL', summary?.total ?? '—', t('act.sum.total'), Activity, 'total'],
    ['SUCCESS', summary ? summary.success_count ?? 0 : '—', t('act.st.SUCCESS'), CheckCircle, 'valid'],
    ['WARNING', summary ? summary.warning ?? 0 : '—', t('act.st.WARNING'), AlertTriangle, 'warning'],
    ['ERROR', summary ? summary.error ?? 0 : '—', t('act.st.ERROR'), XCircle, 'critical'],
  ].map(([key, value, label, Icon, cls]) => {
    const on = kpiKey === key
    const sub = key === 'ERROR' && summary && Number(summary.timeout) > 0 ? t('act.sumTimeoutSub', summary.timeout) : undefined
    return { key, value, label, Icon, cls, sub, hint: key === 'ERROR' ? t('act.sumErrorHint') : t('act.sumFilterHint'),
      tip: on && key !== 'ALL' ? t('a11y.rowAction', label, t('mondash.clearTip')) : t('mondash.filterTip', label) }
  })
  const onStat = (key) => setFilters((f) => ({ ...f, statuses: key === 'ALL' || kpiKeyOf(f.statuses) === key ? [] : [...KPI_STATUS[key]] }))

  const toggleFold = (key) => setUnfolded((u) => { const n = new Set(u); n.add(key); return n })
  /** Satıra basış: ayrıntı sayfası; Ctrl / ⌘ / orta tuş yeni sekmede (paylaşılabilir adres). */
  const openRow = (e, row) => {
    if (e && (e.ctrlKey || e.metaKey || e.button === 1)) {
      try { window.open(detailHref(row.id), '_blank', 'noopener') } catch { /* açılır pencere engelli */ }
      return
    }
    onOpenActivity(row.id)
  }
  /** "Ayrıntı" bağlantısı: düz tık uygulama içinde (geçmiş kaydıyla), değiştiricili tık / orta tuş tarayıcıya bırakılır. */
  const onDetailLink = (e, row) => {
    e.stopPropagation()
    if (e.defaultPrevented || e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return
    e.preventDefault()
    onOpenActivity(row.id)
  }
  const rowKey = (e, fn) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); fn() } }
  const ROW_CLICK = 'cursor-pointer outline-none focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary'
  const TH = 'h-9 px-2.5 text-[0.76em] font-semibold tracking-wide text-muted-foreground uppercase'
  const teamOf = (r, isStatic = true) => (r.team_id != null
    ? <TeamBadge teamId={r.team_id} teamName={r.team_name} static={isStatic} wrap />
    : <span className="text-muted-foreground">—</span>)
  const time = (iso, label) => <time dateTime={iso} title={formatDateSec(iso)} className="whitespace-nowrap tabular-nums">{label ?? rel(iso)}</time>
  const rowName = (row) => row.monitor_name || row.target || String(row.id)

  /** "İzlemeye git" — satırın adı + eylem (satırlar ayırt edilir); telefonda metinli, ≥ 40 px. */
  const goButton = (row, dest, withText = false) => (
    <Button type="button" variant={withText ? 'outline' : 'ghost'} size={withText ? 'sm' : 'icon-sm'}
      className={cn(withText ? 'h-10 shrink-0' : 'text-muted-foreground hover:text-primary')}
      title={t('act.goMonitor')} aria-label={t('act.goMonitorFor', rowName(row))}
      onClick={(e) => { e.stopPropagation(); navigateTo(dest.tab, dest.params) }}>
      <ExternalLink aria-hidden="true" />{withText && <span>{t('act.goMonitor')}</span>}
    </Button>
  )
  /** "Ayrıntı" — gerçek bağlantı (`<a href>`): yeni sekmede açılabilir, adres paylaşılabilir. */
  const detailLink = (row, withText = false) => (
    <Button asChild variant={withText ? 'outline' : 'ghost'} size={withText ? 'sm' : 'icon-sm'}
      className={cn(withText ? 'h-10 shrink-0' : 'text-muted-foreground hover:text-primary')}>
      <a href={detailHref(row.id)} data-slot="act-detail-link" title={t('act.openDetail')} aria-label={t('act.openDetailFor', rowName(row))}
        onClick={(e) => onDetailLink(e, row)} onKeyDown={(e) => e.stopPropagation()}>
        <FileSearch aria-hidden="true" />{withText && <span>{t('act.openDetail')}</span>}
      </a>
    </Button>
  )
  /** "Zaman çizelgesi" — yalnız izleme kimliği olan satırda. */
  const timelineButton = (row, withText = false) => {
    const ref = monitorRefOf(row)
    if (!ref) return null
    return (
      <Button type="button" variant={withText ? 'outline' : 'ghost'} size={withText ? 'icon' : 'icon-sm'} data-slot="act-timeline-link"
        className={cn(withText ? 'size-10 shrink-0' : 'text-muted-foreground hover:text-primary')}
        title={t('act.openTimeline')} aria-label={t('act.openTimelineFor', rowName(row))}
        onClick={(e) => { e.stopPropagation(); onOpenTimeline(ref.type, ref.id) }}>
        <ChartNoAxesGantt aria-hidden="true" />
      </Button>
    )
  }

  // ── Masaüstü / tablet: hizalı sütunlar (shadcn Table). Düşük öncelikli sütunlar dar ekranda gizli. ──
  const desktopFeed = (
    <div className="overflow-hidden rounded-lg border bg-card">
      <Table className="text-[0.88em]">
        <TableHeader className="bg-muted/50">
          <TableRow className="hover:bg-transparent">
            <TableHead className={cn(TH, 'w-8')}><span className="sr-only">{t('act.d.status')}</span></TableHead>
            <TableHead className={TH}>{t('act.d.type')}</TableHead>
            <TableHead className={TH}>{t('act.d.monitor')}</TableHead>
            <TableHead className={cn(TH, 'hidden xl:table-cell')}>{t('act.d.team')}</TableHead>
            <TableHead className={cn(TH, 'hidden lg:table-cell')}>{t('act.d.action')}</TableHead>
            <TableHead className={TH}>{t('act.d.result')}</TableHead>
            <TableHead className={cn(TH, 'text-right')}>{t('act.d.time')}</TableHead>
            <TableHead className={cn(TH, 'w-28')}><span className="sr-only">{t('act.d.actions')}</span></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {groupedFeed.map((entry) => {
            // Zaman grubu başlığı (2026-09-12, #22): bugün / dün / bu hafta / daha eski
            if (entry.kind === 'head') {
              return (
                <TableRow key={entry.key} data-slot="act-group-head" className="bg-muted/40 hover:bg-muted/40">
                  <TableCell colSpan={8} className="py-1.5 text-[0.76em] font-bold tracking-wider text-muted-foreground uppercase">
                    {t('act.group.' + entry.group)} <Badge variant="secondary" className="ml-1 h-4 px-1.5 text-[10px] tabular-nums">{entry.count}</Badge>
                  </TableCell>
                </TableRow>
              )
            }
            // Katlanmış ardışık koşu: aynı hedefin peş peşe N kontrolü tek satırda; tıklayınca açılır (içindeki satır sayfaya gider)
            if (entry.kind === 'fold') {
              const r0 = entry.rows[0]
              const errs = entry.rows.filter((r) => isFailure(r.result_status)).length
              const unfold = () => toggleFold(entry.key)
              return (
                <TableRow key={entry.key} data-slot="act-fold" tabIndex={0} className={cn(ROW_CLICK, 'bg-primary/5 hover:bg-primary/10')}
                  aria-label={t('a11y.toggleRow', `${r0.monitor_name} · ${t('act.folded', entry.rows.length)}`)}
                  onClick={unfold} onKeyDown={(e) => rowKey(e, unfold)}>
                  <TableCell className="text-muted-foreground"><ChevronRight aria-hidden="true" className="size-3.5" /></TableCell>
                  <TableCell><TypeBadge type={r0.monitor_type} /></TableCell>
                  <TableCell className="min-w-[12rem] whitespace-normal">
                    <span className="block font-semibold [overflow-wrap:anywhere]">{r0.monitor_name}</span>
                    <span className="block font-mono text-xs break-all text-muted-foreground">{r0.target}</span>
                  </TableCell>
                  <TableCell className="hidden xl:table-cell" data-col="team">{teamOf(r0)}</TableCell>
                  <TableCell className="hidden lg:table-cell" />
                  <TableCell className="min-w-[9rem] text-[0.9em] whitespace-normal text-primary">
                    {t('act.folded', entry.rows.length)}{errs ? <span className="font-semibold text-destructive"> · {t('act.foldedErrors', errs)}</span> : ''}
                  </TableCell>
                  <TableCell className="text-right text-[0.9em] whitespace-normal text-muted-foreground">{time(r0.activity_time, `${rel(entry.rows[entry.rows.length - 1].activity_time)} → ${rel(r0.activity_time)}`)}</TableCell>
                  <TableCell />
                </TableRow>
              )
            }
            const row = entry.row
            const sm = STATUS_META[row.result_status] || STATUS_META.UNKNOWN
            const dest = activityTarget(row)
            return (
              <TableRow key={entry.key} data-slot="act-item" data-status={row.result_status} tabIndex={0}
                aria-label={t('act.openDetailFor', rowName(row))}
                className={cn(ROW_CLICK, isFailure(row.result_status) && 'bg-destructive/[0.04]')}
                onClick={(e) => openRow(e, row)} onAuxClick={(e) => { if (e.button === 1) openRow(e, row) }}
                onKeyDown={(e) => rowKey(e, () => onOpenActivity(row.id))}>
                <TableCell className={sm.ink}><sm.Icon aria-hidden="true" className="size-3.5" /></TableCell>
                <TableCell><TypeBadge type={row.monitor_type} /></TableCell>
                <TableCell className="min-w-[12rem] whitespace-normal">
                  {/* Ad tıklanınca izlemeye gider (2026-09-20); satırın kalanı ayrıntı sayfasını açar. Ad ve hedef KESİLMEZ,
                      sarar (2026-10-11 — kural: adlar ve uzun metin kırpılmaz). */}
                  {dest ? (
                    <Button type="button" variant="link" size="xs" title={t('act.goMonitor')}
                      className="h-auto justify-start p-0 text-left text-[1em] font-semibold whitespace-normal text-foreground decoration-dotted underline-offset-[3px] hover:text-primary"
                      onClick={(e) => { e.stopPropagation(); navigateTo(dest.tab, dest.params) }}>
                      <span className="[overflow-wrap:anywhere]">{row.monitor_name}</span>
                    </Button>
                  ) : <span className="block font-semibold [overflow-wrap:anywhere]">{row.monitor_name}</span>}
                  {/* Hedef adın altında (ayrı sütun olsaydı zaman sütunu 1440 px'te ekran dışına itiliyordu) */}
                  {row.target && <span className="block font-mono text-xs break-all text-muted-foreground">{row.target}</span>}
                </TableCell>
                <TableCell className="hidden xl:table-cell" data-col="team">{teamOf(row)}</TableCell>
                <TableCell className="hidden text-[0.9em] whitespace-nowrap text-muted-foreground lg:table-cell">{t('act.ac.' + row.action)}</TableCell>
                <TableCell className="min-w-[9rem] whitespace-normal">
                  <span className="flex min-w-0 flex-col gap-1">
                    <ResultSummary row={row} t={t} className="text-[0.95em]" />
                    <RowBadges row={row} t={t} />
                  </span>
                </TableCell>
                <TableCell className="text-right text-[0.9em] text-muted-foreground">{time(row.activity_time)}</TableCell>
                <TableCell className="px-1">
                  <span className="flex items-center justify-end gap-0.5">
                    {detailLink(row)}
                    {timelineButton(row)}
                    {dest && goButton(row, dest)}
                  </span>
                </TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )

  // ── Telefon: kart listesi — üstte tür + ad + göreli zaman, altında hedef, sonuç + eylem + rozetler; alt şeritte takım +
  // "Ayrıntı" / zaman çizelgesi / "İzlemeye git". Metinler üst üste binmez (min-w-0 + sarma; ad ve hedef kesilmez); sol
  // renk şeridi YOK, hata kartı TÜM çerçeveyle belirtilir. ──
  const mobileFeed = (
    <ul className="flex list-none flex-col gap-2 p-0">
      {groupedFeed.map((entry) => {
        if (entry.kind === 'head') {
          return (
            <li key={entry.key} data-slot="act-group-head" className="mt-1 flex items-center gap-1.5 px-1 text-[0.76em] font-bold tracking-wider text-muted-foreground uppercase first:mt-0">
              {t('act.group.' + entry.group)} <Badge variant="secondary" className="h-4 px-1.5 text-[10px] tabular-nums">{entry.count}</Badge>
            </li>
          )
        }
        if (entry.kind === 'fold') {
          const r0 = entry.rows[0]
          const errs = entry.rows.filter((r) => isFailure(r.result_status)).length
          return (
            <li key={entry.key} data-slot="act-fold" className="min-w-0 overflow-hidden rounded-lg border border-dashed border-primary/40 bg-primary/5">
              <Button type="button" variant="ghost" onClick={() => toggleFold(entry.key)}
                className="h-auto min-h-11 w-full flex-col items-stretch gap-1 rounded-none px-3 py-2.5 text-left font-normal whitespace-normal">
                <span className="flex min-w-0 items-start gap-2">
                  <TypeBadge type={r0.monitor_type} />
                  <span className="min-w-0 flex-1 font-semibold [overflow-wrap:anywhere]">{r0.monitor_name}</span>
                  <ChevronRight aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                </span>
                <span className="text-[0.84em] text-primary">
                  {t('act.folded', entry.rows.length)}{errs ? <span className="font-semibold text-destructive"> · {t('act.foldedErrors', errs)}</span> : ''}
                </span>
                <span className="text-[0.78em] text-muted-foreground">{rel(entry.rows[entry.rows.length - 1].activity_time)} → {rel(r0.activity_time)}</span>
              </Button>
            </li>
          )
        }
        const row = entry.row
        const dest = activityTarget(row)
        return (
          <li key={entry.key} data-slot="act-item" data-status={row.result_status}
            className={cn('min-w-0 overflow-hidden rounded-lg border bg-card', isFailure(row.result_status) && 'border-destructive/50')}>
            <Button type="button" variant="ghost" aria-label={t('act.openDetailFor', rowName(row))}
              className="h-auto w-full flex-col items-stretch gap-1.5 rounded-none px-3 py-2.5 text-left font-normal whitespace-normal"
              onClick={(e) => openRow(e, row)}>
              <span className="flex min-w-0 items-start gap-2">
                <TypeBadge type={row.monitor_type} />
                <span className="min-w-0 flex-1 font-semibold [overflow-wrap:anywhere]">{row.monitor_name}</span>
                <span className="shrink-0 text-[0.78em] text-muted-foreground">{time(row.activity_time)}</span>
                <ChevronRight aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
              </span>
              {row.target && <span className="block min-w-0 font-mono text-[0.78em] break-all text-muted-foreground">{row.target}</span>}
              <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5 text-[0.84em]">
                <ResultSummary row={row} t={t} />
                <span className="text-muted-foreground">· {t('act.ac.' + row.action)}</span>
              </span>
              <RowBadges row={row} t={t} />
            </Button>
            <div className="flex min-w-0 flex-wrap items-center justify-between gap-2 border-t px-3 py-1.5">
              <span className="min-w-0" data-col="team">{teamOf(row)}</span>
              <span className="flex flex-wrap items-center gap-1.5">
                {detailLink(row, true)}
                {timelineButton(row, true)}
                {dest && goButton(row, dest, true)}
              </span>
            </div>
          </li>
        )
      })}
    </ul>
  )

  return (
    <div data-slot="activity-log" className="flex min-w-0 flex-col gap-4">
      {/* Özet — tıklanabilir sayım kartları (durum süzgeci) */}
      <MonitorStatsBar items={statItems} activeFilter={kpiKey || null} onStatClick={onStat} className="mb-0" />

      {/* Zaman çizelgesi — seçili aralıkta sonuç dağılımı + son aktivite + canlı göstergesi */}
      <ActivityTimeline series={summary?.series} loading={!summary && !summaryError} failed={!summary && summaryError} lastActivity={summary?.last_activity}
        live={sp.page === 1 && !error && !(!summary && summaryError)} />

      {/* Süzgeç kartı — aralık / arama / eylemler; durum, neden, takım, izleme, kişi; olay grubu ve tür çipleri; etkin süzgeç çipleri */}
      <div ref={setFilterEl} className="min-w-0">
        <ActivityFilterBar filters={filters} setFilters={setFilters} compact={compactFilters}
          typeCounts={summary?.type_counts || null} groupCounts={summary?.group_counts || null} teams={teams}
          actions={(
            <>
              <Button type="button" variant="outline" size="icon" onClick={() => load(false)} title={t('act.refresh')} aria-label={t('act.refresh')}
                className="pointer-coarse:size-10">
                <RefreshCw aria-hidden="true" />
              </Button>
              <ActivityExportMenu params={apiParams} onTruncated={(format, max) => setExportNotice({ format, max })} />
              <SavedViewsMenu listKey="activity" tab="activity" {...VIEW_SPECS.activity} size="default" className="pointer-coarse:h-10" />
            </>
          )} />
      </div>

      {exportNotice && (
        <div data-slot="act-export-truncated" data-format={exportNotice.format}>
          <AlertBanner tone="warning" className="mb-0" onDismiss={() => setExportNotice(null)} dismissLabel={t('act.ex.dismiss')}>
            {t('act.ex.truncated', exportNotice.max.toLocaleString(lang === 'en' ? 'en-GB' : 'tr-TR'))}
          </AlertBanner>
        </div>
      )}

      {filters.monitor && (
        <div data-slot="act-monitor-scope" className="flex min-w-0 flex-wrap items-center gap-2 text-sm">
          <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10"
            onClick={() => { const r = filters.monitor.split(':'); onOpenTimeline(r[0], Number(r[1])) }}>
            <ChartNoAxesGantt aria-hidden="true" /> {t('act.openTimelineOf', filters.monitorLabel || filters.monitor)}
          </Button>
        </div>
      )}

      {/* Ana liste — yükleme göstergesi yalnız elde veri yokken; sayfa değişiminde liste + çubuk yerinde kalır
          (akış sıfırlanıp çubuk kaybolursa sayfa zıplıyordu) */}
      {loading && data.length === 0 ? (
        <LoadingBlock label={t('act.loading')} fullWidth />
      ) : error ? (
        <StatusBlock tone="danger" icon={XCircle} title={t('act.error')} description={error.message || undefined} role="alert"
          details={error.details}
          actions={<Button type="button" variant="outline" onClick={() => load(false)}><RefreshCw aria-hidden="true" /> {t('act.refresh')}</Button>} />
      ) : data.length === 0 ? (
        <StatusBlock tone="neutral" icon={Inbox} title={anyFilter ? t('act.noMatch') : t('act.empty')} description={anyFilter ? t('empty.hintFilter') : t('empty.hintActivity')}
          actions={anyFilter ? (
            <Button type="button" variant="outline" className="pointer-coarse:h-10" onClick={() => setFilters(readFilters(''))}>
              <X aria-hidden="true" /> {t('act.clearFilter')}
            </Button>
          ) : undefined} />
      ) : (
        <>
          <div ref={setFeedEl} data-slot="act-feed" data-layout={layout} aria-busy={loading || undefined} className={cn('min-w-0', loading && 'opacity-70')}>
            {layout === 'cards' ? mobileFeed : desktopFeed}
          </div>

          {/* Sayfalama — proje standardı (shadcn Data Table deseni) */}
          <PaginationBar {...sp.bar} />
        </>
      )}
    </div>
  )
}
