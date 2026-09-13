import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { navigateTo } from '../../utils/navigate.js'
import { TOUR_VERSION, MOBILE_MAX, filterSteps, newStepsSince, decideAutoStart, shouldOfferPageTour } from './tourEngine.js'
import { MAIN_STEPS, PAGE_TOURS, CHECKLIST_BY_TAB } from './tourSteps.js'
import TourOverlay from './TourOverlay.jsx'
import WelcomeCard from './WelcomeCard.jsx'

/**
 * Ürün turu sağlayıcısı (2026-09-13). Tek doğruluk kaynağı: sunucudaki `tour_state` (App → prop);
 * burada yalnız "hangi tur açık, hangi adımda" tutulur. Kalıcı yazımlar `onPersist(patch)` ile App'e
 * (App → POST /api/me/tour + yerel ayna). Hook + erken-return tuzağı: bu sağlayıcı App'in auth kapısından
 * SONRA (oturumlu ağaçta) mount edilir; içinde koşullu return yok.
 *
 * ctx: { role, globalAdmin, canWrite, mustChangePwd, tab }
 */
const TourCtx = createContext({ active: null, start: () => {}, stop: () => {}, offerPage: () => false, tourState: null })

export function useTour() { return useContext(TourCtx) }

export function TourProvider({ ctx, tourState, onPersist, children }) {
  const [active, setActive] = useState(null)        // { kind, steps, index, pageId }
  const [welcome, setWelcome] = useState(null)      // 'welcome' | 'whatsnew' | null
  const autoRan = useRef(false)
  const isMobile = typeof window !== 'undefined' && window.innerWidth <= MOBILE_MAX
  const stepCtx = useMemo(() => ({ ...ctx, isMobile }), [ctx, isMobile])

  const mainSteps = useMemo(() => filterSteps(MAIN_STEPS, stepCtx), [stepCtx])

  const persist = useCallback((patch) => { try { onPersist?.(patch) } catch { /* yoksay */ } }, [onPersist])

  const start = useCallback((kind = 'main', opts = {}) => {
    let steps
    if (kind === 'page') {
      const pageId = opts.pageId || ctx?.tab
      steps = filterSteps(PAGE_TOURS[pageId] || [], stepCtx)
      if (!steps.length) return false
      setActive({ kind, steps, index: 0, pageId })
      return true
    }
    if (kind === 'whatsnew') {
      steps = filterSteps(newStepsSince(MAIN_STEPS, tourState?.version), stepCtx)
      if (!steps.length) return false
    } else {
      steps = mainSteps
    }
    setWelcome(null)
    setActive({ kind, steps, index: 0 })
    // Tamamlamış/kapatmış kullanıcı yeniden başlatıp yarıda bırakırsa durum 'started'a DÜŞMEZ —
    // aksi hâlde bir sonraki girişte karşılama kartı yeniden çıkardı.
    const keep = tourState?.status === 'completed' || tourState?.status === 'dismissed'
    persist(keep ? { last_step: steps[0]?.id } : { status: 'started', version: TOUR_VERSION, last_step: steps[0]?.id })
    return true
  }, [ctx?.tab, stepCtx, mainSteps, tourState?.version, tourState?.status, persist])

  // Yan etkiler (persist) setState güncelleyicisinin DIŞINDA: StrictMode güncelleyiciyi iki kez çağırır,
  // içeride yazım iki POST üretirdi. Güncel `active` kapanıştan okunur.
  /** reason: 'done' | 'skip' | 'never' */
  const stop = useCallback((reason = 'skip') => {
    const a = active
    if (!a) return
    if (a.kind === 'page') {
      persist({ seen_page: a.pageId })
    } else if (reason === 'done') {
      persist({ status: 'completed', version: TOUR_VERSION, last_step: a.steps[a.index]?.id, checklist: { tour: true } })
    } else if (reason === 'never') {
      persist({ status: 'dismissed', version: TOUR_VERSION, last_step: a.steps[a.index]?.id })
    } else {
      persist({ last_step: a.steps[a.index]?.id })
    }
    setActive(null)
  }, [active, persist])

  const goTo = useCallback((index) => {
    const a = active
    if (!a) return
    const i = Math.max(0, Math.min(a.steps.length - 1, index))
    if (i === a.index) return
    if (a.kind !== 'page') persist({ last_step: a.steps[i]?.id })
    setActive({ ...a, index: i })
  }, [active, persist])

  // Otomatik başlatma: girişten sonra bir kez, veri hazır olunca (ctx.ready) ve zorunlu şifre yokken.
  useEffect(() => {
    if (autoRan.current || !ctx?.ready) return
    autoRan.current = true
    const decision = decideAutoStart(tourState, { mustChangePwd: !!ctx?.mustChangePwd, isMobile, steps: MAIN_STEPS })
    if (!decision) return
    const id = setTimeout(() => setWelcome(decision), 1500)
    return () => clearTimeout(id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ctx?.ready])

  // Başlangıç listesi: sekme ziyareti maddeyi tamamlar (sunucuya yalnız değişince yazılır)
  useEffect(() => {
    const key = CHECKLIST_BY_TAB[ctx?.tab]
    if (!key || !tourState || tourState.status === 'dismissed' || tourState.checklist_hidden) return
    if (tourState.checklist?.[key]) return
    persist({ checklist: { [key]: true } })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ctx?.tab])

  // Sekme değişince sayfa turu biter (hedefleri kalmadı); ana tur kendi sekmesine geçer.
  useEffect(() => {
    if (active?.kind === 'page' && active.pageId !== ctx?.tab) setActive(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ctx?.tab])

  // Komut paleti / yardım sayfası: `sm:tour-start` {kind, pageId}
  useEffect(() => {
    const on = (e) => { start(e?.detail?.kind || 'main', { pageId: e?.detail?.pageId }) }
    window.addEventListener('sm:tour-start', on)
    return () => window.removeEventListener('sm:tour-start', on)
  }, [start])

  const offerPage = useCallback((pageId) => !active && shouldOfferPageTour(pageId, tourState, PAGE_TOURS), [active, tourState])

  const value = useMemo(() => ({ active, start, stop, goTo, offerPage, tourState, welcome, setWelcome, persist }),
    [active, start, stop, goTo, offerPage, tourState, welcome, persist])

  return (
    <TourCtx.Provider value={value}>
      {children}
      {welcome && !active && (
        <WelcomeCard kind={welcome} isMobile={isMobile}
          onStart={() => start(welcome === 'whatsnew' ? 'whatsnew' : 'main')}
          onLater={() => { setWelcome(null); persist({ status: 'snoozed', version: TOUR_VERSION }) }}
          onNever={() => { setWelcome(null); persist({ status: 'dismissed', version: TOUR_VERSION }) }} />
      )}
      {active && <TourOverlay active={active} onNext={() => goTo(active.index + 1)} onPrev={() => goTo(active.index - 1)}
        onStop={stop} navigate={(tab) => navigateTo(tab)} />}
    </TourCtx.Provider>
  )
}
