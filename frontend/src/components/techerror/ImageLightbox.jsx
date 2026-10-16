import { useState } from 'react'
import { X } from 'lucide-react'
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogTitle } from '@/components/shadcn/dialog'
import { Button } from '@/components/shadcn/button'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'
import { useT } from '../../i18n/index.jsx'
import { PHONE_FULLSCREEN } from '../ui/modalClasses.js'

const ZOOMS = ['fit', '100', '200']

/**
 * Yüklenen görselin büyük görünümü (2026-10-16, kullanıcı isteği: "görsele tıklanınca büyüteç gibi pop-up açılsın,
 * × ile kapansın"). Ekrana sığdır / %100 / %200; görselin kendisine tıklamak sığdır ↔ %100 arasında geçer, büyükken
 * kaydırarak gezilir. × düğmesi ve Esc kapatır; telefonda tam ekran. Görsel tam çözünürlükte saklandığı için (20.138)
 * %100 gerçek piksellerdir — küçük rakamlar burada okunur.
 */
export function ImageLightbox({ open, onOpenChange, src, label, width, height, returnFocusRef }) {
  const t = useT()
  const [zoom, setZoom] = useState('fit')
  const fit = zoom === 'fit'
  const scale = zoom === '200' ? 2 : 1
  const close = (o) => {
    if (!o) setZoom('fit')
    onOpenChange(o)
  }
  return (
    <Dialog open={open} onOpenChange={close}>
      {/* Sabit yükseklik: "sığdır" görseli KALAN alana sığdırır (uzun ad başlığı büyütse de görselin altı taşmaz);
          telefonda tam ekran, denetimler ayrı satırda. Kapanınca odak tıklanan görsele döner (tetikleyici dışarıda). */}
      <DialogContent showCloseButton={false} data-slot="te-image-lightbox"
        onCloseAutoFocus={(e) => { if (returnFocusRef?.current) { e.preventDefault(); returnFocusRef.current.focus() } }}
        className={cn('flex h-[94dvh] w-[96vw] max-w-[96vw] flex-col gap-3 p-4 sm:max-w-[96vw] max-sm:w-full', PHONE_FULLSCREEN)}>
        <div className="flex flex-wrap items-start justify-between gap-2">
          <DialogTitle className="min-w-0 flex-1 text-base leading-snug [overflow-wrap:anywhere] max-sm:basis-full">{label}</DialogTitle>
          <div className="flex shrink-0 flex-wrap items-center gap-2">
            <ToggleGroup type="single" variant="outline" size="sm" value={zoom} aria-label={t('te.img.zoomGroup')}
              onValueChange={(v) => { if (ZOOMS.includes(v)) setZoom(v) }} data-slot="te-image-lightbox-zoom">
              <ToggleGroupItem value="fit" className="px-3 pointer-coarse:min-h-10">{t('te.img.zoomFit')}</ToggleGroupItem>
              <ToggleGroupItem value="100" className="px-3 pointer-coarse:min-h-10">{t('te.img.zoom100')}</ToggleGroupItem>
              <ToggleGroupItem value="200" className="px-3 pointer-coarse:min-h-10">{t('te.img.zoom200')}</ToggleGroupItem>
            </ToggleGroup>
            <DialogClose asChild>
              <Button type="button" variant="outline" size="icon" aria-label={t('te.img.zoomClose')}
                className="size-9 pointer-coarse:size-10" data-slot="te-image-lightbox-close">
                <X aria-hidden="true" />
              </Button>
            </DialogClose>
          </div>
        </div>
        <DialogDescription className="sr-only">{t('te.img.zoomHint')}</DialogDescription>
        <div className="min-h-0 flex-1 overflow-auto rounded-md border bg-muted/30" data-slot="te-image-lightbox-view" data-zoom={zoom}>
          <Button type="button" variant="ghost" onClick={() => setZoom(fit ? '100' : 'fit')}
            aria-label={fit ? t('te.img.zoomTo100') : t('te.img.zoomToFit')}
            className={cn('min-h-10 min-w-10 rounded-none p-0 hover:bg-transparent',
              fit ? 'flex h-full w-full items-center justify-center cursor-zoom-in' : 'block h-auto w-max cursor-zoom-out')}>
            <img src={src} alt={label} width={width || undefined} height={height || undefined} data-slot="te-image-lightbox-img"
              className={fit ? 'block h-auto max-h-full w-auto max-w-full object-contain' : 'block h-auto max-w-none'}
              style={!fit && width ? { width: `${width * scale}px` } : undefined} />
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
