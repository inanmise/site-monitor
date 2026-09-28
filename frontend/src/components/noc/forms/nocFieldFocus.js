/**
 * "7/24 ayarını düzenle" kısayolu (2026-09-28, 7/24 Kapsamı → izleme): derin bağlantı düzenleme formunu açmadan HEMEN
 * önce bir odak isteği bırakır; formun ortak "7/24 izleme ekibine bildir" alanı (NocNotifyField) bağlanınca isteği
 * TÜKETİR, kendini görünüme kaydırır ve anahtarı odaklar.
 *
 * <p>Neden prop değil: alan dokuz izleme formunda + envanter formunda (SSL) yaşıyor; formların hiçbirinin bu kısayoldan
 * haberi olması gerekmiyor. İstek TÜR anahtarlı (yalnız o türün alanı tüketir) ve SÜRELİ (form hiç açılmazsa — yetki
 * yok, kayıt yüklenemedi — sonra elle açılan başka bir form odağı çalmasın). Saf modül: React yok, birim testte sınanır.
 */
let pending = null   // { type, until }

/** Varsayılan ömür: envanter formu kaydı sunucudan okur (SSL) — yavaş ağda da yetişsin. */
export const NOC_FOCUS_TTL_MS = 10_000

export function requestNocFieldFocus(type, ttlMs = NOC_FOCUS_TTL_MS) {
  pending = type ? { type: String(type), until: Date.now() + ttlMs } : null
}

/** İstek bu tür için geçerliyse true döner ve isteği siler (bir kez); süresi dolmuşsa yalnız siler. */
export function consumeNocFieldFocus(type) {
  if (!pending) return false
  if (Date.now() > pending.until) { pending = null; return false }
  if (pending.type !== String(type)) return false
  pending = null
  return true
}
