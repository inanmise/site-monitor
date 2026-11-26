import { useCallback, useEffect, useRef, useState } from 'react'
import { ClipboardPaste, ImagePlus, RefreshCw, X } from 'lucide-react'
import { api } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { errorInfoOf } from '../../utils/errorMessages.js'
import { isUnsureResult } from '../../utils/unsureResult.js'
import { isMacPlatform } from '../../utils/keyboardShortcuts.js'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Spinner } from '@/components/shadcn/spinner'
import { cn } from '@/lib/utils'
import { useDialog } from '../ui/Dialog.jsx'
import { ImagePrepError, prepareImage } from '../techerror/imagePrep.js'
import { ImageLightbox } from '../techerror/ImageLightbox.jsx'
import { imageFromDataTransfer } from '../techerror/capture/captureModel.js'
import { ClipboardImageError, canReadClipboard, readClipboardImage, renameClipboardFile } from '../techerror/capture/clipboardImage.js'
import { isFileDrag } from '../techerror/capture/useFileDropGuard.js'
import { MAX_IMAGES_PER_PROBLEM, codeOf, errorKey } from './routineModel.js'

/**
 * Nöbet rutini SORUN GÖRSELLERİ (2026-11-26) — kayıtlı bir sorunun görselleri (küçük resim ızgarası → büyük görünüm) ve
 * görsel ekleme / silme. Ayrıca sorun yazma kutusunun (ProblemComposer) da kullandığı ortak yardımcılar burada:
 * görsel hazırlama (`techerror/imagePrep.js` — PNG / JPEG'e çevirir, sunucu sınırına indirir; dosya tarayıcıdan çıkmadan),
 * yapıştırılan içerikten görsel alma, yükleme hatası metni. Sunucu: PNG / JPEG, ≤ 5 MB, sorun başına en çok 4 görsel.
 */

/** Tarayıcının açıp PNG / JPEG'e çevirebildiği türler (imagePrep çevirir). */
export const IMAGE_ACCEPT = 'image/png,image/jpeg,image/webp,image/gif,image/bmp,image/avif'
/** Pano / sürükle-bırak ile gelen görselin dosya adı öneki (`sorun-pano-<zaman>.png`). */
export const PROBLEM_CAPTURE_ID = 'sorun'
const BYTE_PARAMS = new Set(['te.img.errTooBig', 'te.img.errStillLarge'])

/** Bayt → "182 KB" / "1,4 MB" (dilin ondalık ayırıcısıyla). */
export function formatBytes(n, lang = 'tr') {
  const v = Number(n)
  if (!Number.isFinite(v) || v < 0) return '—'
  const fmt = (x) => new Intl.NumberFormat(lang === 'en' ? 'en-GB' : 'tr-TR', { maximumFractionDigits: 1 }).format(x)
  if (v < 1024 * 1024) return `${fmt(Math.max(1, Math.round(v / 1024)))} KB`
  return `${fmt(Math.round((v / 1048576) * 10) / 10)} MB`
}

/** Yapıştırma kısayolu (Ctrl+V / ⌘V). */
export function pasteKey() {
  return isMacPlatform() ? '⌘V' : 'Ctrl+V'
}

/**
 * Bir dosyayı yüklemeye hazırlar. `{ ok: true, file, info }` ya da `{ ok: false, message }` — ileti kullanıcıya hazır
 * (dosyanın türünü / ölçüsünü ve ne yapılacağını söyler). Hiçbir zaman atmaz.
 */
export async function prepareForUpload(file, t, lang) {
  try {
    const r = await prepareImage(file)
    return { ok: true, file: r.file, info: r.info }
  } catch (e) {
    if (e instanceof ImagePrepError) {
      const params = BYTE_PARAMS.has(e.key) ? (e.params || []).map((n) => formatBytes(n, lang)) : (e.params || [])
      return { ok: false, message: t(e.key, ...params) }
    }
    return { ok: false, message: t('te.img.errBrowser') }
  }
}

/**
 * Yapıştırılan içerikten yüklenecek dosya: görsel (okunur adla) ya da yalnız dosya (PDF… — hazırlama türünü söyleyen
 * metni yazar). Metin yapıştırması (ya da metin + görsel olmayan dosya) `null` — tarayıcının kendi yapıştırması sürer.
 */
