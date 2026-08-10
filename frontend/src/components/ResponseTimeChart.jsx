import { useState, useEffect, useCallback, useMemo } from 'react'
import {
  ResponsiveContainer, ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid, Tooltip,
} from 'recharts'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import DateTimeRangePicker from './ui/DateTimeRangePicker.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import { BarChart3 } from 'lucide-react'

const PRESETS = [
  { key: '24h', days: 1 },
  { key: '7d',  days: 7 },
  { key: '30d', days: 30 },
  { key: '90d', days: 90 },
]

// Date → backend ISO (UTC, saniyeye kadar, Z'siz — checked_at deposu formatı).
const toIso = (d) => new Date(d).toISOString().slice(0, 19)

// Kova ISO'su (UTC, Z'siz) → kısa yerel etiket.
function tickLabel(ts, bucket) {
  const d = new Date(ts.endsWith('Z') ? ts : ts + 'Z')
  const p = (n) => String(n).padStart(2, '0')
  if (bucket === 'day') return `${p(d.getDate())}.${p(d.getMonth() + 1)}`
  return `${p(d.getDate())}.${p(d.getMonth() + 1)} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function ChartTooltip({ active, payload, t, isPing }) {
  if (!active || !payload || !payload.length) return null
  const d = payload[0].payload
  const row = (label, val, suffix = 'ms') =>
    val == null ? null : (
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16 }}>
        <span style={{ color: 'var(--text-muted)' }}>{label}</span><strong>{val}{suffix}</strong>
      </div>
    )
  return (
    <div style={{ background: 'var(--bg-card, #fff)', border: '1px solid var(--border)', borderRadius: 8,
      padding: '8px 11px', fontSize: '.82em', lineHeight: 1.7, boxShadow: '0 4px 16px rgba(0,0,0,.12)' }}>
      <div style={{ fontWeight: 700, marginBottom: 4 }}>{formatDate(d.ts)}</div>
      {row(t('chart.avg'), d.avg)}
      {row(t('chart.p95'), d.p95)}
      {row(t('chart.min'), d.min)}
      {row(t('chart.max'), d.max)}
      {isPing && row(t('chart.packetLoss'), d.loss, '%')}
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16 }}>
        <span style={{ color: 'var(--text-muted)' }}>{t('chart.samples')}</span><strong>{d.count}</strong>
      </div>
      {d.down > 0 && (
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16, color: '#dc2626' }}>
          <span>{t('chart.down')}</span><strong>{d.down}</strong>
        </div>
      )}
    </div>
  )
}

export default function ResponseTimeChart({ monitorId, kind }) {
  const t = useT()
  const isPing = kind === 'ping'
  const [preset, setPreset] = useState('30d')
  const [custom, setCustom] = useState(null)         // { from, to } ISO (UTC)
  const [showCustom, setShowCustom] = useState(false)
  const [pickFrom, setPickFrom] = useState(() => { const d = new Date(); d.setDate(d.getDate() - 7); return d })
  const [pickTo, setPickTo] = useState(() => new Date())
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(false)
  const [hidden, setHidden] = useState(() => new Set())   // tıklanabilir legend: izole/gizle (gezgin deseni)

  const load = useCallback(async () => {
    setLoading(true)
    const fetcher = { ping: api.monitoring.getPingResponseSeries, keyword: api.monitoring.getKeywordResponseSeries,
      port: api.monitoring.getPortResponseSeries, dns: api.monitoring.getDnsResponseSeries, http: api.monitoring.getHttpResponseSeries,
      page: api.monitoring.getPageResponseSeries, scripted: api.monitoring.getScriptedResponseSeries }[kind] ?? api.monitoring.getKeywordResponseSeries
    const params = custom ? { from: custom.from, to: custom.to } : { days: PRESETS.find(p => p.key === preset)?.days ?? 30 }
    const res = await fetcher(monitorId, params)
    setData(res?.success ? res.data : null)
    setLoading(false)
  }, [monitorId, kind, preset, custom])

  useEffect(() => { load() }, [load])

  const bucket = data?.bucket
  // Savunma katmanı: ts'siz/bozuk kayıtlar (yanlış beslenmiş endpoint vb.) grafiği DEĞİL yalnız
  // o kaydı düşürür — 2026-08 scripted regresyonunda ham Object[] beslemesi tüm ekranı çökertmişti.
  const chartData = useMemo(() => (data?.series ?? [])
    .filter(s => typeof s?.ts === 'string' && s.ts.length > 0)
    .map(s => ({
      ts: s.ts,
      label: tickLabel(s.ts, bucket),
      avg: s.avg, min: s.min, max: s.max, p95: s.p95, count: s.count, down: s.down, loss: s.loss,
      band: (s.min != null && s.max != null) ? [s.min, s.max] : null,
      downMarker: s.down > 0 ? (s.avg ?? s.max ?? 0) : null,
    })), [data, bucket])

  function applyCustom(f, to) {
    setPickFrom(f); setPickTo(to)
    setCustom({ from: toIso(f), to: toIso(to) })
  }
  function pickPreset(key) { setCustom(null); setShowCustom(false); setPreset(key) }

  const hasData = chartData.some(d => d.avg != null)
  const tickEvery = Math.max(0, Math.floor(chartData.length / 8))

  // Tıklanabilir legend — HttpMetricsExplorer deseni: ilk tık izole (yalnız bunu), sonraki ekle/çıkar, hepsi gizli → hepsi.
  const SERIES = [
    { key: 'band',       name: t('chart.minmax'),     color: '#93c5fd' },
    { key: 'avg',        name: t('chart.avg'),        color: '#2563eb' },
    { key: 'p95',        name: t('chart.p95'),        color: '#9333ea' },
    ...(isPing ? [{ key: 'loss', name: t('chart.packetLoss'), color: '#ea580c' }] : []),
    { key: 'downMarker', name: t('chart.down'),       color: '#dc2626' },
  ]
  const toggleSeries = (key) => setHidden(prev => {
    const allKeys = SERIES.map(s => s.key)
    if (allKeys.every(k => !prev.has(k))) return new Set(allKeys.filter(k => k !== key))   // ilk tık → izole
    const next = new Set(prev)
    if (next.has(key)) next.delete(key); else next.add(key)
    return allKeys.every(k => next.has(k)) ? new Set() : next                              // hepsi gizli → hepsini göster
  })

  return (
    <div>
      <div className="upt-range-btns" style={{ flexWrap: 'wrap' }}>
        {PRESETS.map(p => (
          <button key={p.key} type="button"
            className={`btn btn-sm ${!custom && preset === p.key ? 'btn-primary' : 'btn-secondary'}`}
            onClick={() => pickPreset(p.key)}>{t(`chart.range${p.key}`)}</button>
        ))}
        <button type="button" className={`btn btn-sm ${custom ? 'btn-primary' : 'btn-secondary'}`}
          onClick={() => setShowCustom(s => !s)}>{t('chart.custom')}</button>
      </div>

      {showCustom && (
        <div style={{ margin: '8px 0' }}>
          <DateTimeRangePicker from={pickFrom} to={pickTo} onApply={applyCustom} />
        </div>
      )}

      {data?.capped && (
        <div style={{ fontSize: '.78em', color: '#b45309', margin: '4px 0' }}>⚠ {t('chart.capped')}</div>
      )}

      {loading ? (
        <LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />
      ) : !hasData ? (
        /* Boş durum LoadingBlock ile gösterilirse dönen spinner çıkar ve "yükleniyor" ile
           "veri yok" ayrışmaz — kullanıcı sonsuza kadar bekleniyor sanır. */
        <StatusBlock icon={BarChart3} title={t('chart.noData')} />
      ) : (
        <>
        <ResponsiveContainer width="100%" height={300}>
          <ComposedChart data={chartData} margin={{ top: 10, right: isPing ? 8 : 12, left: 0, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
            <XAxis dataKey="label" tick={{ fontSize: 10, fill: 'var(--text-light)' }} stroke="var(--border)"
              interval={tickEvery} minTickGap={16} />
            <YAxis yAxisId="ms" tick={{ fontSize: 10, fill: 'var(--text-light)' }} stroke="var(--border)"
              width={46} tickFormatter={(v) => `${v}ms`} />
            {isPing && (
              <YAxis yAxisId="loss" orientation="right" domain={[0, 100]} tick={{ fontSize: 10, fill: '#ea580c' }}
                stroke="var(--border)" width={34} tickFormatter={(v) => `${v}%`} />
            )}
            <Tooltip content={<ChartTooltip t={t} isPing={isPing} />} />
            <Area yAxisId="ms" type="monotone" dataKey="band" name={t('chart.minmax')} hide={hidden.has('band')}
              fill="#bfdbfe" fillOpacity={0.45} stroke="none" isAnimationActive={false} connectNulls />
            <Line yAxisId="ms" type="monotone" dataKey="avg" name={t('chart.avg')} hide={hidden.has('avg')}
              stroke="#2563eb" strokeWidth={2} dot={false} isAnimationActive={false} connectNulls />
            <Line yAxisId="ms" type="monotone" dataKey="p95" name={t('chart.p95')} hide={hidden.has('p95')}
              stroke="#9333ea" strokeWidth={1.5} strokeDasharray="4 3" dot={false} isAnimationActive={false} connectNulls />
            {isPing && (
              <Line yAxisId="loss" type="monotone" dataKey="loss" name={t('chart.packetLoss')} hide={hidden.has('loss')}
                stroke="#ea580c" strokeWidth={1.5} dot={false} isAnimationActive={false} connectNulls />
            )}
            <Line yAxisId="ms" dataKey="downMarker" name={t('chart.down')} stroke="transparent" hide={hidden.has('downMarker')}
              dot={{ r: 4, fill: '#dc2626', stroke: '#fff', strokeWidth: 1 }} isAnimationActive={false}
              legendType="circle" connectNulls={false} />
          </ComposedChart>
        </ResponsiveContainer>
        <div className="hme-legend">
          {SERIES.map(s => (
            <button key={s.key} type="button" title={t('chart.legendTip')}
              className={`hme-legend-item${hidden.has(s.key) ? ' hme-legend-off' : ''}`}
              onClick={() => toggleSeries(s.key)}>
              <span className="hme-legend-dot" style={{ background: s.color }} />{s.name}
            </button>
          ))}
        </div>
        </>
      )}
    </div>
  )
}
