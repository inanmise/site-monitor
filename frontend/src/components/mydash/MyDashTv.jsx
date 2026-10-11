import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ChevronLeft, ChevronRight, Expand, LayoutGrid, Minimize, Pause, Play, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { setTvModeActive } from '../../utils/tvMode.js'
import { Button } from '@/components/shadcn/button'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'
import { TV_INTERVALS, columnsFor, paginate, readTvSettings, rowsFor, spanOf, writeTvSettings } from './myDashModel.js'
import { WIDGETS } from './widgets.jsx'

const CONTROLS_HIDE_MS = 4000
const PAD = 48   // yatay iç boşluk (sütun eşiği için)
/** Sığdırma ölçeğinin alt sınırı — daha küçüğü duvardan okunmaz; o durumda sayfa kayar (satır tahmini yanılmış demektir). */
export const MIN_FIT = 0.6

/**
 * Etkin sayfayı ekrana sığdırma ölçeği: sayfanın DOĞAL (dönüşümsüz) yüksekliği kullanılabilir yükseklikten fazlaysa
 * oran kadar küçültülür (`transform: scale`, üst ortadan). Ölçek yerleşimi değiştirmez → ölçüm döngüsü olmaz.
 */
function useFitScale(mainRef, deps) {
  const [fit, setFit] = useState(1)
  useEffect(() => {
    const main = mainRef.current
    if (!main) return undefined
    const grid = main.querySelector('[data-slot="mydash-tv-page"][data-active="true"]')
    if (!grid) { setFit(1); return undefined }
    const measure = () => {
      const cs = getComputedStyle(main)
      const avail = main.clientHeight - (parseFloat(cs.paddingTop) || 0) - (parseFloat(cs.paddingBottom) || 0)
      const natural = grid.offsetHeight
      const next = avail > 0 && natural > avail + 1 ? Math.max(MIN_FIT, Math.floor((avail / natural) * 1000) / 1000) : 1
      setFit((prev) => (Math.abs(prev - next) > 0.004 ? next : prev))
    }
    measure()
    if (typeof ResizeObserver === 'undefined') return undefined
    const ro = new ResizeObserver(measure)
    ro.observe(grid)
    ro.observe(main)
    return () => ro.disconnect()
  }, deps) // eslint-disable-line react-hooks/exhaustive-deps
  return fit
}

function useViewport() {
  const read = () => [typeof window === 'undefined' ? 1280 : window.innerWidth, typeof window === 'undefined' ? 800 : window.innerHeight]
  const [vp, setVp] = useState(read)
  useEffect(() => {
    const on = () => setVp(read())
    window.addEventListener('resize', on)
    return () => window.removeEventListener('resize', on)
  }, [])
  return vp
}

/** Saat (SS:dd) — kendi bileşeninde, 15 sn'de bir; üst bileşen her tıkta yeniden çizilmez. */
function TvClock() {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 15_000)
    return () => clearInterval(id)
  }, [])
  let text
  try {
    text = now.toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Europe/Istanbul' })
  } catch {
    text = now.toTimeString().slice(0, 5)
  }
  return <time className="font-semibold tabular-nums" dateTime={now.toISOString()} data-slot="mydash-tv-clock">{text}</time>
}

/**
 * TV / duvar ekranı kipi (2026-10-11): pano kutuları tam ekran, büyük yazıyla; kutular ekrana sığan sayfalara bölünür
 * ve seçilen aralıkla döner (Duraklat / önceki / sonraki; ← → tuşları, Boşluk). Denetimler 4 sn hareketsizlikte gizlenir
 * (imleç de). Açıkken uygulamanın hareketsizlik çıkışı ertelenir (utils/tvMode.js), ekran uyku kilidi istenir
 * (destekleyen tarayıcıda). Esc (tam ekran dışındayken) ya da "Çık" kipi kapatır. Tüm sayfalar bağlı kalır, yalnız
 * etkin sayfa görünür — dönüşte kutular yeniden yüklenmez, veri arka planda tazelenir.
 */
