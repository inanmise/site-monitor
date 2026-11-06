import { useLayoutEffect, useRef } from 'react'

/**
 * Kart ızgarası işleyicileri için "en güncel kopya" referansı (2026-11-06, performans PF4 O2).
 *
 * <p><b>Neden:</b> izleme sayfalarında form state'i sayfa bileşeninde tutuluyor; formdaki her tuş sayfayı ve
 * onunla birlikte TÜM kart ızgarasını (25–100 kart, her biri rozet / mini trend / eylem çubuğu) yeniden çiziyordu.
 * Izgara artık `useMemo` ile yalnız kendi verisi (liste sayfası, yoğunluk, seçim, çalışan kontroller …)
 * değişince çiziliyor. Ama ızgara çizilmeyince kartlardaki `onClick` kapanışları da eski çizimden kalır —
 * "Düzenle" eski `defaults` / `teams` değerini okurdu. Bu kanca işleyicileri bir referansta tutar ve her
 * işlemeden (commit) sonra günceller; ızgara `ref.current.openEdit(m)` diye ÇAĞIRMA ANINDA en güncelini okur.
 *
 * <p>Kural: ızgaranın ÇİZİMDE kullandığı değerler (yetki bayrakları, rozet metni, seçim kümesi …) `useMemo`
 * bağımlılıklarına yazılır; YALNIZ olay anında çalışan işlevler bu referanstan okunur. Referans çizim sırasında
 * okunmaz (yalnız olay işleyicisi içinde) — React'in "çizimde ref okuma" kuralına uyar.
 *
 * @template T
 * @param {T} handlers  kartların olay işleyicileri (her çizimde yeni nesne olabilir)
 * @returns {{ current: T }} kararlı referans (kimliği hiç değişmez)
 */
export function useCardHandlers(handlers) {
  const ref = useRef(handlers)
  // Bağımlılık listesi bilinçli YOK: her işlemeden sonra en güncel işleyiciler yazılır. Olaylar (tıklama) işlemeden
  // sonra geldiği için kartın çağırdığı kopya her zaman ekrandaki state'e aittir.
  useLayoutEffect(() => { ref.current = handlers })
  return ref
}

export default useCardHandlers
