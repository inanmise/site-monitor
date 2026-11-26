import { useEffect, useId, useRef, useState } from 'react'
import { ArrowDownCircle, CircleAlert, ClipboardPaste, ImageIcon, ImagePlus, RefreshCw, Save, TriangleAlert, X } from 'lucide-react'
import { api } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { serverFieldErrors } from '../../utils/formErrors.js'
import { isUnsureResult } from '../../utils/unsureResult.js'
import { isMacPlatform } from '../../utils/keyboardShortcuts.js'
import { useLatestCallback } from '../../hooks/useLatestCallback.js'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { Textarea } from '@/components/shadcn/textarea'
import { Kbd } from '@/components/shadcn/kbd'
import { Spinner } from '@/components/shadcn/spinner'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'
import AlertBanner from '../ui/AlertBanner.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { canReadClipboard, readClipboardImage } from '../techerror/capture/clipboardImage.js'
import { isFileDrag } from '../techerror/capture/useFileDropGuard.js'
import {
  IMAGE_ACCEPT, PROBLEM_CAPTURE_ID, clipboardProblemText, fileFromPaste, formatBytes, imageMetaOf, pasteKey,
  prepareForUpload, serverText, uploadFailureText, uploadRetryable,
} from './ProblemImages.jsx'
import { MAX_IMAGES_PER_PROBLEM, PROBLEM_NOTE_MAX, PROBLEM_NOTE_MIN, SEVERITIES } from './routineModel.js'

/** Önem simgeleri (rozet ve seçici aynı simgeyi kullanır). */
export const SEVERITY_ICON = Object.freeze({ LOW: ArrowDownCircle, MEDIUM: CircleAlert, HIGH: TriangleAlert })
/** Seçili önem tonu (seçicide). Düşük = nötr vurgu; Orta = dikkat (amber, Badge `warning` varyantıyla aynı); Yüksek = tehlike. */
const SEVERITY_ON = Object.freeze({
  LOW: '',
  MEDIUM: 'data-[state=on]:border-amber-500/50 data-[state=on]:bg-amber-500/15 data-[state=on]:text-amber-700 dark:data-[state=on]:text-amber-300',
  HIGH: 'data-[state=on]:border-destructive/50 data-[state=on]:bg-destructive/10 data-[state=on]:text-destructive',
})
/** Sayaç bu uzunluktan sonra görünür (sınıra yaklaşınca). */
const COUNTER_FROM = Math.floor(PROBLEM_NOTE_MAX * 0.9)
const DEFAULT_SEVERITY = 'MEDIUM'

/**
 * Önem seçici (Düşük · Orta · Yüksek) — tek seçim, seçim kaldırılamaz (boş değer yok sayılır). Yazma kutusu ve sorun
 * kartının düzenleme kipi kullanır. Dokunmatikte her düğme 40 px.
 */
export function SeverityToggle({ value, onChange, labelledBy, disabled = false, slot = 'or-problem-severity' }) {
  const t = useT()
  return (
    <ToggleGroup type="single" variant="outline" size="sm" value={value} disabled={disabled} aria-labelledby={labelledBy}
      onValueChange={(v) => { if (SEVERITIES.includes(v)) onChange(v) }} data-slot={slot} data-value={value}>
      {SEVERITIES.map((s) => {
        const Icon = SEVERITY_ICON[s]
        return (
          <ToggleGroupItem key={s} value={s} data-value={s} className={cn('gap-1.5 px-3 pointer-coarse:h-10', SEVERITY_ON[s])}>
            <Icon aria-hidden="true" />{t(`or.sev.${s}`)}
          </ToggleGroupItem>
        )
      })}
    </ToggleGroup>
  )
}

/** Not uzunluğu sorunu (boş = geçerli) — sunucu kuralının aynısı (kenar boşlukları sayılmaz). */
export function problemNoteProblem(text, t, lang = 'tr') {
  const n = String(text ?? '').trim().length
  if (n < PROBLEM_NOTE_MIN) return t('or.prob.noteTooShort', PROBLEM_NOTE_MIN)
  if (n > PROBLEM_NOTE_MAX) return t('or.prob.noteTooLong', new Intl.NumberFormat(lang === 'en' ? 'en-GB' : 'tr-TR').format(PROBLEM_NOTE_MAX))
  return null
}

