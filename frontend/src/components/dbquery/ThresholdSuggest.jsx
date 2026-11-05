import { useEffect, useRef, useState } from 'react'
import { Check, RotateCw, Sparkles, X } from 'lucide-react'
import { api } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import AlertBanner from '../ui/AlertBanner.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { ageText, formatNumber, isFreshnessKind, ruleSummary, thresholdSet } from './ruleModel.js'
import {
  SUGGEST_DAYS, SUGGEST_KINDS, applySuggestion, seriesAppliesTo, seriesSamples, suggestDirection, suggestThresholds, trialValueFor,
} from './thresholdSuggest.js'

/** Dayanak cümlesinin değerleri — yöne göre (dizinler ardışık: gün, nokta sayısı, en düşük, …, en yüksek). */
function basisArgs(st, direction, fmt, n) {
  if (direction === 'lower') return [SUGGEST_DAYS, n, fmt(st.min), fmt(st.p5), fmt(st.max)]
  if (direction === 'band') return [SUGGEST_DAYS, n, fmt(st.min), fmt(st.p5), fmt(st.p95), fmt(st.max)]
  return [SUGGEST_DAYS, n, fmt(st.min), fmt(st.p95), fmt(st.p99), fmt(st.max)]
}

/**
 * "Eşik öner" (2026-11-05, dbq2 B3) — kural kartının eşik bölümünde. Öneri İSTEMCİDE hesaplanır ve UYGULANMADAN
 * gösterilir: insan dilinde özet ("… 15'ten büyükse UYARI, 20'den büyükse KRİTİK"), dayanağı (kaç ölçüm, p95 / p99) ve
 * Uygula / Vazgeç. Kaynak: kayıtlı izlemede son 7 günün değer serisi (yalnız seriyi belirleyen kural için, tanımı ve sorgu
 * değişmemişse) ya da son "Dene"nin değeri; ikisi de varsa seçilir. Veri yetersizse söylenir — öneri uydurulmaz.
 *
 * <p>Test kancaları: düğme `dbq-suggest-open`, panel `dbq-suggest` (+ `data-state` none|loading|error|few|floor|operator|ok),
 * kaynak seçimi `dbq-suggest-source`, öneri özeti `dbq-suggest-summary`, dayanak `dbq-suggest-basis`, uygula
 * `dbq-suggest-apply`, vazgeç `dbq-suggest-cancel`, uygulandı notu `dbq-suggest-applied`.
 */
