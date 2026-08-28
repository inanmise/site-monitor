import { useState, useEffect, useRef, useMemo } from 'react'
import {
  ComposedChart, Bar, Line, XAxis, YAxis, CartesianGrid,
  Tooltip as RTooltip, ResponsiveContainer,
  PieChart, Pie, Cell, Label,
  Legend,
} from 'recharts'
import { Calendar } from 'lucide-react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import BrandLogo from '../components/BrandLogo.jsx'
import CertificateCard from '../components/CertificateCard.jsx'
import { LoadingBlock } from '../components/ui/Progress.jsx'

// ── Helpers ────────────────────────────────────────────────────────────────────

function localDateStr(d) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function useClock() {
  const [time, setTime] = useState(new Date())
  useEffect(() => {
    const id = setInterval(() => setTime(new Date()), 1000)
    return () => clearInterval(id)
  }, [])
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
      const eased = 1 - Math.pow(1 - t, 3)
      setVal(Math.round(eased * target))
      if (t < 1) rafRef.current = requestAnimationFrame(animate)
    }
    rafRef.current = requestAnimationFrame(animate)
    return () => { if (rafRef.current) cancelAnimationFrame(rafRef.current) }
  }, [target, duration])
  return val
}

// ── Data computation ───────────────────────────────────────────────────────────

/** O7: backend UTC yazar ama 'Z' eki koymaz; JS zone-eksiz datetime'ı YEREL sayar. İstanbul'da
 *  (UTC+3) UTC gününün son 3 saatinde dolan sertifikalar takvim/bar/liste'de bir gün erken
 *  görünüyordu. Kural client.js toUtc ile aynı: zone bilgisi yoksa 'Z' ekle. */
function parseApiDate(s) {
  if (!s) return new Date(NaN)
  const str = String(s)
  const hasZone = str.endsWith('Z') || /[+-]\d{2}:?\d{2}$/.test(str)
  return new Date(hasZone || !str.includes('T') ? str : str + 'Z')
}

function computeForecast(certs) {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const in30 = new Date(today)
  in30.setDate(in30.getDate() + 30)

  const byDate = {}
  const upcomingList = []

  certs.forEach(cert => {
    if (!cert.not_after) return
    const d = parseApiDate(cert.not_after)
    if (isNaN(d.getTime())) return
    if (d < today || d > in30) return
    if ((cert.days_remaining ?? -1) < 0) return
    const key = localDateStr(d)
    const days = cert.days_remaining ?? 999
    const sev = days <= 7 ? 'critical' : days <= 14 ? 'high' : 'warning'
    if (!byDate[key]) byDate[key] = { critical: [], high: [], warning: [] }
    byDate[key][sev].push(cert.domain)
    upcomingList.push({ domain: cert.domain, date: key, severity: sev, days })
  })

  upcomingList.sort((a, b) => a.days - b.days)

  let cumulative = 0
  const dailyData = []
  for (let i = 0; i < 30; i++) {
    const d = new Date(today)
    d.setDate(d.getDate() + i)
    const key = localDateStr(d)
    const slot = byDate[key] || { critical: [], high: [], warning: [] }
    const total = slot.critical.length + slot.high.length + slot.warning.length
    cumulative += total
    dailyData.push({
      date: key,
      label: d.toLocaleDateString('tr-TR', { day: '2-digit', month: 'short' }),
      critical: slot.critical.length,
      high: slot.high.length,
      warning: slot.warning.length,
      total,
      cumulative,
      domains: slot,
    })
  }

  const criticalCount = dailyData.reduce((s, d) => s + d.critical, 0)
  const highCount     = dailyData.reduce((s, d) => s + d.high, 0)
  const warningCount  = dailyData.reduce((s, d) => s + d.warning, 0)

  return { dailyData, byDate, counts: { critical: criticalCount, high: highCount, warning: warningCount }, upcomingList }
}

const CHART_RANGE_OPTIONS = [30, 45, 60, 90]
const CALENDAR_RANGE_OPTIONS = [30, 45, 60, 90]

