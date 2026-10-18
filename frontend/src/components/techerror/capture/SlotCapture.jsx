import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ClipboardPaste, RefreshCw, ScreenShare } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { useT } from '../../../i18n/index.jsx'
import { useIsMobile } from '../../../hooks/use-mobile.js'
import { markActivity } from '../../../utils/crossTabActivity.js'
import { captureFileName, imageFromDataTransfer, shortcutFamily } from './captureModel.js'
import { CaptureError, canCaptureScreen, captureSession } from './captureSession.js'
import { ClipboardImageError, canReadClipboard, readClipboardImage, renameClipboardFile } from './clipboardImage.js'
import { cropFrameToFile, releaseFrame } from './frameReader.js'

// Kırpma penceresi yalnız "Ekrandan al" kullanılınca yüklenir (lazyChunks kapısı)
const CaptureCropDialog = lazy(() => import('./CaptureCropDialog.jsx'))

/** Yapıştırma ve ekran görüntüsü kısayolları (işletim sistemine göre). */
export function shortcutKeys(family = shortcutFamily()) {
  return family === 'mac'
    ? { paste: '⌘V', snip: '⌘⇧⌃4' }
    : family === 'win' ? { paste: 'Ctrl+V', snip: 'Win+Shift+S' } : { paste: 'Ctrl+V', snip: 'PrtSc' }
}

const isEditable = (el) => !!el && (el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName || ''))

/**
 * Yuvanın HIZLI görsel alma yolları (2026-10-18) — hepsi yuvanın kendi yükleme yoluna (`onFile` = imagePrep →
 * yükleme, aynı sınırlar / notlar / hatalar) varır:
 * <ul>
 *   <li><b>Yapıştır:</b> yuva odaklanabilir; Ctrl/⌘+V ile panodaki görsel yüklenir (metin yapıştırması yok sayılır, kısa
 *   ipucu). Görsel olmayan dosya (dosya yöneticisinden kopyalanan PDF…) yükleme yoluna gider — türünü söyleyen hata yazar.
 *   DOLU yuvaya yapıştırma önce sorar (`confirmReplace`; geri alma yok — inceleme 2026-10-18). "Panodan yapıştır"
 *   `navigator.clipboard.read()` olan tarayıcıda görünür; izin yoksa "Ctrl+V ile yapıştırın" der, ham hata göstermez.</li>
 *   <li><b>Ekrandan al:</b> pencere başına paylaşım oturumu (captureSession); kare kırpma penceresinde seçilen alan PNG
 *   olarak yüklenir. Telefonda ve desteklemeyen tarayıcıda düğme yoktur (bozuk düğme bırakılmaz).</li>
 * </ul>
 * Salt okunur yuvada hiçbiri yoktur; yükleme sürerken yuva yeni görsel almaz (bir yuvada tek yükleme).
 *
 * <p>Dönen parçalar: `zoneProps` (odaklanabilir bırakma alanı, `ref` dahil), `zoneRef`, `pasteButton`, `snipButton`,
 * `pasteMenuItem` (yarım yuvanın "Diğer işlemler" menüsü için), `notes`, `emptyHint`, `dialog`. Kırpma penceresi
 * kapanınca odak "Ekrandan al"a döner; düğme o an kapalıysa (yükleme başladı) yuvanın kendisine.
 */
