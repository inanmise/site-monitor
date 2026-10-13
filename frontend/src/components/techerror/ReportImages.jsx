import { useRef, useState } from 'react'
import { ImagePlus, RefreshCw, Trash2, Upload } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Card, CardContent, CardHeader } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { LIMITS, formatBytes, imageRows, preflightImage } from './techErrorModel.js'

/**
 * Grafik yuvaları — iki kolonlu ızgara (PDF'in aynısı): yarım yuvalar yan yana, tam yuva iki kolon genişliğinde. Her
 * yuvaya bir görsel (PNG / JPEG, ≤ 5 MB); yeni yükleme eskisinin yerini alır. Yükleme sunucuda baytlardan doğrulanır;
 * hata yuvanın altında yazar. Görsel yüklemek rapor sürümünü artırmaz (açık değer düzenlemesi çakışmaya düşmez).
 */
export function ImagesBlock({ section, reportId, images, resolve, uiLang, readOnly, onImagesChange }) {
  const t = useT()
  const bySlot = new Map(images.map((i) => [i.slot_id, i]))
  const total = images.reduce((a, i) => a + (i.size_bytes || 0), 0)
  const rows = imageRows(section.slots || [])
  return (
    <Card className="gap-3" data-slot="te-images" data-section={section.id}>
      <CardHeader className="gap-1">
        <h3 className="text-base font-semibold leading-snug [overflow-wrap:anywhere]">{resolve(section.title)}</h3>
        <p className="text-sm text-muted-foreground">{t('te.img.hint')}</p>
        <p className="text-xs text-muted-foreground" data-slot="te-img-total">
          {t('te.img.total', formatBytes(total, uiLang), formatBytes(LIMITS.reportImageBytes, uiLang))}
        </p>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {rows.map((row) => (
          <div key={row.map((r) => r.id).join('|')} className="grid gap-3 md:grid-cols-2">
            {row.map((slot) => (
              <SlotTile key={slot.id} slot={slot} image={bySlot.get(slot.id)} reportId={reportId} resolve={resolve}
                uiLang={uiLang} readOnly={readOnly} onImagesChange={onImagesChange}
                className={slot.width === 'full' ? 'md:col-span-2' : row.length === 1 ? 'md:col-span-1' : ''} />
            ))}
          </div>
        ))}
      </CardContent>
    </Card>
  )
}

function SlotTile({ slot, image, reportId, resolve, uiLang, readOnly, onImagesChange, className }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const inputRef = useRef(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [drag, setDrag] = useState(false)
  const label = resolve(slot.label)

  async function upload(file) {
    const pre = preflightImage(file)
    if (pre) { setError(t(pre)); return }
    setError(null)
    setBusy(true)
    try {
      const res = await api.techErrors.uploadImage(reportId, slot.id, file)
      if (res?.success && res.data) {
        onImagesChange((list) => [...list.filter((i) => i.slot_id !== slot.id), res.data])
        toast.success(t('te.img.uploaded', label))
      } else {
        setError(res?.error || t('te.img.uploadError'))
      }
    } catch (e) {
      setError(e?.message || t('te.img.uploadError'))
    } finally {
      setBusy(false)
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
      if (res?.success) onImagesChange((list) => list.filter((i) => i.id !== image.id))
      else setError(res?.error || t('te.img.removeError'))
    } catch (e) {
      setError(e?.message || t('te.img.removeError'))
    } finally {
      setBusy(false)
    }
  }

  const inputId = `te-img-${slot.id}`
  return (
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
        <p className="min-w-0 text-sm font-semibold leading-snug [overflow-wrap:anywhere]" data-slot="te-image-label">{label}</p>
        <span className="shrink-0 text-xs text-muted-foreground">{slot.width === 'full' ? t('te.width.full') : t('te.width.half')}</span>
      </div>
      {image ? (
        <>
          <img src={api.techErrors.imageUrl(image.id)} alt={label} loading="lazy"
            className="h-auto max-h-80 w-full rounded-md border bg-muted/30 object-contain" />
          <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
            {[image.file_name, `${image.width} × ${image.height}`, formatBytes(image.size_bytes, uiLang)].filter(Boolean).join(' · ')}
          </p>
        </>
      ) : (
        <div className="flex min-h-28 flex-col items-center justify-center gap-1 rounded-md border border-dashed px-3 py-4 text-center text-sm text-muted-foreground">
          <ImagePlus aria-hidden="true" className="size-6" />
          <span>{readOnly ? t('te.img.none') : t('te.img.drop')}</span>
        </div>
      )}
      {!readOnly && (
        <div className="flex flex-wrap gap-2">
          <Input ref={inputRef} id={inputId} type="file" accept="image/png,image/jpeg" hidden tabIndex={-1} aria-hidden="true"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) upload(f) }} />
          {/* Erişilebilir ad görünen metni İÇERİR (yuva adı yalnız ekran okuyucuya eklenir — etiket-adda kuralı) */}
          <Button type="button" size="sm" variant={image ? 'outline' : 'default'} disabled={busy}
            onClick={() => inputRef.current?.click()} className="pointer-coarse:min-h-10" data-slot="te-image-upload">
            {busy ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" />
              : image ? <RefreshCw aria-hidden="true" /> : <Upload aria-hidden="true" />}
            {busy ? t('te.img.uploading') : image ? t('te.img.replace') : t('te.img.upload')}
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
  )
}
