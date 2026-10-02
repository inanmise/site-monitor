import { useState, useEffect, useMemo, useCallback, lazy, Suspense } from 'react'
import {
  Users, LogIn, XCircle, ShieldAlert, UserCheck, UserX, Download, Link2, RefreshCw, ChevronDown, ChevronRight, Info, Check, BookOpen,
  Compass, Lock,
} from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { api, formatDateSec, formatDateOnly } from '../../../api/client'
import { useUrlQuerySync, readUrlParam } from '../../../hooks/useUrlQuerySync.js'
import { downloadCsv } from '../../../utils/csvExport.js'
import { useToast } from '../../ui/Toast.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import UserBadge from '../../ui/UserBadge.jsx'
import Sparkline from '../../ui/Sparkline.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import DateTimeField from '../../ui/DateTimeField.jsx'
import DateTimeRangePicker from '../../ui/DateTimeRangePicker.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import LoginHeatmap from '../LoginHeatmap'
import { SessionDetailModal, HeatCellModal, KpiDetailModal, TerminateModal, AckModal } from './UactModals.jsx'
import UserDirectoryModal from './UserDirectoryModal.jsx'
import EventListModal from './EventListModal.jsx'
import {
  EMPTY_FILTERS, filtersToParams, paramsToFilters, hasActiveFilter, rowMatches, tabLabel, unusedTabs, idleBand, loginStatus,
  relTime, splitDuration, failedTone, failedRatio, sparkFrom, deltaVsAvg, isOffHourCell, FLAG_KEYS, splitFlags, sortRows,
  sessionsCsv, loginStatusCsv, anomaliesCsv, usageCsv, teamBars, STATUS_ORDER, idHidden,
} from './uactModel.js'
import { MaskedValue } from './DirectoryParts.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import SimpleTooltip from '../../ui/SimpleTooltip.jsx'
import { FilterField } from '../ListToolbar.jsx'
import { TH, TH_NUM, TD, TD_NUM, MUTED_SM, DataTable, SortTh, SectionCard, KpiCard, Pill, FlagBadge, LinkButton } from '../HealthUi.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { ButtonGroup } from '@/components/shadcn/button-group'
import { Card } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/shadcn/dropdown-menu'
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

const LoginActivityChart = lazy(() => import('../LoginActivityChart.jsx'))

/** Düşük öncelikli sütunlar telefonda gizli (mobil-önce): md = 768 px+, lg = 1024 px+. */
const MD = 'hidden md:table-cell'
const LG = 'hidden lg:table-cell'

/** Boşta kalma noktası (eski `.uact-idle--*`): canlı yeşil halkalı, boşta amber, uzakta gri. */
const IDLE_DOT = {
  live: 'bg-green-500 shadow-[0_0_0_3px_rgba(34,197,94,.22)]', idle: 'bg-amber-500', away: 'bg-zinc-400', unknown: 'bg-zinc-400',
}

/**
 * Kullanıcı / Oturum paneli (2026-09-13 zenginleştirme, 14 madde): #1 sayfa kullanımı · #2 boşta/düşme · #3 anomali
 * onayı + kullanıcı zaman çizelgesi · #4 trend kartları · #5 giriş durumu pili + atıl hesap · #6 ısı haritası mesai-dışı
 * gölgesi + anomali işareti · #7 takım çubukları + hiç girmeyenler · #8 kaynak ilk/son/kullanıcı · #9 süzgeç/URL/CSV/bağlantı ·
 * #10 gerekçeli sonlandırma · #11 numaralı bölümler + boş durumlar · #12 tazelik damgası · #13 klavye/aria · #14 gizlilik.
 *
 * Veri SystemHealth'in 30 sn döngüsünden gelir (tek payload); burası yalnız türetir ve çizer.
 * Çizim shadcn (Card / Table / Badge / Button / DropdownMenu / Collapsible); ortak parçalar ../HealthUi.jsx.
 */
