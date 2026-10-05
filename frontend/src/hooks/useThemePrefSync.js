import { useEffect } from 'react'
import { useTheme } from '../i18n/theme.jsx'
import { THEME_PREF_KEY } from './userPrefsModel.js'

/**
 * Tema seçimi ↔ kişisel tercihler (2026-10-05, ürün kararı: "kullanıcının şema seçimlerini hatırlayalım, sonraki
 * oturumlarında otomatik olarak o temada açalım"). Girişte tercihler motoru sunucudaki seçimi localStorage'a yazdıysa
 * (`hydratedKeys` onu içerir) ThemeProvider saklı seçimi yeniden okur → tema yeniden yüklemeden değişir. Yalnız OKUR:
 * yazım yok, dolayısıyla PUT döngüsü de yok. Seçimin sunucuya gidişi ayrıca kod gerektirmez — ThemeProvider localStorage'a
 * yazar, motor beyaz listeli anahtarı izleyip toplu PUT'a koyar.
 */
export function useThemePrefSync(hydratedKeys) {
  const { reloadChoice } = useTheme()
  useEffect(() => {
    if (Array.isArray(hydratedKeys) && hydratedKeys.includes(THEME_PREF_KEY)) reloadChoice()
  }, [hydratedKeys, reloadChoice])
}

export default useThemePrefSync
