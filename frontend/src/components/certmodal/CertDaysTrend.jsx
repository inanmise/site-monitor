import { useId, useMemo } from 'react'
import { RefreshCcw } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatDate } from '../../api/client'
import { Spinner } from '../ui/Progress.jsx'
import { SeriesKey } from '../responsechart/SeriesKey.jsx'
import { axisWidth, bucketEndLabel, formatTick, maxTicksFor, niceTimeTicks, shortDateTime } from '../responsechart/responseChartModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import {
  ChartContainer, ChartTooltip, ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid, ReferenceLine,
} from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'
import { daysDomain } from './certHistoryModel.js'
import { daysText } from './CertHistoryRow.jsx'
// Kabın genişliği (ResizeObserver, clientWidth) — x etiket yoğunluğu çizim alanına göre (ChartPanel ile aynı kanca, öneri 29).
import { useElementWidth } from '../../hooks/useElementWidth.js'

/** Az noktalı seride her ölçüm işaretlenir (seyrek veri okunur kalsın) — ChartPanel SPARSE_POINTS eşdeğeri. */
const SPARSE_POINTS = 40
/** Yenileme düğmesi sayısı; fazlası "+N" (aralık daraltılınca hepsi görünür). */
const MAX_JUMPS = 4

function TipRow({ label, value, keyShape, color }) {
  return (
    <div className="flex items-center gap-2">
      {keyShape ? <SeriesKey shape={keyShape} color={color} /> : <span aria-hidden="true" className="w-3.5 shrink-0" />}
      <span className="min-w-0 flex-1 truncate text-muted-foreground">{label}</span>
      <span className="font-semibold text-foreground tabular-nums">{value}</span>
    </div>
  )
}

/** İpucu: kova zamanı (kurum saati) · kalan gün · kontrol sayısı · durum · yenileme işareti. Boşluk noktası ipucu üretmez. */
function TrendTip({ active, payload, t, bucketMs }) {
  if (!active || !Array.isArray(payload) || payload.length === 0) return null
  const p = payload.find((x) => x?.payload)?.payload
  if (!p || p.gap) return null
  return (
    <div data-slot="chart-tooltip" className="grid w-max max-w-[17rem] min-w-[11rem] gap-1.5 rounded-lg border border-border/60 bg-background px-3 py-2 text-xs shadow-xl">
      <div className="font-semibold text-foreground tabular-nums">
        {formatDate(p.ts)}{bucketMs > 60_000 && <span className="font-normal text-muted-foreground"> – {bucketEndLabel(p.t, bucketMs)}</span>}
      </div>
      <TipRow label={t('certh.tipDays')} value={p.days != null ? daysText(p.days, t) : '—'} keyShape="line" color="var(--color-days)" />
      <TipRow label={t('rtc.tip.checks')} value={p.count.toLocaleString()} />
      <div className={cn('border-t pt-1.5 font-medium', p.down > 0 ? 'text-destructive' : 'text-success')}>
        {p.down > 0 ? t('rtc.tip.failed', p.down, p.count) : t('rtc.tip.allOk')}
      </div>
      {p.renewed && <div className="font-semibold text-success">{t('certh.tipRenewed')}</div>}
    </div>
  )
}

/**
 * Kalan gün eğilimi — shadcn Chart (ChartContainer + recharts), Grafik sekmesiyle aynı dağarcık (zaman ekseni etiketleri,
 * ipucu kartı, seri anahtarları). TEK eksen: kalan gün. İşaretler: yenileme anı (yeşil dikey çizgi + halkalı nokta),
 * başarısız kontrol içeren kova (tabanda kırmızı halkalı nokta). Kontrol yapılmayan dilimde çizgi KOPAR.
 *
 * Erişilebilirlik: `figure` başlık + ekran okuyucu özeti (`sr-only`); işaretlerin anlamı görünür açıklamada (renk tek
 * başına değil); yenileme anları ayrıca METİN düğmeleri — dokununca geçmiş o ana daralır (`onJump`). Değerlerin tablo
 * karşılığı hemen alttaki kontrol listesidir. Tembel yüklenir (recharts ağır): CertHistoryInsights.
 */
