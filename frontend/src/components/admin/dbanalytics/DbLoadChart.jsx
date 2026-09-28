import { useId, useState } from 'react'
import { Activity, BarChart3, Info, Table2 } from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import {
  ChartContainer, ChartTooltip, BarChart, LineChart, Bar, Line, XAxis, YAxis, CartesianGrid, ReferenceLine,
} from '@/components/shadcn/chart'
import HintPopover from '../../ui/HintPopover.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { cn } from '@/lib/utils'
import { TD, TD_NUM, TH, TH_NUM } from './DbParts.jsx'
import { DB_MS, msTone, num, pct } from './dbModel.js'

/** Eşik çizgisi yalnız verinin 3 katına kadar çizilir; daha yukarıdaysa ekseni uzatıp veriyi ezmek yerine rozet söyler. */
const THRESHOLD_VIEW_FACTOR = 3
/** Bu kadar ya da daha az nokta varsa çizgide her nokta işaretlenir (seyrek veri — tek nokta da görünür). */
const SPARSE_POINTS = 31

/** Eksen tavanını 1 / 2 / 2,5 / 5 × 10ⁿ'e yuvarla (405 → 500): tik değerleri okunur olsun. */
export function niceCeil(v) {
  if (!(v > 0)) return 1
  const mag = 10 ** Math.floor(Math.log10(v))
  const f = v / mag
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * mag
}

/** Seri anahtarı: çubuk = kare, çizgi = çizgi, eşik = kesikli (grafikteki işaretin kendisi). */
function Key({ shape, color }) {
  const common = { 'aria-hidden': true, focusable: 'false', className: 'size-auto h-2.5 w-3.5 shrink-0 overflow-visible' }
  if (shape === 'bar') return <svg viewBox="0 0 14 10" {...common}><rect x="2" y="0.5" width="10" height="9" rx="2" fill={color} /></svg>
  return (
    <svg viewBox="0 0 14 10" {...common}>
      <line x1="0.5" y1="5" x2="13.5" y2="5" stroke={color} strokeWidth="2" strokeLinecap="round" strokeDasharray={shape === 'dashed' ? '3 2.5' : undefined} />
    </svg>
  )
}

/** İpucu satırı: solda anahtar + ad (ikincil), sağda değer (vurgulu). */
function TipRow({ label, value, shape, color, className }) {
  return (
    <div data-slot="chart-tip-row" className={cn('flex items-center gap-2', className)}>
      {shape ? <Key shape={shape} color={color} /> : <span aria-hidden="true" className="w-3.5 shrink-0" />}
      <span className="min-w-0 flex-1 truncate text-muted-foreground">{label}</span>
      <span className="font-semibold text-foreground tabular-nums">{value}</span>
    </div>
  )
}

/** Grafik ipucu (recharts `content`): kovanın zamanı, başarılı/başarısız/toplam ve ortalama süre (+ eşik üstü rozeti). */
function Tip({ active, payload, t }) {
  if (!active || !Array.isArray(payload) || !payload.length) return null
  const p = payload.find((x) => x?.payload)?.payload
  if (!p) return null
  const over = p.avg != null && p.avg >= DB_MS.warn
  return (
    <div data-slot="chart-tooltip" className="grid w-max max-w-[16rem] min-w-[11rem] gap-1.5 rounded-lg border border-border/60 bg-background px-3 py-2 text-xs shadow-xl">
      <div className="font-semibold text-foreground tabular-nums">{p.full}</div>
      {p.count === 0 ? (
        <div className="text-muted-foreground">{t('dba.tipNoQueries')}</div>
      ) : (
        <div className="grid gap-1">
          <TipRow label={t('dba.seriesOk')} value={num(p.ok)} shape="bar" color="var(--color-ok)" />
          <TipRow label={t('dba.seriesFailed')} value={num(p.failed)} shape="bar" color="var(--color-failed)" />
          <TipRow label={t('dba.tipTotal')} value={num(p.count)} className="border-t border-border/60 pt-1" />
          <TipRow label={t('db.kpiAvg')} value={p.avg != null ? `${num(p.avg)} ms` : '—'} shape="line" color="var(--color-avg)" />
        </div>
      )}
      {over && <Badge variant="warning" className="justify-self-start">{t('dba.tipOver')}</Badge>}
    </div>
  )
}

/**
 * Sorgu yükü — iki küçük grafik, ORTAK zaman ekseni (syncId: ortak imleç + ipucu): üstte sorgu adedi (başarılı +
 * başarısız, yığılmış çubuk), altta ortalama süre (ms, eşik çizgili). Tek eksen kuralı: adet ile milisaniye ayrı
 * ölçek → aynı grafiğe iki y ekseniyle bindirilmez. Seriler aç/kapa (ToggleGroup), renkler tema jetonları.
 * Erişilebilirlik: görünür özet cümlesi + "Tablo" görünümü (her kova satır) — ipucu tek yol değil (dokunmatik).
 * Sorgu olmayan kovada süre çizgisi KOPAR (0 ms uydurulmaz); tek dolu kova da noktasıyla görünür.
 */
