import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import {
  ResponsiveContainer, ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid, Tooltip, ReferenceLine,
} from 'recharts'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import DateTimeRangePicker from './ui/DateTimeRangePicker.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import { formatBytes, formatBytesAxis } from '../utils/formatBytes.js'
import StatusBlock from './ui/StatusBlock.jsx'
import { BarChart3 } from 'lucide-react'

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

function ChartTooltip({ active, payload, t, isPing, isSsl, fmt = (v) => `${v}ms` }) {
  if (!active || !payload || !payload.length) return null
  const d = payload[0].payload
  // Değer biçimi metriğe göre değişir: süre "3480ms", boyut "46.4 MB", istek sayısı çıplak sayı.
  // Sabit 'ms' eki bırakıldığında boyut serisi "48697344ms" yazıyordu — eksen de ipucu da
  // "ne ölçüyorum" sorusuna yanlış cevap veriyordu.
  // suffixOverride: ikinci eksenli seriler (ping paket kaybı %, sertifika kalan gün) kendi
  // birimlerini taşır; onlar ana metrik biçimlendiricisine tabi değildir.
  const row = (label, val, suffixOverride) =>
    val == null ? null : (
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16 }}>
        <span style={{ color: 'var(--text-muted)' }}>{label}</span>
        <strong>{suffixOverride != null ? `${val}${suffixOverride}` : fmt(val)}</strong>
      </div>
    )
  return (
    <div style={{ background: 'var(--bg-card, #fff)', border: '1px solid var(--border)', borderRadius: 8,
      padding: '8px 11px', fontSize: '.82em', lineHeight: 1.7, boxShadow: '0 4px 16px rgba(0,0,0,.12)' }}>
      <div style={{ fontWeight: 700, marginBottom: 4 }}>{formatDate(d.ts)}</div>
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
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16 }}>
        <span style={{ color: 'var(--text-muted)' }}>{t('chart.samples')}</span><strong>{d.count}</strong>
      </div>
      {d.down > 0 && (
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16, color: '#dc2626' }}>
          <span>{t('chart.down')}</span><strong>{d.down}</strong>
        </div>
      )}
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
  const [hidden, setHidden] = useState(() => new Set())   // tıklanabilir legend: izole/gizle (gezgin deseni)

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
  }
  function pickPreset(key) { setCustom(null); setShowCustom(false); setPreset(key) }

  // Yardımcı seri de veri sayılır: sertifikada ms kolonu yeni olduğu için ilk günlerde avg boş,
  // ama kalan gün eğrisi dolu — yalnız avg'e bakan eski kontrol ekranı tümüyle "veri yok" gösterirdi.
  const valueFormat = VALUE_FORMAT[unit] ?? VALUE_FORMAT.ms
  const hasData = chartData.some(d => d.avg != null || (isSsl && d.days != null))
  const tickEvery = Math.max(0, Math.floor(chartData.length / 8))

  // Tıklanabilir legend — HttpMetricsExplorer deseni: ilk tık izole (yalnız bunu), sonraki ekle/çıkar, hepsi gizli → hepsi.
  const SERIES = [
    { key: 'band',       name: t('chart.minmax'),     color: '#93c5fd' },
    { key: 'avg',        name: t('chart.avg'),        color: '#2563eb' },
    { key: 'p95',        name: t('chart.p95'),        color: '#9333ea' },
    ...(isPing ? [{ key: 'loss', name: t('chart.packetLoss'), color: '#ea580c' }] : []),
    ...(isSsl  ? [{ key: 'days', name: t('modal.daysRemain'),  color: '#0d9488' }] : []),
    { key: 'downMarker', name: t('chart.down'),       color: '#dc2626' },
  ]
  const toggleSeries = (key) => setHidden(prev => {
    const allKeys = SERIES.map(s => s.key)
    if (allKeys.every(k => !prev.has(k))) return new Set(allKeys.filter(k => k !== key))   // ilk tık → izole
    const next = new Set(prev)
    if (next.has(key)) next.delete(key); else next.add(key)
    return allKeys.every(k => next.has(k)) ? new Set() : next                              // hepsi gizli → hepsini göster
  })

  return (
    <div>
      <div className="upt-range-btns" style={{ flexWrap: 'wrap' }}>
        {PRESETS.map(p => (
          <button key={p.key} type="button"
            className={`btn btn-sm ${!custom && preset === p.key ? 'btn-primary' : 'btn-secondary'}`}
            onClick={() => pickPreset(p.key)}>{t(`chart.range${p.key}`)}</button>
        ))}
        <button type="button" className={`btn btn-sm ${custom ? 'btn-primary' : 'btn-secondary'}`}
          onClick={() => setShowCustom(s => !s)}>{t('chart.custom')}</button>
      </div>

      {showCustom && (
        <div style={{ margin: '8px 0' }}>
          <DateTimeRangePicker from={pickFrom} to={pickTo} onApply={applyCustom} />
        </div>
      )}

      {data?.capped && (
        <div style={{ fontSize: '.78em', color: '#b45309', margin: '4px 0' }}>⚠ {t('chart.capped')}</div>
      )}

      {loading ? (
        <LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />
      ) : !hasData ? (
        /* Boş durum LoadingBlock ile gösterilirse dönen spinner çıkar ve "yükleniyor" ile
           "veri yok" ayrışmaz — kullanıcı sonsuza kadar bekleniyor sanır. */
        <StatusBlock icon={BarChart3} title={t('chart.noData')} />
      ) : (
        <>
        <ResponsiveContainer width="100%" height={300}>
          <ComposedChart data={chartData} margin={{ top: 10, right: (isPing || isSsl) ? 8 : 12, left: 0, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
            <XAxis dataKey="label" tick={{ fontSize: 10, fill: 'var(--text-light)' }} stroke="var(--border)"
              interval={tickEvery} minTickGap={16} />
            <YAxis yAxisId="ms" tick={{ fontSize: 10, fill: 'var(--text-light)' }} stroke="var(--border)"
              width={valueFormat.width} tickFormatter={valueFormat.axis} />
            {isPing && (
              <YAxis yAxisId="loss" orientation="right" domain={[0, 100]} tick={{ fontSize: 10, fill: '#ea580c' }}
                stroke="var(--border)" width={34} tickFormatter={(v) => `${v}%`} />
            )}
            {isSsl && (
              <YAxis yAxisId="days" orientation="right" tick={{ fontSize: 10, fill: '#0d9488' }}
                stroke="var(--border)" width={40} tickFormatter={(v) => `${v}${t('chart.unitDays')}`} />
            )}
            <Tooltip content={<ChartTooltip t={t} isPing={isPing} isSsl={isSsl} fmt={valueFormat.fmt} />} />
            <Area yAxisId="ms" type="monotone" dataKey="band" name={t('chart.minmax')} hide={hidden.has('band')}
              fill="#bfdbfe" fillOpacity={0.45} stroke="none" isAnimationActive={false} connectNulls />
            {/* Bütçe / eşik çizgisi (2026-09-12, #15): sayfa hızı eşiği grafikte görünür — aşımlar çizginin üstünde */}
            {budget != null && Number.isFinite(Number(budget)) && Number(budget) > 0 && (
              <ReferenceLine yAxisId="ms" y={Number(budget)} stroke="#dc2626" strokeDasharray="6 4" ifOverflow="extendDomain"
                label={{ value: budgetLabel || t('chart.budget'), position: 'insideTopRight', fill: '#dc2626', fontSize: 10 }} />
            )}
            <Line yAxisId="ms" type="monotone" dataKey="avg" name={t('chart.avg')} hide={hidden.has('avg')}
              stroke="#2563eb" strokeWidth={2} dot={false} isAnimationActive={false} connectNulls />
            <Line yAxisId="ms" type="monotone" dataKey="p95" name={t('chart.p95')} hide={hidden.has('p95')}
              stroke="#9333ea" strokeWidth={1.5} strokeDasharray="4 3" dot={false} isAnimationActive={false} connectNulls />
            {isPing && (
              <Line yAxisId="loss" type="monotone" dataKey="loss" name={t('chart.packetLoss')} hide={hidden.has('loss')}
                stroke="#ea580c" strokeWidth={1.5} dot={false} isAnimationActive={false} connectNulls />
            )}
            {isSsl && (
              <Line yAxisId="days" type="monotone" dataKey="days" name={t('modal.daysRemain')} hide={hidden.has('days')}
                stroke="#0d9488" strokeWidth={1.5} dot={false} isAnimationActive={false} connectNulls />
            )}
            <Line yAxisId="ms" dataKey="downMarker" name={t('chart.down')} stroke="transparent" hide={hidden.has('downMarker')}
              dot={{ r: 4, fill: '#dc2626', stroke: '#fff', strokeWidth: 1 }} isAnimationActive={false}
              legendType="circle" connectNulls={false} />
          </ComposedChart>
        </ResponsiveContainer>
        <div className="hme-legend">
          {SERIES.map(s => (
            <button key={s.key} type="button" title={t('chart.legendTip')}
              className={`hme-legend-item${hidden.has(s.key) ? ' hme-legend-off' : ''}`}
              onClick={() => toggleSeries(s.key)}>
              <span className="hme-legend-dot" style={{ background: s.color }} />{s.name}
            </button>
          ))}
        </div>
        </>
      )}
    </div>
  )
}
