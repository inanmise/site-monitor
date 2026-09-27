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

/**
 * Yalnız FORMÜL nötrleme (tırnaklama yok): `=`, `+`, `-`, `@`, sekme ya da CR ile başlayan metnin başına tek tırnak.
 * CSV dışındaki tablo çıktıları (panoya TSV kopyası — admin/sql/sqlUtils `rowsToTsv`) da AYNI kuralı buradan alır.
 * null/undefined → boş dize.
 */
export function neutraliseFormula(value) {
  if (value == null) return ''
  const s = String(value)
  const c0 = s[0]
  if (c0 === '=' || c0 === '+' || c0 === '-' || c0 === '@' || c0 === '\t' || c0 === '\r') {
    return "'" + s
  }
  return s
}

/** Formül nötrleme + tırnaklama uygulanmış hücre. null/undefined → boş dize. */
export function csvCell(value) {
  if (value == null) return ''
  const s = neutraliseFormula(value)
  if (s === '') return ''
  return /[",;\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

/** Satırları CSV gövdesine çevirir (CRLF satır sonu — Excel beklentisi). */
export function csvRows(rows) {
  return rows.map(r => r.map(csvCell).join(',')).join('\r\n')
}
