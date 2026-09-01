import { useState, useEffect, useCallback, useMemo } from 'react'
import {
  ResponsiveContainer, ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid, Tooltip,
} from 'recharts'
import { RefreshCw } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import TimeRangePicker, { resolveRange } from '../ui/TimeRangePicker.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'

const RETENTION_KEY = 'site.monitor.metrics.http.retention-days'
const pad = (n) => String(n).padStart(2, '0')

// Backend ts'i Europe/Istanbul YEREL duvar-saati ("YYYY-MM-DDTHH:mm:ss", Z YOK) → olduğu gibi yerel okunur.
function tickLabel(ts, gran) {
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return ts
  if (gran === 'hour') return `${pad(d.getDate())}.${pad(d.getMonth() + 1)} ${pad(d.getHours())}:00`
  return `${pad(d.getDate())}.${pad(d.getMonth() + 1)} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function ChartTooltip({ active, payload, t }) {
  if (!active || !payload || !payload.length) return null
  const d = payload[0].payload
  const row = (label, val, suffix = '') => val == null ? null : (
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16 }}>
      <span style={{ color: 'var(--text-muted)' }}>{label}</span><strong>{val}{suffix}</strong>
    </div>
  )
  return (
    <div style={{ background: 'var(--bg-card,#fff)', border: '1px solid var(--border)', borderRadius: 8,
      padding: '8px 11px', fontSize: '.82em', lineHeight: 1.7, boxShadow: '0 4px 16px rgba(0,0,0,.12)' }}>
      <div style={{ fontWeight: 700, marginBottom: 4 }}>{String(d.ts).replace('T', ' ')}</div>
      {row(t('http.exp.count'), d.count)}
      {row(t('http.exp.errors'), d.errors)}
      {row(t('http.exp.avg'), d.avg, ' ms')}
      {row(t('http.exp.p95'), d.p95, ' ms')}
      {row(t('http.exp.p99'), d.p99, ' ms')}
    </div>
  )
}

/** Kalıcı, Grafana benzeri HTTP istek metrik gezgini — endpoint seçimi + zaman aralığı + p95/p99. */
export default function HttpMetricsExplorer() {
  const t = useT()
  const toast = useToast()
  const [range, setRange] = useState({ type: 'rel', minutes: 60, key: '1h' })
  const [endpoint, setEndpoint] = useState('')          // '' = Tümü
  const [endpoints, setEndpoints] = useState([])
  const [series, setSeries] = useState(null)
  const [loading, setLoading] = useState(false)
  const [retention, setRetention] = useState(null)      // null = okunamadı/yetkisiz → kutu gizli
  const [savingRet, setSavingRet] = useState(false)
  const [hidden, setHidden] = useState(() => new Set())  // gizli seri anahtarları (tıklanabilir legend)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const { from, to } = resolveRange(range)
      const [epRes, srRes] = await Promise.all([
        api.admin.getHttpMetricsEndpoints(from, to),
        api.admin.getHttpMetricsSeries(from, to, endpoint),
      ])
      if (epRes?.success) setEndpoints(epRes.data ?? [])
      if (srRes?.success) setSeries(srRes.data ?? null)
    } finally {
      setLoading(false)
    }
  }, [range, endpoint])

  useEffect(() => { load() }, [load])

  // Retention'ı Genel Ayarlar'dan oku (yetki yoksa sessiz → kutu gizli).
  useEffect(() => {
    api.admin.getGeneralSettings().then(res => {
      if (!res?.success) return
      const list = res.data?.settings ?? res.data ?? []
      const row = Array.isArray(list) ? list.find(s => s.key === RETENTION_KEY) : null
      if (row) setRetention(String(row.value ?? row.default ?? '7'))
    }).catch(() => {})
  }, [])

  async function saveRetention() {
    const d = parseInt(retention, 10)
    if (!Number.isFinite(d) || d < 1) { toast.error(t('http.exp.retentionInvalid')); return }
    setSavingRet(true)
    try {
      try {
        const res = await api.admin.saveGeneralSettings({ values: { [RETENTION_KEY]: String(d) } })
        if (res?.success) toast.success(t('http.exp.retentionSaved', d))
        else toast.error(res?.error || t('http.exp.saveError'))
      } catch { toast.error(t('http.exp.saveError')) }
    } finally {
      setSavingRet(false)
    }
  }

  const gran = series?.granularity
  const chartData = useMemo(() => (series?.data ?? []).map(p => ({
    ts: p.ts, label: tickLabel(p.ts, gran),
    count: p.count, errors: p.errors, avg: p.avg_ms, p95: p.p95_ms, p99: p.p99_ms,
  })), [series, gran])
  const sum = series?.summary || {}
  const tickEvery = Math.max(0, Math.floor(chartData.length / 10))
  // Grafana benzeri nokta işaretçileri — yalnız kısa aralıklarda (yoğun seride performans + okunabilirlik).
  const dots = chartData.length <= 240 ? { r: 2, strokeWidth: 0 } : false

  const SERIES = [
    { key: 'count',  name: t('http.exp.count'),  color: '#3b82f6' },
    { key: 'errors', name: t('http.exp.errors'), color: '#ef4444' },
    { key: 'avg',    name: t('http.exp.avg'),    color: '#f59e0b' },
    { key: 'p95',    name: t('http.exp.p95'),    color: '#9333ea' },
    { key: 'p99',    name: t('http.exp.p99'),    color: '#be123c' },
  ]
  // Tıklanabilir legend: düz tık → yalnız bunu göster (izole); sonraki tıklar → aç/kapat; hepsi gizlenince → hepsi.
  const toggleSeries = (key) => setHidden(prev => {
    const allKeys = SERIES.map(s => s.key)
    if (allKeys.every(k => !prev.has(k))) return new Set(allKeys.filter(k => k !== key))   // ilk tık → izole
    const next = new Set(prev)
    if (next.has(key)) next.delete(key); else next.add(key)
    return allKeys.every(k => next.has(k)) ? new Set() : next                              // hepsi gizli → hepsini göster
  })
  const epOptions = [{ value: '', label: t('http.exp.allEndpoints') },
    ...endpoints.map(e => ({ value: e.endpoint,
      label: `${e.endpoint}  ·  ${e.count}${e.errors > 0 ? '  ·  ⚠ ' + e.errors : ''}` }))]
  // Aralıkta hata alan endpoint'ler — en çok hatadan başlayarak (tıklanınca grafikte o endpoint'e filtrele).
  const errorEndpoints = useMemo(
    () => (endpoints || []).filter(e => (e.errors || 0) > 0).sort((a, b) => b.errors - a.errors),
    [endpoints])

  return (
    <div className="hme-panel">
      <div className="hme-bar">
        <div className="hme-bar-left">
          <div className="hme-ep"><SearchableSelect value={endpoint} onChange={setEndpoint}
            options={epOptions} searchThreshold={2} placeholder={t('http.exp.allEndpoints')} /></div>
          <TimeRangePicker value={range} onChange={setRange} />
          <button type="button" className="btn btn-sm btn-secondary" onClick={load} disabled={loading}>
            <RefreshCw size={14} />{t('http.exp.refresh')}
          </button>
        </div>
        {retention != null && (
          <div className="hme-retention">
            <span>{t('http.exp.retention')}</span>
            <input type="number" min="1" max="365" value={retention}
              onChange={e => setRetention(e.target.value)} />
            <span>{t('http.exp.days')}</span>
            <button type="button" className="btn btn-sm btn-primary" onClick={saveRetention} disabled={savingRet}>
              {t('http.exp.save')}
            </button>
          </div>
        )}
      </div>

      <div className="hme-pills">
        <span className="hme-pill"><b>{sum.total ?? 0}</b> {t('http.exp.total')}</span>
        <span className="hme-pill"><b>{sum.errors ?? 0}</b> {t('http.exp.errors')}</span>
        <span className="hme-pill"><b>{sum.error_rate_pct ?? 0}%</b> {t('http.exp.errRate')}</span>
        <span className="hme-pill"><b>{sum.avg_ms ?? 0} ms</b> {t('http.exp.avg')}</span>
        <span className="hme-pill"><b>{sum.p95_ms ?? 0} ms</b> {t('http.exp.p95')}</span>
        <span className="hme-pill"><b>{sum.p99_ms ?? 0} ms</b> {t('http.exp.p99')}</span>
      </div>

      {errorEndpoints.length > 0 && (
        <div className="hme-errlist">
          <span className="hme-errlist-lbl">⚠ {t('http.exp.errEndpoints')}</span>
          {errorEndpoints.slice(0, 8).map(e => (
            <button key={e.endpoint} type="button"
                    className={`hme-errchip${endpoint === e.endpoint ? ' is-active' : ''}`}
                    onClick={() => setEndpoint(endpoint === e.endpoint ? '' : e.endpoint)}
                    title={t('http.exp.errChipTip')}>
              <span className="hme-errchip-ep">{e.endpoint}</span>
              <span className="hme-errchip-n">{e.errors} {t('http.exp.errors')} · %{e.error_rate_pct}</span>
            </button>
          ))}
        </div>
      )}

      {loading ? (
        <LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />
      ) : chartData.length === 0 ? (
        <LoadingBlock label={t('http.exp.noData')} className="upt-modal-loading" />
      ) : (
        <ResponsiveContainer width="100%" height={320}>
          <ComposedChart data={chartData} margin={{ top: 10, right: 12, left: 0, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
            <XAxis dataKey="label" tick={{ fontSize: 10, fill: 'var(--text-light)' }} stroke="var(--border)"
              interval={tickEvery} minTickGap={16} />
            <YAxis yAxisId="cnt" tick={{ fontSize: 10, fill: 'var(--text-light)' }} stroke="var(--border)" width={42} />
            <YAxis yAxisId="ms" orientation="right" tick={{ fontSize: 10, fill: 'var(--text-light)' }}
              stroke="var(--border)" width={46} tickFormatter={(v) => `${v}ms`} />
            <Tooltip content={<ChartTooltip t={t} />} />
            <Area yAxisId="cnt" type="linear" dataKey="count" name={t('http.exp.count')} hide={hidden.has('count')}
              fill="#bfdbfe" fillOpacity={0.4} stroke="#3b82f6" strokeWidth={1.5} dot={dots} isAnimationActive={false} />
            <Line yAxisId="cnt" type="linear" dataKey="errors" name={t('http.exp.errors')} hide={hidden.has('errors')}
              stroke="#ef4444" strokeWidth={1.5} dot={dots} connectNulls={false} isAnimationActive={false} />
            <Line yAxisId="ms" type="linear" dataKey="avg" name={t('http.exp.avg')} hide={hidden.has('avg')}
              stroke="#f59e0b" strokeWidth={1.5} dot={dots} connectNulls={false} isAnimationActive={false} />
            <Line yAxisId="ms" type="linear" dataKey="p95" name={t('http.exp.p95')} hide={hidden.has('p95')}
              stroke="#9333ea" strokeWidth={1.5} strokeDasharray="4 3" dot={dots} connectNulls={false} isAnimationActive={false} />
            <Line yAxisId="ms" type="linear" dataKey="p99" name={t('http.exp.p99')} hide={hidden.has('p99')}
              stroke="#be123c" strokeWidth={1.5} strokeDasharray="2 2" dot={dots} connectNulls={false} isAnimationActive={false} />
          </ComposedChart>
        </ResponsiveContainer>
      )}

      {/* Tıklanabilir legend — düz tık izole eder, sonraki tıklar ekler/çıkarır, hepsi gizlenince hepsi döner */}
      {chartData.length > 0 && (
        <div className="hme-legend">
          {SERIES.map(s => (
            <button key={s.key} type="button" title={t('http.exp.legendTip')}
                    className={`hme-legend-item${hidden.has(s.key) ? ' hme-legend-off' : ''}`}
                    onClick={() => toggleSeries(s.key)}>
              <span className="hme-legend-dot" style={{ background: s.color }} />{s.name}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
