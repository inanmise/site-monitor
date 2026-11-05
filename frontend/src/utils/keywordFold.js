/**
 * Anahtar kelime eşleşmesinin TEK harf katlama kuralı — sunucudaki `service/keyword/KeywordFold.java`'nın birebir eşi
 * (kw sözleşmesi §4.1, 2026-11-05: "Türkçe harf katlama HERKESTE"). Saf, durumsuz, doğrusal.
 *
 * <p>Büyük/küçük harf DUYARSIZ aramada iki taraf da {@link fold} ile katlanır: İ (U+0130), I, ı (U+0131) ve i → "i";
 * diğer her karakter tek tek küçültülür (Java `Character.toLowerCase(char)` gibi 1:1). Katlama UZUNLUĞU KORUR: katlanmış
 * metindeki konum özgün metindeki konumdur — vurgulama (kart alıntısı, hata paneli, eşleşme bağlamı) bu yüzden özgün
 * metni dilimleyebilir. Aksan katlaması YOK ("ş" ≠ "s").
 *
 * <p>NEDEN elle döngü: `'İ'.toLowerCase()` iki karakter ("i" + U+0307) döndürür → hem uzunluk bozulur hem de "İstanbul"
 * ile "istanbul" eşleşmez; `i` bayraklı RegExp de Türkçe İ/ı'yı ayrı harf sayar (eski `highlightParts` bu yüzden
 * kaçırıyordu, B3). Tek karakterin küçük hâli birden çok karaktere çıkarsa (yalnız U+0130 — elle ele alındı) karakter
 * olduğu gibi kalır: Java'nın basit eşlemesi de uzunluğu korur. Tam metin `toLowerCase` KULLANILMAZ: bağlama duyarlı
 * kurallar (Yunanca son sigma) Java'nın karakter karakter sonucundan ayrılırdı.
 *
 * <p>Doğruluk tablosu Java ile ortak: `KeywordFoldTest.TABLE` (test `keywordFold.test.js` kaynak dosya varsa onu okur).
 */

/** Sayım üst sınırı (TEXT kuralları) — `KeywordFold.MAX_COUNT`. */
export const MAX_COUNT = 1_000_000

/** Tek karakter (UTF-16 birimi) katlama. */
export function foldChar(c) {
  if (c === 'I' || c === 'i' || c === 'İ' || c === 'ı') return 'i'
  const l = c.toLowerCase()
  return l.length === 1 ? l : c
}

/** Metni katlar (uzunluk korunur); null / undefined → aynen döner. */
export function fold(s) {
  if (s == null) return s
  const str = String(s)
  let out = ''
  for (let i = 0; i < str.length; i++) out += foldChar(str[i])
  return out
}

/**
 * Örtüşmesiz sayım (`KeywordFold.count`): `{ count, firstIndex }`. `caseSensitive=false` ise iki taraf katlanır. Boş /
 * null iğne ya da `max < 1` → `{ count: 0, firstIndex: -1 }`. En çok `max` sayılır.
 */
export function countMatches(hay, needle, caseSensitive = false, max = MAX_COUNT) {
  if (hay == null || needle == null || needle === '' || !(max >= 1)) return { count: 0, firstIndex: -1 }
  const h = caseSensitive ? String(hay) : fold(String(hay))
  const n = caseSensitive ? String(needle) : fold(String(needle))
  let count = 0
  let first = -1
  let from = 0
  let idx
  while (count < max && (idx = h.indexOf(n, from)) >= 0) {
    if (first < 0) first = idx
    count++
    from = idx + n.length
  }
  return { count, firstIndex: first }
}

/**
 * Metni vurgu parçalarına böler: `[{ text, match }]` — eşleşmeler örtüşmesiz, soldan sağa (sayımla aynı kural). Boş iğne →
 * tek parça. Katlama uzunluğu koruduğu için parçalar ÖZGÜN metinden dilimlenir (yazım biçimi korunur: "İstanbul" vurgusu
 * "İstanbul" kalır). En çok `maxMatches` eşleşme işaretlenir (uzun alıntıda sınırsız parça üretilmesin).
 */
export function matchParts(text, needle, caseSensitive = false, maxMatches = 500) {
  const hay = String(text ?? '')
  const n = String(needle ?? '')
  if (!hay) return []
  if (!n) return [{ text: hay, match: false }]
  const h = caseSensitive ? hay : fold(hay)
  const k = caseSensitive ? n : fold(n)
  const out = []
  let last = 0
  let marked = 0
  let idx
  while (marked < maxMatches && (idx = h.indexOf(k, last)) >= 0) {
    if (idx > last) out.push({ text: hay.slice(last, idx), match: false })
    out.push({ text: hay.slice(idx, idx + k.length), match: true })
    last = idx + k.length
    marked++
  }
  if (last < hay.length) out.push({ text: hay.slice(last), match: false })
  return out
}
