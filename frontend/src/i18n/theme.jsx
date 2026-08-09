import { createContext, useContext, useState, useEffect, useCallback } from 'react'

const STORAGE_KEY = 'site-monitor-theme'

// Context nesnesi globalThis'e sabitlenir ve varsayılanı çalışır durumdadır —
// gerekçesi i18n/index.jsx'teki LangCtx yorumunda (HMR çift-modülü + hata yüzeyi).
const FALLBACK_THEME_CTX = { theme: 'light', toggle: () => {}, fallback: true }

const ThemeCtx = (globalThis.__smThemeCtx ??= createContext(FALLBACK_THEME_CTX))

export function ThemeProvider({ children }) {
  const [theme, setTheme] = useState(() => {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored) return stored
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  })

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme)
  }, [theme])

  const toggle = useCallback(() => {
    setTheme(prev => {
      const next = prev === 'dark' ? 'light' : 'dark'
      localStorage.setItem(STORAGE_KEY, next)
      return next
    })
  }, [])

  return <ThemeCtx.Provider value={{ theme, toggle }}>{children}</ThemeCtx.Provider>
}

export function useTheme() {
  return useContext(ThemeCtx) ?? FALLBACK_THEME_CTX
}
