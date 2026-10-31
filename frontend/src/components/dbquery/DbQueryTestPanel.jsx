import { AlertTriangle, CheckCircle2, Columns3, EyeOff, Gauge, Info, ShieldAlert, XCircle } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { RuleResultsList } from './buildingBlocks.jsx'
import {
  checkStatusLabelKey, durationText, failureKey, metricValueText, planTone, rowCountText,
} from './dbqueryPageModel.js'

/**
 * "Sorguyu dene" sonucu (2026-10-31) — kaydetmeden çalıştırılan sorgunun ÖZETİ.
 *
 * <p><b>Kesin ürün kuralı:</b> SELECT sonucunun SATIRLARI burada GÖSTERİLMEZ ve sunucu zaten döndürmez (api §1 `test`:
 * yalnız durum, süre, satır sayısı, kolon ADLARI + türleri, kural sonuçları, güvenlik ihlalleri, plan uyarısı). Panel bunu
 * kullanıcıya da söyler (`dbq-test-norows`) — "veri çekme aracı değil" ilkesinin görünür hâli.
 *
 * <p>Props: `result` (testDbQuery `data`'sı ya da `{ error, code, retryAfter }`), `plan` ("Maliyeti denetle" sonucu,
 * isteğe bağlı). Test kancaları: kök `data-slot="dbq-test"` + `data-status` (OK|BREACH|FAIL|error), kolonlar
 * `dbq-test-columns`, ihlaller `dbq-test-violations`, plan `dbq-test-plan` (+ `data-verdict`).
 */
