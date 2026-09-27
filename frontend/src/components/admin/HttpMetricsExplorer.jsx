import { useState, useEffect, useCallback, useMemo, useId } from 'react'
import { RefreshCw } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import TimeRangePicker, { resolveRange } from '../ui/TimeRangePicker.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import {
  ChartContainer, ChartTooltip, ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid,
} from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'

const RETENTION_KEY = 'site.monitor.metrics.http.retention-days'
const pad = (n) => String(n).padStart(2, '0')

// Backend ts'i Europe/Istanbul YEREL duvar-saati ("YYYY-MM-DDTHH:mm:ss", Z YOK) → olduğu gibi yerel okunur.
function tickLabel(ts, gran) {
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return ts
  if (gran === 'hour') return `${pad(d.getDate())}.${pad(d.getMonth() + 1)} ${pad(d.getHours())}:00`
  return `${pad(d.getDate())}.${pad(d.getMonth() + 1)} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function HmeTooltip({ active, payload, t }) {
  if (!active || !payload || !payload.length) return null
  const d = payload[0].payload
  const row = (label, val, suffix = '') => val == null ? null : (
    <div className="flex justify-between gap-4">
      <span className="text-muted-foreground">{label}</span><strong className="tabular-nums">{val}{suffix}</strong>
    </div>
  )
  return (
    <div className="rounded-lg border bg-card px-[11px] py-2 text-[.82em] leading-[1.7] shadow-lg border-border">
      <div className="mb-1 font-bold">{String(d.ts).replace('T', ' ')}</div>
      {row(t('http.exp.count'), d.count)}
      {row(t('http.exp.errors'), d.errors)}
      {row(t('http.exp.avg'), d.avg, ' ms')}
      {row(t('http.exp.p95'), d.p95, ' ms')}
      {row(t('http.exp.p99'), d.p99, ' ms')}
    </div>
  )
}

/** Kalıcı, Grafana benzeri HTTP istek metrik gezgini — endpoint seçimi + zaman aralığı + p95/p99.
 *  Çizim shadcn: Button / Badge / Input / Label, grafik ChartContainer (recharts içeride). */
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
  const retentionId = useId()

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
  const chartConfig = Object.fromEntries(SERIES.map((x) => [x.key, { label: x.name, color: x.color }]))
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
    <div data-testid="hme-panel" className="flex min-w-0 flex-col gap-3">
      <div className="flex flex-col gap-2 lg:flex-row lg:flex-wrap lg:items-center lg:justify-between">
        <div className="flex flex-wrap items-center gap-2">
          <div className="w-full sm:w-auto sm:min-w-[220px] sm:max-w-[360px]">
            <SearchableSelect value={endpoint} onChange={setEndpoint}
              options={epOptions} searchThreshold={2} placeholder={t('http.exp.allEndpoints')} ariaLabel={t('flt.endpoint')} />
          </div>
          <TimeRangePicker value={range} onChange={setRange} />
          <Button type="button" variant="secondary" size="sm" onClick={load} disabled={loading}>
            <RefreshCw size={14} aria-hidden="true" className={cn(loading && 'animate-spin motion-reduce:animate-none')} />{t('http.exp.refresh')}
          </Button>
        </div>
        {retention != null && (
          <div data-testid="hme-retention" className="flex flex-wrap items-center gap-2 text-sm">
            <Label htmlFor={retentionId} className="font-normal text-muted-foreground">{t('http.exp.retention')}</Label>
            <Input id={retentionId} type="number" min="1" max="365" value={retention} className="h-8 w-20"
              onChange={e => setRetention(e.target.value)} />
            <span className="text-muted-foreground">{t('http.exp.days')}</span>
            <Button type="button" size="sm" onClick={saveRetention} disabled={savingRet}>
              {t('http.exp.save')}
            </Button>
          </div>
        )}
      </div>

      <div data-testid="hme-pills" className="flex flex-wrap gap-1.5">
        {[
          [sum.total ?? 0, t('http.exp.total')],
          [sum.errors ?? 0, t('http.exp.errors')],
          [`${sum.error_rate_pct ?? 0}%`, t('http.exp.errRate')],
          [`${sum.avg_ms ?? 0} ms`, t('http.exp.avg')],
          [`${sum.p95_ms ?? 0} ms`, t('http.exp.p95')],
          [`${sum.p99_ms ?? 0} ms`, t('http.exp.p99')],
        ].map(([v, l]) => (
          <Badge key={l} variant="outline" className="h-7 gap-1 rounded-full px-2.5 text-xs font-normal text-muted-foreground">
            <b className="font-bold text-foreground tabular-nums">{v}</b> {l}
          </Badge>
        ))}
      </div>

      {errorEndpoints.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label={t('http.exp.errEndpoints')}>
          <span className="text-xs font-semibold text-destructive">⚠ {t('http.exp.errEndpoints')}</span>
          {errorEndpoints.slice(0, 8).map(e => {
            const on = endpoint === e.endpoint
            return (
              <Button key={e.endpoint} type="button" variant="outline" size="sm" aria-pressed={on}
                className={cn('h-auto max-w-full flex-col items-start gap-0 px-2.5 py-1 text-left font-normal whitespace-normal',
                  on ? 'border-destructive bg-destructive/10 hover:bg-destructive/15' : 'hover:border-destructive/60')}
                onClick={() => setEndpoint(on ? '' : e.endpoint)}
                title={t('http.exp.errChipTip')}>
                <span className="max-w-[260px] truncate font-mono text-xs">{e.endpoint}</span>
                <span className="text-[11px] text-destructive">{e.errors} {t('http.exp.errors')} · %{e.error_rate_pct}</span>
              </Button>
            )
          })}
        </div>
      )}

      {loading ? (
        <LoadingBlock label={t('modal.loading')} />
      ) : chartData.length === 0 ? (
        <StatusBlock tone="neutral" title={t('http.exp.noData')} />
      ) : (
        <ChartContainer config={chartConfig} className="aspect-auto h-[280px] w-full sm:h-[320px]">
          <ComposedChart data={chartData} margin={{ top: 10, right: 12, left: 0, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" vertical={false} />
            <XAxis dataKey="label" tick={{ fontSize: 10 }} tickLine={false} axisLine={false}
              interval={tickEvery} minTickGap={16} />
            <YAxis yAxisId="cnt" tick={{ fontSize: 10 }} tickLine={false} axisLine={false} width={42} />
            <YAxis yAxisId="ms" orientation="right" tick={{ fontSize: 10 }} tickLine={false} axisLine={false}
              width={46} tickFormatter={(v) => `${v}ms`} />
            <ChartTooltip content={<HmeTooltip t={t} />} />
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
        </ChartContainer>
      )}

      {/* Tıklanabilir legend — düz tık izole eder, sonraki tıklar ekler/çıkarır, hepsi gizlenince hepsi döner */}
      {chartData.length > 0 && (
        <div className="flex flex-wrap justify-center gap-1" role="group" aria-label={t('http.exp.legendTip')}>
          {SERIES.map(s => (
            <Button key={s.key} type="button" variant="ghost" size="sm" title={t('http.exp.legendTip')}
              aria-pressed={!hidden.has(s.key)} data-series={s.key}
              className={cn('gap-1.5 font-normal', hidden.has(s.key) && 'text-muted-foreground line-through opacity-60')}
              onClick={() => toggleSeries(s.key)}>
              {/* renk CSS özel değişkeniyle (--dot): seri rengi SERIES'ten */}
              <span aria-hidden="true" className="size-2.5 rounded-full bg-(--dot)" style={{ '--dot': s.color }} />{s.name}
            </Button>
          ))}
        </div>
      )}
    </div>
  )
}
