import { useId } from 'react'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import { LEVELS, countsOf, parseSample } from './thresholdModel.js'
import { LEVEL_DOT, daysText } from './ThresholdScale.jsx'

/**
 * ETKİ ÖNİZLEMESİ — "bu değerlerle bugün kaç alan hangi seviyede olur, şu ankine göre fark ne?"
 * Kaynak `GET /admin/thresholds/preview` (ThresholdPreviewService): kapsam satırın kapsamıyla aynı,
 * `current` = bugünkü eşiklerle, `proposed` = girilen değerlerle sayım; `samples` = seviye başına en çok
 * 8 örnek alan ("alan (Ng)"). Hiçbir şey kalıcı yazılmaz.
 *
 * @param preview { loading, data, error } | null
 */
export default function ThresholdImpact({ preview, className }) {
  const t = useT()
  const titleId = useId()
  if (!preview) return null
  const d = preview.data
  const box = cn('flex flex-col gap-3 rounded-lg border bg-muted/30 p-3 sm:p-4', className)

  if (preview.error && !d) {
    return <AlertBanner tone="warning" title={t('thr.previewError')} className="mb-0">{String(preview.error)}</AlertBanner>
  }
  if (!d) {
    return (
      <section data-slot="threshold-preview" data-state="loading" aria-busy="true" className={box}>
        <div role="status" className="flex items-center gap-2 text-sm text-muted-foreground">
          <Spinner size={12} inline decorative /> {t('thr.previewLoading')}
        </div>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {LEVELS.map(lv => <Skeleton key={lv} className="h-16 rounded-md" />)}
        </div>
      </section>
    )
  }

  const cur = countsOf(d.current), next = countsOf(d.proposed)
  const same = LEVELS.every(lv => cur[lv] === next[lv])
  const sentence = same
    ? t('thr.impactSame', next.critical, next.high, next.warning)
    : t('thr.impactSentence', next.critical, next.high, next.warning, cur.critical, cur.high, cur.warning)
  const samples = d.samples || {}

  return (
    <section data-slot="threshold-preview" data-testid="threshold-preview" aria-busy={preview.loading || undefined}
      aria-labelledby={titleId} className={box}>
      <div className="flex flex-col gap-1 sm:flex-row sm:items-baseline sm:justify-between sm:gap-3">
        <h3 id={titleId} className="flex items-center gap-2 text-sm font-semibold">
          {t('thr.impactTitle')}
          {preview.loading && <Spinner size={11} inline decorative />}
        </h3>
        <span className="text-xs text-muted-foreground">
          {t('thr.impactScope', d.scope_total ?? 0)}
          {d.unchecked > 0 && <> · {t('thr.previewUnchecked', d.unchecked)}</>}
        </span>
      </div>
      <p data-slot="threshold-impact-sentence" aria-live="polite" className="text-sm font-medium">{sentence}</p>

      <ul className="grid list-none grid-cols-2 gap-2 sm:grid-cols-4">
        {LEVELS.map(lv => {
          const delta = next[lv] - cur[lv]
          return (
            <li key={lv} data-slot="threshold-impact-level" data-level={lv} data-count={next[lv]} data-was={cur[lv]}
              className="flex min-w-0 flex-col gap-1 rounded-md border bg-card px-3 py-2">
              <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
                <span aria-hidden="true" className={cn('size-2 shrink-0 rounded-full', LEVEL_DOT[lv])} />
                {t(`thr.lv.${lv}`)}
              </span>
              <span className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
                <span className="text-xl font-semibold tabular-nums">{next[lv]}</span>
                {delta !== 0 && (
                  <Badge variant="outline" data-slot="threshold-delta"
                    className={cn('px-1.5 tabular-nums', lv !== 'ok' && delta > 0 ? 'text-destructive' : 'text-muted-foreground')}>
                    {delta > 0 ? `+${delta}` : delta}
                  </Badge>
                )}
              </span>
              <span className="text-xs text-muted-foreground tabular-nums">{t('thr.was', cur[lv])}</span>
            </li>
          )
        })}
      </ul>

      {['CRITICAL', 'HIGH', 'WARNING'].some(lv => (samples[lv] || []).length > 0) && (
        <div className="flex flex-col gap-2">
          <span className="text-xs font-semibold text-muted-foreground">{t('thr.samplesTitle')}</span>
          {['CRITICAL', 'HIGH', 'WARNING'].map(LV => {
            const list = samples[LV] || []
            if (list.length === 0) return null
            const lv = LV.toLowerCase()
            return (
              <div key={LV} data-slot="threshold-samples" data-level={lv} className="flex min-w-0 flex-wrap items-center gap-1.5">
                <span className="inline-flex items-center gap-1.5 text-xs font-medium">
                  <span aria-hidden="true" className={cn('size-2 shrink-0 rounded-full', LEVEL_DOT[lv])} />
                  {t(`thr.lv.${lv}`)}:
                </span>
                {list.map(s => {
                  const p = parseSample(s)
                  return (
                    <Badge key={s} variant="outline" className="max-w-full gap-1 font-normal whitespace-normal">
                      <span className="font-mono break-all">{p.domain}</span>
                      {p.days != null && <span className="text-muted-foreground tabular-nums">· {p.days < 0 ? t('thr.expired') : daysText(t, p.days)}</span>}
                    </Badge>
                  )
                })}
              </div>
            )
          })}
        </div>
      )}
      <p className="text-xs text-muted-foreground">{t('thr.previewHint2')}</p>
    </section>
  )
}
