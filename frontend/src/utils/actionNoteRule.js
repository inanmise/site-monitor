/**
 * Gerekçe notu kuralı — backend `AlertActionNote` ile BİREBİR aynı (sahiplen / çöz, tekli ve toplu).
 *
 * <p>Neden ayrı ve "Java gibi" yazılı: arayüz anında geri bildirim verir, garanti sunucudadır. İki taraf ayrışırsa
 * kullanıcı arayüzde geçen bir notla 400 yer. Java'nın `String.trim()`'i yalnız U+0020 ve altındaki karakterleri
 * kırpar (NBSP'yi KIRPMAZ); `split("\\s+")` yalnız ASCII boşluklarında böler ([ \t\n\x0B\f\r] — NBSP ve Unicode
 * boşlukları kelime ayırıcı DEĞİL). JS `trim()` / `\s` ikisinde de daha geniş; aynı sonucu vermek için ikisi de
 * burada Java'nın tanımıyla yazıldı. Uzunluk UTF-16 kod birimi (Java `length()` ile aynı).
 *
 * <p>Kural: kırpılmış metin en az {@link NOTE_RULE.minChars} karakter VE en az {@link NOTE_RULE.minWords} kelime,
 * kelime = en az {@link NOTE_RULE.minWordLen} karakter. "a b c" ve "ok ok ok" (8 karakter) geçmez; "aaa bbb ccc"
 * geçer (bilinen sınır — anlamlılık bir metin kuralıyla zorlanamaz, caydırıcılık notun denetim günlüğüne adla
 * düşmesinden gelir).
 */
export const NOTE_RULE = Object.freeze({ minWords: 3, minWordLen: 2, minChars: 10 })

/** Java `String.trim()`: baştan ve sondan kod noktası ≤ U+0020 olan karakterleri kırpar. */
export function javaTrim(s) {
  const str = s == null ? '' : String(s)
  let start = 0
  let end = str.length
  while (start < end && str.charCodeAt(start) <= 0x20) start++
  while (end > start && str.charCodeAt(end - 1) <= 0x20) end--
  return str.slice(start, end)
}

/** Java `\s` (UNICODE_CHARACTER_CLASS kapalı): yalnız ASCII boşlukları. */
const JAVA_WS = /[ \t\n\v\f\r]+/

/**
 * Kuralın ayrıntılı durumu — canlı kontrol listesi bunu çizer.
 * @returns {{ trimmed: string, chars: number, words: number, charsOk: boolean, wordsOk: boolean, valid: boolean }}
 */
export function noteRuleState(note) {
  const trimmed = javaTrim(note)
  const chars = trimmed.length
  const words = trimmed ? trimmed.split(JAVA_WS).filter((w) => w.length >= NOTE_RULE.minWordLen).length : 0
  const charsOk = chars >= NOTE_RULE.minChars
  const wordsOk = words >= NOTE_RULE.minWords
  return { trimmed, chars, words, charsOk, wordsOk, valid: charsOk && wordsOk }
}

/** Sunucunun `AlertActionNote.isValid` karşılığı. null/boş → false. */
export function isNoteValid(note) {
  return noteRuleState(note).valid
}
