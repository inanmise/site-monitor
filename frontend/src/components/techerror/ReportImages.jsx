import { memo, useRef, useState } from 'react'
import { ImagePlus, RefreshCw, Trash2, Upload, ZoomIn } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Card, CardContent, CardHeader } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { LIMITS, formatBytes, imageRows } from './techErrorModel.js'
import { ImagePrepError, displayWidthPct, prepareImage } from './imagePrep.js'
import { CopyTextButton } from './TechErrorParts.jsx'
import { ImageLightbox } from './ImageLightbox.jsx'

/** Tarayıcının açıp PNG / JPEG'e çevirebildiği türler (sunucu yalnız PNG / JPEG alır — imagePrep çevirir). */
const ACCEPT = 'image/png,image/jpeg,image/webp,image/gif,image/bmp,image/avif'
const BYTE_PARAMS = new Set(['te.img.errTooBig', 'te.img.errStillLarge'])

/**
 * Grafik yuvaları — iki kolonlu ızgara (PDF'in aynısı): yarım yuvalar yan yana, tam yuva iki kolon genişliğinde. Her
 * yuvaya bir görsel (PNG / JPEG, ≤ 5 MB); yeni yükleme eskisinin yerini alır. Yükleme sunucuda baytlardan doğrulanır;
 * hata yuvanın altında yazar. Görsel yüklemek rapor sürümünü artırmaz (açık değer düzenlemesi çakışmaya düşmez).
 */
// memo: yazarken (her tuşta düzenleyici yeniden çizilir) değişmeyen görsel bölümleri — 22 yuva — yeniden çizilmez;
// düzenleyici geri çağrıyı sabit verir (useCallback)
export const ImagesBlock = memo(function ImagesBlock({ section, reportId, images, resolve, uiLang, readOnly, onImagesChange, showTotals = true }) {
  const t = useT()
  const bySlot = new Map(images.map((i) => [i.slot_id, i]))
  const total = images.reduce((a, i) => a + (i.size_bytes || 0), 0)
  // Rapor toplamı yükleme ANINDA okunur: hazırlama sürerken başka yuvaya yüklenen görsel de sayılır (inceleme 2026-10-15)
  const imagesRef = useRef(images)
  imagesRef.current = images
  const otherBytesOf = (slotId) => (imagesRef.current || [])
    .reduce((a, i) => a + (i.slot_id === slotId ? 0 : (i.size_bytes || 0)), 0)
  const rows = imageRows(section.slots || [])
  return (
    <Card className="gap-3" data-slot="te-images" data-section={section.id}>
      <CardHeader className="gap-1">
        <div data-slot="te-copy-row" className="text-base leading-snug [overflow-wrap:anywhere]">
          <h3 className="inline text-base font-semibold leading-snug">{resolve(section.title)}</h3>
          <CopyTextButton text={resolve(section.title)} />
        </div>
        {showTotals && (
          <>
            <p className="text-sm text-muted-foreground">{t('te.img.hint')}</p>
            <p className="text-xs text-muted-foreground" data-slot="te-img-total">
              {t('te.img.total', formatBytes(total, uiLang), formatBytes(LIMITS.reportImageBytes, uiLang))}
            </p>
          </>
        )}
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {rows.map((row) => (
          <div key={row.map((r) => r.id).join('|')} className="grid gap-3 md:grid-cols-2">
            {row.map((slot) => (
              <SlotTile key={slot.id} slot={slot} image={bySlot.get(slot.id)} reportId={reportId} resolve={resolve}
                uiLang={uiLang} readOnly={readOnly} onImagesChange={onImagesChange}
                otherBytes={() => otherBytesOf(slot.id)}
                className={slot.width === 'full' ? 'md:col-span-2' : row.length === 1 ? 'md:col-span-1' : ''} />
            ))}
          </div>
        ))}
      </CardContent>
    </Card>
  )
})

