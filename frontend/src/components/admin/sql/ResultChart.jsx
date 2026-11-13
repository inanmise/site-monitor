import { useMemo, useState } from 'react'
import { BarChart3, ChartLine, PieChart as PieIcon } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import Field from '../../ui/Field.jsx'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import {
  Bar, BarChart, CartesianGrid, Cell, ChartContainer, ChartLegend, ChartLegendContent, ChartTooltip, ChartTooltipContent, Line,
  LineChart, Pie, PieChart, XAxis, YAxis,
} from '@/components/shadcn/chart'
import ChoiceToggle from './ChoiceToggle.jsx'
import { MAX_SERIES, OTHER_KEY, chartColumns, chartData, normalizeSpec } from './chartModel.js'
import { columnTypes, resultColumns } from './sqlUtils.js'

const SERIES_COLORS = ['var(--chart-1)', 'var(--chart-2)']
const PIE_COLORS = ['var(--chart-1)', 'var(--chart-2)', 'var(--chart-3)', 'var(--chart-4)', 'var(--chart-5)']
const NONE = '__none__'

/**
 * Sonuçtan grafik (2026-11-13, C2) — tembel parça (recharts yalnız açılınca). Yalnız EKRANDAKİ satırlar; X + 1–2 sayısal
 * seri (çubuk / çizgi) ya da X + 1 seri (pasta). Boş / sayı olmayan değer BOŞLUK (çizgi kesilir — asla 0). Zaman kolonu
 * varsa çizgi + zaman X'i otomatik. Maskeli kolon seri olamaz. Kolon değişince seçim geçerli kolonlara oturur.
 */
