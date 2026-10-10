/**
 * TLS notu neden kataloğu — backend `tlsgrade.TlsGradeRules.Reason` ile BİREBİR aynı sıra ve tavan (2026-10-10).
 *
 * Arayüz notu HESAPLAMAZ (sunucu hesaplar); bu liste yalnız "notu hangi neden belirledi" (tavanı nota eşit olan) ve
 * metin anahtarı (`tlsg.reason.<KOD>.title|why|fix`) için. Kapı: backend `TlsGradeI18nGateTest` (sıra + tavan + TR/EN
 * metinler). Tavan null = bilgi notu (notu etkilemez).
 */
export const TLS_GRADE_REASONS = [
  ['CERT_EXPIRED', 'F'],
  ['HOSTNAME_MISMATCH', 'F'],
  ['CERT_UNTRUSTED', 'F'],
  ['CERT_REVOKED', 'F'],
  ['CHAIN_BROKEN', 'F'],
  ['KEY_WEAK', 'F'],
  ['SIG_WEAK', 'F'],
  ['CIPHER_INSECURE', 'F'],
  ['INSECURE_CIPHER_ACCEPTED', 'F'],
  ['CIPHER_WEAK', 'D'],
  ['MULTIPLE_SERIOUS', 'D'],
  ['NO_TLS12', 'C'],
  ['WEAK_CIPHER_ACCEPTED', 'C'],
  ['TLS10_ENABLED', 'B'],
  ['TLS11_ENABLED', 'B'],
  ['NO_PFS', 'B'],
  ['CIPHER_CBC', 'B'],
  ['NO_TLS13', 'A'],
  ['HSTS_MISSING', 'A'],
  ['HSTS_SHORT', 'A'],
  ['HSTS_NOT_CHECKED', 'A'],
  ['OCSP_STAPLING_MISSING', 'A'],
  ['PROFILE_PENDING', 'A'],
  ['PROFILE_FAILED', 'A'],
  ['PROFILE_PARTIAL', 'A'],
  ['KEY_2030', null],
]

/** İyiden kötüye notlar (backend `TlsGradeRules.GRADES`). */
export const TLS_GRADES = ['A+', 'A', 'B', 'C', 'D', 'F']

/** Notlanamama gerekçeleri (backend `TlsGradeRules.STATE_CODES`). */
export const TLS_STATE_CODES = ['MANUAL', 'NO_CHECK', 'CHECK_FAILED']
