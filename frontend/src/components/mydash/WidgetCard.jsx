import { ArrowLeft, ArrowRight, ArrowUpRight, GripVertical, TriangleAlert, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { agoText } from '../../utils/relativeTime.js'
import ToneBadge from '../admin/ToneBadge.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'
import { SIZES } from './myDashModel.js'

/** Boyut → ızgara açıklığı (kap sorguları; sütun eşikleri myDashModel.columnsFor ile aynı). */
export const SPAN_CLASS = {
  sm: '',
  md: '@2xl:col-span-2',
  lg: 'col-span-full',
}

const HIT = 'pointer-coarse:size-10'

/**
 * Panom kutusu çerçevesi: başlık (ikon, ad, durum rozeti, "Sayfayı aç"), düzenleme kipinde araç çubuğu (sürükle tutamacı,
 * önceki/sonraki, boyut, kaldır), gövde ve alt not (son güncelleme; hata varsa son verinin gösterildiği). Durum yalnız
 * rozet + `data-tone` ile — kartta renkli sol şerit YOK (ürün kuralı).
 *
 * `frame` (sayfadan): { widget, edit, tv, index, count, onMove, onResize, onRemove, dnd } — kutu bileşeni kendi
 * başlık bilgisini ekleyip buraya yayar.
 */
export default function WidgetCard({
  frame, Icon, title, description, tone = 'muted', toneLabel, tab, loading, error, at, children, className,
}) {
  const t = useT()
  const { widget, edit, tv, canPrev, canNext, onMove, onResize, onRemove, dnd } = frame
  const showBody = !(loading && !at)
  return (
    <div data-slot="mydash-widget" data-type={widget.type} data-size={widget.size} data-tone={tone}
      className={cn('min-w-0', tv ? null : SPAN_CLASS[widget.size], dnd?.over ? 'rounded-xl ring-2 ring-primary ring-offset-2 ring-offset-background' : null, className)}
      style={tv ? { gridColumn: `span ${frame.span || 1} / span ${frame.span || 1}` } : undefined}
      draggable={edit && !!dnd} onDragStart={dnd?.onDragStart} onDragEnd={dnd?.onDragEnd}
      onDragOver={dnd?.onDragOver} onDragLeave={dnd?.onDragLeave} onDrop={dnd?.onDrop}>
      <Card className={cn('h-full gap-3 py-4', dnd?.dragging && 'opacity-50')}>
        <CardHeader className="gap-1 px-4">
          <CardTitle className={cn('flex min-w-0 flex-wrap items-center gap-2 text-base', tv && 'text-xl')}>
            {Icon && (
              <span className={cn('grid size-8 shrink-0 place-items-center rounded-lg bg-muted text-foreground', tv && 'size-10')} aria-hidden="true">
                <Icon className={tv ? 'size-5' : 'size-4'} />
              </span>
            )}
            <span className="min-w-0 [overflow-wrap:anywhere]">{title}</span>
            {toneLabel && <ToneBadge tone={tone} data-slot="mydash-widget-tone" className={tv ? 'text-sm' : null}>{toneLabel}</ToneBadge>}
          </CardTitle>
          {description && !tv && <CardDescription className="[overflow-wrap:anywhere]">{description}</CardDescription>}
          {tab && !tv && !edit && (
            <CardAction>
              <Button type="button" variant="ghost" size="icon" className={cn('size-8', HIT)} data-slot="mydash-open"
                aria-label={t('mydash.openPage', title)} title={t('mydash.openPage', title)} onClick={() => navigateTo(tab)}>
                <ArrowUpRight aria-hidden="true" />
              </Button>
            </CardAction>
          )}
        </CardHeader>

        {edit && (
          <div className="flex flex-wrap items-center gap-1.5 border-y border-border bg-muted/40 px-3 py-1.5" data-slot="mydash-widget-tools">
            <span className={cn('grid size-8 cursor-grab place-items-center rounded-md text-muted-foreground active:cursor-grabbing', HIT, !dnd && 'hidden')}
              title={t('mydash.dragHint')} aria-hidden="true">
              <GripVertical className="size-4" />
            </span>
            <Button type="button" variant="outline" size="icon" className={cn('size-8', HIT)} disabled={!canPrev}
              aria-label={t('mydash.movePrev', title)} title={t('mydash.movePrev', title)} onClick={() => onMove(-1)}>
              <ArrowLeft aria-hidden="true" />
            </Button>
            <Button type="button" variant="outline" size="icon" className={cn('size-8', HIT)} disabled={!canNext}
              aria-label={t('mydash.moveNext', title)} title={t('mydash.moveNext', title)} onClick={() => onMove(1)}>
              <ArrowRight aria-hidden="true" />
            </Button>
            <ToggleGroup type="single" variant="outline" size="sm" value={widget.size} aria-label={t('mydash.size', title)}
              onValueChange={(v) => { if (v) onResize(v) }} className="ml-auto">
              {SIZES.map((s) => (
                <ToggleGroupItem key={s} value={s} className="h-8 min-w-9 px-2 text-xs font-semibold pointer-coarse:h-10 pointer-coarse:min-w-10"
                  aria-label={t('mydash.size.' + s)} title={t('mydash.size.' + s)}>
                  {t('mydash.sizeShort.' + s)}
                </ToggleGroupItem>
              ))}
            </ToggleGroup>
            <Button type="button" variant="ghost" size="icon" className={cn('size-8 text-destructive hover:text-destructive', HIT)}
              aria-label={t('mydash.remove', title)} title={t('mydash.remove', title)} onClick={onRemove} data-slot="mydash-remove">
              <X aria-hidden="true" />
            </Button>
          </div>
        )}

        <CardContent className={cn('@container flex min-w-0 flex-1 flex-col gap-3 px-4', tv && 'text-lg')}>
          {showBody ? children : <LoadingBlock label={t('mydash.loading')} />}
        </CardContent>

        {(error || at) && (
          <div className={cn('flex flex-wrap items-center gap-1.5 px-4 text-xs text-muted-foreground', tv && 'text-sm')} data-slot="mydash-widget-foot">
            {error ? (
              <span className="inline-flex items-center gap-1 text-amber-700 dark:text-amber-300" role="status" data-slot="mydash-widget-error">
                <TriangleAlert className="size-3.5 shrink-0" aria-hidden="true" />
                <span className="[overflow-wrap:anywhere]">{at ? t('mydash.staleData') : error}</span>
              </span>
            ) : (
              <span>{t('mydash.updated', agoText(at, t))}</span>
            )}
          </div>
        )}
      </Card>
    </div>
  )
}