export default function ThresholdSuggest({ rule, context, disabled = false, onApply }) {
  const t = useT()
  const { lang } = useLanguage()
  const [open, setOpen] = useState(false)
  const [source, setSource] = useState(null)
  const [series, setSeries] = useState({ state: 'idle' })
  const [applied, setApplied] = useState(null)
  const seq = useRef(0)
  const btnRef = useRef(null)

  const saved = context?.saved || null
  const seriesOk = seriesAppliesTo(rule, saved, context?.sqlText)
  const trial = trialValueFor(rule, context?.testResult)
  const sources = [seriesOk && 'series', trial != null && 'trial'].filter(Boolean)
  const active = sources.includes(source) ? source : sources[0] || null

  // Uygulandı notu, eşikler başka bir yoldan değişince kalkar (eski not yanlış bilgi olurdu)
  const thresholdsKey = JSON.stringify([rule?.warning ?? null, rule?.critical ?? null])
  useEffect(() => { if (applied != null && applied !== thresholdsKey) setApplied(null) }, [applied, thresholdsKey])

  const loadSeries = async () => {
    if (!saved?.id || typeof api.monitoring?.getDbQuerySeries !== 'function') return
    const my = ++seq.current
    setSeries({ state: 'loading' })
    try {
      const res = await api.monitoring.getDbQuerySeries(saved.id, { days: SUGGEST_DAYS, metric: 'value' })
      if (my !== seq.current) return
      setSeries(res?.success && res.data && typeof res.data === 'object' ? { state: 'ok', data: res.data } : { state: 'error', message: res?.error || null })
    } catch (e) {
      if (my === seq.current) setSeries({ state: 'error', message: e?.message || null })
    }
  }
  useEffect(() => {
    if (open && active === 'series' && series.state === 'idle') loadSeries()
  }, [open, active]) // eslint-disable-line react-hooks/exhaustive-deps
  // Kayıtlı izleme değişirse (başka izleme düzenleniyor) eski seri kullanılmaz
  useEffect(() => { seq.current++; setSeries({ state: 'idle' }) }, [saved?.id])

  if (!rule || !SUGGEST_KINDS.includes(rule.kind) || disabled) return null

  const close = () => { setOpen(false); requestAnimationFrame?.(() => btnRef.current?.focus?.()) }
  const fmt = (v) => (isFreshnessKind(rule.kind) ? ageText(v, t, lang) : formatNumber(v, lang))
  let result = null
  let state = 'none'
  if (!suggestDirection(rule)) state = 'operator'
  else if (active === 'trial') {
    result = suggestThresholds(rule, [trial], { minSamples: 1 })
    state = result.ok ? 'ok' : result.reason === 'floor' ? 'floor' : 'none'
  } else if (active === 'series') {
    if (series.state === 'loading' || series.state === 'idle') state = 'loading'
    else if (series.state === 'error') state = 'error'
    else {
      result = suggestThresholds(rule, seriesSamples(series.data, suggestDirection(rule)))
      state = result.ok ? 'ok' : result.reason === 'too_few' || result.reason === 'no_data' ? 'few' : result.reason === 'floor' ? 'floor' : 'none'
    }
  }
  const proposed = result?.ok ? applySuggestion(rule, result) : null
  const replacing = thresholdSet(rule, 'warning') || thresholdSet(rule, 'critical')

  return (
    <div className="mt-2 flex min-w-0 flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <Button ref={btnRef} type="button" variant="outline" size="sm" data-slot="dbq-suggest-open" aria-expanded={open}
          className="pointer-coarse:h-10" onClick={() => { setOpen((v) => !v); setApplied(null) }}>
          <Sparkles aria-hidden="true" />{t('dbq.suggest.open')}
        </Button>
        {applied != null && (
          <span data-slot="dbq-suggest-applied" role="status" className="inline-flex items-center gap-1 text-xs text-muted-foreground">
            <Check aria-hidden="true" className="size-3.5 text-success" />{t('dbq.suggest.applied')}
          </span>
        )}
      </div>
      {open && (
        <section data-slot="dbq-suggest" data-state={state} aria-live="polite" aria-label={t('dbq.suggest.title')}
          className="flex min-w-0 flex-col gap-2.5 rounded-lg border bg-muted/30 px-3 py-3">
          <div className="flex min-w-0 flex-col gap-0.5">
            <h4 className="m-0 inline-flex items-center gap-1.5 text-sm font-semibold"><Sparkles aria-hidden="true" className="size-4" />{t('dbq.suggest.title')}</h4>
            <p className="m-0 text-xs text-muted-foreground">{t('dbq.suggest.hint')}</p>
          </div>
          {sources.length > 1 && state !== 'operator' && (
            <div data-slot="dbq-suggest-source">
              <SegmentedControl value={active} onChange={setSource} ariaLabel={t('dbq.suggest.sourceLabel')}
                itemClassName="pointer-coarse:h-10"
                options={[{ value: 'series', label: t('dbq.suggest.src.series', SUGGEST_DAYS) }, { value: 'trial', label: t('dbq.suggest.src.trial') }]} />
            </div>
          )}
          {state === 'operator' && <p className="m-0 text-sm">{t('dbq.suggest.operator')}</p>}
          {state === 'none' && (
            <p data-slot="dbq-suggest-none" className="m-0 text-sm text-muted-foreground">
              {saved?.id != null && !seriesOk ? t('dbq.suggest.noneSaved') : t('dbq.suggest.none')}
            </p>
          )}
          {state === 'floor' && <p data-slot="dbq-suggest-floor" className="m-0 text-sm text-muted-foreground">{t('dbq.suggest.floor')}</p>}
          {state === 'loading' && (
            <p className="m-0 inline-flex items-center gap-2 text-sm text-muted-foreground"><Spinner size={14} inline decorative />{t('dbq.suggest.loading', SUGGEST_DAYS)}</p>
          )}
          {state === 'error' && (
            <AlertBanner tone="danger" className="mb-0" title={t('dbq.suggest.unreadTitle')}>
              <span className="flex flex-col items-start gap-1.5">
                <span>{t('dbq.suggest.unread')}</span>
                <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={loadSeries}>
                  <RotateCw aria-hidden="true" />{t('dbq.suggest.retry')}
                </Button>
              </span>
            </AlertBanner>
          )}
          {state === 'few' && (
            <p data-slot="dbq-suggest-few" className="m-0 text-sm text-muted-foreground">
              {result?.n ? t('dbq.suggest.few', result.n, result.min) : t('dbq.suggest.noData', SUGGEST_DAYS)}
            </p>
          )}
          {state === 'ok' && proposed && (
            <>
              <p data-slot="dbq-suggest-summary" className="m-0 rounded-md border bg-background px-2.5 py-2 text-sm font-medium [overflow-wrap:anywhere]">
                {ruleSummary(proposed, t, lang)}
              </p>
              <p data-slot="dbq-suggest-basis" className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">
                {active === 'trial'
                  ? t('dbq.suggest.basisTrial', fmt(trial))
                  : t(`dbq.suggest.basisSeries.${result.direction}`, ...basisArgs(result.stats, result.direction, fmt, formatNumber(result.stats.n, lang)))}
              </p>
              {result.critical == null && <p className="m-0 text-xs text-muted-foreground">{t('dbq.suggest.noCritical')}</p>}
              {replacing && <p className="m-0 text-xs text-amber-700 dark:text-amber-300">{t('dbq.suggest.replaces')}</p>}
            </>
          )}
          <div className="flex flex-wrap items-center gap-2">
            {state === 'ok' && proposed && (
              <Button type="button" size="sm" data-slot="dbq-suggest-apply" className="pointer-coarse:h-10"
                onClick={() => { onApply?.(proposed); setApplied(JSON.stringify([proposed.warning ?? null, proposed.critical ?? null])); close() }}>
                <Check aria-hidden="true" />{t('dbq.suggest.apply')}
              </Button>
            )}
            <Button type="button" variant="outline" size="sm" data-slot="dbq-suggest-cancel" className="pointer-coarse:h-10" onClick={close}>
              <X aria-hidden="true" />{t(state === 'ok' ? 'dbq.suggest.cancel' : 'dbq.suggest.close')}
            </Button>
          </div>
        </section>
      )}
    </div>
  )
}
