/**
 * Keyword hata teşhisinin KOD KATALOGLARI (2026-10-04) — backend ile AYNI listeler, içe aktarımsız (HTTP tanılama modeli de
 * okur; döngüsel içe aktarım olmasın diye ayrı dosya). i18n ad alanları:
 *   - KW_FAILURE_CODES  → `kwfail.<KOD>.short|why|effect|fix`   (KeywordFailureClassifier.CODES)
 *   - KW_HINT_CODES     → `kwhint.<KOD>.title|cause|effect|fix`  (KeywordBodyAnalyzer.HINT_CODES)
 *   - KW_FINDING_CODES  → `kwdx.finding.<KOD>.title|body`        (KeywordDiagFindings.CODES)
 * Backend kapısı (`KeywordDiagFindingsI18nGateTest`) her kodun TR + EN metnini arar.
 */
export const KW_FAILURE_CODES = [
  'KEYWORD_NOT_FOUND', 'KEYWORD_FOUND_FORBIDDEN', 'KEYWORD_COUNT_MISMATCH', 'HTTP_STATUS', 'EMPTY_BODY', 'BODY_TRUNCATED',
  'REDIRECT_BLOCKED',
  // kw zenginleştirmesi (2026-11-05, sözleşme §11.2): son URL / yönlendirme (be1) + kurallar / kapsam / boyut (be2) — Java sırası
  'FINAL_URL_MISMATCH', 'REDIRECT_NOT_FOLLOWED', 'RULES_NOT_MET', 'SCOPE_NOT_FOUND', 'SCOPE_INVALID', 'BODY_TOO_SMALL', 'BODY_TOO_LARGE',
  'TIMEOUT_CONNECT', 'TIMEOUT_READ', 'DNS', 'TLS_HANDSHAKE', 'TLS_CERT', 'CONNECTION_REFUSED',
  'CONNECTION_RESET', 'HOST_UNREACHABLE', 'PROXY', 'SSRF_BLOCKED', 'CONFIG_ERROR', 'REDIRECT_LIMIT', 'PROTOCOL_ERROR', 'UNKNOWN',
]

export const KW_HINT_CODES = [
  'CASE_MISMATCH', 'WHITESPACE_OR_ENTITY', 'CHARSET', 'WAF_OR_BLOCK_PAGE', 'LOGIN_PAGE', 'JS_RENDERED',
  'REDIRECTED_ELSEWHERE', 'MAINTENANCE_PAGE', 'ERROR_PAGE', 'NON_TEXT_CONTENT',
]

export const KW_FINDING_CODES = [
  'KEYWORD_OK', 'KEYWORD_NOT_FOUND', 'KEYWORD_FOUND_FORBIDDEN', 'KEYWORD_COUNT_MISMATCH', 'KEYWORD_HTTP_ERROR',
  'KEYWORD_EMPTY_BODY', 'KEYWORD_BODY_TRUNCATED', 'KEYWORD_REDIRECT_BLOCKED', 'KEYWORD_BODY_UNREAD', 'KEYWORD_SLOW',
  // kw zenginleştirmesi (2026-11-05, sözleşme §10.5): son URL (be1) + kural motoru / boyut eşliği (be2 Aşama 2). Metni olmayan
  // kod tanılamada "bilinmeyen bulgu" metniyle çizilir (ham anahtar değil) — metinleri kodu ekleyen arka uç ajanı yazar.
  'KEYWORD_FINAL_URL_MISMATCH', 'KEYWORD_RULES_NOT_MET', 'KEYWORD_SIZE_MISMATCH',
]

export const KW_HINT_SET = new Set(KW_HINT_CODES)
export const KW_FINDING_SET = new Set(KW_FINDING_CODES)

/** Keyword tanılama ucunun yolu — 429 tespiti son başarısız çağrı halkasında bu yola bakar (HTTP'ninkiyle karışmaz). */
export const KEYWORD_DIAG_PATH = /\/monitoring\/keyword\/\d+\/diagnose/
