import { useEffect, useRef, useState } from 'react'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { importWithDeadline } from '../../utils/importWithDeadline.js'
import { clientFailure } from '../../utils/errorMessages.js'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { onSessionReset } from '../../utils/sessionResetBus.js'
import { GAPS_PROMPTED_KEY, onGapsRefreshRequest, onOpenGapsDialog } from './gapsBus.js'

/** Bir kez inen denetleyici (yeniden bağlanmada — çıkış / giriş — beklemeden çizilir). */
let cachedCore = null

/** Denetleyici modülünü süre sınırlı yükler (önbelleğe alır). Testler de önceden yüklemek için kullanır. */
export function loadGapsCore() {
  return importWithDeadline(() => import('./GapsPromptCore.jsx')).then((m) => {
    if (m?.default) cachedCore = m.default
    return cachedCore
  })
}

function promptedFor(username) {
  try { return sessionStorage.getItem(GAPS_PROMPTED_KEY) === username } catch { return false }
}

/**
 * Giriş sonrası eksikler penceresinin İNCE kabuğu (performans 2026-11-17). App bunu her oturumda bağlar; eskiden eksikler
 * modelleri, kaynak yükleyicisi, toplu düzeltme ve alarm iletimi modelleri statik içe aktarmayla AÇILIŞ paketine giriyordu.
 * Ağır denetleyici (`GapsPromptCore.jsx`) artık yalnız gerektiğinde, süre sınırlı tembel içe aktarmayla iner:
 *   • kendiliğinden gösterme koşulları sağlanınca (açık, kullanıcı belli, yetkiler + tercihler okundu, bu sekmede henüz
 *     gösterilmedi — denetleyicinin kendi kuralıyla aynı ön koşul; asıl karar yine denetleyicide);
 *   • kullanıcı menüsü "Eksiklerim" ya da Kişisel Panom "Denetle" isteğiyle (istek kaybolmaz: modül inince devredilir).
 * Modül inemezse (bayat paket, ağ) bekleyen istek UNUTULUR (inceleme 2026-11-17: yoksa kaybolan "Eksiklerim" tıklaması sonraki
 * bir Panom yenilemesinde pencereyi kendiliğinden açıyordu); kişinin açma isteğiyse bildirim gösterilir. Sonraki istek
 * yeniden dener.
 */
export default function GapsPrompt(props) {
  const { username, enabled = true, prefsSettled = true } = props
  const { loaded: permsLoaded = true } = usePermissions()
  const t = useT()
  const toast = useToast()
  const notifyRef = useRef(null)               // yükleme hatası bildirimi (son çizimin dili / toast'u — etki bağımlılığı değil)
  notifyRef.current = (e) => {
    const f = clientFailure(e, { fallback: t('gaps.openFailed'), chunk: t('te.pageUpdated') })
    toast.error(f.message, { details: f.details })
  }
  const [Core, setCore] = useState(() => cachedCore)
  const [want, setWant] = useState(0)          // > 0 → denetleyici gerekli (artış = yeniden dene)
  const pendingRef = useRef(null)              // modül inerken gelen istek: 'open' | 'refresh'

  // Kendiliğinden yol: koşullar sağlanınca denetleyici yüklenir (denetleyici kendi zamanlayıcısıyla karar verir)
  useEffect(() => {
    if (Core || !enabled || !username || !permsLoaded || !prefsSettled || promptedFor(username)) return
    setWant((n) => (n > 0 ? n : 1))
  }, [Core, enabled, username, permsLoaded, prefsSettled])

  // Modül inmeden gelen menü / Panom istekleri — denetleyici bağlanınca kendi dinleyicilerini kurar
  useEffect(() => {
    if (Core) return undefined
    const offOpen = onOpenGapsDialog(() => { if (enabled) { pendingRef.current = 'open'; setWant((n) => n + 1) } })
    const offRefresh = onGapsRefreshRequest(() => {
      if (!enabled) return
      if (pendingRef.current !== 'open') pendingRef.current = 'refresh'
      setWant((n) => n + 1)
    })
    return () => { offOpen(); offRefresh() }
  }, [Core, enabled])

  // Çıkış / kullanıcı değişimi: denetleyici yüklenmediyse de bu sekmenin "gösterildi" işareti ve bekleyen istek unutulur
  useEffect(() => onSessionReset(() => {
    pendingRef.current = null
    try { sessionStorage.removeItem(GAPS_PROMPTED_KEY) } catch { /* yok */ }
  }), [])

  useEffect(() => {
    if (Core || want === 0) return undefined
    let alive = true
    loadGapsCore().then(
      (c) => { if (alive && c) setCore(() => c) },
      (e) => {
        if (!alive) return
        // inemedi: bekleyen istek unutulur (geç bir Panom yenilemesi pencereyi kendiliğinden açmasın); açma isteğiyse söylenir
        const lost = pendingRef.current
        pendingRef.current = null
        if (lost === 'open') notifyRef.current?.(e)
      },
    )
    return () => { alive = false }
  }, [Core, want])

  if (!Core) return null
  return <Core {...props} pendingRef={pendingRef} />
}
