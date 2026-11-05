import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { ChartSpline, RotateCw } from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { useLanguage, useT, useDateLocale } from '../../../i18n/index.jsx'
import { errorInfoOf } from '../../../utils/errorMessages.js'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { SeriesKey } from '../../responsechart/SeriesKey.jsx'
import { formatTick, maxTicksFor, niceTimeTicks } from '../../responsechart/responseChartModel.js'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { ChartContainer, ChartTooltip, ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid } from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'
import { bytesText } from '../content/contentChecksModel.js'
import { isLonePoint, seriesStats, shapeContentSeries } from './contentDetailModel.js'

/** Aralık seçenekleri (gün) — sunucu 1–90 kabul eder. */
export const CONTENT_SERIES_DAYS = [1, 7, 30, 90]
const OCC_COLOR = 'var(--chart-1)'
const BYTES_COLOR = 'var(--chart-4)'
const statusOf = (r) => Number(r?.status ?? errorInfoOf(r)?.status)

/**
 * EŞLEŞME SAYISI + SAYFA BOYUTU grafiği (kw paket D3 — tembel parça). Veri `GET /keyword/{id}/content-series?days=N`:
 * kova başına ilk kuralın eşleşme sayısı (en az – ortalama – en çok, bant + çizgi) ve ortalama gövde boyutu (sağ eksen);
 * içerik değişen kova amber noktayla işaretlenir. Kontrolü olmayan kova BOŞLUK (0 değil); komşusuz tek değer nokta
 * olarak çizilir. Durumlar AYRI: yükleniyor (iskelet), okunamadı (hata + Yeniden dene — "veri yok" DEĞİL), sunucu
 * desteklemiyor (404 / 405 — nötr not), boş (aralıkta kontrol yok), veri. Ekran okuyucu özeti grafiğin altında.
 *
 * <p>Kancalar: `kw-content-chart`, `-plot`, `-empty`, `-error`, `-unsupported`, `-skeleton`, `-capped`, `-summary`,
 * `-lone-dot`, `-change-dot`.
 */
