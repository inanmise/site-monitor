import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { X, ArrowLeft, ArrowRight, Check, BookOpen, MousePointerClick } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import { placeTooltip, spotlightRect, keyAction, TIP_W, MOBILE_MAX } from './tourEngine.js'

const FIND_TIMEOUT_MS = 3000
const POLL_MS = 120

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
  useEffect(() => {
    if (!ready || step?.center) return undefined
    const update = () => { const el = findTarget(); if (el) setRect(el.getBoundingClientRect()) }
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
  const doIt = () => { if (step.doIt?.click) document.querySelector(`[data-tour="${step.doIt.click}"]`)?.click() }
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
      <div ref={tipRef} tabIndex={-1} role="dialog" aria-modal="false" aria-labelledby="tour-title" aria-describedby="tour-body"
        className={`tour-tip tour-tip--${isMobile ? 'sheet' : pos.placement}${step.center ? ' tour-tip--center' : ''}`}
        style={isMobile ? undefined : { top: pos.top, left: pos.left, width: TIP_W }}>
        {!isMobile && pos.arrow && <span className="tour-arrow" aria-hidden="true" />}
        <div className="tour-tip-head">
          <span className="tour-progress" aria-live="polite">{index + 1}/{total}</span>
          <button type="button" className="tour-x" onClick={() => onStop('skip')} aria-label={t('tour.close')}><X size={16} /></button>
        </div>
        <h3 id="tour-title" className="tour-title">{title}</h3>
        <p id="tour-body" className="tour-body">{body}</p>
        {step.advanceOn && (
          <div className="tour-try">
            <MousePointerClick size={14} /> <span>{t('tour.tryIt')}</span>
            {step.doIt && <button type="button" className="tour-link" onClick={doIt}>{t('tour.doIt')}</button>}
          </div>
        )}
        <ProgressBar value={index + 1} max={total} size="sm" decorative className="tour-bar" />
        <div className="tour-actions">
          {step.help && <button type="button" className="tour-link" onClick={openHelp}><BookOpen size={13} /> {t('tour.more')}</button>}
          <span className="tour-spacer" />
          {index > 0 && <button type="button" className="btn btn-sm btn-secondary" onClick={onPrev}><ArrowLeft size={13} /> {t('tour.prev')}</button>}
          {isLast
            ? <button type="button" className="btn btn-sm btn-primary" onClick={() => onStop('done')}><Check size={13} /> {t('tour.finish')}</button>
            : <button type="button" className="btn btn-sm btn-primary" onClick={onNext}>{t('tour.next')} <ArrowRight size={13} /></button>}
        </div>
        {kind !== 'page' && !isLast && (
          <div className="tour-foot">
            <button type="button" className="tour-link" onClick={() => onStop('skip')}>{t('tour.skip')}</button>
            <button type="button" className="tour-link tour-link--muted" onClick={() => onStop('never')}>{t('tour.never')}</button>
          </div>
        )}
      </div>
    </div>,
    document.body,
  )
}

function currentTab() {
  try { return new URLSearchParams(window.location.search).get('tab') || 'dashboard' } catch { return 'dashboard' }
}
