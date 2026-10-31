import {
  AlertTriangle, CheckCircle2, ChevronDown, CircleHelp, Clock, Columns3, EyeOff, Gauge, Info, ListPlus, ShieldAlert, XCircle,
} from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { cn } from '@/lib/utils'
import { RuleResultsList } from './buildingBlocks.jsx'
import { WARNING_CODES, columnKind, ruleOptionsFor } from './ruleSuggest.js'
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
 * isteğe bağlı). Test kancaları: kök `data-slot="dbq-test"` + `data-status` (OK|BREACH|FAIL|error|wait), kolonlar
 * `dbq-test-columns`, ihlaller `dbq-test-violations`, plan `dbq-test-plan` (+ `data-verdict`).
 *
 * <p>2026-10-31 (r177): kolon rozeti bir menüdür (`onColumnAction(action, column)` verilirse) — "Bu kolonun değerine
 * kural ekle" / "Bu kolonda metin ara" / "Tazelik kuralı ekle" (`rule:<TÜR>`) ve "Mailde maskele" (`mask`); sunucunun
 * önerdiği hassas kolonlar (`suggested_masks`) işaretlenir; sunucu uyarıları (`warnings`: COUNT tuzağı, `SELECT *`,
 * maskesiz hassas kolon) bantta; UNKNOWN (kural değerlendirilemedi) nötr; maliyet denetimi kapalı (`OFF`) nötr
 * "denetlenmedi".
 */
