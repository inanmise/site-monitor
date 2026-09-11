import { useCallback, useEffect, useState } from 'react'

/**
 * Uzun düzenleme modallarında "Devamı için kaydırın" ipucunun beyni — dokuz izleme formu +
 * envanter formu için TEK kopya.
 *
 * <p><b>Neden var.</b> İzleme modalları tek parça kaydırılıyordu: alt bardaki Kaydet/İptal/Sil
 * formla birlikte yukarı kayıyor, uzun formlarda kullanıcı "düğmeler nerede?" diye aşağı
 * arıyordu. Envanter formu bunu çözmüştü (sabit başlık + kaydırılan gövde + sabit alt bar +
 * zıplayan ipucu) ama mantığı bileşenin içinde yaşıyordu. Buraya taşındı ki her form aynı
 * davransın ve düzeltme bir yerde yapılsın.
 *
 * <p>Modal koşullu çizildiği için (modal && createPortal) düz `useRef` ilk effect'te boş
 * kalırdı; bu yüzden CALLBACK ref: eleman bağlanınca `el` state'i dolar ve effect o zaman kurulur.
 *
 * @returns {{ref: Function, show: boolean, scrollMore: Function}}
 *   `ref` kaydırılan gövdeye (`.modal-scroll-body`) verilir; `show` ipucunun görünürlüğü;
 *   `scrollMore` ipucuna tıklanınca 200px aşağı kaydırır.
 */
export function useModalScrollHint() {
  const [el, setEl] = useState(null)
  const [show, setShow] = useState(false)

  useEffect(() => {
    if (!el) { setShow(false); return undefined }
    const check = () => {
      const hasOverflow = el.scrollHeight > el.clientHeight + 2
      const atBottom    = el.scrollTop + el.clientHeight >= el.scrollHeight - 6
      setShow(hasOverflow && !atBottom)
    }
    check()
    el.addEventListener('scroll', check, { passive: true })
    // jsdom'da ResizeObserver test/setup.js ile no-op; tarayıcıda içerik büyüyünce (test sonucu
    // kutusu, uyarı satırı) ipucu yeniden değerlendirilir.
    const ro = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(check) : null
    ro?.observe(el)
    return () => { el.removeEventListener('scroll', check); ro?.disconnect() }
  }, [el])

  const scrollMore = useCallback(() => {
    el?.scrollBy?.({ top: 200, behavior: 'smooth' })
  }, [el])

  return { ref: setEl, show, scrollMore }
}
