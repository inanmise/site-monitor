import { useCallback, useState } from 'react'

/**
 * Kontrol geçmişinde AÇIK hata panellerinin kümesi (2026-10-05, hata teşhisi) — her geçmiş sayfası aynı kalıbı kullanır:
 * satır anahtarı (`id`, yoksa `checked_at`) ile aç/kapa; panel kimliği `aria-controls` için güvenli karakterlerle kurulur.
 * Aralık/sayfa değişse de anahtar satıra bağlı kaldığından yanlış satır açılmaz.
 */
export function failureRowKey(c) {
  return String(c?.id ?? c?.checked_at ?? '')
}

/** `aria-controls` / `id` için güvenli panel kimliği. */
export function failurePanelId(prefix, key) {
  return `${prefix}-fail-${key}`.replace(/[^A-Za-z0-9_-]/g, '')
}

export default function useFailureRows() {
  const [open, setOpen] = useState(() => new Set())
  const toggle = useCallback((k) => setOpen((s) => {
    const n = new Set(s)
    if (n.has(k)) n.delete(k)
    else n.add(k)
    return n
  }), [])
  const isOpen = useCallback((k) => open.has(k), [open])
  return { isOpen, toggle }
}
