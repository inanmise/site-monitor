import { ArrowLeft, ArrowRight, ArrowUpRight, GripVertical, Palette, TriangleAlert, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { agoText } from '../../utils/relativeTime.js'
import ToneBadge from '../admin/ToneBadge.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Toggle } from '@/components/shadcn/toggle'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'
import { SIZES } from './myDashModel.js'
import { WIDGET_POLL_MS } from './useWidgetData.js'
import { LINK_RESET } from './widgetParts.jsx'

/**
 * Son başarılı veriden bu kadar süre sonra hâlâ hata varsa kutu "bayat" sayılır (inceleme 4, 2026-10-12): duvar ekranında
 * kesinti sırasında eski yeşil "sorun yok" süresiz kalıyordu ("bilinmeyen asla yeşil değildir"). Durum rozeti düşer, ton
 * muted olur ve gövde renksiz (gri) çizilir. Eşik ≈ iki yoklama aralığı, 15 sn PAYLA: `at` başarılı yanıtın GELDİĞİ an
 * damgalanır, başarısız yanıt genellikle daha hızlı döner — pay olmadan ikinci başarısız tur eşiğin hemen altında kalıp
 * yeşil üç dakikaya uzuyordu. Tek seferlik hata (ilk başarısız tur ≈ 60 sn) kutuyu oynatmaz.
 */
export const STALE_AFTER_MS = 2 * WIDGET_POLL_MS - 15_000

/** Boyut → ızgara açıklığı (kap sorguları; sütun eşikleri myDashModel.columnsFor ile aynı). */
export const SPAN_CLASS = {
  sm: '',
  md: '@2xl:col-span-2',
  lg: 'col-span-full',
}

const HIT = 'pointer-coarse:size-10'

/**
 * Renkli panel zemini (2026-10-13, Grafana "stat panel"): kutunun TÜM zemini durum renginde. Sabit palet — her temada
 * aynı anlam; metin kontrastı ≥ 4.5:1 (beyaz / kırmızı-700·800, yeşil-700·800, gök-700·800; koyu kahve / kehribar-400·500).
 * `muted` (bilinmiyor, bayat, okunamadı) zemin BOYAMAZ — bilinmeyen asla yeşil değildir.
 */
export const STAT_SURFACE = {
  danger:  'border-transparent bg-red-700 text-white dark:bg-red-800',
  warning: 'border-transparent bg-amber-400 text-amber-950 dark:bg-amber-500',
  success: 'border-transparent bg-green-700 text-white dark:bg-green-800',
  info:    'border-transparent bg-sky-700 text-white dark:bg-sky-800',
}

/** Renkli panel gövdesi: ortada büyük değer, altında etiket ve kısa ayrıntı; tıklanınca ilgili süzülmüş ekran. */
function StatBody({ stat, tv }) {
  const content = (
    <>
      <span data-slot="mydash-stat-value"
        className={cn('block font-bold tabular-nums leading-none [overflow-wrap:anywhere]',
          stat.text ? (tv ? 'text-4xl' : 'text-3xl') : (tv ? 'text-7xl' : 'text-6xl'))}>
        {stat.value ?? '—'}
      </span>
      {stat.label && <span className={cn('mt-2 block font-medium [overflow-wrap:anywhere]', tv ? 'text-xl' : 'text-base')}>{stat.label}</span>}
      {stat.sub && <span className={cn('mt-1 block opacity-90 [overflow-wrap:anywhere]', tv ? 'text-lg' : 'text-sm')}>{stat.sub}</span>}
    </>
  )
  const cls = 'flex min-h-28 flex-1 flex-col items-center justify-center py-2 text-center'
  if (!stat.onClick) return <div className={cls} data-slot="mydash-stat">{content}</div>
  return (
    <Button type="button" variant="ghost" onClick={stat.onClick} title={stat.hint} data-slot="mydash-stat" data-link="true"
      className={cn(LINK_RESET, cls, 'rounded-lg text-current hover:bg-black/10 hover:text-current dark:hover:bg-black/10')}>
      {content}
    </Button>
  )
}

/**
 * Panom kutusu çerçevesi: başlık (ikon, ad, durum rozeti, "Sayfayı aç"), düzenleme kipinde araç çubuğu (sürükle tutamacı,
 * önceki/sonraki, boyut, kaldır), gövde ve alt not (son güncelleme; hata varsa son verinin gösterildiği). Durum yalnız
 * rozet + `data-tone` ile — kartta renkli sol şerit YOK (ürün kuralı).
 *
 * `frame` (sayfadan): { widget, edit, tv, index, count, onMove, onResize, onRemove, dnd } — kutu bileşeni kendi
 * başlık bilgisini ekleyip buraya yayar.
 */
export default function WidgetCard({
  frame, Icon, title, description, tone = 'muted', toneLabel, tab, tabParams, loading, error, at, children, className, stat,
}) {
  const t = useT()
  const { widget, edit, tv, canPrev, canNext, onMove, onResize, onRemove, onStyle, dnd } = frame
  // Hiç veri alınamadıysa (ilk yükleme hatası) gövde ÇİZİLMEZ: özet fonksiyonları boş veride "her şey yolunda" der ve duvar
  // ekranı kesintide yeşil görünürdü (2026-10-12). Durum rozeti de gösterilmez; kutu "veri yok" der.
  const failed = !!error && !at
  const stale = !!error && !!at && Date.now() - at >= STALE_AFTER_MS
  const showBody = !(loading && !at)
  const shownTone = failed || stale ? 'muted' : tone
  // Renkli panel: kutu destekliyorsa (`stat` verildi), görünüm açıksa ve veri GEÇERLİYSE; tonu boyanabilir değilse klasik kart
  const surface = frame.stat && stat && showBody && !failed && !stale ? STAT_SURFACE[shownTone] : null
  const statOn = !!surface
  return (
    <div data-slot="mydash-widget" data-type={widget.type} data-size={widget.size} data-tone={shownTone} data-failed={failed || undefined}
      data-stale={stale || undefined} data-stat={statOn ? 'on' : undefined}
      className={cn('min-w-0', tv ? null : SPAN_CLASS[widget.size], dnd?.over ? 'rounded-xl ring-2 ring-primary ring-offset-2 ring-offset-background' : null, className)}
      style={tv ? { gridColumn: `span ${frame.span || 1} / span ${frame.span || 1}` } : undefined}
      draggable={edit && !!dnd} onDragStart={dnd?.onDragStart} onDragEnd={dnd?.onDragEnd}
      onDragOver={dnd?.onDragOver} onDragLeave={dnd?.onDragLeave} onDrop={dnd?.onDrop}>
      <Card className={cn('h-full gap-3 py-4', surface, dnd?.dragging && 'opacity-50')}>
        <CardHeader className="gap-1 px-4">
          <CardTitle className={cn('flex min-w-0 flex-wrap items-center gap-2 text-base', tv && 'text-xl')}>
            {Icon && (
              <span className={cn('grid size-8 shrink-0 place-items-center rounded-lg', statOn ? 'bg-black/15 text-current' : 'bg-muted text-foreground', tv && 'size-10')} aria-hidden="true">
                <Icon className={tv ? 'size-5' : 'size-4'} />
              </span>
            )}
            <span className="min-w-0 [overflow-wrap:anywhere]">{title}</span>
            {toneLabel && !failed && !stale && (statOn
              // Renkli zeminde rozet yerine sade etiket — durum rengin yanında METİNLE de söylenir
              ? <span data-slot="mydash-widget-tone" className={cn('rounded-md bg-black/15 px-2 py-0.5 font-semibold', tv ? 'text-sm' : 'text-xs')}>{toneLabel}</span>
              : <ToneBadge tone={tone} data-slot="mydash-widget-tone" className={tv ? 'text-sm' : null}>{toneLabel}</ToneBadge>)}
          </CardTitle>
          {description && !tv && !statOn && <CardDescription className="[overflow-wrap:anywhere]">{description}</CardDescription>}
          {tab && !tv && !edit && (
            <CardAction>
              <Button type="button" variant="ghost" size="icon" className={cn('size-8', HIT, statOn && 'text-current hover:bg-black/10 hover:text-current dark:hover:bg-black/10')} data-slot="mydash-open"
                aria-label={t('mydash.openPage', title)} title={t('mydash.openPage', title)} onClick={() => navigateTo(tab, tabParams)}>
                <ArrowUpRight aria-hidden="true" />
              </Button>
            </CardAction>
          )}
        </CardHeader>

        {edit && (
          <div className={cn('flex flex-wrap items-center gap-1.5 border-y border-border px-3 py-1.5', statOn ? 'bg-background text-foreground' : 'bg-muted/40')}
            data-slot="mydash-widget-tools">
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
            {stat !== undefined && onStyle && (
              // Renkli panel görünümü (kutu başına, sunucuda saklanır) — yalnız büyük sayısı olan kutularda
              <Toggle variant="outline" size="sm" pressed={widget.style === 'stat'} onPressedChange={(on) => onStyle(on ? 'stat' : null)}
                aria-label={t('mydash.styleTitle', title)} title={t('mydash.styleTitle', title)} data-slot="mydash-style"
                className="h-8 gap-1.5 px-2 text-xs font-semibold pointer-coarse:h-10">
                <Palette aria-hidden="true" /><span>{t('mydash.style')}</span>
              </Toggle>
            )}
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
          {failed ? (
            <div role="status" data-slot="mydash-widget-failed"
              className={cn('flex items-start gap-2 text-sm text-amber-700 dark:text-amber-300', tv && 'text-base')}>
              <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
              <span className="min-w-0 [overflow-wrap:anywhere]">
                <span className="block font-medium">{t('mydash.loadFailed')}</span>
                {error && error !== 'error' && <span className="block text-xs text-muted-foreground">{error}</span>}
              </span>
            </div>
          ) : statOn ? (
            <StatBody stat={stat} tv={tv} />
          ) : showBody ? (
            // Bayat veri renksiz (inceleme 4): gövdedeki yeşil sayı / "açık alarm yok" de kesinti sırasında yeşil kalmasın
            <div data-slot="mydash-widget-body" data-stale={stale || undefined}
              className={cn('flex min-w-0 flex-1 flex-col gap-3', stale && 'opacity-60 grayscale')}>
              {children}
            </div>
          ) : <LoadingBlock label={t('mydash.loading')} />}
        </CardContent>

        {at && (
          <div className={cn('flex flex-wrap items-center gap-1.5 px-4 text-xs', statOn ? 'text-current opacity-90' : 'text-muted-foreground', tv && 'text-sm')}
            data-slot="mydash-widget-foot">
            {error ? (
              // Renkli zeminde kehribar metin okunmuyordu (inceleme 2026-10-13) — zeminin metin rengi + koyu çip
              <span className={cn('inline-flex items-center gap-1', statOn ? 'rounded-md bg-black/20 px-1.5 py-0.5 font-semibold text-current'
                : 'text-amber-700 dark:text-amber-300')} role="status" data-slot="mydash-widget-error">
                <TriangleAlert className="size-3.5 shrink-0" aria-hidden="true" />
                <span className="[overflow-wrap:anywhere]">{at ? t('mydash.staleData') : error}</span>
              </span>
            ) : null}
            {error && at ? (
              <span className="[overflow-wrap:anywhere]" data-slot="mydash-widget-age">· {t('mydash.updated', agoText(at, t))}</span>
            ) : (
              <span>{t('mydash.updated', agoText(at, t))}</span>
            )}
          </div>
        )}
      </Card>
    </div>
  )
}
