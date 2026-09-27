import { useEffect } from 'react'
import { ChevronLeft, ChevronRight, ZoomIn } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * Ekran görüntüsü ışık kutusu — ModalShell (iç içe açılabilir: Sheet ya da rapor penceresinin üstünde; Escape yalnız
 * bunu kapatır). ←/→ ve düğmelerle gezinir, "2 / 5" sayacı. `images` data-URL dizisi, `index` açık görsel (null = kapalı).
 * Test kancası: `data-slot="issue-lightbox"`.
 */
export default function ImageLightbox({ images, index, onIndex, onClose, title }) {
  const t = useT()
  const open = index != null && !!images?.[index]
  const n = images?.length || 0

  useEffect(() => {
    if (!open || n < 2) return undefined
    const onKey = (e) => {
      if (e.key === 'ArrowRight') { e.preventDefault(); onIndex((index + 1) % n) }
      else if (e.key === 'ArrowLeft') { e.preventDefault(); onIndex((index - 1 + n) % n) }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, index, n, onIndex])

  if (!open) return null
  const label = `${title || t('issue.screenshot')} ${index + 1}`
  return (
    <ModalShell open onClose={onClose} size="full" icon={ZoomIn} title={n > 1 ? `${label} / ${n}` : label}
      closeLabel={t('app.close')}>
      <figure data-slot="issue-lightbox" data-index={index} className="m-0 flex min-w-0 flex-col items-center gap-3">
        <img src={images[index]} alt={label} className="block max-h-[72dvh] max-w-full rounded-md border object-contain" />
        {n > 1 && (
          <figcaption className="flex w-full items-center justify-between gap-2">
            <Button type="button" variant="outline" className="h-10" onClick={() => onIndex((index - 1 + n) % n)}>
              <ChevronLeft aria-hidden="true" />{t('issues.prevImage')}
            </Button>
            <span className="text-sm text-muted-foreground tabular-nums">{t('issues.imageCounter', index + 1, n)}</span>
            <Button type="button" variant="outline" className="h-10" onClick={() => onIndex((index + 1) % n)}>
              {t('issues.nextImage')}<ChevronRight aria-hidden="true" />
            </Button>
          </figcaption>
        )}
      </figure>
    </ModalShell>
  )
}
