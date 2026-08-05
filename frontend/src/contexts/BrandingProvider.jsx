import { createContext, useContext, useEffect, useState, useCallback } from 'react'
import { api } from '../api/client'

const BrandingContext = createContext(null)

/**
 * Branding (beyaz etiket): açılışta PUBLIC /api/branding'i çeker (auth GEREKMEZ — login sayfası da
 * markalanır, bu yüzden provider main.jsx'te App'in üstünde durur). Etkiler:
 *  - tab-title → document.title
 *  - primary-color → :root'ta --brand-primary (App.css'te --primary buna bağlı)
 * Boş değerler = varsayılan Site Monitör kimliği; tüketiciler (Login/Nav/banner) fallback uygular.
 */
export function BrandingProvider({ children }) {
  const [branding, setBranding] = useState(null)

  /** fresh=true → cache-bust (admin kaydı sonrası anında yansıma); açılış çağrısı cache'li kalır. */
  const refresh = useCallback(async (fresh = false) => {
    try {
      const res = await api.getBranding(fresh)
      if (res?.success) setBranding(res.data || {})
    } catch { /* branding olmadan da uygulama çalışır */ }
  }, [])

  useEffect(() => { refresh() }, [refresh])

  useEffect(() => {
    if (!branding) return
    if (branding.tab_title) document.title = branding.tab_title
    const root = document.documentElement
    if (branding.primary_color) root.style.setProperty('--brand-primary', branding.primary_color)
    else root.style.removeProperty('--brand-primary')
  }, [branding])

  return (
    <BrandingContext.Provider value={{ branding: branding ?? {}, refresh }}>
      {children}
    </BrandingContext.Provider>
  )
}

export function useBranding() {
  const ctx = useContext(BrandingContext)
  const b = ctx?.branding ?? {}
  return {
    branding: b,
    /** Dolu branding değeri; boşsa fallback (genelde i18n karşılığı). */
    get: (key, fallback = '') => (b[key] != null && String(b[key]).trim() !== '' ? b[key] : fallback),
    refresh: ctx?.refresh ?? (() => {}),
  }
}
