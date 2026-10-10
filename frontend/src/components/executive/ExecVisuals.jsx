import { useId, useMemo, useState } from 'react'
import { ChartLine, Table2 } from 'lucide-react'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import {
  ChartContainer, ChartTooltip, LineChart, Line, XAxis, YAxis, CartesianGrid, ReferenceLine,
} from '@/components/shadcn/chart'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { cn } from '@/lib/utils'
import {
  CRYPTO_CATEGORY_SEGMENTS, TLS_GRADE_SEGMENTS, dailyPoints, distributionSegments, fmtNumber, fmtPct, renewalSegments, yFloor,
} from './executiveModel.js'

/** Grafik ipucu: gün, erişilebilirlik, kontrol sayısı, hedefe göre durum (metinle). */
function TrendTip({ active, payload, target, t, lang }) {
  if (!active || !Array.isArray(payload) || !payload.length) return null
  const p = payload.find((x) => x?.payload)?.payload
  if (!p) return null
  const below = typeof p.availability === 'number' && target != null && p.availability < target
  return (
    <div data-slot="chart-tooltip" className="grid w-max max-w-[16rem] min-w-[10rem] gap-1 rounded-lg border border-border/60 bg-background px-3 py-2 text-xs shadow-xl">
      <div className="font-semibold text-foreground tabular-nums">{p.full}</div>
      <div className="flex justify-between gap-3"><span className="text-muted-foreground">{t('exec.chart.availability')}</span>
        <span className="font-semibold tabular-nums">{fmtPct(p.availability, lang)}</span></div>
      <div className="flex justify-between gap-3"><span className="text-muted-foreground">{t('exec.chart.checks')}</span>
        <span className="tabular-nums">{fmtNumber(p.checks, lang, 0)}</span></div>
      {target != null && (
        <div className={cn('font-medium', below ? 'text-destructive' : 'text-success')}>
          {below ? t('exec.chart.belowTarget') : t('exec.chart.onTarget')}
        </div>
      )}
    </div>
  )
}

/**
 * Günlük erişilebilirlik (İstanbul günleri) — tek seri çizgi + hedef çizgisi (kesikli). Tek seri olduğu için lejant kutusu
 * yok: başlık seriyi, rozet hedef çizgisini adlandırır. Hedefin altındaki günler kırmızı noktalı (metin de söyler:
 * özet cümlesi + ipucu + tablo görünümü — renk tek taşıyıcı değil).
 */