export function fileFromPaste(clipboardData) {
  const { file, image } = imageFromDataTransfer(clipboardData)
  if (!file) return null
  if (image) return renameClipboardFile(file, PROBLEM_CAPTURE_ID)
  const types = Array.from(clipboardData?.types || []).map(String)
  return types.some((x) => x.startsWith('text/')) ? null : file
}

/** Sunucunun hata metni; yoksa kodun çevirisi; o da yoksa yedek anahtar. */
export function serverText(res, t, fallbackKey) {
  if (typeof res?.error === 'string' && res.error.trim()) return res.error
  const key = errorKey(codeOf(res))
  return key ? t(key) : t(fallbackKey)
}

/** Görsel yükleme reddinin kullanıcı metni. */
export function uploadFailureText(res, t) {
  if (codeOf(res) !== 'PROBLEM_IMAGE_LIMIT' && isUnsureResult(res)) return t('or.prob.imgUnsure')
  return serverText(res, t, 'te.img.uploadError')
}

/** Aynı dosyayla yeniden denemek anlamlı mı (geçici ret)? Sınır / doğrulama reddi aynı sonucu verir. */
export function uploadRetryable(res) {
  const code = codeOf(res)
  if (code === 'PROBLEM_IMAGE_LIMIT' || code === 'PROBLEM_NOT_FOUND') return false
  const status = Number(errorInfoOf(res)?.status ?? res?.status)
  return isUnsureResult(res) || code === 'RATE_LIMITED' || (Number.isFinite(status) && status >= 500)
}

/** Panodan görsel okuma hatasının metni (izin yok / panoda görsel yok / desteklenmiyor). */
export function clipboardProblemText(e, t) {
  const kind = e instanceof ClipboardImageError ? e.kind : 'failed'
  if (kind === 'empty') return t('or.prob.clipEmpty', pasteKey())
  if (kind === 'denied') return t('or.prob.clipDenied', pasteKey())
  return t('or.prob.clipUnreadable', pasteKey())
}

/** Yükleme yanıtından görsel künyesi (`{id, width, height, size_bytes, content_type}`) — yoksa null. */
export function imageMetaOf(res) {
  const d = res?.data?.image ?? res?.data
  return d && typeof d === 'object' && d.id != null ? d : null
}

/** Kayıtlı bir sorunun görsel adresi (oturum çerezi ile aynı köken). */
export function problemImageSrc(problemId, imageId) {
  return api.oncallRoutines.problemImageUrl(problemId, imageId)
}

/**
 * Kayıtlı sorunun görselleri. Props: `{ problem, canWork, onChange }` — `onChange(problem)` görsel eklenince / silinince
 * güncellenmiş sorunla çağrılır. `canWork` yoksa yalnız görüntüleme (küçük resim → büyük görünüm).
 * Ekleme: "Görsel ekle" (dosya seçici), "Panodan yapıştır" (destekleyen tarayıcıda), bu alandayken Ctrl/⌘+V, sürükle-bırak.
 * Bir anda tek yükleme; geçici retlerde hazırlanmış görsel bekletilir ("Yeniden dene").
 */
