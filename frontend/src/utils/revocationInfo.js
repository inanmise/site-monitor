/**
 * İptal (OCSP/CRL) durumunun NEDENİ — arayüzün tek kaynağı (2026-10-08, kullanıcı isteği: "Ham durum UNKNOWN; adres
 * göremedim. O alanları boş da olsa ekleyelim, boş olduğunu bilelim. Yanıltıcı uyarıları kaldıralım").
 *
 * <p>Sunucu `revocation_reason` yazar (backend `RevocationReason`): OCSP · CRL · NO_ENDPOINTS · UNSUPPORTED_SCHEME ·
 * UNREACHABLE · NO_ISSUER · NOT_CHECKED · PENDING. Bu değişiklikten önceki kayıtlarda alan yoktur: sertifika gerçekten
 * okunmuşsa (parmak izi var), durum UNKNOWN ve iki adres de boşsa NO_ENDPOINTS türetilir — backend
 * `RevocationReason.effective` ile AYNI kural.
 */
export const REVOCATION_REASONS = Object.freeze([
  'OCSP', 'CRL', 'NO_ENDPOINTS', 'UNSUPPORTED_SCHEME', 'UNREACHABLE', 'NO_ISSUER', 'NOT_CHECKED', 'PENDING',
])

/** Deneme hata kodları (backend `RevocationReason.FAIL_*`) — `hlth.revFail.<KOD>` ile çevrilir. */
export const REVOCATION_FAIL_CODES = Object.freeze([
  'SCHEME', 'BLOCKED', 'DNS', 'TIMEOUT', 'REFUSED', 'PROXY', 'TLS', 'HTTP', 'BAD_RESPONSE', 'UNKNOWN_CERT', 'NETWORK', 'OTHER',
])

const blank = (v) => v == null || String(v).trim() === ''

/** Gösterilecek neden kodu ya da null (bilinmiyor / eski kayıt + adres var). */
export function revocationReason(d) {
  if (!d) return null
  const stored = blank(d.revocation_reason) ? null : String(d.revocation_reason).trim().toUpperCase()
  if (stored) return stored
  const status = String(d.revocation_status || '').trim().toUpperCase()
  if (status !== 'UNKNOWN') return null
  if (blank(d.fingerprint)) return null   // sertifika okunmamış (hata satırı): adresler o yüzden boş
  return blank(d.ocsp_url) && blank(d.crl_url) ? 'NO_ENDPOINTS' : null
}

/** Sertifika iptal bilgisi yayımlamıyor mu? (denetlenecek bir şey yok — sorun değil, bilgi) */
export function hasNoRevocationInfo(d) {
  return revocationReason(d) === 'NO_ENDPOINTS'
}

/**
 * Bir denemeyi tek satır metne çevirir: "CRL · http://… — HTTP 404 döndü". `t` çeviri işlevi; tanınmayan kod ham
 * gösterilir (bilgi kaybolmaz).
 */
export function attemptText(a, t) {
  if (!a || typeof a !== 'object') return ''
  const code = String(a.code || '').toUpperCase()
  const key = `hlth.revFail.${code}`
  const label = REVOCATION_FAIL_CODES.includes(code) ? t(key, a.status ?? '') : (code || '—')
  const via = a.via ? String(a.via) : ''
  const url = a.url ? String(a.url) : ''
  return [via, url].filter(Boolean).join(' · ') + (label ? ` — ${label}` : '')
}
