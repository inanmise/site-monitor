import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { ChevronsLeftRight, CircleAlert, Columns2 } from 'lucide-react'
import { useT, useDateLocale } from '../../../../i18n/index.jsx'
import ModalShell from '../../../ui/ModalShell.jsx'
import StatusBlock from '../../../ui/StatusBlock.jsx'
import { LoadingBlock } from '../../../ui/Progress.jsx'
import { formatSize } from '../resourceScanModel.js'
import { TOUCH, WRAP } from '../ResourceScanParts.jsx'
import * as engine from './engineClient.js'
import { engineProblemText, growthPctOf, pctText, resultMeta, savingPctOf, similarityText } from './imageToolModel.js'
import { Button } from '@/components/shadcn/button'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'

/** Önizleme tuvalinin tavanı: çok büyük görsel tuvale KÜÇÜLTÜLEREK çizilir (bellek) — söylenir. */
export const PREVIEW_MAX_SIDE = 4096
/** Çözme tavanı: motor görseli uzun kenarı en çok bu kadar olacak şekilde çözer (50 MP özgün tam çözülmez — bellek). */
export const PREVIEW_DECODE_MAX_SIDE = 2560
export const PREVIEW_MAX_PIXELS = 12_000_000
const ZOOMS = ['fit', '100', '200']
const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v))

/** Tuval boyutu: doğal ölçü, tavanı aşıyorsa oran korunarak küçültülür. */
export function previewSize(w, h) {
  const W = Math.max(1, Math.round(Number(w) || 1))
  const H = Math.max(1, Math.round(Number(h) || 1))
  const k = Math.min(1, PREVIEW_MAX_SIDE / W, PREVIEW_MAX_SIDE / H, Math.sqrt(PREVIEW_MAX_PIXELS / (W * H)))
  return { width: Math.max(1, Math.floor(W * k)), height: Math.max(1, Math.floor(H * k)), scaled: k < 1 }
}

/** Bölücü konumu (0–100) klavyeyle: oklar ±2 (Shift ±10), PageUp / PageDown ±10, Home 0, End 100; başka tuş → null. */
export function dividerKey(key, pos, shift = false) {
  if (key === 'ArrowLeft' || key === 'ArrowDown') return clamp(pos - (shift ? 10 : 2), 0, 100)
  if (key === 'ArrowRight' || key === 'ArrowUp') return clamp(pos + (shift ? 10 : 2), 0, 100)
  if (key === 'PageDown') return clamp(pos - 10, 0, 100)
  if (key === 'PageUp') return clamp(pos + 10, 0, 100)
  if (key === 'Home') return 0
  if (key === 'End') return 100
  return null
}

/**
 * Görseli kutuya KENDİ oranıyla sığdırır (zarflama): kapla / küçük resim yöntemlerinde çıktının oranı özgününkinden farklıdır —
 * özgün çıktının oranına GERİLMEZ, ortalanır, kalan alan boş kalır. Dönüş: `{ x, y, w, h }` (tuval pikseli).
 */
export function fitRect(srcW, srcH, boxW, boxH) {
  const sw = Math.max(1, Number(srcW) || 1)
  const sh = Math.max(1, Number(srcH) || 1)
  const bw = Math.max(1, Number(boxW) || 1)
  const bh = Math.max(1, Number(boxH) || 1)
  const k = Math.min(bw / sw, bh / sh)
  const w = Math.max(1, Math.round(sw * k))
  const h = Math.max(1, Math.round(sh * k))
  return { x: Math.round((bw - w) / 2), y: Math.round((bh - h) / 2), w, h }
}

function draw(canvas, bitmap, size, rect = null) {
  if (!canvas || !bitmap) return false
  try {
    canvas.width = size.width
    canvas.height = size.height
    const c = canvas.getContext?.('2d')
    if (!c) return false
    c.clearRect(0, 0, size.width, size.height)
    const r = rect || { x: 0, y: 0, w: size.width, h: size.height }
    c.drawImage(bitmap, r.x, r.y, r.w, r.h)
    return true
  } catch {
    return false
  }
}

const closeQuietly = (b) => { try { b?.close?.() } catch { /* zaten kapalı */ } }

/**
 * ÖNCE / SONRA karşılaştırması (2026-11-14, kullanıcı kararı 5): sürüklenebilir bölücü (fare + dokunma; klavye: oklar,
 * Home / End), yakınlaştırma (sığdır / %100 / %200), kaydırarak gezinme, iki bayt boyutu ve motor ölçtüyse benzerlik
 * değeri. Görseller `<canvas>` üzerine çizilir (`decodeForPreview` — `<img src=blob:>` YOK, CSP değişmez). Solda ÖNCE
 * (özgün), sağda SONRA (çıktı); %100 = ÇIKTININ gerçek pikselleri, özgün aynı kutuya KENDİ oranıyla (zarflanarak) sığar —
 * kapla / küçük resimde gerilmez. Çözme `PREVIEW_DECODE_MAX_SIDE` tavanıyla yapılır (bellek). Pencere kapanınca
 * bit eşlemler kapatılır (`ImageBitmap.close`) ve süren çözme iptal edilir.
 */
