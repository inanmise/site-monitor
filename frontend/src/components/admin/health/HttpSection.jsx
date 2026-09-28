import { useMemo } from 'react'
import { Maximize2 } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { HEALTH_THRESHOLDS, HTTP_ERR_PCT } from './healthModel.js'
import { SectionError, SectionToolbar } from './HealthParts.jsx'
import { Tile } from '../httpmetrics/HttpParts.jsx'
import HttpSparkCharts from '../httpmetrics/HttpSparkCharts.jsx'
import HttpTopLists from '../httpmetrics/HttpTopLists.jsx'
import { errTone, fmtInt, fmtPct, fmtRate, makeMs, msTone, sectionModel } from '../httpmetrics/httpMetricsModel.js'

/**
 * Sistem Sağlığı → HTTP istekleri (2026-09-28 yeniden tasarım; son 24 saat, bellek içi + kalıcı seriden listeler):
 *  - özet kutucukları (toplam istek, istek/dk + tepe, hata oranı, ortalama + p95, en yüksek + p99, en yavaş uç) —
 *    ton Sistem Sağlığı eşiklerinden, açıklama dokun-gör (telefonda da açılır); "en yavaş uç" kutucuğu gezgini o uçla açar
 *  - üç küçük grafik (hacim başarılı/hatalı, süre ort./p95 + eşikler, hata/dk + eşikler) — shadcn Chart
 *  - "en çok hata veren" ve "en yavaş" ilk üç uç — satır gezgini o uca odaklı açar
 *  - İstek Gezgini çağrısı (birincil düğme). Uç düşerse hata bloğu + "Tekrar dene".
 * Telefonda kutucuklar 2 sütun, grafikler tek sütun.
 */
export default function HttpSection({ t, httpMetrics, error, onRetry, onOpenExplorer }) {
  const model = useMemo(() => sectionModel(httpMetrics), [httpMetrics])
  const fmt = useMemo(() => makeMs({ ms: t('rtc.unit.ms'), sec: t('rtc.unit.s') }), [t])
  if (error) return <SectionError t={t} onRetry={onRetry} />

  const s = model.summary
  const minutes = model.points.length
  const rate = s.reqPerMin ?? (minutes ? s.total / minutes : null)
  const peak = s.peak ?? model.points.reduce((m, p) => Math.max(m, p.count), 0)
  const slow = model.top?.slowest?.[0] || null
  const open = (endpoint) => onOpenExplorer?.(endpoint ? { endpoint } : undefined)
  const lat = HEALTH_THRESHOLDS.httpMs

  return (
    <div data-slot="hreq-section" className="flex flex-col gap-3">
      <SectionToolbar left={t('hreq.sec.window')}>
        <Button type="button" size="sm" className="h-10 w-full sm:w-auto lg:h-9 pointer-coarse:h-10" onClick={() => open()}>
          <Maximize2 aria-hidden="true" />{t('health.httpOpenExplorer')}
        </Button>
      </SectionToolbar>

      <div role="group" aria-label={t('hreq.kpi.label')} data-slot="hreq-tiles"
        className="grid grid-cols-2 gap-2 sm:grid-cols-3 2xl:grid-cols-6">
        <Tile t={t} id="total" label={t('hreq.kpi.total')} value={fmtInt(s.total)} sub={t('hreq.kpi.totalSub24')} hint={t('hreq.hint.total')} />
        <Tile t={t} id="rate" label={t('hreq.kpi.rate')} value={fmtRate(rate)} sub={t('hreq.kpi.peak', fmtInt(peak))} hint={t('hreq.hint.rate')} />
        <Tile t={t} id="errors" label={t('hreq.kpi.errorRate')} value={fmtPct(s.errorRate)} tone={errTone(s.errorRate, s.total)}
          sub={t('hreq.kpi.errorsSub', fmtInt(s.errors))} hint={t('hreq.hint.errorRate', HTTP_ERR_PCT.warn, HTTP_ERR_PCT.crit)} />
        <Tile t={t} id="avg" label={t('hreq.kpi.avg')} value={fmt.value(s.avg)} tone={msTone(s.avg)}
          sub={s.p95 != null ? t('hreq.kpi.p95Sub', fmt.value(s.p95)) : null} hint={t('hreq.hint.avg', fmt.value(lat.warn), fmt.value(lat.crit))} />
        <Tile t={t} id="max" label={t('hreq.kpi.max')} value={fmt.value(s.max)}
          sub={s.p99 != null ? t('hreq.kpi.p99Sub', fmt.value(s.p99)) : null} hint={t('hreq.hint.max')} />
        {slow ? (
          <Tile t={t} id="slowest" label={t('hreq.kpi.slowest')} value={fmt.value(slow.p95)} tone={msTone(slow.p95)}
            sub={slow.endpoint} onClick={() => open(slow.endpoint)} actionLabel={t('hreq.kpi.slowestAction', fmt.value(slow.p95), slow.endpoint)} />
        ) : (
          <Tile t={t} id="slowest" label={t('hreq.kpi.slowest')} value="—" sub={t('hreq.top.noData')} hint={t('hreq.hint.slowest')} />
        )}
      </div>

      <HttpSparkCharts t={t} points={model.points} fmt={fmt} />
      <HttpTopLists t={t} top={model.top} fmt={fmt} onOpen={open} />
    </div>
  )
}
