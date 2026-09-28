import { useMemo } from 'react'
import { useT } from '../../i18n/index.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { Card } from '@/components/shadcn/card'
import {
  ChartContainer, ChartTooltip, ChartLegend, ChartLegendContent, BarChart, Bar, XAxis, YAxis, CartesianGrid, Cell,
} from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'
import { SEV_COLOR, TREND_RANGES, formatDay } from './incidentHistoryModel.js'

// Yığılmış çubuklar alttan üste LOW → CRITICAL (kritik en üstte, en belirgin). Renk shadcn Chart deseniyle: config →
// ChartStyle `--color-<anahtar>` üretir, çubuk `var(--color-<anahtar>)` ile boyanır.
const LEGEND_ORDER = ['critical', 'high', 'medium', 'low']
const SEV_BARS = [
  { key: 'low', sev: 'LOW' },
  { key: 'medium', sev: 'MEDIUM' },
  { key: 'high', sev: 'HIGH' },
  { key: 'critical', sev: 'CRITICAL' },
]

/** Temalı ipucu (shadcn yüzey jetonları): gün + sıfır olmayan önem kırılımı + toplam. */
function TrendTooltip({ active, payload, t }) {
  if (!active || !payload?.length) return null
  const p = payload[0]?.payload || {}
  const rows = [['CRITICAL', p.critical], ['HIGH', p.high], ['MEDIUM', p.medium], ['LOW', p.low]].filter(([, v]) => v > 0)
  return (
    <div className="rounded-lg border bg-popover px-2.5 py-1.5 text-xs text-popover-foreground shadow-lg">
      <div className={cn('font-bold', rows.length && 'mb-0.5')}>{formatDay(p.day)}</div>
      {rows.map(([k, v]) => (
        <div key={k} className="flex items-center gap-1.5 leading-normal">
          <span aria-hidden="true" className="size-2 shrink-0 rounded-[2px]" style={{ background: SEV_COLOR[k] }} />
          <span>{t('inc.sev' + k)}: <b>{v}</b></span>
        </div>
      ))}
      <div className="mt-0.5 text-muted-foreground">{t('inc.trendTotal')}: <b>{p.count || 0}</b></div>
    </div>
  )
}

/**
 * Günlük trend — son N gün (7/30/60/90; tablo süzgecinden BAĞIMSIZ, bugünle biten pencere), önem yığılı çubuklar.
 * Bir çubuğa tıklamak listeyi o güne süzer (seçili gün dışındakiler soluklaşır); tekrar tıklamak temizler.
 * 390 px'te okunur: seyrek eksen etiketi, dar sol boşluk, sarılan başlık satırı; grafik yüksekliği sabit.
 */
export default function HistoryTrendChart({ data, days, onDays, selectedDay, onDayClick }) {
  const t = useT()
  const config = useMemo(() => Object.fromEntries(SEV_BARS.map((b) => [b.key, { label: t('inc.sev' + b.sev), color: SEV_COLOR[b.sev] }])), [t])
  const total = data.reduce((s, d) => s + d.count, 0)
  const critical = data.reduce((s, d) => s + d.critical, 0)
  const busiest = data.reduce((m, d) => (d.count > (m?.count ?? 0) ? d : m), null)

  return (
    <Card data-slot="incident-trend" className="min-w-0 gap-3 px-3 py-3 sm:px-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="text-sm font-semibold">{t('inc.trend')}</h3>
          <p className="text-xs text-muted-foreground tabular-nums" aria-live="polite">
            {t('inc.trendSummary', days, total, critical)}
            {busiest && busiest.count > 0 && <> · {t('inc.trendBusiest', formatDay(busiest.day), busiest.count)}</>}
          </p>
        </div>
        <SegmentedControl value={String(days)} onChange={(v) => onDays(Number(v))} ariaLabel={t('inc.trendRange')}
          className="max-sm:[&_[data-slot=toggle-group-item]]:h-10 max-sm:[&_[data-slot=toggle-group-item]]:min-w-10"
          options={TREND_RANGES.map((dd) => ({ value: String(dd), label: `${dd}${t('inc.trendDayUnit')}`, title: t('inc.trendLastN', dd) }))} />
      </div>
      <ChartContainer config={config} className="aspect-auto h-[220px] w-full">
        <BarChart data={data} margin={{ top: 8, right: 4, bottom: 0, left: -8 }} barCategoryGap={days > 60 ? '8%' : '16%'} accessibilityLayer>
          <CartesianGrid strokeDasharray="3 3" vertical={false} />
          <XAxis dataKey="day" tickFormatter={(d) => d.slice(8, 10) + '.' + d.slice(5, 7)}
            tick={{ fontSize: 10 }} tickLine={false} axisLine={false} minTickGap={18} />
          <YAxis allowDecimals={false} tick={{ fontSize: 10 }} width={32} tickLine={false} axisLine={false} />
          <ChartTooltip content={<TrendTooltip t={t} />} cursor={{ fill: 'var(--primary)', fillOpacity: 0.08 }} />
          {/* Gösterge önem sırasıyla (kritik → düşük); recharts varsayılanı ada göre sıralardı */}
          <ChartLegend itemSorter={(item) => LEGEND_ORDER.indexOf(item?.dataKey)} content={<ChartLegendContent className="flex-wrap gap-x-3 gap-y-1 pt-2" />} />
          {SEV_BARS.map((b, i) => (
            <Bar key={b.key} dataKey={b.key} stackId="s" name={t('inc.sev' + b.sev)} fill={`var(--color-${b.key})`}
              isAnimationActive={false} cursor="pointer" radius={i === SEV_BARS.length - 1 ? [3, 3, 0, 0] : 0}
              onClick={(bar) => onDayClick(bar?.day ?? bar?.payload?.day)}>
              {data.map((d) => <Cell key={d.day} fillOpacity={selectedDay && selectedDay !== d.day ? 0.28 : 1} />)}
            </Bar>
          ))}
        </BarChart>
      </ChartContainer>
      <p className="text-[11px] text-muted-foreground">{t('inc.trendClickHint')}</p>
    </Card>
  )
}