export default function ProblemImages({ problem, canWork = false, onChange = () => {} }) {
  const t = useT()
  const { lang } = useLanguage()
  const { showConfirm } = useDialog()
  const images = Array.isArray(problem?.images) ? problem.images : []
  const editable = !!canWork && problem?.can_edit !== false
  const room = MAX_IMAGES_PER_PROBLEM - images.length
  const [busy, setBusy] = useState(null)              // null | { phase: 'prep' | 'upload', pct }
  const [problemText, setProblemText] = useState(null)
  const [kept, setKept] = useState(null)              // geçici retten sonra bekletilen hazır dosya
  const [deletingId, setDeletingId] = useState(null)
  const [drag, setDrag] = useState(false)
  // Büyük görünüm: kapanırken pencere kapanış animasyonunu oynatsın ve odak tıklanan küçük resme dönsün diye görsel kalır
  const [zoom, setZoom] = useState({ open: false, image: null, index: 0 })
  const inputRef = useRef(null)
  const busyRef = useRef(false)
  const zoomReturn = useRef(null)
  const alive = useRef(true)
  // Son sorun (yükleme bitince eklenecek liste güncel olsun — yükleme sürerken başka değişiklik gelebilir)
  const problemRef = useRef(problem)
  problemRef.current = problem
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])

  const send = useCallback(async (prepared) => {
    const p = problemRef.current
    if (!p?.id) return
    setBusy({ phase: 'upload', pct: 0 })
    let res
    try {
      res = await api.oncallRoutines.uploadProblemImage(p.id, prepared, {
        onProgress: (e) => {
          if (!alive.current || !e?.total) return
          setBusy({ phase: 'upload', pct: Math.min(100, Math.round((e.loaded / e.total) * 100)) })
        },
      })
    } catch {
      res = { success: false, status: 0, code: 'NETWORK_ERROR' }
    }
    if (res === null || !alive.current) return
    if (res?.success) {
      const meta = imageMetaOf(res)
      setKept(null)
      if (meta) {
        const cur = problemRef.current || p
        onChangeRef.current({ ...cur, images: [...(Array.isArray(cur.images) ? cur.images : []), meta] })
      }
      return
    }
    setProblemText(uploadFailureText(res, t))
    setKept(uploadRetryable(res) ? prepared : null)
  }, [t])

  const addFile = useCallback(async (file) => {
    if (!editable || !file) return
    if (busyRef.current) { setProblemText(t('or.prob.imgBusy')); return }
    const cur = problemRef.current
    if ((Array.isArray(cur?.images) ? cur.images.length : 0) >= MAX_IMAGES_PER_PROBLEM) {
      setProblemText(t('or.prob.imgLimit', MAX_IMAGES_PER_PROBLEM))
      return
    }
    busyRef.current = true
    setProblemText(null)
    setKept(null)
    setBusy({ phase: 'prep', pct: 0 })
    try {
      const r = await prepareForUpload(file, t, lang)
      if (!alive.current) return
      if (!r.ok) { setProblemText(r.message); return }
      await send(r.file)
    } finally {
      busyRef.current = false
      if (alive.current) {
        setBusy(null)
        if (inputRef.current) inputRef.current.value = ''
      }
    }
  }, [editable, lang, send, t])

  async function retryKept() {
    if (!kept || busyRef.current) return
    busyRef.current = true
    setProblemText(null)
    try {
      await send(kept)
    } finally {
      busyRef.current = false
      if (alive.current) setBusy(null)
    }
  }

  async function pasteFromClipboard() {
    if (!editable) return
    try {
      const file = await readClipboardImage(PROBLEM_CAPTURE_ID)
      await addFile(file)
    } catch (e) {
      if (alive.current) setProblemText(clipboardProblemText(e, t))
    }
  }

  async function removeImage(img, index) {
    if (deletingId != null || !problem?.id) return
    const yes = await showConfirm({
      title: t('or.prob.imgDeleteTitle'), message: t('or.prob.imgDeleteBody', index + 1),
      confirmText: t('or.prob.imgDeleteConfirm'), cancelText: t('or.prob.cancel'), variant: 'danger',
    })
    if (!yes || !alive.current) return
    setDeletingId(img.id)
    setProblemText(null)
    let res
    try {
      res = await api.oncallRoutines.deleteProblemImage(problem.id, img.id)
    } catch {
      res = { success: false, status: 0, code: 'NETWORK_ERROR' }
    } finally {
      if (alive.current) setDeletingId(null)
    }
    if (!alive.current) return
    if (res === null) return
    // Silindi ya da zaten yoktu (404): listeden düşer
    if (res?.success || codeOf(res) === 'IMAGE_NOT_FOUND') {
      const cur = problemRef.current || problem
      onChangeRef.current({ ...cur, images: (Array.isArray(cur.images) ? cur.images : []).filter((x) => x.id !== img.id) })
      return
    }
    setProblemText(isUnsureResult(res) ? t('or.prob.imgDeleteUnsure') : serverText(res, t, 'or.prob.imgDeleteRefused'))
  }

  if (!images.length && !editable) return null
  const label = (i) => t('or.prob.imageLabel', i + 1)

  return (
    <div data-slot="or-problem-images" data-count={images.length}
      className={cn('flex min-w-0 flex-col gap-2 rounded-md', drag && 'ring-2 ring-primary/40')}
      aria-busy={busy ? 'true' : undefined}
      onPaste={editable ? (e) => {
        const f = fileFromPaste(e.clipboardData)
        if (!f) return
        e.preventDefault()
        addFile(f)
      } : undefined}
      onDragOver={editable ? (e) => { if (isFileDrag(e)) { e.preventDefault(); setDrag(true) } } : undefined}
      onDragLeave={editable ? () => setDrag(false) : undefined}
      onDrop={editable ? (e) => {
        if (!isFileDrag(e)) return
        e.preventDefault()
        setDrag(false)
        const f = e.dataTransfer?.files?.[0]
        if (f) addFile(f)
      } : undefined}>
      {images.length > 0 && (
        <ul className="grid grid-cols-[repeat(auto-fill,minmax(5.5rem,1fr))] gap-2" data-slot="or-problem-image-list">
          {images.map((img, i) => (
            <li key={img.id} className="relative min-w-0" data-slot="or-problem-image" data-image-id={img.id}>
              <Button type="button" variant="ghost" aria-label={t('or.prob.imageZoom', i + 1)}
                className="block h-auto w-full overflow-hidden rounded-md border bg-muted/30 p-0 hover:bg-muted/50"
                onClick={(e) => { zoomReturn.current = e.currentTarget; setZoom({ open: true, image: img, index: i }) }}>
                <img src={problemImageSrc(problem.id, img.id)} alt={label(i)} loading="lazy" decoding="async"
                  width={img.width || undefined} height={img.height || undefined}
                  className="block aspect-[4/3] h-auto w-full object-cover" />
              </Button>
              {/* Dokunmatik vuruş alanı 40 px: ::after kenarlığın İÇİNDEKİ dolgu kutusuna göre konumlanır (28 − 2 + 2 × 7) */}
              {editable && (
                <Button type="button" variant="outline" size="icon" aria-label={t('or.prob.imgDelete', i + 1)}
                  data-slot="or-problem-image-delete" disabled={deletingId != null}
                  onClick={() => removeImage(img, i)}
                  className="absolute top-1 right-1 size-7 rounded-full bg-background/90 shadow-sm pointer-coarse:after:absolute pointer-coarse:after:-inset-[7px] pointer-coarse:after:content-['']">
                  {deletingId === img.id ? <Spinner className="size-3.5" /> : <X aria-hidden="true" />}
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {editable && room > 0 && (
        <div className="flex flex-wrap items-center gap-2">
          <Input ref={inputRef} type="file" accept={IMAGE_ACCEPT} hidden tabIndex={-1} aria-hidden="true"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) addFile(f) }} />
          <Button type="button" variant="outline" size="sm" disabled={!!busy} data-slot="or-problem-image-add"
            className="pointer-coarse:min-h-10" onClick={() => inputRef.current?.click()}>
            {busy ? <Spinner /> : <ImagePlus aria-hidden="true" />}
            {busy ? (busy.phase === 'prep' ? t('or.prob.imgPreparing') : t('or.prob.imgUploadingPct', busy.pct)) : t('or.prob.imgAdd')}
          </Button>
          {canReadClipboard() && (
            <Button type="button" variant="ghost" size="sm" disabled={!!busy} data-slot="or-problem-image-paste"
              className="pointer-coarse:min-h-10" onClick={pasteFromClipboard}>
              <ClipboardPaste aria-hidden="true" />{t('or.prob.imgPaste')}
            </Button>
          )}
          <span className="text-xs text-muted-foreground">{t('or.prob.imgHint', pasteKey(), room)}</span>
        </div>
      )}
      {problemText && (
        <div className="flex min-w-0 flex-col gap-2" data-slot="or-problem-image-problem">
          <p className="text-sm text-destructive [overflow-wrap:anywhere]" role="alert">{problemText}</p>
          {kept && !busy && (
            <div className="flex flex-wrap items-center gap-2">
              <Button type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10" onClick={retryKept}
                data-slot="or-problem-image-retry">
                <RefreshCw aria-hidden="true" />{t('or.prob.imgRetry')}
              </Button>
              <Button type="button" size="sm" variant="ghost" className="pointer-coarse:min-h-10"
                onClick={() => { setKept(null); setProblemText(null) }} data-slot="or-problem-image-drop">
                {t('or.prob.imgDrop')}
              </Button>
            </div>
          )}
        </div>
      )}
      {zoom.image && (
        <ImageLightbox open={zoom.open} onOpenChange={(o) => setZoom((z) => ({ ...z, open: o }))} returnFocusRef={zoomReturn}
          src={problemImageSrc(problem.id, zoom.image.id)} label={label(zoom.index)}
          width={zoom.image.width} height={zoom.image.height} />
      )}
    </div>
  )
}