export default function ImageCompareDialog({ title, original, result, summary, onClose }) {
  const t = useT()
  const locale = useDateLocale()
  const [state, setState] = useState({ status: 'loading', problem: null, scaled: false })
  const [zoom, setZoom] = useState('fit')
  const [pos, setPos] = useState(50)
  const [box, setBox] = useState({ w: 0, h: 0 })
  const bitmaps = useRef({ before: null, after: null })
  const sizes = useRef({ before: null, after: null })
  const beforeRef = useRef(null)
  const afterRef = useRef(null)
  const viewRef = useRef(null)
  const stageRef = useRef(null)
  const drag = useRef(null)

  // Çözme: iki Blob → bit eşlem; kapanışta / sökülünce iptal + close()
  useEffect(() => {
    const ctl = new AbortController()
    let cancelled = false
    ;(async () => {
      // allSettled: biri başarısız ya da iptal olsa da ÖTEKİNİN çözülmüş bit eşlemi kapatılır (bellek sızmaz)
      const settled = await Promise.allSettled([
        engine.decodeForPreview(original, { signal: ctl.signal, maxSide: PREVIEW_DECODE_MAX_SIDE }),
        engine.decodeForPreview(result, { signal: ctl.signal, maxSide: PREVIEW_DECODE_MAX_SIDE }),
      ])
      const [b, a] = settled.map((x) => (x.status === 'fulfilled' ? x.value : null))
      if (cancelled || !b || !a) {
        closeQuietly(b)
        closeQuietly(a)
        if (cancelled) return
        const e = settled.find((x) => x.status === 'rejected')?.reason
        setState({ status: 'failed', problem: engine.isEngineError(e) ? { code: e.code, params: e.params } : { code: 'other', params: {} }, scaled: false })
        return
      }
      bitmaps.current = { before: b, after: a }
      // İki tuval de ÇIKTININ (çözülmüş) ölçüsündedir; özgün o kutuya KENDİ oranıyla sığar (kapla / küçük resimde gerilmez).
      // Ekrandaki %100 yine çıktının gerçek pikselleridir (CSS ölçüsü — aşağıda natW / natH).
      const outW = summary?.width || a.width
      const box = previewSize(a.width, a.height)
      const origW = summary?.original?.width || b.width
      const origH = summary?.original?.height || b.height
      sizes.current = { before: box, after: box, beforeRect: fitRect(origW, origH, box.width, box.height) }
      const decodedSmaller = a.width < outW || b.width < origW
      setState({ status: 'ready', problem: null, scaled: box.scaled || decodedSmaller })
    })()
    return () => {
      cancelled = true
      ctl.abort()
      const { before, after } = bitmaps.current
      try { before?.close?.() } catch { /* zaten kapalı */ }
      try { after?.close?.() } catch { /* zaten kapalı */ }
      bitmaps.current = { before: null, after: null }
    }
    // Özet (ölçüler) aynı Blob çiftine aittir; yalnız Blob'lar değişince yeniden çözülür
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [original, result])

  // Çizim: tuvaller hazır olunca bir kez (yakınlaştırma yalnız CSS boyutunu değiştirir)
  useLayoutEffect(() => {
    if (state.status !== 'ready') return
    draw(afterRef.current, bitmaps.current.after, sizes.current.after)
    draw(beforeRef.current, bitmaps.current.before, sizes.current.before, sizes.current.beforeRect)
  }, [state.status])

  // Görünüm alanının ölçüsü ("sığdır" için)
  useLayoutEffect(() => {
    const el = viewRef.current
    if (!el) return undefined
    const measure = () => setBox({ w: el.clientWidth, h: el.clientHeight })
    measure()
    if (typeof ResizeObserver !== 'function') return undefined
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [state.status])

  // %100 = çıktının GERÇEK pikselleri (önizleme küçültülerek çözülmüş olsa da kutu gerçek ölçüde)
  const natW = summary?.width || bitmaps.current.after?.width || 1
  const natH = summary?.height || bitmaps.current.after?.height || 1
  const fitScale = box.w > 0 && box.h > 0 ? Math.min(1, box.w / natW, box.h / natH) : 1
  const scale = zoom === '200' ? 2 : zoom === '100' ? 1 : fitScale
  const stageW = Math.max(1, Math.round(natW * scale))
  const stageH = Math.max(1, Math.round(natH * scale))

  const moveTo = useCallback((clientX) => {
    const r = stageRef.current?.getBoundingClientRect?.()
    if (!r || !r.width) return
    setPos(clamp(((clientX - r.left) / r.width) * 100, 0, 100))
  }, [])
  const onHandleDown = (e) => {
    e.preventDefault()
    e.stopPropagation()
    drag.current = { kind: 'divider', id: e.pointerId }
    try { e.currentTarget.setPointerCapture?.(e.pointerId) } catch { /* yakalama yok */ }
    moveTo(e.clientX)
  }
  const onHandleMove = (e) => { if (drag.current?.kind === 'divider') moveTo(e.clientX) }
  const onHandleUp = (e) => {
    if (drag.current?.kind !== 'divider') return
    drag.current = null
    try { e.currentTarget.releasePointerCapture?.(e.pointerId) } catch { /* yakalama yok */ }
  }
  // Fareyle sürükleyerek gezinme (dokunmatikte doğal kaydırma zaten var)
  const onStageDown = (e) => {
    if (e.pointerType !== 'mouse' || e.button !== 0 || !viewRef.current) return
    drag.current = { kind: 'pan', x: e.clientX, y: e.clientY, left: viewRef.current.scrollLeft, top: viewRef.current.scrollTop }
  }
  const onStageMove = (e) => {
    const d = drag.current
    if (d?.kind !== 'pan' || !viewRef.current) return
    viewRef.current.scrollLeft = d.left - (e.clientX - d.x)
    viewRef.current.scrollTop = d.top - (e.clientY - d.y)
  }
  const onStageUp = () => { if (drag.current?.kind === 'pan') drag.current = null }

  const pct = savingPctOf(summary)
  const growth = growthPctOf(summary)
  const sim = similarityText(summary, locale)
  const before = summary?.original?.bytes ?? original?.size ?? null
  const after = summary?.bytes ?? result?.size ?? null

  return (
    <ModalShell open onClose={onClose} title={t('prs.it.cmp.title')} icon={Columns2} size="xl" scrollBody closeLabel={t('app.dismiss')}
      footer={<Button type="button" variant="outline" className={TOUCH} onClick={onClose}>{t('app.dismiss')}</Button>}>
      <div data-slot="prs-it-compare-dialog" data-status={state.status} data-zoom={zoom} className="flex min-w-0 flex-col gap-3">
        <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{title}</p>
        <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
          <dl data-slot="prs-it-cmp-sizes" data-before={before ?? undefined} data-after={after ?? undefined} data-saving={pct ?? undefined} className="m-0 flex min-w-0 flex-wrap items-center gap-x-4 gap-y-1 text-xs tabular-nums">
            <div className="flex gap-1.5"><dt className="text-muted-foreground">{t('prs.it.cmp.before')}</dt><dd className="m-0 font-semibold">{formatSize(before, { locale })}</dd></div>
            <div className="flex gap-1.5"><dt className="text-muted-foreground">{t('prs.it.cmp.after')}</dt><dd className="m-0 font-semibold">{formatSize(after, { locale })}</dd></div>
            {pct !== null && pct > 0 && (
              <div className="flex gap-1.5"><dt className="text-muted-foreground">{t('prs.it.cmp.saving')}</dt><dd data-slot="prs-it-cmp-saving" className="m-0 font-semibold text-success">{pctText(pct, locale)}</dd></div>
            )}
            {growth !== null && (
              <div className="flex gap-1.5"><dt className="text-muted-foreground">{t('prs.it.cmp.larger')}</dt><dd data-slot="prs-it-cmp-larger" className="m-0 font-semibold text-warning">{pctText(growth, locale)}</dd></div>
            )}
            <div data-slot="prs-it-cmp-similarity" data-known={sim ? 'true' : 'false'} className="flex gap-1.5">
              <dt className="text-muted-foreground">{t('prs.it.cmp.similarity')}</dt>
              <dd className={cn('m-0', sim ? 'font-semibold' : 'text-muted-foreground')}>{sim ?? t('prs.it.cmp.similarityUnknown')}</dd>
            </div>
          </dl>
          <ToggleGroup type="single" variant="outline" size="sm" value={zoom} aria-label={t('prs.it.cmp.zoom')} data-slot="prs-it-cmp-zoom"
            onValueChange={(v) => { if (ZOOMS.includes(v)) setZoom(v) }}>
            <ToggleGroupItem value="fit" className="px-3 pointer-coarse:min-h-10">{t('prs.it.cmp.zoomFit')}</ToggleGroupItem>
            <ToggleGroupItem value="100" className="px-3 pointer-coarse:min-h-10">{t('prs.it.cmp.zoom100')}</ToggleGroupItem>
            <ToggleGroupItem value="200" className="px-3 pointer-coarse:min-h-10">{t('prs.it.cmp.zoom200')}</ToggleGroupItem>
          </ToggleGroup>
        </div>

        {state.status === 'failed' ? (
          <div data-slot="prs-it-cmp-failed">
            <StatusBlock tone="danger" icon={CircleAlert} title={t('prs.it.cmp.notReady')} className="py-6"
              description={engineProblemText(state.problem?.code, state.problem?.params, t, locale)} />
          </div>
        ) : (
          <div ref={viewRef} data-slot="prs-it-cmp-view" tabIndex={-1}
            className="relative h-[min(58dvh,34rem)] min-w-0 overflow-auto rounded-md border bg-muted/30">
            {state.status === 'loading' ? <LoadingBlock label={t('prs.it.cmp.loading')} className="h-full" /> : (
              <div className="flex min-h-full min-w-full items-center justify-center">
                <div ref={stageRef} data-slot="prs-it-cmp-stage" onPointerDown={onStageDown} onPointerMove={onStageMove} onPointerUp={onStageUp}
                  onPointerLeave={onStageUp} className={cn('relative shrink-0 select-none', zoom !== 'fit' && 'cursor-grab active:cursor-grabbing')}
                  style={{ width: stageW, height: stageH }}>
                  {/* Alt katman: SONRA (çıktı); üstte solda kırpılı: ÖNCE (özgün) */}
                  <canvas ref={afterRef} data-slot="prs-it-cmp-after" aria-hidden="true" className="absolute inset-0 block size-full" />
                  <div aria-hidden="true" className="absolute inset-y-0 left-0 overflow-hidden" style={{ width: `${pos}%` }}>
                    <canvas ref={beforeRef} data-slot="prs-it-cmp-before" className="block max-w-none" style={{ width: stageW, height: stageH }}
                      data-rect={sizes.current.beforeRect ? [sizes.current.beforeRect.x, sizes.current.beforeRect.y, sizes.current.beforeRect.w, sizes.current.beforeRect.h].join(',') : undefined} />
                  </div>
                  <span aria-hidden="true" className="pointer-events-none absolute top-1.5 left-1.5 rounded bg-background/85 px-1.5 py-0.5 text-[11px] font-semibold text-foreground shadow-xs">{t('prs.it.cmp.before')}</span>
                  <span aria-hidden="true" className="pointer-events-none absolute top-1.5 right-1.5 rounded bg-background/85 px-1.5 py-0.5 text-[11px] font-semibold text-foreground shadow-xs">{t('prs.it.cmp.after')}</span>
                  <div role="slider" tabIndex={0} data-slot="prs-it-cmp-divider" aria-label={t('prs.it.cmp.divider')} aria-orientation="horizontal"
                    aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pos)} aria-valuetext={t('prs.it.cmp.dividerValue', String(Math.round(pos)))}
                    onPointerDown={onHandleDown} onPointerMove={onHandleMove} onPointerUp={onHandleUp} onPointerCancel={onHandleUp}
                    onKeyDown={(e) => {
                      const next = dividerKey(e.key, pos, e.shiftKey)
                      if (next === null) return
                      e.preventDefault()
                      setPos(next)
                    }}
                    className="group absolute inset-y-0 z-10 flex w-10 -translate-x-1/2 cursor-ew-resize touch-none items-center justify-center outline-hidden"
                    style={{ left: `${pos}%` }}>
                    <span aria-hidden="true" className="absolute inset-y-0 left-1/2 w-0.5 -translate-x-1/2 bg-primary shadow-[0_0_0_1px_var(--background)]" />
                    <span aria-hidden="true" className="relative flex size-8 items-center justify-center rounded-full border border-primary bg-background text-primary shadow-sm group-focus-visible:ring-[3px] group-focus-visible:ring-ring/50 pointer-coarse:size-10">
                      <ChevronsLeftRight className="size-4" />
                    </span>
                  </div>
                </div>
              </div>
            )}
          </div>
        )}

        <ul className="m-0 flex list-disc flex-col gap-0.5 pl-4 text-xs text-muted-foreground">
          <li className={WRAP}>{t('prs.it.cmp.hint')}</li>
          {summary && resultMeta(summary, t, locale) && <li className={WRAP}>{resultMeta(summary, t, locale)}</li>}
          {(summary?.cropped || summary?.resized) && <li data-slot="prs-it-cmp-resized" className={WRAP}>{t(summary.cropped ? 'prs.it.cmp.cropped' : 'prs.it.cmp.resized')}</li>}
          {state.scaled && <li data-slot="prs-it-cmp-scaled" className={WRAP}>{t('prs.it.cmp.previewScaled')}</li>}
        </ul>
      </div>
    </ModalShell>
  )
}