function computeChartData(certs, days) {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const inEnd = new Date(today)
  inEnd.setDate(inEnd.getDate() + days)

  const byDate = {}
  certs.forEach(cert => {
    if (!cert.not_after) return
    const d = parseApiDate(cert.not_after)
    if (isNaN(d.getTime())) return
    if (d < today || d > inEnd) return
    if ((cert.days_remaining ?? -1) < 0) return
    const key = localDateStr(d)
    const remDays = cert.days_remaining ?? 999
    const sev = remDays <= 7 ? 'critical' : remDays <= 14 ? 'high' : 'warning'
    if (!byDate[key]) byDate[key] = { critical: [], high: [], warning: [] }
    byDate[key][sev].push(cert.domain)
  })

  let cumulative = 0
  const out = []
  for (let i = 0; i < days; i++) {
    const d = new Date(today)
    d.setDate(d.getDate() + i)
    const key = localDateStr(d)
    const slot = byDate[key] || { critical: [], high: [], warning: [] }
    const total = slot.critical.length + slot.high.length + slot.warning.length
    cumulative += total
    out.push({
      date: key,
      label: d.toLocaleDateString('tr-TR', { day: '2-digit', month: 'short' }),
      critical: slot.critical.length,
      high: slot.high.length,
      warning: slot.warning.length,
      total,
      cumulative,
      domains: slot,
    })
  }
  return out
}

const PIE_COLORS = ['#3b82f6','#8b5cf6','#ec4899','#14b8a6','#f59e0b','#10b981','#ef4444','#6366f1','#f97316','#06b6d4']

function computePie(teamStats, kpi) {
  if (teamStats?.mode === 'all_teams' && Array.isArray(teamStats.teams)) {
    return teamStats.teams
      .map((t, i) => ({
        name: t.team_name || `Team ${t.team_id}`,
        value: t.sy_stats?.total_certificates ?? 0,
        color: PIE_COLORS[i % PIE_COLORS.length],
      }))
      .filter(t => t.value > 0)
  }
  if (teamStats) {
    const name = teamStats.team_name || 'Takım'
    const sy = teamStats.sy_stats?.total_certificates ?? 0
    if (sy > 0) {
      return [{ name: `${name} (SY)`, value: sy, color: '#3b82f6' }]
    }
  }
  if (!kpi) return []
  return [
    { name: 'Critical', value: kpi.critical_count ?? 0, color: '#dc2626' },
    { name: 'High',     value: kpi.high_count     ?? 0, color: '#ea580c' },
    { name: 'Warning',  value: kpi.warning_count  ?? 0, color: '#f59e0b' },
    { name: 'Valid',    value: kpi.valid_count     ?? 0, color: '#22c55e' },
    { name: 'Error',    value: kpi.error_count     ?? 0, color: '#ef4444' },
  ].filter(d => d.value > 0)
}

const SEV_COLORS = { critical: '#f87171', high: '#fb923c', warning: '#fbbf24' }

// ── KPI Card 2×2 ──────────────────────────────────────────────────────────────

function KpiCard2({ range, value, color, sub }) {
  const count = useCountUp(value ?? 0)
  return (
    <div className="fc-card fc-kpi2">
      <div className="fc-stripe" style={{ background: color }} />
      <div className="fc-kpi2-range">{range}</div>
      <div className="fc-kpi2-number" style={{ color }}>{count}</div>
      <div className="fc-kpi2-sub">{sub}</div>
    </div>
  )
}

// ── Bar Chart Tooltip ─────────────────────────────────────────────────────────

function BarTooltip({ active, payload }) {
  if (!active || !payload?.length) return null
  const d = payload[0]?.payload
  if (!d) return null
  const all = [...(d.domains?.critical || []), ...(d.domains?.high || []), ...(d.domains?.warning || [])]
  return (
    <div className="fc-tooltip">
      <div className="fc-tt-date">{d.date}</div>
      {d.critical > 0 && <div className="fc-tt-row fc-tt-crit">Kritik: <b>{d.critical}</b></div>}
      {d.high > 0     && <div className="fc-tt-row fc-tt-high">Yüksek: <b>{d.high}</b></div>}
      {d.warning > 0  && <div className="fc-tt-row fc-tt-warn">Orta: <b>{d.warning}</b></div>}
      <div className="fc-tt-row fc-tt-total">Günlük expire: <b>{d.total}</b> · Kümülatif: <b>{d.cumulative}</b></div>
      {all.length > 0 && (
        <div className="fc-tt-domains">
          {all.slice(0, 6).map(dm => <div key={dm} className="fc-tt-domain">{dm}</div>)}
          {all.length > 6 && <div className="fc-tt-more">+{all.length - 6} daha</div>}
        </div>
      )}
    </div>
  )
}