function previewUrl(file) {
  try {
    return typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function' ? URL.createObjectURL(file) : null
  } catch {
    return null
  }
}
function revoke(url) {
  try { if (url && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url) } catch { /* yok */ }
}

/**
 * SORUN YAZMA KUTUSU (2026-11-26) — satırın altında ya da Açık sorunlar panelinde SATIR İÇİ açılır, hiçbir zaman pencere
 * değil: nöbetçi listeden ayrılmadan sorunu hızla kaydeder.
 *
 * Akış: önem (varsayılan Orta) + not (3–4000) + isteğe bağlı görseller (yapıştır / sürükle / "Görsel ekle" / "Panodan
 * yapıştır"; tarayıcıda PNG / JPEG'e hazırlanır, en çok 4) → "Kaydet" (Ctrl/⌘+Enter) → sorun oluşturulur (görev verildiyse
 * görev "yapıldı" işaretlenir, `mark_done`) → `onCreated(yanıt)` HEMEN çağrılır (üst bileşen madde / ilerleme / sorun
 * listesini günceller) → sıradaki görseller TEK TEK yüklenir. Bir görsel yüklenemezse sorun kayıtlı kalır, o görselin
 * yanında "Yeniden dene" / "Bırak" çıkar. Hepsi bitince `onImagesDone(görselli sorun)` (en az bir görsel yüklendiyse) ve
 * `onClose()`.
 *
 * Üst bileşen kutuyu `onClose` gelene dek açık tutmalıdır; kutu yükleme sürerken kaldırılırsa yükleme yine biter,
 * `onImagesDone` yine çağrılır ve yüklenemeyen görsel varsa bildirimle söylenir (görsel sessizce kaybolmaz).
 *
 * Esc vazgeçer (yazılmış bir şey varsa önce sorar). Sunucu alan hatası (`note`) alanın altında; sonucu bilinmeyen kayıt
 * (zaman aşımı / ağ) sarı bantla söylenir — yinelenen kayıt açılmasın diye önce listeye bakılması istenir.
 *
 * Props: `{ teamId, date, item = null, onCreated, onImagesDone, onClose, autoFocus = true, compact = false }`.
 * `item` = gün sayfasının ITEM'ı (`task_id`, `period_kind`, `period_key`, `title`, `state`); yoksa genel sorun.
 */
