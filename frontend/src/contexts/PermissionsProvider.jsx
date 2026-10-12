import { createContext, useContext, useEffect, useMemo, useState, useCallback } from 'react'
import { api } from '../api/client'
import { useVisibleInterval } from '../hooks/useVisibleInterval'

// globalThis pin (2026-09-26): HMR çift-modül örneğinde sağlayıcı/hook ayrı context'e düşmesin (i18n/Sidebar/Toast deseni)
const PermissionsContext = (globalThis.__smPermissionsCtx ??= createContext(null))

/**
 * Tek seferde kullanıcının yetki snapshot'unu çeker ve periyodik 60sn refresh ile
 * "anlık yansıma" sağlar. ADMIN matrix'i değiştirdiğinde aktif kullanıcılar en geç
 * 60sn içinde yeni yetkilerini görür; sayfa değişimi de manuel refresh tetikler.
 *
 * Yeniden çizim (2026-10-09): bağlam değeri useMemo'lu ve DEĞİŞMEYEN snapshot state'i yenilemez (JSON eşitliği) —
 * eskiden her yoklama yeni nesne yazıyor, her `usePermissions` tüketicisi dakikada bir boşuna yeniden çiziliyordu.
 */
export function PermissionsProvider({ children, user }) {
  const [perms, setPerms] = useState({})
  // İlk snapshot geldi mi (2026-10-12): gelmeden boş `{}` "hiç yetki yok" ile karışmasın — yetkiye göre süzülen bir
  // listeyi kaydeden ekranlar (Panom) yüklenene kadar yazmaz. Oturum kapanınca (user yok) yeniden false.
  const [loaded, setLoaded] = useState(false)

  const refresh = useCallback(async () => {
    if (!user) { setPerms(keepIfSame({})); setLoaded(false); return }
    let res = null
    try { res = await api.me.getPermissions() } catch { return }   // ağ hatası: önceki snapshot kalır, 60 sn sonra yeniden
    if (res?.success) { setPerms(keepIfSame(res.data || {})); setLoaded(true) }
  }, [user])

  useEffect(() => { refresh() }, [refresh])   // ilk + user değişince (user yoksa perms temizlenir)
  useVisibleInterval(refresh, user ? 60_000 : 0, false)   // periyodik (yalnız user varken), gizli sekmede durur

  const value = useMemo(() => ({ perms, refresh, loaded }), [perms, refresh, loaded])

  return (
    <PermissionsContext.Provider value={value}>
      {children}
    </PermissionsContext.Provider>
  )
}

/** State güncelleyicisi: içerik aynıysa ÖNCEKİ nesneyi korur (React aynı referansta yeniden çizmez). */
function keepIfSame(next) {
  return (prev) => {
    if (prev === next) return prev
    try { return JSON.stringify(prev) === JSON.stringify(next) ? prev : next } catch { return next }
  }
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
    // Sağlayıcı yoksa (eski testler / bağımsız çizim) yüklenmiş sayılır — davranış eskisiyle aynı
    loaded: ctx ? ctx.loaded !== false : true,
  }
}
