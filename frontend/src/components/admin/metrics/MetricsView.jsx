import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Activity, Cpu, Database, Globe, Layers, RefreshCw, ScrollText, Server } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useVisibleInterval } from '../../../hooks/useVisibleInterval'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import { LogHeader } from '../LogViewParts.jsx'
import { Panel, Tile } from '../httpmetrics/HttpParts.jsx'
import MetricChart from './MetricChart.jsx'
import PodList from './PodList.jsx'
import {
  CPU_PCT, HEAP_CHART_PCT, HTTP_ERR_PCT, HTTP_MS, MEMORY_PCT, METRIC_RANGES, QUEUE_PCT, fmtNum, fmtPct, fmtRate, httpRows, latestValue, logCacheRows,
  normalizeRange, overviewKpis, perPodRows, podSeries, seriesPods, utcMs,
} from './metricsModel.js'

/**
 * Sistem Sağlığı → Metrikler (2026-10-13, kullanıcı isteği: "SiteMonitor metriklerini detaylı izleyebileceğim profesyonel,
 * mobil uyumlu bir metrikler sayfası"). Veri: her podun dakikada bir yazdığı kalıcı örnekler (7 gün) —
 * `/api/admin/system/metrics/overview` (podlar + son örnek + son saat) ve `/series` (aralık × pod). 60 sn'de bir
 * tazelenir (görünürken; önceki istek bitmeden yenisi başlamaz). Aralık / pod değişince eski seri SİLİNİR (yeni etiketin
 * altında eski veri görünmez); geç gelen yanıt sıra numarasıyla düşer. Okunamayan değer "—" ve nötr tondur, hiç yeşil
 * değildir. URL: `view=metrics`, `mx_range` (varsayılan 6h yazılmaz), `mx_pod` (boş = tüm podlar).
 */
const fmtMs = (v) => (v == null ? '—' : `${fmtNum(v)} ms`)
/** Dakika başına ms (GC): küçük değer 0'a yuvarlanmaz. */
const fmtRateMs = (v) => (v == null ? '—' : `${fmtRate(v)} ms`)

function writeUrl(range, pod) {
  try {
    const url = new URL(window.location.href)
    const p = url.searchParams
    if (range && range !== '6h') p.set('mx_range', range); else p.delete('mx_range')
    if (pod) p.set('mx_pod', pod); else p.delete('mx_pod')
    const qs = p.toString()
    window.history.replaceState(window.history.state, '', url.pathname + (qs ? `?${qs}` : '') + url.hash)
  } catch { /* history yok */ }
}

function ChartGrid({ children }) {
  return <div className="grid grid-cols-1 gap-3 md:grid-cols-2 2xl:grid-cols-4">{children}</div>
}