export function useSlotCapture({
  slotId, label, busy = false, readOnly = false, inPanel = false, hasImage = false, confirmReplace = null, onFile,
}) {
  const t = useT()
  const isMobile = useIsMobile()
  const keys = shortcutKeys()
  const [note, setNote] = useState(null)            // { tone: 'muted' | 'error', text }
  const [frame, setFrame] = useState(null)
  const [cropBusy, setCropBusy] = useState(false)
  const [cropError, setCropError] = useState(null)
  const [retaking, setRetaking] = useState(false)
  const [snipping, setSnipping] = useState(false)
  const [clipBusy, setClipBusy] = useState(false)
  const snipRef = useRef(null)
  const zoneRef = useRef(null)
  const frameRef = useRef(frame)
  frameRef.current = frame
  const onFileRef = useRef(onFile)
  onFileRef.current = onFile
  const busyRef = useRef(busy)
  busyRef.current = busy
  const hasImageRef = useRef(hasImage)
  hasImageRef.current = hasImage
  const confirmRef = useRef(confirmReplace)
  confirmRef.current = confirmReplace
  const asking = useRef(false)
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => { alive.current = false; releaseFrame(frameRef.current) }
  }, [])

  // Kırpma kapanınca odak: "Ekrandan al" (Vazgeç) ya da — yükleme başladıysa düğme kapalıdır — yuvanın kendisi
  const returnFocusRef = useMemo(() => ({
    get current() {
      const b = snipRef.current
      return b && !b.disabled ? b : zoneRef.current || b
    },
  }), [])

  const editable = !readOnly
  const desktop = inPanel || !isMobile
  const showSnip = editable && desktop && canCaptureScreen()
  const showClip = editable && canReadClipboard()

  const give = useCallback((file) => {
    markActivity()
    setNote(null)
    onFileRef.current?.(file)
  }, [])

  const captureNote = useCallback((e) => {
    const kind = e instanceof CaptureError ? e.kind : 'failed'
    if (kind === 'denied') return { tone: 'muted', text: t('te.cap.snipDenied') }
    if (kind === 'busy') return { tone: 'muted', text: t('te.cap.snipBusy') }
    if (kind === 'ended') return { tone: 'muted', text: t('te.cap.snipEnded') }
    if (kind === 'unsupported') return { tone: 'error', text: t('te.cap.snipUnsupported', keys.snip, keys.paste) }
    if (e?.cause?.kind === 'noFrame') return { tone: 'error', text: t('te.cap.snipNoFrame') }
    return { tone: 'error', text: t('te.cap.snipFailed', keys.snip, keys.paste) }
  }, [t, keys.snip, keys.paste])

  /** Dolu yuvada önce sorar (geri alma yok); onaylanınca yükleme hâlâ boşsa verir. */
  const giveAfterConfirm = useCallback(async (file) => {
    if (asking.current) return
    asking.current = true
    let ok = false
    try {
      ok = await confirmRef.current()
    } catch {
      ok = false
    } finally {
      asking.current = false
    }
    if (!ok || !alive.current) return
    if (busyRef.current) { setNote({ tone: 'muted', text: t('te.cap.pasteBusy') }); return }
    give(file)
  }, [give, t])

  const onPaste = useCallback((e) => {
    if (readOnly || isEditable(e.target)) return
    const { file, hasText, image } = imageFromDataTransfer(e.clipboardData)
    if (!file) {
      if (hasText) setNote({ tone: 'muted', text: t('te.cap.pasteNoImage', keys.snip) })
      return
    }
    e.preventDefault()
    if (busyRef.current) { setNote({ tone: 'muted', text: t('te.cap.pasteBusy') }); return }
    // Görsel türü taşımayan dosya kendi adıyla gider: imagePrep türünü baytlardan okur — görselse yüklenir, değilse
    // türünü söyleyen hatayı yazar. Dolu yuvada TÜRÜNE BAKILMADAN önce sorulur (türsüz bir PNG de görseli değiştirir —
    // 2. inceleme turu 2026-10-18)
    const next = image ? renameClipboardFile(file, slotId) : file
    if (hasImageRef.current && typeof confirmRef.current === 'function') { giveAfterConfirm(next); return }
    give(next)
  }, [readOnly, give, giveAfterConfirm, slotId, t, keys.snip])

  async function pasteFromClipboard() {
    if (busyRef.current || clipBusy) return
    setNote(null)
    markActivity()
    setClipBusy(true)
    try {
      const file = await readClipboardImage(slotId)
      if (!alive.current) return
      give(file)
    } catch (e) {
      if (!alive.current) return
      const kind = e instanceof ClipboardImageError ? e.kind : 'failed'
      setNote({ tone: 'muted', text: kind === 'empty' ? t('te.cap.clipEmpty', keys.snip) : kind === 'denied' ? t('te.cap.clipDenied', keys.paste)
        : t('te.cap.clipFailed', keys.paste) })
    } finally {
      if (alive.current) setClipBusy(false)
    }
  }

  async function snip() {
    if (busyRef.current || snipping) return
    setNote(null)
    markActivity()
    setSnipping(true)
    try {
      // Tıklama olayının içinde: paylaşım kapalıysa tarayıcının seçicisi açılır
      const f = await captureSession.grab()
      markActivity()
      if (!alive.current) { releaseFrame(f); return }
      releaseFrame(frameRef.current)
      setCropError(null)
      setFrame(f)
    } catch (e) {
      if (alive.current) setNote(captureNote(e))
    } finally {
      if (alive.current) setSnipping(false)
    }
  }

  async function retake() {
    if (retaking) return
    markActivity()
    setRetaking(true)
    setCropError(null)
    try {
      const f = await captureSession.grab()
      if (!alive.current) { releaseFrame(f); return }
      releaseFrame(frameRef.current)
      setFrame(f)
    } catch (e) {
      if (alive.current) setCropError(captureNote(e).text)
    } finally {
      if (alive.current) setRetaking(false)
    }
  }

  function closeCrop() {
    releaseFrame(frameRef.current)
    setFrame(null)
    setCropError(null)
  }

  async function confirmCrop(rect) {
    const f = frameRef.current
    if (!f || cropBusy) return
    markActivity()
    setCropBusy(true)
    setCropError(null)
    let file
    try {
      file = await cropFrameToFile(f, rect, captureFileName(slotId, 'ekran'))
    } catch {
      if (alive.current) setCropError(t('te.cap.cropFailed'))
      return
    } finally {
      if (alive.current) setCropBusy(false)
    }
    if (!alive.current) return
    closeCrop()
    if (busyRef.current) { setNote({ tone: 'muted', text: t('te.cap.pasteBusy') }); return }
    give(file)
  }

  // Odaklanabilir bırakma alanı: ekran okuyucu yuvanın adını ve yapıştırma kısayolunu duyar
  const zoneProps = editable
    ? { ref: zoneRef, tabIndex: 0, onPaste, role: 'group', 'aria-label': t('te.cap.zoneLabel', label, keys.paste), 'data-paste': 'true' }
    : {}
  const zoneClassName = editable ? 'outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50' : ''

  const pasteButton = showClip ? (
    <Button type="button" size="sm" variant="outline" disabled={busy || clipBusy} onClick={pasteFromClipboard}
      className="pointer-coarse:min-h-10" data-slot="te-image-paste">
      {clipBusy ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : <ClipboardPaste aria-hidden="true" />}
      {t('te.cap.pasteButton')}
      <span className="sr-only">{`: ${label}`}</span>
    </Button>
  ) : null

  // Yarım yuvanın "Diğer işlemler" menüsündeki karşılığı (aynı işlev; erişilebilir ad yuvanın adını taşır)
  const pasteMenuItem = showClip ? {
    label: t('te.cap.pasteButton'),
    ariaLabel: `${t('te.cap.pasteButton')}: ${label}`,
    icon: clipBusy ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : <ClipboardPaste aria-hidden="true" />,
    disabled: busy || clipBusy,
    onClick: pasteFromClipboard,
  } : null

  const snipButton = showSnip ? (
    <Button ref={snipRef} type="button" size="sm" variant="outline" disabled={busy || snipping} onClick={snip}
      className="pointer-coarse:min-h-10" data-slot="te-image-snip">
      {snipping ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : <ScreenShare aria-hidden="true" />}
      {t('te.cap.snipButton')}
      <span className="sr-only">{`: ${label}`}</span>
    </Button>
  ) : null

  const notes = note ? (
    <p className={cn('text-sm [overflow-wrap:anywhere]', note.tone === 'error' ? 'text-destructive' : 'text-muted-foreground')}
      role={note.tone === 'error' ? 'alert' : 'status'} data-slot="te-capture-note" data-tone={note.tone}>
      {note.text}
    </p>
  ) : null

  const emptyHint = editable && desktop ? (
    <span className="text-xs" data-slot="te-image-paste-hint">{t('te.cap.pasteInline', keys.paste)}</span>
  ) : null

  const dialog = frame ? (
    <Suspense fallback={null}>
      <CaptureCropDialog open frame={frame} label={label} busy={cropBusy} retaking={retaking} error={cropError}
        onRetake={retake} onConfirm={confirmCrop} onCancel={closeCrop} returnFocusRef={returnFocusRef} />
    </Suspense>
  ) : null

  return { zoneProps, zoneRef, zoneClassName, pasteButton, snipButton, pasteMenuItem, notes, emptyHint, dialog }
}
