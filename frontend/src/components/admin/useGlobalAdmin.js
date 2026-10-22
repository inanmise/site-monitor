import { usePermissions } from '../../contexts/PermissionsProvider.jsx'

/**
 * Global yönetici kapısı (20.165.1 bugfix · Theme G): "yalnız global yönetici" işlemleri `systemRole === 'ADMIN'` ile
 * DEĞİL bununla açılır — kapsamlı müdür (AD ADMIN, `viewTeamIds` dolu) da ADMIN'dir ama sunucu bu uçlarda
 * `SessionScope.isGlobalAdmin` ister (403).
 *
 * Kural: App'in verdiği `globalAdmin` prop'u (boolean) her zaman kazanır; verilmemişse `/api/me/permissions`
 * `scope.global_admin` (K1). Bilinmiyor (eski sunucu / snapshot gelmedi / sağlayıcı yok) = global DEĞİL (gizle).
 */
export function resolveGlobalAdmin(prop, scope) {
  if (typeof prop === 'boolean') return prop
  return scope?.global_admin === true
}

export function useGlobalAdmin(prop) {
  const { scope } = usePermissions()
  return resolveGlobalAdmin(prop, scope)
}