export default function KeywordContentChart({ monitorId }) {
  const t = useT()
  const { lang } = useLanguage()
  const locale = useDateLocale()
  const uid = useId().replace(/:/g, '')
  const titleId = `kc-title-${uid}`
  const summaryId = `kc-sum-${uid}`
  const [days, setDays] = useState(7)
  const [state, setState] = useState({ status: 'loading' })
  const [measureRef, width] = useElementWidth()
  const seq = useRef(0)

  const load = useCallback(async () => {
    const my = ++seq.current
    setState((s) => ({ status: 'loading', prev: s.data ?? s.prev ?? null }))
    try {
      const r = await api.monitoring.keywordContentSeries(monitorId, { days })
      if (my !== seq.current) return
      if (r?.success) setState({ status: 'ok', data: r.data || {} })
      else if (statusOf(r) === 404 || statusOf(r) === 405) setState({ status: 'unsupported' })
      else setState((s) => ({ status: 'error', error: r?.error || t('keyword.content.seriesFailed'), prev: s.prev ?? null }))
    } catch (e) {
      if (my === seq.current) setState((s) => ({ status: 'error', error: e?.message || t('keyword.content.seriesFailed'), prev: s.prev ?? null }))
    }
  }, [monitorId, days, t])

  useEffect(() => { load() }, [load])
  useEffect(() => () => { seq.current++ }, [])

  const data = state.status === 'ok' ? state.data : state.prev
  const rows = useMemo(() => shapeContentSeries(data), [data])
  const stats = useMemo(() => seriesStats(rows), [rows])
  const domain = rows.length ? [rows[0].t, rows.at(-1).t] : null
  const plotWidth = Math.max(0, (width || 640) - 110)
  const ticks = useMemo(() => (domain ? niceTimeTicks(domain[0], domain[1] === domain[0] ? domain[0] + 60_000 : domain[1], maxTicksFor(plotWidth)) : null),
    [domain?.[0], domain?.[1], plotWidth])   // eslint-disable-line react-hooks/exhaustive-deps
  const config = useMemo(() => ({
    occAvg: { label: t('keyword.content.occ'), color: OCC_COLOR },
    occBand: { label: t('keyword.content.occRange'), color: OCC_COLOR },
    bytes: { label: t('keyword.content.bytes'), color: BYTES_COLOR },
  }), [t])

  const head = (
    <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
      <h4 id={titleId} className="m-0 flex items-center gap-1.5 text-sm font-semibold">
        <ChartSpline aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />{t('keyword.content.chartTitle')}
      </h4>
      <ToggleGroup type="single" variant="outline" size="sm" value={String(days)} data-slot="kw-content-chart-days"
        aria-label={t('keyword.content.range')} onValueChange={(v) => { if (v) setDays(Number(v)) }} className="flex-wrap justify-start">
        {CONTENT_SERIES_DAYS.map((d) => (
          <ToggleGroupItem key={d} value={String(d)} className="h-8 px-2.5 text-xs pointer-coarse:h-10">
            {d === 1 ? t('keyword.content.days1') : t('keyword.content.days', d)}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
    </div>
  )

  let body
  if (state.status === 'unsupported') {
    body = <div data-slot="kw-content-chart-unsupported"><StatusBlock tone="neutral" title={t('keyword.content.seriesUnsupported')} className="py-6" /></div>
  } else if (state.status === 'error' && !data) {
    body = (
      <div data-slot="kw-content-chart-error">
        <AlertBanner tone="danger" role="alert" className="mb-0" title={t('keyword.content.seriesFailed')}
          actions={<Button type="button" size="sm" variant="outline" onClick={load} className="pointer-coarse:h-10"><RotateCw aria-hidden="true" />{t('hist.retry')}</Button>}>
          {String(state.error)}
        </AlertBanner>
      </div>
    )
  } else if (!data) {
    body = <div data-slot="kw-content-chart-skeleton" className="flex flex-col gap-2"><Skeleton className="h-48 w-full motion-reduce:animate-none" /></div>
  } else if (!stats.checks) {
    body = <div data-slot="kw-content-chart-empty"><StatusBlock tone="neutral" title={t('keyword.content.seriesEmpty')} className="py-6" /></div>
  } else {
    const dot = (key, color) => function ContentDot(props) {
      const { cx, cy, payload, index } = props
      if (cx == null || cy == null) return <g key={`${key}${index}`} />
      if (key === 'occAvg' && payload?.changed) {
        return <circle key={`${key}${index}`} cx={cx} cy={cy} r={4} fill="var(--color-amber-500, #f59e0b)" stroke="var(--card)" strokeWidth={1.5} data-slot="kw-content-chart-change-dot" />
      }
      if (isLonePoint(rows, key, index)) {
        return <circle key={`${key}${index}`} cx={cx} cy={cy} r={3} fill={color} stroke="var(--card)" strokeWidth={1.5} data-slot="kw-content-chart-lone-dot" />
      }
      return <g key={`${key}${index}`} />
    }
    body = (
      <figure aria-labelledby={titleId} aria-describedby={summaryId} ref={measureRef} className="m-0 flex min-w-0 flex-col gap-1.5">
        <ChartContainer config={config} data-slot="kw-content-chart-plot" className={cn('aspect-auto h-[220px] w-full sm:h-[260px]', state.status === 'loading' && 'opacity-60')}>
          <ComposedChart data={rows} margin={{ top: 12, right: 8, left: 0, bottom: 0 }}>
            <CartesianGrid vertical={false} />
            <XAxis dataKey="t" type="number" scale="time" domain={domain} ticks={ticks?.ticks} interval={0}
              tickFormatter={(v) => (ticks ? formatTick(v, { step: ticks.step, span: ticks.span }, locale) : '')}
              tickLine={false} axisLine={false} tickMargin={8} tick={{ fontSize: 11 }} />
            <YAxis yAxisId="occ" width={44} allowDecimals={false} tickLine={false} axisLine={false} tick={{ fontSize: 11 }} />
            <YAxis yAxisId="bytes" orientation="right" width={58} tickLine={false} axisLine={false} tick={{ fontSize: 11 }}
              tickFormatter={(v) => bytesText(v, lang) || ''} />
            <ChartTooltip isAnimationActive={false} cursor={{ stroke: 'var(--muted-foreground)', strokeOpacity: 0.35, strokeWidth: 1 }}
              content={<ContentTooltip t={t} lang={lang} />} />
            <Area yAxisId="occ" dataKey="occBand" type="monotone" stroke="none" fill={OCC_COLOR} fillOpacity={0.18}
              isAnimationActive={false} connectNulls={false} activeDot={false} />
            <Line yAxisId="occ" dataKey="occAvg" type="monotone" stroke={OCC_COLOR} strokeWidth={2} dot={dot('occAvg', OCC_COLOR)}
              activeDot={{ r: 3.5, fill: OCC_COLOR, stroke: 'var(--card)', strokeWidth: 2 }} isAnimationActive={false} connectNulls={false} />
            <Line yAxisId="bytes" dataKey="bytes" type="monotone" stroke={BYTES_COLOR} strokeWidth={1.5} strokeDasharray="4 3"
              dot={dot('bytes', BYTES_COLOR)} activeDot={{ r: 3, fill: BYTES_COLOR, stroke: 'var(--card)', strokeWidth: 2 }}
              isAnimationActive={false} connectNulls={false} />
          </ComposedChart>
        </ChartContainer>
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
          <span className="inline-flex items-center gap-1.5"><SeriesKey shape="line" color={OCC_COLOR} />{t('keyword.content.occ')}</span>
          <span className="inline-flex items-center gap-1.5"><SeriesKey shape="area" color={OCC_COLOR} />{t('keyword.content.occRange')}</span>
          <span className="inline-flex items-center gap-1.5"><SeriesKey shape="dashed" color={BYTES_COLOR} />{t('keyword.content.bytes')}</span>
          <span className="inline-flex items-center gap-1.5"><SeriesKey shape="dot" color="var(--color-amber-500, #f59e0b)" />{t('keyword.content.changedLegend')}</span>
          {data?.capped && <span data-slot="kw-content-chart-capped">{t('keyword.content.seriesCapped')}</span>}
          {state.status === 'error' && <span className="text-destructive">{t('keyword.content.refreshFailed')}</span>}
        </div>
        <p id={summaryId} data-slot="kw-content-chart-summary" className="sr-only">
          {t('keyword.content.summary', stats.buckets, stats.checks, stats.failed, stats.maxOcc ?? '—', stats.changed)}
        </p>
      </figure>
    )
  }

  return (
    <section data-slot="kw-content-chart" aria-labelledby={titleId} className="flex min-w-0 flex-col gap-2">
      {head}
      <p className="m-0 text-xs text-muted-foreground">{t('keyword.content.chartDesc')}</p>
      {body}
    </section>
  )
}

/** İpucu: kova zamanı, kontrol / başarısız, eşleşme (en az–ort–en çok), ortalama boyut, değişim işareti. */
function ContentTooltip({ active, payload, t, lang }) {
  if (!active || !Array.isArray(payload) || payload.length === 0) return null
  const row = payload.find((x) => x?.payload)?.payload
  if (!row) return null
  return (
    <div data-slot="kw-content-chart-tooltip" className="grid w-max max-w-[18rem] min-w-[11rem] gap-1 rounded-lg border border-border/60 bg-background px-3 py-2 text-xs shadow-xl">
      <div className="font-semibold text-foreground tabular-nums">{formatDate(row.ts)}</div>
      <div className="text-muted-foreground">{t('keyword.content.tooltipChecks', row.checks, row.failed)}</div>
      {row.occAvg != null && (
        <div className="flex items-center gap-2"><SeriesKey shape="line" color={OCC_COLOR} />
          <span className="tabular-nums">{t('keyword.content.tooltipOcc', row.occMin ?? '—', row.occAvg, row.occMax ?? '—')}</span></div>
      )}
      {row.bytes != null && (
        <div className="flex items-center gap-2"><SeriesKey shape="dashed" color={BYTES_COLOR} />
          <span className="tabular-nums">{bytesText(Math.round(row.bytes), lang)}</span></div>
      )}
      {row.changed && <div className="font-semibold text-amber-700 dark:text-amber-300">{t('keyword.content.changedLegend')}</div>}
    </div>
  )
}