export default function ResultChart({ rows, masked }) {
  const t = useT()
  const cols = useMemo(() => resultColumns(rows), [rows])
  const types = useMemo(() => columnTypes(rows, cols), [rows, cols])
  const maskedSet = useMemo(() => new Set(masked || []), [masked])
  const [pick, setPick] = useState(null)
  const spec = useMemo(() => normalizeSpec(pick, cols, types, maskedSet), [pick, cols, types, maskedSet])
  const { numeric, category } = useMemo(() => chartColumns(cols, types, maskedSet), [cols, types, maskedSet])
  const built = useMemo(() => chartData(rows, spec, types), [rows, spec, types])

  if (!spec) {
    return (
      <div data-slot="sql-chart-none">
        <StatusBlock icon={BarChart3} title={t('sql.chart.noneTitle')} description={t('sql.chart.noneHint')} className="py-8" />
      </div>
    )
  }
  const update = (patch) => setPick({ ...spec, ...patch })
  // Seri anahtarları GÜVENLİ (s0, s1): kolon adı CSS değişken adı olamayabilir (boşluk, nokta, büyük harf)
  const config = Object.fromEntries((spec.kind === 'pie' ? [] : spec.ys).map((y, i) => [`s${i}`, { label: y, color: SERIES_COLORS[i] }]))
  const series = spec.kind === 'pie' ? [] : built.data.map((p) => ({ x: p.x, ...Object.fromEntries(spec.ys.map((y, i) => [`s${i}`, p[y]])) }))
  const pieConfig = Object.fromEntries(built.data.map((d, i) => [d.key, {
    label: d.name === OTHER_KEY ? t('sql.chart.other') : d.name, color: PIE_COLORS[i % PIE_COLORS.length],
  }]))
  const pieData = built.data.map((d) => ({ ...d, name: d.name === OTHER_KEY ? t('sql.chart.other') : d.name }))
  const xOptions = category

  return (
    <div data-slot="sql-chart" data-kind={spec.kind} className="flex min-w-0 flex-col gap-3 p-3">
      <div className="flex flex-wrap items-end gap-3">
        <ChoiceToggle ariaLabel={t('sql.chart.kind')} value={spec.kind}
          onChange={(k) => update({ kind: k, ys: k === 'pie' ? spec.ys.slice(0, 1) : spec.ys })}
          options={[
            { value: 'bar', label: t('sql.chart.bar'), icon: BarChart3 },
            { value: 'line', label: t('sql.chart.line'), icon: ChartLine },
            { value: 'pie', label: t('sql.chart.pie'), icon: PieIcon },
          ]} />
        <Field label={t('sql.chart.x')} name="chart-x" className="min-w-40 flex-1 sm:flex-none">
          {({ id }) => (
            <div className="[&_[data-slot=native-select-wrapper]]:w-full">
              <NativeSelect id={id} value={spec.x ?? NONE} onChange={(e) => update({ x: e.target.value === NONE ? null : e.target.value })}
                className="font-mono pointer-coarse:h-10">
                <NativeSelectOption value={NONE}>{t('sql.chart.rowNo')}</NativeSelectOption>
                {xOptions.map((c) => <NativeSelectOption key={c} value={c}>{c}</NativeSelectOption>)}
              </NativeSelect>
            </div>
          )}
        </Field>
        {Array.from({ length: spec.kind === 'pie' ? 1 : MAX_SERIES }, (_, i) => (
          <Field key={i} label={t('sql.chart.y', i + 1)} name={`chart-y${i}`} className="min-w-40 flex-1 sm:flex-none">
            {({ id }) => (
              <div className="[&_[data-slot=native-select-wrapper]]:w-full">
                <NativeSelect id={id} value={spec.ys[i] ?? NONE} className="font-mono pointer-coarse:h-10"
                  onChange={(e) => {
                    const ys = [...spec.ys]
                    if (e.target.value === NONE) ys.splice(i, 1); else ys[i] = e.target.value
                    update({ ys: ys.filter(Boolean) })
                  }}>
                  {i > 0 && <NativeSelectOption value={NONE}>—</NativeSelectOption>}
                  {numeric.map((c) => <NativeSelectOption key={c} value={c}>{c}</NativeSelectOption>)}
                </NativeSelect>
              </div>
            )}
          </Field>
        ))}
      </div>
      {built.truncated && (
        <AlertBanner tone="info" className="mb-0">
          {spec.kind === 'pie' ? t('sql.chart.pieTruncated') : t('sql.chart.barTruncated')}
        </AlertBanner>
      )}
      {spec.kind === 'pie' && built.skipped > 0 && <p className="m-0 text-xs text-muted-foreground">{t('sql.chart.pieSkipped', built.skipped)}</p>}
      {built.data.length === 0 ? (
        <StatusBlock icon={BarChart3} title={t('sql.chart.emptyTitle')} className="py-6" />
      ) : spec.kind === 'pie' ? (
        <ChartContainer config={pieConfig} className="aspect-auto h-72 w-full" role="img" aria-label={t('sql.chart.aria', spec.ys[0], pieData.length)}>
          <PieChart>
            <ChartTooltip content={<ChartTooltipContent nameKey="key" hideLabel />} />
            <Pie data={pieData} dataKey="value" nameKey="key" innerRadius="35%" outerRadius="75%" isAnimationActive={false}>
              {pieData.map((d) => <Cell key={d.key} fill={`var(--color-${d.key})`} />)}
            </Pie>
            <ChartLegend content={<ChartLegendContent nameKey="key" className="flex-wrap pt-2 text-xs" />} />
          </PieChart>
        </ChartContainer>
      ) : (
        <ChartContainer config={config} className="aspect-auto h-72 w-full" role="img"
          aria-label={t('sql.chart.aria', spec.ys.join(', '), built.data.length)}>
          {spec.kind === 'line' ? (
            <LineChart data={series} margin={{ top: 6, right: 12, bottom: 0, left: 0 }}>
              <CartesianGrid strokeDasharray="3 3" vertical={false} />
              <XAxis dataKey="x" tickLine={false} axisLine={false} tick={{ fontSize: 10 }} minTickGap={16} />
              <YAxis width={48} tickLine={false} axisLine={false} tick={{ fontSize: 10 }} />
              <ChartTooltip content={<ChartTooltipContent />} />
              <ChartLegend content={<ChartLegendContent className="flex-wrap pt-2 text-xs" />} />
              {spec.ys.map((y, i) => (
                <Line key={y} dataKey={`s${i}`} stroke={`var(--color-s${i})`} strokeWidth={2} dot={built.data.length <= 60}
                  connectNulls={false} isAnimationActive={false} />
              ))}
            </LineChart>
          ) : (
            <BarChart data={series} margin={{ top: 6, right: 12, bottom: 0, left: 0 }}>
              <CartesianGrid strokeDasharray="3 3" vertical={false} />
              <XAxis dataKey="x" tickLine={false} axisLine={false} tick={{ fontSize: 10 }} minTickGap={8} />
              <YAxis width={48} tickLine={false} axisLine={false} tick={{ fontSize: 10 }} />
              <ChartTooltip content={<ChartTooltipContent />} />
              <ChartLegend content={<ChartLegendContent className="flex-wrap pt-2 text-xs" />} />
              {spec.ys.map((y, i) => <Bar key={y} dataKey={`s${i}`} fill={`var(--color-s${i})`} radius={[3, 3, 0, 0]} isAnimationActive={false} />)}
            </BarChart>
          )}
        </ChartContainer>
      )}
    </div>
  )
}
