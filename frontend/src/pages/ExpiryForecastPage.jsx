import { useState, useEffect, useRef, useMemo, useCallback } from 'react'
import { dateLocale } from '../i18n/dateLocale.js'
import {
  ComposedChart, Bar, Line, XAxis, YAxis, CartesianGrid,
  Tooltip as RTooltip, ResponsiveContainer,
  PieChart, Pie, Cell, Label, Legend, BarChart,
} from 'recharts'
import { Calendar, RefreshCw, Download, Link2, Printer, Play, CalendarPlus, CalendarCheck, AlertTriangle, ShieldOff } from 'lucide-react'
import { api, formatDate, formatDateOnly, localDayKey } from '../api/client'
import { navigateTo } from '../utils/navigate.js'
import { useT } from '../i18n/index.jsx'
import BrandLogo from '../components/BrandLogo.jsx'
import AlertBanner from '../components/ui/AlertBanner.jsx'
import PlanModal from '../components/RenewalPlanModal.jsx'   // Genel Bakış kartıyla ortak (2026-09-19)
import ForecastDomainsPanel from './ForecastDomainsPanel.jsx'   // alan adı bitişleri — ayrı seri (2026-09-22, F)
import StatusBlock from '../components/ui/StatusBlock.jsx'
import SearchableSelect from '../components/ui/SearchableSelect.jsx'
import ModalShell from '../components/ui/ModalShell.jsx'
import MonthCalendar from '../components/ui/MonthCalendar.jsx'
import TeamBadge from '../components/ui/TeamBadge.jsx'
import { LoadingBlock } from '../components/ui/Progress.jsx'
import { useToast } from '../components/ui/Toast.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { useUrlQuerySync, readUrlParam } from '../hooks/useUrlQuerySync.js'
import { usePagination } from '../hooks/usePagination.js'
import PaginationBar from '../components/ui/PaginationBar.jsx'
import { buildIcs, downloadIcs } from '../utils/ics.js'
import { csvCell } from '../utils/csv.js'
import {
  EMPTY_FILTERS, filtersToParams, paramsToFilters, applyFilters, computeKpis, dailySeries, byTeam, teamBucketCerts, batches, coverage, byIssuer,
  upcoming, nextExpiry, expiryKey, classify, isHoliday, isWeekend, lastBusinessDay, icsEvents, csvRows, todayKey, dayDiff,
} from './forecastModel.js'

/**
 * Vade Takvimi (Expiry Forecast) — 2026-09-12 zenginleştirme, 14 madde:
 * #1 dolmuş + erişilemeyen görünür · #2 takım/UG/tier/grup süzgeci (URL f_*) · #3 renew-by + lead · #4 tazelik,
 * ortam etiketi, hata bandı · #5 alarm eşikleri · #6 eylemler (kontrol et / planla / aç) · #7 takıma göre dolacaklar ·
 * #8 toplu iş + kapsama + veren · #9 ICS/CSV/bağlantı/yazdır · #10 zamanında yenileme oranı · #11 ay görünümü +
 * tatil/hafta sonu · #12 boş durumlar · #13 sözlük · #14 tek uç (/api/forecast).
 */

function useClock() {
  const [time, setTime] = useState(new Date())
  useEffect(() => { const id = setInterval(() => setTime(new Date()), 1000); return () => clearInterval(id) }, [])
  return time
}
function useCountUp(target, duration = 1200) {
  const [val, setVal] = useState(0)
  const rafRef = useRef(null)
  useEffect(() => {
    if (target == null) return
    const start = performance.now()
    const animate = (now) => {
      const t = Math.min((now - start) / duration, 1)
      setVal(Math.round((1 - Math.pow(1 - t, 3)) * target))
      if (t < 1) rafRef.current = requestAnimationFrame(animate)
    }
    rafRef.current = requestAnimationFrame(animate)
    return () => { if (rafRef.current) cancelAnimationFrame(rafRef.current) }
  }, [target, duration])
  return val
}

const RANGE_OPTIONS = [30, 45, 60, 90]
const SEV_COLORS = { overdue: '#dc2626', unreachable: '#7f1d1d', critical: '#f87171', high: '#fb923c', warning: '#fbbf24', later: '#60a5fa' }
const HEAT_COLORS = ['transparent', '#fca5a5', '#ef4444', '#b91c1c', '#7c2d12']
function heatColor(count) { if (count === 0) return 'transparent'; if (count <= 2) return HEAT_COLORS[1]; if (count <= 5) return HEAT_COLORS[2]; if (count <= 9) return HEAT_COLORS[3]; return HEAT_COLORS[4] }
function countColor(slot) { if (slot.critical.length > 0) return '#f87171'; if (slot.high.length > 0) return '#fb923c'; return '#fbbf24' }

function KpiCard2({ range, value, color, sub, onClick, hint }) {
  const count = useCountUp(value ?? 0)
  const Tag = onClick ? 'button' : 'div'
  return (
    <Tag type={onClick ? 'button' : undefined} className={`fc-card fc-kpi2${onClick ? ' fc-kpi2--btn' : ''}`} onClick={onClick}>
      <div className="fc-stripe" style={{ background: color }} />
      <div className="fc-kpi2-range">{range}</div>
      <div className="fc-kpi2-number" style={{ color }}>{count}</div>
      <div className="fc-kpi2-sub">{value === 0 && hint ? hint : sub}</div>
    </Tag>
  )
}

/** Takım tablosu kova etiketi (hücre modal başlığı için). */
function bucketLabel(bucket, th, t) {
  if (bucket === 'overdue') return t('forecast.cls.overdue')
  if (bucket === 'critical') return `≤${th.critical} ${t('forecast.daysLeft')}`
  if (bucket === 'high') return `≤${th.high} ${t('forecast.daysLeft')}`
  if (bucket === 'warning') return `≤${th.warning} ${t('forecast.daysLeft')}`
  if (bucket === 'later') return `>${th.warning} ${t('forecast.daysLeft')}`
  if (bucket === 'late') return t('forecast.rangeLate')
  return t('forecast.colTotal')
}

/**
 * Günlük yoğunluk özeti (2026-09-18): grafiğin üstünde en yoğun gün + 7 günlük tepe + ortalama —
 * çubuklar "9 tane" derken hangi gün, ne kadar yığılma olduğu tek bakışta görülsün; tepe güne tıklanır.
 */
