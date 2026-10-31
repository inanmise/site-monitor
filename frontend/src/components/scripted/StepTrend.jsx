import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { ChartSpline, RotateCw } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { SeriesKey } from '../responsechart/SeriesKey.jsx'
import { formatTick, maxTicksFor, niceTimeTicks } from '../responsechart/responseChartModel.js'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { ChartContainer, ChartTooltip, ComposedChart, Line, XAxis, YAxis, CartesianGrid } from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'
import { humanizeMs } from './scriptedCardModel.js'
import { ROOT_STEP, stepSeriesData } from './runDetailModel.js'

/** Aralık seçenekleri (gün) — sunucu 1–30 kabul eder. */
export const STEP_TREND_DAYS = [1, 7, 30]
/** Açılışta çizilen en fazla seri sayısı (en sık adımlar önce gelir) — 15 çizgi tek grafikte okunmaz. */
export const STEP_TREND_INITIAL = 5

const msText = (ms) => {
  const p = humanizeMs(ms)
  return p ? `${p.num} ${p.unit}` : '—'
}

/**
 * ADIM TRENDİ (2026-10-31, k6r D4) — senaryonun adımlarının (k6 `group`) süresi zaman içinde, adım başına bir çizgi;
 * başarısız koşumdaki nokta kırmızı halkalı işaretle. Veri `GET /scripted/{id}/step-series?days=N` (yalnız zamanlanmış
 * koşumlar, en sık ≤ 15 adım). Durumlar AYRI: yükleniyor (iskelet), okunamadı (hata + Yeniden dene — "veri yok"
 * DEĞİL), sunucu desteklemiyor (eski pod 404/405 — nötr not), boş (adım üreten koşum yok), veri. Ekran okuyucu özeti
 * grafiğin altında. Kancalar: `step-trend`, `step-trend-chart`, `step-trend-empty`, `step-trend-error`,
 * `step-trend-unsupported`, `step-trend-series` (+ `data-series`), `step-trend-fail-dot`.
 */
