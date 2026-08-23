/**
 * CSV hücresi kaçışı — arayüzdeki tüm dışa aktarımların TEK kuralı.
 *
 * <p>Backend'deki {@code com.sitemonitor.util.Csv} ile AYNI davranış. İki sorunu birden çözer:
 *
 * <p>1) <b>Alan ayrımı</b> — virgül, noktalı virgül, tırnak veya satır sonu içeren değer
 * tırnaklanır, tırnaklar ikilenir. `\r` de sayılır: satırlar `\r\n` ile birleştiği için tek
 * başına bir CR satırı ortasından bölerdi (eski kaçışlar bunu kaçırıyordu).
 *
 * <p>2) <b>Formül enjeksiyonu (CWE-1236)</b> — `=`, `+`, `-`, `@` (ve sekme/CR) ile BAŞLAYAN bir
 * hücreyi Excel/LibreOffice FORMÜL sayar. `=cmd|'/c calc'!A1` biçiminde bir domain adı, not ya da
 * hata mesajı, dosyayı açan kişinin makinesinde komut çalıştırma denemesine dönüşür. Başa tek
 * tırnak konarak hücrenin METİN olduğu sabitlenir.
 *
 * <p><b>Neden ortak modül (2026-08-23 denetimi).</b> Koruma projede vardı ama yalnız backend'in
 * bir dışa aktarımında; arayüzdeki dört dışa aktarım (envanter, aktivite, SQL çalışma alanı,
 * sayfa kaynakları) kendi kaçışını yazmıştı ve hiçbirinde formül nötrlemesi yoktu. Kural tek
 * yerde durmazsa bir sonraki dışa aktarım yine korumasız yazılır.
 */

/** Formül nötrleme + tırnaklama uygulanmış hücre. null/undefined → boş dize. */
export function csvCell(value) {
  if (value == null) return ''
  let s = String(value)
  if (s === '') return ''

  const c0 = s[0]
  if (c0 === '=' || c0 === '+' || c0 === '-' || c0 === '@' || c0 === '\t' || c0 === '\r') {
    s = "'" + s
  }
  return /[",;\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

/** Satırları CSV gövdesine çevirir (CRLF satır sonu — Excel beklentisi). */
export function csvRows(rows) {
  return rows.map(r => r.map(csvCell).join(',')).join('\r\n')
}