/** canAck (2026-09-19): anomali onayı/geri alma yalnız global admin / AUDIT — panel artık her kademeye görünür. */
export default function UserActivityPanel({ data, error, refreshing, onRefresh, isAdmin, globalAdmin, canAck = true, username, onTerminated }) {
  const t = useT()
  const toast = useToast()
  const [filters, setFilters] = useState(() => paramsToFilters(readUrlParam))
  useUrlQuerySync(filtersToParams(filters))
  const setF = (patch) => setFilters((f) => ({ ...f, ...patch }))

  const [sort, setSort] = useState({ col: 'idle_sec', dir: 'asc' })
  const [statusSort, setStatusSort] = useState('status')
  const [weekIdx, setWeekIdx] = useState(0)
  const [expRole, setExpRole] = useState(null)
  const [expTeam, setExpTeam] = useState(null)
  const [heatCell, setHeatCell] = useState(null)
  const [kpiDetail, setKpiDetail] = useState(null)
  const [sessionDetail, setSessionDetail] = useState(null)
  const [directory, setDirectory] = useState(null)     // Kullanıcı Dizini (2026-09-20): { view, tour }
  const [terminate, setTerminate] = useState(null)      // { username }
  const [ackTarget, setAckTarget] = useState(null)      // anomali satırı
  const [glossaryOpen, setGlossaryOpen] = useState(false)
  const [exportOpen, setExportOpen] = useState(false)
  const [busyUser, setBusyUser] = useState(null)
  const [ackBusy, setAckBusy] = useState(null)
  const [now, setNow] = useState(Date.now())
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), 30_000); return () => clearInterval(id) }, [])

  // ── Giriş trendi (esnek seri) ──
  const [trendDays, setTrendDays] = useState(7)
  const [trendDate, setTrendDate] = useState('')
  const [trendCustom, setTrendCustom] = useState(null)
  const [trendPreset, setTrendPreset] = useState(null)
  const [trendShowCustom, setTrendShowCustom] = useState(false)
  const [trendData, setTrendData] = useState(null)
  const [trendLoading, setTrendLoading] = useState(false)
  const loadTrend = useCallback(async () => {
    setTrendLoading(true)
    try {
      const iso = (d) => d.toISOString().slice(0, 19)
      let fromD, toD, gran
      if (trendPreset) { const hrs = trendPreset === '1h' ? 1 : 6; toD = new Date(); fromD = new Date(toD.getTime() - hrs * 3_600_000); gran = 'minute' }
      else if (trendCustom) { fromD = new Date(trendCustom.from + 'Z'); toD = new Date(trendCustom.to + 'Z'); const span = toD - fromD; gran = span <= 6 * 3_600_000 ? 'minute' : span <= 2 * 86_400_000 ? 'hour' : 'day' }
      else if (trendDate) { fromD = new Date(`${trendDate}T00:00:00`); toD = new Date(`${trendDate}T23:59:59`); gran = 'hour' }
      else if (trendDays === 1) { toD = new Date(); fromD = new Date(toD.getTime() - 86_400_000); gran = 'hour' }
      else { toD = new Date(); fromD = new Date(toD.getTime() - trendDays * 86_400_000); gran = 'day' }
      const res = await api.admin.getLoginSeries(iso(fromD), iso(toD), gran)
      if (res?.success) setTrendData(res.data)
    } catch { /* ağ hatası: eski seri kalır */ } finally { setTrendLoading(false) }
  }, [trendDays, trendDate, trendCustom, trendPreset])
  useEffect(() => { loadTrend() }, [loadTrend])
  // Özel aralık seçicisinin uçları KARARLI (2026-09-27 regresyon B1): panel 30 sn'de bir (`now`) yeniden çiziliyor;
  // her çizimde `new Date()` geçmek seçicinin taslağını sıfırlıyordu. Hook erken return'lerin (error/!data) ÜSTÜNDE.
  const trendPicker = useMemo(() => (trendShowCustom ? {
    from: trendCustom ? new Date(trendCustom.from + 'Z') : new Date(Date.now() - 7 * 86_400_000),
    to: trendCustom ? new Date(trendCustom.to + 'Z') : new Date(),
  } : null), [trendShowCustom, trendCustom])

  // ── Türetimler ──
  const ua = useMemo(() => data || {}, [data])
  const sum = ua.summary || {}
  // Kimlik izi (IP / konum / kuruluş / tarayıcı) bu görüntüleyici için sunucuda düşürüldü mü (2026-09-28c): yalnız global
  // yönetici + denetçi görür (kişinin kendi satırı hariç). Alan yoksa "Gizli" çizilir — boş / "—" değil.
  const idMasked = ua.identity_masked === true
  const is7d = filters.range === '7d'
  const activeAll = useMemo(() => ua.active_users || [], [ua])
  const activeSet = useMemo(() => new Set(activeAll.map((u) => String(u.username || '').toLowerCase())), [activeAll])
  /** Oturum detayı kaydı (QA ISSUE-002): çevrimiçi kullanıcıda yalnız active_users seçilince dizin alanları (oluşturulma,
   *  tur, kilit, ek takımlar, user_id) kayboluyordu → login_status TABAN, oturum alanları üstüne yazılır. */
  const detailRecord = useCallback((name) => {
    const key = String(name || '').toLowerCase()
    const dir = (ua.login_status || []).find((u) => String(u.username || '').toLowerCase() === key)
    const live = activeAll.find((u) => String(u.username || '').toLowerCase() === key)
    if (!dir && !live) return null
    return { ...(dir || {}), ...Object.fromEntries(Object.entries(live || {}).filter(([, v]) => v != null)) }
  }, [ua.login_status, activeAll])
  const active = useMemo(() => sortRows(activeAll.filter((u) => rowMatches(u, filters)), sort.col, sort.dir), [activeAll, filters, sort])
  const loginRows = useMemo(() => {
    const rows = (ua.login_status || []).filter((r) => rowMatches(r, filters)).map((r) => ({ ...r, status: loginStatus(r, activeSet, now) }))
    if (statusSort === 'status') rows.sort((a, b) => STATUS_ORDER.indexOf(a.status) - STATUS_ORDER.indexOf(b.status) || String(b.last_login_at || '').localeCompare(String(a.last_login_at || '')))
    else if (statusSort === 'failed') rows.sort((a, b) => (b.failed_since_login || 0) - (a.failed_since_login || 0))
    else rows.sort((a, b) => String(a.username).localeCompare(String(b.username)))
    return rows
  }, [ua.login_status, filters, activeSet, now, statusSort])
  const teamOptions = useMemo(() => {
    const m = new Map()
    for (const r of ua.role_team?.by_team || []) if (r.team_name) m.set(String(r.team_id), r.team_name)
    for (const r of ua.login_status || []) if (r.team_name && ![...m.values()].includes(r.team_name)) m.set(r.team_name, r.team_name)
    return [{ value: '', label: t('uact.filterAny') }, ...[...m.entries()].map(([v, l]) => ({ value: v, label: l }))]
  }, [ua, t])
  const roleOptions = useMemo(() => {
    const s = new Set((ua.login_status || []).map((r) => r.system_role).filter(Boolean))
    return [{ value: '', label: t('uact.filterAny') }, ...[...s].sort().map((r) => ({ value: r, label: r }))]
  }, [ua, t])
  const anomalies = ua.anomalies || {}
  const roleTeam = ua.role_team || {}
  const heatmaps = ua.heatmaps || []
  const usage = ua.usage || {}
  const office = ua.office_hours || { start: 8, end: 20 }
  const dayLabels = t('uact.weekdays').split(',')
  const winLabel = is7d ? t('uact.windowLabel7') : t('uact.windowLabel24')
  const pick = (k24, k7) => (is7d ? sum[k7] : sum[k24]) ?? 0
  const rel = (iso) => { const r = relTime(iso, now); return r ? t(`uact.rel.${r.unit}`, r.n) : '—' }
  const dur = (sec) => { const d = splitDuration(sec); return d.h > 0 ? `${d.h} ${t('chg.unitHour')} ${String(d.m).padStart(2, '0')} ${t('chg.unitMin')}` : `${d.m} ${t('chg.unitMin')}` }

  // ── Eylemler ──
  async function doTerminate(target, reason) {
    setBusyUser(target)
    try {
      const res = await api.admin.terminateUserSession(target, reason)
      if (res?.success) { toast.success(t('uact.terminated', target)); setTerminate(null); setSessionDetail(null); (onTerminated || onRefresh)?.() }
      else toast.error(res?.error || t('uact.loadError'))
    } catch (e) { toast.error(e?.message || t('uact.loadError')) } finally { setBusyUser(null) }
  }
  async function doAck(row, acknowledge, note) {
    if (!row?.id) return
    setAckBusy(row.id)
    try {
      const res = await api.admin.ackAnomaly(row.id, acknowledge, note)
      if (res?.success) { toast.success(acknowledge ? t('uact.acked') : t('uact.unacked')); setAckTarget(null); onRefresh?.() }
      else toast.error(res?.error || t('uact.loadError'))
    } catch (e) { toast.error(e?.message || t('uact.loadError')) } finally { setAckBusy(null) }
  }
  function download(name, csv) {
    downloadCsv(name, '﻿' + csv)   // BOM'lu UTF-8; ortak indirme (utils/csvExport — öneri 29, dosya aynı)
    setExportOpen(false)
  }
  async function copyLink() {
    try {
      const u = new URL(window.location.href); u.searchParams.set('sec', 'users')   // alıcı bölümü açık görsün (QA ISSUE-001)
      await navigator.clipboard.writeText(u.toString()); toast.success(t('uact.copied'))
    } catch { toast.error(t('uact.copyFailed')) }
  }
  if (error) return <StatusBlock tone="danger" icon={ShieldAlert} title={t('uact.loadError')} actions={<Button type="button" variant="secondary" size="sm" onClick={onRefresh}>{t('uact.refresh')}</Button>} />
  if (!data) return <div className="p-2 text-xs text-muted-foreground">…</div>

  const kpi = (key, Icon, val, label, sub, tone, onClick, spark, delta) => (
    <KpiCard key={key} kpiKey={key} icon={Icon} value={val} label={label} sub={sub} tone={tone} onClick={onClick} title={t('uact.detailHint')}
      top={spark && spark.some(Boolean) ? <Sparkline data={spark} width={72} height={22} label={label} className="size-auto h-[22px] w-[72px]" /> : null}
      delta={delta != null && delta !== 0 && (
        <span className={cn('ml-2 align-middle text-[11px] font-bold tracking-normal', delta > 0 ? 'text-destructive' : 'text-success')} title={t('uact.deltaVsAvg')}>
          {delta > 0 ? '▲' : '▼'} {Math.abs(delta)}%
        </span>
      )} />
  )
  const sortTh = (col, label, numeric, className) => (
    <SortTh label={label} numeric={numeric} className={className} active={sort.col === col} dir={sort.dir}
      onSort={() => setSort((s) => s.col === col ? { col, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { col, dir: col === 'username' ? 'asc' : 'asc' })} />
  )
  const note = (text) => <span className="ml-auto text-[0.78em] text-muted-foreground">{text}</span>
  const subTitle = 'mt-2 mb-1 text-[11px] font-bold tracking-widest text-muted-foreground uppercase'
  const rangeBtn = (active, onClick, label, key) => (
    <Button key={key} type="button" size="xs" variant={active ? 'default' : 'outline'} aria-pressed={active} onClick={onClick}>{label}</Button>
  )
  const unusedList = unusedTabs(usage.pages)
  const failedT = failedTone(pick('failed_24h', 'failed_7d'), pick('logins_24h', 'logins_7d'))
  const outcomeText = (o) => o === 'SUCCESS' ? <span className="text-success">{o}</span> : <span className="text-destructive">{o || '—'}</span>
  const teamBarRow = (open, onToggle, head, body, key) => (
    <Collapsible key={key} open={open} onOpenChange={onToggle} className="border-b border-dashed border-border">
      <CollapsibleTrigger className="flex w-full cursor-pointer items-center gap-2.5 px-1 py-[7px] text-left hover:bg-muted/40">
        {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        {head}
      </CollapsibleTrigger>
      <CollapsibleContent className="flex flex-col gap-1 px-6 pb-2">{body}</CollapsibleContent>
    </Collapsible>
  )
  const bar = (pct, wide) => (
    // genişlik CSS özel değişkeniyle (--w): progress-guard kapısı inline width istemez
    <span className={cn('relative inline-block h-2.5 rounded-full bg-muted align-middle', wide ? 'min-w-[60px] flex-[1_1_120px]' : 'w-24 flex-[0_0_96px]')}
      style={{ '--w': `${pct}%` }}>
      <span className="absolute inset-y-0 left-0 w-(--w) max-w-full rounded-full bg-primary" />
    </span>
  )

  return (
    <div className="flex flex-col gap-4">
      {/* Hero */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-[3px]">
          <span className="text-[11px] font-semibold tracking-[.12em] text-muted-foreground uppercase">{t('uact.section')}</span>
          <span className="text-xl font-semibold tracking-tight">{t('uact.heroTitle')}</span>
          <span className="text-[11.5px] text-muted-foreground tabular-nums">{t('uact.dataAsOf', ua.generated_at ? formatDateSec(ua.generated_at) : '—')} · {winLabel}</span>
        </div>
        <div className="flex items-center gap-2">
          <Button type="button" variant="outline" data-hero-live="" onClick={() => setDirectory({ view: 'all' })} title={t('uact.activeCardHint')}>
            <span aria-hidden="true" className="size-[9px] rounded-full bg-green-400 shadow-[0_0_0_3px_rgba(74,222,128,.25)]" />{sum.active_count ?? 0} {t('uact.activeNow')}
          </Button>
          <SimpleTooltip content={t('uact.refresh')}>
            <Button type="button" variant="outline" size="icon" onClick={onRefresh} disabled={refreshing} aria-busy={refreshing || undefined} aria-label={t('uact.refresh')}>
              <RefreshCw size={14} className={cn(refreshing && 'animate-spin motion-reduce:animate-none')} />
            </Button>
          </SimpleTooltip>
        </div>
      </div>

      {/* Süzgeç çubuğu (#9) */}
      <Card data-testid="uact-filters" className="flex-row flex-wrap items-end gap-2.5 px-3 py-2.5 shadow-none">
        <FilterField label={t('uact.filterTeam')} className="w-full sm:w-auto sm:min-w-[160px]"><SearchableSelect value={filters.team} onChange={(v) => setF({ team: v })} options={teamOptions} searchThreshold={4} ariaLabel={t('uact.filterTeam')} /></FilterField>
        <FilterField label={t('uact.filterRole')} className="w-full sm:w-auto sm:min-w-[160px]"><SearchableSelect value={filters.role} onChange={(v) => setF({ role: v })} options={roleOptions} ariaLabel={t('uact.filterRole')} /></FilterField>
        <SegmentedControl value={is7d ? '7d' : '24h'} onChange={(v) => setF({ range: v })} ariaLabel={t('uact.filterRange')}
          options={[{ value: '24h', label: t('uact.range24h') }, { value: '7d', label: t('uact.range7dShort') }]} />
        {hasActiveFilter(filters) && <Button type="button" variant="secondary" size="sm" onClick={() => setFilters({ ...EMPTY_FILTERS })}>{t('uact.filterClear')}</Button>}
        <div className="flex-1" />
        <DropdownMenu modal={false} open={exportOpen} onOpenChange={setExportOpen}>
          <DropdownMenuTrigger asChild>
            <Button type="button" variant="secondary" size="sm"><Download size={13} /> {t('uact.export')}</Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="z-(--z-menu)">
            <DropdownMenuItem onSelect={() => download('sessions.csv', sessionsCsv(active, t, { masked: idMasked }))}>{t('uact.exportSessions')}</DropdownMenuItem>
            <DropdownMenuItem onSelect={() => download('login-status.csv', loginStatusCsv(loginRows, t))}>{t('uact.exportStatus')}</DropdownMenuItem>
            <DropdownMenuItem onSelect={() => download('anomalies.csv', anomaliesCsv(anomalies.recent || [], t, { masked: idMasked }))}>{t('uact.exportAnomalies')}</DropdownMenuItem>
            <DropdownMenuItem onSelect={() => download('page-usage.csv', usageCsv(usage.pages || [], t))}>{t('uact.exportUsage')}</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
        <Button type="button" variant="secondary" size="sm" onClick={copyLink}><Link2 size={13} /> {t('uact.copyLink')}</Button>
      </Card>

      {/* 01 KPI trend kartları (#4, #5) */}
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(150px,100%),1fr))] gap-3">
        {/* Ürün turu (2026-09-13): tamamladı / kapattı / hiç görmedi */}
        {kpi('tour', Compass, sum.tour?.completed ?? 0, t('uact.tourKpi'), t('uact.tourKpiSub', sum.tour?.dismissed ?? 0, sum.tour?.none ?? 0), undefined, () => setDirectory({ tour: 'completed' }))}
        {kpi('active', Users, sum.active_count ?? 0, t('uact.activeNow'), t('uact.kpiLive'), 'ok', () => setDirectory({ view: 'all' }))}
        {kpi('logins', LogIn, pick('logins_24h', 'logins_7d'), t('uact.logins'), t('uact.kpi7d', sum.logins_7d ?? 0), undefined, () => setKpiDetail({ kind: 'logins', title: t('uact.logins') }), sparkFrom(ua.series, 'success'), deltaVsAvg(ua.series, 'success'))}
        {kpi('failed', XCircle, pick('failed_24h', 'failed_7d'), t('uact.failedLbl'), t('uact.failedRatio', failedRatio(pick('failed_24h', 'failed_7d'), pick('logins_24h', 'logins_7d'))), failedT === 'neutral' ? undefined : failedT, () => setKpiDetail({ kind: 'failed', title: t('uact.failedLbl') }), sparkFrom(ua.series, 'failed'), deltaVsAvg(ua.series, 'failed'))}
        {kpi('anom', ShieldAlert, pick('anomalies_24h', 'anomalies_7d'), t('uact.anomalies'), t('uact.unackedSub', anomalies.unacked_recent ?? 0), (anomalies.unacked_recent ?? 0) > 0 ? 'danger' : undefined, () => setKpiDetail({ kind: 'anomalies', title: t('uact.anomalies') }))}
        {kpi('uniq', UserCheck, pick('unique_users_24h', 'unique_users_7d'), t('uact.uniqueUsers'), t('uact.kpi7d', sum.unique_users_7d ?? 0), undefined, () => setKpiDetail({ kind: 'unique_users', title: t('uact.uniqueUsers') }))}
        {kpi('dormant', UserX, sum.dormant_30d ?? 0, t('uact.dormant'), t('uact.kpiDormantSub', sum.never_logged_in ?? 0, sum.total_users ?? 0), (sum.dormant_30d ?? 0) > 0 ? 'warn' : undefined, () => setKpiDetail({ kind: 'dormant', title: t('uact.dormant') }))}
      </div>

      {/* 02 Trend */}
      <SectionCard num="02" title={t('uact.trendTitle')}>
        <div className="flex flex-wrap items-center gap-1.5">
          {['1h', '6h'].map((h) => rangeBtn(trendPreset === h, () => { setTrendDate(''); setTrendCustom(null); setTrendShowCustom(false); setTrendPreset(h) }, t(`uact.range${h}`), h))}
          {[1, 7, 30].map((d) => rangeBtn(!trendDate && !trendCustom && !trendPreset && trendDays === d, () => { setTrendDate(''); setTrendCustom(null); setTrendShowCustom(false); setTrendPreset(null); setTrendDays(d) }, t(`uact.range${d}d`), d))}
          <span className="text-xs text-muted-foreground">·</span>
          <DateTimeField dateOnly clearable className="dtf-inline" value={trendDate} onChange={(v) => { setTrendCustom(null); setTrendShowCustom(false); setTrendPreset(null); setTrendDate(v) }} placeholder={t('uact.gotoDay')} />
          {rangeBtn(!!trendCustom, () => setTrendShowCustom((s) => !s), t('chart.custom'), 'custom')}
          {trendLoading && <Spinner size={14} inline decorative />}
        </div>
        {trendPicker && (
          <div className="my-2">
            <DateTimeRangePicker from={trendPicker.from} to={trendPicker.to}
              onApply={(f, to) => { setTrendDate(''); setTrendDays(7); setTrendPreset(null); setTrendCustom({ from: f.toISOString().slice(0, 19), to: to.toISOString().slice(0, 19) }) }} />
          </div>
        )}
        {(trendData?.buckets || []).length === 0
          ? <StatusBlock tone="neutral" icon={LogIn} title={t('uact.noLoginsRange')} />
          : <Suspense fallback={<div className="p-2 text-xs text-muted-foreground">…</div>}><LoginActivityChart buckets={trendData.buckets} gran={trendData.granularity || 'day'} /></Suspense>}
      </SectionCard>

      {/* 03 Isı haritası (#6) */}
      <SectionCard num="03" title={t('uact.peakTitle')} extra={heatmaps.length > 0 && (
        <ButtonGroup className="ml-auto" aria-label={t('uact.weekNav')}>
          <Button type="button" variant="outline" size="xs" disabled={weekIdx >= heatmaps.length - 1} onClick={() => setWeekIdx((w) => Math.min(heatmaps.length - 1, w + 1))}>← {t('uact.prevWeek')}</Button>
          <Button type="button" variant="secondary" size="xs" aria-current="true" className="pointer-events-none">{weekIdx === 0 ? t('uact.weekThis') : t(`uact.weekPrev${weekIdx}`)}</Button>
          <Button type="button" variant="outline" size="xs" disabled={weekIdx <= 0} onClick={() => setWeekIdx((w) => Math.max(0, w - 1))}>{t('uact.nextWeek')} →</Button>
        </ButtonGroup>
      )}>
        {heatmaps.length > 0 ? (() => {
          const wi = Math.min(weekIdx, heatmaps.length - 1); const hm = heatmaps[wi]
          const range = hm.from && hm.to ? `${formatDateOnly(hm.from)} – ${formatDateOnly(new Date(new Date(hm.to + 'Z').getTime() - 86_400_000).toISOString())}` : ''
          const marks = Array.from({ length: 7 }, (_, r) => Array.from({ length: 24 }, (__, c) => ((hm.cells || {})[`${r}-${c}`] || []).some((x) => x.outcome !== 'SUCCESS') ? 1 : 0))
          return (
            <>
              <LoginHeatmap matrix={hm.matrix || []} failed={hm.failed || []} max={hm.max || 0} dayLabels={dayLabels} title={weekIdx === 0 ? t('uact.weekThis') : t(`uact.weekPrev${weekIdx}`)} hourLabel={range}
                todayDow={hm.today_dow ?? -1} rowTotals={hm.row_totals || []} colTotals={hm.col_totals || []} total={hm.total || 0}
                isOff={(r, c) => isOffHourCell(r, c, office)} marks={marks}
                onCellClick={(weekday, hour) => setHeatCell({ weekday, hour, cells: hm.cells || {}, label: range })} />
              <div className="mt-2 flex flex-wrap items-center gap-3.5 text-[0.78em] text-muted-foreground">
                <span className="bg-[length:12px_12px] bg-left bg-no-repeat pl-4 [background-image:repeating-linear-gradient(135deg,transparent_0_3px,rgba(148,163,184,.55)_3px_5px)]">{t('uact.offHoursLegend', office.start, office.end)}</span>
                <span className="inline-flex items-center gap-1.5"><span aria-hidden="true" className="size-[7px] rounded-full bg-red-600" />{t('uact.failedLegend')}</span>
                <span className={MUTED_SM}>{t('uact.heatHint')}</span>
              </div>
            </>
          )
        })() : <StatusBlock tone="neutral" icon={LogIn} title={t('uact.noLoginsRange')} />}
      </SectionCard>

      {/* 04 Aktif oturumlar (#2, #10, #13) */}
      <SectionCard num="04" title={`${t('uact.activeListTitle')} (${active.length})`} extra={note(t('uact.kpiLive'))}>
        {active.length === 0 ? <StatusBlock tone="neutral" icon={Users} title={t('uact.noActive')} /> : (
          <DataTable testId="uact-sessions">
            <TableHeader><TableRow>
              {sortTh('username', t('uact.colUser'))}
              <TableHead className={cn(TH, MD)}>{t('uact.colTeam')}</TableHead>
              {sortTh('idle_sec', t('uact.colIdle'), true)}
              {sortTh('expires_in_sec', t('uact.colExpires'), true, LG)}
              {sortTh('duration_min', t('uact.colDuration'), true, LG)}
              <TableHead className={cn(TH, MD)}>{t('uact.colLastTab')}</TableHead>
              <TableHead className={cn(TH, LG)}>{t('uact.colLocation')}</TableHead>
              <TableHead className={TH}>{t('uact.colAction')}</TableHead>
            </TableRow></TableHeader>
            <TableBody>
              {active.map((u) => {
                const band = idleBand(u.idle_sec); const self = username && String(u.username).toLowerCase() === String(username).toLowerCase()
                return (
                  <TableRow key={u.username} data-self={self ? 'true' : undefined} className={cn(self && 'bg-primary/5')}>
                    <TableCell className={TD} data-label={t('uact.colUser')}>
                      <UserBadge username={u.username} userId={u.user_id} displayName={u.display_name} nameOnly />
                      {u.last_login_method && /remember/i.test(u.last_login_method) && <Pill tone="remember" className="ml-1.5" title={t('uact.detailLoginMethod')}>{t('uact.methodRemember')}</Pill>}
                      {self && <Pill className="ml-1.5">{t('uact.selfSession')}</Pill>}
                    </TableCell>
                    <TableCell className={cn(TD, MD)} data-label={t('uact.colTeam')}>{u.team_name ? <TeamBadge teamId={u.team_id} teamName={u.team_name} /> : '—'}</TableCell>
                    <TableCell className={TD_NUM} data-label={t('uact.colIdle')}>
                      <span data-idle={band} className={cn('inline-flex items-center gap-1.5 tabular-nums', band === 'away' && 'text-muted-foreground')}>
                        <span aria-hidden="true" className={cn('size-2 rounded-full', IDLE_DOT[band] || IDLE_DOT.away)} />{u.idle_sec >= 0 ? dur(u.idle_sec) : '—'}
                      </span>
                    </TableCell>
                    <TableCell className={cn(TD_NUM, 'text-xs', LG)} data-label={t('uact.colExpires')}>{u.expires_in_sec != null ? dur(u.expires_in_sec) : '—'}</TableCell>
                    <TableCell className={cn(TD_NUM, LG)} data-label={t('uact.colDuration')}>{dur((u.duration_min || 0) * 60)}</TableCell>
                    <TableCell className={cn(TD, 'text-xs', MD)} data-label={t('uact.colLastTab')}>{u.last_tab ? <span title={u.last_tab_at ? formatDateSec(u.last_tab_at) : ''}>{tabLabel(u.last_tab, t)}</span> : '—'}</TableCell>
                    <TableCell className={cn(TD, 'text-xs', LG)} data-label={t('uact.colLocation')}>{idHidden(u, 'ip', idMasked) ? <MaskedValue /> : <>{u.ip ? <span className="font-mono">{u.ip}</span> : '—'}{u.city || u.country ? <span className="text-muted-foreground"> {[u.city, u.country].filter(Boolean).join(', ')}</span> : null}</>}</TableCell>
                    <TableCell className={TD} data-label={t('uact.colAction')}>
                      <span className="inline-flex flex-wrap gap-1.5">
                        <Button type="button" variant="secondary" size="sm" onClick={() => setSessionDetail(u)}>{t('uact.openDetail')}</Button>
                        {isAdmin && !self && <Button type="button" variant="destructive" size="sm" disabled={busyUser === u.username} onClick={() => setTerminate({ username: u.username })}>{busyUser === u.username ? t('uact.terminating') : t('uact.terminate')}</Button>}
                      </span>
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </DataTable>
        )}
      </SectionCard>

      {/* 05 Sayfa kullanımı (#1) */}
      <SectionCard num="05" title={t('uact.secUsage')} extra={note(t('uact.usageWindow', usage.days ?? 7))}>
        {(usage.pages || []).length === 0 ? <StatusBlock tone="neutral" icon={BookOpen} title={t('uact.usageNoneTitle')} description={t('uact.usageNone')} /> : (
          <div className="flex flex-col gap-[18px]">
            <DataTable>
              <TableHeader><TableRow>
                <TableHead className={TH}>{t('uact.colPage')}</TableHead><TableHead className={TH_NUM}>{t('uact.colMinutes')}</TableHead>
                <TableHead className={cn(TH_NUM, LG)}>{t('uact.colUsers')}</TableHead><TableHead className={TH}>{t('uact.colShare')}</TableHead>
                <TableHead className={cn(TH, MD)}>{t('uact.colLastSeen')}</TableHead>
              </TableRow></TableHeader>
              <TableBody>{usage.pages.map((p) => (
                <TableRow key={p.tab}>
                  <TableCell className={TD} data-label={t('uact.colPage')}>{tabLabel(p.tab, t)} <span className="font-mono text-xs text-muted-foreground">{p.tab}</span></TableCell>
                  <TableCell className={TD_NUM} data-label={t('uact.colMinutes')}>{p.minutes}</TableCell>
                  <TableCell className={cn(TD_NUM, LG)} data-label={t('uact.colUsers')}>{p.users}</TableCell>
                  <TableCell className={TD} data-label={t('uact.colShare')}>
                    <span className="inline-flex items-center gap-2 whitespace-nowrap">{bar(Math.min(100, p.share))}<span data-share-lbl="" className="min-w-[38px] text-right text-xs text-muted-foreground tabular-nums">{p.share}%</span></span>
                  </TableCell>
                  <TableCell className={cn(TD, 'text-xs', MD)} data-label={t('uact.colLastSeen')}>{p.last_seen ? <span title={formatDateSec(p.last_seen)}>{rel(p.last_seen)}</span> : '—'}</TableCell>
                </TableRow>
              ))}</TableBody>
            </DataTable>
            {/* 2026-09-21: tablo altında üç blok yan yana (dar ekranda alt alta) — sayfa tablosuyla yan yana sıkışmıyor */}
            <div className="grid grid-cols-[repeat(auto-fit,minmax(min(320px,100%),1fr))] items-start gap-x-6 gap-y-2">
              <div className="min-w-0">
                <div className={cn(subTitle, 'mt-0')}>{t('uact.unusedTitle')}</div>
                {unusedList.length === 0 ? <span className={MUTED_SM}>{t('uact.unusedNone')}</span> : <div className="flex flex-wrap gap-1.5">{unusedList.map((k) => <Badge key={k} variant="outline" data-unused-tab={k} className="rounded-full font-normal">{tabLabel(k, t)}</Badge>)}</div>}
              </div>
              <div className="min-w-0">
                <div className={cn(subTitle, 'mt-0')}>{t('uact.usageUsers')}</div>
                <ul className="flex list-none flex-col gap-1.5">{(usage.users || []).map((u) => <li key={u.username} className="flex flex-wrap items-center gap-2.5"><UserBadge username={u.username} userId={u.user_id} displayName={u.display_name} /><span className={MUTED_SM}>{u.minutes} {t('chg.unitMin')} · {u.pages} {t('uact.colPagesShort')} · {tabLabel(u.top_tab, t)}</span></li>)}</ul>
              </div>
              {(usage.teams || []).length > 0 && (
                <div className="min-w-0">
                  <div className={cn(subTitle, 'mt-0')}>{t('uact.usageTeams')}</div>
                  <ul className="flex list-none flex-col gap-1.5">{usage.teams.map((tm) => <li key={tm.team_id} className="flex flex-wrap items-center gap-2.5"><TeamBadge teamId={tm.team_id} teamName={tm.team_name} /><span className={MUTED_SM}>{Object.entries(tm.tabs || {}).sort((a, b) => b[1] - a[1]).slice(0, 4).map(([tab, n]) => `${tabLabel(tab, t)} (${n})`).join(' · ')}</span></li>)}</ul>
                </div>
              )}
            </div>
          </div>
        )}
      </SectionCard>

      {/* 06 Giriş durumu (#5) */}
      <SectionCard num="06" title={t('uact.loginStatusTitle')} extra={(
        <SegmentedControl className="ml-auto" value={statusSort} onChange={setStatusSort} ariaLabel={t('uact.sortBy')}
          options={['status', 'failed', 'name'].map((k) => ({ value: k, label: t(`uact.sort_${k}`) }))} />
      )}>
        <div className={MUTED_SM}>{t('uact.loginStatusHint')}</div>
        {loginRows.length === 0 ? <StatusBlock tone="neutral" icon={Users} title={t('uact.noRows')} /> : (
          <DataTable>
            <TableHeader><TableRow>
              <TableHead className={TH}>{t('uact.colUser')}</TableHead><TableHead className={cn(TH, MD)}>{t('uact.colTeam')}</TableHead>
              <TableHead className={TH}>{t('uact.colStatus')}</TableHead><TableHead className={TH}>{t('uact.colLastLogin')}</TableHead>
              <TableHead className={cn(TH, LG)}>{t('uact.detailLoginMethod')}</TableHead><TableHead className={TH_NUM}>{t('uact.colFailedCount')}</TableHead>
              <TableHead className={TH}>{t('uact.colAction')}</TableHead>
            </TableRow></TableHeader>
            <TableBody>{loginRows.map((r) => (
              <TableRow key={r.username}>
                <TableCell className={TD} data-label={t('uact.colUser')}><UserBadge username={r.username} userId={r.user_id} displayName={r.display_name} nameOnly showUsername={false} /></TableCell>{/* yalnız ad soyad (2026-09-21 kullanıcı bildirimi: bölüm eki + rol pili kalabalıktı) */}
                <TableCell className={cn(TD, MD)} data-label={t('uact.colTeam')}>{r.team_name ? <TeamBadge teamId={r.team_id} teamName={r.team_name} /> : '—'}</TableCell>
                <TableCell className={TD} data-label={t('uact.colStatus')}><Pill tone={r.status} status={r.status}>{t(`uact.st.${r.status}`)}</Pill></TableCell>
                <TableCell className={cn(TD, 'text-xs')} data-label={t('uact.colLastLogin')}>{r.last_login_at ? <span title={formatDateSec(r.last_login_at)}>{rel(r.last_login_at)}</span> : '—'}</TableCell>
                <TableCell className={cn(TD, 'text-xs', LG)} data-label={t('uact.detailLoginMethod')}>{r.last_login_method || '—'}</TableCell>
                <TableCell className={cn(TD_NUM, (r.failed_since_login || 0) > 0 && 'text-destructive')} data-label={t('uact.colFailedCount')}>{r.failed_since_login ?? 0}</TableCell>
                <TableCell className={TD} data-label={t('uact.colAction')}><Button type="button" variant="secondary" size="sm" onClick={() => setSessionDetail(r)}>{t('uact.openDetail')}</Button></TableCell>
              </TableRow>
            ))}</TableBody>
          </DataTable>
        )}
      </SectionCard>

      {/* 07 Takım / rol (#7) */}
      <SectionCard num="07" title={t('uact.roleTeamTitle')}>
        <div className="grid grid-cols-1 gap-[18px] md:grid-cols-2">
          <div>
            <div className={subTitle}>{t('uact.colTeam')}</div>
            {(roleTeam.by_team || []).length === 0 ? <span className={MUTED_SM}>—</span> : teamBars(roleTeam.by_team.filter((r) => rowMatches({ team_id: r.team_id, team_name: r.team_name }, { ...filters, role: '' }))).map((r, i) => teamBarRow(
              expTeam === i, () => setExpTeam((x) => x === i ? null : i),
              <>
                {r.team_name ? <TeamBadge as="span" teamId={r.team_id} teamName={r.team_name} /> : <span className="text-muted-foreground">{t('uact.teamNone')}</span>}
                {bar(r.pct, true)}
                <b>{r.count}</b>
                {r.member_count != null && <span className={MUTED_SM}>{t('uact.colMembers')}: {r.member_count}</span>}
                {(r.never_logged || []).length > 0 && <Pill tone="dormant" status="dormant">{t('uact.neverLogged', r.never_logged.length)}</Pill>}
              </>,
              <>
                {(r.users || []).map((u) => <div key={u.username}><UserBadge username={u.username} userId={u.user_id} displayName={u.display_name} count={u.count} nameOnly /></div>)}
                {(r.never_logged || []).length > 0 && <div><span className={MUTED_SM}>{t('uact.neverLoggedList')}:</span><div className="flex flex-wrap gap-1.5">{r.never_logged.map((u) => <UserBadge key={u.username} username={u.username} userId={u.user_id} displayName={u.display_name} nameOnly showUsername={false} size="sm" />)}</div></div>}
              </>, i))}
          </div>
          <div>
            <div className={subTitle}>{t('uact.colRole')}</div>
            {(roleTeam.by_role || []).length === 0 ? <span className={MUTED_SM}>—</span> : teamBars(roleTeam.by_role).map((r) => teamBarRow(
              expRole === r.role, () => setExpRole((x) => x === r.role ? null : r.role),
              <><span>{r.role}</span>{bar(r.pct, true)}<b>{r.count}</b></>,
              (r.users || []).map((u) => <div key={u.username}><UserBadge username={u.username} userId={u.user_id} displayName={u.display_name} count={u.count} /></div>), r.role))}
          </div>
        </div>
      </SectionCard>

      {/* 08 Kaynaklar (#8) */}
      <SectionCard num="08" title={t('uact.topSourcesTitle')}>
        {idMasked && !Array.isArray(ua.top_sources)
          // IP anahtarlı liste global olmayan görüntüleyiciye hiç gelmez (2026-09-28c) → "kayıt yok" DEĞİL, yetki durumu
          ? <StatusBlock tone="neutral" icon={Lock} title={t('uact.sourcesMaskedTitle')} description={t('uact.sourcesMaskedDesc')} />
          : (ua.top_sources || []).length === 0 ? <StatusBlock tone="neutral" icon={Info} title={t('uact.noRows')} /> : (
          <DataTable>
            <TableHeader><TableRow>
              <TableHead className={TH}>{t('uact.colIp')}</TableHead><TableHead className={cn(TH, LG)}>{t('uact.colLocation')}</TableHead>
              <TableHead className={cn(TH, MD)}>{t('uact.colOrg')}</TableHead><TableHead className={TH}>{t('uact.colUsersBehind')}</TableHead>
              <TableHead className={cn(TH, LG)}>{t('uact.colFirstSeen')}</TableHead><TableHead className={cn(TH, MD)}>{t('uact.colLastSeen')}</TableHead>
              <TableHead className={TH_NUM}>{t('uact.colTotal')}</TableHead><TableHead className={cn(TH_NUM, LG)}>{t('uact.success')}</TableHead>
              <TableHead className={TH_NUM}>{t('uact.failed')}</TableHead>
            </TableRow></TableHeader>
            <TableBody>{ua.top_sources.map((r) => (
              <TableRow key={r.ip}>
                {/* 2026-09-21: IP hücresi sade — ip + "yeni" pili tek satır, ters DNS altta; kullanıcılar ad soyadla (yalnız sicil değil) */}
                <TableCell className={TD} data-label={t('uact.colIp')}>
                  <span className="inline-flex items-center gap-1.5 whitespace-nowrap"><span className="font-mono">{r.ip}</span>{r.new_this_week && <Pill tone="new">{t('uact.newThisWeek')}</Pill>}</span>
                  {r.reverse_dns && <span className="block text-xs text-muted-foreground">{r.reverse_dns}</span>}
                </TableCell>
                <TableCell className={cn(TD, 'text-xs', LG)} data-label={t('uact.colLocation')}>{[r.city, r.country].filter(Boolean).join(', ') || '—'}</TableCell>
                <TableCell className={cn(TD, 'text-xs', MD)} data-label={t('uact.colOrg')}>{r.org || '—'}</TableCell>
                <TableCell className={cn(TD, 'text-xs')} data-label={t('uact.colUsersBehind')}>
                  <span className="mr-1.5 font-semibold">{r.user_count ?? (r.users || []).length}</span>
                  {(r.users || []).length > 0 && <span className="inline-flex flex-wrap items-center gap-1">{r.users.slice(0, 3).map((u) => <LinkButton key={u} onClick={() => setSessionDetail({ username: u })}><UserBadge username={u} inline nameOnly size="sm" /></LinkButton>)}{r.users.length > 3 ? <span className="text-muted-foreground">+{r.users.length - 3}</span> : null}</span>}
                </TableCell>
                <TableCell className={cn(TD, 'text-xs', LG)} data-label={t('uact.colFirstSeen')}>{r.first_seen ? rel(r.first_seen) : '—'}</TableCell>
                <TableCell className={cn(TD, 'text-xs', MD)} data-label={t('uact.colLastSeen')}>{r.last_seen ? rel(r.last_seen) : '—'}</TableCell>
                <TableCell className={TD_NUM} data-label={t('uact.colTotal')}>{r.total}</TableCell>
                <TableCell className={cn(TD_NUM, 'text-success', LG)} data-label={t('uact.success')}>{r.success}</TableCell>
                <TableCell className={cn(TD_NUM, r.failed > 0 && 'text-destructive')} data-label={t('uact.failed')}>{r.failed}</TableCell>
              </TableRow>
            ))}</TableBody>
          </DataTable>
        )}
      </SectionCard>

      {/* 09 Anomaliler (#3) */}
      <SectionCard num="09" title={`${t('uact.anomaliesTitle')} (${anomalies.total ?? 0})`} extra={
        <Button type="button" variant="secondary" size="sm" className="ml-auto" onClick={() => setGlossaryOpen((o) => !o)} aria-expanded={glossaryOpen}><Info size={13} /> {t('uact.flagHelpTitle')}</Button>}>
        {glossaryOpen && (
          <dl data-testid="uact-glossary" className="grid gap-2 rounded-lg border bg-muted/40 p-3 text-sm border-border">{FLAG_KEYS.map((k) => <div key={k} className="flex flex-wrap items-baseline gap-2"><dt><FlagBadge flag={k}>{t(`uact.anom_${k}`)}</FlagBadge></dt><dd className="text-muted-foreground">{t(`uact.flagHelp.${k}`)}</dd></div>)}</dl>
        )}
        <div className="mb-3 grid grid-cols-2 gap-3 lg:grid-cols-5">
          {FLAG_KEYS.map((k) => <KpiCard key={k} kpiKey={`flag-${k}`} mini value={anomalies.counts?.[k] ?? 0} label={t(`uact.anom_${k}`)} tone={(anomalies.counts?.[k] ?? 0) > 0 ? 'danger' : undefined} />)}
        </div>
        {(anomalies.recent || []).length === 0 ? <StatusBlock tone="success" icon={Check} title={t('uact.noAnomalies')} /> : (
          <DataTable>
            <TableHeader><TableRow>
              <TableHead className={TH}>{t('uact.colTime')}</TableHead><TableHead className={TH}>{t('uact.colUser')}</TableHead>
              <TableHead className={cn(TH, MD)}>{t('uact.colTeam')}</TableHead><TableHead className={TH}>{t('uact.colIp')}</TableHead>
              <TableHead className={TH}>{t('uact.colFlags')}</TableHead><TableHead className={cn(TH, MD)}>{t('uact.colOutcome')}</TableHead>
              <TableHead className={TH}>{t('uact.ackCol')}</TableHead>
            </TableRow></TableHeader>
            <TableBody>{anomalies.recent.map((r, i) => (
              <TableRow key={r.id ?? i} data-acked={r.ack ? 'true' : undefined} className={cn(r.ack && 'opacity-70')}>
                <TableCell className={cn(TD, 'font-mono text-xs')} data-label={t('uact.colTime')}>{r.time ? formatDateSec(r.time) : '—'}</TableCell>
                <TableCell className={TD} data-label={t('uact.colUser')}>{r.actor ? <LinkButton onClick={() => setSessionDetail({ username: r.actor })}><UserBadge username={r.actor} userId={r.user_id} displayName={r.display_name} inline nameOnly size="sm" /></LinkButton> : '—'}</TableCell>
                <TableCell className={cn(TD, MD)} data-label={t('uact.colTeam')}>{r.team_name ? <TeamBadge teamId={r.team_id} teamName={r.team_name} /> : <span className="text-muted-foreground">—</span>}</TableCell>
                <TableCell className={cn(TD, 'font-mono text-xs')} data-label={t('uact.colIp')}>{idHidden(r, 'ip', idMasked) ? <MaskedValue /> : <>{r.ip || '—'}{r.city || r.country ? <span className="text-muted-foreground"> {[r.city, r.country].filter(Boolean).join(', ')}</span> : null}</>}</TableCell>
                <TableCell className={TD} data-label={t('uact.colFlags')}>{splitFlags(r.flags).map((f) => <FlagBadge key={f} flag={f} title={t(`uact.flagHelp.${f}`)}>{t(`uact.anom_${f}`)}</FlagBadge>)}</TableCell>
                <TableCell className={cn(TD, 'text-xs', MD)} data-label={t('uact.colOutcome')}>{outcomeText(r.outcome)}{r.reason ? <span className="text-muted-foreground"> · {r.reason}</span> : null}</TableCell>
                <TableCell className={TD} data-label={t('uact.ackCol')}>{r.ack
                  ? <span className="inline-flex items-center gap-1 text-xs text-success" title={r.ack.note || ''}><Check size={12} /> {r.ack.by} · {rel(r.ack.at)}{r.id && canAck && <LinkButton className="text-xs" disabled={ackBusy === r.id} onClick={() => doAck(r, false)}>{t('uact.unack')}</LinkButton>}</span>
                  : (r.id && canAck ? <Button type="button" variant="secondary" size="sm" disabled={ackBusy === r.id} onClick={() => setAckTarget(r)}>{t('uact.ack')}</Button> : '—')}</TableCell>
              </TableRow>
            ))}</TableBody>
          </DataTable>
        )}
      </SectionCard>

      {heatCell && <HeatCellModal cell={heatCell} identityMasked={idMasked} onClose={() => setHeatCell(null)} onUser={(u) => setSessionDetail({ username: u })} />}
      {kpiDetail && ['logins', 'failed', 'anomalies'].includes(kpiDetail.kind)
        ? <EventListModal kind={kpiDetail.kind} title={kpiDetail.title} rows={ua.details?.[kpiDetail.kind] || []} winLabel={winLabel} identityMasked={idMasked}
            byName={new Map((ua.login_status || []).map((u) => [String(u.username || '').toLowerCase(), u]))}
            onClose={() => setKpiDetail(null)} onUser={(row) => setSessionDetail(row)} />
        : kpiDetail && <KpiDetailModal detail={kpiDetail} data={ua} onClose={() => setKpiDetail(null)} onUser={(row) => setSessionDetail(row)} winLabel={winLabel} />}
      {directory && <UserDirectoryModal data={ua} initial={directory} isAdmin={isAdmin} globalAdmin={globalAdmin} username={username} refreshing={refreshing} onClose={() => setDirectory(null)}
        onUser={(row) => setSessionDetail(row)} onTerminate={(u) => setTerminate({ username: u })} onRefresh={onRefresh} />}
      {sessionDetail && <SessionDetailModal row={sessionDetail} full={detailRecord(sessionDetail.username)}
        isAdmin={isAdmin} globalAdmin={globalAdmin} identityMasked={idMasked} self={username && String(sessionDetail.username).toLowerCase() === String(username).toLowerCase()} activeSet={activeSet}
        onClose={() => setSessionDetail(null)} onTerminate={(u) => setTerminate({ username: u })} onAck={doAck} ackBusy={ackBusy} onRefresh={onRefresh} teams={ua.role_team?.by_team || []} />}
      {terminate && <TerminateModal target={terminate.username} busy={busyUser === terminate.username} onClose={() => setTerminate(null)} onConfirm={(reason) => doTerminate(terminate.username, reason)} />}
      {ackTarget && <AckModal row={ackTarget} busy={ackBusy === ackTarget.id} onClose={() => setAckTarget(null)} onConfirm={(note) => doAck(ackTarget, true, note)} />}
    </div>
  )
}
