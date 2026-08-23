/**
 * Sunucuya gönderilen ZAMAN SINIRLARI — tek doğruluk noktası.
 *
 * <p><b>Sözleşme.</b> Backend zaman damgalarını UTC yazar
 * ({@code MonitorHistoryService}/{@code AuditService}/{@code ActivityLogService} hepsi
 * {@code DateTimeFormatter…withZone(ZoneOffset.UTC)}) ve kolonlar METİN'dir; sorgular da metin
 * karşılaştırması yapar. Arayüz bu değerleri gösterirken yerele çevirir
 * ({@code formatDateSec} → {@code toUtc}). Dolayısıyla bir tarih süzgeci:
 *
 * <ol>
 *   <li>sınırı KULLANICININ YEREL takviminden kurar ("bugün" = yerel gece yarısı),</li>
 *   <li>sunucuya UTC olarak gönderir.</li>
 * </ol>
 *
 * <p><b>Neden ayrı bir modül (2026-08-23'te yaşanan hata).</b> İzleme Değişiklikleri konsolunda
 * aralık yerel saat bileşenleriyle dizeye çevriliyordu. Türkiye UTC+3 olduğu için "Bugün"
 * penceresi üç saat geç başlıyor, gecenin ilk üç saatinde yapılan her değişiklik listeden
 * SESSİZCE düşüyordu — ekran çalışıyor, hata vermiyor, sadece eksik gösteriyordu. Kural tek
 * satırlık ama her ekranda yeniden yazılmaya açık; burada tutulup teste bağlandı.
 */

/** Date → sunucunun beklediği biçim: UTC, saniye hassasiyetinde, ek YOK ("2026-08-23T00:00:00"). */
export function toApiTime(date) {
  if (!(date instanceof Date) || Number.isNaN(date.getTime())) return ''
  return date.toISOString().slice(0, 19)
}

/** Kullanıcının yerel gününün başlangıcı (00:00:00.000). */
export function startOfLocalDay(date = new Date()) {
  const d = new Date(date.getTime())
  d.setHours(0, 0, 0, 0)
  return d
}

/**
 * "Son N gün" penceresinin başlangıcı — BUGÜN DÂHİL sayılır.
 *
 * <p>7 gün = bugün + önceki 6 gün. Kullanıcı "son 7 gün" derken bugünü hariç tutmayı beklemez;
 * hariç tutulsaydı bugünkü değişiklikler pencereye girmezdi.
 */
export function startOfLastNDays(days, now = new Date()) {
  const d = startOfLocalDay(now)
  d.setDate(d.getDate() - (Math.max(1, Number(days) || 1) - 1))
  return d
}