function SlotTile({ slot, image, reportId, resolve, uiLang, readOnly, onImagesChange, className, otherBytes = () => 0 }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const inputRef = useRef(null)
  const [busy, setBusy] = useState(false)
  const [phase, setPhase] = useState(null)        // 'prep' (tarayıcıda hazırlanıyor) | 'upload'
  const [error, setError] = useState(null)
  const [note, setNote] = useState(null)          // son yüklemenin küçültme / çevirme özeti
  const [zoomed, setZoomed] = useState(false)     // büyük görünüm (2026-10-16)
  const zoomRef = useRef(null)                    // kapanınca odak görsele döner
  const [drag, setDrag] = useState(false)
  const label = resolve(slot.label)
  /** Küçültme / çevirme / döndürme özeti (yuvanın altında ve başarı bildiriminde) */
  const noteText = (n) => [
    n.resized && t('te.img.resized', `${n.fromW} × ${n.fromH}`, `${n.toW} × ${n.toH}`, formatBytes(n.fromBytes, uiLang), formatBytes(n.toBytes, uiLang)),
    n.converted && t('te.img.converted', n.fromKind, n.toKind),
    n.rotated && t('te.img.rotated'),
  ].filter(Boolean).join(' ')

  async function upload(file) {
    setError(null)
    setNote(null)
    setBusy(true)
    setPhase('prep')
    try {
      // Tarayıcıda hazırla (2026-10-16: çözünürlük korunur): yalnız sunucu sınırını aşan görsel alan ortalamasıyla
      // küçülür, gerekirse PNG'ye çevrilir; neyin yanlış olduğunu ve ne yapılacağını söyleyen hata
      let prepared
      try {
        prepared = await prepareImage(file)
      } catch (e) {
        if (e instanceof ImagePrepError) {
          const params = BYTE_PARAMS.has(e.key) ? e.params.map((n) => formatBytes(n, uiLang)) : e.params
          setError(t(e.key, ...params))
        } else {
          // Açma hatası errDecode olarak gelir; beklenmeyen hata çizim / bellek tarafıdır — "dosya bozuk" denmez
          setError(t('te.img.errBrowser'))
        }
        return
      }
      // Rapor toplamı (sunucu da denetler; burada yüklemeden ÖNCE söylenir)
      const others = otherBytes()
      if (others + prepared.file.size > LIMITS.reportImageBytes) {
        setError(t('te.img.errTotal', formatBytes(others + prepared.file.size, uiLang), formatBytes(LIMITS.reportImageBytes, uiLang)))
        return
      }
      setPhase('upload')
      const res = await api.techErrors.uploadImage(reportId, slot.id, prepared.file)
      if (res?.success && res.data) {
        onImagesChange((list) => [...list.filter((i) => i.slot_id !== slot.id), res.data])
        const changed = prepared.info.resized || prepared.info.converted || prepared.info.rotated
        // Not YÜKLENEN görsele bağlı: başka biri görseli değiştirip yeniden okunursa eski not o görselin altında kalmaz
        if (changed) setNote({ imageId: res.data.id, info: prepared.info })
        // Not bildirimde de okunur (yuvanın altındaki not görselle birlikte eklendiği için duyurulmaz); uzun metin → uzun süre
        if (changed) toast.success(`${t('te.img.uploaded', label)} ${noteText(prepared.info)}`, 8000)
        else toast.success(t('te.img.uploaded', label))
      } else {
        setError(res?.error || t('te.img.uploadError'))
      }
    } catch (e) {
      setError(e?.message || t('te.img.uploadError'))
    } finally {
      setBusy(false)
      setPhase(null)
      if (inputRef.current) inputRef.current.value = ''
    }
  }

  async function remove() {
    const ok = await showConfirm({ title: t('te.img.removeTitle'), message: t('te.img.removeBody', label), variant: 'danger',
      confirmText: t('te.img.remove'), cancelText: t('te.cancel') })
    if (!ok) return
    setBusy(true)
    try {
      const res = await api.techErrors.deleteImage(reportId, image.id)
      if (res?.success) { setNote(null); onImagesChange((list) => list.filter((i) => i.id !== image.id)) }
      else setError(res?.error || t('te.img.removeError'))
    } catch (e) {
      setError(e?.message || t('te.img.removeError'))
    } finally {
      setBusy(false)
    }
  }

  const inputId = `te-img-${slot.id}`
  return (
    <>
    <div className={cn('flex min-w-0 flex-col gap-2 rounded-xl border p-3', drag && 'border-primary bg-primary/5', className)}
      data-slot="te-image-slot" data-slot-id={slot.id} data-width={slot.width} data-has-image={image ? 'true' : 'false'}
      onDragOver={readOnly ? undefined : (e) => { e.preventDefault(); setDrag(true) }}
      onDragLeave={readOnly ? undefined : () => setDrag(false)}
      onDrop={readOnly ? undefined : (e) => {
        e.preventDefault()
        setDrag(false)
        const f = e.dataTransfer?.files?.[0]
        if (f && !busy) upload(f)
      }}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <p data-slot="te-copy-row" className="min-w-0 text-sm font-semibold leading-snug [overflow-wrap:anywhere]">
          <span data-slot="te-image-label">{label}</span>
          <CopyTextButton text={label} />
        </p>
        <span className="shrink-0 text-xs text-muted-foreground">{slot.width === 'full' ? t('te.width.full') : t('te.width.half')}</span>
      </div>
      {image ? (
        <>
          {/* Yuvanın genişliğini doldurur, oran korunur (kullanıcı kararı 2026-10-16: "hepsi yüklendiği alanın boyutunu
              alsın") — eskiden 320 px'lik tavan görseli daraltıp yanlarda boşluk bırakıyordu. PDF ile aynı kural: yalnız
              sayfaya sığmayacak kadar uzun görsel daralır (displayWidthPct). width / height yer ayırır (yüklenince kayma yok) */}
          {/* Tıklayınca büyük görünüm (2026-10-16, kullanıcı isteği: "görsele tıklanınca büyüteç gibi pop-up açılsın,
              × ile kapansın") */}
          <Button ref={zoomRef} type="button" variant="ghost" onClick={() => setZoomed(true)} aria-label={t('te.img.zoom', label)}
            data-slot="te-image-zoom" style={{ maxWidth: `${displayWidthPct(slot.width, image.width, image.height)}%` }}
            className="group relative mx-auto block h-auto w-full cursor-zoom-in rounded-md p-0 hover:bg-transparent">
            <img src={api.techErrors.imageUrl(image.id)} alt={label} loading="lazy" data-slot="te-image"
              width={image.width || undefined} height={image.height || undefined}
              className="block h-auto w-full rounded-md border" />
            <span aria-hidden="true"
              className="pointer-events-none absolute right-2 top-2 rounded-md border bg-background/90 p-1.5 text-foreground opacity-80 shadow-sm transition-opacity group-hover:opacity-100">
              <ZoomIn className="size-4" />
            </span>
          </Button>
          <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
            {[image.file_name, `${image.width} × ${image.height}`, formatBytes(image.size_bytes, uiLang)].filter(Boolean).join(' · ')}
          </p>
          {note && note.imageId === image.id && (
            <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-image-note">{noteText(note.info)}</p>
          )}
        </>
      ) : (
        <div className="flex min-h-28 flex-col items-center justify-center gap-1 rounded-md border border-dashed px-3 py-4 text-center text-sm text-muted-foreground">
          <ImagePlus aria-hidden="true" className="size-6" />
          <span>{readOnly ? t('te.img.none') : t('te.img.drop')}</span>
        </div>
      )}
      {!readOnly && (
        <div className="flex flex-wrap gap-2">
          <Input ref={inputRef} id={inputId} type="file" accept={ACCEPT} hidden tabIndex={-1} aria-hidden="true"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) upload(f) }} />
          {/* Erişilebilir ad görünen metni İÇERİR (yuva adı yalnız ekran okuyucuya eklenir — etiket-adda kuralı) */}
          <Button type="button" size="sm" variant={image ? 'outline' : 'default'} disabled={busy}
            onClick={() => inputRef.current?.click()} className="pointer-coarse:min-h-10" data-slot="te-image-upload">
            {busy ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" />
              : image ? <RefreshCw aria-hidden="true" /> : <Upload aria-hidden="true" />}
            {busy ? (phase === 'prep' ? t('te.img.preparing') : t('te.img.uploading')) : image ? t('te.img.replace') : t('te.img.upload')}
            <span className="sr-only">{`: ${label}`}</span>
          </Button>
          {image && (
            <Button type="button" size="sm" variant="ghost" disabled={busy} onClick={remove}
              className="text-destructive pointer-coarse:min-h-10">
              <Trash2 aria-hidden="true" /> {t('te.img.remove')}
              <span className="sr-only">{`: ${label}`}</span>
            </Button>
          )}
        </div>
      )}
      {error && <p className="text-sm text-destructive [overflow-wrap:anywhere]" role="alert" data-slot="te-image-error">{error}</p>}
    </div>
    {/* Büyük görünüm yuvanın bırakma alanının DIŞINDA: portal olayları React ağacında kabarcıklanır — içerideyken açık
        pencereye bırakılan dosya bu yuvaya sessizce yükleniyordu (inceleme 2026-10-16) */}
    {image && (
      <ImageLightbox open={zoomed} onOpenChange={setZoomed} src={api.techErrors.imageUrl(image.id)} label={label}
        width={image.width} height={image.height} returnFocusRef={zoomRef} />
    )}
    </>
  )
}
