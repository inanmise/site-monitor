import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { Maximize2, RefreshCw, Upload, X } from 'lucide-react'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/shadcn/dialog'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { PHONE_FULLSCREEN } from '../../ui/modalClasses.js'
import {
  DRAG_THRESHOLD, HANDLES, clampRect, drawRect, fitScale, fullRect, isFullRect, keyRect, moveRect, pointInRect, resizeRect,
  sourceRect, toDisplayRect, toSourcePoint,
} from './captureModel.js'
import { mountFrame } from './frameReader.js'

/** Tutamağın seçim kutusundaki yeri (yüzde) ve imleci. */
const HANDLE_POS = {
  nw: ['0%', '0%', 'cursor-nwse-resize'], n: ['50%', '0%', 'cursor-ns-resize'], ne: ['100%', '0%', 'cursor-nesw-resize'],
  e: ['100%', '50%', 'cursor-ew-resize'], se: ['100%', '100%', 'cursor-nwse-resize'], s: ['50%', '100%', 'cursor-ns-resize'],
  sw: ['0%', '100%', 'cursor-nesw-resize'], w: ['0%', '50%', 'cursor-ew-resize'],
}

/**
 * KIRPMA PENCERESİ (2026-10-18): paylaşılan yüzeyden alınan kare kutuya sığdırılır; kullanıcı yüklenecek alanı
 * sürükleyerek seçer (fare, kalem, dokunma), sekiz tutamakla boyutlandırır, içinden sürükleyerek taşır. Klavye: oklar
 * 1 px (Shift 10 px) taşır, Alt / Option + oklar boyutlandırır, Enter yükler. "Tümünü seç", canlı ölçü (kaynak
 * pikselleri), "Yeniden çek" (aynı paylaşımdan yeni kare — seçim korunur, yeni kareye sığdırılır), "Vazgeç", "Bu alanı
 * yükle". Seçim modelde (captureModel) kaynak pikselleriyle tutulur; yükleme tam sayı bölgeyle yapılır.
 */