export default function MyDashTv({ widgets, refreshKey, onExit }) {
  const t = useT()
  const [vw, vh] = useViewport()
  const cols = columnsFor(vw - PAD)
  const rows = rowsFor(vh)
  const pages = useMemo(() => paginate(widgets, cols, rows), [widgets, cols, rows])
  const [page, setPage] = useState(0)
  const [settings, setSettings] = useState(readTvSettings)
  const [paused, setPaused] = useState(false)
  const [controls, setControls] = useState(true)
  const [fullscreen, setFullscreen] = useState(() => typeof document !== 'undefined' && !!document.fullscreenElement)
  const hideTimer = useRef(null)
  const mainRef = useRef(null)
  const count = pages.length
  const cur = count ? Math.min(page, count - 1) : 0
  const fit = useFitScale(mainRef, [cur, count, cols, rows])

  useEffect(() => {
    setTvModeActive(true)
    // Belge kaydırması kilitli ve kaydırma çubuğu oluğu kapalı (App.css ≥ 640 px'te html'e `scrollbar-gutter: stable`
    // koyar): katman ekranın TAMAMINI kaplasın, sağda boş şerit kalmasın
    const html = document.documentElement
    const prev = { body: document.body.style.overflow, html: html.style.overflow, gutter: html.style.scrollbarGutter }
    document.body.style.overflow = 'hidden'
    html.style.overflow = 'hidden'
    html.style.scrollbarGutter = 'auto'
    return () => {
      setTvModeActive(false)
      document.body.style.overflow = prev.body
      html.style.overflow = prev.html
      html.style.scrollbarGutter = prev.gutter
    }
  }, [])

  // Ekran uyku kilidi — desteklenmiyorsa ya da reddedilirse sessizce geçilir; sekme geri gelince yeniden istenir.
  useEffect(() => {
    let lock = null
    let gone = false
    const acquire = async () => {
      try {
        if (!document.hidden && navigator.wakeLock?.request) {
          const l = await navigator.wakeLock.request('screen')
          if (gone) { l.release?.().catch?.(() => {}); return }
          lock = l
        }
      } catch { /* destek yok / izin yok */ }
    }
    const onVis = () => { if (!document.hidden) acquire() }
    acquire()
    document.addEventListener('visibilitychange', onVis)
    return () => {
      gone = true
      document.removeEventListener('visibilitychange', onVis)
      try { lock?.release?.() } catch { /* yoksay */ }
    }
  }, [])

  useEffect(() => {
    const on = () => setFullscreen(!!document.fullscreenElement)
    document.addEventListener('fullscreenchange', on)
    return () => document.removeEventListener('fullscreenchange', on)
  }, [])

  // Sayfa dönüşü
  useEffect(() => {
    if (!settings.interval || paused || count < 2) return undefined
    const id = setInterval(() => setPage((p) => (p + 1) % count), settings.interval * 1000)
    return () => clearInterval(id)
  }, [settings.interval, paused, count])

  const go = useCallback((dir) => setPage((p) => (count ? (Math.min(p, count - 1) + dir + count) % count : 0)), [count])

  const poke = useCallback(() => {
    setControls(true)
    clearTimeout(hideTimer.current)
    hideTimer.current = setTimeout(() => setControls(false), CONTROLS_HIDE_MS)
  }, [])
  useEffect(() => {
    poke()
    return () => clearTimeout(hideTimer.current)
  }, [poke])

  const exit = useCallback(() => {
    try { if (document.fullscreenElement) document.exitFullscreen?.() } catch { /* yoksay */ }
    onExit?.()
  }, [onExit])

  useEffect(() => {
    const onKey = (e) => {
      poke()
      if (e.defaultPrevented || e.ctrlKey || e.metaKey || e.altKey) return
      const tag = e.target?.tagName
      if (tag === 'SELECT' || tag === 'INPUT' || tag === 'TEXTAREA') return
      if (e.key === 'Escape' && !document.fullscreenElement) { e.preventDefault(); exit() }
      else if (e.key === 'ArrowRight') { e.preventDefault(); go(1) }
      else if (e.key === 'ArrowLeft') { e.preventDefault(); go(-1) }
      else if (e.key === ' ' && tag !== 'BUTTON') { e.preventDefault(); setPaused((p) => !p) }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [poke, go, exit])

  const toggleFullscreen = async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen?.()
      else await document.documentElement.requestFullscreen?.()
    } catch { /* tarayıcı izin vermedi */ }
  }

  const setInterval_ = (v) => {
    const next = { interval: Number(v) }
    setSettings(next)
    writeTvSettings(next)
  }

  const btn = 'h-10 min-w-10'
  return (
    <div data-slot="mydash-tv" data-controls={controls ? 'shown' : 'hidden'} data-page={cur + 1} data-pages={count}
      role="region" aria-label={t('mydash.tv.region')}
      className={cn('fixed inset-0 z-[1900] flex flex-col bg-background text-foreground', !controls && 'cursor-none')}
      onMouseMove={poke} onPointerDown={poke} onTouchStart={poke}>
      <header className={cn('flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-border px-4 py-3 transition-opacity sm:px-6',
        !controls && 'opacity-0 focus-within:opacity-100')} data-slot="mydash-tv-bar">
        <div className="flex min-w-0 items-center gap-2 text-lg font-semibold sm:text-xl">
          <LayoutGrid className="size-5 shrink-0" aria-hidden="true" />
          <span className="min-w-0 [overflow-wrap:anywhere]">{t('mydash.title')}</span>
        </div>
        <span className="text-lg text-muted-foreground sm:text-xl"><TvClock /></span>
        {count > 1 && (
          <span className="text-sm text-muted-foreground tabular-nums" aria-live="polite" data-slot="mydash-tv-pageinfo">
            {t('mydash.tv.page', cur + 1, count)}
          </span>
        )}
        <div className="ml-auto flex flex-wrap items-center gap-2">
          {count > 1 && (
            <>
              <Button type="button" variant="outline" size="icon" className={btn} aria-label={t('mydash.tv.prev')} title={t('mydash.tv.prev')} onClick={() => go(-1)}>
                <ChevronLeft aria-hidden="true" />
              </Button>
              <Button type="button" variant="outline" size="icon" className={btn} data-slot="mydash-tv-pause"
                aria-label={paused ? t('mydash.tv.resume') : t('mydash.tv.pause')} title={paused ? t('mydash.tv.resume') : t('mydash.tv.pause')}
                aria-pressed={paused} onClick={() => setPaused((p) => !p)}>
                {paused ? <Play aria-hidden="true" /> : <Pause aria-hidden="true" />}
              </Button>
              <Button type="button" variant="outline" size="icon" className={btn} aria-label={t('mydash.tv.next')} title={t('mydash.tv.next')}
                data-slot="mydash-tv-next" onClick={() => go(1)}>
                <ChevronRight aria-hidden="true" />
              </Button>
              <NativeSelect value={String(settings.interval)} onChange={(e) => setInterval_(e.target.value)} className="h-10"
                aria-label={t('mydash.tv.interval')} data-slot="mydash-tv-interval">
                {TV_INTERVALS.map((s) => (
                  <NativeSelectOption key={s} value={String(s)}>{s ? t('mydash.tv.every', s) : t('mydash.tv.noRotate')}</NativeSelectOption>
                ))}
              </NativeSelect>
            </>
          )}
          <Button type="button" variant="outline" className={btn} onClick={toggleFullscreen} data-slot="mydash-tv-fullscreen">
            {fullscreen ? <Minimize aria-hidden="true" /> : <Expand aria-hidden="true" />}
            <span>{fullscreen ? t('mydash.tv.exitFullscreen') : t('mydash.tv.fullscreen')}</span>
          </Button>
          <Button type="button" className={btn} onClick={exit} data-slot="mydash-tv-exit">
            <X aria-hidden="true" />
            <span>{t('mydash.tv.exit')}</span>
          </Button>
        </div>
      </header>

      <main ref={mainRef} className={cn('min-h-0 flex-1 p-4 sm:p-6', fit < 1 ? 'overflow-hidden' : 'overflow-y-auto')} data-fit={fit}>
        {count === 0 ? (
          <p className="m-0 text-lg text-muted-foreground">{t('mydash.emptyBody')}</p>
        ) : pages.map((list, i) => (
          <div key={i} hidden={i !== cur} data-slot="mydash-tv-page" data-index={i} data-active={i === cur ? 'true' : undefined}
            className={cn('min-h-full gap-4 sm:gap-5', i === cur ? 'grid' : 'hidden')}
            style={{
              gridTemplateColumns: `repeat(${cols}, minmax(0, 1fr))`, gridAutoRows: 'minmax(min-content, 1fr)', gridAutoFlow: 'row dense',
              ...(i === cur && fit < 1 ? { transform: `scale(${fit})`, transformOrigin: 'top center' } : null),
            }}>
            {list.map((w) => {
              const def = WIDGETS[w.type]
              if (!def) return null
              const C = def.Component
              return <C key={w.id} refreshKey={refreshKey} frame={{ widget: w, tv: true, edit: false, span: spanOf(w.size, cols) }} />
            })}
          </div>
        ))}
      </main>
    </div>
  )
}
