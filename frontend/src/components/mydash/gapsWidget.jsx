import { useEffect } from 'react'
import { ClipboardList } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { openGapsDialog, requestGapsRefresh } from '../gaps/gapsBus.js'
import { useGapsSummary } from '../gaps/useGapsSummary.js'
import WidgetCard from './WidgetCard.jsx'
import { Empty, Metric, Tile } from './widgetParts.jsx'
import { Button } from '@/components/shadcn/button'

/** Kutuda gösterilen kategoriler (sıra = eksikler penceresinin sırası; gapsModel.GAP_CATEGORIES ile aynı — testte denetlenir). */
export const GAPS_WIDGET_CATEGORIES = Object.freeze(['profile', 'team', 'monitors', 'inventory', 'tasks'])

/**
 * Eksikler kutusu ("Eksiklerim", 2026-11-15, D paketi) — giriş sonrası eksikler penceresinin özeti. Ağa kendisi çıkmaz: özeti
 * App'teki eksikler penceresi yayınlar; bu sekmede henüz okunmadıysa kutu bir kez okuma ister (pencere açılmaz). Renk:
 * her şey okundu ve eksik yok → yeşil; yüksek önemli eksik → kırmızı; diğer eksik → turuncu; bir kaynak okunamadı ya da
 * henüz okunmadı → gri (bilinmeyen asla yeşil değil). Tıklama eksikler penceresini açar.
 */
export function GapsWidget({ frame }) {
  const t = useT()
  const summary = useGapsSummary()
  // Bu sekmede özet yoksa bir kez okuma iste (düzenleme kipinde ve TV'de değil — duvar ekranı kişisel okuma tetiklemesin)
  useEffect(() => {
    if (!summary && !frame.edit && !frame.tv) requestGapsRefresh()
  }, []) // eslint-disable-line react-hooks/exhaustive-deps
  const ready = summary?.status === 'ready'
  const total = ready ? summary.total : null
  const high = ready ? summary.high || 0 : 0
  const tone = !ready ? 'muted' : summary.complete ? 'success' : total > 0 ? (high > 0 ? 'danger' : 'warning') : 'muted'
  const toneLabel = !ready ? t('mydash.gaps.notChecked')
    : summary.complete ? t('mydash.gaps.complete')
    : total > 0 ? t(total === 1 ? 'mydash.gaps.count.one' : 'mydash.gaps.count', total)
    : t('mydash.gaps.unknown')
  const open = frame.edit || frame.tv ? undefined : openGapsDialog
  return (
    <WidgetCard frame={frame} Icon={ClipboardList} title={t('mydash.w.my_gaps')} loading={summary?.status === 'loading' && !summary?.at}
      error={null} at={summary?.at ?? null} tone={tone} toneLabel={toneLabel}
      stat={ready ? { value: total, label: t('mydash.gaps.label'), sub: summary.unknown ? t('mydash.gaps.partial') : null, onClick: open, hint: t('mydash.gaps.openHint') } : null}>
      {!summary ? (
        <div className="flex min-w-0 flex-col gap-2" data-slot="mydash-gaps-idle">
          <Empty tone="muted" text={t('mydash.gaps.notCheckedBody')} tv={frame.tv} />
          {!frame.edit && !frame.tv && (
            <Button type="button" variant="outline" size="sm" className="self-start pointer-coarse:min-h-10" onClick={requestGapsRefresh}
              data-slot="mydash-gaps-check">{t('mydash.gaps.check')}</Button>
          )}
        </div>
      ) : !ready ? null : (
        <div className="flex min-w-0 flex-col gap-3" data-slot="mydash-gaps" data-total={total}>
          <Metric value={total} label={t('mydash.gaps.label')} slot="mydash-gaps-total" tv={frame.tv}
            tone={total > 0 ? (high > 0 ? 'danger' : 'warning') : summary.complete ? 'success' : 'neutral'} onClick={open} hint={t('mydash.gaps.openHint')} />
          {total > 0 && (
            <div className="grid min-w-0 grid-cols-2 gap-2 @md:grid-cols-3" data-slot="mydash-gaps-categories">
              {GAPS_WIDGET_CATEGORIES.filter((c) => (summary.byCategory?.[c] ?? 0) > 0).map((c) => (
                <Tile key={c} value={summary.byCategory[c]} label={t(`gaps.cat.${c}`)} tone="warning" tv={frame.tv}
                  slot={`mydash-gaps-${c}`} onClick={open} hint={t('mydash.gaps.openHint')} />
              ))}
            </div>
          )}
          {summary.complete && <Empty text={t('mydash.gaps.completeBody')} tv={frame.tv} />}
          {summary.unknown && <Empty tone="muted" text={t('mydash.gaps.partialBody')} tv={frame.tv} />}
        </div>
      )}
    </WidgetCard>
  )
}

export const GAPS_WIDGETS = {
  my_gaps: { Icon: ClipboardList, Component: GapsWidget },
}
