import { createContext, useContext, useEffect, useState, useCallback } from 'react'
import { api } from '../api/client'

const PermissionsContext = createContext(null)

/**
 * Tek seferde kullanıcının yetki snapshot'unu çeker ve periyodik 60sn refresh ile
 * "anlık yansıma" sağlar. ADMIN matrix'i değiştirdiğinde aktif kullanıcılar en geç
 * 60sn içinde yeni yetkilerini görür; sayfa değişimi de manuel refresh tetikler.
 */
export function PermissionsProvider({ children, user }) {
  const [perms, setPerms] = useState({})

  const refresh = useCallback(async () => {
    if (!user) { setPerms({}); return }
    const res = await api.me.getPermissions()
    if (res?.success) setPerms(res.data || {})
  }, [user])

  useEffect(() => {
    refresh()
    if (!user) return
    const t = setInterval(refresh, 60_000)
    return () => clearInterval(t)
  }, [refresh, user])

  return (
    <PermissionsContext.Provider value={{ perms, refresh }}>
      {children}
    </PermissionsContext.Provider>
  )
}

export function usePermissions() {
  const ctx = useContext(PermissionsContext)
  const can = (resource, action) => Boolean(ctx?.perms?.[resource]?.[action])
  return {
    perms: ctx?.perms ?? {},
    canView:    (r) => can(r, 'view'),
    canEdit:    (r) => can(r, 'edit'),
    canExecute: (r) => can(r, 'execute'),
    refresh: ctx?.refresh ?? (() => {}),
  }
}
