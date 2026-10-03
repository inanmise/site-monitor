/**
 * Push kanalı metin süzgeci — backend `service/PushText.pushSafe`'in AYNASI (2026-10-03, Giriş Yöntemleri push metni
 * düzenleyicisi). Kaynak gerçek SUNUCUDUR: telefona giden metni `UserPushService.sendDirect` süzer; bu dosya yalnız
 * önizlemenin ve "telefonda değişecek karakterler" uyarısının TAM OLARAK aynı sonucu göstermesi için var.
 *
 * Kanal ISO-8859-9 (Latin-5) taşır: U+0000–U+00FF (Ð Ý Þ ð ý þ HARİÇ — o konumlarda Ğ İ Ş ğ ı ş durur) + Ğ İ Ş ğ ı ş.
 * Anlamı olan tipografi karşılığına ÇEVRİLİR (`PUSH_TRANSLATE`), kalan taşınamaz karakter (emoji, vekil çiftler,
 * geometrik şekiller) DÜŞÜRÜLÜR; sonra ardışık ≥2 boşluk tek boşluğa iner (Java `\s` = [ \t\n\x0B\f\r]) ve uçlar
 * Java `trim()` gibi (≤ U+0020) kırpılır.
 *
 * Senkron kapısı: `test/pushSafeText.test.js` backend `PushText.java` içindeki TRANSLATE girdilerini okuyup bu
 * haritayla BİREBİR karşılaştırır; repertuvar kuralını backend `OtpPushTemplateTest` pinler.
 */

/** Kanalın taşıyamadığı ama ANLAMI olan işaretler → karşılığı (PushText.TRANSLATE ile birebir). */
export const PUSH_TRANSLATE = Object.freeze({
  '▸': '-', // ▸ şablon ayracı
  '▶': '-', // ▶
  '—': '-', // — em dash
  '–': '-', // – en dash
  '−': '-', // − minus
  '•': '-', // • bullet
  '…': '...', // … ellipsis
  '✓': 'OK', // ✓
  '✔': 'OK', // ✔
  '✗': 'X', // ✗
  '✘': 'X', // ✘
  '→': '->', // →
  '⇒': '->', // ⇒
  '≥': '>=', // ≥
  '≤': '<=', // ≤
  '“': '"', // “
  '”': '"', // ”
  '„': '"', // „
  '‘': "'", // ‘
  '’': "'", // ’
  '‚': "'", // ‚
  '₺': 'TL', // ₺
  '⚠': '!', // ⚠
})

/** Latin-5'in Latin-1'den farkı: bu altı konumda Türkçe harfler durur. */
const LATIN1_GAPS = new Set([0xd0, 0xdd, 0xde, 0xf0, 0xfd, 0xfe])
const TURKISH_EXTRA = new Set([0x11e, 0x11f, 0x130, 0x131, 0x15e, 0x15f])

/** Tek UTF-16 birimi kanalda kodlanabilir mi (Java `CharsetEncoder.canEncode(char)` ile aynı karar). */
export function canEncodeUnit(code) {
  return (code < 0x100 && !LATIN1_GAPS.has(code)) || TURKISH_EXTRA.has(code)
}

/** Java `String.trim()`: uçlardaki ≤ U+0020 karakterleri kırpar (JS `trim()` NBSP'yi de kırpardı). */
function javaTrim(s) {
  let a = 0
  let b = s.length
  while (a < b && s.charCodeAt(a) <= 0x20) a++
  while (b > a && s.charCodeAt(b - 1) <= 0x20) b--
  return s.slice(a, b)
}

/** Java `replaceAll("\\s{2,}", " ")` — Java `\s` Unicode boşluklarını (NBSP vb.) KAPSAMAZ. */
const JAVA_WS_RUN = /[ \t\n\v\f\r]{2,}/g

/** `PushText.pushSafe` aynası: çevir → düşür → boşluk topla → kırp. null/'' aynen döner. */
export function pushSafe(s) {
  if (s == null || s === '') return s
  let out = ''
  for (let i = 0; i < s.length; i++) {
    const ch = s[i]
    const mapped = PUSH_TRANSLATE[ch]
    if (mapped !== undefined) { out += mapped; continue }
    if (canEncodeUnit(s.charCodeAt(i))) out += ch
  }
  return javaTrim(out.replace(JAVA_WS_RUN, ' '))
}

/** `PushText.truncate` aynası: tavanı aşan metin kelime sınırından kesilir, sonuna "..." (kanal-güvenli). */
export function pushTruncate(s, max) {
  if (s == null || s.length <= max) return s
  let head = s.substring(0, Math.max(0, max - 3))
  const sp = head.lastIndexOf(' ')
  if (sp >= Math.floor(head.length / 2)) head = head.substring(0, sp)
  return javaTrim(head) + '...'
}

/** Emoji dizilerini birleştiren görünmez birimler (ZWJ, değişken seçici) — ayrı "düşen karakter" sayılmaz. */
const JOINERS = new Set([0x200d, 0xfe0e, 0xfe0f])

/**
 * Telefonda DEĞİŞECEK karakterlerin raporu (arayüz uyarısı): `{ text, converted: [{ from, to, count }],
 * dropped: [{ ch, count }] }`. Emoji gibi vekil çiftler TEK simge olarak raporlanır; görünmez birleştiriciler
 * (ZWJ, değişken seçici) raporlanmaz. `text` = `pushSafe(s)` (aynı karar).
 */
export function pushSafeReport(s) {
  const converted = new Map()
  const dropped = new Map()
  for (const sym of String(s ?? '')) {
    const cp = sym.codePointAt(0)
    const mapped = PUSH_TRANSLATE[sym]
    if (mapped !== undefined) {
      const c = converted.get(sym) || { from: sym, to: mapped, count: 0 }
      c.count++
      converted.set(sym, c)
      continue
    }
    const keep = sym.length === 1 && canEncodeUnit(cp)
    if (keep) continue
    if (JOINERS.has(cp)) continue   // görünmez birleştirici: düşer ama ayrı simge olarak raporlanmaz
    const d = dropped.get(sym) || { ch: sym, count: 0 }
    d.count++
    dropped.set(sym, d)
  }
  return { text: pushSafe(s ?? ''), converted: [...converted.values()], dropped: [...dropped.values()] }
}
