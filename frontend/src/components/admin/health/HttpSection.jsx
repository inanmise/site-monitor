import { Globe, Maximize2 } from 'lucide-react'
import { formatPercent } from '../../../i18n/dateLocale.js'
import { Button } from '@/components/shadcn/button'
import MiniChart from '../MiniChart'
import { KpiCard } from '../HealthUi.jsx'
import { HEALTH_THRESHOLDS, HTTP_ERR_PCT } from './healthModel.js'
import { CHART_GRID, SectionError, SectionToolbar } from './HealthParts.jsx'

/**
 * HTTP istekleri (son 24 saat): dört mini KPI + üç grafik (istek/dk, ortalama süre, hata/dk) — tıklanınca İstek
 * Gezgini (ModalShell, HttpMetricsExplorer). Uç düşerse hata bloğu + "Tekrar dene".
 */
export default function HttpSection({ t, httpMetrics, error, onRetry, onOpenExplorer }) {
  if (error) return <SectionError t={t} onRetry={onRetry} />
  const sum = httpMetrics?.summary || {}
  const hist = httpMetrics?.history || []
  const err = sum.error_rate_pct ?? 0
  const avg = sum.avg_ms ?? 0
  return (
    <div className="flex flex-col gap-3">
      <SectionToolbar left={t('http.title')}>
        <Button type="button" variant="outline" size="sm" className="h-10 sm:h-9" onClick={onOpenExplorer}>
          <Maximize2 aria-hidden="true" />{t('health.httpOpenExplorer')}
        </Button>
      </SectionToolbar>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <KpiCard kpiKey="http-total" mini value={Number(sum.total_requests ?? 0).toLocaleString()} label={t('http.totalReqs')} />
        <KpiCard kpiKey="http-err-pct" mini value={formatPercent(err)} label={t('http.errorRate')}
          tone={err >= HTTP_ERR_PCT.crit ? 'danger' : err >= HTTP_ERR_PCT.warn ? 'warn' : undefined} />
        <KpiCard kpiKey="http-avg" mini value={`${avg} ms`} label={t('http.avgMs')}
          tone={avg >= HEALTH_THRESHOLDS.httpMs.crit ? 'danger' : avg >= HEALTH_THRESHOLDS.httpMs.warn ? 'warn' : undefined} />
        <KpiCard kpiKey="http-max" mini value={`${sum.max_ms ?? 0} ms`} label={t('http.maxMs')} />
      </div>
      <div className={CHART_GRID} data-slot="http-charts">
        <MiniChart label={t('http.reqPerMin')} unit="" color="#4f9cf9" data={hist.map((b) => ({ ts: b.ts, value: b.count }))} onClick={onOpenExplorer} />
        <MiniChart label={t('http.avgDuration')} thresholds={HEALTH_THRESHOLDS.httpMs} breachLabel={t('sys.breach')} unit=" ms" color="#f59e0b"
          data={hist.map((b) => ({ ts: b.ts, value: b.avg_ms }))} onClick={onOpenExplorer} />
        <MiniChart label={t('http.errorsPerMin')} thresholds={HEALTH_THRESHOLDS.httpErr} breachLabel={t('sys.breach')} unit="" color="#ef4444"
          data={hist.map((b) => ({ ts: b.ts, value: b.errors }))} onClick={onOpenExplorer} />
      </div>
      <p className="flex items-center gap-1.5 text-xs text-muted-foreground"><Globe size={12} aria-hidden="true" />{t('http.exp.title')}</p>
    </div>
  )
}
