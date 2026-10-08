/**
 * Kullanıcı kimliği yardımcıları (2026-10-08, kullanıcı kararı: "bir kullanıcı başka bir kullanıcının id'sini
 * okuyamasın"). Sunucu global admin'e sayısal id, diğer herkese opak kimlik (UUID metni) döner; arayüz kimliği ASLA
 * sayıya çevirmez (Number("…-uuid-…") = NaN → seçim kaybolur, eşleşme bozulur).
 */

/** Gönderim değeri: yalnız rakamsa sayı (global admin — bugünkü gövde bayt bayt aynı), değilse opak kimlik metni. */
export const userRefValue = (v) => {
  if (v == null) return null
  const s = String(v).trim()
  if (s === '') return null
  return /^[0-9]+$/.test(s) ? Number(s) : s
}

/** İki kullanıcı kimliği aynı kişi mi (sayı ↔ rakam metni farkını yok sayar). */
export const sameUser = (a, b) => a != null && b != null && String(a) === String(b)
