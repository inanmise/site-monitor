/**
 * Envantere alan adı EKLENDİ olayı (2026-09-28).
 *
 * <p>Neden var (kullanıcı: "yeni eklenen alan adının kartında sağlık, açık alarm, sorumlu kişi bir süre boş görünüyor,
 * sonradan geliyor"): ekleme ucu ilk sertifika kontrolünü ARKA PLANDA başlatır; Genel Bakış kayıttan hemen sonra bir kez
 * tazelenir ama o anda kontrol bitmemiştir ve kart ekleri boş gelir — sonraki tazeleme 5 dk sonradır. Olay, Genel
 * Bakış'a "bu alan adının verisi gelene kadar kısa aralıklarla tazele" der (App.jsx dinler).
 *
 * <p>Tek kaynak: `api/client.js` `request()` başarılı her yazmada {@link inventoryAddedDomain} ile bakar ve olayı yayar —
 * ekleme Genel Bakış'tan, Envanter sayfasından, aktarımdan ya da çöp kutusundan geri yüklemeden yapılsa da aynı yol.
 */
export const INVENTORY_ADDED_EVENT = 'sm:inventory-added'

/** Ekle (POST /admin/inventory), aktar, geri yükle — yeni bir kartın doğduğu yazmalar. */
const ADD_PATH = /^\/admin\/inventory(?:\/[^/?]+\/(?:restore|transfer|transfer-ug))?(?:[?]|$)/

/** Bu başarılı yanıt yeni bir kart doğurdu mu? Doğurduysa alan adını döner, yoksa null. */
export function inventoryAddedDomain(path, options = {}, json = null) {
  const method = String(options.method || 'GET').toUpperCase()
  if (method !== 'POST') return null
  if (!ADD_PATH.test(String(path || ''))) return null
  const d = json && typeof json === 'object' ? (json.data?.domain ?? json.domain ?? null) : null
  return typeof d === 'string' && d.trim() ? d.trim() : null
}

/** Olayı yayar (tarayıcı dışı ortamda sessiz). */
export function announceInventoryAdded(domain) {
  if (!domain) return
  try { window.dispatchEvent(new CustomEvent(INVENTORY_ADDED_EVENT, { detail: { domain } })) } catch { /* window yok */ }
}

/**
 * Envanter kaydı YENİDEN ADLANDIRILDI olayı (2026-10-08).
 *
 * <p>Neden var (kullanıcı: "takip adı değiştirince Sağlık bilgisi / Kontrol geçmişi 404, Manuel Sertifikalar sayfasında
 * değişiklik hemen görülmüyor"): ad, sertifika penceresinin Düzenle'sinden (App'in envanter formu) değişince açık pencere
 * ESKİ adla istek atmayı sürdürüyordu, liste sayfaları da kendi formları dışından gelen değişikliği bilmiyordu. Olay,
 * eski adı tutan her yüzeye "bu kayıt artık şu adda" der: App açık pencereyi yeni ada taşır (sekme korunur), Manuel
 * Sertifikalar ve Envanter satırı anında yeniden adlandırıp listeyi tazeler.
 *
 * <p>Tek kaynak: envanter formu ({@code InventoryFormModal}) başarılı DÜZENLEMEDE eski ad ≠ kaydedilen ad ise yayar —
 * güncelleme ucu kaydı kimlikle bulur, eski adı yalnız form bilir. Ad sunucunun döndürdüğü (küçük harfe çevrilmiş) addır.
 */
export const INVENTORY_RENAMED_EVENT = 'sm:inventory-renamed'

/** Olayı yayar; ad gerçekten değişmediyse (ya da tarayıcı dışı ortamda) sessiz. */
export function announceInventoryRenamed(from, to) {
  if (!from || !to || from === to) return
  try { window.dispatchEvent(new CustomEvent(INVENTORY_RENAMED_EVENT, { detail: { from, to } })) } catch { /* window yok */ }
}

/** Satırın adı {@code from} ise yeni adla bir kopyası, değilse kendisi (liste / çekmece için iyimser yeniden adlandırma). */
export function renameRow(row, from, to, key = 'domain') {
  return row && row[key] === from ? { ...row, [key]: to } : row
}