export function ProblemComposer({
  teamId, date, item = null, onCreated = () => {}, onImagesDone = () => {}, onClose = () => {}, autoFocus = true, compact = false,
}) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const baseId = useId()
  const sevLabelId = `${baseId}-sev`
  const noteId = `${baseId}-note`
  const noteHintId = `${baseId}-note-hint`
  const noteProblemId = `${baseId}-note-problem`

  const [severity, setSeverity] = useState(DEFAULT_SEVERITY)
  const [note, setNote] = useState('')
  const [noteProblem, setNoteProblem] = useState(null)
  const [sevProblem, setSevProblem] = useState(null)
  const [images, setImagesState] = useState([])         // { key, file, name, size, url, status, pct, message, retryable, meta }
  const [preparing, setPreparing] = useState(0)
  const [imgNote, setImgNote] = useState(null)
  const [phase, setPhaseState] = useState('form')       // form | saving | images
  const [unsure, setUnsure] = useState(false)
  const [drag, setDrag] = useState(false)

  const alive = useRef(true)
  const textareaRef = useRef(null)
  const inputRef = useRef(null)
  const imagesRef = useRef([])
  const preparingRef = useRef(0)
  const phaseRef = useRef('form')
  const problemRef = useRef(null)
  const uploadingRef = useRef(false)
  const finishedRef = useRef(false)
  const seq = useRef(0)
  // Son geri çağrılar (yükleme kutu kaldırıldıktan sonra da sürer; o an üst bileşenin güncel işleyicisi çağrılır)
  const cb = useRef({ onCreated, onImagesDone, onClose })
  cb.current = { onCreated, onImagesDone, onClose }

  const setPhase = (p) => {
    phaseRef.current = p
    if (alive.current) setPhaseState(p)
  }
  const writeImages = (fn) => {
    imagesRef.current = fn(imagesRef.current)
    if (alive.current) setImagesState(imagesRef.current)
  }
  const patchImage = (key, patch) => writeImages((list) => list.map((i) => (i.key === key ? { ...i, ...patch } : i)))

  /** Görsel aşaması bitti: yüklenenler soruna eklenir, kutu kapanır. Kutu kapanmışsa yüklenemeyenler bildirimle söylenir. */
  const finishImages = useLatestCallback(() => {
    if (finishedRef.current) return
    finishedRef.current = true
    const problem = problemRef.current
    const uploaded = imagesRef.current.filter((i) => i.status === 'done' && i.meta).map((i) => i.meta)
    const left = imagesRef.current.filter((i) => i.status !== 'done').length
    if (problem && uploaded.length) {
      cb.current.onImagesDone({ ...problem, images: [...(Array.isArray(problem.images) ? problem.images : []), ...uploaded] })
    }
    if (!alive.current) {
      if (left) toast.error(t('or.prob.imgLeftBehind', left))
      return
    }
    cb.current.onClose()
  })

  /** Sıra boşaldıysa ve bekleyen karar yoksa bitirir (yüklenemeyen görsel varken kutu açıksa kullanıcı karar verir). */
  const settle = useLatestCallback(() => {
    const list = imagesRef.current
    if (list.some((i) => i.status === 'queued' || i.status === 'uploading')) return
    if (alive.current && list.some((i) => i.status === 'failed')) return
    finishImages()
  })

  /** Sıradaki görselleri TEK TEK yükler (sorun kaydedildikten sonra). */
  const runUploads = useLatestCallback(async () => {
    const problem = problemRef.current
    if (uploadingRef.current || !problem?.id) return
    uploadingRef.current = true
    try {
      // Sınır: her tur bir görseli sıradan çıkarır; yeniden deneme sırayı yeniden doldurabilir
      for (let guard = 0; guard < MAX_IMAGES_PER_PROBLEM * 4; guard++) {
        const next = imagesRef.current.find((i) => i.status === 'queued')
        if (!next) break
        patchImage(next.key, { status: 'uploading', pct: 0, message: null })
        let res
        try {
          res = await api.oncallRoutines.uploadProblemImage(problem.id, next.file, {
            onProgress: (e) => { if (e?.total) patchImage(next.key, { pct: Math.min(100, Math.round((e.loaded / e.total) * 100)) }) },
          })
        } catch {
          res = { success: false, status: 0, code: 'NETWORK_ERROR' }
        }
        if (res === null) {
          // Oturum bitti (401 yolu uygulamada işlendi) — kalan yüklemeler yapılmaz
          patchImage(next.key, { status: 'failed', message: t('or.prob.imgSessionEnded'), retryable: false })
          return
        }
        if (res?.success) patchImage(next.key, { status: 'done', pct: 100, meta: imageMetaOf(res) })
        else patchImage(next.key, { status: 'failed', message: uploadFailureText(res, t), retryable: uploadRetryable(res) })
      }
    } finally {
      uploadingRef.current = false
    }
    settle()
  })

  useEffect(() => {
    alive.current = true
    if (autoFocus) textareaRef.current?.focus()
    return () => {
      alive.current = false
      for (const i of imagesRef.current) revoke(i.url)
      // Görsel aşamasında kaldırıldı ve yükleme sürmüyor: kalan karar burada verilir (görsel sessizce kaybolmasın)
      if (phaseRef.current === 'images' && !uploadingRef.current) settle()
    }
    // Yalnız ilk açılışta odak (autoFocus sonradan değişirse odak çalınmaz)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function addFiles(files) {
    if (phaseRef.current !== 'form') return
    setImgNote(null)
    for (const file of files) {
      if (imagesRef.current.length + preparingRef.current >= MAX_IMAGES_PER_PROBLEM) {
        setImgNote(t('or.prob.imgLimit', MAX_IMAGES_PER_PROBLEM))
        break
      }
      preparingRef.current += 1
      setPreparing((n) => n + 1)
      let r
      try {
        r = await prepareForUpload(file, t, lang)
      } finally {
        preparingRef.current -= 1
        if (alive.current) setPreparing((n) => Math.max(0, n - 1))
      }
      if (!alive.current) return
      if (!r.ok) { setImgNote(r.message); continue }
      const key = `q${++seq.current}`
      writeImages((list) => [...list, {
        key, file: r.file, name: r.file?.name || '', size: r.file?.size ?? null, url: previewUrl(r.file), status: 'queued', pct: 0,
      }])
    }
    if (inputRef.current) inputRef.current.value = ''
  }

  function removeQueued(key) {
    const hit = imagesRef.current.find((i) => i.key === key)
    revoke(hit?.url)
    writeImages((list) => list.filter((i) => i.key !== key))
    setImgNote(null)
  }

  async function pasteFromClipboard() {
    try {
      const file = await readClipboardImage(PROBLEM_CAPTURE_ID)
      await addFiles([file])
    } catch (e) {
      if (alive.current) setImgNote(clipboardProblemText(e, t))
    }
  }

  async function save() {
    if (phaseRef.current !== 'form') return
    const text = note.trim()
    const bad = problemNoteProblem(text, t, lang)
    if (bad) {
      setNoteProblem(bad)
      textareaRef.current?.focus()
      return
    }
    if (preparingRef.current > 0) { setImgNote(t('or.prob.imgWaitPrep')); return }
    setNoteProblem(null)
    setSevProblem(null)
    setUnsure(false)
    setPhase('saving')
    const body = { date, severity, note: text, mark_done: true }
    if (item) Object.assign(body, { task_id: item.task_id, period_kind: item.period_kind, period_key: item.period_key })
    let res
    try {
      res = await api.oncallRoutines.createProblem(teamId, body)
    } catch {
      res = { success: false, status: 0, code: 'NETWORK_ERROR' }
    }
    if (res === null) { setPhase('form'); return }           // oturum bitti
    if (!res?.success) {
      setPhase('form')
      if (!alive.current) return
      const fields = serverFieldErrors(res)
      if (fields?.note || fields?.severity) {
        if (fields.note) { setNoteProblem(fields.note); textareaRef.current?.focus() }
        if (fields.severity) setSevProblem(fields.severity)
        return
      }
      if (isUnsureResult(res)) { setUnsure(true); return }
      toast.error(serverText(res, t, 'or.prob.saveRefused'), { details: res })
      return
    }
    const data = res.data && typeof res.data === 'object' ? res.data : {}
    const problem = data.problem && typeof data.problem === 'object' ? data.problem : null
    problemRef.current = problem
    cb.current.onCreated(data)
    const queued = imagesRef.current.filter((i) => i.status === 'queued')
    if (!problem?.id || queued.length === 0) {
      setPhase('form')
      if (alive.current) cb.current.onClose()
      return
    }
    setPhase('images')
    runUploads()
  }

  function retryImage(key) {
    patchImage(key, { status: 'queued', message: null, pct: 0 })
    runUploads()
  }
  function dropImage(key) {
    const hit = imagesRef.current.find((i) => i.key === key)
    revoke(hit?.url)
    writeImages((list) => list.filter((i) => i.key !== key))
    settle()
  }

  async function requestClose() {
    const p = phaseRef.current
    if (p === 'saving') return
    if (p === 'images') {
      if (uploadingRef.current || imagesRef.current.some((i) => i.status === 'queued' || i.status === 'uploading')) return
      const failed = imagesRef.current.filter((i) => i.status === 'failed').length
      if (failed) {
        const ok = await showConfirm({
          title: t('or.prob.imgLeaveTitle'), message: t('or.prob.imgLeaveBody', failed),
          confirmText: t('or.prob.imgLeaveConfirm'), cancelText: t('or.prob.keepEditing'), variant: 'danger',
        })
        if (!ok) return
      }
      finishImages()
      return
    }
    if (note.trim() || imagesRef.current.length) {
      const ok = await showConfirm({
        title: t('or.prob.discardTitle'), message: t('or.prob.discardBody'),
        confirmText: t('or.prob.discardConfirm'), cancelText: t('or.prob.keepEditing'), variant: 'danger',
      })
      if (!ok) return
    }
    cb.current.onClose()
  }

  function onKeyDown(e) {
    if (e.isComposing || e.keyCode === 229) return
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault()
      e.stopPropagation()
      save()
      return
    }
    if (e.key === 'Escape') {
      e.preventDefault()
      e.stopPropagation()
      requestClose()
    }
  }

  const saving = phase === 'saving'
  const locked = phase !== 'form'
  const textLen = note.trim().length
  const markDone = !!item && (item.state === 'PENDING' || item.state === 'MISSED')
  const room = MAX_IMAGES_PER_PROBLEM - images.length - preparing
  const mod = isMacPlatform() ? '⌘' : 'Ctrl'

  // ── Görsel aşaması: sorun kaydedildi, görseller sırayla yükleniyor ───────────────────────────────────────────────
  if (phase === 'images') {
    const doneN = images.filter((i) => i.status === 'done').length
    const failedN = images.filter((i) => i.status === 'failed').length
    const busy = images.some((i) => i.status === 'queued' || i.status === 'uploading')
    return (
      <div data-slot="or-problem-composer" data-phase="images" role="group" aria-label={t('or.prob.composerLabel')}
        onKeyDown={onKeyDown}
        className={cn('flex min-w-0 flex-col gap-3 rounded-lg border bg-muted/30', compact ? 'p-2.5' : 'p-3')}>
        <p className="text-sm font-medium" role="status" data-slot="or-problem-composer-status">
          {busy ? t('or.prob.savedUploading', doneN, images.length) : failedN ? t('or.prob.savedSomeLeft', failedN) : t('or.prob.savedDone')}
        </p>
        <ul className="flex min-w-0 flex-col gap-2" data-slot="or-problem-upload-list">
          {images.map((img, i) => (
            <li key={img.key} className="flex min-w-0 flex-wrap items-center gap-2 rounded-md border bg-card p-2"
              data-slot="or-problem-upload" data-status={img.status}>
              <Thumb url={img.url} alt="" small />
              <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                <span className="text-xs font-medium [overflow-wrap:anywhere]">{t('or.prob.imageLabel', i + 1)}</span>
                <span className={cn('text-xs [overflow-wrap:anywhere]', img.status === 'failed' ? 'text-destructive' : 'text-muted-foreground')}
                  role={img.status === 'failed' ? 'alert' : undefined}>
                  {img.status === 'uploading' ? t('or.prob.imgUploadingPct', img.pct || 0)
                    : img.status === 'queued' ? t('or.prob.imgQueued')
                      : img.status === 'done' ? t('or.prob.imgUploaded')
                        : img.message}
                </span>
              </div>
              {img.status === 'uploading' && <Spinner />}
              {img.status === 'failed' && (
                <div className="flex flex-wrap gap-2">
                  {img.retryable && (
                    <Button type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10"
                      data-slot="or-problem-upload-retry" onClick={() => retryImage(img.key)}>
                      <RefreshCw aria-hidden="true" />{t('or.prob.imgRetry')}
                    </Button>
                  )}
                  <Button type="button" size="sm" variant="ghost" className="pointer-coarse:min-h-10"
                    data-slot="or-problem-upload-drop" onClick={() => dropImage(img.key)}>
                    {t('or.prob.imgDrop')}
                  </Button>
                </div>
              )}
            </li>
          ))}
        </ul>
        <div className="flex justify-end">
          <Button type="button" size="sm" variant="outline" disabled={busy} className="pointer-coarse:min-h-10"
            data-slot="or-problem-composer-close" onClick={requestClose}>
            {t('or.prob.close')}
          </Button>
        </div>
      </div>
    )
  }

  // ── Yazma aşaması ────────────────────────────────────────────────────────────────────────────────────────────────
  return (
    <div data-slot="or-problem-composer" data-phase={phase} data-drag={drag ? 'true' : undefined}
      role="group" aria-label={t('or.prob.composerLabel')} aria-busy={saving ? 'true' : undefined}
      onKeyDown={onKeyDown}
      onPaste={(e) => {
        if (locked) return
        const f = fileFromPaste(e.clipboardData)
        if (!f) return
        e.preventDefault()
        addFiles([f])
      }}
      onDragOver={(e) => { if (!locked && isFileDrag(e)) { e.preventDefault(); setDrag(true) } }}
      onDragLeave={() => setDrag(false)}
      onDrop={(e) => {
        if (!isFileDrag(e)) return
        e.preventDefault()
        setDrag(false)
        if (!locked) addFiles(Array.from(e.dataTransfer?.files || []))
      }}
      className={cn('flex min-w-0 flex-col rounded-lg border bg-muted/30 transition-shadow', compact ? 'gap-2.5 p-2.5' : 'gap-3 p-3',
        drag && 'border-primary ring-2 ring-primary/30')}>
      {!compact && (
        <div className="flex min-w-0 items-start justify-between gap-2">
          <div className="flex min-w-0 flex-col gap-0.5">
            <h4 className="text-sm font-semibold">{item ? t('or.prob.titleTask') : t('or.prob.titleGeneral')}</h4>
            {item?.title && <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{item.title}</p>}
          </div>
          <Button type="button" variant="ghost" size="icon" aria-label={t('or.prob.cancel')} disabled={saving}
            className="-my-1 size-8 shrink-0 pointer-coarse:size-10" onClick={requestClose}>
            <X aria-hidden="true" />
          </Button>
        </div>
      )}

      <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5">
        <span id={sevLabelId} className="text-xs font-medium text-muted-foreground">{t('or.prob.severity')}</span>
        <SeverityToggle value={severity} onChange={(v) => { setSeverity(v); setSevProblem(null) }} labelledBy={sevLabelId} disabled={saving} />
        {sevProblem && <p className="basis-full text-xs text-destructive" role="alert">{sevProblem}</p>}
      </div>

      <div className="flex min-w-0 flex-col gap-1.5">
        <Label htmlFor={noteId} className="text-xs text-muted-foreground">{t('or.prob.noteLabel')}</Label>
        <Textarea ref={textareaRef} id={noteId} value={note} disabled={saving} data-slot="or-problem-note"
          rows={compact ? 2 : 3} placeholder={t('or.prob.notePlaceholder')}
          aria-invalid={noteProblem ? 'true' : undefined}
          aria-describedby={noteProblem ? `${noteHintId} ${noteProblemId}` : noteHintId}
          className="min-h-20 resize-y [overflow-wrap:anywhere]"
          onChange={(e) => { setNote(e.target.value); if (noteProblem) setNoteProblem(null); if (unsure) setUnsure(false) }} />
        <div className="flex min-w-0 flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
          <p id={noteHintId} className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
            {markDone ? t('or.prob.markDoneHint') : t('or.prob.noteHint')}
          </p>
          {textLen >= COUNTER_FROM && (
            <p className={cn('text-xs tabular-nums', textLen > PROBLEM_NOTE_MAX ? 'font-semibold text-destructive' : 'text-muted-foreground')}
              data-slot="or-problem-note-count">
              {t('or.prob.noteCount', new Intl.NumberFormat(lang === 'en' ? 'en-GB' : 'tr-TR').format(textLen),
                new Intl.NumberFormat(lang === 'en' ? 'en-GB' : 'tr-TR').format(PROBLEM_NOTE_MAX))}
            </p>
          )}
        </div>
        {noteProblem && (
          <p id={noteProblemId} className="text-sm text-destructive [overflow-wrap:anywhere]" role="alert" data-slot="or-problem-note-problem">
            {noteProblem}
          </p>
        )}
      </div>

      {/* Görseller: kayıttan SONRA tek tek yüklenir; burada yalnız hazırlanıp sıraya girer */}
      <div className="flex min-w-0 flex-col gap-2" data-slot="or-problem-composer-images">
        {images.length > 0 && (
          <ul className="flex flex-wrap gap-2">
            {images.map((img, i) => (
              <li key={img.key} className="relative" data-slot="or-problem-queued-image" title={img.name || undefined}>
                <Thumb url={img.url} alt={t('or.prob.imageLabel', i + 1)} />
                {img.size != null && (
                  <span className="mt-0.5 block text-center text-[11px] text-muted-foreground tabular-nums">{formatBytes(img.size, lang)}</span>
                )}
                {/* Dokunmatik vuruş alanı 40 px: ::after kenarlığın İÇİNDEKİ dolgu kutusuna göre konumlanır (24 − 2 + 2 × 9) */}
                <Button type="button" variant="outline" size="icon" disabled={saving}
                  aria-label={t('or.prob.imgRemoveQueued', i + 1)} data-slot="or-problem-queued-remove"
                  className="absolute -top-2 -right-2 size-6 rounded-full bg-background shadow-sm pointer-coarse:after:absolute pointer-coarse:after:-inset-[9px] pointer-coarse:after:content-['']"
                  onClick={() => removeQueued(img.key)}>
                  <X aria-hidden="true" className="size-3" />
                </Button>
              </li>
            ))}
          </ul>
        )}
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <Input ref={inputRef} type="file" accept={IMAGE_ACCEPT} multiple hidden tabIndex={-1} aria-hidden="true"
            onChange={(e) => { const list = Array.from(e.target.files || []); if (list.length) addFiles(list) }} />
          <Button type="button" variant="outline" size="sm" disabled={saving || room <= 0} className="pointer-coarse:min-h-10"
            data-slot="or-problem-image-add" onClick={() => inputRef.current?.click()}>
            {preparing > 0 ? <Spinner /> : <ImagePlus aria-hidden="true" />}
            {preparing > 0 ? t('or.prob.imgPreparing') : t('or.prob.imgAdd')}
          </Button>
          {canReadClipboard() && (
            <Button type="button" variant="ghost" size="sm" disabled={saving || room <= 0} className="pointer-coarse:min-h-10"
              data-slot="or-problem-image-paste" onClick={pasteFromClipboard}>
              <ClipboardPaste aria-hidden="true" />{t('or.prob.imgPaste')}
            </Button>
          )}
          <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
            {room > 0 ? t('or.prob.imgHint', pasteKey(), room) : t('or.prob.imgLimit', MAX_IMAGES_PER_PROBLEM)}
          </span>
        </div>
        {imgNote && <p className="text-sm text-destructive [overflow-wrap:anywhere]" role="alert" data-slot="or-problem-image-note">{imgNote}</p>}
      </div>

      {unsure && (
        <AlertBanner tone="warning" title={t('or.prob.unsureTitle')}>{t('or.prob.unsureBody')}</AlertBanner>
      )}

      <div className="flex min-w-0 flex-wrap items-center justify-end gap-2">
        <span className="me-auto hidden items-center gap-1 text-xs text-muted-foreground pointer-fine:inline-flex">
          <Kbd>{mod}</Kbd>+<Kbd>Enter</Kbd>
          <span className="sr-only">{t('or.prob.saveShortcut')}</span>
        </span>
        <Button type="button" variant="ghost" size="sm" disabled={saving} className="pointer-coarse:min-h-10"
          data-slot="or-problem-cancel" onClick={requestClose}>
          {t('or.prob.cancel')}
        </Button>
        <Button type="button" size="sm" disabled={saving || preparing > 0} className="pointer-coarse:min-h-10"
          data-slot="or-problem-save" onClick={save}>
          {saving ? <Spinner /> : <Save aria-hidden="true" />}
          {saving ? t('or.prob.saving') : t('or.prob.save')}
        </Button>
      </div>
    </div>
  )
}

/** Sıradaki / yüklenen görselin küçük resmi (önizleme adresi yoksa simge). */
function Thumb({ url, alt, small = false }) {
  const box = small ? 'size-10' : 'h-16 w-20'
  if (!url) {
    return (
      <span className={cn('flex shrink-0 items-center justify-center rounded-md border bg-muted text-muted-foreground', box)}>
        <ImageIcon aria-hidden="true" className="size-5" />{alt && <span className="sr-only">{alt}</span>}
      </span>
    )
  }
  return <img src={url} alt={alt} className={cn('shrink-0 rounded-md border object-cover', box)} />
}

export default ProblemComposer
