import { createContext, useContext, useState, useEffect, useCallback } from 'react'

const STORAGE_KEY = 'site-monitor-theme'

// Context nesnesi globalThis'e sabitlenir ve varsayılanı çalışır durumdadır —
// gerekçesi i18n/index.jsx'teki LangCtx yorumunda (HMR çift-modülü + hata yüzeyi).
const FALLBACK_THEME_CTX = { theme: 'light', toggle: () => {}, fallback: true }

const ThemeCtx = (globalThis.__smThemeCtx ??= createContext(FALLBACK_THEME_CTX))

// localStorage erişimi TRY/CATCH şart: ThemeProvider ErrorBoundary'nin ÜSTÜNDE (main.jsx) —
// burada fırlayan hata sınırca yakalanamaz ve uygulama hata mesajı bile veremeden TAM BEYAZ EKRAN
// olur. Depolama kurumsal politika/gizli mod/kota nedeniyle kapalı olabilir.
function storedTheme() {
  try { return localStorage.getItem(STORAGE_KEY) } catch { return null }
}
function persistTheme(v) {
  try { localStorage.setItem(STORAGE_KEY, v) } catch { /* depolama yok: tema yalnız bu oturumda geçerli */ }
}

export function ThemeProvider({ children }) {
  const [theme, setTheme] = useState(() => {
    const stored = storedTheme()
    if (stored) return stored
    try {
      return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
    } catch { return 'light' }
  })

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme)
  }, [theme])

  const toggle = useCallback(() => {
    setTheme(prev => {
      const next = prev === 'dark' ? 'light' : 'dark'
      persistTheme(next)
      return next
    })
  }, [])

  return <ThemeCtx.Provider value={{ theme, toggle }}>{children}</ThemeCtx.Provider>
}

export function useTheme() {
  return useContext(ThemeCtx) ?? FALLBACK_THEME_CTX
}
