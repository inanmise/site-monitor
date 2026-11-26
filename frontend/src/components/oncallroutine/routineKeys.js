/**
 * Nöbet Rutinleri — sayfa kısayolları (sözleşme §9.6). Yazı alanında, açık pencere / menüde, IME bileşiminde, değiştirici
 * tuşla ve genel "g + harf" dizisinin ikinci tuşunda ÇALIŞMAZ (`shortcutEligible`). `g`, `/`, `?` genel kalır.
 *
 *   j / k  sonraki / önceki görev   x  yapıldı ↔ bekliyor   n  not   p  sorun   s  atla   o  bağlantıları aç
 *   Shift+D  odaktaki bölümü tamamla   c  günü tamamla   [ / ]  önceki / sonraki gün   t  bugün
 */
import { useEffect, useRef } from 'react'
import { MODIFIER_KEYS, SEQUENCE_MS, letterOf, shortcutEligible } from '../../utils/keyboardShortcuts.js'

/** Tuş → eylem adı (saf; testler doğrudan çağırır). `null` = bu sayfanın kısayolu değil. */
export function routineKeyAction(e) {
  if (!e) return null
  const k = e.key
  if (k === '[') return 'dayPrev'
  if (k === ']') return 'dayNext'
  const letter = letterOf(e)
  if (!letter) return null
  if (e.shiftKey) return letter === 'd' ? 'section' : null
  switch (letter) {
    case 'j': return 'next'
    case 'k': return 'prev'
    case 'x': return 'toggle'
    case 'n': return 'note'
    case 'p': return 'problem'
    case 's': return 'skip'
    case 'o': return 'links'
    case 'c': return 'complete'
    case 't': return 'today'
    default: return null
  }
}

/** Odaktaki görev satırının anahtarı (yoksa null). */
export function focusedTaskKey(doc = typeof document !== 'undefined' ? document : null) {
  const el = doc?.activeElement
  const row = el && typeof el.closest === 'function' ? el.closest('[data-slot="or-task"]') : null
  return row?.getAttribute('data-key') || null
}

/**
 * Kısayolları bağlar. `actionsRef.current` = `{ next, prev, toggle, note, problem, skip, links, section, complete, dayPrev,
 * dayNext, today }` — her biri odaktaki anahtarı (`focusedTaskKey()`) kendisi okur; eylem yoksa tuş yok sayılır.
 */
export function useRoutineKeys(enabled, actionsRef) {
  const gAt = useRef(0)
  useEffect(() => {
    if (!enabled) return undefined
    const onKey = (e) => {
      // Yalnız değiştirici tuş (Shift, Ctrl …) "g" dizisini bozmaz: g ardından Shift+D gibi
      if (MODIFIER_KEYS.has(e.key)) return
      const now = Date.now()
      const afterG = gAt.current > 0 && now - gAt.current <= SEQUENCE_MS
      if (!shortcutEligible(e)) return
      const letter = letterOf(e)
      if (letter === 'g' && !e.shiftKey) { gAt.current = now; return }
      gAt.current = 0
      if (afterG) return
      const action = routineKeyAction(e)
      const fn = action ? actionsRef.current?.[action] : null
      if (typeof fn !== 'function') return
      // Boşluk / x odaktaki onay kutusunda: x bizim, boşluk tarayıcının (kutunun kendi davranışı)
      const handled = fn(focusedTaskKey())
      if (handled !== false) e.preventDefault()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [enabled, actionsRef])
}
