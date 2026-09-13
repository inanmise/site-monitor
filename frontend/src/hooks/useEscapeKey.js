import { useEffect } from 'react'

/**
 * Escape ile kapatma — ModalShell'e TAŞINMAMIŞ eski detay modalları için (QA ISSUE-002, 2026-09-13).
 *
 * <p>Dokuz izleme sayfasının detay modalı, Uptime ve Sertifika modalı `.upt-modal-overlay` /
 * `.modal-overlay` ile elle kurulu; yalnız X ve dış tıklama kapatıyordu — klavye kullanıcısı
 * modalda kilitli kalıyordu. Kabuğa toplu geçiş ayrı iş; bu hook aradaki boşluğu kapatır.
 *
 * <p>Kurallar: `enabled` yanlışken dinlemez. Üstte bir {@code .modal-overlay} (ModalShell ya da elle
 * kurulu düzenleme formu) açıkken dokunmaz; aksi halde tek tuşla alttaki detay da kapanır ve form
 * emeği gider (düzenleme formları bilinçli olarak Escape ile kapanmaz). `defaultPrevented` olaylara (açılır liste kendi Escape'ini
 * tükettiyse) da dokunmaz.
 */
export function useEscapeKey(enabled, onClose) {
  useEffect(() => {
    if (!enabled) return undefined
    const onKey = (e) => {
      if (e.key !== 'Escape' || e.defaultPrevented) return
      // Üstte bir kabuk (ModalShell) ya da elle kurulu düzenleme formu (.modal-overlay — bilinçli olarak
      // Escape/dış tıklamayla kapanmaz, form emeği korunur) varsa alttaki detay KAPANMAZ.
      if (document.querySelector('.modal-overlay')) return
      onClose?.()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [enabled, onClose])
}
