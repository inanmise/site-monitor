import { createContext, useContext, useEffect, useState, useCallback, useMemo } from 'react'
import { api } from '../../api/client'

// Proje geneli kullanıcı dizini: username/e-posta → {id, username, display_name, email}.
// Bir kez çekilir (login sonrası), her UserBadge buradan ad-soyad + avatar çözer. Böylece
// düzinelerce backend DTO'suna user_id eklemeden, sadece username (veya e-posta) olan her yerde
// ad-soyad+resim gösterebiliriz.
const UserDirectoryCtx = createContext({ lookup: () => null, lookupByEmail: () => null, ready: false })

export function UserDirectoryProvider({ children }) {
  const [maps, setMaps] = useState(null) // { byName, byEmail } | null

  useEffect(() => {
    let alive = true
    api.users.directory()
      .then(res => {
        if (!alive) return
        const byName = new Map(), byEmail = new Map()
        for (const u of (res?.data || [])) {
          if (u.username) byName.set(String(u.username).toLowerCase(), u)
          if (u.email) byEmail.set(String(u.email).toLowerCase(), u)
        }
        setMaps({ byName, byEmail })
      })
      .catch(() => { if (alive) setMaps({ byName: new Map(), byEmail: new Map() }) })
    return () => { alive = false }
  }, [])

  // Audit actor küçük harf, AppUser.username büyük harf olabilir → küçük-harf eşleştir (case-insensitive).
  const lookup = useCallback((username) => {
    if (!username || !maps) return null
    return maps.byName.get(String(username).toLowerCase()) || null
  }, [maps])

  const lookupByEmail = useCallback((email) => {
    if (!email || !maps) return null
    return maps.byEmail.get(String(email).toLowerCase()) || null
  }, [maps])

  const value = useMemo(() => ({ lookup, lookupByEmail, ready: maps != null }), [lookup, lookupByEmail, maps])
  return <UserDirectoryCtx.Provider value={value}>{children}</UserDirectoryCtx.Provider>
}

export function useUserDirectory() { return useContext(UserDirectoryCtx) }
