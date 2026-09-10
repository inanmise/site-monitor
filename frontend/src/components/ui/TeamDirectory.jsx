import { createContext, useContext, useEffect, useMemo, useState } from 'react'
import { api } from '../../api/client'

/**
 * Proje geneli TAKIM dizini: id ↔ {id, name, email, leader_id, leader_display_name}.
 * UserDirectory'nin takım eşleniği: yüzeylerin yarısında yalnız team_name var (alarm geçmişi,
 * izleme kartları, istatistik) — id olmadan modal açılamaz; dizin adı id'ye çözer.
 * Sağlayıcı yoksa (testler) boş dizin döner; TeamBadge o zaman düz metin çizer.
 */
const EMPTY = { byId: new Map(), byName: new Map(), ready: false }
const TeamDirectoryCtx = createContext(EMPTY)

export function TeamDirectoryProvider({ children }) {
  const [maps, setMaps] = useState(null)
  useEffect(() => {
    let alive = true
    const p = api?.teams?.directory ? api.teams.directory() : Promise.resolve(null)
    Promise.resolve(p)
      .then(res => {
        if (!alive) return
        const byId = new Map(), byName = new Map()
        for (const tm of (res?.data || [])) {
          if (tm.id != null) byId.set(Number(tm.id), tm)
          if (tm.name) byName.set(String(tm.name).trim().toLowerCase(), tm)
        }
        setMaps({ byId, byName })
      })
      .catch(() => { if (alive) setMaps({ byId: new Map(), byName: new Map() }) })
    return () => { alive = false }
  }, [])
  const value = useMemo(() => (maps ? { ...maps, ready: true } : EMPTY), [maps])
  return <TeamDirectoryCtx.Provider value={value}>{children}</TeamDirectoryCtx.Provider>
}

export function useTeamDirectory() { return useContext(TeamDirectoryCtx) }
