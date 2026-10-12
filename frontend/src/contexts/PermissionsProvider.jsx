import { createContext, useContext, useEffect, useMemo, useRef, useState, useCallback } from 'react'
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
  // Son okuma başarısız (inceleme 4, 2026-10-12): `loaded` false kalırken ekranlar sonsuza dek "yükleniyor" demesin —
  // yeniden dene gösterebilsinler. Bir snapshot gelince false. Yüklendikten SONRAKİ yoklama hatası da true yapar ama önceki
  // snapshot (ve `loaded`) korunur; ekranlar "okunamadı"yı yalnız `!loaded && failed` iken gösterir.
  const [failed, setFailed] = useState(false)
  // Kullanıcı değişimi ÇİZİM sırasında (inceleme 5): efekt çalışana kadar ilk çizimde yeni kullanıcı eski kullanıcının
  // yetkileriyle çizilmesin — React bu çizimi atıp sıfırlanmış state ile hemen yeniden çizer (alt bileşenler eskiyi görmez).
  const [renderUser, setRenderUser] = useState(() => userKey(user))
  if (renderUser !== userKey(user)) {
    setRenderUser(userKey(user))
    setPerms(keepIfSame({}))
    setLoaded(false)
    setFailed(false)
  }
  // Sıra koruması (inceleme 4): çıkıştan / kullanıcı değişiminden SONRA gelen eski yanıt eski kullanıcının yetkilerini
  // yazmasın. Her istek bir sıra alır; yalnız EN SON isteğin yanıtı uygulanır.
  const seq = useRef(0)
  const lastUser = useRef(userKey(user))

  const refresh = useCallback(async () => {
    const my = ++seq.current
    const key = userKey(user)
    if (lastUser.current !== key) {
      // Kullanıcı değişti: önceki kullanıcının snapshot'ı yeni kullanıcıya bir an bile uygulanmaz
      lastUser.current = key
      setPerms(keepIfSame({}))
      setLoaded(false)
      setFailed(false)
    }
    if (!user) { setPerms(keepIfSame({})); setLoaded(false); setFailed(false); return }
    let res = null
    try { res = await api.me.getPermissions() } catch { res = null }   // ağ hatası: önceki snapshot kalır, 60 sn sonra yeniden
    if (my !== seq.current) return
    if (res?.success) { setPerms(keepIfSame(res.data || {})); setLoaded(true); setFailed(false) }
    else setFailed(true)
  }, [user])

  useEffect(() => { refresh() }, [refresh])   // ilk + user değişince (user yoksa perms temizlenir)
  useVisibleInterval(refresh, user ? 60_000 : 0, false)   // periyodik (yalnız user varken), gizli sekmede durur

  const value = useMemo(() => ({ perms, refresh, loaded, failed }), [perms, refresh, loaded, failed])

  return (
    <PermissionsContext.Provider value={value}>
      {children}
    </PermissionsContext.Provider>
  )
}

/** Kullanıcı kimliği: App kullanıcı adını (metin) verir; nesne verilirse adı — aynı kişinin yeni nesnesi "değişim" değildir. */
function userKey(user) {
  if (user && typeof user === 'object') return user.username ?? user.id ?? null
  return user ?? null
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
    // Son okuma başarısız oldu mu (yüklenmemişken anlamlı: "yeniden dene" gösterilir)
    failed: ctx ? ctx.failed === true : false,
  }
}
