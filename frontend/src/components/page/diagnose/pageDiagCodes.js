/**
 * Sayfa Bütünlüğü + Sayfa Hızı uçtan uca tanılamasının BULGU KATALOGLARI (2026-10-05) — backend ile AYNI listeler
 * (`PageDiagFindings.CODES`, `PageSpeedDiagFindings.CODES`), içe aktarımsız (HTTP tanılama modeli de okur; döngüsel içe
 * aktarım olmasın diye ayrı dosya). i18n ad alanları:
 *   - PG_FINDING_CODES → `pgdx.finding.<KOD>.title|body`
 *   - PS_FINDING_CODES → `psdx.finding.<KOD>.title|body`
 * Backend kapısı (`PageDiagFindingsI18nGateTest`) her kodun TR + EN metnini arar; kodlar HTTP / keyword kataloglarıyla
 * çakışmaz (çeviri ad alanı koddan seçilir).
 */
export const PG_FINDING_CODES = [
  'PAGE_OK', 'PAGE_DOWN', 'PAGE_HTTP_STATUS', 'PAGE_BODY_UNREAD', 'RESOURCES_BROKEN', 'RESOURCES_TIMEOUT', 'MIXED_CONTENT',
  'SAME_HOST_BROKEN', 'THIRD_PARTY_ONLY', 'RESOURCES_BLOCKED', 'RESOURCES_SLOW', 'CRAWL_LIMIT',
]

export const PS_FINDING_CODES = [
  'PAGESPEED_OK', 'PAGESPEED_DOWN', 'PAGESPEED_HTTP_STATUS', 'PAGESPEED_BODY_UNREAD', 'THRESHOLD_BREACH',
  'SLOW_DNS', 'SLOW_CONNECT', 'SLOW_TLS', 'SLOW_SERVER', 'SLOW_DOWNLOAD', 'LARGE_BODY', 'SLOW_RESOURCES', 'HEAVY_RESOURCES',
  'PROXY_SLOWER', 'DIRECT_SLOWER', 'MEASUREMENT_PARTIAL', 'RESOURCES_FAILED',
]

export const PG_FINDING_SET = new Set(PG_FINDING_CODES)
export const PS_FINDING_SET = new Set(PS_FINDING_CODES)

/** Tanılama uçlarının yolları — 429 tespiti son başarısız çağrı halkasında bu yollara bakar (türler karışmaz). */
export const PAGE_DIAG_PATH = /\/monitoring\/page\/\d+\/diagnose/
export const PAGESPEED_DIAG_PATH = /\/monitoring\/pagespeed\/\d+\/diagnose/
