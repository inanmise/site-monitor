import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { X, ArrowLeft, ArrowRight, Check, BookOpen, MousePointerClick } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import { placeTooltip, spotlightRect, keyAction, TIP_W, MOBILE_MAX } from './tourEngine.js'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

const FIND_TIMEOUT_MS = 3000
const POLL_MS = 120

// Balon oku: kartın kenarından taşan 45° döndürülmüş kare (eski .tour-arrow); yön = balonun hedefe göre konumu.
const ARROW_BASE = 'absolute size-3 rotate-45 border bg-card'
const ARROW = {
  bottom: '-top-[7px] left-1/2 -ml-1.5 border-r-0 border-b-0',
  top: '-bottom-[7px] left-1/2 -ml-1.5 border-t-0 border-l-0',
  right: '-left-[7px] top-1/2 -mt-1.5 border-t-0 border-r-0',
  left: '-right-[7px] top-1/2 -mt-1.5 border-b-0 border-l-0',
}
// Metin bağlantısı görünümlü eylem (eski .tour-link): shadcn Button `link`, sıkı dolgu.
const LINK = 'h-auto gap-1 px-1 py-0.5 text-[.84em] font-semibold'

/**
 * Spot ışığı + balon (2026-09-13). Karartma DÖRT parça: delik gerçekten boştur, kullanıcı hedefe
 * tıklayabilir (etkileşimli adımlar). Hedef `data-tour` ile bulunur; bulunamazsa adım atlanır
 * (kırık tur yerine eksik adım). Sekme geçişi, menü açma (`sm:nav-reveal`) ve modal kapatma adım
 * meta'sından (`tab`, `before`, `after`) sürülür. Konum her 200 ms ve kaydırma/boyut olayında tazelenir.
 */
