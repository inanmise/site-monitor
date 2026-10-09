import { useState, useEffect, useRef, useMemo, useCallback } from 'react'
import { AlertOctagon, AlertTriangle, CalendarCheck, CalendarClock, Flame, Globe, Hourglass, ListFilter, RefreshCw } from 'lucide-react'
import { dateLocale } from '../i18n/dateLocale.js'
import { api, formatDateOnly, localDayKey } from '../api/client'
import { navigateTo } from '../utils/navigate.js'
import { copyText } from '../utils/copyText.js'
import { useT } from '../i18n/index.jsx'
import AlertBanner from '../components/ui/AlertBanner.jsx'
import PlanModal from '../components/RenewalPlanModal.jsx'   // Genel Bakış kartıyla ortak (2026-09-19)
import ForecastDomainsPanel from './ForecastDomainsPanel.jsx'   // alan adı bitişleri — ayrı seri (2026-09-22, F)
import StatusBlock from '../components/ui/StatusBlock.jsx'
import MonthCalendar from '../components/ui/MonthCalendar.jsx'
import MonitorStatsBar from '../components/MonitorStatsBar.jsx'
import CollapsibleSection from '../components/ui/CollapsibleSection.jsx'
import SegmentedControl from '../components/ui/SegmentedControl.jsx'
import PaginationBar from '../components/ui/PaginationBar.jsx'
import { useToast } from '../components/ui/Toast.jsx'
import { useIsMobile } from '../hooks/use-mobile.js'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { useUrlQuerySync, readUrlParam } from '../hooks/useUrlQuerySync.js'
import { usePagination } from '../hooks/usePagination.js'
import { buildIcs, downloadIcs } from '../utils/ics.js'
import { csvRows as csvBody } from '../utils/csv.js'
import { downloadCsv } from '../utils/csvExport.js'
import {
  EMPTY_FILTERS, filtersToParams, paramsToFilters, applyFilters, computeKpis, batches, upcoming, nextExpiry, expiryKey,
  icsEvents, csvRows, todayKey, dayDiff,
} from './forecastModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import ForecastHeader from './forecast/ForecastHeader.jsx'
import ForecastHorizon from './forecast/ForecastHorizon.jsx'
import ForecastToolbar, { SORT_KEYS } from './forecast/ForecastToolbar.jsx'
import ForecastList from './forecast/ForecastList.jsx'
import ForecastDaySheet from './forecast/ForecastDaySheet.jsx'
import ForecastInsights from './forecast/ForecastInsights.jsx'
import { TILE_KEYS, matchesTile, calTone, sortRows } from './forecast/forecastUi.jsx'
import { useElementWidthState } from '../hooks/useElementWidth.js'

/**
 * Sertifika Takvimi / Vade Takvimi (Expiry Forecast) — 2026-09-27 shadcn + mobil web yeniden tasarımı.
 *
 * Yapı: başlık (marka, ortam, veri damgası, Tazele, Dışa aktar menüsü) → KPI kutucukları (MonitorStatsBar, süzgeç:
 * `f_urg`) → süzgeç çubuğu (takım/UG/kritiklik/grup `f_*`, arama `f_q`, plan durumu `f_plan`, sıralama, görünüm
 * `f_view`) → vade ufku grafiği (90/180/365, çubuk → dönem süzgeci) → TAKVİM (ui/MonthCalendar; gün → yan panel
 * `f_day`) ya da LİSTE (md+ tablo, telefonda kart; sayfalı) → İçgörüler (takım tablosu, veren, paylaşılan, zamanında)
 * → Alan adı bitişleri (katlanır). Yenileme planı ortak RenewalPlanModal; ICS/CSV içerikleri değişmedi.
 *
 * Miras (2026-09-12 zenginleştirme, 14 madde): #1 dolmuş + erişilemeyen görünür · #2 süzgeç (URL f_*) · #3 renew-by
 * + lead · #4 tazelik, ortam, hata bandı · #5 alarm eşikleri · #6 eylemler · #7 takıma göre · #8 toplu iş + kapsama
 * + veren · #9 ICS/CSV/bağlantı/yazdır · #10 zamanında oranı · #11 takvim + tatil/hafta sonu · #12 boş durumlar ·
 * #14 tek uç (/api/forecast).
 */
