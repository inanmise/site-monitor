/**
 * Ekrana özgü kısayol bağlamları (2026-11-20, A4). Bir ekran (ör. kanal raporu düzenleyicisi) kendi kısayollarını yalnız
 * KURULUYKEN `?` listesinde göstermek için bağlam kaydeder; ikinci bir `?` işleyicisi yazılmaz (KeyboardShortcuts tek
 * dinleyicidir). Liste penceresi açılırken {@link activeShortcutContexts} okunur.
 *
 * Saf modül: React'e ve ağa bağlı değildir; açılış paketine küçük bir küme ekler.
 */
const counts = new Map()

/** Bağlamı etkinleştirir (aynı bağlam iç içe kurulabilir — sayaçla); dönen işlev kaydı bir kez geri alır. */
export function registerShortcutContext(id) {
  if (typeof id !== 'string' || !id) return () => {}
  counts.set(id, (counts.get(id) || 0) + 1)
  let done = false
  return () => {
    if (done) return
    done = true
    const n = (counts.get(id) || 0) - 1
    if (n > 0) counts.set(id, n)
    else counts.delete(id)
  }
}

/** Şu an etkin bağlamlar. */
export function activeShortcutContexts() {
  return new Set(counts.keys())
}

/** Kanal raporu düzenleyicisinin bağlamı. */
export const TE_EDITOR_CONTEXT = 'te-editor'

/** Olay ve Hata Geçmişi sayfasının bağlamı (2026-11-20, "Hızlı Giriş"): `N` yeni olay, `E` açık ayrıntıyı düzenle. */
export const INCIDENT_HISTORY_CONTEXT = 'incident-history'

/**
 * Nöbet Rutinleri sayfasının bağlamı (2026-11-26): `J`/`K` görevler arası, `X` işaretle, `N` not, `P` sorun, `S` atla, `O`
 * bağlantılar, `Shift+D` bölümü tamamla, `C` günü tamamla, `[`/`]` önceki / sonraki gün, `T` bugün. Sayfa kuruluyken kayıtlı.
 */
export const OR_PAGE_CONTEXT = 'or-page'