export default function CertDaysTrend({ shape, busy = false, onJump, className }) {
  const t = useT()
  const uid = useId().replace(/:/g, '')
  const titleId = `cdt-title-${uid}`
  const sumId = `cdt-sum-${uid}`
  const gradId = `cdt-grad-${uid}`
  const [measureRef, width] = useElementWidth()

  const [lo, hi] = daysDomain(shape.min, shape.max)
  const data = useMemo(() => shape.points.map((p) => ({
    ...p,
    failY: p.down > 0 ? lo : null,
    renewY: p.renewed && p.days != null ? p.days : null,
  })), [shape.points, lo])
  const yWidth = axisWidth([String(hi), String(lo)])
  const plotWidth = Math.max(0, (width || 640) - yWidth - 24)
  const { ticks, step, span } = useMemo(
    () => niceTimeTicks(shape.domain[0], shape.domain[1], maxTicksFor(plotWidth)),
    [shape.domain, plotWidth],
  )
  const hasFail = shape.points.some((p) => p.down > 0)
  const renewals = shape.renewals
  const sparse = shape.points.filter((p) => p.days != null).length <= SPARSE_POINTS
  const config = {
    days: { label: t('certh.tipDays'), color: 'var(--chart-5)' },
    renew: { label: t('certh.renewed'), color: 'var(--success)' },
    fail: { label: t('certh.fail'), color: 'var(--destructive)' },
  }

  const dayDot = ({ cx, cy, payload, index }) => (cy == null || payload?.days == null || !sparse
    ? <g key={`d${index}`} />
    : <circle key={`d${index}`} cx={cx} cy={cy} r={2.5} fill="var(--color-days)" stroke="var(--card)" strokeWidth={1.5} />)
  const renewDot = ({ cx, cy, payload, index }) => (cy == null || payload?.renewY == null
    ? <g key={`r${index}`} />
    : <circle key={`r${index}`} cx={cx} cy={cy} r={4.5} fill="var(--color-renew)" stroke="var(--card)" strokeWidth={2} />)
  const failDot = ({ cx, cy, payload, index }) => (cy == null || payload?.failY == null
    ? <g key={`f${index}`} />
    : <circle key={`f${index}`} cx={cx} cy={cy} r={4} fill="var(--color-fail)" stroke="var(--card)" strokeWidth={2} />)

  const first = shape.first
  const last = shape.last
  const summary = t('certh.trendSummary', daysText(first.days, t), daysText(last.days, t), renewals.length, shape.failed)

  return (
    <Card data-slot="cert-days-trend" data-state="ready" aria-busy={busy || undefined}
      className={cn('gap-2.5 px-3 py-3 shadow-none sm:px-4', className)}>
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1.5">
        <div className="flex min-w-0 items-center gap-2">
          <h4 id={titleId} className="m-0 text-sm font-semibold text-foreground">{t('certh.trendTitle')}</h4>
          {busy && <Spinner size={14} label={t('certh.trendUpdating')} className="text-primary" />}
        </div>
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
          <Badge variant="outline" data-slot="cert-trend-change" className="font-semibold text-foreground tabular-nums">
            {t('certh.trendChange', first.days, daysText(last.days, t))}
          </Badge>
          {/* İşaret açıklaması — yalnız çizilen işaretler; renk + biçim + METİN. */}
          <span className="inline-flex items-center gap-1.5"><SeriesKey shape="line" color="var(--chart-5)" />{t('certh.tipDays')}</span>
          {renewals.length > 0 && (
            <span className="inline-flex items-center gap-1.5"><SeriesKey shape="dot" color="var(--success)" />{t('certh.legendRenewal')}</span>
          )}
          {hasFail && (
            <span className="inline-flex items-center gap-1.5"><SeriesKey shape="dot" color="var(--destructive)" />{t('certh.legendFail')}</span>
          )}
        </div>
      </div>

      <figure aria-labelledby={titleId} aria-describedby={sumId} className={cn('m-0 min-w-0 transition-opacity motion-reduce:transition-none', busy && 'opacity-60')}
        ref={measureRef}>
        <ChartContainer config={config} data-slot="cert-trend-chart" className="aspect-auto h-[132px] w-full sm:h-[150px]">
          <ComposedChart data={data} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
            <defs>
              <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="var(--color-days)" stopOpacity={0.24} />
                <stop offset="95%" stopColor="var(--color-days)" stopOpacity={0.02} />
              </linearGradient>
            </defs>
            <CartesianGrid vertical={false} />
            <XAxis dataKey="t" type="number" scale="time" domain={shape.domain} ticks={ticks} interval={0} allowDataOverflow
              tickFormatter={(v) => formatTick(v, { step, span })} tickLine={false} axisLine={false} tickMargin={8} tick={{ fontSize: 11 }} />
            <YAxis domain={[lo, hi]} tickCount={3} allowDecimals={false} width={yWidth} tickLine={false} axisLine={false}
              tick={{ fontSize: 11 }} />
            <ChartTooltip isAnimationActive={false} cursor={{ stroke: 'var(--muted-foreground)', strokeOpacity: 0.35, strokeWidth: 1 }}
              content={<TrendTip t={t} bucketMs={shape.bucketMs} />} />
            {renewals.map((r) => (
              <ReferenceLine key={`rl${r.t}`} x={r.t} stroke="var(--color-renew)" strokeOpacity={0.5} strokeWidth={1.5} ifOverflow="hidden" />
            ))}
            <Area dataKey="days" type="linear" stroke="var(--color-days)" strokeWidth={2} fill={`url(#${gradId})`}
              baseValue={lo} dot={dayDot} activeDot={{ r: 4, fill: 'var(--color-days)', stroke: 'var(--card)', strokeWidth: 2 }}
              isAnimationActive={false} connectNulls={false} />
            {renewals.length > 0 && (
              <Line dataKey="renewY" stroke="none" dot={renewDot} activeDot={false} legendType="none" isAnimationActive={false} connectNulls={false} />
            )}
            {hasFail && (
              <Line dataKey="failY" stroke="none" dot={failDot} activeDot={false} legendType="none" isAnimationActive={false} connectNulls={false} />
            )}
          </ComposedChart>
        </ChartContainer>
        <p id={sumId} data-slot="cert-trend-summary" className="sr-only">{summary}</p>
      </figure>

      {renewals.length > 0 && onJump && (
        <div data-slot="cert-trend-renewals" className="flex min-w-0 flex-wrap items-center gap-1.5">
          <span className="text-xs text-muted-foreground">{t('certh.jumpLabel')}</span>
          {renewals.slice(-MAX_JUMPS).map((r) => (
            <Button key={`j${r.t}`} type="button" variant="outline" size="sm" onClick={() => onJump(r)}
              className="h-8 gap-1.5 border-success/40 px-2.5 text-xs font-medium text-success hover:bg-success/10 hover:text-success pointer-coarse:h-10">
              <RefreshCcw aria-hidden="true" />{t('certh.jumpRenewal', shortDateTime(r.ts), r.from, daysText(r.to, t))}
            </Button>
          ))}
          {renewals.length > MAX_JUMPS && (
            <span className="text-xs text-muted-foreground">{t('certh.jumpMore', renewals.length - MAX_JUMPS)}</span>
          )}
        </div>
      )}
    </Card>
  )
}
