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
