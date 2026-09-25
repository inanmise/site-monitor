import { useState, useEffect, useMemo, useCallback, lazy, Suspense } from 'react'
import {
  Users, LogIn, XCircle, ShieldAlert, UserCheck, UserX, Download, Link2, RefreshCw, ChevronDown, ChevronRight, Info, Check, BookOpen,
  Compass,
} from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { api, formatDateSec, formatDateOnly } from '../../../api/client'
import { useUrlQuerySync, readUrlParam } from '../../../hooks/useUrlQuerySync.js'
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
  sessionsCsv, loginStatusCsv, anomaliesCsv, usageCsv, teamBars, STATUS_ORDER,
} from './uactModel.js'
import { Button } from '@/components/shadcn/button'

const LoginActivityChart = lazy(() => import('../LoginActivityChart.jsx'))

/**
 * Kullanıcı / Oturum paneli (2026-09-13 zenginleştirme, 14 madde): #1 sayfa kullanımı · #2 boşta/düşme · #3 anomali
 * onayı + kullanıcı zaman çizelgesi · #4 trend kartları · #5 giriş durumu pili + atıl hesap · #6 ısı haritası mesai-dışı
 * gölgesi + anomali işareti · #7 takım çubukları + hiç girmeyenler · #8 kaynak ilk/son/kullanıcı · #9 süzgeç/URL/CSV/bağlantı ·
 * #10 gerekçeli sonlandırma · #11 numaralı bölümler + boş durumlar · #12 tazelik damgası · #13 klavye/aria · #14 gizlilik.
 *
 * Veri SystemHealth'in 30 sn döngüsünden gelir (tek payload); burası yalnız türetir ve çizer.
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

  // ── Türetimler ──
  const ua = useMemo(() => data || {}, [data])
  const sum = ua.summary || {}
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
    try {
      const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' }); const url = URL.createObjectURL(blob)
      const a = document.createElement('a'); a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch { /* jsdom */ }
    setExportOpen(false)
  }
  async function copyLink() {
    try {
      const u = new URL(window.location.href); u.searchParams.set('sec', 'users')   // alıcı bölümü açık görsün (QA ISSUE-001)
      await navigator.clipboard.writeText(u.toString()); toast.success(t('uact.copied'))
    } catch { toast.error(t('uact.copyFailed')) }
  }
  if (error) return <StatusBlock tone="danger" icon={ShieldAlert} title={t('uact.loadError')} actions={<Button type="button" variant="secondary" size="sm" onClick={onRefresh}>{t('uact.refresh')}</Button>} />
  if (!data) return <div className="sys-muted sys-small" style={{ padding: 8 }}>…</div>

  const kpi = (key, Icon, val, label, sub, tone, onClick, spark, delta) => (
    <button key={key} type="button" className={`uact-kpi uact-kpi--btn${tone ? ' uact-kpi--' + tone : ''}`} onClick={onClick} title={t('uact.detailHint')} disabled={!onClick}>
      <span className="uact-kpi-top"><span className="uact-kpi-icon"><Icon size={16} /></span>{spark && spark.some(Boolean) ? <Sparkline data={spark} width={72} height={22} label={label} /> : null}</span>
      <span className="uact-kpi-val">{val}{delta != null && delta !== 0 && <span className={`uact-kpi-delta${delta > 0 ? ' is-up' : ' is-down'}`} title={t('uact.deltaVsAvg')}>{delta > 0 ? '▲' : '▼'} {Math.abs(delta)}%</span>}</span>
      <span className="uact-kpi-lbl">{label}</span>
      {sub ? <span className="uact-kpi-sub">{sub}</span> : null}
    </button>
  )
  const secHead = (num, title, extra) => (
    <div className="fc-sec-header uact-sec-header"><span className="fc-sec-num">{num}</span><span className="fc-sec-title">{title}</span>{extra}</div>
  )
  const sortBtn = (col, label, numeric) => (
    <th className={`dbtcol-th${numeric ? ' dbtcol-th-num' : ''}`} aria-sort={sort.col === col ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'}>
      <button type="button" className="uact-th-btn" onClick={() => setSort((s) => s.col === col ? { col, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { col, dir: col === 'username' ? 'asc' : 'asc' })}>
        {label}<span className="dbt-arrow">{sort.col === col ? (sort.dir === 'asc' ? ' ↑' : ' ↓') : ''}</span>
      </button>
    </th>
  )
  const unusedList = unusedTabs(usage.pages)
  const failedT = failedTone(pick('failed_24h', 'failed_7d'), pick('logins_24h', 'logins_7d'))

  return (
    <div className="uact-exec forecast-page uact-page">
      {/* Hero */}
      <div className="uact-hero">
        <div className="uact-hero-title">
          <span className="uact-hero-eyebrow">{t('uact.section')}</span>
          <span className="uact-hero-h">{t('uact.heroTitle')}</span>
          <span className="uact-hero-stamp">{t('uact.dataAsOf', ua.generated_at ? formatDateSec(ua.generated_at) : '—')} · {winLabel}</span>
        </div>
        <div className="uact-hero-actions">
          <button type="button" className="uact-hero-live" onClick={() => setDirectory({ view: 'all' })} title={t('uact.activeCardHint')}>
            <span className="uact-hero-dot" />{sum.active_count ?? 0} {t('uact.activeNow')}
          </button>
          <button type="button" className="uact-hero-btn" onClick={onRefresh} disabled={refreshing} title={t('uact.refresh')} aria-label={t('uact.refresh')}>
            <RefreshCw size={14} className={refreshing ? 'spin' : ''} />
          </button>
        </div>
      </div>

      {/* Süzgeç çubuğu (#9) */}
      <div className="uact-filters">
        <label className="invtb-f"><span>{t('uact.filterTeam')}</span><SearchableSelect value={filters.team} onChange={(v) => setF({ team: v })} options={teamOptions} searchThreshold={4} /></label>
        <label className="invtb-f"><span>{t('uact.filterRole')}</span><SearchableSelect value={filters.role} onChange={(v) => setF({ role: v })} options={roleOptions} /></label>
        <div className="seg-ctl" role="group" aria-label={t('uact.filterRange')}>
          <button type="button" className={`seg-ctl-btn${!is7d ? ' active' : ''}`} onClick={() => setF({ range: '24h' })} aria-pressed={!is7d}>{t('uact.range24h')}</button>
          <button type="button" className={`seg-ctl-btn${is7d ? ' active' : ''}`} onClick={() => setF({ range: '7d' })} aria-pressed={is7d}>{t('uact.range7dShort')}</button>
        </div>
        {hasActiveFilter(filters) && <Button type="button" variant="secondary" size="sm" onClick={() => setFilters({ ...EMPTY_FILTERS })}>{t('uact.filterClear')}</Button>}
        <div className="invtb-spacer" />
        <div className="colpick">
          <Button type="button" variant="secondary" size="sm" onClick={() => setExportOpen((o) => !o)} aria-expanded={exportOpen} aria-haspopup="true"><Download size={13} /> {t('uact.export')}</Button>
          {exportOpen && (
            <div className="colpick-menu" role="group" aria-label={t('uact.export')}>
              <Button type="button" variant="secondary" size="sm" onClick={() => download('sessions.csv', sessionsCsv(active, t))}>{t('uact.exportSessions')}</Button>
              <Button type="button" variant="secondary" size="sm" onClick={() => download('login-status.csv', loginStatusCsv(loginRows, t))}>{t('uact.exportStatus')}</Button>
              <Button type="button" variant="secondary" size="sm" onClick={() => download('anomalies.csv', anomaliesCsv(anomalies.recent || [], t))}>{t('uact.exportAnomalies')}</Button>
              <Button type="button" variant="secondary" size="sm" onClick={() => download('page-usage.csv', usageCsv(usage.pages || [], t))}>{t('uact.exportUsage')}</Button>
            </div>
          )}
        </div>
        <Button type="button" variant="secondary" size="sm" onClick={copyLink}><Link2 size={13} /> {t('uact.copyLink')}</Button>
      </div>

      {/* 01 KPI trend kartları (#4, #5) */}
      <div className="uact-kpi-grid uact-kpi-grid--6 uact-kpi-grid--tour">
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
      <div className="fc-card fc-section-card">
        {secHead('02', t('uact.trendTitle'))}
        <div className="chart-range-bar" style={{ alignItems: 'center', flexWrap: 'wrap' }}>
          {['1h', '6h'].map((h) => <button key={h} type="button" className={`chart-range-btn ${trendPreset === h ? 'chart-range-btn-active' : ''}`} onClick={() => { setTrendDate(''); setTrendCustom(null); setTrendShowCustom(false); setTrendPreset(h) }}>{t(`uact.range${h}`)}</button>)}
          {[1, 7, 30].map((d) => <button key={d} type="button" className={`chart-range-btn ${!trendDate && !trendCustom && !trendPreset && trendDays === d ? 'chart-range-btn-active' : ''}`} onClick={() => { setTrendDate(''); setTrendCustom(null); setTrendShowCustom(false); setTrendPreset(null); setTrendDays(d) }}>{t(`uact.range${d}d`)}</button>)}
          <span className="sys-muted sys-small">·</span>
          <DateTimeField dateOnly clearable className="dtf-inline" value={trendDate} onChange={(v) => { setTrendCustom(null); setTrendShowCustom(false); setTrendPreset(null); setTrendDate(v) }} placeholder={t('uact.gotoDay')} />
          <button type="button" className={`chart-range-btn ${trendCustom ? 'chart-range-btn-active' : ''}`} onClick={() => setTrendShowCustom((s) => !s)}>{t('chart.custom')}</button>
          {trendLoading && <Spinner size={14} inline decorative />}
        </div>
        {trendShowCustom && (
          <div style={{ margin: '8px 0' }}>
            <DateTimeRangePicker from={trendCustom ? new Date(trendCustom.from + 'Z') : new Date(Date.now() - 7 * 86_400_000)} to={trendCustom ? new Date(trendCustom.to + 'Z') : new Date()}
              onApply={(f, to) => { setTrendDate(''); setTrendDays(7); setTrendPreset(null); setTrendCustom({ from: f.toISOString().slice(0, 19), to: to.toISOString().slice(0, 19) }) }} />
          </div>
        )}
        {(trendData?.buckets || []).length === 0
          ? <StatusBlock tone="neutral" icon={LogIn} title={t('uact.noLoginsRange')} />
          : <Suspense fallback={<div className="sys-muted sys-small" style={{ padding: 8 }}>…</div>}><LoginActivityChart buckets={trendData.buckets} gran={trendData.granularity || 'day'} /></Suspense>}
      </div>

      {/* 03 Isı haritası (#6) */}
      <div className="fc-card fc-section-card">
        {secHead('03', t('uact.peakTitle'), heatmaps.length > 0 && (
          <div className="seg-ctl uact-week-nav" role="group" aria-label={t('uact.weekNav')}>
            <button type="button" className="seg-ctl-btn" disabled={weekIdx >= heatmaps.length - 1} onClick={() => setWeekIdx((w) => Math.min(heatmaps.length - 1, w + 1))}>← {t('uact.prevWeek')}</button>
            <button type="button" className="seg-ctl-btn active" aria-current="true">{weekIdx === 0 ? t('uact.weekThis') : t(`uact.weekPrev${weekIdx}`)}</button>
            <button type="button" className="seg-ctl-btn" disabled={weekIdx <= 0} onClick={() => setWeekIdx((w) => Math.max(0, w - 1))}>{t('uact.nextWeek')} →</button>
          </div>
        ))}
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
              <div className="uact-legend"><span className="uact-legend-off">{t('uact.offHoursLegend', office.start, office.end)}</span><span className="uact-legend-mark">{t('uact.failedLegend')}</span><span className="sys-muted sys-small">{t('uact.heatHint')}</span></div>
            </>
          )
        })() : <StatusBlock tone="neutral" icon={LogIn} title={t('uact.noLoginsRange')} />}
      </div>

      {/* 04 Aktif oturumlar (#2, #10, #13) */}
      <div className="fc-card fc-section-card">
        {secHead('04', `${t('uact.activeListTitle')} (${active.length})`, <span className="uact-sec-note">{t('uact.kpiLive')}</span>)}
        {active.length === 0 ? <StatusBlock tone="neutral" icon={Users} title={t('uact.noActive')} /> : (
          <div className="health-table-wrap uact-table-wrap">
            <table className="health-dbtable uact-table uact-table--sessions">
              <thead><tr>
                {sortBtn('username', t('uact.colUser'))}
                <th className="dbtcol-th">{t('uact.colTeam')}</th>
                {sortBtn('idle_sec', t('uact.colIdle'), true)}
                {sortBtn('expires_in_sec', t('uact.colExpires'), true)}
                {sortBtn('duration_min', t('uact.colDuration'), true)}
                <th className="dbtcol-th">{t('uact.colLastTab')}</th>
                <th className="dbtcol-th">{t('uact.colLocation')}</th>
                <th className="dbtcol-th">{t('uact.colAction')}</th>
              </tr></thead>
              <tbody>
                {active.map((u) => {
                  const band = idleBand(u.idle_sec); const self = username && String(u.username).toLowerCase() === String(username).toLowerCase()
                  return (
                    <tr key={u.username} className={`uact-row${self ? ' is-self' : ''}`}>
                      <td data-label={t('uact.colUser')}><UserBadge username={u.username} userId={u.user_id} displayName={u.display_name} nameOnly />{u.last_login_method && /remember/i.test(u.last_login_method) && <span className="uact-pill uact-pill--remember" title={t('uact.detailLoginMethod')}>{t('uact.methodRemember')}</span>}{self && <span className="uact-pill">{t('uact.selfSession')}</span>}</td>
                      <td data-label={t('uact.colTeam')}>{u.team_name ? <TeamBadge teamId={u.team_id} teamName={u.team_name} /> : '—'}</td>
                      <td data-label={t('uact.colIdle')} className="dbtcol-num-cell"><span className={`uact-idle uact-idle--${band}`}><span className="uact-idle-dot" />{u.idle_sec >= 0 ? dur(u.idle_sec) : '—'}</span></td>
                      <td data-label={t('uact.colExpires')} className="dbtcol-num-cell sys-small">{u.expires_in_sec != null ? dur(u.expires_in_sec) : '—'}</td>
                      <td data-label={t('uact.colDuration')} className="dbtcol-num-cell">{dur((u.duration_min || 0) * 60)}</td>
                      <td data-label={t('uact.colLastTab')} className="sys-small">{u.last_tab ? <span title={u.last_tab_at ? formatDateSec(u.last_tab_at) : ''}>{tabLabel(u.last_tab, t)}</span> : '—'}</td>
                      <td data-label={t('uact.colLocation')} className="sys-small">{u.ip ? <span className="sys-mono">{u.ip}</span> : '—'}{u.city || u.country ? <span className="sys-muted"> {[u.city, u.country].filter(Boolean).join(', ')}</span> : null}</td>
                      <td data-label={t('uact.colAction')} className="uact-actions">
                        <Button type="button" variant="secondary" size="sm" onClick={() => setSessionDetail(u)}>{t('uact.openDetail')}</Button>
                        {isAdmin && !self && <Button type="button" variant="destructive" size="sm" disabled={busyUser === u.username} onClick={() => setTerminate({ username: u.username })}>{busyUser === u.username ? t('uact.terminating') : t('uact.terminate')}</Button>}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* 05 Sayfa kullanımı (#1) */}
      <div className="fc-card fc-section-card">
        {secHead('05', t('uact.secUsage'), <span className="uact-sec-note">{t('uact.usageWindow', usage.days ?? 7)}</span>)}
        {(usage.pages || []).length === 0 ? <StatusBlock tone="neutral" icon={BookOpen} title={t('uact.usageNoneTitle')} description={t('uact.usageNone')} /> : (
          <div className="uact-usage">
            <div className="health-table-wrap">
              <table className="health-dbtable uact-table uact-table--usage">
                <thead><tr><th className="dbtcol-th">{t('uact.colPage')}</th><th className="dbtcol-th dbtcol-th-num">{t('uact.colMinutes')}</th><th className="dbtcol-th dbtcol-th-num">{t('uact.colUsers')}</th><th className="dbtcol-th uact-th-share">{t('uact.colShare')}</th><th className="dbtcol-th uact-th-seen">{t('uact.colLastSeen')}</th></tr></thead>
                <tbody>{usage.pages.map((p) => (
                  <tr key={p.tab}><td data-label={t('uact.colPage')}>{tabLabel(p.tab, t)} <span className="sys-muted sys-small sys-mono">{p.tab}</span></td><td data-label={t('uact.colMinutes')} className="dbtcol-num-cell">{p.minutes}</td><td data-label={t('uact.colUsers')} className="dbtcol-num-cell">{p.users}</td>
                    <td data-label={t('uact.colShare')} className="uact-share-cell"><span className="uact-share"><span className="uact-bar" style={{ '--w': `${Math.min(100, p.share)}%` }}><span className="uact-bar-fill" /></span><span className="uact-share-lbl">{p.share}%</span></span></td>
                    <td data-label={t('uact.colLastSeen')} className="sys-small uact-seen-cell">{p.last_seen ? <span title={formatDateSec(p.last_seen)}>{rel(p.last_seen)}</span> : '—'}</td></tr>
                ))}</tbody>
              </table>
            </div>
            {/* 2026-09-21: tablo altında üç blok yan yana (dar ekranda alt alta) — sayfa tablosuyla yan yana sıkışmıyor */}
            <div className="uact-usage-side">
              <div className="uact-usage-block">
              <div className="uact-sub-title">{t('uact.unusedTitle')}</div>
              {unusedList.length === 0 ? <span className="sys-muted sys-small">{t('uact.unusedNone')}</span> : <div className="uact-chips">{unusedList.map((k) => <span key={k} className="uact-chip">{tabLabel(k, t)}</span>)}</div>}
              </div>
              <div className="uact-usage-block">
              <div className="uact-sub-title">{t('uact.usageUsers')}</div>
              <ul className="uact-list">{(usage.users || []).map((u) => <li key={u.username}><UserBadge username={u.username} userId={u.user_id} displayName={u.display_name} /><span className="sys-small sys-muted">{u.minutes} {t('chg.unitMin')} · {u.pages} {t('uact.colPagesShort')} · {tabLabel(u.top_tab, t)}</span></li>)}</ul>
              </div>
              {(usage.teams || []).length > 0 && (
                <div className="uact-usage-block">
              <div className="uact-sub-title">{t('uact.usageTeams')}</div>
              <ul className="uact-list">{usage.teams.map((tm) => <li key={tm.team_id}><TeamBadge teamId={tm.team_id} teamName={tm.team_name} /><span className="sys-small sys-muted">{Object.entries(tm.tabs || {}).sort((a, b) => b[1] - a[1]).slice(0, 4).map(([tab, n]) => `${tabLabel(tab, t)} (${n})`).join(' · ')}</span></li>)}</ul>
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      {/* 06 Giriş durumu (#5) */}
      <div className="fc-card fc-section-card">
        {secHead('06', t('uact.loginStatusTitle'), (
          <div className="seg-ctl" role="group" aria-label={t('uact.sortBy')}>
            {['status', 'failed', 'name'].map((k) => <button key={k} type="button" className={`seg-ctl-btn${statusSort === k ? ' active' : ''}`} onClick={() => setStatusSort(k)} aria-pressed={statusSort === k}>{t(`uact.sort_${k}`)}</button>)}
          </div>
        ))}
        <div className="sys-muted sys-small">{t('uact.loginStatusHint')}</div>
        {loginRows.length === 0 ? <StatusBlock tone="neutral" icon={Users} title={t('uact.noRows')} /> : (
          <div className="health-table-wrap uact-table-wrap">
            <table className="health-dbtable uact-table">
              <thead><tr><th className="dbtcol-th">{t('uact.colUser')}</th><th className="dbtcol-th">{t('uact.colTeam')}</th><th className="dbtcol-th">{t('uact.colStatus')}</th><th className="dbtcol-th">{t('uact.colLastLogin')}</th><th className="dbtcol-th">{t('uact.detailLoginMethod')}</th><th className="dbtcol-th dbtcol-th-num">{t('uact.colFailedCount')}</th><th className="dbtcol-th">{t('uact.colAction')}</th></tr></thead>
              <tbody>{loginRows.map((r) => (
                <tr key={r.username} className="uact-row">
                  <td data-label={t('uact.colUser')}><UserBadge username={r.username} userId={r.user_id} displayName={r.display_name} nameOnly showUsername={false} /></td>{/* yalnız ad soyad (2026-09-21 kullanıcı bildirimi: bölüm eki + rol pili kalabalıktı) */}
                  <td data-label={t('uact.colTeam')}>{r.team_name ? <TeamBadge teamId={r.team_id} teamName={r.team_name} /> : '—'}</td>
                  <td data-label={t('uact.colStatus')}><span className={`uact-pill uact-st--${r.status}`}>{t(`uact.st.${r.status}`)}</span></td>
                  <td data-label={t('uact.colLastLogin')} className="sys-small">{r.last_login_at ? <span title={formatDateSec(r.last_login_at)}>{rel(r.last_login_at)}</span> : '—'}</td>
                  <td data-label={t('uact.detailLoginMethod')} className="sys-small">{r.last_login_method || '—'}</td>
                  <td data-label={t('uact.colFailedCount')} className={`dbtcol-num-cell${(r.failed_since_login || 0) > 0 ? ' sys-err-text' : ''}`}>{r.failed_since_login ?? 0}</td>
                  <td data-label={t('uact.colAction')}><Button type="button" variant="secondary" size="sm" onClick={() => setSessionDetail(r)}>{t('uact.openDetail')}</Button></td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        )}
      </div>

      {/* 07 Takım / rol (#7) */}
      <div className="fc-card fc-section-card">
        {secHead('07', t('uact.roleTeamTitle'))}
        <div className="uact-two">
          <div>
            <div className="uact-sub-title">{t('uact.colTeam')}</div>
            {(roleTeam.by_team || []).length === 0 ? <span className="sys-muted sys-small">—</span> : teamBars(roleTeam.by_team.filter((r) => rowMatches({ team_id: r.team_id, team_name: r.team_name }, { ...filters, role: '' }))).map((r, i) => (
              <div key={i} className="uact-teamrow">
                <button type="button" className="uact-teamrow-head" onClick={() => setExpTeam((x) => x === i ? null : i)} aria-expanded={expTeam === i}>
                  {expTeam === i ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                  {r.team_name ? <TeamBadge as="span" teamId={r.team_id} teamName={r.team_name} /> : <span className="sys-muted">{t('uact.teamNone')}</span>}
                  <span className="uact-bar uact-bar--wide" style={{ '--w': `${r.pct}%` }}><span className="uact-bar-fill" /></span>
                  <b>{r.count}</b>
                  {r.member_count != null && <span className="sys-small sys-muted">{t('uact.colMembers')}: {r.member_count}</span>}
                  {(r.never_logged || []).length > 0 && <span className="uact-pill uact-st--dormant">{t('uact.neverLogged', r.never_logged.length)}</span>}
                </button>
                {expTeam === i && (
                  <div className="uact-teamrow-body">
                    {(r.users || []).map((u) => <div key={u.username}><UserBadge username={u.username} userId={u.user_id} displayName={u.display_name} count={u.count} nameOnly /></div>)}
                    {(r.never_logged || []).length > 0 && <div className="uact-never"><span className="sys-small sys-muted">{t('uact.neverLoggedList')}:</span><div className="uact-never-list">{r.never_logged.map((u) => <UserBadge key={u.username} username={u.username} userId={u.user_id} displayName={u.display_name} nameOnly showUsername={false} size="sm" />)}</div></div>}
                  </div>
                )}
              </div>
            ))}
          </div>
          <div>
            <div className="uact-sub-title">{t('uact.colRole')}</div>
            {(roleTeam.by_role || []).length === 0 ? <span className="sys-muted sys-small">—</span> : teamBars(roleTeam.by_role).map((r) => (
              <div key={r.role} className="uact-teamrow">
                <button type="button" className="uact-teamrow-head" onClick={() => setExpRole((x) => x === r.role ? null : r.role)} aria-expanded={expRole === r.role}>
                  {expRole === r.role ? <ChevronDown size={14} /> : <ChevronRight size={14} />}<span>{r.role}</span>
                  <span className="uact-bar uact-bar--wide" style={{ '--w': `${r.pct}%` }}><span className="uact-bar-fill" /></span><b>{r.count}</b>
                </button>
                {expRole === r.role && <div className="uact-teamrow-body">{(r.users || []).map((u) => <div key={u.username}><UserBadge username={u.username} userId={u.user_id} displayName={u.display_name} count={u.count} /></div>)}</div>}
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* 08 Kaynaklar (#8) */}
      <div className="fc-card fc-section-card">
        {secHead('08', t('uact.topSourcesTitle'))}
        {(ua.top_sources || []).length === 0 ? <StatusBlock tone="neutral" icon={Info} title={t('uact.noRows')} /> : (
          <div className="health-table-wrap uact-table-wrap">
            <table className="health-dbtable uact-table">
              <thead><tr><th className="dbtcol-th">{t('uact.colIp')}</th><th className="dbtcol-th">{t('uact.colLocation')}</th><th className="dbtcol-th">{t('uact.colOrg')}</th><th className="dbtcol-th">{t('uact.colUsersBehind')}</th><th className="dbtcol-th">{t('uact.colFirstSeen')}</th><th className="dbtcol-th">{t('uact.colLastSeen')}</th><th className="dbtcol-th dbtcol-th-num">{t('uact.colTotal')}</th><th className="dbtcol-th dbtcol-th-num">{t('uact.success')}</th><th className="dbtcol-th dbtcol-th-num">{t('uact.failed')}</th></tr></thead>
              <tbody>{ua.top_sources.map((r) => (
                <tr key={r.ip} className="uact-row">
                  {/* 2026-09-21: IP hücresi sade — ip + "yeni" pili tek satır, ters DNS altta; kullanıcılar ad soyadla (yalnız sicil değil) */}
                  <td data-label={t('uact.colIp')} className="uact-src-ip"><span className="uact-src-ipline"><span className="sys-mono">{r.ip}</span>{r.new_this_week && <span className="uact-pill uact-pill--new">{t('uact.newThisWeek')}</span>}</span>{r.reverse_dns && <span className="udir-meta">{r.reverse_dns}</span>}</td>
                  <td data-label={t('uact.colLocation')} className="sys-small">{[r.city, r.country].filter(Boolean).join(', ') || '—'}</td>
                  <td data-label={t('uact.colOrg')} className="sys-small">{r.org || '—'}</td>
                  <td data-label={t('uact.colUsersBehind')} className="sys-small uact-src-users"><span className="uact-src-count">{r.user_count ?? (r.users || []).length}</span>{(r.users || []).length > 0 && <span className="uact-src-userlist">{r.users.slice(0, 3).map((u) => <button key={u} type="button" className="uact-link" onClick={() => setSessionDetail({ username: u })}><UserBadge username={u} inline nameOnly size="sm" /></button>)}{r.users.length > 3 ? <span className="sys-muted">+{r.users.length - 3}</span> : null}</span>}</td>
                  <td data-label={t('uact.colFirstSeen')} className="sys-small">{r.first_seen ? rel(r.first_seen) : '—'}</td>
                  <td data-label={t('uact.colLastSeen')} className="sys-small">{r.last_seen ? rel(r.last_seen) : '—'}</td>
                  <td data-label={t('uact.colTotal')} className="dbtcol-num-cell">{r.total}</td><td data-label={t('uact.success')} className="dbtcol-num-cell sys-ok-text">{r.success}</td><td data-label={t('uact.failed')} className={`dbtcol-num-cell${r.failed > 0 ? ' sys-err-text' : ''}`}>{r.failed}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        )}
      </div>

      {/* 09 Anomaliler (#3) */}
      <div className="fc-card fc-section-card">
        {secHead('09', `${t('uact.anomaliesTitle')} (${anomalies.total ?? 0})`, <Button type="button" variant="secondary" size="sm" onClick={() => setGlossaryOpen((o) => !o)} aria-expanded={glossaryOpen}><Info size={13} /> {t('uact.flagHelpTitle')}</Button>)}
        {glossaryOpen && (
          <dl className="uact-glossary">{FLAG_KEYS.map((k) => <div key={k}><dt><span className="uact-flag">{t(`uact.anom_${k}`)}</span></dt><dd>{t(`uact.flagHelp.${k}`)}</dd></div>)}</dl>
        )}
        <div className="uact-kpi-grid uact-kpi-grid--5">
          {FLAG_KEYS.map((k) => <div key={k} className={`uact-kpi uact-kpi--mini${(anomalies.counts?.[k] ?? 0) > 0 ? ' uact-kpi--danger' : ''}`}><span className="uact-kpi-val">{anomalies.counts?.[k] ?? 0}</span><span className="uact-kpi-lbl">{t(`uact.anom_${k}`)}</span></div>)}
        </div>
        {(anomalies.recent || []).length === 0 ? <StatusBlock tone="success" icon={Check} title={t('uact.noAnomalies')} /> : (
          <div className="health-table-wrap uact-table-wrap">
            <table className="health-dbtable uact-table">
              <thead><tr><th className="dbtcol-th">{t('uact.colTime')}</th><th className="dbtcol-th">{t('uact.colUser')}</th><th className="dbtcol-th">{t('uact.colTeam')}</th><th className="dbtcol-th">{t('uact.colIp')}</th><th className="dbtcol-th">{t('uact.colFlags')}</th><th className="dbtcol-th">{t('uact.colOutcome')}</th><th className="dbtcol-th">{t('uact.ackCol')}</th></tr></thead>
              <tbody>{anomalies.recent.map((r, i) => (
                <tr key={r.id ?? i} className={`uact-row${r.ack ? ' is-acked' : ''}`}>
                  <td data-label={t('uact.colTime')} className="sys-mono sys-small">{r.time ? formatDateSec(r.time) : '—'}</td>
                  <td data-label={t('uact.colUser')}>{r.actor ? <button type="button" className="uact-link" onClick={() => setSessionDetail({ username: r.actor })}><UserBadge username={r.actor} userId={r.user_id} displayName={r.display_name} inline nameOnly size="sm" /></button> : '—'}</td>
                  <td data-label={t('uact.colTeam')}>{r.team_name ? <TeamBadge teamId={r.team_id} teamName={r.team_name} /> : <span className="sys-muted">—</span>}</td>
                  <td data-label={t('uact.colIp')} className="sys-mono sys-small">{r.ip || '—'}{r.city || r.country ? <span className="sys-muted"> {[r.city, r.country].filter(Boolean).join(', ')}</span> : null}</td>
                  <td data-label={t('uact.colFlags')}>{splitFlags(r.flags).map((f) => <span key={f} className="uact-flag" title={t(`uact.flagHelp.${f}`)}>{t(`uact.anom_${f}`)}</span>)}</td>
                  <td data-label={t('uact.colOutcome')} className="sys-small">{r.outcome === 'SUCCESS' ? <span className="sys-ok-text">{r.outcome}</span> : <span className="sys-err-text">{r.outcome || '—'}</span>}{r.reason ? <span className="sys-muted"> · {r.reason}</span> : null}</td>
                  <td data-label={t('uact.ackCol')}>{r.ack
                    ? <span className="uact-ack" title={r.ack.note || ''}><Check size={12} /> {r.ack.by} · {rel(r.ack.at)}{r.id && canAck && <button type="button" className="uact-link sys-small" disabled={ackBusy === r.id} onClick={() => doAck(r, false)}>{t('uact.unack')}</button>}</span>
                    : (r.id && canAck ? <Button type="button" variant="secondary" size="sm" disabled={ackBusy === r.id} onClick={() => setAckTarget(r)}>{t('uact.ack')}</Button> : '—')}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        )}
      </div>

      {heatCell && <HeatCellModal cell={heatCell} onClose={() => setHeatCell(null)} onUser={(u) => setSessionDetail({ username: u })} />}
      {kpiDetail && ['logins', 'failed', 'anomalies'].includes(kpiDetail.kind)
        ? <EventListModal kind={kpiDetail.kind} title={kpiDetail.title} rows={ua.details?.[kpiDetail.kind] || []} winLabel={winLabel}
            byName={new Map((ua.login_status || []).map((u) => [String(u.username || '').toLowerCase(), u]))}
            onClose={() => setKpiDetail(null)} onUser={(row) => setSessionDetail(row)} />
        : kpiDetail && <KpiDetailModal detail={kpiDetail} data={ua} onClose={() => setKpiDetail(null)} onUser={(row) => setSessionDetail(row)} winLabel={winLabel} />}
      {directory && <UserDirectoryModal data={ua} initial={directory} isAdmin={isAdmin} globalAdmin={globalAdmin} username={username} onClose={() => setDirectory(null)}
        onUser={(row) => setSessionDetail(row)} onTerminate={(u) => setTerminate({ username: u })} onRefresh={onRefresh} />}
      {sessionDetail && <SessionDetailModal row={sessionDetail} full={detailRecord(sessionDetail.username)}
        isAdmin={isAdmin} globalAdmin={globalAdmin} self={username && String(sessionDetail.username).toLowerCase() === String(username).toLowerCase()} activeSet={activeSet}
        onClose={() => setSessionDetail(null)} onTerminate={(u) => setTerminate({ username: u })} onAck={doAck} ackBusy={ackBusy} onRefresh={onRefresh} teams={ua.role_team?.by_team || []} />}
      {terminate && <TerminateModal target={terminate.username} busy={busyUser === terminate.username} onClose={() => setTerminate(null)} onConfirm={(reason) => doTerminate(terminate.username, reason)} />}
      {ackTarget && <AckModal row={ackTarget} busy={ackBusy === ackTarget.id} onClose={() => setAckTarget(null)} onConfirm={(note) => doAck(ackTarget, true, note)} />}
    </div>
  )
}