export default function DbQueryTestPanel({ result, plan = null, onColumnAction = null, maskedColumns = [], actionsDisabled = false }) {
  const t = useT()
  if (!result && !plan) return null
  const failedCall = result && result.status == null && (result.error || result.code)
  // Bekleme (429 RATE_LIMITED — 10 sn oturum beklemesi ya da saatlik deneme sınırı, K3): hata değil, bilgi tonu
  const waiting = failedCall && result.code === 'RATE_LIMITED'
  const tone = waiting ? 'info' : failedCall ? 'danger'
    : result?.status === 'FAIL' ? 'danger'
    : result?.status === 'BREACH' ? 'warning'
    : result?.status === 'OK' ? (result.slow ? 'warning' : 'success') : 'info'
  const unevaluated = !failedCall && result?.status === 'UNKNOWN'
  const Icon = waiting ? Clock : unevaluated ? CircleHelp
    : tone === 'success' ? CheckCircle2 : tone === 'danger' ? XCircle : tone === 'warning' ? AlertTriangle : Info
  const warnings = Array.isArray(result?.warnings) ? result.warnings.filter((w) => w && typeof w.code === 'string') : []
  const sensitive = new Set((Array.isArray(result?.suggested_masks) ? result.suggested_masks : []).map((c) => String(c).toLowerCase()))
  const masked = new Set((Array.isArray(maskedColumns) ? maskedColumns : []).map((c) => String(c).toLowerCase()))
  const violations = Array.isArray(result?.guard?.violations) ? result.guard.violations : []
  const columns = Array.isArray(result?.columns) ? result.columns : []
  const effectivePlan = plan || result?.plan || null
  const failKey = failureKey(result?.failure_reason)
  return (
    <section data-slot="dbq-test" data-status={waiting ? 'wait' : failedCall ? 'error' : (result?.status || 'none')} aria-live="polite"
      className="mt-3 flex min-w-0 flex-col gap-2.5 rounded-lg border bg-muted/30 px-3.5 py-3">
      {result && (
        <AlertBanner tone={tone} icon={Icon}
          title={waiting ? t('dbq.test.waitTitle') : failedCall ? t('dbq.test.callFailed') : t(checkStatusLabelKey(result))}>
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
              {unevaluated && <span data-slot="dbq-test-unevaluated" className="text-xs">{t('dbq.test.unevaluatedHint')}</span>}
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

      {warnings.length > 0 && (
        <div data-slot="dbq-test-warnings" className="flex min-w-0 flex-col gap-1.5">
          {warnings.map((w, i) => (
            <AlertBanner key={`${w.code}-${i}`} tone="warning" className="mb-0"
              title={WARNING_CODES.includes(w.code) ? t(`dbq.warn.${w.code}.title`) : t('dbq.warn.other.title')}>
              <span data-slot="dbq-test-warning" data-code={w.code} className="flex flex-col gap-0.5 [overflow-wrap:anywhere]">
                <span>{WARNING_CODES.includes(w.code) ? t(`dbq.warn.${w.code}.body`) : (w.message || t('dbq.warn.other.body'))}</span>
                {Array.isArray(w.columns) && w.columns.length > 0 && (
                  <span className="font-mono text-xs">{w.columns.join(', ')}</span>
                )}
              </span>
            </AlertBanner>
          ))}
        </div>
      )}

      {columns.length > 0 && (
        <div data-slot="dbq-test-columns" className="flex min-w-0 flex-col gap-1.5">
          <span className="flex items-center gap-1.5 text-xs font-semibold text-muted-foreground">
            <Columns3 aria-hidden="true" className="size-3.5" />{t('dbq.test.columns', columns.length)}
          </span>
          {onColumnAction && !actionsDisabled && <span className="text-xs text-muted-foreground">{t('dbq.test.columnsHint')}</span>}
          <ul className="m-0 flex list-none flex-wrap gap-1.5 p-0">
            {columns.map((c, i) => (
              <li key={`${c.name}-${i}`}>
                <ColumnChip column={c} t={t} sensitive={sensitive.has(String(c.name).toLowerCase())}
                  masked={masked.has(String(c.name).toLowerCase())}
                  onAction={onColumnAction && !actionsDisabled ? (a) => onColumnAction(a, c) : null} />
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

      {effectivePlan?.rateLimited && (
        // Maliyet denetimi bekleme (429): "Maliyet okunamadı" DEĞİL — bilgi tonu, sunucunun metni (DÜŞÜK 6, K3)
        <div data-slot="dbq-test-plan" data-verdict="WAIT">
          <AlertBanner tone="info" icon={Clock} title={t('dbq.test.planWaitTitle')}>
            <span className="[overflow-wrap:anywhere]">{effectivePlan.message}</span>
          </AlertBanner>
        </div>
      )}
      {effectivePlan && !effectivePlan.rateLimited && (
        <div data-slot="dbq-test-plan" data-verdict={effectivePlan.verdict === 'OFF' ? 'OFF'
          : effectivePlan.supported === false ? 'UNSUPPORTED' : (effectivePlan.verdict || 'UNKNOWN')}>
          <AlertBanner tone={planTone(effectivePlan) === 'neutral' ? 'info' : planTone(effectivePlan)} icon={Gauge}
            title={t(planTitleKey(effectivePlan))}>
            <span className={cn('flex flex-col gap-0.5 [overflow-wrap:anywhere]')}>
              {effectivePlan.supported !== false && effectivePlan.verdict !== 'OFF' && (effectivePlan.cost != null || effectivePlan.rows != null) && (
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

/** Kolon içeriği: ad + tür (+ hassas / maskeli işareti). */
function ColumnLabel({ column: c, t, sensitive, masked }) {
  return (
    <>
      <span className="font-mono font-semibold [overflow-wrap:anywhere]">{c.name}</span>
      {c.type && <span className="text-muted-foreground">{c.type}</span>}
      {sensitive && !masked && (
        <span data-slot="dbq-col-sensitive" className="inline-flex items-center gap-0.5 text-amber-700 dark:text-amber-300">
          <ShieldAlert aria-hidden="true" className="size-3" />{t('dbq.test.sensitive')}
        </span>
      )}
      {masked && <span data-slot="dbq-col-masked" className="text-muted-foreground">{t('dbq.test.masked')}</span>}
    </>
  )
}

/**
 * Kolon rozeti — eylem verilmişse menülü düğme (kural ekle / maskele; türe göre önerilen önce), yoksa düz rozet. SATIR
 * VERİSİ YOK: yalnız ad ve tür.
 */
function ColumnChip({ column: c, t, sensitive, masked, onAction }) {
  if (!onAction) {
    return (
      <Badge variant="outline" data-slot="dbq-col" className="h-auto max-w-full gap-1 overflow-visible rounded-md px-2 py-0.5 text-[11px] whitespace-normal">
        <ColumnLabel column={c} t={t} sensitive={sensitive} masked={masked} />
      </Badge>
    )
  }
  const kinds = ruleOptionsFor(c)
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button type="button" variant="outline" size="sm" data-slot="dbq-col" data-kind={columnKind(c.type)}
          aria-label={t('dbq.test.columnMenu', c.name)}
          className="h-auto min-h-7 max-w-full gap-1 rounded-md px-2 py-0.5 text-[11px] font-normal whitespace-normal pointer-coarse:min-h-10">
          <ColumnLabel column={c} t={t} sensitive={sensitive} masked={masked} />
          <ChevronDown aria-hidden="true" className="size-3 opacity-70" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="z-(--z-menu) max-w-[min(20rem,90vw)]">
        <DropdownMenuLabel className="font-mono text-xs [overflow-wrap:anywhere]">{c.name}</DropdownMenuLabel>
        {kinds.map((k) => (
          <DropdownMenuItem key={k} onSelect={() => onAction(`rule:${k}`)} className="pointer-coarse:min-h-10" data-action={`rule:${k}`}>
            <ListPlus aria-hidden="true" />{t(`dbq.test.addRule.${k}`)}
          </DropdownMenuItem>
        ))}
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => onAction('mask')} disabled={masked} className="pointer-coarse:min-h-10" data-action="mask">
          <EyeOff aria-hidden="true" />{masked ? t('dbq.test.alreadyMasked') : t('dbq.test.maskColumn')}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

function planTitleKey(plan) {
  if (plan.verdict === 'OFF') return 'dbq.test.planOff'
  if (plan.supported === false) return 'dbq.test.planUnsupported'
  if (plan.verdict === 'OK') return 'dbq.test.planOk'
  if (plan.verdict === 'WARN') return 'dbq.test.planWarn'
  if (plan.verdict === 'BLOCK') return 'dbq.test.planBlock'
  return 'dbq.test.planUnknown'
}