const LIST_RANGES = [30, 60, 90, 180, 365]
const PLAN_STATES = ['planned', 'unplanned']
const TILE_ICON = { overdue: AlertOctagon, critical: Flame, high: Hourglass, warning: AlertTriangle, late: CalendarClock, planned: CalendarCheck }
const TILE_TONE = { overdue: 'expired', critical: 'critical', high: 'high', warning: 'warning', late: 'certissue', planned: 'valid' }

function ForecastSkeleton({ label }) {
  return (
    <div aria-busy="true" className="flex min-w-0 flex-col gap-3">
      <span role="status" className="sr-only">{label}</span>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
        {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-24 rounded-lg" />)}
      </div>
      <Skeleton className="h-9 w-full sm:w-2/3" />
      <Skeleton className="h-52 rounded-xl" />
      <Skeleton className="h-80 rounded-xl" />
    </div>
  )
}

/** Panel açıklaması taze veriyle yeniden kurulur (plan penceresi kapanınca panel geri gelir, rozetler güncel olsun). */
function freshen(desc, certs, dayCerts) {
  if (!desc) return null
  if (desc.key) return { key: desc.key, certs: dayCerts(desc.key) }
  return { title: desc.title, certs: desc.certs.map((c) => certs.find((x) => x.domain === c.domain) ?? c) }
}

// ── Sayfa ─────────────────────────────────────────────────────────────────────────────────────────
/** Faset süzgeçleri (takım/UG/kritiklik/grup + arama + plan durumu) — KPI, içgörüler, takvim/liste ve plan sonrası gün
 *  paneli AYNI hattı kullanır. */
function facetCerts(all, filters, search, plan) {
  const q = (search || '').trim().toLowerCase()
  return applyFilters(all, filters).filter((c) => {
    if (q && !((c.domain || '').toLowerCase().includes(q) || (c.team_name || '').toLowerCase().includes(q) || (c.issuer_cn || '').toLowerCase().includes(q))) return false
    if (plan === 'planned') return c.renewal_plan_state === 'planned'
    if (plan === 'unplanned') return c.renewal_plan_state !== 'planned' && c.renewal_plan_state !== 'done'
    return true
  })
}

export default function ExpiryForecastPage({ onSelectDomain }) {
  const t = useT(); const toast = useToast()
  const locale = dateLocale()
  const isMobile = useIsMobile()
  // Liste kartları: telefonda ya da kap dar olduğunda (tablette kenar çubuğu açıkken içerik ~440 px) — tablo yerine
  const [listWidth, listRef] = useElementWidthState()
  const listCards = isMobile || (listWidth > 0 && listWidth < 640)
  const [data, setData] = useState(null)
  const [loadError, setLoadError] = useState(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [filters, setFilters] = useState(() => paramsToFilters(readUrlParam))
  const [search, setSearch] = useState(() => readUrlParam('f_q', ''))
  const [plan, setPlan] = useState(() => { const v = readUrlParam('f_plan', ''); return PLAN_STATES.includes(v) ? v : '' })
  const [tile, setTile] = useState(() => { const v = readUrlParam('f_urg', ''); return TILE_KEYS.includes(v) ? v : null })
  const [view, setView] = useState(() => (readUrlParam('f_view', '') === 'list' ? 'list' : 'calendar'))
  const [listRange, setListRange] = useState(30)
  const [horizonRange, setHorizonRange] = useState(90)
  const [bucket, setBucket] = useState(null)          // { from, to, label } — ufuk çubuğu süzgeci
  const [sort, setSort] = useState({ key: 'urgency', dir: 'asc' })
  const [day, setDay] = useState(null)                // { key?, title?, certs } — gün / takım-kova paneli
  const [planRow, setPlanRow] = useState(null)
  const [resumeDay, setResumeDay] = useState(null)    // plan penceresi kapanınca geri gelecek panel
  // Kontrol-et meşgul KÜMESİ (useRunningChecks deseni): tek yuvada A sürerken B'ye basınca A'nın göstergesi sönüyor,
  // önce biten diğerinin kilidini de açıyordu. Her kontrol yalnız KENDİ alan adını ekler/siler.
  const [busyDomains, setBusyDomains] = useState(() => new Set())
  const [domainsOpen, setDomainsOpen] = useState(true)
  const deepDay = useRef(readUrlParam('f_day', null))  // derin bağlantı: veri gelince gün paneli açılır
  const today = todayKey()

  const load = useCallback(async (manual = false) => {
    if (manual === true) setRefreshing(true)
    try {
      const r = await api.getForecast()
      if (r?.success && r.data) { setData(r.data); setLoadError(null) } else setLoadError(r?.error || t('forecast.loadError'))
    } catch (e) { setLoadError(e?.message || t('forecast.loadError')) }
    finally { setLoading(false); setRefreshing(false) }
  }, [t])
  useVisibleInterval(load, 300_000, true)   // #4: 5 dk'da bir görünürken tazelenir
  useUrlQuerySync({
    ...filtersToParams(filters), f_q: search.trim() || null, f_plan: plan || null, f_urg: tile,
    f_view: view === 'list' ? 'list' : null, f_day: day?.key ?? null,
  })

  const th = useMemo(() => data?.thresholds || { warning: 30, high: 15, critical: 7 }, [data])
  const allCerts = useMemo(() => data?.certs || [], [data])
  // Faset süzgeçleri (takım/UG/kritiklik/grup + arama + plan durumu) → KPI, içgörüler ve takvim/liste tabanı
  const certs = useMemo(() => facetCerts(allCerts, filters, search, plan), [allCerts, filters, search, plan])
  const kpi = useMemo(() => computeKpis(certs, th, today), [certs, th, today])
  const doneCount = useMemo(() => certs.filter((c) => c.renewal_plan_state === 'done').length, [certs])
  // Kutucuk süzgeci (aciliyet) → ufuk, takvim ve liste
  const scoped = useMemo(() => (tile ? certs.filter((c) => matchesTile(c, tile, th, today)) : certs), [certs, tile, th, today])
  const listAll = useMemo(() => upcoming(scoped, th, listRange, today), [scoped, th, listRange, today])
  const list = useMemo(() => {
    const rows = bucket ? listAll.filter((r) => r.expiry_key && r.expiry_key >= bucket.from && r.expiry_key <= bucket.to) : listAll
    return sortRows(rows, sort)
  }, [listAll, bucket, sort])
  // Yaklaşan bitişler (2026-09-26): standart sayfalama; aralık/süzgeç değişince 1. sayfa.
  const listPager = usePagination(list, { listKey: 'forecast-list', preset: 'panel', resetDeps: [listRange, filters, search, plan, tile, bucket, sort] })
  const batchList = useMemo(() => batches(scoped, th, listRange, today), [scoped, th, listRange, today])
  const next = useMemo(() => nextExpiry(certs, today), [certs, today])
  const teamOpts = useMemo(() => [...new Map(allCerts.filter((c) => c.team_id != null).map((c) => [String(c.team_id), c.team_name || `#${c.team_id}`])).entries()].map(([value, label]) => ({ value, label })), [allCerts])
  const groupOpts = useMemo(() => [...new Set(allCerts.map((c) => c.group_name).filter(Boolean))].sort().map((g) => ({ value: g, label: g })), [allCerts])
  // Yenileme geçmişi sayfa süzgecini izler (ISSUE-010): olaylar süzülen alanlara indirgenir, oran/aylar yeniden sayılır.
  const renewals = useMemo(() => {
    const raw = data?.renewals || { on_time: 0, late: 0, months: [], events: [] }
    if (!Object.values(filters).some(Boolean) && !search.trim() && !plan) return raw
    const allow = new Set(certs.map((c) => c.domain))
    const events = (raw.events || []).filter((e) => allow.has(e.domain))
    const months = {}; let on_time = 0, late = 0
    for (const e of events) {
      e.on_time ? on_time++ : late++
      const m = (localDayKey(e.renewed_at) || '').slice(0, 7)
      ;(months[m] ||= { month: m, on_time: 0, late: 0 })[e.on_time ? 'on_time' : 'late']++
    }
    return { ...raw, on_time, late, events, months: Object.values(months).sort((a, b) => a.month.localeCompare(b.month)) }
  }, [data, certs, filters, search, plan])

  // Takvim: bitiş günü (sertifika), planlı yenileme günü, alan adı (registrar) bitişi — 12 ay (ISSUE-013)
  const yearList = useMemo(() => upcoming(scoped, th, 365, today), [scoped, th, today])
  const dayCerts = useCallback((key) => {
    const seen = new Set(); const out = []
    for (const c of scoped) {
      const hit = expiryKey(c) === key || (c.renewal_plan_state === 'planned' && c.renewal_planned_at === key)
      if (hit && !seen.has(c.domain)) { seen.add(c.domain); out.push(c) }
    }
    return out.sort((a, b) => (a.days_remaining ?? 9999) - (b.days_remaining ?? 9999))
  }, [scoped])
  const monthEvents = useMemo(() => [
    ...yearList.filter((r) => r.expiry_key).map((r) => ({
      date: r.expiry_key, label: r.domain, tone: calTone(r.cls),
      title: `${r.domain} · ${t('forecast.csvExpiry')} ${formatDateOnly(r.expiry_key)}${r.renew_by_key ? ` · ${t('forecast.renewBy')} ${formatDateOnly(r.renew_by_key)}` : ''}`,
      onClick: () => onSelectDomain?.(r.domain),
    })),
    // Planlı yenileme günü de olay (ISSUE-008)
    ...scoped.filter((c) => c.renewal_plan_state === 'planned' && c.renewal_planned_at).map((c) => ({
      date: c.renewal_planned_at, label: c.domain, tone: 'ok', icon: CalendarCheck,
      title: `${c.domain} · ${t('forecast.hmPlanned', formatDateOnly(c.renewal_planned_at))}`, onClick: () => onSelectDomain?.(c.domain),
    })),
    // Alan adı (registrar) bitişleri de takvimde — ayrı simge (2026-09-22, F)
    ...(data?.domains || []).filter((d) => d.expiry_date).map((d) => ({
      date: String(d.expiry_date).slice(0, 10), label: d.domain, icon: Globe,
      title: `${d.domain} · ${t('forecast.domTitle')} · ${formatDateOnly(String(d.expiry_date).slice(0, 10))}`,
      tone: d.days_remaining != null && d.days_remaining <= (d.critical_days ?? 7) ? 'bad' : d.days_remaining != null && d.days_remaining <= (d.warning_days ?? 30) ? 'warn' : 'info',
      onClick: () => navigateTo('domain', { monitor: d.id }),
    })),
  ], [yearList, scoped, data, onSelectDomain, t])

  // Derin bağlantı `?f_day=YYYY-MM-DD`: veri gelince o günün paneli açılır (bir kez).
  useEffect(() => {
    if (!data || !deepDay.current) return
    const key = deepDay.current; deepDay.current = null
    if (/^\d{4}-\d{2}-\d{2}$/.test(key)) setDay({ key, certs: dayCerts(key) })
  }, [data, dayCerts])

  const setFilter = (patch) => setFilters((f) => ({ ...f, ...patch }))
  const clearAll = () => { setFilters({ ...EMPTY_FILTERS }); setSearch(''); setPlan(''); setTile(null); setBucket(null) }
  const openDay = (key) => setDay({ key, certs: dayCerts(key) })
  const pickBucket = (b) => {
    setBucket(b)
    setView('list')
    const need = dayDiff(today, b.to) + 1
    if (need > listRange) setListRange(LIST_RANGES.find((r) => r >= need) ?? 365)
  }
  const toggleSort = (key) => setSort((s) => (s.key === key ? { key, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: 'asc' }))

  async function checkNow(domain) {
    setBusyDomains((prev) => { const next = new Set(prev); next.add(domain); return next })
    try { const r = await api.refreshCertificateHealth(domain); if (r?.success) { toast.success(t('inv.checkNowOk', domain)); load() } else toast.error(r?.error || t('inv.checkNowErr')) }
    catch (e) { toast.error(e?.message || t('inv.checkNowErr')) } finally { setBusyDomains((prev) => { const next = new Set(prev); next.delete(domain); return next }) }
  }
  /** Planla: panel açıksa kapatılır (iç içe katman tuzağı) ve pencere kapanınca taze veriyle geri gelir. */
  function openPlan(row) {
    if (day) { setResumeDay(day); setDay(null) }
    setPlanRow({ ...row, expiry_key: row.expiry_key ?? expiryKey(row), renew_by_key: row.renew_by_key ?? (row.renew_by ? localDayKey(row.renew_by) : null) })
  }
  function closePlan(nextData) {
    setPlanRow(null)
    if (resumeDay) {
      // Aynı süzgeç hattı (2026-10-09, hata düzeltmesi): eskiden HAM liste kullanılıyordu → plan sonrası geri açılan gün
      // paneli kullanıcının takım/arama/plan süzgeçlerinin DIŞINDAKİ sertifikaları da listeliyordu.
      const base = nextData?.certs ? facetCerts(nextData.certs, filters, search, plan) : certs
      const fresh = tile ? base.filter((c) => matchesTile(c, tile, th, today)) : base
      const byKey = (key) => fresh.filter((c) => expiryKey(c) === key || (c.renewal_plan_state === 'planned' && c.renewal_planned_at === key))
      setDay(freshen(resumeDay, fresh, byKey))
      setResumeDay(null)
    }
  }
  function applyPlan(p) {
    // Yeni veri güncel `data`dan türetilir (setData güncelleyicisi tembel koşabilir; panel taze rozetle geri gelsin)
    const nextData = data ? { ...data, certs: data.certs.map((c) => c.domain === p.domain ? { ...c, renewal_planned_at: p.renewal_planned_at, renewal_planned_by: p.renewal_planned_by, renewal_planned_note: p.renewal_planned_note, renewal_plan_state: p.renewal_planned_at ? 'planned' : 'none' } : c) } : null
    if (nextData) setData(nextData)
    closePlan(nextData)
  }
  function exportIcs() { downloadIcs('renewal-plan.ics', buildIcs(icsEvents(list, t), { calName: t('forecast.icsCal') })) }
  function exportCsv() {
    try {
      // BOM + CRLF; kaçış + formül nötrleme utils/csv.js'te. İndirme ortak (utils/csvExport — öneri 29; dosya aynı).
      downloadCsv(`renewal-plan-${today}.csv`, '﻿' + csvBody(csvRows(list, t)))
    } catch { /* jsdom */ }
  }
  async function copyLink() { if (await copyText(window.location.href)) toast.success(t('inv.copied')); else toast.error(t('inv.copyFailed')) }
  function pageLink(params) {
    const u = new URL(window.location.href); u.search = ''
    u.searchParams.set('tab', 'forecast')
    for (const [k, v] of Object.entries(params)) u.searchParams.set(k, v)
    return u.toString()
  }
  async function copyRowLink(domain) { if (await copyText(pageLink({ f_view: 'list', f_q: domain }))) toast.success(t('forecast.rowLinkCopied', domain)); else toast.error(t('inv.copyFailed')) }
  async function copyDayLink(key) { if (await copyText(pageLink({ f_day: key }))) toast.success(t('inv.copied')); else toast.error(t('inv.copyFailed')) }

  const tiles = useMemo(() => {
    const overdueN = kpi.overdue + kpi.unreachable
    const sub = {
      overdue: overdueN ? t('forecast.subOverdue', kpi.overdue, kpi.unreachable) : t('forecast.subOverdueOk'),
      critical: t('forecast.sub7'), high: t('forecast.sub14'), warning: t('forecast.sub30'),
      late: kpi.late ? t('forecast.tileSub.late') : t('forecast.subLateOk'), planned: t('forecast.tileSub.planned', doneCount),
    }
    const label = {
      overdue: t('forecast.tile.overdue'), critical: t('forecast.tile.critical', th.critical), high: t('forecast.tile.high', th.critical + 1, th.high),
      warning: t('forecast.tile.warning', th.high + 1, th.warning), late: t('forecast.tile.late'), planned: t('forecast.tile.planned'),
    }
    const value = { overdue: overdueN, critical: kpi.critical, high: kpi.high, warning: kpi.warning, late: kpi.late, planned: kpi.planned }
    return TILE_KEYS.map((key) => ({ key, Icon: TILE_ICON[key], cls: TILE_TONE[key], value: value[key], label: label[key], sub: sub[key], hint: t(`forecast.tileHint.${key}`) }))
  }, [kpi, th, doneCount, t])

  const optLabel = (opts, v) => opts.find((o) => String(o.value) === String(v))?.label ?? v
  const chips = [
    ...(tile ? [{ key: 'urg', label: tiles.find((x) => x.key === tile)?.label ?? tile, onRemove: () => setTile(null) }] : []),
    ...(bucket ? [{ key: 'bucket', label: t('forecast.horizonFilter', bucket.label), onRemove: () => setBucket(null) }] : []),
    ...(filters.team ? [{ key: 'team', label: `${t('inv.filterTeam')}: ${optLabel(teamOpts, filters.team)}`, onRemove: () => setFilter({ team: '' }) }] : []),
    ...(filters.ugTeam ? [{ key: 'ug', label: `${t('inv.filterUgTeam')}: ${optLabel(teamOpts, filters.ugTeam)}`, onRemove: () => setFilter({ ugTeam: '' }) }] : []),
    ...(filters.tier ? [{ key: 'tier', label: `${t('inv.filterTier')}: ${filters.tier === 'none' ? t('inv.tierNone') : `T${filters.tier}`}`, onRemove: () => setFilter({ tier: '' }) }] : []),
    ...(filters.group ? [{ key: 'group', label: `${t('inv.filterGroup')}: ${filters.group}`, onRemove: () => setFilter({ group: '' }) }] : []),
    ...(plan ? [{ key: 'plan', label: plan === 'planned' ? t('forecast.planPlanned') : t('forecast.planUnplanned'), onRemove: () => setPlan('') }] : []),
    ...(search.trim() ? [{ key: 'q', label: `“${search.trim()}”`, onRemove: () => setSearch('') }] : []),
  ]
  const hintNext = next ? t('forecast.nextExpiry', next.domain, next.days) : t('forecast.noneAhead')
  const legend = [
    { tone: 'bg-red-600', label: t('forecast.legCritical') }, { tone: 'bg-amber-500', label: t('forecast.legWarning') },
    { tone: 'bg-blue-600', label: t('forecast.legLater') }, { tone: 'bg-green-600', label: t('forecast.legendPlanned') },
  ]

  return (
    <div data-slot="forecast-page" className="flex min-w-0 flex-col gap-4">
      <ForecastHeader environment={data?.environment} dataAsOf={data?.data_as_of} refreshing={refreshing} onRefresh={() => load(true)}
        exportDisabled={!list.length} onExportIcs={exportIcs} onExportCsv={exportCsv} onCopyLink={copyLink} onPrint={() => window.print()} />

      {loadError && (
        <AlertBanner tone="danger" title={t('forecast.loadError')} role="alert"
          actions={<Button type="button" variant="outline" size="sm" onClick={() => load(true)} disabled={refreshing}><RefreshCw aria-hidden="true" />{t('forecast.retry')}</Button>}>
          {String(loadError)}{data ? ` · ${t('forecast.staleShown')}` : ''}
        </AlertBanner>
      )}
      {loading && !data && <ForecastSkeleton label={t('forecast.loading')} />}

      {data && (
        <>
          {/* ── KPI kutucukları = süzgeç (#1 overdue, #3 pencere, #5 eşikler) ── */}
          <div className="[&>[data-slot=stats-panel]]:mb-0 print:hidden">
            <MonitorStatsBar items={tiles} activeFilter={tile} onStatClick={(k) => setTile((cur) => (cur === k ? null : k))} />
          </div>
          <p className="-mt-2 text-xs text-muted-foreground" title={t('forecast.thresholdTip')}>
            {t('forecast.thresholdNote', th.critical, th.high, th.warning)} · {t('forecast.leadNote', data.lead_days?.default ?? 14)}
          </p>

          <ForecastToolbar isMobile={isMobile} filters={filters} onFilter={setFilter} teamOpts={teamOpts} groupOpts={groupOpts}
            search={search} onSearch={setSearch} plan={plan} onPlan={setPlan}
            showSort={view === 'list'} sortKey={SORT_KEYS.includes(sort.key) ? sort.key : 'urgency'} onSort={(k) => setSort({ key: k, dir: 'asc' })}
            view={view} onView={setView} chips={chips} onClearAll={clearAll} shown={certs.length} total={allCerts.length} />

          <ForecastHorizon certs={scoped} th={th} today={today} locale={locale} range={horizonRange} onRange={setHorizonRange} onBucket={pickBucket} />

          {view === 'calendar' ? (
            <section data-slot="fc-calendar" aria-label={t('forecast.secCalendar')} className="flex min-w-0 flex-col gap-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h2 className="m-0 text-base font-semibold">{t('forecast.secCalendar')}</h2>
                <ul aria-label={t('forecast.calLegend')} className="m-0 flex list-none flex-wrap items-center gap-x-3 gap-y-1 p-0 text-xs text-muted-foreground">
                  {legend.map((l) => <li key={l.label} className="inline-flex items-center gap-1.5"><span aria-hidden="true" className={`size-2 rounded-full ${l.tone}`} />{l.label}</li>)}
                  <li className="inline-flex items-center gap-1.5"><Globe aria-hidden="true" className="size-3" />{t('forecast.domTitle')}</li>
                </ul>
              </div>
              <MonthCalendar events={monthEvents} ariaLabel={t('forecast.calMonth')} dense onDayClick={openDay} />
              <p className="-mt-2 text-xs text-muted-foreground">{t('forecast.calHint')}</p>
            </section>
          ) : (
            <section ref={listRef} data-slot="fc-list" aria-label={t('forecast.secList')} className="flex min-w-0 flex-col gap-2">
              <div className="flex flex-wrap items-center gap-2">
                <h2 className="m-0 flex items-center gap-2 text-base font-semibold">{t('forecast.secList')} <Badge variant="secondary" className="tabular-nums">{t('forecast.certCount', list.length)}</Badge></h2>
                <SegmentedControl value={listRange} onChange={(v) => { setListRange(v); setBucket(null) }} ariaLabel={t('forecast.rangeLabel')} className="flex-wrap sm:ml-auto print:hidden"
                  options={LIST_RANGES.map((d) => ({ value: d, label: t('forecast.chartDays', d) }))} />
              </div>
              {batchList.length > 0 && (
                <div className="flex flex-wrap gap-1.5">
                  {batchList.map((b) => <Badge key={b.date} variant="warning" data-slot="fc-batch" className="max-w-full font-normal whitespace-normal" title={b.domains.join(', ')}>{t('forecast.batch', formatDateOnly(b.date), b.count, b.issuers.join(' / ') || '—')}</Badge>)}
                </div>
              )}
              {list.length === 0 ? (
                (tile || bucket || chips.length > 0)
                  ? <StatusBlock tone="neutral" icon={ListFilter} title={t('forecast.noneFiltered')} description={t('empty.hintFilter')} className="rounded-xl border border-dashed"
                      actions={<Button type="button" variant="outline" size="sm" onClick={clearAll}>{t('app.clearFilters')}</Button>} />
                  : <StatusBlock tone="success" title={t('forecast.noneInRange', listRange)} description={hintNext} className="rounded-xl border border-dashed"
                      actions={next && next.days >= listRange ? <Button type="button" variant="secondary" size="sm" onClick={() => setListRange(LIST_RANGES.find((r) => r > next.days) ?? 365)}>{t('forecast.widen', LIST_RANGES.find((r) => r > next.days) ?? 365)}</Button> : null} />
              ) : (
                <ForecastList rows={listPager.pageItems} cards={listCards} sort={sort} onSort={toggleSort} busyDomains={busyDomains}
                  onOpen={(d) => onSelectDomain?.(d)} onCheckNow={checkNow} onPlan={openPlan} onCopyLink={copyRowLink} />
              )}
              <PaginationBar {...listPager} />
            </section>
          )}

          <ForecastInsights certs={certs} th={th} today={today} range={listRange} renewals={renewals}
            teamFilter={filters.team} onTeamFilter={(v) => setFilter({ team: v })} onOpenBucket={(desc) => setDay(desc)} />

          {/* ── Alan adı (registrar) bitişleri — sertifika serisinden AYRI (2026-09-22, F) ── */}
          <CollapsibleSection open={domainsOpen} onOpenChange={setDomainsOpen} icon={Globe} label={t('forecast.domTitle')} hint={t('forecast.domainsHint')}
            toggleLabel={t('forecast.domTitle')} contentClassName="pt-3" data-slot="fc-domains-section">
            <ForecastDomainsPanel domains={data?.domains || []} t={t} heading={false} />
          </CollapsibleSection>
        </>
      )}

      {day && (
        <ForecastDaySheet day={day} th={th} isMobile={isMobile} busyDomains={busyDomains} onClose={() => setDay(null)}
          onOpen={(d) => onSelectDomain?.(d)} onPlan={openPlan} onCheckNow={checkNow} onCopyDayLink={copyDayLink} />
      )}
      {planRow && <PlanModal row={planRow} onClose={() => closePlan(null)} onSaved={applyPlan} onCleared={applyPlan} />}
    </div>
  )
}
