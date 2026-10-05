/**
 * Kontrol hata teşhisi KOD KATALOĞU (2026-10-05) — backend `CheckFailureReason` ile AYNI liste ve AYNI sıra
 * (`CheckFailureI18nGateTest.frontendCodeListMatches` iki listeyi karşılaştırır). Ping, Port, DNS, Sayfa Bütünlüğü,
 * Sayfa Hızı, Durum (envanter erişilebilirliği) ve Alan Adı geçmişinin tek kod dili; sertifika satırları da eski
 * `error_class` sınıfından bu kodlara eşlenir.
 *
 * i18n ad alanları (her kodun TR + EN metni ZORUNLU — backend kapısı arar):
 *   - `chkfail.<KOD>.short|why|effect|fix`
 *   - `chkfail.phase.<EVRE>` (evre etiketi)
 */
export const CHECK_FAILURE_CODES = [
  'SSRF_BLOCKED', 'CONFIG_ERROR', 'ICMP_UNAVAILABLE',
  'DNS_NXDOMAIN', 'DNS_SERVFAIL', 'DNS_REFUSED', 'DNS_TIMEOUT', 'DNS_NO_ANSWER', 'DNS_RESOLVE',
  'CONNECT_REFUSED', 'CONNECT_TIMEOUT', 'HOST_UNREACHABLE', 'PROXY_REFUSED', 'PROXY_ERROR',
  'TLS_HANDSHAKE', 'TLS_TRUST', 'TLS_HOSTNAME',
  'READ_TIMEOUT', 'CONNECTION_RESET', 'PROTOCOL_ERROR', 'HTTP_STATUS', 'REDIRECT_LIMIT', 'BANNER_MISMATCH', 'UDP_NO_REPLY',
  'ICMP_NO_REPLY',
  'RESOURCES_BROKEN', 'RESOURCES_TIMEOUT', 'MIXED_CONTENT',
  'RDAP_NOT_FOUND', 'RDAP_UNAVAILABLE', 'RDAP_RATE_LIMITED', 'WHOIS_UNAVAILABLE', 'NO_PUBLIC_REGISTRY', 'REGISTRY_NO_EXPIRY',
  'UNKNOWN',
]

export const CHECK_FAILURE_SET = new Set(CHECK_FAILURE_CODES)

/** Evreler — backend `CheckFailureReason.Phase` ile aynı. */
export const CHECK_FAILURE_PHASES = ['POLICY', 'DNS', 'CONNECT', 'TLS', 'REQUEST', 'RESPONSE', 'ICMP', 'CONTENT', 'REGISTRY']

/** Kod → evre (backend enum'undaki `phase` alanının aynası; eski satırda ayrıntı olmadığında da evre yazılabilsin). */
export const CHECK_FAILURE_PHASE = {
  SSRF_BLOCKED: 'POLICY', CONFIG_ERROR: 'POLICY', ICMP_UNAVAILABLE: 'POLICY',
  DNS_NXDOMAIN: 'DNS', DNS_SERVFAIL: 'DNS', DNS_REFUSED: 'DNS', DNS_TIMEOUT: 'DNS', DNS_NO_ANSWER: 'DNS', DNS_RESOLVE: 'DNS',
  CONNECT_REFUSED: 'CONNECT', CONNECT_TIMEOUT: 'CONNECT', HOST_UNREACHABLE: 'CONNECT', PROXY_REFUSED: 'CONNECT', PROXY_ERROR: 'CONNECT',
  TLS_HANDSHAKE: 'TLS', TLS_TRUST: 'TLS', TLS_HOSTNAME: 'TLS',
  READ_TIMEOUT: 'RESPONSE', CONNECTION_RESET: 'RESPONSE', PROTOCOL_ERROR: 'RESPONSE', HTTP_STATUS: 'RESPONSE',
  REDIRECT_LIMIT: 'RESPONSE', BANNER_MISMATCH: 'RESPONSE', UDP_NO_REPLY: 'RESPONSE',
  ICMP_NO_REPLY: 'ICMP',
  RESOURCES_BROKEN: 'CONTENT', RESOURCES_TIMEOUT: 'CONTENT', MIXED_CONTENT: 'CONTENT',
  RDAP_NOT_FOUND: 'REGISTRY', RDAP_UNAVAILABLE: 'REGISTRY', RDAP_RATE_LIMITED: 'REGISTRY', WHOIS_UNAVAILABLE: 'REGISTRY',
  NO_PUBLIC_REGISTRY: 'REGISTRY', REGISTRY_NO_EXPIRY: 'REGISTRY',
  UNKNOWN: 'REQUEST',
}

/** Ayar / politika / ortam kökenli nedenler — "hedef çöktü" değil "kurulumu düzelt" (uyarı tonu). */
export const CHECK_FAILURE_WARN_CODES = new Set([
  'SSRF_BLOCKED', 'CONFIG_ERROR', 'ICMP_UNAVAILABLE', 'MIXED_CONTENT', 'RESOURCES_TIMEOUT', 'NO_PUBLIC_REGISTRY',
  'REGISTRY_NO_EXPIRY', 'RDAP_RATE_LIMITED',
])

/** Geçmiş satırlarının türleri (model ve panel bu adları kullanır). */
export const CHECK_TYPES = ['ping', 'port', 'dns', 'page', 'pagespeed', 'uptime', 'domain', 'cert']