export function AvailabilityTrend({ section }) {
  const t = useT()
  const { lang } = useLanguage()
  const titleId = useId()
  const sumId = useId()
  const [view, setView] = useState('chart')
  const target = typeof section?.data?.target === 'number' ? section.data.target : null
  const points = useMemo(() => dailyPoints(section?.data?.daily, lang), [section, lang])
  const withData = points.filter((p) => typeof p.availability === 'number')
  if (withData.length === 0) return null
  const below = target == null ? [] : withData.filter((p) => p.availability < target)
  const worst = withData.reduce((a, b) => (b.availability < a.availability ? b : a), withData[0])
  const floor = yFloor(withData, target)
  const config = {
    availability: { label: t('exec.chart.availability'), color: 'var(--chart-1)' },
    target: { label: t('exec.chart.target'), color: 'var(--warning)' },
  }
  const dot = (props) => {
    const { cx, cy, payload, index } = props
    if (cx == null || cy == null || payload?.availability == null) return <g key={index} />
    const miss = target != null && payload.availability < target
    return (
      <circle key={index} cx={cx} cy={cy} r={miss ? 4 : 2.5} fill={miss ? 'var(--destructive)' : 'var(--chart-1)'}
        stroke="var(--card)" strokeWidth={1.5} />
    )
  }
  return (
    <figure aria-labelledby={titleId} aria-describedby={sumId} data-slot="ex-trend" className="m-0 flex min-w-0 flex-col gap-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <h4 id={titleId} className="m-0 text-sm font-semibold">{t('exec.chart.title')}</h4>
          {target != null && (
            <Badge variant="outline" data-slot="ex-trend-target" className="gap-1.5 font-normal text-muted-foreground">
              <svg viewBox="0 0 14 10" aria-hidden="true" className="size-auto h-2.5 w-3.5 overflow-visible">
                <line x1="0.5" y1="5" x2="13.5" y2="5" stroke="var(--warning)" strokeWidth="2" strokeDasharray="3 2.5" strokeLinecap="round" />
              </svg>
              {t('exec.chart.targetLine', fmtPct(target, lang))}
            </Badge>
          )}
        </div>
        <SegmentedControl value={view} onChange={setView} ariaLabel={t('exec.chart.viewLabel')}
          className="[&_[data-slot=toggle-group-item]]:h-10 lg:[&_[data-slot=toggle-group-item]]:h-8 pointer-coarse:[&_[data-slot=toggle-group-item]]:h-10"
          options={[{ value: 'chart', label: t('exec.chart.viewChart'), icon: ChartLine }, { value: 'table', label: t('exec.chart.viewTable'), icon: Table2 }]} />
      </div>
      <p id={sumId} data-slot="ex-trend-summary" className="m-0 text-xs text-muted-foreground">
        {t('exec.chart.summary', worst.full, fmtPct(worst.availability, lang), below.length)}
      </p>
      {view === 'chart' ? (
        <ChartContainer config={config} data-testid="ex-trend-chart" className="aspect-auto h-[180px] w-full sm:h-[220px]">
          <LineChart data={points} margin={{ top: 8, right: 12, left: 0, bottom: 0 }} accessibilityLayer>
            <CartesianGrid vertical={false} strokeDasharray="3 3" />
            <XAxis dataKey="label" tickLine={false} axisLine={false} minTickGap={10} tickMargin={6} tick={{ fontSize: 11 }} />
            <YAxis domain={[floor, 100]} tickCount={4} tickLine={false} axisLine={false} width={52} tick={{ fontSize: 11 }}
              tickFormatter={(v) => fmtPct(v, lang, 2)} allowDataOverflow />
            <ChartTooltip isAnimationActive={false} cursor={{ stroke: 'var(--muted-foreground)', strokeOpacity: 0.35 }}
              content={<TrendTip target={target} t={t} lang={lang} />} />
            {target != null && (
              <ReferenceLine y={target} stroke="var(--color-target)" strokeDasharray="6 4" strokeWidth={1.5} ifOverflow="extendDomain" />
            )}
            <Line dataKey="availability" type="monotone" stroke="var(--color-availability)" strokeWidth={2} connectNulls={false}
              dot={dot} activeDot={{ r: 5, fill: 'var(--color-availability)', stroke: 'var(--card)', strokeWidth: 2 }} isAnimationActive={false} />
          </LineChart>
        </ChartContainer>
      ) : (
        <div data-slot="ex-trend-table" className="max-h-72 overflow-y-auto rounded-lg border">
          <Table className="text-sm">
            <TableHeader>
              <TableRow>
                <TableHead>{t('exec.chart.day')}</TableHead>
                <TableHead className="text-right">{t('exec.chart.availability')}</TableHead>
                <TableHead className="text-right">{t('exec.chart.checks')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {points.map((p) => (
                <TableRow key={p.date}>
                  <TableCell className="tabular-nums">{p.full}</TableCell>
                  <TableCell className={cn('text-right tabular-nums', target != null && p.availability < target && 'text-destructive')}>
                    {fmtPct(p.availability, lang)}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{fmtNumber(p.checks, lang, 0)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </figure>
  )
}

/**
 * Yenileme sınıfları — tek yatay yığılmış çubuk (dilimler arası 2 px boşluk) + sayılı, yüzdeli lejant listesi. Durum
 * renkleri ayrılmış (başarı / uyarı / ciddi / kritik) ve her dilim metinle de söylenir.
 */
export function RenewalDistribution({ section }) {
  const t = useT()
  const { lang } = useLanguage()
  const titleId = useId()
  const segs = renewalSegments(section?.data?.by_class)
  const total = segs[0]?.total || 0
  if (!total) return null
  return (
    <section aria-labelledby={titleId} data-slot="ex-renewal-dist" className="flex min-w-0 flex-col gap-2">
      <h4 id={titleId} className="m-0 text-sm font-semibold">{t('exec.renewals.dist', total)}</h4>
      <div aria-hidden="true" className="flex h-3 w-full gap-0.5 overflow-hidden rounded-full bg-muted">
        {segs.filter((s) => s.count > 0).map((s) => (
          // Bileşim (dağılım) çubuğu, doluluk göstergesi DEĞİL: dilim payı flex-grow ile (adet oranı), en az 4 px görünür
          <span key={s.key} data-slot="ex-renewal-seg" data-class={s.key} className="h-full min-w-1 basis-0 first:rounded-l-full last:rounded-r-full"
            style={{ flexGrow: s.count, background: s.color }} />
        ))}
      </div>
      <ul className="m-0 grid list-none grid-cols-1 gap-x-4 gap-y-1 p-0 sm:grid-cols-2 xl:grid-cols-4">
        {segs.map((s) => (
          <li key={s.key} data-slot="ex-renewal-legend" data-class={s.key} className="flex min-w-0 items-center gap-2 text-sm">
            <span aria-hidden="true" className="size-2.5 shrink-0 rounded-sm" style={{ background: s.color }} />
            <span className="min-w-0 flex-1 truncate text-muted-foreground">{t(`exec.enum.renewal_class.${s.key}`)}</span>
            <span className="font-semibold tabular-nums">{fmtNumber(s.count, lang, 0)}</span>
            <span className="w-14 text-right text-xs text-muted-foreground tabular-nums">{fmtPct(s.pct, lang, 1)}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}

/**
 * Bileşim çubuğu + sayılı, yüzdeli lejant (yenileme dağılımıyla aynı dil). Dilim payı adet oranıdır; her dilim lejantta
 * metinle de söylenir (renk tek taşıyıcı değil). Toplam 0 ise hiçbir şey çizilmez (genel tablolar yeter).
 */
function DistributionBar({ slot, title, segments, labelOf, cols = 'grid-cols-2 sm:grid-cols-3 xl:grid-cols-6' }) {
  const { lang } = useLanguage()
  const titleId = useId()
  const total = segments[0]?.total || 0
  if (!total) return null
  return (
    <section aria-labelledby={titleId} data-slot={slot} className="flex min-w-0 flex-col gap-2">
      <h4 id={titleId} className="m-0 text-sm font-semibold">{title}</h4>
      <div aria-hidden="true" className="flex h-3 w-full gap-0.5 overflow-hidden rounded-full bg-muted">
        {segments.filter((s) => s.count > 0).map((s) => (
          <span key={s.key} data-slot={`${slot}-seg`} data-key={s.key}
            className="h-full min-w-1 basis-0 first:rounded-l-full last:rounded-r-full"
            style={{ flexGrow: s.count, background: s.color }} />
        ))}
      </div>
      <ul className={cn('m-0 grid list-none gap-x-4 gap-y-1 p-0', cols)}>
        {segments.map((s) => (
          <li key={s.key} data-slot={`${slot}-legend`} data-key={s.key} className="flex min-w-0 items-center gap-2 text-sm">
            <span aria-hidden="true" className="size-2.5 shrink-0 rounded-sm" style={{ background: s.color }} />
            <span className="min-w-0 flex-1 truncate text-muted-foreground">{labelOf(s.key)}</span>
            <span className="font-semibold tabular-nums">{fmtNumber(s.count, lang, 0)}</span>
            <span className="w-12 text-right text-xs text-muted-foreground tabular-nums">{fmtPct(s.pct, lang, 1)}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}

/** TLS notu dağılımı (A+ … F) — `data.grades`. */
export function TlsGradeDistribution({ section }) {
  const t = useT()
  const segs = distributionSegments(section?.data?.grades, TLS_GRADE_SEGMENTS)
  return (
    <DistributionBar slot="ex-tls-dist" segments={segs} labelOf={(k) => k}
      title={t('exec.tls-grade.dist', segs[0]?.total || 0)} />
  )
}

/** Kripto geçiş kategorileri — `data.by_category`. */
export function CryptoCategoryDistribution({ section }) {
  const t = useT()
  const segs = distributionSegments(section?.data?.by_category, CRYPTO_CATEGORY_SEGMENTS)
  return (
    <DistributionBar slot="ex-crypto-dist" segments={segs} labelOf={(k) => t(`cinv.cat.${k}`)}
      cols="grid-cols-1 sm:grid-cols-2 xl:grid-cols-3" title={t('exec.crypto-readiness.dist', segs[0]?.total || 0)} />
  )
}
