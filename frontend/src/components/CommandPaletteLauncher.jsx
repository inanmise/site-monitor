import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react'

// Palet + hazır sinyali AYNI tembel bileşende: sinyal ancak palet yüklenip bağlandıktan sonra bağlanabilir.
const ReadyPalette = lazy(() => import('./CommandPalette.jsx').then(({ default: CommandPalette }) => ({
  default: function ReadyPalette({ onReady, ...props }) {
    return (
      <>
        <CommandPalette {...props} />
        <ReadySignal onReady={onReady} />
      </>
    )
  },
})))

/**
 * Komut paletinin İLK KULLANIMDA yüklenen başlatıcısı (2026-10-09, açılış paketi küçültme).
 *
 * <p>Palet (cmdk + arama/sonuç satırları) açılış paketindeydi; Nav onu her zaman bağlıyordu. Artık Nav bu küçük
 * bileşeni bağlar: paletin KENDİ dinleyicileri kurulana dek Ctrl/⌘+K ve `sm:palette` olayını burası karşılar, ilk
 * istekte paleti yükler ve hazır olunca isteği ona devreder. Davranış aynı:
 *  - Ctrl/⌘+K tarayıcı varsayılanını İLK basışta da engeller (yazı alanında da) ve aç-kapa yapar — yükleme sürerken
 *    ikinci basış isteği geri alır; Esc bekleyen açılışı iptal eder;
 *  - `sm:palette` (kenar çubuğu / mobil üst çubuk düğmesi) açar;
 *  - palet hazır olunca bekleyen açılış `sm:palette` ile verilir → odak iadesi için açanı palet kendisi kaydeder
 *    (o an etkin öğe, yani basıldığı yer).
 * Palet bir kez yüklendikten sonra bu dinleyici devre dışıdır (`ready`); her şeyi paletin kendi dinleyicisi yapar.
 */
export default function CommandPaletteLauncher(props) {
  const [mounted, setMounted] = useState(false)
  const pendingOpen = useRef(false)
  const ready = useRef(false)

  useEffect(() => {
    const onKey = (e) => {
      if (ready.current) return
      if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) {
        e.preventDefault()
        pendingOpen.current = !pendingOpen.current
        setMounted(true)
      } else if (e.key === 'Escape') pendingOpen.current = false
    }
    const onOpen = () => {
      if (ready.current) return
      pendingOpen.current = true
      setMounted(true)
    }
    window.addEventListener('keydown', onKey)
    window.addEventListener('sm:palette', onOpen)
    return () => { window.removeEventListener('keydown', onKey); window.removeEventListener('sm:palette', onOpen) }
  }, [])

  // Paletin efektleri (dinleyicileri) kardeş sırasıyla ÖNCE koşar; ReadySignal'in efekti ardından devri yapar.
  const onReady = useCallback(() => {
    ready.current = true
    if (!pendingOpen.current) return
    pendingOpen.current = false
    window.dispatchEvent(new CustomEvent('sm:palette'))
  }, [])

  if (!mounted) return null
  return (
    <Suspense fallback={null}>
      <ReadyPalette {...props} onReady={onReady} />
    </Suspense>
  )
}

/** Kardeş sırasıyla paletin efektlerinden (dinleyicilerinden) SONRA koşar. */
function ReadySignal({ onReady }) {
  useEffect(() => { onReady() }, [onReady])
  return null
}
