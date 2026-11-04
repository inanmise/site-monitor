import { useId, useMemo } from 'react'
import { LineChart as LineChartIcon } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import { SeriesKey } from '../../responsechart/SeriesKey.jsx'
import { axisWidth, formatTick, maxTicksFor, niceTimeTicks } from '../../responsechart/responseChartModel.js'
import ToneBadge from '../../admin/ToneBadge.jsx'
import {
  ChartContainer, ChartTooltip, ComposedChart, Line, XAxis, YAxis, CartesianGrid, ReferenceLine,
} from '@/components/shadcn/chart'
import { SectionCard } from './detailParts.jsx'
import { formatIstanbul, seriesModel, statusLabel } from './activityDetailModel.js'

/** Az noktalı seride her ölçüm işaretlenir (seyrek veri okunur kalsın) — CertDaysTrend / ChartPanel ile aynı eşik. */
const SPARSE_POINTS = 40

/** İpucu: zaman (İstanbul) · yanıt süresi · durum. Boş kova ipucu üretmez. */
function SeriesTip({ active, payload, t, locale }) {
  if (!active || !Array.isArray(payload) || payload.length === 0) return null
  const p = payload.find((x) => x?.payload)?.payload
  if (!p) return null
  return (
    <div data-slot="chart-tooltip" className="grid w-max max-w-[16rem] min-w-[10rem] gap-1 rounded-lg border border-border/60 bg-background px-3 py-2 text-xs shadow-xl">
      <div className="font-semibold text-foreground tabular-nums">{formatIstanbul(p.x, locale)}</div>
      <div className="flex items-center justify-between gap-3">
        <span className="text-muted-foreground">{t('act.dt.m.response')}</span>
        <span className="font-semibold tabular-nums">{p.ms != null ? `${Math.round(p.ms)} ms` : '—'}</span>
      </div>
      <div className={p.fail ? 'font-medium text-destructive' : p.warn ? 'font-medium text-amber-700 dark:text-amber-300' : 'text-muted-foreground'}>
        {statusLabel(p.status, t)}
      </div>
    </div>
  )
}

/**
 * ±24 SAAT (A-4): aynı izlemenin bu olaydan önceki ve sonraki 24 saatteki kontrolleri — yanıt süresi çizgisi, hatalı
 * kontroller kırmızı halkalı nokta (yanıt süresi yoksa tabanda), uyarılar amber, BU OLAY kesikli dikey çizgi. Boş kova
 * çizgide BOŞLUK (sıfır değil); iki yanı boş tek ölçüm nokta olarak çizilir. Üstte özet çipleri (kontrol / hata / uyarı /
 * ortalama / en yüksek), ekran okuyucuya aynı özet metin olarak. Eksen etiketleri izleme grafikleriyle aynı yardımcılar.
 * Test kancaları: `act-dt-series` (`data-state` ready|empty), `act-dt-series-chart`, `act-dt-series-summary`.
 */
