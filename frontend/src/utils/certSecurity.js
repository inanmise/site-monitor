/**
 * Sertifika GÜVENLİK bayrakları — süreden bağımsız hüküm.
 *
 * <p>Karar backend'de tek yerde veriliyor (`CertificateHealthRules.securityFlags`) ve
 * `security_flags` alanıyla geliyor. Burada ikinci bir mantık YAZILMAZ: bu dosya yalnız
 * bayrakları okur ve metne çevirir. Aksi halde aynı sertifika sağlık listesinde kırmızı,
 * kartta yeşil görünebilir — kullanıcı hangisine inanacağını bilemez.
 *
 * <p>Neden gerekti: bitiş tarihi uzak olan her sertifika "Geçerli" sayılıyordu. Var olmayan bir
 * alan adı, NXDOMAIN-hijack ile bir modemin yönetim paneline çözüldüğünde, modemin kendinden
 * imzalı sertifikası yeşil "Geçerli" görünüyordu — oysa tarayıcı aynı adresi reddediyor.
 */

/** Backend bayrağı → i18n anahtarı. Bilinmeyen bayrak sessizce ATLANMAZ, ham hâliyle gösterilir. */
export const SECURITY_FLAG_KEYS = {
  HOSTNAME_MISMATCH: 'cert.sec.hostnameMismatch',
  UNTRUSTED_CA:      'cert.sec.untrustedCa',
}

export function securityFlags(cert) {
  return Array.isArray(cert?.security_flags) ? cert.security_flags : []
}

/** Bilinen bir güvenlik kusuru var mı. Bayrak YOKLUĞU kusur yokluğu demek değildir (UNKNOWN ≠ FAIL). */
export function isInsecure(cert) {
  return securityFlags(cert).length > 0
}

/** Rozetin başlık (tooltip) metni: hangi kusur(lar) yüzünden güvensiz sayıldığı. */
export function securityTitle(cert, t) {
  return securityFlags(cert)
    .map(f => (SECURITY_FLAG_KEYS[f] ? t(SECURITY_FLAG_KEYS[f]) : f))
    .join(' · ')
}
