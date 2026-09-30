import { useCallback, useEffect, useMemo, useState } from 'react'
import { compactErrors, focusFormError } from '../utils/formErrors.js'

/**
 * Alan-bazlı form hataları (2026-09-30): doğrulama hatası TOST yerine alanın altında görünür, sayfa ilk hatalı alana
 * kaydırılıp odaklanır; alan düzenlenince kendi hatası silinir; form yeniden açılınca (`resetKey` değişince) hepsi silinir.
 *
 * Kullanım:
 *   const fe = useFormErrors(modal)
 *   if (fe.check({ url: !form.url.trim() && t('mon.fieldRequired'), teamId: !form.teamId && t('mon.teamRequired') })) return
 *   <FormField label=… required {...fe.fieldProps('teamId')}>…
 *   onChange={v => { setForm(…); fe.clear('teamId') }}
 */
export function useFormErrors(resetKey) {
  const [errors, setErrors] = useState({})
  useEffect(() => { setErrors({}) }, [resetKey])

  /** Hata haritasını uygular ve ilk hatalıya kaydırır. Hata VARSA true döner (çağıran kaydı durdurur). */
  const check = useCallback((map) => {
    const errs = compactErrors(map)
    setErrors(errs || {})
    if (!errs) return false
    // Alanlar aynı render'da işaretlenir; kaydırma/odak boyandıktan sonra (rAF) — jsdom'da rAF varsa.
    const run = () => focusFormError(errs)
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(run); else run()
    return true
  }, [])

  const clear = useCallback((key) => {
    setErrors((e) => (e[key] ? { ...e, [key]: undefined } : e))
  }, [])
  const reset = useCallback(() => setErrors({}), [])
  const fieldProps = useCallback((key) => ({ name: key, error: errors[key] || undefined }), [errors])
  const hasErrors = useMemo(() => Object.values(errors).some(Boolean), [errors])

  return { errors, hasErrors, check, clear, reset, fieldProps }
}

export default useFormErrors