export default function ActivitySeriesChart({ series, anchorIso, locale }) {
  const t = useT()
  const uid = useId().replace(/:/g, '')
  const sumId = `act-dt-sum-${uid}`
  const [measureRef, width] = useElementWidth()
  const model = useMemo(() => seriesModel(series, anchorIso), [series, anchorIso])
  const { points, counts } = model
  const domain = [model.from ?? points[0]?.x ?? 0, model.to ?? points.at(-1)?.x ?? 1]
  const maxLabel = model.maxMs != null ? `${Math.round(model.maxMs)} ms` : '0 ms'
  const yWidth = axisWidth([maxLabel, '0 ms'])
  const plotWidth = Math.max(0, (width || 640) - yWidth - 40)
  const { ticks, step, span } = useMemo(() => niceTimeTicks(domain[0], domain[1], maxTicksFor(plotWidth)), [domain[0], domain[1], plotWidth]) // eslint-disable-line react-hooks/exhaustive-deps
  const sparse = points.filter((p) => p.ms != null).length <= SPARSE_POINTS
  const hasFail = counts.fail > 0
  const hasWarn = counts.warn > 0
  const config = {
    ms: { label: t('act.dt.m.response'), color: 'var(--primary)' },
    fail: { label: t('act.dt.ser.fail'), color: 'var(--destructive)' },
    warn: { label: t('act.dt.ser.warn'), color: 'var(--warning)' },
  }
  const msDot = ({ cx, cy, payload, index }) => {
    if (cy == null || payload?.ms == null) return <g key={`m${index}`} />
    if (payload.warn) return <circle key={`m${index}`} cx={cx} cy={cy} r={3.5} fill="var(--color-warn)" stroke="var(--card)" strokeWidth={1.5} />
    if (!sparse && !payload.lone) return <g key={`m${index}`} />
    return <circle key={`m${index}`} cx={cx} cy={cy} r={payload.lone ? 3 : 2.5} fill="var(--color-ms)" stroke="var(--card)" strokeWidth={1.5} />
  }
  const failDot = ({ cx, cy, payload, index }) => (cy == null || payload?.failY == null
    ? <g key={`f${index}`} />
    : <circle key={`f${index}`} cx={cx} cy={cy} r={4} fill="var(--color-fail)" stroke="var(--card)" strokeWidth={2} />)
  const summary = t('act.dt.ser.summary', counts.total, counts.fail, counts.warn,
    model.avgMs != null ? `${model.avgMs} ms` : '—', model.maxMs != null ? `${Math.round(model.maxMs)} ms` : '—')

  return (
    <SectionCard slot="act-dt-series" icon={LineChartIcon} title={t('act.dt.ser.title')} description={t('act.dt.ser.desc')}
      data-state={model.empty ? 'empty' : 'ready'}>
      {model.empty ? (
        <p className="m-0 text-sm text-muted-foreground">{t('act.dt.ser.empty')}</p>
      ) : (
        <>
          <div data-slot="act-dt-series-summary" className="flex min-w-0 flex-wrap items-center gap-1.5 text-xs" aria-hidden="true">
            <ToneBadge tone="muted" className="h-auto rounded-md whitespace-normal tabular-nums">{t('act.dt.ser.checks', counts.total)}</ToneBadge>
            <ToneBadge tone={hasFail ? 'danger' : 'muted'} className="h-auto rounded-md whitespace-normal tabular-nums">{t('act.dt.ser.fails', counts.fail)}</ToneBadge>
            {hasWarn && <ToneBadge tone="warning" className="h-auto rounded-md whitespace-normal tabular-nums">{t('act.dt.ser.warns', counts.warn)}</ToneBadge>}
            {model.avgMs != null && <ToneBadge tone="muted" className="h-auto rounded-md whitespace-normal tabular-nums">{t('act.dt.ser.avg', `${model.avgMs} ms`)}</ToneBadge>}
            {model.maxMs != null && <ToneBadge tone="muted" className="h-auto rounded-md whitespace-normal tabular-nums">{t('act.dt.ser.max', `${Math.round(model.maxMs)} ms`)}</ToneBadge>}
            {model.truncated && <ToneBadge tone="info" className="h-auto rounded-md whitespace-normal">{t('act.dt.ser.truncated')}</ToneBadge>}
          </div>
          <figure className="m-0 min-w-0" aria-describedby={sumId} ref={measureRef}>
            <ChartContainer config={config} data-slot="act-dt-series-chart" className="aspect-auto h-[180px] w-full sm:h-[220px]">
              <ComposedChart data={points} margin={{ top: 8, right: 28, left: 0, bottom: 0 }}>
                <CartesianGrid vertical={false} />
                <XAxis dataKey="x" type="number" scale="time" domain={domain} ticks={ticks} interval={0} allowDataOverflow
                  tickFormatter={(v) => formatTick(v, { step, span })} tickLine={false} axisLine={false} tickMargin={8} tick={{ fontSize: 11 }} />
                <YAxis domain={[0, 'auto']} tickCount={4} allowDecimals={false} width={yWidth} tickLine={false} axisLine={false}
                  tick={{ fontSize: 11 }} tickFormatter={(v) => `${v} ms`} />
                <ChartTooltip isAnimationActive={false} cursor={{ stroke: 'var(--muted-foreground)', strokeOpacity: 0.35, strokeWidth: 1 }}
                  content={<SeriesTip t={t} locale={locale} />} />
                {model.anchor != null && (
                  <ReferenceLine x={model.anchor} stroke="var(--foreground)" strokeOpacity={0.55} strokeDasharray="4 3" strokeWidth={1.5} ifOverflow="extendDomain" />
                )}
                <Line dataKey="ms" type="linear" stroke="var(--color-ms)" strokeWidth={2} dot={msDot}
                  activeDot={{ r: 4, fill: 'var(--color-ms)', stroke: 'var(--card)', strokeWidth: 2 }} isAnimationActive={false} connectNulls={false} />
                {hasFail && (
                  <Line dataKey="failY" stroke="none" dot={failDot} activeDot={false} legendType="none" isAnimationActive={false} connectNulls={false} />
                )}
              </ComposedChart>
            </ChartContainer>
            <figcaption className="mt-2 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
              <span className="inline-flex items-center gap-1.5"><SeriesKey shape="line" color="var(--primary)" />{t('act.dt.m.response')}</span>
              {hasFail && <span className="inline-flex items-center gap-1.5"><SeriesKey shape="dot" color="var(--destructive)" />{t('act.dt.ser.fail')}</span>}
              {hasWarn && <span className="inline-flex items-center gap-1.5"><SeriesKey shape="dot" color="var(--warning)" />{t('act.dt.ser.warn')}</span>}
              {model.anchor != null && <span className="inline-flex items-center gap-1.5"><SeriesKey shape="dashed" color="var(--foreground)" />{t('act.dt.ser.anchor')}</span>}
            </figcaption>
            <p id={sumId} className="sr-only">{summary}</p>
          </figure>
        </>
      )}
    </SectionCard>
  )
}