function ChartInsight({ data, t, onOpenDay }) {
  const busy = data.filter((d) => d.total > 0)
  if (busy.length === 0) return null
  const peak = busy.reduce((a, b) => (b.total > a.total ? b : a), busy[0])
  const total = data.reduce((n, d) => n + d.total, 0)
  const days = data.length
  const perWeek = (total / Math.max(1, days / 7)).toFixed(1)
  let peakWeek = 0
  for (let i = 0; i + 7 <= days; i++) { const w = data.slice(i, i + 7).reduce((n, d) => n + d.total, 0); if (w > peakWeek) peakWeek = w }
  const open = () => onOpenDay({ key: peak.date, certs: [...(peak.domains?.critical || []), ...(peak.domains?.high || []), ...(peak.domains?.warning || [])] })
  return (
    <div className="fc-chart-insight">
      <span className="fc-ci-item"><b>{total}</b> {t('forecast.ciTotal', days)}</span>
      <span className="fc-ci-item">{t('forecast.ciBusyDays', busy.length)}</span>
      <span className="fc-ci-item">{t('forecast.ciPerWeek', perWeek)}</span>
      <span className="fc-ci-item">{t('forecast.ciPeakWeek', peakWeek)}</span>
      <button type="button" className="fc-ci-peak" onClick={open} title={t('forecast.dayModalOpen')}>
        {t('forecast.ciPeakDay', peak.label, peak.total)}
      </button>
    </div>
  )
}

/**
 * Gün / takım-kova sertifika listesi modali — SAYFALI (2026-09-18, kullanıcı bildirimi: 9 kayıt bile
 * ekranı aşan tek parça liste açıyordu). Sayfa boyutu küçük (10) ve modal içinde kaydırılır; arama
 * kutusu uzun listede alan adına göre daraltır.
 */
function DayListModal({ modal, th, t, onClose, onSelectDomain, onPlan }) {
  const [q, setQ] = useState('')
  const rows = useMemo(() => {
    const s = q.trim().toLowerCase()
    return s ? modal.certs.filter((c) => (c.domain || '').toLowerCase().includes(s) || (c.team_name || '').toLowerCase().includes(s)) : modal.certs
  }, [modal.certs, q])
  const pager = usePagination(rows, { listKey: 'forecast-day', defaultSize: 10, resetDeps: [q, modal] })
  const title = modal.title ?? t('forecast.dayModalTitle', formatDateOnly(modal.key), modal.certs.length)
  return (
    <ModalShell open onClose={onClose} title={title} icon={Calendar} size="lg" scrollBody
      footer={<button type="button" className="btn btn-secondary" onClick={onClose}>{t('app.close')}</button>}>
      {modal.certs.length > 5 && (
        <input className="input fc-day-search" type="text" placeholder={t('forecast.daySearch')} value={q} onChange={(e) => setQ(e.target.value)} />
      )}
      <ul className="fc-day-list">{pager.pageItems.map((c) => {
        const cls = classify(c, th); const rb = c.renew_by ? localDayKey(c.renew_by) : null
        return (
          <li key={c.domain} className="fc-day-row">
            <span className="fc-exp-sev-dot" style={{ background: SEV_COLORS[cls] }} />
            <button type="button" className="inv-domain" onClick={() => onSelectDomain?.(c.domain)}>{c.domain}</button>
            {c.tier && <span className={`tier-badge tier-badge-${c.tier}`}>T{c.tier}</span>}
            {c.team_name && <TeamBadge teamId={c.team_id} teamName={c.team_name} />}
            <span className="inv-dim">{c.days_remaining} {t('forecast.daysLeft')}{rb ? ` · ${t('forecast.renewBy')} ${formatDateOnly(rb)}` : ''}{c.issuer_cn ? ` · ${c.issuer_cn}` : ''}</span>
            <span className="fc-exp-actions"><button type="button" className="btn btn-sm btn-secondary" onClick={() => onPlan(c, rb)}><CalendarPlus size={11} /> {t('forecast.planBtn')}</button></span>
          </li>)
      })}</ul>
      {rows.length === 0 && <div className="fc-no-data">{t('empty.hintFilter')}</div>}
      <PaginationBar {...pager} sizeOptions={[10, 25, 50]} />
    </ModalShell>
  )
}

function BarTooltip({ active, payload, t }) {
  if (!active || !payload?.length) return null
  const d = payload[0]?.payload
  if (!d) return null
  const all = [...(d.domains?.critical || []), ...(d.domains?.high || []), ...(d.domains?.warning || [])].map((c) => c.domain)
  return (
    <div className="fc-tooltip">
      <div className="fc-tt-date">{d.date}</div>
      {d.critical > 0 && <div className="fc-tt-row fc-tt-crit">{t('forecast.legCritical')}: <b>{d.critical}</b></div>}
      {d.high > 0 && <div className="fc-tt-row fc-tt-high">{t('forecast.legHigh')}: <b>{d.high}</b></div>}
      {d.warning > 0 && <div className="fc-tt-row fc-tt-warn">{t('forecast.legWarning')}: <b>{d.warning}</b></div>}
      <div className="fc-tt-row fc-tt-total">{t('forecast.ttDaily')}: <b>{d.total}</b> · {t('forecast.ttCumulative')}: <b>{d.cumulative}</b></div>
      {all.length > 0 && <div className="fc-tt-domains">{all.slice(0, 6).map((dm) => <div key={dm} className="fc-tt-domain">{dm}</div>)}{all.length > 6 && <div className="fc-tt-more">+{all.length - 6}</div>}</div>}
    </div>
  )
}

