import { useEffect, useState } from 'react'

/**
 * Ekran verilen genişliğin üstünde mi — yerleşim seçimi için TEK JS eşiği.
 *
 * <p>Yerleşimin kendisi CSS'te kalır (grid sütunu kırılım noktasında çöker); bu hook yalnız
 * "hangi BİLEŞEN render edilecek" sorusunu cevaplar: geniş ekranda yan panel
 * (`<aside>`), dar ekranda gerçek diyalog (`ModalShell`). İkisini birden render edip
 * CSS ile gizlemek, aynı içeriği DOM'da İKİ kez bulundurmak demekti — ekran okuyucu iki
 * kez okur, testler "birden çok eşleşme" ile kırılır.
 *
 * <p>`matchMedia` yoksa (eski tarayıcı / bazı test ortamları) GENİŞ kabul edilir: masaüstü
 * yerleşimi, dar ekranda bile okunabilir bir geri dönüştür; tersi (her şeyi modale almak)
 * masaüstü kullanıcısını gereksiz bir diyaloğa hapsederdi.
 */
export function useIsWide(minWidthPx = 1100) {
  const query = `(min-width: ${minWidthPx}px)`

  const [wide, setWide] = useState(() => {
    try { return window.matchMedia ? window.matchMedia(query).matches : true }
    catch { return true }
  })

  useEffect(() => {
    let mql
    try { mql = window.matchMedia ? window.matchMedia(query) : null }
    catch { mql = null }
    if (!mql) return

    const onChange = e => setWide(e.matches)
    setWide(mql.matches)
    // Safari 13 ve altı `addEventListener` desteklemez; eski API'ye düşülür.
    if (mql.addEventListener) mql.addEventListener('change', onChange)
    else if (mql.addListener) mql.addListener(onChange)

    return () => {
      if (mql.removeEventListener) mql.removeEventListener('change', onChange)
      else if (mql.removeListener) mql.removeListener(onChange)
    }
  }, [query])

  return wide
}

export default useIsWide
