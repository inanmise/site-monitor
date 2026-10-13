import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { ChevronLeft, ChevronRight, Expand, LayoutGrid, Minimize, Palette, Pause, Play, RotateCcw, TriangleAlert, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { setTvModeActive } from '../../utils/tvMode.js'
import { Button } from '@/components/shadcn/button'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Toggle } from '@/components/shadcn/toggle'
import { cn } from '@/lib/utils'
import { TV_INTERVALS, readTvSettings, statStyleOf, tvDetail, writeTvSettings } from './myDashModel.js'
import { TV_DENSITIES, TV_GAP, cellSize, layoutTv } from './tvLayout.js'
import { WIDGETS } from './widgets.jsx'

const CONTROLS_HIDE_MS = 4000
const PAD = 48   // ana alanın iç boşluğu (ölçüm yokken tahmin)
const BAR = 64   // üst çubuk (ölçüm yokken tahmin)
/** Sığdırma ölçeğinin alt sınırı — daha küçüğü duvardan okunmaz; o durumda sayfa kayar (satır tahmini yanılmış demektir). */
export const MIN_FIT = 0.6

/**
 * Etkin sayfayı ekrana sığdırma ölçeği: sayfanın DOĞAL (dönüşümsüz) yüksekliği kullanılabilir yükseklikten fazlaysa
 * oran kadar küçültülür (`transform: scale`, üst ortadan). Ölçek yerleşimi değiştirmez → ölçüm döngüsü olmaz.
 */
