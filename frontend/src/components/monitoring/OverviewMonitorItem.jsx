// İzleme Panosu — izleme satırı (shadcn Item): "Dikkat gerektirenler" listesi ve KPI özet pencereleri AYNI satırı kullanır.
// Satır ≥ 28rem ise eylemler sağda; daha dar satırda (kap sorgusu `@container/item` — pencere/kart genişliği ekrandan
// bağımsız) eylemler alt satıra iner ve satırı eşit paylaşır (40 px).
// Eylem adları tablo satırınınkinden FARKLI (`mo.att.*`) — aynı izleme hem listede hem burada göründüğünde erişilebilir
// adlar çakışmasın (tek bir "… izleme sayfasında aç" düğmesi kalır).
import { BellRing, ExternalLink } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { navigateTo } from '../../utils/navigate.js'
import TeamBadge from '../ui/TeamBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { Item, ItemActions, ItemContent, ItemDescription, ItemMedia, ItemTitle } from '@/components/shadcn/item'
import { cn } from '@/lib/utils'
import { TYPE_META } from './overviewMeta.js'
import { LastCheck, StatusBadge, TypeLabel } from './OverviewParts.jsx'

const MEDIA_TONE = {
  down: 'border-destructive/30 bg-destructive/10 text-destructive',
  stale: 'border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-300',
  up: 'border-success/30 bg-success/10 text-success',
}

/** Kısa eylemler: açık alarm varsa Alarm Geçmişi (türe + hedefe süzülü) · izleme sayfasında aç. */
export function ItemRowActions({ row, extra = null }) {
  const t = useT()
  const meta = TYPE_META[row.type]
  return (
    <>
      {extra}
      {Number(row.open_alerts) > 0 && (
        <Button type="button" variant="outline" size="sm" className="flex-1 @md/item:flex-none pointer-coarse:h-10"
          aria-label={t('mo.att.alerts', row.name)} title={t('mo.att.alerts', row.name)}
          onClick={() => navigateTo('alerthistory', { view: 'open', src: row.type, q: row.target })}>
          <BellRing aria-hidden="true" />{t('mo.row.alertsShort')}
        </Button>
      )}
      <Button type="button" variant="outline" size="sm" className="flex-1 @md/item:flex-none pointer-coarse:h-10"
        aria-label={t('mo.att.open', row.name)} title={t('mo.att.open', row.name)}
        onClick={() => navigateTo(meta?.tab || 'http', { q: row.target })}>
        <ExternalLink aria-hidden="true" />{t('mo.row.openShort')}
      </Button>
    </>
  )
}

/**
 * @param slot      test kancası (`mo-attention-item` / `mo-dlg-item`)
 * @param badges    ad yanındaki ek rozetler (alarm seviyesi, sahiplenildi…)
 * @param detail    neden satırı (son hata, beklenen aralık, açık kalma süresi…)
 * @param actions   eylem düğmeleri — verilmezse `ItemRowActions`
 */
export default function OverviewMonitorItem({ row, nowMs, slot, badges = null, detail = null, actions, showStatus = true, className }) {
  const meta = TYPE_META[row.type]
  const Icon = meta?.Icon
  return (
    <Item variant="outline" size="sm" data-slot={slot} data-status={row.status} data-type={row.type}
      className={cn('@container/item items-start gap-x-3 gap-y-2 bg-card px-3 sm:px-4', className)}>
      <ItemMedia variant="icon" className={cn('mt-0.5', MEDIA_TONE[row.status])}>
        {Icon && <Icon aria-hidden="true" />}
      </ItemMedia>
      <ItemContent className="min-w-0 basis-0 gap-1">
        <div className="flex min-w-0 flex-wrap items-center gap-1.5">
          <ItemTitle className="max-w-full min-w-0"><span className="truncate" title={row.name}>{row.name}</span></ItemTitle>
          {showStatus && <StatusBadge status={row.status} className="text-[11px]" />}
          {badges}
        </div>
        {row.target && row.target !== row.name && (
          <div className="truncate font-mono text-xs text-muted-foreground" title={row.target}>{row.target}</div>
        )}
        {detail && (
          <ItemDescription className="text-xs text-balance [overflow-wrap:anywhere]">{detail}</ItemDescription>
        )}
        <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
          <TypeLabel type={row.type} />
          {row.team_name && <TeamBadge teamId={row.team_id} teamName={row.team_name} size={12} />}
          <LastCheck row={row} nowMs={nowMs} />
        </div>
      </ItemContent>
      <ItemActions className="w-full gap-1.5 @md/item:w-auto">
        {actions ?? <ItemRowActions row={row} />}
      </ItemActions>
    </Item>
  )
}
