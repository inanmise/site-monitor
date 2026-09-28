import { useMemo, useState } from 'react'
import { BarChart3, RotateCw } from 'lucide-react'
import { useT } from '../i18n/index.jsx'
import DateTimeRangePicker from './ui/DateTimeRangePicker.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import ChartToolbar from './responsechart/ChartToolbar.jsx'
import ChartKpis from './responsechart/ChartKpis.jsx'
import ChartPanel from './responsechart/ChartPanel.jsx'
import { useResponseSeries } from './responsechart/useResponseSeries.js'
import {
  DEFAULT_PRESET, buildSummary, computeStats, isLiveRange, kindMeta, makeFormat, nextWiderPreset, resolveUnit,
  shapeSeries, toIso, validThreshold,
} from './responsechart/responseChartModel.js'

/**
 * Süre grafiği — dokuz izleme türünün detay penceresindeki ortak "Süre Grafiği / Grafik" sekmesi
 * (HTTP, Keyword, Sayfa, Sayfa Hızı, Ping, Port, Sentetik, DNS, Sertifika).
 *
 * <p>2026-09-28 yeniden tasarım (shadcn + mobil): araç çubuğu (aralık: telefonda NativeSelect, geniş ekranda
 * SegmentedControl · canlı göstergesi · son güncelleme · yenile) → özet kutucukları (ortalama, p95, en yüksek,
 * erişilebilirlik, başarısız kontrol, türe özgü ölçü; her birinin dokun-gör açıklaması) → grafik paneli (degrade
 * dolgulu ortalama, p95, min–maks bandı, eşik çizgisi, başarısız kova gölgesi + işaretleri, veri olmayan dilimde
 * kopan çizgi, zengin ipucu, seri anahtarları, ping/sertifika için ortak zaman eksenli ikinci küçük grafik) +
 * ekran okuyucu özeti. Model: `responsechart/responseChartModel.js`, veri: `responsechart/useResponseSeries.js`.
 *
 * <p>Durumlar: ilk yüklemede iskelet; aralık/metrik değişince eski grafik SOLUK kalır + küçük spinner (titreme
 * yok, yarış korumalı); boş aralıkta "veri yok" + aralığı genişlet; hata → AlertBanner + Yeniden dene (varsa son
 * başarılı veri soluk kalır). Duraklatılmış izlemenin geçmişi de çizilir (istek duruma bakmaz).
 *
 * <p>Props (dokuz çağrı yeri): `monitorId` (sertifikada alan adı), `kind`, `metric` (yalnız sayfa hızı), `unit`
 * ('ms' | 'B' | '' — verilmezse türün varsayılanı; `page` kırık kaynak SAYISI), `budget` + `budgetLabel` (sayfa hızı
 * eşiği), `slowThreshold` (yavaş yanıt eşiği, ms — süre birimli türlerde eşik çizgisi).
 * Test kancaları: `data-slot="response-time-chart|chart-toolbar|chart-tiles|chart-tile|chart-panel|chart-series|
 * chart-threshold|chart-summary|chart-aux|chart-skeleton|chart-empty"`, kutucukta `data-kpi` + `data-tone`.
 */
