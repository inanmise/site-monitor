import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react'
import {
  MODIFIER_KEYS, SEQUENCE_MS, SHORTCUTS_EVENT,
  focusPageSearch, goTarget, letterOf, shortcutEligible,
} from '../utils/keyboardShortcuts.js'

// Liste penceresi ilk açılışta yüklenir (2026-10-09); dinleyici burada, açılış paketinde kalır — `?`, `/`, `g`+harf
// ilk tuştan itibaren çalışır.
const ShortcutsDialog = lazy(() => import('./ShortcutsDialog.jsx'))

/**
 * Genel klavye kısayolları + "Klavye kısayolları" listesi (2026-10-02, öneri 24). Kurallar ve neden koşulları:
 * `utils/keyboardShortcuts.js`. Nav içinde, komut paletinin yanında çizilir — `tabs` paletle AYNI liste (menünün
 * görünürlük kuralları), `onTabChange` Nav'ın `go`'su (telefonda çekmeceyi de kapatır).
 *
 * Liste: `?`, komut paletinin "Klavye kısayolları" eylemi ve kullanıcı menüsü (`sm:shortcuts` olayı) açar. Pencere
 * ShortcutsDialog'dadır (ModalShell; ilk açılışta tembel yüklenir, sonra bağlı kalır — kapanış animasyonu aynı).
 * Mevcut kısayollar da listelenir (palet, kenar çubuğu, gönder, Esc, palet içi, ürün turu, envanter ayrıntısı,
 * ekran görüntüsü görüntüleyici).
 */

export default function KeyboardShortcuts({ tabs = [], onTabChange }) {
  const [open, setOpen] = useState(false)
  const [everOpened, setEverOpened] = useState(false)   // pencere parçası ilk açılışta bağlanır, sonra kalır
  const pendingAt = useRef(0)            // `g` basıldığı an (ms); 0 = dizi yok
  const tabIdsRef = useRef(new Set())
  const goRef = useRef(onTabChange)
  const tabIds = useMemo(() => new Set(tabs.map((tb) => tb.id)), [tabs])
  tabIdsRef.current = tabIds
  goRef.current = onTabChange

  useEffect(() => {
    const onKey = (e) => {
      if (MODIFIER_KEYS.has(e.key)) return
      const now = Date.now()
      const pending = pendingAt.current > 0 && now - pendingAt.current <= SEQUENCE_MS
      pendingAt.current = 0
      if (!shortcutEligible(e)) return
      const letter = letterOf(e)
      if (pending) {
        const tab = goTarget(letter, tabIdsRef.current)
        if (tab) { e.preventDefault(); goRef.current?.(tab); return }
      }
      if (e.key === '?') { e.preventDefault(); setOpen(true); return }
      if (e.key === '/') { if (focusPageSearch()) e.preventDefault(); return }
      if (letter === 'g') pendingAt.current = now
    }
    const onOpen = () => setOpen(true)
    window.addEventListener('keydown', onKey)
    window.addEventListener(SHORTCUTS_EVENT, onOpen)
    return () => {
      window.removeEventListener('keydown', onKey)
      window.removeEventListener(SHORTCUTS_EVENT, onOpen)
    }
  }, [])

  useEffect(() => { if (open) setEverOpened(true) }, [open])

  if (!open && !everOpened) return null
  return (
    <Suspense fallback={null}>
      <ShortcutsDialog open={open} onClose={() => setOpen(false)} tabs={tabs} />
    </Suspense>
  )
}