export default function CaptureCropDialog({
  open, frame, label, busy = false, retaking = false, error = null, onRetake, onConfirm, onCancel, returnFocusRef,
}) {
  const t = useT()
  const W = frame?.width || 0
  const H = frame?.height || 0
  const [rect, setRect] = useState(() => fullRect(W, H))
  const rectRef = useRef(rect)
  rectRef.current = rect
  // Kutu ve kare yuvası DURUM olarak tutulur: içerik portal ile bir tur sonra bağlanır (ref ilk etkide boş kalırdı)
  const [boxEl, setBoxEl] = useState(null)
  const stageRef = useRef(null)
  const [hostEl, setHostEl] = useState(null)
  const drag = useRef(null)
  const [box, setBox] = useState({ w: 0, h: 0 })
  const keysId = useId()

  // Yeni kare (yeniden çek): seçim korunur, yeni ölçüye sığdırılır
  const dims = useRef({ W, H })
  useEffect(() => {
    if (dims.current.W === W && dims.current.H === H) return
    dims.current = { W, H }
    setRect((r) => clampRect(r, W, H))
  }, [W, H])

  // Kutu ölçüsü (görüntü buna sığdırılır)
  useLayoutEffect(() => {
    const el = boxEl
    if (!el) return undefined
    const measure = () => setBox((b) => {
      const w = el.clientWidth
      const h = el.clientHeight
      return b.w === w && b.h === h ? b : { w, h }
    })
    measure()
    if (typeof ResizeObserver === 'undefined') return undefined
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [boxEl])

  // Karenin kendi tuvali sahnede gösterilir (ikinci kopya yok)
  useLayoutEffect(() => { mountFrame(frame, hostEl) }, [frame, hostEl])

  const scale = fitScale(W, H, box.w, box.h)
  const dw = Math.max(1, Math.floor(W * scale))
  const dh = Math.max(1, Math.floor(H * scale))
  const shown = toDisplayRect(rect, dw / (W || 1))
  const out = sourceRect(rect, W, H)
  const full = isFullRect(rect, W, H)
  const sizeText = t('te.cap.size', out.w, out.h, W, H)

  // Ekran okuyucuya ölçü SÜRÜKLEME BİTİNCE söylenir (inceleme 2026-10-18): canlı bölge her işaretçi hareketinde
  // değişiyordu — sürükleme boyunca onlarca duyuru. Klavye, "Tümünü seç" ve yeni kare hemen söylenir.
  const [announced, setAnnounced] = useState(sizeText)
  const sizeRef = useRef(sizeText)
  sizeRef.current = sizeText
  useEffect(() => {
    if (!drag.current) setAnnounced(sizeText)
  }, [sizeText])

  const srcPoint = useCallback((e) => {
    const el = stageRef.current
    const b = el?.getBoundingClientRect?.() || { left: 0, top: 0, width: 0 }
    const s = b.width > 0 && W > 0 ? b.width / W : 1
    return toSourcePoint({ x: e.clientX - b.left, y: e.clientY - b.top }, s)
  }, [W])

  function onPointerDown(e) {
    if (busy || !W || !H) return
    if (e.pointerType === 'mouse' && e.button !== 0) return
    const pt = srcPoint(e)
    const handle = e.target?.closest?.('[data-handle]')?.getAttribute('data-handle') || null
    const r = rectRef.current
    const mode = handle ? 'resize' : pointInRect(pt, r) && !isFullRect(r, W, H) ? 'move' : 'draw'
    drag.current = { mode, handle, start: pt, startRect: r, cx: e.clientX, cy: e.clientY, moved: false, id: e.pointerId }
    try { e.currentTarget.setPointerCapture?.(e.pointerId) } catch { /* jsdom */ }
    e.preventDefault()
    stageRef.current?.focus?.({ preventScroll: true })
  }

  function onPointerMove(e) {
    const d = drag.current
    if (!d) return
    if (!d.moved && Math.hypot(e.clientX - d.cx, e.clientY - d.cy) < DRAG_THRESHOLD) return
    d.moved = true
    const pt = srcPoint(e)
    const dx = pt.x - d.start.x
    const dy = pt.y - d.start.y
    if (d.mode === 'draw') setRect(drawRect(d.start, pt, W, H))
    else if (d.mode === 'move') setRect(moveRect(d.startRect, dx, dy, W, H))
    else setRect(resizeRect(d.startRect, d.handle, dx, dy, W, H))
  }

  function endDrag(e) {
    const d = drag.current
    if (!d) return
    drag.current = null
    try { e.currentTarget.releasePointerCapture?.(d.id) } catch { /* jsdom */ }
    setAnnounced(sizeRef.current)
  }

  function onKeyDown(e) {
    if (e.key === 'Enter' && !busy) {
      e.preventDefault()
      onConfirm?.(sourceRect(rectRef.current, W, H))
      return
    }
    const next = keyRect(rectRef.current, e.key, { shift: e.shiftKey, alt: e.altKey }, W, H)
    if (!next) return
    e.preventDefault()
    setRect(next)
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o && !busy) onCancel?.() }}>
      <DialogContent showCloseButton={false} data-slot="te-cap-dialog"
        onCloseAutoFocus={(e) => { if (returnFocusRef?.current) { e.preventDefault(); returnFocusRef.current.focus() } }}
        onOpenAutoFocus={(e) => { e.preventDefault(); stageRef.current?.focus?.({ preventScroll: true }) }}
        className={cn('flex h-[92dvh] w-[96vw] max-w-[96vw] flex-col gap-3 p-4 sm:max-w-[min(96vw,1400px)] max-sm:w-full', PHONE_FULLSCREEN)}>
        <div className="flex flex-wrap items-start justify-between gap-2">
          <DialogTitle className="min-w-0 flex-1 text-base leading-snug [overflow-wrap:anywhere] max-sm:basis-full">
            {t('te.cap.cropTitle', label)}
          </DialogTitle>
          <Button type="button" variant="outline" size="icon" aria-label={t('te.cap.cropClose')} disabled={busy}
            className="size-9 shrink-0 pointer-coarse:size-10" data-slot="te-cap-close" onClick={() => onCancel?.()}>
            <X aria-hidden="true" />
          </Button>
        </div>
        <DialogDescription className="text-sm text-muted-foreground [overflow-wrap:anywhere]">
          {t('te.cap.cropHelp')}
        </DialogDescription>
        {frame?.uniform && (
          <AlertBanner tone="warning" title={t('te.cap.uniformTitle')}>{t('te.cap.uniformBody')}</AlertBanner>
        )}
        <div ref={setBoxEl} className="relative flex min-h-40 min-w-0 flex-1 items-center justify-center overflow-hidden rounded-md border bg-muted/40"
          data-slot="te-cap-box">
          {/* Sahne: karenin ekrandaki kopyası değil kendisi (tuval); seçim kutusu karartmanın içinde boşluk bırakır */}
          <div ref={stageRef} tabIndex={0} role="group" aria-label={t('te.cap.stageLabel', out.w, out.h)} aria-describedby={keysId}
            data-slot="te-cap-stage" data-rect={`${out.x},${out.y},${out.w},${out.h}`} data-source={`${W}x${H}`}
            data-scale={(dw / (W || 1)).toFixed(4)}
            className="relative shrink-0 touch-none select-none overflow-hidden outline-none focus-visible:ring-2 focus-visible:ring-ring"
            style={{ width: dw, height: dh }}
            onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={endDrag} onPointerCancel={endDrag}
            onKeyDown={onKeyDown}>
            <span id={keysId} className="sr-only">{t('te.cap.stageKeys')}</span>
            <div ref={setHostEl} className="absolute inset-0" data-slot="te-cap-frame-host" />
            <div data-slot="te-cap-selection"
              className={cn('absolute border-2 border-white shadow-[0_0_0_9999px_rgb(0_0_0/0.5)] outline outline-1 outline-black/70',
                full ? 'cursor-crosshair' : 'cursor-move')}
              style={{ left: shown.x, top: shown.y, width: shown.w, height: shown.h }}>
              {HANDLES.map((h) => {
                const [left, top, cursor] = HANDLE_POS[h]
                return (
                  <span key={h} data-handle={h} aria-hidden="true"
                    className={cn('absolute flex size-6 -translate-x-1/2 -translate-y-1/2 items-center justify-center pointer-coarse:size-10', cursor)}
                    style={{ left, top }}>
                    <span className="size-3 rounded-sm border-2 border-white bg-primary shadow-sm" />
                  </span>
                )
              })}
            </div>
          </div>
        </div>
        <p className="text-sm tabular-nums text-muted-foreground [overflow-wrap:anywhere]" aria-hidden="true" data-slot="te-cap-size">
          {sizeText}
        </p>
        <span className="sr-only" aria-live="polite" data-slot="te-cap-size-live">{announced}</span>
        {error && <p className="text-sm text-destructive [overflow-wrap:anywhere]" role="alert" data-slot="te-cap-error">{error}</p>}
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="outline" className="pointer-coarse:min-h-10" disabled={busy || full}
              onClick={() => setRect(fullRect(W, H))} data-slot="te-cap-select-all">
              <Maximize2 aria-hidden="true" /> {t('te.cap.selectAll')}
            </Button>
            {onRetake && (
              <Button type="button" variant="outline" className="pointer-coarse:min-h-10" disabled={busy || retaking}
                onClick={() => onRetake()} data-slot="te-cap-retake">
                <RefreshCw aria-hidden="true" className={retaking ? 'animate-spin motion-reduce:animate-none' : undefined} />
                {retaking ? t('te.cap.retaking') : t('te.cap.retake')}
              </Button>
            )}
          </div>
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="ghost" className="pointer-coarse:min-h-10" disabled={busy} onClick={() => onCancel?.()}
              data-slot="te-cap-cancel">
              {t('te.cancel')}
            </Button>
            <Button type="button" className="pointer-coarse:min-h-10" disabled={busy || !W || !H}
              onClick={() => onConfirm?.(sourceRect(rectRef.current, W, H))} data-slot="te-cap-confirm">
              {busy ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : <Upload aria-hidden="true" />}
              {busy ? t('te.img.preparing') : t('te.cap.confirm')}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  )
}