export default function DbQueryTestPanel({ result, plan = null }) {
  const t = useT()
  if (!result && !plan) return null
  const failedCall = result && result.status == null && (result.error || result.code)
  const tone = failedCall ? 'danger'
    : result?.status === 'FAIL' ? 'danger'
    : result?.status === 'BREACH' ? 'warning'
    : result?.status === 'OK' ? (result.slow ? 'warning' : 'success') : 'info'
  const Icon = tone === 'success' ? CheckCircle2 : tone === 'danger' ? XCircle : tone === 'warning' ? AlertTriangle : Info
  const violations = Array.isArray(result?.guard?.violations) ? result.guard.violations : []
  const columns = Array.isArray(result?.columns) ? result.columns : []
  const effectivePlan = plan || result?.plan || null
  const failKey = failureKey(result?.failure_reason)
  return (
    <section data-slot="dbq-test" data-status={failedCall ? 'error' : (result?.status || 'none')} aria-live="polite"
      className="mt-3 flex min-w-0 flex-col gap-2.5 rounded-lg border bg-muted/30 px-3.5 py-3">
      {result && (
        <AlertBanner tone={tone} icon={Icon}
          title={failedCall ? t('dbq.test.callFailed') : t(checkStatusLabelKey(result))}>
          {failedCall ? (
            <span className="[overflow-wrap:anywhere]">
              {result.error}
              {result.retryAfter ? <> {t('dbq.test.retryIn', result.retryAfter)}</> : null}
            </span>
          ) : (
            <span className="flex min-w-0 flex-col gap-1">
              <span data-slot="dbq-test-summary" className="flex flex-wrap gap-x-3 gap-y-0.5 tabular-nums">
                <span>{t('dbq.test.duration', durationText(result.duration_ms, t))}</span>
                <span>{t('dbq.test.rows', rowCountText(result.row_count, result.row_count_capped))}</span>
                {result.metric_value != null && <span>{t('dbq.test.value', metricValueText(result.metric_value))}</span>}
                {result.slow && <span className="font-semibold">{t('dbq.test.slow')}</span>}
              </span>
              {result.row_count_capped && <span className="text-xs">{t('dbq.test.capped')}</span>}
              {result.status === 'FAIL' && (
                <span className="text-xs [overflow-wrap:anywhere]">
                  {failKey && <strong className="mr-1">{t(failKey)}.</strong>}
                  {result.error}
                </span>
              )}
            </span>
          )}
        </AlertBanner>
      )}

      {/* Satır verisinin YOKLUĞU bir eksiklik değil, ilke — kullanıcıya açıkça söylenir. */}
      {result && !failedCall && (
        <p data-slot="dbq-test-norows" className="flex min-w-0 items-start gap-1.5 text-xs text-muted-foreground">
          <EyeOff aria-hidden="true" className="mt-px size-3.5 shrink-0" />
          <span className="[overflow-wrap:anywhere]">{t('dbq.test.noRows')}</span>
        </p>
      )}

      {violations.length > 0 && (
        <div data-slot="dbq-test-violations" className="flex min-w-0 flex-col gap-1">
          <span className="flex items-center gap-1.5 text-xs font-semibold text-destructive">
            <ShieldAlert aria-hidden="true" className="size-3.5" />{t('dbq.test.violations', violations.length)}
          </span>
          <ul className="m-0 flex list-none flex-col gap-1 p-0">
            {violations.map((v, i) => (
              <li key={`${v.code}-${i}`} className="min-w-0 rounded-md border border-destructive/30 bg-destructive/5 px-2.5 py-1.5 text-xs [overflow-wrap:anywhere]">
                {v.line != null && <span className="mr-1.5 font-mono text-muted-foreground">{t('dbq.test.at', v.line, v.column ?? 1)}</span>}
                {v.message || v.code}
              </li>
            ))}
          </ul>
        </div>
      )}

      {columns.length > 0 && (
        <div data-slot="dbq-test-columns" className="flex min-w-0 flex-col gap-1.5">
          <span className="flex items-center gap-1.5 text-xs font-semibold text-muted-foreground">
            <Columns3 aria-hidden="true" className="size-3.5" />{t('dbq.test.columns', columns.length)}
          </span>
          <ul className="m-0 flex list-none flex-wrap gap-1.5 p-0">
            {columns.map((c, i) => (
              <li key={`${c.name}-${i}`}>
                <Badge variant="outline" className="h-auto max-w-full gap-1 overflow-visible rounded-md px-2 py-0.5 text-[11px] whitespace-normal">
                  <span className="font-mono font-semibold [overflow-wrap:anywhere]">{c.name}</span>
                  {c.type && <span className="text-muted-foreground">{c.type}</span>}
                </Badge>
              </li>
            ))}
          </ul>
        </div>
      )}

      {Array.isArray(result?.rule_results) && result.rule_results.length > 0 && (
        <div className="min-w-0">
          <span className="mb-1 block text-xs font-semibold text-muted-foreground">{t('dbq.test.rules')}</span>
          <RuleResultsList results={result.rule_results} compact />
        </div>
      )}

      {effectivePlan && (
        <div data-slot="dbq-test-plan" data-verdict={effectivePlan.supported === false ? 'UNSUPPORTED' : (effectivePlan.verdict || 'UNKNOWN')}>
          <AlertBanner tone={planTone(effectivePlan) === 'neutral' ? 'info' : planTone(effectivePlan)} icon={Gauge}
            title={t(planTitleKey(effectivePlan))}>
            <span className={cn('flex flex-col gap-0.5 [overflow-wrap:anywhere]')}>
              {effectivePlan.supported !== false && (effectivePlan.cost != null || effectivePlan.rows != null) && (
                <span className="tabular-nums">
                  {t('dbq.test.planFigures', metricValueText(effectivePlan.cost), metricValueText(effectivePlan.rows))}
                </span>
              )}
              {effectivePlan.message && <span>{effectivePlan.message}</span>}
            </span>
          </AlertBanner>
        </div>
      )}
    </section>
  )
}

function planTitleKey(plan) {
  if (plan.supported === false) return 'dbq.test.planUnsupported'
  if (plan.verdict === 'OK') return 'dbq.test.planOk'
  if (plan.verdict === 'WARN') return 'dbq.test.planWarn'
  if (plan.verdict === 'BLOCK') return 'dbq.test.planBlock'
  return 'dbq.test.planUnknown'
}