export default function TourOverlay({ active, onNext, onPrev, onStop, navigate }) {
  const t = useT()
  const { steps, index, kind } = active
  const step = steps[index]
  const total = steps.length
  const [rect, setRect] = useState(null)        // hedef dikdörtgeni (viewport)
  const [ready, setReady] = useState(false)     // hedef bulundu / merkez adım
  const [tipH, setTipH] = useState(190)
  const tipRef = useRef(null)
  const isMobile = typeof window !== 'undefined' && window.innerWidth <= MOBILE_MAX
  const skipTimer = useRef(null)

  const findTarget = () => (step?.target ? document.querySelector(`[data-tour="${step.target}"]`) : null)

  // ── Adım girişi: sekme/menü hazırlığı, hedefi bekle, kaydır ──
  useEffect(() => {
    setReady(false); setRect(null)
    if (!step) return undefined
    if (step.before?.reveal) { try { window.dispatchEvent(new CustomEvent('sm:nav-reveal', { detail: { tab: step.before.reveal } })) } catch { /* yoksay */ } }
    // Menü dışı hedef: telefonda açık çekmeceyi kapat (masaüstünde Nav hiçbir şey yapmaz)
    if (step.before?.conceal) { try { window.dispatchEvent(new CustomEvent('sm:nav-conceal')) } catch { /* yoksay */ } }
    if (step.tab && step.tab !== currentTab()) navigate?.(step.tab)
    if (step.center) { setReady(true); return undefined }
    let alive = true
    const started = Date.now()
    const tick = () => {
      if (!alive) return
      if (step.requires && !document.querySelector(step.requires)) {
        if (Date.now() - started > FIND_TIMEOUT_MS) { skip(); return }
        skipTimer.current = setTimeout(tick, POLL_MS); return
      }
      const el = findTarget()
      if (el) {
        try { el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' in document.documentElement.style ? 'instant' : 'auto' }) } catch { /* yoksay */ }
        setRect(el.getBoundingClientRect()); setReady(true); return
      }
      if (Date.now() - started > FIND_TIMEOUT_MS) { skip(); return }
      skipTimer.current = setTimeout(tick, POLL_MS)
    }
    tick()
    return () => { alive = false; if (skipTimer.current) clearTimeout(skipTimer.current) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [step?.id])

  function skip() {
    // Hedefi olmayan adım: sona geldiysek bitir, değilse bir sonrakine geç (kısır döngü yok: her adım en çok bir kez atlanır)
    if (index >= total - 1) onStop('done'); else onNext()
  }

  // ── Konum tazeleme: kaydırma / yeniden boyutlandırma / 200 ms ──
  // Yalnız konum/boyut DEĞİŞİNCE yazılır (2026-10-09): her 200 ms'de ve her kaydırma olayında yeni bir DOMRect nesnesi
  // yazmak, hedef yerinde dururken bile balonu ve karartmayı sürekli yeniden çiziyordu.
  useEffect(() => {
    if (!ready || step?.center) return undefined
    const update = () => {
      const el = findTarget()
      if (!el) return
      const r = el.getBoundingClientRect()
      setRect((prev) => (prev && prev.top === r.top && prev.left === r.left && prev.width === r.width && prev.height === r.height ? prev : r))
    }
    const id = setInterval(update, 200)
    window.addEventListener('scroll', update, true)
    window.addEventListener('resize', update)
    return () => { clearInterval(id); window.removeEventListener('scroll', update, true); window.removeEventListener('resize', update) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, step?.id])

  // ── Etkileşimli adım: seçici belirince / olay yayınlanınca ilerle ──
  useEffect(() => {
    if (!ready || !step?.advanceOn) return undefined
    let done = false
    const go = () => { if (!done) { done = true; onNext() } }
    let id = null
    if (step.advanceOn.selector) id = setInterval(() => { if (document.querySelector(step.advanceOn.selector)) go() }, 150)
    if (step.advanceOn.event) window.addEventListener(step.advanceOn.event, go)
    return () => { if (id) clearInterval(id); if (step.advanceOn.event) window.removeEventListener(step.advanceOn.event, go) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, step?.id])

  // ── Adım çıkışı: after (modal / palet kapat) ──
  const prevStep = useRef(step)
  useEffect(() => {
    const p = prevStep.current
    prevStep.current = step
    if (!p || p === step || !p.after) return
    if (p.after.closeModal || p.after.closePalette) {
      try { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })) } catch { /* yoksay */ }
      try { window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })) } catch { /* yoksay */ }
    }
  }, [step])

  // ── Klavye + odak ──
  useEffect(() => {
    const onKey = (e) => {
      const a = keyAction(e)
      if (!a) return
      // Balon içindeki düğmelere Enter normal davransın
      if (a === 'next' && e.key === 'Enter' && tipRef.current?.contains(e.target)) return
      e.preventDefault(); e.stopPropagation()
      if (a === 'next') { if (index >= total - 1) onStop('done'); else if (!step?.advanceOn) onNext() }
      else if (a === 'prev') onPrev()
      else onStop('skip')
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [index, total, step, onNext, onPrev, onStop])
  useEffect(() => { if (ready) setTimeout(() => tipRef.current?.focus(), 30) }, [ready, step?.id])
  useLayoutEffect(() => { if (tipRef.current) setTipH(tipRef.current.offsetHeight || 190) }, [ready, step?.id, rect?.width])

  if (!step || !ready) return null
  const vp = { w: window.innerWidth, h: window.innerHeight }
  const target = step.center ? null : rect
  const pos = isMobile ? null : placeTooltip(target, { w: TIP_W, h: tipH }, vp, step.placement || 'bottom')
  const hole = spotlightRect(target, 6, vp)
  const isLast = index >= total - 1
  const title = t(`tour.s.${step.id}.title`)
  const body = t(`tour.s.${step.id}.body`)
  // doIt.inner: hedefin İÇİNDEKİ gerçek düğme (ör. kartın gerilmiş başlık düğmesi `[data-cert-open]`) — kök tıklaması
  // artık hiçbir şey açmıyorsa (2026-09-27, sertifika kartı yeniden tasarımı) içteki düğmeye tıklanır.
  const doIt = () => {
    if (!step.doIt?.click) return
    const root = document.querySelector(`[data-tour="${step.doIt.click}"]`)
    const el = step.doIt.inner ? root?.querySelector(step.doIt.inner) || root : root
    el?.click()
  }
  const openHelp = () => { try { window.dispatchEvent(new CustomEvent('sm:help', { detail: { section: step.help } })) } catch { /* yoksay */ } }

  return createPortal(
    <div className="tour-overlay" data-tour-active={step.id}>
      {hole ? (
        <>
          <div className="tour-mask" style={{ top: 0, left: 0, width: '100%', height: hole.y }} onClick={() => onStop('skip')} />
          <div className="tour-mask" style={{ top: hole.y + hole.h, left: 0, width: '100%', bottom: 0 }} onClick={() => onStop('skip')} />
          <div className="tour-mask" style={{ top: hole.y, left: 0, width: hole.x, height: hole.h }} onClick={() => onStop('skip')} />
          <div className="tour-mask" style={{ top: hole.y, left: hole.x + hole.w, right: 0, height: hole.h }} onClick={() => onStop('skip')} />
          <div className="tour-ring" style={{ top: hole.y, left: hole.x, width: hole.w, height: hole.h }} aria-hidden="true" />
        </>
      ) : (
        <div className="tour-mask tour-mask--full" onClick={() => onStop('skip')} />
      )}
      {/* Balon: konum tur motorundan (placeTooltip) — dış kap sabit konumlu odak hedefi, yüzey shadcn Card.
          Telefonda (≤640) alt sayfa gibi ekranın altına yapışır ve kayar. */}
      <div ref={tipRef} tabIndex={-1} role="dialog" aria-modal="false" aria-labelledby="tour-title" aria-describedby="tour-body"
        data-slot="tour-tip" data-placement={isMobile ? 'sheet' : pos.placement} data-center={step.center || undefined}
        className={cn('pointer-events-auto fixed z-[3001] rounded-xl outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50',
          isMobile ? 'inset-x-2 bottom-[calc(8px+env(safe-area-inset-bottom))] max-h-[60vh] overflow-auto'
            : 'transition-[top,left] duration-200 ease-out motion-reduce:transition-none')}
        style={isMobile ? undefined : { top: pos.top, left: pos.left, width: TIP_W }}>
        <Card className={cn('relative gap-0 rounded-xl px-4 pt-3.5 pb-3 text-foreground shadow-2xl',
          step.center && 'shadow-[0_24px_64px_rgba(0,0,0,.4)]')}>
          {!isMobile && pos.arrow && <span aria-hidden="true" className={cn(ARROW_BASE, ARROW[pos.placement])} />}
          <div className="mb-1 flex items-center justify-between">
            <span data-slot="tour-progress" className="text-[.76em] font-bold tracking-[.04em] text-muted-foreground" aria-live="polite">{index + 1}/{total}</span>
            <Button type="button" variant="ghost" size="icon-sm" className="-mr-1.5 text-muted-foreground" onClick={() => onStop('skip')} aria-label={t('tour.close')}>
              <X aria-hidden="true" />
            </Button>
          </div>
          <h3 id="tour-title" className="mb-1.5 text-[1.02em] font-bold">{title}</h3>
          <p id="tour-body" className="mb-2.5 text-[.9em] leading-[1.45]">{body}</p>
          {step.advanceOn && (
            <div className="mb-2 flex flex-wrap items-center gap-1.5 text-[.82em] text-primary">
              <MousePointerClick size={14} aria-hidden="true" /> <span>{t('tour.tryIt')}</span>
              {step.doIt && <Button type="button" variant="link" size="sm" className={LINK} onClick={doIt}>{t('tour.doIt')}</Button>}
            </div>
          )}
          <div className="mt-1 mb-2.5">
            <ProgressBar value={index + 1} max={total} size="sm" decorative />
          </div>
          <div className="flex flex-wrap items-center gap-1.5">
            {step.help && (
              <Button type="button" variant="link" size="sm" className={LINK} onClick={openHelp}>
                <BookOpen aria-hidden="true" className="size-[13px]" /> {t('tour.more')}
              </Button>
            )}
            <span className="flex-1" />
            {index > 0 && <Button type="button" variant="secondary" size="sm" onClick={onPrev}><ArrowLeft aria-hidden="true" /> {t('tour.prev')}</Button>}
            {isLast
              ? <Button type="button" size="sm" onClick={() => onStop('done')}><Check aria-hidden="true" /> {t('tour.finish')}</Button>
              : <Button type="button" size="sm" onClick={onNext}>{t('tour.next')} <ArrowRight aria-hidden="true" /></Button>}
          </div>
          {kind !== 'page' && !isLast && (
            <div className="mt-2 flex justify-between gap-2 border-t border-dashed pt-2">
              <Button type="button" variant="link" size="sm" className={LINK} onClick={() => onStop('skip')}>{t('tour.skip')}</Button>
              <Button type="button" variant="link" size="sm" className={cn(LINK, 'font-normal text-muted-foreground')} onClick={() => onStop('never')}>{t('tour.never')}</Button>
            </div>
          )}
        </Card>
      </div>
    </div>,
    document.body,
  )
}

function currentTab() {
  try { return new URLSearchParams(window.location.search).get('tab') || 'dashboard' } catch { return 'dashboard' }
}
