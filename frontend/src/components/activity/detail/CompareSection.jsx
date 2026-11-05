import { useMemo } from 'react'
import { ArrowLeftRight, ArrowRight, History } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { relTimeOrRaw } from '../../../utils/relativeTime.js'
import ToneBadge from '../../admin/ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { HealthBadge, LinkButton, SectionCard } from './detailParts.jsx'
import { activityCell, compareModel, formatDelta, formatIstanbul } from './activityDetailModel.js'

/** Geçiş cümlesinin tonu: sorun başladı kırmızı, sürüyor amber, düzeldi yeşil, sakin nötr. */
const TRANSITION_TONE = { started: 'danger', continues: 'warning', recovered: 'success', stable: 'muted' }

/**
 * KIYAS (A-3): önceki ↔ bu ↔ sonraki kontrol — durum, yanıt süresi farkı, HTTP kodu ve hata nedeni değişimi. Üstte geçiş
 * cümlesi ("sorun bu kontrolde başladı" / "sürüyor" / "düzeldi"; bilinmeyen durumda cümle YOK — tahmin edilmez). Her komşu
 * hücre aktivitesine (sunucu `activity_id` verdiyse) ya da izlemenin kontrol geçmişine gider. Hücreler KAP genişliğine göre
 * yan yana (@2xl) ya da alt alta. Test kancaları: `act-dt-compare`, hücre `act-dt-cmp-cell` (`data-key` prev|cur|next,
 * `data-health`), geçiş `act-dt-transition` (`data-kind`).
 */
export default function CompareSection({ detail, type, monitor, locale, onOpenActivity }) {
  const t = useT()
  const a = detail.data
  const model = useMemo(() => compareModel({
    type, current: detail.check?.row || null,
    currentCell: detail.check?.row ? null : activityCell(type, a, monitor, t),
    previous: detail.previous, next: detail.next, monitor, t,
  }), [type, detail, a, monitor, t])
  const history = detail.links?.history || null

  const cellTitle = { prev: t('act.dt.cmp.prev'), cur: t('act.dt.cmp.cur'), next: t('act.dt.cmp.next') }
  const missing = { prev: t('act.dt.cmp.noPrev'), next: t('act.dt.cmp.noNext') }

  return (
    <SectionCard slot="act-dt-compare" icon={ArrowLeftRight} title={t('act.dt.cmp.title')} description={t('act.dt.cmp.desc')}>
      {(model.transition || model.after) && (
        <div className="flex min-w-0 flex-wrap gap-1.5">
          {model.transition && (
            <ToneBadge tone={TRANSITION_TONE[model.transition]} data-slot="act-dt-transition" data-kind={model.transition}
              className="h-auto rounded-md whitespace-normal">{t('act.dt.tr.' + model.transition)}</ToneBadge>
          )}
          {model.after && model.after !== 'stable' && (
            <ToneBadge tone={TRANSITION_TONE[model.after]} data-slot="act-dt-transition" data-kind={'after-' + model.after}
              className="h-auto rounded-md whitespace-normal">{t('act.dt.trAfter.' + model.after)}</ToneBadge>
          )}
        </div>
      )}
      <div className="@container min-w-0">
        <ol className="m-0 grid list-none grid-cols-1 gap-2.5 p-0 @2xl:grid-cols-3">
          {model.cells.map((c) => (
            <li key={c.key} data-slot="act-dt-cmp-cell" data-key={c.key} data-health={c.row ? c.health : undefined}
              aria-current={c.key === 'cur' ? 'true' : undefined}
              className={cn('flex min-w-0 flex-col gap-2 rounded-lg border p-3',
                c.key === 'cur' ? 'border-primary/50 bg-primary/5' : 'bg-card')}>
              <h4 className="m-0 flex items-center gap-1.5 text-xs font-bold tracking-wide text-muted-foreground uppercase">
                {c.key === 'next' && <ArrowRight aria-hidden="true" className="size-3.5" />}
                {cellTitle[c.key]}
              </h4>
              {!c.row ? (
                <p className="m-0 text-sm text-muted-foreground">{missing[c.key]}</p>
              ) : (
                <>
                  <div className="flex min-w-0 flex-col gap-0.5 text-sm">
                    <time dateTime={c.at || undefined} className="tabular-nums [overflow-wrap:anywhere]">{c.at ? formatIstanbul(c.at, locale) : '—'}</time>
                    {c.at && <span className="text-xs text-muted-foreground">{relTimeOrRaw(c.at, t)}</span>}
                  </div>
                  <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                    <HealthBadge health={c.health} />
                    {c.reason && (
                      <ToneBadge tone={c.reason.tone} data-slot="act-dt-cmp-reason" data-code={c.reason.code}
                        className="h-auto max-w-full rounded-md whitespace-normal [overflow-wrap:anywhere]">{c.reason.short}</ToneBadge>
                    )}
                    {c.reasonChanged && <span data-slot="act-dt-cmp-changed" className="text-xs font-semibold text-primary">{t('act.dt.cmp.reasonChanged')}</span>}
                  </div>
                  <dl className="m-0 grid grid-cols-2 gap-x-3 gap-y-1 text-xs">
                    <dt className="text-muted-foreground">{t('act.dt.m.response')}</dt>
                    <dd className="m-0 min-w-0 tabular-nums">
                      {c.ms != null ? `${Math.round(c.ms)} ms` : '—'}
                      {c.delta != null && (
                        <span data-slot="act-dt-cmp-delta" className={cn('ml-1 font-semibold', c.delta > 0 ? 'text-amber-700 dark:text-amber-300' : 'text-muted-foreground')}>
                          ({formatDelta(c.delta)})
                        </span>
                      )}
                    </dd>
                    <dt className="text-muted-foreground">{t('act.dt.m.httpStatus')}</dt>
                    <dd className="m-0 min-w-0 tabular-nums">
                      {c.http != null ? `HTTP ${c.http}` : '—'}
                      {c.httpChanged && <span data-slot="act-dt-cmp-http-changed" className="ml-1 font-semibold text-primary">{t('act.dt.cmp.changed')}</span>}
                    </dd>
                  </dl>
                  {c.key !== 'cur' && (c.activityId != null ? (
                    <Button type="button" variant="outline" size="sm" className="mt-auto self-start pointer-coarse:h-10"
                      aria-label={t('a11y.rowAction', cellTitle[c.key], t('act.dt.cmp.openActivity'))}
                      onClick={() => onOpenActivity?.(c.activityId)}>
                      {t('act.dt.cmp.openActivity')}
                    </Button>
                  ) : history && (
                    <LinkButton target={history} slot="cmp-history" className="mt-auto self-start"
                      aria-label={t('a11y.rowAction', cellTitle[c.key], t('act.dt.cmp.openHistory'))}>
                      <History aria-hidden="true" />{t('act.dt.cmp.openHistory')}
                    </LinkButton>
                  ))}
                </>
              )}
            </li>
          ))}
        </ol>
      </div>
    </SectionCard>
  )
}