// ── Isı haritası (#11: hafta sonu / tatil gölgesi, gün modalı satır listesi) ────────────────────
function CalendarHeatmap({ certs, th, t, today, rangeDays, setRangeDays, onOpenDay }) {
  const [hovered, setHovered] = useState(null)
  const byDate = useMemo(() => {
    const map = {}
    for (const c of certs) {
      const key = expiryKey(c); if (!key) continue
      const diff = dayDiff(today, key); if (diff < 0 || diff >= rangeDays) continue
      const cls = classify(c, th); if (!['critical', 'high', 'warning', 'later'].includes(cls)) continue
      ;(map[key] ||= { critical: [], high: [], warning: [] })[cls === 'later' ? 'warning' : cls].push(c)
    }
    return map
  }, [certs, th, today, rangeDays])
  // Planlı yenileme günleri (ISSUE-008): plan modalı 'takvimde taralı çizilir' der — hücre kesik çerçeve + işaret alır
  const plannedByDate = useMemo(() => {
    const map = {}
    for (const c of certs) if (c.renewal_plan_state === 'planned' && c.renewal_planned_at) (map[c.renewal_planned_at] ||= []).push(c.domain)
    return map
  }, [certs])
  const start = new Date(today + 'T00:00:00')
  const dow = (start.getDay() + 6) % 7
  const weekStart = new Date(start); weekStart.setDate(weekStart.getDate() - dow)
  const weekCount = Math.ceil((dow + rangeDays) / 7)
  const cells = []
  for (let i = 0; i < weekCount * 7; i++) {
    const d = new Date(weekStart); d.setDate(d.getDate() + i)
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    const diff = dayDiff(today, key)
    const slot = byDate[key] || { critical: [], high: [], warning: [] }
    const list = [...slot.critical, ...slot.high, ...slot.warning]
    cells.push({ key, inRange: diff >= 0 && diff < rangeDays, count: list.length, list, slot, isToday: key === today, dayNum: d.getDate(), off: isWeekend(key) || isHoliday(key), holiday: isHoliday(key), planned: plannedByDate[key] || [] })
  }
  const DAYS = t('forecast.calDays').split(',')
  return (
    <>
      <div className="fc-range-filter">
        {RANGE_OPTIONS.map((d) => <button key={d} type="button" className={`fc-range-btn${rangeDays === d ? ' active' : ''}`} onClick={() => setRangeDays(d)}>{t('forecast.chartDays', d)}</button>)}
      </div>
      <div className="fc-heatmap-days">{DAYS.map((d) => <div key={d} className="fc-hm-day-label">{d}</div>)}</div>
      <div className={`fc-heatmap-grid${weekCount > 10 ? ' fc-heatmap-grid--compact' : ''}`} style={{ '--cal-aspect': `7 / ${Math.ceil((dow + 30) / 7)}` }}>
        {cells.map(({ key, inRange, count, list, slot, isToday, dayNum, off, holiday, planned }) => inRange ? (
          <button key={key} type="button"
            className={`fc-hm-cell fc-hm-cell-v2${count === 0 ? ' fc-hm-zero' : ''}${isToday ? ' fc-hm-today' : ''}${count > 0 ? ' fc-hm-clickable' : ''}${off ? ' fc-hm-off' : ''}${planned.length ? ' fc-hm-planned' : ''}`}
            style={{ backgroundColor: heatColor(count) }}
            onMouseEnter={() => setHovered(key)} onMouseLeave={() => setHovered(null)} onFocus={() => setHovered(key)} onBlur={() => setHovered(null)}
            onClick={count > 0 ? () => onOpenDay({ key, certs: list }) : undefined}
            aria-label={`${key} — ${t('forecast.certCount', count)}${holiday ? ` · ${t('forecast.holiday')}` : ''}`}
            title={holiday ? t('forecast.holiday') : off ? t('forecast.weekend') : count > 0 ? t('forecast.dayModalOpen') : undefined}>
            <span className="fc-hm-day-num">{dayNum}</span>
            {count > 0 && <span className="fc-hm-count-v2" style={{ color: countColor(slot) }}>{count}</span>}
            {planned.length > 0 && <span className="fc-hm-plan-mark" aria-label={t('forecast.hmPlanned', planned.join(', '))}><CalendarCheck size={12} aria-hidden="true" /></span>}
            {hovered === key && (
              <div className="fc-hm-tooltip">
                <strong>{key}</strong>
                <div>{t('forecast.certCount', count)}{holiday ? ` · ${t('forecast.holiday')}` : off ? ` · ${t('forecast.weekend')}` : ''}</div>
                {list.slice(0, 4).map((c) => <div key={c.domain} className="fc-hm-tdomain">{c.domain}</div>)}
                {list.length > 4 && <div>+{list.length - 4}</div>}
                {planned.length > 0 && <div className="fc-hm-tplan">{t('forecast.hmPlanned', planned.join(', '))}</div>}
              </div>
            )}
          </button>
        ) : <div key={key} className="fc-hm-cell fc-hm-empty" />)}
      </div>
      <div className="fc-hm-legend">
        <span className="fc-hm-leg-label">{t('forecast.legendLow')}</span>
        {HEAT_COLORS.slice(1).map((c) => <div key={c} className="fc-hm-leg-dot" style={{ background: c }} />)}
        <span className="fc-hm-leg-label">{t('forecast.legendHigh')}</span>
        <span className="fc-hm-leg-off">{t('forecast.legendOff')}</span>
        <span className="fc-hm-leg-planned"><CalendarCheck size={11} aria-hidden="true" /> {t('forecast.legendPlanned')}</span>
      </div>
    </>
  )
}

