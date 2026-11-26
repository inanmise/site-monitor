/**
 * Denetim zinciri doğrulamasının BAŞARISIZ yanıtı → ekran durumu (H paketi 2026-11-26, yönetim B2).
 *
 * Sunucu iki durumda 409 döner ve ikisi de zincirin BOZUK olduğu anlamına GELMEZ:
 *   - `AUDIT_VERIFY_INCOMPLETE`: tarama süre sınırına takıldı — gövdede `checked`, `checked_to_seq`, `unverified`
 *     ("şu kayda kadar denetlendi, kalan doğrulanmadı");
 *   - `AUDIT_VERIFY_RUNNING`: bu pod'da başka bir doğrulama sürüyor — birkaç saniye sonra yeniden denenir.
 * Ekran ikisini de uyarı tonuyla, sunucunun metniyle gösterir; asla yeşil "doğrulandı" ya da kırmızı "zincir bozuk".
 * Başka her başarısızlık (ağ, 5xx, eski sunucu) bugünkü genel "Doğrulama hatası" rozetidir.
 */
export const INTEGRITY_INCOMPLETE = 'AUDIT_VERIFY_INCOMPLETE'
export const INTEGRITY_RUNNING = 'AUDIT_VERIFY_RUNNING'

const num = (v) => (typeof v === 'number' && Number.isFinite(v) && v >= 0 ? v : null)

/** Başarısız doğrulama yanıtı (ya da reddedilen istek: r = null) → `{ error: true, code, message, checked, … }`. */
export function integrityFailure(r) {
  const body = r && typeof r === 'object' ? r : {}
  const data = body.data && typeof body.data === 'object' ? body.data : {}
  const rawCode = body.code || body.error_code || null
  const code = rawCode === INTEGRITY_INCOMPLETE || rawCode === INTEGRITY_RUNNING ? rawCode : null
  const message = typeof body.error === 'string' && body.error.trim() ? body.error.trim() : null
  return {
    error: true,
    code,
    message: code ? message : null,
    checked: num(body.checked ?? data.checked),
    checked_to_seq: num(body.checked_to_seq ?? data.checked_to_seq),
    unverified: num(body.unverified ?? data.unverified),
  }
}

/** Uyarı şeridi gösterilecek mi (tamamlanamadı / sürüyor). */
export function isIntegrityNotice(integrity) {
  return !!integrity?.error && (integrity.code === INTEGRITY_INCOMPLETE || integrity.code === INTEGRITY_RUNNING)
}