export default function DbLoadChart({ t, chart, winLbl }) {
  const uid = useId().replace(/:/g, '')
  const titleId = `db-trend-title-${uid}`
  const summaryId = `db-trend-sum-${uid}`
  const syncId = `db-trend-${uid}`
  const [hidden, setHidden] = useState(() => new Set())
  const [view, setView] = useState('chart')
  // syncId iki grafikte de ipucunu açar; yalnız üzerinde gezinilen grafik ipucunu çizer (diğeri yalnız imleç).
  const [hoverChart, setHoverChart] = useState(null)
  const tipFor = (id) => (hoverChart && hoverChart !== id ? () => null : <Tip t={t} />)
  const hoverProps = (id) => ({ onMouseEnter: () => setHoverChart(id), onMouseLeave: () => setHoverChart(null), onTouchStart: () => setHoverChart(id) })

  const series = [
    { key: 'ok', label: t('dba.seriesOk'), shape: 'bar', color: 'var(--chart-1)' },
    { key: 'failed', label: t('dba.seriesFailed'), shape: 'bar', color: 'var(--destructive)' },
    { key: 'avg', label: t('db.kpiAvg'), shape: 'line', color: 'var(--chart-5)' },
  ]
  const config = {
    ...Object.fromEntries(series.map((s) => [s.key, { label: s.label, color: s.color }])),
    threshold: { label: t('dba.threshold', DB_MS.warn), color: 'var(--warning)' },
  }
  const visible = series.map((s) => s.key).filter((k) => !hidden.has(k))
  const showDur = !hidden.has('avg')
  const { points, total, failed, errPct, peak, maxAvg, withData, empty } = chart
  const sparse = withData <= SPARSE_POINTS
  const thrInView = maxAvg > 0 && DB_MS.warn <= maxAvg * THRESHOLD_VIEW_FACTOR
  const yTopMs = niceCeil(Math.max(maxAvg, thrInView ? DB_MS.warn : 0) * 1.05)
  const maxCount = Math.max(1, ...points.map((p) => p.count))
  const yWidth = Math.max(32, String(Math.max(maxCount, yTopMs)).length * 7 + 14)
  const margin = { top: 10, right: 12, left: 0, bottom: 0 }
  const axisX = (hide) => (
    <XAxis dataKey="label" tickLine={false} axisLine={false} minTickGap={14} tickMargin={6} tick={{ fontSize: 11 }} hide={hide} />
  )

  return (
    <Card data-slot="db-trend-card" className="gap-3 px-3.5 py-4 shadow-xs sm:px-5">
      <div className="flex flex-col gap-2 @3xl:flex-row @3xl:items-start @3xl:justify-between">
        <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
          <h4 id={titleId} className="m-0 text-sm font-semibold">{t('db.trendTitle')}</h4>
          <Badge variant="outline" className="font-normal text-muted-foreground">{winLbl}</Badge>
          <HintPopover content={t('dba.trendSource')} triggerClassName="rounded-full p-1 text-muted-foreground hover:text-foreground max-lg:size-10 pointer-coarse:size-10">
            <Info aria-hidden="true" className="size-3.5" />
            <span className="sr-only">{t('dba.trendSourceLabel')}</span>
          </HintPopover>
        </div>
        {!empty && (
          <div className="flex flex-wrap items-center gap-1.5 @3xl:justify-end">
            {view === 'chart' && (
              <ToggleGroup type="multiple" variant="outline" size="sm" spacing={1.5} data-slot="chart-series"
                aria-label={t('chart.seriesLabel')} value={visible} className="flex-wrap"
                onValueChange={(vals) => setHidden(new Set(series.map((s) => s.key).filter((k) => !vals.includes(k))))}>
                {series.map((s) => (
                  <ToggleGroupItem key={s.key} value={s.key} data-series={s.key}
                    className="h-10 gap-1.5 rounded-md px-2.5 text-xs font-medium data-[state=off]:text-muted-foreground data-[state=off]:line-through data-[state=off]:opacity-70 data-[state=on]:bg-muted lg:h-8 pointer-coarse:h-10">
                    <Key shape={s.shape} color={s.color} />{s.label}
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
            )}
            <SegmentedControl value={view} onChange={setView} ariaLabel={t('dba.viewLabel')}
              className="[&_[data-slot=toggle-group-item]]:h-10 lg:[&_[data-slot=toggle-group-item]]:h-8 pointer-coarse:[&_[data-slot=toggle-group-item]]:h-10"
              options={[{ value: 'chart', label: t('dba.viewChart'), icon: BarChart3 }, { value: 'table', label: t('dba.viewTable'), icon: Table2 }]} />
          </div>
        )}
      </div>

      {empty ? (
        <StatusBlock tone="neutral" icon={Activity} title={t('db.trendEmpty')} />
      ) : (
        <figure aria-labelledby={titleId} aria-describedby={summaryId} className="m-0 flex min-w-0 flex-col gap-2">
          <p id={summaryId} data-testid="db-trend-summary" className="m-0 flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
            <span>{t('dba.trendSummary', num(total), num(failed), pct(errPct))}</span>
            {peak && peak.count > 0 && <span>{t('dba.trendPeak', peak.full, num(peak.count))}</span>}
          </p>
          {view === 'chart' ? (
            <>
              <span className="text-[11px] font-semibold text-muted-foreground">{t('db.queriesPer')}</span>
              <ChartContainer config={config} data-testid="db-trend" {...hoverProps('vol')} className="aspect-auto h-[170px] w-full sm:h-[210px]">
                <BarChart data={points} syncId={syncId} margin={margin} barCategoryGap="16%" accessibilityLayer>
                  <CartesianGrid vertical={false} strokeDasharray="3 3" />
                  {axisX(showDur)}
                  <YAxis allowDecimals={false} tickLine={false} axisLine={false} width={yWidth} tick={{ fontSize: 11 }} />
                  <ChartTooltip isAnimationActive={false} cursor={{ fill: 'var(--muted)', fillOpacity: 0.6 }} content={tipFor('vol')} />
                  <Bar dataKey="ok" stackId="q" fill="var(--color-ok)" hide={hidden.has('ok')} maxBarSize={34} isAnimationActive={false} />
                  <Bar dataKey="failed" stackId="q" fill="var(--color-failed)" hide={hidden.has('failed')} radius={[3, 3, 0, 0]}
                    stroke="var(--card)" strokeWidth={1} maxBarSize={34} isAnimationActive={false} />
                </BarChart>
              </ChartContainer>
              {showDur && (
                <>
                  <div className="mt-1 flex flex-wrap items-center justify-between gap-2">
                    <span className="text-[11px] font-semibold text-muted-foreground">{t('dba.durationTitle')}</span>
                    <Badge variant="outline" data-slot="chart-threshold" data-in-view={thrInView ? 'true' : 'false'}
                      className="gap-1.5 font-normal text-muted-foreground">
                      <Key shape="dashed" color="var(--warning)" />
                      {thrInView ? t('dba.threshold', DB_MS.warn) : t('dba.thresholdBelow', DB_MS.warn)}
                    </Badge>
                  </div>
                  <ChartContainer config={config} data-testid="db-trend-duration" {...hoverProps('dur')} className="aspect-auto h-[120px] w-full sm:h-[150px]">
                    <LineChart data={points} syncId={syncId} margin={margin} accessibilityLayer>
                      <CartesianGrid vertical={false} strokeDasharray="3 3" />
                      {axisX(false)}
                      <YAxis domain={[0, yTopMs]} tickCount={3} allowDecimals={false} tickLine={false} axisLine={false} width={yWidth} tick={{ fontSize: 11 }} />
                      <ChartTooltip isAnimationActive={false} cursor={{ stroke: 'var(--muted-foreground)', strokeOpacity: 0.35 }} content={tipFor('dur')} />
                      {thrInView && (
                        <ReferenceLine y={DB_MS.warn} stroke="var(--color-threshold)" strokeDasharray="6 4" strokeWidth={1.5} ifOverflow="extendDomain" />
                      )}
                      <Line dataKey="avg" type="monotone" stroke="var(--color-avg)" strokeWidth={2} connectNulls={false}
                        dot={sparse ? { r: 3, fill: 'var(--color-avg)', stroke: 'var(--card)', strokeWidth: 1.5 } : false}
                        activeDot={{ r: 4, fill: 'var(--color-avg)', stroke: 'var(--card)', strokeWidth: 2 }} isAnimationActive={false} />
                    </LineChart>
                  </ChartContainer>
                </>
              )}
            </>
          ) : (
            <div className="max-h-80 overflow-y-auto rounded-lg border border-border" data-testid="db-trend-table">
              <Table className="text-[0.84em]">
                <TableHeader><TableRow>
                  <TableHead className={TH}>{t('dba.colBucket')}</TableHead>
                  <TableHead className={TH_NUM}>{t('db.queriesPer')}</TableHead>
                  <TableHead className={TH_NUM}>{t('db.kpiFailed')}</TableHead>
                  <TableHead className={TH_NUM}>{t('db.colAvgMs')}</TableHead>
                </TableRow></TableHeader>
                <TableBody>
                  {points.map((p) => (
                    <TableRow key={p.ts}>
                      <TableCell className={cn(TD, 'whitespace-nowrap')}>{p.full}</TableCell>
                      <TableCell className={TD_NUM}>{num(p.count)}</TableCell>
                      <TableCell className={cn(TD_NUM, p.failed > 0 && 'font-semibold text-destructive')}>{num(p.failed)}</TableCell>
                      <TableCell className={cn(TD_NUM, msTone(p.avg) === 'danger' && 'font-semibold text-destructive',
                        msTone(p.avg) === 'warning' && 'text-amber-700 dark:text-amber-300')}>{p.avg != null ? num(p.avg) : '—'}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </figure>
      )}
    </Card>
  )
}