export default function ResponseTimeChart({ monitorId, kind, metric, unit, budget = null, budgetLabel = null, slowThreshold = null }) {
  const t = useT()
  const [preset, setPreset] = useState(DEFAULT_PRESET)
  const [custom, setCustom] = useState(null)         // { from, to } ISO (UTC, Z'siz)
  const [showCustom, setShowCustom] = useState(false)
  const [pickFrom, setPickFrom] = useState(() => { const d = new Date(); d.setDate(d.getDate() - 7); return d })
  const [pickTo, setPickTo] = useState(() => new Date())
  const [hidden, setHidden] = useState(() => new Set())

  const effUnit = resolveUnit(kind, unit)
  const budgetValue = validThreshold(budget)
  const threshold = budgetValue ?? (effUnit === 'ms' ? validThreshold(slowThreshold) : null)
  const thresholdLabel = budgetValue != null ? (budgetLabel || t('chart.budget')) : t('rtc.slowLine')
  const extra = { unit: effUnit, threshold, thresholdLabel }

  const { view, loading, error, stale, reload } = useResponseSeries({ monitorId, kind, metric, preset, custom, extra })

  // Soluk (eski) görünüm KENDİ metriği/birimi/eşiğiyle çizilir: metrik değişirken eski sayıları yeni birimle
  // etiketlemek yanlış okuturdu.
  const shownMetric = stale ? view?.metric : metric
  const shown = stale && view ? view.extra : extra
  const meta = kindMeta(kind, shownMetric)
  const shape = useMemo(() => (view ? shapeSeries(view.data, { aux: meta.aux }) : null), [view, meta.aux])
  const stats = useMemo(
    () => (shape ? computeStats(shape, { threshold: shown.threshold, aux: meta.aux }) : null),
    [shape, shown.threshold, meta.aux],
  )
  const fmt = useMemo(() => makeFormat(shown.unit, { ms: t('rtc.unit.ms'), sec: t('rtc.unit.s') }), [shown.unit, t])
  const title = t(meta.titleKey)
  const auxLabel = meta.aux === 'loss' ? t('chart.packetLoss') : meta.aux === 'days' ? t('modal.daysRemain') : null
  const rangeKey = view?.rangeKey ?? (custom ? 'custom' : preset)   // özet, ÇİZİLEN verinin aralığını söyler
  const rangeValue = custom || showCustom ? 'custom' : preset
  const wider = custom ? null : nextWiderPreset(preset)

  function applyCustom(f, to) {
    setPickFrom(f); setPickTo(to)
    setCustom({ from: toIso(f), to: toIso(to) })
    setShowCustom(false)
  }
  function pickPreset(key) { setCustom(null); setShowCustom(false); setPreset(key) }
  function pickRange(key) { if (key === 'custom') setShowCustom(true); else pickPreset(key) }

  // Yeniden dene düğmesi metnin ALTINDA (AlertBanner `actions` yan sütunu telefonda metni 3–4 harflik sütuna eziyordu).
  const errorBanner = (message) => (
    <AlertBanner tone="danger" title={t('rtc.error')} className="mb-0">
      <span>{message}</span>
      <Button type="button" variant="outline" size="sm" onClick={reload} className="mt-1.5 gap-1.5 pointer-coarse:h-10">
        <RotateCw aria-hidden="true" className="size-3.5" />{t('rtc.retry')}
      </Button>
    </AlertBanner>
  )

  let body
  if (!view && error) {
    body = errorBanner(error.message ? `${t('rtc.errorHint')} (${error.message})` : t('rtc.errorHint'))
  } else if (!view) {
    body = (
      <div data-slot="chart-skeleton" aria-busy="true" className="flex flex-col gap-3">
        <span role="status" className="sr-only">{t('rtc.loadingChart')}</span>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 md:grid-cols-5">
          {Array.from({ length: 5 }, (_, i) => (
            <Skeleton key={i} className="h-[74px] rounded-lg motion-reduce:animate-none" />
          ))}
        </div>
        <Skeleton className="h-[268px] rounded-xl motion-reduce:animate-none sm:h-[356px]" />
      </div>
    )
  } else if (!shape.hasData) {
    body = (
      <div data-slot="chart-empty" data-stale={stale ? 'true' : undefined} className={stale ? 'opacity-60' : undefined}>
        {/* Boş durum LoadingBlock ile gösterilmez: dönen spinner "yükleniyor" ile "veri yok"u ayırt ettirmez. */}
        <StatusBlock icon={BarChart3} title={t('chart.noData')} description={t('chart.noDataHint')}
          className="rounded-xl border border-dashed"
          actions={wider ? (
            <Button type="button" variant="outline" size="sm" onClick={() => pickPreset(wider)} className="pointer-coarse:h-10">
              {t(`rtc.widen.${wider}`)}
            </Button>
          ) : null} />
      </div>
    )
  } else {
    body = (
      <div className={stale ? 'flex flex-col gap-3 opacity-60 transition-opacity motion-reduce:transition-none' : 'flex flex-col gap-3'}
        data-stale={stale ? 'true' : undefined}>
        <ChartKpis t={t} stats={stats} fmt={fmt} raw={shape.raw} aux={meta.aux} threshold={shown.threshold} />
        <ChartPanel t={t} title={title} shape={shape} stats={stats} fmt={fmt} aux={meta.aux} auxLabel={auxLabel}
          threshold={shown.threshold} thresholdLabel={shown.thresholdLabel} hidden={hidden} onHiddenChange={setHidden}
          summary={buildSummary({ t, rangeKey, stats, fmt })} busy={loading && stale} />
      </div>
    )
  }

  return (
    <div data-slot="response-time-chart" className="flex min-w-0 flex-col gap-3">
      <ChartToolbar t={t} rangeValue={rangeValue} onPick={pickRange} custom={custom} showCustom={showCustom}
        onEditCustom={() => setShowCustom(true)} live={isLiveRange({ preset, custom })} updatedAt={view?.at ?? null}
        busy={loading && !!view} onRefresh={reload} />

      {showCustom && <DateTimeRangePicker from={pickFrom} to={pickTo} onApply={applyCustom} />}

      {view && error && errorBanner(stale ? t('rtc.staleNote') : t('rtc.errorHint'))}
      {shape?.capped && <AlertBanner tone="warning" className="mb-0 py-2">{t('chart.capped')}</AlertBanner>}

      {body}
    </div>
  )
}
