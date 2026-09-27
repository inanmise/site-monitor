import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { api, formatDate, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import DateTimeRangePicker from './ui/DateTimeRangePicker.jsx'
import SegmentedControl from './ui/SegmentedControl.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import { formatBytes, formatBytesAxis } from '../utils/formatBytes.js'
import StatusBlock from './ui/StatusBlock.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import SimpleTooltip from './ui/SimpleTooltip.jsx'
import { BarChart3, Calendar, Pencil } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import {
  ChartContainer, ChartTooltip, ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid, ReferenceLine,
} from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'

// Saatlik on ayarlar: en kucuk pencere 24 saatti ve 10 dakikalik kova yuzunden olcumler
// ortalamaya karisiyordu — "az once ne oldu" sorusu grafikten cevaplanamiyordu. <= 6 saatte
// backend DAKIKA kovasina duser, yani her kontrol kendi noktasi olur.
const PRESETS = [
  { key: '1h',  hours: 1 },
  { key: '6h',  hours: 6 },
  { key: '12h', hours: 12 },
  { key: '24h', days: 1 },
  { key: '7d',  days: 7 },
  { key: '30d', days: 30 },
  { key: '90d', days: 90 },
]

// Date → backend ISO (UTC, saniyeye kadar, Z'siz — checked_at deposu formatı).
const toIso = (d) => new Date(d).toISOString().slice(0, 19)

// Kova ISO'su (UTC, Z'siz) → kısa yerel etiket.
function tickLabel(ts, bucket) {
  const d = new Date(ts.endsWith('Z') ? ts : ts + 'Z')
  const p = (n) => String(n).padStart(2, '0')
  if (bucket === 'day') return `${p(d.getDate())}.${p(d.getMonth() + 1)}`
  return `${p(d.getDate())}.${p(d.getMonth() + 1)} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** İpucu satırı: etiket solda gri, değer sağda kalın; `tone` verilirse satır o renkte (kesinti). */
function TipRow({ label, children, tone }) {
  return (
    <div className={cn('flex justify-between gap-4', tone)}>
      <span className={tone ? undefined : 'text-muted-foreground'}>{label}</span>
      <strong className="tabular-nums">{children}</strong>
    </div>
  )
}

function SeriesTooltip({ active, payload, t, isPing, isSsl, fmt = (v) => `${v}ms` }) {
  if (!active || !payload || !payload.length) return null
  const d = payload[0].payload
  // Değer biçimi metriğe göre değişir: süre "3480ms", boyut "46.4 MB", istek sayısı çıplak sayı.
  // Sabit 'ms' eki bırakıldığında boyut serisi "48697344ms" yazıyordu — eksen de ipucu da
  // "ne ölçüyorum" sorusuna yanlış cevap veriyordu.
  // suffixOverride: ikinci eksenli seriler (ping paket kaybı %, sertifika kalan gün) kendi
  // birimlerini taşır; onlar ana metrik biçimlendiricisine tabi değildir.
  const row = (label, val, suffixOverride) =>
    val == null ? null : (
      <TipRow label={label}>{suffixOverride != null ? `${val}${suffixOverride}` : fmt(val)}</TipRow>
    )
  return (
    <div className="rounded-lg border bg-card px-[11px] py-2 text-[.82em] leading-[1.7] text-card-foreground shadow-lg">
      <div className="mb-1 font-bold">{formatDate(d.ts)}</div>
      {/* Kovada TEK olcum varsa avg/p95/min/max ayni sayidir; dordunu birden yazmak "dort ayri
          veri var" izlenimi verip okumayi zorlastiriyordu. Tek olcumde tek satir. */}
      {d.count === 1 ? row(t('chart.value'), d.avg) : (<>
        {row(t('chart.avg'), d.avg)}
        {row(t('chart.p95'), d.p95)}
        {row(t('chart.min'), d.min)}
        {row(t('chart.max'), d.max)}
      </>)}
      {isPing && row(t('chart.packetLoss'), d.loss, '%')}
      {isSsl && row(t('modal.daysRemain'), d.days, t('chart.unitDays'))}
      <TipRow label={t('chart.samples')}>{d.count}</TipRow>
      {d.down > 0 && <TipRow label={t('chart.down')} tone="text-destructive">{d.down}</TipRow>}
    </div>
  )
}

/**
 * Metrik birimi → değer biçimi. Grafik ekseni ve ipucu AYNI biçimlendiriciyi kullanır;
 * ayrışırlarsa aynı sayı iki yerde farklı okunur.
 */
const VALUE_FORMAT = {
  ms: { fmt: (v) => `${v}ms`,        axis: (v) => `${v}ms`,          width: 46 },
  B:  { fmt: (v) => formatBytes(v),  axis: formatBytesAxis,          width: 58 },
  '': { fmt: (v) => String(v),       axis: (v) => String(v),         width: 40 },
}

/** Özet kutucuğu (min / ort / p95 / maks / kesinti / örnek) — DetailMetric ölçüsünde, ton yalnız değer rengi. */
function StatTile({ label, value, hint, tone }) {
  const body = (
    <div data-slot="chart-tile" className={cn('flex min-w-0 flex-col gap-0.5 rounded-lg border bg-muted/30 px-2.5 py-2', hint && 'cursor-help')}>
      <span data-slot="chart-tile-value" className={cn('truncate text-[15px] leading-tight font-bold tabular-nums', tone ?? 'text-foreground')}>{value}</span>
      <span className="text-[10px] font-semibold tracking-[.05em] text-muted-foreground uppercase">{label}</span>
    </div>
  )
  return hint ? <SimpleTooltip content={hint}>{body}</SimpleTooltip> : body
}

/**
 * Yanıt süresi grafiği — dokuz izleme türünün ortak "Yanıt Süresi" sekmesi (shadcn Chart + recharts).
 *
 * <p>2026-09-27 yeniden tasarım: aralık `ui/SegmentedControl` (1s…90g + özel; telefonda kendi kabında kayar),
 * seriler shadcn ToggleGroup (çoklu; aria-pressed — klavyeyle de açılıp kapanır), özet kutucukları (min / ort /
 * p95 tepe / maks / kesinti / örnek — seçili pencerenin kovalarından türer), eşik çizgisi (`budget`), yükseklik
 * telefonda 224 px, geniş ekranda 300 px. Boş/yükleniyor/kırpılmış durumları ayrı (dönen spinner "veri yok"
 * demek değildir). Test kancaları: `data-slot="response-time-chart|chart-tile|chart-series"`, seride `data-series`.
 */
export default function ResponseTimeChart({ monitorId, kind, metric, unit = 'ms', budget = null, budgetLabel = null }) {
  const t = useT()
  const isPing = kind === 'ping'
  // Sertifika: ana seri kontrol süresi (ms), yardımcı seri kalan gün — ping'in paket kaybı için
  // kurduğu ikinci eksen deseninin aynısı. response_ms kolonu YENİ olduğu için geçmişte ms yok,
  // kalan gün ise 180 günlük geçmişten dolu gelir; hasData bunu da saymalı (aşağıda).
  const isSsl = kind === 'ssl'
  // Varsayılan aralık 24 saat (eskiden 30 gündü) — TÜM izleme türlerinde. Grafik "şu an ne
  // oluyor" sorusuna bakılan yer; 30 günlük pencere son birkaç saatteki dalgalanmayı kova
  // ortalamasında eritiyordu. Uzun pencereye ihtiyaç olduğunda tek tıkla erişiliyor.
  // Yan fayda: 24 saat en küçük pencere → ilk açılışta en az satır taranır.
  const [preset, setPreset] = useState('24h')
  const [custom, setCustom] = useState(null)         // { from, to } ISO (UTC)
  const [showCustom, setShowCustom] = useState(false)
  const [pickFrom, setPickFrom] = useState(() => { const d = new Date(); d.setDate(d.getDate() - 7); return d })
  const [pickTo, setPickTo] = useState(() => new Date())
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(false)
  const [hidden, setHidden] = useState(() => new Set())   // gizlenen seriler (ToggleGroup çoklu seçim)

  // YARIŞ KORUMASI (desen: history/useCheckHistory.js). 24s → 7g → 30g hızlıca tıklanırsa
  // yavaş dönen ESKİ yanıt yeniyi eziyor, grafik seçili olmayan aralığı gösteriyordu.
  const seqRef = useRef(0)
  const load = useCallback(async () => {
    const seq = ++seqRef.current
    setLoading(true)
    try {
      const fetcher = { ping: api.monitoring.getPingResponseSeries, keyword: api.monitoring.getKeywordResponseSeries,
        port: api.monitoring.getPortResponseSeries, dns: api.monitoring.getDnsResponseSeries, http: api.monitoring.getHttpResponseSeries,
        page: api.monitoring.getPageResponseSeries, scripted: api.monitoring.getScriptedResponseSeries,
        pagespeed: api.monitoring.getPageSpeedSeries,
        ssl: api.monitoring.getSslResponseSeries }[kind] ?? api.monitoring.getKeywordResponseSeries
      const sel = PRESETS.find(p => p.key === preset)
      // Saatlik pencereler gun cinsinden ifade edilemez: acik from/to gonderilir (ozel aralikla ayni yol).
      const params = custom ? { from: custom.from, to: custom.to }
        : sel?.hours ? { from: toIso(Date.now() - sel.hours * 3600_000), to: toIso(Date.now()) }
        : { days: sel?.days ?? 30 }
      // metric yalnız sayfa hızında dolu; diğer uçlarda undefined kalır ve istemci onu URL'e koymaz.
      const res = await fetcher(monitorId, metric ? { ...params, metric } : params)
      if (seq !== seqRef.current) return          // daha yeni bir istek var: bu yanıtı YOK SAY
      setData(res?.success ? res.data : null)
    } finally {
      setLoading(false)
    }
  }, [monitorId, kind, preset, custom, metric])

  useEffect(() => { load() }, [load])

  const bucket = data?.bucket
  // Savunma katmanı: ts'siz/bozuk kayıtlar (yanlış beslenmiş endpoint vb.) grafiği DEĞİL yalnız
  // o kaydı düşürür — 2026-08 scripted regresyonunda ham Object[] beslemesi tüm ekranı çökertmişti.
  const chartData = useMemo(() => (data?.series ?? [])
    .filter(s => typeof s?.ts === 'string' && s.ts.length > 0)
    .map(s => ({
      ts: s.ts,
      label: tickLabel(s.ts, bucket),
      avg: s.avg, min: s.min, max: s.max, p95: s.p95, count: s.count, down: s.down, loss: s.loss, days: s.days,
      band: (s.min != null && s.max != null) ? [s.min, s.max] : null,
      downMarker: s.down > 0 ? (s.avg ?? s.max ?? 0) : null,
    })), [data, bucket])

  function applyCustom(f, to) {
    setPickFrom(f); setPickTo(to)
    setCustom({ from: toIso(f), to: toIso(to) })
    setShowCustom(false)
  }
  function pickPreset(key) { setCustom(null); setShowCustom(false); setPreset(key) }
  function pickRange(key) { if (key === 'custom') setShowCustom(true); else pickPreset(key) }

  // Yardımcı seri de veri sayılır: sertifikada ms kolonu yeni olduğu için ilk günlerde avg boş,
  // ama kalan gün eğrisi dolu — yalnız avg'e bakan eski kontrol ekranı tümüyle "veri yok" gösterirdi.
  const valueFormat = VALUE_FORMAT[unit] ?? VALUE_FORMAT.ms
  const hasData = chartData.some(d => d.avg != null || (isSsl && d.days != null))
  const tickEvery = Math.max(0, Math.floor(chartData.length / 8))

  // Özet kutucukları: pencerenin kovalarından türer (örnek ağırlıklı ortalama; p95 = en yüksek kova p95'i).
  const stats = useMemo(() => {
    const pts = chartData.filter(d => d.avg != null)
    if (pts.length === 0) return null
    const w = (d) => Math.max(1, Number(d.count) || 1)
    const weight = pts.reduce((n, d) => n + w(d), 0)
    const avg = pts.reduce((n, d) => n + Number(d.avg) * w(d), 0) / weight
    const mins = pts.map(d => d.min).filter(v => v != null).map(Number)
    const maxs = pts.map(d => d.max).filter(v => v != null).map(Number)
    const p95s = pts.map(d => d.p95).filter(v => v != null).map(Number)
    return {
      avg: Math.round(avg),
      min: mins.length ? Math.min(...mins) : null,
      max: maxs.length ? Math.max(...maxs) : null,
      p95: p95s.length ? Math.max(...p95s) : null,
      samples: chartData.reduce((n, d) => n + (Number(d.count) || 0), 0),
      down: chartData.reduce((n, d) => n + (Number(d.down) || 0), 0),
    }
  }, [chartData])

  // Seriler tek kaynaktan → ToggleGroup + ChartContainer yapılandırması (`--color-<anahtar>`).
  const SERIES = [
    { key: 'band',       name: t('chart.minmax'),     color: '#93c5fd' },
    { key: 'avg',        name: t('chart.avg'),        color: '#2563eb' },
    { key: 'p95',        name: t('chart.p95'),        color: '#9333ea' },
    ...(isPing ? [{ key: 'loss', name: t('chart.packetLoss'), color: '#ea580c' }] : []),
    ...(isSsl  ? [{ key: 'days', name: t('modal.daysRemain'),  color: '#0d9488' }] : []),
    { key: 'downMarker', name: t('chart.down'),       color: '#dc2626' },
  ]
  const visible = SERIES.map(s => s.key).filter(k => !hidden.has(k))
  const chartConfig = Object.fromEntries(SERIES.map(s => [s.key, { label: s.name, color: s.color }]))

  const rangeOptions = [
    ...PRESETS.map(p => ({ value: p.key, label: t(`chart.range${p.key}`) })),
    { value: 'custom', label: t('chart.custom'), icon: Calendar },
  ]
  const rangeValue = custom || showCustom ? 'custom' : preset

  return (
    <div data-slot="response-time-chart" className="flex min-w-0 flex-col gap-3">
      {/* Aralık seçici: bitişik 8 düğme telefonda sığmaz → kendi kabında yatay kayar (sayfa taşmaz). */}
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <div data-slot="chart-range-scroll" className="max-w-full min-w-0 overflow-x-auto overscroll-x-contain rounded-lg [scrollbar-width:thin]">
          <SegmentedControl ariaLabel={t('chart.rangeLabel')} options={rangeOptions} value={rangeValue} onChange={pickRange}
            className="w-max flex-nowrap" />
        </div>
        {custom && !showCustom && (
          <Button type="button" variant="ghost" size="sm" className="gap-1.5 text-xs text-muted-foreground pointer-coarse:h-10"
            aria-expanded={showCustom} onClick={() => setShowCustom(true)}>
            <span className="tabular-nums">{formatDateSec(custom.from)} → {formatDateSec(custom.to)}</span>
            <Pencil aria-hidden="true" className="size-3.5" />
            <span className="sr-only">{t('chart.editRange')}</span>
          </Button>
        )}
      </div>

      {showCustom && (
        <DateTimeRangePicker from={pickFrom} to={pickTo} onApply={applyCustom} />
      )}

      {data?.capped && (
        <AlertBanner tone="warning" className="my-0 py-2">{t('chart.capped')}</AlertBanner>
      )}

      {loading ? (
        <LoadingBlock label={t('modal.loading')} />
      ) : !hasData ? (
        /* Boş durum LoadingBlock ile gösterilirse dönen spinner çıkar ve "yükleniyor" ile
           "veri yok" ayrışmaz — kullanıcı sonsuza kadar bekleniyor sanır. */
        <StatusBlock icon={BarChart3} title={t('chart.noData')} description={t('chart.noDataHint')} className="rounded-lg border border-dashed" />
      ) : (
        <>
        {stats && (
          <div data-slot="chart-tiles" className="grid grid-cols-3 gap-2 sm:grid-cols-6">
            <StatTile label={t('chart.min')} value={stats.min == null ? '—' : valueFormat.fmt(stats.min)} />
            <StatTile label={t('chart.avg')} value={valueFormat.fmt(stats.avg)} hint={t('chart.avgHint')} />
            <StatTile label={t('chart.p95')} value={stats.p95 == null ? '—' : valueFormat.fmt(stats.p95)} hint={t('chart.p95Hint')} />
            <StatTile label={t('chart.max')} value={stats.max == null ? '—' : valueFormat.fmt(stats.max)} />
            <StatTile label={t('chart.down')} value={String(stats.down)} tone={stats.down > 0 ? 'text-destructive' : 'text-success'} hint={t('chart.downHint')} />
            <StatTile label={t('chart.samples')} value={stats.samples.toLocaleString()} />
          </div>
        )}
        {/* shadcn Chart: ChartContainer (yükseklik sabit, genişlik kaptan) + recharts parçaları. Telefonda 224 px. */}
        <ChartContainer config={chartConfig} className="aspect-auto h-56 w-full sm:h-[300px]">
          <ComposedChart data={chartData} margin={{ top: 10, right: (isPing || isSsl) ? 8 : 12, left: 0, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" vertical={false} />
            <XAxis dataKey="label" tick={{ fontSize: 10 }} tickLine={false} axisLine={false}
              interval={tickEvery} minTickGap={16} />
            <YAxis yAxisId="ms" tick={{ fontSize: 10 }} tickLine={false} axisLine={false}
              width={valueFormat.width} tickFormatter={valueFormat.axis} />
            {isPing && (
              <YAxis yAxisId="loss" orientation="right" domain={[0, 100]} tick={{ fontSize: 10, fill: 'var(--color-loss)' }}
                tickLine={false} axisLine={false} width={34} tickFormatter={(v) => `${v}%`} />
            )}
            {isSsl && (
              <YAxis yAxisId="days" orientation="right" tick={{ fontSize: 10, fill: 'var(--color-days)' }}
                tickLine={false} axisLine={false} width={40} tickFormatter={(v) => `${v}${t('chart.unitDays')}`} />
            )}
            <ChartTooltip content={<SeriesTooltip t={t} isPing={isPing} isSsl={isSsl} fmt={valueFormat.fmt} />} />
            <Area yAxisId="ms" type="monotone" dataKey="band" name={t('chart.minmax')} hide={hidden.has('band')}
              fill="#bfdbfe" fillOpacity={0.45} stroke="none" isAnimationActive={false} connectNulls />
            {/* Bütçe / eşik çizgisi (2026-09-12, #15): sayfa hızı eşiği grafikte görünür — aşımlar çizginin üstünde */}
            {budget != null && Number.isFinite(Number(budget)) && Number(budget) > 0 && (
              <ReferenceLine yAxisId="ms" y={Number(budget)} stroke="#dc2626" strokeDasharray="6 4" ifOverflow="extendDomain"
                label={{ value: budgetLabel || t('chart.budget'), position: 'insideTopRight', fill: '#dc2626', fontSize: 10 }} />
            )}
            <Line yAxisId="ms" type="monotone" dataKey="avg" name={t('chart.avg')} hide={hidden.has('avg')}
              stroke="var(--color-avg)" strokeWidth={2} dot={false} isAnimationActive={false} connectNulls />
            <Line yAxisId="ms" type="monotone" dataKey="p95" name={t('chart.p95')} hide={hidden.has('p95')}
              stroke="var(--color-p95)" strokeWidth={1.5} strokeDasharray="4 3" dot={false} isAnimationActive={false} connectNulls />
            {isPing && (
              <Line yAxisId="loss" type="monotone" dataKey="loss" name={t('chart.packetLoss')} hide={hidden.has('loss')}
                stroke="var(--color-loss)" strokeWidth={1.5} dot={false} isAnimationActive={false} connectNulls />
            )}
            {isSsl && (
              <Line yAxisId="days" type="monotone" dataKey="days" name={t('modal.daysRemain')} hide={hidden.has('days')}
                stroke="var(--color-days)" strokeWidth={1.5} dot={false} isAnimationActive={false} connectNulls />
            )}
            <Line yAxisId="ms" dataKey="downMarker" name={t('chart.down')} stroke="transparent" hide={hidden.has('downMarker')}
              dot={{ r: 4, fill: '#dc2626', stroke: '#fff', strokeWidth: 1 }} isAnimationActive={false}
              legendType="circle" connectNulls={false} />
          </ComposedChart>
        </ChartContainer>
        {/* Açıklama = seri anahtarları: shadcn ToggleGroup (çoklu). Basılı = görünür (aria-pressed); klavyeyle de
            değişir. Nasıl çalıştığı dokunmatikte de okunsun diye ipucu görünür metin. */}
        <div className="flex flex-col items-center gap-1">
          <ToggleGroup type="multiple" variant="outline" size="sm" spacing={1.5} data-slot="chart-series"
            aria-label={t('chart.seriesLabel')} value={visible}
            onValueChange={(vals) => setHidden(new Set(SERIES.map(s => s.key).filter(k => !vals.includes(k))))}
            className="flex-wrap justify-center">
            {SERIES.map(s => (
              <ToggleGroupItem key={s.key} value={s.key} data-series={s.key}
                className="h-8 gap-1.5 rounded-md text-xs font-medium data-[state=off]:text-muted-foreground data-[state=off]:line-through data-[state=on]:bg-muted pointer-coarse:h-10">
                <span aria-hidden="true" className="h-[3px] w-[13px] shrink-0 rounded-sm bg-(--dot)" style={{ '--dot': s.color }} />{s.name}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
          <p className="m-0 text-center text-[11px] text-muted-foreground">{t('chart.seriesHint')}</p>
        </div>
        </>
      )}
    </div>
  )
}
