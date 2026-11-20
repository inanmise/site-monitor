import { memo } from 'react'
import { ImagePlus, RefreshCw, Trash2 } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { formatBytes } from '../techErrorModel.js'
import { imageUsedInDraft } from './noteModel.js'

/**
 * "BU NOTUN GÖRSELLERİ" — düzenleyicideki görsel listesi (küçük resim, ad, boyut). Her satırda "Metne ekle"; YALNIZ
 * kullanılmayan görselde "Sil" (onaylı). "Kullanılmıyor" = kayıtlı metinde de (sunucunun `linked` alanı) taslakta da
 * başvurusu yok. Taslaktan çıkarılmış ama kayıtlı metinde duran görsel önce kaydedilmelidir (sunucu 409 verir) — satır
 * bunu söyler. memo: yazarken (her tuşta) liste yeniden çizilmez — `usedIds` yalnız başvuru kümesi değişince yenilenir.
 */
function NoteImageList({ images, usedIds, imageBytes, limits, uiLang, busyId = null, disabled = false, onInsert, onDelete }) {
  const t = useT()
  const list = Array.isArray(images) ? images : []
  return (
    <div className="flex min-w-0 flex-col gap-2" data-slot="te-note-images" data-count={list.length}>
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <h4 className="text-sm font-semibold">{t('te.note.img.listTitle')}</h4>
        <p className="text-xs text-muted-foreground" data-slot="te-note-images-totals">
          {imageBytes == null
            ? t('te.note.img.totalsCount', list.length, limits.max_images)
            : t('te.note.img.totals', list.length, limits.max_images, formatBytes(imageBytes, uiLang), formatBytes(limits.max_total_bytes, uiLang))}
        </p>
      </div>
      {list.length === 0 ? (
        <p className="text-sm text-muted-foreground" data-slot="te-note-images-empty">{t('te.note.img.none')}</p>
      ) : (
        <ul className="flex list-none flex-col gap-2">
          {list.map((img) => {
            const used = imageUsedInDraft(img, usedIds)
            const unused = !used && img.linked === false
            const pendingRemoval = !used && img.linked !== false
            const name = img.file_name || t('te.note.view.imageUnnamed')
            return (
              <li key={img.id} className="flex min-w-0 flex-wrap items-start gap-3 rounded-lg border p-2" data-slot="te-note-image-row"
                data-image-id={img.id} data-unused={unused ? 'true' : 'false'}>
                <img src={api.techErrors.channelNoteImageUrl(img.id)} alt="" loading="lazy" decoding="async" width="56" height="56"
                  className="size-14 shrink-0 rounded-md border bg-muted/30 object-cover" data-slot="te-note-image-thumb" />
                <div className="flex min-w-0 flex-1 basis-40 flex-col gap-1">
                  <p className="text-sm font-medium [overflow-wrap:anywhere]">{name}</p>
                  <p className="text-xs text-muted-foreground">
                    {[img.width && img.height ? `${img.width} × ${img.height}` : null, formatBytes(img.size_bytes, uiLang)].filter(Boolean).join(' · ')}
                  </p>
                  {unused && (
                    <Badge variant="outline" className="w-fit overflow-visible rounded-md whitespace-normal text-muted-foreground" data-slot="te-note-image-unused">
                      {t('te.note.img.unused')}
                    </Badge>
                  )}
                  {pendingRemoval && (
                    <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-note-image-pending">{t('te.note.img.removedInDraft')}</p>
                  )}
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <Button type="button" size="sm" variant="outline" disabled={disabled} className="pointer-coarse:min-h-10"
                    data-slot="te-note-image-insert" onClick={() => onInsert?.(img)}>
                    <ImagePlus aria-hidden="true" /> {t('te.note.img.insert')}
                    <span className="sr-only">{`: ${name}`}</span>
                  </Button>
                  {unused && (
                    <Button type="button" size="sm" variant="ghost" disabled={disabled || busyId != null} className="text-destructive pointer-coarse:min-h-10"
                      data-slot="te-note-image-delete" onClick={() => onDelete?.(img)}>
                      {busyId === img.id ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : <Trash2 aria-hidden="true" />}
                      {t('te.delete')}
                      <span className="sr-only">{`: ${name}`}</span>
                    </Button>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      )}
      <p className="text-xs text-muted-foreground">{t('te.note.img.keepHint', limits.unlinked_keep_days)}</p>
    </div>
  )
}

export default memo(NoteImageList)