// ── Calendar Heatmap ──────────────────────────────────────────────────────────

const HEAT_COLORS = ['transparent', '#fca5a5', '#ef4444', '#b91c1c', '#7c2d12']

function heatColor(count) {
  if (count === 0) return 'transparent'
  if (count <= 2)  return HEAT_COLORS[1]
  if (count <= 5)  return HEAT_COLORS[2]
  if (count <= 9)  return HEAT_COLORS[3]
  return HEAT_COLORS[4]
}

function countColor(slot) {
  if (slot.critical.length > 0) return '#f87171'
  if (slot.high.length > 0)     return '#fb923c'
  return '#fbbf24'
}

function CalendarHeatmap({ certs, t, onSelectDomain }) {
  const [hovered, setHovered] = useState(null)
  const [rangeDays, setRangeDays] = useState(30)
  const [dayModal, setDayModal] = useState(null)  // { key, certs:[...] } → o günün sertifika kartları

  const byDate = useMemo(() => {
    const today = new Date(); today.setHours(0, 0, 0, 0)
    const inEnd = new Date(today); inEnd.setDate(inEnd.getDate() + rangeDays)
    const map = {}
    certs.forEach(cert => {
      if (!cert.not_after) return
      const d = parseApiDate(cert.not_after)
      if (isNaN(d.getTime())) return
      if (d < today || d > inEnd) return
      if ((cert.days_remaining ?? -1) < 0) return
      const key = localDateStr(d)
      const remDays = cert.days_remaining ?? 999
      const sev = remDays <= 7 ? 'critical' : remDays <= 14 ? 'high' : 'warning'
      if (!map[key]) map[key] = { critical: [], high: [], warning: [] }
      map[key][sev].push(cert)   // tam cert objesi — kart modalı için
    })
    return map
  }, [certs, rangeDays])

  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const todayStr = localDateStr(today)
  const inEnd = new Date(today); inEnd.setDate(inEnd.getDate() + rangeDays)

  const dow = (today.getDay() + 6) % 7
  const weekStart = new Date(today)
  weekStart.setDate(weekStart.getDate() - dow)

  const weekCount = Math.ceil((dow + rangeDays) / 7)
  const baselineWeekCount = Math.ceil((dow + 30) / 7)
  const cellCount = weekCount * 7

  const cells = []
  for (let i = 0; i < cellCount; i++) {
    const d = new Date(weekStart)
    d.setDate(d.getDate() + i)
    const key = localDateStr(d)
    const inRange = d >= today && d <= inEnd
    const slot = byDate[key] || { critical: [], high: [], warning: [] }
    const count = slot.critical.length + slot.high.length + slot.warning.length
    const dayCerts = [...slot.critical, ...slot.high, ...slot.warning]  // tam cert objeleri
    cells.push({ key, d, inRange, count, dayCerts, slot, isToday: key === todayStr, dayNum: d.getDate() })
  }

  const DAYS = t('forecast.calDays').split(',')

  return (
    <div className="fc-card fc-section-card">
      <div className="fc-sec-header">
        <span className="fc-sec-num">02</span>
        <span className="fc-sec-title">{t('forecast.secCalendar')}</span>
        <div className="fc-range-filter">
          {CALENDAR_RANGE_OPTIONS.map(d => (
            <button
              key={d}
              type="button"
              className={`fc-range-btn${rangeDays === d ? ' active' : ''}`}
              onClick={() => setRangeDays(d)}
            >{t('forecast.chartDays', d)}</button>
          ))}
        </div>
      </div>
      <div className="fc-heatmap-days">
        {DAYS.map(d => <div key={d} className="fc-hm-day-label">{d}</div>)}
      </div>
      <div
        className={`fc-heatmap-grid${weekCount > 10 ? ' fc-heatmap-grid--compact' : ''}`}
        style={{ '--cal-aspect': `7 / ${baselineWeekCount}` }}
      >
        {cells.map(({ key, inRange, count, dayCerts, slot, isToday, dayNum }) => (
          inRange ? (
            <div
              key={key}
              className={`fc-hm-cell fc-hm-cell-v2${count === 0 ? ' fc-hm-zero' : ''}${isToday ? ' fc-hm-today' : ''}${count > 0 ? ' fc-hm-clickable' : ''}`}
              style={{ background: heatColor(count) }}
              onMouseEnter={() => setHovered({ key, count, dayCerts })}
              onMouseLeave={() => setHovered(null)}
              onClick={count > 0 ? () => setDayModal({ key, certs: dayCerts }) : undefined}
              role={count > 0 ? 'button' : undefined}
              title={count > 0 ? t('forecast.dayModalOpen') : undefined}
            >
              <span className="fc-hm-day-num">{dayNum}</span>
              {count > 0 && (
                <span className="fc-hm-count-v2" style={{ color: countColor(slot) }}>{count}</span>
              )}
              {hovered?.key === key && (
                <div className="fc-hm-tooltip">
                  <strong>{key}</strong>
                  <div>{count > 0 ? `${count} sertifika` : 'Yok'}</div>
                  {dayCerts.slice(0, 4).map(c => <div key={c.domain} className="fc-hm-tdomain">{c.domain}</div>)}
                  {dayCerts.length > 4 && <div>+{dayCerts.length - 4} daha</div>}
                </div>
              )}
            </div>
          ) : (
            <div key={key} className="fc-hm-cell fc-hm-empty" />
          )
        ))}
      </div>
      <div className="fc-hm-legend">
        <span className="fc-hm-leg-label">Az</span>
        {HEAT_COLORS.slice(1).map(c => (
          <div key={c} className="fc-hm-leg-dot" style={{ background: c }} />
        ))}
        <span className="fc-hm-leg-label">Çok</span>
      </div>

      {/* ── Gün detay modalı — o günün sertifikaları (genel bakış kartları gibi) ── */}
      {dayModal && (
        <div className="modal-overlay" style={{ zIndex: 990 }}>
          <div className="modal-box modal-wide">
            <div className="modal-icon-hdr modal-icon-hdr--user">
              <div className="modal-icon-hdr-badge"><Calendar size={20} /></div>
              <h3>{t('forecast.dayModalTitle', dayModal.key, dayModal.certs.length)}</h3>
              <button type="button" className="show-close" style={{ marginLeft: 'auto' }}
                aria-label={t('app.dismiss')} onClick={() => setDayModal(null)}>✕</button>
            </div>
            <div className="cards-container" style={{ padding: '0 18px 18px', maxHeight: '70vh', overflowY: 'auto' }}>
              {dayModal.certs.map((c) => (
                <CertificateCard key={c.domain} cert={c}
                  onClick={(d) => onSelectDomain?.(d)} />
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

// ── Pie Chart ─────────────────────────────────────────────────────────────────

function ForecastPie({ pieData, t }) {
  if (!pieData?.length) {
    return (
      <div className="fc-card fc-pie-empty">
        <div className="fc-sec-header">
          <span className="fc-sec-num">04</span>
          <span className="fc-sec-title">{t('forecast.pieTitle')}</span>
        </div>
        <div className="fc-no-data">{t('forecast.noTeamData')}</div>
      </div>
    )
  }
  const total = pieData.reduce((s, d) => s + d.value, 0)
  return (
    <div className="fc-card">
      <div className="fc-sec-header">
        <span className="fc-sec-num">04</span>
        <span className="fc-sec-title">{t('forecast.pieTitle')}</span>
      </div>
      <ResponsiveContainer width="100%" height={240}>
        <PieChart>
          <Pie
            data={pieData}
            dataKey="value"
            nameKey="name"
            cx="50%"
            cy="50%"
            outerRadius={80}
            innerRadius={44}
            paddingAngle={2}
          >
            {pieData.map((entry, i) => <Cell key={i} fill={entry.color} />)}
            <Label value={total} position="center" fill="#e2e8f0" fontSize={20} fontWeight={800} />
          </Pie>
          <RTooltip
            formatter={(v, n) => [v, n]}
            contentStyle={{ background: '#1e293b', border: '1px solid #334155', borderRadius: 6, color: '#e2e8f0', fontSize: 12 }}
          />
          <Legend
            wrapperStyle={{ fontSize: 11, color: '#94a3b8', paddingTop: 6 }}
            formatter={v => <span style={{ color: '#94a3b8' }}>{v}</span>}
          />
        </PieChart>
      </ResponsiveContainer>
    </div>
  )
}

// ── Expiry List ───────────────────────────────────────────────────────────────

function ExpiryList({ upcomingList, t }) {
  const [showAll, setShowAll] = useState(false)
  const visible = showAll ? upcomingList : upcomingList.slice(0, 15)

  return (
    <div className="fc-card fc-section-card">
      <div className="fc-sec-header">
        <span className="fc-sec-num">03</span>
        <span className="fc-sec-title">{t('forecast.secList')}</span>
        <span className="fc-sec-badge">{upcomingList.length} {t('forecast.certUnit')}</span>
      </div>
      <div className="fc-expiry-list">
        {visible.map((item, idx) => (
          <div key={`${item.domain}-${idx}`} className={`fc-exp-row fc-sev-${item.severity}`}>
            <span className="fc-exp-sev-dot" style={{ background: SEV_COLORS[item.severity] }} />
            <span className="fc-exp-domain">{item.domain}</span>
            <span className="fc-exp-date">{item.date}</span>
            <span className="fc-exp-days" style={{ color: SEV_COLORS[item.severity] }}>{item.days} {t('forecast.daysLeft')}</span>
          </div>
        ))}
        {!showAll && upcomingList.length > 15 && (
          <button className="fc-show-more" onClick={() => setShowAll(true)}>
            +{upcomingList.length - 15} {t('forecast.showMore')}
          </button>
        )}
        {upcomingList.length === 0 && (
          <div className="fc-no-data">{t('forecast.noData')}</div>
        )}
      </div>
    </div>
  )
}

// ── Main Page ─────────────────────────────────────────────────────────────────

export default function ExpiryForecastPage({ onSelectDomain }) {
  const t = useT()
  const time = useClock()
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [chartRangeDays, setChartRangeDays] = useState(30)

  useEffect(() => {
    Promise.allSettled([api.getCertificates(), api.getStats(), api.getTeamStats()])
      .then(([certsResult, statsResult, teamResult]) => {
        const rawCerts  = certsResult.status === 'fulfilled' ? certsResult.value              : []
        const stats     = statsResult.status  === 'fulfilled' ? (statsResult.value?.data  ?? null) : null
        const teamStats = teamResult.status   === 'fulfilled' ? (teamResult.value?.data   ?? null) : null

        const certs = Array.isArray(rawCerts) ? rawCerts : (rawCerts?.data ?? [])
        const { dailyData, counts, upcomingList } = computeForecast(certs)
        const pieData = computePie(teamStats, stats)

        setData({ dailyData, counts, kpi: stats, pieData, upcomingList, certs })
      })
      .catch(console.error)
      .finally(() => setLoading(false))
  }, [])

  const chartData = useMemo(() => {
    if (!data) return []
    if (chartRangeDays === 30) return data.dailyData
    return data.certs ? computeChartData(data.certs, chartRangeDays) : data.dailyData
  }, [data, chartRangeDays])

  const timeStr = time.toLocaleTimeString('tr-TR', { hour12: false })
  const dateStr = time.toLocaleDateString('tr-TR', { day: '2-digit', month: 'short', year: 'numeric' })

  return (
    <div className="forecast-page">

      {/* ── Header ── */}
      <header className="fc-header">
        <div className="fc-header-left">
          <BrandLogo status="ok" size={34} />
          <div>
            <div className="fc-brand-name">Certificate Monitor</div>
            <div className="fc-brand-sub">EXPIRATION INTELLIGENCE · PROD</div>
          </div>
        </div>
        <div className="fc-header-right">
          <div className="fc-live-row">
            <span className="fc-live-dot" />
            live · {timeStr}
          </div>
          <div className="fc-snapshot">snapshot · {dateStr}</div>
        </div>
      </header>
      <div className="fc-header-sep" />

      {loading && <LoadingBlock label={t('forecast.loading')} className="forecast-loading" />}

      {!loading && data && (
        <>
          {/* ── KPI 2×2 ── */}
          <div className="fc-kpi2-grid">
            <KpiCard2 range={t('forecast.range7')}     value={data.counts.critical}        color="#dc2626" sub={t('forecast.sub7')} />
            <KpiCard2 range={t('forecast.range14')}    value={data.counts.high}            color="#ea580c" sub={t('forecast.sub14')} />
            <KpiCard2 range={t('forecast.range30')}    value={data.counts.warning}         color="#f59e0b" sub={t('forecast.sub30')} />
            <KpiCard2 range={t('forecast.rangeTotal')} value={data.kpi?.total_certificates} color="#14b8a6" sub={t('forecast.subTotal')} />
          </div>

          {/* ── Section 01: Bar chart + Pie ── */}
          <div className="fc-row-2col">
            <div className="fc-card fc-section-card">
              <div className="fc-sec-header">
                <span className="fc-sec-num">01</span>
                <span className="fc-sec-title">{t('forecast.secChart')}</span>
                <div className="fc-range-filter">
                  {CHART_RANGE_OPTIONS.map(d => (
                    <button
                      key={d}
                      type="button"
                      className={`fc-range-btn${chartRangeDays === d ? ' active' : ''}`}
                      onClick={() => setChartRangeDays(d)}
                    >{t('forecast.chartDays', d)}</button>
                  ))}
                </div>
                <span className="fc-sec-legend">
                  <span className="fc-leg-dot" style={{ background: '#dc2626' }} />{t('forecast.legCritical')}
                  <span className="fc-leg-dot" style={{ background: '#ea580c' }} />{t('forecast.legHigh')}
                  <span className="fc-leg-dot" style={{ background: '#f59e0b' }} />{t('forecast.legWarning')}
                </span>
              </div>
              <ResponsiveContainer width="100%" height={280}>
                <ComposedChart data={chartData} margin={{ top: 4, right: 20, bottom: 0, left: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#1e3a5f" />
                  <XAxis dataKey="label" tick={{ fill: '#64748b', fontSize: 10 }} interval={Math.max(0, Math.floor(chartRangeDays / 6))} />
                  <YAxis yAxisId="left"  allowDecimals={false} tick={{ fill: '#94a3b8', fontSize: 10 }} width={26} />
                  <YAxis yAxisId="right" orientation="right" allowDecimals={false} tick={{ fill: '#3b82f6', fontSize: 10 }} width={36} />
                  <RTooltip content={<BarTooltip />} cursor={{ fill: 'rgba(255,255,255,.04)' }} />
                  <Bar yAxisId="left" dataKey="critical" stackId="s" fill="#dc2626" name="Critical" />
                  <Bar yAxisId="left" dataKey="high"     stackId="s" fill="#ea580c" name="High" />
                  <Bar yAxisId="left" dataKey="warning"  stackId="s" fill="#f59e0b" name="Warning" radius={[3, 3, 0, 0]} />
                  <Line yAxisId="right" type="monotone" dataKey="cumulative" stroke="#3b82f6" strokeWidth={2} dot={false} name="Cumulative" />
                </ComposedChart>
              </ResponsiveContainer>
            </div>

            <ForecastPie pieData={data.pieData} t={t} />
          </div>

          {/* ── Section 02: Calendar ── */}
          <CalendarHeatmap certs={data.certs} t={t} onSelectDomain={onSelectDomain} />

          {/* ── Section 03: Expiry List ── */}
          <ExpiryList upcomingList={data.upcomingList} t={t} />
        </>
      )}
    </div>
  )
}