function useFitScale(mainRef, deps) {
  const [fit, setFit] = useState(1)
  // Alt sınırda bile sığmıyor mu (çok kısa ekran / yatay telefon) — o zaman sayfa KAYAR, kırpılmaz (2026-10-12)
  const [clipped, setClipped] = useState(false)
  useEffect(() => {
    const main = mainRef.current
    if (!main) return undefined
    const grid = main.querySelector('[data-slot="mydash-tv-page"][data-active="true"]')
    if (!grid) { setFit(1); setClipped(false); return undefined }
    const measure = () => {
      const cs = getComputedStyle(main)
      const avail = main.clientHeight - (parseFloat(cs.paddingTop) || 0) - (parseFloat(cs.paddingBottom) || 0)
      const natural = grid.offsetHeight
      const next = avail > 0 && natural > avail + 1 ? Math.max(MIN_FIT, Math.floor((avail / natural) * 1000) / 1000) : 1
      setFit((prev) => (Math.abs(prev - next) > 0.004 ? next : prev))
      const over = avail > 0 && natural * next > avail + 1
      setClipped((prev) => (prev === over ? prev : over))
    }
    measure()
    if (typeof ResizeObserver === 'undefined') return undefined
    const ro = new ResizeObserver(measure)
    ro.observe(grid)
    ro.observe(main)
    return () => ro.disconnect()
  }, deps) // eslint-disable-line react-hooks/exhaustive-deps
  return { fit, clipped }
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

/**
 * Sayfa ızgarasının kullanabileceği alan (px): ana alanın iç boyu (ölçülür — üst çubuk / izin şeridi değişince de doğru);
 * ölçüm yoksa (ilk çizim, jsdom) pencereden tahmin. Tam sayıya yuvarlanır, küçük titreşimde yeniden hesaplanmaz.
 */
function useAvailableArea(mainRef, vw, vh) {
  const [area, setArea] = useState(null)
  useEffect(() => {
    const main = mainRef.current
    if (!main) return undefined
    const measure = () => {
      const cs = getComputedStyle(main)
      const w = Math.floor(main.clientWidth - (parseFloat(cs.paddingLeft) || 0) - (parseFloat(cs.paddingRight) || 0))
      const h = Math.floor(main.clientHeight - (parseFloat(cs.paddingTop) || 0) - (parseFloat(cs.paddingBottom) || 0))
      if (w > 0 && h > 0) setArea((prev) => (prev && Math.abs(prev.w - w) < 2 && Math.abs(prev.h - h) < 2 ? prev : { w, h }))
    }
    measure()
    if (typeof ResizeObserver === 'undefined') return undefined
    const ro = new ResizeObserver(measure)
    ro.observe(main)
    return () => ro.disconnect()
  }, [mainRef])
  return area || { w: Math.max(1, vw - PAD), h: Math.max(1, vh - BAR - PAD) }
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
/**
 * @param permsFailed  yetkiler okunamadı (inceleme 4): yetki isteyen kutular listede YOK — duvar ekranı bunu her an görünür
 *                     bir şeritle söyler (denetimlerle birlikte gizlenmez), "sakin" görünmez; `onRetryPerms` yeniden dener.
 */
export default function MyDashTv({ widgets, refreshKey, onExit, permsFailed = false, onRetryPerms }) {
  const t = useT()
  const [vw, vh] = useViewport()
  const [settings, setSettings] = useState(readTvSettings)
  const mainRef = useRef(null)
  const area = useAvailableArea(mainRef, vw, vh)
  // Sayfalar (2026-10-13): yoğunluğa göre en çok ızgara, iki boyutlu ilk-uyan dizilim, her sayfa kendi en büyük hücreli
  // ızgarası — ekranda daha çok kutu, az kutulu sayfada boş alan kalmaz (tvLayout.js)
  const pages = useMemo(() => layoutTv(
    (widgets || []).filter((w) => WIDGETS[w.type]).map((w) => ({ id: w.id, size: w.size, detail: tvDetail(w, settings.panels), widget: w })),
    area.w, area.h, settings.density,
  ), [widgets, area.w, area.h, settings.density, settings.panels])
  const [slide, setSlide] = useState(0)
  const [paused, setPaused] = useState(false)
  const [controls, setControls] = useState(true)
  const [fullscreen, setFullscreen] = useState(() => typeof document !== 'undefined' && !!document.fullscreenElement)
  const hideTimer = useRef(null)
  const rootRef = useRef(null)
  const count = pages.length
  const cur = count ? Math.min(slide, count - 1) : 0
  const { fit, clipped } = useFitScale(mainRef, [cur, count, area.w, area.h, settings.panels, settings.density])

  // Yerleşim etkisi (2026-10-13): katman DOM'a girdiği anda hareketsizlik çıkışı ertelenir ve kaydırma kilitlenir — pasif
  // etkide, katman görünürken bayrağın henüz inmediği kısa bir aralık vardı (yavaş CI'da AppTvIdle testi yakaladı)
  useLayoutEffect(() => {
    setTvModeActive(true)
    // Belge kaydırması kilitli ve kaydırma çubuğu oluğu kapalı (App.css ≥ 640 px'te html'e `scrollbar-gutter: stable`
    // koyar): katman ekranın TAMAMINI kaplasın, sağda boş şerit kalmasın
    const html = document.documentElement
    const prev = { body: document.body.style.overflow, html: html.style.overflow, gutter: html.style.scrollbarGutter }
    document.body.style.overflow = 'hidden'
    html.style.overflow = 'hidden'
    html.style.scrollbarGutter = 'auto'
    // Kalıcı pencere (2026-10-12): odak katmana alınır (Boşluk duraklatır, gizli "TV modu" düğmesine gitmez), kapanınca
    // açan düğmeye döner; katman kapanırken (Çık, Esc ya da başka sekmeye geçiş) tam ekrandan da çıkılır.
    const opener = document.activeElement
    rootRef.current?.focus({ preventScroll: true })
    return () => {
      setTvModeActive(false)
      document.body.style.overflow = prev.body
      html.style.overflow = prev.html
      html.style.scrollbarGutter = prev.gutter
      try { if (document.fullscreenElement) document.exitFullscreen?.() } catch { /* yoksay */ }
      try { if (opener && opener.isConnected) opener.focus?.({ preventScroll: true }) } catch { /* yoksay */ }
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
    const id = setInterval(() => setSlide((p) => (p + 1) % count), settings.interval * 1000)
    return () => clearInterval(id)
  }, [settings.interval, paused, count])

  const go = useCallback((dir) => setSlide((p) => (count ? (Math.min(p, count - 1) + dir + count) % count : 0)), [count])

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
      // Üstte BAŞKA bir pencere (komut paleti, bakım / pasif hesap geri sayımı …) odaktaysa TV karışmaz (inceleme 4):
      // eskiden Tab odağı TV denetimlerine çekiyor, Escape o pencere yerine TV'yi kapatıyordu.
      const dlg = e.target?.closest?.('[role="dialog"], [role="alertdialog"]')
      if (dlg && dlg !== rootRef.current && !rootRef.current?.contains(dlg)) return
      const tag = e.target?.tagName
      if (tag === 'SELECT' || tag === 'INPUT' || tag === 'TEXTAREA') return
      if (e.key === 'Tab') { trapTab(e); return }
      if (e.key === 'Escape' && !document.fullscreenElement) { e.preventDefault(); exit() }
      else if (e.key === 'ArrowRight') { e.preventDefault(); go(1) }
      else if (e.key === 'ArrowLeft') { e.preventDefault(); go(-1) }
      else if (e.key === ' ' && tag !== 'BUTTON') { e.preventDefault(); setPaused((p) => !p) }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [poke, go, exit])

  /** Tab / Shift+Tab katmanın denetimleri arasında döner — arkadaki (görünmeyen) uygulamaya geçmez. */
  function trapTab(e) {
    const root = rootRef.current
    if (!root) return
    const items = [...root.querySelectorAll('button:not([disabled]), select:not([disabled]), [href], [tabindex]:not([tabindex="-1"])')]
      .filter((el) => !el.closest('[hidden]') && !el.closest('[aria-hidden="true"]'))
    if (!items.length) { e.preventDefault(); root.focus(); return }
    const first = items[0]
    const last = items[items.length - 1]
    const active = document.activeElement
    if (e.shiftKey && (active === first || active === root || !root.contains(active))) { e.preventDefault(); last.focus() }
    else if (!e.shiftKey && (active === last || !root.contains(active))) { e.preventDefault(); first.focus() }
  }

  const toggleFullscreen = async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen?.()
      else await document.documentElement.requestFullscreen?.()
    } catch { /* tarayıcı izin vermedi */ }
  }

  const setInterval_ = (v) => {
    const next = { ...settings, interval: Number(v) }
    setSettings(next)
    writeTvSettings(next)
  }
  // Renkli paneller (2026-10-13): TV'de varsayılan AÇIK; kapatılınca kutular ayrıntılı çizilir (bu tarayıcıda hatırlanır)
  const setPanels = (on) => {
    const next = { ...settings, panels: on ? 'stat' : 'detail' }
    setSettings(next)
    writeTvSettings(next)
    // Ayrıntılı kutular daha çok yer ister → sayfalar değişir; eski sayfa numarası başka kutuları gösterirdi
    setSlide(0)
  }
  // Yoğunluk (2026-10-13): ekrana sığan en çok kutu — Rahat (büyük, uzaktan), Sık (varsayılan), Çok sık; bu tarayıcıda
  const setDensity = (v) => {
    const next = { ...settings, density: v }
    setSettings(next)
    writeTvSettings(next)
    setSlide(0)
  }

  const btn = 'h-10 min-w-10'
  return (
    <div ref={rootRef} tabIndex={-1} data-slot="mydash-tv" data-controls={controls ? 'shown' : 'hidden'} data-page={cur + 1} data-pages={count}
      role="dialog" aria-modal="true" aria-label={t('mydash.tv.region')}
      className={cn('fixed inset-0 z-[1900] flex flex-col bg-background text-foreground outline-none', !controls && 'cursor-none')}
      onMouseMove={poke} onPointerDown={poke} onTouchStart={poke}>
      <header className={cn('flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-border px-4 py-3 transition-opacity sm:px-6',
        !controls && 'opacity-0 focus-within:opacity-100')} data-slot="mydash-tv-bar">
        <div className="flex min-w-0 items-center gap-2 text-lg font-semibold sm:text-xl">
          <LayoutGrid className="size-5 shrink-0" aria-hidden="true" />
          <span className="min-w-0 [overflow-wrap:anywhere]">{t('mydash.title')}</span>
        </div>
        <span className="text-lg text-muted-foreground sm:text-xl"><TvClock /></span>
        {/* Tek sayfada da YER kaplar (görünmez): üst çubuk boyu sayfa sayısıyla değişirse ölçülen alan → yerleşim → sayfa
            sayısı döngüye girebiliyordu (inceleme 2026-10-13) */}
        <span className={cn('text-sm text-muted-foreground tabular-nums', count < 2 && 'invisible')} aria-hidden={count < 2 || undefined}
          aria-live="polite" data-slot="mydash-tv-pageinfo">
          {t('mydash.tv.page', cur + 1, Math.max(1, count))}
        </span>
        <div className="ml-auto flex flex-wrap items-center gap-2">
          <div className={cn('flex flex-wrap items-center gap-2', count < 2 && 'invisible')} aria-hidden={count < 2 || undefined}
            data-slot="mydash-tv-nav">
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
          </div>
          <NativeSelect value={settings.density} onChange={(e) => setDensity(e.target.value)} className="h-10"
            aria-label={t('mydash.tv.density')} title={t('mydash.tv.densityTitle')} data-slot="mydash-tv-density">
            {TV_DENSITIES.map((d) => (
              <NativeSelectOption key={d} value={d}>{t(`mydash.tv.density.${d}`)}</NativeSelectOption>
            ))}
          </NativeSelect>
          <Toggle variant="outline" pressed={settings.panels !== 'detail'} onPressedChange={setPanels} className={cn(btn, 'gap-1.5 px-3')}
            aria-label={t('mydash.tv.panelsTitle')} title={t('mydash.tv.panelsTitle')} data-slot="mydash-tv-panels">
            <Palette aria-hidden="true" /><span>{t('mydash.tv.panels')}</span>
          </Toggle>
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

      {permsFailed && (
        <div role="status" data-slot="mydash-tv-perms"
          className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-amber-500/40 bg-amber-500/10 px-4 py-2 text-amber-800 sm:px-6 dark:text-amber-300">
          <TriangleAlert className="size-5 shrink-0" aria-hidden="true" />
          <span className="min-w-0 flex-1 text-base [overflow-wrap:anywhere]">{t('mydash.tvPermsFailed')}</span>
          {onRetryPerms && (
            <Button type="button" variant="outline" className={btn} onClick={onRetryPerms} data-slot="mydash-tv-perms-retry">
              <RotateCcw aria-hidden="true" /><span>{t('hist.retry')}</span>
            </Button>
          )}
        </div>
      )}

      <main ref={mainRef} className={cn('min-h-0 flex-1 p-4 sm:p-6', fit < 1 && !clipped ? 'overflow-hidden' : 'overflow-y-auto')} data-fit={fit}
        data-clipped={clipped || undefined}>
        {count === 0 ? (
          <p className="m-0 text-lg text-muted-foreground">{t('mydash.emptyBody')}</p>
        ) : pages.map((page, i) => {
          // Satırlar hücre boyundan küçük olmaz (ekranı doldurur); ayrıntılı kutu daha çok yer isterse satır uzar ve sayfa
          // ölçeklenir (useFitScale) — kırpılmaz
          const cell = cellSize(area.w, area.h, page.cols, page.rows)
          return (
            <div key={i} hidden={i !== cur} data-slot="mydash-tv-page" data-index={i} data-active={i === cur ? 'true' : undefined}
              data-cols={page.cols} data-rows={page.rows}
              className={cn('min-h-full', i === cur ? 'grid' : 'hidden')}
              style={{
                gap: `${TV_GAP}px`,
                gridTemplateColumns: `repeat(${page.cols}, minmax(0, 1fr))`,
                gridTemplateRows: `repeat(${page.rows}, minmax(${Math.max(0, Math.floor(cell.h))}px, auto))`,
                ...(i === cur && fit < 1 ? { transform: `scale(${fit})`, transformOrigin: 'top center' } : null),
              }}>
              {page.placed.map(({ item, col, row, c, r }) => {
                const w = item.widget
                const def = WIDGETS[w.type]
                if (!def) return null
                const C = def.Component
                return <C key={w.id} refreshKey={refreshKey}
                  frame={{ widget: w, tv: true, edit: false, span: c, place: { col, row, c, r }, stat: statStyleOf(w, true, settings.panels),
                    tvPanel: !item.detail }} />
              })}
            </div>
          )
        })}
      </main>
    </div>
  )
}