// ── Yenileme yükü — 12 ay (korundu) ──────────────────────────────────────────────────────────────
const LOAD_COLORS = ['#2563eb', '#16a34a', '#d97706', '#7c3aed', '#0891b2', '#db2777', '#94a3b8']
function computeRenewalLoad(certs, locale) {
  const now = new Date(); const months = []
  for (let i = 0; i < 12; i++) { const d = new Date(now.getFullYear(), now.getMonth() + i, 1); months.push({ key: `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`, label: d.toLocaleDateString(locale, { month: 'short', year: '2-digit' }) }) }
  const teamCount = new Map(); const byMonth = new Map(months.map((m) => [m.key, new Map()]))
  for (const c of certs) {
    const key = expiryKey(c); if (!key) continue
    const mk = key.slice(0, 7); if (!byMonth.has(mk)) continue
    const team = c.team_name || '—'
    byMonth.get(mk).set(team, (byMonth.get(mk).get(team) || 0) + 1); teamCount.set(team, (teamCount.get(team) || 0) + 1)
  }
  const top = [...teamCount.entries()].sort((a, b) => b[1] - a[1]).slice(0, 6).map(([n]) => n)
  const rows = months.map((m) => { const row = { month: m.label, total: 0 }; for (const [team, n] of byMonth.get(m.key)) { const k = top.includes(team) ? team : '__other'; row[k] = (row[k] || 0) + n; row.total += n } return row })
  const peak = rows.reduce((p, r) => (r.total > (p?.total ?? 0) ? r : p), null)
  return { rows, teams: top, hasOther: rows.some((r) => r.__other), total: rows.reduce((n, r) => n + r.total, 0), peak }
}
function RenewalLoadChart({ certs, t, locale }) {
  const data = useMemo(() => computeRenewalLoad(certs, locale), [certs, locale])
  return (
    <div className="fc-card fc-section-card fc-load">
      <div className="fc-sec-header">
        <span className="fc-sec-num">02b</span>
        <span className="fc-sec-title">{t('forecast.loadTitle')}</span>
        <span className="fc-load-sum">{data.total > 0 ? t('forecast.loadPeak', data.total, data.peak?.month ?? '—', data.peak?.total ?? 0) : t('forecast.loadNone')}</span>
      </div>
      <div className="fc-load-chart">
        <ResponsiveContainer width="100%" height={220}>
          <BarChart data={data.rows} margin={{ top: 6, right: 12, bottom: 0, left: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
            <XAxis dataKey="month" tick={{ fontSize: 11 }} />
            <YAxis allowDecimals={false} tick={{ fontSize: 11 }} width={28} />
            <RTooltip />
            <Legend wrapperStyle={{ fontSize: 11 }} />
            {data.teams.map((team, i) => <Bar key={team} dataKey={team} stackId="load" fill={LOAD_COLORS[i % LOAD_COLORS.length]} />)}
            {data.hasOther && <Bar dataKey="__other" name={t('forecast.loadOther')} stackId="load" fill={LOAD_COLORS[6]} />}
          </BarChart>
        </ResponsiveContainer>
      </div>
      <p className="fc-load-note">{t('forecast.loadNote')}</p>
    </div>
  )
}

// ── Sayfa ─────────────────────────────────────────────────────────────────────────────────────────
export default function ExpiryForecastPage({ onSelectDomain }) {
  const t = useT(); const toast = useToast()
  const time = useClock()
  const locale = dateLocale()
  const [data, setData] = useState(null)
  const [loadError, setLoadError] = useState(null)
  const [loading, setLoading] = useState(true)
  const [filters, setFilters] = useState(() => paramsToFilters(readUrlParam))
  const [chartRange, setChartRange] = useState(30)
  const [calRange, setCalRange] = useState(30)
  const [calView, setCalView] = useState('heat')     // heat | month
  const [listRange, setListRange] = useState(30)
  const [showAll, setShowAll] = useState(false)
  const [dayModal, setDayModal] = useState(null)
  const [planRow, setPlanRow] = useState(null)
  const [busyDomain, setBusyDomain] = useState(null)
  const today = todayKey()

  const load = useCallback(async () => {
    try {
      const r = await api.getForecast()
      if (r?.success && r.data) { setData(r.data); setLoadError(null) } else setLoadError(r?.error || t('forecast.loadError'))
    } catch (e) { setLoadError(e?.message || t('forecast.loadError')) }
    finally { setLoading(false) }
  }, [t])
  useVisibleInterval(load, 300_000, true)   // #4: 5 dk'da bir görünürken tazelenir
  useUrlQuerySync(filtersToParams(filters))

  const th = useMemo(() => data?.thresholds || { warning: 30, high: 15, critical: 7 }, [data])
  const allCerts = useMemo(() => data?.certs || [], [data])
  const certs = useMemo(() => applyFilters(allCerts, filters), [allCerts, filters])
  const kpi = useMemo(() => computeKpis(certs, th, today), [certs, th, today])
  const chartData = useMemo(() => dailySeries(certs, th, chartRange, today, locale), [certs, th, chartRange, today, locale])
  const teams = useMemo(() => byTeam(certs, th, chartRange, today), [certs, th, chartRange, today])
  /** Günlük yoğunluk çubuğu/sütunu → o günün listesi (gün modali). payload = dailySeries satırı. */
  const openChartDay = (d) => {
    if (!d || !d.date || !(d.total > 0)) return
    setDayModal({ key: d.date, certs: [...(d.domains?.critical || []), ...(d.domains?.high || []), ...(d.domains?.warning || [])] })
  }
  const list = useMemo(() => upcoming(certs, th, listRange, today), [certs, th, listRange, today])
  const batchList = useMemo(() => batches(certs, th, listRange, today), [certs, th, listRange, today])
  const shared = useMemo(() => coverage(certs), [certs])
  const issuers = useMemo(() => byIssuer(certs, th, listRange, today), [certs, th, listRange, today])
  const next = useMemo(() => nextExpiry(certs, today), [certs, today])
  const teamOpts = useMemo(() => [...new Map(allCerts.filter((c) => c.team_id != null).map((c) => [String(c.team_id), c.team_name || `#${c.team_id}`])).entries()].map(([value, label]) => ({ value, label })), [allCerts])
  const groupOpts = useMemo(() => [...new Set(allCerts.map((c) => c.group_name).filter(Boolean))].sort().map((g) => ({ value: g, label: g })), [allCerts])
  // Yenileme geçmişi sayfa süzgecini izler (ISSUE-010): olaylar süzülen alanlara indirgenir, oran/aylar yeniden sayılır.
  // Süzgeç yokken sunucu toplamları (olay listesi 50 ile kısıtlı) olduğu gibi kullanılır.
  const renewals = useMemo(() => {
    const raw = data?.renewals || { on_time: 0, late: 0, months: [], events: [] }
    if (!Object.values(filters).some(Boolean)) return raw
    const allow = new Set(certs.map((c) => c.domain))
    const events = (raw.events || []).filter((e) => allow.has(e.domain))
    const months = {}; let on_time = 0, late = 0
    for (const e of events) {
      e.on_time ? on_time++ : late++
      const m = (localDayKey(e.renewed_at) || '').slice(0, 7)
      ;(months[m] ||= { month: m, on_time: 0, late: 0 })[e.on_time ? 'on_time' : 'late']++
    }
    return { ...raw, on_time, late, events, months: Object.values(months).sort((a, b) => a.month.localeCompare(b.month)) }
  }, [data, certs, filters])
  const onTimePct = renewals.on_time + renewals.late > 0 ? Math.round(renewals.on_time * 100 / (renewals.on_time + renewals.late)) : null
  const filterActive = !!(filters.team || filters.ugTeam || filters.tier || filters.group)

  // Ay görünümü gezinebilir: 03 tablosunun 30 günlük penceresine değil, 12 aya bağlı (ISSUE-013)
  const monthList = useMemo(() => upcoming(certs, th, 365, today), [certs, th, today])
  const monthEvents = useMemo(() => [
    ...monthList.filter((r) => r.renew_by_key || r.expiry_key).map((r) => ({
      date: r.renew_by_key || r.expiry_key, label: r.domain, title: `${r.domain} · ${t('forecast.csvRenewBy')} ${r.renew_by_key || '—'} · ${t('forecast.csvExpiry')} ${r.expiry_key || '—'}`,
      tone: r.cls === 'overdue' || r.cls === 'critical' ? 'bad' : r.cls === 'high' || r.window === 'late' ? 'warn' : 'info', onClick: () => onSelectDomain?.(r.domain),
    })),
    // Alan adı (registrar) bitişleri de takvimde — ayrı ton/etiket (2026-09-22, F)
    ...(data?.domains || []).filter((d) => d.expiry_date).map((d) => ({
      date: String(d.expiry_date).slice(0, 10), label: '🌐 ' + d.domain, title: `${d.domain} · ${t('forecast.domTitle')} · ${String(d.expiry_date).slice(0, 10)}`,
      tone: d.days_remaining != null && d.days_remaining <= (d.critical_days ?? 7) ? 'bad' : d.days_remaining != null && d.days_remaining <= (d.warning_days ?? 30) ? 'warn' : 'info',
      onClick: () => navigateTo('domain', { monitor: d.id }),
    })),
    // Planlı yenileme günü de olay (ISSUE-008)
    ...certs.filter((c) => c.renewal_plan_state === 'planned' && c.renewal_planned_at).map((c) => ({
      date: c.renewal_planned_at, label: c.domain, title: `${c.domain} · ${t('forecast.hmPlanned', c.renewal_planned_at)}`, tone: 'ok', onClick: () => onSelectDomain?.(c.domain),
    })),
  ], [monthList, certs, data, onSelectDomain, t])

  async function checkNow(domain) {
    setBusyDomain(domain)
    try { const r = await api.refreshCertificateHealth(domain); if (r?.success) { toast.success(t('inv.checkNowOk', domain)); load() } else toast.error(r?.error || t('inv.checkNowErr')) }
    catch (e) { toast.error(e?.message || t('inv.checkNowErr')) } finally { setBusyDomain(null) }
  }
  function applyPlan(p) {
    setData((d) => d ? { ...d, certs: d.certs.map((c) => c.domain === p.domain ? { ...c, renewal_planned_at: p.renewal_planned_at, renewal_planned_by: p.renewal_planned_by, renewal_planned_note: p.renewal_planned_note, renewal_plan_state: p.renewal_planned_at ? 'planned' : 'none' } : c) } : d)
    setPlanRow(null)
  }
  function exportIcs() { downloadIcs('renewal-plan.ics', buildIcs(icsEvents(list, t), { calName: t('forecast.icsCal') })) }
  function exportCsv() {
    try {
      const rows = csvRows(list, t)
      const csv = '\uFEFF' + rows.map((r) => r.map(csvCell).join(',')).join('\r\n')   // escaping + formula neutralisation in one place (utils/csv.js)
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' }); const url = URL.createObjectURL(blob)
      const a = document.createElement('a'); a.href = url; a.download = `renewal-plan-${today}.csv`; document.body.appendChild(a); a.click(); a.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch { /* jsdom */ }
  }
  async function copyLink() { try { await navigator.clipboard.writeText(window.location.href); toast.success(t('inv.copied')) } catch { toast.error(t('inv.copyFailed')) } }

  const timeStr = time.toLocaleTimeString(locale, { hour12: false })
  const clsLabel = (cls) => t(`forecast.cls.${cls}`)
  const visible = showAll ? list : list.slice(0, 15)
  const hintNext = next ? t('forecast.nextExpiry', next.domain, next.days) : t('forecast.noneAhead')

  return (
    <div className="forecast-page">
      <header className="fc-header">
        <div className="fc-header-left">
          <BrandLogo status={kpi.overdue + kpi.unreachable > 0 ? 'error' : 'ok'} size={34} />
          <div>
            <div className="fc-brand-name">{t('forecast.brand')}</div>
            <div className="fc-brand-sub">{t('forecast.brandSub')} · {(data?.environment || '').toUpperCase() || '…'}</div>
          </div>
        </div>
        <div className="fc-header-right">
          <div className="fc-live-row"><span className="fc-live-dot" />{t('forecast.clock')} · {timeStr}</div>
          <div className="fc-snapshot">
            {t('forecast.dataAsOf')} · {data?.data_as_of ? formatDate(data.data_as_of) : '—'}
            <button type="button" className="fc-refresh" onClick={load} title={t('forecast.refresh')} aria-label={t('forecast.refresh')}><RefreshCw size={12} /></button>
          </div>
        </div>
      </header>
      <div className="fc-header-sep" />

      {loadError && <AlertBanner tone="danger" title={t('forecast.loadError')} role="alert">{String(loadError)}{data ? ` · ${t('forecast.staleShown')}` : ''}</AlertBanner>}
      {loading && !data && <LoadingBlock label={t('forecast.loading')} className="forecast-loading" />}

      {data && (
        <>
          {/* ── Süzgeç (#2) ── */}
          <div className="fc-filters no-print" role="group" aria-label={t('forecast.filters')}>
            <label className="fc-f"><span>{t('inv.filterTeam')}</span><SearchableSelect value={filters.team} onChange={(v) => setFilters((f) => ({ ...f, team: v }))} options={[{ value: '', label: t('inv.filterAny') }, ...teamOpts]} searchThreshold={4} /></label>
            <label className="fc-f"><span>{t('inv.filterUgTeam')}</span><SearchableSelect value={filters.ugTeam} onChange={(v) => setFilters((f) => ({ ...f, ugTeam: v }))} options={[{ value: '', label: t('inv.filterAny') }, ...teamOpts]} searchThreshold={4} /></label>
            <label className="fc-f"><span>{t('inv.filterTier')}</span><SearchableSelect value={filters.tier} onChange={(v) => setFilters((f) => ({ ...f, tier: v }))} options={[{ value: '', label: t('inv.filterAny') }, { value: '1', label: 'T1' }, { value: '2', label: 'T2' }, { value: '3', label: 'T3' }, { value: '4', label: 'T4' }, { value: 'none', label: t('inv.tierNone') }]} /></label>
            <label className="fc-f"><span>{t('inv.filterGroup')}</span><SearchableSelect value={filters.group} onChange={(v) => setFilters((f) => ({ ...f, group: v }))} options={[{ value: '', label: t('inv.filterAny') }, ...groupOpts]} /></label>
            <span className="fc-f-count">{t('inv.shownOf', certs.length, allCerts.length)}</span>
            {filterActive && <button type="button" className="btn btn-sm btn-secondary" onClick={() => setFilters({ ...EMPTY_FILTERS })}>{t('inv.filterClear')}</button>}
            <span className="invtb-spacer" />
            <span className="fc-th-note" title={t('forecast.thresholdTip')}>{t('forecast.thresholdNote', th.critical, th.high, th.warning)} · {t('forecast.leadNote', data.lead_days?.default ?? 14)}</span>
          </div>

          {/* ── KPI (#1 overdue, #3 pencere, #10 oran) ── */}
          <div className="fc-kpi2-grid fc-kpi2-grid--6">
            <KpiCard2 range={t('forecast.rangeOverdue')} value={kpi.overdue + kpi.unreachable} color={SEV_COLORS.overdue} sub={t('forecast.subOverdue', kpi.overdue, kpi.unreachable)} hint={t('forecast.subOverdueOk')} />
            <KpiCard2 range={t('forecast.rangeCrit', th.critical)} value={kpi.critical} color="#dc2626" sub={t('forecast.sub7')} hint={hintNext} />
            <KpiCard2 range={t('forecast.rangeHigh', th.critical + 1, th.high)} value={kpi.high} color="#ea580c" sub={t('forecast.sub14')} hint={hintNext} />
            <KpiCard2 range={t('forecast.rangeWarn', th.high + 1, th.warning)} value={kpi.warning} color="#f59e0b" sub={t('forecast.sub30')} hint={hintNext} />
            <KpiCard2 range={t('forecast.rangeLate')} value={kpi.late} color="#b45309" sub={t('forecast.subLate', kpi.planned)} hint={t('forecast.subLateOk')} />
            <KpiCard2 range={t('forecast.rangeOnTime')} value={onTimePct ?? 0} color="#14b8a6" sub={onTimePct == null ? t('forecast.subOnTimeNone') : t('forecast.subOnTime', renewals.on_time, renewals.late, renewals.window_days ?? 90)} />
          </div>

          {/* ── 01 Günlük yoğunluk + 04 takıma göre (#7) ── */}
          <div className="fc-row-2col">
            <div className="fc-card fc-section-card">
              <div className="fc-sec-header">
                <span className="fc-sec-num">01</span>
                <span className="fc-sec-title">{t('forecast.secChart')}</span>
                <div className="fc-range-filter">{RANGE_OPTIONS.map((d) => <button key={d} type="button" className={`fc-range-btn${chartRange === d ? ' active' : ''}`} onClick={() => setChartRange(d)}>{t('forecast.chartDays', d)}</button>)}</div>
                <span className="fc-sec-legend">
                  <span className="fc-leg-dot" style={{ background: '#dc2626' }} />{t('forecast.legCritical')}
                  <span className="fc-leg-dot" style={{ background: '#ea580c' }} />{t('forecast.legHigh')}
                  <span className="fc-leg-dot" style={{ background: '#f59e0b' }} />{t('forecast.legWarning')}
                </span>
              </div>
              {chartData.every((d) => d.total === 0)
                ? <StatusBlock tone="success" title={t('forecast.noneInRange', chartRange)} description={hintNext} />
                : (<>
                  <ChartInsight data={chartData} t={t} onOpenDay={setDayModal} />
                  <ResponsiveContainer width="100%" height={280}>
                    {/* Güne tıkla → o günün sertifika listesi (ısı haritasıyla aynı modal; 2026-09-18) */}
                    <ComposedChart data={chartData} margin={{ top: 4, right: 20, bottom: 0, left: 0 }} style={{ cursor: 'pointer' }}
                      onClick={(st) => { const i = [st?.activeTooltipIndex, st?.activeIndex].map(Number).find(Number.isInteger); openChartDay(st?.activePayload?.[0]?.payload ?? (i != null ? chartData[i] : null)) }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#1e3a5f" />
                      <XAxis dataKey="label" tick={{ fill: '#64748b', fontSize: 10 }} interval={Math.max(0, Math.floor(chartRange / 6))} />
                      <YAxis yAxisId="left" allowDecimals={false} tick={{ fill: '#94a3b8', fontSize: 10 }} width={26} />
                      <YAxis yAxisId="right" orientation="right" allowDecimals={false} tick={{ fill: '#3b82f6', fontSize: 10 }} width={36} />
                      <RTooltip content={<BarTooltip t={t} />} cursor={{ fill: 'rgba(255,255,255,.04)' }} />
                      {/* Recharts 3: grafik-seviyesi onClick her tıklamada activePayload vermiyor (kullanıcı bildirimi
                          2026-09-18: "çubuklara tıklanmıyor") → çubukların KENDİ onClick'i birincil, grafik yedek. */}
                      <Bar yAxisId="left" dataKey="critical" stackId="s" fill="#dc2626" name={t('forecast.legCritical')} onClick={(d) => openChartDay(d?.payload ?? d)} cursor="pointer" />
                      <Bar yAxisId="left" dataKey="high" stackId="s" fill="#ea580c" name={t('forecast.legHigh')} onClick={(d) => openChartDay(d?.payload ?? d)} cursor="pointer" />
                      <Bar yAxisId="left" dataKey="warning" stackId="s" fill="#f59e0b" name={t('forecast.legWarning')} radius={[3, 3, 0, 0]} onClick={(d) => openChartDay(d?.payload ?? d)} cursor="pointer" />
                      {/* Kümülatif çizgi çubukların ÜSTÜNDE çiziliyor ve tıklamayı yutuyordu (tarayıcıda görüldü:
                          elementFromPoint → .recharts-line-curve). Dekoratif: fare olaylarını almasın. */}
                      <Line yAxisId="right" type="monotone" dataKey="cumulative" stroke="#3b82f6" strokeWidth={2} dot={false} name={t('forecast.ttCumulative')} style={{ pointerEvents: 'none' }} />
                    </ComposedChart>
                  </ResponsiveContainer>
                  <div className="fc-chart-hint">{t('forecast.chartClickHint')}</div>
                </>)}
            </div>
            <div className="fc-card fc-section-card">
              <div className="fc-sec-header"><span className="fc-sec-num">04</span><span className="fc-sec-title">{t('forecast.teamTitle', chartRange)}</span></div>
              {teams.length === 0 ? <div className="fc-no-data">{t('forecast.noTeamData')}</div> : (
                <>
                  <ResponsiveContainer width="100%" height={170}>
                    <PieChart>
                      <Pie data={teams.map((r) => ({ name: r.name || t('inv.teamNoTeam'), value: r.total }))} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius={64} innerRadius={36} paddingAngle={2}>
                        {teams.map((_, i) => <Cell key={i} fill={LOAD_COLORS[i % LOAD_COLORS.length]} />)}
                        <Label value={teams.reduce((n, r) => n + r.total, 0)} position="center" fill="#e2e8f0" fontSize={18} fontWeight={800} />
                      </Pie>
                      <RTooltip formatter={(v, n) => [v, n]} contentStyle={{ background: '#1e293b', border: '1px solid #334155', borderRadius: 6, color: '#e2e8f0', fontSize: 12 }} />
                    </PieChart>
                  </ResponsiveContainer>
                  {/* Hücreler tıklanır (2026-09-18): sayı → o takım+kova sertifika listesi (gün modaliyle aynı yüzey) */}
                  <table className="fc-team-table fc-team-table--grid">
                    <thead><tr>
                      <th>{t('inv.colTeam')}</th>
                      <th className="fc-num" title={t('forecast.cls.overdue')}>{t('forecast.cls.overdue')}</th>
                      <th className="fc-num" title={t('forecast.legCritical')}>≤{th.critical}</th>
                      <th className="fc-num" title={t('forecast.legHigh')}>≤{th.high}</th>
                      <th className="fc-num" title={t('forecast.legWarning')}>≤{th.warning}</th>
                      <th className="fc-num">&gt;{th.warning}</th>
                      <th className="fc-num">{t('forecast.rangeLate')}</th>
                      <th className="fc-num">{t('forecast.colTotal')}</th>
                      <th></th>
                    </tr></thead>
                    <tbody>{teams.map((r) => {
                      const teamLabel = r.name || t('inv.teamNoTeam')
                      const cell = (bucket, value, cls = '') => (
                        <td className={`fc-num${cls ? ' ' + cls : ''}`}>
                          {value > 0
                            ? <button type="button" className="fc-cell-btn" title={t('forecast.cellOpen', teamLabel)}
                                onClick={() => setDayModal({ title: `${teamLabel} · ${bucketLabel(bucket, th, t)} (${value})`, certs: teamBucketCerts(certs, th, chartRange, r.id, bucket, today) })}>{value}</button>
                            : <span className="fc-zero">0</span>}
                        </td>)
                      return (
                        <tr key={r.id} className={filters.team === r.id ? 'is-on' : ''}>
                          <td>{r.name ? <TeamBadge teamId={Number(r.id)} teamName={r.name} /> : <span className="inv-warn-text">{t('inv.teamNoTeam')}</span>}</td>
                          {cell('overdue', r.overdue + r.unreachable, r.overdue + r.unreachable > 0 ? 'is-bad' : '')}
                          {cell('critical', r.critical, r.critical > 0 ? 'is-crit' : '')}
                          {cell('high', r.high, r.high > 0 ? 'is-high' : '')}
                          {cell('warning', r.warning)}
                          {cell('later', r.later, 'inv-dim')}
                          {cell('late', r.late, r.late > 0 ? 'is-warn' : '')}
                          {cell('total', r.total, 'is-total')}
                          <td>{r.id !== 'none' && <button type="button" className="btn btn-sm btn-secondary" onClick={() => setFilters((f) => ({ ...f, team: f.team === r.id ? '' : r.id }))}>{filters.team === r.id ? t('inv.filterClear') : t('forecast.teamFilter')}</button>}</td>
                        </tr>)
                    })}</tbody>
                  </table>
                </>
              )}
            </div>
          </div>

          {/* ── 02 Takvim (#11) ── */}
          <div className="fc-card fc-section-card">
            <div className="fc-sec-header">
              <span className="fc-sec-num">02</span>
              <span className="fc-sec-title">{t('forecast.secCalendar')}</span>
              <div className="seg-ctl no-print" role="group" aria-label={t('forecast.calView')}>
                <button type="button" className={`seg-ctl-btn${calView === 'heat' ? ' active' : ''}`} onClick={() => setCalView('heat')} aria-pressed={calView === 'heat'}>{t('forecast.calHeat')}</button>
                <button type="button" className={`seg-ctl-btn${calView === 'month' ? ' active' : ''}`} onClick={() => setCalView('month')} aria-pressed={calView === 'month'}>{t('forecast.calMonth')}</button>
              </div>
            </div>
            {calView === 'heat'
              ? <CalendarHeatmap certs={certs} th={th} t={t} today={today} rangeDays={calRange} setRangeDays={setCalRange} onOpenDay={setDayModal} />
              : <MonthCalendar events={monthEvents} ariaLabel={t('forecast.calMonth')} />}
          </div>

          <RenewalLoadChart certs={certs} t={t} locale={locale} />

          {/* ── 03 Yaklaşan + eylemler (#1 #3 #6 #9 #12) ── */}
          <div className="fc-card fc-section-card">
            <div className="fc-sec-header">
              <span className="fc-sec-num">03</span>
              <span className="fc-sec-title">{t('forecast.secList')}</span>
              <span className="fc-sec-badge">{t('forecast.certCount', list.length)}</span>
              <div className="fc-range-filter">{RANGE_OPTIONS.map((d) => <button key={d} type="button" className={`fc-range-btn${listRange === d ? ' active' : ''}`} onClick={() => setListRange(d)}>{t('forecast.chartDays', d)}</button>)}</div>
              <div className="fc-actions no-print">
                <button type="button" className="btn btn-sm btn-secondary" disabled={!list.length} onClick={exportIcs} title={t('renewal.icsTip')}><Download size={12} /> ICS</button>
                <button type="button" className="btn btn-sm btn-secondary" disabled={!list.length} onClick={exportCsv}><Download size={12} /> CSV</button>
                <button type="button" className="btn btn-sm btn-secondary" onClick={copyLink}><Link2 size={12} /> {t('inv.copyLink')}</button>
                <button type="button" className="btn btn-sm btn-secondary" onClick={() => window.print()}><Printer size={12} /> {t('forecast.print')}</button>
              </div>
            </div>
            {batchList.length > 0 && (
              <div className="fc-batches">
                {batchList.map((b) => <span key={b.date} className="fc-batch" title={b.domains.join(', ')}>{t('forecast.batch', formatDateOnly(b.date), b.count, b.issuers.join(' / ') || '—')}</span>)}
              </div>
            )}
            <div className="fc-expiry-list">
              {visible.map((r) => (
                <div key={r.domain} className={`fc-exp-row fc-exp-row--${r.cls}${r.window === 'late' ? ' is-late' : ''}`}>
                  <span className="fc-exp-sev-dot" style={{ background: SEV_COLORS[r.cls] }} />
                  <button type="button" className="fc-exp-domain inv-domain" onClick={() => onSelectDomain?.(r.domain)}>{r.domain}</button>
                  {r.tier && <span className={`tier-badge tier-badge-${r.tier}`}>T{r.tier}</span>}
                  <span className={`fc-exp-cls fc-exp-cls--${r.cls}`}>{r.cls === 'unreachable' ? <ShieldOff size={11} /> : r.cls === 'overdue' ? <AlertTriangle size={11} /> : null} {clsLabel(r.cls)}</span>
                  <span className="fc-exp-date" title={t('forecast.csvExpiry')}>{r.expiry_key ? formatDateOnly(r.expiry_key) : '—'}{r.cls === 'unreachable' && r.error ? ` · ${r.error}` : ''}</span>
                  <span className="fc-exp-renew" title={t('forecast.renewByTip', r.lead_days ?? '')}>
                    {r.renew_by_key ? <>{t('forecast.renewBy')} <b>{formatDateOnly(r.renew_by_key)}</b>{(isWeekend(r.renew_by_key) || isHoliday(r.renew_by_key)) && <span className="fc-exp-offday" title={t('forecast.offDayTip', formatDateOnly(lastBusinessDay(r.renew_by_key)))}>→ {formatDateOnly(lastBusinessDay(r.renew_by_key))}</span>}</> : null}
                  </span>
                  <span className="fc-exp-days" style={{ color: SEV_COLORS[r.cls] }}>{r.days_remaining == null ? '—' : r.days_remaining < 0 ? t('inv.expiredAgo', -r.days_remaining) : `${r.days_remaining} ${t('forecast.daysLeft')}`}</span>
                  {r.renewal_plan_state === 'planned' && <span className="fc-exp-plan is-planned" title={r.renewal_planned_note || ''}>{t('forecast.planned', formatDateOnly(r.renewal_planned_at))}</span>}
                  {r.renewal_plan_state === 'done' && <span className="fc-exp-plan is-done">{t('forecast.planDone')}</span>}
                  <span className="fc-exp-actions no-print">
                    <button type="button" className="btn btn-sm btn-secondary" disabled={busyDomain === r.domain} onClick={() => checkNow(r.domain)} title={t('inv.checkNow')} aria-label={t('inv.checkNow')}><Play size={11} /></button>
                    <button type="button" className="btn btn-sm btn-secondary" onClick={() => setPlanRow(r)} title={t('forecast.planTitle', r.domain)} aria-label={t('forecast.planBtn')}><CalendarPlus size={11} /></button>
                  </span>
                </div>
              ))}
              {!showAll && list.length > 15 && <button type="button" className="fc-show-more" onClick={() => setShowAll(true)}>+{list.length - 15} {t('forecast.showMore')}</button>}
              {list.length === 0 && <StatusBlock tone="success" title={t('forecast.noneInRange', listRange)} description={hintNext} actions={next && next.days >= listRange ? <button type="button" className="btn btn-sm btn-secondary" onClick={() => setListRange(90)}>{t('forecast.widen', 90)}</button> : null} />}
            </div>
          </div>

          {/* ── 05 Zeka: veren · kapsama (#8) · 06 zamanında yenileme (#10) ── */}
          <div className="fc-row-2col">
            <div className="fc-card fc-section-card">
              <div className="fc-sec-header"><span className="fc-sec-num">05</span><span className="fc-sec-title">{t('forecast.intelTitle')}</span></div>
              <div className="fc-intel">
                <div className="fc-intel-block">
                  <div className="fc-intel-h">{t('forecast.issuerTitle', listRange)}</div>
                  {issuers.length === 0 ? <span className="inv-muted">—</span> : <div className="fc-chips">{issuers.map((i) => <span key={i.issuer} className="fc-chip"><b>{i.count}</b> {i.issuer}</span>)}</div>}
                </div>
                <div className="fc-intel-block">
                  <div className="fc-intel-h">{t('forecast.coverageTitle')}</div>
                  {shared.length === 0 ? <span className="inv-muted">{t('forecast.coverageNone')}</span> : (
                    <ul className="fc-intel-list">{shared.slice(0, 6).map((s, i) => <li key={i}>{t('forecast.coverageRow', s.count)} <span className="inv-dim">{s.domains.join(', ')}</span></li>)}</ul>
                  )}
                </div>
              </div>
            </div>
            <div className="fc-card fc-section-card">
              <div className="fc-sec-header"><span className="fc-sec-num">06</span><span className="fc-sec-title">{t('forecast.onTimeTitle', renewals.window_days ?? 90)}</span></div>
              {(renewals.on_time + renewals.late) === 0 ? <div className="fc-no-data">{t('forecast.subOnTimeNone')}</div> : (
                <>
                  <ResponsiveContainer width="100%" height={120}>
                    <BarChart data={renewals.months} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
                      <XAxis dataKey="month" tick={{ fontSize: 10 }} /><YAxis allowDecimals={false} tick={{ fontSize: 10 }} width={24} /><RTooltip />
                      <Bar dataKey="on_time" name={t('forecast.onTime')} stackId="r" fill="#16a34a" /><Bar dataKey="late" name={t('forecast.lateRenewal')} stackId="r" fill="#dc2626" />
                    </BarChart>
                  </ResponsiveContainer>
                  <ul className="fc-intel-list">{(renewals.events || []).slice(0, 6).map((e, i) => (
                    <li key={i}><span className={`fc-exp-cls ${e.on_time ? 'fc-exp-cls--warning' : 'fc-exp-cls--overdue'}`}>{e.on_time ? t('forecast.onTime') : t('forecast.lateRenewal')}</span> {e.domain} <span className="inv-dim">· {formatDateOnly(e.renewed_at)}{e.renew_by ? ` · ${t('forecast.renewBy')} ${formatDateOnly(e.renew_by)}` : ''}</span></li>
                  ))}</ul>
                </>
              )}
            </div>
          </div>
          {/* ── 07 Alan adı (registrar) bitişleri — sertifika serisinden AYRI (2026-09-22, F) ── */}
          <ForecastDomainsPanel domains={data?.domains || []} t={t} num="07" />
        </>
      )}

      {dayModal && (
        <DayListModal modal={dayModal} th={th} t={t} onClose={() => setDayModal(null)}
          onSelectDomain={onSelectDomain} onPlan={(c, rb) => setPlanRow({ ...c, expiry_key: expiryKey(c), renew_by_key: rb })} />
      )}
      {planRow && <PlanModal row={planRow} onClose={() => setPlanRow(null)} onSaved={applyPlan} onCleared={applyPlan} />}
    </div>
  )
}
