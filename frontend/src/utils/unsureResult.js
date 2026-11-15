/**
 * Bir YAZMA isteğinin sonucu bilinmiyor mu (2026-11-15, prod kapısı incelemesi). İstek gönderildi ama yanıt gelmedi —
 * istemci zaman aşımı (`REQUEST_TIMEOUT`, durum 0), ağ kopması (`NETWORK_ERROR` / `OFFLINE`) — ya da araya giren ağ geçidi
 * kesti (502 / 503 / 504; ör. NetScaler / Route zaman aşımı dolup sunucu işi sürdürürken). Bu durumda işlem sunucuda
 * tamamlanmış OLABİLİR: yan etkili çağıran durumu YENİDEN OKUR ve kullanıcıya "sonuç bilinmiyor — tekrarlamadan önce
 * kontrol edin" der; körlemesine yeniden denemez (ikinci tıklama aynı postayı ikinci kez gönderebilir).
 *
 * <p>Sunucunun kesin reddi (400 / 403 / 404 / 409 …) "bilinmiyor" DEĞİLDİR. Yanıt hiç yoksa (null / undefined —
 * çağıran istisna yakaladı) bilinmiyordur. Saf modül: React'e ve `api/client.js`'e bağlı değil.
 */
const UNSURE_CODES = new Set(['REQUEST_TIMEOUT', 'NETWORK_ERROR', 'OFFLINE', 'TIMEOUT', 'GATEWAY_TIMEOUT', 'NO_RESPONSE'])
const UNSURE_STATUSES = new Set([0, 502, 503, 504])

/** `res` başarısız bir yanıt ya da yakalanan istisna; true = sonuç bilinmiyor (yeniden okuyup kullanıcıya söyleyin). */
export function isUnsureResult(res) {
  if (res == null || typeof res !== 'object') return true
  if (res.success === true) return false
  const rawStatus = res.status ?? res.errorInfo?.status
  if (rawStatus !== undefined && rawStatus !== null && rawStatus !== '' && UNSURE_STATUSES.has(Number(rawStatus))) return true
  return UNSURE_CODES.has(String(res.code ?? res.errorInfo?.code ?? ''))
}
