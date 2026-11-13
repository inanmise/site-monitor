// Sayfa bütünlüğü sorunlu kaynakları — paylaşılan model (E4: insan-okur neden + ne yapmalı anahtarı; tonlar).
// Saf: i18n ANAHTARI döndürür, bileşen t() ile çevirir. page_resource_issues satırları
// {resource_url, resource_type, source_page, issue_type, first_party, http_status, duration_ms, checked_at} ile çalışır.

/** Sorun türü → metin rengi (PageMonitorPage ISSUE_TEXT ile birebir). */
export const ISSUE_TONE = {
  MIXED_CONTENT: 'text-amber-700 dark:text-amber-400',
  SLOW: 'text-sky-700 dark:text-sky-400',
  BLOCKED: 'text-stone-500 dark:text-stone-400',
  TIMEOUT: 'text-yellow-700 dark:text-yellow-500',
  BROKEN: 'text-red-700 dark:text-red-400',
}

/**
 * Bir sorunlu kaynağın NEDEN anahtarı (E4 — "nasıl bir dönüşten/hatadan"). BROKEN, HTTP durumuna göre incelir:
 * yanıt yok (DNS/TLS/bağlantı reddi), 404/410 (bulunamadı/kaldırılmış), 4xx (istemci), 5xx (sunucu),
 * 3xx (yönlendirme sorunu), diğer. i18n: page.issueReason.<KEY>.why / .fix
 */
export function issueReasonKey(issue) {
  const type = issue?.issue_type
  const s = issue?.http_status
  switch (type) {
    case 'MIXED_CONTENT': return 'MIXED_CONTENT'
    case 'TIMEOUT':       return 'TIMEOUT'
    // BLOCKED iki kaynaktan gelir (sunucu PageCheckerService.classifyStatus / verifyOne): HTTP durumu VARSA hedef sunucu
    // yanıt verdi ama reddetti / geçici olarak sunmadı (401/403/429/451/503 ve diğer 4xx — WAF, hız sınırı, bakım);
    // durum YOKSA istek hiç atılmadı (iç/özel IP güvenlik kuralı ya da üçüncü taraf adı kurum DNS'inde çözülmedi).
    case 'BLOCKED':       return s != null ? 'BLOCKED_HTTP' : 'BLOCKED'
    case 'SLOW':          return 'SLOW'
    case 'BROKEN':
      if (s == null) return 'BROKEN_NO_RESPONSE'
      if (s === 404 || s === 410) return 'BROKEN_404'
      if (s >= 500) return 'BROKEN_5XX'
      if (s >= 400) return 'BROKEN_4XX'
      if (s >= 300) return 'BROKEN_3XX'
      return 'BROKEN_OTHER'
    default: return 'OTHER'
  }
}

/** Bir kontrolün (checked_at) sorunlu kaynakları — yüklü issues listesinden eşleştirir (checked_at birebir eşit). */
export function issuesForCheckedAt(issues, checkedAt) {
  if (!Array.isArray(issues) || !checkedAt) return []
  return issues.filter(r => r && r.checked_at === checkedAt)
}

/**
 * Bulgu 7 (2026-11-13): kontrol geçmişi satırının sorunları — `?checkId=` yanıtından süzülür. Satır `check_id` taşıyorsa
 * kontrol kimliğiyle eşleşir (aynı saniyedeki iki kontrol karışmaz), taşımıyorsa `checked_at` ile. Eski sunucu `checkId`'yi
 * yok sayıp pencere listesini döndürürse de doğru satırlar kalır (pencere dışındaysa liste eksik kalabilir — bkz.
 * {@link expectedIssueCount}).
 */
export function issuesForCheck(rows, check) {
  if (!Array.isArray(rows) || !check) return []
  const id = check.id != null ? String(check.id) : null
  return rows.filter((r) => r && (r.check_id != null && id != null
    ? String(r.check_id) === id
    : r.checked_at === check.checked_at))
}

/**
 * Kontrolün sayaçlarından EN AZ kaç sorun satırı beklendiği (kırık + zaman aşımı + mixed; belirsiz / yavaş satırlar
 * sayaçta yok, bu yüzden "en az"). Sayaç okunamıyorsa null — "eksik" iddiası yalnız bilinen sayıyla yapılır.
 */
export function expectedIssueCount(check) {
  if (!check) return null
  const parts = [check.broken_resources, check.timeout_count, check.mixed_content_count]
  if (parts.every((v) => v == null)) return null
  return parts.reduce((s, v) => s + (Number.isFinite(Number(v)) && v != null ? Number(v) : 0), 0)
}
