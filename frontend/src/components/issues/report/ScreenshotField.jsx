import { useRef, useState } from 'react'
import { ImagePlus, ZoomIn, X, Upload } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import ImageLightbox from '../ImageLightbox.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Kbd } from '@/components/shadcn/kbd'
import { cn } from '@/lib/utils'
import { MAX_IMAGES } from './reportModel.js'

/**
 * Ekran görüntüsü alanı — sürükle-bırak, dosya seçici ve (pencere düzeyinde) panodan yapıştırma. Küçük resimler
 * büyütülür (ışık kutusu, ←/→) ve tek tek kaldırılır; sınırlar görünür yazılı. Hiçbir dosya sessizce düşmez:
 * çağıranın `notice` metni uyarı bandında gösterilir.
 * Test kancaları: `data-slot="issue-dropzone"` (sürüklerken `data-dragging`), `data-slot="issue-thumbs"`.
 */
export default function ScreenshotField({ images, onFiles, onRemove, notice, onDismissNotice, disabled = false }) {
  const t = useT()
  const fileRef = useRef(null)
  const [dragging, setDragging] = useState(false)
  const [zoom, setZoom] = useState(null)
  const full = images.length >= MAX_IMAGES

  return (
    <div className="flex min-w-0 flex-col gap-2.5">
      <div data-slot="issue-dropzone" data-dragging={dragging || undefined}
        className={cn('flex min-w-0 flex-col items-center gap-2 rounded-lg border border-dashed px-3 py-4 text-center transition-colors motion-reduce:transition-none',
          dragging ? 'border-primary bg-primary/5' : 'bg-muted/20', full && 'opacity-70')}
        onDragEnter={(e) => { e.preventDefault(); if (!disabled) setDragging(true) }}
        onDragOver={(e) => { e.preventDefault(); if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy' }}
        onDragLeave={(e) => { if (e.currentTarget === e.target) setDragging(false) }}
        onDrop={(e) => { e.preventDefault(); setDragging(false); if (!disabled) onFiles(e.dataTransfer?.files) }}>
        <Upload aria-hidden="true" className="size-5 text-muted-foreground" />
        <div className="text-sm">
          <Button type="button" variant="outline" size="sm" className="h-9" onClick={() => fileRef.current?.click()} disabled={disabled || full}>
            <ImagePlus aria-hidden="true" />{t('issue.addImage')}
          </Button>
        </div>
        <p className="m-0 max-w-sm text-xs text-muted-foreground">
          {t('irf.dropHint')} <span className="whitespace-nowrap">{t('irf.pasteHint')} <Kbd>Ctrl</Kbd>+<Kbd>V</Kbd></span>
        </p>
        <p className="m-0 text-[11px] text-muted-foreground tabular-nums">{t('irf.imgLimits', images.length, MAX_IMAGES)}</p>
        <Input ref={fileRef} type="file" accept="image/png,image/jpeg" multiple hidden tabIndex={-1} aria-hidden="true"
          onChange={(e) => { onFiles(e.target.files); e.target.value = '' }} />
      </div>

      {images.length > 0 && (
        <ul data-slot="issue-thumbs" className="m-0 grid list-none grid-cols-[repeat(auto-fill,minmax(88px,1fr))] gap-2 p-0">
          {images.map((img, i) => (
            <li key={i} className="relative min-w-0">
              <Button type="button" variant="outline" onClick={() => setZoom(i)} aria-label={t('issue.imgZoom', i + 1)}
                className="group relative block aspect-[4/3] h-auto w-full cursor-zoom-in overflow-hidden rounded-md p-0 has-[>svg]:p-0">
                <img src={img} alt="" className="block size-full object-cover" />
                <ZoomIn aria-hidden="true" className="absolute bottom-1 left-1 size-5 rounded bg-black/55 p-0.5 text-white opacity-80 group-hover:opacity-100" />
              </Button>
              <Button type="button" variant="secondary" size="icon-xs" onClick={() => onRemove(i)} aria-label={t('issue.imgRemove', i + 1)}
                disabled={disabled}
                className="absolute -top-2 -right-2 size-7 rounded-full border bg-card text-muted-foreground shadow-sm hover:bg-destructive hover:text-white pointer-coarse:size-9">
                <X aria-hidden="true" className="size-3.5" />
              </Button>
            </li>
          ))}
        </ul>
      )}

      {notice && (
        <AlertBanner tone="warning" className="mb-0" onDismiss={onDismissNotice} dismissLabel={t('app.close')}>{notice}</AlertBanner>
      )}

      <ImageLightbox images={images} index={zoom} onIndex={setZoom} onClose={() => setZoom(null)} />
    </div>
  )
}
