import { useEffect } from 'react'

/** Sürüklenen bir DOSYA mı (metin / bağlantı sürüklemesine dokunulmaz)? */
export function isFileDrag(e) {
  const types = e?.dataTransfer?.types
  return !!types && Array.from(types).includes('Files')
}

/**
 * Pencere düzeyinde dosya bırakma koruması: grafik yuvasının DIŞINA bırakılan dosya tarayıcıyı o dosyaya götürmez.
 * Rapor düzenleyicisinde kaydedilmemiş taslak, Görsel panelinde panel ve ekran paylaşımı kaybolurdu (inceleme
 * 2026-10-18 — panelde eksikti). Yuvanın kendi bırakma işleyicisi önce çalışır; burada yalnız varsayılan engellenir.
 */
export function useFileDropGuard(enabled = true) {
  useEffect(() => {
    if (!enabled) return undefined
    const guard = (e) => { if (isFileDrag(e)) e.preventDefault() }
    window.addEventListener('dragover', guard)
    window.addEventListener('drop', guard)
    return () => {
      window.removeEventListener('dragover', guard)
      window.removeEventListener('drop', guard)
    }
  }, [enabled])
}