export default function MetricsView({ onBack, initial }) {
  const t = useT()
  const [range, setRange] = useState(() => normalizeRange(initial?.range))
  const [pod, setPod] = useState(() => (typeof initial?.pod === 'string' && initial.pod.length <= 80 ? initial.pod : ''))
  const [overview, setOverview] = useState(null)
  const [series, setSeries] = useState(null)
  const [failed, setFailed] = useState({ overview: false, series: false })
  const [loading, setLoading] = useState(true)
  const [updatedAt, setUpdatedAt] = useState(null)
  const seq = useRef(0)

  const load = useCallback(async () => {
    const my = ++seq.current
    setLoading(true)
    try {
      const [ov, se] = await Promise.allSettled([
        api.admin.systemMetrics.overview(),
        api.admin.systemMetrics.series(range, pod || null),
      ])
      if (my !== seq.current) return
      const ovOk = ov.status === 'fulfilled' && ov.value?.success && ov.value.data
      const seOk = se.status === 'fulfilled' && se.value?.success && se.value.data
      if (ovOk) setOverview(ov.value.data)
      if (seOk) setSeries(se.value.data)
      setFailed({ overview: !ovOk, series: !seOk })
      if (ovOk || seOk) setUpdatedAt(new Date())
    } finally {
      // Yalnız en yeni istek bayrağı indirir (eskisi yenisinin "yükleniyor"unu silmesin; kapanmış görünümde de değil)
      if (my === seq.current) setLoading(false)
    }
  }, [range, pod])

  // Aralık / pod değişince eski seri gösterilmez (yeni etiketin altında eski veri yanıltır).
  const firstRun = useRef(true)
  useEffect(() => {
    if (firstRun.current) { firstRun.current = false; return }
    setSeries(null)
    // Önceki aralığın hatası yeni istek sürerken "okunamadı" demesin
    setFailed((f) => (f.series ? { ...f, series: false } : f))
    writeUrl(range, pod)
  }, [range, pod])
  useEffect(() => { load() }, [load])
  useVisibleInterval(load, 60_000, false)
  // Görünüm kapanınca geç gelen yanıt uygulanmasın
  useEffect(() => () => { seq.current += 1 }, [])

  const kpi = useMemo(() => overviewKpis(overview), [overview])
  const pods = useMemo(() => seriesPods(series), [series])
  const ps = useMemo(() => podSeries(pods), [pods])
  const keyOf = useMemo(() => {
    const m = new Map(ps.map((s) => [s.podId, s.key]))
    return (id) => m.get(id) ?? null
  }, [ps])
  const keys = useMemo(() => ps.map((s) => s.key), [ps])

  const charts = useMemo(() => {
    if (!series) return null
    const per = (pick) => perPodRows(series, pick, keyOf)
    return {
      cpu: per((p) => p.cpu_process_avg),
      heap: per((p) => p.heap_pct_max),
      threads: per((p) => p.threads_max),
      gc: per((p) => p.gc_pause_ms_pm),
      dbActive: per((p) => p.db_active_max),
      dbPending: per((p) => p.db_pending_max),
      execActive: per((p) => p.exec_active_max),
      execQueue: per((p) => p.exec_queue_max),
      execDone: per((p) => p.exec_completed_pm),
      http: httpRows(series),
      logs: logCacheRows(series),
    }
  }, [series, keyOf])

  const from = utcMs(series?.from)
  const to = utcMs(series?.to)
  const bucket = series?.bucket || ({ '1h': '1m', '7d': '1h' }[range] || '10m')
  const per = t(`mx.per.${bucket}`)
  const podOptions = useMemo(() => {
    const list = (overview?.pods || []).map((p) => ({ value: String(p.id), label: String(p.name ?? p.id) }))
    if (pod && !list.some((o) => o.value === pod)) list.push({ value: pod, label: pod })
    return [{ value: '', label: t('mx.pod.all') }, ...list]
  }, [overview, pod, t])

  if (loading && !overview && !series && !failed.overview && !failed.series) {
    return (
      <div data-slot="mx-view" className="flex min-w-0 flex-col gap-4">
        <LogHeader icon={Activity} title={t('mx.title')} backLabel={t('sml.back')} onBack={onBack} subtitle={t('mx.subtitle')} actions={null} />
        <LoadingBlock />
      </div>
    )
  }

  const chartProps = { t, from, to }
  const podsLine = (rows, extra) => ({ ...chartProps, rows, series: ps, ...extra })
  // Örnekleyici kapalı (site.monitor.metrics.system.enabled=false): "toplanıyor" / "pod durdu" değil, "kapalı"
  const samplingOff = overview?.enabled === false
  // Hiç / yalnız durmuş pod: kısa süre "toplanıyor", süre geçince "örnek yazılamıyor" (kırmızı)
  const notWriting = kpi.pods.notWriting === true
  const noSamples = overview && !samplingOff && !notWriting && (overview.pods || []).length === 0
  // Okunamayan özet "pod yok / örnek yok" diye gösterilmez; "eski değerler duruyor" yalnız o değerler ekrandaysa
  const overviewUnread = failed.overview && !overview
  const keptOld = (failed.overview && overview) || (failed.series && series)

  return (
    <div data-slot="mx-view" className="flex min-w-0 flex-col gap-4">
      <LogHeader icon={Activity} title={t('mx.title')} backLabel={t('sml.back')} onBack={onBack}
        subtitle={<>{t('mx.subtitle')}{updatedAt ? ` · ${t('sml.updatedAt', updatedAt.toLocaleTimeString())}` : ''}</>}
        actions={
          <Button type="button" variant="outline" onClick={() => load()} disabled={loading} aria-busy={loading || undefined} className="pointer-coarse:h-10">
            <RefreshCw aria-hidden="true" className={cn(loading && 'animate-spin motion-reduce:animate-none')} /> {t('sml.refresh')}
          </Button>
        } />

      <div data-slot="mx-controls" className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
        <SegmentedControl value={range} onChange={(v) => setRange(normalizeRange(v))} ariaLabel={t('mx.range.label')}
          itemClassName="flex-1 max-sm:h-10 sm:flex-none"
          options={METRIC_RANGES.map((r) => ({ value: r, label: t(`mx.range.${r}`) }))} />
        <div className="min-w-0 sm:w-72">
          <SearchableSelect value={pod} onChange={(v) => setPod(v ? String(v) : '')} options={podOptions} ariaLabel={t('mx.pod.filter')} wrapLabels />
        </div>
        <p data-slot="mx-bucket" data-bucket={bucket} className="m-0 text-xs text-muted-foreground sm:ml-auto">{t('mx.bucketNote', per)}</p>
      </div>

      {(failed.overview || failed.series) && (
        <AlertBanner tone="danger" role="alert" className="mb-0" title={t('mx.loadError')}
          actions={<Button type="button" variant="secondary" size="sm" onClick={() => load()}>{t('mx.retry')}</Button>}>
          {[failed.overview && t('mx.part.overview'), failed.series && t('mx.part.series')].filter(Boolean).join(', ')}.
          {keptOld ? ` ${t('mx.keptOld')}` : ''}
        </AlertBanner>
      )}

      {samplingOff && (
        <AlertBanner tone="info" className="mb-0" title={t('mx.off.title')}>{t('mx.off.body')}</AlertBanner>
      )}

      {notWriting && (
        <AlertBanner tone="danger" role="alert" className="mb-0" title={t('mx.notWriting.title')}>{t('mx.notWriting.body')}</AlertBanner>
      )}

      {noSamples && (
        <AlertBanner tone="info" className="mb-0" title={t('mx.collecting.title')}>{t('mx.collecting.body')}</AlertBanner>
      )}

      <div role="group" aria-label={t('mx.kpi.label')} data-slot="mx-kpis" className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-4 2xl:grid-cols-7">
        <Tile wrapText t={t} id="pods" label={t('mx.kpi.pods')} tone={samplingOff ? 'neutral' : kpi.pods.tone}
          value={kpi.pods.total ? t('mx.kpi.podsValue', kpi.pods.live, kpi.pods.total) : '—'}
          sub={overviewUnread ? t('mx.kpi.unreadable') : samplingOff ? t('mx.kpi.off') : notWriting ? t('mx.kpi.notWriting') : kpi.pods.stopped ? t('mx.kpi.podsStopped', kpi.pods.stopped)
            : kpi.pods.total ? t('mx.kpi.podsSub') : t('mx.kpi.noSamples')} hint={t('mx.hint.pods')} />
        <Tile wrapText t={t} id="cpu" label={t('mx.kpi.cpu')} tone={kpi.cpu.tone} value={fmtPct(kpi.cpu.value)}
          sub={t('mx.kpi.maxOfPods')} hint={t('mx.hint.cpu', CPU_PCT.warn, CPU_PCT.crit)} />
        <Tile wrapText t={t} id="heap" label={t('mx.kpi.heap')} tone={kpi.heap.tone} value={fmtPct(kpi.heap.value)}
          sub={t('mx.kpi.maxOfPods')} hint={t('mx.hint.heap', MEMORY_PCT.warn, MEMORY_PCT.crit)} />
        <Tile wrapText t={t} id="db" label={t('mx.kpi.db')} tone={kpi.db.tone} value={fmtPct(kpi.db.value)}
          sub={kpi.db.pending != null ? t('mx.kpi.dbPending', fmtNum(kpi.db.pending)) : null} hint={t('mx.hint.db')} />
        <Tile wrapText t={t} id="queue" label={t('mx.kpi.queue')} tone={kpi.queue.tone} value={fmtPct(kpi.queue.value)}
          sub={kpi.queue.queued != null ? t('mx.kpi.queueSub', fmtNum(kpi.queue.queued), fmtNum(kpi.queue.callerRuns)) : null} hint={t('mx.hint.queue', QUEUE_PCT.warn, QUEUE_PCT.crit)} />
        <Tile wrapText t={t} id="http" label={t('mx.kpi.http')} tone={kpi.http.tone} value={fmtNum(kpi.http.requests)}
          sub={kpi.http.requests != null ? t('mx.kpi.httpSub', fmtPct(kpi.http.errPct), fmtMs(kpi.http.p95)) : null}
          hint={t('mx.hint.http', HTTP_ERR_PCT.warn, HTTP_ERR_PCT.crit)} />
        <Tile wrapText t={t} id="logs" label={t('mx.kpi.logs')} tone={kpi.logs.tone} value={fmtNum(kpi.logs.errors)}
          sub={kpi.logs.warns != null ? t('mx.kpi.logsSub', fmtNum(kpi.logs.warns)) : null} hint={t('mx.hint.logs')} />
      </div>

      <Panel title={<span className="inline-flex items-center gap-2"><Server aria-hidden="true" className="size-4 text-primary" />{t('mx.sec.pods')}</span>}
        data-slot="mx-sec" data-sec="pods">
        <PodList t={t} pods={overview?.pods} servedBy={overview?.served_by} failed={overviewUnread} />
      </Panel>

      {!charts ? (
        failed.series ? (
          <p data-slot="mx-charts-failed" role="status" className="m-0 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">{t('mx.chart.failed')}</p>
        ) : <LoadingBlock />
      ) : (
        <>
          <Panel data-slot="mx-sec" data-sec="jvm"
            title={<span className="inline-flex items-center gap-2"><Cpu aria-hidden="true" className="size-4 text-primary" />{t('mx.sec.jvm')}</span>}>
            <ChartGrid>
              <MetricChart id="cpu" title={t('mx.chart.cpu')} format={fmtPct} thresholds={CPU_PCT} yMax={100}
                current={fmtPct(latestValue(charts.cpu, keys))} {...podsLine(charts.cpu)} />
              <MetricChart id="heap" title={t('mx.chart.heap')} format={fmtPct} thresholds={HEAP_CHART_PCT} yMax={100}
                current={fmtPct(latestValue(charts.heap, keys))} {...podsLine(charts.heap)} />
              <MetricChart id="threads" title={t('mx.chart.threads')} format={(v) => fmtNum(v)} allowDecimals={false}
                current={fmtNum(latestValue(charts.threads, keys))} {...podsLine(charts.threads)} />
              <MetricChart id="gc" title={t('mx.chart.gc')} format={fmtRateMs} axisFormat={fmtRate}
                current={fmtRateMs(latestValue(charts.gc, keys))} {...podsLine(charts.gc)} />
            </ChartGrid>
          </Panel>

          <Panel data-slot="mx-sec" data-sec="http"
            title={<span className="inline-flex items-center gap-2"><Globe aria-hidden="true" className="size-4 text-primary" />{t('mx.sec.http')}</span>}>
            <ChartGrid>
              <MetricChart id="rpm" title={t('mx.chart.rpm')} format={fmtRate} {...chartProps} rows={charts.http}
                series={[{ key: 'rpm', label: t('mx.series.rpm'), color: 'var(--chart-2)', shape: 'area' }]}
                current={fmtRate(latestValue(charts.http, ['rpm']))} />
              <MetricChart id="errpct" title={t('mx.chart.errPct')} format={fmtPct} thresholds={HTTP_ERR_PCT} {...chartProps} rows={charts.http}
                series={[{ key: 'errPct', label: t('mx.series.errPct'), color: 'var(--destructive)', shape: 'area' }]}
                current={fmtPct(latestValue(charts.http, ['errPct']))} />
              <MetricChart id="latency" title={t('mx.chart.latency')} format={fmtMs} axisFormat={(v) => fmtNum(v)} thresholds={HTTP_MS}
                {...chartProps} rows={charts.http}
                series={[{ key: 'avg', label: t('chart.avg'), color: 'var(--chart-1)', shape: 'line' },
                  { key: 'p95', label: t('mx.series.p95'), color: 'var(--chart-1)', shape: 'dashed' }]}
                current={fmtMs(latestValue(charts.http, ['avg']))} />
            </ChartGrid>
          </Panel>

          <Panel data-slot="mx-sec" data-sec="db"
            title={<span className="inline-flex items-center gap-2"><Database aria-hidden="true" className="size-4 text-primary" />{t('mx.sec.db')}</span>}>
            <ChartGrid>
              <MetricChart id="dbActive" title={t('mx.chart.dbActive')} format={(v) => fmtNum(v)} allowDecimals={false}
                current={fmtNum(latestValue(charts.dbActive, keys))} {...podsLine(charts.dbActive)} />
              <MetricChart id="dbPending" title={t('mx.chart.dbPending')} format={(v) => fmtNum(v)} allowDecimals={false}
                current={fmtNum(latestValue(charts.dbPending, keys))} {...podsLine(charts.dbPending)} />
            </ChartGrid>
          </Panel>

          <Panel data-slot="mx-sec" data-sec="exec"
            title={<span className="inline-flex items-center gap-2"><Layers aria-hidden="true" className="size-4 text-primary" />{t('mx.sec.exec')}</span>}>
            <ChartGrid>
              <MetricChart id="execActive" title={t('mx.chart.execActive')} format={(v) => fmtNum(v)} allowDecimals={false}
                current={fmtNum(latestValue(charts.execActive, keys))} {...podsLine(charts.execActive)} />
              <MetricChart id="execQueue" title={t('mx.chart.execQueue')} format={(v) => fmtNum(v)} allowDecimals={false}
                current={fmtNum(latestValue(charts.execQueue, keys))} {...podsLine(charts.execQueue)} />
              <MetricChart id="execDone" title={t('mx.chart.execDone')} format={fmtRate}
                current={fmtRate(latestValue(charts.execDone, keys))} {...podsLine(charts.execDone)} />
              <MetricChart id="callerRuns" title={t('mx.chart.callerRuns')} format={fmtRate}
                {...chartProps} rows={charts.logs}
                series={[{ key: 'callerRuns', label: t('mx.series.callerRuns'), color: 'var(--chart-3)', shape: 'area' }]}
                current={fmtRate(latestValue(charts.logs, ['callerRuns']))} />
            </ChartGrid>
          </Panel>

          <Panel data-slot="mx-sec" data-sec="logs"
            title={<span className="inline-flex items-center gap-2"><ScrollText aria-hidden="true" className="size-4 text-primary" />{t('mx.sec.logs')}</span>}>
            <ChartGrid>
              <MetricChart id="logs" title={t('mx.chart.logs')} format={fmtRate} {...chartProps} rows={charts.logs}
                series={[{ key: 'error', label: t('mx.series.logError'), color: 'var(--destructive)', shape: 'area' },
                  { key: 'warn', label: t('mx.series.logWarn'), color: 'var(--chart-3)', shape: 'line' }]}
                current={fmtRate(latestValue(charts.logs, ['error']))} />
              <MetricChart id="cache" title={t('mx.chart.cache')} format={fmtPct} yMax={100} {...chartProps} rows={charts.logs}
                series={[{ key: 'hitPct', label: t('mx.series.hitPct'), color: 'var(--chart-2)', shape: 'line' }]}
                current={fmtPct(latestValue(charts.logs, ['hitPct']))} />
            </ChartGrid>
          </Panel>
        </>
      )}

      <p data-slot="mx-footer" className="m-0 text-center text-xs text-muted-foreground">{t('mx.footer')}</p>
    </div>
  )
}