export default function StepTrend({ monitorId }) {
  const t = useT()
  const locale = useDateLocale()
  const uid = useId().replace(/:/g, '')
  const titleId = `st-title-${uid}`
  const summaryId = `st-sum-${uid}`
  const [days, setDays] = useState(7)
  const [state, setState] = useState({ status: 'loading' })
  const [visible, setVisible] = useState(null)   // null = varsayılan (ilk N seri)
  const [measureRef, width] = useElementWidth()
  const seq = useRef(0)

  const load = useCallback(async () => {
    const my = ++seq.current
    setState((s) => ({ status: 'loading', prev: s.data ?? s.prev ?? null }))
    try {
      const r = await api.monitoring.scriptedStepSeries(monitorId, days)
      if (my !== seq.current) return
      if (r?.success) setState({ status: 'ok', data: r.data || {} })
      else if (r?.status === 404 || r?.status === 405) setState({ status: 'unsupported' })
      else setState({ status: 'error', error: r?.error || t('scripted.trend.loadFailed') })
    } catch (e) {
      if (my === seq.current) setState({ status: 'error', error: e?.message || t('scripted.trend.loadFailed') })
    }
  }, [monitorId, days, t])

  useEffect(() => { load() }, [load])

  const data = state.status === 'ok' ? state.data : state.prev
  const shaped = useMemo(() => stepSeriesData(data), [data])
  const shown = visible ?? shaped.series.slice(0, STEP_TREND_INITIAL).map((s) => s.key)
  const values = shaped.rows
  const domain = values.length ? [values[0].t, values.at(-1).t] : null
  const plotWidth = Math.max(0, (width || 640) - 70)
  const ticks = useMemo(() => (domain ? niceTimeTicks(domain[0], domain[1] === domain[0] ? domain[0] + 60_000 : domain[1], maxTicksFor(plotWidth)) : null),
    [domain?.[0], domain?.[1], plotWidth])   // eslint-disable-line react-hooks/exhaustive-deps
  const config = useMemo(() => Object.fromEntries(shaped.series.map((s) => [s.key, { label: labelOf(t, s.name), color: s.color }])), [shaped.series, t])
  const nameOf = (s) => labelOf(t, s.name)

  const head = (
    <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
      <h4 id={titleId} className="m-0 flex items-center gap-1.5 text-sm font-semibold">
        <ChartSpline aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />{t('scripted.trend.title')}
      </h4>
      <ToggleGroup type="single" variant="outline" size="sm" value={String(days)} data-slot="step-trend-days"
        aria-label={t('scripted.trend.range')} onValueChange={(v) => { if (v) setDays(Number(v)) }} className="flex-wrap justify-start">
        {STEP_TREND_DAYS.map((d) => (
          <ToggleGroupItem key={d} value={String(d)} className="h-8 px-2.5 text-xs pointer-coarse:h-10">{t('scripted.trend.days', d)}</ToggleGroupItem>
        ))}
      </ToggleGroup>
    </div>
  )

  let body
  if (state.status === 'unsupported') {
    body = <div data-slot="step-trend-unsupported"><StatusBlock tone="neutral" title={t('scripted.trend.unsupported')} className="py-6" /></div>
  } else if (state.status === 'error' && !data) {
    body = (
      <div data-slot="step-trend-error">
        <AlertBanner tone="danger" role="alert" className="mb-0" title={t('scripted.trend.loadFailed')}
          actions={<Button type="button" size="sm" variant="outline" onClick={load} className="pointer-coarse:h-10"><RotateCw aria-hidden="true" />{t('hist.retry')}</Button>}>
          {String(state.error)}
        </AlertBanner>
      </div>
    )
  } else if (!data) {
    body = <div data-slot="step-trend-skeleton" className="flex flex-col gap-2"><Skeleton className="h-8 w-full max-w-md motion-reduce:animate-none" /><Skeleton className="h-48 w-full motion-reduce:animate-none" /></div>
  } else if (!shaped.series.length || !values.length) {
    body = <div data-slot="step-trend-empty"><StatusBlock tone="neutral" title={t('scripted.trend.empty', days)} description={t('scripted.trend.emptyHint')} className="py-6" /></div>
  } else {
    const failDot = (key) => function StepDot(props) {
      const { cx, cy, payload, index } = props
      if (cx == null || cy == null || !payload?.[`${key}f`]) return <g key={`${key}${index}`} />
      return <circle key={`${key}${index}`} cx={cx} cy={cy} r={4} fill="var(--destructive)" stroke="var(--card)" strokeWidth={1.5} data-slot="step-trend-fail-dot" />
    }
    const slowest = shaped.series
      .map((s) => ({ s, max: Math.max(0, ...values.map((r) => r[s.key] ?? 0)) }))
      .sort((a, b) => b.max - a.max)[0]
    body = (
      <>
        <ToggleGroup type="multiple" variant="outline" size="sm" data-slot="step-trend-series" className="flex-wrap justify-start"
          aria-label={t('scripted.trend.series')} value={shown} onValueChange={(v) => setVisible(v)}>
          {shaped.series.map((s) => (
            <ToggleGroupItem key={s.key} value={s.key} data-series={s.key}
              className="h-auto min-h-8 max-w-full gap-1.5 rounded-md px-2 py-1 text-left text-xs font-medium whitespace-normal data-[state=off]:text-muted-foreground data-[state=off]:opacity-75 data-[state=on]:bg-muted pointer-coarse:min-h-10">
              <SeriesKey shape="line" color={s.color} />
              <span className="min-w-0 [overflow-wrap:anywhere]">{nameOf(s)}</span>
              {s.failed > 0 && <span className="text-destructive tabular-nums">({t('scripted.trend.badShort', s.failed)})</span>}
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
        <figure aria-labelledby={titleId} aria-describedby={summaryId} ref={measureRef} className="m-0 flex min-w-0 flex-col gap-1.5">
          {shown.length === 0 ? (
            <p className="m-0 rounded-md bg-muted/50 px-3 py-2 text-xs text-muted-foreground">{t('scripted.trend.noSeries')}</p>
          ) : (
            <ChartContainer config={config} data-slot="step-trend-chart" className={cn('aspect-auto h-[220px] w-full sm:h-[260px]', state.status === 'loading' && 'opacity-60')}>
              <ComposedChart data={values} margin={{ top: 12, right: 12, left: 0, bottom: 0 }}>
                <CartesianGrid vertical={false} />
                <XAxis dataKey="t" type="number" scale="time" domain={domain} ticks={ticks?.ticks} interval={0}
                  tickFormatter={(v) => (ticks ? formatTick(v, { step: ticks.step, span: ticks.span }, locale) : '')}
                  tickLine={false} axisLine={false} tickMargin={8} tick={{ fontSize: 11 }} />
                <YAxis width={58} tickLine={false} axisLine={false} tickFormatter={(v) => msText(v)} tick={{ fontSize: 11 }} />
                <ChartTooltip isAnimationActive={false} cursor={{ stroke: 'var(--muted-foreground)', strokeOpacity: 0.35, strokeWidth: 1 }}
                  content={<TrendTooltip t={t} series={shaped.series} nameOf={nameOf} />} />
                {shaped.series.filter((s) => shown.includes(s.key)).map((s) => (
                  <Line key={s.key} dataKey={s.key} type="monotone" stroke={s.color} strokeWidth={2} dot={failDot(s.key)}
                    activeDot={{ r: 3.5, fill: s.color, stroke: 'var(--card)', strokeWidth: 2 }} isAnimationActive={false} connectNulls={false} />
                ))}
              </ComposedChart>
            </ChartContainer>
          )}
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
            <span className="inline-flex items-center gap-1.5"><SeriesKey shape="dot" color="var(--destructive)" />{t('scripted.trend.failLegend')}</span>
            {data?.truncated && <span data-slot="step-trend-truncated">{t('scripted.trend.truncated')}</span>}
            {state.status === 'error' && <span className="text-destructive">{t('scripted.trend.refreshFailed')}</span>}
          </div>
          <p id={summaryId} data-slot="step-trend-summary" className="sr-only">
            {t('scripted.trend.summary', shaped.series.length, days, values.length, shaped.failed,
              slowest ? nameOf(slowest.s) : '—', slowest ? msText(slowest.max) : '—')}
          </p>
        </figure>
      </>
    )
  }

  return (
    <section data-slot="step-trend" aria-labelledby={titleId} className="mt-4 flex min-w-0 flex-col gap-2 border-t pt-3">
      {head}
      <p className="m-0 text-xs text-muted-foreground">{t('scripted.trend.desc')}</p>
      {body}
    </section>
  )
}

function labelOf(t, name) {
  return name === ROOT_STEP ? t('scripted.steps.root') : name
}

/** İpucu: koşum zamanı + her seri için süre (başarısızsa işaretli). */
function TrendTooltip({ active, payload, t, series, nameOf }) {
  if (!active || !Array.isArray(payload) || payload.length === 0) return null
  const row = payload.find((x) => x?.payload)?.payload
  if (!row) return null
  const keys = new Set(payload.map((p) => p?.dataKey))
  return (
    <div data-slot="step-trend-tooltip" className="grid w-max max-w-[18rem] min-w-[11rem] gap-1.5 rounded-lg border border-border/60 bg-background px-3 py-2 text-xs shadow-xl">
      <div className="font-semibold text-foreground tabular-nums">{formatDate(row.ts)}</div>
      {series.filter((s) => keys.has(s.key) && row[s.key] != null).map((s) => (
        <div key={s.key} className="flex items-center gap-2">
          <SeriesKey shape="line" color={s.color} />
          <span className="min-w-0 flex-1 text-muted-foreground [overflow-wrap:anywhere]">{nameOf(s)}</span>
          <span className={cn('font-semibold tabular-nums', row[`${s.key}f`] ? 'text-destructive' : 'text-foreground')}>
            {msText(row[s.key])}{row[`${s.key}f`] ? ` · ${t('scripted.trend.badTip')}` : ''}
          </span>
        </div>
      ))}
    </div>
  )
}
